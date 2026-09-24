package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * `SaveAssetSettings`, the asset editor's one save (spec §10.4; master plan §10.1, plan decision 15):
 * four parts validated together against the final state and written in one transaction, or not at
 * all; the strands rule on the combined result; nothing written when nothing changed; a create that
 * writes its first activation. The generator is F3, the hot tub F5.
 */
class SaveAssetSettingsTest {

    private val h = ConditionHealthHarness(today = "2026-09-24")

    private fun settings(
        name: String,
        mode: SeasonMode = SeasonMode.YEAR_ROUND,
        window: Pair<String, String>? = null,
        manualPhase: SeasonPhase? = null,
        pause: Pair<String, String>? = null,
        aggregation: HealthAggregation = HealthAggregation.WORST,
        primary: String? = null,
    ) = AssetSettingsCommand(
        asset = AssetCommand(name = name),
        seasonMode = SeasonModeCommand(mode, window?.first, window?.second, manualPhase),
        maintenanceBreak = BreakCommand(pause?.first, pause?.second),
        healthPolicy = HealthPolicyCommand(aggregation, primary?.let(::HealthSubjectId)),
    )

    /** An asset with a boundary and a PRE_SERVICE schedule counting back from it. */
    private fun withPreService(
        id: String,
        mode: SeasonMode,
        window: Pair<String, String>? = null,
        pause: Pair<String, String>? = null,
    ): Asset {
        val asset = h.asset(
            id, name = "Generator $id", mode = mode, seasonStart = window?.first, seasonEnd = window?.second,
            breakStart = pause?.first, breakEnd = pause?.second,
        )
        h.schedule("s-$id", assetId = id, policy = ServicePolicy.PRE_SERVICE, offset = -14, title = "Pre-season service $id")
        return asset
    }

    /**
     * Hazard: the save is partial. A strands refusal from the break leaves the asset's name, its mode
     * and its policy exactly as they were — nothing is written; the same save with the break kept
     * writes all four parts in one asset row, its activation and the recompute.
     */
    @Test
    fun allFourPartsWriteInOneTransactionOrNone() = runBlocking<Unit> {
        val before = withPreService("a1", SeasonMode.YEAR_ROUND, pause = "06-01" to "06-30")
        h.subject("h1")

        val refused = assertFailsWith<BreakStrandsPolicy> {
            h.saveAssetSettings.run(
                AssetId("a1"),
                settings(
                    "Standby generator", SeasonMode.MANUAL, manualPhase = SeasonPhase.IN_SEASON,
                    aggregation = HealthAggregation.TRACK_ONE, primary = "h1",
                ),
            )
        }
        assertEquals(listOf(ScheduleId("s-a1")), refused.schedules.map { it.id })
        assertEquals(before, h.stored("a1"), "not the name, not the mode, not the policy")
        assertEquals(0, h.assets.upserts)
        assertTrue(h.activations.rows.isEmpty())
        assertTrue(h.states.rows.isEmpty(), "no recompute")

        val saved = h.saveAssetSettings.run(
            AssetId("a1"),
            settings(
                "Standby generator", SeasonMode.MANUAL, manualPhase = SeasonPhase.IN_SEASON, pause = "06-01" to "06-30",
                aggregation = HealthAggregation.TRACK_ONE, primary = "h1",
            ),
        )
        val expected = before.copy(
            name = "Standby generator", seasonMode = SeasonMode.MANUAL, healthAggregation = HealthAggregation.TRACK_ONE,
            healthPrimarySubjectId = HealthSubjectId("h1"), updatedAt = h.now,
        )
        assertEquals(expected, saved)
        assertEquals(expected, h.stored("a1"))
        assertEquals(1, h.assets.upserts, "one asset row for all four parts")
        assertEquals(listOf(SeasonAction.START to "2026-09-24"), h.activations.rows.values.map { it.action to it.occurredOn })
        assertTrue(h.states.rows.containsKey("s-a1"), "the season changed, so the schedules were rebuilt")
    }

