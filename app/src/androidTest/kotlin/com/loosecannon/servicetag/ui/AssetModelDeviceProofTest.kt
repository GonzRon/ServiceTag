package com.loosecannon.servicetag.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.ui.asset.NO_PARENT
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Phase 2B-2's device proof: spec §12's instrumented rows, driven through the real screens on real
 * hardware. Same shape as `JournalDeviceProofTest` and `EditorsDeviceProofTest` — an empty Compose
 * rule, a destructive `@Before`, one test per scenario, each cold-starting the screen it needs
 * (the `servicetag://asset/<id>` deep link a tag tap takes, or a plain launch onto the dashboard).
 *
 * What the suite deliberately does *not* claim:
 *
 * - **The assets a scenario starts from are created in-process**, through
 *   `createAsset.run(AssetCommand(...))` — the same command the editor builds. The new-asset form
 *   itself is driven through the UI in the two rows that are *about* the form (the category hint
 *   and the price), and `AppSmokeTest` already proves it end to end. A parent is given to a child
 *   as `parentAssetId` for the same reason: the picker is what rows (b) and (c) exercise, and a
 *   row about COMPONENTS should not fail because a dropdown moved.
 * - **A badge is attributed to its row by the row's own merged node, not by on-screen adjacency.**
 *   `AssetListRow` is a `clickable` Row, so label and badge merge into one node and
 *   `hasText(name) and hasText(badge)` is a real claim about that row. The plate's badges are
 *   asserted by presence, because the plate is the only thing on screen that has them.
 * - **The season window is computed from the phone's own `LocalDate.now()`**, not injected:
 *   `AppGraph.clock` is a `val` reading `System.currentTimeMillis()` and 2B-2 added no seam for a
 *   fake today. A window two days wide starting 30 days out therefore cannot contain today, and a
 *   window from yesterday to tomorrow always does — on any date the suite is ever run.
 */
class AssetModelDeviceProofTest {

    @get:Rule val rule = createEmptyComposeRule()

    /** Destructive, exactly as the 1C, 2A and 2B-1 suites: no preferences, no rows, no journal. */
    @Before fun freshInstall() = clearInstall()

    // ---------------------------------------------------------------- scenario (a)

    /**
     * A parent names its components and a component names its parent, and the "Part of" line is
     * the way back up: one level each way, reached by tapping (spec §5, §9).
     */
    @Test fun aParentListsItsComponentsAndEachComponentNamesTheParent() {
        val parent = newAsset("Solar system")
        newAsset("Inverter", parentId = parent)
        val battery = newAsset("Battery bank", parentId = parent)

        openAsset(parent).use {
            rule.awaitText("COMPONENTS")
            rule.onNodeWithText("Inverter").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Battery bank").performScrollTo().assertIsDisplayed()
            // The parent is nobody's component, so it has no "Part of" line of its own.
            rule.onAllNodesWithText("Part of Solar system").assertCountEquals(0)
        }

        openAsset(battery).use {
            rule.awaitText("Part of Solar system")
            rule.onNodeWithText("Part of Solar system").assertIsDisplayed()

            // Tapping the line opens the parent: its COMPONENTS is what proves we arrived.
            rule.onNodeWithText("Part of Solar system").performClick()
            rule.awaitText("Inverter")
            rule.onNodeWithText("Inverter").performScrollTo().assertIsDisplayed()
            rule.onAllNodesWithText("Part of Solar system").assertCountEquals(0)
        }
    }

    // ---------------------------------------------------------------- scenario (b)

