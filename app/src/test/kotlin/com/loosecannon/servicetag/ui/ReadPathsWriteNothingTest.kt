package com.loosecannon.servicetag.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.LoanStanding
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleLocalDelivery
import com.loosecannon.servicetag.core.ports.ScheduleLocalDeliveryRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.CaseEntryCommand
import com.loosecannon.servicetag.core.usecase.GetAssetSeason
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.ServiceCaseCommand
import com.loosecannon.servicetag.core.warranty.WarrantyStatus
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.fakeContactReader
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.loanRow
import com.loosecannon.servicetag.testing.replacementOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import com.loosecannon.servicetag.ui.asset.AssetDetailViewModel
import com.loosecannon.servicetag.ui.asset.AssetEditViewModel
import com.loosecannon.servicetag.ui.asset.AssetsViewModel
import com.loosecannon.servicetag.ui.dashboard.DashboardViewModel
import com.loosecannon.servicetag.ui.dashboard.SectionEntry
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.health.AssetHealthView
import com.loosecannon.servicetag.ui.journal.EventDetailViewModel
import com.loosecannon.servicetag.ui.journal.caseLinksOf
import com.loosecannon.servicetag.ui.loan.LoanEditViewModel
import com.loosecannon.servicetag.ui.maintenance.AttentionReadModel
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.ui.maintenance.NoHealthFindings
import com.loosecannon.servicetag.ui.maintenance.ScanRoundMembership
import com.loosecannon.servicetag.ui.maintenance.ScanSheetOffer
import com.loosecannon.servicetag.ui.maintenance.scanSheetContentFor
import com.loosecannon.servicetag.ui.service.ServiceCaseEditViewModel
import com.loosecannon.servicetag.ui.service.ServiceCaseViewModel
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inv. 105: **no read path writes** `schedule_state`, health or anything else — and the case that
 * tempts a write is the one exercised: a state row the recompute left behind yesterday, on the last
 * day before a season opened, beside a schedule with no state row at all.
 *
 * Every repository the read models and the recompute reach is wrapped in a recorder that notes each
 * write call and passes it through; the whole read surface then runs and the recorder must stay
 * empty. The delivery seam is checked on the graph itself (plan decision 47).
 */
class ReadPathsWriteNothingTest {

    private val graph = FakeGraph()
    private val writes = mutableListOf<String>()

    @After fun tearDown() = graph.close()

