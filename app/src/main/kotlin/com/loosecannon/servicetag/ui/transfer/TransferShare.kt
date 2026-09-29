package com.loosecannon.servicetag.ui.transfer

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.loosecannon.servicetag.share.ShareIntakeActivity
import java.io.File

/**
 * #77 (C18, C21; R77-1, R77-2) — the one outbound share: a chooser (P77-25) over `ACTION_SEND` of `application/zip`
 * whose stream is the pack's `${applicationId}.files` URI, also carried as `ClipData` with a read grant, and which
 * leaves out ServiceTag's own share target — it refuses the app's own authority (audit §1 (9)). The provider exposes
 * `cache/transfer/` and nothing else, so only a sealed pack can be handed out.
 */
internal object TransferShare {
    const val MIME = "application/zip"

    /** The provider URI of [file], a pack in `cache/transfer/`; any other path is refused by the provider. */
    fun uriOf(context: Context, file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun chooser(context: Context, file: File): Intent {
        val uri = uriOf(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, TransferStrings.SHARE_TITLE).apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, ShareIntakeActivity::class.java)))
        }
    }
}
