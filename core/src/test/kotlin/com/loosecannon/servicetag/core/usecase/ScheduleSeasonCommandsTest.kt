package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.seasonsync.ForgetHaConnection
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefusal
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefused
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncGuard
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.core.testing.groupOf
import com.loosecannon.servicetag.core.testing.haConnectionOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.scheduleSeasonBindingOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.usecase.ScheduleSeasonHarness.Companion.HEATER
import com.loosecannon.servicetag.core.usecase.ScheduleSeasonHarness.Companion.SWITCH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #125 (U-C3, constraint 6; row h13) — the link, the unlink and the archive: every refusal writes nothing; a
 * `YEAR_ROUND` asset's schedule links; a schedule returns to its asset's season only by the unlink or the archive,
 * never by Forget (stranded, its dates frozen). The "Example Heater" is in use 11-01…03-31; every name is fictional.
 */
class ScheduleSeasonCommandsTest {
    private val h = ScheduleSeasonHarness("2026-10-20")

    private fun snapshot() = listOf(
        h.raw.schedules.rows.toMap(), h.bindings.rows.toMap(), h.rows.rows.toMap(), h.states.rows.toMap(), h.ensured,
    )

    private suspend fun refusedWith(reason: ScheduleSeasonRefusal, id: String = "s-own", entityId: String = SWITCH) {
        val before = snapshot()
        val refused = assertFailsWith<ScheduleSeasonRefused>("$reason") { h.link.run(ScheduleId(id), entityId) }
        assertEquals(reason, refused.reason)
        assertEquals(before, snapshot(), "$reason writes nothing")
    }

    private suspend fun linkedHeater() {
        h.asset()
        h.connect()
        h.schedule("s-own", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0)
        h.schedule("s-other", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0)
        h.link.run(ScheduleId("s-own"), SWITCH)
        h.answer("s-own", HaSwitchState.ON)
    }

    @Test fun eachRefusalWritesNothing() = runTest {
        h.asset()
        h.schedule("s-own", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0)
        refusedWith(ScheduleSeasonRefusal.NO_CONNECTION)
        h.raw.haConnections.upsert(haConnectionOf())
        refusedWith(ScheduleSeasonRefusal.NEEDS_TOKEN)
        h.connect()
        refusedWith(ScheduleSeasonRefusal.BAD_ENTITY_ID, entityId = "switch.example_heater_winterize_season")
        refusedWith(ScheduleSeasonRefusal.BAD_ENTITY_ID, entityId = "input_boolean.Example")
        h.written += SWITCH
        refusedWith(ScheduleSeasonRefusal.OUTBOUND_TARGET)
        h.written.clear()

        h.group("g-1")
        h.schedule("s-group", groupId = "g-1")
        refusedWith(ScheduleSeasonRefusal.GROUP_TARGET, id = "s-group")
        h.schedule("s-archived", status = ScheduleStatus.ARCHIVED)
        refusedWith(ScheduleSeasonRefusal.SCHEDULE_ARCHIVED, id = "s-archived")
        h.schedule("s-pre", policy = ServicePolicy.PRE_SERVICE, offset = -14)
        refusedWith(ScheduleSeasonRefusal.STRANDS_PRE_SERVICE, id = "s-pre")

        h.bindings.insert(scheduleSeasonBindingOf("s-linked"))
        h.schedule("s-linked", source = ScheduleSeasonSource.OWN)
        refusedWith(ScheduleSeasonRefusal.ALREADY_LINKED, id = "s-linked")

        h.raw.transfers.append(transferOf("out-heater", assetId = HEATER))
        refusedWith(ScheduleSeasonRefusal.NOT_MAINTAINED_HERE)
        assertFailsWith<NoSuchSchedule> { h.link.run(ScheduleId("s-none"), SWITCH) }
    }

    @Test fun aLinkWritesTheBindingAndTheSourceAndNoRowAndTheScheduleWaits() = runTest {
        h.asset()
        h.connect()
        h.schedule("s-own", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0)

        val binding = h.link.run(ScheduleId("s-own"), SWITCH)

        assertEquals(binding, h.bindings.rows.getValue("s-own"))
        assertEquals(
            listOf(SWITCH, SyncMode.FOLLOW, true, 1L, "conn-1"),
            listOf(binding.entityId, binding.mode, binding.enabled, binding.revision, binding.connectionId),
        )
        assertEquals(ScheduleSeasonSource.OWN, h.stored("s-own").seasonSource)
        assertTrue(h.rows.rows.isEmpty(), "the post-link check writes the first row")
        val state = h.states.rows.getValue("s-own")
        assertEquals(PolicyReason.AWAITING_START to null, state.policyReason to state.actionableDueOn)
        assertEquals(1, h.ensured)
    }

