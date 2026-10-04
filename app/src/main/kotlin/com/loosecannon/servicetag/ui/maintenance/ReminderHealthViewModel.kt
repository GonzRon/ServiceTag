package com.loosecannon.servicetag.ui.maintenance

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.reminders.ReminderHealthFinding
import com.loosecannon.servicetag.core.reminders.ReminderHealthSeverity
import com.loosecannon.servicetag.core.reminders.RepairAction
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.reminders.ReminderHealthCheck
import com.loosecannon.servicetag.reminders.ReminderHealthRun
import com.loosecannon.servicetag.reminders.ReminderRepair
import com.loosecannon.servicetag.reminders.repairActionOf
import com.loosecannon.servicetag.reminders.repairTargetOf
import com.loosecannon.servicetag.ui.condition.displayDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * #103 (1.7.1; P171-2 and P171-3, RATIFIED 2026-10-04): the Settings › Utilities row and the page's
 * title, one word for both, because the row that opens a page names it.
 */
const val REMINDER_HEALTH_TITLE = "Reminder health"

/** #103 (P171-4, RATIFIED): the healthy state's one sentence, drawn only once a run has found nothing. */
const val NO_PROBLEMS_FOUND = "No problems found."

/** #103 (P171-6, RATIFIED): the heading over the checks that passed. */
const val CHECKS_THAT_PASSED = "Checks that passed"

/**
 * #103 (P171-5, RATIFIED): `Last checked <date> at <time>` — [checkedAt] being the run's own instant
 * (owner ruling Q4), the date in the app's one display shape and the time in the phone's short form.
 */
fun lastCheckedLine(checkedAt: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
    val at = Instant.ofEpochMilli(checkedAt).atZone(zone)
    val time = at.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return "Last checked ${displayDate(at.toLocalDate())} at $time"
}

/**
 * The seven checks the reminder-health run performs, in the order the healthy state lists them, each
 * with the finding [codes] it raises and its RATIFIED passed line (#103; P171-7a…g, 2026-10-04). A
 * check passed when the run raised none of its codes; [DELIVERY] owns two, the batch shape and the
 * residual shape 1.4.1 split from it, because they are one question with two answers. Declared here,
 * beside the labels, so the page's words have one home.
 */
enum class HealthCheck(val codes: Set<String>, val passedLine: String) {
    NOTIFICATIONS(setOf("NOTIFICATIONS_BLOCKED"), "Notifications are allowed"),
    REMINDERS_ON(setOf("REMINDERS_GLOBALLY_OFF"), "Reminders are turned on"),
    DIGEST_ALARM(setOf("DIGEST_ALARM_MISSING"), "The daily reminder check is scheduled"),
    BACKSTOP(setOf("BACKSTOP_WORK_MISSING"), "The background safety check is running"),
    APP_RESTRICTION(setOf("APP_RESTRICTED"), "This phone is not holding ServiceTag back in the background"),
    DELIVERY(setOf("SCHEDULE_NO_PROVIDER", "SCHEDULE_PROVIDER_DISABLED"), "Every schedule with reminders can deliver them"),
    METER_BASELINES(setOf("NO_DATA"), "Every meter schedule has a baseline reading"),
}

/** The checks [findings] raised nothing for, in display order: derived from the run, never from a second check. */
fun passedChecks(findings: List<ReminderHealthFinding>): List<HealthCheck> =
    HealthCheck.entries.filter { check -> findings.none { it.code in check.codes } }

/**
 * The eight RATIFIED repair labels, keyed by the action half of a repair's code: master plan
 * §17.1a's seven — `NO_DATA`'s is D-24's already-ratified "Log meter reading", the other six were
 * ratified at the gate on 2026-09-22 — and 1.4.1's P141-2 for the delivery repair (ratified
 * 2026-09-25). Quoted verbatim, never paraphrased.
 *
 * Null for a code this build has no label for, which is how an unlabelled repair becomes a finding
 * shown with no button rather than a button saying nothing.
 */
fun repairLabel(code: String): String? = when (repairActionOf(code)) {
    ReminderRepair.OPEN_NOTIFICATION_SETTINGS -> "Open notification settings"
    ReminderRepair.ARM_DIGEST_ALARM -> "Reschedule the check"
    ReminderRepair.ENQUEUE_BACKSTOP -> "Restart the check"
    ReminderRepair.OPEN_BATTERY_SETTINGS -> "Open battery settings"
    ReminderRepair.TURN_REMINDERS_ON -> "Turn reminders on"
    ReminderRepair.OPEN_SCHEDULE -> "Open the schedule"
    ReminderRepair.LOG_METER_READING -> "Log meter reading"
    ReminderRepair.RESTORE_REMINDER_DELIVERY -> "Fix reminder delivery"
    else -> null
}

/**
 * What tapping a repair does, in the kinds the Health screen can actually perform.
 *
 * The screen switches on this and holds **no** repair policy of its own: which repairs may be run
 * on the owner's behalf is [ReminderHealthCheck]'s decision, carried here by whether the port's
 * action was [RepairAction.Automatic]. Two in-app repairs write instead of navigating, and only on
 * the owner's tap: [TurnRemindersOn] — #27's "one tap to enable" — writes B06's preference and
 * nothing else, and [RestoreReminderDelivery] (1.4.1, #80) runs the one canonical delivery repair.
 */
