package com.loosecannon.servicetag.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * The digest alarm's own receiver: run the digest, then arm tomorrow's.
 *
 * It is **manifest-declared**, non-exported, and the only component in this brief that is: an alarm
 * has to survive the process it was armed from, so a runtime-registered receiver would be gone by
 * the time it fired (controller ruling, 2026-09-22 — this brief adds exactly one manifest element
 * and it is this `<receiver>`).
 *
 * It starts nothing. The re-arm and the posting both happen inside [ReminderRunDispatch]'s run;
 * `goAsync()` does not lift the roughly ten-second broadcast budget, which is why the run hands
 * its real work to the same recompute-then-reconcile sequence the backstop uses rather than
 * drawing anything itself.
 *
 * A run that has not been assigned yet is **dropped**, for [ReminderDispatch]'s reason: the
 * assignment is synchronous in `Application.onCreate`, which always completes before any
 * component's `onReceive`, so the drop path is only ever taken by an alarm that arrived before
 * this app had a policy at all — and the backstop re-arms whatever that loses.
 */
internal class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val run = ReminderRunDispatch.run ?: return
        val pending = goAsync()
        ReminderDispatch.scope.launch {
            try {
                run.onDigestFired()
            } finally {
                pending.finish()
            }
        }
    }
}
