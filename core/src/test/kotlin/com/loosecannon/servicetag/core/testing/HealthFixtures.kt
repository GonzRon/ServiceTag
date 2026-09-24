package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.health.AssetHealthEngine
import com.loosecannon.servicetag.core.health.AssetHealthResult
import com.loosecannon.servicetag.core.health.LinkedSchedule
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonInputs
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.schedule.ScheduleRecompute
import com.loosecannon.servicetag.core.schedule.SeasonContext
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Spec §12.4's five fictional fixtures as health sees them: the subjects, the events their
 * baselines come from, and one helper that runs the whole read path — the recompute, the policy
 * inputs, the engine — for a day. F3, F4 and F5 are [SeasonFixtures]' assets and schedules, read and
 * never edited here; F1 and F2 are new. Every value is the spec's own, and the few it leaves unsaid
 * are named where they are chosen.
 */
object HealthFixtures {

    /** The spec's date: every fixture's "expected at `T`" is this day. */
    val SPEC_DAY: LocalDate = LocalDate.parse("2026-09-24")

    /** The AGE thresholds F1 and F2 share: 365 / 1095 / 1460. */
    private const val BATTERY_NOMINAL_UNTIL = 365
    private const val BATTERY_WARNING_FROM = 1095
    private const val BATTERY_CRITICAL_FROM = 1460

    /** The engine-service thresholds F3 and F4 state: 14 / 45 / 120. */
    private const val ENGINE_NOMINAL_UNTIL = 14
    private const val ENGINE_WARNING_FROM = 45
    private const val ENGINE_CRITICAL_FROM = 120

    // ----------------------------------------------------------------------------------------
    // F1 — the UPS: "Battery age", PART, AGE 365 / 1095 / 1460, REPLACEMENT 2022-06-01, no baseline
    // quick action (any REPLACEMENT). At the spec's date x = 1576 → 18 CRITICAL. The self-test
    // INSPECTION on 20 Sep (the day the condition went DOWN) is this file's choice: an event on the
    // asset that must never count as a baseline.
    // ----------------------------------------------------------------------------------------

    fun upsAsset(id: String = "ups"): Asset = SeasonFixtures.assetOf(id = id, name = "UPS", mode = SeasonMode.YEAR_ROUND)

    fun upsBatteryAge(assetId: String = "ups"): HealthSubject = subjectOf(
        id = "h-ups-battery",
        assetId = assetId,
        name = "Battery age",
        kind = HealthSubjectKind.PART,
        driver = HealthDriver.AGE,
        nominalUntil = BATTERY_NOMINAL_UNTIL,
        warningFrom = BATTERY_WARNING_FROM,
        criticalFrom = BATTERY_CRITICAL_FROM,
    )

    fun upsBatteryReplaced(assetId: String = "ups"): AssetEvent =
        eventOf("e-ups-replaced", assetId, EventKind.REPLACEMENT, "Battery replaced", "2022-06-01")

    fun upsSelfTest(assetId: String = "ups"): AssetEvent =
        eventOf("e-ups-self-test", assetId, EventKind.INSPECTION, "Battery self-test", "2026-09-20")

    // ----------------------------------------------------------------------------------------
    // F2 — the battery pack: "Battery age", ASSET, the same thresholds, baseline quick action
    // "Battery replaced" (a REPLACEMENT profile), REPLACEMENT 2023-05-10; an INSPECTION load test
    // with its own quick action (the C.2 split). x = 1233 → 47 WARNING; WARNING from 9 May 2026,
    // CRITICAL from 9 May 2027. The load test's date is this file's choice.
    // ----------------------------------------------------------------------------------------

    val PACK_BATTERY_REPLACED = ProfileId("p-pack-battery-replaced")
    val PACK_LOAD_TEST = ProfileId("p-pack-load-test")

    fun packAsset(id: String = "pack"): Asset = SeasonFixtures.assetOf(id = id, name = "Battery pack", mode = SeasonMode.YEAR_ROUND)

    fun packBatteryAge(assetId: String = "pack"): HealthSubject = subjectOf(
        id = "h-pack-battery",
        assetId = assetId,
        name = "Battery age",
        kind = HealthSubjectKind.ASSET,
        driver = HealthDriver.AGE,
        baselineProfileId = PACK_BATTERY_REPLACED.value,
        nominalUntil = BATTERY_NOMINAL_UNTIL,
        warningFrom = BATTERY_WARNING_FROM,
        criticalFrom = BATTERY_CRITICAL_FROM,
    )

    fun packBatteryReplaced(assetId: String = "pack"): AssetEvent = eventOf(
        "e-pack-replaced", assetId, EventKind.REPLACEMENT, "Battery replaced", "2023-05-10",
        profileId = PACK_BATTERY_REPLACED.value,
    )

    fun packLoadTest(assetId: String = "pack", on: String = "2026-06-01"): AssetEvent = eventOf(
        "e-pack-load-test-$on", assetId, EventKind.INSPECTION, "Load test", on,
        profileId = PACK_LOAD_TEST.value,
    )

    // ----------------------------------------------------------------------------------------
    // F3 — the generator (§7.4), F4 — the snowblower and the mower (§7.3): "Engine oil service",
    // 14 / 45 / 120. The spec states the kind for the snowblower (PART) and calls the mower's "the
    // same subject"; the generator's is PART here too — only MEDIUM changes the clock (§6.1).
    // ----------------------------------------------------------------------------------------

    fun generatorEngineOil(assetId: String = "gen", scheduleId: String = "s-gen"): HealthSubject =
        engineOilService("h-gen-oil", assetId, scheduleId)

