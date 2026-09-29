package com.loosecannon.servicetag.ui.asset

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.attachments.DocumentTreeRoot
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.UpdateAttachmentCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.attachments.AttachmentEditSheet
import com.loosecannon.servicetag.ui.attachments.AttachmentRowState
import com.loosecannon.servicetag.ui.attachments.AttachmentsSectionState
import com.loosecannon.servicetag.ui.attachments.DocumentsSection
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long an assertion waits for a state to settle. */
private const val WAIT_MS = 5_000L

/** The four Role chips, in the order the sheet and the intake draw them (P67-6, P67-2/3/4). */
private val ROLE_CHIPS = listOf("No role", "Purchase invoice or receipt", "User manual", "Service manual")

/**
 * #67, C7 and C8 on a real Compose tree: the edit sheet's Role section, the Key documents block
 * inside the attachments section, and the Details fact "Purchase document".
 *
 * The sheet and `DocumentsSection` are pure functions of what they are handed, so the first four
 * cases draw them alone, with hand-built rows, exactly as `DocumentsDescriptionLineTest` does — the
 * sheet's `onSave` and the section's `onOpen` are what those cases capture. The last case draws the
 * whole Asset detail over the app's own graph, with its attachment seam pointed at a file-backed
 * tree the way `AttachmentsDeviceProofTest` points it, so the fact is proved through the production
 * view model and its wiring.
 *
 * Emulator only (`emulator-5554`) — the last case wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetDetailKeyDocumentsTest {

    /** An activity rule, not the plain one, so the 360 dp phone case can hand the activity its own view. */
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun row(
        id: String,
        name: String,
        role: DocumentRole?,
        kind: AttachmentKind = AttachmentKind.DOCUMENT,
    ) = AttachmentRowState(
        id = id,
        displayName = name,
        kind = kind,
        sizeBytes = 2048L,
        capturedOn = "2026-09-20",
        notes = "",
        mimeType = "application/pdf",
        locator = "assets/$id/$name",
        isImage = false,
        present = true,
        role = role,
    )

    /** The sheet alone, over whichever row and flag the case is on. */
    private fun sheet(
        initial: Pair<AttachmentRowState, Boolean>,
        onSave: (UpdateAttachmentCommand) -> Unit = {},
    ): (Pair<AttachmentRowState, Boolean>) -> Unit {
        var shown by mutableStateOf(initial)
        rule.setContent {
            ServiceTagTheme {
                AttachmentEditSheet(
                    row = shown.first,
                    rolesOffered = shown.second,
                    onSave = onSave,
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }
        rule.awaitText("KIND")
        return { shown = it }
    }

    /**
     * C7 (AC 3, R67-11): an asset's file gets the Role section under Kind — the header and the four
     * chips, seeded from the row's own role — and an event's file, which the section hands the
     * sheet with the flag off, gets none of it.
     */
    @Test fun theRoleChipsAreOfferedForAnAssetRowAndNotForAnEventRow() {
        val show = sheet(row("a1", "Pump manual.pdf", DocumentRole.USER_MANUAL) to true)

        rule.onNodeWithText("ROLE").assertExists()
        ROLE_CHIPS.forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("User manual").assertIsSelected()
        check(top("KIND") < top("ROLE")) { "ROLE is drawn under KIND" }

        show(row("e1", "Filter change.jpg", null, AttachmentKind.PHOTO) to false)
        rule.awaitText("KIND")
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText("ROLE").fetchSemanticsNodes().isEmpty() }
        ROLE_CHIPS.forEach { rule.onAllNodesWithText(it).assertCountEquals(0) }
    }

    /**
     * C2/C7 (matrix row 3): a rename through the sheet, with the chips untouched, saves the row's
     * own role — a rename never clears one. The row is on the last role, so a sheet that seeded its
     * chips from anywhere but the row could not pass.
     */
    @Test fun aRenameThroughTheSheetLeavesTheRoleUnchanged() {
        val saved = mutableListOf<UpdateAttachmentCommand>()
        sheet(row("a1", "Pump service manual.pdf", DocumentRole.SERVICE_MANUAL) to true) { saved += it }

        rule.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Spa pump service manual.pdf")
        // The keyboard is up after typing: bring Save into view first, as the 360 dp case does.
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitUntil(WAIT_MS) { saved.isNotEmpty() }

        assertEquals("Spa pump service manual.pdf", saved.single().displayName)
        assertEquals(DocumentRole.SERVICE_MANUAL, saved.single().role)
    }

    /**
     * C7 (AC 3, R67-6): reclassifying is the sheet's job — a chip picked is the role saved, and
     * "No role" saves none.
     */
    @Test fun aChosenRoleIsTheRoleSaved() {
        val saved = mutableListOf<UpdateAttachmentCommand>()
        sheet(row("a1", "Invoice 4471.pdf", DocumentRole.USER_MANUAL) to true) { saved += it }

        rule.onNodeWithText("Purchase invoice or receipt").performClick()
        rule.onNodeWithText("Purchase invoice or receipt").assertIsSelected()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(WAIT_MS) { saved.size == 1 }

        rule.onNodeWithText("No role").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(WAIT_MS) { saved.size == 2 }

        assertEquals(DocumentRole.PURCHASE_INVOICE_OR_RECEIPT, saved[0].role)
        assertNull(saved[1].role)
        assertEquals(listOf("Invoice 4471.pdf", "Invoice 4471.pdf"), saved.map { it.displayName })
    }

    /**
     * C8 (AC 4, R67-4): a receipt shows in KEY DOCUMENTS, under its role label, above DOCUMENTS —
     * and still in DOCUMENTS, whose count and rows are unchanged. Both rows open the one attachment.
     */
    @Test fun aReceiptShowsInKeyDocumentsAndInDocumentsAndOpensTheSameAttachment() {
        val opened = mutableListOf<String>()
        val receipt = row("r1", "Hot tub receipt.pdf", DocumentRole.PURCHASE_INVOICE_OR_RECEIPT)
        val notes = row("n1", "Chemistry notes.pdf", null)
        rule.setContent {
            ServiceTagTheme {
                Column {
                    DocumentsSection(
                        state = AttachmentsSectionState(store = READY, rows = listOf(notes, receipt)),
                        onOpen = { opened += it.id },
                        onEdit = {},
                        onAddFiles = {},
                        onTakePhoto = {},
                        onOpenSettings = {},
                    )
                }
            }
        }
        rule.awaitText("KEY DOCUMENTS")

        rule.onNodeWithText("Purchase invoice or receipt").assertIsDisplayed()
        rule.onNodeWithText("DOCUMENTS · 2").assertIsDisplayed()
        rule.onAllNodesWithText("Chemistry notes.pdf").assertCountEquals(1)
        val receipts = rule.onAllNodesWithText("Hot tub receipt.pdf")
        receipts.assertCountEquals(2)
        rule.onAllNodesWithContentDescription("More for Hot tub receipt.pdf").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("More for Hot tub receipt.pdf, Purchase invoice or receipt").assertCountEquals(1)

        val keyed = receipts[0].getUnclippedBoundsInRoot().top
        val listed = receipts[1].getUnclippedBoundsInRoot().top
        check(top("KEY DOCUMENTS") < top("Purchase invoice or receipt")) { "the role label is under KEY DOCUMENTS" }
        check(top("Purchase invoice or receipt") < keyed) { "the receipt is under its role label" }
        check(keyed < top("DOCUMENTS · 2")) { "KEY DOCUMENTS is above DOCUMENTS" }
        check(top("DOCUMENTS · 2") < listed) { "the receipt is still in DOCUMENTS" }

        receipts[0].performClick()
        receipts[1].performClick()
        assertEquals(listOf("r1", "r1"), opened)
    }

    /**
     * R67-5 (AC 4), drawn: the Asset detail's DETAILS names the purchase document by its display
     * name, as text directly under its label — not a link — and the same receipt is the Key
     * documents row and the DOCUMENTS row, so the name is on screen three times and no more.
     */
    @Test fun theDetailsFactsNameThePurchaseDocument() {
        clearInstall()
        useFileBackedTree()
        val id = runBlocking {
            val asset = app.graph.createAsset.run(AssetCommand(name = "Hot tub"))
            val added = app.graph.addAttachment.run(
                AttachmentOwner.OfAsset(asset.id),
                AddAttachmentCommand(
                    displayName = "Hot tub receipt.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 4L,
                    capturedOn = "2026-05-01",
                    role = DocumentRole.PURCHASE_INVOICE_OR_RECEIPT,
                ),
                ByteSource { "%PDF".byteInputStream() },
            )
            check(added is AttachmentResult.Ok) { "the receipt is added: $added" }
            asset.id.value
        }
        rule.setContent {
            ServiceTagTheme {
                AssetDetailScreen(
                    graph = app.graph,
                    assetId = id,
                    onBack = {}, onEdit = {}, onSetup = {}, onWriteTag = {}, onBackup = {},
                    onLogEvent = { _, _ -> }, onOpenEvent = {}, onOpenAsset = {}, onAddComponent = {},
                    onAddSchedule = {}, onLogOutcome = { _, _ -> }, onOpenSettings = {},
                    onOpenSchedule = {}, onOpenGroup = {},
                )
            }
        }
        rule.awaitText("PURCHASE DOCUMENT")

        val label = rule.onNodeWithText("PURCHASE DOCUMENT").performScrollTo()
        label.assertIsDisplayed()
        val value = rule.onNode(hasText("Hot tub receipt.pdf") and !hasClickAction()).performScrollTo()
        value.assertIsDisplayed()
        val labelBounds = label.getUnclippedBoundsInRoot()
        val valueTop = value.getUnclippedBoundsInRoot().top
        check(valueTop >= labelBounds.bottom && valueTop - labelBounds.bottom < 8.dp) {
            "the name sits directly under its label: label bottom ${labelBounds.bottom}, value top $valueTop"
        }

        rule.awaitText("KEY DOCUMENTS")
        rule.onAllNodesWithText("Hot tub receipt.pdf").assertCountEquals(3)
    }

    /**
     * m-1: the sheet's content scrolls, so Save stays reachable on a 360 dp-wide phone at font scale
     * 2.0, where the Role section pushes the Delete / Cancel / Save row past the window.
     *
     * The sheet is a window of its own, which takes its density from the view that opened it and
     * not from `LocalDensity`, so the phone is a `ComposeView` whose context overrides the
     * configuration: font scale 2.0 and a density at which this display is 360 dp wide. The first
     * two checks prove the sheet really drew at that size before Save is looked for.
     */
    @Test fun saveIsReachableOnA360dpPhoneAtDoubleTextSize() {
        val saved = mutableListOf<UpdateAttachmentCommand>()
        rule.runOnUiThread {
            val activity = rule.activity
            val metrics = activity.resources.displayMetrics
            val phone = Configuration(activity.resources.configuration).apply {
                fontScale = 2f
                densityDpi = metrics.widthPixels * 160 / 360
                screenWidthDp = 360
                screenHeightDp = metrics.heightPixels * 360 / metrics.widthPixels
            }
            val context = ContextThemeWrapper(activity, activity.theme).apply { applyOverrideConfiguration(phone) }
            activity.setContentView(
                ComposeView(context).apply {
                    setContent {
                        ServiceTagTheme {
                            AttachmentEditSheet(
                                row = row("a1", "Pump manual.pdf", DocumentRole.USER_MANUAL),
                                rolesOffered = true,
                                onSave = { saved += it },
                                onDelete = {},
                                onDismiss = {},
                            )
                        }
                    }
                },
            )
        }
        rule.awaitText("KIND")

        val name = rule.onNode(hasSetTextAction() and hasText("Name")).getUnclippedBoundsInRoot()
        check(abs((name.right - name.left).value - 328f) < 1f) {
            "the sheet is 360 dp wide: its Name field spans ${name.right - name.left}, not 328 dp"
        }
        val kind = rule.onNodeWithText("KIND").getUnclippedBoundsInRoot()
        check(kind.bottom - kind.top > 20.dp) { "the sheet draws double-size text: KIND is ${kind.bottom - kind.top} tall" }

        rule.onNodeWithText("Save").performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(WAIT_MS) { saved.isNotEmpty() }
        assertEquals(DocumentRole.USER_MANUAL, saved.single().role)
    }

    private fun top(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top
}

private val READY = StoreState.Ready("Attachments", "com.example.provider")

/**
 * Points the graph's attachment seams at an ordinary directory, exactly as `AttachmentsDeviceProofTest`
 * does — the picker is the one thing an instrumented test cannot drive; everything after it is real.
 */
private fun useFileBackedTree(): File {
    val root = File(context.getExternalFilesDir(null), "key-documents-detail-proof").also {
        it.deleteRecursively()
        it.mkdirs()
    }
    val graph = app.graph
    graph.attachmentRootResolver = { _ ->
        DocumentTreeRoot(DocumentFile.fromFile(root), context.contentResolver)
    }
    graph.attachmentGrantCheck = { true }
    graph.prefs.attachmentTreeUri = "file://" + root.absolutePath
    return root
}

private val context: Context get() = ApplicationProvider.getApplicationContext()
