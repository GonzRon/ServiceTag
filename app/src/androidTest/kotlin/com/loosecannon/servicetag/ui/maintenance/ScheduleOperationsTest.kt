package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The five operations on a real Compose tree, and the "Close this round" gate.
 *
 * What only a real tree shows: which actions are **on screen** for which kind of schedule, that the
 * close confirmation says the sentence that keeps it from reading as "mark everything done", and
 * that confirming it writes one closure row and no member event. The arithmetic behind the dates is
 * the engine's and is proved in `:core`; the "does not" halves are proved again here because a
 * screen can offer an action the engine would refuse and that is exactly the dead end to catch.
 *
 * The seeded rows carry a genuinely older `created_at`: the D-27 pin means a schedule made through
 * `saveSchedule` is never overdue on a fresh store, so an overdue row has to be seeded.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleOperationsTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private fun dayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun seeded(
        id: String,
        target: ScheduleTarget,
        title: String,
        anchorOn: String,
        createdOn: LocalDate,
    ) = MaintenanceSchedule(
        id = ScheduleId(id),
        target = target,
        title = title,
        description = "",
        timeInterval = 3,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.FIXED,
        anchorOn = anchorOn,
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
        createdAt = dayMillis(createdOn),
        updatedAt = dayMillis(createdOn),
        providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
    )

    private fun detailFor(graph: AppGraph, scheduleId: String): MutableList<String> {
        val record = mutableListOf<String>()
        rule.setContent {
            ServiceTagTheme {
                ScheduleDetailScreen(
                    graph = graph,
                    scheduleId = scheduleId,
                    onBack = { record += "back" },
                    onEditRecurrence = { record += "edit:$it" },
                    onLogForm = { assetId, profileId -> record += "form:$assetId:$profileId" },
                )
            }
        }
        return record
    }

    /**
     * An **asset**-targeted schedule: the three actions it gets, the one it does not, and the
     * canonical affordance behind the completion.
     *
     * "Close this round" is **absent** — in 1.2 the action is offered on group targets only, where a
     * round can be partially done and stuck; an asset round is one member and completing it is the
     * answer (spec §1.2). The recurrence edit leaves through its own seam, which is the only path to
     * a rule column.
     */
    @Test fun anAssetScheduleOffersCompleteSnoozeAndPostponeButNeverClose() {
        val graph = app.graph
        val today = LocalDate.now()
        runBlocking {
            val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
            graph.schedules.upsert(
                seeded(
                    id = "b14-asset",
                    target = ScheduleTarget.AssetTarget(mower.id),
                    title = "Blade sharpen",
                    anchorOn = today.minusMonths(6).toString(),
                    createdOn = today.minusMonths(6),
                ),
            )
            graph.recomputeSchedules.forSchedule(ScheduleId("b14-asset"))
        }
        val record = detailFor(graph, "b14-asset")

        rule.awaitText("Blade sharpen")
        rule.onNodeWithText("Log maintenance").assertIsDisplayed()
        rule.onNodeWithText("Snooze").assertIsDisplayed()
        rule.onNodeWithText("Postpone").assertIsDisplayed()
        // Never on an asset target.
        rule.onAllNodesWithText("Close this round").assertCountEquals(0)
        // Nor the group-round labels.
        rule.onAllNodesWithText("Complete all").assertCountEquals(0)
        rule.onAllNodesWithText("Complete selected").assertCountEquals(0)

        // The recurrence edit is the editor, reached through its seam and nowhere else.
        rule.onNodeWithText("Edit").performClick()
        rule.runOnIdle { assert(record == listOf("edit:b14-asset")) { "unexpected: $record" } }

        // Snooze: no date moves and no event appears.
        val rowBefore = runBlocking { graph.schedules.get(ScheduleId("b14-asset"))!! }
        rule.onNodeWithText("Snooze").performClick()
        rule.waitForIdle()
        val rowAfter = runBlocking { graph.schedules.get(ScheduleId("b14-asset"))!! }
        assert(rowBefore == rowAfter) { "the snooze wrote a column: $rowBefore -> $rowAfter" }
        assert(runBlocking { graph.events.all() }.isEmpty()) { "the snooze wrote an event" }

        // The canonical affordance, in the ratified words, and it is what writes.
        rule.onNodeWithText("Log maintenance").performClick()
        rule.awaitText("When was this done?")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5_000) { runBlocking { graph.events.all() }.size == 1 }
        val event = runBlocking { graph.events.all().single() }
        assert(event.scheduleId == ScheduleId("b14-asset")) { "the completion names its schedule" }
        assert(event.occurredOn == today.toString()) { "today by default: ${event.occurredOn}" }
    }

    /**
     * A **group** round: the two ratified completion labels, the close gate, the confirmation
     * sentence and what confirming writes.
     *
     * The close writes **one** `occurrence_closure` row, **no** schedule column and **no**
     * `asset_event` on any member — every member's history byte-identical (invariants 35, 36) — and
     * the sentence on screen is what stops the action reading as "mark everything done".
     */
    @Test fun aGroupRoundOffersTheCloseAndConfirmingItRecordsNobodyAsServiced() {
        val graph = app.graph
        val today = LocalDate.now()
        val members = runBlocking {
            val one = graph.createAsset.run(AssetCommand(name = "Sprinkler 1", category = "Irrigation"))
            val two = graph.createAsset.run(AssetCommand(name = "Sprinkler 2", category = "Irrigation"))
            // The group row is written directly, with membership windows that opened **before** the
            // schedule did. `saveGroup` stamps `added_at` from the clock, and a window that opens
            // after the round did covers nothing: the round would oblige nobody, which is the state
            // the third test is about and not this one (spec §2.4, D-10).
            val opened = dayMillis(today.minusMonths(7))
            graph.groups.upsert(
                com.loosecannon.servicetag.core.model.MaintenanceGroup(
                    id = GroupId("b14-north"),
                    name = "North run",
                    description = "",
                    archivedAt = null,
                    createdAt = opened,
                    updatedAt = opened,
                    members = listOf(
                        com.loosecannon.servicetag.core.model.GroupMember(
                            id = "b14-north-m1", assetId = one.id, sortOrder = 0,
                            addedAt = opened, removedAt = null,
                        ),
                        com.loosecannon.servicetag.core.model.GroupMember(
                            id = "b14-north-m2", assetId = two.id, sortOrder = 1,
                            addedAt = opened, removedAt = null,
                        ),
                    ),
                ),
            )
            graph.schedules.upsert(
                seeded(
                    id = "b14-group",
                    target = ScheduleTarget.GroupTarget(GroupId("b14-north")),
                    title = "Head check",
                    anchorOn = today.minusMonths(6).toString(),
                    createdOn = today.minusMonths(6),
                ),
            )
            graph.recomputeSchedules.forSchedule(ScheduleId("b14-group"))
            // One member done: the round is open, obliges two and is unfinished — the only state
            // the close is offered in.
            graph.completeGroupMembers.run(
                ScheduleId("b14-group"),
                listOf(one.id),
                CompletionCommand(occurredOn = today.minusDays(3).toString(), tzId = "UTC"),
            )
            listOf(one.id, two.id)
        }
        val historyBefore = runBlocking {
            graph.events.all().map { it.id.value to it.assetId.value }.toSet()
        }
        val scheduleBefore = runBlocking { graph.schedules.get(ScheduleId("b14-group"))!! }

        detailFor(graph, "b14-group")

        rule.awaitText("Head check")
        // The RATIFIED progress form, and the two RATIFIED group labels.
        rule.awaitText("1 of 2 complete")
        rule.onNodeWithText("Complete all").assertIsDisplayed()
        rule.onNodeWithText("Complete selected").assertIsDisplayed()
        // The asset target's single-completion label is not what a group round offers.
        rule.onAllNodesWithText("Log maintenance").assertCountEquals(members.size - 1)

        // The gate is open, and the confirmation says what the action does **not** do.
        rule.onNodeWithText("Close this round").performClick()
        rule.awaitText("Close this round? The members not marked done will not be recorded as serviced.")
        // The date defaults to today, and it is the picked value — the field is read-only, so a date
        // outside the round's range is unreachable rather than merely refused.
        rule.awaitText(today.toString())
        rule.onAllNodesWithText("Close this round")[1].performClick()

        rule.waitUntil(5_000) { runBlocking { graph.closures.all() }.size == 1 }
        val closure = runBlocking { graph.closures.all().single() }
        assert(closure.closedOn == today.toString()) { "today by default: ${closure.closedOn}" }
        assert(runBlocking { graph.schedules.get(ScheduleId("b14-group"))!! } == scheduleBefore) {
            "the close wrote a schedule column"
        }
        val historyAfter = runBlocking {
            graph.events.all().map { it.id.value to it.assetId.value }.toSet()
        }
        assert(historyAfter == historyBefore) { "a member was recorded as serviced: $historyAfter" }
    }

    /**
     * The other half of the gate on a real tree: a round that obliges **nobody** offers nothing at
     * all — no close, no completion, and no status word (invariants 74, 77; §17.1a).
     *
     * Written straight to the repository, because `saveSchedule` refuses a group target with nobody
     * in it: this row only exists on a phone whose members were all removed after the fact.
     */
    @Test fun aRoundThatObligesNobodyOffersNoCloseAndNoCompletion() {
        val graph = app.graph
        val today = LocalDate.now()
        runBlocking {
            graph.groups.upsert(
                com.loosecannon.servicetag.core.model.MaintenanceGroup(
                    id = GroupId("b14-empty"),
                    name = "Emptied run",
                    description = "",
                    archivedAt = null,
                    createdAt = dayMillis(today.minusMonths(6)),
                    updatedAt = dayMillis(today.minusMonths(6)),
                    members = emptyList(),
                ),
            )
            graph.schedules.upsert(
                seeded(
                    id = "b14-vacuous",
                    target = ScheduleTarget.GroupTarget(GroupId("b14-empty")),
                    title = "Nobody's round",
                    anchorOn = today.minusMonths(6).toString(),
                    createdOn = today.minusMonths(6),
                ),
            )
            graph.recomputeSchedules.forSchedule(ScheduleId("b14-vacuous"))
        }
        detailFor(graph, "b14-vacuous")

        rule.awaitText("Nobody's round")
        rule.onAllNodesWithText("Close this round").assertCountEquals(0)
        rule.onAllNodesWithText("Complete all").assertCountEquals(0)
        rule.onAllNodesWithText("Complete selected").assertCountEquals(0)
        rule.onAllNodesWithText("Log maintenance").assertCountEquals(0)
        // §17 ratifies no word for this state, so it gets none — and never the meter-baseline one.
        rule.onAllNodesWithText("NO BASELINE").assertCountEquals(0)
        rule.onAllNodesWithText("0 of 0 complete").assertCountEquals(0)
    }
}
