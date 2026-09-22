package com.loosecannon.servicetag.reminders

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
}
