package com.loosecannon.servicetag.reminders

import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.UnitOfWork
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delivered action: the nonce gate, the two writes behind it, and the two rules a
 * `BroadcastReceiver` on API 31+ must never break.
 *
 * `QuickActionReceiver.onReceive` itself is not driven from here — it has a `Context` and an
 * `Intent` and this module has no Robolectric — so the body it forwards to is named and driven
 * directly, exactly as B06 did for the digest fire. What the receiver adds on top of
 * [commandFrom] is one work enqueue, and that is the connected class's.
 */
class QuickActionReceiverTest {

    private val delivery = FakeDeliveryRepository()
    private val clock = Clock { Fixture.NOW }
    private val nonces = NonceStore(delivery, IdGenerator { "nonce-1" }, clock)
    private val snooze = ReminderSnooze(delivery, clock)

    private val completed = mutableListOf<ScheduleId>()
    private var reconciles = 0

    private fun runs(completion: ScheduleCompletion = ScheduleCompletion { completed += it }) = QuickActionRuns(
        nonces = nonces,
        completion = completion,
        snooze = snooze,
        uow = uow,
        reconcile = { reconciles++ },
        clock = clock,
    )

    /**
     * A transaction, as far as this brief's rows can see one: everything the block writes into the
     * delivery table commits together, and a throw puts the table back exactly as it was.
     *
     * A pass-through fake would let `aFailingWriteLeavesTheNonceIntact` pass for the wrong reason —
     * the point of that row is that the **rollback** is what keeps the nonce, not the ordering.
     */
    private val uow = object : UnitOfWork {
        override suspend fun <T> write(block: suspend () -> T): T {
            val before = LinkedHashMap(delivery.rows)
            return try {
                block()
            } catch (e: Throwable) {
                delivery.rows.clear()
                delivery.rows.putAll(before)
                throw e
            }
        }

        override suspend fun <T> read(block: suspend () -> T): T = block()
    }

    private val id = ScheduleId("s1")

    /**
     * #11 AC 1: one "Done" is **one** completion, and the run that follows it is what takes the
     * notification down — the receiver posts and cancels nothing itself, it reconciles and lets the
     * provider decide, which is why a completed schedule's notification simply stops being wanted.
     */
    @Test
    fun doneCompletesTheScheduleExactlyOnceAndThenReconciles() = runTest {
        val nonce = nonces.issue(id)

        runs().perform(QuickActionCommand(QuickActionKind.COMPLETE, id, nonce))

        assertEquals(listOf(id), completed)
        assertEquals(1, reconciles)
        assertNull("the nonce was consumed with the write", delivery.get(id)?.actionNonce)
    }

    /**
     * A redelivered broadcast — the same intent twice, which the platform is entitled to do —
     * completes **once**. The nonce is what makes that true: the second delivery finds it spent.
     */
    @Test
    fun aRedeliveredDoneCompletesOnlyOnce() = runTest {
        val nonce = nonces.issue(id)
        val runs = runs()

        runs.perform(QuickActionCommand(QuickActionKind.COMPLETE, id, nonce))
        runs.perform(QuickActionCommand(QuickActionKind.COMPLETE, id, nonce))

        assertEquals(listOf(id), completed)
        assertEquals("and the second delivery still reconciles, so a stale notification clears", 2, reconciles)
    }

    /**
     * Invariant 56 and #11 AC 4, all three shapes in one row: a **missing** nonce, a **stale** one
     * (issued for an earlier notification and replaced since) and an **already-used** one each
     * write nothing at all — no completion, no snooze — and the broadcast is simply dropped.
     *
     * Without this gate any app on the phone can complete the owner's maintenance by guessing an
     * intent, which is the one hazard a notification action introduces that nothing else does.
     */
    @Test
    fun aForgedStaleOrAlreadyUsedNonceWritesNothing() = runTest {
        val runs = runs()

        // Missing: nothing was ever issued for this schedule.
        runs.perform(QuickActionCommand(QuickActionKind.COMPLETE, id, "forged"))
        assertEquals(emptyList<ScheduleId>(), completed)
        assertNull(delivery.get(id)?.snoozedUntilAt)

        // Stale: issued for one notification, replaced by the next one's.
        val stale = nonces.issue(id)
        var counter = 1
        val rotating = NonceStore(delivery, IdGenerator { "nonce-${++counter}" }, clock)
        rotating.issue(id)
        runs.perform(QuickActionCommand(QuickActionKind.SNOOZE, id, stale))
        assertNull("no snooze was written for a replaced notification's action", delivery.get(id)?.snoozedUntilAt)

        // Already used: spent by a real action, then replayed.
        val live = delivery.get(id)?.actionNonce!!
        runs.perform(QuickActionCommand(QuickActionKind.COMPLETE, id, live))
        assertEquals(listOf(id), completed)
        runs.perform(QuickActionCommand(QuickActionKind.COMPLETE, id, live))
        assertEquals("the replay wrote nothing", listOf(id), completed)
    }

