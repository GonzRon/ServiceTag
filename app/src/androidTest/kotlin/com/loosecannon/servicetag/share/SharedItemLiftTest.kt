package com.loosecannon.servicetag.share

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.references.StreamSourcePolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android lift itself — a real `Intent`, a real `content://` `Uri` from a **foreign** provider
 * and the platform's own `ContentResolver`. Everything above this line is proved on the JVM
 * against `decideShare`; what only a device can execute is the typed extra read, the cursor
 * handling and null-column guards in `ResolverStream.facts()`, the bounded read loop, the `Uri`
 * pairing and the byte source `AddAttachment` is handed.
 *
 * The stream comes from `MediaStore`, which is the shape of the real case — a document or a photo
 * handed over by a provider in another package, under an authority that is not this app's. Nothing
 * is added to any manifest and no permission is needed: an app may write its own entry under
 * `Downloads` and read it back, and every staged row is deleted again afterwards.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class SharedItemLiftTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    private val streamPolicy = StreamSourcePolicy(
        setOf(context.packageName, "${context.packageName}.files"),
    )
    private val linkPolicy = LinkLaunchPolicy()

    private val staged = mutableListOf<Uri>()

    @After fun tearDown() {
        staged.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        staged.clear()
    }

    /**
     * One file, handed over by a provider that is not this app's.
     *
     * `MediaStore.Downloads` and `IS_PENDING` are API 29+ while `minSdk` is 26, so the two cases
     * that stage a file say so out loud. The suite is emulator-only and the emulator is far above
     * that floor, so nothing is skipped in practice; the assumption is here so a run on an older
     * image reports the reason instead of an obscure failure.
     */
    private fun serve(displayName: String, mimeType: String, body: ByteArray): Uri {
        assumeTrue(
            "MediaStore.Downloads is API 29+",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            // Pending until the bytes are there; clearing it is what makes the store finalise the
            // entry and report a size, which is the column the reader has to carry.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
        ) { "the media store would not take a staged file" }
        staged += uri
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(body) }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    }

    /** What the provider itself says about the file, which is what the reader must carry. */
    private fun factsOf(uri: Uri): Pair<String, Long?> {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0) to cursor.getLong(1).takeIf { !cursor.isNull(1) }
            }
        }
        error("the provider reported nothing about the staged file")
    }

    private fun sendIntent(type: String, stream: Uri? = null, text: String? = null): Intent =
        Intent(Intent.ACTION_SEND).setType(type).apply {
            stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }

    private fun read(intent: Intent) = readShare(intent, resolver, streamPolicy, linkPolicy)

    /** The byte arm end to end: the provider's own name, type and size, and then its bytes. */
    @Test fun aForeignProvidersStreamIsReadThroughTheRealResolver() {
        val body = "%PDF-1.7 deck belt diagram".toByteArray()
        val uri = serve("deck-belt.pdf", "application/pdf", body)
        val (reported, size) = factsOf(uri)

        val share = read(sendIntent("application/pdf", stream = uri))

        // Field for field what the provider said, which is the whole of the cursor path.
        assertEquals(
            SharedItem.Bytes(uri, reported, "application/pdf", size),
            share.asSharedItem(),
        )
        assertEquals("the provider's size is the file's", body.size.toLong(), size)
        assertTrue(reported, reported.endsWith(".pdf"))
        assertEquals(uri, share.streamUri)
        // The one byte source a share ever builds, opened exactly as `AddAttachment` opens it.
        val copied = checkNotNull(share.bytes).open().use { it.readBytes() }
        assertEquals(String(body), String(copied))
    }

    /** The uri-list arm: a stream, no text extra, and the first usable line becomes the link. */
    @Test fun aUriListStreamIsReadAsTextThroughTheRealResolver() {
        val body = "# what this list is\r\n\r\nhttps://example-mower.invalid/xt1/manual.pdf\r\n"
        val uri = serve("links.txt", "text/uri-list", body.toByteArray())

        val share = read(sendIntent("text/uri-list", stream = uri))

        assertEquals(
            SharedItem.Link("https://example-mower.invalid/xt1/manual.pdf", null),
            share.asSharedItem(),
        )
        // A uri-list never reaches the attachment path, so no byte source is ever built for one.
        assertNull(share.bytes)
    }

    /**
     * I-9 on the real resolver: a `content://` URI under ServiceTag's own authority is refused —
     * the app's own `FileProvider` is not exported, and intake must not be the door that reads it.
     */
    @Test fun ourOwnAuthorityIsRefused() {
        val ours = Uri.parse("content://${context.packageName}.files/camera/whatever.jpg")

        val share = read(sendIntent("image/jpeg", stream = ours))

        assertEquals(SharedItem.Refused(IntakeRefusal.STREAM_NOT_ACCEPTED), share.asSharedItem())
        assertNull(share.bytes)
    }

    /**
     * The **order**, on the real resolver. The authority is ServiceTag's own `FileProvider` and the
     * path names a root it is not configured with, so a query for it throws. The answer is still
     * the I-9 refusal — which is only possible if the predicate ran before anything was asked; a
     * reader that queried first would have reported the read failure instead.
     */
    @Test fun theRefusalComesBeforeTheResolverIsAskedAnything() {
        val unconfigured = Uri.parse("content://${context.packageName}.files/no-such-root/x.jpg")

        assertEquals(
            SharedItem.Refused(IntakeRefusal.STREAM_NOT_ACCEPTED),
            read(sendIntent("application/pdf", stream = unconfigured)).asSharedItem(),
        )
    }

    /** The same refusal for a scheme the resolver would happily read with this app's own uid. */
    @Test fun aFileUriIsRefusedOnTheRealResolverToo() {
        val onDisk = Uri.fromFile(context.cacheDir.resolve("nothing-here.jpg"))

        assertEquals(
            SharedItem.Refused(IntakeRefusal.STREAM_NOT_ACCEPTED),
            read(sendIntent("image/jpeg", stream = onDisk)).asSharedItem(),
        )
    }

    /**
     * I-11 (#63) on the real resolver: an authority no provider answers to, which from here is
     * also what a sharer hidden by package visibility looks like. `query` returns null without
     * throwing, and that is the read failure at read time, with no byte source built.
     */
    @Test fun aStreamNoProviderAnswersForIsUnreadable() {
        val nowhere = Uri.parse("content://org.example.nosuch.files/x.pdf")

        val share = read(sendIntent("application/pdf", stream = nowhere))

        assertEquals(SharedItem.Refused(IntakeRefusal.UNREADABLE), share.asSharedItem())
        assertNull(share.bytes)
    }

    /** No stream at all: the text arm, through the same real `Intent`. */
    @Test fun aTextOnlyShareTakesTheTextArm() {
        val share = read(
            sendIntent("text/plain", text = "https://example-mower.invalid/xt1/manual.pdf"),
        )

        assertEquals(
            SharedItem.Link("https://example-mower.invalid/xt1/manual.pdf", null),
            share.asSharedItem(),
        )
        assertNull(share.streamUri)
        assertNull(share.bytes)
    }
}
