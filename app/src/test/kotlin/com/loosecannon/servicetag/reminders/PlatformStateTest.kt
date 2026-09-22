package com.loosecannon.servicetag.reminders

import android.app.usage.UsageStatsManager
import androidx.core.app.NotificationManagerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A fake [PlatformState] — the whole reason the brief makes this an interface (master plan §12, decision 22). */
private class FakePlatformState(
    private val notificationsEnabled: Boolean = true,
    private val importances: Map<String, ChannelImportance> = emptyMap(),
    private val restriction: AppRestriction = AppRestriction.NORMAL,
) : PlatformState {
    override fun notificationsEnabled(): Boolean = notificationsEnabled
    override fun channelImportance(channelId: String): ChannelImportance =
        importances[channelId] ?: ChannelImportance.ABSENT
    override fun appRestricted(): AppRestriction = restriction
}

class PlatformStateTest {

    /**
     * Invariant 53's detection half, #24 AC 3: `MUTED` for a channel the user silenced, `DEFAULT`
     * and `HIGH` for the two live channels, `ABSENT` for an id never created — with a negative
     * control proving `MUTED` is not reported for a normal channel.
     */
    @Test
    fun channelImportanceMapsEveryRawValueOntoTheClosedEnum() {
        assertEquals(ChannelImportance.MUTED, channelImportanceOf(NotificationManagerCompat.IMPORTANCE_NONE))
        assertEquals(ChannelImportance.DEFAULT, channelImportanceOf(NotificationManagerCompat.IMPORTANCE_DEFAULT))
        assertEquals(ChannelImportance.HIGH, channelImportanceOf(NotificationManagerCompat.IMPORTANCE_HIGH))
        assertEquals(ChannelImportance.ABSENT, channelImportanceOf(null))

        // Negative control: a normal, un-muted channel must never read as MUTED.
        assertFalse(channelImportanceOf(NotificationManagerCompat.IMPORTANCE_DEFAULT) == ChannelImportance.MUTED)
    }

    /** #24's two OEM realities, positive case and negative control. */
    @Test
    fun appRestrictionMapsBatteryAndStandbyAndNormal() {
        assertEquals(
            AppRestriction.BATTERY_RESTRICTED,
            appRestrictionOf(backgroundRestricted = true, standbyBucket = UsageStatsManager.STANDBY_BUCKET_ACTIVE),
        )
        assertEquals(
            AppRestriction.STANDBY_RESTRICTED,
            appRestrictionOf(backgroundRestricted = false, standbyBucket = UsageStatsManager.STANDBY_BUCKET_RESTRICTED),
        )
        // Negative control: neither condition holds.
        assertEquals(
            AppRestriction.NORMAL,
            appRestrictionOf(backgroundRestricted = false, standbyBucket = UsageStatsManager.STANDBY_BUCKET_ACTIVE),
        )
    }

    /**
     * The interface's own shape, over the fake: a denied permission is a value `PlatformState`
     * reports, never an exception it throws. This is a contract check, not the invariant 61 proof
     * — `deniedNotificationsDisablesNothingThisBriefOwns` below is, over production code.
     */
    @Test
    fun theInterfaceReportsDenialAsAValueNeverAThrow() {
        val denied = FakePlatformState(notificationsEnabled = false)
        assertFalse(denied.notificationsEnabled())
        assertEquals(ChannelImportance.ABSENT, denied.channelImportance(NotificationChannels.DUE))
        assertEquals(AppRestriction.NORMAL, denied.appRestricted())
    }

    /**
     * Invariant 61, D-22, over production code rather than the fake (B05 fix round 1, finding 5:
     * the previous version of this test asserted only `FakePlatformState`'s own constructor
     * defaults, which cannot fail if `AndroidPlatformState` regressed).
     *
     * Three claims, each over the real thing: **channels exist** — `NotificationChannels.ensure`,
     * the production seam, still creates both when driven directly, because it takes no permission
     * or platform-state argument to gate on at all. **The seam answers** — structurally, neither
     * `NotificationChannels.kt` nor `ReminderReceivers.kt` reads `notificationsEnabled()`,
     * `granted()` or `PlatformState` anywhere, so there is no guard clause in either file for a
     * denial to trip. **Receivers dispatch** — `ReminderTrigger.onPlatformEvent` takes a
     * [PlatformEventKind] and nothing else; a denial has no parameter to arrive through.
     */
    @Test
    fun deniedNotificationsDisablesNothingThisBriefOwns() {
        val created = mutableListOf<NotificationChannels.Spec>()
        NotificationChannels.ensure { created += it }
        assertEquals(setOf(NotificationChannels.DUE, NotificationChannels.OVERDUE), created.map { it.id }.toSet())

        listOf(
            "reminders/NotificationChannels.kt" to sourceFile("kotlin/com/loosecannon/servicetag/reminders/NotificationChannels.kt"),
            "reminders/ReminderReceivers.kt" to sourceFile("kotlin/com/loosecannon/servicetag/reminders/ReminderReceivers.kt"),
        ).forEach { (label, file) ->
            val text = file.readText()
            assertFalse("$label must not read notificationsEnabled()", "notificationsEnabled" in text)
            assertFalse("$label must not call granted()", ".granted(" in text)
            assertFalse("$label must not reference PlatformState", "PlatformState" in text)
        }

        val seen = mutableListOf<PlatformEventKind>()
        val trigger = ReminderTrigger { kind -> seen += kind }
        kotlinx.coroutines.runBlocking { trigger.onPlatformEvent(PlatformEventKind.BOOT_COMPLETED) }
        assertEquals(listOf(PlatformEventKind.BOOT_COMPLETED), seen)
    }
}
