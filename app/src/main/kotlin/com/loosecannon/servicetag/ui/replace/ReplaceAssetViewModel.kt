package com.loosecannon.servicetag.ui.replace

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.journal.CategoryCatalog
import com.loosecannon.servicetag.core.journal.CategoryChoice
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.ReplaceAsset
import com.loosecannon.servicetag.core.usecase.ReplaceDraft
import com.loosecannon.servicetag.core.usecase.ReplaceOffer
import com.loosecannon.servicetag.core.usecase.ReplacePlan
import com.loosecannon.servicetag.core.usecase.ReplaceProblem
import com.loosecannon.servicetag.core.usecase.ReplaceStale
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.AssetField
import com.loosecannon.servicetag.ui.asset.ENTER_A_DATE_AS_YYYY_MM_DD
import com.loosecannon.servicetag.ui.asset.GIVE_THE_ASSET_A_NAME
import com.loosecannon.servicetag.ui.asset.IN_SEASON_NOW
import com.loosecannon.servicetag.ui.asset.OUT_OF_SEASON_NOW
import com.loosecannon.servicetag.ui.asset.ParentChoice
import com.loosecannon.servicetag.ui.asset.READINGS_AND_ACTIONS
import com.loosecannon.servicetag.ui.asset.parentChoicesIn
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.scan.identityLine
import com.loosecannon.servicetag.ui.scan.placementOrNull
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where the Replace screen is (C17): reading the offer, the FORM, the REVIEW, or GONE — the old asset is missing,
 * held, or already replaced, so there is nothing to replace and the screen leaves. GONE says nothing of its own.
 */
enum class ReplacePhase { LOADING, FORM, REVIEW, GONE }

/** The form's fields that can carry a problem line (C17); the name, and the four dates. */
enum class ReplaceField { RETIRED_ON, NAME, PURCHASE_ON, IN_SERVICE_ON, SCHEDULE_START_ON }

/**
 * The Replace form as typed (C17). **Nothing starts ticked** (R86-9) and **no tag starts moved** (R86-14): every flag
 * is false and every set empty until the owner changes it. [retiredOn] is the `Retired on` field — blank, and unused,
 * once the old asset is already retired (R86-3). [manualPhase] is P86-28's answer, with no default.
 */
data class ReplaceForm(
    val retiredOn: String = "",
    val name: String = "",
    val category: String = "",
    val location: String = "",
    val parentId: String? = null,
    val manufacturer: String = "",
    val model: String = "",
    val serialNumber: String = "",
    val purchaseOn: String = "",
    val inServiceOn: String = "",
    val carrySeason: Boolean = false,
    val manualPhase: SeasonPhase? = null,
    val carrySetup: Boolean = false,
    val carryNotes: Boolean = false,
    val scheduleIds: Set<String> = emptySet(),
    val scheduleStartOn: String = "",
    val groupIds: Set<String> = emptySet(),
    val movedTagIds: Set<String> = emptySet(),
)

/** One offered schedule: its checkbox by [title], and P86-13 / P86-14 under it while it needs an item ([needs]). */
data class ScheduleOption(val id: String, val title: String, val ticked: Boolean, val needs: List<String>)

/** One offered group: its checkbox, [label] being P86-11. */
data class GroupOption(val id: String, val label: String, val ticked: Boolean)

/** One offered tag: its identity line and placement (data, not words), and P86-15 ([moved]) or P86-16. */
data class TagOption(val id: String, val identity: String, val placement: String?, val moved: Boolean)

/**
 * The REVIEW's lines (C17), each composed: P86-20 or P86-21 ([retireLine]); P86-22 ([createLine]); under P86-7 the
 * ticked items' labels in form order — P86-9, then a MANUAL answer's S36/S37 word on its own line, then `Readings &
 * actions`, P86-10, each schedule by title, P86-11 per group — or P86-23 alone ([carried]); under P86-15 the moved
 * tags' identity lines ([movedTags]; empty, nothing drawn).
 */
