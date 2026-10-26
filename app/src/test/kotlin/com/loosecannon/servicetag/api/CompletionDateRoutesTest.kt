package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The code this suite pairs with. Invented, and the only token these requests carry. */
private const val TOKEN = "ABCD2345"

/** The day the fake `Today` reports for every call below. */
private val TODAY: LocalDate = LocalDate.parse("2026-02-10")
private val TOMORROW: String = TODAY.plusDays(1).toString()

/**
 * O-16 as amended (C39, row 127): over `/v1`, a schedule's completion dated after the phone's today is **422
 * `COMPLETION_DATE_IN_FUTURE`**, `field` `occurredOn`, with nothing written — on `POST /v1/schedules/{id}/complete`
 * for both target kinds and on `PATCH /v1/events/{id}` of a completion — and today is accepted. The production
 * handlers, use cases and serializers over `FakeGraph`, as `MaintenanceRoutesTest` drives them.
 */
class CompletionDateRoutesTest {

    private val graph = FakeGraph().apply {
        now = LocalDate.parse("2026-02-01").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        today = TODAY
    }

    @After fun close() = graph.close()

    private fun router(): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments, graph.categories, graph.transferRecords, graph.assetSuccessions,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
            graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
            maintenanceHandlersFor(graph),
            referenceHandlersFor(graph),
            seasonHealthHandlersFor(graph),
            warrantyHandlersFor(graph),
            serviceCaseHandlersFor(graph),
            loanHandlersFor(graph),
            attachmentHandlersFor(graph),
            replaceHandlersFor(graph),
            supplyHandlersFor(graph),
            installedComponentHandlersFor(graph),
            appVersion = "1.8.1",
            schemaVersion = AppGraph.SCHEMA_VERSION,
        ),
        TOKEN,
    )

    private fun call(method: String, path: String, body: String = ""): ApiResponse {
        val headers = buildMap {
            put("host", "127.0.0.1")
            put("authorization", "Bearer $TOKEN")
            if (body.isNotEmpty()) put("content-type", "application/json")
        }
        return router().handle(ApiRequest(method, path, headers, body.toByteArray()))
    }

    private fun ApiResponse.text(): String = body.decodeToString()
    private fun ApiResponse.error(): ApiErrorDetail = ApiJson.decodeFromString(ApiErrorBody.serializer(), text()).error

    private fun created(response: ApiResponse): String {
        assertEquals(response.text(), 201, response.status)
        return response.text()
    }

    private fun createAsset(name: String): String = ApiJson.decodeFromString(
        AssetResponse.serializer(), created(call("POST", "/v1/assets", """{"name":"$name","category":"Heating"}""")),
    ).asset.id

    private fun createSchedule(target: String): String = ApiJson.decodeFromString(
        ScheduleResponse.serializer(),
        created(
            call(
                "POST", "/v1/schedules",
                """{"title":"Example annual service",$target,"timeInterval":1,
                   "timeUnit":"MONTH","timeBasis":"FIXED","anchorOn":"2026-02-01"}""",
            ),
        ),
    ).schedule.id

    private fun createGroup(vararg assetIds: String): String {
        val members = assetIds.mapIndexed { i, id -> """{"assetId":"$id","sortOrder":$i}""" }
        val response = call("POST", "/v1/groups", """{"name":"Example pre-season check","members":[${members.joinToString(",")}]}""")
        assertEquals(response.text(), 201, response.status)
        return ApiJson.decodeFromString(GroupResponse.serializer(), response.text()).group.id
    }

    private fun completion(on: String, assetId: String? = null): String {
        val member = assetId?.let { ""","assetId":"$it"""" } ?: ""
        return """{"occurredOn":"$on","tzId":"UTC"$member}"""
    }

    /** Every table a refused completion could have touched. */
    private fun snapshot(): List<Any> = runBlocking {
        listOf(graph.events.all(), graph.schedules.all(), graph.scheduleStates.all(), graph.closures.all())
    }

    private fun assertRefusedWritingNothing(method: String, path: String, body: String) {
        val before = snapshot()
        val response = call(method, path, body)
        assertEquals(response.text(), 422, response.status)
        val error = response.error()
        assertEquals("COMPLETION_DATE_IN_FUTURE", error.code)
        assertEquals("occurredOn", error.field)
        assertEquals("occurredOn may not be later than today for a schedule's completion", error.message)
        assertEquals(before, snapshot())
    }

    @Test fun aCompletionDatedTomorrowIs422OnBothTargetKindsAndTodayIsAccepted() {
        val boiler = createAsset("Example Boiler")
        val heater = createAsset("Example Heater")
        val assetSchedule = createSchedule(""""targetAssetId":"$boiler"""")
        val groupSchedule = createSchedule(""""targetGroupId":"${createGroup(boiler, heater)}"""")

        assertRefusedWritingNothing("POST", "/v1/schedules/$assetSchedule/complete", completion(TOMORROW))
        assertRefusedWritingNothing("POST", "/v1/schedules/$groupSchedule/complete", completion(TOMORROW, boiler))

        created(call("POST", "/v1/schedules/$assetSchedule/complete", completion(TODAY.toString())))
        created(call("POST", "/v1/schedules/$groupSchedule/complete", completion(TODAY.toString(), heater)))
    }

    @Test fun anEventPatchThatDatesACompletionTomorrowIs422AndTodayIsAccepted() {
        val boiler = createAsset("Example Boiler")
        val schedule = createSchedule(""""targetAssetId":"$boiler"""")
        val done = ApiJson.decodeFromString(
            CompletionResponse.serializer(),
            created(call("POST", "/v1/schedules/$schedule/complete", completion("2026-02-09"))),
        ).event.id

        fun edit(on: String) =
            """{"assetId":"$boiler","kind":"MAINTENANCE","title":"Example annual service","occurredOn":"$on","tzId":"UTC"}"""

        assertRefusedWritingNothing("PATCH", "/v1/events/$done", edit(TOMORROW))

        val moved = call("PATCH", "/v1/events/$done", edit(TODAY.toString()))
        assertEquals(moved.text(), 200, moved.status)
        assertEquals(TODAY.toString(), ApiJson.decodeFromString(EventResponse.serializer(), moved.text()).event.occurredOn)
    }

    /** The contract names the code where a client reads it: the Errors table and the two rows. */
    @Test fun v1MdNamesTheCode() {
        val doc = repoFile("docs/api/v1.md").readText()
        val lines = doc.lines().filter { "COMPLETION_DATE_IN_FUTURE" in it }
        assertTrue(lines.toString(), lines.any { it.startsWith("| 422 | `COMPLETION_DATE_IN_FUTURE` | `occurredOn` |") })
        assertTrue(lines.toString(), lines.any { it.startsWith("| `POST` | `/v1/schedules/{id}/complete` |") })
        assertTrue(lines.toString(), lines.any { it.startsWith("| `PATCH` | `/v1/events/{id}` |") })
    }
}
