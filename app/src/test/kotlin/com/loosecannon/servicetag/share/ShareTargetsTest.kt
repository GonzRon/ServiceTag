package com.loosecannon.servicetag.share

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #69 row 53b, the pure half: what Share's Installed components and Supplies lists hold before and after a query,
 * the line each row draws, their order, and whether an empty list is "nothing of this type" or a miss. Fixtures are
 * fictional.
 */
class ShareTargetsTest {

    private val ups = asset("asset-ups", "Example UPS")
    private val generator = asset("asset-generator", "Example Generator")

    private fun asset(id: String, name: String, status: AssetStatus = AssetStatus.ACTIVE, retiredOn: String? = null) =
        Asset(id = AssetId(id), name = name, status = status, retiredOn = retiredOn, createdAt = 1L, updatedAt = 1L)

    private fun component(
        id: String,
        on: Asset,
        name: String,
        parent: String? = null,
        removedOn: String? = null,
        replaces: String? = null,
        sortOrder: Int = 0,
    ) = InstalledComponent(
        id = InstalledComponentId(id), assetId = on.id, parentId = parent?.let(::InstalledComponentId), name = name,
        supplyId = null, composition = emptyList(), serialOrLot = "", installedOn = null, removedOn = removedOn,
        replacesId = replaces?.let(::InstalledComponentId), sortOrder = sortOrder, notes = "", createdAt = 1L,
        updatedAt = 1L,
    )

    private fun supply(
        id: String,
        name: String,
        manufacturer: String = "",
        model: String = "",
        partNumber: String = "",
        category: String = "",
        notes: String = "",
        archivedAt: Long? = null,
    ) = SupplyItem(
        id = SupplyId(id), name = name, category = category, manufacturer = manufacturer, model = model,
        partNumber = partNumber, preferredUnit = "", notes = notes, archivedAt = archivedAt, createdAt = 1L,
        updatedAt = 1L, specifications = emptyList(),
    )

    private fun <T> rowsOf(list: ShareTargetList<T>): List<T> = when (list) {
        ShareTargetList.NoneEligible -> throw AssertionError("expected rows, was NoneEligible")
        ShareTargetList.NothingMatches -> throw AssertionError("expected rows, was NothingMatches")
        is ShareTargetList.Rows -> list.rows
    }

    private fun componentIds(
        assets: List<Asset>,
        components: List<InstalledComponent>,
        query: String = "",
        held: Set<AssetId> = emptySet(),
    ): List<String> = rowsOf(componentList(assets, held, components, query)).map { it.componentId.value }

    private fun supplyIds(items: List<SupplyItem>, query: String = ""): List<String> =
        rowsOf(supplyList(items, query)).map { it.supplyId.value }

    // --- eligibility ---

    @Test fun eachListHoldsItsOwnTypeOnly() {
        // One id string on all three types: each list answers with its own type's row, never another's.
        val sameId = "example-1"
        val asset = asset(sameId, "Example UPS")
        val tray = component(sameId, asset, "Example Battery Tray")
        val battery = supply(sameId, "Example 12 V Battery", manufacturer = "Example Power Co.", model = "EP-12")

        assertEquals(listOf(AssetId(sameId)), shareableAssets(listOf(asset), emptySet()).map { it.id })
        assertEquals(
            listOf(
                ComponentTarget(
                    AssetId(sameId),
                    InstalledComponentId(sameId),
                    listOf("Example UPS", "Example Battery Tray"),
                ),
            ),
            rowsOf(componentList(listOf(asset), emptySet(), listOf(tray), "")),
        )
        assertEquals(
            listOf(SupplyTarget(SupplyId(sameId), "Example 12 V Battery", "Example Power Co. · EP-12")),
            rowsOf(supplyList(listOf(battery), "")),
        )
    }

    @Test fun shareableAssetsAreTheOnesMaintainedHereInTheirOwnOrder() {
        val retired = asset("asset-alternator", "Example Alternator", retiredOn = "2026-01-05")
        val archived = asset("asset-pump", "Example Pump", status = AssetStatus.ARCHIVED)
        val lent = asset("asset-lent", "Example Lent UPS")

        val offered = shareableAssets(listOf(ups, retired, archived, lent, generator), held = setOf(lent.id))

        assertEquals(listOf(ups.id, generator.id), offered.map { it.id })
    }

    @Test fun componentsOfAnAssetNotMaintainedHereAreNeverOffered() {
        val retired = asset("asset-alternator", "Example Alternator", retiredOn = "2026-01-05")
        val archived = asset("asset-pump", "Example Pump", status = AssetStatus.ARCHIVED)
        val lent = asset("asset-lent", "Example Lent UPS")
        val unknown = asset("asset-unknown", "Example Unlisted")
        val rows = listOf(
            component("c-ups", ups, "Example Fan"),
            component("c-retired", retired, "Example Fan"),
            component("c-archived", archived, "Example Fan"),
            component("c-lent", lent, "Example Fan"),
            component("c-unknown", unknown, "Example Fan"),
        )

        val offered = componentIds(listOf(ups, retired, archived, lent), rows, held = setOf(lent.id))

        assertEquals(listOf("c-ups"), offered)
    }