sealed interface HealthAction {
    data object Automatic : HealthAction
    data object NotificationSettings : HealthAction
    data object BatterySettings : HealthAction
    data object TurnRemindersOn : HealthAction
    data class OpenSchedule(val scheduleId: String) : HealthAction
    data class LogMeterReading(val scheduleId: String) : HealthAction
    data object RestoreReminderDelivery : HealthAction
}

/**
 * One finding as the screen draws it: the ratified sentence, the ratified button label, and what
 * the button does.
 *
 * [action] is null when there is nothing honest to offer — the finding is still worth showing, and
 * explaining a platform reality beats a button that cannot help.
 */
data class HealthRow(
    val finding: ReminderHealthFinding,
    val label: String?,
    val action: HealthAction?,
) {
    val code: String get() = finding.code
    val severity: ReminderHealthSeverity get() = finding.severity
    val message: String get() = finding.message
}

/**
 * The Health page's state.
 *
 * [loaded] distinguishes "nothing is wrong" from "nothing has been asked yet", and since 1.7.1 (#103)
 * the screen draws the difference: [healthy] — loaded and no row — is the ratified healthy state,
 * P171-4 over [checkedAt] and [passed]; "not asked yet" still draws nothing. [checkedAt] is the run's
 * own instant (owner ruling Q4), carried from the cache and never moved by a read; [passed] is the
 * checks that run raised nothing for, computed with the rows and always carried, drawn only when
 * [healthy] — a half-healthy list under a finding has no ratified words.
 */
data class HealthState(
    val rows: List<HealthRow> = emptyList(),
    val loaded: Boolean = false,
    val checkedAt: Long? = null,
    val passed: List<HealthCheck> = emptyList(),
) {
    /** The badge's own question, over the same findings the rows came from. */
    val worstSeverity: ReminderHealthSeverity? get() = rows.maxByOrNull { it.severity }?.severity

    /** The ratified healthy state: a run has answered, and it found nothing. */
    val healthy: Boolean get() = loaded && rows.isEmpty()
}

/**
 * The real [HealthSummary] (B08's decision 28), and the one cache behind it.
 *
 * **It caches, deliberately.** Master plan decision 32 fixes where the check runs — app launch, the
 * backstop worker, the Health screen — and *not* per dashboard emission, because the check reads
 * the standby bucket and queries WorkManager. Both badge surfaces ask [worstSeverity] on every
 * emission of their own flows, so this answers from the last [refresh] and never does platform work
 * of its own. Nothing cached means "not asked yet", which leaves the badge off: the honest answer
 * for a process that has not looked.
 */
class ReminderHealth(
    private val check: ReminderHealthCheck,
    /** The clock the run's instant is read from, once per publish. No default: a wiring that forgets it does not compile. */
    private val now: () -> Long,
) : HealthSummary, ReminderHealthRun {

    @Volatile
    private var cached: List<ReminderHealthFinding>? = null

    /**
     * #103 (owner ruling Q4): the instant the cached run completed — null until a run has — set only
     * by [publish], so it belongs to the findings beside it and never advances because a screen opened
     * or read it.
     */
    @Volatile
    var checkedAt: Long? = null
        private set

    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    override suspend fun worstSeverity(): ReminderHealthSeverity? = cached?.maxByOrNull { it.severity }?.severity

    /** Runs the check and caches what it found. The Health screen's own refresh, and launch's. */
    suspend fun refresh(): List<ReminderHealthFinding> = check.run().also(::publish)

    /**
     * One pass for a background run: report, repair the unambiguous, and cache the result.
     *
     * This — not the bare check — is what the backstop worker drives, so a repair it applies reaches
     * the badge instead of leaving it lit until the next launch (fix round 1, S3).
     */
    override suspend fun runAndRepair(): List<ReminderHealthFinding> = check.runAndRepair().also(::publish)

    /** Applies one automatic repair, and nothing else; the caller refreshes after it. */
    suspend fun repair(finding: ReminderHealthFinding) = check.repair(finding)

    /**
     * The cache, then the tick — in that order, because a surface woken by the tick reads the cache,
     * and a tick published first is a wake-up to the previous answer.
     */
    private fun publish(findings: List<ReminderHealthFinding>) {
        cached = findings
        checkedAt = now()
        _changes.update { it + 1 }
    }
}

/**
 * The Reminder health page (#27, spec §5.8; under Settings › Utilities since 1.7.1, #103).
 *
 * It reports and repairs and decides nothing: the findings, their severities and which of them may
 * be repaired automatically are all [ReminderHealthCheck]'s, and this turns each one into a row with
 * its ratified label. The three repairs it performs itself are the automatic one — delegated
 * straight back to the check — #27's one-tap "Turn reminders on", which writes B06's preference,
 * and #80's "Fix reminder delivery", which runs the one canonical delivery repair. The other four
 * take the owner somewhere: two system screens and two in-app destinations, both performed by the
 * screen.
 */
