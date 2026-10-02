package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.merge.MergeTable
import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.asAttachmentOwner
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.dataTreeOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #69 (B3c; C16b, rows 39–40; E1–E4 and E7 (E5 is rows 53–54, E6 rows 26 and 44), AC3, AC4, AC12, AC14) — the
 * generic complex-equipment scenario, end to end over the core doubles and the shipped use cases, fictional throughout.
 *
 * An "Example Generator" fits an "Example Alternator" whose direct link names one SupplyItem. An "Example UPS" fits an
 * "Example Battery Tray" whose four positions name one battery SupplyItem, and a pack made of `4 ×` that battery. Each
 * SupplyItem owns its product documents, its package photo and its web page, once; the alternator and the tray own
 * their installation, label and wiring photos and a setup link. A component, and its asset through the graph, reach a
 * SupplyItem's resources by navigation and never by a copy. A child Asset's file is an asset-owned row like any other.
 * An export, a replace import into an empty install and a merge into one keep every owner.
 */
class ResourceOwnersFixtureTest {

    private val fitting = FittingInstall()
    private val home get() = fitting.home
    private val raw get() = home.raw
    private val addFile = AddAttachment(
        raw.attachments, raw.assets, raw.events, raw.supplyItems, raw.installedComponents, raw.storage, raw.uow, home.ids, home.clock,
    )
    private val addLink = AddReference(
        raw.references, raw.assets, raw.supplyItems, raw.installedComponents, LinkLaunchPolicy(), raw.uow, home.ids, home.clock,
    )

    private class Estate(
        val generator: AssetId,
        val ups: AssetId,
        val panel: AssetId,
        val alternatorItem: SupplyItem,
        val battery: SupplyItem,
        val alternator: InstalledComponent,
        val tray: InstalledComponent,
        val positions: List<InstalledComponent>,
        val pack: InstalledComponent,
    )

    private suspend fun estate(): Estate {
        val generator = home.createAsset.run("Example Generator").id
        val ups = home.createAsset.run("Example UPS").id
        val panel = home.createAsset.run(AssetCommand(name = "Example Control Panel", parentAssetId = generator)).id
        val alternatorItem = product("Example AL-40 Alternator", "EP-AL40")
        val battery = product("Example 12 V Battery", "EP-B12-9")
        val alternator = fitting.fit(generator, "Example Alternator", supplyId = alternatorItem.id)
        val tray = fitting.fit(ups, "Example Battery Tray")
        val positions = (1..4).map { fitting.fit(ups, "Position $it", parentId = tray.id, supplyId = battery.id) }
        val pack = fitting.fit(ups, "Example Battery Pack", composition = listOf(madeOf("4", battery.id)))
        listOf(alternator, tray).forEach { installed(it) }
        file(AttachmentOwner.OfAsset(panel), "Example Control Panel front.jpg", AttachmentKind.PHOTO)
        return Estate(generator, ups, panel, alternatorItem, battery, alternator, tray, positions, pack)
    }

    /** A SupplyItem of "Example Power Co." with its own product resources, each added once through the use cases. */
    private suspend fun product(name: String, partNumber: String): SupplyItem {
        val item = home.saveSupplyItem.run(
            null,
            SupplyItemCommand(
                name = name, category = "", manufacturer = "Example Power Co.", model = "", partNumber = partNumber,
                preferredUnit = "ea", notes = "", specifications = emptyList(),
            ),
        ).item
        val owner = AttachmentOwner.OfSupplyItem(item.id)
        file(owner, "$name manual.pdf", AttachmentKind.MANUAL, DocumentRole.USER_MANUAL)
        file(owner, "$name data sheet.pdf", AttachmentKind.DOCUMENT)
        file(owner, "$name package.jpg", AttachmentKind.PHOTO)
        file(owner, "$name receipt.pdf", AttachmentKind.RECEIPT, DocumentRole.PURCHASE_INVOICE_OR_RECEIPT)
        link(ReferenceOwner.OfSupplyItem(item.id), "https://example.invalid/products/$partNumber", "Vendor — $name")
        return item
    }

    /** [component]'s own installation, label and wiring photos and its setup link. */
    private suspend fun installed(component: InstalledComponent) {
        val owner = AttachmentOwner.OfInstalledComponent(component.id)
        COMPONENT_FILES.forEach { (suffix, kind) -> file(owner, "${component.name} $suffix", kind) }
        link(
            ReferenceOwner.OfInstalledComponent(component.id),
            "https://example.invalid/setup/${component.id.value}", "Setup — ${component.name}",
        )
    }

