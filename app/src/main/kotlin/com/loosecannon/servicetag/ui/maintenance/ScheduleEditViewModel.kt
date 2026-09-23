package com.loosecannon.servicetag.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.usecase.SaveSchedule
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.ScheduleProblem
import com.loosecannon.servicetag.core.usecase.ScheduleValidation
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.reminders.NotificationPermission
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The names [ScheduleEditState.problems] marks a control by — one per field of the form, so a
 * refused save marks the control the refusal is actually about, in the shipped editors' idiom.
 *
 * **They are field names, never sentences.** §17 ratifies no wording for a refused schedule save,
 * and no brief invents one, so the form marks the field and says nothing — see [fieldOf].
 */
object ScheduleField {
    const val TITLE = "title"
    const val INTERVAL = "interval"
    const val UNIT = "unit"
    const val ANCHOR = "anchor"
    const val LEAD = "lead"
    const val METER = "meter"
    const val METER_INTERVAL = "meterInterval"
    const val METER_LEAD = "meterLead"
    const val SEASON = "season"
    const val COMPLETION_MODE = "completionMode"
    const val PROFILE = "profile"
    const val PROVIDER = "provider"
    const val TARGET = "target"
}

/**
 * Which control a refusal belongs to. A pure function, so the mapping is asserted by a JVM test
 * rather than inferred from a screenshot.
 *
 * Every member maps somewhere: an unmapped problem would be a refused save with nothing marked,
 * which is the dead end the target-choice matrix row is about.
 */
fun fieldOf(problem: ScheduleProblem): String = when (problem) {
    ScheduleProblem.TargetInvalid -> ScheduleField.TARGET
    ScheduleProblem.EmptyGroupTarget -> ScheduleField.TARGET
    ScheduleProblem.NoRuleSide -> ScheduleField.INTERVAL
    ScheduleProblem.TimeIntervalNotPositive -> ScheduleField.INTERVAL
    ScheduleProblem.TimeUnitRequired -> ScheduleField.UNIT
    ScheduleProblem.AnchorRequired -> ScheduleField.ANCHOR
    ScheduleProblem.BadAnchorDate -> ScheduleField.ANCHOR
    ScheduleProblem.NegativeLeadDays -> ScheduleField.LEAD
    ScheduleProblem.MeterRuleOnGroupTarget -> ScheduleField.METER
    ScheduleProblem.MeterIntervalRequired -> ScheduleField.METER_INTERVAL
    ScheduleProblem.MeterIntervalNotPositive -> ScheduleField.METER_INTERVAL
    ScheduleProblem.NegativeMeterLead -> ScheduleField.METER_LEAD
    is ScheduleProblem.ForeignMeterDefinition -> ScheduleField.METER
    is ScheduleProblem.MeterDefinitionNotAMeter -> ScheduleField.METER
    ScheduleProblem.SeasonFollowsAssetOnGroupTarget -> ScheduleField.SEASON
    ScheduleProblem.FormCompletionOnGroupTarget -> ScheduleField.COMPLETION_MODE
    ScheduleProblem.ProfileOnGroupTarget -> ScheduleField.PROFILE
    is ScheduleProblem.ForeignProfile -> ScheduleField.PROFILE
    is ScheduleProblem.UnknownProvider -> ScheduleField.PROVIDER
    ScheduleProblem.PostponeNeedsTimeRule -> ScheduleField.INTERVAL
}

/**
 * The schedule form. Numbers are held as text until a save parses them, exactly as the shipped
 * editors do: a half-typed "1" or an emptied lead is a state a form has to be able to sit in.
 *
 * **[target] is one value, not two ids**, which is what makes invariant 1 unreachable through this
 * screen rather than merely refused by the command: there is no state in which both a target asset
 * and a target group are set, whatever the owner taps. The choice is made once — by the entry point
 * that opened the editor — and an edit keeps the stored target, so it is never editable afterwards.
 *
 * The three [isGroup] hides are the D-12 and D-28 rules made structural: a group target draws no
 * meter block, no profile picker and no `FOLLOW_ASSET` option at all.
 */
