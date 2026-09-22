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
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.Money
import com.loosecannon.servicetag.core.model.Season as SeasonWindow
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.usecase.ApplyResult
import com.loosecannon.servicetag.core.usecase.ApplyTemplate
import com.loosecannon.servicetag.core.usecase.ArchiveAsset
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetCycle
import com.loosecannon.servicetag.core.usecase.AssetHasChildren
import com.loosecannon.servicetag.core.usecase.AssetProblem
import com.loosecannon.servicetag.core.usecase.AssetValidation
import com.loosecannon.servicetag.core.usecase.CreateAsset
import com.loosecannon.servicetag.core.usecase.DeleteAsset
import com.loosecannon.servicetag.core.usecase.RetireAsset
import com.loosecannon.servicetag.core.usecase.UpdateAsset
import com.loosecannon.servicetag.di.AppGraph
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
)

/**
 * The list. Two decisions live here: whether the archived tail is shown at all — archive is not
 * delete (R-9), but a list that keeps showing everything you archived is no better than never
 * archiving — and the order, which is active, then retired, then archived, by name within each
 * group (spec §9). The order is the ViewModel's rather than the query's because "retired" is a
 * date column, not a status, and sorting by it in SQL would say nothing about lifecycle.
 */
class AssetsViewModel(assets: AssetRepository, private val clock: Clock) : ViewModel() {

    constructor(graph: AppGraph) : this(graph.assets, graph.clock)

    private val showArchived = MutableStateFlow(false)

    /** The zone the season window is read in: "out of season" is a fact about the user's today. */
    private val zone: ZoneId = ZoneId.systemDefault()

    val state: StateFlow<AssetsState> = combine(assets.observeAll(), showArchived) { rows, archived ->
        val today = clock.nowMillis().asLocalDate(zone)
        val byId = rows.associateBy { it.id }
        val visible = if (archived) rows else rows.filter { it.status == AssetStatus.ACTIVE }
        AssetsState(
            items = visible
                .sortedWith(compareBy({ lifecycleRank(it) }, { it.name.lowercase() }))
                .map { row ->
                    AssetRow(
                        asset = row,
                        // The parent by name, from the rows already in hand: no second query, and
                        // a parent that has gone leaves the subtitle off rather than showing an id.
                        parentName = row.parentAssetId?.let { byId[it]?.name },
                        outOfSeason = outOfSeasonOn(row, today),
                    )
                },
            showArchived = archived,
            archivedCount = rows.count { it.status != AssetStatus.ACTIVE },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), AssetsState())

    fun toggleArchived() = showArchived.update { !it }
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

