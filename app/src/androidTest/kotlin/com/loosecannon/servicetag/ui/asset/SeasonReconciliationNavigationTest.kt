package com.loosecannon.servicetag.ui.asset

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetSettingsCommand
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.maintenance.SCHEDULES_SECTION
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.nav.ServiceTagRoot
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** How long a navigation and its effects are given to settle. */
private const val SETTLE_MILLIS = 10_000L

/**
 * #78 (C4) — "Review maintenance schedules" on the real back stack.
 *
 * **Why `ServiceTagRoot` and not the screens alone.** What is under test is the stack: the editor
 * leaves, the asset detail it was opened from is *replaced* by one opened on its schedules rather than
 * stacked under a second one, and a single back press then leaves the asset. A screen composed alone
 * has no stack, and inside `ServiceTagRoot` the nav entry decorator owns each entry's view-model store
 * (as `InspectNamesABoundTagTest` records), so the whole shell is composed here the way
 * `ReaderModeHoldTest` composes it, and it is driven only through the Compose test APIs: the bottom
 * bar, the list row, the detail's Edit, the editor's season answers, Save, and the dialog's button.
 * Back is the activity's own `OnBackPressedDispatcher`, which is what `NavDisplay` listens to.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class SeasonReconciliationNavigationTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val deepLinks = MutableSharedFlow<Route>(replay = 1, extraBufferCapacity = 4)
    private val snackbars = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    @Before fun freshInstall() = clearInstall()

    /**
     * An existing YEAR_ROUND asset with one CONTINUOUS schedule, opened from the Assets list and
     * edited into "Started and ended by hand", out of season. Review lands on the asset's schedules,
     * in view without a scroll; one back press returns to the list the asset was opened from, never
     * to a second, stale detail of the same asset.
     */
    @Test fun reviewMaintenanceSchedulesLandsOnTheAssetsSchedulesOnce() {
        val graph = app.graph
        runBlocking {
            val gen = graph.saveAssetSettings.run(
                null,
                AssetSettingsCommand(
                    asset = AssetCommand(name = "Generator", category = "Power"),
                    seasonMode = SeasonModeCommand(SeasonMode.YEAR_ROUND),
                    maintenanceBreak = BreakCommand(null, null),
                    healthPolicy = HealthPolicyCommand(HealthAggregation.WORST),
                ),
            ).id
            graph.saveSchedule.run(
                null,
                ScheduleCommand(
                    targetAssetId = gen,
                    targetGroupId = null,
                    title = "Weekly check",
                    timeInterval = 1,
                    timeUnit = RecurrenceUnit.WEEK,
                    anchorOn = LocalDate.now().toString(),
                ),
            )
        }
        rule.setContent {
            ServiceTagTheme {
                ServiceTagRoot(graph = graph, deepLinks = deepLinks, snackbars = snackbars)
            }
        }
        rule.awaitText("ServiceTag")

        // The Assets tab, then the asset's row, then its Edit action.
        rule.onNode(hasText("Assets") and isSelectable()).performClick()
        rule.awaitText("Show archived")
        rule.onNodeWithText("Generator").performClick()
        rule.awaitText("Readings & actions")
        rule.onNodeWithText("Edit").performScrollTo().performClick()

        // The owner's switch: started and ended by hand, out of season, saved. ("Edit asset" is the
        // editor's own title; "Operating season" is drawn on the detail as well.)
        rule.awaitText("Edit asset")
        rule.onNodeWithText(STARTED_AND_ENDED_BY_HAND).performScrollTo().performClick()
        rule.onNodeWithText(OUT_OF_SEASON_NOW).performScrollTo().performClick()
        rule.onNodeWithText("Save asset").performScrollTo().performClick()
        rule.awaitText(NOT_TIED_TO_SEASON_ONE)

        rule.onNodeWithText(REVIEW_MAINTENANCE_SCHEDULES).performClick()
        rule.waitUntil(SETTLE_MILLIS) { rule.onAllNodesWithText("Edit asset").fetchSemanticsNodes().isEmpty() }
        rule.awaitText("Weekly check")
        rule.waitForIdle()
        rule.onNodeWithText(SCHEDULES_SECTION).assertIsDisplayed()
        rule.onNodeWithText("Weekly check").assertIsDisplayed()

        // One press leaves the asset: the list it was opened from, and no detail of it left behind.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onAllNodesWithText("Readings & actions").assertCountEquals(0)
        rule.onNodeWithText("Show archived").assertIsDisplayed()
        rule.onNodeWithText("Generator").assertIsDisplayed()
    }
}
