package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.core.schedule.statusOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * The asset command's `MM-DD` pair as a 1.3 compatibility input (spec §3.2, §9.3; inv. 88, 128): an
 * equal pair leaves the season alone, a different one is translated through the season-mode rules —
 * or refused on a MANUAL asset — and a create reads it as the season. Today is 2026-06-10.
 */
class LegacyAssetPairTest {

    private val a1 = AssetId("a1")

    /** A MANUAL asset's null pair round-trips; so does a CALENDAR asset's own window. */
    @Test
    fun anEqualPairLeavesTheSeasonAlone() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.MANUAL, breakStart = "12-01", breakEnd = "02-28")
        h.activation("act-1", SeasonAction.START, "2026-05-01")
        h.asset(id = "c1", mode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31", breakStart = "07-01", breakEnd = "07-10")

        val manual = h.updateAsset.run(a1, AssetCommand(name = "Renamed"))
        assertEquals("Renamed", manual.name)
        assertEquals(SeasonMode.MANUAL, manual.seasonMode)
        assertEquals(null to null, manual.seasonStartMmdd to manual.seasonEndMmdd)
        assertEquals("12-01" to "02-28", manual.blackoutStartMmdd to manual.blackoutEndMmdd)
        assertEquals(listOf("act-1"), h.rows().map { it.id })

        val calendar = h.updateAsset.run(AssetId("c1"), AssetCommand(name = "Mower", seasonStartMmdd = "04-15", seasonEndMmdd = "10-31"))
        assertEquals(SeasonMode.CALENDAR, calendar.seasonMode)
        assertEquals("07-01" to "07-10", calendar.blackoutStartMmdd to calendar.blackoutEndMmdd)
    }

    @Test
    fun aDifferentPairOnManualIs422AndWritesNothing() = runTest {
        val h = SeasonCommandHarness()
        val before = h.asset(mode = SeasonMode.MANUAL)
        h.activation("act-1", SeasonAction.START, "2026-05-01")

        val refusal = assertFailsWith<LegacyWriteCannotRepresent> {
            h.updateAsset.run(a1, AssetCommand(name = "Renamed", seasonStartMmdd = "04-15", seasonEndMmdd = "10-31"))
        }
        assertEquals(a1, refusal.assetId)
        assertEquals(before, h.stored(), "not even the name is written")
        assertEquals(0, h.assets.upserts)
        assertEquals(listOf("act-1"), h.rows().map { it.id })
    }

    @Test
    fun aDifferentPairBecomesCalendarOrYearRound() = runTest {
        val h = SeasonCommandHarness()
        h.asset(breakStart = "12-01", breakEnd = "02-28")
        h.schedule("s1", policy = ServicePolicy.IN_SERVICE_AT_START)
        h.recompute.all()
        assertEquals(PolicyPhase.ACTIVE, h.state("s1")!!.policyPhase)

        val calendar = h.updateAsset.run(a1, AssetCommand(name = "Snowblower", seasonStartMmdd = "11-15", seasonEndMmdd = "03-31"))
        assertEquals(SeasonMode.CALENDAR, calendar.seasonMode)
        assertEquals("11-15" to "03-31", calendar.seasonStartMmdd to calendar.seasonEndMmdd)
        assertEquals("Snowblower", calendar.name, "the asset's other fields in the same write")
        assertEquals("12-01" to "02-28", calendar.blackoutStartMmdd to calendar.blackoutEndMmdd, "the break is never reset")
        assertEquals(calendar, h.stored())
        assertEquals(PolicyPhase.DORMANT, h.state("s1")!!.policyPhase, "recomputed: out of season in June")

        val yearRound = h.updateAsset.run(a1, AssetCommand(name = "Snowblower"))
        assertEquals(SeasonMode.YEAR_ROUND, yearRound.seasonMode)
        assertEquals(null to null, yearRound.seasonStartMmdd to yearRound.seasonEndMmdd)
        assertEquals("12-01" to "02-28", yearRound.blackoutStartMmdd to yearRound.blackoutEndMmdd)
        assertEquals(PolicyPhase.ACTIVE, h.state("s1")!!.policyPhase)
    }

    /** A pair that moves only its end is a different pair: translated, stored and recomputed. */
    @Test
    fun anEndOnlyPairChangeIsTranslated() = runTest {
        val h = SeasonCommandHarness()
        h.asset(mode = SeasonMode.CALENDAR, seasonStart = "04-15", seasonEnd = "10-31")
        h.schedule("s1", policy = ServicePolicy.IN_SERVICE_AT_START)
        h.recompute.all()
        assertEquals(PolicyPhase.ACTIVE, h.state("s1")!!.policyPhase)

        val saved = h.updateAsset.run(a1, AssetCommand(name = "Mower", seasonStartMmdd = "04-15", seasonEndMmdd = "06-05"))

        assertEquals(SeasonMode.CALENDAR, saved.seasonMode)
        assertEquals("04-15" to "06-05", saved.seasonStartMmdd to saved.seasonEndMmdd)
        assertEquals(saved, h.stored())
        assertEquals(PolicyPhase.DORMANT, h.state("s1")!!.policyPhase, "recomputed: the season ended on 5 June")
    }

    /** Inv. 128: the legacy pair obeys the strands rule exactly as the season-mode command does. */
    @Test
    fun thePairObeysTheStrandsRule() = runTest {
        val h = SeasonCommandHarness()
        val calendar = h.asset(mode = SeasonMode.CALENDAR, seasonStart = "11-15", seasonEnd = "03-31")
        h.schedule("s1", title = "Pre-season service")
        val refusal = assertFailsWith<SeasonModeStrandsPolicy> { h.updateAsset.run(a1, AssetCommand(name = "Renamed")) }
        assertEquals(listOf("Pre-season service"), refusal.schedules.map { it.title })
        assertEquals(calendar, h.stored(), "nothing written")

        // A break-only boundary given a pair: BREAK -> SEASON, refused.
        val breakOnly = h.asset(id = "b1", breakStart = "12-01", breakEnd = "02-28")
        h.schedule("s2", assetId = "b1")
        assertFailsWith<SeasonModeStrandsPolicy> {
            h.updateAsset.run(AssetId("b1"), AssetCommand(name = "Asset b1", seasonStartMmdd = "04-15", seasonEndMmdd = "10-31"))
        }
        assertEquals(breakOnly, h.stored("b1"))

        // No boundary at all given a pair: a repair, allowed (dec. 44). A new window keeps the kind.
        h.asset(id = "n1")
        h.schedule("s3", assetId = "n1")
        assertEquals(
            SeasonMode.CALENDAR,
            h.updateAsset.run(AssetId("n1"), AssetCommand(name = "Asset n1", seasonStartMmdd = "04-15", seasonEndMmdd = "10-31")).seasonMode,
        )
        assertEquals(
            "11-01" to "03-15",
            h.updateAsset.run(a1, AssetCommand(name = "Asset a1", seasonStartMmdd = "11-01", seasonEndMmdd = "03-15"))
                .let { it.seasonStartMmdd to it.seasonEndMmdd },
        )
        assertEquals(0, h.rows().size + h.rows("b1").size + h.rows("n1").size)
    }

    /**
     * B01's carry-forward (M4): creating an asset — or a component — with a window makes it CALENDAR, and
     * its non-CONTINUOUS schedules read INACTIVE_SEASON out of season; without one it is YEAR_ROUND.
     */
    @Test
    fun creatingAnAssetWithAWindowTakesCalendar() = runTest {
        val h = SeasonCommandHarness()

        val seasonal = h.createAsset.run(AssetCommand(name = "Snowblower", seasonStartMmdd = "11-15", seasonEndMmdd = "03-31"))
        assertEquals(SeasonMode.CALENDAR, seasonal.seasonMode)
        assertEquals(seasonal, h.stored(seasonal.id.value))
        val component = h.createAsset.run(
            AssetCommand(name = "Auger", parentAssetId = seasonal.id, seasonStartMmdd = "11-15", seasonEndMmdd = "03-31"),
        )
        assertEquals(SeasonMode.CALENDAR, component.seasonMode)
        assertEquals(SeasonMode.YEAR_ROUND, h.createAsset.run(AssetCommand(name = "Pump")).seasonMode)

        val schedule = h.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = seasonal.id, targetGroupId = null, title = "Engine oil", timeInterval = 1,
                timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-05-01",
                servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0,
                providers = listOf(ScheduleProviderRow("LOCAL", enabled = true)),
            ),
        )
        assertEquals(DueStatus.INACTIVE_SEASON, statusOf(schedule, h.state(schedule.id.value)!!, h.today))
    }
}
