package com.loosecannon.servicetag.ui.maintenance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loosecannon.servicetag.core.reminders.HealthFinding
import com.loosecannon.servicetag.core.reminders.RepairAction
import com.loosecannon.servicetag.core.reminders.Severity
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.prefs.AppPrefs
import com.loosecannon.servicetag.reminders.ReminderHealthCheck
import com.loosecannon.servicetag.reminders.ReminderRepair
import com.loosecannon.servicetag.reminders.repairActionOf
import com.loosecannon.servicetag.reminders.repairTargetOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The seven RATIFIED repair labels (master plan §17.1a), keyed by the action half of a repair's
 * code. `NO_DATA`'s is D-24's already-ratified "Log meter reading"; the other six were ratified at
 * the gate on 2026-09-22. Quoted verbatim, never paraphrased.
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
    else -> null
}

/**
 * What tapping a repair does, in the five kinds the Health screen can actually perform.
 *
 * The screen switches on this and holds **no** repair policy of its own: which repairs may be run
 * on the owner's behalf is [ReminderHealthCheck]'s decision, carried here by whether the port's
 * action was [RepairAction.Automatic]. [TurnRemindersOn] is the one in-app repair that writes
 * instead of navigating — #27's "one tap to enable" — and it writes B06's preference and nothing
 * else.
 */
sealed interface HealthAction {
    data object Automatic : HealthAction
    data object NotificationSettings : HealthAction
    data object BatterySettings : HealthAction
    data object TurnRemindersOn : HealthAction
    data class OpenSchedule(val scheduleId: String) : HealthAction
    data class LogMeterReading(val scheduleId: String) : HealthAction
}

/**
 * One finding as the screen draws it: the ratified sentence, the ratified button label, and what
 * the button does.
 *
 * [action] is null when there is nothing honest to offer — the finding is still worth showing, and
 * explaining a platform reality beats a button that cannot help.
 */
data class HealthRow(
    val finding: HealthFinding,
    val label: String?,
    val action: HealthAction?,
) {
    val code: String get() = finding.code
    val severity: Severity get() = finding.severity
    val message: String get() = finding.message
}

/** The Health section's state. [loaded] is why an empty list does not flash before the first run. */
data class HealthState(
    val rows: List<HealthRow> = emptyList(),
    val loaded: Boolean = false,
) {
    /** The badge's own question, over the same findings the rows came from. */
    val worstSeverity: Severity? get() = rows.maxByOrNull { it.severity }?.severity
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
class ReminderHealth(private val check: ReminderHealthCheck) : HealthSummary {

    @Volatile
    private var cached: List<HealthFinding>? = null

    override suspend fun worstSeverity(): Severity? = cached?.maxByOrNull { it.severity }?.severity

    /** Runs the check and caches what it found. The Health screen's own refresh, and launch's. */
    suspend fun refresh(): List<HealthFinding> = check.run().also { cached = it }

    /** Applies one automatic repair, and nothing else; the caller refreshes after it. */
    suspend fun repair(finding: HealthFinding) = check.repair(finding)
}

/**
 * The Health section inside the Maintenance destination (#27, spec §5.8).
 *
 * It reports and repairs and decides nothing: the findings, their severities and which of them may
 * be repaired automatically are all [ReminderHealthCheck]'s, and this turns each one into a row with
 * its ratified label. The two repairs it performs itself are the automatic one — delegated straight
 * back to the check — and #27's one-tap "Turn reminders on", which writes B06's preference. The
 * other four take the owner somewhere: two system screens and two in-app destinations, both
 * performed by the screen.
 */
class HealthViewModel(
    private val health: ReminderHealth,
    private val prefs: AppPrefs,
) : ViewModel() {

    constructor(graph: AppGraph) : this(graph.reminderHealth, graph.prefs)

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
     * The two repairs this view model performs. Anything else on the row is the screen's to do, and
     * a row with no action at all falls through without a refresh — there is nothing to have
     * changed.
     */
    fun repair(row: HealthRow) {
        viewModelScope.launch {
            when (row.action) {
                HealthAction.Automatic -> health.repair(row.finding)
                HealthAction.TurnRemindersOn -> prefs.remindersEnabled = true
                else -> return@launch
            }
            emit(health.refresh())
        }
    }

    private fun emit(findings: List<HealthFinding>) {
        _state.value = HealthState(rows = findings.map(::rowOf), loaded = true)
    }

    private fun rowOf(finding: HealthFinding): HealthRow {
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
            else -> null
        }
    }
}
