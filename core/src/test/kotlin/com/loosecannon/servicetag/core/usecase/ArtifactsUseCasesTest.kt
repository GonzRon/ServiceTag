package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.ArtifactsPlan
import com.loosecannon.servicetag.core.backup.ArtifactsPlanEntry
import com.loosecannon.servicetag.core.backup.ArtifactsSetMismatch
import com.loosecannon.servicetag.core.backup.ArtifactsWritten
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentMode
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.AttachmentStore
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.testing.FakeAttachmentStorage
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentRepository
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryLinkRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryReferenceRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryTagRepository
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactsUseCasesTest {

    private val assets = InMemoryAssetRepository()
    private val tags = InMemoryTagRepository()
    private val links = InMemoryLinkRepository()
    private val definitions = InMemoryDefinitionRepository()
    private val profiles = InMemoryProfileRepository()
    private val events = InMemoryEventRepository()
    private val attachments = InMemoryAttachmentRepository()
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val schedules = InMemoryScheduleRepository(closures)
    private val references = InMemoryReferenceRepository()
    private val uow = FakeUnitOfWork(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references,
    )
    private val storage = FakeAttachmentStorage()
    private val store: InMemoryAttachmentStore get() = storage.store

    private val export = ExportBackupSet(
        assets, groups, tags, links, definitions, profiles, schedules, closures, events,
        attachments, references, uow,
        IdGenerator { "set-1" }, Clock { 1_726_000_000_000L }, appVersion = "2.4", schemaVersion = 5,
    )
    private val restore = RestoreArtifacts(attachments, storage)

    private val payload = "bullfrog headrest clip".toByteArray()

    private suspend fun seed(id: String = "att-1", mode: AttachmentMode = AttachmentMode.MANAGED): Attachment {
        assets.upsert(Asset(id = AssetId("a1"), name = "Hot tub", createdAt = 1L, updatedAt = 1L))
        val locator = "assets/a1/$id.pdf"
        val row = Attachment(
            id = AttachmentId(id), owner = AttachmentOwner.OfAsset(AssetId("a1")),
            kind = AttachmentKind.MANUAL, mode = mode, displayName = "$id.pdf",
            mimeType = "application/pdf", sizeBytes = payload.size.toLong(),
            sha256 = InMemoryAttachmentStore.sha256Hex(payload), storageLocator = locator,
            capturedOn = null, createdAt = 1L, updatedAt = 1L,
        )
        attachments.upsert(row)
        store.put(locator, ByteSource { payload.inputStream() })
        return row
    }

    private suspend fun writeArchive(): ByteArray {
        val set = export.run()
        val out = ByteArrayOutputStream()
        ArtifactsCodec.write(out, set.plan) { locator -> store.open(locator) }
        return out.toByteArray()
    }

    @Test fun thePlanListsEveryManagedRowExactlyOnceAndNoReferenceRow() = runTest {
        seed("att-1")
        seed("att-2")
        seed("att-3", mode = AttachmentMode.REFERENCE)
        val set = export.run()

        assertEquals(listOf("att-1", "att-2"), set.plan.entries.map { it.attachmentId.value })
        assertEquals("set-1", set.plan.backupSetId)
        assertEquals(BackupCodec.FORMAT_VERSION, set.plan.dataFormatVersion)
        assertEquals(1_726_000_000_000L, set.plan.createdAt)
        assertEquals(
            listOf("artifacts/att-1.pdf", "artifacts/att-2.pdf"),
            set.plan.entries.map { it.entryName },
        )
        assertEquals(
            listOf("assets/a1/att-1.pdf", "assets/a1/att-2.pdf"),
            set.plan.entries.map { it.locator },
        )
        // and the data archive carries the same set id and the same tallies
        val manifest = BackupCodec.decode(set.data).manifest
        assertEquals("set-1", manifest.backupSetId)
        assertEquals(2, manifest.artifactCount)
        assertEquals(1_726_000_000_000L, manifest.createdAt)
        assertEquals(3, manifest.counts.getValue("attachments"))
        assertEquals(1, uow.reads)   // one snapshot for the whole export
    }

    @Test fun restoreWritesTheBytesAtTheRowsOwnLocator() = runTest {
        val row = seed()
        val archive = writeArchive()
        store.files.clear()   // a fresh install: rows restored from the data archive, no bytes

        val report = restore.run(ByteArrayInputStream(archive), expectedSetId = "set-1")

        assertEquals(1, report.restored)
        assertEquals(0, report.skipped)
        assertEquals("set-1", report.backupSetId)
        assertTrue(report.missingRows.isEmpty())
        assertTrue(report.missingEntries.isEmpty())
        assertTrue(report.unexpectedEntries.isEmpty())
        assertContentEquals(payload, store.open(row.storageLocator)!!.use { it.readBytes() })
    }

    @Test fun aSetMismatchIsRefusedAndWritesNothing() = runTest {
        seed()
        val archive = writeArchive()
        store.files.clear()

        val boom = assertFailsWith<ArtifactsSetMismatch> {
            restore.run(ByteArrayInputStream(archive), expectedSetId = "set-9")
        }
        assertEquals("set-9", boom.expected)
        assertEquals("set-1", boom.found)
        assertTrue(store.files.isEmpty())

        // a null expectation is the "I have not restored data in this session" case: allowed
        assertEquals(1, restore.run(ByteArrayInputStream(archive), expectedSetId = null).restored)
    }

    @Test fun anEntryWithNoRowIsCountedAndNamed() = runTest {
        seed()
        val archive = writeArchive()
        attachments.deleteAll()
        store.files.clear()

        val report = restore.run(ByteArrayInputStream(archive), expectedSetId = "set-1")
        assertEquals(0, report.restored)
        assertEquals(1, report.skipped)
        assertEquals(listOf("att-1"), report.missingRows)
        assertTrue(store.files.isEmpty())
    }

    @Test fun anEntryWhoseShaDisagreesWithTheRowIsSkippedAndLeavesNoBytes() = runTest {
        val row = seed()
        val archive = writeArchive()
        store.files.clear()
        // the data archive restored a row claiming a different digest for these bytes
        attachments.upsert(row.copy(sha256 = "b".repeat(64)))

        val report = restore.run(ByteArrayInputStream(archive), expectedSetId = "set-1")
        assertEquals(0, report.restored)
        assertEquals(1, report.skipped)
        assertTrue(report.missingRows.isEmpty())
        assertFalse(store.exists(row.storageLocator))
    }

    @Test fun bytesThatDoNotHashToWhatTheManifestClaimsAreDeletedNotLeftBehind() = runTest {
        val row = seed()
        val archive = writeArchive()
        store.files.clear()
        // bit rot: the entry's bytes are not the bytes the manifest and the row both promise
        val entries = unzip(archive)
        entries[ArtifactsCodec.ENTRY_PREFIX + "att-1.pdf"] = "rotted".toByteArray()

        val report = restore.run(ByteArrayInputStream(rezip(entries)), expectedSetId = "set-1")
        assertEquals(0, report.restored)
        assertEquals(1, report.skipped)
        assertFalse(store.exists(row.storageLocator))
        assertTrue(store.files.isEmpty())
    }

    @Test fun aManifestEntryWithNoBytesIsNamedRatherThanIgnored() = runTest {
        seed()
        val archive = writeArchive()
        store.files.clear()
        val entries = unzip(archive)
        entries.remove(ArtifactsCodec.ENTRY_PREFIX + "att-1.pdf")

        val report = restore.run(ByteArrayInputStream(rezip(entries)), expectedSetId = "set-1")
        assertEquals(0, report.restored)
        assertEquals(0, report.skipped)
        assertEquals(listOf("artifacts/att-1.pdf"), report.missingEntries)
        assertTrue(store.files.isEmpty())
    }

    /**
     * The idempotence claim: a second restore of the same archive over an install whose bytes are
     * already right writes nothing, deletes nothing, and says so.
     */
    @Test fun aRowWhoseBytesAlreadyMatchIsCountedPresentAndNotWrittenAgain() = runTest {
        val row = seed()
        val archive = writeArchive()
        val deletesBefore = store.deletes

        val report = restore.run(ByteArrayInputStream(archive), expectedSetId = "set-1")

        assertEquals(0, report.restored)
        assertEquals(0, report.skipped)
        assertEquals(1, report.alreadyPresent)
        assertEquals(deletesBefore, store.deletes)
        assertContentEquals(payload, store.open(row.storageLocator)!!.use { it.readBytes() })
    }

    /**
     * C1: a damaged entry over good local bytes. The old code put the entry's bytes down first —
     * and a put replaces the document — so the digest check that followed deleted the damage *and*
     * the only good copy, leaving the row with no bytes anywhere. The local digest is checked
     * first now, so the good bytes are never opened for writing at all.
     */
    @Test fun aDamagedEntryOverGoodLocalBytesLeavesTheGoodBytesAlone() = runTest {
        val row = seed()
        val archive = writeArchive()
        val entries = unzip(archive)
        // bit rot in the archive only: the manifest still promises the row's own digest
        entries[ArtifactsCodec.ENTRY_PREFIX + "att-1.pdf"] = "rotted".toByteArray()
        val deletesBefore = store.deletes

        val report = restore.run(ByteArrayInputStream(rezip(entries)), expectedSetId = "set-1")

        assertEquals(0, report.restored)
        assertEquals(1, report.alreadyPresent)
        assertEquals(deletesBefore, store.deletes)
        assertTrue(store.exists(row.storageLocator))
        assertContentEquals(payload, store.open(row.storageLocator)!!.use { it.readBytes() })
    }

    @Test fun anUnreadableLocalFileIsReplacedFromTheArchiveInsteadOfAbortingTheRestore() = runTest {
        val row = seed()
        val archive = writeArchive()
        // the row's bytes are on the device but the document cannot be read past its header
        val unreadable = UnreadableOnFirstOpen(store, row.storageLocator)
        val restoreOver = RestoreArtifacts(attachments, FixedStorage(unreadable))

        val report = restoreOver.run(ByteArrayInputStream(archive), expectedSetId = "set-1")

        assertEquals(1, report.restored)
        assertEquals(0, report.alreadyPresent)
        assertEquals(0, report.skipped)
        assertContentEquals(payload, store.open(row.storageLocator)!!.use { it.readBytes() })
    }

    @Test fun aPutThatDiesMidCopyLeavesNoBytesBehindAndStillFailsTheRestore() = runTest {
        val row = seed()
        val archive = writeArchive()
        store.files.clear()
        store.failOnPut = row.storageLocator

        assertFailsWith<StoreIoException> {
            restore.run(ByteArrayInputStream(archive), expectedSetId = "set-1")
        }
        assertEquals(1, store.deletes)   // the locator was swept before the failure travelled on
        assertTrue(store.files.isEmpty())
    }

    @Test fun aSweepTheStoreRefusesStillCountsTheSkipAndKeepsGoing() = runTest {
        val first = seed("att-1")
        seed("att-2")
        val archive = writeArchive()
        store.files.clear()
        // att-1's bytes rotted, and the store will not delete them either
        val entries = unzip(archive)
        entries[ArtifactsCodec.ENTRY_PREFIX + "att-1.pdf"] = "rotted".toByteArray()
        val stubborn = RestoreArtifacts(
            attachments,
            FixedStorage(DeleteRefusingStore(store, first.storageLocator)),
        )

        val report = stubborn.run(ByteArrayInputStream(rezip(entries)), expectedSetId = "set-1")
        assertEquals(1, report.skipped)
        assertEquals(1, report.restored)   // att-2 still landed
        assertContentEquals(payload, store.open("assets/a1/att-2.pdf")!!.use { it.readBytes() })
    }

    @Test fun restoreWithNoStoreConfiguredIsAnIoFailureNotAPartialRestore() = runTest {
        seed()
        val archive = writeArchive()
        storage.state = StoreState.NotConfigured
        assertFailsWith<StoreIoException> {
            restore.run(ByteArrayInputStream(archive), expectedSetId = "set-1")
        }
    }

    @Test fun coversIsTrueOnlyWhenEveryPlannedRowLandedWithItsSize() {
        val plan = ArtifactsPlan(
            backupSetId = "set", dataFormatVersion = 5, createdAt = 1L,
            entries = listOf(
                ArtifactsPlanEntry(
                    attachmentId = AttachmentId("att-1"), entryName = "artifacts/att-1.pdf",
                    locator = "assets/a1/att-1.pdf", sha256 = "0".repeat(64), sizeBytes = 10L,
                    mimeType = "application/pdf",
                ),
                ArtifactsPlanEntry(
                    attachmentId = AttachmentId("att-2"), entryName = "artifacts/att-2.jpg",
                    locator = "assets/a1/att-2.jpg", sha256 = "1".repeat(64), sizeBytes = 5L,
                    mimeType = "image/jpeg",
                ),
            ),
        )
        assertTrue(ArtifactsWritten(2, 15L, emptyList(), emptyList()).covers(plan))
        assertFalse(ArtifactsWritten(1, 10L, listOf(AttachmentId("att-2")), emptyList()).covers(plan))
        assertFalse(ArtifactsWritten(1, 10L, emptyList(), listOf(AttachmentId("att-2"))).covers(plan))
        assertFalse(ArtifactsWritten(2, 14L, emptyList(), emptyList()).covers(plan))
    }

    @Test fun anInstallWithNoAttachmentsStillExportsAPlanAndAnArchive() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Hot tub", createdAt = 1L, updatedAt = 1L))
        val set = export.run()
        assertTrue(set.plan.entries.isEmpty())
        val archive = writeArchive()
        val report = restore.run(ByteArrayInputStream(archive), expectedSetId = "set-1")
        assertEquals(0, report.restored)
        assertEquals(0, report.skipped)
        assertEquals("set-1", report.backupSetId)
    }

    // --- store doubles -------------------------------------------------------------------------

    /** A store that refuses to delete one locator, so the best-effort sweep is really exercised. */
    private class DeleteRefusingStore(
        private val delegate: InMemoryAttachmentStore,
        private val refused: String,
    ) : AttachmentStore {
        override suspend fun put(locator: String, source: ByteSource) = delegate.put(locator, source)
        override suspend fun open(locator: String) = delegate.open(locator)
        override suspend fun exists(locator: String) = delegate.exists(locator)
        override suspend fun delete(locator: String) {
            if (locator == refused) throw StoreIoException("rigged delete failure at $locator")
            delegate.delete(locator)
        }
    }

    /** Hands back a stream that dies on its first read, once; every later open is the real one. */
    private class UnreadableOnFirstOpen(
        private val delegate: InMemoryAttachmentStore,
        private val locator: String,
    ) : AttachmentStore {
        private var armed = true
        override suspend fun put(locator: String, source: ByteSource) = delegate.put(locator, source)
        override suspend fun open(locator: String): java.io.InputStream? {
            if (locator == this.locator && armed) {
                armed = false
                return object : java.io.InputStream() {
                    override fun read(): Int = throw java.io.IOException("rigged unreadable document")
                }
            }
            return delegate.open(locator)
        }
        override suspend fun exists(locator: String) = delegate.exists(locator)
        override suspend fun delete(locator: String) = delegate.delete(locator)
    }

    private class FixedStorage(private val store: AttachmentStore) : AttachmentStorage {
        override fun state() = StoreState.Ready("Attachments", "com.example.provider")
        override fun store() = store
    }

    // --- zip helpers, mirroring BackupCodecTest's pair ------------------------------------------

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry: ZipEntry = zin.nextEntry ?: break
                out[entry.name] = zin.readBytes()
                zin.closeEntry()
            }
        }
        return out
    }

    private fun rezip(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, payload) ->
                val e = ZipEntry(name)
                e.time = 0L
                zos.putNextEntry(e)
                zos.write(payload)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
