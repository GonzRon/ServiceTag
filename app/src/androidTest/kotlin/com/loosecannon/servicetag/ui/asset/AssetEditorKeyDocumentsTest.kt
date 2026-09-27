package com.loosecannon.servicetag.ui.asset

import android.content.Context
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.attachments.DocumentTreeRoot
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long an assertion waits for a state to settle. */
private const val WAIT_MS = 5_000L

/**
 * #67, C5, editor device row — the asset editor's document intake on a real Compose tree.
 *
 * **How the picks are driven without the SAF picker.** Same seam as `EmptyStoreRestorePromptTest`:
 * `rememberLauncherForActivityResult` resolves its registry through `LocalActivityResultRegistryOwner`,
 * so a tap on any of the three affordances gets a `content://` document URI back immediately, no
 * picker activity involved. Each pick gets its own file name (`pick-0.pdf`, `pick-1.pdf`, …) so the
 * staged lines are never ambiguous. No seam is added to production code.
 *
 * The bytes are never opened here — that only happens at Save, which these cases never reach — so
 * the fake authority is never asked to hand any over.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetEditorKeyDocumentsTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var tree: File
    private var pickCounter = 0

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                val uri = "content://com.loosecannon.servicetag.test/pick-${pickCounter++}.pdf".toUri()
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, uri as O)
            }
        }
    }

    @Before fun freshInstallWithATree() {
        clearInstall()
        tree = useFileBackedTree()
    }

    private fun openNewAsset(
        graph: AppGraph = app.graph,
        onOpenSettings: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            ServiceTagTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    AssetEditScreen(
                        graph = graph,
                        assetId = null,
                        onDone = {},
                        onBack = onBack,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
    }

    /** C5 (AC 1, AC 2): the three affordances each stage a file, and Remove forgets one of them. */
    @Test fun theThreeAffordancesStageAndRemove() {
        openNewAsset()
        rule.awaitText("PURCHASE")

        rule.onNodeWithText(ADD_PURCHASE_INVOICE_OR_RECEIPT).performScrollTo().performClick()
        rule.awaitText("pick-0.pdf")
        rule.onNodeWithText(ADD_USER_MANUAL).performScrollTo().performClick()
        rule.awaitText("pick-1.pdf")
        rule.onNodeWithText(ADD_SERVICE_MANUAL).performScrollTo().performClick()
        rule.awaitText("pick-2.pdf")

        rule.onAllNodesWithText(ATTACHED_WHEN_YOU_SAVE).assertCountEquals(3)
        // B-2: each line draws the visible word, and says which file it removes to a screen reader.
        rule.onAllNodesWithText("Remove").assertCountEquals(3)
        rule.onNodeWithContentDescription("Remove pick-1.pdf").assert(hasText("Remove"))

        rule.onNodeWithContentDescription("Remove pick-1.pdf").performScrollTo().performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText("pick-1.pdf").fetchSemanticsNodes().isEmpty() }
        rule.onAllNodesWithText(ATTACHED_WHEN_YOU_SAVE).assertCountEquals(2)
        rule.onAllNodesWithText("Remove").assertCountEquals(2)
        rule.onAllNodesWithText("pick-0.pdf").assertCountEquals(1)
        rule.onAllNodesWithText("pick-2.pdf").assertCountEquals(1)
    }

    /**
     * R67-6: the receipt affordance and its staged line sit in PURCHASE (before WARRANTY); the two
     * manual affordances and their staged lines sit in KEY DOCUMENTS (after WARRANTY, before NOTES).
     * The column scrolls but never recycles, so every node is laid out and comparable in one frame.
     */
    @Test fun theReceiptSitsInPurchaseAndTheManualsInKeyDocuments() {
        openNewAsset()
        rule.awaitText("PURCHASE")
        rule.onNodeWithText(ADD_PURCHASE_INVOICE_OR_RECEIPT).performScrollTo().performClick()
        rule.awaitText("pick-0.pdf")
        rule.onNodeWithText(ADD_USER_MANUAL).performScrollTo().performClick()
        rule.awaitText("pick-1.pdf")
        rule.onNodeWithText(ADD_SERVICE_MANUAL).performScrollTo().performClick()
        rule.awaitText("pick-2.pdf")
        rule.waitForIdle()

        val purchase = top("PURCHASE")
        val warranty = top("WARRANTY")
        val keyDocuments = top("KEY DOCUMENTS")
        val notes = top("NOTES")
        check(purchase < warranty && warranty < keyDocuments && keyDocuments < notes) {
            "PURCHASE $purchase, WARRANTY $warranty, KEY DOCUMENTS $keyDocuments, NOTES $notes"
        }
        listOf(ADD_PURCHASE_INVOICE_OR_RECEIPT, "pick-0.pdf").forEach { text ->
            val at = top(text)
            check(at > purchase && at < warranty) { "$text at $at is not in PURCHASE ($purchase..$warranty)" }
        }
        listOf(ADD_USER_MANUAL, "pick-1.pdf", ADD_SERVICE_MANUAL, "pick-2.pdf").forEach { text ->
            val at = top(text)
            check(at > keyDocuments && at < notes) { "$text at $at is not in KEY DOCUMENTS ($keyDocuments..$notes)" }
        }
    }

    private fun top(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    /** C5 (AC 1): Cancel leaves no asset, no row and no file — nothing was ever written or copied. */
    @Test fun cancelLeavesNoRowAndNoFile() {
        var backed = false
        openNewAsset(onBack = { backed = true })
        rule.awaitText("PURCHASE")

        rule.onNodeWithText(ADD_USER_MANUAL).performScrollTo().performClick()
        rule.awaitText("pick-0.pdf")
        rule.onNodeWithText(ADD_SERVICE_MANUAL).performScrollTo().performClick()
        rule.awaitText("pick-1.pdf")

        rule.onNodeWithContentDescription("Cancel").performClick()
        rule.waitUntil(WAIT_MS) { backed }

        val assets = runBlocking { app.graph.assets.all().size }
        val rows = runBlocking { app.graph.attachments.count() }
        check(assets == 0) { "no asset after Cancel, found $assets" }
        check(rows == 0) { "no attachment row after Cancel, found $rows" }
        val files = filesUnder(tree)
        check(files.isEmpty()) { "no bytes under the tree after Cancel, found $files" }
    }

    /** R67-13: with no folder the three affordances are hidden and the card shows exactly once. */
    @Test fun theCardShowsOnceWithoutAFolder() {
        app.graph.prefs.attachmentTreeUri = null
        var opened = false
        openNewAsset(onOpenSettings = { opened = true })
        rule.awaitText("KEY DOCUMENTS")

        rule.onAllNodesWithText(ADD_PURCHASE_INVOICE_OR_RECEIPT).assertCountEquals(0)
        rule.onAllNodesWithText(ADD_USER_MANUAL).assertCountEquals(0)
        rule.onAllNodesWithText(ADD_SERVICE_MANUAL).assertCountEquals(0)

        rule.onNodeWithText("Attachment storage not set up").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Attachment storage not set up").assertCountEquals(1)

        rule.onNodeWithText("Open settings").performScrollTo().performClick()
        check(opened) { "Open settings pushes the Settings route" }
    }
}

/**
 * Points the graph's attachment seams at an ordinary directory, exactly as `AttachmentsDeviceProofTest`
 * does — the picker is the one thing this suite cannot drive, everything downstream of it is real.
 */
private fun useFileBackedTree(): File {
    val root = wipedDir("key-documents-proof")
    val graph = app.graph
    graph.attachmentRootResolver = { _ ->
        DocumentTreeRoot(DocumentFile.fromFile(root), context.contentResolver)
    }
    graph.attachmentGrantCheck = { true }
    graph.prefs.attachmentTreeUri = "file://" + root.absolutePath
    return root
}

private fun wipedDir(name: String): File =
    File(context.getExternalFilesDir(null), name).also {
        it.deleteRecursively()
        it.mkdirs()
    }

private fun filesUnder(dir: File): List<String> = dir.walkTopDown()
    .filter { it.isFile }
    .map { it.relativeTo(dir).path }
    .sorted()
    .toList()

private val context: Context get() = ApplicationProvider.getApplicationContext()
