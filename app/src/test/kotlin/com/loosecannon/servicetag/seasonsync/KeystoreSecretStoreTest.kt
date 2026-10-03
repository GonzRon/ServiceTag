package com.loosecannon.servicetag.seasonsync

import com.loosecannon.servicetag.core.seasonsync.Secret
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16 B4 (C18, R16-5; rows 46–48) — the token store's whole logic on the JVM: the real [KeystoreSecretStore] over the
 * JDK's AES-GCM ([JdkAead]) and a temporary directory standing for the no-backup one. A new [JdkAead] over an old
 * directory stands for a platform restore: the key never comes back. The Keystore's own facts are B9's (row 71).
 * Every token here is fictional.
 */
class KeystoreSecretStoreTest {

    private val roots = mutableListOf<File>()

    @After fun cleanUp() = roots.forEach { it.deleteRecursively() }

    /** One installation: its no-backup directory and its keys. */
    private inner class Phone(val root: File = freshRoot(), val aead: JdkAead = JdkAead()) {
        val store = KeystoreSecretStore(root, aead)
        val dir = File(root, "ha-secrets")

        fun file(id: String) = File(dir, "$id.bin")
    }

    private fun freshRoot(): File = Files.createTempDirectory("no-backup").toFile().also { roots += it }

    private val token = Secret("fictional-token-1")
    private val otherToken = Secret("fictional-token-2")

    // Row 46 — the store's contract.

    @Test fun putGetRoundTrip() = runBlocking {
        val phone = Phone()

        phone.store.put("conn-1", token)
        phone.store.put("conn-2", otherToken)

        assertEquals(token, phone.store.get("conn-1"))
        assertEquals(otherToken, phone.store.get("conn-2"))
        assertTrue(phone.store.has("conn-1"))
        assertEquals(setOf("conn-1", "conn-2"), phone.store.keys())

        phone.store.put("conn-1", otherToken)

        assertEquals(otherToken, phone.store.get("conn-1"))
        assertEquals(setOf("servicetag.ha.conn-1", "servicetag.ha.conn-2"), phone.aead.aliases())
    }

    @Test fun aMissingFileOrKeyIsNull() = runBlocking {
        val phone = Phone()
        assertNull(phone.store.get("conn-1"))
        assertFalse(phone.store.has("conn-1"))

        phone.store.put("conn-1", token)
        assertTrue(phone.file("conn-1").delete())
        assertNull(phone.store.get("conn-1"))
        assertFalse(phone.store.has("conn-1"))

        phone.store.put("conn-2", token)
        phone.aead.deleteKey("servicetag.ha.conn-2")
        assertTrue(phone.file("conn-2").isFile)
        assertNull(phone.store.get("conn-2"))
        assertFalse(phone.store.has("conn-2"))
    }

    @Test fun aTamperedFileIsNullNotAThrow() = runBlocking {
        val phone = Phone()
        phone.store.put("conn-1", token)
        val good = phone.file("conn-1").readBytes()

        val changed = listOf(
            good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, // the tag
            good.copyOf().also { it[12] = (it[12] + 1).toByte() }, // the ciphertext
            good.copyOf().also { it[0] = (it[0] + 1).toByte() }, // the IV
            good.copyOf(27), // one byte short of an IV and a tag
            good.copyOf(5),
            ByteArray(0),
        )
        for (bytes in changed) {
            phone.file("conn-1").writeBytes(bytes)
            assertNull(phone.store.get("conn-1"))
            // The file and the key are both there: `has` does not decrypt (C18).
            assertTrue(phone.store.has("conn-1"))
        }

        phone.store.put("conn-1", token)
        assertEquals(token, phone.store.get("conn-1"))
    }

    @Test fun theFileHoldsNoPlaintextAndAFreshIvEachPut() = runBlocking {
        val phone = Phone()
        val plaintext = token.value.encodeToByteArray()

        phone.store.put("conn-1", token)
        val first = phone.file("conn-1").readBytes()
        phone.store.put("conn-1", token)
        val second = phone.file("conn-1").readBytes()

        for (sealed in listOf(first, second)) {
            assertFalse(contains(sealed, plaintext))
            assertEquals(12 + plaintext.size + 16, sealed.size)
        }
        assertFalse(first.copyOf(12).contentEquals(second.copyOf(12)))
        assertFalse(first.contentEquals(second))
        // Written then renamed: one file, no temporary left; one key per connection, reused.
        assertEquals(listOf("conn-1.bin"), phone.dir.list()!!.toList())
        assertEquals(setOf("servicetag.ha.conn-1"), phone.aead.aliases())
        assertArrayEquals(plaintext, phone.aead.open("servicetag.ha.conn-1", second))
    }

