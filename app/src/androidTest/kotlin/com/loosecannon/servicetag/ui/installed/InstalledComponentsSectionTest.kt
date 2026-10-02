package com.loosecannon.servicetag.ui.installed

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
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.ui.asset.NAME_FIELD
import com.loosecannon.servicetag.ui.replace.ReplaceStrings
import com.loosecannon.servicetag.ui.supplies.LINKED_TO
import com.loosecannon.servicetag.ui.supplies.LINK_SUPPLY
import com.loosecannon.servicetag.ui.supplies.REMOVE_LINK
import com.loosecannon.servicetag.ui.supplies.SUPPLY_ITEM_GONE
import com.loosecannon.servicetag.ui.supplies.SupplyListRow
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #47 (C25–C27; row 53, the list and sheet cases): the asset's Installed components section drawn.
 * [InstalledComponentsList] and the sheets are pure functions of their arguments, so every case renders one directly,
 * as `SupplySurfacesTest` renders `AssetSuppliesList` and the role sheet; the state they draw is proven on the JVM
 * (rows 50 and 51). What only a device shows is the indent, the wording that reaches the semantics tree, the toggle,
 * a sheet's fields, sentences and buttons, and which controls a read-only section or sheet leaves out. The composition
 * cases are the next brief's.
 *
 * Every sentence comes from its one home (`InstalledComponentStrings.kt`), so a re-worded constant moves this test with
 * it. A section header and a badge draw their words upper-case, so that is what the tree carries.
 *
 * Emulator only, never a phone. No case reaches the store, so none needs a wipe.
 */
@RunWith(AndroidJUnit4::class)
class InstalledComponentsSectionTest {

    @get:Rule val rule = createComposeRule()

    private var opened: InstalledComponentId? = null
    private var installTaps = 0

    private val tray = row("ic-tray", "Example Battery Tray", depth = 0, quiet = installedOnDay("2025-03-04"))
    private val positionOne = row(
        "ic-one", "Position 1", depth = 1, inside = insideOf("Example Battery Tray"),
        quiet = listOf(compositionLine("4", "Example 12 V Battery"), "SN-EXAMPLE-01").joinToString(" · "),
    )
    private val cover = row("ic-cover", "Example Terminal Cover", depth = 2, inside = insideOf("Position 1"))
    private val pack = row("ic-pack", "Example Spare Pack", depth = 0, quiet = "", archived = true)
    private val oldTray = row("ic-old", "Example Old Tray", depth = 0, quiet = removedOnDay("2026-01-10"))
    private val positionThree = row(
        "ic-three", "Position 3", depth = 0,
        quiet = listOf(insideOf("Example Old Tray"), removedOnDay("2026-01-10")).joinToString(" · "),
    )

    private fun row(
        id: String,
        name: String,
        depth: Int,
        inside: String? = null,
        quiet: String = "",
        archived: Boolean = false,
    ) = InstalledComponentRowState(InstalledComponentId(id), name, depth, inside, quiet, archived)

