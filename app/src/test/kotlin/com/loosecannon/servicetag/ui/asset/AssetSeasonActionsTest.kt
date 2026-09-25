package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.usecase.ActivationCommand
import com.loosecannon.servicetag.core.usecase.SeasonView
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.ui.condition.displayDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Asset detail's **Start season** and **End season** (spec §3.3, §10.3; inv. 91, 93), and the
 * Season section's calendar line, against a Room-backed [FakeGraph] and the real
 * `RecordSeasonActivation`. `T` is 2026-06-15.
 *
 * What these hold: opening a dialog writes nothing; the confirm writes exactly one row, with the
 * dialog's own date and no event; a date outside `[latest row, today]` is S54 and writes nothing; a
 * row that raced the dialog is S56 or S57; Cancel writes nothing; and a MANUAL asset never has a
 * predicted start (Q-6, O-5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetSeasonActionsTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-06-15")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private val tub = AssetId("tub")

    private fun activation(id: String, action: SeasonAction, on: String, assetId: String = "tub") = SeasonActivation(
        id = id,
        assetId = AssetId(assetId),
        action = action,
        occurredOn = on,
        eventId = null,
        createdAt = dayMillis(on),
    )

    /** A MANUAL hot tub whose latest row is [latest]. */
    private suspend fun manualTub(vararg rows: SeasonActivation) {
        graph.assets.upsert(assetRow("tub", name = "Hot tub", seasonMode = SeasonMode.MANUAL))
        rows.forEach { graph.seasonActivations.insert(it) }
    }

    /** The detail model with its state and its one-shot lines collected, once it has loaded. */
    private suspend fun TestScope.detail(said: MutableList<String> = mutableListOf()): AssetDetailViewModel {
        val vm = AssetDetailViewModel(
            graph.assets, graph.tags,
            graph.definitions, graph.profiles, graph.events,
            graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
            graph.conditions, graph.seasonActivations, graph.healthSubjects,
            graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
            graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
            graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, tub,
        )
        backgroundScope.launch { vm.state.collect() }
        backgroundScope.launch { vm.messages.collect { said += it } }
        vm.state.first { it != null }
        return vm
    }

    private suspend fun rows(): List<SeasonActivation> = graph.seasonActivations.forAsset(tub)

    private fun AssetDetailViewModel.seasonPrompt(): DetailPrompt.SeasonChange? = prompt.value as? DetailPrompt.SeasonChange

    /**
     * Inv. 93: **Start** writes one START with the date the dialog holds — here backdated to the 10th —
     * and no event; **End** then writes one END on today's default. Opening a dialog writes nothing,
     * and only the action the section offers opens at all.
     */
    @Test fun startAndEndSendTheChosenDate() = runTest {
        manualTub(activation("a1", SeasonAction.END, "2026-05-01"))
        val vm = detail()

        vm.askSeason(SeasonAction.END)
        assertNull("an END is not offered while the season is ended", vm.prompt.value)

        vm.askSeason(SeasonAction.START)
        assertEquals(
            DetailPrompt.SeasonChange(SeasonAction.START, "2026-06-15", LocalDate.parse("2026-05-01"), LocalDate.parse("2026-06-15")),
            vm.prompt.value,
        )
        assertEquals("opening writes nothing", 1, rows().size)

        vm.onSeasonDate("2026-06-10")
        vm.confirmSeason()
        vm.prompt.first { it == null }
        val started = rows().single { it.id != "a1" }
        assertEquals(SeasonAction.START, started.action)
        assertEquals("2026-06-10", started.occurredOn)
        assertNull(started.eventId)

        vm.state.first { it?.season?.phase == com.loosecannon.servicetag.core.schedule.SeasonPhase.IN_SEASON }
        vm.askSeason(SeasonAction.END)
        assertEquals("2026-06-15", vm.seasonPrompt()?.date)
        assertEquals("the latest row bounds the range", LocalDate.parse("2026-06-10"), vm.seasonPrompt()?.from)
        vm.confirmSeason()
        vm.prompt.first { it == null }
        val ended = rows().single { it.id != "a1" && it.action == SeasonAction.END }
        assertEquals("2026-06-15", ended.occurredOn)
        assertNull(ended.eventId)
        assertEquals(3, rows().size)
        assertEquals(
            "S48 lists them newest first",
            listOf(SeasonAction.END, SeasonAction.START, SeasonAction.END),
            seasonHistory(vm.state.first { it?.season?.activations?.size == 3 }!!.season).map { it.action },
        )
    }

    /**
     * Spec §3.3: the date is bounded to `[latest row, today]`. Before the latest row, or after today,
     * the dialog says S54 — naming the latest row's day — and writes nothing; a latest row dated today
     * narrows the range to today alone.
     */
    @Test fun aDateOutsideTheRangeShowsS54AndWritesNothing() = runTest {
        manualTub(activation("a1", SeasonAction.END, "2026-05-01"))
        val vm = detail()
        val s54 = chooseADateFrom(displayDate(LocalDate.parse("2026-05-01")))
        assertTrue(s54.startsWith("Choose a date from ") && s54.endsWith(" to today."))

        vm.askSeason(SeasonAction.START)
        vm.onSeasonDate("2026-04-30")
        vm.confirmSeason()
        assertEquals(s54, vm.seasonPrompt()?.refusal)
        vm.onSeasonDate("2026-06-16")
        assertNull("editing the date clears the refusal", vm.seasonPrompt()?.refusal)
        vm.confirmSeason()
        assertEquals(s54, vm.seasonPrompt()?.refusal)
        scheduler.advanceUntilIdle()
        assertEquals("nothing was written", listOf("a1"), rows().map { it.id })

        // A START dated today narrows the End dialog's range to today alone.
        graph.recordSeasonActivation.run(tub, ActivationCommand(SeasonAction.START))
        vm.dismissPrompt()
        vm.state.first { it?.season?.activations?.size == 2 }
        vm.askSeason(SeasonAction.END)
        vm.onSeasonDate("2026-06-14")
        vm.confirmSeason()
        assertEquals(chooseADateFrom(displayDate(LocalDate.parse("2026-06-15"))), vm.seasonPrompt()?.refusal)
        scheduler.advanceUntilIdle()
        assertEquals(2, rows().size)
    }

    /**
     * A row that landed while the dialog was open — another START, another END — is refused by the
     * use case, and the screen says S56 or S57 once and closes, having written nothing of its own.
     */
    @Test fun aRaceShowsS56OrS57() = runTest {
        manualTub(activation("a1", SeasonAction.END, "2026-05-01"))
        val said = mutableListOf<String>()
        val vm = detail(said)

        vm.askSeason(SeasonAction.START)
        graph.recordSeasonActivation.run(tub, ActivationCommand(SeasonAction.START))
        vm.confirmSeason()
        vm.prompt.first { it == null }
        scheduler.advanceUntilIdle()
        assertEquals(listOf(THE_SEASON_IS_ALREADY_RUNNING), said)
        assertEquals("only the racing START", 2, rows().size)

        vm.state.first { it?.season?.activations?.size == 2 }
        vm.askSeason(SeasonAction.END)
        graph.recordSeasonActivation.run(tub, ActivationCommand(SeasonAction.END))
        vm.confirmSeason()
        vm.prompt.first { it == null }
        scheduler.advanceUntilIdle()
        assertEquals(listOf(THE_SEASON_IS_ALREADY_RUNNING, THE_SEASON_HAS_ALREADY_ENDED), said)
        assertEquals("only the racing END", 3, rows().size)
        assertEquals("The season is already running.", THE_SEASON_IS_ALREADY_RUNNING)
        assertEquals("The season has already ended.", THE_SEASON_HAS_ALREADY_ENDED)
    }

    /** Inv. 93: Cancel — with a date chosen or not — closes the dialog and writes nothing. */
    @Test fun cancelWritesNothing() = runTest {
        manualTub(activation("a1", SeasonAction.END, "2026-05-01"))
        val vm = detail()

        vm.askSeason(SeasonAction.START)
        vm.onSeasonDate("2026-06-01")
        vm.dismissPrompt()
        scheduler.advanceUntilIdle()

        assertNull(vm.prompt.value)
        assertEquals(listOf("a1"), rows().map { it.id })
    }

    /**
     * Spec §10.3, Q-6, O-5: the calendar line is S50 "Season ends <date>" in season and S49 "Next
     * season starts <date>" out of it, for a CALENDAR asset only — wrapping windows included. A
     * MANUAL asset has no such line either way, because its next START is never predicted; and the
     * rows a switch out of MANUAL left behind are no season history.
     */
    @Test fun s49OutOfSeasonS50InSeasonNothingForManual() = runTest {
        val mower = assetRow("mower", seasonMode = SeasonMode.CALENDAR, seasonStart = "05-01", seasonEnd = "09-30")
        val snow = assetRow("snow", seasonMode = SeasonMode.CALENDAR, seasonStart = "11-01", seasonEnd = "03-31")
        val iso: (LocalDate) -> String = { it.toString() }
        fun line(view: SeasonView) = calendarLine(view, iso)
        val june = LocalDate.parse("2026-06-15")
        val january = LocalDate.parse("2026-01-15")

        assertEquals("Season ends 2026-09-30", line(SeasonView.of(mower, emptyList(), june)))
        assertEquals("Next season starts 2027-05-01", line(SeasonView.of(mower, emptyList(), LocalDate.parse("2026-10-01"))))
        assertEquals("Next season starts 2026-05-01", line(SeasonView.of(mower, emptyList(), january)))
        assertEquals("Season ends 2026-03-31", line(SeasonView.of(snow, emptyList(), january)))
        assertEquals("Next season starts 2026-11-01", line(SeasonView.of(snow, emptyList(), june)))

        val tub = assetRow("tub", seasonMode = SeasonMode.MANUAL)
        val ended = listOf(activation("a1", SeasonAction.START, "2025-06-01"), activation("a2", SeasonAction.END, "2025-09-01"))
        assertNull("an ended MANUAL season predicts no start", line(SeasonView.of(tub, ended, june)))
        assertNull(line(SeasonView.of(tub, ended.take(1), june)))
        assertNull("year-round has no boundary", line(SeasonView.of(assetRow("gen"), emptyList(), june)))
        assertEquals(SeasonAction.START, manualAction(SeasonView.of(tub, ended, june)))
        assertEquals(SeasonAction.END, manualAction(SeasonView.of(tub, ended.take(1), june)))
        assertNull(manualAction(SeasonView.of(mower, emptyList(), june)))

        val leftManual = assetRow("left", seasonMode = SeasonMode.CALENDAR, seasonStart = "05-01", seasonEnd = "09-30")
        assertEquals("no history once it is not MANUAL", emptyList<SeasonActivation>(), seasonHistory(SeasonView.of(leftManual, ended, june)))
        assertEquals(listOf("a2", "a1"), seasonHistory(SeasonView.of(tub, ended, june)).map { it.id })
    }
}
