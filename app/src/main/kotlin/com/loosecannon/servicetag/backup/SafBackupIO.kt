package com.loosecannon.servicetag.backup

import android.content.ContentResolver
import android.net.Uri
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.core.ports.BackupIO
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Storage Access Framework side of [BackupIO]: one document the user picked, read on
 * [Dispatchers.IO]. A data archive is small enough that holding it in one [ByteArray] is the
 * simplest thing that is also atomic.
 *
 * [openStream] is the exception, and the reason the port has it: an artifacts archive is read
 * entry by entry and must never be held whole.
 */
class SafBackupIO(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : BackupIO {

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error(localized(R.string.backup_cannot_open_for_reading, uri))
    }

    override suspend fun openStream(): InputStream = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri) ?: error(localized(R.string.backup_cannot_open_for_reading, uri))
    }
}
