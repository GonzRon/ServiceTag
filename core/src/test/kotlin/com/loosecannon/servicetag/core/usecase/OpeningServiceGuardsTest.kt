package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * #124 (N2b; C42; rows 146, 147) — the guards on counting from the opening service. A save choosing the answer on an
 * asset no live role covers, on the role schedule itself or on a `YEAR_ROUND` asset is refused, and so is the role on a
 * schedule that counts from it; every change that would leave a live dependent with no live role — the role cleared or
 * archived, its group archived or the asset taken out of it, the asset going `YEAR_ROUND` — is stranded
 * (`HAS_DEPENDENT_SCHEDULES`), naming the dependents. Nothing is written on a refusal; an archived dependent, or none,
 * lets each change through. Every name is fictional.
 */
class OpeningServiceGuardsTest {
    private val h = SeasonStartHarness()
    private val opening = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE

    /** A new schedule of [asset] counting from its opening service, as the editor sends it. */
    private fun create(asset: String, title: String = "Filter check") = ScheduleCommand(
        targetAssetId = AssetId(asset),
        targetGroupId = null,
        title = title,
        timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR,
        anchorOn = "2026-01-15",
        servicePolicy = opening,
        policyOffsetDays = 0,
        completionMode = CompletionMode.FORM,
    )

    /** A schedule of [asset] counting from its opening service, stored as it is. */
    private fun dependent(
        id: String,
        asset: String = "heater",
        title: String = "Filter check $id",
        status: ScheduleStatus = ScheduleStatus.ACTIVE,
    ): MaintenanceSchedule = h.schedule(id, assetId = asset, title = title, policy = opening, offset = 0, status = status)

    private fun ScheduleCommand.counting() = copy(servicePolicy = opening, policyOffsetDays = 0)

    private suspend fun unchanged(block: suspend () -> Unit) {
        val assets = h.raw.assets.rows.toMap()
        val schedules = h.raw.schedules.rows.toMap()
        val groups = h.raw.groups.rows.toMap()
        h.writes = 0
        block()
        assertEquals(0, h.writes, "a refusal writes nothing")
        assertEquals(assets, h.raw.assets.rows.toMap())
        assertEquals(schedules, h.raw.schedules.rows.toMap())
        assertEquals(groups, h.raw.groups.rows.toMap())
    }

    private suspend fun refused(block: suspend () -> Unit): SeasonStartRefusal {
        var reason: SeasonStartRefusal? = null
        unchanged { reason = assertFailsWith<SeasonStartRefused> { block() }.reason }
        return assertNotNull(reason)
    }

    private suspend fun stranded(block: suspend () -> Unit): Triple<AssetId?, SeasonStartStrandCause, List<StrandedSchedule>> {
        var strands: SeasonStartStrands? = null
        unchanged { strands = assertFailsWith<SeasonStartStrands> { block() } }
        val e = assertNotNull(strands)
        return Triple(e.assetId, e.strandCause, e.schedules)
    }

    private fun dependents(asset: String, vararg schedules: Pair<String, String>) = Triple(
        AssetId(asset),
        SeasonStartStrandCause.HAS_DEPENDENT_SCHEDULES,
        schedules.map { (id, title) -> StrandedSchedule(ScheduleId(id), title) },
    )

    private fun settings(asset: AssetCommand, mode: SeasonModeCommand) = AssetSettingsCommand(
        asset = asset,
        seasonMode = mode,
        maintenanceBreak = BreakCommand(null, null),
        healthPolicy = HealthPolicyCommand(HealthAggregation.WORST),
    )

    // ---- Row 146 — refused: SaveSchedule and SetScheduleRole ---------------------------------------------------------

    @Test fun saveScheduleRefusesTheAnswerOnAnAssetNoLiveRoleCovers() = runTest {
        h.asset("heater")
        // An archived role covers nothing (C9's coverage).
        h.schedule("s-old", title = "Old annual service", role = ScheduleRole.SEASON_START, status = ScheduleStatus.ARCHIVED)
        val sweep = h.schedule("s2", title = "Chimney sweep")

        assertEquals(
            SeasonStartRefusal.NoSeasonStartService(AssetId("heater")),
            refused { h.saveSchedule.run(null, create("heater")) },
            "a create",
        )
        assertEquals(
            SeasonStartRefusal.NoSeasonStartService(AssetId("heater")),
            refused { h.saveSchedule.run(ScheduleId("s2"), h.commandOf(sweep).counting()) },
            "an update into the answer",
        )
    }

    @Test fun theRoleScheduleCannotCountFromItself() = runTest {
        h.asset("heater")
        val role = h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)

