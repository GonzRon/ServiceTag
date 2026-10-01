package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.merge.MergeTable
import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.dayMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #15 (B4b) — one install as `AppGraph` builds it for the linkage: [BackupInstall]'s canonical stores, the real
 * recompute over them, and the shipped use cases a person drives: the asset with its template, the SupplyItem,
 * applicability, the quick action, the schedule, its completion and a logged event. Ids are minted
 * `<prefix>-1`, `<prefix>-2`, …, so two installs never mint the same one.
 */
internal class LinkageInstall(prefix: String = "id", val raw: BackupInstall = BackupInstall("set-$prefix")) {
    private var seq = 0
    val ids = IdGenerator { "$prefix-${++seq}" }
    var now: Long = dayMillis("2026-03-01")
    val clock = Clock { now }
    private val states = InMemoryScheduleStateRepository()
    val recompute = RecomputeSchedules(
        raw.schedules, states, raw.events, raw.closures, raw.groups, raw.assets, raw.activations,
        Today { LocalDate.parse("2026-03-01") }, clock,
    ) { ZoneOffset.UTC }
    val applyTemplate = ApplyTemplate(raw.definitions, raw.profiles, raw.assets, raw.uow, ids, clock)
    val createAsset = CreateAsset(raw.assets, raw.uow, ids, clock, applyTemplate, PromoteCategory(raw.categories))
    val saveSupplyItem = SaveSupplyItem(raw.supplyItems, raw.uow, ids, clock)
    val addAssetSupply = AddAssetSupply(raw.assets, raw.supplyItems, raw.assetSupplies, raw.uow, ids, clock)
    val saveProfile = SaveProfile(raw.profiles, raw.definitions, raw.assets, raw.supplyItems, raw.uow, ids, clock)
    val saveSchedule = SaveSchedule(
        raw.schedules, raw.assets, raw.groups, raw.definitions, raw.profiles, raw.uow, ids, clock, recompute,
        raw.subjects,
    )
    val completeSchedule = CompleteSchedule(
        raw.schedules, raw.events, raw.definitions, raw.profiles, raw.supplyItems, raw.uow, ids, clock, recompute,
    )
    val logEvent = LogEvent(
        raw.events, raw.definitions, raw.profiles, raw.assets, raw.supplyItems, raw.uow, ids, clock, recompute,
    )

    /** A SupplyItem with the identity given and [specs] as (label, value, unit), keys left to the slug rule. */
    suspend fun item(
        name: String,
        partNumber: String = "",
        vararg specs: Triple<String, String, String>,
    ): SupplyItem = saveSupplyItem.run(
        null,
        SupplyItemCommand(
            name = name, category = "", manufacturer = "Example Filters Co.", model = "", partNumber = partNumber,
            preferredUnit = "ea", notes = "",
            specifications = specs.map { (label, value, unit) -> SpecificationInput(null, "", label, value, unit) },
        ),
    ).item

    /** [assetId] takes [supplyId] in [role]; the row as stored. */
    suspend fun applies(assetId: AssetId, supplyId: SupplyId, role: String) =
        (addAssetSupply.run(AddAssetSupplyCommand(assetId, supplyId, role)) as AssetSupplyResult.Ok).row

    /** A REPLACEMENT quick action on [assetId] whose one line is [line], linked to [link]. */
    suspend fun replaceAction(assetId: AssetId, name: String, line: String, link: SupplyId?): EventProfile =
        saveProfile.run(
            null,
            ProfileCommand(
                assetId, name, EventKind.REPLACEMENT, "", emptyList(),
                listOf(ProfileConsumableInput(null, line, 1.0, "ea", link)),
            ),
        )

    /** A FORM schedule on [assetId] every [months] months, naming [profile] (never a SupplyItem: that is #96). */
    suspend fun schedule(assetId: AssetId, profile: EventProfile, months: Int): MaintenanceSchedule =
        saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = assetId, targetGroupId = null, title = profile.name,
                timeInterval = months, timeUnit = RecurrenceUnit.MONTH, timeBasis = TimeBasis.FIXED,
                anchorOn = "2026-01-15", completionMode = CompletionMode.FORM, profileId = profile.id,
            ),
        )
}

