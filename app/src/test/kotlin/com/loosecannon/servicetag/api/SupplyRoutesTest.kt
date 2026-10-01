package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetSupplyDto
import com.loosecannon.servicetag.core.backup.SupplyItemDto
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.usecase.AssetSupplyProblem
import com.loosecannon.servicetag.core.usecase.NoSuchSupplyItem
import com.loosecannon.servicetag.core.usecase.SupplyItemProblem
import com.loosecannon.servicetag.core.usecase.SupplyItemValidation
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #15 (B5; C2, C3, C22–C25; rows 46–53) — the SupplyItem and applicability routes over the production router, the
 * production handlers and the Room-backed [FakeGraph]: create and read, the PATCH overlay and its clearing rule,
 * archive, every new code (each read off a live route, and the mappers called directly), the asset sub-resource,
 * the line refusal under the shipped families, the two status counts, and `docs/api/v1.md` agreeing with all of it.
 *
 * Fixtures are fictional: "Example RO System", "Example Prefilter Cartridge", "Example Filters Co.".
 */
class SupplyRoutesTest {

    private val graph = FakeGraph()
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private fun call(method: String, path: String, body: String = ""): ApiResponse = api.call(method, path, body)

    private fun ApiResponse.text(): String = bodyText()

    private fun ApiResponse.error(): ApiErrorDetail = errorDetail()

    private fun item(response: ApiResponse): SupplyItemDto =
        ApiJson.decodeFromString(SupplyItemResponse.serializer(), response.text()).supplyItem

    private fun create(body: String): SupplyItemDto {
        val response = call("POST", "/v1/supply-items", body)
        assertEquals(response.text(), 201, response.status)
        return item(response)
    }

    private fun createNamed(name: String): SupplyItemDto = create("""{"name":"$name"}""")

    /** A full item: every text field and two specifications, one with a typed key. */
    private fun cartridge(): SupplyItemDto = create(
        """{"name":"Example Prefilter Cartridge","category":"Filters","manufacturer":"Example Filters Co.",""" +
            """"model":"PF-10","partNumber":"EX-PF-0010","preferredUnit":"ea","notes":"Sediment stage",""" +
            """"specifications":[{"label":"Micron rating","value":"5","unit":"µm"},""" +
            """{"key":"len","label":"Length","value":"10","unit":"in"}]}""",
    )

    private fun patch(id: String, body: String): ApiResponse = call("PATCH", "/v1/supply-items/$id", body)

    private fun patched(id: String, body: String): SupplyItemDto {
        val response = patch(id, body)
        assertEquals(response.text(), 200, response.status)
        return item(response)
    }

    private fun stored(id: String) = runBlocking { graph.supplyItems.get(SupplyId(id)) }

    private fun list(): List<SupplyItemDto> =
        api.ok(SupplyItemListResponse.serializer(), "GET", "/v1/supply-items").supplyItems

    private fun addRow(assetId: String, supplyId: String, role: String): ApiResponse = call(
        "POST", "/v1/asset-supplies", """{"assetId":"$assetId","supplyId":"$supplyId","role":"$role"}""",
    )

    private fun row(response: ApiResponse): AssetSupplyDto {
        assertTrue(response.text(), response.status in setOf(200, 201))
        return ApiJson.decodeFromString(AssetSupplyResponse.serializer(), response.text()).assetSupply
    }

    private fun archive(id: String, archived: Boolean): ApiResponse =
        call("POST", "/v1/supply-items/$id/archive", """{"archived":$archived}""")

    private fun holdAsset(assetId: String) = runBlocking {
        graph.transferRecords.append(
            TransferRecord(
                id = "out-1", assetId = AssetId(assetId), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example RO System", note = "",
            ),
        )
    }

    // --- row 46: create and read ---------------------------------------------------------------------

