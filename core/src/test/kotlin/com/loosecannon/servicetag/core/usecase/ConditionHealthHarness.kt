package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryConditionRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryHealthSubjectRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * B06's use cases over one set of in-memory stores, with the real recompute behind them. `T` is
 * [today] and the clock is [now]; both are `var`s a test moves by hand. [assets] counts every
 * upsert, so "no asset column" can be asserted as a count as well as a row. Every name is fictional
 * (spec F1–F5).
 */
internal class ConditionHealthHarness(today: String = "2026-09-24") {

    /** The asset table, counting writes: a condition write must make none. */
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
    val conditions = InMemoryConditionRepository()
    val healthSubjects = InMemoryHealthSubjectRepository()
    val closures = InMemoryClosureRepository()
    val states = InMemoryScheduleStateRepository()
    val schedules = InMemoryScheduleRepository(closures, states)
    val groups = InMemoryGroupRepository()
    val definitions = InMemoryDefinitionRepository()
    val profiles = InMemoryProfileRepository()
    val uow = FakeUnitOfWork(
        assets, events, activations, conditions, healthSubjects, closures, states, schedules, groups,
        definitions, profiles,
    )

    private var seq = 0
    val ids = IdGenerator { "id-%03d".format(++seq) }
    var now: Long = dayMillis(today) + 1_000L
    val clock = Clock { now }
    var today: LocalDate = LocalDate.parse(today)
    val todayPort = Today { this.today }

