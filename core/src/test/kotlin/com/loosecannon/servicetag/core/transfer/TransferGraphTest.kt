package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.subjectOf
import com.loosecannon.servicetag.core.transfer.TransferFixtures.ANODE
import com.loosecannon.servicetag.core.transfer.TransferFixtures.COMPRESSOR
import com.loosecannon.servicetag.core.transfer.TransferFixtures.EMPTY_GROUP
import com.loosecannon.servicetag.core.transfer.TransferFixtures.GROUP
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import com.loosecannon.servicetag.core.transfer.TransferFixtures.OPENER
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** #77 (C2; AC 1–3, 17; R77-6 to R77-10) — what a selection carries, and every cut it refuses. */
class TransferGraphTest {

    private val estate = TransferFixtures.estate()

    private fun selected(data: BackupData, vararg roots: String) =
        assertIs<TransferSelection.Selected>(TransferGraph.select(data, roots.map(::AssetId)))

    private fun refused(data: BackupData, vararg roots: String) =
        assertIs<TransferSelection.Refused>(TransferGraph.select(data, roots.map(::AssetId))).refusals

    // --- what travels (row 2) ---------------------------------------------------------------------

    /** Every asset-owned table, row for row and field for field: season, condition, health and the lead included. */
    @Test
    fun aRootCarriesEveryAssetOwnedTable() {
        val pack = selected(estate, HEATER).data

        assertEquals(estate.assets.filter { it.id in setOf(HEATER, ANODE) }, pack.assets, "verbatim, lead and season included")
        assertEquals(30, pack.assets.first { it.id == HEATER }.warrantyReminderLeadDays)
        assertEquals("CALENDAR", pack.assets.first { it.id == HEATER }.seasonMode)
        assertEquals(listOf("t1", "t2", "t6"), pack.nfcTags.map { it.id }, "LOST included")
        assertEquals(listOf("d1"), pack.measurementDefinitions.map { it.id })
        assertEquals(estate.eventProfiles, pack.eventProfiles)
        assertEquals(listOf("e1", "e2"), pack.assetEvents.map { it.id })
        assertEquals(estate.assetEvents.first { it.id == "e1" }, pack.assetEvents.first(), "measurements and consumables")
        assertEquals(listOf("at1", "at2"), pack.attachments.map { it.id }, "the asset's and its events' documents")
        assertEquals(listOf("r1"), pack.assetReferences.map { it.id })
        assertEquals(listOf("sa1"), pack.seasonActivations.map { it.id })
        assertEquals(listOf("co1"), pack.assetConditions.map { it.id })
        assertEquals(listOf("hs1"), pack.healthSubjects.map { it.id })
        assertEquals(listOf("s1", "sg"), pack.maintenanceSchedules.map { it.id })
        assertEquals(listOf("cl1", "cl2"), pack.occurrenceClosures.map { it.id })
        assertEquals(listOf("sc1"), pack.serviceCases.map { it.id }, "an open case travels (R77-7)")
        assertEquals(listOf("n1"), pack.serviceCaseEntries.map { it.id })
    }

    /** R77-8: a parent never leaves without its components. */
    @Test
    fun descendantsAreForced() {
        val selection = selected(estate, HEATER)

        assertEquals(listOf(AssetId(HEATER)), selection.rootIds)
        assertEquals(listOf(AssetId(HEATER), AssetId(ANODE)), selection.assetIds)
        assertEquals(listOf(HEATER, ANODE), selection.data.assets.map { it.id })
    }

    /** The flush round names the heater now and the anode once: wholly inside, it travels with its schedule and closure. */
    @Test
    fun aWholeGroupTravels() {
        val pack = selected(estate, HEATER).data

        assertEquals(listOf(GROUP), pack.maintenanceGroups.map { it.id })
        assertEquals(estate.maintenanceGroups.first { it.id == GROUP }, pack.maintenanceGroups.single(), "every row, the removed one included")
        assertTrue("sg" in pack.maintenanceSchedules.map { it.id } && "cl2" in pack.occurrenceClosures.map { it.id })
    }

    @Test
    fun onlyCategoriesInUseTravel() {
        assertEquals(listOf("appliance"), selected(estate, HEATER).data.assetCategories.map { it.key })
        assertEquals(listOf("garage"), selected(estate, OPENER).data.assetCategories.map { it.key }, "archived and retired may be a root (R77-10)")
        val openerAsIs = estate.assets.first { it.id == OPENER }
        assertEquals(listOf(openerAsIs), selected(estate, OPENER).data.assets, "lifecycle facts verbatim")
    }

