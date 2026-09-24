package com.loosecannon.servicetag.share

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the installed package manager resolves for a share (#62, planning policy layer 3): no
 * navigation, no second app. `ManifestContractTest` reads the source manifest's filter on the JVM;
 * this asks the framework what that filter actually matches once the APK is installed, which is
 * the answer a share sheet gets. It replaces the shell's query-activities step.
 *
 * Only this package's answers are asserted: which other apps also take a share is the device's
 * business, not ServiceTag's.
 */
@RunWith(AndroidJUnit4::class)
class ShareResolutionContractTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val intake = ComponentName(
        context.packageName,
        "com.loosecannon.servicetag.share.ShareIntakeActivity",
    )

    @Test fun aTextShareResolvesToTheIntakeAlone() =
        assertEquals(listOf(intake), resolvedHere(Intent.ACTION_SEND, "text/plain"))

    @Test fun aPdfShareResolvesToTheIntakeAlone() =
        assertEquals(listOf(intake), resolvedHere(Intent.ACTION_SEND, "application/pdf"))

    @Test fun aJpegShareResolvesToTheIntakeAlone() =
        assertEquals(listOf(intake), resolvedHere(Intent.ACTION_SEND, "image/jpeg"))

    /** The multi-item send is a wider door than D-6 opened, and nothing here answers it. */
    @Test fun aMultiItemShareResolvesToNothingHere() =
        assertEquals(
            emptyList<ComponentName>(),
            resolvedHere(Intent.ACTION_SEND_MULTIPLE, "application/pdf"),
        )

    /** The activities in this package that answer an implicit share, as a share sheet asks. */
    private fun resolvedHere(action: String, type: String): List<ComponentName> {
        @Suppress("DEPRECATION") // the flags overload is API 33+; minSdk is 26
        val all = context.packageManager.queryIntentActivities(
            Intent(action).setType(type),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return all
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.packageName == context.packageName }
    }
}
