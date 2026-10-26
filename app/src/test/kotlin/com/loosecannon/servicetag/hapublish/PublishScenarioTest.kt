package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.hapublish.haBoundaryOf
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.season.HaBoundary
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.SeasonStartRefusal
import com.loosecannon.servicetag.core.usecase.SeasonStartRefused
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.DATE
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.ENDED
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.STATUS
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.dateHelper
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.statusHelper
import com.loosecannon.servicetag.seasonsync.HOME_WIFI
import com.loosecannon.servicetag.seasonsync.NetworkReading
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.RecordingPublishWork
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.scheduleOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertFailsWith

/**
 * #113/#114 (C32; rows 58, 59, 105) — end to end over the Room-backed graph, the real projector and the dispatch, with
 * a scripted Home Assistant: each recorded condition reaches the helper in order; a service date and its deletion
 * publish the date then `1970-01-01`; transitions made offline survive a process death and coalesce to one POST of
 * the latest; the home-network mode with background checks Off waits for the next open; and a year-round asset
 * publishes its status with no season and no boundary (G22). Every asset, helper, address and token is fictional.
 */
class PublishScenarioTest {

    private val rig = ScenarioRig()
    private val graph get() = rig.graph
    private val ha get() = rig.graph.haPublish

    @After fun close() = rig.close()

    // Row 58

    @Test
    fun upDegradedDownEachReachTheHelperInOrder() = rig.run {
        yearRound()
        connect()
        ha.helpers[STATUS] = statusHelper("DOWN")
        enable(PublishKind.EQUIPMENT_STATUS)
        val observer = graph.publishTriggers.observe()

        val sent = mapOf(
            OperationalCondition.OPERATIONAL to "UP",
            OperationalCondition.DEGRADED to "DEGRADED",
            OperationalCondition.DOWN to "DOWN",
        )
        for ((condition, value) in sent) {
            record(condition)
            eventually("$value reaches the helper") { ha.state(STATUS) == value }
        }
        observer.cancel()
        assertEquals(listOf("UP", "DEGRADED", "DOWN"), ha.posts.map { it.value })
    }

    @Test
    fun aServiceDateThenADeletionPublishesTheDateThen1970() = rig.run {
        calendar()
        role("2025-09-01")
        connect()
        ha.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        enable(PublishKind.SEASON_SERVICE_DATE)
        val observer = graph.publishTriggers.observe()

        val service = complete("2025-11-15")
        eventually("the service date reaches the helper") { ha.state(DATE) == "2025-11-15" }
        graph.deleteEvent.run(service.id)
        eventually("1970-01-01 reaches the helper") { ha.state(DATE) == INVALID_SERVICE_DATE }
        observer.cancel()
        assertEquals(listOf("2025-11-15", INVALID_SERVICE_DATE), ha.posts.map { it.value })
    }

    // Row 59

    @Test
    fun offlineTransitionsSurviveProcessDeathAndCoalesce() = rig.run {
        yearRound()
        connect(background = BackgroundChecks.ON)
        graph.backgroundAllowed = true
        ha.helpers[STATUS] = statusHelper("UP")
        val binding = enable(PublishKind.EQUIPMENT_STATUS)
        record(OperationalCondition.OPERATIONAL)
        val observer = graph.publishTriggers.observe()
        eventually("UP verified") { delivery(binding).lastVerifiedValue == "UP" }

        // Offline: every request fails before it reaches Home Assistant.
        ha.onRead = { RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) }
        record(OperationalCondition.DOWN)
        eventually("DOWN desired") { delivery(binding).desiredValue == "DOWN" }
        record(OperationalCondition.DEGRADED)
        eventually("DEGRADED desired") { delivery(binding).desiredValue == "DEGRADED" }
        eventually("the failed drain handed its work to the one-time work") {
            graph.publishWork.calls.contains(RecordingPublishWork.Call.EnqueueOnce)
        }
        graph.awaitDispatched()
        observer.cancel()
        assertTrue("nothing reached Home Assistant", ha.posts.isEmpty())

        // The process dies; Home Assistant still holds UP and answers again; the one-time work runs.
        val rebuilt = rebuild()
        rebuilt.backgroundAllowed = true
        assertEquals("DEGRADED", rebuilt.publishDeliveries.get(binding.id)!!.desiredValue)
        assertEquals(PublishWorkResult.SUCCESS, PublishWorkerBody(rebuilt.publishDispatch).once(0))