    fun snowblowerEngineOil(assetId: String = "snow", scheduleId: String = "s-snow"): HealthSubject =
        engineOilService("h-snow-oil", assetId, scheduleId)

    fun mowerEngineOil(assetId: String = "mow", scheduleId: String = "s-mow"): HealthSubject =
        engineOilService("h-mow-oil", assetId, scheduleId)

    private fun engineOilService(id: String, assetId: String, scheduleId: String): HealthSubject = subjectOf(
        id = id,
        assetId = assetId,
        name = "Engine oil service",
        kind = HealthSubjectKind.PART,
        driver = HealthDriver.MAINTENANCE_OVERDUE,
        scheduleId = scheduleId,
        nominalUntil = ENGINE_NOMINAL_UNTIL,
        warningFrom = ENGINE_WARNING_FROM,
        criticalFrom = ENGINE_CRITICAL_FROM,
    )

    // ----------------------------------------------------------------------------------------
    // F5 — the hot tub (§7.2): "Water care", MEDIUM, 2 / 7 / 14. [kind] exists for §7.2's contrast,
    // which asks what the same subject would read as a PART.
    // ----------------------------------------------------------------------------------------

    fun hotTubWaterCare(
        assetId: String = "tub",
        scheduleId: String = "s-tub",
        kind: HealthSubjectKind = HealthSubjectKind.MEDIUM,
    ): HealthSubject = subjectOf(
        id = "h-tub-water",
        assetId = assetId,
        name = "Water care",
        kind = kind,
        driver = HealthDriver.MAINTENANCE_OVERDUE,
        scheduleId = scheduleId,
        nominalUntil = 2,
        warningFrom = 7,
        criticalFrom = 14,
    )

    /**
     * §7.2's contrast history: the 18 Apr occurrence still open at an END on Wed 22 Apr (instead of
     * Thu 16 Apr), then the START of Sat 10 Oct.
     */
    fun hotTubContrastActivations(assetId: String = "tub"): List<SeasonActivation> = listOf(
        SeasonFixtures.activationOf("act-c1", assetId, SeasonAction.START, "2026-01-03"),
        SeasonFixtures.activationOf("act-c2", assetId, SeasonAction.END, "2026-04-22"),
        SeasonFixtures.activationOf("act-c3", assetId, SeasonAction.START, "2026-10-10"),
    )

    // ----------------------------------------------------------------------------------------
    // The read path.
    // ----------------------------------------------------------------------------------------

    /**
     * [schedule] as the engine is handed it on [today]: the recompute's state for the day, then the
     * policy inputs off it — the same two steps the app's read model takes through `readState`.
     */
    fun linkedOn(
        schedule: MaintenanceSchedule,
        events: List<AssetEvent>,
        today: LocalDate,
        season: SeasonInputs?,
    ): LinkedSchedule {
        val state = ScheduleRecompute.rebuild(schedule, events, emptyList(), emptyList(), today, ZoneOffset.UTC, season)
        return LinkedSchedule(schedule, ScheduleRecompute.policyInputsOf(schedule, state, ZoneOffset.UTC))
    }

    /**
     * The asset's health on [date], from its configuration and history as they stand that day:
     * [activations] are the asset's manual rows up to [date], [events] its journal.
     */
    fun healthOn(
        date: String,
        asset: Asset,
        subjects: List<HealthSubject>,
        schedules: List<MaintenanceSchedule> = emptyList(),
        events: List<AssetEvent> = emptyList(),
        activations: List<SeasonActivation> = emptyList(),
        profileExists: (ProfileId) -> Boolean = { true },
    ): AssetHealthResult {
        val today = LocalDate.parse(date)
        val season = asset.seasonInputs(activations)
        val links = schedules.associate { it.id to linkedOn(it, events, today, season) }
        return AssetHealthEngine.evaluate(asset, subjects, links, events, profileExists, SeasonContext.of(season), today)
    }

    // ----------------------------------------------------------------------------------------
    // Builders.
    // ----------------------------------------------------------------------------------------

    fun subjectOf(
        id: String,
        assetId: String,
        name: String = id,
        kind: HealthSubjectKind = HealthSubjectKind.ASSET,
        driver: HealthDriver,
        scheduleId: String? = null,
        baselineProfileId: String? = null,
        nominalUntil: Int,
        warningFrom: Int,
        criticalFrom: Int,
        weight: Int = 1,
        sortOrder: Int = 0,
        archivedAt: Long? = null,
    ): HealthSubject = HealthSubject(
        id = HealthSubjectId(id),
        assetId = AssetId(assetId),
        name = name,
        kind = kind,
        driver = driver,
        scheduleId = scheduleId?.let(::ScheduleId),
        baselineProfileId = baselineProfileId?.let(::ProfileId),
        nominalUntilDays = nominalUntil,
        warningFromDays = warningFrom,
        criticalFromDays = criticalFrom,
        weight = weight,
        sortOrder = sortOrder,
        archivedAt = archivedAt,
        createdAt = dayMillis("2026-01-01"),
        updatedAt = dayMillis("2026-01-01"),
    )

    fun eventOf(
        id: String,
        assetId: String,
        kind: EventKind,
        title: String,
        occurredOn: String,
        profileId: String? = null,
        createdAt: Long = dayMillis(occurredOn),
    ): AssetEvent = AssetEvent(
        id = EventId(id),
        assetId = AssetId(assetId),
        kind = kind,
        title = title,
        profileId = profileId?.let(::ProfileId),
        occurredOn = occurredOn,
        occurredTime = null,
        tzId = "UTC",
        notes = "",
        source = EventSource.MANUAL,
        sourceRef = null,
        createdAt = createdAt,
        updatedAt = createdAt,
        measurements = emptyList(),
        consumables = emptyList(),
    )
}
