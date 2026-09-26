package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupCorrupt
import com.loosecannon.servicetag.core.backup.BackupNewerFormat
import com.loosecannon.servicetag.core.merge.MergeDecision
import com.loosecannon.servicetag.core.merge.MergeReason
import com.loosecannon.servicetag.core.merge.MergeTable
import com.loosecannon.servicetag.core.merge.MergeTally
import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.core.testing.InMemoryCategoryRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryConditionRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryHealthSubjectRepository
import com.loosecannon.servicetag.core.testing.InMemoryLinkRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryReferenceRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.testing.RiggedFailure
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * 1.1.0 (#46), semantics from #44 — the merge end to end: a real format-5 archive, the in-memory
 * repositories, one transaction, and the two refusals.
 *
 * The rules themselves are `MergePlannerTest`'s, where they are values. What is proved here is what
 * only the wiring can prove: that a plan over real bytes matches a plan over real rows, that an
 * apply writes the union in dependency order, that a refusal leaves the destination **byte for
 * byte** as it was (asserted by snapshotting every fake before and after), and that a plan built
 * against one destination cannot be applied to a different one.
 */
class ImportBackupMergeTest {

    /**
     * One install. [storage] is a parameter rather than a field a case reaches in and mutates,
     * because the three store answers the merge distinguishes — no folder, a folder without these
     * bytes, a folder that refuses to open them — are three *different installs* and not three
     * states of one.
     */
    private class Fakes(val storage: AttachmentStorage = FakeAttachmentStorage()) {
        val assets = InMemoryAssetRepository()
        val tags = InMemoryTagRepository()
        val links = InMemoryLinkRepository()
        val definitions = InMemoryDefinitionRepository()
        val profiles = InMemoryProfileRepository()
        val events = InMemoryEventRepository()
        val attachments = InMemoryAttachmentRepository()
        val groups = InMemoryGroupRepository()
        val closures = InMemoryClosureRepository()
        val schedules = InMemoryScheduleRepository(closures)
        val references = InMemoryReferenceRepository()
        val categories = InMemoryCategoryRepository()
        val uow = FakeUnitOfWork(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references, categories,
        )

        /** How many times the apply asked for a total recompute, and what it had written by then. */
        var rebuilds = 0
        var writesAtRebuild: List<Int> = emptyList()

        /** Set to make the recompute throw, which is how "inside the transaction" is observed. */
        var rebuildFails = false

        val build = BuildBackupMergePlan(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            categories, storage, uow,
        )
        val apply = ApplyBackupMergePlan(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            categories, storage, uow,
            rebuildAll = {
                rebuilds += 1
                writesAtRebuild = runBlocking {
                    listOf(
                        assets.all().size, groups.all().size, definitions.all().size,
                        profiles.all().size, schedules.all().size, closures.all().size,
                        links.all().size, tags.all().size, events.all().size,
                        attachments.all().size,
                    )
                }
                if (rebuildFails) throw RiggedFailure("rigged recompute failure")
            },
        )
        val merge = ImportBackupMerge(build, apply)

        /** Everything this install holds, as comparable values. */
        fun everything(): List<Any> = runBlocking {
            listOf(
                assets.all().sortedBy { it.id.value },
                groups.all().sortedBy { it.id.value },
                tags.all().sortedBy { it.id.value },
                links.all().sortedBy { it.id.value },
                definitions.all().sortedBy { it.id.value },
                profiles.all().sortedBy { it.id.value },
                schedules.all().sortedBy { it.id.value },
                closures.all().sortedBy { it.id },
                events.all().sortedBy { it.id.value },
                attachments.all().sortedBy { it.id.value },
            )
        }
    }

    /**
     * A store that is *Ready* and whose `open` **fails** — the shape a document provider gives when
     * the file went away between the tree walk and the open, or when the tree grant was revoked in
     * between (`ContentResolver.openInputStream` raises `FileNotFoundException`). Written here
     * rather than in `core/src/test/.../testing/`, which the brief declares untouched, and it is a
     * local fake because the in-memory store cannot be made to throw.
     */
    private class RefusingStorage(private val failing: String) : AttachmentStorage {
        private val store = object : AttachmentStore {
            override suspend fun put(locator: String, source: ByteSource): StoredBytes =
                throw StoreIoException("this fake is only ever read")

            override suspend fun open(locator: String): InputStream? =
                if (locator == failing) throw StoreIoException("open refused at $locator") else null

            override suspend fun exists(locator: String): Boolean = locator == failing
            override suspend fun delete(locator: String) = Unit
        }

        override fun state(): StoreState = StoreState.Ready("Attachments", "com.example.provider")
        override fun store(): AttachmentStore = store
    }

    private fun asset(id: String, name: String, parent: String? = null) = Asset(
        id = AssetId(id), name = name, createdAt = 1L, updatedAt = 2L,
        parentAssetId = parent?.let(::AssetId),
    )

    private fun tag(id: String, key: String, assetId: String) = TagBinding(
        id = TagId(id), payloadFormat = PayloadFormat.V1, payloadKey = key,
        target = TagTarget.AssetTarget(AssetId(assetId)), createdAt = 3L, updatedAt = 4L,
    )

    /** Four bytes, and an attachment row that tells the truth about them. */
    private val bytes = byteArrayOf(1, 2, 3, 4)

    private fun attachment(id: String, assetId: String) = Attachment(
        id = AttachmentId(id), owner = AttachmentOwner.OfAsset(AssetId(assetId)),
        kind = AttachmentKind.DOCUMENT, displayName = "Manual.pdf",
        mimeType = "application/pdf",
        sizeBytes = bytes.size.toLong(),
        // The row's hash must describe the bytes, or the merge is right to refuse it — see
        // `MergeReason.ATTACHMENT_BYTES_DIFFER`. The store's own helper computes it in one place.
        sha256 = InMemoryAttachmentStore.sha256Hex(bytes),
        storageLocator = "assets/$assetId/$id.pdf", capturedOn = null,
        createdAt = 9L, updatedAt = 10L,
    )

    /** A real data archive of [f]'s rows, at the current format, through the production export. */
    private fun exportOf(f: Fakes): ByteArray = runBlocking {
        ExportBackupSet(
            f.assets, f.groups, f.tags, f.links, f.definitions, f.profiles, f.schedules,
            f.closures, f.events, f.attachments, f.references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            f.categories, f.uow, IdGenerator { "set-merge" }, Clock { 1_758_400_000_000L },
            appVersion = "1.2.0", schemaVersion = 6,
        ).run().data
    }

    private fun group(id: String, assetId: String) = MaintenanceGroup(
        id = GroupId(id), name = "Aviary Feeders", description = "", archivedAt = null,
        createdAt = 10L, updatedAt = 20L,
        members = listOf(
            GroupMember(id = "gm-$id", assetId = AssetId(assetId), sortOrder = 0, addedAt = 1_000L, removedAt = null),
        ),
    )

    private fun schedule(id: String, groupId: String) = MaintenanceSchedule(
        id = ScheduleId(id), target = ScheduleTarget.GroupTarget(GroupId(groupId)),
        title = "Top up feeders", description = "", timeInterval = 1,
        timeUnit = RecurrenceUnit.WEEK, timeBasis = TimeBasis.FIXED, anchorOn = "2026-04-06",
        leadDays = 1, meterDefinitionId = null, meterInterval = null, anchorMeter = null,
        meterLead = null, servicePolicy = ServicePolicy.CONTINUOUS, policyOffsetDays = null,
        completionMode = CompletionMode.QUICK, profileId = null,
        remindersEnabled = true, status = ScheduleStatus.ACTIVE, postponedDueOn = null,
        createdAt = 30L, updatedAt = 40L, ruleChangedAt = 40L,
        providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
    )

    private fun closure(id: String, scheduleId: String) = OccurrenceClosure(
        id = id, scheduleId = ScheduleId(scheduleId), occurrenceOn = "2026-05-04",
        closedOn = "2026-05-06", createdAt = 70L,
    )

    /** A store that already holds the one attachment [wholeEstate] uses. */
    private fun storageWithBytes() = FakeAttachmentStorage(
        InMemoryAttachmentStore().also { it.files["assets/a1/att-1.pdf"] = bytes },
    )

    /**
     * An install carrying a row in every table the merge writes that 1.2 added, plus an attachment
     * whose bytes are really there — so a plan over it reaches the **last** write loop and "after
     * every write" is a claim with something behind it.
     */
    private fun wholeEstate(): Fakes = Fakes(storageWithBytes()).also { f ->
        runBlocking {
            f.assets.upsert(asset("a1", "Hot tub"))
            f.groups.upsert(group("g1", "a1"))
            f.schedules.upsert(schedule("s1", "g1"))
            f.closures.insert(closure("oc1", "s1"))
            f.tags.upsert(tag("t1", "key-1", "a1"))
            f.attachments.upsert(attachment("att-1", "a1"))
        }
    }

    @Test
    fun `a disjoint archive merges into a non-empty install and leaves the local rows alone`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Hot tub"))
            source.assets.upsert(asset("a2", "Filter", parent = "a1"))
            source.tags.upsert(tag("t1", "key-1", "a1"))
        }
        val target = Fakes()
        runBlocking {
            target.assets.upsert(asset("z9", "Generator"))
            target.tags.upsert(tag("t9", "key-9", "z9"))
        }

        val report = runBlocking { target.merge.run(exportOf(source)) }

        assertTrue(report.applicable)
        assertEquals(MergeTally(insert = 2, identical = 0, conflict = 0, skipped = 0), report.assets)
        assertEquals(MergeTally(1, 0, 0, 0), report.tags)
        assertEquals(emptyList(), report.conflicts)
        // One transaction, committed once: #44's "apply accepted plan in one Room transaction".
        assertEquals(1, target.uow.commits)
        assertEquals(0, target.uow.rollbacks)
        runBlocking {
            assertEquals(listOf("a1", "a2", "z9"), target.assets.all().map { it.id.value }.sorted())
            // #44 acceptance 2: the imported UUIDs are preserved exactly.
            assertEquals("Hot tub", target.assets.get(AssetId("a1"))!!.name)
            assertEquals(AssetId("a1"), target.assets.get(AssetId("a2"))!!.parentAssetId)
            // #44 acceptance 1: the pre-existing rows are untouched.
            assertEquals(asset("z9", "Generator"), target.assets.get(AssetId("z9")))
            assertEquals(tag("t9", "key-9", "z9"), target.tags.get(TagId("t9")))
        }
    }

    /** #44 acceptance 4. */
    @Test
    fun `importing the same archive twice is idempotent`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Hot tub"))
            source.tags.upsert(tag("t1", "key-1", "a1"))
        }
        val archive = exportOf(source)
        val target = Fakes()

        runBlocking { target.merge.run(archive) }
        val after = target.everything()
        val second = runBlocking { target.merge.run(archive) }

        assertTrue(second.applicable)
        assertEquals(MergeTally(0, 1, 0, 0), second.assets)
        assertEquals(MergeTally(0, 1, 0, 0), second.tags)
        assertEquals(after, target.everything())
    }

    /**
     * #44 acceptance 5 and 13: a conflict refuses, and mutates nothing at all.
     *
     * The archive carries a second, perfectly **insertable** asset on purpose. Without it the
     * assertion that nothing changed is satisfied by the fixture rather than by the code, because
     * there would be nothing an implementation that "writes what it can" could have written. `a2`
     * is that something, and its absence afterwards is what makes this case a proof.
     */
    @Test
    fun `a conflicting archive is refused and the destination is byte for byte unchanged`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Hot tub"))
            source.assets.upsert(asset("a2", "Filter"))
        }
        val target = Fakes()
        runBlocking {
            target.assets.upsert(asset("a1", "Spa"))
            target.assets.upsert(asset("z9", "Generator"))
        }
        val before = target.everything()

        val refused = assertFailsWith<MergeRefused> { runBlocking { target.merge.run(exportOf(source)) } }

        assertFalse(refused.report.applicable)
        assertEquals(1, refused.report.conflicts.size)
        assertEquals(MergeTable.ASSETS, refused.report.conflicts.single().table)
        assertEquals(MergeReason.CONTENT_DIFFERS, refused.report.conflicts.single().reason)
        // The insertable row was not written, and nothing else moved either.
        runBlocking { assertEquals(null, target.assets.get(AssetId("a2"))) }
        assertEquals(before, target.everything())
    }

    /** The plan endpoint's whole promise: it decides and writes nothing. */
    @Test
    fun `planning writes nothing, whether the plan is applicable or not`() {
        val source = Fakes()
        runBlocking { source.assets.upsert(asset("a1", "Hot tub")) }
        val archive = exportOf(source)

        val clean = Fakes()
        val cleanBefore = clean.everything()
        val cleanPlan = runBlocking { clean.merge.plan(archive) }
        assertTrue(cleanPlan.applicable)
        assertEquals(cleanBefore, clean.everything())

        val dirty = Fakes()
        runBlocking { dirty.assets.upsert(asset("a1", "Spa")) }
        val dirtyBefore = dirty.everything()
        val dirtyPlan = runBlocking { dirty.merge.plan(archive) }
        assertFalse(dirtyPlan.applicable)
        assertEquals(dirtyBefore, dirty.everything())
    }

    /**
     * TOCTOU, half one: the destination gains a **conflicting** row between the plan and the apply.
     * The apply re-plans inside its own transaction and refuses with `MergeRefused` — not
     * `MergePlanStale` — because a conflict is the more useful answer and is the one that carries a
     * conflict list. Decision 14: the `applicable` check comes before the fingerprint check
     * precisely so that this case cannot be swallowed by the staleness one.
     */
    @Test
    fun `a destination that gained a conflicting row between plan and apply is refused as a conflict`() {
        val source = Fakes()
        runBlocking { source.assets.upsert(asset("a1", "Hot tub")) }
        val target = Fakes()

        val plan = runBlocking { target.build.run(exportOf(source)) }
        assertTrue(plan.applicable)

        runBlocking { target.assets.upsert(asset("a1", "Spa")) }
        val before = target.everything()

        val refused = assertFailsWith<MergeRefused> { runBlocking { target.apply.run(plan) } }

        assertEquals(MergeReason.CONTENT_DIFFERS, refused.report.conflicts.single().reason)
        assertEquals("a1", refused.report.conflicts.single().id)
        assertEquals(before, target.everything())
    }

    /**
     * TOCTOU, half two, and the only case `MergePlanStale` is for: the destination changed in a way
     * that changes a *verdict* **without** creating a conflict — it gained the very row the plan was
     * going to insert. The fresh plan is applicable, so nothing but the fingerprint can tell that it
     * is not what was accepted.
     */
    @Test
    fun `a plan whose verdicts no longer hold is refused as stale and writes nothing`() {
        val source = Fakes()
        runBlocking { source.assets.upsert(asset("a1", "Hot tub")) }
        val target = Fakes()

        val plan = runBlocking { target.build.run(exportOf(source)) }
        assertEquals(MergeTally(1, 0, 0, 0), plan.report().assets)

        runBlocking { target.assets.upsert(asset("a1", "Hot tub")) }
        val before = target.everything()

        val stale = assertFailsWith<MergePlanStale> { runBlocking { target.apply.run(plan) } }

        assertTrue(stale.report.applicable)
        assertEquals(MergeTally(0, 1, 0, 0), stale.report.assets)
        assertEquals(before, target.everything())
    }

    /**
     * The three bytes answers, end to end: no folder → SKIPPED with its own reason; a folder and no
     * bytes → SKIPPED absent; a folder and the row's own bytes → INSERT. The fourth answer, bytes
     * that are *not* the row's, is `MergePlannerTest`'s, where the store does not have to be faked
     * into lying.
     */
    @Test
    fun `an attachment row is written only when the store holds the bytes the row claims`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Hot tub"))
            source.attachments.upsert(attachment("att1", "a1"))
        }
        val archive = exportOf(source)

        val noFolder = Fakes(FakeAttachmentStorage(state = StoreState.NotConfigured))
        val skippedNoFolder = runBlocking { noFolder.merge.run(archive) }
        assertTrue(skippedNoFolder.applicable)
        assertEquals(MergeTally(insert = 0, identical = 0, conflict = 0, skipped = 1), skippedNoFolder.attachments)
        assertEquals(MergeTally(1, 0, 0, 0), skippedNoFolder.assets)

        val noBytes = Fakes()
        val skippedNoBytes = runBlocking { noBytes.merge.run(archive) }
        assertEquals(MergeTally(0, 0, 0, 1), skippedNoBytes.attachments)

        val withBytes = Fakes(
            FakeAttachmentStorage(
                InMemoryAttachmentStore().also { it.files["assets/a1/att1.pdf"] = bytes },
            ),
        )
        val written = runBlocking { withBytes.merge.run(archive) }
        assertEquals(MergeTally(1, 0, 0, 0), written.attachments)
        runBlocking { assertEquals(1, withBytes.attachments.count()) }
    }

    /**
     * The fourth store answer, and the one that used to be fatal: a locator whose `open` **throws**.
     * A raced delete or a revoked grant is a missing-bytes answer for that one row — not a failed
     * plan, and not the 500 the API would have to report for an exception it cannot name. The other
     * nine tables merge, `applicable` stays true, and no attachment row is written.
     */
    @Test
    fun `an attachment whose bytes cannot be opened is SKIPPED and everything else still merges`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Hot tub"))
            source.attachments.upsert(attachment("att1", "a1"))
        }
        val archive = exportOf(source)
        val locator = "assets/a1/att1.pdf"

        val target = Fakes(RefusingStorage(locator))
        val plan = runBlocking { target.build.run(archive) }
        assertEquals(
            MergeDecision(
                MergeTable.ATTACHMENTS, "att1", MergeVerdict.SKIPPED,
                MergeReason.ATTACHMENT_BYTES_ABSENT, locator,
            ),
            plan.decisions.single { it.table == MergeTable.ATTACHMENTS },
        )

        val report = runBlocking { target.merge.run(archive) }

        assertTrue(report.applicable)
        assertEquals(MergeTally(0, 0, 0, 1), report.attachments)
        assertEquals(MergeTally(1, 0, 0, 0), report.assets)
        runBlocking {
            assertEquals(listOf("a1"), target.assets.all().map { it.id.value })
            assertEquals(0, target.attachments.count())
        }
    }

    /**
     * #44 acceptance 14, the one safety claim that rested entirely on prose: a failure **after** the
     * first row is written rolls the whole thing back. The tag is the fifth table the apply writes,
     * so both assets are already in when it fails — and the destination still ends up exactly as it
     * started. Falsifiable: move the ten `upsert` loops outside `uow.write` and the two assets
     * survive.
     */
    @Test
    fun `a failure partway through the apply rolls the whole transaction back`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Hot tub"))
            source.assets.upsert(asset("a2", "Filter", parent = "a1"))
            source.tags.upsert(tag("t1", "key-1", "a1"))
        }
        val archive = exportOf(source)

        val target = Fakes()
        runBlocking { target.assets.upsert(asset("z9", "Generator")) }
        val before = target.everything()
        target.tags.failOnUpsert = 1

        assertFailsWith<RiggedFailure> { runBlocking { target.merge.run(archive) } }

        assertEquals(before, target.everything())
        assertEquals(1, target.uow.rollbacks)
        assertEquals(0, target.uow.commits)
    }

    /**
     * The post-apply recompute is **total**, runs **inside** the transaction, and runs **once**.
     *
     * Rather than enumerate which schedules an imported event, membership row, closure or meter
     * reading could touch, the apply recomputes every schedule in the database after the last write
     * loop. Three facts, each falsifiable: a call per table would make the count more than one; a
     * call before a loop would make the counts it sees short; and a call outside `uow.write` would
     * survive a rolled-back apply, which the failing-recompute half proves it does not.
     */
    @Test
    fun `the total recompute runs once, inside the transaction, after every write`() {
        val archive = exportOf(wholeEstate())

        val target = Fakes(storageWithBytes())
        val report = runBlocking { target.merge.run(archive) }

        assertTrue(report.applicable, "unexpected conflicts: ${report.conflicts}")
        assertEquals(1, target.rebuilds)
        // What was in the database when it was asked, in MergeWrites' field order. Every row the
        // plan had to write was already written — including the attachment, which goes last.
        assertEquals(listOf(1, 1, 0, 0, 1, 1, 0, 1, 0, 1), target.writesAtRebuild)
        assertEquals(1, target.uow.commits)

        // Inside the transaction: a recompute that throws takes the whole apply with it.
        val rolledBack = Fakes(storageWithBytes())
        runBlocking { rolledBack.assets.upsert(asset("z9", "Generator")) }
        val before = rolledBack.everything()
        rolledBack.rebuildFails = true

        assertFailsWith<RiggedFailure> { runBlocking { rolledBack.merge.run(archive) } }

        assertEquals(1, rolledBack.rebuilds)
        assertEquals(before, rolledBack.everything())
        assertEquals(1, rolledBack.uow.rollbacks)
        assertEquals(0, rolledBack.uow.commits)

        // And a refused apply never reaches it at all: nothing derived is recomputed for a merge
        // that wrote nothing.
        val conflicted = Fakes(storageWithBytes())
        runBlocking { conflicted.assets.upsert(asset("a1", "Spa")) }
        assertFailsWith<MergeRefused> { runBlocking { conflicted.merge.run(archive) } }
        assertEquals(0, conflicted.rebuilds)
    }

    @Test
    fun `a corrupt archive is refused before a plan exists`() {
        val target = Fakes()
        runBlocking { target.assets.upsert(asset("z9", "Generator")) }
        val before = target.everything()

        assertFailsWith<BackupCorrupt> { runBlocking { target.merge.plan(byteArrayOf(1, 2, 3)) } }
        assertFailsWith<BackupCorrupt> { runBlocking { target.merge.run(byteArrayOf(1, 2, 3)) } }

        assertEquals(before, target.everything())
    }

    @Test
    fun `an archive from a newer format is refused before a plan exists`() {
        val source = Fakes()
        runBlocking { source.assets.upsert(asset("a1", "Hot tub")) }
        val newer = BackupCodec.decode(exportOf(source)).let { decoded ->
            BackupCodec.encode(
                decoded.data, appVersion = "9.9.9", schemaVersion = 5,
                createdAt = 1_758_400_000_000L, backupSetId = "set-merge",
                formatVersion = BackupCodec.FORMAT_VERSION + 1,
            )
        }
        val target = Fakes()
        val before = target.everything()

        assertFailsWith<BackupNewerFormat> { runBlocking { target.merge.run(newer) } }

        assertEquals(before, target.everything())
    }
}
