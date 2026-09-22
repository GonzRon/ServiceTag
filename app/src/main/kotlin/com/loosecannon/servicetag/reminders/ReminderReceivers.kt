package com.loosecannon.servicetag.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.reminders.ProviderId
import com.loosecannon.servicetag.core.reminders.ReconcileReport
import com.loosecannon.servicetag.core.reminders.ReminderProvider
import com.loosecannon.servicetag.core.reminders.ReminderSubject
import java.time.LocalDate
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The platform events the four re-arm receivers below dispatch (spec §5.4). What each one
 * *triggers* is B06's policy; this brief owns only the manifest declarations, the non-exported
 * discipline, and the door each receiver calls through.
 */
enum class PlatformEventKind { BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, DATE_CHANGED }

/** B06's single implementation of the re-arm/recompute policy behind the four receivers (master plan §12, decision 24). */
fun interface ReminderTrigger {
    suspend fun onPlatformEvent(kind: PlatformEventKind)
}

/**
 * The bridge from a manifest-declared, no-arg-constructed [BroadcastReceiver] to the app-scoped
 * [ReminderTrigger] B06 builds inside the composition root. Android instantiates a receiver by
 * reflection, never through `AppGraph`, so this is the one static seam the four classes below
 * share.
 *
 * **B06 must assign [trigger] synchronously in `ServiceTagApp.onCreate`, before `AppGraph`'s
 * constructor returns — not from a coroutine, and not lazily.** `Application.onCreate` always
 * completes before any component's `onReceive` runs, which is what makes a `BOOT_COMPLETED`
 * broadcast — the one event that creates the process and the one the user never sees to retry —
 * safe to drop-if-unset rather than queue: the drop path is only ever taken for an event that
 * genuinely arrived before this brief's own app had a policy to run, never for one that raced it.
 * If B06 assigns the trigger any other way, boot events are dropped silently and invariant 60
 * fails on a real device with nothing in any log to show for it.
 */
object ReminderDispatch {
    @Volatile
    var trigger: ReminderTrigger? = null

