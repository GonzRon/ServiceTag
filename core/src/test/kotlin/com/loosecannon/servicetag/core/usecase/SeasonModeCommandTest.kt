package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.testing.closureOf
import com.loosecannon.servicetag.core.testing.completionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/**
 * `SetSeasonMode` (spec §3.2, §3.4; master plan §7.2): the window rules, `manualPhase`, the one row a
 * switch into MANUAL writes, the strands rule both ways, the unchanged save, and what a season write
 * may and may not touch. Today is 2026-06-10 throughout.
 */
class SeasonModeCommandTest {

    private val a1 = AssetId("a1")

    private suspend fun SeasonCommandHarness.refused(cmd: SeasonModeCommand, id: AssetId = a1): List<SeasonProblem> =
        assertFailsWith<SeasonValidation> { setSeasonMode.run(id, cmd) }.problems

    @Test
    fun calendarNeedsAWindowAndOtherModesForbidOne() = runTest {
        val h = SeasonCommandHarness()
        val before = h.asset()

        assertEquals(listOf(SeasonProblem.SeasonWindowRequired), h.refused(SeasonModeCommand(SeasonMode.CALENDAR)))
        assertEquals(
            listOf(SeasonProblem.SeasonWindowRequired),
            h.refused(SeasonModeCommand(SeasonMode.CALENDAR, seasonStartMmdd = "04-15")),
        )
        assertEquals(
            listOf(SeasonProblem.BadDate("seasonEndMmdd")),
            h.refused(SeasonModeCommand(SeasonMode.CALENDAR, "04-15", "10-32")),
            "a malformed bound keeps the shipped BadDate shape",
        )
        assertEquals(
            listOf(SeasonProblem.SeasonWindowForbidden),
            h.refused(SeasonModeCommand(SeasonMode.YEAR_ROUND, "04-15", "10-31")),
        )
        assertEquals(
            listOf(SeasonProblem.SeasonWindowForbidden),
            h.refused(SeasonModeCommand(SeasonMode.YEAR_ROUND, seasonEndMmdd = "10-31")),
        )
        assertEquals(
            listOf(SeasonProblem.SeasonWindowForbidden),
            h.refused(SeasonModeCommand(SeasonMode.MANUAL, "04-15", "10-31", manualPhase = SeasonPhase.IN_SEASON)),
        )
        assertEquals(before, h.stored(), "a refusal writes nothing")
        assertEquals(0, h.assets.upserts)
        assertEquals(emptyList(), h.rows())

        val saved = h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.CALENDAR, " 04-15 ", "10-31"))
        assertEquals(SeasonMode.CALENDAR, saved.seasonMode)
        assertEquals("04-15" to "10-31", saved.seasonStartMmdd to saved.seasonEndMmdd)
        assertEquals(saved, h.stored())
    }

    @Test
    fun manualPhaseIsRequiredOnlyOnASwitchIntoManual() = runTest {
        val h = SeasonCommandHarness()
        h.asset()
        h.asset(id = "m1", mode = SeasonMode.MANUAL)
        h.activation("act-1", SeasonAction.START, "2026-03-01", assetId = "m1")

        assertEquals(listOf(SeasonProblem.ManualPhaseRequired), h.refused(SeasonModeCommand(SeasonMode.MANUAL)))
        assertEquals(
            listOf(SeasonProblem.ManualPhaseForbidden),
            h.refused(SeasonModeCommand(SeasonMode.CALENDAR, "04-15", "10-31", manualPhase = SeasonPhase.IN_SEASON)),
        )
        assertEquals(
            listOf(SeasonProblem.ManualPhaseForbidden),
            h.refused(SeasonModeCommand(SeasonMode.YEAR_ROUND, manualPhase = SeasonPhase.OUT_OF_SEASON)),
        )
        assertEquals(
            listOf(SeasonProblem.ManualPhaseForbidden),
            h.refused(SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON), AssetId("m1")),
            "MANUAL -> MANUAL states no phase: the history already does",
        )
        assertEquals(
            listOf(SeasonProblem.ManualPhaseForbidden),
            h.refused(SeasonModeCommand(SeasonMode.YEAR_ROUND, manualPhase = SeasonPhase.OUT_OF_SEASON), AssetId("m1")),
        )
        assertEquals(0, h.assets.upserts)
        assertEquals(listOf("act-1"), h.rows("m1").map { it.id }, "no refusal writes a row")
    }

    /** END, END is valid history (spec §3.4): the switch writes its row even when the latest says the same. */
    @Test
    fun aSwitchIntoManualWritesExactlyOneRowToday() = runTest {
        val h = SeasonCommandHarness()
        h.asset()
        // Left behind by an earlier MANUAL spell: unread while the asset is YEAR_ROUND.
        h.activation("act-1", SeasonAction.START, "2026-01-03")
        h.activation("act-2", SeasonAction.END, "2026-04-16")

        val saved = h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON))

        assertEquals(SeasonMode.MANUAL, saved.seasonMode)
        val rows = h.rows()
        assertEquals(3, rows.size, "exactly one row")
        val written = rows.last()
        assertEquals(SeasonAction.END, written.action)
        assertEquals("2026-06-10", written.occurredOn, "dated today")
        assertEquals(h.now, written.createdAt)
        assertEquals(null, written.eventId)
        assertEquals(listOf(SeasonAction.END, SeasonAction.END), rows.takeLast(2).map { it.action })

        h.asset(id = "a2")
        h.setSeasonMode.run(AssetId("a2"), SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.IN_SEASON))
        assertEquals(listOf(SeasonAction.START to "2026-06-10"), h.rows("a2").map { it.action to it.occurredOn })
    }

    @Test
    fun leavingManualKeepsTheRows() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.MANUAL)
        h.activation("act-1", SeasonAction.START, "2026-01-03")
        h.activation("act-2", SeasonAction.END, "2026-04-16")
        h.activation("act-3", SeasonAction.START, "2026-05-10")
        val history = h.rows()

        assertEquals(SeasonMode.YEAR_ROUND, h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.YEAR_ROUND)).seasonMode)
        assertEquals(history, h.rows())

        h.asset(id = "m2", mode = SeasonMode.MANUAL)
        h.activation("m2-1", SeasonAction.START, "2026-02-01", assetId = "m2")
        h.setSeasonMode.run(AssetId("m2"), SeasonModeCommand(SeasonMode.CALENDAR, "04-15", "10-31"))
        assertEquals(listOf("m2-1"), h.rows("m2").map { it.id })
        assertEquals(history, h.rows(), "the other asset's history too")
    }

    /**
     * RS-4 / O-3 / dec. 44: a change that removes or re-kinds the boundary a PRE_SERVICE schedule
     * counts back from is a 409 naming the schedules — whatever their lifecycle — and writes nothing.
     */
    @Test
    fun reKindingAPreServiceBoundaryIsRefused() = runTest {
        val h = SeasonCommandHarness()

        suspend fun strands(cmd: SeasonModeCommand, id: String): List<String> {
            val asset = h.stored(id)
            val schedules = h.schedules.rows.toMap()
            val upserts = h.assets.upserts
            val refusal = assertFailsWith<SeasonModeStrandsPolicy> { h.setSeasonMode.run(AssetId(id), cmd) }
            assertEquals(AssetId(id), refusal.assetId)
            assertEquals(asset, h.stored(id), "the asset is untouched")
            assertEquals(upserts, h.assets.upserts)
            assertEquals(schedules, h.schedules.rows.toMap(), "no schedule is touched")
            assertEquals(emptyList(), h.rows(id), "no activation is written")
            return refusal.schedules.map { it.id.value + ":" + it.title }
        }

        // CALENDAR -> YEAR_ROUND and CALENDAR -> MANUAL: SEASON -> NONE.
        h.asset(id = "cal", mode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31")
        h.schedule("s-cal", assetId = "cal", title = "Pre-season service")
        h.schedule("s-cont", assetId = "cal", policy = ServicePolicy.CONTINUOUS, title = "Blade check")
        assertEquals(listOf("s-cal:Pre-season service"), strands(SeasonModeCommand(SeasonMode.YEAR_ROUND), "cal"))
        assertEquals(
            listOf("s-cal:Pre-season service"),
            strands(SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON), "cal"),
        )

        // CALENDAR with a break -> MANUAL: SEASON -> BREAK, a re-kind.
        h.asset(id = "calb", mode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31", breakStart = "12-20", breakEnd = "01-05")
        h.schedule("s-calb", assetId = "calb")
        assertEquals(
            listOf("s-calb:Schedule s-calb"),
            strands(SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.IN_SEASON), "calb"),
        )

        // A break-only boundary -> CALENDAR: BREAK -> SEASON.
        h.asset(id = "brk", breakStart = "12-01", breakEnd = "02-28")
        h.schedule("s-brk", assetId = "brk")
        assertEquals(listOf("s-brk:Schedule s-brk"), strands(SeasonModeCommand(SeasonMode.CALENDAR, "04-15", "10-31"), "brk"))

        // A paused and an archived PRE_SERVICE schedule count: either can come back.
        h.asset(id = "life", mode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31")
        h.schedule("s-paused", assetId = "life", status = ScheduleStatus.PAUSED, title = "B paused")
        h.schedule("s-archived", assetId = "life", status = ScheduleStatus.ARCHIVED, title = "A archived")
        assertEquals(
            listOf("s-archived:A archived", "s-paused:B paused"),
            strands(SeasonModeCommand(SeasonMode.YEAR_ROUND), "life"),
        )
        h.asset(id = "arch", mode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31")
        h.schedule("s-only-archived", assetId = "arch", status = ScheduleStatus.ARCHIVED)
        assertEquals(listOf("s-only-archived:Schedule s-only-archived"), strands(SeasonModeCommand(SeasonMode.YEAR_ROUND), "arch"))

        // A new CALENDAR window keeps the kind: allowed.
        val moved = h.setSeasonMode.run(AssetId("cal"), SeasonModeCommand(SeasonMode.CALENDAR, "11-01", "03-15"))
        assertEquals("11-01" to "03-15", moved.seasonStartMmdd to moved.seasonEndMmdd)
    }

    /** Dec. 44 (M11): adding a season where there was no boundary repairs a merged schedule. */
    @Test
    fun addingASeasonWhereNoneExistedIsAllowed() = runTest {
        val h = SeasonCommandHarness()
        h.asset()
        h.schedule("s-merged")

        val saved = h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.CALENDAR, "04-15", "10-31"))
        assertEquals(SeasonMode.CALENDAR, saved.seasonMode)
        assertEquals(saved, h.stored())

        h.asset(id = "m1", mode = SeasonMode.MANUAL)
        h.schedule("s-merged-2", assetId = "m1")
        assertEquals(
            SeasonMode.CALENDAR,
            h.setSeasonMode.run(AssetId("m1"), SeasonModeCommand(SeasonMode.CALENDAR, "04-15", "10-31")).seasonMode,
        )
    }

    /** The editor sends every settings command on every save; one that changes nothing writes nothing. */
    @Test
    fun anUnchangedModeWritesNothing() = runTest {
        val h = SeasonCommandHarness()
        val calendar = h.asset(mode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31", breakStart = "12-01", breakEnd = "02-28")
        val manual = h.asset(id = "m1", mode = SeasonMode.MANUAL)
        h.activation("act-1", SeasonAction.START, "2026-03-01", assetId = "m1")
        val yearRound = h.asset(id = "y1")
        h.now += 60_000L

        assertSame(calendar, h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.CALENDAR, "04-15", "10-31")))
        assertSame(manual, h.setSeasonMode.run(AssetId("m1"), SeasonModeCommand(SeasonMode.MANUAL)))
        assertSame(yearRound, h.setSeasonMode.run(AssetId("y1"), SeasonModeCommand(SeasonMode.YEAR_ROUND, "", " ")))
        assertSame(calendar, h.setMaintenanceBreak.run(a1, BreakCommand("12-01", "02-28")))
        assertSame(yearRound, h.setMaintenanceBreak.run(AssetId("y1"), BreakCommand(null, null)))

        assertEquals(0, h.assets.upserts)
        assertEquals(calendar.updatedAt, h.stored().updatedAt, "updatedAt byte-identical")
        assertEquals(manual.updatedAt, h.stored("m1").updatedAt)
        assertEquals(listOf("act-1"), h.rows("m1").map { it.id }, "no row")
    }

    /** Inv. 86: a season or break write writes no event, no closure and no schedule column. */
    @Test
    fun seasonAndBreakWritesTouchNoEventClosureOrScheduleColumn() = runTest {
        val h = SeasonCommandHarness()
        h.asset()
        h.schedule("s1", policy = ServicePolicy.IN_SERVICE_AT_START, postponedDueOn = "2026-07-01")
        h.schedule("s2", policy = ServicePolicy.CONTINUOUS)
        h.events.rows["e1"] = completionOf("e1", occurredOn = "2026-02-15", occurrenceOn = "2026-02-15", scheduleId = "s1")
        h.closures.rows["c1"] = closureOf("c1", occurrenceOn = "2026-01-15", closedOn = "2026-01-20", scheduleId = "s2")
        val schedules = h.schedules.rows.toMap()
        val events = h.events.rows.toMap()
        val closures = h.closures.rows.toMap()

        h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.CALENDAR, "04-15", "10-31"))
        h.setMaintenanceBreak.run(a1, BreakCommand("12-01", "02-28"))
        h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.IN_SEASON))
        h.setMaintenanceBreak.run(a1, BreakCommand(null, null))
        h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.YEAR_ROUND))

        assertEquals(schedules, h.schedules.rows.toMap(), "every schedule column, the postponement included")
        assertEquals(events, h.events.rows.toMap())
        assertEquals(closures, h.closures.rows.toMap())
        assertEquals(listOf(EventId("e1")), h.events.rows.values.map { it.id })
        assertEquals(1, h.rows().size, "the switch into MANUAL wrote its one row and nothing else did")
    }

    /** 1.3's asset edit never rebuilt; every season and break write does. */
    @Test
    fun aSeasonOrBreakChangeRecomputesTheAssetsSchedules() = runTest {
        val h = SeasonCommandHarness()
        h.asset()
        h.schedule("s1", policy = ServicePolicy.IN_SERVICE_AT_START)
        h.asset(id = "a2")
        h.schedule("s2", assetId = "a2", policy = ServicePolicy.IN_SERVICE_AT_START)
        h.recompute.all()
        assertEquals(PolicyPhase.ACTIVE, h.state("s1")!!.policyPhase)
        assertEquals(false, h.state("s2")!!.quiet)

        // Out of season on 10 June: the schedule goes dormant with no other write.
        h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.CALENDAR, "09-01", "03-31"))
        assertEquals(PolicyPhase.DORMANT, h.state("s1")!!.policyPhase)

        // A break around today: the in-service schedule is quiet.
        h.setMaintenanceBreak.run(AssetId("a2"), BreakCommand("06-01", "06-30"))
        assertEquals(true, h.state("s2")!!.quiet)

        // And back: YEAR_ROUND again, active again.
        h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.YEAR_ROUND))
        assertEquals(PolicyPhase.ACTIVE, h.state("s1")!!.policyPhase)
        assertEquals(ScheduleId("s1"), h.state("s1")!!.scheduleId)
    }
}