    /** Draws the list; the toggle is live, so a case can open and close the removed rows. */
    private fun drawSection(
        rows: List<InstalledComponentRowState>,
        removed: List<InstalledComponentRowState> = emptyList(),
        readOnly: Boolean = false,
    ) {
        rule.setContent {
            ServiceTagTheme {
                var showRemoved by remember { mutableStateOf(false) }
                Column {
                    InstalledComponentsList(
                        rows = rows,
                        removed = removed,
                        showRemoved = showRemoved,
                        readOnly = readOnly,
                        onOpen = { opened = it.id },
                        onInstall = { installTaps += 1 },
                        onToggleRemoved = { showRemoved = !showRemoved },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** P47-1's header, each row's name and quiet line, an empty quiet line drawing nothing, and P47-2 absent. */
    @Test fun theSectionDrawsEachRowWithItsQuietLine() {
        drawSection(listOf(tray, positionOne, cover, pack))

        rule.onNodeWithText(INSTALLED_COMPONENTS_SECTION.uppercase()).assertIsDisplayed()
        rule.onNodeWithText("Example Battery Tray", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(installedOnDay("2025-03-04"), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(positionOne.quiet, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Example Terminal Cover", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Example Spare Pack", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText(NO_INSTALLED_COMPONENTS).assertCountEquals(0)
    }

    /** C25: each depth step indents the name 16 dp from its parent's. */
    @Test fun aNestedRowIsIndentedSixteenDpPerDepth() {
        drawSection(listOf(tray, positionOne, cover))

        val top = left("Example Battery Tray")
        assertEquals(16f, (left("Position 1") - top).value, 0.5f)
        assertEquals(32f, (left("Example Terminal Cover") - top).value, 0.5f)
    }

    /** C25: TalkBack cannot hear an indent, so a nested row says P47-5 naming its parent; a top row says nothing more. */
    @Test fun aNestedRowSaysInsideItsParent() {
        drawSection(listOf(tray, positionOne, cover))

        rule.onNodeWithContentDescription(insideOf("Example Battery Tray")).assertIsDisplayed()
        rule.onNodeWithContentDescription(insideOf("Position 1")).assertIsDisplayed()
        rule.onAllNodesWithContentDescription("Inside", substring = true).assertCountEquals(2)
    }

    /** The shipped "Archived" badge on the row whose direct SupplyItem is archived, and on no other. */
    @Test fun anArchivedLinkWearsTheBadge() {
        drawSection(listOf(tray, pack))

        rule.onAllNodesWithText("ARCHIVED").assertCountEquals(1)
    }

    /** An asset with nothing fitted says P47-2 under the header, and the add glyph is still there. */
    @Test fun anEmptySectionSaysNoInstalledComponentsAndStillOffersInstall() {
        drawSection(emptyList())

        rule.onNodeWithText(INSTALLED_COMPONENTS_SECTION.uppercase()).assertIsDisplayed()
        rule.onNodeWithText(NO_INSTALLED_COMPONENTS).assertIsDisplayed()
        rule.onNodeWithContentDescription(INSTALL_COMPONENT).assertIsDisplayed()
        rule.onAllNodesWithText(removedCount(0)).assertCountEquals(0)
    }

    /** C27: the add glyph is labelled P47-3 and reports its tap; a row's tap reports that row. */
    @Test fun theGlyphAndARowReportTheirTaps() {
        drawSection(listOf(tray, positionOne))

        rule.onNodeWithContentDescription(INSTALL_COMPONENT).performClick()
        assertEquals(1, installTaps)
        rule.onNodeWithText("Position 1").performClick()
        assertEquals(InstalledComponentId("ic-one"), opened)
    }

    /** C27, #77: a held asset's section draws its rows and its toggle and offers no glyph. */
    @Test fun aReadOnlySectionDrawsItsRowsAndToggleAndNoGlyph() {
        drawSection(listOf(tray, positionOne), removed = listOf(oldTray), readOnly = true)

        rule.onNodeWithText("Example Battery Tray", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Position 1", useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithContentDescription(INSTALL_COMPONENT).assertCountEquals(0)
        rule.onNodeWithText(removedCount(1)).performClick()
        rule.onNodeWithText("Example Old Tray", useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * R47-16: P47-18 counts the removed rows nothing replaced; they draw only while it is open, each with its P47-17
     * day, and P47-5 on the one whose parent is not current; closing it hides them again.
     */
    @Test fun theToggleCountsAndRevealsTheRemovedRows() {
        drawSection(listOf(tray), removed = listOf(oldTray, positionThree))

        rule.onAllNodesWithText("Example Old Tray", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText(removedCount(2)).performClick()
        rule.onNodeWithText("Example Old Tray", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(oldTray.quiet, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(positionThree.quiet, useUnmergedTree = true).assertIsDisplayed()

        rule.onNodeWithText(removedCount(2)).performClick()
        rule.onAllNodesWithText("Position 3", useUnmergedTree = true).assertCountEquals(0)
    }

    /** No removed row: no toggle. */
    @Test fun noRemovedRowDrawsNoToggle() {
        drawSection(listOf(tray))

        rule.onAllNodesWithText("Removed", substring = true).assertCountEquals(0)
    }

    private val cellId = SupplyId("si-cell")
    private val catalog = mapOf(
        cellId to SupplyListRow(cellId, "Example 12 V Battery", "Example Power Co. · EX-12", false),
        SupplyId("si-old") to SupplyListRow(SupplyId("si-old"), "Example Old Battery", "", true),
    )

    private fun rowSheet(current: Boolean) = RowSheetState(
        id = InstalledComponentId("ic-c"),
        name = "Position 1 C",
        current = current,
        supplyId = cellId,
        composition = emptyList(),
        serialOrLot = "SN-EXAMPLE-01",
        installedDay = if (current) ReplaceStrings.day("2026-01-20") else null,
        removedDay = if (current) null else ReplaceStrings.day("2026-02-01"),
        history = listOf(
            HistoryLineState(
                InstalledComponentId("ic-c"), "Position 1 C", installedOnDay("2026-01-20"), null,
                ReplaceStrings.replaces("Position 1 B"),
            ),
            HistoryLineState(
                InstalledComponentId("ic-b"), "Position 1 B", installedOnDay("2025-09-01"),
                ReplaceStrings.replacedBy("Position 1 C", "2026-01-20"), null,
            ),
        ),
    )

    private fun form(
        target: ComponentFormTarget,
        title: String,
        name: String = "",
        inside: String? = null,
        supplyId: SupplyId? = null,
        subtreeToo: Boolean = false,
        linkProblem: String? = null,
    ) = ComponentFormState(
        target = target, title = title, inside = inside, name = name, supplyId = supplyId, composition = emptyList(),
        serialOrLot = "", date = "", notes = "", subtreeToo = subtreeToo, linkProblem = linkProblem,
    )

    /**
     * C26, N-12: the row sheet draws the stored facts — the link in P15-22's words (a tap opens it), the serial or lot,
     * the install day — and "History" newest first with #86's words, then a current row's four actions.
     */
    @Test fun theRowSheetDrawsItsFactsHistoryAndActions() {
        var openedSupply: SupplyId? = null
        var insideTaps = 0
        rule.setContent {
            ServiceTagTheme {
                InstalledComponentRowSheet(
                    sheet = rowSheet(current = true), supplies = catalog, offersWrites = true,
                    onOpenSupply = { openedSupply = it }, onInstallInside = { insideTaps += 1 }, onReplace = {},
                    onRemove = {}, onEdit = {}, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onAllNodesWithText("Position 1 C").assertCountEquals(2)
        rule.onNodeWithText(SERIAL_OR_LOT).assertExists()
        rule.onNodeWithText("SN-EXAMPLE-01").assertExists()
        rule.onNodeWithText(INSTALLED_ON).assertExists()
        rule.onNodeWithText(ReplaceStrings.day("2026-01-20")).assertExists()
        rule.onNodeWithText(COMPONENT_HISTORY.uppercase()).assertExists()
        rule.onNodeWithText(listOf(installedOnDay("2026-01-20"), ReplaceStrings.replaces("Position 1 B")).joinToString(" · "))
            .assertExists()
        rule.onNodeWithText(
            listOf(installedOnDay("2025-09-01"), ReplaceStrings.replacedBy("Position 1 C", "2026-01-20")).joinToString(" · "),
        ).assertExists()
        rule.onAllNodesWithText(INSTALL_DATE_NOT_RECORDED).assertCountEquals(0)
        rule.onNodeWithText(REPLACE_COMPONENT).assertExists()
        rule.onNodeWithText("Remove").assertExists()
        rule.onNodeWithText("Edit").assertExists()

        rule.onNodeWithText(LINKED_TO.format("Example 12 V Battery")).performClick()
        assertEquals(cellId, openedSupply)
        rule.onNodeWithText(INSTALL_INSIDE).performScrollTo().performClick()
        assertEquals(1, insideTaps)
    }

    /** C26, #77: a removed row's sheet says P47-16 and its removal day and offers "Edit" only; a held asset's offers nothing. */
    @Test fun aRemovedRowOffersEditOnlyAndAHeldOneNothing() {
        val offers = mutableStateOf(true)
        rule.setContent {
            ServiceTagTheme {
                InstalledComponentRowSheet(
                    sheet = rowSheet(current = false), supplies = catalog, offersWrites = offers.value,
                    onOpenSupply = {}, onInstallInside = {}, onReplace = {}, onRemove = {}, onEdit = {}, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(INSTALL_DATE_NOT_RECORDED).assertExists()
        rule.onNodeWithText(REMOVED_ON).assertExists()
        rule.onNodeWithText(ReplaceStrings.day("2026-02-01")).assertExists()
        rule.onNodeWithText("Edit").assertExists()
        rule.onAllNodesWithText(INSTALL_INSIDE).assertCountEquals(0)
        rule.onAllNodesWithText(REPLACE_COMPONENT).assertCountEquals(0)
        rule.onAllNodesWithText("Remove").assertCountEquals(0)

        offers.value = false
        rule.waitForIdle()
        rule.onAllNodesWithText("Edit").assertCountEquals(0)
        rule.onNodeWithText(COMPONENT_HISTORY.uppercase()).assertExists()
    }

    /**
     * C26: the install sheet (P47-3) inside a row says P47-5, offers "Link supply", enables Save once the name has text,
     * and Cancel closes it without a save.
     */
    @Test fun theInstallSheetEnablesSaveForANameAndCancelWritesNothing() {
        var saves = 0
        var dismissed = false
        var linkTaps = 0
        rule.setContent {
            ServiceTagTheme {
                var sheet by remember {
                    mutableStateOf(
                        form(
                            ComponentFormTarget.Install(InstalledComponentId("ic-tray")), INSTALL_COMPONENT,
                            inside = insideOf("Example Battery Tray"),
                        ),
                    )
                }
                ComponentFormSheet(
                    form = sheet, supplies = catalog, onName = { sheet = sheet.copy(name = it) },
                    onLink = { linkTaps += 1 }, onUnlink = {}, onSerialOrLot = {}, onDate = {}, onNotes = {},
                    onSave = { saves += 1 }, onDismiss = { dismissed = true },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(INSTALL_COMPONENT).assertIsDisplayed()
        rule.onNodeWithText(insideOf("Example Battery Tray")).assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText(SERIAL_OR_LOT)).assertExists()
        rule.onNode(hasSetTextAction() and hasText(INSTALLED_ON)).assertExists()
        rule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(LINK_SUPPLY).performScrollTo().performClick()
        assertEquals(1, linkTaps)

        rule.onNode(hasSetTextAction() and hasText(NAME_FIELD)).performTextInput("Position 1")
        rule.onNodeWithText("Save").performScrollTo().assertIsEnabled()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(dismissed)
        assertEquals(0, saves)
    }

    /**
     * R47-17b, C-1: the replace sheet (P47-10) draws its draft — the name and an archived link with its badge and
     * "Remove link" — P15-20 under the link after a refused save, P47-12, and the "Replace" button.
     */
    @Test fun theReplaceSheetDrawsTheDraftWithItsArchivedLinkAndSaysReplace() {
        var unlinks = 0
        var saves = 0
        rule.setContent {
            ServiceTagTheme {
                ComponentFormSheet(
                    form = form(
                        ComponentFormTarget.Replace(InstalledComponentId("ic-one")), replaceTitle("Position 1"),
                        name = "Position 1", supplyId = SupplyId("si-old"), subtreeToo = true, linkProblem = SUPPLY_ITEM_GONE,
                    ),
                    supplies = catalog, onName = {}, onLink = {}, onUnlink = { unlinks += 1 }, onSerialOrLot = {},
                    onDate = {}, onNotes = {}, onSave = { saves += 1 }, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(replaceTitle("Position 1")).assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("Position 1")).assertExists()
        rule.onNodeWithText(LINKED_TO.format("Example Old Battery")).assertExists()
        rule.onNodeWithText("ARCHIVED").assertExists()
        rule.onNodeWithText(SUPPLY_ITEM_GONE).assertExists()
        rule.onNodeWithText(SUBTREE_REMOVED_TOO).assertExists()
        rule.onAllNodesWithText("Save").assertCountEquals(0)
        rule.onNodeWithText(REMOVE_LINK).performScrollTo().performClick()
        assertEquals(1, unlinks)
        rule.onNodeWithText(REPLACE_COMPONENT).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, saves)
    }

    /**
     * C26, R47-6: the remove sheet (P47-11) asks only for "Removed on" (no typed confirmation), says P47-12 for a row
     * with current children and P47-20 under the date, and reports "Remove".
     */
    @Test fun theRemoveSheetDrawsP47_12AndItsDateProblem() {
        var removes = 0
        rule.setContent {
            ServiceTagTheme {
                RemoveComponentSheet(
                    sheet = RemoveSheetState(
                        rowId = InstalledComponentId("ic-tray"), title = removeTitle("Example Battery Tray"),
                        removedOn = "2025-01-01", subtreeToo = true, dateProblem = REMOVAL_BEFORE_INSTALL,
                    ),
                    onRemovedOn = {}, onRemove = { removes += 1 }, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(removeTitle("Example Battery Tray")).assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.onNode(hasSetTextAction() and hasText(REMOVED_ON)).assertExists()
        rule.onNodeWithText(REMOVAL_BEFORE_INSTALL).assertExists()
        rule.onNodeWithText(SUBTREE_REMOVED_TOO).assertExists()
        rule.onNodeWithText("Remove").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, removes)
    }

    private fun left(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().left
}