class ReminderHealthViewModel(
    private val health: ReminderHealth,
    private val prefs: AppPrefs,
    /**
     * #80's one canonical repair — the use case the `/v1` and MCP adapters also call, so there is
     * one repair policy (R2). A seam, placed before [resumeDelivery] and with no default, so a
     * wiring that forgot it does not compile.
     */
    private val restoreDelivery: suspend () -> Unit,
    /**
     * B06's own reconcile — the same entry point the backstop worker and every platform receiver
     * drive. A seam rather than the type, because nothing about this view model is Android-shaped
     * and the one thing it needs from the delivery path is "make the shade match the schedules
     * again".
     */
    private val resumeDelivery: suspend () -> Unit,
) : ViewModel() {

    constructor(graph: AppGraph) : this(
        graph.reminderHealth,
        graph.prefs,
        { graph.repairScheduleProviders.apply() },
        { graph.reminderRuns.reconcileAll() },
    )

    private val _state = MutableStateFlow(HealthState())
    val state: StateFlow<HealthState> = _state.asStateFlow()

    /**
     * Re-run the check.
     *
     * There is deliberately no run in `init`: the screen asks on every `ON_START`, which covers both
     * arriving here and coming back from the system settings a repair sent the owner to, and a
     * constructor run on top of that would read the standby bucket twice for one screen open.
     */
    fun refresh() {
        viewModelScope.launch { emit(health.refresh()) }
    }

    /**
     * The three repairs this view model performs. Anything else on the row is the screen's to do,
     * and a row with no action at all falls through without a refresh — there is nothing to have
     * changed.
     */
    fun repair(row: HealthRow) {
        viewModelScope.launch {
            when (row.action) {
                HealthAction.Automatic -> health.repair(row.finding)
                HealthAction.TurnRemindersOn -> {
                    prefs.remindersEnabled = true
                    // The write alone restores nothing (fix round 1, S4): B06 **takes down what it
                    // was showing** when the switch goes off, so without this the one tap would put
                    // nothing back until the next digest alarm — up to a day — or the next backstop.
                    // `reconcile` receives the whole desired state, so driving it here is the same
                    // call the worker makes and has no second effect of its own.
                    resumeDelivery()
                }
                HealthAction.RestoreReminderDelivery -> restoreThenResume()
                else -> return@launch
            }
            emit(health.refresh())
        }
    }

    /**
     * The repair writes first; the sweep runs after it, so the schedules it just gave a delivery
     * row are delivered now rather than at the next digest or backstop — the same reason
     * [HealthAction.TurnRemindersOn] sweeps — and the caller's refresh comes last, so the row
     * clears within the same tap.
     *
     * A repair that throws is logged and nothing more: no sweep follows a write that did not
     * happen, the refresh still runs, and the finding simply stays. Nothing is drawn for it — no
     * sentence for a failed repair is ratified — and what it repaired is not drawn either.
     */
    private suspend fun restoreThenResume() {
        try {
            restoreDelivery()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the delivery repair failed; the finding stays", e)
            return
        }
        resumeDelivery()
    }

    private fun emit(findings: List<ReminderHealthFinding>) {
        _state.value = HealthState(
            rows = findings.map(::rowOf),
            loaded = true,
            checkedAt = health.checkedAt,
            passed = passedChecks(findings),
        )
    }

    private fun rowOf(finding: ReminderHealthFinding): HealthRow {
        val repair = finding.repair
        return HealthRow(
            finding = finding,
            label = repair?.code?.let(::repairLabel),
            action = repair?.let(::actionOf),
        )
    }

    /**
     * The port's three repair kinds, narrowed to what this screen can perform. An `OpenInApp` or
     * `OpenSystemSettings` code this build does not recognise yields no action, so the row is drawn
     * without a button rather than with one that does nothing.
     */
    private fun actionOf(repair: RepairAction): HealthAction? = when (repair) {
        is RepairAction.Automatic -> HealthAction.Automatic
        is RepairAction.OpenSystemSettings -> when (repairActionOf(repair.code)) {
            ReminderRepair.OPEN_NOTIFICATION_SETTINGS -> HealthAction.NotificationSettings
            ReminderRepair.OPEN_BATTERY_SETTINGS -> HealthAction.BatterySettings
            else -> null
        }
        is RepairAction.OpenInApp -> when (repairActionOf(repair.code)) {
            ReminderRepair.TURN_REMINDERS_ON -> HealthAction.TurnRemindersOn
            ReminderRepair.OPEN_SCHEDULE -> repairTargetOf(repair.code)?.let(HealthAction::OpenSchedule)
            ReminderRepair.LOG_METER_READING -> repairTargetOf(repair.code)?.let(HealthAction::LogMeterReading)
            ReminderRepair.RESTORE_REMINDER_DELIVERY -> HealthAction.RestoreReminderDelivery
            else -> null
        }
    }

    private companion object {
        const val TAG = "ReminderHealth"
    }
}
