package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #124 (C44; row 152) — `IN_SERVICE_AFTER_OPENING_SERVICE` over `/v1`, through the production router and `FakeGraph`:
 * the schedule create and update bodies take it with its offset (omitted is 0), every schedule read carries it with
 * the legacy triple null and `policyReason` `AWAITING_OPENING_SERVICE` or `OPENING_SERVICE`, and the guards travel in
 * the shipped codes — `SEASON_START_REFUSED` with the new `reason` names, `SEASON_START_STRANDED` with the new `cause` —
 * writing nothing. Today is 2026-04-15, so "Example Heater", in season 1 April – 31 October, is in season; every name
 * is fictional.
 */
class OpeningServicePolicyRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-04-15") + 12 * 60 * 60 * 1000L
        today = LocalDate.parse("2026-04-15")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    // --- fixtures -------------------------------------------------------------------------------

    /** Every table a refused or stranded route could write, as the set of its rows. */
    private fun snapshot(): Map<String, Set<Any>> = runBlocking {
        mapOf(
            "asset" to graph.assets.all().toSet(),
            "maintenance_schedule" to graph.schedules.all().toSet(),
            "schedule_state" to graph.scheduleStates.all().toSet(),
            "maintenance_group" to graph.groups.all().toSet(),
            "asset_event" to graph.events.all().toSet(),
        )
    }

    private fun calendarAsset(name: String): String = api.asset(name).also { asset ->
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$asset/season-mode",
            """{"seasonMode":"CALENDAR","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}""",
        )
    }

    /** A quarterly schedule's body on [asset] from [anchorOn], with [policy] spliced in (`,"servicePolicy":…`). */
    private fun body(asset: String, title: String, policy: String = "", anchorOn: String = "2026-01-01"): String =
        """{"title":"$title","targetAssetId":"$asset","timeInterval":3,"timeUnit":"MONTH","anchorOn":"$anchorOn"$policy}"""

    private fun schedule(
        asset: String,
        title: String,
        policy: String = "",
        anchorOn: String = "2026-01-01",
    ): ScheduleRowResponse =
        api.ok(ScheduleResponse.serializer(), "POST", "/v1/schedules", body(asset, title, policy, anchorOn), status = 201).schedule

    /** "Example annual service" on [asset], made its Season Start service. */
    private fun seasonStartService(asset: String): String = schedule(asset, "Example annual service").id.also { id ->
        runBlocking { graph.setScheduleRole.run(ScheduleId(id), ScheduleRole.SEASON_START, "2026-01-01", quickElected = true) }
    }

    private fun detail(id: String): ScheduleDetailResponse =
        ApiJson.decodeFromString(ScheduleDetailResponse.serializer(), api.call("GET", "/v1/schedules/$id").bodyText())

    private fun assertLegacyTripleNull(row: ScheduleRowResponse) {
        assertNull(row.seasonBehavior)
        assertNull(row.seasonReentry)
        assertNull(row.seasonReentryOffsetDays)
    }

    /** [method] [path] [body] answers [status] [code] with [problems] and writes nothing. */
    private fun assertRefused(method: String, path: String, body: String, code: String, problems: List<String>) {
        graph.now += 86_400_000L
        val before = snapshot()
        val response = api.call(method, path, body)
        assertEquals("$method $path: ${response.bodyText()}", 409, response.status)
        val error = response.errorDetail()
        assertEquals(code, error.code)
        assertEquals(problems, error.problems)
        assertEquals("$method $path wrote nothing", before, snapshot())
    }

    // --- the value on the bodies and the reads --------------------------------------------------

    @Test fun createAndPatchCarryTheValueAndItsOffsetAndAReadSaysItWaits() {
        val heater = calendarAsset("Example Heater")
        seasonStartService(heater)

        val created = schedule(heater, "Example filter check", OPENING_7)
        assertEquals(OPENING, created.servicePolicy)
        assertEquals(7, created.policyOffsetDays)
        assertLegacyTripleNull(created)
        assertEquals("an omitted offset is 0", 0, schedule(heater, "Example flue check", OPENING_NO_OFFSET).policyOffsetDays)

        val patched = api.ok(
            ScheduleResponse.serializer(), "PATCH", "/v1/schedules/${created.id}",
            body(heater, "Example filter check", ""","servicePolicy":"$OPENING","policyOffsetDays":14"""),
        ).schedule
        assertEquals(OPENING, patched.servicePolicy)
        assertEquals(14, patched.policyOffsetDays)

        val read = detail(created.id)
        assertEquals(OPENING, read.schedule.servicePolicy)
        assertEquals(14, read.schedule.policyOffsetDays)
        assertLegacyTripleNull(read.schedule)
        assertEquals("AWAITING_OPENING_SERVICE", read.state.policyReason)
        assertEquals("DORMANT", read.state.policyPhase)
        assertNull("no service yet, so no date", read.state.actionableDueOn)
    }

    @Test fun aReadCarriesTheDateMovedToCountFromTheOpeningService() {
        val heater = calendarAsset("Example Heater")
        val service = seasonStartService(heater)
        // Due on 16 April by its own rule; the service of 10 April moves it to the 17th.
        val dependent = schedule(heater, "Example filter check", OPENING_7, anchorOn = "2026-04-16").id
        val completed = api.call("POST", "/v1/schedules/$service/complete", """{"occurredOn":"2026-04-10","tzId":"UTC"}""")
        assertEquals(completed.bodyText(), 201, completed.status)

        val state = detail(dependent).state
        assertEquals("2026-04-16", state.effectiveDueOn)
        assertEquals("OPENING_SERVICE", state.policyReason)
        assertEquals("ACTIVE", state.policyPhase)
        assertEquals("seven days after the service of 10 April", "2026-04-17", state.actionableDueOn)
    }

    // --- the refusals and the strand ------------------------------------------------------------

    @Test fun theRefusalsTravelAsSeasonStartRefusedReasons() {
        val heater = calendarAsset("Example Heater")
        val pump = api.asset("Example Pump")
        assertRefused(
            "POST", "/v1/schedules", body(heater, "Example filter check", OPENING_7), "SEASON_START_REFUSED",
            listOf("SeasonStartRefused(reason=NO_SEASON_START_SERVICE, assetId=$heater)"),
        )
        assertRefused(
            "POST", "/v1/schedules", body(pump, "Example pump check", OPENING_7), "SEASON_START_REFUSED",
            listOf("SeasonStartRefused(reason=YEAR_ROUND, assetId=$pump)"),
        )

        val service = seasonStartService(heater)
        assertRefused(
            "PATCH", "/v1/schedules/$service", body(heater, "Example annual service", OPENING_7), "SEASON_START_REFUSED",
            listOf("SeasonStartRefused(reason=COUNTS_FROM_ITSELF)"),
        )
        val dependent = schedule(heater, "Example filter check", OPENING_7).id
        assertRefused(
            "POST", "/v1/schedules/$dependent/role", """{"role":"SEASON_START"}""", "SEASON_START_REFUSED",
            listOf("SeasonStartRefused(reason=COUNTS_FROM_ITSELF)"),
        )
    }

    @Test fun theStrandTravelsAsHasDependentSchedules() {
        val heater = calendarAsset("Example Heater")
        val service = seasonStartService(heater)
        val dependent = schedule(heater, "Example filter check", OPENING_7).id
        val strand = listOf(
            "SeasonStartStrands(cause=HAS_DEPENDENT_SCHEDULES, assetId=$heater)",
            "StrandedSchedule(id=$dependent, title=Example filter check)",
        )
        assertRefused("POST", "/v1/schedules/$service/role", """{"role":"NONE"}""", "SEASON_START_STRANDED", strand)
        assertRefused("POST", "/v1/schedules/$service/archive", """{"archived":true}""", "SEASON_START_STRANDED", strand)
    }

    private companion object {
        const val OPENING = "IN_SERVICE_AFTER_OPENING_SERVICE"
        const val OPENING_7 = ""","servicePolicy":"$OPENING","policyOffsetDays":7"""
        const val OPENING_NO_OFFSET = ""","servicePolicy":"$OPENING""""
    }
}
