package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.seasonsync.AppliedLine
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.LastAppliedSource
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonBinding
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonBindingRepository
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefusal
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncState
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.UnlinkScheduleSeason
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedDateTime
import com.loosecannon.servicetag.seasonsync.currentScheduleSeasonState
import com.loosecannon.servicetag.seasonsync.seasonSyncStale
import com.loosecannon.servicetag.ui.asset.LinkSeasonSyncSheet
import com.loosecannon.servicetag.ui.asset.RefusalLine
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_BAD_ENTITY_ID
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_FOLLOW
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_NOT_CHECKED_IN_TIME
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_NOT_MAINTAINED_HERE
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_NO_READING_YET
import com.loosecannon.servicetag.ui.asset.SeasonSyncSheetPurpose
import com.loosecannon.servicetag.ui.asset.SeasonSyncSheetTarget
import com.loosecannon.servicetag.ui.asset.appliedLineText
import com.loosecannon.servicetag.ui.asset.seasonSyncChangedInHa
import com.loosecannon.servicetag.ui.asset.seasonSyncErrorNotices
import com.loosecannon.servicetag.ui.asset.seasonSyncLastSuccess
import com.loosecannon.servicetag.ui.asset.seasonSyncOutboundTarget
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.homeassistant.HA_ENTER_TOKEN_AGAIN
import com.loosecannon.servicetag.ui.homeassistant.HA_NOT_CONNECTED
import com.loosecannon.servicetag.ui.theme.ControlShape
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// #125 (U-C8, §5) — a schedule's own season on its detail screen: the ratified sentences P125-1…7, each declared once
// here; the words live in res/values/strings_maintenance.xml (`schedule_season_*`), each beside its P125 id. #16's
// sentences are imported from the asset sheet's and the Home Assistant screen's files, never copied.

/** P125-1 — the row, `ASSET` source: [assetName]'s season. */
internal fun scheduleSeasonOfAsset(assetName: String): String = localized(R.string.schedule_season_asset, assetName)

/** P125-2 — the row, `OWN` with a binding: the schedule follows [entityId]. */
internal fun scheduleSeasonFollows(entityId: String): String = localized(R.string.schedule_season_follows, entityId)

/** P125-3 — the unlink action, and its confirm's button. */
internal val SCHEDULE_SEASON_USE_ASSET: String get() = localized(R.string.schedule_season_use_asset)

/** P125-4 — the unlink's confirm: the schedule will use [assetName]'s season again. */
internal fun scheduleSeasonUseAssetConfirm(assetName: String): String =
    localized(R.string.schedule_season_use_asset_confirm, assetName)

/** P125-5 — the row, `OWN` without a binding (stranded: after Forget, or after an import). */
internal val SCHEDULE_SEASON_STRANDED: String get() = localized(R.string.schedule_season_stranded)

/** P125-6 — `GROUP_TARGET`. */
internal val SCHEDULE_SEASON_REFUSED_GROUP: String get() = localized(R.string.schedule_season_refused_group)

/** P125-7 — `STRANDS_PRE_SERVICE`. */
internal val SCHEDULE_SEASON_REFUSED_STRANDS_PRE_SERVICE: String
    get() = localized(R.string.schedule_season_refused_strands_pre_service)

/** P114-19, reused for `SCHEDULE_ARCHIVED`: the same resource the Season Start role's refusal reads. */
private val SCHEDULE_SEASON_REFUSED_ARCHIVED: String get() = localized(R.string.season_start_refused_schedule_archived)

/** Where a refused schedule link's sentence goes in the sheet: under the entity, or the sheet's own line. */
internal sealed interface ScheduleSeasonRefusalLine {
    val text: String

    data class UnderEntity(override val text: String) : ScheduleSeasonRefusalLine

    data class InSheet(override val text: String) : ScheduleSeasonRefusalLine
}

/**
 * #125 (U-C3, U-C8) — each `ScheduleSeasonRefusal`, its ratified sentence and its place: an exhaustive `when`, no
 * `else`. The two only an entity id causes (P16-49, P113-43, for [entityId]) sit under the field, as #16's do;
 * `ALREADY_LINKED` has none: the schedule is linked, the sheet closes and the row says so.
 */