    @Test fun aPostIs201AndReadsBack() {
        val created = cartridge()
        assertEquals("Example Prefilter Cartridge", created.name)
        assertEquals("Filters", created.category)
        assertEquals("Example Filters Co.", created.manufacturer)
        assertEquals("PF-10", created.model)
        assertEquals("EX-PF-0010", created.partNumber)
        assertEquals("ea", created.preferredUnit)
        assertEquals("Sediment stage", created.notes)
        assertNull(created.archivedAt)
        assertEquals(listOf("micron_rating", "len"), created.specifications.map { it.key })
        assertEquals(listOf(0, 1), created.specifications.map { it.sortOrder })
        assertEquals(listOf("µm", "in"), created.specifications.map { it.unit })

        val read = call("GET", "/v1/supply-items/${created.id}")
        assertEquals(read.text(), 200, read.status)
        val detail = ApiJson.decodeFromString(SupplyItemDetailResponse.serializer(), read.text())
        assertEquals(created, detail.supplyItem)
        assertEquals(emptyList<AssetSupplyDto>(), detail.assetSupplies)

        // Absent text is "" and an absent list is none.
        val bare = createNamed("Example O-ring")
        assertEquals(listOf("", "", "", "", "", ""), with(bare) { listOf(category, manufacturer, model, partNumber, preferredUnit, notes) })
        assertEquals(emptyList<Any>(), bare.specifications)
        // A name is required by the decoder: a body without one is the shipped 400.
        assertEquals(400, call("POST", "/v1/supply-items", """{"category":"Filters"}""").status)
    }

    @Test fun theListIsOrderedAndIncludesArchived() {
        val zeolite = createNamed("Zeolite media")
        val anode = createNamed("anode rod")
        val prefilter = createNamed("Example Prefilter Cartridge")
        assertEquals(200, archive(zeolite.id, true).status)

        val all = list()
        // By name casefolded, then id: neither the minting order nor a case-sensitive sort.
        assertEquals(listOf(anode.id, prefilter.id, zeolite.id), all.map { it.id })
        assertNotNull("archived rows are listed, flagged by archivedAt", all.last().archivedAt)
        assertNull(all.first().archivedAt)
    }

    @Test fun getAnswersTheItemAndItsApplicability() {
        val ro = api.asset("Example RO System")
        val spareShelf = api.asset("Example Spares Shelf")
        val prefilter = createNamed("Example Prefilter Cartridge")
        val membrane = createNamed("Example Membrane")
        val onShelf = row(addRow(spareShelf, prefilter.id, "Spare"))
        val stageTwo = row(addRow(ro, prefilter.id, "Stage 2"))
        val stageOne = row(addRow(ro, prefilter.id, "Stage 1"))
        row(addRow(ro, membrane.id, "Membrane"))

        val detail = api.ok(SupplyItemDetailResponse.serializer(), "GET", "/v1/supply-items/${prefilter.id}")
        assertEquals(prefilter, detail.supplyItem)
        // Every row naming it, in (assetId, role, id) order; the membrane's row is not one of them.
        val expected = listOf(onShelf, stageTwo, stageOne).sortedWith(compareBy({ it.assetId }, { it.role }, { it.id }))
        assertEquals(expected, detail.assetSupplies)

        val missing = call("GET", "/v1/supply-items/no-such-item")
        assertEquals(404, missing.status)
        assertEquals("NO_SUCH_SUPPLY_ITEM", missing.error().code)
    }

    // --- row 47: the PATCH overlay and the clearing rule (C-7) -------------------------------------------

    @Test fun aPatchWithOnlyNotesLeavesEverythingElse() {
        val before = cartridge()
        graph.now += 60_000L
        val after = patched(before.id, """{"notes":"Replace every six months"}""")
        assertEquals("Replace every six months", after.notes)
        assertEquals(before.copy(notes = after.notes, updatedAt = after.updatedAt), after)
        assertNotEquals(before.updatedAt, after.updatedAt)
    }

    @Test fun aNullKeyIsUnchanged() {
        val before = cartridge()
        val after = patched(
            before.id,
            """{"name":null,"category":null,"manufacturer":null,"model":null,"partNumber":null,""" +
                """"preferredUnit":null,"specifications":null,"notes":"Two stages"}""",
        )
        assertEquals(before.copy(notes = "Two stages", updatedAt = after.updatedAt), after)
    }

