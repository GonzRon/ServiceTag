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
 * | `send_bad_grant` | the same stream and `ClipData`, with no grant flag                 |
 *
 * The target is named explicitly, so no chooser and no other share target is ever involved.
 *
 * **Why `send_bad_grant` keeps its `ClipData`.** On API 37 a flagless `ACTION_SEND` with a stream
 * and no `ClipData` is granted anyway: `Instrumentation.execStartActivity` calls
 * `Intent.migrateExtraStreamToClipData`, which copies `EXTRA_STREAM` into `ClipData` and adds
 * `FLAG_GRANT_READ_URI_PERMISSION` itself, logging "Implicit URI grant for
 * android.intent.action.SEND action will be discontinued from Android 18 onwards. Please set the
 * grant explicitly in the app." A share that already carries `ClipData` is left alone, so this one
 * arrives with no grant at all — and `send_file` sets its flag explicitly rather than lean on a
 * platform behaviour that is on its way out.
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
                // The grant is set here, explicitly: the platform's implicit one is being retired.
                share.setType(TYPES.getValue(fixture))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                share.clipData = ClipData(fixture, arrayOf(TYPES.getValue(fixture)), ClipData.Item(uri))
            }

            SEND_BAD_GRANT -> {
                val fixture = fixtureFrom(request) ?: return null
                val uri = uriFor(fixture)
                share.setType(TYPES.getValue(fixture))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                // No grant flag. The ClipData is what keeps it that way: without it the platform
                // would migrate the stream and grant it anyway (see the class comment).
                share.clipData = ClipData(fixture, arrayOf(TYPES.getValue(fixture)), ClipData.Item(uri))
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
        /**
         * **Outside ServiceTag's `applicationId` namespace on purpose.** ServiceTag refuses a
         * stream whose authority is its own application id or anything under it
         * (`core/src/main/kotlin/com/loosecannon/servicetag/core/references/StreamSourcePolicy.kt:34`,
         * `host == it || host.startsWith("$it.")`, fed `BuildConfig.APPLICATION_ID` at
         * `app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt:358-359`). An authority
         * under `com.loosecannon.servicetag.` — this package's own id included — would be refused
         * as ServiceTag's own before any grant was looked at.
         */
        const val AUTHORITY = "com.loosecannon.sharetestsender.fixtures"

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
