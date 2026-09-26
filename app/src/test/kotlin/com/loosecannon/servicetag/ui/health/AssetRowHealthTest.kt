package com.loosecannon.servicetag.ui.health

import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.replacementOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #71 (plan E3): [AssetHealthReadModel.observeRowHealth], the Assets list's one reactive health
 * source — a view per asset in service, each the same view [AssetHealthReadModel.forAsset] gives,
 * re-derived on every signal it names, and never blocked or taken down by one asset.
 *
 * `T` is 2026-04-15 and the fixture thresholds are 0 / 40 / 75, so a replacement 10, 50 and 100
 * days back reads NOMINAL, WARNING and CRITICAL. Room answers on its own threads, so every wait is
 * real time, bounded, and names what it waited for; every signal is proved by what it changes.
 */
class AssetRowHealthTest {

    private val graph = FakeGraph().also { it.today = T }
    private val refreshes = MutableStateFlow(0)

    @After fun tearDown() = graph.close()

    // --- seeding ---------------------------------------------------------------------------------

    /** An in-service asset with one AGE subject whose replacement was [daysBack] days before `T`. */
    private suspend fun tracked(id: String, daysBack: Long, name: String = id) {
        graph.assets.upsert(assetRow(id, name = name))
        graph.events.upsert(replacementOf("e-$id", id, T.minusDays(daysBack).toString()))
        graph.healthSubjects.upsert(subjectRow("h-$id", id, name = "$name battery"))
    }

    /** An asset whose one MAINTENANCE_OVERDUE subject rides a schedule due 2026-01-01: 104 days late. */
    private suspend fun overdue(id: String) {
        graph.assets.upsert(assetRow(id))
        graph.schedules.upsert(scheduleOf("s-$id", assetId = id, title = "Engine oil service", anchorOn = "2026-01-01", leadDays = 0))
        graph.recomputeSchedules.forSchedule(ScheduleId("s-$id"))
        graph.healthSubjects.upsert(subjectRow("h-$id", id, name = "Oil", driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = "s-$id"))
    }

    // --- collecting ------------------------------------------------------------------------------

    /** Every emission of [model]'s row health, for the length of the test. */
    private fun TestScope.collect(model: AssetHealthReadModel = graph.assetHealthReadModel): StateFlow<List<Map<AssetId, AssetHealthView>>> {
        val seen = MutableStateFlow(emptyList<Map<AssetId, AssetHealthView>>())
        backgroundScope.launch { model.observeRowHealth(refreshes).collect { map -> seen.update { it + listOf(map) } } }
        return seen
    }

    private suspend fun <T> StateFlow<T>.await(what: String, until: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeoutOrNull(WAIT_MS) { first(until) } }
            ?: throw AssertionError("$what: never emitted; the last emission was $value")

    private suspend fun StateFlow<List<Map<AssetId, AssetHealthView>>>.latest(
        what: String,
        until: (Map<AssetId, AssetHealthView>) -> Boolean,
    ): Map<AssetId, AssetHealthView> = await(what) { it.isNotEmpty() && until(it.last()) }.last()

    private fun Map<AssetId, AssetHealthView>.band(id: String): HealthBand? = get(AssetId(id))?.result?.aggregate?.band

    // --- the cases -------------------------------------------------------------------------------

    /**
     * Every asset in service is covered — out of season too, since in service is the lifecycle (E1)
     * — tracked or not; a retired and an archived asset, each with a subject that would score, are
     * not. The bands are the read model's own.
     */
    @Test fun observeRowHealthCoversEveryInServiceTrackedAsset() = runTest {
        tracked("ups", daysBack = 10)
        tracked("gen", daysBack = 100)
        graph.assets.upsert(assetRow("fan", name = "Fan"))
        graph.assets.upsert(assetRow("mow", name = "Mower", seasonMode = SeasonMode.CALENDAR, seasonStart = "06-01", seasonEnd = "09-30"))
        graph.healthSubjects.upsert(subjectRow("h-mow", "mow", name = "Blade age"))
        tracked("old", daysBack = 10)
        graph.assets.upsert(assetRow("old", retiredOn = "2026-01-01"))
        tracked("gone", daysBack = 10)
        graph.assets.upsert(assetRow("gone", status = AssetStatus.ARCHIVED))

        val map = collect().latest("the in-service assets") { it.size == 4 }

        assertEquals(setOf("ups", "gen", "fan", "mow"), map.keys.map { it.value }.toSet())
        assertEquals(HealthBand.NOMINAL, map.band("ups"))
        assertEquals(HealthBand.CRITICAL, map.band("gen"))
        assertNull("in service, no subject: a view with no aggregate", map.band("fan"))
    }

