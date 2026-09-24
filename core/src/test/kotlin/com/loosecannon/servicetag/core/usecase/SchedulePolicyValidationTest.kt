package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.testing.groupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * `SaveSchedule`'s service-policy rules (spec §4.1–§4.3, §4.6; master plan §7.2): the offset ranges, a
 * meter-only schedule is phase-only, a group target is CONTINUOUS only, PRE_SERVICE needs a boundary
 * on its asset, and the policy is not a rule field. Today is 2026-06-10.
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

    /** The policy's own problems, or nothing when the save goes through (and the row is then removed). */
    private suspend fun SeasonCommandHarness.problemsOf(cmd: ScheduleCommand): List<ScheduleProblem> =
        try {
            saveSchedule.run(null, cmd).also { schedules.rows.remove(it.id.value) }
            emptyList()
        } catch (refusal: ScheduleValidation) {
            refusal.problems
        }

    private val policyCodes = setOf(
        ScheduleProblem.SeasonFollowsAssetOnGroupTarget,
        ScheduleProblem.SeasonPolicyNeedsATimeRule,
        ScheduleProblem.PolicyOffsetInvalid,
    )

    /**
     * [cmd]'s problems are [expected], and the shared validator a restore asks gives the command's
     * policy problems in the command's own order (parity: one rule set, one order).
     */
    private suspend fun SeasonCommandHarness.assertPolicy(expected: List<ScheduleProblem>, cmd: ScheduleCommand, label: String) {
        val problems = problemsOf(cmd)
        assertEquals(expected, problems, label)
        assertEquals(
            problems.filter { it in policyCodes },
            policyProblems(cmd.servicePolicy, cmd.policyOffsetDays, cmd.timeInterval != null, cmd.targetGroupId != null),
            "parity: $label",
        )
    }

    /** Spec §4.2: AT_START 0–365, PRE_SERVICE −365…−1 and present, every other policy none. All 422. */
    @Test
    fun offsetRangesPerPolicy() = runTest {
        val h = SeasonCommandHarness()
        h.asset(breakStart = "12-01", breakEnd = "02-28")
        val bad = listOf(ScheduleProblem.PolicyOffsetInvalid)

        for ((case, expected) in listOf(
            (ServicePolicy.IN_SERVICE_AT_START to -1) to bad,
            (ServicePolicy.IN_SERVICE_AT_START to 0) to emptyList(),
            (ServicePolicy.IN_SERVICE_AT_START to 365) to emptyList(),
            (ServicePolicy.IN_SERVICE_AT_START to 366) to bad,
            (ServicePolicy.IN_SERVICE_AT_START to null) to bad,
            (ServicePolicy.PRE_SERVICE to 0) to bad,
            (ServicePolicy.PRE_SERVICE to -1) to emptyList(),
            (ServicePolicy.PRE_SERVICE to -365) to emptyList(),
            (ServicePolicy.PRE_SERVICE to -366) to bad,
            (ServicePolicy.PRE_SERVICE to null) to bad,
            (ServicePolicy.IN_SERVICE_RESUME_CLAMPED to null) to emptyList(),
            (ServicePolicy.IN_SERVICE_RESUME_CLAMPED to 0) to bad,
            (ServicePolicy.CONTINUOUS to null) to emptyList(),
            (ServicePolicy.CONTINUOUS to 0) to bad,
        )) {
            val (policy, offset) = case
            h.assertPolicy(expected, command(policy, offset), "$policy / $offset")
        }
        assertEquals(emptyMap(), h.schedules.rows.toMap(), "a refusal writes nothing")

        // Collected with the shipped problems, not instead of them.
        assertEquals(
            listOf(ScheduleProblem.NegativeLeadDays, ScheduleProblem.PolicyOffsetInvalid),
            h.problemsOf(command(ServicePolicy.PRE_SERVICE, 14).copy(leadDays = -1)),
        )
    }

    /**
     * O-7, inv. 95: a meter-only schedule is phase-only. PRE_SERVICE, or an IN_SERVICE offset other than
     * 0, has no date to move and is `SeasonPolicyNeedsATimeRule` — ahead of `PolicyOffsetInvalid`.
     */
    @Test
    fun meterOnlyIsPhaseOnly() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31")
        h.definitions.upsert(
            MeasurementDefinition(
                id = DefinitionId("hours"), assetId = AssetId("a1"), key = "hours", label = "Hours", unit = "h",
                valueType = ValueType.NUMBER, decimals = 1, rangeLow = null, rangeHigh = null, isMeter = true,
                sortOrder = 0, archivedAt = null, createdAt = 1L, updatedAt = 1L,
            ),
        )
        fun meterOnly(policy: ServicePolicy, offset: Int?) = command(policy, offset).copy(
            timeInterval = null, timeUnit = null, anchorOn = null,
            meterDefinitionId = DefinitionId("hours"), meterInterval = 50.0,
        )
        val needsTime = listOf(ScheduleProblem.SeasonPolicyNeedsATimeRule)
        val bad = listOf(ScheduleProblem.PolicyOffsetInvalid)

        h.assertPolicy(needsTime, meterOnly(ServicePolicy.PRE_SERVICE, -14), "PRE_SERVICE")
        h.assertPolicy(needsTime, meterOnly(ServicePolicy.PRE_SERVICE, null), "PRE_SERVICE with none: ahead of the offset problem")
        h.assertPolicy(needsTime, meterOnly(ServicePolicy.IN_SERVICE_AT_START, 5), "AT_START 5")
        h.assertPolicy(needsTime, meterOnly(ServicePolicy.IN_SERVICE_AT_START, 400), "AT_START 400: ahead of the offset problem")
        // RESUME_CLAMPED has no offset at all, so a time rule would not cure one: the offset is the problem.
        h.assertPolicy(bad, meterOnly(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, 5), "RESUME_CLAMPED 5")
        h.assertPolicy(bad, meterOnly(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, 0), "RESUME_CLAMPED 0")
        assertEquals(emptyMap(), h.schedules.rows.toMap())

        h.assertPolicy(emptyList(), meterOnly(ServicePolicy.IN_SERVICE_AT_START, 0), "dormancy with offset 0")
        h.assertPolicy(emptyList(), meterOnly(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, null), "RESUME_CLAMPED")
        h.assertPolicy(emptyList(), meterOnly(ServicePolicy.CONTINUOUS, null), "CONTINUOUS")
    }

    /** Inv. 106: a season and a break live on one asset; a group target is CONTINUOUS only. */
    @Test
    fun aGroupTargetIsContinuousOnly() = runTest {
        val h = SeasonCommandHarness()
        h.asset()
        h.groups.upsert(groupOf("g1", members = listOf(Triple("a1", "2026-01-01", null))))

        // The group problem is the whole answer, whatever the offset: CONTINUOUS is the remedy, and it makes
        // the offset question moot, so a group body never gets the offset's code.
        for ((policy, offset) in listOf(
            ServicePolicy.IN_SERVICE_AT_START to 0,
            ServicePolicy.IN_SERVICE_RESUME_CLAMPED to null,
            ServicePolicy.PRE_SERVICE to -14,
            ServicePolicy.PRE_SERVICE to null,
            ServicePolicy.IN_SERVICE_RESUME_CLAMPED to 5,
        )) {
            h.assertPolicy(
                listOf(ScheduleProblem.SeasonFollowsAssetOnGroupTarget),
                command(policy, offset, assetId = null, groupId = "g1"),
                "$policy / $offset on a group",
            )
        }
        assertEquals(emptyMap(), h.schedules.rows.toMap())
        h.assertPolicy(
            listOf(ScheduleProblem.PolicyOffsetInvalid),
            command(ServicePolicy.CONTINUOUS, 0, assetId = null, groupId = "g1"),
            "CONTINUOUS with an offset on a group",
        )

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

        // The body's 422 comes before the asset's 409: a PRE_SERVICE body with no offset is fixed first.
        assertEquals(
            listOf(ScheduleProblem.PolicyOffsetInvalid),
            assertFailsWith<ScheduleValidation> {
                h.saveSchedule.run(null, command(ServicePolicy.PRE_SERVICE, null, assetId = "a1"))
            }.problems,
        )

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
