package com.loosecannon.servicetag.reminders

import android.app.Application
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B05 fix round 1, finding 1 (blocking): `grantedOf` is the pure decision `granted()` and
 * `request()` both defer to, extracted so it is testable without a real `Context` — the module's
 * `unitTests.isReturnDefaultValues = true` setup makes this the only testable shape, exactly as
 * `channelImportanceOf` already is.
 */
class NotificationPermissionTest {

    @Test
    fun belowApi33TheAnswerIsTheAppLevelToggleNeverThePermissionCheck() {
        // A pre-33 device has no POST_NOTIFICATIONS permission record at all; a permission-check
        // result of `false` here must not leak through as a denial when notifications are in fact
        // enabled — the whole point of the fix.
        assertTrue(grantedOf(sdkInt = Build.VERSION_CODES.S, notificationsEnabled = true, permissionCheckGranted = false))
        assertFalse(grantedOf(sdkInt = Build.VERSION_CODES.S, notificationsEnabled = false, permissionCheckGranted = true))
    }

    @Test
    fun api33AndAboveTheAnswerIsThePermissionCheckNeverTheToggle() {
        assertTrue(
            grantedOf(sdkInt = Build.VERSION_CODES.TIRAMISU, notificationsEnabled = false, permissionCheckGranted = true),
        )
        assertFalse(
            grantedOf(sdkInt = Build.VERSION_CODES.TIRAMISU, notificationsEnabled = true, permissionCheckGranted = false),
        )
    }

    /**
     * B05 fix round 2, finding 18 (blocking regression): registration must happen from
     * [AndroidNotificationPermission]'s constructor, not on the first read of `resumedActivity`.
     * The lazy variant this guards against registers only when `shouldExplain()`/`request()`
     * first reads the tracker — which happens while an activity is already resumed, so
     * `onActivityResumed` never fires for it and the first `request()` in the process silently
     * reports a denial with no system dialog. This test fails on that lazy variant: it asserts
     * registration happened at construction time, before `resumedActivity` (or anything that
     * would trigger lazy registration) is ever read.
     */
    @Test
    fun constructingTheAndroidBackedSeamRegistersTheTrackerEagerly() {
        val app = Application()
        assertFalse(ResumedActivityTracker.isRegisteredFor(app))

        AndroidNotificationPermission(app)

        assertTrue(ResumedActivityTracker.isRegisteredFor(app))
    }
}
