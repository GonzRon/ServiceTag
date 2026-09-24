package com.loosecannon.servicetag.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.reminders.ReminderHealthSeverity
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.ui.maintenance.AttentionSection
import com.loosecannon.servicetag.ui.maintenance.DueItem
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.ui.maintenance.HealthSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** How long the repository flow stays hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * One row of the dashboard list: the asset, and — for a promoted component — the name of the asset
 * it is part of. A component row on its own would be a name with no home, and the whole point of
 * promoting a part's due work is that the hit can be understood.
 *
 * [hasSchedule] is why the shipped "No schedule yet" line can stay honest. An asset reaches this
 * list either because nothing is scheduled on it, or because everything that is has a status the
 * dashboard deliberately does not draw — a `PAUSED` schedule, or a round that obliges nobody. In
 * the second case the row must not claim there is no schedule, and §17 has no line for "its
 * schedules are all paused", so the subtitle is **omitted**: leaving a ratified string out needs no
 * ratification, inventing one would.
 */
data class DashboardRow(
    val asset: Asset,
    val parentName: String? = null,
    val hasSchedule: Boolean = false,
)

/**
 * One drawn section: its identity and the rows in it, already in `rank` order. A section with no
 * rows is not here at all — D12 §10 omits empty sections (`:706-707`).
 */
data class AttentionGroup(val section: AttentionSection, val items: List<DueItem>)

/**
 * What the dashboard draws. [needsBackup] is deliberately not `lastBackupAt == null` at the call
 * site: the screen should never have to work out what the absence of an instant means. [assets] is
 * likewise already filtered — by lifecycle and by whether a row is a component of something else —
 * so no rule about what belongs in the list lives on the screen.
 *
 * [anyInService] and [hiddenComponents] exist because an empty list has two different meanings.
 * Nothing in service at all is a first run and gets the empty state; and a list that is short
 * because the components are on their own systems is neither, and says so once.
 *
 * **1.2 adds [sections]**: the attention sections of D12 §10 — ATTENTION · UPCOMING · CURRENT ·
 * OUT OF SEASON, in that order, empty ones omitted — drawn from the shared due projection, so the
 * order this screen shows is the order B09's sheet and `/v1/due` use.
 *
 * **An asset appears exactly once** (controller ruling, fix round 1): in its schedule's section
 * when any of its schedules is drawn there, and in [assets] otherwise. So an asset whose only
 * schedule is `PAUSED`, or whose only round obliges nobody, is still on the landing screen — the
 * dashboard omits those *sections*, never the asset.
 *
 * There is deliberately **no due total** here. "How many are due" is one rule — `DueItem.countsAsDue`
 * — and nothing on this screen draws a number: §17 ratifies no wording for one, and a field no
 * surface reads is a second place for the rule to drift to. The surface that wants a count applies
 * that predicate to the projection itself.
 */
data class DashboardState(
    val assets: List<DashboardRow> = emptyList(),
    val sections: List<AttentionGroup> = emptyList(),
    /** Whether anything is in service at all. */
    val anyInService: Boolean = false,
    /** How many in-service components there are — the ones kept on the systems they belong to. */
    val hiddenComponents: Int = 0,
    val needsBackup: Boolean = false,
    val lastBackupAt: Long? = null,
    val filters: DashboardFilters = DashboardFilters(),
    /** The worst reminder-health finding, or null for none. The badge shows at >= WARN (#27). */
    val worstSeverity: ReminderHealthSeverity? = null,
)

