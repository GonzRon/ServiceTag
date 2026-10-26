package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.season.SeasonReadinessReader
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.LinkScheduleSeason
import com.loosecannon.servicetag.core.seasonsync.OutboundTargets
import com.loosecannon.servicetag.core.seasonsync.RecordScheduleSeasonSyncResult
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRecorded
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncScheduler
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.UnlinkScheduleSeason
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryPublishBindingRepository
import com.loosecannon.servicetag.core.testing.InMemoryPublishDeliveryRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleSeasonActivationRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleSeasonBindingRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySecretStore
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.haConnectionOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #125 (H1) — one installation for a schedule's own season: [BackupInstall]'s stores plus the two schedule season
 * tables in one unit of work, the real recompute reading both activation tables, #124's opening-service reader, the
 * link, the unlink, the applier, the archive, the save and the completion, built as `AppGraph` will build them. `T`
 * and the clock move together ([on]). Every name and id is fictional; the zone is UTC.
 */
internal class ScheduleSeasonHarness(today: String = "2026-10-26") {
    val raw = BackupInstall()
    val bindings = InMemoryScheduleSeasonBindingRepository().also { raw.haConnections.cascadesTo(it::cascadeFromConnection) }
    val rows = InMemoryScheduleSeasonActivationRepository()
    val uow = FakeUnitOfWork(
        raw.assets, raw.groups, raw.tags, raw.links, raw.definitions, raw.profiles, raw.schedules, raw.closures,
        raw.events, raw.attachments, raw.references, raw.activations, raw.conditions, raw.subjects, raw.categories,
        raw.serviceCases, raw.caseEntries, raw.loans, raw.transfers, raw.successions, raw.supplyItems, raw.assetSupplies,
        raw.installedComponents, raw.seasonSyncBindings, raw.haConnections, bindings, rows,
    )
    val states = InMemoryScheduleStateRepository()
    private var seq = 0
    val ids = IdGenerator { "new-${++seq}" }
    var day: LocalDate = LocalDate.parse(today)
    var now: Long = dayMillis(today) + 36_000_000L
    val clock = Clock { now }
    val todayPort = Today { day }
    val secrets = InMemorySecretStore()

    /** Every `ensure` the commands asked of the runner, after their commit. */
    var ensured = 0
    val scheduler = object : SeasonSyncScheduler {
        override suspend fun ensure() {
            ensured++
        }

        override suspend fun cancel() = Unit

        override suspend fun requestFreshRead(assetId: AssetId) = Unit
    }

    /** The entities this phone writes, as the publishing side would answer C26. */
    val written = mutableSetOf<String>()
    val outbound = object : OutboundTargets by OutboundTargets.NONE {
        override suspend fun writes(connectionId: String, entityId: String): Boolean = entityId in written
    }

    val publishBindings = InMemoryPublishBindingRepository()
    val publishDeliveries = InMemoryPublishDeliveryRepository(publishBindings)

    /** #124 (C41): C9's opening-service day over these stores — the asset's readiness, never a schedule's season. */
    val openingServiceReader = SeasonReadinessReader(
        raw.assets, raw.schedules, raw.groups, raw.events, raw.activations, publishBindings, publishDeliveries,
        raw.seasonSyncBindings, raw.haConnections, todayPort, clock,
    ) { ZoneOffset.UTC }

    val recompute = RecomputeSchedules(
        raw.schedules, states, raw.events, raw.closures, raw.groups, raw.assets, raw.activations, todayPort, clock,
        openingServiceReader, rows,
    ) { ZoneOffset.UTC }