data class ScheduleEditState(
    val target: ScheduleTarget? = null,
    val targetName: String = "",
    val title: String = "",
    val description: String = "",
    val timeInterval: String = "",
    val timeUnit: RecurrenceUnit = RecurrenceUnit.MONTH,
    val timeBasis: TimeBasis = TimeBasis.FIXED,
    val anchorOn: String = "",
    val leadDays: String = "0",
    val meterDefinitionId: DefinitionId? = null,
    val meterInterval: String = "",
    val anchorMeter: String = "",
    val meterLead: String = "",
    val seasonBehavior: SeasonBehavior = SeasonBehavior.IGNORE,
    val completionMode: CompletionMode = CompletionMode.QUICK,
    val profileId: ProfileId? = null,
    val remindersEnabled: Boolean = true,
    /**
     * The one provider this release has (decision 8). It is **not settable from the screen**: §17.1c
     * ratifies the row's label and no option word, so there is nothing for a second choice to be
     * called, and with one member the single-choice row of #4 is a choice of one. Kept as state
     * rather than hardcoded at the command, so #25's multi-provider UI is additive: the seam is
     * here, not at the command.
     *
     * **What a stored row naming an unknown provider does, precisely:** the loader maps it to
     * `LOCAL` (`?: ProviderId.LOCAL` below), so saving rewrites it. That is unreachable in 1.2 —
     * `ProviderId` has exactly one member, and nothing writes another name — and it is the one
     * line #25 has to revisit when a second provider exists, because a phone running an older
     * build must not silently re-point a schedule at itself. It is recorded here rather than
     * guarded now: a guard for a value that cannot exist is a branch no test can reach.
     */
    val provider: ProviderId? = ProviderId.LOCAL,
    /** The asset's meter definitions, offered only for an asset target (D-12). */
    val meters: List<MeasurementDefinition> = emptyList(),
    /** The asset's quick actions, offered only for a `FORM` asset target (D-12). */
    val profiles: List<EventProfile> = emptyList(),
    val problems: List<ScheduleProblem> = emptyList(),
    /** D-11's non-blocking line: shown, and the schedule still saves. */
    val duplicateWarning: Boolean = false,
    /** The permission rationale is open, which happens once and on a create only (#24 AC 1). */
    val askingForNotifications: Boolean = false,
    val editing: Boolean = false,
    val saving: Boolean = false,
    val loaded: Boolean = false,
) {
    val isGroup: Boolean get() = target is ScheduleTarget.GroupTarget

    /** Which controls a refused save marked. Never a sentence — see [ScheduleField]. */
    val marks: Set<String> get() = problems.map(::fieldOf).toSet()

    /** A meter rule is present when a definition is chosen, which a group target can never be. */
    val hasMeterRule: Boolean get() = meterDefinitionId != null && !isGroup
}

/**
 * Create ([scheduleId] null) or edit one schedule, against **one asset or one group**.
 *
 * Every rule is `SaveSchedule`'s, called: this view model constructs the command, sends it and maps
 * whatever comes back onto the control it belongs to. What it adds is the three target-dependent
 * hides that make an illegal group schedule unreachable through the UI, D-11's non-blocking warning
 * and — on the **first** schedule creation only — the notification permission request.
 *
 * The permission request lives here and nowhere else (master plan decision 23, #24 AC 1). "First"
 * is derived from the store rather than a remembered flag: a create is the first one when the store
 * held no schedule before it. That is the same fact a persisted "already asked" flag would carry,
 * with nothing to drift out of step, and it makes both halves of the matrix row true by
 * construction — never at launch, and never again once a schedule exists.
 */
