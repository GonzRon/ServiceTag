package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.testing.SupplyEstate
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.assetSupplyOf
import com.loosecannon.servicetag.core.testing.dataJsonOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.editRows
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.specificationOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import com.loosecannon.servicetag.core.testing.with
import com.loosecannon.servicetag.core.testing.without
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backup format 18 (#15, B2a rows 6–12; C8, C9; R15-6): two lists, `supplyItems` (each with its ordered
 * `specifications`) and `assetSupplies`, and one key, `supplyId`, appended to both material lines. No shipped writer
 * put any of them into a format ≤17 archive, so a row or a non-null link there is a hand-built file and is refused;
 * an explicit `"supplyId": null`, or no key at all, is what this build reads anyway. A link always resolves inside the
 * file (no foreign key holds it), and an archived SupplyItem is still a valid target. The names are fictional.
 */
class BackupFormat18Test {

    private val estate = SupplyEstate.data()

    private fun corrupt(data: BackupData, formatVersion: Int = BackupCodec.FORMAT_VERSION): BackupCorrupt =
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(archiveOf(data, formatVersion)) }

    private fun assertNames(refusal: BackupCorrupt, vararg words: String) {
        val message = refusal.message!!
        words.forEach { assertTrue(it in message, "\"$it\" missing from: $message") }
    }

    /** [tree] with every material line of both lists rewritten by [edit]. */
    private fun JsonObject.editLines(edit: (JsonObject) -> JsonObject): JsonObject =
        editRows("eventProfiles") { p -> p.with("consumables", JsonArray(p.getValue("consumables").jsonArray.map { edit(it.jsonObject) })) }
            .editRows("assetEvents") { e -> e.with("consumables", JsonArray(e.getValue("consumables").jsonArray.map { edit(it.jsonObject) })) }

    /** The estate with no supply row and no link: what a format-17 writer could have produced. */
    private val unlinked = estate.copy(
        supplyItems = emptyList(),
        assetSupplies = emptyList(),
        eventProfiles = estate.eventProfiles.map { p -> p.copy(consumables = p.consumables.map { it.copy(supplyId = null) }) },
        assetEvents = estate.assetEvents.map { e -> e.copy(consumables = e.consumables.map { it.copy(supplyId = null) }) },
    )

    private val SerialDescriptor.names: List<String> get() = elementNames.toList()

    // --- row 6: the round trip -------------------------------------------------------------------

    /**
     * Hazard: a row, a specification or a link dropped in transit. Every SupplyItem with its specifications, every
     * applicability row and every line link reads back equal; an unlinked line is written as an explicit
     * `"supplyId": null`, never left out, as the last key of the row.
     */
    @Test
    fun everySupplyRowSpecificationAndLinkRoundTrips() {
        val bytes = archiveOf(estate)

        val decoded = BackupCodec.decode(bytes)

        assertEquals(18, decoded.manifest.formatVersion)
        assertEquals(listOf(SupplyEstate.prefilter, SupplyEstate.membrane), decoded.data.supplyItems.map { it.toDomain() })
        assertEquals(
            listOf(SupplyEstate.prefilterOnSystem, SupplyEstate.membraneOnSoftener),
            decoded.data.assetSupplies.map { it.toDomain() },
        )
        assertEquals(listOf(SupplyEstate.quickAction), decoded.data.eventProfiles.map { it.toDomain() })
        assertEquals(listOf(SupplyEstate.change), decoded.data.assetEvents.map { it.toDomain() })

        val tree = dataTreeOf(bytes)
        val profileLines = tree.getValue("eventProfiles").jsonArray.single().jsonObject.getValue("consumables").jsonArray.map { it.jsonObject }
        val eventLines = tree.getValue("assetEvents").jsonArray.single().jsonObject.getValue("consumables").jsonArray.map { it.jsonObject }
        assertEquals("s1", profileLines[0].getValue("supplyId").jsonPrimitive.content)
        assertEquals(JsonNull, profileLines[1]["supplyId"], "an unlinked line is written as an explicit null")
        assertEquals(listOf("s1", "s2"), eventLines.take(2).map { it.getValue("supplyId").jsonPrimitive.content })
        assertEquals(JsonNull, eventLines[2]["supplyId"], "an unlinked line is written as an explicit null")
        (profileLines + eventLines).forEach { assertEquals("supplyId", it.keys.last(), "the link closes the row") }
    }

    // --- row 7: the fence, structurally ----------------------------------------------------------

    /**
     * Hazard: a field outside the HARD SCOPE (a quantity, a threshold, a URL, a position) slipping into the archive.
     * The three DTOs are exactly these keys, in column order, none with a default; the two lines append `supplyId`
     * last, the one key with a default; and the two lists close `data.json`.
     */
    @Test
    fun theSupplyDtosCarryExactlyTheseKeys() {
        assertEquals(
            listOf(
                "id", "name", "category", "manufacturer", "model", "partNumber", "preferredUnit", "notes",
                "archivedAt", "createdAt", "updatedAt", "specifications",
            ),
            SupplyItemDto.serializer().descriptor.names,
        )
        assertEquals(
            listOf("id", "key", "label", "value", "unit", "sortOrder"),
            SupplySpecificationDto.serializer().descriptor.names,
        )
        assertEquals(
            listOf("id", "assetId", "supplyId", "role", "createdAt", "updatedAt"),
            AssetSupplyDto.serializer().descriptor.names,
        )
        for (descriptor in listOf(
            SupplyItemDto.serializer().descriptor,
            SupplySpecificationDto.serializer().descriptor,
            AssetSupplyDto.serializer().descriptor,
        )) {
            assertTrue((0 until descriptor.elementsCount).none { descriptor.isElementOptional(it) }, "${descriptor.serialName} has no default")
        }
        assertEquals(listOf("id", "name", "defaultQuantity", "unit", "sortOrder", "supplyId"), ProfileConsumableDto.serializer().descriptor.names)
        assertEquals(listOf("id", "name", "quantity", "unit", "sortOrder", "supplyId"), ConsumableUsageDto.serializer().descriptor.names)
        assertEquals(listOf("supplyItems", "assetSupplies"), BackupData.serializer().descriptor.names.takeLast(2))
    }

    // --- row 8: the format ≤ 17 gate -------------------------------------------------------------

    /** The shipped shape before #15: neither list and no `supplyId` key at all. It reads with none, through the strict decode. */
    @Test
    fun aFormat17ArchiveDecodesWithNoSupplies() {
        for (format in listOf(17, 12, 8)) {
            val absent = sealed(
                dataTreeOf(archiveOf(unlinked)).without("supplyItems", "assetSupplies").editLines { it.without("supplyId") },
                formatVersion = format,
            )
            assertTrue("supplyId" !in dataJsonOf(absent), "the fixture carries no key")

            val decoded = BackupCodec.decode(absent)

            assertEquals(format, decoded.manifest.formatVersion)
            assertEquals(emptyList(), decoded.data.supplyItems, "format $format")
            assertEquals(emptyList(), decoded.data.assetSupplies, "format $format")
            assertTrue(decoded.data.eventProfiles.single().consumables.all { it.supplyId == null })
            assertTrue(decoded.data.assetEvents.single().consumables.all { it.supplyId == null })
        }
    }

    @Test
    fun aFormat17ArchiveCarryingSupplyItemsIsCorrupt() {
        val refusal = corrupt(unlinked.copy(supplyItems = estate.supplyItems), formatVersion = 17)

        assertNames(refusal, "supplyItems", "format 17")
    }

    @Test
    fun aFormat17ArchiveCarryingAssetSuppliesIsCorrupt() {
        val refusal = corrupt(unlinked.copy(assetSupplies = estate.assetSupplies), formatVersion = 17)

        assertNames(refusal, "assetSupplies", "format 17")
    }

    /** A non-null link on any line of an older archive is refused naming its quick action or its event. */
    @Test
    fun aFormat17LineCarryingALinkIsCorruptNamingItsRow() {
        val onProfile = corrupt(unlinked.copy(eventProfiles = estate.eventProfiles), formatVersion = 17)
        assertNames(onProfile, "eventProfiles", "format 17", "supply link", "p1")

        val onEvent = corrupt(unlinked.copy(assetEvents = estate.assetEvents), formatVersion = 17)
        assertNames(onEvent, "assetEvents", "format 17", "supply link", "e1")
    }

    @Test
    fun anExplicitNullLinkInAFormat17ArchiveIsAccepted() {
        val bytes = archiveOf(unlinked, formatVersion = 17)
        assertEquals(5, dataTreeOf(bytes).toString().split("\"supplyId\":null").size - 1, "every line writes an explicit null")

        val decoded = BackupCodec.decode(bytes)

        assertEquals(unlinked.eventProfiles, decoded.data.eventProfiles)
        assertEquals(unlinked.assetEvents, decoded.data.assetEvents)
    }

    // --- row 9: the graph ------------------------------------------------------------------------

    /** R15-6: a link always resolves — there is no foreign key, so the codec holds it, on both lines. */
    @Test
    fun aDanglingLineLinkIsCorrupt() {
        val onProfile = corrupt(estate.copy(eventProfiles = estate.eventProfiles.map { p ->
            p.copy(consumables = p.consumables.map { if (it.id == "pc1") it.copy(supplyId = "s9") else it })
        }))
        assertNames(onProfile, "eventProfiles", "pc1", "s9")

        val onEvent = corrupt(estate.copy(assetEvents = estate.assetEvents.map { e ->
            e.copy(consumables = e.consumables.map { if (it.id == "cu3") it.copy(supplyId = "s9") else it })
        }))
        assertNames(onEvent, "assetEvents", "cu3", "s9")
    }

    @Test
    fun applicabilityNamingAnUnknownAssetIsCorrupt() {
        val refusal = corrupt(estate.copy(assetSupplies = listOf(assetSupplyOf("as1", "x9", "s1").toDto())))

        assertNames(refusal, "assetSupplies", "as1", "x9")
    }

    @Test
    fun applicabilityNamingAnUnknownSupplyItemIsCorrupt() {
        val refusal = corrupt(estate.copy(assetSupplies = listOf(assetSupplyOf("as1", "x1", "s9").toDto())))

        assertNames(refusal, "assetSupplies", "as1", "s9")
    }

    /** A specification id is a row id: unique across every SupplyItem in the file, as a group member's is. */
    @Test
    fun aSpecificationIdUsedByTwoItemsIsCorrupt() {
        val twin = supplyItemOf("s3", "Example Carbon Block", specifications = listOf(specificationOf("sp1", "length", "Length", "20", "in")))

        val refusal = corrupt(estate.copy(supplyItems = estate.supplyItems + twin.toDto()))

        assertNames(refusal, "supplySpecifications", "sp1")
    }

    @Test
    fun duplicateSupplyAndApplicabilityIdsAreCorrupt() {
        assertNames(corrupt(estate.copy(supplyItems = estate.supplyItems + estate.supplyItems.first().copy(specifications = emptyList()))), "supplyItems", "s1")
        assertNames(corrupt(estate.copy(assetSupplies = estate.assetSupplies + estate.assetSupplies.first().copy(role = "Spare"))), "assetSupplies", "as1")
    }

    /** R15-6: an archived SupplyItem stays a valid target for an applicability row and for a line. */
    @Test
    fun aLinkOrRowNamingAnArchivedItemIsValid() {
        check(SupplyEstate.membrane.archivedAt != null)
        check(estate.assetSupplies.any { it.supplyId == "s2" } && estate.assetEvents.single().consumables.any { it.supplyId == "s2" })

        val decoded = BackupCodec.decode(archiveOf(estate))

        assertEquals(estate.assetSupplies, decoded.data.assetSupplies)
        assertEquals(estate.assetEvents, decoded.data.assetEvents)
    }

    // --- row 10: the content ---------------------------------------------------------------------

    private fun withPrefilter(edit: (SupplyItemDto) -> SupplyItemDto) =
        estate.copy(supplyItems = estate.supplyItems.map { if (it.id == "s1") edit(it) else it })

    private fun withSpec(id: String, edit: (SupplySpecificationDto) -> SupplySpecificationDto) =
        withPrefilter { item -> item.copy(specifications = item.specifications.map { if (it.id == id) edit(it) else it }) }

    @Test
    fun aBlankNameIsCorrupt() {
        assertNames(corrupt(withPrefilter { it.copy(name = "  ") }), "supplyItems", "s1", "name")
    }

    @Test
    fun aBlankSpecLabelOrValueIsCorrupt() {
        assertNames(corrupt(withSpec("sp2") { it.copy(label = " ") }), "supplyItems", "s1", "sp2", "label")
        assertNames(corrupt(withSpec("sp2") { it.copy(value = "") }), "supplyItems", "s1", "sp2", "value")
    }

    @Test
    fun aSpecKeyOutsideThePatternIsCorrupt() {
        for (key in listOf("Length", "1length", "", "length-in", "k".repeat(41))) {
            assertNames(corrupt(withSpec("sp1") { it.copy(key = key) }), "supplyItems", "s1", "sp1", "key")
        }
    }

    @Test
    fun aDuplicateSpecKeyIsCorrupt() {
        assertNames(corrupt(withSpec("sp2") { it.copy(key = "length") }), "supplyItems", "s1", "sp1", "sp2", "length")
    }

    /** The role is stored cleaned by `CategoryKey.display` (R15-3); a blank or an uncleaned one is a hand-built row. */
    @Test
    fun anUncleanedRoleIsCorrupt() {
        for (role in listOf("Prefilter ", " Prefilter", "Pre  filter", "Pre filter")) {
            val refusal = corrupt(estate.copy(assetSupplies = estate.assetSupplies.map { if (it.id == "as1") it.copy(role = role) else it }))
            assertNames(refusal, "assetSupplies", "as1", "role")
        }
        val blank = corrupt(estate.copy(assetSupplies = estate.assetSupplies.map { if (it.id == "as1") it.copy(role = " ") else it }))
        assertNames(blank, "assetSupplies", "as1", "blank role")
    }

    @Test
    fun aDuplicateTripleIsCorrupt() {
        val again = assetSupplyOf("as3", "x1", "s1", "Prefilter").toDto()

        assertNames(corrupt(estate.copy(assetSupplies = estate.assetSupplies + again)), "assetSupplies", "as1", "as3")
    }

    // --- row 11: sort and counts -----------------------------------------------------------------

    /**
     * Hazard: non-deterministic bytes. Items and applicability rows are written by id, and each item's
     * specifications by `(sortOrder, id)` — the group members' rule — whatever order they were handed over in.
     */
    @Test
    fun specificationsAreWrittenInSortOrderThenId() {
        val shuffled = supplyItemOf(
            "s1",
            specifications = listOf(
                specificationOf("sp9", "micron_rating", "Micron rating", "5", sortOrder = 1),
                specificationOf("sp5", "width", "Width", "3", sortOrder = 0),
                specificationOf("sp4", "length", "Length", "10", sortOrder = 0),
            ),
        )
        val data = estate.copy(
            supplyItems = listOf(SupplyEstate.membrane.toDto(), shuffled.toDto()),
            assetSupplies = estate.assetSupplies.reversed(),
        )
        check(data.supplyItems.map { it.id } == listOf("s2", "s1") && data.assetSupplies.map { it.id } == listOf("as2", "as1"))

        val tree = dataTreeOf(archiveOf(data))

        val items = tree.getValue("supplyItems").jsonArray.map { it.jsonObject }
        assertEquals(listOf("s1", "s2"), items.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(
            listOf("sp4", "sp5", "sp9"),
            items.first().getValue("specifications").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content },
        )
        assertEquals(listOf("as1", "as2"), tree.getValue("assetSupplies").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }

    /** Both lists are counted, and the specifications beside them exactly as group members are: three keys, 29 in all. */
    @Test
    fun theManifestCountsBothLists() {
        val counts = BackupCodec.decode(archiveOf(estate)).manifest.counts

        assertEquals(2, counts["supplyItems"])
        assertEquals(3, counts["supplySpecifications"])
        assertEquals(2, counts["assetSupplies"])
        assertEquals(29, counts.size)
        val none = BackupCodec.decode(archiveOf(unlinked)).manifest.counts
        assertEquals(listOf(0, 0, 0), listOf("supplyItems", "supplySpecifications", "assetSupplies").map { none[it] }, "present at zero")
    }

    // --- row 12: newer refused -------------------------------------------------------------------

    /** One format past this build's, over a tree no format could read: refused as newer before a row is parsed. */
    @Test
    fun aFormat19ArchiveIsRefusedAsNewer() {
        val unreadable = dataTreeOf(archiveOf(estate)).editRows("supplyItems") { it.with("name", JsonNull) }
        val bytes = sealed(unreadable, formatVersion = 19)

        val refusal = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(bytes) }

        assertEquals(19, refusal.found)
        assertEquals(18, refusal.supported)
    }
}