/**
 * The landing screen's state: the assets that are in service, what needs attention, F2's filters,
 * and whether a backup has ever been taken. Different kinds of fact, so they arrive different ways
 * — the rows and the schedule tables from live repository flows, the filters from the screen, the
 * backup instant from preferences, which nothing observes.
 *
 * **The store's half and the screen's half are two flows on purpose** (master plan decision 32).
 * Reading the store — the ranked projection, the health summary and the backup instant — is work:
 * four `all()` reads, an occurrence derivation per group schedule, and in B10's hands a platform
 * probe that touches the standby bucket. That happens when the tables move or the screen asks for a
 * [refresh], and **never on a filter change**. The filters only ever *narrow* an already-ranked
 * list, so they combine with it rather than re-deriving it.
 *
 * [refresh] is what closes the preference gap. The screen calls it when it comes back into
 * composition, so an export that happened while the user was on the backup screen puts the nudge out
 * on the next emission rather than on the next process start.
 *
 * The initial state says `needsBackup = false`: a nudge that flashes up before the preferences
 * have been read and then disappears is worse than a nudge that arrives a frame late.
 *
 * The due half is a **read** of derived state and never a write (invariant 17): the schedule and
 * `schedule_state` flows only say "something changed", and the projection recomputes each status
 * from the row plus today, so a stale section is unrepresentable (invariant 18).
 */
