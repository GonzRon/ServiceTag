package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteException
import com.loosecannon.servicetag.data.room.entities.AssetEntity
import com.loosecannon.servicetag.data.room.entities.AssetReferenceEntity
import com.loosecannon.servicetag.data.room.entities.AttachmentEntity
import com.loosecannon.servicetag.data.room.entities.InstalledComponentEntity
import com.loosecannon.servicetag.data.room.entities.SupplyItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Schema v20's owner columns (#69, C6–C8) against a real database: the rules the schema carries, and the owner-keyed
 * reads beside the asset ones.
 *
 *  - A URI is unique **per owner** (I3): one unique pair per owner column, so one owner holds a URI once, the same
 *    URI on two owners is ordinary, and one owner holds any number of different URIs.
 *  - Every owner column is CASCADE: deleting an asset takes its installed components' files and links at every
 *    depth, and the SupplyItem wipe takes the SupplyItems' own; no other owner's row moves.
 *  - Exactly one owner, on both entities, is the mappers' `require` — the schema carries no `CHECK`.
 *  - The SupplyItem and installed-component reads return that owner's rows only, in the asset reads' order.
 *
 * Here a SupplyItem and an installed component deliberately share the id string `x1`, so a read keyed by the wrong
 * column returns the other owner's rows rather than nothing. The names are fictional.
 */
class ResourceOwnerDaoConstraintTest {

    private val db = inMemoryDb()
    private val attachments = db.attachmentDao()
    private val references = db.assetReferenceDao()

    @After fun close() = db.close()

    @Test
    fun oneUriOncePerOwnerAndAcrossOwnersFreely() = runTest {
        seed()

        references.upsert(reference("r-s1", supply = "s1"))
        refused("a second (supply_item_id, uri)") { references.upsert(reference("r-s1-again", supply = "s1")) }
        // a second link on one SupplyItem is ordinary
        references.upsert(reference("r-s1-parts", supply = "s1", uri = PARTS))

        references.upsert(reference("r-c1", component = "c1"))
        refused("a second (installed_component_id, uri)") { references.upsert(reference("r-c1-again", component = "c1")) }
        references.upsert(reference("r-c1-parts", component = "c1", uri = PARTS))

        // the same URI on every other owner: another SupplyItem, another component, an asset
        references.upsert(reference("r-x1-supply", supply = "x1"))
        references.upsert(reference("r-x1-component", component = "x1"))
        references.upsert(reference("r-a1", asset = "a1"))

        assertEquals(
            listOf("r-a1", "r-c1", "r-c1-parts", "r-s1", "r-s1-parts", "r-x1-component", "r-x1-supply"),
            references.all().map { it.id },
        )
        assertEquals("r-s1", references.findByUriOnSupplyItem("s1", MANUAL)?.id)
        assertEquals("r-c1", references.findByUriOnInstalledComponent("c1", MANUAL)?.id)
        assertEquals("r-x1-supply", references.findByUriOnSupplyItem("x1", MANUAL)?.id)
        assertEquals("r-x1-component", references.findByUriOnInstalledComponent("x1", MANUAL)?.id)
        assertEquals("r-a1", references.findByUri("a1", MANUAL)?.id)
        assertNull(references.findByUriOnSupplyItem("s2", MANUAL))
    }

    /** Asset → installed component → its child component, each with a file and a link: one delete takes them all. */
    @Test
    fun deletingAnAssetTakesItsComponentsFilesAndLinks() = runTest {
        seed()
        db.installedComponentDao().insert(component("c1-child", asset = "a1", parent = "c1"), emptyList())
        for (owner in listOf("c1", "c1-child", "c2")) {
            attachments.upsert(attachment("att-$owner", component = owner))
            references.upsert(reference("r-$owner", component = owner))
        }
        attachments.upsert(attachment("att-a1", asset = "a1"))
        attachments.upsert(attachment("att-s1", supply = "s1"))
        references.upsert(reference("r-s1", supply = "s1"))

        db.assetDao().delete("a1")

        assertEquals(listOf("c2", "x1"), db.installedComponentDao().all().map { it.row.id })
        assertEquals(listOf("att-c2", "att-s1"), attachments.all().map { it.id }.sorted())
        assertEquals(listOf("r-c2", "r-s1"), references.all().map { it.id })
    }

