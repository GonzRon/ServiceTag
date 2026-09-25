package com.loosecannon.servicetag.ui.maintenance

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.GroupOccurrence
import com.loosecannon.servicetag.core.usecase.PostponeSchedule
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.condition.OfferBatch
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.health.ComponentCondition
import com.loosecannon.servicetag.ui.health.ConditionView
import com.loosecannon.servicetag.ui.journal.formatNumber
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * RATIFIED, verbatim (master plan §17): the sheet's title. The same word as the Maintenance
 * destination's, because it is the same subject — §17 lists the two together as one string.
 */
const val MAINTENANCE_SHEET_TITLE = "Maintenance"

/**
 * RATIFIED (§17): the way out to the ordinary asset detail, which the sheet always offers.
 *
 * Prefixed because B15's `GroupDetailScreen` declares the **same** ratified word for a member row's
 * action in this same package, and B15 is merged — so this one takes the qualified name. §17 lists
 * "Open asset" once, for B09; that two surfaces now say it is a §17 attribution note for B13's
 * documentation pass, not two strings. Collapsing the two declarations into one would be the
 * cleaner shape and is the controller's call, not this brief's: it would couple the sheet's strings
 * block to a constant whose KDoc frames it as a group member row's.
 */
const val SHEET_OPEN_ASSET = "Open asset"

/** RATIFIED (§17): leave, having written nothing at all. */
const val NOT_NOW = "Not now"

/** RATIFIED (§17): the schedule detail, which is a look and never a completion. */
const val REVIEW_MAINTENANCE = "Review maintenance"

/** RATIFIED (§17, #49 AC 3): the caption over the scanned tag's placement label. */
const val TAG_PLACEMENT = "Tag placement"

/** S139: the maintenance block when the sheet opened for condition and nothing is due. */
const val NOTHING_DUE = "Nothing due"

/**
 * One of the scan sheet's **seven blocks**, top to bottom (spec §10.1; master plan §13.3). The order
 * is decided here, in one list, and the sheet draws the list as it comes — so "condition before
 * maintenance" is a fact a JVM test can hold, not a layout accident.
 *
 * Every block is filled from the one [ScanSheetContent] (inv. 123): the sheet draws nothing the
 * predicate did not decide. A block with nothing to show — no DOWN or DEGRADED component, no
 * CRITICAL subject and no WARNING or CRITICAL aggregate — is left out rather than drawn empty.
 */
sealed interface SheetBlock {
    /** 1. The asset's name and #49's placement caption (shipped). */
    data object Identity : SheetBlock

    /** 2. The condition: word, glyph, reason (or S23) and S22 "since" — or S4 when none is recorded. */
    data class Condition(val view: ConditionView?) : SheetBlock

    /**
     * 3. S7 "Mark operational" and S6 "Change condition" for a DOWN or DEGRADED asset — [markOperational]
     * is then that condition, for S18 — otherwise S6 alone.
     */
    data class ConditionActions(val markOperational: OperationalCondition?) : SheetBlock

    /** 4. Every DOWN or DEGRADED in-service component, as S27. */
    data class Components(val components: List<ComponentCondition>) : SheetBlock

    /**
     * 5. Every CRITICAL subject as S109, then the aggregate as S108 when it is WARNING or CRITICAL —
     * [contributors] are the subjects S108 names. A CRITICAL subject is listed whatever the aggregate
     * says (inv. 119), and a NOMINAL or NOT TRACKED aggregate is not drawn.
     */
    data class Health(
        val critical: List<SubjectHealth>,
        val aggregate: SubjectValue.Scored?,
        val contributors: List<SubjectHealth>,
    ) : SheetBlock

    /** 6. "Maintenance" with the items, or S139 when [nothingDue]. */
    data class Maintenance(val nothingDue: Boolean) : SheetBlock

    /** 7. "Open asset" (and the shipped "Not now"). */
    data object OpenAsset : SheetBlock
}

/**
 * Spec §10.1's order over one [content]; [hasItems] is whether the maintenance block has rows. Before
 * the first load there is no content, and only the asset's name and the way out are drawn.
 */
