package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
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
import com.loosecannon.servicetag.core.testing.dayMillis
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The three schedule-command rules the editor brief was handed, each of which the engine brief
 * deliberately left open and named this one's owner.
 *
 * They live here rather than in the editor because a rule the UI merely hides is not a rule: the
 * editor's job is to make the illegal state unreachable through the UI, and the command's job is
 * to refuse it whoever constructed it — the API, an import, or a future surface.
 */
class ScheduleCommandRulesTest {

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

    private val recompute =
        RecomputeSchedules(schedules, states, events, closures, groups, assets, todayPort, clock) { ZoneOffset.UTC }
    private val save =
        SaveSchedule(schedules, assets, groups, defs, profiles, uow, ids, clock, recompute)
    private val completeMembers = CompleteGroupMembers(
        schedules, groups, events, closures, defs, profiles, uow, ids, clock, recompute,
    )

    private suspend fun seedAsset(id: String): AssetId {
        val asset = Asset(id = AssetId(id), name = "Feed bin $id", createdAt = 1L, updatedAt = 1L)
        assets.upsert(asset)
        return asset.id
    }

    /** A group whose one window opens at [addedAt] and closes at [removedAt]. */
    private suspend fun seedGroup(
        id: String,
        assetId: AssetId,
        addedAt: Long = 1L,
        removedAt: Long? = null,
    ): GroupId {
        val group = MaintenanceGroup(
            id = GroupId(id), name = "North run", description = "", archivedAt = null,
            createdAt = 1L, updatedAt = 1L,
            members = listOf(
                GroupMember(id = "$id-m1", assetId = assetId, sortOrder = 0, addedAt = addedAt, removedAt = removedAt),
            ),
        )
        groups.upsert(group)
        return group.id
    }

    private fun groupCommand(groupId: GroupId) = ScheduleCommand(
        targetAssetId = null,
        targetGroupId = groupId,
        title = "Quarterly inspection",
        timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH,
        anchorOn = "2026-01-01",
        providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
    )

    /**
     * Carry-forward (e) — D-12: a group-targeted schedule is **QUICK-only**.
     *
     * The command already refuses a `profileId` on a group target, which left `FORM` with nothing
     * to collect and yet storable. `CompleteGroupMembers` records the gap in a comment and names
     * this brief as its owner; this is the rule it was waiting for.
     */
    @Test
    fun aGroupTargetIsQuickOnly() = runTest {
        val head = seedAsset("a1")
        val groupId = seedGroup("g1", head)

        val problems = assertFailsWith<ScheduleValidation> {
            save.run(null, groupCommand(groupId).copy(completionMode = CompletionMode.FORM))
        }.problems
        assertTrue(
            ScheduleProblem.FormCompletionOnGroupTarget in problems,
            "a FORM group schedule must be refused, not stored: $problems",
        )

        // And the same command as QUICK is stored, so the rule refuses the shape and not the target.
        val saved = save.run(null, groupCommand(groupId))
        assertEquals(CompletionMode.QUICK, saved.completionMode)
    }

    /**
     * Carry-forward (f) — `EmptyGroupTarget` gates a **create**, never an edit of a schedule whose
     * round is already live.
     *
     * The member is removed after the round opened, so it is still required for that round (D-10)
     * and the round is neither vacuous nor stuck. Counting the windows open **now** would refuse
     * the edit and leave the owner unable to change the rule of a schedule that is working.
     */
    @Test
    fun anEmptyGroupRefusesACreateButNotAnEditWhoseRoundIsLive() = runTest {
        val head = seedAsset("a1")
        val groupId = seedGroup("g1", head)
        val schedule = save.run(null, groupCommand(groupId))

        // The member leaves *after* the round opened: the open round still obliges it.
        groups.upsert(
            groups.get(groupId)!!.let { group ->
                group.copy(members = group.members.map { it.copy(removedAt = dayMillis("2026-02-20")) })
            },
        )
        now = dayMillis("2026-03-01")
        val round = recompute.occurrenceOf(schedules.get(schedule.id)!!)!!
        assertTrue(round.isActionable, "the open round still obliges the removed member")

        // The edit goes through, and its new rule is what got stored.
        val edited = save.run(schedule.id, groupCommand(groupId).copy(timeInterval = 6))
        assertEquals(6, edited.timeInterval)

        // A *create* against the same group is still refused: a new schedule's first round would
        // open now and oblige nobody.
        val problems = assertFailsWith<ScheduleValidation> {
            save.run(null, groupCommand(groupId).copy(title = "Second round"))
        }.problems
        assertTrue(ScheduleProblem.EmptyGroupTarget in problems, "a create must still be refused: $problems")
    }

