package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseEntryId
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryServiceCaseEntryRepository
import com.loosecannon.servicetag.core.testing.InMemoryServiceCaseRepository
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.plainAssetOf
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * #79 (C13, C14; R79-1, R79-3, R79-5, R79-7–R79-9): the service case aggregate's three writers.
 *
 * - [OpenServiceCase] writes one header, OPEN with no `closedOn`, from an Incident of the asset or from
 *   none (the core and the API accept an Incident-less case; only the phone UI insists on one).
 * - [UpdateServiceCase] replaces the editable header in full and never moves the asset, the Incident,
 *   the status, `closedOn` or `createdAt`; an equal header writes nothing; a resolution link must be a
 *   MAINTENANCE or REPLACEMENT of the asset, except a stored dangling one sent back as it is.
 * - [AddServiceCaseEntry] appends one immutable entry; a note-only entry touches nothing else, and a
 *   status entry also moves the header's status, `closedOn` and stamp, in the same write.
 *
 * Every problem is collected before any write. No case writer touches an event, a condition or a
 * schedule; that gate is `CrossConceptWriteTest`'s. The names are fictional.
 */
class ServiceCaseUseCasesTest {

    private val assets = InMemoryAssetRepository()
    private val events = InMemoryEventRepository()
    private val entryRows = InMemoryServiceCaseEntryRepository()
    private val caseRows = InMemoryServiceCaseRepository(entryRows)
    private val uow = FakeUnitOfWork(assets, events, caseRows, entryRows)

    /** Every header write, so "writes nothing" is a count and not an inference. */
    private var caseUpserts = 0
    private val cases = object : ServiceCaseRepository by caseRows {
        override suspend fun upsert(case: ServiceCase) = caseRows.upsert(case).also { caseUpserts++ }
    }
    private var entryInserts = 0
    private val entries = object : ServiceCaseEntryRepository by entryRows {
        override suspend fun insert(entry: ServiceCaseEntry) = entryRows.insert(entry).also { entryInserts++ }
    }

    private var seq = 0
    private var fixedId: String? = null
    private val ids = IdGenerator { fixedId ?: "id-%03d".format(++seq) }
    private var now = dayMillis("2026-09-24") + 1_000L
    private val clock = Clock { now }
    private val today = Today { LocalDate.parse("2026-09-24") }

    private val open = OpenServiceCase(assets, events, cases, uow, ids, clock, today)
    private val update = UpdateServiceCase(events, cases, uow, clock, today)
    private val addEntry = AddServiceCaseEntry(cases, entries, uow, ids, clock, today)

    init {
        assets.rows["a1"] = plainAssetOf("a1", "Example Heater").copy(currency = "EUR", warrantyExpiresOn = "2027-03-01")
        assets.rows["a2"] = plainAssetOf("a2", "Example Heater two")
        event("inc-1", "a1", EventKind.INCIDENT, "No hot water", "2026-09-20")
        event("inc-other", "a2", EventKind.INCIDENT, "Leaking", "2026-09-21")
        event("maint-1", "a1", EventKind.MAINTENANCE, "Element replaced", "2026-09-23")
        event("repl-1", "a1", EventKind.REPLACEMENT, "Unit swapped", "2026-09-23")
        event("maint-other", "a2", EventKind.MAINTENANCE, "Seal replaced", "2026-09-23")
        event("note-1", "a1", EventKind.NOTE, "Called the installer", "2026-09-21")
        // A completion logged as an INCIDENT against a schedule: not a failure's Incident (`CurrentIncident.kt:32`).
        event("inc-done", "a1", EventKind.INCIDENT, "Inspection found a leak", "2026-09-22", ScheduleId("s1"))
    }

    private fun event(id: String, assetId: String, kind: EventKind, title: String, on: String, schedule: ScheduleId? = null) {
        val row: AssetEvent = HealthFixtures.eventOf(id, assetId, kind, title, on).copy(scheduleId = schedule, occurrenceOn = schedule?.let { on })
        events.rows[id] = row
    }

