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
     * Invariant 61, D-22: a denied permission is a fact this interface reports, not an error it
     * raises. Nothing in this brief throws, guards or disables anything else on the strength of
     * `notificationsEnabled() == false`; `NotificationChannelsTest` proves the channels' half,
     * `ManifestContractTest` proves the receivers' half (the manifest is unconditional), and this
     * asserts the seam itself just keeps answering.
     */
    @Test
    fun deniedNotificationsIsAFactNotAThrow() {
        val denied = FakePlatformState(notificationsEnabled = false)
        assertFalse(denied.notificationsEnabled())
        // Every other member still answers normally — nothing about the denial disables the seam.
        assertEquals(ChannelImportance.ABSENT, denied.channelImportance(NotificationChannels.DUE))
        assertEquals(AppRestriction.NORMAL, denied.appRestricted())
    }
}