    @Test fun anEmptyStringClearsAnOptionalField() {
        val before = cartridge()
        val after = patched(
            before.id,
            """{"category":"","manufacturer":"","model":"  ","partNumber":"","preferredUnit":"","notes":""}""",
        )
        assertEquals(listOf("", "", "", "", "", ""), with(after) { listOf(category, manufacturer, model, partNumber, preferredUnit, notes) })
        assertEquals(before.name, after.name)
        assertEquals(before.specifications, after.specifications)
        assertEquals(after, stored(before.id)?.let { it.toDto() })
    }

    @Test fun aBlankNameIs422() {
        val before = cartridge()
        for (blank in listOf("\"\"", "\"   \"")) {
            val refused = patch(before.id, """{"name":$blank}""")
            assertEquals(blank, 422, refused.status)
            assertEquals("SUPPLY_ITEM_NAME_REQUIRED", refused.error().code)
            assertEquals("name", refused.error().field)
            assertEquals("a supply item needs a name", refused.error().message)
            assertEquals(listOf("NameRequired"), refused.error().problems)
        }
        assertEquals(before.name, stored(before.id)?.name)
    }

    @Test fun anAbsentSpecificationsKeepsTheRowsTheirIdsAndKeys() {
        val before = cartridge()
        val after = patched(before.id, """{"name":"Example Sediment Cartridge"}""")
        assertEquals("Example Sediment Cartridge", after.name)
        // The same ids, the same stored keys (the typed "len" included), labels, values, units and order.
        assertEquals(before.specifications, after.specifications)
    }

    @Test fun aGivenListReplacesItWholly() {
        val before = cartridge()
        val (micron, length) = before.specifications
        val after = patched(
            before.id,
            """{"specifications":[{"id":"${length.id}","label":"Overall length","value":"20","unit":"in"},""" +
                """{"label":"Flow rate","value":"2","unit":"gpm"}]}""",
        )
        assertEquals(2, after.specifications.size)
        val (kept, added) = after.specifications
        // Sent with its id: the id and the stored key stay, though the label changed.
        assertEquals(length.id, kept.id)
        assertEquals("len", kept.key)
        assertEquals("Overall length", kept.label)
        assertEquals("20", kept.value)
        assertEquals(0, kept.sortOrder)
        // A new row: a fresh id and the label's slug.
        assertFalse(added.id in setOf(micron.id, length.id))
        assertEquals("flow_rate", added.key)
        assertEquals(1, added.sortOrder)
        // The row left out is gone.
        assertFalse(after.specifications.any { it.id == micron.id })
    }

    @Test fun anEmptySpecificationsListRemovesThem() {
        val before = cartridge()
        val after = patched(before.id, """{"specifications":[]}""")
        assertEquals(emptyList<Any>(), after.specifications)
        assertEquals(emptyList<Any>(), stored(before.id)!!.specifications)
        assertEquals(before.name, after.name)
    }

    @Test fun aNoOpPatchIs200TheStoredRowAndWritesNothing() {
        val before = cartridge()
        graph.now += 60_000L
        for (body in listOf("{}", """{"name":"Example Prefilter Cartridge","notes":"Sediment stage"}""")) {
            val same = patched(before.id, body)
            assertEquals(body, before, same)
        }
        assertEquals(before.updatedAt, stored(before.id)!!.updatedAt)
    }

    // --- row 48: archive ------------------------------------------------------------------------------

    @Test fun archiveThenUnarchive() {
        val created = cartridge()
        graph.now += 60_000L
        val archived = archive(created.id, true)
        assertEquals(archived.text(), 200, archived.status)
        assertEquals(graph.now, item(archived).archivedAt)
        assertEquals(graph.now, stored(created.id)!!.archivedAt)
        assertEquals(listOf(created.id), list().map { it.id })

        val unarchived = archive(created.id, false)
        assertEquals(200, unarchived.status)
        assertNull(item(unarchived).archivedAt)
        assertNull(stored(created.id)!!.archivedAt)
    }

