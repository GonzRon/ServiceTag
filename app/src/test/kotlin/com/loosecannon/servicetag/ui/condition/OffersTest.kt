package com.loosecannon.servicetag.ui.condition

import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.ui.journal.EventEntryViewModel
import com.loosecannon.servicetag.ui.maintenance.CompletionAnswer
import com.loosecannon.servicetag.ui.maintenance.CompletionOutcome
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two offers (spec §3.3, §5.4; inv. 81, 93): each is a **question**, made only where it could be
 * kept, and nothing is written unless it is accepted. "Mark operational?" follows a completion — once
 * per completed DOWN or DEGRADED member, one at a time (plan decision 12) — or a saved MAINTENANCE or
 * REPLACEMENT entry; "Start the season now?" and "End the season now?" follow a season entry on a
 * MANUAL asset in the opposite phase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OffersTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-04-15")
        // Midday, so the entry form's own "today" is the 15th in any zone the JVM runs in.
        graph.now = dayMillis("2026-04-15") + 12 * 60 * 60 * 1000L
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- fixtures

    private fun quarterly(assetId: AssetId, title: String) = ScheduleCommand(
        targetAssetId = assetId,
        targetGroupId = null,
        title = title,
        timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH,
        anchorOn = "2026-01-01",
    )

    private suspend fun down(assetId: AssetId, on: String = "2026-04-01", reason: String = "") =
        graph.conditions.insert(conditionRow("c-${assetId.value}", assetId.value, OperationalCondition.DOWN, on, reason))

    private suspend fun current(assetId: AssetId) = ConditionHistory.of(graph.conditions.forAsset(assetId)).current

    /**
     * A QUICK task whose profile logs [kind], so its completion takes that kind (`CompleteSchedule`):
     * a season-start task completed is a SEASON_START event (spec §3.3).
     */
    private suspend fun taskLogging(assetId: AssetId, kind: EventKind, title: String) = graph.saveSchedule.run(
        null,
        quarterly(assetId, title).copy(
            profileId = graph.saveProfile.run(
                null,
                ProfileCommand(
                    assetId = assetId, name = title, eventKind = kind, defaultTitle = title,
                    fields = emptyList(), consumables = emptyList(),
                ),
            ).id,
        ),
    ).id

    /** A new, profile-less entry of [kind], as the journal's preset kinds open it. */
    private fun entry(assetId: AssetId, kind: EventKind) = EventEntryViewModel(
        graph.assets, graph.definitions, graph.profiles, graph.events,
        graph.logEvent, graph.updateEvent, graph.clock,
        assetId, null, null, kind, graph.eventOffers,
    )

    /** Logs one entry and returns its model once the save has settled, with its `saved` shots counted. */
    private suspend fun TestScope.logged(
        assetId: AssetId,
        kind: EventKind,
        title: String,
        on: String = "2026-04-15",
    ): Pair<EventEntryViewModel, MutableList<EventId>> {
        val model = entry(assetId, kind)
        val saved = mutableListOf<EventId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.saved.collect { saved += it } }
        model.state.first { it.loaded }
        model.onTitle(title)
        model.onDate(on)
        model.save()
        advanceUntilIdle()
        return model to saved
    }

    // ---------------------------------------------------------------- the operational offer

    /**
     * A completion on a DOWN asset asks "Mark operational?" — S17 over S19, S7 and S20 — and the
     * completion call waits for the answer. Accepting writes one OPERATIONAL row, dated the event's
     * day and linked to it; the DOWN row before it stays.
     */
    @Test fun anOperationalOfferAfterACompletionOnADownAsset() = runTest(scheduler) {
        val ups = graph.createAsset.run(AssetCommand(name = "UPS", category = "Power")).id
        val schedule = graph.saveSchedule.run(null, quarterly(ups, "Battery self-test"))
        down(ups, reason = "Alarm on")
        val flow = graph.completionFlow

        val outcome = async { flow.complete(schedule.id) }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-14")))
        val offer = flow.offer.first { it != null }!!

        assertEquals("Mark operational?", offer.title)
        assertEquals("You logged Battery self-test. Is UPS working normally again?", offer.body)
        assertEquals("Mark operational", offer.acceptLabel)
        assertEquals("Not yet", offer.declineLabel)
        assertFalse("the completion waits for the answer", outcome.isCompleted)
        // Asked, not applied: the completion alone wrote no condition (inv. 81).
        assertEquals(OperationalCondition.DOWN, current(ups)!!.condition)

        assertTrue(flow.acceptOffer())
        val event = (outcome.await() as CompletionOutcome.Completed).events.single()

        val rows = graph.conditions.forAsset(ups)
        assertEquals(2, rows.size)
        val now = ConditionHistory.of(rows).current!!
        assertEquals(OperationalCondition.OPERATIONAL, now.condition)
        assertEquals("2026-04-14", now.occurredOn)
        assertEquals(event.id, now.eventId)
        assertNull(flow.offer.value)
    }

    /**
     * A group round completed for three DOWN members asks three times, **one at a time**, in the
     * order the events were written; declining one does not cancel the others (plan decision 12).
     */
    @Test fun oneOfferPerDownMemberInTurn() = runTest(scheduler) {
        val members = listOf("UPS A", "UPS B", "UPS C").map {
            graph.createAsset.run(AssetCommand(name = it, category = "Power")).id
        }
        val group = graph.saveGroup.run(
            null,
            GroupCommand(name = "Rack", members = members.map { GroupMemberInput(assetId = it) }),
        )
        val schedule = graph.saveSchedule.run(
            null,
            quarterly(members.first(), "Battery self-test").copy(targetAssetId = null, targetGroupId = group.id),
        )
        members.forEach { down(it) }
        val flow = graph.completionFlow

        val outcome = async { flow.completeAll(schedule.id) }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))

        val asked = mutableListOf<String>()
        repeat(3) { turn ->
            advanceUntilIdle()
            val offer = flow.offer.value as? OperationalOfferPrompt
            assertNotNull("member ${turn + 1} of 3 is asked", offer)
            assertFalse("each member is asked once", offer!!.assetName in asked)
            asked += offer.assetName
            // One at a time: the call is still waiting on this answer.
            assertFalse(outcome.isCompleted)
            if (turn == 1) assertTrue(flow.acceptOffer()) else flow.declineOffer()
        }
        val events = (outcome.await() as CompletionOutcome.Completed).events

        assertEquals(events.map { event -> graph.assets.get(event.assetId)!!.name }, asked)
        assertEquals(setOf("UPS A", "UPS B", "UPS C"), asked.toSet())
        val accepted = events[1].assetId
        members.forEach { member ->
            val expected = if (member == accepted) OperationalCondition.OPERATIONAL else OperationalCondition.DOWN
            assertEquals(expected, current(member)!!.condition)
        }
        assertEquals("one row added in all", 4, graph.conditions.all().size)
    }

    /**
     * A saved MAINTENANCE or REPLACEMENT entry on a DOWN asset asks; an INSPECTION does not, and
     * leaves at once. The offer names the entry's title and the asset.
     */
    @Test fun aMaintenanceOrReplacementEventOffersAnInspectionDoesNot() = runTest(scheduler) {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        val gen = AssetId("gen")
        down(gen)

        val (maintenance, maintenanceSaved) = logged(gen, EventKind.MAINTENANCE, "Starter rebuilt")
        val asked = maintenance.state.value.offer as OperationalOfferPrompt
        assertEquals("You logged Starter rebuilt. Is Generator working normally again?", asked.body)
        assertEquals("the screen waits for the answer", emptyList<EventId>(), maintenanceSaved)
        maintenance.declineOffer()

        val (replacement, _) = logged(gen, EventKind.REPLACEMENT, "Battery replaced")
        assertTrue(replacement.state.value.offer is OperationalOfferPrompt)
        replacement.declineOffer()

        val (inspection, inspectionSaved) = logged(gen, EventKind.INSPECTION, "Visual check")
        assertNull(inspection.state.value.offer)
        assertEquals(1, inspectionSaved.size)

        // An asset that is not DOWN or DEGRADED is not asked either.
        graph.assets.upsert(assetRow("mower", name = "Mower"))
        val (healthy, _) = logged(AssetId("mower"), EventKind.MAINTENANCE, "Blade sharpened")
        assertNull(healthy.state.value.offer)
        assertEquals(OperationalCondition.DOWN, current(gen)!!.condition)
    }

    /** "Not yet" writes nothing — after a completion and after an entry — and the screen leaves. */
    @Test fun notYetWritesNothing() = runTest(scheduler) {
        val ups = graph.createAsset.run(AssetCommand(name = "UPS", category = "Power")).id
        val schedule = graph.saveSchedule.run(null, quarterly(ups, "Battery self-test"))
        down(ups)
        val flow = graph.completionFlow

        val outcome = async { flow.complete(schedule.id) }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))
        flow.offer.first { it != null }
        flow.declineOffer()
        assertTrue(outcome.await() is CompletionOutcome.Completed)

        val (model, saved) = logged(ups, EventKind.MAINTENANCE, "Fan cleaned")
        assertTrue(model.state.value.offer is OperationalOfferPrompt)
        model.declineOffer()
        advanceUntilIdle()

        assertEquals(1, saved.size)
        assertNull(model.state.value.offer)
        assertEquals(1, graph.conditions.all().size)
        assertEquals(OperationalCondition.DOWN, current(ups)!!.condition)
    }

    /**
     * The carry-forward from B06: an event dated after today is never offered — the row would be
     * `CONDITION_DATE_IN_FUTURE`, and there is no clamp — whether it is a completion or an entry.
     */
    @Test fun anEventDatedAfterTodayIsNeverOffered() = runTest(scheduler) {
        val ups = graph.createAsset.run(AssetCommand(name = "UPS", category = "Power")).id
        val schedule = graph.saveSchedule.run(null, quarterly(ups, "Battery self-test"))
        down(ups)
        val flow = graph.completionFlow

        val outcome = async { flow.complete(schedule.id) }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-16")))
        advanceUntilIdle()
        assertNull("nothing is asked", flow.offer.value)
        assertTrue("the completion returns with nothing to ask", outcome.await() is CompletionOutcome.Completed)

        val (model, saved) = logged(ups, EventKind.REPLACEMENT, "Battery replaced", on = "2026-04-20")
        assertNull(model.state.value.offer)
        assertEquals(1, saved.size)
        assertEquals(1, graph.conditions.all().size)
    }

    /**
     * The carry-forward from B06's review: an event whose zone does not resolve on this device is
     * never offered — the row would take that zone, and the command would refuse it.
     */
    @Test fun anEventInAZoneThisDeviceCannotResolveIsNeverOffered() = runTest(scheduler) {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        down(AssetId("gen"))
        val offers = graph.eventOffers
        val event = AssetEvent(
            id = EventId("e-far"), assetId = AssetId("gen"), kind = EventKind.MAINTENANCE, title = "Starter rebuilt",
            profileId = null, occurredOn = "2026-04-15", occurredTime = null, tzId = "Mars/Olympus_Mons", notes = "",
            source = EventSource.MANUAL, sourceRef = null, createdAt = 0L, updatedAt = 0L,
            measurements = emptyList(), consumables = emptyList(),
        )

        assertEquals(emptyList<EventOffer>(), offers.offersAfter(event))
        assertTrue(
            "the same event in a zone that resolves is offered",
            offers.offersAfter(event.copy(tzId = "UTC")).single() is OperationalOfferPrompt,
        )
    }

    /**
     * The carry-forward from B06: the offer is made once. Its buttons disable on the first tap, and a
     * second tap writes nothing — so a double tap can never record a second OPERATIONAL row.
     */
    @Test fun aDoubleTapOnTheOfferWritesOneRow() = runTest(scheduler) {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        val gen = AssetId("gen")
        down(gen)

        val (model, saved) = logged(gen, EventKind.MAINTENANCE, "Starter rebuilt")
        assertTrue(model.state.value.offer is OperationalOfferPrompt)
        model.acceptOffer()
        assertTrue("the first tap disables the offer", model.state.value.offer?.accepting != false)
        model.acceptOffer()
        advanceUntilIdle()

        assertEquals("one OPERATIONAL row, not two", 2, graph.conditions.all().size)
        assertEquals(OperationalCondition.OPERATIONAL, current(gen)!!.condition)
        assertEquals(1, saved.size)
        assertNull(model.state.value.offer)
    }

    // ---------------------------------------------------------------- the season offer

    /**
     * A season entry offers only on a MANUAL asset in the **opposite** phase: SEASON_START while out
     * of season asks S51 with S40, SEASON_END while in season asks S52 with S41; the same entry in
     * the same phase, or on a CALENDAR asset, asks nothing. Accepting writes the activation, linked.
     */
    @Test fun theSeasonOfferOnlyOnAManualAssetInTheOppositePhase() = runTest(scheduler) {
        graph.assets.upsert(assetRow("tub", name = "Hot tub", seasonMode = SeasonMode.MANUAL))
        val tub = AssetId("tub")

        val (start, _) = logged(tub, EventKind.SEASON_START, "Opened for summer")
        val startOffer = start.state.value.offer as SeasonOfferPrompt
        assertEquals(SeasonAction.START, startOffer.action)
        assertEquals("Start the season now?", startOffer.title)
        assertEquals("You logged Opened for summer.", startOffer.body)
        assertEquals("Start season", startOffer.acceptLabel)
        assertEquals("Not now", startOffer.declineLabel)
        start.acceptOffer()
        advanceUntilIdle()
        val started = graph.seasonActivations.forAsset(tub).single()
        assertEquals(SeasonAction.START, started.action)
        assertEquals("2026-04-15", started.occurredOn)
        assertEquals(startOffer.event.id, started.eventId)

        // In season now: another SEASON_START asks nothing; SEASON_END asks to end it.
        val (again, _) = logged(tub, EventKind.SEASON_START, "Opened again")
        assertNull(again.state.value.offer)
        val (end, _) = logged(tub, EventKind.SEASON_END, "Closed for winter")
        val endOffer = end.state.value.offer as SeasonOfferPrompt
        assertEquals("End the season now?", endOffer.title)
        assertEquals("End season", endOffer.acceptLabel)
        end.declineOffer()

        // A CALENDAR asset's season is its dates; an entry never asks.
        graph.assets.upsert(
            assetRow("snow", name = "Snowblower", seasonMode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31"),
        )
        val (calendar, _) = logged(AssetId("snow"), EventKind.SEASON_START, "Season opened")
        assertNull(calendar.state.value.offer)
        assertEquals(1, graph.seasonActivations.all().size)
    }

    /**
     * The ruling on B12's review, I-1: a **completion** that takes a season kind from its profile
     * asks the season offer too — S51 with S40 on a MANUAL asset that is out of season. "Not now"
     * writes nothing; accepting writes one activation, dated the event's day and linked to it.
     */
    @Test fun aSeasonStartCompletionAsksToStartTheSeason() = runTest(scheduler) {
        graph.assets.upsert(assetRow("tub", name = "Hot tub", seasonMode = SeasonMode.MANUAL))
        val tub = AssetId("tub")
        val cover = taskLogging(tub, EventKind.SEASON_START, "Cover off")
        val fill = taskLogging(tub, EventKind.SEASON_START, "Fill and heat")
        val flow = graph.completionFlow

        val declined = async { flow.complete(cover) }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))
        advanceUntilIdle()
        val first = flow.offer.value as SeasonOfferPrompt
        assertEquals(SeasonAction.START, first.action)
        assertEquals("Start the season now?", first.title)
        assertEquals("You logged Cover off.", first.body)
        assertEquals("Start season", first.acceptLabel)
        assertEquals("Not now", first.declineLabel)
        flow.declineOffer()
        assertTrue(declined.await() is CompletionOutcome.Completed)
        assertEquals(emptyList<SeasonActivation>(), graph.seasonActivations.all())

        val accepted = async { flow.complete(fill) }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-14")))
        advanceUntilIdle()
        assertTrue(flow.offer.value is SeasonOfferPrompt)
        assertTrue(flow.acceptOffer())
        val event = (accepted.await() as CompletionOutcome.Completed).events.single()

        val started = graph.seasonActivations.all().single()
        assertEquals(SeasonAction.START, started.action)
        assertEquals("2026-04-14", started.occurredOn)
        assertEquals(event.id, started.eventId)
        assertEquals(EventKind.SEASON_START, event.kind)
    }

    /**
     * A season-kind completion on a DOWN MANUAL asset makes **both** offers, one at a time:
     * "Mark operational?" first, then the season offer — declining the first does not lose the
     * second (the ruling on B12's review, I-1).
     */
    @Test fun aSeasonCompletionOnADownManualAssetAsksBothInTurn() = runTest(scheduler) {
        graph.assets.upsert(assetRow("tub", name = "Hot tub", seasonMode = SeasonMode.MANUAL))
        val tub = AssetId("tub")
        down(tub, reason = "Heater fault")
        val task = taskLogging(tub, EventKind.SEASON_START, "Cover off")
        val flow = graph.completionFlow

        val outcome = async { flow.complete(task) }
        flow.prompt.first { it != null }
        assertTrue(flow.submit(CompletionAnswer(occurredOn = "2026-04-15")))
        advanceUntilIdle()
        val first = flow.offer.value
        assertTrue("$first", first is OperationalOfferPrompt)
        assertEquals("You logged Cover off. Is Hot tub working normally again?", first!!.body)
        flow.declineOffer()
        advanceUntilIdle()
        val second = flow.offer.value
        assertTrue("$second", second is SeasonOfferPrompt)
        assertFalse("still waiting on the second answer", outcome.isCompleted)
        flow.acceptOffer()
        assertTrue(outcome.await() is CompletionOutcome.Completed)

        assertEquals(OperationalCondition.DOWN, current(tub)!!.condition)
        assertEquals(1, graph.conditions.all().size)
        assertEquals(SeasonAction.START, graph.seasonActivations.all().single().action)
    }

    /** "Not now" writes nothing: the entry stands, and the phase is what it was. */
    @Test fun notNowWritesNothing() = runTest(scheduler) {
        graph.assets.upsert(assetRow("tub", name = "Hot tub", seasonMode = SeasonMode.MANUAL))
        graph.seasonActivations.insert(
            SeasonActivation("a-start", AssetId("tub"), SeasonAction.START, "2026-04-01", null, dayMillis("2026-04-01")),
        )

        val (model, saved) = logged(AssetId("tub"), EventKind.SEASON_END, "Closed early")
        assertTrue(model.state.value.offer is SeasonOfferPrompt)
        model.declineOffer()
        advanceUntilIdle()

        assertEquals(1, saved.size)
        assertEquals(listOf("a-start"), graph.seasonActivations.all().map { it.id })
        assertEquals(1, graph.events.all().size)
    }
}