    @Test fun aYearRoundAssetsScheduleMayLink() = runTest {
        h.asset(mode = SeasonMode.YEAR_ROUND)
        h.connect()
        h.schedule("s-own", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0)

        h.link.run(ScheduleId("s-own"), SWITCH)
        h.answer("s-own", HaSwitchState.ON)

        assertEquals("2026-10-20", h.state("s-own").actionableDueOn, "its own season gives it a start")
    }

    @Test fun linkThenUnlinkReturnsToTheAssetsDatesOnlyOnTheUnlink() = runTest {
        linkedHeater()
        assertEquals("2026-10-20", h.state("s-own").actionableDueOn)
        assertEquals("2026-11-01", h.state("s-other").actionableDueOn, "the asset's other schedule keeps the window")

        h.on("2026-10-28")
        h.recompute.forAsset(AssetId(HEATER))
        assertEquals("2026-10-20", h.state("s-own").actionableDueOn, "still its own")

        val unlinked = h.unlink.run(ScheduleId("s-own"))

        assertEquals(ScheduleSeasonSource.ASSET, unlinked.seasonSource)
        assertNull(h.bindings.rows["s-own"])
        assertEquals("2026-11-01" to PolicyPhase.DORMANT, h.states.rows.getValue("s-own").let { it.actionableDueOn to it.policyPhase })
        assertEquals(1, h.rows.rows.size, "the history is kept")
        assertEquals(2, h.ensured)
    }

    @Test fun unlinkingAnAssetScheduleWritesNothingAndAStrandedOneUnlinks() = runTest {
        h.asset()
        h.schedule("s-asset")
        val before = snapshot()
        assertEquals(h.stored("s-asset"), h.unlink.run(ScheduleId("s-asset")))
        assertEquals(before, snapshot())

        h.schedule("s-stranded", source = ScheduleSeasonSource.OWN)
        assertEquals(ScheduleSeasonSource.ASSET, h.unlink.run(ScheduleId("s-stranded")).seasonSource)
    }

    @Test fun archiveUnlinksInTheSameWriteAndRestoreReadsTheAssetsSeason() = runTest {
        linkedHeater()

        h.archive.run(ScheduleId("s-own"), archived = true)

        assertEquals(ScheduleSeasonSource.ASSET to ScheduleStatus.ARCHIVED, h.stored("s-own").let { it.seasonSource to it.status })
        assertNull(h.bindings.rows["s-own"])
        h.archive.run(ScheduleId("s-own"), archived = false)
        assertEquals(ScheduleSeasonSource.ASSET, h.stored("s-own").seasonSource)
        assertEquals("2026-11-01", h.state("s-own").actionableDueOn)
    }

    @Test fun forgetLeavesTheScheduleOnItsOwnSeasonStrandedWithItsDatesFrozen() = runTest {
        linkedHeater()
        val forget = ForgetHaConnection(h.raw.haConnections, h.secrets, h.scheduler, h.uow)

        forget.run()
        h.on("2026-10-28")
        h.recompute.forAsset(AssetId(HEATER))

        assertNull(h.bindings.rows["s-own"], "the binding goes with the connection")
        assertEquals(ScheduleSeasonSource.OWN, h.stored("s-own").seasonSource, "no silent fallback")
        assertEquals("2026-10-20", h.state("s-own").actionableDueOn, "frozen at its last phase")

        // Following again keeps its own history.
        h.connect()
        h.link.run(ScheduleId("s-own"), SWITCH)
        assertEquals(1, h.rows.rows.size)
        assertEquals("2026-10-20", h.state("s-own").actionableDueOn)
    }

    @Test fun aFullReplaceSaveKeepsTheSource() = runTest {
        linkedHeater()

        h.saveSchedule.run(ScheduleId("s-own"), SeasonStartHarness().commandOf(h.stored("s-own")).copy(title = "Example winterize"))

        assertEquals(ScheduleSeasonSource.OWN, h.stored("s-own").seasonSource)
        assertEquals("2026-10-20", h.state("s-own").actionableDueOn)
    }