    @Test fun anUnknownIdIs404NoSuchSupplyItem() {
        for ((method, path, body) in listOf(
            Triple("GET", "/v1/supply-items/no-such-item", ""),
            Triple("PATCH", "/v1/supply-items/no-such-item", """{"notes":"x"}"""),
            Triple("POST", "/v1/supply-items/no-such-item/archive", """{"archived":true}"""),
        )) {
            val refused = call(method, path, body)
            assertEquals("$method $path", 404, refused.status)
            assertEquals("$method $path", "NO_SUCH_SUPPLY_ITEM", refused.error().code)
            assertEquals("no such supply item", refused.error().message)
        }
        val created = createNamed("Example O-ring")
        // No verb deletes a SupplyItem (R15-5); a verb a shape does not take is the shipped 405, a sub-path no shape.
        assertEquals(405, call("DELETE", "/v1/supply-items/${created.id}").status)
        assertEquals(405, call("PUT", "/v1/supply-items").status)
        assertEquals(405, call("GET", "/v1/supply-items/${created.id}/archive").status)
        assertEquals(404, call("GET", "/v1/supply-items/${created.id}/specifications").status)
        assertEquals(created, stored(created.id)?.let { it.toDto() })
    }

    // --- row 49: every code -----------------------------------------------------------------------------

    @Test fun everySupplyItemProblemHasItsOwnCode() {
        val specs = "specifications"
        assertEquals(
            Refusal("SUPPLY_ITEM_NAME_REQUIRED", "a supply item needs a name", "name"),
            supplyItemRefusal(SupplyItemProblem.NameRequired),
        )
        assertEquals(
            Refusal("SPECIFICATION_LABEL_REQUIRED", "every specification needs a label", specs),
            supplyItemRefusal(SupplyItemProblem.SpecLabelRequired(0)),
        )
        assertEquals(
            Refusal("SPECIFICATION_VALUE_REQUIRED", "every specification needs a value", specs),
            supplyItemRefusal(SupplyItemProblem.SpecValueRequired(1)),
        )
        assertEquals(
            Refusal(
                "SPECIFICATION_KEY_INVALID",
                "a specification key must be lower-case letters, digits and underscores, starting with a letter, at most 40",
                specs,
            ),
            supplyItemRefusal(SupplyItemProblem.SpecKeyInvalid(2)),
        )
        assertEquals(
            Refusal("SPECIFICATION_KEY_TAKEN", "another specification of this supply item already has that key", specs),
            supplyItemRefusal(SupplyItemProblem.SpecKeyTaken(3)),
        )
        // The fallback: a validation naming no problem — unreachable, documented (the GROUP_INVALID precedent).
        val fallback = mapDomainFailure(SupplyItemValidation(emptyList()))
        assertEquals(422, fallback.status)
        assertEquals(ApiErrorDetail("SUPPLY_ITEM_INVALID", "the supply item was refused"), fallback.errorDetail())
        // The first problem's code, sentence and key; every problem by its domain name.
        val several = mapDomainFailure(
            SupplyItemValidation(listOf(SupplyItemProblem.SpecValueRequired(1), SupplyItemProblem.SpecKeyTaken(2))),
        )
        assertEquals(
            ApiErrorDetail(
                "SPECIFICATION_VALUE_REQUIRED", "every specification needs a value",
                listOf("SpecValueRequired(index=1)", "SpecKeyTaken(index=2)"), specs,
            ),
            several.errorDetail(),
        )
        val missing = mapDomainFailure(NoSuchSupplyItem(SupplyId("s-1")))
        assertEquals(404, missing.status)
        assertEquals("NO_SUCH_SUPPLY_ITEM", missing.errorDetail().code)
    }

