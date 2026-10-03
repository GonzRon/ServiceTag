package com.loosecannon.servicetag.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.LinkSeasonSync
import com.loosecannon.servicetag.core.seasonsync.ResumeSeasonSync
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefusal
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefused
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinked
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncNotLinked
import com.loosecannon.servicetag.core.usecase.SeasonModeStrandsPolicy
import com.loosecannon.servicetag.core.usecase.SeasonSyncOwnsSeason
import com.loosecannon.servicetag.core.usecase.StrandedSchedule
import com.loosecannon.servicetag.core.usecase.liveContinuousCount
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.homeassistant.HA_ENTER_TOKEN_AGAIN
import com.loosecannon.servicetag.ui.homeassistant.HA_NOT_CONNECTED
import kotlin.coroutines.cancellation.CancellationException
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
 * #16 (C27) — the setup sheet as drawn, one immutable value. [sentence] is the asset's mode's reconciliation sentence
 * (P16-44/45/46), null until the asset is read; Save is held until it is there, so nothing is written before the owner
 * has seen it. [entityLine] is P16-49 under the field; [refusal] is S55 or another refusal's sentence, the sheet open;
 * [prompt] is #78's question after a written link or resume out of YEAR_ROUND; [finished] tells the screen to close.
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
) {
    val canSave: Boolean get() = sentence != null && !saving && prompt == null && finished == null
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
) : ViewModel() {

    constructor(graph: AppGraph, assetId: AssetId, purpose: SeasonSyncSheetPurpose) : this(
        assetId, purpose, graph.assets, graph.schedules, graph.haConnections, graph.linkSeasonSync,
        graph.resumeSeasonSync,
    )

    private val _state = MutableStateFlow(LinkSeasonSyncState(purpose))
    val state: StateFlow<LinkSeasonSyncState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val mode = assets.get(assetId)?.seasonMode
            _state.update { it.copy(sentence = mode?.let(::seasonSyncLinkSentence)) }
        }
    }

    fun onEntityId(text: String) = _state.update { it.copy(entityId = text, entityLine = null, refusal = null) }

    fun save() {
        val asked = _state.value
        if (!asked.canSave) return
        _state.update { it.copy(saving = true, entityLine = null, refusal = null) }
        viewModelScope.launch {
            val next = try {
                val written = when (asked.purpose) {
                    SeasonSyncSheetPurpose.LINK -> link.run(assetId, asked.entityId.trim())
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
