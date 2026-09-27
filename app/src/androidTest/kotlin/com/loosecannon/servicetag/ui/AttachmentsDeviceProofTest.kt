package com.loosecannon.servicetag.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.attachments.DocumentTreeRoot
import com.loosecannon.servicetag.backup.SafBackupIO
import com.loosecannon.servicetag.backup.SafBackupSetWriter
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.ui.backup.BackupViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4A's device proof (spec §12), driven through the real screens on the **emulator**. Same
 * shape as `AssetModelDeviceProofTest`: an empty Compose rule, a destructive `@Before`, one test
 * per scenario, each cold-starting the screen it needs through the `servicetag://asset/<id>` deep
 * link a tag tap takes.
 *
 * What the suite deliberately does *not* claim:
 *
 * - **The SAF picker is never driven.** There is no way to tap a system file chooser reliably, and
 *   spec §5.3 provides the seam instead: `@Before` points `AppGraph.attachmentRootResolver` at a
 *   `DocumentFile.fromFile` tree under `getExternalFilesDir`, `attachmentGrantCheck` at `{ true }`,
 *   and writes `AppPrefs.attachmentTreeUri`. Everything downstream of the picker — the store, the
 *   rows, the thumbnails, the viewer intent, the export, the restore — is the production path.
 * - **Files arrive from a test-provided source, not from a document picker.** A scenario seeds a
 *   file through `graph.addAttachment.run(...)` with a `ByteSource` over bytes built in-process,
 *   which is exactly what `AttachmentPickers` hands the use case. The picker's own mapping is
 *   covered by the §10 procedure, on real files, through the shipped UI.
 * - **`ACTION_VIEW` is asserted as an intent, not as a viewer.** The launch path is proved by
 *   `viewUri` being non-null; the tap itself is proved by the "No app can open this file" line,
 *   which is the branch the `<queries>` entry exists for. On this emulator that branch is the one
 *   a tap always takes, because the test tree hands back `file://` URIs and the manifest's
 *   `<queries>` entry covers `content://` only — so no installed viewer is even visible to the
 *   resolve. (The emulator *does* ship a PDF viewer, contrary to the brief's guess.)
 * - **The folder pick and the archive pick on the Backup screen are seams too.** The export runs
 *   through the production `SafBackupSetWriter` over a `fromFile` tree and the restores through the
 *   production `SafBackupIO` over a `file://` document; the Backup screen is then driven and
 *   asserted, but the system chooser in front of it is not.
 */
class AttachmentsDeviceProofTest {

    @get:Rule val rule = createEmptyComposeRule()

    /** The tree the seams point at for this test. Wiped every time: no bytes survive a scenario. */
    private lateinit var tree: File

    @Before fun freshInstallWithATree() {
        clearInstall()
        tree = useFileBackedTree()
    }

    // ---------------------------------------------------------------- scenario (a)

    /** With no folder configured the section says so and offers the way to Settings. */
    @Test fun withNoFolderTheDocumentsSectionPointsAtSettings() {
        app.graph.prefs.attachmentTreeUri = null
        val id = newAsset("Well pump")

        openAsset(id).use {
            rule.awaitText("DOCUMENTS")
            rule.onNodeWithText("ATTACHMENT STORAGE").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Attachment storage not set up").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Choose a folder in Settings").performScrollTo().assertIsDisplayed()
            // Both add actions are hidden rather than disabled: there is nowhere for bytes to go.
            rule.onAllNodesWithText("Add file").assertCountEquals(0)
            rule.onAllNodesWithText("Take photo").assertCountEquals(0)
            rule.onAllNodesWithText("No documents yet").assertCountEquals(0)

            // And the offer is a real one: it lands on the section that fixes it.
            //
            // The barrier is "Choose folder", which only Settings has: waiting on
            // "ATTACHMENT STORAGE" would be satisfied by the status block we are still looking at
            // — both screens render that headline — so it would return before Settings arrived and
            // prove nothing. Waiting for the status block's own detail line to go as well means
            // the push has finished, so each assertion below is about one node on one screen.
            rule.onNodeWithText("Open settings").performScrollTo().performClick()
            rule.awaitText("Choose folder")
            rule.awaitGone("Choose a folder in Settings")
            rule.onNodeWithText("Settings").assertIsDisplayed()
            rule.onNodeWithText("ATTACHMENT STORAGE").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Not set").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Choose folder").performScrollTo().assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- scenario (b)

    /** A file added from a test-provided source becomes a row, and its bytes are in the tree. */
    @Test fun anAddedFileIsARowAndAFileInTheTree() {
        val id = newAsset("Well pump")
        val added = addFile(AttachmentOwner.OfAsset(AssetId(id)), MANUAL_NAME, PDF_MIME, pdfBytes())

        // The bytes are where the row says they are, byte for byte.
        val stored = soleFileIn(File(tree, AttachmentLocator.dirFor(added.owner)))
        assertTrue("stored as ${stored.name}", stored.name.startsWith("${added.id.value}."))
        assertArrayEquals(pdfBytes(), stored.readBytes())
        // The launch path exists even though nothing on this device can follow it.
        assertNotNull(app.graph.attachmentStorage.viewUri(added.storageLocator))

        openAsset(id).use {
            rule.awaitText("DOCUMENTS · 1")
            rule.onNodeWithText("DOCUMENTS · 1").performScrollTo().assertIsDisplayed()
            rule.onNode(hasText(MANUAL_NAME) and hasText("Document · 2.0 KB · ${today()}"))
                .performScrollTo()
                .assertIsDisplayed()
            rule.onNodeWithText("Add file").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Take photo").performScrollTo().assertIsDisplayed()
            rule.onAllNodesWithText("Attachment storage not set up").assertCountEquals(0)
        }
    }

    /**
     * The other half of the row's tap: nothing on this device can open the document, so the row
     * says so instead of throwing an intent at nothing (spec §8.2).
     */
    @Test fun tappingARowNothingCanOpenSaysSo() {
        val id = newAsset("Well pump")
        addFile(AttachmentOwner.OfAsset(AssetId(id)), MANUAL_NAME, PDF_MIME, pdfBytes())

        openAsset(id).use {
            rule.awaitText("DOCUMENTS · 1")
            rule.onNodeWithText(MANUAL_NAME).performScrollTo().performClick()
            rule.awaitText("No app can open this file")
            rule.onNodeWithText("No app can open this file").assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- scenario (c)

    /** The overflow sheet renames and re-kinds, and the row redraws from the flow. */
    @Test fun theSheetRenamesAndReKindsTheRow() {
        val id = newAsset("Well pump")
        val added = addFile(AttachmentOwner.OfAsset(AssetId(id)), MANUAL_NAME, PDF_MIME, pdfBytes())
        val ownerDir = File(tree, AttachmentLocator.dirFor(added.owner))
        val storedName = soleFileIn(ownerDir).name

        openAsset(id).use {
            rule.awaitText("DOCUMENTS · 1")
            rule.openRowSheet(MANUAL_NAME)
            // #67 (R67-11): an asset's file is offered the Role section, through the real section.
            rule.onNodeWithText("ROLE").assertExists()
            rule.onNodeWithText("No role").assertExists()

            rule.field("Name").performTextReplacement(RENAMED)
            rule.onNodeWithText("Manual").performClick()
            rule.onNodeWithText("Save").performClick()

            rule.awaitText(RENAMED)
            rule.onNode(hasText(RENAMED) and hasText("Manual · 2.0 KB · ${today()}"))
                .performScrollTo()
                .assertIsDisplayed()
            rule.onAllNodesWithText(MANUAL_NAME).assertCountEquals(0)
            // The sheet closed on the save that landed.
            rule.onAllNodesWithText("KIND").assertCountEquals(0)
        }

        // A rename never moves bytes (spec §4): same document, same locator, same content.
        assertEquals(storedName, soleFileIn(ownerDir).name)
        assertArrayEquals(pdfBytes(), soleFileIn(ownerDir).readBytes())
        val row = runBlocking { app.graph.attachments.get(added.id)!! }
        assertEquals(RENAMED, row.displayName)
        assertEquals(added.storageLocator, row.storageLocator)
    }

    // ---------------------------------------------------------------- scenario (d)

    /** Delete asks once, then the row and the file are both gone. */
    @Test fun deletingARowRemovesTheRowAndTheFile() {
        val id = newAsset("Well pump")
        val added = addFile(AttachmentOwner.OfAsset(AssetId(id)), MANUAL_NAME, PDF_MIME, pdfBytes())
        val ownerDir = File(tree, AttachmentLocator.dirFor(added.owner))
        assertEquals(1, ownerDir.listFiles()!!.size)

        openAsset(id).use {
            rule.awaitText("DOCUMENTS · 1")
            rule.openRowSheet(MANUAL_NAME)
            rule.onNodeWithText("Delete").performClick()

            // A plain confirmation, not a typed one (spec §11.7), and it names the file.
            rule.awaitText("Delete file?")
            rule.onNodeWithText(
                "Delete $MANUAL_NAME? The file is removed from your attachment folder.",
            ).assertIsDisplayed()
            rule.confirmDeleteInDialog()

            rule.awaitGone("DOCUMENTS · 1")
            rule.onNodeWithText("DOCUMENTS").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("No documents yet").performScrollTo().assertIsDisplayed()
            rule.onAllNodesWithText(MANUAL_NAME).assertCountEquals(0)
            // The sheet went with the row it was editing.
            rule.onAllNodesWithText("KIND").assertCountEquals(0)
        }

        assertEquals(emptyList<String>(), ownerDir.list()!!.toList())
        assertEquals(null, runBlocking { app.graph.attachments.get(added.id) })
    }

    // ---------------------------------------------------------------- scenario (e)

    /** An image row shows a thumbnail; a document row shows its kind glyph. */
    @Test fun anImageRowGetsAThumbnailAndADocumentRowGetsAGlyph() {
        val id = newAsset("Well pump")
        val owner = AttachmentOwner.OfAsset(AssetId(id))
        val photo = addFile(owner, PHOTO_NAME, JPEG_MIME, jpegBytes())
        val manual = addFile(owner, MANUAL_NAME, PDF_MIME, pdfBytes())

        val thumbnails = app.graph.thumbnails
        val photoThumb = thumbnails.cacheFileFor(photo.id, photo.sha256)
        val manualThumb = thumbnails.cacheFileFor(manual.id, manual.sha256)

        openAsset(id).use {
            rule.awaitText("DOCUMENTS · 2")
            rule.onNode(hasText(PHOTO_NAME) and hasText("Photo · ", substring = true))
                .performScrollTo()
                .assertIsDisplayed()
            rule.onNode(hasText(MANUAL_NAME) and hasText("Document · ", substring = true))
                .performScrollTo()
                .assertIsDisplayed()

            // The image's thumbnail is decoded by the section's own scan; the document has none to
            // decode, so its row falls back to the kind glyph and no cache file is ever written.
            rule.waitUntil(WAIT_MS) { photoThumb.isFile && photoThumb.length() > 0L }
            rule.waitForIdle()
            assertFalse("a document must not get a thumbnail", manualThumb.exists())
        }
    }

    // ---------------------------------------------------------------- scenario (f)

    /** Export writes two stamped files into a test tree, and the Backup screen agrees it happened. */
    @Test fun exportingWritesTwoStampedFilesIntoThePickedFolder() {
        val id = newAsset("Well pump")
        addFile(AttachmentOwner.OfAsset(AssetId(id)), MANUAL_NAME, PDF_MIME, pdfBytes())
        val folder = wipedDir("backup-proof")

        val message = runBlocking { exportInto(folder) }

        // Two names asked for, both stamped, both carrying the same stamp.
        val asked = message.split(" + ")
        val stamps = asked.map { name ->
            val match = SET_FILE.matchEntire(name)
            assertNotNull("unexpected file name $name", match)
            match!!.groupValues[1]
        }.distinct()
        assertEquals("both halves carry one stamp", 1, stamps.size)
        val stamp = stamps.single()
        assertEquals(
            listOf("ServiceTag-artifacts-$stamp.zip", "ServiceTag-data-$stamp.zip"),
            asked.sorted(),
        )

        // And two documents in the folder, one per name. This provider appends the extension it
        // derives from the mime type, so each document on disk is the name the writer asked for
        // with a second ".zip" behind it (`RawDocumentFile.createFile`) — the same quirk
        // `SafTreeAttachmentStore` resolves by prefix, and the reason the set's naming is asserted
        // on what the writer reported rather than on what a provider chose to call it.
        assertEquals(2, folder.list()!!.size)
        asked.forEach { name ->
            val document = documentFor(folder, name)
            assertTrue(document.name, document.name.startsWith(name))
            assertTrue(document.name, document.length() > 0L)
        }

        // The artifacts half is the attachment's bytes: manifest first, then one entry.
        ZipFile(documentFor(folder, "ServiceTag-artifacts-$stamp.zip")).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertEquals("manifest.json", entries.first())
            assertEquals(1, entries.count { it.startsWith("artifacts/") })
        }

        openAsset(id).use {
            rule.awaitText("Backup")
            rule.onNodeWithText("Backup").performScrollTo().performClick()
            rule.awaitText("Export backup set")
            // "Last backup" is the export we just took, not "Never".
            rule.onAllNodesWithText("Never").assertCountEquals(0)
            rule.onNodeWithText(lastBackupLine()).performScrollTo().assertIsDisplayed()
        }
    }

    /** The real writer's invariant: a body that throws leaves no document behind. */
    @Test fun safWriterRemovesTheDocumentItCreatedWhenTheBodyThrows() {
        val dir = wipedDir("set-${System.nanoTime()}")
        val writer = SafBackupSetWriter(context, context.contentResolver, DocumentFile.fromFile(dir))
        val failure = runCatching {
            runBlocking {
                writer.write("ServiceTag-artifacts-20260916-000000.zip") { out ->
                    out.write(ByteArray(4096))
                    error("rigged mid-write failure")
                }
            }
        }
        assertTrue(failure.isFailure)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    // ---------------------------------------------------------------- scenario (g)

    /** Data-only restore is a first-class outcome: rows come back reading "Not on this device". */
    @Test fun restoringDataAloneLeavesTheRowsSayingNotOnThisDevice() {
        val id = newAsset("Well pump")
        addFile(AttachmentOwner.OfAsset(AssetId(id)), PHOTO_NAME, JPEG_MIME, jpegBytes())
        val folder = wipedDir("backup-proof")
        runBlocking { exportInto(folder) }

        // A fresh install with the same folder chosen and nothing in it.
        clearInstall()
        tree = useFileBackedTree()
        val report = runBlocking {
            BackupViewModel(app.graph)
                .restoreData(SafBackupIO(context.contentResolver, Uri.fromFile(dataArchive(folder))))
                .getOrThrow()
        }
        assertEquals(1, report.attachments)
        assertNotEquals("", report.lastRestoredBackupSetId)
        assertEquals(emptyList<String>(), filesUnder(tree))

        openAsset(id).use {
            rule.awaitText("DOCUMENTS · 1")
            rule.onNode(hasText(PHOTO_NAME) and hasText("Not on this device"))
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- scenario (h)

    /** Then the files archive puts the bytes back and the row stops saying it. */
    @Test fun restoringTheFilesArchiveBringsTheBytesBack() {
        val id = newAsset("Well pump")
        val owner = AttachmentOwner.OfAsset(AssetId(id))
        val photo = addFile(owner, PHOTO_NAME, JPEG_MIME, jpegBytes())
        val folder = wipedDir("backup-proof")
        runBlocking { exportInto(folder) }

        clearInstall()
        tree = useFileBackedTree()
        val model = BackupViewModel(app.graph)
        val files = runBlocking {
            model.restoreData(SafBackupIO(context.contentResolver, Uri.fromFile(dataArchive(folder))))
                .getOrThrow()
            model.restoreFiles(
                SafBackupIO(context.contentResolver, Uri.fromFile(artifactsArchive(folder))),
            ).getOrThrow()
        }
        assertEquals(1, files.restored)
        assertEquals(0, files.skipped)
        assertEquals(emptyList<String>(), files.missingEntries)
        assertEquals(emptyList<String>(), files.unexpectedEntries)

        // The bytes are back under the locator the row has always named.
        val restored = soleFileIn(File(tree, AttachmentLocator.dirFor(owner)))
        assertArrayEquals(jpegBytes(), restored.readBytes())

        val thumb = app.graph.thumbnails.cacheFileFor(photo.id, photo.sha256)
        assertFalse("the wipe took the thumbnail with it", thumb.exists())

        openAsset(id).use {
            rule.awaitText("DOCUMENTS · 1")
            rule.onNode(hasText(PHOTO_NAME) and hasText("Photo · ", substring = true))
                .performScrollTo()
                .assertIsDisplayed()
            rule.onAllNodesWithText("Not on this device").assertCountEquals(0)
            rule.waitUntil(WAIT_MS) { thumb.isFile && thumb.length() > 0L }
        }
    }

    // ---------------------------------------------------------------- scenario (i)

    /** The files archive of another set is refused, naming both short ids. */
    @Test fun aFilesArchiveFromAnotherSetIsRefused() {
        val id = newAsset("Well pump")
        addFile(AttachmentOwner.OfAsset(AssetId(id)), PHOTO_NAME, JPEG_MIME, jpegBytes())
        val first = wipedDir("backup-proof-a")
        val second = wipedDir("backup-proof-b")
        runBlocking { exportInto(first) }
        runBlocking { exportInto(second) }

        // Nothing in the folder any more, so anything that lands is something this restore wrote.
        tree = useFileBackedTree()
        val model = BackupViewModel(app.graph)
        val resolver = context.contentResolver
        val (expected, offered) = runBlocking {
            // Each data archive names its own set; restoring the first one last makes it the set
            // the files step must match.
            val second2 = model.restoreData(SafBackupIO(resolver, Uri.fromFile(dataArchive(second))))
                .getOrThrow().lastRestoredBackupSetId
            val first2 = model.restoreData(SafBackupIO(resolver, Uri.fromFile(dataArchive(first))))
                .getOrThrow().lastRestoredBackupSetId
            first2 to second2
        }
        assertNotEquals(expected, offered)

        val line = firstMessage(model) {
            model.restoreFilesFrom(SafBackupIO(resolver, Uri.fromFile(artifactsArchive(second))))
        }
        assertEquals(
            "Those files belong to backup set ${offered.take(8)}, not ${expected.take(8)}",
            line,
        )
        assertEquals("the refused restore wrote nothing", emptyList<String>(), filesUnder(tree))

        openAsset(id).use {
            rule.awaitText("DOCUMENTS · 1")
            rule.onNode(hasText(PHOTO_NAME) and hasText("Not on this device"))
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- scenario (j)

    /** An event carries its own files, and deleting the entry takes them with it. */
    @Test fun anEventsFilesAreItsOwnAndGoWhenTheEntryDoes() {
        val assetId = newAsset("Mower")
        val eventId = newEvent(assetId, ENTRY_TITLE)
        val owner = AttachmentOwner.OfEvent(EventId(eventId))
        addFile(owner, RECEIPT_NAME, PDF_MIME, pdfBytes())
        val ownerDir = File(tree, AttachmentLocator.dirFor(owner))
        assertEquals(1, ownerDir.listFiles()!!.size)

        openAsset(assetId).use {
            rule.awaitText("SERVICE RECORD")
            // The asset's own DOCUMENTS is empty: an event's files belong to the event (spec §3).
            rule.onNodeWithText("DOCUMENTS").performScrollTo().assertIsDisplayed()
            rule.onAllNodesWithText("DOCUMENTS · 1").assertCountEquals(0)
            rule.onAllNodesWithText(RECEIPT_NAME).assertCountEquals(0)

            rule.openLedgerEntry(ENTRY_TITLE, LocalDate.now())
            rule.awaitText("DOCUMENTS · 1")
            rule.onNode(hasText(RECEIPT_NAME) and hasText("Document · 2.0 KB · ${today()}"))
                .performScrollTo()
                .assertIsDisplayed()

            // #67 (R67-11): an event entry's file is never offered a role, through the real section.
            rule.openRowSheet(RECEIPT_NAME)
            rule.onAllNodesWithText("ROLE").assertCountEquals(0)
            rule.onAllNodesWithText("No role").assertCountEquals(0)
            rule.onNodeWithText("Cancel").performScrollTo().performClick()
            rule.awaitGone("KIND")

            // Delete the entry: the app bar's own overflow, not the row's.
            rule.onNodeWithContentDescription("More").performClick()
            rule.onNodeWithText("Delete").performClick()
            rule.awaitText("Delete this entry?")
            // The menu is dismissed before the dialog's own Delete is the only one on screen.
            rule.waitUntil(WAIT_MS) {
                rule.onAllNodesWithText("Delete").fetchSemanticsNodes().size == 1
            }
            rule.onNodeWithText("Delete").performClick()

            rule.awaitText("SERVICE RECORD")
            rule.onAllNodesWithText(ENTRY_TITLE).assertCountEquals(0)
        }

        assertEquals(emptyList<String>(), ownerDir.list()!!.toList())
        assertEquals(emptyList<Attachment>(), runBlocking { app.graph.attachments.forOwner(owner) })
    }
}

// -------------------------------------------------------------------- fixtures

/** How long a device assertion waits for a Room flow, a navigation or a decode to land. */
private const val WAIT_MS = 10_000L

private const val PDF_MIME = "application/pdf"
private const val JPEG_MIME = "image/jpeg"
private const val MANUAL_NAME = "pump-manual.pdf"
private const val RENAMED = "Well pump manual.pdf"
private const val PHOTO_NAME = "label.jpg"
private const val RECEIPT_NAME = "receipt.pdf"
private const val ENTRY_TITLE = "Oil change"

/** `ServiceTag-<half>-<yyyyMMdd-HHmmss>.zip`, the shape `BackupSetNames` writes. */
private val SET_FILE = Regex("""^ServiceTag-(?:data|artifacts)-(\d{8}-\d{6})\.zip$""")

private val context: Context get() = ApplicationProvider.getApplicationContext()

/**
 * Points the graph's attachment seams at an ordinary directory (spec §12). The picker is the one
 * thing an instrumented test cannot drive; everything it would have produced is produced here.
 *
 * The seams stay pointed at this directory for the rest of the process, which is harmless: they
 * are only ever consulted when `attachmentTreeUri` is set, and [clearInstall] clears it.
 */
private fun useFileBackedTree(): File {
    val root = wipedDir("attachments-proof")
    val graph = app.graph
    graph.attachmentRootResolver = { _ ->
        DocumentTreeRoot(DocumentFile.fromFile(root), context.contentResolver)
    }
    graph.attachmentGrantCheck = { true }
    graph.prefs.attachmentTreeUri = "file://" + root.absolutePath
    return root
}

/** An app-external directory with nothing in it. Used for the tree and for a backup folder. */
private fun wipedDir(name: String): File =
    File(context.getExternalFilesDir(null), name).also {
        it.deleteRecursively()
        it.mkdirs()
    }

/** Created in-process through the command the editor builds, as the 2B-2 suite does. */
private fun newAsset(name: String): String =
    runBlocking { app.graph.createAsset.run(name = name).id.value }

/** One plain entry against [assetId], with no profile and no readings. */
private fun newEvent(assetId: String, title: String): String = runBlocking {
    app.graph.logEvent.run(
        EventCommand(
            assetId = AssetId(assetId),
            profileId = null,
            kind = EventKind.MAINTENANCE,
            title = title,
            occurredOn = LocalDate.now().toString(),
            occurredTime = null,
            tzId = ZoneId.systemDefault().id,
            notes = "",
            values = emptyMap(),
            consumables = emptyList(),
        ),
    ).id.value
}

/** Adds a file the way `AttachmentPickers` would: display name, mime, size, a byte source. */
private fun addFile(
    owner: AttachmentOwner,
    name: String,
    mime: String,
    bytes: ByteArray,
): Attachment = runBlocking {
    val result = app.graph.addAttachment.run(
        owner,
        AddAttachmentCommand(
            displayName = name,
            mimeType = mime,
            sizeBytes = bytes.size.toLong(),
            // The section stamps today on everything it adds (spec §8.1).
            capturedOn = today(),
        ),
        ByteSource { bytes.inputStream() },
    )
    (result as AttachmentResult.Ok).value
}

/** Two kibibytes, so the quiet line reads a stable "2.0 KB". */
private fun pdfBytes(): ByteArray = ByteArray(2048) { (it % 251).toByte() }

/** A tiny valid JPEG, encoded in-process so no binary test asset is committed. */
private fun jpegBytes(): ByteArray = ByteArrayOutputStream().also { out ->
    Bitmap.createBitmap(512, 384, Bitmap.Config.ARGB_8888)
        .compress(Bitmap.CompressFormat.JPEG, 90, out)
}.toByteArray()

private fun today(): String = LocalDate.now().toString()

/** The production export, into a folder picked the only way a test can pick one. */
private suspend fun exportInto(folder: File): String =
    BackupViewModel(app.graph)
        .exportSet(SafBackupSetWriter(context, context.contentResolver, DocumentFile.fromFile(folder)))
        .getOrThrow()

private fun dataArchive(folder: File): File = documentFor(folder, "ServiceTag-data-")

private fun artifactsArchive(folder: File): File = documentFor(folder, "ServiceTag-artifacts-")

/** The one document in [folder] whose name begins with [asked] — see the export scenario. */
private fun documentFor(folder: File, asked: String): File =
    folder.listFiles()!!.single { it.name.startsWith(asked) }

private fun soleFileIn(dir: File): File = dir.listFiles()!!.single()

private fun filesUnder(dir: File): List<String> = dir.walkTopDown()
    .filter { it.isFile }
    .map { it.relativeTo(dir).path }
    .sorted()
    .toList()

/** What the Backup screen writes for the export just taken. */
private fun lastBackupLine(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    .format(Date(app.graph.prefs.lastBackupAt!!))

/**
 * The first line [trigger] causes the ViewModel to say.
 *
 * `messages` has no replay, so the collector has to be subscribed before the work starts:
 * `Dispatchers.Unconfined` runs the `launch` body eagerly up to its first suspension, which is the
 * subscription itself, so by the time [trigger] is called the line cannot be missed.
 */
private fun firstMessage(model: BackupViewModel, trigger: () -> Unit): String = runBlocking {
    val said = CompletableDeferred<String>()
    val collector = launch(Dispatchers.Unconfined) { said.complete(model.messages.first()) }
    try {
        trigger()
        withTimeout(WAIT_MS) { said.await() }
    } finally {
        collector.cancel()
    }
}

/** The cold-start path a tag tap takes, which is also the one `ActivityScenario` can track (1C). */
private fun openAsset(id: String): ActivityScenario<MainActivity> {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("servicetag://asset/$id"))
        .setClass(context, MainActivity::class.java)
    return ActivityScenario.launch(intent)
}

// -------------------------------------------------------------------- driving the screens

/** Waits until nothing on screen carries [text] any more. */
private fun ComposeTestRule.awaitGone(text: String) {
    waitUntil(WAIT_MS) { onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
}

/** One labelled field of the edit sheet; the label is how a Material field is named (2B-1). */
private fun ComposeTestRule.field(label: String) = onNode(hasSetTextAction() and hasText(label))

/** A DOCUMENTS row's own overflow, which is named after the file it belongs to (spec §8.1). */
private fun ComposeTestRule.openRowSheet(displayName: String) {
    onNodeWithContentDescription("More for $displayName").performScrollTo().performClick()
    awaitText("KIND")
}

/**
 * The delete confirmation's own Delete.
 *
 * Two nodes read "Delete" while the dialog is up — the sheet's button underneath it and the
 * dialog's — and the sheet's is the one that sits beside "Save".
 */
private fun ComposeTestRule.confirmDeleteInDialog() {
    val deletes = hasClickAction() and hasText("Delete")
    waitUntil(WAIT_MS) { onAllNodes(deletes).fetchSemanticsNodes().size == 2 }
    onNode(deletes and !hasAnySibling(hasText("Save"))).performClick()
}

/** One ledger row of the service record, named by its title and the day it is filed under. */
private fun ComposeTestRule.openLedgerEntry(title: String, on: LocalDate) {
    val entry = hasText(title) and hasText(ledgerDate(on))
    waitUntil(WAIT_MS) { onAllNodes(entry).fetchSemanticsNodes().isNotEmpty() }
    onNode(entry).performScrollTo().performClick()
}

/** "15 SEP", the way `LedgerEntry` writes the day it happened on. */
private fun ledgerDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("dd")) + " " +
        date.format(DateTimeFormatter.ofPattern("MMM")).uppercase()
