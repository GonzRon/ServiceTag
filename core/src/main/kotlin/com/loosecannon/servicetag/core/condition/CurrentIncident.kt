package com.loosecannon.servicetag.core.condition

import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition

/**
 * The Incident of an asset's **current failure** (#82 C4, AC 9; R82-6), or null. Pure: it is handed
 * the asset's condition rows and journal and reads nothing; it only decides whether "Log incident"
 * leads, never what history says.
 *
 * Null unless the current row is DOWN or DEGRADED. The failure is the **impaired stretch**: the latest
 * contiguous run of DOWN and DEGRADED rows in history order — a worsening from DEGRADED to DOWN stays
 * one failure, an OPERATIONAL row ends it. It is not "since", which a worsening would restart. Then:
 * 1. the latest INCIDENT, not a completion, that a row of the stretch names, if it still exists;
 * 2. else the latest **unlinked** INCIDENT of the asset — one no condition row names, since a row
 *    outside the stretch ties it to an earlier failure — not a completion, dated on or after the
 *    stretch's first day **or** logged (`createdAt`) at or after that row was: failed Monday, recorded
 *    Tuesday, the Incident backdated to Monday still belongs to this failure.
 *
 * "Latest" is the condition order's key: `(occurredOn, occurredTime with nulls first, createdAt, id)`.
 * A dangling link, a linked MAINTENANCE or any other kind never counts.
 */
fun currentIncident(rows: List<AssetCondition>, events: List<AssetEvent>): AssetEvent? {
    val ordered = ConditionHistory.of(rows).ordered
    val current = ordered.lastOrNull()?.takeIf { it.condition.impaired } ?: return null
    var start = ordered.lastIndex
    while (start > 0 && ordered[start - 1].condition.impaired) start--
    val stretch = ordered.subList(start, ordered.size)
    val incidents = events.filter {
        it.assetId == current.assetId && it.kind == EventKind.INCIDENT && it.scheduleId == null
    }
    val named = stretch.mapNotNull { it.eventId }.toSet()
    incidents.filter { it.id in named }.maxWithOrNull(LATEST)?.let { return it }
    val began = stretch.first()
    val linked = ordered.mapNotNull { it.eventId }.toSet()
    return incidents
        .filter { it.id !in linked && (it.occurredOn >= began.occurredOn || it.createdAt >= began.createdAt) }
        .maxWithOrNull(LATEST)
}

/**
 * Whether "Log incident" leads (#82 C4, C10, C11): the asset is in service, its current condition is
 * DOWN or DEGRADED, and its current failure has no Incident ([currentIncident]). The caller supplies
 * [inService], the app's one rule for retired and archived assets (R82-7).
 */
fun needsIncident(inService: Boolean, rows: List<AssetCondition>, events: List<AssetEvent>): Boolean =
    inService &&
        ConditionHistory.of(rows).current?.condition?.impaired == true &&
        currentIncident(rows, events) == null

private val OperationalCondition.impaired: Boolean
    get() = this == OperationalCondition.DOWN || this == OperationalCondition.DEGRADED

private val LATEST: Comparator<AssetEvent> =
    compareBy({ it.occurredOn }, { it.occurredTime }, { it.createdAt }, { it.id.value })
