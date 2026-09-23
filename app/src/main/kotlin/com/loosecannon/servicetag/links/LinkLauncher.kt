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
    /**
     * [notify] is how a caller says it draws the refusal itself. The References section maps the
     * `false` below to a snackbar carrying [NO_HANDLER_MESSAGE], and a toast over the top of it is
     * the same sentence twice at once, so that surface passes `false`; the Settings caller has
     * nowhere of its own to put it and keeps the default.
     */
    fun open(activity: Activity, uri: String, notify: Boolean = true): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        true
    } catch (e: ActivityNotFoundException) {
        noHandler(notify) { toast(activity) }
    } catch (e: SecurityException) {
        // A handler exists but will not take the call from us (a permission-guarded activity).
        noHandler(notify) { toast(activity) }
    }

    /** No URI parameter: there is nothing left to put one in, and §4.4 says there must not be. */
    private fun toast(activity: Activity) =
        Toast.makeText(activity, NO_HANDLER_MESSAGE, Toast.LENGTH_LONG).show()
}

/**
 * The whole of a missing handler with no Android type in it: the answer is `false` either way, and
 * the notice is asked for only when the caller is not drawing one. It sits outside the object so a
 * JVM test can watch whether the toast was requested — `Activity` and `Toast` are stubs off the
 * device, so a rule expressed over them could only ever be proved on one.
 */
internal fun noHandler(notify: Boolean, notice: () -> Unit): Boolean {
    if (notify) notice()
    return false
}