    @Test
    fun aSupplyItemWipeTakesItsResources() = runTest {
        db.assetDao().upsert(asset("a1"))
        db.supplyItemDao().upsert(item("s1"), emptyList())
        db.supplyItemDao().upsert(item("s2"), emptyList())
        attachments.upsert(attachment("att-s1", supply = "s1"))
        attachments.upsert(attachment("att-s2", supply = "s2"))
        attachments.upsert(attachment("att-a1", asset = "a1"))
        references.upsert(reference("r-s1", supply = "s1"))
        references.upsert(reference("r-a1", asset = "a1"))

        db.supplyItemDao().deleteAll()

        assertEquals(listOf("att-a1"), attachments.all().map { it.id })
        assertEquals(listOf("r-a1"), references.all().map { it.id })
    }

    @Test
    fun exactlyOneOwnerIsRequiredOnBothEntities() {
        val file = attachment("x", asset = null)
        val fileOwners = listOf<(AttachmentEntity) -> AttachmentEntity>(
            { it.copy(assetId = "a1") },
            { it.copy(eventId = "e1") },
            { it.copy(supplyItemId = "s1") },
            { it.copy(installedComponentId = "c1") },
        )
        assertThrows(IllegalArgumentException::class.java) { file.requireExactlyOneOwner() }
        for ((i, first) in fileOwners.withIndex()) {
            val one = first(file)
            assertEquals("owner $i alone", one, one.requireExactlyOneOwner())
            for (second in fileOwners.drop(i + 1)) {
                assertThrows(IllegalArgumentException::class.java) { second(one).requireExactlyOneOwner() }
            }
        }

        val link = reference("y")
        val linkOwners = listOf<(AssetReferenceEntity) -> AssetReferenceEntity>(
            { it.copy(assetId = "a1") },
            { it.copy(supplyItemId = "s1") },
            { it.copy(installedComponentId = "c1") },
        )
        assertThrows(IllegalArgumentException::class.java) { link.requireExactlyOneOwner() }
        for ((i, first) in linkOwners.withIndex()) {
            val one = first(link)
            assertEquals("owner $i alone", one, one.requireExactlyOneOwner())
            for (second in linkOwners.drop(i + 1)) {
                assertThrows(IllegalArgumentException::class.java) { second(one).requireExactlyOneOwner() }
            }
        }
    }

    /**
     * Each owner-keyed read is its asset twin's query on another column: the owner's rows only, files by
     * `display_name COLLATE NOCASE` and links by that then `id`; the observed reads emit the same lists.
     */
    @Test
    fun rowsAndObservedRowsBySupplyItemAndByComponentInTheShippedOrder() = runTest {
        seed()
        for ((owner, supply, component) in listOf(Triple("s", "x1", null), Triple("c", null, "x1"))) {
            attachments.upsert(attachment("$owner-att-b", supply = supply, component = component, name = "b-datasheet.pdf"))
            attachments.upsert(attachment("$owner-att-a", supply = supply, component = component, name = "A-manual.pdf"))
            attachments.upsert(attachment("$owner-att-c", supply = supply, component = component, name = "c-photo.jpg"))
            references.upsert(reference("$owner-r2", supply = supply, component = component, uri = "$MANUAL/2", name = "manual"))
            references.upsert(reference("$owner-r1", supply = supply, component = component, uri = "$MANUAL/1", name = "Manual"))
            references.upsert(reference("$owner-r3", supply = supply, component = component, uri = "$MANUAL/3", name = "Parts"))
        }
        // rows on every other owner, under the same names, which no read below may return
        attachments.upsert(attachment("a1-att", asset = "a1", name = "A-manual.pdf"))
        attachments.upsert(attachment("s1-att", supply = "s1", name = "A-manual.pdf"))
        attachments.upsert(attachment("c1-att", component = "c1", name = "A-manual.pdf"))
        references.upsert(reference("a1-r", asset = "a1", name = "Manual"))
        references.upsert(reference("s1-r", supply = "s1", name = "Manual"))
        references.upsert(reference("c1-r", component = "c1", name = "Manual"))

        val supplyFiles = listOf("s-att-a", "s-att-b", "s-att-c")
        val componentFiles = listOf("c-att-a", "c-att-b", "c-att-c")
        assertEquals(supplyFiles, attachments.forSupplyItem("x1").map { it.id })
        assertEquals(componentFiles, attachments.forInstalledComponent("x1").map { it.id })
        assertEquals(supplyFiles, attachments.observeForSupplyItem("x1").first().map { it.id })
        assertEquals(componentFiles, attachments.observeForInstalledComponent("x1").first().map { it.id })

        val supplyLinks = listOf("s-r1", "s-r2", "s-r3")
        val componentLinks = listOf("c-r1", "c-r2", "c-r3")
        assertEquals(supplyLinks, references.forSupplyItem("x1").map { it.id })
        assertEquals(componentLinks, references.forInstalledComponent("x1").map { it.id })
        assertEquals(supplyLinks, references.observeForSupplyItem("x1").first().map { it.id })
        assertEquals(componentLinks, references.observeForInstalledComponent("x1").first().map { it.id })
    }

