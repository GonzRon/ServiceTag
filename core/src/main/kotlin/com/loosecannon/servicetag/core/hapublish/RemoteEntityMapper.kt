package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.seasonsync.HaHttpAnswer
import com.loosecannon.servicetag.core.seasonsync.bounded
import com.loosecannon.servicetag.core.seasonsync.isJsonMediaType
import com.loosecannon.servicetag.core.seasonsync.parseObject
import com.loosecannon.servicetag.core.seasonsync.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** #113/#114 (C16) — the most options kept from one helper's answer. */
const val MAX_HA_OPTIONS = 64

/**
 * #113/#114 (C16) — one helper as a read saw it: its [state] (bounded), an `input_select`'s [options] (at most
 * [MAX_HA_OPTIONS], each bounded; null when absent) and an `input_datetime`'s [hasDate] / [hasTime] flags (null when
 * absent or not a boolean).
 */
data class RemoteEntity(
    val entityId: String,
    val state: String,
    val options: List<String>?,
    val hasDate: Boolean?,
    val hasTime: Boolean?,
)

/** #113/#114 (C16) — what one `GET /api/states/<entity_id>` of a helper came to. */
sealed interface RemoteRead {
    data class Answered(val remote: RemoteEntity) : RemoteRead

    /** No answer to use. [detail] is an HTTP status at most; never a URL, a body or a token. */
    data class Failed(val kind: PublishErrorKind, val detail: String?) : RemoteRead
}

private val MALFORMED = RemoteRead.Failed(PublishErrorKind.MALFORMED, null)

/**
 * #113/#114 (C16) — a helper's GET answer, beside #16's on/off mapper and reusing its JSON helpers. In order:
 * 401 and 403 → `AUTH_REFUSED` (a GET's rule; a write's 403 is the client's `WRITE_FORBIDDEN`); 404 →
 * `ENTITY_NOT_FOUND`; 300–399 → `REDIRECTED`; any other status but 200 → `HTTP_ERROR` with the code; a 200 that was
 * truncated, is not JSON, is not one object, answers another entity or has a non-string state → `MALFORMED`.
 */
fun mapRemoteEntity(answer: HaHttpAnswer, requestedEntity: String): RemoteRead {
    val status = answer.status
    if (status == 401 || status == 403) return RemoteRead.Failed(PublishErrorKind.AUTH_REFUSED, null)
    if (status == 404) return RemoteRead.Failed(PublishErrorKind.ENTITY_NOT_FOUND, null)
    if (status in 300..399) return RemoteRead.Failed(PublishErrorKind.REDIRECTED, null)
    if (status != 200) return RemoteRead.Failed(PublishErrorKind.HTTP_ERROR, status.toString())

    if (answer.truncated) return MALFORMED
    if (!isJsonMediaType(answer.contentType)) return MALFORMED
    val entity = parseObject(answer.body) ?: return MALFORMED
    if (entity.stringOrNull("entity_id") != requestedEntity) return MALFORMED
    val state = entity.stringOrNull("state") ?: return MALFORMED
    val attributes = entity["attributes"] as? JsonObject

    return RemoteRead.Answered(
        RemoteEntity(
            entityId = requestedEntity,
            state = bounded(state),
            options = attributes?.let(::optionsOf),
            hasDate = attributes?.flag("has_date"),
            hasTime = attributes?.flag("has_time"),
        ),
    )
}

/**
 * `attributes.options` when it is an array of strings: the first [MAX_HA_OPTIONS], each bounded. Anything else — absent,
 * not an array, or holding a non-string — is null, which no status validator accepts.
 */
private fun optionsOf(attributes: JsonObject): List<String>? {
    val array = attributes["options"] as? JsonArray ?: return null
    val texts = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
    return texts.take(MAX_HA_OPTIONS).map { bounded(it) }
}

/** A JSON boolean member; null when absent or anything else (a string `"true"` included). */
private fun JsonObject.flag(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
