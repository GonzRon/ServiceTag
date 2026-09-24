package com.loosecannon.servicetag.reminders

import android.content.Context
import androidx.work.WorkManager
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.reminders.ReminderHealthFinding
import com.loosecannon.servicetag.core.reminders.ReminderHealthSeverity
import com.loosecannon.servicetag.core.reminders.ReminderProvider
import com.loosecannon.servicetag.core.reminders.RepairAction
import com.loosecannon.servicetag.core.schedule.listedForDue
import com.loosecannon.servicetag.core.schedule.targetInService
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The identifier of each repair this app can offer, in **one** place.
 *
 * A [RepairAction] carries a code and nothing else, so the code is the whole of what a screen has
 * to go on: which automatic repair to run, which system screen to open, which **in-app action the
 * owner must take** — of which navigating somewhere is one kind and `TURN_REMINDERS_ON`, which
 * flips the owner's own switch on their explicit tap, is the other. Declared here rather than as
 * literals at each construction site because three of them are
 * built by [LocalReminderProvider] and all seven are labelled by the Health screen, and a code that
 * was spelled differently at those two ends would be a button that does nothing.
 *
 * **The two in-app destinations carry their target.** "Open the schedule" and "Log meter reading"
 * are useless without one, and neither the port's [RepairAction] nor the finding has a field for
 * it, so the code is written `"<action>:<scheduleId>"` and read back with [repairActionOf] /
 * [repairTargetOf]. This is an **internal** convention between this file and the Health screen: it
 * is not on the wire, not in the backup and not in the API (controller ruling, 2026-09-22).
 */
object ReminderRepair {
    const val OPEN_NOTIFICATION_SETTINGS = "OPEN_NOTIFICATION_SETTINGS"
    const val ARM_DIGEST_ALARM = "ARM_DIGEST_ALARM"
    const val ENQUEUE_BACKSTOP = "ENQUEUE_BACKSTOP"
    const val OPEN_BATTERY_SETTINGS = "OPEN_BATTERY_SETTINGS"
    const val TURN_REMINDERS_ON = "TURN_REMINDERS_ON"
    const val OPEN_SCHEDULE = "OPEN_SCHEDULE"
    const val LOG_METER_READING = "LOG_METER_READING"

    /** What separates an action from the one thing it acts on. */
    const val TARGET_SEPARATOR = ":"
}

/** The action half of a repair code — the whole code when it carries no target. */
fun repairActionOf(code: String): String = code.substringBefore(ReminderRepair.TARGET_SEPARATOR)

/** The target half, or null for a repair that needs none. */
fun repairTargetOf(code: String): String? =
    code.substringAfter(ReminderRepair.TARGET_SEPARATOR, "").ifEmpty { null }

/**
 * The backstop's unique work, as a seam: whether one is pending, and the one call that puts it
 * there.
 *
 * Two methods rather than one "ensure": asking first is what makes the repair idempotent, and a
 * single call that enqueued unconditionally is precisely the duplicate periodic work #27's repair
 * policy forbids. Both are blocking platform reads, which is why [ReminderHealthCheck] runs them
 * off the caller's thread.
 */
interface BackstopWork {
    fun enqueued(): Boolean
    fun enqueue()
}

/**
 * The real backstop, over WorkManager's own record of [BackstopWorker.UNIQUE_NAME].
 *
 * "Pending" means at least one work info that has not finished: a periodic worker that the platform
 * cancelled, or that ran to completion and was never replaced, is finished, and a finished record
 * is not a scheduled check. [enqueue] is B06's own entry point, `KEEP` and all, so this class holds
 * no second opinion about the period or the flex window.
 */
class WorkManagerBackstop(private val context: Context) : BackstopWork {

    override fun enqueued(): Boolean =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWork(BackstopWorker.UNIQUE_NAME)
            .get()
            .any { !it.state.isFinished }

    override fun enqueue() = BackstopWorker.enqueue(context)
}