    val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, activations, todayPort, clock,
    ) { ZoneOffset.UTC }

    val recordCondition = RecordCondition(assets, events, conditions, uow, ids, clock, todayPort)
    val acceptOperationalOffer = AcceptOperationalOffer(conditions, recordCondition, uow)
    val logEvent = LogEvent(events, definitions, profiles, assets, uow, ids, clock, recompute)

    val saveHealthSubject = SaveHealthSubject(healthSubjects, assets, schedules, profiles, uow, ids, clock)
    val archiveHealthSubject = ArchiveHealthSubject(healthSubjects, assets, schedules, uow, clock)
    val setHealthPolicy = SetHealthPolicy(assets, healthSubjects, uow, clock)
    val saveSchedule =
        SaveSchedule(schedules, assets, groups, definitions, profiles, uow, ids, clock, recompute, healthSubjects)
    val archiveSchedule = ArchiveSchedule(schedules, uow, recompute, healthSubjects, assets, clock)
    val saveGroup = SaveGroup(groups, assets, uow, ids, clock)

    /** An asset stored as it is, with no command in between — the state a test starts from. */
    fun asset(
        id: String = "a1",
        name: String = "UPS $id",
        mode: SeasonMode = SeasonMode.YEAR_ROUND,
        seasonStart: String? = null,
        seasonEnd: String? = null,
        breakStart: String? = null,
        breakEnd: String? = null,
    ): Asset = SeasonFixtures.assetOf(
        id = id,
        name = name,
        mode = mode,
        seasonStart = seasonStart,
        seasonEnd = seasonEnd,
        breakStart = breakStart,
        breakEnd = breakEnd,
    ).also { assets.rows[it.id.value] = it }

    /** A condition row stored as it is — the history a test starts from. */
    fun condition(
        id: String,
        condition: OperationalCondition,
        on: String,
        time: String? = null,
        assetId: String = "a1",
        reason: String = "",
        createdAt: Long = dayMillis(on),
    ): AssetCondition = conditionOf(
        id = id,
        assetId = assetId,
        condition = condition,
        occurredOn = on,
        occurredTime = time,
        tzId = "UTC",
        reason = reason,
        createdAt = createdAt,
    ).also { conditions.rows[it.id] = it }

    /** A journal event stored as it is. */
    fun event(
        id: String,
        kind: EventKind,
        on: String,
        assetId: String = "a1",
        title: String = "Battery replaced",
    ): AssetEvent = HealthFixtures.eventOf(id, assetId, kind, title, on).also { events.rows[it.id.value] = it }

    /** A monthly FIXED schedule with a time rule, stored as it is. */
    fun schedule(
        id: String,
        assetId: String = "a1",
        title: String = "Battery test $id",
        policy: ServicePolicy = ServicePolicy.CONTINUOUS,
        offset: Int? = null,
        status: ScheduleStatus = ScheduleStatus.ACTIVE,
        timeRule: Boolean = true,
        meterDefinitionId: String? = null,
    ): MaintenanceSchedule = scheduleOf(
        id = id,
        assetId = assetId,
        title = title,
        timeInterval = 1.takeIf { timeRule },
        timeUnit = RecurrenceUnit.MONTH.takeIf { timeRule },
        anchorOn = "2026-01-15".takeIf { timeRule },
        meterDefinitionId = meterDefinitionId,
        meterInterval = meterDefinitionId?.let { 250.0 },
        servicePolicy = policy,
        policyOffsetDays = offset,
        status = status,
    ).also { schedules.rows[it.id.value] = it }

    /** A meter definition (engine hours) stored as it is. */
    fun meter(id: String = "d-hours", assetId: String = "a1"): MeasurementDefinition = MeasurementDefinition(
        id = DefinitionId(id), assetId = AssetId(assetId), key = "engine_hours", label = "Engine hours", unit = "h",
        valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null, isMeter = true,
        sortOrder = 0, archivedAt = null, createdAt = 0L, updatedAt = 0L,
    ).also { definitions.rows[it.id.value] = it }

    /** A quick action stored as it is. */
    fun profile(id: String, kind: EventKind, assetId: String = "a1", name: String = "Battery replaced"): EventProfile =
        EventProfile(
            id = ProfileId(id), assetId = AssetId(assetId), name = name, eventKind = kind, defaultTitle = name,
            templateKey = null, sortOrder = 0, archivedAt = null, createdAt = 0L, updatedAt = 0L,
            fields = emptyList(), consumables = emptyList(),
        ).also { profiles.rows[it.id.value] = it }

    /** A subject stored as it is — MAINTENANCE_OVERDUE on [scheduleId], or AGE without one. */
    fun subject(
        id: String,
        scheduleId: String? = null,
        assetId: String = "a1",
        name: String = "Battery $id",
        archivedAt: Long? = null,
        sortOrder: Int = 0,
    ): HealthSubject = HealthSubject(
        id = HealthSubjectId(id),
        assetId = AssetId(assetId),
        name = name,
        kind = HealthSubjectKind.PART,
        driver = if (scheduleId != null) HealthDriver.MAINTENANCE_OVERDUE else HealthDriver.AGE,
        scheduleId = scheduleId?.let(::ScheduleId),
        baselineProfileId = null,
        nominalUntilDays = 14,
        warningFromDays = 45,
        criticalFromDays = 120,
        weight = 1,
        sortOrder = sortOrder,
        archivedAt = archivedAt,
        createdAt = dayMillis("2026-01-01"),
        updatedAt = dayMillis("2026-01-01"),
    ).also { healthSubjects.rows[it.id.value] = it }

    /** [schedule] as the command that would store it: the editor's round trip. */
    fun commandOf(schedule: MaintenanceSchedule): ScheduleCommand = ScheduleCommand(
        targetAssetId = (schedule.target as? ScheduleTarget.AssetTarget)?.assetId,
        targetGroupId = (schedule.target as? ScheduleTarget.GroupTarget)?.groupId,
        title = schedule.title,
        description = schedule.description,
        timeInterval = schedule.timeInterval,
        timeUnit = schedule.timeUnit,
        timeBasis = schedule.timeBasis,
        anchorOn = schedule.anchorOn,
        leadDays = schedule.leadDays,
        meterDefinitionId = schedule.meterDefinitionId,
        meterInterval = schedule.meterInterval,
        anchorMeter = schedule.anchorMeter,
        meterLead = schedule.meterLead,
        servicePolicy = schedule.servicePolicy,
        policyOffsetDays = schedule.policyOffsetDays,
        completionMode = schedule.completionMode,
        profileId = schedule.profileId,
        remindersEnabled = schedule.remindersEnabled,
        providers = schedule.providers,
    )

    fun stored(id: String = "a1"): Asset = assets.rows.getValue(id)

    fun storedSchedule(id: String): MaintenanceSchedule = schedules.rows.getValue(id)

    fun storedSubject(id: String): HealthSubject = healthSubjects.rows.getValue(id)

    fun rows(assetId: String = "a1"): List<AssetCondition> =
        conditions.rows.values.filter { it.assetId == AssetId(assetId) }
}
