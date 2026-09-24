package com.loosecannon.servicetag.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.statusOf
import com.loosecannon.servicetag.core.usecase.ArchiveSchedule
import com.loosecannon.servicetag.core.usecase.CloseRound
import com.loosecannon.servicetag.core.usecase.HealthSubjectIsPrimary
import com.loosecannon.servicetag.core.usecase.PauseSchedule
import com.loosecannon.servicetag.core.usecase.PostponeSchedule
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.ScheduleDrivesHealthSubject
import com.loosecannon.servicetag.core.usecase.occurrenceWindowOpensOn
import com.loosecannon.servicetag.di.AppGraph
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How long a schedule's snooze suppresses its notifications for: **a literal day from the tap**.
 *
 * It is a duration and not a calendar boundary, because that is how its consumer reads it —
 * `DigestPolicy` suppresses while `snoozedUntilAt > nowMillis`, a **wall clock** comparison. An
 * instant derived from a calendar date is a different quantity in every zone but UTC: midnight UTC
 * of tomorrow's *local* date is 19:00 **today** in UTC−5, so a snooze taken that evening would be
 * expired before it was written and would suppress nothing at all, and at every other hour it would
 * be short of a day. The notification action is "Snooze 1 day" (§17, B07), and this is the same
 * quantity as well as the same code (`ReminderSnooze`, through the seam).
 *
 * `internal` rather than private because **B09's sheet offers the same "Snooze"** and carry-forward
 * (d) makes it the same quantity as well as the same seam; a second literal here would be a second
 * answer to one question.
 */
internal const val SNOOZE_MILLIS = 86_400_000L

/**
 * B06's `ReminderSnooze`, as this brief needs it: the **device-local instant only**.
 *
 * Declared here because B06 has not landed and this brief's operations table needs the snooze to
 * exist; B06 owns `schedule_local_delivery` and its port (master plan decision 25), so this is a
 * one-method seam its use case satisfies rather than a second reader of a table this brief does not
 * own. **It writes no `*_on` column and creates no event**, which is the whole of invariant 20.
 */
fun interface ScheduleSnooze {
    suspend fun snooze(scheduleId: ScheduleId, untilAt: Long)
}

/**
 * This schedule's completion events, read-only.
 *
 * A **seam** rather than the event port, for B09's reason (master plan decision 41): the screen has
 * to show the history and must not be able to write one, and a one-method read is a guarantee of
 * that rather than a promise about it. Every write on this screen goes through a use case, and the
 * gate grep over this package finds no repository that could insert an event.
 */
fun interface ScheduleCompletions {
    suspend fun of(scheduleId: ScheduleId): List<AssetEvent>
}

/**
 * This schedule's closed rounds, read-only — and read-only twice over, because the closure port has
 * no update and no delete either (invariants 37, 43). Declared as a seam for [ScheduleCompletions]'
 * reason.
 */
fun interface ScheduleClosures {
    suspend fun of(scheduleId: ScheduleId): List<OccurrenceClosure>
}

/** One member of a group round, and whether this round has its completion. */
data class RoundMemberRow(
    val assetId: AssetId,
    val name: String,
    val complete: Boolean,
)

/**
 * One completion in this schedule's history.
 *
 * [detailsPending] is **taken from the event**, never computed here (carry-forward (d)). It is
 * carried and not drawn: §17 ratifies no wording for "the details are still owed", and the in-app
 * flow cannot produce such an event at all — a `FORM` schedule routes into its profile form — so
 * the flag only ever arrives from the notification quick action or the API, and the badge's word is
 * a finding for the controller rather than a string for this brief to draft.
 */
data class CompletionRow(
    val eventId: EventId,
    val assetName: String,
    val occurredOn: String,
    val occurrenceOn: String?,
    val detailsPending: Boolean,
)

/** One closed round. **Read-only**: there is no edit and no delete above it (invariant 43). */
data class ClosureRow(val occurrenceOn: String, val closedOn: String)

/**
 * The schedule's own screen: its state, its history, and which of the five operations it may offer.
 *
 * Status is read from `statusOf` and stored nowhere (invariants 17, 18); [requiredSetEmpty] is
 * carried separately, because a round that obliges nobody reads `NO_DATA` too and must never
 * receive the meter-baseline word (§17.1a, invariant 74).
 */
