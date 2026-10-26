package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.hapublish.BoundaryCache
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.season.BoundarySource
import com.loosecannon.servicetag.core.season.NotReadyReason
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.ReadinessBoundary
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.scheduleOf
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * #114 row 75 (C33, R-N1, F, O-19) — the season card's readiness lines, their model over a Room-backed [FakeGraph]: one
 * readiness line for each of C9's answers, the source and cutoff lines under it, the "unverified since" disclosure, no
 * line at all without a role or a date binding, and a line read again on resume. The sentences are written out in their
 * ratified words, so a paraphrase fails. Every name is fictional ("Example Heater", "Example annual service").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadinessLineTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    private val heater = AssetId("example-heater")
    private val service = ScheduleId("example-annual-service")

    /** The date binding a test hands the card; B3's ports are not on this lane, so the card reads it through the seam. */
    private var dateBinding: DateBindingRead? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        on("2026-02-10")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun on(date: String, hour: Int = 9) {
        graph.today = LocalDate.parse(date)
        graph.now = dayMillis(date) + hour * HOUR
    }

    // --- fixtures -------------------------------------------------------------------------------

    /** In season 1 April – 31 October: on 10 February 2026 its season last ended on 1 November 2025 (O-1). */
    private suspend fun calendarHeater() =
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-01", seasonEnd = "10-31"))

    private suspend fun manualHeater() =
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))

    private fun roleSchedule(id: ScheduleId = service, sinceOn: String = "2025-09-01"): MaintenanceSchedule =
        scheduleOf(id.value, assetId = heater.value, title = "Example annual service", anchorOn = "2025-06-01")
            .copy(role = ScheduleRole.SEASON_START, roleSinceOn = sinceOn)

    /** A Season Start service written straight to the store: the card reads what is stored, refused or not. */
    private suspend fun role(sinceOn: String = "2025-09-01", id: ScheduleId = service, status: ScheduleStatus = ScheduleStatus.ACTIVE) =
        graph.uow.write { graph.schedules.upsert(roleSchedule(id, sinceOn).copy(status = status)) }

    private suspend fun complete(on: String, id: ScheduleId = service): AssetEvent =
        graph.completeSchedule.run(id, CompletionCommand(occurredOn = on, tzId = "UTC"))

    private suspend fun amend(event: AssetEvent, change: (AssetEvent) -> AssetEvent) =
        graph.uow.write { graph.events.upsert(change(graph.events.get(event.id)!!)) }

    private suspend fun activation(action: SeasonAction, on: String, createdAt: Long = dayMillis(on)) =
        graph.seasonActivations.insert(SeasonActivation("act-$on", heater, action, on, null, createdAt))

    private suspend fun linkedToHomeAssistant() {
        graph.haConnections.upsert(
            HaConnection(
                "connection-1", "http://192.168.0.10:8123", SyncCadence.DAILY, NetworkEligibility.ANY_NETWORK, null,
                BackgroundChecks.OFF, dayMillis("2025-03-01"), dayMillis("2025-03-01"),
            ),
        )
        graph.seasonSyncBindings.insert(
            SeasonSyncBinding(
                heater, "connection-1", "input_boolean.example_heater_in_season", SyncMode.FOLLOW, true, 1, null, null,
                null, null, null, null, null, null, null, null, null, dayMillis("2025-03-01"), dayMillis("2025-03-01"),
            ),
        )
    }

    /** The heater's date binding, naming the season-ended helper, with a boundary [readAt] the given day. */
    private fun dateBindingRead(value: String?, readAt: Long?) = DateBindingRead(
        PublishBinding(
            id = "binding-1", assetId = heater, connectionId = "connection-1", kind = PublishKind.SEASON_SERVICE_DATE,
            entityId = "input_datetime.example_heater_service_done", enabled = true, revision = 3,
            activatedInstallationId = "example-installation", validatedAt = dayMillis("2025-03-02"), validatedRevision = 3,
            boundaryEntityId = "input_datetime.example_heater_season_ended",
            createdAt = dayMillis("2025-03-02"), updatedAt = dayMillis("2025-03-02"),
        ),
        BoundaryCache(value, readAt, 3),
    )

    private fun TestScope.open(): ReadinessLineViewModel {
        val model = ReadinessLineViewModel(
            heater, graph.assets, graph.schedules, graph.groups, graph.events, graph.seasonActivations,
            graph.seasonSyncBindings, graph.haConnections, { dateBinding }, graph.uow, graph.todayPort, graph.clock,
        ) { ZoneOffset.UTC }
        advanceUntilIdle()
        return model
    }

    private fun lines(model: ReadinessLineViewModel): List<String?> = model.state.value.let {
        listOf(it.line, it.sourceLine, it.unverifiedLine, it.cutoffLine)
    }

    // --- R-N1 -----------------------------------------------------------------------------------

    @Test fun noLineWithoutARoleOrDateBinding() = runTest(scheduler) {
        calendarHeater()
        assertEquals(listOf(null, null, null, null), lines(open()))

        // The same asset with a date binding and still no role: P114-40, and no boundary to draw.
        dateBinding = dateBindingRead(null, null)
        assertEquals(listOf("Not ready: no Season Start service", null, null, null), lines(open()))
    }

    // --- each state's sentence -----------------------------------------------------------------

    @Test fun aReadyAssetSaysWhenItWasServicedWhereTheSeasonEndCameFromAndTheCutoff() = runTest(scheduler) {
        calendarHeater()
        role(sinceOn = "2025-09-01")
        complete("2026-02-10")
        assertEquals(
            listOf(
                "Ready for the season · serviced 10 Feb 2026",
                "Season end from the calendar season: 1 Nov 2025",
                null,
                "Services dated on or after 1 Nov 2025 count.",
            ),
            lines(open()),
        )
    }

    @Test fun aServiceBeforeTheSeasonEndedSaysSo() = runTest(scheduler) {
        calendarHeater()
        role(sinceOn = "2025-09-01")
        complete("2025-10-15")
        assertEquals(
            listOf(
                "Not ready: not serviced since the season ended on 1 Nov 2025",
                "Season end from the calendar season: 1 Nov 2025",
                null,
                "Services dated on or after 1 Nov 2025 count.",
            ),
            lines(open()),
        )
    }

    @Test fun aRoleSetAfterTheSeasonEndedCountsFromItsDay() = runTest(scheduler) {
        calendarHeater()
        role(sinceOn = "2026-01-15")
        assertEquals(
            listOf(
                "Not ready: no qualifying service dated on or after 15 Jan 2026.",
                "Counted from the day the Season Start service was set: 15 Jan 2026",
                null,
                "Services dated on or after 15 Jan 2026 count.",
            ),
            lines(open()),
        )
    }

    @Test fun aManualAssetsSeasonEndIsThePhones() = runTest(scheduler) {
        manualHeater()
        activation(SeasonAction.START, "2025-04-10")
        activation(SeasonAction.END, "2025-11-20")
        role(sinceOn = "2025-09-01")
        complete("2026-02-01")
        assertEquals(
            listOf(
                "Ready for the season · serviced 1 Feb 2026",
                "Season end as recorded on this phone: 20 Nov 2025",
                null,
                "Services dated on or after 20 Nov 2025 count.",
            ),
            lines(open()),
        )
    }

    @Test fun aServiceDatedAfterTodayNamesItsDate() = runTest(scheduler) {
        calendarHeater()
        role(sinceOn = "2025-09-01")
        val done = complete("2026-02-10")
        amend(done) { it.copy(occurredOn = "2026-02-20", updatedAt = dayMillis("2026-02-20")) }
        assertEquals("Not ready yet: the service is dated 20 Feb 2026, after today", open().state.value.line)
    }

    @Test fun aServiceEnteredBeforeItsDayNamesItsDate() = runTest(scheduler) {
        calendarHeater()
        role(sinceOn = "2025-09-01")
        val done = complete("2026-02-10")
        amend(done) { it.copy(updatedAt = dayMillis("2026-02-08")) }
        assertEquals(
            "Not ready: the service dated 10 Feb 2026 was entered before that day, so it does not count. " +
                "Edit it on or after that day to confirm it.",
            open().state.value.line,
        )
    }

    @Test fun detailsStillToFillInSaySo() = runTest(scheduler) {
        calendarHeater()
        role(sinceOn = "2025-09-01")
        val done = complete("2026-02-10")
        amend(done) { it.copy(detailsPending = true) }
        assertEquals("Not ready: the service's details are still to be filled in", open().state.value.line)
    }

    @Test fun aServiceThatCameWithTheAssetSaysSo() = runTest(scheduler) {
        calendarHeater()
        role(sinceOn = "2025-09-01")
        val done = complete("2026-02-10")
        amend(done) { it.copy(imported = true) }
        assertEquals(
            "Not ready: the service on record was recorded on another phone and came with this asset. " +
                "Record this season's service on this phone.",
            open().state.value.line,
        )
    }

    @Test fun anArchivedRoleATwoRoleAssetAndAnAssetThatCannotHaveOneEachSaySo() = runTest(scheduler) {
        calendarHeater()
        role(status = ScheduleStatus.ARCHIVED)
        assertEquals(listOf("Not ready: the Season Start service is archived", null, null, null), lines(open()))

        role()
        role(id = ScheduleId("example-second-service"))
        assertEquals(
            listOf("Not ready: two Season Start services cover this asset. Stop using one of them.", null, null, null),
            lines(open()),
        )

        graph.uow.write { graph.schedules.upsert(roleSchedule(ScheduleId("example-second-service")).copy(role = ScheduleRole.NONE, roleSinceOn = null)) }
        graph.assets.upsert(assetRow(heater.value, "Example Heater"))
        assertEquals(
            listOf(
                "Not ready: a Season Start service applies only to an asset with seasons that has no parent or has child assets",
                null, null, null,
            ),
            lines(open()),
        )
    }

    // --- the Home Assistant boundary (C9a, O-19) -------------------------------------------------

    @Test fun aFreshHomeAssistantSeasonEndIsItsSourceWithItsCheckDay() = runTest(scheduler) {
        manualHeater()
        linkedToHomeAssistant()
        dateBinding = dateBindingRead("2025-05-10", readAt = dayMillis("2026-02-09"))
        role(sinceOn = "2025-04-01")
        complete("2025-09-18")
        assertEquals(
            listOf(
                "Ready for the season · serviced 18 Sep 2025",
                "Season end from Home Assistant: 10 May 2025 · checked 9 Feb 2026",
                null,
                "Services dated on or after 10 May 2025 count.",
            ),
            lines(open()),
        )
    }

    @Test fun anUnverifiedHomeAssistantSeasonEndSaysSinceWhenAndWhatIsUsed() = runTest(scheduler) {
        manualHeater()
        linkedToHomeAssistant()
        dateBinding = dateBindingRead("2025-05-10", readAt = dayMillis("2026-01-11"))
        role(sinceOn = "2025-04-01")
        complete("2025-09-18")
        assertEquals(
            listOf(
                "Ready for the season · serviced 18 Sep 2025",
                "Season end not checked with Home Assistant since 11 Jan 2026 · using 10 May 2025",
                null,
                "Services dated on or after 10 May 2025 count.",
            ),
            lines(open()),
        )
    }

    @Test fun anUnverifiedBoundaryIsDisclosedWhenTheRolesDayWins() = runTest(scheduler) {
        manualHeater()
        linkedToHomeAssistant()
        dateBinding = dateBindingRead("2025-05-10", readAt = dayMillis("2026-01-11"))
        role(sinceOn = "2025-12-01")
        assertEquals(
            listOf(
                "Not ready: no qualifying service dated on or after 1 Dec 2025.",
                "Counted from the day the Season Start service was set: 1 Dec 2025",
                "Season end not checked with Home Assistant since 11 Jan 2026 · using 1 Dec 2025",
                "Services dated on or after 1 Dec 2025 count.",
            ),
            lines(open()),
        )
    }

    // --- resume ---------------------------------------------------------------------------------

    @Test fun theLineRecomputesOnResume() = runTest(scheduler) {
        calendarHeater()
        role(sinceOn = "2025-09-01")
        complete("2026-02-10")
        val model = open()
        assertEquals("Ready for the season · serviced 10 Feb 2026", model.state.value.line)

        // The season runs and ends with nothing stored changing: only the day moves.
        on("2026-11-05")
        advanceUntilIdle()
        assertEquals("Ready for the season · serviced 10 Feb 2026", model.state.value.line)
        model.onResumed()
        advanceUntilIdle()
        assertEquals("Not ready: not serviced since the season ended on 1 Nov 2026", model.state.value.line)
        assertEquals("Services dated on or after 1 Nov 2026 count.", model.state.value.cutoffLine)
    }

    // --- every reason and source has its sentence (F-16) ----------------------------------------

    @Test fun everyReasonAndSourceHasItsOwnSentence() {
        val day = LocalDate.parse("2026-02-01")
        val boundary = ReadinessBoundary(day, BoundarySource.CALENDAR_WINDOW)
        val reasons = NotReadyReason.entries.map { reason ->
            ReadinessStrings.line(Readiness.NotReady(reason, service, boundary), hasDateBinding = true, evidenceOn = day)
                .also { assertNotNull(reason.name, it) }
        }
        assertEquals("one sentence per reason", NotReadyReason.entries.size, reasons.toSet().size)

        val sources = BoundarySource.entries.map { source ->
            ReadinessStrings.sourceLine(
                ReadinessBoundary(day, source, checkedAt = dayMillis("2026-02-02"), unverifiedSince = dayMillis("2026-02-03")),
                ZoneOffset.UTC,
            ).also { assertNotNull(source.name, it) }
        }
        assertEquals("one sentence per source", BoundarySource.entries.size, sources.toSet().size)
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
