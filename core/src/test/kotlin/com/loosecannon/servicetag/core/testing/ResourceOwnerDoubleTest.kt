package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #69 (C11, row 20; N-16) — the core doubles carry the schema's two-level CASCADE for a component's files: an asset
 * delete through [BackupInstall] takes the asset's installed components (#47's registration), and the component
 * double hands the ids it removed to the attachment double, which drops the rows those components own and nothing
 * else. An asset's own and an entry's files deliberately do not cascade in the double (the Room tests prove that
 * half), so no shipped assertion moves. Every name is fictional.
 */
class ResourceOwnerDoubleTest {

    private val install = BackupInstall()

    private val heater = AssetId("h1")
    private val compressor = AssetId("x1")

    /**
     * Two assets; on the heater a tray (c1) holding a removed position (c2), on the compressor a housing (cx); one
     * SupplyItem (s1). A file on each component, one on the SupplyItem, one on the heater itself and one on its entry.
     */
    private suspend fun seed() {
        install.assets.upsert(plainAssetOf("h1", "Example Water Heater"))
        install.assets.upsert(plainAssetOf("x1", "Example Compressor"))
        install.supplyItems.upsert(supplyItemOf("s1", "Example 12 V Battery"))
        install.installedComponents.insert(installedComponentOf("c1", assetId = "h1", name = "Example Battery Tray"))
        install.installedComponents.insert(
            installedComponentOf("c2", assetId = "h1", parentId = "c1", name = "Position 1", supplyId = "s1", removedOn = "2026-09-20"),
        )
        install.installedComponents.insert(installedComponentOf("cx", assetId = "x1", name = "Example Intake Housing"))
        install.events.upsert(completionOf("e1", "2026-02-01", null, assetId = "h1").copy(scheduleId = null))
        listOf(
            fileOf("f1", AttachmentOwner.OfInstalledComponent(InstalledComponentId("c1"))),
            fileOf("f2", AttachmentOwner.OfInstalledComponent(InstalledComponentId("c2"))),
            fileOf("fx", AttachmentOwner.OfInstalledComponent(InstalledComponentId("cx"))),
            fileOf("fs", AttachmentOwner.OfSupplyItem(SupplyId("s1"))),
            fileOf("fa", AttachmentOwner.OfAsset(heater)),
            fileOf("fe", AttachmentOwner.OfEvent(EventId("e1"))),
        ).forEach { install.attachments.upsert(it) }
    }

    @Test
    fun anAssetDeleteThroughBackupInstallTakesItsComponentsFiles() = runTest {
        seed()

        install.assets.delete(heater)

        assertEquals(listOf("cx"), install.installedComponents.all().map { it.id.value }, "the components went with the asset")
        assertEquals(
            setOf("fx", "fs", "fa", "fe"),
            install.attachments.rows.keys,
            "the files of c1 and c2, the removed one included, went with them; the compressor's component keeps its own",
        )

        install.assets.deleteAll()

        assertEquals(setOf("fs", "fa", "fe"), install.attachments.rows.keys, "the wipe takes the last component's file too")
    }

    @Test
    fun assetOwnedRowsDoNotCascadeInTheDouble() = runTest {
        seed()
        val own = install.attachments.forOwner(AttachmentOwner.OfAsset(heater))
        val entry = install.attachments.forOwner(AttachmentOwner.OfEvent(EventId("e1")))
        val item = install.attachments.forOwner(AttachmentOwner.OfSupplyItem(SupplyId("s1")))

        install.assets.delete(heater)
        install.assets.delete(compressor)

        assertEquals(own, install.attachments.forOwner(AttachmentOwner.OfAsset(heater)), "the asset's own file stays, as before")
        assertEquals(entry, install.attachments.forOwner(AttachmentOwner.OfEvent(EventId("e1"))), "the entry's file stays, as before")
        assertEquals(item, install.attachments.forOwner(AttachmentOwner.OfSupplyItem(SupplyId("s1"))), "a SupplyItem's file is no asset's")
    }

    // --- B2b (C12): the reference twin ------------------------------------------------------------------------

    /** The links' twin of the first case: a component's links go with it, removed one included; no other owner's do. */
    @Test
    fun anAssetDeleteThroughBackupInstallTakesItsComponentsLinks() = runTest {
        seed()
        listOf(
            linkOf("l1", ReferenceOwner.OfInstalledComponent(InstalledComponentId("c1"))),
            linkOf("l2", ReferenceOwner.OfInstalledComponent(InstalledComponentId("c2"))),
            linkOf("lx", ReferenceOwner.OfInstalledComponent(InstalledComponentId("cx"))),
            linkOf("ls", ReferenceOwner.OfSupplyItem(SupplyId("s1"))),
            linkOf("la", ReferenceOwner.OfAsset(heater)),
        ).forEach { install.references.upsert(it) }

        install.assets.delete(heater)

        assertEquals(
            setOf("lx", "ls", "la"),
            install.references.rows.keys,
            "the links of c1 and c2 went with them; the asset's own stays in the double, as its files do",
        )
    }

    /** I3 in the double: one row per `(owner, uri)` — the same URI on three owners sharing an id string is three rows. */
    @Test
    fun aSecondOwnerUriPairThrows() = runTest {
        val owners = listOf(
            ReferenceOwner.OfAsset(AssetId("x1")),
            ReferenceOwner.OfSupplyItem(SupplyId("x1")),
            ReferenceOwner.OfInstalledComponent(InstalledComponentId("x1")),
        )
        owners.forEachIndexed { i, owner -> install.references.upsert(linkOf("l$i", owner)) }

        for ((i, owner) in owners.withIndex()) {
            assertFailsWith<IllegalStateException>("$owner") { install.references.upsert(linkOf("again$i", owner)) }
        }
        assertEquals(setOf("l0", "l1", "l2"), install.references.rows.keys)
    }

    private fun linkOf(id: String, owner: ReferenceOwner) = AssetReference(
        id = ReferenceId(id), owner = owner, kind = ReferenceKind.WEB_URL, uri = "https://example.invalid/sheet",
        displayName = "$id link", description = "", scheme = "https", createdAt = 100L, updatedAt = 100L,
    )

    private fun fileOf(id: String, owner: AttachmentOwner) = Attachment(
        id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT, displayName = "$id file",
        mimeType = "application/pdf", sizeBytes = 4L, sha256 = "ab".repeat(32),
        storageLocator = "${AttachmentLocator.dirFor(owner)}/$id.pdf", capturedOn = null, createdAt = 100L, updatedAt = 100L,
    )
}
