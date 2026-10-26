package com.loosecannon.servicetag.ui.asset

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.health.AssetHealthResult
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.journal.CategoryCatalog
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.LoanStanding
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.ui.health.AssetHealthView
import com.loosecannon.servicetag.ui.health.ComponentCondition
import com.loosecannon.servicetag.ui.health.ConditionView
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #116: the Assets row's in-season mark, Home Assistant mark and condition badge, the Condition control and the
 * condition-hidden line (plan `docs/superpowers/plans/2026-10-26-issue-116-assets-ha-condition-season-indicators.md`
 * U-C4, row u9) — the issue's own UI proof. The rules live in the view model and `rowFactsOf` and are pinned on the
 * JVM; what only a device can show is the drawing: which marks and words appear, with which accessible names, in which
 * order, and that nothing clips.
 *
 * Each case draws [AssetListRow], [AssetsFilterRow] or [EmptyList] alone in a frame of a fixed width, under a
 * [LocalDensity] whose font scale stands in for the Android font-size setting — #71's `AssetsIndicatorsTest`
 * technique, whose reading helpers are repeated here. A row's facts are built as the list builds them: through
 * `rowFactsOf`, from a fictional asset and its condition rows. Every word and description expected here is written
 * out, never read from the code under test.
 *
 * [theFourRowsInLightAndDark] writes its two captures under the app's external files, `issue-116-ui-proof/`, for the
 * gate evidence (plan §7): the gate pulls them with `adb pull` while the app is still installed.
 *
 * Emulator only (`emulator-5554`), never a phone. Nothing here touches the app's data.
 */
@RunWith(AndroidJUnit4::class)
class AssetsRowFactsTest {

    @get:Rule val rule = createComposeRule()

    private sealed interface Scene {
        data class Rows(val rows: List<AssetRow>) : Scene
        data class Controls(val condition: OperationalCondition?) : Scene
        data class Empty(val reason: EmptyReason) : Scene
    }

    private class Frame(val width: Dp, val fontScale: Float, val dark: Boolean, val scene: Scene)

    private var frame by mutableStateOf<Frame?>(null)
    private val opened = mutableListOf<String>()
    private val picked = mutableListOf<OperationalCondition?>()

    // --- the marks -----------------------------------------------------------------------------------

