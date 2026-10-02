package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.testing.successionOf
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.AssetReferenceDto
import com.loosecannon.servicetag.core.backup.AttachmentDto
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.testing.InMemoryAttachmentStore
import com.loosecannon.servicetag.core.testing.assetSupplyOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.compositionEntryOf
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.installedComponentOf
import com.loosecannon.servicetag.core.testing.specificationOf
import com.loosecannon.servicetag.core.testing.subjectOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
import com.loosecannon.servicetag.core.transfer.TransferFixtures.ANODE
import com.loosecannon.servicetag.core.transfer.TransferFixtures.COMPRESSOR
import com.loosecannon.servicetag.core.transfer.TransferFixtures.EMPTY_GROUP
import com.loosecannon.servicetag.core.transfer.TransferFixtures.GROUP
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import com.loosecannon.servicetag.core.transfer.TransferFixtures.OPENER
import com.loosecannon.servicetag.core.usecase.artifactsPlanOf
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

    /**
     * #86 (C6; R86-16, AC 13): SENDER_ONLY — a pack never carries a succession, not even one whose two ends both
     * travel, and lineage never forces a selection: nothing is refused for it.
     */
    @Test
    fun successionsNeverTravel() {
        val lineage = estate.copy(
            assetSuccessions = listOf(
                successionOf("s1", predecessor = COMPRESSOR, successor = OPENER),
                successionOf("s2", predecessor = OPENER, successor = HEATER),
            ).map { it.toDto() },
        )

        val both = selected(lineage, HEATER, OPENER).data
        assertEquals(listOf(OPENER, HEATER, ANODE), both.assets.map { it.id }, "s2's two ends, and the heater's anode")
        assertEquals(emptyList(), both.assetSuccessions, "both ends selected; the pack carries none")
        assertEquals(emptyList(), selected(lineage, OPENER).data.assetSuccessions, "one end of each selected; still none")
        BackupCodec.decode(encode(both))
    }

    // --- #15 (C13, rows 27–28): SupplyItems by naming row, applicability with its asset ------------------------------

    /**
     * The estate with four SupplyItems, fictional: s1 the heater and its anode take, s3 the compressor takes; s2 is
     * named only by the heater's quick-action line and s4 only by its completion's line; s5 only by the compressor's
     * completion line. Specifications travel inside their item. The compressor's loan is returned, so it may be a root.
     */
    private fun supplied(): BackupData = estate.copy(
        assetLoans = estate.assetLoans.map { it.copy(returnedOn = it.returnedOn ?: "2026-09-21") },
        eventProfiles = estate.eventProfiles.map { p ->
            if (p.assetId == HEATER) p.copy(consumables = p.consumables.map { it.copy(supplyId = "s2") }) else p
        },
        assetEvents = estate.assetEvents.map { e ->
            when (e.id) {
                "e1" -> e.copy(consumables = e.consumables.map { it.copy(supplyId = "s4") })
                "e3" -> e.copy(consumables = listOf(e1Line.copy(id = "cu3", supplyId = "s5")))
                else -> e
            }
        },
        supplyItems = listOf(
            supplyItemOf("s1", specifications = listOf(specificationOf("sp1", "length", "Length", "10", "in"))),
            supplyItemOf("s2", "Example Descaler"),
            supplyItemOf("s3", "Example Intake Filter"),
            supplyItemOf("s4", "Example Descaler Refill", archivedAt = 3_000L),
            supplyItemOf("s5", "Example Compressor Oil"),
        ).map { it.toDto() },
        assetSupplies = listOf(
            assetSupplyOf("as1", HEATER, "s1", "Anode kit"),
            assetSupplyOf("as2", ANODE, "s1", "Replacement"),
            assetSupplyOf("as3", COMPRESSOR, "s3", "Intake filter"),
        ).map { it.toDto() },
    )

    private val e1Line get() = estate.assetEvents.first { it.id == "e1" }.consumables.single()

    /** An applicability row travels with its asset, and a SupplyItem only when a carried row names it. */
    @Test
    fun aPackCarriesOnlyTheSupplyItemsItsRowsName() {
        // No line linked: only the applicability rows name an item here.
        val rowsOnly = supplied().let { data ->
            data.copy(
                supplyItems = data.supplyItems.filter { it.id in setOf("s1", "s3") },
                eventProfiles = estate.eventProfiles,
                assetEvents = estate.assetEvents,
            )
        }

        val heater = selected(rowsOnly, HEATER).data
        assertEquals(listOf("as1", "as2"), heater.assetSupplies.map { it.id }, "the heater's row and its anode's")
        assertEquals(rowsOnly.supplyItems.filter { it.id == "s1" }, heater.supplyItems, "s1 with its specification; s3 stays home")
        val compressor = selected(rowsOnly, COMPRESSOR).data
        assertEquals(listOf("as3"), compressor.assetSupplies.map { it.id })
        assertEquals(listOf("s3"), compressor.supplyItems.map { it.id })
        assertEquals(heater, BackupCodec.decode(encode(heater)).data)
    }

    /** A SupplyItem no applicability row names travels when a carried quick-action or event line names it, archived too. */
    @Test
    fun anItemNamedOnlyByACarriedLineTravels() {
        val data = supplied()

        val pack = selected(data, HEATER).data

        assertEquals(listOf("s1", "s2", "s4"), pack.supplyItems.map { it.id }, "s3 and s5 stay home: no carried row names them")
        assertEquals(data.supplyItems.filter { it.id in setOf("s1", "s2", "s4") }, pack.supplyItems, "verbatim, archived included")
        assertEquals(listOf("s3", "s5"), selected(data, COMPRESSOR).data.supplyItems.map { it.id }, "its row's item and its line's")
        assertEquals(pack, BackupCodec.decode(encode(pack)).data, "every carried link resolves inside the pack")
    }

    /**
     * C3 (C13): `retain` drops the held assets' applicability rows and keeps every SupplyItem — global, never dropped,
     * even one only a held asset's rows name — so an export without the held graph still decodes.
     */
    @Test
    fun retainDropsAHeldAssetsApplicabilityAndKeepsEveryItem() {
        val data = supplied()

        val kept = assertIs<TransferRetention.Retained>(TransferGraph.retain(data, setOf(AssetId(HEATER), AssetId(ANODE)))).data

        assertEquals(listOf("as3"), kept.assetSupplies.map { it.id }, "only the compressor's row stays")
        assertEquals(data.supplyItems, kept.supplyItems, "every item, s1, s2 and s4 included")
        BackupCodec.decode(encode(kept))
    }

    // --- #47 (C13, rows 28–29; R47-4): installed components with their asset, history whole ---------------------------

    /**
     * [supplied] with installed components (fictional) and three more SupplyItems: on the heater a tray (c1) whose
     * direct link is s7, a pack under it removed on 2026-06-01 (c2, composed of 4 × s6) and the pack that replaced it
     * (c3, composed of 4 × s1); on the anode one row (c4); on the compressor one (c5, an s8 composed of one s8). s6 is
     * named only by the removed pack's entry, s7 only by the tray's direct link, s8 only by the compressor's row. The
     * rows come out of id order, so the pack's own order is the selection's.
     */
    private fun fitted(): BackupData = supplied().let { data ->
        data.copy(
            supplyItems = data.supplyItems + listOf(
                supplyItemOf("s6", "Example 12 V Battery"),
                supplyItemOf("s7", "Example Battery Tray"),
                supplyItemOf("s8", "Example Intake Housing"),
            ).map { it.toDto() },
            installedComponents = listOf(
                installedComponentOf(
                    "c3", assetId = HEATER, name = "Example Battery Pack", parentId = "c1", installedOn = "2026-06-01",
                    replacesId = "c2", composition = listOf(compositionEntryOf("k2", "s1", 4.0)),
                ),
                installedComponentOf("c5", assetId = COMPRESSOR, name = "Example Intake Housing", supplyId = "s8",
                    composition = listOf(compositionEntryOf("k3", "s8", 1.0))),
                installedComponentOf("c1", assetId = HEATER, name = "Example Battery Tray", supplyId = "s7"),
                installedComponentOf(
                    "c2", assetId = HEATER, name = "Example Battery Pack", parentId = "c1", installedOn = "2026-01-10",
                    removedOn = "2026-06-01", composition = listOf(compositionEntryOf("k1", "s6", 4.0)),
                ),
                installedComponentOf("c4", assetId = ANODE, name = "Example Anode Sleeve"),
            ).map { it.toDto() },
        )
    }

    /**
     * Every row of a carried asset travels — current and removed, each with its composition, the anode's with its
     * parent asset — and the pack, once sorted, round-trips through the codec unchanged; the compressor's row stays.
     */
    @Test
    fun aPackCarriesAnAssetsWholeHistoryWithEntries() {
        val data = fitted()

        val pack = selected(data, HEATER).data

        assertEquals(
            data.installedComponents.filter { it.assetId in setOf(HEATER, ANODE) }.sortedBy { it.id },
            pack.installedComponents,
            "c1–c4 by id, verbatim: the removed pack and the pack that replaced it both",
        )
        assertEquals(listOf("k1"), pack.installedComponents.single { it.id == "c2" }.composition.map { it.id }, "the closed row keeps its entry")
        assertEquals(listOf("s1", "s2", "s4", "s6", "s7"), pack.supplyItems.map { it.id }, "s3, s5 and s8 stay home")
        assertEquals(pack, BackupCodec.decode(encode(pack)).data, "the whole history decodes inside the pack")
    }

    /** A SupplyItem named only by a carried row's composition entry — here a removed row's — travels with it, verbatim. */
    @Test
    fun aSupplyItemNamedOnlyByAnEntryTravels() {
        val data = fitted()

        val pack = selected(data, HEATER).data

        assertEquals(data.supplyItems.filter { it.id == "s6" }, pack.supplyItems.filter { it.id == "s6" }, "the removed pack's entry names it")
        assertTrue(pack.supplyItems.none { it.id == "s8" }, "s8 stays home: only the compressor's row names it")
    }

    /** N-8: a SupplyItem named only by a carried row's direct link travels with it, verbatim. */
    @Test
    fun aSupplyItemNamedOnlyByADirectLinkTravels() {
        val data = fitted()

        val pack = selected(data, HEATER).data

        assertEquals(data.supplyItems.filter { it.id == "s7" }, pack.supplyItems.filter { it.id == "s7" }, "the tray's direct link names it")
        assertTrue("s8" in selected(data, COMPRESSOR).data.supplyItems.map { it.id }, "the compressor's own row names s8 and takes it")
    }

    /**
     * C3 (C13): `retain` drops a held asset's installed components, current and removed, with their entries, and keeps
     * every SupplyItem — s6 and s7, named only by held rows, included — so what stays still decodes.
     */
    @Test
    fun retainDropsAHeldAssetsRows() {
        val data = fitted()

        val kept = assertIs<TransferRetention.Retained>(TransferGraph.retain(data, setOf(AssetId(HEATER), AssetId(ANODE)))).data

        assertEquals(listOf("c5"), kept.installedComponents.map { it.id }, "only the compressor's row stays")
        assertEquals(data.supplyItems, kept.supplyItems, "every item, s6 and s7 included")
        assertEquals(kept.installedComponents, BackupCodec.decode(encode(kept)).data.installedComponents)
    }

    // --- #69 (C15, rows 35): a file or link travels with its component, or with its SupplyItem in use ------------------

    /**
     * [fitted] with one file and one link on each of four installed components — the heater's tray (c1), its removed
     * pack (c2), the anode's sleeve (c4) and the compressor's housing (c5) — and on five SupplyItems: s1 (the heater's
     * applicability row), s4 (archived, named only by the heater's completion line), s6 (only by the removed pack's
     * entry), s7 (only by the tray's direct link) and s8 (only by the compressor's housing), plus s9, which nothing
     * names. Fictional, every link under example.invalid.
     */
    private fun resourced(): BackupData = fitted().let { data ->
        val owners = listOf(
            "c1" to component("c1"), "c2" to component("c2"), "c4" to component("c4"), "c5" to component("c5"),
            "s1" to supply("s1"), "s4" to supply("s4"), "s6" to supply("s6"), "s7" to supply("s7"), "s8" to supply("s8"),
            "s9" to supply("s9"),
        )
        data.copy(
            supplyItems = data.supplyItems + supplyItemOf("s9", "Example Fuse").toDto(),
            attachments = data.attachments + owners.map { (id, owner) -> fileOf("f$id", owner.first) },
            assetReferences = data.assetReferences + owners.map { (id, owner) -> linkOf("r$id", owner.second) },
        )
    }

    private fun component(id: String) =
        AttachmentOwner.OfInstalledComponent(InstalledComponentId(id)) to ReferenceOwner.OfInstalledComponent(InstalledComponentId(id))

    private fun supply(id: String) = AttachmentOwner.OfSupplyItem(SupplyId(id)) to ReferenceOwner.OfSupplyItem(SupplyId(id))

    private fun fileOf(id: String, owner: AttachmentOwner): AttachmentDto {
        val bytes = "Example $id sheet".toByteArray()
        return Attachment(
            id = AttachmentId(id), owner = owner, kind = AttachmentKind.DOCUMENT, displayName = "$id file",
            mimeType = "application/pdf", sizeBytes = bytes.size.toLong(), sha256 = InMemoryAttachmentStore.sha256Hex(bytes),
            storageLocator = "${AttachmentLocator.dirFor(owner)}/$id.pdf", capturedOn = null, createdAt = 100L, updatedAt = 100L,
        ).toDto()
    }

    private fun linkOf(id: String, owner: ReferenceOwner): AssetReferenceDto = AssetReference(
        id = ReferenceId(id), owner = owner, kind = ReferenceKind.WEB_URL, uri = "https://example.invalid/$id",
        displayName = "Example $id page", description = "", scheme = "https", createdAt = 100L, updatedAt = 100L,
    ).toDto()

    /**
     * Every installed component of a carried asset travels (R47-4), so its files and links do, verbatim — the removed
     * pack's and the anode's included — and the pack decodes with every owner inside it; the compressor's stay home.
     */
    @Test
    fun aPackCarriesItsComponentsFilesAndLinks() {
        val data = resourced()

        val pack = selected(data, HEATER).data

        assertEquals(listOf("fc1", "fc2", "fc4"), pack.attachments.filter { it.installedComponentId != null }.map { it.id })
        assertEquals(listOf("rc1", "rc2", "rc4"), pack.assetReferences.filter { it.installedComponentId != null }.map { it.id })
        assertEquals(
            data.attachments.filter { it.id in setOf("fc1", "fc2", "fc4") } to data.assetReferences.filter { it.id in setOf("rc1", "rc2", "rc4") },
            pack.attachments.filter { it.installedComponentId != null } to pack.assetReferences.filter { it.installedComponentId != null },
            "verbatim",
        )
        assertEquals(pack, BackupCodec.decode(encode(pack)).data, "every component a carried row names is in the pack")
    }

    /**
     * R69-7 (H5): a SupplyItem in use travels whole — its files and links with it, archived item included, whichever
     * carried row names it — and the pack's artifacts plan names every carried file, so its bytes travel too.
     */
    @Test
    fun aPackCarriesTheResourcesOfEverySupplyItemInUseWithBytes() {
        val data = resourced()

        val pack = selected(data, HEATER).data

        assertEquals(listOf("fs1", "fs4", "fs6", "fs7"), pack.attachments.filter { it.supplyItemId != null }.map { it.id })
        assertEquals(listOf("rs1", "rs4", "rs6", "rs7"), pack.assetReferences.filter { it.supplyItemId != null }.map { it.id })
        assertEquals(
            data.attachments.filter { it.id in setOf("fs1", "fs4", "fs6", "fs7") },
            pack.attachments.filter { it.supplyItemId != null },
            "verbatim",
        )
        assertEquals(
            pack.attachments.map { it.storageLocator }.sorted(),
            artifactsPlanOf(pack, "pack-test", 1L).entries.map { it.locator }.sorted(),
            "the bytes follow the carried rows",
        )
        assertTrue("supply-items/s4/fs4.pdf" in artifactsPlanOf(pack, "pack-test", 1L).entries.map { it.locator })
        assertEquals(pack, BackupCodec.decode(encode(pack)).data, "every SupplyItem a carried row names is in the pack")
    }

    /** A SupplyItem no carried row names stays home with its resources; one nothing names never travels at all. */
    @Test
    fun anUnusedSupplyItemsResourcesStayHome() {
        val data = resourced()

        val heater = selected(data, HEATER).data
        assertTrue(heater.attachments.none { it.id in setOf("fs8", "fs9", "fc5") }, "the compressor's and the unused item's")
        assertTrue(heater.assetReferences.none { it.id in setOf("rs8", "rs9", "rc5") })

        val compressor = selected(data, COMPRESSOR).data
        assertEquals(listOf("at3", "fc5", "fs8"), compressor.attachments.map { it.id }, "its own, its housing's and s8's")
        assertEquals(listOf("rc5", "rs8"), compressor.assetReferences.map { it.id }, "s9 is named by nothing")
        assertEquals(compressor, BackupCodec.decode(encode(compressor)).data)
    }
}
