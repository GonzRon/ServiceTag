package com.loosecannon.servicetag.ui.maintenance

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #123 (§3.7, I13): the sweep a completion runs after its write. The write has committed, so a sweep that fails is
 * logged and the call returns (RA1); a cancelled one stays cancelled, so a scope that ended is never reported as a
 * completion that went on to ask its offers (RA2).
 */
class ReminderSweepHelperTest {

    /** RA1: a sweep that throws is logged; the call returns normally, and the sweep ran once. */
    @Test fun aFailedSweepIsLoggedAndTheCallReturns() = runTest {
        val sweep = CountingSweep(IllegalStateException("the provider refused"))

        sweep.runAfterWrite("ReminderSweepHelperTest")

        assertEquals(1, sweep.runs)
    }

    /** RA2: a sweep cancelled under it rethrows the same `CancellationException`. */
    @Test fun aCancelledSweepStaysCancelled() = runTest {
        val cancelled = CancellationException("the screen left")
        val sweep = CountingSweep(cancelled)

        val thrown = try {
            sweep.runAfterWrite("ReminderSweepHelperTest")
            null
        } catch (caught: CancellationException) {
            caught
        }

        assertSame(cancelled, thrown)
        assertEquals(1, sweep.runs)
    }
}

/** What a sweep saw at the moment it ran: how many events the store held, and whether an offer was open. */
internal data class SweepSighting(val events: Int, val offerOpen: Boolean)

/**
 * #123's counting fake: a [ReminderReconcile] that records a [SweepSighting] each time it runs, then throws [failure]
 * when one is given. [sight] is read inside the run, so it sees the store as the sweep does.
 */
internal class CountingSweep(
    private val failure: Throwable? = null,
    private val sight: suspend () -> SweepSighting = { SweepSighting(events = 0, offerOpen = false) },
) : ReminderReconcile {
    val sightings = mutableListOf<SweepSighting>()
    val runs: Int get() = sightings.size

    override suspend fun run() {
        sightings += sight()
        failure?.let { throw it }
    }
}