    /** AC116-1, P116-1: a linked row draws the Home Assistant mark, named for TalkBack, with no tap of its own. */
    @Test fun theHomeAssistantMarkIsNamedAndTakesNoTap() {
        drawRows(listOf(row("Example Heater", publishing = true)))

        rule.onAllNodesWithContentDescription(HA, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithContentDescription(IN_SEASON, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription(HA, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .performClick()
        rule.runOnIdle { assertEquals("a tap on the mark opens the asset", listOf("Example Heater"), opened) }
    }

    /** AC116-4, P116-3: an in-season row draws the in-season mark, named for TalkBack, with no tap of its own. */
    @Test fun theInSeasonMarkIsNamedAndTakesNoTap() {
        drawRows(listOf(row("Example Heater", seasonMode = SeasonMode.MANUAL)))

        rule.onAllNodesWithContentDescription(IN_SEASON, useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithContentDescription(HA, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("OUT OF SEASON", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithContentDescription(IN_SEASON, useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .performClick()
        rule.runOnIdle { assertEquals("a tap on the mark opens the asset", listOf("Example Heater"), opened) }
    }

    /**
     * U-C4: left to right the in-season mark, the Home Assistant mark, then the NFC disc at the far right inside the
     * padding — each in the disc's 22dp box, centred on the row, right of the badges — and read in that order.
     */
    @Test fun theMarksSitInSeasonThenHomeAssistantThenTheDisc() {
        drawRows(
            listOf(row("Example Heater", seasonMode = SeasonMode.MANUAL, condition = OperationalCondition.DOWN, inbound = true, tagged = true)),
        )

        val season = mark(IN_SEASON)
        val ha = mark(HA)
        val disc = mark(NFC)
        val row = rowBounds("Example Heater")
        assertTrue("the in-season mark $season lies left of the Home Assistant mark $ha", season.right.value <= ha.left.value + HALF)
        assertTrue("the Home Assistant mark $ha lies left of the disc $disc", ha.right.value <= disc.left.value + HALF)
        assertEquals("the disc anchors the right edge, inside the padding", row.right.value - 16f, disc.right.value, HALF)
        listOf(season, ha, disc).forEach { box ->
            assertEquals("$box is the disc's box", 22f, width(box), HALF)
            assertEquals("$box is centred on the row", centreY(row), centreY(box), HALF)
        }
        assertTrue("the badge lies left of the marks", pill("DOWN").right.value <= season.left.value + HALF)
        val reading = listOf(hasText("DOWN"), described(IN_SEASON), described(HA), described(NFC)).map(::order)
        assertEquals("read: the condition, the in-season mark, the Home Assistant mark, the disc", reading.sorted(), reading)
    }

    /** AC116-1, AC116-4: a row with neither fact draws neither mark, and the disc alone keeps the right edge. */
    @Test fun aRowWithNeitherFactDrawsNoMark() {
        drawRows(listOf(row("Example Shed Light", tagged = true)))

        rule.onAllNodesWithContentDescription(HA, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithContentDescription(IN_SEASON, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(rowBounds("Example Shed Light").right.value - 16f, mark(NFC).right.value, HALF)
    }

    /** U-2, AC116-4: an out-of-season seasonal asset keeps its badge and gets no mark; a year-round one shows neither. */
    @Test fun outOfSeasonKeepsItsBadgeAndYearRoundShowsNeither() {
        drawRows(
            listOf(
                row("Example Boiler", seasonMode = SeasonMode.MANUAL, outOfSeason = true),
                row("Example Indoor Pool"),
            ),
        )

        rule.onAllNodesWithText("OUT OF SEASON", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithContentDescription(IN_SEASON, useUnmergedTree = true).assertCountEquals(0)
        val badge = pill("OUT OF SEASON")
        assertTrue("the badge is the boiler's", badge.bottom.value <= bounds("Example Indoor Pool").top.value + HALF)
    }

    // --- the condition badge -------------------------------------------------------------------------

    /**
     * AC116-2, U-1 (#71's R71-11 amended for this badge): an untracked row in service draws its condition — OPERATIONAL
     * included — in its word, with its "since" line, once per row and under its own name.
     */
    @Test fun anUntrackedRowShowsEachOfTheThreeConditions() {
        drawRows(
            listOf(
                row("Example Boiler", condition = OperationalCondition.OPERATIONAL),
                row("Example Indoor Pool", condition = OperationalCondition.DEGRADED),
                row("Example Heater", condition = OperationalCondition.DOWN),
            ),
        )

        listOf("OPERATIONAL", "DEGRADED", "DOWN").forEach { rule.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(1) }
        rule.onAllNodesWithText(SINCE, useUnmergedTree = true).assertCountEquals(3)
        listOf("NOMINAL", "WARNING", "CRITICAL", "NOT TRACKED").forEach {
            rule.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0)
        }
        listOf("Example Boiler" to "OPERATIONAL", "Example Indoor Pool" to "DEGRADED", "Example Heater" to "DOWN").forEach { (name, word) ->
            val under = bounds(name)
            val badge = pill(word)
            assertTrue("$word is under $name", badge.top.value >= under.bottom.value - HALF)
            assertTrue("$word is inside $name's row", badge.bottom.value <= rowBounds(name).bottom.value + HALF)
        }
    }

    /**
     * U-C4, inv. 119, H4: a tracked DOWN row draws one condition badge — never a second for the health surface — and
     * draws it before its health badge, on screen and in reading order.
     */
    @Test fun aTrackedDownRowDrawsOneConditionBadgeBeforeItsHealth() {
        drawRows(listOf(row("Example Heater", condition = OperationalCondition.DOWN, health = downCritical())))

        rule.onAllNodesWithText("DOWN", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithText(SINCE, useUnmergedTree = true).assertCountEquals(1)
        assertTrue("the condition is drawn before the health", before(pill("DOWN"), pill("CRITICAL")))
        assertTrue("and read before it", order(hasText("DOWN")) < order(hasText("CRITICAL")))
    }

    /** Limit 2, U-4: a retired and an archived asset, each with a DOWN row, show their own badge and no condition. */
    @Test fun aRetiredOrArchivedRowShowsNoCondition() {
        drawRows(
            listOf(
                row("Example Old Pump", condition = OperationalCondition.DOWN, retiredOn = "2026-01-01"),
                row("Example Spare Heater", condition = OperationalCondition.DOWN, status = AssetStatus.ARCHIVED),
            ),
        )

        rule.onNodeWithText("RETIRED", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("ARCHIVED", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("DOWN", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("since", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    // --- the Condition control and the empty state ---------------------------------------------------

    /**
     * U-C4, R73-3's shape: the Condition control is a dropdown opener, never a checkbox — named `Condition` always and
     * stating All while nothing is chosen — and its menu is All, Operational, Degraded, Down; a pick reports the
     * condition and closes the menu.
     */
    @Test fun theConditionControlIsADropdownWithFourRows() {
        drawControls(condition = null)

        conditionChip()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            .assert(hasText("Condition"))
        assertEquals(listOf("Condition"), chipValue(SemanticsProperties.ContentDescription))
        assertEquals("All", chipValue(SemanticsProperties.StateDescription))

        conditionChip().performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(menuItem()).fetchSemanticsNodes().isNotEmpty() }
        val offered = rule.onAllNodes(menuItem()).fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.Text].joinToString { it.text }
        }
        assertEquals(listOf("All", "Operational", "Degraded", "Down"), offered)

        rule.onNode(menuItem() and hasText("Degraded")).performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodes(menuItem()).fetchSemanticsNodes().isEmpty() }
        rule.runOnIdle { assertEquals(listOf<OperationalCondition?>(OperationalCondition.DEGRADED), picked) }
    }

    /** A chosen condition shows its option word and states it; the name stays `Condition`, and Type keeps its own. */
    @Test fun aChosenConditionShowsAndStatesItsWord() {
        drawControls(condition = OperationalCondition.DOWN)

        conditionChip()
            .assert(hasText("Down"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        assertEquals(listOf("Condition"), chipValue(SemanticsProperties.ContentDescription))
        assertEquals("Down", chipValue(SemanticsProperties.StateDescription))
        rule.onNode(hasContentDescription("Type") and hasClickAction()).assert(hasText("Type"))
    }

    /** U-C4's empty state, P116-2: CONDITION_HIDDEN draws its one quiet line and offers no button. */
    @Test fun conditionHiddenSaysItsLine() {
        drawEmpty(EmptyReason.CONDITION_HIDDEN)

        rule.onNodeWithText("Matching assets have another condition. Set Condition to All.").assertIsDisplayed()
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    // --- the widths ----------------------------------------------------------------------------------

    /**
     * #71's C5 widths (320/1.0, 360/1.0, 412/2.0): the fullest reachable row — LOAN OVERDUE, DOWN with its "since"
     * line, CRITICAL, its lines, and all three marks — keeps every word whole inside a name column of at least 200dp,
     * every mark inside the row, and nothing overlapping; and the four controls, a condition chosen, keep every label
     * whole on one line inside the row, Condition after Type.
     */
    @Test fun nothingClipsAtNarrowWidthOrLargeFont() {
        val fullest = row(
            "Example Heater", seasonMode = SeasonMode.MANUAL, condition = OperationalCondition.DOWN, inbound = true,
            publishing = true, health = downCritical(), tagged = true, loan = LoanStanding.OVERDUE,
        )
        val badges = listOf("LOAN OVERDUE", "DOWN", "CRITICAL")
        val lines = listOf(SINCE, CRITICAL_BATTERY, CRITICAL_FAN, COMPONENT_PACK)
        for ((width, scale) in FRAMES) {
            drawRows(listOf(fullest), width, scale)
            val where = "the fullest row at ${width.value.toInt()}/$scale"
            val row = rowBounds(fullest.asset.name)
            val marks = listOf(IN_SEASON, HA, NFC).map(::mark)
            val column = DpRect(row.left + 16.dp, row.top, marks.first().left - 8.dp, row.bottom)
            val pills = badges.map(::pill)
            val boxes = lines.map(::bounds)

            assertTrue("$where: the name column is ${width(column)}dp", width(column) >= 200f)
            marks.forEach { assertInside("$where: the mark $it", it, row) }
            badges.forEachIndexed { i, word ->
                assertUnclipped(where, word)
                assertEquals("$where: '$word' keeps one line", 1, textLayout(word).lineCount)
                assertInside("$where: '$word'", pills[i], column)
            }
            lines.forEachIndexed { i, line ->
                assertUnclipped(where, line)
                assertInside("$where: '$line'", boxes[i], column)
            }
            val all = pills + boxes + marks
            all.forEachIndexed { i, a ->
                all.forEachIndexed { j, b -> if (i < j) assertFalse("$where: boxes $i $a and $j $b overlap", overlap(a, b)) }
            }
        }
        val labels = listOf("Type", "Degraded", "Child assets", "Archived")
        for ((width, scale) in FRAMES) {
            // The controls' frame is the phone's content width, after the screen's 16dp gutters.
            drawControls(OperationalCondition.DEGRADED, width - 32.dp, scale)
            val where = "the controls at ${width.value.toInt()}/$scale"
            val row = rule.onNodeWithTag(CONTROLS).getUnclippedBoundsInRoot()
            labels.forEach { label ->
                val layout = textLayout(label)
                assertEquals("$where: '$label' keeps one line", 1, layout.lineCount)
                assertFalse("$where: '$label' is cut vertically", layout.didOverflowHeight)
                assertInside("$where: '$label'", bounds(label), row)
            }
            assertTrue("$where: Condition comes after Type", before(bounds("Type"), bounds("Degraded")))
        }
    }

    // --- the UI proof --------------------------------------------------------------------------------

    /**
     * Plan §7, the owner's "its own acceptance and UI proof": four fictional rows — Example Heater (MANUAL, linked by
     * its status publication, DOWN, in season), Example Boiler (MANUAL, linked by its #16 sync, OPERATIONAL, out of
     * season), Example Indoor Pool (YEAR_ROUND, DEGRADED) and the retired Example Old Pump (its DOWN row is no condition
     * here) — each carry exactly their facts, in light and in dark, and each frame is captured for the gate evidence.
     */
    @Test fun theFourRowsInLightAndDark() {
        val rows = listOf(
            row("Example Heater", seasonMode = SeasonMode.MANUAL, condition = OperationalCondition.DOWN, publishing = true, tagged = true),
            row(
                "Example Boiler", seasonMode = SeasonMode.MANUAL, outOfSeason = true, condition = OperationalCondition.OPERATIONAL,
                inbound = true, tagged = true,
            ),
            row("Example Indoor Pool", condition = OperationalCondition.DEGRADED),
            row("Example Old Pump", condition = OperationalCondition.DOWN, retiredOn = "2026-01-01"),
        )
        for (dark in listOf(false, true)) {
            drawRows(rows, PHONE_412, 1.0f, dark)

            assertRow("Example Heater", words = listOf("DOWN"), marks = listOf(IN_SEASON, HA, NFC))
            assertRow("Example Boiler", words = listOf("OUT OF SEASON", "OPERATIONAL"), marks = listOf(HA, NFC))
            assertRow("Example Indoor Pool", words = listOf("DEGRADED"), marks = emptyList())
            assertRow("Example Old Pump", words = listOf("RETIRED"), marks = emptyList())

            val image = rule.onNodeWithTag(FRAME).captureToImage()
            assertEquals("the capture is the frame", with(rule.density) { PHONE_412.roundToPx() }, image.width)
            save(image, if (dark) "assets-rows-dark.png" else "assets-rows-light.png")
        }
    }

    /** The row's merged node — the row is one clickable — holds exactly these of the words and marks the four can show. */
    private fun assertRow(name: String, words: List<String>, marks: List<String>) {
        val config = rule.onNodeWithTag(ROW + name).fetchSemanticsNode().config
        val texts = config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        val described = config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        val shown = listOf("DOWN", "DEGRADED", "OPERATIONAL", "OUT OF SEASON", "RETIRED").filter { it in texts }
        assertEquals("$name's words", words.sorted(), shown.sorted())
        assertEquals("$name's marks", marks.sorted(), listOf(IN_SEASON, HA, NFC).filter { it in described }.sorted())
    }

    private fun save(image: ImageBitmap, name: String) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dir = File(context.getExternalFilesDir(null), PROOF_DIR).also { it.mkdirs() }
        File(dir, name).outputStream().use { out ->
            assertTrue("$name was written", image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out))
        }
    }

    // --- drawing -------------------------------------------------------------------------------------

    private fun drawRows(rows: List<AssetRow>, width: Dp = PHONE_412, fontScale: Float = 1.0f, dark: Boolean = false) =
        draw(Frame(width, fontScale, dark, Scene.Rows(rows)))

    private fun drawControls(condition: OperationalCondition?, width: Dp = PHONE_412 - 32.dp, fontScale: Float = 1.0f) =
        draw(Frame(width, fontScale, false, Scene.Controls(condition)))

    private fun drawEmpty(reason: EmptyReason) = draw(Frame(PHONE_412, 1.0f, false, Scene.Empty(reason)))

    /** Composes once; a later call swaps the frame in place, since a rule composes one content per test. */
    private fun draw(next: Frame) {
        if (frame == null) {
            frame = next
            rule.setContent {
                val current = frame!!
                ServiceTagTheme(darkTheme = current.dark) {
                    CompositionLocalProvider(
                        LocalDensity provides Density(LocalDensity.current.density, fontScale = current.fontScale),
                    ) {
                        Box(
                            Modifier
                                .width(current.width)
                                .background(MaterialTheme.colorScheme.surface)
                                .testTag(FRAME),
                        ) {
                            when (val scene = current.scene) {
                                is Scene.Rows -> Column(Modifier.fillMaxWidth()) {
                                    scene.rows.forEach { r ->
                                        AssetListRow(
                                            row = r,
                                            onClick = { opened += r.asset.name },
                                            modifier = Modifier.testTag(ROW + r.asset.name),
                                        )
                                    }
                                }
                                is Scene.Controls -> AssetsFilterRow(
                                    filters = AssetFilters(condition = scene.condition),
                                    typeLabel = null,
                                    typeChoices = CategoryCatalog.builtIns,
                                    onPickType = {},
                                    onToggleComponents = {},
                                    onToggleArchived = {},
                                    modifier = Modifier.fillMaxWidth().testTag(CONTROLS),
                                    onPickCondition = { picked += it },
                                )
                                is Scene.Empty -> EmptyList(
                                    reason = scene.reason,
                                    archivedCount = 0,
                                    onNewAsset = null,
                                    onShowArchived = {},
                                )
                            }
                        }
                    }
                }
            }
        } else {
            rule.runOnIdle { frame = next }
        }
        rule.waitForIdle()
        // The frame really is the width the case names, not a narrower screen's.
        rule.onNodeWithTag(FRAME).assertWidthIsEqualTo(next.width)
    }

    /**
     * A row as the list builds it: the asset's own facts through `rowFactsOf` — its condition rows (one, on [DAY], when
     * [condition] is given), whether an enabled #16 binding ([inbound]) or publish binding ([publishing]) names it, and
     * the list's season phase ([outOfSeason]).
     */
    private fun row(
        name: String,
        seasonMode: SeasonMode = SeasonMode.YEAR_ROUND,
        outOfSeason: Boolean = false,
        condition: OperationalCondition? = null,
        inbound: Boolean = false,
        publishing: Boolean = false,
        retiredOn: String? = null,
        status: AssetStatus = AssetStatus.ACTIVE,
        health: AssetHealthView? = null,
        tagged: Boolean = false,
        loan: LoanStanding? = null,
    ): AssetRow {
        val asset = Asset(
            id = AssetId(name.lowercase().replace(' ', '-')),
            name = name,
            category = "Example",
            status = status,
            retiredOn = retiredOn,
            seasonMode = seasonMode,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val facts = rowFactsOf(
            asset = asset,
            held = false,
            outOfSeason = outOfSeason,
            conditionRows = listOfNotNull(condition?.let { conditionRow(asset.id, it) }),
            inboundEnabled = inbound,
            publishingEnabled = publishing,
        )
        return AssetRow(
            asset = asset,
            outOfSeason = outOfSeason,
            hasWrittenTag = tagged,
            health = health,
            loan = loan,
            haLinked = facts.haLinked,
            condition = facts.condition,
            inSeason = facts.inSeason,
        )
    }

    private fun conditionRow(assetId: AssetId, condition: OperationalCondition) = AssetCondition(
        id = "c-${assetId.value}", assetId = assetId, condition = condition, occurredOn = DAY.toString(), occurredTime = null,
        tzId = "UTC", reason = "", eventId = null, createdAt = 1L,
    )

    private fun subject(name: String, score: Int, band: HealthBand) = SubjectHealth(
        subject = HealthSubject(
            id = HealthSubjectId(name), assetId = AssetId("example-heater"), name = name, kind = HealthSubjectKind.PART,
            driver = HealthDriver.AGE, scheduleId = null, baselineProfileId = null, nominalUntilDays = 0,
            warningFromDays = 40, criticalFromDays = 75, weight = 1, sortOrder = 0, archivedAt = null,
            createdAt = 1L, updatedAt = 1L,
        ),
        value = SubjectValue.Scored(score, band, 100L),
        lines = emptyList(),
    )

    /** DOWN, CRITICAL, two critical subjects and one DOWN component: #71's case (iii) health, on the heater. */
    private fun downCritical(): AssetHealthView {
        val critical = listOf(subject("Battery age", 12, HealthBand.CRITICAL), subject("Cooling fan age", 9, HealthBand.CRITICAL))
        return AssetHealthView(
            assetId = AssetId("example-heater"),
            computedForOn = DAY,
            condition = ConditionView(
                OperationalCondition.DOWN, since = DAY, reason = "", occurredOn = DAY, occurredTime = null, eventId = null,
                eventExists = false,
            ),
            aggregation = HealthAggregation.WORST,
            result = AssetHealthResult(
                subjects = critical, aggregate = SubjectValue.Scored(50, HealthBand.CRITICAL, null), fallback = false,
                critical = critical,
            ),
            components = listOf(ComponentCondition(AssetId("pack"), "Battery pack", OperationalCondition.DOWN, "Won't hold charge", DAY)),
            inService = true,
        )
    }

    // --- reading -------------------------------------------------------------------------------------

    private fun rowBounds(name: String): DpRect = rule.onNodeWithTag(ROW + name).getUnclippedBoundsInRoot()

    /** A mark or the disc: its box, found by its accessible name. */
    private fun mark(description: String): DpRect =
        rule.onNodeWithContentDescription(description, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun bounds(text: String): DpRect = rule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun described(description: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description))

    /** The Condition control: the clickable node named `Condition`, whatever it currently shows. */
    private fun conditionChip(): SemanticsNodeInteraction = rule.onNode(hasContentDescription("Condition") and hasClickAction())

    private fun <T> chipValue(key: SemanticsPropertyKey<T>): T? = conditionChip().fetchSemanticsNode().config.getOrNull(key)

    /** A row of the open menu: clickable, inside the popup. */
    private fun menuItem(): SemanticsMatcher = hasAnyAncestor(isPopup()) and hasClickAction()

    /**
     * A badge's box: its `Surface`, the label's second layout ancestor (a `Surface` adds no semantics node), checked to
     * hold the label with no more than a badge's padding and glyph beside it.
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

    /** Nothing of [text] is cut: no line lost below its box, and no line's ink past its box's right edge (#71's check). */
    private fun assertUnclipped(where: String, text: String) {
        val layout = textLayout(text)
        assertFalse("$where: '$text' is cut vertically", layout.didOverflowHeight)
        val box = with(rule.density) { width(bounds(text)).dp.toPx() }
        val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
        assertTrue("$where: '$text' runs ${widest}px in a ${box}px box", widest <= box + 1f)
    }

    /** [a] is drawn before [b]: left of it on the same line, or on an earlier line. */
    private fun before(a: DpRect, b: DpRect): Boolean =
        (a.right.value <= b.left.value + HALF && abs(a.top.value - b.top.value) <= HALF) || a.bottom.value <= b.top.value + HALF

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
        const val CONTROLS = "controls"
        const val HALF = 0.5f
        const val WAIT_MS = 10_000L
        const val PROOF_DIR = "issue-116-ui-proof"

        /** 7dp of padding each side, a 16dp glyph and a 4dp gap, with a margin: what a badge adds to its label. */
        const val PILL_EXTRA = 40f

        /** P116-1 and P116-3, ratified 2026-10-26; P71-4, ratified 2026-09-26. */
        const val HA = "Linked to Home Assistant"
        const val IN_SEASON = "In season"
        const val NFC = "NFC tag written"

        const val CRITICAL_BATTERY = "Critical: Battery age 12"
        const val CRITICAL_FAN = "Critical: Cooling fan age 9"
        const val COMPONENT_PACK = "Battery pack DOWN — Won't hold charge"

        /** S22 beside a condition badge: `since` and [DAY] in the app's display shape. */
        const val SINCE = "since 10 Apr 2026"

        val PHONE_412 = 412.dp
        val FRAMES = listOf(320.dp to 1.0f, 360.dp to 1.0f, 412.dp to 2.0f)
        val DAY: LocalDate = LocalDate.parse("2026-04-10")
    }
}
