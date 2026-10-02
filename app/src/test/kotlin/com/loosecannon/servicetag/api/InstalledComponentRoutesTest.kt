package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.CompositionEntryDto
import com.loosecannon.servicetag.core.backup.InstalledComponentDto
import com.loosecannon.servicetag.core.backup.SupplyItemDto
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.usecase.InstalledComponentProblem
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #47 (B4; C2, C3, C20–C22; rows 40–44, 46) — the installed-component routes over the production router, the
 * production handlers and the Room-backed [FakeGraph]: install and read, the asset sub-resource, the PATCH overlay and
 * its clearing rule, remove and replace (an omitted link or composition is none, R47-17b), every new code (each read
 * off a live route, and the mapper called directly), the two status counts, a held asset, and `docs/api/v1.md`
 * agreeing with the mapper (B4b, row 45).
 *
 * Every rule is the use cases'; what is proved here is the wire. The fake graph's today is 2026-02-10. Fixtures are
 * fictional: "Example UPS", "Example Battery Tray", "Example 12 V Battery", "Example Power Co.".
 */
class InstalledComponentRoutesTest {

    private val graph = FakeGraph()
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private fun call(method: String, path: String, body: String = ""): ApiResponse = api.call(method, path, body)

    private fun ApiResponse.text(): String = bodyText()

    private fun ApiResponse.error(): ApiErrorDetail = errorDetail()

    private fun supply(name: String): String = ApiJson.decodeFromString(
        SupplyItemResponse.serializer(),
        call("POST", "/v1/supply-items", """{"name":"$name","manufacturer":"Example Power Co."}""").also {
            assertEquals(it.text(), 201, it.status)
        }.text(),
    ).supplyItem.id

    private fun archiveSupply(id: String) =
        assertEquals(200, call("POST", "/v1/supply-items/$id/archive", """{"archived":true}""").status)

    private fun row(response: ApiResponse): InstalledComponentDto {
        assertTrue(response.text(), response.status in setOf(200, 201))
        return ApiJson.decodeFromString(InstalledComponentResponse.serializer(), response.text()).installedComponent
    }

    private fun installCall(assetId: String, fields: String = ""): ApiResponse = call(
        "POST", "/v1/installed-components",
        """{"assetId":"$assetId"${if (fields.isEmpty()) "" else ",$fields"}}""",
    )

    /** One row on [assetId]: [fields] is the rest of the body, `name` included. */
    private fun install(assetId: String, fields: String): InstalledComponentDto {
        val response = installCall(assetId, fields)
        assertEquals(response.text(), 201, response.status)
        return row(response)
    }

    private fun named(assetId: String, name: String, more: String = ""): InstalledComponentDto =
        install(assetId, """"name":"$name"${if (more.isEmpty()) "" else ",$more"}""")

    private fun patch(id: String, body: String): ApiResponse = call("PATCH", "/v1/installed-components/$id", body)

    private fun patched(id: String, body: String): InstalledComponentDto {
        val response = patch(id, body)
        assertEquals(response.text(), 200, response.status)
        return row(response)
    }

    private fun remove(id: String, removedOn: String): ApiResponse =
        call("POST", "/v1/installed-components/$id/remove", """{"removedOn":"$removedOn"}""")

    private fun replace(id: String, body: String): ApiResponse =
        call("POST", "/v1/installed-components/$id/replace", body)

    private fun replaced(id: String, body: String): InstalledComponentReplacedResponse {
        val response = replace(id, body)
        assertEquals(response.text(), 201, response.status)
        return ApiJson.decodeFromString(InstalledComponentReplacedResponse.serializer(), response.text())
    }

    private fun stored(id: String): InstalledComponentDto? =
        runBlocking { graph.installedComponents.get(InstalledComponentId(id)) }?.toDto()

    private fun everyRow(): List<InstalledComponentDto> = runBlocking { graph.installedComponents.all() }.map { it.toDto() }

    private fun entry(supplyId: String, quantity: String, unit: String = "", id: String? = null): String =
        """{${if (id == null) "" else """"id":"$id","""}"supplyId":"$supplyId","quantity":$quantity,"unit":"$unit"}"""

    private fun holdAsset(assetId: String) = runBlocking {
        graph.transferRecords.append(
            TransferRecord(
                id = "out-1", assetId = AssetId(assetId), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
                lineage = emptyList(), at = 1_758_960_000_000L, packSha256 = "ab".repeat(32),
                nameSnapshot = "Example UPS", note = "",
            ),
        )
    }

    /** A full row: a direct link, two entries, a serial, a date, notes and a sort order. */
    private class Pack(val row: InstalledComponentDto, val battery: String, val strap: String, val sku: String)

    private fun pack(ups: String): Pack {
        val battery = supply("Example 12 V Battery")
        val strap = supply("Example Battery Strap")
        val sku = supply("Example Battery Pack")
        val row = install(
            ups,
            """"name":"Battery pack","supplyId":"$sku","composition":[${entry(battery, "4", "ea")},""" +
                """${entry(strap, "2")}],"serialOrLot":"LOT-0001","installedOn":"2025-06-01",""" +
                """"notes":"Front bay","sortOrder":3""",
        )
        return Pack(row, battery, strap, sku)
    }

    // --- row 40: install and read ------------------------------------------------------------------------

