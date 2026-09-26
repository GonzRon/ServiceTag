package com.loosecannon.servicetag.core.backup

import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/**
 * Decoder conformance for the Stage-A bundle tool (`tools/servicetag-bundle`). The committed
 * fixture archive (`core/src/test/resources/stage-a-synthetic-estate.zip`, built by the tool's
 * CLI from `tools/servicetag-bundle/fixtures/synthetic-estate.json`) must decode with the app's
 * own [BackupCodec], and every row the generator wrote must carry exactly the field names its
 * matching DTO's `@Serializable` descriptor expects -- so a DTO field added later with a default
 * cannot drift past the generator unnoticed. Tampering is already covered by [BackupCodecTest];
 * this class does not repeat it.
 */
class StageABundleConformanceTest {

    private fun resourceBytes(): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream(RESOURCE_NAME)
            ?: error("missing test resource $RESOURCE_NAME")
        return stream.use { it.readBytes() }
    }

    @Test
    fun `decodes the fixture archive with the counts the fixture declares`() {
        val backup = BackupCodec.decode(resourceBytes())

        assertEquals(5, backup.manifest.formatVersion)
        assertTrue(backup.manifest.backupSetId.isNotBlank())

        assertEquals(4, backup.data.assets.size)
        assertEquals(0, backup.data.nfcTags.size)
        assertEquals(0, backup.data.externalLinks.size)
        assertEquals(5, backup.data.measurementDefinitions.size)
        assertEquals(1, backup.data.eventProfiles.size)
        assertEquals(2, backup.data.assetEvents.size)
        assertEquals(0, backup.data.attachments.size)
        assertEquals(1, backup.data.eventProfiles.sumOf { it.fields.size })
        assertEquals(1, backup.data.eventProfiles.sumOf { it.consumables.size })
        assertEquals(5, backup.data.assetEvents.sumOf { it.measurements.size })
        assertEquals(1, backup.data.assetEvents.sumOf { it.consumables.size })
    }

    @Test
    fun `the child asset's parentAssetId resolves to its parent`() {
        val backup = BackupCodec.decode(resourceBytes())
        val parent = backup.data.assets.single { it.name == "Greenhouse Heater" }
        val child = backup.data.assets.single { it.name == "Greenhouse Heater Thermostat" }

        assertEquals(parent.id, child.parentAssetId)
    }

    @Test
    fun `the fixture's non-ASCII note survives the decoder`() {
        val backup = BackupCodec.decode(resourceBytes())
        val parent = backup.data.assets.single { it.name == "Greenhouse Heater" }

        // The fixture's one non-ASCII character round-trips through the pinned `ensure_ascii`
        // escape in data.json and back out through the decoder -- not just present in the bytes.
        assertEquals("runs October through April; façade repainted 2025", parent.notes)
    }

    @Test
    fun `one event's measurement carries the expected value`() {
        val backup = BackupCodec.decode(resourceBytes())
        val definition = backup.data.measurementDefinitions.single { it.key == "temp_c" }
        val event = backup.data.assetEvents.single { it.title == "Spring startup" }
        val measurement = event.measurements.single { it.definitionId == definition.id }

        assertEquals(18.5, measurement.valueNum)
        assertEquals(null, measurement.valueText)
    }

    @Test
    fun `every row's key set equals its DTO's descriptor element names`() {
        val entries = readZipEntries(resourceBytes())
        val data = Json.parseToJsonElement(String(entries.getValue("data.json"))).jsonObject

        // The root object itself: BackupData defaults eleven of its fourteen tables to emptyList(), so
        // a *new* table added there tomorrow would never be emitted by the generator and would
        // decode away silently unless the root's own key set is pinned here too. The fixture is a
        // **format-5** archive and the generator writes format 5, so the three format-6 tables, the
        // one format-7 table, the three format-8 tables and format 9's categories are subtracted by
        // name — which keeps the guard live: a further table added to BackupData without a thought
        // for the generator still fails here. (A restore of the bundle promotes its assets'
        // categories itself, so the generator needs no category list.)
        assertEquals(
            BackupData.serializer().descriptor.elementNames.toSet() -
                FORMAT_6_TABLES - FORMAT_7_TABLES - FORMAT_8_TABLES - FORMAT_9_TABLES,
            data.keys,
            "data.json root",
        )

        // The format ≤7 asset key set: `AssetDto`'s names minus format 8's five, which the decoder
        // writes into an old tree itself and which the format-5 generator therefore never emits.
        assertKeysMatch(
            "assets",
            data.getValue("assets").jsonArray,
            AssetDto.serializer().descriptor.elementNames.toSet() - FORMAT_8_ASSET_FIELDS,
        )
        assertKeysMatch(
            "measurementDefinitions",
            data.getValue("measurementDefinitions").jsonArray,
            MeasurementDefinitionDto.serializer().descriptor.elementNames,
        )

        // The bundle never carries these three tables -- the source format has no way to declare
        // an NFC tag, an external link or an attachment -- so the real contract is that they stay
        // empty, not a (vacuous) walk over zero rows.
        assertTrue(data.getValue("nfcTags").jsonArray.isEmpty(), "the generator never writes nfcTags")
        assertTrue(
            data.getValue("externalLinks").jsonArray.isEmpty(),
            "the generator never writes externalLinks",
        )
        assertTrue(
            data.getValue("attachments").jsonArray.isEmpty(),
            "the generator never writes attachments",
        )

        val profiles = data.getValue("eventProfiles").jsonArray
        assertKeysMatch("eventProfiles", profiles, EventProfileDto.serializer().descriptor.elementNames)
        profiles.forEach { profile ->
            assertKeysMatch(
                "eventProfiles[].fields",
                profile.jsonObject.getValue("fields").jsonArray,
                ProfileFieldDto.serializer().descriptor.elementNames,
            )
            assertKeysMatch(
                "eventProfiles[].consumables",
                profile.jsonObject.getValue("consumables").jsonArray,
                ProfileConsumableDto.serializer().descriptor.elementNames,
            )
        }

        val events = data.getValue("assetEvents").jsonArray
        assertKeysMatch(
            "assetEvents",
            events,
            AssetEventDto.serializer().descriptor.elementNames.toSet() - FORMAT_6_EVENT_FIELDS,
        )
        events.forEach { event ->
            assertKeysMatch(
                "assetEvents[].measurements",
                event.jsonObject.getValue("measurements").jsonArray,
                MeasurementDto.serializer().descriptor.elementNames,
            )
            assertKeysMatch(
                "assetEvents[].consumables",
                event.jsonObject.getValue("consumables").jsonArray,
                ConsumableUsageDto.serializer().descriptor.elementNames,
            )
        }
    }

    private fun assertKeysMatch(label: String, array: JsonArray, expectedElementNames: Iterable<String>) {
        val expected = expectedElementNames.toSet()
        array.forEach { element -> assertEquals(expected, element.jsonObject.keys, label) }
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                entries[entry.name] = zin.readBytes()
                zin.closeEntry()
            }
        }
        return entries
    }

    companion object {
        private const val RESOURCE_NAME = "stage-a-synthetic-estate.zip"

        /**
         * What format 6 added and the format-5 fixture therefore cannot carry. Named rather than
         * derived, so the key-set guards stay exact: anything added after these still fails.
         */
        private val FORMAT_6_TABLES =
            setOf("maintenanceGroups", "maintenanceSchedules", "occurrenceClosures")
        private val FORMAT_6_EVENT_FIELDS = setOf("scheduleId", "occurrenceOn", "detailsPending")

        /** What format 7 added, for the same reason [FORMAT_6_TABLES] is named rather than derived. */
        private val FORMAT_7_TABLES = setOf("assetReferences")

        /** What format 8 added, named for the same reason. */
        private val FORMAT_8_TABLES = setOf("seasonActivations", "assetConditions", "healthSubjects")
        private val FORMAT_9_TABLES = setOf("assetCategories")
        private val FORMAT_8_ASSET_FIELDS = setOf(
            "seasonMode", "blackoutStartMmdd", "blackoutEndMmdd", "healthAggregation",
            "healthPrimarySubjectId",
        )
    }
}
