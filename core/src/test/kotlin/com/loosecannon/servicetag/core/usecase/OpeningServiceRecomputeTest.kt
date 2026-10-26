package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.season.OpeningServiceReader
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncGuard
import com.loosecannon.servicetag.core.testing.activationOf
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.scheduleOf
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * #124 (C41; rows 143–145) — the feed: `RecomputeSchedules` hands a schedule counting from the opening service its
 * asset's qualifying day, read by the one reader inside the write, and asks the reader for no other schedule; a group
 * role's members each count from their own completion (C1); and every change that can move that day rebuilds the
 * dependent in the same write — an asset-role and a group-role completion, the role set and moved, a role group
 * restored, an END and a START, a deleted completion (clearing a role under a live dependent is refused, N2b). Every name is fictional; today is 2026-10-26 and the zone UTC.
 */
class OpeningServiceRecomputeTest {
    private val h = SeasonStartHarness()
    private val completeGroupMembers = CompleteGroupMembers(
        h.schedules, h.groups, h.raw.events, h.raw.closures, h.raw.definitions, h.raw.profiles, h.raw.supplyItems,
        h.raw.uow, h.ids, h.clock, h.recompute, h.todayPort,
    )
    private val recordActivation = RecordSeasonActivation(
        h.assets, h.raw.events, h.activations, h.raw.uow, h.ids, h.clock, h.todayPort, h.recompute,
        SeasonSyncGuard(h.raw.seasonSyncBindings),
    )
    private val deleteEvent = DeleteEvent(h.raw.events, h.raw.attachments, h.raw.storage, h.raw.uow, h.recompute)

    /** In season from 1 April to 31 December: on 2026-10-26 its cycle started 2026-04-01. */
    private fun calendar(id: String) = h.asset(id, mode = SeasonMode.CALENDAR, start = "04-01", end = "12-31")

    /** A yearly schedule due 2026-03-01 (never done), stored as it is; [role] gives it the role since [since]. */
    private fun yearly(
        id: String,
        assetId: String? = "heater",
        groupId: String? = null,
        policy: ServicePolicy = ServicePolicy.CONTINUOUS,
        offset: Int? = null,
        mode: CompletionMode = if (groupId != null) CompletionMode.QUICK else CompletionMode.FORM,
        role: ScheduleRole = ScheduleRole.NONE,
        since: String = "2026-09-01",
    ): MaintenanceSchedule = scheduleOf(
        id = id,
        assetId = if (groupId != null) null else assetId,
        groupId = groupId,
        title = "Example $id",
        timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR,
        anchorOn = "2026-03-01",
        servicePolicy = policy,
        policyOffsetDays = offset,
        completionMode = mode,
        ruleChangedOn = "2026-01-01",
    ).copy(role = role, roleSinceOn = since.takeIf { role == ScheduleRole.SEASON_START })
        .also { h.raw.schedules.rows[it.id.value] = it }

    /** A schedule on [assetId] counting from its opening service plus [offset] days. */
    private fun dependent(id: String, assetId: String = "heater", offset: Int = 14) =
        yearly(id, assetId, policy = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, offset = offset)

    private fun state(id: String): ScheduleState = h.states.rows.getValue(id)

    private fun assertWaiting(id: String, why: String) {
        val state = state(id)
        assertNull(state.actionableDueOn, why)
        assertEquals(PolicyReason.AWAITING_OPENING_SERVICE, state.policyReason, why)
        assertEquals(PolicyPhase.DORMANT, state.policyPhase, why)
    }

    private fun assertCounts(id: String, on: String, reason: PolicyReason, why: String) {
        val state = state(id)
        assertEquals(on, state.actionableDueOn, why)
        assertEquals(reason, state.policyReason, why)
        assertEquals(PolicyPhase.ACTIVE, state.policyPhase, why)
    }

    private suspend fun complete(scheduleId: String, on: String) =
        h.completeSchedule.run(ScheduleId(scheduleId), CompletionCommand(occurredOn = on, tzId = "UTC", viaForm = true))

    // ---- Row 143 — the dependent's stored state carries the reader's day ---------------------------------------

    @Test fun aDependentsStoredStateCarriesTheReadersDay() = runTest {
        calendar("heater")
        yearly("s-role", role = ScheduleRole.SEASON_START)
        dependent("d-heater")
        h.recompute.forAsset(AssetId("heater"))
        assertWaiting("d-heater", "no opening service yet")

        complete("s-role", "2026-10-01")
        assertEquals(
            LocalDate.parse("2026-10-01"),
            h.raw.uow.read { h.openingServiceReader.openingServiceOn(AssetId("heater")) },
        )
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the service's day plus 14")
        assertEquals("2026-10-15", h.recompute.readState(h.stored("d-heater")).actionableDueOn, "the read path agrees")
    }

