package com.loosecannon.servicetag.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.seasonsync.EntityScope
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.LinkSeasonSync
import com.loosecannon.servicetag.core.seasonsync.ResumeSeasonSync
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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the setup sheet does on Save (C27): link an unlinked asset, or resume a stopped binding (R16-19). */
internal enum class SeasonSyncSheetPurpose { LINK, RESUME }

/** How the sheet ended: closed, or closed onto the asset's schedules (#78's P78-2). */
internal enum class SeasonSheetExit { CLOSED, REVIEW_SCHEDULES }

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
    val entityId: String = "",
    val entityLine: String? = null,
    val refusal: String? = null,
    val prompt: EditPrompt? = null,
    val saving: Boolean = false,
    val finished: SeasonSheetExit? = null,
    val chosen: HaEntityCandidate? = null,
    val manualEntry: Boolean = false,
    val browse: EntityBrowseState? = null,
) {
    /** Link also waits for something to send (#105 row 16): a pick, or typed text. Resume sends no entity. */
    val canSave: Boolean get() = sentence != null && !saving && prompt == null && finished == null &&
        (purpose == SeasonSyncSheetPurpose.RESUME || entityToLink.isNotEmpty())

    val entityToLink: String get() = chosen?.entityId ?: entityId.trim()
}

/**
 * #16 (C27; R16-Q-C, R16-Q-G, R16-19) — the setup sheet's model. It reads the asset once for its sentence and writes
 * only through the shipped [LinkSeasonSync] or [ResumeSeasonSync] on Save; each refusal comes back as a code or a
 * shipped exception and is drawn as its ratified sentence. After a write that left YEAR_ROUND it asks #78's question
 * with core's [liveContinuousCount], never silently; the answer writes nothing.
 */
internal class LinkSeasonSyncViewModel(
    private val assetId: AssetId,
    purpose: SeasonSyncSheetPurpose,
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val connections: HaConnectionRepository,
    private val link: LinkSeasonSync,
    private val resume: ResumeSeasonSync,
    /**
     * #105: the one foreground list read, over the stored connection and its token (`AppGraph.listHaEntities`): null
     * when no connection is stored, else the client's outcome. Called only by Choose entity and Refresh, never on
     * construction. No default: a wiring that forgets it does not compile.
     */
    private val listEntities: suspend () -> HaListOutcome?,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: AssetId, purpose: SeasonSyncSheetPurpose) : this(
        assetId, purpose, graph.assets, graph.schedules, graph.haConnections, graph.linkSeasonSync,
        graph.resumeSeasonSync, graph.listHaEntities,
    )

    private val _state = MutableStateFlow(LinkSeasonSyncState(purpose))
    val state: StateFlow<LinkSeasonSyncState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val mode = assets.get(assetId)?.seasonMode
            _state.update { it.copy(sentence = mode?.let(::seasonSyncLinkSentence)) }
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
     * which disconnects it. Each opening's model is keyed under the asset page and outlives its sheet, so nothing else
     * would stop it.
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
                val written = when (asked.purpose) {
                    SeasonSyncSheetPurpose.LINK -> link.run(assetId, asked.entityToLink)
                    SeasonSyncSheetPurpose.RESUME -> resume.run(assetId)
                }
                afterWrite(written)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SeasonSyncLinkRefused) {
                refusedFor(e.reason)
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
    private suspend fun afterWrite(written: SeasonSyncLinked): Next {
        if (written.switchedFrom != SeasonMode.YEAR_ROUND) return Done(SeasonSheetExit.CLOSED)
        // A failed read closes as a write that asked nothing, as the editor's does; a cancellation goes back out.
        val count = runCatching { liveContinuousCount(schedules.forAsset(assetId)) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(0)
        return if (count > 0) Asking(EditPrompt.ReconcileSchedules(count)) else Done(SeasonSheetExit.CLOSED)
    }

    /** C16's codes, each its ratified sentence. ALREADY_LINKED has none: the asset is linked, and the card says so. */
    private fun refusedFor(reason: SeasonSyncLinkRefusal): Next = when (reason) {
        SeasonSyncLinkRefusal.BAD_ENTITY_ID -> Refused(entityLine = SEASON_SYNC_BAD_ENTITY_ID)
        SeasonSyncLinkRefusal.NOT_MAINTAINED_HERE -> Refused(refusal = SEASON_SYNC_NOT_MAINTAINED_HERE)
        SeasonSyncLinkRefusal.NO_CONNECTION -> Refused(refusal = HA_NOT_CONNECTED)
        SeasonSyncLinkRefusal.NEEDS_TOKEN -> Refused(refusal = HA_ENTER_TOKEN_AGAIN)
        SeasonSyncLinkRefusal.ALREADY_LINKED -> Done(SeasonSheetExit.CLOSED)
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
}
