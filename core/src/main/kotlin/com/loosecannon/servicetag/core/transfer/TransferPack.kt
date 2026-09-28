package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.ArtifactsPlan
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * #77 (C4; R77-1) — a Transfer Pack is one ZIP holding exactly three entries, in this order:
 *
 * ```
 * transfer-manifest.json   the pack's own manifest (TransferPackManifest), at most 64 KiB
 * data.zip                 BackupCodec.encode output, unchanged, its backupSetId the packId (STORED)
 * artifacts.zip            ArtifactsCodec.write output, unchanged, the same set id (STORED, streamed)
 * ```
 *
 * The manifest comes first so a reader can refuse or preview before it unpacks anything. The inner
 * archives keep every check their own codecs make; the pack adds none of its own semantics to a row.
 */
object TransferPack {
    const val PACK_FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "transfer-manifest.json"
    const val DATA_ENTRY = "data.zip"
    const val ARTIFACTS_ENTRY = "artifacts.zip"

    /** The entries, in the only order a pack may hold them. */
    val ENTRIES: List<String> = listOf(MANIFEST_ENTRY, DATA_ENTRY, ARTIFACTS_ENTRY)

    /** The pack's own manifest, read whole before anything else. */
    const val MAX_MANIFEST_BYTES = 64L * 1024

    /** `data.zip` as stored in the pack. Creation refuses above it (P77-59) and a reader calls it damaged. */
    const val MAX_PACK_DATA_BYTES = 16L * 1024 * 1024

    /** Every entry of `data.zip` inflated, together, before the backup codec is handed the archive. */
    const val MAX_PACK_JSON_BYTES = 64L * 1024 * 1024

    /** R77-15: one optional note, one line, at most this many characters; "" by default. */
    const val MAX_NOTE_CHARS = 200

    /** The note rule, stated once: the creation and the reader ask the same question. */
    fun noteAccepted(note: String): Boolean =
        note.length <= MAX_NOTE_CHARS && note.none { it == '\n' || it == '\r' || it == ' ' || it == ' ' }

    internal fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    internal fun hex(digest: ByteArray): String = digest.joinToString("") { b -> "%02x".format(b) }
}

/**
 * The pack's manifest (C4). [lineage] keys every pack asset (sorted) to the pack ids it travelled in
 * before this one, oldest first. [counts] is the inner data manifest's `counts`, verbatim; [attachments]
 * and [attachmentBytes] are its artifact tallies — the MANAGED rows whose bytes `artifacts.zip` carries.
 * [dataSha256] and [artifactsSha256] hash the two inner files as stored; [contentSha256] is the inner
 * manifest's `dataSha256`, which is deterministic (sorted rows, no set id, no stamp).
 */
@Serializable
data class TransferPackManifest(
    val packFormatVersion: Int,
    val packId: String,
    val createdAt: Long,
    val appVersion: String,
    val schemaVersion: Int,
    val dataFormatVersion: Int,
    val artifactFormatVersion: Int,
    val rootAssetIds: List<String>,
    val assetIds: List<String>,
    val lineage: Map<String, List<String>>,
    val counts: Map<String, Int>,
    val attachments: Int,
    val attachmentBytes: Long,
    val dataSha256: String,
    val artifactsSha256: String,
    val contentSha256: String,
    val note: String = "",
)

/**
 * What creation hands over (C5): every manifest field but the artifacts' hash, the encoded data archive,
 * and the plan for the artifacts archive — the pack's own MANAGED rows and nothing else. Nothing has been
 * written anywhere; the app streams [plan] into `artifacts.zip` and then seals the pack with
 * [TransferPackCodec.write].
 */
class TransferPackDraft(
    val packId: String,
    val createdAt: Long,
    val appVersion: String,
    val schemaVersion: Int,
    val dataFormatVersion: Int,
    val artifactFormatVersion: Int,
    val rootAssetIds: List<String>,
    val assetIds: List<String>,
    val lineage: Map<String, List<String>>,
    val counts: Map<String, Int>,
    val attachments: Int,
    val attachmentBytes: Long,
    val contentSha256: String,
    val note: String,
    val data: ByteArray,
    val plan: ArtifactsPlan,
) {
    /** The sha256 of [data] as the pack stores it. */
    val dataSha256: String = TransferPack.sha256Hex(data)

    fun manifest(artifactsSha256: String): TransferPackManifest = TransferPackManifest(
        packFormatVersion = TransferPack.PACK_FORMAT_VERSION,
        packId = packId,
        createdAt = createdAt,
        appVersion = appVersion,
        schemaVersion = schemaVersion,
        dataFormatVersion = dataFormatVersion,
        artifactFormatVersion = artifactFormatVersion,
        rootAssetIds = rootAssetIds,
        assetIds = assetIds,
        lineage = lineage,
        counts = counts,
        attachments = attachments,
        attachmentBytes = attachmentBytes,
        dataSha256 = dataSha256,
        artifactsSha256 = artifactsSha256,
        contentSha256 = contentSha256,
        note = note,
    )
}
