package com.loosecannon.servicetag.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishRefused
import com.loosecannon.servicetag.core.hapublish.validateTarget
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.isValidEntityId
import com.loosecannon.servicetag.core.seasonsync.pickerRows
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.hapublish.PublishControls
import com.loosecannon.servicetag.hapublish.Validation
import com.loosecannon.servicetag.ui.homeassistant.HA_NOT_CONNECTED
import com.loosecannon.servicetag.ui.homeassistant.Notice
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * #113/#114 (C36) — the setup sheet as drawn, one immutable value. The helper is picked in the browser ([chosen],
 * [browse], scoped to the kind's domain) or typed ([entityId], [manualEntry]); Check helper reads it fresh
 * ([checking], P113-28) and answers either the read's or the validator's sentences ([answer]) or the confirm naming the
 * exact destination and the mapping ([confirm], P113-5), whose Publish enables. [entityLine] is P113-29 under the
 * field; [refusal] a command's refusal, the sheet open; [finished] tells the screen to close.
 */
internal data class LinkPublishState(
    val kind: PublishKind,
    val entityId: String = "",
    val chosen: HaEntityCandidate? = null,
    val manualEntry: Boolean = false,
    val browse: EntityBrowseState? = null,
    val checking: Boolean = false,
    val entityLine: String? = null,
    val answer: List<String> = emptyList(),
    val confirm: String? = null,
    val enabling: Boolean = false,
    val refusal: String? = null,
    val finished: Boolean = false,
) {
    /** What Check helper reads and Publish names: the picked id, else the trimmed text. */
    val target: String get() = chosen?.entityId ?: entityId.trim()

    val canCheck: Boolean get() = !checking && !enabling && !finished

    /** Publish needs the confirm, which only a fresh read the validator accepts draws. */
    val canPublish: Boolean get() = confirm != null && !checking && !enabling && !finished
}

/**
 * #113/#114 (C36, C30, C32) — the setup sheet's model. Nothing is read until Choose helper, Refresh or Check helper;
 * nothing is written before Publish. Check helper is the runner's [validate] — one snapshot, then the helper's fresh
 * read — and then the kind's validator; only a result both accept draws the confirm (P113-5), and Publish commits
 * exactly that read through [PublishControls.enable], which activates this installation and asks for delivery
 * (A03, I26). Any change to the helper drops the read. A refusal comes back as a code and is drawn as its sentence.
 */
