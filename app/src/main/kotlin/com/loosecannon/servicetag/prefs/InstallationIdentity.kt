package com.loosecannon.servicetag.prefs

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * #92 (C5a, R92-8) — this installation's opaque id: `GET /v1/status`' `installationId`, and the installation half of
 * the upload id namespace (C13), so two phones that share asset ids never derive one attachment id.
 *
 * **The lifecycle:** the id is **created once per installation, then merely loaded and cached once per process**. A
 * random 128-bit id is atomically created, if absent, in one device-local file, [FILE_NAME] in the directory this is
 * given — the app's no-backup directory, which neither Auto Backup nor a device-to-device transfer copies. It is then
 * stable across ordinary restarts and upgrades; a new identity exists only after an app-data reset or a reinstall. A
 * well-formed file's content **is** the id: only an absent file mints one, and a malformed one is replaced once, as if
 * the data had been cleared, and then read like any other.
 *
 * It is 16 random bytes from a CSPRNG, written in the 8-4-4-4-12 lowercase-hex form with **no** UUID version or
 * variant bits forced. It is non-secret and non-canonical: not a credential (the pairing code stays the only one), not
 * derived from any Android or hardware identifier, never logged, and never in a backup, an export, a merge or a
 * Transfer Pack — nothing on those paths names this class.
 *
 * [id] resolves under one process-wide lock and caches the answer, so concurrent first reads yield one id and one
 * file. A new id goes to a temporary file in the same directory and is renamed onto [FILE_NAME] only while no file is
 * there; whichever file won is read back. No I/O happens before the first [id].
 */
class InstallationIdentity(
    private val dir: File,
    private val random: java.security.SecureRandom = java.security.SecureRandom(),
) {
    @Volatile private var cached: String? = null

    /** The id: read from the file once per process, minted only when the file is absent or malformed. */
    fun id(): String = cached ?: synchronized(LOCK) { cached ?: resolve().also { cached = it } }

    private fun resolve(): String {
        val file = File(dir, FILE_NAME)
        readWellFormed(file)?.let { return it }
        // Absent, or not exactly one id: replaced once, as a data clear would, and then read like any other.
        if (file.exists() && !file.delete()) throw IOException("the installation id could not be replaced")
        createIfAbsent(file, mint())
        return readWellFormed(file) ?: throw IOException("the installation id could not be read back")
    }

    private fun createIfAbsent(file: File, id: String) {
        dir.mkdirs()
        val temp = File.createTempFile(FILE_NAME, ".tmp", dir)
        try {
            temp.writeText(id)
            // No REPLACE_EXISTING: a file that is already there wins and is read back, never overwritten.
            Files.move(temp.toPath(), file.toPath())
        } catch (e: FileAlreadyExistsException) {
            // Another first read created it; its content is the id.
        } finally {
            temp.delete()
        }
    }

    private fun readWellFormed(file: File): String? =
        if (file.isFile) file.readText().takeIf(WELL_FORMED::matches) else null

    private fun mint(): String {
        val bytes = ByteArray(ID_BYTES).also { random.nextBytes(it) }
        val hex = buildString { bytes.forEach { b -> append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF]) } }
        return listOf(hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16), hex.substring(16, 20), hex.substring(20))
            .joinToString("-")
    }

    companion object {
        /** The one file, under the no-backup directory. */
        const val FILE_NAME: String = "installation-id"

        private const val ID_BYTES = 16
        private const val HEX = "0123456789abcdef"
        private val WELL_FORMED = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Process-wide, so every instance over one directory resolves one at a time. */
        private val LOCK = Any()
    }
}
