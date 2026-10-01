package com.loosecannon.servicetag.ui.supplies

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.usecase.AddAssetSupplyCommand
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.SpecificationInput
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.loosecannon.servicetag.ui.asset.MODEL_FIELD

/**
 * #15 (C29–C31, row 61): the Supplies screens on the real back stack. What only a device shows is that the new
 * `Route` keys really draw a screen from the Maintenance shell's fifth row, and that the list and the detail draw
 * the state their view models are proven to hold (rows 59, 60) under the ratified words (§5).
 *
 * B7a's cases are the list's and the detail's; B7b adds the editor's here. Every string is imported from its one
 * home (`SupplyStrings.kt`, the shipped field labels), so a re-worded constant moves this test with it.
 *
 * Emulator only — the suite wipes app data. `clearInstall` predates #15 and leaves the SupplyItem catalog in
 * place, so this class clears the catalog itself, before and after each case: after `clearInstall`'s asset wipe,
 * which takes every applicability row by its CASCADE and so lets the catalog go past `asset_supply`'s RESTRICT.
 */
@RunWith(AndroidJUnit4::class)
class SupplyScreensTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun freshInstall() = clearCatalog()

    @After fun leaveNoCatalog() = clearCatalog()

    private fun clearCatalog() {
        clearInstall()
        val graph = app.graph
        runBlocking { graph.uow.write { graph.supplyItems.deleteAll() } }
    }

    private fun save(
        name: String,
        manufacturer: String = "",
        partNumber: String = "",
        preferredUnit: String = "",
        specifications: List<SpecificationInput> = emptyList(),
    ): SupplyItem = runBlocking {
        app.graph.saveSupplyItem.run(
            null,
            SupplyItemCommand(
                name = name,
                category = "",
                manufacturer = manufacturer,
                model = "",
                partNumber = partNumber,
                preferredUnit = preferredUnit,
                notes = "",
                specifications = specifications,
            ),
        ).item
    }

    /** The Maintenance tab, then its fifth row, which is past the groups and so may need a scroll. */
    private fun openSupplies() {
        rule.onNode(hasText("Maintenance") and hasClickAction()).performClick()
        rule.awaitText(SUPPLIES_SECTION)
        rule.onNode(hasTextExactly(SUPPLIES_SECTION) and hasClickAction()).performScrollTo().performClick()
    }

    /**
     * The fifth row opens the list: every item, the archived one marked with the shipped "Archived" badge and kept
     * in its name-order place, the quiet line of the manufacturer and the P15-4 field, and the P15-2 add button.
     */
    @Test fun theMaintenanceRowOpensTheListAndAnArchivedRowIsMarked() {
        val carbon = save("Example Carbon Block", partNumber = "CB-5")
        save("Example Prefilter Cartridge", manufacturer = "Example Filters Co.", partNumber = "PF-10")
        save("Example RO Membrane")
        runBlocking { app.graph.archiveSupplyItem.run(carbon.id, archived = true) }

        openSupplies()

        rule.awaitText("Example RO Membrane")
        rule.onNodeWithText("Example Carbon Block").assertIsDisplayed()
        rule.onNodeWithText("Example Filters Co. · PF-10").assertIsDisplayed()
        rule.onNodeWithText("CB-5").assertIsDisplayed()
        // The badge draws its label upper-case; "Archived" is only its content description (the shipped badge tests).
        rule.onNode(hasText("Example Carbon Block") and hasText("ARCHIVED")).assertIsDisplayed()
        rule.onAllNodesWithText("ARCHIVED").assertCountEquals(1)
        rule.onNodeWithText(SUPPLY_ITEM).assertIsDisplayed()
        rule.onAllNodesWithText(NO_SUPPLIES_YET).assertCountEquals(0)
    }

    /** An empty catalog says P15-3, and the add button is still there: it is the only way to make the first one. */
    @Test fun anEmptyCatalogSaysSo() {
        openSupplies()

        rule.awaitText(NO_SUPPLIES_YET)
        rule.onNodeWithText(SUPPLY_ITEM).assertIsDisplayed()
    }

    /**
     * A row opens the detail: the identity facts that are there under their labels, "Specifications" with one
     * "label — value unit" line each, "Used by" with the asset and the role; Archive flips to Unarchive and writes
     * the one column; and a "Used by" row opens that asset's own screen.
     */
    @Test fun theDetailDrawsItsFactsSpecificationsAndUsersAndARowOpensTheAsset() {
        val system = runBlocking { app.graph.createAsset.run(AssetCommand(name = "Example RO System", category = "Water")) }
        val cartridge = save(
            name = "Example Prefilter Cartridge",
            manufacturer = "Example Filters Co.",
            partNumber = "PF-10",
            preferredUnit = "cartridge",
            specifications = listOf(
                SpecificationInput(id = null, key = "", label = "Micron rating", value = "5", unit = "µm"),
                SpecificationInput(id = null, key = "", label = "Connection", value = "Quick-connect", unit = ""),
            ),
        )
        runBlocking { app.graph.addAssetSupply.run(AddAssetSupplyCommand(system.id, cartridge.id, "Stage 1")) }

        openSupplies()
        rule.onNode(hasText("Example Prefilter Cartridge") and hasClickAction()).performClick()

        rule.awaitText(PART_NUMBER_FIELD)
        rule.onNodeWithText("PF-10").assertIsDisplayed()
        rule.onNodeWithText(PREFERRED_UNIT_FIELD).assertIsDisplayed()
        rule.onNodeWithText("cartridge").assertIsDisplayed()
        rule.onNodeWithText(SPECIFICATIONS_SECTION).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Micron rating — 5 µm").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Connection — Quick-connect").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(USED_BY_SECTION).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Stage 1").performScrollTo().assertIsDisplayed()
        // Only the fields that are there: no model was given, and neither empty line is drawn.
        rule.onAllNodesWithText(MODEL_FIELD).assertCountEquals(0)
        rule.onAllNodesWithText(NO_SPECIFICATIONS).assertCountEquals(0)
        rule.onAllNodesWithText(NOT_USED_BY_ANY_ASSET).assertCountEquals(0)

        rule.onNodeWithText("Archive").performClick()
        rule.awaitText("Unarchive")
        check(runBlocking { app.graph.supplyItems.get(cartridge.id)!!.archivedAt } != null) { "the archive wrote nothing" }

        rule.onNode(hasText("Example RO System") and hasClickAction()).performScrollTo().performClick()
        // The asset's own screen, which only an Asset has.
        rule.awaitText("SERVICE RECORD")
    }

    /** An item with a name and nothing else: its detail draws P15-9 and P15-11, and no identity fact at all. */
    @Test fun aBareItemsDetailSaysItsTwoEmptyLines() {
        val bare: SupplyId = save("Example Sediment Cartridge").id

        openSupplies()
        rule.onNode(hasText("Example Sediment Cartridge") and hasClickAction()).performClick()

        rule.awaitText(NO_SPECIFICATIONS)
        rule.awaitText(NOT_USED_BY_ANY_ASSET)
        rule.onAllNodesWithText(PART_NUMBER_FIELD).assertCountEquals(0)
        rule.onAllNodesWithText(PREFERRED_UNIT_FIELD).assertCountEquals(0)
        check(runBlocking { app.graph.assetSupplies.forSupply(bare) }.isEmpty())
    }
}
