package com.loosecannon.servicetag.ui.asset

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B07 (owner instruction, 2026-09-23) — the quick filter left the Dashboard for the Assets screen.
 * The JVM cases pin every rule; what only a device can show is that the field takes a keystroke,
 * that the list redraws from it, and that the clear glyph puts it all back. Carries the two
 * scenarios `DashboardSearchTest` proved before the box moved, re-targeted at `AssetsScreen`.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetsSearchTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    /** A hot tub with a circulation pump under it, and the Assets screen drawn over that install. */
    private fun aSystemWithOnePart(archiveTheSystem: Boolean = false): AppGraph {
        val graph = app.graph
        runBlocking {
            val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
            graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))
            if (archiveTheSystem) graph.archiveAsset.run(tub.id)
        }
        rule.setContent {
            ServiceTagTheme {
                AssetsScreen(graph = graph, onOpenAsset = {}, onNewAsset = {})
            }
        }
        return graph
    }

    @Test fun aComponentIsHiddenUntilItIsSearchedForAndThenNamesItsSystem() {
        aSystemWithOnePart()

        // The system is listed; its pump is not. The box itself is named by the ratified
        // placeholder, which only shows while it is empty.
        rule.awaitText("Hot tub")
        rule.onAllNodesWithText("Circulation pump").assertCountEquals(0)
        rule.awaitText("Search assets and components")

        // One keystroke away. The hit names its system, and the system itself drops out because it
        // does not match — which is what proves the list is filtered and not merely extended.
        rule.onNode(hasSetTextAction()).performTextInput("circ")
        rule.awaitText("Circulation pump")
        rule.awaitText("Part of Hot tub")
        rule.onAllNodesWithText("Hot tub").assertCountEquals(0)

        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.awaitText("Hot tub")
        rule.onAllNodesWithText("Circulation pump").assertCountEquals(0)

        // A query that matches nothing is the one state the no-match sentence is for.
        rule.onNode(hasSetTextAction()).performTextInput("zzz")
        rule.awaitText("Nothing matches that.")
        rule.onAllNodesWithText("Hot tub").assertCountEquals(0)
    }

    /**
     * F1 — an empty list under an empty box, reached the way an owner reaches it on this screen:
     * archive the system, which leaves its still-active pump hidden by the blank-query rule, so the
     * chip's own honest "No active assets · N archived" is what shows — never a claim that a search
     * found nothing when nothing was searched for.
     */
    @Test fun anEmptyListUnderAnEmptyBoxIsNotToldItsSearchFoundNothing() {
        aSystemWithOnePart(archiveTheSystem = true)

        rule.awaitText("No active assets · 1 archived")
        rule.onAllNodesWithText("Hot tub").assertCountEquals(0)
        rule.onAllNodesWithText("Nothing matches that.").assertCountEquals(0)

        // And the sentence is still there for the query that earns it.
        rule.onNode(hasSetTextAction()).performTextInput("zzz")
        rule.awaitText("Nothing matches that.")
    }
}
