package com.loosecannon.servicetag.reminders

import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChannelsTest {

    /** Invariant 53, D-20 = B, #24 AC 3 amended: exactly two, with their ratified ids, importances,
     * names and descriptions — and no `supplies` or `sync_problems` channel anywhere. */
    @Test
    fun exactlyTwoChannelsWithTheirRatifiedShape() {
        assertEquals(2, NotificationChannels.CHANNELS.size)

        val due = NotificationChannels.CHANNELS.single { it.id == NotificationChannels.DUE }
        assertEquals("maintenance_due", due.id)
        assertEquals(NotificationManagerCompat.IMPORTANCE_DEFAULT, due.importance)
        assertEquals("Maintenance due", due.name)
        assertEquals("Reminders for maintenance that is due.", due.description)

        val overdue = NotificationChannels.CHANNELS.single { it.id == NotificationChannels.OVERDUE }
        assertEquals("maintenance_overdue", overdue.id)
        assertEquals(NotificationManagerCompat.IMPORTANCE_HIGH, overdue.importance)
        assertEquals("Maintenance overdue", overdue.name)
        assertEquals("Reminders for maintenance that is past due.", overdue.description)

        val ids = NotificationChannels.CHANNELS.map { it.id }
        assertTrue("supplies" !in ids)
        assertTrue("sync_problems" !in ids)
    }

    /**
     * The hazard ("recreating a channel with a fresh importance silently overrides the user's
     * mute") is prevented by construction, not proved by this test alone: `CHANNELS` is a fixed
     * `val` and `ensure` reads no current state before choosing an importance, so the platform's
     * own no-overwrite guarantee always holds. What this asserts, structurally, is that calling
     * `ensure` twice builds the exact same two specs both times — the fact the construction argument
     * depends on.
     */
    @Test
    fun ensureIsIdempotentAndNeverVariesWhatItPasses() {
        val firstPass = mutableListOf<NotificationChannels.Spec>()
        val secondPass = mutableListOf<NotificationChannels.Spec>()

        NotificationChannels.ensure { firstPass += it }
        NotificationChannels.ensure { secondPass += it }

        assertEquals(2, firstPass.size)
        assertEquals(firstPass, secondPass)
    }

    /**
     * Invariant 61, D-22: a denied `POST_NOTIFICATIONS` permission is a fact `PlatformState`
     * reports, never a guard clause. `ensure` takes no permission or platform-state argument at
     * all, so a fake reporting total denial cannot change what it creates — proven here by simply
     * calling `ensure` on its own and getting both channels regardless.
     */
    @Test
    fun channelCreationDoesNotConsultPermissionOrPlatformState() {
        val created = mutableListOf<NotificationChannels.Spec>()
        NotificationChannels.ensure { created += it }
        assertEquals(setOf(NotificationChannels.DUE, NotificationChannels.OVERDUE), created.map { it.id }.toSet())
    }
}