fun sheetBlocks(content: ScanSheetContent?, hasItems: Boolean): List<SheetBlock> {
    if (content == null) return listOf(SheetBlock.Identity, SheetBlock.OpenAsset)
    return buildList {
        add(SheetBlock.Identity)
        add(SheetBlock.Condition(content.condition))
        add(
            SheetBlock.ConditionActions(
                markOperational = content.condition?.condition?.takeIf { content.offersMarkOperational },
            ),
        )
        if (content.components.isNotEmpty()) add(SheetBlock.Components(content.components))
        if (content.critical.isNotEmpty() || content.aggregate != null) {
            add(SheetBlock.Health(content.critical, content.aggregate, content.subjects))
        }
        add(SheetBlock.Maintenance(nothingDue = !hasItems))
        add(SheetBlock.OpenAsset)
    }
}

/**
 * The last completion's measurements, **read**.
 *
 * Master plan decision 41. D5 §7A `:219-221` requires the sheet to show the last completion's date
 * **and its key readings**, and `DueItem` carries the date but structurally cannot carry the values
 * — profile values are heterogeneous per schedule, so a generic projection field would be the wrong
 * shape. This is that one collaborator: it is backed by the shipped `EventRepository.get` and
 * exposes **no write method at all**, so the sheet cannot reach a write path even by accident.
 */
fun interface LastCompletionReadings {
    suspend fun forEvent(eventId: EventId): List<Measurement>
}

/**
 * Which event a schedule's last completion was — `ScheduleState.lastCompletionEventId`, read.
 *
 * A second one-method seam rather than handing the sheet `ScheduleStateRepository`, for
 * [LastCompletionReadings]' reason and for invariant 17's: that port carries `upsert`, and `rebuild`
 * is the only write path into `schedule_state`. `DueItem` carries the completion's **date** but not
 * its id, and the id is what [LastCompletionReadings] is keyed on, so the two seams together are
 * what let the display fact D5 §7A requires and the sheet's write-free gate both hold.
 */
fun interface LastCompletionEventId {
    suspend fun of(scheduleId: ScheduleId): EventId?
}

/**
 * B06's reminder sweep, as the sheet needs it: recompute, then hand the provider the whole desired
 * state (#50 AC 8).
 *
 * A completion quiesces its notification **by canonical state** and never by deleting a
 * notification — the inversion that would make the notification the source of truth. This is a
 * seam rather than `ReminderRun` itself because the sheet has no business arming an alarm or
 * driving a backstop, and because a test of "a completion reconciles" should not have to build a
 * provider.
 */
fun interface ReminderReconcile {
    suspend fun run()
}

/**
 * The current round of a group-targeted schedule, **derived and read**.
 *
 * The third one-method seam, in the shape of the other two, and the tool the brief already grants
 * ("**From B03:** `GroupOccurrence`, `CompleteGroupMembers`"). It is needed because
 * `DueReadModel.forAsset` returns a deliberate **superset** on the group half — its own KDoc says
 * it reads every group the Asset has ever held a window in and "only has to be certain it misses
 * nothing" — and `requiredSetEmpty` answers "does this round oblige *nobody*", never "does it
 * oblige *this* Asset" (review blocking 2).
 *
 * `RecomputeSchedules.occurrenceOf` derives without upserting, so this stays a pure read and the
 * write-free gate is intact.
 */
fun interface ScanRoundMembership {
    suspend fun roundFor(scheduleId: ScheduleId): GroupOccurrence?
}

/**
 * Whether a scan of this Asset opens the sheet.
 *
 * The **routing** half of spec §10.1, asked by the scan path before it navigates: a scan with
 * nothing to open the sheet for opens the asset exactly as today (#50 AC 1), and only a
 * `Resolution.OpenAsset` that answers true here reaches `Route.MaintenanceSheet` at all. The answer
 * is exactly [ScanSheetContent.opens] of the content the sheet itself lists ([scanSheetContentFor]),
 * so the routing decision and the sheet's contents cannot disagree (inv. 123) — the failure a
 * second predicate would introduce.
 */
fun interface ScanSheetOffer {
    suspend fun has(assetId: AssetId): Boolean
}

