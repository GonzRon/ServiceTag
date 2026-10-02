package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetReferenceDto
import com.loosecannon.servicetag.core.backup.AttachmentDto
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.ports.ByteSource
import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddAttachmentCommand
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.InMemoryAttachmentStore
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOKEN = "ABCD2345"

/** G1, the create's ratified 400 (C20). */
private const val G1 = "a reference names exactly one owner: assetId, supplyItemId or installedComponentId"

/**
 * #69 (B4; C19–C21; rows 41–43) — a SupplyItem's and an installed component's own links and files over the production
 * router, the production handlers and the Room-backed [FakeGraph]: the six new rows, the create's owner rule, the held
 * rule and the upload's derived id. The rules are the use cases'; what is proved here is the wire.
 *
 * Fixtures are fictional: "Example UPS", "Example 12 V Battery", "Example Battery Tray", every URL under
 * `https://example.invalid/…`.
 */
class ResourceOwnerRoutesTest {

    private val graph = FakeGraph()
    private val api = V1Client(graph, TOKEN)
    private val bytes = "Example 12 V Battery — data sheet, page 1 of 1".toByteArray()

    /** The production create, built as `referenceHandlersFor` builds it, so a seed is exactly a phone-side add. */
    private val addReference = AddReference(
        graph.references, graph.assets, graph.supplyItems, graph.installedComponents, LinkLaunchPolicy(), graph.uow,
        graph.ids, graph.clock,
    )

    @After fun close() = graph.close()

    // --- fixtures ---------------------------------------------------------------------------------------------------

    private fun call(method: String, path: String, body: String = ""): ApiResponse = api.call(method, path, body)

    private fun supply(name: String): String = api.ok(
        SupplyItemResponse.serializer(), "POST", "/v1/supply-items",
        """{"name":"$name","manufacturer":"Example Power Co."}""", status = 201,
    ).supplyItem.id

    /** One installed component on [assetId], linked to the SupplyItem [supplyId]. */
    private fun component(assetId: String, name: String, supplyId: String): String = api.ok(
        InstalledComponentResponse.serializer(), "POST", "/v1/installed-components",
        """{"assetId":"$assetId","name":"$name","supplyId":"$supplyId"}""", status = 201,
    ).installedComponent.id

    private fun holdAsset(assetId: String) = runBlocking {
        graph.transferRecords.append(
            TransferRecord(
                id = "out-1", assetId = AssetId(assetId), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example UPS", note = "",
            ),
        )
    }

    /** A link written by the use case, as the phone writes one. */
    private fun seedLink(owner: ReferenceOwner, uri: String, name: String): String = runBlocking {
        when (val saved = addReference.run(owner, AddReferenceCommand(uri = uri, displayName = name))) {
            is ReferenceResult.Ok -> saved.value.id.value
            is ReferenceResult.Refused -> error("the seed was refused: ${saved.problem}")
        }
    }

    /** A file written by the use case, as the phone writes one. */
    private fun seedFile(owner: AttachmentOwner, name: String): Attachment = runBlocking {
        val command = AddAttachmentCommand(displayName = name, mimeType = "application/pdf")
        when (val saved = graph.addAttachment.run(owner, command, ByteSource { bytes.inputStream() })) {
            is AttachmentResult.Ok -> saved.value
            is AttachmentResult.Refused -> error("the seed was refused: ${saved.problem}")
        }
    }

    private fun links(path: String): List<AssetReferenceDto> {
        val response = call("GET", path)
        assertEquals(response.bodyText(), 200, response.status)
        return ApiJson.decodeFromString(ReferenceListResponse.serializer(), response.bodyText()).references
    }

    private fun files(path: String): AttachmentListResponse {
        val response = call("GET", path)
        assertEquals(response.bodyText(), 200, response.status)
        return ApiJson.decodeFromString(AttachmentListResponse.serializer(), response.bodyText())
    }

    private fun create(body: String): ApiResponse = call("POST", "/v1/references", body)

    private fun createdLink(response: ApiResponse): AssetReferenceDto {
        assertEquals(response.bodyText(), 201, response.status)
        return ApiJson.decodeFromString(ReferenceResponse.serializer(), response.bodyText()).reference
    }

