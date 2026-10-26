package com.loosecannon.servicetag.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * Hides the keyboard and waits until its inset is 0 and has stayed 0 for a few consecutive checks.
 *
 * A form screen's `Scaffold` takes the keyboard's inset (#121), so typing starts a slide-in that shrinks the scroll
 * viewport frame by frame. `performScrollTo` landing mid-animation leaves its node clipped below the keyboard's top.
 * A test that types and then scrolls calls this between the two.
 */
internal fun ComposeTestRule.settleKeyboard(timeoutMs: Long = 5_000L) {
    Espresso.closeSoftKeyboard()
    var zeroChecks = 0
    waitUntil(timeoutMs) {
        var bottom = Int.MAX_VALUE
        runOnUiThread {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
            val insets = activity?.window?.decorView?.let { ViewCompat.getRootWindowInsets(it) }
            bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        zeroChecks = if (bottom == 0) zeroChecks + 1 else 0
        zeroChecks >= 3
    }
    waitForIdle()
}
