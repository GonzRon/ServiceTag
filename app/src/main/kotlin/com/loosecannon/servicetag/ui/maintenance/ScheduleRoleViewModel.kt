package com.loosecannon.servicetag.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.NoSuchSchedule
import com.loosecannon.servicetag.core.usecase.SeasonStartBadDate
import com.loosecannon.servicetag.core.usecase.SeasonStartRefusal
import com.loosecannon.servicetag.core.usecase.SeasonStartRefused
import com.loosecannon.servicetag.core.usecase.SetScheduleRole
import com.loosecannon.servicetag.di.AppGraph
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * #114 (C33) — the role confirm, open: P114-4 (an asset's schedule) or P114-4g (a group's) as [question], the date
 * field (P114-6) holding [sinceOn] as typed — today by default, or the stored day when the role's date is being moved
 * (O-4) — and, on a one-tap schedule, the note P114-5 ([quickNote], O-2). [quickElected] is what the confirm passes:
 * true exactly when the dialog shows P114-5, so the confirm **is** the owner's explicit election (C10a) and is never
 * defaulted. [problem] is the refusal's sentence, drawn under the field.
 */
data class SeasonStartConfirm(
    val title: String,
    val targetName: String,
    val group: Boolean,
    val sinceOn: String,
    val quickNote: String?,
    val quickElected: Boolean,
    val problem: String? = null,
) {
    val question: String get() = SeasonStartStrings.question(title, targetName, group, sinceOn)
}

/**
 * #114 (C33) — the Season Start role on the schedule detail: the role line P114-41 ([roleLine]), the action "Use as
 * Season Start service" (P114-2, [canUse]) or "Stop using as Season Start service" (P114-3, [canStop]), and the confirm
 * while it is open. An archived schedule is offered only the clear, which `SetScheduleRole` allows.
 */
data class ScheduleRoleState(
    val loaded: Boolean = false,
    val role: ScheduleRole = ScheduleRole.NONE,
    val roleLine: String? = null,
    val archived: Boolean = false,
    val busy: Boolean = false,
    val confirm: SeasonStartConfirm? = null,
) {
    val canUse: Boolean get() = loaded && !archived && role == ScheduleRole.NONE
    val canStop: Boolean get() = loaded && role == ScheduleRole.SEASON_START

    /** The role line opens the same confirm to move the day services count from: asked again, P114-5 included. */
    val canMove: Boolean get() = loaded && !archived && role == ScheduleRole.SEASON_START
}

/**
 * #114 (C33, K11) — the Season Start role's own action on the schedule detail, beside [ScheduleDetailViewModel]: the
 * role is set and cleared here through [SetScheduleRole] and **never** by the editor's save, which keeps the stored role.
 *
 * Every set goes through the confirm, a move of the date included, so a one-tap schedule's note P114-5 is shown and its
 * election passed (`quickElected = true`) each time — `SetScheduleRole` asks for it again on every QUICK set (O-2, C10a).
 * A refusal keeps the confirm open with its sentence ([SeasonStartStrings.refused], P114-28 for a date that is not
 * one); `QUICK_NOT_ELECTED` — the schedule turned one-tap while the confirm was open — shows P114-5 and makes the next
 * confirm the election. The state follows the stored schedule while the model lives, so an archive or restore on the
 * detail is reflected without a resume.
 */
