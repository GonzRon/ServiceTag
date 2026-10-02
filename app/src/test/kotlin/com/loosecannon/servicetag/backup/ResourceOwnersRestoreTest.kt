package com.loosecannon.servicetag.backup

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.InMemoryAttachmentStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * #69 (C14, H1; row 32) — the replace restore writes every resource after every owner it can name. A link's owner is
 * an asset, a SupplyItem or an installed component, and a file's one of those or an entry, each a foreign key on Room:
 * so the links land after the assets, the SupplyItems and the components, and the files last, whatever order the file
 * lists anything in. The archive here is a real export with every list reversed (the component tree included, child
 * before parent), restored onto an empty install over Room, and exported again: `data.json` comes back byte for byte.
 * The names are fictional and every URL is under `example.invalid`.
 */
class ResourceOwnersRestoreTest {

    private val source = FakeGraph()
    private val target = FakeGraph()

    @After fun close() {
        source.close()
        target.close()
    }

    private val ups = AssetId("asset-ups")
    private val battery = SupplyId("supply-battery")
    private val tray = InstalledComponentId("installed-tray")
    private val slot = InstalledComponentId("installed-tray-slot")

    private fun link(id: String, owner: ReferenceOwner, uri: String) = AssetReference(
        id = ReferenceId(id), owner = owner, kind = ReferenceKind.WEB_URL, uri = uri, displayName = "Example page $id",
        description = "", scheme = "https", createdAt = 1_000L, updatedAt = 2_000L,
    )

    private fun file(id: String, owner: AttachmentOwner) = Attachment(
        id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT, displayName = "Example sheet $id.pdf",
        mimeType = "application/pdf", sizeBytes = 4L, sha256 = "c".repeat(64),
        storageLocator = "${AttachmentLocator.dirFor(owner)}/$id.pdf", capturedOn = null, createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    private fun component(id: InstalledComponentId, name: String, parentId: InstalledComponentId?, supplyId: SupplyId?) =
        InstalledComponent(
            id = id, assetId = ups, parentId = parentId, name = name, supplyId = supplyId, composition = emptyList(),
            serialOrLot = "", installedOn = "2026-01-10", removedOn = null, replacesId = null, sortOrder = 0, notes = "",
            createdAt = 1_000L, updatedAt = 2_000L,
        )

    /** One asset, one SupplyItem, a two-row component tree, and a link and a file on every owner. */
    private suspend fun seed(graph: FakeGraph) {
        graph.assets.upsert(Asset(id = ups, name = "Example UPS", createdAt = 1_000L, updatedAt = 2_000L))
        graph.supplyItems.upsert(
            SupplyItem(
                id = battery, name = "Example 12 V Battery", category = "Battery", manufacturer = "Example Power Co.",
                model = "EP-12", partNumber = "EP-12-7", preferredUnit = "ea", notes = "", archivedAt = null,
                createdAt = 1_000L, updatedAt = 2_000L, specifications = emptyList(),
            ),
        )
        graph.installedComponents.insert(component(tray, "Example Battery Tray", null, null))
        graph.installedComponents.insert(component(slot, "Position 1", tray, battery))
        graph.references.upsert(link("ref-ups", ReferenceOwner.OfAsset(ups), "https://example.invalid/ups/manual"))
        graph.references.upsert(
            link("ref-battery", ReferenceOwner.OfSupplyItem(battery), "https://example.invalid/battery/datasheet"),
        )
        graph.references.upsert(
            link("ref-tray", ReferenceOwner.OfInstalledComponent(tray), "https://example.invalid/tray/guide"),
        )
        graph.attachments.upsert(file("att-ups", AttachmentOwner.OfAsset(ups)))
        graph.attachments.upsert(file("att-battery", AttachmentOwner.OfSupplyItem(battery)))
        graph.attachments.upsert(file("att-slot", AttachmentOwner.OfInstalledComponent(slot)))
    }

    @Test
    fun aShuffledArchiveWithSupplyItemAndComponentLinksRestoresByteEqual() = runTest {
        seed(source)
        val export = source.exportBackupSet.run().data
        val archive = shuffled(export)
        assertNotEquals(
            "the fixture is reordered",
            entryOf(export, BackupCodec.DATA_ENTRY).decodeToString(),
            entryOf(archive, BackupCodec.DATA_ENTRY).decodeToString(),
        )

        target.importBackupReplace.run(archive)

        assertEquals(
            entryOf(export, BackupCodec.DATA_ENTRY).decodeToString(),
            entryOf(target.exportBackupSet.run().data, BackupCodec.DATA_ENTRY).decodeToString(),
        )
        assertEquals(listOf("ref-battery"), target.references.forOwner(ReferenceOwner.OfSupplyItem(battery)).map { it.id.value })
        assertEquals(listOf("ref-tray"), target.references.forOwner(ReferenceOwner.OfInstalledComponent(tray)).map { it.id.value })
        assertEquals(listOf("att-battery"), target.attachments.forOwner(AttachmentOwner.OfSupplyItem(battery)).map { it.id.value })
        assertEquals(listOf("att-slot"), target.attachments.forOwner(AttachmentOwner.OfInstalledComponent(slot)).map { it.id.value })
    }

    // --- the archive, reordered -----------------------------------------------------------------------

    private fun entriesOf(archive: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun entryOf(archive: ByteArray, name: String): ByteArray = entriesOf(archive).getValue(name)

    /** [archive] with every list in its `data.json` reversed and the manifest's checksum sealed again. */
    private fun shuffled(archive: ByteArray): ByteArray {
        val entries = entriesOf(archive)
        val tree = Json.parseToJsonElement(entries.getValue(BackupCodec.DATA_ENTRY).decodeToString()).jsonObject
        val reversed = JsonObject(tree.mapValues { (_, value) -> if (value is JsonArray) JsonArray(value.reversed()) else value })
        val data = Json.encodeToString(JsonObject.serializer(), reversed).toByteArray(Charsets.UTF_8)
        val manifest = Json.parseToJsonElement(entries.getValue(BackupCodec.MANIFEST_ENTRY).decodeToString()).jsonObject
        val resealed = JsonObject(manifest + ("dataSha256" to JsonPrimitive(InMemoryAttachmentStore.sha256Hex(data))))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in listOf(
                BackupCodec.MANIFEST_ENTRY to Json.encodeToString(JsonObject.serializer(), resealed).toByteArray(Charsets.UTF_8),
                BackupCodec.DATA_ENTRY to data,
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
