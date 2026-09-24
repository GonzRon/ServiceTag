package com.loosecannon.servicetag.backup

import androidx.test.platform.app.InstrumentationRegistry
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.merge.MergeReason
import com.loosecannon.servicetag.core.merge.MergeVerdict
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.LegacySeasonMapping
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.clearInstall
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 1.4 (B03) — the format-7 import proof of master plan §17.8, in R2: an Android contract test with
 * **no navigation**. B01's committed golden format-7 archive (fictional: a CALENDAR and a YEAR_ROUND
 * asset; IGNORE and FOLLOW_ASSET schedules with null, `AT_START`, `RESUME_CLAMPED` and `MM-DD`
 * re-entries and out-of-range offsets) is restored in process through the production
 * `importBackupReplace` into the real Room schema 8, and exported again through the production
 * `exportBackupSet`.
 *
 * The archive is read from this APK's assets, which `app/build.gradle.kts` points at
 * `core/src/test/resources/golden` — the one committed copy, never a second one. Every expectation
 * about a row is read off the archive's **raw** `data.json` and spec §4.1's table, never off what
 * the decoder produced.
 */
class Format7RestoreContractTest {

    @Before fun freshInstall() = clearInstall()

    private fun golden(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(ARCHIVE).use { it.readBytes() }

    private fun expected(): JsonObject = Json.parseToJsonElement(
        InstrumentationRegistry.getInstrumentation().context.assets.open(EXPECTED).use { it.readBytes() }.decodeToString(),
    ).jsonObject

    private fun dataJson(archive: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == BackupCodec.DATA_ENTRY) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("no ${BackupCodec.DATA_ENTRY} in the archive")
    }

    private fun rawRows(archive: ByteArray, table: String): Map<String, JsonObject> =
        Json.parseToJsonElement(dataJson(archive)).jsonObject.getValue(table).jsonArray
            .map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }

    private fun JsonElement?.text(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.contentOrNull
    private fun JsonElement?.int(): Int? = if (this == null || this is JsonNull) null else jsonPrimitive.intOrNull

    /**
     * Restored, the golden archive keeps its counts; each asset is CALENDAR exactly when both of its
     * `MM-DD` bounds were set, with no break, WORST and no primary; each schedule is spec §4.1's
     * mapping of its own triple — and the committed expected file's — with the floor at its own
     * `updatedAt` and no `updatedAt` moved; and the three new tables are empty.
     */
    @Test fun restoringTheGoldenArchiveKeepsCountsAndMapsEveryRow() {
        val bytes = golden()
        val manifest = BackupCodec.decode(bytes).manifest
        assertEquals(7, manifest.formatVersion)
        val graph = app.graph

        val report = runBlocking { graph.importBackupReplace.run(bytes) }

        assertEquals(7, report.formatVersion)
        val assets = runBlocking { graph.assets.all() }.associateBy { it.id.value }
        val schedules = runBlocking { graph.schedules.all() }.associateBy { it.id.value }
        assertEquals(manifest.counts["assets"], assets.size)
        assertEquals(manifest.counts["assets"], report.assets)
        assertEquals(manifest.counts["measurementDefinitions"], runBlocking { graph.definitions.all() }.size)
        assertEquals(manifest.counts["maintenanceSchedules"], schedules.size)
        assertEquals(
            manifest.counts["scheduleProviders"],
            schedules.values.sumOf { it.providers.size },
        )

        val expectedAssets = expected().getValue("assets").jsonObject
        for ((id, raw) in rawRows(bytes, "assets")) {
            val asset = assets.getValue(id)
            val calendar = raw["seasonStartMmdd"].text() != null && raw["seasonEndMmdd"].text() != null
            assertEquals(id, if (calendar) SeasonMode.CALENDAR else SeasonMode.YEAR_ROUND, asset.seasonMode)
            assertEquals(id, expectedAssets.getValue(id).jsonObject["seasonMode"].text(), asset.seasonMode.name)
            assertNull(id, asset.blackoutStartMmdd)
            assertNull(id, asset.blackoutEndMmdd)
            assertEquals(id, HealthAggregation.WORST, asset.healthAggregation)
            assertNull(id, asset.healthPrimarySubjectId)
            assertEquals(id, raw.getValue("updatedAt").jsonPrimitive.long, asset.updatedAt)
        }

        val expectedSchedules = expected().getValue("schedules").jsonObject
        for ((id, raw) in rawRows(bytes, "maintenanceSchedules")) {
            val schedule = schedules.getValue(id)
            val policy = LegacySeasonMapping.toPolicy(
                behavior = raw["seasonBehavior"].text()?.let(SeasonBehavior::valueOf),
                reentry = raw["seasonReentry"].text(),
                offsetDays = raw["seasonReentryOffsetDays"].int(),
                hasTimeRule = raw["timeInterval"].int() != null,
            )
            assertEquals(id, policy.servicePolicy, schedule.servicePolicy)
            assertEquals(id, policy.policyOffsetDays, schedule.policyOffsetDays)
            val want = expectedSchedules.getValue(id).jsonObject
            assertEquals(id, ServicePolicy.valueOf(want["servicePolicy"].text()!!), schedule.servicePolicy)
            assertEquals(id, want["policyOffsetDays"].int(), schedule.policyOffsetDays)
            val updatedAt = raw.getValue("updatedAt").jsonPrimitive.long
            assertEquals(id, updatedAt, schedule.updatedAt)
            assertEquals(id, updatedAt, schedule.ruleChangedAt)
        }

        runBlocking {
            assertTrue(graph.seasonActivations.all().isEmpty())
            assertTrue(graph.conditions.all().isEmpty())
            assertTrue(graph.healthSubjects.all().isEmpty())
        }
    }

    /**
     * Exported again, the restored estate is **format 8** — no legacy season field anywhere in its
     * `data.json` — and it plans IDENTICAL, row for row, against the store it came from; and so does
     * the original format-7 archive (inv. 125 on the device's own schema).
     */
    @Test fun reExportIsFormat8AndPlansIdentical() {
        val bytes = golden()
        val graph = app.graph
        runBlocking { graph.importBackupReplace.run(bytes) }

        val reexported = runBlocking { graph.exportBackupSet.run() }.data
        val decoded = BackupCodec.decode(reexported)
        assertEquals(8, decoded.manifest.formatVersion)
        val json = dataJson(reexported)
        for (legacy in listOf("seasonBehavior", "seasonReentry", "seasonReentryOffsetDays")) {
            assertFalse(legacy, json.contains("\"$legacy\""))
        }
        assertEquals(0, decoded.manifest.counts["seasonActivations"])
        assertEquals(0, decoded.manifest.counts["assetConditions"])
        assertEquals(0, decoded.manifest.counts["healthSubjects"])

        for ((label, archive) in listOf("re-export" to reexported, "golden format 7" to bytes)) {
            val plan = runBlocking { graph.buildBackupMergePlan.run(archive) }
            assertEquals(label, 11, plan.decisions.size)
            assertEquals(
                label,
                emptyList<Any>(),
                plan.decisions.filter { it.verdict != MergeVerdict.IDENTICAL || it.reason != MergeReason.NONE },
            )
            assertTrue(label, plan.applicable)
        }
    }

    private companion object {
        const val ARCHIVE = "format-7-legacy-seasons.zip"
        const val EXPECTED = "format-7-legacy-seasons.expected.json"
    }
}
