package com.loosecannon.servicetag.ui.transfer.`import`

import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.IdGenerator
import java.io.File

/**
 * #77 (C14 (0), R77-2) — where a picked or shared Transfer Pack is copied **before** it is read: `cache/transfer-in/`,
 * which no provider exposes. A share's grant lives only as long as the sharing task, so the intake copies while it
 * lives; the in-app door copies the picked document the same way. The import screen reads the copy, never a `Uri`
 * and never a path from an intent extra, and every end deletes it.
 */
interface TransferPackInbox {
    /** Copies [source] whole into a new file here. A failed copy leaves no file behind. */
    suspend fun copyIn(source: ByteSource): File

    /** The copy named [name] — a bare file name — or null when it is gone (or the name is not one of ours). */
    fun find(name: String): File?

    /** Best effort; an absent file is not an error. */
    fun delete(file: File)
}

/** The app's inbox: one file per copy, under [dir], named by [ids]. */
class CacheTransferPackInbox(private val dir: File, private val ids: IdGenerator) : TransferPackInbox {
    override suspend fun copyIn(source: ByteSource): File {
        dir.mkdirs()
        val file = File(dir, "${ids.newId()}.zip")
        try {
            source.open().use { input -> file.outputStream().use { out -> input.copyTo(out) } }
        } catch (t: Throwable) {
            file.delete()
            throw t
        }
        return file
    }

    override fun find(name: String): File? {
        if (name.isEmpty() || name != File(name).name || name.startsWith(".")) return null
        return File(dir, name).takeIf { it.isFile }
    }

    override fun delete(file: File) {
        file.delete()
    }
}
