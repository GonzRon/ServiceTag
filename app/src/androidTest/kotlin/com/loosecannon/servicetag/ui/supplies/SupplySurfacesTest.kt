package com.loosecannon.servicetag.ui.supplies

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.attachments.DocumentTreeRoot
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.attachments.ROLE_HEADER
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #15 (C32–C35, row 64): the asset's Supplies section, the role sheet, the picker — and, from B8b, the material
 * line's link — drawn. Each is a pure function of its arguments, so every case renders one directly, as
 * `ReferencesSectionTest` renders `ReferencesList`; the state each draws is proven on the JVM (rows 62, 63). What only
 * a device shows is the wording that reaches the semantics tree and which controls a read-only section leaves out.
 *
 * Every sentence comes from its one home (`SupplyStrings.kt`, `ROLE_HEADER`), so a re-worded constant moves this test
 * with it. A section header and a badge draw their words upper-case, so that is what the tree carries.
 *
 * #69 (C26, row 50) adds the SupplyItem detail's own Documents and References, below "Used by": those three cases draw
 * the whole detail over the app's own graph, so each wipes the install before and after itself (`clearInstall` takes
 * the catalog, and its CASCADE the item's files and links); the attachment seam points at a file-backed tree the way
 * `AttachmentsDeviceProofTest` points it. The section words are the sections' own, drawn as their headers draw them,
 * and spelled here as literals: those words have no constants to import, so the one-home rule above does not reach them.
 *
 * Emulator only, never a phone. No other case reaches the store, so none of them needs a wipe.
 */
@RunWith(AndroidJUnit4::class)
class SupplySurfacesTest {

    @get:Rule val rule = createComposeRule()

    private var opened: SupplyId? = null
    private var edited: String? = null
    private var removed: String? = null
    private var addTaps = 0

    private val carbon = AssetSupplyRowState(
        id = "as-carbon", supplyId = SupplyId("si-carbon"), name = "Example Carbon Block", role = "Stage 3",
        archived = true,
    )
    private val prefilter = AssetSupplyRowState(
        id = "as-prefilter", supplyId = SupplyId("si-prefilter"), name = "Example Prefilter Cartridge",
        role = "Stage 1", archived = false,
    )