    private fun assertRefused(response: ApiResponse, status: Int, code: String, field: String?, message: String? = null) {
        assertEquals(response.bodyText(), status, response.status)
        val error = response.errorDetail()
        assertEquals(response.bodyText(), code, error.code)
        assertEquals(response.bodyText(), field, error.field)
        if (message != null) assertEquals(message, error.message)
    }

    private fun metadata(key: String = "op-1", name: String = "Example 12 V Battery data sheet", role: String? = null) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            buildString {
                append("""{"operationKey":"$key","displayName":"$name","sha256":"${InMemoryAttachmentStore.sha256Hex(bytes)}"""")
                if (role != null) append(""","role":"$role"""")
                append("}")
            }.toByteArray(),
        )

    private fun upload(path: String, metadata: String = metadata()): ApiResponse = api.router().handle(
        ApiRequest(
            "POST", path,
            mapOf(
                "host" to "127.0.0.1", "authorization" to "Bearer $TOKEN", "content-length" to bytes.size.toString(),
                "content-type" to "application/pdf", UPLOAD_METADATA_HEADER to metadata,
            ),
            ByteArray(0), bytes.inputStream(),
        ),
    )

    private fun uploaded(response: ApiResponse, status: Int = 201): AttachmentDto {
        assertEquals(response.bodyText(), status, response.status)
        return ApiJson.decodeFromString(AttachmentResponse.serializer(), response.bodyText()).attachment
    }

    private class World(val ups: String, val battery: String, val tray: String)

    /** An asset, a SupplyItem, and one installed component on the asset that names the SupplyItem. */
    private fun world(): World {
        val ups = api.asset("Example UPS")
        val battery = supply("Example 12 V Battery")
        return World(ups, battery, component(ups, "Example Battery Tray", battery))
    }

    /** The shipped attachment order: oldest first, then by id. */
    private val oldestFirst = compareBy<Attachment>({ it.createdAt }, { it.id.value })

    // --- row 41: C19 the routes -------------------------------------------------------------------------------------

    @Test fun eachGetListsOnlyThatOwnersRowsInTheShippedOrder() {
        val w = world()
        val upsOwner = ReferenceOwner.OfAsset(AssetId(w.ups))
        val batteryOwner = ReferenceOwner.OfSupplyItem(SupplyId(w.battery))
        val trayOwner = ReferenceOwner.OfInstalledComponent(InstalledComponentId(w.tray))
        val shared = "https://example.invalid/battery/data-sheet"
        seedLink(upsOwner, "https://example.invalid/ups/manual", "Example UPS manual")
        seedLink(batteryOwner, shared, "Zeta data sheet")
        seedLink(batteryOwner, "https://example.invalid/battery/vendor", "Alpha vendor page")
        seedLink(trayOwner, shared, "Example Battery Tray wiring")
        val upsFile = seedFile(AttachmentOwner.OfAsset(AssetId(w.ups)), "Example UPS label photo")
        val batteryFiles = listOf(
            seedFile(AttachmentOwner.OfSupplyItem(SupplyId(w.battery)), "Example 12 V Battery data sheet"),
            seedFile(AttachmentOwner.OfSupplyItem(SupplyId(w.battery)), "Example 12 V Battery package photo"),
        )
        val trayFile = seedFile(AttachmentOwner.OfInstalledComponent(InstalledComponentId(w.tray)), "Tray photo")

        val batteryLinks = links("/v1/supply-items/${w.battery}/references")
        assertEquals(listOf("Alpha vendor page", "Zeta data sheet"), batteryLinks.map { it.displayName })
        for (row in batteryLinks) {
            assertEquals(w.battery, row.supplyItemId)
            assertNull(row.assetId)
            assertNull(row.installedComponentId)
        }
        val trayLinks = links("/v1/installed-components/${w.tray}/references")
        assertEquals(listOf("Example Battery Tray wiring"), trayLinks.map { it.displayName })
        assertEquals(w.tray, trayLinks.single().installedComponentId)
        assertNull(trayLinks.single().assetId)
        assertNull(trayLinks.single().supplyItemId)
        val upsLinks = links("/v1/assets/${w.ups}/references")
        assertEquals(listOf("Example UPS manual"), upsLinks.map { it.displayName })
        assertEquals(w.ups, upsLinks.single().assetId)

        val batteryList = files("/v1/supply-items/${w.battery}/attachments")
        assertEquals(AttachmentFolder.READY, batteryList.folder)
        assertEquals(
            batteryFiles.sortedWith(oldestFirst).map { it.id.value },
            batteryList.attachments.map { it.id },
        )
        for (row in batteryList.attachments) {
            assertEquals(w.battery, row.supplyItemId)
            assertNull(row.assetId)
            assertTrue(row.storageLocator, row.storageLocator.startsWith("supply-items/${w.battery}/"))
        }
        assertEquals(listOf(trayFile.id.value), files("/v1/installed-components/${w.tray}/attachments").attachments.map { it.id })
        assertEquals(listOf(upsFile.id.value), files("/v1/assets/${w.ups}/attachments").attachments.map { it.id })
    }

    @Test fun aMissingSupplyItemOrComponentIs404() {
        world()
        for (path in listOf("/v1/supply-items/nope/references", "/v1/supply-items/nope/attachments")) {
            assertRefused(call("GET", path), 404, NO_SUCH_SUPPLY_ITEM, null)
        }
        assertRefused(upload("/v1/supply-items/nope/attachments"), 404, NO_SUCH_SUPPLY_ITEM, null)
        for (path in listOf("/v1/installed-components/nope/references", "/v1/installed-components/nope/attachments")) {
            assertRefused(call("GET", path), 404, "NO_SUCH_INSTALLED_COMPONENT", null)
        }
        assertRefused(upload("/v1/installed-components/nope/attachments"), 404, "NO_SUCH_INSTALLED_COMPONENT", null)
        assertTrue(runBlocking { graph.attachments.all() }.isEmpty())
    }

    @Test fun aWrongVerbIs405AndAnArchivedItemOrRemovedComponentStillLists() {
        val w = world()
        for ((method, path) in listOf(
            "POST" to "/v1/supply-items/${w.battery}/references",
            "PATCH" to "/v1/supply-items/${w.battery}/attachments",
            "DELETE" to "/v1/supply-items/${w.battery}/attachments",
            "POST" to "/v1/installed-components/${w.tray}/references",
            "PATCH" to "/v1/installed-components/${w.tray}/attachments",
            "DELETE" to "/v1/installed-components/${w.tray}/references",
        )) {
            assertRefused(call(method, path, if (method == "DELETE") "" else "{}"), 405, "method_not_allowed", null)
        }
        seedLink(ReferenceOwner.OfSupplyItem(SupplyId(w.battery)), "https://example.invalid/battery/a", "Battery link")
        seedLink(ReferenceOwner.OfInstalledComponent(InstalledComponentId(w.tray)), "https://example.invalid/tray/a", "Tray link")
        assertEquals(200, call("POST", "/v1/supply-items/${w.battery}/archive", """{"archived":true}""").status)
        assertEquals(200, call("POST", "/v1/installed-components/${w.tray}/remove", """{"removedOn":"2026-02-10"}""").status)
        assertEquals(listOf("Battery link"), links("/v1/supply-items/${w.battery}/references").map { it.displayName })
        assertEquals(listOf("Tray link"), links("/v1/installed-components/${w.tray}/references").map { it.displayName })
        assertEquals(201, upload("/v1/supply-items/${w.battery}/attachments").status)
        assertEquals(201, upload("/v1/installed-components/${w.tray}/attachments").status)
    }

    @Test fun aHeldAssetsComponentReadsAsAnyOther() {
        val w = world()
        seedLink(ReferenceOwner.OfInstalledComponent(InstalledComponentId(w.tray)), "https://example.invalid/tray/a", "Tray link")
        val file = seedFile(AttachmentOwner.OfInstalledComponent(InstalledComponentId(w.tray)), "Tray photo")
        holdAsset(w.ups)
        assertEquals(listOf("Tray link"), links("/v1/installed-components/${w.tray}/references").map { it.displayName })
        assertEquals(listOf(file.id.value), files("/v1/installed-components/${w.tray}/attachments").attachments.map { it.id })
    }

    @Test fun theAssetSubResourcesAreUnchanged() {
        val w = world()
        seedLink(ReferenceOwner.OfAsset(AssetId(w.ups)), "https://example.invalid/ups/manual", "Example UPS manual")
        seedLink(ReferenceOwner.OfSupplyItem(SupplyId(w.battery)), "https://example.invalid/ups/manual", "Battery link")
        seedFile(AttachmentOwner.OfAsset(AssetId(w.ups)), "Example UPS label photo")
        seedFile(AttachmentOwner.OfInstalledComponent(InstalledComponentId(w.tray)), "Tray photo")
        val owner = ReferenceOwner.OfAsset(AssetId(w.ups))

        val references = call("GET", "/v1/assets/${w.ups}/references")
        val expectedLinks = runBlocking { graph.references.forOwner(owner) }.map { it.toDto() }
        assertEquals(
            ApiJson.encodeToString(ReferenceListResponse.serializer(), ReferenceListResponse(expectedLinks)),
            references.bodyText(),
        )
        val attachments = call("GET", "/v1/assets/${w.ups}/attachments")
        val expectedFiles = runBlocking { graph.attachments.forAsset(AssetId(w.ups)) }
            .sortedWith(oldestFirst).map { it.toDto() }
        assertEquals(
            ApiJson.encodeToString(
                AttachmentListResponse.serializer(), AttachmentListResponse(expectedFiles, AttachmentFolder.READY),
            ),
            attachments.bodyText(),
        )
        // The shipped 404s: an unknown asset, and a verb an asset sub-resource does not take.
        assertRefused(call("GET", "/v1/assets/nope/references"), 404, "no_such_asset", null)
        assertRefused(call("GET", "/v1/assets/nope/attachments"), 404, "no_such_asset", null)
        assertRefused(upload("/v1/assets/nope/attachments"), 404, "no_such_asset", null)
        assertRefused(call("PATCH", "/v1/assets/${w.ups}/references", "{}"), 404, "not_found", null)
    }

    // --- row 42: C20 the create -------------------------------------------------------------------------------------

    @Test fun exactlyOneOwnerKey() {
        val w = world()
        val rest = """"uri":"https://example.invalid/battery/vendor","displayName":"Vendor — Example Power Co.""""
        for (body in listOf(
            """{$rest}""",
            """{"assetId":null,$rest}""",
            """{"supplyItemId":null,"installedComponentId":null,$rest}""",
            """{"assetId":"${w.ups}","supplyItemId":"${w.battery}",$rest}""",
            """{"supplyItemId":"${w.battery}","installedComponentId":"${w.tray}",$rest}""",
            """{"assetId":"${w.ups}","supplyItemId":"${w.battery}","installedComponentId":"${w.tray}",$rest}""",
        )) {
            assertRefused(create(body), 400, "bad_request", null, G1)
        }
        // A lone null beside one named owner is still exactly one owner.
        val onSupply = createdLink(create("""{"assetId":null,"supplyItemId":"${w.battery}",$rest}"""))
        assertEquals(w.battery, onSupply.supplyItemId)
        assertNull(onSupply.assetId)
        assertNull(onSupply.installedComponentId)
        val onComponent = createdLink(create("""{"installedComponentId":"${w.tray}",$rest}"""))
        assertEquals(w.tray, onComponent.installedComponentId)
        assertNull(onComponent.assetId)
        assertNull(onComponent.supplyItemId)
        val onAsset = createdLink(create("""{"assetId":"${w.ups}",$rest}"""))
        assertEquals(w.ups, onAsset.assetId)
        assertNull(onAsset.supplyItemId)
        assertNull(onAsset.installedComponentId)
        assertEquals(3, runBlocking { graph.references.all() }.size)
        // No key names a preferred link (R69-2): it is the decoder's 400, as any unknown key.
        assertRefused(create("""{"supplyItemId":"${w.battery}","preferred":true,$rest}"""), 400, "bad_request", null)
    }

    @Test fun anUnknownOwnerIs404WithItsCodeAndField() {
        world()
        val rest = """"uri":"https://example.invalid/x","displayName":"Example link""""
        assertRefused(create("""{"assetId":"nope",$rest}"""), 404, "no_such_asset", null, "no such asset")
        assertRefused(
            create("""{"supplyItemId":"nope",$rest}"""), 404, NO_SUCH_SUPPLY_ITEM, "supplyItemId", "no such supply item",
        )
        assertRefused(
            create("""{"installedComponentId":"nope",$rest}"""), 404, "NO_SUCH_INSTALLED_COMPONENT",
            "installedComponentId", "no such installed component",
        )
        assertTrue(runBlocking { graph.references.all() }.isEmpty())
    }

    @Test fun aDuplicateIsPerOwner() {
        val w = world()
        val uri = "https://example.invalid/battery/data-sheet"
        val rest = """"uri":"$uri","displayName":"Example 12 V Battery data sheet""""
        createdLink(create("""{"assetId":"${w.ups}",$rest}"""))
        createdLink(create("""{"supplyItemId":"${w.battery}",$rest}"""))
        createdLink(create("""{"installedComponentId":"${w.tray}",$rest}"""))
        for (body in listOf(
            """{"assetId":"${w.ups}",$rest}""",
            """{"supplyItemId":"${w.battery}",$rest}""",
            """{"installedComponentId":"${w.tray}",$rest}""",
        )) {
            assertRefused(create(body), 409, "REFERENCE_URI_TAKEN", null)
        }
        assertEquals(3, runBlocking { graph.references.all() }.size)
    }

    @Test fun aPatchNamingAnOwnerKeyIs400() {
        val w = world()
        val id = seedLink(ReferenceOwner.OfSupplyItem(SupplyId(w.battery)), "https://example.invalid/battery/a", "Battery link")
        val before = runBlocking { graph.references.get(ReferenceId(id)) }
        for (key in listOf("assetId", "supplyItemId", "installedComponentId")) {
            assertRefused(call("PATCH", "/v1/references/$id", """{"$key":"${w.ups}"}"""), 400, "bad_request", null)
        }
        assertEquals(before, runBlocking { graph.references.get(ReferenceId(id)) })
    }

    // --- row 43: C21 the upload -------------------------------------------------------------------------------------

    @Test fun uploadToASupplyItemAndAComponent() {
        val w = world()
        val onSupply = uploaded(upload("/v1/supply-items/${w.battery}/attachments", metadata(role = "USER_MANUAL")))
        assertEquals(w.battery, onSupply.supplyItemId)
        assertNull(onSupply.assetId)
        assertEquals("USER_MANUAL", onSupply.role)
        assertTrue(onSupply.storageLocator, onSupply.storageLocator.startsWith("supply-items/${w.battery}/"))
        assertTrue(bytes.contentEquals(graph.attachmentStorage.store.files.getValue(onSupply.storageLocator)))

        val onComponent = uploaded(upload("/v1/installed-components/${w.tray}/attachments"))
        assertEquals(w.tray, onComponent.installedComponentId)
        assertNull(onComponent.assetId)
        assertTrue(
            onComponent.storageLocator,
            onComponent.storageLocator.startsWith("installed-components/${w.tray}/"),
        )
        // One operation key on two owners is two rows; a retry on one owner is that owner's row, written once.
        assertTrue(onSupply.id != onComponent.id)
        val commits = graph.commits
        assertEquals(onComponent, uploaded(upload("/v1/installed-components/${w.tray}/attachments"), status = 200))
        assertEquals(commits, graph.commits)
        assertEquals(2, runBlocking { graph.attachments.all() }.size)
    }

    @Test fun aHeldAssetsComponentIs409() {
        val w = world()
        holdAsset(w.ups)
        val refused = upload("/v1/installed-components/${w.tray}/attachments")
        assertRefused(refused, 409, "asset_transferred_out", null)
        assertEquals(listOf("AssetTransferredOut(assetId=${w.ups})"), refused.errorDetail().problems)
        assertTrue(runBlocking { graph.attachments.all() }.isEmpty())
        // A SupplyItem is never held, even while the asset whose component names it is.
        assertEquals(201, upload("/v1/supply-items/${w.battery}/attachments").status)
    }
}