class ScheduleEditViewModel(
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val saveSchedule: SaveSchedule,
    private val notifications: NotificationPermission,
    private val today: Today,
    private val scheduleId: ScheduleId?,
    private val targetAssetId: AssetId?,
    private val targetGroupId: GroupId?,
) : ViewModel() {

    constructor(
        graph: AppGraph,
        scheduleId: String?,
        targetAssetId: String?,
        targetGroupId: String?,
    ) : this(
        graph.schedules, graph.assets, graph.groups, graph.definitions, graph.profiles,
        graph.saveSchedule, graph.notificationPermission, graph.today,
        scheduleId?.let(::ScheduleId),
        targetAssetId?.let(::AssetId),
        // Both ids arriving is a serialised back stack from an older process or a hand-built key.
        // The asset wins and the group is dropped rather than both being carried into a command the
        // engine would refuse: one target is the only representable state (invariant 1).
        targetGroupId?.takeIf { targetAssetId == null }?.let(::GroupId),
    )

    private val _state = MutableStateFlow(ScheduleEditState(editing = scheduleId != null))
    val state: StateFlow<ScheduleEditState> = _state.asStateFlow()

    /** One shot per successful save; the screen that started it is told to leave, once. */
    private val _saved = MutableSharedFlow<ScheduleId>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<ScheduleId> = _saved.asSharedFlow()

    init {
        viewModelScope.launch {
            val existing = scheduleId?.let { schedules.get(it) }
            // An edit keeps the stored target, whatever the route carried: the choice is made once.
            val target = existing?.target ?: targetAssetId?.let(ScheduleTarget::AssetTarget)
                ?: targetGroupId?.let(ScheduleTarget::GroupTarget)
            val assetId = (target as? ScheduleTarget.AssetTarget)?.assetId
            _state.update { form ->
                (existing?.let { form.filledFrom(it) } ?: form.blank()).copy(
                    target = target,
                    targetName = nameOf(target),
                    // A group target is offered neither, so nothing is read for one (D-12).
                    meters = assetId?.let { definitions.forAsset(it).filter { d -> d.isMeter && d.archivedAt == null } }
                        .orEmpty(),
                    profiles = assetId?.let { profiles.forAsset(it).filter { p -> p.archivedAt == null } }
                        .orEmpty(),
                    loaded = true,
                )
            }
            refreshDuplicateWarning()
        }
    }

    private suspend fun nameOf(target: ScheduleTarget?): String = when (target) {
        is ScheduleTarget.AssetTarget -> assets.get(target.assetId)?.name.orEmpty()
        is ScheduleTarget.GroupTarget -> groups.get(target.groupId)?.name.orEmpty()
        null -> ""
    }

    /** A new schedule opens anchored on today: the one date that is never a guess. */
    private fun ScheduleEditState.blank() = copy(anchorOn = today.localDate().toString())

    private fun ScheduleEditState.filledFrom(row: MaintenanceSchedule) = copy(
        title = row.title,
        description = row.description,
        timeInterval = row.timeInterval?.toString().orEmpty(),
        timeUnit = row.timeUnit ?: RecurrenceUnit.MONTH,
        timeBasis = row.timeBasis,
        anchorOn = row.anchorOn.orEmpty(),
        leadDays = row.leadDays.toString(),
        meterDefinitionId = row.meterDefinitionId,
        meterInterval = row.meterInterval?.let(::plainNumber).orEmpty(),
        anchorMeter = row.anchorMeter?.let(::plainNumber).orEmpty(),
        meterLead = row.meterLead?.let(::plainNumber).orEmpty(),
        seasonBehavior = row.seasonBehavior,
        completionMode = row.completionMode,
        profileId = row.profileId,
        remindersEnabled = row.remindersEnabled,
        // **Without the `enabled` filter, and never null.** Filtering on `enabled` lost the row of
        // a reminders-off schedule: opening and saving one deleted its `schedule_provider` row with
        // nothing said, and switching reminders back on in the same session then wrote
        // `remindersEnabled = true` with **no** provider at all — B10's `SCHEDULE_NO_PROVIDER`
        // finding, reached through the editor's ordinary path. The row carries its own `enabled`
        // flag, so the provider and the switch are two facts and not one.
        provider = row.providers.firstOrNull()
            ?.let { stored -> ProviderId.entries.firstOrNull { it.name == stored.provider } }
            ?: ProviderId.LOCAL,
        editing = true,
    )

    /** Typing in a field clears that field's mark and nothing else — the rest is still wrong. */
    private fun clearing(vararg fields: String, block: (ScheduleEditState) -> ScheduleEditState) =
        _state.update { form ->
            block(form).copy(problems = form.problems.filterNot { fieldOf(it) in fields })
        }

    fun onTitle(value: String) {
        clearing(ScheduleField.TITLE) { it.copy(title = value) }
        // D-11's warning follows the title, because a case-insensitive title match is what "similar"
        // means (master plan decision 37) — so it has to be re-asked as the title is typed.
        viewModelScope.launch { refreshDuplicateWarning() }
    }

    fun onDescription(value: String) = _state.update { it.copy(description = value) }

    fun onInterval(value: String) = clearing(ScheduleField.INTERVAL) { it.copy(timeInterval = value) }

    fun onUnit(value: RecurrenceUnit) = clearing(ScheduleField.UNIT) { it.copy(timeUnit = value) }

    fun onBasis(value: TimeBasis) = _state.update { it.copy(timeBasis = value) }

    fun onAnchor(value: String) = clearing(ScheduleField.ANCHOR) { it.copy(anchorOn = value) }

    fun onLead(value: String) = clearing(ScheduleField.LEAD) { it.copy(leadDays = value) }

    /**
     * Choosing or clearing the meter definition **is** the meter rule's on/off. A group target has
     * no meter block at all, so this is unreachable for one; it is still guarded, because a
     * serialised state restored onto a group target must not be able to set one (invariant 2).
     */
    fun onMeterDefinition(value: DefinitionId?) = clearing(
        ScheduleField.METER,
        ScheduleField.METER_INTERVAL,
    ) { form ->
        if (form.isGroup) form else form.copy(meterDefinitionId = value)
    }

    fun onMeterInterval(value: String) =
        clearing(ScheduleField.METER_INTERVAL) { it.copy(meterInterval = value) }

    fun onAnchorMeter(value: String) = _state.update { it.copy(anchorMeter = value) }

    fun onMeterLead(value: String) = clearing(ScheduleField.METER_LEAD) { it.copy(meterLead = value) }

    /** `FOLLOW_ASSET` is unreachable for a group target (D-28): a group has no season window. */
    fun onSeason(value: SeasonBehavior) = clearing(ScheduleField.SEASON) { form ->
        if (form.isGroup && value == SeasonBehavior.FOLLOW_ASSET) form else form.copy(seasonBehavior = value)
    }

    /**
     * `FORM` is unreachable for a group target (D-12): it carries no profile, so a form would have
     * nothing to collect. Choosing `QUICK` also drops the profile, which is the field that would
     * otherwise be sent for a mode that does not use it.
     */
    fun onCompletionMode(value: CompletionMode) = clearing(
        ScheduleField.COMPLETION_MODE,
        ScheduleField.PROFILE,
    ) { form ->
        when {
            form.isGroup && value == CompletionMode.FORM -> form
            value == CompletionMode.QUICK -> form.copy(completionMode = value, profileId = null)
            else -> form.copy(completionMode = value)
        }
    }

    fun onProfile(value: ProfileId?) = clearing(ScheduleField.PROFILE) { form ->
        if (form.isGroup) form else form.copy(profileId = value)
    }

    fun onReminders(value: Boolean) = _state.update { it.copy(remindersEnabled = value) }

    /**
     * D-11: the asset being scheduled already has a **similar operation through a group**.
     *
     * "Similar" is a case-insensitive `title` match against the schedules of the groups this Asset
     * is an **open** member of (decision 37). The comparison set is deliberately that narrow: a
     * global title match would have two owners both scheduling "Replace filter" warning each other
     * for ever, and an asset's own schedules are not "through another group".
     *
     * It is **non-blocking** — it never gates [save] — because D-11 rules the warning in and
     * semantic deduplication out.
     */
    private suspend fun refreshDuplicateWarning() {
        val form = _state.value
        val assetId = (form.target as? ScheduleTarget.AssetTarget)?.assetId
        val typed = form.title.trim()
        val similar = if (assetId == null || typed.isEmpty()) {
            false
        } else {
            groups.forAsset(assetId)
                .flatMap { schedules.forGroup(it.id) }
                .any { it.id != scheduleId && it.title.trim().equals(typed, ignoreCase = true) }
        }
        // The title is re-read before the answer lands, because one query is launched per keystroke
        // and two of them can finish out of order — which would leave the line answering a title
        // the owner has already typed past. An answer about a title that is no longer there is
        // dropped rather than shown.
        _state.update { form ->
            if (form.title.trim() == typed) form.copy(duplicateWarning = similar) else form
        }
    }

    /**
     * Saves through `SaveSchedule` and, on the **first** schedule creation, asks for notification
     * permission with the ratified rationale.
     *
     * The order is deliberate: the schedule is written **first**, so a denial — or a rationale the
     * owner dismisses — cannot cost them the schedule they came here to make. D-22 says a denial
     * disables nothing, and this is where that is true rather than asserted.
     *
     * The guard is set before the first suspension, so two taps in one frame write one row.
     */
    fun save() {
        val form = _state.value
        if (form.saving) return
        _state.update { it.copy(saving = true, problems = emptyList()) }
        viewModelScope.launch {
            // Asked before the write, because after it the store is never empty again.
            val firstEver = scheduleId == null && schedules.all().isEmpty()
            val outcome = runCatching { saveSchedule.run(scheduleId, form.command()) }
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                _state.update {
                    it.copy(saving = false, problems = (failure as? ScheduleValidation)?.problems.orEmpty())
                }
                return@launch
            }
            val saved = outcome.getOrThrow()
            if (firstEver && !notifications.granted()) {
                // The rationale first, then the system dialog: spec §5.1 asks for the permission
                // with a reason, on a creation, and never at launch.
                _state.update { it.copy(saving = false, askingForNotifications = true) }
                pendingSave = saved.id
            } else {
                _state.update { it.copy(saving = false) }
                _saved.tryEmit(saved.id)
            }
        }
    }

    /** The schedule that is written and waiting for the rationale to be answered. */
    private var pendingSave: ScheduleId? = null

    /**
     * The owner read the rationale. Requests the permission through B05's seam and finishes either
     * way: the answer is a fact about the phone, not a condition on the schedule (D-22,
     * invariant 61).
     */
    fun requestNotifications() {
        val id = pendingSave ?: return
        viewModelScope.launch {
            runCatching { notifications.request() }
            pendingSave = null
            _state.update { it.copy(askingForNotifications = false) }
            _saved.tryEmit(id)
        }
    }

    /** The owner dismissed the rationale. Nothing is requested, and the schedule still stands. */
    fun dismissNotifications() {
        val id = pendingSave ?: return
        pendingSave = null
        _state.update { it.copy(askingForNotifications = false) }
        _saved.tryEmit(id)
    }

    private fun ScheduleEditState.command(): ScheduleCommand {
        val group = target as? ScheduleTarget.GroupTarget
        val asset = target as? ScheduleTarget.AssetTarget
        val meter = meterDefinitionId.takeIf { group == null }
        return ScheduleCommand(
            // One target or the other, from one value: there is no path that sets both.
            targetAssetId = asset?.assetId,
            targetGroupId = group?.groupId,
            title = title,
            description = description,
            timeInterval = timeInterval.trim().toIntOrNull(),
            timeUnit = timeUnit,
            timeBasis = timeBasis,
            // The anchor is only part of a time rule; a meter-only schedule has no series to anchor.
            anchorOn = anchorOn.trim().takeIf { it.isNotEmpty() && timeInterval.trim().isNotEmpty() },
            // A blank lead is no lead, which is zero, not a refusal.
            leadDays = leadDays.trim().toIntOrNull() ?: 0,
            meterDefinitionId = meter,
            meterInterval = meterInterval.trim().toDoubleOrNull().takeIf { meter != null },
            anchorMeter = anchorMeter.trim().toDoubleOrNull().takeIf { meter != null },
            // Sent **as typed**, negative or not. Dropping a negative silently saved the schedule
            // with *no* lead while the field still showed the number the owner entered — a value
            // quietly turned into a different one. `NegativeMeterLead` refuses it instead, and the
            // form marks the field (carry-forward (c): non-negative in the editor, now by refusal
            // rather than by erasure).
            meterLead = meterLead.trim().toDoubleOrNull().takeIf { meter != null },
            seasonBehavior = if (group != null) SeasonBehavior.IGNORE else seasonBehavior,
            completionMode = if (group != null) CompletionMode.QUICK else completionMode,
            profileId = profileId.takeIf { group == null && completionMode == CompletionMode.FORM },
            remindersEnabled = remindersEnabled,
            // At most one row, ever (#4). #25 is the multi-provider UI and this is not it.
            providers = listOfNotNull(
                provider?.let { ScheduleProviderRow(it.name, enabled = remindersEnabled) },
            ),
        )
    }
}

/** A stored `Double` as the form shows it: "3" rather than "3.0", and "0.5" unchanged. */
internal fun plainNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
