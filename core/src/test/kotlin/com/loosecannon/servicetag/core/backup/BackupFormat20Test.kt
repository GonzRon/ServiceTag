package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.editRows
import com.loosecannon.servicetag.core.testing.installedComponentOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.supplyItemOf
import com.loosecannon.servicetag.core.testing.with
import com.loosecannon.servicetag.core.testing.without
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backup format 20 (#69, B1b rows 7–10; C9, C10; R69-5): the envelope only. Every `attachments[]` and
 * `assetReferences[]` row appends two keys, `supplyItemId` and `installedComponentId`, each an explicit `null` when
 * unset; no list, table or count key is added. No shipped writer put either key into a format ≤19 archive — the keys
 * did not exist — so a non-null one there was built by hand and is refused naming the list and the row; an explicit
 * `null` there, or no key at all, is what this build reads anyway. A row names exactly one owner. The names are
 * fictional.
 */
class BackupFormat20Test {

    private val ownerKeys = listOf("supplyItemId", "installedComponentId")

    /** The row each owner key names in [data]: a SupplyItem and an installed component that are in the file. */
    private val ownerIdOf = mapOf("supplyItemId" to "s1", "installedComponentId" to "c1")

    private val generator = plainAssetOf("x1", "Example Generator")
    private val battery = supplyItemOf("s1", "Example 12 V Battery")
    private val tray = installedComponentOf("c1", name = "Example Battery Tray")

    private val service = AssetEventDto(
        id = "e1", assetId = "x1", kind = "MEASUREMENT", title = "Example service", profileId = null,
        occurredOn = "2026-09-15", occurredTime = null, tzId = "UTC", notes = "", source = "MANUAL", sourceRef = null,
        createdAt = 1_000L, updatedAt = 2_000L, measurements = emptyList(), consumables = emptyList(),
    )

    /** The asset's manual, with a role. */
    private val manual = AttachmentDto(
        id = "att-1", assetId = "x1", eventId = null, kind = "MANUAL", mode = "MANAGED",
        displayName = "Example Generator manual.pdf", mimeType = "application/pdf", sizeBytes = 12L,
        sha256 = "a".repeat(64), storageProvider = "SAF_TREE", storageLocator = "assets/x1/att-1.pdf",
        capturedOn = "2026-09-15", notes = "", createdAt = 1_000L, updatedAt = 2_000L, role = "USER_MANUAL",
    )

    /** An entry's photo. */
    private val photo = AttachmentDto(
        id = "att-2", assetId = null, eventId = "e1", kind = "PHOTO", mode = "MANAGED",
        displayName = "Example service photo.jpg", mimeType = "image/jpeg", sizeBytes = 34L,
        sha256 = "b".repeat(64), storageProvider = "SAF_TREE", storageLocator = "events/e1/att-2.jpg",
        capturedOn = null, notes = "", createdAt = 1_000L, updatedAt = 2_000L,
    )

    /** The asset's web link, with a role. */
    private val link = AssetReferenceDto(
        id = "r1", assetId = "x1", kind = "WEB_URL", uri = "https://example.invalid/generator/manual.pdf",
        displayName = "Example Generator manual", description = "", scheme = "https",
        createdAt = 1_000L, updatedAt = 2_000L, role = "USER_MANUAL",
    )

    /** One asset, its entry, a file on each and a link; with [owners], the SupplyItem and component the keys name. */
    private fun data(owners: Boolean = true): BackupData = BackupData(
        listOf(generator.toDto()), emptyList(), emptyList(),
        assetEvents = listOf(service),
        attachments = listOf(manual, photo),
        assetReferences = listOf(link),
        supplyItems = if (owners) listOf(battery.toDto()) else emptyList(),
        installedComponents = if (owners) listOf(tray.toDto()) else emptyList(),
    )

    private val SerialDescriptor.names: List<String> get() = elementNames.toList()

    private val JsonObject.id: String get() = getValue("id").jsonPrimitive.content

    /** [this] tree with [edit] applied to the one row [id] of [list]. */
    private fun JsonObject.editRow(list: String, id: String, edit: (JsonObject) -> JsonObject): JsonObject =
        editRows(list) { row -> if (row.id == id) edit(row) else row }

    /** [this] tree with [edit] applied to every attachment and every reference. */
    private fun JsonObject.editResources(edit: (JsonObject) -> JsonObject): JsonObject =
        editRows("attachments", edit).editRows("assetReferences", edit)

    private fun refusalOf(bytes: ByteArray): String =
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(bytes) }.message!!

    // --- row 7: the round trip of the envelope ---------------------------------------------------

    /**
     * Hazard: an owner key left out of the written row. An asset's file, an entry's file and an asset's link each
     * write both keys, as explicit nulls, appended after the shipped last key (`sourceName`, `role`); the rows read
     * back equal, on their shipped owners.
     */
    @Test
    fun assetAndEventRowsWriteBothNewKeysAsExplicitNulls() {
        val bytes = archiveOf(data())

        val decoded = BackupCodec.decode(bytes)

        assertEquals(20, decoded.manifest.formatVersion)
        assertEquals(listOf(manual, photo), decoded.data.attachments)
        assertEquals(listOf(link), decoded.data.assetReferences)
        assertEquals(
            listOf(AttachmentOwner.OfAsset(AssetId("x1")), AttachmentOwner.OfEvent(EventId("e1"))),
            decoded.data.attachments.map { it.toDomain().owner },
        )
        val tree = dataTreeOf(bytes)
        for ((list, shippedLast) in listOf("attachments" to "sourceName", "assetReferences" to "role")) {
            for (row in tree.getValue(list).jsonArray.map { it.jsonObject }) {
                assertEquals(listOf(shippedLast) + ownerKeys, row.keys.toList().takeLast(3), "$list ${row.id}: appended, in order")
                for (key in ownerKeys) assertEquals(JsonNull, row[key], "$list ${row.id}: $key is written as an explicit null")
            }
        }
    }

    /**
     * Hazard: a key slipping in or out of the envelope. Both DTOs are exactly these keys in this order, the two new
     * ones last; each new one has a default, so an archive written before them decodes.
     */
    @Test
    fun theDtosCarryExactlyTheseKeysInOrder() {
        assertEquals(
            listOf(
                "id", "assetId", "eventId", "kind", "mode", "displayName", "mimeType", "sizeBytes", "sha256",
                "storageProvider", "storageLocator", "capturedOn", "notes", "createdAt", "updatedAt", "role",
                "sourceUri", "sourceResolvedUri", "sourceRetrievedAt", "sourceName", "supplyItemId", "installedComponentId",
            ),
            AttachmentDto.serializer().descriptor.names,
        )
        assertEquals(
            listOf(
                "id", "assetId", "kind", "uri", "displayName", "description", "scheme", "createdAt", "updatedAt", "role",
                "supplyItemId", "installedComponentId",
            ),
            AssetReferenceDto.serializer().descriptor.names,
        )
        for (descriptor in listOf(AttachmentDto.serializer().descriptor, AssetReferenceDto.serializer().descriptor)) {
            for (key in ownerKeys) {
                assertTrue(descriptor.isElementOptional(descriptor.getElementIndex(key)), "${descriptor.serialName}.$key has a default")
            }
        }
    }

    /**
     * The shipped shape before #69: no owner key at all, as a format 19 or 17 writer put its rows down. It reads with
     * every row on its shipped owner.
     */
    @Test
    fun aFormat19ArchiveWithoutTheKeysDecodes() {
        for ((format, data) in listOf(19 to data(), 17 to data(owners = false))) {
            val absent = sealed(dataTreeOf(archiveOf(data)).editResources { it.without(*ownerKeys.toTypedArray()) }, format)
            val tree = dataTreeOf(absent)
            assertTrue(
                listOf("attachments", "assetReferences").all { list -> tree.getValue(list).jsonArray.none { row -> ownerKeys.any { it in row.jsonObject } } },
                "the fixture carries no owner key",
            )

            val decoded = BackupCodec.decode(absent)

            assertEquals(format, decoded.manifest.formatVersion)
            assertEquals(listOf(manual, photo), decoded.data.attachments, "format $format")
            assertEquals(listOf(link), decoded.data.assetReferences, "format $format")
        }
    }

    // --- row 8: the format ≤ 19 gate -------------------------------------------------------------

    /**
     * Hazard: a hand-built older archive carrying an owner the format could not have written. Each key on each list is
     * refused by the gate, naming the list, the format and the row: an attachment whose owner moved onto the key, a
     * reference carrying it beside its asset. Without the gate the row is still refused, by exactly-one, with another
     * message (N-3) — so the message is what this asserts.
     */
    @Test
    fun aFormat19ArchiveWithANewOwnerKeyIsCorrupt() {
        val tree = dataTreeOf(archiveOf(data()))
        for (format in listOf(19, 17)) {
            for (key in ownerKeys) {
                val owner = JsonPrimitive(ownerIdOf.getValue(key))
                val attachment = tree.editRow("attachments", "att-1") { it.with("assetId", JsonNull).with(key, owner) }
                val reference = tree.editRow("assetReferences", "r1") { it.with(key, owner) }

                assertEquals(
                    "attachments: a format $format archive cannot carry a supply item or installed component owner (attachment att-1)",
                    refusalOf(sealed(attachment, format)),
                    "$key at format $format",
                )
                assertEquals(
                    "assetReferences: a format $format archive cannot carry a supply item or installed component owner (reference r1)",
                    refusalOf(sealed(reference, format)),
                    "$key at format $format",
                )
            }
        }
    }

    /** An explicit `null` on both keys, on every row, is what this build writes anyway: a format-19 archive with them reads. */
    @Test
    fun explicitNullsInAFormat19ArchiveDecode() {
        val explicit = dataTreeOf(archiveOf(data())).editResources { row -> ownerKeys.fold(row) { acc, key -> acc.with(key, JsonNull) } }

        val decoded = BackupCodec.decode(sealed(explicit, 19))

        assertEquals(19, decoded.manifest.formatVersion)
        assertEquals(listOf(manual, photo), decoded.data.attachments)
        assertEquals(listOf(link), decoded.data.assetReferences)
    }

    // --- row 9: exactly one owner ----------------------------------------------------------------

    /** A link that names no owner — its asset key an explicit `null`, or left out — is refused before anything is written. */
    @Test
    fun aReferenceWithNoOwnerKeyIsCorrupt() {
        val tree = dataTreeOf(archiveOf(data()))
        val shapes = mapOf<String, (JsonObject) -> JsonObject>(
            "an explicit null" to { it.with("assetId", JsonNull) },
            "no key at all" to { it.without("assetId") },
        )
        for ((shape, edit) in shapes) {
            assertFailsWith<BackupCorrupt>(shape) { BackupCodec.decode(sealed(tree.editRow("assetReferences", "r1", edit), 20)) }
        }
    }

    /**
     * Hazard: a second owner read past. A file or a link naming its shipped owner and a new one, or an entry's file
     * naming both new ones, is refused by exactly-one at format 20, naming the row — never read as the first owner.
     */
    @Test
    fun twoOwnerKeysAreCorrupt() {
        val tree = dataTreeOf(archiveOf(data()))
        for (key in ownerKeys) {
            val owner = JsonPrimitive(ownerIdOf.getValue(key))

            assertEquals(
                "attachment att-1 must name exactly one owner, an asset, an event, a supply item or an installed component",
                refusalOf(sealed(tree.editRow("attachments", "att-1") { it.with(key, owner) }, 20)),
                key,
            )
            assertEquals(
                "reference r1 must name exactly one owner, an asset, a supply item or an installed component",
                refusalOf(sealed(tree.editRow("assetReferences", "r1") { it.with(key, owner) }, 20)),
                key,
            )
        }
        val bothNew = tree.editRow("attachments", "att-2") { row ->
            ownerKeys.fold(row.with("eventId", JsonNull)) { acc, key -> acc.with(key, JsonPrimitive(ownerIdOf.getValue(key))) }
        }
        assertEquals(
            "attachment att-2 must name exactly one owner, an asset, an event, a supply item or an installed component",
            refusalOf(sealed(bothNew, 20)),
        )
    }

    // --- row 10: newer refused -------------------------------------------------------------------

    /** One format past this build's, over a tree no format could read: refused as newer before a row is parsed. */
    @Test
    fun aFormat21ArchiveIsRefusedAsNewer() {
        val unreadable = dataTreeOf(archiveOf(data()))
            .editRow("attachments", "att-1") { it.with("installedComponentId", JsonObject(mapOf("id" to JsonPrimitive("c1")))) }

        val refusal = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(sealed(unreadable, formatVersion = 21)) }

        assertEquals(21, refusal.found)
        assertEquals(20, refusal.supported)
        // The same tree at this build's format is parsed — and refused as corrupt.
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(sealed(unreadable, formatVersion = 20)) }
    }

    // --- B2a row 14: a SupplyItem's and an installed component's files (C5; R69-6, R69-10) ---------

    private val onBattery = AttachmentOwner.OfSupplyItem(SupplyId("s1"))
    private val onTray = AttachmentOwner.OfInstalledComponent(InstalledComponentId("c1"))

    /** A file on [owner] as the phone writes it: its locator under the owner's own directory. */
    private fun fileOn(owner: AttachmentOwner, id: String, role: DocumentRole? = null) = Attachment(
        id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT, displayName = "Example data sheet.pdf",
        mimeType = "application/pdf", sizeBytes = 21L, sha256 = "c".repeat(64),
        storageLocator = AttachmentLocator.forOwner(owner, AttachmentId(id), "Example data sheet.pdf", "application/pdf"),
        capturedOn = null, createdAt = 1_000L, updatedAt = 2_000L, role = role,
    )

    /** [base] with [files] beside the asset's and the entry's. */
    private fun dataWith(vararg files: AttachmentDto, base: BackupData = data()): BackupData =
        base.copy(attachments = base.attachments + files)

    /**
     * Hazard: a new owner's key dropped on the way out or misread on the way in. A SupplyItem's file and a component's
     * write their own key and null the other three, under their own directory, and read back equal.
     */
    @Test
    fun aSupplyItemsAndAComponentsFilesRoundTrip() {
        val sheet = fileOn(onBattery, "att-3")
        val fitted = fileOn(onTray, "att-4")
        val bytes = archiveOf(dataWith(sheet.toDto(), fitted.toDto()))

        val decoded = BackupCodec.decode(bytes)

        assertEquals(listOf(sheet, fitted), decoded.data.attachments.drop(2).map { it.toDomain() })
        assertEquals("supply-items/s1/att-3.pdf", sheet.storageLocator)
        assertEquals("installed-components/c1/att-4.pdf", fitted.storageLocator)
        val rows = dataTreeOf(bytes).getValue("attachments").jsonArray.map { it.jsonObject }.associateBy { it.id }
        val written = mapOf(
            "att-3" to listOf(JsonNull, JsonNull, JsonPrimitive("s1"), JsonNull),
            "att-4" to listOf(JsonNull, JsonNull, JsonNull, JsonPrimitive("c1")),
        )
        for ((id, values) in written) {
            val keys = listOf("assetId", "eventId") + ownerKeys
            assertEquals(values, keys.map { rows.getValue(id)[it] }, "$id: $keys")
        }
    }

    /** R69-10: an archived SupplyItem and a removed component are owners like any other in the file. */
    @Test
    fun anArchivedSupplyItemOrRemovedComponentIsAValidOwner() {
        val retired = data().copy(
            supplyItems = listOf(supplyItemOf("s1", "Example 12 V Battery", archivedAt = 3_000L).toDto()),
            installedComponents = listOf(
                installedComponentOf("c1", name = "Example Battery Tray", installedOn = "2026-09-01", removedOn = "2026-09-10")
                    .toDto(),
            ),
        )

        val files = arrayOf(fileOn(onBattery, "att-3").toDto(), fileOn(onTray, "att-4").toDto())

        val decoded = BackupCodec.decode(archiveOf(dataWith(*files, base = retired)))

        assertEquals(listOf(onBattery, onTray), decoded.data.attachments.drop(2).map { it.toDomain().owner })
    }

    /**
     * Hazard: an owner checked against the wrong list. A SupplyItem's file must name a SupplyItem in the file and a
     * component's a component in the file — an id string another list holds is not enough — in the two shipped
     * templates (G2).
     */
    @Test
    fun anOwnerNotInTheFileIsCorrupt() {
        val cases = listOf(
            AttachmentOwner.OfSupplyItem(SupplyId("s9")) to "points at supply item s9, which is not in supplyItems",
            AttachmentOwner.OfSupplyItem(SupplyId("c1")) to "points at supply item c1, which is not in supplyItems",
            AttachmentOwner.OfInstalledComponent(InstalledComponentId("c9")) to
                "points at installed component c9, which is not in installedComponents",
            AttachmentOwner.OfInstalledComponent(InstalledComponentId("s1")) to
                "points at installed component s1, which is not in installedComponents",
        )
        for ((owner, problem) in cases) {
            val refusal = refusalOf(archiveOf(dataWith(fileOn(owner, "att-3").toDto())))
            assertEquals("attachments: attachment att-3 $problem", refusal, "$owner")
        }
    }

    /** I4: a SupplyItem's or a component's locator under any other directory is not its own. */
    @Test
    fun aLocatorUnderAnotherOwnersDirectoryIsCorrupt() {
        val sheet = fileOn(onBattery, "att-3").toDto()
        val fitted = fileOn(onTray, "att-4").toDto()
        val misplaced = listOf(
            sheet.copy(storageLocator = "installed-components/s1/att-3.pdf"),
            sheet.copy(storageLocator = "assets/s1/att-3.pdf"),
            sheet.copy(storageLocator = "supply-items/s2/att-3.pdf"),
            fitted.copy(storageLocator = "supply-items/c1/att-4.pdf"),
            fitted.copy(storageLocator = "components/c1/att-4.pdf"),
        )
        for (row in misplaced) {
            assertEquals(
                "attachments: attachment ${row.id} has a locator that is not its own",
                refusalOf(archiveOf(dataWith(row))),
                row.storageLocator,
            )
        }
    }

    /** R69-6: a role on a SupplyItem's or a component's file reads back; one on an entry's file is still refused. */
    @Test
    fun aRoleOnASupplyItemsFileDecodes() {
        val guide = fileOn(onBattery, "att-3", DocumentRole.USER_MANUAL)
        val service = fileOn(onTray, "att-4", DocumentRole.SERVICE_MANUAL)

        val decoded = BackupCodec.decode(archiveOf(dataWith(guide.toDto(), service.toDto())))

        assertEquals(listOf(guide, service), decoded.data.attachments.drop(2).map { it.toDomain() })
        assertEquals(
            "attachment att-2 is an entry's file and carries a document role; an entry's file takes none",
            refusalOf(archiveOf(data().copy(attachments = listOf(manual, photo.copy(role = "USER_MANUAL"))))),
        )
    }
}
