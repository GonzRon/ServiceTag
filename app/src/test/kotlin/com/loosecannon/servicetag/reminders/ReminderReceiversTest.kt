package com.loosecannon.servicetag.reminders

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Not one of the brief's three named test files (`NotificationChannelsTest`, `PlatformStateTest`,
 * `ManifestContractTest`); `ReminderDispatch` is the static bridge this brief adds so a
 * manifest-instantiated `BroadcastReceiver` can reach the `ReminderTrigger` B06 builds inside
 * `AppGraph` (see the report's deviations section). `PlatformEventReceiver.onReceive` itself
 * — `BroadcastReceiver`, `goAsync()` — cannot be driven from this Robolectric-free JVM unit test
 * without a real `Context`; what is unit-testable, and what actually carries the risk of a
 * copy-paste bug, is the dispatch object's own state and each receiver class's own `kind`
 * (B05 fix round 1, finding 6).
 *
 * `ReminderDispatch` is process-global, static state shared by every test in this module's one
 * JVM — `@After`, not just `@Before`, so a failure partway through a test still leaves the field
 * clear for whatever runs next.
 */
class ReminderReceiversTest {

    @After
    fun resetDispatch() {
        ReminderDispatch.trigger = null
    }

    @Test
    fun theTriggerAssignedToReminderDispatchIsWhatAReceiverWouldInvoke() {
        val seen = mutableListOf<PlatformEventKind>()
        val trigger = ReminderTrigger { kind -> seen += kind }
        ReminderDispatch.trigger = trigger

        // Read back through the object's own field, not the local variable, and invoke through
        // it — a receiver's `onReceive` does exactly this lookup before it dispatches.
        assertSame(trigger, ReminderDispatch.trigger)
        PlatformEventKind.entries.forEach { kind ->
            runBlocking { ReminderDispatch.trigger?.onPlatformEvent(kind) }
        }

        assertEquals(PlatformEventKind.entries, seen)
    }

    /**
     * The one real risk in this file: four one-line classes each hard-coding a
     * [PlatformEventKind] against a manifest `<intent-filter>` action declared in a different
     * file entirely. A copy-paste giving `DateChangedReceiver` the `TIME_SET` kind would pass
     * every other test here; this is the row that catches it. `ManifestContractTest` pairs each
     * class name with its filtered manifest action, so together the two ends of the wiring —
     * the action a receiver is invoked for, and the kind it forwards — are both proven.
     */
    @Test
    fun eachReceiverClassCarriesItsOwnKind() {
        assertEquals(PlatformEventKind.BOOT_COMPLETED, BootCompletedReceiver().kind)
        assertEquals(PlatformEventKind.TIME_SET, TimeSetReceiver().kind)
        assertEquals(PlatformEventKind.TIMEZONE_CHANGED, TimezoneChangedReceiver().kind)
        assertEquals(PlatformEventKind.DATE_CHANGED, DateChangedReceiver().kind)
    }
}