    private suspend fun file(owner: AttachmentOwner, name: String, kind: AttachmentKind, role: DocumentRole? = null): Attachment {
        val mime = if (name.endsWith(".pdf")) "application/pdf" else "image/jpeg"
        val result = addFile.run(
            owner, AddAttachmentCommand(displayName = name, mimeType = mime, kind = kind, role = role),
            ByteSource { ByteArrayInputStream("bytes of $name".toByteArray()) },
        )
        check(result is AttachmentResult.Ok) { "not added: $result" }
        return result.value
    }

    private suspend fun link(owner: ReferenceOwner, uri: String, name: String): AssetReference {
        val result = addLink.run(owner, AddReferenceCommand(uri, name))
        check(result is ReferenceResult.Ok) { "not added: $result" }
        return result.value
    }

    private suspend fun files(owner: AttachmentOwner) = raw.attachments.forOwner(owner)
    private suspend fun links(owner: ReferenceOwner) = raw.references.forOwner(owner)
    private suspend fun supplyFiles(id: SupplyId) = files(AttachmentOwner.OfSupplyItem(id))
    private suspend fun supplyLinks(id: SupplyId) = links(ReferenceOwner.OfSupplyItem(id))
    private suspend fun ownFiles(c: InstalledComponent) = files(AttachmentOwner.OfInstalledComponent(c.id))
    private suspend fun ownLinks(c: InstalledComponent) = links(ReferenceOwner.OfInstalledComponent(c.id))

    /** The SupplyItems [this] names, as #47 stores them: its direct link, then its composition entries', each once. */
    private fun InstalledComponent.named(): List<SupplyId> = (listOfNotNull(supplyId) + composition.map { it.supplyId }).distinct()

    /** Rows, links and stored byte files: what a copy for visibility would grow. */
    private suspend fun counts() = Triple(raw.attachments.all().size, raw.references.all().size, raw.storage.store.files.size)

    @Test
    fun eachSupplyItemOwnsItsProductResourcesOnceAndEachComponentItsOwn() = runBlocking<Unit> {
        val e = estate()

        // E1: every SupplyItem owns its product files and its web page, each once, under its own directory.
        for (item in listOf(e.alternatorItem, e.battery)) {
            val own = supplyFiles(item.id)
            assertEquals(
                listOf(
                    Triple("${item.name} manual.pdf", AttachmentKind.MANUAL, DocumentRole.USER_MANUAL),
                    Triple("${item.name} data sheet.pdf", AttachmentKind.DOCUMENT, null),
                    Triple("${item.name} package.jpg", AttachmentKind.PHOTO, null),
                    Triple("${item.name} receipt.pdf", AttachmentKind.RECEIPT, DocumentRole.PURCHASE_INVOICE_OR_RECEIPT),
                ).sortedBy { it.first },
                own.map { Triple(it.displayName, it.kind, it.role) }.sortedBy { it.first },
            )
            assertTrue(own.all { it.storageLocator.startsWith("supply-items/${item.id.value}/") }, "$own")
            assertEquals(listOf("https://example.invalid/products/${item.partNumber}"), supplyLinks(item.id).map { it.uri })
        }

        // E2: the alternator and the tray own their installation material, with no role, under their own directory.
        for (component in listOf(e.alternator, e.tray)) {
            val own = ownFiles(component)
            assertEquals(
                COMPONENT_FILES.map { (suffix, kind) -> "${component.name} $suffix" to kind }.sortedBy { it.first },
                own.map { it.displayName to it.kind }.sortedBy { it.first },
            )
            assertTrue(own.all { it.role == null && it.storageLocator.startsWith("installed-components/${component.id.value}/") }, "$own")
            assertEquals(listOf("https://example.invalid/setup/${component.id.value}"), ownLinks(component).map { it.uri })
        }

        // AC12: by owner, the installation, label and wiring material is its component's and the product material its
        // SupplyItem's; neither asset owns a row, and the child Asset owns only its own file.
        val owners = raw.attachments.all().associate { it.displayName to it.owner }
        for (component in listOf(e.alternator, e.tray)) for ((suffix, _) in COMPONENT_FILES) {
            assertEquals(AttachmentOwner.OfInstalledComponent(component.id), owners.getValue("${component.name} $suffix"))
        }
        for (item in listOf(e.alternatorItem, e.battery)) for (suffix in listOf("manual.pdf", "data sheet.pdf", "package.jpg", "receipt.pdf")) {
            assertEquals(AttachmentOwner.OfSupplyItem(item.id), owners.getValue("${item.name} $suffix"))
        }
        assertEquals(
            mapOf(
                "https://example.invalid/products/EP-AL40" to ReferenceOwner.OfSupplyItem(e.alternatorItem.id),
                "https://example.invalid/products/EP-B12-9" to ReferenceOwner.OfSupplyItem(e.battery.id),
                "https://example.invalid/setup/${e.alternator.id.value}" to ReferenceOwner.OfInstalledComponent(e.alternator.id),
                "https://example.invalid/setup/${e.tray.id.value}" to ReferenceOwner.OfInstalledComponent(e.tray.id),
            ),
            raw.references.all().associate { it.uri to it.owner },
        )
        assertEquals(listOf("Example Control Panel front.jpg"), files(AttachmentOwner.OfAsset(e.panel)).map { it.displayName })
        for (asset in listOf(e.generator, e.ups)) {
            assertEquals(emptyList(), files(AttachmentOwner.OfAsset(asset)))
            assertEquals(emptyList(), links(ReferenceOwner.OfAsset(asset)))
        }
    }