        assertEquals(listOf("DEGRADED"), rebuilt.haPublish.posts.map { it.value })
        assertEquals("DEGRADED", rebuilt.haPublish.state(STATUS))
    }

    @Test
    fun homeWifiOnlyWithBackgroundOffWaitsForResume() = rig.run {
        yearRound()
        connect() // the home-network mode, background checks Off: FOREGROUND_ONLY
        ha.helpers[STATUS] = statusHelper("UP")
        val binding = enable(PublishKind.EQUIPMENT_STATUS)
        record(OperationalCondition.OPERATIONAL)
        graph.publishProjector.reconcileDesired(graph.today)
        graph.publishRunner.publishNow(heater)
        assertEquals("UP", delivery(binding).lastVerifiedValue)
        ha.log.clear()

        graph.currentNetwork = NetworkReading(CurrentNetwork.Wifi("ExampleCafeWifi"), null)
        val observer = graph.publishTriggers.observe()
        record(OperationalCondition.DOWN)
        eventually("DOWN desired") { delivery(binding).desiredValue == "DOWN" }
        eventually("the projection asked for delivery") { graph.deliveryRequests.get() > 0 }
        graph.awaitDispatched()
        observer.cancel()
        assertTrue("nothing requested away from home", ha.log.isEmpty())
        assertTrue("nothing queued in the background", RecordingPublishWork.Call.EnqueueOnce !in graph.publishWork.calls)
        assertEquals("UP", ha.state(STATUS))

        graph.currentNetwork = NetworkReading(CurrentNetwork.Wifi(HOME_WIFI), null)
        graph.publishTriggers.resumePass()
        assertEquals("DOWN", ha.state(STATUS))
        assertEquals(listOf("POST $STATUS=DOWN"), ha.log.filter { it.startsWith("POST") })
    }

    /**
     * C30's rule on the triggers that do not depend on a flow (the controller's addition to B4b2): away from the home
     * Wi-Fi, the start, resume and midnight passes send nothing in process; under FOREGROUND_ONLY nothing is queued
     * either, and under PERIODIC the one-time work takes the delivery.
     */
    @Test
    fun startResumeAndMidnightAwayFromHomeSendNothing() = rig.run {
        yearRound()
        connect() // FOREGROUND_ONLY
        ha.helpers[STATUS] = statusHelper("UP")
        val binding = enable(PublishKind.EQUIPMENT_STATUS)
        record(OperationalCondition.OPERATIONAL)
        graph.publishProjector.reconcileDesired(graph.today)
        graph.publishRunner.publishNow(heater)
        assertEquals("UP", delivery(binding).lastVerifiedValue)
        ha.helpers[STATUS] = statusHelper("DOWN") // Home Assistant reset
        ha.log.clear()
        graph.currentNetwork = NetworkReading(CurrentNetwork.Wifi("ExampleCafeWifi"), null)

        graph.publishTriggers.startPass()
        graph.publishTriggers.resumePass()
        graph.publishTriggers.midnightPass()
        assertTrue("nothing requested away from home", ha.log.isEmpty())
        assertTrue(RecordingPublishWork.Call.EnqueueOnce !in graph.publishWork.calls)

        connect(background = BackgroundChecks.ON)
        graph.backgroundAllowed = true // PERIODIC
        graph.publishTriggers.startPass()
        assertTrue("still nothing in process", ha.log.isEmpty())
        assertTrue("the one-time work takes it", RecordingPublishWork.Call.EnqueueOnce in graph.publishWork.calls)

        graph.currentNetwork = NetworkReading(CurrentNetwork.Wifi(HOME_WIFI), null)
        graph.publishTriggers.startPass()
        assertEquals("UP", ha.state(STATUS))
    }

    // Row 105 — story 4: a year-round indoor pool

    @Test
    fun aYearRoundPoolPublishesItsStatusWithNoSeasonAndNoBoundary() = rig.run {
        val pool = AssetId("example-indoor-pool")
        graph.assets.upsert(assetRow(pool.value, "Example Indoor Pool"))
        val check = ScheduleId("example-pool-check")
        graph.uow.write { graph.schedules.upsert(scheduleOf(check.value, assetId = pool.value, title = "Example pool check")) }
        val refused = assertFailsWith<SeasonStartRefused> {
            graph.setScheduleRole.run(check, ScheduleRole.SEASON_START, "2026-01-01", quickElected = true)
        }
        assertEquals(SeasonStartRefusal.YearRound(pool), refused.reason)

        connect()
        ha.helpers[POOL_STATUS] = statusHelper("UP").copy(entityId = POOL_STATUS)
        graph.enablePublishing.run(
            pool, PublishKind.EQUIPMENT_STATUS, POOL_STATUS, fresh(POOL_STATUS, PublishKind.EQUIPMENT_STATUS, pool), null,
            graph.installationIdentity.id(),
        )
        val observer = graph.publishTriggers.observe()
        record(OperationalCondition.DOWN, pool)
        eventually("DOWN reaches the pool's helper") { ha.state(POOL_STATUS) == "DOWN" }
        record(OperationalCondition.OPERATIONAL, pool)
        eventually("UP reaches the pool's helper") { ha.state(POOL_STATUS) == "UP" }
        observer.cancel()
        assertEquals(listOf("DOWN", "UP"), ha.posts.map { it.value })

        // No date binding is offered a boundary: a year-round asset stores none and its boundary is not used.
        ha.helpers[POOL_DATE] = dateHelper(POOL_DATE, INVALID_SERVICE_DATE)
        ha.helpers[ENDED] = dateHelper(ENDED, "2026-05-10")
        val date = graph.enablePublishing.run(
            pool, PublishKind.SEASON_SERVICE_DATE, POOL_DATE, fresh(POOL_DATE, PublishKind.SEASON_SERVICE_DATE, pool),
            fresh(ENDED, PublishKind.SEASON_SERVICE_DATE, pool), graph.installationIdentity.id(),
        )
        assertNull(date.boundaryEntityId)
        assertEquals(
            HaBoundary.NotUsed,
            haBoundaryOf(null, date, SeasonMode.YEAR_ROUND, inboundEnabled = true, SyncCadence.DAILY, null, graph.now),
        )
    }

    private companion object {
        const val POOL_STATUS = "input_select.example_indoor_pool_equipment_status"
        const val POOL_DATE = "input_datetime.example_indoor_pool_service_done"
    }
}