internal fun scheduleSeasonRefusalLine(reason: ScheduleSeasonRefusal, entityId: String): ScheduleSeasonRefusalLine? =
    when (reason) {
        ScheduleSeasonRefusal.NOT_MAINTAINED_HERE -> ScheduleSeasonRefusalLine.InSheet(SEASON_SYNC_NOT_MAINTAINED_HERE)
        ScheduleSeasonRefusal.NO_CONNECTION -> ScheduleSeasonRefusalLine.InSheet(HA_NOT_CONNECTED)
        ScheduleSeasonRefusal.NEEDS_TOKEN -> ScheduleSeasonRefusalLine.InSheet(HA_ENTER_TOKEN_AGAIN)
        ScheduleSeasonRefusal.BAD_ENTITY_ID -> ScheduleSeasonRefusalLine.UnderEntity(SEASON_SYNC_BAD_ENTITY_ID)
        ScheduleSeasonRefusal.ALREADY_LINKED -> null
        ScheduleSeasonRefusal.OUTBOUND_TARGET ->
            ScheduleSeasonRefusalLine.UnderEntity(seasonSyncOutboundTarget(entityId))
        ScheduleSeasonRefusal.GROUP_TARGET -> ScheduleSeasonRefusalLine.InSheet(SCHEDULE_SEASON_REFUSED_GROUP)
        ScheduleSeasonRefusal.SCHEDULE_ARCHIVED -> ScheduleSeasonRefusalLine.InSheet(SCHEDULE_SEASON_REFUSED_ARCHIVED)
        ScheduleSeasonRefusal.STRANDS_PRE_SERVICE ->
            ScheduleSeasonRefusalLine.InSheet(SCHEDULE_SEASON_REFUSED_STRANDS_PRE_SERVICE)
    }

/** The row's three shapes, and none: a group's schedule, or one not there, has no season of its own to show. */
internal enum class ScheduleSeasonRowKind { NONE, ASSET, FOLLOWING, STRANDED }

/**
 * #125 (U-C8) — the season row as drawn, one immutable value. [headline] is P125-1, P125-2 or P125-5; [statusLines]
 * are #16's status sentences for a binding (the last reading or P16-31, the stale marker, HA's change time, the
 * provenance, the binding's state) and [errorLines] its latest error's, in the card's order. [confirm] is P125-4 while
 * it is asked; [sheet] is the link sheet's target while it is open, [sheetOpening] keying a fresh sheet each time.
 */
internal data class ScheduleSeasonRowState(
    val kind: ScheduleSeasonRowKind = ScheduleSeasonRowKind.NONE,
    val headline: String? = null,
    val statusLines: List<String> = emptyList(),
    val errorLines: List<String> = emptyList(),
    val offersFollow: Boolean = false,
    val offersUseAsset: Boolean = false,
    val confirm: String? = null,
    val sheet: SeasonSyncSheetTarget.Schedule? = null,
    val sheetOpening: Int = 0,
    val busy: Boolean = false,
)

/**
 * #125 (U-C8; constraint 6) — the row's model. It reads the schedule, its asset, its binding (live), the connection,
 * the token and the open transfers, and draws:
 *
 * - `ASSET`: P125-1 and **Follow Home Assistant** (#16's `season_sync_follow`) while a connection is saved and the
 *   schedule is live on an asset maintained here — #16's P16-22 rule for the asset's own Link;
 * - `OWN` with a binding: P125-2, #16's status and error sentences, and **Use the asset's season** (P125-3);
 * - `OWN` without one (stranded): P125-5 and both actions, Follow even with no connection — P125-5 names it, and the
 *   sheet's refusal (P16-10) then says why.
 *
 * Follow opens `LinkSeasonSyncSheet` with the schedule as its target ([ScheduleSeasonRowState.sheet]); Use the asset's
 * season asks P125-4 first, then runs the shipped `UnlinkScheduleSeason`. Nothing here returns a schedule to its
 * asset's season but that confirm (no silent fallback).
 */
