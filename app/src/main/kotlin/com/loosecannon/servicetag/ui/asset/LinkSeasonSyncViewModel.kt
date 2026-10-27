package com.loosecannon.servicetag.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.seasonsync.EntityScope
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.LinkSeasonSync
import com.loosecannon.servicetag.core.seasonsync.ResumeSeasonSync
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefused
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefusal
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefused
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinked
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncNotLinked
import com.loosecannon.servicetag.core.seasonsync.pickerRows
import com.loosecannon.servicetag.core.usecase.SeasonModeStrandsPolicy
import com.loosecannon.servicetag.core.usecase.SeasonSyncOwnsSeason
import com.loosecannon.servicetag.core.usecase.StrandedSchedule
import com.loosecannon.servicetag.core.usecase.liveContinuousCount
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.homeassistant.HA_ENTER_TOKEN_AGAIN
import com.loosecannon.servicetag.ui.homeassistant.HA_NOT_CONNECTED
import com.loosecannon.servicetag.ui.homeassistant.Notice
import com.loosecannon.servicetag.ui.maintenance.ScheduleSeasonRefusalLine
import com.loosecannon.servicetag.ui.maintenance.scheduleSeasonRefusalLine
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the setup sheet does on Save (C27): link an unlinked asset, resume a stopped binding (R16-19), or (#129, L-C7)
 * point a linked asset's binding at another helper through the shipped `EditSeasonSyncEntity`.
 */
internal enum class SeasonSyncSheetPurpose { LINK, RESUME, CHANGE_ENTITY }

/** How the sheet ended: closed, or closed onto the asset's schedules (#78's P78-2). */
internal enum class SeasonSheetExit { CLOSED, REVIEW_SCHEDULES }

/**
 * #125 (U-C8) — whose season the sheet links: an asset's (#16, as shipped), or one asset-targeted schedule's own
 * season. The same sheet, browser and `INPUT_BOOLEANS` scope either way; only the write on Save differs.
 */
internal sealed interface SeasonSyncSheetTarget {
    data class Asset(val assetId: AssetId) : SeasonSyncSheetTarget

    data class Schedule(val scheduleId: ScheduleId) : SeasonSyncSheetTarget
}

/**
 * #105 (B3) — the entity browser as drawn, one immutable value, inside the sheet's state while the browser is open.
 * [all] is the last successful list (kept across a failed refresh); [rows] and [truncatedList] are `pickerRows` over it
 * for [query]; [scopeEmpty] says the scope had nothing even with no query, which is P105-8 rather than P105-9;
 * [failure] is the latest read's ratified sentences, empty after a success. The token is never here.
 */
internal data class EntityBrowseState(
    val loading: Boolean = false,
    val query: String = "",
    val all: List<HaEntityCandidate> = emptyList(),
    val rows: List<HaEntityCandidate> = emptyList(),
    val truncatedList: Boolean = false,
    val scopeEmpty: Boolean = false,
    val loadedOnce: Boolean = false,
    val failure: List<Notice> = emptyList(),
) {
    /** P105-9: a read succeeded, the scope has helpers, and the query matches none of them. */
    val noMatch: Boolean get() = loadedOnce && failure.isEmpty() && !scopeEmpty && rows.isEmpty()
}

/**
 * #16 (C27) — the setup sheet as drawn, one immutable value. [sentence] is the asset's mode's reconciliation sentence
 * (P16-44/45/46), null until the asset is read; Save is held until it is there, so nothing is written before the owner
 * has seen it. [entityLine] is P16-49 under the field; [refusal] is S55 or another refusal's sentence, the sheet open;
 * [prompt] is #78's question after a written link or resume out of YEAR_ROUND; [finished] tells the screen to close.
 * #105: [chosen] is the candidate picked in the browser, [manualEntry] whether the typed field is shown instead of the
 * Choose entity row, and [browse] the browser while it is open; [entityToLink] is what Save sends — the picked id,
 * else the trimmed text — through the shipped link, unchanged.
 */
internal data class LinkSeasonSyncState(
    val purpose: SeasonSyncSheetPurpose,
    val sentence: String? = null,
    /** #125: a schedule's own season — no asset mode is reconciled, so no sentence is waited for. */
    val scheduleTarget: Boolean = false,
    val entityId: String = "",
    val entityLine: String? = null,
    val refusal: String? = null,
    val prompt: EditPrompt? = null,
    val saving: Boolean = false,
    val finished: SeasonSheetExit? = null,
    val chosen: HaEntityCandidate? = null,
    val manualEntry: Boolean = false,
    val browse: EntityBrowseState? = null,
    /** #129: the helper the binding follows now, for [SeasonSyncSheetPurpose.CHANGE_ENTITY]; null otherwise. */
    val storedEntityId: String? = null,
) {
    /**
     * Link also waits for something to send (#105 row 16): a pick, or typed text. Resume sends no entity. Change helper
     * (#129) waits for a helper other than the stored one: choosing the same one writes nothing, and Save is held.
     */
    val canSave: Boolean get() = (sentence != null || scheduleTarget) && !saving && prompt == null &&
        finished == null && sendsSomething

    private val sendsSomething: Boolean get() = when (purpose) {
        SeasonSyncSheetPurpose.LINK -> entityToLink.isNotEmpty()
        SeasonSyncSheetPurpose.RESUME -> true
        SeasonSyncSheetPurpose.CHANGE_ENTITY -> entityToLink.isNotEmpty() && entityToLink != storedEntityId
    }

    /** The entity row, the browser and manual entry: Link and Change helper choose a helper; Resume names none. */
    val choosesEntity: Boolean get() = when (purpose) {
        SeasonSyncSheetPurpose.LINK, SeasonSyncSheetPurpose.CHANGE_ENTITY -> true
        SeasonSyncSheetPurpose.RESUME -> false
    }

    val entityToLink: String get() = chosen?.entityId ?: entityId.trim()
}

/**
 * #16 (C27; R16-Q-C, R16-Q-G, R16-19) — the setup sheet's model. It reads the asset once for its sentence and writes
 * only through the shipped [LinkSeasonSync] or [ResumeSeasonSync] on Save; each refusal comes back as a code or a
 * shipped exception and is drawn as its ratified sentence. After a write that left YEAR_ROUND it asks #78's question
 * with core's [liveContinuousCount], never silently; the answer writes nothing.
 *
 * #125 (U-C8): with a [SeasonSyncSheetTarget.Schedule] target, Save writes through [linkSchedule] instead — the
 * schedule's own season, never the asset's — and its refusals are drawn by `scheduleSeasonRefusalLine`.
 */
internal class LinkSeasonSyncViewModel(
    private val target: SeasonSyncSheetTarget,
    purpose: SeasonSyncSheetPurpose,
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val connections: HaConnectionRepository,
    private val link: LinkSeasonSync,
    private val resume: ResumeSeasonSync,
    /**
     * #125: a schedule's link (`LinkScheduleSeason`), then its post-link check launched and never awaited. Reached only
     * for a [SeasonSyncSheetTarget.Schedule] target.
     */
    private val linkSchedule: suspend (ScheduleId, String) -> Unit,
    /**
     * #105: the one foreground list read, over the stored connection and its token (`AppGraph.listHaEntities`): null
     * when no connection is stored, else the client's outcome. Called only by Choose entity and Refresh, never on
     * construction. No default: a wiring that forgets it does not compile.
     */
    private val listEntities: suspend () -> HaListOutcome?,
    /**
     * #129 (L-C7): the asset's stored binding's entity (`seasonSyncBindings.get(…)?.entityId`), null with no binding.
     * Read once, on construction, for [SeasonSyncSheetPurpose.CHANGE_ENTITY] only.
     */
    private val storedEntity: suspend (AssetId) -> String?,
    /** #129 (L-C7): Change helper's Save, the shipped `EditSeasonSyncEntity`. */
    private val changeEntity: suspend (AssetId, String) -> Unit,
) : ViewModel() {

    /**
     * #125's construction, as shipped: no Change helper. Kept as its own constructor, not as defaults on the two seams
     * above, so a call passing [listEntities] last — positionally or as a trailing lambda — still binds it; reaching
     * either seam from here is a wiring fault and fails loudly.
     */
    constructor(
        target: SeasonSyncSheetTarget,
        purpose: SeasonSyncSheetPurpose,
        assets: AssetRepository,
        schedules: ScheduleRepository,
        connections: HaConnectionRepository,
        link: LinkSeasonSync,
        resume: ResumeSeasonSync,
        linkSchedule: suspend (ScheduleId, String) -> Unit,
        listEntities: suspend () -> HaListOutcome?,
    ) : this(
        target, purpose, assets, schedules, connections, link, resume, linkSchedule, listEntities, NO_STORED_ENTITY,
        NO_CHANGE_ENTITY,
    )

    /** #16's asset sheet, as shipped. */
    constructor(
        assetId: AssetId,
        purpose: SeasonSyncSheetPurpose,
        assets: AssetRepository,
        schedules: ScheduleRepository,
        connections: HaConnectionRepository,
        link: LinkSeasonSync,
        resume: ResumeSeasonSync,
        listEntities: suspend () -> HaListOutcome?,
    ) : this(
        SeasonSyncSheetTarget.Asset(assetId), purpose, assets, schedules, connections, link, resume, NO_SCHEDULE_LINK,
        listEntities,
    )

    constructor(graph: AppGraph, target: SeasonSyncSheetTarget, purpose: SeasonSyncSheetPurpose) : this(
        target, purpose, graph.assets, graph.schedules, graph.haConnections, graph.linkSeasonSync,
        graph.resumeSeasonSync,
        { scheduleId, entityId ->
            graph.linkScheduleSeason.run(scheduleId, entityId)
            graph.seasonSyncRunner.requestScheduleRead(scheduleId)
        },
        graph.listHaEntities,
        { assetId -> graph.seasonSyncBindings.get(assetId)?.entityId },
        { assetId, entityId -> graph.editSeasonSyncEntity.run(assetId, entityId) },
    )

    private val _state = MutableStateFlow(
        LinkSeasonSyncState(purpose, scheduleTarget = target is SeasonSyncSheetTarget.Schedule),
    )
    val state: StateFlow<LinkSeasonSyncState> = _state.asStateFlow()

    init {
        when (target) {
            is SeasonSyncSheetTarget.Asset -> viewModelScope.launch {
                when (purpose) {
                    SeasonSyncSheetPurpose.LINK, SeasonSyncSheetPurpose.RESUME -> {
                        val mode = assets.get(target.assetId)?.seasonMode
                        _state.update { it.copy(sentence = mode?.let(::seasonSyncLinkSentence)) }
                    }
                    SeasonSyncSheetPurpose.CHANGE_ENTITY -> openOnStoredHelper(target.assetId)
                }
            }
            // #125: the asset's mode is not changed by a schedule's link, so there is no sentence to reconcile; and a
            // schedule's binding is never stopped in 1.9.0 (H-3) nor re-pointed (#129: a schedule's link changes by
            // unlink and link), so its sheet only links.
            is SeasonSyncSheetTarget.Schedule ->
                require(purpose == SeasonSyncSheetPurpose.LINK) { "a schedule's season is only linked here" }
        }
    }

    /**
     * #129 (L-C7): Change helper opens on the stored helper, chosen as a pick with no friendly name (so the row reads
     * the id with Change beside it), under P129-3 whatever the link's mode. With no binding — it went with a
     * Disconnect, or another way — the sheet closes onto the card, as the edit's `SeasonSyncNotLinked` would.
     */
    private suspend fun openOnStoredHelper(assetId: AssetId) {
        val stored = storedEntity(assetId)
        _state.update {
            if (stored == null) {
                it.copy(finished = SeasonSheetExit.CLOSED)
            } else {
                it.copy(
                    chosen = HaEntityCandidate(stored, friendlyName = null),
                    storedEntityId = stored,
                    sentence = SEASON_SYNC_CHANGE_HELPER_SENTENCE,
                )
            }
        }
    }

    /** A typed id is the manual path: it clears any pick, so Save sends what the owner can see in the field. */
    fun onEntityId(text: String) =
        _state.update { it.copy(entityId = text, chosen = null, entityLine = null, refusal = null) }

    // --- #105: the browser ----------------------------------------------------------------------------------------

    /** Choose entity: open the browser and read the list once. Nothing was read before this. */
    fun chooseEntity() {
        _state.update { it.copy(browse = EntityBrowseState(), entityLine = null, refusal = null) }
        readEntities()
    }

    /** Refresh: read again; the last good list and the selection stay until the answer arrives. */
    fun refreshEntities() {
        if (_state.value.browse == null) return
        readEntities()
    }

    fun onQuery(text: String) = _state.update { state ->
        state.browse?.let { state.copy(browse = withRows(it.copy(query = text))) } ?: state
    }

    /** The pick: the exact candidate, the typed text cleared, the browser closed, the form back. */
    fun pick(candidate: HaEntityCandidate) {
        stopReading()
        _state.update {
            it.copy(
                chosen = candidate, entityId = "", manualEntry = false, browse = null, entityLine = null, refusal = null,
            )
        }
    }

    /** Back from the browser without a pick: whatever was chosen or typed before stays. */
    fun closeBrowse() {
        stopReading()
        _state.update { it.copy(browse = null) }
    }

    /**
     * The sheet is going away without a Save — Cancel, back, a scrim tap or a swipe: a list read still running stops,
     * which disconnects it. Each opening's model is keyed under its page (the asset's, or the schedule's) and outlives
     * its sheet, so nothing else would stop it.
     */
    fun dismiss() = closeBrowse()

    /** Enter entity ID manually: the field, prefilled with a pick's id if there was one, which it then replaces. */
    fun enterManually() {
        stopReading()
        _state.update {
            it.copy(manualEntry = true, entityId = it.chosen?.entityId ?: it.entityId, chosen = null, browse = null)
        }
    }

    /**
     * The read in flight, if any. A new read, a pick, manual entry and a close each cancel it, which disconnects the
     * request (C19), so an answer that is no longer wanted never lands in a browser opened after it.
     */
    private var listing: Job? = null

    private fun stopReading() {
        listing?.cancel()
        listing = null
    }

    private fun readEntities() {
        stopReading()
        _state.update { state -> state.browse?.let { state.copy(browse = it.copy(loading = true)) } ?: state }
        listing = viewModelScope.launch {
            val outcome: HaListOutcome? = try {
                listEntities()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A store failed before anything was asked of Home Assistant: no sentence is ratified for it; the
                // list and the selection stay as they were.
                _state.update { state -> state.browse?.let { state.copy(browse = it.copy(loading = false)) } ?: state }
                return@launch
            }
            _state.update { state ->
                // The browser is gone (its close also cancelled this read): nothing changes.
                val browse = state.browse ?: return@update state
                state.copy(
                    browse = when (outcome) {
                        null -> browse.copy(loading = false, failure = listOf(Notice(HA_NOT_CONNECTED)))
                        is HaListOutcome.Failed -> browse.copy(
                            loading = false,
                            failure = seasonSyncErrorNotices(outcome.kind, outcome.detail, entityId = ""),
                        )
                        is HaListOutcome.Listed -> withRows(
                            browse.copy(
                                loading = false,
                                all = outcome.entities,
                                loadedOnce = true,
                                failure = emptyList(),
                                // Once per list, not per keystroke: P105-8 rather than P105-9.
                                scopeEmpty = pickerRows(outcome.entities, EntityScope.INPUT_BOOLEANS, "")
                                    .rows.isEmpty(),
                            ),
                        )
                    },
                )
            }
        }
    }

    /** The rows for the browser's query over its last good list. */
    private fun withRows(browse: EntityBrowseState): EntityBrowseState {
        val shown = pickerRows(browse.all, EntityScope.INPUT_BOOLEANS, browse.query)
        return browse.copy(rows = shown.rows, truncatedList = shown.truncatedList)
    }

    fun save() {
        val asked = _state.value
        if (!asked.canSave) return
        _state.update { it.copy(saving = true, entityLine = null, refusal = null) }
        viewModelScope.launch {
            val next = try {
                when (target) {
                    is SeasonSyncSheetTarget.Asset -> when (asked.purpose) {
                        SeasonSyncSheetPurpose.LINK ->
                            afterWrite(target.assetId, link.run(target.assetId, asked.entityToLink))
                        SeasonSyncSheetPurpose.RESUME -> afterWrite(target.assetId, resume.run(target.assetId))
                        // #129: the edit moves no season mode, so #78's question is never asked after it.
                        SeasonSyncSheetPurpose.CHANGE_ENTITY -> {
                            changeEntity(target.assetId, asked.entityToLink)
                            Done(SeasonSheetExit.CLOSED)
                        }
                    }
                    is SeasonSyncSheetTarget.Schedule -> when (asked.purpose) {
                        SeasonSyncSheetPurpose.LINK -> {
                            linkSchedule(target.scheduleId, asked.entityToLink)
                            Done(SeasonSheetExit.CLOSED)
                        }
                        // Refused at construction (`init`); the arms are the exhaustive `when`'s, never reached.
                        SeasonSyncSheetPurpose.RESUME, SeasonSyncSheetPurpose.CHANGE_ENTITY ->
                            Done(SeasonSheetExit.CLOSED)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ScheduleSeasonRefused) {
                scheduleRefusedFor(scheduleSeasonRefusalLine(e.reason, asked.entityToLink))
            } catch (e: SeasonSyncLinkRefused) {
                refusedFor(e.reason, asked.entityToLink)
            } catch (e: SeasonModeStrandsPolicy) {
                Refused(refusal = seasonStrands(e.schedules.map(StrandedSchedule::title)))
            } catch (e: SeasonSyncOwnsSeason) {
                Refused(refusal = SEASON_SYNC_FOLLOWS_HA)
            } catch (e: SeasonSyncNotLinked) {
                // The binding went with a Disconnect: P16-10. Gone any other way, the card offers Link again.
                if (connections.get() == null) Refused(refusal = HA_NOT_CONNECTED) else Done(SeasonSheetExit.CLOSED)
            } catch (e: Exception) {
                // A store failed: nothing was written and no sentence is ratified for it; the sheet stays as typed.
                Refused()
            }
            _state.update { next.applyTo(it.copy(saving = false)) }
        }
    }

    /** #78's P78-3, and any other dismissal of the question: the sheet closes. Writes nothing. */
    fun keepSchedules() = answer(SeasonSheetExit.CLOSED)

    /** #78's P78-2: the sheet closes onto the asset's schedules. Writes nothing. */
    fun reviewSchedules() = answer(SeasonSheetExit.REVIEW_SCHEDULES)

    private fun answer(exit: SeasonSheetExit) = _state.update {
        if (it.prompt == null) it else it.copy(prompt = null, finished = exit)
    }

    /** After the write: #78's question when the asset left YEAR_ROUND with live CONTINUOUS schedules, else close. */
    private suspend fun afterWrite(assetId: AssetId, written: SeasonSyncLinked): Next {
        if (written.switchedFrom != SeasonMode.YEAR_ROUND) return Done(SeasonSheetExit.CLOSED)
        // A failed read closes as a write that asked nothing, as the editor's does; a cancellation goes back out.
        val count = runCatching { liveContinuousCount(schedules.forAsset(assetId)) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(0)
        return if (count > 0) Asking(EditPrompt.ReconcileSchedules(count)) else Done(SeasonSheetExit.CLOSED)
    }

    /** C16's codes, each its ratified sentence. ALREADY_LINKED has none: the asset is linked, and the card says so. */
    private fun refusedFor(reason: SeasonSyncLinkRefusal, entityId: String): Next = when (reason) {
        SeasonSyncLinkRefusal.BAD_ENTITY_ID -> Refused(entityLine = SEASON_SYNC_BAD_ENTITY_ID)
        SeasonSyncLinkRefusal.OUTBOUND_TARGET -> Refused(entityLine = seasonSyncOutboundTarget(entityId))
        SeasonSyncLinkRefusal.NOT_MAINTAINED_HERE -> Refused(refusal = SEASON_SYNC_NOT_MAINTAINED_HERE)
        SeasonSyncLinkRefusal.NO_CONNECTION -> Refused(refusal = HA_NOT_CONNECTED)
        SeasonSyncLinkRefusal.NEEDS_TOKEN -> Refused(refusal = HA_ENTER_TOKEN_AGAIN)
        SeasonSyncLinkRefusal.ALREADY_LINKED -> Done(SeasonSheetExit.CLOSED)
    }

    /** #125: a schedule's refusal, where its sentence goes; none (`ALREADY_LINKED`) closes onto the row saying so. */
    private fun scheduleRefusedFor(line: ScheduleSeasonRefusalLine?): Next = when (line) {
        null -> Done(SeasonSheetExit.CLOSED)
        is ScheduleSeasonRefusalLine.UnderEntity -> Refused(entityLine = line.text)
        is ScheduleSeasonRefusalLine.InSheet -> Refused(refusal = line.text)
    }

    /** What a Save came to, applied to the state in one step. */
    private sealed interface Next {
        fun applyTo(state: LinkSeasonSyncState): LinkSeasonSyncState
    }

    private data class Refused(val entityLine: String? = null, val refusal: String? = null) : Next {
        override fun applyTo(state: LinkSeasonSyncState) = state.copy(entityLine = entityLine, refusal = refusal)
    }

    private data class Asking(val prompt: EditPrompt) : Next {
        override fun applyTo(state: LinkSeasonSyncState) = state.copy(prompt = prompt)
    }

    private data class Done(val exit: SeasonSheetExit) : Next {
        override fun applyTo(state: LinkSeasonSyncState) = state.copy(finished = exit)
    }

    private companion object {
        /** The asset sheet's constructions link no schedule; reaching this is a wiring fault, never a fallback. */
        val NO_SCHEDULE_LINK: suspend (ScheduleId, String) -> Unit = { _, _ -> error("this sheet links an asset") }

        /** #125's construction has no Change helper; reaching either seam is a wiring fault, never a fallback. */
        val NO_STORED_ENTITY: suspend (AssetId) -> String? = { error("this sheet was built without Change helper") }

        val NO_CHANGE_ENTITY: suspend (AssetId, String) -> Unit =
            { _, _ -> error("this sheet was built without Change helper") }
    }
}
