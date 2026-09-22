package com.loosecannon.servicetag.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.UnitOfWork

/** Which of the two nonce-checked writes a delivered broadcast is asking for. */
enum class QuickActionKind { COMPLETE, SNOOZE }

/** One delivered action, already read out of the broadcast and validated as a shape. */
data class QuickActionCommand(val kind: QuickActionKind, val scheduleId: ScheduleId, val nonce: String)

/**
 * "Done", as the canonical completion use case performs it.
 *
 * A seam rather than `CompleteSchedule` itself, for the shape `AppGraph` already uses for
 * `ReminderRuns.rebuildAll`: the use case takes eight ports, and a test of the **nonce gate** should
 * not have to construct them. What matters for invariant 17 is what this file can reach, and it is
 * this and the device-local row's snooze — no state repository, and no event repository either.
 */
fun interface ScheduleCompletion {
    suspend fun complete(id: ScheduleId)
}

/** The work one delivered action does, so [QuickActionWorker] does not have to know what that is. */
fun interface QuickActionHandler {
    suspend fun perform(command: QuickActionCommand)
}

/**
 * The bridge from the WorkManager-constructed [QuickActionWorker] to the handler `AppGraph` builds,
 * for exactly [ReminderRunDispatch]'s reason: a worker is never constructed through `AppGraph`.
 *
 * **Assigned synchronously in `ServiceTagApp.onCreate`**, beside the other two, so it is in place
 * before any notification the previous process posted can be tapped.
 */
object QuickActionDispatch {
    @Volatile
    var handler: QuickActionHandler? = null
}

/** "Snooze 1 day", in milliseconds: a day from the tap. */
internal const val ONE_DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

/**
 * The nonce gate and the two writes behind it (§12.1, D-21, invariant 56).
 *
 * **The gate and the write are one transaction** (brief, *The nonce's life*: "the use case runs, and
 * the nonce is **cleared** in the **same transaction** as the write"). Consuming first inside it is
 * what a replay meets — the second delivery finds the value spent — and committing the two together
 * is what the crash case needs: a process death between the clear and the event would otherwise
 * spend the nonce and write nothing, and the owner's tap would be lost with the notification's
 * action already dead. Rolled back together, the nonce is still in the row and the retry works
 * (fix round 1, finding 1).
 *
 * `CompleteSchedule` opens a write of its own; Room joins an active transaction rather than
 * starting a second, so the event, the conditional postponement clear, the recompute and this
 * clear all commit or roll back as one.
 *
 * `reconcile` runs **whether or not the nonce was accepted**, and now also when the write throws
 * (controller carry-forward (d); fix round 1, nit 7). A refused action is a silent no-op on the
 * domain, but the notification that carried it is stale by definition — it was issued against a
 * nonce that has since been replaced or spent — and leaving it standing would leave the owner
 * tapping a dead action for ever. It is deliberately **outside** the transaction: it posts to the
 * shade and reads the platform, neither of which belongs inside a database write.
 *
 * Invariant 17 holds by construction: the only write surfaces here are the completion use case and
 * the device-local row, and no file under `reminders/` names a state repository at all.
 */
class QuickActionRuns(
    private val nonces: NonceStore,
    private val completion: ScheduleCompletion,
    private val snooze: ReminderSnooze,
    private val uow: UnitOfWork,
    private val reconcile: suspend () -> Unit,
    private val clock: Clock,
) : QuickActionHandler {

    override suspend fun perform(command: QuickActionCommand) {
        try {
            uow.write {
                if (nonces.consume(command.scheduleId, command.nonce)) {
                    when (command.kind) {
                        QuickActionKind.COMPLETE -> completion.complete(command.scheduleId)
                        // Invariant 20: one column of one device-local row. No date moves and no
                        // event is written — the confusion #11 and D-13 exist to prevent, and the
                        // reason the snooze was never allowed near `postponed_due_on`.
                        QuickActionKind.SNOOZE ->
                            snooze.snooze(command.scheduleId, clock.nowMillis() + ONE_DAY_MILLIS)
                    }
                }
            }
        } finally {
            reconcile()
        }
    }
}

/**
 * The one non-exported receiver behind "Done" and "Snooze 1 day".
 *
 * It is **manifest-declared** for the digest receiver's reason: a notification outlives the process
 * that posted it, so a runtime-registered receiver would be gone by the time the owner taps. It
 * carries **no intent filter at all** and is addressed only by this app's own explicit,
 * `FLAG_IMMUTABLE` pending intents (invariant 54) — an implicit intent could not reach a
 * non-exported receiver anyway, and an exported one would be the forgery door the nonce exists
 * because of.
 *
 * **It starts nothing.** The two actions that open a screen never arrive here at all: they are
 * `getActivity` pending intents built next door, which is the API 31+ trampoline rule (invariant 55)
 * honoured at the source rather than worked around here.
 *
 * **Nothing heavy runs here** either. The work behind one tap is a completion, a recompute and a
 * whole reconcile — the same sweep B06 moved out of the digest fire — and the broadcast budget is
 * roughly ten seconds that `goAsync()` does not extend. So this does two cheap synchronous things,
 * reads the command out of the intent and enqueues [QuickActionWorker], and the work runs under
 * WorkManager's own budget on a process the system will not kill part-way through a completion.
 *
 * **Nothing it does may crash the process.** An enqueue can throw if `WorkManager.getInstance`
 * fails to initialise; improbable, and not worth a dead process for a notification tap. The action
 * is simply lost, which is recoverable: the notification is still standing and the nonce is still in
 * the row, so the next tap works.
 */
