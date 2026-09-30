package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.fetch.StagedReader
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
 * full cache does; [failReads] makes every read of a file's [StagingFile.reader] throw, as a vanished file does.
 */
class FakeStaging(
    private val failCreate: Boolean = false,
    private val failWriteAfter: Int? = null,
    private val failReads: Boolean = false,
) : StagingArea {
    val files = mutableListOf<FakeStagingFile>()

    override fun create(): StagingFile {
        if (failCreate) throw IOException("no room")
        return FakeStagingFile(failWriteAfter, failReads).also { files += it }
    }
}

/**
 * One staged file. [sourceOpens] proves the fetch never reads it back; [discards] that it was dropped;
 * [readerOpens] and [reads] count the container inspection's reader and its reads (C26).
 */
class FakeStagingFile(private val failWriteAfter: Int?, private val failReads: Boolean = false) : StagingFile {
    private val content = ByteArrayOutputStream()
    @Volatile var outputs = 0
    @Volatile var outputClosed = false
    @Volatile var sourceOpens = 0
    @Volatile var discards = 0
    private val readers = mutableListOf<BytesStagedReader>()
    val readerOpens get() = readers.size
    val reads get() = readers.sumOf { it.reads }
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

    override fun reader(): StagedReader =
        BytesStagedReader({ content.toByteArray() }, failReads).also { readers += it }

    override fun discard() {
        discards++
        content.reset()
    }
}

/** A [StagedReader] over bytes in memory (C26). [reads] counts every read; with [failing], each read throws. */
class BytesStagedReader(private val bytes: () -> ByteArray, private val failing: Boolean = false) : StagedReader {
    @Volatile var reads = 0

    override fun readAt(position: Long, length: Int): ByteArray {
        reads++
        if (failing) throw IOException("gone")
        require(position >= 0 && length >= 0)
        val b = bytes()
        if (position >= b.size) return ByteArray(0)
        return b.copyOfRange(position.toInt(), minOf(b.size.toLong(), position + length).toInt())
    }

    companion object {
        fun of(bytes: ByteArray) = BytesStagedReader({ bytes })
    }
}
