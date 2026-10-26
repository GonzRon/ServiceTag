package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefusal
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefused
import com.loosecannon.servicetag.seasonsync.HOME_WIFI
import com.loosecannon.servicetag.seasonsync.HTTP_ADDRESS
import com.loosecannon.servicetag.seasonsync.TOKEN
import com.loosecannon.servicetag.seasonsync.connect
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #125 (U-C7; row h15) — a schedule's own Home Assistant season over `/v1`: `GET /v1/schedules/{id}/season-sync` in the
 * asset season sync's shape, holding no address, Wi-Fi name or token; `POST` links with the caller's own
 * `confirmEntityId` and unlinks with the caller's own `confirmUnlink: true`, never defaulting either; each
 * `ScheduleSeasonRefusal` is 409 `SCHEDULE_SEASON_REFUSED` by its name with nothing written, the schedule PATCH's
 * included; another verb is a 405. No Home Assistant is called: the reader is scripted. Every name is fictional.
 */
class ScheduleSeasonRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-10-20")
        today = LocalDate.parse("2026-10-20")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private val switch = "input_boolean.example_heater_winterize_season"

    // --- fixtures -------------------------------------------------------------------------------

    private fun schedule(target: String, title: String = "Example season start", extra: String = ""): String = api.ok(
        ScheduleResponse.serializer(), "POST", "/v1/schedules",
        """{"title":"$title",$target,"timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01"$extra}""",
        status = 201,
    ).schedule.id

    private fun heaterSchedule(): Pair<String, String> {
        val heater = api.asset("Example Heater")
        return heater to schedule(""""targetAssetId":"$heater"""")
    }

    private fun path(schedule: String) = "/v1/schedules/$schedule/season-sync"

    private fun link(schedule: String, entity: String = switch, confirm: String? = entity): ApiResponse {
        val confirmation = confirm?.let { ""","confirmEntityId":"$it"""" } ?: ""
        return api.call("POST", path(schedule), """{"action":"LINK","entityId":"$entity"$confirmation}""")
    }

    private fun answer(response: ApiResponse): ScheduleSeasonResponse {
        assertEquals(response.bodyText(), 200, response.status)
        return ApiJson.decodeFromString(ScheduleSeasonResponse.serializer(), response.bodyText())
    }

    private fun tree(response: ApiResponse): JsonObject = Json.parseToJsonElement(response.bodyText()).jsonObject

    /** Every row a link, an unlink or a refusal could reach. */
    private fun snapshot(): Map<String, Set<Any>> = runBlocking {
        mapOf(
            "maintenance_schedule" to graph.schedules.all().toSet(),
            "schedule_state" to graph.scheduleStates.all().toSet(),
            "schedule_season_sync_binding" to graph.scheduleSeasonBindings.all().toSet(),
            "schedule_season_activation" to graph.scheduleSeasonActivations.all().toSet(),
            "season_sync_binding" to graph.seasonSyncBindings.all().toSet(),
            "asset_season_activation" to graph.seasonActivations.all().toSet(),
        )
    }

    /** [call] is refused with [status] and [code] (and [problem] among its `problems`), and nothing moves. */
    private fun assertRefused(status: Int, code: String, problem: String? = null, call: () -> ApiResponse): ApiResponse {
        graph.now += 86_400_000L
        val before = snapshot()
        val response = call()
        assertEquals(response.bodyText(), status, response.status)
        assertTrue(response.bodyText(), response.bodyText().contains("\"code\":\"$code\""))
        if (problem != null) assertTrue(response.bodyText(), response.bodyText().contains(problem))
        assertEquals("nothing was written", before, snapshot())
        // H3 review m1: a launched check runs on the season sync scope, so join it before asking whether one ran.
        runBlocking { graph.awaitSeasonSyncReads() }
        assertTrue("no read was launched", graph.haStateReader.reads.isEmpty())
        return response
    }

    // --- the read ---------------------------------------------------------------------------------

    @Test fun theReadHoldsNoAddressWifiNameOrToken() = runBlocking<Unit> {
        graph.connect()
        val (_, start) = heaterSchedule()
        answer(link(start))
        graph.awaitSeasonSyncReads()

        val response = api.call("GET", path(start))
        val read = answer(response)

        assertEquals(start, read.scheduleId)
        assertEquals("OWN", read.seasonSource)
        assertEquals("no row yet: out of its own season", "OUT_OF_SEASON", read.seasonPhase)
        assertTrue(read.connection.configured)
        assertFalse(read.connection.needsToken)
        val binding = checkNotNull(read.binding)
        assertEquals(switch, binding.entityId)
        assertEquals("FOLLOW", binding.mode)
        assertEquals("ACTIVE", binding.state)
        assertEquals(
            setOf("scheduleId", "seasonSource", "seasonPhase", "connection", "binding", "activations"),
            tree(response).keys,
        )
        val bytes = response.bodyText()
        for (secret in listOf(HTTP_ADDRESS, "192.168.0.10", HOME_WIFI, TOKEN.value)) {
            assertFalse("the read carries $secret: $bytes", bytes.contains(secret))
        }
    }

    @Test fun aScheduleWithNoBindingReadsItsAssetsSeason() {
        val (_, start) = heaterSchedule()
        val response = api.call("GET", path(start))
        val read = answer(response)
        assertEquals("ASSET", read.seasonSource)
        assertEquals("a year-round asset is in season", "IN_SEASON", read.seasonPhase)
        assertFalse(read.connection.configured)
        assertEquals(JsonNull, tree(response).getValue("binding"))
        assertTrue(read.activations.isEmpty())
    }

    // --- LINK -------------------------------------------------------------------------------------

    @Test fun linkWithTheConfirmationLinksAndLaunchesTheCheck() = runBlocking<Unit> {
        graph.connect()
        val (_, start) = heaterSchedule()
        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.ON, null) }

        val linked = answer(link(start))
        assertEquals("OWN", linked.seasonSource)
        assertEquals(switch, linked.binding?.entityId)
        assertEquals(ScheduleSeasonSource.OWN, graph.schedules.get(ScheduleId(start))!!.seasonSource)

        graph.awaitSeasonSyncReads()
        assertEquals("the post-link check read the switch once", listOf(switch), graph.haStateReader.reads.map { it.entityId })
        val read = answer(api.call("GET", path(start)))
        assertEquals("ON", read.binding?.observation?.state)
        assertEquals(listOf("START"), read.activations.map { it.action })
        assertEquals("2026-10-20", read.activations.single().occurredOn)
        assertEquals("IN_SEASON", read.seasonPhase)

        assertEquals(1, ApiJson.decodeFromString(StatusResponse.serializer(), api.call("GET", "/v1/status").bodyText())
            .counts["scheduleSeasonActivations"])
    }

    /** The confirmation is the caller's: missing, different or a key LINK does not take is the 422, nothing written. */
    @Test fun aLinkWithoutItsOwnConfirmationIs422() = runBlocking<Unit> {
        graph.connect()
        val (_, start) = heaterSchedule()
        for (body in listOf(
            """{"action":"LINK","entityId":"$switch"}""",
            """{"action":"LINK","entityId":"$switch","confirmEntityId":"input_boolean.example_other_switch"}""",
            """{"action":"LINK","entityId":"$switch","confirmEntityId":null}""",
        )) {
            assertRefused(422, "SCHEDULE_SEASON_CONFIRMATION_REQUIRED", "\"field\":\"confirmEntityId\"") {
                api.call("POST", path(start), body)
            }
        }
        assertRefused(422, "SCHEDULE_SEASON_CONFIRMATION_REQUIRED", "\"field\":\"confirmUnlink\"") {
            api.call("POST", path(start), """{"action":"LINK","entityId":"$switch","confirmEntityId":"$switch","confirmUnlink":true}""")
        }
        assertRefused(400, "bad_request") { api.call("POST", path(start), """{"action":"LINK","confirmEntityId":"$switch"}""") }
        assertRefused(400, "bad_request") { api.call("POST", path(start), """{"action":"FORCE_IN","entityId":"$switch"}""") }
        assertRefused(400, "bad_request") {
            api.call("POST", path(start), """{"action":"LINK","entityId":"$switch","confirmEntityId":"$switch","mode":"FORCE_IN"}""")
        }
    }

    // --- UNLINK -----------------------------------------------------------------------------------

    @Test fun unlinkReturnsToTheAssetsSeasonAndKeepsTheRows() = runBlocking<Unit> {
        graph.connect()
        val (_, start) = heaterSchedule()
        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.ON, null) }
        answer(link(start))
        graph.awaitSeasonSyncReads()
        val rows = graph.scheduleSeasonActivations.forSchedule(ScheduleId(start))
        assertEquals(1, rows.size)

        val unlinked = answer(api.call("POST", path(start), """{"action":"UNLINK","confirmUnlink":true}"""))

        assertEquals("ASSET", unlinked.seasonSource)
        assertNull(unlinked.binding)
        assertEquals("its own rows are kept as history", rows.map { it.id }, unlinked.activations.map { it.id })
        assertNull(graph.scheduleSeasonBindings.get(ScheduleId(start)))
        assertEquals(ScheduleSeasonSource.ASSET, graph.schedules.get(ScheduleId(start))!!.seasonSource)
    }

    @Test fun anUnlinkWithoutItsOwnConfirmationIsRefused() = runBlocking<Unit> {
        graph.connect()
        val (_, start) = heaterSchedule()
        answer(link(start))
        graph.awaitSeasonSyncReads()
        graph.haStateReader.reads.clear()
        for (body in listOf(
            """{"action":"UNLINK"}""",
            """{"action":"UNLINK","confirmUnlink":false}""",
            """{"action":"UNLINK","confirmUnlink":"true"}""",
            """{"action":"UNLINK","confirmUnlink":1}""",
            """{"action":"UNLINK","confirmUnlink":null}""",
        )) {
            assertRefused(422, "SCHEDULE_SEASON_CONFIRMATION_REQUIRED", "\"field\":\"confirmUnlink\"") {
                api.call("POST", path(start), body)
            }
        }
        assertRefused(422, "SCHEDULE_SEASON_CONFIRMATION_REQUIRED", "\"field\":\"confirmEntityId\"") {
            api.call("POST", path(start), """{"action":"UNLINK","confirmUnlink":true,"confirmEntityId":"$switch"}""")
        }
        assertRefused(400, "bad_request") {
            api.call("POST", path(start), """{"action":"UNLINK","confirmUnlink":true,"entityId":"$switch"}""")
        }
    }

    // --- the refusals -----------------------------------------------------------------------------

    @Test fun eachReachableRefusalIsScheduleSeasonRefusedByName() = runBlocking<Unit> {
        fun refused(reason: ScheduleSeasonRefusal, call: () -> ApiResponse) =
            assertRefused(409, "SCHEDULE_SEASON_REFUSED", "ScheduleSeasonRefused(reason=${reason.name})", call)

        val (heater, start) = heaterSchedule()
        refused(ScheduleSeasonRefusal.NO_CONNECTION) { link(start) }
        val connection = graph.connect()
        refused(ScheduleSeasonRefusal.BAD_ENTITY_ID) { link(start, "input_select.example_heater_mode") }
        answer(link(start))
        graph.awaitSeasonSyncReads()
        graph.haStateReader.reads.clear()
        refused(ScheduleSeasonRefusal.ALREADY_LINKED) { link(start, "input_boolean.example_heater_other") }

        val group = api.ok(
            GroupResponse.serializer(), "POST", "/v1/groups", """{"name":"Example appliances","members":[{"assetId":"$heater"}]}""",
            status = 201,
        ).group.id
        val groupCheck = schedule(""""targetGroupId":"$group"""", "Example group check")
        refused(ScheduleSeasonRefusal.GROUP_TARGET) { link(groupCheck) }

        val archived = schedule(""""targetAssetId":"$heater"""", "Example archived check")
        api.ok(ScheduleResponse.serializer(), "POST", "/v1/schedules/$archived/archive", """{"archived":true}""")
        refused(ScheduleSeasonRefusal.SCHEDULE_ARCHIVED) { link(archived) }

        val seasonal = api.asset("Example Boiler")
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$seasonal/season-mode",
            """{"seasonMode":"CALENDAR","seasonStartMmdd":"11-01","seasonEndMmdd":"03-31"}""",
        )
        val before = schedule(""""targetAssetId":"$seasonal"""", "Example pre-season check", ""","servicePolicy":"PRE_SERVICE","policyOffsetDays":-14""")
        refused(ScheduleSeasonRefusal.STRANDS_PRE_SERVICE) { link(before) }

        val gone = api.asset("Example Spa")
        val goneCheck = schedule(""""targetAssetId":"$gone"""", "Example spa check")
        api.call("POST", "/v1/assets/$gone/archive", """{"archived":true}""").also { assertEquals(it.bodyText(), 200, it.status) }
        refused(ScheduleSeasonRefusal.NOT_MAINTAINED_HERE) { link(goneCheck) }

        graph.secretStore.delete(connection.id)
        val other = schedule(""""targetAssetId":"$heater"""", "Example second check")
        refused(ScheduleSeasonRefusal.NEEDS_TOKEN) { link(other) }
    }

    /** Every reason, the one no route can reach today (`OUTBOUND_TARGET`: a written helper is never an input_boolean) too. */
    @Test fun everyRefusalMapsTo409ByItsName() {
        for (reason in ScheduleSeasonRefusal.entries) {
            val response = mapDomainFailure(ScheduleSeasonRefused(reason))
            assertEquals(reason.name, 409, response.status)
            val text = response.body.decodeToString()
            assertTrue(text, text.contains("\"code\":\"SCHEDULE_SEASON_REFUSED\""))
            assertTrue(text, text.contains("ScheduleSeasonRefused(reason=${reason.name})"))
        }
    }

    /** H1b's carry: a retarget of an own-season schedule to a group was a 500; it is the 409, nothing written. */
    @Test fun aRetargetOfAnOwnSeasonScheduleToAGroupIs409() = runBlocking<Unit> {
        graph.connect()
        val (heater, start) = heaterSchedule()
        answer(link(start))
        graph.awaitSeasonSyncReads()
        graph.haStateReader.reads.clear()
        val group = api.ok(
            GroupResponse.serializer(), "POST", "/v1/groups", """{"name":"Example appliances","members":[{"assetId":"$heater"}]}""",
            status = 201,
        ).group.id

        assertRefused(409, "SCHEDULE_SEASON_REFUSED", "ScheduleSeasonRefused(reason=GROUP_TARGET)") {
            api.call(
                "PATCH", "/v1/schedules/$start",
                """{"title":"Example season start","targetGroupId":"$group","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01"}""",
            )
        }
    }

    @Test fun anotherVerbIs405AndAnUnknownScheduleIs404(): Unit = run {
        val (_, start) = heaterSchedule()
        for (verb in listOf("PUT", "PATCH", "DELETE")) {
            val response = api.call(verb, path(start), """{"action":"UNLINK","confirmUnlink":true}""")
            assertEquals("$verb: ${response.bodyText()}", 405, response.status)
        }
        assertRefused(404, "NO_SUCH_SCHEDULE") { api.call("GET", path("no-such-schedule")) }
        assertRefused(404, "NO_SUCH_SCHEDULE") {
            api.call("POST", path("no-such-schedule"), """{"action":"LINK","entityId":"$switch","confirmEntityId":"$switch"}""")
        }
    }

    // --- the status count and the merge tally (U-C6's API side) -----------------------------------

    @Test fun aMergeReportsTheScheduleSeasonRows() = runBlocking<Unit> {
        val archive = FakeGraph().let { donor ->
            try {
                val donorApi = V1Client(donor)
                donor.connect()
                val heater = donorApi.asset("Example Heater")
                val start = donorApi.ok(
                    ScheduleResponse.serializer(), "POST", "/v1/schedules",
                    """{"title":"Example season start","targetAssetId":"$heater","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01"}""",
                    status = 201,
                ).schedule.id
                donor.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.ON, null) }
                donor.linkScheduleSeason.run(ScheduleId(start), switch)
                donor.seasonSyncRunner.syncSchedule(ScheduleId(start))
                assertEquals(1, donor.scheduleSeasonActivations.all().size)
                donor.exportBackupSet.run().data
            } finally {
                donor.close()
            }
        }
        val planned = api.router().handle(
            ApiRequest(
                "POST", IMPORT_MERGE_PLAN_PATH,
                mapOf("authorization" to "Bearer ABCD2345", "content-type" to "application/zip"), archive,
            ),
        )
        assertEquals(planned.bodyText(), 200, planned.status)
        assertTrue(planned.bodyText(), "\"scheduleSeasonActivations\"" in planned.bodyText())
        val report = ApiJson.decodeFromString(MergeReportResponse.serializer(), planned.bodyText())
        assertEquals(MergeTallyDto(insert = 1, identical = 0, conflict = 0, skipped = 0), report.scheduleSeasonActivations)
        assertEquals(0, ApiJson.decodeFromString(StatusResponse.serializer(), api.call("GET", "/v1/status").bodyText())
            .counts["scheduleSeasonActivations"])
    }
}
