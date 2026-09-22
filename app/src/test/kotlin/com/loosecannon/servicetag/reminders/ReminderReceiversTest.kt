package com.loosecannon.servicetag.reminders

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Not one of the brief's three named test files (`NotificationChannelsTest`, `PlatformStateTest`,
 * `ManifestContractTest`); `ReminderDispatch` is the static bridge this brief adds so a
 * manifest-instantiated `BroadcastReceiver` can reach the `ReminderTrigger` B06 builds inside
 * `AppGraph` (see the report's deviations section). `PlatformEventReceiver.onReceive` itself
 * — `BroadcastReceiver`, `goAsync()` — cannot be driven from this Robolectric-free JVM unit test;
 * what is unit-testable, and what actually carries risk, is the dispatch object's own state.
 */
class ReminderReceiversTest {

    @Before
    fun resetDispatch() {
        ReminderDispatch.trigger = null
    }

    @Test
    fun beforeAnyBriefWiresOneNoTriggerIsSet() {
        assertNull(ReminderDispatch.trigger)
    }

    @Test
    fun onceSetTheTriggerReceivesExactlyWhatItIsHandedAndInOrder() {
        val seen = mutableListOf<PlatformEventKind>()
        val trigger = ReminderTrigger { kind -> seen += kind }
        ReminderDispatch.trigger = trigger

        PlatformEventKind.entries.forEach { kind -> runBlocking { trigger.onPlatformEvent(kind) } }

        assertEquals(PlatformEventKind.entries, seen)
    }
}
