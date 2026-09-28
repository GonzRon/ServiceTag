package com.loosecannon.servicetag.ui.service

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseEntryId
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.ui.asset.LINKED_RECORD_REMOVED
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import com.loosecannon.servicetag.ui.condition.EntryOffers
import com.loosecannon.servicetag.ui.condition.EventOffers
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #79 (C22, AC 9, 10, 15; R79-8–R79-10; §3 row 48): the case screen's view model over a Room-backed
 * [FakeGraph] and the real case writers. The timeline is in its order and append-only; an update writes
 * one entry; the sheet's save needs a note or a status; its refusals are the shipped lines; closing
 * shows Closed on and writes no condition or event; the picker lists only this asset's MAINTENANCE and
 * REPLACEMENT events; a link or its removal writes the header alone and asks nothing; a gone event
 * reads S24.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceCaseViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private val store = ViewModelStore()
    private val berlin = ZoneId.of("Europe/Berlin")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2028-07-15")
    }

    @After fun tearDown() {
        store.clear()
        graph.close()
        Dispatchers.resetMain()
    }

    private fun <T : ViewModel> held(model: T): T = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = model as V
        },
    )["${model::class.java.name}-${System.identityHashCode(model)}", model::class.java]

    /** The case screen on [id], with its state collected as the screen collects it. */
    private fun TestScope.screen(id: ServiceCaseId): ServiceCaseViewModel {
        val model = held(
            ServiceCaseViewModel(
                graph.serviceCases, graph.serviceCaseEntries, graph.events,
                graph.updateServiceCase, graph.addServiceCaseEntry, graph.todayPort, id,
                zone = { berlin },
            ),
        )
        backgroundScope.launch { model.state.collect() }
        return model
    }

    private suspend fun ServiceCaseViewModel.shown(until: (ServiceCaseState) -> Boolean = { true }): ServiceCaseState =
        state.first { it != null && until(it) }!!

    private fun eventOf(id: String, kind: EventKind, occurredOn: String, title: String, assetId: String = "heater") = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = kind, title = title,
        profileId = null, occurredOn = occurredOn, occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = null, createdAt = dayMillis(occurredOn),
        updatedAt = dayMillis(occurredOn), measurements = emptyList(), consumables = emptyList(),
    )

    /** Example Heater, DOWN with an Incident, and an open case on it. */
    private suspend fun openCase(): ServiceCase {
        graph.assets.upsert(assetRow("heater", name = "Example Heater"))
        graph.events.upsert(eventOf("inc-1", EventKind.INCIDENT, "2028-07-01", "Will not heat"))
        graph.conditions.insert(conditionRow("cond-1", "heater", OperationalCondition.DOWN, "2028-07-01", eventId = "inc-1"))
        val command = ServiceCaseCommand(
            title = "Heater claim", type = CaseType.WARRANTY_SERVICE, openedOn = "2028-07-02",
            coverage = CaseCoverage.IN_WARRANTY, resolutionEventId = null, caseRef = "RMA-0001",
        )
        return graph.openServiceCase.run(AssetId("heater"), command, EventId("inc-1"))
    }

    private fun entryOf(id: String, caseId: ServiceCaseId, on: String, time: String?, createdAt: Long, note: String = id) =
        ServiceCaseEntry(ServiceCaseEntryId(id), caseId, on, time, "UTC", note, null, createdAt)

    /** C22: `(occurredOn, occurredTime nulls first, createdAt, id)`, oldest first, whatever the write order. */
    @Test fun theTimelineIsChronological() = runTest {
        val case = openCase()
        graph.serviceCaseEntries.insert(entryOf("n-late", case.id, "2028-07-09", "08:00", createdAt = 10))
        graph.serviceCaseEntries.insert(entryOf("n-timed", case.id, "2028-07-05", "09:30", createdAt = 11))
        graph.serviceCaseEntries.insert(entryOf("n-b", case.id, "2028-07-05", null, createdAt = 12))
        graph.serviceCaseEntries.insert(entryOf("n-a", case.id, "2028-07-05", null, createdAt = 12))
        graph.serviceCaseEntries.insert(entryOf("n-first", case.id, "2028-07-03", "17:00", createdAt = 13))

        val shown = screen(case.id).shown { it.timeline.size == 5 }

        assertEquals(listOf("n-first", "n-a", "n-b", "n-timed", "n-late"), shown.timeline.map { it.id })
        assertEquals(TimelineRow("n-first", "3 Jul 2028", "17:00", null, "n-first"), shown.timeline.first())
    }

    /** One note: one entry, in the device's zone, on today; the header — its `updatedAt` too — untouched. */
    @Test fun addUpdateWritesOneEntry() = runTest {
        val case = openCase()
        val model = screen(case.id)
        model.shown()

        model.openUpdate()
        assertEquals("2028-07-15", model.sheet.value!!.date)
        model.onUpdateNote("Shipped to the service center")
        model.saveUpdate()
        model.sheet.first { it == null }

        val entry = graph.serviceCaseEntries.forCase(case.id).single()
        assertEquals("Shipped to the service center", entry.note)
        assertEquals("2028-07-15", entry.occurredOn)
        assertNull(entry.occurredTime)
        assertNull("a note moves no status", entry.status)
        assertEquals("Europe/Berlin", entry.tzId)
        assertEquals("a note-only entry leaves the header alone", case, graph.serviceCases.get(case.id))
        assertEquals(listOf("Shipped to the service center"), model.shown { it.timeline.size == 1 }.timeline.map { it.note })
    }

    /** P79-56 needs a note or a status; a status chip tapped again is none; a held save writes nothing. */
    @Test fun saveUpdateIsEnabledOnlyWithANoteOrAStatus() = runTest {
        val case = openCase()
        val model = screen(case.id)
        model.shown()
        model.openUpdate()

        assertFalse("empty", model.sheet.value!!.canSave)
        model.onUpdateNote("   ")
        assertFalse("blank", model.sheet.value!!.canSave)
        model.saveUpdate()
        assertEquals(0, graph.serviceCaseEntries.forCase(case.id).size)

        model.onUpdateNote("Called the provider")
        assertTrue("a note", model.sheet.value!!.canSave)
        model.onUpdateNote("")
        model.onUpdateStatus(CaseStatus.SENT_OUT)
        assertTrue("a status", model.sheet.value!!.canSave)
        model.onUpdateStatus(CaseStatus.SENT_OUT)
        assertNull("tapped again: none, which is no change", model.sheet.value!!.status)
        assertFalse(model.sheet.value!!.canSave)

        model.cancelUpdate()
        assertNull(model.sheet.value)
        assertEquals("Cancel writes nothing", 0, graph.serviceCaseEntries.forCase(case.id).size)
    }

    /** The sheet's refusals: the shipped time and date lines, and S25 for a later day; nothing written. */
    @Test fun aBadTimeSaysEnterATimeAsHHMM() = runTest {
        val case = openCase()
        val model = screen(case.id)
        model.shown()
        model.openUpdate()
        model.onUpdateNote("Picked up")

        model.onUpdateTime("25:00")
        model.saveUpdate()
        model.sheet.first { it?.saving == false }
        assertEquals(mapOf(UpdateField.TIME to "Enter a time as HH:MM"), model.sheet.value!!.problems)

        model.onUpdateTime("09:15")
        assertEquals("an edit takes its line away", emptyMap<String, String>(), model.sheet.value!!.problems)
        model.onUpdateDate("2028-13-01")
        model.saveUpdate()
        model.sheet.first { it?.saving == false }
        assertEquals(mapOf(UpdateField.DATE to "Enter a date as YYYY-MM-DD"), model.sheet.value!!.problems)

        model.onUpdateDate("2028-07-16")
        model.saveUpdate()
        model.sheet.first { it?.saving == false }
        assertEquals(mapOf(UpdateField.DATE to DATE_NOT_LATER_THAN_TODAY), model.sheet.value!!.problems)
        assertNull(model.sheet.value!!.failure)
        assertEquals(0, graph.serviceCaseEntries.forCase(case.id).size)
    }

    /**
     * A CLOSED entry closes the case: Status reads Closed and Closed on its date; no condition, event or
     * schedule is written and nothing is asked (R79-9). A later-written status entry dated earlier is
     * accepted and sets the status by write order (the plan's erratum); the timeline shows it at its date.
     */
    @Test fun closingShowsClosedOnAndWritesNoConditionOrEvent() = runTest {
        val case = openCase()
        val events = graph.events.all()
        val conditions = graph.conditions.forAsset(AssetId("heater"))
        val model = screen(case.id)
        model.shown()

        model.openUpdate()
        model.onUpdateDate("2028-07-12")
        model.onUpdateStatus(CaseStatus.CLOSED)
        model.onUpdateNote("Repaired under warranty")
        model.saveUpdate()
        val closed = model.shown { it.case.status == CaseStatus.CLOSED }

        assertEquals(CaseFact("Status", "Closed"), closed.facts.single { it.label == CASE_STATUS })
        assertEquals(CaseFact("Closed on", "12 Jul 2028"), closed.facts.single { it.label == CLOSED_ON })
        assertEquals("nothing asked", null, model.sheet.value)
        assertFalse(model.picking.value)
        assertEquals("no event written", events, graph.events.all())
        assertEquals("no condition written", conditions, graph.conditions.forAsset(AssetId("heater")))
        assertEquals(0, graph.schedules.all().size)

        model.openUpdate()
        model.onUpdateDate("2028-07-05")
        model.onUpdateStatus(CaseStatus.RETURNED)
        model.saveUpdate()
        val reopened = model.shown { it.case.status == CaseStatus.RETURNED }
        assertTrue("reopened: no Closed on", reopened.facts.none { it.label == CLOSED_ON })
        assertEquals(listOf("Returned", "Closed"), reopened.timeline.map { it.status })
    }

    /** P79-59 offers this asset's MAINTENANCE and REPLACEMENT events only, newest first. */
    @Test fun thePickerListsOnlyThisAssetsMaintenanceAndReplacement() = runTest {
        val case = openCase()
        graph.assets.upsert(assetRow("pump", name = "Example Pump"))
        graph.events.upsert(eventOf("m-1", EventKind.MAINTENANCE, "2028-07-10", "Element replaced"))
        graph.events.upsert(eventOf("r-1", EventKind.REPLACEMENT, "2028-07-12", "Unit swapped"))
        graph.events.upsert(eventOf("n-1", EventKind.NOTE, "2028-07-13", "Called support"))
        graph.events.upsert(eventOf("i-2", EventKind.INCIDENT, "2028-07-14", "Tripped again"))
        graph.events.upsert(eventOf("m-pump", EventKind.MAINTENANCE, "2028-07-14", "Seal", assetId = "pump"))
        val model = screen(case.id)

        val shown = model.shown { it.candidates.isNotEmpty() }

        assertEquals(listOf("r-1", "m-1"), shown.candidates.map { it.eventId })
        assertEquals(RepairCandidate("r-1", "Unit swapped", "12 Jul 2028"), shown.candidates.first())
        assertTrue(shown.offersLinkRepair)
        model.openPicker()
        assertTrue("opening the picker writes nothing", model.picking.value)
        assertEquals(case, graph.serviceCases.get(case.id))
    }

    /**
     * A pick writes the header alone — the resolution link, every other field as loaded — through the
     * update, and asks nothing: no entry, event or condition, and no offer can be reached from here.
     */
    @Test fun linkingWritesTheHeaderAloneAndAsksNothing() = runTest {
        val case = openCase()
        graph.events.upsert(eventOf("m-1", EventKind.MAINTENANCE, "2028-07-10", "Element replaced"))
        val events = graph.events.all()
        val conditions = graph.conditions.forAsset(AssetId("heater"))
        val model = screen(case.id)
        model.shown { it.offersLinkRepair }

        model.openPicker()
        model.linkRepair("m-1")
        val linked = model.shown { it.repair != null }

        assertEquals(case.copy(resolutionEventId = EventId("m-1"), updatedAt = linked.case.updatedAt), linked.case)
        assertEquals(CaseLink(REPAIR_RECORD, "m-1", "Element replaced", "10 Jul 2028", exists = true, removable = true), linked.repair)
        assertFalse("the picker closed", model.picking.value)
        assertNull("nothing asked", model.sheet.value)
        assertEquals(0, graph.serviceCaseEntries.forCase(case.id).size)
        assertEquals(events, graph.events.all())
        assertEquals(conditions, graph.conditions.forAsset(AssetId("heater")))
        // No offer can be asked from here: the case screen holds no offers at all (C22; AC 11).
        val offers = listOf(EntryOffers::class.java, EventOffers::class.java)
        val held = ServiceCaseViewModel::class.java.declaredFields.map { it.type } +
            ServiceCaseViewModel::class.java.constructors.flatMap { it.parameterTypes.toList() }
        assertTrue("no offers dependency", held.none { type -> offers.any { it.isAssignableFrom(type) } })
    }

    /** "Remove" is the Repair record's alone: the link goes, the event stays; the Incident cannot be unlinked. */
    @Test fun removeOnlyOnTheRepairRow() = runTest {
        val case = openCase()
        graph.events.upsert(eventOf("m-1", EventKind.MAINTENANCE, "2028-07-10", "Element replaced"))
        graph.serviceCases.upsert(case.copy(resolutionEventId = EventId("m-1")))
        val model = screen(case.id)
        val shown = model.shown { it.repair != null }

        assertEquals(CaseLink(INCIDENT_LINK, "inc-1", "Will not heat", "1 Jul 2028", exists = true, removable = false), shown.incident)
        assertTrue(shown.repair!!.removable)

        model.removeRepair()
        val removed = model.shown { it.repair == null }
        assertNull(removed.case.resolutionEventId)
        assertEquals("the Incident stays", EventId("inc-1"), removed.case.incidentEventId)
        assertEquals("the event stays", "Element replaced", graph.events.get(EventId("m-1"))?.title)
        assertTrue("P79-59 again", removed.offersLinkRepair)
    }

    /** R79-4: a link whose event is gone is readable — S24 — never an error; its label stays. */
    @Test fun aDanglingLinkSaysS24() = runTest {
        val case = openCase()
        graph.serviceCases.upsert(
            case.copy(incidentEventId = EventId("inc-gone"), resolutionEventId = EventId("maint-gone")),
        )
        val shown = screen(case.id).shown { it.incident?.eventId == "inc-gone" }

        assertEquals(CaseLink(INCIDENT_LINK, "inc-gone", null, null, exists = false, removable = false), shown.incident)
        assertEquals("The linked record was removed.", shown.incident!!.removedLine)
        assertEquals(LINKED_RECORD_REMOVED, shown.repair!!.removedLine)
        assertFalse("a dangling repair record is still a link", shown.offersLinkRepair)
        assertNull("an existing link says nothing more", CaseLink(REPAIR_RECORD, "m", "t", "d", true, true).removedLine)
    }
}
