package com.loosecannon.servicetag.core.fetch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Row 34 (C26): the file reader B4's staging answers with. It reads, and nothing else. */
class FileStagedReaderTest {

    @Test
    fun readsATempFileReadOnly() {
        val bytes = ByteArray(3_000) { (it * 7).toByte() }
        val file = File.createTempFile("staged", ".part")
        try {
            file.writeBytes(bytes)
            val mtime = 1_700_000_000_000L
            assertTrue(file.setLastModified(mtime))
            val reader = FileStagedReader(file)
            assertContentEquals(bytes.copyOfRange(0, 1_024), reader.readAt(0, 1_024))
            assertContentEquals(bytes.copyOfRange(1_500, 1_510), reader.readAt(1_500, 10))
            assertContentEquals(bytes.copyOfRange(2_990, 3_000), reader.readAt(2_990, 64), "short at the end")
            assertContentEquals(ByteArray(0), reader.readAt(3_000, 8), "empty at the end")
            assertContentEquals(ByteArray(0), reader.readAt(9_000, 8), "empty past the end")
            assertContentEquals(bytes, file.readBytes(), "the bytes are unchanged")
            assertEquals(mtime, file.lastModified(), "the file was never written")
        } finally {
            file.delete()
        }
    }
}
