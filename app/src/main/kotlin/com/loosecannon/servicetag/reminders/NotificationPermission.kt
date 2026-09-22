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
    /**
     * The runtime permission on API 33+, the app-level notification toggle below it (B05 fix
     * round 2, finding 22: "whether notifications work right now" overstated this — the runtime
     * permission can be granted on 33+ while the app-level toggle, [PlatformState.notificationsEnabled],
     * is off, and that combination is a real, distinct fact this member does not carry).
     */
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
 *
 * **Registration must happen eagerly, from [init], not on first read (B05 fix round 2, finding
 * 18 — a blocking regression fix round 1 introduced).** The lazy version registered the callback
 * only when `resumedActivity` was first read, which only ever happens from `shouldExplain()` or
 * `request()` — both called while an activity is *already* resumed, so `onActivityResumed` never
 * fires for that activity and the first `request()` a process ever makes silently reports a
 * denial with no system dialog shown. Registering here, in the constructor `AppGraph` runs inside
 * `Application.onCreate`, restores the property this depends on: the callback is in place before
 * any activity can resume. [ResumedActivityTracker.ensureRegistered] stays idempotent per
 * `Application`, so this constructor running more than once — the connected suite's extra
 * `AppGraph` builds — still registers exactly one callback per real process.
 */
class AndroidNotificationPermission(private val context: Context) : NotificationPermission {

    init {
        application(context)?.let(ResumedActivityTracker::ensureRegistered)
    }

    private val resumedActivity: ComponentActivity?
        get() = application(context)?.let(ResumedActivityTracker::resumedActivityIn)

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
 * `AppGraph` always hands [AndroidNotificationPermission] the real `Application` instance
 * directly (`ServiceTagApp.onCreate` builds it with `this`), so this checks [context] itself
 * first rather than going straight to `context.applicationContext` — a real difference under this
 * module's `unitTests.isReturnDefaultValues = true` stubs, where `Context.getApplicationContext()`
 * is itself a stubbed method that always answers `null`, regardless of what a real `Application`
 * would return. `NotificationPermissionTest` constructs a bare `Application()` and hands it
 * straight in for exactly this reason.
 */
internal fun application(context: Context): Application? = context as? Application ?: context.applicationContext as? Application

/**
 * One `Application.ActivityLifecycleCallbacks` per `Application`, however many
 * [AndroidNotificationPermission] instances end up calling it (B05 fix round 1, finding 9). Keyed
 * by identity in a `WeakHashMap` so neither the tracker nor the resumed activity it holds outlives
 * the `Application` — production builds exactly one and never revisits this, the connected suite
 * builds several against the one real process and this is what stops that from registering a
 * callback, and its strong `ComponentActivity` reference, once per build.
 *
 * `internal`, not `private` (B05 fix round 2, finding 18): `NotificationPermissionTest` asserts
 * eager registration directly, which needs to see this object.
 */
internal object ResumedActivityTracker {
    private val perApplication = Collections.synchronizedMap(WeakHashMap<Application, PerApplication>())

    /**
     * Registers the callback for [app] if nothing has yet, and does nothing otherwise. The only
     * intended caller is [AndroidNotificationPermission]'s constructor — eagerly, so the callback
     * is in place before any activity can resume (finding 18).
     */
    fun ensureRegistered(app: Application) {
        trackerFor(app)
    }

    fun resumedActivityIn(app: Application): ComponentActivity? = trackerFor(app).resumed

    /** Test-only: whether [app] already has a registered tracker, without creating one. */
    internal fun isRegisteredFor(app: Application): Boolean = perApplication.containsKey(app)

    /**
     * `synchronized`, not a bare `getOrPut` over the `synchronizedMap` (B05 fix round 2, finding
     * 20): `synchronizedMap` locks each individual `get`/`put` call, but `getOrPut` is a `get`
     * then a `put` with no lock held *across* them, so two concurrent first calls for the same
     * `Application` could each pass the `get`, and each register its own callback.
     */
    private fun trackerFor(app: Application): PerApplication = synchronized(perApplication) {
        perApplication.getOrPut(app) {
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
    }

    private class PerApplication {
        @Volatile
        var resumed: ComponentActivity? = null
    }
}
