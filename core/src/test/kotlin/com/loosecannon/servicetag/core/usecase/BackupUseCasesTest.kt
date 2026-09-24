package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupNewerFormat
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.ConsumableUsage
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.DerivedSpec
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
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
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class BackupUseCasesTest {

    private class Fakes(
        val definitions: InMemoryDefinitionRepository = InMemoryDefinitionRepository(),
        val assets: InMemoryAssetRepository = InMemoryAssetRepository(),
        val attachments: InMemoryAttachmentRepository = InMemoryAttachmentRepository(),
    ) {
        val tags = InMemoryTagRepository()
        val links = InMemoryLinkRepository()
        val profiles = InMemoryProfileRepository()
        val events = InMemoryEventRepository()
        val groups = InMemoryGroupRepository()
        val closures = InMemoryClosureRepository()
        val schedules = InMemoryScheduleRepository(closures)
        val references = InMemoryReferenceRepository()
        val storage = FakeAttachmentStorage()
        val uow = FakeUnitOfWork(
            assets, groups, tags, links, definitions, profiles, schedules, closures,
            events, attachments, references,
        )
    }

    private fun asset(
        id: String,
        name: String,
        status: AssetStatus = AssetStatus.ACTIVE,
        parentAssetId: String? = null,
    ) = Asset(
        AssetId(id), name, "desc $id", "cat", "notes", status,
        createdAt = 1L, updatedAt = 2L, parentAssetId = parentAssetId?.let(::AssetId),
    )

    private fun link(id: String, assetId: String?) = ExternalLink(
        id = LinkId(id),
        assetId = assetId?.let(::AssetId),
        kind = LinkKind.JOPLIN,
        label = "label $id",
        uri = "joplin://$id",
        createdAt = 3L,
        lastOpenedAt = null,
        updatedAt = 4L,
    )

    private fun tag(id: String, target: TagTarget, status: TagStatus = TagStatus.ACTIVE) = TagBinding(
        id = TagId(id),
        payloadFormat = PayloadFormat.V1,
        payloadKey = "key-$id",
        target = target,
        status = status,
        label = "tag $id",
        physicalUid = null,
        writtenAt = 5L,
        lastScannedAt = null,
        createdAt = 6L,
        updatedAt = 7L,
    )

    // --- journal fixtures ------------------------------------------------------------------------

    private fun definition(id: String, assetId: String, valueType: ValueType = ValueType.NUMBER) = MeasurementDefinition(
        id = DefinitionId(id), assetId = AssetId(assetId), key = "key-$id", label = "Label $id", unit = "",
        valueType = valueType, decimals = 1, rangeLow = null, rangeHigh = null, isMeter = false,
        sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 2L,
    )

    private fun derivedDefinition(id: String, assetId: String, sourceA: String, sourceB: String) = MeasurementDefinition(
        id = DefinitionId(id), assetId = AssetId(assetId), key = "key-$id", label = "Label $id", unit = "%",
        valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null, isMeter = false,
        sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 2L,
        kind = DefinitionKind.DERIVED,
        derived = DerivedSpec(DerivedFormula.PERCENT_DROP, DefinitionId(sourceA), DefinitionId(sourceB)),
    )

    private fun profileField(id: String, definitionId: String, sortOrder: Int = 0) =
        ProfileField(id, DefinitionId(definitionId), required = true, sortOrder = sortOrder)

    private fun profileConsumable(id: String, sortOrder: Int = 0) =
        ProfileConsumable(id, "Consumable $id", 1.0, "unit", sortOrder)

    private fun profile(
        id: String,
        assetId: String,
        fields: List<ProfileField> = emptyList(),
        consumables: List<ProfileConsumable> = emptyList(),
    ) = EventProfile(
        id = ProfileId(id), assetId = AssetId(assetId), name = "Profile $id", eventKind = EventKind.MEASUREMENT,
        defaultTitle = "Title", templateKey = null, sortOrder = 0, archivedAt = null,
        createdAt = 1L, updatedAt = 2L, fields = fields, consumables = consumables,
    )

    private fun measurement(
        id: String,
        definitionId: String,
        valueNum: Double? = 1.0,
        valueText: String? = null,
        sortOrder: Int = 0,
    ) = Measurement(id, DefinitionId(definitionId), valueNum, valueText, "", sortOrder)

    private fun consumableUsage(id: String, sortOrder: Int = 0) =
        ConsumableUsage(id, "Usage $id", 1.0, "unit", sortOrder)

    private fun event(
        id: String,
        assetId: String,
        profileId: String? = null,
        measurements: List<Measurement> = emptyList(),
        consumables: List<ConsumableUsage> = emptyList(),
    ) = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = EventKind.MEASUREMENT, title = "Title",
        profileId = profileId?.let(::ProfileId), occurredOn = "2026-09-15", occurredTime = null, tzId = "UTC",
        notes = "", source = EventSource.MANUAL, sourceRef = null, createdAt = 1L, updatedAt = 2L,
        measurements = measurements, consumables = consumables,
    )

    private fun attachment(id: String, owner: AttachmentOwner, locator: String) = Attachment(
        id = AttachmentId(id),
        owner = owner,
        kind = AttachmentKind.DOCUMENT,
        displayName = "Manual.pdf",
        mimeType = "application/pdf",
        sizeBytes = 12L,
        sha256 = "a".repeat(64),
        storageLocator = locator,
        capturedOn = null,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private suspend fun populate(f: Fakes) {
        f.assets.upsert(asset("a1", "Furnace"))
        f.assets.upsert(asset("a2", "Mower", AssetStatus.ARCHIVED))
        f.assets.upsert(asset("a3", "Heater", AssetStatus.ARCHIVED))
        f.links.upsert(link("l1", "a1"))
        f.links.upsert(link("l2", "a2"))
        f.links.upsert(link("l3", null))
        f.tags.upsert(tag("t1", TagTarget.AssetTarget(AssetId("a1"))))
        f.tags.upsert(tag("t2", TagTarget.LinkTarget(LinkId("l3"))))
        f.tags.upsert(tag("t3", TagTarget.None, TagStatus.UNBOUND))
        f.tags.upsert(tag("t4", TagTarget.AssetTarget(AssetId("a2")), TagStatus.LOST))
    }

    /** One asset carrying one row in each of the seven journal tables, all cross-referencing cleanly. */
    private suspend fun populateJournal(f: Fakes, assetId: String = "a1") {
        f.definitions.upsert(definition("d1", assetId))
        f.profiles.upsert(
            profile(
                "p1", assetId,
                fields = listOf(profileField("pf1", "d1")),
                consumables = listOf(profileConsumable("pc1")),
            ),
        )
        f.events.upsert(
            event(
                "e1", assetId, profileId = "p1",
                measurements = listOf(measurement("m1", "d1", valueNum = 7.4)),
                consumables = listOf(consumableUsage("cu1")),
            ),
        )
    }

    private fun exportOf(f: Fakes, now: Long = 1_726_000_000_000L): ByteArray = runBlocking {
        ExportBackupSet(
            f.assets, f.groups, f.tags, f.links, f.definitions, f.profiles, f.schedules,
            f.closures, f.events, f.attachments, f.references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            f.uow, IdGenerator { "set-1" }, Clock { now }, appVersion = "2.0", schemaVersion = 1,
        ).run().data
    }

    private fun importInto(f: Fakes, bytes: ByteArray): ImportReport = runBlocking {
        ImportBackupReplace(
            f.assets, f.groups, f.tags, f.links, f.definitions, f.profiles, f.schedules,
            f.closures, f.events, f.attachments, f.references,
            InMemorySeasonActivationRepository(), InMemoryConditionRepository(), InMemoryHealthSubjectRepository(),
            f.storage, f.uow, rebuildAll = { },
        ).run(bytes)
    }

    @Test
    fun `export then import into empty fakes reproduces contents and ids`() {
        val source = Fakes()
        runBlocking { populate(source) }
        val bytes = exportOf(source)

        val target = Fakes()
        val report = importInto(target, bytes)

        runBlocking {
            assertEquals(source.assets.all().sortedBy { it.id.value }, target.assets.all().sortedBy { it.id.value })
            assertEquals(source.tags.all().sortedBy { it.id.value }, target.tags.all().sortedBy { it.id.value })
            assertEquals(source.links.all().sortedBy { it.id.value }, target.links.all().sortedBy { it.id.value })
        }
        assertEquals(
            ImportReport(
                formatVersion = BackupCodec.FORMAT_VERSION, assets = 3, tags = 4, links = 3, definitions = 0, profiles = 0,
                events = 0, attachments = 0, lastRestoredBackupSetId = "set-1",
            ),
            report,
        )
    }

    @Test
    fun `tag targets survive the round trip`() {
        val source = Fakes()
        runBlocking { populate(source) }
        val target = Fakes()
        importInto(target, exportOf(source))

        runBlocking {
            assertEquals(TagTarget.AssetTarget(AssetId("a1")), target.tags.get(TagId("t1"))!!.target)
            assertEquals(TagTarget.LinkTarget(LinkId("l3")), target.tags.get(TagId("t2"))!!.target)
            assertEquals(TagTarget.None, target.tags.get(TagId("t3"))!!.target)
            assertEquals(PayloadFormat.V1, target.tags.get(TagId("t1"))!!.payloadFormat)
            assertEquals(1, target.links.rows.values.count { it.assetId == null })
        }
    }

    /**
     * 2.6 — the `externalLinks` tombstone. Nothing in the app creates a link any more, so this is
     * the only proof left that an old set's rows survive a replace-import intact and come back out
     * of the next export unchanged.
     */
    @Test
    fun `external link rows round-trip through export and replace-import unchanged`() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Furnace"))
            source.links.upsert(link("l1", "a1"))
            source.links.upsert(link("l3", null))
        }
        val before = runBlocking { source.links.all().sortedBy { it.id.value } }
        val target = Fakes()
        importInto(target, exportOf(source))
        runBlocking {
            assertEquals(before, target.links.all().sortedBy { it.id.value })
        }
        val decoded = BackupCodec.decode(exportOf(target))
        assertEquals(listOf("l1", "l3"), decoded.data.externalLinks.map { it.id })
    }

    @Test
    fun `export uses the clock for createdAt`() {
        val f = Fakes()
        runBlocking { populate(f) }
        val manifest = BackupCodec.decode(exportOf(f, now = 777_000L)).manifest
        assertEquals(777_000L, manifest.createdAt)
        assertEquals("2.0", manifest.appVersion)
    }

    @Test
    fun exportReadsAllTablesInsideOneReadSnapshot() {
        val f = Fakes()
        runBlocking { populate(f) }

        exportOf(f)

        assertEquals(1, f.uow.reads)
        assertEquals(0, f.uow.readsOutsideSnapshot)
    }

    @Test
    fun `import replaces pre-existing rows`() {
        val source = Fakes()
        runBlocking { populate(source) }
        val bytes = exportOf(source)

        val target = Fakes()
        runBlocking {
            target.assets.upsert(asset("old-asset", "Stale"))
            target.links.upsert(link("old-link", null))
            target.tags.upsert(tag("old-tag", TagTarget.None))
        }

        importInto(target, bytes)

        runBlocking {
            assertNull(target.assets.get(AssetId("old-asset")))
            assertNull(target.links.get(LinkId("old-link")))
            assertNull(target.tags.get(TagId("old-tag")))
            assertEquals(3, target.assets.all().size)
            assertEquals(4, target.tags.all().size)
            assertEquals(3, target.links.all().size)
        }
    }

    @Test
    fun `a failed insert rolls the whole import back`() {
        val source = Fakes()
        runBlocking { populate(source) }
        val bytes = exportOf(source)

        val target = Fakes()
        runBlocking {
            target.assets.upsert(asset("keep-a", "Keep me"))
            target.links.upsert(link("keep-l", null))
            target.tags.upsert(tag("keep-t", TagTarget.None))
        }
        val before = runBlocking {
            Triple(target.assets.all(), target.tags.all(), target.links.all())
        }

        target.tags.failOnUpsert = 3 + 1 // the pre-existing keep-t upsert counts, so this is the third imported tag

        assertFailsWith<RiggedFailure> { importInto(target, bytes) }

        runBlocking {
            assertEquals(before.first, target.assets.all())
            assertEquals(before.second, target.tags.all())
            assertEquals(before.third, target.links.all())
        }
        assertEquals(1, target.uow.rollbacks)
        assertEquals(0, target.uow.commits)
    }

    @Test
    fun `importing a newer format changes nothing`() {
        val source = Fakes()
        runBlocking { populate(source) }
        val bytes = bumpFormatVersion(exportOf(source))

        val target = Fakes()
        runBlocking { target.assets.upsert(asset("untouched", "Untouched")) }

        val e = assertFailsWith<BackupNewerFormat> { importInto(target, bytes) }
        assertEquals(BackupCodec.FORMAT_VERSION + 1, e.found)
        assertEquals(BackupCodec.FORMAT_VERSION, e.supported)

        runBlocking {
            assertEquals(listOf("untouched"), target.assets.all().map { it.id.value })
            assertTrue(target.tags.all().isEmpty())
            assertTrue(target.links.all().isEmpty())
        }
        assertEquals(0, target.uow.commits)
        assertEquals(0, target.uow.rollbacks) // decode happens outside the transaction
    }

    @Test
    fun `exporting empty repositories yields an empty but valid backup`() {
        val f = Fakes()
        val decoded = BackupCodec.decode(exportOf(f))
        assertTrue(decoded.data.assets.isEmpty())
        assertTrue(decoded.data.nfcTags.isEmpty())
        assertTrue(decoded.data.externalLinks.isEmpty())
        assertEquals(
            mapOf(
                "assets" to 0, "nfcTags" to 0, "externalLinks" to 0,
                "measurementDefinitions" to 0, "eventProfiles" to 0, "assetEvents" to 0,
                "profileFields" to 0, "profileConsumables" to 0,
                "measurements" to 0, "consumableUsages" to 0,
                "attachments" to 0,
                "maintenanceGroups" to 0, "groupMembers" to 0,
                "maintenanceSchedules" to 0, "scheduleProviders" to 0,
                "occurrenceClosures" to 0,
                "assetReferences" to 0,
            ),
            decoded.manifest.counts,
        )

        val target = Fakes()
        runBlocking { target.assets.upsert(asset("gone", "Gone")) }
        val report = importInto(target, exportOf(f))
        assertEquals(
            ImportReport(
                formatVersion = BackupCodec.FORMAT_VERSION, assets = 0, tags = 0, links = 0, definitions = 0, profiles = 0,
                events = 0, attachments = 0, lastRestoredBackupSetId = "set-1",
            ),
            report,
        )
        runBlocking { assertTrue(target.assets.all().isEmpty()) }
    }

    // --- journal tables (format 2) ---------------------------------------------------------------

    @Test
    fun exportCarriesJournalRows() {
        val f = Fakes()
        runBlocking {
            f.assets.upsert(asset("a1", "Furnace"))
            populateJournal(f)
        }

        val decoded = BackupCodec.decode(exportOf(f))

        assertEquals(listOf("d1"), decoded.data.measurementDefinitions.map { it.id })
        assertEquals(listOf("p1"), decoded.data.eventProfiles.map { it.id })
        assertEquals(listOf("e1"), decoded.data.assetEvents.map { it.id })
        assertEquals(listOf("pf1"), decoded.data.eventProfiles[0].fields.map { it.id })
        assertEquals(listOf("pc1"), decoded.data.eventProfiles[0].consumables.map { it.id })
        assertEquals(listOf("m1"), decoded.data.assetEvents[0].measurements.map { it.id })
        assertEquals(listOf("cu1"), decoded.data.assetEvents[0].consumables.map { it.id })
    }

    @Test
    fun importReplacesJournalRowsAndReportsCounts() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Furnace"))
            populateJournal(source)
        }
        val bytes = exportOf(source)

        val target = Fakes()
        runBlocking {
            target.assets.upsert(asset("a2", "Old asset"))
            target.definitions.upsert(definition("d-old", "a2"))
            target.profiles.upsert(profile("p-old", "a2"))
            target.events.upsert(event("e-old", "a2"))
        }

        val report = importInto(target, bytes)

        assertEquals(
            ImportReport(
                formatVersion = BackupCodec.FORMAT_VERSION, assets = 1, tags = 0, links = 0, definitions = 1, profiles = 1,
                events = 1, attachments = 0, lastRestoredBackupSetId = "set-1",
            ),
            report,
        )
        runBlocking {
            assertNull(target.definitions.get(DefinitionId("d-old")))
            assertNull(target.profiles.get(ProfileId("p-old")))
            assertNull(target.events.get(EventId("e-old")))
            assertEquals(listOf("d1"), target.definitions.all().map { it.id.value })
            assertEquals(listOf("p1"), target.profiles.all().map { it.id.value })
            assertEquals(listOf("e1"), target.events.all().map { it.id.value })
        }
    }

    @Test
    fun importFailureLeavesEverythingIntact() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Furnace"))
            populateJournal(source)
        }
        val bytes = exportOf(source)

        val target = Fakes()
        runBlocking {
            target.assets.upsert(asset("keep-a", "Keep me"))
            target.definitions.upsert(definition("keep-d", "keep-a"))
        }
        val before = runBlocking {
            Triple(target.assets.all(), target.definitions.all(), target.events.all())
        }

        target.events.failOnUpsert = 1 // the single imported event

        assertFailsWith<RiggedFailure> { importInto(target, bytes) }

        runBlocking {
            assertEquals(before.first, target.assets.all())
            assertEquals(before.second, target.definitions.all())
            assertEquals(before.third, target.events.all())
        }
        assertEquals(1, target.uow.rollbacks)
        assertEquals(0, target.uow.commits)
    }

    @Test
    fun importReportEchoesTheManifestsFormatVersion() {
        val source = Fakes()
        runBlocking { populate(source) }
        val v2Bytes = exportOf(source)

        val v2Report = importInto(Fakes(), v2Bytes)
        assertEquals(BackupCodec.FORMAT_VERSION, v2Report.formatVersion)
        assertEquals("set-1", v2Report.lastRestoredBackupSetId)

        // reseal the same, already-valid data under a manifest claiming format 1 — the same
        // trick BackupCodecTest's formatOneFileStillDecodes uses.
        val v1Data = BackupCodec.decode(v2Bytes).data
        val v1Bytes = BackupCodec.encode(
            v1Data,
            appVersion = "2.0",
            schemaVersion = 1,
            createdAt = 1_726_000_000_000L,
            backupSetId = "",
            formatVersion = 1,
        )
        val v1Report = importInto(Fakes(), v1Bytes)
        assertEquals(1, v1Report.formatVersion)
        assertEquals("", v1Report.lastRestoredBackupSetId)
    }

    // --- attachments (format 5) -------------------------------------------------------------------

    @Test
    fun `attachment rows survive the round trip and are reported`() {
        val source = Fakes()
        runBlocking {
            populate(source)
            populateJournal(source)
            source.attachments.upsert(attachment("att-1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf"))
            source.attachments.upsert(attachment("att-2", AttachmentOwner.OfEvent(EventId("e1")), "events/e1/att-2.jpg"))
        }
        val target = Fakes()
        val report = importInto(target, exportOf(source))

        runBlocking {
            assertEquals(
                source.attachments.all().sortedBy { it.id.value },
                target.attachments.all().sortedBy { it.id.value },
            )
        }
        assertEquals(2, report.attachments)
        assertEquals(BackupCodec.FORMAT_VERSION, report.formatVersion)
        assertEquals("set-1", report.lastRestoredBackupSetId)
    }

    @Test
    fun `a replace import deletes the bytes of the rows it replaced`() {
        val target = Fakes()
        runBlocking {
            populate(target)
            target.attachments.upsert(attachment("old", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/old.pdf"))
            target.storage.store.put("assets/a1/old.pdf", ByteSource { "x".toByteArray().inputStream() })
        }
        val source = Fakes()
        runBlocking { populate(source) }
        importInto(target, exportOf(source))

        runBlocking {
            assertTrue(target.attachments.all().isEmpty())
            assertFalse(target.storage.store.exists("assets/a1/old.pdf"))
        }
    }

    @Test
    fun `a row that comes back in the file keeps its bytes`() {
        val f = Fakes()
        runBlocking {
            populate(f)
            f.attachments.upsert(attachment("att-1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf"))
            f.storage.store.put("assets/a1/att-1.pdf", ByteSource { "x".toByteArray().inputStream() })
        }
        importInto(f, exportOf(f)) // export and re-import the same install

        runBlocking {
            assertEquals(listOf("att-1"), f.attachments.all().map { it.id.value })
            assertTrue(f.storage.store.exists("assets/a1/att-1.pdf"))
        }
        assertEquals(0, f.storage.store.deletes)
    }

    /** Fails an upsert whose owner row is not in yet, to prove attachment rows go in last. */
    private class FkCheckingAttachmentRepository : InMemoryAttachmentRepository() {
        lateinit var assets: InMemoryAssetRepository
        lateinit var events: InMemoryEventRepository

        override suspend fun upsert(a: Attachment) {
            val ownerIsIn = when (val owner = a.owner) {
                is AttachmentOwner.OfAsset -> assets.get(owner.assetId) != null
                is AttachmentOwner.OfEvent -> events.get(owner.eventId) != null
            }
            check(ownerIsIn) { "attachment ${a.id.value} has an owner that is not inserted yet" }
            super.upsert(a)
        }
    }

    @Test
    fun `attachment rows are written after the owners they point at`() {
        val source = Fakes()
        runBlocking {
            populate(source)
            populateJournal(source)
            source.attachments.upsert(attachment("att-1", AttachmentOwner.OfAsset(AssetId("a1")), "assets/a1/att-1.pdf"))
            source.attachments.upsert(attachment("att-2", AttachmentOwner.OfEvent(EventId("e1")), "events/e1/att-2.jpg"))
        }
        val target = Fakes(attachments = FkCheckingAttachmentRepository())
        (target.attachments as FkCheckingAttachmentRepository).let {
            it.assets = target.assets
            it.events = target.events
        }

        val report = importInto(target, exportOf(source))

        assertEquals(2, report.attachments)
        runBlocking {
            assertEquals(listOf("att-1", "att-2"), target.attachments.all().map { it.id.value })
        }
    }

    /** Fails a DERIVED upsert whose sources are not yet in [rows], to prove insert order. */
    private class FkCheckingDefinitionRepository : InMemoryDefinitionRepository() {
        override suspend fun upsert(d: MeasurementDefinition) {
            if (d.kind == DefinitionKind.DERIVED) {
                val spec = requireNotNull(d.derived) { "definition ${d.id.value} is DERIVED with no spec" }
                check(rows.containsKey(spec.sourceA.value)) {
                    "definition ${d.id.value} is DERIVED but source ${spec.sourceA.value} is not inserted yet"
                }
                check(rows.containsKey(spec.sourceB.value)) {
                    "definition ${d.id.value} is DERIVED but source ${spec.sourceB.value} is not inserted yet"
                }
            }
            super.upsert(d)
        }
    }

    @Test
    fun importInsertsEnteredBeforeDerived() {
        val source = Fakes()
        runBlocking {
            source.assets.upsert(asset("a1", "Furnace"))
            // ids chosen so the file's own (alphabetical) sort order would put the DERIVED row
            // first if import didn't partition by kind — that's what actually proves the fix.
            source.definitions.upsert(definition("z_a", "a1"))
            source.definitions.upsert(definition("z_b", "a1"))
            source.definitions.upsert(derivedDefinition("a_derived", "a1", "z_a", "z_b"))
        }
        val bytes = exportOf(source)

        val target = Fakes(definitions = FkCheckingDefinitionRepository())
        val report = importInto(target, bytes)

        assertEquals(3, report.definitions)
        runBlocking {
            assertEquals(
                setOf("z_a", "z_b", "a_derived"),
                target.definitions.all().map { it.id.value }.toSet(),
            )
        }
    }

    /** Fails an upsert whose `parentAssetId` is not yet in [rows], to prove insert order. */
    private class FkCheckingAssetRepository : InMemoryAssetRepository() {
        override suspend fun upsert(asset: Asset) {
            val parent = asset.parentAssetId
            if (parent != null) {
                check(rows.containsKey(parent.value)) {
                    "asset ${asset.id.value} has parent ${parent.value} which is not inserted yet"
                }
            }
            super.upsert(asset)
        }
    }

    @Test
    fun shuffledTreeImportsParentsFirst() {
        val source = Fakes()
        runBlocking {
            // ids chosen so the file's own (alphabetical) sort order lists the grandchild
            // first, then the child, then the root — children before parents — which is what
            // actually proves parents-first import order, not file order.
            source.assets.upsert(asset("c_root", "Root"))
            source.assets.upsert(asset("b_child", "Child", parentAssetId = "c_root"))
            source.assets.upsert(asset("a_grandchild", "Grandchild", parentAssetId = "b_child"))
        }
        val bytes = exportOf(source)
        assertEquals(
            listOf("a_grandchild", "b_child", "c_root"),
            BackupCodec.decode(bytes).data.assets.map { it.id },
            "fixture is not actually shuffled",
        )

        val target = Fakes(assets = FkCheckingAssetRepository())
        val report = importInto(target, bytes)

        assertEquals(3, report.assets)
        runBlocking {
            assertEquals(
                setOf("a_grandchild", "b_child", "c_root"),
                target.assets.all().map { it.id.value }.toSet(),
            )
            assertEquals(AssetId("c_root"), target.assets.get(AssetId("b_child"))!!.parentAssetId)
            assertEquals(AssetId("b_child"), target.assets.get(AssetId("a_grandchild"))!!.parentAssetId)
        }
    }

    // --- helper: rewrite the manifest to claim a newer format version --------------------------

    private fun bumpFormatVersion(bytes: ByteArray): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e: ZipEntry = zin.nextEntry ?: break
                entries[e.name] = zin.readBytes()
                zin.closeEntry()
            }
        }
        val manifest = String(entries.getValue(BackupCodec.MANIFEST_ENTRY), Charsets.UTF_8)
            .replace(
                Regex("\"formatVersion\"\\s*:\\s*${BackupCodec.FORMAT_VERSION}"),
                "\"formatVersion\": ${BackupCodec.FORMAT_VERSION + 1}",
            )
        entries[BackupCodec.MANIFEST_ENTRY] = manifest.toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, payload) ->
                zos.putNextEntry(ZipEntry(name).also { it.time = 0L })
                zos.write(payload)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