data class ReplaceReview(
    val retireLine: String,
    val createLine: String,
    val carried: List<String>,
    val movedTags: List<String>,
)

/**
 * The one state the Replace screen draws (C17). Every line is a ratified string already composed; the screen adds
 * none. [oldAssetLine] is P86-3 (with the `Retired on` field, [asksRetiredOn]) or P86-4 (no field). [problems] are the
 * plan's field lines — `Give the asset a name`, `Enter a date as YYYY-MM-DD`, and `DATE_NOT_LATER_THAN_TODAY` under
 * `Retired on`, or under P86-4 when the stored date is the future one. [error] is P86-24, P86-25 or P77-35. [done] is
 * the new asset's id, handed over once through [ReplaceAssetViewModel.takeDone].
 */
data class ReplaceAssetState(
    val phase: ReplacePhase = ReplacePhase.LOADING,
    val form: ReplaceForm = ReplaceForm(),
    val oldName: String = "",
    val oldAssetLine: String = "",
    val asksRetiredOn: Boolean = false,
    val parentChoices: List<ParentChoice> = emptyList(),
    val seasonOffered: Boolean = false,
    val setupOffered: Boolean = false,
    val notesOffered: Boolean = false,
    /** P86-28 iff the season is ticked on a MANUAL old asset; its options are S36 and S37, with no default. */
    val phaseQuestion: String? = null,
    val schedules: List<ScheduleOption> = emptyList(),
    /** P86-12 is shown iff a ticked schedule has a time rule. */
    val asksScheduleStart: Boolean = false,
    val groups: List<GroupOption> = emptyList(),
    val tags: List<TagOption> = emptyList(),
    /** P86-18, one child or several; null when there is none. */
    val childrenLine: String? = null,
    /** P86-19; null when there is no open loan. */
    val loanLine: String? = null,
    val problems: Map<ReplaceField, String> = emptyMap(),
    /** `Review` is enabled iff the plan of exactly this form answers no problem. */
    val reviewEnabled: Boolean = false,
    val review: ReplaceReview? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val done: String? = null,
)

/**
 * #86 (C17; R86-3…9, R86-12…14, R86-13a) — **Replace asset**'s state machine: the offer drawn as the form, **every item
 * unticked**; each edit re-planned, so `Review` is enabled iff [ReplaceAsset.plan] answers no problem for exactly the
 * form on screen; the REVIEW; and the one confirm.
 *
 * - **Nothing is written before the confirm** (R86-4): the offer and the plan only read; Cancel in the review returns
 *   to the form and writes nothing.
 * - **The confirm runs once:** [ReplaceAssetState.saving] is set before the first suspension, and the write gets the
 *   reviewed plan's own draft (B2's MN-2), so a changed form can never reach it.
 * - **After the write, one sweep** ([ReminderReconcile], R77-23), then [ReplaceAssetState.done].
 * - **Refusals:** `ReplaceStale` first (it is an `IllegalStateException`, like a failed check) → P86-25, back to the
 *   form with the offer re-read, ticks and moves no longer offered dropped, typed fields kept; `AssetTransferredOut` →
 *   P77-35; anything else → P86-24. None sweeps.
 * - **The defaults are the form's** (R86-8, NT-6): the in-service date follows the replacement date, and the copied
 *   schedules' start follows the in-service date (or the replacement date while that is blank), each until edited.
 */
