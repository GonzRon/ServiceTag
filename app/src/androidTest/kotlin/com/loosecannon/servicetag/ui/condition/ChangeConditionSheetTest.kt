package com.loosecannon.servicetag.ui.condition

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Change condition on a real Compose tree (spec §5.4, §10.1): titled S5, the three options with
 * their helpers and **none selected**, S14, S15 on today, and S16 held until an answer is picked —
 * then one row on Save, and none on Cancel. The rules are `ChangeConditionViewModelTest`'s; what is
 * here is that the ratified words are really drawn and the taps really reach them.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class ChangeConditionSheetTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private fun generator(graph: AppGraph): AssetId = runBlocking {
        graph.createAsset.run(AssetCommand(name = "Generator", category = "Power")).id
    }

    private fun sheetFor(graph: AppGraph, assetId: AssetId): MutableList<String> {
        val trail = mutableListOf<String>()
        rule.setContent {
            ServiceTagTheme { ChangeConditionSheet(graph = graph, assetId = assetId.value) { trail += "done" } }
        }
        return trail
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    @Test fun threeOptionsHelpersAndSave() {
        val graph = app.graph
        val generator = generator(graph)
        val trail = sheetFor(graph, generator)

        rule.awaitText("Save condition")
        rule.onNodeWithText("Condition").assertIsDisplayed()
        listOf(
            "Operational" to "Available for normal use.",
            "Degraded" to "Works, but with a known problem.",
            "Down" to "Not available for its intended use.",
        ).forEach { (option, helper) ->
            rule.onNodeWithText(option).assertIsDisplayed()
            rule.onNodeWithText(helper, useUnmergedTree = true).assertIsDisplayed()
        }
        // Nothing preselected, so nothing can be saved yet.
        rule.onAllNodes(isSelected()).assertCountEquals(0)
        rule.onNodeWithText("Save condition").assertIsNotEnabled()
        field("What is wrong? (optional)").assertIsDisplayed()
        field("When did this change?").assert(hasText(LocalDate.now().toString()))

        rule.onNodeWithText("Down").performClick()
        field("What is wrong? (optional)").performTextInput("Will not start")
        rule.onNodeWithText("Save condition").assertIsEnabled().performClick()

        rule.waitUntil(TIMEOUT_MS) { trail.isNotEmpty() }
        assertEquals(listOf("done"), trail)
        val row = runBlocking { graph.conditions.all().single() }
        assertEquals(generator, row.assetId)
        assertEquals(OperationalCondition.DOWN, row.condition)
        assertEquals("Will not start", row.reason)
        assertEquals(LocalDate.now().toString(), row.occurredOn)
        assertNull(row.occurredTime)
        assertEquals(ZoneId.systemDefault().id, row.tzId)
    }

    @Test fun cancel() {
        val graph = app.graph
        val generator = generator(graph)
        val trail = sheetFor(graph, generator)

        rule.awaitText("Save condition")
        rule.onNodeWithText("Degraded").performClick()
        field("What is wrong? (optional)").performTextInput("Runs rough")
        rule.onNodeWithText("Cancel").performClick()

        rule.waitUntil(TIMEOUT_MS) { trail.isNotEmpty() }
        assertEquals(listOf("done"), trail)
        assertEquals(0, runBlocking { graph.conditions.all().size })
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
