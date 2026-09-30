package com.loosecannon.servicetag.prefs

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.usecase.CreateTransferPackResult
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.transfer.TransferPackWriter
import com.loosecannon.servicetag.ui.transfer.`import`.TransferPackAppFixtures
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #92 B1a (C5a, R92-8; row 46) — the installation id: created once per installation, then loaded and cached once
 * per process. Two instances over one directory stand for two process starts; a fresh directory for a reinstall.
 */
class InstallationIdentityTest {

    private val dirs = mutableListOf<File>()

    private fun freshDir(): File = Files.createTempDirectory("installation-test").toFile().also { dirs += it }

    @After fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun fileIn(dir: File) = File(dir, InstallationIdentity.FILE_NAME)

    @Test fun oneIdStableAcrossProcessRestarts() {
        val dir = freshDir()
        val first = InstallationIdentity(dir).id()

        val restarted = InstallationIdentity(dir)

        assertEquals(first, restarted.id())
        assertEquals(first, restarted.id())
        assertEquals(first, fileIn(dir).readText())
    }

    @Test fun aFreshDirectoryMintsADifferentId() {
        assertNotEquals(InstallationIdentity(freshDir()).id(), InstallationIdentity(freshDir()).id())
    }

    @Test fun concurrentFirstReadsYieldOneId() {
        val dir = freshDir()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val answers = (1..threads).map {
                pool.submit<String> {
                    start.await()
                    InstallationIdentity(dir).id()
                }
            }
            start.countDown()
            val ids = answers.map { it.get(10, TimeUnit.SECONDS) }.toSet()

            assertEquals(1, ids.size)
            assertEquals(listOf(InstallationIdentity.FILE_NAME), dir.list()!!.toList())
            assertEquals(ids.single(), fileIn(dir).readText())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun aMalformedFileIsReplacedOnce() {
        for (malformed in listOf("0f1e2d3c-4b5a", "not an id", "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0", "")) {
            val dir = freshDir()
            fileIn(dir).writeText(malformed)

            val replaced = InstallationIdentity(dir).id()

            assertNotEquals(malformed, replaced)
            assertEquals(replaced, fileIn(dir).readText())
            assertEquals(replaced, InstallationIdentity(dir).id())
            assertEquals(listOf(InstallationIdentity.FILE_NAME), dir.list()!!.toList())
        }
    }

    @Test fun aWellFormedFileIsTheIdAndNothingIsMinted() {
        val dir = freshDir()
        fileIn(dir).writeText("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0")
        val random = CountingRandom(0x00)

        assertEquals("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", InstallationIdentity(dir, random).id())
        assertEquals(0, random.requested)
    }

    @Test fun theIdIs128CsprngBitsInUuidForm() {
        val shape = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        assertTrue(shape.matches(InstallationIdentity(freshDir()).id()))

        // Sixteen bytes, in order, and nothing forced: a version-4 id would read `…-4607-8809-…` here.
        val ascending = SequenceRandom()
        assertEquals("00010203-0405-0607-0809-0a0b0c0d0e0f", InstallationIdentity(freshDir(), ascending).id())
        assertEquals(16, ascending.requested)
        for ((fill, expected) in listOf(
            0x00 to "00000000-0000-0000-0000-000000000000",
            0xFF to "ffffffff-ffff-ffff-ffff-ffffffffffff",
        )) {
            val random = CountingRandom(fill)
            assertEquals(expected, InstallationIdentity(freshDir(), random).id())
            assertEquals(16, random.requested)
        }
    }

    @Test fun absentFromTheBackupCodecsOutput() = runBlocking {
        val graph = FakeGraph()
        try {
            val id = graph.installationIdentity.id()
            TransferPackAppFixtures.seedHeater(graph)

            val set = graph.exportBackupSet.run()

            assertFalse("the data archive names the installation id", containsAnywhere(set.data, id))
            assertFalse("the data archive names the installation id", containsAnywhere(set.data, "installationId"))
        } finally {
            graph.close()
        }
    }

    @Test fun absentFromATransferPack() = runBlocking {
        val graph = FakeGraph()
        val cache = freshDir()
        try {
            val id = graph.installationIdentity.id()
            TransferPackAppFixtures.seedHeater(graph)
            val created = graph.createTransferPack.run(listOf(AssetId(TransferPackAppFixtures.HEATER)), "")
            val draft = (created as CreateTransferPackResult.Created).draft

            val pack = TransferPackWriter(cache, graph.attachmentStorage).write(draft, "example-pack.zip").file.readBytes()

            assertTrue("the pack holds entries", entries(pack).isNotEmpty())
            assertFalse("a pack entry names the installation id", containsAnywhere(pack, id))
            assertFalse("a pack entry names the installation id", containsAnywhere(pack, "installationId"))
        } finally {
            graph.close()
        }
    }

    // --- plumbing ---------------------------------------------------------------------------

    /** A generator that fills every requested byte with [fill] and counts the bytes asked for. */
    private class CountingRandom(private val fill: Int) : SecureRandom() {
        var requested = 0
        override fun nextBytes(bytes: ByteArray) {
            requested += bytes.size
            bytes.fill(fill.toByte())
        }
    }

    /** A generator that answers 0, 1, 2, … and counts the bytes asked for. */
    private class SequenceRandom : SecureRandom() {
        var requested = 0
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (requested + i).toByte()
            requested += bytes.size
        }
    }

    /** Every entry of a zip, and of every zip inside it, decompressed. */
    private fun entries(zip: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = input.readBytes()
                out += bytes
                if (entry.name.endsWith(".zip")) out += entries(bytes)
            }
        }
        return out
    }

    private fun containsAnywhere(zip: ByteArray, text: String): Boolean =
        (entries(zip) + listOf(zip)).any { text in String(it, Charsets.ISO_8859_1) }
}