    /**
     * Reparenting a component under its sibling, through the picker, and the picker's own rule:
     * an asset is never offered a parent that sits underneath it, so choosing one cannot be the
     * move that makes the cycle (spec §5).
     */
    @Test fun aComponentIsReparentedUnderItsSiblingAndNoDescendantIsOffered() {
        val parent = newAsset("Solar system")
        newAsset("Inverter", parentId = parent)
        val battery = newAsset("Battery bank", parentId = parent)

        openAsset(battery).use {
            rule.awaitText("Part of Solar system")
            rule.openEditor()

            rule.awaitText("PLACEMENT")
            rule.choice("Part of").performScrollTo().performClick()
            rule.onNode(hasText("Inverter") and hasClickAction()).performClick()
            rule.onNodeWithText("Save").performClick()

            // Back on the component: it is part of the sibling now, not of the old root.
            rule.awaitText("Part of Inverter")
            rule.onAllNodesWithText("Part of Solar system").assertCountEquals(0)

            // Up one level: the new parent lists it.
            rule.onNodeWithText("Part of Inverter").performClick()
            rule.awaitText("COMPONENTS")
            rule.onNodeWithText("Battery bank").performScrollTo().assertIsDisplayed()

            // Up again, and open the root's own picker.
            rule.awaitText("Part of Solar system")
            rule.onNodeWithText("Part of Solar system").performClick()
            rule.awaitText("COMPONENTS")
            rule.openEditor()

            rule.awaitText("PLACEMENT")
            rule.choice("Part of").performScrollTo().performClick()
            // The menu is open once the anchor's "None" has a second, clickable twin in the list.
            rule.awaitText(NO_PARENT, count = 2)
            // Neither descendant is on offer — not the child, not the grandchild.
            rule.onAllNodesWithText("Inverter").assertCountEquals(0)
            rule.onAllNodesWithText("Battery bank").assertCountEquals(0)
        }
    }

    // ---------------------------------------------------------------- scenario (c)

    /**
     * Delete refuses a parent and says which components are in the way; archive does not, and
     * archiving a parent leaves its children exactly as active as they were (spec §5, §7).
     */
    @Test fun deletingAParentIsRefusedByNameAndArchivingItLeavesTheChildrenActive() {
        val parent = newAsset("Solar system")
        newAsset("Inverter", parentId = parent)
        newAsset("Battery bank", parentId = parent)

        openAsset(parent).use {
            rule.awaitText("COMPONENTS")
            rule.openOverflow()
            rule.onNodeWithText("Delete").performClick()

            rule.awaitText("Delete Solar system?")
            rule.field("Type Solar system to confirm").performTextInput("Solar system")
            rule.onNodeWithText("Delete").performClick()

            // Refused, with both components named in the one line the dialog shows.
            rule.awaitText("Components first")
            rule.onNode(
                hasText("Inverter", substring = true) and hasText("Battery bank", substring = true),
            ).assertIsDisplayed()
            rule.onNodeWithText("OK").performClick()

            // Nothing was written: the parent and both components are still there.
            rule.awaitText("COMPONENTS")
            rule.onNodeWithText("Inverter").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Battery bank").performScrollTo().assertIsDisplayed()

            // Archive is the offered way out, and it takes only the parent.
            rule.openOverflow()
            rule.onNodeWithText("Archive").performClick()
            rule.awaitText("ARCHIVED")
        }

        openApp().use {
            rule.openAssetsTab()
            // The default list hides the archived parent and, Components being off, both children:
            // what is left says so rather than claiming there is nothing active.
            rule.awaitText("Only components here. Turn on Components to see them.")
            rule.onAllNodesWithText("Inverter").assertCountEquals(0)

            // Components on: both children, still active and unbadged, and still no parent.
            rule.onNodeWithText("Components").performClick()
            rule.awaitText("Inverter")
            rule.onNodeWithText("Inverter").assertIsDisplayed()
            rule.onNodeWithText("Battery bank").assertIsDisplayed()
            rule.onAllNodesWithText("ARCHIVED").assertCountEquals(0)
            rule.onAllNodesWithText("Solar system").assertCountEquals(0)

            // With the archived tail shown, the badge is on the parent and on neither child.
            rule.onNodeWithText("Archived").performClick()
            rule.awaitText("Solar system")
            rule.onNode(hasText("Solar system") and hasText("ARCHIVED")).assertIsDisplayed()
            rule.onAllNodesWithText("ARCHIVED").assertCountEquals(1)
        }
    }

    // ---------------------------------------------------------------- scenario (d)

    /**
     * A season window has exactly one effect in 2B-2: it says OUT OF SEASON, on the asset and on
     * its list row, and only while today is outside it (spec §6).
     */
    @Test fun anOutOfSeasonWindowBadgesTheAssetAndItsRowWhileAnInSeasonOneDoesNot() {
        val today = LocalDate.now()
        val outOfSeason = newAsset(
            "Snowblower",
            seasonStart = today.plusDays(30).asMmdd(),
            seasonEnd = today.plusDays(31).asMmdd(),
        )
        val inSeason = newAsset(
            "Mower",
            seasonStart = today.minusDays(1).asMmdd(),
            seasonEnd = today.plusDays(1).asMmdd(),
        )

        openAsset(outOfSeason).use {
            rule.awaitText("OUT OF SEASON")
            rule.onNodeWithText("OUT OF SEASON").assertIsDisplayed()
        }

        openAsset(inSeason).use {
            rule.awaitText("COMPONENTS")
            rule.onAllNodesWithText("OUT OF SEASON").assertCountEquals(0)
        }

        openApp().use {
            rule.openAssetsTab()
            rule.awaitText("Snowblower")
            rule.onNode(hasText("Snowblower") and hasText("OUT OF SEASON")).assertIsDisplayed()
            rule.onAllNodesWithText("OUT OF SEASON").assertCountEquals(1)
        }
    }

