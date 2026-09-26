package com.loosecannon.servicetag.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * #68: the asset quick-action grid keeps every word of its labels (plan
 * `docs/superpowers/plans/2026-09-26-issue-68-action-grid-layout.md` §4).
 *
 * Each case draws [ActionGrid] in a frame of a fixed width, standing in for a phone's content width
 * after the screen's 16dp gutters, under a [LocalDensity] whose font scale stands in for the Android
 * font-size setting; the platform's non-linear font tables apply to it exactly as they do on a device.
 * Every expected column count is written down from the plan's C3 table for K = 6, never computed from
 * the rule under test, so a broken rule cannot mirror itself into the oracle.
 *
 * A label is read from the unmerged tree (its button merges it), and whether any of it was cut is its
 * own `TextLayoutResult`'s overflow flags. The label's box lying inside its button's box is checked too,
 * but only as containment: a label squeezed into a box too short for it is still inside that box.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class ActionGridTest {

    @get:Rule val rule = createComposeRule()

    private class Frame(val width: Dp, val fontScale: Float, val actions: List<ActionSpec>)

    private var frame by mutableStateOf<Frame?>(null)
    private val clicked = mutableListOf<String>()

    /**
     * The production-shaped grid of AC 1–2 and the thresholds hold at 380dp and scale 1.0: two columns,
     * every word of both wrapped labels inside its button, the pair levelled, `Backup` alone and left.
     */
    @Test fun theProductionShapedGridRendersEveryWord() {
        draw(PHONE_412, 1.0f, productionShaped())

        FIXTURE.forEach(::assertWhole)
        assertEquals("two columns", listOf(2, 2, 1), columnsPerRow(FIXTURE))
        val descale = bounds(DESCALE)
        val writeTag = bounds(WRITE_TAG)
        assertEquals("the first pair shares a top", descale.top.value, writeTag.top.value, HALF)
        assertEquals("Write tag is as tall as its neighbour", heightOf(descale), heightOf(writeTag), HALF)
        assertTrue("the wrapped pair grew past 44dp, was ${heightOf(descale)}", heightOf(descale) > 44f + HALF)
        val backup = bounds(BACKUP)
        assertEquals("Backup sits in the left column", descale.left.value, backup.left.value, HALF)
        assertEquals("Backup keeps half the width", HALF_OF_PHONE_412, widthOf(backup), HALF)
    }

    /** AC 3: a name nobody would shorten wraps as far as it must and its neighbour levels to it. */
    @Test fun aLongUserDefinedLabelStaysInsideItsButton() {
        draw(PHONE_412, 1.0f, listOf(tonal(LONG), outlined(EDIT)))

        assertWhole(LONG)
        assertWhole(EDIT)
        assertEquals("one pair", listOf(2), columnsPerRow(listOf(LONG, EDIT)))
        val long = bounds(LONG)
        val edit = bounds(EDIT)
        assertEquals("the pair is level", heightOf(long), heightOf(edit), HALF)
        assertTrue("the long label's button grew past 44dp, was ${heightOf(long)}", heightOf(long) > 44f + HALF)
    }

    /** AC 4, 6: 300dp cannot give two columns six ems each, so every action takes the full width. */
    @Test fun aNarrowWidthDrawsOneColumn() {
        draw(NARROW, 1.0f, productionShaped())

        assertEquals("one column", List(FIXTURE.size) { 1 }, columnsPerRow(FIXTURE))
        val all = FIXTURE.map(::bounds)
        all.forEach { b ->
            assertEquals("one left edge", all.first().left.value, b.left.value, HALF)
            assertEquals("full width", NARROW.value, widthOf(b), HALF)
        }
        FIXTURE.forEach(::assertWhole)
    }

    /** AC 4, 6: at 1.2 a 380dp content width still gives each column six ems of the larger face. */
    @Test fun aLargerFontKeepsTwoColumnsWhileTheyFit() {
        draw(PHONE_412, 1.2f, productionShaped())

        assertEquals("two columns", listOf(2, 2, 1), columnsPerRow(FIXTURE))
        FIXTURE.forEach(::assertWhole)
        rows(FIXTURE).filter { it.size == 2 }.forEach { (left, right) ->
            assertEquals("each pair is level", heightOf(left), heightOf(right), HALF)
        }
        FIXTURE.map(::bounds).forEach { b ->
            assertTrue("no button under 44dp, was ${heightOf(b)}", heightOf(b) >= 44f - HALF)
        }
    }

    /**
     * AC 3, 4, 6: 380dp at 2.0 and 328dp at 1.2 both fall short of two six-em columns, so one full-width
     * column, the long name included, and nothing cut.
     */
    @Test fun aLargeFontDrawsOneColumn() {
        val labels = listOf(DESCALE, LONG) + FIXTURE.drop(1)
        listOf(PHONE_412 to 2.0f, PHONE_360 to 1.2f).forEach { (width, scale) ->
            draw(width, scale, listOf(tonal(DESCALE), tonal(LONG)) + productionShaped().drop(1))

            assertEquals("one column at $width × $scale", List(labels.size) { 1 }, columnsPerRow(labels))
            labels.forEach(::assertWhole)
            labels.map(::bounds).forEach { b ->
                assertTrue("no button under 44dp at $width × $scale, was ${heightOf(b)}", heightOf(b) >= 44f - HALF)
                assertEquals("full width at $width × $scale", width.value, widthOf(b), HALF)
            }
        }
    }

    /** AC 5: one-line labels keep today's 44dp buttons, 54dp row pitch, half widths and 48dp touch height. */
    @Test fun shortLabelsKeepTheirHeightRhythmAndTaps() {
        draw(
            PHONE_412,
            1.0f,
            listOf(outlined(WRITE_TAG), outlined(EDIT), tonal(BACKUP), tonal(HISTORY), outlined(SET_UP)),
        )
        val labels = listOf(WRITE_TAG, EDIT, BACKUP, HISTORY, SET_UP)
        val all = labels.map(::bounds)

        all.forEachIndexed { i, b -> assertEquals("${labels[i]} is 44dp tall", 44f, heightOf(b), HALF) }
        assertEquals("two per row", listOf(2, 2, 1), columnsPerRow(labels))
        all.forEachIndexed { i, b -> assertEquals("${labels[i]} keeps half the width", HALF_OF_PHONE_412, widthOf(b), HALF) }
        assertEquals("a 54dp row pitch", 54f, (all[2].top - all[0].top).value, HALF)
        assertEquals("the fifth sits in the left column", all[0].left.value, all[4].left.value, HALF)

        button(EDIT).assertTouchHeightIsEqualTo(48.dp)
        button(BACKUP).assertTouchHeightIsEqualTo(48.dp)
        button(EDIT).performClick()
        button(BACKUP).performClick()
        rule.runOnIdle { assertEquals(listOf(EDIT, BACKUP), clicked) }
        labels.forEach { button(it).assertHasClickAction() }
    }

    // --- drawing -------------------------------------------------------------------------------------

    /** Composes once; a later call swaps the frame in place, since a rule composes one content per test. */
    private fun draw(width: Dp, fontScale: Float, actions: List<ActionSpec>) {
        if (frame == null) {
            frame = Frame(width, fontScale, actions)
            rule.setContent {
                val current = frame!!
                ServiceTagTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, fontScale = current.fontScale),
                    ) {
                        Box(Modifier.width(current.width).testTag(FRAME)) {
                            ActionGrid(current.actions, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        } else {
            rule.runOnIdle { frame = Frame(width, fontScale, actions) }
        }
        rule.waitForIdle()
        // The frame really is the width the case names, not a narrower screen's.
        rule.onNodeWithTag(FRAME).assertWidthIsEqualTo(width)
    }

    private fun outlined(label: String) = ActionSpec(label, Icons.Outlined.Build, outlined = true) { clicked += label }

    private fun tonal(label: String) = ActionSpec(label, Icons.Outlined.Build, outlined = false) { clicked += label }

    /** A profile's log action, then the four utility actions in production order and style. */
    private fun productionShaped() = listOf(
        tonal(DESCALE),
        outlined(WRITE_TAG),
        outlined(EDIT),
        outlined(READINGS),
        tonal(BACKUP),
    )

    // --- reading -------------------------------------------------------------------------------------

    /** The button: the merged node that is clickable and carries the label. */
    private fun button(label: String): SemanticsNodeInteraction = rule.onNode(hasClickAction() and hasText(label))

    /** The label itself: the `Text` node of the unmerged tree. */
    private fun labelNode(label: String): SemanticsNodeInteraction = rule.onNodeWithText(label, useUnmergedTree = true)

    private fun bounds(label: String): DpRect = button(label).getUnclippedBoundsInRoot()

    private fun textLayout(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        labelNode(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /** Nothing cut in either axis, and the label's box inside its button's box. */
    private fun assertWhole(label: String) {
        val layout = textLayout(label)
        assertFalse("'$label' is cut vertically", layout.didOverflowHeight)
        assertFalse("'$label' is cut horizontally", layout.didOverflowWidth)
        val text = labelNode(label).getUnclippedBoundsInRoot()
        val outer = bounds(label)
        assertTrue(
            "'$label' at $text lies outside its button at $outer",
            text.left.value >= outer.left.value - HALF &&
                text.top.value >= outer.top.value - HALF &&
                text.right.value <= outer.right.value + HALF &&
                text.bottom.value <= outer.bottom.value + HALF,
        )
    }

    /** The buttons grouped into rows by their tops, in list order. */
    private fun rows(labels: List<String>): List<List<DpRect>> {
        val rows = mutableListOf<MutableList<DpRect>>()
        labels.map(::bounds).forEach { b ->
            val row = rows.lastOrNull()
            if (row != null && abs(row.first().top.value - b.top.value) <= HALF) row += b else rows += mutableListOf(b)
        }
        return rows
    }

    /** Per row, the number of distinct button left edges. */
    private fun columnsPerRow(labels: List<String>): List<Int> = rows(labels).map { row ->
        val lefts = row.map { it.left.value }.sorted()
        1 + lefts.zipWithNext().count { (a, b) -> b - a > HALF }
    }

    private fun heightOf(b: DpRect): Float = (b.bottom - b.top).value

    private fun widthOf(b: DpRect): Float = (b.right - b.left).value

    private companion object {
        const val FRAME = "frame"
        const val HALF = 0.5f

        val PHONE_412 = 380.dp // a 412dp phone after its 16dp gutters
        val PHONE_360 = 328.dp // a 360dp phone after its gutters
        val NARROW = 300.dp // a narrow window
        const val HALF_OF_PHONE_412 = 185f // (380 − 10) / 2

        const val DESCALE = "Log Descale and flush"
        const val WRITE_TAG = "Write tag"
        const val EDIT = "Edit"
        const val READINGS = "Readings & actions"
        const val BACKUP = "Backup"
        const val HISTORY = "History"
        const val SET_UP = "Set up from template"
        const val LONG = "Log Replace every intake gasket and recalibrate the flow sensor"

        val FIXTURE = listOf(DESCALE, WRITE_TAG, EDIT, READINGS, BACKUP)
    }
}
