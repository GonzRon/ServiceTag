package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonInputs
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.seasonInputs

/**
 * Spec §7.2–§7.4's fictional fixtures F3, F4 and F5 (spec §12.4), as builders: every value below is
 * the spec's own, and the few the spec leaves unsaid are named where they are chosen. Read-only for
 * later briefs — a brief that needs a different configuration builds its own beside these.
 *
 * Each fixture is an [Asset] (the season and break live there), its schedule, and the history the
 * spec's timeline starts from. [SeasonInputs] come from [Asset.seasonInputs], exactly as the
 * recompute builds them, so a test that hands the engine these inputs and a test that runs the whole
 * recompute over the repositories are asking about the same asset.
 */
object SeasonFixtures {

    /** The break every fixture with one uses: 1 Dec → 28 Feb, wrapping the year. */
    const val WINTER_BREAK_START = "12-01"
    const val WINTER_BREAK_END = "02-28"

    // ----------------------------------------------------------------------------------------
    // F3 — the generator (spec §7.4): YEAR_ROUND with the winter break, IN_SERVICE_AT_START
    // ("After the maintenance break"), six-monthly FIXED anchored 2025-12-20, created 5 Jan 2026,
    // lead 14. First R = 20 Jun 2026; completed 20 Oct 2026 → R = 20 Dec 2026 → A = 1 Mar 2027.
    // ----------------------------------------------------------------------------------------

    fun generatorAsset(id: String = "gen"): Asset = assetOf(
        id = id,
        name = "Generator",
        mode = SeasonMode.YEAR_ROUND,
        breakStart = WINTER_BREAK_START,
        breakEnd = WINTER_BREAK_END,
    )

    fun generatorSchedule(id: String = "s-gen", assetId: String = "gen"): MaintenanceSchedule = scheduleOf(
        id = id,
        assetId = assetId,
        title = "Engine oil service",
        timeInterval = 6,
        timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.FIXED,
        anchorOn = "2025-12-20",
        leadDays = 14,
        servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
        policyOffsetDays = 0,
        createdOn = "2026-01-05",
    )

    /** The 20 Jun 2026 occurrence completed on 20 Oct 2026 (§7.4's fifth row). */
    fun generatorCompletedInOctober(assetId: String = "gen", scheduleId: String = "s-gen"): AssetEvent =
        completionOf("e-gen-1", occurredOn = "2026-10-20", occurrenceOn = "2026-06-20", assetId = assetId, scheduleId = scheduleId)

    // ----------------------------------------------------------------------------------------
    // F4 — the snowblower (spec §7.3): CALENDAR 11-15 → 03-31 with the winter break inside the
    // season; PRE_SERVICE, margin 14 (offset −14), lead 14; two-yearly FIXED anchored 2024-12-20,
    // last done 2024-11-10 (that is O), so R = 2026-12-20 and A = 1 Nov 2026 (BEFORE_SEASON).
    // ----------------------------------------------------------------------------------------

    fun snowblowerAsset(id: String = "snow"): Asset = assetOf(
        id = id,
        name = "Snowblower",
        mode = SeasonMode.CALENDAR,
        seasonStart = "11-15",
        seasonEnd = "03-31",
        breakStart = WINTER_BREAK_START,
        breakEnd = WINTER_BREAK_END,
    )

    fun snowblowerSchedule(id: String = "s-snow", assetId: String = "snow"): MaintenanceSchedule = scheduleOf(
        id = id,
        assetId = assetId,
        title = "Engine oil service",
        timeInterval = 2,
        timeUnit = RecurrenceUnit.YEAR,
        timeBasis = TimeBasis.FIXED,
        anchorOn = "2024-12-20",
        leadDays = 14,
        servicePolicy = ServicePolicy.PRE_SERVICE,
        policyOffsetDays = -14,
        createdOn = "2024-10-01",
    )

    /** The 2024-12-20 occurrence, done early on 2024-11-10: O = 2024-11-10, R = 2026-12-20. */
    fun snowblowerLastDone(assetId: String = "snow", scheduleId: String = "s-snow"): AssetEvent =
        completionOf("e-snow-1", occurredOn = "2024-11-10", occurrenceOn = "2024-12-20", assetId = assetId, scheduleId = scheduleId)

    // ----------------------------------------------------------------------------------------
    // F4 — the mower (spec §7.3): CALENDAR 04-15 → 10-31, the same break in the off-season;
    // yearly oil change, PRE_SERVICE margin 14, lead 14. Two histories the spec names: R = 5 Nov
    // 2026 (kept) and R = 12 Jan 2027 (→ 1 Apr 2027, AFTER_BREAK). The anchors and the dates the
    // earlier occurrences were done are this file's choice; only R matters to the timelines.
    // ----------------------------------------------------------------------------------------

    fun mowerAsset(id: String = "mow", breakEnd: String = WINTER_BREAK_END): Asset = assetOf(
        id = id,
        name = "Mower",
        mode = SeasonMode.CALENDAR,
        seasonStart = "04-15",
        seasonEnd = "10-31",
        breakStart = WINTER_BREAK_START,
        breakEnd = breakEnd,
    )