data class ScheduleDetailState(
    val scheduleId: ScheduleId? = null,
    val title: String = "",
    val description: String = "",
    val targetName: String = "",
    val isGroup: Boolean = false,
    val hasTimeRule: Boolean = false,
    val status: DueStatus? = null,
    val requiredSetEmpty: Boolean = false,
    val effectiveDueOn: String? = null,
    /** The schedule's own lead, in days. 1.2.1: `canClose`'s fifth question reads it too. */
    val leadDays: Int = 0,
    val postponedDueOn: String? = null,
    val paused: Boolean = false,
    val archived: Boolean = false,
    /** The schedule's own reminder switch: with it off there is no delivery to suppress. */
    val remindersEnabled: Boolean = false,
    /** The RATIFIED "3 of 5 complete", or null for a round with no progress to report. */
    val progress: String? = null,
    val members: List<RoundMemberRow> = emptyList(),
    val history: List<CompletionRow> = emptyList(),
    val closures: List<ClosureRow> = emptyList(),
    /** The round the operations act on. Null only for a schedule with no time rule. */
    val currentOccurrenceOn: String? = null,
    /** The round's open date — the closure range's floor, before the clamp to today. */
    val roundOpenOn: LocalDate? = null,
    val today: LocalDate = LocalDate.EPOCH,
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val missing: Boolean = false,
    /** The archive action's link-guard dialog (S140–S141, or S137), when one is open. */
    val linkGuard: LinkGuardPrompt? = null,
) {
    /**
     * The RATIFIED status word, withheld for a round that obliges nobody — "NO BASELINE" belongs to
     * the repairable missing-meter form alone and nothing here may lend it to the other one.
     */
    val statusWord: String? get() = status?.takeIf { !requiredSetEmpty }?.let(::statusLabel)

    /**
     * **Complete** is offered while the schedule can still be acted on: not archived, and a group
     * round that obliges nobody is not offered for completion at all (invariant 74).
     */
    val canComplete: Boolean get() = !archived && !requiredSetEmpty && (!isGroup || members.isNotEmpty())

    /**
     * **Postpone** needs a current occurrence to move. A meter-only schedule has none —
     * `computedDueOn` is null for one — and the engine refuses it with `PostponeNeedsTimeRule`, so
     * the action is **not offered** rather than offered and refused (carry-forward (a),
     * invariant 10).
     */
    val canPostpone: Boolean get() = !archived && hasTimeRule && effectiveDueOn != null

    /**
     * **"Snooze"** is offered when a notification **could be suppressed**, which is the brief's own
     * condition and not "the schedule is not archived".
     *
     * A snooze suppresses delivery and changes nothing else, so it means something only where there
     * is a delivery to suppress: reminders on, a status that notifies at all (`INACTIVE_SEASON`,
     * `PAUSED` and `NO_DATA` never do, invariant 22), and a round that obliges somebody — the
     * vacuous round of invariant 74 can never notify, and offering a snooze on it was a control for
     * a thing that cannot happen.
     */
    val canSnooze: Boolean
        get() = !archived && !requiredSetEmpty && remindersEnabled && status?.notifies == true

    /** A postponement that is set can always be put back, whatever the rule. */
    val canClearPostponement: Boolean get() = !archived && postponedDueOn != null

    /**
     * The 1.2.1 window question, on its own: has this round reached `effectiveDueOn - leadDays`?
     * `true` when there is no `effectiveDueOn` to gate on (a round `canClose` would already refuse
     * for another reason). Built on [occurrenceWindowOpensOn], the same arithmetic `CloseRound`'s
     * guard uses, so the two cannot silently drift onto different rules.
     */
    private val windowOpen: Boolean
        get() = effectiveDueOn?.let { !today.isBefore(occurrenceWindowOpensOn(LocalDate.parse(it), leadDays)) }
            ?: true

    /**
     * **"Close this round"** — the whole gate, and the same five questions `CloseRound` asks, so the
     * action and the use case cannot disagree (spec §1.2, invariants 74, 77; 1.2.1 amendment):
     *
     * - a **group** target: in 1.2 an asset round is one member and completing it is the answer;
     * - a **non-empty** required set: a round that obliges nobody is not a round to close;
     * - **not already complete**: closing a finished round would record that it ended unfinished;
     * - **not already closed**: the first row stands, and a second attempt is refused underneath;
     * - **1.2.1**: [windowOpen] — never a status word, so this and `CloseRound`'s guard cannot drift
     *   apart. A round postponed past `today + leadDays` reads false here too, since
     *   `effectiveDueOn` already folds in the postponement; clearing it is the way to re-offer Close.
     */
    val canClose: Boolean
        get() = isGroup && !archived && !requiredSetEmpty && members.any { !it.complete } &&
            closures.none { it.occurrenceOn == currentOccurrenceOn } && windowOpen

    /** Whichever members of the current round are still outstanding. */
    val outstanding: List<AssetId> get() = members.filterNot { it.complete }.map { it.assetId }
}

/**
 * One schedule and the five operations, each doing exactly its own thing.
 *
 * No action here is a generic "move it along". **Complete** goes through [CompletionFlow] and writes an
 * event; **snooze** writes a device-local instant and no date and no event; **postpone** writes
 * `postponed_due_on` and nothing else, and the *next* occurrence still comes from the rule;
 * **close** writes one closure row, no schedule column and no member event; **edit recurrence** is
 * `SaveSchedule`, reached by navigating to the editor, and is the only thing that touches a rule
 * column or moves the D-27 pin's floor.
 *
 * Every answer is **derived**: status comes from `statusOf` and the round from the engine's own
 * occurrence derivation, so nothing here writes derived state (invariants 17, 18).
 */
