package com.loosecannon.servicetag.core.transfer

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/** What sealing a pack produced: its manifest, and the whole file's sha256 and size as written. */
data class TransferPackWritten(val manifest: TransferPackManifest, val packSha256: String, val packBytes: Long)

/**
 * #77 (C4) — seals a pack around the two unchanged inner archives. `data.zip` is in memory; `artifacts.zip`
 * can be hundreds of megabytes, so it is opened twice and never held: pass one measures its size, CRC-32
 * and sha256 (a STORED entry declares size and CRC before its first byte, and the manifest, written first,
 * carries the sha); pass two copies it. A source that differs between the passes fails the write
 * ([java.util.zip.ZipException] from the JDK, or [IllegalStateException] on a length change); the caller
 * deletes the file (C5: any failure leaves no file).
 */
object TransferPackCodec {
    private const val BUFFER = 64 * 1024

    internal val json = Json { prettyPrint = true; encodeDefaults = true }

    internal fun encodeManifest(manifest: TransferPackManifest): ByteArray =
        json.encodeToString(TransferPackManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

    /**
     * Writes the pack for [draft] to [sink], which it closes. [artifacts] opens the finished artifacts
     * archive (a work file in the app) and is called exactly twice.
     */
    fun write(sink: OutputStream, draft: TransferPackDraft, artifacts: () -> InputStream): TransferPackWritten {
        val measured = artifacts().use { measure(it) }
        val manifest = draft.manifest(artifactsSha256 = measured.sha256)
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = CountingDigestStream(sink, digest)
        ZipOutputStream(counted).use { zos ->
            zos.putNextEntry(ZipEntry(TransferPack.MANIFEST_ENTRY).also { it.time = draft.createdAt })
            zos.write(encodeManifest(manifest))
            zos.closeEntry()

            val dataCrc = CRC32().also { it.update(draft.data) }
            zos.putNextEntry(stored(TransferPack.DATA_ENTRY, draft.data.size.toLong(), dataCrc.value, draft.createdAt))
            zos.write(draft.data)
            zos.closeEntry()

            zos.putNextEntry(stored(TransferPack.ARTIFACTS_ENTRY, measured.size, measured.crc, draft.createdAt))
            val copied = artifacts().use { it.copyTo(zos, BUFFER) }
            check(copied == measured.size) { "artifacts changed while the pack was written: $copied of ${measured.size} bytes" }
            zos.closeEntry()
        }
        return TransferPackWritten(manifest, TransferPack.hex(digest.digest()), counted.count)
    }

    private fun stored(name: String, size: Long, crc: Long, time: Long) = ZipEntry(name).also {
        it.method = ZipEntry.STORED
        it.size = size
        it.compressedSize = size
        it.crc = crc
        it.time = time
    }

    private class Measured(val size: Long, val crc: Long, val sha256: String)

    private fun measure(input: InputStream): Measured {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            crc.update(buffer, 0, read)
            size += read
        }
        return Measured(size, crc.value, TransferPack.hex(digest.digest()))
    }

    /** Hashes and counts every byte on its way to the sink, so the caller learns the file's sha without re-reading it. */
    private class CountingDigestStream(out: OutputStream, private val digest: MessageDigest) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            digest.update(b.toByte())
            count += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            digest.update(b, off, len)
            count += len
        }
    }
}