    @Test fun aPostIs201AndReadsBack() {
        val ups = api.asset("Example UPS")
        val pack = pack(ups)
        with(pack.row) {
            assertEquals(ups, assetId)
            assertNull(parentId)
            assertEquals("Battery pack", name)
            assertEquals(pack.sku, supplyId)
            assertEquals(listOf(pack.battery to 4.0, pack.strap to 2.0), composition.map { it.supplyId to it.quantity })
            assertEquals(listOf("ea", ""), composition.map { it.unit })
            assertEquals(listOf(0, 1), composition.map { it.sortOrder })
            assertEquals("LOT-0001", serialOrLot)
            assertEquals("2025-06-01", installedOn)
            assertNull(removedOn)
            assertNull(replacesId)
            assertEquals(3, sortOrder)
            assertEquals("Front bay", notes)
        }
        assertEquals(pack.row, stored(pack.row.id))

        // Absent text is "", an absent or "" date is not recorded, an absent link and composition are none, and an
        // absent sort order appends after the current siblings.
        val bare = named(ups, "Example Battery Tray")
        assertEquals(listOf("", ""), listOf(bare.serialOrLot, bare.notes))
        assertNull(bare.supplyId)
        assertNull(bare.installedOn)
        assertEquals(emptyList<CompositionEntryDto>(), bare.composition)
        assertEquals(4, bare.sortOrder)
        assertNull(named(ups, "Example Fuse", """"installedOn":"","supplyId":""""").let { it.installedOn ?: it.supplyId })
        // A name and an asset are required by the decoder: a body without one is the shipped 400.
        assertEquals(400, call("POST", "/v1/installed-components", """{"assetId":"$ups"}""").status)
        assertEquals(400, call("POST", "/v1/installed-components", """{"name":"Example Fuse"}""").status)
    }

    @Test fun getById() {
        val ups = api.asset("Example UPS")
        val created = named(ups, "Example Battery Tray")
        assertEquals(created, row(call("GET", "/v1/installed-components/${created.id}")))

        val missing = call("GET", "/v1/installed-components/no-such-row")
        assertEquals(404, missing.status)
        assertEquals("NO_SUCH_INSTALLED_COMPONENT", missing.error().code)
        assertNull(missing.error().field)
    }

