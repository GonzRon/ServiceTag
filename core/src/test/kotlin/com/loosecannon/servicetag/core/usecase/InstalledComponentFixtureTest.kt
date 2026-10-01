package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.merge.MergeTable
import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.InstalledComponentTree
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.transfer.HeldWriteGuard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #47 (B3b) — one install as `AppGraph` builds it for installed components: [LinkageInstall]'s canonical stores and
 * #15's use cases, and the four installed component use cases over the guarded port. Ids are minted as
 * [LinkageInstall] mints them, `<prefix>-1`, `<prefix>-2`, …; every date is on or before [today].
 */
internal class FittingInstall(prefix: String = "id", today: String = "2026-03-01") {
    val home = LinkageInstall(prefix)
    val raw: BackupInstall get() = home.raw
    private val todayPort = Today { LocalDate.parse(today) }
    private val guarded = HeldWriteGuard(
        raw.transfers, raw.events, raw.definitions, raw.profiles, raw.groups, raw.schedules, raw.serviceCases, raw.links,
    ).installedComponents(raw.installedComponents)
    val install = InstallComponent(raw.assets, raw.supplyItems, guarded, raw.uow, home.ids, home.clock, todayPort)
    val remove = RemoveInstalledComponent(guarded, raw.uow, home.clock, todayPort)
    val replace = ReplaceInstalledComponent(raw.supplyItems, guarded, raw.uow, home.ids, home.clock, todayPort)
    val update = UpdateInstalledComponent(raw.supplyItems, guarded, raw.uow, home.ids, home.clock, todayPort)

    /** Fits [name] in [assetId], inside [parentId] when given, after its current siblings; the row as stored. */
    suspend fun fit(
        assetId: AssetId,
        name: String,
        parentId: InstalledComponentId? = null,
        supplyId: SupplyId? = null,
        composition: List<CompositionInput> = emptyList(),
        serialOrLot: String = "",
        installedOn: String? = "2026-01-10",
    ): InstalledComponent = written(
        install.run(InstallComponentCommand(assetId, parentId, name, supplyId, composition, serialOrLot, installedOn, notes = "", sortOrder = null)),
    ).row

    /** Replaces [id] on [replacedOn] with exactly what is given (R47-17b). */
    suspend fun swap(
        id: InstalledComponentId,
        replacedOn: String,
        name: String,
        supplyId: SupplyId? = null,
        composition: List<CompositionInput> = emptyList(),
        serialOrLot: String = "",
    ): InstalledComponentResult.Ok =
        written(replace.run(id, ReplaceComponentCommand(replacedOn, name, supplyId, composition, serialOrLot, notes = "")))

    private fun written(result: InstalledComponentResult): InstalledComponentResult.Ok {
        check(result is InstalledComponentResult.Ok) { "expected a row, got $result" }
        return result
    }
}

/** [quantity] of [supplyId] in [unit], as a person types it: a new entry. */
internal fun madeOf(quantity: String, supplyId: SupplyId, unit: String = "ea") = CompositionInput(null, supplyId, quantity, unit)

/**
 * #47 (rows 37–38; AC2, AC4, AC6, AC8, AC10, AC11, AC-C) — the two shapes a UPS battery takes and the multi-stage
 * treatment fixture, end to end through the use cases, fictional throughout.
 *
 * One "Example UPS" holds both compositions R47-13 names: **instance-level**, a tray with four positions each naming
 * the one 12 V battery, where each position has its own history; and **aggregate**, a pack whose direct link is its
 * pack SKU and which is made of `4 ×` that battery, with no child rows. Replacing one position, or the pack, moves
 * nothing else. Both survive an export, a replace import into an empty install and a re-plan, `IDENTICAL`.
 *
 * An "Example RO System" made from the RO water template fits a membrane and a UV lamp as installed components, where
 * the instance and its replacements matter, and keeps its sediment cartridge and its sanitizer as SupplyItems it takes
 * by applicability only: no row names them.
 */
class InstalledComponentFixtureTest {

    private val fitting = FittingInstall()
    private val home get() = fitting.home

    private class Ups(
        val asset: AssetId,
        val battery: SupplyItem,
        val packSku: SupplyItem,
        val tray: InstalledComponent,
        val positions: List<InstalledComponent>,
        val pack: InstalledComponent,
    )