/**
 * #15 (C18; AC11, AC2, AC7) — the multi-stage treatment fixture, end to end, fictional throughout.
 *
 * An "Example RO System" made from the RO water template takes three replaceable products, each its own
 * SupplyItem with its own identity and specifications, in three stage roles; three REPLACEMENT quick actions
 * each carry one line linked to its SupplyItem, and three schedules name those quick actions. A second asset
 * takes an "Example Battery Pack" and an "Example Battery Cell" as two unrelated SupplyItems (AC2). No child
 * Asset is created anywhere: identity, specifications, applicability and the linkage hold it all.
 *
 * A completion that **sends** a linked line writes an event line carrying that link; a minimal completion
 * writes no material line and so no `supplyId` anywhere on its event — no usage record is implied (limit 2).
 */
class SupplyItemFixtureTest {

    private val home = LinkageInstall()

    private class Estate(
        val ro: AssetId,
        val bank: AssetId,
        val prefilter: SupplyItem,
        val membrane: SupplyItem,
        val postFilter: SupplyItem,
        val pack: SupplyItem,
        val cell: SupplyItem,
        val actions: List<EventProfile>,
        val schedules: List<MaintenanceSchedule>,
    )

    private suspend fun estate(): Estate {
        val ro = home.createAsset.run("Example RO System", templateKey = "ro_water").id
        val prefilter = home.item(
            "Example Prefilter Cartridge", "EF-PF10-5",
            Triple("Micron rating", "5", "µm"), Triple("Length", "10", "in"),
        )
        val membrane = home.item(
            "Example RO Membrane", "EF-RO75",
            Triple("Capacity", "75", "gpd"), Triple("Diameter", "1.8", "in"),
        )
        val postFilter = home.item(
            "Example Post-filter", "EF-PC10",
            Triple("Media", "Coconut carbon", ""), Triple("Length", "10", "in"),
        )
        home.applies(ro, prefilter.id, "Stage 1 prefilter")
        home.applies(ro, membrane.id, "Stage 2 membrane")
        home.applies(ro, postFilter.id, "Stage 3 post-filter")
        val actions = listOf(
            home.replaceAction(ro, "Replace prefilter", "Prefilter cartridge", prefilter.id),
            home.replaceAction(ro, "Replace membrane", "RO membrane", membrane.id),
            home.replaceAction(ro, "Replace post-filter", "Post-filter cartridge", postFilter.id),
        )
        val schedules = actions.zip(listOf(6, 24, 12)).map { (action, months) -> home.schedule(ro, action, months) }

        val bank = home.createAsset.run("Example Battery Bank").id
        val pack = home.item("Example Battery Pack", "EB-PK12", Triple("Voltage", "12.8", "V"), Triple("Capacity", "100", "Ah"))
        val cell = home.item("Example Battery Cell", "EB-C32", Triple("Voltage", "3.2", "V"), Triple("Chemistry", "LiFePO4", ""))
        home.applies(bank, pack.id, "Pack")
        home.applies(bank, cell.id, "Cell")
        return Estate(ro, bank, prefilter, membrane, postFilter, pack, cell, actions, schedules)
    }

