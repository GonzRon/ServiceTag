package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.ui.service.NO_SERVICE_CASES_YET
import com.loosecannon.servicetag.ui.service.ONE_OPEN_SERVICE_CASE
import com.loosecannon.servicetag.ui.service.ServiceCaseRow
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
 * #79 (C20, R79-3, R79-17; §3 row 45): the asset detail's Service cases section, as the detail state
 * carries it, over a Room-backed [FakeGraph] and the real `observeForAsset`. P79-16 with none; the
 * open count only while one is open; open cases first, then the closed ones as history; P79-19 only
 * in service, aimed at the current failure's Incident when there is one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetCasesStateTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-09-20")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun detailModel(id: AssetId) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.conditions, graph.seasonActivations, graph.healthSubjects, graph.attachments,
        graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, id,
        serviceCases = graph.serviceCases,
    )

    private suspend fun TestScope.loaded(id: String): AssetDetailState {
        val vm = detailModel(AssetId(id))
        backgroundScope.launch { vm.state.collect() }
        return vm.state.first { it != null }!!
    }

    private fun case(
        id: String,
        openedOn: String,
        status: CaseStatus = CaseStatus.OPEN,
        title: String = "Heater claim $id",
        coverage: CaseCoverage = CaseCoverage.IN_WARRANTY,
        assetId: String = "heater",
    ) = ServiceCase(
        id = ServiceCaseId(id), assetId = AssetId(assetId), title = title, type = CaseType.WARRANTY_SERVICE,
        openedOn = openedOn, closedOn = openedOn.takeIf { status.isTerminal }, provider = "", contact = "",
        caseRef = "", coverage = coverage, status = status, outboundTracking = "", outboundCarrier = "",
        returnTracking = "", returnCarrier = "", costMinor = null, currency = null, notes = "",
        incidentEventId = null, resolutionEventId = null, createdAt = 1L, updatedAt = 1L,
    )

    @Test fun noCasesSaysNoServiceCasesYet() = runTest {
        graph.assets.upsert(assetRow("heater", name = "Example Heater"))

        val page = loaded("heater")

        assertEquals(emptyList<ServiceCaseRow>(), page.cases)
        assertNull("no count line at zero", page.openCasesLine)
        assertEquals("No service cases yet", NO_SERVICE_CASES_YET)
    }

    /** P79-17 at one, P79-18 above; hidden — null — while every case is closed or cancelled. */
    @Test fun theOpenCountIsHiddenAtZeroAndSaysOneOrMany() = runTest {
        graph.assets.upsert(assetRow("heater", name = "Example Heater"))
        graph.serviceCases.upsert(case("c1", "2026-09-01", CaseStatus.CLOSED))
        graph.serviceCases.upsert(case("c2", "2026-09-02", CaseStatus.CANCELLED))
        assertNull("two closed cases: hidden", loaded("heater").openCasesLine)

        graph.serviceCases.upsert(case("c3", "2026-09-03", CaseStatus.SENT_OUT))
        assertEquals(ONE_OPEN_SERVICE_CASE, loaded("heater").openCasesLine)
        assertEquals("1 open service case", loaded("heater").openCasesLine)

        graph.serviceCases.upsert(case("c4", "2026-09-04", CaseStatus.AT_SERVICE_CENTER))
        graph.serviceCases.upsert(case("c5", "2026-09-05", CaseStatus.RETURNED))
        assertEquals("3 open service cases", loaded("heater").openCasesLine)
    }

    /**
     * Open first, newest opened first; then the closed and cancelled ones, as history, the same way —
     * and never hidden. Each row's quiet line is `<status word> · <coverage word>`. Another asset's
     * case is not this asset's.
     */
    @Test fun openFirstThenClosedAsHistory() = runTest {
        graph.assets.upsert(assetRow("heater", name = "Example Heater"))
        graph.assets.upsert(assetRow("pump", name = "Example Pump"))
        graph.serviceCases.upsert(case("c-old-open", "2026-08-01", CaseStatus.OPEN, coverage = CaseCoverage.UNKNOWN))
        graph.serviceCases.upsert(case("c-new-closed", "2026-09-10", CaseStatus.CLOSED))
        graph.serviceCases.upsert(case("c-new-open", "2026-09-12", CaseStatus.SENT_OUT, coverage = CaseCoverage.PARTLY_COVERED))
        graph.serviceCases.upsert(case("c-old-cancelled", "2026-07-01", CaseStatus.CANCELLED, coverage = CaseCoverage.OUT_OF_WARRANTY))
        graph.serviceCases.upsert(case("c-pump", "2026-09-15", assetId = "pump"))

        val rows = loaded("heater").cases

        assertEquals(listOf("c-new-open", "c-old-open", "c-new-closed", "c-old-cancelled"), rows.map { it.id })
        assertEquals(listOf(true, true, false, false), rows.map { it.open })
        assertEquals(
            listOf("Sent out · Partly covered", "Open · Unknown", "Closed · In warranty", "Cancelled · Out of warranty"),
            rows.map { it.line },
        )
        assertEquals("Heater claim c-new-open", rows.first().title)
    }

    /**
     * R79-17: P79-19 only on an asset in service — never retired or archived. It is aimed at the
     * current failure's Incident when there is one ([AssetDetailState.currentIncidentId]); with none,
     * the host opens an Incident entry first (`newServiceCaseRoute`).
     */
    @Test fun newServiceCaseOnlyInService() = runTest {
        graph.assets.upsert(assetRow("heater", name = "Example Heater"))
        graph.assets.upsert(assetRow("retired", name = "Example Pump", retiredOn = "2026-09-01"))
        graph.assets.upsert(assetRow("archived", name = "Example Fan", status = AssetStatus.ARCHIVED))

        val working = loaded("heater")
        assertTrue(working.offersNewServiceCase)
        assertNull("no current failure: an Incident is logged first", working.currentIncidentId)
        assertFalse("retired", loaded("retired").offersNewServiceCase)
        assertFalse("archived", loaded("archived").offersNewServiceCase)

        // DOWN with an Incident for this failure: P79-19 opens the editor on it.
        graph.events.upsert(incidentOf("inc-1", "heater", "2026-09-18"))
        graph.conditions.insert(conditionRow("c1", "heater", OperationalCondition.DOWN, "2026-09-18", eventId = "inc-1"))
        val down = loaded("heater")
        assertTrue(down.offersNewServiceCase)
        assertEquals("inc-1", down.currentIncidentId)
    }

    private fun incidentOf(id: String, assetId: String, occurredOn: String) = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = EventKind.INCIDENT, title = "Will not heat",
        profileId = null, occurredOn = occurredOn, occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = null, createdAt = dayMillis(occurredOn),
        updatedAt = dayMillis(occurredOn), measurements = emptyList(), consumables = emptyList(),
    )
}
