package com.loosecannon.servicetag.ui.health

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.meterDefinitionOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import com.loosecannon.servicetag.ui.asset.ADD_HEALTH_SUBJECT
import com.loosecannon.servicetag.ui.maintenance.THE_SUBJECT_HEALTH_FOLLOWS
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The health subject editor's form (spec §10.4, §6.1, §6.3, §6.5; strings S113–S136, S137 used).
 *
 * What is proved here: the three thresholds start **empty** and a starting point fills them only
 * after S130 — never on the S128 tap, never for an age subject (Q-1, inv. 121); the two pickers offer
 * exactly the links the subject command accepts (archived schedules excluded, dec. 45); the filters and
 * the stepper make every numeric refusal unreachable without a sentence (master dec. 46); S135 and
 * S137 answer their refusals; Restore is held while its link would be refused; and a link broken by
 * another writer between the check and the tap reloads the pickers silently (the race rule).
 *
 * The race is real, not faked: the store is changed behind the model's back — the way an API or MCP
 * write would change it — so the production use case gives the production refusal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthSubjectEditViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private suspend fun editor(assetId: String, subjectId: String? = null): HealthSubjectEditViewModel {
        val model = HealthSubjectEditViewModel(
            graph.assets, graph.healthSubjects, graph.schedules, graph.profiles,
            graph.saveHealthSubject, graph.archiveHealthSubject,
            AssetId(assetId), subjectId?.let(::HealthSubjectId),
        )
        model.state.first { it.loaded }
        return model
    }

    private suspend fun HealthSubjectEditViewModel.settle() = state.first { !it.saving }

    private fun profileOf(
        id: String,
        assetId: String,
        name: String,
        kind: EventKind = EventKind.REPLACEMENT,
        sortOrder: Int = 0,
        archivedAt: Long? = null,
    ) = EventProfile(
        id = ProfileId(id),
        assetId = AssetId(assetId),
        name = name,
        eventKind = kind,
        defaultTitle = name,
        templateKey = null,
        sortOrder = sortOrder,
        archivedAt = archivedAt,
        createdAt = 1L,
        updatedAt = 1L,
        fields = emptyList(),
        consumables = emptyList(),
    )

    /** A generator with one timed schedule, and an editor filled up to the thresholds. */
    private suspend fun overdueForm(): HealthSubjectEditViewModel {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(scheduleOf("s-oil", assetId = "gen", title = "Oil change"))
        val vm = editor("gen")
        vm.onName("Engine")
        vm.onKind(HealthSubjectKind.ASSET)
        vm.onDriver(HealthDriver.MAINTENANCE_OVERDUE)
        vm.onSchedule(ScheduleId("s-oil"))
        return vm
    }

    private suspend fun subjects(assetId: String): List<HealthSubject> = graph.healthSubjects.forAsset(AssetId(assetId))

    // ---------------------------------------------------------------------------------------------
    // Thresholds and starting points (Q-1, inv. 121)
    // ---------------------------------------------------------------------------------------------

    @Test fun thresholdsStartEmptyAndSaveWaitsForAllThree() = runTest {
        val vm = overdueForm()
        val fresh = vm.state.value
        assertEquals("the three thresholds start empty", listOf("", "", ""), fresh.thresholds)
        assertTrue("S126 is drawn", fresh.thresholdsMissing)
        assertEquals(
            listOf("Grace period (days overdue)", "Warning at (days overdue)", "Critical at (days overdue)"),
            fresh.thresholdLabels,
        )
        assertFalse(fresh.canSave)

        vm.onThreshold(0, "0")
        vm.onThreshold(1, "30")
        assertFalse("two of three is not enough", vm.state.value.canSave)
        vm.save()
        assertTrue(subjects("gen").isEmpty())

        vm.onThreshold(2, "90")
        assertFalse(vm.state.value.thresholdsMissing)
        assertTrue(vm.state.value.canSave)
        vm.save()
        vm.settle()
        val saved = subjects("gen").single()
        assertEquals(Triple(0, 30, 90), Triple(saved.nominalUntilDays, saved.warningFromDays, saved.criticalFromDays))
        assertEquals(ScheduleId("s-oil"), saved.scheduleId)
    }

    @Test fun aStartingPointFillsOnlyAfterS130() = runTest {
        val vm = overdueForm()
        assertTrue(vm.state.value.startingPointsOffered)
        assertEquals(
            "S128's two names, split only at the separator",
            listOf("Engine service: 14 / 45 / 120 days overdue", "Water care: 2 / 7 / 14 days overdue"),
            StartingPoint.entries.map(StartingPoint::label),
        )

        vm.chooseStartingPoint(StartingPoint.ENGINE_SERVICE)
        assertEquals(StartingPoint.ENGINE_SERVICE, vm.state.value.confirming)
        assertEquals("the S128 tap fills nothing", listOf("", "", ""), vm.state.value.thresholds)

        vm.confirmStartingPoint()
        assertNull(vm.state.value.confirming)
        assertEquals(listOf("14", "45", "120"), vm.state.value.thresholds)
    }

    @Test fun cancelFillsNothing() = runTest {
        val vm = overdueForm()
        vm.onThreshold(0, "3")
        vm.chooseStartingPoint(StartingPoint.WATER_CARE)
        vm.cancelStartingPoint()
        assertNull(vm.state.value.confirming)
        assertEquals("Cancel leaves the fields as they were", listOf("3", "", ""), vm.state.value.thresholds)
        vm.confirmStartingPoint()
        assertEquals("S130 after Cancel has nothing to apply", listOf("3", "", ""), vm.state.value.thresholds)
    }

    @Test fun ageOffersNoStartingPoint() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        val vm = editor("ups")
        vm.onDriver(HealthDriver.AGE)
        assertFalse("AGE has no starting point (spec §6.3)", vm.state.value.startingPointsOffered)
        assertEquals(
            listOf("As new for (days)", "Warning after (days)", "Critical after (days)"),
            vm.state.value.thresholdLabels,
        )
        vm.chooseStartingPoint(StartingPoint.ENGINE_SERVICE)
        assertNull(vm.state.value.confirming)
        vm.confirmStartingPoint()
        assertEquals(listOf("", "", ""), vm.state.value.thresholds)
    }

    // ---------------------------------------------------------------------------------------------
    // The pickers
    // ---------------------------------------------------------------------------------------------

    @Test fun ageListsOnlyReplacementQuickActionsWithAnyReplacement() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack"))
        graph.profiles.upsert(profileOf("p-load", "ups", "Load test", kind = EventKind.MAINTENANCE, sortOrder = 0))
        graph.profiles.upsert(profileOf("p-batt", "ups", "Battery replacement", sortOrder = 1))
        graph.profiles.upsert(profileOf("p-old", "ups", "Old replacement", sortOrder = 2, archivedAt = 5L))
        graph.profiles.upsert(profileOf("p-other", "pack", "Cell replacement"))
        val vm = editor("ups")
        vm.onDriver(HealthDriver.AGE)

        val options = vm.state.value.baselineOptions
        assertEquals(listOf(ANY_REPLACEMENT, "Battery replacement"), options.map { it.label })
        assertEquals("S121 is the new subject's answer", Baseline.AnyReplacement, vm.state.value.baseline)

        vm.onBaseline(Baseline.Action(ProfileId("p-load")))
        assertEquals("a quick action that is not a replacement cannot be chosen", Baseline.AnyReplacement, vm.state.value.baseline)
        vm.onBaseline(Baseline.Action(ProfileId("p-batt")))
        assertEquals(Baseline.Action(ProfileId("p-batt")), vm.state.value.baseline)

        // An asset with no replacement quick action offers S121 alone.
        graph.assets.upsert(assetRow("mow", name = "Mower"))
        val bare = editor("mow")
        bare.onDriver(HealthDriver.AGE)
        assertEquals(listOf(ANY_REPLACEMENT), bare.state.value.baselineOptions.map { it.label })
    }

    @Test fun overdueListsOnlyThisAssetsTimedSchedules() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.assets.upsert(assetRow("mow", name = "Mower"))
        graph.definitions.upsert(meterDefinitionOf("d-hours", assetId = "gen"))
        graph.groups.upsert(groupOf("g1", name = "Engines", members = listOf(Triple("gen", "2026-01-01", null))))
        graph.schedules.upsert(scheduleOf("s-oil", assetId = "gen", title = "Oil change"))
        graph.schedules.upsert(
            scheduleOf(
                "s-hours", assetId = "gen", title = "Air filter",
                timeInterval = null, timeUnit = null, anchorOn = null,
                meterDefinitionId = "d-hours", meterInterval = 100.0, anchorMeter = 0.0,
            ),
        )
        graph.schedules.upsert(scheduleOf("s-blade", assetId = "mow", title = "Blade sharpening"))
        graph.schedules.upsert(scheduleOf("s-group", groupId = "g1", title = "Fleet inspection"))
        val vm = editor("gen")
        vm.onDriver(HealthDriver.MAINTENANCE_OVERDUE)

        assertEquals(listOf("Oil change"), vm.state.value.timedSchedules.map { it.label })
        vm.onSchedule(ScheduleId("s-hours"))
        assertNull("a meter-only schedule cannot be chosen", vm.state.value.scheduleId)
        assertEquals("$MAINTENANCE_SCHEDULE *", vm.state.value.scheduleLabel)

        // An asset with no timed schedule of its own offers an empty S122, and Save stays held.
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        val empty = editor("ups")
        empty.onName("Battery")
        empty.onKind(HealthSubjectKind.PART)
        empty.onDriver(HealthDriver.MAINTENANCE_OVERDUE)
        empty.onThreshold(0, "1"); empty.onThreshold(1, "2"); empty.onThreshold(2, "3")
        assertTrue(empty.state.value.timedSchedules.isEmpty())
        assertFalse(empty.state.value.canSave)
    }

    @Test fun s122ExcludesArchivedSchedules() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(scheduleOf("s-oil", assetId = "gen", title = "Oil change"))
        graph.schedules.upsert(scheduleOf("s-paused", assetId = "gen", title = "Coolant", status = ScheduleStatus.PAUSED))
        graph.schedules.upsert(scheduleOf("s-old", assetId = "gen", title = "Belt", status = ScheduleStatus.ARCHIVED))
        val vm = editor("gen")
        vm.onDriver(HealthDriver.MAINTENANCE_OVERDUE)
        assertEquals(
            "archived excluded (dec. 45); a paused schedule still has its rule",
            listOf("Coolant", "Oil change"),
            vm.state.value.timedSchedules.map { it.label },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Order, weight, the numeric filters (I10)
    // ---------------------------------------------------------------------------------------------

    @Test fun aNonIncreasingOrderShowsS125() = runTest {
        val vm = overdueForm()
        vm.onThreshold(0, "10")
        vm.onThreshold(1, "10")
        vm.commitThresholds()
        assertTrue("equal is not larger", vm.state.value.orderRefused)
        vm.onThreshold(2, "20")
        assertFalse("an edit waits for the next commit", vm.state.value.orderRefused)

        // A Save may be tried with numbers that do not rise: S125 answers it and nothing is written.
        assertTrue(vm.state.value.canSave)
        vm.save()
        assertTrue("the try is answered by S125", vm.state.value.orderRefused)
        assertTrue(subjects("gen").isEmpty())

        vm.onThreshold(1, "5")
        vm.commitThresholds()
        assertTrue("a fall between any two is S125", vm.state.value.orderRefused)
        vm.onThreshold(0, "0")
        vm.commitThresholds()
        assertFalse("0 < 5 < 20, and t1 = 0 is legal", vm.state.value.orderRefused)
        vm.save()
        vm.settle()
        assertEquals(listOf(Triple(0, 5, 20)), subjects("gen").map { Triple(it.nominalUntilDays, it.warningFromDays, it.criticalFromDays) })
    }

    /** The controller's ruling on B10-M3: S125 is judged on leaving a field or trying Save, never per keystroke. */
    @Test fun s125WaitsForACommitOrASave() = runTest {
        val vm = overdueForm()
        vm.onThreshold(0, "14")
        vm.commitThresholds()
        // 45 and 120 typed digit by digit pass through 4, 1 and 12, which do not rise: nothing is drawn.
        listOf("4", "45").forEach { vm.onThreshold(1, it); assertFalse("typing $it", vm.state.value.orderRefused) }
        vm.commitThresholds()
        listOf("1", "12").forEach { vm.onThreshold(2, it); assertFalse("typing $it", vm.state.value.orderRefused) }

        // Left at 12, the field commits, and 14 / 45 / 12 is S125.
        vm.commitThresholds()
        assertTrue(vm.state.value.orderRefused)
        vm.onThreshold(2, "120")
        vm.commitThresholds()
        assertFalse(vm.state.value.orderRefused)
    }

    /** The controller's ruling on B10-M2: a starting point's numbers belong to overdue maintenance. */
    @Test fun aStartingPointIsClearedWhenTheDriverSwitchesToAge() = runTest {
        val vm = overdueForm()
        vm.chooseStartingPoint(StartingPoint.ENGINE_SERVICE)
        vm.confirmStartingPoint()
        assertEquals(listOf("14", "45", "120"), vm.state.value.thresholds)

        vm.onDriver(HealthDriver.AGE)
        assertEquals("age has no starting point, so its numbers go", listOf("", "", ""), vm.state.value.thresholds)
        vm.onDriver(HealthDriver.MAINTENANCE_OVERDUE)
        assertEquals("and they do not come back", listOf("", "", ""), vm.state.value.thresholds)

        // Once the owner edits them, the numbers are the owner's own and stay.
        vm.chooseStartingPoint(StartingPoint.WATER_CARE)
        vm.confirmStartingPoint()
        vm.onThreshold(2, "21")
        vm.onDriver(HealthDriver.AGE)
        assertEquals(listOf("2", "7", "21"), vm.state.value.thresholds)

        // Typed by hand, they stay too.
        val typed = overdueForm()
        typed.onThreshold(0, "1"); typed.onThreshold(1, "2"); typed.onThreshold(2, "3")
        typed.onDriver(HealthDriver.AGE)
        assertEquals(listOf("1", "2", "3"), typed.state.value.thresholds)
    }

    @Test fun weightOnlyUnderWeightedAverage() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.healthSubjects.upsert(subjectRow("battery", "ups", name = "Battery age", weight = 7))
        val hidden = editor("ups", "battery")
        assertFalse("S133 is not drawn under Worst subject", hidden.state.value.weighted)
        assertEquals("a hidden weight keeps its stored value", 7, hidden.state.value.weight)
        hidden.onName("Battery")
        hidden.save()
        hidden.settle()
        assertEquals(7, graph.healthSubjects.get(HealthSubjectId("battery"))!!.weight)

        graph.assets.upsert(assetRow("pack", name = "Battery pack", aggregation = HealthAggregation.WEIGHTED))
        assertTrue(editor("pack").state.value.weighted)
    }

    @Test fun thresholdsTakeDigitsCappedAt36500() = runTest {
        val vm = overdueForm()
        vm.onThreshold(2, "36500")
        assertEquals("36500", vm.state.value.criticalFrom)
        vm.onThreshold(2, "36501")
        assertEquals("a keystroke past the cap is not taken", "36500", vm.state.value.criticalFrom)
        vm.onThreshold(0, "12a")
        vm.onThreshold(0, "-1")
        vm.onThreshold(0, "1.5")
        assertEquals("digits only", "", vm.state.value.nominalUntil)
        vm.onThreshold(0, "0")
        assertEquals("0", vm.state.value.nominalUntil)
        vm.onThreshold(0, "")
        assertEquals("clearing is allowed", "", vm.state.value.nominalUntil)
        assertTrue(acceptsThresholdDays("36500"))
        assertFalse(acceptsThresholdDays("100000"))
    }

    @Test fun weightIsAStepperFrom1To10() = runTest {
        graph.assets.upsert(assetRow("pack", name = "Battery pack", aggregation = HealthAggregation.WEIGHTED))
        val vm = editor("pack")
        assertEquals(1, vm.state.value.weight)
        vm.onWeightStep(-1)
        assertEquals("never below 1", 1, vm.state.value.weight)
        repeat(12) { vm.onWeightStep(1) }
        assertEquals("never above 10", 10, vm.state.value.weight)
        vm.onWeightStep(-1)
        assertEquals(9, vm.state.value.weight)
    }

    @Test fun theNameStopsAt60Characters() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        val vm = editor("ups")
        vm.onName("x".repeat(61))
        assertEquals(60, vm.state.value.name.length)
        vm.onName("")
        assertEquals("the unanswered name carries the asterisk", "Name *", vm.state.value.nameLabel)
        assertFalse(vm.state.value.canSave)
    }

    // ---------------------------------------------------------------------------------------------
    // Refusals with words: S135, S137
    // ---------------------------------------------------------------------------------------------

    @Test fun aTakenScheduleShowsS135() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(scheduleOf("s-oil", assetId = "gen", title = "Oil change"))
        graph.healthSubjects.upsert(
            subjectRow("engine", "gen", name = "Engine", driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = "s-oil"),
        )
        val vm = editor("gen")
        vm.onName("Engine again")
        vm.onKind(HealthSubjectKind.ASSET)
        vm.onDriver(HealthDriver.MAINTENANCE_OVERDUE)
        vm.onSchedule(ScheduleId("s-oil"))
        vm.onThreshold(0, "1"); vm.onThreshold(1, "2"); vm.onThreshold(2, "3")
        vm.save()
        vm.settle()
        assertTrue("S135", vm.state.value.scheduleTaken)
        assertEquals(listOf("engine"), subjects("gen").map { it.id.value })
        // S135 is about S122's schedule, so it goes when the driver no longer has one.
        vm.onDriver(HealthDriver.AGE)
        assertFalse("S135 goes with the switch to age", vm.state.value.scheduleTaken)

        // A restore refused because the schedule is taken still answers S135 and stays archived.
        graph.healthSubjects.upsert(
            subjectRow(
                "twin", "gen", name = "Twin", driver = HealthDriver.MAINTENANCE_OVERDUE,
                scheduleId = "s-oil", sortOrder = 1, archivedAt = 5L,
            ),
        )
        val twin = editor("gen", "twin")
        assertTrue("the link itself is valid, so Restore is offered", twin.state.value.restoreAllowed)
        twin.restore()
        twin.settle()
        assertTrue(twin.state.value.scheduleTaken)
        assertNotNull(graph.healthSubjects.get(HealthSubjectId("twin"))!!.archivedAt)
    }

    @Test fun archivingThePrimaryShowsS137() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS", aggregation = HealthAggregation.TRACK_ONE, primary = "battery"))
        graph.healthSubjects.upsert(subjectRow("battery", "ups", name = "Battery age"))
        val vm = editor("ups", "battery")
        vm.archive()
        vm.settle()
        assertTrue("S137 is drawn", vm.state.value.primaryRefused)
        assertEquals(
            "B08's constant is the ratified S137",
            "This is the subject asset health follows. Choose another way to combine health first.",
            THE_SUBJECT_HEALTH_FOLLOWS,
        )
        assertNull(graph.healthSubjects.get(HealthSubjectId("battery"))!!.archivedAt)
        assertFalse(vm.state.value.archived)
        vm.dismissPrimaryRefusal()
        assertFalse(vm.state.value.primaryRefused)
    }

    @Test fun archiveThenRestoreFlipsTheAction() = runTest {
        graph.assets.upsert(assetRow("ups", name = "UPS"))
        graph.healthSubjects.upsert(subjectRow("battery", "ups", name = "Battery age"))
        val vm = editor("ups", "battery")
        assertTrue(vm.state.value.editing)
        assertFalse(vm.state.value.archived)

        vm.archive()
        vm.state.first { it.archived }
        assertNotNull(graph.healthSubjects.get(HealthSubjectId("battery"))!!.archivedAt)
        assertTrue(vm.state.value.restoreAllowed)

        vm.restore()
        vm.state.first { !it.archived }
        assertNull(graph.healthSubjects.get(HealthSubjectId("battery"))!!.archivedAt)
        assertEquals("S112 is the asset side's word for a new one", "Add health subject", ADD_HEALTH_SUBJECT)
    }

    // ---------------------------------------------------------------------------------------------
    // Restore and the race rule (dec. 45; the plan-review follow-up's F5)
    // ---------------------------------------------------------------------------------------------

    @Test fun restoreIsDisabledWhileTheLinkWouldBeRefused() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(scheduleOf("s-old", assetId = "gen", title = "Belt", status = ScheduleStatus.ARCHIVED))
        graph.schedules.upsert(scheduleOf("s-oil", assetId = "gen", title = "Oil change"))
        graph.healthSubjects.upsert(
            subjectRow(
                "belt", "gen", name = "Belt wear", driver = HealthDriver.MAINTENANCE_OVERDUE,
                scheduleId = "s-old", archivedAt = 5L,
            ),
        )
        val vm = editor("gen", "belt")
        assertTrue(vm.state.value.archived)
        assertFalse("its schedule is archived: Restore is held", vm.state.value.restoreAllowed)
        assertNull("the archived schedule is not an answer S122 offers", vm.state.value.scheduleId)
        vm.restore()
        vm.settle()
        assertNotNull("nothing was written", graph.healthSubjects.get(HealthSubjectId("belt"))!!.archivedAt)

        // Edited onto a valid schedule and saved, it can then be restored.
        vm.onSchedule(ScheduleId("s-oil"))
        vm.save()
        vm.settle()
        val reopened = editor("gen", "belt")
        assertTrue(reopened.state.value.restoreAllowed)
    }

    @Test fun aForeignOrRuleLessAnswerReloadsThePickerAndRestoreStateAndDrawsNothing() = runTest {
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(scheduleOf("s-oil", assetId = "gen", title = "Oil change"))
        graph.schedules.upsert(scheduleOf("s-load", assetId = "gen", title = "Load test"))
        graph.schedules.upsert(scheduleOf("s-belt", assetId = "gen", title = "Belt check"))
        val vm = editor("gen")
        vm.onName("Engine")
        vm.onKind(HealthSubjectKind.ASSET)
        vm.onDriver(HealthDriver.MAINTENANCE_OVERDUE)
        vm.onThreshold(0, "1"); vm.onThreshold(1, "2"); vm.onThreshold(2, "3")
        assertEquals(listOf("Belt check", "Load test", "Oil change"), vm.state.value.timedSchedules.map { it.label })

        // FOREIGN_SCHEDULE: archived by another writer after the picker was read.
        vm.onSchedule(ScheduleId("s-oil"))
        graph.schedules.upsert(scheduleOf("s-oil", assetId = "gen", title = "Oil change", status = ScheduleStatus.ARCHIVED))
        vm.save()
        val afterForeign = vm.settle()
        assertTrue("nothing is written", subjects("gen").isEmpty())
        assertEquals("the picker was re-read", listOf("Belt check", "Load test"), afterForeign.timedSchedules.map { it.label })
        assertNull("the answer it took away is let go", afterForeign.scheduleId)
        assertFalse("no sentence", afterForeign.scheduleTaken || afterForeign.primaryRefused)

        // HEALTH_SCHEDULE_NEEDS_A_TIME_RULE: de-ruled by another writer.
        vm.onSchedule(ScheduleId("s-load"))
        graph.schedules.upsert(
            scheduleOf("s-load", assetId = "gen", title = "Load test", timeInterval = null, timeUnit = null, anchorOn = null),
        )
        vm.save()
        val afterRuleLess = vm.settle()
        assertTrue(subjects("gen").isEmpty())
        assertEquals(listOf("Belt check"), afterRuleLess.timedSchedules.map { it.label })
        assertNull(afterRuleLess.scheduleId)
        assertFalse(afterRuleLess.scheduleTaken || afterRuleLess.primaryRefused)

        // The Restore half: offered when read, then the link breaks before the tap.
        graph.healthSubjects.upsert(
            subjectRow(
                "belt", "gen", name = "Belt wear", driver = HealthDriver.MAINTENANCE_OVERDUE,
                scheduleId = "s-belt", archivedAt = 5L,
            ),
        )
        val archived = editor("gen", "belt")
        assertTrue(archived.state.value.restoreAllowed)
        graph.schedules.upsert(scheduleOf("s-belt", assetId = "gen", title = "Belt check", status = ScheduleStatus.ARCHIVED))
        archived.restore()
        val afterRestore = archived.settle()
        assertNotNull("still archived", graph.healthSubjects.get(HealthSubjectId("belt"))!!.archivedAt)
        assertFalse("the Restore state was re-read", afterRestore.restoreAllowed)
        assertTrue(afterRestore.timedSchedules.isEmpty())
        assertFalse(afterRestore.scheduleTaken || afterRestore.primaryRefused)
    }
}
