package com.loosecannon.servicetag.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.condition.currentIncident
import com.loosecannon.servicetag.core.condition.needsIncident
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.journal.CategoryCatalog
import com.loosecannon.servicetag.core.journal.CategoryChoice
import com.loosecannon.servicetag.core.journal.CategoryKey
import com.loosecannon.servicetag.core.journal.CategorySuggestions
import com.loosecannon.servicetag.core.journal.LatestReadings
import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.core.journal.Reading
import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.LoanStanding
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.Money
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.Season as SeasonWindow
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.isWrittenFor
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.openOuts
import com.loosecannon.servicetag.core.model.shortPackId
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.usecase.WithdrawTransferRecord
import com.loosecannon.servicetag.core.usecase.WithdrawTransferResult
import com.loosecannon.servicetag.ui.transfer.TransferStrings
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.usecase.ActivationCommand
import com.loosecannon.servicetag.core.usecase.AddAttachment
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.ApplyResult
import com.loosecannon.servicetag.core.usecase.ApplyTemplate
import com.loosecannon.servicetag.core.usecase.ArchiveAsset
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetCycle
import com.loosecannon.servicetag.core.usecase.AssetHasChildren
import com.loosecannon.servicetag.core.usecase.AssetMembershipReferenced
import com.loosecannon.servicetag.core.usecase.AssetProblem
import com.loosecannon.servicetag.core.usecase.AssetSettingsCommand
import com.loosecannon.servicetag.core.usecase.AssetValidation
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.BreakStrandsPolicy
import com.loosecannon.servicetag.core.usecase.DeleteAsset
import com.loosecannon.servicetag.core.usecase.GetAssetSeason
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.HealthValidation
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.RecordSeasonActivation
import com.loosecannon.servicetag.core.usecase.RetireAsset
import com.loosecannon.servicetag.core.usecase.SaveAssetSettings
import com.loosecannon.servicetag.core.usecase.SeasonAlreadyEnded
import com.loosecannon.servicetag.core.usecase.SeasonAlreadyStarted
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeStrandsPolicy
import com.loosecannon.servicetag.core.usecase.SeasonNotManual
import com.loosecannon.servicetag.core.usecase.SeasonProblem
import com.loosecannon.servicetag.core.usecase.SeasonValidation
import com.loosecannon.servicetag.core.usecase.SeasonView
import com.loosecannon.servicetag.core.usecase.StrandedSchedule
import com.loosecannon.servicetag.core.usecase.WarrantyReminderCommand
import com.loosecannon.servicetag.core.usecase.WarrantyReminderProblem
import com.loosecannon.servicetag.core.usecase.WarrantyReminderValidation
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.ui.attachments.AttachmentFailure
import com.loosecannon.servicetag.ui.attachments.PickedFile
import com.loosecannon.servicetag.ui.attachments.newestFirst
import com.loosecannon.servicetag.ui.attachments.sentence
import com.loosecannon.servicetag.ui.condition.componentLine
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.health.AssetHealthView
import com.loosecannon.servicetag.ui.health.ComponentCondition
import com.loosecannon.servicetag.ui.health.ConditionView
import com.loosecannon.servicetag.ui.health.HealthPlurals
import com.loosecannon.servicetag.ui.health.NOT_TRACKED
import com.loosecannon.servicetag.ui.health.aggregateLine
import com.loosecannon.servicetag.ui.health.criticalLine
import com.loosecannon.servicetag.ui.health.driverLines
import com.loosecannon.servicetag.ui.health.healthBadgeLabel
import com.loosecannon.servicetag.ui.health.inService
import com.loosecannon.servicetag.ui.health.needsAttention
import com.loosecannon.servicetag.ui.loan.LoanFacts
import com.loosecannon.servicetag.ui.loan.loanFactsOf
import com.loosecannon.servicetag.ui.maintenance.DueItem
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.ui.maintenance.ENTER_THE_NUMBER_OF_DAYS
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.service.ServiceCaseRow
import com.loosecannon.servicetag.ui.service.openServiceCasesLine
import com.loosecannon.servicetag.ui.service.serviceCaseRowsOf
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Locale

/**
 * The three asset ViewModels. Each takes the `AppGraph` members it actually uses — the secondary
 * constructor is what the Compose entry calls, the primary one is what a test builds on a
 * Room-backed fake graph. No screen ever reaches past its state and its callbacks.
 */

/** How long the repository flows stay hot after the last collector leaves (a rotation, typically). */
private const val SUBSCRIPTION_GRACE_MS = 5_000L

/**
 * One row of the Assets list: the asset plus what the row says that the asset itself does not carry —
 * whose component it is, whether today falls outside its season window (spec §9), and (#71) whether it
 * carries a written ServiceTag tag and what its health is. Every fact is the row's own asset's: a
 * component's row reads its own tags and its own health, and a parent's row never borrows a child's.
 */
data class AssetRow(
    val asset: Asset,
    /** The parent's name for the "Part of <parent>" subtitle; null for a root asset. */
    val parentName: String? = null,
    val outOfSeason: Boolean = false,
    /** #71: some tag row [isWrittenFor] this asset — targets it, ACTIVE, and written. */
    val hasWrittenTag: Boolean = false,
    /**
     * #71 (plan E2, E4): the read model's own health view, the one `forAsset` gives, while the row is
     * a health surface — the asset in service (lifecycle, E1) and tracked (a non-null aggregate) —
     * and null otherwise, when the row draws no health and no condition beside it ([rowHealthOf]).
     */
    val health: AssetHealthView? = null,
    /**
     * #72 (C19, R72-22): the asset's open loan's standing — P72-1 or P72-2 on the row, after the
     * lifecycle badges and before condition and health — or null when nothing is lent. Every
     * lifecycle: a retired or archived asset's open loan is still out.
     */
    val loan: LoanStanding? = null,
    /**
     * #77 (C19): the asset is **held** — transferred out from this phone by an open OUT record — keyed on the
     * records, never on ARCHIVED. The row draws P77-31 (`Transferred`, `ic_handover`) instead of "Archived", no
     * health, and is listed only with the Archived control on, whatever its status.
     */
    val transferred: Boolean = false,
)

/**
 * #71's gate (plan E2): the row shows health only for an asset **in service** — active and not
 * retired; its season plays no part — whose health is **tracked**. A row that shows no health shows
 * none of the group, the condition badge included (R71-6, R71-11). Decided here, never in Compose.
 *
 * In service is asked of both the view and the list's own [asset] row: the view is the read model's
 * snapshot, and a view kept across a restart (review MINOR-1) or a pass still running can be older
 * than the row, so an asset the list already knows is retired or archived never shows its health.
 */
internal fun rowHealthOf(asset: Asset, view: AssetHealthView?): AssetHealthView? =
    view?.takeIf { asset.inService && it.inService && it.result.aggregate != null }

/**
 * The lines the row draws under its badges (plan E2): S109 for every CRITICAL subject, then S27 for
 * every DOWN or DEGRADED in-service component, in [healthBlocksOf]'s order and in its words. Every
 * other block — the aggregate line, the fallback, the subjects, the footer — stays on the detail.
 */
fun rowHealthLines(view: AssetHealthView): List<String> = healthBlocksOf(view).mapNotNull { block ->
    when (block) {
        is HealthBlock.Critical -> criticalLine(block.subject)
        is HealthBlock.Component -> componentLine(block.component)
        else -> null
    }
}

/**
 * The Assets list's three controls (#73, C1): the category it is narrowed to — a [CategoryKey], null
 * for All — and whether components and archived rows are admitted. One value, updated by `copy`, so
 * changing one control can never reset another. Session state (R73-2): the view model holds it across
 * a rotation, and nothing persists it.
 */
data class AssetFilters(
    val type: String? = null,
    val showComponents: Boolean = false,
    val showArchived: Boolean = false,
)

/**
 * Why the list is empty (#73, C5; owner ruling §18.23, extended to every control by R73-7). The view
 * model decides it and the screen only switches on it. [NONE] whenever the list has a row.
 */
enum class EmptyReason {
    NONE,
    /** A blank query, Type All, and no asset at all. */
    NO_ASSETS,
    /** A blank query, Type All, and no asset the Archived control admits. */
    NO_ACTIVE_ASSETS,
    /** A blank query, Type All, and every asset the Archived control admits is a component. */
    ONLY_COMPONENTS,
    /** Nothing matches the query and the type together, and no query match of another type explains it. */
    NOTHING_MATCHES,
    /** The query has matches, and none of them is of the chosen type. */
    TYPE_HIDDEN,
    /** Every match of the chosen type is hidden, and only Components hides any of them. */
    COMPONENTS_HIDDEN,
    /** Every match of the chosen type is hidden, and only Archived hides any of them. */
    ARCHIVED_HIDDEN,
    /** Every match of the chosen type is hidden, and both controls hide at least one of them. */
    BOTH_HIDDEN,
}

data class AssetsState(
    val items: List<AssetRow> = emptyList(),
    /** The three controls, exactly as the owner left them. */
    val filters: AssetFilters = AssetFilters(),
    /** How many rows are archived, so the blank-query empty state can say how many are behind it. */
    val archivedCount: Int = 0,
    /** What the search box holds, verbatim. */
    val query: String = "",
    /** The chosen category's display, or null while Type is All. */
    val typeLabel: String? = null,
    /** What the Type menu offers after All: the durable category catalog, in its own order (#74). */
    val typeChoices: List<CategoryChoice> = emptyList(),
    /** Why [items] is empty; [EmptyReason.NONE] while it is not. */
    val emptyReason: EmptyReason = EmptyReason.NONE,
) {
    /** The Archived control, mirrored for the callers that only ever asked about it. */
    val showArchived: Boolean get() = filters.showArchived
}

/**
 * The list. Three decisions live here: which rows the controls admit, the order — active, then
 * retired, then archived, by name within each group (spec §9) — and why an empty list is empty. The
 * order is the ViewModel's rather than the query's because "retired" is a date column, not a status,
 * and sorting by it in SQL would say nothing about lifecycle.
 *
 * **#73: the list is four predicates ANDed together** — the Archived control (archive is not delete,
 * R-9, but a list that keeps showing everything you archived is no better than never archiving), the
 * Components control, the chosen Type, and the search query. Components is off by default, so a blank
 * query lists root assets only and a component appears once the owner turns Components on, still
 * naming its system. A query searches **within** the admitted set and never widens it or touches a
 * control (R73-1); when the controls hide every match, [EmptyReason] names the ones in the way.
 *
 * **Type** is the durable category catalog (#74) matched by [CategoryKey], so a spelling stored before
 * promotion still matches (R73-5). A category renamed to a new key, or deleted, while it is the chosen
 * Type writes the control back to All, and it stays All until the owner picks again (C4).
 *
 * **1.4 (B14).** A row's out-of-season mark is the asset's **season phase** on [today] (spec §3.1),
 * read from [SeasonContext] like every other season surface — so a MANUAL asset after an END reads
 * out of season here too. The activation rows are read, and observed, only for MANUAL assets
 * (inv. 90); a Start or an End on the detail screen writes no asset column (inv. 126), so the list
 * watches those rows directly rather than waiting for an asset row to change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetsViewModel(
    assets: AssetRepository,
    categories: CategoryRepository,
    activations: SeasonActivationRepository,
    tags: TagRepository,
    health: AssetHealthReadModel,
    private val today: Today,
    /**
     * #72 (C19): every open loan, observed once and joined to the rows by asset id. Null lends
     * nothing — a test that is not about loans; `AppGraph` passes the real repository.
     */
    loans: AssetLoanRepository? = null,
    /**
     * #77 (C19): the held set, observed once. Null holds nothing — a test that is not about transfers;
     * `AppGraph` passes the real records.
     */
    transfers: TransferRecordRepository? = null,
) : ViewModel() {

    constructor(graph: AppGraph) : this(
        graph.assets, graph.categories, graph.seasonActivations, graph.tags, graph.assetHealthReadModel, graph.today,
        loans = graph.loans,
        transfers = graph.transferRecords,
    )

    private val filters = MutableStateFlow(AssetFilters())
    private val queries = MutableStateFlow("")

    /**
     * #71 (plan C2): the health read model's one unobserved signal. Events, activations, profiles and
     * midnight move no table the row health watches, so the screen calls [refresh] on every resume —
     * the attention list's accepted staleness, and its `refreshes` shape.
     */
    private val refreshes = MutableStateFlow(0)

    /**
     * What the search box draws itself from, synchronously (F3): a `combine`/`stateIn` round trip
     * is not guaranteed to be back before the next keystroke, which is how characters get dropped
     * and the cursor jumps to the end mid-word. [AssetsState.query] carries the same string once
     * the list has caught up with it.
     */
    val query: StateFlow<String> = queries.asStateFlow()

    /** Every asset, beside the activation rows of each MANUAL one (and of no other, inv. 90). */
    private val seasonal: Flow<Seasonal> = assets.observeAll().flatMapLatest { rows ->
        val manual = rows.filter { it.seasonMode == SeasonMode.MANUAL }
        if (manual.isEmpty()) {
            flowOf(Seasonal(rows, emptyMap()))
        } else {
            combine(manual.map { asset -> activations.observeForAsset(asset.id).map { asset.id to it } }) { pairs ->
                Seasonal(rows, pairs.toMap())
            }
        }
    }

    /**
     * The row health a pass last delivered (review MINOR-1). [state] stops its upstream after the
     * subscription grace, and a restarted read model begins with its synthetic empty map — made so the
     * list's first open never waits, never to erase groups already drawn. So a restart shows these
     * kept views until its own pass lands; the first open, with nothing kept, still gets the empty map.
     * Only the collecting coroutine touches it, one at a time.
     */
    private var keptRowHealth: Map<AssetId, AssetHealthView>? = null

    private val rowHealth: Flow<Map<AssetId, AssetHealthView>> = flow {
        val kept = keptRowHealth
        var first = true
        health.observeRowHealth(refreshes).collect { views ->
            if (first) {
                // The read model's first emission is always its synthetic empty map, never a pass.
                first = false
                emit(kept ?: views)
            } else {
                keptRowHealth = views
                emit(views)
            }
        }
    }

    /**
     * #71 (plan C1–C3): the rows beside the two facts each row reads by its own id — the assets that
     * carry a written tag, folded from every tag row on each emission here, and the row health, whose
     * first emission never waits for a pass: empty on the list's first open, the kept views on a
     * restart ([rowHealth]).
     */
    private val facts: Flow<Facts> =
        combine(
            seasonal,
            tags.observeAll(),
            rowHealth,
            // #72 (C19): one read of every open loan, keyed by asset — at most one each (C2).
            (loans?.observeOpen() ?: flowOf(emptyList())).map { open -> open.associateBy { it.assetId } },
            // #77 (C19): the held set, keyed on the records and never on ARCHIVED.
            transfers?.observeHeldIds() ?: flowOf(emptySet()),
        ) { (rows, activationsOf), tagRows, views, lent, held ->
            Facts(rows, activationsOf, writtenTagsOf(tagRows), views, lent, held)
        }

    val state: StateFlow<AssetsState> =
        combine(facts, filters, categories.observeAll(), queries) { (rows, activationsOf, tagged, views, lent, held), picked, custom, query ->
            val choices = CategoryCatalog.choices(custom)
            val stale = picked.type?.takeIf { key -> choices.none { it.key == key } }
            // C4: a chosen category the catalog no longer holds (renamed to a new key, or deleted)
            // writes the control itself back to All — compare-and-set, so a type picked since this
            // emission began is never erased — and this state is already built with All. Never a
            // derived "effective type": that would silently re-apply if the key came back.
            if (stale != null) filters.update { if (it.type == stale) it.copy(type = null) else it }
            val controls = if (stale != null) picked.copy(type = null) else picked
            val day = today.localDate()
            val byId = rows.associateBy { it.id }
            // The four predicates, ANDed: the three controls admit a row, and the query searches only
            // what they admitted (R73-1) — #39's six-field predicate, unchanged.
            val matching = rows.filter { controls.admits(it, held) && it.matches(query) }
            AssetsState(
                items = matching
                    .sortedWith(compareBy({ lifecycleRank(it, held) }, { it.name.lowercase() }))
                    .map { row ->
                        AssetRow(
                            asset = row,
                            // The parent by name, from the rows already in hand: no second query,
                            // and a parent that has gone leaves the subtitle off rather than
                            // showing an id.
                            parentName = row.parentAssetId?.let { byId[it]?.name },
                            outOfSeason = outOfSeasonOn(row, activationsOf[row.id].orEmpty(), day),
                            hasWrittenTag = row.id in tagged,
                            // #77 (C11, C19): a held asset is quiesced — no health on its row.
                            health = if (row.id in held) null else rowHealthOf(row, views[row.id]),
                            loan = lent[row.id]?.standingOn(day),
                            transferred = row.id in held,
                        )
                    },
                filters = controls,
                archivedCount = rows.count { it.status != AssetStatus.ACTIVE || it.id in held },
                query = query,
                typeLabel = controls.type?.let { key -> choices.first { it.key == key }.display },
                typeChoices = choices,
                emptyReason = emptyReason(rows, controls, query, listed = matching.isNotEmpty(), held = held),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), AssetsState())

    /** The Archived control. */
    fun toggleArchived() = filters.update { it.copy(showArchived = !it.showArchived) }

    /** The Components control. */
    fun toggleComponents() = filters.update { it.copy(showComponents = !it.showComponents) }

    /** The Type control: a category key from [AssetsState.typeChoices], or null for All. */
    fun pickType(key: String?) = filters.update { it.copy(type = key) }

    /**
     * What the search box holds. Filtering is a pass over rows the store flow already produced, so
     * a keystroke runs no query, reads no preference and needs no debounce.
     */
    fun onQueryChange(value: String) { queries.value = value }

    /** The clear action. Separate from `onQueryChange("")` so the screen states its intent. */
    fun clearQuery() { queries.value = "" }

    /** #71: re-derive the row health — the screen's resume, as the dashboard's `refresh`. */
    fun refresh() = refreshes.update { it + 1 }

    /** The asset rows and, keyed by asset, the activation rows of the MANUAL ones. */
    private data class Seasonal(val rows: List<Asset>, val activationsOf: Map<AssetId, List<SeasonActivation>>)

    /** [Seasonal] beside the assets with a written tag and the row health (#71), and the open loans (#72). */
    private data class Facts(
        val rows: List<Asset>,
        val activationsOf: Map<AssetId, List<SeasonActivation>>,
        val tagged: Set<AssetId>,
        val views: Map<AssetId, AssetHealthView>,
        val lent: Map<AssetId, AssetLoan>,
        val held: Set<AssetId>,
    )
}