    private suspend fun ups(): Ups {
        val asset = home.createAsset.run("Example UPS", templateKey = "ups").id
        val battery = home.item("Example 12 V Battery", "EP-B12-9", Triple("Voltage", "12", "V"), Triple("Capacity", "9", "Ah"))
        val packSku = home.item("Example Battery Pack", "EP-PK48", Triple("Voltage", "48", "V"))
        val tray = fitting.fit(asset, "Example Battery Tray", serialOrLot = "TRAY-0001")
        val positions = (1..4).map { fitting.fit(asset, "Position $it", parentId = tray.id, supplyId = battery.id, serialOrLot = "B-000$it") }
        val pack = fitting.fit(
            asset, "Example Battery Pack", supplyId = packSku.id, composition = listOf(madeOf("4", battery.id)), serialOrLot = "PK-0001",
        )
        return Ups(asset, battery, packSku, tray, positions, pack)
    }

    private suspend fun rows(asset: AssetId) = home.raw.installedComponents.forAsset(asset)

    @Test
    fun aTrayHoldsFourPositionsOfOneBatteryAndReplacingPositionTwoMovesNothingElse() = runBlocking<Unit> {
        val e = ups()

        // AC2, AC4: four positions inside the tray, in install order, each naming the same battery; no child Asset.
        val tree = InstalledComponentTree.current(rows(e.asset))
        assertEquals(
            listOf("Example Battery Tray" to 0, "Position 1" to 1, "Position 2" to 1, "Position 3" to 1, "Position 4" to 1, "Example Battery Pack" to 0),
            tree.map { it.row.name to it.depth },
        )
        assertEquals(listOf(0, 1, 2, 3), e.positions.map { it.sortOrder })
        assertEquals(setOf<SupplyId?>(e.battery.id), e.positions.map { it.supplyId }.toSet())
        assertEquals(listOf(e.asset), home.raw.assets.all().map { it.id })

        val before = rows(e.asset)
        home.now = dayMillis("2026-02-15")
        val swapped = fitting.swap(e.positions[1].id, "2026-02-15", "Position 2", supplyId = e.battery.id, serialOrLot = "B-0005")

        // AC6: position 2 closed and its successor in its place; the tray, the other three and the pack byte-equal.
        assertEquals(e.positions[1].copy(removedOn = "2026-02-15", updatedAt = dayMillis("2026-02-15")), swapped.replaced)
        val successor = swapped.row
        assertEquals<List<Any?>>(
            listOf(e.tray.id, 1, e.positions[1].id, "B-0005"),
            listOf(successor.parentId, successor.sortOrder, successor.replacesId, successor.serialOrLot),
        )
        val after = rows(e.asset)
        before.filter { it.id != e.positions[1].id }.forEach { row -> assertEquals(row, after.single { it.id == row.id }, row.name) }
        assertEquals(before.size + 1, after.size)
        assertEquals(listOf(successor, swapped.replaced), InstalledComponentTree.history(after, successor.id), "newest first")
        assertEquals(
            listOf("Position 1", "Position 2", "Position 3", "Position 4"),
            InstalledComponentTree.current(after).filter { it.row.parentId == e.tray.id }.map { it.row.name },
        )
    }

    @Test
    fun aPackMadeOfFourBatteriesIsReplacedWholeWithItsCompositionSentAgain() = runBlocking<Unit> {
        val e = ups()

        // AC-C: the pack is its SKU and is made of 4 × the battery, with no child rows.
        assertEquals(e.packSku.id, e.pack.supplyId)
        assertEquals(listOf(e.battery.id to 4.0), e.pack.composition.map { it.supplyId to it.quantity })
        assertTrue(rows(e.asset).none { it.parentId == e.pack.id })

        val before = rows(e.asset)
        val swapped = fitting.swap(
            e.pack.id, "2026-02-20", "Example Battery Pack", supplyId = e.packSku.id,
            composition = listOf(madeOf("4", e.battery.id)), serialOrLot = "PK-0002",
        )
        val successor = swapped.row
        assertEquals(e.pack.composition, swapped.replaced?.composition, "the closed pack keeps its own entries")
        assertEquals(listOf(e.battery.id to 4.0), successor.composition.map { it.supplyId to it.quantity })
        assertTrue(successor.composition.single().id != e.pack.composition.single().id, "its own entry")
        assertEquals(e.packSku.id, successor.supplyId)
        val after = rows(e.asset)
        before.filter { it.id != e.pack.id }.forEach { row -> assertEquals(row, after.single { it.id == row.id }, row.name) }
    }

    @Test
    fun bothShapesRestoreFromAnExportAndReplanIdentical() = runBlocking<Unit> {
        val e = ups()
        fitting.swap(e.positions[1].id, "2026-02-15", "Position 2", supplyId = e.battery.id, serialOrLot = "B-0005")
        fitting.swap(e.pack.id, "2026-02-20", "Example Battery Pack", supplyId = e.packSku.id, composition = listOf(madeOf("4", e.battery.id)))
        written(fitting.remove.run(e.positions[3].id, "2026-02-25"))

        assertRestoresAndReplansIdentical(home.raw, components = 8)
    }

