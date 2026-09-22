package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.di.AppGraph
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
 * The shell B15, B10, B14 and B09 land inside, and the bottom bar that reaches it.
 *
 * Two things only a real Compose tree shows: that the bar really draws three items with the
 * ratified label, and that the destination's four sections each reach something — a shell with
 * three working sections is how 1.2 would ship an engine nobody can drive.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class MaintenanceShellTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    /** A schedule to list, a group to open, and the destination drawn over them. */
    private fun aStoreWithWork(record: MutableList<String>): AppGraph {
        val graph = app.graph
        runBlocking {
            val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
            val head = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
            graph.saveSchedule.run(
                null,
                ScheduleCommand(
                    targetAssetId = mower.id,
                    targetGroupId = null,
                    title = "Blade sharpen",
                    timeInterval = 3,
                    timeUnit = RecurrenceUnit.MONTH,
                    anchorOn = java.time.LocalDate.now().toString(),
                ),
            )
            graph.saveGroup.run(
                null,
                GroupCommand(name = "North run", members = listOf(GroupMemberInput(assetId = head.id))),
            )
        }
        rule.setContent {
            ServiceTagTheme {
                MaintenanceScreen(
                    graph = graph,
                    onOpenSchedule = { record += "schedule:$it" },
                    onOpenGroup = { record += "group:$it" },
                    onReminderHealth = { record += "health" },
                    onScanTag = { record += "scan" },
                    onAddAsset = { record += "asset" },
                    onLogMaintenance = { record += "log" },
                )
            }
        }
        return graph
    }

    /**
     * The four sections, and one navigation out of each: a due row and a schedule row to a
     * schedule, a group row to its group, and the Reminders row to reminder health. Four
     * navigations from one screen.
     */
    @Test fun theFourSectionsEachReachSomething() {
        val record = mutableListOf<String>()
        aStoreWithWork(record)

        rule.awaitText("Due work")
        rule.onNodeWithText("Schedules").assertIsDisplayed()
        rule.onNodeWithText("Maintenance groups").assertIsDisplayed()
        rule.onNodeWithText("Reminders").assertIsDisplayed()

        // A due row. The title appears twice — once under Due work, once under Schedules — so the
        // first node is taken deliberately rather than by an ambiguous single-match lookup.
        rule.onAllNodes(hasText("Blade sharpen") and hasClickAction())[0].performClick()
        rule.onAllNodes(hasText("North run") and hasClickAction())[0].performClick()
        rule.onNodeWithText("Reminders").performClick()

        rule.runOnIdle {
            assert(record.size == 3) { "three navigations, not $record" }
            assert(record[0].startsWith("schedule:")) { "the due row opens a schedule, not ${record[0]}" }
            assert(record[1].startsWith("group:")) { "the group row opens a group, not ${record[1]}" }
            assert(record[2] == "health") { "the Reminders row opens reminder health, not ${record[2]}" }
        }
    }

    /**
     * F4's three persistent quick actions. "Scan tag" and "Add asset" reach the shipped routes;
     * "Log maintenance" calls the completion flow's seam and **writes nothing** — the event count
     * is unchanged after the tap, which is the behavioural half of #50's rule.
     */
    @Test fun theThreeQuickActionsArePresentAndLogMaintenanceWritesNothing() {
        val record = mutableListOf<String>()
        val graph = aStoreWithWork(record)

        rule.awaitText("Log maintenance")
        rule.onNodeWithText("Scan tag").assertIsDisplayed()
        rule.onNodeWithText("Add asset").assertIsDisplayed()

        val before = runBlocking { graph.events.all().size }
        rule.onNodeWithText("Log maintenance").performClick()
        rule.onNodeWithText("Scan tag").performClick()
        rule.onNodeWithText("Add asset").performClick()

        rule.runOnIdle {
            assert(record.containsAll(listOf("log", "scan", "asset"))) { "missing an action: $record" }
        }
        // The flow B14 owns is what writes; this screen's tap wrote nothing on its own.
        val after = runBlocking { graph.events.all().size }
        assert(before == after) { "the quick action wrote an event: $before -> $after" }
    }

    /** The empty state, verbatim, on a phone with nothing scheduled. */
    @Test fun aPhoneWithNoSchedulesSaysSo() {
        val record = mutableListOf<String>()
        val graph = app.graph
        rule.setContent {
            ServiceTagTheme {
                MaintenanceScreen(
                    graph = graph,
                    onOpenSchedule = { record += "schedule:$it" },
                    onOpenGroup = { record += "group:$it" },
                    onReminderHealth = { record += "health" },
                    onScanTag = {},
                    onAddAsset = {},
                    onLogMaintenance = {},
                )
            }
        }

        rule.awaitText("No maintenance schedules yet. Add one from an asset or a maintenance group.")
        // Reminders is still reachable: a phone with no schedules can still have blocked ones.
        rule.onNodeWithText("Reminders").performClick()
        rule.runOnIdle { assert(record == listOf("health")) { "unexpected navigation: $record" } }
    }
}

/**
 * The bar itself, on the real activity: three items, the third carrying the ratified label, and
 * tapping it lands on the destination rather than stacking it on whatever was there.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class MaintenanceTabTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun freshInstall() = clearInstall()

    @Test fun theThirdTabOpensTheMaintenanceDestination() {
        rule.onNode(hasText("Dashboard") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("Assets") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("Maintenance") and hasClickAction()).performClick()

        // The destination's own title and its four section labels. "Maintenance" now appears twice
        // — the tab and the title — which is what the count asserts.
        rule.awaitText("Maintenance", count = 2)
        rule.awaitText("Maintenance groups")
        rule.awaitText("Reminders")

        // Scan is still not a tab (D12 §16 correction): the third slot is Maintenance, not Scan.
        rule.onAllNodes(hasText("Scan") and hasClickAction()).assertCountEquals(0)
    }
}
