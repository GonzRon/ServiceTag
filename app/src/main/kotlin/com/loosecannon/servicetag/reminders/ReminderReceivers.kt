package com.loosecannon.servicetag.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 * share. B06 assigns [trigger] once `AppGraph` exists (from `ServiceTagApp.onCreate`); an event
 * that arrives before that assignment is dropped, which is correct — there is no policy yet to run.
 */
object ReminderDispatch {
    @Volatile
    var trigger: ReminderTrigger? = null

    /** Outlives any single `onReceive` call; `goAsync()`'s `PendingResult` is what keeps the process alive for it. */
    @Volatile
    var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/**
 * Forwards [kind] to [ReminderDispatch.trigger] and nothing else: it never starts a screen and
 * never builds an `Activity`-bound `Intent` — the API 31+ trampoline rule (invariant 55's
 * mechanism) is never at risk from a receiver declared in this file, checked structurally by this
 * brief's own test.
 */
internal abstract class PlatformEventReceiver(private val kind: PlatformEventKind) : BroadcastReceiver() {
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