internal class ScheduleSeasonRowViewModel(
    private val scheduleId: ScheduleId,
    private val schedules: ScheduleRepository,
    private val assets: AssetRepository,
    bindings: ScheduleSeasonBindingRepository,
    private val connections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val transfers: TransferRecordRepository,
    private val unlink: UnlinkScheduleSeason,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    constructor(graph: AppGraph, scheduleId: String) : this(
        ScheduleId(scheduleId), graph.schedules, graph.assets, graph.scheduleSeasonBindings, graph.haConnections,
        graph.secretStore, graph.transferRecords, graph.unlinkScheduleSeason, graph.clock,
    )

    private data class Pending(
        val busy: Boolean = false,
        val confirm: String? = null,
        val sheet: SeasonSyncSheetTarget.Schedule? = null,
        val opening: Int = 0,
    )

    private val pending = MutableStateFlow(Pending())
    private val refreshes = MutableStateFlow(0)

    val state: StateFlow<ScheduleSeasonRowState> = combine(
        schedules.observeAll(),
        assets.observeAll(),
        bindings.observeFor(scheduleId),
        refreshes,
        pending,
    ) { _, _, binding, _, pending ->
        build(binding).copy(
            busy = pending.busy,
            confirm = pending.confirm,
            sheet = pending.sheet,
            sheetOpening = pending.opening,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleSeasonRowState())

    /** Reads the connection, the token and the clock again: on resume. */
    fun refresh() = refreshes.update { it + 1 }

    /** Follow Home Assistant: the link sheet, for this schedule, a fresh opening. Writes nothing. */
    fun follow() {
        if (!state.value.offersFollow || state.value.busy) return
        pending.update { it.copy(sheet = SeasonSyncSheetTarget.Schedule(scheduleId), opening = it.opening + 1) }
    }

    /** The sheet closed: saved, refused and cancelled, or dismissed. The row re-reads the stores itself. */
    fun sheetClosed() = pending.update { it.copy(sheet = null) }

    /** Use the asset's season: P125-4 with the asset's name. Writes nothing. */
    fun askUseAsset() {
        if (!state.value.offersUseAsset || state.value.busy) return
        viewModelScope.launch {
            val name = assetOf()?.name ?: return@launch
            pending.update { it.copy(confirm = scheduleSeasonUseAssetConfirm(name)) }
        }
    }

    /** The confirm's Cancel: nothing is written. */
    fun dismissUseAsset() = pending.update { it.copy(confirm = null) }

    /** The confirm's button: the shipped unlink, in one write; the own rows stay as history. */
    fun confirmUseAsset() {
        val now = pending.value
        if (now.confirm == null || now.busy) return
        pending.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                unlink.run(scheduleId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The schedule went, or a store failed: nothing was written and no sentence is ratified for it; the
                // row re-derives from the stores and the owner can ask again.
            }
            pending.update { it.copy(busy = false, confirm = null) }
        }
    }

    private suspend fun assetOf() = (schedules.get(scheduleId)?.target as? ScheduleTarget.AssetTarget)
        ?.let { assets.get(it.assetId) }

    private suspend fun build(binding: ScheduleSeasonBinding?): ScheduleSeasonRowState {
        val schedule = schedules.get(scheduleId) ?: return ScheduleSeasonRowState()
        val target = schedule.target as? ScheduleTarget.AssetTarget ?: return ScheduleSeasonRowState()
        val asset = assets.get(target.assetId) ?: return ScheduleSeasonRowState()
        val here = asset.maintainedHere(transfers.heldIds())
        val live = schedule.status != ScheduleStatus.ARCHIVED && here
        return when (schedule.seasonSource) {
            ScheduleSeasonSource.ASSET -> ScheduleSeasonRowState(
                kind = ScheduleSeasonRowKind.ASSET,
                headline = scheduleSeasonOfAsset(asset.name),
                offersFollow = live && connections.get() != null,
            )
            ScheduleSeasonSource.OWN -> if (binding == null) {
                ScheduleSeasonRowState(
                    kind = ScheduleSeasonRowKind.STRANDED,
                    headline = SCHEDULE_SEASON_STRANDED,
                    offersFollow = live,
                    offersUseAsset = true,
                )
            } else {
                following(binding)
            }
        }
    }

    /** #16's status lines over the schedule's binding, in the card's order (C27); an error never takes their place. */
    private suspend fun following(binding: ScheduleSeasonBinding): ScheduleSeasonRowState {
        val connection = connections.get()
        val stateLine = when (derivedState(binding)) {
            SeasonSyncState.NEEDS_TOKEN -> HA_ENTER_TOKEN_AGAIN
            SeasonSyncState.NOT_MAINTAINED_HERE -> SEASON_SYNC_NOT_MAINTAINED_HERE
            SeasonSyncState.ACTIVE, SeasonSyncState.STOPPED -> null
        }
        // A refusal already the binding's state line (P16-11, P16-36) is drawn once, as on the asset's card.
        val errors = binding.errorKind?.let { seasonSyncErrorNotices(it, binding.errorDetail, binding.entityId) }
            .orEmpty()
            .map { it.text }
            .filterNot { it == stateLine }
        return ScheduleSeasonRowState(
            kind = ScheduleSeasonRowKind.FOLLOWING,
            headline = scheduleSeasonFollows(binding.entityId),
            statusLines = listOfNotNull(
                binding.lastSuccessAt?.let { seasonSyncLastSuccess(dateTime(Instant.ofEpochMilli(it))) }
                    ?: SEASON_SYNC_NO_READING_YET,
                SEASON_SYNC_NOT_CHECKED_IN_TIME.takeIf {
                    connection == null || seasonSyncStale(binding.lastSuccessAt, connection.cadence, clock.nowMillis())
                },
                binding.observedChangedAt?.let(::haTime)?.let(::seasonSyncChangedInHa),
                appliedLineOf(binding)?.let { appliedLineText(it, day(binding.appliedOn)) },
                stateLine,
            ),
            errorLines = errors,
            offersUseAsset = true,
        )
    }

    /** The runner's derived state; a key store that cannot answer reads as no token (B4's rule), never a crash. */
    private suspend fun derivedState(binding: ScheduleSeasonBinding): SeasonSyncState = try {
        currentScheduleSeasonState(binding, secrets, schedules, assets, transfers)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SeasonSyncState.NEEDS_TOKEN
    }

    /** C33(4)'s provenance for a schedule's binding: core's rule, by its source (always Home Assistant in 1.9.0). */
    private fun appliedLineOf(binding: ScheduleSeasonBinding): AppliedLine? = when (binding.lastAppliedSource) {
        null -> null
        LastAppliedSource.HOME_ASSISTANT -> when (binding.appliedAction) {
            SeasonAction.START -> AppliedLine.STARTED_FROM_HOME_ASSISTANT
            SeasonAction.END -> AppliedLine.ENDED_FROM_HOME_ASSISTANT
            null -> null
        }
        LastAppliedSource.FORCED_IN -> AppliedLine.FORCED_IN_SEASON
        LastAppliedSource.FORCED_OUT -> AppliedLine.FORCED_OUT_OF_SEASON
    }

    private fun dateTime(at: Instant): String = localizedDateTime(at.atZone(zone()).toLocalDateTime())

    /** HA's `last_changed`, shown only when it parses as ISO-8601 (P16-33). */
    private fun haTime(text: String): String? =
        runCatching { dateTime(OffsetDateTime.parse(text).toInstant()) }.getOrNull()

    /** The applied day in the shipped display date; a string the model would refuse shows verbatim. */
    private fun day(on: String?): String =
        on?.let { runCatching { displayDate(LocalDate.parse(it)) }.getOrDefault(it) }.orEmpty()
}

