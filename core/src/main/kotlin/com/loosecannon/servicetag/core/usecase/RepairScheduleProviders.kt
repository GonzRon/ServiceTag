package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.schedule.listedForDue

/** Why a matched schedule is left for a person rather than repaired. Status is judged first. */
enum class ProviderRepairSkip { PAUSED, PROVIDERS_DISABLED }

/** One matched schedule: repairable when [skip] is null, otherwise left alone for the named reason. */
data class ProviderRepairEntry(val scheduleId: ScheduleId, val title: String, val skip: ProviderRepairSkip?)

/** Every matched schedule, in (title, id) order. */
data class ProviderRepairPlan(val entries: List<ProviderRepairEntry>) {
    val repairable: List<ProviderRepairEntry> get() = entries.filter { it.skip == null }
    val skipped: List<ProviderRepairEntry> get() = entries.filter { it.skip != null }
    val matched: Int get() = entries.size
}

/** What an apply found inside its own write ([plan]) and the schedules it repaired, in plan order. */
data class ProviderRepairReport(val plan: ProviderRepairPlan, val repaired: List<ScheduleId>)

/**
 * #80's existing-data repair: the **one** canonical repair policy (R2) for a schedule whose reminders
 * are on and that no provider delivers — the state a `/v1` create with `providers` omitted stored
 * before 1.4.1. The Reminder Health action and the `/v1` + MCP plan/apply tooling are adapters over
 * this class; none of them restates the predicate.
 *
 * - **Universe:** the non-archived rows ([listedForDue]), the health check's own. An ARCHIVED row is
 *   never matched, listed or written.
 * - **Matched:** reminders on and no provider enabled.
 * - **Repairable (R3):** matched, `ACTIVE` and providerless. Anything else matched is skipped:
 *   not ACTIVE is [ProviderRepairSkip.PAUSED]; a non-empty set with nothing enabled is
 *   [ProviderRepairSkip.PROVIDERS_DISABLED] — a provider someone disabled is not for a bulk repair
 *   to second-guess.
 *
 * [plan] writes nothing. [apply] never replays a plan it was handed: it plans again **inside** its
 * one `uow.write`, then gives each repairable row exactly `LOCAL enabled=true` through the ordinary
 * repository upsert, moving `updatedAt` (R5) and carrying every other field by `copy` — the rule,
 * the policy, `ruleChangedAt`, `postponedDueOn`, `profileId`, `createdAt`. A skipped, archived or
 * reminders-off row is written zero times. It runs no recompute and no reminder sweep: a provider
 * row is not an input to derived state, and delivery catches up on the next reconcile.
 *
 * Deliberately **not** a `RepairAction.Automatic`: it runs only when a person asks for it, from
 * Reminder Health or through `/v1`, never unattended from the backstop.
 */
class RepairScheduleProviders(
    private val schedules: ScheduleRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    /** What an apply would do now. Writes nothing. */
    suspend fun plan(): ProviderRepairPlan = uow.read { planOf(schedules.all()) }

    /** Re-plans inside one write and repairs what that plan calls repairable, and nothing else. */
    suspend fun apply(): ProviderRepairReport = uow.write {
        val rows = schedules.all().associateBy { it.id }
        val plan = planOf(rows.values)
        val repaired = plan.repairable.map { entry ->
            val row = rows.getValue(entry.scheduleId)
            schedules.upsert(
                row.copy(
                    providers = listOf(ScheduleProviderRow(ProviderId.LOCAL.name, enabled = true)),
                    updatedAt = clock.nowMillis(),
                ),
            )
            entry.scheduleId
        }
        ProviderRepairReport(plan, repaired)
    }

    private fun planOf(rows: Collection<MaintenanceSchedule>): ProviderRepairPlan = ProviderRepairPlan(
        rows.toList().listedForDue()
            .filter { it.remindersEnabled && it.providers.none { provider -> provider.enabled } }
            .sortedWith(compareBy({ it.title }, { it.id.value }))
            .map { ProviderRepairEntry(it.id, it.title, skipOf(it)) },
    )

    private fun skipOf(row: MaintenanceSchedule): ProviderRepairSkip? = when {
        row.status != ScheduleStatus.ACTIVE -> ProviderRepairSkip.PAUSED
        row.providers.isNotEmpty() -> ProviderRepairSkip.PROVIDERS_DISABLED
        else -> null
    }
}
