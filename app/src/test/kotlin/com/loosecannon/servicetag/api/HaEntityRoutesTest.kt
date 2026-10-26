package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.FIELD_ENTITY_ID
import com.loosecannon.servicetag.core.hapublish.PublishRefusal
import com.loosecannon.servicetag.core.hapublish.PublishRefused
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.HelperAttributes
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #113/#114 (C29b, I33; row 114) — helper discovery, `GET /v1/ha-connection/entities/{scope}`, over a **scripted**
 * list collaborator standing in for the shipped #105 read (no Home Assistant is ever called here): each scope lists
 * only its domain, sorted and bounded; a row carries `options` or the two date flags for its own domain only, and no
 * state, address, Wi-Fi name or token; a missing connection and a failed read are refusals by name; every call is one
 * fresh read, nothing cached. Every id is fictional.
 */
class HaEntityRoutesTest {

    private val graph = FakeGraph()

    /** What the scripted read answers next, and how many reads were made. */
    private var answer: HaListOutcome? = HaListOutcome.Listed(emptyList())
    private var reads = 0
    private val api = V1Client(graph, haEntities = { reads += 1; answer })

    @After fun close() = graph.close()

    private fun select(id: String, name: String?, vararg options: String) =
        HaEntityCandidate(id, name, HelperAttributes(options.toList(), null, null))

    private fun date(id: String, name: String?, hasDate: Boolean? = true, hasTime: Boolean? = false) =
        HaEntityCandidate(id, name, HelperAttributes(null, hasDate, hasTime))

    private val mixed = listOf(
        HaEntityCandidate("input_boolean.example_heater_in_season", "Example Heater in season"),
        select("input_select.example_heater_equipment_status", "Example Heater status", "UP", "DEGRADED", "DOWN"),
        date("input_datetime.example_heater_service_done", "Example Heater service done"),
        date("input_datetime.example_heater_season_ended", "Example Heater season ended"),
        select("input_select.example_boiler_equipment_status", null, "UP", "DOWN"),
        HaEntityCandidate("switch.example_lamp", "Example lamp"),
        date("input_datetime.example_alarm", "Example alarm", hasDate = false, hasTime = true),
    )

    private fun get(scope: String): ApiResponse = api.call("GET", "/v1/ha-connection/entities/$scope")

    private fun listed(scope: String): JsonObject {
        val response = get(scope)
        assertEquals(response.bodyText(), 200, response.status)
        return Json.parseToJsonElement(response.bodyText()).jsonObject
    }

    private fun JsonObject.ids(): List<String> = getValue("entities").jsonArray.map { it.jsonObject.getValue("entityId").jsonPrimitive.content }

    @Test fun eachScopeListsOnlyItsDomainSortedByName() {
        answer = HaListOutcome.Listed(mixed)
        val selects = listed("input_select")
        assertEquals("input_select", selects.getValue("scope").jsonPrimitive.content)
        // By the casefolded friendly name, a row with none by its id: "Example Heater status" before "input_select.…".
        assertEquals(
            listOf("input_select.example_heater_equipment_status", "input_select.example_boiler_equipment_status"),
            selects.ids(),
        )
        assertEquals("false", selects.getValue("truncated").jsonPrimitive.content)
        assertEquals(
            listOf("input_datetime.example_alarm", "input_datetime.example_heater_season_ended", "input_datetime.example_heater_service_done"),
            listed("input_datetime").ids(),
        )
        assertEquals(listOf("input_boolean.example_heater_in_season"), listed("input_boolean").ids())
    }