/** #71 (R71-1): the assets some tag row [isWrittenFor] — one fold of every tag row, the predicate its one home. */
private fun writtenTagsOf(tags: List<TagBinding>): Set<AssetId> =
    tags.mapNotNullTo(HashSet()) { tag -> (tag.target as? TagTarget.AssetTarget)?.assetId?.takeIf { tag.isWrittenFor(it) } }

/**
 * The Archived control's predicate: archived rows only while it is on. #77 (C19): a held asset is listed only with
 * the control on too, whatever its status — a held-but-ACTIVE row from merged history included.
 */
private fun AssetFilters.admitsStatus(asset: Asset, held: Set<AssetId> = emptySet()): Boolean =
    showArchived || (asset.status == AssetStatus.ACTIVE && asset.id !in held)

/** The Components control's predicate: a component's admission is this and its own status, never its parent's. */
private fun AssetFilters.admitsPlace(asset: Asset): Boolean = showComponents || asset.parentAssetId == null

/** The Type control's predicate, by key (R73-5): a spelling stored before promotion still matches. */
private fun AssetFilters.admitsType(asset: Asset): Boolean = type == null || CategoryKey.of(asset.category) == type

private fun AssetFilters.admits(asset: Asset, held: Set<AssetId>): Boolean =
    admitsStatus(asset, held) && admitsPlace(asset) && admitsType(asset)

/**
 * C5's ordered table. [listed] is whether the list has a row at all; `hits` are the rows the query
 * matches with no control applied, and `ofType` the hits the chosen Type admits. When the controls
 * hide every one of those, the reason is the **union** of the controls hiding them — never a
 * precedence — so a mix of an active component and an archived root names both controls, and no
 * sentence is false for one of its rows.
 */
internal fun emptyReason(
    rows: List<Asset>,
    filters: AssetFilters,
    query: String,
    listed: Boolean,
    held: Set<AssetId> = emptySet(),
): EmptyReason {
    if (listed) return EmptyReason.NONE
    if (query.isBlank() && filters.type == null) {
        return when {
            rows.isEmpty() -> EmptyReason.NO_ASSETS
            rows.none { filters.admitsStatus(it, held) } -> EmptyReason.NO_ACTIVE_ASSETS
            else -> EmptyReason.ONLY_COMPONENTS
        }
    }
    val hits = rows.filter { it.matches(query) }
    val ofType = hits.filter { filters.admitsType(it) }
    if (ofType.isEmpty()) {
        return if (query.isNotBlank() && hits.isNotEmpty()) EmptyReason.TYPE_HIDDEN else EmptyReason.NOTHING_MATCHES
    }
    val byComponents = ofType.any { !filters.admitsPlace(it) }
    val byArchived = ofType.any { !filters.admitsStatus(it, held) }
    return when {
        byComponents && byArchived -> EmptyReason.BOTH_HIDDEN
        byComponents -> EmptyReason.COMPONENTS_HIDDEN
        else -> EmptyReason.ARCHIVED_HIDDEN
    }
}

/**
 * Active first, then retired, then archived (spec §9). Archived wins over retired, so an asset that
 * is both sorts with the archived tail: the chip that hides archived rows must hide all of them.
 */
private fun lifecycleRank(asset: Asset, held: Set<AssetId>): Int = when {
    asset.status != AssetStatus.ACTIVE || asset.id in held -> 2
    asset.isRetired -> 1
    else -> 0
}

/**
 * Out of season for [today]: the asset's **season phase** (spec §3.1), from [SeasonContext] — the
 * one predicate the schedules, health and the detail screen's `SeasonView` read too. YEAR_ROUND is
 * never out of season; CALENDAR reads its window; MANUAL reads the latest of [activations] dated on
 * or before [today], and a MANUAL asset with no row at all reads out of season. [activations] is
 * consulted only for a MANUAL asset (inv. 90). A CALENDAR window the use cases would refuse reads as
 * no window — in season — so no asset is hidden behind a window nobody could have set.
 */
internal fun outOfSeasonOn(asset: Asset, activations: List<SeasonActivation>, today: LocalDate): Boolean =
    SeasonContext.of(asset.seasonInputs(if (asset.seasonMode == SeasonMode.MANUAL) activations else emptyList()))
        .phaseAt(today) == SeasonPhase.OUT_OF_SEASON

/** The clock's instant as a calendar day in [zone] — the only place millis become a date here. */
private fun Long.asLocalDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/**
 * One child of the asset as COMPONENTS draws it (spec §9). [outOfRange] is a count of the child's
 * *own* current readings that are LOW or HIGH; 2B-2 rolls no values up into the parent (spec §2),
 * so the parent's screen says how many need a look and never what they read.
 *
 * **1.4 (B14).** [condition] is the child's **own** current condition (spec §10.3: every component
 * row carries the condition badge), or null when none is recorded (S4). It is a fact about the
 * child, never rolled up into the parent's.
 */
data class ComponentRow(
    val id: String,
    val name: String,
    val category: String,
    val outOfRange: Int,
    val condition: ConditionView? = null,
)

/**
 * What the detail screen must put in front of the user next, if anything (spec §7). All four live
 * in the ViewModel rather than in the composition because two of them are *outcomes* of a write —
 * the follow-on offer and the children-first refusal — and a screen that owns half of a sequence
 * ends up owning the wrong half across a rotation.
 */
sealed interface DetailPrompt {
    /** The retirement date dialog. [date] is what the field opens with: today, ISO, backdatable. */
    data class Retire(val date: String) : DetailPrompt

    /** "Log what happened?", offered once the retirement is already written, and always declinable. */
    data object LogWhatHappened : DetailPrompt

    data object ConfirmDelete : DetailPrompt

    /** Children-first (spec §5): the delete was refused, and these are the children by name. */
    data class DeleteRefused(val children: List<String>) : DetailPrompt

    /**
     * 1.4 — **Start season** (S42, S43) or **End season** (S44, S45) on a MANUAL asset (spec §3.3).
     * Nothing is written until the confirm (inv. 93), and then only the chosen [date], with no event.
     *
     * [date] is the field's text, today by default. The range is `[from, today]`: [from] is the
     * latest activation's date (null when the asset has none, which only an import makes), and a
     * date outside it is refused with [refusal] = S54 before anything is written. A date that is not
     * one holds the confirm rather than being worded (master dec. 46), and so does a later-than-today
     * date on an asset with no row, since S54 would have no `<date>` to name.
     */
    /**
     * #77 (C23, R77-5): the withdrawal question for the open OUT of [packId] — P77-63 ([title]), P77-64, P77-65 /
     * `Cancel`. Nothing is written until the confirm; [saving] holds a second tap.
     */
    data class Withdraw(val packId: String, val title: String, val saving: Boolean = false) : DetailPrompt

