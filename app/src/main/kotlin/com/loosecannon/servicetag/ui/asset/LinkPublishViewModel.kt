package com.loosecannon.servicetag.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.hapublish.FIELD_BOUNDARY_ENTITY_ID
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishRefused
import com.loosecannon.servicetag.core.hapublish.validateDateTarget
import com.loosecannon.servicetag.core.hapublish.validateTarget
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
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
    /**
     * #114 (C9a, D) — the season-ended helper's field (P114-47) is asked for exactly when the date kind publishes for a
     * `MANUAL` asset whose season an enabled #16 binding drives; [boundaryEntityId] is its typed id and [boundaryLine]
     * the refusal under it.
     */
    val boundaryAsked: Boolean = false,
    val boundaryEntityId: String = "",
    val boundaryLine: String? = null,
) {
    /** What Check helper reads and Publish names: the picked id, else the trimmed text. */
    val target: String get() = chosen?.entityId ?: entityId.trim()

    /** The season-ended helper Check helper reads with [target], or null when it is not asked for. */
    val boundary: String? get() = boundaryEntityId.trim().takeIf { boundaryAsked }

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
    /** #16's inbound bindings: whether the asset's season follows Home Assistant (C9a). */
    private val seasonSync: SeasonSyncRepository,
    private val controls: PublishControls,
    private val validate: suspend (AssetId, PublishKind, String, String?) -> Validation,
    /**
     * B6a review M2 — the stored helper (and season-ended helper) when the switch turns a stopped or copied setup back
     * on: the sheet opens on them, typed, so the owner checks exactly the helper P113-21 names.
     */
    initial: String? = null,
    initialBoundary: String? = null,
    /**
     * #105's one foreground list read over the stored connection and its token (`AppGraph.listHaEntities`): null with
     * no connection, else the client's outcome. Called only by Choose helper and Refresh.
     */
    private val listEntities: suspend () -> HaListOutcome?,
) : ViewModel() {

    constructor(
        graph: AppGraph,
        assetId: AssetId,
        kind: PublishKind,
        initial: String? = null,
        initialBoundary: String? = null,
    ) : this(
        assetId, kind, graph.assets, graph.seasonSyncBindings, graph.publishControls, graph.publishRunner::validate,
        initial, initialBoundary, graph.listHaEntities,
    )

    private val words: PublishKindWords? = publishWordsOf(kind)
    private val scope = entityScopeOf(kind)
    private val _state = MutableStateFlow(
        LinkPublishState(
            kind,
            entityId = initial.orEmpty(),
            manualEntry = initial != null,
            boundaryEntityId = initialBoundary.orEmpty(),
        ),
    )
    val state: StateFlow<LinkPublishState> = _state.asStateFlow()

    /** The fresh read the confirm stands on, for exactly [LinkPublishState.target]; dropped with any change. */
    private var validated: Validation.Validated? = null

    /** A typed id is the manual path: it clears any pick and any read, so Publish names what the owner can see. */
    fun onEntityId(text: String) {
        validated = null
        _state.update { it.copy(entityId = text, chosen = null).cleared() }
    }

    init {
        // C9a, D: whether this sheet asks for the season-ended helper, read once when it opens.
        viewModelScope.launch {
            val asked = try {
                boundaryAsked(kind, assets.get(assetId)?.seasonMode, seasonSync.get(assetId)?.enabled == true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A store failed: the field stays hidden, and Enable's own refusal (P113-29) says so if it is needed.
                false
            }
            _state.update { it.copy(boundaryAsked = asked) }
        }
    }

    /** P114-47: a typed season-ended helper; like the helper itself, any change drops the read. */
    fun onBoundaryEntityId(text: String) {
        validated = null
        _state.update { it.copy(boundaryEntityId = text).cleared() }
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

    /**
     * Check helper (P113-25): P113-29 for nothing or a malformed id, under the helper's field or the season-ended
     * helper's; P114-48 for a season-ended helper that is the helper itself; else the fresh read of both and its answer,
     * each sentence naming the helper it is about.
     */
    fun check() {
        val asked = _state.value
        val words = words ?: return
        if (!asked.canCheck) return
        val target = asked.target
        val boundary = asked.boundary
        validated = null
        if (target.isEmpty() || !isValidEntityId(target)) {
            _state.update { it.cleared().copy(entityLine = HA_PUBLISH_CHOOSE_HELPER_FIRST) }
            return
        }
        if (boundary != null && (boundary.isEmpty() || !isValidEntityId(boundary))) {
            _state.update { it.cleared().copy(boundaryLine = HA_PUBLISH_CHOOSE_HELPER_FIRST) }
            return
        }
        if (boundary == target) {
            _state.update { it.cleared().copy(boundaryLine = words.boundaryIsTarget(boundary)) }
            return
        }
        _state.update { it.cleared().copy(checking = true) }
        viewModelScope.launch {
            val next = try {
                when (val read = validate(assetId, kind, target, boundary)) {
                    Validation.NoConnection -> Answer(listOf(HA_NOT_CONNECTED))
                    is Validation.ReadFailed -> {
                        val named = if (read.field == FIELD_BOUNDARY_ENTITY_ID) boundary.orEmpty() else target
                        Answer(publishErrorLines(read.kind, read.detail, named, words))
                    }
                    is Validation.Validated -> {
                        val shape = validateTarget(kind, target, read.target.remote)
                        val boundaryShape = read.boundary?.let { validateDateTarget(it.target, it.remote) }
                        if (shape != null) {
                            Answer(publishErrorLines(shape, null, target, words))
                        } else if (boundaryShape != null) {
                            Answer(publishErrorLines(boundaryShape, null, boundary.orEmpty(), words))
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
                // A helper changed while the read ran: its answer is for another pair.
                if (it.target != target || it.boundary != boundary) {
                    it.copy(checking = false)
                } else {
                    it.copy(checking = false, answer = next.lines, confirm = next.confirm)
                }
            }
        }
    }

    /** P113-6: Enable with exactly the read the confirm stands on. */
    fun publish() {
        val asked = _state.value
        val words = words ?: return
        val held = validated ?: return
        if (!asked.canPublish || held.target.target != asked.target || held.boundary?.target != asked.boundary) return
        _state.update { it.copy(enabling = true, refusal = null) }
        viewModelScope.launch {
            val outcome = try {
                controls.enable(assetId, kind, asked.target, held.target, held.boundary)
                Enabled.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: PublishRefused) {
                val named = if (e.field == FIELD_BOUNDARY_ENTITY_ID) asked.boundary.orEmpty() else asked.target
                Enabled.Refused(publishRefusalLine(e.reason, named, assets.get(assetId)?.name.orEmpty(), words))
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
        copy(entityLine = null, boundaryLine = null, answer = emptyList(), confirm = null, refusal = null)
}

/**
 * C9a, D — EnablePublishing's own rule (`boundaryOf`): the season-ended helper is asked for exactly when the date kind
 * publishes for a `MANUAL` asset whose season an enabled #16 binding drives ([mode] null: the asset is gone). Seasonality
 * is the asset's own setting (G22): nothing here reads a name or a type.
 */
internal fun boundaryAsked(kind: PublishKind, mode: SeasonMode?, inboundEnabled: Boolean): Boolean = when (kind) {
    PublishKind.EQUIPMENT_STATUS -> false
    PublishKind.SEASON_SERVICE_DATE -> when (mode) {
        null, SeasonMode.YEAR_ROUND, SeasonMode.CALENDAR -> false
        SeasonMode.MANUAL -> inboundEnabled
    }
}