    @Test fun everyAssetSupplyProblemHasItsCodeStatusAndField() {
        data class Expected(val status: Int, val code: String, val message: String, val field: String?)
        val table = mapOf(
            AssetSupplyProblem.OwnerMissing to Expected(404, "no_such_asset", "no such asset", null),
            AssetSupplyProblem.SupplyItemMissing to Expected(404, "NO_SUCH_SUPPLY_ITEM", "no such supply item", null),
            AssetSupplyProblem.SupplyItemArchived to
                Expected(409, "SUPPLY_ITEM_ARCHIVED", "an archived supply item takes no new asset", "supplyId"),
            AssetSupplyProblem.RoleRequired to
                Expected(422, "ASSET_SUPPLY_ROLE_REQUIRED", "an asset supply needs a role", "role"),
            AssetSupplyProblem.Taken to
                Expected(409, "ASSET_SUPPLY_TAKEN", "this asset already takes that supply item in that role", "role"),
            AssetSupplyProblem.NoSuchAssetSupply to
                Expected(404, "NO_SUCH_ASSET_SUPPLY", "no such applicability row", null),
            // Never emitted: the PATCH answers 200 with the stored row first. Defence only — the shipped 500, no code.
            AssetSupplyProblem.Unchanged to Expected(500, "internal", "Unchanged", null),
        )
        for ((problem, expected) in table) {
            val failure = assetSupplyFailure(problem)
            assertEquals(
                "$problem",
                expected,
                Expected(failure.status, failure.code, failure.message.orEmpty(), failure.field),
            )
        }
    }

    @Test fun everyNewCodeIsReachableOverTheWire() {
        val ro = api.asset("Example RO System")
        val prefilter = cartridge()

        fun refused(response: ApiResponse, status: Int, code: String, field: String?) {
            assertEquals(response.text(), status, response.status)
            assertEquals(code, response.error().code)
            assertEquals(code, field, response.error().field)
        }

        refused(call("GET", "/v1/supply-items/no-such-item"), 404, "NO_SUCH_SUPPLY_ITEM", null)
        refused(call("POST", "/v1/supply-items", """{"name":"  "}"""), 422, "SUPPLY_ITEM_NAME_REQUIRED", "name")
        refused(
            call("POST", "/v1/supply-items", """{"name":"x","specifications":[{"label":" ","value":"1"}]}"""),
            422, "SPECIFICATION_LABEL_REQUIRED", "specifications",
        )
        refused(
            call("POST", "/v1/supply-items", """{"name":"x","specifications":[{"label":"Length","value":" "}]}"""),
            422, "SPECIFICATION_VALUE_REQUIRED", "specifications",
        )
        refused(
            call("POST", "/v1/supply-items", """{"name":"x","specifications":[{"key":"Bad Key","label":"L","value":"1"}]}"""),
            422, "SPECIFICATION_KEY_INVALID", "specifications",
        )
        // A stored row repeated, each copy sent with its key: the second copy's typed key is taken by the first.
        val length = prefilter.specifications[1]
        val repeated = """{"id":"${length.id}","key":"${length.key}","label":"Length","value":"10"}"""
        refused(
            patch(prefilter.id, """{"specifications":[$repeated,$repeated]}"""),
            422, "SPECIFICATION_KEY_TAKEN", "specifications",
        )

        refused(addRow("no-such-asset", prefilter.id, "Stage 1"), 404, "no_such_asset", null)
        refused(addRow(ro, "no-such-item", "Stage 1"), 404, "NO_SUCH_SUPPLY_ITEM", null)
        // "" is not "none": it names no SupplyItem, so it is refused as unknown.
        refused(addRow(ro, "", "Stage 1"), 404, "NO_SUCH_SUPPLY_ITEM", null)
        refused(addRow(ro, prefilter.id, "   "), 422, "ASSET_SUPPLY_ROLE_REQUIRED", "role")
        row(addRow(ro, prefilter.id, "Stage 1"))
        refused(addRow(ro, prefilter.id, "Stage 1"), 409, "ASSET_SUPPLY_TAKEN", "role")
        refused(call("PATCH", "/v1/asset-supplies/no-such-row", """{"role":"Spare"}"""), 404, "NO_SUCH_ASSET_SUPPLY", null)
        refused(call("DELETE", "/v1/asset-supplies/no-such-row"), 404, "NO_SUCH_ASSET_SUPPLY", null)
        assertEquals(200, archive(prefilter.id, true).status)
        refused(addRow(ro, prefilter.id, "Stage 2"), 409, "SUPPLY_ITEM_ARCHIVED", "supplyId")
    }

    // --- row 50: applicability ------------------------------------------------------------------------

