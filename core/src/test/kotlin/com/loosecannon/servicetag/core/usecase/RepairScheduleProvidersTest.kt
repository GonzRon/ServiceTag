package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.testing.FakeUnitOfWork
import com.loosecannon.servicetag.core.testing.InMemoryScheduleRepository
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #80's repair (plan.md §3; rulings R2, R3, R5): one use case, a plan that writes nothing and an
 * apply that re-plans inside its own write and adds exactly `LOCAL enabled=true` to every ACTIVE,
 * reminders-on, providerless schedule — and to nothing else.
 *
 * The seeds go straight into the in-memory store, so [RecordingSchedules] sees only what the use
 * case itself reads and writes: an upsert it records is the use case's, and "written zero times" is
 * a count, not an inference from a field that happened not to move.
 */
class RepairScheduleProvidersTest {

    private var now = dayMillis("2026-09-25")
    private val clock = Clock { now }
    private val store = InMemoryScheduleRepository()
    private val uow = RecordingUnitOfWork(FakeUnitOfWork(store))
    private val schedules = RecordingSchedules(store, uow)
    private val repair = RepairScheduleProviders(schedules, uow, clock)

    private val local = ScheduleProviderRow(ProviderId.LOCAL.name, enabled = true)
    private val localOff = ScheduleProviderRow(ProviderId.LOCAL.name, enabled = false)

    /** A monthly schedule in the given shape; providerless and reminders on unless told otherwise. */
    private fun row(
        id: String,
        title: String = "Schedule $id",
        status: ScheduleStatus = ScheduleStatus.ACTIVE,
        remindersEnabled: Boolean = true,
        providers: List<ScheduleProviderRow> = emptyList(),
    ): MaintenanceSchedule = scheduleOf(
        id = id, title = title, status = status,
        timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01",
    ).copy(remindersEnabled = remindersEnabled, providers = providers)

    private suspend fun seed(vararg rows: MaintenanceSchedule) = rows.forEach { store.upsert(it) }

    private fun ids(vararg ids: String): List<ScheduleId> = ids.map(::ScheduleId)

    // --- the universe and the predicate -----------------------------------------------------------

    /** #80 AC 9: an ARCHIVED providerless row is never matched, never listed, never written. */
    @Test
    fun theUniverseIsTheNonArchivedRows() = runTest {
        val archived = row("s-archived", status = ScheduleStatus.ARCHIVED)
        seed(row("s-live"), archived)

        assertEquals(ids("s-live"), repair.plan().entries.map { it.scheduleId })
        val report = repair.apply()
        assertEquals(ids("s-live"), report.plan.entries.map { it.scheduleId })
        assertEquals(ids("s-live"), report.repaired)
        assertEquals(archived, store.get(archived.id))
    }

    /**
     * R3: matched is "reminders on, nothing enabled"; repairable is matched, ACTIVE and empty; a
     * skip names why, status first. Reminders off, or an enabled provider, is not matched at all.
     */
    @Test
    fun thePredicateMatrix() = runTest {
        seed(
            row("a", status = ScheduleStatus.ACTIVE),
            row("b", status = ScheduleStatus.PAUSED),
            row("c", status = ScheduleStatus.ACTIVE, providers = listOf(localOff)),
            row("d", status = ScheduleStatus.PAUSED, providers = listOf(localOff)),
            row("e", status = ScheduleStatus.ACTIVE, remindersEnabled = false),
            row("f", status = ScheduleStatus.ACTIVE, providers = listOf(local)),
        )

        val plan = repair.plan()

        assertEquals(
            listOf(
                "a" to null,
                "b" to ProviderRepairSkip.PAUSED,
                "c" to ProviderRepairSkip.PROVIDERS_DISABLED,
                "d" to ProviderRepairSkip.PAUSED,
            ),
            plan.entries.map { it.scheduleId.value to it.skip },
        )
        assertEquals(4, plan.matched)
        assertEquals(ids("a"), plan.repairable.map { it.scheduleId })
        assertEquals(ids("b", "c", "d"), plan.skipped.map { it.scheduleId })
        assertEquals(listOf("Schedule a", "Schedule b", "Schedule c", "Schedule d"), plan.entries.map { it.title })
    }

    // --- the apply ----------------------------------------------------------------------------------

    /**
     * R5: the repair moves `providers` and `updatedAt` and nothing else — not the profile, the
     * postponement, the rule-change floor, the policy, the rule or the created stamp.
     */
    @Test
    fun applyChangesOnlyProvidersAndUpdatedAt() = runTest {
        val before = scheduleOf(
            id = "s1", title = "Filter change", timeInterval = 3, timeUnit = RecurrenceUnit.MONTH,
            anchorOn = "2026-01-15", leadDays = 7, servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
            policyOffsetDays = 5, completionMode = CompletionMode.QUICK, profileId = "p-1",
            postponedDueOn = "2026-10-01", createdOn = "2026-01-01", updatedOn = "2026-03-01",
            ruleChangedOn = "2026-02-01",
        ).copy(description = "Rinse the cartridge first", providers = emptyList())
        seed(before)
        now = dayMillis("2026-09-25") + 1_234L

        repair.apply()

        val after = store.get(before.id)!!
        assertEquals(listOf(local), after.providers)
        assertEquals(now, after.updatedAt)
        assertEquals(before.copy(providers = listOf(local), updatedAt = after.updatedAt), after)
    }

