package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.NoDecision
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome.Observed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * #16 (C5) — one HTTP answer from `GET /api/states/<entity_id>`, as the client (C19) received it: [body] is read
 * only on a 200 and capped, and [truncated] says the cap cut it. A transport failure never becomes one of these;
 * the client maps it before the mapper runs.
 */
class HaHttpAnswer(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    val truncated: Boolean,
) {
    /** No body: what HA sent is not a log line. */
    override fun toString(): String = "HaHttpAnswer(status=$status, truncated=$truncated)"
}

/** #16 (C5 rule 4) — the longest `detail` or `haLastChanged` text kept from an answer. */
const val MAX_HA_TEXT_LENGTH = 64

private val MALFORMED = NoDecision(SyncErrorKind.MALFORMED, null)

/**
 * #16 (C5) — the issue's exact-state rule, pure. In order:
 * 1. 401 or 403 → [SyncErrorKind.AUTH_REFUSED]; 404 → [SyncErrorKind.ENTITY_NOT_FOUND]; 300–399 →
 *    [SyncErrorKind.REDIRECTED]; any other status but 200 → [SyncErrorKind.HTTP_ERROR] with the status as `detail`.
 * 2. A 200 that was [HaHttpAnswer.truncated], whose content type is not `application/json` (parameters allowed),
 *    whose body is not one JSON object, whose `entity_id` is not exactly [requestedEntity], or whose `state` is not
 *    a JSON string → [SyncErrorKind.MALFORMED].
 * 3. `state` exactly `"on"` → [Observed] ON; exactly `"off"` → [Observed] OFF. Case-sensitive and never trimmed:
 *    every other string — `"On"`, `" on"`, `"true"`, `"unknown"`, `"unavailable"`, `""` — is
 *    [SyncErrorKind.UNSUPPORTED_STATE] with that string as `detail`.
 * 4. `detail` and `haLastChanged` keep at most [MAX_HA_TEXT_LENGTH] characters, each control character replaced
 *    by `?`. `last_changed` stays text: no decision reads it as a date (R16-3).
 */
fun mapHaAnswer(answer: HaHttpAnswer, requestedEntity: String): HaReadOutcome {
    val status = answer.status
    if (status == 401 || status == 403) return NoDecision(SyncErrorKind.AUTH_REFUSED, null)
    if (status == 404) return NoDecision(SyncErrorKind.ENTITY_NOT_FOUND, null)
    if (status in 300..399) return NoDecision(SyncErrorKind.REDIRECTED, null)
    if (status != 200) return NoDecision(SyncErrorKind.HTTP_ERROR, status.toString())

    if (answer.truncated) return MALFORMED
    if (!isJsonMediaType(answer.contentType)) return MALFORMED
    val entity = parseObject(answer.body) ?: return MALFORMED
    if (entity.stringOrNull("entity_id") != requestedEntity) return MALFORMED
    val state = entity.stringOrNull("state") ?: return MALFORMED
    val haLastChanged = entity.stringOrNull("last_changed")?.let(::bounded)

    return when {
        state == "on" -> Observed(HaSwitchState.ON, haLastChanged)
        state == "off" -> Observed(HaSwitchState.OFF, haLastChanged)
        else -> NoDecision(SyncErrorKind.UNSUPPORTED_STATE, bounded(state))
    }
}

/** The media type alone, before any parameter; media types compare without regard to case (RFC 9110 §8.3.1). */
private fun isJsonMediaType(contentType: String?): Boolean =
    contentType != null && contentType.substringBefore(';').trim().lowercase() == "application/json"

/** One JSON object, or null for anything else: an array, a scalar, or text that does not parse. */
private fun parseObject(body: ByteArray): JsonObject? = try {
    Json.parseToJsonElement(body.decodeToString()) as? JsonObject
} catch (e: IllegalArgumentException) {
    // kotlinx.serialization's SerializationException is an IllegalArgumentException.
    null
}

/** The member's text when it is a JSON string; null when it is absent, `null`, a number, a boolean or a container. */
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** C5 rule 4: at most [MAX_HA_TEXT_LENGTH] characters (never half a surrogate pair), control characters as `?`. */
private fun bounded(text: String): String {
    var kept = text
    if (kept.length > MAX_HA_TEXT_LENGTH) {
        kept = kept.substring(0, MAX_HA_TEXT_LENGTH)
        if (kept.last().isHighSurrogate()) kept = kept.dropLast(1)
    }
    return buildString(kept.length) {
        for (c in kept) append(if (c.isISOControl()) '?' else c)
    }
}