class ScheduleRoleViewModel(
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val setScheduleRole: SetScheduleRole,
    private val today: Today,
    private val scheduleId: ScheduleId,
) : ViewModel() {

    constructor(graph: AppGraph, scheduleId: String) : this(
        graph.schedules, graph.assets, graph.groups, graph.setScheduleRole, graph.today, ScheduleId(scheduleId),
    )

    private val _state = MutableStateFlow(ScheduleRoleState())
    val state: StateFlow<ScheduleRoleState> = _state.asStateFlow()

    init {
        viewModelScope.launch { schedules.observeAll().collect { load() } }
    }

    /** Re-reads the stored schedule; the screen calls it on resume. */
    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val schedule = schedules.get(scheduleId)
        _state.update {
            it.copy(
                loaded = true,
                role = schedule?.role ?: ScheduleRole.NONE,
                roleLine = schedule?.let(::roleLineOf),
                archived = schedule?.status == ScheduleStatus.ARCHIVED,
            )
        }
    }

    private fun roleLineOf(schedule: MaintenanceSchedule): String? = when (schedule.role) {
        ScheduleRole.NONE -> null
        ScheduleRole.SEASON_START -> schedule.roleSinceOn?.let(SeasonStartStrings::roleLine)
    }

    /** "Use as Season Start service", or the role line to move its day: opens the confirm. */
    fun ask() {
        val now = _state.value
        if (now.busy || (!now.canUse && !now.canMove)) return
        viewModelScope.launch {
            val schedule = schedules.get(scheduleId) ?: return@launch
            val sinceOn = schedule.roleSinceOn ?: today.localDate().toString()
            _state.update { it.copy(confirm = confirmFor(schedule, sinceOn)) }
        }
    }

    private suspend fun confirmFor(schedule: MaintenanceSchedule, sinceOn: String): SeasonStartConfirm {
        val target = schedule.target
        val group = target is ScheduleTarget.GroupTarget
        val targetName = when (target) {
            is ScheduleTarget.AssetTarget -> assets.get(target.assetId)?.name
            is ScheduleTarget.GroupTarget -> groups.get(target.groupId)?.name
        }.orEmpty()
        val quick = when (schedule.completionMode) {
            CompletionMode.FORM -> false
            CompletionMode.QUICK -> true
        }
        return SeasonStartConfirm(
            title = schedule.title,
            targetName = targetName,
            group = group,
            sinceOn = sinceOn,
            quickNote = SeasonStartStrings.quickNote().takeIf { quick },
            quickElected = quick,
        )
    }

    /** The date field (P114-6), as typed; a new day takes the last refusal's sentence down. */
    fun onSinceOn(value: String) = _state.update { it.copy(confirm = it.confirm?.copy(sinceOn = value, problem = null)) }

    /** Cancel: the confirm closes and nothing is written. */
    fun dismiss() = _state.update { it.copy(confirm = null) }

    /** The confirm's button: `SetScheduleRole` with the day as typed and the election the dialog showed. */
    fun confirm() {
        val open = _state.value.confirm ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val failure = runCatching {
                setScheduleRole.run(scheduleId, ScheduleRole.SEASON_START, open.sinceOn.trim(), quickElected = open.quickElected)
            }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            val next = when (failure) {
                null -> null
                is SeasonStartBadDate -> open.copy(problem = SeasonStartStrings.badDate())
                is SeasonStartRefused -> refusedConfirm(open, failure.reason)
                // Gone meanwhile: there is nothing left to confirm, and the screen re-derives.
                is NoSuchSchedule -> null
                // Anything else wrote nothing either; the confirm stays open as typed — never a silent close — so the
                // owner can try again or cancel. (A Throwable is not a sealed set: this arm is every other failure.)
                else -> open
            }
            load()
            _state.update { it.copy(busy = false, confirm = next) }
        }
    }

    private suspend fun refusedConfirm(open: SeasonStartConfirm, reason: SeasonStartRefusal): SeasonStartConfirm {
        val name = SeasonStartStrings.assetOf(reason)?.let { assets.get(it)?.name }
        val sentence = SeasonStartStrings.refused(reason, name, throughAGroup = open.group)
        // The schedule became one-tap behind the open confirm: P114-5 is said now, and the next confirm is the election.
        val elect = reason == SeasonStartRefusal.QuickNotElected
        return open.copy(problem = sentence, quickElected = open.quickElected || elect)
    }

    /** "Stop using as Season Start service": the role and its day are cleared; no confirm (§5 has none). */
    fun stop() {
        val now = _state.value
        if (now.busy || !now.canStop) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val failure = runCatching { setScheduleRole.run(scheduleId, ScheduleRole.NONE) }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            load()
            _state.update { it.copy(busy = false) }
        }
    }
}
