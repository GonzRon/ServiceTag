package com.loosecannon.servicetag.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
