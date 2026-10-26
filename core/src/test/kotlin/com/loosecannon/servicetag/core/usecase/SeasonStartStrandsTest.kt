package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.season.NotReadyReason
import com.loosecannon.servicetag.core.season.Readiness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * #114 (A2; C11; rows 18–20) — the hooks on the shipped commands: a change that would leave a live Season Start role
 * where it may not stand is refused with `SeasonStartStrands`, after the PRE_SERVICE strands rule, and writes nothing;
 * the indirect breaks C11 leaves unhooked are read by readiness, not refused. Every name is fictional.
 */
class SeasonStartStrandsTest {
    private val h = SeasonStartHarness()

    private fun strand(asset: String?, cause: SeasonStartStrandCause, vararg schedules: Pair<String, String>) =
        Triple(asset?.let(::AssetId), cause, schedules.map { (id, title) -> StrandedSchedule(ScheduleId(id), title) })

    private suspend fun stranded(block: suspend () -> Unit): Triple<AssetId?, SeasonStartStrandCause, List<StrandedSchedule>> {
        val assets = h.raw.assets.rows.toMap()
        val schedules = h.raw.schedules.rows.toMap()
        val groups = h.raw.groups.rows.toMap()
        h.writes = 0
        val e = assertFailsWith<SeasonStartStrands> { block() }
        assertEquals(0, h.writes, "a strand writes nothing")
        assertEquals(assets, h.raw.assets.rows.toMap())
        assertEquals(schedules, h.raw.schedules.rows.toMap())
        assertEquals(groups, h.raw.groups.rows.toMap())
        return Triple(e.assetId, e.strandCause, e.schedules)
    }

    private fun settings(asset: AssetCommand, mode: SeasonModeCommand) = AssetSettingsCommand(
        asset = asset,
        seasonMode = mode,
        maintenanceBreak = BreakCommand(null, null),
        healthPolicy = HealthPolicyCommand(HealthAggregation.WORST),
    )

    // ---- Row 18 — NEEDS_SEASONS ------------------------------------------------------------------------------------

    @Test fun yearRoundIsRefusedOnACoveredAssetBySetSeasonModeUpdateAssetAndSaveAssetSettings() = runTest {
        val heater = h.asset("heater", mode = SeasonMode.CALENDAR, start = "10-01", end = "04-30")
        h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        val expected = strand("heater", SeasonStartStrandCause.NEEDS_SEASONS, "s1" to "Annual service")

        assertEquals(
            expected,
            stranded { h.setSeasonMode.run(AssetId("heater"), SeasonModeCommand(SeasonMode.YEAR_ROUND)) },
            "SetSeasonMode",
        )
        assertEquals(
            expected,
            stranded {
                h.updateAsset.run(AssetId("heater"), h.assetCommandOf(heater).copy(seasonStartMmdd = null, seasonEndMmdd = null))
            },
            "UpdateAsset's legacy pair",
        )
        assertEquals(
            expected,
            stranded {
                h.saveAssetSettings.run(
                    AssetId("heater"),
                    settings(h.assetCommandOf(heater), SeasonModeCommand(SeasonMode.YEAR_ROUND)),
                )
            },
            "SaveAssetSettings",
        )
    }