/**
 * #125 (U-C8) — the schedule detail's season row (group C; no `Scaffold`, sheet or input dialog of its own): the
 * headline, #16's status and error lines, the two actions while [editable] (a held owner's schedule offers no write),
 * P125-4's confirm, which takes no typed input, and #16's `LinkSeasonSyncSheet` with the schedule as its target.
 */
@Composable
internal fun ScheduleSeasonRow(graph: AppGraph, scheduleId: String, editable: Boolean) {
    val model: ScheduleSeasonRowViewModel =
        viewModel(key = "schedule-season:$scheduleId") { ScheduleSeasonRowViewModel(graph, scheduleId) }
    val state by model.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose { }
    }
    ScheduleSeasonRowContent(
        state = state, editable = editable, onFollow = model::follow, onUseAsset = model::askUseAsset,
    )
    state.confirm?.let { question ->
        AlertDialog(
            onDismissRequest = model::dismissUseAsset,
            text = { Text(question) },
            confirmButton = {
                TextButton(onClick = model::confirmUseAsset, enabled = !state.busy) { Text(SCHEDULE_SEASON_USE_ASSET) }
            },
            dismissButton = {
                TextButton(onClick = model::dismissUseAsset) { Text(stringResource(R.string.maintenance_cancel)) }
            },
        )
    }
    state.sheet?.let { target ->
        LinkSeasonSyncSheet(
            graph = graph,
            target = target,
            purpose = SeasonSyncSheetPurpose.LINK,
            opening = state.sheetOpening,
            // A schedule's link never changes its asset's mode, so #78's question is never asked from here.
            onReviewSchedules = {},
            onDone = model::sheetClosed,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSeasonRowContent(
    state: ScheduleSeasonRowState,
    editable: Boolean,
    onFollow: () -> Unit,
    onUseAsset: () -> Unit,
) {
    val headline = state.headline ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(headline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        state.statusLines.forEach { QuietLine(it) }
        state.errorLines.forEach { RefusalLine(it) }
        if (editable && (state.offersFollow || state.offersUseAsset)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.offersFollow) {
                    OutlinedButton(onClick = onFollow, enabled = !state.busy, shape = ControlShape) {
                        Text(SEASON_SYNC_FOLLOW)
                    }
                }
                if (state.offersUseAsset) {
                    OutlinedButton(onClick = onUseAsset, enabled = !state.busy, shape = ControlShape) {
                        Text(SCHEDULE_SEASON_USE_ASSET)
                    }
                }
            }
        }
    }
}