internal class QuickActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val command = commandFrom(
            action = intent.action,
            scheduleId = intent.data?.lastPathSegment,
            nonce = intent.getStringExtra(EXTRA_NONCE),
        ) ?: return
        try {
            QuickActionWorker.enqueue(context, command)
        } catch (e: Exception) {
            Log.w(TAG, "a quick action could not be handed to a worker; the notification still stands", e)
        }
    }

    internal companion object {
        /**
         * Custom actions on otherwise explicit intents. They are what tells the two writes apart on
         * one receiver, and they take part in the `filterEquals` match `FLAG_UPDATE_CURRENT`
         * uses to recognise a pending intent again.
         */
        const val ACTION_COMPLETE = "com.loosecannon.servicetag.reminders.QUICK_COMPLETE"
        const val ACTION_SNOOZE = "com.loosecannon.servicetag.reminders.QUICK_SNOOZE"

        /** The notification's nonce. The schedule id travels in the data URI, not in an extra. */
        const val EXTRA_NONCE = "nonce"

        private const val TAG = "QuickActionReceiver"
    }
}

/**
 * The command a delivered broadcast names, or null when it names none.
 *
 * Named and file-level so a JVM unit test can drive it: `onReceive`'s two Android parameters are
 * the only reason the receiver above cannot be, and a test that had to build a `Context` and a
 * `Uri` to prove that a nonce-less broadcast is dropped would be proving the harness instead.
 *
 * A missing action, an unknown one, a missing schedule id and a missing nonce are all the same
 * answer: not a command. Nothing is read and nothing is written for any of them.
 */
internal fun commandFrom(action: String?, scheduleId: String?, nonce: String?): QuickActionCommand? {
    val kind = when (action) {
        QuickActionReceiver.ACTION_COMPLETE -> QuickActionKind.COMPLETE
        QuickActionReceiver.ACTION_SNOOZE -> QuickActionKind.SNOOZE
        else -> return null
    }
    if (scheduleId.isNullOrEmpty() || nonce.isNullOrEmpty()) return null
    return QuickActionCommand(kind, ScheduleId(scheduleId), nonce)
}

/**
 * One tapped action, out of the broadcast window (B06 fix round 1, finding 4's rule applied to this
 * brief's own path).
 *
 * Enqueued under **one unique name with `APPEND_OR_REPLACE`**, so two taps — the owner's
 * double-tap, or a platform redelivery — are performed one after the other rather than at the same
 * time. That is belt and braces rather than the load-bearing guard: the nonce already refuses the
 * second, and a completion's `occurrence_on` makes a repeat a no-op at the database level. `KEEP`
 * would have been wrong here in a way it is not for the sweep — it would **drop** a legitimate
 * action on another schedule while one was running, and a dropped tap looks to the owner exactly
 * like a broken button.
 */
class QuickActionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val handler = QuickActionDispatch.handler
        if (handler == null) {
            // Unreachable in a healthy process: `Application.onCreate` assigns it before any worker
            // can run. Success rather than retry, so an unreachable branch cannot become a loop.
            Log.w(TAG, "a quick action ran before a handler was assigned; nothing to do")
            return Result.success()
        }
        val command = commandFrom(
            action = inputData.getString(KEY_ACTION),
            scheduleId = inputData.getString(KEY_SCHEDULE_ID),
            nonce = inputData.getString(KEY_NONCE),
        )
        if (command == null) {
            Log.w(TAG, "a quick action carried no command; it is dropped rather than retried")
            return Result.success()
        }
        return try {
            handler.perform(command)
            Result.success()
        } catch (e: Exception) {
            // A busy database or a read that raced a write is worth one more attempt on
            // WorkManager's backoff; the nonce has already been consumed, so a retry that reaches
            // the gate again writes nothing and only reconciles — which is the safe half.
            Log.w(TAG, "a quick action failed; WorkManager will retry it", e)
            Result.retry()
        }
    }

    companion object {
        /** One name for every quick action, so two taps are serialised rather than raced. */
        const val UNIQUE_NAME = "reminder-quick-action"

        private const val KEY_ACTION = "action"
        private const val KEY_SCHEDULE_ID = "schedule_id"
        private const val KEY_NONCE = "nonce"
        private const val TAG = "QuickActionWorker"

        internal fun request(command: QuickActionCommand): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(QuickActionWorker::class.java)
                .setInputData(
                    workDataOf(
                        KEY_ACTION to when (command.kind) {
                            QuickActionKind.COMPLETE -> QuickActionReceiver.ACTION_COMPLETE
                            QuickActionKind.SNOOZE -> QuickActionReceiver.ACTION_SNOOZE
                        },
                        KEY_SCHEDULE_ID to command.scheduleId.value,
                        KEY_NONCE to command.nonce,
                    ),
                )
                .build()

        fun enqueue(context: Context, command: QuickActionCommand) {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(command))
        }
    }
}
