package com.loosecannon.servicetag.ui.dashboard

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.reminders.ReminderHealthSeverity
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.GroupCommand
import com.loosecannon.servicetag.core.usecase.GroupMemberInput
import com.loosecannon.servicetag.core.usecase.HealthSubjectCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.maintenance.HealthSummary
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
     * the real engine, because of D-27's floor: created now, due today at the earliest, never
     * yesterday — so an OVERDUE row on a fresh store needs a row whose `created_at` is genuinely
     * older. The group schedule goes through `saveSchedule`, because its membership windows are
     * stamped now and its round has to open on an instant those windows cover.
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
            // The round has to be **due today** for this store to mean what its name says, and the
            // anchor alone does not settle that: the D-27 pin floors the first occurrence at the
            // row's own `rule_changed_at`. `ScheduleRecompute` now reads that floor in the owner's zone
            // rather than at UTC, which is what makes the anchor above mean today at every hour —
            // but a fixture that leans on a floor rule it never states is how this store came to
            // read OK instead of DUE for the last four hours of every day. Stamping the floor a
            // day back says it outright.
            //
            // Only the floor moves: `created_at` has to stay *now*, because the round's open
            // instant is its `created_at` and the membership windows above were stamped now — an
            // older one would empty the required set and take the progress line with it. And it is
            // stamped before the completion, so the completion lands on today's round.
            graph.schedules.upsert(
                graph.schedules.get(groupSchedule.id)!!
                    .copy(ruleChangedAt = dayMillis(LocalDate.now().minusDays(1).toString())),
            )
            graph.recomputeSchedules.forSchedule(groupSchedule.id)
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
    private fun draw(graph: AppGraph, severity: ReminderHealthSeverity? = null) {
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

    /** B07 — the search box left the Dashboard for the Assets screen; the field is gone from here. */
    @Test fun theSearchFieldIsGoneFromTheDashboard() {
        draw(aStoreWithAttentionWork())

        rule.awaitText("ATTENTION")
        rule.onNodeWithContentDescription("Search assets and components").assertDoesNotExist()

        // Q1-residual (B07 fix round 3): the two guards the search box's removal simplified,
        // asserted at render level now that `DashboardSearchTest` is gone. The fixture's pump is a
        // component, so the hint shows in its shortened form (fix round 2, S1 — pending owner
        // ratification); the Dashboard carries content, so the no-match sentence never does.
        rule.awaitText("Components are listed on the asset they belong to.")
        rule.onAllNodesWithText("Nothing matches that.").assertCountEquals(0)
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
     * is distinguished by colour alone. One row reads OVERDUE (the component's overdue check) and
     * two read DUE (the group round, which is due today, and the meter past its threshold — a meter
     * has no overdue degree). `awaitText` is exact-match, so the DUE count does not pick up
     * "OVERDUE".
     */
    @Test fun everyStateOnScreenCarriesItsOwnWord() {
        draw(aStoreWithAttentionWork())

        rule.awaitText("OVERDUE")
        rule.awaitText("DUE", count = 2)
        // Exactly one row is OVERDUE — the component's overdue check. The group's round and the
        // meter past its threshold both read DUE.
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
                    servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
                    policyOffsetDays = 0,
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
        check(currentTop < outOfSeasonTop) {
            "CURRENT must be drawn above OUT OF SEASON: $currentTop vs $outOfSeasonTop"
        }
        // And the season row is still in its own section, with its ratified word.
        rule.awaitText("Pre-season check")
    }

    /** An `INFO`-only set leaves the badge off: a badge that never clears says nothing (#27). */
    @Test fun anInfoOnlyFindingLeavesTheBadgeOff() {
        draw(aStoreWithAttentionWork(), severity = ReminderHealthSeverity.INFO)

        rule.awaitText("ATTENTION")
        rule.onAllNodesWithText("REMINDER FAILED").assertCountEquals(0)
    }

    /** At `WARN` it appears (#27, D3 §7.3). */
    @Test fun aWarnFindingShowsTheBadge() {
        draw(aStoreWithAttentionWork(), severity = ReminderHealthSeverity.WARN)

        rule.awaitText("REMINDER FAILED")
    }


    // ------------------------------------------------------------ 1.4: condition, health, Deferred

    /** The top of the first node carrying exactly [text]. */
    private fun top(text: String): Float = rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /**
     * Spec §10.2 on a real tree: ATTENTION draws the DOWN unit, then the schedule row, then the
     * DEGRADED component naming its parent, then the independent CRITICAL subject (S110); UPCOMING
     * then draws the independent WARNING subject. The condition rows carry S22 and the reason or S23.
     */
    @Test fun sectionOrderWithConditionAndHealthRows() {
        val graph = app.graph
        val today = LocalDate.now()
        runBlocking {
            val generator = graph.createAsset.run(AssetCommand(name = "Generator", category = "Power")).id
            graph.recordCondition.run(
                generator,
                ConditionCommand(OperationalCondition.DOWN, occurredOn = today.minusDays(3).toString(), tzId = zone(), reason = "Won't start"),
            )
            val pack = graph.createAsset.run(AssetCommand(name = "Battery pack", category = "Power", parentAssetId = generator)).id
            graph.recordCondition.run(
                pack,
                ConditionCommand(OperationalCondition.DEGRADED, occurredOn = today.minusDays(2).toString(), tzId = zone()),
            )
            val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
            seed(
                graph,
                scheduleOf(
                    id = "b13-overdue", assetId = mower.id.value, title = "Blade sharpen",
                    timeInterval = 3, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01", createdOn = "2026-01-01",
                ),
            )
            ageSubject(graph, "Hot tub", "Filter age", daysAgo = 90)
            ageSubject(graph, "Snowblower", "Belt age", daysAgo = 50)
        }
        draw(graph)

        rule.awaitText("Belt age WARNING")
        rule.awaitText("Won't start")
        rule.awaitText("since ${displayDate(today.minusDays(3))}")
        rule.awaitText("No reason given")
        rule.awaitText("Part of Generator")

        val order = listOf("ATTENTION", "Generator", "Blade sharpen", "Battery pack", "Filter age CRITICAL", "UPCOMING", "Belt age WARNING")
        val tops = order.map(::top)
        check(tops == tops.sorted()) { "drawn out of order: ${order.zip(tops)}" }
    }

    /**
     * Spec §4.5 on a real tree: a row the maintenance break holds is under its own **Deferred**
     * heading (S93, in its ratified case), between CURRENT and OUT OF SEASON, with its status word
     * (S92) and its why-line (S85).
     */
    @Test fun theDeferredSection() {
        val graph = app.graph
        val today = LocalDate.now()
        runBlocking {
            val generator = graph.createAsset.run(AssetCommand(name = "Generator", category = "Power")).id
            graph.assets.upsert(
                graph.assets.get(generator)!!.copy(
                    blackoutStartMmdd = today.minusDays(10).format(MMDD),
                    blackoutEndMmdd = today.plusDays(20).format(MMDD),
                ),
            )
            seed(
                graph,
                scheduleOf(
                    id = "b13-held", assetId = generator.value, title = "Engine oil service",
                    timeInterval = 3, timeUnit = RecurrenceUnit.MONTH, anchorOn = today.minusDays(5).toString(),
                    createdOn = today.minusDays(30).toString(),
                    servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
                ),
            )
            val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
            seed(
                graph,
                scheduleOf(
                    id = "b13-ok", assetId = mower.id.value, title = "Blade sharpen",
                    timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = today.plusDays(100).toString(),
                    createdOn = "2026-01-01",
                ),
            )
            val blower = graph.createAsset.run(
                AssetCommand(
                    name = "Snowblower", category = "Yard",
                    seasonStartMmdd = today.plusDays(30).format(MMDD), seasonEndMmdd = today.plusDays(60).format(MMDD),
                ),
            )
            seed(
                graph,
                scheduleOf(
                    id = "b13-parked", assetId = blower.id.value, title = "Pre-season check",
                    timeInterval = 3, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01", createdOn = "2026-01-01",
                    servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
                ),
            )
        }
        draw(graph)

        rule.awaitText("Deferred")
        rule.awaitText("DEFERRED")
        rule.awaitText("Held until ${displayDate(today.plusDays(21))} because of the maintenance break")
        rule.awaitText("CURRENT")

        val outOfSeasonTop = rule.onAllNodesWithText("OUT OF SEASON").fetchSemanticsNodes().minOf { it.positionInRoot.y }
        check(top("CURRENT") < top("Deferred")) { "Deferred is below CURRENT" }
        check(top("Deferred") < top("Engine oil service")) { "the held row is under the Deferred heading" }
        check(top("Engine oil service") < outOfSeasonTop) { "Deferred is above OUT OF SEASON" }
        // Held is never due: nothing is in ATTENTION.
        rule.onAllNodesWithText("ATTENTION").assertCountEquals(0)
    }

    /**
     * The four condition chips (S8, S10, S12, S26), multi-select with no label and no "All" chip.
     * A chip selects rows by their asset's condition; none selected is no condition filter.
     */
    @Test fun conditionChips() {
        val graph = app.graph
        runBlocking {
            val generator = graph.createAsset.run(AssetCommand(name = "Generator", category = "Power")).id
            graph.recordCondition.run(generator, ConditionCommand(OperationalCondition.DOWN, tzId = zone(), reason = "Won't start"))
            val mower = graph.createAsset.run(AssetCommand(name = "Mower", category = "Yard"))
            seed(
                graph,
                scheduleOf(
                    id = "b13-overdue", assetId = mower.id.value, title = "Blade sharpen",
                    timeInterval = 3, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01", createdOn = "2026-01-01",
                ),
            )
        }
        draw(graph)

        rule.awaitText("Blade sharpen")
        rule.awaitText("Won't start")
        for (word in listOf("Operational", "Degraded", "Down", "Not recorded")) {
            rule.onNode(hasText(word) and hasClickAction()).assertIsDisplayed()
        }

        // Down: the DOWN unit stays, the mower's row (nothing recorded) goes.
        rule.onNode(hasText("Down") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Blade sharpen").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Won't start").assertIsDisplayed()

        // Down off, Not recorded on: the other way round.
        rule.onNode(hasText("Down") and hasClickAction()).performClick()
        rule.onNode(hasText("Not recorded") and hasClickAction()).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Won't start").fetchSemanticsNodes().isEmpty() }
        rule.awaitText("Blade sharpen")
    }

    /** An AGE subject on a new asset named [assetName], replaced [daysAgo] days ago (0 / 40 / 75). */
    private suspend fun ageSubject(graph: AppGraph, assetName: String, subject: String, daysAgo: Long) {
        val asset = graph.createAsset.run(AssetCommand(name = assetName, category = "Water")).id
        graph.saveHealthSubject.create(
            asset,
            HealthSubjectCommand(
                name = subject, kind = HealthSubjectKind.PART, driver = HealthDriver.AGE,
                nominalUntilDays = 0, warningFromDays = 40, criticalFromDays = 75,
            ),
        )
        graph.logEvent.run(
            EventCommand(
                assetId = asset, profileId = null, kind = EventKind.REPLACEMENT, title = "$subject replaced",
                occurredOn = LocalDate.now().minusDays(daysAgo).toString(), occurredTime = null, tzId = zone(),
                notes = "", values = emptyMap(), consumables = emptyList(),
            ),
        )
    }

    private companion object {
        fun today(): String = LocalDate.now().toString()

        val MMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

        fun zone(): String = ZoneId.systemDefault().id

        fun summary(severity: ReminderHealthSeverity?): HealthSummary = object : HealthSummary {
            override suspend fun worstSeverity(): ReminderHealthSeverity? = severity
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
            servicePolicy: ServicePolicy = ServicePolicy.CONTINUOUS,
            policyOffsetDays: Int? = null,
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
            servicePolicy = servicePolicy,
            policyOffsetDays = policyOffsetDays,
            completionMode = CompletionMode.QUICK,
            profileId = null,
            remindersEnabled = true,
            status = ScheduleStatus.ACTIVE,
            postponedDueOn = null,
            createdAt = dayMillis(createdOn),
            updatedAt = dayMillis(createdOn),
            ruleChangedAt = dayMillis(createdOn),
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
