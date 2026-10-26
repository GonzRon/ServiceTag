package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.usecase.SeasonStartStrandCause
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * #114 (D4, C2, C11; row 110) — 409 `SEASON_START_STRANDED` on each hooked `/v1` route, over the production router and
 * `FakeGraph`: a change that would leave a live Season Start role where it may not stand answers the code, its
 * `problems` naming the `SeasonStartStrandCause` first and then each role schedule by id and title, `field` null —
 * and nothing is written. One case per hooked route and cause; the names are fictional.
 */
class SeasonStartStrandRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-10")
        today = LocalDate.parse("2026-02-10")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    // --- fixtures -------------------------------------------------------------------------------

    /** Every table a hooked route could write, as the set of its rows. */
    private fun snapshot(): Map<String, Set<Any>> = runBlocking {
        mapOf(
            "asset" to graph.assets.all().toSet(),
            "maintenance_schedule" to graph.schedules.all().toSet(),
            "schedule_state" to graph.scheduleStates.all().toSet(),
            "maintenance_group" to graph.groups.all().toSet(),
            "asset_season_activation" to graph.seasonActivations.all().toSet(),
            "asset_event" to graph.events.all().toSet(),
            "health_subject" to graph.healthSubjects.all().toSet(),
        )
    }

    /** [method] [path] [body] answers the strand for [cause], naming [schedules] as `(id, title)`; nothing moves. */
    private fun assertStranded(
        method: String,
        path: String,
        body: String,
        cause: SeasonStartStrandCause,
        assetId: String?,
        schedules: List<Pair<String, String>>,
    ) {
        graph.now += 86_400_000L
        val before = snapshot()
        val response = api.call(method, path, body)
        assertEquals("$method $path: ${response.bodyText()}", 409, response.status)
        val error = response.errorDetail()
        assertEquals("SEASON_START_STRANDED", error.code)
        assertNull(error.field)
        val first = "SeasonStartStrands(cause=${cause.name}" + (assetId?.let { ", assetId=$it" } ?: "") + ")"
        assertEquals(
            listOf(first) + schedules.map { (id, title) -> "StrandedSchedule(id=$id, title=$title)" },
            error.problems,
        )
        assertEquals("$method $path wrote nothing", before, snapshot())
    }

    private fun calendarAsset(name: String): String = api.asset(name).also { asset ->
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$asset/season-mode",
            """{"seasonMode":"CALENDAR","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}""",
        )
    }

    private fun manualAsset(name: String): String = api.asset(name).also { asset ->
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$asset/season-mode",
            """{"seasonMode":"MANUAL","manualPhase":"OUT_OF_SEASON"}""",
        )
    }

    private fun schedule(target: String, title: String = "Example annual service"): String = api.ok(
        ScheduleResponse.serializer(), "POST", "/v1/schedules",
        """{"title":"$title",$target,"timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01"}""",
        status = 201,
    ).schedule.id

    private fun role(schedule: String) = runBlocking {
        graph.setScheduleRole.run(ScheduleId(schedule), ScheduleRole.SEASON_START, "2026-01-01", quickElected = true)
    }

    // --- PATCH /v1/schedules/{id} ---------------------------------------------------------------

    @Test fun aRoleSchedulesRetargetIsStranded() {
        val heater = calendarAsset("Example Heater")
        val boiler = calendarAsset("Example Boiler")
        val service = schedule(""""targetAssetId":"$heater"""")
        role(service)
        assertStranded(
            "PATCH", "/v1/schedules/$service",
            """{"title":"Example annual service","targetAssetId":"$boiler","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01"}""",
            SeasonStartStrandCause.ROLE_SCHEDULE_RETARGET, heater, listOf(service to "Example annual service"),
        )
    }

    @Test fun aRoleSchedulesFormToQuickIsStranded() {
        val heater = calendarAsset("Example Heater")
        val reading = api.ok(
            DefinitionResponse.serializer(), "POST", "/v1/definitions",
            """{"assetId":"$heater","label":"Flue temperature","unit":"C","decimals":0}""",
        ).definition.id
        val profile = api.ok(
            ProfileResponse.serializer(), "POST", "/v1/profiles",
            """{"assetId":"$heater","name":"Example service form","eventKind":"MAINTENANCE","fields":[{"definitionId":"$reading","required":false}]}""",
        ).profile.id
        val service = schedule(""""targetAssetId":"$heater","completionMode":"FORM","profileId":"$profile"""")
        role(service)
        assertStranded(
            "PATCH", "/v1/schedules/$service",
            """{"title":"Example annual service","targetAssetId":"$heater","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01","completionMode":"QUICK","profileId":"$profile"}""",
            SeasonStartStrandCause.QUICK_NEEDS_ELECTION, heater, listOf(service to "Example annual service"),
        )
    }

    // --- POST /v1/assets/{id}/season-mode -------------------------------------------------------

    @Test fun aCoveredAssetsYearRoundIsStranded() {
        val heater = calendarAsset("Example Heater")
        val service = schedule(""""targetAssetId":"$heater"""")
        role(service)
        assertStranded(
            "POST", "/v1/assets/$heater/season-mode", """{"seasonMode":"YEAR_ROUND"}""",
            SeasonStartStrandCause.NEEDS_SEASONS, heater, listOf(service to "Example annual service"),
        )
    }

    // --- PATCH /v1/assets/{id} ------------------------------------------------------------------

    /** A covered top-level asset moved under another would be a child with no child of its own. */
    @Test fun aCoveredAssetsReparentIsStranded() {
        val heater = manualAsset("Example Heater")
        val boiler = api.asset("Example Boiler")
        val service = schedule(""""targetAssetId":"$heater"""")
        role(service)
        assertStranded(
            "PATCH", "/v1/assets/$heater", """{"name":"Example Heater","parentAssetId":"$boiler"}""",
            SeasonStartStrandCause.WOULD_BE_LEAF_CHILD, heater, listOf(service to "Example annual service"),
        )
    }

    /** The asset command's legacy pair sent empty turns a CALENDAR asset YEAR_ROUND, which a role forbids. */
    @Test fun aCoveredAssetsLegacyPairIsStranded() {
        val heater = calendarAsset("Example Heater")
        val service = schedule(""""targetAssetId":"$heater"""")
        role(service)
        assertStranded(
            "PATCH", "/v1/assets/$heater", """{"name":"Example Heater","seasonStartMmdd":null,"seasonEndMmdd":null}""",
            SeasonStartStrandCause.NEEDS_SEASONS, heater, listOf(service to "Example annual service"),
        )
    }

    // --- PATCH /v1/groups/{id} ------------------------------------------------------------------

    /** A member added to a role group must pass C10's rule and have no other role. */
    @Test fun aRoleGroupsNewMemberIsStranded() {
        val heater = calendarAsset("Example Heater")
        val group = api.ok(
            GroupResponse.serializer(), "POST", "/v1/groups",
            """{"name":"Example appliances","members":[{"assetId":"$heater"}]}""", status = 201,
        ).group
        val check = schedule(""""targetGroupId":"${group.id}"""", "Example pre-season check")
        role(check)
        val kept = """{"id":"${group.members.single().id}","assetId":"$heater"}"""

        val yearRound = api.asset("Example Boiler")
        assertStranded(
            "PATCH", "/v1/groups/${group.id}",
            """{"name":"Example appliances","members":[$kept,{"assetId":"$yearRound"}]}""",
            SeasonStartStrandCause.MEMBER_NOT_ELIGIBLE, yearRound, listOf(check to "Example pre-season check"),
        )

        val covered = calendarAsset("Example Boiler 2")
        val own = schedule(""""targetAssetId":"$covered"""", "Example annual service")
        role(own)
        assertStranded(
            "PATCH", "/v1/groups/${group.id}",
            """{"name":"Example appliances","members":[$kept,{"assetId":"$covered"}]}""",
            SeasonStartStrandCause.MEMBER_ALREADY_COVERED, covered,
            listOf(own to "Example annual service", check to "Example pre-season check"),
        )
    }
}
