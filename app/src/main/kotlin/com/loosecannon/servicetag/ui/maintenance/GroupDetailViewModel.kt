package com.loosecannon.servicetag.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.listedForDue
import com.loosecannon.servicetag.core.schedule.statusOf
import com.loosecannon.servicetag.core.usecase.ArchiveGroup
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How long the repository flows stay hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * One membership as the group screens draw it: the Asset behind the window, by name.
 *
 * [complete] is only meaningful inside a round's checklist, where it says whether **this** member
 * has an event for that occurrence. It is per round and never a property of the membership.
 */
data class GroupMemberRow(
    val assetId: AssetId,
    val name: String,
    val complete: Boolean = false,
)

/**
 * One of the group's schedules, with its current round.
 *
 * [progress] is the RATIFIED "3 of 5 complete", derived from `RecomputeSchedules.occurrenceOf` and
 * **never from a membership count**: the denominator is `required(D)`, the windows that covered the
 * round's open instant bounded by each member Asset's own lifecycle (D-16), so adding a member today
 * cannot move a round that is already open. Null for a round that obliges nobody — "0 of 0 complete"
 * reads as done, and emptiness never means complete (invariant 74).
 *
 * [checklist] is that same required set, in the same order, each entry carrying whether it is done.
 *
 * [roundOpen] is the fact the progress line cannot state on its own: three of five done is a round
 * still running, and the screen says so by still listing the two that are not.
 */
data class GroupScheduleRow(
    val scheduleId: ScheduleId,
    val title: String,
    val status: DueStatus,
    val requiredSetEmpty: Boolean,
    val progress: String?,
    val checklist: List<GroupMemberRow>,
    val roundOpen: Boolean,
) {
    /**
     * Whether the round can still take a completion: it obliges somebody and somebody is still
     * outstanding. A round that obliges nobody is never offered for completion (invariant 74), and
     * emptiness is not the same question as completeness.
     */
    val canComplete: Boolean get() = !requiredSetEmpty && roundOpen && checklist.any { !it.complete }
}

/**
 * One maintenance group, in full: what it is, who is in it now, and what its rounds are asking for.
 *
 * [members] is the **open** windows only — a closed window is history, not membership — and
 * [schedules] carries the history the required sets are derived from, which is why a removed member
 * can still appear in a checklist while being absent from this list.
 */
data class GroupDetailState(
    val id: GroupId,
    val name: String,
    val description: String,
    val archived: Boolean,
    val members: List<GroupMemberRow>,
    val schedules: List<GroupScheduleRow>,
)

/**
 * The group detail screen's state (#55's "Dashboard / UX" minimum, spec §2.3, §2.4).
 *
 * It **adds no rule**. Every fact it shows is read: membership from the group aggregate, the round
 * and its progress from the recompute's `occurrenceOf`, the status word from the shared `statusOf`.
 * The one thing it writes is `archived_at`, and it writes that through [ArchiveGroup] — the use case
 * that changes one column and cascades nothing, which is what makes an archived group's history
 * survive (D-16).
 *
 * **The schedules are read here rather than through `DueReadModel`**, and deliberately: the
 * projection drops an archived group's schedules, because they belong in no due total and no
 * dashboard section, and this screen is where that group's retained history has to remain readable.
 * `listedForDue()` still bounds the list — carry-forward (a) — so an archived *schedule* is absent
 * here as it is everywhere.
 *
 * **It completes nothing itself.** The round's two ratified actions and a single member's are all
 * [CompletionFlow] calls, which is the one completion mechanism in 1.2 (master plan decision 36):
 * the occurrence key, the idempotence index, the conditional postponement clear and the recompute
 * are the use cases', and a completion written from this screen's own hand would be the second
 * write path #50 forbids. The round itself — its date affordance, its postponement and its close —
 * belongs to the schedule, which the checklist opens.
 */