/**
 * Whether this row is **actionable on the scan sheet** — D-18a's admission set, and deliberately
 * narrower than the dashboard's promotion rule (master plan §11.1, decision 27).
 *
 * `DUE` and `OVERDUE` always; `NO_DATA` **only** in its repairable missing-meter-baseline form,
 * which "Log meter reading" repairs; and an **empty required set** never, however its status enum
 * reads — a round that obliges nobody is not work to offer somebody standing at the equipment
 * (invariant 74). `DUE_SOON` is not actionable and rides along only as a passenger; `OK`,
 * `INACTIVE_SEASON` and `PAUSED` never appear.
 *
 * A **reminders-disabled** schedule never appears either: the D-18a row reads "an archived or
 * **reminders-disabled** schedule | never", and the archived half is already gone by
 * `listedForDue()` (review blocking 3 — the earlier reading of "disabled" as PAUSED was overturned,
 * because PAUSED is excluded by the clause immediately before it and that reading left the second
 * clause with no content).
 */
val DueItem.actionableOnScanSheet: Boolean
    get() = !requiredSetEmpty && remindersEnabled &&
        (status == DueStatus.DUE || status == DueStatus.OVERDUE || isRepairableNoData)

/**
 * D-18a applied to one in-service Asset's projection **with no condition in play**: which of its
 * rows the sheet offers, in the attention order [DueReadModel] already put them in (master plan
 * §11.1 — never a second ordering rule).
 *
 * It is [scanSheetContent]'s maintenance list and nothing else, so it cannot drift from the one
 * predicate: `DUE_SOON` joins **only** when the sheet is already open, and **never alone** (D5 §7A
 * `:208`). An empty answer means "no maintenance item opens the sheet".
 *
 * An **archived** schedule is already gone before this sees it: every due and projection query
 * starts from `listedForDue()`, inside `DueReadModel` (carry-forward (a)).
 *
 * `internal`, with its asset-level twin [scanSheetItemsFor]: a condition-blind subset of the
 * predicate is not a routing answer, so main exposes none (review M3). Only the module's own tests
 * ask it.
 */
internal fun scanSheetItems(items: List<DueItem>): List<DueItem> =
    scanSheetContent(items, condition = null, components = emptyList(), health = null, inService = true).maintenance

/**
 * What the scan sheet offers **this** Asset: the projection, narrowed to the rounds that actually
 * oblige it, then D-18a.
 *
 * This is the whole of the sheet's admission set and the **one** place it is decided, so the
 * routing question ([ScanSheetOffer]) and the sheet's own contents cannot disagree — a second
 * predicate would be wrong on one of them the moment it was right on the other.
 *
 * A group-targeted row is admitted only when this Asset is **required** by the open round and has
 * **not already completed** it (review blocking 2). Both halves are load-bearing: a former member
 * whose window closed before the round opened would otherwise be offered work
 * `CompleteGroupMembers` refuses as `NotARequiredMember`, and a member who has already done it
 * would be offered a second completion the idempotence index refuses — which is #50 AC 12 at the
 * group level.
 *
 * A group schedule with **no round at all** — `occurrenceOf` answers null for a schedule with no
 * time rule, and a group target carries no meter rule (invariant 2) — is not admitted: there is no
 * occurrence to oblige anybody.
 */
internal suspend fun scanSheetItemsFor(
    assetId: AssetId,
    items: List<DueItem>,
    rounds: ScanRoundMembership,
): List<DueItem> = scanSheetItems(rounds.obliged(assetId, items))

/** [items] narrowed to the rows whose round obliges [assetId]; every asset-targeted row does. */
internal suspend fun ScanRoundMembership.obliged(assetId: AssetId, items: List<DueItem>): List<DueItem> =
    items.filter { obliges(assetId, it) }

private suspend fun ScanRoundMembership.obliges(assetId: AssetId, item: DueItem): Boolean =
    when (item.target) {
        is ScheduleTarget.AssetTarget -> true
        is ScheduleTarget.GroupTarget -> roundFor(item.scheduleId)
            ?.let { assetId in it.required && assetId !in it.completed } == true
    }