    @Test fun optionsOnlyOnADropdownAndTheFlagsOnlyOnADateHelper() {
        answer = HaListOutcome.Listed(mixed)
        val keys = setOf("entityId", "friendlyName", "options", "hasDate", "hasTime")

        val status = listed("input_select").getValue("entities").jsonArray[0].jsonObject
        assertEquals(keys, status.keys)
        assertEquals("Example Heater status", status.getValue("friendlyName").jsonPrimitive.content)
        assertEquals(listOf("UP", "DEGRADED", "DOWN"), status.getValue("options").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("null", status.getValue("hasDate").toString())
        assertEquals("null", status.getValue("hasTime").toString())

        val done = listed("input_datetime").getValue("entities").jsonArray[2].jsonObject
        assertEquals(keys, done.keys)
        assertEquals("null", done.getValue("options").toString())
        assertEquals("true", done.getValue("hasDate").jsonPrimitive.content)
        assertEquals("false", done.getValue("hasTime").jsonPrimitive.content)

        val boolean = listed("input_boolean").getValue("entities").jsonArray.single().jsonObject
        assertEquals(keys, boolean.keys)
        assertTrue(listOf("options", "hasDate", "hasTime").all { boolean.getValue(it).toString() == "null" })
    }

    @Test fun aDropdownReportingNoUsableOptionsAnswersNullNeverAnEmptyList() {
        answer = HaListOutcome.Listed(
            listOf(HaEntityCandidate("input_select.example_odd", "Example odd", HelperAttributes(null, null, null))),
        )
        val row = listed("input_select").getValue("entities").jsonArray.single().jsonObject
        assertEquals("null", row.getValue("options").toString())
    }

    @Test fun theBodyCarriesNoStateAddressWifiNameOrToken() {
        answer = HaListOutcome.Listed(mixed)
        for (scope in listOf("input_boolean", "input_select", "input_datetime")) {
            val text = get(scope).bodyText()
            for (secret in listOf("state", "baseUrl", "http", "192.168.0.10", "ha.example", "ExampleHomeWifi", "fictional-token-1", "token")) {
                assertFalse("$scope carries $secret: $text", secret in text)
            }
        }
    }

    @Test fun atMostTwoThousandWithTruncatedWhenMoreMatched() {
        fun many(n: Int) = (1..n).map { HaEntityCandidate("input_boolean.example_%05d".format(it), null) }
        answer = HaListOutcome.Listed(many(2_001))
        val over = listed("input_boolean")
        assertEquals(2_000, over.getValue("entities").jsonArray.size)
        assertEquals("true", over.getValue("truncated").jsonPrimitive.content)
        answer = HaListOutcome.Listed(many(2_000))
        val exact = listed("input_boolean")
        assertEquals(2_000, (exact.getValue("entities") as JsonArray).size)
        assertEquals("false", exact.getValue("truncated").jsonPrimitive.content)
    }

    @Test fun noStoredConnectionIs409HaPublishRefusedNoConnection() {
        answer = null
        val response = get("input_select")
        assertEquals(response.bodyText(), 409, response.status)
        val error = response.errorDetail()
        assertEquals("HA_PUBLISH_REFUSED", error.code)
        assertEquals(listOf("PublishRefused(reason=NO_CONNECTION)"), error.problems)
        assertNull(error.field)
    }

    @Test fun aFailedReadIs409HaHelperReadFailedWithItsKind() {
        val cases = listOf(
            HaListOutcome.Failed(SyncErrorKind.NEEDS_TOKEN, null) to "HelperReadFailed(errorKind=NEEDS_TOKEN)",
            HaListOutcome.Failed(SyncErrorKind.NOT_ON_LOCAL_NETWORK, "LOCATION_OFF") to
                "HelperReadFailed(errorKind=NOT_ON_LOCAL_NETWORK, detail=LOCATION_OFF)",
            HaListOutcome.Failed(SyncErrorKind.NOT_ON_LOCAL_NETWORK, null) to "HelperReadFailed(errorKind=NOT_ON_LOCAL_NETWORK)",
            HaListOutcome.Failed(SyncErrorKind.AUTH_REFUSED, null) to "HelperReadFailed(errorKind=AUTH_REFUSED)",
            HaListOutcome.Failed(SyncErrorKind.HTTP_ERROR, "502") to "HelperReadFailed(errorKind=HTTP_ERROR, detail=502)",
        )
        for ((failed, problem) in cases) {
            answer = failed
            val response = get("input_datetime")
            assertEquals(response.bodyText(), 409, response.status)
            val error = response.errorDetail()
            assertEquals("HA_HELPER_READ_FAILED", error.code)
            assertEquals(listOf(problem), error.problems)
            assertNull(error.field)
        }
    }

    /** A4w review M1: `STALE_PLAN` is its own 409 — the caller plans again — and keeps its refused key. */
    @Test fun thePublishRefusedArmGivesStalePlanItsOwnCode() {
        val response = mapDomainFailure(PublishRefused(PublishRefusal.STALE_PLAN, "planId"))
        assertEquals(409, response.status)
        val error = response.errorDetail()
        assertEquals("STALE_PLAN", error.code)
        assertEquals(listOf("PublishRefused(reason=STALE_PLAN)"), error.problems)
        assertEquals("planId", error.field)
    }

    /** A4w review M1: `TARGET_IN_USE` names the asset that holds the helper, under the shared refusal code. */
    @Test fun thePublishRefusedArmNamesTheHolderOfATargetInUse() {
        val response = mapDomainFailure(
            PublishRefused(PublishRefusal.TARGET_IN_USE, FIELD_ENTITY_ID, AssetId("example-holder")),
        )
        assertEquals(409, response.status)
        val error = response.errorDetail()
        assertEquals("HA_PUBLISH_REFUSED", error.code)
        assertEquals(listOf("PublishRefused(reason=TARGET_IN_USE, holderAssetId=example-holder)"), error.problems)
        assertEquals("entityId", error.field)
    }

    @Test fun twoCallsAreTwoReads() {
        // One router for both calls, as the Developer API screen keeps one for its visit: nothing may be kept between.
        val router = api.router()
        fun read(): JsonObject {
            val response = router.handle(
                ApiRequest(
                    "GET", "/v1/ha-connection/entities/input_select",
                    mapOf("host" to "127.0.0.1", "authorization" to "Bearer ABCD2345"), ByteArray(0),
                ),
            )
            assertEquals(response.bodyText(), 200, response.status)
            return Json.parseToJsonElement(response.bodyText()).jsonObject
        }
        answer = HaListOutcome.Listed(mixed)
        assertEquals(2, read().ids().size)
        // A connection saved between the calls (another Home Assistant, another list): the second call reads again.
        answer = HaListOutcome.Listed(listOf(select("input_select.example_pool_status", "Example pool status", "UP", "DEGRADED", "DOWN")))
        assertEquals(listOf("input_select.example_pool_status"), read().ids())
        assertEquals("one read per call, nothing cached", 2, reads)
    }

    @Test fun theContractDocumentsTheRouteItsCodesAnd405() {
        val doc = repoFile("docs/api/v1.md").readText()
        val lines = doc.lines()
        assertEquals(1, lines.count { it.startsWith("| `GET` | `/v1/ha-connection/entities/{scope}` |") })
        assertTrue("### Home Assistant helper discovery (#113, #114)" in doc)
        val codes = doc.substringAfter("### The #113/#114 codes").substringBefore("\n## ")
        for (row in listOf("| 409 | `HA_PUBLISH_REFUSED` |", "| 409 | `HA_HELPER_READ_FAILED` |")) {
            assertEquals(row, 1, codes.lines().count { it.startsWith(row) })
        }
        assertTrue(lines.single { it.startsWith("| 405 |") }.contains("`/v1/ha-connection/entities/{scope}`"))
    }

    @Test fun anUnknownScopeIs404WithNoReadAndAnotherVerbIs405() {
        answer = HaListOutcome.Listed(mixed)
        for (path in listOf(
            "/v1/ha-connection/entities/switch",
            "/v1/ha-connection/entities/input_text",
            "/v1/ha-connection/entities/INPUT_SELECT",
            "/v1/ha-connection/entities/input_selects",
            "/v1/ha-connection/entities",
            "/v1/ha-connection",
            "/v1/ha-connection/entities/input_select/x",
        )) {
            assertEquals(path, 404, api.call("GET", path).status)
        }
        for (method in listOf("POST", "PATCH", "DELETE")) {
            assertEquals(method, 405, api.call(method, "/v1/ha-connection/entities/input_select", "{}").status)
        }
        assertEquals("no refused call reads Home Assistant", 0, reads)
    }
}