    /**
     * Carry-forward (g) — the provider value is constrained to a `ProviderId` name.
     *
     * `schedule_provider.provider` is a TEXT column, and the subject builder matches it against
     * `ProviderId` by name: a row naming anything else is a schedule with reminders switched on and
     * no provider that will ever read it, which is a finding the owner has to chase rather than a
     * refusal at the point it was typed.
     */
    @Test
    fun aProviderValueMustNameAKnownProvider() = runTest {
        val bin = seedAsset("a1")
        val good = ScheduleCommand(
            targetAssetId = bin,
            targetGroupId = null,
            title = "Quarterly inspection",
            timeInterval = 3,
            timeUnit = RecurrenceUnit.MONTH,
            anchorOn = "2026-01-01",
            providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
        )
        val problems = assertFailsWith<ScheduleValidation> {
            save.run(null, good.copy(providers = listOf(ScheduleProviderRow("TODOIST", enabled = true))))
        }.problems
        assertTrue(
            ScheduleProblem.UnknownProvider("TODOIST") in problems,
            "an unknown provider must be refused: $problems",
        )

        // LOCAL is the one 1.2 has, and it saves.
        val saved = save.run(null, good)
        assertEquals(listOf("LOCAL"), saved.providers.map { it.provider })
        assertFalse(saved.providers.isEmpty())
    }

    /**
     * N12 — a **negative meter lead** is refused, not clamped and not dropped.
     *
     * The lead subtracts from the due reading, so below zero it moves the "due soon" warning past
     * the threshold the schedule is already due at, and that state can never be reached. It was
     * previously erased by the editor, which saved the schedule with no lead at all while the field
     * still showed what the owner typed; refusing at the command is what lets every caller — the
     * editor and B12's API alike — be told which field is wrong.
     */
    @Test
    fun aNegativeMeterLeadIsRefused() = runTest {
        val tractor = seedAsset("a1")
        val hours = MeasurementDefinition(
            id = DefinitionId("d1"), assetId = tractor, key = "hours", label = "Hours", unit = "h",
            valueType = ValueType.NUMBER, decimals = 0, rangeLow = null, rangeHigh = null,
            isMeter = true, sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 1L,
        )
        defs.upsert(hours)
        val meterRule = ScheduleCommand(
            targetAssetId = tractor,
            targetGroupId = null,
            title = "Oil change",
            meterDefinitionId = hours.id,
            meterInterval = 100.0,
            anchorMeter = 400.0,
            providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
        )

        val problems = assertFailsWith<ScheduleValidation> {
            save.run(null, meterRule.copy(meterLead = -5.0))
        }.problems
        assertTrue(ScheduleProblem.NegativeMeterLead in problems, "a negative lead must be refused: $problems")

        // Not a number is refused the same way the interval's is.
        assertTrue(
            ScheduleProblem.NegativeMeterLead in assertFailsWith<ScheduleValidation> {
                save.run(null, meterRule.copy(meterLead = Double.NaN))
            }.problems,
        )

        // Zero and above are leads, and they save as sent — nothing is clamped or erased.
        assertEquals(0.0, save.run(null, meterRule.copy(meterLead = 0.0)).meterLead)
        assertEquals(10.0, save.run(null, meterRule.copy(title = "Filter", meterLead = 10.0)).meterLead)
    }

    /**
     * A group round completed through the member path stays QUICK-sourced now that the rule above
     * exists: the `MANUAL` branch `CompleteGroupMembers` guards is unreachable for a stored group
     * schedule, which is what the comment there asked for.
     */
    @Test
    fun aGroupMemberCompletionIsAlwaysAQuickComplete() = runTest {
        val head = seedAsset("a1")
        val groupId = seedGroup("g1", head)
        val schedule = save.run(null, groupCommand(groupId))

        val written = completeMembers.all(
            schedule.id,
            CompletionCommand(occurredOn = "2026-04-01", tzId = "UTC"),
        )
        assertEquals(1, written.size)
        assertEquals(
            com.loosecannon.servicetag.core.model.EventSource.SCHEDULE_QUICK_COMPLETE,
            written.single().source,
        )
    }
}
