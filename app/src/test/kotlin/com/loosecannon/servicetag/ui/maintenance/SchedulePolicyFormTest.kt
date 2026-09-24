package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonInputs
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.meterDefinitionOf
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The schedule editor's service-policy question (spec §10.4, §4.1, §4.2; strings S65–S84).
 *
 * What is proved here is the form: which answers each asset is offered, what each answer means as a
 * policy and a signed offset, that nothing the owner must decide is prefilled (inv. 121), that the day
 * fields' filters and the held Save make every policy refusal unreachable without a sentence (master
 * dec. 46), that a stored row loads into its own answer or into none, and the two warnings. The
 * screen's drawing of it is `ScheduleEditorTest`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulePolicyFormTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    private val permission = object : NotificationPermission {
        override fun granted(): Boolean = true
        override fun shouldExplain(): Boolean = false
        override suspend fun request(): Boolean = true
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-04-15")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(scheduleId: ScheduleId? = null, targetAssetId: AssetId? = null, targetGroupId: GroupId? = null) =
        ScheduleEditViewModel(
            schedules = graph.schedules,
            assets = graph.assets,
            groups = graph.groups,
            definitions = graph.definitions,
            profiles = graph.profiles,
            saveSchedule = graph.saveSchedule,
            notifications = permission,
            today = graph.todayPort,
            scheduleId = scheduleId,
            targetAssetId = targetAssetId,
            targetGroupId = targetGroupId,
        )

    private fun season(
        mode: SeasonMode,
        start: String? = null,
        end: String? = null,
        breakStart: String? = null,
        breakEnd: String? = null,
    ) = SeasonInputs(mode, start, end, breakStart, breakEnd, emptyList())

    private val calendar = season(SeasonMode.CALENDAR, "05-01", "09-30")
    private val calendarWithBreak = season(SeasonMode.CALENDAR, "05-01", "09-30", "07-01", "07-31")
    private val manual = season(SeasonMode.MANUAL)
    private val manualWithBreak = season(SeasonMode.MANUAL, breakStart = "12-20", breakEnd = "01-05")
    private val yearRoundWithBreak = season(SeasonMode.YEAR_ROUND, breakStart = "12-20", breakEnd = "01-05")
    private val yearRound = season(SeasonMode.YEAR_ROUND)

    /** An asset form with a time rule, loaded, on [season]. */
    private fun form(season: SeasonInputs?, vararg edits: (ScheduleEditState) -> ScheduleEditState): ScheduleEditState =
        edits.fold(
            ScheduleEditState(
                target = ScheduleTarget.AssetTarget(AssetId("a-1")),
                season = season,
                timeInterval = "3",
                anchorOn = "2026-06-01",
                todayOn = LocalDate.parse("2026-04-15"),
                loaded = true,
            ),
        ) { state, edit -> edit(state) }

    private fun labels(options: List<PolicyOption>) = options.map(::policyOptionLabel)

    /** An asset of the given season, made through the shipped commands. */
    private suspend fun anAsset(
        name: String,
        mode: SeasonMode = SeasonMode.YEAR_ROUND,
        start: String? = null,
        end: String? = null,
        breakStart: String? = null,
        breakEnd: String? = null,
    ): AssetId {
        val asset = graph.createAsset.run(AssetCommand(name = name, category = "Yard"))
        if (mode != SeasonMode.YEAR_ROUND) {
            graph.setSeasonMode.run(
                asset.id,
                SeasonModeCommand(
                    seasonMode = mode,
                    seasonStartMmdd = start,
                    seasonEndMmdd = end,
                    manualPhase = if (mode == SeasonMode.MANUAL) SeasonPhase.IN_SEASON else null,
                ),
            )
        }
        if (breakStart != null) graph.setMaintenanceBreak.run(asset.id, BreakCommand(breakStart, breakEnd))
        return asset.id
    }

    private suspend fun aStoredSchedule(
        assetId: AssetId,
        policy: ServicePolicy,
        offset: Int?,
        interval: Int? = 3,
    ): ScheduleId = graph.saveSchedule.run(
        null,
        ScheduleCommand(
            targetAssetId = assetId,
            targetGroupId = null,
            title = "Blade sharpen",
            timeInterval = interval,
            timeUnit = RecurrenceUnit.MONTH,
            anchorOn = "2026-05-15",
            servicePolicy = policy,
            policyOffsetDays = offset,
        ),
    ).id

    /**
     * Matrix row **"wrong options for the asset"**: spec §10.4's table, the five rows, in order and in
     * the ratified words — and the two rows where the question is **not drawn** at all.
     */
    @Test fun theOptionSetPerAssetMode() = runTest {
        val threeCalendar = listOf("Before the season starts", "When the season starts", "Whenever it is due")
        assertEquals(threeCalendar, labels(policyOptionsFor(calendar, hasTimeRule = true)))
        assertEquals("a break changes nothing on CALENDAR", threeCalendar, labels(policyOptionsFor(calendarWithBreak, true)))

        assertEquals(
            listOf("When the season starts", "Whenever it is due"),
            labels(policyOptionsFor(manual, hasTimeRule = true)),
        )
        assertEquals(
            listOf("Before the maintenance break", "When the season starts", "Whenever it is due"),
            labels(policyOptionsFor(manualWithBreak, hasTimeRule = true)),
        )
        assertEquals(
            listOf("Before the maintenance break", "After the maintenance break", "Whenever it is due"),
            labels(policyOptionsFor(yearRoundWithBreak, hasTimeRule = true)),
        )
        assertEquals("YEAR_ROUND without a break: no question", emptyList<PolicyOption>(), policyOptionsFor(yearRound, true))
        assertEquals("a group: no question (inv. 106)", emptyList<PolicyOption>(), policyOptionsFor(null, true))

        // The same through the view model: a group target and a boundary-less asset never see S65, and
        // both save CONTINUOUS.
        val plain = anAsset("Mower")
        val onPlain = viewModel(targetAssetId = plain).state.first { it.loaded }
        assertFalse(onPlain.questionDrawn)
        assertEquals(ServicePolicy.CONTINUOUS, onPlain.servicePolicy)
        val group = graph.saveGroup.run(
            null,
            com.loosecannon.servicetag.core.usecase.GroupCommand(
                name = "Yard run",
                members = listOf(com.loosecannon.servicetag.core.usecase.GroupMemberInput(assetId = plain)),
            ),
        )
        val onGroup = viewModel(targetGroupId = group.id).state.first { it.loaded }
        assertFalse(onGroup.questionDrawn)
        assertEquals(ServicePolicy.CONTINUOUS, onGroup.servicePolicy)
        val seasonal = anAsset("Snowblower", SeasonMode.CALENDAR, "11-01", "03-31")
        val vm = viewModel(targetAssetId = seasonal)
        val onSeasonal = vm.state.first { it.loaded }
        assertTrue(onSeasonal.questionDrawn)
        // A new schedule has no interval yet, so it opens on the meter-less row until one is typed.
        vm.onInterval("3")
        assertEquals(threeCalendar, labels(vm.state.value.policyOptions))
    }

    /**
     * Matrix row **"a meter-only pre-service choice"**: with no time rule there is no "Before…" option
     * and no offset field (O-7); the in-service option keeps its asset's label (master dec. 38). An
     * offset typed under S74 is hidden and sent as 0, and removing the time rule withdraws a chosen
     * "Before…" answer and leaves the question unanswered.
     */
    @Test fun aMeterOnlyScheduleHasNoPreServiceOptionAndNoOffset() = runTest {
        assertEquals(
            listOf(PolicyOption.WHEN_SEASON_STARTS, PolicyOption.WHENEVER_DUE),
            policyOptionsFor(calendar, hasTimeRule = false),
        )
        assertEquals(
            listOf(PolicyOption.WHEN_SEASON_STARTS, PolicyOption.WHENEVER_DUE),
            policyOptionsFor(manualWithBreak, hasTimeRule = false),
        )
        assertEquals(
            listOf(PolicyOption.AFTER_BREAK, PolicyOption.WHENEVER_DUE),
            policyOptionsFor(yearRoundWithBreak, hasTimeRule = false),
        )

        val snowblower = anAsset("Snowblower", SeasonMode.CALENDAR, "11-01", "03-31")
        val hours = meterDefinitionOf("d-hours", snowblower.value)
        graph.definitions.upsert(hours)
        val vm = viewModel(targetAssetId = snowblower)
        vm.state.first { it.loaded }
        vm.onTitle("Belt check")

        // With a time rule, S66 is chosen and a margin typed; then the time rule goes.
        vm.onInterval("3")
        vm.onPolicy(PolicyOption.BEFORE_SEASON)
        vm.onDaysBefore("14")
        vm.onMeterDefinition(hours.id)
        vm.onMeterInterval("50")
        vm.onInterval("")
        val meterOnly = vm.state.value
        assertNull("the withdrawn answer is cleared, not re-answered", meterOnly.policyOption)
        assertFalse(PolicyOption.BEFORE_SEASON in meterOnly.policyOptions)
        assertFalse("and Save waits for a pick", meterOnly.canSave)
        vm.onPolicy(PolicyOption.BEFORE_SEASON)
        assertNull("a withdrawn option cannot be picked", vm.state.value.policyOption)

        // S67 on a meter-only schedule: an offset typed while the time rule existed is hidden and sent as 0.
        vm.onInterval("3")
        vm.onPolicy(PolicyOption.WHEN_SEASON_STARTS)
        vm.onDaysAfter("5")
        vm.onInterval("")
        assertEquals(ServicePolicy.IN_SERVICE_AT_START, vm.state.value.servicePolicy)
        assertEquals(0, vm.state.value.policyOffsetDays)
        vm.save()
        vm.state.first { !it.saving }
        val stored = graph.schedules.all().single()
        assertNull(stored.timeInterval)
        assertEquals(ServicePolicy.IN_SERVICE_AT_START, stored.servicePolicy)
        assertEquals(0, stored.policyOffsetDays)
    }

    /**
     * Matrix row **"the offset's sign or default"**: 14 before → −14; S74 with nothing typed → 0; S75 →
     * null; S70 → 0; S68 → CONTINUOUS with null. And the command carries it to the store.
     */
    @Test fun optionsMapToPolicyAndSignedOffset() = runTest {
        val before = form(calendar, { it.copy(policyOption = PolicyOption.BEFORE_SEASON, daysBefore = "14") })
        assertEquals(ServicePolicy.PRE_SERVICE, before.servicePolicy)
        assertEquals(-14, before.policyOffsetDays)

        val beforeBreak = form(yearRoundWithBreak, { it.copy(policyOption = PolicyOption.BEFORE_BREAK, daysBefore = "3") })
        assertEquals(ServicePolicy.PRE_SERVICE, beforeBreak.servicePolicy)
        assertEquals(-3, beforeBreak.policyOffsetDays)

        val atStart = form(calendar, { it.copy(policyOption = PolicyOption.WHEN_SEASON_STARTS) })
        assertEquals(ServicePolicy.IN_SERVICE_AT_START, atStart.servicePolicy)
        assertEquals("S74 with nothing typed is the start itself", 0, atStart.policyOffsetDays)
        assertEquals(5, atStart.copy(daysAfter = "5").policyOffsetDays)

        val clamped = atStart.copy(startCountingFrom = StartCountingFrom.OWN_DATE, daysAfter = "5")
        assertEquals(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, clamped.servicePolicy)
        assertNull(clamped.policyOffsetDays)

        val afterBreak = form(yearRoundWithBreak, { it.copy(policyOption = PolicyOption.AFTER_BREAK) })
        assertEquals(ServicePolicy.IN_SERVICE_AT_START, afterBreak.servicePolicy)
        assertEquals(0, afterBreak.policyOffsetDays)

        val whenever = form(calendar)
        assertEquals("a new form starts on S68 (master dec. 43)", PolicyOption.WHENEVER_DUE, whenever.policyOption)
        assertEquals(ServicePolicy.CONTINUOUS, whenever.servicePolicy)
        assertNull(whenever.policyOffsetDays)

        // Through the command to the store.
        val snowblower = anAsset("Snowblower", SeasonMode.CALENDAR, "11-01", "03-31")
        val vm = viewModel(targetAssetId = snowblower)
        vm.state.first { it.loaded }
        vm.onTitle("Belt check")
        vm.onInterval("3")
        vm.onPolicy(PolicyOption.BEFORE_SEASON)
        vm.onDaysBefore("14")
        vm.save()
        vm.state.first { !it.saving }
        val stored = graph.schedules.all().single()
        assertEquals(ServicePolicy.PRE_SERVICE, stored.servicePolicy)
        assertEquals(-14, stored.policyOffsetDays)
    }

    /**
     * Matrix row **"a prefilled margin"**: S71 is empty until the owner enters it, S83 stands under it
     * while it is, and Save is held — nothing reaches `SaveSchedule` (inv. 121).
     */
    @Test fun theMarginIsEmptyUntilEnteredAndSaveRefusesWithS83() = runTest {
        assertEquals("Enter the number of days.", ENTER_THE_NUMBER_OF_DAYS)
        assertEquals("Days before it starts", DAYS_BEFORE_IT_STARTS)

        val snowblower = anAsset("Snowblower", SeasonMode.CALENDAR, "11-01", "03-31")
        val vm = viewModel(targetAssetId = snowblower)
        vm.state.first { it.loaded }
        vm.onTitle("Belt check")
        vm.onInterval("3")
        vm.onPolicy(PolicyOption.BEFORE_SEASON)

        val empty = vm.state.value
        assertEquals("nothing suggests a margin", "", empty.daysBefore)
        assertTrue("S83 stands under the empty field", empty.marginMissing)
        assertFalse("and Save is held", empty.canSave)
        vm.save()
        testScheduler.advanceUntilIdle()
        assertFalse(vm.state.value.saving)
        assertTrue("nothing reached SaveSchedule", graph.schedules.all().isEmpty())

        vm.onDaysBefore("14")
        assertFalse(vm.state.value.marginMissing)
        assertTrue(vm.state.value.canSave)
    }

    /**
     * Matrix row **"S71 out of range"** (I10): digits only, at most three, never above 365 — a keystroke
     * that breaks the filter is not accepted as typed. "0" is accepted as typed and holds Save, with no
     * refusal text: nothing is said and nothing is sent.
     */
    @Test fun s71TakesDigitsOnlyCappedAt365AndZeroKeepsSaveDisabled() = runTest {
        val snowblower = anAsset("Snowblower", SeasonMode.CALENDAR, "11-01", "03-31")
        val vm = viewModel(targetAssetId = snowblower)
        vm.state.first { it.loaded }
        vm.onTitle("Belt check")
        vm.onInterval("3")
        vm.onPolicy(PolicyOption.BEFORE_SEASON)

        vm.onDaysBefore("400")
        assertEquals("400 is not accepted", "", vm.state.value.daysBefore)
        vm.onDaysBefore("40")
        vm.onDaysBefore("400")
        assertEquals("and not turned into anything else", "40", vm.state.value.daysBefore)
        vm.onDaysBefore("365")
        assertEquals("365", vm.state.value.daysBefore)
        vm.onDaysBefore("3650")
        vm.onDaysBefore("366")
        vm.onDaysBefore("-1")
        vm.onDaysBefore("1.5")
        vm.onDaysBefore("1a")
        assertEquals("365", vm.state.value.daysBefore)

        vm.onDaysBefore("0")
        val zero = vm.state.value
        assertEquals("0 is accepted as typed", "0", zero.daysBefore)
        assertFalse("and holds Save", zero.canSave)
        assertFalse("with no refusal text", zero.marginMissing)
        vm.save()
        testScheduler.advanceUntilIdle()
        assertTrue("no refusal was provoked", vm.state.value.problems.isEmpty())
        assertTrue("and nothing was sent", graph.schedules.all().isEmpty())
    }

    /** Matrix row **"S72 out of range"** (I10): the same filter — digits only, three at most, ≤ 365. */
    @Test fun s72TakesDigitsOnlyCappedAt365() = runTest {
        val snowblower = anAsset("Snowblower", SeasonMode.CALENDAR, "11-01", "03-31")
        val vm = viewModel(targetAssetId = snowblower)
        vm.state.first { it.loaded }
        vm.onTitle("Belt check")
        vm.onInterval("3")
        vm.onPolicy(PolicyOption.WHEN_SEASON_STARTS)

        vm.onDaysAfter("000")
        assertEquals("000", vm.state.value.daysAfter)
        vm.onDaysAfter("0005")
        assertEquals("a fourth digit is not accepted", "000", vm.state.value.daysAfter)
        vm.onDaysAfter("366")
        vm.onDaysAfter("-3")
        vm.onDaysAfter("2.")
        assertEquals("000", vm.state.value.daysAfter)
        vm.onDaysAfter("365")
        assertEquals("365", vm.state.value.daysAfter)
        assertTrue("0 is legal here: the start itself", vm.state.value.copy(daysAfter = "0").canSave)

        vm.save()
        vm.state.first { !it.saving }
        val stored = graph.schedules.all().single()
        assertEquals(ServicePolicy.IN_SERVICE_AT_START, stored.servicePolicy)
        assertEquals(365, stored.policyOffsetDays)
    }

    /**
     * Matrix row **"a stored row misread"**: PRE_SERVICE −14 → the before-option with 14; AT_START 5 →
     * S67, S74, 5; RESUME_CLAMPED → S67, S75; CONTINUOUS → S68. Opening and saving each unchanged
     * writes back exactly what was stored.
     */
    @Test fun aStoredPolicyLoadsIntoItsOption() = runTest {
        val snowblower = anAsset("Snowblower", SeasonMode.CALENDAR, "11-01", "03-31")
        val generator = anAsset("Generator", breakStart = "12-20", breakEnd = "01-05")
        val hotTub = anAsset("Hot tub", SeasonMode.MANUAL, breakStart = "07-01", breakEnd = "07-15")

        data class Case(
            val asset: AssetId,
            val policy: ServicePolicy,
            val offset: Int?,
            val option: PolicyOption,
            val from: StartCountingFrom = StartCountingFrom.SEASON_START,
            val daysBefore: String = "",
            val daysAfter: String = "",
        )
        val cases = listOf(
            Case(snowblower, ServicePolicy.PRE_SERVICE, -14, PolicyOption.BEFORE_SEASON, daysBefore = "14"),
            Case(snowblower, ServicePolicy.IN_SERVICE_AT_START, 5, PolicyOption.WHEN_SEASON_STARTS, daysAfter = "5"),
            Case(
                snowblower, ServicePolicy.IN_SERVICE_RESUME_CLAMPED, null, PolicyOption.WHEN_SEASON_STARTS,
                from = StartCountingFrom.OWN_DATE,
            ),
            Case(snowblower, ServicePolicy.CONTINUOUS, null, PolicyOption.WHENEVER_DUE),
            Case(generator, ServicePolicy.PRE_SERVICE, -10, PolicyOption.BEFORE_BREAK, daysBefore = "10"),
            Case(generator, ServicePolicy.IN_SERVICE_AT_START, 0, PolicyOption.AFTER_BREAK),
            Case(hotTub, ServicePolicy.PRE_SERVICE, -3, PolicyOption.BEFORE_BREAK, daysBefore = "3"),
            Case(
                hotTub, ServicePolicy.IN_SERVICE_RESUME_CLAMPED, null, PolicyOption.WHEN_SEASON_STARTS,
                from = StartCountingFrom.OWN_DATE,
            ),
        )
        cases.forEach { case ->
            val id = aStoredSchedule(case.asset, case.policy, case.offset)
            val vm = viewModel(scheduleId = id)
            val opened = vm.state.first { it.loaded }
            val what = "${case.policy} ${case.offset}"
            assertEquals(what, case.option, opened.policyOption)
            assertEquals(what, case.from, opened.startCountingFrom)
            assertEquals(what, case.daysBefore, opened.daysBefore)
            assertEquals(what, case.daysAfter, opened.daysAfter)
            assertFalse(what, opened.noBoundary)

            vm.save()
            vm.state.first { !it.saving }
            val stored = graph.schedules.get(id)!!
            assertEquals("$what round trip", case.policy, stored.servicePolicy)
            assertEquals("$what round trip", case.offset, stored.policyOffsetDays)
        }
    }

    /**
     * A stored policy the asset's options cannot show, on an asset that **has** a boundary — RESUME_CLAMPED
     * or AT_START with an offset on a YEAR_ROUND asset with a break, reachable when an asset's season is
     * changed under its schedules. It is not guessed at: the question stands unanswered with the asset's
     * own options, Save waits for a pick, and S77 — which would be false here — is not shown.
     */
    @Test fun aStoredPolicyTheOptionsCannotShowIsLeftUnanswered() = runTest {
        val generator = anAsset("Generator", breakStart = "12-20", breakEnd = "01-05")
        listOf(ServicePolicy.IN_SERVICE_RESUME_CLAMPED to null, ServicePolicy.IN_SERVICE_AT_START to 5)
            .forEach { (policy, offset) ->
                val id = aStoredSchedule(generator, policy, offset)
                val vm = viewModel(scheduleId = id)
                val opened = vm.state.first { it.loaded }
                assertNull("$policy is not guessed at", opened.policyOption)
                assertFalse("S77 would be false on an asset with a break", opened.noBoundary)
                assertEquals(policyOptionsFor(yearRoundWithBreak, hasTimeRule = true), opened.policyOptions)
                assertFalse(opened.canSave)
                vm.save()
                testScheduler.advanceUntilIdle()
                assertEquals("nothing changed silently", policy, graph.schedules.get(id)!!.servicePolicy)
                assertEquals(offset, graph.schedules.get(id)!!.policyOffsetDays)
            }
    }

    /**
     * Matrix row **"the warnings"**, S76: shown when S67 is chosen on a CALENDAR asset and the anchor
     * lies outside the season **window** — not the break — and never a refusal.
     */
    @Test fun s76WhenTheAnchorIsOutsideTheSeason() = runTest {
        assertEquals(
            "The first due date is outside this asset's season, so it will wait for the season to start.",
            FIRST_DUE_OUTSIDE_THE_SEASON,
        )
        val whenStarts = { s: ScheduleEditState -> s.copy(policyOption = PolicyOption.WHEN_SEASON_STARTS) }

        val outside = form(calendarWithBreak, whenStarts, { it.copy(anchorOn = "2026-03-01") })
        assertTrue("March is outside May–September", outside.anchorOutsideSeason)
        assertTrue("a warning, never a refusal", outside.canSave)

        val inWindowAndBreak = form(calendarWithBreak, whenStarts, { it.copy(anchorOn = "2026-07-15") })
        assertFalse("July is in the season, whatever the break says", inWindowAndBreak.anchorOutsideSeason)

        assertFalse("only under S67", form(calendarWithBreak, { it.copy(anchorOn = "2026-03-01") }).anchorOutsideSeason)
        assertTrue(
            "S75 is under S67 too",
            form(calendar, whenStarts, { it.copy(anchorOn = "2026-03-01", startCountingFrom = StartCountingFrom.OWN_DATE) })
                .anchorOutsideSeason,
        )
        assertFalse("only on CALENDAR", form(manual, whenStarts, { it.copy(anchorOn = "2026-03-01") }).anchorOutsideSeason)
        assertFalse(
            "no time rule, no first due date",
            form(calendar, whenStarts, { it.copy(anchorOn = "2026-03-01", timeInterval = "") }).anchorOutsideSeason,
        )
    }

    /**
     * Matrix row **"the warnings"**, S84: shown when S74 is chosen on a CALENDAR asset and the season's
     * start plus the offset falls after the end of **that** season — the window, not the break — with a
     * wrapping season measured across the year end. Never a refusal.
     */
    @Test fun s84WhenTheOffsetPassesTheSeasonsEnd() = runTest {
        assertEquals("That is after the season ends, so this would never become due.", AFTER_THE_SEASON_ENDS)
        // May 1 – Sep 30 with a June break; on Apr 15 the offset counts from May 1, 2026.
        val june = season(SeasonMode.CALENDAR, "05-01", "09-30", "06-01", "06-30")
        val span = ChronoUnit.DAYS.between(LocalDate.parse("2026-05-01"), LocalDate.parse("2026-09-30")).toInt()
        val atStart = { days: Int ->
            form(june, { it.copy(policyOption = PolicyOption.WHEN_SEASON_STARTS, daysAfter = days.toString()) })
        }
        assertFalse("the last day of the season is still in it", atStart(span).offsetPassesSeasonEnd)
        assertTrue("the day after is not", atStart(span + 1).offsetPassesSeasonEnd)
        assertTrue("a warning, never a refusal", atStart(span + 1).canSave)
        assertFalse("inside the break is still inside the season", atStart(45).offsetPassesSeasonEnd)
        assertFalse(
            "only under S74",
            atStart(span + 1).copy(startCountingFrom = StartCountingFrom.OWN_DATE).offsetPassesSeasonEnd,
        )

        // A wrapping season, Oct 15 – Apr 15, measured across the year end, out of season and in it.
        val winter = season(SeasonMode.CALENDAR, "10-15", "04-15")
        val winterSpan = ChronoUnit.DAYS.between(LocalDate.parse("2026-10-15"), LocalDate.parse("2027-04-15")).toInt()
        listOf("2026-06-01", "2026-12-01").forEach { today ->
            val onDay = { days: Int ->
                form(
                    winter,
                    {
                        it.copy(
                            todayOn = LocalDate.parse(today),
                            policyOption = PolicyOption.WHEN_SEASON_STARTS,
                            daysAfter = days.toString(),
                        )
                    },
                )
            }
            assertFalse(today, onDay(winterSpan).offsetPassesSeasonEnd)
            assertTrue(today, onDay(winterSpan + 1).offsetPassesSeasonEnd)
        }
    }

    /**
     * Matrix row **"the merged state"** (master dec. 30): a stored non-CONTINUOUS policy on an asset with
     * neither a season nor a break shows S77 and the question with S68 alone, unanswered; Save waits
     * until the owner picks it, so nothing is changed silently. A `PreServiceNeedsDates` refusal on Save
     * — the asset lost its boundary while the form was open — shows S77 the same way.
     */
    @Test fun aStoredPolicyWithNoBoundaryShowsS77AndOnlyS68() = runTest {
        assertEquals(
            "This asset has no season or maintenance break to be ready before, so this is due whenever its date comes.",
            NOTHING_TO_BE_READY_BEFORE,
        )
        val mower = anAsset("Mower")
        val migrated = aStoredSchedule(mower, ServicePolicy.IN_SERVICE_AT_START, 0)
        val merged = aStoredSchedule(mower, ServicePolicy.CONTINUOUS, null).let { id ->
            graph.schedules.upsert(
                graph.schedules.get(id)!!.copy(servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14),
            )
            id
        }
        listOf(migrated to ServicePolicy.IN_SERVICE_AT_START, merged to ServicePolicy.PRE_SERVICE).forEach { (id, policy) ->
            val vm = viewModel(scheduleId = id)
            val opened = vm.state.first { it.loaded }
            assertTrue("$policy: S77", opened.noBoundary)
            assertTrue("$policy: the question is drawn", opened.questionDrawn)
            assertEquals(listOf(PolicyOption.WHENEVER_DUE), opened.policyOptions)
            assertNull("$policy: unanswered", opened.policyOption)
            assertFalse(opened.canSave)
            vm.save()
            testScheduler.advanceUntilIdle()
            assertEquals("$policy: nothing changed silently", policy, graph.schedules.get(id)!!.servicePolicy)

            vm.onPolicy(PolicyOption.WHEN_SEASON_STARTS)
            assertNull("S68 is the only answer", vm.state.value.policyOption)
            vm.onPolicy(PolicyOption.WHENEVER_DUE)
            vm.save()
            vm.state.first { !it.saving }
            assertEquals(ServicePolicy.CONTINUOUS, graph.schedules.get(id)!!.servicePolicy)
            assertNull(graph.schedules.get(id)!!.policyOffsetDays)
        }

        // The race: the asset loses its season while the form is open, and Save is refused 409.
        val snowblower = anAsset("Snowblower", SeasonMode.CALENDAR, "11-01", "03-31")
        val vm = viewModel(targetAssetId = snowblower)
        vm.state.first { it.loaded }
        vm.onTitle("Belt check")
        vm.onInterval("3")
        vm.onPolicy(PolicyOption.BEFORE_SEASON)
        vm.onDaysBefore("14")
        graph.assets.upsert(
            graph.assets.get(snowblower)!!.copy(
                seasonMode = SeasonMode.YEAR_ROUND,
                seasonStartMmdd = null,
                seasonEndMmdd = null,
            ),
        )
        vm.save()
        val raced = vm.state.first { !it.saving }
        assertTrue("the 409 takes the S77 path", raced.noBoundary)
        assertEquals(listOf(PolicyOption.WHENEVER_DUE), raced.policyOptions)
        assertNull(raced.policyOption)
        assertTrue("nothing was written", graph.schedules.all().none { it.target == ScheduleTarget.AssetTarget(snowblower) })
    }
}
