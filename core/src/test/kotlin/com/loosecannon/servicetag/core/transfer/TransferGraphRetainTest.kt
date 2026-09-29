package com.loosecannon.servicetag.core.transfer

import com.loosecannon.servicetag.core.testing.successionOf
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.transfer.TransferFixtures.ANODE
import com.loosecannon.servicetag.core.transfer.TransferFixtures.COMPRESSOR
import com.loosecannon.servicetag.core.transfer.TransferFixtures.EMPTY_GROUP
import com.loosecannon.servicetag.core.transfer.TransferFixtures.GROUP
import com.loosecannon.servicetag.core.transfer.TransferFixtures.HEATER
import com.loosecannon.servicetag.core.transfer.TransferFixtures.OPENER
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/**
 * #77 (C3; AC 11–13, nt-4) — what stays after a transfer: the archive without the held assets and every
 * row naming them, and an honest refusal when a row that stays still points at one that left.
 */
class TransferGraphRetainTest {

    private val estate = TransferFixtures.estate()
    private val heaterAndAnode = setOf(AssetId(HEATER), AssetId(ANODE))

    /** The held assets, their rows, the wholly held group, the 2.6 link and its tag, and the returned loan all go. */
    @Test
    fun heldAndEverythingNamingItDrops() {
        val kept = assertIs<TransferRetention.Retained>(TransferGraph.retain(estate, heaterAndAnode)).data

        assertEquals(listOf(OPENER, COMPRESSOR), kept.assets.map { it.id })
        assertEquals(listOf("t3", "t5"), kept.nfcTags.map { it.id }, "the spare stays; the link's tag goes with the link")
        assertEquals(emptyList(), kept.externalLinks)
        assertEquals(listOf("d2"), kept.measurementDefinitions.map { it.id })
        assertEquals(emptyList(), kept.eventProfiles)
        assertEquals(listOf("e3"), kept.assetEvents.map { it.id })
        assertEquals(listOf("at3"), kept.attachments.map { it.id })
        assertEquals(listOf(EMPTY_GROUP), kept.maintenanceGroups.map { it.id })
        assertEquals(listOf("s2"), kept.maintenanceSchedules.map { it.id })
        assertEquals(listOf("cl3"), kept.occurrenceClosures.map { it.id })
        assertEquals(emptyList(), kept.assetReferences + kept.seasonActivations + kept.assetConditions)
        assertEquals(listOf("hs2"), kept.healthSubjects.map { it.id })
        assertEquals(estate.assetCategories, kept.assetCategories, "the catalog is the sender's")
        assertEquals(listOf("sc2") to listOf("n2"), kept.serviceCases.map { it.id } to kept.serviceCaseEntries.map { it.id })
        assertEquals(listOf("l2"), kept.assetLoans.map { it.id }, "the heater's returned loan leaves every later backup (nt-4)")
    }

    /**
     * mn-2: holding nothing keeps everything. The fixture fills every list the archive has, so a list that
     * `retain` forgets or filters wrongly — #15's tables and every later one included — fails here.
     */
    @Test
    fun retainingNothingIsTheIdentity() {
        val lists = Json.encodeToJsonElement(BackupData.serializer(), estate).jsonObject
        assertEquals(emptyList(), lists.filterValues { it.jsonArray.isEmpty() }.keys.toList(), "the fixture fills every list")

        assertEquals(TransferRetention.Retained(estate), TransferGraph.retain(estate, emptySet()))
    }

    /** MJ-2: an empty group is wholly held by nothing, so no held set ever drops it. */
    @Test
    fun anEmptyGroupIsAlwaysRetained() {
        listOf(heaterAndAnode, setOf(AssetId(OPENER)), setOf(AssetId(COMPRESSOR)), emptySet()).forEach { held ->
            val kept = assertIs<TransferRetention.Retained>(TransferGraph.retain(estate, held)).data
            assertEquals(true, kept.maintenanceGroups.any { it.id == EMPTY_GROUP }, "held $held")
        }
    }

    @Test
    fun theRetainedArchiveDecodes() {
        val kept = assertIs<TransferRetention.Retained>(TransferGraph.retain(estate, heaterAndAnode)).data

        val decoded = BackupCodec.decode(
            BackupCodec.encode(kept, appVersion = "1.4.1", schemaVersion = 13, createdAt = 1L, backupSetId = "set-kept"),
        )

        assertEquals(kept, decoded.data)
    }

    /**
     * Exactly the held set, no recomputed closure: holding the heater alone keeps its anode, whose parent
     * is gone, and the flush round, whose row names it; and an event of an asset that stays, naming a
     * wholly held group's schedule, points into what left.
     */
    @Test
    fun aRetainedRowNamingAHeldRowIsEntangled() {
        val heaterOnly = assertIs<TransferRetention.Entangled>(TransferGraph.retain(estate, setOf(AssetId(HEATER))))
        assertEquals(
            listOf(
                EntangledRef("assets", ANODE, "assets", HEATER),
                EntangledRef("maintenanceGroups", GROUP, "assets", HEATER),
            ),
            heaterOnly.refs,
        )

        val stray = completionOf("e8", "2026-05-01", "2026-05-01", assetId = COMPRESSOR, scheduleId = "sg").toDto()
        val withStray = estate.copy(assetEvents = estate.assetEvents + stray)
        assertEquals(
            listOf(EntangledRef("assetEvents", "e8", "maintenanceSchedules", "sg")),
            assertIs<TransferRetention.Entangled>(TransferGraph.retain(withStray, heaterAndAnode)).refs,
        )
    }

    /**
     * #86 (C6, Hazard 1): a succession naming a held asset at either end drops, like a loan — and is never
     * `Entangled`, so a mark, a withdrawal and an export are never refused for one. A row between two staying
     * assets stays, and what stays decodes.
     */
    @Test
    fun aSuccessionNamingAHeldAssetDropsAndIsNeverEntangled() {
        val staying = successionOf("s3", predecessor = COMPRESSOR, successor = OPENER)
        val lineage = estate.copy(
            assetSuccessions = listOf(
                successionOf("s1", predecessor = HEATER, successor = COMPRESSOR),
                successionOf("s2", predecessor = OPENER, successor = ANODE),
                staying,
            ).map { it.toDto() },
        )

        val kept = assertIs<TransferRetention.Retained>(TransferGraph.retain(lineage, heaterAndAnode)).data

        assertEquals(listOf(staying.toDto()), kept.assetSuccessions)
        assertEquals(lineage.assetSuccessions, assertIs<TransferRetention.Retained>(TransferGraph.retain(lineage, emptySet())).data.assetSuccessions)
    }
}
