package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.ServiceCaseDto
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseId
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
 * #79b (C24; R79-5, R79-7, R79-18) — Part B's five routes over the production router, handlers and
 * serializers.
 *
 * `GET /v1/assets/{id}/service-cases` and `GET /v1/service-cases/{id}` read; `POST /v1/service-cases`,
 * `PATCH /v1/service-cases/{id}` and `POST /v1/service-cases/{id}/entries` each call one use case. A case
 * answers as the archive's own `ServiceCaseDto`, an entry as `ServiceCaseEntryDto`. The header's status
 * and `closedOn` are in no request: they move only through a status entry, so a `PATCH` naming either is
 * the unknown-field 400 and writes nothing. The API applies none of the phone's form defaults.
 */
class ServiceCaseRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-09-01")
        today = LocalDate.parse("2026-09-20")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private fun heater(currency: String? = null): String {
        val money = currency?.let { ""","currency":"$it"""" } ?: ""
        return api.ok(
            AssetResponse.serializer(), "POST", "/v1/assets",
            """{"name":"Example Heater","warrantyExpiresOn":"2026-12-31"$money}""", status = 201,
        ).asset.id
    }

    private fun event(asset: String, kind: String, title: String, on: String = "2026-09-10"): String =
        api.ok(
            EventResponse.serializer(), "POST", "/v1/events",
            """{"assetId":"$asset","kind":"$kind","title":"$title","occurredOn":"$on","tzId":"UTC"}""", status = 201,
        ).event.id

    private fun openBody(asset: String, extra: String = ""): String =
        """{"assetId":"$asset","title":"Heater claim","type":"WARRANTY_SERVICE","openedOn":"2026-09-12","coverage":"IN_WARRANTY"$extra}"""

    private fun open(asset: String, extra: String = ""): ServiceCaseDto =
        api.ok(ServiceCaseResponse.serializer(), "POST", "/v1/service-cases", openBody(asset, extra), status = 201).serviceCase

    private fun entry(case: String, body: String): CaseEntryResponse =
        api.ok(CaseEntryResponse.serializer(), "POST", "/v1/service-cases/$case/entries", body, status = 201)

    private fun stored(id: String): ServiceCase = runBlocking { graph.serviceCases.get(ServiceCaseId(id)) }!!

    private fun entryCount(): Int = runBlocking { graph.serviceCaseEntries.all().size }

    /**
     * The open: 201 `{serviceCase}`, OPEN with no `closedOn`, the row as stored; with an Incident of this
     * asset or without one (R79-3: the Incident is the phone's rule, not the API's). Exactly the fields
     * sent are stored — no coverage suggestion, no currency from the asset. The required keys have no
     * default, an unknown enum name is the shipped 400, and a missing asset the shipped 404.
     */
    @Test fun postServiceCaseOpensAnOpenCaseWithExactlyWhatWasSent() {
        val asset = heater(currency = "EUR")
        val incident = event(asset, "INCIDENT", "Will not heat")

        val opened = open(
            asset,
            ""","provider":"Northwind Service","caseRef":"RMA-0001","costMinor":0,"currency":"EUR","incidentEventId":"$incident"""",
        )
        assertEquals("OPEN", opened.status)
        assertNull(opened.closedOn)
        assertEquals(incident, opened.incidentEventId)
        assertEquals("RMA-0001", opened.caseRef)
        assertEquals("zero is no charge, and is kept", 0L, opened.costMinor)
        assertEquals("the answer is the row as stored", stored(opened.id).toDto(), opened)

        val bare = open(asset)
        assertNull("an Incident-less case is accepted", bare.incidentEventId)
        assertNull("no cost is none, not zero", bare.costMinor)
        assertNull("no currency comes from the asset", bare.currency)
        assertEquals("the coverage sent, never a suggestion", "IN_WARRANTY", bare.coverage)

        val outOfWarranty = api.ok(
            ServiceCaseResponse.serializer(), "POST", "/v1/service-cases",
            """{"assetId":"$asset","title":"Pump","type":"REPAIR","openedOn":"2026-09-12","coverage":"OUT_OF_WARRANTY"}""",
            status = 201,
        ).serviceCase
        assertEquals("OUT_OF_WARRANTY", outOfWarranty.coverage)

        val costOnly = api.call("POST", "/v1/service-cases", openBody(asset, ""","costMinor":1500"""))
        assertEquals("a cost needs its own currency, whatever the asset's: ${costOnly.bodyText()}", 422, costOnly.status)
        assertEquals(listOf("CostWithoutCurrency"), costOnly.errorDetail().problems)

        for (body in listOf(
            """{"assetId":"$asset","title":"x","type":"REPAIR","openedOn":"2026-09-12"}""",
            """{"assetId":"$asset","title":"x","coverage":"UNKNOWN","openedOn":"2026-09-12"}""",
            openBody(asset).replace("WARRANTY_SERVICE", "WARRANTY"),
            openBody(asset).replace("IN_WARRANTY", "COVERED"),
            openBody(asset, ""","status":"CLOSED""""),
        )) {
            val refused = api.call("POST", "/v1/service-cases", body)
            assertEquals("$body: ${refused.bodyText()}", 400, refused.status)
        }
        val missing = api.call("POST", "/v1/service-cases", openBody("no-such-asset"))
        assertEquals(404, missing.status)
        assertEquals("no_such_asset", missing.errorDetail().code)
        assertEquals("a refused open writes nothing", 3, runBlocking { graph.serviceCases.all().size })
    }

    /**
     * The two reads: an asset's cases newest opened first, then by id, as the archive rows; one case with
     * its whole timeline in the timeline's order `(occurredOn, occurredTime nulls first, createdAt, id)`.
     * A missing asset is `no_such_asset`, a missing case `no_such_service_case`; the asset's new
     * sub-resource answers 404 for a verb it does not take, as every one of them does.
     */
    @Test fun theTwoReadsAnswerTheRowsAndTheTimelineInOrder() {
        val asset = heater()
        val older = open(asset, "").id
        val newer = api.ok(
            ServiceCaseResponse.serializer(), "POST", "/v1/service-cases",
            openBody(asset).replace("2026-09-12", "2026-09-15"), status = 201,
        ).serviceCase.id

        val listed = api.ok(ServiceCaseListResponse.serializer(), "GET", "/v1/assets/$asset/service-cases").serviceCases
        assertEquals(listOf(newer, older), listed.map { it.id })
        assertEquals(stored(newer).toDto(), listed.first())
        assertEquals(
            """{"serviceCases":[]}""",
            api.call("GET", "/v1/assets/${heater()}/service-cases").bodyText(),
        )

        graph.now += 1_000
        entry(older, """{"occurredOn":"2026-09-18","occurredTime":"09:30","tzId":"UTC","note":"Courier booked"}""")
        graph.now += 1_000
        entry(older, """{"occurredOn":"2026-09-14","tzId":"UTC","note":"Called the provider"}""")
        graph.now += 1_000
        entry(older, """{"occurredOn":"2026-09-18","tzId":"UTC","note":"Label printed"}""")

        val detail = api.ok(ServiceCaseDetailResponse.serializer(), "GET", "/v1/service-cases/$older")
        assertEquals(stored(older).toDto(), detail.serviceCase)
        assertEquals(listOf("Called the provider", "Label printed", "Courier booked"), detail.entries.map { it.note })
        assertEquals(listOf("UTC"), detail.entries.map { it.tzId }.distinct())

        for ((path, code) in listOf(
            "/v1/assets/no-such-asset/service-cases" to "no_such_asset",
            "/v1/service-cases/no-such-case" to "no_such_service_case",
        )) {
            val missing = api.call("GET", path)
            assertEquals("$path: ${missing.bodyText()}", 404, missing.status)
            assertEquals(code, missing.errorDetail().code)
        }
        assertEquals(404, api.call("POST", "/v1/assets/$asset/service-cases", openBody(asset)).status)
    }

    /**
     * R79-5: the `PATCH` is a full replace of the header's command fields only. An optional field left out
     * is cleared — the repair link included, since `resolutionEventId` has no default — while the status,
     * `closedOn`, the asset and the Incident never move. A body naming `status`, `closedOn`, `assetId` or
     * `incidentEventId` is the unknown-field 400 and writes nothing; the stored header sent again writes
     * nothing either.
     */
    @Test fun patchReplacesTheHeaderAndNeverMovesStatusOrClosedOn() {
        val asset = heater()
        val incident = event(asset, "INCIDENT", "Will not heat")
        val repair = event(asset, "MAINTENANCE", "Element replaced", on = "2026-09-19")
        val case = open(asset, ""","incidentEventId":"$incident","provider":"Northwind Service"""").id
        entry(case, """{"occurredOn":"2026-09-19","tzId":"UTC","note":"Fixed","status":"CLOSED"}""")
        val closed = stored(case)

        graph.now += 86_400_000L
        val linked = api.ok(
            ServiceCaseResponse.serializer(), "PATCH", "/v1/service-cases/$case",
            """{"title":"Heater claim, element","type":"WARRANTY_SERVICE","openedOn":"2026-09-12","coverage":"PARTLY_COVERED",
               "resolutionEventId":"$repair","costMinor":4500,"currency":"EUR"}""",
        ).serviceCase
        assertEquals("Heater claim, element", linked.title)
        assertEquals("PARTLY_COVERED", linked.coverage)
        assertEquals(repair, linked.resolutionEventId)
        assertEquals("a field left out is cleared", "", linked.provider)
        assertEquals("CLOSED", linked.status)
        assertEquals("2026-09-19", linked.closedOn)
        assertEquals(incident, linked.incidentEventId)
        assertEquals(closed.assetId.value, linked.assetId)
        assertEquals(stored(case).toDto(), linked)

        val stamp = stored(case).updatedAt
        graph.now += 86_400_000L
        val again = api.ok(
            ServiceCaseResponse.serializer(), "PATCH", "/v1/service-cases/$case",
            """{"title":"Heater claim, element","type":"WARRANTY_SERVICE","openedOn":"2026-09-12","coverage":"PARTLY_COVERED",
               "resolutionEventId":"$repair","costMinor":4500,"currency":"EUR"}""",
        ).serviceCase
        assertEquals("the stored header sent again writes nothing", stamp, again.updatedAt)

        val unlinked = api.ok(
            ServiceCaseResponse.serializer(), "PATCH", "/v1/service-cases/$case",
            """{"title":"Heater claim, element","type":"WARRANTY_SERVICE","openedOn":"2026-09-12","coverage":"PARTLY_COVERED"}""",
        ).serviceCase
        assertNull("an omitted resolutionEventId removes the repair link", unlinked.resolutionEventId)
        assertNull("and so does an omitted cost", unlinked.costMinor)

        val before = stored(case)
        val header = """"title":"x","type":"REPAIR","openedOn":"2026-09-12","coverage":"UNKNOWN""""
        for (extra in listOf(
            """"status":"OPEN"""", """"closedOn":null""", """"closedOn":"2026-09-20"""",
            """"assetId":"$asset"""", """"incidentEventId":null""", """"updatedAt":1""",
        )) {
            val refused = api.call("PATCH", "/v1/service-cases/$case", "{$header,$extra}")
            assertEquals("$extra: ${refused.bodyText()}", 400, refused.status)
        }
        assertEquals("a refused body writes nothing", before, stored(case))

        val missing = api.call("PATCH", "/v1/service-cases/no-such-case", "{$header}")
        assertEquals(404, missing.status)
        assertEquals("no_such_service_case", missing.errorDetail().code)
    }

    /**
     * The timeline: 201 `{serviceCase, entry}`. A note-only entry writes the entry alone — the header, its
     * `updatedAt` included, is untouched; a status entry moves the header's status, CLOSED or CANCELLED
     * setting `closedOn` to the entry's date and any other clearing it. The entry route takes `POST` only.
     */
    @Test fun anEntryIsAppendedAndOnlyAStatusMovesTheHeader() {
        val asset = heater()
        val case = open(asset).id
        val opened = stored(case)

        graph.now += 60_000
        val note = entry(case, """{"occurredOn":"2026-09-13","tzId":"UTC","note":"Called the provider"}""")
        assertEquals("a note leaves the header exactly as it was", opened.toDto(), note.serviceCase)
        assertEquals(opened, stored(case))
        assertNull(note.entry.status)
        assertEquals(case, note.entry.caseId)

        graph.now += 60_000
        val sent = entry(case, """{"occurredOn":"2026-09-14","occurredTime":"08:15","tzId":"UTC","status":"SENT_OUT"}""")
        assertEquals("SENT_OUT", sent.serviceCase.status)
        assertEquals("SENT_OUT", sent.entry.status)
        assertEquals("", sent.entry.note)
        assertEquals(graph.now, stored(case).updatedAt)

        val cancelled = entry(case, """{"occurredOn":"2026-09-16","tzId":"UTC","note":"Withdrawn","status":"CANCELLED"}""")
        assertEquals("CANCELLED", cancelled.serviceCase.status)
        assertEquals("2026-09-16", cancelled.serviceCase.closedOn)
        val reopened = entry(case, """{"occurredOn":"2026-09-17","tzId":"UTC","status":"OPEN"}""")
        assertEquals("OPEN", reopened.serviceCase.status)
        assertNull(reopened.serviceCase.closedOn)
        assertEquals(stored(case).toDto(), reopened.serviceCase)
        assertEquals(4, entryCount())

        for (method in listOf("GET", "PATCH", "DELETE")) {
            assertEquals(method, 405, api.call(method, "/v1/service-cases/$case/entries", if (method == "GET") "" else "{}").status)
        }
        val missing = api.call("POST", "/v1/service-cases/no-such-case/entries", """{"occurredOn":"2026-09-13","tzId":"UTC","note":"x"}""")
        assertEquals(404, missing.status)
        assertEquals("no_such_service_case", missing.errorDetail().code)
        val badStatus = api.call("POST", "/v1/service-cases/$case/entries", """{"occurredOn":"2026-09-13","tzId":"UTC","status":"DONE"}""")
        assertEquals(badStatus.bodyText(), 400, badStatus.status)
        assertEquals(4, entryCount())
    }

    /**
     * The family: one lower-snake code for every problem, `field` the body key the first problem is about,
     * `problems` every problem by the domain's own name, first problem first; a refusal writes nothing.
     */
    @Test fun theFamilysCodeAndFields() {
        val asset = heater()
        val other = heater()
        val incident = event(asset, "INCIDENT", "Will not heat")
        val foreign = event(other, "INCIDENT", "Leak")
        val case = open(asset).id
        val before = runBlocking { graph.serviceCases.all() }

        for ((body, expected) in openRefusals(asset, foreign, incident)) {
            val refused = api.call("POST", "/v1/service-cases", body)
            assertEquals("$body: ${refused.bodyText()}", 422, refused.status)
            assertEquals(body, expected, refused.errorDetail())
        }
        val header = """"title":"Heater claim","type":"WARRANTY_SERVICE","openedOn":"2026-09-12","coverage":"IN_WARRANTY""""
        val resolution = api.call("PATCH", "/v1/service-cases/$case", """{$header,"resolutionEventId":"$incident"}""")
        assertEquals(
            ApiErrorDetail(
                "service_case_validation",
                "resolutionEventId must name a MAINTENANCE or REPLACEMENT event of this case's asset",
                listOf("ResolutionInvalid(eventId=EventId(value=$incident))"),
                "resolutionEventId",
            ),
            resolution.errorDetail(),
        )
        assertEquals(before, runBlocking { graph.serviceCases.all() })

        for ((body, expected) in entryRefusals()) {
            val refused = api.call("POST", "/v1/service-cases/$case/entries", body)
            assertEquals("$body: ${refused.bodyText()}", 422, refused.status)
            assertEquals(body, expected, refused.errorDetail())
        }
        assertEquals("a refused entry writes nothing", 0, entryCount())
        assertEquals(before, runBlocking { graph.serviceCases.all() })
    }

    /** `/v1/status` counts both tables, under the archive's own list names. */
    @Test fun statusCountsTheCasesAndTheirEntries() {
        val asset = heater()
        val case = open(asset).id
        open(asset)
        entry(case, """{"occurredOn":"2026-09-13","tzId":"UTC","note":"Called the provider"}""")
        entry(case, """{"occurredOn":"2026-09-14","tzId":"UTC","status":"SENT_OUT"}""")
        entry(case, """{"occurredOn":"2026-09-15","tzId":"UTC","status":"RETURNED"}""")

        val counts = api.ok(StatusResponse.serializer(), "GET", "/v1/status").counts
        assertEquals(2, counts["serviceCases"])
        assertEquals(3, counts["serviceCaseEntries"])
    }

    /**
     * The contract names the five routes, each on exactly one anchored row, every row of the family with
     * the sentence the wire actually sends, the case's 404, and the numbers the build now carries.
     */
    @Test fun theApiDocumentNamesTheFiveRoutesAndTheFamily() {
        val text = repoFile("docs/api/v1.md").readText()
        assertEquals(
            5,
            Regex("""^\| `(GET|POST|PATCH)` \| `/v1/(assets/\{id\}/service-cases|service-cases)""", RegexOption.MULTILINE)
                .findAll(text).count(),
        )
        for (row in listOf(
            """^\| `GET` \| `/v1/assets/\{id\}/service-cases` \|""",
            """^\| `POST` \| `/v1/service-cases` \|""",
            """^\| `GET` \| `/v1/service-cases/\{id\}` \|""",
            """^\| `PATCH` \| `/v1/service-cases/\{id\}` \|""",
            """^\| `POST` \| `/v1/service-cases/\{id\}/entries` \|""",
            """^\| 404 \| `no_such_service_case` \|""",
        )) {
            assertEquals(row, 1, Regex(row, RegexOption.MULTILINE).findAll(text).count())
        }
        val asset = heater()
        val other = heater()
        val incident = event(asset, "INCIDENT", "Will not heat")
        val foreign = event(other, "INCIDENT", "Leak")
        val case = open(asset).id
        val wire = openRefusals(asset, foreign, incident).map { it.second } +
            api.call("PATCH", "/v1/service-cases/$case", """{"title":"x","type":"REPAIR","openedOn":"2026-09-12","coverage":"UNKNOWN","resolutionEventId":"$incident"}""").errorDetail() +
            entryRefusals().map { it.second }
        for (detail in wire) {
            val problem = detail.problems.first().replace(Regex("""EventId\(value=[^)]*\)"""), "EventId(value=…)")
            val line = "| 422 | `${detail.code}` | `$problem` | `${detail.field}` | `${detail.message}` |"
            assertTrue(
                "docs/api/v1.md is missing the row: $line",
                Regex("^" + Regex.escape(line) + "$", RegexOption.MULTILINE).containsMatchIn(text),
            )
        }
        assertTrue("the status line says 12 since #79 (service cases)", "12 since #79 (service cases)" in text)
        for (key in listOf("serviceCases", "serviceCaseEntries", "caseEntries", "ServiceCaseDto", "ServiceCaseEntryDto")) {
            assertTrue("docs/api/v1.md does not name $key", "`$key`" in text)
        }
    }

    /** Every open refusal the family can send, with the envelope the wire must carry for it. */
    private fun openRefusals(asset: String, foreign: String, incident: String): List<Pair<String, ApiErrorDetail>> {
        fun body(title: String = "Heater claim", openedOn: String = "2026-09-12", extra: String = "") =
            """{"assetId":"$asset","title":"$title","type":"REPAIR","openedOn":"$openedOn","coverage":"UNKNOWN"$extra}"""
        fun refusal(message: String, field: String, vararg problems: String) =
            ApiErrorDetail("service_case_validation", message, problems.toList(), field)
        return listOf(
            body(title = " ", extra = ""","costMinor":-1""") to refusal(
                "a service case needs a title", "title", "TitleRequired", "CostWithoutCurrency", "NegativeCost",
            ),
            body(openedOn = "12/09/2026") to refusal("openedOn must be an ISO YYYY-MM-DD date", "openedOn", "BadDate(field=openedOn)"),
            body(openedOn = "2026-09-21") to refusal("openedOn may not be later than today", "openedOn", "OpenedAfterToday"),
            body(extra = ""","costMinor":-5,"currency":"EUR"""") to refusal("costMinor may not be negative", "costMinor", "NegativeCost"),
            body(extra = ""","costMinor":5""") to refusal("a costMinor needs a currency", "currency", "CostWithoutCurrency"),
            body(extra = ""","currency":"euro"""") to refusal("currency must be an ISO 4217 code this build knows", "currency", "BadCurrency"),
            body(extra = ""","incidentEventId":"$foreign"""") to refusal(
                "incidentEventId must name an INCIDENT of this asset that no schedule completion logged",
                "incidentEventId", "IncidentInvalid(eventId=EventId(value=$foreign))",
            ),
            body(extra = ""","resolutionEventId":"$incident"""") to refusal(
                "resolutionEventId must name a MAINTENANCE or REPLACEMENT event of this case's asset",
                "resolutionEventId", "ResolutionInvalid(eventId=EventId(value=$incident))",
            ),
        )
    }

    /** Every entry refusal the family can send. */
    private fun entryRefusals(): List<Pair<String, ApiErrorDetail>> {
        fun refusal(message: String, field: String, vararg problems: String) =
            ApiErrorDetail("service_case_validation", message, problems.toList(), field)
        return listOf(
            """{"occurredOn":"2026-09-13","tzId":"UTC","note":"  "}""" to
                refusal("an entry needs a note, a status, or both", "note", "EntryEmpty"),
            """{"occurredOn":"13 Sep","tzId":"UTC","note":"x"}""" to
                refusal("occurredOn must be an ISO YYYY-MM-DD date", "occurredOn", "BadDate(field=occurredOn)"),
            """{"occurredOn":"2026-09-13","occurredTime":"9am","tzId":"UTC","note":"x"}""" to
                refusal("occurredTime must be an HH:MM time of day", "occurredTime", "BadTime(field=occurredTime)"),
            """{"occurredOn":"2026-09-13","tzId":"Mars/Olympus_Mons","note":"x"}""" to
                refusal("tzId must be a time zone id this phone knows", "tzId", "BadTimeZone(field=tzId)"),
            """{"occurredOn":"2026-09-21","tzId":"UTC","note":"x"}""" to
                refusal("occurredOn may not be later than today", "occurredOn", "EntryAfterToday"),
        )
    }
}