/**
 * One row of the sheet, with every display fact D5 §7A `:219-221` requires already rendered.
 *
 * Nothing here is a new sentence. [whyNow] is composed from the item's own status and dates using
 * the **ratified** per-item forms of §17.1e ("Due \<date\>.", "Overdue since \<date\>."), [meter]
 * is F3's ratified "Due at \<n\> \<unit\>, now \<n\>." off the shared [meterLine], [statusWord] is
 * the ratified status term and [completionTakes] the ratified completion-mode option — so the
 * sheet draws no wording the owner has not ratified, and this brief drafts nothing.
 *
 * [passenger] is a `DUE_SOON` row riding along; [repairOnly] is the repairable `NO_DATA`, which
 * offers "Log meter reading" and is not selectable for completion — it is a repair, not an
 * obligation.
 */
data class SheetItem(
    val scheduleId: ScheduleId,
    val title: String,
    /** Carried so the row can draw D12 §5's glyph and colour beside the word, never a raw colour. */
    val status: DueStatus,
    val statusWord: String,
    val passenger: Boolean,
    val repairOnly: Boolean,
    val effectiveDueOn: String?,
    val meter: String?,
    val whyNow: String?,
    val completionTakes: String,
    val lastCompletedOn: String?,
    val lastReadings: List<String>,
    val progress: String?,
    val canPostpone: Boolean,
    val canSnooze: Boolean,
) {
    /** Whether "Complete selected" may act on this row. A repair is not a completion. */
    val selectable: Boolean get() = !repairOnly
}

/**
 * The sheet's whole state: which asset was scanned, from which tag, what is actionable, and what
 * the owner has selected.
 *
 * [emptyOnArrival] is the nothing-to-open case surviving a restored back stack: the route is reached
 * only from a resolution whose [ScanSheetContent.opens] was true, but a stack restored into an asset
 * whose work has since been done (and whose condition no longer opens the sheet) must still land on
 * the ordinary asset screen rather than on an empty sheet (#50 AC 1). It is decided on the **first** load alone, so completing the last item leaves the
 * owner on the sheet with its two ways out rather than yanking the screen away.
 */
data class MaintenanceSheetState(
    val assetId: AssetId? = null,
    val assetName: String = "",
    val tagPlacement: String? = null,
    val items: List<SheetItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    val loaded: Boolean = false,
    val emptyOnArrival: Boolean = false,
    /**
     * The whole of [scanSheetContent]'s answer for this asset — the condition, the components, the
     * critical subjects and the aggregate beside the maintenance [items] — for the sheet to draw.
     * Null until the first load.
     */
    val content: ScanSheetContent? = null,
) {
    /** "Complete selected" acts on an explicit selection and never on "everything shown". */
    val canComplete: Boolean get() = selected.isNotEmpty()

    /** The seven blocks, in spec §10.1's order, for the sheet to draw as they come. */
    val blocks: List<SheetBlock> get() = sheetBlocks(content, hasItems = items.isNotEmpty())
}

/**
 * The scan completion sheet, as state and operations.
 *
 * **It reads derived state and never writes it** (invariant 17): the item list is
 * [DueReadModel.forAsset]'s projection and the last completion's readings come through two
 * read-only seams. **It has no completion path of its own**: every completion goes through B14's
 * [CompletionFlow], which asks the ratified "When was this done?" — so a backdated completion is
 * first class here exactly as it is everywhere else, and a `FORM` schedule leaves for its own form
 * with nothing fabricated (#50 AC 4, D-25).
 *
 * **The five actions are five different things** (D5 §7A `:230-232`, #50 AC 6). Completion writes
 * an event and reconciles; "Snooze" writes the device-local instant and no date and no event
 * (invariant 20); "Postpone" writes `postponed_due_on` and nothing else, and the next occurrence
 * still comes from the rule (invariant 21); "Review maintenance" navigates; "Not now" and the back
 * gesture write nothing at all. None of them is an alias for any other.
 *
 * **A group round completes this member only.** The scanned Asset is handed to [CompletionFlow] as
 * the member, so `CompleteGroupMembers` writes one member's event and every other member's history
 * is byte-identical (invariants 28, 29) — the sheet has no "complete the group" at all.
 */
