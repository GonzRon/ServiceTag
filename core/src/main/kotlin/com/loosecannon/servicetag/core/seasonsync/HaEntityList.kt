package com.loosecannon.servicetag.core.seasonsync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * #105 — one Home Assistant entity as the setup sheet's browser lists it: its exact id, and the `friendly_name` Home
 * Assistant reported for it, or null when it reported none. Presentation only: the binding stores [entityId] and
 * nothing is ever inferred from [friendlyName].
 */
data class HaEntityCandidate(val entityId: String, val friendlyName: String?)

/** #105 — the answer to one foreground `GET /api/states`: the candidates, or a failure in the poll's own kinds. */
sealed interface HaListOutcome {
    data class Listed(val entities: List<HaEntityCandidate>) : HaListOutcome

    data class Failed(val kind: SyncErrorKind, val detail: String?) : HaListOutcome
}

/** #105 — the longest friendly name kept from an answer; the id keeps C5's [MAX_HA_TEXT_LENGTH]. */
const val MAX_HA_NAME_LENGTH = 128

private val MALFORMED_LIST = HaListOutcome.Failed(SyncErrorKind.MALFORMED, null)

/**
 * #105 (B1) — the states list, pure. In order:
 * 1. 401 or 403 → [SyncErrorKind.AUTH_REFUSED]; 300–399 → [SyncErrorKind.REDIRECTED]; any other status but 200 →
 *    [SyncErrorKind.HTTP_ERROR] with the status as `detail` — Test connection's map, not the poll's: a 404 here is not
 *    a missing entity.
 * 2. A 200 that was [HaHttpAnswer.truncated], whose content type is not `application/json`, or whose body is not one
 *    JSON array → [SyncErrorKind.MALFORMED]. The cap is the client's (8 MiB for this call, owner ruling Q2).
 * 3. Each array element that is an object with a string `entity_id` of C4's shape ([isValidEntityId]) and no longer
 *    than [MAX_HA_TEXT_LENGTH] is one candidate, the first of any duplicate id winning; anything else is skipped,
 *    never a failure. Its `attributes.friendly_name` is kept when it is a non-blank string, bounded by
 *    [MAX_HA_NAME_LENGTH] with control characters as `?` (C5 rule 4); otherwise null.
 *
 * Nothing about an entity's `state` is read: a helper is listed for what it is, never for what it reports (#105).
 */
fun mapHaStatesAnswer(answer: HaHttpAnswer): HaListOutcome {
    val status = answer.status
    if (status == 401 || status == 403) return HaListOutcome.Failed(SyncErrorKind.AUTH_REFUSED, null)
    if (status in 300..399) return HaListOutcome.Failed(SyncErrorKind.REDIRECTED, null)
    if (status != 200) return HaListOutcome.Failed(SyncErrorKind.HTTP_ERROR, status.toString())

    if (answer.truncated) return MALFORMED_LIST
    if (!isJsonMediaType(answer.contentType)) return MALFORMED_LIST
    val array = parseArray(answer.body) ?: return MALFORMED_LIST

    val seen = HashSet<String>()
    val entities = ArrayList<HaEntityCandidate>()
    for (element in array) {
        val entity = element as? JsonObject ?: continue
        val id = entity.stringOrNull("entity_id") ?: continue
        if (id.length > MAX_HA_TEXT_LENGTH || !isValidEntityId(id)) continue
        if (!seen.add(id)) continue
        val name = (entity["attributes"] as? JsonObject)
            ?.stringOrNull("friendly_name")
            ?.let { bounded(it, MAX_HA_NAME_LENGTH) }
            ?.takeIf { it.isNotBlank() }
        entities += HaEntityCandidate(id, name)
    }
    return HaListOutcome.Listed(entities)
}

/** One JSON array, or null for anything else: an object, a scalar, or text that does not parse. */
private fun parseArray(body: ByteArray): JsonArray? = try {
    Json.parseToJsonElement(body.decodeToString()) as? JsonArray
} catch (e: IllegalArgumentException) {
    // kotlinx.serialization's SerializationException is an IllegalArgumentException.
    null
}