    // --- fixtures --------------------------------------------------------------------------------

    /**
     * Assets `a1` and `a2`; SupplyItems `s1`, `s2` and `x1`; installed components `c1` (on `a1`, naming `s1`), `c2`
     * (on `a2`) and `x1` (on `a2` — the SupplyItem's id string).
     */
    private suspend fun seed() {
        db.assetDao().upsert(asset("a1"))
        db.assetDao().upsert(asset("a2"))
        for (id in listOf("s1", "s2", "x1")) db.supplyItemDao().upsert(item(id), emptyList())
        db.installedComponentDao().insert(component("c1", asset = "a1", supply = "s1"), emptyList())
        db.installedComponentDao().insert(component("c2", asset = "a2"), emptyList())
        db.installedComponentDao().insert(component("x1", asset = "a2"), emptyList())
    }

    private fun asset(id: String) = AssetEntity(
        id = id, name = "Example Generator $id", description = "", category = "", notes = "",
        status = "ACTIVE", templateKey = null, createdAt = 1L, updatedAt = 1L,
    )

    private fun item(id: String) = SupplyItemEntity(
        id = id, name = "Example 12 V Battery $id", category = "Batteries", manufacturer = "Example Power Co.",
        model = "EB-$id", partNumber = "EB-$id-1", preferredUnit = "ea", notes = "", archivedAt = null,
        createdAt = 10L, updatedAt = 20L,
    )

    private fun component(id: String, asset: String, parent: String? = null, supply: String? = null) =
        InstalledComponentEntity(
            id = id, assetId = asset, parentId = parent, name = "Example Battery Tray $id", supplyId = supply,
            serialOrLot = "", installedOn = null, removedOn = null, replacesId = null, sortOrder = 0, notes = "",
            createdAt = 30L, updatedAt = 40L,
        )

    private fun attachment(
        id: String,
        asset: String? = null,
        supply: String? = null,
        component: String? = null,
        name: String = "$id.pdf",
    ) = AttachmentEntity(
        id = id, assetId = asset, eventId = null, kind = "DOCUMENT", mode = "MANAGED",
        displayName = name, mimeType = "application/pdf", sizeBytes = 12L, sha256 = "a".repeat(64),
        storageProvider = "SAF_TREE", storageLocator = "owners/$id.pdf", capturedOn = null, notes = "",
        createdAt = 1L, updatedAt = 1L, documentRole = null,
        sourceUri = null, sourceResolvedUri = null, sourceRetrievedAt = null, sourceName = null,
        supplyItemId = supply, installedComponentId = component,
    )

    private fun reference(
        id: String,
        asset: String? = null,
        supply: String? = null,
        component: String? = null,
        uri: String = MANUAL,
        name: String = "Manual",
    ) = AssetReferenceEntity(
        id = id, assetId = asset, kind = "WEB_URL", uri = uri, displayName = name, description = "",
        scheme = "https", createdAt = 10L, updatedAt = 20L, documentRole = null,
        supplyItemId = supply, installedComponentId = component,
    )

    private suspend fun refused(what: String, write: suspend () -> Unit) {
        try {
            write()
            fail("expected a UNIQUE violation on $what")
        } catch (e: Exception) {
            // the driver's message is not asserted, for the reason NfcTagDaoTest records
            assertTrue("unexpected exception: $e", e is SQLiteException)
        }
    }

    private companion object {
        const val MANUAL = "https://example.invalid/battery/manual"
        const val PARTS = "https://example.invalid/battery/parts"
    }
}
