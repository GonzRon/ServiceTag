package com.loosecannon.servicetag.hapublish

import android.util.Log
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger
import com.loosecannon.servicetag.core.ports.Today
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * #113/#114 (C31, F-3, K14) — every trigger of the projection, on [scope] (the graph's `appScope`). Every pass that
 * may reach Home Assistant goes through [dispatch], so C30's network and schedule rule applies to each (C32): a
 * read-back or drain runs in process only while the connection's network rule allows a request now, and otherwise its
 * work goes to the one-time work while the rule answers PERIODIC.
 *
 * - **the observer** — [inputs]' work arrived, debounced [debounceMillis] → a projection, then, computed apart from
 *   its answer, the delivery-work signal ([workPending]) → [requester]. A source change, a generation move and a new
 *   obligation are each enough; the runner's bookkeeping is never an input (I28), so nothing a drain writes asks for
 *   another drain.
 * - **start** (`ServiceTagApp.onCreate`) — a projection, then **reconcile mode** ([PublishDispatch.readBack]: every
 *   enabled helper read back, FOREGROUND_ONLY included); it also starts the observer and the midnight [tick].
 * - **resume** ([onResume], the nav shell's hook) — a projection, then reconcile mode at most once per
 *   [RESUME_WINDOW_MILLIS] of process time ([elapsed]) since the last **completed** read-back (N-1: a pass the gate
 *   refused starts no window); inside the window, an ordinary drain of what the projection left.
 * - **local midnight** — a projection, then a scheduled drain ([PublishDispatch.drainNow] with `SCHEDULED`).
 *
 * The runner's lock makes every pass single flight. Each pass is guarded: a failure is logged without its cause and
 * the next trigger repeats it.
 */
class PublishTriggers(
    private val reconciler: DesiredStateReconciler,
    private val workPending: suspend () -> Boolean,
    private val dispatch: PublishDispatch,
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

    /**
     * Holds the window decision with the read-back it guards: a resume during the start's read-back waits for it and
     * then finds the window open, so a cold start reads each helper once (F-3); a read that did not complete opens
     * none (N-1).
     */
    private val readBacks = Mutex()

    /** The start pass, then, for the life of [scope], the observer and the midnight tick: all children of this Job. */
    fun start(): Job = scope.launch {
        launch { guarded { startPass() } }
        launch { observeWork() }
        launch { tick.run { guarded { midnightPass() } } }
    }

    /** Launches the resume pass and returns at once. */
    fun onResume() {
        scope.launch { guarded { resumePass() } }
    }

    internal suspend fun startPass() {
        reconciler.reconcileDesired(today.localDate())
        readBacks.withLock { readBack() }
    }

    internal suspend fun resumePass() {
        reconciler.reconcileDesired(today.localDate())
        readBacks.withLock {
            val last = lastReadBackAt
            if (last != null && elapsed() - last < RESUME_WINDOW_MILLIS) {
                dispatch.drainNow(DeliveryTrigger.CHANGE)
            } else {
                readBack()
            }
        }
    }

    internal suspend fun midnightPass() {
        reconciler.reconcileDesired(today.localDate())
        dispatch.drainNow(DeliveryTrigger.SCHEDULED)
    }

    /** The observer alone, on [scope] (a test's entry; [start] runs it as its own child). */
    internal fun observe(): Job = scope.launch { observeWork() }

    /**
     * The observer's collect. A failure of an observed flow itself (a Room query that throws upstream) ends the
     * observer with GH3 rather than reaching the scope, which carries no handler; start, resume, midnight and every
     * control commit still deliver.
     */
    @OptIn(FlowPreview::class)
    private suspend fun observeWork() {
        try {
            inputs.workArrived().debounce(debounceMillis).collect {
                guarded {
                    reconciler.reconcileDesired(today.localDate())
                    if (workPending()) requester.requestDelivery()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Log.w(TAG, "the ha publish reconcile failed; the next trigger repeats it") // l10n-ok
        }
    }

    private suspend fun readBack() {
        if (dispatch.readBack()?.readCompleted == true) lastReadBackAt = elapsed()
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
