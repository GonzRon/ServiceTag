package com.loosecannon.servicetag.core.fetch

import java.io.File

/** C26: a [StagedReader] over a staged file. */
class FileStagedReader(private val file: File) : StagedReader {
    override fun readAt(position: Long, length: Int): ByteArray = ByteArray(0)
}
