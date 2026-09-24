package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryAssetRepository
import com.loosecannon.servicetag.core.testing.InMemoryClosureRepository
import com.loosecannon.servicetag.core.testing.InMemoryDefinitionRepository
import com.loosecannon.servicetag.core.testing.InMemoryEventRepository
import com.loosecannon.servicetag.core.testing.InMemoryGroupRepository
import com.loosecannon.servicetag.core.testing.InMemoryProfileRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonActivationRepository
import com.loosecannon.servicetag.core.testing.dayMillis
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * #64 (inv. 87): `rule_changed_at` is written by a create and by a rule change and by nothing else,
 * and it is the never-terminated pin's floor. 1.3 floored the pin on `updated_at`, which every save
 * stamps, so a title or lead edit on a never-completed FIXED schedule moved its due date forward
 * with no completion recorded.
 *
 * The fixture: a monthly FIXED schedule anchored 10 January, created 1 January and never
 * terminated, postponed to 20 March; the edits land on 1 March.
 */
class SaveScheduleRuleFieldTest {

    private val assets = InMemoryAssetRepository()
    private val defs = InMemoryDefinitionRepository()
    private val profiles = InMemoryProfileRepository()
    private val events = InMemoryEventRepository()
    private val groups = InMemoryGroupRepository()
    private val closures = InMemoryClosureRepository()
    private val states = InMemoryScheduleStateRepository()
    private val schedules = InMemoryScheduleRepository(closures, states)
    private val uow = FakeUnitOfWork(assets, defs, profiles, events, groups, closures, schedules, states)

    private var seq = 0
    private val ids = IdGenerator { "id-${++seq}" }
    private var now = dayMillis("2026-01-01")
    private val clock = Clock { now }
    private var today = LocalDate.parse("2026-01-01")

    private val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, InMemorySeasonActivationRepository(),
        Today { today }, clock,
    ) { ZoneOffset.UTC }
    private val save = SaveSchedule(schedules, assets, groups, defs, profiles, uow, ids, clock, recompute)
    private val postpone = PostponeSchedule(schedules, uow, recompute)

    private val monthly = ScheduleCommand(
        targetAssetId = AssetId("a1"),
        targetGroupId = null,
        title = "Filter change",
        timeInterval = 1,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.FIXED,
        anchorOn = "2026-01-10",
        leadDays = 7,
        providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
    )

    /** Created 1 January, postponed to 20 March, and the clock moved to 1 March. */
    private suspend fun created(): ScheduleId {
        assets.upsert(Asset(id = AssetId("a1"), name = "Hot tub", createdAt = 1L, updatedAt = 1L))
        val id = save.run(null, monthly).id
        postpone.run(id, "2026-03-20")
        now = dayMillis("2026-03-01")
        today = LocalDate.parse("2026-03-01")
        return id
    }

    @Test
    fun aNonRuleEditMovesNothing() = runTest {
        val id = created()

        for ((label, edit) in listOf(
            "a title edit" to monthly.copy(title = "Filter swap"),
            "a policy edit" to monthly.copy(
                title = "Filter swap", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
            ),
            "a lead edit" to monthly.copy(
                title = "Filter swap", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
                leadDays = 3,
            ),
        )) {
            val saved = save.run(id, edit)
            assertEquals("2026-01-10", states.get(id)!!.computedDueOn, "computedDueOn after $label")
            assertEquals(dayMillis("2026-01-01"), saved.ruleChangedAt, "ruleChangedAt after $label")
            assertEquals("2026-03-20", saved.postponedDueOn, "the postponement after $label")
            // `updatedAt` is bookkeeping and still moves: the floor is simply not read from it.
            assertEquals(dayMillis("2026-03-01"), saved.updatedAt, "updatedAt after $label")
        }
    }

    @Test
    fun aRuleEditFloorsOnTheEditDateAndClearsThePostponement() = runTest {
        val id = created()

        val saved = save.run(id, monthly.copy(anchorOn = "2026-01-05"))

        assertEquals(dayMillis("2026-03-01"), saved.ruleChangedAt)
        assertNull(saved.postponedDueOn)
        // the first series date on or after the 1 March floor, not the re-anchored 5 January
        assertEquals("2026-03-05", states.get(id)!!.computedDueOn)
    }

    @Test
    fun aCreateStampsRuleChangedAtWithCreatedAt() = runTest {
        assets.upsert(Asset(id = AssetId("a1"), name = "Hot tub", createdAt = 1L, updatedAt = 1L))
        now = dayMillis("2026-01-01") + 34_200_000L

        val saved = save.run(null, monthly)

        assertEquals(now, saved.createdAt)
        assertEquals(saved.createdAt, saved.ruleChangedAt)
        assertEquals(saved.createdAt, schedules.get(saved.id)!!.ruleChangedAt)
    }
}
