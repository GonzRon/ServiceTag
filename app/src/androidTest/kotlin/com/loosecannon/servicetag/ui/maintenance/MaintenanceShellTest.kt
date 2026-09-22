package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TimeBasis
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
            // A paused schedule: the shell lists it under Schedules and Due work omits it, which
            // is the one row that tells the two sections apart.
            val paused = graph.saveSchedule.run(
                null,
                ScheduleCommand(
                    targetAssetId = mower.id,
                    targetGroupId = null,
                    title = "Winter service",
                    timeInterval = 1,
                    timeUnit = RecurrenceUnit.YEAR,
                    anchorOn = java.time.LocalDate.now().toString(),
                ),
            )
            graph.pauseSchedule.run(paused.id, paused = true)
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
                    onNewGroup = { record += "new-group" },
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
     * **Four** navigations from one screen, one out of each section: a Due-work row and a
     * Schedules-only row both to a schedule, a group row to its group, and the Reminders row to
     * reminder health.
     *
     * The two schedule taps are told apart by the id each one reports, which is why the second is
     * the **paused** schedule — it is listed under Schedules and nowhere else, so tapping it can
     * only have come from that section. Both call the same `onOpenSchedule` seam, as they should.
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
        // The paused row exists only under Schedules, and it carries the ratified PAUSED word.
        rule.awaitText("PAUSED")
        rule.onAllNodes(hasText("Winter service") and hasClickAction())[0].performClick()
        rule.onAllNodes(hasText("North run") and hasClickAction())[0].performClick()
        rule.onNodeWithText("Reminders").performClick()

        rule.runOnIdle {
            assert(record.size == 4) { "four navigations, not $record" }
            assert(record[0].startsWith("schedule:")) { "the due row opens a schedule, not ${record[0]}" }
            assert(record[1].startsWith("schedule:")) { "the schedules row opens a schedule, not ${record[1]}" }
            assert(record[0] != record[1]) { "the two rows are different schedules: $record" }
            assert(record[2].startsWith("group:")) { "the group row opens a group, not ${record[2]}" }
            assert(record[3] == "health") { "the Reminders row opens reminder health, not ${record[3]}" }
        }
    }

    /**
     * Blocking finding 1, and master plan §17.1a: a round that **obliges nobody** is drawn with no
     * meter word, no progress line and no repair label.
     *
     * "NO BASELINE" is the repairable missing-meter-baseline form's word and its alone; "0 of 0
     * complete" would read as *done*, and emptiness never means complete; and no reading repairs an
     * empty required set. §17 ratifies no word for this state, so the row says its name and what it
     * is on and nothing else — and it is still listed, which is what lets the owner find it.
     */
    @Test fun aRoundThatObligesNobodyGetsNoMeterWordNoProgressAndNoRepair() {
        val record = mutableListOf<String>()
        val graph = app.graph
        runBlocking {
            // A group with no members at all: the round's required set is empty. The schedule is
            // written straight to the repository because `saveSchedule` refuses a group target with
            // nobody in it (422), which is the correct refusal — this row only exists on a phone
            // whose members were all removed after the fact.
            graph.groups.upsert(
                MaintenanceGroup(
                    id = GroupId("b08-empty-group"),
                    name = "Emptied run",
                    description = "",
                    archivedAt = null,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                    members = emptyList(),
                ),
            )
            val schedule = MaintenanceSchedule(
                id = ScheduleId("b08-vacuous"),
                target = ScheduleTarget.GroupTarget(GroupId("b08-empty-group")),
                title = "Nobody's round",
                description = "",
                timeInterval = 3,
                timeUnit = RecurrenceUnit.MONTH,
                timeBasis = TimeBasis.FIXED,
                anchorOn = "2026-01-01",
                leadDays = 0,
                meterDefinitionId = null,
                meterInterval = null,
                anchorMeter = null,
                meterLead = null,
                seasonBehavior = SeasonBehavior.IGNORE,
                seasonReentry = null,
                seasonReentryOffsetDays = null,
                completionMode = CompletionMode.QUICK,
                profileId = null,
                remindersEnabled = true,
                status = ScheduleStatus.ACTIVE,
                postponedDueOn = null,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
            )
            graph.schedules.upsert(schedule)
            graph.recomputeSchedules.forSchedule(schedule.id)
        }
        rule.setContent {
            ServiceTagTheme {
                MaintenanceScreen(
                    graph = graph,
                    onOpenSchedule = { record += "schedule:$it" },
                    onOpenGroup = { record += "group:$it" },
                    onNewGroup = { record += "new-group" },
                    onReminderHealth = { record += "health" },
                    onScanTag = {},
                    onAddAsset = {},
                    onLogMaintenance = {},
                )
            }
        }

        // It is listed, under Schedules, naming its group.
        rule.awaitText("Nobody's round")
        rule.awaitText("Emptied run")
        // And it says none of the three things it cannot support.
        rule.onAllNodesWithText("NO BASELINE").assertCountEquals(0)
        rule.onAllNodesWithText("0 of 0 complete").assertCountEquals(0)
        rule.onAllNodesWithText("Log meter reading").assertCountEquals(0)
        // Nor is it in Due work: that section is omitted because it would be empty.
        rule.onAllNodesWithText("Due work").assertCountEquals(0)
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
                    onNewGroup = { record += "new-group" },
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

        // The destination's own title: "Maintenance" now appears twice — the tab and the title —
        // which is what the count asserts. This install is fresh, so what the destination shows is
        // its ratified empty state, the groups section and the Reminders row.
        rule.awaitText("Maintenance", count = 2)
        rule.awaitText("No maintenance schedules yet. Add one from an asset or a maintenance group.")
        rule.awaitText("Reminders")

        // C5 (controller ruling): the groups section is drawn even with no groups, because its
        // create affordance is the only in-app way to make the first one — and the ratified empty
        // state above points the owner at exactly that. Tapping it reaches the group form.
        rule.awaitText("Maintenance groups")
        rule.onAllNodesWithText("Maintenance group").assertCountEquals(1)
        rule.onNodeWithText("Maintenance group").performClick()
        rule.awaitText("Name")

        // Scan is still not a tab (D12 §16 correction): the third slot is Maintenance, not Scan.
        rule.onAllNodes(hasText("Scan") and hasClickAction()).assertCountEquals(0)
    }
}
