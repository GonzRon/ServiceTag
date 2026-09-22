package com.loosecannon.servicetag.ui.dashboard

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.reminders.Severity
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.maintenance.HealthSummary
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dashboard's attention sections over a seeded store, on a device.
 *
 * The rules are all proved on the JVM; what only a real Compose tree shows is that the sections
 * draw in D12 §10's order with the empty ones absent, that a promoted component's row really is on
 * screen naming its parent, that a group row is **one** row carrying the ratified progress form,
 * that F3's meter line reads off the projection's own numbers, and that the health badge appears at
 * WARN and not below it.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class DashboardAttentionTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    /**
     * A hot tub whose **component** is overdue, a mower whose meter is past its threshold, and a
     * group of three sprinklers with one member done.
     *
     * The overdue and meter schedules are written straight to the repository and then rebuilt by
     * the real engine, because the D-27 pin floors a new schedule's first occurrence at its own
     * `created_at` (invariant 23) — a schedule created now can be due today but never yesterday, so
     * an OVERDUE row on a fresh store needs a row whose `created_at` is genuinely older. The group
     * schedule goes through `saveSchedule`, because its membership windows are stamped now and its
     * round has to open on an instant those windows cover.
     */
    private fun aStoreWithAttentionWork(): AppGraph {
        val graph = app.graph
        runBlocking {
            val tub = graph.createAsset.run(AssetCommand(name = "Hot tub", category = "Water"))
            val pump = graph.createAsset.run(
                AssetCommand(name = "Circulation pump", category = "Water", parentAssetId = tub.id),
            )
            val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
            val heads = (1..3).map {
                graph.createAsset.run(AssetCommand(name = "Sprinkler $it", category = "Irrigation"))
            }

            // The component's overdue work: the row the blank-query rule would otherwise hide.
            seed(
                graph,
                scheduleOf(
                    id = "b08-overdue",
                    assetId = pump.id.value,
                    title = "Impeller check",
                    timeInterval = 3,
                    timeUnit = RecurrenceUnit.MONTH,
                    anchorOn = "2026-01-01",
                    createdOn = "2026-01-01",
                ),
            )

            // A meter rule with a baseline and a reading past it: F3's line has real numbers.
            val hours = graph.saveDefinition.run(null, meterDefinition(mower.id))
            graph.logEvent.run(reading(mower.id, hours.id, value = 520.0))
            seed(
                graph,
                scheduleOf(
                    id = "b08-meter",
                    assetId = mower.id.value,
                    title = "Oil change",
                    timeInterval = null,
                    timeUnit = null,
                    anchorOn = null,
                    meterDefinitionId = hours.id.value,
                    meterInterval = 100.0,
                    anchorMeter = 400.0,
                    createdOn = "2026-01-01",
                ),
            )

            // A group round, due today, with one of its three members done.
            val group = graph.saveGroup.run(
                null,
                GroupCommand(
                    name = "North run",
                    members = heads.mapIndexed { index, asset ->
                        GroupMemberInput(assetId = asset.id, sortOrder = index)
                    },
                ),
            )
            val groupSchedule = graph.saveSchedule.run(
                null,
                ScheduleCommand(
                    targetAssetId = null,
                    targetGroupId = group.id,
                    title = "Head check",
                    timeInterval = 1,
                    timeUnit = RecurrenceUnit.YEAR,
                    anchorOn = today(),
                ),
            )
            graph.completeGroupMembers.run(
                groupSchedule.id,
                listOf(heads.first().id),
                CompletionCommand(occurredOn = today(), tzId = "UTC"),
            )
        }
        return graph
    }

    /**
     * The summary is passed to the screen rather than assigned on the graph: a view model captures
     * it when it is built, so a field mutated afterwards would be silently ignored.
     */
    private fun draw(graph: AppGraph, severity: Severity? = null) {
        rule.setContent {
            ServiceTagTheme {
                DashboardScreen(
                    graph = graph,
                    onOpenAsset = {},
                    onNewAsset = {},
                    onBackup = {},
                    onSettings = {},
                    onScan = {},
                    health = summary(severity),
                )
            }
        }
    }

    /**
     * ATTENTION is drawn first and the promoted component's row is in it naming its parent — with
     * the query blank, which is the state the shipped filter would have hidden it in (invariant 75,
     * #5 AC 1). A section with no rows is absent rather than an empty heading.
     */
    @Test fun aPromotedComponentsOverdueWorkIsOnScreenWithTheQueryBlank() {
        draw(aStoreWithAttentionWork())

        rule.awaitText("ATTENTION")
        rule.awaitText("Impeller check")
        rule.awaitText("Circulation pump · Part of Hot tub")
        rule.onNodeWithText("OVERDUE").assertIsDisplayed()

        // Nothing is DUE_SOON or out of season here, so neither section is drawn at all
        // (D12 §10 `:706-707`).
        rule.onAllNodesWithText("UPCOMING").assertCountEquals(0)
        rule.onAllNodesWithText("OUT OF SEASON").assertCountEquals(0)
    }

    /**
     * The group row: **one** row whatever the member workload, carrying the ratified "1 of 3
     * complete" and naming the group. One row per member would inflate the total and make the list
     * unreadable (D-15).
     */
    @Test fun aGroupScheduleIsOneRowWithTheRatifiedProgressForm() {
        draw(aStoreWithAttentionWork())

        rule.awaitText("Head check")
        rule.onAllNodesWithText("Head check").assertCountEquals(1)
        rule.awaitText("1 of 3 complete")
        rule.awaitText("North run")
    }

    /**
     * F3's line, from the projection's own two fields, in the ratified form. The rows without a
     * meter rule render no such line, which is what the count asserts.
     */
    @Test fun aMeterRowStatesItsThresholdAndItsReading() {
        draw(aStoreWithAttentionWork())

        rule.awaitText("Oil change")
        rule.awaitText("Due at 500 h, now 520.")
        rule.onAllNodesWithText("Due at 500 h, now 520.").assertCountEquals(1)
    }

    /**
     * Grayscale acceptance (#5 AC 2, D12 §5, the D12 acceptance): every state on screen carries a
     * **word** of its own as well as a colour, and the words differ. With the palette removed the
     * wording plus the glyph plus the row's position is what tells the states apart, so nothing here
     * is distinguished by colour alone. One row reads OVERDUE (the component's overdue check) and two
 * read DUE (the group round, which is due today, and the meter past its threshold — a meter has no
 * overdue degree). `awaitText` is exact-match, so the DUE count does not pick up "OVERDUE".
     */
    @Test fun everyStateOnScreenCarriesItsOwnWord() {
        draw(aStoreWithAttentionWork())

        rule.awaitText("OVERDUE")
        rule.awaitText("DUE", count = 2)
        // The component's row and the group's round are overdue; the meter past its threshold is
        // DUE, because a meter has no overdue degree.
        rule.onAllNodesWithText("OVERDUE").assertCountEquals(1)
    }

    /**
     * Blocking finding 2: the **drawn** order is D12 §10's fixed one, whichever sections are empty.
     *
     * The reachable case is one asset carrying an out-of-season schedule and one asset with nothing
     * scheduled: there is no `OK` row, so CURRENT's only content is the asset list, and the old
     * screen appended that block after the `sections` loop — drawing OUT OF SEASON, its row, and
     * then CURRENT. The assertion is on positions in the tree, not on the view model, because the
     * view model was right and the render was not.
     */
    @Test fun theDrawnSectionOrderIsFixedEvenWhenCurrentHoldsOnlyAssetRows() {
        val graph = app.graph
        runBlocking {
            val blower = graph.createAsset.run(
                AssetCommand(
                    name = "Snowblower",
                    category = "Yard",
                    seasonStartMmdd = "11-01",
                    seasonEndMmdd = "02-28",
                ),
            )
            seed(
                graph,
                scheduleOf(
                    id = "b08-season",
                    assetId = blower.id.value,
                    title = "Pre-season check",
                    timeInterval = 3,
                    timeUnit = RecurrenceUnit.MONTH,
                    anchorOn = "2026-01-01",
                    createdOn = "2026-01-01",
                    seasonBehavior = SeasonBehavior.FOLLOW_ASSET,
                ),
            )
            graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
        }
        draw(graph)

        rule.awaitText("CURRENT")
        rule.awaitText("Mower")
        // "OUT OF SEASON" is both the section label and the row's own status word, so every node
        // carrying it is compared: the CURRENT heading must be above all of them.
        val currentTop = rule.onNodeWithText("CURRENT").fetchSemanticsNode().positionInRoot.y
        val outOfSeasonTop = rule.onAllNodesWithText("OUT OF SEASON")
            .fetchSemanticsNodes()
            .minOf { it.positionInRoot.y }
        assert(currentTop < outOfSeasonTop) {
            "CURRENT must be drawn above OUT OF SEASON: $currentTop vs $outOfSeasonTop"
        }
        // And the season row is still in its own section, with its ratified word.
        rule.awaitText("Pre-season check")
    }

    /** An `INFO`-only set leaves the badge off: a badge that never clears says nothing (#27). */
    @Test fun anInfoOnlyFindingLeavesTheBadgeOff() {
        draw(aStoreWithAttentionWork(), severity = Severity.INFO)

        rule.awaitText("ATTENTION")
        rule.onAllNodesWithText("REMINDER FAILED").assertCountEquals(0)
    }

    /** At `WARN` it appears (#27, D3 §7.3). */
    @Test fun aWarnFindingShowsTheBadge() {
        draw(aStoreWithAttentionWork(), severity = Severity.WARN)

        rule.awaitText("REMINDER FAILED")
    }

    private companion object {
        fun today(): String = LocalDate.now().toString()

        fun summary(severity: Severity?): HealthSummary = object : HealthSummary {
            override suspend fun worstSeverity(): Severity? = severity
        }

        suspend fun seed(graph: AppGraph, schedule: MaintenanceSchedule) {
            graph.schedules.upsert(schedule)
            graph.recomputeSchedules.forSchedule(schedule.id)
        }

        fun dayMillis(date: String): Long =
            LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        @Suppress("LongParameterList")
        fun scheduleOf(
            id: String,
            assetId: String,
            title: String,
            timeInterval: Int?,
            timeUnit: RecurrenceUnit?,
            anchorOn: String?,
            createdOn: String,
            meterDefinitionId: String? = null,
            meterInterval: Double? = null,
            anchorMeter: Double? = null,
            seasonBehavior: SeasonBehavior = SeasonBehavior.IGNORE,
        ): MaintenanceSchedule = MaintenanceSchedule(
            id = ScheduleId(id),
            target = ScheduleTarget.AssetTarget(AssetId(assetId)),
            title = title,
            description = "",
            timeInterval = timeInterval,
            timeUnit = timeUnit,
            timeBasis = TimeBasis.FIXED,
            anchorOn = anchorOn,
            leadDays = 0,
            meterDefinitionId = meterDefinitionId?.let(::DefinitionId),
            meterInterval = meterInterval,
            anchorMeter = anchorMeter,
            meterLead = null,
            seasonBehavior = seasonBehavior,
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

        fun meterDefinition(assetId: AssetId) = DefinitionCommand(
            assetId = assetId,
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
        )

        fun reading(assetId: AssetId, definitionId: DefinitionId, value: Double) = EventCommand(
            assetId = assetId,
            profileId = null,
            kind = EventKind.MEASUREMENT,
            title = "Log hours",
            occurredOn = today(),
            occurredTime = null,
            tzId = "UTC",
            notes = "",
            values = mapOf(definitionId to value.toInt().toString()),
            consumables = emptyList(),
        )
    }
}
