package com.loosecannon.servicetag.core.season

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.PolicyReason
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.testing.SeasonFixtures.activationOf
import com.loosecannon.servicetag.core.testing.SeasonFixtures.assetOf
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.usecase.ScheduleSeasonHarness
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * #114 (G22, I30): seasonality is configured per asset. Fictional stories 2 and 3 and their neighbours (rows
 * 102–104, 106): a winter hot tub, a summer pool and `MANUAL` appliances, each with its own role schedule, whose
 * seasons, cutoffs and readiness never touch one another and never depend on a name, a category or a title.
 */
class SeasonStoriesTest {

    private fun on(date: String): LocalDate = LocalDate.parse(date)

    private fun roleSchedule(id: String, assetId: String, since: String, title: String = "Opening service"): MaintenanceSchedule =
        scheduleOf(id = id, assetId = assetId, title = title).copy(role = ScheduleRole.SEASON_START, roleSinceOn = since)

    private fun service(day: String, scheduleId: String, assetId: String): AssetEvent =
        completionOf(id = "e-$scheduleId-$day", occurredOn = day, occurrenceOn = day, assetId = assetId, scheduleId = scheduleId)

    private fun end(day: String, assetId: String): SeasonActivation = activationOf("end-$assetId-$day", assetId, SeasonAction.END, day)
    private fun start(day: String, assetId: String): SeasonActivation = activationOf("start-$assetId-$day", assetId, SeasonAction.START, day)

    private fun window(cutoff: String) = ReadinessBoundary(on(cutoff), BoundarySource.CALENDAR_WINDOW)

    // ---------------------------------------------------------------------------------------------
    // Row 102 — story 2: a winter hot tub, CALENDAR 11-01…03-31, opened in October.
    // ---------------------------------------------------------------------------------------------

    private val hotTub: Asset = assetOf("tub", "Example Hot Tub", SeasonMode.CALENDAR, seasonStart = "11-01", seasonEnd = "03-31")
    private val opening = roleSchedule("s-open", "tub", since = "2026-03-15")
    private val closing = scheduleOf(id = "s-close", assetId = "tub", title = "Closing service")

    private fun tubOn(today: String, events: List<AssetEvent>): Readiness = seasonReadiness(
        hotTub, listOf(hotTub), listOf(opening, closing), emptyList(), events, emptyList(), on(today), ZoneOffset.UTC,
    )

    @Test
    fun aWinterHotTubStaysReadyAcrossNewYearAndNotPastItsSeason() {
        val openingService = service("2026-10-20", "s-open", "tub")
        for (today in listOf("2026-10-20", "2026-11-01", "2027-01-15", "2027-03-31")) {
            assertEquals(
                Readiness.Ready(on("2026-10-20"), ScheduleId("s-open"), window("2026-04-01")),
                tubOn(today, listOf(openingService)),
                "on $today",
            )
        }
        assertEquals(
            Readiness.NotReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, ScheduleId("s-open"), window("2027-04-01")),
            tubOn("2027-04-01", listOf(openingService)),
        )
        // The closing schedule's completion is not the opening service, whatever its date.
        assertEquals(
            Readiness.NotReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, ScheduleId("s-open"), window("2027-04-01")),
            tubOn("2027-04-05", listOf(openingService, service("2027-04-05", "s-close", "tub"))),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 103 — story 3: a summer pool, CALENDAR 06-01…08-31.
    // ---------------------------------------------------------------------------------------------

    private val pool: Asset = assetOf("pool", "Example Pool", SeasonMode.CALENDAR, seasonStart = "06-01", seasonEnd = "08-31")
    private val poolOpening = roleSchedule("s-pool", "pool", since = "2025-06-01")

    private fun poolOn(today: String, events: List<AssetEvent>): Readiness = seasonReadiness(
        pool, listOf(pool), listOf(poolOpening), emptyList(), events, emptyList(), on(today), ZoneOffset.UTC,
    )