class DashboardViewModel(
    assets: AssetRepository,
    schedules: ScheduleRepository,
    states: ScheduleStateRepository,
    private val due: DueReadModel,
    private val health: HealthSummary,
    private val prefs: AppPrefs,
) : ViewModel() {

    /**
     * [health] is a parameter rather than a read of `graph.healthSummary` so a test can hand the
     * screen a known answer without mutating the composition root: a view model captures the
     * summary when it is built, and a field assigned afterwards would be silently ignored.
     */
    constructor(graph: AppGraph, health: HealthSummary = graph.healthSummary) : this(
        graph.assets,
        graph.schedules,
        graph.scheduleStates,
        graph.dueReadModel,
        health,
        graph.prefs,
    )

    private val refreshes = MutableStateFlow(0)
    private val filterChoices = MutableStateFlow(DashboardFilters())

    /**
     * Everything that has to be read to answer "what is in the store". Emits on a table change or a
     * [refresh] and on nothing else — see the class KDoc and decision 32.
     */
    private data class StoreView(
        val rows: List<Asset>,
        val items: List<DueItem>,
        val worstSeverity: ReminderHealthSeverity?,
        val lastBackupAt: Long?,
    )

    private val store: Flow<StoreView> =
        combine(
            assets.observeAll(),
            // Either schedule table moving can move a status or a section, and neither is what a
            // row *says* — each is only the signal to re-derive.
            combine(schedules.observeAll(), states.observeAll()) { _, _ -> Unit },
            refreshes,
            // The badge's own change signal (B10 fix round 1, S3). `HealthSummary` is a cache —
            // decision 32 forbids the check running per emission — so without this the badge would
            // only ever be re-read when the *store* moved: a launch check landing after the first
            // emission would leave a ≥ WARN badge absent for the whole session.
            health.changes,
        ) { rows, _, _, _ -> rows }
            .map { rows ->
                StoreView(
                    rows = rows,
                    items = due.items(),
                    worstSeverity = health.worstSeverity(),
                    lastBackupAt = prefs.lastBackupAt,
                )
            }

    val state: StateFlow<DashboardState> =
        combine(store, filterChoices) { view, chosen -> build(view, chosen) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), DashboardState())

    /**
     * The screen's half: lifecycle, then §11.1's promotion exception, then F2 — over a list the
     * projection has already ranked. Pure, and no repository or platform read in it.
     */
    private fun build(view: StoreView, chosen: DashboardFilters): DashboardState {
        val active = view.rows.filter { it.status == AssetStatus.ACTIVE }
        val inService = active.filterNot { it.isRetired }
        // Parent names come from every row, not just the in-service ones: a component of a
        // retired machine is itself in service and still has to say whose component it is.
        val byId = view.rows.associateBy { it.id }

        val items = view.items
        // §11.1's promotion rule, and decision 29's reading of "actionable": a status in ATTENTION
        // or UPCOMING, which is OVERDUE, DUE, DUE_SOON and NO_DATA in its repairable form — the
        // empty-required-set form has no section, so it is not in this set (invariant 74).
        val promoted = items.filter { it.isPromotable }.mapNotNull { it.targetAssetId }.toSet()

        // F2 is applied here — after the lifecycle, over rows the projection has already ranked.
        // A filter narrows what is listed and never re-derives an order.
        val listedDue = items
            .filter { it.section != null }
            .filter { !it.isComponent || it.isPromotable }
            .filter { chosen.admits(it) }

        // The asset appears exactly once (controller ruling, fix round 1). The exclusion is the set
        // of assets whose schedule is **actually drawn** above — not every asset with a schedule:
        // a `PAUSED` one, or a round that obliges nobody, is in no section, so excluding its asset
        // would leave it represented by nothing at all.
        val drawn = listedDue.mapNotNull { it.targetAssetId }.toSet()
        // Whether an asset has any listed schedule at all, which is a different question and only
        // decides whether "No schedule yet" would be a lie.
        val anySchedule = items.mapNotNull { it.targetAssetId }.toSet()

        // The list is the systems: the parts stay on the systems they belong to (#39). §11.1 adds
        // the one exception: a component carrying actionable due work is promoted rather than
        // hidden, so a part's overdue maintenance is never invisible (invariant 75, #5 AC 1) — and
        // it is promoted to its *attention rank*, which is why the row it produces is the schedule
        // row in `sections` and not a second asset row here.
        val shown = inService.filter { it.parentAssetId == null || it.id.value in promoted }
        val assetRows = shown
            .filterNot { it.id.value in drawn }
            .filter { chosen.admitsAsset(it) }
            .map { row ->
                DashboardRow(
                    asset = row,
                    parentName = row.parentAssetId?.let { byId[it]?.name },
                    hasSchedule = row.id.value in anySchedule,
                )
            }

        return DashboardState(
            assets = assetRows,
            sections = AttentionSection.entries.mapNotNull { section ->
                listedDue.filter { it.section == section }
                    .takeIf { it.isNotEmpty() }
                    ?.let { AttentionGroup(section, it) }
            },
            anyInService = inService.isNotEmpty(),
            hiddenComponents = inService.count { it.parentAssetId != null },
            // An empty install has nothing to lose, and a nudge over an empty dashboard is
            // noise: the offer only means something once there is something to survive the
            // phone change.
            // The nudge counts every active asset, retired included; CURRENT above excludes them.
            needsBackup = view.lastBackupAt == null && active.isNotEmpty(),
            lastBackupAt = view.lastBackupAt,
            filters = chosen.copy(
                categories = inService.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(),
            ),
            worstSeverity = view.worstSeverity,
        )
    }

    /**
     * Re-read the store and emit: the preferences, the projection and the health summary. This is
     * the screen coming back into composition, not a keystroke — decision 32's "app launch and the
     * Health screen", never per emission.
     */
    fun refresh() = refreshes.update { it + 1 }

    /** F2: null is "All categories" — the absence of a filter, not a sentinel option. */
    fun onCategoryChange(category: String?) {
        filterChoices.update { it.copy(category = category) }
    }

    /** F2: null is "All statuses". */
    fun onStatusChange(status: DueStatus?) {
        filterChoices.update { it.copy(status = status) }
    }
}

/** The Asset a row targets, or null for a group row: a group is not an Asset (invariant 4). */
private val DueItem.targetAssetId: String?
    get() = (target as? ScheduleTarget.AssetTarget)?.assetId?.value

/**
 * Whether this row is the kind of component row §11.1 promotes: a status in ATTENTION or UPCOMING
 * (decision 29). It lives here, on the dashboard, and not on `DueItem` — "actionable" is
 * surface-specific and the scan sheet's set is narrower, which is why decision 27 keeps a single
 * shared flag off the projection.
 */
private val DueItem.isPromotable: Boolean
    get() = section == AttentionSection.ATTENTION || section == AttentionSection.UPCOMING

/** F2 over a due row: both controls narrow, neither reorders. */
private fun DashboardFilters.admits(item: DueItem): Boolean =
    (category == null || item.category == category) && (status == null || item.status == status)

/**
 * F2 over an asset row. A row with no schedule has no maintenance status, so choosing one narrows
 * the list to the rows that carry one rather than quietly keeping the rows that cannot.
 */
private fun DashboardFilters.admitsAsset(asset: Asset): Boolean =
    (category == null || asset.category == category) && status == null