    @Test fun aPostIs201() {
        val ro = api.asset("Example RO System")
        val prefilter = createNamed("Example Prefilter Cartridge")
        val response = addRow(ro, prefilter.id, "  Stage  1 ")
        assertEquals(response.text(), 201, response.status)
        val created = row(response)
        assertEquals(ro, created.assetId)
        assertEquals(prefilter.id, created.supplyId)
        // Stored cleaned (`CategoryKey.display`), never raw.
        assertEquals("Stage 1", created.role)
        assertEquals(listOf(created), runBlocking { graph.assetSupplies.all().map { it.toDto() } })
        // `role` is required on a create: a body without one is the decoder's 400.
        assertEquals(400, call("POST", "/v1/asset-supplies", """{"assetId":"$ro","supplyId":"${prefilter.id}"}""").status)
    }

    @Test fun aTakenTripleIs409() {
        val ro = api.asset("Example RO System")
        val prefilter = createNamed("Example Prefilter Cartridge")
        row(addRow(ro, prefilter.id, "Stage 1"))
        val taken = addRow(ro, prefilter.id, " Stage  1 ")
        assertEquals(409, taken.status)
        assertEquals("ASSET_SUPPLY_TAKEN", taken.error().code)
        assertEquals("role", taken.error().field)
        // The same item in another role is a second row.
        assertEquals(201, addRow(ro, prefilter.id, "Spare").status)
        assertEquals(2, runBlocking { graph.assetSupplies.all().size })
    }

    @Test fun anArchivedItemIs409WithFieldSupplyId() {
        val ro = api.asset("Example RO System")
        val prefilter = createNamed("Example Prefilter Cartridge")
        val kept = row(addRow(ro, prefilter.id, "Stage 1"))
        assertEquals(200, archive(prefilter.id, true).status)
        val refused = addRow(ro, prefilter.id, "Stage 2")
        assertEquals(409, refused.status)
        assertEquals("SUPPLY_ITEM_ARCHIVED", refused.error().code)
        assertEquals("supplyId", refused.error().field)
        // The existing row stays, and may still be re-roled.
        assertEquals("Stage 3", row(call("PATCH", "/v1/asset-supplies/${kept.id}", """{"role":"Stage 3"}""")).role)
    }

    @Test fun aPatchReRoles() {
        val ro = api.asset("Example RO System")
        val prefilter = createNamed("Example Prefilter Cartridge")
        val created = row(addRow(ro, prefilter.id, "Stage 1"))
        graph.now += 60_000L

        val reRoled = row(call("PATCH", "/v1/asset-supplies/${created.id}", """{"role":" Spare "}"""))
        assertEquals(created.copy(role = "Spare", updatedAt = graph.now), reRoled)

        // The same cleaned role: 200 with the stored row, nothing written.
        graph.now += 60_000L
        val same = call("PATCH", "/v1/asset-supplies/${created.id}", """{"role":"Spare"}""")
        assertEquals(same.text(), 200, same.status)
        assertEquals(reRoled, row(same))
        assertEquals(reRoled.updatedAt, runBlocking { graph.assetSupplies.get(created.id)!!.updatedAt })

        // `role` is the row's only editable field: required, so its absence is the decoder's 400 and any other key too.
        assertEquals(400, call("PATCH", "/v1/asset-supplies/${created.id}", "{}").status)
        assertEquals(400, call("PATCH", "/v1/asset-supplies/${created.id}", """{"role":"x","assetId":"$ro"}""").status)
        val blank = call("PATCH", "/v1/asset-supplies/${created.id}", """{"role":"  "}""")
        assertEquals(422, blank.status)
        assertEquals("ASSET_SUPPLY_ROLE_REQUIRED", blank.error().code)
    }

    @Test fun aDeleteIs204ThenGone() {
        val ro = api.asset("Example RO System")
        val prefilter = createNamed("Example Prefilter Cartridge")
        val created = row(addRow(ro, prefilter.id, "Stage 1"))

        val removed = call("DELETE", "/v1/asset-supplies/${created.id}")
        assertEquals(removed.text(), 204, removed.status)
        assertEquals(0, removed.body.size)
        assertNull(runBlocking { graph.assetSupplies.get(created.id) })
        assertEquals(404, call("DELETE", "/v1/asset-supplies/${created.id}").status)
        // The SupplyItem stays: removing applicability never removes the item.
        assertNotNull(stored(prefilter.id))
        // The collection takes POST only; a row takes PATCH and DELETE only.
        assertEquals(405, call("GET", "/v1/asset-supplies").status)
        assertEquals(405, call("GET", "/v1/asset-supplies/${created.id}").status)
    }

