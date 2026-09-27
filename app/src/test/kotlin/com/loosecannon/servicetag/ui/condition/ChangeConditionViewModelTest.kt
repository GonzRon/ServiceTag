package com.loosecannon.servicetag.ui.condition

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **Change condition** and the **Mark operational** confirmation (spec §5.4, §10.1): nothing is
 * preselected, Save records one row, a later date is S25 and writes nothing, Cancel writes nothing,
 * and the past is allowed. These are the only two ways a person writes a condition by hand (inv. 81).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeConditionViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-04-15")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private suspend fun generator(): AssetId {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        return AssetId("gen")
    }

    private fun model(assetId: AssetId) = ChangeConditionViewModel(
        graph.recordCondition, graph.todayPort, assetId,
        assetOf = { graph.assets.get(it) },
        rowsOf = { graph.conditions.forAsset(it) },
        ids = graph.ids,
    ) { ZoneId.of("UTC") }

    @Test fun nothingIsPreselectedAndSaveWritesOnce() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        var finished = 0
        // The host listens before anything is tapped, as the sheet's LaunchedEffect does.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.finished.collect { finished++ } }

        // No answer, today's date, and Save held until an answer is given.
        val opened = model.state.value
        assertNull("none of the three is preselected", opened.choice)
        assertEquals("2026-04-15", opened.occurredOn)
        assertFalse(opened.canSave)
        model.save()
        advanceUntilIdle()
        assertEquals(0, graph.conditions.all().size)

        model.choose(OperationalCondition.DOWN)
        model.onReason("Will not start")
        assertTrue(model.state.value.canSave)
        // #82 (row 11a): two S16 taps in one frame write nothing and ask P82-1 once.
        val holds = mutableSetOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.state.collect { state -> state.held?.let { holds += it.id } }
        }
        model.save()
        model.save()
        advanceUntilIdle()
        assertEquals("S16 wrote nothing", 0, graph.conditions.all().size)
        assertTrue("P82-1 is asked", model.state.value.asking)
        assertEquals("asked once", 1, holds.size)
        // Two P82-4 taps in one frame: one row.
        model.saveConditionOnly()
        model.saveConditionOnly()
        advanceUntilIdle()

        val row = graph.conditions.all().single()
        assertEquals(OperationalCondition.DOWN, row.condition)
        assertEquals("Will not start", row.reason)
        assertEquals("2026-04-15", row.occurredOn)
        assertNull("the time is left null", row.occurredTime)
        assertEquals("UTC", row.tzId)
        assertNull(row.eventId)
        assertEquals(1, finished)
        // The form is fresh for the next opening: nothing preselected again.
        assertNull(model.state.value.choice)
    }

    @Test fun aFutureDateShowsS25AndWritesNothing() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)

        model.choose(OperationalCondition.DEGRADED)
        model.onDate("2026-04-16")
        model.save()
        advanceUntilIdle()

        assertEquals("The date cannot be later than today.", model.state.value.refusal)
        assertEquals(0, graph.conditions.all().size)
        // Typing a new date clears the refusal; the answer and the reason stay as typed.
        model.onDate("2026-04-15")
        assertNull(model.state.value.refusal)
        assertEquals(OperationalCondition.DEGRADED, model.state.value.choice)
    }

    @Test fun cancelWritesNothing() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        var finished = 0
        // The host listens before anything is tapped, as the sheet's LaunchedEffect does.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.finished.collect { finished++ } }

        model.choose(OperationalCondition.DOWN)
        model.onReason("Belt snapped")
        model.cancel()
        advanceUntilIdle()

        assertEquals(0, graph.conditions.all().size)
        assertEquals(1, finished)
        assertNull(model.state.value.choice)
        assertEquals("", model.state.value.reason)
    }

    /**
     * The ruling on B12's review, M-2: Cancel is ignored while a Save is in flight — the row is
     * landing, so the form finishes once, as saved, and never also as cancelled.
     */
    @Test fun cancelWhileSavingIsIgnored() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        var finished = 0
        // The host listens before anything is tapped, as the sheet's LaunchedEffect does.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.finished.collect { finished++ } }

        model.choose(OperationalCondition.DOWN)
        model.save()
        advanceUntilIdle()
        // #82 (row 11a): the row is committed by P82-4, so that is the save in flight.
        model.saveConditionOnly()
        assertTrue("the save is in flight", model.state.value.saving)
        model.cancel()
        advanceUntilIdle()

        assertEquals(1, graph.conditions.all().size)
        assertEquals("finished once, as saved", 1, finished)
    }

    @Test fun thePastIsAllowed() = runTest(scheduler) {
        val gen = generator()
        graph.conditions.insert(conditionRow("c-now", "gen", OperationalCondition.OPERATIONAL, "2026-04-10"))
        val model = model(gen)

        // A backdated change sorts into place in the history; nothing earlier is edited.
        model.choose(OperationalCondition.DEGRADED)
        model.onDate("2025-11-02")
        model.save()
        advanceUntilIdle()
        // #82 (row 11a): the backdated DEGRADED is committed by P82-4.
        model.saveConditionOnly()
        advanceUntilIdle()

        val rows = graph.conditions.all()
        assertEquals(2, rows.size)
        val added = rows.single { it.id != "c-now" }
        assertEquals("2025-11-02", added.occurredOn)
        assertEquals(OperationalCondition.DEGRADED, added.condition)
        assertEquals("", added.reason)
        assertEquals("c-now", ConditionHistory.of(rows).current!!.id)
    }

    /**
     * The Mark operational confirmation's one write: OPERATIONAL dated **today**, and the DOWN row
     * before it left exactly as it was (inv. 110).
     */
    @Test fun markOperationalRecordsOperationalTodayAndKeepsTheEarlierRow() = runTest(scheduler) {
        val gen = generator()
        val down = conditionRow("c-down", "gen", OperationalCondition.DOWN, "2026-04-01", reason = "Will not start")
        graph.conditions.insert(down)

        MarkOperationalConfirm(graph.recordCondition) { ZoneId.of("UTC") }.run(gen)

        val rows = graph.conditions.all()
        assertEquals(2, rows.size)
        assertEquals(down, rows.single { it.id == "c-down" })
        val current = ConditionHistory.of(rows).current!!
        assertEquals(OperationalCondition.OPERATIONAL, current.condition)
        assertEquals("2026-04-15", current.occurredOn)
        assertEquals("", current.reason)
        assertEquals("The earlier DOWN record stays in the history.", markOperationalBody(OperationalCondition.DOWN))
        assertEquals("The earlier DEGRADED record stays in the history.", markOperationalBody(OperationalCondition.DEGRADED))
    }

    // ------------------------------------------------------------------ #82: the hold (C6, R82-2)

    /** Counts `finished` and collects every hand-over, listening before anything is tapped, as the sheet does. */
    private class Host {
        var finished = 0
        val handedOver = mutableListOf<PendingCondition>()
    }

    private fun kotlinx.coroutines.test.TestScope.host(model: ChangeConditionViewModel): Host {
        val host = Host()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.finished.collect { host.finished++ } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.handOver.collect { host.handedOver += it } }
        return host
    }

    /** DOWN with a reason, saved: the question is up and nothing is written. */
    private suspend fun kotlinx.coroutines.test.TestScope.held(model: ChangeConditionViewModel): PendingCondition {
        model.choose(OperationalCondition.DOWN)
        model.onReason("Will not start\nStarter clicks")
        model.save()
        advanceUntilIdle()
        return model.state.value.held!!
    }

    /** Row 11 (AC 1): DOWN or DEGRADED on an asset in service holds the answer and asks P82-1 over the sheet. */
    @Test fun downOrDegradedOnAnInServiceAssetHoldsAndAsks() = runTest(scheduler) {
        val gen = generator()
        for (condition in listOf(OperationalCondition.DOWN, OperationalCondition.DEGRADED)) {
            val model = model(gen)
            val host = host(model)
            model.choose(condition)
            model.onReason("Runs rough")
            model.onDate("2026-04-12")
            model.save()
            advanceUntilIdle()

            assertEquals("$condition: S16 wrote nothing", 0, graph.conditions.all().size)
            val state = model.state.value
            assertTrue("$condition: P82-1 is asked", state.asking)
            val held = state.held!!
            assertEquals(condition, held.condition)
            assertEquals("2026-04-12", held.occurredOn)
            assertEquals("Runs rough", held.reason)
            assertTrue("a pre-allocated id", held.id.isNotBlank())
            assertEquals("Log incident details?", LOG_INCIDENT_DETAILS_QUESTION)
            assertEquals(
                "Generator is ${conditionWord(condition)}. Record what went wrong in the service record?",
                logIncidentDetailsBody(state.assetName, held.condition),
            )
            assertEquals("Log incident details", LOG_INCIDENT_DETAILS)
            assertEquals("Save condition only", SAVE_CONDITION_ONLY)
            assertEquals("the sheet stays open", 0, host.finished)
            // S16 while the question is up is ignored: the hold is not replaced.
            model.save()
            advanceUntilIdle()
            assertEquals(held, model.state.value.held)
            assertEquals(0, graph.conditions.all().size)
        }
    }

    /** Row 11: OPERATIONAL writes at S16 as shipped, asks nothing, and two taps still record one row. */
    @Test fun operationalWritesAtOnceAndAsksNothing() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        val host = host(model)

        model.choose(OperationalCondition.OPERATIONAL)
        model.save()
        model.save()
        advanceUntilIdle()

        val row = graph.conditions.all().single()
        assertEquals(OperationalCondition.OPERATIONAL, row.condition)
        assertNull(row.eventId)
        assertFalse(model.state.value.asking)
        assertNull(model.state.value.held)
        assertEquals(1, host.finished)
    }

    /** Row 11 (R82-2): a retired or archived asset writes DOWN at S16, as today, and is asked nothing. */
    @Test fun anOutOfServiceAssetWritesAtOnce() = runTest(scheduler) {
        graph.assets.upsert(assetRow("old", name = "Old generator", retiredOn = "2026-01-01"))
        graph.assets.upsert(assetRow("shelf", name = "Shelf generator", status = AssetStatus.ARCHIVED))
        for (id in listOf("old", "shelf")) {
            val model = model(AssetId(id))
            val host = host(model)
            model.choose(OperationalCondition.DOWN)
            model.save()
            advanceUntilIdle()

            assertEquals("$id: written at S16", 1, graph.conditions.forAsset(AssetId(id)).size)
            assertFalse("$id: nothing asked", model.state.value.asking)
            assertNull(model.state.value.held)
            assertEquals(1, host.finished)
        }
    }

    /** Row 11 (R82-2 (a)): DOWN recorded over DOWN asks too — no improvement heuristic. */
    @Test fun downOverDownAsksToo() = runTest(scheduler) {
        val gen = generator()
        graph.conditions.insert(conditionRow("c-down", "gen", OperationalCondition.DOWN, "2026-04-01"))
        val model = model(gen)

        model.choose(OperationalCondition.DOWN)
        model.save()
        advanceUntilIdle()

        assertTrue(model.state.value.asking)
        assertEquals("only the earlier row", listOf("c-down"), graph.conditions.all().map { it.id })
    }

    /** Row 11: a later date is answered by S25 first, and no question is asked. */
    @Test fun aLaterDateIsS25BeforeAnyQuestion() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)

        model.choose(OperationalCondition.DOWN)
        model.onDate("2026-04-16")
        model.save()
        advanceUntilIdle()

        assertEquals("The date cannot be later than today.", model.state.value.refusal)
        assertNull(model.state.value.held)
        assertFalse(model.state.value.asking)
        assertEquals(0, graph.conditions.all().size)
    }

    /** Row 12 (AC 2): P82-4 writes the held row alone — its pre-allocated id, no link, no event. */
    @Test fun saveConditionOnlyWritesOneUnlinkedRowWithTheHeldIdAndNoEvent() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        val host = host(model)
        val held = held(model)

        model.saveConditionOnly()
        advanceUntilIdle()

        val row = graph.conditions.all().single()
        assertEquals(held.id, row.id)
        assertEquals(OperationalCondition.DOWN, row.condition)
        assertEquals("Will not start\nStarter clicks", row.reason)
        assertEquals("2026-04-15", row.occurredOn)
        assertNull("unlinked", row.eventId)
        assertEquals("no event", 0, graph.events.forAsset(gen).size)
        assertEquals(1, host.finished)
        assertTrue("nothing handed over", host.handedOver.isEmpty())
        assertNull(model.state.value.held)
    }

    /** Row 12: dismissing the question drops the hold, keeps the form as typed and writes nothing. */
    @Test fun dismissingTheQuestionKeepsTheFormAndWritesNothing() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        val host = host(model)
        held(model)

        model.dismissQuestion()
        advanceUntilIdle()

        val state = model.state.value
        assertNull(state.held)
        assertFalse(state.asking)
        assertEquals(OperationalCondition.DOWN, state.choice)
        assertEquals("Will not start\nStarter clicks", state.reason)
        assertEquals("2026-04-15", state.occurredOn)
        assertTrue("Save condition is live again", state.canSave)
        assertEquals(0, graph.conditions.all().size)
        assertEquals(0, graph.events.forAsset(gen).size)
        assertEquals("the sheet stays open", 0, host.finished)
    }

    /** Row 13 (K5): P82-3 hands the draft to the host once, writes nothing, and keeps the hold. */
    @Test fun logIncidentDetailsHandsOverTheDraftAndWritesNothing() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        val host = host(model)
        val held = held(model)

        model.logIncidentDetails()
        advanceUntilIdle()

        assertEquals(listOf(PendingCondition(held.id, OperationalCondition.DOWN, "2026-04-15", "Will not start\nStarter clicks")), host.handedOver)
        assertEquals(0, graph.conditions.all().size)
        assertEquals(0, graph.events.forAsset(gen).size)
        assertEquals("the hold is kept", held, model.state.value.held)
        assertFalse("the question is not drawn over the hand-over", model.state.value.asking)
        assertEquals(0, host.finished)
    }

    /** Row 13: a double tap on P82-3 hands over once. */
    @Test fun aDoubleTapOnLogIncidentDetailsHandsOverOnce() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        val host = host(model)
        held(model)

        model.logIncidentDetails()
        model.logIncidentDetails()
        advanceUntilIdle()

        assertEquals(1, host.handedOver.size)
    }

    /** The combined Save landing, as the Incident entry makes it, for the held draft. */
    private suspend fun landed(gen: AssetId, held: PendingCondition) = graph.recordConditionWithIncident.run(
        gen,
        held.id,
        ConditionCommand(held.condition, held.occurredOn, null, "UTC", held.reason),
        EventCommand(
            assetId = gen, profileId = null, kind = EventKind.INCIDENT, title = "Will not start",
            occurredOn = held.occurredOn, occurredTime = null, tzId = "UTC", notes = "Starter clicks",
            values = emptyMap(), consumables = emptyList(),
        ),
    )

    /** Row 13: back on the sheet with the held id stored (the combined Save landed), it closes and writes nothing. */
    @Test fun onShownWithTheHeldIdStoredClosesWithoutAWrite() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        val host = host(model)
        val held = held(model)
        model.logIncidentDetails()
        advanceUntilIdle()
        landed(gen, held)

        model.onShown()
        advanceUntilIdle()

        assertEquals(1, host.finished)
        assertEquals(listOf(held.id), graph.conditions.all().map { it.id })
        assertEquals(1, graph.events.forAsset(gen).size)
        assertNull(model.state.value.held)
    }

    /** Row 13: back on the sheet without it (back from the entry), the question is asked again. */
    @Test fun onShownWithoutItAsksAgain() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        val host = host(model)
        val held = held(model)
        model.logIncidentDetails()
        advanceUntilIdle()

        model.onShown()
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state.asking)
        assertFalse(state.handingOver)
        assertEquals(held, state.held)
        assertEquals(0, host.finished)
        assertEquals(0, graph.conditions.all().size)
        // And it can be handed over again.
        model.logIncidentDetails()
        advanceUntilIdle()
        assertEquals(2, host.handedOver.size)
    }

    /** Row 13: the question stays hidden until the one read answers. */
    @Test fun theQuestionIsHiddenUntilTheReadAnswers() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        held(model)
        model.logIncidentDetails()
        advanceUntilIdle()

        model.onShown()
        assertTrue("checking", model.state.value.checking)
        assertFalse("hidden while the read is out", model.state.value.asking)
        advanceUntilIdle()
        assertFalse(model.state.value.checking)
        assertTrue("asked once the read answers", model.state.value.asking)
    }

    /** Row 13 (C2): P82-4 after the combined Save already landed that id writes nothing more. */
    @Test fun saveConditionOnlyAfterALandedCombinedSaveWritesNothing() = runTest(scheduler) {
        val gen = generator()
        val model = model(gen)
        val host = host(model)
        val held = held(model)
        model.logIncidentDetails()
        advanceUntilIdle()
        // Back on the sheet before the entry's Save committed: the read finds nothing and asks again.
        model.onShown()
        advanceUntilIdle()
        assertTrue(model.state.value.asking)
        // The Save lands after that read, so no resume will close the sheet; P82-4 is tapped instead.
        val stored = landed(gen, held)
        model.saveConditionOnly()
        advanceUntilIdle()

        assertEquals(listOf(stored.condition), graph.conditions.all())
        assertNotNull("still the linked row", graph.conditions.all().single().eventId)
        assertEquals(1, graph.events.forAsset(gen).size)
        assertEquals(1, host.finished)
    }
}
