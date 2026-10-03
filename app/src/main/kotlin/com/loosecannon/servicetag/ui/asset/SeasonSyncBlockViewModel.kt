package com.loosecannon.servicetag.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.ResumeSeasonSync
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefused
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncState
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SetSeasonSyncMode
import com.loosecannon.servicetag.core.seasonsync.StopSeasonSync
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.core.seasonsync.appliedLineOf
import com.loosecannon.servicetag.core.seasonsync.seasonSyncStateOf
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.seasonsync.SeasonSyncSchedule
import com.loosecannon.servicetag.seasonsync.seasonSyncScheduleOf
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.homeassistant.HA_BACKGROUND_PAUSED
import com.loosecannon.servicetag.ui.homeassistant.HA_ENTER_TOKEN_AGAIN
import com.loosecannon.servicetag.ui.homeassistant.Notice
import com.loosecannon.servicetag.ui.homeassistant.NoticeAction
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** C27's three shapes: no binding, an enabled one, a stopped one. */
internal enum class SeasonSyncBlockKind { NONE, ENABLED, STOPPED }

/**
 * #16 (C27) — the season card's Home Assistant block as drawn, one immutable value. Each line is its own fact: the
 * source ([sourceLine]), the last success or P16-31 ([lastSuccessLine]), the stale marker ([staleLine]), HA's change
 * text ([haChangedLine]), the provenance ([appliedLine], C33(4)), the binding's derived state ([stateLine]), the paused
 * background checks ([pausedLine]) and the latest error ([errorLines]); an error never takes the last-success line's
 * place. [notices] is the last command's refusal.
 */
internal data class SeasonSyncBlockState(
    val kind: SeasonSyncBlockKind = SeasonSyncBlockKind.NONE,
    /** No binding, a connection, the asset maintained here: P16-22 (C27). */
    val offersLink: Boolean = false,
    val mode: SyncMode? = null,
    /** The mode control and Sync now: an enabled binding on an asset maintained here and not held. */
    val offersControls: Boolean = false,
    val offersStop: Boolean = false,
    val offersResume: Boolean = false,
    /** Resume on an asset no longer MANUAL runs the link's reconciliation first, in the setup sheet (C17, R16-19). */
    val resumeOpensSheet: Boolean = false,
    val sourceLine: String? = null,
    val lastSuccessLine: String? = null,
    val staleLine: String? = null,
    val haChangedLine: String? = null,
    val appliedLine: String? = null,
    val stateLine: String? = null,
    val pausedLine: Notice? = null,
    val errorLines: List<Notice> = emptyList(),
    val stoppedLine: String? = null,
    val notices: List<Notice> = emptyList(),
    val busy: Boolean = false,
) {
    /** While a binding is enabled it owns the season (R16-1): the shipped Start and End are not drawn. */
    val hidesStartAndEnd: Boolean get() = kind == SeasonSyncBlockKind.ENABLED
}

/**
 * #16 (C27; R16-1, R16-15, R16-19) — the block's model: it reads the binding (live), the asset (live), the
 * connection, whether the token is on this phone and the open transfers, and derives the state with core's
 * [seasonSyncStateOf] and the provenance with core's [appliedLineOf]. The writes are the shipped use cases and the
 * runner's Sync now; a refusal comes back as a code and is drawn as its ratified sentence.
 */
