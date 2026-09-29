package com.loosecannon.servicetag.ui.replace

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.ports.AssetSuccessionRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.usecase.ReplaceAsset
import com.loosecannon.servicetag.core.usecase.readSnapshot
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.loanRow
import com.loosecannon.servicetag.testing.meterDefinitionOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.asset.NO_PARENT
import com.loosecannon.servicetag.ui.asset.OUT_OF_SEASON_NOW
import com.loosecannon.servicetag.ui.asset.ParentChoice
import com.loosecannon.servicetag.ui.asset.READINGS_AND_ACTIONS
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.scan.identityLine
import com.loosecannon.servicetag.ui.transfer.`import`.TransferImportStrings
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * #86 (C17; row 15) — the Replace screen's state: the offer drawn with **every item unticked** and every tag left
 * (R86-9, R86-14); the prefill (R86-8); the plan's problems as the ratified lines, each keeping Review disabled; the
 * review's lines; **nothing written before the confirm** (R86-4); one write for a double tap; one sweep **after** the
 * write, then the successor handed over once; and the three refusals (P86-25 with the offer re-read, P77-35, P86-24).
 * Over a Room-backed [FakeGraph]; every name is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceAssetViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    /** Each sweep: whether the succession row was already stored, and what `done` said, when it ran. */
    private val sweeps = mutableListOf<Pair<Boolean, String?>>()
    private var current: ReplaceAssetViewModel? = null

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse(TODAY)
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.open(id: String = "p1", replace: ReplaceAsset = graph.replaceAsset): ReplaceAssetViewModel {
        val vm = ReplaceAssetViewModel(
            replace, graph.categories, graph.todayPort,
            ReminderReconcile {
                sweeps += (graph.assetSuccessions.replacedBy(AssetId(id)) != null) to current?.state?.value?.done
            },
            AssetId(id),
        )
        current = vm
        advanceUntilIdle()
        return vm
    }

    /** `FakeGraph`'s own wiring of [ReplaceAsset], with one port swapped for a case that needs to count or fail. */
    private fun replaceOver(
        uow: UnitOfWork = graph.uow,
        successions: AssetSuccessionRepository = graph.assetSuccessions,
    ) = ReplaceAsset(
        graph.assets, graph.schedules, graph.groups, graph.tags, graph.definitions, graph.profiles, graph.loans,
        graph.transferRecords, successions, uow, graph.ids, graph.clock, graph.todayPort,
        graph.retireAsset, graph.saveAssetSettings, graph.saveSchedule, graph.saveGroup, graph.bindTag,
    )

    private suspend fun snapshot() = graph.uow.read { readSnapshot(graph.backupRepositories) }

    private fun ReplaceAssetViewModel.schedule(id: String) = state.value.schedules.single { it.id == id }

    /**
     * The pool pump: a category, a location and a parent; identity and purchase facts; a calendar season; notes; a
     * meter definition; a time schedule, a meter schedule and a PRE_SERVICE one; a group; a placed tag; one component.
     */
    private suspend fun pump() {
        graph.assets.upsert(assetRow("h1", name = "Example Pool House"))
        graph.assets.upsert(
            assetRow(
                "p1", name = "Sample Pool Pump", parent = "h1",
                seasonMode = SeasonMode.CALENDAR, seasonStart = "05-01", seasonEnd = "09-30",
            ).copy(
                category = "Pump", location = "Back yard", manufacturer = "Example Co", model = "X-1",
                serialNumber = "TEST-SN-1", purchaseOn = "2020-05-01", inServiceOn = "2020-05-02",
                description = "Runs daily", notes = "Check the basket",
            ),
        )
        graph.assets.upsert(assetRow("c1", name = "Example Pump Motor", parent = "p1"))
        graph.definitions.upsert(meterDefinitionOf("d1", "p1"))
        graph.schedules.upsert(scheduleOf("s-time", assetId = "p1", title = "Clean the basket"))
        graph.schedules.upsert(
            scheduleOf(
                "s-meter", assetId = "p1", title = "Change the seal",
                timeInterval = null, timeUnit = null, anchorOn = null, meterDefinitionId = "d1", meterInterval = 500.0,
            ),
        )
        graph.schedules.upsert(
            scheduleOf(
                "s-pre", assetId = "p1", title = "Open for the season",
                servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = 7,
            ),
        )
        graph.groups.upsert(groupOf("g1", name = "Backyard", members = listOf(Triple("p1", "2026-01-01", null))))
        graph.tags.upsert(tag("t1", "p1", label = "Under the lid"))
    }

    /** The garage door opener: MANUAL, with notes, one definition, one schedule, one group and one tag. */
    private suspend fun opener() {
        graph.assets.upsert(
            assetRow("m1", name = "Example Garage Door Opener", seasonMode = SeasonMode.MANUAL).copy(notes = "Chain drive"),
        )
        graph.definitions.upsert(meterDefinitionOf("d9", "m1", key = "cycles", label = "Cycles", unit = ""))
        graph.schedules.upsert(scheduleOf("s-lube", assetId = "m1", title = "Lube the rails"))
        graph.groups.upsert(groupOf("g2", name = "Garage", members = listOf(Triple("m1", "2026-01-01", null))))
        graph.tags.upsert(tag("t9", "m1"))
    }

    private fun tag(id: String, asset: String, label: String? = null) = TagBinding(
        id = TagId(id), payloadFormat = PayloadFormat.V1, payloadKey = "TEST-$id",
        target = TagTarget.AssetTarget(AssetId(asset)), label = label, createdAt = 1L, updatedAt = 1L,
    )

    @Test fun everyItemStartsUnticked() = runTest(scheduler) {
        pump()

        val state = open().state.value

        assertEquals(ReplacePhase.FORM, state.phase)
        assertTrue(state.seasonOffered && state.setupOffered && state.notesOffered)
        assertFalse("season", state.form.carrySeason)
        assertFalse("readings & actions", state.form.carrySetup)
        assertFalse("notes", state.form.carryNotes)
        assertNull("no phase answered", state.form.manualPhase)
        assertEquals(listOf("s-meter", "s-time", "s-pre"), state.schedules.map { it.id })
        assertEquals(listOf(false, false, false), state.schedules.map { it.ticked })
        assertEquals(listOf(false), state.groups.map { it.ticked })
        assertEquals("every tag is left by default", listOf(false), state.tags.map { it.moved })
        assertTrue(state.form.scheduleIds.isEmpty() && state.form.groupIds.isEmpty() && state.form.movedTagIds.isEmpty())
        assertFalse("no ticked time rule, no start date", state.asksScheduleStart)
        assertTrue("nothing ticked still reviews", state.reviewEnabled)
    }

    @Test fun thePrefillIsNameCategoryLocationAndAValidParent() = runTest(scheduler) {
        pump()
        graph.assets.upsert(assetRow("a9", name = "Example Old Shed", status = AssetStatus.ARCHIVED))
        val vm = open()

        val form = vm.state.value.form
        assertEquals("Sample Pool Pump", form.name)
        assertEquals("Pump", form.category)
        assertEquals("Back yard", form.location)
        assertEquals("h1", form.parentId)
        assertEquals(listOf("", "", "", ""), listOf(form.manufacturer, form.model, form.serialNumber, form.purchaseOn))
        assertEquals("retired today by default", TODAY, form.retiredOn)
        assertEquals("in service on the replacement date", TODAY, form.inServiceOn)
        assertEquals(
            "None first, archived marked, never the old asset or its component",
            listOf(ParentChoice(null, NO_PARENT), ParentChoice("a9", "Example Old Shed (archived)"), ParentChoice("h1", "Example Pool House")),
            vm.state.value.parentChoices,
        )

        vm.onRetiredOn("2026-06-01")
        advanceUntilIdle()
        assertEquals("follows the replacement date", "2026-06-01", vm.state.value.form.inServiceOn)
        vm.onInServiceOn("2026-06-03")
        vm.onRetiredOn("2026-06-02")
        advanceUntilIdle()
        assertEquals("an edited date stays", "2026-06-03", vm.state.value.form.inServiceOn)
    }

    @Test fun aDependencyLineBlocksReview() = runTest(scheduler) {
        pump()
        val vm = open()

        vm.onSchedule("s-meter", true)
        advanceUntilIdle()
        assertEquals(listOf(ReplaceStrings.NEEDS_SETUP), vm.schedule("s-meter").needs)
        assertFalse(vm.state.value.reviewEnabled)
        vm.onCarrySetup(true)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.schedule("s-meter").needs)
        assertTrue(vm.state.value.reviewEnabled)

        vm.onSchedule("s-pre", true)
        advanceUntilIdle()
        assertEquals(listOf(ReplaceStrings.NEEDS_SEASON), vm.schedule("s-pre").needs)
        assertFalse(vm.state.value.reviewEnabled)
        assertTrue("a ticked time rule asks its start", vm.state.value.asksScheduleStart)
        assertEquals("the start follows the in-service date", TODAY, vm.state.value.form.scheduleStartOn)
        vm.onCarrySeason(true)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), vm.schedule("s-pre").needs)
        assertTrue(vm.state.value.reviewEnabled)
    }

    @Test fun anUnansweredPhaseBlocksReview() = runTest(scheduler) {
        opener()
        val vm = open("m1")

        vm.onCarrySeason(true)
        advanceUntilIdle()
        assertEquals(ReplaceStrings.wasInSeasonOn(TODAY), vm.state.value.phaseQuestion)
        assertFalse(vm.state.value.reviewEnabled)

        vm.onRetiredOn("2026-06-01")
        advanceUntilIdle()
        assertEquals("asked on the replacement date", ReplaceStrings.wasInSeasonOn("2026-06-01"), vm.state.value.phaseQuestion)
        vm.onManualPhase(SeasonPhase.IN_SEASON)
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewEnabled)
    }

    @Test fun aFutureDateSaysTheShippedSentenceAndBlocksReview() = runTest(scheduler) {
        pump()
        graph.assets.upsert(assetRow("r1", name = "Example Water Heater", retiredOn = "2026-07-01"))
        val vm = open()

        vm.onRetiredOn("2026-06-20")
        advanceUntilIdle()
        assertTrue(vm.state.value.asksRetiredOn)
        assertEquals(DATE_NOT_LATER_THAN_TODAY, vm.state.value.problems[ReplaceField.RETIRED_ON])
        assertFalse(vm.state.value.reviewEnabled)
        vm.onRetiredOn(TODAY)
        advanceUntilIdle()
        assertNull("today itself passes", vm.state.value.problems[ReplaceField.RETIRED_ON])
        assertTrue(vm.state.value.reviewEnabled)

        val stored = open("r1").state.value
        assertFalse("no field once retired", stored.asksRetiredOn)
        assertEquals(ReplaceStrings.wasRetiredOn("Example Water Heater", "2026-07-01"), stored.oldAssetLine)
        assertEquals("under P86-4", DATE_NOT_LATER_THAN_TODAY, stored.problems[ReplaceField.RETIRED_ON])
        assertFalse(stored.reviewEnabled)
    }

    @Test fun oneChildAndSeveralChildrenTakeTheirP86_18Forms() = runTest(scheduler) {
        pump()
        graph.loans.upsert(loanRow("l1", "p1", lentOn = "2026-06-01"))

        val one = open().state.value
        assertEquals("Example Pump Motor stays part of Sample Pool Pump.", one.childrenLine)
        assertEquals("Sample Pool Pump is lent out. The loan stays with it.", one.loanLine)

        graph.assets.upsert(assetRow("c2", name = "Example Filter Housing", parent = "p1"))
        assertEquals(
            "Example Filter Housing, Example Pump Motor stay part of Sample Pool Pump.",
            open().state.value.childrenLine,
        )
        val none = open("h1").state.value
        assertEquals("the pool house's one child is the pump", "Sample Pool Pump stays part of Example Pool House.", none.childrenLine)
        assertNull("no loan, no line", none.loanLine)
    }

    @Test fun nothingIsWrittenBeforeTheConfirm() = runTest(scheduler) {
        pump()
        val before = snapshot()
        val vm = open()

        vm.onName("Sample Pool Pump II")
        vm.onCarrySeason(true)
        vm.onCarrySetup(true)
        vm.onCarryNotes(true)
        listOf("s-time", "s-meter", "s-pre").forEach { vm.onSchedule(it, true) }
        vm.onGroup("g1", true)
        vm.onTagMove("t1", true)
        advanceUntilIdle()
        assertTrue(vm.state.value.reviewEnabled)
        vm.review()
        advanceUntilIdle()
        assertEquals(ReplacePhase.REVIEW, vm.state.value.phase)
        vm.backToForm()
        advanceUntilIdle()

        assertEquals(ReplacePhase.FORM, vm.state.value.phase)
        assertEquals("the store is as it was", before, snapshot())
        assertTrue("no sweep", sweeps.isEmpty())
    }

    @Test fun aDoubleTapReplacesOnce() = runTest(scheduler) {
        pump()
        var writes = 0
        val counted = object : UnitOfWork by graph.uow {
            override suspend fun <T> write(block: suspend () -> T): T {
                writes += 1
                return graph.uow.write(block)
            }
        }
        val vm = open(replace = replaceOver(uow = counted))
        vm.onName("Sample Pool Pump II")
        advanceUntilIdle()
        vm.review()
        advanceUntilIdle()

        vm.confirm()
        vm.confirm()
        advanceUntilIdle()

        assertEquals("one write opened", 1, writes)
        assertEquals(1, snapshot().assetSuccessions.size)
        assertEquals("one sweep", 1, sweeps.size)
        assertNull("no refusal from a second run", vm.state.value.error)
        assertNotNull(vm.state.value.done)
    }

    @Test fun successSweepsOnceThenOpensTheSuccessor() = runTest(scheduler) {
        pump()
        val vm = open()
        vm.onName("Sample Pool Pump II")
        advanceUntilIdle()
        vm.review()
        advanceUntilIdle()

        vm.confirm()
        advanceUntilIdle()

        val row = graph.assetSuccessions.replacedBy(AssetId("p1"))!!
        assertEquals("one sweep, after the write and before the hand-over", listOf(true to null), sweeps)
        assertEquals("Sample Pool Pump II", graph.assets.get(row.successorAssetId)!!.name)
        assertEquals(row.successorAssetId.value, vm.state.value.done)
        assertEquals(row.successorAssetId.value, vm.takeDone())
        assertNull("handed over once", vm.takeDone())
    }

    @Test fun aStaleRefusalSaysP86_25AndReReads() = runTest(scheduler) {
        pump()
        val vm = open()
        vm.onName("Sample Pool Pump II")
        vm.onCarrySetup(true)
        vm.onSchedule("s-time", true)
        vm.onSchedule("s-meter", true)
        advanceUntilIdle()
        vm.review()
        advanceUntilIdle()
        graph.schedules.upsert(graph.schedules.get(ScheduleId("s-time"))!!.copy(status = ScheduleStatus.ARCHIVED))

        vm.confirm()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ReplaceStrings.CHANGED_WHILE_REVIEWING, state.error)
        assertEquals(ReplacePhase.FORM, state.phase)
        assertEquals("a tick no longer offered is dropped", setOf("s-meter"), state.form.scheduleIds)
        assertEquals(listOf("s-meter", "s-pre"), state.schedules.map { it.id })
        assertEquals("typed fields are kept", "Sample Pool Pump II", state.form.name)
        assertTrue(state.form.carrySetup)
        assertTrue(state.reviewEnabled)
        assertTrue(snapshot().assetSuccessions.isEmpty())
        assertTrue(sweeps.isEmpty())
    }

    @Test fun aHeldRefusalSaysP77_35() = runTest(scheduler) {
        pump()
        val vm = open()
        vm.review()
        advanceUntilIdle()
        graph.transferRecords.append(
            TransferRecord(
                id = "out-p1", assetId = AssetId("p1"), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = 1_790_510_400_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Sample Pool Pump", note = "",
            ),
        )

        vm.confirm()
        advanceUntilIdle()

        assertEquals(TransferImportStrings.ASSET_TRANSFERRED_OUT, vm.state.value.error)
        assertFalse(vm.state.value.saving)
        assertNull(vm.state.value.done)
        assertTrue(snapshot().assetSuccessions.isEmpty())
        assertTrue(sweeps.isEmpty())
    }

    @Test fun anyOtherFailureSaysP86_24() = runTest(scheduler) {
        pump()
        val before = snapshot()
        // An IllegalStateException, like ReplaceStale, so the mapping order is what tells them apart.
        val rigged = object : AssetSuccessionRepository by graph.assetSuccessions {
            override suspend fun append(row: AssetSuccession) {
                throw IllegalStateException("rigged")
            }
        }
        val vm = open(replace = replaceOver(successions = rigged))
        vm.review()
        advanceUntilIdle()

        vm.confirm()
        advanceUntilIdle()

        assertEquals(ReplaceStrings.couldNotReplace("Sample Pool Pump"), vm.state.value.error)
        assertFalse(vm.state.value.saving)
        assertEquals("rolled back", before, snapshot())
        assertTrue(sweeps.isEmpty())
    }

    @Test fun theReviewListsWhatIsTicked() = runTest(scheduler) {
        opener()
        val vm = open("m1")
        vm.onName("Example Garage Door Opener II")
        vm.onCarrySeason(true)
        vm.onManualPhase(SeasonPhase.OUT_OF_SEASON)
        vm.onCarrySetup(true)
        vm.onCarryNotes(true)
        vm.onSchedule("s-lube", true)
        vm.onGroup("g2", true)
        vm.onTagMove("t9", true)
        advanceUntilIdle()

        vm.review()
        advanceUntilIdle()

        val review = vm.state.value.review!!
        assertEquals(ReplaceStrings.retireOn("Example Garage Door Opener", TODAY), review.retireLine)
        assertEquals(ReplaceStrings.create("Example Garage Door Opener II"), review.createLine)
        assertEquals(
            listOf(
                ReplaceStrings.SEASON_AND_BREAK, OUT_OF_SEASON_NOW, READINGS_AND_ACTIONS,
                ReplaceStrings.DESCRIPTION_AND_NOTES, "Lube the rails", ReplaceStrings.addTo("Garage"),
            ),
            review.carried,
        )
        assertEquals(listOf(tag("t9", "m1").identityLine()), review.movedTags)

        graph.assets.upsert(assetRow("r1", name = "Example Water Heater", retiredOn = "2026-03-01"))
        val retired = open("r1")
        retired.review()
        advanceUntilIdle()
        val plain = retired.state.value.review!!
        assertEquals(ReplaceStrings.staysRetiredFrom("Example Water Heater", "2026-03-01"), plain.retireLine)
        assertEquals(listOf(ReplaceStrings.NOTHING_CARRIED), plain.carried)
        assertEquals(emptyList<String>(), plain.movedTags)
    }

    private companion object {
        const val TODAY = "2026-06-15"
    }
}
