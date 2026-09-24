package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.testing.completionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * `RecordSeasonActivation` (spec §3.3; master plan §7.2; inv. 91, 126): the refusals, the one row it
 * writes, and the asset row it never touches. Today is 2026-06-10 throughout.
 */
class ManualSeasonTest {

    private val a1 = AssetId("a1")

    private suspend fun SeasonCommandHarness.dateRefused(cmd: ActivationCommand): List<SeasonProblem> =
        assertFailsWith<SeasonValidation> { recordSeasonActivation.run(a1, cmd) }.problems

    /** Compared with the **latest** row, never the first: a START after START, END, START is refused. */
    @Test
    fun repeatedStartOrEndIsRefusedAndWritesNothing() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.MANUAL)
        h.activation("act-1", SeasonAction.START, "2026-03-01")
        h.activation("act-2", SeasonAction.END, "2026-05-01")

        assertFailsWith<SeasonAlreadyEnded> { h.recordSeasonActivation.run(a1, ActivationCommand(SeasonAction.END)) }
        assertEquals(listOf("act-1", "act-2"), h.rows().map { it.id }, "a refusal writes nothing")

        val started = h.recordSeasonActivation.run(a1, ActivationCommand(SeasonAction.START))
        assertEquals(SeasonAction.START, started.action)
        assertFailsWith<SeasonAlreadyStarted> { h.recordSeasonActivation.run(a1, ActivationCommand(SeasonAction.START)) }
        assertEquals(3, h.rows().size)

        // No row at all reads OUT_OF_SEASON (plan decision 32): END is already ended, START is allowed.
        h.asset(id = "m2", mode = SeasonMode.MANUAL)
        assertFailsWith<SeasonAlreadyEnded> { h.recordSeasonActivation.run(AssetId("m2"), ActivationCommand(SeasonAction.END)) }
        assertEquals(emptyList(), h.rows("m2"))
        h.recordSeasonActivation.run(AssetId("m2"), ActivationCommand(SeasonAction.START))
        assertEquals(listOf(SeasonAction.START), h.rows("m2").map { it.action })
        assertEquals(0, h.assets.upserts)
    }

    @Test
    fun aFutureDateOrOneBeforeTheLatestRowIsRefused() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.MANUAL)
        h.activation("act-1", SeasonAction.START, "2026-03-01")
        h.activation("act-2", SeasonAction.END, "2026-05-01")

        assertEquals(
            listOf(SeasonProblem.SeasonDateOutOfRange),
            h.dateRefused(ActivationCommand(SeasonAction.START, occurredOn = "2026-06-11")),
            "after today",
        )
        assertEquals(
            listOf(SeasonProblem.SeasonDateOutOfRange),
            h.dateRefused(ActivationCommand(SeasonAction.START, occurredOn = "2026-04-30")),
            "before the latest row's date, though after the first row's",
        )
        assertEquals(
            listOf(SeasonProblem.BadDate("occurredOn")),
            h.dateRefused(ActivationCommand(SeasonAction.START, occurredOn = "2026-13-01")),
        )
        assertEquals(listOf("act-1", "act-2"), h.rows().map { it.id })

        val onTheLatestDay = h.recordSeasonActivation.run(a1, ActivationCommand(SeasonAction.START, occurredOn = "2026-05-01"))
        assertEquals("2026-05-01", onTheLatestDay.occurredOn, "equal to the latest row's date is allowed")
        val byDefault = h.recordSeasonActivation.run(a1, ActivationCommand(SeasonAction.END))
        assertEquals("2026-06-10", byDefault.occurredOn, "occurredOn defaults to today")
    }

    @Test
    fun anAssetNotManualIsRefused() = runTest {
        val h = SeasonCommandHarness()
        h.asset()
        h.asset(id = "c1", mode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31")
        // History left from an earlier MANUAL spell changes nothing: the asset is not MANUAL now.
        h.activation("act-1", SeasonAction.END, "2026-01-10")

        assertFailsWith<SeasonNotManual> { h.recordSeasonActivation.run(a1, ActivationCommand(SeasonAction.START)) }
        assertFailsWith<SeasonNotManual> { h.recordSeasonActivation.run(AssetId("c1"), ActivationCommand(SeasonAction.END)) }
        // The body's 422 comes before the asset's 409 (spec §9.2's tie-break): a future date is fixed first.
        assertEquals(
            listOf(SeasonProblem.SeasonDateOutOfRange),
            h.dateRefused(ActivationCommand(SeasonAction.START, occurredOn = "2026-06-11")),
        )
        assertEquals(listOf("act-1"), h.rows().map { it.id })
        assertEquals(emptyList(), h.rows("c1"))
    }

    @Test
    fun aForeignEventIsRefused() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.MANUAL)
        h.asset(id = "a2")
        h.events.rows["e-own"] = completionOf("e-own", occurredOn = "2026-06-09", occurrenceOn = null, assetId = "a1")
        h.events.rows["e-other"] = completionOf("e-other", occurredOn = "2026-06-09", occurrenceOn = null, assetId = "a2")

        assertEquals(
            listOf(SeasonProblem.ForeignEvent(EventId("e-other"))),
            h.dateRefused(ActivationCommand(SeasonAction.START, eventId = EventId("e-other"))),
        )
        assertEquals(
            listOf(SeasonProblem.ForeignEvent(EventId("ghost"))),
            h.dateRefused(ActivationCommand(SeasonAction.START, eventId = EventId("ghost"))),
        )
        assertEquals(emptyList(), h.rows())

        val linked = h.recordSeasonActivation.run(a1, ActivationCommand(SeasonAction.START, eventId = EventId("e-own")))
        assertEquals(EventId("e-own"), linked.eventId)
    }

    /** Inv. 91: an END on the day of a START is allowed, and the later-created row decides the phase. */
    @Test
    fun aSameDayEndAfterStartWins() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.MANUAL)

        h.recordSeasonActivation.run(a1, ActivationCommand(SeasonAction.START))
        assertEquals(SeasonPhase.IN_SEASON, h.getAssetSeason.run(a1).phase)
        h.now += 1_000L
        h.recordSeasonActivation.run(a1, ActivationCommand(SeasonAction.END))

        val view = h.getAssetSeason.run(a1)
        assertEquals(SeasonPhase.OUT_OF_SEASON, view.phase)
        assertEquals(
            listOf(SeasonAction.START to "2026-06-10", SeasonAction.END to "2026-06-10"),
            view.activations.map { it.action to it.occurredOn },
        )
    }

    /** Inv. 126: one row, and no asset column — not even `updatedAt`; then the recompute. */
    @Test
    fun anActivationWritesOneRowAndNoAssetColumn() = runTest {
        val h = SeasonCommandHarness()
        val before = h.asset(mode = SeasonMode.MANUAL)
        h.schedule("s1", policy = ServicePolicy.IN_SERVICE_AT_START)
        h.recompute.all()
        assertEquals(PolicyPhase.DORMANT, h.state("s1")!!.policyPhase, "no row reads OUT_OF_SEASON")
        h.now += 60_000L

        val row = h.recordSeasonActivation.run(a1, ActivationCommand(SeasonAction.START))

        assertEquals(listOf(row), h.rows())
        assertEquals(h.now, row.createdAt)
        assertEquals(0, h.assets.upserts, "no asset write at all")
        assertEquals(before, h.stored(), "the asset row is byte-identical")
        assertEquals(PolicyPhase.ACTIVE, h.state("s1")!!.policyPhase, "the asset's schedules were rebuilt")
    }

    /**
     * Inv. 126's production half, for every season-side command: a START or END leaves the asset row
     * byte-identical, `updated_at` included; a season-mode or break write changes only the columns it
     * owns (and `updated_at`); a policy write — a schedule save — changes nothing on the asset.
     */
    @Test
    fun seasonCommandsChangeOnlyTheAssetColumnsTheyOwn() = runTest {
        val h = SeasonCommandHarness()
        var before = h.asset().copy(category = "Outdoor", notes = "Kept", location = "Rack B")
        h.assets.rows[before.id.value] = before

        h.now += 1_000L
        var after = h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.CALENDAR, "04-15", "10-31"))
        assertEquals(
            before,
            after.copy(
                seasonMode = before.seasonMode, seasonStartMmdd = before.seasonStartMmdd,
                seasonEndMmdd = before.seasonEndMmdd, updatedAt = before.updatedAt,
            ),
            "a season-mode write owns the mode and the window",
        )
        before = after

        h.now += 1_000L
        after = h.setMaintenanceBreak.run(a1, BreakCommand("12-01", "02-28"))
        assertEquals(
            before,
            after.copy(blackoutStartMmdd = before.blackoutStartMmdd, blackoutEndMmdd = before.blackoutEndMmdd, updatedAt = before.updatedAt),
            "a break write owns the break",
        )
        before = after

        h.now += 1_000L
        h.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = a1, targetGroupId = null, title = "Oil", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
                anchorOn = "2026-04-01", servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
                providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
            ),
        )
        assertEquals(before, h.stored(), "a policy write owns no asset column")

        h.now += 1_000L
        before = h.setSeasonMode.run(a1, SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON))
        for (action in listOf(SeasonAction.START, SeasonAction.END)) {
            h.now += 1_000L
            h.recordSeasonActivation.run(a1, ActivationCommand(action))
            assertEquals(before, h.stored(), "a $action owns no asset column, updatedAt included")
        }
    }
}
