package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.usecase.BindTag
import com.loosecannon.servicetag.core.usecase.ResolveTag
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.journal.EventDetailViewModel
import com.loosecannon.servicetag.ui.maintenance.GroupDetailViewModel
import com.loosecannon.servicetag.ui.maintenance.GroupEditViewModel
import com.loosecannon.servicetag.ui.maintenance.ScheduleClosures
import com.loosecannon.servicetag.ui.maintenance.ScheduleCompletions
import com.loosecannon.servicetag.ui.maintenance.ScheduleDetailViewModel
import com.loosecannon.servicetag.ui.scan.TagResultViewModel
import com.loosecannon.servicetag.ui.service.ServiceCaseViewModel
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.ui.maintenance.CompletionAnswer
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.ui.transfer.transferredOutOr
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.loanRow
import com.loosecannon.servicetag.ui.loan.LoanAction
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.transfer.TransferStrings
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #77 (C19, C23; rows 32, 33 and §14's row-25 case) — a transferred-out asset on the phone, keyed on its **held**
 * records and never on ARCHIVED: the detail opens with the P77-32 block and offers no write but Delete; a
 * held-but-ACTIVE row is listed only with the Archived control on, as P77-31; an ordinary archived asset keeps every
 * action (AC 11); the withdrawal asks, then writes one WITHDRAWN and sweeps once; Delete keeps the records; a write
 * a stale screen still reaches says P77-35. Over a Room-backed [FakeGraph]; every name is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetTransferStateTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private var sweeps = 0

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-09-28")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun detailModel(id: String) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.conditions, graph.seasonActivations, graph.healthSubjects, graph.attachments,
        graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, AssetId(id),
        serviceCases = graph.serviceCases,
        loans = graph.loans,
        transfers = graph.transferRecords,
        withdrawTransfer = WithdrawTransferRecordFor(graph),
        reconcile = ReminderReconcile { sweeps += 1 },
    )

    private fun TestScope.listModel() = AssetsViewModel(
        graph.assets, graph.categories, graph.seasonActivations, graph.tags, graph.assetHealthReadModel, graph.todayPort,
        loans = graph.loans, transfers = graph.transferRecords,
    ).also { vm -> backgroundScope.launch { vm.state.collect() } }

    private suspend fun TestScope.loaded(vm: AssetDetailViewModel): AssetDetailState {
        backgroundScope.launch { vm.state.collect() }
        advanceUntilIdle()
        return vm.state.first { it != null }!!
    }

    private suspend fun out(asset: String, pack: String = PACK, note: String = "") = graph.transferRecords.append(
        TransferRecord(
            id = "out-$asset-$pack", assetId = AssetId(asset), kind = TransferKind.OUT, packId = pack,
            lineage = emptyList(), at = AT, packSha256 = "ab".repeat(32), nameSnapshot = "Example Water Heater", note = note,
        ),
    )

    /** The heater as marking leaves it: archived, with one OUT. */
    private suspend fun heldHeater() {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater", status = AssetStatus.ARCHIVED))
        out("h1", note = "Keys are in the drawer")
    }

    @Test fun aHeldAssetDrawsTheBlockAndOffersNoWriteButDelete() = runTest(scheduler) {
        heldHeater()

        val state = loaded(detailModel("h1"))

        assertEquals(
            listOf(
                TransferOutRow(
                    packId = PACK,
                    on = "Transferred on " + TransferStrings.day(AT, ZoneId.systemDefault()),
                    pack = "Transfer Pack 0f1e2d3c",
                    note = "Keys are in the drawer",
                    withdrawTitle = "Withdraw the record for Transfer Pack 0f1e2d3c?",
                ),
            ),
            state.transferredOut,
        )
        assertTrue(state.held)
        assertFalse("no write", state.offersWrites)
        assertEquals("the overflow but Delete is hidden", listOf(DetailMenuItem.DELETE), state.menu)
        assertFalse(state.offersLogIncident)
        assertFalse(state.offersMarkOperational)
        assertFalse(state.offersNewServiceCase)
        assertFalse("no lending", state.loans.offersLendOut)
        assertTrue("P77-31 on the plate", PlateFact.Transferred in state.plate)
        assertTrue("never Archived beside it", state.plate.none { it is PlateFact.Archived })
    }

    /** Merged history can hold an asset lent out here: its open loan is still drawn, and only "Open contact" is offered. */
    @Test fun anOpenLoanOnAHeldAssetOffersNoWrite() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.loans.upsert(loanRow("l1", assetId = "h1", lentOn = "2026-09-20", borrower = "Example Buyer"))
        out("h1")

        val loans = loaded(detailModel("h1")).loans

        assertTrue("the open loan is still drawn", loans.open != null)
        assertTrue(loans.open!!.actions.all { it == LoanAction.OPEN_CONTACT })
        assertFalse(loans.offersLendOut)
    }

    @Test fun heldButActiveIsHiddenWithoutTheArchivedControl() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.assets.upsert(assetRow("g1", name = "Sample Garage Door Opener"))
        out("h1") // merged history: the row is still ACTIVE here
        val list = listModel()
        advanceUntilIdle()

        assertEquals(listOf("g1"), list.state.value.items.map { it.asset.id.value })
        list.toggleArchived()
        advanceUntilIdle()
        val rows = list.state.value.items
        assertEquals(listOf("g1", "h1"), rows.map { it.asset.id.value })
        assertEquals(listOf(false, true), rows.map { it.transferred })
        assertNull("a held row draws no health", rows.last().health)

        val detail = loaded(detailModel("h1"))
        assertFalse("held-but-ACTIVE is read-only all the same", detail.offersWrites)
        assertEquals(listOf(DetailMenuItem.DELETE), detail.menu)
    }

    @Test fun anArchivedAssetKeepsEveryAction() = runTest(scheduler) {
        graph.assets.upsert(assetRow("x1", name = "Example Ladder", status = AssetStatus.ARCHIVED))
        val list = listModel()

        val state = loaded(detailModel("x1"))

        assertFalse(state.held)
        assertTrue(state.offersWrites)
        assertEquals(
            listOf(
                DetailMenuItem.EDIT, DetailMenuItem.UNARCHIVE, DetailMenuItem.RETIRE, DetailMenuItem.TRANSFER,
                DetailMenuItem.DELETE,
            ),
            state.menu,
        )
        assertTrue(state.plate.any { it is PlateFact.Archived })
        assertTrue(PlateFact.Transferred !in state.plate)
        list.toggleArchived()
        advanceUntilIdle()
        assertEquals(listOf(false), list.state.value.items.map { it.transferred })
    }

    @Test fun withdrawAsksThenWritesThenSweepsOnce() = runTest(scheduler) {
        heldHeater()
        val vm = detailModel("h1")
        loaded(vm)

        vm.askWithdraw(PACK)
        assertEquals(DetailPrompt.Withdraw(PACK, "Withdraw the record for Transfer Pack 0f1e2d3c?"), vm.prompt.value)
        assertEquals("nothing is written before the confirm", 1, graph.transferRecords.all().size)
        assertEquals(0, sweeps)

        vm.withdraw()
        advanceUntilIdle()

        assertEquals(listOf(TransferKind.OUT, TransferKind.WITHDRAWN), graph.transferRecords.all().map { it.kind }.sorted())
        assertEquals("one sweep, after the write", 1, sweeps)
        assertNull(vm.prompt.value)
        assertEquals("it stays archived", AssetStatus.ARCHIVED, graph.assets.get(AssetId("h1"))!!.status)
        assertFalse("no longer held", vm.state.value!!.held)
    }

    @Test fun withdrawalIsOfferedOnlyForAnOpenOut() = runTest(scheduler) {
        graph.assets.upsert(assetRow("x1", name = "Example Ladder", status = AssetStatus.ARCHIVED))
        val vm = detailModel("x1")
        loaded(vm)

        vm.askWithdraw(PACK)

        assertNull(vm.prompt.value)
        assertEquals(emptyList<TransferRecord>(), graph.transferRecords.all())
    }

    @Test fun deleteAssetOnAHeldAssetKeepsTheRecords() = runTest(scheduler) {
        heldHeater()
        val vm = detailModel("h1")
        loaded(vm)

        vm.askDelete()
        vm.delete()
        advanceUntilIdle()

        assertNull("the asset is gone", graph.assets.get(AssetId("h1")))
        assertEquals("its transfer record survives the delete", listOf("out-h1-$PACK"), graph.transferRecords.all().map { it.id })
    }

    @Test fun aWriteAStaleScreenStillReachesSaysP77_35() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.tags.upsert(
            TagBinding(
                id = TagId("t1"), payloadFormat = PayloadFormat.V1, payloadKey = "TEST-0001",
                target = TagTarget.AssetTarget(AssetId("h1")), createdAt = 100L, updatedAt = 100L,
            ),
        )
        val vm = detailModel("h1")
        loaded(vm)
        val said = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.messages.toList(said) }
        out("h1") // the asset leaves while this screen is open

        vm.editTagLabel(TagId("t1"), "Example Basement")
        advanceUntilIdle()
        vm.archive()
        advanceUntilIdle()

        assertEquals(listOf("This asset was transferred out.", "This asset was transferred out."), said)
        assertNull(graph.tags.get(TagId("t1"))!!.label)
        assertEquals(0, sweeps)
    }

    // ---------------------------------------------------------------- the four sub-screens (C19, row 32)

    private fun incidentOf(id: String, assetId: String) = AssetEvent(
        id = EventId(id), assetId = AssetId(assetId), kind = EventKind.INCIDENT, title = "Will not heat",
        profileId = null, occurredOn = "2026-09-20", occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = null, createdAt = 100L, updatedAt = 100L,
        measurements = emptyList(), consumables = emptyList(),
    )

    @Test fun eventDetailIsReadOnlyForAHeldOwner() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.events.upsert(incidentOf("e1", "h1"))
        fun model() = EventDetailViewModel(
            graph.events, graph.definitions, graph.assets, graph.deleteEvent, EventId("e1"),
            transfers = graph.transferRecords,
        ).also { vm -> backgroundScope.launch { vm.state.collect() } }

        val before = model()
        advanceUntilIdle()
        assertTrue("an entry of an asset here is editable", before.state.value!!.editable)
        assertTrue(before.state.value!!.startsServiceCase)

        out("h1")
        val after = model()
        advanceUntilIdle()
        assertFalse("read only for a held owner", after.state.value!!.editable)
        assertFalse(after.state.value!!.startsServiceCase)
    }

    @Test fun serviceCaseIsReadOnlyForAHeldOwner() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.events.upsert(incidentOf("inc-1", "h1"))
        graph.conditions.insert(conditionRow("cond-1", "h1", OperationalCondition.DOWN, "2026-09-20", eventId = "inc-1"))
        graph.events.upsert(incidentOf("m1", "h1").copy(kind = EventKind.MAINTENANCE, title = "Replaced the element"))
        val case = graph.openServiceCase.run(
            AssetId("h1"),
            ServiceCaseCommand(
                title = "Heater claim", type = CaseType.WARRANTY_SERVICE, openedOn = "2026-09-21",
                coverage = CaseCoverage.IN_WARRANTY, resolutionEventId = null, caseRef = "RMA-0001",
            ),
            EventId("inc-1"),
        )
        fun model() = ServiceCaseViewModel(
            graph.serviceCases, graph.serviceCaseEntries, graph.events, graph.updateServiceCase,
            graph.addServiceCaseEntry, graph.todayPort, case.id, transfers = graph.transferRecords,
        ).also { vm -> backgroundScope.launch { vm.state.collect() } }

        val before = model()
        advanceUntilIdle()
        assertTrue(before.state.value!!.editable)
        assertTrue(before.state.value!!.offersLinkRepair)

        out("h1")
        val after = model()
        advanceUntilIdle()
        assertFalse("read only for a held owner", after.state.value!!.editable)
        assertFalse(after.state.value!!.offersLinkRepair)
    }

    private fun scheduleModel(id: String) = ScheduleDetailViewModel(
        schedules = graph.schedules,
        assets = graph.assets,
        groups = graph.groups,
        completions = ScheduleCompletions { sid -> graph.events.all().filter { it.scheduleId == sid } },
        closures = ScheduleClosures { sid -> graph.closures.forSchedule(sid) },
        recompute = graph.recomputeSchedules,
        postponeSchedule = graph.postponeSchedule,
        pauseSchedule = graph.pauseSchedule,
        archiveSchedule = graph.archiveSchedule,
        closeRoundUseCase = graph.closeRound,
        snoozer = graph.scheduleSnooze,
        today = graph.todayPort,
        clock = graph.clock,
        completion = graph.completionFlow,
        scheduleId = ScheduleId(id),
        transfers = graph.transferRecords,
    )

    @Test fun scheduleDetailIsReadOnlyForAHeldOwner() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.schedules.upsert(scheduleOf("s1", assetId = "h1", title = "Flush the tank"))
        graph.recomputeSchedules.all()

        val before = scheduleModel("s1")
        advanceUntilIdle()
        assertTrue(before.state.value.editable)
        assertTrue(before.state.value.canComplete)

        out("h1")
        val after = scheduleModel("s1")
        advanceUntilIdle()
        assertFalse("read only for a held owner", after.state.value.editable)
        assertFalse(after.state.value.canComplete)
        assertFalse(after.state.value.canPostpone)
    }

    private fun groupModel(id: String) = GroupDetailViewModel(
        groups = graph.groups,
        assets = graph.assets,
        schedules = graph.schedules,
        states = graph.scheduleStates,
        recompute = graph.recomputeSchedules,
        archiveGroup = graph.archiveGroup,
        today = graph.todayPort,
        completion = graph.completionFlow,
        id = GroupId(id),
        transfers = graph.transferRecords,
    ).also { vm -> vm.state }

    @Test fun groupDetailIsReadOnlyForAHeldOwner() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.assets.upsert(assetRow("g1", name = "Sample Garage Door Opener"))
        // The heater's window is closed: a removed row still makes the group the heater's too.
        graph.groups.upsert(groupOf("G1", members = listOf(Triple("h1", "2026-01-01", "2026-02-01"), Triple("g1", "2026-01-01", null))))
        graph.schedules.upsert(scheduleOf("s1", groupId = "G1", title = "Lubricate"))
        graph.recomputeSchedules.all()

        val before = groupModel("G1")
        backgroundScope.launch { before.state.collect() }
        advanceUntilIdle()
        assertTrue(before.state.value!!.editable)

        out("h1")
        val after = groupModel("G1")
        backgroundScope.launch { after.state.collect() }
        advanceUntilIdle()
        assertFalse("read only for a group naming a held asset", after.state.value!!.editable)
        assertTrue(after.state.value!!.schedules.none { it.canComplete })
    }

    /** B4 hand-off 1: the one mapping onto P77-35, and a refused group write that no longer takes the screen down. */
    @Test fun aStaleScreensRefusedWriteSaysP77_35AndNeverCrashes() = runTest(scheduler) {
        assertEquals("This asset was transferred out.", AssetTransferredOut(AssetId("h1")).transferredOutOr("Could not save this asset."))
        assertEquals("Could not save this asset.", IllegalStateException("other").transferredOutOr("Could not save this asset."))

        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.groups.upsert(groupOf("G1", members = listOf(Triple("h1", "2026-01-01", null))))
        val group = groupModel("G1")
        backgroundScope.launch { group.state.collect() }
        advanceUntilIdle()
        out("h1") // the heater leaves while the group screen is open

        group.setArchived(true)
        advanceUntilIdle()

        assertNull("refused, nothing written", graph.groups.get(GroupId("G1"))!!.archivedAt)
        assertFalse(group.state.value!!.editable)
    }

    /**
     * MJ-1 (fix round 1): a standing post's "Done" opens the schedule with `complete = true`. For a held owner it asks
     * nothing — no "When was this done?" and no form — writes nothing, and says P77-35. A QUICK and a FORM schedule.
     */
    @Test fun aDoneDeepLinkForAHeldOwnerAsksNothingAndSaysP77_35() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.schedules.upsert(scheduleOf("s1", assetId = "h1", title = "Flush the tank"))
        graph.schedules.upsert(scheduleOf("s2", assetId = "h1", title = "Anode check", completionMode = CompletionMode.FORM))
        graph.recomputeSchedules.all()
        out("h1") // the post outlived the hold

        val quick = scheduleModel("s1")
        val saidQuick = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { quick.messages.toList(saidQuick) }
        advanceUntilIdle()
        quick.complete()
        advanceUntilIdle()
        assertNull("no \"When was this done?\"", graph.completionFlow.prompt.value)
        assertEquals(listOf("This asset was transferred out."), saidQuick)

        val form = scheduleModel("s2")
        val saidForm = mutableListOf<String>()
        val forms = mutableListOf<Any>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { form.messages.toList(saidForm) }
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { form.needsForm.toList(forms) }
        advanceUntilIdle()
        form.complete()
        advanceUntilIdle()
        assertEquals("no form is opened", emptyList<Any>(), forms)
        assertEquals(listOf("This asset was transferred out."), saidForm)
        assertEquals("nothing written", emptyList<Any>(), graph.events.all())
    }

    /** MJ-1 (2): a write a stale schedule screen still reaches is refused and says P77-35, never folded silently. */
    @Test fun aStaleSchedulePauseSaysP77_35() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.schedules.upsert(scheduleOf("s1", assetId = "h1", title = "Flush the tank"))
        graph.recomputeSchedules.all()
        val vm = scheduleModel("s1")
        val said = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.messages.toList(said) }
        advanceUntilIdle()
        out("h1")

        vm.pause(true)
        advanceUntilIdle()

        assertEquals(listOf("This asset was transferred out."), said)
        assertEquals(ScheduleStatus.ACTIVE, graph.schedules.get(ScheduleId("s1"))!!.status)
    }

    /** mn-1: the group detail's refused writes say P77-35 — the archive, and a completion the guard refuses. */
    @Test fun aGroupDetailsRefusedWritesSayP77_35() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.assets.upsert(assetRow("g1", name = "Sample Garage Door Opener"))
        graph.groups.upsert(groupOf("G1", members = listOf(Triple("h1", "2026-01-01", null), Triple("g1", "2026-01-01", null))))
        graph.schedules.upsert(scheduleOf("s-group", groupId = "G1", title = "Lubricate", anchorOn = "2026-01-01", leadDays = 0))
        graph.recomputeSchedules.all()
        val group = groupModel("G1")
        val said = mutableListOf<String>()
        backgroundScope.launch { group.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { group.messages.toList(said) }
        advanceUntilIdle()
        out("h1")

        group.setArchived(true)
        advanceUntilIdle()
        group.completeAll(ScheduleId("s-group"))
        advanceUntilIdle()
        graph.completionFlow.prompt.first { it != null }
        assertTrue(graph.completionFlow.submit(CompletionAnswer(occurredOn = "2026-09-28")))
        advanceUntilIdle()

        assertEquals(listOf("This asset was transferred out.", "This asset was transferred out."), said)
        assertNull(graph.groups.get(GroupId("G1"))!!.archivedAt)
        assertEquals(emptyList<Any>(), graph.events.all())
    }

    /** NOTE 2: a held asset's open loan (merged history) draws no reminder line — the reminder is quiesced (R77-20). */
    @Test fun aHeldAssetsOpenLoanDrawsNoReminderLine() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.loans.upsert(
            loanRow("l1", assetId = "h1", lentOn = "2026-09-20", dueOn = "2026-10-01", mode = LoanReminderMode.ONCE, borrower = "Example Buyer"),
        )
        out("h1")

        val open = loaded(detailModel("h1")).loans.open!!

        assertNull(open.reminderLine)
    }

    // ---------------------------------------------------------------- the pickers (C19, rm-5)

    /** A held-but-ACTIVE heater beside an ordinary opener: a picker keyed on status would offer both. */
    private suspend fun heldActiveHeater() {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.assets.upsert(assetRow("g1", name = "Sample Garage Door Opener"))
        out("h1")
    }

    @Test fun scanToBindNeverOffersAHeldAsset() = runTest(scheduler) {
        heldActiveHeater()
        val model = TagResultViewModel(
            ResolveTag(graph.tags, graph.assets, graph.uow, graph.clock, graph.transferRecords),
            BindTag(graph.tags, graph.assets, graph.uow, graph.clock),
            graph.assets,
            { false },
            PayloadFormat.V1.name,
            "00000000-0000-4000-8000-00000000abcd",
            graph.transferRecords,
        )
        backgroundScope.launch { model.targets.collect() }
        advanceUntilIdle()

        assertEquals(listOf("g1"), model.targets.value.assets.map { it.id.value })
    }

    @Test fun parentChoicesNeverOfferAHeldAsset() = runTest(scheduler) {
        heldActiveHeater()
        val model = AssetEditViewModel(
            graph.assets, graph.healthSubjects, graph.saveAssetSettings, graph.schedules, graph.categories,
            graph.attachments, graph.attachmentStorage, graph.addAttachment, graph.todayPort, null, null,
            transfers = graph.transferRecords,
        )
        advanceUntilIdle()
        val choices = model.state.first { it.parentChoices.isNotEmpty() }.parentChoices

        assertEquals(listOf(null, "g1"), choices.map { it.id })
    }

    @Test fun groupMemberPickerNeverOffersAHeldAsset() = runTest(scheduler) {
        heldActiveHeater()
        val model = GroupEditViewModel(graph.groups, graph.assets, graph.saveGroup, null, transfers = graph.transferRecords)
        advanceUntilIdle()

        assertEquals(listOf("g1"), model.state.first { it.loaded }.candidates.map { it.assetId.value })
    }

    /**
     * The schedule editor has no target picker: its target is fixed by the entry that opens it (`ServiceTagRoot`:
     * "the editor carries no second picker"). A held target is never offered because both entries withhold their
     * create action for a held owner — the asset detail's (no write offered) and the group detail's.
     */
    @Test fun scheduleTargetPickerNeverOffersAHeldAsset() = runTest(scheduler) {
        graph.assets.upsert(assetRow("h1", name = "Example Water Heater"))
        graph.groups.upsert(groupOf("G1", members = listOf(Triple("h1", "2026-01-01", null))))
        out("h1")

        assertFalse("the asset detail offers no Add schedule", loaded(detailModel("h1")).offersWrites)
        val group = groupModel("G1")
        backgroundScope.launch { group.state.collect() }
        advanceUntilIdle()
        assertFalse("the group detail offers no Add schedule", group.state.value!!.editable)
    }

    private companion object {
        const val PACK = "0f1e2d3c-4b5a-4968-8776-655443322110"
        const val AT = 1_790_510_400_000L // 27 Sep 2026, 12:00 UTC
    }
}

/** The production withdrawal over [graph]'s own records, unit of work, ids and clock. */
@Suppress("FunctionName")
private fun WithdrawTransferRecordFor(graph: FakeGraph) =
    com.loosecannon.servicetag.core.usecase.WithdrawTransferRecord(graph.transferRecords, graph.uow, graph.ids, graph.clock)
