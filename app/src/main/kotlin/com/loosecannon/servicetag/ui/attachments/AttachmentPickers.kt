package com.loosecannon.servicetag.ui.attachments

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.loosecannon.servicetag.di.AppGraph
import java.io.FilterInputStream
import java.io.InputStream
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The two launchers and the one intent the section needs, kept out of the screens: a multi-select
 * document picker, a camera capture into a `FileProvider` cache URI, and `ACTION_VIEW`.
 */
class AttachmentPickers internal constructor(
    val addFiles: () -> Unit,
    val takePhoto: () -> Unit,
    val open: (AttachmentRowState) -> Unit,
)

/** #67, C5: one document, any MIME — the asset editor's Purchase and Key documents affordances. */
class DocumentPicker internal constructor(val pick: () -> Unit)

/**
 * #67, C5: a single [OpenDocument][ActivityResultContracts.OpenDocument] pick whose [PickedFile.open]
 * closes over the **application** context's resolver, never the Activity's — the editor's staged
 * file must survive exactly as long as the grant does, and no longer be tied to the screen that
 * happened to be on top when it was picked.
 *
 * [onPicked] is handed the lookup, not its answer: the name and size are a provider query, and a
 * cloud provider may take its time over it, so the caller runs it off the main thread — and knows,
 * from the moment the picker returns, that a file is on its way (R-1).
 */
@Composable
fun rememberDocumentPicker(
    onPicked: (lookup: suspend () -> PickedFile) -> Unit,
    onNoFilePicker: () -> Unit,
): DocumentPicker {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked { resolver.pickedFile(uri) }
    }
    return DocumentPicker(
        pick = { runCatching { launcher.launch(arrayOf("*/*")) }.onFailure { onNoFilePicker() } },
    )
}

@Composable
fun rememberAttachmentPickers(
    graph: AppGraph,
    /** Where a stored document lives, as the section's ViewModel answers it. */
    viewUri: (String) -> Uri?,
    onPicked: (List<PickedFile>) -> Unit,
    onNoViewer: () -> Unit,
    /** Nothing on this device can take a picture. Same shape as [onNoViewer], different wording. */
    onNoCamera: () -> Unit = onNoViewer,
    /**
     * Nothing on this device can hand files over. A stripped ROM with no `ACTION_OPEN_DOCUMENT`
     * handler is the case; "No app can open this file" would be the wrong sentence for it, because
     * no file has been chosen yet.
     */
    onNoFilePicker: () -> Unit = onNoViewer,
): AttachmentPickers {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()

    // The capture URI has to survive both the launch and a rotation while the camera is up.
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        // Reading a provider's display name and size is a query per URI, and there may be eight
        // of them: the callback lands on the main thread, so the asking does not stay there.
        if (!uris.isNullOrEmpty()) {
            scope.launch {
                onPicked(withContext(Dispatchers.IO) { uris.map { uri -> resolver.pickedFile(uri) } })
            }
        }
    }

    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCapture?.toUri()
        pendingCapture = null
        when {
            uri == null -> Unit
            // The copy is what consumes the temp file: closing the stream deletes it (spec §8.1).
            saved -> onPicked(listOf(resolver.cameraFile(uri)))
            // Cancelled, so nothing will ever read it.
            else -> resolver.deleteQuietly(uri)
        }
    }

    // Not remembered: three lambdas per composition are cheaper than the stale captures a
    // `remember` here would freeze in place.
    return AttachmentPickers(
        addFiles = { runCatching { pickFiles.launch(arrayOf("*/*")) }.onFailure { onNoFilePicker() } },
        takePhoto = {
            val uri = graph.cameraCaptureUri()
            pendingCapture = uri.toString()
            runCatching { capture.launch(uri) }.onFailure {
                pendingCapture = null
                resolver.deleteQuietly(uri)
                onNoCamera()
            }
        },
        open = { row ->
            scope.launch {
                // Resolving a document under the tree is several provider queries: not the main
                // thread's work, even for one tap (spec §8.2).
                val uri = withContext(Dispatchers.IO) { viewUri(row.locator) }
                val intent = uri?.let { viewIntent(it, row.mimeType) }
                if (intent == null || intent.resolveActivity(context.packageManager) == null) {
                    onNoViewer()
                } else {
                    runCatching { context.startActivity(intent) }.onFailure { onNoViewer() }
                }
            }
        },
    )
}

/** `ACTION_VIEW` on the document's own URI, with the one-shot read grant the viewer needs. */
private fun viewIntent(uri: Uri, mimeType: String): Intent =
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

/**
 * #84 (C12): the name a pick is called. The provider's own name when it reports one that is not
 * blank (kept verbatim, spaces included); else the last `/`-part of the URI's last path segment when
 * that is not blank; else "file". A provider may answer an empty name, and a blank one would leave
 * the editor nothing to show or fix.
 */
internal fun pickedName(reported: String?, uriSegment: String?): String =
    reported?.takeIf { it.isNotBlank() }
        ?: uriSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "file"

/** Display name, size and mime as the provider reports them; the stream is opened on demand. */
private fun ContentResolver.pickedFile(uri: Uri): PickedFile {
    var reported: String? = null
    var size: Long? = null
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameAt = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameAt >= 0 && !cursor.isNull(nameAt)) reported = cursor.getString(nameAt)
                    val sizeAt = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeAt >= 0 && !cursor.isNull(sizeAt)) size = cursor.getLong(sizeAt)
                }
            }
    }
    return PickedFile(
        displayName = pickedName(reported, uri.lastPathSegment),
        mimeType = getType(uri) ?: "application/octet-stream",
        sizeBytes = size,
        open = { openInputStream(uri) ?: error("no bytes at $uri") },
    )
}

/**
 * A capture in the cache directory. The name is the day rather than the uuid the file is called:
 * the person renames it from the sheet if they want to, but a hex string is no one's photo.
 *
 * `sizeBytes` is null on purpose — the provider does not know it yet, so `AddAttachment` applies
 * the size guard to what the store actually wrote.
 */
private fun ContentResolver.cameraFile(uri: Uri): PickedFile = PickedFile(
    displayName = "Photo ${LocalDate.now()}.jpg",
    mimeType = "image/jpeg",
    sizeBytes = null,
    fromCamera = true,
    open = { DeleteOnClose(openInputStream(uri) ?: error("no bytes at $uri")) { deleteQuietly(uri) } },
)

/** A temp file's bytes are read exactly once; closing the stream is the moment to let it go. */
private class DeleteOnClose(
    stream: InputStream,
    private val onClosed: () -> Unit,
) : FilterInputStream(stream) {
    override fun close() {
        try {
            super.close()
        } finally {
            onClosed()
        }
    }
}

/** A cache file the store already copied. Failing to delete it is the OS's problem, not a report. */
private fun ContentResolver.deleteQuietly(uri: Uri) {
    runCatching { delete(uri, null, null) }
}