    @Test
    fun theTreatmentFixtureFitsAMembraneAndALampAndKeepsTheCartridgeAndSanitizerAsSupplyItems() = runBlocking<Unit> {
        val ro = home.createAsset.run("Example RO System", templateKey = "ro_water").id
        val cartridge = home.item("Example Sediment Cartridge", "EF-SD10", Triple("Micron rating", "5", "µm"))
        val membrane = home.item("Example RO Membrane", "EF-RO75", Triple("Capacity", "75", "gpd"))
        val lamp = home.item("Example UV Lamp", "EF-UV12", Triple("Power", "12", "W"))
        val sanitizer = home.item("Example Sanitizer", "EF-SN1", Triple("Volume", "250", "mL"))
        listOf(cartridge to "Stage 1 sediment", membrane to "Stage 2 membrane", lamp to "Stage 3 UV", sanitizer to "Sanitizing")
            .forEach { (item, role) -> home.applies(ro, item.id, role) }

        // AC10: the membrane and the lamp are fitted instances with serials; the lamp's replacement is its history.
        fitting.fit(ro, "Stage 2 membrane", supplyId = membrane.id, serialOrLot = "MB-0001", installedOn = "2026-01-05")
        val firstLamp = fitting.fit(ro, "Stage 3 UV lamp", supplyId = lamp.id, serialOrLot = "UV-0001", installedOn = "2026-01-05")
        val lampNow = fitting.swap(firstLamp.id, "2026-02-20", "Stage 3 UV lamp", supplyId = lamp.id, serialOrLot = "UV-0002").row

        val rows = rows(ro)
        assertEquals(listOf("MB-0001", "UV-0002"), InstalledComponentTree.current(rows).map { it.row.serialOrLot })
        assertEquals(listOf("UV-0002", "UV-0001"), InstalledComponentTree.history(rows, lampNow.id).map { it.serialOrLot })

        // AC11: the cartridge and the sanitizer are SupplyItems the system takes, and no row names them.
        val named = rows.flatMapTo(HashSet()) { row -> listOfNotNull(row.supplyId) + row.composition.map { it.supplyId } }
        assertEquals(setOf(membrane.id, lamp.id), named)
        assertEquals(
            setOf(cartridge.id, membrane.id, lamp.id, sanitizer.id),
            home.raw.assetSupplies.forAsset(ro).mapTo(HashSet()) { it.supplyId },
        )
        assertEquals(listOf(ro), home.raw.assets.all().map { it.id }, "no child Asset")

        assertRestoresAndReplansIdentical(home.raw, components = 3)
    }

    /**
     * [source]'s export, unedited, re-plans `IDENTICAL` against [source] (N-5); replace-imported into an empty install,
     * every row, entry, pointer and date reads back, every list exports byte-equal, and the same archive re-plans
     * `IDENTICAL` against it, its [components] installed component decisions among them.
     */
    private suspend fun assertRestoresAndReplansIdentical(source: BackupInstall, components: Int) {
        val bytes = source.export.run().data
        val againstSource = source.build.run(bytes)
        assertTrue(againstSource.decisions.all { it.verdict == MergeVerdict.IDENTICAL }, "${againstSource.decisions}")
        assertEquals(components, againstSource.decisions.count { it.table == MergeTable.INSTALLED_COMPONENTS })

        val restored = BackupInstall("set-restored")
        restored.replace.run(bytes)
        val replan = restored.build.run(bytes)
        assertEquals(
            List(components) { MergeVerdict.IDENTICAL },
            replan.decisions.filter { it.table == MergeTable.INSTALLED_COMPONENTS }.map { it.verdict },
            "the restored install holds every installed component",
        )
        assertTrue(replan.decisions.all { it.verdict == MergeVerdict.IDENTICAL }, "${replan.decisions}")
        assertEquals(source.installedComponents.all(), restored.installedComponents.all())

        val written = dataTreeOf(bytes).filterValues { it is JsonArray }
        val again = dataTreeOf(restored.export.run().data).filterValues { it is JsonArray }
        assertEquals(written.keys, again.keys)
        for (list in written.keys) assertEquals(written.getValue(list).toString(), again.getValue(list).toString(), list)
    }

    private fun written(result: InstalledComponentResult): InstalledComponentResult.Ok {
        assertTrue(result is InstalledComponentResult.Ok, "expected a row, got $result")
        return result
    }
}
