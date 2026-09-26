package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.seasonalAssetOf
import com.loosecannon.servicetag.core.testing.subjectOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Backup **format 9** (#74, C11): one new list, `assetCategories`, sorted by key, counted in the
 * manifest and unique by key; everything format 8 carries decodes exactly as it did, because
 * `LegacyArchive.LAST_LEGACY_FORMAT` stays 7 — raising it would send every format-8 archive through
 * the ≤7 upgrade, which strips the three 1.4 lists and resets season, break, health and policy.
 */
class BackupFormat9Test {

    /** In key order, which is the order the codec writes them in: `é` sorts after `w`. */
    private val rows = listOf(
        AssetCategory("appliance", "Appliance", 100L, 150L),
        AssetCategory("water heater", "Water heater", 90L, 300L),
        AssetCategory("éclairage", "Éclairage", 120L, 120L),
    )

    private val compressor = plainAssetOf("a1", "Compressor").copy(category = "Appliance")

    private fun data(categories: List<AssetCategory> = rows) = BackupData(
        assets = listOf(compressor.toDto()),
        nfcTags = emptyList(),
        externalLinks = emptyList(),
        assetCategories = categories.map { it.toDto() },
    )

    /** The numbers this tip carries, as literals: the format moved, the legacy boundary did not. */
    @Test
    fun theFormatIsNineAndTheLegacyBoundaryStaysSeven() {
        assertEquals(9, BackupCodec.FORMAT_VERSION)
        assertEquals(7, LegacyArchive.LAST_LEGACY_FORMAT)
    }

    /** Hazard: a category dropped or reordered in transit. Shuffled in, key order out, counted. */
    @Test
    fun categoriesSurviveARoundTripInKeyOrder() {
        val bytes = archiveOf(data(rows.reversed()))

        val decoded = BackupCodec.decode(bytes)

        assertEquals(9, decoded.manifest.formatVersion)
        assertEquals(rows, decoded.data.assetCategories.map { it.toDomain() })
        assertEquals(3, decoded.manifest.counts["assetCategories"])
        assertEquals(
            listOf("appliance", "water heater", "éclairage"),
            dataTreeOf(bytes).getValue("assetCategories").jsonArray.map { it.jsonObject.getValue("key").jsonPrimitive.content },
        )
    }

    /** The DTO is the table's four columns, and the list is the data entry's last key. */
    @Test
    fun theDtoIsTheFourColumns() {
        assertEquals(
            listOf("key", "display", "createdAt", "updatedAt"),
            AssetCategoryDto.serializer().descriptor.elementNames.toList(),
        )
        assertEquals("assetCategories", BackupData.serializer().descriptor.elementNames.last())
    }

    /** A format-8 archive has no categories and decodes to none. */
    @Test
    fun aFormat8ArchiveDecodesWithNoCategories() {
        val decoded = BackupCodec.decode(archiveOf(data(emptyList()), formatVersion = 8))

        assertEquals(8, decoded.manifest.formatVersion)
        assertEquals(emptyList(), decoded.data.assetCategories)
        assertEquals(listOf(compressor), decoded.data.assets.map { it.toDomain() })
    }

    /** What a 1.4.x build (format 8) does with this one: refuses it loudly, before a row is read. */
    @Test
    fun aFormat8BuildRefusesAFormat9Archive() {
        val refusal = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(archiveOf(data()), supportedFormat = 8) }

        assertEquals(9, refusal.found)
        assertEquals(8, refusal.supported)
    }

    /**
     * No shipped writer put a category into a format ≤8 archive, so one that carries a row is a
     * hand-built file and is refused — through the strict format-8 decode and through the ≤7 upgrade
     * alike, which is the refusal a format-8 build gives its unknown key.
     */
    @Test
    fun anOlderArchiveCarryingACategoryIsRefused() {
        for (format in listOf(8, 7)) {
            val refusal = assertFailsWith<BackupCorrupt>("format $format") {
                BackupCodec.decode(archiveOf(data(), formatVersion = format))
            }
            assertTrue(refusal.message!!.startsWith("assetCategories:"), refusal.message)
        }
    }

    /** Hazard: the duplicate key the table's primary key would refuse only halfway through a restore. */
    @Test
    fun aDuplicateKeyIsRefused() {
        val twice = data(rows + AssetCategory("appliance", "APPLIANCE", 1L, 1L))

        val refusal = assertFailsWith<BackupCorrupt> { BackupCodec.decode(archiveOf(twice)) }

        assertEquals("assetCategories: duplicate id appliance", refusal.message)
    }

    // --- AC 4: export, then replace ---------------------------------------------------------------

    /**
     * AC 4: the owner's categories survive a backup and a restore — through the production export
     * and the production replace, into an install that held other rows — the unused one included.
     */
    @Test
    fun anExportThenReplaceCarriesTheCategories() = runBlocking<Unit> {
        val source = BackupInstall(setId = "set-source")
        source.assets.upsert(compressor)
        rows.forEach { source.categories.upsert(it) }
        val target = BackupInstall()
        target.categories.upsert(AssetCategory("old key", "Old key", 1L, 1L))

        target.replace.run(source.export.run().data)

        assertEquals(rows, target.categories.all())
        assertEquals(listOf(compressor), target.assets.all())
    }

    // --- format 8's 1.4 data under format 9 --------------------------------------------------------

    /** MANUAL, TRACK_ONE, no break. */
    private val generator = plainAssetOf("a2", "Generator").copy(
        seasonMode = SeasonMode.MANUAL, healthAggregation = HealthAggregation.TRACK_ONE,
    )

    private val preService = scheduleOf(
        id = "s1", assetId = "a1", title = "Auger service", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
        anchorOn = "2026-01-01", servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14,
        createdOn = "2026-01-01", updatedOn = "2026-02-01", ruleChangedOn = "2026-01-05",
    )

    /**
     * N1's case: a format-8 archive carrying a MANUAL asset, a CALENDAR asset with a break, a
     * PRE_SERVICE schedule, health rows and all three 1.4 lists decodes **unchanged** under format 9.
     * Raising `LAST_LEGACY_FORMAT` to 8 would route it through the ≤7 upgrade and strip them.
     */
    @Test
    fun aFormat8ArchiveOf14DataDecodesUnchanged() {
        val fixture = BackupData(
            assets = listOf(seasonalAssetOf(), generator).map { it.toDto() },
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            maintenanceSchedules = listOf(preService.toDto()),
            seasonActivations = listOf(
                activationOf("act-1", assetId = "a2", action = SeasonAction.START, occurredOn = "2026-03-01", eventId = "e-9"),
            ).map { it.toDto() },
            assetConditions = listOf(
                conditionOf("c-1", eventId = "e-8"),
                conditionOf("c-2", assetId = "a2", condition = OperationalCondition.OPERATIONAL, createdAt = 420L),
            ).map { it.toDto() },
            healthSubjects = listOf(
                subjectOf(
                    "h1", assetId = "a1", scheduleId = "s1", archivedAt = 700L,
                    kind = HealthSubjectKind.MEDIUM, driver = HealthDriver.MAINTENANCE_OVERDUE,
                ),
            ).map { it.toDto() },
        )

        val decoded = BackupCodec.decode(archiveOf(fixture, formatVersion = 8))

        assertEquals(8, decoded.manifest.formatVersion)
        assertEquals(fixture, decoded.data)
        assertEquals(SeasonMode.MANUAL.name, decoded.data.assets.single { it.id == "a2" }.seasonMode)
        assertEquals("01-10", decoded.data.assets.single { it.id == "a1" }.blackoutStartMmdd)
        assertEquals(ServicePolicy.PRE_SERVICE.name, decoded.data.maintenanceSchedules.single().servicePolicy)
    }
}
