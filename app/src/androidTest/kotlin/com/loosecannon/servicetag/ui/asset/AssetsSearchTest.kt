package com.loosecannon.servicetag.ui.asset

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
 * **#73:** the box searches only what the Type, Components and Archived controls admit. Components
 * is off by default, so a component is listed once the owner turns it on, still naming its system;
 * a search that matches only a hidden row says which control is in the way and turns nothing on.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetsSearchTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    /** A hot tub with a circulation pump under it, and the Assets screen drawn over that install. */
    private fun aSystemWithOnePart(): AppGraph {
        val graph = app.graph
        runBlocking {
            val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
            graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))
        }
        rule.setContent {
            ServiceTagTheme {
                AssetsScreen(graph = graph, onOpenAsset = {}, onNewAsset = {})
            }
        }
        return graph
    }

    @Test fun aComponentIsListedAndNamesItsSystemAndASearchNarrowsToIt() {
        aSystemWithOnePart()

        // Under a blank query only the system is listed: Components is off by default. The box
        // itself is named by the ratified placeholder, which only shows while it is empty.
        rule.awaitText("Hot tub")
        rule.awaitText("Search assets")
        rule.onAllNodesWithText("Circulation pump").assertCountEquals(0)

        // One keystroke away, the only match is the hidden component: the list says which control
        // is in the way, and the search turns nothing on by itself.
        rule.onNode(hasSetTextAction()).performTextInput("circ")
        rule.awaitText("Matching assets are components. Turn on Components to see them.")
        rule.onAllNodesWithText("Circulation pump").assertCountEquals(0)

        // Components on: the hit is listed, naming its system, and the system it does not name
        // stays out — which is what proves the list is filtered and not merely reordered.
        rule.onNodeWithText("Components").performClick()
        rule.awaitText("Circulation pump")
        rule.awaitText("Part of Hot tub")
        rule.onAllNodesWithText("Hot tub").assertCountEquals(0)

        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.awaitText("Hot tub")
        rule.awaitText("Circulation pump")

        // A query that matches nothing is the one state the no-match sentence is for.
        rule.onNode(hasSetTextAction()).performTextInput("zzz")
        rule.awaitText("Nothing matches that.")
        rule.onAllNodesWithText("Hot tub").assertCountEquals(0)
        rule.onAllNodesWithText("Circulation pump").assertCountEquals(0)
    }

    /**
     * F1 — an empty list under an empty box, reached the way an owner reaches it on this screen:
     * archive the only asset, so the chip's own honest "No active assets · N archived" is what
     * shows — never a claim that a search found nothing when nothing was searched for.
     */
    @Test fun anEmptyListUnderAnEmptyBoxIsNotToldItsSearchFoundNothing() {
        val graph = app.graph
        runBlocking {
            val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
            graph.archiveAsset.run(tub.id)
        }
        rule.setContent {
            ServiceTagTheme {
                AssetsScreen(graph = graph, onOpenAsset = {}, onNewAsset = {})
            }
        }

        rule.awaitText("No active assets · 1 archived")
        rule.onAllNodesWithText("Hot tub").assertCountEquals(0)
        rule.onAllNodesWithText("Nothing matches that.").assertCountEquals(0)

        // And the sentence is still there for the query that earns it.
        rule.onNode(hasSetTextAction()).performTextInput("zzz")
        rule.awaitText("Nothing matches that.")
    }

    /**
     * Owner ruling §18.23 (B07 fix round 4) — a query that matches only an archived row, with the
     * Archived chip off, gets the archived-only hint (#73's wording) instead of "Nothing matches
     * that."; turning the chip on then lists the match, same as any other query.
     */
    @Test fun anArchivedOnlyMatchShowsTheHintAndTheChipListsIt() {
        val graph = app.graph
        runBlocking {
            val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
            graph.archiveAsset.run(tub.id)
        }
        rule.setContent {
            ServiceTagTheme {
                AssetsScreen(graph = graph, onOpenAsset = {}, onNewAsset = {})
            }
        }

        rule.onNode(hasSetTextAction()).performTextInput("hot")
        rule.awaitText("Matching assets are archived. Turn on Archived to see them.")
        rule.onAllNodesWithText("Hot tub").assertCountEquals(0)
        rule.onAllNodesWithText("Nothing matches that.").assertCountEquals(0)

        rule.onNodeWithText("Archived").performClick()
        rule.awaitText("Hot tub")
        rule.onAllNodesWithText("Matching assets are archived. Turn on Archived to see them.")
            .assertCountEquals(0)
    }
}
