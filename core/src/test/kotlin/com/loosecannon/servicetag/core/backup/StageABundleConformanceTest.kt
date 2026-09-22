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
        val json = Json
        val entries = readZipEntries(resourceBytes())
        val data = json.parseToJsonElement(String(entries.getValue("data.json"))).jsonObject

        assertKeysMatch(data.getValue("assets").jsonArray, AssetDto.serializer().descriptor.elementNames)
        assertKeysMatch(data.getValue("nfcTags").jsonArray, NfcTagDto.serializer().descriptor.elementNames)
        assertKeysMatch(
            data.getValue("externalLinks").jsonArray,
            ExternalLinkDto.serializer().descriptor.elementNames,
        )
        assertKeysMatch(
            data.getValue("measurementDefinitions").jsonArray,
            MeasurementDefinitionDto.serializer().descriptor.elementNames,
        )
        assertKeysMatch(
            data.getValue("attachments").jsonArray,
            AttachmentDto.serializer().descriptor.elementNames,
        )

        val profiles = data.getValue("eventProfiles").jsonArray
        assertKeysMatch(profiles, EventProfileDto.serializer().descriptor.elementNames)
        profiles.forEach { profile ->
            assertKeysMatch(
                profile.jsonObject.getValue("fields").jsonArray,
                ProfileFieldDto.serializer().descriptor.elementNames,
            )
            assertKeysMatch(
                profile.jsonObject.getValue("consumables").jsonArray,
                ProfileConsumableDto.serializer().descriptor.elementNames,
            )
        }

        val events = data.getValue("assetEvents").jsonArray
        assertKeysMatch(events, AssetEventDto.serializer().descriptor.elementNames)
        events.forEach { event ->
            assertKeysMatch(
                event.jsonObject.getValue("measurements").jsonArray,
                MeasurementDto.serializer().descriptor.elementNames,
            )
            assertKeysMatch(
                event.jsonObject.getValue("consumables").jsonArray,
                ConsumableUsageDto.serializer().descriptor.elementNames,
            )
        }
    }

    private fun assertKeysMatch(array: JsonArray, expectedElementNames: Iterable<String>) {
        val expected = expectedElementNames.toSet()
        array.forEach { element -> assertEquals(expected, element.jsonObject.keys) }
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
    }
}
