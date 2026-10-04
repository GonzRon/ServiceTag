package com.loosecannon.servicetag.backup

import android.content.ContentResolver
import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.ui.backup.BackupSetSink
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A folder the owner picked for *this* export only — no persisted grant here (spec §11.2; 3R turns
 * it into a remembered destination). Two files go in, both stamped, and the data file is removed
 * again if the artifacts file cannot be written: half a set is worse than none, because a restore
 * would look possible and then not be.
 */
class SafBackupSetWriter(
    private val context: Context,
    private val resolver: ContentResolver,
    private val tree: DocumentFile,          // the screen passes DocumentFile.fromTreeUri(...)
) : BackupSetSink {

    /**
     * Creates the document, streams [body] into it, and — the invariant the owner asked for —
     * deletes that document again if [body] throws, then rethrows. A failed export must leave
     * no file from that attempted set, and the caller only knows the handles of writes that
     * returned.
     */
    override suspend fun write(name: String, body: suspend (OutputStream) -> Unit): String =
        withContext(Dispatchers.IO) {
            val document = tree.createFile("application/zip", name)
                ?: error(localized(R.string.backup_cannot_create, name))
            try {
                resolver.openOutputStream(document.uri, "wt")?.use { body(it) }
                    ?: error(localized(R.string.backup_cannot_open_for_writing, name))
            } catch (t: Throwable) {
                runCatching { document.delete() }
                throw t
            }
            document.uri.toString()
        }

    /**
     * True only when the document is really gone. A provider that refuses — or that throws, or
     * that cannot even be resolved back to a document — answers false, because the export's
     * cleanup has to be able to tell the owner which file is still sitting in their folder.
     */
    override suspend fun delete(handle: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                DocumentFile.fromSingleUri(context.applicationContext, handle.toUri())?.delete()
            }.getOrNull() == true
        }
}

/** `ServiceTag-data-<stamp>.zip` and `ServiceTag-artifacts-<stamp>.zip`, stamp = local `yyyyMMdd-HHmmss`. */
object BackupSetNames {
    fun stamp(at: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))

    fun data(stamp: String): String = "ServiceTag-data-$stamp.zip"

    fun artifacts(stamp: String): String = "ServiceTag-artifacts-$stamp.zip"
}
