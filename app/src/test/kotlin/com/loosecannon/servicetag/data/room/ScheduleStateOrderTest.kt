package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.data.room.entities.AssetEntity
import com.loosecannon.servicetag.data.room.entities.MaintenanceScheduleEntity
import com.loosecannon.servicetag.data.room.entities.ScheduleStateEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ScheduleStateDao.observeAll` orders by the **actionable** date, the sort key once the policy can
 * move it off the effective one (1.4 master plan §8.1; B01 review M5). The two rows below order one
 * way by `effective_due_on` and the other way by `actionable_due_on`.
 */
class ScheduleStateOrderTest {

    private fun schedule(id: String) = MaintenanceScheduleEntity(
        id = id, assetId = "a1", groupId = null, title = "Filter change", description = "",
        timeInterval = 3, timeUnit = "MONTH", timeBasis = "FIXED", anchorOn = "2026-03-01",
        leadDays = 7, meterDefinitionId = null, meterInterval = null, anchorMeter = null,
        meterLead = null, servicePolicy = "PRE_SERVICE", policyOffsetDays = -14,
        completionMode = "QUICK", profileId = null,
        remindersEnabled = true, status = "ACTIVE", postponedDueOn = null,
        createdAt = 1L, updatedAt = 2L, ruleChangedAt = 2L,
    )

    private fun state(scheduleId: String, effective: String, actionable: String, reason: String) = ScheduleStateEntity(
        scheduleId = scheduleId, lastCompletedOn = null, lastCompletionEventId = null,
        lastCompletedMeter = null, currentMeter = null, computedDueMeter = null,
        lastTerminationEffectiveOn = null, lastTerminationKind = "NONE",
        computedDueOn = effective, effectiveDueOn = effective,
        policyPhase = "ACTIVE", actionableDueOn = actionable, policyReason = reason, quiet = false,
        computedForOn = "2026-09-24", computedAt = 0L,
    )

    @Test
    fun observeAllOrdersByTheActionableDate() = runTest {
        val db = inMemoryDb()
        try {
            db.assetDao().upsert(
                AssetEntity(
                    id = "a1", name = "Asset a1", description = "", category = "", notes = "",
                    status = "ACTIVE", templateKey = null, createdAt = 1L, updatedAt = 1L,
                ),
            )
            db.maintenanceScheduleDao().upsert(schedule("s-early-effective"), emptyList())
            db.maintenanceScheduleDao().upsert(schedule("s-late-effective"), emptyList())
            // Effective 1 Nov but held until 1 Mar; effective 20 Dec but pulled to 1 Nov.
            db.scheduleStateDao().upsert(state("s-early-effective", "2026-11-01", "2027-03-01", "AFTER_BREAK"))
            db.scheduleStateDao().upsert(state("s-late-effective", "2026-12-20", "2026-11-01", "BEFORE_SEASON"))

            assertEquals(
                listOf("s-late-effective", "s-early-effective"),
                db.scheduleStateDao().observeAll().first().map { it.scheduleId },
            )
        } finally {
            db.close()
        }
    }
}