/**
 * The scenarios' fixture: one Room-backed graph with its real projector and the dispatch, the fictional "Example
 * Heater", its "Example annual service" schedule, the fictional connection, and helpers Home Assistant holds in the
 * graph's script. Setup runs the commands directly; what a row proves runs through its own path.
 */
internal class ScenarioRig {
    var graph = FakeGraph()
        private set
    private val graphs = mutableListOf(graph)
    val heater = AssetId("example-heater")
    val service = ScheduleId("example-annual-service")

    fun close() = graphs.forEach { it.close() }

    fun run(block: suspend ScenarioRig.() -> Unit) = runBlocking<Unit> {
        on("2026-02-10")
        withTimeout(40_000) { block() }
    }

    /** Today and the clock, both at [hour] on [date] (UTC). */
    fun on(date: String, hour: Int = 9) {
        graph.today = LocalDate.parse(date)
        graph.now = dayMillis(date) + hour * HOUR
    }

    suspend fun yearRound() = graph.assets.upsert(assetRow(heater.value, "Example Heater"))

    /** In season 1 April – 31 October. */
    suspend fun calendar() = graph.assets.upsert(
        assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-01", seasonEnd = "10-31"),
    )

    suspend fun role(sinceOn: String) = graph.uow.write {
        graph.schedules.upsert(
            scheduleOf(service.value, assetId = heater.value, title = "Example annual service", anchorOn = "2025-06-01")
                .copy(role = ScheduleRole.SEASON_START, roleSinceOn = sinceOn),
        )
    }

    /** The service recorded on its own day (O-16's write-day rule), then the clock put back. */
    suspend fun complete(serviceOn: String): AssetEvent {
        val (day, now) = graph.today to graph.now
        on(serviceOn, 12)
        return try {
            graph.completeSchedule.run(service, CompletionCommand(occurredOn = serviceOn, tzId = "UTC"))
        } finally {
            graph.today = day
            graph.now = now
        }
    }

    suspend fun record(condition: OperationalCondition, assetId: AssetId = heater): AssetCondition {
        graph.now += 1_000
        return graph.recordCondition.run(assetId, ConditionCommand(condition, tzId = "UTC"))
    }

    /** The fictional connection: the home-network mode by default, background checks as given (Off when null). */
    suspend fun connect(
        eligibility: NetworkEligibility = NetworkEligibility.HOME_NETWORK_ONLY,
        background: BackgroundChecks? = null,
        token: String = "fictional-token-1",
    ): HaConnection = graph.saveHaConnection.run(ADDRESS, Secret(token), null, eligibility, HOME_WIFI, background)

    suspend fun fresh(entityId: String, kind: PublishKind, assetId: AssetId = heater): ValidationResult {
        val connection = graph.haConnections.get()!!
        val revision = graph.publishBindings.forAsset(assetId).firstOrNull { it.kind == kind }?.revision ?: 0
        return ValidationResult(
            entityId, graph.haPublish.helpers.getValue(entityId), connection.id, connection.configGeneration, revision, graph.now,
        )
    }

    /** Enabled through B3's command on the heater's usual helper; [boundary] names a season-ended helper. */
    suspend fun enable(kind: PublishKind, entityId: String = targetOf(kind), boundary: String? = null): PublishBinding =
        graph.enablePublishing.run(
            heater, kind, entityId, fresh(entityId, kind), boundary?.let { fresh(it, PublishKind.SEASON_SERVICE_DATE) },
            graph.installationIdentity.id(),
        )

    suspend fun delivery(binding: PublishBinding): PublishDelivery = graph.publishDeliveries.get(binding.id)!!

    suspend fun eventually(what: String, check: suspend () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!check()) {
            check(System.currentTimeMillis() < until) { "never: $what" }
            delay(20)
        }
    }

    /** The process dies: the same database, token store and installation in a new graph; Home Assistant unchanged. */
    fun rebuild(): FakeGraph {
        val before = graph
        val after = FakeGraph(db = before.db, secretStore = before.secretStore, installationDir = before.installationDir)
        after.haPublish.helpers.putAll(before.haPublish.helpers)
        after.today = before.today
        after.now = before.now
        graph = after
        graphs += after
        return after
    }

    companion object {
        const val ADDRESS = "http://192.168.0.10:8123"
        const val HOUR = 60L * 60 * 1000

        fun targetOf(kind: PublishKind): String = when (kind) {
            PublishKind.EQUIPMENT_STATUS -> STATUS
            PublishKind.SEASON_SERVICE_DATE -> DATE
        }
    }
}