    @Test fun aScheduleThatDoesNotCountFromTheOpeningServiceNeverAsksTheReader() = runTest {
        val asked = mutableListOf<AssetId>()
        val counting = OpeningServiceReader { asked += it; LocalDate.parse("2026-10-01") }
        val recompute = RecomputeSchedules(
            h.schedules, h.states, h.raw.events, h.raw.closures, h.groups, h.assets, h.activations, h.todayPort, h.clock,
            counting,
        ) { ZoneOffset.UTC }
        calendar("heater")
        calendar("boiler")
        h.group("g1", Triple("heater", "2025-12-01", null), Triple("boiler", "2025-12-01", null))
        yearly("s-role", role = ScheduleRole.SEASON_START)
        yearly("s-continuous")
        yearly("s-at-start", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 14)
        yearly("s-meter-free-group", groupId = "g1")
        // Only a merge can store the value on a group target (inv. 106): it is no asset's, so it reads no day.
        yearly("s-group-value", groupId = "g1", policy = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE)

        recompute.all()
        assertEquals(emptyList(), asked, "no schedule here counts from its asset's opening service")
        assertNull(recompute.quietUntil(h.stored("s-at-start")))
        assertEquals(emptyList(), asked)

        dependent("d-boiler", assetId = "boiler")
        recompute.all()
        assertEquals(listOf(AssetId("boiler")), asked, "asked once, for the dependent's own asset")
        assertCounts("d-boiler", "2026-10-15", PolicyReason.OPENING_SERVICE, "the reader's day reached the state")
    }

    @Test fun twoMembersOfOneRoleGroupEachCountFromTheirOwnCompletion() = runTest {
        calendar("heater")
        calendar("boiler")
        h.group("g1", Triple("heater", "2025-12-01", null), Triple("boiler", "2025-12-01", null))
        yearly("g-role", groupId = "g1", role = ScheduleRole.SEASON_START)
        dependent("d-heater", assetId = "heater")
        dependent("d-boiler", assetId = "boiler", offset = 0)
        h.recompute.all()

        completeGroupMembers.run(
            ScheduleId("g-role"), listOf(AssetId("heater")), CompletionCommand(occurredOn = "2026-10-01", tzId = "UTC"),
        )
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the heater's own completion, in that write")
        assertWaiting("d-boiler", "the heater's completion is not the boiler's")

        completeGroupMembers.run(
            ScheduleId("g-role"), listOf(AssetId("boiler")), CompletionCommand(occurredOn = "2026-10-05", tzId = "UTC"),
        )
        assertCounts("d-boiler", "2026-10-05", PolicyReason.OPENING_SERVICE, "the boiler's own day")
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the heater's stands")
    }

    // ---- Row 144 — every change that moves the day moves the dependent in the same write -----------------------

    @Test fun theRoleSetMovedAndClearedAndACompletionAddedAndDeletedEachMoveTheDependent() = runTest {
        calendar("heater")
        yearly("s1")
        dependent("d-heater")
        complete("s1", "2026-10-01")
        assertWaiting("d-heater", "a service, but no role yet")

        val roleState = state("s1")
        h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, "2026-09-01")
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the role set")
        assertEquals(roleState.copy(computedAt = 0), state("s1").copy(computedAt = 0), "the role schedule's own state (row 21)")

