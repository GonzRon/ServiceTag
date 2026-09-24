package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.testing.groupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * `SaveSchedule`'s service-policy rules (spec §4.1–§4.3, §4.6; master plan §7.2): a group target is
 * CONTINUOUS only, PRE_SERVICE needs a boundary on its asset, and the policy is not a rule field.
 * Today is 2026-06-10.
 */
class SchedulePolicyValidationTest {

    private fun command(
        policy: ServicePolicy,
        offset: Int?,
        assetId: String? = "a1",
        groupId: String? = null,
    ) = ScheduleCommand(
        targetAssetId = assetId?.let(::AssetId),
        targetGroupId = groupId?.let(::GroupId),
        title = "Service",
        timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR,
        anchorOn = "2026-09-01",
        servicePolicy = policy,
        policyOffsetDays = offset,
        providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
    )

    /** Inv. 106: a season and a break live on one asset; a group target is CONTINUOUS only. */
    @Test
    fun aGroupTargetIsContinuousOnly() = runTest {
        val h = SeasonCommandHarness()
        h.asset()
        h.groups.upsert(groupOf("g1", members = listOf(Triple("a1", "2026-01-01", null))))

        for ((policy, offset) in listOf(
            ServicePolicy.IN_SERVICE_AT_START to 0,
            ServicePolicy.IN_SERVICE_RESUME_CLAMPED to null,
            ServicePolicy.PRE_SERVICE to -14,
        )) {
            val refusal = assertFailsWith<ScheduleValidation> {
                h.saveSchedule.run(null, command(policy, offset, assetId = null, groupId = "g1"))
            }
            assertTrue(ScheduleProblem.SeasonFollowsAssetOnGroupTarget in refusal.problems, "$policy on a group")
        }
        assertEquals(emptyMap(), h.schedules.rows.toMap())

        val saved = h.saveSchedule.run(null, command(ServicePolicy.CONTINUOUS, null, assetId = null, groupId = "g1"))
        assertEquals(ServicePolicy.CONTINUOUS, saved.servicePolicy)
    }

    /** Inv. 99: PRE_SERVICE counts back from a season or a break; with neither it is a 409 on the asset. */
    @Test
    fun preServiceWithoutABoundaryIs409() = runTest {
        val h = SeasonCommandHarness()
        h.asset()
        h.asset(id = "m1", mode = SeasonMode.MANUAL)

        for (id in listOf("a1", "m1")) {
            val refusal = assertFailsWith<PreServiceNeedsDates> {
                h.saveSchedule.run(null, command(ServicePolicy.PRE_SERVICE, -14, assetId = id))
            }
            assertEquals(AssetId(id), refusal.assetId)
        }
        assertEquals(emptyMap(), h.schedules.rows.toMap(), "nothing written")

        // An edit of a merged boundary-less PRE_SERVICE row is refused the same way.
        val merged = h.schedule("s-merged")
        assertFailsWith<PreServiceNeedsDates> {
            h.saveSchedule.run(merged.id, command(ServicePolicy.PRE_SERVICE, -14).copy(title = "Renamed"))
        }
        assertEquals(merged, h.schedules.rows[merged.id.value])

        // With a boundary — a break, a calendar season, a manual asset's break — it is allowed.
        h.asset(id = "y1", breakStart = "12-01", breakEnd = "02-28")
        h.asset(id = "c1", mode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31")
        h.asset(id = "m2", mode = SeasonMode.MANUAL, breakStart = "12-01", breakEnd = "02-28")
        for (id in listOf("y1", "c1", "m2")) {
            assertEquals(
                ServicePolicy.PRE_SERVICE,
                h.saveSchedule.run(null, command(ServicePolicy.PRE_SERVICE, -14, assetId = id)).servicePolicy,
                id,
            )
        }
    }

    /** B01's `ruleChanged` is unchanged: a policy-only edit keeps the postponement and the pin's floor. */
    @Test
    fun policyIsNotARuleField() = runTest {
        val h = SeasonCommandHarness()
        h.asset(breakStart = "12-01", breakEnd = "02-28")
        val created = h.saveSchedule.run(null, command(ServicePolicy.CONTINUOUS, null))
        PostponeSchedule(h.schedules, h.uow, h.recompute).run(created.id, "2026-09-20")
        h.now += 86_400_000L

        for ((policy, offset) in listOf(
            ServicePolicy.IN_SERVICE_AT_START to 0,
            ServicePolicy.IN_SERVICE_AT_START to 30,
            ServicePolicy.PRE_SERVICE to -14,
            ServicePolicy.IN_SERVICE_RESUME_CLAMPED to null,
        )) {
            val saved = h.saveSchedule.run(created.id, command(policy, offset))
            assertEquals(policy to offset, saved.servicePolicy to saved.policyOffsetDays)
            assertEquals("2026-09-20", saved.postponedDueOn, "the postponement survives $policy/$offset")
            assertEquals(created.ruleChangedAt, saved.ruleChangedAt, "the floor survives $policy/$offset")
        }
    }
}
