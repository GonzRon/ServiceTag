package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.meterDefinitionOf
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #77 (C13, R77-17) — every write route that reaches a row of an asset transferred out from this phone answers
 * **409 `asset_transferred_out`**, its `problems` naming the held asset, and writes nothing: one route per family,
 * `POST /v1/assets` with a held parent included (rm-6). The asset still reads ACTIVE — held-but-ACTIVE, the state
 * merged transfer history can leave — so ARCHIVED is not what refuses it. A body that fails its own validation
 * still answers its 422 first: the guard fires at the write.
 */
class TransferRefusalRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-09-01")
        today = LocalDate.parse("2026-09-20")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private lateinit var heater: String
    private lateinit var event: String
    private lateinit var definition: String

    /**
     * "Example Water Heater" through the API, a reading and a note on it, then its OUT — marking's order. [archived]
     * archives it first through the API, as marking leaves it.
     */
    private fun holdTheHeater(archived: Boolean = false) = runBlocking {
        heater = api.asset("Example Water Heater")
        if (archived) api.ok(AssetResponse.serializer(), "POST", "/v1/assets/$heater/archive", """{"archived":true}""")
        graph.definitions.upsert(meterDefinitionOf("d-heater", assetId = heater))
        definition = "d-heater"
        event = graph.logEvent.run(
            EventCommand(
                assetId = AssetId(heater), profileId = null, kind = EventKind.NOTE, title = "Example note",
                occurredOn = "2026-09-10", occurredTime = null, tzId = "UTC", notes = "", values = emptyMap(),
                consumables = emptyList(),
            ),
        ).id.value
        graph.transferRecords.append(
            TransferRecord(
                id = "out-1", assetId = AssetId(heater), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example Water Heater", note = "",
            ),
        )
    }

    private fun everyRow() = runBlocking {
        listOf(
            graph.assets.all().toSet(), graph.events.all().toSet(), graph.definitions.all().toSet(),
            graph.groups.all().toSet(), graph.schedules.all().toSet(), graph.conditions.all().toSet(),
            graph.references.all().toSet(), graph.healthSubjects.all().toSet(), graph.serviceCases.all().toSet(),
            graph.loans.all().toSet(),
        )
    }

    @Test fun everyWriteFamilyAnswers409AndWritesNothing() {
        holdTheHeater()
        val before = everyRow()
        val families = listOf(
            "POST /v1/assets (a held parent)" to Triple("POST", "/v1/assets", """{"name":"Example Thermostat","parentAssetId":"$heater"}"""),
            "PATCH /v1/assets/{id}" to Triple("PATCH", "/v1/assets/$heater", """{"name":"Example Water Heater 2"}"""),
            "POST /v1/assets/{id}/retire" to Triple("POST", "/v1/assets/$heater/retire", """{"retiredOn":"2026-09-15"}"""),
            "POST /v1/assets/{id}/conditions" to Triple("POST", "/v1/assets/$heater/conditions", """{"condition":"DEGRADED","tzId":"UTC"}"""),
            "POST /v1/groups" to Triple("POST", "/v1/groups", """{"name":"Example Flush Round","members":[{"assetId":"$heater"}]}"""),
            "POST /v1/schedules" to Triple(
                "POST", "/v1/schedules",
                """{"title":"Flush the tank","targetAssetId":"$heater","timeInterval":1,"timeUnit":"MONTH","anchorOn":"2026-09-05"}""",
            ),
            "POST /v1/definitions/{id}/archive" to Triple("POST", "/v1/definitions/$definition/archive", """{"archived":true}"""),
            "DELETE /v1/events/{id}" to Triple("DELETE", "/v1/events/$event", ""),
            "POST /v1/references" to Triple(
                "POST", "/v1/references", """{"assetId":"$heater","uri":"https://example.com/heater","displayName":"Example page"}""",
            ),
            "POST /v1/health-subjects" to Triple(
                "POST", "/v1/health-subjects",
                """{"assetId":"$heater","name":"Example anode age","kind":"PART","driver":"AGE","nominalUntilDays":0,"warningFromDays":30,"criticalFromDays":60}""",
            ),
            "POST /v1/service-cases" to Triple(
                "POST", "/v1/service-cases",
                """{"assetId":"$heater","title":"Example claim","type":"REPAIR","openedOn":"2026-09-19","coverage":"UNKNOWN"}""",
            ),
            "POST /v1/loans" to Triple(
                "POST", "/v1/loans", """{"assetId":"$heater","borrowerName":"Sample Borrower","lentOn":"2026-09-10"}""",
            ),
        )
        for ((family, call) in families) {
            val (method, path, body) = call
            val response = api.call(method, path, body)
            assertEquals("$family: ${response.bodyText()}", 409, response.status)
            val detail = response.errorDetail()
            assertEquals(family, "asset_transferred_out", detail.code)
            assertEquals(family, listOf("AssetTransferredOut(assetId=$heater)"), detail.problems)
        }
        assertEquals("a refused write writes nothing", before, everyRow())
    }

    /**
     * mn-1 (fix round 1; R77-17 "every mutation answers 409"): a full-body `PATCH` whose one change is the category,
     * and an archive of a held asset marking already archived, are mutations too — 409, nothing written.
     */
    @Test fun aCategoryOnlyPatchAndARepeatedArchiveAnswer409() {
        holdTheHeater(archived = true)
        val before = everyRow()
        for ((family, call) in listOf(
            "PATCH /v1/assets/{id} (the category alone)" to Triple("PATCH", "/v1/assets/$heater", """{"name":"Example Water Heater","category":"Appliance"}"""),
            "POST /v1/assets/{id}/archive (already archived)" to Triple("POST", "/v1/assets/$heater/archive", """{"archived":true}"""),
        )) {
            val (method, path, body) = call
            val response = api.call(method, path, body)
            assertEquals("$family: ${response.bodyText()}", 409, response.status)
            assertEquals(family, "asset_transferred_out", response.errorDetail().code)
        }
        assertEquals("a refused write writes nothing", before, everyRow())
    }

    /** Validation first: a body that is wrong answers its own 422 on a held asset, never the 409. */
    @Test fun aMalformedBodyStillAnswersItsOwn422() {
        holdTheHeater()
        val response = api.call("PATCH", "/v1/assets/$heater", """{"name":"   "}""")
        assertEquals(response.bodyText(), 422, response.status)
        assertEquals("asset_validation", response.errorDetail().code)
    }
}