    @Test fun anOwnScheduleCannotBeSavedIntoPreServiceWithNoBreakToCountBackFrom() = runTest {
        linkedHeater()
        val before = h.stored("s-own")

        assertFailsWith<PreServiceNeedsDates> {
            h.saveSchedule.run(
                ScheduleId("s-own"),
                SeasonStartHarness().commandOf(before).copy(servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14),
            )
        }
        assertEquals(before, h.stored("s-own"))
    }

    @Test fun theStrandsRuleReadsAnOwnSchedulesBoundaryWhichIsOnlyTheBreak() = runTest {
        h.asset(breakStart = "06-01", breakEnd = "08-31")
        h.schedule("s-pre", policy = ServicePolicy.PRE_SERVICE, offset = -14, source = ScheduleSeasonSource.OWN)
        val guard = SeasonSyncGuard(h.raw.seasonSyncBindings)
        val setSeasonMode = SetSeasonMode(
            h.raw.assets, h.raw.schedules, h.raw.activations, h.uow, h.ids, h.clock, h.todayPort, h.recompute, guard, h.raw.groups,
        )
        val setBreak = SetMaintenanceBreak(h.raw.assets, h.raw.schedules, h.uow, h.clock, h.recompute)

        // The asset's mode and window are not its boundary: CALENDAR → MANUAL re-kinds the asset's, never its own.
        setSeasonMode.run(AssetId(HEATER), SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON))
        assertEquals(SeasonMode.MANUAL, h.raw.assets.rows.getValue(HEATER).seasonMode)

        // Removing the break removes its only boundary.
        val stranded = assertFailsWith<BreakStrandsPolicy> { setBreak.run(AssetId(HEATER), BreakCommand(null, null)) }
        assertEquals(listOf(ScheduleId("s-pre")), stranded.schedules.map { it.id })
    }

    @Test fun aReplaceAssetCopyFollowsItsNewAssetsSeason() = runTest {
        val r = ReplaceHarness()
        r.put(r.assetRow(PRED, "Example Water Heater"))
        r.put(
            scheduleOf("s-own", assetId = PRED, title = "Example winterize", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = "2025-04-01")
                .copy(seasonSource = ScheduleSeasonSource.OWN),
        )

        val successor = r.replaceWith(r.draft(scheduleIds = setOf("s-own"), scheduleStartOn = "2026-09-15")).successor.id

        assertEquals(ScheduleSeasonSource.ASSET, r.copiesFor(successor).single().seasonSource, "a successor inherits no link")
    }

    @Test fun aGroupScheduleIsNeverRecomputedFromAnOwnSeason() = runTest {
        h.asset()
        h.group("g-1")
        h.schedule("s-group", groupId = "g-1", source = ScheduleSeasonSource.OWN) // only a hand-built row carries it
        assertNull(h.recompute.seasonFor(h.stored("s-group")), "a group target reads no season (inv. 106)")
    }

    /**
     * #125 (H1b; the controller's ruling on H1 concern 5) — a schedule following its own season cannot become a
     * group's (inv. 106): the save is refused `GROUP_TARGET` and writes nothing, linked or stranded alike. Moving to
     * another asset keeps the link (U-C3), and an `ASSET` schedule still moves to the group.
     */
    @Test fun aScheduleOnItsOwnSeasonRetargetedToAGroupIsRefusedAndWritesNothing() = runTest {
        linkedHeater()
        h.group("g-1")
        fun toGroup(id: String) = SeasonStartHarness().commandOf(h.stored(id)).copy(
            targetAssetId = null, targetGroupId = GroupId("g-1"), servicePolicy = ServicePolicy.CONTINUOUS, policyOffsetDays = null,
        )
        suspend fun refusedToMove(why: String) {
            val before = snapshot()
            val refused = assertFailsWith<ScheduleSeasonRefused>(why) { h.saveSchedule.run(ScheduleId("s-own"), toGroup("s-own")) }
            assertEquals(ScheduleSeasonRefusal.GROUP_TARGET, refused.reason, why)
            assertEquals(before, snapshot(), "$why: nothing written")
        }

        refusedToMove("linked")
        h.bindings.rows.remove("s-own")
        refusedToMove("stranded")

        h.saveSchedule.run(ScheduleId("s-other"), toGroup("s-other"))
        assertEquals(ScheduleTarget.GroupTarget(GroupId("g-1")), h.stored("s-other").target, "an ASSET schedule moves")
    }

    private fun ScheduleSeasonHarness.group(id: String) {
        raw.groups.rows[id] = groupOf(id = id, name = "Example group $id", members = listOf(Triple(HEATER, "2026-01-01", null)))
    }
}
