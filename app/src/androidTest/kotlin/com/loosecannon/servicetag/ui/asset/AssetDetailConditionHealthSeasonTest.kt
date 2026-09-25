package com.loosecannon.servicetag.ui.asset

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetSettingsCommand
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.HealthSubjectCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.condition.CHANGE_CONDITION
import com.loosecannon.servicetag.ui.condition.CONDITION_TITLE
import com.loosecannon.servicetag.ui.condition.END_SEASON
import com.loosecannon.servicetag.ui.condition.MARK_OPERATIONAL
import com.loosecannon.servicetag.ui.condition.MARK_OPERATIONAL_TITLE
import com.loosecannon.servicetag.ui.condition.START_SEASON
import com.loosecannon.servicetag.ui.condition.WHEN_DID_THIS_CHANGE
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Asset detail's Condition, Health and Season sections on a real Compose tree (spec §10.3; B14).
 *
 * What only a real tree shows: that the three sections are drawn **in that order** and the Health
 * section leads with its criticals and components; that every ratified word is drawn verbatim and in
 * its ratified case; that "Mark operational" and "Change condition" open B12's own surfaces; that the
 * Start and End dialogs refuse with S54 and write only on their confirm; and that S99 reads "1 day"
 * through `AndroidHealthPlurals` (the B12 carry-forward). The rules themselves are
 * `AssetViewModelsTest`'s and `AssetSeasonActionsTest`'s.
 *
 * Store checks use `check`/JUnit asserts, never Kotlin's `assert`: the platform runs with assertions
 * disabled. Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetDetailConditionHealthSeasonTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private val graph: AppGraph get() = app.graph
    private val today: LocalDate = LocalDate.now()
    private val zone: String get() = ZoneId.systemDefault().id

    /** Asset detail on [initial]; the returned setter switches it to another asset. */
    private fun detail(initial: AssetId): (AssetId) -> Unit {
        var shown by mutableStateOf(initial)
        rule.setContent {
            ServiceTagTheme {
                AssetDetailScreen(
                    graph = graph,
                    assetId = shown.value,
                    onBack = {}, onEdit = {}, onSetup = {}, onWriteTag = {}, onBackup = {},
                    onLogEvent = { _, _ -> }, onOpenEvent = {}, onOpenAsset = {}, onAddComponent = {},
                    onAddSchedule = {}, onLogOutcome = { _, _ -> }, onOpenSettings = {},
                    onOpenSchedule = {}, onOpenGroup = {},
                )
            }
        }
        return { shown = it }
    }

    private fun asset(name: String, parent: AssetId? = null): AssetId = runBlocking {
        graph.createAsset.run(AssetCommand(name = name, category = "Power", parentAssetId = parent)).id
    }

    private fun settings(name: String, mode: SeasonModeCommand, breakCmd: BreakCommand = BreakCommand(null, null)): AssetId =
        runBlocking {
            graph.saveAssetSettings.run(
                null,
                AssetSettingsCommand(
                    asset = AssetCommand(name = name),
                    seasonMode = mode,
                    maintenanceBreak = breakCmd,
                    healthPolicy = HealthPolicyCommand(HealthAggregation.WORST),
                ),
            ).id
        }

    private fun record(assetId: AssetId, condition: OperationalCondition, on: LocalDate, reason: String = "") = runBlocking {
        graph.recordCondition.run(
            assetId,
            ConditionCommand(condition = condition, occurredOn = on.toString(), tzId = zone, reason = reason),
        )
    }

    private fun replaced(assetId: AssetId, on: LocalDate) = runBlocking {
        graph.logEvent.run(
            EventCommand(
                assetId = assetId, profileId = null, kind = EventKind.REPLACEMENT, title = "Parts replaced",
                occurredOn = on.toString(), occurredTime = null, tzId = zone, notes = "",
                values = emptyMap(), consumables = emptyList(),
            ),
        )
    }

    private fun age(name: String, t1: Int, t2: Int, t3: Int) = HealthSubjectCommand(
        name = name, kind = HealthSubjectKind.PART, driver = HealthDriver.AGE,
        nominalUntilDays = t1, warningFromDays = t2, criticalFromDays = t3,
    )

    private fun activationRows(assetId: AssetId): List<SeasonActivation> =
        runBlocking { graph.seasonActivations.forAsset(assetId) }

    private fun conditionRows(assetId: AssetId): List<AssetCondition> = runBlocking { graph.conditions.forAsset(assetId) }

    /** Where a node sits on the page: sections are compared by their vertical order. */
    private fun SemanticsNodeInteraction.top(): Float = fetchSemanticsNode().positionInRoot.y

    private fun top(text: String, substring: Boolean = false): Float =
        rule.onNode(hasText(text, substring = substring)).top()

    private fun mmdd(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("MM-dd"))

    /**
     * Spec §10.3, §5.1, §5.3: the Condition section shows the current badge and reason, S7 beside S6
     * for a DOWN asset, and S21 with every row newest first — the row whose linked record is gone
     * reads S24 and is not a control. S7 opens B12's confirmation and writes one row; S6 opens B12's
     * sheet, and cancelling it writes nothing. Each component row carries its own badge.
     */
    @Test fun conditionSectionAndActions() {
        val gen = asset("Generator")
        val pack = asset("Battery pack", parent = gen)
        record(pack, OperationalCondition.DEGRADED, today.minusDays(4))
        record(gen, OperationalCondition.OPERATIONAL, today.minusDays(10))
        runBlocking {
            graph.conditions.insert(
                AssetCondition(
                    id = "dangling", assetId = gen, condition = OperationalCondition.DOWN,
                    occurredOn = today.minusDays(2).toString(), occurredTime = "08:30", tzId = zone,
                    reason = "Won't start", eventId = EventId("an-event-since-deleted"), createdAt = 5L,
                ),
            )
        }
        detail(gen)

        rule.awaitText(CONDITION_HISTORY)
        rule.onNodeWithText(CONDITION_TITLE).performScrollTo().assertIsDisplayed()
        // The badge, with S22, on the plate and again at the head of the section.
        rule.onAllNodesWithText("since ${displayDate(today.minusDays(2))}").assertCountEquals(2)
        rule.onNodeWithText(MARK_OPERATIONAL).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(CHANGE_CONDITION).assertIsDisplayed()
        // S24 in place of the link, the rest of the row kept, and nothing on it to tap.
        rule.onNode(hasText(LINKED_RECORD_REMOVED)).performScrollTo().assertIsDisplayed().assert(hasNoClickAction())
        rule.onNodeWithText("08:30").assertExists()
        // Newest first: the DOWN row above the older OPERATIONAL one.
        assertTrue(top("08:30") < top("OPERATIONAL"))
        // The component row's own badge.
        rule.onNodeWithText("DEGRADED").assertExists()
        // Condition before Health before Season, always (inv. 119).
        assertTrue(top(CONDITION_TITLE) < top(HEALTH_SECTION))
        assertTrue(top(HEALTH_SECTION) < top(YEAR_ROUND))

        rule.onNodeWithText(MARK_OPERATIONAL).performScrollTo().performClick()
        rule.awaitText(MARK_OPERATIONAL_TITLE)
        rule.onNodeWithText("The earlier DOWN record stays in the history.").assertIsDisplayed()
        rule.onNode(hasText(MARK_OPERATIONAL) and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(TIMEOUT_MS) { conditionRows(gen).size == 3 }
        // The page redraws on its own: S7 is gone, the history grew, the removed link still reads S24.
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithText(MARK_OPERATIONAL).fetchSemanticsNodes().isEmpty() }
        rule.onAllNodesWithText(LINKED_RECORD_REMOVED).assertCountEquals(1)

        rule.onNodeWithText(CHANGE_CONDITION).performScrollTo().performClick()
        rule.awaitText("Save condition")
        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithText("Save condition").fetchSemanticsNodes().isEmpty() }
        assertEquals("Cancel wrote nothing", 3, conditionRows(gen).size)
    }

    /**
     * Spec §10.3, §6.5 (inv. 119): an AVERAGE that reads NOMINAL still leads its Health section with
     * the CRITICAL subject (S109) and the DOWN component (S27), then the aggregate (S108), then each
     * contributor, and closes with S107.
     */
    @Test fun healthOrderCriticalsComponentsAggregateContributorsFooter() {
        val ups = asset("UPS")
        val pack = asset("Battery pack", parent = ups)
        record(pack, OperationalCondition.DOWN, today.minusDays(1), reason = "Won't hold charge")
        runBlocking {
            graph.saveHealthSubject.create(ups, age("Starter battery", 0, 40, 75))
            graph.saveHealthSubject.create(ups, age("Fan", 90, 100, 200))
            graph.saveHealthSubject.create(ups, age("Filter", 90, 100, 200))
            graph.setHealthPolicy.run(ups, HealthPolicyCommand(HealthAggregation.AVERAGE))
        }
        replaced(ups, today.minusDays(80))
        detail(ups)

        rule.awaitText("Critical: Starter battery 22")
        val critical = top("Critical: Starter battery 22")
        val component = top("Battery pack DOWN — Won't hold charge")
        val aggregate = top("74 — Starter battery 22, Fan 100, Filter 100")
        val contributor = top("Fan")
        val footer = top(HEALTH_FOOTER)
        assertTrue("criticals first", top(HEALTH_SECTION) < critical)
        assertTrue("then the components", critical < component)
        assertTrue("then the aggregate", component < aggregate)
        assertTrue("then the contributors", aggregate < contributor)
        assertTrue("then the footer", contributor < footer)
        rule.onNodeWithText(HEALTH_FOOTER).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("CRITICAL 22").assertExists()
        rule.onAllNodesWithText("NOMINAL 100").assertCountEquals(2)
    }

    /**
     * B12 carry-forward: S99's `<age>` renders through `AndroidHealthPlurals` on asset detail, and a
     * replacement one day old reads "1 day".
     */
    @Test fun s99ReadsOneDayThroughTheDayForms() {
        val gen = asset("Generator")
        runBlocking { graph.saveHealthSubject.create(gen, age("Belt", 0, 40, 75)) }
        replaced(gen, today.minusDays(1))
        detail(gen)

        rule.awaitText("Replaced ${displayDate(today.minusDays(1))}, 1 day ago")
        rule.onNodeWithText("Replaced ${displayDate(today.minusDays(1))}, 1 day ago").performScrollTo().assertIsDisplayed()
    }

    /**
     * Spec §3.3, §10.3: a MANUAL asset out of season shows its phase and S40 and no predicted start;
     * S48 lists its rows newest first. S40 opens S42 over S43 on today; a date before the latest row
     * is S54 and writes nothing; a date in range writes one START with that date, and the page turns
     * IN SEASON with S41. S41 opens S44 over S45, and Cancel writes nothing.
     */
    @Test fun manualSeasonStartDialogAndHistory() {
        val tub = settings("Hot tub", SeasonModeCommand(SeasonMode.YEAR_ROUND))
        runBlocking {
            graph.assets.upsert(graph.assets.get(tub)!!.copy(seasonMode = SeasonMode.MANUAL))
            graph.seasonActivations.insert(SeasonActivation("r1", tub, SeasonAction.START, today.minusDays(30).toString(), null, 1L))
            graph.seasonActivations.insert(SeasonActivation("r2", tub, SeasonAction.END, today.minusDays(10).toString(), null, 2L))
        }
        detail(tub)

        rule.awaitText(SEASON_HISTORY)
        rule.onNodeWithText(OUT_OF_SEASON).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Next season starts", substring = true).assertCountEquals(0)
        assertTrue("newest first", top(SEASON_ENDED) < top(SEASON_STARTED))

        rule.onNodeWithText(START_SEASON).performScrollTo().performClick()
        rule.awaitText(START_THE_SEASON)
        rule.onNodeWithText(startSeasonBody(displayDate(today))).assertIsDisplayed()
        val field = rule.onNode(hasSetTextAction() and hasText(WHEN_DID_THIS_CHANGE))
        field.performTextReplacement(today.minusDays(11).toString())
        rule.onNode(hasText(START_SEASON) and hasAnyAncestor(isDialog())).performClick()
        rule.awaitText(chooseADateFrom(displayDate(today.minusDays(10))))
        assertEquals("S54 wrote nothing", 2, activationRows(tub).size)

        field.performTextReplacement(today.minusDays(5).toString())
        rule.onNode(hasText(START_SEASON) and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(TIMEOUT_MS) { activationRows(tub).size == 3 }
        val started = activationRows(tub).single { it.id !in setOf("r1", "r2") }
        assertEquals(SeasonAction.START, started.action)
        assertEquals(today.minusDays(5).toString(), started.occurredOn)
        assertEquals(null, started.eventId)

        rule.awaitText(IN_SEASON_WORD)
        rule.onNodeWithText(END_SEASON).performScrollTo().assertIsDisplayed()
        val order = (rule.onAllNodesWithText(SEASON_STARTED).fetchSemanticsNodes() + rule.onAllNodesWithText(SEASON_ENDED).fetchSemanticsNodes())
            .sortedBy { it.positionInRoot.y }
            .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
        assertEquals(listOf(SEASON_STARTED, SEASON_ENDED, SEASON_STARTED), order)

        rule.onNodeWithText(END_SEASON).performClick()
        rule.awaitText(END_THE_SEASON)
        rule.onNodeWithText(END_SEASON_BODY).assertIsDisplayed()
        rule.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithText(END_THE_SEASON).fetchSemanticsNodes().isEmpty() }
        assertEquals("Cancel wrote nothing", 3, activationRows(tub).size)
    }

    /**
     * Spec §10.3: a CALENDAR asset in season reads S32 and S33, IN SEASON and S50; one out of season
     * reads the shipped out-of-season word and S49; a break inside the season adds S58 with S60 and
     * S61 and leaves the phase word alone. A year-round asset reads S29 and has no season history.
     */
    @Test fun calendarSeasonLines() {
        val mower = settings(
            "Mower",
            SeasonModeCommand(SeasonMode.CALENDAR, mmdd(today.minusDays(1)), mmdd(today.plusDays(1))),
            BreakCommand(mmdd(today), mmdd(today)),
        )
        val snow = settings("Snowblower", SeasonModeCommand(SeasonMode.CALENDAR, mmdd(today.plusDays(30)), mmdd(today.plusDays(31))))
        val gen = settings("Generator", SeasonModeCommand(SeasonMode.YEAR_ROUND))
        val show = detail(mower)

        rule.awaitText(seasonEndsLine(displayDate(today.plusDays(1))))
        rule.onNodeWithText(SEASON_STARTS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(SEASON_ENDS).assertIsDisplayed()
        rule.onNodeWithText(IN_SEASON_WORD).assertIsDisplayed()
        rule.onNodeWithText(MAINTENANCE_BREAK).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(BREAK_STARTS).assertIsDisplayed()
        rule.onNodeWithText(BREAK_ENDS).assertIsDisplayed()
        rule.onAllNodesWithText(SEASON_HISTORY).assertCountEquals(0)

        show(snow)
        rule.awaitText(nextSeasonStartsLine(displayDate(today.plusDays(30))))
        rule.onNodeWithText(OUT_OF_SEASON).performScrollTo().assertIsDisplayed()
        // The plate keeps its shipped badge; the section's word is drawn as ratified.
        rule.onAllNodesWithText("OUT OF SEASON").assertCountEquals(1)
        rule.onAllNodesWithText(IN_SEASON_WORD).assertCountEquals(0)

        show(gen)
        rule.awaitText(YEAR_ROUND)
        rule.onNodeWithText(YEAR_ROUND).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(SEASON_HISTORY).assertCountEquals(0)
        rule.onAllNodesWithText(MAINTENANCE_BREAK).assertCountEquals(0)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
