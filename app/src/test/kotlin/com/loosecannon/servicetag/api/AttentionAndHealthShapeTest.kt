package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.subjectRow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 1.4 (B09) — the shapes of the four derived 1.4 responses on the wire (spec §9.1; master plan
 * §11.1, §11.2; plan decision 19): the attention item, the health response, the season response and
 * the condition history. Every field is present, `null` when absent, and each is read off the raw
 * JSON as well as through the DTO, so a field that stopped being emitted fails here.
 *
 * The fixture is the spec's fictional F1: a UPS whose battery pack has a cell, 100 days after the
 * UPS's last REPLACEMENT event.
 */
class AttentionAndHealthShapeTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-01")
        today = LocalDate.parse("2026-02-10")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private fun call(method: String, path: String, body: String = "") = api.call(method, path, body)

    private fun json(response: ApiResponse): JsonObject {
        assertEquals(response.bodyText(), 200, response.status)
        return ApiJson.parseToJsonElement(response.bodyText()).jsonObject
    }

    private fun component(parent: String, name: String): String = api.ok(
        AssetResponse.serializer(), "POST", "/v1/assets/$parent/components", """{"name":"$name"}""", status = 201,
    ).asset.id

    private fun condition(asset: String, condition: String, on: String, reason: String = "", time: String? = null): ConditionResponse {
        val at = time?.let { ""","occurredTime":"$it"""" } ?: ""
        return api.ok(
            ConditionResponse.serializer(), "POST", "/v1/assets/$asset/conditions",
            """{"condition":"$condition","occurredOn":"$on","tzId":"UTC","reason":"$reason"$at}""", status = 201,
        )
    }

    /** An AGE subject; 100 days after the replacement, (0, 30, 60) is CRITICAL and (0, 90, 200) WARNING. */
    private fun ageSubject(asset: String, name: String, warning: Int, critical: Int, sortOrder: Int): String = api.ok(
        SubjectResponse.serializer(), "POST", "/v1/health-subjects",
        """{"assetId":"$asset","name":"$name","kind":"PART","driver":"AGE","nominalUntilDays":0,
           "warningFromDays":$warning,"criticalFromDays":$critical,"sortOrder":$sortOrder}""",
        status = 201,
    ).subject.id

    private class F1(val ups: String, val pack: String, val cell: String, val battery: String, val charger: String)

    /**
     * The UPS is DOWN (recorded twice, so its run began before its latest row), its battery pack is
     * DEGRADED and the pack's cell is DOWN; the UPS has a CRITICAL and a WARNING AGE subject.
     */
    private fun f1(): F1 {
        val ups = api.asset("UPS")
        val pack = component(ups, "Battery pack")
        val cell = component(pack, "Cell")
        api.ok(
            EventResponse.serializer(), "POST", "/v1/events",
            """{"assetId":"$ups","kind":"REPLACEMENT","title":"Battery replaced","occurredOn":"2025-11-02","tzId":"UTC"}""",
            status = 201,
        )
        val battery = ageSubject(ups, "Battery", 30, 60, 0)
        val charger = ageSubject(ups, "Charger", 90, 200, 1)
        condition(ups, "DOWN", "2026-02-01", "Inverter fault")
        condition(ups, "DOWN", "2026-02-05", "Inverter fault, fan too")
        condition(pack, "DEGRADED", "2026-02-06", "Cell imbalance")
        condition(cell, "DOWN", "2026-02-07", "Swollen")
        return F1(ups, pack, cell, battery, charger)
    }

    // --- /v1/attention --------------------------------------------------------------------------

    /**
     * Master plan §11.2: every item carries exactly the twelve keys, `null` when absent — a
     * top-level asset's `parentAssetId` included — and `rank` is dense in the order DOWN, DEGRADED,
     * independent CRITICAL, independent WARNING, then asset name.
     */
    @Test fun attentionItemsCarryEveryFieldAndADenseRank() {
        val f = f1()
        val items = json(call("GET", "/v1/attention")).getValue("items").jsonArray.map { it.jsonObject }
        val keys = listOf(
            "kind", "section", "assetId", "parentAssetId", "condition", "reason", "occurredOn",
            "healthSubjectId", "subjectName", "band", "score", "rank",
        )
        items.forEach { assertEquals(it.toString(), keys, it.keys.toList()) }

        fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
        assertEquals(
            listOf(
                listOf("CONDITION", "ATTENTION", f.cell, f.pack, "DOWN", null, "0"),
                listOf("CONDITION", "ATTENTION", f.ups, null, "DOWN", null, "1"),
                listOf("CONDITION", "ATTENTION", f.pack, f.ups, "DEGRADED", null, "2"),
                listOf("HEALTH", "ATTENTION", f.ups, null, null, "CRITICAL", "3"),
                listOf("HEALTH", "UPCOMING", f.ups, null, null, "WARNING", "4"),
            ),
            items.map { listOf(it.s("kind"), it.s("section"), it.s("assetId"), it.s("parentAssetId"), it.s("condition"), it.s("band"), it.s("rank")) },
        )
        val down = items[1]
        assertEquals(JsonNull, down["parentAssetId"])
        assertEquals("Inverter fault, fan too", down.s("reason"))
        assertEquals("2026-02-05", down.s("occurredOn"))
        assertEquals(listOf(JsonNull, JsonNull, JsonNull, JsonNull), listOf(down["healthSubjectId"], down["subjectName"], down["band"], down["score"]))
        val critical = items[3]
        assertEquals(f.battery, critical.s("healthSubjectId"))
        assertEquals("Battery", critical.s("subjectName"))
        assertTrue(critical.s("score")!!.toInt() in 0..100)
        assertEquals(listOf(JsonNull, JsonNull, JsonNull), listOf(critical["condition"], critical["reason"], critical["occurredOn"]))
    }

    // --- /v1/assets/{id}/health -----------------------------------------------------------------

    /**
     * Inv. 119 at the wire: the health response carries the asset's current condition (with the day
     * its run began), **every** CRITICAL subject whatever the aggregate says, and every DOWN or
     * DEGRADED in-service component at any depth — DOWN first. The aggregate's `trackedDays` is
     * `null` (an aggregate has no day count; its subjects carry theirs).
     */
    @Test fun theHealthResponseListsCriticalsAndComponentsWithItsCondition() {
        val f = f1()
        assertEquals(200, call("POST", "/v1/assets/${f.ups}/health-policy", """{"healthAggregation":"AVERAGE"}""").status)
        val raw = json(call("GET", "/v1/assets/${f.ups}/health"))
        assertEquals(
            listOf("assetId", "computedForOn", "condition", "aggregation", "aggregate", "subjects", "critical", "components"),
            raw.keys.toList(),
        )
        val health = ApiJson.decodeFromString(HealthResponse.serializer(), raw.toString())
        assertEquals(f.ups, health.assetId)
        assertEquals("2026-02-10", health.computedForOn)
        assertEquals("AVERAGE", health.aggregation)
        assertEquals(HealthConditionDto("DOWN", "2026-02-01", "Inverter fault, fan too", "2026-02-05", null), health.condition)

        val aggregate = health.aggregate!!
        assertNull("an aggregate has no day count of its own", aggregate.trackedDays)
        assertTrue("trackedDays" in raw.getValue("aggregate").jsonObject)
        assertEquals(false, aggregate.fallback)

        assertEquals(listOf(f.battery, f.charger), health.subjects.map { it.subjectId })
        val battery = health.subjects[0]
        assertEquals("CRITICAL", battery.band)
        assertEquals(100L, battery.trackedDays)
        assertEquals(listOf("PART", "AGE", null, null), listOf(battery.kind, battery.driver, battery.scheduleId, battery.notTracked))
        assertEquals("WARNING", health.subjects[1].band)

        assertEquals(listOf(HealthCriticalDto(f.battery, "Battery", battery.score!!)), health.critical)
        assertEquals(
            listOf(
                HealthComponentDto(f.cell, "Cell", "DOWN", "Swollen", "2026-02-07"),
                HealthComponentDto(f.pack, "Battery pack", "DEGRADED", "Cell imbalance", "2026-02-06"),
            ),
            health.components,
        )
    }

    /**
     * B07's ruling: a subject the read model screened out — malformed configuration no command or
     * restore can store — reads `notTracked: UNSCORABLE`, with no score, band or day count; with
     * nothing else to contribute, the aggregate is null (NOT TRACKED, never a 100).
     */
    @Test fun anUnscorableSubjectReadsNotTrackedUnscorable() {
        val generator = api.asset("Generator")
        runBlocking {
            graph.healthSubjects.upsert(
                subjectRow("h-bad", generator, name = "Coolant", nominalUntilDays = 60, warningFromDays = 30, criticalFromDays = 10),
            )
        }
        val health = ApiJson.decodeFromString(HealthResponse.serializer(), json(call("GET", "/v1/assets/$generator/health")).toString())
        assertEquals(
            HealthSubjectValueDto("h-bad", "Coolant", "PART", "AGE", null, null, null, null, "UNSCORABLE"),
            health.subjects.single(),
        )
        assertNull(health.aggregate)
        assertNull(health.condition)
        assertEquals(emptyList<HealthCriticalDto>(), health.critical)
    }

    // --- /v1/assets/{id}/season -----------------------------------------------------------------

    /**
     * Plan decision 19's season shape on three assets: a CALENDAR mower out of season (its next
     * boundary is its season start), a MANUAL hot tub whose history is oldest first and whose next
     * boundary is never predicted, and a YEAR_ROUND generator inside its break.
     */
    @Test fun theSeasonResponse() {
        val mower = api.asset("Mower")
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$mower/season-mode",
            """{"seasonMode":"CALENDAR","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}""",
        )
        val raw = json(call("GET", "/v1/assets/$mower/season"))
        assertEquals(
            listOf(
                "seasonMode", "seasonStartMmdd", "seasonEndMmdd", "seasonPhase", "nextBoundaryOn",
                "blackoutStartMmdd", "blackoutEndMmdd", "inBreak", "activations", "computedForOn",
            ),
            raw.keys.toList(),
        )
        assertEquals(
            SeasonResponse("CALENDAR", "04-01", "10-31", "OUT_OF_SEASON", "2026-04-01", null, null, false, emptyList(), "2026-02-10"),
            ApiJson.decodeFromString(SeasonResponse.serializer(), raw.toString()),
        )

        val tub = api.asset("Hot tub")
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$tub/season-mode",
            """{"seasonMode":"MANUAL","manualPhase":"IN_SEASON"}""",
        )
        graph.now = dayMillis("2026-02-10")
        val ended = api.ok(
            ActivationResponse.serializer(), "POST", "/v1/assets/$tub/season", """{"action":"END"}""", status = 201,
        )
        assertEquals(listOf("END", "2026-02-10"), listOf(ended.activation.action, ended.activation.occurredOn))
        assertEquals("OUT_OF_SEASON", ended.season.seasonPhase)
        assertNull(ended.season.nextBoundaryOn)
        assertEquals(listOf("START", "END"), ended.season.activations.map { it.action })
        assertEquals(ended.activation, ended.season.activations.last())

        val generator = api.asset("Generator")
        val inBreak = api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$generator/maintenance-break",
            """{"blackoutStartMmdd":"02-01","blackoutEndMmdd":"02-28"}""",
        )
        assertEquals("02-01", inBreak.asset.blackoutStartMmdd)
        assertEquals(
            SeasonResponse("YEAR_ROUND", null, null, "IN_SEASON", null, "02-01", "02-28", true, emptyList(), "2026-02-10"),
            inBreak.season,
        )
    }

    // --- /v1/assets/{id}/conditions -------------------------------------------------------------

    /**
     * Master plan §11.1: the history is oldest first by the ordering key — `(occurredOn, occurredTime
     * with none first, createdAt, id)`, not arrival order — and `current` is its last row. A write's
     * `current` is read after it, so a backdated row is not claimed as current.
     */
    @Test fun theConditionsResponseOrderAndCurrent() {
        val ups = api.asset("UPS")
        graph.now = dayMillis("2026-02-08")
        val down = condition(ups, "DOWN", "2026-02-05", "Inverter fault", time = "14:00")
        assertEquals(down.condition, down.current)
        graph.now = dayMillis("2026-02-09")
        val backdated = condition(ups, "OPERATIONAL", "2026-02-03")
        assertEquals("the backdated row is not current", down.condition, backdated.current)
        graph.now = dayMillis("2026-02-10")
        val sameDayUntimed = condition(ups, "DEGRADED", "2026-02-05", "Fan noise")

        val raw = json(call("GET", "/v1/assets/$ups/conditions"))
        assertEquals(listOf("conditions", "current"), raw.keys.toList())
        val history = ApiJson.decodeFromString(ConditionsResponse.serializer(), raw.toString())
        assertEquals(
            listOf(backdated.condition.id, sameDayUntimed.condition.id, down.condition.id),
            history.conditions.map { it.id },
        )
        assertEquals(down.condition, history.current)

        val empty = ApiJson.decodeFromString(ConditionsResponse.serializer(), json(call("GET", "/v1/assets/${api.asset("Mower")}/conditions")).toString())
        assertEquals(ConditionsResponse(emptyList(), null), empty)
    }

    // --- the asset row --------------------------------------------------------------------------

    /**
     * B03's carry-forward: `/v1` asset responses carry the five 1.4 asset fields — the season mode,
     * the break pair, the health aggregation and its primary — beside 1.3's season pair, as the
     * archive row does, and each write route answers the row as it stored it.
     */
    @Test fun theAssetResponseCarriesTheFiveNewAssetFields() {
        val ups = api.asset("UPS")
        val battery = ageSubject(ups, "Battery", 30, 60, 0)
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$ups/maintenance-break",
            """{"blackoutStartMmdd":"07-01","blackoutEndMmdd":"07-31"}""",
        )
        val policy = api.ok(
            AssetResponse.serializer(), "POST", "/v1/assets/$ups/health-policy",
            """{"healthAggregation":"TRACK_ONE","healthPrimarySubjectId":"$battery"}""",
        ).asset
        assertEquals(listOf("TRACK_ONE", battery), listOf(policy.healthAggregation, policy.healthPrimarySubjectId))

        val row = json(call("GET", "/v1/assets/$ups")).getValue("asset").jsonObject
        assertEquals(
            listOf(
                JsonPrimitive("YEAR_ROUND"), JsonPrimitive("07-01"), JsonPrimitive("07-31"),
                JsonPrimitive("TRACK_ONE"), JsonPrimitive(battery),
            ),
            listOf("seasonMode", "blackoutStartMmdd", "blackoutEndMmdd", "healthAggregation", "healthPrimarySubjectId").map { row.getValue(it) },
        )
        assertEquals(JsonNull, row["seasonStartMmdd"])
        assertEquals(JsonNull, row["seasonEndMmdd"])
    }
}