class GroupDetailViewModel(
    private val groups: GroupRepository,
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val states: ScheduleStateRepository,
    private val recompute: RecomputeSchedules,
    private val archiveGroup: ArchiveGroup,
    private val today: Today,
    /** The one completion mechanism. Exposed because the screen draws its affordance. */
    val completion: CompletionFlow,
    private val id: GroupId,
) : ViewModel() {

    constructor(graph: AppGraph, id: String) : this(
        graph.groups,
        graph.assets,
        graph.schedules,
        graph.scheduleStates,
        graph.recomputeSchedules,
        graph.archiveGroup,
        graph.today,
        graph.completionFlow,
        GroupId(id),
    )

    private val rows = groups.observeAll()

    private val _busy = MutableStateFlow(false)

    /** Whether a completion is in flight; the round's actions are disabled while it is. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val state: StateFlow<GroupDetailState?> =
        combine(rows, schedules.observeAll(), states.observeAll()) { groupRows, _, _ ->
            groupRows.firstOrNull { it.id == id }
        }
            .map { group -> group?.let { detailOf(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    /**
     * Separate from [state] because "not read yet" and "gone" both read as a null state, and only
     * the second one should send the owner back — a restored back stack or a replacing import can
     * name a group that is no longer there.
     */
    val missing: StateFlow<Boolean> = rows
        .map { groupRows -> groupRows.none { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), false)

    /** Archive is one column and no cascade: every window, completion and closure survives it. */
    fun setArchived(archived: Boolean) {
        viewModelScope.launch { archiveGroup.run(id, archived) }
    }

    /**
     * **"Complete all"**: every required member of the round that is not yet done, in one write.
     *
     * The member list is derived inside `CompleteGroupMembers`, not here, which is what stops a
     * surface completing somebody the round does not oblige (invariants 28, 29).
     */
    fun completeAll(scheduleId: ScheduleId) = operate { completion.completeAll(scheduleId) }

    /** **"Complete selected"**: exactly the members named, and no other (invariants 28, 29). */
    fun completeSelected(scheduleId: ScheduleId, assetIds: List<AssetId>) = operate {
        if (assetIds.isNotEmpty()) completion.completeSelected(scheduleId, assetIds)
    }

    /**
     * One member, done. The same flow, with the member named: a group target with no member named
     * is refused rather than guessed at, so the id is always passed.
     *
     * There is no `NeedsForm` branch here, and there is nothing for one to do: `CompletionFlow`
     * returns that outcome only for an **asset** target, and a group target is QUICK-only anyway
     * (D-12), so a form route from this screen would be dead code with a KDoc promising a path
     * that cannot be taken.
     */
    fun completeMember(scheduleId: ScheduleId, assetId: AssetId) = operate {
        completion.complete(scheduleId, assetId)
    }

    /**
     * Runs one completion with [busy] held.
     *
     * Nothing is refreshed afterwards and nothing needs to be: a completion ends in the recompute,
     * which upserts `schedule_state`, and this screen's state is derived from that table's own flow
     * (invariants 17, 18).
     */
    private fun operate(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun detailOf(group: MaintenanceGroup): GroupDetailState {
        val names = assets.all().associate { it.id to it.name }
        return GroupDetailState(
            id = group.id,
            name = group.name,
            description = group.description,
            archived = group.archivedAt != null,
            members = group.members
                .filter { it.removedAt == null }
                .sortedWith(compareBy({ it.sortOrder }, { names[it.assetId]?.lowercase().orEmpty() }, { it.id }))
                .map { GroupMemberRow(it.assetId, names[it.assetId].orEmpty()) },
            schedules = schedules.forGroup(group.id).listedForDue()
                .sortedWith(compareBy({ it.title.lowercase() }, { it.id.value }))
                .map { scheduleRow(it, names) },
        )
    }

    private suspend fun scheduleRow(
        schedule: MaintenanceSchedule,
        names: Map<AssetId, String>,
    ): GroupScheduleRow {
        // The round, from the one gathering point every use case validates against. Never
        // `group.members`: that is today's membership, and a past round's obligation is not.
        val occurrence = recompute.occurrenceOf(schedule)
        val state = states.get(schedule.id) ?: recompute.stateOf(schedule)
        val requiredSetEmpty = occurrence != null && occurrence.required.isEmpty()
        return GroupScheduleRow(
            scheduleId = schedule.id,
            title = schedule.title,
            status = statusOf(schedule, state, today.localDate()),
            requiredSetEmpty = requiredSetEmpty,
            progress = occurrence
                ?.takeUnless { requiredSetEmpty }
                ?.let { progressLine(it.progress.first, it.progress.second) },
            checklist = occurrence?.required.orEmpty().map { assetId ->
                GroupMemberRow(
                    assetId = assetId,
                    name = names[assetId].orEmpty(),
                    complete = occurrence != null && assetId in occurrence.completed,
                )
            },
            roundOpen = occurrence != null && !occurrence.isComplete,
        )
    }
}
