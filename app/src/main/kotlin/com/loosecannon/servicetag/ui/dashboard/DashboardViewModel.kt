package com.loosecannon.servicetag.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.ui.maintenance.AttentionSection
import com.loosecannon.servicetag.ui.maintenance.DueItem
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.ui.maintenance.HealthSummary
import com.loosecannon.servicetag.ui.maintenance.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** How long the repository flow stays hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * One row of the dashboard list: the asset, and — for a component a search has surfaced — the name
 * of the asset it is part of. A component row on its own would be a name with no home, and the
 * whole point of letting the search reach components is that the hit can be understood.
 */
data class DashboardRow(val asset: Asset, val parentName: String? = null)

/**
 * One drawn section: its identity and the rows in it, already in `rank` order. A section with no
 * rows is not here at all — D12 §10 omits empty sections (`:706-707`).
 */
data class AttentionGroup(val section: AttentionSection, val items: List<DueItem>)

/**
 * What the dashboard draws. [needsBackup] is deliberately not `lastBackupAt == null` at the call
 * site: the screen should never have to work out what the absence of an instant means. [assets] is
 * likewise already filtered — by lifecycle, by [query], and by whether a row is a component of
 * something else — so no rule about what belongs in the list lives on the screen.
 *
 * [anyInService] and [hiddenComponents] exist because an empty list has three different meanings.
 * Nothing in service at all is a first run and gets the empty state; nothing *matching* is a search
 * that found nothing; and a list that is short because the components are on their own systems is
 * neither, and says so once.
 *
 * **1.2 adds [sections]**: the attention sections of D12 §10 — ATTENTION · UPCOMING · CURRENT ·
 * OUT OF SEASON, in that order, empty ones omitted — drawn from the shared due projection, so the
 * order this screen shows is the order B09's sheet and `/v1/due` use. [assets] is narrowed by one
 * rule with them: an asset that has a listed schedule is represented by that schedule's row, so
 * CURRENT's asset rows are the systems nothing is scheduled on yet, which is exactly what their
 * "No schedule yet" line already said.
 *
 * [dueCount] counts the store and not the view: a filter narrows what is listed, and a total that
 * moved with the filter would not be a total. A group schedule contributes **once** however many
 * members are outstanding (D-15), and `PAUSED`, `INACTIVE_SEASON` and an empty required set
 * contribute nothing (invariants 22, 74).
 */
data class DashboardState(
    val assets: List<DashboardRow> = emptyList(),
    val sections: List<AttentionGroup> = emptyList(),
    /** What the search box holds, verbatim. Blank means "the systems, and not their parts". */
    val query: String = "",
    /** Whether anything is in service at all, before [query] is applied. */
    val anyInService: Boolean = false,
    /** How many in-service components there are, whatever the query; read only while it is blank. */
    val hiddenComponents: Int = 0,
    val needsBackup: Boolean = false,
    val lastBackupAt: Long? = null,
    val filters: DashboardFilters = DashboardFilters(),
    val dueCount: Int = 0,
    /** The worst reminder-health finding, or null for none. The badge shows at >= WARN (#27). */
    val worstSeverity: Severity? = null,
)

/**
 * The fields a search reaches, and why these six (#39): the name; the category the Assets list
 * already shows as its own subtitle, so someone who typed "pump" there expects it to work here;
 * and the four an owner reads off the machine itself when they cannot remember what they called it
 * — make, model, serial, and where the thing is. `description`, `notes`, `vendor` and the warranty
 * prose are deliberately out: they are paragraphs, and a row that shows a name and its parent
 * could not explain a hit buried in one.
 */
private val SEARCHED_FIELDS: List<(Asset) -> String> = listOf(
    Asset::name,
    Asset::category,
    Asset::manufacturer,
    Asset::model,
    Asset::serialNumber,
    Asset::location,
)

/** Case-insensitive substring over [SEARCHED_FIELDS]. A blank query matches everything. */
internal fun Asset.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return SEARCHED_FIELDS.any { field -> field(this).contains(needle, ignoreCase = true) }
}

