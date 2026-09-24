package com.loosecannon.servicetag.core.model

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.toDomain
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Spec §4.1's legacy table, run over the committed golden cases (`docs/api/legacy-season-mapping.json`,
 * written from the spec and not from the object), its reverse projection, and B01's committed
 * golden format-7 archive.
 */
class LegacySeasonMappingTest {

    private data class Case(
        val seasonBehavior: SeasonBehavior?,
        val seasonReentry: String?,
        val seasonReentryOffsetDays: Int?,
        val hasTimeRule: Boolean,
        val servicePolicy: ServicePolicy,
        val policyOffsetDays: Int?,
    )

    private fun JsonElement.textOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.contentOrNull

    private fun JsonElement.intOrNullValue(): Int? = if (this is JsonNull) null else jsonPrimitive.intOrNull

    private fun cases(): List<Case> =
        Json.parseToJsonElement(repoFile("docs/api/legacy-season-mapping.json").readText()).jsonArray.map {
            val row = it.jsonObject
            Case(
                seasonBehavior = row.getValue("seasonBehavior").textOrNull()?.let(SeasonBehavior::valueOf),
                seasonReentry = row.getValue("seasonReentry").textOrNull(),
                seasonReentryOffsetDays = row.getValue("seasonReentryOffsetDays").intOrNullValue(),
                hasTimeRule = checkNotNull(row.getValue("hasTimeRule").jsonPrimitive.booleanOrNull),
                servicePolicy = ServicePolicy.valueOf(checkNotNull(row.getValue("servicePolicy").textOrNull())),
                policyOffsetDays = row.getValue("policyOffsetDays").intOrNullValue(),
            )
        }

    @Test
    fun everyGoldenCaseMaps() {
        val cases = cases()
        // every row of the table, the offset edges both ways and an MM-DD re-entry are there
        assertTrue(cases.size >= 20, "the golden file lost its cases: ${cases.size}")
        assertTrue(cases.any { it.seasonReentry == "04-01" }, "an MM-DD re-entry case")
        for (edge in listOf(-1, 0, 365, 366)) {
            for (timeRule in listOf(true, false)) {
                assertTrue(
                    cases.any { it.seasonReentryOffsetDays == edge && it.hasTimeRule == timeRule },
                    "the offset edge $edge with hasTimeRule=$timeRule",
                )
            }
        }

        for (case in cases) {
            assertEquals(
                LegacySeasonMapping.Policy(case.servicePolicy, case.policyOffsetDays),
                LegacySeasonMapping.toPolicy(
                    case.seasonBehavior, case.seasonReentry, case.seasonReentryOffsetDays, case.hasTimeRule,
                ),
                "the case $case",
            )
        }
    }

    @Test
    fun theReverseRoundTripsEveryRepresentablePolicy() {
        // master §4's reverse table, exactly
        assertEquals(
            LegacySeasonMapping.Legacy(SeasonBehavior.IGNORE, null, null),
            LegacySeasonMapping.toLegacy(ServicePolicy.CONTINUOUS, null),
        )
        assertEquals(
            LegacySeasonMapping.Legacy(SeasonBehavior.FOLLOW_ASSET, "AT_START", 12),
            LegacySeasonMapping.toLegacy(ServicePolicy.IN_SERVICE_AT_START, 12),
        )
        assertEquals(
            LegacySeasonMapping.Legacy(SeasonBehavior.FOLLOW_ASSET, "RESUME_CLAMPED", null),
            LegacySeasonMapping.toLegacy(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, null),
        )
        assertEquals(
            LegacySeasonMapping.Legacy(null, null, null),
            LegacySeasonMapping.toLegacy(ServicePolicy.PRE_SERVICE, -30),
        )

        // and every policy a legacy triple can describe comes back through the forward table
        val representable = buildList {
            add(Triple(ServicePolicy.CONTINUOUS, null, true))
            add(Triple(ServicePolicy.CONTINUOUS, null, false))
            add(Triple(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, null, true))
            add(Triple(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, null, false))
            for (offset in 0..365) add(Triple(ServicePolicy.IN_SERVICE_AT_START, offset, true))
            // a meter-only AT_START schedule's offset is always 0
            add(Triple(ServicePolicy.IN_SERVICE_AT_START, 0, false))
        }
        for ((policy, offset, timeRule) in representable) {
            val legacy = LegacySeasonMapping.toLegacy(policy, offset)
            assertEquals(
                LegacySeasonMapping.Policy(policy, offset),
                LegacySeasonMapping.toPolicy(
                    legacy.seasonBehavior, legacy.seasonReentry, legacy.seasonReentryOffsetDays, timeRule,
                ),
                "$policy / $offset / hasTimeRule=$timeRule",
            )
        }
    }

    @Test
    fun theGoldenFormat7ArchiveDecodesAsItsExpectedFile() {
        val archive = resourceBytes("golden/format-7-legacy-seasons.zip")
        val expected = Json.parseToJsonElement(
            resourceBytes("golden/format-7-legacy-seasons.expected.json").decodeToString(),
        ).jsonObject

        val backup = BackupCodec.decode(archive)
        assertEquals(7, backup.manifest.formatVersion)

        val schedules = backup.data.maintenanceSchedules.map { it.toDomain() }.associateBy { it.id.value }
        val expectedSchedules = expected.getValue("schedules").jsonObject
        assertEquals(expectedSchedules.keys, schedules.keys)
        for ((id, row) in expectedSchedules) {
            val want = row as JsonObject
            val got = schedules.getValue(id)
            val policy = ServicePolicy.valueOf(checkNotNull(want.getValue("servicePolicy").textOrNull()))
            assertEquals(policy, got.servicePolicy, id)
            assertEquals(want.getValue("policyOffsetDays").intOrNullValue(), got.policyOffsetDays, id)
            assertEquals(got.updatedAt, got.ruleChangedAt, "$id seeds ruleChangedAt from updatedAt")
        }

        val assets = backup.data.assets.map { it.toDomain() }.associateBy { it.id.value }
        val expectedAssets = expected.getValue("assets").jsonObject
        assertEquals(expectedAssets.keys, assets.keys)
        for ((id, row) in expectedAssets) {
            val want = SeasonMode.valueOf(checkNotNull((row as JsonObject).getValue("seasonMode").textOrNull()))
            assertEquals(want, assets.getValue(id).seasonMode, id)
        }
    }

    private fun resourceBytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "no test resource $name" }.use { it.readBytes() }

    private companion object {
        /** The repository root, found by walking up to the settings script (see `ScheduleStructuralTest`). */
        fun repoFile(relative: String): File {
            var dir = File(".").absoluteFile
            while (!File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile ?: error("cannot find the repository root from ${File(".").absolutePath}")
            }
            return File(dir, relative).also { check(it.isFile) { "no such file: $relative" } }
        }
    }
}
