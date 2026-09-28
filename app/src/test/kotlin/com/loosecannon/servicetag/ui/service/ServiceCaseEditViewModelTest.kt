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
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.usecase.CaseEntryCommand
import com.loosecannon.servicetag.core.usecase.OpenServiceCase
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import java.time.LocalDate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #79 (C21; §3 row 47): the case editor's view model over a Room-backed [FakeGraph] and the real case
 * writers. A new case is prefilled from its Incident — the suggestion a form default, the currency the
 * asset's — every refusal lands on its field with its ratified line, a double tap writes one case,
 * leaving writes nothing, an edit keeps what the update never moves, and anything else is P79-61.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceCaseEditViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private val store = ViewModelStore()

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

    /** Held in a store the test clears, so no view model scope outlives the test's Main. */
    private fun <T : ViewModel> held(model: T): T = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = model as V
        },
    )["${model::class.java.name}-${System.identityHashCode(model)}", model::class.java]

    private fun editor(
        assetId: String = "heater",
        caseId: String? = null,
        incidentId: String? = "inc-1",
        cases: ServiceCaseRepository = graph.serviceCases,
        open: OpenServiceCase = graph.openServiceCase,
    ) = held(
        ServiceCaseEditViewModel(
            graph.assets, graph.events, cases, open, graph.updateServiceCase, graph.todayPort,
            AssetId(assetId), caseId?.let(::ServiceCaseId), incidentId?.let(::EventId),
        ),
    )

    private suspend fun ServiceCaseEditViewModel.ready(): ServiceCaseForm = state.first { it.loaded }

    private suspend fun heater(currency: String? = "EUR", warranty: String? = "2028-06-30") = graph.assets.upsert(
        assetRow("heater", name = "Example Heater").copy(currency = currency, warrantyExpiresOn = warranty),
    )

    private fun incidentOf(id: String, assetId: String, occurredOn: String, title: String = "Will not heat") = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = EventKind.INCIDENT, title = title,
        profileId = null, occurredOn = occurredOn, occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = null, createdAt = dayMillis(occurredOn),
        updatedAt = dayMillis(occurredOn), measurements = emptyList(), consumables = emptyList(),
    )

    private suspend fun settle(model: ServiceCaseEditViewModel) = model.state.first { !it.saving }

    /**
     * C21, C15: the title is the Incident's, `openedOn` today; the coverage comes from the **Incident's**
     * date against the warranty (the expiry day is in — today, a fortnight later, would say OUT), so the
     * type is Warranty service; the currency is the asset's; P79-36 is drawn and P79-19 is the title. An
     * asset with no currency and no warranty date opens with a blank currency, Unknown and Repair.
     */
    @Test fun aNewCaseIsPrefilledFromItsIncident() = runTest {
        heater()
        graph.events.upsert(incidentOf("inc-1", "heater", "2028-06-30"))

        val form = editor().ready()

        assertEquals("Will not heat", form.title)
        assertEquals("2028-07-15", form.openedOn)
        assertEquals(CaseCoverage.IN_WARRANTY, form.coverage)
        assertEquals(CaseType.WARRANTY_SERVICE, form.type)
        assertEquals("EUR", form.currency)
        assertTrue("P79-36 on a new case", form.suggested)
        assertEquals("New service case", form.screenTitle)

        graph.assets.upsert(assetRow("pump", name = "Example Pump"))
        graph.events.upsert(incidentOf("inc-2", "pump", "2028-07-10", title = "Leaks"))
        val bare = editor(assetId = "pump", incidentId = "inc-2").ready()
        assertEquals("Leaks", bare.title)
        assertEquals("", bare.currency)
        assertEquals(CaseCoverage.UNKNOWN, bare.coverage)
        assertEquals(CaseType.REPAIR, bare.type)
        assertEquals("nothing is written by a load", 0, graph.serviceCases.all().size)
    }

    /**
     * Every refusal on its field with its ratified line: P79-52 on Title; the shipped date line and S25
     * on Opened on; the shipped currency line and P79-48 on Currency; P79-62 on Cost — and an edit of a
     * field takes its line away.
     */
    @Test fun refusalsLandOnTheirFields() = runTest {
        heater(currency = null)
        graph.events.upsert(incidentOf("inc-1", "heater", "2028-07-01"))
        val model = editor()
        model.ready()

        model.onTitle("  ")
        model.onOpenedOn("2028-07-16")
        model.onCurrency("usd")
        model.save()
        val refused = settle(model)
        assertEquals(
            mapOf(
                CaseField.TITLE to "Give the case a title",
                CaseField.OPENED_ON to DATE_NOT_LATER_THAN_TODAY,
                CaseField.CURRENCY to "Currency is a three-letter code like USD",
            ),
            refused.problems,
        )

        model.onTitle("Heater claim")
        assertFalse("an edit takes its field's line away", CaseField.TITLE in model.state.value.problems)
        model.onOpenedOn("15-07-2028")
        model.onCurrency("")
        model.onCost("120")
        model.save()
        assertEquals(mapOf(CaseField.CURRENCY to "A cost needs a currency"), settle(model).problems)

        model.onOpenedOn("2028-07-14")
        model.onCurrency("USD")
        model.onCost("12.345")
        model.save()
        assertEquals(mapOf(CaseField.COST to "Enter a cost like 123.45"), settle(model).problems)

        model.onCost("12.34")
        model.onOpenedOn("15-07-2028")
        model.save()
        assertEquals(mapOf(CaseField.OPENED_ON to "Enter a date as YYYY-MM-DD"), settle(model).problems)
        assertEquals("every refusal wrote nothing", 0, graph.serviceCases.all().size)
    }

    /** `saving` is set before the first suspension: two taps in one frame open one case. */
    @Test fun aDoubleTapWritesOnce() = runTest {
        heater()
        graph.events.upsert(incidentOf("inc-1", "heater", "2028-06-20"))
        val model = editor()
        model.ready()
        val saved = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.saved.first() }

        model.save()
        model.save()
        saved.await()
        // Read behind both taps' writes: a second one, had it been let through, is already queued.
        assertEquals("one case", 1, graph.serviceCases.all().size)
        val case = graph.serviceCases.all().single()
        assertEquals(EventId("inc-1"), case.incidentEventId)
        assertEquals(CaseStatus.OPEN, case.status)
        assertEquals("the suggestion stored as sent", CaseCoverage.IN_WARRANTY, case.coverage)
        assertEquals("EUR", case.currency)
    }

    /** ✕ and back only leave: a form typed into and abandoned writes nothing, on a new case or an edit. */
    @Test fun cancelWritesNothing() = runTest {
        heater()
        graph.events.upsert(incidentOf("inc-1", "heater", "2028-07-01"))
        val fresh = editor()
        fresh.ready()
        fresh.onTitle("Heater claim")
        fresh.onProvider("Example Service Co")
        fresh.onCost("80")
        testScheduler.advanceUntilIdle()
        assertEquals(0, graph.serviceCases.all().size)

        val opened = graph.openServiceCase.run(AssetId("heater"), command("Heater claim"), EventId("inc-1"))
        val edit = editor(caseId = opened.id.value, incidentId = null)
        edit.ready()
        edit.onProvider("Another provider")
        store.clear()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(opened), graph.serviceCases.all())
    }

    /**
     * An edit loads the stored header (P79-21 its title, no P79-36) and writes it back through the
     * update: status, Closed on, the Incident and the resolution link — sent back as loaded, even
     * dangling — all stay.
     */
    @Test fun anEditKeepsStatusClosedOnAndLinks() = runTest {
        heater()
        graph.events.upsert(incidentOf("inc-1", "heater", "2028-07-01"))
        val opened = graph.openServiceCase.run(AssetId("heater"), command("Heater claim"), EventId("inc-1"))
        graph.addServiceCaseEntry.run(opened.id, CaseEntryCommand("2028-07-10", null, "UTC", "", CaseStatus.CLOSED))
        // A dangling resolution link, as a merge or a deleted repair record leaves one.
        val stored = graph.serviceCases.get(opened.id)!!.copy(resolutionEventId = EventId("maint-gone"))
        graph.serviceCases.upsert(stored)

        val model = editor(caseId = opened.id.value, incidentId = null)
        val form = model.ready()
        assertEquals("Service case", form.screenTitle)
        assertFalse(form.suggested)
        assertEquals("Heater claim", form.title)
        assertEquals("80.00", form.cost)

        model.onProvider("Example Service Co")
        val saved = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.saved.first() }
        model.save()
        assertEquals(opened.id.value, saved.await())

        val after = graph.serviceCases.get(opened.id)!!
        assertEquals("Example Service Co", after.provider)
        assertEquals(CaseStatus.CLOSED, after.status)
        assertEquals("2028-07-10", after.closedOn)
        assertEquals(EventId("inc-1"), after.incidentEventId)
        assertEquals(EventId("maint-gone"), after.resolutionEventId)
    }

    /** A failure no field explains is P79-61, once; the form stays as typed and may be saved again. */
    @Test fun anUnexpectedFailureSaysP79_61() = runTest {
        heater()
        graph.events.upsert(incidentOf("inc-1", "heater", "2028-07-01"))
        val broken = object : ServiceCaseRepository by graph.serviceCases {
            override suspend fun upsert(case: ServiceCase) = throw IllegalStateException("disk full")
        }
        val open = OpenServiceCase(graph.assets, graph.events, broken, graph.uow, graph.ids, graph.clock, graph.todayPort)
        val model = editor(cases = broken, open = open)
        model.ready()
        val said = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }

        model.save()

        assertEquals("Could not save this case.", said.await())
        assertFalse("the form can be saved again", model.state.first { !it.saving }.saving)
        assertEquals(emptyMap<String, String>(), model.state.value.problems)
        assertEquals(0, graph.serviceCases.all().size)
    }

    private fun command(title: String) = ServiceCaseCommand(
        title = title, type = CaseType.WARRANTY_SERVICE, openedOn = "2028-07-02",
        coverage = CaseCoverage.IN_WARRANTY, resolutionEventId = null, costMinor = 8000, currency = "EUR",
    )
}
