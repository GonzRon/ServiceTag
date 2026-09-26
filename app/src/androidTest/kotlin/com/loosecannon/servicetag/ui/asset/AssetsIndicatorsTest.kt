package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.health.AssetHealthResult
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.ui.health.AssetHealthView
import com.loosecannon.servicetag.ui.health.ComponentCondition
import com.loosecannon.servicetag.ui.health.ConditionView
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #71: the Assets row's health group and NFC disc (plan `docs/superpowers/plans/2026-09-26-issue-71-assets-row-indicators.md`
 * §3, errata E2, E5, E6). The rules live in the view model and are pinned on the JVM; what only a device
 * can show is the row's drawing — which words and marks appear, in which order, and that nothing clips.
 *
 * Each case draws [AssetListRow] alone, from a synthetic [AssetRow], in a frame of a fixed width — the
 * row's own frame, 16dp padding included, so 360dp is a 360dp phone's full width — under a
 * [LocalDensity] whose font scale stands in for the Android font-size setting (the #68 technique,
 * `ActionGridTest`). Words and the disc are read from the unmerged tree, since the row merges them; a
 * badge's box is its `Surface`'s layout box, the label's second layout ancestor (a `Surface` adds no
 * semantics node; see [pill]); whether any text was cut is read from its own `TextLayoutResult` and
 * line extents ([assertUnclipped]). Every word and description expected here is written out, never
 * read from the code under test.
 *
 * Emulator only (`emulator-5554`), never a phone. Nothing here touches the app's data.
 */
@RunWith(AndroidJUnit4::class)
class AssetsIndicatorsTest {

    @get:Rule val rule = createComposeRule()

    private class Frame(val width: Dp, val fontScale: Float, val rows: List<AssetRow>)

    private var frame by mutableStateOf<Frame?>(null)
    private val opened = mutableListOf<String>()

    /** AC 1, 8: a tagged NOMINAL row draws S95 with its bars, and the disc after it, at the right edge. */
    @Test fun aTaggedNominalRowCarriesTheBadgeAndTheDisc() {
        draw(PHONE_412, 1.0f, listOf(row("Pool pump", health = view(HealthBand.NOMINAL), tagged = true)))

        rule.onNodeWithText("NOMINAL", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithContentDescription(NFC, useUnmergedTree = true).assertCountEquals(1)
        val badge = pill("NOMINAL")
        val disc = disc()
        val row = rowBounds("Pool pump")
        assertTrue("the badge $badge lies left of the disc $disc", badge.right.value <= disc.left.value + HALF)
        assertEquals("the disc anchors the right edge, inside the padding", row.right.value - 16f, disc.right.value, HALF)
        assertEquals("the disc is centred on the row", centreY(row), centreY(disc), HALF)
        assertTrue(
            "health is read before the tag (R71-3)",
            order(hasText("NOMINAL")) < order(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(NFC))),
        )
    }

