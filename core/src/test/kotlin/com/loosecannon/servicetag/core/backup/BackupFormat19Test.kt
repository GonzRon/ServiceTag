package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.compositionEntryOf
import com.loosecannon.servicetag.core.testing.dataJsonOf
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backup format 19 (#47, B2a rows 10–16; C9, C10; R47-2, R47-3, R47-17a): one list, `installedComponents`, each row
 * with its `composition` nested. No shipped writer put a row into a format ≤18 archive, so one there is a hand-built
 * file and is refused. A row's asset, parent, replaced row and every SupplyItem it names resolve inside the file, and
 * an archived SupplyItem is still a valid target. The names are fictional.
 */
class BackupFormat19Test {

    private val ups = plainAssetOf("x1", "Example UPS")
    private val system = plainAssetOf("x2", "Example RO System")

    private val battery = supplyItemOf("s1", "Example 12 V Battery")
    private val packSku = supplyItemOf("s2", "Example Battery Pack", archivedAt = 3_000L)
    private val strap = supplyItemOf("s3", "Example Terminal Strap", archivedAt = 3_000L)

    // The instance-level form: a tray with positions, position 2 replaced in place.
    private val tray = installedComponentOf("c1", name = "Example Battery Tray", installedOn = "2026-01-10")
    private val position1 = installedComponentOf(
        "c2", name = "Position 1", parentId = "c1", supplyId = "s1", serialOrLot = "LOT-EX-0001", installedOn = "2026-01-10",
    )
    private val position2Before = installedComponentOf(
        "c3", name = "Position 2", parentId = "c1", supplyId = "s1", installedOn = "2026-01-10", removedOn = "2026-06-01",
        sortOrder = 1,
    )
    private val position2 = installedComponentOf(
        "c4", name = "Position 2", parentId = "c1", supplyId = "s1", installedOn = "2026-06-01", replacesId = "c3",
        sortOrder = 1,
    )

    // The aggregate form: a pack made of 4 × the battery, replaced whole; its archived SKU and strap stay valid.
    private val packBefore = installedComponentOf(
        "c5", name = "Example Battery Pack", supplyId = "s2",
        composition = listOf(
            compositionEntryOf("e1", "s1", 4.0, "ea", 0), compositionEntryOf("e2", "s3", 2.0, "ea", 1),
            compositionEntryOf("e4", "s1", 1.0, "ea", 2),
        ),
        installedOn = "2025-03-01", removedOn = "2026-05-01", sortOrder = 2,
    )
    private val pack = installedComponentOf(
        "c6", name = "Example Battery Pack", supplyId = "s2", composition = listOf(compositionEntryOf("e3", "s1", 4.0, "ea", 0)),
        replacesId = "c5", sortOrder = 2, notes = "Example note",
    )

    // Every nullable key null and no composition, on the second asset.
    private val housing = installedComponentOf("c7", assetId = "x2", name = "Example Membrane Housing")

    private val rows = listOf(tray, position1, position2Before, position2, packBefore, pack, housing)

    private fun data(components: List<InstalledComponent> = rows): BackupData = BackupData(
        listOf(ups, system).map { it.toDto() }, emptyList(), emptyList(),
        supplyItems = listOf(battery, packSku, strap).map { it.toDto() },
        installedComponents = components.map { it.toDto() },
    )

    /** [rows] with the row [id] rewritten by [edit]. */
    private fun edited(id: String, edit: (InstalledComponent) -> InstalledComponent) =
        rows.map { if (it.id.value == id) edit(it) else it }

    private fun corrupt(components: List<InstalledComponent>, formatVersion: Int = BackupCodec.FORMAT_VERSION): BackupCorrupt =
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(archiveOf(data(components), formatVersion)) }

    private fun assertNames(refusal: BackupCorrupt, vararg words: String) {
        val message = refusal.message!!
        words.forEach { assertTrue(it in message, "\"$it\" missing from: $message") }
    }

    private val SerialDescriptor.names: List<String> get() = elementNames.toList()

    // --- row 10: the round trip ------------------------------------------------------------------

    /**
     * Hazard: a row, an entry, a pointer or a date dropped in transit. Every row reads back equal — its parent, its
     * direct link, its composition in order, its serial or lot, both dates, the row it replaces — and a row with none
     * of them writes every key, the nulls explicitly, the empty composition as `[]`. The list closes `data.json`.
     */
    @Test
    fun everyRowEntryPointerAndDateRoundTrips() {
        val bytes = archiveOf(data())

        val decoded = BackupCodec.decode(bytes)

        assertEquals(20, decoded.manifest.formatVersion)
        assertEquals(rows, decoded.data.installedComponents.map { it.toDomain() })
        val tree = dataTreeOf(bytes)
        assertEquals("installedComponents", tree.keys.last(), "the list closes data.json")
        val bare = tree.getValue("installedComponents").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "c7" }
        assertEquals(InstalledComponentDto.serializer().descriptor.names, bare.keys.toList(), "every key is written")
        for (key in listOf("parentId", "supplyId", "installedOn", "removedOn", "replacesId")) {
            assertEquals(JsonNull, bare[key], "$key is written as an explicit null")
        }
        assertEquals(JsonArray(emptyList()), bare["composition"])
    }

    // --- row 11: the fence, structurally ---------------------------------------------------------

    /**
     * Hazard: a field outside the HARD SCOPE slipping into the archive. Both DTOs are exactly these keys, in column
     * order, none with a default, the composition nested in its row; the one list with a default closes `BackupData`.
     */
    @Test
    fun theDtosCarryExactlyTheseKeys() {
        assertEquals(
            listOf(
                "id", "assetId", "parentId", "name", "supplyId", "composition", "serialOrLot", "installedOn", "removedOn",
                "replacesId", "sortOrder", "notes", "createdAt", "updatedAt",
            ),
            InstalledComponentDto.serializer().descriptor.names,
        )
        assertEquals(listOf("id", "supplyId", "quantity", "unit", "sortOrder"), CompositionEntryDto.serializer().descriptor.names)
        for (descriptor in listOf(InstalledComponentDto.serializer().descriptor, CompositionEntryDto.serializer().descriptor)) {
            assertTrue((0 until descriptor.elementsCount).none { descriptor.isElementOptional(it) }, "${descriptor.serialName} has no default")
        }
        val backup = BackupData.serializer().descriptor
        assertEquals("installedComponents", backup.names.last())
        assertTrue(backup.isElementOptional(backup.elementsCount - 1), "an older archive decodes with none")
    }

    // --- row 12: the format ≤ 18 gate ------------------------------------------------------------

    /** The shipped shape before #47: no list at all. It reads with none, through the strict decode. */
    @Test
    fun aFormat18ArchiveDecodesWithNone() {
        for (format in listOf(18, 12, 8)) {
            val tree = dataTreeOf(archiveOf(data(emptyList()))).without("installedComponents")
            val older = if (format < 18) tree.without("supplyItems", "assetSupplies") else tree
            val absent = sealed(older, formatVersion = format)
            assertTrue("installedComponents" !in dataJsonOf(absent), "the fixture carries no list")

            val decoded = BackupCodec.decode(absent)

            assertEquals(format, decoded.manifest.formatVersion)
            assertEquals(emptyList(), decoded.data.installedComponents, "format $format")
        }
    }

    @Test
    fun aFormat18ArchiveCarryingRowsIsCorrupt() {
        assertNames(corrupt(rows, formatVersion = 18), "installedComponents", "format 18")
    }

    @Test
    fun anEmptyListInAFormat18ArchiveIsAccepted() {
        val decoded = BackupCodec.decode(archiveOf(data(emptyList()), formatVersion = 18))

        assertEquals(18, decoded.manifest.formatVersion)
        assertEquals(emptyList(), decoded.data.installedComponents)
    }

    // --- row 13: the graph -----------------------------------------------------------------------

    @Test
    fun anUnknownAssetIsCorrupt() {
        assertNames(corrupt(edited("c7") { it.copy(assetId = AssetId("x9")) }), "installedComponents", "c7", "x9")
    }

    @Test
    fun anUnknownParentIsCorrupt() {
        assertNames(corrupt(edited("c2") { it.copy(parentId = InstalledComponentId("c9")) }), "installedComponents", "c2", "c9")
    }

    /** One tree per asset: a parent on another asset is refused, though every id resolves. */
    @Test
    fun aParentOnAnotherAssetIsCorrupt() {
        assertNames(corrupt(edited("c7") { it.copy(parentId = tray.id) }), "installedComponents", "c7", "c1", "another asset")
    }

    @Test
    fun anUnknownDirectSupplyItemIsCorrupt() {
        assertNames(corrupt(edited("c2") { it.copy(supplyId = SupplyId("s9")) }), "installedComponents", "c2", "s9")
    }

    @Test
    fun anUnknownEntrySupplyItemIsCorrupt() {
        val refusal = corrupt(edited("c6") { it.copy(composition = listOf(compositionEntryOf("e3", "s9", 4.0))) })

        assertNames(refusal, "installedComponents", "c6", "e3", "s9")
    }

    /** An entry id is a row id: unique across every installed component in the file, as a specification's is. */
    @Test
    fun anEntryIdOnTwoRowsIsCorrupt() {
        val refusal = corrupt(edited("c6") { it.copy(composition = listOf(compositionEntryOf("e1", "s1", 4.0))) })

        assertNames(refusal, "compositionEntries", "e1")
    }

    @Test
    fun aDuplicateRowIdIsCorrupt() {
        assertNames(corrupt(rows + housing.copy(name = "Example Second Housing")), "installedComponents", "c7")
    }

    @Test
    fun anUnknownReplacedRowIsCorrupt() {
        assertNames(corrupt(edited("c4") { it.copy(replacesId = InstalledComponentId("c9")) }), "installedComponents", "c4", "c9")
    }

    @Test
    fun aReplacedRowOnAnotherAssetIsCorrupt() {
        val refusal = corrupt((rows - pack).map { if (it == housing) it.copy(replacesId = packBefore.id) else it })

        assertNames(refusal, "installedComponents", "c7", "c5", "another asset")
    }

    @Test
    fun aRowReplacingItselfIsCorrupt() {
        assertNames(corrupt(edited("c4") { it.copy(replacesId = it.id) }), "installedComponents", "c4", "itself")
    }

    @Test
    fun aReplacedRowThatIsCurrentIsCorrupt() {
        assertNames(corrupt(edited("c4") { it.copy(replacesId = position1.id) }), "installedComponents", "c4", "c2", "not removed")
    }

    @Test
    fun aRowReplacedTwiceIsCorrupt() {
        val again = position2.copy(id = InstalledComponentId("c8"))

        assertNames(corrupt(rows + again), "installedComponents", "c8", "c3", "c4")
    }

    /** N-9: one position — a successor sits where its predecessor sat, under the same parent. */
    @Test
    fun aSuccessorUnderAnotherParentIsCorrupt() {
        assertNames(corrupt(edited("c4") { it.copy(parentId = null) }), "installedComponents", "c4", "c3", "another parent")
    }

    @Test
    fun aCurrentRowInsideARemovedParentIsCorrupt() {
        assertNames(corrupt(edited("c2") { it.copy(parentId = position2Before.id) }), "installedComponents", "c2", "c3", "removed")
    }

    @Test
    fun aParentCycleIsCorrupt() {
        val loop = listOf(
            installedComponentOf("c8", assetId = "x2", name = "Example Filter Head", parentId = "c9"),
            installedComponentOf("c9", assetId = "x2", name = "Example Filter Bowl", parentId = "c8"),
        )

        assertNames(corrupt(rows + loop), "installedComponents", "cycle")
    }

    /** Each row is replaced at most once, so a chain that comes back round is a loop; the walk is the codec's own. */
    @Test
    fun aReplacementCycleIsCorrupt() {
        val loop = listOf(
            installedComponentOf("c8", assetId = "x2", name = "Example Filter Bowl", removedOn = "2026-02-01", replacesId = "c9"),
            installedComponentOf("c9", assetId = "x2", name = "Example Filter Bowl", removedOn = "2026-03-01", replacesId = "c8"),
        )

        assertNames(corrupt(rows + loop), "installedComponents", "c8", "replacement cycle")
    }

    /** R47-3: an archived SupplyItem stays a valid target for a direct link and for an entry. */
    @Test
    fun anArchivedSupplyItemIsAValidTarget() {
        check(packSku.archivedAt != null && strap.archivedAt != null)
        check(pack.supplyId == packSku.id && packBefore.composition.any { it.supplyId == strap.id })

        val decoded = BackupCodec.decode(archiveOf(data()))

        assertEquals(data().installedComponents, decoded.data.installedComponents)
    }

    // --- row 14: the content ---------------------------------------------------------------------

    @Test
    fun aBlankNameIsCorrupt() {
        assertNames(corrupt(edited("c1") { it.copy(name = "  ") }), "installedComponents", "c1", "NameRequired")
    }

    @Test
    fun aDateThatIsNotIsoIsCorrupt() {
        assertNames(corrupt(edited("c7") { it.copy(installedOn = "2026-13-40") }), "installedComponents", "c7", "BadDate", "installedOn")
        assertNames(corrupt(edited("c3") { it.copy(removedOn = "01/06/2026") }), "installedComponents", "c3", "BadDate", "removedOn")
    }

    @Test
    fun aRemovalBeforeTheInstallIsCorrupt() {
        assertNames(corrupt(edited("c3") { it.copy(removedOn = "2025-12-31") }), "installedComponents", "c3", "RemovedBeforeInstalled")
    }

    /** The entry is named by its index in the row's composition, as the commands report it. */
    @Test
    fun aZeroQuantityEntryIsCorrupt() {
        val zero = corrupt(edited("c6") { it.copy(composition = listOf(compositionEntryOf("e3", "s1", 0.0))) })
        assertNames(zero, "installedComponents", "c6", "QuantityInvalid(index=0)")

        val negative = corrupt(edited("c5") { it.copy(composition = it.composition.map { e -> if (e.id == "e2") e.copy(quantity = -2.0) else e }) })
        assertNames(negative, "installedComponents", "c5", "QuantityInvalid(index=1)")
    }

    /** No today: a restore never judges a row by the importing phone's date. */
    @Test
    fun aFutureDateIsAcceptedOnRestore() {
        val future = edited("c7") { it.copy(installedOn = "2999-01-01") }
            .map { if (it.id.value == "c3") it.copy(removedOn = "2999-06-01") else it }

        val decoded = BackupCodec.decode(archiveOf(data(future)))

        assertEquals(future, decoded.data.installedComponents.map { it.toDomain() })
    }

    // --- row 15: sort and counts -----------------------------------------------------------------

    /**
     * Hazard: non-deterministic bytes. Rows are written by id, and each composition by `(sortOrder, id)` — the
     * specifications' rule — whatever order they were handed over in.
     */
    @Test
    fun rowsByIdEntriesBySortOrderThenId() {
        val shuffled = packBefore.copy(
            composition = listOf(
                compositionEntryOf("e9", "s1", 1.0, sortOrder = 1),
                compositionEntryOf("e5", "s3", 2.0, sortOrder = 0),
                compositionEntryOf("e4", "s1", 4.0, sortOrder = 0),
            ),
        )
        val handed = rows.map { if (it == packBefore) shuffled else it }.reversed()
        check(handed.first() == housing)

        val tree = dataTreeOf(archiveOf(data(handed)))

        val written = tree.getValue("installedComponents").jsonArray.map { it.jsonObject }
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "c6", "c7"), written.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(
            listOf("e4", "e5", "e9"),
            written.single { it.getValue("id").jsonPrimitive.content == "c5" }.getValue("composition").jsonArray
                .map { it.jsonObject.getValue("id").jsonPrimitive.content },
        )
    }

    /** The rows are counted, and the entries beside them as specifications are: two keys, 31 in all. */
    @Test
    fun theManifestCountsRowsAndEntries() {
        val counts = BackupCodec.decode(archiveOf(data())).manifest.counts

        assertEquals(7, counts["installedComponents"])
        assertEquals(4, counts["compositionEntries"])
        assertEquals(31, counts.size)
        val none = BackupCodec.decode(archiveOf(data(emptyList()))).manifest.counts
        assertEquals(listOf(0, 0), listOf("installedComponents", "compositionEntries").map { none[it] }, "present at zero")
    }

    // --- row 16: newer refused -------------------------------------------------------------------

    /** One format past this build's, over a tree no format could read: refused as newer before a row is parsed. */
    @Test
    fun aFormat21ArchiveIsRefusedAsNewer() {
        val unreadable = dataTreeOf(archiveOf(data())).editRows("installedComponents") { it.with("name", JsonNull) }
        val bytes = sealed(unreadable, formatVersion = 21)

        val refusal = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(bytes) }

        assertEquals(21, refusal.found)
        assertEquals(20, refusal.supported)
    }
}