    data class SeasonChange(
        val action: SeasonAction,
        val date: String,
        val from: LocalDate?,
        val today: LocalDate,
        val refusal: String? = null,
        val saving: Boolean = false,
    ) : DetailPrompt {
        /** The field as a date, by the strict `YYYY-MM-DD` shape the command takes; null otherwise. */
        val parsed: LocalDate?
            get() = date.trim().takeIf { ISO_DAY.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        /** Whether the confirm (S40 or S41) may be tapped. */
        val canConfirm: Boolean
            get() = !saving && parsed.let { it != null && (from != null || !it.isAfter(today)) }
    }
}

private val ISO_DAY = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * One group this asset is an **open** member of, as the asset screen lists it (#55's asset -> groups
 * direction). A closed window is history and is not here: listing one would make a removed asset
 * look like a current member.
 */
data class AssetGroupRow(val id: GroupId, val name: String)

/**
 * #77 (C19, C23): one open OUT record naming this asset, as the P77-32 block draws it — P77-33 ([on]), P77-34
 * ([pack]), the note when there is one, and P77-62, whose dialog is titled [withdrawTitle] (P77-63). Two rows only
 * after a double mark (`TRANSFER_DIVERGED`), each withdrawable on its own.
 */
data class TransferOutRow(
    val packId: String,
    val on: String,
    val pack: String,
    val note: String?,
    val withdrawTitle: String,
)

/** The detail overflow's items, in the order drawn (#77 adds [TRANSFER], P77-1, before the destructive one). */
enum class DetailMenuItem { EDIT, ARCHIVE, UNARCHIVE, RETIRE, UNRETIRE, TRANSFER, DELETE }

/**
 * Everything the detail screen draws about one asset, or null while it is still unknown.
 *
 * **1.4 (B14).** The three independent facts beside the lifecycle (spec §10.3): [health] (B07's one
 * health view, which also carries the current [condition] and the DOWN or DEGRADED components),
 * [conditionHistory], and [season] (B04's `SeasonView`). Everything the screen draws from them is
 * derived here — [plate], [healthBlocks], [outOfSeason] — so the composition decides nothing.
 */
data class AssetDetailState(
    val asset: Asset,
    /** One read of B07's `AssetHealthReadModel.forAsset`: result, current condition, components. */
    val health: AssetHealthView,
    /** One read of B04's `GetAssetSeason`: mode, window, phase, next boundary, break, activations. */
    val season: SeasonView,
    /** S21: every condition row of this asset, **newest first** (dec. 41), read-only. */
    val conditionHistory: List<ConditionHistoryRow> = emptyList(),
    val tags: List<TagBinding> = emptyList(),
    val definitions: List<MeasurementDefinition> = emptyList(),
    /** Unarchived only, in `sortOrder`: these are the quick actions the screen offers. */
    val profiles: List<EventProfile> = emptyList(),
    /** Newest first (§4.1), as the repository returns them. */
    val events: List<AssetEvent> = emptyList(),
    /** Derived from [definitions] and [events] on every emission, never stored (§4.2). */
    val readings: List<Reading> = emptyList(),
    /**
     * True only while the asset has nothing at all to log against — no definition and no profile,
     * archived ones included (spec §9). It gates "Set up from template", and archive is not delete
     * (R-9): a retired reading is still a reading the asset has, and the template would be refused.
     */
    val bare: Boolean = false,
    /** The parent for the "Part of <parent>" line, tappable; both null for a root asset (spec §9). */
    val parentId: String? = null,
    val parentName: String? = null,
    /** This asset's children, by name. The COMPONENTS section always renders, empty or not. */
    val components: List<ComponentRow> = emptyList(),
    /**
     * 1.2 — the asset's **own** schedules, from the shared projection (master plan decision 38).
     * A group-targeted schedule it is a member of is not here: that obligation is counted once, on
     * the group, and [groups] is how it is reached.
     */
    val schedules: List<DueItem> = emptyList(),
    /** 1.2 — the groups this asset holds an **open** membership window in. */
    val groups: List<AssetGroupRow> = emptyList(),
    /**
     * #67 (R67-5): the newest purchase invoice or receipt's display name, for the Details fact
     * (P67-11) — text, not a link; null when the asset has none.
     */
    val purchaseDocument: String? = null,
    /**
     * #82 (C10, R82-7): P82-10 "Log incident" leads the Condition section, first and tonal, while
     * the asset needs an Incident — in service, DOWN or DEGRADED, and none for its current failure
     * (`needsIncident`). Computed where [conditionHistory] is, from the same rows and [events].
     */
    val leadsWithLogIncident: Boolean = false,
    /** #79 (C10): the Warranty section's facts, from the row already read and `Today`. */
    val warranty: WarrantyFacts = WarrantyFacts(),
    /** #79 (C20): this asset's service cases, from one `observeForAsset` — open first, then closed as history. */
    val cases: List<ServiceCaseRow> = emptyList(),
    /**
     * #79 (C20): the Incident of the current failure (`currentIncident`), from the same rows and journal
     * as [leadsWithLogIncident]. P79-19 opens the case editor on it, or — null — an Incident entry first.
     */
    val currentIncidentId: String? = null,
    /** #72 (C16): the Lending section's facts, from one `observeForAsset` and `Today`. */
    val loans: LoanFacts = LoanFacts(),
    /**
     * #77 (C19): the open OUT records naming this asset, latest first — non-empty iff the asset is **held** here.
     * Keyed on the records, never on ARCHIVED: an ordinary archived asset keeps every action (AC 11), and a
     * held-but-ACTIVE one (merged history) is read-only all the same.
     */
    val transferredOut: List<TransferOutRow> = emptyList(),
) {
    /** #77 (C19): transferred out from this phone by an open OUT. */
    val held: Boolean get() = transferredOut.isNotEmpty()

    /**
     * #77 (R77-4): a held asset is inspectable and offers no write — the action grid but Backup, every section
     * action, and the overflow but Delete are hidden. Everything else offers what it always did.
     */
    val offersWrites: Boolean get() = !held

    /** The overflow: Delete alone for a held asset (R77-4's one destructive exception); else the shipped four and P77-1. */
    val menu: List<DetailMenuItem>
        get() = if (held) {
            listOf(DetailMenuItem.DELETE)
        } else {
            listOf(
                DetailMenuItem.EDIT,
                if (asset.status == AssetStatus.ACTIVE) DetailMenuItem.ARCHIVE else DetailMenuItem.UNARCHIVE,
                if (asset.isRetired) DetailMenuItem.UNRETIRE else DetailMenuItem.RETIRE,
                DetailMenuItem.TRANSFER,
                DetailMenuItem.DELETE,
            )
        }

    /** The current condition (S1–S3, or S4 when null), from the same read as [health]. */
    val condition: ConditionView? get() = health.condition

    /**
     * Today is out of season: the **season phase** (spec §3.1), never the `MM-DD` window alone, so a
     * MANUAL asset after an END reads out of season exactly as the list does.
     */
    val outOfSeason: Boolean get() = season.phase == SeasonPhase.OUT_OF_SEASON

    /** The identity plate's badges: condition, then retired, archived, an open loan, and the season phase. */
    val plate: List<PlateFact> get() = plateFacts(asset, condition, season, loans.standing, held)

    /** The Health section, in its order (spec §6.5, inv. 119). */
    val healthBlocks: List<HealthBlock> get() = healthBlocksOf(health)

    /**
     * Whether the Condition section offers S7 "Mark operational": a DOWN or DEGRADED asset **in
     * service** — active and not retired — as B07's `AssetHealthView.inService` rules for every
     * surface (M1). A retired or archived asset's page still shows its condition and S6.
     */
    val offersMarkOperational: Boolean
        get() = offersWrites && health.inService && condition?.condition?.needsAttention == true

    /**
     * #82 (C10, R82-7): whether the Condition section offers P82-10 "Log incident" — every asset **in
     * service**, whatever its condition, and none retired or archived. Its tap only opens a new
     * INCIDENT entry; [leadsWithLogIncident] says whether it leads or follows S6.
     */
    val offersLogIncident: Boolean
        get() = offersWrites && health.inService

    /** #79 (C20): P79-17/P79-18 above the case rows, or null — hidden — while none is open. */
    val openCasesLine: String?
        get() = openServiceCasesLine(cases.count { it.open })

    /** #79 (C20, R79-17): P79-19 "New service case" only on an asset **in service**, as P82-10 and P79-20. */
    val offersNewServiceCase: Boolean
        get() = offersWrites && health.inService
}

/**
 * One asset and the rows that point at it. [missing] is separate from [state] because "not loaded
 * yet" and "gone" both read as a null state, and only the second one should send the user back —
 * a deep link or a restored back stack can name an asset a backup import has since replaced.
 *
 * Five flows feed the state and `combine` takes three, so the journal's three are folded into one
 * first. `readings` is computed here rather than stored: editing or deleting an event changes the
 * answer on the next emission with no cache to invalidate.
 *
 * **1.4 (B14).** Condition, health and season are read once per emission — B07's
 * [AssetHealthReadModel.forAsset], B04's [GetAssetSeason] and the condition rows through
 * `ConditionHistory` — and re-read on the flows that can change them: this asset's and every
 * descendant's condition rows, this asset's activations and its health subjects, beside the events
 * and schedules already observed. A Start, an End or a new condition therefore redraws the page with
 * no manual refresh. Start and End write through [RecordSeasonActivation] and only from the dialog's
 * confirm (inv. 93); nothing here edits or removes a condition or an activation (inv. 89, 107).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetDetailViewModel(
    private val assets: AssetRepository,
    private val tags: TagRepository,
    private val definitions: DefinitionRepository,
    profiles: ProfileRepository,
    private val events: EventRepository,
    schedules: ScheduleRepository,
    states: ScheduleStateRepository,
    groups: GroupRepository,
    private val due: DueReadModel,
    conditions: ConditionRepository,
    activations: SeasonActivationRepository,
    subjects: HealthSubjectRepository,
    /** #67 (R67-5): this asset's files, observed for the Details fact only — the one accepted second read. */
    attachments: AttachmentRepository,
    private val healthReadModel: AssetHealthReadModel,
    private val getSeason: GetAssetSeason,
    private val recordActivation: RecordSeasonActivation,
    private val archiveAsset: ArchiveAsset,
    private val retireAsset: RetireAsset,
    private val deleteAsset: DeleteAsset,
    private val applyTemplate: ApplyTemplate,
    /** Review fix round 1, finding 3: `editTagLabel`'s read-modify-write needs the same transaction every other tag write goes through. */
    private val uow: UnitOfWork,
    private val clock: Clock,
    /** `T` for the season dialog, the same day `RecordSeasonActivation` and the season view read. */
    private val today: Today,
    private val id: AssetId,
    /**
     * #79 (C20): this asset's cases, observed once for the Service cases section. Null lists none — a
     * test that is not about cases; `AppGraph` passes the real repository.
     */
    serviceCases: ServiceCaseRepository? = null,
    /**
     * #72 (C16): this asset's loans, observed once for the Lending section and the plate. Null lends
     * nothing — a test that is not about loans; `AppGraph` passes the real repository.
     */
    loans: AssetLoanRepository? = null,
    /**
     * #77 (C19): the transfer records, observed for this asset's open OUTs. Null holds nothing — a test that is
     * not about transfers; `AppGraph` passes the real records.
     */
    transfers: TransferRecordRepository? = null,
    /** #77 (C23): the phone-only withdrawal. Null withdraws nothing. */
    private val withdrawTransfer: WithdrawTransferRecord? = null,
    /** #77 (C22, R77-23): the one sweep after a lifecycle write or a withdrawal. Null sweeps nothing. */
    private val reconcile: ReminderReconcile? = null,
) : ViewModel() {

    constructor(graph: AppGraph, id: String) : this(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.conditions, graph.seasonActivations, graph.healthSubjects, graph.attachments,
        graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, graph.today, AssetId(id),
        serviceCases = graph.serviceCases,
        loans = graph.loans,
        transfers = graph.transferRecords,
        withdrawTransfer = graph.withdrawTransferRecord,
        reconcile = graph.reminderReconcile,
    )

    /** The zone the retirement dialog's today is read in: the user's calendar day. */
    private val zone: ZoneId = ZoneId.systemDefault()

    /** Every asset, because this screen needs its parent and its children as well as itself. */
    private val rows = assets.observeAll()

    private val asset = rows.map { all -> all.firstOrNull { it.id == id } }

    private val journal = combine(
        definitions.observeForAsset(id),
        profiles.observeForAsset(id),
        events.observeForAsset(id),
    ) { defs, profileRows, eventRows -> Journal(defs, profileRows, eventRows) }

    /**
     * The two maintenance tables and this asset's open memberships, folded into one signal.
     *
     * None of the three is what a section *says* — each is the cue to re-derive from the shared
     * projection (invariant 18). The group flow carries the rows as well, because the groups section
     * is exactly `GroupRepository.observeForAsset`'s answer and nothing more.
     */
    private val maintenance = combine(
        schedules.observeAll(),
        states.observeAll(),
        groups.observeForAsset(id),
    ) { _, _, groupRows -> groupRows }

    /**
     * 1.4 — the cue to re-read condition, health and season. The condition rows of this asset **and
     * of every descendant** (the plate, the component badges and the Health section's DOWN or
     * DEGRADED components all read them), this asset's activations and its health subjects. Like
     * [maintenance], none of these is what a section says; each is the signal to read again.
     */
    private val facts: Flow<Unit> = combine(
        // Re-subscribed only when the tree under the asset changes, so an edit of the asset row
        // (which `state` already hears through `rows`) rebuilds the page once, not twice.
        rows.map { all -> listOf(id) + AssetTree.descendants(all, id) }
            .distinctUntilChanged()
            .flatMapLatest { watched -> combine(watched.map { conditions.observeForAsset(it) }) { } },
        activations.observeForAsset(id),
        subjects.observeForAsset(id),
    ) { _, _, _ -> }

    /**
     * #67 (R67-5): the Details fact's one observation of this asset's files — the newest receipt's
     * name, or null. It is folded in after the page's own combine, so a file landing re-derives this
     * one line and never rebuilds the page.
     */
    private val purchaseDocument: Flow<String?> = attachments.observeForOwner(AttachmentOwner.OfAsset(id))
        .map { files -> purchaseDocumentOf(files) }
        .distinctUntilChanged()

    /**
     * #79 (C20): the Service cases section's one observation — this asset's cases, as rows. Folded in
     * after the page's own combine, as [purchaseDocument] is, so a case written re-derives the section
     * and never rebuilds the page. No read per case.
     */
    private val cases: Flow<List<ServiceCaseRow>> = (serviceCases?.observeForAsset(id) ?: flowOf(emptyList()))
        .map { rows -> serviceCaseRowsOf(rows) }
        .distinctUntilChanged()

    /**
     * #72 (C16): the Lending section's one observation — this asset's loans. Folded in after the page's
     * own combine, as [cases] is, so a lend or a return re-derives the section and the plate and never
     * rebuilds the page. The standing is `Today`'s, never the clock's.
     */
    private val loanRows: Flow<List<AssetLoan>> = (loans?.observeForAsset(id) ?: flowOf(emptyList()))
        .distinctUntilChanged()

    /**
     * #77 (C19): this asset's open OUT records — re-read whenever the held set moves — folded in right after the
     * page's own combine, so the loan facts that follow see [AssetDetailState.held].
     */
    private val openOutRecords: Flow<List<TransferRecord>> = (
        transfers?.let { repo -> repo.observeHeldIds().map { held -> if (id in held) openOuts(repo.forAsset(id)) else emptyList() } }
            ?: flowOf(emptyList())
        ).distinctUntilChanged()

    val state: StateFlow<AssetDetailState?> =
        combine(rows, tags.observeForAsset(id), journal, maintenance, facts) { all, tagRows, j, groupRows, _ ->
            val row = all.firstOrNull { it.id == id } ?: return@combine null
            val parent = row.parentAssetId?.let { parentId -> all.firstOrNull { it.id == parentId } }
            // A delete between the row above and this read leaves nothing to draw, not a crash.
            val season = try {
                getSeason.run(id)
            } catch (gone: NoSuchAsset) {
                return@combine null
            }
            val histories = healthReadModel.conditionHistories()
            val health = healthReadModel.forAsset(id)
            AssetDetailState(
                asset = row,
                health = health,
                season = season,
                conditionHistory = histories[id]?.let { historyOf(it) }.orEmpty(),
                // #82 (C10): the same rows and journal the page already holds — no further read.
                leadsWithLogIncident = needsIncident(health.inService, histories[id]?.ordered.orEmpty(), j.events),
                // #79 (C20): the same rows and journal again — the Incident P79-19 opens a case on.
                currentIncidentId = currentIncident(histories[id]?.ordered.orEmpty(), j.events)?.id?.value,
                tags = tagRows,
                definitions = j.definitions,
                // An archived profile keeps its history but stops offering a quick action.
                profiles = j.profiles.filter { p -> p.archivedAt == null },
                events = j.events,
                readings = LatestReadings.of(j.definitions, j.events),
                // Both lists unfiltered on purpose: archived rows count as rows the asset has.
                bare = j.definitions.isEmpty() && j.profiles.isEmpty(),
                parentId = parent?.id?.value,
                parentName = parent?.name,
                components = componentsOf(all, histories),
                // #79 (C10, K7): the `Today` port's date, the one the reminder sweep reads — never the clock.
                warranty = warrantyFactsOf(row, today.localDate()),
                // Asset-targeted only (decision 38). `forAsset` deliberately answers with the group
                // schedules too, because the scan sheet wants both; this screen counts a group
                // obligation once, on the group.
                schedules = due.forAsset(id).filter { it.target is ScheduleTarget.AssetTarget },
                groups = groupRows
                    .sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
                    .map { AssetGroupRow(it.id, it.name) },
            )
        }.combine(openOutRecords) { page, open -> page?.withTransfer(open) }
            .combine(purchaseDocument) { page, document -> page?.copy(purchaseDocument = document) }
            .combine(cases) { page, rows -> page?.copy(cases = rows) }
            .combine(loanRows) { page, rows ->
                // #77 (C19): a held asset offers no lending, whatever its lifecycle.
                page?.copy(loans = loanFactsOf(rows, today.localDate(), page.asset.inService && !page.held))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

    val missing: StateFlow<Boolean> = asset
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), false)

    /** Anything the screen should say out loud but has no room for: one line, shown once. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _prompt = MutableStateFlow<DetailPrompt?>(null)
    val prompt: StateFlow<DetailPrompt?> = _prompt.asStateFlow()

    /** One shot once the asset is gone: the screen pops instead of redrawing an empty plate. */
    private val _deleted = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    /**
     * Each child's own current readings, counted. The repositories are queried per child on every
     * emission rather than observed: a handful of children is a handful of indexed lookups, and an
     * observer per child would have to be torn down and rebuilt whenever the tree changed.
     */
    private suspend fun componentsOf(all: List<Asset>, histories: Map<AssetId, ConditionHistory>): List<ComponentRow> =
        AssetTree.children(all, id)
            .sortedBy { it.name.lowercase() }
            .map { child ->
                val childReadings = LatestReadings.of(definitions.forAsset(child.id), events.forAsset(child.id))
                ComponentRow(
                    id = child.id.value,
                    name = child.name,
                    category = child.category,
                    outOfRange = childReadings.count {
                        it.state == RangeState.LOW || it.state == RangeState.HIGH
                    },
                    condition = histories[child.id]?.let { healthReadModel.conditionViewOf(it) },
                )
            }

    /**
     * S21's rows: **every** row of the asset, newest first — the ordering key reversed (dec. 41) —
     * each with its word, day, time, reason and link. A link naming an event that has since been
     * deleted is kept and marked, never hidden and never an error (spec §5.3). The row's zone is not
     * read: nothing here draws one, so a restored zone this device cannot resolve can harm nothing.
     */
    private suspend fun historyOf(history: ConditionHistory): List<ConditionHistoryRow> =
        history.ordered.asReversed().map { row ->
            val linked = row.eventId?.let { events.get(it) }
            ConditionHistoryRow(
                id = row.id,
                condition = row.condition,
                occurredOn = row.occurredOn,
                occurredTime = row.occurredTime,
                reason = row.reason,
                eventId = row.eventId,
                eventExists = linked != null,
                eventTitle = linked?.title,
            )
        }

    /**
     * #77 (C19): the P77-32 block's rows, latest first; a held asset's facts that would offer a write or a reminder
     * line (the incident lead, the warranty's reminder) are quiesced here, the rest through [AssetDetailState.held].
     */
    private fun AssetDetailState.withTransfer(open: List<TransferRecord>): AssetDetailState {
        if (open.isEmpty()) return this
        return copy(
            transferredOut = open.sortedWith(compareByDescending<TransferRecord> { it.at }.thenBy { it.id }).map { out ->
                val short = shortPackId(out.packId)
                TransferOutRow(
                    packId = out.packId,
                    on = TransferStrings.transferredOn(TransferStrings.day(out.at, zone)),
                    pack = TransferStrings.packLine(short),
                    note = out.note.takeIf { it.isNotBlank() },
                    withdrawTitle = TransferStrings.withdrawTitle(short),
                )
            },
            leadsWithLogIncident = false,
            warranty = warranty.copy(inService = false),
        )
    }

    /** #77 (R77-23): archive, unarchive, retire and unretire each sweep once, after a successful write. */
    fun archive() = lifecycle { archiveAsset.run(id) }

    fun unarchive() = lifecycle { archiveAsset.unarchive(id) }

    /** One lifecycle write, then — only if it landed — the one sweep. A write refused as transferred out says P77-35. */
    private fun lifecycle(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (e: AssetTransferredOut) {
                refuse(TransferImportStrings.ASSET_TRANSFERRED_OUT)
                return@launch
            }
            sweepOnce()
        }
    }

    /** R77-23's one sweep; a sweep that fails is the backstop's to repeat, never the write's failure. */
    private suspend fun sweepOnce() {
        val sweep = reconcile ?: return
        try {
            sweep.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The next sweep repeats it.
        }
    }

    /** #77 (C23): P77-62 — asks before anything is written. Offered only for an open OUT. */
    fun askWithdraw(packId: String) {
        val row = state.value?.transferredOut?.firstOrNull { it.packId == packId } ?: return
        _prompt.update { DetailPrompt.Withdraw(packId, row.withdrawTitle) }
    }

    /** #77 (C23): P77-65 — appends the WITHDRAWN, then one sweep (R77-23). The asset stays archived. */
    fun withdraw() {
        val prompt = _prompt.value as? DetailPrompt.Withdraw ?: return
        if (prompt.saving) return
        val use = withdrawTransfer ?: return
        _prompt.value = prompt.copy(saving = true)
        viewModelScope.launch {
            val result = try {
                use.run(id, prompt.packId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refuse(COULD_NOT_UPDATE_THIS_ASSET)
                return@launch
            }
            if (result is WithdrawTransferResult.Withdrawn) sweepOnce()
            _prompt.update { null }
        }
    }

    /** Opens the retirement dialog on today, which the user may then backdate (spec §7). */
    fun askRetire() = _prompt.update { DetailPrompt.Retire(clock.nowMillis().asLocalDate(zone).toString()) }

    fun askDelete() = _prompt.update { DetailPrompt.ConfirmDelete }

    fun dismissPrompt() = _prompt.update { null }

    /**
     * Opens **Start season** (S42) or **End season** (S44) on today, ranged from the latest
     * activation's date to today (spec §3.3). Only the action the Season section offers opens —
     * S40 on a MANUAL asset out of season, S41 in season — and opening writes nothing.
     */
    fun askSeason(action: SeasonAction) {
        val season = state.value?.season ?: return
        if (manualAction(season) != action) return
        val t = today.localDate()
        _prompt.update { DetailPrompt.SeasonChange(action, t.toString(), latestActivationOn(season), t) }
    }

    /** The dialog's date field. Editing it clears a refusal: the next confirm judges the new value. */
    fun onSeasonDate(text: String) = _prompt.update { prompt ->
        if (prompt is DetailPrompt.SeasonChange && !prompt.saving) prompt.copy(date = text, refusal = null) else prompt
    }

    /**
     * The dialog's confirm (S40 or S41), the **only** way this screen writes a season row (inv. 93).
     *
     * A date outside `[latest row, today]` is answered with S54 and writes nothing. Otherwise one
     * [ActivationCommand] goes to `RecordSeasonActivation` with the chosen date and no event. A row
     * that landed since the dialog opened is answered too: a repeated START or END (a race) closes
     * the dialog with S56 or S57, and a date the newer row now puts out of range redraws S54 naming
     * its date. An asset that has left MANUAL meanwhile simply closes the dialog; the page redraws in
     * its new mode, which says why.
     */
    fun confirmSeason() {
        val prompt = _prompt.value as? DetailPrompt.SeasonChange ?: return
        if (!prompt.canConfirm) return
        val on = prompt.parsed ?: return
        val from = prompt.from
        if (on.isAfter(prompt.today) || (from != null && on.isBefore(from))) {
            _prompt.value = prompt.copy(refusal = from?.let { chooseADateFrom(displayDate(it)) })
            return
        }
        // Set before the first suspension, so a second tap on the confirm finds `saving` and stops.
        _prompt.value = prompt.copy(saving = true, refusal = null)
        viewModelScope.launch {
            val command = ActivationCommand(action = prompt.action, occurredOn = on.toString())
            when (val failure = runCatching { recordActivation.run(id, command) }.exceptionOrNull()) {
                null -> _prompt.update { null }
                is SeasonAlreadyStarted -> refuse(THE_SEASON_IS_ALREADY_RUNNING)
                is SeasonAlreadyEnded -> refuse(THE_SEASON_HAS_ALREADY_ENDED)
                is SeasonValidation -> {
                    // The asset may have gone since the write was refused: then there is no row to
                    // name and no dialog to keep, and the page leaves through `missing`.
                    val season = runCatching { getSeason.run(id) }.getOrNull()
                    if (season == null) {
                        _prompt.update { null }
                    } else {
                        val latest = latestActivationOn(season)
                        _prompt.update {
                            prompt.copy(from = latest, refusal = latest?.let { chooseADateFrom(displayDate(it)) })
                        }
                    }
                }
                is SeasonNotManual -> _prompt.update { null }
                is AssetTransferredOut -> refuse(TransferImportStrings.ASSET_TRANSFERRED_OUT)
                else -> refuse(COULD_NOT_UPDATE_THIS_ASSET)
            }
        }
    }

    /**
     * Retirement commits on its own (spec §7). Only once the date is written is logging what
     * happened *offered*, as a second dialog: declining it — or cancelling the entry it opens —
     * leaves the asset retired, which is why this is two steps and not one wizard.
     */
    fun retire(on: String) {
        viewModelScope.launch {
            when (runCatching { retireAsset.retire(id, on) }.exceptionOrNull()) {
                null -> {
                    sweepOnce()
                    _prompt.update { DetailPrompt.LogWhatHappened }
                }
                is AssetValidation -> refuse("Enter a date as YYYY-MM-DD")
                is AssetTransferredOut -> refuse(TransferImportStrings.ASSET_TRANSFERRED_OUT)
                else -> refuse("Could not retire this asset.")
            }
        }
    }

    fun unretire() {
        viewModelScope.launch {
            when (runCatching { retireAsset.unretire(id) }.exceptionOrNull()) {
                null -> sweepOnce()
                is AssetTransferredOut -> refuse(TransferImportStrings.ASSET_TRANSFERRED_OUT)
                else -> refuse(COULD_NOT_UPDATE_THIS_ASSET)
            }
        }
    }

    /**
     * The one destructive action, already confirmed by the time it is called. A parent is refused
     * with its children named (spec §5) rather than cascading: nobody should lose a sub-assembly to
     * a delete they pictured as being about one machine.
     */
    fun delete() {
        viewModelScope.launch {
            when (val failure = runCatching { deleteAsset.run(id) }.exceptionOrNull()) {
                null -> {
                    _prompt.update { null }
                    _deleted.tryEmit(Unit)
                }
                is AssetHasChildren -> {
                    val names = namesOf(failure.children)
                    _prompt.update { DetailPrompt.DeleteRefused(names) }
                }
                // Invariant 8: the asset's membership windows are part of the basis of a recorded
                // group round, so the delete does not proceed. It says the shipped line and no
                // more: the string table ratifies no sentence for this refusal, and a brief may not
                // draft one.
                is AssetMembershipReferenced -> refuse("Could not delete this asset.")
                else -> refuse("Could not delete this asset.")
            }
        }
    }

    /** Closes whatever dialog is open and says why, once. */
    private fun refuse(line: String) {
        _prompt.update { null }
        _messages.tryEmit(line)
    }

    /** The refused children by name, so the dialog names them; an id only if one has since gone. */
    private suspend fun namesOf(children: List<AssetId>): List<String> {
        val byId = assets.all().associateBy { it.id }
        return children.map { byId[it]?.name ?: it.value }
    }

    /**
     * Seeds this asset from one of the starter templates. The state flow carries the result, so
     * nothing is echoed back on success; the two ways it can do nothing are worth a line each.
     */
    fun setUpFromTemplate(key: String) {
        viewModelScope.launch {
            val template = SeedTemplates.byKey(key)
            if (template == null) {
                _messages.tryEmit("That template is not available.")
                return@launch
            }
            val outcome = runCatching { applyTemplate.run(id, template) }
            when {
                outcome.exceptionOrNull() is AssetTransferredOut ->
                    _messages.tryEmit(TransferImportStrings.ASSET_TRANSFERRED_OUT)
                outcome.isFailure -> _messages.tryEmit("Could not set up this asset.")
                outcome.getOrNull() is ApplyResult.AlreadySetUp ->
                    _messages.tryEmit("This asset is already set up.")
            }
        }
    }

    /**
     * The tags section's inline "Tag placement" edit (#49 AC 4, invariant 59). This is the label's
     * *only* write path from the asset detail screen: it reads the row back, changes `label` and
     * `updated_at`, and writes it straight through [TagRepository.upsert] — never through the
     * tag-provisioning or tag-binding write paths, so no NFC payload is re-encoded and no binding
     * field moves. A blank value clears the placement rather than being refused (an ordinary
     * one-tag asset has no placement to type).
     *
     * The read and the write are one [UnitOfWork.write] transaction (review fix round 1,
     * finding 3): every other read-modify-write of a tag row in this codebase already is one —
     * a scan's `lastScannedAt` stamp can otherwise interleave between this function's read and
     * its write and be lost when this edit's stale copy is written back.
     */
    fun editTagLabel(id: TagId, label: String?) {
        viewModelScope.launch {
            try {
                uow.write {
                    val row = tags.get(id) ?: return@write
                    val trimmed = label?.trim()?.takeIf { it.isNotEmpty() }
                    if (trimmed == row.label) return@write
                    tags.upsert(row.copy(label = trimmed, updatedAt = clock.nowMillis()))
                }
            } catch (e: AssetTransferredOut) {
                // #77 (B4 hand-off 1): the guarded tag port refuses a held asset's tag; a stale screen says so.
                _messages.tryEmit(TransferImportStrings.ASSET_TRANSFERRED_OUT)
            }
        }
    }

    /** The three journal flows as one value, so the outer `combine` takes three slots, not five. */
    private data class Journal(
        val definitions: List<MeasurementDefinition>,
        val profiles: List<EventProfile>,
        val events: List<AssetEvent>,
    )
}

