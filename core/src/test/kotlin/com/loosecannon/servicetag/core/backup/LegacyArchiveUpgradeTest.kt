package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.testing.LegacyTriple
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.asFormat7
import com.loosecannon.servicetag.core.testing.dataTreeOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.sealed
import com.loosecannon.servicetag.core.testing.seasonalAssetOf
import com.loosecannon.servicetag.core.testing.with
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Reading formats 1–7 through the one tree upgrade (spec §8.3, inv. 124; master plan §5): each
 * schedule's 1.3 triple through spec §4.1's table with `ruleChangedAt = updatedAt`, each asset
 * CALENDAR exactly when both `MM-DD` bounds are set, and the three new lists empty.
 *
 * Every archive here is a **genuine format-7 shape**: a format-8 tree is put back the way a 1.3
 * writer spelled it (`asFormat7`) and sealed under format 7, because the codec itself can no longer
 * write a legacy field — which is the point.
 */
class LegacyArchiveUpgradeTest {

    private val meter = MeasurementDefinition(
        id = DefinitionId("d1"), assetId = AssetId("a1"), key = "engine_hours", label = "Engine hours",
        unit = "h", valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null,
        isMeter = true, sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 2L,
    )

    /** One schedule on `a1`, with a time rule or — for `hasTimeRule = false` — meter-only. */
    private fun scheduleWith(hasTimeRule: Boolean): MaintenanceSchedule = if (hasTimeRule) {
        scheduleOf(
            id = "s1", timeInterval = 1, timeUnit = RecurrenceUnit.MONTH, anchorOn = "2026-01-01",
            createdOn = "2026-01-01", updatedOn = "2026-02-01", ruleChangedOn = "2026-01-05",
        )
    } else {
        scheduleOf(
            id = "s1", meterDefinitionId = "d1", meterInterval = 100.0, anchorMeter = 0.0,
            createdOn = "2026-01-01", updatedOn = "2026-02-01", ruleChangedOn = "2026-01-05",
        )
    }

    /** A format-7 archive holding one plain asset, the meter and [schedule] under [triple]. */
    private fun format7Archive(schedule: MaintenanceSchedule, triple: LegacyTriple): ByteArray {
        val data = BackupData(
            assets = listOf(plainAssetOf("a1").toDto()),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            measurementDefinitions = listOf(meter.toDto()),
            maintenanceSchedules = listOf(schedule.toDto()),
        )
        return sealed(dataTreeOf(archiveOf(data)).asFormat7(mapOf(schedule.id.value to triple)), formatVersion = 7)
    }

    private fun decodedSchedule(bytes: ByteArray): MaintenanceSchedule =
        BackupCodec.decode(bytes).data.maintenanceSchedules.single().toDomain()

    // --- every golden case -----------------------------------------------------------------------

    private data class Case(val triple: LegacyTriple, val hasTimeRule: Boolean, val policy: ServicePolicy, val offset: Int?)

    private fun JsonElement.textOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.contentOrNull
    private fun JsonElement.intOrNullValue(): Int? = if (this is JsonNull) null else jsonPrimitive.intOrNull

    private fun goldenCases(): List<Case> =
        Json.parseToJsonElement(repoFile("docs/api/legacy-season-mapping.json").readText()).jsonArray.map {
            val row = it.jsonObject
            Case(
                triple = LegacyTriple(
                    row.getValue("seasonBehavior").textOrNull(),
                    row.getValue("seasonReentry").textOrNull(),
                    row.getValue("seasonReentryOffsetDays").intOrNullValue(),
                ),
                hasTimeRule = checkNotNull(row.getValue("hasTimeRule").jsonPrimitive.booleanOrNull),
                policy = ServicePolicy.valueOf(checkNotNull(row.getValue("servicePolicy").textOrNull())),
                offset = row.getValue("policyOffsetDays").intOrNullValue(),
            )
        }

    /**
     * Hazard: an old archive stops working. Each case of the committed golden file — written from
     * spec §4.1 and not from the code — travels as a format-7 schedule and decodes to its policy and
     * offset, with the floor seeded from the row's own `updatedAt`. An absent `seasonBehavior` is
     * written by leaving the key out, as the table's "absent" row means.
     */
    @Test
    fun everyGoldenCaseDecodesToItsPolicy() {
        val cases = goldenCases()
        assertTrue(cases.size >= 20, "the golden file lost its cases: ${cases.size}")
        assertTrue(cases.any { it.triple.seasonBehavior == null }, "an absent-behaviour case")
        for (case in cases) {
            val schedule = decodedSchedule(format7Archive(scheduleWith(case.hasTimeRule), case.triple))
            assertEquals(case.policy, schedule.servicePolicy, "$case")
            assertEquals(case.offset, schedule.policyOffsetDays, "$case")
            assertEquals(dayMillis("2026-02-01"), schedule.ruleChangedAt, "$case")
        }
    }