    private fun command(
        title: String = "Heater warranty claim",
        openedOn: String = "2026-09-24",
        coverage: CaseCoverage = CaseCoverage.IN_WARRANTY,
        resolution: EventId? = null,
        costMinor: Long? = null,
        currency: String? = null,
    ) = ServiceCaseCommand(
        title = title, type = CaseType.WARRANTY_SERVICE, openedOn = openedOn, coverage = coverage,
        resolutionEventId = resolution, provider = "Northwind Service", contact = "0100 000 000", caseRef = "RMA-0001",
        costMinor = costMinor, currency = currency,
    )

    /** A stored case, laid down directly so only the command under test writes. */
    private fun stored(
        id: String = "c1",
        status: CaseStatus = CaseStatus.OPEN,
        closedOn: String? = null,
        resolution: EventId? = null,
        incident: EventId? = EventId("inc-1"),
    ): ServiceCase = ServiceCase(
        id = ServiceCaseId(id), assetId = AssetId("a1"), title = "Heater warranty claim", type = CaseType.WARRANTY_SERVICE,
        openedOn = "2026-09-21", closedOn = closedOn, provider = "Northwind Service", contact = "0100 000 000",
        caseRef = "RMA-0001", coverage = CaseCoverage.IN_WARRANTY, status = status, outboundTracking = "",
        outboundCarrier = "", returnTracking = "", returnCarrier = "", costMinor = null, currency = null, notes = "",
        incidentEventId = incident, resolutionEventId = resolution, createdAt = 500L, updatedAt = 600L,
    ).also { caseRows.rows[id] = it }

    /** The command that restates [case] exactly. */
    private fun restating(case: ServiceCase) = ServiceCaseCommand(
        title = case.title, type = case.type, openedOn = case.openedOn, coverage = case.coverage,
        resolutionEventId = case.resolutionEventId, provider = case.provider, contact = case.contact,
        caseRef = case.caseRef, outboundTracking = case.outboundTracking, outboundCarrier = case.outboundCarrier,
        returnTracking = case.returnTracking, returnCarrier = case.returnCarrier, costMinor = case.costMinor,
        currency = case.currency, notes = case.notes,
    )

    private fun entryOf(note: String = "", status: CaseStatus? = null, on: String = "2026-09-24", time: String? = null) =
        CaseEntryCommand(occurredOn = on, occurredTime = time, tzId = "UTC", note = note, status = status)

    private suspend fun refusedOpen(cmd: ServiceCaseCommand, incident: EventId? = null): List<ServiceCaseProblem> =
        assertFailsWith<ServiceCaseValidation> { open.run(AssetId("a1"), cmd, incident) }.problems

    private suspend fun refusedEntry(cmd: CaseEntryCommand): List<ServiceCaseProblem> =
        assertFailsWith<ServiceCaseValidation> { addEntry.run(ServiceCaseId("c1"), cmd) }.problems

    // --- row 33: open ---------------------------------------------------------------------------------

    @Test
    fun openWritesOneHeaderOpenWithNoClosedOn() = runBlocking<Unit> {
        val cmd = command(title = "  Heater warranty claim  ").copy(notes = " sent photos ", outboundCarrier = " Parcel Co ")

        val opened = open.run(AssetId("a1"), cmd, EventId("inc-1"))

        val expected = ServiceCase(
            id = ServiceCaseId("id-001"), assetId = AssetId("a1"), title = "Heater warranty claim",
            type = CaseType.WARRANTY_SERVICE, openedOn = "2026-09-24", closedOn = null, provider = "Northwind Service",
            contact = "0100 000 000", caseRef = "RMA-0001", coverage = CaseCoverage.IN_WARRANTY, status = CaseStatus.OPEN,
            outboundTracking = "", outboundCarrier = "Parcel Co", returnTracking = "", returnCarrier = "",
            costMinor = null, currency = null, notes = "sent photos", incidentEventId = EventId("inc-1"),
            resolutionEventId = null, createdAt = now, updatedAt = now,
        )
        assertEquals(expected, opened)
        assertEquals(listOf(expected), caseRows.rows.values.toList(), "one header, as returned")
        assertEquals(1, caseUpserts)
        assertEquals(0, entryInserts, "opening writes no timeline entry")
        assertEquals(7, events.rows.size, "no event written")
        assertFailsWith<NoSuchAsset> { open.run(AssetId("a9"), command(), null) }
    }