// ------------------------------------------------------------------------------------------------
// 1.4 (B14) — asset detail's Condition, Health and Season sections (spec §10.3).
//
// The words below are RATIFIED (spec §10.7), each by its S-number and verbatim; `<…>` is the one
// substitution. B14 owns S21, S24, S39, S42–S50, S54, S56, S57, S94, S107 and S138 (master §19) and
// only uses B12's and B10's.
// ------------------------------------------------------------------------------------------------

/** S21, section. */
const val CONDITION_HISTORY = "Condition history"

/** S24, a condition row's link to an event that has since been deleted (spec §5.3). */
const val LINKED_RECORD_REMOVED = "The linked record was removed."

/** S39, the phase word for IN_SEASON. The out-of-season word is the shipped [OUT_OF_SEASON]. */
const val IN_SEASON_WORD = "IN SEASON"

/** S42, dialog title. */
const val START_THE_SEASON = "Start the season?"

/** S43, "Maintenance set to follow the season becomes active again from <date>.", the dialog's date substituted. */
fun startSeasonBody(date: String): String =
    "Maintenance set to follow the season becomes active again from $date."

/** S44, dialog title. */
const val END_THE_SEASON = "End the season?"

/** S45, dialog body. */
const val END_SEASON_BODY =
    "Maintenance set to follow the season waits until you start it again. Nothing is marked done."

/** S46, history row. */
const val SEASON_STARTED = "Season started"

/** S47, history row. */
const val SEASON_ENDED = "Season ended"

/** S48, section. */
const val SEASON_HISTORY = "Season history"

/** S49, "Next season starts <date>": a CALENDAR asset out of season. */
fun nextSeasonStartsLine(date: String): String = "Next season starts $date"

/** S50, "Season ends <date>": a CALENDAR asset in season. */
fun seasonEndsLine(date: String): String = "Season ends $date"

/** S54, "Choose a date from <date> to today.", `<date>` the latest activation's day. */
fun chooseADateFrom(date: String): String = "Choose a date from $date to today."

/** S56, refusal: a START raced by another START. */
const val THE_SEASON_IS_ALREADY_RUNNING = "The season is already running."

/** S57, refusal: an END raced by another END. */
const val THE_SEASON_HAS_ALREADY_ENDED = "The season has already ended."

/** S94, section. */
const val HEALTH_SECTION = "Health"

/** S107, the Health section's footer (spec §6.6). */
const val HEALTH_FOOTER =
    "Health is an estimate from dates and records, not a diagnosis. It never changes the condition."

/** S138, fallback: TRACK_ONE's subject is gone and WORST is shown instead (spec §6.5). */
const val FALLBACK_TO_WORST = "The subject to follow is missing, so the worst subject is shown."

/** The shipped line this screen already says when a lifecycle write fails for no stated reason. */
internal const val COULD_NOT_UPDATE_THIS_ASSET = "Could not update this asset."

/**
 * One row of S21 "Condition history" (spec §5.1): a recorded fact, drawn and never edited — no row
 * here can be changed or removed (inv. 89, 107), and a correction is a newer row (inv. 110).
 *
 * [eventExists] is false when [eventId] names an event that has since been deleted: the row then
 * draws S24 in place of the link and keeps every other field (spec §5.3). [eventTitle] is the
 * linked event's own title while it exists — the link, drawn as the record it points at.
 */
data class ConditionHistoryRow(
    val id: String,
    val condition: OperationalCondition,
    /** The row's own `YYYY-MM-DD`. */
    val occurredOn: String,
    /** `HH:MM`, or null when only the day was recorded. */
    val occurredTime: String?,
    /** As recorded; drawn as S23 when empty. */
    val reason: String,
    val eventId: EventId?,
    val eventExists: Boolean,
    val eventTitle: String?,
) {
    /** S24: the row names an event, and that event is gone. */
    val linkRemoved: Boolean get() = eventId != null && !eventExists
}

/**
 * One badge on the identity plate (spec §10.3, `AssetDetailScreen`'s `plateBadges`). The four are
 * independent facts, and an asset can carry every one of them.
 */
sealed interface PlateFact {
    /** The condition badge, always present: S1–S3 with S22, or S4 when nothing is recorded. */
    data class Condition(val view: ConditionView?) : PlateFact

    data object Retired : PlateFact

    /** [label] is the shipped lifecycle word. */
    data class Archived(val label: String) : PlateFact

    data object OutOfSeason : PlateFact

    /** S39 IN SEASON (spec §10.6: "plate, season section"). */
    data object InSeason : PlateFact

    /**
     * #72 (C16, R72-22): an open loan — P72-1 "Lent out", or P72-2 "Loan overdue" once [overdue] (after
     * the due day), with the loan glyph in the neutral tone. Custody, never maintenance (AC 13).
     */
    data class Lent(val overdue: Boolean) : PlateFact

