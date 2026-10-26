package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.FIELD_ENTITY_ID
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishRefusal
import com.loosecannon.servicetag.core.hapublish.PublishRefused
import com.loosecannon.servicetag.core.hapublish.publishKindOf
import com.loosecannon.servicetag.core.seasonsync.EntityScope
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.pickerRows
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.serialization.Serializable

/**
 * #113/#114 (C29b) — helper discovery: `GET /v1/ha-connection/entities/{scope}`, the helpers Home Assistant reports in
 * one of [EntityScope]'s three domains, so a caller can name a publication's target or season-ended helper by its
 * exact id.
 *
 * The read is the shipped #105 one, taken as a collaborator ([listHaEntities], `AppGraph.listHaEntities` in
 * production): the stored connection, its token from the key store, and one `GET /api/states` through #16's request
 * gate — its network rule, headers, 30 s bound and 8 MiB cap — so this route sends the token exactly where and when
 * Test connection may. Then #105's own [pickerRows] over the scope: sorted, at most 2,000, `truncated` when more
 * matched. **Never cached:** each call is one fresh read and nothing it read is kept, so no answer outlives the
 * connection it was read under. The answer names ids, friendly names and the helper's shape only — never a state,
 * the address, the Wi-Fi name or the token — and nothing here writes a row.
 *
 * No stored connection is 409 `HA_PUBLISH_REFUSED` (`NO_CONNECTION`, which names no body key); a read that answered
 * no list is 409 `HA_HELPER_READ_FAILED` with its kind under the outbound name ([publishKindOf]).
 */
internal class HaEntityHandlers(private val listHaEntities: suspend () -> HaListOutcome?) {
    constructor(graph: AppGraph) : this(graph.listHaEntities)

    suspend fun listEntities(scope: EntityScope): ApiResponse =
        when (val outcome = listHaEntities() ?: throw PublishRefused(PublishRefusal.NO_CONNECTION, FIELD_ENTITY_ID)) {
            is HaListOutcome.Listed -> {
                val picked = pickerRows(outcome.entities, scope, "")
                ok(
                    HaEntityListResponse.serializer(),
                    HaEntityListResponse(scope.domain, picked.rows.map { it.toRow() }, picked.truncatedList),
                )
            }
            is HaListOutcome.Failed -> throw HelperReadFailed(publishKindOf(outcome.kind), outcome.detail)
        }

    companion object {
        /** The scope a path segment names — a domain spelled exactly — or null, which the router answers 404. */
        fun scopeOf(segment: String): EntityScope? = EntityScope.entries.firstOrNull { it.domain == segment }
    }
}

/**
 * #113/#114 (C29b) — 409 `HA_HELPER_READ_FAILED`: a read of Home Assistant answered nothing to use, as [kind] with
 * [detail] (an HTTP status or #16's network cause at most; never an address, a body or a token).
 */
internal class HelperReadFailed(val kind: PublishErrorKind, val detail: String?) :
    IllegalStateException("home assistant read failed: $kind")

/** `GET /v1/ha-connection/entities/{scope}`: the scope's domain, its rows, and whether more matched than are listed. */
@Serializable
internal data class HaEntityListResponse(val scope: String, val entities: List<HaEntityRow>, val truncated: Boolean)

/**
 * One listed helper: its exact id, Home Assistant's friendly name (or null), and its shape — [options] on an
 * `input_select` row, [hasDate] / [hasTime] on an `input_datetime` row, null everywhere else. Never a state.
 */
@Serializable
internal data class HaEntityRow(
    val entityId: String,
    val friendlyName: String?,
    val options: List<String>?,
    val hasDate: Boolean?,
    val hasTime: Boolean?,
)

private fun HaEntityCandidate.toRow() = HaEntityRow(
    entityId = entityId,
    friendlyName = friendlyName,
    options = attributes?.options,
    hasDate = attributes?.hasDate,
    hasTime = attributes?.hasTime,
)
