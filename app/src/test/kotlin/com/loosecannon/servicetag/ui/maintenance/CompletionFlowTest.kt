package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.meterDefinitionOf
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    // ---------------------------------------------------------------- #123: the sweep after the write (§3.7)

    /** #123: a sweep that records, as it runs, the events the store holds and whether an offer is open. */
    private fun sweep(failure: Throwable? = null) = CountingSweep(failure) {
        SweepSighting(events = graph.events.all().size, offerOpen = graph.completionFlow.offer.value != null)
    }

    /** Two sprinklers in "North run" and its quarterly "Head check" round, open today. */
    private suspend fun aGroupRound(): Pair<ScheduleId, List<AssetId>> {
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
        return schedule.id to listOf(north.id, south.id)
    }

    /** A QUICK "Blade sharpen" on a Mower that is DOWN, so its completion asks "Mark operational?". */
    private suspend fun aQuickScheduleWithAnOffer(): ScheduleId {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        graph.conditions.insert(conditionRow("c-mower", mower.id.value, OperationalCondition.DOWN, "2026-04-01"))
        return graph.saveSchedule.run(null, quarterly(mower.id)).id
    }

    /** F1: "Complete all" runs its `afterWrite` once, after the members are written and before any offer. */
    @Test fun completeAllRunsItsAfterWriteOnceAfterTheWrite() = runTest {
        val (round, members) = aGroupRound()
        val sweep = sweep()
        val flow = graph.completionFlow

        val outcome = async { flow.completeAll(round) { sweep.run() } }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))

        val events = (outcome.await() as CompletionOutcome.Completed).events
        assertEquals(members.toSet(), events.map { it.assetId }.toSet())
        assertEquals("once, with both members stored and no offer open", listOf(SweepSighting(2, false)), sweep.sightings)
    }

    /** F2: "Complete selected" runs its `afterWrite` once, after the one write. */
    @Test fun completeSelectedRunsItsAfterWriteOnceAfterTheWrite() = runTest {
        val (round, members) = aGroupRound()
        val sweep = sweep()
        val flow = graph.completionFlow

        val outcome = async { flow.completeSelected(round, members) { sweep.run() } }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))

        assertEquals(2, (outcome.await() as CompletionOutcome.Completed).events.size)
        assertEquals(listOf(SweepSighting(2, false)), sweep.sightings)
    }

    /** F3, a pin: backed out at the prompt, or refused (an archived schedule), neither group call runs `afterWrite`. */
    @Test fun aCancelledOrRefusedGroupCompletionRunsNoAfterWrite() = runTest {
        val (round, members) = aGroupRound()
        val sweep = sweep()
        val flow = graph.completionFlow

        val cancelledAll = async { flow.completeAll(round) { sweep.run() } }
        flow.prompt.first { it != null }
        flow.cancel()
        assertEquals(CompletionOutcome.Cancelled, cancelledAll.await())
        val cancelledSelected = async { flow.completeSelected(round, members) { sweep.run() } }
        flow.prompt.first { it != null }
        flow.cancel()
        assertEquals(CompletionOutcome.Cancelled, cancelledSelected.await())

        graph.archiveSchedule.run(round, true)
        val refusedAll = async { flow.completeAll(round) { sweep.run() } }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))
        assertTrue("${refusedAll.await()}", refusedAll.await() is CompletionOutcome.Refused)
        val refusedSelected = async { flow.completeSelected(round, members) { sweep.run() } }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))
        assertTrue("${refusedSelected.await()}", refusedSelected.await() is CompletionOutcome.Refused)

        assertEquals(0, sweep.runs)
        assertEquals(0, graph.events.all().size)
    }

    /** F4, a pin: on a QUICK schedule with an offer to make, `afterWrite` runs once, before the offer is published. */
    @Test fun completeRunsItsAfterWriteBeforeTheOffer() = runTest {
        val schedule = aQuickScheduleWithAnOffer()
        val sweep = sweep()
        val flow = graph.completionFlow

        val outcome = async { flow.complete(schedule) { sweep.run() } }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))
        flow.offer.first { it != null }
        assertEquals("once, the event stored and the offer not yet open", listOf(SweepSighting(1, false)), sweep.sightings)
        flow.declineOffer()

        assertTrue(outcome.await() is CompletionOutcome.Completed)
        assertEquals(1, sweep.runs)
    }

    /**
     * F5 (F-3, I13): the sweep cancelled under the flow — `runAfterWrite` rethrows, so the call ends in that
     * `CancellationException`; the event stands and no offer is published.
     */
    @Test fun aCancelledSweepEndsTheCallWithTheEventWrittenAndNoOffer() = runTest {
        val schedule = aQuickScheduleWithAnOffer()
        val sweep = sweep(CancellationException("the screen left"))
        val flow = graph.completionFlow
        val offered = mutableListOf<Any>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { flow.offer.filterNotNull().collect { offered += it } }

        val outcome = async { runCatching { flow.complete(schedule) { sweep.runAfterWrite("CompletionFlowTest") } } }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))

        val failure = outcome.await().exceptionOrNull()
        assertTrue("$failure", failure is CancellationException)
        assertEquals(1, sweep.runs)
        assertEquals("the event stands", 1, graph.events.all().size)
        assertTrue("no offer published", offered.isEmpty())
        assertNull(flow.offer.value)
    }
}