    /** An unrecognised `seasonBehavior` name was corrupt in 1.3 and stays corrupt: the table never guesses. */
    @Test
    fun anUnknownSeasonBehaviorIsStillCorrupt() {
        assertFailsWith<BackupCorrupt> {
            BackupCodec.decode(format7Archive(scheduleWith(true), LegacyTriple("SOMETIMES", null, null)))
        }
        // and a quoted offset, which 1.3's Int field refused, is refused the same way
        val quoted = dataTreeOf(format7Archive(scheduleWith(true), LegacyTriple("FOLLOW_ASSET", "AT_START", 5)))
        val tree = JsonObject(
            quoted + ("maintenanceSchedules" to JsonArray(
                quoted.getValue("maintenanceSchedules").jsonArray.map {
                    it.jsonObject.with("seasonReentryOffsetDays", JsonPrimitive("5"))
                },
            )),
        )
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(sealed(tree, formatVersion = 7)) }
    }

    // --- assets ----------------------------------------------------------------------------------

    /**
     * An old asset is CALENDAR exactly when **both** `MM-DD` bounds are set — through the codec for
     * the two shapes a 1.3 file could hold, and on the tree for the half-set shapes a 1.3 codec
     * refused anyway — with no break, `WORST` and no primary subject in every case.
     */
    @Test
    fun aFormat7AssetIsCalendarExactlyWhenBothMonthDaysAreSet() {
        val data = BackupData(
            assets = listOf(
                seasonalAssetOf("a-both", blackoutStartMmdd = null, blackoutEndMmdd = null).toDto(),
                plainAssetOf("a-none").toDto(),
            ),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
        )
        val decoded = BackupCodec.decode(sealed(dataTreeOf(archiveOf(data)).asFormat7(emptyMap()), formatVersion = 7))
            .data.assets.associate { it.id to it.toDomain() }
        assertEquals(SeasonMode.CALENDAR, decoded.getValue("a-both").seasonMode)
        assertEquals(SeasonMode.YEAR_ROUND, decoded.getValue("a-none").seasonMode)
        for (asset in decoded.values) {
            assertNull(asset.blackoutStartMmdd, asset.id.value)
            assertNull(asset.blackoutEndMmdd, asset.id.value)
            assertEquals(HealthAggregation.WORST, asset.healthAggregation, asset.id.value)
            assertNull(asset.healthPrimarySubjectId, asset.id.value)
        }

        fun modeOf(start: String?, end: String?): String {
            val asset = buildMap<String, JsonElement> {
                put("id", JsonPrimitive("a"))
                put("seasonStartMmdd", start?.let(::JsonPrimitive) ?: JsonNull)
                if (end != null) put("seasonEndMmdd", JsonPrimitive(end))
            }
            val tree = JsonObject(mapOf("assets" to JsonArray(listOf(JsonObject(asset)))))
            val upgraded = LegacyArchive.upgrade(tree, formatVersion = 7).getValue("assets").jsonArray.single().jsonObject
            return upgraded.getValue("seasonMode").jsonPrimitive.content
        }
        assertEquals("CALENDAR", modeOf("11-01", "03-31"))
        assertEquals("YEAR_ROUND", modeOf("11-01", null))
        assertEquals("YEAR_ROUND", modeOf(null, "03-31"))
        assertEquals("YEAR_ROUND", modeOf(null, null))
    }

    // --- schedules and lists ---------------------------------------------------------------------

    /**
     * Hazard: the floor seeded from the wrong stamp. A format-7 schedule created on 1 January and
     * last saved on 1 February decodes with `ruleChangedAt` = 1 February, its own `updatedAt`, as
     * the migration seeds it — and the three new lists are empty, even when a stray key claims
     * otherwise, because a format ≤7 file can hold none of them.
     */
    @Test
    fun ruleChangedAtIsUpdatedAtAndTheNewListsAreEmpty() {
        val bytes = format7Archive(scheduleWith(true), LegacyTriple("IGNORE", null, null))
        val decoded = BackupCodec.decode(bytes)
        val schedule = decoded.data.maintenanceSchedules.single().toDomain()
        assertEquals(dayMillis("2026-01-01"), schedule.createdAt)
        assertEquals(dayMillis("2026-02-01"), schedule.updatedAt)
        assertEquals(schedule.updatedAt, schedule.ruleChangedAt)
        assertEquals(emptyList(), decoded.data.seasonActivations)
        assertEquals(emptyList(), decoded.data.assetConditions)
        assertEquals(emptyList(), decoded.data.healthSubjects)

        val stray = dataTreeOf(bytes)
            .with("seasonActivations", JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("act-1"))))))
        val upgraded = LegacyArchive.upgrade(stray, formatVersion = 7)
        assertTrue("seasonActivations" !in upgraded, "a format-7 tree carries no activation list")
        assertEquals(emptyList(), BackupCodec.decode(sealed(stray, formatVersion = 7)).data.seasonActivations)
    }

    private companion object {
        /** The repository root, found by walking up to the settings script. */
        fun repoFile(relative: String): File {
            var dir = File(".").absoluteFile
            while (!File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile ?: error("cannot find the repository root from ${File(".").absolutePath}")
            }
            return File(dir, relative).also { check(it.isFile) { "no such file: $relative" } }
        }
    }
}