internal class LinkPublishViewModel(
    private val assetId: AssetId,
    private val kind: PublishKind,
    private val assets: AssetRepository,
    private val controls: PublishControls,
    private val validate: suspend (AssetId, PublishKind, String, String?) -> Validation,
    /**
     * #105's one foreground list read over the stored connection and its token (`AppGraph.listHaEntities`): null with
     * no connection, else the client's outcome. Called only by Choose helper and Refresh.
     */
    private val listEntities: suspend () -> HaListOutcome?,
) : ViewModel() {

    constructor(graph: AppGraph, assetId: AssetId, kind: PublishKind) : this(
        assetId, kind, graph.assets, graph.publishControls, graph.publishRunner::validate, graph.listHaEntities,
    )

    private val words: PublishKindWords? = publishWordsOf(kind)
    private val scope = entityScopeOf(kind)
    private val _state = MutableStateFlow(LinkPublishState(kind))
    val state: StateFlow<LinkPublishState> = _state.asStateFlow()

    /** The fresh read the confirm stands on, for exactly [LinkPublishState.target]; dropped with any change. */
    private var validated: Validation.Validated? = null

    /** A typed id is the manual path: it clears any pick and any read, so Publish names what the owner can see. */
    fun onEntityId(text: String) {
        validated = null
        _state.update { it.copy(entityId = text, chosen = null).cleared() }
    }

    // --- the browser (#105's, scoped to the kind's domain) ------------------------------------------------------------

    fun chooseHelper() {
        _state.update { it.copy(browse = EntityBrowseState()).cleared() }
        readEntities()
    }

    fun refreshEntities() {
        if (_state.value.browse == null) return
        readEntities()
    }

    fun onQuery(text: String) = _state.update { state ->
        state.browse?.let { state.copy(browse = withRows(it.copy(query = text))) } ?: state
    }

    fun pick(candidate: HaEntityCandidate) {
        stopReading()
        validated = null
        _state.update { it.copy(chosen = candidate, entityId = "", manualEntry = false, browse = null).cleared() }
    }

    fun closeBrowse() {
        stopReading()
        _state.update { it.copy(browse = null) }
    }

    /** Every way out but Publish: a list read still running stops with the sheet. */
    fun dismiss() = closeBrowse()

    fun enterManually() {
        stopReading()
        validated = null
        _state.update {
            it.copy(manualEntry = true, entityId = it.chosen?.entityId ?: it.entityId, chosen = null, browse = null).cleared()
        }
    }

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
                _state.update { state -> state.browse?.let { state.copy(browse = it.copy(loading = false)) } ?: state }
                return@launch
            }
            _state.update { state ->
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
                                scopeEmpty = pickerRows(outcome.entities, scope, "").rows.isEmpty(),
                            ),
                        )
                    },
                )
            }
        }
    }

    private fun withRows(browse: EntityBrowseState): EntityBrowseState {
        val shown = pickerRows(browse.all, scope, browse.query)
        return browse.copy(rows = shown.rows, truncatedList = shown.truncatedList)
    }

    // --- the fresh read, the confirm, Publish --------------------------------------------------------------------------

    /** Check helper (P113-25): P113-29 for nothing or a malformed id; else the fresh read and its answer. */
    fun check() {
        val asked = _state.value
        val words = words ?: return
        if (!asked.canCheck) return
        val target = asked.target
        validated = null
        if (target.isEmpty() || !isValidEntityId(target)) {
            _state.update { it.cleared().copy(entityLine = HA_PUBLISH_CHOOSE_HELPER_FIRST) }
            return
        }
        _state.update { it.cleared().copy(checking = true) }
        viewModelScope.launch {
            val next = try {
                when (val read = validate(assetId, kind, target, null)) {
                    Validation.NoConnection -> Answer(listOf(HA_NOT_CONNECTED))
                    is Validation.ReadFailed -> Answer(publishErrorLines(read.kind, read.detail, target, words))
                    is Validation.Validated -> {
                        val shape = validateTarget(kind, target, read.target.remote)
                        if (shape != null) {
                            Answer(publishErrorLines(shape, null, target, words))
                        } else {
                            validated = read
                            val name = assets.get(assetId)?.name.orEmpty()
                            Answer(confirm = words.confirm(name, target))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A store failed before anything was asked of Home Assistant: no sentence is ratified for it.
                Answer()
            }
            _state.update {
                // The helper changed while the read ran: its answer is for another helper.
                if (it.target != target) it.copy(checking = false) else it.copy(checking = false, answer = next.lines, confirm = next.confirm)
            }
        }
    }

    /** P113-6: Enable with exactly the read the confirm stands on. */
    fun publish() {
        val asked = _state.value
        val words = words ?: return
        val held = validated ?: return
        if (!asked.canPublish || held.target.target != asked.target) return
        _state.update { it.copy(enabling = true, refusal = null) }
        viewModelScope.launch {
            val outcome = try {
                controls.enable(assetId, kind, asked.target, held.target, held.boundary)
                Enabled.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: PublishRefused) {
                Enabled.Refused(publishRefusalLine(e.reason, asked.target, assets.get(assetId)?.name.orEmpty(), words))
            } catch (e: Exception) {
                // A store failed: nothing was written and no sentence is ratified for it; the sheet stays as it was.
                Enabled.Failed
            }
            when (outcome) {
                Enabled.Done -> _state.update { it.copy(enabling = false, finished = true) }
                is Enabled.Refused -> {
                    // A refused read is spent: the owner checks the helper again before Publish is offered again.
                    validated = null
                    _state.update { it.copy(enabling = false, refusal = outcome.line, confirm = null) }
                }
                Enabled.Failed -> _state.update { it.copy(enabling = false) }
            }
        }
    }

    /** What Publish came to. */
    private sealed interface Enabled {
        data object Done : Enabled

        data class Refused(val line: String?) : Enabled

        data object Failed : Enabled
    }

    private data class Answer(val lines: List<String> = emptyList(), val confirm: String? = null)

    /** A changed helper: no answer, confirm or refusal stands. */
    private fun LinkPublishState.cleared(): LinkPublishState =
        copy(entityLine = null, answer = emptyList(), confirm = null, refusal = null)
}
