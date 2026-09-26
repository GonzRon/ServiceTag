package com.loosecannon.servicetag.ui.asset

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetSettingsCommand
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.HealthSubjectCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.health.RESTORE_SUBJECT
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The asset editor's season, break and health sections on a real Compose tree (spec §10.4; B10).
 *
 * What only a real tree shows: that every ratified word is drawn **verbatim** — "Operating season" and
 * its neighbours are sentence case, which the shipped upper-casing section header would have
 * paraphrased — that S35 is drawn on a switch into MANUAL with neither answer chosen and never on an
 * asset already MANUAL, that the break's fields appear empty only when S59 is turned on, that the
 * required mark is an asterisk in the label and the held Save is really disabled, that the subject
 * rows open the subject editor and S134 lists only live subjects, and that the retired sentence is
 * gone. The rules themselves are `AssetSettingsFormTest`'s.
 *
 * Store checks use `check`, not Kotlin's `assert`: the platform runs with assertions disabled.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class AssetEditorSeasonAndHealthTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    /** What the screen asked its host to do: a saved id, a subject editor to open, or schedules to review. */
    private val asked = mutableListOf<String>()

    /** The editor on [initial]; the returned setter switches it to another asset (null is a new one). */
    private fun editor(graph: AppGraph, initial: String?): (String?) -> Unit {
        var shown by mutableStateOf(initial)
        rule.setContent {
            ServiceTagTheme {
                AssetEditScreen(
                    graph = graph,
                    assetId = shown,
                    onDone = { asked += "saved:$it" },
                    onBack = { asked += "back" },
                    onAddSubject = { asked += "add:$it" },
                    onOpenSubject = { asset, subject -> asked += "open:$asset:$subject" },
                    onReviewSchedules = { asked += "review:$it" },
                )
            }
        }
        return { shown = it }
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    private fun saveAsset() = rule.onNodeWithText("Save asset").performScrollTo()

    private fun settings(name: String, mode: SeasonModeCommand) = AssetSettingsCommand(
        asset = AssetCommand(name = name),
        seasonMode = mode,
        maintenanceBreak = BreakCommand(null, null),
        healthPolicy = HealthPolicyCommand(HealthAggregation.WORST),
    )

    @Test fun operatingSeasonOptionsAndFields() {
        editor(app.graph, null)
        rule.awaitText(OPERATING_SEASON)

        rule.onNodeWithText(OPERATING_SEASON).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(YEAR_ROUND).performScrollTo().assertIsSelected()
        rule.onNodeWithText(SAME_DATES_EVERY_YEAR).assertIsNotSelected()
        rule.onNodeWithText(STARTED_AND_ENDED_BY_HAND).assertIsNotSelected()
        rule.onAllNodesWithText(SEASON_STARTS, substring = true).assertCountEquals(0)
        // A new asset has no subjects to list or combine yet (master dec. 39).
        rule.onAllNodesWithText(HEALTH_SUBJECTS).assertCountEquals(0)
        rule.onAllNodesWithText(COMBINE_HEALTH_BY).assertCountEquals(0)

        rule.onNodeWithText(SAME_DATES_EVERY_YEAR).performScrollTo().performClick()
        rule.onNodeWithText(SAME_DATES_EVERY_YEAR).assertIsSelected()
        // Both fields empty and marked required by an asterisk, never by a word; Save is held.
        field("$SEASON_STARTS *").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        field("$SEASON_ENDS *").assertExists()
        rule.onNodeWithText(SEASON_MAY_RUN_ACROSS_THE_NEW_YEAR).performScrollTo().assertIsDisplayed()
        saveAsset().assertIsNotEnabled()
        rule.onNodeWithText("Save").assertIsNotEnabled()

        field("$SEASON_STARTS *").performTextInput("11-01")
        saveAsset().assertIsNotEnabled()
        field("$SEASON_ENDS *").performScrollTo().performTextInput("13-31")
        rule.onNodeWithText("Not a real month and day").assertExists()
        // The outline is drawn from the state's flag (review M4): on the bad value only.
        field("$SEASON_ENDS *").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        field("$SEASON_STARTS *").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        saveAsset().assertIsNotEnabled()

        rule.onNodeWithText(STARTED_AND_ENDED_BY_HAND).performScrollTo().performClick()
        rule.onAllNodesWithText(SEASON_MAY_RUN_ACROSS_THE_NEW_YEAR).assertCountEquals(0)
        rule.onNodeWithText(YOU_START_AND_END_THE_SEASON).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("$IS_THIS_ASSET_IN_SEASON *").assertExists()
    }

    @Test fun theManualQuestion() {
        val graph = app.graph
        val tub = runBlocking {
            graph.saveAssetSettings.run(
                null,
                settings("Hot tub", SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.IN_SEASON)),
            ).id.value
        }
        val show = editor(graph, null)
        rule.awaitText(OPERATING_SEASON)
        field("Name").performTextInput("Mower")

        rule.onNodeWithText(STARTED_AND_ENDED_BY_HAND).performScrollTo().performClick()
        rule.onNodeWithText("$IS_THIS_ASSET_IN_SEASON *").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(IN_SEASON_NOW).performScrollTo().assertIsNotSelected()
        rule.onNodeWithText(OUT_OF_SEASON_NOW).assertIsNotSelected()
        saveAsset().assertIsNotEnabled()

        rule.onNodeWithText(OUT_OF_SEASON_NOW).performScrollTo().performClick()
        rule.onNodeWithText(OUT_OF_SEASON_NOW).assertIsSelected()
        rule.onNodeWithText(IS_THIS_ASSET_IN_SEASON).assertExists()
        saveAsset().assertIsEnabled()

        // An asset already MANUAL is never asked.
        show(tub)
        rule.awaitText("Hot tub")
        rule.onNodeWithText(STARTED_AND_ENDED_BY_HAND).performScrollTo().assertIsSelected()
        rule.onNodeWithText(YOU_START_AND_END_THE_SEASON).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(IS_THIS_ASSET_IN_SEASON, substring = true).assertCountEquals(0)
        rule.onAllNodesWithText(IN_SEASON_NOW).assertCountEquals(0)
        saveAsset().assertIsEnabled()
    }

    @Test fun theBreakToggle() {
        val graph = app.graph
        editor(graph, null)
        rule.awaitText(MAINTENANCE_BREAK)
        field("Name").performTextInput("Generator")

        rule.onNodeWithText(MAINTENANCE_BREAK).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(NO_ROUTINE_MAINTENANCE_BETWEEN_TWO_DATES).performScrollTo().assertIsOff()
        rule.onAllNodesWithText(BREAK_STARTS, substring = true).assertCountEquals(0)
        rule.onAllNodesWithText(BREAK_HELPER).assertCountEquals(0)

        rule.onNodeWithText(NO_ROUTINE_MAINTENANCE_BETWEEN_TWO_DATES).performClick()
        rule.onNodeWithText(NO_ROUTINE_MAINTENANCE_BETWEEN_TWO_DATES).assertIsOn()
        field("$BREAK_STARTS *").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        field("$BREAK_ENDS *")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        rule.onNodeWithText(BREAK_HELPER).performScrollTo().assertIsDisplayed()
        saveAsset().assertIsNotEnabled()

        field("$BREAK_STARTS *").performScrollTo().performTextInput("12-01")
        field("$BREAK_ENDS *").performScrollTo().performTextInput("02-28")
        field(BREAK_STARTS).assertExists()
        saveAsset().assertIsEnabled().performClick()

        rule.waitUntil(10_000) { asked.any { it.startsWith("saved:") } }
        val id = asked.first { it.startsWith("saved:") }.removePrefix("saved:")
        val stored = runBlocking { graph.assets.get(AssetId(id)) }
        check(stored?.blackoutStartMmdd == "12-01" && stored.blackoutEndMmdd == "02-28") {
            "the break was not saved: $stored"
        }
    }

    @Test fun healthSubjectsAndCombine() {
        val graph = app.graph
        val (ups, battery, fan) = runBlocking {
            val asset = graph.saveAssetSettings.run(null, settings("UPS", SeasonModeCommand(SeasonMode.YEAR_ROUND))).id
            fun age(name: String) = HealthSubjectCommand(
                name = name, kind = HealthSubjectKind.PART, driver = HealthDriver.AGE,
                nominalUntilDays = 0, warningFromDays = 700, criticalFromDays = 1000,
            )
            val live = graph.saveHealthSubject.create(asset, age("Battery age")).id
            val gone = graph.saveHealthSubject.create(asset, age("Fan")).id
            graph.archiveHealthSubject.run(gone, true)
            Triple(asset.value, live.value, gone.value)
        }
        editor(graph, ups)
        rule.awaitText("Battery age")

        rule.onNodeWithText(HEALTH_SUBJECTS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Battery age").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Fan").performScrollTo().assertIsDisplayed()
        // The archived one is marked by its S136 action, once.
        rule.onAllNodesWithText(RESTORE_SUBJECT).assertCountEquals(1)

        rule.onNodeWithText(ADD_HEALTH_SUBJECT).performScrollTo().performClick()
        rule.onNodeWithText("Fan").performScrollTo().performClick()
        check(asked == listOf("add:$ups", "open:$ups:$fan")) { "the subject editor was not opened as asked: $asked" }
        check(runBlocking { graph.healthSubjects.get(HealthSubjectId(fan)) }?.archivedAt != null) {
            "opening a subject wrote something"
        }

        rule.onNodeWithText(COMBINE_HEALTH_BY).performScrollTo().assertIsDisplayed()
        val words = ratifiedParts(COMBINE_HEALTH_OPTIONS)
        check(words == listOf("Worst subject", "One subject", "Average", "Weighted average")) { "S132 split wrongly: $words" }
        words.forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithText("Worst subject").assertIsSelected()

        rule.onNodeWithText("One subject").performClick()
        rule.onNodeWithText("$WHICH_SUBJECT *").performScrollTo().assertIsDisplayed()
        // Under S134, only the live subject: "Battery age" is now drawn twice (the list and the
        // choice), "Fan" still once.
        rule.onAllNodesWithText("Battery age").assertCountEquals(2)
        rule.onAllNodesWithText("Fan").assertCountEquals(1)
        rule.onNodeWithText("Save").assertIsNotEnabled()

        rule.onAllNodesWithText("Battery age")[1].performScrollTo().performClick()
        rule.onNodeWithText(WHICH_SUBJECT).assertExists()
        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        rule.waitUntil(10_000) { asked.any { it.startsWith("saved:") } }
        val stored = runBlocking { graph.assets.get(AssetId(ups)) }
        check(stored?.healthAggregation == HealthAggregation.TRACK_ONE && stored.healthPrimarySubjectId?.value == battery) {
            "the policy was not saved: $stored"
        }
    }

    @Test fun theRetiredSeasonSentenceIsGone() {
        val graph = app.graph
        val snow = runBlocking {
            graph.saveAssetSettings.run(
                null,
                settings("Snowblower", SeasonModeCommand(SeasonMode.CALENDAR, "11-01", "03-31")),
            ).id.value
        }
        val show = editor(graph, null)
        rule.awaitText(OPERATING_SEASON)
        rule.onAllNodesWithText("Off means this asset is only in use", substring = true).assertCountEquals(0)

        show(snow)
        rule.awaitText("Snowblower")
        rule.onNodeWithText(SAME_DATES_EVERY_YEAR).performScrollTo().assertIsSelected()
        field(SEASON_STARTS).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("11-01")))
        rule.onAllNodesWithText("Off means this asset is only in use", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Set both season dates or neither").assertCountEquals(0)
    }

    /**
     * #78: a YEAR_ROUND asset with one schedule set to "Whenever it is due" (CONTINUOUS), whose
     * editor is open on it. Returns the asset's id.
     */
    private fun yearRoundWithOneContinuousSchedule(graph: AppGraph): String {
        val gen = runBlocking {
            val asset = graph.saveAssetSettings.run(null, settings("Generator", SeasonModeCommand(SeasonMode.YEAR_ROUND))).id
            graph.saveSchedule.run(
                null,
                ScheduleCommand(
                    targetAssetId = asset,
                    targetGroupId = null,
                    title = "Weekly check",
                    timeInterval = 1,
                    timeUnit = RecurrenceUnit.WEEK,
                    anchorOn = LocalDate.now().toString(),
                ),
            )
            asset.value
        }
        editor(graph, gen)
        rule.awaitText("Generator")
        return gen
    }

    /** S31 with S35 answered "Out of season", then Save: the switch the owner made in #78. */
    private fun switchToManualOutOfSeasonAndSave() {
        rule.onNodeWithText(STARTED_AND_ENDED_BY_HAND).performScrollTo().performClick()
        rule.onNodeWithText(OUT_OF_SEASON_NOW).performScrollTo().performClick()
        saveAsset().assertIsEnabled().performClick()
        rule.awaitText(NOT_TIED_TO_SEASON_ONE)
    }

    /** Every word drawn inside the dialog, in the merged tree: the body and the two buttons. */
    private fun dialogWords(): List<String> =
        rule.onAllNodes(hasAnyAncestor(isDialog())).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
            .sorted()

    /** The event, closure and activation counts: what a fabricated row would move. */
    private fun historyCounts(graph: AppGraph): List<Int> = runBlocking {
        listOf(graph.events.all().size, graph.closures.all().size, graph.seasonActivations.all().size)
    }

    /**
     * #78 (C3, R-2): the switch is saved first, then P78-1b is drawn over the form with both buttons
     * and nothing else — no title, no third line — and the editor waits. "Keep schedules as-is" then
     * finishes it exactly as a save that asked nothing.
     */
    @Test fun switchingAYearRoundAssetToManualAsksAboutItsContinuousSchedules() {
        val graph = app.graph
        val gen = yearRoundWithOneContinuousSchedule(graph)
        switchToManualOutOfSeasonAndSave()

        rule.onNodeWithText(NOT_TIED_TO_SEASON_ONE).assertIsDisplayed()
        rule.onNodeWithText(REVIEW_MAINTENANCE_SCHEDULES).assertIsDisplayed()
        rule.onNodeWithText(KEEP_SCHEDULES_AS_IS).assertIsDisplayed()
        check(dialogWords() == listOf(KEEP_SCHEDULES_AS_IS, REVIEW_MAINTENANCE_SCHEDULES, NOT_TIED_TO_SEASON_ONE).sorted()) {
            "the dialog draws more than its ratified words: ${dialogWords()}"
        }
        check(asked.isEmpty()) { "the editor finished before the owner answered: $asked" }
        check(runBlocking { graph.assets.get(AssetId(gen)) }?.seasonMode == SeasonMode.MANUAL) {
            "the season was not saved before the question"
        }

        rule.onNodeWithText(KEEP_SCHEDULES_AS_IS).performClick()
        rule.waitUntil(10_000) { asked.isNotEmpty() }
        rule.waitForIdle()
        check(asked == listOf("saved:$gen")) { "Keep schedules as-is did not finish the editor: $asked" }
        rule.onAllNodesWithText(NOT_TIED_TO_SEASON_ONE).assertCountEquals(0)
    }

    /**
     * #78 (C3, C5): the system back gesture on the question is "Keep schedules as-is" — the editor
     * finishes through `saved`, never `review` — and nothing is written: the schedule row is what it
     * was before the save, and no event, closure or activation appears after the question.
     */
    @Test fun theBackGestureOnTheSeasonPromptKeepsTheSchedules() {
        val graph = app.graph
        val gen = yearRoundWithOneContinuousSchedule(graph)
        val before = runBlocking { graph.schedules.forAsset(AssetId(gen)) }
        switchToManualOutOfSeasonAndSave()
        val counts = historyCounts(graph)

        // The dialog is its own window, with its own back dispatcher: the key goes to it, as a
        // gesture does, rather than to the activity's dispatcher underneath.
        Espresso.pressBack()
        rule.waitUntil(10_000) { asked.isNotEmpty() }
        rule.waitForIdle()
        check(asked == listOf("saved:$gen")) { "back did not answer Keep schedules as-is: $asked" }
        rule.onAllNodesWithText(NOT_TIED_TO_SEASON_ONE).assertCountEquals(0)
        check(runBlocking { graph.schedules.forAsset(AssetId(gen)) } == before) { "a schedule row was written" }
        check(historyCounts(graph) == counts) { "a history row was written: $counts → ${historyCounts(graph)}" }
    }
}