    @Test
    fun anIncidentLessCaseIsAccepted() = runBlocking<Unit> {
        val opened = open.run(AssetId("a2"), command(coverage = CaseCoverage.UNKNOWN).copy(type = CaseType.REPAIR), null)

        assertNull(opened.incidentEventId)
        assertEquals(CaseStatus.OPEN, opened.status)
        assertEquals(opened, caseRows.rows.getValue(opened.id.value))
    }

    @Test
    fun theIncidentMustBeANonCompletionIncidentOfThisAsset() = runBlocking<Unit> {
        for (id in listOf("inc-other", "maint-1", "note-1", "inc-done", "gone")) {
            assertEquals(
                listOf(ServiceCaseProblem.IncidentInvalid(EventId(id))),
                refusedOpen(command(), EventId(id)),
                id,
            )
        }
        assertEquals(0, caseUpserts, "nothing written")
        assertEquals(EventId("inc-1"), open.run(AssetId("a1"), command(), EventId("inc-1")).incidentEventId)
    }

    // --- row 34: the header's rules ---------------------------------------------------------------------

    @Test
    fun titleCostAndCurrencyProblemsAreReportedTogether() = runBlocking<Unit> {
        assertEquals(
            listOf(ServiceCaseProblem.TitleRequired, ServiceCaseProblem.BadCurrency, ServiceCaseProblem.NegativeCost),
            refusedOpen(command(title = "   ", costMinor = -1, currency = "eur")),
        )
        assertEquals(
            listOf(ServiceCaseProblem.TitleRequired, ServiceCaseProblem.CostWithoutCurrency),
            refusedOpen(command(title = "", costMinor = 4_500)),
        )
        assertEquals(
            listOf(ServiceCaseProblem.BadCurrency),
            refusedOpen(command(costMinor = 4_500, currency = "ZZZ")),
            "a code no currency table knows, beside a cost",
        )
        assertEquals(
            listOf(ServiceCaseProblem.TitleRequired, ServiceCaseProblem.ResolutionInvalid(EventId("inc-1"))),
            refusedOpen(command(title = " ", resolution = EventId("inc-1"))),
        )
        assertEquals(0, uow.commits, "every refusal before any write")
        assertEquals(0, caseUpserts)
    }

    @Test
    fun openedAfterTodayIsRefused() = runBlocking<Unit> {
        assertEquals(listOf(ServiceCaseProblem.OpenedAfterToday), refusedOpen(command(openedOn = "2026-09-25")))
        assertEquals(listOf(ServiceCaseProblem.BadDate("openedOn")), refusedOpen(command(openedOn = "2026-13-01")))
        assertEquals(listOf(ServiceCaseProblem.BadDate("openedOn")), refusedOpen(command(openedOn = "")))
        assertEquals(0, caseUpserts)
        assertEquals("2026-09-24", open.run(AssetId("a1"), command(openedOn = "2026-09-24"), null).openedOn, "today itself")
    }

