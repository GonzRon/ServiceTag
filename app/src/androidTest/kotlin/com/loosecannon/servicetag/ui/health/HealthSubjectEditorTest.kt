package com.loosecannon.servicetag.ui.health

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetSettingsCommand
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.HealthSubjectCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.asset.ratifiedParts
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.maintenance.THE_SUBJECT_HEALTH_FOLLOWS
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The health subject editor on a real Compose tree (spec §10.4, §6.1, §6.3; B10).
 *
 * What only a real tree shows: that the three thresholds are drawn **empty** under their ratified
 * labels and that Save is really disabled with S126 drawn until all three hold a number; that the
 * digits filter holds on a real keyboard input; that S127 offers S128's two names and only S129's S130
 * fills the fields, while Cancel fills nothing; that S133 is a stepper; and that S136's two actions
 * flip, S137 is drawn for the primary, and Restore is disabled while its link would be refused. The
 * rules themselves are `HealthSubjectEditViewModelTest`'s.
 *
 * Store checks use `check`, not Kotlin's `assert`: the platform runs with assertions disabled.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class HealthSubjectEditorTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private val done = mutableListOf<String>()

    /** The editor for [assetId] on [initial]; the returned setter switches it to another subject. */
    private fun editor(graph: AppGraph, assetId: String, initial: String?): (String?) -> Unit {
        var shown by mutableStateOf(initial)
        rule.setContent {
            ServiceTagTheme {
                HealthSubjectEditScreen(
                    graph = graph,
                    assetId = assetId,
                    subjectId = shown,
                    onDone = { done += "saved" },
                    onBack = { done += "back" },
                )
            }
        }
        return { shown = it }
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    private fun empty() = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))

    private fun holds(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text))

    private val overdueLabels = ratifiedParts(OVERDUE_THRESHOLD_LABELS)

    /** A generator with one timed schedule; [aggregation] is how it combines its subjects. */
    private fun generator(
        graph: AppGraph,
        aggregation: HealthAggregation = HealthAggregation.WORST,
    ): Pair<AssetId, ScheduleId> = runBlocking {
        val asset = graph.saveAssetSettings.run(
            null,
            AssetSettingsCommand(
                asset = AssetCommand(name = "Generator"),
                seasonMode = SeasonModeCommand(SeasonMode.YEAR_ROUND),
                maintenanceBreak = BreakCommand(null, null),
                healthPolicy = HealthPolicyCommand(aggregation),
            ),
        ).id
        val schedule = graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = asset,
                targetGroupId = null,
                title = "Oil change",
                timeInterval = 6,
                timeUnit = RecurrenceUnit.MONTH,
                anchorOn = "2026-01-01",
            ),
        ).id
        asset to schedule
    }

    private fun overdue(schedule: ScheduleId, name: String) = HealthSubjectCommand(
        name = name,
        kind = HealthSubjectKind.ASSET,
        driver = HealthDriver.MAINTENANCE_OVERDUE,
        scheduleId = schedule,
        nominalUntilDays = 0,
        warningFromDays = 7,
        criticalFromDays = 30,
    )

    /** Name, kind, driver and schedule answered: only the thresholds are left. */
    private fun answerUpToTheThresholds() {
        // Blank, the name's label carries the required mark.
        field("$NAME *").performTextInput("Engine")
        field(NAME).assertExists()
        rule.onNodeWithText(THE_WHOLE_ASSET).performScrollTo().performClick()
        rule.onNodeWithText(OVERDUE_MAINTENANCE).performScrollTo().performClick()
        rule.onNodeWithText("Oil change").performScrollTo().performClick()
    }

    @Test fun emptyThresholdsDisableSave() {
        val graph = app.graph
        val (asset, _) = generator(graph, HealthAggregation.WEIGHTED)
        editor(graph, asset.value, null)
        rule.awaitText("$WHAT_IS_IT *")

        // Every answer the owner must give is marked by an asterisk until given; nothing is chosen.
        rule.onNodeWithText("$WHAT_IS_IT *").assertIsDisplayed()
        rule.onNodeWithText("$WHAT_WEARS_IT_DOWN *").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()

        answerUpToTheThresholds()
        rule.onNodeWithText(MAINTENANCE_SCHEDULE).assertExists()
        overdueLabels.forEach { label -> field(label).performScrollTo().assert(empty()) }
        rule.onNodeWithText(ENTER_ALL_THREE_NUMBERS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()

        // The filter: past 36,500 and not a digit, the keystroke is not taken.
        field(overdueLabels[2]).performScrollTo().performTextInput("36501")
        field(overdueLabels[2]).assert(empty())
        field(overdueLabels[0]).performScrollTo().performTextInput("0")
        field(overdueLabels[1]).performScrollTo().performTextInput("30")
        rule.onNodeWithText("Save").assertIsNotEnabled()
        field(overdueLabels[2]).performScrollTo().performTextInput("90")
        rule.onAllNodesWithText(ENTER_ALL_THREE_NUMBERS).assertCountEquals(0)

        // S133 under Weighted average: a stepper, one step at a time.
        rule.onNodeWithText(WEIGHT).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("−").assertIsNotEnabled()
        rule.onNodeWithText("+").performClick()
        rule.onNodeWithText("2").assertExists()

        rule.onNodeWithText("Save").assertIsEnabled().performClick()
        rule.waitUntil(10_000) { done.isNotEmpty() }
        val saved = runBlocking { graph.healthSubjects.forAsset(asset) }.single()
        val numbers = listOf(saved.nominalUntilDays, saved.warningFromDays, saved.criticalFromDays, saved.weight)
        check(numbers == listOf(0, 30, 90, 2)) {
            "the subject was not saved as typed: $saved"
        }
    }

    @Test fun startingPointConfirmation() {
        val graph = app.graph
        val (asset, _) = generator(graph)
        editor(graph, asset.value, null)
        rule.awaitText("$WHAT_IS_IT *")

        // Age since replacement has no starting point.
        rule.onNodeWithText(AGE_SINCE_REPLACEMENT).performScrollTo().performClick()
        rule.onNodeWithText(ANY_REPLACEMENT).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(USE_A_STARTING_POINT).assertCountEquals(0)

        answerUpToTheThresholds()
        val names = ratifiedParts(STARTING_POINT_NAMES)
        rule.onNodeWithText(USE_A_STARTING_POINT).performScrollTo().performClick()
        names.forEach { rule.onNodeWithText(it).assertIsDisplayed() }

        // Picking a name opens S129 and fills nothing; Cancel fills nothing either.
        rule.onNodeWithText(names[0]).performClick()
        rule.onNodeWithText(STARTING_POINTS_ARE_NOT_SAFETY_LIMITS).assertIsDisplayed()
        rule.onNodeWithText(USE_THESE_NUMBERS).assertIsDisplayed()
        overdueLabels.forEach { label -> field(label).assert(empty()) }
        rule.onNodeWithText("Cancel").performClick()
        rule.onAllNodesWithText(STARTING_POINTS_ARE_NOT_SAFETY_LIMITS).assertCountEquals(0)
        overdueLabels.forEach { label -> field(label).assert(empty()) }

        // Only S130 fills the three fields.
        rule.onNodeWithText(USE_A_STARTING_POINT).performScrollTo().performClick()
        rule.onNodeWithText(names[0]).performClick()
        rule.onNodeWithText(USE_THESE_NUMBERS).performClick()
        listOf("14", "45", "120").zip(overdueLabels).forEach { (value, label) -> field(label).assert(holds(value)) }
        rule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test fun archiveAndRestore() {
        val graph = app.graph
        val (asset, oil) = generator(graph)
        val (engine, belt) = runBlocking {
            val live = graph.saveHealthSubject.create(asset, overdue(oil, "Engine")).id
            // A second schedule, then an archived subject on it, then that schedule archived.
            val beltSchedule = graph.saveSchedule.run(
                null,
                ScheduleCommand(
                    targetAssetId = asset, targetGroupId = null, title = "Belt check",
                    timeInterval = 3, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01",
                ),
            ).id
            val beltSubject = graph.saveHealthSubject.create(asset, overdue(beltSchedule, "Belt wear")).id
            graph.archiveHealthSubject.run(beltSubject, true)
            graph.archiveSchedule.run(beltSchedule, true)
            live to beltSubject
        }
        val show = editor(graph, asset.value, engine.value)
        rule.awaitText(ARCHIVE_SUBJECT)

        rule.onNodeWithText(ARCHIVE_SUBJECT).performScrollTo().performClick()
        rule.awaitText(RESTORE_SUBJECT)
        check(runBlocking { graph.healthSubjects.get(engine) }?.archivedAt != null) { "Archive wrote nothing" }
        rule.onNodeWithText(RESTORE_SUBJECT).performScrollTo().assertIsEnabled().performClick()
        rule.awaitText(ARCHIVE_SUBJECT)
        check(runBlocking { graph.healthSubjects.get(engine) }?.archivedAt == null) { "Restore wrote nothing" }

        // Its schedule archived, the other subject cannot be restored until it is edited.
        show(belt.value)
        rule.awaitText(RESTORE_SUBJECT)
        rule.onNodeWithText(RESTORE_SUBJECT).performScrollTo().assertIsNotEnabled()
        rule.onAllNodesWithText("Belt check").assertCountEquals(0)

        // The subject asset health follows is not archived: S137 says why.
        runBlocking {
            graph.saveAssetSettings.run(
                asset,
                AssetSettingsCommand(
                    asset = AssetCommand(name = "Generator"),
                    seasonMode = SeasonModeCommand(SeasonMode.YEAR_ROUND),
                    maintenanceBreak = BreakCommand(null, null),
                    healthPolicy = HealthPolicyCommand(HealthAggregation.TRACK_ONE, engine),
                ),
            )
        }
        show(engine.value)
        rule.awaitText(ARCHIVE_SUBJECT)
        rule.onNodeWithText(ARCHIVE_SUBJECT).performScrollTo().performClick()
        rule.awaitText(THE_SUBJECT_HEALTH_FOLLOWS)
        check(runBlocking { graph.healthSubjects.get(engine) }?.archivedAt == null) { "the primary was archived" }
    }
}
