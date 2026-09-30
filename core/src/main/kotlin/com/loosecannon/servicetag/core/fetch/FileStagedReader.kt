package com.loosecannon.servicetag.core.fetch

import java.io.File
import java.io.RandomAccessFile

/**
 * C26: a [StagedReader] over a staged file; B4's staging answers `reader()` with one over its part file.
 * Each read opens the file read-only and closes it before returning, so nothing stays open between reads
 * and nothing can change the file.
 */
class FileStagedReader(private val file: File) : StagedReader {
    override fun readAt(position: Long, length: Int): ByteArray {
        require(position >= 0 && length >= 0) { "a negative position or length" }
        RandomAccessFile(file, "r").use { f ->
            val bytes = ByteArray((f.length() - position).coerceIn(0L, length.toLong()).toInt())
            if (bytes.isEmpty()) return bytes // at or past the end: no seek
            f.seek(position)
            f.readFully(bytes)
            return bytes
        }
    }
}
