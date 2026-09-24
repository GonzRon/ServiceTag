package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The season, break and activation use cases over one set of in-memory stores, with the real
 * recompute behind them, for B04's command tests. `T` is [today] and the clock is [now]; both are
 * `var`s a test moves by hand. [assets] counts every upsert, so "writes nothing" can be asserted as
 * a count as well as a row.
 */
internal class SeasonCommandHarness(today: String = "2026-06-10") {

    /** The asset table, counting writes: an activation or an unchanged command must write none. */
    class CountingAssets : InMemoryAssetRepository() {
        var upserts = 0
        override suspend fun upsert(asset: Asset) {
            upserts++
            super.upsert(asset)
        }
    }

    val assets = CountingAssets()
    val events = InMemoryEventRepository()
    val activations = InMemorySeasonActivationRepository()
    val closures = InMemoryClosureRepository()
    val states = InMemoryScheduleStateRepository()
    val schedules = InMemoryScheduleRepository(closures, states)
    val groups = InMemoryGroupRepository()
    val definitions = InMemoryDefinitionRepository()
    val profiles = InMemoryProfileRepository()
    val uow = FakeUnitOfWork(
        assets, events, activations, closures, states, schedules, groups, definitions, profiles,
    )

    private var seq = 0
    val ids = IdGenerator { "id-${++seq}" }
    var now: Long = dayMillis(today) + 1_000L
    val clock = Clock { now }
    var today: LocalDate = LocalDate.parse(today)
    val todayPort = Today { this.today }

    val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, activations, todayPort, clock,
    ) { ZoneOffset.UTC }

    val setSeasonMode = SetSeasonMode(assets, schedules, activations, uow, ids, clock, todayPort, recompute)
    val setMaintenanceBreak = SetMaintenanceBreak(assets, schedules, uow, clock, recompute)
    val recordSeasonActivation =
        RecordSeasonActivation(assets, events, activations, uow, ids, clock, todayPort, recompute)
    val getAssetSeason = GetAssetSeason(assets, activations, uow, todayPort)
    val acceptSeasonOffer = AcceptSeasonOffer(activations, recordSeasonActivation, uow, todayPort)
    val updateAsset = UpdateAsset(assets, schedules, uow, clock, recompute)
    val createAsset = CreateAsset(assets, uow, ids, clock, ApplyTemplate(definitions, profiles, assets, uow, ids, clock))
    val saveSchedule = SaveSchedule(schedules, assets, groups, definitions, profiles, uow, ids, clock, recompute)
    val logEvent = LogEvent(events, definitions, profiles, assets, uow, ids, clock, recompute)

    /** An asset stored as it is, with no command in between — the state a test starts from. */
    suspend fun asset(
        id: String = "a1",
        mode: SeasonMode = SeasonMode.YEAR_ROUND,
        seasonStart: String? = null,
        seasonEnd: String? = null,
        breakStart: String? = null,
        breakEnd: String? = null,
    ): Asset = SeasonFixtures.assetOf(
        id = id,
        name = "Asset $id",
        mode = mode,
        seasonStart = seasonStart,
        seasonEnd = seasonEnd,
        breakStart = breakStart,
        breakEnd = breakEnd,
    ).also { assets.rows[it.id.value] = it }

    /**
     * A monthly FIXED schedule stored as it is — the way a merge lands one, with no command refusing
     * it — so a boundary-less PRE_SERVICE row can exist to be repaired or stranded.
     */
    suspend fun schedule(
        id: String,
        assetId: String = "a1",
        policy: ServicePolicy = ServicePolicy.PRE_SERVICE,
        offset: Int? = if (policy == ServicePolicy.PRE_SERVICE) -14 else if (policy == ServicePolicy.IN_SERVICE_AT_START) 0 else null,
        status: ScheduleStatus = ScheduleStatus.ACTIVE,
        title: String = "Schedule $id",
        postponedDueOn: String? = null,
    ): MaintenanceSchedule = scheduleOf(
        id = id,
        assetId = assetId,
        title = title,
        timeInterval = 1,
        timeUnit = RecurrenceUnit.MONTH,
        anchorOn = "2026-01-15",
        leadDays = 7,
        servicePolicy = policy,
        policyOffsetDays = offset,
        status = status,
        postponedDueOn = postponedDueOn,
    ).also { schedules.rows[it.id.value] = it }

    /** A history row stored as it is. */
    fun activation(id: String, action: SeasonAction, on: String, assetId: String = "a1", createdAt: Long = dayMillis(on)) {
        activations.rows[id] = SeasonFixtures.activationOf(id, assetId, action, on, createdAt)
    }

    fun stored(id: String = "a1"): Asset = assets.rows.getValue(id)

    fun rows(assetId: String = "a1"): List<SeasonActivation> =
        activations.rows.values.filter { it.assetId == AssetId(assetId) }.sortedWith(ACTIVATION_ORDER)

    fun state(scheduleId: String): ScheduleState? = states.rows[ScheduleId(scheduleId).value]
}