        h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, "2026-10-05")
        assertWaiting("d-heater", "the role's date moved past the service")

        h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.SEASON_START, "2026-09-01")
        val later = complete("s1", "2026-10-20")
        assertCounts("d-heater", "2026-11-03", PolicyReason.OPENING_SERVICE, "a later completion")

        deleteEvent.run(later.id)
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the later completion deleted")

        // #124 (N2b, C42): clearing the role a live dependent counts from is stranded; the dependent keeps its day.
        assertFailsWith<SeasonStartStrands> { h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.NONE) }
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the role kept")
    }

    @Test fun aGroupRoleSetMovesEveryMembersDependentAndClearingItIsStranded() = runTest {
        calendar("heater")
        calendar("boiler")
        h.group("g1", Triple("heater", "2025-12-01", null), Triple("boiler", "2025-12-01", null))
        yearly("g1-check", groupId = "g1")
        dependent("d-heater", assetId = "heater")
        dependent("d-boiler", assetId = "boiler")
        h.recompute.all()
        completeGroupMembers.all(ScheduleId("g1-check"), CompletionCommand(occurredOn = "2026-10-01", tzId = "UTC"))
        assertWaiting("d-heater", "no role yet")

        h.setScheduleRole.run(ScheduleId("g1-check"), ScheduleRole.SEASON_START, "2026-09-01", quickElected = true)
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the group role set")
        assertCounts("d-boiler", "2026-10-15", PolicyReason.OPENING_SERVICE, "every open member")

        // #124 (N2b, C42): clearing it while the members' dependents count from it is stranded.
        assertFailsWith<SeasonStartStrands> { h.setScheduleRole.run(ScheduleId("g1-check"), ScheduleRole.NONE) }
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the group role kept")
        assertCounts("d-boiler", "2026-10-15", PolicyReason.OPENING_SERVICE, "the group role kept")
    }

    /**
     * N2a's C41 erratum (the controller, 2026-10-26): a role group's restore moves the role's coverage of each open
     * member, so the same write rebuilds their dependents. (Archiving one under a live dependent is refused, C42.)
     */
    @Test fun restoringARoleGroupMovesEveryMembersDependent() = runTest {
        calendar("heater")
        h.group("g1", Triple("heater", "2025-12-01", null))
        yearly("g1-check", groupId = "g1", role = ScheduleRole.SEASON_START)
        completeGroupMembers.all(ScheduleId("g1-check"), CompletionCommand(occurredOn = "2026-10-01", tzId = "UTC"))
        h.archiveGroup.run(GroupId("g1"), archived = true)
        // A dependent stored while the group is archived (as only a merge could leave it): it waits, uncovered.
        dependent("d-heater")
        h.recompute.forAsset(AssetId("heater"))
        assertWaiting("d-heater", "the role's group archived")

        h.archiveGroup.run(GroupId("g1"), archived = false)
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the role's group restored")
    }

    /** N2b review N-4: a role schedule's restore moves its asset's dependents, as a role group's restore does. */
    @Test fun restoringARoleScheduleMovesItsAssetsDependent() = runTest {
        calendar("heater")
        yearly("s1", role = ScheduleRole.SEASON_START)
        complete("s1", "2026-10-01")
        h.archiveSchedule.run(ScheduleId("s1"), archived = true)
        // A dependent stored while the role is archived (as only a merge could leave it): it waits, uncovered.
        dependent("d-heater")
        h.recompute.forAsset(AssetId("heater"))
        assertWaiting("d-heater", "the role archived")

        h.archiveSchedule.run(ScheduleId("s1"), archived = false)
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the role restored")
    }

    @Test fun anEndAndAStartEachMoveTheDependent() = runTest {
        h.asset("boiler", mode = SeasonMode.MANUAL)
        h.raw.activations.rows["start"] = activationOf(
            "start", "boiler", SeasonAction.START, "2026-09-01", createdAt = dayMillis("2026-09-01"),
        )
        yearly("s-b", assetId = "boiler", role = ScheduleRole.SEASON_START)
        dependent("d-boiler", assetId = "boiler", offset = 0)
        complete("s-b", "2026-10-01")
        assertCounts("d-boiler", "2026-10-01", PolicyReason.OPENING_SERVICE, "in season, ready")

        recordActivation.run(AssetId("boiler"), ActivationCommand(SeasonAction.END, "2026-10-20"))
        assertNull(state("d-boiler").actionableDueOn, "the END")
        assertEquals(PolicyReason.AWAITING_START, state("d-boiler").policyReason, "out of season it waits as AT_START")

        recordActivation.run(AssetId("boiler"), ActivationCommand(SeasonAction.START))
        assertWaiting("d-boiler", "the START: the season ended 2026-10-20, after the service")
    }

    // ---- Row 145 — the shipped constructions compile with no feed (pin) ----------------------------------------

    @Test fun theShippedConstructionHasNoFeedSoADependentWaits() = runTest {
        val shipped = RecomputeSchedules(
            h.schedules, h.states, h.raw.events, h.raw.closures, h.groups, h.assets, h.activations, h.todayPort, h.clock,
        ) { ZoneOffset.UTC }
        calendar("heater")
        yearly("s-role", role = ScheduleRole.SEASON_START)
        dependent("d-heater")
        complete("s-role", "2026-10-01")
        assertCounts("d-heater", "2026-10-15", PolicyReason.OPENING_SERVICE, "the harness's graph feeds the day")

        shipped.forAsset(AssetId("heater"))
        assertWaiting("d-heater", "OpeningServiceReader.NONE: production never builds it so (R6)")
    }
}