class MaintenanceSheetViewModel(
    private val due: DueReadModel,
    private val health: AssetHealthReadModel,
    private val assets: AssetRepository,
    private val tags: TagRepository,
    private val readings: LastCompletionReadings,
    private val lastCompletionEventId: LastCompletionEventId,
    private val rounds: ScanRoundMembership,
    private val snoozer: ScheduleSnooze,
    private val postponeSchedule: PostponeSchedule,
    private val reconcile: ReminderReconcile,
    private val clock: Clock,
    val completion: CompletionFlow,
    private val assetId: AssetId,
    private val tagId: TagId?,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: String, tagId: String?) : this(
        graph.dueReadModel, graph.assetHealthReadModel, graph.assets, graph.tags, graph.lastCompletionReadings,
        graph.lastCompletionEventId, graph.scanRoundMembership, graph.scheduleSnooze,
        graph.postponeSchedule, graph.reminderReconcile, graph.clock, graph.completionFlow,
        AssetId(assetId), tagId?.let(::TagId),
    )

    private val _state = MutableStateFlow(MaintenanceSheetState())
    val state: StateFlow<MaintenanceSheetState> = _state.asStateFlow()

    /** A `FORM` schedule's completion is collected by its profile form; the sheet navigates. */
    private val _needsForm = MutableSharedFlow<CompletionOutcome.NeedsForm>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val needsForm: SharedFlow<CompletionOutcome.NeedsForm> = _needsForm.asSharedFlow()

    /**
     * The sequential form flow, as two fields and no more.
     *
     * [queue] is what is left of one "Complete selected", in the order the owner saw the rows;
     * [awaiting] is the one schedule whose form the owner has been sent to. Several selected forms
     * therefore run **sequentially and explicitly** — one form at a time, each one saved before the
     * next opens — and abandoning one stops the run, so the forms already saved stand and the rest
     * are absent. A batch that started every form at once is the partial silent write this shape
     * exists to make unrepresentable.
     */
    private var queue: List<ScheduleId> = emptyList()
    private var awaiting: ScheduleId? = null

    /**
     * The offers already asked during this run (the controller's rulings on B12's review, M-1, RS-2):
     * one "Complete selected" asks each asset each offer at most once — the quick items the flow
     * completes and the form items saved in the journal entry alike — so "Not yet" on the first item
     * done on a DOWN asset is remembered for the rest of the selection. A **new** batch every run, so
     * nothing leaks into the next selection; it is open on the flow from the run's start until the run
     * ends ([settle]).
     */
    private var batch = OfferBatch()

    init {
        refresh()
    }

    override fun onCleared() {
        completion.closeSelection(batch)
    }

    /** The run is over unless a form is still being filled in: its selection closes. */
    private fun settle() {
        if (awaiting == null) completion.closeSelection(batch)
    }

    /**
     * Re-derives from canonical state, then advances the form queue if there is one.
     *
     * Every read is from the store and not from a list held across an action, which is what makes a
     * repeat scan after a completion refuse to re-offer that occurrence (#50 AC 12): the completed
     * schedule is no longer `DUE`, so [scanSheetContent] does not admit it.
     */
    fun refresh() {
        viewModelScope.launch {
            if (_state.value.busy) return@launch
            load()
            resumeQueue()
        }
    }

    private suspend fun load() {
        val asset = assets.get(assetId)
        val placement = tagId?.let { tags.get(it) }?.label?.takeIf { it.isNotBlank() }
        // The one predicate: what opened the scan here is what the sheet lists (inv. 123).
        val content = scanSheetContentFor(assetId, due, rounds, health)
        val rows = content.maintenance.map { item(it) }
        val shown = rows.map { it.scheduleId.value }.toSet()
        val first = !_state.value.loaded
        _state.update { previous ->
            previous.copy(
                assetId = assetId,
                assetName = asset?.name.orEmpty(),
                tagPlacement = placement,
                items = rows,
                // A row that has gone — completed, or no longer actionable — takes its selection
                // with it, so "Complete selected" can never act on something that is not on screen.
                selected = previous.selected.intersect(shown),
                loaded = true,
                emptyOnArrival = if (first) !content.opens else previous.emptyOnArrival,
                content = content,
            )
        }
    }

    private suspend fun item(row: DueItem): SheetItem {
        val eventId = row.lastCompletedOn?.let { lastCompletionEventId.of(row.scheduleId) }
        return SheetItem(
            scheduleId = row.scheduleId,
            title = row.title,
            status = row.status,
            statusWord = statusLabel(row.status),
            passenger = row.status == DueStatus.DUE_SOON,
            repairOnly = row.isRepairableNoData,
            effectiveDueOn = row.effectiveDueOn?.toString(),
            meter = meterLine(row),
            whyNow = whyNow(row),
            completionTakes = if (row.completionMode == CompletionMode.FORM) THE_FULL_FORM else ONE_TAP,
            lastCompletedOn = row.lastCompletedOn?.toString(),
            lastReadings = eventId?.let { readings.forEvent(it) }.orEmpty().map(::readingLine),
            progress = progressLine(row),
            // A meter-only schedule has no occurrence date to move and `PostponeSchedule` refuses
            // one, so the action is **not offered** rather than offered and refused (invariant 10).
            canPostpone = !row.isRepairableNoData && row.effectiveDueOn != null,
            // A snooze suppresses a delivery, so it means something only where there is one:
            // `NO_DATA`, `INACTIVE_SEASON` and `PAUSED` never notify (invariant 22), and a schedule
            // whose reminders are off has nothing to suppress. The same two conditions B14's
            // schedule detail gates the identical action on, so the sheet's Snooze is not merely
            // the same code but the same *availability* (carry-forward (d), review should-fix 7).
            canSnooze = row.remindersEnabled && row.status.notifies,
        )
    }

    /** Toggles one row's selection. Selection is the owner's explicit act and the sheet's only one. */
    fun toggle(scheduleId: ScheduleId) {
        val id = scheduleId.value
        _state.update { current ->
            if (current.items.none { it.scheduleId.value == id && it.selectable }) {
                current
            } else {
                current.copy(
                    selected = if (id in current.selected) current.selected - id else current.selected + id,
                )
            }
        }
    }

    /**
     * **"Complete selected"**: the rows the owner selected, in the order they are shown, each one
     * through [CompletionFlow] and none of them fabricated.
     */
    fun completeSelected() {
        val chosen = _state.value.items
            .filter { it.selectable && it.scheduleId.value in _state.value.selected }
            .map { it.scheduleId }
        if (chosen.isEmpty()) return
        run(chosen)
    }

    /**
     * **"Log meter reading"**: the repairable `NO_DATA` row's repair, through the **same** flow.
     *
     * The flow demands the reading a meter rule owes and refuses to write without it, so this is
     * the repair and the completion at once and never a second path (#50 AC 5, #11).
     */
    fun repair(scheduleId: ScheduleId) = run(listOf(scheduleId))

    private fun run(ids: List<ScheduleId>) {
        if (_state.value.busy) return
        queue = ids
        awaiting = null
        batch = OfferBatch()
        completion.openSelection(batch)
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { pump() }
            settle()
            load()
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * One schedule at a time, and a stop on anything that is not a completion.
     *
     * A `FORM` schedule returns [CompletionOutcome.NeedsForm] **without writing anything**, so the
     * sheet navigates and the rest of the queue waits for the owner to come back. A cancelled
     * prompt or a refusal empties the queue: the alternative is continuing through the rest of a
     * selection the owner has just backed out of.
     */
    private suspend fun pump() {
        while (queue.isNotEmpty()) {
            val head = queue.first()
            queue = queue.drop(1)
            // Exhaustive, with **no `else ->`** (review blocking 2): a refusal that fell into a
            // catch-all was how `NotARequiredMember` and the idempotence index became "the queue
            // emptied and nothing happened".
            // The reconcile runs right after the completion is written, **before** any offer is asked
            // (the controller's ruling on B12's review, R-2): leaving the sheet with an offer open
            // skips only that offer's write, never quiescing the notification.
            when (val outcome = completion.complete(head, assetId) { reconcile.run() }) {
                is CompletionOutcome.Completed -> Unit
                is CompletionOutcome.NeedsForm -> {
                    awaiting = head
                    _needsForm.tryEmit(outcome)
                    return
                }
                // The owner backed out of the question. Stopping is the explicit answer, and §17
                // ratifies no wording for it.
                CompletionOutcome.Cancelled -> {
                    queue = emptyList()
                    return
                }
                // A use case refused. The two refusals this sheet could reach —
                // `NotARequiredMember` and the repeat-completion index — are now **unreachable**,
                // because `scanSheetItemsFor` will not offer a round this Asset is not required by
                // or has already done. So this branch is the belt to that fix rather than a
                // routine path, and the cause is carried to the log instead of being dropped: §17
                // ratifies no sentence for a refusal, and inventing one is not this brief's to do.
                is CompletionOutcome.Refused -> {
                    Log.w(TAG, "a completion the scan sheet offered was refused", outcome.cause)
                    queue = emptyList()
                    return
                }
            }
        }
    }

    /**
     * Back from a form: carry on only if that form was actually saved.
     *
     * A saved completion takes its schedule out of [scanSheetContent]'s admission set, so "is it still
     * offered" is the question, asked of canonical state. Still offered means the owner left the
     * form without saving — and then the run **stops**, which is what leaves the forms already
     * saved written and every later one absent rather than half-finished.
     */
    private suspend fun resumeQueue() {
        val pending = awaiting ?: return
        awaiting = null
        if (_state.value.items.any { it.scheduleId == pending }) {
            // Still offered, so the owner left the form without saving: the run stops, which is
            // what leaves the forms already saved written and every later one absent.
            queue = emptyList()
            settle()
            return
        }
        // The form **was** saved, and the journal screen that wrote the event reconciles nothing —
        // so this is the one point on the FORM path where the standing notification can be quiesced
        // by canonical state rather than left up until the next digest or backstop sweep (#50 AC 8,
        // review should-fix 4). It runs whether or not there is more queue to walk.
        reconcile.run()
        if (queue.isEmpty()) {
            settle()
            return
        }
        _state.update { it.copy(busy = true) }
        runCatching { pump() }
        settle()
        load()
        _state.update { it.copy(busy = false) }
    }

    /**
     * **"Snooze"**: the device-local instant, and nothing else — no `*_on` column, no event, and
     * the schedule still reads exactly what it read before (invariant 20).
     *
     * The same [SNOOZE_MILLIS] and the same `ReminderSnooze` behind the same seam the schedule
     * detail and the notification action use, so "Snooze" and "Snooze 1 day" are not merely the
     * same length: they are the same code (carry-forward (d)).
     */
    fun snooze(scheduleId: ScheduleId) = operate {
        snoozer.snooze(scheduleId, clock.nowMillis() + SNOOZE_MILLIS)
    }

    /**
     * **"Postpone"**: `postponed_due_on` only. No rule changes, no event is written, and the
     * **next** occurrence still comes from the rule (invariant 21).
     */
    fun postpone(scheduleId: ScheduleId, dueOn: String) = operate {
        postponeSchedule.run(scheduleId, dueOn)
    }

    private fun operate(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { block() }
            load()
            _state.update { it.copy(busy = false) }
        }
    }

    private companion object {
        private const val TAG = "MaintenanceSheet"

        /**
         * The why-now line, composed from the row's **own** status and dates using the RATIFIED
         * per-item forms of §17.1e. This brief drafts no sentence template: a row with neither a
         * date nor a meter side says nothing here, and its status word and its meter line carry
         * the answer instead.
         *
         * The date is the **actionable** one (1.4), the date the status word is measured against,
         * so a row the policy pulled before its season says "Overdue since" the day it became late
         * and never the later canonical date it would otherwise name.
         */
        fun whyNow(row: DueItem): String? {
            val due = row.actionableDueOn ?: return null
            return when (row.status) {
                DueStatus.OVERDUE -> "Overdue since $due."
                DueStatus.DUE, DueStatus.DUE_SOON -> "Due $due."
                else -> null
            }
        }

        /**
         * One reading of the last completion: the value as it was entered and the unit snapshotted
         * with it. The definition's **label** is deliberately absent — the declared seam answers
         * `List<Measurement>`, and reaching for `DefinitionRepository` to name each one would hand
         * this sheet a port that can write.
         */
        fun readingLine(measurement: Measurement): String {
            val value = measurement.valueNum?.let(::formatNumber) ?: measurement.valueText.orEmpty()
            return if (measurement.unit.isBlank()) value else "$value ${measurement.unit}"
        }
    }
}
