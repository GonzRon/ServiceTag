package com.loosecannon.servicetag.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.service.A_SERVICE_CASE_LINKS_THIS_ENTRY
import java.time.LocalDate
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
 * #79 (C23, R79-3, R79-4; §3 row 50): the Incident's detail. P79-20 "Start service case" only on a
 * non-completion INCIDENT of an asset in service — the one rule with the asset detail's P79-19 — and
 * the delete confirm gains P79-60 when a service case links the entry (as its Incident or its repair
 * record), through the graph's read-only [CaseLinks].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventDetailCaseLinksTest {

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

    private fun <T : ViewModel> held(model: T): T = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = model as V
        },
    )["${model::class.java.name}-${System.identityHashCode(model)}", model::class.java]

    /** The entry's page, open: its state collected as the screen collects it. */
    private suspend fun TestScope.page(id: String): EventDetailViewModel {
        val model = held(
            EventDetailViewModel(graph.events, graph.definitions, graph.assets, graph.deleteEvent, EventId(id), graph.caseLinks),
        )
        backgroundScope.launch { model.state.collect() }
        model.state.first { it != null }
        return model
    }

    private suspend fun TestScope.detail(id: String): EventDetailState = page(id).state.value!!

    /** Delete, tapped: the confirm as asked, then dismissed ("Cancel") — nothing is deleted. */
    private suspend fun EventDetailViewModel.confirmAsked(): DeleteConfirm {
        askDelete()
        val asked = deleteConfirm.first { it != null }!!
        dismissDelete()
        return asked
    }

    private fun eventOf(
        id: String,
        assetId: String,
        kind: EventKind = EventKind.INCIDENT,
        scheduleId: String? = null,
        title: String = "Will not heat",
    ) = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = kind, title = title,
        profileId = null, occurredOn = "2028-07-01", occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = null, createdAt = dayMillis("2028-07-01"),
        updatedAt = dayMillis("2028-07-01"), measurements = emptyList(), consumables = emptyList(),
        scheduleId = scheduleId?.let(::ScheduleId), occurrenceOn = scheduleId?.let { "2028-07-01" },
    )

    @Test fun startServiceCaseOnlyOnANonCompletionIncidentOfAnInServiceAsset() = runTest {
        graph.assets.upsert(assetRow("heater", name = "Example Heater"))
        graph.assets.upsert(assetRow("retired", name = "Example Pump", retiredOn = "2028-07-02"))
        graph.assets.upsert(assetRow("archived", name = "Example Fan", status = AssetStatus.ARCHIVED))
        graph.schedules.upsert(scheduleOf("s1", assetId = "heater"))
        graph.events.upsert(eventOf("inc", "heater"))
        graph.events.upsert(eventOf("inc-done", "heater", scheduleId = "s1"))
        graph.events.upsert(eventOf("maint", "heater", kind = EventKind.MAINTENANCE, title = "Element replaced"))
        graph.events.upsert(eventOf("inc-retired", "retired"))
        graph.events.upsert(eventOf("inc-archived", "archived"))

        assertTrue("an Incident of an asset in service", detail("inc").startsServiceCase)
        assertFalse("a completion", detail("inc-done").startsServiceCase)
        assertFalse("not an Incident", detail("maint").startsServiceCase)
        assertFalse("a retired asset", detail("inc-retired").startsServiceCase)
        assertFalse("an archived asset", detail("inc-archived").startsServiceCase)
    }

    /**
     * R79-4: deleting a linked entry is allowed and leaves a readable dangling link; the confirm says so
     * with P79-60 after "Its readings go with it." — for the case's Incident and its repair record alike,
     * and only when a case names the entry. The link is read when Delete is tapped, so a case started
     * while the Incident's page is open (P79-20, and back) counts.
     */
    @Test fun theDeleteConfirmAddsP79_60WhenACaseLinksIt() = runTest {
        graph.assets.upsert(assetRow("heater", name = "Example Heater"))
        graph.events.upsert(eventOf("inc", "heater"))
        graph.events.upsert(eventOf("maint", "heater", kind = EventKind.MAINTENANCE, title = "Element replaced"))
        graph.events.upsert(eventOf("other", "heater", title = "Tripped once"))
        val incident = page("inc")

        assertFalse("no case yet", incident.confirmAsked().linkedByCase)
        assertEquals(listOf("Its readings go with it."), deleteConfirmLines(linkedByCase = false))

        val command = ServiceCaseCommand(
            title = "Heater claim", type = CaseType.WARRANTY_SERVICE, openedOn = "2028-07-02",
            coverage = CaseCoverage.IN_WARRANTY, resolutionEventId = EventId("maint"),
        )
        graph.openServiceCase.run(AssetId("heater"), command, EventId("inc"))

        assertTrue("the case's Incident, on the page already open", incident.confirmAsked().linkedByCase)
        assertNull("dismissed: no confirm, nothing deleted", incident.deleteConfirm.value)
        assertEquals("inc", graph.events.get(EventId("inc"))?.id?.value)
        assertTrue("the case's repair record", page("maint").confirmAsked().linkedByCase)
        assertFalse("an entry no case names", page("other").confirmAsked().linkedByCase)
        assertEquals(
            listOf("Its readings go with it.", "A service case links this entry. Its documents go with it."),
            deleteConfirmLines(linkedByCase = true),
        )
        assertEquals(A_SERVICE_CASE_LINKS_THIS_ENTRY, deleteConfirmLines(linkedByCase = true).last())
    }
}