internal class SeasonSyncBlockViewModel(
    private val assetId: AssetId,
    private val bindings: SeasonSyncRepository,
    private val connections: HaConnectionRepository,
    private val secrets: SecretStore,
    assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val setMode: SetSeasonSyncMode,
    private val stop: StopSeasonSync,
    private val resume: ResumeSeasonSync,
    private val runSyncNow: suspend (AssetId) -> Unit,
    private val backgroundAllowed: () -> Boolean,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    constructor(graph: AppGraph, assetId: AssetId) : this(
        assetId, graph.seasonSyncBindings, graph.haConnections, graph.secretStore, graph.assets,
        graph.transferRecords, graph.setSeasonSyncMode, graph.stopSeasonSync, graph.resumeSeasonSync,
        { graph.seasonSyncRunner.syncNow(it) }, graph.seasonSyncBackgroundAllowed, graph.clock,
    )

    private data class Pending(val busy: Boolean = false, val notices: List<Notice> = emptyList())

    private val pending = MutableStateFlow(Pending())
    private val refreshes = MutableStateFlow(0)

    val state: StateFlow<SeasonSyncBlockState> = combine(
        bindings.observeFor(assetId),
        assets.observeAll().map { all -> all.firstOrNull { it.id == assetId } }.distinctUntilChanged(),
        refreshes,
        pending,
    ) { binding, asset, _, pending -> build(binding, asset).copy(busy = pending.busy, notices = pending.notices) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SeasonSyncBlockState())

    /** Reads the connection, the token and the clock again: on resume, after a permission answer. */
    fun refresh() = refreshes.update { it + 1 }

    fun chooseMode(mode: SyncMode) = command { setMode.run(assetId, mode) }

    fun syncNow() = command { runSyncNow(assetId) }

    fun stopSyncing() = command { stop.run(assetId) }

    /** Resume on a MANUAL asset; on any other the screen opens the setup sheet ([SeasonSyncBlockState.resumeOpensSheet]). */
    fun resumeSyncing() = command { resume.run(assetId) }

    private fun command(block: suspend () -> Unit) {
        if (pending.value.busy) return
        pending.value = Pending(busy = true)
        viewModelScope.launch {
            val notices = try {
                block()
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SeasonSyncLinkRefused) {
                seasonSyncRefusalNotices(e.reason)
            } catch (e: Exception) {
                // The binding went (Disconnect), or a store failed: nothing was written, the status stays as it was,
                // and the next resume, run or Sync now repeats the step. No sentence is ratified for it.
                emptyList()
            }
            pending.value = Pending(notices = notices)
        }
    }

    private suspend fun build(binding: SeasonSyncBinding?, asset: Asset?): SeasonSyncBlockState {
        val held = transfers.heldIds()
        val here = asset != null && asset.maintainedHere(held)
        val writable = assetId !in held
        val connection = connections.get()
        if (binding == null) return SeasonSyncBlockState(offersLink = connection != null && here && writable)
        val derived = seasonSyncStateOf(binding, tokenHeld(binding.connectionId), here)
        val stateLine = when (derived) {
            SeasonSyncState.NEEDS_TOKEN -> HA_ENTER_TOKEN_AGAIN
            SeasonSyncState.NOT_MAINTAINED_HERE -> SEASON_SYNC_NOT_MAINTAINED_HERE
            SeasonSyncState.ACTIVE, SeasonSyncState.STOPPED -> null
        }
        if (!binding.enabled) {
            return SeasonSyncBlockState(
                kind = SeasonSyncBlockKind.STOPPED,
                mode = binding.mode,
                offersResume = writable,
                resumeOpensSheet = asset?.seasonMode != SeasonMode.MANUAL,
                stateLine = stateLine,
                stoppedLine = SEASON_SYNC_STOPPED,
            )
        }
        val paused = seasonSyncScheduleOf(connection, true, backgroundAllowed()) == SeasonSyncSchedule.PAUSED
        return SeasonSyncBlockState(
            kind = SeasonSyncBlockKind.ENABLED,
            mode = binding.mode,
            offersControls = writable && derived != SeasonSyncState.NOT_MAINTAINED_HERE,
            offersStop = writable,
            sourceLine = seasonSyncSourceLine(binding.mode, binding.entityId),
            lastSuccessLine = binding.lastSuccessAt?.let { seasonSyncLastSuccess(dateTime(Instant.ofEpochMilli(it))) }
                ?: SEASON_SYNC_NO_READING_YET,
            staleLine = SEASON_SYNC_NOT_CHECKED_IN_TIME.takeIf { stale(binding.lastSuccessAt, connection) },
            haChangedLine = binding.observedChangedAt?.let(::haTime)?.let(::seasonSyncChangedInHa),
            appliedLine = appliedLineOf(binding)?.let { line -> appliedLineText(line, day(binding.appliedOn)) },
            stateLine = stateLine,
            pausedLine = Notice(HA_BACKGROUND_PAUSED, NoticeAction.ALLOW_BACKGROUND_AGAIN).takeIf { paused },
            errorLines = binding.errorKind?.let { seasonSyncErrorNotices(it, binding.errorDetail, binding.entityId) }
                .orEmpty()
                .filterNot { it.text == stateLine },
        )
    }

    /**
     * The stale marker (C27, the runner's rule): no success yet, or the last one at least one cadence old, or dated
     * after now (a clock moved back). An attempt never counts.
     */
    private fun stale(lastSuccessAt: Long?, connection: HaConnection?): Boolean {
        val cadence = connection?.let { Duration.ofHours(it.cadence.hours).toMillis() } ?: return true
        val now = clock.nowMillis()
        return lastSuccessAt == null || now < lastSuccessAt || now - lastSuccessAt >= cadence
    }

    /** A key store that cannot answer reads as no token (B4's rule), never a crash. */
    private suspend fun tokenHeld(key: String): Boolean = try {
        secrets.has(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** The shipped date-and-time display (the backup screen's last-backup line), in this phone's zone. */
    private fun dateTime(at: Instant): String = DATE_TIME.format(at.atZone(zone()))

    /** HA's `last_changed`, shown only when it parses as ISO-8601 (P16-33). */
    private fun haTime(text: String): String? = runCatching { dateTime(OffsetDateTime.parse(text).toInstant()) }.getOrNull()

    /** The applied day in the shipped display date; a string the model would refuse shows verbatim. */
    private fun day(on: String?): String =
        on?.let { runCatching { displayDate(LocalDate.parse(it)) }.getOrDefault(it) }.orEmpty()

    private companion object {
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
    }
}
