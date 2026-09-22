package com.loosecannon.servicetag.reminders

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * `ABSENT` is an id [NotificationChannels] never created — a different fact from `MUTED`, which
 * is the user setting a channel it did create to none. B10's findings report the two differently.
 */
enum class ChannelImportance { DEFAULT, HIGH, MUTED, ABSENT }

/** The two OEM realities #24 names, distinguished because the health-finding explanation differs. */
enum class AppRestriction { NORMAL, STANDBY_RESTRICTED, BATTERY_RESTRICTED }

/**
 * The detections #27's health findings and #24's platform reality read, each a closed enum rather
 * than a boolean pair so a caller cannot conflate two different facts (master plan §12, decision
 * 22). One Android-backed implementation, [AndroidPlatformState]; a fake implements this directly
 * for B10's positive/negative-control tests without an emulator.
 */
interface PlatformState {
    /** The app-level notification toggle only — not a per-channel fact. */
    fun notificationsEnabled(): Boolean

    /** The live importance of the channel [channelId], or `ABSENT` if it was never created. */
    fun channelImportance(channelId: String): ChannelImportance

    /** The OEM standby/battery reality this phone is applying to the app right now. */
    fun appRestricted(): AppRestriction
}

/**
 * Maps a raw `NotificationManagerCompat` importance — or its absence — onto the closed enum
 * above. Pure and unit-testable on its own: this brief's JVM unit tests run Robolectric-free, so
 * this mapping, not a live `NotificationManager`, is what a test actually exercises.
 */
internal fun channelImportanceOf(rawImportance: Int?): ChannelImportance = when {
    rawImportance == null -> ChannelImportance.ABSENT
    rawImportance == NotificationManagerCompat.IMPORTANCE_NONE -> ChannelImportance.MUTED
    // >=, not ==: IMPORTANCE_MAX sits above IMPORTANCE_HIGH and this app never creates one, but a
    // range is the correct shape for "at least as urgent as the one channel we ship at HIGH"
    // rather than a value equality that would silently fall through to DEFAULT.
    rawImportance >= NotificationManagerCompat.IMPORTANCE_HIGH -> ChannelImportance.HIGH
    else -> ChannelImportance.DEFAULT
}

/**
 * Battery restriction is checked first: an OEM that has restricted the app's battery use is the
 * more actionable fact, and the two are not mutually exclusive on every OEM's own bucket rules.
 *
 * **Controller ruling 2026-09-22:** "`BATTERY_RESTRICTED` explains before the standby bucket
 * because it is the condition the owner can act on directly." Carried to B10, which reads this
 * precedence when it picks which of the two explanations a phone that is both gets.
 */
internal fun appRestrictionOf(backgroundRestricted: Boolean, standbyBucket: Int): AppRestriction = when {
    backgroundRestricted -> AppRestriction.BATTERY_RESTRICTED
    standbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> AppRestriction.STANDBY_RESTRICTED
    else -> AppRestriction.NORMAL
}

/**
 * The Android-backed [PlatformState]. Every `NotificationManagerCompat` and `ActivityManager` call
 * it makes returns a stub default under this module's JVM unit tests (`unitTests.isReturnDefaultValues
 * = true`, no Robolectric), so the mapping functions above — not this class — are what those tests
 * exercise; this thin wiring is what a later connected suite proves.
 */
class AndroidPlatformState(private val context: Context) : PlatformState {

    override fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun channelImportance(channelId: String): ChannelImportance =
        channelImportanceOf(
            NotificationManagerCompat.from(context).getNotificationChannelCompat(channelId)?.importance,
        )

    override fun appRestricted(): AppRestriction {
        // isBackgroundRestricted() is API 28+; STANDBY_BUCKET_RESTRICTED itself did not arrive
        // until API 30, so STANDBY_RESTRICTED can never actually be reported on 28-29 — harmless,
        // since the constant is a compile-time literal, but worth being honest about here. minSdk
        // is 26, so a pre-28 device is simply never restricted by either mechanism — NORMAL is the
        // correct, not a fallback, answer.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return appRestrictionOf(backgroundRestricted = false, standbyBucket = UsageStatsManager.STANDBY_BUCKET_ACTIVE)
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        return appRestrictionOf(
            backgroundRestricted = activityManager?.isBackgroundRestricted == true,
            standbyBucket = usageStatsManager?.appStandbyBucket ?: UsageStatsManager.STANDBY_BUCKET_ACTIVE,
        )
    }
}