    /**
     * The list never waits for a pass: the first emission is an empty map while the pass is still
     * blocked on its first read, and the views follow once it finishes.
     */
    @Test fun theFirstEmissionIsEmptyAndTheListNeverWaits() = runTest {
        tracked("ups", daysBack = 10)
        val gate = CompletableDeferred<Unit>()
        val gated = object : HealthSubjectRepository by graph.healthSubjects {
            override suspend fun forAsset(assetId: AssetId): List<HealthSubject> {
                gate.await()
                return graph.healthSubjects.forAsset(assetId)
            }
        }
        val seen = collect(modelWith(gated))

        seen.await("an empty first emission") { it.isNotEmpty() }
        assertEquals("the pass is still blocked", listOf(emptyMap<AssetId, AssetHealthView>()), seen.value)

        gate.complete(Unit)
        assertEquals(HealthBand.NOMINAL, seen.latest("the pass, once unblocked") { AssetId("ups") in it }.band("ups"))
    }

    /** One asset whose read throws is absent, logged, and every other asset is still listed. */
    @Test fun aFailingAssetIsAbsentAndTheOthersStay() = runTest {
        tracked("ups", daysBack = 10)
        tracked("bad", daysBack = 10)
        tracked("gen", daysBack = 100)
        val failing = object : HealthSubjectRepository by graph.healthSubjects {
            override suspend fun forAsset(assetId: AssetId): List<HealthSubject> {
                if (assetId == AssetId("bad")) throw IllegalStateException("a malformed merged row")
                return graph.healthSubjects.forAsset(assetId)
            }
        }

        val map = collect(modelWith(failing)).latest("the other two") { AssetId("ups") in it && AssetId("gen") in it }

        assertFalse("the failing asset is absent", AssetId("bad") in map)
        assertEquals(HealthBand.NOMINAL, map.band("ups"))
        assertEquals(HealthBand.CRITICAL, map.band("gen"))
    }

    /** A pass whose own table read fails leaves the list standing, and the next signal recovers. */
    @Test fun aFailingPassLeavesTheListStandingUntilTheNextSignal() = runTest {
        tracked("ups", daysBack = 10)
        var failures = 1
        val flaky = object : ConditionRepository by graph.conditions {
            override suspend fun all(): List<AssetCondition> {
                if (failures-- > 0) throw IllegalStateException("the table could not be read")
                return graph.conditions.all()
            }
        }
        val model = AssetHealthReadModel(
            graph.assets, graph.healthSubjects, graph.schedules, graph.scheduleStates, graph.events, graph.profiles,
            graph.seasonActivations, flaky, graph.recomputeSchedules, graph.todayPort, zone = { ZoneOffset.UTC },
        )
        val seen = collect(model)
        seen.await("the empty first emission and the failed pass's empty map") { it.size == 2 }
        assertEquals(listOf(emptyMap<AssetId, AssetHealthView>(), emptyMap()), seen.value)

        refreshes.update { it + 1 }

        assertEquals(HealthBand.NOMINAL, seen.latest("the next pass") { AssetId("ups") in it }.band("ups"))
    }

    /** A state row rewritten for today — no schedule write — moves the band. */
    @Test fun aScheduleStateChangeReemits() = runTest {
        overdue("gen")
        val seen = collect()
        seen.latest("104 days late") { it.band("gen") == HealthBand.CRITICAL }

        val state = graph.scheduleStates.get(ScheduleId("s-gen"))!!
        assertEquals("the row is today's, so it is the one read", T.toString(), state.computedForOn)
        graph.scheduleStates.upsert(state.copy(computedDueOn = "2026-04-10", effectiveDueOn = "2026-04-10", actionableDueOn = "2026-04-10"))

        seen.latest("5 days late, from the state row alone") { it.band("gen") == HealthBand.NOMINAL }
    }

    /** A schedule row written with no recompute — a postponement — moves the band. */
    @Test fun aScheduleRowChangeReemits() = runTest {
        overdue("gen")
        val seen = collect()
        seen.latest("104 days late") { it.band("gen") == HealthBand.CRITICAL }

        graph.schedules.upsert(graph.schedules.get(ScheduleId("s-gen"))!!.copy(postponedDueOn = "2026-04-20"))

        seen.latest("postponed, not late") { it.band("gen") == HealthBand.NOMINAL }
    }

    /** Archiving the only subject leaves the asset untracked: still in the map, with no aggregate. */
    @Test fun aSubjectArchiveReemits() = runTest {
        tracked("ups", daysBack = 10)
        val seen = collect()
        seen.latest("tracked") { it.band("ups") == HealthBand.NOMINAL }

        graph.healthSubjects.upsert(graph.healthSubjects.get(HealthSubjectId("h-ups"))!!.copy(archivedAt = dayMillis("2026-04-15")))

        val map = seen.latest("untracked") { AssetId("ups") in it && it.band("ups") == null }
        assertEquals("the view stays, so its condition can", AssetId("ups"), map.getValue(AssetId("ups")).assetId)
    }

    /** Moving the clock emits nothing on its own; a refresh re-derives on the new day. */
    @Test fun refreshReemits() = runTest {
        tracked("ups", daysBack = 10)
        val seen = collect()
        seen.latest("10 days") { it.band("ups") == HealthBand.NOMINAL }

        graph.today = T.plusDays(40)
        refreshes.update { it + 1 }
        seen.latest("50 days, after the refresh") { it.band("ups") == HealthBand.WARNING }

        graph.today = T.plusDays(90)
        refreshes.update { it + 1 }
        seen.latest("100 days, after the next") { it.band("ups") == HealthBand.CRITICAL }
    }

