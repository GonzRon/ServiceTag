package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #114 (C10, C10a, C29b; O-7, O-2; row 113) — the Season Start role over `/v1`: `POST /v1/schedules/{id}/role` sets and
 * clears it through `SetScheduleRole`, answering the schedule row with `role` and `roleSinceOn`; each
 * `SeasonStartRefusal` is 409 `SEASON_START_REFUSED` by its name with nothing written, a malformed `sinceOn` 422
 * `SEASON_START_DATE_INVALID`; and the QUICK election is never defaulted — not on the role route, and not on the
 * schedule `PATCH`, whose `seasonStartQuickElected` flag passes it to `SaveSchedule`. Every name is fictional.
 */
class ScheduleRoleRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-10")
        today = LocalDate.parse("2026-02-10")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    // --- fixtures -------------------------------------------------------------------------------

    /** Every table the role route could reach, as the set of its rows. */
    private fun snapshot(): Map<String, Set<Any>> = runBlocking {
        mapOf(
            "asset" to graph.assets.all().toSet(),
            "maintenance_schedule" to graph.schedules.all().toSet(),
            "schedule_state" to graph.scheduleStates.all().toSet(),
            "maintenance_group" to graph.groups.all().toSet(),
            "asset_event" to graph.events.all().toSet(),
        )
    }

    private fun seasonal(asset: String) = api.ok(
        AssetSeasonResponse.serializer(), "POST", "/v1/assets/$asset/season-mode",
        """{"seasonMode":"CALENDAR","seasonStartMmdd":"10-01","seasonEndMmdd":"04-30"}""",
    )

    private fun calendarAsset(name: String): String = api.asset(name).also(::seasonal)

    private var forms = 0

    /** A fresh service form on [asset]: its own reading and profile, each named once per asset. */
    private fun profileOn(asset: String): String {
        forms += 1
        val reading = api.ok(
            DefinitionResponse.serializer(), "POST", "/v1/definitions",
            """{"assetId":"$asset","label":"Flue temperature $forms","unit":"C","decimals":0}""",
        ).definition.id
        return api.ok(
            ProfileResponse.serializer(), "POST", "/v1/profiles",
            """{"assetId":"$asset","name":"Example service form $forms","eventKind":"MAINTENANCE","fields":[{"definitionId":"$reading","required":false}]}""",
        ).profile.id
    }

    private fun schedule(target: String, title: String = "Example annual service", extra: String = ""): String = api.ok(
        ScheduleResponse.serializer(), "POST", "/v1/schedules",
        """{"title":"$title",$target,"timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01"$extra}""",
        status = 201,
    ).schedule.id

    private fun formSchedule(asset: String, title: String = "Example annual service"): String =
        schedule(""""targetAssetId":"$asset"""", title, ""","completionMode":"FORM","profileId":"${profileOn(asset)}"""")

    private fun role(schedule: String, body: String): ApiResponse = api.call("POST", "/v1/schedules/$schedule/role", body)

    private fun roleOk(schedule: String, body: String): ScheduleRowResponse {
        val response = role(schedule, body)
        assertEquals(response.bodyText(), 200, response.status)
        return ApiJson.decodeFromString(ScheduleResponse.serializer(), response.bodyText()).schedule
    }

    private fun group(name: String, members: List<String>): String = api.ok(
        GroupResponse.serializer(), "POST", "/v1/groups",
        """{"name":"$name","members":[${members.joinToString(",") { """{"assetId":"$it"}""" }}]}""", status = 201,
    ).group.id

    /** [schedule]'s role route with [body] is refused as [problem], and nothing moves. */
    private fun assertRefused(schedule: String, body: String, problem: String) {
        graph.now += 86_400_000L
        val before = snapshot()
        val response = role(schedule, body)
        assertEquals(response.bodyText(), 409, response.status)
        val error = response.errorDetail()
        assertEquals("SEASON_START_REFUSED", error.code)
        assertEquals(listOf(problem), error.problems)
        assertNull(error.field)
        assertEquals("$body wrote nothing", before, snapshot())
    }

    // --- set and clear ----------------------------------------------------------------------------

    @Test fun settingTheRoleAnswersTheRowWithRoleAndRoleSinceOn() {
        val heater = calendarAsset("Example Heater")
        val service = formSchedule(heater)
        val set = roleOk(service, """{"role":"SEASON_START","sinceOn":"2026-01-15"}""")
        assertEquals(service, set.id)
        assertEquals("SEASON_START", set.role)
        assertEquals("2026-01-15", set.roleSinceOn)
        // The read agrees: the row is stored as answered.
        val read = api.ok(ScheduleDetailResponse.serializer(), "GET", "/v1/schedules/$service").schedule
        assertEquals("SEASON_START" to "2026-01-15", read.role to read.roleSinceOn)
    }

    @Test fun anAbsentOrNullSinceOnIsToday() {
        val heater = calendarAsset("Example Heater")
        assertEquals("2026-02-10", roleOk(formSchedule(heater), """{"role":"SEASON_START"}""").roleSinceOn)
        val boiler = calendarAsset("Example Boiler")
        assertEquals("2026-02-10", roleOk(formSchedule(boiler), """{"role":"SEASON_START","sinceOn":null}""").roleSinceOn)
    }

    @Test fun clearingTheRoleAnswersNoneAndANoneOnANoneWritesNothing() {
        val heater = calendarAsset("Example Heater")
        val service = formSchedule(heater)
        roleOk(service, """{"role":"SEASON_START","sinceOn":"2026-01-15"}""")
        val cleared = roleOk(service, """{"role":"NONE"}""")
        assertEquals("NONE", cleared.role)
        assertNull(cleared.roleSinceOn)

        graph.now += 86_400_000L
        val before = snapshot()
        assertEquals("NONE", roleOk(service, """{"role":"NONE"}""").role)
        assertEquals("clearing a NONE wrote nothing", before, snapshot())
    }

    // --- refusals -----------------------------------------------------------------------------------

    @Test fun eachSeasonStartRefusalIs409ByItsNameWithNothingWritten() {
        // SCHEDULE_ARCHIVED
        val heater = calendarAsset("Example Heater")
        val archived = formSchedule(heater, "Example archived service")
        api.ok(ScheduleResponse.serializer(), "POST", "/v1/schedules/$archived/archive", """{"archived":true}""")
        assertRefused(archived, """{"role":"SEASON_START"}""", "SeasonStartRefused(reason=SCHEDULE_ARCHIVED)")

        // QUICK_NOT_ELECTED: a QUICK schedule with the flag absent, and with it false.
        val quick = schedule(""""targetAssetId":"$heater"""", "Example quick check")
        assertRefused(quick, """{"role":"SEASON_START"}""", "SeasonStartRefused(reason=QUICK_NOT_ELECTED)")
        assertRefused(
            quick, """{"role":"SEASON_START","seasonStartQuickElected":false}""",
            "SeasonStartRefused(reason=QUICK_NOT_ELECTED)",
        )

        // NOT_MAINTAINED_HERE
        val stored = calendarAsset("Example stored heater")
        val storedService = formSchedule(stored)
        api.ok(AssetResponse.serializer(), "POST", "/v1/assets/$stored/archive", """{"archived":true}""")
        assertRefused(
            storedService, """{"role":"SEASON_START"}""",
            "SeasonStartRefused(reason=NOT_MAINTAINED_HERE, assetId=$stored)",
        )

        // YEAR_ROUND
        val pump = api.asset("Example pump")
        assertRefused(formSchedule(pump), """{"role":"SEASON_START"}""", "SeasonStartRefused(reason=YEAR_ROUND, assetId=$pump)")

        // LEAF_CHILD
        val burner = api.ok(
            AssetResponse.serializer(), "POST", "/v1/assets/$heater/components", """{"name":"Example burner"}""", status = 201,
        ).asset.id
        seasonal(burner)
        assertRefused(formSchedule(burner), """{"role":"SEASON_START"}""", "SeasonStartRefused(reason=LEAF_CHILD, assetId=$burner)")

        // ALREADY_COVERED
        val annual = formSchedule(heater)
        roleOk(annual, """{"role":"SEASON_START","sinceOn":"2026-01-15"}""")
        assertRefused(
            formSchedule(heater, "Example second service"), """{"role":"SEASON_START"}""",
            "SeasonStartRefused(reason=ALREADY_COVERED, assetId=$heater, scheduleId=$annual, title=Example annual service)",
        )

        // GROUP_HAS_NO_MEMBERS (its one member soft-removed by the full-replace PATCH) and GROUP_ARCHIVED
        val spare = calendarAsset("Example spare heater")
        val emptied = group("Example emptied group", listOf(spare))
        val emptiedCheck = schedule(""""targetGroupId":"$emptied"""", "Example emptied check")
        api.ok(GroupResponse.serializer(), "PATCH", "/v1/groups/$emptied", """{"name":"Example emptied group","members":[]}""")
        assertRefused(
            emptiedCheck, """{"role":"SEASON_START","seasonStartQuickElected":true}""",
            "SeasonStartRefused(reason=GROUP_HAS_NO_MEMBERS)",
        )
        val boiler = calendarAsset("Example Boiler")
        val appliances = group("Example appliances", listOf(boiler))
        val check = schedule(""""targetGroupId":"$appliances"""", "Example pre-season check")
        api.ok(GroupResponse.serializer(), "POST", "/v1/groups/$appliances/archive", """{"archived":true}""")
        assertRefused(check, """{"role":"SEASON_START","seasonStartQuickElected":true}""", "SeasonStartRefused(reason=GROUP_ARCHIVED)")

        // DATE_IN_FUTURE
        assertRefused(
            formSchedule(boiler), """{"role":"SEASON_START","sinceOn":"2026-02-11"}""",
            "SeasonStartRefused(reason=DATE_IN_FUTURE)",
        )
    }

    @Test fun aQuickScheduleTakesTheRoleOnlyWithTheElectionAndEachDateMoveAsksAgain() {
        val heater = calendarAsset("Example Heater")
        val quick = schedule(""""targetAssetId":"$heater"""", "Example quick check")
        val set = roleOk(quick, """{"role":"SEASON_START","sinceOn":"2026-01-15","seasonStartQuickElected":true}""")
        assertEquals("SEASON_START" to "2026-01-15", set.role to set.roleSinceOn)
        // Moving an existing QUICK role's date is the election again (N-4).
        assertRefused(quick, """{"role":"SEASON_START","sinceOn":"2026-02-01"}""", "SeasonStartRefused(reason=QUICK_NOT_ELECTED)")
        assertEquals(
            "2026-02-01",
            roleOk(quick, """{"role":"SEASON_START","sinceOn":"2026-02-01","seasonStartQuickElected":true}""").roleSinceOn,
        )
    }

    @Test fun aBadSinceOnIs422SeasonStartDateInvalid() {
        val heater = calendarAsset("Example Heater")
        val service = formSchedule(heater)
        for (bad in listOf("2026-13-01", "15/01/2026", "2026-1-5", "")) {
            graph.now += 86_400_000L
            val before = snapshot()
            val response = role(service, """{"role":"SEASON_START","sinceOn":"$bad"}""")
            assertEquals(response.bodyText(), 422, response.status)
            val error = response.errorDetail()
            assertEquals("SEASON_START_DATE_INVALID", error.code)
            assertEquals("sinceOn", error.field)
            assertEquals(before, snapshot())
        }
    }

    @Test fun anUnknownRoleOrAnUnknownKeyIs400() {
        val service = formSchedule(calendarAsset("Example Heater"))
        for (body in listOf(
            """{"role":"READY"}""",
            """{"sinceOn":"2026-01-15"}""",
            """{"role":"SEASON_START","readyForSeason":true}""",
            """{"role":"SEASON_START","seasonServiceDoneOn":"2026-02-01"}""",
        )) {
            val response = role(service, body)
            assertEquals("$body: ${response.bodyText()}", 400, response.status)
        }
    }

    @Test fun noSuchScheduleIs404() {
        val response = role("no-such-schedule", """{"role":"SEASON_START"}""")
        assertEquals(404, response.status)
        assertEquals("NO_SUCH_SCHEDULE", response.errorDetail().code)
    }

    @Test fun anotherVerbIs404() {
        val service = formSchedule(calendarAsset("Example Heater"))
        for (method in listOf("GET", "PATCH", "DELETE")) {
            val response = api.call(method, "/v1/schedules/$service/role", if (method == "GET") "" else """{"role":"NONE"}""")
            assertEquals("$method: ${response.bodyText()}", 404, response.status)
        }
    }

    // --- the PATCH flag (C11, C29b) -----------------------------------------------------------------

    @Test fun aPatchWithSeasonStartQuickElectedPassesTheElection() {
        val heater = calendarAsset("Example Heater")
        val profile = profileOn(heater)
        val service = schedule(""""targetAssetId":"$heater"""", extra = ""","completionMode":"FORM","profileId":"$profile"""")
        roleOk(service, """{"role":"SEASON_START","sinceOn":"2026-01-15"}""")
        val quick =
            """"title":"Example annual service","targetAssetId":"$heater","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01","completionMode":"QUICK","profileId":"$profile""""

        for (flag in listOf("", ""","seasonStartQuickElected":false""")) {
            val refused = api.call("PATCH", "/v1/schedules/$service", "{$quick$flag}")
            assertEquals(refused.bodyText(), 409, refused.status)
            assertEquals("SEASON_START_STRANDED", refused.errorDetail().code)
            assertTrue(refused.errorDetail().problems.first().startsWith("SeasonStartStrands(cause=QUICK_NEEDS_ELECTION"))
            assertTrue("the message names the flag", "seasonStartQuickElected: true" in refused.errorDetail().message)
        }

        val saved = api.ok(ScheduleResponse.serializer(), "PATCH", "/v1/schedules/$service", """{$quick,"seasonStartQuickElected":true}""")
            .schedule
        assertEquals("QUICK", saved.completionMode)
        assertEquals("the role is kept" , "SEASON_START" to "2026-01-15", saved.role to saved.roleSinceOn)
    }

    // --- the contract (C29b; the carried rulings) ---------------------------------------------------

    @Test fun theContractDocumentsTheRouteTheFlagAndTheCodes() {
        val doc = repoFile("docs/api/v1.md").readText()
        val lines = doc.lines()
        assertEquals(1, lines.count { it.startsWith("| `POST` | `/v1/schedules/{id}/role` |") })
        assertTrue(lines.single { it.startsWith("| `PATCH` | `/v1/schedules/{id}` |") }.contains("`seasonStartQuickElected`"))
        val codes = doc.substringAfter("### The #113/#114 codes").substringBefore("\n## ")
        for (row in listOf(
            "| 409 | `SEASON_START_REFUSED` |", "| 422 | `SEASON_START_DATE_INVALID` | `sinceOn` |", "| 409 | `SEASON_START_STRANDED` |",
        )) {
            assertEquals(row, 1, codes.lines().count { it.startsWith(row) })
        }
        for (name in listOf(
            "QUICK_NOT_ELECTED", "SCHEDULE_ARCHIVED", "NOT_MAINTAINED_HERE", "YEAR_ROUND", "LEAF_CHILD", "ALREADY_COVERED",
            "GROUP_ARCHIVED", "GROUP_HAS_NO_MEMBERS", "DATE_IN_FUTURE", "RESTORE_NOT_ELIGIBLE", "RESTORE_ALREADY_COVERED",
        )) {
            assertTrue("the codes table names $name", "`$name`" in codes)
        }
        assertTrue("the strand's message names the flag", "`seasonStartQuickElected: true`" in doc)
    }

    @Test fun aCreateStillRefusesTheFlagAsAnUnknownKey() {
        val heater = calendarAsset("Example Heater")
        val response = api.call(
            "POST", "/v1/schedules",
            """{"title":"Example annual service","targetAssetId":"$heater","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01","seasonStartQuickElected":true}""",
        )
        assertEquals(response.bodyText(), 400, response.status)
    }
}
