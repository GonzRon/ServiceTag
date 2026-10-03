package com.loosecannon.servicetag.seasonsync

import android.content.Context
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.GeneralSecurityException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * #16 (C18, R16-5; D9 as written) — the Home Assistant access token, keyed by the connection id. It is sealed under
 * one AES-256-GCM key per connection ([KeyedAead]; on a device the Android Keystore's, alias [ALIAS_PREFIX] and the
 * id), and the sealed bytes are one file, [DIRECTORY]`/<id>.bin` under the app's no-backup directory, written to a
 * temporary file and then renamed. Neither Auto Backup nor a device-to-device transfer copies that directory (the
 * `InstallationIdentity` precedent), so no backup rule names it (R16-5's stated deviation, N-1).
 *
 * [SecretStore]'s contract: [get] answers null, never a throw, when the file or the key is missing, or the file is
 * short or fails its tag. So after a platform restore, which brings the Room rows back without the key or the file
 * (H5), every binding reads NEEDS_TOKEN until a token is entered again. [has] is the file and the key both present,
 * without decrypting. [delete] removes the key first, so a file left behind can no longer be opened, and then the
 * file. [sweepOrphans] removes what names no known connection.
 *
 * Every call runs under one lock, off the caller's thread. [has], [put], [delete], [keys] and [sweepOrphans] pass a
 * Keystore failure through to the caller, loud by design (a missing key or file is never a throw), and a malformed id
 * is an `IllegalArgumentException` on every call. The token is never logged, never in an exception message and never
 * in [toString]: nothing here logs at all.
 */
class KeystoreSecretStore(
    noBackupDir: File,
    private val aead: KeyedAead,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SecretStore {
    private val dir = File(noBackupDir, DIRECTORY)
    private val lock = Mutex()

    override suspend fun put(key: String, secret: Secret) = locked {
        val plaintext = secret.value.encodeToByteArray()
        val sealed = try {
            aead.seal(aliasFor(key), plaintext)
        } finally {
            plaintext.fill(0)
        }
        writeThenRename(fileFor(key), sealed)
    }

    override suspend fun get(key: String): Secret? = locked { read(key) }

    override suspend fun has(key: String): Boolean = locked { fileFor(key).isFile && aead.hasKey(aliasFor(key)) }

    override suspend fun delete(key: String) = locked {
        aead.deleteKey(aliasFor(key))
        val file = fileFor(key)
        if (!file.delete() && file.exists()) throw IOException("the Home Assistant token file could not be deleted")
    }

    /** Every connection id with a file or a key here: what [sweepOrphans] would look at. */
    override suspend fun keys(): Set<String> = locked { fileIds() + aliasIds() }

    /**
     * Removes every file in the directory but a known connection's `<id>.bin` — an orphan's, or the temporary file an
     * interrupted [put] left — and every [ALIAS_PREFIX] key that names no known connection; another alias is left
     * alone. Run at start, off the main thread, guarded by the caller (C18): a failure is repeated at the next start.
     *
     * [knownIds] is read under the store's lock, so a token put after its row commits (C17) waits for the sweep and is
     * never swept with a stale set. It must not call this store: the lock is not reentrant.
     */
    suspend fun sweepOrphans(knownIds: suspend () -> Set<String>) = lock.withLock {
        val known = knownIds()
        withContext(io) {
            val kept = known.mapTo(HashSet()) { it + SUFFIX }
            dir.listFiles().orEmpty().filter { it.name !in kept }.forEach { it.delete() }
            aead.aliases()
                .filter { it.startsWith(ALIAS_PREFIX) && it.removePrefix(ALIAS_PREFIX) !in known }
                .forEach(aead::deleteKey)
        }
    }

    private fun read(key: String): Secret? {
        val sealed = readOrNull(fileFor(key)) ?: return null
        val plaintext = try {
            aead.open(aliasFor(key), sealed)
        } catch (_: MissingKeyException) {
            return null // the row came back without its key (H5)
        } catch (_: GeneralSecurityException) {
            return null // a short or changed file: its tag fails
        }
        return try {
            Secret(plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
    }

    private fun readOrNull(file: File): ByteArray? =
        try {
            if (file.isFile) file.readBytes() else null
        } catch (_: IOException) {
            null
        }

    private fun writeThenRename(target: File, bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("the Home Assistant token directory could not be made")
        val temp = File.createTempFile(target.name, TEMP_SUFFIX, dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private fun fileIds(): Set<String> =
        dir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(SUFFIX) }.map { it.removeSuffix(SUFFIX) }
            .filterTo(HashSet()) { CONNECTION_ID.matches(it) }

    private fun aliasIds(): Set<String> =
        aead.aliases().filter { it.startsWith(ALIAS_PREFIX) }.map { it.removePrefix(ALIAS_PREFIX) }
            .filterTo(HashSet()) { CONNECTION_ID.matches(it) }

    private fun fileFor(key: String): File = File(dir, checked(key) + SUFFIX)

    private fun aliasFor(key: String): String = ALIAS_PREFIX + checked(key)

    private fun checked(key: String): String {
        require(CONNECTION_ID.matches(key)) { "not a connection id" }
        return key
    }

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    companion object {
        /** The directory under the app's no-backup directory. */
        const val DIRECTORY: String = "ha-secrets"

        /** Every key's alias is this prefix and the connection id. */
        const val ALIAS_PREFIX: String = "servicetag.ha."

        private const val SUFFIX = ".bin"
        private const val TEMP_SUFFIX = ".tmp"

        /** A connection id is a file name and an alias suffix as it stands: no separator, no dot, nothing to escape. */
        private val CONNECTION_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")

        /** The device's store: the Android Keystore, and the directory the platform never backs up or transfers. */
        fun onDevice(context: Context): KeystoreSecretStore =
            KeystoreSecretStore(context.applicationContext.noBackupFilesDir, AndroidKeystoreAead())
    }
}
