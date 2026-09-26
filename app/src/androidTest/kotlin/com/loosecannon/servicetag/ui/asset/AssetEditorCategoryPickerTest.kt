package com.loosecannon.servicetag.ui.asset

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long a step waits for a save, a Room flow or the menu to land. */
private const val WAIT_MS = 10_000L

/**
 * The asset editor's Category picker on a real Compose tree (#74 AC 1, C16): a category one editor
 * saved is offered by the next editor's menu, reading like a built-in. The menu is reached by typing
 * the category's prefix, as the owner would: unfiltered it holds the twelve built-ins and the saved
 * one, and the thirteenth item is clipped below the menu's height. The rule behind it — the choices
 * follow the catalog, and typing alone adds nothing — is `AssetViewModelsTest`'s.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetEditorCategoryPickerTest {

    @get:Rule val rule = createComposeRule()

    private val graph get() = app.graph

    /** The ids the editor finished with, as its host is told them. */
    private val saved = mutableListOf<String>()

    @Before fun freshInstall() = clearInstall()

    /** The editor on [initial]; the returned setter switches it to another asset (null is a new one). */
    private fun editor(initial: String?): (String?) -> Unit {
        var shown by mutableStateOf(initial)
        rule.setContent {
            ServiceTagTheme {
                AssetEditScreen(graph = graph, assetId = shown, onDone = { saved += it }, onBack = {})
            }
        }
        return { shown = it }
    }

    private fun field(label: String): SemanticsNodeInteraction = rule.onNode(hasSetTextAction() and hasText(label))

    /** A menu row: the one node carrying [label] that is not the field itself. */
    private fun offered(label: String) = !hasSetTextAction() and hasClickAction() and hasText(label)

    @Test fun aCategorySavedByOneEditorIsOfferedByTheNext() {
        val other = runBlocking { graph.createAsset.run(AssetCommand(name = "Pump 3")) }
        val show = editor(null)

        field("Name").performTextInput("Unit A")
        field("Category").performScrollTo().performTextInput("Appliance")
        // Nothing is offered for it yet: no choice starts with what was typed.
        rule.onAllNodes(offered("Appliance")).assertCountEquals(0)
        rule.onNodeWithText("Save asset").performScrollTo().performClick()
        rule.waitUntil(WAIT_MS) { saved.isNotEmpty() }
        assertEquals("Appliance", runBlocking { graph.categories.get("appliance") }?.display)

        // Another asset's editor: its Category is empty, and its prefix brings the saved one up.
        show(other.id.value)
        rule.waitUntil(WAIT_MS) {
            rule.onAllNodes(hasSetTextAction() and hasText("Pump 3")).fetchSemanticsNodes().isNotEmpty()
        }
        field("Category").performScrollTo().performTextInput("Appl")
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(offered("Appliance")).fetchSemanticsNodes().isNotEmpty() }

        rule.onNode(offered("Appliance")).performClick()
        field("Category").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Appliance")))
    }
}
