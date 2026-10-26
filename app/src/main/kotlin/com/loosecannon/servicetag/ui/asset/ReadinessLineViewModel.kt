package com.loosecannon.servicetag.ui.asset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.hapublish.BoundaryCache
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.haBoundaryOf
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.season.NotReadyReason
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.seasonReadiness
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.di.AppGraph
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * #114 (C33, F) — the season card's readiness lines as drawn: the readiness line ([line]), then the source line
 * ([sourceLine]), the "unverified since" disclosure under another source's line ([unverifiedLine], O-19) and the cutoff
 * line ([cutoffLine]). Every line null is nothing drawn: no role covers the asset and no date binding exists (R-N1).
 */
internal data class ReadinessLineState(
    val line: String? = null,
    val sourceLine: String? = null,
    val unverifiedLine: String? = null,
    val cutoffLine: String? = null,
)

/** #114 (C9a) — an asset's Season Start date binding and the boundary cache its delivery record carries. */
internal data class DateBindingRead(val binding: PublishBinding, val cache: BoundaryCache?)

/** #114 (C9a, R-N1) — the asset's date binding (a `SEASON_SERVICE_DATE` binding, enabled or not), or null. */
internal fun interface DateBindings {
    suspend fun of(assetId: AssetId): DateBindingRead?

    companion object {
        /**
         * No date binding. The graph passes this until B3's binding and delivery ports are wired to the card: no screen
         * or route can enable a date binding before B6a, so it is every reachable answer until then.
         */
        val NONE: DateBindings = DateBindings { null }
    }
}

/**
 * #114 (C33, C9a) — the season card's readiness, read now by C9 ([seasonReadiness]) over one `uow.read` snapshot, with
 * `today` from the shipped [Today], the clock's `now` for the Home Assistant boundary's freshness ([haBoundaryOf]: the
 * date binding's cache, the asset's mode and #16 binding, the connection's cadence) and an event's unresolvable zone
 * read in the phone's own. It is read again on every change to the asset, its schedules, groups, events, activations
 * and #16 binding, and on every resume ([onResumed]): the answer is never cached, because time alone moves it (a
 * season ends, a fresh boundary goes stale). Nothing here writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReadinessLineViewModel(
    private val assetId: AssetId,
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val groups: GroupRepository,
    private val events: EventRepository,
    private val activations: SeasonActivationRepository,
    private val seasonSync: SeasonSyncRepository,
    private val connections: HaConnectionRepository,
    private val dateBindings: DateBindings,
    private val uow: UnitOfWork,
    private val today: Today,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    constructor(graph: AppGraph, assetId: AssetId) : this(
        assetId, graph.assets, graph.schedules, graph.groups, graph.events, graph.seasonActivations,
        graph.seasonSyncBindings, graph.haConnections, DateBindings.NONE, graph.uow, graph.today, graph.clock,
    )

    private val refreshes = MutableStateFlow(0)

    private val changes: List<Flow<Any?>> = listOf(
        assets.observeAll(), schedules.observeAll(), groups.observeAll(), events.observeForAsset(assetId),
        activations.observeForAsset(assetId), seasonSync.observeFor(assetId), refreshes,
    )

    val state: StateFlow<ReadinessLineState> = combine(changes) { }
        .mapLatest { read() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReadinessLineState())

    /** Reads `today` and the clock again: the screen calls it on every resume. */
    fun onResumed() = refreshes.update { it + 1 }

    private suspend fun read(): ReadinessLineState {
        val day = today.localDate()
        val zone = zone()
        val now = clock.nowMillis()
        return uow.read {
            val asset = assets.get(assetId) ?: return@read ReadinessLineState()
            val own = events.forAsset(assetId)
            val ownActivations = activations.forAsset(assetId)
            val date = dateBindings.of(assetId)
            val connection = connections.get()
            val boundary = haBoundaryOf(
                cache = date?.cache,
                binding = date?.binding,
                assetMode = asset.seasonMode,
                inboundEnabled = seasonSync.get(assetId)?.enabled == true,
                // A new connection's cadence (C17); with no connection no #16 binding is enabled, so it decides nothing.
                cadence = connection?.cadence ?: SyncCadence.DAILY,
                newestActivationCreatedAt = ownActivations.maxOfOrNull { it.createdAt },
                now = now,
            )
            val readiness = seasonReadiness(
                asset, assets.all(), schedules.all(), groups.all(), own, ownActivations, day, zone, boundary,
            )
            readinessLinesOf(readiness, hasDateBinding = date != null, own, day, zone)
        }
    }
}

/**
 * The card's lines for C9's [readiness] (C33): the readiness line, and — whenever the answer carries its boundary — the
 * source line, the O-19 disclosure and the cutoff line. [events] are the asset's own; a `FUTURE_DATED` line names the
 * latest of the role schedule's counted services dated after [today], a `RECORDED_IN_ADVANCE` line the latest dated in
 * `[cutoff, today]` (every one of them written in advance, or C9 would have answered Ready) — the latest, as O-3 reads.
 */
internal fun readinessLinesOf(
    readiness: Readiness,
    hasDateBinding: Boolean,
    events: List<AssetEvent>,
    today: LocalDate,
    zone: ZoneId,
): ReadinessLineState {
    val boundary = when (readiness) {
        is Readiness.Ready -> readiness.boundary
        is Readiness.NotReady -> readiness.boundary
    }
    val evidenceOn = (readiness as? Readiness.NotReady)?.let { notReady ->
        val counted = events
            .filter { it.scheduleId == notReady.scheduleId && !it.detailsPending && !it.imported }
            .mapNotNull { dayOrNull(it.occurredOn) }
        when (notReady.reason) {
            NotReadyReason.FUTURE_DATED -> counted.filter { it > today }.maxOrNull()
            NotReadyReason.RECORDED_IN_ADVANCE ->
                notReady.boundary?.let { b -> counted.filter { it in b.cutoffOn..today }.maxOrNull() }
            NotReadyReason.NO_ROLE,
            NotReadyReason.ROLE_NOT_ALLOWED,
            NotReadyReason.TWO_ROLES,
            NotReadyReason.SCHEDULE_ARCHIVED,
            NotReadyReason.NOT_DONE_SINCE_SEASON_END,
            NotReadyReason.NOT_DONE_SINCE_COMMISSIONING,
            NotReadyReason.DETAILS_PENDING,
            NotReadyReason.INHERITED_SERVICE_ONLY,
            -> null
        }
    }
    val line = ReadinessStrings.line(readiness, hasDateBinding, evidenceOn) ?: return ReadinessLineState()
    return ReadinessLineState(
        line = line,
        sourceLine = boundary?.let { ReadinessStrings.sourceLine(it, zone) },
        unverifiedLine = boundary?.let { ReadinessStrings.unverifiedDisclosure(it, zone) },
        cutoffLine = boundary?.let { ReadinessStrings.cutoffLine(it) },
    )
}

private fun dayOrNull(iso: String): LocalDate? = try {
    LocalDate.parse(iso)
} catch (_: DateTimeException) {
    null
}