/**
 * One health pass as a background run performs it: report, repair what is unambiguous, and **publish
 * the result** so the badge sees it.
 *
 * It exists so the worker drives the cached summary rather than the bare check (fix round 1, S3): a
 * repair the worker applies has to reach the cache the badge reads, or the badge stays lit until the
 * next launch. Declared here, in the package the worker lives in, and implemented by the summary in
 * `ui/maintenance/` — so the dependency points from the UI at the delivery platform and not back.
 */
fun interface ReminderHealthRun {
    suspend fun runAndRepair(): List<ReminderHealthFinding>
}

/**
 * How [BackstopWorker] reaches the health run, for the same reason [ReminderRunDispatch] exists:
 * a worker is constructed by WorkManager and never through `AppGraph`. `ServiceTagApp.onCreate`
 * assigns it synchronously, before any `doWork` can run.
 */
object ReminderHealthDispatch {
    @Volatile
    var run: ReminderHealthRun? = null
}

/**
 * #27 — what is wrong with this phone's ability to remind, and what the app may fix by itself.
 *
 * Seven findings, and the policy is visible in the shape: [run] reports, [repair] acts, and it acts
 * **only** on an automatic repair — the two that are unambiguous and idempotent, re-arming the
 * alarm and re-enqueuing the worker. Everything else takes the owner somewhere to decide. **No
 * conflict is ever repaired** (invariant 50): nothing here reads a merge plan, has a merge plan in
 * its constructor, or could reach one.
 *
 * **The three provider-side findings are read from [ReminderProvider.health], never re-derived**
 * (controller carry-forward, 2026-09-22). `NOTIFICATIONS_BLOCKED`, `REMINDERS_GLOBALLY_OFF` and
 * `DIGEST_ALARM_MISSING` are facts B06's provider already holds, and their ratified sentences
 * (master plan §17.1a) are therefore written **once** in this repository, in
 * [LocalReminderProvider]. A second detector here would be a second answer to the same question and
 * a second copy of the same sentence.
 *
 * **Derived state arrives through a read-only seam.** [ScheduleStateReader] has one method and no
 * writer, so "the health check cannot move a due date" is a property of the type (invariant 17) —
 * and B06's own structural test forbids every file under `reminders/` from so much as naming the
 * port that has a write method, this file included.
 *
 * Every store query starts from `listedForDue()`: an archived schedule appears in no finding, for
 * the same reason it appears in no due total.
 *
 * Where it runs is fixed (master plan decision 32): app launch, the backstop worker, and the Health
 * screen. **Not** on every dashboard emission — [platform] reads the standby bucket, and a badge
 * that re-read it on every keystroke is what the decision exists to prevent, which is why the
 * `HealthSummary` over this is a cache rather than a passthrough.
 */
