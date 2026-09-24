package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.health.AssetHealthResult
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.health.ComponentCondition
import com.loosecannon.servicetag.ui.health.ConditionView
import com.loosecannon.servicetag.ui.health.needsAttention

/**
 * What a scan of one asset shows, and whether it shows the sheet at all (spec §10.1, O-8).
 *
 * [opens] is the routing answer and the rest is the content, and both come out of
 * [scanSheetContent] in one call, so "does this scan open the sheet" and "what does the sheet list"
 * can never disagree (inv. 123).
 *
 * [maintenance] is empty whenever the sheet does not open; the other lists are filled either way,
 * because asset detail shows the same lines. [aggregate] is present only when it is WARNING or
 * CRITICAL; [critical] lists every CRITICAL subject whatever the aggregate says (inv. 119).
 * [subjects] is every non-archived subject with its value, in the engine's order — the ones with a
 * score are the contributors the aggregate line (S108) names — so the sheet reads health once.
 */
data class ScanSheetContent(
    val opens: Boolean,
    val condition: ConditionView?,
    val offersMarkOperational: Boolean,
    val components: List<ComponentCondition>,
    val critical: List<SubjectHealth>,
    /** Only when WARNING or CRITICAL; its `trackedDays` is always null (an aggregate has no day count). */
    val aggregate: SubjectValue.Scored?,
    val maintenance: List<DueItem>,
    val subjects: List<SubjectHealth>,
)

/**
 * **The one predicate** that routes and fills the scan sheet (spec §10.1; master plan §13.1).
 *
 * The sheet opens for any D-18a item ([actionableOnScanSheet]), or when the asset is DOWN or
 * DEGRADED, or when any in-service component is — condition is an at-the-unit concern, so it may
 * open the sheet (O-8). **Calculated health never opens it alone**: a CRITICAL subject is a
 * passenger, listed when something else opens the sheet, exactly as a DUE SOON row is.
 *
 * When it opens, [ScanSheetContent.maintenance] lists the D-18a items with DUE SOON rows riding
 * along — whatever opened it, condition included (plan decision 35) — in the attention order the
 * projection already has. [items] must already be narrowed to the rounds that oblige this asset
 * ([scanSheetContentFor] does that).
 *
 * [inService] is the scanned asset's own lifecycle. An archived or retired asset **never** opens the
 * sheet and is never offered "Mark operational", whatever its condition or its schedules say: the
 * scan goes to asset detail, which shows the same lines (the controller's ruling on B07's review,
 * M1). It defaults to true only so the pinned four-argument form still reads a live asset;
 * [scanSheetContentFor], the one production caller, always passes the asset's own answer.
 */
fun scanSheetContent(
    items: List<DueItem>,
    condition: ConditionView?,
    components: List<ComponentCondition>,
    health: AssetHealthResult?,
    inService: Boolean = true,
): ScanSheetContent {
    val unitNeedsAttention = inService && condition?.condition?.needsAttention == true
    val opens = inService && (
        items.any { it.actionableOnScanSheet } ||
            unitNeedsAttention ||
            components.any { it.condition.needsAttention }
        )
    return ScanSheetContent(
        opens = opens,
        condition = condition,
        offersMarkOperational = unitNeedsAttention,
        components = components,
        critical = health?.critical.orEmpty(),
        aggregate = health?.aggregate?.takeIf { it.band != HealthBand.NOMINAL },
        maintenance = if (opens) items.filter { it.actionableOnScanSheet || it.ridesAlong } else emptyList(),
        subjects = health?.subjects.orEmpty(),
    )
}

/**
 * [scanSheetContent] for the asset a scan resolved to: its projection narrowed to the rounds that
 * oblige it, and its health view. `ScanSheetOffer.has` is this call's [ScanSheetContent.opens] and
 * the sheet lists this call's content, so there is no second predicate anywhere.
 */
suspend fun scanSheetContentFor(
    assetId: AssetId,
    due: DueReadModel,
    rounds: ScanRoundMembership,
    health: AssetHealthReadModel,
): ScanSheetContent {
    val view = health.forAsset(assetId)
    return scanSheetContent(
        items = rounds.obliged(assetId, due.forAsset(assetId)),
        condition = view.condition,
        components = view.components,
        health = view.result,
        inService = view.inService,
    )
}

/**
 * A DUE SOON row the sheet carries as a passenger when it is open for another reason (D5 §7A,
 * `:208`): never alone, never an empty round, never a reminders-disabled schedule.
 */
private val DueItem.ridesAlong: Boolean
    get() = status == DueStatus.DUE_SOON && !requiredSetEmpty && remindersEnabled