    /** #77 (C19, R77-24): P77-31 `Transferred` with `ic_handover`, in the `seasonInactive` tone, instead of Archived. */
    data object Transferred : PlateFact
}

/**
 * The plate's badges, in the order they are drawn: the condition — the fourth independent fact, on
 * **every** asset, retired and archived ones included (lifecycle bounds only the dashboard, spec
 * §5.1) — then retired and archived, each only when true, then the season phase: out of season, or
 * S39 IN SEASON (spec §10.6, "plate, season section") wherever the Season section draws a phase word
 * — CALENDAR and MANUAL. A YEAR_ROUND asset has no phase word (its section draws S29), so nothing.
 *
 * #72 (C16): an open loan's [loan] standing sits after the lifecycle facts and before the season.
 */
fun plateFacts(
    asset: Asset,
    condition: ConditionView?,
    season: SeasonView,
    loan: LoanStanding? = null,
    /** #77 (C19): transferred out from this phone — P77-31 stands where "Archived" would. */
    held: Boolean = false,
): List<PlateFact> = buildList {
    add(PlateFact.Condition(condition))
    if (asset.isRetired) add(PlateFact.Retired)
    if (held) add(PlateFact.Transferred) else statusLabel(asset.status)?.let { add(PlateFact.Archived(it)) }
    loan?.let { add(PlateFact.Lent(overdue = it == LoanStanding.OVERDUE)) }
    when {
        season.phase == SeasonPhase.OUT_OF_SEASON -> add(PlateFact.OutOfSeason)
        season.seasonMode != SeasonMode.YEAR_ROUND -> add(PlateFact.InSeason)
    }
}

/**
 * One block of the Health section, in the order [healthBlocksOf] gives (spec §10.3, §6.5, §6.6).
 * The engine's data rides along; the words are [words]'s.
 */
sealed interface HealthBlock {
    /** S109: a CRITICAL subject, whatever the aggregate says (inv. 119). */
    data class Critical(val subject: SubjectHealth) : HealthBlock

    /** S27: a DOWN or DEGRADED in-service component, at any depth (inv. 119). */
    data class Component(val component: ComponentCondition) : HealthBlock

    /** The aggregate: its band and S108, or S98 when nothing contributes — never a number (inv. 118). */
    data class Aggregate(val value: SubjectValue.Scored?, val subjects: List<SubjectHealth>) : HealthBlock

    /** S138: the TRACK_ONE subject is gone and the worst subject is shown. */
    data object Fallback : HealthBlock

    /** One non-archived subject: its name, its band and score or S98, and its driver lines. */
    data class Subject(val health: SubjectHealth) : HealthBlock

    /** S107. */
    data object Footer : HealthBlock
}

/**
 * The Health section in order (spec §10.3; inv. 119): **first** every CRITICAL subject and every
 * DOWN or DEGRADED in-service component, then the aggregate — with S138 only when the primary really
 * fell back, which the engine reports only when WORST found a value — then each non-archived subject,
 * then the footer. Which subjects and components appear is never the aggregate's to decide: an
 * AVERAGE that reads NOMINAL still leads with its CRITICAL subject.
 */
fun healthBlocksOf(view: AssetHealthView): List<HealthBlock> = buildList {
    val result = view.result
    result.critical.forEach { add(HealthBlock.Critical(it)) }
    view.components.forEach { add(HealthBlock.Component(it)) }
    add(HealthBlock.Aggregate(result.aggregate, result.subjects))
    if (result.fallback && result.aggregate != null) add(HealthBlock.Fallback)
    result.subjects.filter { it.subject.archivedAt == null }.forEach { add(HealthBlock.Subject(it)) }
    add(HealthBlock.Footer)
}

/**
 * The words one [HealthBlock] draws, in order — the screen draws exactly these, beside the badge
 * glyphs. A value with no band is S98 (`NOT TRACKED`) wherever it appears, never a number (inv. 118).
 * S99's `<age>` and S102 go through [plurals] (B12's `AndroidHealthPlurals` on the screen).
 */
fun HealthBlock.words(plurals: HealthPlurals, format: (LocalDate) -> String): List<String> = when (this) {
    is HealthBlock.Critical -> listOf(criticalLine(subject))
    is HealthBlock.Component -> listOf(componentLine(component))
    is HealthBlock.Aggregate -> value?.let { listOf(healthBadgeLabel(it.band, null), aggregateLine(it.score, subjects)) }
        ?: listOf(NOT_TRACKED)
    HealthBlock.Fallback -> listOf(FALLBACK_TO_WORST)
    is HealthBlock.Subject -> listOf(health.subject.name, subjectBadge(health)) + driverLines(health, plurals, format)
    HealthBlock.Footer -> listOf(HEALTH_FOOTER)
}

/** A subject's band and score, or S98 for a subject with no value. */
fun subjectBadge(subject: SubjectHealth): String = healthBadgeLabel(subject.band, subject.score)

/** The band of a scored subject; null — S98 — for one NOT TRACKED. */
internal val SubjectHealth.band: HealthBand? get() = (value as? SubjectValue.Scored)?.band

/** The score of a scored subject; null for one NOT TRACKED, which is never drawn as a number. */
internal val SubjectHealth.score: Int? get() = (value as? SubjectValue.Scored)?.score

/**
 * The Season section's calendar line (spec §10.3): on a CALENDAR asset, S50 "Season ends <date>"
 * while in season and S49 "Next season starts <date>" while out of it, from `nextBoundaryOn`. A
 * MANUAL asset has **none**: its next START is never predicted (Q-6), and a START is real only once
 * recorded (O-5). YEAR_ROUND has no boundary to name.
 */
fun calendarLine(season: SeasonView, format: (LocalDate) -> String): String? {
    if (season.seasonMode != SeasonMode.CALENDAR) return null
    val on = season.nextBoundaryOn ?: return null
    return if (season.phase == SeasonPhase.IN_SEASON) seasonEndsLine(format(on)) else nextSeasonStartsLine(format(on))
}

/**
 * The one manual action the Season section offers: START (S40) while a MANUAL asset is out of
 * season, END (S41) while it is in. Nothing on any other mode.
 */
fun manualAction(season: SeasonView): SeasonAction? = when {
    season.seasonMode != SeasonMode.MANUAL -> null
    season.phase == SeasonPhase.IN_SEASON -> SeasonAction.END
    else -> SeasonAction.START
}

/**
 * S48's rows, **newest first** (dec. 41), in **every** mode (the controller's ruling on I-2): an asset
 * that left MANUAL keeps its old rows readable (spec §3.2), even though its phase no longer reads
 * them. `SeasonView.activations` is already in the phase's order `(occurredOn, createdAt, id)`.
 */
fun seasonHistory(season: SeasonView): List<SeasonActivation> = season.activations.asReversed()

/**
 * Whether the Season section draws S48: whenever rows exist, and always on a MANUAL asset — whose
 * empty history (only an import makes one with no row) is still its history.
 */
fun seasonHistoryShown(season: SeasonView): Boolean =
    season.seasonMode == SeasonMode.MANUAL || season.activations.isNotEmpty()

/** S46 or S47. */
fun activationWord(action: SeasonAction): String = when (action) {
    SeasonAction.START -> SEASON_STARTED
    SeasonAction.END -> SEASON_ENDED
}

/**
 * The latest activation's day — the lower bound of Start and End (spec §3.3); null when there is
 * none. `SeasonView.activations` arrives in `:core`'s own activation order, so the latest is the last.
 */
internal fun latestActivationOn(season: SeasonView): LocalDate? =
    season.activations.lastOrNull()?.let { runCatching { LocalDate.parse(it.occurredOn) }.getOrNull() }

/** One row of the "Part of" picker. [id] null is "None", which is also the default (spec §9). */
data class ParentChoice(val id: String?, val label: String)

/**
 * The keys [AssetEditState.problems] is keyed by: one per field the form can mark. They are the
 * screen's own names for its inputs, and three of them are spelled the way
 * [AssetProblem.BadDate] spells them, so a date problem needs no translation table.
 */
object AssetField {
    const val NAME = "name"
    const val PARENT = "parent"
    const val SEASON_START = "seasonStart"
    const val SEASON_END = "seasonEnd"
    const val BREAK_START = "breakStart"
    const val BREAK_END = "breakEnd"
    const val PURCHASE_ON = "purchaseOn"
    const val IN_SERVICE_ON = "inServiceOn"
    const val PRICE = "price"
    const val CURRENCY = "currency"
    const val WARRANTY_EXPIRES_ON = "warrantyExpiresOn"

    /** #79 (C11): the warranty reminder's lead, "Remind me N days early". */
    const val WARRANTY_LEAD = "warrantyLead"
}

/** One row of the asset editor's "Health subjects" (S111): the subject's name, archived ones marked. */
data class SubjectRow(val id: String, val name: String, val archived: Boolean)

/**
 * One `MM-DD` field as the form draws it (spec §10.4; master dec. 46, the controller's ruling on I10).
 *
 * [label] is the ratified word; [required] puts the **non-verbal** required mark on it — an asterisk,
 * [drawnLabel] — while its pair is required and not yet two real month-days; [outlined] is the field's
 * error outline; [problem] is the one line under it, and only ever the shipped "Not a real month and
 * day". A missing or half-filled pair draws **no sentence**: Save is held instead, and the asterisks
 * say which fields hold it.
 */
data class MonthDayInput(
    val label: String,
    val required: Boolean,
    val text: String,
    val outlined: Boolean,
    val problem: String?,
) {
    val drawnLabel: String get() = if (required) requiredMark(label) else label
}

/** The shipped line under a month-day that is not one. */
internal const val NOT_A_REAL_MONTH_AND_DAY = "Not a real month and day"

/**
 * The non-verbal required mark (the controller's ruling on I10 and the plan-review follow-up's F4): an asterisk
 * in the ratified label, never a word.
 */
internal fun requiredMark(label: String): String = "$label *"

/** Whether [text] is a real `MM-DD`, by the shipped rule every season and break command uses. */
internal fun isMonthDay(text: String): Boolean {
    val value = text.trim()
    return value.isNotEmpty() && SeasonWindow.validate(value, value).isEmpty()
}

/**
 * #67, C5: one picked file, its role, and the sentence a failed copy leaves behind. Nothing is
 * copied and no row exists until Save's settings write has landed — this is only the promise of
 * one, held in memory, forgotten by Remove or by process death alike (R67-2).
 */
data class StagedDocument(
    val role: DocumentRole,
    val file: PickedFile,
    /** Null until a copy of this file fails; the line then shows this instead of P67-10. */
    val problem: String? = null,
)

/** #67, R67-6: one of the asset's own role-tagged attachments, as the editor lists it read-only. */
data class AttachedDocument(val role: DocumentRole, val displayName: String)

/**
 * The grouped form of spec §9, as text. Every field is a string because that is what the person
 * typed; the command the use case validates is built once, on save, so a half-typed price or a
 * half-typed date is a thing the form still holds rather than a thing it has already refused.
 *
 * [problems] is empty until a save is refused — no field is red before the user has tried
 * anything — and editing a field clears its own mark. [templateTouched] is the memory the hint
 * rule of §8 needs: a category suggestion pre-selects a template only while the answer to "did
 * you choose one yourself?" is still no.
 *
 * **1.4 (B10).** The season, the maintenance break and the health policy are four parts of one
 * save (spec §10.4; `SaveAssetSettings`), and **nothing in them is decided for the owner**: the
 * manual phase has no default, the break is off unless one is stored and its dates start empty, and
 * "One subject" names no subject until one is chosen. Save is held ([canSave]) instead of any of
 * those becoming a refusal that would need a sentence the spec does not ratify (master dec. 46).
 */
data class AssetEditState(
    val name: String = "",
    val category: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val serialNumber: String = "",
    val description: String = "",
    val location: String = "",
    val parentId: String? = null,
    /** Empty until the picker has loaded; [NO_PARENT] is its first row from then on (spec §5). */
    val parentChoices: List<ParentChoice> = emptyList(),
    /** S28's answer: S29, S30 or S31. */
    val seasonMode: SeasonMode = SeasonMode.YEAR_ROUND,
    /** What the asset holds; a new asset counts as YEAR_ROUND, as `SaveAssetSettings` does. */
    val storedSeasonMode: SeasonMode = SeasonMode.YEAR_ROUND,
    /** S32 and S33, sent only under S30. */
    val seasonStart: String = "",
    val seasonEnd: String = "",
    /** S35's answer, **no default** (inv. 92): asked only on a switch into MANUAL, and sent only then. */
    val manualPhase: SeasonPhase? = null,
    /** S59: off unless a break is stored (inv. 121). */
    val breakOn: Boolean = false,
    /** S60 and S61: empty whenever S59 is turned on. */
    val breakStart: String = "",
    val breakEnd: String = "",
    /** S55, naming the schedules, when the season's change was refused. */
    val seasonRefusal: String? = null,
    /** S63 or S64 (naming the schedules), when the break's change was refused. */
    val breakRefusal: String? = null,
    /** The asset's subjects in `sortOrder`, archived ones included and marked (S111). Existing assets only. */
    val subjects: List<SubjectRow> = emptyList(),
    /** S131's answer. */
    val aggregation: HealthAggregation = HealthAggregation.WORST,
    /** S134's answer under "One subject"; null until chosen. */
    val primaryId: String? = null,
    val purchaseOn: String = "",
    val inServiceOn: String = "",
    val price: String = "",
    val currency: String = "",
    val vendor: String = "",
    val warrantyExpiresOn: String = "",
    /** #79 (C11): the warranty reminder's lead in whole days, as typed; blank is no reminder. */
    val warrantyLead: String = "",
    val warrantyNotes: String = "",
    val notes: String = "",
    val editing: Boolean = false,
    /** #79 (C11): P79-12 is up — after a save that first set a lead, while notifications are not granted. */
    val askingForNotifications: Boolean = false,
    val saving: Boolean = false,
    /** Field key → the one line shown under that field. Empty until a save is refused. */
    val problems: Map<String, String> = emptyMap(),
    /** New assets only. null is "None · set up later", the default; Generic is a choice (§7). */
    val templateKey: String? = null,
    /** True once the user picked a template by hand; category edits stop touching it then (§8). */
    val templateTouched: Boolean = false,
    /** #67, C5: files picked but not yet copied — nothing is written until Save's settings write lands. */
    val staged: List<StagedDocument> = emptyList(),
    /** #67, R67-6: the asset's own role-tagged files, newest by `capturedOn` then `createdAt` first. */
    val attached: List<AttachedDocument> = emptyList(),
    /** #67, R67-13: whether the three document affordances have a folder to copy into. */
    val offersDocuments: Boolean = true,
    /** #67 (R-1): picks whose name and size are still being asked of the provider; Save waits for none. */
    val picksInFlight: Int = 0,
) {
    /** S35 is asked only when S31 is chosen on an asset that is not already MANUAL (inv. 92, UI half). */
    val asksManualPhase: Boolean
        get() = seasonMode == SeasonMode.MANUAL && storedSeasonMode != SeasonMode.MANUAL

    /** S35 as drawn: the asterisk stays until it is answered. */
    val manualQuestionLabel: String
        get() = if (manualPhase == null) requiredMark(IS_THIS_ASSET_IN_SEASON) else IS_THIS_ASSET_IN_SEASON

    val seasonStartInput: MonthDayInput
        get() = monthDay(SEASON_STARTS, seasonStart, seasonWindowReady, AssetField.SEASON_START)
    val seasonEndInput: MonthDayInput
        get() = monthDay(SEASON_ENDS, seasonEnd, seasonWindowReady, AssetField.SEASON_END)
    val breakStartInput: MonthDayInput
        get() = monthDay(BREAK_STARTS, breakStart, breakWindowReady, AssetField.BREAK_START)
    val breakEndInput: MonthDayInput
        get() = monthDay(BREAK_ENDS, breakEnd, breakWindowReady, AssetField.BREAK_END)

    /** S134's choices: the non-archived subjects only, so `HEALTH_PRIMARY_INVALID` is unreachable. */
    val primaryChoices: List<SubjectRow> get() = subjects.filterNot { it.archived }

    /** S134 as drawn: the asterisk stays until a subject is chosen. */
    val primaryQuestionLabel: String
        get() = if (primaryReady) WHICH_SUBJECT else requiredMark(WHICH_SUBJECT)

    /**
     * Save is held — never refused with words — while an answer the owner must give is missing: two
     * real `MM-DD`s under S30 and under S59, S35 on a switch into MANUAL, and S134 under "One subject".
     * #67 (R-1): it is held too while a picked file is still being looked up, and is back the moment
     * the file is staged.
     */
    val canSave: Boolean
        get() = !saving && picksInFlight == 0 && seasonReady && breakWindowReady && primaryReady

    private val seasonWindowReady: Boolean get() = isMonthDay(seasonStart) && isMonthDay(seasonEnd)

    private val seasonReady: Boolean
        get() = when (seasonMode) {
            SeasonMode.YEAR_ROUND -> true
            SeasonMode.CALENDAR -> seasonWindowReady
            SeasonMode.MANUAL -> !asksManualPhase || manualPhase != null
        }

    private val breakWindowReady: Boolean get() = !breakOn || (isMonthDay(breakStart) && isMonthDay(breakEnd))

    private val primaryReady: Boolean
        get() = aggregation != HealthAggregation.TRACK_ONE || primaryChoices.any { it.id == primaryId }

    /**
     * One month-day field. Both of a pair carry the asterisk until the pair is two real month-days; a
     * value that is not one is outlined with the shipped line, and so is one the save was refused for.
     */
    private fun monthDay(label: String, text: String, pairReady: Boolean, field: String): MonthDayInput {
        val malformed = text.isNotBlank() && !isMonthDay(text)
        val problem = if (malformed) NOT_A_REAL_MONTH_AND_DAY else problems[field]
        return MonthDayInput(
            label = label,
            required = !pairReady,
            text = text,
            outlined = problem != null,
            problem = problem,
        )
    }
}