    @Test fun removedAndReplacedComponentsAreNeverOffered() {
        val rows = listOf(
            component("c-fan", ups, "Example Fan", removedOn = "2026-02-01"),
            component("c-old", ups, "Example 12 V Battery", removedOn = "2026-03-01"),
            component("c-new", ups, "Example 12 V Battery", replaces = "c-old"),
            component("c-tray", ups, "Example Battery Tray"),
        )

        assertEquals(listOf("c-new", "c-tray"), componentIds(listOf(ups), rows))
    }

    @Test fun noEligibleComponentIsNoneEligibleWhateverTheQuery() {
        val rows = listOf(
            component("c-lent", ups, "Example Fan"),
            component("c-gone", generator, "Example Fan", removedOn = "2026-02-01"),
        )

        for (query in listOf("", "fan", "nothing like it")) {
            assertEquals(
                ShareTargetList.NoneEligible,
                componentList(listOf(ups, generator), setOf(ups.id), rows, query),
            )
        }
        assertEquals(ShareTargetList.NoneEligible, componentList(listOf(ups), emptySet(), emptyList(), ""))
    }

    @Test fun everyUnarchivedSupplyIsOfferedAndNoArchivedOne() {
        val battery = supply("s-battery", "Example 12 V Battery")
        val old = supply("s-old", "Example Old Filter", archivedAt = 5L)
        val tray = supply("s-tray", "Example Battery Tray")

        assertEquals(listOf("s-battery", "s-tray"), supplyIds(listOf(battery, old, tray)))
        assertTrue(battery.isShareable)
        assertFalse(old.isShareable)
    }

    @Test fun noUnarchivedSupplyIsNoneEligibleWhateverTheQuery() {
        val items = listOf(supply("s-old", "Example Old Filter", archivedAt = 5L))

        for (query in listOf("", "filter", "nothing like it")) {
            assertEquals(ShareTargetList.NoneEligible, supplyList(items, query))
        }
        assertEquals(ShareTargetList.NoneEligible, supplyList(emptyList(), ""))
    }

    @Test fun aQueryWithNoHitIsNothingMatchesOnEitherList() {
        val rows = listOf(component("c-tray", ups, "Example Battery Tray"))
        val items = listOf(supply("s-battery", "Example 12 V Battery"))

        assertEquals(ShareTargetList.NothingMatches, componentList(listOf(ups), emptySet(), rows, "alternator"))
        assertEquals(ShareTargetList.NothingMatches, supplyList(items, "alternator"))
    }

    // --- the component row ---

    @Test fun aComponentRowCarriesTheAssetEachAncestorAndThenItsOwnName() {
        val rows = listOf(
            component("c-tray", ups, "Example Battery Tray"),
            component("c-slot", ups, "Position 1", parent = "c-tray"),
            component("c-cell", ups, "Example 12 V Battery", parent = "c-slot"),
        )

        val cell = rowsOf(componentList(listOf(ups), emptySet(), rows, "")).single { it.componentId.value == "c-cell" }

        assertEquals(listOf("Example UPS", "Example Battery Tray", "Position 1", "Example 12 V Battery"), cell.path)
        assertEquals("Example 12 V Battery", cell.name)
        assertEquals(listOf("Example UPS", "Example Battery Tray", "Position 1"), cell.context)
        assertEquals(ups.id, cell.assetId)
    }

    @Test fun aCurrentRowUnderARemovedOrMissingParentIsARoot() {
        val rows = listOf(
            component("c-tray", ups, "Example Battery Tray", removedOn = "2026-02-01"),
            component("c-slot", ups, "Position 1", parent = "c-tray"),
            component("c-loose", ups, "Position 2", parent = "c-missing"),
        )

        val offered = rowsOf(componentList(listOf(ups), emptySet(), rows, ""))

        assertEquals(
            listOf(listOf("Example UPS", "Position 1"), listOf("Example UPS", "Position 2")),
            offered.map { it.path },
        )
    }

    // --- the order (C-11) ---

    @Test fun twoSiblingsSortByNameCasefoldedNotBySortOrder() {
        val rows = listOf(
            component("c1", ups, "Zeta", sortOrder = 0),
            component("c2", ups, "beta", sortOrder = 1),
            component("c3", ups, "Alpha", sortOrder = 2),
        )

        assertEquals(listOf("c3", "c2", "c1"), componentIds(listOf(ups), rows))
    }

    @Test fun pathsThatFoldEquallyFallBackToTheComponentId() {
        val rows = listOf(component("c5", ups, "Example Fan"), component("c4", ups, "example fan"))

        assertEquals(listOf("c4", "c5"), componentIds(listOf(ups), rows))
    }