    @Test
    fun componentsNamingOneSupplyItemReadTheSameRowsAndNothingIsCopied() = runBlocking<Unit> {
        val e = estate()
        val before = counts()
        assertEquals(Triple(15, 4, 15), before, "8 product files, 6 component files and the child Asset's file; 4 links; one byte file each")

        // AC4: the four positions (by direct link) and the pack (by a composition entry) all name the battery, and each
        // reads the same rows by the SupplyItem's owner; none holds a copy of its own.
        val batteryFiles = supplyFiles(e.battery.id)
        val batteryLinks = supplyLinks(e.battery.id)
        for (component in e.positions + e.pack) {
            assertEquals(listOf(e.battery.id), component.named(), component.name)
            assertEquals(batteryFiles, component.named().flatMap { supplyFiles(it) }, component.name)
            assertEquals(batteryLinks, component.named().flatMap { supplyLinks(it) }, component.name)
            assertEquals(emptyList(), ownFiles(component), component.name)
            assertEquals(emptyList(), ownLinks(component), component.name)
        }

        // E3, AC3: the alternator reaches both sets, each under its own owner, sharing no row, locator or byte.
        val own = ownFiles(e.alternator)
        val its = e.alternator.named().flatMap { supplyFiles(it) }
        assertTrue(own.all { it.owner == AttachmentOwner.OfInstalledComponent(e.alternator.id) }, "$own")
        assertTrue(its.all { it.owner == AttachmentOwner.OfSupplyItem(e.alternatorItem.id) }, "$its")
        val reached = own + its
        assertEquals(listOf(3, 4), listOf(own.size, its.size))
        assertEquals(
            List(3) { reached.size },
            listOf(reached.map { it.id }.toSet().size, reached.map { it.storageLocator }.toSet().size, reached.map { it.sha256 }.toSet().size),
        )
        assertEquals(
            listOf(ReferenceOwner.OfInstalledComponent(e.alternator.id), ReferenceOwner.OfSupplyItem(e.alternatorItem.id)),
            (ownLinks(e.alternator) + e.alternator.named().flatMap { supplyLinks(it) }).map { it.owner },
        )

        // I6 and limit 5: replacing the alternator creates, moves and deletes no resource. Its closed row keeps its own
        // rows, and its successor starts with none and reaches the same SupplyItem's rows.
        val ownLinksBefore = ownLinks(e.alternator)
        val swapped = fitting.swap(e.alternator.id, "2026-02-15", "Example Alternator", supplyId = e.alternatorItem.id)
        assertEquals("2026-02-15", swapped.replaced?.removedOn)
        assertEquals(own to ownLinksBefore, ownFiles(e.alternator) to ownLinks(e.alternator), "the closed row keeps its own")
        assertEquals(its, swapped.row.named().flatMap { supplyFiles(it) })
        assertEquals(emptyList(), ownFiles(swapped.row))
        assertEquals(emptyList(), ownLinks(swapped.row))
        assertEquals(before, counts())
        assertEquals(before.first, raw.attachments.all().map { it.storageLocator }.toSet().size)
    }