    val state: StateFlow<AssetDetailState?> =
        combine(rows, tags.observeForAsset(id), journal) { all, tagRows, j ->
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
    const val SEASON = "season"
    const val SEASON_START = "seasonStart"
    const val SEASON_END = "seasonEnd"
    const val PURCHASE_ON = "purchaseOn"
    const val IN_SERVICE_ON = "inServiceOn"
    const val PRICE = "price"
    const val CURRENCY = "currency"
    const val WARRANTY_EXPIRES_ON = "warrantyExpiresOn"
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
    val seasonYearRound: Boolean = true,
    val seasonStart: String = "",
    val seasonEnd: String = "",
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
)

/** The label of the empty choice in the "Part of" picker, and of the asset with no parent. */
const val NO_PARENT = "None"

/**
 * Create ([id] null) or edit one asset. Every rule lives in the use cases — this turns the form's
 * text into one [AssetCommand], hands it over, and turns whatever comes back into marks under
 * fields or a line for the snackbar.
 *
 * [presetParentId] is the "Part of" a new asset opens with, which is how "+ Add component" on a
 * parent's screen makes a child (spec §9). An existing asset's stored parent always wins over it.
 */
class AssetEditViewModel(
    private val assets: AssetRepository,
    private val createAsset: CreateAsset,
    private val updateAsset: UpdateAsset,
    private val id: AssetId?,
    presetParentId: String? = null,
) : ViewModel() {

    constructor(graph: AppGraph, id: String?, parentId: String? = null) :
        this(graph.assets, graph.createAsset, graph.updateAsset, id?.let(::AssetId), parentId)

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
            _state.update { form ->
                val filled = if (row == null) form else form.filledFrom(row)
                filled.copy(parentChoices = choicesIn(all))
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

    /** Year-round is both dates absent (spec §6), so turning it on is what clears them. */
    fun onSeasonYearRound(yearRound: Boolean) =
        edit(AssetField.SEASON, AssetField.SEASON_START, AssetField.SEASON_END) { form ->
            if (yearRound) {
                form.copy(seasonYearRound = true, seasonStart = "", seasonEnd = "")
            } else {
                form.copy(seasonYearRound = false)
            }
        }

    fun onSeasonStart(value: String) =
        edit(AssetField.SEASON, AssetField.SEASON_START) { it.copy(seasonStart = value) }

    fun onSeasonEnd(value: String) =
        edit(AssetField.SEASON, AssetField.SEASON_END) { it.copy(seasonEnd = value) }

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
        if (_state.value.saving) return
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

    private suspend fun commit(form: AssetEditState, priceMinor: Long?) {
        val cmd = form.toCommand(priceMinor)
        val result = runCatching {
            if (id == null) createAsset.run(cmd, form.templateKey) else updateAsset.run(id, cmd)
        }
        when (val failure = result.exceptionOrNull()) {
            null -> {
                _state.update { it.copy(saving = false, problems = emptyMap()) }
                result.getOrNull()?.let { saved -> _saved.tryEmit(saved.id) }
            }
            is AssetValidation ->
                _state.update { it.copy(saving = false, problems = failure.problems.associate(::markFor)) }
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
        seasonStartMmdd = if (seasonYearRound) null else seasonStart.ifBlank { null },
        seasonEndMmdd = if (seasonYearRound) null else seasonEnd.ifBlank { null },
    )

    /** A stored row as form text. Minor units come back through [Money], never by hand. */
    private fun AssetEditState.filledFrom(row: Asset) = copy(
        name = row.name,
        category = row.category,
        manufacturer = row.manufacturer,
        model = row.model,
        serialNumber = row.serialNumber,
        description = row.description,
        location = row.location,
        parentId = row.parentAssetId?.value,
        seasonYearRound = row.seasonStartMmdd == null && row.seasonEndMmdd == null,
        seasonStart = row.seasonStartMmdd.orEmpty(),
        seasonEnd = row.seasonEndMmdd.orEmpty(),
        purchaseOn = row.purchaseOn.orEmpty(),
        inServiceOn = row.inServiceOn.orEmpty(),
        price = priceTextOf(row.purchasePriceMinor, row.currency),
        currency = row.currency.orEmpty(),
        vendor = row.vendor,
        warrantyExpiresOn = row.warrantyExpiresOn.orEmpty(),
        warrantyNotes = row.warrantyNotes,
        notes = row.notes,
    )

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

/** One typed problem as the field it belongs under and the line that field shows. */
private fun markFor(problem: AssetProblem): Pair<String, String> = when (problem) {
    AssetProblem.NameRequired -> AssetField.NAME to "Give the asset a name"
    AssetProblem.BadCurrency -> AssetField.CURRENCY to BAD_CURRENCY
    AssetProblem.CurrencyRequired -> AssetField.CURRENCY to CURRENCY_REQUIRED
    AssetProblem.NegativePrice -> AssetField.PRICE to "Price cannot be negative"
    AssetProblem.UnknownParent -> AssetField.PARENT to "That asset is no longer there"
    is AssetProblem.BadDate -> problem.field to "Enter a date as YYYY-MM-DD"
    is AssetProblem.Season -> when (val season = problem.p) {
        SeasonWindow.Problem.BothOrNeither -> AssetField.SEASON to "Set both season dates or neither"
        is SeasonWindow.Problem.BadDate ->
            (if (season.which == "start") AssetField.SEASON_START else AssetField.SEASON_END) to
                "Not a real month and day"
    }
}

private const val BAD_CURRENCY = "Currency is a three-letter code like USD"
private const val CURRENCY_REQUIRED = "A price needs a currency"