    /**
     * Hazard: the strands rule reads one part at a time. It compares the boundary kind before the save
     * with the kind after the mode **and** the break both apply: removing the break alone strands,
     * leaving a calendar season for a break re-kinds and strands, entering one from a break strands, and
     * adding a boundary where there was none is a repair (plan decision 44).
     */
    @Test
    fun strandsIsJudgedOnTheCombinedResult() = runBlocking<Unit> {
        withPreService("a2", SeasonMode.YEAR_ROUND, pause = "06-01" to "06-30")
        val breakGone = assertFailsWith<BreakStrandsPolicy> { h.saveAssetSettings.run(AssetId("a2"), settings("Generator a2")) }
        assertEquals(listOf(ScheduleId("s-a2")), breakGone.schedules.map { it.id })

        withPreService("a3", SeasonMode.CALENDAR, window = "04-15" to "10-31")
        assertFailsWith<SeasonModeStrandsPolicy>("leaving the calendar for a break re-kinds the boundary") {
            h.saveAssetSettings.run(AssetId("a3"), settings("Generator a3", pause = "12-01" to "12-31"))
        }

        withPreService("a4", SeasonMode.YEAR_ROUND, pause = "06-01" to "06-30")
        assertFailsWith<SeasonModeStrandsPolicy>("entering the calendar from a break re-kinds it") {
            h.saveAssetSettings.run(AssetId("a4"), settings("Generator a4", SeasonMode.CALENDAR, window = "04-15" to "10-31"))
        }

        withPreService("a5", SeasonMode.YEAR_ROUND)
        val repaired = h.saveAssetSettings.run(
            AssetId("a5"),
            settings("Generator a5", SeasonMode.CALENDAR, window = "04-15" to "10-31", pause = "07-01" to "07-10"),
        )
        assertEquals(SeasonMode.CALENDAR, repaired.seasonMode)
        assertEquals("07-01" to "07-10", repaired.blackoutStartMmdd to repaired.blackoutEndMmdd)

        val kept = h.saveAssetSettings.run(
            AssetId("a2"),
            settings("Generator a2", SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON, pause = "06-10" to "06-30"),
        )
        assertEquals(SeasonMode.MANUAL, kept.seasonMode, "the break is still the boundary: nothing is stranded")
    }

    /**
     * Hazard: a no-op save still writes. The editor sends every part on every Save, so a save equal to
     * the stored row — a MANUAL asset included, with no phase to state — writes no asset row, no
     * activation, and recomputes nothing.
     */
    @Test
    fun anUnchangedSaveWritesNothing() = runBlocking<Unit> {
        val yearRound = h.asset("a1", name = "Generator", breakStart = "06-01", breakEnd = "06-30")
        h.schedule("s1")
        val manual = h.asset("a2", name = "Hot tub", mode = SeasonMode.MANUAL)

        assertEquals(yearRound, h.saveAssetSettings.run(AssetId("a1"), settings("Generator", pause = "06-01" to "06-30")))
        assertEquals(manual, h.saveAssetSettings.run(AssetId("a2"), settings(" Hot tub ", SeasonMode.MANUAL)))

        assertEquals(0, h.assets.upserts)
        assertTrue(h.activations.rows.isEmpty())
        assertTrue(h.states.rows.isEmpty())
        assertEquals(yearRound, h.stored("a1"))
    }

    /**
     * `id == null` creates the asset in the same transaction as its first activation and its template:
     * a MANUAL create writes exactly one row for the phase it states, and a create that states none is
     * refused with nothing written — no asset, no row, no seeded quick action.
     */
    @Test
    fun aCreateWithManualWritesItsFirstRow() = runBlocking<Unit> {
        assertEquals(
            listOf(SeasonProblem.ManualPhaseRequired),
            assertFailsWith<SeasonValidation> {
                h.saveAssetSettings.run(null, settings("Hot tub", SeasonMode.MANUAL), templateKey = "hot_tub")
            }.problems,
        )
        assertEquals(
            listOf(HealthProblem.PrimaryInvalid),
            assertFailsWith<HealthValidation> {
                h.saveAssetSettings.run(null, settings("Hot tub", aggregation = HealthAggregation.TRACK_ONE))
            }.problems,
            "a new asset has no subject to follow",
        )
        assertTrue(h.assets.rows.isEmpty() && h.activations.rows.isEmpty() && h.profiles.rows.isEmpty())

        val created = h.saveAssetSettings.run(
            null, settings("Hot tub", SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON), templateKey = "hot_tub",
        )
        assertEquals(created, h.stored(created.id.value))
        assertEquals(SeasonMode.MANUAL to null, created.seasonMode to created.seasonStartMmdd)
        assertEquals(h.now to h.now, created.createdAt to created.updatedAt)
        assertEquals(
            listOf(Triple(created.id, SeasonAction.END, "2026-09-24")),
            h.activations.rows.values.map { Triple(it.assetId, it.action, it.occurredOn) },
            "exactly one row, the phase it was created in",
        )
        assertTrue(h.profiles.rows.values.any { it.assetId == created.id }, "the template seeded its quick actions")
    }
}