    /**
     * R3 at the apply: every skipped, archived or reminders-off row is written **zero** times, so
     * its `updatedAt` cannot move either; the one repairable row is the one upsert.
     */
    @Test
    fun applyLeavesEverySkippedAndArchivedRowByteIdentical() = runTest {
        val others = listOf(
            row("s2", status = ScheduleStatus.PAUSED),
            row("s3", providers = listOf(localOff)),
            row("s4", status = ScheduleStatus.ARCHIVED),
            row("s5", remindersEnabled = false),
        )
        seed(row("s1"), *others.toTypedArray())
        now += 86_400_000L

        repair.apply()

        for (other in others) assertEquals(other, store.get(other.id), other.id.value)
        assertEquals(ids("s1"), schedules.upserts)
        assertEquals(listOf(local), store.get(ScheduleId("s1"))!!.providers)
    }

    /** Idempotent: nothing is left to repair, the skips stand, and a second apply writes nothing. */
    @Test
    fun applyThenPlanHasNoRepairableAndTheSameSkipped() = runTest {
        seed(
            row("s1"),
            row("s2"),
            row("s3", status = ScheduleStatus.PAUSED),
            row("s4", providers = listOf(localOff)),
        )

        val first = repair.apply()
        assertEquals(ids("s1", "s2"), first.repaired)
        assertEquals(ids("s1", "s2"), first.plan.repairable.map { it.scheduleId })

        val replan = repair.plan()
        assertEquals(emptyList(), replan.repairable)
        assertEquals(first.plan.skipped, replan.skipped)
        assertEquals(2, replan.matched)

        val writtenBefore = schedules.upserts.size
        val second = repair.apply()
        assertEquals(emptyList(), second.repaired)
        assertEquals(writtenBefore, schedules.upserts.size)
    }

    /** The plan is the gate a person reads before any write: it writes nothing and opens no write. */
    @Test
    fun planWritesNothing() = runTest {
        seed(row("s1"), row("s2", status = ScheduleStatus.PAUSED), row("s3", providers = listOf(localOff)))
        val before = store.all()

        val plan = repair.plan()

        assertEquals(1, plan.repairable.size)
        assertEquals(emptyList(), schedules.upserts)
        assertEquals(0, uow.writes)
        assertEquals(before, store.all())
    }

    /**
     * R2: the apply re-evaluates the current state inside its own write — the read that decides and
     * every upsert it decided on are all observed inside the one `uow.write`, never before it.
     */
    @Test
    fun applyReadsAndWritesInsideOneUnitOfWork() = runTest {
        seed(row("s1"), row("s2"), row("s3", status = ScheduleStatus.PAUSED))

        repair.apply()

        assertEquals(1, uow.writes)
        assertEquals(listOf("all@write", "upsert:s1@write", "upsert:s2@write"), schedules.observed)
    }

    /** Deterministic: entries and repairs are in (title, id) order, whatever order the store has. */
    @Test
    fun entriesAreOrderedByTitleThenId() = runTest {
        seed(
            row("s3", title = "Backwash"),
            row("s1", title = "Rinse"),
            row("s2", title = "Backwash"),
            row("s0", title = "Anode check", status = ScheduleStatus.PAUSED),
        )

        assertEquals(ids("s0", "s2", "s3", "s1"), repair.plan().entries.map { it.scheduleId })
        assertEquals(ids("s2", "s3", "s1"), repair.apply().repaired)
    }
}

/** A [UnitOfWork] that says whether a write is open, and counts the writes it opened. */
private class RecordingUnitOfWork(private val inner: UnitOfWork) : UnitOfWork {
    var inWrite = false
        private set
    var writes = 0
        private set

    override suspend fun <T> write(block: suspend () -> T): T {
        writes += 1
        return inner.write {
            inWrite = true
            try {
                block()
            } finally {
                inWrite = false
            }
        }
    }

    override suspend fun <T> read(block: suspend () -> T): T = inner.read(block)
}

/** The store, with every `all()` and `upsert` the use case makes recorded with where it happened. */
private class RecordingSchedules(
    private val inner: ScheduleRepository,
    private val uow: RecordingUnitOfWork,
) : ScheduleRepository by inner {
    val observed = mutableListOf<String>()
    val upserts = mutableListOf<ScheduleId>()

    override suspend fun all(): List<MaintenanceSchedule> {
        observed += "all${where()}"
        return inner.all()
    }

    override suspend fun upsert(schedule: MaintenanceSchedule) {
        observed += "upsert:${schedule.id.value}${where()}"
        upserts += schedule.id
        inner.upsert(schedule)
    }

    private fun where(): String = if (uow.inWrite) "@write" else "@outside"
}
