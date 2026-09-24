package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.SeasonMode
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

    fun stored(id: String = "a1"): Asset = assets.rows.getValue(id)

    fun rows(assetId: String = "a1"): List<AssetCondition> =
        conditions.rows.values.filter { it.assetId == AssetId(assetId) }
}