    /** A primary subject, a case's incident and a fact's event are soft: carried as they are, even when they name a row that stays. */
    @Test
    fun softLinksAsIs() {
        val data = estate.copy(
            assets = estate.assets.map { if (it.id == HEATER) it.copy(healthPrimarySubjectId = "hs2") else it },
            serviceCases = estate.serviceCases.map { if (it.id == "sc1") it.copy(incidentEventId = "e3") else it },
            assetConditions = estate.assetConditions.map { it.copy(eventId = "e3") },
        )

        val pack = selected(data, HEATER).data

        assertEquals("hs2", pack.assets.first { it.id == HEATER }.healthPrimarySubjectId)
        assertEquals("e3", pack.serviceCases.single().incidentEventId)
        assertEquals("e3", pack.assetConditions.single().eventId)
        BackupCodec.decode(encode(pack))
    }

    /** Nothing of the compressor, the opener, the spare tag, the empty group or the unused category. */
    @Test
    fun unrelatedRowsNeverTravel() {
        val pack = selected(estate, HEATER).data

        val names = listOf(COMPRESSOR, OPENER)
        assertEquals(emptyList(), pack.assets.filter { it.id in names })
        assertEquals(emptyList(), pack.nfcTags.filter { it.assetId !in setOf(HEATER, ANODE) }, "no spare, no other asset's tag")
        assertEquals(listOf("d1"), pack.measurementDefinitions.map { it.id })
        assertTrue(pack.assetEvents.none { it.id == "e3" } && pack.attachments.none { it.id == "at3" })
        assertTrue(pack.maintenanceSchedules.none { it.id == "s2" } && pack.occurrenceClosures.none { it.id == "cl3" })
        assertTrue(pack.healthSubjects.none { it.id == "hs2" } && pack.serviceCases.none { it.id == "sc2" })
        assertTrue(pack.serviceCaseEntries.none { it.id == "n2" } && pack.maintenanceGroups.none { it.id == EMPTY_GROUP })
        assertTrue(pack.assetCategories.none { it.key != "appliance" })
    }

    /** The same rows in any order: the same selection, and the same archive bytes. */
    @Test
    fun shuffledInputGivesEqualBytes() {
        val random = Random(77)
        val shuffled = estate.copy(
            assets = estate.assets.shuffled(random),
            nfcTags = estate.nfcTags.shuffled(random),
            assetEvents = estate.assetEvents.shuffled(random),
            attachments = estate.attachments.shuffled(random),
            maintenanceGroups = estate.maintenanceGroups.shuffled(random),
            maintenanceSchedules = estate.maintenanceSchedules.reversed(),
            occurrenceClosures = estate.occurrenceClosures.reversed(),
            assetCategories = estate.assetCategories.reversed(),
            healthSubjects = estate.healthSubjects.reversed(),
        )

        val a = selected(estate, HEATER)
        val b = selected(shuffled, HEATER)

        assertEquals(a, b)
        assertContentEquals(encode(a.data), encode(b.data))
    }

    // --- what is refused (row 3) ------------------------------------------------------------------

    /** R77-9: a group with rows on both sides of the cut names every asset that would stay. */
    @Test
    fun aMixedGroupNamesItsStayingAssets() {
        val mixed = groupOf("G2", "Example Mixed Round", members = listOf(
            Triple(HEATER, "2026-01-01", null),
            Triple(COMPRESSOR, "2026-01-01", null),
            Triple(OPENER, "2026-01-01", "2026-02-01"),
        ))
        val data = estate.copy(maintenanceGroups = estate.maintenanceGroups + mixed.toDto())

        assertEquals(
            listOf(TransferRefusal.MixedGroup("G2", listOf(AssetId(OPENER), AssetId(COMPRESSOR)))),
            refused(data, HEATER),
        )
    }

    /** Historical membership counts (R77-9): a removed row naming a staying asset still mixes the group. */
    @Test
    fun aRemovedMembershipStillMixes() {
        val once = groupOf("G3", "Example Past Round", members = listOf(
            Triple(HEATER, "2026-01-01", null),
            Triple(COMPRESSOR, "2026-01-01", "2026-03-01"),
        ))
        val data = estate.copy(maintenanceGroups = estate.maintenanceGroups + once.toDto())

        assertEquals(listOf(TransferRefusal.MixedGroup("G3", listOf(AssetId(COMPRESSOR)))), refused(data, HEATER))
    }

    /** MJ-2: an empty group is wholly in nothing — never carried, whatever the roots. */
    @Test
    fun anEmptyGroupNeverTravels() {
        val everyone = estate.copy(assetLoans = emptyList())

        assertTrue(selected(everyone, HEATER).data.maintenanceGroups.none { it.id == EMPTY_GROUP })
        assertEquals(listOf(GROUP), selected(everyone, HEATER, OPENER, COMPRESSOR).data.maintenanceGroups.map { it.id })
    }