    @Test
    fun aZeroCostIsKeptAndANullCostIsNone() = runBlocking<Unit> {
        val free = open.run(AssetId("a1"), command(costMinor = 0, currency = "EUR", coverage = CaseCoverage.IN_WARRANTY), null)
        assertEquals(0L, free.costMinor, "no charge is a cost of zero")
        assertEquals("EUR", free.currency)

        val none = open.run(AssetId("a1"), command(), null)
        assertNull(none.costMinor, "no cost recorded")

        val paid = open.run(AssetId("a1"), command(costMinor = 12_950, currency = "EUR", coverage = CaseCoverage.PARTLY_COVERED), null)
        assertEquals(12_950L to CaseCoverage.PARTLY_COVERED, paid.costMinor to paid.coverage, "any coverage may carry a cost")
        assertEquals(listOf(0L, null, 12_950L), caseRows.rows.values.map { it.costMinor })
    }

    // --- row 35: the header's update ----------------------------------------------------------------

    @Test
    fun anUpdateNeverMovesStatusClosedOnIncidentOrAsset() = runBlocking<Unit> {
        val before = stored(status = CaseStatus.CLOSED, closedOn = "2026-09-23")
        now += 5_000L

        val cmd = restating(before).copy(title = "Heater claim, second unit", costMinor = 0, currency = "EUR", returnTracking = "TRK-2")
        val updated = update.run(ServiceCaseId("c1"), cmd)

        val expected = before.copy(
            title = "Heater claim, second unit", costMinor = 0, currency = "EUR", returnTracking = "TRK-2", updatedAt = now,
        )
        assertEquals(expected, updated)
        assertEquals(expected, caseRows.rows.getValue("c1"))
        assertEquals(1, caseUpserts)
        assertFailsWith<NoSuchServiceCase> { update.run(ServiceCaseId("c9"), cmd) }
    }

    @Test
    fun theResolutionMustBeAMaintenanceOrReplacementOfThisAsset() = runBlocking<Unit> {
        val before = stored()
        for (id in listOf("inc-1", "note-1", "maint-other", "gone")) {
            val problems = assertFailsWith<ServiceCaseValidation>(id) {
                update.run(ServiceCaseId("c1"), restating(before).copy(resolutionEventId = EventId(id)))
            }.problems
            assertEquals(listOf(ServiceCaseProblem.ResolutionInvalid(EventId(id))), problems, id)
        }
        assertEquals(before, caseRows.rows.getValue("c1"))
        assertEquals(0, caseUpserts)

        for (id in listOf("maint-1", "repl-1")) {
            val linked = update.run(ServiceCaseId("c1"), restating(before).copy(resolutionEventId = EventId(id)))
            assertEquals(EventId(id), linked.resolutionEventId, id)
            assertEquals(CaseStatus.OPEN, linked.status, "linking moves no status")
        }
        val unlinked = update.run(ServiceCaseId("c1"), restating(before).copy(resolutionEventId = null))
        assertNull(unlinked.resolutionEventId, "Remove")
    }

    @Test
    fun anUnchangedDanglingResolutionPasses() = runBlocking<Unit> {
        // The resolving event was deleted after it was linked: the case keeps a readable dangling id (R79-4).
        val before = stored(resolution = EventId("maint-deleted"))
        now += 5_000L

        val renamed = update.run(ServiceCaseId("c1"), restating(before).copy(title = "Heater claim"))
        assertEquals(before.copy(title = "Heater claim", updatedAt = now), renamed)

        assertEquals(
            listOf(ServiceCaseProblem.ResolutionInvalid(EventId("maint-deleted-too"))),
            assertFailsWith<ServiceCaseValidation> {
                update.run(ServiceCaseId("c1"), restating(before).copy(resolutionEventId = EventId("maint-deleted-too")))
            }.problems,
            "a different dangling id is not the stored one",
        )
    }

    @Test
    fun anEqualHeaderWritesNothing() = runBlocking<Unit> {
        val before = stored(resolution = EventId("maint-1"))
        now += 5_000L

        assertEquals(before, update.run(ServiceCaseId("c1"), restating(before)))
        assertEquals(before, update.run(ServiceCaseId("c1"), restating(before).copy(title = "  ${before.title} ")), "trimmed first")
        assertEquals(before, caseRows.rows.getValue("c1"), "not even the stamp")
        assertEquals(0, caseUpserts)
    }