class ReminderHealthCheck(
    private val provider: ReminderProvider,
    private val platform: PlatformState,
    private val backstop: BackstopWork,
    private val alarm: DigestAlarm,
    private val schedules: ScheduleRepository,
    private val states: ScheduleStateReader,
    /**
     * Read for one thing only: whether what a schedule is aimed at is still in service. The bound
     * itself is the domain's single `targetInService`, never re-derived here (decision 27).
     */
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    /**
     * Where the blocking platform reads happen. The standby bucket, the pending-alarm query and
     * WorkManager's future are all binder calls, and every caller of this class is on a scope whose
     * default dispatcher is the main thread.
     */
    private val io: CoroutineContext = Dispatchers.IO,
) {

    /**
     * Every finding, **worst first**, so the position on the screen carries the severity as well as
     * the icon and the wording do — never colour alone (D12 §5).
     */
    suspend fun run(): List<ReminderHealthFinding> = withContext(io) {
        buildList {
            addAll(provider.health())
            if (!backstop.enqueued()) {
                add(
                    ReminderHealthFinding(
                        code = "BACKSTOP_WORK_MISSING",
                        severity = ReminderHealthSeverity.WARN,
                        message = "The background safety check is not running, " +
                            "so a missed reminder would not be caught.",
                        repair = RepairAction.Automatic(ReminderRepair.ENQUEUE_BACKSTOP),
                    ),
                )
            }
            if (platform.appRestricted() != AppRestriction.NORMAL) {
                add(
                    ReminderHealthFinding(
                        code = "APP_RESTRICTED",
                        severity = ReminderHealthSeverity.WARN,
                        // One sentence for both restricted states, because the cost is the same
                        // either way; which of the two a phone that is both gets is settled
                        // upstream by `appRestrictionOf`'s ratified battery-first precedence. It is
                        // never repaired, and it never asks for an exemption the app does not need
                        // (#24). On Android 13+ a restricted app may also not receive
                        // BOOT_COMPLETED until it is opened again — documented, not gated
                        // (master plan §12).
                        message = "This phone is holding ServiceTag back in the background, " +
                            "so reminders may arrive late or not at all.",
                        repair = RepairAction.OpenSystemSettings(ReminderRepair.OPEN_BATTERY_SETTINGS),
                    ),
                )
            }
            addAll(scheduleFindings())
        }.sortedByDescending { it.severity }
    }

    /**
     * Applies the finding's repair if — and only if — the app is certain about it.
     *
     * Everything that makes this safe is here: the gate is the **type**, so a repair the owner has
     * to decide cannot be run on their behalf however its code reads; each branch asks before it
     * acts, so a second run changes nothing (#27 AC 3); both branches go through B06's own entry
     * points, so this holds no second opinion about an alarm instant or a worker period; and
     * nothing in it writes derived state (invariant 17) or resolves anything (invariant 50).
     *
     * An unknown automatic code is a no-op rather than an error: a finding this build does not know
     * how to repair is a finding to show, not a crash.
     *
     * Answers whether this was a repair the app owns, which is what [runAndRepair] needs and the one
     * question a caller could not ask without re-deciding the policy itself.
     */
    suspend fun repair(finding: ReminderHealthFinding): Boolean {
        val repair = finding.repair
        if (repair !is RepairAction.Automatic) return false
        return withContext(io) {
            when (repairActionOf(repair.code)) {
                ReminderRepair.ARM_DIGEST_ALARM -> {
                    if (!alarm.armed()) alarm.arm()
                    true
                }
                ReminderRepair.ENQUEUE_BACKSTOP -> {
                    if (!backstop.enqueued()) backstop.enqueue()
                    true
                }
                // A code this build cannot act on is answered honestly, so `runAndRepair` does not
                // pay for a second pass that changed nothing (fix round 1, nit 9).
                else -> false
            }
        }
    }

    /**
     * One pass: report, repair what is unambiguous, and answer with the state **after** the
     * repairs. This is the shape the backstop worker runs, where "the finding and its repair are one
     * pass" (#27's "Where it runs").
     *
     * The second [run] happens only when something was actually repaired, so a healthy phone pays
     * for exactly one pass.
     */
    suspend fun runAndRepair(): List<ReminderHealthFinding> {
        val found = run()
        var repaired = false
        found.forEach { if (repair(it)) repaired = true }
        return if (repaired) run() else found
    }

    /**
     * The two findings whose facts are in the store.
     *
     * **Both findings carry both lifecycle bounds** (fix round 1, S1/S2). `listedForDue()` drops an
     * archived schedule; [inService] drops a live schedule on a retired asset or an archived group;
     * and `status == ACTIVE` drops a paused one. Every clause earns its place: without the archive
     * bound every retired schedule raises a finding nothing can clear; without the target bound the
     * app reports a delivery problem for an obligation it has already withdrawn from delivery
     * (`BuildReminderSubjects` hands a retired one over as `Withdrawn`) and sends the owner to edit
     * a schedule on equipment they retired; and without `status == ACTIVE` a **paused** schedule is
     * told it "need[s] a meter reading before [it] can come due", which is false of a schedule that
     * cannot come due — §11.1's ruling is that a paused schedule needs nothing.
     *
     * `NO_DATA` is keyed on the **meter baseline**, not on the status word (master plan §17.1a's
     * constraint). `lastCompletedMeter` *is* the baseline: the recompute writes it as "the newest
     * completion's reading, or else `anchorMeter`", which is exactly the "neither a completion nor
     * `anchorMeter`" the finding is for. A group occurrence whose required set is empty reports the
     * same `NO_DATA` status word and gets **no finding, no meter sentence and no repair** — it is
     * not actionable at all (invariants 74, 77) — and keying off the status enum is the one mistake
     * that would tell the owner to log a reading for a group that obliges nobody.
     *
     * A schedule with **no derived row at all** is skipped: the recompute has not run for it yet,
     * and a finding derived from nothing would be a guess.
     */
    private suspend fun scheduleFindings(): List<ReminderHealthFinding> = buildList {
        val listed = inService(schedules.all().listedForDue())

        val undeliverable = listed.filter {
            it.status == ScheduleStatus.ACTIVE &&
                it.remindersEnabled &&
                it.providers.none(ScheduleProviderRow::enabled)
        }
        if (undeliverable.isNotEmpty()) {
            add(
                ReminderHealthFinding(
                    code = "SCHEDULE_NO_PROVIDER",
                    severity = ReminderHealthSeverity.WARN,
                    // RATIFIED verbatim, count-shaped (master plan §17.1a). The finding cannot name
                    // the schedule — the ratified sentence counts them and `ReminderHealthFinding` has no
                    // field for a name — so the repair is what reaches one, and the count is what
                    // says there are more.
                    message = "${undeliverable.size} schedules have reminders switched on " +
                        "but no way to deliver them.",
                    repair = RepairAction.OpenInApp(targeted(ReminderRepair.OPEN_SCHEDULE, undeliverable)),
                ),
            )
        }

        val withoutBaseline = listed.filter { schedule ->
            schedule.status == ScheduleStatus.ACTIVE &&
                schedule.meterDefinitionId != null &&
                states.stateOf(schedule.id).let { it != null && it.lastCompletedMeter == null }
        }
        if (withoutBaseline.isNotEmpty()) {
            add(
                ReminderHealthFinding(
                    code = "NO_DATA",
                    severity = ReminderHealthSeverity.WARN,
                    message = "${withoutBaseline.size} schedules need a meter reading " +
                        "before they can come due.",
                    repair = RepairAction.OpenInApp(targeted(ReminderRepair.LOG_METER_READING, withoutBaseline)),
                ),
            )
        }
    }

    /**
     * The target-in-service bound, applied once per run rather than once per schedule: both reads
     * happen here, and the predicate itself is the domain's single [targetInService] — never
     * re-derived, because two copies of one lifecycle bound is the drift decision 27 forbids and
     * would mean the badge carrying a finding for a schedule no list will show.
     */
    private suspend fun inService(rows: List<MaintenanceSchedule>): List<MaintenanceSchedule> {
        if (rows.isEmpty()) return rows
        val assetsById = assets.all().associateBy { it.id.value }
        val groupsById = groups.all().associateBy { it.id.value }
        return rows.filter { it.targetInService({ id -> assetsById[id.value] }, { id -> groupsById[id.value] }) }
    }

    /**
     * Which of the affected schedules the repair opens: the lowest id, so the same set always opens
     * the same one. Nothing in the spec or the plan ranks them, and an order that depended on the
     * repository's row order would send the owner somewhere different on every run.
     */
    private fun targeted(action: String, rows: List<MaintenanceSchedule>): String =
        "$action${ReminderRepair.TARGET_SEPARATOR}${rows.minOf { it.id.value }}"
}
