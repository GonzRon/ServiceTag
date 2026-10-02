package com.loosecannon.servicetag.ui.installed

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #47 (C25, C27; row 53, the list cases): the asset's Installed components section drawn. [InstalledComponentsList] is
 * a pure function of its arguments, so every case renders it directly, as `SupplySurfacesTest` renders
 * `AssetSuppliesList`; the state it draws is proven on the JVM (row 50). What only a device shows is the indent, the
 * wording that reaches the semantics tree, the toggle, and which controls a read-only section leaves out. The sheet
 * cases are the later briefs'.
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

    private fun left(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().left
}