    @Test
    fun anAssetReachesItsComponentsAndTheirSupplyItemsResourcesThroughTheGraph() = runBlocking<Unit> {
        val e = estate()

        /** E4: asset → its components (current and removed) → the SupplyItems they name → each owner's own rows. */
        suspend fun reach(asset: AssetId): Pair<List<Attachment>, List<AssetReference>> {
            val rows = raw.installedComponents.forAsset(asset)
            val items = rows.flatMap { it.named() }.distinct()
            return (rows.flatMap { ownFiles(it) } + items.flatMap { supplyFiles(it) }) to
                (rows.flatMap { ownLinks(it) } + items.flatMap { supplyLinks(it) })
        }

        val (generatorFiles, generatorLinks) = reach(e.generator)
        assertEquals(ownFiles(e.alternator) + supplyFiles(e.alternatorItem.id), generatorFiles)
        assertEquals(ownLinks(e.alternator) + supplyLinks(e.alternatorItem.id), generatorLinks)
        val (upsFiles, upsLinks) = reach(e.ups)
        assertEquals(ownFiles(e.tray) + supplyFiles(e.battery.id), upsFiles, "the tray's own rows, then the battery's")
        assertEquals(ownLinks(e.tray) + supplyLinks(e.battery.id), upsLinks)

        // Every resource but the child Asset's file is reached from exactly one asset, and is the stored row itself.
        val stored = raw.attachments.all().filterNot { it.owner == AttachmentOwner.OfAsset(e.panel) }
        assertEquals(stored.sortedBy { it.id.value }, (generatorFiles + upsFiles).sortedBy { it.id.value })
        assertEquals(raw.references.all().sortedBy { it.id.value }, (generatorLinks + upsLinks).sortedBy { it.id.value })
    }

    @Test
    fun anExportRestoresIntoAnEmptyInstallAndReplansIdenticalWithEveryOwnerKept() = runBlocking<Unit> {
        estate()
        val bytes = raw.export.run().data
        val againstSource = raw.build.run(bytes)
        assertTrue(againstSource.decisions.all { it.verdict == MergeVerdict.IDENTICAL }, "${againstSource.decisions}")
        assertEquals(
            listOf(15, 4, 2, 7),
            listOf(MergeTable.ATTACHMENTS, MergeTable.REFERENCES, MergeTable.SUPPLY_ITEMS, MergeTable.INSTALLED_COMPONENTS)
                .map { table -> againstSource.decisions.count { it.table == table } },
        )

        val restored = BackupInstall("set-restored")
        restored.replace.run(bytes)
        assertEquals(raw.installedComponents.all(), restored.installedComponents.all(), "asset → component → SupplyItem")
        assertEquals(raw.supplyItems.all(), restored.supplyItems.all())
        assertSameResourcesByOwner(raw, restored)

        val replan = restored.build.run(bytes)
        assertTrue(replan.decisions.all { it.verdict == MergeVerdict.IDENTICAL }, "${replan.decisions}")
        val written = dataTreeOf(bytes).filterValues { it is JsonArray }
        val again = dataTreeOf(restored.export.run().data).filterValues { it is JsonArray }
        assertEquals(written.keys, again.keys)
        for (list in written.keys) assertEquals(written.getValue(list).toString(), again.getValue(list).toString(), list)
    }

    @Test
    fun aMergeIntoAnEmptyInstallInsertsEveryResourceAfterItsOwner() = runBlocking<Unit> {
        estate()
        val bytes = raw.export.run().data
        val target = BackupInstall("set-target")
        // The archive carries rows; the folder carries bytes, and the plan verifies every file's size and hash there.
        target.storage.store.files.putAll(raw.storage.store.files)

        val plan = target.build.run(bytes)
        assertTrue(plan.applicable, "${plan.report()}")
        for ((table, rows) in listOf(MergeTable.SUPPLY_ITEMS to 2, MergeTable.INSTALLED_COMPONENTS to 7, MergeTable.ATTACHMENTS to 15, MergeTable.REFERENCES to 4)) {
            assertEquals(List(rows) { MergeVerdict.INSERT }, plan.decisions.filter { it.table == table }.map { it.verdict }, "$table")
        }
        ownersFirst(target).run(plan)

        assertEquals(raw.installedComponents.all(), target.installedComponents.all())
        assertSameResourcesByOwner(raw, target)
        assertEquals(raw.storage.store.files.keys, target.storage.store.files.keys, "no byte file was added")
        assertTrue(target.build.run(bytes).decisions.all { it.verdict == MergeVerdict.IDENTICAL })
    }

