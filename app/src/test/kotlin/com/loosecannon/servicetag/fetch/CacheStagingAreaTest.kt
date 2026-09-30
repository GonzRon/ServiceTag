package com.loosecannon.servicetag.fetch

import com.loosecannon.servicetag.core.fetch.FileStagedReader
import com.loosecannon.servicetag.core.ports.IdGenerator
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Row 27 (#85 C18, C26; R85-8): the app's staging area, over a temporary directory standing in for the cache. */
class CacheStagingAreaTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir by lazy { File(tmp.root, "materialize") }
    private var seq = 0
    private val area by lazy { CacheStagingArea(dir, IdGenerator { "download-${++seq}" }) }
    private val bytes = "%PDF-1.7 Sample Water Heater manual %%EOF".toByteArray()

    @Test
    fun createWriteReadBackAndDiscardTwice() {
        val staged = area.create()
        val part = File(dir, "download-1.part")
        assertTrue("create makes the part file", part.isFile)

        staged.output().use { it.write(bytes) }

        assertArrayEquals(bytes, part.readBytes())
        assertArrayEquals(bytes, staged.source().open().use { it.readBytes() })
        staged.discard()
        assertFalse(part.exists())
        staged.discard() // idempotent, and it never throws
    }

    @Test
    fun readerReadsThePartFile() {
        val staged = area.create()
        staged.output().use { it.write(bytes) }

        val reader = staged.reader()

        assertTrue("the core's read-only reader, over the part file", reader is FileStagedReader)
        assertArrayEquals("Sample".toByteArray(), reader.readAt(9, 6))
        assertArrayEquals("%%EOF".toByteArray(), reader.readAt(bytes.size - 5L, 64))
        assertEquals(0, reader.readAt(bytes.size.toLong(), 8).size)
    }

    @Test
    fun sweepAtStartKeepsThisProcesssFiles() {
        dir.mkdirs()
        val startedAt = 1_790_000_000_000L
        fun part(name: String, modified: Long) =
            File(dir, name).apply { writeBytes(bytes); check(setLastModified(modified)) }
        val leftOver = part("left-over.part", startedAt - 60_000)
        val atStart = part("at-start.part", startedAt)
        val ours = part("ours.part", startedAt + 5_000)

        area.sweepAtStart(startedAt)

        assertFalse("a previous process's download goes", leftOver.exists())
        assertTrue("not older than the start", atStart.exists())
        assertTrue("a download this process began stays", ours.exists())
    }

    @Test
    fun sweepingBeforeAnyDownloadIsNothing() {
        area.sweepAtStart(System.currentTimeMillis())

        assertFalse(dir.exists())
    }
}