    /**
     * A trigger failure must never crash the process (B05 fix round 1, finding 2): a
     * `SupervisorJob` stops a child failure from propagating to *siblings*, it does not stop an
     * uncaught exception from a root `launch` reaching the thread's default handler, which on
     * Android kills the process — and the path that would fire is `BOOT_COMPLETED`. `val`, not
     * `var` (finding 15): nothing needs to replace this scope, and a mutable global a stray test
     * reassigns would pollute every later test in the same JVM.
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                Log.w(TAG, "a platform-event trigger failed; the broadcast is still considered handled", throwable)
            },
    )

    private const val TAG = "ReminderDispatch"
}

/**
 * Forwards [kind] to [ReminderDispatch.trigger] and nothing else: it never starts a screen and
 * never builds an `Activity`-bound `Intent` — the API 31+ trampoline rule (invariant 55's
 * mechanism) is never at risk from a receiver declared in this file, checked structurally by this
 * brief's own test. `goAsync()` does not lift the broadcast budget: the system still expects
 * `finish()` within roughly ten seconds, so B06's trigger must be short or hand off to its own
 * worker rather than doing real work inline here.
 */
internal abstract class PlatformEventReceiver(internal val kind: PlatformEventKind) : BroadcastReceiver() {
    final override fun onReceive(context: Context, intent: Intent) {
        val trigger = ReminderDispatch.trigger ?: return
        val pending = goAsync()
        ReminderDispatch.scope.launch {
            try {
                trigger.onPlatformEvent(kind)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * `internal`, not `private` — Kotlin's `internal` still compiles to an ordinary public class file,
 * so Android's manifest-driven reflection can still construct it; only this module's own Kotlin
 * source is kept from depending on these four names directly, which nothing needs to.
 */

/** Alarms are cleared on shutdown; this is what lets the digest alarm be re-armed after one. */
internal class BootCompletedReceiver : PlatformEventReceiver(PlatformEventKind.BOOT_COMPLETED)

/** A manual clock change moves the next digest instant. */
internal class TimeSetReceiver : PlatformEventReceiver(PlatformEventKind.TIME_SET)

/** D-6 requires a re-arm on a zone change. */
internal class TimezoneChangedReceiver : PlatformEventReceiver(PlatformEventKind.TIMEZONE_CHANGED)

/** The device-local date `T` moved, so due state moved with it. */
internal class DateChangedReceiver : PlatformEventReceiver(PlatformEventKind.DATE_CHANGED)

/**
 * The two runs that are not a platform event: the digest alarm firing, and the periodic backstop.
 *
 * A separate seam from [ReminderTrigger] rather than two more [PlatformEventKind] members, because
 * neither is a platform event — nothing broadcast them, the app armed one and enqueued the other —
 * and because the difference between them is precisely the re-arm rule, which a single `kind`
 * parameter would hide inside a `when`.
 */
interface ReminderRun {
    /** The digest alarm fired: run, then arm the next one. */
    suspend fun onDigestFired()

    /** The periodic backstop woke: run, and arm only if the alarm has gone. */
    suspend fun onBackstop()
}

/**
 * The bridge from the manifest-declared digest receiver and the WorkManager-constructed
 * `BackstopWorker` to the run B06 builds inside the composition root, for exactly
 * [ReminderDispatch]'s reason: neither of those two is constructed through `AppGraph`.
 *
 * **Assigned synchronously in `ServiceTagApp.onCreate`**, beside [ReminderDispatch.trigger] and
 * before `AppGraph`'s constructor returns, so it is in place before any alarm can fire or any
 * worker can run.
 */
object ReminderRunDispatch {
    @Volatile
    var run: ReminderRun? = null
}

/**
 * The single implementation of the re-arm and recompute policy behind all six entry points: the
 * four platform receivers, the digest alarm's receiver and the backstop worker.
 *
 * One class, because every entry point does the same three things in the same order — rebuild
 * derived state, ask for the subjects that state implies, hand the whole list to the provider — and
 * the only thing that differs is when the alarm is re-armed. Six copies of that sequence would be
 * six chances for one of them to reconcile before it recomputed and post yesterday's answer.
 *
 * The two collaborators are function seams rather than the concrete `RecomputeSchedules` and
 * `BuildReminderSubjects`, following the shape `AppGraph` already uses for
 * `ImportBackupReplace.rebuildAll`: the recompute takes eight ports and the subject builder five,
 * and a test of the *order* of three calls should not have to construct either. `rebuildAll` is
 * wired to `RecomputeSchedules.all()`, which reads each schedule's own season window itself —
 * nothing in this brief calls `ScheduleRecompute.rebuild` directly, so there is no `season`
 * argument here to pass.
 *
 * Invariant 17 holds by construction: the recompute is the only writer of derived state, and this
 * class holds no state repository at all.
 */
class ReminderRuns(
    private val rebuildAll: suspend () -> Unit,
    private val subjectsFor: suspend (ProviderId, LocalDate) -> List<ReminderSubject>,
    private val provider: ReminderProvider,
    private val alarm: DigestAlarm,
    private val today: Today,
) : ReminderTrigger, ReminderRun {

    /**
     * Every one of the four platform events re-arms **unconditionally**. A boot cleared the alarm;
     * a clock or zone change moved the instant it should be set for; a date change moved `T` and so
     * moved the next instant too. `armed()` cannot tell an alarm pending for a stale instant from a
     * current one, and `arm()` replaces rather than adds, so asking the question would only ever
     * let a wrong answer stand.
     */
    override suspend fun onPlatformEvent(kind: PlatformEventKind) {
        reconcileAll()
        alarm.arm()
    }

    /** The inexact alarm is one shot: handling the fire is what arms tomorrow's. */
    override suspend fun onDigestFired() {
        reconcileAll()
        alarm.arm()
    }

    /**
     * The backstop re-arms **only if the alarm is gone**, which is the whole of what it is for.
     * Arming an already-pending alarm twice a day would be harmless and would also stop this being
     * a statement about a missing alarm.
     */
    override suspend fun onBackstop() {
        reconcileAll()
        if (!alarm.armed()) alarm.arm()
    }

    private suspend fun reconcileAll(): ReconcileReport {
        rebuildAll()
        return provider.reconcile(subjectsFor(provider.id, today.localDate()))
    }
}