    val link = LinkScheduleSeason(
        raw.schedules, raw.assets, raw.transfers, bindings, raw.haConnections, secrets, recompute, scheduler, uow, clock,
        outbound,
    )
    val unlink = UnlinkScheduleSeason(raw.schedules, bindings, recompute, scheduler, uow, clock)
    val applier = RecordScheduleSeasonSyncResult(
        bindings, raw.schedules, raw.assets, rows, raw.transfers, recompute, uow, ids, clock, todayPort,
    )
    val archive = ArchiveSchedule(
        raw.schedules, uow, recompute, raw.subjects, raw.assets, clock, raw.groups, raw.transfers, bindings,
    )
    val saveSchedule =
        SaveSchedule(raw.schedules, raw.assets, raw.groups, raw.definitions, raw.profiles, uow, ids, clock, recompute, raw.subjects)
    val completeSchedule = CompleteSchedule(
        raw.schedules, raw.events, raw.definitions, raw.profiles, raw.supplyItems, uow, ids, clock, recompute, todayPort,
    )

    /** Moves `T` and the clock together, to mid-morning of [date]. */
    fun on(date: String) {
        day = LocalDate.parse(date)
        now = dayMillis(date) + 36_000_000L
    }

    /** An asset stored as it is. */
    fun asset(
        id: String = HEATER,
        mode: SeasonMode = SeasonMode.CALENDAR,
        start: String? = if (mode == SeasonMode.CALENDAR) "11-01" else null,
        end: String? = if (mode == SeasonMode.CALENDAR) "03-31" else null,
        breakStart: String? = null,
        breakEnd: String? = null,
    ): Asset = SeasonFixtures.assetOf(id, "Example Heater", mode, start, end, breakStart, breakEnd)
        .also { raw.assets.rows[id] = it }

    /** A schedule stored as it is, never done, QUICK; [anchorOn] is its first raw due. */
    fun schedule(
        id: String,
        assetId: String? = HEATER,
        groupId: String? = null,
        policy: ServicePolicy = ServicePolicy.CONTINUOUS,
        offset: Int? = null,
        every: Int = 1,
        unit: RecurrenceUnit = RecurrenceUnit.YEAR,
        anchorOn: String = "2026-01-15",
        role: ScheduleRole = ScheduleRole.NONE,
        since: String = "2026-04-01",
        status: ScheduleStatus = ScheduleStatus.ACTIVE,
        source: ScheduleSeasonSource = ScheduleSeasonSource.ASSET,
    ): MaintenanceSchedule = scheduleOf(
        id = id,
        assetId = if (groupId != null) null else assetId,
        groupId = groupId,
        title = "Example $id",
        timeInterval = every,
        timeUnit = unit,
        anchorOn = anchorOn,
        servicePolicy = policy,
        policyOffsetDays = offset,
        completionMode = CompletionMode.QUICK,
        status = status,
        ruleChangedOn = "2026-01-01",
    ).copy(
        role = role,
        roleSinceOn = since.takeIf { role == ScheduleRole.SEASON_START },
        seasonSource = source,
    ).also { raw.schedules.rows[it.id.value] = it }

    /** The fictional connection, with its token on this phone. */
    suspend fun connect() {
        raw.haConnections.upsert(haConnectionOf())
        secrets.put("conn-1", Secret("fictional-token-1"))
    }

    fun stored(id: String): MaintenanceSchedule = raw.schedules.rows.getValue(id)

    /** The schedule's state as every read path reads it today. */
    suspend fun state(id: String): ScheduleState = recompute.readState(stored(id))

    /** One Home Assistant answer for [id]'s binding at its current revision. */
    suspend fun answer(id: String, state: HaSwitchState): ScheduleSeasonRecorded =
        applier.run(
            ScheduleId(id), bindings.rows.getValue(id).revision, now - 20_000L, now - 10_000L,
            HaReadOutcome.Observed(state, "2026-10-20T06:00:00+00:00"),
        )

    suspend fun complete(id: String, on: String) {
        completeSchedule.run(ScheduleId(id), CompletionCommand(occurredOn = on, tzId = "UTC"))
    }

    companion object {
        const val HEATER = "heater"
        const val SWITCH = "input_boolean.example_heater_winterize_season"
    }
}