    /** AC 2: an untagged row with no health draws neither mark. */
    @Test fun anUntaggedUntrackedRowCarriesNeither() {
        draw(PHONE_412, 1.0f, listOf(row("Pool pump")))

        rule.onNodeWithText("Pool pump", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithContentDescription(NFC, useUnmergedTree = true).assertCountEquals(0)
        listOf("NOMINAL", "WARNING", "CRITICAL", "NOT TRACKED").forEach {
            rule.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    /** S95, S96 and S97 on three rows: each band draws its own word, once. */
    @Test fun theThreeBandsAreDistinct() {
        draw(
            PHONE_412, 1.0f,
            listOf(
                row("Pool pump", health = view(HealthBand.NOMINAL)),
                row("Heater", health = view(HealthBand.WARNING)),
                row("Generator", health = view(HealthBand.CRITICAL)),
            ),
        )

        listOf("NOMINAL", "WARNING", "CRITICAL").forEach { word ->
            rule.onAllNodesWithText(word, useUnmergedTree = true).assertCountEquals(1)
        }
        assertTrue("each word on its own row", listOf("NOMINAL", "WARNING", "CRITICAL").map { pill(it).top.value }.zipWithNext().all { (a, b) -> a < b })
    }

    /** C9: the disc is not a control — it has no click of its own, and a tap on it opens the asset. */
    @Test fun theDiscTakesNoTapOfItsOwn() {
        draw(PHONE_412, 1.0f, listOf(row("Pool pump", tagged = true)))

        rule.onNodeWithContentDescription(NFC, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .performClick()
        rule.runOnIdle { assertEquals(listOf("Pool pump"), opened) }
    }

    /**
     * E2, inv. 119: a DOWN asset with a CRITICAL subject draws its condition badge before its health
     * badge, then S109 above S27 under them.
     */
    @Test fun aDownCriticalRowShowsConditionThenHealthThenTheLines() {
        draw(PHONE_412, 1.0f, listOf(row("Generator", health = downCritical(), tagged = true)))

        val condition = pill("DOWN")
        val health = pill("CRITICAL")
        assertTrue(
            "the condition $condition comes left of or above the health $health",
            (condition.right.value <= health.left.value + HALF && abs(condition.top.value - health.top.value) <= HALF) ||
                condition.bottom.value <= health.top.value + HALF,
        )
        val critical = bounds(CRITICAL_BATTERY)
        val component = bounds(COMPONENT_PACK)
        assertTrue("S109 sits under the badges", critical.top.value >= health.bottom.value - HALF)
        assertTrue("S109 above S27", critical.bottom.value <= component.top.value + HALF)
        rule.onNodeWithText(CRITICAL_FAN, useUnmergedTree = true).assertExists()
    }

    /** E2: an OPERATIONAL, NOMINAL row draws the health badge alone — no condition badge, no line. */
    @Test fun anOkTrackedRowShowsOnlyTheHealthBadge() {
        draw(PHONE_412, 1.0f, listOf(row("Pool pump", health = view(HealthBand.NOMINAL, condition = OperationalCondition.OPERATIONAL))))

        rule.onNodeWithText("NOMINAL", useUnmergedTree = true).assertExists()
        listOf("OPERATIONAL", "DOWN", "DEGRADED").forEach { rule.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0) }
        rule.onAllNodesWithText("since", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("Critical:", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText(" — ", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    /**
     * C5, E5: the reachable worst cases at 320/1.0, 360/1.0 and 412/2.0 — (i) in service: OUT OF SEASON,
     * CRITICAL and the disc; (ii) out of service: RETIRED, OUT OF SEASON, ARCHIVED and the disc; (iii) in
     * service: OUT OF SEASON, DOWN, CRITICAL, two S109 lines, one S27 line and the disc. Every word whole,
     * every badge and line inside the name column, the disc inside the row, nothing overlapping, and the
     * name column at least 200dp.
     */
    @Test fun nothingClipsAtNarrowWidthOrLargeFont() {
        val cases = listOf(
            Case("i", row("Generator", outOfSeason = true, health = view(HealthBand.CRITICAL), tagged = true), listOf("OUT OF SEASON", "CRITICAL"), emptyList()),
            Case(
                "ii",
                row("Old pump", retiredOn = "2026-01-01", status = AssetStatus.ARCHIVED, outOfSeason = true, tagged = true),
                listOf("RETIRED", "OUT OF SEASON", "ARCHIVED"),
                emptyList(),
            ),
            Case(
                "iii",
                row("Generator", outOfSeason = true, health = downCritical(), tagged = true),
                listOf("OUT OF SEASON", "DOWN", "CRITICAL"),
                listOf(SINCE, CRITICAL_BATTERY, CRITICAL_FAN, COMPONENT_PACK),
            ),
        )
        for ((width, scale) in FRAMES) {
            for (case in cases) {
                draw(width, scale, listOf(case.row))
                val where = "case ${case.name} at ${width.value.toInt()}/$scale"
                val row = rowBounds(case.row.asset.name)
                val disc = disc()
                val column = DpRect(row.left + 16.dp, row.top, disc.left - 8.dp, row.bottom)
                val pills = case.badges.map(::pill)
                val lines = case.lines.map(::bounds)

                assertTrue("$where: the name column is ${width(column)}dp", width(column) >= 200f)
                assertInside("$where: the disc", disc, row)
                case.badges.forEachIndexed { i, word ->
                    assertUnclipped(where, word)
                    assertEquals("$where: '$word' keeps one line", 1, textLayout(word).lineCount)
                    assertInside("$where: '$word'", pills[i], column)
                }
                case.lines.forEachIndexed { i, line ->
                    assertUnclipped(where, line)
                    assertInside("$where: '$line'", lines[i], column)
                }
                val all = pills + lines + disc
                all.forEachIndexed { i, a ->
                    all.forEachIndexed { j, b -> if (i < j) assertFalse("$where: boxes $i $a and $j $b overlap", overlap(a, b)) }
                }
            }
        }
    }

    // --- drawing -------------------------------------------------------------------------------------

    /** Composes once; a later call swaps the frame in place, since a rule composes one content per test. */
    private fun draw(width: Dp, fontScale: Float, rows: List<AssetRow>) {
        if (frame == null) {
            frame = Frame(width, fontScale, rows)
            rule.setContent {
                val current = frame!!
                ServiceTagTheme {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, fontScale = current.fontScale),
                    ) {
                        Box(Modifier.width(current.width).testTag(FRAME)) {
                            Column(Modifier.fillMaxWidth()) {
                                current.rows.forEach { r ->
                                    AssetListRow(
                                        row = r,
                                        onClick = { opened += r.asset.name },
                                        modifier = Modifier.testTag(ROW + r.asset.name),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            rule.runOnIdle { frame = Frame(width, fontScale, rows) }
        }
        rule.waitForIdle()
        // The frame really is the width the case names, not a narrower screen's.
        rule.onNodeWithTag(FRAME).assertWidthIsEqualTo(width)
    }

    private fun row(
        name: String,
        outOfSeason: Boolean = false,
        retiredOn: String? = null,
        status: AssetStatus = AssetStatus.ACTIVE,
        health: AssetHealthView? = null,
        tagged: Boolean = false,
    ) = AssetRow(
        asset = Asset(
            id = AssetId(name.lowercase().replace(' ', '-')),
            name = name,
            category = "Power",
            status = status,
            retiredOn = retiredOn,
            createdAt = 1L,
            updatedAt = 1L,
        ),
        outOfSeason = outOfSeason,
        hasWrittenTag = tagged,
        health = health,
    )

    private fun subject(name: String, score: Int, band: HealthBand) = SubjectHealth(
        subject = HealthSubject(
            id = HealthSubjectId(name), assetId = AssetId("generator"), name = name, kind = HealthSubjectKind.PART,
            driver = HealthDriver.AGE, scheduleId = null, baselineProfileId = null, nominalUntilDays = 0,
            warningFromDays = 40, criticalFromDays = 75, weight = 1, sortOrder = 0, archivedAt = null,
            createdAt = 1L, updatedAt = 1L,
        ),
        value = SubjectValue.Scored(score, band, 100L),
        lines = emptyList(),
    )

    private fun view(
        band: HealthBand,
        condition: OperationalCondition? = null,
        critical: List<SubjectHealth> = emptyList(),
        components: List<ComponentCondition> = emptyList(),
    ) = AssetHealthView(
        assetId = AssetId("generator"),
        computedForOn = DAY,
        condition = condition?.let {
            ConditionView(it, since = DAY, reason = "", occurredOn = DAY, occurredTime = null, eventId = null, eventExists = false)
        },
        aggregation = HealthAggregation.WORST,
        result = AssetHealthResult(subjects = critical, aggregate = SubjectValue.Scored(50, band, null), fallback = false, critical = critical),
        components = components,
        inService = true,
    )

    /** DOWN, CRITICAL, two critical subjects and one DOWN component: case (iii)'s health. */
    private fun downCritical() = view(
        HealthBand.CRITICAL,
        condition = OperationalCondition.DOWN,
        critical = listOf(subject("Battery age", 12, HealthBand.CRITICAL), subject("Cooling fan age", 9, HealthBand.CRITICAL)),
        components = listOf(ComponentCondition(AssetId("pack"), "Battery pack", OperationalCondition.DOWN, "Won't hold charge", DAY)),
    )

    private data class Case(val name: String, val row: AssetRow, val badges: List<String>, val lines: List<String>)

    // --- reading -------------------------------------------------------------------------------------

    private fun rowBounds(name: String): DpRect = rule.onNodeWithTag(ROW + name).getUnclippedBoundsInRoot()

    private fun disc(): DpRect = rule.onNodeWithContentDescription(NFC, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun bounds(text: String): DpRect = rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    /**
     * A badge's box: its `Surface`. The three badges draw `Surface { Row(padding) { Icon; Text } }`, and
     * a Surface adds no semantics node of its own, so the box is the label's second layout ancestor —
     * checked to hold the label with no more than a badge's padding and glyph beside it.
     */
    private fun pill(word: String): DpRect {
        val text = rule.onNodeWithText(word, useUnmergedTree = true)
        val label = text.getUnclippedBoundsInRoot()
        val surface = text.fetchSemanticsNode().layoutInfo.parentInfo?.parentInfo
            ?: throw AssertionError("'$word' sits in no badge")
        val box = surface.coordinates.boundsInRoot().toDp()
        assertInside("'$word' inside its badge", label, box)
        assertTrue("'$word''s badge $box is a badge, not a line", width(box) - width(label) <= PILL_EXTRA)
        return box
    }

    /** The position of the first node [matcher] accepts in the unmerged tree's reading order. */
    private fun order(matcher: SemanticsMatcher): Int {
        val flat = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            flat += node
            node.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return flat.indexOfFirst { matcher.matches(it) }.also { assertTrue("nothing matches $matcher", it >= 0) }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    /**
     * Nothing of [text] is cut: no line lost below its box (`didOverflowHeight`), and no line's ink
     * running past its box's right edge. The result's own `didOverflowWidth` — and so
     * `hasVisualOverflow` — is not read: the node rebuilds this result at its incoming maximum width,
     * so a one-line label narrower than the space offered reads as overflowing (the #68 finding,
     * `ActionGridTest.assertWhole`); the line extents against the box are the direct check.
     */
    private fun assertUnclipped(where: String, text: String) {
        val layout = textLayout(text)
        assertFalse("$where: '$text' is cut vertically", layout.didOverflowHeight)
        val box = with(rule.density) { width(bounds(text)).dp.toPx() }
        val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
        assertTrue("$where: '$text' runs ${widest}px in a ${box}px box", widest <= box + 1f)
    }

    private fun Rect.toDp(): DpRect = with(rule.density) { DpRect(left.toDp(), top.toDp(), right.toDp(), bottom.toDp()) }

    private fun width(r: DpRect): Float = (r.right - r.left).value

    private fun centreY(r: DpRect): Float = (r.top.value + r.bottom.value) / 2

    private fun overlap(a: DpRect, b: DpRect): Boolean =
        a.left.value < b.right.value - HALF && b.left.value < a.right.value - HALF &&
            a.top.value < b.bottom.value - HALF && b.top.value < a.bottom.value - HALF

    private fun assertInside(what: String, b: DpRect, outer: DpRect) {
        assertTrue(
            "$what at $b lies outside $outer",
            b.left.value >= outer.left.value - HALF &&
                b.top.value >= outer.top.value - HALF &&
                b.right.value <= outer.right.value + HALF &&
                b.bottom.value <= outer.bottom.value + HALF,
        )
    }

    private companion object {
        const val FRAME = "frame"
        const val ROW = "row-"
        const val HALF = 0.5f

        /** 7dp of padding each side, a 16dp glyph and a 4dp gap, with a margin: what a badge adds to its label. */
        const val PILL_EXTRA = 40f

        /** P71-4, ratified 2026-09-26. */
        const val NFC = "NFC tag written"

        const val CRITICAL_BATTERY = "Critical: Battery age 12"
        const val CRITICAL_FAN = "Critical: Cooling fan age 9"
        const val COMPONENT_PACK = "Battery pack DOWN — Won't hold charge"

        /** S22 beside the DOWN badge: `since` and [DAY] in the app's display shape. */
        const val SINCE = "since 10 Apr 2026"

        val PHONE_412 = 412.dp
        val FRAMES = listOf(320.dp to 1.0f, 360.dp to 1.0f, 412.dp to 2.0f)
        val DAY: LocalDate = LocalDate.parse("2026-04-10")
    }
}
