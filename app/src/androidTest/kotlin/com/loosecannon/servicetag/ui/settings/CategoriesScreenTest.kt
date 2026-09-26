package com.loosecannon.servicetag.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long a step waits for a Room flow, a dialog or a snackbar to land. */
private const val WAIT_MS = 10_000L

/**
 * Settings → Categories on a real Compose tree (#74, C17; AC 9). What only a real tree shows: that the
 * ratified words are drawn verbatim — the section headers in sentence case — that the built-ins are
 * rows with **no** menu, that the held Rename is really disabled, that a refused rename is drawn under
 * the field inside the dialog that stays open, and that an in-use delete asks nothing and says so on
 * the snackbar. The rules themselves are `CategoriesViewModelTest`'s.
 *
 * Every expected sentence is hard-coded. Store checks use JUnit's asserts, never Kotlin's `assert`,
 * which the platform runs disabled. Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class CategoriesScreenTest {

    @get:Rule val rule = createComposeRule()

    private val graph get() = app.graph

    @Before fun freshInstall() = clearInstall()

    /** Appliance, used by two assets; Spare, used by none (a row that outlived its assets). */
    private fun seed() = runBlocking {
        graph.createAsset.run(AssetCommand(name = "Pump 3", category = "Appliance"))
        graph.createAsset.run(AssetCommand(name = "Unit A", category = "appliance"))
        graph.categories.upsert(AssetCategory("spare", "Spare", 1L, 1L))
    }

    private fun show() {
        rule.setContent { ServiceTagTheme { CategoriesScreen(graph = graph, onBack = {}) } }
    }

    /** The overflow of the [index]th of the owner's rows, in the list's order: Appliance, then Spare. */
    private fun menuOf(index: Int) = rule.onAllNodesWithContentDescription("More")[index].performClick()

    private fun inDialog(text: String): SemanticsNodeInteraction =
        rule.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun nameField(): SemanticsNodeInteraction = rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))

    private fun awaitGone(text: String) =
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    @Test fun theOwnersRowsCarryTheirUsageAndTheBuiltInsHaveNoMenu() {
        seed()
        show()
        rule.awaitText("Spare")

        rule.onNodeWithText("Categories").assertIsDisplayed()
        rule.onNodeWithText("Your categories").assertIsDisplayed()
        rule.onNodeWithText("Appliance").assertIsDisplayed()
        rule.onNodeWithText("Used by 2 assets").assertIsDisplayed()
        rule.onNodeWithText("Spare").assertIsDisplayed()
        rule.onNodeWithText("Not used").assertIsDisplayed()
        // A row's name and usage line are one node to a screen reader; its menu is another.
        rule.onNode(hasText("Appliance") and hasText("Used by 2 assets")).assertIsDisplayed()
        rule.onNode(hasText("Spare") and hasText("Not used")).assertIsDisplayed()
        rule.onAllNodesWithText(
            "No categories of your own yet. Save an asset with a new category to add one.",
        ).assertCountEquals(0)

        rule.onNodeWithText("Built-in").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Built-in categories are always offered and cannot be renamed or deleted.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Generator").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Other").performScrollTo().assertIsDisplayed()

        // Two menus, one per row of the owner's; the twelve built-ins have none.
        rule.onAllNodesWithContentDescription("More").assertCountEquals(2)
    }

    @Test fun renameIsHeldWhileTheNameIsBlankOrUnchanged() {
        seed()
        show()
        rule.awaitText("Spare")
        menuOf(0)
        rule.onNodeWithText("Rename").performClick()
        rule.awaitText("Rename category")

        nameField().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Appliance")))
        inDialog("Rename").assertIsNotEnabled()
        nameField().performTextReplacement("   ")
        inDialog("Rename").assertIsNotEnabled()
        nameField().performTextReplacement(" Appliance ")
        inDialog("Rename").assertIsNotEnabled()
        nameField().performTextReplacement("Appliances")
        inDialog("Rename").assertIsEnabled()

        inDialog("Cancel").performClick()
        awaitGone("Rename category")
        assertEquals("Appliance", runBlocking { graph.categories.get("appliance") }?.display)
    }

    @Test fun aRefusedRenameIsSaidUnderTheFieldAndTheDialogStays() {
        seed()
        show()
        rule.awaitText("Spare")
        menuOf(1)
        rule.onNodeWithText("Rename").performClick()
        rule.awaitText("Rename category")
        nameField().performTextReplacement("APPLIANCE")
        inDialog("Rename").performClick()

        rule.awaitText("A category named Appliance already exists.")
        // Under the field, in the dialog, which is still up — never a snackbar under its scrim.
        inDialog("A category named Appliance already exists.").assertIsDisplayed()
        rule.onAllNodesWithText("A category named Appliance already exists.").assertCountEquals(1)
        nameField().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        inDialog("Rename category").assertIsDisplayed()
        assertEquals("Spare", runBlocking { graph.categories.get("spare") }?.display)
    }

    @Test fun anUnusedDeleteAsksThenRemovesTheRow() {
        seed()
        show()
        rule.awaitText("Spare")
        menuOf(1)
        rule.onNodeWithText("Delete").performClick()

        rule.awaitText("Delete this category?")
        inDialog("Spare is not used by any asset.").assertIsDisplayed()
        inDialog("Delete").performClick()

        awaitGone("Spare")
        rule.onAllNodesWithText("Delete this category?").assertCountEquals(0)
        rule.onAllNodesWithContentDescription("More").assertCountEquals(1)
        assertNull(runBlocking { graph.categories.get("spare") })
    }

    @Test fun anInUseDeleteSaysSoOnTheSnackbarAndRemovesNothing() {
        seed()
        show()
        rule.awaitText("Spare")
        menuOf(0)
        rule.onNodeWithText("Delete").performClick()

        rule.awaitText("Appliance is used by 2 assets. Change their category first.")
        rule.onAllNodesWithText("Delete this category?").assertCountEquals(0)
        rule.onNodeWithText("Appliance").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("More").assertCountEquals(2)
        assertNotNull(runBlocking { graph.categories.get("appliance") })
    }

    @Test fun anEmptyCatalogSaysHowACategoryIsAdded() {
        show()
        rule.awaitText("No categories of your own yet. Save an asset with a new category to add one.")
        rule.onAllNodesWithContentDescription("More").assertCountEquals(0)
        rule.onNodeWithText("Built-in").performScrollTo().assertIsDisplayed()
    }
}
