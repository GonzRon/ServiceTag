package com.loosecannon.servicetag.ui.supplies

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.ui.attachments.ROLE_HEADER
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
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
 * Emulator only, never a phone. No case reaches the store, so none needs a wipe (`clearInstall` wipes the catalog for
 * the classes that do).
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
}
