package com.loosecannon.servicetag.fetch

import com.loosecannon.servicetag.core.fetch.FileStagedReader
import com.loosecannon.servicetag.core.fetch.StagedReader
import com.loosecannon.servicetag.core.fetch.StagingArea
import com.loosecannon.servicetag.core.fetch.StagingFile
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.IdGenerator
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * #85 C18 (R85-8): app-private staging for Save as document, one `<id>.part` file per download in [dir]
 * (`cache/materialize/`), outside the attachments folder, so a half-downloaded file is never where a synced folder
 * or the attachment store could see it. The core discards a staging file on every path that does not hand it to
 * the attachment store; this area only deletes what it is asked to, plus [sweepAtStart]'s leftovers.
 */
class CacheStagingArea(private val dir: File, private val ids: IdGenerator) : StagingArea {

    override fun create(): StagingFile {
        dir.mkdirs()
        val part = File(dir, "${ids.newId()}.part")
        if (!part.createNewFile()) throw IOException("no staging file")
        return PartFile(part)
    }

    /**
     * At app start: every file made **before** this process started ([startedAt]) — a download a previous process
     * left when it died. A file made since belongs to a download this process is running, so it stays (the
     * `TransferPackWriter.sweepAtStart` shape).
     */
    fun sweepAtStart(startedAt: Long) {
        dir.listFiles()?.filter { it.isFile && it.lastModified() < startedAt }?.forEach { it.delete() }
    }

    /** One download. [reader] is the core's read-only reader over this file (C26); [discard] never throws. */
    private class PartFile(private val file: File) : StagingFile {
        override fun output(): OutputStream = FileOutputStream(file)

        override fun source(): ByteSource = ByteSource { FileInputStream(file) }

        override fun reader(): StagedReader = FileStagedReader(file)

        override fun discard() {
            try {
                file.delete()
            } catch (_: SecurityException) {
                // Nothing to report: the start-up sweep takes it next time.
            }
        }
    }

    companion object {
        /** The directory under the app's cache. */
        const val DIRECTORY = "materialize"
    }
}
