package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.schedule.GroupOccurrences
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.testing.closureOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Membership, which is the part of #55 that has to be right for anything else to be: the windows are
 * append-only, and every test here asserts what an operation left **alone** as well as what it
 * wrote. The failure this file exists to prevent is not a missing member — it is a tidy-looking edit
 * that rewrites a window a past round was derived from.
 */
class GroupMembershipTest {

    private val assets = InMemoryAssetRepository()
    private val defs = InMemoryDefinitionRepository()
    private val profiles = InMemoryProfileRepository()
    private val events = InMemoryEventRepository()
    private val attachments = InMemoryAttachmentRepository()
    private val tags = InMemoryTagRepository()
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)
    private val uow = FakeUnitOfWork(
        assets, tags, defs, profiles, events, attachments, groups, closures, schedules, states,
    )

    private var seq = 0
    private val ids = IdGenerator { "id-${++seq}" }
    private var now = dayMillis("2026-01-01")
    private val clock = Clock { now }
    private var today = LocalDate.parse("2026-04-15")
    private val todayPort = Today { today }

    private val recompute =
        RecomputeSchedules(schedules, states, events, closures, groups, assets, todayPort, clock) { ZoneOffset.UTC }
    private val saveGroup = SaveGroup(groups, assets, uow, ids, clock)
    private val archiveGroup = ArchiveGroup(groups, uow, clock)
    private val saveSchedule =
        SaveSchedule(schedules, assets, groups, defs, profiles, uow, ids, clock, recompute)
    private val completeMembers = CompleteGroupMembers(
        schedules, groups, events, closures, defs, profiles, uow, ids, clock, recompute,
    )
    private val archiveAsset = ArchiveAsset(assets, uow, clock) { recompute.forAsset(it) }
    private val retireAsset = RetireAsset(assets, uow, clock) { recompute.forAsset(it) }

    private suspend fun seedAsset(id: String): AssetId {
        val asset = Asset(id = AssetId(id), name = "Feeder $id", createdAt = 1L, updatedAt = 1L)
        assets.upsert(asset)
        return asset.id
    }

    private suspend fun groupSchedule(groupId: GroupId) = saveSchedule.run(
        null,
        ScheduleCommand(
            targetAssetId = null,
            targetGroupId = groupId,
            title = "Top up feeders",
            timeInterval = 3,
            timeUnit = RecurrenceUnit.MONTH,
            anchorOn = "2026-01-01",
        ),
    )

    private fun command(vararg members: GroupMemberInput, name: String = "North run") =
        GroupCommand(name = name, members = members.toList())

    private fun add(assetId: AssetId, sortOrder: Int = 0) =
        GroupMemberInput(assetId = assetId, sortOrder = sortOrder)

    private fun keep(id: String, assetId: AssetId, sortOrder: Int = 0) =
        GroupMemberInput(assetId = assetId, id = id, sortOrder = sortOrder)

    /**
     * The three membership rules in one edit: a named window is **kept** with its dates untouched, an
     * omitted open window is **soft-removed**, and a member with no id is **inserted**.
     *
     * The `added_at` of the kept row is asserted against its original value rather than against a
     * literal, because the failure being guarded is re-stamping it — which would silently move the
     * window a past round keyed off.
     */
    @Test
    fun savingAGroupKeepsNamedWindowsSoftRemovesOmittedOnesAndInsertsAdds() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val a3 = seedAsset("a3")

        val created = saveGroup.run(null, command(add(a1, 0), add(a2, 1)))
        assertEquals(2, created.members.size)
        val original = created.members.associateBy { it.assetId }
        assertTrue(original.values.all { it.addedAt == now && it.removedAt == null })

        now = dayMillis("2026-03-01")
        val edited = saveGroup.run(
            created.id,
            command(keep(original.getValue(a1).id, a1, sortOrder = 5), add(a3, 9)),
        )

        val kept = edited.members.single { it.id == original.getValue(a1).id }
        assertEquals(original.getValue(a1).addedAt, kept.addedAt)
        assertNull(kept.removedAt)
        assertEquals(5, kept.sortOrder)

        val removed = edited.members.single { it.id == original.getValue(a2).id }
        assertEquals(original.getValue(a2).addedAt, removed.addedAt)
        assertEquals(dayMillis("2026-03-01"), removed.removedAt)

        val inserted = edited.members.single { it.assetId == a3 }
        assertEquals(dayMillis("2026-03-01"), inserted.addedAt)
        assertNull(inserted.removedAt)

        // Three rows: nothing was deleted, and the member ids the caller kept are the ids it sent.
        assertEquals(3, edited.members.size)
        assertEquals(edited, groups.get(created.id))
        // Two saves, two transactions: validation runs entirely outside them.
        assertEquals(2, uow.commits)
    }

    /**
     * Remove then re-add: a **second** window with a new id and a new `added_at`, no `removed_at`
     * cleared anywhere, and — the fact that matters — **no change to the past round's required set**.
     *
     * Clearing `removed_at` to "reopen" a window is the tempting implementation and the one that
     * breaks #55 AC 8: the round that was derived from `[addedAt, removedAt)` would silently acquire
     * a member it never obliged.
     */
    @Test
    fun removeThenReAddInsertsASecondWindowAndChangesNoPastRequiredSet() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val group = saveGroup.run(null, command(add(a1, 0), add(a2, 1)))
        val schedule = saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = null,
                targetGroupId = group.id,
                title = "Top up feeders",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
            ),
        )
        val round = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals(listOf(a1, a2), round.required)

        now = dayMillis("2026-03-01")
        val a1Window = group.members.single { it.assetId == a1 }
        val afterRemoval = saveGroup.run(group.id, command(keep(group.members.single { it.assetId == a2 }.id, a2)))
        assertEquals(dayMillis("2026-03-01"), afterRemoval.members.single { it.id == a1Window.id }.removedAt)

        now = dayMillis("2026-04-01")
        val afterReAdd = saveGroup.run(
            group.id,
            command(keep(group.members.single { it.assetId == a2 }.id, a2), add(a1, 3)),
        )

        val windows = afterReAdd.members.filter { it.assetId == a1 }
        assertEquals(2, windows.size, "re-adding inserts a window, it does not reopen one")
        val closed = windows.single { it.id == a1Window.id }
        val reopened = windows.single { it.id != a1Window.id }
        assertEquals(dayMillis("2026-03-01"), closed.removedAt, "the old window's removal stands")
        assertEquals(dayMillis("2026-04-01"), reopened.addedAt)
        assertNull(reopened.removedAt)
        assertEquals(1, windows.count { it.removedAt == null }, "one open window per asset")

        // The round that was open before any of this still obliges exactly who it obliged.
        assertEquals(
            listOf(a1, a2),
            GroupOccurrences.requiredOn(
                schedule, emptyList(), emptyList(), afterReAdd.members, round.occurrenceOn.toString(),
            ),
        )
    }

    /**
     * An **add** for an asset that already holds an open window is refused, and the store still holds
     * exactly one open window for it (invariant 80). A partial unique index is not expressible in
     * Room, so this rule lives in the use case — which is why it needs a test rather than a schema.
     */
    @Test
    fun addingAMemberThatAlreadyHasAnOpenWindowIsRefused() = runTest {
        val a1 = seedAsset("a1")
        val group = saveGroup.run(null, command(add(a1)))
        val open = group.members.single()

        val problems = assertFailsWith<GroupValidation> {
            saveGroup.run(group.id, command(keep(open.id, a1), add(a1, 1)))
        }.problems
        assertEquals(listOf(GroupProblem.MemberAlreadyOpen(a1)), problems)
        assertEquals(group, groups.get(group.id))
        assertEquals(1, groups.get(group.id)!!.members.count { it.removedAt == null })
    }

    /** Every bad command, each with the problem type the wire renders. All are 422-class refusals. */
    @Test
    fun everyBadGroupCommandIsRefusedWithItsOwnProblem() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val group = saveGroup.run(null, command(add(a1)))
        val other = saveGroup.run(null, command(add(a2), name = "South run"))

        suspend fun problemsOf(id: GroupId?, cmd: GroupCommand): List<GroupProblem> =
            assertFailsWith<GroupValidation> { saveGroup.run(id, cmd) }.problems

        assertTrue(
            GroupProblem.BlankName in problemsOf(null, command(add(a1), name = "   ")),
            "a blank name",
        )
        assertTrue(
            GroupProblem.MemberAssetMissing(AssetId("a-nowhere")) in
                problemsOf(null, command(add(AssetId("a-nowhere")), name = "Nowhere")),
            "a member naming an absent asset",
        )
        val foreign = other.members.single().id
        assertTrue(
            GroupProblem.ForeignMember(foreign) in problemsOf(group.id, command(keep(foreign, a2))),
            "a member id belonging to another group",
        )
        assertTrue(
            GroupProblem.MemberAlreadyOpen(a2) in
                problemsOf(null, command(add(a2, 0), add(a2, 1), name = "Twice")),
            "two adds for one asset in one command",
        )
        // Nothing was written by any of them.
        assertEquals(2, groups.all().size)
    }

    /**
     * Archiving a group hides it and **retains everything**: every window, every member completion
     * and every closure. A cascade here would destroy exactly the history #55 AC 8 requires, and the
     * schedules themselves are untouched — which views hide an archived group is the views' question.
     */
    @Test
    fun archivingAGroupRetainsEveryWindowCompletionAndClosure() = runTest {
        val a1 = seedAsset("a1")
        val group = saveGroup.run(null, command(add(a1)))
        val schedule = saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = null,
                targetGroupId = group.id,
                title = "Top up feeders",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
            ),
        )
        events.upsert(completionOf("e1", "2026-01-05", "2026-01-01", scheduleId = schedule.id.value))
        closures.insert(closureOf("oc1", "2026-04-01", "2026-04-10", scheduleId = schedule.id.value))

        now = dayMillis("2026-05-01")
        val archived = archiveGroup.run(group.id, archived = true)
        assertEquals(dayMillis("2026-05-01"), archived.archivedAt)
        assertEquals(group.members, archived.members)
        assertEquals(1, events.all().size)
        assertEquals(1, closures.all().size)
        assertEquals(schedule, schedules.get(schedule.id))

        val restored = archiveGroup.run(group.id, archived = false)
        assertNull(restored.archivedAt)
        assertEquals(group.members, restored.members)
    }

    /**
     * D-16, the side that still counts (with D-10): a member **retired after a round opened** is
     * still required for it. The obligation was real when the round opened, and the way out of it is
     * to finish the round or to close it — not to have the past quietly rewritten.
     *
     * The stored windows are byte-identical throughout: the lifecycle bound is applied to the list
     * the derivation reads, never written back, and [SaveGroup] remains the only writer of a row.
     */
    @Test
    fun aMemberRetiredAfterARoundOpenedIsStillRequiredForIt() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val group = saveGroup.run(null, command(add(a1, 0), add(a2, 1)))
        val schedule = groupSchedule(group.id)
        val before = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals(listOf(a1, a2), before.required)
        assertEquals("2026-01-01", before.openOn.toString())

        now = dayMillis("2026-03-01")
        retireAsset.retire(a2, "2026-02-20")

        assertEquals(group.members, groups.get(group.id)!!.members, "no window was written")
        val after = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals(before.required, after.required, "the round opened before the retirement")
        assertEquals(before.openInstant, after.openInstant)
    }

    /**
     * D-16, the side that does not: a member whose `retiredOn` is **on or before a new round's open
     * date** is excluded from it, and an **archived** member is excluded outright.
     *
     * **The known limit, stated against invariant 33's letter** (owner-flagged; the
     * `lifecycleChangedAt` column is deferred): neither bound is a stable instant. A retirement is
     * deliberately back-datable, so recording one after the fact moves the bound and can change a
     * round that has already opened; and `AssetStatus` carries no timestamp at all, so an archive
     * takes the member out of **every** round, this one included. Both are treated as what they are
     * — a correction of what the equipment is — rather than approximated with `updated_at`, which
     * any unrelated edit would move. The visible consequence is asserted here: when the bound empties
     * a round, the schedule reports `NO_DATA` rather than advancing on nobody's work.
     */
    @Test
    fun aMemberRetiredBeforeARoundOpenedIsExcludedFromItAndAnArchivedOneEntirely() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val group = saveGroup.run(null, command(add(a1, 0), add(a2, 1)))
        val schedule = groupSchedule(group.id)

        now = dayMillis("2026-03-01")
        retireAsset.retire(a2, "2026-02-20")
        // Finishing the first round is what opens the next one, on 2026-03-01 — after a2 retired.
        completeMembers.all(schedule.id, CompletionCommand(occurredOn = "2026-03-01", tzId = "UTC"))

        val next = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals("2026-04-01", next.occurrenceOn.toString())
        assertEquals("2026-03-01", next.openOn.toString())
        assertEquals(listOf(a1), next.required, "a2 retired on or before this round's open date")

        archiveAsset.run(a1)
        val emptied = assertNotNull(recompute.occurrenceOf(schedule))
        assertEquals(emptyList(), emptied.required)
        assertEquals(false, emptied.isActionable)

        // Nothing was written to any window, and the round that was already finished stays finished.
        assertEquals(group.members, groups.get(group.id)!!.members)
        assertEquals(2, events.all().size)
        assertNull(assertNotNull(states.get(schedule.id)).computedDueOn)
    }

    /**
     * A group is not equipment. It gets no `Asset` row, it enters no parent/child tree, and no tag
     * can point at one — the last of those structurally, because `TagTarget` has no group case and
     * so a binding to a group is unrepresentable rather than merely unwritten (invariants 4, 5).
     */
    @Test
    fun aGroupIsNeverEquipment() = runTest {
        val a1 = seedAsset("a1")
        val before = assets.all()
        val group = saveGroup.run(null, command(add(a1)))

        assertEquals(before, assets.all(), "saving a group creates no Asset row")
        assertEquals(emptyList(), tags.all())
        assertTrue(assets.all().all { it.parentAssetId == null })
        assertNull(assets.get(AssetId(group.id.value)), "the group's id is not an asset's")

        // Read off the source, because the fact is the *absence* of a case: `TagTarget` has three,
        // none of them a group, so no binding can name one however a caller asks.
        val cases = sourceFile("core/src/main/kotlin/com/loosecannon/servicetag/core/model/TagBinding.kt")
            .readText()
            .substringAfter("sealed interface TagTarget {")
            .substringBefore("\n}")
        assertEquals(
            listOf("AssetTarget", "LinkTarget", "None"),
            Regex("""data (?:class|object) (\w+)""").findAll(cases).map { it.groupValues[1] }.toList(),
        )
        // And exhaustively, at compile time: a fourth case would break this `when`, not just the
        // list above, so the two halves fail in different ways and neither can rot quietly.
        val target: TagTarget = TagTarget.None
        val label = when (target) {
            is TagTarget.AssetTarget -> "asset"
            is TagTarget.LinkTarget -> "link"
            TagTarget.None -> "none"
        }
        assertEquals("none", label)
    }

    /**
     * A name is descriptive, never identity (invariant 7): two groups may share one, and no port
     * anywhere offers a lookup by name for a cache to coalesce them with.
     */
    @Test
    fun twoGroupsMayShareANameAndNothingResolvesOneByName() = runTest {
        val a1 = seedAsset("a1")
        val a2 = seedAsset("a2")
        val first = saveGroup.run(null, command(add(a1), name = "North run"))
        val second = saveGroup.run(null, command(add(a2), name = "North run"))

        assertTrue(first.id != second.id)
        assertEquals(2, groups.all().size)
        assertEquals(first.name, second.name)

        val port = sourceFile("core/src/main/kotlin/com/loosecannon/servicetag/core/ports/Repositories.kt")
        val groupPort = port.readText().substringAfter("interface GroupRepository {").substringBefore("\n}")
        assertTrue(
            Regex("""fun \w*[Bb]y[Nn]ame""").findAll(groupPort).none(),
            "the group port must offer no lookup by name",
        )
    }

    /**
     * Invariant 79, read off the source tree because no runtime assertion can see the absence of a
     * write: nothing in `:core` **reopens** a window or re-stamps one.
     *
     * The assertion is on the *shape* of the site rather than on the words. `removedAt = null`
     * inside a constructor is a brand-new window, which is the one legitimate way to write that
     * field and the only one [SaveGroup] uses; `copy(removedAt = null)` on an existing row is a
     * reopen, and `copy(addedAt = ...)` is a re-stamp. Either of the last two would rewrite the
     * window a past round was derived from, and there is no site for either.
     *
     * The second half is the negative control: the one mutation that *is* allowed — closing an open
     * window — has to still be there, or this would pass on a file that had lost the feature.
     */
    @Test
    fun noSiteInCoreEverReopensOrReStampsAWindow() {
        val sources = kotlinFilesUnder("core/src/main").map { it.name to it.readText() }
        val reopens = sources.flatMap { (name, text) ->
            text.lines()
                .filter { line ->
                    Regex("""copy\([^)]*removedAt\s*=\s*null""").containsMatchIn(line) ||
                        Regex("""copy\([^)]*addedAt\s*=""").containsMatchIn(line)
                }
                .map { "$name: ${it.trim()}" }
        }
        assertEquals(emptyList(), reopens)

        val closes = sources.sumOf { (_, text) ->
            Regex("""copy\(removedAt = now\)""").findAll(text).count()
        }
        assertEquals(1, closes, "closing an open window is the one mutation there should be")
    }

    private companion object {
        fun repoRoot(): File {
            var dir = File(".").absoluteFile
            while (!File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile ?: error("cannot find the repository root")
            }
            return dir
        }

        fun sourceFile(relative: String): File = File(repoRoot(), relative)
            .also { check(it.exists()) { "no such source path: $relative" } }

        fun kotlinFilesUnder(relative: String): List<File> = sourceFile(relative)
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
