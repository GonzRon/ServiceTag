package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryLinkRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.dayMillis
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The operations, and what each of them does **not** do. Every test here asserts both halves,
 * because the failure mode this brief exists to prevent is not a wrong date: it is four operations
 * quietly collapsing into one generic "reschedule" that does a bit of all of them.
 */
class ScheduleOperationsTest {

    private val assets = InMemoryAssetRepository()
    private val defs = InMemoryDefinitionRepository()
    private val profiles = InMemoryProfileRepository()
    private val events = InMemoryEventRepository()
    private val attachments = InMemoryAttachmentRepository()
    private val tags = InMemoryTagRepository()
    private val links = InMemoryLinkRepository()
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)
    private val storage = FakeAttachmentStorage()
    private val uow = FakeUnitOfWork(
        assets, tags, links, defs, profiles, events, attachments, groups, closures, schedules, states,
    )

    private var seq = 0
    private val ids = IdGenerator { "id-${++seq}" }
    private var now = dayMillis("2026-02-10")
    private val clock = Clock { now }
    private var today = LocalDate.parse("2026-02-10")
    private val todayPort = Today { today }

    /** Counts writes to the schedule row, which is what the conditional-write rule is about. */
    private var scheduleWrites = 0
    private val countedSchedules = object : ScheduleRepository by schedules {
        override suspend fun upsert(schedule: MaintenanceSchedule) {
            scheduleWrites += 1
            schedules.upsert(schedule)
        }
    }

    private val recompute =
        RecomputeSchedules(schedules, states, events, closures, groups, assets, todayPort, clock)
    private val save =
        SaveSchedule(countedSchedules, assets, groups, defs, profiles, uow, ids, clock, recompute)
    private val complete =
        CompleteSchedule(countedSchedules, events, defs, profiles, uow, ids, clock, recompute)
    private val postpone = PostponeSchedule(countedSchedules, uow, recompute)
    private val pause = PauseSchedule(countedSchedules, uow, recompute)
    private val archive = ArchiveSchedule(countedSchedules, uow, recompute)
    private val logEvent = LogEvent(events, defs, profiles, assets, uow, ids, clock, recompute)
    private val updateEvent = UpdateEvent(events, defs, profiles, uow, ids, clock, recompute)
    private val deleteEvent = DeleteEvent(events, attachments, storage, uow, recompute)

    private suspend fun seedAsset(id: String = "a1"): AssetId {
        val asset = Asset(id = AssetId(id), name = "Feed bin $id", createdAt = 1L, updatedAt = 1L)
        assets.upsert(asset)
        return asset.id
    }

    private suspend fun seedGroup(id: String = "g1", members: List<String> = emptyList()): GroupId {
        val group = MaintenanceGroup(
            id = GroupId(id), name = "North run", description = "", archivedAt = null,
            createdAt = 1L, updatedAt = 1L,
            members = members.mapIndexed { index, assetId ->
                GroupMember(
                    id = "$id-m${index + 1}", assetId = AssetId(assetId), sortOrder = index,
                    addedAt = 1L, removedAt = null,
                )
            },
        )
        groups.upsert(group)
        return group.id
    }

    private suspend fun seedMeter(assetId: AssetId, id: String = "d1", isMeter: Boolean = true): DefinitionId {
        val definition = MeasurementDefinition(
            id = DefinitionId(id), assetId = assetId, key = id, label = "Hours", unit = "h",
            valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null,
            isMeter = isMeter, sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 1L,
        )
        defs.upsert(definition)
        return definition.id
    }

    private suspend fun seedProfile(assetId: AssetId, id: String = "p1"): ProfileId {
        val profile = EventProfile(
            id = ProfileId(id), assetId = assetId, name = "Inspection",
            eventKind = EventKind.INSPECTION, defaultTitle = "Inspected", templateKey = null,
            sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 1L,
            fields = emptyList(), consumables = emptyList(),
        )
        profiles.upsert(profile)
        return profile.id
    }

    private fun quarterlyCommand(assetId: AssetId) = ScheduleCommand(
        targetAssetId = assetId,
        targetGroupId = null,
        title = "Quarterly inspection",
        timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.FIXED,
        anchorOn = "2026-01-01",
        leadDays = 14,
        providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
    )

    private fun completion(occurredOn: String, values: Map<DefinitionId, String> = emptyMap()) =
        CompletionCommand(occurredOn = occurredOn, tzId = "UTC", values = values)

    /**
     * **Complete**: it inserts the completion event, stamps the occurrence key from the *computed*
     * due date, rebuilds — and touches no rule column and no schedule column at all. The four
     * "does not change" assertions are the point: an implementation that wrote the new due date
     * onto the row would pass the first two and fail the rest.
     */
    @Test
    fun completingInsertsAnEventAndRebuildsAndChangesNoRule() = runTest {
        val assetId = seedAsset()
        val schedule = save.run(null, quarterlyCommand(assetId))
        assertEquals("2026-04-01", states.get(schedule.id)!!.computedDueOn)

        today = LocalDate.parse("2026-03-20")
        val writesBefore = scheduleWrites
        val event = complete.run(schedule.id, completion("2026-03-20"))

        assertEquals(schedule.id, event.scheduleId)
        assertEquals("2026-04-01", event.occurrenceOn)
        assertEquals(EventSource.SCHEDULE_QUICK_COMPLETE, event.source)
        assertEquals(false, event.detailsPending)
        assertEquals("2026-07-01", states.get(schedule.id)!!.computedDueOn)
        assertEquals(TerminationKind.COMPLETED, states.get(schedule.id)!!.lastTerminationKind)

        // and nothing at all happened to the schedule row
        assertEquals(writesBefore, scheduleWrites)
        assertEquals(schedule, schedules.get(schedule.id))
    }

    /**
     * The conditional write (invariants 68, 69), the reason a phone can exchange completions with
     * another phone for years without a single conflict: a completion that clears nothing leaves
     * the row **byte-identical**, `updated_at` included, so a re-import of it is `IDENTICAL`. A
     * completion that does clear a postponement writes the row once, and only then bumps the stamp.
     */
    @Test
    fun completingWritesTheScheduleRowOnlyToClearAPostponement() = runTest {
        val assetId = seedAsset()
        val schedule = save.run(null, quarterlyCommand(assetId))
        val storedBefore = schedules.get(schedule.id)!!
        val writesBefore = scheduleWrites

        today = LocalDate.parse("2026-03-20")
        complete.run(schedule.id, completion("2026-03-20"))
        assertEquals(writesBefore, scheduleWrites)
        assertEquals(storedBefore, schedules.get(schedule.id))
        assertEquals(storedBefore.updatedAt, schedules.get(schedule.id)!!.updatedAt)

        // now postpone the next occurrence and complete that one
        today = LocalDate.parse("2026-06-20")
        postpone.run(schedule.id, "2026-07-10")
        val postponed = schedules.get(schedule.id)!!
        val writesAfterPostpone = scheduleWrites

        now = dayMillis("2026-07-10")
        today = LocalDate.parse("2026-07-10")
        complete.run(schedule.id, completion("2026-07-10"))
        assertEquals(writesAfterPostpone + 1, scheduleWrites)
        val cleared = schedules.get(schedule.id)!!
        assertNull(cleared.postponedDueOn)
        // one column, and **not** the stamp: `updated_at`'s date is the D-27 pin's floor and only
        // an explicit edit may move it (invariant 25). See
        // `postponingThenCompletingThenDeletingReturnsTheSamePin` for the sequence that would
        // otherwise observe the floor jumping without an edit.
        assertEquals(postponed.copy(postponedDueOn = null), cleared)
        assertEquals(postponed.updatedAt, cleared.updatedAt)
    }

    /**
     * The D-27 pin's fourth fact on the path that actually reaches it, which the pin's own pure-
     * function test cannot: **postpone → complete → delete the completion → the same pin.** After
     * the delete the schedule is never-terminated again, so the FIXED pin branch applies and its
     * floor must still be the creation date. If clearing the postponement had bumped `updated_at`,
     * the floor would now be the completion date and the pin would have jumped a quarter forward
     * with nobody editing anything (invariant 25).
     */
    @Test
    fun postponingThenCompletingThenDeletingReturnsTheSamePin() = runTest {
        val assetId = seedAsset()
        val schedule = save.run(null, quarterlyCommand(assetId))
        assertEquals("2026-04-01", states.get(schedule.id)!!.computedDueOn)

        today = LocalDate.parse("2026-03-01")
        now = dayMillis("2026-03-01")
        postpone.run(schedule.id, "2026-05-01")

        today = LocalDate.parse("2026-05-01")
        now = dayMillis("2026-05-01")
        val done = complete.run(schedule.id, completion("2026-05-01"))
        assertEquals("2026-07-01", states.get(schedule.id)!!.computedDueOn)

        today = LocalDate.parse("2026-09-01")
        now = dayMillis("2026-09-01")
        deleteEvent.run(done.id)

        assertEquals("2026-04-01", states.get(schedule.id)!!.computedDueOn)
        assertEquals(TerminationKind.NONE, states.get(schedule.id)!!.lastTerminationKind)
        assertEquals(schedule.updatedAt, schedules.get(schedule.id)!!.updatedAt)
    }

    /**
     * A postpone with **no date clears the override**, which is the other half of the route built on
     * this use case. Clearing restores `effectiveDueOn` to the computed date, writes no rule column
     * and leaves the stamp alone, exactly as setting one does.
     */
    @Test
    fun aPostponeWithNoDateClearsTheOverride() = runTest {
        val assetId = seedAsset()
        val schedule = save.run(null, quarterlyCommand(assetId))

        postpone.run(schedule.id, "2026-05-01")
        assertEquals("2026-05-01", states.get(schedule.id)!!.effectiveDueOn)

        val cleared = postpone.run(schedule.id, null)
        assertNull(cleared.postponedDueOn)
        assertEquals(schedule, cleared)
        val state = states.get(schedule.id)!!
        assertEquals("2026-04-01", state.computedDueOn)
        assertEquals(state.computedDueOn, state.effectiveDueOn)
    }

    /**
     * **A meter-only schedule cannot be postponed.** It has no occurrence date to move, so writing
     * `postponed_due_on` would hand the sort key a date the schedule does not have — and
     * `effectiveDueOn` is null only when there is no time rule (invariant 10). Clearing is still
     * allowed whatever the rule, so a row that arrived carrying an override it should never have had
     * can be cleaned up rather than stuck.
     */
    @Test
    fun aMeterOnlyScheduleCannotBePostponedButCanBeCleared() = runTest {
        val assetId = seedAsset()
        val meterId = seedMeter(assetId)
        val schedule = save.run(
            null,
            ScheduleCommand(
                targetAssetId = assetId,
                targetGroupId = null,
                title = "Oil change",
                meterDefinitionId = meterId,
                meterInterval = 50.0,
                anchorMeter = 120.0,
            ),
        )

        val problems = assertFailsWith<ScheduleValidation> {
            postpone.run(schedule.id, "2026-05-01")
        }.problems
        assertEquals(listOf(ScheduleProblem.PostponeNeedsTimeRule), problems)
        assertNull(schedules.get(schedule.id)!!.postponedDueOn)

        // a row that arrived with one anyway — from an archive, say — can still be cleaned up
        schedules.upsert(schedule.copy(postponedDueOn = "2026-05-01"))
        assertNull(postpone.run(schedule.id, null).postponedDueOn)
        assertNull(states.get(schedule.id)!!.effectiveDueOn)
    }

    /**
     * Two completions of a **meter-only** schedule both carry a null occurrence key, and that is
     * correct rather than a hole: a schedule with no time rule has no calendar occurrence, so
     * `UNIQUE(schedule_id, occurrence_on, asset_id)` has nothing to refuse and the two events do not
     * collide. Idempotence by index is a property of a *dated* occurrence; pinned here so the wire
     * does not document an idempotence it does not have.
     */
    @Test
    fun twoCompletionsOfAMeterOnlyScheduleCarryNoOccurrenceKey() = runTest {
        val assetId = seedAsset()
        val meterId = seedMeter(assetId)
        val schedule = save.run(
            null,
            ScheduleCommand(
                targetAssetId = assetId,
                targetGroupId = null,
                title = "Oil change",
                meterDefinitionId = meterId,
                meterInterval = 50.0,
                anchorMeter = 120.0,
            ),
        )

        val first = complete.run(schedule.id, completion("2026-08-20", mapOf(meterId to "172")))
        val second = complete.run(schedule.id, completion("2026-11-02", mapOf(meterId to "225")))

        assertNull(first.occurrenceOn)
        assertNull(second.occurrenceOn)
        assertEquals(2, events.all().size)
        // and the engine still advances: the newer completion's reading is the new baseline
        assertEquals(275.0, states.get(schedule.id)!!.computedDueMeter)
    }

    /**
     * Invariants 15 and 16 at the collaborator's level: running the whole sweep twice against
     * unchanged history leaves the state rows byte-identical. The pure function's own idempotence is
     * `ScheduleRecomputeTest`'s; this is the property the digest and the backstop rely on.
     */
    @Test
    fun recomputingEverythingTwiceLeavesTheSameStateRows() = runTest {
        val assetId = seedAsset()
        val schedule = save.run(null, quarterlyCommand(assetId))
        today = LocalDate.parse("2026-04-20")
        complete.run(schedule.id, completion("2026-04-20"))

        recompute.all()
        val first = states.all()
        recompute.all()
        assertEquals(first, states.all())
    }

    /**
     * **Postpone**: `postponed_due_on` and nothing else. The rule is untouched, `computed_due_on`
     * is kept for audit, `updated_at` does not move — it is the pin's floor — and the **next**
     * occurrence still comes from the rule, which is the assertion a "reschedule" implementation
     * fails: completing on July 10 gives October 1, the series date after the occurrence and the
     * completion, not three months after the postponed date.
     */
    @Test
    fun postponingMovesTheOccurrenceAndNeverTheRule() = runTest {
        val assetId = seedAsset()
        val schedule = save.run(null, quarterlyCommand(assetId))

        today = LocalDate.parse("2026-03-01")
        now = dayMillis("2026-03-01")
        val postponed = postpone.run(schedule.id, "2026-05-01")

        assertEquals("2026-05-01", postponed.postponedDueOn)
        assertEquals(schedule.timeInterval, postponed.timeInterval)
        assertEquals(schedule.timeUnit, postponed.timeUnit)
        assertEquals(schedule.anchorOn, postponed.anchorOn)
        assertEquals(schedule.updatedAt, postponed.updatedAt)

        val state = states.get(schedule.id)!!
        assertEquals("2026-04-01", state.computedDueOn)
        assertEquals("2026-05-01", state.effectiveDueOn)

        today = LocalDate.parse("2026-07-10")
        now = dayMillis("2026-07-10")
        val event = complete.run(schedule.id, completion("2026-07-10"))
        assertEquals("2026-04-01", event.occurrenceOn)
        assertEquals("2026-10-01", states.get(schedule.id)!!.computedDueOn)
        assertNull(schedules.get(schedule.id)!!.postponedDueOn)
    }

    /**
     * **Edit recurrence**: the rule columns, the postponement cleared — and **history untouched**.
     * The recorded completion and the closure stay exactly as they were: an edit abandons an open
     * occurrence by leaving its rows alone and letting the new rule produce the next one, and
     * "tidying up" old rows whose keys no longer lie on the series is the bug (invariant 14). A
     * title-only edit clears nothing.
     *
     * The edit-date **floor** is not exercised here — this fixture has a closure, so it is
     * terminated and the pin branch never runs; the floor is the pin test's fourth fact. The
     * partially-complete case belongs to a group occurrence, and that is the groups brief's.
     */
    @Test
    fun aRecurrenceEditClearsThePostponementAndRewritesNoHistory() = runTest {
        val assetId = seedAsset()
        val schedule = save.run(null, quarterlyCommand(assetId))
        closures.insert(
            com.loosecannon.servicetag.core.model.OccurrenceClosure(
                id = "oc1", scheduleId = schedule.id, occurrenceOn = "2026-04-01",
                closedOn = "2026-04-02", createdAt = dayMillis("2026-04-02"),
            ),
        )
        today = LocalDate.parse("2026-05-01")
        now = dayMillis("2026-05-01")
        postpone.run(schedule.id, "2026-08-01")

        // a title-only edit leaves the agreed date alone
        now = dayMillis("2026-06-01")
        val renamed = save.run(schedule.id, quarterlyCommand(assetId).copy(title = "Quarterly check"))
        assertEquals("2026-08-01", renamed.postponedDueOn)

        val eventsBefore = events.all()
        val closuresBefore = closures.all()

        now = dayMillis("2026-06-20")
        today = LocalDate.parse("2026-06-21")
        val edited = save.run(
            schedule.id,
            quarterlyCommand(assetId).copy(timeInterval = 1, title = "Quarterly check"),
        )

        assertNull(edited.postponedDueOn)
        assertEquals(1, edited.timeInterval)
        assertEquals("2026-01-01", edited.anchorOn)
        assertEquals(eventsBefore, events.all())
        assertEquals(closuresBefore, closures.all())
        // The closure's key (Apr 1) is still on the *old* quarterly series and is left exactly
        // where it is. The new due date below is what that costs — nothing: it is the first
        // *monthly* series date after the closure's own effective date, Apr 2.
        assertEquals("2026-05-01", states.get(schedule.id)!!.computedDueOn)
    }

    /**
     * Deleting the latest completion moves the due date **back**, observably, and the last
     * termination falls to the previous one (invariant 24). This is the direction that proves there
     * is no stored "last completed" pointer anywhere: if there were, it would still be pointing at
     * the row that has gone.
     */
    @Test
    fun deletingTheLatestCompletionMovesTheDueDateBack() = runTest {
        val assetId = seedAsset()
        val schedule = save.run(null, quarterlyCommand(assetId))

        today = LocalDate.parse("2026-04-20")
        now = dayMillis("2026-04-20")
        complete.run(schedule.id, completion("2026-04-20"))
        today = LocalDate.parse("2026-07-05")
        now = dayMillis("2026-07-05")
        val second = complete.run(schedule.id, completion("2026-07-05"))

        assertEquals("2026-10-01", states.get(schedule.id)!!.computedDueOn)
        assertEquals("2026-07-05", states.get(schedule.id)!!.lastTerminationEffectiveOn)

        deleteEvent.run(second.id)

        val after = states.get(schedule.id)!!
        assertEquals("2026-07-01", after.computedDueOn)
        assertEquals("2026-04-20", after.lastTerminationEffectiveOn)
        assertEquals("2026-04-20", after.lastCompletedOn)
    }

    /**
     * Every bad rule, each with the problem type the wire renders. All of them are **422-class**
     * refusals — the command describes a schedule that cannot exist — and without them the row
     * would store a schedule the engine cannot evaluate at all.
     */
    @Test
    fun everyBadRuleIsRefusedWithItsOwnProblem() = runTest {
        val assetId = seedAsset()
        val otherId = seedAsset("a2")
        val groupId = seedGroup()
        val meterId = seedMeter(assetId)
        val plainId = seedMeter(assetId, "d2", isMeter = false)
        val foreignMeter = seedMeter(otherId, "d3")
        seedProfile(assetId)
        val foreignProfile = seedProfile(otherId, "p2")
        val good = quarterlyCommand(assetId)

        suspend fun problemsOf(cmd: ScheduleCommand): List<ScheduleProblem> =
            assertFailsWith<ScheduleValidation> { save.run(null, cmd) }.problems

        assertTrue(
            ScheduleProblem.TargetInvalid in problemsOf(good.copy(targetGroupId = groupId)),
            "both targets",
        )
        assertTrue(
            ScheduleProblem.TargetInvalid in problemsOf(good.copy(targetAssetId = null)),
            "neither target",
        )
        assertTrue(
            ScheduleProblem.NoRuleSide in problemsOf(
                good.copy(timeInterval = null, timeUnit = null, anchorOn = null),
            ),
            "no rule side",
        )
        val groupCmd = good.copy(targetAssetId = null, targetGroupId = groupId)
        assertTrue(
            ScheduleProblem.MeterRuleOnGroupTarget in problemsOf(
                groupCmd.copy(meterDefinitionId = meterId, meterInterval = 50.0),
            ),
            "a meter rule on a group target",
        )
        assertTrue(
            ScheduleProblem.SeasonFollowsAssetOnGroupTarget in problemsOf(
                groupCmd.copy(seasonBehavior = SeasonBehavior.FOLLOW_ASSET),
            ),
            "FOLLOW_ASSET on a group target",
        )
        assertTrue(
            ScheduleProblem.ProfileOnGroupTarget in problemsOf(
                groupCmd.copy(profileId = ProfileId("p1"), completionMode = CompletionMode.FORM),
            ),
            "a profile on a group target",
        )
        // `seedGroup` left this group with no members, which is the refusal itself: a first round
        // that obliges nobody can never terminate, so the schedule is not storable (invariant 74).
        assertTrue(
            ScheduleProblem.EmptyGroupTarget in problemsOf(groupCmd),
            "a group-targeted schedule on an empty group",
        )
        assertTrue(
            ScheduleProblem.ForeignProfile(foreignProfile) in problemsOf(good.copy(profileId = foreignProfile)),
            "a foreign profile",
        )
        assertTrue(
            ScheduleProblem.ForeignMeterDefinition(foreignMeter) in problemsOf(
                good.copy(meterDefinitionId = foreignMeter, meterInterval = 50.0),
            ),
            "a foreign meter definition",
        )
        assertTrue(
            ScheduleProblem.MeterDefinitionNotAMeter(plainId) in problemsOf(
                good.copy(meterDefinitionId = plainId, meterInterval = 50.0),
            ),
            "a definition that is not a meter",
        )
        assertTrue(
            ScheduleProblem.TimeIntervalNotPositive in problemsOf(good.copy(timeInterval = 0)),
            "a zero interval",
        )
        assertTrue(
            ScheduleProblem.TimeUnitRequired in problemsOf(good.copy(timeUnit = null)),
            "an interval with no unit",
        )
        assertTrue(
            ScheduleProblem.MeterIntervalRequired in problemsOf(good.copy(meterDefinitionId = meterId)),
            "a meter rule with no interval",
        )
        assertTrue(
            ScheduleProblem.MeterIntervalNotPositive in problemsOf(
                good.copy(meterDefinitionId = meterId, meterInterval = 0.0),
            ),
            "a meter interval of zero",
        )
        assertTrue(
            ScheduleProblem.MeterIntervalNotPositive in problemsOf(
                good.copy(meterDefinitionId = meterId, meterInterval = -5.0),
            ),
            "a negative meter interval",
        )
        assertTrue(
            ScheduleProblem.NegativeLeadDays in problemsOf(good.copy(leadDays = -1)),
            "a negative lead",
        )
        assertTrue(
            ScheduleProblem.AnchorRequired in problemsOf(good.copy(anchorOn = null)),
            "a time rule with no anchor",
        )
        assertTrue(
            ScheduleProblem.BadAnchorDate in problemsOf(good.copy(anchorOn = "2026-13-40")),
            "an anchor that is not a date",
        )

        // and nothing was stored by any of them
        assertEquals(emptyList(), schedules.all())
        assertEquals(emptyList(), states.all())
    }

    /**
     * Pause and archive are the lifecycle column and nothing else: the rule, the overrides and the
     * stamp are all left alone, which is what keeps a paused schedule honestly overdue when it is
     * resumed instead of quietly re-pinned to the day somebody pressed the button. A completion of
     * an archived schedule is refused — a refusal about state, not about the rule.
     */
    @Test
    fun pauseAndArchiveAreTheLifecycleColumnOnly() = runTest {
        val assetId = seedAsset()
        val schedule = save.run(null, quarterlyCommand(assetId))

        today = LocalDate.parse("2026-09-01")
        now = dayMillis("2026-09-01")
        val paused = pause.run(schedule.id, paused = true)
        assertEquals(ScheduleStatus.PAUSED, paused.status)
        assertEquals(schedule.updatedAt, paused.updatedAt)
        assertEquals(schedule.copy(status = ScheduleStatus.PAUSED), paused)

        val resumed = pause.run(schedule.id, paused = false)
        assertEquals(ScheduleStatus.ACTIVE, resumed.status)
        // resumed and still overdue: the pin did not move while it was paused
        assertEquals("2026-04-01", states.get(schedule.id)!!.computedDueOn)

        val archived = archive.run(schedule.id, archived = true)
        assertEquals(ScheduleStatus.ARCHIVED, archived.status)
        assertEquals(schedule.updatedAt, archived.updatedAt)
        assertFailsWith<ScheduleArchived> { complete.run(schedule.id, completion("2026-09-01")) }
        assertEquals(emptyList(), events.all())
    }

    /**
     * A group-targeted completion is refused **here**, permanently, and not for want of a
     * derivation: this operation takes no member list, so obeying it would mean choosing an Asset to
     * write an event against, which is exactly what invariants 28 and 29 forbid. Completing a group
     * round names its members and goes through [CompleteGroupMembers]; the route that serves both
     * kinds of target dispatches on the target, so the two paths cannot be confused for one.
     */
    @Test
    fun aGroupTargetedCompletionIsRefusedBecauseItNamesNoMember() = runTest {
        val assetId = seedAsset()
        val groupId = seedGroup(members = listOf(assetId.value))
        val schedule = save.run(
            null,
            quarterlyCommand(assetId).copy(targetAssetId = null, targetGroupId = groupId),
        )
        assertFailsWith<GroupCompletionNotSupported> {
            complete.run(schedule.id, completion("2026-03-20", emptyMap()))
        }
        assertEquals(emptyList(), events.all())
    }

    /**
     * A minimal completion against a `FORM` schedule is allowed and owes its details: this is the
     * notification's one-tap action and a bodyless API call, and demanding the profile's required
     * fields here would make the quick path impossible. A form completion with values is an
     * ordinary manual entry and owes nothing.
     */
    @Test
    fun aMinimalCompletionOfAFormScheduleOwesItsDetails() = runTest {
        val assetId = seedAsset()
        val profileId = seedProfile(assetId)
        val meterId = seedMeter(assetId)
        val schedule = save.run(
            null,
            quarterlyCommand(assetId).copy(completionMode = CompletionMode.FORM, profileId = profileId),
        )

        today = LocalDate.parse("2026-03-20")
        val minimal = complete.run(schedule.id, completion("2026-03-20"))
        assertTrue(minimal.detailsPending)
        assertEquals(EventSource.MANUAL, minimal.source)
        assertEquals(profileId, minimal.profileId)
        assertEquals(EventKind.INSPECTION, minimal.kind)

        val full = complete.run(schedule.id, completion("2026-07-02", mapOf(meterId to "18.5")))
        assertEquals(false, full.detailsPending)
        assertEquals(18.5, full.measurements.single().valueNum)
    }

    /**
     * **Every** event write rebuilds, not only a completion: an ordinary "log hours" entry moves
     * the meter side, editing it moves it again, and deleting it takes the reading away. Without
     * the rebuild in all three the threshold and the current value drift apart and the dashboard
     * shows a number nothing supports.
     */
    @Test
    fun everyEventWriteRebuildsTheSchedulesItCanAffect() = runTest {
        val assetId = seedAsset()
        val meterId = seedMeter(assetId)
        val schedule = save.run(
            null,
            ScheduleCommand(
                targetAssetId = assetId,
                targetGroupId = null,
                title = "Oil change",
                meterDefinitionId = meterId,
                meterInterval = 50.0,
                anchorMeter = 120.0,
                meterLead = 5.0,
            ),
        )
        assertEquals(170.0, states.get(schedule.id)!!.computedDueMeter)
        assertNull(states.get(schedule.id)!!.currentMeter)

        val reading = logEvent.run(
            EventCommand(
                assetId = assetId, profileId = null, kind = EventKind.MEASUREMENT, title = "Log hours",
                occurredOn = "2026-08-01", occurredTime = null, tzId = "UTC", notes = "",
                values = mapOf(meterId to "165"), consumables = emptyList(),
            ),
        )
        assertEquals(165.0, states.get(schedule.id)!!.currentMeter)

        updateEvent.run(
            reading.id,
            EventCommand(
                assetId = assetId, profileId = null, kind = EventKind.MEASUREMENT, title = "Log hours",
                occurredOn = "2026-08-01", occurredTime = null, tzId = "UTC", notes = "",
                values = mapOf(meterId to "171"), consumables = emptyList(),
            ),
        )
        assertEquals(171.0, states.get(schedule.id)!!.currentMeter)

        deleteEvent.run(reading.id)
        assertNull(states.get(schedule.id)!!.currentMeter)
    }

    /**
     * Spec §3.2: derived state is rebuilt after **any** import, and a replace import is the one that
     * replaces the most — the wipe takes `schedule_state` with it through the CASCADE. Held to the
     * same standard the merge apply's twin seam is: **once, inside the transaction, after the last
     * insert**, and never at all for an import that was refused.
     */
    @Test
    fun aReplaceImportRebuildsDerivedStateOnceInsideItsTransaction() = runTest {
        val assetId = seedAsset()
        val schedule = save.run(null, quarterlyCommand(assetId))
        assertEquals("2026-04-01", states.get(schedule.id)!!.computedDueOn)

        val bytes = ExportBackupSet(
            assets, groups, tags, links, defs, profiles, schedules, closures, events, attachments,
            uow, IdGenerator { "set-1" }, clock, appVersion = "1.2.0", schemaVersion = 6,
        ).run().data

        fun restore(rebuildAll: suspend () -> Unit) = ImportBackupReplace(
            assets, groups, tags, links, defs, profiles, schedules, closures, events, attachments,
            storage, uow, rebuildAll = rebuildAll,
        )

        // Once, and after the last insert: everything the file carried was already in when it ran.
        var calls = 0
        var rowsAtRebuild: List<Int> = emptyList()
        restore {
            calls += 1
            rowsAtRebuild = listOf(assets.all().size, schedules.all().size, states.all().size)
        }.run(bytes)
        assertEquals(1, calls)
        assertEquals(listOf(1, 1, 0), rowsAtRebuild)

        // And the real seam leaves derived state behind, which the wipe had taken away.
        restore { recompute.all() }.run(bytes)
        val rebuilt = assertNotNull(states.get(schedule.id), "the restore must leave derived state behind")
        assertEquals("2026-04-01", rebuilt.computedDueOn)

        // Inside the transaction: a recompute that throws takes the whole restore with it, and the
        // row that was here before — and is not in the file — survives the wipe.
        assets.upsert(
            Asset(id = AssetId("z9"), name = "Generator", createdAt = 1L, updatedAt = 1L),
        )
        val before = assets.all().sortedBy { it.id.value }
        val commitsBefore = uow.commits
        assertFailsWith<RiggedFailure> { restore { throw RiggedFailure("rigged recompute") }.run(bytes) }
        assertEquals(before, assets.all().sortedBy { it.id.value })
        assertEquals(commitsBefore, uow.commits)
        assertNotNull(states.get(schedule.id))

        // A file that cannot be decoded never reaches it: the decode happens before the transaction.
        var reached = 0
        assertFails { restore { reached += 1 }.run(byteArrayOf(1, 2, 3)) }
        assertEquals(0, reached)
    }
}