    @Test
    fun theTreatmentFixtureHoldsIdentitySpecsApplicabilityAndTheLinkageWithNoChildAsset() = runBlocking<Unit> {
        val e = estate()

        // AC11: two assets, neither a child, and no child Asset was made for any product.
        assertEquals(setOf(e.ro, e.bank), home.raw.assets.all().map { it.id }.toSet())
        assertTrue(home.raw.assets.all().all { it.parentAssetId == null })

        // Each product its own identity and its own ordered specifications, keyed by the slug rule.
        assertEquals(
            listOf(
                "Example Prefilter Cartridge" to listOf("micron_rating", "length"),
                "Example RO Membrane" to listOf("capacity", "diameter"),
                "Example Post-filter" to listOf("media", "length"),
            ),
            listOf(e.prefilter, e.membrane, e.postFilter).map { item ->
                home.raw.supplyItems.get(item.id)!!.let { it.name to it.specifications.sortedBy { s -> s.sortOrder }.map { s -> s.key } }
            },
        )
        assertEquals(
            listOf("EF-PF10-5", "EF-RO75", "EF-PC10"),
            listOf(e.prefilter, e.membrane, e.postFilter).map { home.raw.supplyItems.get(it.id)!!.partNumber },
        )

        // AC4: the RO system takes the three products in three stage roles.
        assertEquals(
            listOf(
                e.prefilter.id to "Stage 1 prefilter",
                e.membrane.id to "Stage 2 membrane",
                e.postFilter.id to "Stage 3 post-filter",
            ),
            home.raw.assetSupplies.forAsset(e.ro).sortedBy { it.role }.map { it.supplyId to it.role },
        )

        // AC2: a pack and a cell are two rows with their own specifications, both on the bank in their own roles;
        // nothing on either names the other.
        assertTrue(e.pack.id != e.cell.id)
        assertEquals(
            listOf(e.cell.id to "Cell", e.pack.id to "Pack"),
            home.raw.assetSupplies.forAsset(e.bank).sortedBy { it.role }.map { it.supplyId to it.role },
        )
        assertTrue(e.cell.id.value !in home.raw.supplyItems.get(e.pack.id)!!.toString())
        assertTrue(e.pack.id.value !in home.raw.supplyItems.get(e.cell.id)!!.toString())

        // AC7: the identity sits on each quick action's line, beside the line's own words.
        assertEquals(
            listOf(
                "Prefilter cartridge" to e.prefilter.id,
                "RO membrane" to e.membrane.id,
                "Post-filter cartridge" to e.postFilter.id,
            ),
            e.actions.map { home.raw.profiles.get(it.id)!!.consumables.single().let { line -> line.name to line.supplyId } },
        )
        // Each schedule names its quick action, and only that: a schedule never names a SupplyItem (#96).
        assertEquals(e.actions.map { it.id }, e.schedules.map { home.raw.schedules.get(it.id)!!.profileId })
    }

