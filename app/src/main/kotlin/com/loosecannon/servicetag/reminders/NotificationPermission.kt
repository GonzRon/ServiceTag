package com.loosecannon.servicetag.reminders

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The rationale B14's editor shows before it requests `POST_NOTIFICATIONS`, on first schedule
 * creation and nowhere else (spec §5.1). RATIFIED verbatim (master plan §17); this brief carries
 * the constant, B14 draws it.
 */
const val NOTIFICATION_PERMISSION_RATIONALE =
    "ServiceTag needs notification permission to remind you when maintenance is due."

/**
 * The permission state and the request plumbing (master plan §12, decision 23). `request()` never
 * throws on a denial — a denial is an answer, not an error (D-22) — and its only intended caller
 * is B14's editor: nothing under the navigation host, `MainActivity.kt` or `ServiceTagApp.kt` may
 * reference this type (#24 AC 1). One Android-backed implementation, [AndroidNotificationPermission].
 */
interface NotificationPermission {
    /** Whether notifications work right now — the runtime permission on API 33+, the app-level toggle below it. */
    fun granted(): Boolean

    /** Whether the OS wants a rationale shown before the next request (a prior denial, not "never ask again"). */
    fun shouldExplain(): Boolean

    /** Requests the permission if it is not already granted, and returns the owner's answer. */
    suspend fun request(): Boolean
}

/**
 * `POST_NOTIFICATIONS` does not exist as a platform permission below API 33 — there is no runtime
 * record for `ContextCompat.checkSelfPermission` to find, so on `minSdk = 26` the honest fact is
 * the app-level notification toggle `NotificationManagerCompat.areNotificationsEnabled()` reads,
 * not a permission check that would report every pre-33 device as denied. Fixed API 33 (B05 fix
 * round 1, finding 1): the KDoc previously claimed `checkSelfPermission` was correct pre-33, which
 * it is not — this function, and this comment, are what replaced that claim.
 */
internal fun grantedOf(sdkInt: Int, notificationsEnabled: Boolean, permissionCheckGranted: Boolean): Boolean =
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) notificationsEnabled else permissionCheckGranted

/**
 * The Android-backed [NotificationPermission].
 *
 * The actual system dialog can only be driven from a live `Activity`, and B14's editor is the
 * only caller `request()` is meant to have — never `MainActivity` or the navigation host, which
 * this brief's own structural test forbids referencing this type at all. So this class reads the
 * resumed activity from [ResumedActivityTracker] rather than asking any excluded file to hand one
 * in, and rather than registering its own `ActivityLifecycleCallbacks` per instance: `AppGraph` is
 * built more than once against the same process in the connected suite, and a callback per
 * instance is a callback that never unregisters (B05 fix round 1, finding 9).
 */
class AndroidNotificationPermission(private val context: Context) : NotificationPermission {

    private val resumedActivity: ComponentActivity?
        get() = (context.applicationContext as? Application)?.let(ResumedActivityTracker::resumedActivityIn)

    override fun granted(): Boolean = grantedOf(
        sdkInt = Build.VERSION.SDK_INT,
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        permissionCheckGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED,
    )

    override fun shouldExplain(): Boolean {
        // Below API 33 there is no runtime permission and so no rationale to show before one.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val activity = resumedActivity ?: return false
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    override suspend fun request(): Boolean {
        // Below API 33, or once already granted: the answer is the fact above, and nothing is
        // ever prompted for a permission the platform does not have (B05 fix round 1, finding 1).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return granted()
        if (granted()) return true
        // No resumed activity to drive a system dialog from: "could not ask" is not "the owner
        // said no", so the honest answer is the same fact `granted()` already reports, not a
        // fabricated denial (B05 fix round 1, finding 8).
        val activity = resumedActivity ?: return granted()
        return suspendCancellableCoroutine { continuation ->
            // A unique key per call: a fixed key would let a second concurrent `request()`
            // overwrite the first's registration and orphan its continuation forever (B05 fix
            // round 1, finding 7).
            val requestKey = "$REQUEST_KEY#${UUID.randomUUID()}"
            var launcher: ActivityResultLauncher<String>? = null
            launcher = activity.activityResultRegistry.register(
                requestKey,
                ActivityResultContracts.RequestPermission(),
            ) { isGranted ->
                launcher?.unregister()
                if (continuation.isActive) continuation.resumeWith(Result.success(isGranted))
            }
            // A cancelled request — the editor leaving composition, the caller's scope dying —
            // must not leave the launcher registered in the activity's registry (finding 7). A
            // configuration change or process death while the system dialog is up never resumes
            // this continuation either way; B14 re-reads granted() on resume rather than waiting
            // on request() across one of those.
            continuation.invokeOnCancellation { launcher.unregister() }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val REQUEST_KEY = "com.loosecannon.servicetag.reminders.POST_NOTIFICATIONS"
    }
}

/**
 * One `Application.ActivityLifecycleCallbacks` per `Application`, however many
 * [AndroidNotificationPermission] instances end up reading it (B05 fix round 1, finding 9). Keyed
 * by identity in a `WeakHashMap` so neither the tracker nor the resumed activity it holds outlives
 * the `Application` — production builds exactly one and never revisits this, the connected suite
 * builds several against the one real process and this is what stops that from registering a
 * callback, and its strong `ComponentActivity` reference, once per build.
 */
private object ResumedActivityTracker {
    private val perApplication = Collections.synchronizedMap(WeakHashMap<Application, PerApplication>())

    fun resumedActivityIn(app: Application): ComponentActivity? = trackerFor(app).resumed

    private fun trackerFor(app: Application): PerApplication = perApplication.getOrPut(app) {
        val tracker = PerApplication()
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    tracker.resumed = activity as? ComponentActivity
                }

                override fun onActivityPaused(activity: Activity) {
                    if (tracker.resumed === activity) tracker.resumed = null
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (tracker.resumed === activity) tracker.resumed = null
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            },
        )
        tracker
    }

    private class PerApplication {
        @Volatile
        var resumed: ComponentActivity? = null
    }
}
