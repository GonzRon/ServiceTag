package com.loosecannon.servicetag.testsender

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * A sharer with its own UID (#62). ServiceTag's connected `ShareBoundaryTest` starts this activity
 * with a `command` extra; it fires one real `ACTION_SEND` at ServiceTag's share target and
 * finishes. It never reads anything of ServiceTag's and asks for nothing back.
 *
 * | command          | what arrives at the share target                                   |
 * |------------------|--------------------------------------------------------------------|
 * | `send_text`      | `text/plain`, `EXTRA_TEXT` = the `text` extra                      |
 * | `send_file`      | the fixture's type, `EXTRA_STREAM` = its `content://` URI, `ClipData` from the URI, `FLAG_GRANT_READ_URI_PERMISSION` |
 * | `send_bad_grant` | the same stream with no grant flag and no `ClipData`               |
 *
 * The target is named explicitly, so no chooser and no other share target is ever involved.
 */
class SenderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation is not a second request: one start, one share.
        if (savedInstanceState == null) {
            shareFor(intent)?.let(::startActivity)
        }
        finish()
    }

    private fun shareFor(request: Intent): Intent? {
        val share = Intent(Intent.ACTION_SEND)
            .setClassName(TARGET_PACKAGE, TARGET_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when (val command = request.getStringExtra(EXTRA_COMMAND)) {
            SEND_TEXT -> share
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, request.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT)

            SEND_FILE -> {
                val fixture = fixtureFrom(request) ?: return null
                val uri = uriFor(fixture)
                share.setType(TYPES.getValue(fixture))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                share.clipData = ClipData(fixture, arrayOf(TYPES.getValue(fixture)), ClipData.Item(uri))
            }

            SEND_BAD_GRANT -> {
                val fixture = fixtureFrom(request) ?: return null
                share.setType(TYPES.getValue(fixture))
                    .putExtra(Intent.EXTRA_STREAM, uriFor(fixture))
            }

            else -> {
                Log.w(TAG, "unknown command '$command'; nothing was sent")
                return null
            }
        }
        return share
    }

    /** Only the two named fixtures: this activity is exported, so the name is never a path. */
    private fun fixtureFrom(request: Intent): String? {
        val name = request.getStringExtra(EXTRA_FIXTURE) ?: DEFAULT_FIXTURE
        if (name !in TYPES) {
            Log.w(TAG, "unknown fixture '$name'; nothing was sent")
            return null
        }
        return name
    }

    /** Copied out of assets once, into the one directory the FileProvider serves. */
    private fun uriFor(fixture: String): Uri {
        val file = File(File(filesDir, FIXTURE_DIR), fixture)
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            assets.open("$FIXTURE_DIR/$fixture").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return FileProvider.getUriForFile(this, AUTHORITY, file)
    }

    private companion object {
        const val TAG = "ShareTestSender"

        const val TARGET_PACKAGE = "com.loosecannon.servicetag"
        const val TARGET_ACTIVITY = "com.loosecannon.servicetag.share.ShareIntakeActivity"
        const val AUTHORITY = "com.loosecannon.servicetag.testsender.fixtures"

        const val EXTRA_COMMAND = "command"
        const val EXTRA_FIXTURE = "fixture"
        const val EXTRA_TEXT = "text"

        const val SEND_TEXT = "send_text"
        const val SEND_FILE = "send_file"
        const val SEND_BAD_GRANT = "send_bad_grant"

        const val FIXTURE_DIR = "fixtures"
        const val DEFAULT_FIXTURE = "mower-manual.pdf"
        const val DEFAULT_TEXT = "https://example-mower.invalid/xt1/manual.pdf"

        /** Each fixture's type, from its extension. */
        val TYPES = mapOf(
            "mower-manual.pdf" to "application/pdf",
            "mower-shot.png" to "image/png",
        )
    }
}
