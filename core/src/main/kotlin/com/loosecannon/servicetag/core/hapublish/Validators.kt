package com.loosecannon.servicetag.core.hapublish

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * #113/#114 (C16) — the domain rule, from the id alone and before any read: the part before the dot must be the
 * domain of [kind]'s one action ([actionOf]). Null when it holds.
 */
fun domainRefusalOf(kind: PublishKind, entityId: String): PublishErrorKind? =
    if (entityId.substringBefore('.', missingDelimiterValue = "") == actionOf(kind).domain) {
        null
    } else {
        PublishErrorKind.WRONG_DOMAIN
    }

/**
 * #113 (C16) — an equipment-status helper: an `input_select` whose options, as a set, are exactly `UP`, `DOWN`
 * and `DEGRADED` (case-sensitive, nothing extra, any order). Null when it holds.
 */
fun validateStatusTarget(entityId: String, remote: RemoteEntity): PublishErrorKind? {
    domainRefusalOf(PublishKind.EQUIPMENT_STATUS, entityId)?.let { return it }
    val options = remote.options ?: return PublishErrorKind.OPTIONS_MISMATCH
    return if (options.toSet() == STATUS_OPTIONS) null else PublishErrorKind.OPTIONS_MISMATCH
}

/**
 * #114 (C16) — a service-date helper, and a season-ended helper (C9a): a date-only `input_datetime`
 * (`has_date` true, `has_time` false). Null when it holds.
 */
fun validateDateTarget(entityId: String, remote: RemoteEntity): PublishErrorKind? {
    domainRefusalOf(PublishKind.SEASON_SERVICE_DATE, entityId)?.let { return it }
    return if (remote.hasDate == true && remote.hasTime == false) null else PublishErrorKind.NOT_DATE_ONLY
}

/** [kind]'s validator. */
fun validateTarget(kind: PublishKind, entityId: String, remote: RemoteEntity): PublishErrorKind? = when (kind) {
    PublishKind.EQUIPMENT_STATUS -> validateStatusTarget(entityId, remote)
    PublishKind.SEASON_SERVICE_DATE -> validateDateTarget(entityId, remote)
}

/** What checking one helper came to: the read entity, or why it may not be used. */
sealed interface TargetCheck {
    data class Valid(val remote: RemoteEntity) : TargetCheck

    data class Refused(val kind: PublishErrorKind, val detail: String?) : TargetCheck
}

/**
 * #113/#114 (C16) — check [entityId] as [kind]'s helper: the domain rule first, so a wrong id is refused without
 * any request; then one [read] and [kind]'s validator.
 */
inline fun checkTarget(kind: PublishKind, entityId: String, read: (String) -> RemoteRead): TargetCheck {
    domainRefusalOf(kind, entityId)?.let { return TargetCheck.Refused(it, null) }
    return when (val answer = read(entityId)) {
        is RemoteRead.Failed -> TargetCheck.Refused(answer.kind, answer.detail)
        is RemoteRead.Answered ->
            validateTarget(kind, entityId, answer.remote)
                ?.let { TargetCheck.Refused(it, null) }
                ?: TargetCheck.Valid(answer.remote)
    }
}

/** #114 (C9a) — a season-ended helper's state as a date when it is exactly `YYYY-MM-DD`; null otherwise. */
fun boundaryDateOf(remote: RemoteEntity): LocalDate? = isoDayOrNull(remote.state)

private val ISO_DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** Exactly `YYYY-MM-DD` and a real day, else null (`unknown`, `unavailable`, a time, `2026-02-30`). */
internal fun isoDayOrNull(text: String): LocalDate? {
    if (!ISO_DAY.matches(text)) return null
    return try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }
}

/** #113/#114 (C16) — exact string equality of the helper's state with the desired option or `YYYY-MM-DD`. */
fun remoteMatches(kind: PublishKind, remote: RemoteEntity, desired: String): Boolean = when (kind) {
    PublishKind.EQUIPMENT_STATUS -> remote.state == desired
    PublishKind.SEASON_SERVICE_DATE -> remote.state == desired
}
