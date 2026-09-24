package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
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
 * a group target draws **none** of its three forbidden controls.
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
    ): MutableList<String> {
        val saved = mutableListOf<String>()
        rule.setContent {
            ServiceTagTheme {
                ScheduleEditScreen(
                    graph = graph,
                    scheduleId = null,
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
        rule.onNodeWithText("Out of season").assertExists()
        rule.onNodeWithText("Pause with the asset's season").assertExists()
        rule.onNodeWithText("Remind me year round").assertExists()
        rule.onNodeWithText("Completing this takes").assertExists()
        rule.onNodeWithText("One tap").assertExists()
        rule.onNodeWithText("The full form").assertExists()
        rule.onNodeWithText("Remind me through").assertExists()

        rule.onNodeWithText("Name").performTextInput("Blade sharpen")
        rule.onNodeWithText("Every N").performTextInput("3")
        saveAndSettle(saved)

        val stored = runBlocking { graph.schedules.all() }
        assert(stored.size == 1) { "one schedule, created: $stored" }
        assert(stored.single().title == "Blade sharpen") { "wrong schedule: ${stored.single()}" }
        assert(stored.single().target == ScheduleTarget.AssetTarget(mower.id)) { "wrong target" }
        // The permission answer cost nothing either way (D-22, invariant 61).
        assert(stored.single().remindersEnabled) { "reminders were switched off: ${stored.single()}" }
        assert(stored.single().providers.size == 1) { "one provider row: ${stored.single().providers}" }
    }

    /**
     * Matrix rows **"a schedule cannot be created at all"** (the group half) and **"an illegal group
     * schedule"**: a group target draws **no** meter rule, **no** profile picker and **no**
     * `FOLLOW_ASSET` option at all (D-12, D-28; invariants 2, 3, 27).
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
        rule.onAllNodesWithText("Pause with the asset's season").assertCountEquals(0)
        rule.onAllNodesWithText("The full form").assertCountEquals(0)
        // And the one option each of those choices does have, stated rather than silently defaulted.
        rule.onNodeWithText("Remind me year round").assertExists()
        rule.onNodeWithText("One tap").assertExists()

        rule.onNodeWithText("Name").performTextInput("Head check")
        rule.onNodeWithText("Every N").performTextInput("3")
        saveAndSettle(saved)

        val stored = runBlocking { graph.schedules.all().single() }
        assert(stored.target is ScheduleTarget.GroupTarget) { "wrong target: ${stored.target}" }
        assert(stored.meterDefinitionId == null) { "a group target carries no meter rule" }
        assert(stored.profileId == null) { "a group target carries no profile" }
        assert(stored.completionMode == CompletionMode.QUICK) {
            "a group target is QUICK-only: ${stored.completionMode}"
        }
        assert(stored.servicePolicy == ServicePolicy.CONTINUOUS) {
            "a group target is CONTINUOUS only: ${stored.servicePolicy}"
        }
    }
}
