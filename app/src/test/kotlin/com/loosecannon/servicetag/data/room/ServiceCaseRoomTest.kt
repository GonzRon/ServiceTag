package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseEntryId
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.usecase.AddServiceCaseEntry
import com.loosecannon.servicetag.core.usecase.CaseEntryCommand
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two #79 case tables through the production adapters (C13; R79-4, R79-8): a header round-trips
 * field for field with its two soft event links dangling — no foreign key holds either — and an
 * **entry id already held aborts**, leaving the stored entry exactly as it was: the timeline is
 * append-only in the schema as it is in the port. The names are fictional.
 */
class ServiceCaseRoomTest {

    private val db = inMemoryDb()
    private val assets = RoomAssetRepository(db.assetDao())
    private val cases = RoomServiceCaseRepository(db.serviceCaseDao())
    private val entries = RoomServiceCaseEntryRepository(db.serviceCaseEntryDao())

    @After fun close() = db.close()

    private fun caseOf(id: String, openedOn: String = "2026-09-20") = ServiceCase(
        id = ServiceCaseId(id), assetId = AssetId("a1"), title = "Example Heater claim", type = CaseType.WARRANTY_SERVICE,
        openedOn = openedOn, closedOn = null, provider = "Northwind Service", contact = "0100 000 000",
        caseRef = "RMA-0001", coverage = CaseCoverage.PARTLY_COVERED, status = CaseStatus.SENT_OUT,
        outboundTracking = "TRK-1", outboundCarrier = "Parcel Co", returnTracking = "", returnCarrier = "",
        costMinor = 0L, currency = "EUR", notes = "Photos sent",
        incidentEventId = EventId("e-never-stored"), resolutionEventId = EventId("e-gone"), createdAt = 100L, updatedAt = 150L,
    )

    private fun entryOf(id: String, note: String, on: String = "2026-09-21", time: String? = null, createdAt: Long = 200L) =
        ServiceCaseEntry(ServiceCaseEntryId(id), ServiceCaseId("c1"), on, time, "UTC", note, null, createdAt)

    @Test
    fun aCaseRoundTripsWithItsSoftLinksDangling() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Heater", createdAt = 1L, updatedAt = 1L))
        cases.upsert(caseOf("c1"))
        cases.upsert(caseOf("c2", openedOn = "2026-09-22"))

        assertEquals("no event exists, and the row is stored as written", caseOf("c1"), cases.get(ServiceCaseId("c1")))
        assertEquals("newest opened first", listOf("c2", "c1"), cases.forAsset(AssetId("a1")).map { it.id.value })
        assertEquals(listOf("c2", "c1"), cases.observeForAsset(AssetId("a1")).first().map { it.id.value })

        val closed = caseOf("c1").copy(status = CaseStatus.CLOSED, closedOn = "2026-09-24", updatedAt = 300L)
        cases.upsert(closed)
        assertEquals("upsert replaces the header", closed, cases.get(ServiceCaseId("c1")))
        assertNull(cases.get(ServiceCaseId("c9")))
    }

    @Test
    fun aDuplicateEntryIdAborts() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Heater", createdAt = 1L, updatedAt = 1L))
        cases.upsert(caseOf("c1"))
        val first = entryOf("n1", "Courier booked", time = "09:30")
        entries.insert(first)
        entries.insert(entryOf("n0", "Called the provider", on = "2026-09-21", time = null, createdAt = 250L))

        val refused = runCatching { entries.insert(entryOf("n1", "Rewritten")) }

        assertTrue("the second insert of n1 is refused: ${refused.exceptionOrNull()}", refused.isFailure)
        assertEquals(
            "the stored entry is untouched, in timeline order (a null time first)",
            listOf(entryOf("n0", "Called the provider", createdAt = 250L), first),
            entries.forCase(ServiceCaseId("c1")),
        )
        assertEquals(entries.all(), entries.observeForCase(ServiceCaseId("c1")).first())
    }

    /**
     * Review MINOR-1: a header written again **after** its case has a timeline keeps every entry. The
     * DAO's upsert is update-then-insert; an `INSERT OR REPLACE` would delete the header row first, and
     * the entry table's CASCADE would take the whole timeline with it — on every status entry. Proved
     * both ways: a bare re-upsert of the header, and a status entry through the real use case over Room.
     */
    @Test
    fun rewritingAHeaderKeepsItsTimeline() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Example Heater", createdAt = 1L, updatedAt = 1L))
        cases.upsert(caseOf("c1"))
        val before = listOf(entryOf("n1", "Courier booked", time = "09:30"), entryOf("n2", "Courier collected", time = "15:00"))
        before.forEach { entries.insert(it) }

        cases.upsert(caseOf("c1").copy(title = "Example Heater claim, renamed", updatedAt = 400L))
        assertEquals("a bare header rewrite", before, entries.forCase(ServiceCaseId("c1")))

        var n = 0
        val addEntry = AddServiceCaseEntry(
            cases, entries, RoomUnitOfWork(db), IdGenerator { "n-status-${++n}" }, Clock { 500L },
            Today { LocalDate.parse("2026-09-24") },
        )
        val closed = addEntry.run(ServiceCaseId("c1"), CaseEntryCommand("2026-09-24", null, "UTC", "Repaired", CaseStatus.CLOSED))

        assertEquals(CaseStatus.CLOSED to "2026-09-24", closed.serviceCase.status to closed.serviceCase.closedOn)
        assertEquals(closed.serviceCase, cases.get(ServiceCaseId("c1")))
        assertEquals(
            "a status entry's header write keeps every entry, its own included",
            listOf("n1", "n2", "n-status-1"),
            entries.forCase(ServiceCaseId("c1")).map { it.id.value },
        )
    }
}
