package com.loosecannon.servicetag.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2.6 — the surfaces that are gone, asked of the platform. A grep proves the source tree; this
 * proves the manifest the APK actually shipped, which is what decides whether a share sheet or a
 * `servicetag://link/…` URI can reach ServiceTag at all. The asset host is asserted in the same
 * breath so a manifest that lost everything fails as loudly as one that kept the link host.
 */
@RunWith(AndroidJUnit4::class)
class RemovedSurfacesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun handlersHere(intent: Intent): Int =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .count { it.activityInfo.packageName == context.packageName }

    /**
     * 1.3.0 reverses exactly one of 2.6's removals: #43 brings a share target back, so this case
     * now says how many answer and which kind. **One** activity in this package answers
     * `ACTION_SEND` — the intake screen — while a multi-item send stays unanswered, which is the
     * half of the 2.6 fact that has not changed and is what R4 drives by intent on the emulator.
     */
    @Test fun exactlyOneActivityHereTakesASharedItemAndNoneTakesSeveral() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.invalid/x")
        assertEquals(1, handlersHere(send))

        val several = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain")
        assertEquals(0, handlersHere(several))
    }

    @Test fun theLinkHostIsUnregisteredAndTheAssetHostStillIsNot() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        assertEquals(0, handlersHere(Intent(Intent.ACTION_VIEW, Uri.parse("servicetag://link/$id"))))
        assertEquals(1, handlersHere(Intent(Intent.ACTION_VIEW, Uri.parse("servicetag://asset/$id"))))
    }
}