    @Test fun deleteRemovesFileAndKey() = runBlocking {
        val phone = Phone()
        phone.store.put("conn-1", token)
        phone.store.put("conn-2", otherToken)

        phone.store.delete("conn-1")

        assertFalse(phone.file("conn-1").exists())
        assertEquals(setOf("servicetag.ha.conn-2"), phone.aead.aliases())
        assertNull(phone.store.get("conn-1"))
        assertFalse(phone.store.has("conn-1"))
        assertEquals(otherToken, phone.store.get("conn-2"))
        assertEquals(setOf("conn-2"), phone.store.keys())

        phone.store.delete("conn-1") // nothing left to delete is not a failure
    }

    @Test fun noExceptionOrToStringCarriesTheValue() = runBlocking {
        val phone = Phone()
        phone.store.put("conn-1", token)
        phone.file("conn-1").writeBytes(ByteArray(40))
        assertNull(phone.store.get("conn-1"))

        val failures = mutableListOf<Throwable>()
        // A no-backup directory that is a plain file: the put cannot write.
        val blocked = File(freshRoot(), "blocked").also { it.writeText("") }
        failures += failureOf { KeystoreSecretStore(blocked, JdkAead()).put("conn-1", token) }
        failures += failureOf { phone.store.put("../conn-1", token) }
        failures += failureOf { JdkAead().open("servicetag.ha.conn-1", ByteArray(40)) }
        failures += failureOf { phone.aead.open("servicetag.ha.conn-1", phone.file("conn-1").readBytes()) }

        val rendered = failures.map { it.stackTraceToString() } +
            listOf(phone.store.toString(), phone.aead.toString(), token.toString(), "$token")
        for (text in rendered) {
            assertFalse(text, text.contains("fictional-token"))
        }
    }

    // Row 47 — the orphan sweep.

    @Test fun filesAndAliasesNamingNoConnectionAreSwept() = runBlocking {
        val phone = Phone()
        listOf("conn-1", "conn-2", "conn-3").forEach { phone.store.put(it, token) }
        phone.aead.deleteKey("servicetag.ha.conn-3") // a file without its key
        phone.aead.seal("servicetag.ha.conn-9", byteArrayOf(1)) // a key without its file
        phone.aead.seal("another.alias", byteArrayOf(1)) // not this store's
        File(phone.dir, "conn-2.bin1234.tmp").writeBytes(byteArrayOf(1)) // an interrupted put

        phone.store.sweepOrphans { setOf("conn-2") }

        assertEquals(listOf("conn-2.bin"), phone.dir.list()!!.toList())
        assertEquals(setOf("servicetag.ha.conn-2", "another.alias"), phone.aead.aliases())
        assertEquals(setOf("conn-2"), phone.store.keys())
        assertEquals(token, phone.store.get("conn-2"))
    }

    @Test fun aKnownOneIsKept() = runBlocking {
        val phone = Phone()
        phone.store.sweepOrphans { setOf("conn-1") } // nothing stored yet: no directory, no failure
        phone.store.put("conn-1", token)

        phone.store.sweepOrphans { setOf("conn-1") }

        assertEquals(token, phone.store.get("conn-1"))
        assertTrue(phone.store.has("conn-1"))
    }

    @Test fun aTokenPutWhileTheSweepReadsItsIdsWaitsAndIsKept() = runBlocking {
        val phone = Phone()
        val reading = CompletableDeferred<Unit>()
        val staleIds = CompletableDeferred<Set<String>>()
        val sweep = launch { phone.store.sweepOrphans { reading.complete(Unit); staleIds.await() } }
        reading.await()

        // A Save commits its row and puts its token while the sweep still holds ids read before that row.
        val save = launch { phone.store.put("conn-1", token) }
        yield()
        assertFalse(save.isCompleted)
        staleIds.complete(emptySet())
        sweep.join()
        save.join()

        assertEquals(token, phone.store.get("conn-1"))
    }

    // Row 48 — a platform restore (H5).

    @Test fun aRowRestoredWithoutItsKeyReadsNullAndHasFalse() = runBlocking {
        val before = Phone()
        before.store.put("conn-1", token)

        // What Android restores: the Room rows, neither the key nor the no-backup file.
        val restored = Phone()
        assertNull(restored.store.get("conn-1"))
        assertFalse(restored.store.has("conn-1"))

        // Worse: the file arrives some other way, and still no key.
        val copied = Phone()
        copied.dir.mkdirs()
        before.file("conn-1").copyTo(copied.file("conn-1"))
        assertNull(copied.store.get("conn-1"))
        assertFalse(copied.store.has("conn-1"))

        // Entering the token again is the one step back.
        copied.store.put("conn-1", otherToken)
        assertEquals(otherToken, copied.store.get("conn-1"))
        assertTrue(copied.store.has("conn-1"))
    }

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        val caught = runCatching { block() }.exceptionOrNull()
        assertNotNull("expected a failure", caught)
        return caught!!
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }
}
