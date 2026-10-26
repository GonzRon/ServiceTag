package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.listedForDue
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.HealthSubjectCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.reminders.DeliveryInput
import com.loosecannon.servicetag.reminders.DigestPolicy
import com.loosecannon.servicetag.reminders.Fixture
import com.loosecannon.servicetag.reminders.ItemPost
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.meterDefinitionOf
import com.loosecannon.servicetag.testing.scheduleOf
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
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

    private fun viewModel(
        scheduleId: ScheduleId,
        transfers: TransferRecordRepository? = null,
        reconcile: ReminderReconcile = ReminderReconcile {},
    ) = ScheduleDetailViewModel(
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
        transfers = transfers,
        reconcile = reconcile,
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

    /** As [aGroupRound], but with an explicit anchor and lead — for the 1.2.1 window tests. */
    private suspend fun aGroupRoundDueOn(
        anchorOn: String,
        leadDays: Int,
        members: Int = 2,
    ): Triple<GroupId, ScheduleId, List<AssetId>> {
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
                anchorOn = anchorOn,
                leadDays = leadDays,
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
        graph.today = LocalDate.parse("2026-07-02")
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
     * every zone. The hour chosen here is the sharpest case a device in UTC−4 offers: at 20:00
     * local on a April evening in UTC−4, midnight UTC of *tomorrow's local date* is **the tap
     * instant itself**, so the previous arithmetic wrote a snooze that had already expired when it
     * was written and suppressed nothing at all. The expected value is computed from the clock and
     * not from the date — the old assertion restated the expression under test and so could not
     * fail — and `DigestPolicy` itself is asked whether it suppresses.
     *
     * The zone is a fixed `Etc/GMT+4` (UTC−4, no DST): the sharpest-case arithmetic above needs
     * exactly that offset, not a real place's daylight rule, so a fixed offset keeps the "midnight
     * UTC" claim true by construction rather than by which April day happens to fall in EDT.
     */
    @Test fun snoozeIsExactlyOneDayFromTheTapAndSuppressesTheDigest() = runTest {
        // A device in a negative UTC offset whose clock says 20:00 local on the day of the tap.
        val zone = ZoneId.of("Etc/GMT+4")
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

        graph.today = LocalDate.parse("2026-04-20")
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
     * 1.2.1's fifth question: **the day before** the round's own due-soon window
     * (`effectiveDueOn - leadDays`), `canClose` is false even though every other condition holds.
     */
    @Test fun canCloseIsFalseTheDayBeforeTheWindowOpens() = runTest {
        // due = anchorOn = "2026-05-01"; leadDays = 10 puts the window's open date at 2026-04-21.
        val (_, id, _) = aGroupRoundDueOn(anchorOn = "2026-05-01", leadDays = 10)
        graph.today = LocalDate.parse("2026-04-20")

        val state = viewModel(id).state.first { it.loaded }
        assertEquals("2026-05-01", state.effectiveDueOn)
        assertFalse("the window has not opened yet", state.canClose)
    }

    /** The other edge of the same gate: **on** the boundary day, `canClose` is true. */
    @Test fun canCloseIsTrueOnTheBoundaryDayOfTheWindow() = runTest {
        val (_, id, _) = aGroupRoundDueOn(anchorOn = "2026-05-01", leadDays = 10)
        graph.today = LocalDate.parse("2026-04-21")

        val state = viewModel(id).state.first { it.loaded }
        assertEquals("2026-05-01", state.effectiveDueOn)
        assertTrue("the window opens today", state.canClose)
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

    /** A mower's oil change with a time rule, and the subject it drives. */
    private suspend fun aDrivingSchedule(): Triple<AssetId, ScheduleId, HealthSubjectId> {
        val (mower, schedule) = anAssetSchedule(title = "Oil change")
        val subject = graph.saveHealthSubject.create(
            mower,
            HealthSubjectCommand(
                name = "Engine oil",
                kind = HealthSubjectKind.PART,
                driver = HealthDriver.MAINTENANCE_OVERDUE,
                scheduleId = schedule,
                nominalUntilDays = 0,
                warningFromDays = 7,
                criticalFromDays = 30,
            ),
        )
        return Triple(mower, schedule, subject.id)
    }

    /**
     * Matrix row **"the guard, detail"**: archiving a schedule a health subject depends on asks S140
     * first and archives nothing; Cancel writes nothing; "Archive both" archives the schedule **and**
     * the subject, through the same archive with the unlink flag (spec §6.1, D-30; inv. 130).
     */
    @Test fun archivingADrivingScheduleAsksFirst() = runTest {
        val (_, id, subjectId) = aDrivingSchedule()
        val vm = viewModel(id)
        vm.state.first { it.loaded }

        vm.archive(true)
        val asking = vm.state.first { !it.busy && it.linkGuard != null }
        assertEquals(LinkGuardPrompt.Asks("Engine oil"), asking.linkGuard)
        assertFalse("nothing is archived before the answer", asking.archived)
        assertEquals(ScheduleStatus.ACTIVE, graph.schedules.get(id)!!.status)
        assertNull(graph.healthSubjects.get(subjectId)!!.archivedAt)

        // Cancel: the dialog closes and nothing is written.
        vm.cancelLinkGuard()
        assertNull(vm.state.value.linkGuard)
        assertEquals(ScheduleStatus.ACTIVE, graph.schedules.get(id)!!.status)
        assertNull(graph.healthSubjects.get(subjectId)!!.archivedAt)

        // Asked again, and answered "Archive both".
        vm.archive(true)
        vm.state.first { !it.busy && it.linkGuard != null }
        vm.archiveBoth()
        val done = vm.state.first { !it.busy }
        assertNull(done.linkGuard)
        assertEquals(ScheduleStatus.ARCHIVED, graph.schedules.get(id)!!.status)
        assertNotNull("the subject is archived with it", graph.healthSubjects.get(subjectId)!!.archivedAt)
        assertTrue(done.archived)
    }

    /**
     * Matrix row **"the guard, detail"**, the primary: "Archive both" on the subject its asset's health
     * follows is refused whole and shows S137 — neither the schedule nor the subject is archived.
     */
    @Test fun thePrimaryAnswerShowsS137() = runTest {
        val (assetId, id, subjectId) = aDrivingSchedule()
        graph.setHealthPolicy.run(assetId, HealthPolicyCommand(HealthAggregation.TRACK_ONE, subjectId))
        val vm = viewModel(id)
        vm.state.first { it.loaded }

        vm.archive(true)
        vm.state.first { !it.busy && it.linkGuard != null }
        vm.archiveBoth()
        val refused = vm.state.first { !it.busy }
        assertEquals(LinkGuardPrompt.Primary, refused.linkGuard)
        assertEquals(ScheduleStatus.ACTIVE, graph.schedules.get(id)!!.status)
        assertNull(graph.healthSubjects.get(subjectId)!!.archivedAt)

        vm.cancelLinkGuard()
        assertNull(vm.state.value.linkGuard)
        assertEquals(ScheduleStatus.ACTIVE, graph.schedules.get(id)!!.status)
    }

    // ---------------------------------------------------------------- #117 (F1): an arrival asks once

    /** Every `arrivalEnded` [vm] says, collected from now on. */
    private fun TestScope.endings(vm: ScheduleDetailViewModel): List<Unit> {
        val ended = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.arrivalEnded.collect { ended += it } }
        return ended
    }

    /**
     * #117 SD1 (§3.3, I8): the notification's **Done** on a FORM schedule hands over to the form **once**. A second
     * `arrive()` on the same model — the detail composed afresh back from the form, a rotation — opens no second
     * form. Handing over is no ending: the root clears that arrival itself, beside the push.
     */
    @Test fun aFormArrivalHandsOverOnceAndIsNoEnding() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val schedule = graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = mower.id,
                targetGroupId = null,
                title = "Blade sharpen",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
                completionMode = CompletionMode.FORM,
            ),
        )
        val vm = viewModel(schedule.id)
        vm.state.first { it.loaded }
        val forms = mutableListOf<CompletionOutcome.NeedsForm>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.needsForm.collect { forms += it } }
        val ended = endings(vm)

        vm.arrive()
        vm.state.first { !it.busy }
        vm.arrive()
        advanceUntilIdle()

        assertEquals("one form", listOf(CompletionOutcome.NeedsForm(schedule.id, mower.id, null)), forms)
        assertEquals("a hand-over is no ending", 0, ended.size)
        assertTrue("nothing written", graph.events.all().isEmpty())
    }

    /**
     * #117 SD1 (§3.3): a QUICK schedule carrying a meter rule arrives on "When was this done?" **once** per model —
     * not again while it is open, nor after the owner backed out. Each run's end is said exactly once: Cancelled,
     * and on a second arrival Completed, after which the root clears the arrival and the detail re-derives.
     */
    @Test fun aMeterArrivalAsksOnceAndEachEndingIsSaidOnce() = runTest {
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
        val prompts = mutableListOf<CompletionPrompt>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) {
            graph.completionFlow.prompt.filterNotNull().collect { prompts += it }
        }

        val backedOut = viewModel(schedule.id)
        backedOut.state.first { it.loaded }
        val endedBackedOut = endings(backedOut)
        backedOut.arrive()
        graph.completionFlow.prompt.first { it != null }
        backedOut.arrive()
        graph.completionFlow.cancel()
        backedOut.state.first { !it.busy }
        backedOut.arrive()
        advanceUntilIdle()
        assertEquals("one question", 1, prompts.size)
        assertNull(graph.completionFlow.prompt.value)
        assertEquals("Cancelled, said once", 1, endedBackedOut.size)

        val answered = viewModel(schedule.id)
        answered.state.first { it.loaded }
        val endedAnswered = endings(answered)
        answered.arrive()
        answer("2026-04-15", meterValue = "512")
        answered.state.first { !it.busy && it.history.isNotEmpty() }
        answered.arrive()
        advanceUntilIdle()
        assertEquals(2, prompts.size)
        assertEquals("Completed, said once", 1, endedAnswered.size)
        assertEquals(1, graph.events.all().size)
    }

    /**
     * #117 SD1 (§3.3): a refused run ends the arrival, said once. The held refusal (#77, P77-35) does not: it wrote
     * nothing and its sentence stays on screen, and the same model never says it twice.
     */
    @Test fun aRefusedArrivalEndsAndAHeldOneDoesNot() = runTest {
        val (_, round, _) = aGroupRound()
        val refused = viewModel(round)
        refused.state.first { it.loaded }
        val endedRefused = endings(refused)
        refused.arrive()
        refused.state.first { !it.busy }
        advanceUntilIdle()
        assertNull("no question for a group with no member named", graph.completionFlow.prompt.value)
        assertEquals("Refused, said once", 1, endedRefused.size)

        val (mower, id) = anAssetSchedule()
        graph.transferRecords.append(
            TransferRecord(
                id = "out-mower", assetId = mower, kind = TransferKind.OUT, packId = "0f1e2d3c-4b5a-4968-8776-655443322110",
                lineage = emptyList(), at = dayMillis("2026-04-01"), packSha256 = "ab".repeat(32), nameSnapshot = "Mower",
                note = "",
            ),
        )
        val held = viewModel(id, transfers = graph.transferRecords)
        held.state.first { it.loaded }
        val said = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { held.messages.collect { said += it } }
        val endedHeld = endings(held)
        held.arrive()
        held.state.first { !it.busy }
        held.arrive()
        advanceUntilIdle()
        assertNull("no question", graph.completionFlow.prompt.value)
        assertEquals(listOf("This asset was transferred out."), said)
        assertEquals("the held refusal is no ending", 0, endedHeld.size)
        assertTrue("nothing written", graph.events.all().isEmpty())
    }

    /**
     * #117 SD1 (§3.3): the held refusal's race shape — the model reads no hold, so the arrival asks, and the guarded
     * write refuses the answer (`Refused(AssetTransferredOut)`). That run is no ending either: P77-35 said once, and
     * nothing written.
     */
    @Test fun aHeldRefusalFromTheGuardedWriteEndsNoArrival() = runTest {
        val (mower, id) = anAssetSchedule()
        // Built without `transfers`, so the model's own check passes and the question opens.
        val raced = viewModel(id)
        raced.state.first { it.loaded }
        graph.transferRecords.append(
            TransferRecord(
                id = "out-mower", assetId = mower, kind = TransferKind.OUT, packId = "0f1e2d3c-4b5a-4968-8776-655443322110",
                lineage = emptyList(), at = dayMillis("2026-04-01"), packSha256 = "ab".repeat(32), nameSnapshot = "Mower",
                note = "",
            ),
        )
        val said = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { raced.messages.collect { said += it } }
        val ended = endings(raced)
        raced.arrive()
        answer("2026-04-15")
        raced.state.first { !it.busy }
        advanceUntilIdle()
        assertNull(graph.completionFlow.prompt.value)
        assertEquals(listOf("This asset was transferred out."), said)
        assertEquals("the held refusal is no ending", 0, ended.size)
        assertTrue("nothing written", graph.events.all().isEmpty())
    }

    // ---------------------------------------------------------------- #123: one sweep after every completion (§3.7)

    /** #123: a sweep that records, as it runs, the events the store holds and whether an offer is open. */
    private fun sweep(failure: Throwable? = null) = CountingSweep(failure) {
        SweepSighting(events = graph.events.all().size, offerOpen = graph.completionFlow.offer.value != null)
    }

    /**
     * "Oil change" on a Tractor: QUICK, quarterly from Jan 1 (so OVERDUE today) and every 100 engine hours from 400,
     * so its arrival asks "When was this done?" with a reading. [down] makes the Tractor DOWN, so the completion asks
     * "Mark operational?" after it.
     */
    private suspend fun aMeterSchedule(down: Boolean = false): ScheduleId {
        val tractor = graph.createAsset.run(AssetCommand(name = "Tractor", category = "Yard"))
        val hours = meterDefinitionOf("d-hours", tractor.id.value)
        graph.definitions.upsert(hours)
        if (down) {
            graph.conditions.insert(conditionRow("c-tractor", tractor.id.value, OperationalCondition.DOWN, "2026-04-01"))
        }
        return graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = tractor.id,
                targetGroupId = null,
                title = "Oil change",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
                meterDefinitionId = hours.id,
                meterInterval = 100.0,
                anchorMeter = 400.0,
            ),
        ).id
    }

    private fun transferredOut(assetId: AssetId) = TransferRecord(
        id = "out-${assetId.value}", assetId = assetId, kind = TransferKind.OUT, packId = "0f1e2d3c-4b5a-4968-8776-655443322110",
        lineage = emptyList(), at = dayMillis("2026-04-01"), packSha256 = "ab".repeat(32), nameSnapshot = "Mower",
        note = "",
    )

    /** SD1: **Complete** on a QUICK schedule, answered — one event, and one sweep that already sees it. */
    @Test fun completeSweepsOnceAfterTheWrite() = runTest {
        val (_, id) = anAssetSchedule()
        val sweep = sweep()
        val vm = viewModel(id, reconcile = sweep)
        vm.state.first { it.loaded }

        vm.complete()
        answer("2026-04-15")
        vm.state.first { !it.busy && it.history.isNotEmpty() }

        assertEquals(1, graph.events.all().size)
        assertEquals("once, with the event already stored", listOf(SweepSighting(1, false)), sweep.sightings)
    }

    /** SD2: the reminder's **Done** arrival on a meter schedule, the reading answered — one event, one sweep, one end. */
    @Test fun theDoneArrivalSweepsOnceAfterTheReading() = runTest {
        val id = aMeterSchedule()
        val sweep = sweep()
        val vm = viewModel(id, reconcile = sweep)
        vm.state.first { it.loaded }
        val ended = endings(vm)

        vm.arrive()
        answer("2026-04-15", meterValue = "512")
        vm.state.first { !it.busy && it.history.isNotEmpty() }
        advanceUntilIdle()

        assertEquals(1, graph.events.all().size)
        assertEquals(listOf(SweepSighting(1, false)), sweep.sightings)
        assertEquals("arrivalEnded once", 1, ended.size)
    }

    /**
     * SD3: **Complete all** — one write, one sweep. (A second **Complete all** is not the empty case: once the round is
     * complete the engine's current occurrence is the next round, so it writes that one. The empty `Completed` is
     * reached by naming a member already done, SD4.)
     */
    @Test fun completeAllSweepsOnceAfterTheWrite() = runTest {
        val (_, round, members) = aGroupRound()
        val sweep = sweep()
        val vm = viewModel(round, reconcile = sweep)
        vm.state.first { it.loaded }

        vm.completeAll()
        answer("2026-04-15")
        vm.state.first { !it.busy && it.history.size == members.size }
        advanceUntilIdle()

        assertEquals(members.size, graph.events.all().size)
        assertEquals(listOf(SweepSighting(members.size, false)), sweep.sightings)
    }

    /**
     * SD4: **Complete selected** with one member sweeps once; the same member again — already done, so nothing is
     * written, an empty `Completed` — sweeps once more (F-4); with none it asks nothing and sweeps nothing.
     */
    @Test fun completeSelectedSweepsOnceAnEmptyCompletionSweepsTooAndAnEmptySelectionNone() = runTest {
        val (_, round, members) = aGroupRound()
        val sweep = sweep()
        val vm = viewModel(round, reconcile = sweep)
        vm.state.first { it.loaded }

        vm.completeSelected(listOf(members.first()))
        answer("2026-04-15")
        vm.state.first { !it.busy && it.history.size == 1 }
        assertEquals(listOf(SweepSighting(1, false)), sweep.sightings)

        vm.completeSelected(listOf(members.first()))
        answer("2026-04-15")
        vm.state.first { !it.busy }
        advanceUntilIdle()
        assertEquals("nothing more written", 1, graph.events.all().size)
        assertEquals("the empty one sweeps too", listOf(SweepSighting(1, false), SweepSighting(1, false)), sweep.sightings)

        vm.completeSelected(emptyList())
        vm.state.first { !it.busy }
        advanceUntilIdle()
        assertNull("nothing asked", graph.completionFlow.prompt.value)
        assertEquals(2, sweep.runs)
    }

    /** SD5, a pin: a FORM schedule's **Complete** hands over to the form and sweeps nothing; the form sweeps its own write. */
    @Test fun aFormScheduleHandsOverWithNoSweep() = runTest {
        val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        val schedule = graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = mower.id,
                targetGroupId = null,
                title = "Blade sharpen",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
                completionMode = CompletionMode.FORM,
            ),
        )
        val sweep = sweep()
        val vm = viewModel(schedule.id, reconcile = sweep)
        vm.state.first { it.loaded }
        val forms = mutableListOf<CompletionOutcome.NeedsForm>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.needsForm.collect { forms += it } }

        vm.complete()
        vm.state.first { !it.busy }
        advanceUntilIdle()

        assertEquals(1, forms.size)
        assertEquals(0, sweep.runs)
    }

    /**
     * SD6, a pin: backed out at the prompt, refused (archived after the screen loaded), and the asset transferred
     * out — refused by the guarded write, and refused before the flow on a model that reads the hold (P77-35 each) —
     * none of them sweeps.
     */
    @Test fun aCancelledRefusedOrHeldCompletionRunsNoSweep() = runTest {
        val sweep = sweep()
        val said = mutableListOf<String>()

        val (_, cancelledId) = anAssetSchedule()
        val cancelled = viewModel(cancelledId, reconcile = sweep)
        cancelled.state.first { it.loaded }
        cancelled.complete()
        graph.completionFlow.prompt.first { it != null }
        graph.completionFlow.cancel()
        cancelled.state.first { !it.busy }

        val (_, archivedId) = anAssetSchedule(title = "Deck clean")
        val archived = viewModel(archivedId, reconcile = sweep)
        archived.state.first { it.loaded }
        graph.archiveSchedule.run(archivedId, true)
        archived.complete()
        answer("2026-04-15")
        archived.state.first { !it.busy }

        val (mower, heldId) = anAssetSchedule(title = "Belt check")
        val raced = viewModel(heldId, reconcile = sweep)
        raced.state.first { it.loaded }
        graph.transferRecords.append(transferredOut(mower))
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { raced.messages.collect { said += it } }
        raced.complete()
        answer("2026-04-15")
        raced.state.first { !it.busy }

        val held = viewModel(heldId, transfers = graph.transferRecords, reconcile = sweep)
        held.state.first { it.loaded }
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { held.messages.collect { said += it } }
        held.complete()
        held.state.first { !it.busy }
        advanceUntilIdle()

        assertEquals(List(2) { "This asset was transferred out." }, said)
        assertTrue("nothing written", graph.events.all().isEmpty())
        assertEquals(0, sweep.runs)
    }

    /**
     * SD7 (I13): a sweep that throws, on an arrival — attempted once; the event stands, the arrival ends once, and the
     * screen re-derives (no longer due) with nothing busy.
     */
    @Test fun aFailedSweepOnAnArrivalLeavesTheCompletionWrittenAndTheArrivalEnded() = runTest {
        val id = aMeterSchedule()
        val sweep = sweep(IllegalStateException("the provider refused"))
        val vm = viewModel(id, reconcile = sweep)
        assertTrue("due before", vm.state.first { it.loaded }.status!!.countsAsDue)
        val ended = endings(vm)

        vm.arrive()
        answer("2026-04-15", meterValue = "512")
        vm.state.first { !it.busy && it.history.isNotEmpty() }
        advanceUntilIdle()

        assertEquals("attempted once", 1, sweep.runs)
        assertEquals("the event stands", 1, graph.events.all().size)
        assertEquals("the arrival ended once", 1, ended.size)
        assertFalse(vm.state.value.busy)
        assertFalse("re-derived: no longer due", vm.state.value.status!!.countsAsDue)
    }

    /**
     * SD8 (F-3): an arrival whose sweep is cancelled under it — with an offer to make — asks no offer and says no end;
     * the event stands, and `operateGuarded` (which wraps its block in `runCatching`) reloads as it always has.
     */
    @Test fun aCancelledSweepOnAnArrivalAsksNoOfferAndEndsNothing() = runTest {
        val id = aMeterSchedule(down = true)
        val sweep = sweep(CancellationException("the screen left"))
        val vm = viewModel(id, reconcile = sweep)
        vm.state.first { it.loaded }
        val offered = mutableListOf<Any>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) {
            graph.completionFlow.offer.filterNotNull().collect { offered += it }
        }
        val ended = endings(vm)

        vm.arrive()
        answer("2026-04-15", meterValue = "512")
        advanceUntilIdle()

        assertEquals(1, sweep.runs)
        assertTrue("no offer asked", offered.isEmpty())
        assertEquals("arrivalEnded not sent", 0, ended.size)
        assertEquals("the event stands", 1, graph.events.all().size)
        assertFalse("reloaded", vm.state.value.busy)
    }

    /**
     * #114 (A2 re-review): a restore the Season Start rule refuses was folded silently by `operateGuarded`; it is said
     * now, and the schedule stays archived. An asset target's sentence stands alone (P114-17); another role over the asset
     * is P114-20, naming that role and not the one being restored.
     */
    @Test fun aRefusedRestoreSaysWhyAndTheScheduleStaysArchived() = runTest {
        val pump = graph.createAsset.run(AssetCommand(name = "Example Pump", category = "Heating"))
        val heater = graph.createAsset.run(
            AssetCommand(name = "Example Heater", category = "Heating", seasonStartMmdd = "10-01", seasonEndMmdd = "04-30"),
        )
        fun role(schedule: MaintenanceSchedule) = schedule.copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2026-01-01")
        graph.schedules.upsert(role(scheduleOf("s-pump", assetId = pump.id.value, title = "Pump service", status = ScheduleStatus.ARCHIVED)))
        graph.schedules.upsert(
            role(scheduleOf("s-heater-old", assetId = heater.id.value, title = "Annual service", status = ScheduleStatus.ARCHIVED)),
        )
        graph.schedules.upsert(role(scheduleOf("s-heater", assetId = heater.id.value, title = "Heater service")))

        for ((id, sentence) in listOf(
            "s-pump" to "Example Pump is in use all year, so it has no season to start. Give it a calendar or manual season first.",
            "s-heater-old" to "Example Heater already has a Season Start service: \"Heater service\". Stop using that one first.",
        )) {
            val vm = viewModel(ScheduleId(id))
            vm.state.first { it.loaded }
            val said = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.messages.collect { said += it } }

            vm.archive(false)
            vm.state.first { !it.busy }
            advanceUntilIdle()

            assertEquals(id, listOf(sentence), said)
            assertEquals(id, ScheduleStatus.ARCHIVED, graph.schedules.get(ScheduleId(id))!!.status)
        }
    }
    /** An in-season heater whose "Heater service" holds the role, and whose "Example filter check" counts from it. */
    private suspend fun aCountedRole(): ScheduleId {
        val heater = graph.createAsset.run(
            AssetCommand(name = "Example Heater", category = "Heating", seasonStartMmdd = "10-01", seasonEndMmdd = "04-30"),
        )
        graph.schedules.upsert(
            scheduleOf("s-role", assetId = heater.id.value, title = "Heater service")
                .copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2026-01-01"),
        )
        graph.schedules.upsert(
            scheduleOf(
                "s-check", assetId = heater.id.value, title = "Example filter check",
                servicePolicy = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, policyOffsetDays = 0,
            ),
        )
        return ScheduleId("s-role")
    }

    /**
     * #124 (N2b review M-1, M-2): archiving the role a live schedule counts from is refused and said (P124-8), the role
     * kept — straight, and through S141's "Archive both" when a health subject depends on the role.
     */
    @Test fun archivingTheRoleUnderACountingScheduleSaysP124_8() = runTest {
        val role = aCountedRole()
        val sentence = "\"Example filter check\" counts from Example Heater's Season Start service. Change when it starts counting first."
        val vm = viewModel(role)
        vm.state.first { it.loaded }
        val said = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.messages.collect { said += it } }

        vm.archive(true)
        vm.state.first { !it.busy }
        advanceUntilIdle()
        assertEquals(listOf(sentence), said)
        assertEquals(ScheduleStatus.ACTIVE, graph.schedules.get(role)!!.status)

        val heater = (graph.schedules.get(role)!!.target as ScheduleTarget.AssetTarget).assetId
        val subject = graph.saveHealthSubject.create(
            heater,
            HealthSubjectCommand(
                name = "Burner", kind = HealthSubjectKind.PART, driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = role,
                nominalUntilDays = 0, warningFromDays = 7, criticalFromDays = 30,
            ),
        )
        vm.archive(true)
        assertEquals(LinkGuardPrompt.Asks("Burner"), vm.state.first { !it.busy && it.linkGuard != null }.linkGuard)
        vm.archiveBoth()
        val done = vm.state.first { !it.busy }
        advanceUntilIdle()
        assertNull("the dialog closes", done.linkGuard)
        assertEquals("said again, through Archive both", listOf(sentence, sentence), said)
        assertEquals(ScheduleStatus.ACTIVE, graph.schedules.get(role)!!.status)
        assertNull("nor is the subject archived", graph.healthSubjects.get(subject.id)!!.archivedAt)
    }

    /** #124 (N2b review M-2): restoring a schedule counting from a service its asset does not have is refused (P124-5). */
    @Test fun restoringACountingScheduleWithNoServiceSaysP124_5() = runTest {
        val heater = graph.createAsset.run(
            AssetCommand(name = "Example Heater", category = "Heating", seasonStartMmdd = "10-01", seasonEndMmdd = "04-30"),
        )
        graph.schedules.upsert(
            scheduleOf(
                "s-check", assetId = heater.id.value, title = "Example filter check",
                servicePolicy = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, policyOffsetDays = 0, status = ScheduleStatus.ARCHIVED,
            ),
        )
        val vm = viewModel(ScheduleId("s-check"))
        vm.state.first { it.loaded }
        val said = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.messages.collect { said += it } }

        vm.archive(false)
        vm.state.first { !it.busy }
        advanceUntilIdle()

        assertEquals(
            listOf(
                "Example Heater has no Season Start service to count from. Use one of its schedules as its Season Start " +
                    "service first.",
            ),
            said,
        )
        assertEquals(ScheduleStatus.ARCHIVED, graph.schedules.get(ScheduleId("s-check"))!!.status)
    }
}