    @Test
    fun aChildAssetsFileIsAnAssetOwnedRow() = runBlocking<Unit> {
        val e = estate()
        assertEquals(e.generator, raw.assets.get(e.panel)?.parentAssetId)

        // AC14: the child Asset's file is an ordinary asset-owned row; its parent gains no copy and no other owner names it.
        val row = files(AttachmentOwner.OfAsset(e.panel)).single()
        assertTrue(row.storageLocator.startsWith("assets/${e.panel.value}/"), row.storageLocator)
        assertEquals(emptyList(), files(AttachmentOwner.OfAsset(e.generator)))
        assertEquals(listOf(row), raw.attachments.all().filter { it.id == row.id })

        // On the wire it names its asset and writes both new owner keys as explicit nulls.
        val dto = dataTreeOf(raw.export.run().data).getValue("attachments").jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content == row.id.value }.jsonObject
        assertEquals(
            listOf(e.panel.value, null, null, null),
            listOf("assetId", "eventId", "supplyItemId", "installedComponentId").map { dto.getValue(it).jsonPrimitive.contentOrNull },
        )
    }

    /** Every owner [source] holds reads the same files and links in [other], row for row (files by id: the double keeps write order). */
    private suspend fun assertSameResourcesByOwner(source: BackupInstall, other: BackupInstall) {
        assertEquals(source.attachments.all().sortedBy { it.id.value }, other.attachments.all().sortedBy { it.id.value })
        assertEquals(source.references.all().sortedBy { it.id.value }, other.references.all().sortedBy { it.id.value })
        for (owner in source.attachments.all().map { it.owner }.distinct()) {
            assertEquals(
                source.attachments.forOwner(owner).sortedBy { it.id.value }, other.attachments.forOwner(owner).sortedBy { it.id.value }, "$owner",
            )
        }
        for (owner in source.references.all().map { it.owner }.distinct()) {
            assertEquals(source.references.forOwner(owner), other.references.forOwner(owner), "$owner")
        }
    }

    /**
     * [t]'s merge apply, each file and link write first asking whether its owner is already in: the order the schema's
     * foreign keys hold Room to, observed over the doubles.
     */
    private fun ownersFirst(t: BackupInstall): ApplyBackupMergePlan {
        suspend fun present(owner: AttachmentOwner): Boolean = when (owner) {
            is AttachmentOwner.OfAsset -> t.assets.get(owner.assetId) != null
            is AttachmentOwner.OfEvent -> t.events.get(owner.eventId) != null
            is AttachmentOwner.OfSupplyItem -> t.supplyItems.get(owner.supplyId) != null
            is AttachmentOwner.OfInstalledComponent -> t.installedComponents.get(owner.componentId) != null
        }
        val files = object : AttachmentRepository by t.attachments {
            override suspend fun upsert(a: Attachment) {
                check(present(a.owner)) { "file ${a.id.value} written before its owner ${a.owner}" }
                t.attachments.upsert(a)
            }
        }
        val links = object : ReferenceRepository by t.references {
            override suspend fun upsert(reference: AssetReference) {
                check(present(reference.owner.asAttachmentOwner())) { "link ${reference.id.value} written before its owner ${reference.owner}" }
                t.references.upsert(reference)
            }
        }
        return ApplyBackupMergePlan(
            t.assets, t.groups, t.tags, t.links, t.definitions, t.profiles, t.schedules, t.closures, t.events, files,
            links, t.activations, t.conditions, t.subjects, t.categories, t.serviceCases, t.caseEntries, t.loans,
            t.transfers, t.successions, t.supplyItems, t.assetSupplies, t.installedComponents, t.storage, t.uow,
            rebuildAll = {},
        )
    }

    private companion object {
        /** A component's own material, in the order it is added. */
        val COMPONENT_FILES = listOf(
            "installed.jpg" to AttachmentKind.PHOTO,
            "label.jpg" to AttachmentKind.LABEL_PHOTO,
            "wiring.jpg" to AttachmentKind.PHOTO,
        )
    }
}
