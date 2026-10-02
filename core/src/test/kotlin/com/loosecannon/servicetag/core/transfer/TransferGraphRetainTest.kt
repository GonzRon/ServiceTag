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
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.installedComponentOf
import com.loosecannon.servicetag.core.testing.supplyItemOf
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
        // #47 (C13): one installed component on the heater, so `installedComponents` is a list this identity checks.
        val fitted = estate.copy(
            installedComponents = listOf(installedComponentOf("c1", assetId = HEATER, name = "Example Element").toDto()),
        )
        val lists = Json.encodeToJsonElement(BackupData.serializer(), fitted).jsonObject
        assertEquals(emptyList(), lists.filterValues { it.jsonArray.isEmpty() }.keys.toList(), "the fixture fills every list")
        assertEquals(true, "installedComponents" in lists, "the installed components are among them")

        assertEquals(TransferRetention.Retained(fitted), TransferGraph.retain(fitted, emptySet()))
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

    // --- #69 (C15, row 36): a held asset's components take their files and links; a SupplyItem's always stay --------

    /**
     * The estate with installed components (fictional) on the heater — a tray (c1) whose direct link is the battery
     * SupplyItem s1, and an old tray removed on 2026-01-10 (c2) — on the anode (c4) and on the compressor (cx); each, and
     * s1, owns one file and one link. Only a held row (c1) names s1.
     */
    private fun resourced(): BackupData {
        val components = listOf("c1", "c2", "c4", "cx").map { InstalledComponentId(it) }
        return estate.copy(
            supplyItems = listOf(supplyItemOf("s1", "Example 12 V Battery").toDto()),
            installedComponents = listOf(
                installedComponentOf("c1", assetId = HEATER, name = "Example Battery Tray", supplyId = "s1"),
                installedComponentOf("c2", assetId = HEATER, name = "Example Old Tray", installedOn = "2025-01-10", removedOn = "2026-01-10"),
                installedComponentOf("c4", assetId = ANODE, name = "Example Anode Sleeve"),
                installedComponentOf("cx", assetId = COMPRESSOR, name = "Example Intake Housing"),
            ).map { it.toDto() },
            attachments = estate.attachments +
                components.map { fileOf("f${it.value}", AttachmentOwner.OfInstalledComponent(it)) } +
                fileOf("fs1", AttachmentOwner.OfSupplyItem(SupplyId("s1"))),
            assetReferences = estate.assetReferences +
                components.map { linkOf("r${it.value}", ReferenceOwner.OfInstalledComponent(it)) } +
                linkOf("rs1", ReferenceOwner.OfSupplyItem(SupplyId("s1"))),
        )
    }

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
     * The held assets' components leave, current and removed, and their files and links leave with them — so the kept
     * archive names no component it does not carry and decodes; the compressor's housing keeps its own.
     */
    @Test
    fun aHeldAssetsComponentsResourcesDropAndTheKeptArchiveDecodes() {
        val data = resourced()

        val kept = assertIs<TransferRetention.Retained>(TransferGraph.retain(data, heaterAndAnode)).data

        assertEquals(
            kept,
            BackupCodec.decode(
                BackupCodec.encode(kept, appVersion = "1.4.1", schemaVersion = 13, createdAt = 1L, backupSetId = "set-kept"),
            ).data,
            "every kept row's owner is in the kept archive",
        )
        assertEquals(listOf("cx"), kept.installedComponents.map { it.id })
        assertEquals(listOf("at3", "fcx", "fs1"), kept.attachments.map { it.id }, "the heater's, the anode's and their components' go")
        assertEquals(listOf("rcx", "rs1"), kept.assetReferences.map { it.id })
    }

    /** H5: a SupplyItem is global — it and its files and links stay whole, even when only a held row names it. */
    @Test
    fun aSupplyItemsResourcesAreKeptWhenOnlyHeldRowsNameIt() {
        val data = resourced()

        val kept = assertIs<TransferRetention.Retained>(TransferGraph.retain(data, heaterAndAnode)).data

        assertEquals(data.supplyItems, kept.supplyItems, "s1, named only by the held tray, stays")
        assertEquals(data.attachments.filter { it.supplyItemId != null }, kept.attachments.filter { it.supplyItemId != null })
        assertEquals(data.assetReferences.filter { it.supplyItemId != null }, kept.assetReferences.filter { it.supplyItemId != null })
    }
}
