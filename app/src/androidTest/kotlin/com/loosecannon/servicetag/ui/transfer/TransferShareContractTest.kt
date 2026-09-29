package com.loosecannon.servicetag.ui.transfer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.share.ShareIntakeActivity
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #77 (C21, R77-2; row 36) — the one platform contract of the outbound share, observable only on a device: a pack in
 * `cache/transfer/` resolves through the app's `.files` provider and reads back byte for byte, a file in
 * `cache/transfer-in/`, `cache/transfer-work/` or `files/` has no URI at all, and the chooser's inner intent carries
 * the action, type, stream, `ClipData` and read grant, with ServiceTag's own share target excluded. `Intent` and
 * `ClipData` are real only here (nt-6: no Robolectric in this build).
 *
 * Emulator only (`emulator-5554`). The files are this class's own and are deleted after each case.
 */
@RunWith(AndroidJUnit4::class)
class TransferShareContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val made = mutableListOf<File>()

    @After fun tidy() {
        made.forEach { it.delete() }
    }

    private fun fileIn(dir: File, name: String, bytes: ByteArray): File =
        File(dir.also { it.mkdirs() }, name).also { it.writeBytes(bytes); made += it }

    private val bytes = "Example Transfer Pack bytes".toByteArray()

    @Test fun aPackInTheSharedDirectoryResolvesAndReadsBack() {
        val pack = fileIn(File(context.cacheDir, "transfer"), "servicetag-transfer-2026-09-28-0f1e2d3c.zip", bytes)

        val uri = TransferShare.uriOf(context, pack)

        assertEquals("${context.packageName}.files", uri.authority)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun outsidePathsAreRefused() {
        listOf(
            fileIn(File(context.cacheDir, "transfer-in"), "copy.zip", bytes),
            fileIn(File(context.cacheDir, "transfer-work"), "work.zip", bytes),
            fileIn(context.filesDir, "private.zip", bytes),
        ).forEach { file ->
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                fail("${file.parentFile!!.name}/${file.name} must have no provider URI")
            } catch (expected: IllegalArgumentException) {
                // The provider exposes cache/transfer/ alone.
            }
        }
    }

    @Test fun theChooserCarriesStreamGrantClipDataAndExclusion() {
        val pack = fileIn(File(context.cacheDir, "transfer"), "servicetag-transfer-2026-09-28-0f1e2d3c.zip", bytes)
        val uri = TransferShare.uriOf(context, pack)

        val chooser = TransferShare.chooser(context, pack)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share Transfer Pack", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
        val inner = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("application/zip", inner.type)
        assertEquals(uri, inner.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, inner.clipData!!.getItemAt(0).uri)
        assertNotEquals(0, inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, Parcelable::class.java)!!
            .map { it as ComponentName }
        assertTrue(
            "ServiceTag's own share target is excluded: $excluded",
            ComponentName(context, ShareIntakeActivity::class.java) in excluded,
        )
    }
}
