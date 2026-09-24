package com.loosecannon.servicetag.ui.maintenance

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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.HealthSubjectCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.reminders.NOTIFICATION_PERMISSION_RATIONALE
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
 * The schedule editor on a real Compose tree.
 *
 * What only a real tree shows: that a schedule can be created against **an asset** and against **a
 * group** and is in the store afterwards, that every ratified label is on screen **verbatim** — the
 * one place an upper-casing section header or a smoothed-out placeholder would be caught — and that
 * a group target draws **none** of its three forbidden controls. For 1.4 (B08): the service-policy
 * question, drawn only where the asset's season or break gives it meaning, its helpers and fields, the
 * health link guard's dialog, and the retired words' absence.
 *
 * Store checks use `check`, not Kotlin's `assert`: the platform runs with assertions disabled, so an
 * `assert` here would never fail.
 *
 * The permission request is deliberately **not** asserted here. Its timing and its denial are proved
 * against B05's seam in `ScheduleEditViewModelTest`, where the answer is injected; on a device the
 * answer depends on how the instrumentation installed the APK, so a test that required the rationale
 * would be asserting the installer. What this class does instead is get **past** it either way, and
 * assert that the schedule is in the store regardless — which is D-22's rule.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleEditorTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private fun editorFor(
        graph: AppGraph,
        targetAssetId: String? = null,
        targetGroupId: String? = null,
        scheduleId: String? = null,
    ): MutableList<String> {
        val saved = mutableListOf<String>()
        rule.setContent {
            ServiceTagTheme {
                ScheduleEditScreen(
                    graph = graph,
                    scheduleId = scheduleId,
                    targetAssetId = targetAssetId,
                    targetGroupId = targetGroupId,
                    onDone = { saved += it },
                    onBack = { saved += "back" },
                )
            }
        }
        return saved
    }

    /**
     * Taps Save and gets past the permission rationale if the phone shows one.
     *
     * "Not now" rather than "OK", because "OK" hands over to the system dialog, which is not this
     * suite's to drive — and dismissing is a real answer whose consequence is exactly what D-22
     * requires: the schedule stands.
     */
    private fun saveAndSettle(saved: MutableList<String>) {
        // The app bar's Save, which is the one that commits — and the only one, because this
        // form has no foot button (see `ScheduleEditScreen`).
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(10_000) {
            saved.isNotEmpty() ||
                rule.onAllNodesWithText(NOTIFICATION_PERMISSION_RATIONALE)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithText(NOTIFICATION_PERMISSION_RATIONALE).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Not now").performClick()
        }
        rule.waitUntil(10_000) { saved.isNotEmpty() }
    }

    /**
     * Matrix row **"a schedule cannot be created at all"**, the asset half — and every ratified
     * label of the time rule, verbatim.
     *
     * `SectionHeader` upper-cases its title, which would paraphrase a ratified sentence-case string;
     * this is the assertion that catches that, and the reason the editor draws its headings with the
     * Maintenance destination's sentence-case one instead.
     */
    @Test fun aScheduleIsCreatedAgainstAnAssetWithEveryRatifiedLabelVerbatim() {
        val graph = app.graph
        val mower = runBlocking {
            graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        }
        val saved = editorFor(graph, targetAssetId = mower.id.value)

        rule.awaitText("This applies to")
        rule.onNodeWithText("One asset").assertIsDisplayed()
        rule.onNodeWithText("Repeats from").assertIsDisplayed()
        rule.onNodeWithText("the scheduled date").assertIsDisplayed()
        rule.onNodeWithText("when I complete it").assertIsDisplayed()
        rule.onNodeWithText("Every N").assertExists()
        rule.onNodeWithText("Remind me N days early").assertExists()
        rule.onNodeWithText("Completing this takes").assertExists()
        rule.onNodeWithText("One tap").assertExists()
        rule.onNodeWithText("The full form").assertExists()
        rule.onNodeWithText("Remind me through").assertExists()

        rule.onNodeWithText("Name").performTextInput("Blade sharpen")
        rule.onNodeWithText("Every N").performTextInput("3")
        saveAndSettle(saved)

        val stored = runBlocking { graph.schedules.all() }
        check(stored.size == 1) { "one schedule, created: $stored" }
        check(stored.single().title == "Blade sharpen") { "wrong schedule: ${stored.single()}" }
        check(stored.single().target == ScheduleTarget.AssetTarget(mower.id)) { "wrong target" }
        // The permission answer cost nothing either way (D-22, invariant 61).
        check(stored.single().remindersEnabled) { "reminders were switched off: ${stored.single()}" }
        check(stored.single().providers.size == 1) { "one provider row: ${stored.single().providers}" }
    }

    /**
     * Matrix rows **"a schedule cannot be created at all"** (the group half) and **"an illegal group
     * schedule"**: a group target draws **no** meter rule, **no** profile picker and **no**
     * service-policy question at all (D-12; invariants 2, 3, 106).
     *
     * They are absent rather than disabled, which is what makes the illegal state unreachable through
     * the UI instead of merely refused on save — and the member asset really does carry a meter, so
     * the meter block's absence is an absence and not an empty list.
     */
    @Test fun aGroupScheduleIsCreatedAndOffersNoneOfItsThreeForbiddenControls() {
        val graph = app.graph
        val group = runBlocking {
            val head = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
            graph.saveDefinition.run(
                null,
                DefinitionCommand(
                    assetId = head.id,
                    key = "engine_hours",
                    label = "Engine hours",
                    unit = "h",
                    kind = DefinitionKind.ENTERED,
                    valueType = ValueType.NUMBER,
                    decimals = 0,
                    rangeLow = null,
                    rangeHigh = null,
                    isMeter = true,
                    formula = null,
                    sourceA = null,
                    sourceB = null,
                ),
            )
            graph.saveGroup.run(
                null,
                GroupCommand(name = "North run", members = listOf(GroupMemberInput(assetId = head.id))),
            )
        }
        val saved = editorFor(graph, targetGroupId = group.id.value)

        rule.awaitText("A maintenance group")
        // The three controls a group target does not get.
        rule.onAllNodesWithText("Also due by use").assertCountEquals(0)
        rule.onAllNodesWithText("Use this form").assertCountEquals(0)
        rule.onAllNodesWithText(QUESTION).assertCountEquals(0)
        rule.onAllNodesWithText("The full form").assertCountEquals(0)
        // And the one completion mode it does have, stated rather than silently defaulted.
        rule.onNodeWithText("One tap").assertExists()

        rule.onNodeWithText("Name").performTextInput("Head check")
        rule.onNodeWithText("Every N").performTextInput("3")
        saveAndSettle(saved)

        val stored = runBlocking { graph.schedules.all().single() }
        check(stored.target is ScheduleTarget.GroupTarget) { "wrong target: ${stored.target}" }
        check(stored.meterDefinitionId == null) { "a group target carries no meter rule" }
        check(stored.profileId == null) { "a group target carries no profile" }
        check(stored.completionMode == CompletionMode.QUICK) {
            "a group target is QUICK-only: ${stored.completionMode}"
        }
        check(stored.servicePolicy == ServicePolicy.CONTINUOUS) {
            "a group target is CONTINUOUS only: ${stored.servicePolicy}"
        }
    }

    /** An asset in a calendar season, November through March, made through the shipped commands. */
    private fun aSeasonalAsset(graph: AppGraph, name: String = "Snowblower"): AssetId = runBlocking {
        val asset = graph.createAsset.run(AssetCommand(name = name, category = "Yard"))
        graph.setSeasonMode.run(asset.id, SeasonModeCommand(SeasonMode.CALENDAR, "11-01", "03-31"))
        asset.id
    }

    /** The editor on a target the test can change, so one test can look at two targets. */
    private fun switchableEditor(graph: AppGraph, first: Pair<String?, String?>): (Pair<String?, String?>) -> Unit {
        var target by mutableStateOf(first)
        rule.setContent {
            ServiceTagTheme {
                ScheduleEditScreen(
                    graph = graph,
                    scheduleId = null,
                    targetAssetId = target.first,
                    targetGroupId = target.second,
                    onDone = {},
                    onBack = {},
                )
            }
        }
        return { next -> rule.runOnIdle { target = next } }
    }

    private fun gone(text: String) = rule.onAllNodesWithText(text).assertCountEquals(0)

    /**
     * Matrix row **"the screen"**: S65 is **not drawn** for a group target (inv. 106) nor for a
     * YEAR_ROUND asset without a break — not even one option of it — whatever the time rule.
     */
    @Test fun theQuestionIsHiddenForAGroupAndAYearRoundAssetWithoutABreak() {
        val graph = app.graph
        val (mower, group) = runBlocking {
            val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
            mower.id to graph.saveGroup.run(
                null,
                GroupCommand(name = "Yard run", members = listOf(GroupMemberInput(assetId = mower.id))),
            ).id
        }
        val switchTo = switchableEditor(graph, mower.value to null)

        rule.awaitText("One asset")
        rule.onNodeWithText("Every N").performTextInput("3")
        gone(QUESTION)
        gone("Whenever it is due")
        gone("When the season starts")

        switchTo(null to group.value)
        rule.awaitText("A maintenance group")
        rule.onNodeWithText("Every N").performTextInput("3")
        gone(QUESTION)
        gone("Whenever it is due")
        gone("When the season starts")
    }

    /**
     * Matrix row **"the screen"**: a CALENDAR asset's three answers, verbatim and in order of
     * appearance, with the chosen one's helper under it and no other helper. A new schedule starts on
     * S68 (master dec. 43); the before-option waits for a time rule.
     */
    @Test fun aCalendarAssetOffersThreeOptionsWithHelpers() {
        val graph = app.graph
        val asset = aSeasonalAsset(graph)
        editorFor(graph, targetAssetId = asset.value)

        rule.awaitText(QUESTION)
        gone("Before the season starts")
        rule.onNodeWithText("Every N").performTextInput("3")
        rule.onNodeWithText("Before the season starts").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("When the season starts").assertIsDisplayed()
        rule.onNodeWithText("Whenever it is due").assertIsDisplayed()
        val s78 = "A date in the season or the maintenance break becomes due on an allowed day before the season starts."
        val s80 = "This maintenance waits while the season is off and becomes active again when it starts."
        val s82 = "The season and the maintenance break never change when this is due."
        rule.onNodeWithText(s82).assertExists()
        gone(s78)
        gone(s80)

        rule.onNodeWithText("Before the season starts").performScrollTo().performClick()
        rule.onNodeWithText(s78).assertExists()
        gone(s80)
        gone(s82)

        rule.onNodeWithText("When the season starts").performScrollTo().performClick()
        rule.onNodeWithText(s80).assertExists()
        gone(s78)
        gone(s82)

        rule.onNodeWithText("Whenever it is due").performScrollTo().performClick()
        rule.onNodeWithText(s82).assertExists()
        gone(s78)
        gone(s80)
    }

    /** Matrix row **"the screen"**: S73 and its two answers appear only under S67; S72 only under S74. */
    @Test fun startCountingFromShowsOnlyUnderWhenTheSeasonStarts() {
        val graph = app.graph
        val asset = aSeasonalAsset(graph)
        editorFor(graph, targetAssetId = asset.value)

        rule.awaitText(QUESTION)
        rule.onNodeWithText("Every N").performTextInput("3")
        gone("Start counting from")
        gone("The season's start")

        rule.onNodeWithText("When the season starts").performScrollTo().performClick()
        rule.onNodeWithText("Start counting from").assertExists()
        rule.onNodeWithText("The season's start").assertExists()
        rule.onNodeWithText("Its own date, but not before the season starts").assertExists()
        rule.onNodeWithText("Days after it starts").assertExists()

        rule.onNodeWithText("Its own date, but not before the season starts").performScrollTo().performClick()
        gone("Days after it starts")

        rule.onNodeWithText("Before the season starts").performScrollTo().performClick()
        gone("Start counting from")
        gone("Its own date, but not before the season starts")
    }

    /**
     * Matrix row **"the screen"**: S71 starts **empty** with S83 under it and Save held (inv. 121); a
     * value above 365 is not accepted as typed; a margin releases Save.
     */
    @Test fun theDaysBeforeFieldStartsEmpty() {
        val graph = app.graph
        val asset = aSeasonalAsset(graph)
        editorFor(graph, targetAssetId = asset.value)

        rule.awaitText(QUESTION)
        rule.onNodeWithText("Name").performTextInput("Belt check")
        rule.onNodeWithText("Every N").performTextInput("3")
        rule.onNodeWithText("Save").assertIsEnabled()
        rule.onNodeWithText("Before the season starts").performScrollTo().performClick()

        val field = rule.onNode(hasSetTextAction() and hasText("Days before it starts"))
        field.performScrollTo()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        rule.onNodeWithText("Enter the number of days.").assertExists()
        rule.onNodeWithText("Save").assertIsNotEnabled()

        field.performTextInput("400")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        rule.onNodeWithText("Save").assertIsNotEnabled()

        field.performTextInput("14")
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("14")))
        gone("Enter the number of days.")
        rule.onNodeWithText("Save").assertIsEnabled()
    }

    /**
     * Matrix row **"the screen"**: removing the time rule of a schedule a health subject depends on asks
     * S140 naming the subject; Cancel closes it and writes nothing; "Archive both" saves and archives
     * the subject with it (spec §6.1, D-30; inv. 130).
     */
    @Test fun theLinkGuardDialogConfirmsAndCancels() {
        val graph = app.graph
        val (scheduleId, subjectId) = runBlocking {
            val generator = graph.createAsset.run(AssetCommand(name = "Generator", category = "Power"))
            val hours = graph.saveDefinition.run(
                null,
                DefinitionCommand(
                    assetId = generator.id,
                    key = "engine_hours",
                    label = "Engine hours",
                    unit = "h",
                    kind = DefinitionKind.ENTERED,
                    valueType = ValueType.NUMBER,
                    decimals = 0,
                    rangeLow = null,
                    rangeHigh = null,
                    isMeter = true,
                    formula = null,
                    sourceA = null,
                    sourceB = null,
                ),
            )
            val schedule = graph.saveSchedule.run(
                null,
                ScheduleCommand(
                    targetAssetId = generator.id,
                    targetGroupId = null,
                    title = "Oil change",
                    timeInterval = 6,
                    timeUnit = RecurrenceUnit.MONTH,
                    anchorOn = "2026-01-01",
                    meterDefinitionId = hours.id,
                    meterInterval = 100.0,
                ),
            )
            val subject = graph.saveHealthSubject.create(
                generator.id,
                HealthSubjectCommand(
                    name = "Engine oil",
                    kind = HealthSubjectKind.PART,
                    driver = HealthDriver.MAINTENANCE_OVERDUE,
                    scheduleId = schedule.id,
                    nominalUntilDays = 0,
                    warningFromDays = 7,
                    criticalFromDays = 30,
                ),
            )
            schedule.id to subject.id
        }
        val saved = editorFor(graph, scheduleId = scheduleId.value)
        val asks = "This schedule drives the health subject Engine oil. Archive that subject as well?"

        rule.awaitText("Oil change")
        rule.onNodeWithText("Every N").performTextClearance()
        rule.onNodeWithText("Save").performClick()
        rule.awaitText(asks)
        rule.onNodeWithText("Archive both").assertExists()

        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(asks).fetchSemanticsNodes().isEmpty() }
        runBlocking {
            check(graph.schedules.get(scheduleId)!!.timeInterval == 6) { "Cancel wrote the schedule" }
            check(graph.healthSubjects.get(subjectId)!!.archivedAt == null) { "Cancel archived the subject" }
        }
        check(saved.isEmpty()) { "Cancel left the editor: $saved" }

        rule.onNodeWithText("Save").performClick()
        rule.awaitText(asks)
        rule.onNodeWithText("Archive both").performClick()
        rule.waitUntil(10_000) { saved.isNotEmpty() }
        runBlocking {
            val stored = graph.schedules.get(scheduleId)!!
            check(stored.timeInterval == null) { "the time rule stayed: $stored" }
            check(stored.meterInterval == 100.0) { "the meter rule went: $stored" }
            check(graph.healthSubjects.get(subjectId)!!.archivedAt != null) { "the subject was not archived" }
        }
    }

    /**
     * Matrix row **"retired words linger"**: 1.2's two-option season choice and its heading are gone
     * from the editor, for an asset with a season and for a group alike (master plan §1).
     */
    @Test fun theRetiredOptionWordsAreGone() {
        val graph = app.graph
        val asset = aSeasonalAsset(graph)
        val group = runBlocking {
            graph.saveGroup.run(
                null,
                GroupCommand(name = "Yard run", members = listOf(GroupMemberInput(assetId = asset))),
            ).id
        }
        val switchTo = switchableEditor(graph, asset.value to null)
        val retired = listOf("Pause with the asset's season", "Remind me year round", "Out of season")

        rule.awaitText(QUESTION)
        rule.onNodeWithText("Every N").performTextInput("3")
        retired.forEach(::gone)

        switchTo(null to group.value)
        rule.awaitText("A maintenance group")
        retired.forEach(::gone)
    }

    private companion object {
        /** S65, verbatim. */
        const val QUESTION = "When should this maintenance be done?"
    }
}
