package com.loosecannon.servicetag.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The digest alarm's own receiver: arm tomorrow's alarm, hand the sweep to a worker, return.
 *
 * It is **manifest-declared**, non-exported, and the only component in this brief that is: an alarm
 * has to survive the process it was armed from, so a runtime-registered receiver would be gone by
 * the time it fired (controller ruling, 2026-09-22 — this brief adds exactly one manifest element
 * and it is this `<receiver>`).
 *
 * **Nothing heavy runs here** (fix round 1, finding 4). The first version ran the whole
 * recompute-then-reconcile sweep inline under `goAsync()`, which does not lift the roughly
 * ten-second broadcast budget: this is the one path that fires at 09:00 on a phone nobody is
 * looking at, often on a cold doze-woken process, and overrunning the budget there means the
 * process is killed part-way through a reconcile. [ReminderRun.onDigestFired] now does two cheap
 * synchronous things — one `AlarmManager` call and one unique work enqueue — so this receiver needs
 * no `goAsync()` and no coroutine at all, and [ReconcileWorker] does the sweep under WorkManager's
 * own budget.
 *
 * It starts nothing.
 *
 * A run that has not been assigned yet is **dropped**, for [ReminderDispatch]'s reason: the
 * assignment is synchronous in `Application.onCreate`, which always completes before any
 * component's `onReceive`, so the drop path is only ever taken by an alarm that arrived before
 * this app had a policy at all — and the backstop re-arms whatever that loses.
 */
internal class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderRunDispatch.run?.onDigestFired()
    }
}
