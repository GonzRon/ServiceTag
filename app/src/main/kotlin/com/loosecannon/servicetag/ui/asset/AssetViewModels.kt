package com.loosecannon.servicetag.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.journal.CategorySuggestions
import com.loosecannon.servicetag.core.journal.LatestReadings
import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.core.journal.Reading
import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.Money
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.Season as SeasonWindow
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.SeasonPhase
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
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.BreakStrandsPolicy
import com.loosecannon.servicetag.core.usecase.DeleteAsset
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.HealthValidation
import com.loosecannon.servicetag.core.usecase.RetireAsset
import com.loosecannon.servicetag.core.usecase.SaveAssetSettings
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeStrandsPolicy
import com.loosecannon.servicetag.core.usecase.SeasonProblem
import com.loosecannon.servicetag.core.usecase.SeasonValidation
import com.loosecannon.servicetag.core.usecase.StrandedSchedule
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.maintenance.DueItem
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 * One row of the Assets list: the asset plus the two things the row says that the asset itself does
 * not carry — whose component it is, and whether today falls outside its season window (spec §9).
 */
data class AssetRow(
    val asset: Asset,
    /** The parent's name for the "Part of <parent>" subtitle; null for a root asset. */
    val parentName: String? = null,
    val outOfSeason: Boolean = false,
)

data class AssetsState(
    val items: List<AssetRow> = emptyList(),
    val showArchived: Boolean = false,
    /** How many rows the chip is hiding, so an empty list can say why it is empty. */
    val archivedCount: Int = 0,
    /** What the search box holds, verbatim. Blank leaves the list exactly as it was before B07. */
    val query: String = "",
    /**
     * The archived-only hint's exact condition (owner ruling §18.23; the view model's to decide,
     * not the screen's — B07 fix round 5, controller ruling Q4): a non-blank [query], [showArchived]
     * off, no active row matching, and at least one archived row that does. Self-sufficient — it
     * already implies [items] is empty, so the screen needs no `items.isEmpty()` check of its own
     * to show the hint correctly.
     */
    val showArchivedOnlyHint: Boolean = false,
)

/**
 * The list. Three decisions live here: whether the archived tail is shown at all — archive is not
 * delete (R-9), but a list that keeps showing everything you archived is no better than never
 * archiving — the order, which is active, then retired, then archived, by name within each group
 * (spec §9) — and, since the owner's 2026-09-23 instruction moved #39's quick filter here from the
 * Dashboard, what the search box narrows the list to. The order is the ViewModel's rather than the
 * query's because "retired" is a date column, not a status, and sorting by it in SQL would say
 * nothing about lifecycle.
 *
 * **The Assets screen does not adopt the Dashboard's hide-components-until-searched behaviour**
 * (controller ruling, B07 fix round 1): a blank query leaves the list exactly as it was before this
 * brief — every asset, components included, each still naming its system — and a non-blank query
 * only ever narrows that same list. Only the box's *location* moved; what the screen lists did not.
 */
class AssetsViewModel(assets: AssetRepository, private val clock: Clock) : ViewModel() {

    constructor(graph: AppGraph) : this(graph.assets, graph.clock)

    private val showArchived = MutableStateFlow(false)
    private val queries = MutableStateFlow("")

    /**
     * What the search box draws itself from, synchronously (F3): a `combine`/`stateIn` round trip
     * is not guaranteed to be back before the next keystroke, which is how characters get dropped
     * and the cursor jumps to the end mid-word. [AssetsState.query] carries the same string once
     * the list has caught up with it.
     */
    val query: StateFlow<String> = queries.asStateFlow()

    /** The zone the season window is read in: "out of season" is a fact about the user's today. */
    private val zone: ZoneId = ZoneId.systemDefault()

