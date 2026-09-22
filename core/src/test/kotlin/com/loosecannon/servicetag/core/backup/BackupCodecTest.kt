package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.EventId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class BackupCodecTest {

    // --- fixture -------------------------------------------------------------------------------

    private fun fixture(): BackupData = BackupData(
        assets = listOf(
            AssetDto("a1", "Furnace", "basement unit", "hvac", "filter 16x25", "ACTIVE", 100L, 200L),
            AssetDto("a2", "Mower", "", "yard", "", "ARCHIVED", 101L, 201L),
            AssetDto("a3", "Water heater", "40 gal", "plumbing", "", "ACTIVE", 102L, 202L),
        ),
        nfcTags = listOf(
            // bound to an asset, every optional field filled in
            NfcTagDto("t1", "V1", "11111111-1111-4111-8111-111111111111", "a1", null, "ACTIVE", "furnace tag", "04A224B2", 300L, 400L, 103L, 203L),
            // bound to a standalone link
            NfcTagDto("t2", "V1", "key-t2", null, "l3", "ACTIVE", null, null, null, null, 104L, 204L),
            // unbound, everything optional is null
            NfcTagDto("t3", "V1", "key-t3", null, null, "UNBOUND", null, null, null, null, 105L, 205L),
            // lost tag still pointing at an asset
            NfcTagDto("t4", "V1", "key-t4", "a2", null, "LOST", "mower", null, 301L, null, 106L, 206L),
        ),
        externalLinks = listOf(
            ExternalLinkDto("l1", "a1", "JOPLIN", "Furnace notes", "joplin://x-callback-url/openNote?id=abc", 107L, 500L, 207L),
            ExternalLinkDto("l2", "a2", "WEB", "Manual", "https://example.invalid/mower.pdf", 108L, null, 208L),
            // standalone link (no asset)
            ExternalLinkDto("l3", null, "OBSIDIAN", "Loose note", "obsidian://open?vault=v&file=f", 109L, null, 209L),
        ),
    )

    // --- journal fixtures ------------------------------------------------------------------------

    private fun assetDto(id: String) = AssetDto(id, "Asset $id", "", "", "", "ACTIVE", 1L, 2L)

    private fun definitionDto(
        id: String,
        assetId: String,
        valueType: String = "NUMBER",
        isMeter: Boolean = false,
        kind: String = "ENTERED",
        formula: String? = null,
        sourceAId: String? = null,
        sourceBId: String? = null,
    ) = MeasurementDefinitionDto(
        id = id, assetId = assetId, key = "key-$id", label = "Label $id", unit = "",
        valueType = valueType, decimals = 1, rangeLow = null, rangeHigh = null,
        isMeter = isMeter, sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 2L,
        kind = kind, formula = formula, sourceAId = sourceAId, sourceBId = sourceBId,
    )

    private fun derivedDefinitionDto(id: String, assetId: String, sourceAId: String, sourceBId: String) =
        definitionDto(id, assetId, kind = "DERIVED", formula = "PERCENT_DROP", sourceAId = sourceAId, sourceBId = sourceBId)

    private fun profileFieldDto(id: String, definitionId: String, sortOrder: Int = 0) =
        ProfileFieldDto(id, definitionId, required = true, sortOrder = sortOrder)

    private fun profileConsumableDto(id: String, sortOrder: Int = 0) =
        ProfileConsumableDto(id, "Consumable $id", 1.0, "unit", sortOrder)

    private fun eventProfileDto(
        id: String,
        assetId: String,
        fields: List<ProfileFieldDto> = emptyList(),
        consumables: List<ProfileConsumableDto> = emptyList(),
    ) = EventProfileDto(
        id = id, assetId = assetId, name = "Profile $id", eventKind = "MEASUREMENT",
        defaultTitle = "Title", templateKey = null, sortOrder = 0, archivedAt = null,
        createdAt = 1L, updatedAt = 2L, fields = fields, consumables = consumables,
    )

    private fun measurementDto(
        id: String,
        definitionId: String,
        valueNum: Double? = 1.0,
        valueText: String? = null,
        sortOrder: Int = 0,
    ) = MeasurementDto(id, definitionId, valueNum, valueText, "", sortOrder)

    private fun consumableUsageDto(id: String, sortOrder: Int = 0) =
        ConsumableUsageDto(id, "Usage $id", 1.0, "unit", sortOrder)

    private fun assetEventDto(
        id: String,
        assetId: String,
        profileId: String? = null,
        measurements: List<MeasurementDto> = emptyList(),
        consumables: List<ConsumableUsageDto> = emptyList(),
    ) = AssetEventDto(
        id = id, assetId = assetId, kind = "MEASUREMENT", title = "Title", profileId = profileId,
        occurredOn = "2026-09-15", occurredTime = null, tzId = "UTC", notes = "", source = "MANUAL",
        sourceRef = null, createdAt = 1L, updatedAt = 2L, measurements = measurements, consumables = consumables,
    )

    /** One asset carrying one row in each of the seven journal tables, all cross-referencing cleanly. */
    private fun journalFixture(): BackupData {
        val definition = definitionDto("d1", "a1")
        val field = profileFieldDto("pf1", "d1")
        val consumableSuggestion = profileConsumableDto("pc1")
        val profile = eventProfileDto("p1", "a1", listOf(field), listOf(consumableSuggestion))
        val measurement = measurementDto("m1", "d1", valueNum = 7.4)
        val usage = consumableUsageDto("cu1")
        val event = assetEventDto("e1", "a1", profileId = "p1", measurements = listOf(measurement), consumables = listOf(usage))
        return BackupData(
            assets = listOf(assetDto("a1")),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            measurementDefinitions = listOf(definition),
            eventProfiles = listOf(profile),
            assetEvents = listOf(event),
        )
    }

    // --- zip helpers used only by the tampering tests ------------------------------------------

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val entry: ZipEntry = zin.nextEntry ?: break
                out[entry.name] = zin.readBytes()
                zin.closeEntry()
            }
        }
        return out
    }

    private fun rezip(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            entries.forEach { (name, payload) ->
                val e = ZipEntry(name)
                e.time = 0L
                zos.putNextEntry(e)
                zos.write(payload)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Replace data.json and repair the manifest hash, so the corruption under test is the only one. */
    private fun resealed(bytes: ByteArray, newData: ByteArray): ByteArray {
        val entries = unzip(bytes)
        val oldHash = sha256Hex(entries.getValue(BackupCodec.DATA_ENTRY))
        val manifest = String(entries.getValue(BackupCodec.MANIFEST_ENTRY), Charsets.UTF_8)
            .replace(oldHash, sha256Hex(newData))
        entries[BackupCodec.MANIFEST_ENTRY] = manifest.toByteArray(Charsets.UTF_8)
        entries[BackupCodec.DATA_ENTRY] = newData
        return rezip(entries)
    }

    private fun encoded(data: BackupData = fixture(), setId: String = "set-1"): ByteArray =
        BackupCodec.encode(
            data,
            appVersion = "2.0",
            schemaVersion = 1,
            createdAt = 1_726_000_000_000L,
            backupSetId = setId,
        )

    // --- tests ---------------------------------------------------------------------------------

    @Test
    fun `round trip preserves the whole fixture`() {
        val data = fixture()
        val decoded = BackupCodec.decode(encoded(data))
        assertEquals(data, decoded.data)
    }

    @Test
    fun `ids are preserved verbatim`() {
        val decoded = BackupCodec.decode(encoded())
        assertEquals(listOf("a1", "a2", "a3"), decoded.data.assets.map { it.id })
        assertEquals(listOf("t1", "t2", "t3", "t4"), decoded.data.nfcTags.map { it.id })
        assertEquals(listOf("l1", "l2", "l3"), decoded.data.externalLinks.map { it.id })
        assertEquals("l3", decoded.data.nfcTags.first { it.id == "t2" }.linkId)
        assertEquals("a1", decoded.data.nfcTags.first { it.id == "t1" }.assetId)
    }

    @Test
    fun `zip contains exactly manifest and data`() {
        val names = unzip(encoded()).keys.toList()
        assertEquals(listOf(BackupCodec.MANIFEST_ENTRY, BackupCodec.DATA_ENTRY), names)
    }

    @Test
    fun `manifest carries counts and provenance`() {
        val manifest = BackupCodec.decode(encoded()).manifest
        assertEquals(BackupCodec.FORMAT_VERSION, manifest.formatVersion)
        assertEquals("2.0", manifest.appVersion)
        assertEquals(1, manifest.schemaVersion)
        assertEquals(1_726_000_000_000L, manifest.createdAt)
        assertEquals(
            mapOf(
                "assets" to 3, "nfcTags" to 4, "externalLinks" to 3,
                "measurementDefinitions" to 0, "eventProfiles" to 0, "assetEvents" to 0,
                "profileFields" to 0, "profileConsumables" to 0,
                "measurements" to 0, "consumableUsages" to 0,
                "attachments" to 0,
                "maintenanceGroups" to 0, "groupMembers" to 0,
                "maintenanceSchedules" to 0, "scheduleProviders" to 0,
                "occurrenceClosures" to 0,
            ),
            manifest.counts,
        )
    }

    @Test
    fun `manifest hash matches the data entry bytes`() {
        val bytes = encoded()
        val entries = unzip(bytes)
        val manifest = BackupCodec.decode(bytes).manifest
        assertEquals(sha256Hex(entries.getValue(BackupCodec.DATA_ENTRY)), manifest.dataSha256)
    }

    @Test
    fun `output is deterministic and sorted by id`() {
        val shuffled = fixture().let { f ->
            BackupData(f.assets.reversed(), f.nfcTags.reversed(), f.externalLinks.reversed())
        }
        assertContentEquals(encoded(), encoded(shuffled))
        val json = String(unzip(encoded(shuffled)).getValue(BackupCodec.DATA_ENTRY), Charsets.UTF_8)
        // each list is checked inside its own section: ids also appear as references elsewhere
        val assetsJson = json.substring(json.indexOf("\"assets\""), json.indexOf("\"nfcTags\""))
        val tagsJson = json.substring(json.indexOf("\"nfcTags\""), json.indexOf("\"externalLinks\""))
        val linksJson = json.substring(json.indexOf("\"externalLinks\""))
        assertTrue(assetsJson.indexOf("\"a1\"") < assetsJson.indexOf("\"a3\""), "assets not sorted by id")
        assertTrue(tagsJson.indexOf("\"t1\"") < tagsJson.indexOf("\"t4\""), "tags not sorted by id")
        assertTrue(linksJson.indexOf("\"l1\"") < linksJson.indexOf("\"l3\""), "links not sorted by id")
    }

    @Test
    fun `a tampered data entry is corrupt`() {
        val entries = unzip(encoded())
        val data = entries.getValue(BackupCodec.DATA_ENTRY).copyOf()
        val i = String(data, Charsets.UTF_8).indexOf("Furnace")
        data[i] = 'G'.code.toByte()
        entries[BackupCodec.DATA_ENTRY] = data
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(rezip(entries)) }
        assertTrue(e.message!!.contains("sha256") || e.message!!.contains("hash"), "unhelpful: ${e.message}")
    }

    @Test
    fun `a newer format version is refused`() {
        val entries = unzip(encoded())
        val manifest = String(entries.getValue(BackupCodec.MANIFEST_ENTRY), Charsets.UTF_8)
            .replace(
                Regex("\"formatVersion\"\\s*:\\s*${BackupCodec.FORMAT_VERSION}"),
                "\"formatVersion\": ${BackupCodec.FORMAT_VERSION + 1}",
            )
        entries[BackupCodec.MANIFEST_ENTRY] = manifest.toByteArray(Charsets.UTF_8)
        val e = assertFailsWith<BackupNewerFormat> { BackupCodec.decode(rezip(entries)) }
        assertEquals(BackupCodec.FORMAT_VERSION + 1, e.found)
        assertEquals(BackupCodec.FORMAT_VERSION, e.supported)
    }

    @Test
    fun `a missing data entry is corrupt`() {
        val entries = unzip(encoded())
        entries.remove(BackupCodec.DATA_ENTRY)
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(rezip(entries)) }
    }

    @Test
    fun `a missing manifest entry is corrupt`() {
        val entries = unzip(encoded())
        entries.remove(BackupCodec.MANIFEST_ENTRY)
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(rezip(entries)) }
    }

    @Test
    fun `bytes that are not a zip are corrupt`() {
        assertFailsWith<BackupCorrupt> { BackupCodec.decode("not a zip at all".toByteArray()) }
    }

    @Test
    fun `unparsable data json is corrupt`() {
        val broken = "{ this is not json".toByteArray(Charsets.UTF_8)
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(resealed(encoded(), broken)) }
    }

    @Test
    fun `an unknown asset status is corrupt`() {
        val data = String(unzip(encoded()).getValue(BackupCodec.DATA_ENTRY), Charsets.UTF_8)
            .replace("\"ARCHIVED\"", "\"MULCHED\"")
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(resealed(encoded(), data.toByteArray(Charsets.UTF_8))) }
    }

    @Test
    fun `an unknown payload format is corrupt`() {
        val data = String(unzip(encoded()).getValue(BackupCodec.DATA_ENTRY), Charsets.UTF_8)
            .replace("\"V1\"", "\"V9\"")
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(resealed(encoded(), data.toByteArray(Charsets.UTF_8))) }
    }

    @Test
    fun `an unknown link kind is corrupt`() {
        val data = String(unzip(encoded()).getValue(BackupCodec.DATA_ENTRY), Charsets.UTF_8)
            .replace("\"OBSIDIAN\"", "\"SCRIVENER\"")
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(resealed(encoded(), data.toByteArray(Charsets.UTF_8))) }
    }

    // --- referential integrity and id uniqueness -----------------------------------------------
    // encode() writes whatever it is handed, so a broken fixture is sealed into a well-formed
    // archive: the hash matches and every enum is nameable. Only decode() is under test here.

    @Test
    fun `the fixture graph is referentially whole`() {
        val decoded = BackupCodec.decode(encoded())
        assertEquals(fixture(), decoded.data)
    }

    @Test
    fun `a link pointing at a missing asset is corrupt`() {
        val f = fixture()
        val orphan = ExternalLinkDto("l4", "a404", "WEB", "Orphan", "https://example.invalid/x", 110L, null, 210L)
        val e = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(f.copy(externalLinks = f.externalLinks + orphan)))
        }
        assertTrue(e.message!!.contains("l4") && e.message!!.contains("a404"), "unhelpful: ${e.message}")
    }

    @Test
    fun `a tag pointing at a missing asset is corrupt`() {
        val f = fixture()
        val orphan = NfcTagDto("t5", "V1", "key-t5", "a404", null, "ACTIVE", null, null, null, null, 110L, 210L)
        val e = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(f.copy(nfcTags = f.nfcTags + orphan)))
        }
        assertTrue(e.message!!.contains("t5") && e.message!!.contains("a404"), "unhelpful: ${e.message}")
    }

    @Test
    fun `a tag pointing at a missing link is corrupt`() {
        val f = fixture()
        val orphan = NfcTagDto("t5", "V1", "key-t5", null, "l404", "ACTIVE", null, null, null, null, 110L, 210L)
        val e = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(f.copy(nfcTags = f.nfcTags + orphan)))
        }
        assertTrue(e.message!!.contains("t5") && e.message!!.contains("l404"), "unhelpful: ${e.message}")
    }

    @Test
    fun `a duplicate asset id is corrupt`() {
        val f = fixture()
        val twin = f.assets.first { it.id == "a1" }.copy(name = "Furnace again")
        val e = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(f.copy(assets = f.assets + twin)))
        }
        assertTrue(e.message!!.contains("assets") && e.message!!.contains("a1"), "unhelpful: ${e.message}")
    }

    @Test
    fun `a duplicate tag id is corrupt`() {
        val f = fixture()
        val twin = f.nfcTags.first { it.id == "t3" }.copy(payloadKey = "key-t3-again")
        val e = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(f.copy(nfcTags = f.nfcTags + twin)))
        }
        assertTrue(e.message!!.contains("nfcTags") && e.message!!.contains("t3"), "unhelpful: ${e.message}")
    }

    @Test
    fun `a duplicate link id is corrupt`() {
        val f = fixture()
        val twin = f.externalLinks.first { it.id == "l3" }.copy(label = "Loose note again")
        val e = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(f.copy(externalLinks = f.externalLinks + twin)))
        }
        assertTrue(e.message!!.contains("externalLinks") && e.message!!.contains("l3"), "unhelpful: ${e.message}")
    }

    @Test
    fun `decode of encode is identity over fifty random fixtures`() {
        val rng = Random(42)
        repeat(50) { round ->
            val data = randomData(rng)
            val decoded = BackupCodec.decode(
                BackupCodec.encode(data, "2.0", 1, rng.nextLong(0, 2_000_000_000_000L), "set-1"),
            )
            assertEquals(data, decoded.data, "round $round did not survive the round trip")
        }
    }

    // --- journal tables (format 2) ---------------------------------------------------------------

    @Test
    fun formatOneFileStillDecodes() {
        val f = fixture()
        val bytes = BackupCodec.encode(
            f,
            appVersion = "2.0",
            schemaVersion = 1,
            createdAt = 1_726_000_000_000L,
            backupSetId = "",
            formatVersion = 1,
        )
        val decoded = BackupCodec.decode(bytes)
        assertEquals(1, decoded.manifest.formatVersion)
        assertTrue(decoded.data.measurementDefinitions.isEmpty())
        assertTrue(decoded.data.eventProfiles.isEmpty())
        assertTrue(decoded.data.assetEvents.isEmpty())
        assertTrue(decoded.data.assets.all { it.templateKey == null })
    }

    @Test
    fun roundTripsAllSevenTables() {
        val data = journalFixture()
        val decoded = BackupCodec.decode(encoded(data))
        assertEquals(data, decoded.data)
        assertEquals(
            mapOf(
                "assets" to 1, "nfcTags" to 0, "externalLinks" to 0,
                "measurementDefinitions" to 1, "eventProfiles" to 1, "assetEvents" to 1,
                "profileFields" to 1, "profileConsumables" to 1,
                "measurements" to 1, "consumableUsages" to 1,
                "attachments" to 0,
                "maintenanceGroups" to 0, "groupMembers" to 0,
                "maintenanceSchedules" to 0, "scheduleProviders" to 0,
                "occurrenceClosures" to 0,
            ),
            decoded.manifest.counts,
        )
    }

    @Test
    fun measurementMustReferenceADefinitionOnTheSameAsset() {
        val f = journalFixture()
        val otherDefinition = definitionDto("d2", "a2")
        val badMeasurement = measurementDto("m2", "d2", valueNum = 1.0)
        val badEvent = f.assetEvents[0].copy(measurements = f.assetEvents[0].measurements + badMeasurement)
        val data = f.copy(
            assets = f.assets + assetDto("a2"),
            measurementDefinitions = f.measurementDefinitions + otherDefinition,
            assetEvents = listOf(badEvent),
        )
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("m2"), "unhelpful: ${e.message}")
    }

    @Test
    fun profileFieldMustReferenceAKnownDefinition() {
        val f = journalFixture()
        val badField = profileFieldDto("pf2", "d404")
        val badProfile = f.eventProfiles[0].copy(fields = f.eventProfiles[0].fields + badField)
        val data = f.copy(eventProfiles = listOf(badProfile))
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("pf2") && e.message!!.contains("d404"), "unhelpful: ${e.message}")
    }

    @Test
    fun eventMustReferenceAKnownAsset() {
        val f = journalFixture()
        val orphan = assetEventDto("e2", "a404")
        val data = f.copy(assetEvents = f.assetEvents + orphan)
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("e2") && e.message!!.contains("a404"), "unhelpful: ${e.message}")
    }

    @Test
    fun unknownValueTypeIsCorrupt() {
        val f = journalFixture()
        val bad = f.measurementDefinitions[0].copy(valueType = "WEIGHT")
        val data = f.copy(measurementDefinitions = listOf(bad))
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("d1") || e.message!!.contains("WEIGHT"), "unhelpful: ${e.message}")
    }

    @Test
    fun duplicateDefinitionIdIsCorrupt() {
        val f = journalFixture()
        val twin = f.measurementDefinitions[0].copy(key = "again")
        val data = f.copy(measurementDefinitions = f.measurementDefinitions + twin)
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("measurementDefinitions") && e.message!!.contains("d1"), "unhelpful: ${e.message}")
    }

    @Test
    fun duplicateProfileFieldIdIsCorrupt() {
        val f = journalFixture()
        val twin = f.eventProfiles[0].fields[0].copy(sortOrder = 1)
        val badProfile = f.eventProfiles[0].copy(fields = f.eventProfiles[0].fields + twin)
        val data = f.copy(eventProfiles = listOf(badProfile))
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("profileFields") && e.message!!.contains("pf1"), "unhelpful: ${e.message}")
    }

    @Test
    fun measurementValueShapeMustMatchItsDefinition() {
        data class Case(val label: String, val valueType: String, val valueNum: Double?, val valueText: String?)
        val cases = listOf(
            Case("NUMBER missing valueNum", "NUMBER", null, "7.8"),
            Case("TEXT carrying valueNum", "TEXT", 7.8, null),
            Case("BOOLEAN out of range", "BOOLEAN", 2.0, null),
            Case("NUMBER with both set", "NUMBER", 7.8, "seven"),
            Case("neither value set", "NUMBER", null, null),
        )
        cases.forEach { case ->
            val definition = definitionDto("d1", "a1", valueType = case.valueType)
            val measurement = MeasurementDto("m1", "d1", case.valueNum, case.valueText, "", 0)
            val event = assetEventDto("e1", "a1", measurements = listOf(measurement))
            val data = BackupData(
                assets = listOf(assetDto("a1")),
                nfcTags = emptyList(),
                externalLinks = emptyList(),
                measurementDefinitions = listOf(definition),
                eventProfiles = emptyList(),
                assetEvents = listOf(event),
            )
            val e = assertFailsWith<BackupCorrupt>(case.label) { BackupCodec.decode(encoded(data)) }
            assertTrue(e.message!!.contains("m1"), "${case.label}: unhelpful: ${e.message}")
        }
    }

    @Test
    fun encodeIsReproducibleWithTheNewLists() {
        val d1 = definitionDto("d1", "a1")
        val d2 = definitionDto("d2", "a1")
        val field1 = profileFieldDto("pf1", "d1", sortOrder = 0)
        val field2 = profileFieldDto("pf2", "d2", sortOrder = 1)
        val consumable1 = profileConsumableDto("pc1", sortOrder = 0)
        val consumable2 = profileConsumableDto("pc2", sortOrder = 1)
        val profile = eventProfileDto("p1", "a1", listOf(field1, field2), listOf(consumable1, consumable2))
        val m1 = measurementDto("m1", "d1", sortOrder = 0)
        val m2 = measurementDto("m2", "d2", sortOrder = 1)
        val u1 = consumableUsageDto("u1", sortOrder = 0)
        val u2 = consumableUsageDto("u2", sortOrder = 1)
        val event = assetEventDto("e1", "a1", profileId = "p1", measurements = listOf(m1, m2), consumables = listOf(u1, u2))
        val data = BackupData(
            assets = listOf(assetDto("a1")),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            measurementDefinitions = listOf(d1, d2),
            eventProfiles = listOf(profile),
            assetEvents = listOf(event),
        )
        val shuffled = data.copy(
            measurementDefinitions = data.measurementDefinitions.reversed(),
            eventProfiles = listOf(profile.copy(fields = profile.fields.reversed(), consumables = profile.consumables.reversed())),
            assetEvents = listOf(event.copy(measurements = event.measurements.reversed(), consumables = event.consumables.reversed())),
        )
        assertContentEquals(encoded(data), encoded(shuffled))
    }

    // --- journal tables (format 3): derived definitions ------------------------------------------

    @Test
    fun formatTwoFileStillDecodes() {
        val f = journalFixture()
        val bytes = BackupCodec.encode(
            f,
            appVersion = "2.0",
            schemaVersion = 1,
            createdAt = 1_726_000_000_000L,
            backupSetId = "",
            formatVersion = 2,
        )
        val decoded = BackupCodec.decode(bytes)
        assertEquals(2, decoded.manifest.formatVersion)
        val definition = decoded.data.measurementDefinitions.single()
        assertEquals("ENTERED", definition.kind)
        assertEquals(null, definition.formula)
        assertEquals(null, definition.sourceAId)
        assertEquals(null, definition.sourceBId)
    }

    @Test
    fun formatThreeRoundTripsDerivedDefinition() {
        val data = BackupData(
            assets = listOf(assetDto("a1")),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            measurementDefinitions = listOf(
                definitionDto("da", "a1"),
                definitionDto("db", "a1"),
                derivedDefinitionDto("dd", "a1", "da", "db"),
            ),
        )
        val decoded = BackupCodec.decode(encoded(data))
        assertEquals(data, decoded.data)
        val derived = decoded.data.measurementDefinitions.first { it.id == "dd" }
        assertEquals("DERIVED", derived.kind)
        assertEquals("PERCENT_DROP", derived.formula)
        assertEquals("da", derived.sourceAId)
        assertEquals("db", derived.sourceBId)
    }

    @Test
    fun derivedDefinitionWithBadSourceIsCorrupt() {
        val data = BackupData(
            assets = listOf(assetDto("a1")),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            measurementDefinitions = listOf(
                definitionDto("da", "a1", valueType = "TEXT"), // not NUMBER: an invalid source
                definitionDto("db", "a1"),
                derivedDefinitionDto("dd", "a1", "da", "db"),
            ),
        )
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("dd") && e.message!!.contains("SourceNotNumber"), "unhelpful: ${e.message}")
    }

    @Test
    fun derivedRowWithoutSourcesIsCorrupt() {
        val bad = definitionDto("dd", "a1", kind = "DERIVED", formula = "PERCENT_DROP")
        val data = BackupData(
            assets = listOf(assetDto("a1")),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            measurementDefinitions = listOf(bad),
        )
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("dd"), "unhelpful: ${e.message}")
    }

    @Test
    fun enteredRowWithFormulaIsCorrupt() {
        val bad = definitionDto("d1", "a1", formula = "PERCENT_DROP")
        val data = BackupData(
            assets = listOf(assetDto("a1")),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            measurementDefinitions = listOf(bad),
        )
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("d1"), "unhelpful: ${e.message}")
    }

    @Test
    fun measurementOnDerivedDefinitionIsCorrupt() {
        val measurement = measurementDto("m1", "dd", valueNum = 1.0)
        val event = assetEventDto("e1", "a1", measurements = listOf(measurement))
        val data = BackupData(
            assets = listOf(assetDto("a1")),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            measurementDefinitions = listOf(
                definitionDto("da", "a1"),
                definitionDto("db", "a1"),
                derivedDefinitionDto("dd", "a1", "da", "db"),
            ),
            assetEvents = listOf(event),
        )
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("m1"), "unhelpful: ${e.message}")
    }

    // --- asset fields and hierarchy (format 4) ----------------------------------------------------

    @Test
    fun formatThreeFileStillDecodes() {
        val f = fixture()
        val bytes = BackupCodec.encode(
            f,
            appVersion = "2.0",
            schemaVersion = 1,
            createdAt = 1_726_000_000_000L,
            backupSetId = "",
            formatVersion = 3,
        )
        val decoded = BackupCodec.decode(bytes)
        assertEquals(3, decoded.manifest.formatVersion)
        assertTrue(decoded.data.assets.all { it.manufacturer == "" && it.model == "" && it.serialNumber == "" })
        assertTrue(decoded.data.assets.all { it.purchaseOn == null && it.inServiceOn == null })
        assertTrue(decoded.data.assets.all { it.purchasePriceMinor == null && it.currency == null })
        assertTrue(decoded.data.assets.all { it.vendor == "" && it.location == "" })
        assertTrue(decoded.data.assets.all { it.warrantyExpiresOn == null && it.warrantyNotes == "" })
        assertTrue(decoded.data.assets.all { it.retiredOn == null && it.parentAssetId == null })
        assertTrue(decoded.data.assets.all { it.seasonStartMmdd == null && it.seasonEndMmdd == null })
    }

    @Test
    fun formatFourRoundTripsATree() {
        val root = assetDto("root").copy(
            manufacturer = "Acme",
            model = "9000",
            serialNumber = "SN-1",
            purchaseOn = "2024-01-01",
            inServiceOn = "2024-01-05",
            purchasePriceMinor = 12345L,
            currency = "USD",
            vendor = "Acme Store",
            location = "Garage",
            warrantyExpiresOn = "2026-01-01",
            warrantyNotes = "5 year parts",
            retiredOn = null,
            parentAssetId = null,
            seasonStartMmdd = "04-01",
            seasonEndMmdd = "10-31",
        )
        val child = assetDto("child").copy(parentAssetId = "root", retiredOn = "2025-06-01")
        val grandchild = assetDto("grandchild").copy(parentAssetId = "child")
        // encode() sorts assets by id ("child" < "grandchild" < "root"), so the fixture is
        // pre-sorted the same way — matching the pattern the other round-trip tests use — for
        // the whole-data equality check below to hold.
        val data = BackupData(
            assets = listOf(child, grandchild, root),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
        )
        val decoded = BackupCodec.decode(encoded(data))
        assertEquals(data, decoded.data)
        assertEquals(BackupCodec.FORMAT_VERSION, decoded.manifest.formatVersion)
    }

    @Test
    fun unknownParentIsCorrupt() {
        val orphan = assetDto("a1").copy(parentAssetId = "a404")
        val data = BackupData(assets = listOf(orphan), nfcTags = emptyList(), externalLinks = emptyList())
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("a1") && e.message!!.contains("a404"), "unhelpful: ${e.message}")
    }

    @Test
    fun cycleIsCorrupt() {
        val a1 = assetDto("a1").copy(parentAssetId = "a2")
        val a2 = assetDto("a2").copy(parentAssetId = "a1")
        val data = BackupData(assets = listOf(a1, a2), nfcTags = emptyList(), externalLinks = emptyList())
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("assets") && e.message!!.contains("cycle"), "unhelpful: ${e.message}")
    }

    @Test
    fun retiredStatusNameIsCorrupt() {
        val bad = assetDto("a1").copy(status = "RETIRED")
        val data = BackupData(assets = listOf(bad), nfcTags = emptyList(), externalLinks = emptyList())
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("a1"), "unhelpful: ${e.message}")
    }

    @Test
    fun badCurrencyIsCorrupt() {
        val badShape = assetDto("a1").copy(purchasePriceMinor = 100L, currency = "XX1")
        val e1 = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(BackupData(listOf(badShape), emptyList(), emptyList())))
        }
        assertTrue(e1.message!!.contains("a1"), "unhelpful: ${e1.message}")

        val unresolvable = assetDto("a2").copy(purchasePriceMinor = 100L, currency = "ZZZ")
        val e2 = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(BackupData(listOf(unresolvable), emptyList(), emptyList())))
        }
        assertTrue(e2.message!!.contains("a2"), "unhelpful: ${e2.message}")

        val noCurrency = assetDto("a3").copy(purchasePriceMinor = 100L, currency = null)
        val e3 = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(BackupData(listOf(noCurrency), emptyList(), emptyList())))
        }
        assertTrue(e3.message!!.contains("a3"), "unhelpful: ${e3.message}")
    }

    @Test
    fun badSeasonIsCorrupt() {
        val bad = assetDto("a1").copy(seasonStartMmdd = "04-01", seasonEndMmdd = null)
        val data = BackupData(assets = listOf(bad), nfcTags = emptyList(), externalLinks = emptyList())
        val e = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data)) }
        assertTrue(e.message!!.contains("a1"), "unhelpful: ${e.message}")
    }

    // --- attachments (format 5) -------------------------------------------------------------------

    private fun attachmentDto(
        id: String,
        assetId: String? = "a1",
        eventId: String? = null,
        locator: String = "assets/a1/$id.pdf",
        sha256: String = "a".repeat(64),
        sizeBytes: Long = 12L,
        mode: String = "MANAGED",
    ) = AttachmentDto(
        id = id, assetId = assetId, eventId = eventId, kind = "DOCUMENT", mode = mode,
        displayName = "Manual.pdf", mimeType = "application/pdf", sizeBytes = sizeBytes,
        sha256 = sha256, storageProvider = "SAF_TREE", storageLocator = locator,
        capturedOn = "2026-09-15", notes = "", createdAt = 1L, updatedAt = 2L,
    )

    @Test
    fun formatFiveRoundTripsAttachmentsOnBothOwners() {
        val data = BackupData(
            assets = listOf(assetDto("a1")),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            assetEvents = listOf(assetEventDto("e1", "a1")),
            attachments = listOf(
                attachmentDto("att-1"),
                attachmentDto("att-2", assetId = null, eventId = "e1", locator = "events/e1/att-2.jpg"),
            ),
        )
        val decoded = BackupCodec.decode(encoded(data))

        assertEquals(BackupCodec.FORMAT_VERSION, decoded.manifest.formatVersion)
        assertEquals("set-1", decoded.manifest.backupSetId)
        assertEquals(1, decoded.manifest.artifactFormatVersion)
        assertEquals(2, decoded.manifest.artifactCount)
        assertEquals(24L, decoded.manifest.artifactBytes)
        assertEquals(2, decoded.manifest.counts["attachments"])
        assertEquals(data.attachments, decoded.data.attachments)
        assertEquals(
            AttachmentOwner.OfEvent(EventId("e1")),
            decoded.data.attachments.first { it.id == "att-2" }.toDomain().owner,
        )
    }

    @Test
    fun aReferenceRowIsNotCountedAsAnArtifact() {
        val data = BackupData(
            assets = listOf(assetDto("a1")), nfcTags = emptyList(), externalLinks = emptyList(),
            attachments = listOf(
                attachmentDto("att-1"),
                attachmentDto("att-2", mode = "REFERENCE", locator = "assets/a1/att-2.pdf"),
            ),
        )
        val manifest = BackupCodec.decode(encoded(data)).manifest
        assertEquals(1, manifest.artifactCount)
        assertEquals(12L, manifest.artifactBytes)
        assertEquals(2, manifest.counts["attachments"])
    }

    @Test
    fun formatFourFileStillDecodesWithNoAttachments() {
        val data = BackupData(assets = listOf(assetDto("a1")), nfcTags = emptyList(), externalLinks = emptyList())
        val bytes = BackupCodec.encode(data, "2.3", 4, 1L, backupSetId = "", formatVersion = 4)
        val decoded = BackupCodec.decode(bytes)
        assertEquals(4, decoded.manifest.formatVersion)
        assertEquals("", decoded.manifest.backupSetId)
        assertTrue(decoded.data.attachments.isEmpty())
        assertEquals(0, decoded.manifest.artifactCount)
        assertEquals(0L, decoded.manifest.artifactBytes)
    }

    /** Drops [keys] from a JSON object entirely, the way a writer that never knew them would. */
    private fun withoutKeys(text: String, keys: Set<String>): String {
        val kept = Json.parseToJsonElement(text).jsonObject.filterKeys { it !in keys }
        return Json.encodeToString(JsonObject.serializer(), JsonObject(kept))
    }

    @Test
    fun aFormatFourArchiveMissingTheNewKeysEntirelyStillDecodes() {
        val data = BackupData(assets = listOf(assetDto("a1")), nfcTags = emptyList(), externalLinks = emptyList())
        val bytes = BackupCodec.encode(data, "2.3", 4, 1L, backupSetId = "", formatVersion = 4)

        // A real format-4 writer never emitted these keys at all, so resealing our own encoder's
        // empty values would prove nothing about reading an actually-old file. Strip them for real.
        val strippedData = withoutKeys(
            String(unzip(bytes).getValue(BackupCodec.DATA_ENTRY), Charsets.UTF_8),
            setOf("attachments"),
        )
        assertFalse("attachments" in strippedData, "the fixture still carries the new data key")
        val entries = unzip(resealed(bytes, strippedData.toByteArray(Charsets.UTF_8)))
        val strippedManifest = withoutKeys(
            String(entries.getValue(BackupCodec.MANIFEST_ENTRY), Charsets.UTF_8),
            setOf("backupSetId", "artifactFormatVersion", "artifactCount", "artifactBytes"),
        )
        assertFalse("backupSetId" in strippedManifest, "the fixture still carries the new manifest keys")
        entries[BackupCodec.MANIFEST_ENTRY] = strippedManifest.toByteArray(Charsets.UTF_8)

        val decoded = BackupCodec.decode(rezip(entries))
        assertEquals(4, decoded.manifest.formatVersion)
        assertEquals("", decoded.manifest.backupSetId)
        assertEquals(1, decoded.manifest.artifactFormatVersion)
        assertEquals(0, decoded.manifest.artifactCount)
        assertEquals(0L, decoded.manifest.artifactBytes)
        assertTrue(decoded.data.attachments.isEmpty())
        assertEquals(data, decoded.data)
    }

    @Test
    fun aFormatFiveFileWithNoSetIdIsCorrupt() {
        val data = BackupData(assets = listOf(assetDto("a1")), nfcTags = emptyList(), externalLinks = emptyList())
        val boom = assertFailsWith<BackupCorrupt> { BackupCodec.decode(encoded(data, setId = "  ")) }
        assertTrue("backupSetId" in boom.message!!, "unhelpful: ${boom.message}")
    }

    @Test
    fun anAttachmentWithNoOwnerOrTwoOwnersIsCorrupt() {
        val base = BackupData(assets = listOf(assetDto("a1")), nfcTags = emptyList(), externalLinks = emptyList())
        val noOwner = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(base.copy(attachments = listOf(attachmentDto("att-1", assetId = null)))))
        }
        assertTrue("exactly one owner" in noOwner.message!!, "unhelpful: ${noOwner.message}")
        val twoOwners = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(base.copy(attachments = listOf(attachmentDto("att-1", eventId = "e1")))))
        }
        assertTrue("exactly one owner" in twoOwners.message!!, "unhelpful: ${twoOwners.message}")
    }

    @Test
    fun anAttachmentPointingAtARowThatIsNotInTheFileIsCorrupt() {
        val base = BackupData(assets = listOf(assetDto("a1")), nfcTags = emptyList(), externalLinks = emptyList())
        val onAGhostAsset = attachmentDto("att-1", assetId = "a9", locator = "assets/a9/att-1.pdf")
        val ghostAsset = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(base.copy(attachments = listOf(onAGhostAsset))))
        }
        assertTrue("not in assets" in ghostAsset.message!!, "unhelpful: ${ghostAsset.message}")
        val onAGhostEvent = attachmentDto("att-2", assetId = null, eventId = "e9", locator = "events/e9/att-2.pdf")
        val ghostEvent = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(base.copy(attachments = listOf(onAGhostEvent))))
        }
        assertTrue("not in assetEvents" in ghostEvent.message!!, "unhelpful: ${ghostEvent.message}")
    }

    @Test
    fun aBadShaABadLocatorANegativeSizeAndADuplicateLocatorAreAllCorrupt() {
        val base = BackupData(assets = listOf(assetDto("a1")), nfcTags = emptyList(), externalLinks = emptyList())
        // uppercase hex is not the lowercase-hex shape the row promises
        val upperSha = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(base.copy(attachments = listOf(attachmentDto("att-1", sha256 = "A".repeat(64))))))
        }
        assertTrue("sha256" in upperSha.message!!, "unhelpful: ${upperSha.message}")
        val shortSha = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(base.copy(attachments = listOf(attachmentDto("att-1", sha256 = "abc")))))
        }
        assertTrue("sha256" in shortSha.message!!, "unhelpful: ${shortSha.message}")
        val negative = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(base.copy(attachments = listOf(attachmentDto("att-1", sizeBytes = -1L)))))
        }
        assertTrue("negative size" in negative.message!!, "unhelpful: ${negative.message}")
        // a locator that belongs to another row's id
        val strayLocator = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(base.copy(attachments = listOf(attachmentDto("att-1", locator = "assets/a1/att-9.pdf")))))
        }
        assertTrue("not its own" in strayLocator.message!!, "unhelpful: ${strayLocator.message}")
        // an event-shaped locator on an asset-owned row is not its own either
        val wrongOwnerDir = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(encoded(base.copy(attachments = listOf(attachmentDto("att-1", locator = "events/a1/att-1.pdf")))))
        }
        assertTrue("not its own" in wrongOwnerDir.message!!, "unhelpful: ${wrongOwnerDir.message}")
        // two rows claiming the same provider + locator. A locator always carries its own row's
        // id (AttachmentLocator.matchesShape), so the duplicate id is what a real file trips on
        // first; the locator set behind it is belt and braces for a later, looser provider.
        val twins = assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(
                encoded(
                    base.copy(
                        attachments = listOf(
                            attachmentDto("att-1", locator = "assets/a1/att-1.pdf"),
                            attachmentDto("att-1", locator = "assets/a1/att-1.pdf"),
                        ),
                    ),
                ),
            )
        }
        assertTrue("att-1" in twins.message!!, "unhelpful: ${twins.message}")
    }

    @Test
    fun encodeIsStillReproducibleAndAttachmentsAreSortedById() {
        val data = BackupData(
            assets = listOf(assetDto("a1")), nfcTags = emptyList(), externalLinks = emptyList(),
            attachments = listOf(attachmentDto("att-2", locator = "assets/a1/att-2.pdf"), attachmentDto("att-1")),
        )
        assertContentEquals(encoded(data), encoded(data))
        assertEquals(listOf("att-1", "att-2"), BackupCodec.decode(encoded(data)).data.attachments.map { it.id })
    }

    // --- random fixture generation (ids pre-sorted, so the identity is literal) -----------------

    private fun id(prefix: String, n: Int): String = "$prefix-%04d".format(n)

    private fun randomData(rng: Random): BackupData {
        val assetIds = (0 until rng.nextInt(0, 6)).map { id("a", it) }
        val linkIds = (0 until rng.nextInt(0, 6)).map { id("l", it) }
        val assets = assetIds.map { aid ->
            AssetDto(
                id = aid,
                name = rng.word(),
                description = if (rng.nextBoolean()) "" else rng.word(),
                category = rng.word(),
                notes = if (rng.nextBoolean()) "" else rng.word(),
                status = rng.pick(listOf("ACTIVE", "ARCHIVED")),
                createdAt = rng.nextLong(0, 2_000_000_000_000L),
                updatedAt = rng.nextLong(0, 2_000_000_000_000L),
            )
        }
        val links = linkIds.map { lid ->
            ExternalLinkDto(
                id = lid,
                assetId = if (assetIds.isEmpty() || rng.nextBoolean()) null else rng.pick(assetIds),
                kind = rng.pick(listOf("JOPLIN", "OBSIDIAN", "LOGSEQ", "WEB", "OTHER")),
                label = rng.word(),
                uri = "scheme://${rng.word()}",
                createdAt = rng.nextLong(0, 2_000_000_000_000L),
                lastOpenedAt = if (rng.nextBoolean()) null else rng.nextLong(0, 2_000_000_000_000L),
                updatedAt = rng.nextLong(0, 2_000_000_000_000L),
            )
        }
        val tags = (0 until rng.nextInt(0, 8)).map { i ->
            val target = rng.nextInt(0, 3)
            NfcTagDto(
                id = id("t", i),
                payloadFormat = "V1",
                payloadKey = rng.word(),
                assetId = if (target == 0 && assetIds.isNotEmpty()) rng.pick(assetIds) else null,
                linkId = if (target == 1 && linkIds.isNotEmpty()) rng.pick(linkIds) else null,
                status = rng.pick(listOf("ACTIVE", "UNBOUND", "LOST", "RETIRED")),
                label = if (rng.nextBoolean()) null else rng.word(),
                physicalUid = if (rng.nextBoolean()) null else rng.word(),
                writtenAt = if (rng.nextBoolean()) null else rng.nextLong(0, 2_000_000_000_000L),
                lastScannedAt = if (rng.nextBoolean()) null else rng.nextLong(0, 2_000_000_000_000L),
                createdAt = rng.nextLong(0, 2_000_000_000_000L),
                updatedAt = rng.nextLong(0, 2_000_000_000_000L),
            )
        }
        return BackupData(assets, tags, links)
    }

    private fun Random.word(): String =
        (0 until nextInt(1, 12)).map { "abcdefghijklmnopqrstuvwxyz ".random(this) }.joinToString("").trim()
            .ifEmpty { "x" }

    private fun <T> Random.pick(items: List<T>): T = items[nextInt(items.size)]
}
