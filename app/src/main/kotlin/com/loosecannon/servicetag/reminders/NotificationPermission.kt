package com.loosecannon.servicetag.reminders

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    /** Whether `POST_NOTIFICATIONS` is granted right now. */
    fun granted(): Boolean

    /** Whether the OS wants a rationale shown before the next request (a prior denial, not "never ask again"). */
    fun shouldExplain(): Boolean

    /** Requests the permission if it is not already granted, and returns the owner's answer. */
    suspend fun request(): Boolean
}

/**
 * The Android-backed [NotificationPermission]. `POST_NOTIFICATIONS` is an API 33+ runtime
 * permission; `ContextCompat.checkSelfPermission` reports a pre-33 device as granted, which is
 * correct — there is no runtime prompt to answer there.
 *
 * The actual system dialog can only be driven from a live `Activity`, and B14's editor is the
 * only caller `request()` is meant to have — never `MainActivity` or the navigation host, which
 * this brief's own structural test forbids referencing this type at all. So this class tracks the
 * resumed activity itself, through `Application.ActivityLifecycleCallbacks`, rather than asking
 * any excluded file to hand one in.
 */
class AndroidNotificationPermission(private val context: Context) : NotificationPermission {

    @Volatile
    private var resumedActivity: ComponentActivity? = null

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumedActivity = activity as? ComponentActivity
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumedActivity === activity) resumedActivity = null
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    override fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun shouldExplain(): Boolean {
        val activity = resumedActivity ?: return false
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
    }

    override suspend fun request(): Boolean {
        if (granted()) return true
        val activity = resumedActivity ?: return false
        return suspendCancellableCoroutine { continuation ->
            var launcher: ActivityResultLauncher<String>? = null
            launcher = activity.activityResultRegistry.register(
                REQUEST_KEY,
                ActivityResultContracts.RequestPermission(),
            ) { isGranted ->
                launcher?.unregister()
                if (continuation.isActive) continuation.resumeWith(Result.success(isGranted))
            }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val REQUEST_KEY = "com.loosecannon.servicetag.reminders.POST_NOTIFICATIONS"
    }
}