/** The label of the empty choice in the "Part of" picker, and of the asset with no parent. */
const val NO_PARENT = "None"

/**
 * #78 — what the editor asks after a save has been written and before it closes. It writes nothing
 * and decides nothing: the answer only chooses where the editor goes next.
 */
sealed interface EditPrompt {
    /**
     * The asset went from YEAR_ROUND into a season and [count] of its live schedules (ACTIVE or
     * PAUSED, never ARCHIVED) are CONTINUOUS, which keep coming due out of season (P78-1a/1b).
     */
    data class ReconcileSchedules(val count: Int) : EditPrompt
}

/**
 * Create ([id] null) or edit one asset. Every rule lives in the use cases — this turns the form's
 * text into one [AssetCommand], hands it over, and turns whatever comes back into marks under
 * fields or a line for the snackbar.
 *
 * [presetParentId] is the "Part of" a new asset opens with, which is how "+ Add component" on a
 * parent's screen makes a child (spec §9). An existing asset's stored parent always wins over it.
 *
 * **1.4 (B10): one save.** The asset, its season mode, its break and its health policy go to
 * [SaveAssetSettings] as one [AssetSettingsCommand], in one transaction: a refusal of any part writes
 * none of them (spec §10.4; master §10.1). A season or break change that would strand pre-service
 * work is said by name — S55 or S64 with the schedules' titles — and the form stays as typed.
 *
 * **#78: one question after the save.** [schedules] is read, never written: when a save takes an
 * existing asset from YEAR_ROUND into a season and the asset has live CONTINUOUS schedules, the editor
 * holds [saved] back and raises [prompt] instead. The answer finishes the editor through [saved] (keep
 * the schedules as they are) or [review] (open them). Nothing is remembered: the transition is the gate.
 *
 * **#74: the Category field offers the catalog.** [categories] is read, never written: [categoryChoices]
 * follows it. The owner's way in here is a successful save, inside [SaveAssetSettings] (C5); a restore
 * or a merge also adds rows, for the assets it brings.
 *
 * **#79: the warranty reminder's lead** (C11) is the save's fifth part, never a field of [AssetCommand].
 * A save that moved the date or the lead runs [reconcile] once. After a save that first sets a lead —
 * after the copies and the #78 question — and only while notifications are not granted, the editor
 * asks with P79-12 at most once and requests through [notifications] only after "OK": the asset
 * editor is the permission's second requester (R79-16), and the request follows the write (D-22).
 */