    @Test
    fun aSummerPoolCountsFromTheFirstDayAfterItsLastSeason() {
        val may2026 = service("2026-05-20", "s-pool", "pool")
        assertEquals(Readiness.Ready(on("2026-05-20"), ScheduleId("s-pool"), window("2025-09-01")), poolOn("2026-05-20", listOf(may2026)))
        assertEquals(Readiness.Ready(on("2026-05-20"), ScheduleId("s-pool"), window("2025-09-01")), poolOn("2026-08-31", listOf(may2026)))
        assertEquals(
            Readiness.NotReady(NotReadyReason.NOT_DONE_SINCE_SEASON_END, ScheduleId("s-pool"), window("2026-09-01")),
            poolOn("2026-09-01", listOf(may2026)),
        )
        val may2027 = service("2027-05-15", "s-pool", "pool")
        assertEquals(
            Readiness.Ready(on("2027-05-15"), ScheduleId("s-pool"), window("2026-09-01")),
            poolOn("2027-05-15", listOf(may2026, may2027)),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Row 104 — independent assets, all passed together.
    // ---------------------------------------------------------------------------------------------

    private data class World(
        val assets: List<Asset>,
        val schedules: List<MaintenanceSchedule>,
        val events: List<AssetEvent>,
        val activations: List<SeasonActivation>,
    ) {
        fun readinessOf(today: LocalDate): Map<String, Readiness> = assets.associate { asset ->
            asset.id.value to seasonReadiness(asset, assets, schedules, emptyList(), events, activations, today, ZoneOffset.UTC)
        }
    }

    private val heater: Asset = assetOf("heater", "Example Heater", SeasonMode.MANUAL)
    private val boiler: Asset = assetOf("boiler", "Example Boiler", SeasonMode.MANUAL)

    private val world = World(
        assets = listOf(hotTub, pool, heater, boiler),
        schedules = listOf(
            roleSchedule("s-tub", "tub", "2025-06-01"),
            roleSchedule("s-pool", "pool", "2025-06-01"),
            roleSchedule("s-heater", "heater", "2025-06-01"),
            roleSchedule("s-boiler", "boiler", "2025-06-01"),
        ),
        events = listOf(
            service("2026-10-20", "s-tub", "tub"),
            service("2026-09-10", "s-pool", "pool"),
            service("2026-09-18", "s-heater", "heater"),
            service("2026-09-20", "s-boiler", "boiler"),
        ),
        activations = listOf(
            start("2025-10-01", "heater"), end("2026-04-20", "heater"),
            start("2025-10-01", "boiler"), end("2026-05-02", "boiler"),
        ),
    )

    @Test
    fun twoAssetsKeepIndependentSeasonsAndReadiness() {
        val today = on("2026-10-25")
        val baseline = world.readinessOf(today)
        assertEquals(
            mapOf(
                "tub" to Readiness.Ready(on("2026-10-20"), ScheduleId("s-tub"), window("2026-04-01")),
                "pool" to Readiness.Ready(on("2026-09-10"), ScheduleId("s-pool"), window("2026-09-01")),
                "heater" to Readiness.Ready(
                    on("2026-09-18"), ScheduleId("s-heater"), ReadinessBoundary(on("2026-04-20"), BoundarySource.PHONE_SEASON_END),
                ),
                "boiler" to Readiness.Ready(
                    on("2026-09-20"), ScheduleId("s-boiler"), ReadinessBoundary(on("2026-05-02"), BoundarySource.PHONE_SEASON_END),
                ),
            ),
            baseline,
        )

        val variants = listOf(
            "heater" to world.copy(activations = world.activations + end("2026-10-24", "heater")),
            "boiler" to world.copy(activations = world.activations + end("2026-09-21", "boiler")),
            "tub" to world.copy(events = world.events.filterNot { it.assetId == hotTub.id }),
            "pool" to world.copy(assets = world.assets.map { if (it.id == pool.id) it.copy(seasonStartMmdd = "05-01", seasonEndMmdd = "09-05") else it }),
            "boiler" to world.copy(schedules = world.schedules.map { if (it.id.value == "s-boiler") it.copy(roleSinceOn = "2026-09-01") else it }),
            "tub" to world.copy(schedules = world.schedules.map { if (it.id.value == "s-tub") it.copy(roleSinceOn = "2026-05-01") else it }),
        )
        for ((varied, variant) in variants) {
            val answers = variant.readinessOf(today)
            assertNotEquals(baseline.getValue(varied), answers.getValue(varied), "$varied moves")
            for (other in baseline.keys - varied) {
                assertEquals(baseline.getValue(other), answers.getValue(other), "$other stays when $varied moves")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Row 106 — names, categories, templates and titles never decide a season (tripwire).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun nameCategoryTitleAndTypeNeverDecideTheSeason() {
        val events = listOf(service("2026-10-20", "s-open", "tub"))
        fun answer(asset: Asset, title: String): Readiness = seasonReadiness(
            asset, listOf(asset), listOf(roleSchedule("s-open", "tub", "2026-03-15", title = title)), emptyList(), events,
            listOf(end("2026-10-01", "tub")), on("2026-10-25"), ZoneOffset.UTC,
        )
        val baseline = answer(hotTub, "Opening service")
        assertEquals(Readiness.Ready(on("2026-10-20"), ScheduleId("s-open"), window("2026-04-01")), baseline)
        val disguises = listOf(
            hotTub.copy(name = "Example Heater"),
            hotTub.copy(category = "Heating"),
            hotTub.copy(category = "Pool & Spa", templateKey = "hot_tub"),
            hotTub.copy(name = "Example Heater", category = "Heater", templateKey = "pellet_stove", description = "pellet-type appliance"),
        )
        for (asset in disguises) {
            assertEquals(baseline, answer(asset, "Opening service"), "${asset.name} / ${asset.category} / ${asset.templateKey}")
            assertEquals(baseline, answer(asset, "Season Start service"), "the title too")
            assertEquals(baseline, answer(asset, "Example annual service"), "any title")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Row h4 (#125) — the owner's case, fictional: the opening service follows the winterize switch, and the
    // weekly in-use checks start with the in-use season.
    // ---------------------------------------------------------------------------------------------

    /**
     * An "Example Heater" in use 11-01…03-31 (`CALENDAR`). Its Season Start service (the role, `IN_SERVICE_AT_START`,
     * 0, its yearly due long reached) follows the Home Assistant winterize switch, which turns on when the first freeze
     * is forecast; its weekly in-use check (#124, `IN_SERVICE_AFTER_OPENING_SERVICE`, 7) keeps the asset's season.
     */
    private suspend fun winterizedHeater(h: ScheduleSeasonHarness) {
        h.on("2026-10-15")
        h.asset()
        h.schedule(
            "s-open", policy = ServicePolicy.IN_SERVICE_AT_START, offset = 0, role = ScheduleRole.SEASON_START,
        )
        h.schedule(
            "s-weekly", policy = ServicePolicy.IN_SERVICE_AFTER_OPENING_SERVICE, offset = 7, unit = RecurrenceUnit.WEEK,
            anchorOn = "2026-01-05",
        )
        h.connect()
        h.link.run(ScheduleId("s-open"), "input_boolean.example_heater_winterize_season")
        h.answer("s-open", HaSwitchState.OFF)
    }

    private suspend fun ScheduleSeasonHarness.readiness(): Readiness = seasonReadiness(
        raw.assets.rows.getValue("heater"), raw.assets.rows.values, raw.schedules.rows.values, raw.groups.rows.values,
        raw.events.rows.values, raw.activations.rows.values.toList(), day, ZoneOffset.UTC,
    )

    @Test
    fun theOpeningServiceIsDueWithTheWinterizeSwitchAndTheWeeklyChecksStartWithTheInUseSeason() = runTest {
        val h = ScheduleSeasonHarness()
        winterizedHeater(h)
        val waiting = h.state("s-open")
        assertNull(waiting.actionableDueOn, "the switch is off: no start is predicted")
        assertEquals(PolicyReason.AWAITING_START, waiting.policyReason)

        // The switch turns on: the opening service comes due that day.
        h.on("2026-10-20")
        h.answer("s-open", HaSwitchState.ON)
        assertEquals("2026-10-20" to PolicyPhase.ACTIVE, h.state("s-open").let { it.actionableDueOn to it.policyPhase })

        // Serviced on 10-22: the asset is ready, by its own window's cutoff. The equipment is on standby: no weekly
        // check runs before the in-use season, and the first one is held at 11-01 + 7.
        h.on("2026-10-22")
        h.complete("s-open", "2026-10-22")
        assertEquals(
            Readiness.Ready(on("2026-10-22"), ScheduleId("s-open"), window("2026-04-01")),
            h.readiness(),
            "readiness stays on the asset's season",
        )
        val standby = h.state("s-weekly")
        assertEquals("2026-11-08" to PolicyPhase.DORMANT, standby.actionableDueOn to standby.policyPhase, "standby")

        // The in-use season starts 11-01: the first weekly check is the later of 11-01 and 10-22, plus 7 — never 10-29.
        h.on("2026-11-01")
        h.recompute.forAsset(AssetId("heater"))
        val weekly = h.state("s-weekly")
        assertEquals(
            Triple("2026-11-08", PolicyPhase.ACTIVE, PolicyReason.SEASON_START),
            Triple(weekly.actionableDueOn, weekly.policyPhase, weekly.policyReason),
        )
        assertEquals(ScheduleSeasonSource.ASSET, h.stored("s-weekly").seasonSource, "the asset's other schedules untouched")

        // The in-use season ends 03-31: from 04-01 the weekly checks stop again (H1 review N3).
        h.on("2027-04-01")
        h.recompute.forAsset(AssetId("heater"))
        assertEquals(PolicyPhase.DORMANT, h.state("s-weekly").policyPhase, "weekly checks only in 11-01…03-31")
    }

    @Test
    fun aLateOpeningServiceStartsTheWeeklyChecksSevenDaysAfterIt() = runTest {
        val h = ScheduleSeasonHarness()
        winterizedHeater(h)
        h.on("2026-10-20")
        h.answer("s-open", HaSwitchState.ON)
        assertEquals("2026-10-20", h.state("s-open").actionableDueOn, "due with the switch")

        // In use from 11-01 but not yet serviced: the weekly check waits for the opening service.
        h.on("2026-11-03")
        h.recompute.forAsset(AssetId("heater"))
        assertEquals(PolicyReason.AWAITING_OPENING_SERVICE, h.state("s-weekly").policyReason)

        h.on("2026-11-05")
        h.complete("s-open", "2026-11-05")
        val weekly = h.state("s-weekly")
        assertEquals(
            Triple("2026-11-12", PolicyPhase.ACTIVE, PolicyReason.OPENING_SERVICE),
            Triple(weekly.actionableDueOn, weekly.policyPhase, weekly.policyReason),
            "the later of 11-01 and 11-05, plus 7",
        )
    }
}