    @Test fun theOrderIsSegmentBySegmentNeverTheJoinedLine() {
        // Joined by " › ", "Bay 10" would land between "Bay" and its children, and the name holding the separator
        // would tie "Bay"'s "Position 10" and then cut in by its id.
        val rows = listOf(
            component("c0", ups, "Bay › Position 10"),
            component("c1", ups, "Bay"),
            component("c2", ups, "Bay 10"),
            component("c3", ups, "Position 2", parent = "c1"),
            component("c4", ups, "Position 10", parent = "c1"),
        )

        assertEquals(listOf("c1", "c4", "c3", "c2", "c0"), componentIds(listOf(ups), rows))
    }

    @Test fun rowsOfTwoAssetsOrderByTheAssetsNameFirst() {
        val rows = listOf(component("c-ups", ups, "Alpha"), component("c-gen", generator, "Zeta"))

        assertEquals(listOf("c-gen", "c-ups"), componentIds(listOf(ups, generator), rows))
    }

    // --- the component matcher ---

    @Test fun theComponentMatcherHitsTheAssetAnAncestorAndItsOwnName() {
        val rows = listOf(
            component("c-tray", generator, "Example Battery Tray"),
            component("c-slot", generator, "Position 1", parent = "c-tray"),
            component("c-fan", ups, "Example Fan"),
        )
        val assets = listOf(ups, generator)

        assertEquals(listOf("c-tray", "c-slot"), componentIds(assets, rows, query = "battery tray"))
        assertEquals(listOf("c-tray", "c-slot"), componentIds(assets, rows, query = "generator"))
        assertEquals(listOf("c-slot"), componentIds(assets, rows, query = "position"))
        assertEquals(listOf("c-fan"), componentIds(assets, rows, query = "ups"))
    }

    @Test fun aQueryIsTrimmedAndCaseFreeAndABlankOneHitsEveryRow() {
        val rows = listOf(component("c-tray", ups, "Example Battery Tray"), component("c-fan", ups, "Example Fan"))

        assertEquals(listOf("c-tray"), componentIds(listOf(ups), rows, query = "  BATTERY tray "))
        assertEquals(listOf("c-tray", "c-fan"), componentIds(listOf(ups), rows, query = "   "))
        assertEquals(listOf("s-battery"), supplyIds(listOf(supply("s-battery", "Example 12 V Battery")), "  12 v "))
    }

    // --- the supply list ---

    @Test fun suppliesReadInTheSuppliesListsOrder() {
        val items = listOf(
            supply("s1", "Zeta Filter"),
            supply("s2", "beta Belt"),
            supply("s4", "Alpha Fuse"),
            supply("s3", "alpha fuse"),
        )

        assertEquals(listOf("s3", "s4", "s2", "s1"), supplyIds(items))
    }

    @Test fun theSupplyMatcherReadsNameManufacturerModelAndPartNumberOnly() {
        val battery = supply(
            "s-battery", "Example 12 V Battery", manufacturer = "Example Power Co.", model = "EP-12",
            partNumber = "EP-12-7", category = "Starter", notes = "Example note",
        )
        val items = listOf(battery, supply("s-tray", "Example Battery Tray"))

        assertEquals(listOf("s-battery"), supplyIds(items, "12 v"))
        assertEquals(listOf("s-battery"), supplyIds(items, "power co"))
        assertEquals(listOf("s-battery"), supplyIds(items, "ep-12"))
        assertEquals(listOf("s-battery"), supplyIds(items, "12-7"))
        assertEquals(ShareTargetList.NothingMatches, supplyList(items, "starter"))
        assertEquals(ShareTargetList.NothingMatches, supplyList(items, "note"))
    }

    @Test fun theProductLineOmitsEmptiesAndShowsAnEqualModelAndPartNumberOnce() {
        fun line(manufacturer: String, model: String, partNumber: String) =
            productLineOf(supply("s", "Example 12 V Battery", manufacturer, model, partNumber))

        assertEquals("Example Power Co. · EP-12 · EP-12-7", line("Example Power Co.", "EP-12", "EP-12-7"))
        assertEquals("Example Power Co. · EP-12-7", line("Example Power Co.", "", "EP-12-7"))
        assertEquals("EP-12 · EP-12-7", line(" ", "EP-12", "EP-12-7"))
        assertEquals("Example Power Co. · EP-12", line("Example Power Co.", "EP-12", "ep-12"))
        assertEquals("EP-12", line("", "EP-12", "EP-12"))
        assertEquals("EP-12-7", line("", "", "EP-12-7"))
        assertEquals("", line("", "", ""))
    }

    @Test fun aSupplyRowCarriesItsIdNameAndProductLine() {
        val battery =
            supply("s-battery", "Example 12 V Battery", manufacturer = "Example Power Co.", partNumber = "EP-12-7")

        assertEquals(
            SupplyTarget(SupplyId("s-battery"), "Example 12 V Battery", "Example Power Co. · EP-12-7"),
            supplyTargetOf(battery),
        )
    }
}
