package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.testing.GOLDEN_FORMAT_7
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.archiveOf
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.plainAssetOf
import com.loosecannon.servicetag.core.testing.resourceBytes
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.subjectOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The format-8 content check (the controller's ruling on B03's concern 3): a restore refuses a row a
 * command would refuse, naming the command's own problem, and lands everything a command — or a
 * merge — could have written. One refused archive per rule; the edges and the merge-only states
 * decode; the golden format-7 archive still decodes through it.
 */
class BackupContentCheckTest {

    private val generator = plainAssetOf("a1", "Generator")

    private val hours = MeasurementDefinition(
        id = DefinitionId("d-hours"), assetId = AssetId("a1"), key = "engine_hours", label = "Engine hours", unit = "h",
        valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null, isMeter = true, sortOrder = 0,
        archivedAt = null, createdAt = 100L, updatedAt = 100L,
    )

    private fun timed(
        id: String,
        policy: ServicePolicy,
        offset: Int?,
        assetId: String? = "a1",
        groupId: String? = null,
        status: ScheduleStatus = ScheduleStatus.ACTIVE,
    ): MaintenanceSchedule = scheduleOf(
        id = id, assetId = assetId, groupId = groupId, timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
        anchorOn = "2026-01-01", servicePolicy = policy, policyOffsetDays = offset, status = status,
    )

    private fun data(
        assets: List<Asset> = listOf(generator),
        definitions: List<MeasurementDefinition> = emptyList(),
        groups: List<MaintenanceGroup> = emptyList(),
        schedules: List<MaintenanceSchedule> = emptyList(),
        subjects: List<HealthSubject> = emptyList(),
        conditions: List<AssetCondition> = emptyList(),
        activations: List<SeasonActivation> = emptyList(),
    ) = BackupData(
        assets = assets.map { it.toDto() },
        nfcTags = emptyList(),
        externalLinks = emptyList(),
        measurementDefinitions = definitions.map { it.toDto() },
        maintenanceGroups = groups.map { it.toDto() },
        maintenanceSchedules = schedules.map { it.toDto() },
        seasonActivations = activations.map { it.toDto() },
        assetConditions = conditions.map { it.toDto() },
        healthSubjects = subjects.map { it.toDto() },
    )

    /** The refusal's message, which names the table, the row and the command's problem. */
    private fun refused(data: BackupData): String =
        assertFailsWith<BackupCorrupt> { BackupCodec.decode(archiveOf(data)) }.message!!

    private fun assertRefused(data: BackupData, row: String, problem: String) {
        val message = refused(data)
        assertTrue(message.startsWith(row) && message.contains(problem), message)
    }

    /** `SaveSchedule`'s offset ranges (spec §4.2), through B04's `policyProblems`. */
    @Test
    fun aPolicyOffsetOutsideItsRangeIsRefused() {
        assertRefused(
            data(schedules = listOf(timed("s1", ServicePolicy.IN_SERVICE_AT_START, 366))),
            "maintenanceSchedules: schedule s1", "PolicyOffsetInvalid",
        )
        assertRefused(
            data(schedules = listOf(timed("s1", ServicePolicy.PRE_SERVICE, null))),
            "maintenanceSchedules: schedule s1", "PolicyOffsetInvalid",
        )
    }

    /** PRE_SERVICE on a meter-only schedule has no date to move (O-7). */
    @Test
    fun preServiceOnAMeterOnlyScheduleIsRefused() {
        val meterOnly = scheduleOf(
            id = "s1", assetId = "a1", meterDefinitionId = "d-hours", meterInterval = 250.0,
            servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14,
        )
        assertRefused(
            data(definitions = listOf(hours), schedules = listOf(meterOnly)),
            "maintenanceSchedules: schedule s1", "SeasonPolicyNeedsATimeRule",
        )
    }

    /** A group target is CONTINUOUS only (inv. 106). */
    @Test
    fun aNonContinuousPolicyOnAGroupTargetIsRefused() {
        assertRefused(
            data(
                groups = listOf(groupOf("g1")),
                schedules = listOf(timed("s1", ServicePolicy.IN_SERVICE_RESUME_CLAMPED, null, assetId = null, groupId = "g1")),
            ),
            "maintenanceSchedules: schedule s1", "SeasonFollowsAssetOnGroupTarget",
        )
    }

    /** `SetMaintenanceBreak`'s year-long break (master plan §7.2): its shape is the graph check's, its reach this one's. */
    @Test
    fun aBreakCoveringTheYearIsRefused() {
        val yearLong = generator.copy(blackoutStartMmdd = "03-01", blackoutEndMmdd = "02-28")
        assertRefused(data(assets = listOf(yearLong)), "assets: asset a1", "BlackoutCoversTheYear")
    }

