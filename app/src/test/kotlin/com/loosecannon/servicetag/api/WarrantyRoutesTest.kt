package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetDto
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.warranty.WarrantyStatus
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.LocalDate

/**
 * #79 (C12; M1; R79-18) — Part A's two routes over the production router, handlers and serializers.
 *
 * `GET /v1/assets/{id}/warranty` derives the status from the stored date and **`Today`**, in a response
 * of its own, as `/health` and `/season` are; `POST /v1/assets/{id}/warranty-reminder` writes the lead
 * through `SetWarrantyReminder` alone and answers `{asset, warranty}`, as `season-mode` answers
 * `{asset, season}`. The lead is canonical and rides on the backup row the API reuses; the status never
 * does, and the lead is never a key of the asset command, so a full-replace `PATCH` keeps it while the
 * date stays and clears it with the date.
 */
class WarrantyRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-01")
        today = LocalDate.parse("2026-03-01")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private fun assetWith(name: String, warrantyExpiresOn: String?): String {
        val date = warrantyExpiresOn?.let { ""","warrantyExpiresOn":"$it"""" } ?: ""
        return api.ok(AssetResponse.serializer(), "POST", "/v1/assets", """{"name":"$name"$date}""", status = 201).asset.id
    }

    private fun warranty(id: String): WarrantyDto =
        api.ok(WarrantyResponse.serializer(), "GET", "/v1/assets/$id/warranty").warranty

    private fun setLead(id: String, leadDays: String): ApiResponse =
        api.call("POST", "/v1/assets/$id/warranty-reminder", """{"leadDays":$leadDays}""")

    private fun leadSet(id: String, leadDays: String): AssetWarrantyResponse =
        api.ok(AssetWarrantyResponse.serializer(), "POST", "/v1/assets/$id/warranty-reminder", """{"leadDays":$leadDays}""")

    private fun stored(id: String): Asset = runBlocking { graph.assets.get(AssetId(id)) }!!

    /**
     * Hazard: the status read off a clock, or the expiry day counted out. The clock is set years away,
     * so only `Today` can give these answers: the expiry day is IN, the day after is OUT, and no date
     * is NOT_RECORDED. Every field is present, `null` where absent.
     */
    @Test fun getWarrantyDerivesTheStatusWithToday() {
        val heater = assetWith("Example Heater", "2026-03-01")
        val pump = assetWith("Example Pump", null)
        graph.now = dayMillis("2031-01-01")

        assertEquals(WarrantyDto("IN_WARRANTY", "2026-03-01", null), warranty(heater))
        graph.today = LocalDate.parse("2026-03-02")
        assertEquals(WarrantyDto("OUT_OF_WARRANTY", "2026-03-01", null), warranty(heater))
        assertEquals(WarrantyDto("NOT_RECORDED", null, null), warranty(pump))
        assertEquals(
            """{"warranty":{"status":"NOT_RECORDED","expiresOn":null,"leadDays":null}}""",
            api.call("GET", "/v1/assets/$pump/warranty").bodyText(),
        )

        val missing = api.call("GET", "/v1/assets/no-such-asset/warranty")
        assertEquals(404, missing.status)
        assertEquals("no_such_asset", missing.errorDetail().code)
        // Two more sub-resources, on the shipped convention: a verb one does not take is a 404.
        assertEquals(404, api.call("POST", "/v1/assets/$heater/warranty", """{"leadDays":1}""").status)
        assertEquals(404, api.call("GET", "/v1/assets/$heater/warranty-reminder").status)
    }

    /**
     * The write answers the row as stored and the warranty now; a lead has no upper bound; `null`
     * turns it off; the key is required, so `{}` is a 400 and never a reminder silently cleared, and a
     * lead that is not a whole number is the decoder's 400. The write can run no sweep — it settles at
     * the next digest or the 12-hour backstop (R79-15) — and that is structural: the handler holds no
     * reconcile, provider or graph to reach one, which the last lines check by reflection.
     */
    @Test fun postWarrantyReminderAnswersAssetAndWarranty() {
        val heater = assetWith("Example Heater", "2026-06-30")
        graph.now = dayMillis("2026-02-02")

        val set = leadSet(heater, "30")
        assertEquals(30, set.asset.warrantyReminderLeadDays)
        assertEquals(WarrantyDto("IN_WARRANTY", "2026-06-30", 30), set.warranty)
        assertEquals("the answer is the row as stored", stored(heater).toDto(), set.asset)
        assertEquals("the write moved the row's stamp", dayMillis("2026-02-02"), stored(heater).updatedAt)
        assertEquals(30, api.ok(AssetResponse.serializer(), "GET", "/v1/assets/$heater").asset.warrantyReminderLeadDays)

        assertEquals("no upper bound", 36_500, leadSet(heater, "36500").asset.warrantyReminderLeadDays)

        val off = leadSet(heater, "null")
        assertNull(off.asset.warrantyReminderLeadDays)
        assertEquals(WarrantyDto("IN_WARRANTY", "2026-06-30", null), off.warranty)

        leadSet(heater, "14")
        val forgot = api.call("POST", "/v1/assets/$heater/warranty-reminder", "{}")
        assertEquals(forgot.bodyText(), 400, forgot.status)
        val misspelt = api.call("POST", "/v1/assets/$heater/warranty-reminder", """{"leadDays":14,"lead":3}""")
        assertEquals(misspelt.bodyText(), 400, misspelt.status)
        val fractional = api.call("POST", "/v1/assets/$heater/warranty-reminder", """{"leadDays":1.5}""")
        assertEquals(fractional.bodyText(), 400, fractional.status)
        assertEquals("a refused body writes nothing", 14, stored(heater).warrantyReminderLeadDays)

        val missing = setLead("no-such-asset", "5")
        assertEquals(404, missing.status)
        assertEquals("no_such_asset", missing.errorDetail().code)

        val held = WarrantyHandlers::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.type.simpleName }
        assertTrue("reflection sees the collaborators: $held", "SetWarrantyReminder" in held)
        assertTrue("no sweep is reachable: $held", held.none { Regex("Reconcile|ReminderRuns|ReminderProvider|AppGraph").containsMatchIn(it) })
    }

    /**
     * The family: one lower-snake code for both problems, `field` the one body key (`leadDays`), and
     * `problems` every problem by the domain's own name, first problem first. A refusal writes nothing.
     */
    @Test fun theFamilysCodeAndField() {
        val heater = assetWith("Example Heater", "2026-06-30")
        val pump = assetWith("Example Pump", null)

        val zero = setLead(heater, "0")
        assertEquals(422, zero.status)
        assertEquals(
            ApiErrorDetail(
                "warranty_reminder_validation",
                "leadDays must be a whole number of days, 1 or more, or null for no reminder",
                listOf("LeadNotPositive"),
                "leadDays",
            ),
            zero.errorDetail(),
        )
        val dateless = setLead(pump, "14")
        assertEquals(422, dateless.status)
        assertEquals(
            ApiErrorDetail(
                "warranty_reminder_validation",
                "a warranty reminder needs the asset's warrantyExpiresOn; set the date first",
                listOf("LeadWithoutDate"),
                "leadDays",
            ),
            dateless.errorDetail(),
        )
        val both = setLead(pump, "-1").errorDetail()
        assertEquals(listOf("LeadNotPositive", "LeadWithoutDate"), both.problems)
        assertEquals(zero.errorDetail().message, both.message)

        assertNull(stored(heater).warrantyReminderLeadDays)
        assertNull(stored(pump).warrantyReminderLeadDays)
        // Either way the key is `leadDays` and the code is the family's: a client branches on one code.
        assertEquals(setOf("warranty_reminder_validation"), setOf(zero, dateless).map { it.errorDetail().code }.toSet())
    }

    /**
     * K4: the lead is not on the asset command. A full-replace `PATCH` that keeps the date — renamed,
     * or with the date moved — keeps the lead; a body naming the lead is the shipped unknown-field 400.
     */
    @Test fun patchAssetWithoutTheLeadKeepsIt() {
        val heater = assetWith("Example Heater", "2026-06-30")
        leadSet(heater, "30")

        val renamed = api.ok(
            AssetResponse.serializer(), "PATCH", "/v1/assets/$heater",
            """{"name":"Example Heater, loft","warrantyExpiresOn":"2026-06-30"}""",
        )
        assertEquals(30, renamed.asset.warrantyReminderLeadDays)
        val moved = api.ok(
            AssetResponse.serializer(), "PATCH", "/v1/assets/$heater",
            """{"name":"Example Heater, loft","warrantyExpiresOn":"2027-06-30"}""",
        )
        assertEquals(30, moved.asset.warrantyReminderLeadDays)
        assertEquals(WarrantyDto("IN_WARRANTY", "2027-06-30", 30), warranty(heater))

        for ((method, path) in listOf("PATCH" to "/v1/assets/$heater", "POST" to "/v1/assets")) {
            val sent = api.call(method, path, """{"name":"Example Heater","warrantyExpiresOn":"2027-06-30","warrantyReminderLeadDays":5}""")
            assertEquals("$method $path: ${sent.bodyText()}", 400, sent.status)
        }
        assertEquals(30, stored(heater).warrantyReminderLeadDays)
        assertEquals("Example Heater, loft", stored(heater).name)
    }

    /** R79-12b: a full-replace `PATCH` without the date clears the lead with it, and a date sent back later does not restore it. */
    @Test fun patchAssetClearingTheDateClearsTheLead() {
        val heater = assetWith("Example Heater", "2026-06-30")
        leadSet(heater, "30")

        val cleared = api.ok(AssetResponse.serializer(), "PATCH", "/v1/assets/$heater", """{"name":"Example Heater"}""")
        assertNull(cleared.asset.warrantyExpiresOn)
        assertNull(cleared.asset.warrantyReminderLeadDays)
        assertEquals(WarrantyDto("NOT_RECORDED", null, null), warranty(heater))

        val dated = api.ok(
            AssetResponse.serializer(), "PATCH", "/v1/assets/$heater",
            """{"name":"Example Heater","warrantyExpiresOn":"2026-06-30"}""",
        )
        assertNull(dated.asset.warrantyReminderLeadDays)
    }

    /**
     * M1: the derived status is never a field of `core.backup.AssetDto` — the row an archive carries
     * and every asset response reuses — while the canonical lead is. Checked by reflection and on the
     * wire.
     */
    @Test fun theBackupAssetDtoCarriesNoStatus() {
        val fields = AssetDto::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertTrue("no field holds a WarrantyStatus", fields.none { it.type == WarrantyStatus::class.java })
        val names = AssetDto.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName) }
        assertTrue("the lead is canonical", "warrantyReminderLeadDays" in names)
        assertEquals(
            "a derived warranty value on the backup row",
            emptyList<String>(),
            names.filter { name -> listOf("warrantystatus", "inwarranty", "expired").any { it in name.lowercase() } },
        )

        val heater = assetWith("Example Heater", "2026-06-30")
        leadSet(heater, "30")
        for (path in listOf("/v1/assets/$heater", "/v1/assets")) {
            val body = api.call("GET", path).bodyText()
            assertTrue(path, "\"warrantyReminderLeadDays\":30" in body)
            assertFalse(path, "IN_WARRANTY" in body || "warrantyStatus" in body)
        }
    }

    /**
     * The contract names both routes, each on exactly one anchored table row, and every row of the
     * family with the sentence the wire actually sends — so the document drifts from the code only with
     * this red.
     */
    @Test fun theApiDocumentNamesBothRoutesAndTheFamily() {
        val text = repoFile("docs/api/v1.md").readText()
        for (row in listOf(
            """^\| `GET` \| `/v1/assets/\{id\}/warranty` \|""",
            """^\| `POST` \| `/v1/assets/\{id\}/warranty-reminder` \|""",
        )) {
            assertEquals(row, 1, Regex(row, RegexOption.MULTILINE).findAll(text).count())
        }
        val heater = assetWith("Example Heater", "2026-06-30")
        val pump = assetWith("Example Pump", null)
        for ((problem, response) in listOf("LeadNotPositive" to setLead(heater, "0"), "LeadWithoutDate" to setLead(pump, "3"))) {
            val detail = response.errorDetail()
            val line = "| 422 | `${detail.code}` | `$problem` | `${detail.field}` | `${detail.message}` |"
            assertTrue(
                "docs/api/v1.md is missing the $problem row: $line",
                Regex("^" + Regex.escape(line) + "$", RegexOption.MULTILINE).containsMatchIn(text),
            )
        }
        assertTrue("the lead is a documented asset row field", "`warrantyReminderLeadDays`" in text)
        assertTrue("an API-set lead settles at the next digest or the backstop", "12-hour backstop" in text)
    }
}
