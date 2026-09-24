package com.loosecannon.servicetag.ui

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
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
import com.loosecannon.servicetag.core.ports.AssetRepository
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
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.conditionRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.replacementOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.testing.subjectRow
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.maintenance.AttentionReadModel
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import com.loosecannon.servicetag.ui.maintenance.ScanRoundMembership
import com.loosecannon.servicetag.ui.maintenance.ScanSheetOffer
import com.loosecannon.servicetag.ui.maintenance.scanSheetContentFor
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
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
            assets, subjects, schedules, events, profiles, activations, conditions, recompute, graph.todayPort,
            zone = { ZoneOffset.UTC },
        )
        val attention = AttentionReadModel(assets, conditions, health, graph.todayPort)
        val due = DueReadModel(
            schedules, assets, groups, definitions, recompute, graph.todayPort, health,
            snoozedUntilOf = { delivery.get(it)?.snoozedUntilAt },
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

    // ---------------------------------------------------------------- recording repositories

    private fun write(what: String) {
        writes += what
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
    private val delivery = object : ScheduleLocalDeliveryRepository by graph.scheduleLocalDelivery {
        override suspend fun upsert(row: ScheduleLocalDelivery) = write("delivery.upsert").also { graph.scheduleLocalDelivery.upsert(row) }
        override suspend fun deleteAll() = write("delivery.deleteAll").also { graph.scheduleLocalDelivery.deleteAll() }
    }
}
