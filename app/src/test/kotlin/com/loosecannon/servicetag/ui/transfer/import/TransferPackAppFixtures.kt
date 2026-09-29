package com.loosecannon.servicetag.ui.transfer.`import`

import com.loosecannon.servicetag.core.backup.ArtifactsCodec
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.transfer.TransferPackCodec
import com.loosecannon.servicetag.core.usecase.CreateTransferPackResult
import com.loosecannon.servicetag.core.usecase.CreatedPack
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.InMemoryAttachmentStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A sealed Transfer Pack made on a [FakeGraph]: the file's bytes, and what marking needs. */
internal class AppPack(val bytes: ByteArray, val created: CreatedPack)

/**
 * #77 (B3) — Transfer Packs for the app's JVM tests, made by the production creation over a Room-backed [FakeGraph]:
 * "Example Water Heater" with an NFC tag (`TEST-0001`) and its manual. Fictional names and keys only.
 */
internal object TransferPackAppFixtures {
    const val HEATER = "h1"
    const val MANUAL_AT = "assets/h1/at1.pdf"
    val MANUAL: ByteArray = "Example Water Heater manual".toByteArray()

    suspend fun seedHeater(graph: FakeGraph, withManual: Boolean = true) {
        graph.assets.upsert(Asset(id = AssetId(HEATER), name = "Example Water Heater", createdAt = 100L, updatedAt = 100L))
        graph.tags.upsert(
            TagBinding(
                id = TagId("t1"), payloadFormat = PayloadFormat.V1, payloadKey = "TEST-0001",
                target = TagTarget.AssetTarget(AssetId(HEATER)), createdAt = 100L, updatedAt = 100L,
            ),
        )
        if (withManual) {
            graph.attachments.upsert(
                Attachment(
                    id = AttachmentId("at1"), owner = AttachmentOwner.OfAsset(AssetId(HEATER)),
                    kind = AttachmentKind.DOCUMENT, displayName = "Manual.pdf", mimeType = "application/pdf",
                    sizeBytes = MANUAL.size.toLong(), sha256 = InMemoryAttachmentStore.sha256Hex(MANUAL),
                    storageLocator = MANUAL_AT, capturedOn = null, createdAt = 100L, updatedAt = 100L,
                ),
            )
            graph.attachmentStorage.store.files[MANUAL_AT] = MANUAL
        }
    }

    /** Creates a pack of [roots] on [graph] with the production use case, and seals it with its documents' bytes. */
    suspend fun seal(graph: FakeGraph, vararg roots: String, note: String = ""): AppPack {
        val result = graph.createTransferPack.run(roots.map(::AssetId), note)
        val draft = (result as CreateTransferPackResult.Created).draft
        val artifacts = ByteArrayOutputStream().also { out ->
            ArtifactsCodec.write(out, draft.plan) { locator ->
                graph.attachmentStorage.store.files[locator]?.let(::ByteArrayInputStream)
            }
        }.toByteArray()
        val out = ByteArrayOutputStream()
        val written = TransferPackCodec.write(out, draft) { ByteArrayInputStream(artifacts) }
        return AppPack(out.toByteArray(), CreatedPack.of(draft, written.packSha256))
    }

    /** A ZIP of [entries] in order — any ZIP that is not a pack, or a pack after surgery. */
    fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun entriesOf(zip: ByteArray): List<Pair<String, ByteArray>> {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries += entry.name to zin.readBytes()
            }
        }
        return entries
    }
}
