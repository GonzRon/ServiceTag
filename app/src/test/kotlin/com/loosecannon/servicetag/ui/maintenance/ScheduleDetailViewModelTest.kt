package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.listedForDue
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.reminders.DeliveryInput
import com.loosecannon.servicetag.reminders.DigestPolicy
import com.loosecannon.servicetag.reminders.Fixture
import com.loosecannon.servicetag.reminders.ItemPost
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.meterDefinitionOf
import com.loosecannon.servicetag.testing.scheduleOf
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The five operations, and **what each of them does not do**.
 *
 * The failure this class exists to catch is not a wrong date: it is five actions quietly collapsing
 * into one generic "reschedule" that does a bit of all of them. So every test asserts both halves,
 * against master plan §5.2's table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleDetailViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-04-15")
        // The D-27 pin's floor is `max(anchorOn, createdOn)`, so the clock has to be a real date
        // before anything is created: the fixture's default 1970 would pin every schedule created
        // here on its anchor and make the first round Jan 1 rather than Apr 1.
        graph.now = dayMillis("2026-02-10")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(scheduleId: ScheduleId) = ScheduleDetailViewModel(
        schedules = graph.schedules,
        assets = graph.assets,
        groups = graph.groups,
        completions = ScheduleCompletions { id -> graph.events.all().filter { it.scheduleId == id } },
        closures = ScheduleClosures { id -> graph.closures.forSchedule(id) },
        recompute = graph.recomputeSchedules,
        postponeSchedule = graph.postponeSchedule,
        pauseSchedule = graph.pauseSchedule,
        archiveSchedule = graph.archiveSchedule,
        closeRoundUseCase = graph.closeRound,
        snoozer = graph.scheduleSnooze,
        today = graph.todayPort,
        clock = graph.clock,
        completion = graph.completionFlow,
        scheduleId = scheduleId,
    )

    private suspend fun anAssetSchedule(
        title: String = "Blade sharpen",
        basis: TimeBasis = TimeBasis.FIXED,
        interval: Int = 3,
        unit: RecurrenceUnit = RecurrenceUnit.MONTH,
    ): Pair<AssetId, ScheduleId> {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val schedule = graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = mower.id,
                targetGroupId = null,
                title = title,
                timeInterval = interval,
                timeUnit = unit,
                timeBasis = basis,
                anchorOn = "2026-01-01",
            ),
        )
        return mower.id to schedule.id
    }

    private suspend fun aGroupRound(members: Int = 3): Triple<GroupId, ScheduleId, List<AssetId>> {
        val assets = (1..members).map {
            graph.createAsset.run(AssetCommand(name = "Sprinkler $it", category = "Irrigation")).id
        }
        val group = graph.saveGroup.run(
            null,
            GroupCommand(name = "North run", members = assets.map { GroupMemberInput(assetId = it) }),
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
        return Triple(group.id, schedule.id, assets)
    }

    /** Answers the flow's affordance as soon as it opens, with the date given. */
    private suspend fun answer(occurredOn: String, meterValue: String? = null) {
        graph.completionFlow.prompt.first { it != null }
        assertTrue(
            graph.completionFlow.submit(
                CompletionAnswer(occurredOn = occurredOn, meterValue = meterValue),
            ),
        )
    }

    /**
     * Matrix row **"the five operations collapsing"** — *complete*: it writes the event and clears
     * the postponement **only if it was set**, and touches no rule column.
     */
    @Test fun completeWritesTheEventAndClearsThePostponementOnlyIfSet() = runTest {
        val (_, id) = anAssetSchedule()
        val before = graph.schedules.get(id)!!
        val vm = viewModel(id)
        vm.state.first { it.loaded }

        // No postponement: the completion writes the event and leaves the row entirely alone —
        // including `updated_at`, which is the D-27 pin's floor (invariants 25, 68, 69).
        vm.complete()
        answer("2026-04-15")
        vm.state.first { !it.busy && it.history.isNotEmpty() }
        val after = graph.schedules.get(id)!!
        assertEquals(1, graph.events.all().size)
        assertEquals(before, after)

        // With one set, the same operation clears exactly that column and still leaves the stamp.
        graph.postponeSchedule.run(id, "2026-08-20")
        val postponed = graph.schedules.get(id)!!
        vm.refresh()
        vm.state.first { it.postponedDueOn == "2026-08-20" }
        vm.complete()
        answer("2026-07-02")
        vm.state.first { !it.busy && it.history.size == 2 }
        val cleared = graph.schedules.get(id)!!
        assertNull(cleared.postponedDueOn)
        assertEquals(postponed.copy(postponedDueOn = null), cleared)
    }

    /**
     * Matrix row **"the five operations collapsing"** — *snooze*: it changes **no date** and creates
     * **no event**, and the schedule still reads OVERDUE (invariant 20).
     *
     * **And it is a literal day from the tap, in a zone that is not UTC.** The instant is written
     * through B06's `ReminderSnooze` and read by `DigestPolicy` against a **wall clock**
     * (`snoozedUntilAt > nowMillis`), so a calendar-derived instant is a different quantity in
     * every zone. The hour chosen here is the sharpest case the owner's own zone offers: at 20:00
     * local on a April evening in UTC−4, midnight UTC of *tomorrow's local date* is **the tap
     * instant itself**, so the previous arithmetic wrote a snooze that had already expired when it
     * was written and suppressed nothing at all. The expected value is computed from the clock and
     * not from the date — the old assertion restated the expression under test and so could not
     * fail — and `DigestPolicy` itself is asked whether it suppresses.
     */
    @Test fun snoozeIsExactlyOneDayFromTheTapAndSuppressesTheDigest() = runTest {
        // A device in a negative UTC offset whose clock says 20:00 local on the day of the tap.
        val zone = ZoneId.of("America/New_York")
        val tapped = ZonedDateTime.of(LocalDate.parse("2026-04-15"), LocalTime.of(20, 0), zone)
        graph.now = tapped.toInstant().toEpochMilli()

        graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard")).let { mower ->
            graph.schedules.upsert(
                scheduleOf("s-old", assetId = mower.id.value, title = "Blade sharpen", createdOn = "2026-01-01"),
            )
            graph.recomputeSchedules.forSchedule(ScheduleId("s-old"))
        }
        val id = ScheduleId("s-old")
        val before = graph.schedules.get(id)!!
        val stateBefore = graph.scheduleStates.get(id)!!

        val vm = viewModel(id)
        assertEquals(DueStatus.OVERDUE, vm.state.first { it.loaded }.status)

        vm.snooze()
        vm.state.first { !it.busy }

        // Exactly twenty-four hours from the tap, in the device-local table, written by B06's own
        // use case and nowhere else.
        val delivery = graph.scheduleLocalDelivery.get(id)!!
        val expected = tapped.toInstant().plus(Duration.ofDays(1)).toEpochMilli()
        assertEquals(expected, delivery.snoozedUntilAt)
        assertTrue(
            "and it is in the future at the moment it is written, which UTC midnight was not",
            delivery.snoozedUntilAt!! > graph.now,
        )
        assertEquals("and it is never exported or merged", 1, graph.scheduleLocalDelivery.all().size)

        // The consumer's own answer: `DigestPolicy` posts nothing for a snoozed subject now and an
        // hour before the snooze runs out, and posts again once it has.
        assertEquals(emptyList<ItemPost>(), digestPosts(id, delivery, at = graph.now))
        assertEquals(
            emptyList<ItemPost>(),
            digestPosts(id, delivery, at = expected - 3_600_000L),
        )
        assertTrue(
            "once the day is up the subject is announced again",
            digestPosts(id, delivery, at = expected + 1).isNotEmpty(),
        )

        // No date column moved, no event appeared, and the status is unchanged: a snooze suppresses
        // delivery, it does not change what is true.
        assertEquals(before, graph.schedules.get(id)!!)
        assertEquals(
            stateBefore.copy(computedAt = graph.scheduleStates.get(id)!!.computedAt),
            graph.scheduleStates.get(id)!!,
        )
        assertEquals(0, graph.events.all().size)
        assertEquals(DueStatus.OVERDUE, vm.state.value.status)
    }

    /** What `DigestPolicy` would post for this one OVERDUE subject carrying [row], at [at]. */
    private fun digestPosts(id: ScheduleId, row: ScheduleLocalDelivery, at: Long): List<ItemPost> =
        DigestPolicy.decide(
            inputs = listOf(
                DeliveryInput(
                    subject = Fixture.subject(id.value, "2026-01-01"),
                    facts = Fixture.facts(DueStatus.OVERDUE),
                    delivery = row,
                ),
            ),
            standingTags = emptySet(),
            standingSummaryTag = null,
            nowMillis = at,
        ).posts

    /**
     * Matrix row **"the five operations collapsing"** — *postpone*: it changes only
     * `postponedDueOn`, writes no event, and the **next** occurrence still comes from the rule.
     *
     * The postpone-then-complete sequence is where a rule-from-postponed-date implementation shows
     * up: Apr 1 postponed to Aug 20 and completed on Apr 20 advances to **Jul 1**, the next series
     * date after the occurrence and the completion — never Aug 20 plus a quarter.
     */
    @Test fun postponeMovesThisOccurrenceOnlyAndTheNextComesFromTheRule() = runTest {
        val (_, id) = anAssetSchedule()
        val before = graph.schedules.get(id)!!
        val vm = viewModel(id)
        vm.state.first { it.loaded }
        assertTrue("a time rule, so postpone is offered", vm.state.value.canPostpone)

        vm.postpone("2026-08-20")
        val postponed = vm.state.first { it.postponedDueOn == "2026-08-20" }
        // Only that column, and no event.
        assertEquals(before.copy(postponedDueOn = "2026-08-20"), graph.schedules.get(id)!!)
        assertEquals(0, graph.events.all().size)
        assertEquals("2026-08-20", postponed.effectiveDueOn)
        // The occurrence key itself did not move (invariant 21).
        assertEquals("2026-04-01", postponed.currentOccurrenceOn)

        vm.complete()
        answer("2026-04-20")
        vm.state.first { !it.busy && it.history.isNotEmpty() }
        assertEquals("2026-07-01", graph.scheduleStates.get(id)!!.computedDueOn)
        assertNull(graph.schedules.get(id)!!.postponedDueOn)
    }

    /**
     * Carry-forward (a): a **meter-only** schedule cannot be postponed, so the action is **not
     * offered** — the engine refuses it with `PostponeNeedsTimeRule`, and offering an action that
     * can only be refused is the dead end the gates exist to avoid.
     */
    @Test fun aMeterOnlyScheduleIsNotOfferedPostpone() = runTest {
        val tractor = graph.createAsset.run(AssetCommand(name = "Tractor", category = "Yard"))
        val hours = meterDefinitionOf("d-hours", tractor.id.value)
        graph.definitions.upsert(hours)
        val schedule = graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = tractor.id,
                targetGroupId = null,
                title = "Oil change",
                meterDefinitionId = hours.id,
                meterInterval = 100.0,
                anchorMeter = 400.0,
            ),
        )

        val vm = viewModel(schedule.id)
        val state = vm.state.first { it.loaded }
        assertFalse("no occurrence date to move", state.canPostpone)
        assertNull(state.effectiveDueOn)
        assertFalse(state.hasTimeRule)
    }

    /**
     * Matrix rows **"Close this round" offered wrongly** and **"the close reading as 'mark all
     * done'"**, together.
     *
     * The action is offered on a group round that is open, obliges somebody and is unfinished;
     * confirming writes **one** closure row, **no** schedule column and **no** member event — every
     * member's history byte-identical (invariants 35, 36) — and the round then advances from
     * `closed_on`. Closing again is not offered, because the first row stands.
     */
    @Test fun closeWritesOneClosureRowAndNoMemberEvent() = runTest {
        val (_, id, members) = aGroupRound()
        // One member done: the round is open, obliges three and is unfinished.
        graph.completeGroupMembers.run(
            id,
            listOf(members.first()),
            CompletionCommand(occurredOn = "2026-04-10", tzId = "UTC"),
        )
        val scheduleBefore = graph.schedules.get(id)!!
        val historyBefore = graph.events.all().map { it.id.value to it.assetId.value }.toSet()

        val vm = viewModel(id)
        val open = vm.state.first { it.loaded }
        assertTrue("a group round, open, non-empty and unfinished", open.canClose)
        assertEquals("1 of 3 complete", open.progress)
        assertEquals("2026-04-01", open.currentOccurrenceOn)

        vm.closeRound("2026-04-14")
        val closed = vm.state.first { !it.busy && it.closures.isNotEmpty() }

        // Exactly one closure row, and nothing else at all.
        assertEquals(1, graph.closures.all().size)
        assertEquals(ClosureRow("2026-04-01", "2026-04-14"), closed.closures.single())
        assertEquals("no schedule column, no bumped stamp", scheduleBefore, graph.schedules.get(id)!!)
        assertEquals(
            "no member was recorded as serviced",
            historyBefore,
            graph.events.all().map { it.id.value to it.assetId.value }.toSet(),
        )
        // The round advanced from `closed_on` — a closure is a termination, so both bases move on
        // exactly as they do from a completion — which is why the screen is now looking at the
        // *next* round and the closed one is behind it.
        assertEquals("2026-07-01", graph.scheduleStates.get(id)!!.computedDueOn)
        assertEquals("2026-07-01", closed.currentOccurrenceOn)
        // And the closed round can never be closed again: the gate refuses a key that already
        // carries a row, and the unique index refuses it underneath (invariant 38).
        assertFalse(
            "a round that already carries a closure is not offered again",
            closed.copy(currentOccurrenceOn = "2026-04-01").canClose,
        )
    }

    /**
     * The other half of the gate: **absent** for an asset target, for a fully complete round, and
     * for an **empty** required set — the three states where a closure would be a 409 the owner
     * cannot act on, or a claim about a round that obliges nobody (spec §1.2, invariants 74, 77).
     */
    @Test fun closeIsAbsentForAnAssetTargetACompleteRoundAndAnEmptyRequiredSet() = runTest {
        // An asset target: completing it is the answer, so the action is not offered.
        val (_, assetSchedule) = anAssetSchedule()
        assertFalse(viewModel(assetSchedule).state.first { it.loaded }.canClose)

        // An empty required set: written straight to the repository, because `saveSchedule` refuses
        // a group target with nobody in it — this row only exists on a phone whose members all left.
        graph.groups.upsert(
            MaintenanceGroup(
                id = GroupId("g-empty"), name = "Emptied run", description = "",
                archivedAt = null, createdAt = dayMillis("2026-01-01"),
                updatedAt = dayMillis("2026-01-01"), members = emptyList(),
            ),
        )
        graph.schedules.upsert(scheduleOf("s-vacuous", groupId = "g-empty", title = "Nobody's round"))
        graph.recomputeSchedules.forSchedule(ScheduleId("s-vacuous"))
        val vacuous = viewModel(ScheduleId("s-vacuous")).state.first { it.loaded }
        assertTrue(vacuous.requiredSetEmpty)
        assertFalse("a round that obliges nobody can never be closed", vacuous.canClose)
        assertFalse("nor completed", vacuous.canComplete)
        assertNull("and it carries no status word at all", vacuous.statusWord)
        assertNull("and no progress line — 0 of 0 would read as done", vacuous.progress)

        // A **fully complete** round, asserted on the gate itself.
        //
        // It is asserted this way rather than by completing every member because a complete round
        // is a *termination*: the engine advances past it, so the round this screen is looking at is
        // never the complete one. The next assertion proves exactly that — which means the gate's
        // own "not complete" clause is the belt to that braces, and is worth asserting directly
        // because `CloseRound` refuses such a round too and a UI that offered it would produce a
        // 409 the owner cannot act on.
        val allDone = ScheduleDetailState(
            scheduleId = ScheduleId("s-done"),
            isGroup = true,
            currentOccurrenceOn = "2026-04-01",
            members = listOf(
                RoundMemberRow(AssetId("a1"), "Sprinkler 1", complete = true),
                RoundMemberRow(AssetId("a2"), "Sprinkler 2", complete = true),
            ),
        )
        assertFalse("a finished round is not closed, it is done", allDone.canClose)

        // And the engine really does advance past it: completing every member moves the round on.
        val (_, groupSchedule, _) = aGroupRound(members = 2)
        val vm = viewModel(groupSchedule)
        assertEquals("2026-04-01", vm.state.first { it.loaded }.currentOccurrenceOn)
        graph.completeGroupMembers.all(
            groupSchedule,
            CompletionCommand(occurredOn = "2026-04-10", tzId = "UTC"),
        )
        vm.refresh()
        val advanced = vm.state.first { it.currentOccurrenceOn != "2026-04-01" }
        assertEquals("2026-07-01", advanced.currentOccurrenceOn)
        assertEquals("0 of 2 complete", advanced.progress)
    }

    /**
     * Matrix row **"a closure amended or deleted"**: the closure history is **read-only**.
     *
     * A structural assertion over the state this screen renders — it carries an occurrence key and a
     * date and nothing that could change either — plus the port itself, which has no update and no
     * delete to reach for (invariants 37, 43).
     */
    @Test fun aClosureCarriesNoWayToAmendIt() = runTest {
        val (_, id, members) = aGroupRound(members = 2)
        graph.completeGroupMembers.run(
            id,
            listOf(members.first()),
            CompletionCommand(occurredOn = "2026-04-10", tzId = "UTC"),
        )
        val vm = viewModel(id)
        vm.state.first { it.loaded }
        vm.closeRound("2026-04-14")
        vm.state.first { it.closures.isNotEmpty() }

        // The row the screen holds is two strings: there is no id to edit and no handle to delete.
        val row = vm.state.value.closures.single()
        assertEquals("2026-04-01", row.occurrenceOn)
        assertEquals("2026-04-14", row.closedOn)
        // And the port offers nothing that could amend one.
        val members2 = ClosureRepository::class.java.methods.map { it.name }
        assertFalse("upsert", members2.any { it.contains("upsert", ignoreCase = true) })
        assertFalse("delete", members2.any { it.contains("delete", ignoreCase = true) })
        assertFalse("update", members2.any { it.contains("update", ignoreCase = true) })
    }

    /**
     * Matrix row **"the close date unbounded"**: the picker's range is the occurrence's **open date
     * through today**, the day before the open date and tomorrow are outside it, and the default is
     * **today** (invariant 78).
     *
     * Asserted over the pure predicate the dialog's `SelectableDates` is built from, so the bound is
     * a fact about the range and not about one rendering of it.
     */
    @Test fun theClosureDateRangeIsTheOpenDateThroughTodayAndNothingElse() {
        val openOn = LocalDate.parse("2026-04-01")
        val today = LocalDate.parse("2026-04-15")

        assertEquals(openOn..today, closureDateRange(openOn, today))
        assertTrue(isClosureDateSelectable(openOn, openOn, today))
        assertTrue(isClosureDateSelectable(today, openOn, today))
        assertTrue(isClosureDateSelectable(LocalDate.parse("2026-04-07"), openOn, today))
        assertFalse("the day before the open date", isClosureDateSelectable(openOn.minusDays(1), openOn, today))
        assertFalse("tomorrow", isClosureDateSelectable(today.plusDays(1), openOn, today))

        // The clamp: an open date derived at UTC can read as tomorrow on the evening it opened, and
        // an unclamped floor would then leave the range empty and refuse the default.
        val tomorrow = today.plusDays(1)
        assertEquals(today..today, closureDateRange(tomorrow, today))
        assertTrue(isClosureDateSelectable(today, tomorrow, today))
    }

    /**
     * Matrix row **"an edit clearing a postponement"**, the detail screen's half: a recurrence edit
     * abandons the open partial occurrence and **keeps** the recorded member completions as history
     * (invariants 14, 19; D-9).
     *
     * The edit itself is `SaveSchedule`, reached by navigating to the editor — this screen has no
     * path to a rule column, which is what keeps the five operations from collapsing into it.
     */
    @Test fun aRecurrenceEditAbandonsThePartialRoundAndKeepsItsHistory() = runTest {
        val (_, id, members) = aGroupRound(members = 3)
        graph.completeGroupMembers.run(
            id,
            listOf(members.first()),
            CompletionCommand(occurredOn = "2026-04-10", tzId = "UTC"),
        )
        graph.postponeSchedule.run(id, "2026-08-20")

        val vm = viewModel(id)
        vm.state.first { it.postponedDueOn == "2026-08-20" }

        // The edit, through the one use case that may touch a rule column.
        val target = (graph.schedules.get(id)!!.target as ScheduleTarget.GroupTarget)
        graph.now = dayMillis("2026-04-15")
        graph.saveSchedule.run(
            id,
            ScheduleCommand(
                targetAssetId = null,
                targetGroupId = target.groupId,
                title = "Head check",
                timeInterval = 6,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
            ),
        )
        vm.refresh()
        val edited = vm.state.first { it.postponedDueOn == null }

        assertNull("the postponement is gone", edited.postponedDueOn)
        assertEquals("the member's completion is still history", 1, graph.events.all().size)
        assertEquals(1, edited.history.size)
        // The pin's floor moved to the edit date — and only the edit moves it.
        assertEquals(dayMillis("2026-04-15"), graph.schedules.get(id)!!.updatedAt)
        // The new rule produced the new current occurrence; the abandoned round was not tidied.
        assertEquals("2026-07-01", graph.scheduleStates.get(id)!!.computedDueOn)
    }

    /**
     * Matrix row **"a pause or archive losing state"**: pause and resume round-trip, and archiving
     * takes the schedule out of the active lists while **keeping** its history and its closures.
     */
    @Test fun pauseAndResumeRoundTripAndArchivingKeepsTheHistory() = runTest {
        val (_, id, members) = aGroupRound(members = 2)
        graph.completeGroupMembers.run(
            id,
            listOf(members.first()),
            CompletionCommand(occurredOn = "2026-04-10", tzId = "UTC"),
        )
        val vm = viewModel(id)
        vm.state.first { it.loaded }
        vm.closeRound("2026-04-14")
        vm.state.first { it.closures.isNotEmpty() }

        vm.pause(true)
        assertEquals(DueStatus.PAUSED, vm.state.first { it.paused }.status)
        assertEquals("PAUSED", vm.state.value.statusWord)
        vm.pause(false)
        assertFalse(vm.state.first { !it.paused }.paused)
        assertEquals(ScheduleStatus.ACTIVE, graph.schedules.get(id)!!.status)

        vm.archive(true)
        val archived = vm.state.first { it.archived }
        assertEquals(ScheduleStatus.ARCHIVED, graph.schedules.get(id)!!.status)
        // Out of every due list, and nothing destroyed.
        assertTrue(graph.schedules.all().listedForDue().none { it.id == id })
        assertEquals(1, graph.closures.all().size)
        assertEquals(1, archived.history.size)
        // And no operation is offered on an archived schedule.
        assertFalse(archived.canComplete)
        assertFalse(archived.canClose)
        assertFalse(archived.canPostpone)
        // Nor a snooze — there is no delivery left to suppress — and the screen withholds the
        // recurrence edit too, which is the one action `SaveSchedule` would otherwise have accepted
        // from a screen that withholds every other one.
        assertFalse(archived.canSnooze)
    }
}