    // ---------------------------------------------------------------- scenario (e)

    /**
     * A category suggestion pre-selects a template while nobody has chosen one, and stops the
     * moment somebody has: the hint is a hint and never an override (spec §8).
     */
    @Test fun aCategoryHintPreSelectsATemplateButNeverOverridesAnExplicitChoice() {
        openApp().use {
            rule.openAssetsTab()
            rule.onNodeWithContentDescription("Add asset").performClick()
            rule.awaitText("IDENTITY")
            rule.field("Name").performTextInput("Water maker")

            // "RO system" carries ro_water, so the chip row picks RO water on its own.
            rule.field("Category").performScrollTo().performTextInput("RO system")
            rule.pickSuggestion("RO system")
            rule.templateChip("RO water").assertIsSelected()

            // Choose a template by hand, then change the category to one that hints at another.
            rule.templateChip("Hot tub").performClick()
            rule.templateChip("Hot tub").assertIsSelected()
            rule.field("Category").performScrollTo().performTextReplacement("Generator")
            rule.pickSuggestion("Generator")

            // "Generator" hints at power_equipment; the explicit choice stands.
            rule.templateChip("Hot tub").assertIsSelected()
            rule.templateChip("Power equipment").assertIsNotSelected()
        }
    }

    // ---------------------------------------------------------------- scenario (f)

    /**
     * Retirement is a date and it commits on its own: the event offered afterwards is a follow-on
     * that can be declined without undoing anything, and unretiring is the only thing that does
     * (spec §7).
     */
    @Test fun retirementCommitsBeforeTheEventIsOfferedAndDecliningItChangesNothing() {
        val id = newAsset("Generator")

        openAsset(id).use {
            rule.awaitText("COMPONENTS")
            rule.openOverflow()
            rule.onNodeWithText("Retire").performClick()

            // The date field opens on today, which the user may then backdate.
            rule.awaitText("Retire this asset?")
            rule.onNodeWithText(LocalDate.now().toString()).assertIsDisplayed()
            rule.onNodeWithText("Retire").performClick()

            // The offer comes after the write, so declining it leaves the asset retired.
            rule.awaitText("Log what happened?")
            rule.onNodeWithText("Not now").performClick()
            rule.awaitText("RETIRED")
            rule.onNodeWithText("RETIRED").assertIsDisplayed()

            rule.openOverflow()
            rule.onNodeWithText("Unretire").performClick()
            rule.awaitGone("RETIRED")
        }
    }

    // ---------------------------------------------------------------- scenario (g)

