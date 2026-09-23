package com.loosecannon.servicetag.links

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * What a phone with nothing to handle a URI says, ratified in §10 — and it names **no URI**: spec
 * §4.4 forbids a visible refusal from naming one, and the line this replaced printed the whole URI
 * on a second row of the toast (plan §18.8).
 *
 * `internal const` so a JVM test can hold it against the ratified text without an activity, and so
 * the References section's snackbar draws the same characters rather than a second copy of them.
 */
internal const val NO_HANDLER_MESSAGE = "No app can open this link"

/** Fires `ACTION_VIEW` for a URI the caller has already checked; never crashes on a missing handler. */
object LinkLauncher {
    fun open(activity: Activity, uri: String): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        true
    } catch (e: ActivityNotFoundException) {
        noHandler(activity)
    } catch (e: SecurityException) {
        // A handler exists but will not take the call from us (a permission-guarded activity).
        noHandler(activity)
    }

    /** No URI parameter: there is nothing left to put one in, and §4.4 says there must not be. */
    private fun noHandler(activity: Activity): Boolean {
        Toast.makeText(activity, NO_HANDLER_MESSAGE, Toast.LENGTH_LONG).show()
        return false
    }
}
