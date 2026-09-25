package com.loosecannon.servicetag.ui.condition

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
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

    private fun model(assetId: AssetId) =
        ChangeConditionViewModel(graph.recordCondition, graph.todayPort, assetId) { ZoneId.of("UTC") }

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
        // Two taps in one frame: one row.
        model.save()
        model.save()
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

    @Test fun thePastIsAllowed() = runTest(scheduler) {
        val gen = generator()
        graph.conditions.insert(conditionRow("c-now", "gen", OperationalCondition.OPERATIONAL, "2026-04-10"))
        val model = model(gen)

        // A backdated change sorts into place in the history; nothing earlier is edited.
        model.choose(OperationalCondition.DEGRADED)
        model.onDate("2025-11-02")
        model.save()
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
}
