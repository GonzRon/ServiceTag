package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.links.DeepLinkRoute
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.nfc.TagPayload
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.BindTag
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.Resolution
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.routeForDeepLink
import com.loosecannon.servicetag.routeForQuickCompletion
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.meterDefinitionOf
import com.loosecannon.servicetag.testing.replacementOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import com.loosecannon.servicetag.ui.condition.OperationalOfferPrompt
import com.loosecannon.servicetag.ui.condition.componentLine
import com.loosecannon.servicetag.ui.health.aggregateLine
import com.loosecannon.servicetag.ui.health.criticalLine
import com.loosecannon.servicetag.ui.journal.EventEntryViewModel
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.nav.TopLevelRoutes
import com.loosecannon.servicetag.ui.nav.readsTags
import com.loosecannon.servicetag.ui.scan.TagResult
import com.loosecannon.servicetag.ui.scan.TagResultViewModel
import com.loosecannon.servicetag.ui.scan.TagResultWire
import com.loosecannon.servicetag.ui.scan.asTagResult
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scan completion sheet: which work it admits, what each of its actions does **and does not**
 * do, and the two ways a scan could stop being navigation-only.
 *
 * The failure this class exists to catch is not a wrong list. It is a sheet that quietly completes
 * something, that lets a revoked or foreign tag into the completion path, that treats a group round
 * as one button, or that collapses Review, Snooze and Postpone into a generic "reschedule" — which
 * is the failure D5 §7A `:230-232` names out loud.
 *
 * One dispatcher carries the test body and `Dispatchers.Main`, with Room on a `StandardTestDispatcher`
 * sharing the scheduler, which is what makes `advanceUntilIdle()` a real settle here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MaintenanceSheetViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private var reconciles = 0

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-04-15")
        // The D-27 pin's floor is `max(anchorOn, createdOn)`, so the clock has to be a real date
        // before anything is created (carry-forward (c)).
        graph.now = dayMillis("2026-02-10")
        reconciles = 0
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- fixtures

    private fun readModel() = DueReadModel(
        schedules = graph.schedules,
        assets = graph.assets,
        groups = graph.groups,
        definitions = graph.definitions,
        recompute = graph.recomputeSchedules,
        today = graph.todayPort,
        health = graph.assetHealthReadModel,
        snoozedUntilOf = { null },
    )

    private fun viewModel(assetId: AssetId, tagId: TagId? = null) = MaintenanceSheetViewModel(
        due = readModel(),
        health = graph.assetHealthReadModel,
        assets = graph.assets,
        tags = graph.tags,
        readings = LastCompletionReadings { id -> graph.events.get(id)?.measurements.orEmpty() },
        lastCompletionEventId = LastCompletionEventId { id ->
            graph.scheduleStates.get(id)?.lastCompletionEventId
        },
        rounds = roundMembership(),
        snoozer = graph.scheduleSnooze,
        postponeSchedule = graph.postponeSchedule,
        reconcile = ReminderReconcile { reconciles++ },
        clock = graph.clock,
        completion = graph.completionFlow,
        assetId = assetId,
        tagId = tagId,
    )

    private suspend fun seed(schedule: MaintenanceSchedule) {
        graph.schedules.upsert(schedule)
        graph.recomputeSchedules.forSchedule(schedule.id)
    }

    private suspend fun mower(
        category: String = "Yard",
        seasonStartMmdd: String? = null,
        seasonEndMmdd: String? = null,
    ) = graph.createAsset.run(
        AssetCommand(
            name = "Mower",
            category = category,
            seasonStartMmdd = seasonStartMmdd,
            seasonEndMmdd = seasonEndMmdd,
        ),
    ).id

    private fun roundMembership() = ScanRoundMembership { scheduleId ->
        graph.schedules.get(scheduleId)?.let { graph.recomputeSchedules.occurrenceOf(it) }
    }

    /**
     * What the scan sheet would offer **this** Asset, asked of the real projection through the real
     * narrowing — the same entry point the sheet and `AppGraph.scanSheetOffer` both use.
     */
    private suspend fun offered(assetId: AssetId): List<String> =
        scanSheetItemsFor(assetId, readModel().forAsset(assetId), roundMembership())
            .map { it.scheduleId.value }

    /** Answers the open "When was this done?" with today, which is the affordance's default. */
    private fun answerToday() {
        graph.completionFlow.submit(CompletionAnswer(occurredOn = graph.today.toString()))
    }

    // ---------------------------------------------------------------- the item set

    /**
     * D-18a, whole (proportionality: one test, not nine). `DUE` and `OVERDUE` always; the
     * **repairable** `NO_DATA` with its repair; `DUE_SOON` only as a passenger and **never alone**;
     * `OK`, `INACTIVE_SEASON` and `PAUSED` never; an **archived** schedule never — carry-forward
     * (a)'s negative row, and it never even reaches the predicate because every projection query
     * starts from `listedForDue()`; and an **empty-required-set** group round, which reports
     * `NO_DATA` with *no* repair, never (master plan §11.1, invariant 74).
     *
     * Listing everything is what turns a scan into "a completion checklist for every future
     * maintenance item" (`issue-50.md:58`); excluding `NO_DATA` loses the one repair the owner is
     * standing next to.
     */
    @Test fun theSheetAdmitsExactlyDminus18asSet() = runTest(scheduler) {
        // A season that is shut on 15 April: November through February.
        val asset = mower(seasonStartMmdd = "11-01", seasonEndMmdd = "02-28")
        seed(scheduleOf("s-overdue", assetId = asset.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s-due", assetId = asset.value, title = "Oil change", anchorOn = "2026-04-15", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 0))
        seed(scheduleOf("s-soon", assetId = asset.value, title = "Belt check", anchorOn = "2026-04-20", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 14))
        seed(scheduleOf("s-ok", assetId = asset.value, title = "Deep clean", anchorOn = "2026-12-01", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 0))
        seed(scheduleOf("s-paused", assetId = asset.value, title = "Winter store", status = ScheduleStatus.PAUSED))
        seed(scheduleOf("s-season", assetId = asset.value, title = "Season job", anchorOn = "2026-01-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0))
        seed(scheduleOf("s-archived", assetId = asset.value, title = "Retired job", anchorOn = "2026-01-01", leadDays = 0, status = ScheduleStatus.ARCHIVED))
        // The other half of the same D-18a row: DUE, but its reminders are off (blocking 3).
        seed(
            scheduleOf("s-muted", assetId = asset.value, title = "Muted job", anchorOn = "2026-01-01", leadDays = 0)
                .copy(remindersEnabled = false),
        )
        // A meter rule with a definition and no baseline at all: the repairable NO_DATA.
        graph.definitions.upsert(meterDefinitionOf("d-hours", assetId = asset.value))
        seed(
            scheduleOf(
                "s-nodata", assetId = asset.value, title = "Engine hours",
                timeInterval = null, timeUnit = null, anchorOn = null,
                meterDefinitionId = "d-hours", meterInterval = 100.0,
            ),
        )
        // A group this Asset once held a window in, closed long before the round opened: the round
        // obliges nobody, so it reports NO_DATA with no repair (invariant 74).
        graph.groups.upsert(
            groupOf("g-empty", name = "Emptied run", members = listOf(Triple(asset.value, "2025-01-01", "2025-06-01"))),
        )
        seed(scheduleOf("s-empty", assetId = null, groupId = "g-empty", title = "Emptied round", anchorOn = "2026-01-01", leadDays = 0))

        val projection = readModel().forAsset(asset)
        // Every state is in the projection — it is one shared read model — and the sheet's own
        // predicate is what narrows it (decision 27).
        assertEquals(
            setOf(
                "s-overdue", "s-due", "s-soon", "s-ok", "s-paused", "s-season", "s-nodata",
                "s-empty", "s-muted",
            ),
            projection.map { it.scheduleId.value }.toSet(),
        )
        assertEquals(listOf("s-overdue", "s-due", "s-nodata", "s-soon"), offered(asset))

        // "an archived or **reminders-disabled** schedule | never": the archived one is gone before
        // the predicate sees it (`listedForDue()`), and the muted one is DUE and excluded here.
        val muted = projection.single { it.scheduleId.value == "s-muted" }
        assertEquals(DueStatus.OVERDUE, muted.status)
        assertFalse("a reminders-disabled schedule is never offered", muted.actionableOnScanSheet)
        assertFalse("nor does it ride along as a passenger", "s-muted" in offered(asset))

        val vacuous = projection.single { it.scheduleId.value == "s-empty" }
        assertEquals(DueStatus.NO_DATA, vacuous.status)
        assertTrue(vacuous.requiredSetEmpty)
        assertFalse("an empty required set is never actionable", vacuous.actionableOnScanSheet)
        assertTrue(projection.single { it.scheduleId.value == "s-nodata" }.isRepairableNoData)

        // `DUE_SOON` never alone: with the actionable rows taken away the sheet does not open.
        assertEquals(
            emptyList<DueItem>(),
            scanSheetItems(projection.filterNot { it.actionableOnScanSheet }),
        )
    }

    /** #50 AC 1: with nothing actionable, a scan opens the ordinary asset screen exactly as today. */
    @Test fun nothingActionableMeansNoSheetAtAll() = runTest(scheduler) {
        val asset = mower()
        seed(scheduleOf("s-ok", assetId = asset.value, title = "Deep clean", anchorOn = "2026-12-01", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, leadDays = 0))
        assertEquals(emptyList<String>(), offered(asset))
    }

    /**
     * The carry-forward from B02's review: the sheet's why-line names the date its status word is
     * measured against. A snowblower (spec F4: CALENDAR 15 Nov to 31 Mar, the winter break inside
     * it, PRE_SERVICE with a 14-day margin) is pulled before its season to 1 Nov, so on 10 Nov it
     * is "Overdue since" 1 Nov — never since its canonical 20 Dec, which is still the date a
     * postponement would start from.
     */
    @Test fun aPulledItemsWhyLineNamesTheActionableDate() = runTest(scheduler) {
        graph.today = LocalDate.parse("2026-11-10")
        graph.assets.upsert(
            assetRow(
                "snow", name = "Snowblower", seasonMode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31",
                breakStart = "12-01", breakEnd = "02-28",
            ),
        )
        seed(
            scheduleOf(
                "s-snow", assetId = "snow", title = "Engine oil service", timeInterval = 2, timeUnit = RecurrenceUnit.YEAR,
                anchorOn = "2026-12-20", createdOn = "2026-06-01", servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14,
            ),
        )

        val model = viewModel(AssetId("snow"))
        advanceUntilIdle()
        val item = model.state.value.items.single()

        assertEquals(DueStatus.OVERDUE, item.status)
        assertEquals("Overdue since 2026-11-01.", item.whyNow)
        assertEquals("the canonical date is unchanged", "2026-12-20", item.effectiveDueOn)
    }

    // ---------------------------------------------------------------- 1.4: the seven blocks

    /**
     * A DOWN generator (F3) with a DOWN component, a CRITICAL subject and overdue work: everything
     * spec §10.1 can draw at once. The starter battery was replaced on 25 January, 80 days before
     * 15 April, so with the fixture thresholds 0 / 40 / 75 it scores 22 — CRITICAL — and under WORST
     * the aggregate is that same 22.
     */
    private suspend fun generatorWithEverything() {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.assets.upsert(assetRow("fan", name = "Cooling fan", parent = "gen"))
        graph.conditions.insert(
            conditionRow("c-gen", "gen", OperationalCondition.DOWN, "2026-04-01", reason = "Will not start"),
        )
        graph.conditions.insert(conditionRow("c-fan", "fan", OperationalCondition.DOWN, "2026-04-02"))
        graph.events.upsert(replacementOf("e-battery", "gen", "2026-01-25"))
        graph.healthSubjects.upsert(subjectRow("h-battery", "gen", name = "Starter battery"))
        seed(scheduleOf("s-gen", assetId = "gen", title = "Load test", anchorOn = "2026-01-01", leadDays = 0))
    }

    /**
     * Spec §10.1's seven blocks, **in order**: the asset, its condition, the condition actions, the
     * DOWN or DEGRADED components, the CRITICAL subjects and the aggregate, "Maintenance" with its
     * items, and the way out — each filled from the one `scanSheetContent`.
     */
    @Test fun theSevenBlocksInOrder() = runTest(scheduler) {
        generatorWithEverything()

        val model = viewModel(AssetId("gen"))
        advanceUntilIdle()
        val state = model.state.value
        val blocks = state.blocks

        assertEquals(
            listOf("Identity", "Condition", "ConditionActions", "Components", "Health", "Maintenance", "OpenAsset"),
            blocks.map { it::class.simpleName },
        )
        assertEquals("Generator", state.assetName)
        val condition = (blocks[1] as SheetBlock.Condition).view!!
        assertEquals(OperationalCondition.DOWN, condition.condition)
        assertEquals("Will not start", condition.reason)
        assertEquals(LocalDate.parse("2026-04-01"), condition.since)
        assertEquals(OperationalCondition.DOWN, (blocks[2] as SheetBlock.ConditionActions).markOperational)
        assertEquals(
            listOf("Cooling fan DOWN — No reason given"),
            (blocks[3] as SheetBlock.Components).components.map(::componentLine),
        )
        val health = blocks[4] as SheetBlock.Health
        assertEquals(listOf("Critical: Starter battery 22"), health.critical.map(::criticalLine))
        assertEquals(HealthBand.CRITICAL, health.aggregate!!.band)
        assertEquals("22 — Starter battery 22", aggregateLine(health.aggregate!!.score, health.contributors))
        assertFalse((blocks[5] as SheetBlock.Maintenance).nothingDue)
        assertEquals(listOf("Load test"), state.items.map { it.title })
    }

    /**
     * S7 is offered beside S6 for a DOWN or DEGRADED asset, and S6 alone otherwise — including an
     * asset with nothing recorded, whose block reads S4.
     */
    @Test fun markOperationalOnlyForDownOrDegraded() = runTest(scheduler) {
        val cases = listOf(
            OperationalCondition.DOWN, OperationalCondition.DEGRADED, OperationalCondition.OPERATIONAL, null,
        )
        cases.forEachIndexed { index, condition ->
            val id = "ups-$index"
            graph.assets.upsert(assetRow(id, name = "UPS $index"))
            condition?.let { graph.conditions.insert(conditionRow("c-$id", id, it, "2026-04-01")) }
            // Due work, so the sheet opens whatever the condition says.
            seed(scheduleOf("s-$id", assetId = id, title = "Battery self-test", anchorOn = "2026-01-01", leadDays = 0))
        }

        val offered = cases.indices.map { index ->
            val model = viewModel(AssetId("ups-$index"))
            advanceUntilIdle()
            val blocks = model.state.value.blocks
            blocks.filterIsInstance<SheetBlock.ConditionActions>().single().markOperational
        }

        assertEquals(listOf(OperationalCondition.DOWN, OperationalCondition.DEGRADED, null, null), offered)
    }

    /**
     * A DOWN asset with nothing due still opens the sheet — condition is an at-the-unit concern
     * (O-8) — and its maintenance block reads S139 with no item and no "Complete selected" to act on.
     */
    @Test fun nothingDueWhenNoItem() = runTest(scheduler) {
        graph.assets.upsert(assetRow("pack", name = "Battery pack"))
        graph.conditions.insert(conditionRow("c-pack", "pack", OperationalCondition.DOWN, "2026-04-10", reason = "Cells swollen"))

        val model = viewModel(AssetId("pack"))
        advanceUntilIdle()
        val state = model.state.value

        assertTrue("condition opens the sheet", state.content!!.opens)
        assertFalse(state.emptyOnArrival)
        assertEquals(emptyList<SheetItem>(), state.items)
        assertTrue(state.blocks.filterIsInstance<SheetBlock.Maintenance>().single().nothingDue)
        assertEquals("Nothing due", NOTHING_DUE)
        assertEquals(0, graph.events.all().size)
    }

    /**
     * The carry-forward from B06's follow-up: a restored condition may name a zone this device cannot
     * resolve. The sheet draws the row as it is — its word, its reason and its "since" — and nothing
     * resolves the zone, so nothing crashes and nothing hides.
     */
    @Test fun aRestoredConditionInAZoneThisDeviceCannotResolveIsStillDrawn() = runTest(scheduler) {
        graph.assets.upsert(assetRow("tub", name = "Hot tub"))
        graph.conditions.insert(
            conditionRow(
                "c-tub", "tub", OperationalCondition.DEGRADED, "2026-04-03",
                reason = "Heater slow", tzId = "Mars/Olympus_Mons",
            ),
        )

        val model = viewModel(AssetId("tub"))
        advanceUntilIdle()
        val view = model.state.value.blocks.filterIsInstance<SheetBlock.Condition>().single().view!!

        assertEquals(OperationalCondition.DEGRADED, view.condition)
        assertEquals("Heater slow", view.reason)
        assertEquals(LocalDate.parse("2026-04-03"), view.since)
        assertEquals(
            OperationalCondition.DEGRADED,
            model.state.value.blocks.filterIsInstance<SheetBlock.ConditionActions>().single().markOperational,
        )
    }

    /** A DOWN UPS (F1) with [titles] each overdue, and the sheet open on it with every row selected. */
    private suspend fun TestScope.downUpsWithEverythingSelected(vararg titles: String): MaintenanceSheetViewModel {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.conditions.insert(conditionRow("c-ups", "ups", OperationalCondition.DOWN, "2026-04-01", reason = "Alarm on"))
        titles.forEachIndexed { index, title ->
            seed(scheduleOf("s-ups-$index", assetId = "ups", title = title, anchorOn = "2026-01-01", leadDays = 0))
        }
        val model = viewModel(AssetId("ups"))
        advanceUntilIdle()
        model.state.value.items.forEach { model.toggle(it.scheduleId) }
        return model
    }

    /**
     * The ruling on B12's review, R-2: the completion's reminder reconcile runs **as soon as the
     * completion is written**, while "Mark operational?" is still open — so leaving the sheet with the
     * offer open skips only the offer's write. "Not yet" then writes nothing.
     */
    @Test fun theReconcileRunsBeforeTheOfferIsAnswered() = runTest(scheduler) {
        val model = downUpsWithEverythingSelected("Battery self-test")

        model.completeSelected()
        advanceUntilIdle()
        answerToday()
        advanceUntilIdle()

        assertNotNull("the offer is open", graph.completionFlow.offer.value)
        assertEquals(1, graph.events.all().size)
        assertEquals("reconciled before the offer is answered", 1, reconciles)

        graph.completionFlow.declineOffer()
        advanceUntilIdle()
        assertEquals(1, reconciles)
        assertEquals(1, graph.conditions.all().size)
    }

    /**
     * The ruling on B12's review, M-1: one "Complete selected" asks each asset "Mark operational?" at
     * most once. After "Not yet" on the first item, the second item done on the same DOWN asset does
     * not ask again; both completions are written and reconciled.
     */
    @Test fun aSelectionAsksEachAssetOnce() = runTest(scheduler) {
        val model = downUpsWithEverythingSelected("Battery self-test", "Fan clean")

        model.completeSelected()
        advanceUntilIdle()
        answerToday()
        advanceUntilIdle()
        assertNotNull("the first item asks", graph.completionFlow.offer.value)
        graph.completionFlow.declineOffer()
        advanceUntilIdle()

        assertNotNull("the second item's question is open", graph.completionFlow.prompt.value)
        answerToday()
        advanceUntilIdle()

        assertNull("the same asset is not asked again in this selection", graph.completionFlow.offer.value)
        assertEquals(2, graph.events.all().size)
        assertEquals(2, reconciles)
        assertEquals(1, graph.conditions.all().size)
        assertFalse(model.state.value.busy)
    }

    /**
     * The ruling on B12's review, RS-1: the batch is **per selection**. "Not yet" in one "Complete
     * selected" is not carried into the next one on the same DOWN asset: that one asks again.
     */
    @Test fun aNewSelectionAsksAgain() = runTest(scheduler) {
        val model = downUpsWithEverythingSelected("Battery self-test", "Fan clean")
        val second = model.state.value.items[1].scheduleId
        model.toggle(second)

        model.completeSelected()
        advanceUntilIdle()
        answerToday()
        advanceUntilIdle()
        assertNotNull("the first selection asks", graph.completionFlow.offer.value)
        graph.completionFlow.declineOffer()
        advanceUntilIdle()

        model.toggle(second)
        model.completeSelected()
        advanceUntilIdle()
        answerToday()
        advanceUntilIdle()

        assertNotNull("a new selection asks again", graph.completionFlow.offer.value)
        graph.completionFlow.declineOffer()
        advanceUntilIdle()
        assertEquals(2, graph.events.all().size)
        assertEquals(1, graph.conditions.all().size)
    }

    /** A MAINTENANCE profile on [assetId], as a form item's journal entry logs it. */
    private suspend fun maintenanceForm(assetId: String) = graph.saveProfile.run(
        null,
        ProfileCommand(
            assetId = AssetId(assetId), name = "Annual service", eventKind = EventKind.MAINTENANCE,
            defaultTitle = "Annual service", fields = emptyList(), consumables = emptyList(),
        ),
    ).id

    /** The journal entry a form item opens, saved as it opens; its offer (or none) is returned. */
    private suspend fun TestScope.saveTheForm(assetId: String, profileId: ProfileId): EventEntryViewModel {
        val entry = EventEntryViewModel(
            graph.assets, graph.definitions, graph.profiles, graph.events,
            graph.logEvent, graph.updateEvent, graph.clock,
            AssetId(assetId), profileId, null, offers = graph.eventOffers,
        )
        entry.state.first { it.loaded }
        entry.save()
        advanceUntilIdle()
        return entry
    }

    /**
     * The ruling on B12's review, RS-2: a selection holding a **form** item and a **quick** item for
     * one DOWN asset asks "Mark operational?" once — once per asset per batch, whatever the item kinds
     * and whichever comes first. The form's journal entry asks through the same open batch the flow
     * does.
     */
    @Test fun aFormItemAndAQuickItemOnOneAssetAskOnce() = runTest(scheduler) {
        // Form first: the journal entry asks, and the quick item after it does not.
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.conditions.insert(conditionRow("c-ups", "ups", OperationalCondition.DOWN, "2026-04-01"))
        val upsForm = maintenanceForm("ups")
        seed(scheduleOf("f-ups", assetId = "ups", title = "Annual service", anchorOn = "2026-01-01", leadDays = 0, completionMode = CompletionMode.FORM))
        seed(scheduleOf("q-ups", assetId = "ups", title = "Fan clean", anchorOn = "2026-01-02", leadDays = 0))
        val ups = viewModel(AssetId("ups"))
        advanceUntilIdle()
        assertEquals(listOf("f-ups", "q-ups"), ups.state.value.items.map { it.scheduleId.value })
        ups.state.value.items.forEach { ups.toggle(it.scheduleId) }
        ups.completeSelected()
        advanceUntilIdle()

        val entry = saveTheForm("ups", upsForm)
        assertTrue("the form's entry asks", entry.state.value.offer is OperationalOfferPrompt)
        entry.declineOffer()
        // The owner's form completes its occurrence, as in the shipped form tests.
        graph.completeSchedule.run(ScheduleId("f-ups"), CompletionCommand(occurredOn = graph.today.toString(), tzId = "UTC"))
        ups.refresh()
        advanceUntilIdle()
        assertNotNull("the quick item's question is open", graph.completionFlow.prompt.value)
        answerToday()
        advanceUntilIdle()
        assertNull("the quick item does not ask again", graph.completionFlow.offer.value)
        assertFalse(ups.state.value.busy)

        // Quick first: the flow asks, and the form's journal entry after it does not.
        graph.assets.upsert(assetRow("pack", name = "Battery pack"))
        graph.conditions.insert(conditionRow("c-pack", "pack", OperationalCondition.DOWN, "2026-04-01"))
        val packForm = maintenanceForm("pack")
        seed(scheduleOf("q-pack", assetId = "pack", title = "Cell check", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("f-pack", assetId = "pack", title = "Annual service", anchorOn = "2026-01-02", leadDays = 0, completionMode = CompletionMode.FORM))
        val pack = viewModel(AssetId("pack"))
        advanceUntilIdle()
        assertEquals(listOf("q-pack", "f-pack"), pack.state.value.items.map { it.scheduleId.value })
        pack.state.value.items.forEach { pack.toggle(it.scheduleId) }
        pack.completeSelected()
        advanceUntilIdle()
        answerToday()
        advanceUntilIdle()
        assertNotNull("the quick item asks", graph.completionFlow.offer.value)
        graph.completionFlow.declineOffer()
        advanceUntilIdle()

        val packEntry = saveTheForm("pack", packForm)
        assertNull("the form's entry does not ask again", packEntry.state.value.offer)
        assertEquals(2, graph.conditions.all().size)
    }

    // ---------------------------------------------------------------- completion

    /**
     * #50 AC 2 and AC 8: one due `QUICK` schedule shows the sheet, an explicit completion writes
     * **exactly one** event, that occurrence is **no longer offered**, and **`reconcile` runs** — so
     * the standing notification is quiesced by canonical state and not by deleting a notification.
     * #50 AC 12 rides along: the re-read comes from the store, so a repeat scan re-offers nothing.
     */
    @Test fun oneQuickCompletionWritesOneEventStopsBeingOfferedAndReconciles() = runTest(scheduler) {
        val asset = mower()
        seed(scheduleOf("s1", assetId = asset.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        assertEquals(listOf("s1"), offered(asset))

        val model = viewModel(asset)
        advanceUntilIdle()
        val item = model.state.value.items.single()
        assertEquals("Blade sharpen", item.title)

        model.toggle(item.scheduleId)
        model.completeSelected()
        advanceUntilIdle()
        answerToday()
        advanceUntilIdle()

        assertEquals(1, graph.events.all().count { it.scheduleId?.value == "s1" })
        assertEquals(1, reconciles)
        assertEquals(emptyList<String>(), model.state.value.items.map { it.scheduleId.value })
        assertEquals(emptyList<String>(), offered(asset))
    }

    /** #50 AC 3: two due `QUICK` schedules complete together without duplicating either event. */
    @Test fun twoSelectedTogetherCompleteWithoutDuplicatingEither() = runTest(scheduler) {
        val asset = mower()
        seed(scheduleOf("s1", assetId = asset.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        seed(scheduleOf("s2", assetId = asset.value, title = "Oil change", anchorOn = "2026-01-02", leadDays = 0))

        val model = viewModel(asset)
        advanceUntilIdle()
        model.state.value.items.forEach { model.toggle(it.scheduleId) }
        model.completeSelected()
        advanceUntilIdle()
        answerToday()
        advanceUntilIdle()
        answerToday()
        advanceUntilIdle()

        assertEquals(1, graph.events.all().count { it.scheduleId?.value == "s1" })
        assertEquals(1, graph.events.all().count { it.scheduleId?.value == "s2" })
        assertEquals(2, reconciles)
        assertEquals(emptyList<String>(), offered(asset))
    }

    /** #50 AC 4: a `FORM` schedule routes through its form and is not complete until it is saved. */
    @Test fun aFormScheduleIsNotCompletedUntilItsFormIsSaved() = runTest(scheduler) {
        val asset = mower()
        seed(
            scheduleOf(
                "s-form", assetId = asset.value, title = "Annual service",
                anchorOn = "2026-01-01", leadDays = 0, completionMode = CompletionMode.FORM,
            ),
        )
        val model = viewModel(asset)
        advanceUntilIdle()
        val forms = mutableListOf<String>()
        backgroundScope.launch { model.needsForm.collect { forms += it.scheduleId.value } }
        advanceUntilIdle()

        model.toggle(ScheduleId("s-form"))
        model.completeSelected()
        advanceUntilIdle()

        assertEquals(listOf("s-form"), forms)
        assertEquals(0, graph.events.all().count { it.scheduleId?.value == "s-form" })
        assertEquals("nothing was written, so nothing was reconciled", 0, reconciles)
        assertEquals(listOf("s-form"), offered(asset))
    }

    /**
     * Several selected forms run **sequentially**: the first opens, and only once it is saved does
     * the second — and abandoning the second leaves the first's event written and the second's
     * **absent**. Never a partial silent write.
     */
    @Test fun selectedFormsRunOneAtATimeAndAbandoningOneStopsTheRun() = runTest(scheduler) {
        val asset = mower()
        seed(scheduleOf("f1", assetId = asset.value, title = "Annual service", anchorOn = "2026-01-01", leadDays = 0, completionMode = CompletionMode.FORM))
        seed(scheduleOf("f2", assetId = asset.value, title = "Deep clean", anchorOn = "2026-01-02", leadDays = 0, completionMode = CompletionMode.FORM))

        val model = viewModel(asset)
        advanceUntilIdle()
        val forms = mutableListOf<String>()
        backgroundScope.launch { model.needsForm.collect { forms += it.scheduleId.value } }
        advanceUntilIdle()

        model.state.value.items.forEach { model.toggle(it.scheduleId) }
        model.completeSelected()
        advanceUntilIdle()
        assertEquals("one form at a time", listOf("f1"), forms)

        // The owner saves the first form, which is what the journal entry screen does.
        graph.completeSchedule.run(ScheduleId("f1"), CompletionCommand(occurredOn = graph.today.toString(), tzId = "UTC"))
        model.refresh()
        advanceUntilIdle()
        assertEquals("and only then does the second open", listOf("f1", "f2"), forms)
        // #50 AC 8 on the FORM path: the journal screen wrote the event and reconciles nothing, so
        // the sheet is where the standing notification is quiesced by canonical state
        // (should-fix 4).
        assertEquals(1, reconciles)

        // …and abandons the second. The run stops rather than carrying on behind their back.
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf("f1", "f2"), forms)
        assertEquals(1, graph.events.all().count { it.scheduleId?.value == "f1" })
        assertEquals(0, graph.events.all().count { it.scheduleId?.value == "f2" })
        assertEquals("an abandoned form reconciles nothing", 1, reconciles)
    }

    /** #50 AC 5 / #11: a meter schedule cannot be completed without its required reading. */
    @Test fun aMeterScheduleCannotCompleteWithoutItsReading() = runTest(scheduler) {
        val asset = mower()
        graph.definitions.upsert(meterDefinitionOf("d-hours", assetId = asset.value))
        seed(
            scheduleOf(
                "s-meter", assetId = asset.value, title = "Oil change",
                timeInterval = null, timeUnit = null, anchorOn = null,
                meterDefinitionId = "d-hours", meterInterval = 100.0,
            ),
        )
        val model = viewModel(asset)
        advanceUntilIdle()
        val item = model.state.value.items.single()
        // The repairable NO_DATA: the missing baseline, offered as its repair and never selectable
        // for a completion (carry-forward (f)).
        assertEquals("NO BASELINE", item.statusWord)
        assertTrue(item.repairOnly)
        assertFalse(item.selectable)
        assertFalse("a repair is not an obligation, so it is not snoozeable", item.canSnooze)
        assertFalse("and there is no occurrence date to move", item.canPostpone)

        model.repair(item.scheduleId)
        advanceUntilIdle()
        val prompt = model.completion.prompt.value
        assertNotNull(prompt)
        assertTrue(prompt!!.needsMeterReading)
        // An empty reading is refused and the prompt stays open: nothing is written.
        assertFalse(model.completion.submit(CompletionAnswer(occurredOn = graph.today.toString())))
        assertEquals(0, graph.events.all().count { it.scheduleId?.value == "s-meter" })
        model.completion.cancel()
        advanceUntilIdle()
        assertEquals(0, graph.events.all().size)
        assertEquals(0, reconciles)
    }

    /**
     * D-25 and the D7 Phase 3 exit criterion: completing with **yesterday's** date yields the next
     * due date derived from the **backdated** event, not from today. Defaulting silently to today
     * would make "When was this done?" cosmetic.
     */
    @Test fun aBackdatedCompletionDerivesTheNextDateFromTheBackdatedEvent() = runTest(scheduler) {
        val asset = mower()
        seed(
            scheduleOf(
                "s-back", assetId = asset.value, title = "Blade sharpen",
                timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, timeBasis = TimeBasis.COMPLETION,
                anchorOn = "2026-01-01", leadDays = 0,
            ),
        )
        val model = viewModel(asset)
        advanceUntilIdle()
        model.toggle(ScheduleId("s-back"))
        model.completeSelected()
        advanceUntilIdle()
        val yesterday = graph.today.minusDays(1)
        model.completion.submit(CompletionAnswer(occurredOn = yesterday.toString()))
        advanceUntilIdle()

        val event = graph.events.all().single { it.scheduleId?.value == "s-back" }
        assertEquals(yesterday.toString(), event.occurredOn)
        assertEquals(
            yesterday.plusMonths(1).toString(),
            graph.scheduleStates.get(ScheduleId("s-back"))?.computedDueOn,
        )
    }

    // ---------------------------------------------------------------- the other actions

    /**
     * #50 AC 6: **"Snooze"** writes only the device-local instant — no date moves, no event is
     * written, the schedule row is byte-identical and its status is unchanged (invariant 20).
     */
    @Test fun snoozeWritesOnlyTheDeviceLocalInstant() = runTest(scheduler) {
        val asset = mower()
        seed(scheduleOf("s1", assetId = asset.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        val before = graph.schedules.get(ScheduleId("s1"))!!
        val state = graph.scheduleStates.get(ScheduleId("s1"))!!

        val model = viewModel(asset)
        advanceUntilIdle()
        assertTrue(model.state.value.items.single().canSnooze)
        model.snooze(ScheduleId("s1"))
        advanceUntilIdle()

        assertEquals(
            graph.now + SNOOZE_MILLIS,
            graph.scheduleLocalDelivery.get(ScheduleId("s1"))?.snoozedUntilAt,
        )
        assertEquals(before, graph.schedules.get(ScheduleId("s1")))
        assertEquals(state.effectiveDueOn, graph.scheduleStates.get(ScheduleId("s1"))?.effectiveDueOn)
        assertEquals(0, graph.events.all().size)
        assertEquals(0, reconciles)
        assertEquals("and the work is still offered, because it is still due", listOf("s1"), offered(asset))
    }

    /**
     * #50 AC 6: **"Postpone"** changes only `postponed_due_on` — no event, no rule change, and
     * `computed_due_on` (which is where the *next* occurrence comes from) is untouched
     * (invariant 21).
     */
    @Test fun postponeMovesThisOccurrenceOnly() = runTest(scheduler) {
        val asset = mower()
        seed(scheduleOf("s1", assetId = asset.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        val before = graph.schedules.get(ScheduleId("s1"))!!
        val computed = graph.scheduleStates.get(ScheduleId("s1"))?.computedDueOn

        val model = viewModel(asset)
        advanceUntilIdle()
        assertTrue(model.state.value.items.single().canPostpone)
        model.postpone(ScheduleId("s1"), "2026-05-20")
        advanceUntilIdle()

        val after = graph.schedules.get(ScheduleId("s1"))!!
        assertEquals("2026-05-20", after.postponedDueOn)
        assertEquals(before.copy(postponedDueOn = "2026-05-20", updatedAt = after.updatedAt), after)
        assertEquals(computed, graph.scheduleStates.get(ScheduleId("s1"))?.computedDueOn)
        assertEquals("2026-05-20", graph.scheduleStates.get(ScheduleId("s1"))?.effectiveDueOn)
        assertEquals(0, graph.events.all().size)
    }

    /**
     * #50 AC 6: **"Not now"** and the system back gesture write **nothing at all** — which at this
     * layer is the statement that opening the sheet, selecting every row and leaving writes nothing.
     * "Review maintenance" is the same fact: it navigates and the view model has no action for it.
     */
    @Test fun notNowBackAndReviewWriteNothingAtAll() = runTest(scheduler) {
        val asset = mower()
        seed(scheduleOf("s1", assetId = asset.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        val before = graph.schedules.get(ScheduleId("s1"))!!

        val model = viewModel(asset)
        advanceUntilIdle()
        model.state.value.items.forEach { model.toggle(it.scheduleId) }
        advanceUntilIdle()

        assertEquals(0, graph.events.all().size)
        assertEquals(0, graph.closures.all().size)
        assertEquals(0, reconciles)
        assertEquals(before, graph.schedules.get(ScheduleId("s1")))
        assertNull(graph.scheduleLocalDelivery.get(ScheduleId("s1"))?.snoozedUntilAt)
    }

    // ---------------------------------------------------------------- display and context

    /**
     * D5 §7A `:219-221`, as **one** composed assertion per the proportionality ruling: the state
     * word, the due date and/or the meter threshold, the current meter value, the last completion's
     * date **and its key readings**, the why-now line, and quick versus form **before** selection.
     *
     * A bare title-and-status row cannot answer "is this the thing I am standing next to, and
     * should I do it now".
     */
    @Test fun anItemShowsEverythingTheOwnerNeedsToDecide() = runTest(scheduler) {
        val asset = mower()
        graph.definitions.upsert(meterDefinitionOf("d-hours", assetId = asset.value))
        seed(
            scheduleOf(
                "s1", assetId = asset.value, title = "Oil change",
                anchorOn = "2026-01-01", leadDays = 0,
                meterDefinitionId = "d-hours", meterInterval = 50.0, anchorMeter = 100.0,
            ),
        )
        // A completion whose event carries the reading the sheet has to show back.
        graph.completeSchedule.run(
            ScheduleId("s1"),
            CompletionCommand(occurredOn = "2026-03-01", tzId = "UTC", values = mapOf(graph.definitions.all().single().id to "120")),
        )
        graph.recomputeSchedules.forSchedule(ScheduleId("s1"))

        val model = viewModel(asset)
        advanceUntilIdle()
        val item = model.state.value.items.singleOrNull()
            ?: error("expected one row, got ${model.state.value.items.map { it.title }}")

        assertEquals("OVERDUE", item.statusWord)
        assertEquals(statusLabel(item.status), item.statusWord)
        assertNotNull("a dated rule shows its date", item.effectiveDueOn)
        assertEquals(
            "the RATIFIED per-item form for this status, and no new sentence template",
            "Overdue since ${item.effectiveDueOn}.",
            item.whyNow,
        )
        assertEquals("the ratified meter line, threshold and current value", "Due at 170 h, now 120.", item.meter)
        assertEquals("2026-03-01", item.lastCompletedOn)
        assertEquals(listOf("120 h"), item.lastReadings)
        assertEquals("quick versus form, before any selection", ONE_TAP, item.completionTakes)
        assertNull("an asset target has no round to report progress on", item.progress)
    }

    /**
     * #50 AC 9, #49 AC 3: a second active tag on the same Asset shows the **same** work with **its
     * own** placement label as the context. Per-tag schedule state, or the first tag's label, would
     * misidentify the scan point.
     */
    @Test fun eitherTagOnOneAssetShowsTheSameWorkUnderItsOwnLabel() = runTest(scheduler) {
        val asset = mower()
        seed(scheduleOf("s1", assetId = asset.value, title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        val deck = boundTag("11111111-1111-4111-8111-111111111111", asset, "Deck plate")
        val handle = boundTag("22222222-2222-4222-8222-222222222222", asset, "Handle bar")

        val first = viewModel(asset, deck)
        advanceUntilIdle()
        val second = viewModel(asset, handle)
        advanceUntilIdle()

        assertEquals(
            first.state.value.items.map { it.scheduleId.value },
            second.state.value.items.map { it.scheduleId.value },
        )
        assertEquals("Deck plate", first.state.value.tagPlacement)
        assertEquals("Handle bar", second.state.value.tagPlacement)
    }

    // ---------------------------------------------------------------- groups

    /**
     * Invariants 28 and 29 at this surface: a scanned **member** of a due group round is offered
     * that round, completing it marks **this member only** — no other member gets an event — and
     * the round is still **open** while others remain, which is also the ratified progress form.
     */
    @Test fun aScannedGroupMemberCompletesItselfAndNobodyElse() = runTest(scheduler) {
        val one = graph.createAsset.run(AssetCommand(name = "Sprinkler one", category = "Irrigation")).id
        val two = graph.createAsset.run(AssetCommand(name = "Sprinkler two", category = "Irrigation")).id
        graph.groups.upsert(
            groupOf(
                "g1",
                name = "North run",
                members = listOf(Triple(one.value, "2026-01-01", null), Triple(two.value, "2026-01-01", null)),
            ),
        )
        seed(scheduleOf("g-s", assetId = null, groupId = "g1", title = "Head flush", anchorOn = "2026-01-01", leadDays = 0))

        val model = viewModel(one)
        advanceUntilIdle()
        val item = model.state.value.items.single()
        assertEquals("0 of 2 complete", item.progress)

        model.toggle(item.scheduleId)
        model.completeSelected()
        advanceUntilIdle()
        answerToday()
        advanceUntilIdle()

        val written = graph.events.all().filter { it.scheduleId?.value == "g-s" }
        assertEquals(listOf(one.value), written.map { it.assetId.value })
        assertEquals(1, reconciles)

        // #50 AC 12 at the **group** level: this member has done this round, so a second scan of
        // this Asset does not re-offer it — even though the round is still open and the schedule
        // still reads DUE (review blocking 2).
        assertEquals(emptyList<String>(), offered(one))
        assertEquals(emptyList<String>(), model.state.value.items.map { it.scheduleId.value })

        // The round is still open, so the other member is still offered its own work and the
        // progress has moved by exactly one.
        val other = viewModel(two)
        advanceUntilIdle()
        assertEquals("1 of 2 complete", other.state.value.items.single().progress)
        assertEquals(listOf("g-s"), offered(two))
    }

    /**
     * The other half of the group contract, and the one `requiredSetEmpty` cannot answer (review
     * blocking 2): `DueReadModel.forAsset` returns a deliberate **superset** on the group half, so
     * the sheet has to ask the round itself whether it obliges **this** Asset.
     *
     * A **former member** — window closed before the round opened — and a member who has **already
     * done it** are both offered nothing, while the outstanding required member is offered the
     * round. Without the narrowing the first would be handed work `CompleteGroupMembers` refuses as
     * `NotARequiredMember` and the second a completion the idempotence index refuses, and in both
     * cases the owner would tap, answer "When was this done?", and watch nothing happen.
     */
    @Test fun aRoundOfferedOnlyToTheMembersItObligesAndOnlyUntilTheyDoIt() = runTest(scheduler) {
        val former = graph.createAsset.run(AssetCommand(name = "Sprinkler one", category = "Irrigation")).id
        val doneAlready = graph.createAsset.run(AssetCommand(name = "Sprinkler two", category = "Irrigation")).id
        val outstanding = graph.createAsset.run(AssetCommand(name = "Sprinkler three", category = "Irrigation")).id
        graph.groups.upsert(
            groupOf(
                "g-mixed",
                name = "North run",
                members = listOf(
                    // Its window closed long before the round opened, so the round never obliged it.
                    Triple(former.value, "2025-01-01", "2025-06-01"),
                    Triple(doneAlready.value, "2026-01-01", null),
                    Triple(outstanding.value, "2026-01-01", null),
                ),
            ),
        )
        seed(scheduleOf("g-mixed-s", assetId = null, groupId = "g-mixed", title = "Head flush", anchorOn = "2026-01-01", leadDays = 0))
        graph.completeGroupMembers.run(
            ScheduleId("g-mixed-s"),
            listOf(doneAlready),
            CompletionCommand(occurredOn = "2026-04-14", tzId = "UTC"),
        )

        // The round is open, obliges two members, and one of them has done it.
        val round = graph.recomputeSchedules.occurrenceOf(graph.schedules.get(ScheduleId("g-mixed-s"))!!)!!
        assertEquals(listOf(doneAlready, outstanding).map { it.value }.sorted(), round.required.map { it.value }.sorted())
        assertEquals(listOf(doneAlready.value), round.completed.map { it.value })

        // The projection hands all three Assets the row — it is a superset by design …
        listOf(former, doneAlready, outstanding).forEach { assetId ->
            assertTrue(
                "the projection is a superset on the group half",
                readModel().forAsset(assetId).any { it.scheduleId.value == "g-mixed-s" },
            )
        }
        // … and the sheet offers it to exactly one of them.
        assertEquals(emptyList<String>(), offered(former))
        assertEquals(emptyList<String>(), offered(doneAlready))
        assertEquals(listOf("g-mixed-s"), offered(outstanding))

        val model = viewModel(former)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), model.state.value.items.map { it.scheduleId.value })
        assertEquals("and nothing was written on the way to finding that out", 1, graph.events.all().size)
    }

    // ---------------------------------------------------------------- navigation-only

    /**
     * Invariant 58 and #50 AC 10: **one row per non-`OpenAsset` resolution**, each asserting the
     * sheet is unreachable and the shipped routing is unchanged — every one of them still maps to
     * the `Route.TagResult` it mapped to before.
     *
     * Invariant 5 rides on the same exhaustive `when` (master plan §13, structural): `TagTarget` has
     * no group case and `Resolution` has no group variant, so a tag cannot resolve to a group — and
     * `asTagResult`'s `when` has no `else ->` through which a new variant could slip into the
     * completion path unnoticed.
     */
    @Test fun noResolutionButOpenAssetIsEvenAskedAboutTheSheet() = runTest(scheduler) {
        val key = "77777777-7777-4777-8777-777777777777"
        // Every resolution a (format, key) pair can actually produce, driven through the real
        // `TagResultViewModel` with an offer that **throws if it is consulted**. `NeedsNewerApp` is
        // unreachable from the wire pair by construction — the pair carries no payload version — so
        // it is covered by the mapping half below alone.
        val cases: List<Pair<String, suspend () -> String>> = listOf(
            "Unbound" to {
                graph.tags.upsert(binding(key, TagTarget.None, TagStatus.UNBOUND)); key
            },
            "Revoked" to {
                graph.tags.upsert(binding(key, TagTarget.None, TagStatus.RETIRED)); key
            },
            "UnknownV1" to { key },
            "PreSplitLink" to {
                graph.links.upsert(
                    ExternalLink(LinkId("l1"), null, LinkKind.JOPLIN, "note", "joplin://x", 1L, null, 1L),
                )
                graph.tags.upsert(binding(key, TagTarget.LinkTarget(LinkId("l1")), TagStatus.ACTIVE))
                key
            },
        )
        cases.forEach { (name, seedTag) ->
            graph.tags.deleteAll()
            val scanned = seedTag()
            val model = TagResultViewModel(
                ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock),
                BindTag(graph.tags, graph.assets, graph.uow, graph.clock),
                graph.assets,
                { error("a $name resolution must never be asked about the sheet") },
                PayloadFormat.V1.name,
                scanned,
            )
            advanceUntilIdle()
            assertFalse("$name reached OpensAsset", model.state.value is TagResult.OpensAsset)
        }
        // And "not ours at all", which never reaches `ResolveTag`'s tag lookup either.
        val foreign = TagResultViewModel(
            ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock),
            BindTag(graph.tags, graph.assets, graph.uow, graph.clock),
            graph.assets,
            { error("a NotOurs resolution must never be asked about the sheet") },
            TagResultWire.FORMAT_NONE,
            "not a ServiceTag tag",
        )
        advanceUntilIdle()
        assertTrue(foreign.state.value is TagResult.NotOurs)

        theShippedRoutingIsUnchanged()
    }

    /** The other half of the brief's row: every resolution still maps to the route it always did. */
    private fun theShippedRoutingIsUnchanged() {
        val binding = TagBinding(
            TagId("33333333-3333-4333-8333-333333333333"),
            PayloadFormat.V1,
            "33333333-3333-4333-8333-333333333333",
            TagTarget.None,
            TagStatus.UNBOUND,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val resolutions = listOf<Resolution>(
            Resolution.Unbound(binding),
            Resolution.Revoked(binding.copy(status = TagStatus.RETIRED)),
            Resolution.UnknownV1(TagId("44444444-4444-4444-8444-444444444444")),
            Resolution.NeedsNewerApp(9),
            Resolution.NotOurs(TagPayload.Empty),
            Resolution.PreSplitLink(binding.copy(target = TagTarget.LinkTarget(LinkId("l1")))),
        )
        resolutions.forEach { resolution ->
            assertFalse("$resolution", resolution is Resolution.OpenAsset)
            val route: Route = resolution.asTagResult()
            assertTrue("$resolution still routes exactly as today", route is Route.TagResult)
        }
    }

    /**
     * The sheet holds no reader mode: `Route.readsTags()` is unchanged, `Route.MaintenanceSheet` is
     * **not** in it, and it is not a tab either. Adding it would hold reader mode over a screen with
     * no tag sink and re-open the #37 re-dispatch.
     */
    @Test fun theSheetIsNotATagReadingRoute() {
        assertFalse(Route.MaintenanceSheet("a1", "t1").readsTags())
        assertFalse(Route.MaintenanceSheet("a1", null).readsTags())
        assertFalse(TopLevelRoutes.any { it is Route.MaintenanceSheet })
        assertTrue("and the routes that do read tags still do", Route.Scan.readsTags())
    }

    // ---------------------------------------------------------------- B07's Done redirect

    /**
     * B07's review left this brief the one-line target change (#50's redirect): **"Done"** on a
     * `FORM` schedule, or a `QUICK` one carrying a meter rule, no longer merely opens the schedule
     * — it opens it with B14's canonical `CompletionFlow` already asking "When was this done?", so
     * the notification path and this sheet share the **one** completion mechanism.
     *
     * The two halves the failure would hide are both here: the redirect names a destination and
     * writes nothing, and an **external** `servicetag://schedule/<uuid>` is untouched — the
     * *mapping* an outside link is routed by opens no completion flow of its own.
     *
     * That is all this test establishes, and the docstring used to claim more (review should-fix 8):
     * `MainActivity` is exported, so whether an outside **intent** can pre-open the question is a
     * question about `routeFrom`'s provenance check, not about this pure function. `routeFrom` now
     * honours the extra only on an intent addressed explicitly to `MainActivity`, and even then the
     * pre-opened question writes nothing until it is answered — which is what keeps invariant 57.
     */
    @Test fun doneOnAFormOrMeterScheduleLandsOnTheCanonicalCompletionFlow() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val link = DeepLinkRoute.parse("servicetag", "schedule", listOf(id))

        assertEquals(Route.ScheduleDetail(id, complete = true), routeForQuickCompletion(link))
        assertEquals(
            "an external link is unchanged and opens no completion flow",
            Route.ScheduleDetail(id),
            routeForDeepLink(link),
        )
        assertFalse((routeForDeepLink(link) as Route.ScheduleDetail).complete)
        // Nothing but a well-formed schedule link can carry the instruction anywhere.
        assertNull(routeForQuickCompletion(DeepLinkRoute.parse("servicetag", "asset", listOf(id))))
        assertNull(routeForQuickCompletion(DeepLinkRoute.parse("servicetag", "schedule", listOf("nope"))))
        assertNull(routeForQuickCompletion(null))
    }

    // ---------------------------------------------------------------- helpers

    private fun binding(key: String, target: TagTarget, status: TagStatus) = TagBinding(
        TagId(key),
        PayloadFormat.V1,
        key,
        target,
        status,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private suspend fun boundTag(id: String, assetId: AssetId, label: String): TagId {
        graph.tags.upsert(
            TagBinding(
                TagId(id),
                PayloadFormat.V1,
                id,
                TagTarget.AssetTarget(assetId),
                TagStatus.ACTIVE,
                label = label,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        return TagId(id)
    }
}