    @Test fun aHeldAssetIs409AssetTransferredOut() {
        val ro = api.asset("Example RO System")
        val prefilter = createNamed("Example Prefilter Cartridge")
        val created = row(addRow(ro, prefilter.id, "Stage 1"))
        holdAsset(ro)
        val before = runBlocking { graph.assetSupplies.all() }

        for ((method, path, body) in listOf(
            Triple("POST", "/v1/asset-supplies", """{"assetId":"$ro","supplyId":"${prefilter.id}","role":"Spare"}"""),
            Triple("PATCH", "/v1/asset-supplies/${created.id}", """{"role":"Spare"}"""),
            Triple("DELETE", "/v1/asset-supplies/${created.id}", ""),
        )) {
            val refused = call(method, path, body)
            assertEquals("$method $path", 409, refused.status)
            assertEquals("asset_transferred_out", refused.error().code)
        }
        assertEquals(before, runBlocking { graph.assetSupplies.all() })

        // A no-op re-role writes nothing, so it never reaches the guard: 200 with the stored row.
        val same = call("PATCH", "/v1/asset-supplies/${created.id}", """{"role":"Stage 1"}""")
        assertEquals(same.text(), 200, same.status)
        assertEquals(created, row(same))
        // A held asset reads as any other.
        assertEquals(200, call("GET", "/v1/assets/$ro/supply-items").status)
    }

    @Test fun theAssetSubResourceListsRowsAndEachItemOnce() {
        val ro = api.asset("Example RO System")
        val other = api.asset("Example Water Heater")
        val prefilter = createNamed("Example Prefilter Cartridge")
        val membrane = createNamed("example membrane")
        val anode = createNamed("Example Anode Rod")
        val spare = row(addRow(ro, prefilter.id, "Spare"))
        val stageOne = row(addRow(ro, prefilter.id, "Stage 1"))
        val stageThree = row(addRow(ro, membrane.id, "Membrane"))
        row(addRow(other, anode.id, "Anode"))

        val answer = api.ok(AssetSupplyListResponse.serializer(), "GET", "/v1/assets/$ro/supply-items")
        assertEquals(listOf(stageThree, spare, stageOne), answer.assetSupplies)
        // Each item the rows name, once, by name casefolded then id; the other asset's item is not here.
        assertEquals(listOf(membrane, prefilter), answer.supplyItems)

        val missing = call("GET", "/v1/assets/no-such-asset/supply-items")
        assertEquals(404, missing.status)
        assertEquals("no_such_asset", missing.error().code)
        // A sub-resource: a verb it does not take is a 404, the shipped convention.
        assertEquals(404, call("POST", "/v1/assets/$ro/supply-items", "{}").status)
    }

    // --- row 51: the line refusal under the shipped families (C24) ---------------------------------------

    @Test fun aLineNamingNoItemIs422ProfileValidationWithFieldConsumables() {
        val ro = api.asset("Example RO System")
        val refused = call(
            "POST", "/v1/profiles",
            """{"assetId":"$ro","name":"Prefilter swap","eventKind":"REPLACEMENT","consumables":[""" +
                """{"name":"Prefilter cartridge","unit":"ea","supplyId":""}]}""",
        )
        assertEquals(refused.text(), 422, refused.status)
        assertEquals(
            ApiErrorDetail(
                "profile_validation", "every supplyId in consumables must name a supply item",
                listOf("UnknownSupplyItem(index=0)"), "consumables",
            ),
            refused.error(),
        )
        assertEquals(emptyList<Any>(), runBlocking { graph.profiles.forAsset(AssetId(ro)) })
    }