    /** An asset archived after the list opened leaves the map. */
    @Test fun anArchivedAssetLeavesTheMap() = runTest {
        tracked("ups", daysBack = 10)
        tracked("gen", daysBack = 100)
        val seen = collect()
        seen.latest("both") { it.size == 2 }

        graph.archiveAsset.run(AssetId("ups"))

        assertEquals(setOf(AssetId("gen")), seen.latest("ups gone") { AssetId("ups") !in it }.keys)
    }

    /** A condition recorded after the list opened reaches the view. */
    @Test fun aConditionChangeReemits() = runTest {
        tracked("ups", daysBack = 10)
        val seen = collect()
        seen.latest("no condition yet") { AssetId("ups") in it && it.getValue(AssetId("ups")).condition == null }

        graph.conditions.insert(conditionRow("c1", "ups", OperationalCondition.DOWN, "2026-04-14", reason = "Won't start"))

        val view = seen.latest("DOWN") { it[AssetId("ups")]?.condition?.condition == OperationalCondition.DOWN }.getValue(AssetId("ups"))
        assertEquals("Won't start", view.condition?.reason)
    }

    /**
     * One computation for every surface (inv. 82, 111): each view in the map equals `forAsset` for
     * the same asset on the same day — a CRITICAL subject behind a NOMINAL average, a DOWN condition
     * with a linked event, DOWN and DEGRADED components at two depths, a retired component, a
     * screened TRACK_ONE primary, an overdue schedule, and an asset with nothing at all.
     */
    @Test fun everyRowViewEqualsForAsset() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS", aggregation = HealthAggregation.AVERAGE))
        graph.events.upsert(replacementOf("e1", "ups", "2026-01-15"))
        graph.healthSubjects.upsert(subjectRow("h1", "ups", name = "Battery age", sortOrder = 0))
        graph.healthSubjects.upsert(subjectRow("h2", "ups", name = "Fan age", nominalUntilDays = 1000, warningFromDays = 2000, criticalFromDays = 3000, sortOrder = 1))
        graph.healthSubjects.upsert(subjectRow("h3", "ups", name = "Case age", nominalUntilDays = 1000, warningFromDays = 2000, criticalFromDays = 3000, sortOrder = 2))
        graph.conditions.insert(conditionRow("c1", "ups", OperationalCondition.DOWN, "2026-04-10", reason = "Won't hold", eventId = "e1"))
        graph.assets.upsert(assetRow("eng", name = "Engine", parent = "ups"))
        graph.conditions.insert(conditionRow("c2", "eng", OperationalCondition.DEGRADED, "2026-04-11"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "eng"))
        graph.conditions.insert(conditionRow("c3", "pack", OperationalCondition.DOWN, "2026-04-12", reason = "Swollen"))
        graph.assets.upsert(assetRow("spare", name = "Spare pack", parent = "ups", retiredOn = "2026-01-01"))
        graph.conditions.insert(conditionRow("c4", "spare", OperationalCondition.DOWN, "2026-04-12"))
        graph.assets.upsert(assetRow("tub", name = "Tub", aggregation = HealthAggregation.TRACK_ONE, primary = "h-bad"))
        graph.events.upsert(replacementOf("e2", "tub", "2026-01-15"))
        graph.healthSubjects.upsert(subjectRow("h-bad", "tub", name = "Heater age", nominalUntilDays = 50, warningFromDays = 40, criticalFromDays = 75))
        graph.healthSubjects.upsert(subjectRow("h-good", "tub", name = "Pump age", sortOrder = 1))
        overdue("gen")
        graph.assets.upsert(assetRow("shed", name = "Shed"))

        val map = collect().latest("every asset in service") { it.size == 6 }

        assertEquals(setOf("ups", "eng", "pack", "tub", "gen", "shed"), map.keys.map { it.value }.toSet())
        map.forEach { (id, view) -> assertEquals("the row's view of ${id.value}", graph.assetHealthReadModel.forAsset(id), view) }
        // The world really has the shapes the KDoc names.
        val ups = map.getValue(AssetId("ups"))
        assertEquals(HealthBand.NOMINAL, ups.result.aggregate?.band)
        assertEquals(listOf("h1"), ups.result.critical.map { it.subject.id.value })
        assertEquals(listOf("pack", "eng"), ups.components.map { it.assetId.value })
        assertEquals(true, ups.condition?.eventExists)
    }

    private fun modelWith(subjects: HealthSubjectRepository) = AssetHealthReadModel(
        graph.assets, subjects, graph.schedules, graph.scheduleStates, graph.events, graph.profiles,
        graph.seasonActivations, graph.conditions, graph.recomputeSchedules, graph.todayPort, zone = { ZoneOffset.UTC },
    )

    private companion object {
        val T: LocalDate = LocalDate.parse("2026-04-15")
        const val WAIT_MS = 5_000L
    }
}