    /** `SaveHealthSubject`'s name (1–60 after trimming), thresholds and weight. */
    @Test
    fun aSubjectsNameThresholdsAndWeightAreChecked() {
        for (name in listOf("  ", "n".repeat(61))) {
            assertRefused(
                data(subjects = listOf(subjectOf("h1", name = name))), "healthSubjects: subject h1", "NameRequired(limit=1..60)",
            )
        }
        val sameTwice = subjectOf("h1").copy(nominalUntilDays = 5, warningFromDays = 5, criticalFromDays = 6)
        assertRefused(data(subjects = listOf(sameTwice)), "healthSubjects: subject h1", "ThresholdsInvalid")
        val tooLong = subjectOf("h1").copy(criticalFromDays = 36_501)
        assertRefused(data(subjects = listOf(tooLong)), "healthSubjects: subject h1", "ThresholdsInvalid")
        assertRefused(data(subjects = listOf(subjectOf("h1", weight = 0))), "healthSubjects: subject h1", "WeightOutOfRange")
        assertRefused(data(subjects = listOf(subjectOf("h1", weight = 11))), "healthSubjects: subject h1", "WeightOutOfRange")
    }

    /** `RecordCondition`'s shapes: an ISO date, an `HH:MM` time, a real zone, a reason of at most 500. */
    @Test
    fun aConditionsDateTimeZoneAndReasonAreChecked() {
        val cases = listOf(
            conditionOf("c1", occurredOn = "2026-02-30") to "BadDate(field=occurredOn)",
            conditionOf("c1", occurredTime = "7:45") to "BadTime(field=occurredTime)",
            conditionOf("c1", tzId = "not a zone") to "BadTimeZone(field=tzId)",
            conditionOf("c1", reason = "r".repeat(501)) to "ReasonTooLong(limit=500)",
        )
        for ((row, problem) in cases) assertRefused(data(conditions = listOf(row)), "assetConditions: condition c1", problem)
    }

    /**
     * The controller's ruling on B06-F7: a condition's zone is judged within the archive's own contents,
     * never against the importing device's zone data. A well-formed region id that no zone data here
     * knows — one only the archive holds — restores, row for row; only a malformed id is refused.
     */
    @Test
    fun aConditionsZoneIsJudgedByTheArchiveNotThisDevice() {
        val elsewhere = conditionOf("c1", tzId = "Mars/Olympus_Mons")
        val archive = data(conditions = listOf(elsewhere))

        val restored = BackupCodec.decode(archiveOf(archive)).data.assetConditions.map { it.toDomain() }
        assertEquals(listOf(elsewhere), restored)
        assertRefused(
            data(conditions = listOf(conditionOf("c1", tzId = "UTC+99"))), "assetConditions: condition c1",
            "BadTimeZone(field=tzId)",
        )
    }

    /** An activation's date is an ISO date, as `RecordSeasonActivation` requires. */
    @Test
    fun anActivationsDateIsChecked() {
        assertRefused(
            data(activations = listOf(activationOf("act-1", occurredOn = "2026-3-1"))),
            "seasonActivations: activation act-1", "BadDate(field=occurredOn)",
        )
    }

    /**
     * Shape only: what a command allows — every bound at its edge — and what only a merge brings (a
     * subject on an archived schedule, a missing TRACK_ONE primary, PRE_SERVICE on an asset with no
     * boundary) still decode, unchanged; and the golden format-7 archive passes through the check.
     */
    @Test
    fun whatACommandOrAMergeAllowsStillDecodes() {
        val edges = data(
            assets = listOf(
                generator.copy(
                    healthAggregation = HealthAggregation.TRACK_ONE, healthPrimarySubjectId = HealthSubjectId("h-gone"),
                ),
            ),
            definitions = listOf(hours),
            schedules = listOf(
                timed("s1", ServicePolicy.IN_SERVICE_AT_START, 365),
                timed("s2", ServicePolicy.PRE_SERVICE, -365),
                timed("s3", ServicePolicy.CONTINUOUS, null, status = ScheduleStatus.ARCHIVED),
                scheduleOf(id = "s4", assetId = "a1", meterDefinitionId = "d-hours", meterInterval = 250.0,
                    servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0),
            ),
            subjects = listOf(
                subjectOf("h1", scheduleId = "s3", name = "n".repeat(60), weight = 10)
                    .copy(nominalUntilDays = 0, warningFromDays = 1, criticalFromDays = 36_500),
                subjectOf("h2", name = " Battery age ", weight = 1),
            ),
            conditions = listOf(
                conditionOf("c1", occurredTime = null, reason = "r".repeat(500)),
                conditionOf("c2", reason = ""),
            ),
            activations = listOf(activationOf("act-1", occurredOn = "2026-02-28")),
        )
        assertEquals(edges, BackupCodec.decode(archiveOf(edges)).data)

        val golden = BackupCodec.decode(resourceBytes(GOLDEN_FORMAT_7))
        assertTrue(golden.data.maintenanceSchedules.isNotEmpty())
    }
}