    /**
     * Yesterday's world: a mower on a calendar season that opens today, with an IN_SERVICE
     * schedule whose stored row is DORMANT for yesterday; health subjects of both drivers; a DOWN
     * condition and a DOWN component; a group round; a snooze; and a schedule with no row at all.
     */
    private suspend fun seedAStaleWorld() {
        graph.today = LocalDate.parse("2026-04-14")
        graph.assets.upsert(assetRow("mow", name = "Mower", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31"))
        graph.assets.upsert(assetRow("pack", name = "Battery pack", parent = "mow"))
        graph.schedules.upsert(
            scheduleOf("s-mow", assetId = "mow", title = "Engine oil service", anchorOn = "2026-01-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0),
        )
        graph.recomputeSchedules.forSchedule(ScheduleId("s-mow"))
        graph.groups.upsert(groupOf("g1", name = "Mowers", members = listOf(Triple("mow", "2026-01-01", null))))
        graph.schedules.upsert(scheduleOf("s-group", groupId = "g1", title = "Blade sharpen", anchorOn = "2026-01-01", leadDays = 0))
        graph.recomputeSchedules.forSchedule(ScheduleId("s-group"))
        // No recompute: this one has no state row at all, as after the 7 → 8 migration.
        graph.schedules.upsert(scheduleOf("s-new", assetId = "mow", title = "Air filter", anchorOn = "2026-02-01", leadDays = 0))
        graph.healthSubjects.upsert(subjectRow("h-oil", "mow", name = "Oil", driver = HealthDriver.MAINTENANCE_OVERDUE, scheduleId = "s-mow"))
        graph.events.upsert(replacementOf("e1", "mow", "2025-11-01"))
        graph.healthSubjects.upsert(subjectRow("h-age", "mow", name = "Battery age", sortOrder = 1))
        graph.conditions.insert(conditionRow("c1", "mow", OperationalCondition.DOWN, "2026-04-10"))
        graph.conditions.insert(conditionRow("c2", "pack", OperationalCondition.DOWN, "2026-04-11"))
        graph.reminderSnooze.snooze(ScheduleId("s-mow"), dayMillis("2026-04-20"))
        graph.today = LocalDate.parse("2026-04-15")
    }

    @Test fun everyReadModelWritesNothing() = runTest {
        seedAStaleWorld()
        val stale = graph.scheduleStates.get(ScheduleId("s-mow"))!!
        assertEquals("the row really is yesterday's", "2026-04-14", stale.computedForOn)
        assertEquals(PolicyPhase.DORMANT, stale.policyPhase)
        val statesBefore = graph.scheduleStates.all()

        val recompute = RecomputeSchedules(
            schedules, states, events, closures, groups, assets, activations, graph.todayPort, graph.clock,
            zone = { ZoneOffset.UTC },
        )
        val health = AssetHealthReadModel(
            assets, subjects, schedules, states, events, profiles, activations, conditions, recompute, graph.todayPort,
            zone = { ZoneOffset.UTC },
            transfers = graph.transferRecords,
        )
        val attention = AttentionReadModel(assets, health, graph.todayPort, transfers = graph.transferRecords)
        val due = DueReadModel(
            schedules, assets, groups, definitions, recompute, graph.todayPort, health,
            snoozedUntilOf = { delivery.get(it)?.snoozedUntilAt },
            transfers = graph.transferRecords,
        )
        val rounds = ScanRoundMembership { id -> schedules.get(id)?.let { recompute.occurrenceOf(it) } }
        val offer = ScanSheetOffer { scanSheetContentFor(it, due, rounds, health).opens }

        val listed = due.items()
        due.forAsset(AssetId("mow"))
        val rows = attention.items()
        health.forAsset(AssetId("mow"))
        health.forAsset(AssetId("pack"))
        val opens = offer.has(AssetId("mow")) && offer.has(AssetId("pack"))

        assertEquals("zero writes anywhere", emptyList<String>(), writes)
        assertEquals("the stale row is untouched", statesBefore, graph.scheduleStates.all())
        // The reads did real work: today's state, both kinds of row, and a sheet that opens.
        assertEquals(PolicyPhase.ACTIVE, listed.single { it.scheduleId.value == "s-mow" }.policyPhase)
        assertEquals(3, listed.size)
        assertTrue(rows.isNotEmpty())
        assertTrue(opens)
    }

    /**
     * #71 (plan C2, E3): the Assets list's two new flows — the row health and every tag row — read
     * over the same stale world, through a refresh, and write nothing: the stale state row is read
     * through `readState`, never rebuilt.
     */
    @Test fun observeRowHealthWritesNothing() = runTest {
        seedAStaleWorld()
        graph.tags.upsert(
            TagBinding(
                id = TagId("t1"), payloadFormat = PayloadFormat.V1, payloadKey = "key-t1",
                target = TagTarget.AssetTarget(AssetId("mow")), writtenAt = 1L, createdAt = 1L, updatedAt = 1L,
            ),
        )
        val statesBefore = graph.scheduleStates.all()
        val recompute = RecomputeSchedules(
            schedules, states, events, closures, groups, assets, activations, graph.todayPort, graph.clock,
            zone = { ZoneOffset.UTC },
        )
        val health = AssetHealthReadModel(
            assets, subjects, schedules, states, events, profiles, activations, conditions, recompute, graph.todayPort,
            zone = { ZoneOffset.UTC },
            transfers = graph.transferRecords,
        )
        val refreshes = MutableStateFlow(0)
        val passes = MutableStateFlow(emptyList<Map<AssetId, AssetHealthView>>())
        val tagRows = MutableStateFlow<List<TagBinding>?>(null)
        backgroundScope.launch { health.observeRowHealth(refreshes).collect { map -> passes.update { it + listOf(map) } } }
        backgroundScope.launch { tags.observeAll().collect { tagRows.value = it } }

        passes.await("the first pass") { seen -> seen.lastOrNull()?.containsKey(AssetId("mow")) == true }
        tagRows.await("the tag row") { it?.size == 1 }
        val before = passes.value.size
        refreshes.update { it + 1 }
        passes.await("a refreshed pass") { it.size > before }

        assertEquals("zero writes anywhere", emptyList<String>(), writes)
        assertEquals("the stale row is untouched", statesBefore, graph.scheduleStates.all())
        // The pass did real work over the stale world: the mower's health, and its DOWN component.
        val mow = passes.value.last().getValue(AssetId("mow"))
        assertTrue(mow.result.aggregate != null)
        assertEquals(listOf(AssetId("pack")), mow.components.map { it.assetId })
    }

    private suspend fun <T> StateFlow<T>.await(what: String, until: (T) -> Boolean) {
        withContext(Dispatchers.Default) { withTimeoutOrNull(5_000L) { first(until) } }
            ?: throw AssertionError("$what: never emitted; the last emission was $value")
    }

    /**
     * M12 (plan decision 47): the graph's delivery seam — what `ScheduleDeliveryFacts` and
     * `ReminderHealthCheck` read — answers today's phase for a row computed yesterday across the
     * season boundary, and leaves the stored row as it was.
     */
    @Test fun theDeliverySeamReadsFreshStateAndWritesNothing() = runTest {
        seedAStaleWorld()
        val before = graph.scheduleStates.all()

        val read = graph.scheduleStateReader.stateOf(ScheduleId("s-mow"))!!

        assertEquals(PolicyPhase.ACTIVE, read.policyPhase)
        assertEquals("2026-04-15", read.computedForOn)
        assertEquals("a missing row is derived too", "2026-04-15", graph.scheduleStateReader.stateOf(ScheduleId("s-new"))?.computedForOn)
        assertEquals("nothing was written", before, graph.scheduleStates.all())
    }

    /**
     * Review M2: the scan sheet's last-completion seam reads through the same accessor, so right
     * after the 7 → 8 migration — `schedule_state` recreated empty — the sheet still finds the last
     * completion (and so its readings), and finding it writes nothing.
     */
    @Test fun theSheetsLastCompletionReadsThroughTheSeam() = runTest {
        graph.today = LocalDate.parse("2026-04-15")
        graph.now = dayMillis("2026-04-15")
        graph.assets.upsert(assetRow("gen", name = "Generator"))
        graph.schedules.upsert(scheduleOf("s-gen", assetId = "gen", title = "Engine oil service", anchorOn = "2026-01-01", leadDays = 0))
        graph.recomputeSchedules.forSchedule(ScheduleId("s-gen"))
        val done = graph.completeSchedule.run(ScheduleId("s-gen"), CompletionCommand(occurredOn = "2026-04-15", tzId = "UTC"))
        graph.scheduleStates.deleteAll()

        assertEquals(done.id, graph.lastCompletionEventId.of(ScheduleId("s-gen")))
        assertEquals("nothing was written back", emptyList<ScheduleState>(), graph.scheduleStates.all())
    }

    /**
     * #79 (C10, C11; §3 row 29): the asset detail's load and the asset editor's load, over an asset in
     * warranty with a reminder lead, write nothing — and the editor's load neither sweeps nor asks.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theAssetDetailAndEditorLoadsWriteNothing() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        // The two view models live in a store the test clears before Main goes back: this graph's
        // queries run on real threads, and a scope left running would resume on a Main that is gone.
        val store = ViewModelStore()
        fun <T : ViewModel> held(model: T): T = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <V : ViewModel> create(modelClass: Class<V>): V = model as V
            },
        )[model::class.java.name, model::class.java]
        try {
            graph.today = LocalDate.parse("2028-01-10")
            graph.assets.upsert(
                assetRow("heater", name = "Example Heater").copy(
                    warrantyExpiresOn = "2028-06-30", warrantyReminderLeadDays = 30, warrantyNotes = "Parts only",
                ),
            )
            val recompute = RecomputeSchedules(
                schedules, states, events, closures, groups, assets, activations, graph.todayPort, graph.clock,
                zone = { ZoneOffset.UTC },
            )
            val health = AssetHealthReadModel(
                assets, subjects, schedules, states, events, profiles, activations, conditions, recompute, graph.todayPort,
                zone = { ZoneOffset.UTC },
                transfers = graph.transferRecords,
            )
            val due = DueReadModel(
                schedules, assets, groups, definitions, recompute, graph.todayPort, health,
                snoozedUntilOf = { delivery.get(it)?.snoozedUntilAt },
                transfers = graph.transferRecords,
            )
            var sweeps = 0
            val asked = mutableListOf<String>()
            val permission = object : NotificationPermission {
                override fun granted(): Boolean = false
                override fun shouldExplain(): Boolean = false
                override suspend fun request(): Boolean = false.also { asked += "request" }
            }
            val detail = held(AssetDetailViewModel(
                assets, tags, definitions, profiles, events, schedules, states, groups, due, conditions, activations,
                subjects, attachments, health, GetAssetSeason(assets, activations, graph.uow, graph.todayPort),
                graph.recordSeasonActivation, graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
                graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, AssetId("heater"),
            ))
            val editor = held(AssetEditViewModel(
                assets, subjects, graph.saveAssetSettings, schedules, categories, attachments,
                graph.attachmentStorage, graph.addAttachment, graph.todayPort, AssetId("heater"),
                notifications = permission,
                reconcile = { sweeps++ },
            ))
            val page = backgroundScope.launch { detail.state.collect {} }
            detail.state.await("the detail page") { it != null }
            editor.state.await("the editor's form") { it.parentChoices.isNotEmpty() }

            assertEquals("zero writes anywhere", emptyList<String>(), writes)
            assertEquals("no sweep and no request on a load", 0 to emptyList<String>(), sweeps to asked)
            // The loads did real work: the section's facts, and the lead as the field shows it.
            assertEquals(WarrantyStatus.IN_WARRANTY, detail.state.value!!.warranty.status)
            assertEquals("Reminder: 30 days before", detail.state.value!!.warranty.reminderLine)
            assertEquals("30", editor.state.value.warrantyLead)
            page.cancel()
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    /**
     * #79 (C20–C23; §3 row 51): the Service cases section's load (the detail), the case screen's, the
     * case editor's — new on an Incident, and an edit — and the Incident detail's with its case links,
     * over a case with a timeline and a repair record, write nothing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theServiceCaseLoadsWriteNothing() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        fun <T : ViewModel> held(model: T): T = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <V : ViewModel> create(modelClass: Class<V>): V = model as V
            },
        )["${model::class.java.name}-${System.identityHashCode(model)}", model::class.java]
        try {
            graph.today = LocalDate.parse("2028-07-15")
            graph.assets.upsert(assetRow("heater", name = "Example Heater").copy(currency = "EUR", warrantyExpiresOn = "2028-06-30"))
            graph.events.upsert(replacementOf("r1", "heater", "2028-07-10"))
            val incident = replacementOf("inc-1", "heater", "2028-07-01").copy(kind = EventKind.INCIDENT, title = "Will not heat")
            graph.events.upsert(incident)
            val opened = graph.openServiceCase.run(
                AssetId("heater"),
                ServiceCaseCommand(
                    title = "Heater claim", type = CaseType.WARRANTY_SERVICE, openedOn = "2028-07-02",
                    coverage = CaseCoverage.IN_WARRANTY, resolutionEventId = EventId("r1"),
                ),
                incident.id,
            )
            graph.addServiceCaseEntry.run(opened.id, CaseEntryCommand("2028-07-03", null, "UTC", "Shipped", CaseStatus.SENT_OUT))

            val recompute = RecomputeSchedules(
                schedules, states, events, closures, groups, assets, activations, graph.todayPort, graph.clock,
                zone = { ZoneOffset.UTC },
            )
            val health = AssetHealthReadModel(
                assets, subjects, schedules, states, events, profiles, activations, conditions, recompute, graph.todayPort,
                zone = { ZoneOffset.UTC },
                transfers = graph.transferRecords,
            )
            val due = DueReadModel(
                schedules, assets, groups, definitions, recompute, graph.todayPort, health,
                snoozedUntilOf = { delivery.get(it)?.snoozedUntilAt },
                transfers = graph.transferRecords,
            )
            val detail = held(AssetDetailViewModel(
                assets, tags, definitions, profiles, events, schedules, states, groups, due, conditions, activations,
                subjects, attachments, health, GetAssetSeason(assets, activations, graph.uow, graph.todayPort),
                graph.recordSeasonActivation, graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
                graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, AssetId("heater"),
                serviceCases = serviceCases,
            ))
            val screen = held(ServiceCaseViewModel(
                serviceCases, caseEntries, events, graph.updateServiceCase, graph.addServiceCaseEntry,
                graph.todayPort, opened.id,
            ))
            val fresh = held(ServiceCaseEditViewModel(
                assets, events, serviceCases, graph.openServiceCase, graph.updateServiceCase, graph.todayPort,
                AssetId("heater"), null, incident.id,
            ))
            val edit = held(ServiceCaseEditViewModel(
                assets, events, serviceCases, graph.openServiceCase, graph.updateServiceCase, graph.todayPort,
                AssetId("heater"), opened.id, null,
            ))
            val incidentDetail = held(EventDetailViewModel(
                events, definitions, assets, graph.deleteEvent, incident.id, caseLinksOf(events, serviceCases),
            ))
            val page = backgroundScope.launch { detail.state.collect {} }
            val case = backgroundScope.launch { screen.state.collect {} }
            val entry = backgroundScope.launch { incidentDetail.state.collect {} }
            detail.state.await("the detail page") { it?.cases?.isNotEmpty() == true }
            screen.state.await("the case screen") { it?.timeline?.isNotEmpty() == true && it.repair != null }
            fresh.state.await("the new case form") { it.loaded }
            edit.state.await("the edit form") { it.loaded }
            incidentDetail.state.await("the Incident detail") { it != null }
            // Delete, tapped: the confirm's one read, and nothing is deleted or written.
            incidentDetail.askDelete()
            incidentDetail.deleteConfirm.await("the delete confirm") { it != null }

            assertEquals("zero writes anywhere", emptyList<String>(), writes)
            // The loads did real work: the section's row, the timeline, both forms and the link check.
            assertEquals(listOf("Sent out · In warranty"), detail.state.value!!.cases.map { it.line })
            assertEquals("Battery replaced", screen.state.value!!.repair!!.title)
            assertEquals("Will not heat", fresh.state.value.title)
            assertEquals("Heater claim", edit.state.value.title)
            assertTrue(incidentDetail.deleteConfirm.value!!.linkedByCase)
            page.cancel()
            case.cancel()
            entry.cancel()
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    /**
     * #72 (C16–C20; §3 row 34): the Lending section's load (the detail and its plate), the Assets list's,
     * the Dashboard's and the lend form's — new, and an edit of the open loan — over an asset with an
     * overdue open loan and a returned one, write nothing; no load sweeps or asks.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theLoanLoadsWriteNothing() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        fun <T : ViewModel> held(model: T): T = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <V : ViewModel> create(modelClass: Class<V>): V = model as V
            },
        )["${model::class.java.name}-${System.identityHashCode(model)}", model::class.java]
        try {
            graph.today = LocalDate.parse("2026-09-20")
            graph.assets.upsert(assetRow("drill", name = "Example Drill"))
            graph.assets.upsert(assetRow("ladder", name = "Example Ladder"))
            graph.loans.upsert(loanRow("l-open", "drill", lentOn = "2026-09-01", dueOn = "2026-09-10", mode = LoanReminderMode.ONCE))
            graph.loans.upsert(loanRow("l-back", "drill", lentOn = "2026-06-01", returnedOn = "2026-07-01"))

            val recompute = RecomputeSchedules(
                schedules, states, events, closures, groups, assets, activations, graph.todayPort, graph.clock,
                zone = { ZoneOffset.UTC },
            )
            val health = AssetHealthReadModel(
                assets, subjects, schedules, states, events, profiles, activations, conditions, recompute, graph.todayPort,
                zone = { ZoneOffset.UTC },
                transfers = graph.transferRecords,
            )
            val due = DueReadModel(
                schedules, assets, groups, definitions, recompute, graph.todayPort, health,
                snoozedUntilOf = { delivery.get(it)?.snoozedUntilAt },
                transfers = graph.transferRecords,
            )
            var sweeps = 0
            val asked = mutableListOf<String>()
            val permission = object : NotificationPermission {
                override fun granted(): Boolean = false
                override fun shouldExplain(): Boolean = false
                override suspend fun request(): Boolean = false.also { asked += "request" }
            }
            val detail = held(AssetDetailViewModel(
                assets, tags, definitions, profiles, events, schedules, states, groups, due, conditions, activations,
                subjects, attachments, health, GetAssetSeason(assets, activations, graph.uow, graph.todayPort),
                graph.recordSeasonActivation, graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
                graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, AssetId("drill"),
                loans = loans,
            ))
            val list = held(AssetsViewModel(assets, categories, activations, tags, health, graph.todayPort, loans = loans))
            val dashboard = held(DashboardViewModel(
                assets, schedules, states, due, AttentionReadModel(assets, health, graph.todayPort, transfers = graph.transferRecords), health,
                NoHealthFindings, graph.prefs, graph.transferRecords, loans = loans, today = graph.todayPort,
            ))
            fun editor(loanId: String?) = held(LoanEditViewModel(
                assets, loans, graph.lendAsset, graph.updateLoan, fakeContactReader(), graph.todayPort,
                AssetId(if (loanId == null) "ladder" else "drill"), loanId?.let(::AssetLoanId),
                io = UnconfinedTestDispatcher(testScheduler),
                notifications = permission,
                reconcile = { sweeps++ },
            ))
            val fresh = editor(null)
            val edit = editor("l-open")
            val page = backgroundScope.launch { detail.state.collect {} }
            val rows = backgroundScope.launch { list.state.collect {} }
            val board = backgroundScope.launch { dashboard.state.collect {} }
            detail.state.await("the detail page") { it?.loans?.open != null }
            list.state.await("the list") { it.items.any { row -> row.loan != null } }
            dashboard.state.await("the dashboard") { it.sections.any { group -> group.entries.any { e -> e is SectionEntry.Loan } } }
            fresh.state.await("the new loan form") { it.loaded }
            edit.state.await("the edit form") { it.loaded }

            assertEquals("zero writes anywhere", emptyList<String>(), writes)
            assertEquals("no sweep and no request on a load", 0 to emptyList<String>(), sweeps to asked)
            // The loads did real work: the block, the plate, the row, the Dashboard row and both forms.
            assertEquals(LoanStanding.OVERDUE, detail.state.value!!.loans.standing)
            assertEquals(1, detail.state.value!!.loans.history.size)
            assertEquals(LoanStanding.OVERDUE, list.state.value.items.single { it.asset.name == "Example Drill" }.loan)
            assertEquals("2026-09-10", edit.state.value.dueOn)
            assertEquals("2026-09-20", fresh.state.value.lentOn)
            page.cancel()
            rows.cancel()
            board.cancel()
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    // ---------------------------------------------------------------- recording repositories

    private fun write(what: String) {
        writes += what
    }

    private val loans = object : AssetLoanRepository by graph.loans {
        override suspend fun upsert(loan: AssetLoan) = write("loan.upsert").also { graph.loans.upsert(loan) }
        override suspend fun deleteAll() = write("loan.deleteAll").also { graph.loans.deleteAll() }
    }

    private val serviceCases = object : ServiceCaseRepository by graph.serviceCases {
        override suspend fun upsert(case: ServiceCase) = write("case.upsert").also { graph.serviceCases.upsert(case) }
        override suspend fun deleteAll() = write("case.deleteAll").also { graph.serviceCases.deleteAll() }
    }
    private val caseEntries = object : ServiceCaseEntryRepository by graph.serviceCaseEntries {
        override suspend fun insert(entry: ServiceCaseEntry) = write("entry.insert").also { graph.serviceCaseEntries.insert(entry) }
        override suspend fun deleteAll() = write("entry.deleteAll").also { graph.serviceCaseEntries.deleteAll() }
    }

    private val assets = object : AssetRepository by graph.assets {
        override suspend fun upsert(asset: Asset) = write("asset.upsert").also { graph.assets.upsert(asset) }
        override suspend fun delete(id: AssetId) = write("asset.delete").also { graph.assets.delete(id) }
        override suspend fun deleteAll() = write("asset.deleteAll").also { graph.assets.deleteAll() }
    }
    private val groups = object : GroupRepository by graph.groups {
        override suspend fun upsert(group: MaintenanceGroup) = write("group.upsert").also { graph.groups.upsert(group) }
        override suspend fun deleteAll() = write("group.deleteAll").also { graph.groups.deleteAll() }
    }
    private val definitions = object : DefinitionRepository by graph.definitions {
        override suspend fun upsert(d: MeasurementDefinition) = write("definition.upsert").also { graph.definitions.upsert(d) }
        override suspend fun delete(id: DefinitionId) = write("definition.delete").also { graph.definitions.delete(id) }
        override suspend fun deleteAll() = write("definition.deleteAll").also { graph.definitions.deleteAll() }
    }
    private val profiles = object : ProfileRepository by graph.profiles {
        override suspend fun upsert(p: EventProfile) = write("profile.upsert").also { graph.profiles.upsert(p) }
        override suspend fun delete(id: ProfileId) = write("profile.delete").also { graph.profiles.delete(id) }
        override suspend fun deleteAll() = write("profile.deleteAll").also { graph.profiles.deleteAll() }
    }
    private val events = object : EventRepository by graph.events {
        override suspend fun upsert(e: AssetEvent) = write("event.upsert").also { graph.events.upsert(e) }
        override suspend fun delete(id: EventId) = write("event.delete").also { graph.events.delete(id) }
        override suspend fun deleteAll() = write("event.deleteAll").also { graph.events.deleteAll() }
    }
    private val schedules = object : ScheduleRepository by graph.schedules {
        override suspend fun upsert(schedule: MaintenanceSchedule) = write("schedule.upsert").also { graph.schedules.upsert(schedule) }
        override suspend fun deleteAll() = write("schedule.deleteAll").also { graph.schedules.deleteAll() }
    }
    private val closures = object : ClosureRepository by graph.closures {
        override suspend fun insert(closure: OccurrenceClosure) = write("closure.insert").also { graph.closures.insert(closure) }
    }
    private val states = object : ScheduleStateRepository by graph.scheduleStates {
        override suspend fun upsert(state: ScheduleState) = write("state.upsert").also { graph.scheduleStates.upsert(state) }
        override suspend fun deleteAll() = write("state.deleteAll").also { graph.scheduleStates.deleteAll() }
    }
    private val activations = object : SeasonActivationRepository by graph.seasonActivations {
        override suspend fun insert(row: SeasonActivation) = write("activation.insert").also { graph.seasonActivations.insert(row) }
    }
    private val conditions = object : ConditionRepository by graph.conditions {
        override suspend fun insert(row: AssetCondition) = write("condition.insert").also { graph.conditions.insert(row) }
    }
    private val subjects = object : HealthSubjectRepository by graph.healthSubjects {
        override suspend fun upsert(subject: HealthSubject) = write("subject.upsert").also { graph.healthSubjects.upsert(subject) }
    }
    private val tags = object : TagRepository by graph.tags {
        override suspend fun upsert(tag: TagBinding) = write("tag.upsert").also { graph.tags.upsert(tag) }
        override suspend fun delete(id: TagId) = write("tag.delete").also { graph.tags.delete(id) }
        override suspend fun deleteAll() = write("tag.deleteAll").also { graph.tags.deleteAll() }
    }
    private val attachments = object : AttachmentRepository by graph.attachments {
        override suspend fun upsert(a: Attachment) = write("attachment.upsert").also { graph.attachments.upsert(a) }
        override suspend fun delete(id: AttachmentId) = write("attachment.delete").also { graph.attachments.delete(id) }
        override suspend fun deleteAll() = write("attachment.deleteAll").also { graph.attachments.deleteAll() }
    }
    private val categories = object : CategoryRepository by graph.categories {
        override suspend fun upsert(row: AssetCategory) = write("category.upsert").also { graph.categories.upsert(row) }
        override suspend fun delete(key: String) = write("category.delete").also { graph.categories.delete(key) }
        override suspend fun deleteAll() = write("category.deleteAll").also { graph.categories.deleteAll() }
    }
    private val delivery = object : ScheduleLocalDeliveryRepository by graph.scheduleLocalDelivery {
        override suspend fun upsert(row: ScheduleLocalDelivery) = write("delivery.upsert").also { graph.scheduleLocalDelivery.upsert(row) }
        override suspend fun deleteAll() = write("delivery.deleteAll").also { graph.scheduleLocalDelivery.deleteAll() }
    }
}