class ScheduleDetailViewModel(
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val completions: ScheduleCompletions,
    private val closures: ScheduleClosures,
    private val recompute: RecomputeSchedules,
    private val postponeSchedule: PostponeSchedule,
    private val pauseSchedule: PauseSchedule,
    private val archiveSchedule: ArchiveSchedule,
    private val closeRoundUseCase: CloseRound,
    private val snoozer: ScheduleSnooze,
    private val today: Today,
    private val clock: Clock,
    val completion: CompletionFlow,
    private val scheduleId: ScheduleId,
) : ViewModel() {

    constructor(graph: AppGraph, scheduleId: String) : this(
        graph.schedules, graph.assets, graph.groups, graph.scheduleCompletions, graph.scheduleClosures,
        graph.recomputeSchedules, graph.postponeSchedule, graph.pauseSchedule,
        graph.archiveSchedule, graph.closeRound, graph.scheduleSnooze, graph.today, graph.clock,
        graph.completionFlow, ScheduleId(scheduleId),
    )

    private val _state = MutableStateFlow(ScheduleDetailState())
    val state: StateFlow<ScheduleDetailState> = _state.asStateFlow()

    /** A `FORM` schedule's completion is collected by its profile form; the screen navigates. */
    private val _needsForm = MutableSharedFlow<CompletionOutcome.NeedsForm>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val needsForm: SharedFlow<CompletionOutcome.NeedsForm> = _needsForm.asSharedFlow()

    init {
        refresh()
    }

    /**
     * Re-derives everything from the store. Called after every operation, because every operation
     * ends in the recompute and what this screen shows is derived from it (invariant 17).
     */
    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val schedule = schedules.get(scheduleId)
        if (schedule == null) {
            _state.update { it.copy(loaded = true, missing = true) }
            return
        }
        val t = today.localDate()
        // Computed rather than read back out of the table, so the occurrence this screen shows is
        // the one the operations will act on and never a state row a concurrent write left behind.
        val derived = recompute.stateOf(schedule)
        val occurrence = recompute.occurrenceOf(schedule)
        val isGroup = schedule.target is ScheduleTarget.GroupTarget
        val group = (schedule.target as? ScheduleTarget.GroupTarget)?.let { groups.get(it.groupId) }
        val asset = (schedule.target as? ScheduleTarget.AssetTarget)?.let { assets.get(it.assetId) }

        val names = mutableMapOf<String, String>()
        suspend fun nameOf(id: AssetId): String =
            names.getOrPut(id.value) { assets.get(id)?.name.orEmpty() }

        val history = completions.of(schedule.id)
            .sortedWith(
                compareByDescending<AssetEvent> { it.occurredOn }.thenByDescending { it.createdAt },
            )
        val closureRows = closures.of(schedule.id).sortedByDescending { it.occurrenceOn }

        val members = if (isGroup && occurrence != null) {
            occurrence.required.map { assetId ->
                RoundMemberRow(
                    assetId = assetId,
                    name = nameOf(assetId),
                    complete = assetId in occurrence.completed,
                )
            }
        } else {
            emptyList()
        }

        val requiredSetEmpty = isGroup && occurrence?.isActionable != true
        val progress = occurrence
            ?.takeIf { isGroup && it.isActionable }
            ?.progress
            // The RATIFIED progress form. Never "0 of 0 complete": that reads as *done*, and
            // emptiness never means complete (invariant 74).
            ?.let { (done, total) -> "$done of $total complete" }

        // `busy` is carried across the rebuild rather than reset by it: `refresh()` is also called
        // from `LifecycleResumeEffect`, so a resume arriving while an operation is in flight would
        // otherwise re-open the gate `operate` closed. Benign today — the flow's one-prompt guard
        // and the closure's unique index absorb a double — but it is a hole in the state machine
        // and not a property worth leaning on.
        val inFlight = _state.value.busy
        // The link-guard dialog likewise: a resume re-derives, and must not close a question the
        // owner has not answered yet.
        val asking = _state.value.linkGuard
        _state.value = ScheduleDetailState(
            busy = inFlight,
            linkGuard = asking,
            scheduleId = schedule.id,
            title = schedule.title,
            description = schedule.description,
            targetName = group?.name ?: asset?.name.orEmpty(),
            isGroup = isGroup,
            hasTimeRule = schedule.timeInterval != null,
            status = statusOf(schedule, derived, t),
            requiredSetEmpty = requiredSetEmpty,
            effectiveDueOn = derived.effectiveDueOn,
            leadDays = schedule.leadDays,
            postponedDueOn = schedule.postponedDueOn,
            paused = schedule.status == ScheduleStatus.PAUSED,
            archived = schedule.status == ScheduleStatus.ARCHIVED,
            remindersEnabled = schedule.remindersEnabled,
            progress = progress,
            members = members,
            history = history.map { event ->
                CompletionRow(
                    eventId = event.id,
                    assetName = nameOf(event.assetId),
                    occurredOn = event.occurredOn,
                    occurrenceOn = event.occurrenceOn,
                    detailsPending = event.detailsPending,
                )
            },
            closures = closureRows.map { ClosureRow(it.occurrenceOn, it.closedOn) },
            currentOccurrenceOn = occurrence?.occurrenceOn?.toString(),
            roundOpenOn = occurrence?.openOn,
            today = t,
            loaded = true,
        )
    }

    /** **Complete**: the canonical flow, then re-derive. It clears the postponement only if set. */
    fun complete(assetId: AssetId? = null) = operate {
        val outcome = completion.complete(scheduleId, assetId)
        if (outcome is CompletionOutcome.NeedsForm) _needsForm.tryEmit(outcome)
    }

    /** "Complete all": every outstanding required member of the round, in one write. */
    fun completeAll() = operate { completion.completeAll(scheduleId) }

    /** "Complete selected": the members named, and no other (invariants 28, 29). */
    fun completeSelected(assetIds: List<AssetId>) = operate {
        if (assetIds.isNotEmpty()) completion.completeSelected(scheduleId, assetIds)
    }

    /**
     * **"Snooze"**: the device-local instant, and nothing else. No date moves, no event is written,
     * and the schedule still reads OVERDUE — the snooze suppresses delivery; it does not change
     * what is true (invariant 20).
     */
    fun snooze() = operate {
        snoozer.snooze(scheduleId, clock.nowMillis() + SNOOZE_MILLIS)
    }

    /**
     * **"Postpone"**: `postponed_due_on` only. No rule change, no event, and the **next** occurrence
     * still comes from the rule (invariant 21).
     */
    fun postpone(dueOn: String) = operate { postponeSchedule.run(scheduleId, dueOn) }

    /** Puts the occurrence back where the rule says it is. */
    fun clearPostponement() = operate { postponeSchedule.run(scheduleId, null) }

    /**
     * **"Close this round"**: one `occurrence_closure` row and nothing else — no schedule column, no
     * `asset_event` on any member (invariants 35, 36).
     */
    fun closeRound(closedOn: String) = operate { closeRoundUseCase.run(scheduleId, closedOn) }

    fun pause(paused: Boolean) = operate { pauseSchedule.run(scheduleId, paused) }

    /**
     * Archive or restore. **Archiving a schedule a health subject depends on asks first** (spec §6.1,
     * D-30; inv. 130): the refusal naming the subject opens S140, and nothing is archived until the
     * owner answers "Archive both". A restore is never guarded.
     */
    fun archive(archived: Boolean) = operateGuarded {
        try {
            archiveSchedule.run(scheduleId, archived)
            null
        } catch (refused: ScheduleDrivesHealthSubject) {
            LinkGuardPrompt.Asks(refused.name)
        }
    }

    /**
     * S141 "Archive both": the same archive, with the unlink flag, so the subject is archived in the same
     * transaction. When that subject is the one its asset's health follows the whole write is refused
     * and S137 is shown instead — neither the schedule nor the subject is archived.
     */
    fun archiveBoth() {
        if (_state.value.linkGuard !is LinkGuardPrompt.Asks) return
        operateGuarded {
            try {
                archiveSchedule.run(scheduleId, true, unlinkHealthSubject = true)
                null
            } catch (refused: HealthSubjectIsPrimary) {
                LinkGuardPrompt.Primary
            }
        }
    }

    /** Cancel on the link-guard dialog: it closes and nothing is written. */
    fun cancelLinkGuard() = _state.update { it.copy(linkGuard = null) }

    /**
     * Runs one operation and re-derives.
     *
     * A refusal is folded into the re-derivation rather than announced: §17 ratifies no wording for
     * one, and every refusal this screen can reach is a state whose action the gates above already
     * withhold — so the honest answer is the state as it now is. The one exception is the health link
     * guard, which has ratified words and a remedy: see [archive].
     */
    private fun operate(block: suspend () -> Unit) = operateGuarded {
        block()
        null
    }

    /** [operate], for an operation that may end in the link-guard dialog it returns. */
    private fun operateGuarded(block: suspend () -> LinkGuardPrompt?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, linkGuard = null) }
        viewModelScope.launch {
            val prompt = runCatching { block() }.getOrNull()
            load()
            _state.update { it.copy(busy = false, linkGuard = prompt) }
        }
    }
}