    /**
     * Two tabs and no third: Scan is not a destination in the bottom bar any more, and the reader
     * it used to be lives under Settings as a utility you can get back out of (spec §9, D12 §16).
     */
    @Test fun theBottomBarHasTwoTabsAndTheTagReaderLivesUnderSettings() {
        openApp().use {
            rule.awaitText("ServiceTag")
            rule.onAllNodes(hasClickAction() and (hasText("Dashboard") or hasText("Assets")))
                .assertCountEquals(2)
            rule.onAllNodes(hasClickAction() and hasText("Scan")).assertCountEquals(0)

            rule.onNodeWithContentDescription("Settings").performClick()
            rule.awaitText("Read / inspect tag")
            rule.onNodeWithText("Read / inspect tag").performScrollTo().performClick()

            rule.awaitText("READY TO SCAN")
            rule.onNodeWithText("READY TO SCAN").assertIsDisplayed()

            // Back lands on Settings, not on an empty stack and not on the dashboard.
            rule.onNodeWithContentDescription("Back").performClick()
            rule.awaitGone("READY TO SCAN")
            rule.onNodeWithText("Settings").assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- scenario (h)

    /**
     * A price is minor units and a currency, both the way in and the way out: grouping typed by
     * hand is stripped, and the stored amount comes back at the currency's own precision (§4).
     */
    @Test fun aGroupedPriceIsStoredAsMinorUnitsAndShownAtTheCurrencysPrecision() {
        openApp().use {
            rule.openAssetsTab()
            rule.onNodeWithContentDescription("Add asset").performClick()
            rule.awaitText("IDENTITY")

            rule.field("Name").performTextInput("Well pump")
            rule.field("Currency").performScrollTo().performTextReplacement("USD")
            rule.field("Price").performScrollTo().performTextInput("1,234.5")
            rule.onNodeWithText("Save").performClick()

            rule.awaitText("DETAILS")
            rule.onNodeWithText("1234.50 USD").performScrollTo().assertIsDisplayed()
        }
    }
}

// -------------------------------------------------------------------- fixtures

/** How long a device assertion waits for a Room flow, a navigation or a recomposition to land. */
private const val WAIT_MS = 10_000L

/**
 * Created in-process through the very command the editor builds, so a row about the asset screen
 * does not depend on the shape of the form (spec §4).
 */
private fun newAsset(
    name: String,
    parentId: String? = null,
    category: String = "",
    seasonStart: String? = null,
    seasonEnd: String? = null,
    templateKey: String? = null,
): String = runBlocking {
    app.graph.createAsset.run(
        AssetCommand(
            name = name,
            category = category,
            parentAssetId = parentId?.let(::AssetId),
            seasonStartMmdd = seasonStart,
            seasonEndMmdd = seasonEnd,
        ),
        templateKey,
    ).id.value
}

/** The cold-start path a tag tap takes, which is also the one `ActivityScenario` can track (1C). */
private fun openAsset(id: String): ActivityScenario<MainActivity> {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("servicetag://asset/$id"))
        .setClass(context, MainActivity::class.java)
    return ActivityScenario.launch(intent)
}

/** A plain launch: the dashboard with the bottom bar, which is where the nav rows start. */
private fun openApp(): ActivityScenario<MainActivity> =
    ActivityScenario.launch(MainActivity::class.java)

/** `MM-DD`, the way a season boundary is stored (spec §6). */
private fun LocalDate.asMmdd(): String = "%02d-%02d".format(monthValue, dayOfMonth)

// -------------------------------------------------------------------- driving the screens

/** Waits until nothing on screen carries [text] any more. */
private fun ComposeTestRule.awaitGone(text: String) {
    waitUntil(WAIT_MS) { onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
}

/**
 * One labelled field of a form. A Material text field merges its label into its own semantics
 * node, so the label is how a field is named here — the editors carry no test tags of their own,
 * and the label is what the person filling the form reads (2B-1).
 */
private fun ComposeTestRule.field(label: String): SemanticsNodeInteraction =
    onNode(hasSetTextAction() and hasText(label))

/**
 * A read-only picker's anchor, which is the same kind of field with no `SetText` action: tapping
 * it is what opens the `ExposedDropdownMenu` behind it.
 */
private fun ComposeTestRule.choice(label: String): SemanticsNodeInteraction =
    onNode(!hasSetTextAction() and hasClickAction() and hasText(label))

/** One template chip of the new-asset form, by the seed's own name. */
private fun ComposeTestRule.templateChip(name: String): SemanticsNodeInteraction =
    onNode(hasClickAction() and hasText(name)).also { it.performScrollTo() }

/**
 * Picks a category out of the suggestion menu rather than leaving it half-typed: the menu row is
 * the one node carrying that label which is not the field itself, and tapping it closes the menu
 * so the chips underneath are reachable again.
 */
private fun ComposeTestRule.pickSuggestion(label: String) {
    onNode(!hasSetTextAction() and hasClickAction() and hasText(label)).performClick()
}

/**
 * The asset's own editor, through the quick action rather than the overflow: both say "Edit" and
 * the grid's button is the one that is always on screen (2B-2 §10 puts the actions first).
 */
private fun ComposeTestRule.openEditor() {
    onNodeWithText("Edit").performScrollTo().performClick()
}

/** The detail screen's app-bar overflow, which is the first "More" in the tree. */
private fun ComposeTestRule.openOverflow() {
    onAllNodesWithContentDescription("More").onFirst().performClick()
}

/** The Assets tab of the bottom bar — the label the person taps, not a test tag. */
private fun ComposeTestRule.openAssetsTab() {
    onNode(hasClickAction() and hasText("Assets")).performClick()
}