    /**
     * D-21's whole reason for a column instead of a field: the process that built the notification
     * dies, everything it held in memory goes with it, and the action the owner taps an hour later
     * **still works**. Two fresh instances over the surviving row are what a new process looks
     * like — an in-process nonce would make every quick action fail after a swap-out, which is most
     * of them.
     */
    @Test
    fun anActionStillWorksAfterTheProcessThatIssuedTheNonceHasDied() = runTest {
        val nonce = NonceStore(delivery, IdGenerator { "nonce-1" }, clock).issue(id)

        val afterProcessDeath = QuickActionRuns(
            nonces = NonceStore(delivery, IdGenerator { "nonce-9" }, clock),
            completion = ScheduleCompletion { completed += it },
            snooze = ReminderSnooze(delivery, clock),
            uow = uow,
            reconcile = { reconciles++ },
            clock = clock,
        )
        afterProcessDeath.perform(QuickActionCommand(QuickActionKind.COMPLETE, id, nonce))

        assertEquals(listOf(id), completed)
    }

    /**
     * Invariant 20 and #11 AC 3: "Snooze 1 day" writes `snoozed_until_at` and **nothing else** — no
     * completion, no event, and no date column, which this file cannot even reach: the only write
     * port it holds is the device-local row's.
     *
     * One day is 24 h from the tap, not "tomorrow at the digest hour": the owner asked for a day.
     */
    @Test
    fun snoozeWritesTheDeviceLocalInstantAndNothingElse() = runTest {
        val nonce = nonces.issue(id)

        runs().perform(QuickActionCommand(QuickActionKind.SNOOZE, id, nonce))

        val row = delivery.get(id)!!
        assertEquals(Fixture.NOW + ONE_DAY_MILLIS, row.snoozedUntilAt)
        assertEquals("no completion was written", emptyList<ScheduleId>(), completed)
        assertNull("and the nonce was spent", row.actionNonce)
        assertEquals(1, reconciles)
    }

    /** A broadcast that names no action, no schedule or no nonce is not a command at all. */
    @Test
    fun aBroadcastThatNamesNothingIsNotACommand() {
        assertEquals(
            QuickActionCommand(QuickActionKind.COMPLETE, id, "nonce-1"),
            commandFrom(QuickActionReceiver.ACTION_COMPLETE, "s1", "nonce-1"),
        )
        assertEquals(
            QuickActionCommand(QuickActionKind.SNOOZE, id, "nonce-1"),
            commandFrom(QuickActionReceiver.ACTION_SNOOZE, "s1", "nonce-1"),
        )
        assertNull(commandFrom(null, "s1", "nonce-1"))
        assertNull(commandFrom("android.intent.action.VIEW", "s1", "nonce-1"))
        assertNull(commandFrom(QuickActionReceiver.ACTION_COMPLETE, null, "nonce-1"))
        assertNull(commandFrom(QuickActionReceiver.ACTION_COMPLETE, "s1", null))
        assertNull(commandFrom(QuickActionReceiver.ACTION_COMPLETE, "", "nonce-1"))
        assertNull(commandFrom(QuickActionReceiver.ACTION_COMPLETE, "s1", ""))
    }

