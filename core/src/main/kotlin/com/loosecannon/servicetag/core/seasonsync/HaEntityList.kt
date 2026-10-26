package com.loosecannon.servicetag.core.seasonsync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * #105 — one Home Assistant entity as the setup sheet's browser lists it: its exact id, and the `friendly_name` Home
 * Assistant reported for it, or null when it reported none. Presentation only: the binding stores [entityId] and
 * nothing is ever inferred from [friendlyName]. #113/#114 (C29b): [attributes] is a helper's shape, kept only for an
 * `input_select` or `input_datetime` id and null for every other domain.
 */
data class HaEntityCandidate(
    val entityId: String,
    val friendlyName: String?,
    val attributes: HelperAttributes? = null,
)

/**
 * #113/#114 (C29b) — what a listed helper reports about its own shape, and nothing else: an `input_select`'s
 * [options] (at most [MAX_LISTED_OPTIONS] strings, each bounded like an id; null when Home Assistant reported none,
 * more, or anything but strings) or an `input_datetime`'s [hasDate] / [hasTime] (null when absent or not a JSON
 * boolean). The other domain's members are always null. Never a state: a helper is listed for what it is.
 */
data class HelperAttributes(val options: List<String>?, val hasDate: Boolean?, val hasTime: Boolean?)

/** #113/#114 (C29b) — the most options a listed dropdown may report; past it the list is `null`, never cut. */
const val MAX_LISTED_OPTIONS = 64

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
        val attributes = entity["attributes"] as? JsonObject
        val name = attributes
            ?.stringOrNull("friendly_name")
            ?.let { bounded(it, MAX_HA_NAME_LENGTH) }
            ?.takeIf { it.isNotBlank() }
        entities += HaEntityCandidate(id, name, helperAttributesOf(id, attributes))
    }
    return HaListOutcome.Listed(entities)
}

/**
 * #113/#114 (C29b) — a helper's shape for the two domains a publication writes, and null for every other id: an
 * `input_select` keeps its options (C16's bounds, but never cut: more than [MAX_LISTED_OPTIONS], none, or anything
 * but strings is null), an `input_datetime` its `has_date` / `has_time` JSON booleans. Exhaustive over the resolved
 * [EntityScope], so a scope added later must say what shape it keeps (A4w review N3).
 */
private fun helperAttributesOf(id: String, attributes: JsonObject?): HelperAttributes? =
    when (EntityScope.entries.firstOrNull { it.domain == id.substringBefore('.') }) {
        EntityScope.INPUT_SELECTS -> HelperAttributes(attributes?.let(::listedOptionsOf), null, null)
        EntityScope.INPUT_DATETIMES ->
            HelperAttributes(null, attributes?.flagOrNull("has_date"), attributes?.flagOrNull("has_time"))
        EntityScope.INPUT_BOOLEANS, null -> null
    }

private fun listedOptionsOf(attributes: JsonObject): List<String>? {
    val array = attributes["options"] as? JsonArray ?: return null
    if (array.isEmpty() || array.size > MAX_LISTED_OPTIONS) return null
    return array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::bounded) ?: return null }
}

/** A JSON boolean member; null when absent or anything else (a string `"true"` included). */
private fun JsonObject.flagOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** One JSON array, or null for anything else: an object, a scalar, or text that does not parse. */
private fun parseArray(body: ByteArray): JsonArray? = try {
    Json.parseToJsonElement(body.decodeToString()) as? JsonArray
} catch (e: IllegalArgumentException) {
    // kotlinx.serialization's SerializationException is an IllegalArgumentException.
    null
}