    @Test fun aGroupRoleCoversForNeedsSeasonsToo() = runTest {
        h.asset("boiler")
        h.group("g1", Triple("boiler", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", title = "Pre-season check", role = ScheduleRole.SEASON_START)

        assertEquals(
            strand("boiler", SeasonStartStrandCause.NEEDS_SEASONS, "sg" to "Pre-season check"),
            stranded { h.setSeasonMode.run(AssetId("boiler"), SeasonModeCommand(SeasonMode.YEAR_ROUND)) },
        )
    }

    @Test fun aPreServiceStrandStillWinsFirst() = runTest {
        h.asset("heater", mode = SeasonMode.CALENDAR, start = "10-01", end = "04-30")
        h.schedule("s1", role = ScheduleRole.SEASON_START)
        h.schedule("p1", title = "Chimney sweep", policy = ServicePolicy.PRE_SERVICE, offset = -14)

        assertFailsWith<SeasonModeStrandsPolicy> {
            h.setSeasonMode.run(AssetId("heater"), SeasonModeCommand(SeasonMode.YEAR_ROUND))
        }
        assertEquals(0, h.writes)
    }

    @Test fun anUncoveredAssetGoesYearRound() = runTest {
        h.asset("heater", mode = SeasonMode.CALENDAR, start = "10-01", end = "04-30")
        h.schedule("s1")

        val saved = h.setSeasonMode.run(AssetId("heater"), SeasonModeCommand(SeasonMode.YEAR_ROUND))
        assertEquals(SeasonMode.YEAR_ROUND, saved.seasonMode)
    }

    // ---- Row 19 — WOULD_BE_LEAF_CHILD ------------------------------------------------------------------------------

    @Test fun reparentingARoleAssetIntoALeafChildIsRefused() = runTest {
        h.asset("house", mode = SeasonMode.YEAR_ROUND)
        val heater = h.asset("heater")
        h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        val expected = strand("heater", SeasonStartStrandCause.WOULD_BE_LEAF_CHILD, "s1" to "Annual service")

        assertEquals(
            expected,
            stranded { h.updateAsset.run(AssetId("heater"), h.assetCommandOf(heater, parent = AssetId("house"))) },
            "UpdateAsset",
        )
        assertEquals(
            expected,
            stranded {
                h.saveAssetSettings.run(
                    AssetId("heater"),
                    settings(h.assetCommandOf(heater, parent = AssetId("house")), SeasonModeCommand(SeasonMode.MANUAL)),
                )
            },
            "SaveAssetSettings",
        )
    }

    @Test fun movingTheLastChildAwayFromAChildRoleAssetIsRefused() = runTest {
        h.asset("house", mode = SeasonMode.YEAR_ROUND)
        h.asset("heater", parent = "house")
        val burner = h.asset("burner", mode = SeasonMode.YEAR_ROUND, parent = "heater")
        h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        val expected = strand("heater", SeasonStartStrandCause.WOULD_BE_LEAF_CHILD, "s1" to "Annual service")

        assertEquals(
            expected,
            stranded { h.updateAsset.run(AssetId("burner"), h.assetCommandOf(burner, parent = null)) },
            "UpdateAsset: the old parent loses its last child",
        )
        assertEquals(
            expected,
            stranded {
                h.saveAssetSettings.run(
                    AssetId("burner"),
                    settings(h.assetCommandOf(burner, parent = AssetId("house")), SeasonModeCommand(SeasonMode.YEAR_ROUND)),
                )
            },
            "SaveAssetSettings: moved to a sibling of its old parent",
        )
    }

    @Test fun reparentingAnUncoveredAssetIsUnchanged() = runTest {
        h.asset("house", mode = SeasonMode.YEAR_ROUND)
        val heater = h.asset("heater")
        h.asset("pump", mode = SeasonMode.YEAR_ROUND, parent = "heater")
        h.schedule("s1")

        val moved = h.updateAsset.run(AssetId("heater"), h.assetCommandOf(heater, parent = AssetId("house")))
        assertEquals(AssetId("house"), moved.parentAssetId)

        val pump = h.raw.assets.rows.getValue("pump")
        val freed = h.saveAssetSettings.run(
            AssetId("pump"),
            settings(h.assetCommandOf(pump, parent = null), SeasonModeCommand(SeasonMode.YEAR_ROUND)),
        )
        assertEquals(null, freed.parentAssetId, "heater, uncovered, may lose its last child")
    }

    // ---- Row 20 — retarget, group membership, the indirect break and the QUICK election ---------------------------

    @Test fun retargetingARoleScheduleIsRefused() = runTest {
        h.asset("heater")
        h.asset("boiler")
        val s1 = h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)

        assertEquals(
            strand("heater", SeasonStartStrandCause.ROLE_SCHEDULE_RETARGET, "s1" to "Annual service"),
            stranded { h.saveSchedule.run(ScheduleId("s1"), h.commandOf(s1).copy(targetAssetId = AssetId("boiler"))) },
            "O-6: clear the role first",
        )

        val retitled = h.saveSchedule.run(ScheduleId("s1"), h.commandOf(s1).copy(title = "Annual service and clean"))
        assertEquals("Annual service and clean", retitled.title, "an edit that keeps the target is not a retarget")
        assertEquals(ScheduleRole.SEASON_START, retitled.role)
    }

    @Test fun retargetingAGroupRoleScheduleNamesNoAsset() = runTest {
        h.asset("heater")
        h.asset("boiler")
        h.group("g1", Triple("heater", "2026-01-01", null))
        h.group("g2", Triple("boiler", "2026-01-01", null))
        val sg = h.schedule("sg", groupId = "g1", title = "Pre-season check", role = ScheduleRole.SEASON_START)

        assertEquals(
            strand(null, SeasonStartStrandCause.ROLE_SCHEDULE_RETARGET, "sg" to "Pre-season check"),
            stranded { h.saveSchedule.run(ScheduleId("sg"), h.commandOf(sg).copy(targetGroupId = GroupId("g2"))) },
        )
    }

    @Test fun addingAnIneligibleOrCoveredMemberToARoleGroupIsRefused() = runTest {
        h.asset("heater")
        h.asset("pump", mode = SeasonMode.YEAR_ROUND)
        h.asset("boiler")
        h.asset("stove")
        h.schedule("s-boiler", assetId = "boiler", title = "Boiler service", role = ScheduleRole.SEASON_START)
        h.group("g1", Triple("heater", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", title = "Pre-season check", role = ScheduleRole.SEASON_START)
        val kept = GroupMemberInput(AssetId("heater"), id = "g1-m1")

        assertEquals(
            strand("pump", SeasonStartStrandCause.MEMBER_NOT_ELIGIBLE, "sg" to "Pre-season check"),
            stranded {
                h.saveGroup.run(GroupId("g1"), GroupCommand("Example group g1", members = listOf(kept, GroupMemberInput(AssetId("pump")))))
            },
        )
        assertEquals(
            strand("boiler", SeasonStartStrandCause.MEMBER_ALREADY_COVERED, "s-boiler" to "Boiler service", "sg" to "Pre-season check"),
            stranded {
                h.saveGroup.run(GroupId("g1"), GroupCommand("Example group g1", members = listOf(kept, GroupMemberInput(AssetId("boiler")))))
            },
        )

        val saved = h.saveGroup.run(
            GroupId("g1"),
            GroupCommand("Example group g1", members = listOf(kept, GroupMemberInput(AssetId("stove")))),
        )
        assertEquals(2, saved.members.count { it.removedAt == null }, "an eligible, uncovered asset joins")
    }

    @Test fun aGroupWithNoRoleTakesAnyMember() = runTest {
        h.asset("heater")
        h.asset("pump", mode = SeasonMode.YEAR_ROUND)
        h.group("g1", Triple("heater", "2026-01-01", null))
        h.schedule("sg", groupId = "g1")

        val saved = h.saveGroup.run(
            GroupId("g1"),
            GroupCommand("Example group g1", members = listOf(GroupMemberInput(AssetId("heater"), id = "g1-m1"), GroupMemberInput(AssetId("pump")))),
        )
        assertEquals(2, saved.members.size)
    }

    @Test fun anIndirectBreakIsReadNotRefused() = runTest {
        h.asset("house", mode = SeasonMode.YEAR_ROUND)
        h.asset("heater", parent = "house")
        h.asset("burner", mode = SeasonMode.YEAR_ROUND, parent = "heater")
        h.schedule("s1", role = ScheduleRole.SEASON_START)

        h.deleteAsset.run(AssetId("burner"))

        val readiness = h.readiness("heater")
        assertIs<Readiness.NotReady>(readiness)
        assertEquals(NotReadyReason.ROLE_NOT_ALLOWED, readiness.reason, "C9 reads the break; the delete is not hooked")
        assertEquals(ScheduleRole.SEASON_START, h.stored("s1").role)
    }

    @Test fun aFormToQuickEditOfARoleScheduleNeedsTheElection() = runTest {
        h.asset("heater")
        val s1 = h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        val quick = h.commandOf(s1).copy(completionMode = CompletionMode.QUICK)

        assertEquals(
            strand("heater", SeasonStartStrandCause.QUICK_NEEDS_ELECTION, "s1" to "Annual service"),
            stranded { h.saveSchedule.run(ScheduleId("s1"), quick) },
            "F-22: refused without the election",
        )

        val saved = h.saveSchedule.run(ScheduleId("s1"), quick, seasonStartQuickElected = true)
        assertEquals(CompletionMode.QUICK, saved.completionMode, "accepted with it")
        assertEquals(ScheduleRole.SEASON_START, saved.role)
        assertEquals("2026-09-01", saved.roleSinceOn)
    }

    @Test fun aQuickEditOfAScheduleWithNoRoleNeedsNoElection() = runTest {
        h.asset("heater")
        val s1 = h.schedule("s1")

        val saved = h.saveSchedule.run(ScheduleId("s1"), h.commandOf(s1).copy(completionMode = CompletionMode.QUICK))
        assertEquals(CompletionMode.QUICK, saved.completionMode)
    }

    // ---- Concern 8 — a hook judges the change, never a break already stored ------------------------------------------

    @Test fun anAlreadyBrokenRowBlocksNoUnrelatedEdit() = runTest {
        val heater = h.asset("heater", mode = SeasonMode.YEAR_ROUND)
        h.schedule("s1", role = ScheduleRole.SEASON_START)
        val renamed = h.updateAsset.run(AssetId("heater"), h.assetCommandOf(heater).copy(name = "Example heater, renamed"))
        assertEquals("Example heater, renamed", renamed.name, "a covered YEAR_ROUND asset is renamed; C9 reads the break")

        h.asset("pump", mode = SeasonMode.YEAR_ROUND)
        h.group("g1", Triple("pump", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", role = ScheduleRole.SEASON_START)
        val group = h.saveGroup.run(
            GroupId("g1"),
            GroupCommand("Example group renamed", members = listOf(GroupMemberInput(AssetId("pump"), id = "g1-m1"))),
        )
        assertEquals("Example group renamed", group.name, "a role group with a YEAR_ROUND open member is renamed")
    }

    // ---- M-4 (C11 erratum) — a restore from the archive runs the same rules -----------------------------------------

    @Test fun restoringARoleScheduleThatWouldBeASecondRoleIsRefused() = runTest {
        h.asset("heater")
        h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        h.schedule("s2", title = "Burner check")
        h.archiveSchedule.run(ScheduleId("s1"), archived = true)
        h.setScheduleRole.run(ScheduleId("s2"), ScheduleRole.SEASON_START)

        assertEquals(
            strand("heater", SeasonStartStrandCause.RESTORE_ALREADY_COVERED, "s1" to "Annual service", "s2" to "Burner check"),
            stranded { h.archiveSchedule.run(ScheduleId("s1"), archived = false) },
            "C9 would read TWO_ROLES",
        )
        assertEquals(
            null,
            assertFailsWith<SeasonStartStrands> { h.archiveSchedule.run(ScheduleId("s1"), archived = false) }.refusal,
            "only RESTORE_NOT_ELIGIBLE carries the asset rule's answer",
        )
    }

    @Test fun restoringARoleScheduleOverAnIneligibleAssetIsRefused() = runTest {
        h.asset("heater", mode = SeasonMode.CALENDAR, start = "10-01", end = "04-30")
        h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        h.archiveSchedule.run(ScheduleId("s1"), archived = true)
        h.setSeasonMode.run(AssetId("heater"), SeasonModeCommand(SeasonMode.YEAR_ROUND))

        assertEquals(
            strand("heater", SeasonStartStrandCause.RESTORE_NOT_ELIGIBLE, "s1" to "Annual service"),
            stranded { h.archiveSchedule.run(ScheduleId("s1"), archived = false) },
        )
        assertEquals(
            SeasonStartRefusal.YearRound(AssetId("heater")),
            assertFailsWith<SeasonStartStrands> { h.archiveSchedule.run(ScheduleId("s1"), archived = false) }.refusal,
            "the phone says which rule refused (P114-17)",
        )

        h.asset("boiler")
        h.schedule("s-boiler", assetId = "boiler", role = ScheduleRole.SEASON_START, status = ScheduleStatus.ARCHIVED)
        assertEquals(ScheduleStatus.ACTIVE, h.archiveSchedule.run(ScheduleId("s-boiler"), archived = false).status, "the control")
    }

    @Test fun restoringARoleGroupWhoseMemberHasAnotherRoleIsRefused() = runTest {
        h.asset("heater")
        h.asset("boiler")
        h.group("g1", Triple("heater", "2026-01-01", null), Triple("boiler", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", title = "Pre-season check")
        h.schedule("s-boiler", assetId = "boiler", title = "Boiler service")
        h.setScheduleRole.run(ScheduleId("sg"), ScheduleRole.SEASON_START, quickElected = true)
        h.archiveGroup.run(GroupId("g1"), archived = true)
        h.setScheduleRole.run(ScheduleId("s-boiler"), ScheduleRole.SEASON_START)

        assertEquals(
            strand("boiler", SeasonStartStrandCause.RESTORE_ALREADY_COVERED, "s-boiler" to "Boiler service", "sg" to "Pre-season check"),
            stranded { h.archiveGroup.run(GroupId("g1"), archived = false) },
        )
    }

    @Test fun restoringARoleGroupWithAnIneligibleMemberIsRefused() = runTest {
        h.asset("heater")
        h.asset("pump", mode = SeasonMode.CALENDAR, start = "10-01", end = "04-30")
        h.group("g1", Triple("heater", "2026-01-01", null), Triple("pump", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", title = "Pre-season check")
        h.setScheduleRole.run(ScheduleId("sg"), ScheduleRole.SEASON_START, quickElected = true)
        h.archiveGroup.run(GroupId("g1"), archived = true)
        h.setSeasonMode.run(AssetId("pump"), SeasonModeCommand(SeasonMode.YEAR_ROUND))

        assertEquals(
            strand("pump", SeasonStartStrandCause.RESTORE_NOT_ELIGIBLE, "sg" to "Pre-season check"),
            stranded { h.archiveGroup.run(GroupId("g1"), archived = false) },
        )

        h.group("g2", Triple("pump", "2026-01-01", null), archivedAt = 1L)
        assertEquals(null, h.archiveGroup.run(GroupId("g2"), archived = false).archivedAt, "the control: no role, no rule")
    }
}