        assertEquals(
            SeasonStartRefusal.CountsFromItself,
            refused { h.saveSchedule.run(ScheduleId("s1"), h.commandOf(role).counting()) },
        )
    }

    @Test fun aYearRoundAssetIsRefusedAsYearRound() = runTest {
        h.asset("pump", mode = SeasonMode.YEAR_ROUND)

        assertEquals(SeasonStartRefusal.YearRound(AssetId("pump")), refused { h.saveSchedule.run(null, create("pump")) })
    }

    @Test fun retargetingADependentToAnAssetNoRoleCoversIsRefused() = runTest {
        h.asset("heater")
        h.asset("stove")
        h.schedule("s1", role = ScheduleRole.SEASON_START)
        val d1 = dependent("d1")

        assertEquals(
            SeasonStartRefusal.NoSeasonStartService(AssetId("stove")),
            refused { h.saveSchedule.run(ScheduleId("d1"), h.commandOf(d1).copy(targetAssetId = AssetId("stove"))) },
        )
    }

    @Test fun theRoleIsRefusedOnAScheduleThatCountsFromTheOpeningService() = runTest {
        h.asset("heater")
        h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        dependent("d1")
        h.schedule("d2", title = "Quick check", policy = opening, offset = 0, mode = CompletionMode.QUICK)

        assertEquals(
            SeasonStartRefusal.CountsFromItself,
            refused { h.setScheduleRole.run(ScheduleId("d1"), ScheduleRole.SEASON_START) },
            "before the asset's own rule (another role covers it)",
        )
        assertEquals(
            SeasonStartRefusal.CountsFromItself,
            refused { h.setScheduleRole.run(ScheduleId("d2"), ScheduleRole.SEASON_START) },
            "before the one-tap election",
        )
    }

    @Test fun anAssetAGroupRoleCoversTakesTheAnswer() = runTest {
        h.asset("boiler")
        h.group("g1", Triple("boiler", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", title = "Pre-season check", role = ScheduleRole.SEASON_START)

        val saved = h.saveSchedule.run(null, create("boiler"))

        assertEquals(opening, saved.servicePolicy)
        assertEquals(saved, h.stored(saved.id.value))
    }

    @Test fun anAssetItsOwnLiveRoleCoversTakesTheAnswerAPausedRoleIncluded() = runTest {
        h.asset("heater")
        h.schedule("s1", role = ScheduleRole.SEASON_START, status = ScheduleStatus.PAUSED)
        val sweep = h.schedule("s2", title = "Chimney sweep")

        assertEquals(opening, h.saveSchedule.run(null, create("heater")).servicePolicy)
        assertEquals(opening, h.saveSchedule.run(ScheduleId("s2"), h.commandOf(sweep).counting()).servicePolicy)
    }

    @Test fun anEditThatKeepsTheAnswerAndTheTargetIsNotJudgedAgain() = runTest {
        h.asset("heater")
        val d1 = dependent("d1") // a dependent already stored with no role: C9 reads it as waiting

        val saved = h.saveSchedule.run(ScheduleId("d1"), h.commandOf(d1).copy(title = "Filter check, renamed"))
        assertEquals("Filter check, renamed", saved.title)
    }

    @Test fun restoringADependentFromTheArchiveNeedsALiveRole() = runTest {
        h.asset("heater")
        dependent("d1", status = ScheduleStatus.ARCHIVED)

        assertEquals(
            SeasonStartRefusal.NoSeasonStartService(AssetId("heater")),
            refused { h.archiveSchedule.run(ScheduleId("d1"), archived = false) },
        )

        h.schedule("s1", role = ScheduleRole.SEASON_START)
        assertEquals(ScheduleStatus.ACTIVE, h.archiveSchedule.run(ScheduleId("d1"), archived = false).status)
    }

    /** N2b review I-1: a pause or a resume never brings an archived schedule back past the restore's rule. */
    @Test fun anArchivedScheduleIsNeitherResumedNorPausedBack() = runTest {
        h.asset("heater")
        dependent("d1", status = ScheduleStatus.ARCHIVED)
        val pauseSchedule = PauseSchedule(h.schedules, h.raw.uow, h.recompute)

        unchanged { assertFailsWith<ScheduleArchived> { pauseSchedule.run(ScheduleId("d1"), paused = false) } }
        unchanged { assertFailsWith<ScheduleArchived> { pauseSchedule.run(ScheduleId("d1"), paused = true) } }
        assertEquals(ScheduleStatus.ARCHIVED, h.stored("d1").status)
    }

    // ---- Row 147 — stranded: HAS_DEPENDENT_SCHEDULES -----------------------------------------------------------------

    @Test fun clearingOrArchivingTheRoleWithALiveDependentIsStranded() = runTest {
        h.asset("heater")
        h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        dependent("d2", title = "Filter check")
        dependent("d1", title = "Burner check", status = ScheduleStatus.PAUSED) // PAUSED is live
        dependent("d3", title = "Archived check", status = ScheduleStatus.ARCHIVED) // never named
        val expected = dependents("heater", "d1" to "Burner check", "d2" to "Filter check")

        assertEquals(expected, stranded { h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.NONE) }, "SetScheduleRole(NONE)")
        assertEquals(expected, stranded { h.archiveSchedule.run(ScheduleId("s1"), archived = true) }, "ArchiveSchedule")
    }

    @Test fun aGroupRoleClearedArchivedOrItsGroupArchivedStrandsAMembersDependent() = runTest {
        h.asset("heater")
        h.asset("boiler")
        h.group("g1", Triple("heater", "2026-01-01", null), Triple("boiler", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", title = "Pre-season check", role = ScheduleRole.SEASON_START)
        dependent("d1", asset = "boiler", title = "Boiler filter check")
        val expected = dependents("boiler", "d1" to "Boiler filter check")

        assertEquals(expected, stranded { h.setScheduleRole.run(ScheduleId("sg"), ScheduleRole.NONE) }, "SetScheduleRole(NONE)")
        assertEquals(expected, stranded { h.archiveSchedule.run(ScheduleId("sg"), archived = true) }, "ArchiveSchedule")
        assertEquals(expected, stranded { h.archiveGroup.run(GroupId("g1"), archived = true) }, "ArchiveGroup (N2a 7)")
    }

    @Test fun removingAMemberWithALiveDependentFromARoleGroupIsStranded() = runTest {
        h.asset("heater")
        h.asset("boiler")
        h.group("g1", Triple("heater", "2026-01-01", null), Triple("boiler", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", title = "Pre-season check", role = ScheduleRole.SEASON_START)
        dependent("d1", asset = "boiler", title = "Boiler filter check")
        val heater = GroupMemberInput(AssetId("heater"), id = "g1-m1", sortOrder = 0)
        val boiler = GroupMemberInput(AssetId("boiler"), id = "g1-m2", sortOrder = 1)

        assertEquals(
            dependents("boiler", "d1" to "Boiler filter check"),
            stranded { h.saveGroup.run(GroupId("g1"), GroupCommand("Example group g1", members = listOf(heater))) },
        )

        val saved = h.saveGroup.run(GroupId("g1"), GroupCommand("Example group g1", members = listOf(boiler)))
        assertEquals(listOf(AssetId("boiler")), saved.openMembers().map { it.assetId }, "a member with no dependent leaves")
    }

    @Test fun goingYearRoundWithALiveDependentIsStrandedByEachSeasonModeWriter() = runTest {
        // A dependent no role covers (stored as it is): NEEDS_SEASONS has nothing to name, the dependent does.
        val heater = h.asset("heater", mode = SeasonMode.CALENDAR, start = "10-01", end = "04-30")
        dependent("d1", title = "Filter check")
        val expected = dependents("heater", "d1" to "Filter check")

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

    @Test fun aCoveredAssetGoingYearRoundSaysNeedsSeasonsFirst() = runTest {
        h.asset("heater", mode = SeasonMode.CALENDAR, start = "10-01", end = "04-30")
        h.schedule("s1", title = "Annual service", role = ScheduleRole.SEASON_START)
        dependent("d1")

        assertEquals(
            Triple(
                AssetId("heater"),
                SeasonStartStrandCause.NEEDS_SEASONS,
                listOf(StrandedSchedule(ScheduleId("s1"), "Annual service")),
            ),
            stranded { h.setSeasonMode.run(AssetId("heater"), SeasonModeCommand(SeasonMode.YEAR_ROUND)) },
        )
    }

    @Test fun withOnlyAnArchivedDependentOrNoneEachChangeProceeds() = runTest {
        h.asset("heater", mode = SeasonMode.CALENDAR, start = "10-01", end = "04-30")
        h.asset("boiler")
        h.asset("stove")
        h.schedule("s1", role = ScheduleRole.SEASON_START)
        dependent("d1", status = ScheduleStatus.ARCHIVED)
        h.group("g1", Triple("boiler", "2026-01-01", null), Triple("stove", "2026-01-01", null))
        h.schedule("sg", groupId = "g1", title = "Pre-season check", role = ScheduleRole.SEASON_START)
        dependent("d2", asset = "boiler", status = ScheduleStatus.ARCHIVED)

        assertEquals(ScheduleRole.NONE, h.setScheduleRole.run(ScheduleId("s1"), ScheduleRole.NONE).role, "the role cleared")
        assertEquals(
            SeasonMode.YEAR_ROUND,
            h.setSeasonMode.run(AssetId("heater"), SeasonModeCommand(SeasonMode.YEAR_ROUND)).seasonMode,
            "the asset goes all year",
        )
        // While boiler, holding the archived d2, is still a member of the role group.
        assertNotNull(h.archiveGroup.run(GroupId("g1"), archived = true).archivedAt, "the group archived")
        assertNull(h.archiveGroup.run(GroupId("g1"), archived = false).archivedAt, "and restored")
        assertEquals(ScheduleStatus.ARCHIVED, h.archiveSchedule.run(ScheduleId("sg"), archived = true).status, "the role archived")
        assertEquals(ScheduleStatus.ACTIVE, h.archiveSchedule.run(ScheduleId("sg"), archived = false).status, "and restored")
        val stove = GroupMemberInput(AssetId("stove"), id = "g1-m2", sortOrder = 1)
        assertEquals(
            listOf(AssetId("stove")),
            h.saveGroup.run(GroupId("g1"), GroupCommand("Example group g1", members = listOf(stove))).openMembers()
                .map { it.assetId },
            "the member leaves",
        )
    }
}