    /**
     * R77-8: a component cannot leave without its parent. The anode alone also splits the flush round it
     * once belonged to, and its completion then names a schedule that stays: every reason, together.
     */
    @Test
    fun aChildWithoutItsParent() {
        assertEquals(
            listOf(
                TransferRefusal.ParentNotSelected(AssetId(ANODE), AssetId(HEATER)),
                TransferRefusal.MixedGroup(GROUP, listOf(AssetId(HEATER))),
                TransferRefusal.OutsideReference(AssetId(ANODE), "assetEvents", "e2", "sg"),
            ),
            refused(estate, ANODE),
        )
    }

    /** R77-6: an open loan refuses; a returned one neither refuses nor travels. */
    @Test
    fun anOpenLoanRefusesAndReturnedLoansStay() {
        assertEquals(listOf(TransferRefusal.OpenLoan(AssetId(COMPRESSOR), "l2")), refused(estate, COMPRESSOR))
        assertEquals(emptyList(), selected(estate, HEATER).data.assetLoans)
    }

    /** An event or a subject naming a schedule that stays would not decode in the pack (AC 3). */
    @Test
    fun anEventNamingARetainedScheduleIsOutside() {
        val data = estate.copy(
            assetEvents = estate.assetEvents + completionOf("e9", "2026-05-01", "2026-05-01", assetId = HEATER, scheduleId = "s2").toDto(),
            healthSubjects = estate.healthSubjects + subjectOf("hs9", HEATER, scheduleId = "s2").toDto(),
        )

        assertEquals(
            listOf(
                TransferRefusal.OutsideReference(AssetId(HEATER), "assetEvents", "e9", "s2"),
                TransferRefusal.OutsideReference(AssetId(HEATER), "healthSubjects", "hs9", "s2"),
            ),
            refused(data, HEATER),
        )
    }

    /** No refusal hides another: the owner sees the whole list at once. */
    @Test
    fun everyRefusalTogether() {
        val mixed = groupOf("G2", "Example Mixed Round", members = listOf(
            Triple(COMPRESSOR, "2026-01-01", null),
            Triple(OPENER, "2026-01-01", null),
        ))
        val data = estate.copy(
            maintenanceGroups = estate.maintenanceGroups + mixed.toDto(),
            assetEvents = estate.assetEvents + completionOf("e9", "2026-05-01", "2026-05-01", assetId = COMPRESSOR, scheduleId = "s1").toDto(),
        )

        assertEquals(
            listOf(
                TransferRefusal.ParentNotSelected(AssetId(ANODE), AssetId(HEATER)),
                TransferRefusal.OpenLoan(AssetId(COMPRESSOR), "l2"),
                TransferRefusal.MixedGroup(GROUP, listOf(AssetId(HEATER))),
                TransferRefusal.MixedGroup("G2", listOf(AssetId(OPENER))),
                TransferRefusal.OutsideReference(AssetId(ANODE), "assetEvents", "e2", "sg"),
                TransferRefusal.OutsideReference(AssetId(COMPRESSOR), "assetEvents", "e9", "s1"),
            ),
            refused(data, ANODE, COMPRESSOR),
        )
    }

    // --- every pack decodes (row 4) ---------------------------------------------------------------

    /**
     * Every selection this estate allows is a valid archive on its own, and selecting every top-level
     * asset carries every row that can travel — nothing silently left behind.
     */
    @Test
    fun everySelectedArchiveDecodes() {
        val data = estate.copy(assetLoans = estate.assetLoans.map { it.copy(returnedOn = it.returnedOn ?: "2026-09-21") })
        val rootSets = listOf(listOf(HEATER), listOf(OPENER), listOf(COMPRESSOR), listOf(HEATER, ANODE),
            listOf(HEATER, OPENER), listOf(HEATER, COMPRESSOR), listOf(OPENER, COMPRESSOR), listOf(HEATER, OPENER, COMPRESSOR))

        rootSets.forEach { roots ->
            val pack = selected(data, *roots.toTypedArray()).data
            assertEquals(pack, BackupCodec.decode(encode(pack)).data, "roots $roots")
        }

        val everything = selected(data, HEATER, OPENER, COMPRESSOR).data
        val travelling = data.copy(
            nfcTags = data.nfcTags.filter { it.assetId != null },
            externalLinks = emptyList(),
            maintenanceGroups = data.maintenanceGroups.filter { it.id != EMPTY_GROUP },
            assetCategories = data.assetCategories.filter { it.key != "spare parts" },
            assetLoans = emptyList(),
        )
        assertEquals(BackupCodec.decode(encode(travelling)).data, everything)
    }

    private fun encode(data: BackupData): ByteArray =
        BackupCodec.encode(data, appVersion = "1.4.1", schemaVersion = 13, createdAt = 1_758_900_000_000L, backupSetId = "pack-test")
}
