package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.fetch.StagingArea
import com.loosecannon.servicetag.core.fetch.StagingFile
import com.loosecannon.servicetag.core.ports.ByteSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A [StagingArea] in memory. [files] lists every staging file made, in order. [failCreate] makes
 * `create()` throw; [failWriteAfter] makes a file's output throw once that many bytes are written, as a
 * full cache does.
 */
class FakeStaging(
    private val failCreate: Boolean = false,
    private val failWriteAfter: Int? = null,
) : StagingArea {
    val files = mutableListOf<FakeStagingFile>()

    override fun create(): StagingFile {
        if (failCreate) throw IOException("no room")
        return FakeStagingFile(failWriteAfter).also { files += it }
    }
}

/** One staged file. [sourceOpens] proves the fetch never reads it back; [discards] that it was dropped. */
class FakeStagingFile(private val failWriteAfter: Int?) : StagingFile {
    private val content = ByteArrayOutputStream()
    @Volatile var outputs = 0
    @Volatile var outputClosed = false
    @Volatile var sourceOpens = 0
    @Volatile var discards = 0
    val discarded get() = discards > 0
    val bytes: ByteArray get() = content.toByteArray()

    override fun output(): OutputStream {
        outputs++
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWriteAfter != null && content.size() + len > failWriteAfter) throw IOException("full")
                content.write(b, off, len)
            }

            override fun close() {
                outputClosed = true
            }
        }
    }

    override fun source(): ByteSource = ByteSource {
        sourceOpens++
        ByteArrayInputStream(content.toByteArray())
    }

    override fun discard() {
        discards++
        content.reset()
    }
}