    /**
     * D-21's "same transaction" clause, from the side that needs it (fix round 1, finding 1).
     *
     * The gate is consumed **inside** the write the action performs, so a database failure — or a
     * process death — between clearing the nonce and writing the event rolls **both** back. The
     * nonce is still in the row, the notification still standing in the shade still authorises it,
     * and `QuickActionWorker`'s retry completes the tap. Consumed in a transaction of its own, the
     * same failure would have spent the nonce and written nothing: a tap silently lost, with the
     * action already dead.
     *
     * `reconcile` still runs, because it is in a `finally` and outside the transaction (nit 7).
     */
    @Test
    fun aFailingWriteLeavesTheNonceIntactAndStillReconciles() = runTest {
        val nonce = nonces.issue(id)
        val failing = runs(completion = ScheduleCompletion { error("the database was busy") })

        var thrown: Throwable? = null
        try {
            failing.perform(QuickActionCommand(QuickActionKind.COMPLETE, id, nonce))
        } catch (e: Exception) {
            thrown = e
        }

        assertNotNull("the worker has to see the failure to retry it", thrown)
        assertEquals("the nonce rolled back with the write", nonce, delivery.get(id)?.actionNonce)
        assertEquals("and the retry spends it properly", emptyList<ScheduleId>(), completed)
        assertEquals("the reconcile ran even so", 1, reconciles)

        // The retry: the same broadcast, the same nonce, and this time the write succeeds.
        runs().perform(QuickActionCommand(QuickActionKind.COMPLETE, id, nonce))
        assertEquals(listOf(id), completed)
        assertNull(delivery.get(id)?.actionNonce)
    }

    /**
     * Invariant 55, structurally at the declaration: the receiver's own file never calls
     * `startActivity` and never constructs an `Intent` — so it cannot trampoline, whatever a later
     * edit does to its body. Android 12+ silently drops such a launch, which is the worst possible
     * failure mode: the action appears to do nothing at all.
     *
     * The wider form of the same scan — `startActivity` nowhere under `reminders/`, `Intent(`
     * nowhere in a file declaring a receiver — is `ManifestContractTest`'s and covers this file too;
     * this row names it at the component the invariant is about.
     */
    @Test
    fun theReceiverNeitherStartsAnActivityNorConstructsAnIntent() {
        val source = sourceFile("kotlin/com/loosecannon/servicetag/reminders/QuickActionReceiver.kt").readText()

        assertTrue("QuickActionReceiver declares the receiver", "BroadcastReceiver" in source)
        assertTrue("and must never start an activity", "startActivity" !in source)
        assertTrue("and must never construct an Intent", "Intent(" !in source)
    }

    /**
     * Invariant 54 at the builder: every `PendingIntent` it builds is `FLAG_IMMUTABLE` **on the
     * same physical line as its call**, because master plan §16's release proof is line-based and a
     * wrapped argument list would turn that grep from 0 to 1 while the invariant itself still held.
     *
     * The four §12.1 actions are built from **two** call sites — the two writes share a
     * `getBroadcast` and the two navigations share a `getActivity` — so what makes them four
     * distinct pending intents is four distinct request codes, which is the assertion below rather
     * than a count of lines. `getService` appears nowhere: this brief starts no service.
     */
    @Test
    fun everyPendingIntentTheBuilderMakesIsImmutableAndTheFourActionsStayFour() {
        val lines = sourceFile("kotlin/com/loosecannon/servicetag/reminders/QuickActions.kt").readText().lines()
        val calls = lines.filter { "PendingIntent.get" in it }

        calls.forEach { line ->
            assertTrue("every PendingIntent is FLAG_IMMUTABLE on its own line: ${line.trim()}", "FLAG_IMMUTABLE" in line)
        }
        assertEquals("one broadcast site for the two writes", 1, calls.count { "PendingIntent.getBroadcast" in it })
        assertEquals("one activity site for the two navigations", 1, calls.count { "PendingIntent.getActivity" in it })
        assertEquals("nothing here starts a service", 0, calls.count { "PendingIntent.getService" in it })

        val codes = listOf(
            AndroidQuickActionIntents.REQUEST_COMPLETE,
            AndroidQuickActionIntents.REQUEST_SNOOZE,
            AndroidQuickActionIntents.REQUEST_COMPLETION_FORM,
            AndroidQuickActionIntents.REQUEST_OPEN,
        )
        assertEquals("four actions, four request codes: a shared code would be one pending intent", 4, codes.distinct().size)
        assertTrue(
            "the broadcasts name their own component, so the non-exported receiver is reachable at all",
            lines.any { "QuickActionReceiver::class.java" in it },
        )
    }
}
