package com.loosecannon.servicetag.core.backup

import java.io.File
import org.junit.jupiter.api.Test

/**
 * ONE-OFF: writes `core/src/test/resources/golden/format-7-legacy-seasons.zip` through the format-7
 * codec while it still exists (master plan dec. 49). The schedule DTOs are built directly, so raw
 * 1.3 values the domain can no longer hold — an `MM-DD` re-entry, an out-of-range offset, re-entry
 * leftovers on an IGNORE row — reach the archive as a 1.3 export would have written them.
 *
 * Deleted in the same brief once the archive is committed; the archive is never regenerated.
 */
class GoldenFormat7ArchiveWriter {

    private fun asset(id: String, name: String, start: String?, end: String?) = AssetDto(
        id = id, name = name, description = "", category = "Yard", notes = "", status = "ACTIVE",
        createdAt = 1_750_000_000_000L, updatedAt = 1_750_000_100_000L,
        seasonStartMmdd = start, seasonEndMmdd = end,
    )

    private fun schedule(
        id: String,
        assetId: String,
        title: String,
        behavior: String,
        reentry: String?,
        offset: Int?,
        timeRule: Boolean = true,
        updatedAt: Long,
    ) = MaintenanceScheduleDto(
        id = id, assetId = assetId, groupId = null, title = title, description = "",
        timeInterval = if (timeRule) 1 else null, timeUnit = if (timeRule) "YEAR" else null,
        timeBasis = "FIXED", anchorOn = if (timeRule) "2025-10-15" else null, leadDays = 14,
        meterDefinitionId = if (timeRule) null else "d-snowblower-hours",
        meterInterval = if (timeRule) null else 25.0, anchorMeter = if (timeRule) null else 0.0,
        meterLead = null,
        seasonBehavior = behavior, seasonReentry = reentry, seasonReentryOffsetDays = offset,
        completionMode = "QUICK", profileId = null, remindersEnabled = true, status = "ACTIVE",
        postponedDueOn = null, createdAt = 1_750_000_000_000L, updatedAt = updatedAt,
        providers = listOf(ScheduleProviderDto("LOCAL", enabled = true)),
    )

    @Test
    fun writeTheGoldenArchiveOnce() {
        val data = BackupData(
            assets = listOf(
                asset("a-snowblower", "Snowblower", "11-01", "03-31"),
                asset("a-generator", "Generator", null, null),
            ),
            nfcTags = emptyList(),
            externalLinks = emptyList(),
            measurementDefinitions = listOf(
                MeasurementDefinitionDto(
                    id = "d-snowblower-hours", assetId = "a-snowblower", key = "engine_hours",
                    label = "Engine hours", unit = "h", valueType = "NUMBER", decimals = 1,
                    rangeLow = null, rangeHigh = null, isMeter = true, sortOrder = 0, archivedAt = null,
                    createdAt = 1_750_000_000_000L, updatedAt = 1_750_000_000_000L,
                ),
            ),
            maintenanceSchedules = listOf(
                schedule("s-generator-ignore", "a-generator", "Oil change", "IGNORE", null, null,
                    updatedAt = 1_750_000_200_000L),
                schedule("s-generator-ignore-leftovers", "a-generator", "Load test", "IGNORE", "AT_START", 5,
                    updatedAt = 1_750_000_300_000L),
                schedule("s-generator-follow-mmdd-5", "a-generator", "Battery check", "FOLLOW_ASSET", "04-01", 5,
                    updatedAt = 1_750_000_400_000L),
                schedule("s-snowblower-follow-null", "a-snowblower", "Shear pins", "FOLLOW_ASSET", null, null,
                    updatedAt = 1_750_000_500_000L),
                schedule("s-snowblower-follow-at-start-5", "a-snowblower", "Auger belt", "FOLLOW_ASSET", "AT_START", 5,
                    updatedAt = 1_750_000_600_000L),
                schedule("s-snowblower-follow-clamped", "a-snowblower", "Skid shoes", "FOLLOW_ASSET",
                    "RESUME_CLAMPED", null, updatedAt = 1_750_000_700_000L),
                schedule("s-snowblower-follow-mmdd-400", "a-snowblower", "Fuel stabiliser", "FOLLOW_ASSET",
                    "04-01", 400, updatedAt = 1_750_000_800_000L),
                schedule("s-snowblower-follow-meter-only", "a-snowblower", "Engine oil", "FOLLOW_ASSET",
                    "AT_START", 5, timeRule = false, updatedAt = 1_750_000_900_000L),
            ),
        )
        val bytes = BackupCodec.encode(
            data,
            appVersion = "1.3.0",
            schemaVersion = 7,
            createdAt = 1_758_700_000_000L,
            backupSetId = "golden-format-7-legacy-seasons",
        )
        var root = File(".").absoluteFile
        while (!File(root, "settings.gradle.kts").isFile) root = root.parentFile
        val out = File(root, "core/src/test/resources/golden/format-7-legacy-seasons.zip")
        check(!out.exists()) { "the golden archive is written once and never regenerated" }
        out.parentFile.mkdirs()
        out.writeBytes(bytes)
    }
}