    @Test
    fun anUpdateIsHeldToTheOpeningRules() = runBlocking<Unit> {
        val before = stored()
        assertEquals(
            listOf(ServiceCaseProblem.TitleRequired, ServiceCaseProblem.OpenedAfterToday, ServiceCaseProblem.CostWithoutCurrency),
            assertFailsWith<ServiceCaseValidation> {
                update.run(ServiceCaseId("c1"), restating(before).copy(title = "", openedOn = "2026-10-01", costMinor = 100))
            }.problems,
        )
        assertEquals(before, caseRows.rows.getValue("c1"))
    }

    // --- row 36: the timeline -----------------------------------------------------------------------

    @Test
    fun aNoteOnlyEntryLeavesTheHeaderUntouched() = runBlocking<Unit> {
        val before = stored()
        now += 5_000L

        val added = addEntry.run(ServiceCaseId("c1"), entryOf(note = "  Courier booked for Monday ", time = "09:30"))

        val entry = ServiceCaseEntry(
            ServiceCaseEntryId("id-001"), ServiceCaseId("c1"), "2026-09-24", "09:30", "UTC", "Courier booked for Monday", null, now,
        )
        assertEquals(CaseEntryAdded(before, entry), added)
        assertEquals(listOf(entry), entryRows.rows.values.toList())
        assertEquals(before, caseRows.rows.getValue("c1"), "the header, its stamp included, is untouched")
        assertEquals(0, caseUpserts)
        assertFailsWith<NoSuchServiceCase> { addEntry.run(ServiceCaseId("c9"), entryOf(note = "x")) }
    }

    @Test
    fun aStatusEntryMovesTheHeader() = runBlocking<Unit> {
        val before = stored()
        now += 5_000L

        val added = addEntry.run(ServiceCaseId("c1"), entryOf(note = "Shipped to the service centre", status = CaseStatus.SENT_OUT))

        val header = before.copy(status = CaseStatus.SENT_OUT, updatedAt = now)
        assertEquals(header, added.serviceCase)
        assertEquals(header, caseRows.rows.getValue("c1"))
        assertEquals(CaseStatus.SENT_OUT, entryRows.rows.values.single().status)
        assertEquals(1, caseUpserts)
        assertEquals(1, entryInserts)
        assertEquals(1, uow.commits, "the entry and the header in one write")

        // A status with no note is an entry too.
        val quiet = addEntry.run(ServiceCaseId("c1"), entryOf(status = CaseStatus.AT_SERVICE_CENTER))
        assertEquals(CaseStatus.AT_SERVICE_CENTER, quiet.serviceCase.status)
        assertEquals("", quiet.entry.note)
    }

    @Test
    fun closedOrCancelledSetsClosedOnToTheEntryDate() = runBlocking<Unit> {
        stored()
        val closed = addEntry.run(ServiceCaseId("c1"), entryOf(note = "Repaired under warranty", status = CaseStatus.CLOSED, on = "2026-09-22"))
        assertEquals(CaseStatus.CLOSED to "2026-09-22", closed.serviceCase.status to closed.serviceCase.closedOn)

        stored("c2")
        val cancelled = addEntry.run(ServiceCaseId("c2"), entryOf(status = CaseStatus.CANCELLED, on = "2026-09-23"))
        assertEquals(CaseStatus.CANCELLED to "2026-09-23", cancelled.serviceCase.status to cancelled.serviceCase.closedOn)
        assertEquals(cancelled.serviceCase, caseRows.rows.getValue("c2"))
    }