    val state: StateFlow<AssetsState> =
        combine(assets.observeAll(), showArchived, queries) { rows, archived, query ->
            val today = clock.nowMillis().asLocalDate(zone)
            val byId = rows.associateBy { it.id }
            val visible = if (archived) rows else rows.filter { it.status == AssetStatus.ACTIVE }
            // A blank query leaves the list exactly as it was before B07 (components included);
            // a non-blank query only ever narrows it (#39's six-field predicate, unchanged).
            val matching = visible.filter { it.matches(query) }
            AssetsState(
                items = matching
                    .sortedWith(compareBy({ lifecycleRank(it) }, { it.name.lowercase() }))
                    .map { row ->
                        AssetRow(
                            asset = row,
                            // The parent by name, from the rows already in hand: no second query,
                            // and a parent that has gone leaves the subtitle off rather than
                            // showing an id.
                            parentName = row.parentAssetId?.let { byId[it]?.name },
                            outOfSeason = outOfSeasonOn(row, today),
                        )
                    },
                showArchived = archived,
                archivedCount = rows.count { it.status != AssetStatus.ACTIVE },
                query = query,
                // Checked directly against `rows`, not against `matching`/`items`: a formula that
                // only ever counted archived matches would say `true` even when an active row also
                // matched (Q4), so this asks both halves itself rather than trusting the screen's
                // `items.isEmpty()` to have ruled the active half out already.
                showArchivedOnlyHint = query.isNotBlank() && !archived &&
                    rows.none { it.status == AssetStatus.ACTIVE && it.matches(query) } &&
                    rows.any { it.status != AssetStatus.ACTIVE && it.matches(query) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), AssetsState())

    fun toggleArchived() = showArchived.update { !it }

    /**
     * What the search box holds. Filtering is a pass over rows the store flow already produced, so
     * a keystroke runs no query, reads no preference and needs no debounce.
     */
    fun onQueryChange(value: String) { queries.value = value }

    /** The clear action. Separate from `onQueryChange("")` so the screen states its intent. */
    fun clearQuery() { queries.value = "" }
}

/**
 * Active first, then retired, then archived (spec §9). Archived wins over retired, so an asset that
 * is both sorts with the archived tail: the chip that hides archived rows must hide all of them.
 */
private fun lifecycleRank(asset: Asset): Int = when {
    asset.status != AssetStatus.ACTIVE -> 2
    asset.isRetired -> 1
    else -> 0
}

/**
 * Out of season for [today] (spec §6). A half-set window is a thing the use cases refuse, so it can
 * only reach here past them; reading it as year-round is the answer that never hides an asset
 * behind a window nobody could have set.
 */
internal fun outOfSeasonOn(asset: Asset, today: LocalDate): Boolean =
    runCatching { !SeasonWindow.inSeason(asset.seasonStartMmdd, asset.seasonEndMmdd, today) }
        .getOrDefault(false)

/** A date the calendar has already passed. A string `LocalDate` refuses is not "expired". */
internal fun expiredOn(date: String, today: LocalDate): Boolean =
    runCatching { LocalDate.parse(date).isBefore(today) }.getOrDefault(false)

/** The clock's instant as a calendar day in [zone] — the only place millis become a date here. */
private fun Long.asLocalDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/**
 * One child of the asset as COMPONENTS draws it (spec §9). [outOfRange] is a count of the child's
 * *own* current readings that are LOW or HIGH; 2B-2 rolls no values up into the parent (spec §2),
 * so the parent's screen says how many need a look and never what they read.
 */
data class ComponentRow(
    val id: String,
    val name: String,
    val category: String,
    val outOfRange: Int,
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
}

/**
 * One group this asset is an **open** member of, as the asset screen lists it (#55's asset -> groups
 * direction). A closed window is history and is not here: listing one would make a removed asset
 * look like a current member.
 */
data class AssetGroupRow(val id: GroupId, val name: String)

/** Everything the detail screen draws about one asset, or null while it is still unknown. */
data class AssetDetailState(
    val asset: Asset,
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
    /** Today is outside the season window (spec §6) — the one effect the window has in 2B-2. */
    val outOfSeason: Boolean = false,
    /** The warranty date has passed, so DETAILS says "(expired)" rather than making the user count. */
    val warrantyExpired: Boolean = false,
    /**
     * 1.2 — the asset's **own** schedules, from the shared projection (master plan decision 38).
     * A group-targeted schedule it is a member of is not here: that obligation is counted once, on
     * the group, and [groups] is how it is reached.
     */
    val schedules: List<DueItem> = emptyList(),
    /** 1.2 — the groups this asset holds an **open** membership window in. */
    val groups: List<AssetGroupRow> = emptyList(),
)

/**
 * One asset and the rows that point at it. [missing] is separate from [state] because "not loaded
 * yet" and "gone" both read as a null state, and only the second one should send the user back —
 * a deep link or a restored back stack can name an asset a backup import has since replaced.
 *
 * Five flows feed the state and `combine` takes three, so the journal's three are folded into one
 * first. `readings` is computed here rather than stored: editing or deleting an event changes the
 * answer on the next emission with no cache to invalidate.
 */
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
    private val archiveAsset: ArchiveAsset,
    private val retireAsset: RetireAsset,
    private val deleteAsset: DeleteAsset,
    private val applyTemplate: ApplyTemplate,
    /** Review fix round 1, finding 3: `editTagLabel`'s read-modify-write needs the same transaction every other tag write goes through. */
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val id: AssetId,
) : ViewModel() {

    constructor(graph: AppGraph, id: String) : this(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, AssetId(id),
    )

    /** The zone the season window and the warranty date are read in: the user's calendar day. */
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

    val state: StateFlow<AssetDetailState?> =
        combine(rows, tags.observeForAsset(id), journal, maintenance) { all, tagRows, j, groupRows ->
            val row = all.firstOrNull { it.id == id } ?: return@combine null
            val today = clock.nowMillis().asLocalDate(zone)
            val parent = row.parentAssetId?.let { parentId -> all.firstOrNull { it.id == parentId } }
            AssetDetailState(
                asset = row,
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
                components = componentsOf(all),
                outOfSeason = outOfSeasonOn(row, today),
                warrantyExpired = row.warrantyExpiresOn?.let { expiredOn(it, today) } == true,
                // Asset-targeted only (decision 38). `forAsset` deliberately answers with the group
                // schedules too, because the scan sheet wants both; this screen counts a group
                // obligation once, on the group.
                schedules = due.forAsset(id).filter { it.target is ScheduleTarget.AssetTarget },
                groups = groupRows
                    .sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
                    .map { AssetGroupRow(it.id, it.name) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), null)

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
    private suspend fun componentsOf(all: List<Asset>): List<ComponentRow> =
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
                )
            }

    fun archive() {
        viewModelScope.launch { archiveAsset.run(id) }
    }

    fun unarchive() {
        viewModelScope.launch { archiveAsset.unarchive(id) }
    }

    /** Opens the retirement dialog on today, which the user may then backdate (spec §7). */
    fun askRetire() = _prompt.update { DetailPrompt.Retire(clock.nowMillis().asLocalDate(zone).toString()) }

    fun askDelete() = _prompt.update { DetailPrompt.ConfirmDelete }

    fun dismissPrompt() = _prompt.update { null }

    /**
     * Retirement commits on its own (spec §7). Only once the date is written is logging what
     * happened *offered*, as a second dialog: declining it — or cancelling the entry it opens —
     * leaves the asset retired, which is why this is two steps and not one wizard.
     */
    fun retire(on: String) {
        viewModelScope.launch {
            when (runCatching { retireAsset.retire(id, on) }.exceptionOrNull()) {
                null -> _prompt.update { DetailPrompt.LogWhatHappened }
                is AssetValidation -> refuse("Enter a date as YYYY-MM-DD")
                else -> refuse("Could not retire this asset.")
            }
        }
    }

    fun unretire() {
        viewModelScope.launch {
            if (runCatching { retireAsset.unretire(id) }.isFailure) refuse("Could not update this asset.")
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
            uow.write {
                val row = tags.get(id) ?: return@write
                val trimmed = label?.trim()?.takeIf { it.isNotEmpty() }
                if (trimmed == row.label) return@write
                tags.upsert(row.copy(label = trimmed, updatedAt = clock.nowMillis()))
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
    val warrantyNotes: String = "",
    val notes: String = "",
    val editing: Boolean = false,
    val saving: Boolean = false,
    /** Field key → the one line shown under that field. Empty until a save is refused. */
    val problems: Map<String, String> = emptyMap(),
    /** New assets only. null is "None · set up later", the default; Generic is a choice (§7). */
    val templateKey: String? = null,
    /** True once the user picked a template by hand; category edits stop touching it then (§8). */
    val templateTouched: Boolean = false,
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
     */
    val canSave: Boolean
        get() = !saving && seasonReady && breakWindowReady && primaryReady

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
 */
class AssetEditViewModel(
    private val assets: AssetRepository,
    private val healthSubjects: HealthSubjectRepository,
    private val saveAssetSettings: SaveAssetSettings,
    private val id: AssetId?,
    presetParentId: String? = null,
) : ViewModel() {

    constructor(graph: AppGraph, id: String?, parentId: String? = null) :
        this(graph.assets, graph.healthSubjects, graph.saveAssetSettings, id?.let(::AssetId), parentId)

    private val _state = MutableStateFlow(
        AssetEditState(
            editing = id != null,
            parentId = presetParentId,
            // Once, on a new asset: a currency the person then clears stays cleared (spec §9).
            currency = if (id == null) localeCurrencyCode() else "",
        ),
    )
    val state: StateFlow<AssetEditState> = _state.asStateFlow()

    /**
     * One shot per successful save. A buffer of one and no replay: the screen that started the
     * save is told where to go, and a screen that comes back later is not told again.
     */
    private val _saved = MutableSharedFlow<AssetId>(replay = 0, extraBufferCapacity = 1)
    val saved: SharedFlow<AssetId> = _saved.asSharedFlow()

    /** What has no room under a field: the refused reparent, named (spec §9). One line, once. */
    private val _messages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            val all = assets.all()
            val row = id?.let { existing -> all.firstOrNull { it.id == existing } }
            val subjects = id?.let { healthSubjects.forAsset(it) }.orEmpty()
            _state.update { form ->
                val filled = if (row == null) form else form.filledFrom(row, subjects)
                filled.copy(parentChoices = choicesIn(all))
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

    fun onWarrantyExpiresOn(value: String) =
        edit(AssetField.WARRANTY_EXPIRES_ON) { it.copy(warrantyExpiresOn = value) }

    fun onWarrantyNotes(value: String) = edit { it.copy(warrantyNotes = value) }
    fun onNotes(value: String) = edit { it.copy(notes = value) }

    /** null selects "None · set up later"; either way the choice was the user's from now on (§8). */
    fun onTemplate(key: String?) = _state.update { it.copy(templateKey = key, templateTouched = true) }

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
            when (val priced = priceOf(form.price, form.currency)) {
                // The price is text until Money says otherwise, and Money needs the currency to
                // say anything at all, so this one pair is settled before the command is built.
                is Priced.Bad -> _state.update { it.copy(saving = false, problems = priced.problems) }
                is Priced.Ok -> commit(form, priced.minor)
            }
        }
    }

    /**
     * One [SaveAssetSettings] call per Save. Every refusal leaves the form as typed and writes
     * nothing, because the use case wrote nothing: S55 or S64 names the schedules, S63 is the
     * year-long break, the asset's own fields keep their shipped lines. A refusal the held Save
     * already makes unreachable — a missing phase, a half window, a primary that is not a live
     * subject — draws nothing, since no sentence for it is ratified (master dec. 46).
     */
    private suspend fun commit(form: AssetEditState, priceMinor: Long?) {
        val cmd = form.settingsCommand(priceMinor)
        val result = runCatching {
            saveAssetSettings.run(id, cmd, form.templateKey.takeIf { id == null })
        }
        // Each answer replaces the last one's lines: a refusal names what **this** save was refused for,
        // never a line left over from an earlier one (B10 review M6).
        _state.update { it.copy(seasonRefusal = null, breakRefusal = null) }
        when (val failure = result.exceptionOrNull()) {
            null -> {
                _state.update { it.copy(saving = false, problems = emptyMap()) }
                result.getOrNull()?.let { saved -> _saved.tryEmit(saved.id) }
            }
            is SeasonModeStrandsPolicy -> _state.update {
                it.copy(saving = false, seasonRefusal = seasonStrands(failure.schedules.map(StrandedSchedule::title)))
            }
            is BreakStrandsPolicy -> _state.update {
                it.copy(saving = false, breakRefusal = breakStrands(failure.schedules.map(StrandedSchedule::title)))
            }
            is SeasonValidation -> _state.update { form ->
                form.copy(
                    saving = false,
                    breakRefusal = BREAK_CANNOT_COVER_THE_YEAR
                        .takeIf { SeasonProblem.BlackoutCoversTheYear in failure.problems },
                    problems = failure.problems.mapNotNull(::monthDayMarkFor).toMap(),
                )
            }
            is HealthValidation -> _state.update { it.copy(saving = false) }
            is AssetValidation ->
                _state.update { it.copy(saving = false, problems = failure.problems.mapNotNull(::markFor).toMap()) }
            is AssetCycle -> {
                _state.update { it.copy(saving = false) }
                _messages.tryEmit("${nameOf(failure.parentId)} is already part of this asset.")
            }
            else -> {
                _state.update { it.copy(saving = false) }
                _messages.tryEmit("Could not save this asset.")
            }
        }
    }

    /**
     * The four parts of one Save (spec §10.4). The asset part carries no `MM-DD` pair: the season-mode
     * part decides the season. A window goes only with S30, a phase only with a switch into MANUAL,
     * a break only while S59 is on, and a primary only with "One subject".
     */
    private fun AssetEditState.settingsCommand(priceMinor: Long?) = AssetSettingsCommand(
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

private const val BAD_CURRENCY = "Currency is a three-letter code like USD"
private const val CURRENCY_REQUIRED = "A price needs a currency"