class AssetEditViewModel(
    private val assets: AssetRepository,
    private val healthSubjects: HealthSubjectRepository,
    private val saveAssetSettings: SaveAssetSettings,
    private val schedules: ScheduleRepository,
    categories: CategoryRepository,
    private val attachments: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val addAttachment: AddAttachment,
    private val today: Today,
    private val id: AssetId?,
    presetParentId: String? = null,
    /**
     * Where the folder re-read and the staged copies run: `Dispatchers.IO` in the app, and a
     * test's own scheduler in a JVM test, so none of that work outlives the test that started it
     * (mirrors `AttachmentsSectionViewModel`'s own `io`).
     */
    private val io: CoroutineContext = Dispatchers.IO,
    /**
     * #79 (C11, R79-16): the notification permission, taken from the graph as the schedule editor takes
     * it. Null asks nothing — a test that is not about the warranty reminder.
     */
    private val notifications: NotificationPermission? = null,
    /** #79 (C11): the shipped sweep, run once after a save that moved the warranty date or lead. Null runs none. */
    private val reconcile: ReminderReconcile? = null,
) : ViewModel() {

    constructor(graph: AppGraph, id: String?, parentId: String? = null) : this(
        graph.assets, graph.healthSubjects, graph.saveAssetSettings, graph.schedules, graph.categories,
        graph.attachments, graph.attachmentStorage, graph.addAttachment, graph.today,
        id?.let(::AssetId), parentId,
        notifications = graph.notificationPermission,
        reconcile = graph.reminderReconcile,
    )

    private val _state = MutableStateFlow(
        AssetEditState(
            editing = id != null,
            parentId = presetParentId,
            // Once, on a new asset: a currency the person then clears stays cleared (spec §9).
            currency = if (id == null) localeCurrencyCode() else "",
            // Read synchronously, exactly as `AttachmentsSectionViewModel`'s own seed is: a status
            // block flashing over a folder that was there all along would be the wrong first frame.
            offersDocuments = storage.state() is StoreState.Ready,
        ),
    )
    val state: StateFlow<AssetEditState> = _state.asStateFlow()

    /** #67, C6: the route's id, or the id the write minted; null only before the first write. */
    private var savedId: AssetId? = id

    /** #67, C6: the user-editable fields as they were at the last successful write; null before it. */
    private var writtenForm: WrittenForm? = null

    /** #67, C6 step 3: the #78 question decided at the write, held while anything is staged. */
    private var pendingPrompt: EditPrompt? = null

    /** #79 (C11): the lead the form was opened on — none for a new asset. P79-12 follows a first lead only. */
    private var leadAtLoad: Int? = null

    /** #79 (C11): the lead the last successful write stored; the form's own until the first write. */
    private var writtenLead: Int? = null

    /** #79 (C11): the date and lead the reminder sweep last saw from this editor — the loaded ones at first. */
    private var sweptWarranty: Pair<String?, Int?> = null to null

    /** #79 (C11, R79-16): P79-12 is asked at most once per editor. */
    private var rationaleAsked = false

    /** #79 (C11): where the editor goes once P79-12 is answered — the save's own way out, or #78's. */
    private var afterRationale: Pair<AssetId, Exit>? = null

    /**
     * One shot per successful save. A buffer of one and no replay: the screen that started the
     * save is told where to go, and a screen that comes back later is not told again.
     */
    private val _saved = MutableSharedFlow<AssetId>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<AssetId> = _saved.asSharedFlow()

    /** #78: the question a written save asks before the editor closes; null when nothing is asked. */
    private val _prompt = MutableStateFlow<EditPrompt?>(null)
    val prompt: StateFlow<EditPrompt?> = _prompt.asStateFlow()

    /** #78: the editor is done and the owner asked to see the asset's schedules. One shot, as [saved] is. */
    private val _review = MutableSharedFlow<AssetId>(replay = 0, extraBufferCapacity = 1)
    val review: SharedFlow<AssetId> = _review.asSharedFlow()

    /** What has no room under a field: the refused reparent, named (spec §9). One line, once. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * #74 (C16): what the Category field offers — the built-ins in compiled order, then the owner's own
     * categories (R74-10) — straight from the catalog, never from the categories Assets happen to hold.
     * It follows the store, so a category another editor saved is offered here without reopening this
     * one; before the first read it is the built-ins alone, which is what the field offered until #74.
     */
    val categoryChoices: StateFlow<List<CategoryChoice>> = categories.observeAll()
        .map(CategoryCatalog::choices)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), CategoryCatalog.builtIns)

    init {
        viewModelScope.launch {
            val all = assets.all()
            val row = id?.let { existing -> all.firstOrNull { it.id == existing } }
            val subjects = id?.let { healthSubjects.forAsset(it) }.orEmpty()
            // #67, R67-6: the asset's own role-tagged files, read once alongside the row itself —
            // no second load, and the parentChoices wait every test suspends on already gates this.
            val attached = id?.let { existing -> attachments.forOwner(AttachmentOwner.OfAsset(existing)) }
                .orEmpty()
                .let(::attachedDocumentsOf)
            if (row != null) {
                leadAtLoad = row.warrantyReminderLeadDays
                writtenLead = row.warrantyReminderLeadDays
                sweptWarranty = row.warrantyExpiresOn to row.warrantyReminderLeadDays
            }
            _state.update { form ->
                val filled = if (row == null) form else form.filledFrom(row, subjects)
                filled.copy(parentChoices = choicesIn(all), attached = attached)
            }
        }
        // The subject list follows the store, so one added or archived in the subject editor is
        // here on return. Only the list: the stored policy was read once, above.
        id?.let { existing ->
            viewModelScope.launch {
                healthSubjects.observeForAsset(existing).collect { rows ->
                    _state.update { it.copy(subjects = rowsOf(rows)) }
                }
            }
        }
    }

    fun onName(value: String) = edit(AssetField.NAME) { it.copy(name = value) }

    /**
     * The hint rule of spec §8: a category that names a suggestion pre-selects that suggestion's
     * template, but only on a new asset and only while the user has not chosen one by hand. Free
     * text resolves to no template, so the hint follows the field down as well as up.
     */
    fun onCategory(value: String) = _state.update { form ->
        form.copy(
            category = value,
            templateKey = if (!form.editing && !form.templateTouched) {
                CategorySuggestions.templateFor(value)
            } else {
                form.templateKey
            },
        )
    }

    fun onManufacturer(value: String) = edit { it.copy(manufacturer = value) }
    fun onModel(value: String) = edit { it.copy(model = value) }
    fun onSerialNumber(value: String) = edit { it.copy(serialNumber = value) }
    fun onDescription(value: String) = edit { it.copy(description = value) }
    fun onLocation(value: String) = edit { it.copy(location = value) }
    fun onParent(value: String?) = edit(AssetField.PARENT) { it.copy(parentId = value) }

    /**
     * S28's answer. Leaving an answer forgets S35's, so a return to S31 asks again with no default;
     * the typed S32 and S33 stay in the form and are sent only under S30.
     */
    fun onSeasonMode(mode: SeasonMode) =
        edit(AssetField.SEASON_START, AssetField.SEASON_END) { form ->
            if (form.seasonMode == mode) {
                form
            } else {
                form.copy(seasonMode = mode, manualPhase = null, seasonRefusal = null)
            }
        }

    /** S36 or S37, the answer to S35. */
    fun onManualPhase(phase: SeasonPhase) = _state.update { it.copy(manualPhase = phase, seasonRefusal = null) }

    fun onSeasonStart(value: String) =
        edit(AssetField.SEASON_START) { it.copy(seasonStart = value, seasonRefusal = null) }

    fun onSeasonEnd(value: String) =
        edit(AssetField.SEASON_END) { it.copy(seasonEnd = value, seasonRefusal = null) }

    /** S59. Either way both dates start empty: a break is never prefilled (inv. 121). */
    fun onBreak(on: Boolean) =
        edit(AssetField.BREAK_START, AssetField.BREAK_END) {
            it.copy(breakOn = on, breakStart = "", breakEnd = "", breakRefusal = null)
        }

    fun onBreakStart(value: String) =
        edit(AssetField.BREAK_START) { it.copy(breakStart = value, breakRefusal = null) }

    fun onBreakEnd(value: String) =
        edit(AssetField.BREAK_END) { it.copy(breakEnd = value, breakRefusal = null) }

    /** S131's answer. Only "One subject" names a subject, and it starts naming none. */
    fun onAggregation(aggregation: HealthAggregation) = _state.update { form ->
        if (form.aggregation == aggregation) form else form.copy(aggregation = aggregation, primaryId = null)
    }

    /** S134's answer: a non-archived subject of this asset, or nothing. */
    fun onPrimary(subjectId: String) = _state.update { form ->
        if (form.primaryChoices.any { it.id == subjectId }) form.copy(primaryId = subjectId) else form
    }

    fun onPurchaseOn(value: String) = edit(AssetField.PURCHASE_ON) { it.copy(purchaseOn = value) }
    fun onInServiceOn(value: String) = edit(AssetField.IN_SERVICE_ON) { it.copy(inServiceOn = value) }

    fun onPrice(value: String) =
        edit(AssetField.PRICE, AssetField.CURRENCY) { it.copy(price = value) }

    fun onCurrency(value: String) =
        edit(AssetField.CURRENCY, AssetField.PRICE) { it.copy(currency = value) }

    fun onVendor(value: String) = edit { it.copy(vendor = value) }

    /** Editing the date also takes down the lead's line: P79-9 is about the date. */
    fun onWarrantyExpiresOn(value: String) =
        edit(AssetField.WARRANTY_EXPIRES_ON, AssetField.WARRANTY_LEAD) { it.copy(warrantyExpiresOn = value) }

    /** #79 (C11): the lead, as typed. */
    fun onWarrantyLead(value: String) = edit(AssetField.WARRANTY_LEAD) { it.copy(warrantyLead = value) }

    fun onWarrantyNotes(value: String) = edit { it.copy(warrantyNotes = value) }
    fun onNotes(value: String) = edit { it.copy(notes = value) }

    /** null selects "None · set up later"; either way the choice was the user's from now on (§8). */
    fun onTemplate(key: String?) = _state.update { it.copy(templateKey = key, templateTouched = true) }

    /**
     * #67, C5: a file in hand joins the staged list ([stagePicked] is the picker's way in, which
     * looks the file up first). Nothing is copied until Save's write lands. Held while
     * saving (C6, R67-8): the staged list is the one the copies are working through, so a pick that
     * lands then is ignored, as a second Save tap is — the screen does not open the picker then either.
     */
    fun stage(role: DocumentRole, file: PickedFile) = _state.update {
        if (it.saving) it else it.copy(staged = it.staged + StagedDocument(role, file))
    }

    /**
     * #67 (R-1): the picker's way in. The picker has a file but its name and size are still to be
     * asked of the provider, which [lookup] does on [io]; Save is held ([canSave]) from this call
     * until the answer is staged, so a Save tapped in that moment cannot leave the file behind. The
     * count drops however the lookup ends; a lookup that throws still throws, as it did before.
     */
    fun stagePicked(role: DocumentRole, lookup: suspend () -> PickedFile) {
        _state.update { it.copy(picksInFlight = it.picksInFlight + 1) }
        viewModelScope.launch(io) {
            var file: PickedFile? = null
            try {
                file = lookup()
            } finally {
                val landed = file
                _state.update { form ->
                    val settled = form.copy(picksInFlight = form.picksInFlight - 1)
                    if (landed == null || settled.saving) {
                        settled
                    } else {
                        settled.copy(staged = settled.staged + StagedDocument(role, landed))
                    }
                }
            }
        }
    }

    /** #67, C5: forgets one staged file — Remove, on a line not copied yet. Held while saving, as [stage] is. */
    fun unstage(document: StagedDocument) = _state.update {
        if (it.saving) it else it.copy(staged = it.staged - document)
    }

    /**
     * #67, R67-13: re-reads the folder. The screen calls this on entering composition and on
     * return from Settings — the two moments `AttachmentsSectionViewModel.refreshStore` answers
     * the same way for DOCUMENTS.
     */
    fun refreshStore() {
        viewModelScope.launch(io) {
            val ready = storage.state() is StoreState.Ready
            _state.update { it.copy(offersDocuments = ready) }
        }
    }

    /**
     * #78, P78-3 and the back gesture (C2): the season is already saved and the schedules stay as they
     * are, so the editor finishes exactly as a save that asked nothing. Writes nothing, and does nothing
     * once the question has been answered. #67 (C6 step 3): never finishes over a file still staged —
     * the answer takes the question down for good, and the next Save copies what is staged and then
     * finishes without asking again.
     */
    fun keepSchedules() {
        val asset = answered() ?: return
        if (_state.value.staged.isEmpty()) finish(asset, Exit.SAVED)
    }

    /** #78, P78-2 (C2): the editor finishes onto the asset's schedules. Writes nothing either. */
    fun reviewSchedules() {
        val asset = answered() ?: return
        if (_state.value.staged.isEmpty()) finish(asset, Exit.REVIEW)
    }

    /**
     * #79 (C11, R79-16): P79-12's "OK" — the only place this editor requests the permission, after the
     * asset is written (D-22). The answer is a fact about the phone, not about the asset, so the editor
     * finishes either way, the way the save (or #78's answer) chose.
     */
    fun requestNotifications() {
        val (asset, exit) = afterRationale ?: return
        afterRationale = null
        viewModelScope.launch {
            runCatching { notifications?.request() }.onFailure { if (it is CancellationException) throw it }
            _state.update { it.copy(askingForNotifications = false) }
            leave(asset, exit)
        }
    }

    /** #79 (C11): P79-12's "Not now" and any other dismissal. Nothing is requested; the asset still stands. */
    fun dismissNotifications() {
        val (asset, exit) = afterRationale ?: return
        afterRationale = null
        _state.update { it.copy(askingForNotifications = false) }
        leave(asset, exit)
    }

    /** The editor is done: through P79-12 first when [holdForRationale] says so, else straight out. */
    private fun finish(asset: AssetId, exit: Exit) {
        if (holdForRationale(asset, exit)) {
            _state.update { it.copy(askingForNotifications = true) }
        } else {
            leave(asset, exit)
        }
    }

    /**
     * #79 (C11, R79-16): whether P79-12 goes up before the editor leaves — a lead first set in this editor
     * (none when it opened, one stored now), notifications not granted, and not asked before. When it
     * does, [asset] and [exit] wait for the answer.
     */
    private fun holdForRationale(asset: AssetId, exit: Exit): Boolean {
        val permission = notifications ?: return false
        if (rationaleAsked || leadAtLoad != null || writtenLead == null || permission.granted()) return false
        rationaleAsked = true
        afterRationale = asset to exit
        return true
    }

    private fun leave(asset: AssetId, exit: Exit) {
        when (exit) {
            Exit.SAVED -> _saved.tryEmit(asset)
            Exit.REVIEW -> _review.tryEmit(asset)
        }
    }

    /** Takes the question down; the asset it was about, or null when none was up. Only an edit ever asks. */
    private fun answered(): AssetId? = id?.takeIf { _prompt.getAndUpdate { null } != null }

    /**
     * Builds the command, saves, and then names the asset the screen should show, once, on
     * [saved]. A failure leaves the form exactly as the user typed it and only adds marks: an
     * [AssetValidation] one per field, an [AssetCycle] as a line naming the parent, because "that
     * asset is inside this one" is about the pair and not about any single input.
     *
     * The write runs in `viewModelScope`, not in the screen's composition scope: a rotation
     * halfway through must not abandon it with `saving` stuck true. The guard is set before the
     * first suspension, so two taps inside one frame create one asset, not two.
     */
    fun save() {
        if (!_state.value.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val form = _state.value
            // The price is text until Money says otherwise, and Money needs the currency to say
            // anything at all, so this one pair is settled before the command is built. So is the
            // warranty lead (#79): text that is not a whole number never reaches the use case.
            val priced = priceOf(form.price, form.currency)
            val lead = warrantyPartOf(form.warrantyLead)
            if (priced is Priced.Ok && lead != null) {
                commit(form, priced.minor, lead)
            } else {
                val marks = (priced as? Priced.Bad)?.problems.orEmpty() +
                    (if (lead == null) mapOf(AssetField.WARRANTY_LEAD to ENTER_THE_NUMBER_OF_DAYS) else emptyMap())
                _state.update { it.copy(saving = false, problems = marks) }
            }
        }
    }

    /**
     * #67, C6: one [SaveAssetSettings] call, only when the form has moved since the last successful
     * one ([writtenForm]) or nothing has been written yet — a retry whose form has not changed since
     * the write never calls it again. Every refusal leaves the form as typed and the staged list
     * intact, because the use case wrote nothing: S55 or S64 names the schedules, S63 is the
     * year-long break, the asset's own fields keep their shipped lines. A refusal the held Save
     * already makes unreachable — a missing phase, a half window, a primary that is not a live
     * subject — draws nothing, since no sentence for it is ratified (master dec. 46).
     *
     * On success the #78 question is decided from the *pre-write* stored mode and held in
     * [pendingPrompt] rather than raised immediately — [copyStaged] raises it, and only once
     * nothing is staged. Every write re-decides what is held: a write that leaves the asset
     * year-round drops it, whatever an earlier write decided. `savedId` becomes the id this and every
     * later retry addresses (B1): a new asset is minted once, and a retry after a failed copy never
     * mints a second one.
     */
    private suspend fun commit(form: AssetEditState, priceMinor: Long?, lead: WarrantyReminderCommand) {
        val snapshot = form.writtenSnapshot()
        if (writtenForm == null || writtenForm != snapshot) {
            val cmd = form.settingsCommand(priceMinor, lead)
            val result = runCatching {
                saveAssetSettings.run(savedId, cmd, form.templateKey.takeIf { savedId == null })
            }
            // Each answer replaces the last one's lines: a refusal names what **this** save was refused for,
            // never a line left over from an earlier one (B10 review M6).
            _state.update { it.copy(seasonRefusal = null, breakRefusal = null) }
            when (val failure = result.exceptionOrNull()) {
                null -> {
                    val written = result.getOrThrow()
                    // A failed read finishes the editor as a save that asked nothing; a cancellation is not a
                    // failed read and goes back out the way it came (the backup model's own rule).
                    val ask = runCatching { reconcilePromptFor(form) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                    // Re-decided by every write: a year-round asset has nothing to be asked about, and
                    // an edited retry that stays in a season keeps what the write out of year-round
                    // decided (its own pre-write mode is already seasonal, so it decides nothing).
                    pendingPrompt = if (written.seasonMode == SeasonMode.YEAR_ROUND) null else ask ?: pendingPrompt
                    savedId = written.id
                    writtenForm = snapshot
                    writtenLead = written.warrantyReminderLeadDays
                    _state.update { it.copy(storedSeasonMode = written.seasonMode, editing = true) }
                    sweepIfTheWarrantyMoved(written)
                }
                is SeasonModeStrandsPolicy -> {
                    _state.update {
                        it.copy(
                            saving = false,
                            seasonRefusal = seasonStrands(failure.schedules.map(StrandedSchedule::title)),
                        )
                    }
                    return
                }
                is BreakStrandsPolicy -> {
                    _state.update {
                        it.copy(
                            saving = false,
                            breakRefusal = breakStrands(failure.schedules.map(StrandedSchedule::title)),
                        )
                    }
                    return
                }
                is SeasonValidation -> {
                    _state.update { form ->
                        form.copy(
                            saving = false,
                            breakRefusal = BREAK_CANNOT_COVER_THE_YEAR
                                .takeIf { SeasonProblem.BlackoutCoversTheYear in failure.problems },
                            problems = failure.problems.mapNotNull(::monthDayMarkFor).toMap(),
                        )
                    }
                    return
                }
                is HealthValidation -> {
                    _state.update { it.copy(saving = false) }
                    return
                }
                is AssetValidation -> {
                    _state.update {
                        it.copy(saving = false, problems = failure.problems.mapNotNull(::markFor).toMap())
                    }
                    return
                }
                is AssetCycle -> {
                    _state.update { it.copy(saving = false) }
                    _messages.tryEmit("${nameOf(failure.parentId)} is already part of this asset.")
                    return
                }
                is WarrantyReminderValidation -> {
                    _state.update {
                        it.copy(saving = false, problems = mapOf(AssetField.WARRANTY_LEAD to leadLineFor(failure.problems)))
                    }
                    return
                }
                else -> {
                    _state.update { it.copy(saving = false) }
                    _messages.tryEmit("Could not save this asset.")
                    return
                }
            }
        }
        copyStaged()
    }

    /**
     * #67, C6 step 2: the staged files, straight after the write and before any #78 question, in
     * staged order on [io]. The loop works on the **live** staged list, never on the one the tap saw:
     * [stage] and [unstage] are held while saving, so this is the only hand on it, and each success
     * drops its own line there and then. The first failure sentences its line through the extracted
     * mapping ([AttachmentFailure]) and stops, the rest staying staged; the asset is never undone.
     * When anything landed the asset's key documents are read again, so a file copied before a
     * failure is listed under its role while the editor stays open, and is not picked a second time.
     *
     * Step 3: only once the live staged list is empty does the write's own #78 question — held in
     * [pendingPrompt] since [commit] — go up, or [saved] emit.
     */
    private suspend fun copyStaged() {
        val assetId = savedId
        var attached: List<AttachedDocument>? = null
        var offersDocuments = _state.value.offersDocuments
        if (assetId != null && _state.value.staged.isNotEmpty()) {
            withContext(io) {
                val capturedOn = today.localDate().toString()
                val tried = mutableListOf<StagedDocument>()
                var landed = false
                while (true) {
                    val doc = _state.value.staged.firstOrNull { line -> tried.none { it === line } } ?: break
                    tried += doc
                    val stopped = copyOne(assetId, doc, capturedOn)
                    if (stopped == null) {
                        landed = true
                        _state.update { form -> form.copy(staged = form.staged.filterNot { it === doc }) }
                    } else {
                        _state.update { form ->
                            form.copy(staged = form.staged.map { if (it === doc) doc.copy(problem = stopped.sentence) else it })
                        }
                        break
                    }
                }
                if (landed) {
                    attached = runCatching { attachedDocumentsOf(attachments.forOwner(AttachmentOwner.OfAsset(assetId))) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                }
                // A refusal is itself news about the folder — the same reasoning
                // `AttachmentsSectionViewModel.scan` bumps its own store read for: don't wait for the
                // screen to come back around to learn the tree just went away mid-copy.
                offersDocuments = storage.state() is StoreState.Ready
            }
        }
        // The question goes up before the form stops saving, so whoever waits on `saving` finds the
        // question, a staged line to act on, or the finished editor — never none of them.
        val done = _state.value.staged.isEmpty()
        val prompt = pendingPrompt?.takeIf { done }
        if (prompt != null) {
            pendingPrompt = null
            _prompt.value = prompt
        }
        // #79 (C11): after the copies and the #78 question, P79-12 — decided before `saving` drops, so it
        // is up by the time anyone waiting on `saving` looks.
        val leaving = assetId?.takeIf { done && prompt == null }
        val asking = leaving != null && holdForRationale(leaving, Exit.SAVED)
        _state.update {
            it.copy(
                saving = false,
                problems = emptyMap(),
                offersDocuments = offersDocuments,
                attached = attached ?: it.attached,
                askingForNotifications = it.askingForNotifications || asking,
            )
        }
        if (leaving != null && !asking) leave(leaving, Exit.SAVED)
    }

    /**
     * #79 (C11, R79-15): a write that moved the warranty date or the lead runs the shipped reminder sweep
     * once, so the warning is posted or withdrawn now rather than at the next digest; a write that moved
     * neither never does. The asset is already written: a sweep that fails leaves it to the next digest
     * or the backstop, and the editor finishes as it would have.
     */
    private suspend fun sweepIfTheWarrantyMoved(written: Asset) {
        val warranty = written.warrantyExpiresOn to written.warrantyReminderLeadDays
        if (warranty == sweptWarranty) return
        sweptWarranty = warranty
        val sweep = reconcile ?: return
        runCatching { sweep.run() }.onFailure { if (it is CancellationException) throw it }
    }

    /** One staged file onto [assetId]: null when it landed, else why its line stops the copies. */
    private suspend fun copyOne(assetId: AssetId, doc: StagedDocument, capturedOn: String): CopyStopped? {
        val outcome = try {
            addAttachment.run(
                AttachmentOwner.OfAsset(assetId),
                AddAttachmentCommand(
                    displayName = doc.file.displayName,
                    mimeType = doc.file.mimeType,
                    sizeBytes = doc.file.sizeBytes,
                    kind = null,
                    capturedOn = capturedOn,
                    role = doc.role,
                ),
                ByteSource { doc.file.open() },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return CopyStopped(AttachmentFailure.CopyFailed(doc.file.displayName).sentence())
        }
        return (outcome as? AttachmentResult.Refused)?.let { CopyStopped(AttachmentFailure.Refused(it.problem).sentence()) }
    }

    /**
     * #78, C1: the one question a written save may ask. Only an existing asset the form loaded as
     * YEAR_ROUND and saved into CALENDAR or MANUAL asks — the form's own fields, never a re-read of
     * the row the save just wrote — and only when some of its live schedules (ACTIVE or PAUSED, R-1)
     * are CONTINUOUS: those are the ones that keep coming due out of season. Nothing else asks, so
     * a seasonal asset that stays seasonal is never asked again (AC 5, R-3).
     */
    private suspend fun reconcilePromptFor(form: AssetEditState): EditPrompt? {
        val asset = id ?: return null
        val intoSeason = form.seasonMode == SeasonMode.CALENDAR || form.seasonMode == SeasonMode.MANUAL
        if (form.storedSeasonMode != SeasonMode.YEAR_ROUND || !intoSeason) return null
        val continuous = schedules.forAsset(asset).count {
            it.status != ScheduleStatus.ARCHIVED && it.servicePolicy == ServicePolicy.CONTINUOUS
        }
        return if (continuous > 0) EditPrompt.ReconcileSchedules(continuous) else null
    }

    /**
     * The five parts of one Save (spec §10.4; #79 adds the warranty reminder's lead). The asset part carries no `MM-DD` pair: the season-mode
     * part decides the season. A window goes only with S30, a phase only with a switch into MANUAL,
     * a break only while S59 is on, and a primary only with "One subject".
     */
    private fun AssetEditState.settingsCommand(priceMinor: Long?, lead: WarrantyReminderCommand) = AssetSettingsCommand(
        asset = toCommand(priceMinor),
        seasonMode = SeasonModeCommand(
            seasonMode = seasonMode,
            seasonStartMmdd = seasonStart.trim().takeIf { seasonMode == SeasonMode.CALENDAR },
            seasonEndMmdd = seasonEnd.trim().takeIf { seasonMode == SeasonMode.CALENDAR },
            manualPhase = manualPhase.takeIf { asksManualPhase },
        ),
        maintenanceBreak = if (breakOn) {
            BreakCommand(breakStart.trim(), breakEnd.trim())
        } else {
            BreakCommand(null, null)
        },
        healthPolicy = HealthPolicyCommand(
            healthAggregation = aggregation,
            healthPrimarySubjectId = primaryId?.let(::HealthSubjectId)
                .takeIf { aggregation == HealthAggregation.TRACK_ONE },
        ),
        // #79 (C2, C11): the fifth part, on every save — blank is off, so the field is the whole truth.
        warrantyReminder = lead,
    )

    /**
     * #67, C6: exactly the fields [settingsCommand] reads, snapshotted so a retry whose form has
     * not moved since the last successful write can skip [SaveAssetSettings] entirely. Deliberately
     * excludes `storedSeasonMode`, `staged`, `saving`, `problems` and the store state — comparing
     * more than [settingsCommand] reads would only cost an extra, harmless write attempt, since
     * `SaveAssetSettings` itself already no-ops an unchanged form.
     */
    private fun AssetEditState.writtenSnapshot() = WrittenForm(
        name, category, manufacturer, model, serialNumber, description, location, parentId,
        seasonMode, seasonStart, seasonEnd, manualPhase, breakOn, breakStart, breakEnd,
        aggregation, primaryId, purchaseOn, inServiceOn, price, currency, vendor,
        warrantyExpiresOn, warrantyLead, warrantyNotes, notes,
    )

    private fun AssetEditState.toCommand(priceMinor: Long?) = AssetCommand(
        name = name,
        category = category,
        description = description,
        notes = notes,
        manufacturer = manufacturer,
        model = model,
        serialNumber = serialNumber,
        purchaseOn = purchaseOn.ifBlank { null },
        inServiceOn = inServiceOn.ifBlank { null },
        purchasePriceMinor = priceMinor,
        currency = currency.ifBlank { null },
        vendor = vendor,
        location = location,
        warrantyExpiresOn = warrantyExpiresOn.ifBlank { null },
        warrantyNotes = warrantyNotes,
        parentAssetId = parentId?.let(::AssetId),
        // The season-mode part is authoritative; `SaveAssetSettings` keeps the stored pair here.
        seasonStartMmdd = null,
        seasonEndMmdd = null,
    )

    /**
     * A stored row as form text. Minor units come back through [Money], never by hand.
     *
     * **A dangling primary is not re-sent** (the controller's carry-forward from B06's review): a
     * TRACK_ONE whose primary names no non-archived subject of this asset — a state only a merge
     * reaches — is read as the engine already reads it, Worst subject with no primary (S138), so a
     * rename or a season change on that asset is not refused for a choice the owner never made here.
     */
    private fun AssetEditState.filledFrom(row: Asset, subjects: List<HealthSubject>): AssetEditState {
        val (aggregation, primary) = policyOf(row, subjects)
        return copy(
            name = row.name,
            category = row.category,
            manufacturer = row.manufacturer,
            model = row.model,
            serialNumber = row.serialNumber,
            description = row.description,
            location = row.location,
            parentId = row.parentAssetId?.value,
            seasonMode = row.seasonMode,
            storedSeasonMode = row.seasonMode,
            seasonStart = row.seasonStartMmdd.orEmpty(),
            seasonEnd = row.seasonEndMmdd.orEmpty(),
            breakOn = row.blackoutStartMmdd != null && row.blackoutEndMmdd != null,
            breakStart = row.blackoutStartMmdd.orEmpty(),
            breakEnd = row.blackoutEndMmdd.orEmpty(),
            subjects = rowsOf(subjects),
            aggregation = aggregation,
            primaryId = primary,
            purchaseOn = row.purchaseOn.orEmpty(),
            inServiceOn = row.inServiceOn.orEmpty(),
            price = priceTextOf(row.purchasePriceMinor, row.currency),
            currency = row.currency.orEmpty(),
            vendor = row.vendor,
            warrantyExpiresOn = row.warrantyExpiresOn.orEmpty(),
            warrantyLead = row.warrantyReminderLeadDays?.toString().orEmpty(),
            warrantyNotes = row.warrantyNotes,
            notes = row.notes,
        )
    }

    /**
     * The picker of spec §5: everything but this asset and everything under it, so choosing a
     * parent can never be the move that creates the cycle. Archived rows are offered and marked —
     * archive is not delete (R-9), and a component of an archived machine is still its component.
     */
    private fun choicesIn(all: Collection<Asset>): List<ParentChoice> {
        val blocked = id?.let { self -> AssetTree.descendants(all, self) + self }.orEmpty()
        return listOf(ParentChoice(null, NO_PARENT)) + all
            .filterNot { it.id in blocked }
            .sortedBy { it.name.lowercase() }
            .map { row ->
                ParentChoice(
                    id = row.id.value,
                    label = if (row.status == AssetStatus.ARCHIVED) "${row.name} (archived)" else row.name,
                )
            }
    }

    /** The refused parent by name, so the line says which asset it was; its id if it has gone. */
    private suspend fun nameOf(parentId: AssetId): String =
        assets.all().firstOrNull { it.id == parentId }?.name ?: parentId.value

    /** Applies [block] and then drops the marks on the fields the edit was about. */
    private fun edit(vararg fields: String, block: (AssetEditState) -> AssetEditState) =
        _state.update { form -> block(form).let { it.copy(problems = it.problems - fields.toSet()) } }
}

/** Either a price in minor units (or none at all), or the marks that say why there is not one. */
private sealed interface Priced {
    data class Ok(val minor: Long?) : Priced
    data class Bad(val problems: Map<String, String>) : Priced
}

/** #79 (C11): the editor's two ways out, held while P79-12 is answered. */
private enum class Exit { SAVED, REVIEW }

/**
 * #79 (C11, R79-12): the lead field's text as the save's fifth part — blank is no reminder, a whole
 * number goes to the use case (which refuses one under a day), and anything else is null: it never
 * reaches the use case and the field says "Enter the number of days.".
 */
private fun warrantyPartOf(text: String): WarrantyReminderCommand? {
    val typed = text.trim()
    if (typed.isEmpty()) return WarrantyReminderCommand(null)
    return typed.toIntOrNull()?.let(::WarrantyReminderCommand)
}

/** The lead's one line for a refused part: a bad number first, then P79-9. */
private fun leadLineFor(problems: List<WarrantyReminderProblem>): String =
    if (WarrantyReminderProblem.LeadNotPositive in problems) ENTER_THE_NUMBER_OF_DAYS else ADD_THE_WARRANTY_DATE_FIRST

/** #67, C6: a staged copy that did not land, and the sentence its line shows instead of P67-10. */
private class CopyStopped(val sentence: String?)

/** #67, C6: [AssetEditViewModel.writtenSnapshot]'s shape — see there for what it deliberately omits. */
private data class WrittenForm(
    val name: String,
    val category: String,
    val manufacturer: String,
    val model: String,
    val serialNumber: String,
    val description: String,
    val location: String,
    val parentId: String?,
    val seasonMode: SeasonMode,
    val seasonStart: String,
    val seasonEnd: String,
    val manualPhase: SeasonPhase?,
    val breakOn: Boolean,
    val breakStart: String,
    val breakEnd: String,
    val aggregation: HealthAggregation,
    val primaryId: String?,
    val purchaseOn: String,
    val inServiceOn: String,
    val price: String,
    val currency: String,
    val vendor: String,
    val warrantyExpiresOn: String,
    val warrantyLead: String,
    val warrantyNotes: String,
    val notes: String,
)

/** #67 (R67-5): the newest purchase invoice or receipt, in R67-3's order, by name; null when there is none. */
private fun purchaseDocumentOf(files: List<Attachment>): String? = files
    .filter { it.role == DocumentRole.PURCHASE_INVOICE_OR_RECEIPT }
    .minWithOrNull(newestFirst({ it.capturedOn }, { it.createdAt }))
    ?.displayName

/**
 * #67, R67-6: the asset's role-tagged attachments only, newest by `capturedOn` (nulls last) then by
 * `createdAt` first — one flat list; the editor's two blocks (Purchase, Key documents) each filter
 * it by role rather than loading twice.
 */
private fun attachedDocumentsOf(rows: List<Attachment>): List<AttachedDocument> = rows
    .filter { it.role != null }
    .sortedWith(
        compareByDescending<Attachment> { it.capturedOn != null }
            .thenByDescending { it.capturedOn }
            .thenByDescending { it.createdAt },
    )
    .map { AttachedDocument(role = it.role!!, displayName = it.displayName) }

/**
 * The form's price text as minor units. A blank price is no price, which is always allowed; a
 * price at all needs a currency [Money] can resolve before the digits mean anything, so the three
 * ways this can fail are marked here rather than guessed at by the use case.
 */
private fun priceOf(price: String, currency: String): Priced {
    val text = price.trim()
    val code = currency.trim()
    if (text.isEmpty()) return Priced.Ok(null)
    if (code.isEmpty()) return Priced.Bad(mapOf(AssetField.CURRENCY to CURRENCY_REQUIRED))
    val digits = Money.fractionDigits(code) ?: return Priced.Bad(mapOf(AssetField.CURRENCY to BAD_CURRENCY))
    val minor = Money.parse(text, code) ?: return Priced.Bad(mapOf(AssetField.PRICE to priceExample(digits)))
    return Priced.Ok(minor)
}

/** A stored price as the form shows it: the amount without the code, which the field names. */
internal fun priceTextOf(minor: Long?, code: String?): String {
    if (minor == null || code == null) return ""
    return runCatching { Money.format(minor, code).removeSuffix(" $code") }.getOrDefault("")
}

/** "123.45" for a two-digit currency, "123" for a zero-digit one: the shape, not an amount. */
internal fun priceExample(digits: Int): String =
    "Enter a price like " + if (digits <= 0) "123" else "123." + "456789".take(digits)

/** What the price field says before anything is wrong: how many decimals this currency has. */
internal fun priceHint(currency: String): String {
    val digits = Money.fractionDigits(currency.trim()) ?: return "Amount"
    return when (digits) {
        0 -> "Whole numbers only"
        1 -> "Up to 1 decimal place"
        else -> "Up to $digits decimal places"
    }
}

/** The device's currency where Android resolves one; blank where it does not (spec §9). */
private fun localeCurrencyCode(): String = try {
    Currency.getInstance(Locale.getDefault())?.currencyCode.orEmpty()
} catch (e: IllegalArgumentException) {
    ""
} catch (e: NullPointerException) {
    ""
}

/**
 * One typed problem as the field it belongs under and the line that field shows, or null for one
 * this form cannot reach and has no ratified words for. The asset part of a settings save carries
 * the stored `MM-DD` pair, so a season problem here is a stored pair gone bad — marked on its field
 * with the shipped line, and never as the both-or-neither sentence, which a calendar season that
 * requires both dates would make false (the controller's ruling on I10).
 */
private fun markFor(problem: AssetProblem): Pair<String, String>? = when (problem) {
    AssetProblem.NameRequired -> AssetField.NAME to "Give the asset a name"
    AssetProblem.BadCurrency -> AssetField.CURRENCY to BAD_CURRENCY
    AssetProblem.CurrencyRequired -> AssetField.CURRENCY to CURRENCY_REQUIRED
    AssetProblem.NegativePrice -> AssetField.PRICE to "Price cannot be negative"
    AssetProblem.UnknownParent -> AssetField.PARENT to "That asset is no longer there"
    is AssetProblem.BadDate -> problem.field to "Enter a date as YYYY-MM-DD"
    is AssetProblem.Season -> when (val season = problem.p) {
        SeasonWindow.Problem.BothOrNeither -> null
        is SeasonWindow.Problem.BadDate ->
            (if (season.which == "start") AssetField.SEASON_START else AssetField.SEASON_END) to
                NOT_A_REAL_MONTH_AND_DAY
    }
}

/**
 * A season or break command's `MM-DD` refusal on its field, with the shipped line. The held Save
 * keeps these out of reach; this is what a refusal would still draw. Nothing else in a
 * [SeasonValidation] has a field or ratified words (S63 is drawn by the caller).
 */
private fun monthDayMarkFor(problem: SeasonProblem): Pair<String, String>? {
    val field = when ((problem as? SeasonProblem.BadDate)?.field) {
        "seasonStartMmdd" -> AssetField.SEASON_START
        "seasonEndMmdd" -> AssetField.SEASON_END
        "blackoutStartMmdd" -> AssetField.BREAK_START
        "blackoutEndMmdd" -> AssetField.BREAK_END
        else -> return null
    }
    return field to NOT_A_REAL_MONTH_AND_DAY
}

/** The subjects in `sortOrder`, then id, as S111 lists them. */
private fun rowsOf(subjects: List<HealthSubject>): List<SubjectRow> =
    subjects
        .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
        .map { SubjectRow(id = it.id.value, name = it.name, archived = it.archivedAt != null) }

/**
 * The stored policy as the form's answer. A primary goes with TRACK_ONE alone, and a dangling one —
 * naming no non-archived subject of the asset — falls back to Worst subject, as the engine reads it.
 */
private fun policyOf(row: Asset, subjects: List<HealthSubject>): Pair<HealthAggregation, String?> {
    if (row.healthAggregation != HealthAggregation.TRACK_ONE) return row.healthAggregation to null
    val live = subjects.filter { it.archivedAt == null && it.assetId == row.id }.map { it.id }
    val primary = row.healthPrimarySubjectId?.takeIf { it in live }
        ?: return HealthAggregation.WORST to null
    return HealthAggregation.TRACK_ONE to primary.value
}

/** S55 with its one substitution: the stranded schedules' titles. */
internal fun seasonStrands(titles: List<String>): String =
    SEASON_STRANDS_PRE_SERVICE.replace("<titles>", titles.joinToString(", "))

/** S64 with its one substitution: the stranded schedules' titles. */
internal fun breakStrands(titles: List<String>): String =
    BREAK_STRANDS_PRE_SERVICE.replace("<titles>", titles.joinToString(", "))

/** Also the case editor's currency refusal (#79, C21): reused through this home. */
internal const val BAD_CURRENCY = "Currency is a three-letter code like USD"
private const val CURRENCY_REQUIRED = "A price needs a currency"
