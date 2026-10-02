package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.journal.CategoryCatalog
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * #73: the Assets list's compact Type, Components and Archived controls (plan
 * `docs/superpowers/plans/2026-09-26-issue-73-assets-filters.md` §3). The rules live in the view model
 * and are pinned on the JVM; what only a device can show is the row's layout, the Type chip's
 * accessibility, the menu, and each control redrawing the list.
 *
 * The layout cases draw [AssetsFilterRow] alone in a frame of a fixed width — a phone's content width
 * after the screen's 16dp gutters — under a [LocalDensity] whose font scale stands in for the Android
 * font-size setting (the #68 technique, `ActionGridTest`). A label is read from the unmerged tree, and
 * whether any of it was cut is its own `TextLayoutResult`. The screen cases draw [AssetsScreen] over
 * the app's Room-backed graph. Menu rows are scoped to the popup, since a category's name can also be
 * a row's subtitle.
 *
 * Emulator only (`emulator-5554`) — the screen cases wipe app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetsFiltersTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private class Frame(val width: Dp, val fontScale: Float, val typeLabel: String?)

    private var frame by mutableStateOf<Frame?>(null)

    /** AC 1, 2: at 380dp and scale 1.0 the three chips share one line, in order, inside the row. */
    @Test fun theThreeChipsShareOneRowInAPhoneFrame() {
        drawRow(PHONE_412, 1.0f)

        val chips = CHIPS.map(::bounds)
        val row = rule.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        chips.forEachIndexed { i, b ->
            assertEquals("${CHIPS[i]} shares the first chip's top", chips.first().top.value, b.top.value, HALF)
            assertInside(CHIPS[i], b, row)
        }
        assertTrue(
            "Type, Child assets, Archived from left to right, was ${chips.map { it.left }}",
            chips[0].left < chips[1].left && chips[1].left < chips[2].left,
        )
        CHIPS.forEach { assertOneWholeLine(it) }
    }

    /** AC 8: at scale 2.0 the row wraps — never clips — and every one-word label keeps one line. */
    @Test fun aLargeFontWrapsTheRowWithoutClipping() {
        drawRow(PHONE_412, 2.0f)

        val chips = CHIPS.map(::bounds)
        val row = rule.onNodeWithTag(ROW).getUnclippedBoundsInRoot()
        // A second line, not merely a taller chip: some chip starts at or below another's bottom.
        assertTrue(
            "the row wraps onto a second line, chips were $chips",
            chips.any { below -> chips.any { above -> below.top.value >= above.bottom.value - HALF } },
        )
        chips.forEachIndexed { i, b -> assertInside(CHIPS[i], b, row) }
        CHIPS.forEach { assertOneWholeLine(it) }
    }

    /**
     * C6, R73-3: the Type chip is a dropdown opener to accessibility, never a checkbox — its name is
     * always `Type`, its state is the choice (`All` while nothing is chosen), and it carries no
     * selection state (a `FilterChip` would set `Selected`).
     */
    @Test fun theTypeChipIsADropdownNotACheckbox() {
        drawRow(PHONE_412, 1.0f)

        typeChip()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        assertEquals(listOf("Type"), typeChipValue(SemanticsProperties.ContentDescription))
        assertEquals("All", typeChipValue(SemanticsProperties.StateDescription))

        drawRow(PHONE_412, 1.0f, typeLabel = "Pump")
        typeChip()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        assertEquals(listOf("Type"), typeChipValue(SemanticsProperties.ContentDescription))
        assertEquals("Pump", typeChipValue(SemanticsProperties.StateDescription))
        assertEquals("Pump", typeChipValue(SemanticsProperties.Text)?.joinToString { it.text })
    }

    /** AC 6: Components on lists the component with its existing `Part of <parent>` subtitle. */
    @Test fun componentsOnRestoresThePart() {
        runBlocking {
            val tub = app.graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
            app.graph.createAsset.run(AssetCommand(name = "Circulation pump", parentAssetId = tub.id))
        }
        drawScreen()

        rule.awaitText("Hot tub")
        rule.onAllNodesWithText("Circulation pump").assertCountEquals(0)
        rule.onNode(hasText("Child assets") and hasClickAction()).assertIsNotSelected()

        rule.onNode(hasText("Child assets") and hasClickAction()).performClick()
        rule.awaitText("Circulation pump")
        rule.onNode(hasText("Child assets") and hasClickAction()).assertIsSelected()
        rule.onNode(hasText("Circulation pump") and hasText("Part of Hot tub")).assertIsDisplayed()
        rule.onNodeWithText("Hot tub").assertIsDisplayed()
    }

    /**
     * AC 3, 9; C4: the menu is All, then the durable catalog in its own order — the built-ins, then
     * the owner's rows by name, one no asset uses included — and picking a category lists only its
     * rows while the chip shows, and states, the choice.
     */
    @Test fun theTypeMenuListsAllAndTheCatalogAndFilters() {
        runBlocking {
            app.graph.createAsset.run(AssetCommand(name = "Sump pump", category = "Pump"))
            app.graph.createAsset.run(AssetCommand(name = "Deck heater", category = "Patio"))
            app.graph.categories.upsert(AssetCategory(key = "attic", display = "Attic", createdAt = 1L, updatedAt = 1L))
        }
        drawScreen()
        rule.awaitText("Sump pump")
        rule.awaitText("Deck heater")

        typeChip().performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(menuItem()).fetchSemanticsNodes().isNotEmpty() }
        val offered = rule.onAllNodes(menuItem()).fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.Text].joinToString { it.text }
        }
        assertEquals(
            listOf(
                "All", "Generator", "Lawn mower", "Snowblower", "UPS", "Battery", "Inverter / charger",
                "Solar charge controller", "RO system", "Hot tub", "HVAC", "Pump", "Other", "Attic", "Patio",
            ),
            offered,
        )

        rule.onNode(menuItem() and hasText("Patio")).performScrollTo().performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText("Sump pump").fetchSemanticsNodes().isEmpty() }
        rule.onAllNodes(menuItem()).assertCountEquals(0)
        rule.onNodeWithText("Deck heater").assertIsDisplayed()
        typeChip().assert(hasText("Patio"))
        assertEquals("Patio", typeChipValue(SemanticsProperties.StateDescription))
        assertEquals(listOf("Type"), typeChipValue(SemanticsProperties.ContentDescription))
    }

    /**
     * AC 5; owner ruling §18.23 kept whole: a match hidden only by Archived reads P73-5, and the
     * Archived chip — the old `Show archived` semantics under the chip's new name — lists it.
     */
    @Test fun theArchivedChipKeepsTheOldSemantics() {
        runBlocking {
            val heater = app.graph.createAsset.run(AssetCommand(name = "Deck heater", category = "Patio"))
            app.graph.archiveAsset.run(heater.id)
        }
        drawScreen()
        rule.awaitText("No active assets · 1 archived")

        rule.onNode(hasSetTextAction()).performTextInput("deck")
        rule.awaitText("Matching assets are archived. Turn on Archived to see them.")
        rule.onAllNodesWithText("Deck heater").assertCountEquals(0)
        rule.onAllNodesWithText("Nothing matches that.").assertCountEquals(0)

        rule.onNode(hasText("Archived") and hasClickAction()).performClick()
        rule.awaitText("Deck heater")
        rule.onNode(hasText("Archived") and hasClickAction()).assertIsSelected()
        rule.onAllNodesWithText("Matching assets are archived. Turn on Archived to see them.").assertCountEquals(0)
    }

    // --- drawing -------------------------------------------------------------------------------------

    /** Composes once; a later call swaps the frame in place, since a rule composes one content per test. */
    private fun drawRow(width: Dp, fontScale: Float, typeLabel: String? = null) {
        if (frame == null) {
            frame = Frame(width, fontScale, typeLabel)
            rule.setContent {
                val current = frame!!
                ServiceTagTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, fontScale = current.fontScale),
                    ) {
                        Box(Modifier.width(current.width).testTag(FRAME)) {
                            AssetsFilterRow(
                                filters = AssetFilters(),
                                typeLabel = current.typeLabel,
                                typeChoices = CategoryCatalog.builtIns,
                                onPickType = {},
                                onToggleComponents = {},
                                onToggleArchived = {},
                                modifier = Modifier.fillMaxWidth().testTag(ROW),
                            )
                        }
                    }
                }
            }
        } else {
            rule.runOnIdle { frame = Frame(width, fontScale, typeLabel) }
        }
        rule.waitForIdle()
        // The frame really is the width the case names, not a narrower screen's.
        rule.onNodeWithTag(FRAME).assertWidthIsEqualTo(width)
    }

    private fun drawScreen() {
        rule.setContent {
            ServiceTagTheme {
                AssetsScreen(graph = app.graph, onOpenAsset = {}, onNewAsset = {})
            }
        }
    }

    // --- reading -------------------------------------------------------------------------------------

    /** The Type chip: the clickable node named `Type`, whatever it currently shows. */
    private fun typeChip(): SemanticsNodeInteraction = rule.onNode(hasContentDescription("Type") and hasClickAction())

    private fun <T> typeChipValue(key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>): T? =
        typeChip().fetchSemanticsNode().config.getOrNull(key)

    /** A row of the Type menu: clickable, inside the popup. */
    private fun menuItem(): SemanticsMatcher = hasAnyAncestor(isPopup()) and hasClickAction()

    /** A chip by the word it shows; the Type chip by its name, since it shows `Type` only while All. */
    private fun chip(label: String): SemanticsNodeInteraction =
        if (label == "Type") typeChip() else rule.onNode(hasText(label) and hasClickAction())

    private fun bounds(label: String): DpRect = chip(label).getUnclippedBoundsInRoot()

    private fun textLayout(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /** One line, nothing cut. (No width flag: the result is rebuilt at its incoming maximum width.) */
    private fun assertOneWholeLine(label: String) {
        val layout = textLayout(label)
        assertEquals("'$label' keeps one line", 1, layout.lineCount)
        assertFalse("'$label' is cut vertically", layout.didOverflowHeight)
    }

    private fun assertInside(label: String, b: DpRect, row: DpRect) {
        assertTrue(
            "'$label' at $b lies outside the row at $row",
            b.left.value >= row.left.value - HALF &&
                b.top.value >= row.top.value - HALF &&
                b.right.value <= row.right.value + HALF &&
                b.bottom.value <= row.bottom.value + HALF,
        )
    }

    private companion object {
        const val FRAME = "frame"
        const val ROW = "row"
        const val HALF = 0.5f
        const val WAIT_MS = 10_000L

        val PHONE_412 = 380.dp // a 412dp phone after its 16dp gutters

        val CHIPS = listOf("Type", "Child assets", "Archived")
    }
}
