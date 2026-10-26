package com.loosecannon.servicetag.hapublish

import android.util.Log
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger
import com.loosecannon.servicetag.core.ports.Today
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * #113/#114 (C31, F-3, K14) — every trigger of the projection, on [scope] (the graph's `appScope`):
 *
 * - **the observer** — [inputs]' work arrived, debounced [debounceMillis] → a projection, then, computed apart from
 *   its answer, the delivery-work signal ([workPending]) → [requester]. A source change, a generation move and a new
 *   obligation are each enough; the runner's bookkeeping is never an input (I28), so nothing a drain writes asks for
 *   another drain.
 * - **start** (`ServiceTagApp.onCreate`) — a projection, then **reconcile mode** (`publishNow`: every enabled helper
 *   read back, FOREGROUND_ONLY included); it also starts the observer and the midnight [tick].
 * - **resume** ([onResume], the nav shell's hook) — a projection, then reconcile mode at most once per
 *   [RESUME_WINDOW_MILLIS] of process time ([elapsed]) since the last **completed** read-back (N-1: a pass the gate
 *   refused starts no window); inside the window, an ordinary drain of what the projection left.
 * - **local midnight** — a projection, then a scheduled drain (`drain(SCHEDULED)`).
 *
 * The runner's lock makes every pass single flight. Each pass is guarded: a failure is logged without its cause and
 * the next trigger repeats it.
 */
class PublishTriggers(
    private val reconciler: DesiredStateReconciler,
    private val workPending: suspend () -> Boolean,
    private val runner: PublishRunner,
    private val inputs: PublishInputs,
    private val requester: DeliveryRequester,
    private val today: Today,
    private val scope: CoroutineScope,
    private val elapsed: () -> Long,
    private val tick: MidnightTick,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) {
    /** When the last start or resume read-back completed, in [elapsed]'s time; null before the first. */
    @Volatile private var lastReadBackAt: Long? = null

    /** The start pass, then, for the life of [scope], the observer and the midnight tick. */
    fun start(): Job = scope.launch {
        launch { guarded { startPass() } }
        observe()
        launch { tick.run { guarded { midnightPass() } } }
    }

    /** Launches the resume pass and returns at once. */
    fun onResume() {
        scope.launch { guarded { resumePass() } }
    }

    internal suspend fun startPass() {
        reconciler.reconcileDesired(today.localDate())
        readBack()
    }

    internal suspend fun resumePass() {
        reconciler.reconcileDesired(today.localDate())
        val last = lastReadBackAt
        if (last != null && elapsed() - last < RESUME_WINDOW_MILLIS) {
            runner.drain()
        } else {
            readBack()
        }
    }

    internal suspend fun midnightPass() {
        reconciler.reconcileDesired(today.localDate())
        runner.drain(DeliveryTrigger.SCHEDULED)
    }

    @OptIn(FlowPreview::class)
    internal fun observe(): Job = scope.launch {
        inputs.workArrived().debounce(debounceMillis).collect {
            guarded {
                reconciler.reconcileDesired(today.localDate())
                if (workPending()) requester.requestDelivery()
            }
        }
    }

    private suspend fun readBack() {
        if (runner.publishNow(null).readCompleted) lastReadBackAt = elapsed()
    }

    private suspend fun guarded(pass: suspend () -> Unit) {
        try {
            pass()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.w(TAG, "the ha publish reconcile failed; the next trigger repeats it") // l10n-ok
        }
    }

    companion object {
        /** C31: the observer's quiet period before a projection. */
        const val DEBOUNCE_MILLIS = 300L

        /** F-3: resume reads every helper back at most once an hour of process time. */
        const val RESUME_WINDOW_MILLIS = 60L * 60 * 1000

        private const val TAG = "HaPublish"
    }
}