    /** Yearly FIXED anchored on [anchorOn]; the pin puts R on the anchor until it is completed. */
    fun mowerSchedule(
        id: String = "s-mow",
        assetId: String = "mow",
        anchorOn: String = "2026-01-12",
        createdOn: String = "2025-12-01",
    ): MaintenanceSchedule = scheduleOf(
        id = id,
        assetId = assetId,
        title = "Engine oil service",
        timeInterval = 1,
        timeUnit = RecurrenceUnit.YEAR,
        timeBasis = TimeBasis.FIXED,
        anchorOn = anchorOn,
        leadDays = 14,
        servicePolicy = ServicePolicy.PRE_SERVICE,
        policyOffsetDays = -14,
        createdOn = createdOn,
    )

    /** The January mower: the 2026-01-12 occurrence done on 2 Mar 2026, so R = 12 Jan 2027. */
    fun mowerJanuaryDone(assetId: String = "mow", scheduleId: String = "s-mow"): AssetEvent =
        completionOf("e-mow-1", occurredOn = "2026-03-02", occurrenceOn = "2026-01-12", assetId = assetId, scheduleId = scheduleId)

    /** The autumn mower: anchored 2025-11-05 and done that day, so R = 5 Nov 2026. */
    fun mowerAutumnSchedule(id: String = "s-mow", assetId: String = "mow"): MaintenanceSchedule =
        mowerSchedule(id = id, assetId = assetId, anchorOn = "2025-11-05", createdOn = "2025-10-01")

    fun mowerAutumnDone(assetId: String = "mow", scheduleId: String = "s-mow"): AssetEvent =
        completionOf("e-mow-2", occurredOn = "2025-11-05", occurrenceOn = "2025-11-05", assetId = assetId, scheduleId = scheduleId)

    // ----------------------------------------------------------------------------------------
    // F5 — the hot tub (spec §7.2): MANUAL; weekly FIXED anchored Sat 2026-01-03,
    // IN_SERVICE_AT_START offset 0, lead 1. Completed Sat 11 Apr (R = Sat 18 Apr), END Thu 16 Apr,
    // START Sat 10 Oct. The spring START the timeline begins after is not given by the spec; it is
    // dated on the anchor here, 3 Jan 2026.
    // ----------------------------------------------------------------------------------------

    fun hotTubAsset(id: String = "tub"): Asset = assetOf(id = id, name = "Hot tub", mode = SeasonMode.MANUAL)

    fun hotTubSchedule(id: String = "s-tub", assetId: String = "tub"): MaintenanceSchedule = scheduleOf(
        id = id,
        assetId = assetId,
        title = "Water care",
        timeInterval = 1,
        timeUnit = RecurrenceUnit.WEEK,
        timeBasis = TimeBasis.FIXED,
        anchorOn = "2026-01-03",
        leadDays = 1,
        servicePolicy = ServicePolicy.IN_SERVICE_AT_START,
        policyOffsetDays = 0,
        createdOn = "2026-01-01",
    )

    fun hotTubCompletedOnApril11(assetId: String = "tub", scheduleId: String = "s-tub"): AssetEvent =
        completionOf("e-tub-1", occurredOn = "2026-04-11", occurrenceOn = "2026-04-11", assetId = assetId, scheduleId = scheduleId)

    /** The spring START (chosen here), the END of Thu 16 Apr and the START of Sat 10 Oct. */
    fun hotTubActivations(assetId: String = "tub"): List<SeasonActivation> = listOf(
        activationOf("act-1", assetId, SeasonAction.START, "2026-01-03"),
        activationOf("act-2", assetId, SeasonAction.END, "2026-04-16"),
        activationOf("act-3", assetId, SeasonAction.START, "2026-10-10"),
    )

    /** The hot tub's history as it stood on [on]: only the rows dated on or before it. */
    fun hotTubActivationsUpTo(on: String, assetId: String = "tub"): List<SeasonActivation> =
        hotTubActivations(assetId).filter { it.occurredOn <= on }

    // ----------------------------------------------------------------------------------------
    // Builders.
    // ----------------------------------------------------------------------------------------

    fun assetOf(
        id: String,
        name: String,
        mode: SeasonMode,
        seasonStart: String? = null,
        seasonEnd: String? = null,
        breakStart: String? = null,
        breakEnd: String? = null,
        category: String = "",
        templateKey: String? = null,
    ): Asset = Asset(
        id = AssetId(id),
        name = name,
        category = category,
        templateKey = templateKey,
        createdAt = dayMillis("2024-01-01"),
        updatedAt = dayMillis("2024-01-01"),
        seasonStartMmdd = seasonStart,
        seasonEndMmdd = seasonEnd,
        seasonMode = mode,
        blackoutStartMmdd = breakStart,
        blackoutEndMmdd = breakEnd,
    )

    fun activationOf(
        id: String,
        assetId: String,
        action: SeasonAction,
        occurredOn: String,
        createdAt: Long = dayMillis(occurredOn),
    ): SeasonActivation = SeasonActivation(
        id = id,
        assetId = AssetId(assetId),
        action = action,
        occurredOn = occurredOn,
        eventId = null,
        createdAt = createdAt,
    )

    /** Season inputs with no history: YEAR_ROUND or CALENDAR, with or without a break. */
    fun seasonOf(
        mode: SeasonMode,
        seasonStart: String? = null,
        seasonEnd: String? = null,
        breakStart: String? = null,
        breakEnd: String? = null,
        activations: List<SeasonActivation> = emptyList(),
    ): SeasonInputs = SeasonInputs(
        mode = mode,
        seasonStartMmdd = seasonStart,
        seasonEndMmdd = seasonEnd,
        blackoutStartMmdd = breakStart,
        blackoutEndMmdd = breakEnd,
        activations = activations,
    )
}
