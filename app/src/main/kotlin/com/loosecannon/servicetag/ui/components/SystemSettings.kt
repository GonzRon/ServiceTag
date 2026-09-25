package com.loosecannon.servicetag.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The app's own details page: where an OEM's background restriction is turned off, where a
 * hardened build's network permission is allowed again, and a screen every Android build has.
 * Deliberately **not** a battery-exemption request (#24) and not a permission request (#66).
 *
 * Hoisted from `ReminderHealthScreen` so the Developer API screen opens the same page the same way.
 */
internal fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

/**
 * A settings screen an OEM has removed is a dead button, not a crash: the screen that offered it
 * stays up and still explains what is wrong, which is more than a stack trace would.
 */
internal fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing to do and nothing to say: no message is ratified for "this phone has no such
        // screen", and the screen the owner is looking at already explains the problem.
    }
}