    @Test fun aLineNamingNoItemIs422EventValidationWithFieldConsumables() {
        val ro = api.asset("Example RO System")
        val refused = call(
            "POST", "/v1/events",
            """{"assetId":"$ro","kind":"REPLACEMENT","title":"Prefilter swap","occurredOn":"2026-09-20","tzId":"UTC",""" +
                """"consumables":[{"name":"Prefilter cartridge","quantity":"1","unit":"ea","supplyId":"no-such-item"}]}""",
        )
        assertEquals(refused.text(), 422, refused.status)
        assertEquals(
            ApiErrorDetail(
                "event_validation", "every supplyId in consumables must name a supply item",
                listOf("UnknownSupplyItem(index=0)"), "consumables",
            ),
            refused.error(),
        )
        assertEquals(emptyList<Any>(), runBlocking { graph.events.forAsset(AssetId(ro)) })
    }

    // --- row 52: status ------------------------------------------------------------------------------

    @Test fun statusCountsBothNewLists() {
        val ro = api.asset("Example RO System")
        val prefilter = createNamed("Example Prefilter Cartridge")
        val membrane = createNamed("Example Membrane")
        row(addRow(ro, prefilter.id, "Stage 1"))
        row(addRow(ro, prefilter.id, "Spare"))
        row(addRow(ro, membrane.id, "Membrane"))
        assertEquals(200, archive(membrane.id, true).status)

        val counts = api.ok(StatusResponse.serializer(), "GET", "/v1/status").counts
        // Archived items are counted: a count of rows, as every other key is.
        assertEquals(2, counts["supplyItems"])
        assertEquals(3, counts["assetSupplies"])
        assertFalse("specifications are nested in their item, never a count of their own", "supplySpecifications" in counts)
    }

    // --- row 53: the document agrees -------------------------------------------------------------------

    @Test fun everyNewCodeIsInV1md() {
        val text = repoFile("docs/api/v1.md").readText()
        val expected = listOf(
            404 to "NO_SUCH_SUPPLY_ITEM", 404 to "NO_SUCH_ASSET_SUPPLY",
            422 to "SUPPLY_ITEM_NAME_REQUIRED", 422 to "SPECIFICATION_LABEL_REQUIRED",
            422 to "SPECIFICATION_VALUE_REQUIRED", 422 to "SPECIFICATION_KEY_INVALID", 422 to "SPECIFICATION_KEY_TAKEN",
            422 to "SUPPLY_ITEM_INVALID", 409 to "SUPPLY_ITEM_ARCHIVED", 422 to "ASSET_SUPPLY_ROLE_REQUIRED",
            409 to "ASSET_SUPPLY_TAKEN",
        )
        for ((status, code) in expected) {
            val rows = Regex("""^\| (404|409|422) \| `$code` \|.*$""", RegexOption.MULTILINE).findAll(text).toList()
            assertEquals("docs/api/v1.md must carry exactly one $code row", 1, rows.size)
            assertTrue("$code's row must say $status: ${rows.single().value}", rows.single().value.startsWith("| $status |"))
        }
        // The two line rows under the shipped 1.1.0 families (B4a's arms), in the families' own shape.
        for (family in listOf("event_validation", "profile_validation")) {
            val line = "| 422 | `$family` | `UnknownSupplyItem(index=…)` | `consumables` | " +
                "`every supplyId in consumables must name a supply item` |"
            assertTrue("docs/api/v1.md is missing $line", text.lines().any { it == line })
        }
        // The routes, the status keys, the import range and the status lines.
        for (name in listOf(
            "/v1/supply-items", "/v1/supply-items/{id}", "/v1/supply-items/{id}/archive", "/v1/assets/{id}/supply-items",
            "/v1/asset-supplies", "/v1/asset-supplies/{id}", "supplyItems", "assetSupplies", "supplyId",
        )) {
            assertTrue("docs/api/v1.md does not name $name", "`$name`" in text)
        }
        assertFalse("the import range reads 1–18 now", "1–17" in text)
        assertEquals("both status lines say 18 since #15", 2, text.lines().count { "18 since #15 (supply items)" in it })
        // R15-12: no command-shapes entry — the PATCH is an overlay, so no client rebuilds a whole command.
        assertFalse("supplyItem" in repoFile("docs/api/command-shapes.json").readText())
    }
}
