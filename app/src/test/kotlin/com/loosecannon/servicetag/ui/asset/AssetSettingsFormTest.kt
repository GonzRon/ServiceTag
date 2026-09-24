package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.usecase.SaveAssetSettings
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
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
 * The asset editor's season, break and health form (spec §10.4, §3.2, §3.4, §4.4, §6.5; strings
 * S28–S38, S55, S58–S64, S111–S136).
 *
 * What is proved here is the form and its one save: that nothing the owner must decide is decided for
 * them — S35 has no default (inv. 92), the break is off and empty unless stored (inv. 121), "One
 * subject" names no subject — that a half-filled or malformed window holds Save with a non-verbal mark
 * and never a new sentence (the controller's ruling on I10, plan-review F4), that one Save is one
 * [SaveAssetSettings] write carrying all four parts or none, and that a refused boundary change is said
 * by S55, S63 or S64. The screen's drawing of it is `AssetEditorSeasonAndHealthTest`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetSettingsFormTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    /** Every asset row `SaveAssetSettings` writes, in order: one Save must be one upsert of the whole row. */
    private lateinit var written: RecordingAssets
    private lateinit var saveSettings: SaveAssetSettings

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = java.time.LocalDate.parse("2026-09-24")
        written = RecordingAssets(graph.assets)
        saveSettings = SaveAssetSettings(
            written, graph.schedules, graph.healthSubjects, graph.seasonActivations, graph.uow, graph.ids,
            graph.clock, graph.todayPort, graph.recomputeSchedules, graph.applyTemplate,
        )
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private class RecordingAssets(private val inner: AssetRepository) : AssetRepository by inner {
        val upserts = mutableListOf<Asset>()
        override suspend fun upsert(asset: Asset) {
            upserts += asset
            inner.upsert(asset)
        }
    }

    /** Create ([id] null) or edit; suspends until the form has read the stored row and the picker. */
    private suspend fun form(id: String? = null): AssetEditViewModel {
        val model = AssetEditViewModel(graph.assets, graph.healthSubjects, saveSettings, id?.let(::AssetId))
        model.state.first { it.parentChoices.isNotEmpty() }
        return model
    }

    private suspend fun AssetEditViewModel.saveAndSettle() {
        save()
        state.first { !it.saving }
    }

    private suspend fun stored(id: String): Asset = graph.assets.get(AssetId(id))!!

    // ---------------------------------------------------------------------------------------------
    // S28: the season mode, and S35 only on a switch into MANUAL (inv. 92)
    // ---------------------------------------------------------------------------------------------

    @Test fun choosingManualAsksS35WithNoDefaultAndSaveWaits() = runTest {
        val vm = form()
        vm.onName("Hot tub")
        assertEquals(SeasonMode.YEAR_ROUND, vm.state.value.seasonMode)
        assertFalse(vm.state.value.asksManualPhase)

        vm.onSeasonMode(SeasonMode.MANUAL)
        val asked = vm.state.value
        assertTrue("S35 is asked on a switch into MANUAL", asked.asksManualPhase)
        assertNull("S35 has no default", asked.manualPhase)
        assertEquals("the unanswered question carries the asterisk", "$IS_THIS_ASSET_IN_SEASON *", asked.manualQuestionLabel)
        assertFalse("Save waits for S35", asked.canSave)

        vm.save()
        assertTrue("a held Save writes nothing", graph.assets.all().isEmpty())

        // Leaving S31 forgets the answer, so coming back asks again with no default.
        vm.onManualPhase(SeasonPhase.OUT_OF_SEASON)
        vm.onSeasonMode(SeasonMode.YEAR_ROUND)
        vm.onSeasonMode(SeasonMode.MANUAL)
        assertNull(vm.state.value.manualPhase)

        vm.onManualPhase(SeasonPhase.IN_SEASON)
        assertEquals(IS_THIS_ASSET_IN_SEASON, vm.state.value.manualQuestionLabel)
        assertTrue(vm.state.value.canSave)
        vm.saveAndSettle()

        val tub = graph.assets.all().single()
        assertEquals(SeasonMode.MANUAL, tub.seasonMode)
        assertEquals(
            "the answer became the one row the switch writes, dated today",
            listOf(SeasonAction.START to "2026-09-24"),
            graph.seasonActivations.forAsset(tub.id).map { it.action to it.occurredOn },
        )
    }

    @Test fun anAssetAlreadyManualSendsNoPhase() = runTest {
        graph.assets.upsert(assetRow("tub", name = "Hot tub", seasonMode = SeasonMode.MANUAL))
        val vm = form("tub")

        val loaded = vm.state.value
        assertEquals(SeasonMode.MANUAL, loaded.seasonMode)
        assertFalse("S35 is never asked of an asset already MANUAL", loaded.asksManualPhase)
        assertTrue(loaded.canSave)

        // Away and back is still MANUAL → MANUAL: no question, and no phase.
        vm.onSeasonMode(SeasonMode.CALENDAR)
        vm.onSeasonMode(SeasonMode.MANUAL)
        assertFalse(vm.state.value.asksManualPhase)

        vm.onName("Hot tub (deck)")
        vm.saveAndSettle()
        assertEquals("the save was accepted: no phase was sent", "Hot tub (deck)", stored("tub").name)
        assertEquals(SeasonMode.MANUAL, stored("tub").seasonMode)
        assertTrue("no activation is written when nothing switched", graph.seasonActivations.all().isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // One Save, one SaveAssetSettings write, all four parts or none
    // ---------------------------------------------------------------------------------------------

    @Test fun saveSendsOneSettingsCommandWithAllFourParts() = runTest {
        graph.assets.upsert(assetRow("mower", name = "Mower"))
        graph.healthSubjects.upsert(subjectRow("blade", "mower", name = "Blade"))
        val vm = form("mower")
        vm.state.first { it.subjects.isNotEmpty() }

        vm.onName("")
        vm.onSeasonMode(SeasonMode.CALENDAR)
        vm.onSeasonStart("04-01")
        vm.onSeasonEnd("10-31")
        vm.onBreak(true)
        vm.onBreakStart("07-01")
        vm.onBreakEnd("07-15")
        vm.onAggregation(HealthAggregation.TRACK_ONE)
        vm.onPrimary("blade")

        // The asset part is refused: none of the four is written, the season and break included.
        vm.saveAndSettle()
        assertEquals("Give the asset a name", vm.state.value.problems[AssetField.NAME])
        assertTrue("a refusal writes nothing", written.upserts.isEmpty())
        assertEquals(assetRow("mower", name = "Mower"), stored("mower"))

        vm.onName("Mower")
        vm.saveAndSettle()
        val row = written.upserts.single()
        assertEquals("Mower", row.name)
        assertEquals(SeasonMode.CALENDAR to ("04-01" to "10-31"), row.seasonMode to (row.seasonStartMmdd to row.seasonEndMmdd))
        assertEquals("07-01" to "07-15", row.blackoutStartMmdd to row.blackoutEndMmdd)
        assertEquals(HealthAggregation.TRACK_ONE to HealthSubjectId("blade"), row.healthAggregation to row.healthPrimarySubjectId)
        assertEquals(row, stored("mower"))
    }

    // ---------------------------------------------------------------------------------------------
    // S58: the break is never prefilled (inv. 121)
    // ---------------------------------------------------------------------------------------------

    @Test fun theBreakIsOffAndEmptyUnlessStored() = runTest {
        val fresh = form()
        assertFalse("a new asset has no break", fresh.state.value.breakOn)
        fresh.onBreak(true)
        assertEquals("turning it on shows both dates empty", "" to "", fresh.state.value.let { it.breakStart to it.breakEnd })
        assertFalse("and Save waits for them", fresh.state.value.canSave)

        graph.assets.upsert(assetRow("gen", name = "Generator"))
        assertFalse("an asset with no stored break opens with S59 off", form("gen").state.value.breakOn)

        graph.assets.upsert(assetRow("ups", name = "UPS", breakStart = "12-01", breakEnd = "02-28"))
        val withBreak = form("ups")
        assertTrue(withBreak.state.value.breakOn)
        assertEquals("12-01" to "02-28", withBreak.state.value.let { it.breakStart to it.breakEnd })

        // Off and on again: empty, never the stored dates coming back as a default.
        withBreak.onBreak(false)
        withBreak.onBreak(true)
        assertEquals("" to "", withBreak.state.value.let { it.breakStart to it.breakEnd })
    }

    // ---------------------------------------------------------------------------------------------
    // Refusals: S55, S64, S63
    // ---------------------------------------------------------------------------------------------

    @Test fun aStrandsRefusalShowsS55OrS64WithTitlesAndKeepsTheForm() = runTest {
        // A calendar season a pre-service schedule counts back from: leaving CALENDAR strands it.
        graph.assets.upsert(assetRow("snow", name = "Snowblower", seasonMode = SeasonMode.CALENDAR, seasonStart = "11-01", seasonEnd = "03-31"))
        graph.schedules.upsert(scheduleOf("s-carb", assetId = "snow", title = "Carburetor service", servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14))
        val season = form("snow")
        season.onSeasonMode(SeasonMode.YEAR_ROUND)
        season.saveAndSettle()
        assertEquals(
            "Some maintenance on this asset is set to be ready before its season. Change it first: Carburetor service.",
            season.state.value.seasonRefusal,
        )
        assertNull(season.state.value.breakRefusal)
        assertEquals("the form stays as typed", SeasonMode.YEAR_ROUND, season.state.value.seasonMode)
        assertEquals("nothing is written", SeasonMode.CALENDAR, stored("snow").seasonMode)

        // A break-only boundary two pre-service schedules count back from: removing it strands both.
        graph.assets.upsert(assetRow("gen", name = "Generator", breakStart = "12-01", breakEnd = "02-28"))
        graph.schedules.upsert(scheduleOf("s-oil", assetId = "gen", title = "Oil change", servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -7))
        graph.schedules.upsert(scheduleOf("s-load", assetId = "gen", title = "Load test", servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -3))
        val pause = form("gen")
        pause.onBreak(false)
        pause.saveAndSettle()
        assertEquals(
            "Some maintenance on this asset is set to be ready before the break. Change it first: Load test, Oil change.",
            pause.state.value.breakRefusal,
        )
        assertNull(pause.state.value.seasonRefusal)
        assertFalse("the form stays as typed", pause.state.value.breakOn)
        assertEquals("nothing is written", "12-01" to "02-28", stored("gen").let { it.blackoutStartMmdd to it.blackoutEndMmdd })
        assertTrue(written.upserts.isEmpty())
    }

    @Test fun aBreakCoveringTheYearShowsS63() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        val vm = form("ups")
        vm.onBreak(true)
        vm.onBreakStart("03-01")
        vm.onBreakEnd("02-28")
        assertTrue("two real month-days: Save is not held", vm.state.value.canSave)

        vm.saveAndSettle()
        assertEquals("The break cannot cover the whole year.", vm.state.value.breakRefusal)
        assertTrue(vm.state.value.breakOn)
        assertNull(stored("ups").blackoutStartMmdd)
        assertTrue(written.upserts.isEmpty())

        // Editing a date clears the line.
        vm.onBreakEnd("02-27")
        assertNull(vm.state.value.breakRefusal)
    }

    // ---------------------------------------------------------------------------------------------
    // S131 and S134
    // ---------------------------------------------------------------------------------------------

    @Test fun oneSubjectOffersOnlyNonArchivedSubjects() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.healthSubjects.upsert(subjectRow("battery", "ups", name = "Battery age", sortOrder = 0))
        graph.healthSubjects.upsert(subjectRow("fan", "ups", name = "Fan", sortOrder = 1, archivedAt = 5L))
        val vm = form("ups")
        val listed = vm.state.first { it.subjects.size == 2 }
        assertEquals(
            "S111 lists every subject in sortOrder, the archived one marked",
            listOf(SubjectRow("battery", "Battery age", false), SubjectRow("fan", "Fan", true)),
            listed.subjects,
        )

        vm.onAggregation(HealthAggregation.TRACK_ONE)
        assertEquals(listOf("battery"), vm.state.value.primaryChoices.map(SubjectRow::id))
        assertNull("One subject names none until one is chosen", vm.state.value.primaryId)
        assertEquals("$WHICH_SUBJECT *", vm.state.value.primaryQuestionLabel)
        assertFalse(vm.state.value.canSave)

        vm.onPrimary("fan")
        assertNull("an archived subject cannot be chosen", vm.state.value.primaryId)
        vm.onPrimary("battery")
        assertTrue(vm.state.value.canSave)
        vm.saveAndSettle()
        assertEquals(HealthSubjectId("battery"), stored("ups").healthPrimarySubjectId)

        // With no live subject at all, S134 is empty and Save stays held: HEALTH_PRIMARY_INVALID is unreachable.
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.healthSubjects.upsert(subjectRow("old", "gen", archivedAt = 5L))
        val bare = form("gen")
        bare.state.first { it.subjects.isNotEmpty() }
        bare.onAggregation(HealthAggregation.TRACK_ONE)
        assertTrue(bare.state.value.primaryChoices.isEmpty())
        assertFalse(bare.state.value.canSave)
    }

    /** The carry-forward from B06's review: a dangling TRACK_ONE primary is never re-sent. */
    @Test fun aDanglingPrimaryIsNotResent() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS", aggregation = HealthAggregation.TRACK_ONE, primary = "gone"))
        graph.healthSubjects.upsert(subjectRow("gone", "ups", name = "Old battery", archivedAt = 5L))
        graph.healthSubjects.upsert(subjectRow("battery", "ups", name = "Battery age", sortOrder = 1))
        val vm = form("ups")

        val loaded = vm.state.value
        assertEquals("read as the engine reads it", HealthAggregation.WORST, loaded.aggregation)
        assertNull(loaded.primaryId)
        assertTrue(loaded.canSave)

        vm.onName("Rack UPS")
        vm.saveAndSettle()
        val row = stored("ups")
        assertEquals("a rename is not refused for the dangling primary", "Rack UPS", row.name)
        assertEquals(HealthAggregation.WORST to null, row.healthAggregation to row.healthPrimarySubjectId)
    }

    // ---------------------------------------------------------------------------------------------
    // I10: a half-filled or malformed window holds Save, with no new sentence
    // ---------------------------------------------------------------------------------------------

    @Test fun calendarWithOneDateDisablesSaveWithNoSentence() = runTest {
        val vm = form()
        vm.onName("Mower")
        vm.onSeasonMode(SeasonMode.CALENDAR)
        vm.onSeasonStart("05-01")

        val half = vm.state.value
        assertFalse(half.canSave)
        assertEquals("both are marked required", "$SEASON_STARTS *" to "$SEASON_ENDS *", half.seasonStartInput.drawnLabel to half.seasonEndInput.drawnLabel)
        assertNull(half.seasonStartInput.problem)
        assertNull(half.seasonEndInput.problem)
        assertFalse(half.seasonEndInput.outlined)

        vm.save()
        vm.state.first { !it.saving }
        assertTrue("a held Save writes nothing", graph.assets.all().isEmpty())
        assertTrue("and draws nothing", vm.state.value.problems.isEmpty())
        assertNull(vm.state.value.seasonRefusal)

        vm.onSeasonEnd("09-30")
        assertTrue(vm.state.value.canSave)
        assertEquals(SEASON_STARTS, vm.state.value.seasonStartInput.drawnLabel)
        vm.saveAndSettle()
        assertEquals("05-01" to "09-30", graph.assets.all().single().let { it.seasonStartMmdd to it.seasonEndMmdd })
    }

    @Test fun aHalfFilledBreakDisablesSave() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        val vm = form("gen")
        vm.onBreak(true)
        vm.onBreakStart("12-01")

        val half = vm.state.value
        assertFalse(half.canSave)
        assertEquals("$BREAK_STARTS *" to "$BREAK_ENDS *", half.breakStartInput.drawnLabel to half.breakEndInput.drawnLabel)
        assertNull(half.breakStartInput.problem)
        assertNull(half.breakEndInput.problem)

        vm.save()
        vm.state.first { !it.saving }
        assertTrue(written.upserts.isEmpty())
        assertNull(vm.state.value.breakRefusal)

        vm.onBreakEnd("02-28")
        assertTrue(vm.state.value.canSave)
    }

    @Test fun aMalformedMonthDayShowsTheShippedSentence() = runTest {
        val vm = form()
        vm.onName("Snowblower")
        vm.onSeasonMode(SeasonMode.CALENDAR)
        vm.onSeasonStart("13-40")
        vm.onSeasonEnd("03-31")
        vm.onBreak(true)
        vm.onBreakStart("12-01")
        vm.onBreakEnd("02-30")

        val typed = vm.state.value
        assertEquals("Not a real month and day", typed.seasonStartInput.problem)
        assertTrue(typed.seasonStartInput.outlined)
        assertNull(typed.seasonEndInput.problem)
        assertEquals("Not a real month and day", typed.breakEndInput.problem)
        assertNull(typed.breakStartInput.problem)
        assertFalse(typed.canSave)

        vm.onSeasonStart("11-01")
        vm.onBreakEnd("02-28")
        assertNull(vm.state.value.seasonStartInput.problem)
        assertTrue(vm.state.value.canSave)
    }

    /**
     * Plan-review F4: the required mark is an asterisk or an outline, never a word. Every line the
     * form's season, break and policy state can put on screen, walked through each state that holds
     * Save, is a ratified string, a ratified string with the asterisk, or the shipped month-day line.
     */
    @Test fun theRequiredMarkIsNonVerbal() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.healthSubjects.upsert(subjectRow("battery", "ups", name = "Battery age", driver = HealthDriver.AGE))
        val vm = form("ups")
        vm.state.first { it.subjects.isNotEmpty() }

        val ratified = listOf(
            SEASON_STARTS, SEASON_ENDS, BREAK_STARTS, BREAK_ENDS, IS_THIS_ASSET_IN_SEASON, WHICH_SUBJECT,
        )
        val allowed = ratified.toSet() + ratified.map { "$it *" } + NOT_A_REAL_MONTH_AND_DAY
        val seen = mutableSetOf<String>()
        fun look() {
            val s = vm.state.value
            listOf(s.seasonStartInput, s.seasonEndInput, s.breakStartInput, s.breakEndInput).forEach { input ->
                seen += input.drawnLabel
                input.problem?.let { seen += it }
            }
            seen += s.manualQuestionLabel
            seen += s.primaryQuestionLabel
            seen += s.problems.values
            listOfNotNull(s.seasonRefusal, s.breakRefusal).forEach { seen += it }
        }

        look()
        vm.onSeasonMode(SeasonMode.CALENDAR); look()
        vm.onSeasonStart("05-01"); look()
        vm.onSeasonStart("5-1"); look()
        vm.onSeasonMode(SeasonMode.MANUAL); look()
        vm.onBreak(true); look()
        vm.onBreakEnd("02-28"); look()
        vm.onBreakEnd("02-30"); look()
        vm.onAggregation(HealthAggregation.TRACK_ONE); look()
        vm.save(); vm.state.first { !it.saving }; look()

        assertTrue("every line is ratified or shipped: ${seen - allowed}", allowed.containsAll(seen))
        assertTrue("the asterisk was drawn", "$SEASON_ENDS *" in seen && "$BREAK_STARTS *" in seen)
        assertFalse(seen.any { it.contains("equired") })
        assertEquals("the mark is an asterisk on the ratified label", "$SEASON_STARTS *", requiredMark(SEASON_STARTS))
    }
}