/**
 * The landing screen's state: the assets that are in service, what needs attention, what the search
 * box holds, and whether a backup has ever been taken. Different kinds of fact, so they arrive
 * different ways — the rows and the schedule tables from live repository flows, the query and the
 * filters from the screen, the backup instant from preferences, which nothing observes.
 *
 * [refresh] is what closes that last gap. The screen calls it when it comes back into composition,
 * so an export that happened while the user was on the backup screen puts the nudge out on the next
 * emission rather than on the next process start.
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

    constructor(graph: AppGraph) : this(
        graph.assets,
        graph.schedules,
        graph.scheduleStates,
        graph.dueReadModel,
        graph.healthSummary,
        graph.prefs,
    )

    private val refreshes = MutableStateFlow(0)
    private val queries = MutableStateFlow("")
    private val filterChoices = MutableStateFlow(DashboardFilters())

    /**
     * What the search box draws itself from, synchronously (F3). [DashboardState.query] carries the
     * same string, but it arrives through `combine` and `stateIn` — an internal channel and a
     * sharing coroutine — so it is not guaranteed to be back before the next keystroke, which is
     * how characters get dropped and the cursor jumps to the end mid-word. Every other hoisted
     * field in this app reads its own `asStateFlow()` for exactly that reason; the filtering still
     * happens off [DashboardState.query] and nothing about the list's rules moves here.
     */
    val query: StateFlow<String> = queries.asStateFlow()

    /** What the two F2 controls draw themselves from, for the same reason the box does. */
    val filters: StateFlow<DashboardFilters> = filterChoices.asStateFlow()

    val state: StateFlow<DashboardState> =
        combine(
            assets.observeAll(),
            // Either table moving can move a status or a section, and neither is what a row
            // *says* — it is only the signal to re-derive. Folding the two keeps the outer
            // `combine` inside its five-argument overload.
            combine(schedules.observeAll(), states.observeAll()) { _, _ -> Unit },
            refreshes,
            queries,
            filterChoices,
        ) { rows, _, _, query, chosen -> Inputs(rows, query, chosen) }
            .map { inputs -> build(inputs) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), DashboardState())

    private data class Inputs(val rows: List<Asset>, val query: String, val chosen: DashboardFilters)

    private suspend fun build(inputs: Inputs): DashboardState {
        val rows = inputs.rows
        val query = inputs.query
        val chosen = inputs.chosen
        val last = prefs.lastBackupAt
        val active = rows.filter { it.status == AssetStatus.ACTIVE }
        val inService = active.filterNot { it.isRetired }
        // Parent names come from every row, not just the in-service ones: a component of a
        // retired machine is itself in service and still has to say whose component it is.
        val byId = rows.associateBy { it.id }
        val matching = inService.filter { it.matches(query) }

        val items = due.items()
        // §11.1's promotion rule, and decision 29's reading of "actionable": a status in ATTENTION
        // or UPCOMING, which is OVERDUE, DUE, DUE_SOON and NO_DATA in its repairable form — the
        // empty-required-set form has no section, so it is not in this set (invariant 74).
        val promoted = items.filter { it.isPromotable }.mapNotNull { it.targetAssetId }.toSet()
        val scheduled = items.mapNotNull { it.targetAssetId }.toSet()

        // The list is the systems. A blank query keeps the parts on the systems they belong to
        // (#39); typing brings them back, because that is the one place a hidden part is asked
        // for by name. §11.1 adds the one exception: a component carrying actionable due work is
        // promoted rather than hidden, so a part's overdue maintenance is never invisible
        // (invariant 75, #5 AC 1) — and it is promoted to its *attention rank*, which is why the
        // row it produces is the schedule row in `sections` and not a second asset row here.
        val shown = if (query.isBlank()) {
            matching.filter { it.parentAssetId == null || it.id.value in promoted }
        } else {
            matching
        }
        val assetRows = shown
            .filterNot { it.id.value in scheduled }
            .filter { chosen.admitsAsset(it) }
            .map { row -> DashboardRow(asset = row, parentName = row.parentAssetId?.let { byId[it]?.name }) }

        // F2 is applied here — after the lifecycle and the search, over rows the projection has
        // already ranked. A filter narrows what is listed and never re-derives an order.
        val listedDue = items
            .filter { it.section != null }
            .filter { it.admittedBy(query, byId) }
            .filter { query.isNotBlank() || !it.isComponent || it.isPromotable }
            .filter { chosen.admits(it) }

        return DashboardState(
            assets = assetRows,
            sections = AttentionSection.entries.mapNotNull { section ->
                listedDue.filter { it.section == section }
                    .takeIf { it.isNotEmpty() }
                    ?.let { AttentionGroup(section, it) }
            },
            query = query,
            anyInService = inService.isNotEmpty(),
            hiddenComponents = inService.count { it.parentAssetId != null },
            // An empty install has nothing to lose, and a nudge over an empty dashboard is
            // noise: the offer only means something once there is something to survive the
            // phone change.
            // The nudge counts every active asset, retired included; CURRENT above excludes them.
            needsBackup = last == null && active.isNotEmpty(),
            lastBackupAt = last,
            filters = chosen.copy(
                categories = inService.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(),
            ),
            dueCount = items.count { it.countsAsDue },
            worstSeverity = health.worstSeverity(),
        )
    }

    /** Re-read the preferences and emit. Cheap: it is one `SharedPreferences` lookup. */
    fun refresh() = refreshes.update { it + 1 }

    /**
     * What the search box holds. Filtering is a pass over rows already in hand, so a keystroke runs
     * no query and needs no debounce; the one cost is that the preference lookup above happens
     * again per keystroke, which is the same single lookup [refresh] is built on.
     */
    fun onQueryChange(value: String) { queries.value = value }

    /** The clear action. Separate from `onQueryChange("")` so the screen states its intent. */
    fun clearQuery() { queries.value = "" }

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

/**
 * Whether a typed query admits this due row. The Asset side is the shipped six-field predicate,
 * unchanged; a group row has no Asset and only a name of its own, so that is what it is matched on.
 */
private fun DueItem.admittedBy(query: String, byId: Map<AssetId, Asset>): Boolean {
    if (query.isBlank()) return true
    return when (val t = target) {
        is ScheduleTarget.AssetTarget -> byId[t.assetId]?.matches(query) == true
        is ScheduleTarget.GroupTarget -> assetName.contains(query.trim(), ignoreCase = true)
    }
}

/** F2 over a due row: both controls narrow, neither reorders. */
private fun DashboardFilters.admits(item: DueItem): Boolean =
    (category == null || item.category == category) && (status == null || item.status == status)

/**
 * F2 over an asset row. A row with no schedule has no maintenance status, so choosing one narrows
 * the list to the rows that carry one rather than quietly keeping the rows that cannot.
 */
private fun DashboardFilters.admitsAsset(asset: Asset): Boolean =
    (category == null || asset.category == category) && status == null