class ReplaceAssetViewModel(
    private val replaceAsset: ReplaceAsset,
    categories: CategoryRepository,
    private val today: Today,
    /** R77-23: the one sweep after the write. */
    private val reconcile: ReminderReconcile,
    private val id: AssetId,
) : ViewModel() {

    constructor(graph: AppGraph, id: String) : this(
        graph.replaceAsset, graph.categories, graph.today, graph.reminderReconcile, AssetId(id),
    )

    private val _state = MutableStateFlow(ReplaceAssetState())
    val state: StateFlow<ReplaceAssetState> = _state.asStateFlow()

    /** The Category field's catalog (#74), read, never written — the editor's own rule. */
    val categoryChoices: StateFlow<List<CategoryChoice>> = categories.observeAll()
        .map(CategoryCatalog::choices)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), CategoryCatalog.builtIns)

    private var offer: ReplaceOffer? = null

    /** The latest plan answered; `Review` needs it to be the plan of exactly the form on screen. */
    private var plan: ReplacePlan? = null
    private var planning: Job? = null
    private var inServiceEdited = false
    private var scheduleStartEdited = false
    private var handedOver = false

    init {
        viewModelScope.launch { load(keep = null, error = null) }
    }

    fun onRetiredOn(value: String) = edit { it.copy(retiredOn = value) }
    fun onName(value: String) = edit { it.copy(name = value) }
    fun onCategory(value: String) = edit { it.copy(category = value) }
    fun onLocation(value: String) = edit { it.copy(location = value) }
    fun onParent(parentId: String?) = edit { it.copy(parentId = parentId) }
    fun onManufacturer(value: String) = edit { it.copy(manufacturer = value) }
    fun onModel(value: String) = edit { it.copy(model = value) }
    fun onSerialNumber(value: String) = edit { it.copy(serialNumber = value) }
    fun onPurchaseOn(value: String) = edit { it.copy(purchaseOn = value) }

    fun onInServiceOn(value: String) {
        if (!editable()) return
        inServiceEdited = true
        edit { it.copy(inServiceOn = value) }
    }

    /** P86-9; unticking it forgets P86-28's answer, so a later tick asks again with no default. */
    fun onCarrySeason(ticked: Boolean) = edit { it.copy(carrySeason = ticked, manualPhase = it.manualPhase.takeIf { ticked }) }
    fun onManualPhase(phase: SeasonPhase) = edit { it.copy(manualPhase = phase) }
    fun onCarrySetup(ticked: Boolean) = edit { it.copy(carrySetup = ticked) }
    fun onCarryNotes(ticked: Boolean) = edit { it.copy(carryNotes = ticked) }
    fun onSchedule(scheduleId: String, ticked: Boolean) = edit { it.copy(scheduleIds = it.scheduleIds.toggled(scheduleId, ticked)) }

    fun onScheduleStartOn(value: String) {
        if (!editable()) return
        scheduleStartEdited = true
        edit { it.copy(scheduleStartOn = value) }
    }

    fun onGroup(groupId: String, ticked: Boolean) = edit { it.copy(groupIds = it.groupIds.toggled(groupId, ticked)) }

    /** P86-15 ([moved]) or P86-16. */
    fun onTagMove(tagId: String, moved: Boolean) = edit { it.copy(movedTagIds = it.movedTagIds.toggled(tagId, moved)) }

    /** `Review`: only while the plan of exactly this form answers no problem. Writes nothing. */
    fun review() {
        val current = _state.value
        val read = offer ?: return
        val reviewed = plan ?: return
        if (current.phase != ReplacePhase.FORM || !current.reviewEnabled) return
        _state.value = current.copy(phase = ReplacePhase.REVIEW, review = reviewOf(read, current.form, reviewed), error = null)
    }

    /** The review's `Cancel`: back to the form, writing nothing. */
    fun backToForm() {
        val current = _state.value
        if (current.phase != ReplacePhase.REVIEW || current.saving) return
        _state.value = current.copy(phase = ReplacePhase.FORM, review = null)
    }

    /** P86-1 in the review — once: [ReplaceAssetState.saving] is set before the first suspension. */
    fun confirm() {
        val current = _state.value
        val reviewed = plan ?: return
        val read = offer ?: return
        if (current.phase != ReplacePhase.REVIEW || current.saving) return
        _state.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            val result = try {
                replaceAsset.run(reviewed.draft, reviewed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReplaceStale) {
                // Matched before any other IllegalStateException (hand-off 3).
                _state.update { it.copy(phase = ReplacePhase.FORM, review = null, saving = false) }
                load(keep = current.form, error = ReplaceStrings.CHANGED_WHILE_REVIEWING)
                return@launch
            } catch (e: AssetTransferredOut) {
                refuse(TransferImportStrings.ASSET_TRANSFERRED_OUT)
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "Replace asset failed", e)
                refuse(ReplaceStrings.couldNotReplace(read.predecessor.name))
                return@launch
            }
            sweepOnce()
            _state.update { it.copy(saving = false, done = result.successor.id.value) }
        }
    }

    /**
     * The hand-over (the #84 one-shot lesson): the new asset's id **once**, after the write and its sweep; null
     * before, and on every later call, so a recomposition or a rotation never navigates twice. It writes nothing.
     */
    fun takeDone(): String? {
        val done = _state.value.done ?: return null
        if (handedOver) return null
        handedOver = true
        return done
    }

    private fun editable(): Boolean = _state.value.let { it.phase == ReplacePhase.FORM && !it.saving }

    private fun edit(change: (ReplaceForm) -> ReplaceForm) {
        val current = _state.value
        val read = offer ?: return
        if (!editable()) return
        _state.value = derive(current.copy(form = follow(read, change(current.form)), error = null), read)
        replan()
    }

    /** R86-8 and NT-6: each default follows the date before it until the owner edits it. */
    private fun follow(read: ReplaceOffer, form: ReplaceForm): ReplaceForm {
        val replacement = replacementOf(read, form)
        val inService = if (inServiceEdited) form.inServiceOn else replacement
        val start = if (scheduleStartEdited) form.scheduleStartOn else inService.ifBlank { replacement }
        return form.copy(inServiceOn = inService, scheduleStartOn = start)
    }

    private fun replacementOf(read: ReplaceOffer, form: ReplaceForm): String =
        read.predecessor.retiredOn ?: form.retiredOn.trim()

    /**
     * Reads the offer; [keep] is the form P86-25 returns to — its typed fields kept, and every tick or move the
     * offer no longer holds dropped. With no [keep], the form is the offer's prefill, nothing ticked (R86-9).
     */
    private suspend fun load(keep: ReplaceForm?, error: String?) {
        val read = try {
            replaceAsset.offer(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoSuchAsset) {
            null
        }
        if (read == null || !read.eligible) {
            offer = null
            _state.update { it.copy(phase = ReplacePhase.GONE, saving = false) }
            return
        }
        offer = read
        plan = null
        val form = keep?.let { kept -> stillOffered(read, kept) } ?: prefill(read)
        _state.value = derive(ReplaceAssetState(phase = ReplacePhase.FORM, form = follow(read, form), error = error), read)
        replan()
    }

    private fun prefill(read: ReplaceOffer): ReplaceForm = ReplaceForm(
        retiredOn = if (read.predecessor.retiredOn == null) today.localDate().toString() else "",
        name = read.prefill.name,
        category = read.prefill.category,
        location = read.prefill.location,
        parentId = read.prefill.parentAssetId?.value,
    )

    private fun stillOffered(read: ReplaceOffer, kept: ReplaceForm): ReplaceForm {
        val schedules = read.schedules.map { it.id.value }.toSet()
        val groups = read.groups.map { it.id.value }.toSet()
        val tags = read.tags.map { it.id.value }.toSet()
        val season = kept.carrySeason && read.seasonOffered
        return kept.copy(
            carrySeason = season,
            manualPhase = kept.manualPhase.takeIf { season },
            carrySetup = kept.carrySetup && read.setupOffered,
            carryNotes = kept.carryNotes && read.notesOffered,
            scheduleIds = kept.scheduleIds.filterTo(mutableSetOf()) { it in schedules },
            groupIds = kept.groupIds.filterTo(mutableSetOf()) { it in groups },
            movedTagIds = kept.movedTagIds.filterTo(mutableSetOf()) { it in tags },
        )
    }

    /** One plan per form: an older plan still in flight is cancelled, and its answer is never applied. */
    private fun replan() {
        val read = offer ?: return
        val draft = draftOf(read, _state.value.form)
        planning?.cancel()
        planning = viewModelScope.launch {
            val answered = try {
                replaceAsset.plan(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoSuchAsset) {
                offer = null
                _state.update { it.copy(phase = ReplacePhase.GONE) }
                return@launch
            }
            if (offer !== read) return@launch
            plan = answered
            _state.update { derive(it, read) }
        }
    }

    /** Exactly what the form on screen sends: blank dates are none, and the season's answer only when it is ticked. */
    private fun draftOf(read: ReplaceOffer, form: ReplaceForm): ReplaceDraft = ReplaceDraft(
        predecessorId = id,
        retiredOn = form.retiredOn.trim().takeIf { read.predecessor.retiredOn == null },
        successor = AssetCommand(
            name = form.name,
            category = form.category,
            location = form.location,
            manufacturer = form.manufacturer,
            model = form.model,
            serialNumber = form.serialNumber,
            purchaseOn = form.purchaseOn.trim().ifBlank { null },
            inServiceOn = form.inServiceOn.trim().ifBlank { null },
            parentAssetId = form.parentId?.let(::AssetId),
        ),
        carrySeason = form.carrySeason,
        manualPhase = form.manualPhase.takeIf { form.carrySeason },
        carrySetup = form.carrySetup,
        carryNotes = form.carryNotes,
        scheduleIds = form.scheduleIds.map(::ScheduleId).toSet(),
        scheduleStartOn = form.scheduleStartOn.trim().takeIf { asksScheduleStart(read, form) },
        groupIds = form.groupIds.map(::GroupId).toSet(),
        movedTagIds = form.movedTagIds.map(::TagId).toSet(),
    )

    private fun asksScheduleStart(read: ReplaceOffer, form: ReplaceForm): Boolean =
        read.schedules.any { it.id.value in form.scheduleIds && it.timeInterval != null }

    /** Everything the screen draws from the offer, the form and the latest plan. */
    private fun derive(state: ReplaceAssetState, read: ReplaceOffer): ReplaceAssetState {
        val old = read.predecessor
        val form = state.form
        val answered = plan
        val problems = answered?.problems.orEmpty()
        val current = answered != null && answered.draft == draftOf(read, form)
        return state.copy(
            oldName = old.name,
            oldAssetLine = old.retiredOn?.let { ReplaceStrings.wasRetiredOn(old.name, it) } ?: ReplaceStrings.willBeRetired(old.name),
            asksRetiredOn = old.retiredOn == null,
            parentChoices = parentChoicesIn(read.parentChoices, emptySet(), old.id),
            seasonOffered = read.seasonOffered,
            setupOffered = read.setupOffered,
            notesOffered = read.notesOffered,
            phaseQuestion = ReplaceStrings.wasInSeasonOn(replacementOf(read, form))
                .takeIf { form.carrySeason && old.seasonMode == SeasonMode.MANUAL },
            schedules = read.schedules.map { schedule ->
                ScheduleOption(
                    id = schedule.id.value,
                    title = schedule.title,
                    ticked = schedule.id.value in form.scheduleIds,
                    needs = problems.mapNotNull { problem ->
                        when {
                            problem is ReplaceProblem.NeedsSetup && problem.scheduleId == schedule.id -> ReplaceStrings.NEEDS_SETUP
                            problem is ReplaceProblem.NeedsSeason && problem.scheduleId == schedule.id -> ReplaceStrings.NEEDS_SEASON
                            else -> null
                        }
                    },
                )
            },
            asksScheduleStart = asksScheduleStart(read, form),
            groups = read.groups.map { GroupOption(it.id.value, ReplaceStrings.addTo(it.name), it.id.value in form.groupIds) },
            tags = read.tags.map { TagOption(it.id.value, it.identityLine(), it.placementOrNull(), it.id.value in form.movedTagIds) },
            childrenLine = ReplaceStrings.childrenStay(read.childNames, old.name),
            loanLine = ReplaceStrings.lentOut(old.name).takeIf { read.openLoan },
            problems = fieldLinesOf(problems),
            reviewEnabled = current && problems.isEmpty() && !state.saving,
        )
    }

    /** The plan's field problems as their shipped lines; the rest block Review with no line of their own. */
    private fun fieldLinesOf(problems: List<ReplaceProblem>): Map<ReplaceField, String> = buildMap {
        problems.forEach { problem ->
            when (problem) {
                ReplaceProblem.NameRequired -> put(ReplaceField.NAME, GIVE_THE_ASSET_A_NAME)
                is ReplaceProblem.BadDate -> fieldOf(problem.field)?.let { put(it, ENTER_A_DATE_AS_YYYY_MM_DD) }
                ReplaceProblem.ReplacedOnAfterToday -> put(ReplaceField.RETIRED_ON, DATE_NOT_LATER_THAN_TODAY)
                else -> Unit
            }
        }
    }

    private fun fieldOf(field: String): ReplaceField? = when (field) {
        RETIRED_ON_KEY -> ReplaceField.RETIRED_ON
        AssetField.PURCHASE_ON -> ReplaceField.PURCHASE_ON
        AssetField.IN_SERVICE_ON -> ReplaceField.IN_SERVICE_ON
        SCHEDULE_START_ON_KEY -> ReplaceField.SCHEDULE_START_ON
        else -> null
    }

    private fun reviewOf(read: ReplaceOffer, form: ReplaceForm, reviewed: ReplacePlan): ReplaceReview {
        val old = read.predecessor
        val carried = buildList {
            if (form.carrySeason) {
                add(ReplaceStrings.SEASON_AND_BREAK)
                if (old.seasonMode == SeasonMode.MANUAL) {
                    when (form.manualPhase) {
                        SeasonPhase.IN_SEASON -> add(IN_SEASON_NOW)
                        SeasonPhase.OUT_OF_SEASON -> add(OUT_OF_SEASON_NOW)
                        null -> Unit
                    }
                }
            }
            if (form.carrySetup) add(READINGS_AND_ACTIONS)
            if (form.carryNotes) add(ReplaceStrings.DESCRIPTION_AND_NOTES)
            read.schedules.filter { it.id.value in form.scheduleIds }.forEach { add(it.title) }
            read.groups.filter { it.id.value in form.groupIds }.forEach { add(ReplaceStrings.addTo(it.name)) }
        }
        return ReplaceReview(
            retireLine = if (old.retiredOn != null) {
                ReplaceStrings.staysRetiredFrom(old.name, reviewed.replacedOn)
            } else {
                ReplaceStrings.retireOn(old.name, reviewed.replacedOn)
            },
            createLine = ReplaceStrings.create(form.name.trim()),
            carried = carried.ifEmpty { listOf(ReplaceStrings.NOTHING_CARRIED) },
            movedTags = read.tags.filter { it.id.value in form.movedTagIds }.map { it.identityLine() },
        )
    }

    private fun refuse(line: String) {
        _state.update { it.copy(saving = false, error = line) }
    }

    /** R77-23's one sweep, after the write; a sweep that fails is the backstop's to repeat, never the write's failure. */
    private suspend fun sweepOnce() {
        try {
            reconcile.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the sweep after Replace asset failed", e)
        }
    }

    private fun Set<String>.toggled(id: String, on: Boolean): Set<String> = if (on) this + id else this - id

    private companion object {
        const val TAG = "ReplaceAsset"
        const val SUBSCRIPTION_GRACE_MS = 5_000L

        /** `ReplaceProblem.BadDate`'s field names for the two dates the asset command does not carry (B2). */
        const val RETIRED_ON_KEY = "retiredOn"
        const val SCHEDULE_START_ON_KEY = "scheduleStartOn"
    }
}
