package com.loosecannon.servicetag.ui.backup

import com.loosecannon.servicetag.backup.BackupSetNames
import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupSetIncomplete
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.ports.BackupIO
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.StoreIsEmpty
import com.loosecannon.servicetag.testing.FakeGraph
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The backup set, against a Room-backed [FakeGraph]. The destination is a [BackupSetSink] rather
 * than a folder precisely so these can run without SAF: one sink keeps both files, another refuses
 * one of the two, a third breaks half-way through a write the way a full disk does — and the rule
 * the owner asked for has to hold in every case.
 *
 * That rule: a backup set is a *set*. Both files land and the artifacts archive covers the plan,
 * or nothing is saved and the nudge does not move.
 *
 * `messages` has no replay by design, so a test that wants a line subscribes before the call that
 * produces it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = BackupViewModel(
        graph.exportBackupSet, graph.importBackupReplace, graph.restoreArtifacts,
        StoreIsEmpty(graph.assets, graph.tags, graph.events, graph.attachments, graph.links, graph.categories),
        graph.attachmentStorage, graph.prefs, graph.clock,
    )

    // --- doubles ---------------------------------------------------------------------------

    /** A document the user picked that keeps what it is given. */
    private class MemoryIO(private val bytes: ByteArray) : BackupIO {
        override suspend fun read(): ByteArray = bytes
        override suspend fun openStream(): InputStream = ByteArrayInputStream(bytes)
    }

    /**
     * A sink that keeps what it was handed, and can be told to fail on one of the two names.
     * [refuseDeleteOfPrefix] is the provider that will not take a file back: `delete` answers no
     * and the file stays, which is the one case where "Nothing was saved" would be a lie.
     */
    private class RecordingSink(
        private val failOnPrefix: String? = null,
        private val refuseDeleteOfPrefix: String? = null,
    ) : BackupSetSink {
        val files = LinkedHashMap<String, ByteArray>()
        val deleted = mutableListOf<String>()

        override suspend fun write(name: String, body: suspend (OutputStream) -> Unit): String {
            if (failOnPrefix != null && name.startsWith(failOnPrefix)) {
                error("rigged write failure for $name")
            }
            val out = ByteArrayOutputStream()
            body(out)
            files[name] = out.toByteArray()
            return name
        }

        override suspend fun delete(handle: String): Boolean {
            if (refuseDeleteOfPrefix != null && handle.startsWith(refuseDeleteOfPrefix)) return false
            deleted += handle
            files.remove(handle)
            return true
        }
    }

    /**
     * `SafBackupSetWriter`'s own shape: the document exists from the moment `write` is called, and
     * a body that throws half-way takes that document with it before the failure travels on.
     */
    private class PartialWriteSink(private val failOnPrefix: String) : BackupSetSink {
        val files = LinkedHashMap<String, ByteArray>()
        val deleted = mutableListOf<String>()

        override suspend fun write(name: String, body: suspend (OutputStream) -> Unit): String {
            files[name] = ByteArray(0)          // created before a single byte is written
            val out = ByteArrayOutputStream()
            try {
                body(if (name.startsWith(failOnPrefix)) BreakingStream(out, after = 64) else out)
            } catch (t: Throwable) {
                // The invariant the SAF writer keeps: its own half-written document goes away.
                files.remove(name)
                deleted += name
                throw t
            }
            files[name] = out.toByteArray()
            return name
        }

        override suspend fun delete(handle: String): Boolean {
            deleted += handle
            files.remove(handle)
            return true
        }
    }

    /**
     * A sink that can be held open inside the artifacts write, so a test can cancel an export
     * while it is in flight.
     *
     * `delete` suspends for real — `SafBackupSetWriter.delete` is a `withContext(Dispatchers.IO)`
     * block — and that is the whole point: a cleanup that runs on an already-cancelled coroutine
     * never gets past the first suspension point, so the file it was meant to take back survives.
     * The other fakes here have no suspension point at all, which is why they cannot see it.
     */
    private class SuspendingSink(private val holdOnPrefix: String) : BackupSetSink {
        val files = LinkedHashMap<String, ByteArray>()
        val deleted = mutableListOf<String>()

        /** Completes once the held write has been reached, so a test can stop guessing. */
        val holding = CompletableDeferred<Unit>()

        /** Nothing ever completes this: the test cancels instead. */
        private val release = CompletableDeferred<Unit>()

        override suspend fun write(name: String, body: suspend (OutputStream) -> Unit): String {
            files[name] = ByteArray(0)          // created before a single byte is written
            val out = ByteArrayOutputStream()
            try {
                if (name.startsWith(holdOnPrefix)) {
                    holding.complete(Unit)
                    release.await()
                }
                body(out)
            } catch (t: Throwable) {
                // The SAF writer's `document.delete()` is a blocking call inside its own
                // `withContext`, so it runs even when the failure is a cancellation.
                files.remove(name)
                deleted += name
                throw t
            }
            files[name] = out.toByteArray()
            return name
        }

        override suspend fun delete(handle: String): Boolean =
            withContext(Dispatchers.IO) {
                deleted += handle
                files.remove(handle)
                true
            }
    }

    /** Takes [after] bytes and then fails, the way a disk that filled up does. */
    private class BreakingStream(
        private val sink: OutputStream,
        private val after: Int,
    ) : OutputStream() {
        private var written = 0
        override fun write(b: Int) {
            if (written++ >= after) throw IOException("rigged mid-write failure")
            sink.write(b)
        }
    }

    // --- fixtures --------------------------------------------------------------------------

    private val stamped = Regex("""^ServiceTag-(data|artifacts)-\d{8}-\d{6}\.zip$""")

    private val payload = ByteArray(5_000) { (it % 251).toByte() }

    /** Adds a file the way the picker would, and returns the row. */
    private suspend fun addFile(asset: AssetId, name: String = "Guide.pdf"): Attachment {
        val result = graph.addAttachment.run(
            AttachmentOwner.OfAsset(asset),
            AddAttachmentCommand(
                displayName = name,
                mimeType = "application/pdf",
                sizeBytes = payload.size.toLong(),
            ),
            ByteSource { ByteArrayInputStream(payload) },
        )
        return (result as AttachmentResult.Ok).value
    }

    private fun entryNames(zip: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                names += entry.name
                zin.closeEntry()
            }
        }
        return names
    }

    /** The set id an artifacts archive carries, read out of its manifest. */
    private fun setIdOf(artifacts: ByteArray): String {
        val manifest = ZipInputStream(ByteArrayInputStream(artifacts)).use { zin ->
            val first = zin.nextEntry!!
            assertEquals(ArtifactsCodec.MANIFEST_ENTRY, first.name)
            String(zin.readBytes(), Charsets.UTF_8)
        }
        return Regex(""""backupSetId"\s*:\s*"([^"]*)"""").find(manifest)!!.groupValues[1]
    }

    /**
     * The same artifacts archive with its payload entries dropped and one entry the manifest never
     * named put in their place: both kinds of damage `ArtifactsReadReport` can see, in one file.
     */
    private fun damagedArtifacts(artifacts: ByteArray): ByteArray {
        val manifest = ZipInputStream(ByteArrayInputStream(artifacts)).use { zin ->
            zin.nextEntry
            zin.readBytes()
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry(ArtifactsCodec.MANIFEST_ENTRY))
            zos.write(manifest)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(ArtifactsCodec.ENTRY_PREFIX + "stranger.bin"))
            zos.write(ByteArray(8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * The same data archive as a format-4 file: the version goes back, the four fields format 4
     * never had come out of the manifest, and format 9's `assetCategories` comes out of `data.json`
     * — a format ≤8 file never carried one, and the codec refuses one that does (#74). The
     * manifest's `dataSha256` is resealed over the edited `data.json`.
     */
    private fun asFormatFour(data: ByteArray): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes()
            }
        }
        val tree = Json.parseToJsonElement(String(entries.getValue("data.json"), Charsets.UTF_8)).jsonObject
        val dataBytes = Json.encodeToString(JsonObject.serializer(), JsonObject(tree - "assetCategories"))
            .toByteArray(Charsets.UTF_8)
        val sealedWith = MessageDigest.getInstance("SHA-256").digest(dataBytes).joinToString("") { "%02x".format(it) }
        val dropped = setOf("backupSetId", "artifactFormatVersion", "artifactCount", "artifactBytes")
        val manifest = String(entries.getValue("manifest.json"), Charsets.UTF_8)
            .lines()
            .filterNot { line -> dropped.any { line.trimStart().startsWith("\"$it\"") } }
            .joinToString("\n")
            .replace(
                Regex(""""formatVersion"\s*:\s*${BackupCodec.FORMAT_VERSION}"""),
                "\"formatVersion\": 4",
            )
            .replace(Regex(""""dataSha256"\s*:\s*"[0-9a-f]{64}""""), "\"dataSha256\": \"$sealedWith\"")
            .replace(Regex(""",(\s*})"""), "$1") // the comma the dropped fields left behind
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("data.json"))
            zos.write(dataBytes)
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    // --- export ----------------------------------------------------------------------------

    @Test fun exportWritesTwoStampedFilesAndMarksTheBackupOnce() = runTest {
        graph.createAsset.run("Pool pump", "Water")
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()

        val said = async(Dispatchers.Main) { vm.messages.first() }
        val message = vm.exportSet(sink).getOrThrow()
        vm.exportSetTo(RecordingSink())

        val stamp = BackupSetNames.stamp(7_000L)
        assertEquals(
            listOf(BackupSetNames.data(stamp), BackupSetNames.artifacts(stamp)),
            sink.files.keys.toList(),
        )
        assertEquals(emptyList<String>(), sink.files.keys.filterNot { stamped.matches(it) })
        assertTrue("both archives must carry bytes", sink.files.values.all { it.isNotEmpty() })
        assertEquals(emptyList<String>(), sink.deleted)
        assertEquals(BackupSetNames.data(stamp) + " + " + BackupSetNames.artifacts(stamp), message)
        assertEquals("Exported as $message", said.await())
        assertEquals(7_000L, graph.prefs.lastBackupAt)
        assertEquals(7_000L, vm.state.value.lastBackupAt)
    }

    @Test fun anInstallWithNoAttachmentsStillWritesBothFiles() = runTest {
        graph.createAsset.run("Pool pump", "Water")
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()

        vm.exportSet(sink).getOrThrow()

        val artifacts = sink.files.getValue(BackupSetNames.artifacts(BackupSetNames.stamp(7_000L)))
        assertEquals(listOf(ArtifactsCodec.MANIFEST_ENTRY), entryNames(artifacts))
    }

    @Test fun aFailedDataWriteWritesNothingAndLeavesTheNudgeAlone() = runTest {
        graph.createAsset.run("Pool pump", "Water")
        val vm = viewModel()
        val sink = RecordingSink(failOnPrefix = "ServiceTag-data")

        assertTrue(vm.exportSet(sink).isFailure)

        assertEquals(emptyList<String>(), sink.files.keys.toList())
        assertNull(graph.prefs.lastBackupAt)
        assertNull(vm.state.value.lastBackupAt)
    }

    @Test fun aFailedArtifactsWriteRemovesTheDataFileAndLeavesTheNudgeAlone() = runTest {
        graph.createAsset.run("Pool pump", "Water")
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink(failOnPrefix = "ServiceTag-artifacts")

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.exportSetTo(sink)
        // The line arrives when the export is over, so waiting for it is how the assertions
        // below know they are looking at the end state and not at a step on the way.
        assertEquals(
            "Export failed: the files archive could not be written. Nothing was saved.",
            said.await(),
        )

        val stamp = BackupSetNames.stamp(7_000L)
        assertEquals(emptyList<String>(), sink.files.keys.toList())
        assertEquals(listOf(BackupSetNames.data(stamp)), sink.deleted)
        assertNull(graph.prefs.lastBackupAt)
        assertNull(vm.state.value.lastBackupAt)
    }

    @Test fun missingManagedAttachmentMakesExportFailAndLeavesNoArchives() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        val row = addFile(pump.id)
        // The row still claims its bytes; the folder no longer has them.
        graph.attachmentStorage.store.files.clear()
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()

        val failure = vm.exportSet(sink).exceptionOrNull()

        assertTrue("expected BackupSetIncomplete, got $failure", failure is BackupSetIncomplete)
        failure as BackupSetIncomplete
        assertEquals(listOf(row.id), failure.missing)
        assertEquals(emptyList<AttachmentId>(), failure.mismatched)
        val stamp = BackupSetNames.stamp(7_000L)
        assertEquals(emptyList<String>(), sink.files.keys.toList())
        assertEquals(
            listOf(BackupSetNames.artifacts(stamp), BackupSetNames.data(stamp)),
            sink.deleted,
        )
        assertNull(graph.prefs.lastBackupAt)
        assertNull(vm.state.value.lastBackupAt)

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.exportSetTo(RecordingSink())
        assertEquals("Backup not saved: 1 attachment file is missing", said.await())
    }

    @Test fun mismatchedManagedAttachmentMakesExportFailAndLeavesNoArchives() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        val row = addFile(pump.id)
        // Someone edited the file in the folder: same length, different bytes.
        graph.attachmentStorage.store.files[row.storageLocator] = ByteArray(payload.size) { 1 }
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()

        val failure = vm.exportSet(sink).exceptionOrNull()

        assertTrue("expected BackupSetIncomplete, got $failure", failure is BackupSetIncomplete)
        failure as BackupSetIncomplete
        assertEquals(emptyList<AttachmentId>(), failure.missing)
        assertEquals(listOf(row.id), failure.mismatched)
        val stamp = BackupSetNames.stamp(7_000L)
        assertEquals(emptyList<String>(), sink.files.keys.toList())
        assertEquals(
            listOf(BackupSetNames.artifacts(stamp), BackupSetNames.data(stamp)),
            sink.deleted,
        )
        assertNull(graph.prefs.lastBackupAt)
        assertNull(vm.state.value.lastBackupAt)

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.exportSetTo(RecordingSink())
        assertEquals(
            "Backup not saved: 1 attachment file has changed since it was added",
            said.await(),
        )
    }

    /**
     * I2: rows name bytes and there is no folder to read them from. Every entry would land in
     * `missing` and the owner would be told "1 attachment file is missing" — sending them to look
     * for a file when the thing that is missing is the folder. The refusal comes before the first
     * write, so there is nothing to clean up either.
     */
    @Test fun exportingWithNoAttachmentFolderSaysSoAndWritesNothing() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        addFile(pump.id)
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()
        graph.attachmentStorage.state = StoreState.NotConfigured

        val failure = vm.exportSet(sink).exceptionOrNull()

        assertTrue("expected NoAttachmentFolder, got $failure", failure is NoAttachmentFolder)
        assertEquals("Choose an attachment folder in Settings first", failure!!.message)
        assertEquals(emptyList<String>(), sink.files.keys.toList())
        assertEquals(emptyList<String>(), sink.deleted)
        assertNull(graph.prefs.lastBackupAt)
        assertNull(vm.state.value.lastBackupAt)

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.exportSetTo(RecordingSink())
        assertEquals("Choose an attachment folder in Settings first", said.await())
    }

    /** The other side of that boundary: nothing to open, so nothing to refuse. */
    @Test fun exportingWithNoRowsAndNoFolderStillWritesTheSet() = runTest {
        graph.createAsset.run("Pool pump", "Water")
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()
        graph.attachmentStorage.state = StoreState.NotConfigured

        vm.exportSet(sink).getOrThrow()

        val stamp = BackupSetNames.stamp(7_000L)
        assertEquals(
            listOf(BackupSetNames.data(stamp), BackupSetNames.artifacts(stamp)),
            sink.files.keys.toList(),
        )
        assertEquals(7_000L, graph.prefs.lastBackupAt)
    }

    /**
     * I3: the provider refuses to take the data file back. A complete, importable data archive is
     * still in the folder, so "Nothing was saved" is false — the line names the file instead.
     */
    @Test fun aDataFileTheSinkWillNotTakeBackIsNamedInTheFailure() = runTest {
        graph.createAsset.run("Pool pump", "Water")
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink(
            failOnPrefix = "ServiceTag-artifacts",
            refuseDeleteOfPrefix = "ServiceTag-data",
        )
        val stamp = BackupSetNames.stamp(7_000L)

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.exportSetTo(sink)

        assertEquals(
            "Export failed: the files archive could not be written. " +
                "Could not remove ${BackupSetNames.data(stamp)} — delete it yourself.",
            said.await(),
        )
        assertEquals(listOf(BackupSetNames.data(stamp)), sink.files.keys.toList())
        assertEquals(emptyList<String>(), sink.deleted)
        assertNull(graph.prefs.lastBackupAt)
        assertNull(vm.state.value.lastBackupAt)
    }

    @Test fun midArtifactsWriteRemovesPartialArtifactsAndDataZip() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        addFile(pump.id)
        graph.now = 7_000L
        val vm = viewModel()
        val sink = PartialWriteSink(failOnPrefix = "ServiceTag-artifacts")

        assertTrue(vm.exportSet(sink).isFailure)

        val stamp = BackupSetNames.stamp(7_000L)
        assertEquals(emptyList<String>(), sink.files.keys.toList())
        // The sink dropped its own half-written artifacts document; the export dropped the data one.
        assertEquals(
            listOf(BackupSetNames.artifacts(stamp), BackupSetNames.data(stamp)),
            sink.deleted,
        )
        assertNull(graph.prefs.lastBackupAt)
        assertNull(vm.state.value.lastBackupAt)
    }

    @Test fun cancellingMidExportStillTakesBothFilesBack() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        addFile(pump.id)
        graph.now = 7_000L
        val vm = viewModel()
        val sink = SuspendingSink(holdOnPrefix = "ServiceTag-artifacts")

        val export = launch(Dispatchers.Main) { vm.exportSet(sink) }
        sink.holding.await()            // the data file is written and the artifacts one is open
        export.cancelAndJoin()

        val stamp = BackupSetNames.stamp(7_000L)
        // The sink dropped the artifacts document it had created; the export's own cleanup ran
        // under `NonCancellable`, which is the only way it could reach a suspending `delete`.
        assertEquals(
            listOf(BackupSetNames.artifacts(stamp), BackupSetNames.data(stamp)),
            sink.deleted,
        )
        assertEquals(emptyList<String>(), sink.files.keys.toList())
        assertNull(graph.prefs.lastBackupAt)
        assertNull(vm.state.value.lastBackupAt)
    }

    // --- restore ---------------------------------------------------------------------------

    @Test fun restoringDataReplacesEverythingFromTheDataArchive() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()
        vm.exportSet(sink).getOrThrow()
        val data = MemoryIO(sink.files.getValue(BackupSetNames.data(BackupSetNames.stamp(7_000L))))

        // Everything the backup knows about is gone before it is read back.
        graph.uow.write {
            graph.tags.deleteAll()
            graph.links.deleteAll()
            graph.assets.deleteAll()
        }
        assertEquals(emptyList<Asset>(), graph.assets.all())

        val report = vm.restoreData(data).getOrThrow()

        assertEquals(1, report.assets)
        assertEquals(0, report.tags)
        assertEquals(0, report.links)
        assertEquals(BackupCodec.FORMAT_VERSION, report.formatVersion)
        assertEquals(listOf(pump.id), graph.assets.all().map(Asset::id))
    }

    @Test fun restoringDataRemembersTheSetIdAndMentionsTheAttachmentsItListed() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        addFile(pump.id)
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()
        vm.exportSet(sink).getOrThrow()
        val stamp = BackupSetNames.stamp(7_000L)
        val setId = setIdOf(sink.files.getValue(BackupSetNames.artifacts(stamp)))
        val data = MemoryIO(sink.files.getValue(BackupSetNames.data(stamp)))

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.restoreDataFrom(data)
        val line = said.await()

        assertEquals(setId, graph.prefs.lastRestoredBackupSetId)
        assertEquals(setId, vm.state.value.lastRestoredBackupSetId)
        assertTrue(
            "the line must point at the second half: $line",
            line.contains("1 attachments listed; restore the files archive to get their contents"),
        )
    }

    @Test fun restoringTheFilesArchiveOfTheSameSetRestoresTheBytes() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        val row = addFile(pump.id)
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()
        vm.exportSet(sink).getOrThrow()
        val stamp = BackupSetNames.stamp(7_000L)

        // A new phone: the rows arrive from the data archive, the bytes are not here yet.
        graph.attachmentStorage.store.files.clear()
        vm.restoreData(MemoryIO(sink.files.getValue(BackupSetNames.data(stamp)))).getOrThrow()
        assertTrue(graph.attachmentStorage.store.files.isEmpty())

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.restoreFilesFrom(MemoryIO(sink.files.getValue(BackupSetNames.artifacts(stamp))))

        assertEquals("Restored 1 files, skipped 0", said.await())
        assertArrayEquals(payload, graph.attachmentStorage.store.files.getValue(row.storageLocator))
    }

    /**
     * C1, as the owner sees it: the same files archive restored twice. The second run finds every
     * row's bytes already right, writes nothing over them, and says so instead of reporting a
     * restore of nothing.
     */
    @Test fun restoringTheSameFilesArchiveTwiceKeepsTheBytesAndSaysTheyArePresent() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        val row = addFile(pump.id)
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()
        vm.exportSet(sink).getOrThrow()
        val stamp = BackupSetNames.stamp(7_000L)
        val artifacts = sink.files.getValue(BackupSetNames.artifacts(stamp))

        graph.attachmentStorage.store.files.clear()
        vm.restoreData(MemoryIO(sink.files.getValue(BackupSetNames.data(stamp)))).getOrThrow()
        assertEquals(1, vm.restoreFiles(MemoryIO(artifacts)).getOrThrow().restored)

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.restoreFilesFrom(MemoryIO(artifacts))

        assertEquals("Restored 0 files, skipped 0; 1 already present", said.await())
        assertArrayEquals(payload, graph.attachmentStorage.store.files.getValue(row.storageLocator))
    }

    @Test fun restoringTheFilesArchiveOfAnotherSetIsRefusedByTheTwoShortIds() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        addFile(pump.id)
        graph.now = 7_000L
        val vm = viewModel()
        val stamp = BackupSetNames.stamp(7_000L)
        val first = RecordingSink().also { vm.exportSet(it).getOrThrow() }
        val second = RecordingSink().also { vm.exportSet(it).getOrThrow() }
        val firstId = setIdOf(first.files.getValue(BackupSetNames.artifacts(stamp)))
        val secondId = setIdOf(second.files.getValue(BackupSetNames.artifacts(stamp)))

        graph.attachmentStorage.store.files.clear()
        vm.restoreData(MemoryIO(second.files.getValue(BackupSetNames.data(stamp)))).getOrThrow()

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.restoreFilesFrom(MemoryIO(first.files.getValue(BackupSetNames.artifacts(stamp))))

        assertEquals(
            "Those files belong to backup set ${firstId.take(8)}, not ${secondId.take(8)}",
            said.await(),
        )
        assertTrue(
            "the refused archive must add nothing",
            graph.attachmentStorage.store.files.isEmpty(),
        )
    }

    @Test fun restoringFilesWithNoFolderConfiguredSaysToChooseOne() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        addFile(pump.id)
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()
        vm.exportSet(sink).getOrThrow()
        val artifacts = sink.files.getValue(BackupSetNames.artifacts(BackupSetNames.stamp(7_000L)))

        graph.attachmentStorage.store.files.clear()
        graph.attachmentStorage.state = StoreState.NotConfigured

        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.restoreFilesFrom(MemoryIO(artifacts))

        assertEquals("Choose an attachment folder in Settings first", said.await())
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    @Test fun aDamagedFilesArchiveSaysWhatItDidNotCarryAndWhatItCarriedUnasked() = runTest {
        val pump = graph.createAsset.run("Pool pump", "Water")
        addFile(pump.id)
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()
        vm.exportSet(sink).getOrThrow()
        val stamp = BackupSetNames.stamp(7_000L)

        graph.attachmentStorage.store.files.clear()
        vm.restoreData(MemoryIO(sink.files.getValue(BackupSetNames.data(stamp)))).getOrThrow()

        val damaged = damagedArtifacts(sink.files.getValue(BackupSetNames.artifacts(stamp)))
        val said = async(Dispatchers.Main) { vm.messages.first() }
        vm.restoreFilesFrom(MemoryIO(damaged))

        // Restored 0 and skipped 0 on their own would read as a clean restore of nothing.
        assertEquals(
            "Restored 0 files, skipped 0" +
                "; 1 listed files were not in the archive" +
                "; 1 files in the archive were not listed",
            said.await(),
        )
        assertTrue(graph.attachmentStorage.store.files.isEmpty())
    }

    @Test fun aFormatFourFileStillImportsThroughRestoreData() = runTest {
        graph.createAsset.run("Pool pump", "Water")
        graph.now = 7_000L
        val vm = viewModel()
        val sink = RecordingSink()
        vm.exportSet(sink).getOrThrow()
        val data = sink.files.getValue(BackupSetNames.data(BackupSetNames.stamp(7_000L)))

        val report = vm.restoreData(MemoryIO(asFormatFour(data))).getOrThrow()

        assertEquals(4, report.formatVersion)
        assertEquals(1, report.assets)
        assertEquals(0, report.attachments)
        assertEquals("", report.lastRestoredBackupSetId)
        // Nothing to pair a files archive with, so nothing is remembered.
        assertNull(graph.prefs.lastRestoredBackupSetId)
        assertNull(vm.state.value.lastRestoredBackupSetId)
    }

    /**
     * The file name is not part of the format: the importer is handed bytes (`BackupIO.read()`),
     * and `BackupSetNames` is only ever consulted on the export side (arch §7.3). This is the
     * regression guard for the prefix change — an archive named with the retired `noteNFC-*`
     * prefix restores, because nothing on the import path reads the name at all.
     */
    @Test fun aPreservedRetiredPrefixArchiveStillImports() = runTest {
        val vm = viewModel()
        val sink = RecordingSink()
        vm.exportSet(sink).getOrThrow()
        val bytes = sink.files.entries.single { it.key.startsWith("ServiceTag-data-") }.value
        val assetsBefore = graph.assets.all().size

        // the very same bytes, as they sit in the owner's folder under the retired name
        val preserved = mapOf("noteNFC-data-20260915-101010.zip" to bytes)
        val report = vm.restoreData(MemoryIO(preserved.getValue("noteNFC-data-20260915-101010.zip"))).getOrThrow()

        // a wipe-and-load of our own export puts back exactly what was there, and the set id is
        // the one we exported: the file's name reached nothing at all.
        assertEquals(assetsBefore, graph.assets.all().size)
        assertNotNull(report.lastRestoredBackupSetId)
        assertEquals(report.lastRestoredBackupSetId, vm.state.value.lastRestoredBackupSetId)
    }

    // --- the restore prompt (#40) -----------------------------------------------------------

    /**
     * #40 — a phone with nothing on it. The Backup screen asks this before it raises a dialog, and
     * the answer is what decides between the typed `REPLACE` confirmation and a plain one. Nothing
     * is seeded here, so the fixture *is* the empty install.
     */
    @Test fun anUntouchedInstallReportsAnEmptyStore() = runTest {
        assertTrue(viewModel().isStoreEmpty())
    }

    /**
     * #40, the other branch — one asset is enough. The typed word exists to make the owner accept
     * losing what is here, and now there is something here to lose.
     */
    @Test fun oneAssetIsEnoughToReportANonEmptyStore() = runTest {
        graph.createAsset.run("Pool pump", "Water")
        assertFalse(viewModel().isStoreEmpty())
    }
}
