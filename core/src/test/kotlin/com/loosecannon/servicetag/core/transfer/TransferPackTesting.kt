package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.backup.ArtifactsPlan
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.usecase.BackupRepositories
import com.loosecannon.servicetag.core.usecase.CreateTransferPack
import com.loosecannon.servicetag.core.usecase.CreateTransferPackResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/** #77 (B1) — the pieces the pack tests share: a seeded install, its creation, and raw ZIP surgery. */
internal object TransferPackTesting {
    const val PACK_ID = "pack-0001"
    const val CREATED_AT = 1_758_950_000_000L

    fun repositoriesOf(install: BackupInstall) = BackupRepositories(
        install.assets, install.groups, install.tags, install.links, install.definitions, install.profiles,
        install.schedules, install.closures, install.events, install.attachments, install.references,
        install.activations, install.conditions, install.subjects, install.categories, install.serviceCases,
        install.caseEntries, install.loans, install.transfers,
    )

    fun creationOf(
        install: BackupInstall,
        lineageOf: suspend (AssetId) -> List<String> = { emptyList() },
        maxDataBytes: Long = TransferPack.MAX_PACK_DATA_BYTES,
        ids: IdGenerator = IdGenerator { PACK_ID },
        maxManifestBytes: Long = TransferPack.MAX_MANIFEST_BYTES,
        maxJsonBytes: Long = TransferPack.MAX_PACK_JSON_BYTES,
    ) = CreateTransferPack(
        repositoriesOf(install), install.uow, ids, Clock { CREATED_AT }, appVersion = "1.4.1", schemaVersion = 13,
        lineageOf = lineageOf, maxDataBytes = maxDataBytes, maxManifestBytes = maxManifestBytes,
        maxJsonBytes = maxJsonBytes,
    )

    /** A ZIP whose names are in a legacy code page: `é` is the single byte 0xE9, and no UTF-8 flag is set. */
    fun legacyZipOf(entries: List<Raw>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.ISO_8859_1).use { zos ->
            entries.forEach { raw ->
                zos.putNextEntry(ZipEntry(raw.name))
                zos.write(raw.bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The fixtures' heater (and so its anode) as a draft, from a freshly seeded install. */
    fun heaterDraft(note: String = "Example handover note"): TransferPackDraft = runBlocking {
        val install = BackupInstall()
        TransferFixtures.seed(install)
        val result = creationOf(install).run(listOf(AssetId(TransferFixtures.HEATER)), note)
        assertIs<CreateTransferPackResult.Created>(result).draft
    }

    /** `ArtifactsCodec.write` over the fixtures' bytes, plus [extra] locators a test adds. */
    fun artifactsOf(plan: ArtifactsPlan, extra: Map<String, ByteArray> = emptyMap()): ByteArray = runBlocking {
        val bytes = TransferFixtures.bytesByLocator + extra
        val out = ByteArrayOutputStream()
        ArtifactsCodec.write(out, plan) { locator -> bytes[locator]?.let(::ByteArrayInputStream) }
        out.toByteArray()
    }

    fun seal(draft: TransferPackDraft, artifacts: ByteArray = artifactsOf(draft.plan)): Pair<ByteArray, TransferPackWritten> {
        val out = ByteArrayOutputStream()
        val written = TransferPackCodec.write(out, draft) { ByteArrayInputStream(artifacts) }
        return out.toByteArray() to written
    }

    fun read(pack: ByteArray): TransferPackRead = runBlocking { TransferPackReader.read(ByteArrayInputStream(pack)) }

    /** One raw entry: its name, its bytes, whether it was STORED. */
    data class Raw(val name: String, val bytes: ByteArray, val stored: Boolean = true)

    fun entriesOf(zip: ByteArray): List<Raw> {
        val entries = mutableListOf<Raw>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries += Raw(entry.name, zin.readBytes(), entry.method == ZipEntry.STORED)
            }
        }
        return entries
    }

    /** A ZIP of exactly [entries], in order; directories by a trailing '/', everything DEFLATED. */
    fun zipOf(entries: List<Raw>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { raw ->
                zos.putNextEntry(ZipEntry(raw.name))
                zos.write(raw.bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A copy of [draft] with the fields a test tampers with. */
    fun redraft(
        draft: TransferPackDraft,
        assetIds: List<String> = draft.assetIds,
        lineage: Map<String, List<String>> = draft.lineage,
        note: String = draft.note,
        data: ByteArray = draft.data,
        plan: ArtifactsPlan = draft.plan,
    ) = TransferPackDraft(
        packId = draft.packId, createdAt = draft.createdAt, appVersion = draft.appVersion,
        schemaVersion = draft.schemaVersion, dataFormatVersion = draft.dataFormatVersion,
        artifactFormatVersion = draft.artifactFormatVersion, rootAssetIds = draft.rootAssetIds, assetIds = assetIds,
        lineage = lineage, counts = draft.counts, attachments = draft.attachments,
        attachmentBytes = draft.attachmentBytes, contentSha256 = draft.contentSha256, note = note, data = data,
        plan = plan,
    )
}