    @Test
    fun anyOtherStatusClearsClosedOn() = runBlocking<Unit> {
        for (status in listOf(CaseStatus.OPEN, CaseStatus.SENT_OUT, CaseStatus.AT_SERVICE_CENTER, CaseStatus.RETURNED)) {
            stored(status = CaseStatus.CLOSED, closedOn = "2026-09-22")
            val reopened = addEntry.run(ServiceCaseId("c1"), entryOf(note = "Fault came back", status = status))
            assertEquals(status to null, reopened.serviceCase.status to reopened.serviceCase.closedOn, "$status")
            assertEquals(reopened.serviceCase, caseRows.rows.getValue("c1"))
        }
    }

    @Test
    fun anEmptyEntryIsRefused() = runBlocking<Unit> {
        val before = stored()
        assertEquals(listOf(ServiceCaseProblem.EntryEmpty), refusedEntry(entryOf(note = "   ")))
        assertEquals(
            listOf(ServiceCaseProblem.EntryEmpty, ServiceCaseProblem.BadDate("occurredOn"), ServiceCaseProblem.BadTime("occurredTime")),
            refusedEntry(entryOf(on = "24-09-2026", time = "9.30")),
            "every problem at once",
        )
        assertEquals(before, caseRows.rows.getValue("c1"))
        assertTrue(entryRows.rows.isEmpty())
        assertEquals(0, uow.commits)
    }

    @Test
    fun anEntryAfterTodayIsRefused() = runBlocking<Unit> {
        val before = stored()
        assertEquals(listOf(ServiceCaseProblem.EntryAfterToday), refusedEntry(entryOf(note = "Pickup", on = "2026-09-25")))
        assertEquals(
            listOf(ServiceCaseProblem.EntryAfterToday),
            refusedEntry(entryOf(status = CaseStatus.CLOSED, on = "2026-10-01")),
            "a closing entry cannot be dated ahead either",
        )
        assertEquals(before, caseRows.rows.getValue("c1"))
        assertTrue(entryRows.rows.isEmpty())
        assertEquals("2026-09-24", addEntry.run(ServiceCaseId("c1"), entryOf(note = "Today")).entry.occurredOn, "today itself")
    }

    @Test
    fun aThrowingHeaderWriteLeavesNoEntry() = runBlocking<Unit> {
        val before = stored()
        caseRows.failOnUpsert = 1

        assertFailsWith<RiggedFailure> { addEntry.run(ServiceCaseId("c1"), entryOf(note = "Returned", status = CaseStatus.RETURNED)) }

        assertTrue(entryRows.rows.isEmpty(), "the entry rolled back with the header")
        assertEquals(before, caseRows.rows.getValue("c1"))
        assertEquals(1, uow.rollbacks)
    }

    // --- row 37: append-only -----------------------------------------------------------------------

    /**
     * The port is the rule: no member could amend or remove one entry, or remove one case. A JVM name
     * of a member taking a value class carries a mangling suffix (`forCase-…`), which is cut off.
     */
    @Test
    fun theEntryPortHasNoUpdateOrDelete() {
        fun members(port: Class<*>) = port.declaredMethods.map { it.name.substringBefore('-') }.toSet()
        assertEquals(setOf("insert", "forCase", "all", "deleteAll", "observeForCase"), members(ServiceCaseEntryRepository::class.java))
        assertEquals(
            setOf("upsert", "get", "forAsset", "all", "deleteAll", "observeForAsset"),
            members(ServiceCaseRepository::class.java),
            "a case has no delete either (R79-9): CANCELLED is the exit",
        )
    }

    @Test
    fun aDuplicateEntryIdAborts() = runBlocking<Unit> {
        stored()
        fixedId = "entry-1"
        val first = addEntry.run(ServiceCaseId("c1"), entryOf(note = "First"))

        assertFailsWith<RiggedFailure> { addEntry.run(ServiceCaseId("c1"), entryOf(note = "Second", status = CaseStatus.CLOSED)) }

        assertEquals(listOf(first.entry), entryRows.rows.values.toList(), "the first entry, as it was")
        assertEquals(CaseStatus.OPEN, caseRows.rows.getValue("c1").status, "and the refused status never reached the header")
    }
}
