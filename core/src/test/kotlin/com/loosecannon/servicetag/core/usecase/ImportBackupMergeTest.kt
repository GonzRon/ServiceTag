package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.testing.InMemorySupplyItemRepository
import com.loosecannon.servicetag.core.testing.InMemoryInstalledComponentRepository
import com.loosecannon.servicetag.core.testing.InMemoryAssetSupplyRepository
import com.loosecannon.servicetag.core.testing.successionOf
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.testing.InMemoryTransferRecordRepository
import com.loosecannon.servicetag.core.testing.InMemoryAssetSuccessionRepository
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupCorrupt
import com.loosecannon.servicetag.core.backup.BackupNewerFormat
import com.loosecannon.servicetag.core.merge.MergeDecision
import com.loosecannon.servicetag.core.merge.MergeReason
import com.loosecannon.servicetag.core.merge.MergeTable
import com.loosecannon.servicetag.core.merge.MergeTally
import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventKind
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
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.ports.StoredBytes
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.HealthFixtures
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
import com.loosecannon.servicetag.core.testing.InMemoryAssetLoanRepository
import com.loosecannon.servicetag.core.testing.InMemoryServiceCaseEntryRepository
import com.loosecannon.servicetag.core.testing.InMemoryServiceCaseRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.caseEntryOf
import com.loosecannon.servicetag.core.testing.caseOf
import com.loosecannon.servicetag.core.testing.loanOf
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
        val caseEntries = InMemoryServiceCaseEntryRepository()
        val serviceCases = InMemoryServiceCaseRepository(caseEntries)
        val loans = InMemoryAssetLoanRepository()
        val transfers = InMemoryTransferRecordRepository()
        val successions = InMemoryAssetSuccessionRepository()
        val uow = FakeUnitOfWork(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references, categories, serviceCases, caseEntries, loans, transfers, successions,
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
            categories, serviceCases, caseEntries, loans, transfers, successions, InMemorySupplyItemRepository(), InMemoryAssetSupplyRepository(), storage, uow,
        )
        val apply = ApplyBackupMergePlan(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            categories, serviceCases, caseEntries, loans, transfers, successions, InMemorySupplyItemRepository(), InMemoryAssetSupplyRepository(), storage, uow,
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
            f.categories, f.serviceCases, f.caseEntries, f.loans, f.transfers,
            f.successions, InMemorySupplyItemRepository(), InMemoryAssetSupplyRepository(), InMemoryInstalledComponentRepository(InMemorySupplyItemRepository()),
            f.uow, IdGenerator { "set-merge" }, Clock { 1_758_400_000_000L },
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
     * #67 AC 6: a role travels by merge onto a **new** row, end to end — the production export, the
     * real codec, the plan and the apply — and the same format-10 archive then re-plans IDENTICAL.
     */
    @Test
    fun `a merged role lands on the new row`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Hot tub"))
            source.attachments.upsert(attachment("att1", "a1").copy(role = DocumentRole.SERVICE_MANUAL))
        }
        val archive = exportOf(source)
        val target = Fakes(
            FakeAttachmentStorage(InMemoryAttachmentStore().also { it.files["assets/a1/att1.pdf"] = bytes }),
        )

        val report = runBlocking { target.merge.run(archive) }

        assertEquals(MergeTally(1, 0, 0, 0), report.attachments)
        runBlocking {
            assertEquals(DocumentRole.SERVICE_MANUAL, target.attachments.get(AttachmentId("att1"))?.role)
        }
        assertEquals(MergeTally(0, 1, 0, 0), runBlocking { target.merge.plan(archive) }.attachments)
    }

    /**
     * #85 C5: a document's source provenance travels by merge onto a **new** row, end to end — the production
     * export, the real codec, the plan and the apply — and the same archive then re-plans IDENTICAL.
     */
    @Test
    fun anInsertCarriesTheSource() {
        val provenance = AttachmentSource(
            uri = "https://manuals.example.invalid/pool-pump/manual.pdf",
            resolvedUri = null,
            retrievedAt = 1_758_900_000_000L,
            name = "Example Pool Pump manual",
        )
        val donor = Fakes()
        runBlocking {
            donor.assets.upsert(asset("a1", "Example Pool Pump"))
            donor.attachments.upsert(attachment("att1", "a1").copy(source = provenance))
        }
        val archive = exportOf(donor)
        val target = Fakes(
            FakeAttachmentStorage(InMemoryAttachmentStore().also { it.files["assets/a1/att1.pdf"] = bytes }),
        )

        val report = runBlocking { target.merge.run(archive) }

        assertEquals(MergeTally(1, 0, 0, 0), report.attachments)
        runBlocking { assertEquals(provenance, target.attachments.get(AttachmentId("att1"))?.source) }
        assertEquals(MergeTally(0, 1, 0, 0), runBlocking { target.merge.plan(archive) }.attachments)
    }

    /**
     * R67-12 B, end to end, through the path the app really takes: an export made before this phone
     * gave its documents a role — a format-9 archive, exactly as a #74 build wrote it — still re-plans
     * **applicable, with zero INSERT and every attachment IDENTICAL** after the roles are given the way
     * the sheet gives them, through `UpdateAttachment`, whose save also moves the last-modified stamp.
     * So it stays restorable by merge, and the dev → production path is not blocked by a role.
     */
    @Test
    fun `a pre-format-10 export still re-plans IDENTICAL after roles are given through UpdateAttachment`() {
        val phone = Fakes(
            FakeAttachmentStorage(
                InMemoryAttachmentStore().also {
                    it.files["assets/a1/att1.pdf"] = bytes
                    it.files["assets/a1/att2.pdf"] = bytes
                },
            ),
        )
        runBlocking {
            phone.assets.upsert(asset("a1", "Hot tub"))
            phone.attachments.upsert(attachment("att1", "a1"))
            phone.attachments.upsert(attachment("att2", "a1"))
        }
        val before = BackupCodec.decode(exportOf(phone)).let { decoded ->
            BackupCodec.encode(
                decoded.data, appVersion = "1.4.1", schemaVersion = 9,
                createdAt = 1_758_400_000_000L, backupSetId = "set-merge", formatVersion = 9,
            )
        }
        val sheet = UpdateAttachment(phone.attachments, phone.uow, Clock { 1_758_500_000_000L })
        runBlocking {
            for ((id, role) in listOf("att1" to DocumentRole.USER_MANUAL, "att2" to DocumentRole.PURCHASE_INVOICE_OR_RECEIPT)) {
                val row = phone.attachments.get(AttachmentId(id))!!
                assertTrue(
                    sheet.run(row.id, UpdateAttachmentCommand(row.displayName, row.kind, row.capturedOn, row.notes, role))
                        is AttachmentResult.Ok,
                )
            }
            // the stamp moved with the role, as it does on the phone
            assertEquals(listOf(1_758_500_000_000L, 1_758_500_000_000L), phone.attachments.all().map { it.updatedAt })
        }

        val report = runBlocking { phone.merge.plan(before) }

        assertTrue(report.applicable, report.conflicts.toString())
        assertEquals(MergeTally(0, 2, 0, 0), report.attachments)
        assertEquals(MergeTally(0, 1, 0, 0), report.assets)
        val tallies = listOf(
            report.assets, report.groups, report.definitions, report.profiles, report.schedules,
            report.closures, report.links, report.tags, report.events, report.attachments,
            report.references, report.seasonActivations, report.conditions, report.healthSubjects,
            report.categories,
        )
        assertEquals(0, tallies.sumOf { it.insert })
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

    /**
     * #79 (C19): the apply writes a case after its asset and after the events — which it names only
     * softly — and each case's entries after the case, then rebuilds once. Every write is logged in
     * the order it reached its store.
     */
    @Test
    fun casesThenEntriesLandAfterAssetsAndEvents() = runBlocking<Unit> {
        val donor = Fakes()
        donor.assets.upsert(asset("a1", "Example Heater"))
        donor.events.upsert(HealthFixtures.eventOf("e1", "a1", EventKind.INCIDENT, "No hot water", "2026-09-20"))
        donor.serviceCases.upsert(caseOf("c1", incident = "e1"))
        donor.caseEntries.insert(caseEntryOf("n1"))
        donor.caseEntries.insert(caseEntryOf("n2", note = "Courier collected", occurredOn = "2026-09-22"))
        val archive = exportOf(donor)

        val target = Fakes()
        val log = mutableListOf<String>()
        val assets = object : AssetRepository by target.assets {
            override suspend fun upsert(asset: Asset) = target.assets.upsert(asset).also { log += "asset:${asset.id.value}" }
        }
        val events = object : EventRepository by target.events {
            override suspend fun upsert(e: AssetEvent) = target.events.upsert(e).also { log += "event:${e.id.value}" }
        }
        val cases = object : ServiceCaseRepository by target.serviceCases {
            override suspend fun upsert(case: ServiceCase) = target.serviceCases.upsert(case).also { log += "case:${case.id.value}" }
        }
        val entries = object : ServiceCaseEntryRepository by target.caseEntries {
            override suspend fun insert(entry: ServiceCaseEntry) =
                target.caseEntries.insert(entry).also { log += "entry:${entry.id.value}" }
        }
        val apply = ApplyBackupMergePlan(
            assets, target.groups, target.tags, target.links, target.definitions, target.profiles, target.schedules,
            target.closures, events, target.attachments, target.references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            target.categories, cases, entries, target.loans, target.transfers,
            target.successions, InMemorySupplyItemRepository(), InMemoryAssetSupplyRepository(), target.storage, target.uow, rebuildAll = { log += "rebuild" },
        )

        apply.run(target.build.run(archive))

        assertEquals(listOf("asset:a1", "event:e1", "case:c1", "entry:n1", "entry:n2", "rebuild"), log)
    }

    /**
     * #72 (C7): the apply writes a loan after its asset — the open one and the returned history — then
     * rebuilds once, and a re-plan of the same archive is IDENTICAL. Every write is logged in the order it
     * reached its store.
     */
    @Test
    fun loansLandAfterAssets() = runBlocking<Unit> {
        val donor = Fakes()
        donor.assets.upsert(asset("a1", "Example Drill"))
        donor.loans.upsert(loanOf("l1"))
        donor.loans.upsert(loanOf("l0", lentOn = "2026-08-01", returnedOn = "2026-08-02"))
        val archive = exportOf(donor)

        val target = Fakes()
        val log = mutableListOf<String>()
        val assets = object : AssetRepository by target.assets {
            override suspend fun upsert(asset: Asset) = target.assets.upsert(asset).also { log += "asset:${asset.id.value}" }
        }
        val loans = object : AssetLoanRepository by target.loans {
            override suspend fun upsert(loan: AssetLoan) = target.loans.upsert(loan).also { log += "loan:${loan.id.value}" }
        }
        val apply = ApplyBackupMergePlan(
            assets, target.groups, target.tags, target.links, target.definitions, target.profiles, target.schedules,
            target.closures, target.events, target.attachments, target.references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            target.categories, target.serviceCases, target.caseEntries, loans, target.transfers,
            target.successions, InMemorySupplyItemRepository(), InMemoryAssetSupplyRepository(), target.storage, target.uow,
            rebuildAll = { log += "rebuild" },
        )

        apply.run(target.build.run(archive))

        assertEquals(listOf("asset:a1", "loan:l0", "loan:l1", "rebuild"), log)
        assertEquals(donor.loans.all(), target.loans.all())
        assertTrue(target.build.run(archive).decisions.all { it.verdict == MergeVerdict.IDENTICAL })
    }

    /**
     * #77 (C10): the transfer records are applied **last** — after the loans, before the one rebuild — so a
     * record never lands ahead of the rows the same plan inserts (B2b's write guard reads it). A re-plan of the
     * same archive is IDENTICAL.
     */
    @Test
    fun recordsLandLast() = runBlocking<Unit> {
        val donor = Fakes()
        donor.assets.upsert(asset("a1", "Example Drill"))
        donor.loans.upsert(loanOf("l1"))
        donor.transfers.append(transferOf("r1", assetId = "a1", kind = TransferKind.IN, packId = "pack-o"))
        donor.transfers.append(transferOf("r2", assetId = "a9", packId = "pack-q"))
        val archive = exportOf(donor)

        val target = Fakes()
        val log = mutableListOf<String>()
        val assets = object : AssetRepository by target.assets {
            override suspend fun upsert(asset: Asset) = target.assets.upsert(asset).also { log += "asset:${asset.id.value}" }
        }
        val loans = object : AssetLoanRepository by target.loans {
            override suspend fun upsert(loan: AssetLoan) = target.loans.upsert(loan).also { log += "loan:${loan.id.value}" }
        }
        val transfers = object : TransferRecordRepository by target.transfers {
            override suspend fun append(record: TransferRecord) =
                target.transfers.append(record).also { log += "transfer:${record.id}" }
        }
        val apply = ApplyBackupMergePlan(
            assets, target.groups, target.tags, target.links, target.definitions, target.profiles, target.schedules,
            target.closures, target.events, target.attachments, target.references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            target.categories, target.serviceCases, target.caseEntries, loans, transfers,
            target.successions, InMemorySupplyItemRepository(), InMemoryAssetSupplyRepository(), target.storage, target.uow,
            rebuildAll = { log += "rebuild" },
        )

        apply.run(target.build.run(archive))

        assertEquals(listOf("asset:a1", "loan:l1", "transfer:r1", "transfer:r2", "rebuild"), log)
        assertEquals(donor.transfers.all(), target.transfers.all())
        assertTrue(target.build.run(archive).decisions.all { it.verdict == MergeVerdict.IDENTICAL })
    }

    /**
     * #86 (C4): the successions land after their assets and the loans, and before the transfer records — the
     * apply's order — so both ends are in when a row is appended, as the schema's foreign keys need.
     */
    @Test
    fun successionsLandAfterAssetsAndLoans() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Example Water Heater"))
            source.assets.upsert(asset("a2", "Example Water Heater, second"))
            source.loans.upsert(loanOf("l1", assetId = "a1", lentOn = "2026-08-01", returnedOn = "2026-08-02"))
            source.successions.append(successionOf("s1", predecessor = "a1", successor = "a2"))
            source.transfers.append(transferOf("r1", assetId = "x9", packId = "pack-q"))
        }
        val archive = exportOf(source)

        val target = Fakes()
        val seen = mutableListOf<String>()
        target.successions.onAppend = { row ->
            seen += "${row.id}: assets=${target.assets.rows.keys.sorted()} loans=${target.loans.rows.keys.sorted()} records=${target.transfers.rows.keys}"
        }
        val report = runBlocking { target.merge.run(archive) }

        assertTrue(report.applicable, "unexpected conflicts: ${report.conflicts}")
        assertEquals(listOf("s1: assets=[a1, a2] loans=[l1] records=[]"), seen)
        assertEquals(listOf("s1"), runBlocking { target.successions.all() }.map { it.id })
        assertEquals(listOf("r1"), target.transfers.rows.keys.toList(), "the records still land, after it")
    }
}
