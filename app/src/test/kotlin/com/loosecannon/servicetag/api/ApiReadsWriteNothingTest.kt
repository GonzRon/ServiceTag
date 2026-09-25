package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 1.4 (B09) — the API half of invariants 105 and 86 (spec §4.7; master plan §8.6, §14): **no read
 * route writes**, not even over derived state stored before a season or break boundary; and **each
 * write route writes only its own use case's tables** — a season write touches no condition, a
 * condition write touches no asset row, and so on.
 *
 * The proof is table by table: every table a route could reach is read through its repository
 * before and after the call, and the set of tables whose rows changed is the assertion.
 */
class ApiReadsWriteNothingTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-01-20")
        today = LocalDate.parse("2026-01-20")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    /** Every table a 1.4 route could write, by its SQL name, as the set of its rows. */
    private fun snapshot(): Map<String, Set<Any>> = runBlocking {
        mapOf(
            "asset" to graph.assets.all().toSet(),
            "maintenance_schedule" to graph.schedules.all().toSet(),
            "schedule_state" to graph.scheduleStates.all().toSet(),
            "asset_event" to graph.events.all().toSet(),
            "asset_condition" to graph.conditions.all().toSet(),
            "asset_season_activation" to graph.seasonActivations.all().toSet(),
            "health_subject" to graph.healthSubjects.all().toSet(),
            "occurrence_closure" to graph.closures.all().toSet(),
            "maintenance_group" to graph.groups.all().toSet(),
            "measurement_definition" to graph.definitions.all().toSet(),
            "event_profile" to graph.profiles.all().toSet(),
        )
    }

    /** The tables [call] changed. The clock moves first, so a rewrite of an identical row still shows. */
    private fun tablesWrittenBy(call: () -> ApiResponse): Set<String> {
        graph.now += 86_400_000L
        val before = snapshot()
        val response = call()
        assertTrue(response.bodyText(), response.status in 200..299)
        val after = snapshot()
        return before.keys.filter { before[it] != after[it] }.toSet()
    }

    private fun schedule(asset: String, title: String, season: String = ""): String {
        val tail = if (season.isEmpty()) "" else ",$season"
        return api.ok(
            ScheduleResponse.serializer(), "POST", "/v1/schedules",
            """{"title":"$title","targetAssetId":"$asset","timeInterval":1,"timeUnit":"MONTH","anchorOn":"2026-02-05"$tail}""",
            status = 201,
        ).schedule.id
    }

    private fun subjectBody(asset: String, name: String, driver: String = "AGE", schedule: String? = null): String {
        val link = schedule?.let { ""","scheduleId":"$it"""" } ?: ""
        return """{"assetId":"$asset","name":"$name","kind":"PART","driver":"$driver"$link,
            "nominalUntilDays":0,"warningFromDays":30,"criticalFromDays":60}"""
    }

    /**
     * Inv. 105 at the wire. Everything is built on 20 January; then it is 10 February — inside the
     * generator's maintenance break, which moves its held job's actionable date — so every stored
     * `schedule_state` row is stale. The five read routes the brief names, and the other 1.4 reads,
     * answer for today and **write nothing**: every table, the stale state rows included, is exactly
     * as it was.
     */
    @Test fun theReadRoutesWriteNothingOverStaleState() {
        val generator = api.asset("Generator")
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$generator/maintenance-break",
            """{"blackoutStartMmdd":"02-01","blackoutEndMmdd":"02-28"}""",
        )
        val held = schedule(generator, "Load bank test", """"servicePolicy":"IN_SERVICE_AT_START"""")
        api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(generator, "Oil", "MAINTENANCE_OVERDUE", held), status = 201)
        api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(generator, "Belt"), status = 201)
        api.ok(ConditionResponse.serializer(), "POST", "/v1/assets/$generator/conditions", """{"condition":"DEGRADED","tzId":"UTC"}""", status = 201)
        val tub = api.asset("Hot tub")
        api.ok(AssetSeasonResponse.serializer(), "POST", "/v1/assets/$tub/season-mode", """{"seasonMode":"MANUAL","manualPhase":"IN_SEASON"}""")
        schedule(tub, "Water change", """"servicePolicy":"IN_SERVICE_RESUME_CLAMPED"""")

        graph.today = LocalDate.parse("2026-02-10")
        graph.now = dayMillis("2026-02-10")
        val before = snapshot()
        assertTrue("the stored state is stale", runBlocking { graph.scheduleStates.all() }.all { it.computedForOn == "2026-01-20" })

        for (path in listOf(
            "/v1/due",
            "/v1/attention",
            "/v1/assets/$generator/health",
            "/v1/schedules/$held",
            "/v1/assets/$generator/season",
            "/v1/assets/$tub/season",
            "/v1/assets/$generator/conditions",
            "/v1/assets/$generator/health-subjects",
            "/v1/schedules",
            "/v1/status",
        )) {
            val response = api.call("GET", path)
            assertEquals("$path: ${response.bodyText()}", 200, response.status)
            assertEquals(path, before, snapshot())
        }
        // And what the reads answered was today's derivation, not the stored row.
        val detail = api.ok(ScheduleDetailResponse.serializer(), "GET", "/v1/schedules/$held")
        assertEquals("2026-02-10", detail.computedForOn)
        assertEquals("2026-03-01", detail.state.actionableDueOn)
        assertEquals("DEFERRED", detail.status)
    }

    /**
     * Inv. 86 and 81 at the wire: each 1.4 write route, and the widened schedule writes, change
     * exactly the tables their one use case owns — a season write never records a condition, a
     * condition never stamps its asset, a subject never touches a schedule — and the recompute's
     * `schedule_state` only where a season, break, activation or schedule moved.
     */
    @Test fun eachWriteRouteWritesOnlyItsUseCasesTables() {
        graph.today = LocalDate.parse("2026-02-10")
        val generator = api.asset("Generator")
        val tub = api.asset("Hot tub")
        val oilChange = schedule(generator, "Oil change")
        schedule(tub, "Water change")

        val expectations = listOf<Pair<Set<String>, () -> ApiResponse>>(
            setOf("asset", "schedule_state") to {
                api.call("POST", "/v1/assets/$generator/season-mode", """{"seasonMode":"CALENDAR","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}""")
            },
            setOf("asset", "schedule_state") to {
                api.call("POST", "/v1/assets/$generator/maintenance-break", """{"blackoutStartMmdd":"07-01","blackoutEndMmdd":"07-31"}""")
            },
            setOf("asset", "asset_season_activation", "schedule_state") to {
                api.call("POST", "/v1/assets/$tub/season-mode", """{"seasonMode":"MANUAL","manualPhase":"IN_SEASON"}""")
            },
            setOf("asset_season_activation", "schedule_state") to {
                api.call("POST", "/v1/assets/$tub/season", """{"action":"END"}""")
            },
            setOf("asset_condition") to {
                api.call("POST", "/v1/assets/$generator/conditions", """{"condition":"DOWN","tzId":"UTC","reason":"Will not start"}""")
            },
            setOf("health_subject") to {
                api.call("POST", "/v1/health-subjects", subjectBody(generator, "Starter battery"))
            },
        )
        for ((expected, write) in expectations) assertEquals(expected, tablesWrittenBy(write))

        val battery = runBlocking { graph.healthSubjects.all() }.single().id.value
        assertEquals(
            setOf("health_subject"),
            tablesWrittenBy {
                api.call(
                    "PATCH", "/v1/health-subjects/$battery",
                    """{"name":"Starter battery (12V)","kind":"PART","driver":"AGE","nominalUntilDays":0,"warningFromDays":30,"criticalFromDays":60}""",
                )
            },
        )
        assertEquals(
            setOf("asset"),
            tablesWrittenBy {
                api.call("POST", "/v1/assets/$generator/health-policy", """{"healthAggregation":"TRACK_ONE","healthPrimarySubjectId":"$battery"}""")
            },
        )
        val belt = api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(generator, "Belt"), status = 201).subject.id
        assertEquals(setOf("health_subject"), tablesWrittenBy { api.call("POST", "/v1/health-subjects/$belt/archive", """{"archived":true}""") })

        // The widened schedule writes: the flag archives the driven subject in the same write.
        api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(generator, "Oil", "MAINTENANCE_OVERDUE", oilChange), status = 201)
        assertEquals(
            setOf("maintenance_schedule", "health_subject", "schedule_state"),
            tablesWrittenBy {
                api.call(
                    "PATCH", "/v1/schedules/$oilChange",
                    """{"title":"Oil change","targetAssetId":"$tub","timeInterval":1,"timeUnit":"MONTH","anchorOn":"2026-02-05","unlinkHealthSubject":true}""",
                )
            },
        )
        val coolant = schedule(generator, "Coolant flush")
        api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(generator, "Coolant", "MAINTENANCE_OVERDUE", coolant), status = 201)
        assertEquals(
            setOf("maintenance_schedule", "health_subject", "schedule_state"),
            tablesWrittenBy { api.call("POST", "/v1/schedules/$coolant/archive", """{"archived":true,"unlinkHealthSubject":true}""") },
        )
        assertEquals(
            setOf("maintenance_schedule", "schedule_state"),
            tablesWrittenBy {
                api.call(
                    "POST", "/v1/schedules",
                    """{"title":"Spark plugs","targetAssetId":"$generator","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-05",
                       "servicePolicy":"IN_SERVICE_AT_START"}""",
                )
            },
        )
    }
}