    private fun drawSection(vararg rows: AssetSupplyRowState, readOnly: Boolean = false) {
        rule.setContent {
            ServiceTagTheme {
                Column {
                    AssetSuppliesList(
                        rows = rows.toList(),
                        readOnly = readOnly,
                        onOpen = { opened = it.supplyId },
                        onAdd = { addTaps += 1 },
                        onEditRole = { edited = it.id },
                        onRemove = { removed = it.id },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * The header (P15-1, no subtitle) and its add glyph labelled P15-13; each row the item's name, its role, and the
     * shipped badge on the archived one; a row's tap opens the item; the overflow offers P15-17 and "Remove".
     */
    @Test fun theSectionDrawsEachRowWithItsRoleAndTheArchivedBadge() {
        drawSection(prefilter, carbon)

        rule.onNodeWithText(SUPPLIES_SECTION.uppercase()).assertIsDisplayed()
        rule.onNodeWithText("Example Prefilter Cartridge").assertIsDisplayed()
        rule.onNodeWithText("Stage 1").assertIsDisplayed()
        rule.onNodeWithText("Example Carbon Block").assertIsDisplayed()
        rule.onNodeWithText("Stage 3").assertIsDisplayed()
        rule.onAllNodesWithText("ARCHIVED").assertCountEquals(1)
        rule.onAllNodesWithText(NO_SUPPLIES).assertCountEquals(0)

        rule.onNodeWithText("Example Carbon Block").performClick()
        assertEquals(SupplyId("si-carbon"), opened)
        rule.onNodeWithContentDescription(ADD_SUPPLY).performClick()
        assertEquals(1, addTaps)

        rule.onAllNodesWithContentDescription("More").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("More")[0].performClick()
        rule.onNodeWithText("Remove").assertIsDisplayed()
        rule.onNodeWithText(EDIT_ROLE).performClick()
        assertEquals("as-prefilter", edited)
        rule.onAllNodesWithContentDescription("More")[1].performClick()
        rule.onNodeWithText("Remove").performClick()
        assertEquals("as-carbon", removed)
    }

    /** An asset that takes nothing says P15-14 under the header, and the add glyph is still there. */
    @Test fun anEmptySectionSaysNoSuppliesAndStillOffersAdd() {
        drawSection()

        rule.onNodeWithText(SUPPLIES_SECTION.uppercase()).assertIsDisplayed()
        rule.onNodeWithText(NO_SUPPLIES).assertIsDisplayed()
        rule.onNodeWithContentDescription(ADD_SUPPLY).assertIsDisplayed()
    }

    /** A held asset's section draws its rows and nothing that writes: no add glyph, no overflow. */
    @Test fun aReadOnlySectionDrawsItsRowsAndNoGlyphOrOverflow() {
        drawSection(prefilter, carbon, readOnly = true)

        rule.onNodeWithText("Example Prefilter Cartridge").assertIsDisplayed()
        rule.onNodeWithText("Example Carbon Block").assertIsDisplayed()
        rule.onAllNodesWithContentDescription(ADD_SUPPLY).assertCountEquals(0)
        rule.onAllNodesWithContentDescription("More").assertCountEquals(0)
    }

    /**
     * The add sheet (P15-13): Save is disabled until the role has text, a suggestion chip fills the "Role" field, and
     * Cancel closes the sheet without a save.
     */
    @Test fun theRoleSheetEnablesSaveForARoleAndCancelWritesNothing() {
        var saves = 0
        var dismissed = false
        rule.setContent {
            ServiceTagTheme {
                var sheet by remember {
                    mutableStateOf(
                        RoleSheetState(
                            rowId = null, supplyId = SupplyId("si-prefilter"),
                            supplyName = "Example Prefilter Cartridge", role = "",
                        ),
                    )
                }
                AssetSupplyRoleSheet(
                    sheet = sheet,
                    suggestions = listOf("Spare", "Stage 1"),
                    onRole = { sheet = sheet.copy(role = it) },
                    onSave = { saves += 1 },
                    onDismiss = { dismissed = true },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(ADD_SUPPLY).assertIsDisplayed()
        rule.onNodeWithText("Example Prefilter Cartridge").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText(ROLE_HEADER)).assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()

        rule.onNodeWithText("Stage 1").performClick()
        rule.onNode(hasSetTextAction() and hasText("Stage 1")).assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsEnabled()

        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(dismissed)
        assertEquals(0, saves)
    }

    /** "Edit role" (P15-17) prefilled, with `Taken`'s P15-18 under the field; Save is offered again. */
    @Test fun theEditSheetDrawsTakenUnderTheField() {
        var saves = 0
        rule.setContent {
            ServiceTagTheme {
                AssetSupplyRoleSheet(
                    sheet = RoleSheetState(
                        rowId = "as-prefilter", supplyId = SupplyId("si-prefilter"),
                        supplyName = "Example Prefilter Cartridge", role = "Stage 1", problem = SUPPLY_ROLE_TAKEN,
                    ),
                    suggestions = emptyList(),
                    onRole = {},
                    onSave = { saves += 1 },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(EDIT_ROLE).assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("Stage 1")).assertIsDisplayed()
        rule.onNodeWithText(SUPPLY_ROLE_TAKEN).assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        assertEquals(1, saves)
    }

    /** An empty catalog: the picker's title (P15-15) and P15-16, which sends the owner to Maintenance › Supplies. */
    @Test fun anEmptyPickerSaysWhereToAddTheFirstItem() {
        rule.setContent { ServiceTagTheme { SupplyItemPicker(rows = emptyList(), onPick = {}) } }
        rule.waitForIdle()

        rule.onNodeWithText(CHOOSE_A_SUPPLY).assertIsDisplayed()
        rule.onNodeWithText(NO_SUPPLY_ITEMS_YET).assertIsDisplayed()
    }

    /** The picker draws exactly the rows its host gives it — the host filters, the picker decides nothing — and a tap reports the row. */
    @Test fun thePickerDrawsTheRowsGivenAndReportsAPick() {
        var picked: SupplyListRow? = null
        val rows = listOf(
            SupplyListRow(SupplyId("si-prefilter"), "Example Prefilter Cartridge", "Example Filters Co. · PF-10", false),
            SupplyListRow(SupplyId("si-sediment"), "Example Sediment Cartridge", "", false),
        )
        rule.setContent { ServiceTagTheme { SupplyItemPicker(rows = rows, onPick = { picked = it }) } }
        rule.waitForIdle()

        rule.onNodeWithText(CHOOSE_A_SUPPLY).assertIsDisplayed()
        rule.onAllNodesWithText(NO_SUPPLY_ITEMS_YET).assertCountEquals(0)
        rule.onNodeWithText("Example Filters Co. · PF-10").assertIsDisplayed()
        assertNull(picked)
        rule.onNodeWithText("Example Sediment Cartridge").performClick()
        assertEquals(SupplyId("si-sediment"), picked?.id)
    }

    // --- B8b (C34, C35): the material line's link -------------------------------------------------------------

    private val catalog = mapOf(
        SupplyId("si-prefilter") to
            SupplyListRow(SupplyId("si-prefilter"), "Example Prefilter Cartridge", "Example Filters Co. · PF-10", false),
        SupplyId("si-carbon") to SupplyListRow(SupplyId("si-carbon"), "Example Carbon Block", "", true),
    )

    /**
     * An unlinked row: the quick-action editor's line (given `onLink`) offers P15-21 and its tap asks to link; the
     * event form's line (no `onLink`) draws nothing, so the event form never offers a link (C35).
     */
    @Test fun anUnlinkedRowOffersLinkSupplyOnTheQuickActionEditorOnly() {
        var linkTaps = 0
        rule.setContent {
            ServiceTagTheme {
                Column {
                    SupplyLinkLine(supplyId = null, supplies = catalog, onUnlink = {}, onLink = { linkTaps += 1 })
                    SupplyLinkLine(supplyId = null, supplies = catalog, onUnlink = {})
                }
            }
        }
        rule.waitForIdle()

        rule.onAllNodesWithText(LINK_SUPPLY).assertCountEquals(1)
        rule.onAllNodesWithText(REMOVE_LINK).assertCountEquals(0)
        rule.onNodeWithText(LINK_SUPPLY).performClick()
        assertEquals(1, linkTaps)
    }

    /**
     * A linked row says P15-22 with its item's name and offers P15-23, whose tap clears the link; never P15-21 or the
     * badge for an active item. A link whose item is not loaded draws no line at all (C34), on either form.
     */
    @Test fun aLinkedRowSaysWhatItIsLinkedToAndOffersRemoveLink() {
        var unlinked = 0
        rule.setContent {
            ServiceTagTheme {
                Column {
                    SupplyLinkLine(
                        supplyId = SupplyId("si-prefilter"), supplies = catalog,
                        onUnlink = { unlinked += 1 }, onLink = {},
                    )
                    SupplyLinkLine(supplyId = SupplyId("si-not-loaded"), supplies = catalog, onUnlink = {}, onLink = {})
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(linkedTo("Example Prefilter Cartridge")).assertIsDisplayed()
        rule.onAllNodesWithText(REMOVE_LINK).assertCountEquals(1)
        rule.onAllNodesWithText(LINK_SUPPLY).assertCountEquals(0)
        rule.onAllNodesWithText("ARCHIVED").assertCountEquals(0)
        rule.onNodeWithText(REMOVE_LINK).performClick()
        assertEquals(1, unlinked)
    }

    /** A row linked to an archived item still names it, wears the shipped "Archived" badge, and can drop the link. */
    @Test fun aLinkToAnArchivedItemWearsTheBadge() {
        rule.setContent {
            ServiceTagTheme { SupplyLinkLine(supplyId = SupplyId("si-carbon"), supplies = catalog, onUnlink = {}) }
        }
        rule.waitForIdle()

        rule.onNodeWithText(linkedTo("Example Carbon Block")).assertIsDisplayed()
        rule.onNodeWithText("ARCHIVED").assertIsDisplayed()
        rule.onNodeWithText(REMOVE_LINK).assertIsDisplayed()
    }

    // --- #69 B6b (C26, row 50): the SupplyItem detail's own files and links, below "Used by" ------------------------

    private var settingsTaps = 0

    /** A fresh install around [block], and none left behind for a later class. */
    private fun onAFreshInstall(block: () -> Unit) {
        clearInstall()
        try {
            block()
        } finally {
            clearInstall()
        }
    }

    private fun battery(): SupplyItem = runBlocking {
        app.graph.saveSupplyItem.run(
            null,
            SupplyItemCommand(
                name = "Example 12 V Battery", category = "", manufacturer = "Example Power Co.", model = "",
                partNumber = "", preferredUnit = "", notes = "", specifications = emptyList(),
            ),
        ).item
    }

    /** The detail as the root draws it, over the app's own graph; its Settings taps are counted. */
    private fun drawDetail(id: SupplyId) {
        rule.setContent {
            ServiceTagTheme {
                SupplyDetailScreen(
                    graph = app.graph,
                    supplyId = id.value,
                    onBack = {},
                    onEdit = {},
                    onOpenAsset = {},
                    onOpenSettings = { settingsTaps += 1 },
                )
            }
        }
    }

    private fun top(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    /**
     * Below "Used by": DOCUMENTS first, then REFERENCES with the item's own link. With no folder chosen, the
     * Documents section's card is there, and its "Open settings" reaches the screen's new `onOpenSettings`.
     */
    @Test fun bothSectionsDrawBelowUsedByAndTheFolderCardOpensSettings() = onAFreshInstall {
        val item = battery()
        runBlocking {
            val added = app.graph.addReference.run(
                ReferenceOwner.OfSupplyItem(item.id),
                AddReferenceCommand(uri = "https://example.invalid/battery-12v", displayName = "Example battery data sheet"),
            )
            check(added is ReferenceResult.Ok) { "the link is added: $added" }
        }
        drawDetail(item.id)
        rule.awaitText("REFERENCES · 1")
        rule.awaitText("Open settings")

        rule.onNodeWithText("Example battery data sheet").assertExists()
        val usedBy = top(USED_BY_SECTION)
        val documents = top("DOCUMENTS")
        val references = top("REFERENCES · 1")
        check(usedBy < documents && documents < references) {
            "Used by ($usedBy), then Documents ($documents), then References ($references)"
        }

        rule.onNodeWithText("Open settings").performScrollTo().performClick()
        assertEquals(1, settingsTaps)
    }

    /** With a folder, the Documents section offers "Add file" and "Take photo" and References "Add link"; no card. */
    @Test fun withAFolderTheAddActionsArePresent() = onAFreshInstall {
        useFileBackedTree()
        val item = battery()
        drawDetail(item.id)
        rule.awaitText("Add file")

        rule.onNodeWithText("No documents yet").assertExists()
        rule.onNodeWithText("Take photo").assertExists()
        rule.onNodeWithText("No references yet").assertExists()
        rule.onNodeWithText("Add link").assertExists()
        rule.onAllNodesWithText("Open settings").assertCountEquals(0)
    }

    /**
     * An archived item (R69-10): its file is drawn, and the sections are not read-only — "Add file", "Take photo" and
     * "Add link" are all still offered, because a SupplyItem is never held. The file's bytes are deleted before the
     * draw (the section checks presence when its rows arrive, not on a tap), so the row says "Not on this device" and
     * its tap puts the same words in the screen's own snackbar: the detail's `SnackbarHost` (C26) is wired.
     */
    @Test fun anArchivedItemsSectionsDrawAndStayWritable() = onAFreshInstall {
        val tree = useFileBackedTree()
        val item = battery()
        runBlocking {
            val added = app.graph.addAttachment.run(
                AttachmentOwner.OfSupplyItem(item.id),
                AddAttachmentCommand(
                    displayName = "Example battery manual.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 4L,
                    capturedOn = "2026-05-01",
                ),
                ByteSource { "%PDF".byteInputStream() },
            )
            check(added is AttachmentResult.Ok) { "the file is added: $added" }
            app.graph.archiveSupplyItem.run(item.id, true)
        }
        // The item's bytes live under its own directory (`supply-items/<id>/`, C4); nothing else there is touched.
        val bytes = File(tree, "supply-items/${item.id.value}").walkBottomUp().filter { it.isFile }.toList()
        check(bytes.size == 1 && bytes.single().delete()) { "the file's bytes are deleted: $bytes" }
        drawDetail(item.id)
        rule.awaitText("Unarchive")
        rule.awaitText("DOCUMENTS · 1")

        rule.onNodeWithText("Example battery manual.pdf").assertExists()
        rule.onNodeWithText("Add file").assertExists()
        rule.onNodeWithText("Take photo").assertExists()
        rule.onNodeWithText("REFERENCES").assertExists()
        rule.onNodeWithText("Add link").assertExists()

        rule.awaitText("Not on this device")
        rule.onNodeWithText("Example battery manual.pdf").performScrollTo().performClick()
        rule.awaitText("Not on this device", count = 2)
    }
}

/** Points the graph's attachment seams at an ordinary directory, as `AttachmentsDeviceProofTest` does. */
private fun useFileBackedTree(): File {
    val context: Context = ApplicationProvider.getApplicationContext()
    val root = File(context.getExternalFilesDir(null), "supply-detail-proof").also {
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
