package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.meterDefinitionOf
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * The one completion mechanism in 1.2.
 *
 * What is proved here is the affordance's promises: it defaults to today and accepts a **backdated**
 * date that the engine then derives from, it refuses to complete a meter-rule schedule until the
 * reading is entered, a `FORM` schedule routes into its profile form and **fabricates nothing**, and
 * backing out writes nothing at all. The recurrence arithmetic behind those answers is the engine's
 * and is proved in `:core`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompletionFlowTest {

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

    private fun quarterly(assetId: AssetId, title: String = "Blade sharpen") = ScheduleCommand(
        targetAssetId = assetId,
        targetGroupId = null,
        title = title,
        timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH,
        anchorOn = "2026-01-01",
    )

    /**
     * Matrix row **"a backdated completion"**: the affordance defaults to **today**, accepts an
     * optional time, and a completion dated **yesterday** yields the next due date derived from the
     * backdated event — not from today (D-25, the D7 Phase 3 exit criterion).
     *
     * FIXED quarterly from Jan 1: the Apr 1 round completed on Apr 14 advances to Jul 1 either way,
     * so the schedule this asserts on is COMPLETION-based, where the two answers differ by a day and
     * a silent today-only completion would be visible.
     */
    @Test fun theAffordanceDefaultsToTodayAndABackdatedCompletionIsWhatTheEngineDerivesFrom() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val schedule = graph.saveSchedule.run(
            null,
            quarterly(mower.id).copy(
                timeBasis = com.loosecannon.servicetag.core.model.TimeBasis.COMPLETION,
                timeInterval = 30,
                timeUnit = RecurrenceUnit.DAY,
            ),
        )
        val flow = graph.completionFlow

        val outcome = async { flow.complete(schedule.id) }
        val prompt = flow.prompt.first { it != null }!!
        // Today by default, and the schedule it is about is named.
        assertEquals("2026-04-15", prompt.occurredOn)
        assertEquals("Blade sharpen", prompt.scheduleTitle)
        assertFalse("no meter rule, so no reading is demanded", prompt.needsMeterReading)

        // Yesterday, with a time.
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-14", occurredTime = "08:30")))
        val completed = outcome.await()
        assertTrue("$completed", completed is CompletionOutcome.Completed)

        val event = (completed as CompletionOutcome.Completed).events.single()
        assertEquals("2026-04-14", event.occurredOn)
        assertEquals("08:30", event.occurredTime)
        assertEquals(EventSource.SCHEDULE_QUICK_COMPLETE, event.source)
        // Derived from the backdated event: 2026-04-14 + 30 days, not today + 30.
        assertEquals("2026-05-14", graph.scheduleStates.get(schedule.id)!!.computedDueOn)
        // And the prompt is closed again, so nothing can answer it twice.
        assertNull(flow.prompt.value)
    }

    /**
     * Matrix row **"a meter completion without its reading"**, first half: a meter-rule schedule
     * **cannot** be completed until the reading is entered.
     *
     * The prompt stays open, no event is written, and the reading — once given — lands as a
     * measurement the engine's meter side advances from, which is the reason the demand exists.
     */
    @Test fun aMeterRuleScheduleCannotBeCompletedUntilTheReadingIsEntered() = runTest {
        val tractor = graph.createAsset.run(AssetCommand(name = "Tractor", category = "Yard"))
        val hours = meterDefinitionOf("d-hours", tractor.id.value)
        graph.definitions.upsert(hours)
        val schedule = graph.saveSchedule.run(
            null,
            quarterly(tractor.id, title = "Oil change").copy(
                meterDefinitionId = hours.id,
                meterInterval = 100.0,
                anchorMeter = 400.0,
            ),
        )
        val flow = graph.completionFlow

        val outcome = async { flow.complete(schedule.id) }
        val prompt = flow.prompt.first { it != null }!!
        assertTrue(prompt.needsMeterReading)
        assertEquals("Engine hours", prompt.meterLabel)
        assertEquals("h", prompt.meterUnit)

        // An answer with no reading is refused and the prompt stays open.
        assertFalse(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))
        assertFalse(flow.submit(CompletionAnswer(occurredOn = "2026-04-15", meterValue = "   ")))
        assertNotNull("the prompt is still open", flow.prompt.value)
        assertEquals("and nothing was written", 0, graph.events.all().size)

        // With the reading it completes, and the reading is a measurement on the completion.
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15", meterValue = "512")))
        val completed = outcome.await() as CompletionOutcome.Completed
        val event = completed.events.single()
        assertEquals(512.0, event.measurements.single { it.definitionId == hours.id }.valueNum)
        // The meter side advanced from the reading the completion carried, not from the anchor.
        val state = graph.scheduleStates.get(schedule.id)!!
        assertEquals(512.0, state.lastCompletedMeter)
        assertEquals(612.0, state.computedDueMeter)
    }

    /**
     * Matrix row **"a meter completion without its reading"**, second half: a `FORM` schedule routes
     * into its profile form and **fabricates nothing**.
     *
     * No prompt opens, no event is written, and the outcome names the asset and the profile the form
     * belongs to — which is also why the in-app path can never produce a `details_pending`
     * completion.
     */
    @Test fun aFormScheduleRoutesIntoItsProfileFormAndWritesNothing() = runTest {
        val pump = graph.createAsset.run(AssetCommand(name = "Pump", category = "Water"))
        val profile = graph.saveProfile.run(
            null,
            ProfileCommand(
                assetId = pump.id,
                name = "Inspection",
                eventKind = EventKind.INSPECTION,
                defaultTitle = "Inspected",
                fields = emptyList(),
                consumables = emptyList(),
            ),
        )
        val schedule = graph.saveSchedule.run(
            null,
            quarterly(pump.id, title = "Service").copy(
                completionMode = CompletionMode.FORM,
                profileId = profile.id,
            ),
        )

        val outcome = graph.completionFlow.complete(schedule.id)
        assertTrue("$outcome", outcome is CompletionOutcome.NeedsForm)
        outcome as CompletionOutcome.NeedsForm
        assertEquals(pump.id, outcome.assetId)
        assertEquals(profile.id, outcome.profileId)
        assertNull("no affordance opened", graph.completionFlow.prompt.value)
        assertEquals("and nothing was fabricated", 0, graph.events.all().size)
    }

    /** Backing out writes nothing: the affordance's whole promise, asserted rather than assumed. */
    @Test fun backingOutOfTheAffordanceWritesNothing() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val schedule = graph.saveSchedule.run(null, quarterly(mower.id))

        val flow = graph.completionFlow
        val outcome = async { flow.complete(schedule.id) }
        flow.prompt.first { it != null }
        flow.cancel()

        assertEquals(CompletionOutcome.Cancelled, outcome.await())
        assertEquals(0, graph.events.all().size)
        assertNull(flow.prompt.value)
    }

    /**
     * A group round through the flow: "Complete all" records **every required member exactly once**,
     * on each member's own Asset, and a group target is never asked for a reading — it carries no
     * meter rule at all (invariants 2, 31, 34).
     */
    @Test fun completeAllRecordsEveryRequiredMemberOnItsOwnAsset() = runTest {
        val north = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
        val south = graph.createAsset.run(AssetCommand(name = "Sprinkler 2", category = "Irrigation"))
        val group = graph.saveGroup.run(
            null,
            GroupCommand(
                name = "North run",
                members = listOf(GroupMemberInput(assetId = north.id), GroupMemberInput(assetId = south.id)),
            ),
        )
        val schedule = graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = null,
                targetGroupId = group.id,
                title = "Head check",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
            ),
        )

        val flow = graph.completionFlow
        val outcome = async { flow.completeAll(schedule.id) }
        val prompt = flow.prompt.first { it != null }!!
        assertFalse("a group target carries no meter rule", prompt.needsMeterReading)
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))

        val events = (outcome.await() as CompletionOutcome.Completed).events
        assertEquals(setOf(north.id, south.id), events.map { it.assetId }.toSet())
        assertEquals("one event each, and no more", 2, graph.events.all().size)
    }
}
