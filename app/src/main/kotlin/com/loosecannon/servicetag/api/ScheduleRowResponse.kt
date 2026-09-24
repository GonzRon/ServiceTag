package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.MaintenanceScheduleDto
import com.loosecannon.servicetag.core.backup.ScheduleProviderDto
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.LegacySeasonMapping
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import kotlinx.serialization.Serializable

/**
 * A schedule on the `/v1` wire: **the format-8 row plus the derived compatibility triple**, as one
 * flat object (master plan §11.3).
 *
 * It is the single documented exception to "a response row is the archive row". Format 8 forbids
 * 1.3's three season fields (spec §8.3), and `/v1` must still report them (spec §9.1), so a 1.3
 * client keeps reading what it always read. The triple is **derived**, never stored: it is
 * [LegacySeasonMapping.toLegacy] of the row's own policy and offset, and all three are null for
 * `PRE_SERVICE`, which has no 1.3 spelling. It exists from the change that took the triple out of
 * the archive row, so no `/v1` response ever carries a schedule without it.
 *
 * Every field before the triple is [MaintenanceScheduleDto]'s, in its order; [rowResponse] copies
 * them from the DTO rather than restating the domain mapping.
 */
@Serializable
internal data class ScheduleRowResponse(
    val id: String,
    val assetId: String?,
    val groupId: String?,
    val title: String,
    val description: String,
    val timeInterval: Int?,
    val timeUnit: String?,
    val timeBasis: String,
    val anchorOn: String?,
    val leadDays: Int,
    val meterDefinitionId: String?,
    val meterInterval: Double?,
    val anchorMeter: Double?,
    val meterLead: Double?,
    val servicePolicy: String,
    val policyOffsetDays: Int?,
    val completionMode: String,
    val profileId: String?,
    val remindersEnabled: Boolean,
    val status: String,
    val postponedDueOn: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val ruleChangedAt: Long,
    val providers: List<ScheduleProviderDto>,
    /** Derived: `IGNORE` or `FOLLOW_ASSET`, or null for `PRE_SERVICE`. */
    val seasonBehavior: String?,
    /** Derived: `AT_START` or `RESUME_CLAMPED` for an in-service policy, else null. */
    val seasonReentry: String?,
    /** Derived: the AT_START offset, else null. */
    val seasonReentryOffsetDays: Int?,
)

/** This schedule as `/v1` reports it: its format-8 row, and the triple §4's reverse table derives. */
internal fun MaintenanceSchedule.rowResponse(): ScheduleRowResponse {
    val row = toDto()
    val legacy = LegacySeasonMapping.toLegacy(servicePolicy, policyOffsetDays)
    return ScheduleRowResponse(
        id = row.id,
        assetId = row.assetId,
        groupId = row.groupId,
        title = row.title,
        description = row.description,
        timeInterval = row.timeInterval,
        timeUnit = row.timeUnit,
        timeBasis = row.timeBasis,
        anchorOn = row.anchorOn,
        leadDays = row.leadDays,
        meterDefinitionId = row.meterDefinitionId,
        meterInterval = row.meterInterval,
        anchorMeter = row.anchorMeter,
        meterLead = row.meterLead,
        servicePolicy = row.servicePolicy,
        policyOffsetDays = row.policyOffsetDays,
        completionMode = row.completionMode,
        profileId = row.profileId,
        remindersEnabled = row.remindersEnabled,
        status = row.status,
        postponedDueOn = row.postponedDueOn,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        ruleChangedAt = row.ruleChangedAt,
        providers = row.providers,
        seasonBehavior = legacy.seasonBehavior?.name,
        seasonReentry = legacy.seasonReentry,
        seasonReentryOffsetDays = legacy.seasonReentryOffsetDays,
    )
}