    @Test fun theSubResourceListsCurrentAndRemovedAndEachSupplyItemOnce() {
        val ups = api.asset("Example UPS")
        val other = api.asset("Example Inverter")
        val battery = supply("example 12 V Battery")
        val tray = supply("Example Battery Tray")
        val coolant = supply("Example Coolant")
        val fan = supply("Example Fan")
        val trayRow = named(ups, "Tray", """"supplyId":"$tray"""")
        val position = named(ups, "Position 1", """"parentId":"${trayRow.id}","supplyId":"$battery"""")
        val pack = named(ups, "Pack", """"composition":[${entry(battery, "4")}]""")
        // The only row naming the coolant is removed: its item is still listed, once.
        val drained = named(ups, "Loop", """"installedOn":"2025-01-01","composition":[${entry(coolant, "2", "L")}]""")
        assertEquals(200, remove(drained.id, "2025-02-01").status)
        named(other, "Fan", """"supplyId":"$fan"""")

        val answer = api.ok(InstalledComponentListResponse.serializer(), "GET", "/v1/assets/$ups/installed-components")
        assertEquals(
            "every row of the asset, current and removed, by id",
            listOf(trayRow.id, position.id, pack.id, drained.id).sorted(),
            answer.installedComponents.map { it.id },
        )
        assertEquals("2025-02-01", answer.installedComponents.single { it.id == drained.id }.removedOn)
        assertEquals(listOf(2.0), answer.installedComponents.single { it.id == drained.id }.composition.map { it.quantity })
        // Each item the rows and the entries name, once, by name casefolded then id; the other asset's is not here.
        assertEquals(
            listOf("example 12 V Battery", "Example Battery Tray", "Example Coolant"),
            answer.supplyItems.map(SupplyItemDto::name),
        )

        val missing = call("GET", "/v1/assets/no-such-asset/installed-components")
        assertEquals(404, missing.status)
        assertEquals("no_such_asset", missing.error().code)
        // A sub-resource: a verb it does not take is a 404, the shipped convention.
        assertEquals(404, call("POST", "/v1/assets/$ups/installed-components", "{}").status)
        // The collection takes a POST only: rows are read per asset, never as one list of every row.
        assertEquals(405, call("GET", "/v1/installed-components").status)
    }

    // --- row 41: the PATCH overlay and its clearing rule ------------------------------------------------

    @Test fun onlyNotesLeavesEverythingElse() {
        val ups = api.asset("Example UPS")
        val before = pack(ups).row
        graph.now = 5_000L
        val after = patched(before.id, """{"notes":"Rear bay"}""")
        assertEquals(before.copy(notes = "Rear bay", updatedAt = 5_000L), after)
        assertEquals(after, stored(before.id))
    }

    @Test fun aNullKeyIsUnchanged() {
        val ups = api.asset("Example UPS")
        val before = pack(ups).row
        graph.now = 5_000L
        val after = patched(
            before.id,
            """{"name":null,"supplyId":null,"composition":null,"serialOrLot":null,"installedOn":null,""" +
                """"notes":null,"sortOrder":null}""",
        )
        assertEquals("null keeps every field, so nothing is written", before, after)
        assertEquals(before, stored(before.id))
    }

    @Test fun anEmptyStringClearsTheLinkTheDateTheSerialAndTheNotes() {
        val ups = api.asset("Example UPS")
        val before = pack(ups).row
        val after = patched(before.id, """{"supplyId":"","installedOn":"","serialOrLot":"","notes":""}""")
        assertNull(after.supplyId)
        assertNull("\"\" clears the install date to not recorded", after.installedOn)
        assertEquals("", after.serialOrLot)
        assertEquals("", after.notes)
        assertEquals("the untouched keys stay", before.name to before.composition, after.name to after.composition)
        assertEquals(after, stored(before.id))
    }

    @Test fun aBlankNameIs422() {
        val ups = api.asset("Example UPS")
        val before = pack(ups).row
        for (name in listOf("", "   ")) {
            val refused = patch(before.id, """{"name":"$name"}""")
            assertEquals(refused.text(), 422, refused.status)
            assertEquals("INSTALLED_COMPONENT_NAME_REQUIRED", refused.error().code)
            assertEquals("name", refused.error().field)
        }
        assertEquals(before, stored(before.id))
    }

    @Test fun anAbsentCompositionKeepsEntriesAndIds() {
        val ups = api.asset("Example UPS")
        val before = pack(ups).row
        val after = patched(before.id, """{"name":"Battery pack A","serialOrLot":"LOT-0002"}""")
        assertEquals(before.composition, after.composition)
        assertEquals("Battery pack A", after.name)
    }

    @Test fun aGivenCompositionReplacesIt() {
        val ups = api.asset("Example UPS")
        val pack = pack(ups)
        val (four, two) = pack.row.composition
        val after = patched(
            pack.row.id,
            """{"composition":[${entry(pack.strap, "3", id = two.id)},${entry(pack.battery, "1.5", "kg")}]}""",
        )
        assertEquals(2, after.composition.size)
        // An owned id sent back keeps it; an entry sent without one is minted; the one not sent is gone.
        assertEquals(CompositionEntryDto(two.id, pack.strap, 3.0, "", 0), after.composition[0])
        val minted = after.composition[1]
        assertTrue(minted.id !in setOf(four.id, two.id))
        assertEquals(CompositionEntryDto(minted.id, pack.battery, 1.5, "kg", 1), minted)
        assertEquals(after, stored(pack.row.id))
    }

    @Test fun anEmptyCompositionEmptiesIt() {
        val ups = api.asset("Example UPS")
        val before = pack(ups).row
        val after = patched(before.id, """{"composition":[]}""")
        assertEquals(emptyList<CompositionEntryDto>(), after.composition)
        assertEquals(before.supplyId, after.supplyId)
    }

    @Test fun aRepeatedEntryIdKeepsTheFirstAndMintsTheRest() {
        val ups = api.asset("Example UPS")
        val pack = pack(ups)
        val first = pack.row.composition.first()
        val after = patched(
            pack.row.id,
            """{"composition":[${entry(pack.battery, "4", "ea", first.id)},${entry(pack.battery, "2", id = first.id)}]}""",
        )
        assertEquals(first.id, after.composition[0].id)
        assertNotEquals("a repeated id is minted fresh (C-7)", first.id, after.composition[1].id)
        assertEquals(listOf(4.0, 2.0), after.composition.map { it.quantity })
    }

    @Test fun aStringQuantityIs400() {
        val ups = api.asset("Example UPS")
        val pack = pack(ups)
        for (quantity in listOf("\"4\"", "true", "null")) {
            val refused = patch(pack.row.id, """{"composition":[${entry(pack.battery, quantity)}]}""")
            assertEquals("$quantity: ${refused.text()}", 400, refused.status)
            assertEquals("bad_request", refused.error().code)
            assertEquals(400, installCall(ups, """"name":"x","composition":[${entry(pack.battery, quantity)}]""").status)
        }
        assertEquals(pack.row, stored(pack.row.id))
        assertEquals(1, everyRow().size)
    }

    @Test fun assetParentRemovedOnAndReplacesIdKeysAre400() {
        val ups = api.asset("Example UPS")
        val tray = named(ups, "Example Battery Tray")
        val before = named(ups, "Position 1", """"parentId":"${tray.id}"""")
        for (body in listOf(
            """{"assetId":"$ups"}""", """{"parentId":"${tray.id}"}""", """{"removedOn":"2025-06-01"}""",
            """{"replacesId":"${tray.id}"}""", """{"name":"x","composition":[{"supplyId":"s","quantity":1,"sortOrder":0}]}""",
        )) {
            val refused = patch(before.id, body)
            assertEquals(body, 400, refused.status)
            assertEquals("bad_request", refused.error().code)
        }
        assertEquals(before, stored(before.id))
    }

    @Test fun aNoOpIs200AndWritesNothing() {
        val ups = api.asset("Example UPS")
        val before = pack(ups).row
        graph.now = 5_000L
        val composition = before.composition.joinToString(",") { entry(it.supplyId, "${it.quantity}", it.unit, it.id) }
        val same = patched(
            before.id,
            """{"name":"${before.name}","supplyId":"${before.supplyId}","composition":[$composition],""" +
                """"serialOrLot":"${before.serialOrLot}","installedOn":"${before.installedOn}",""" +
                """"notes":"${before.notes}","sortOrder":${before.sortOrder}}""",
        )
        assertEquals("200 with the stored row; updatedAt did not move", before, same)
        assertEquals(before, stored(before.id))
    }

    @Test fun aRestoredFutureInstallDateTakesANotesOnlyPatch() {
        val ups = api.asset("Example UPS")
        // As a restore carries it: an install date later than this phone's today (2026-02-10), stored unjudged.
        val restored = InstalledComponent(
            id = InstalledComponentId("restored-tray"), assetId = AssetId(ups), parentId = null,
            name = "Example Battery Tray", supplyId = null, composition = emptyList(), serialOrLot = "",
            installedOn = "2026-03-01", removedOn = null, replacesId = null, sortOrder = 0, notes = "",
            createdAt = 1_000L, updatedAt = 1_000L,
        )
        runBlocking { graph.installedComponents.insert(restored) }
        graph.now = 5_000L

        // The overlay re-sends the stored date, and an unchanged date is not judged again.
        val after = patched(restored.id.value, """{"notes":"Checked"}""")
        assertEquals(restored.toDto().copy(notes = "Checked", updatedAt = 5_000L), after)
        assertEquals(after, stored(restored.id.value))
        // Moving it to another future day is still the 422.
        val moved = patch(restored.id.value, """{"installedOn":"2026-03-02"}""")
        assertEquals(moved.text(), 422, moved.status)
        assertEquals("INSTALLED_COMPONENT_DATE_AFTER_TODAY", moved.error().code)
        assertEquals("installedOn", moved.error().field)
    }

    // --- row 42: remove and replace ----------------------------------------------------------------------

    @Test fun removeIs200WithTheClosedSubtree() {
        val ups = api.asset("Example UPS")
        val battery = supply("Example 12 V Battery")
        val tray = named(ups, "Tray", """"installedOn":"2025-01-01"""")
        val one = named(ups, "Position 1", """"parentId":"${tray.id}","composition":[${entry(battery, "1")}]""")
        val two = named(ups, "Position 2", """"parentId":"${tray.id}"""")
        val clip = named(ups, "Clip", """"parentId":"${two.id}"""")
        val gone = named(ups, "Position 3", """"parentId":"${tray.id}","installedOn":"2025-02-01"""")
        assertEquals(200, remove(gone.id, "2025-03-01").status)
        val bystander = named(ups, "Fuse")

        val response = remove(tray.id, "2025-09-01")
        assertEquals(response.text(), 200, response.status)
        val answer = ApiJson.decodeFromString(InstalledComponentRemovedResponse.serializer(), response.text())
        assertEquals(tray.id, answer.installedComponent.id)
        assertEquals("2025-09-01", answer.installedComponent.removedOn)
        // Every current descendant, by id, closed on the same date; the earlier removal is not among them.
        assertEquals(listOf(one.id, two.id, clip.id).sorted(), answer.closed.map { it.id })
        assertTrue(answer.closed.all { it.removedOn == "2025-09-01" })
        // The store agrees: the subtree closed with it, compositions kept, the earlier date and the bystander untouched.
        val after = everyRow().associateBy { it.id }
        for (id in listOf(tray.id, one.id, two.id, clip.id)) assertEquals(id, "2025-09-01", after.getValue(id).removedOn)
        assertEquals(one.composition, after.getValue(one.id).composition)
        assertEquals("2025-03-01", after.getValue(gone.id).removedOn)
        assertEquals(bystander, after.getValue(bystander.id))
        assertEquals("nothing is deleted", 6, after.size)
    }

    @Test fun removeTwiceIs409() {
        val ups = api.asset("Example UPS")
        val row = named(ups, "Example Battery Tray")
        assertEquals(200, remove(row.id, "2025-09-01").status)
        val again = remove(row.id, "2025-09-02")
        assertEquals(again.text(), 409, again.status)
        assertEquals("INSTALLED_COMPONENT_REMOVED", again.error().code)
        assertNull(again.error().field)
        assertEquals("2025-09-01", stored(row.id)?.removedOn)
        // The removal date is required, by the decoder.
        assertEquals(400, call("POST", "/v1/installed-components/${row.id}/remove", "{}").status)
        assertEquals("NO_SUCH_INSTALLED_COMPONENT", remove("no-such-row", "2025-09-01").error().code)
    }

    @Test fun replaceIs201WithBothRows() {
        val ups = api.asset("Example UPS")
        val tray = named(ups, "Example Battery Tray")
        val battery = supply("Example 12 V Battery")
        val sku = supply("Example Battery Pack")
        val old = install(
            ups,
            """"name":"Battery pack","parentId":"${tray.id}","supplyId":"$sku","composition":[${entry(battery, "4")}],""" +
                """"serialOrLot":"LOT-0001","installedOn":"2025-01-01","notes":"Old","sortOrder":2""",
        )
        val strap = named(ups, "Strap", """"parentId":"${old.id}"""")

        val answer = replaced(
            old.id,
            """{"replacedOn":"2025-10-01","name":"Battery pack B","supplyId":"$sku",""" +
                """"composition":[${entry(battery, "4", "ea")}],"serialOrLot":"LOT-0002","notes":"New"}""",
        )
        with(answer.installedComponent) {
            assertNotEquals(old.id, id)
            assertEquals(ups, assetId)
            assertEquals("the successor takes the parent", tray.id, parentId)
            assertEquals("and the sort order", 2, sortOrder)
            assertEquals(old.id, replacesId)
            assertEquals("2025-10-01", installedOn)
            assertNull(removedOn)
            assertEquals(listOf("Battery pack B", sku, "LOT-0002", "New"), listOf(name, supplyId, serialOrLot, notes))
            assertEquals(listOf(battery to 4.0), composition.map { it.supplyId to it.quantity })
        }
        // The replaced row closes on the day and keeps its own composition; its current child closes with it.
        assertEquals(old.copy(removedOn = "2025-10-01", updatedAt = answer.replaced.updatedAt), answer.replaced)
        assertEquals(listOf(strap.id), answer.closed.map { it.id })
        assertEquals("2025-10-01", stored(strap.id)?.removedOn)
        assertEquals(answer.installedComponent, stored(answer.installedComponent.id))
        // Replacing it again is the 409: a closed row is history.
        assertEquals("INSTALLED_COMPONENT_REMOVED", replace(old.id, """{"replacedOn":"2025-10-02"}""").error().code)
    }

    @Test fun anAbsentNameIsThePredecessors() {
        val ups = api.asset("Example UPS")
        val old = named(ups, "Battery pack")
        for ((index, body) in listOf("""{"replacedOn":"2025-10-01"}""", """{"replacedOn":"2025-10-01","name":null}""").withIndex()) {
            val from = if (index == 0) old else named(ups, "Battery pack")
            assertEquals(body, "Battery pack", replaced(from.id, body).installedComponent.name)
        }
        assertEquals("NO_SUCH_INSTALLED_COMPONENT", replace("no-such-row", """{"replacedOn":"2025-10-01"}""").error().code)
        // The replacement date is required, by the decoder.
        assertEquals(400, replace(old.id, """{"name":"x"}""").status)
    }

    @Test fun anAbsentLinkIsNone() {
        val ups = api.asset("Example UPS")
        val sku = supply("Example Battery Pack")
        for (link in listOf("", ""","supplyId":null""", ""","supplyId":""""")) {
            val old = named(ups, "Battery pack", """"supplyId":"$sku"""")
            val answer = replaced(old.id, """{"replacedOn":"2025-10-01"$link}""")
            assertNull("the link is never the predecessor's ($link)", answer.installedComponent.supplyId)
            assertEquals(sku, answer.replaced.supplyId)
        }
    }

    @Test fun anAbsentCompositionIsNone() {
        val ups = api.asset("Example UPS")
        val battery = supply("Example 12 V Battery")
        for (composition in listOf("", ""","composition":null""", ""","composition":[]""")) {
            val old = named(ups, "Battery pack", """"composition":[${entry(battery, "4", "ea")}]""")
            val answer = replaced(old.id, """{"replacedOn":"2025-10-01"$composition}""")
            assertEquals(
                "the successor has no composition ($composition)",
                emptyList<CompositionEntryDto>(),
                answer.installedComponent.composition,
            )
            assertEquals(old.composition, answer.replaced.composition)
            assertEquals(emptyList<CompositionEntryDto>(), stored(answer.installedComponent.id)?.composition)
        }
    }

    @Test fun aSentCompositionGetsFreshIds() {
        val ups = api.asset("Example UPS")
        val pack = pack(ups)
        val sent = pack.row.composition.joinToString(",") { entry(it.supplyId, "${it.quantity}", it.unit, it.id) }
        val answer = replaced(pack.row.id, """{"replacedOn":"2025-10-01","composition":[$sent]}""")
        val successor = answer.installedComponent.composition
        assertEquals(pack.row.composition.map { Triple(it.supplyId, it.quantity, it.unit) }, successor.map {
            Triple(it.supplyId, it.quantity, it.unit)
        })
        assertTrue(
            "the successor's entry ids are its own",
            successor.map { it.id }.intersect(pack.row.composition.map { it.id }.toSet()).isEmpty(),
        )
        assertEquals(pack.row.composition, answer.replaced.composition)
    }

    @Test fun nothingDeletesAnInstalledComponent() {
        val ups = api.asset("Example UPS")
        val row = named(ups, "Example Battery Tray")
        assertEquals(405, call("DELETE", "/v1/installed-components/${row.id}").status)
        assertEquals(405, call("PUT", "/v1/installed-components/${row.id}", "{}").status)
        assertEquals(405, call("GET", "/v1/installed-components/${row.id}/remove").status)
        assertEquals(405, call("DELETE", "/v1/installed-components/${row.id}/replace").status)
        assertEquals(404, call("POST", "/v1/installed-components/${row.id}/delete", "{}").status)
        assertEquals(listOf(row), everyRow())
    }

    // --- row 43: every code ------------------------------------------------------------------------------

    @Test fun everyProblemHasItsCodeStatusAndField() {
        data class Expected(val status: Int, val code: String, val message: String, val field: String?)
        val table = mapOf(
            InstalledComponentProblem.NoSuchInstalledComponent to
                Expected(404, "NO_SUCH_INSTALLED_COMPONENT", "no such installed component", null),
            InstalledComponentProblem.ParentMissing to
                Expected(404, "NO_SUCH_INSTALLED_COMPONENT", "no such installed component", "parentId"),
            InstalledComponentProblem.NameRequired to
                Expected(422, "INSTALLED_COMPONENT_NAME_REQUIRED", "an installed component needs a name", "name"),
            InstalledComponentProblem.BadDate("removedOn") to
                Expected(422, "INSTALLED_COMPONENT_DATE_INVALID", "a date must be YYYY-MM-DD", "removedOn"),
            InstalledComponentProblem.AfterToday("installedOn") to
                Expected(422, "INSTALLED_COMPONENT_DATE_AFTER_TODAY", "a date cannot be later than today", "installedOn"),
            InstalledComponentProblem.RemovedBeforeInstalled("replacedOn") to Expected(
                422, "INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED", "the removal date cannot be before the install date",
                "replacedOn",
            ),
            InstalledComponentProblem.ParentOnAnotherAsset to Expected(
                422, "INSTALLED_COMPONENT_PARENT_ON_ANOTHER_ASSET", "the parent belongs to another asset", "parentId",
            ),
            InstalledComponentProblem.ParentRemoved to
                Expected(409, "INSTALLED_COMPONENT_PARENT_REMOVED", "the parent has been removed", "parentId"),
            InstalledComponentProblem.AlreadyRemoved to
                Expected(409, "INSTALLED_COMPONENT_REMOVED", "this installed component has already been removed", null),
            InstalledComponentProblem.QuantityInvalid(1) to Expected(
                422, "COMPOSITION_QUANTITY_INVALID", "every composition quantity must be a number above zero", "composition",
            ),
            InstalledComponentProblem.OwnerMissing to Expected(404, "no_such_asset", "no such asset", null),
            InstalledComponentProblem.SupplyItemMissing to
                Expected(404, "NO_SUCH_SUPPLY_ITEM", "no such supply item", "supplyId"),
            InstalledComponentProblem.EntrySupplyItemMissing(0) to
                Expected(404, "NO_SUCH_SUPPLY_ITEM", "no such supply item", "composition"),
            // The reused code keeps its shipped sentence verbatim (G1, C-8).
            InstalledComponentProblem.SupplyItemArchived to
                Expected(409, "SUPPLY_ITEM_ARCHIVED", "an archived supply item takes no new asset", "supplyId"),
            InstalledComponentProblem.EntrySupplyItemArchived(2) to
                Expected(409, "SUPPLY_ITEM_ARCHIVED", "an archived supply item takes no new asset", "composition"),
            // Never emitted: the edit answers 200 with the stored row first. Defence only — the shipped 500, no code.
            InstalledComponentProblem.Unchanged to Expected(500, "internal", "Unchanged", null),
        )
        for ((problem, expected) in table) {
            val failure = installedComponentRefusal(listOf(problem))
            assertEquals("$problem", expected, Expected(failure.status, failure.code, failure.message.orEmpty(), failure.field))
        }
        // The first problem decides; `problems` names every one by its domain name.
        val both = installedComponentRefusal(
            listOf(InstalledComponentProblem.NameRequired, InstalledComponentProblem.QuantityInvalid(1)),
        )
        assertEquals("INSTALLED_COMPONENT_NAME_REQUIRED", both.code)
        assertEquals(listOf("NameRequired", "QuantityInvalid(index=1)"), both.problems)
        // The fallback, for a refusal naming no problem.
        val none = installedComponentRefusal(emptyList())
        assertEquals(
            Expected(422, "INSTALLED_COMPONENT_INVALID", "the installed component was refused", null),
            Expected(none.status, none.code, none.message.orEmpty(), none.field),
        )
    }

    @Test fun everyNewCodeIsReachableOverTheWire() {
        val ups = api.asset("Example UPS")
        val inverter = api.asset("Example Inverter")
        val battery = supply("Example 12 V Battery")
        val old = supply("Example Old Battery")
        archiveSupply(old)
        val tray = named(ups, "Tray", """"installedOn":"2025-06-01"""")
        val closed = named(ups, "Closed", """"installedOn":"2025-06-01"""")
        assertEquals(200, remove(closed.id, "2025-07-01").status)
        val before = everyRow()

        fun refused(response: ApiResponse, status: Int, code: String, field: String?) {
            assertEquals(response.text(), status, response.status)
            assertEquals(code, response.error().code)
            assertEquals(code, field, response.error().field)
        }

        refused(call("GET", "/v1/installed-components/no-such-row"), 404, "NO_SUCH_INSTALLED_COMPONENT", null)
        refused(installCall(ups, """"name":"x","parentId":"no-such-row""""), 404, "NO_SUCH_INSTALLED_COMPONENT", "parentId")
        // `parentId` is read as sent, as an asset's `parentAssetId` is: `""` names no row, never "top level".
        refused(installCall(ups, """"name":"x","parentId":"""""), 404, "NO_SUCH_INSTALLED_COMPONENT", "parentId")
        refused(installCall(ups, """"name":"  """"), 422, "INSTALLED_COMPONENT_NAME_REQUIRED", "name")
        refused(installCall(ups, """"name":"x","installedOn":"2025-13-01""""), 422, "INSTALLED_COMPONENT_DATE_INVALID", "installedOn")
        refused(remove(tray.id, "June"), 422, "INSTALLED_COMPONENT_DATE_INVALID", "removedOn")
        refused(replace(tray.id, """{"replacedOn":""}"""), 422, "INSTALLED_COMPONENT_DATE_INVALID", "replacedOn")
        refused(
            installCall(ups, """"name":"x","installedOn":"2026-02-11""""), 422, "INSTALLED_COMPONENT_DATE_AFTER_TODAY",
            "installedOn",
        )
        refused(remove(tray.id, "2099-01-01"), 422, "INSTALLED_COMPONENT_DATE_AFTER_TODAY", "removedOn")
        refused(remove(tray.id, "2025-01-01"), 422, "INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED", "removedOn")
        refused(
            replace(tray.id, """{"replacedOn":"2025-01-01"}"""), 422, "INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED",
            "replacedOn",
        )
        refused(
            patch(closed.id, """{"installedOn":"2025-08-01"}"""), 422, "INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED",
            "installedOn",
        )
        refused(
            installCall(inverter, """"name":"x","parentId":"${tray.id}""""), 422,
            "INSTALLED_COMPONENT_PARENT_ON_ANOTHER_ASSET", "parentId",
        )
        refused(installCall(ups, """"name":"x","parentId":"${closed.id}""""), 409, "INSTALLED_COMPONENT_PARENT_REMOVED", "parentId")
        refused(remove(closed.id, "2025-08-01"), 409, "INSTALLED_COMPONENT_REMOVED", null)
        for (quantity in listOf("0", "-1")) {
            refused(
                installCall(ups, """"name":"x","composition":[${entry(battery, quantity)}]"""), 422,
                "COMPOSITION_QUANTITY_INVALID", "composition",
            )
        }
        // A sent sort order outside 0..1000000 is the decoder's 400: a value that does not fit its field.
        for (outside in listOf(installCall(ups, """"name":"x","sortOrder":-1"""), patch(tray.id, """{"sortOrder":1000001}"""))) {
            refused(outside, 400, "bad_request", null)
            assertEquals("sortOrder must be a whole number between 0 and 1000000", outside.error().message)
        }
        // The reused codes, with the body key that caused them.
        refused(installCall("no-such-asset", """"name":"x""""), 404, "no_such_asset", null)
        refused(installCall(ups, """"name":"x","supplyId":"no-such-item""""), 404, "NO_SUCH_SUPPLY_ITEM", "supplyId")
        refused(
            installCall(ups, """"name":"x","composition":[${entry("no-such-item", "1")}]"""), 404, "NO_SUCH_SUPPLY_ITEM",
            "composition",
        )
        refused(installCall(ups, """"name":"x","supplyId":"$old""""), 409, "SUPPLY_ITEM_ARCHIVED", "supplyId")
        refused(
            installCall(ups, """"name":"x","composition":[${entry(old, "1")}]"""), 409, "SUPPLY_ITEM_ARCHIVED",
            "composition",
        )
        refused(replace(tray.id, """{"replacedOn":"2025-10-01","supplyId":"$old"}"""), 409, "SUPPLY_ITEM_ARCHIVED", "supplyId")
        assertEquals("no refusal wrote anything", before, everyRow())
        // The bound itself is accepted.
        assertEquals(1_000_000, patched(tray.id, """{"sortOrder":1000000}""").sortOrder)
    }

    // --- row 44: status ----------------------------------------------------------------------------------

    @Test fun statusCountsRowsAndEntries() {
        val empty = api.ok(StatusResponse.serializer(), "GET", "/v1/status").counts
        assertEquals(0, empty["installedComponents"])
        assertEquals(0, empty["compositionEntries"])

        val ups = api.asset("Example UPS")
        val battery = supply("Example 12 V Battery")
        val strap = supply("Example Battery Strap")
        named(ups, "Pack", """"composition":[${entry(battery, "4")},${entry(strap, "2")}]""")
        named(ups, "Tray")
        val removed = named(ups, "Old pack", """"composition":[${entry(battery, "4")}]""")
        assertEquals(200, remove(removed.id, "2025-09-01").status)

        val counts = api.ok(StatusResponse.serializer(), "GET", "/v1/status").counts
        // Every row, current and removed, and every entry of every row: a count of rows, as every other key is.
        assertEquals(3, counts["installedComponents"])
        assertEquals(3, counts["compositionEntries"])
    }

    // --- row 46: a held asset ----------------------------------------------------------------------------

    @Test fun aHeldAssetIs409AssetTransferredOut() {
        val ups = api.asset("Example UPS")
        val pack = pack(ups)
        val tray = named(ups, "Example Battery Tray")
        holdAsset(ups)
        val before = everyRow()

        for ((method, path, body) in listOf(
            Triple("POST", "/v1/installed-components", """{"assetId":"$ups","name":"Example Fuse"}"""),
            Triple("POST", "/v1/installed-components", """{"assetId":"$ups","name":"Position 1","parentId":"${tray.id}"}"""),
            Triple("PATCH", "/v1/installed-components/${pack.row.id}", """{"notes":"Rear bay"}"""),
            Triple("POST", "/v1/installed-components/${pack.row.id}/remove", """{"removedOn":"2025-09-01"}"""),
            Triple("POST", "/v1/installed-components/${tray.id}/replace", """{"replacedOn":"2025-09-01"}"""),
        )) {
            val refused = call(method, path, body)
            assertEquals("$method $path: ${refused.text()}", 409, refused.status)
            assertEquals("asset_transferred_out", refused.error().code)
        }
        assertEquals(before, everyRow())

        // A no-op edit writes nothing, so it never reaches the guard: 200 with the stored row.
        assertEquals(pack.row, patched(pack.row.id, """{"notes":"Front bay"}"""))
        // A held asset reads as any other.
        assertEquals(200, call("GET", "/v1/assets/$ups/installed-components").status)
        assertEquals(200, call("GET", "/v1/installed-components/${pack.row.id}").status)
    }

    // --- row 45: the document agrees -------------------------------------------------------------------

    /** The C2 codes' row in `v1.md`: exactly one, in the table's `| status | code | when |` shape. */
    private fun codeRow(text: String, code: String): String {
        val rows = Regex("""^\| (404|409|422) \| `$code` \|.*$""", RegexOption.MULTILINE).findAll(text).toList()
        assertEquals("docs/api/v1.md must carry exactly one $code row", 1, rows.size)
        return rows.single().value
    }

    @Test fun everyNewCodeIsInV1md() {
        val text = repoFile("docs/api/v1.md").readText()
        // Every new code as the mapper answers it: its status, its sentence and its field, on its one row.
        val failures = listOf(
            InstalledComponentProblem.NoSuchInstalledComponent, InstalledComponentProblem.ParentMissing,
            InstalledComponentProblem.NameRequired, InstalledComponentProblem.BadDate("installedOn"),
            InstalledComponentProblem.BadDate("removedOn"), InstalledComponentProblem.BadDate("replacedOn"),
            InstalledComponentProblem.AfterToday("installedOn"), InstalledComponentProblem.AfterToday("removedOn"),
            InstalledComponentProblem.AfterToday("replacedOn"), InstalledComponentProblem.RemovedBeforeInstalled("removedOn"),
            InstalledComponentProblem.RemovedBeforeInstalled("replacedOn"),
            InstalledComponentProblem.RemovedBeforeInstalled("installedOn"), InstalledComponentProblem.ParentOnAnotherAsset,
            InstalledComponentProblem.ParentRemoved, InstalledComponentProblem.AlreadyRemoved,
            InstalledComponentProblem.QuantityInvalid(0),
        ).map { installedComponentRefusal(listOf(it)) } + installedComponentRefusal(emptyList())
        val codes = failures.map { it.code }.toSet()
        assertEquals(10, codes.size)
        for (failure in failures) {
            val row = codeRow(text, failure.code)
            assertTrue("${failure.code}'s row must say ${failure.status}: $row", row.startsWith("| ${failure.status} |"))
            assertTrue("${failure.code}'s row must carry its sentence: $row", "`message` `${failure.message}`" in row)
            failure.field?.let { assertTrue("${failure.code}'s row must name `$it`: $row", "`$it`" in row) }
        }
        // The routes, the row's keys, the status keys and the merge reason.
        for (name in listOf(
            "/v1/assets/{id}/installed-components", "/v1/installed-components", "/v1/installed-components/{id}",
            "/v1/installed-components/{id}/remove", "/v1/installed-components/{id}/replace", "installedComponents",
            "compositionEntries", "composition", "serialOrLot", "installedOn", "removedOn", "replacesId", "replacedOn",
            "INSTALLED_COMPONENT_REPLACEMENT_TAKEN",
        )) {
            assertTrue("docs/api/v1.md does not name $name", "`$name`" in text)
        }
        assertFalse("the import range reads 1–20 now", "1–19" in text)
        assertEquals(
            "both status lines say 19 since #47",
            2,
            text.lines().count { "19 since #47 (installed components)" in it },
        )
        // No command-shapes entry: the PATCH is an overlay, so no client rebuilds a whole command.
        assertFalse("installedComponent" in repoFile("docs/api/command-shapes.json").readText())
    }

    @Test fun theReusedSupplyCodesNameTheComponentRoutes() {
        val text = repoFile("docs/api/v1.md").readText()
        // C-8: the two shipped rows, widened to the #47 routes with both fields, their sentences unchanged.
        for (problem in listOf(
            InstalledComponentProblem.SupplyItemMissing, InstalledComponentProblem.EntrySupplyItemMissing(0),
            InstalledComponentProblem.SupplyItemArchived, InstalledComponentProblem.EntrySupplyItemArchived(0),
        )) {
            val failure = installedComponentRefusal(listOf(problem))
            val rows = Regex("""^\| ${failure.status} \| `${failure.code}` \|.*$""", RegexOption.MULTILINE)
                .findAll(text).toList()
            assertEquals("docs/api/v1.md must carry exactly one ${failure.code} row", 1, rows.size)
            val row = rows.single().value
            assertTrue("$problem: the row must name the #47 routes: $row", "`/v1/installed-components`" in row)
            assertTrue("$problem: the row must name `field` `${failure.field}`: $row", "`field` `${failure.field}`" in row)
            assertTrue("$problem: the row must keep its sentence: $row", "`message` `${failure.message}`" in row)
        }
    }
}
