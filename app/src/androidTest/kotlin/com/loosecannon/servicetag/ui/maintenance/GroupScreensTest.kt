package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The group screens on the real back stack: what only a device shows is that the two `Route` keys
 * really draw a screen now — they were placeholders that popped themselves — and that #55's two
 * navigation directions both work through the shipped navigation and not through a test callback.
 *
 * Three things are proved here and nowhere else: **the group list reaches a detail and a member row
 * reaches that member's own Asset**; **an asset's two sections list its own work and its groups, and
 * open the group**; and **a soft-remove followed by a re-add**, with the current-members list read
 * off the screen at each step and the stored windows read off the database at the end.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class GroupScreensTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun freshInstall() = clearInstall()

    /**
     * Two sprinklers, a group with a round one of them has done, a second group with no schedule at
     * all, and one schedule on the first sprinkler itself.
     *
     * The second group is the one the remove-and-re-add test edits: with no schedule it has no
     * round, so the only place a member's name appears on its screen is the members list — which is
     * exactly what that test has to read at each step.
     *
     * North run holds **three** members with one done, so completing a second leaves the round
     * open: a round whose last outstanding member is completed is finished, and the engine opens
     * the next one — which is correct, and would make "the progress moved" the wrong assertion.
     *
     * The third group has **no** members at all, which is what the create-affordance test needs:
     * `SaveSchedule` refuses a group target with nobody in it, so the affordance must not be there
     * to tap (invariant 74).
     *
     * Everything goes through the production use cases, so the membership windows are stamped before
     * the schedule is created and the round therefore opens on an instant they cover.
     */
    private fun aStoreWithGroups() {
        val graph = app.graph
        runBlocking {
            val one = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
            val two = graph.createAsset.run(AssetCommand(name = "Sprinkler 2", category = "Irrigation"))
            val three = graph.createAsset.run(AssetCommand(name = "Sprinkler 3", category = "Irrigation"))
            val north = graph.saveGroup.run(
                null,
                GroupCommand(
                    name = "North run",
                    members = listOf(
                        GroupMemberInput(assetId = one.id),
                        GroupMemberInput(assetId = two.id),
                        GroupMemberInput(assetId = three.id),
                    ),
                ),
            )
            graph.saveGroup.run(
                null,
                GroupCommand(
                    name = "South run",
                    members = listOf(GroupMemberInput(assetId = one.id), GroupMemberInput(assetId = two.id)),
                ),
            )
            // A group with nobody in it: the half of the create rule that has to be *absent*.
            graph.saveGroup.run(null, GroupCommand(name = "Emptied run"))
            val today = LocalDate.now().toString()
            val round = graph.saveSchedule.run(
                null,
                ScheduleCommand(
                    targetAssetId = null,
                    targetGroupId = north.id,
                    title = "Head check",
                    timeInterval = 3,
                    timeUnit = RecurrenceUnit.MONTH,
                    anchorOn = today,
                ),
            )
            graph.completeGroupMembers.run(
                round.id,
                listOf(one.id),
                CompletionCommand(occurredOn = today, tzId = "UTC"),
            )
            graph.saveSchedule.run(
                null,
                ScheduleCommand(
                    targetAssetId = one.id,
                    targetGroupId = null,
                    title = "Nozzle clean",
                    timeInterval = 3,
                    timeUnit = RecurrenceUnit.MONTH,
                    anchorOn = today,
                ),
            )
        }
    }

    private fun openMaintenance() {
        rule.onNode(hasText("Maintenance") and hasClickAction()).performClick()
        rule.awaitText("Maintenance groups")
    }

    private fun openAssets() {
        rule.onNode(hasText("Assets") and hasClickAction()).performClick()
    }

    /**
     * #55's group → member direction, end to end: the list under "Maintenance groups" opens a
     * detail, the detail carries the RATIFIED noun and the RATIFIED progress form for its round, and
     * a member row's RATIFIED "Open asset" lands on that member's **own** Asset screen.
     */
    @Test fun theListOpensAGroupAndAMemberRowOpensThatAssetsOwnScreen() {
        aStoreWithGroups()
        openMaintenance()

        // `hasTextExactly` and not `hasText`: the group's name is also the **subtitle** of its
        // schedule's due row, which is clickable too and opens the schedule. A list row says the
        // group's name and nothing else, which is what tells the two apart.
        rule.onNode(hasTextExactly("North run") and hasClickAction()).performScrollTo().performClick()

        rule.awaitText("Maintenance group")
        // The round's progress, from the occurrence and not from the membership count.
        rule.awaitText("1 of 3 complete")
        rule.awaitText("Head check")

        rule.onAllNodesWithText("Open asset")[0].performScrollTo().performClick()

        // The member's own Asset screen, which only an Asset has.
        rule.awaitText("SERVICE RECORD")
        rule.awaitText("COMPONENTS")
    }

    /**
     * #55's asset → group direction, and master plan decision 38 on the same screen: the asset's
     * schedules section lists its **own** schedule, the group's schedule is **not** there, and the
     * groups section opens the group.
     */
    @Test fun anAssetsSectionsListItsOwnWorkAndItsGroupsAndOpenTheGroup() {
        aStoreWithGroups()
        openAssets()
        rule.onNode(hasText("Sprinkler 1") and hasClickAction()).performClick()

        rule.awaitText("Schedules")
        rule.awaitText("Nozzle clean")
        rule.awaitText("Maintenance groups")
        // One obligation is not shown twice (D-15, decision 38). The group's name appears **once**
        // here — as the row that opens the group; had the group's schedule been listed in the
        // asset's own section it would be on screen again as that row's subtitle.
        //
        // The schedule's *title* is deliberately not what is counted: the member completion is an
        // ordinary journal event on this Asset and carries the same title, so it appears in the
        // service record further down. That is a record of work done, not a second obligation.
        rule.onAllNodesWithText("North run").assertCountEquals(1)

        rule.onNode(hasTextExactly("North run") and hasClickAction()).performScrollTo().performClick()
        rule.awaitText("Maintenance group")
        rule.awaitText("1 of 3 complete")
    }

    /**
     * Soft-remove, then re-add, with the current-members list read at each step (invariants 33, 79,
     * 80). The group edited here has no schedule, so a name on its screen is a **current member** and
     * nothing else.
     *
     * The last three assertions are the ones a screen cannot make: three stored windows where two
     * members were named, the first one's `removed_at` **stamped and left alone**, and exactly one
     * open window for the pair.
     */
    @Test fun aMemberIsSoftRemovedAndReAddedWithTheListReadAtEachStep() {
        aStoreWithGroups()
        openMaintenance()
        rule.onNode(hasTextExactly("South run") and hasClickAction()).performScrollTo().performClick()

        rule.awaitText("Maintenance group")
        rule.awaitText("Sprinkler 1")
        rule.awaitText("Sprinkler 2")

        // Out.
        rule.onNodeWithContentDescription("Edit").performClick()
        rule.awaitText("Name")
        rule.onNodeWithText("Sprinkler 1").performScrollTo().performClick()
        rule.onNodeWithText("Save").performClick()

        // "Archive" is on the detail and on no other screen: awaiting it is awaiting the pop, and
        // without that the form's own unticked row would still be answering to the name below.
        rule.awaitText("Archive")
        rule.awaitText("Sprinkler 2")
        rule.onAllNodesWithText("Sprinkler 1").assertCountEquals(0)

        // And back in.
        rule.onNodeWithContentDescription("Edit").performClick()
        rule.awaitText("Name")
        rule.onNodeWithText("Sprinkler 1").performScrollTo().performClick()
        rule.onNodeWithText("Save").performClick()

        rule.awaitText("Archive")
        rule.awaitText("Sprinkler 1")
        rule.awaitText("Sprinkler 2")

        val south = runBlocking { app.graph.groups.all().single { it.name == "South run" } }
        val windows = south.members.filter { it.assetId.value == assetIdOf("Sprinkler 1") }
        assert(windows.size == 2) { "a re-add is a second window, not a reopened one: $windows" }
        assert(windows.count { it.removedAt == null } == 1) { "exactly one open window: $windows" }
        val closed = windows.single { it.removedAt != null }
        val opened = windows.single { it.removedAt == null }
        assert(closed.addedAt < opened.addedAt) { "the second window is newer: $windows" }
        assert(closed.id != opened.id) { "a new durable id: $windows" }
        assert(south.members.size == 3) { "nothing was deleted: ${south.members}" }
    }

    /**
     * Plan decision 39: an archived group stays **in** the list, visibly distinguished, because #55
     * requires it to keep its maintenance history and history nobody can open is not kept. What
     * archiving takes away is its due work, which leaves the Due work section.
     */
    @Test fun anArchivedGroupStaysInTheListAndIsMarked() {
        aStoreWithGroups()
        openMaintenance()
        rule.awaitText("Head check")

        rule.onNode(hasTextExactly("North run") and hasClickAction()).performScrollTo().performClick()
        rule.awaitText("Maintenance group")
        rule.onNodeWithText("Archive").performClick()
        rule.awaitText("Unarchive")

        rule.onNodeWithContentDescription("Back").performClick()

        // Still listed, and marked; its round has left the due lists entirely.
        rule.awaitText("North run")
        rule.awaitText("ARCHIVED")
        rule.onAllNodesWithText("Head check").assertCountEquals(0)

        val stored = runBlocking { app.graph.groups.all().single { it.name == "North run" } }
        assert(stored.archivedAt != null) { "the column was written" }
        assert(stored.members.size == 3) { "archive cascades nothing: ${stored.members}" }
        val events = runBlocking { app.graph.events.all() }
        assert(events.size == 1) { "the member completion is retained: $events" }
    }

    /**
     * The round's checklist and its two RATIFIED actions, on the real back stack: ticking the one
     * outstanding member and tapping **"Complete selected"** opens the canonical affordance —
     * **"When was this done?"** — and the confirm writes **one** event, on that member, through
     * `CompletionFlow` and therefore `CompleteGroupMembers` (invariants 28, 29).
     *
     * The seeded round already has one of its three members done, so "1 of 3 complete" before and
     * "2 of 3 complete" after is the whole arithmetic — the round is still open, which is what
     * makes the progress line the right thing to read — and the event count moving by exactly one
     * is the proof that nothing else was written. *Which* member it was is asserted exactly on the
     * JVM; here it is asserted to be one of the two the round still owed.
     */
    @Test fun theRoundsChecklistCompletesTheSelectedMemberThroughTheRatifiedAction() {
        aStoreWithGroups()
        openMaintenance()
        rule.onNode(hasTextExactly("North run") and hasClickAction()).performScrollTo().performClick()

        rule.awaitText("1 of 3 complete")
        rule.onNodeWithText("Complete all").assertIsDisplayed()
        rule.onNodeWithText("Complete selected").assertIsDisplayed()

        val before = runBlocking { app.graph.events.all().size }

        // An outstanding member's own checkbox: the completed one's is checked and disabled, so
        // the enabled toggles are the two the round still owes.
        rule.onAllNodes(isToggleable() and isEnabled())[0].performScrollTo().performClick()
        rule.onNodeWithText("Complete selected").performScrollTo().performClick()

        // The one completion affordance, shared with the schedule, the sheet and the quick action.
        rule.awaitText("When was this done?")
        rule.onNodeWithText("Save").performClick()

        rule.awaitText("2 of 3 complete")
        val after = runBlocking { app.graph.events.all() }
        assert(after.size == before + 1) { "one member, one event: ${after.size} vs $before" }
        val written = after.maxBy { it.createdAt }
        val owed = setOf(assetIdOf("Sprinkler 2"), assetIdOf("Sprinkler 3"))
        assert(written.assetId.value in owed) { "a member the round owed, and only one: $written" }
        assert(written.scheduleId != null) { "the completion carries its schedule: $written" }
        assert(written.occurrenceOn != null) { "and its occurrence key: $written" }
    }

    /**
     * The in-app way to put a schedule on a group, and the one case where it must **not** be
     * offered (invariant 74).
     *
     * Both halves in one test, because they are one rule: a group with an open member carries the
     * create glyph — the same glyph and the same ratified `contentDescription` the asset screen
     * uses — and tapping it lands on B14's editor with the **group** as the target, which the
     * read-only target picker names. A group with nobody in it carries no glyph at all, so the
     * refusal `SaveSchedule` would give is unreachable through the UI and needs no sentence.
     *
     * Without this the release has no in-app path to a group-targeted schedule: the only other
     * `Route.ScheduleEdit` pushes are the asset screen's and "edit this one".
     */
    @Test fun theSchedulesSectionOffersCreationOnlyWhileTheGroupHasAMember() {
        aStoreWithGroups()
        openMaintenance()
        rule.onNode(hasTextExactly("North run") and hasClickAction()).performScrollTo().performClick()
        rule.awaitText("Maintenance group")

        // The glyph is labelled with the ratified section word; the heading beside it is a Text and
        // carries no contentDescription, so this counts the action and nothing else.
        rule.onAllNodesWithContentDescription("Schedules").assertCountEquals(1)
        rule.onNodeWithContentDescription("Schedules").performScrollTo().performClick()

        // B14's editor, opened on a group target: the read-only picker says which kind it is.
        rule.awaitText("This applies to")
        rule.awaitText("A maintenance group")

        // And the group with nobody in it offers nothing to tap.
        rule.onNodeWithContentDescription("Cancel").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNode(hasTextExactly("Emptied run") and hasClickAction()).performScrollTo().performClick()
        rule.awaitText("Maintenance group")
        // The ratified empty state stands in for the schedules it has none of.
        rule.awaitText("No maintenance schedules yet. Add one from an asset or a maintenance group.")
        rule.onAllNodesWithContentDescription("Schedules").assertCountEquals(0)
    }

    private fun assetIdOf(name: String): String =
        runBlocking { app.graph.assets.all().single { it.name == name }.id.value }
}