    @Test
    fun aCompletionSendingALinkedLineCarriesItAndAMinimalCompletionRecordsNoUsage() = runBlocking<Unit> {
        val e = estate()

        val replaced = home.completeSchedule.run(
            e.schedules[0].id,
            CompletionCommand(
                occurredOn = "2026-02-20", tzId = "UTC",
                consumables = listOf(ConsumableInput("Prefilter cartridge", "1", "ea", e.prefilter.id)),
            ),
        )
        val stored = home.raw.events.get(replaced.id)!!
        assertEquals(EventKind.REPLACEMENT, stored.kind)
        assertEquals(e.actions[0].id, stored.profileId)
        assertEquals(listOf("Prefilter cartridge" to e.prefilter.id), stored.consumables.map { it.name to it.supplyId })

        val minimal = home.completeSchedule.run(e.schedules[1].id, CompletionCommand(occurredOn = "2026-02-21", tzId = "UTC"))
        val bare = home.raw.events.get(minimal.id)!!
        assertEquals(emptyList(), bare.consumables)
        // The absence, asserted where an archive would carry it: no line, so no supplyId key on that event at all.
        val written = dataTreeOf(home.raw.export.run().data).getValue("assetEvents").jsonArray
            .map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == minimal.id.value }
        assertEquals(JsonArray(emptyList()), written.getValue("consumables"))
        assertTrue("supplyId" !in written.toString(), "$written")
    }

    @Test
    fun renamingAnItemLeavesTheLinesSnapshotsIntact() = runBlocking<Unit> {
        val e = estate()
        val logged = home.completeSchedule.run(
            e.schedules[0].id,
            CompletionCommand(
                occurredOn = "2026-02-20", tzId = "UTC",
                consumables = listOf(ConsumableInput("Prefilter cartridge", "1", "ea", e.prefilter.id)),
            ),
        )
        val profilesBefore = home.raw.profiles.all()
        val eventsBefore = home.raw.events.all()

        home.saveSupplyItem.run(
            e.prefilter.id,
            SupplyItemCommand(
                name = "Example Prefilter Cartridge (5 micron)", category = "Cartridge",
                manufacturer = "Example Filters Co.", model = "PF-10B", partNumber = "EF-PF10-5B", preferredUnit = "pack",
                notes = "",
                specifications = e.prefilter.specifications.map { SpecificationInput(it.id, "", it.label, it.value, it.unit) },
            ),
        )

        assertEquals("Example Prefilter Cartridge (5 micron)", home.raw.supplyItems.get(e.prefilter.id)!!.name)
        assertEquals(profilesBefore, home.raw.profiles.all())
        assertEquals(eventsBefore, home.raw.events.all())
        assertEquals(
            "Prefilter cartridge" to "ea",
            home.raw.events.get(logged.id)!!.consumables.single().let { it.name to it.unit },
        )
    }

    @Test
    fun theFixtureRoundTripsAndMergesIntoAnEmptyInstallThenReplansIdentical() = runBlocking<Unit> {
        val e = estate()
        home.completeSchedule.run(
            e.schedules[0].id,
            CompletionCommand(
                occurredOn = "2026-02-20", tzId = "UTC",
                consumables = listOf(ConsumableInput("Prefilter cartridge", "1", "ea", e.prefilter.id)),
            ),
        )
        val bytes = home.raw.export.run().data

        // Export → replace import → export: every list byte-equal.
        val restored = BackupInstall("set-restored")
        restored.replace.run(bytes)
        val written = dataTreeOf(bytes).filterValues { it is JsonArray }
        val again = dataTreeOf(restored.export.run().data).filterValues { it is JsonArray }
        assertEquals(written.keys, again.keys)
        for (list in written.keys) assertEquals(written.getValue(list).toString(), again.getValue(list).toString(), list)

        // A merge into an empty install INSERTs every row, the five SupplyItems and five applicability rows among them.
        val empty = BackupInstall("set-empty")
        val plan = empty.build.run(bytes)
        assertTrue(plan.applicable, "${plan.conflicts}")
        assertTrue(plan.decisions.all { it.verdict == MergeVerdict.INSERT }, "${plan.decisions}")
        assertEquals(5, plan.decisions.count { it.table == MergeTable.SUPPLY_ITEMS })
        assertEquals(5, plan.decisions.count { it.table == MergeTable.ASSET_SUPPLIES })
        empty.apply.run(plan)

        assertEquals(home.raw.supplyItems.all().sortedBy { it.id.value }, empty.supplyItems.all().sortedBy { it.id.value })
        assertEquals(home.raw.assetSupplies.all().sortedBy { it.id }, empty.assetSupplies.all().sortedBy { it.id })
        assertEquals(home.raw.profiles.all().sortedBy { it.id.value }, empty.profiles.all().sortedBy { it.id.value })
        assertEquals(linesOf(home.raw.events.all()), linesOf(empty.events.all()))

        // A re-plan of the same archive is all IDENTICAL, and writes nothing.
        val replan = empty.build.run(bytes)
        assertTrue(replan.decisions.all { it.verdict == MergeVerdict.IDENTICAL }, "${replan.decisions}")
        assertEquals(plan.decisions.size, replan.decisions.size)
    }

    /** Every event line as (event, name, link), the shape the linkage is about. */
    private fun linesOf(events: List<AssetEvent>) =
        events.flatMap { event -> event.consumables.map { Triple(event.id, it.name, it.supplyId) } }.sortedBy { it.first.value }
}
