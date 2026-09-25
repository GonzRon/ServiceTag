package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.BackupCorrupt
import com.loosecannon.servicetag.core.backup.BackupNewerFormat
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.usecase.AssetCycle
import com.loosecannon.servicetag.core.usecase.AssetHasChildren
import com.loosecannon.servicetag.core.usecase.AssetMembershipReferenced
import com.loosecannon.servicetag.core.usecase.AssetValidation
import com.loosecannon.servicetag.core.usecase.BadScheduleDate
import com.loosecannon.servicetag.core.usecase.BreakStrandsPolicy
import com.loosecannon.servicetag.core.usecase.CloseNotSupported
import com.loosecannon.servicetag.core.usecase.ClosedOnOutOfRange
import com.loosecannon.servicetag.core.usecase.ConditionProblem
import com.loosecannon.servicetag.core.usecase.ConditionValidation
import com.loosecannon.servicetag.core.usecase.DefinitionInUse
import com.loosecannon.servicetag.core.usecase.DefinitionValidation
import com.loosecannon.servicetag.core.usecase.DefinitionWouldBreakDerived
import com.loosecannon.servicetag.core.usecase.DefinitionWouldBreakProfiles
import com.loosecannon.servicetag.core.usecase.EventOwnership
import com.loosecannon.servicetag.core.usecase.EventValidation
import com.loosecannon.servicetag.core.usecase.GroupCompletionNotSupported
import com.loosecannon.servicetag.core.usecase.GroupProblem
import com.loosecannon.servicetag.core.usecase.GroupValidation
import com.loosecannon.servicetag.core.usecase.HealthProblem
import com.loosecannon.servicetag.core.usecase.HealthScheduleTaken
import com.loosecannon.servicetag.core.usecase.HealthSubjectIsPrimary
import com.loosecannon.servicetag.core.usecase.HealthValidation
import com.loosecannon.servicetag.core.usecase.LegacyWriteCannotRepresent
import com.loosecannon.servicetag.core.usecase.MemberCompletionNotSupported
import com.loosecannon.servicetag.core.usecase.MergePlanStale
import com.loosecannon.servicetag.core.usecase.MergeRefused
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchDefinition
import com.loosecannon.servicetag.core.usecase.NoSuchEvent
import com.loosecannon.servicetag.core.usecase.NoSuchGroup
import com.loosecannon.servicetag.core.usecase.NoSuchHealthSubject
import com.loosecannon.servicetag.core.usecase.NoSuchProfile
import com.loosecannon.servicetag.core.usecase.NoSuchSchedule
import com.loosecannon.servicetag.core.usecase.NotAGroupMember
import com.loosecannon.servicetag.core.usecase.NotARequiredMember
import com.loosecannon.servicetag.core.usecase.OccurrenceAlreadyClosed
import com.loosecannon.servicetag.core.usecase.OccurrenceAlreadyComplete
import com.loosecannon.servicetag.core.usecase.OccurrenceClosed
import com.loosecannon.servicetag.core.usecase.OccurrenceNotActionable
import com.loosecannon.servicetag.core.usecase.OccurrenceNotCloseable
import com.loosecannon.servicetag.core.usecase.OccurrenceNotYetOpen
import com.loosecannon.servicetag.core.usecase.PreServiceNeedsDates
import com.loosecannon.servicetag.core.usecase.ProfileValidation
import com.loosecannon.servicetag.core.usecase.ReferenceProblem
import com.loosecannon.servicetag.core.usecase.ScheduleArchived
import com.loosecannon.servicetag.core.usecase.ScheduleDrivesHealthSubject
import com.loosecannon.servicetag.core.usecase.ScheduleProblem
import com.loosecannon.servicetag.core.usecase.ScheduleValidation
import com.loosecannon.servicetag.core.usecase.SeasonAlreadyEnded
import com.loosecannon.servicetag.core.usecase.SeasonAlreadyStarted
import com.loosecannon.servicetag.core.usecase.SeasonModeStrandsPolicy
import com.loosecannon.servicetag.core.usecase.SeasonNotManual
import com.loosecannon.servicetag.core.usecase.SeasonProblem
import com.loosecannon.servicetag.core.usecase.SeasonValidation
import com.loosecannon.servicetag.core.usecase.StrandedSchedule
import com.loosecannon.servicetag.core.usecase.UnknownTemplate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/** The `/v1` contract's version, reported by `GET /v1/status`. Bumped only by a breaking change. */
internal const val API_VERSION: Int = 1

/**
 * Strict on the way in, complete on the way out.
 *
 * `ignoreUnknownKeys = false` so a misspelled field is a 400 naming it, rather than an intent
 * silently dropped — on a `PATCH` that is the difference between an error and a lost edit.
 * `encodeDefaults = true` matches `BackupCodec`'s own `Json` (`BackupCodec.kt:54`–`57`), so every
 * field is present in every response and a client never distinguishes absent from default.
 * `prettyPrint` is off: this goes over a socket, not into a file a person reads.
 */
internal val ApiJson: Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    prettyPrint = false
}

@Serializable
internal data class ApiErrorBody(val error: ApiErrorDetail)

/**
 * [code] is stable and machine-readable; [message] is for a person; [problems] lists every problem by
 * the domain's own name, and on a validation failure [code], [message] and [field] describe the first.
 *
 * [field] is the one body key a refusal is about, and null where a refusal is not about exactly one
 * key. The 1.4 codes master plan §11.3 names fill it (1.4, spec §9.2) — `POLICY_OFFSET_INVALID` is
 * `policyOffsetDays`, `HEALTH_SUBJECT_NAME_REQUIRED` is `name`, and so on — and so do the four 1.1.0
 * validation families (#52, `ValidationRefusals.kt`) and the 1.4 malformed-value rows, from the key
 * their problem already carries. One key names that key, a pair names its first key, and a reading's
 * id without the request key that sent it answers null. It is encoded like every other field, so a
 * client reads `null` rather than an absent key.
 */
@Serializable
internal data class ApiErrorDetail(
    val code: String,
    val message: String,
    val problems: List<String> = emptyList(),
    val field: String? = null,
)

/**
 * A [ReferenceProblem] on its way to a status code.
 *
 * `ReferenceResult` is a **value** and not an exception — every one of its refusals is something a
 * caller draws or maps — so [ReferenceHandlers] wraps the ones it cannot answer itself in this,
 * and **this file stays the only place a `ReferenceProblem` becomes a status**. The message is the
 * problem's class name and never its data: `UnknownSchemeNeedsConfirmation` carries the scheme it
 * refused, and no refusal on this wire may name a scheme, a URI, an authority or a path (spec
 * §4.4). It never reaches a response either way — [mapDomainFailure] writes its own sentence.
 */
internal class ReferenceRefused(val problem: ReferenceProblem) :
    Exception(problem::class.simpleName)

/** A refusal a handler or the router raises deliberately, carrying the status it means. */
internal class ApiFailure(
    val status: Int,
    val reason: String,
    val code: String,
    message: String,
    val problems: List<String> = emptyList(),
    /** The body key the refusal is about, when there is exactly one (see [ApiErrorDetail.field]). */
    val field: String? = null,
) : Exception(message) {
    companion object {
        fun badRequest(message: String) = ApiFailure(400, "Bad Request", "bad_request", message)

        fun notFound(what: String) =
            ApiFailure(404, "Not Found", "not_found", "nothing here answers $what")

        fun methodNotAllowed(method: String, path: String) =
            ApiFailure(405, "Method Not Allowed", "method_not_allowed", "$method is not allowed on $path")

        fun unsupportedMediaType(expected: String, found: String?) = ApiFailure(
            415, "Unsupported Media Type", "unsupported_media_type",
            "this endpoint wants $expected, not ${found ?: "an unnamed type"}",
        )
    }
}

internal fun errorResponse(
    status: Int,
    reason: String,
    code: String,
    message: String,
    problems: List<String> = emptyList(),
    field: String? = null,
): ApiResponse = ApiResponse.json(
    status, reason,
    ApiJson.encodeToString(
        ApiErrorBody.serializer(),
        ApiErrorBody(ApiErrorDetail(code, message, problems, field)),
    ),
)

internal const val JSON_MEDIA_TYPE: String = "application/json"
internal const val ZIP_MEDIA_TYPE: String = "application/zip"

/*
 * The three answers and the one body-reader every handler shares.
 *
 * Top-level `internal` rather than private members of one class, because 1.2 added a second handler
 * class beside `ApiHandlers` and two copies of "what a 200 looks like" is exactly how two endpoints
 * of one API come to disagree about their envelope.
 */

internal fun <T> ok(serializer: SerializationStrategy<T>, value: T): ApiResponse =
    ApiResponse.json(200, "OK", ApiJson.encodeToString(serializer, value))

/** Named `createdResponse`, not `created`, so it cannot be misread as a local `val`. */
internal fun <T> createdResponse(serializer: SerializationStrategy<T>, value: T): ApiResponse =
    ApiResponse.json(201, "Created", ApiJson.encodeToString(serializer, value))

internal fun <T> conflictResponse(serializer: SerializationStrategy<T>, value: T): ApiResponse =
    ApiResponse.json(409, "Conflict", ApiJson.encodeToString(serializer, value))

/**
 * The body as JSON, or a 415 for the wrong type and a 400 for the wrong shape.
 *
 * The decoder's own message is passed through, because with `ignoreUnknownKeys = false` that
 * message is what names the misspelled field — the difference between a caller fixing a typo and a
 * caller guessing. What it echoes is the caller's own body, back to the caller, over this phone's
 * loopback address; nothing about the phone's data is in it.
 */
internal fun <T> ApiRequest.decode(serializer: DeserializationStrategy<T>): T {
    val type = mediaType()
    if (type != JSON_MEDIA_TYPE) throw ApiFailure.unsupportedMediaType(JSON_MEDIA_TYPE, type)
    return decodeOr400(serializer, body.decodeToString())
}

/** [text] as [serializer]'s value, or the shipped 400 carrying the decoder's own message. */
internal fun <T> decodeOr400(serializer: DeserializationStrategy<T>, text: String): T = try {
    ApiJson.decodeFromString(serializer, text)
} catch (e: SerializationException) {
    throw ApiFailure.badRequest(e.message ?: "that is not the JSON this endpoint wants")
}

/**
 * Every refusal `:core` can raise, given the status it means.
 *
 * 422 is a *validation* failure — the body is wrong, and `problems` lists every problem, using the
 * sealed problem types' own `toString()` so the names in the JSON are the names in the code; `code`,
 * `message` and `field` describe the first.
 * 409 is a refusal about *state*: the row is fine and the store will not have it (a cycle, a
 * definition that already has measurements, a derived reading that would break). 404 is a row that
 * is not there. 400 is a caller error that is not about a field. Anything left is a 500 carrying
 * the exception's class name and nothing else — no message, because an unanticipated message is the
 * one place a path or a value could leak into a response.
 */
internal fun mapDomainFailure(e: Exception): ApiResponse = when (e) {
    // #52: the four 1.1.0 families keep their code and their `problems`, and describe the first
    // problem in `message` and `field` (`ValidationRefusals.kt`). The shipped sentence is kept only
    // as the fallback for a refusal that named no problem, unreachable as `SCHEDULE_INVALID` is:
    // `mapDomainFailure` runs inside the router's `catch`, so a throw here would escape `handle`.
    is AssetValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::assetRefusal) ?: Refusal(ASSET_VALIDATION, "the asset was refused"),
        e.problems.map { it.toString() },
    )
    is EventValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::eventRefusal) ?: Refusal(EVENT_VALIDATION, "the event was refused"),
        e.problems.map { it.toString() },
    )
    is DefinitionValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::definitionRefusal) ?: Refusal(DEFINITION_VALIDATION, "the reading was refused"),
        e.problems.map { it.toString() },
    )
    is ProfileValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::profileRefusal) ?: Refusal(PROFILE_VALIDATION, "the quick action was refused"),
        e.problems.map { it.toString() },
    )
    // --- 1.2, the maintenance domain (master plan §9.3's status split) ---------------------
    //
    // Two things are different about these rows, and both are the contract's, not a style choice.
    //
    // **The code is the UPPER_SNAKE name §9.3 fixes**, not a lower-cased one in 1.1.0's style: the
    // plan names each of them as the stable string a client branches on to tell "already done" from
    // "broken", and `docs/api/v1.md` records the two spellings side by side rather than inventing a
    // third. **A validation failure's code is its *first* problem's**, which is deterministic
    // because `scheduleProblems` and `SaveGroup.problemsOf` collect in a fixed order that checks
    // the target's own shape first — and `problems` still carries every problem by the domain's own
    // name, exactly as the shipped rows do.
    is ScheduleValidation -> errorResponse(
        422, "Unprocessable Content",
        // `SCHEDULE_INVALID` is the fallback for a refusal that named no problem. It is
        // unreachable — every throw site collects at least one — and it is documented in
        // `docs/api/v1.md` anyway, because it is still a value `error.code` can carry.
        e.problems.firstOrNull()?.let(::scheduleProblemCode) ?: "SCHEDULE_INVALID",
        "the schedule was refused",
        e.problems.map { it.toString() },
        // 1.4: the one schedule problem master plan §11.3 names a key for.
        field = if (e.problems.firstOrNull() == ScheduleProblem.PolicyOffsetInvalid) "policyOffsetDays" else null,
    )
    is GroupValidation -> errorResponse(
        422, "Unprocessable Content",
        // `GROUP_INVALID`, the same fallback for the same reason. Also documented.
        e.problems.firstOrNull()?.let(::groupProblemCode) ?: "GROUP_INVALID",
        "the group was refused",
        e.problems.map { it.toString() },
    )
    is NoSuchSchedule -> errorResponse(404, "Not Found", "NO_SUCH_SCHEDULE", "no such schedule")
    is NoSuchGroup -> errorResponse(404, "Not Found", "NO_SUCH_GROUP", "no such group")
    is ScheduleArchived -> errorResponse(
        409, "Conflict", "SCHEDULE_ARCHIVED", "this schedule is archived",
    )
    is OccurrenceClosed -> errorResponse(
        409, "Conflict", "OCCURRENCE_CLOSED",
        "this round was closed, so no completion may claim it",
    )
    is OccurrenceNotActionable -> errorResponse(
        409, "Conflict", "OCCURRENCE_NOT_ACTIONABLE", "this round obliges nobody",
    )
    is CloseNotSupported -> errorResponse(
        409, "Conflict", "CLOSE_NOT_SUPPORTED", "closing a round is offered on group targets only",
    )
    is OccurrenceAlreadyClosed -> errorResponse(
        409, "Conflict", "OCCURRENCE_ALREADY_CLOSED", "this round is already closed",
    )
    is OccurrenceAlreadyComplete -> errorResponse(
        409, "Conflict", "OCCURRENCE_ALREADY_COMPLETE", "this round is already complete",
    )
    is OccurrenceNotCloseable -> errorResponse(
        409, "Conflict", "OCCURRENCE_NOT_CLOSEABLE", "a round that obliges nobody cannot be closed",
    )
    is OccurrenceNotYetOpen -> errorResponse(
        409, "Conflict", "OCCURRENCE_NOT_YET_OPEN",
        "this round has not reached its due-soon window, so there is nothing to abandon yet",
        // Both fields, deliberately: with no occurrence key on the request (the owner's ruling), this
        // is the only way a retrying client can tell "my first call succeeded and the schedule
        // advanced" (occurrenceOn is ahead of what it last saw) apart from "it was simply not due yet"
        // (occurrenceOn is unchanged).
        listOf("OccurrenceNotYetOpen(occurrenceOn=${e.occurrenceOn}, opensOn=${e.opensOn})"),
    )
    is ClosedOnOutOfRange -> errorResponse(
        422, "Unprocessable Content", "CLOSED_ON_OUT_OF_RANGE",
        "closedOn must be from the round's open date through today, inclusive",
        listOf("ClosedOnOutOfRange(earliestOn=${e.earliestOn}, today=${e.today})"),
    )
    is BadScheduleDate -> errorResponse(
        422, "Unprocessable Content", "BAD_DATE", "that is not an ISO YYYY-MM-DD date",
    )
    // Both are the completion command's `assetId` naming the wrong asset, so both are 422 and not
    // 409: what was asked for describes a completion that cannot exist (§9.2's "validated as a
    // required member"). They stay two codes because they are two different facts — never in this
    // group, and not in it when this round opened — and a surface that cannot tell them apart will
    // eventually tell an owner the wrong one.
    is NotAGroupMember -> errorResponse(
        422, "Unprocessable Content", "NOT_A_GROUP_MEMBER",
        "that asset is not a member of this schedule's group",
    )
    is NotARequiredMember -> errorResponse(
        422, "Unprocessable Content", "NOT_A_REQUIRED_MEMBER",
        "that asset is not required for this round",
    )
    // Defence in depth: `MaintenanceHandlers.completeSchedule` dispatches on the schedule's target,
    // so neither of these can be reached through a route. If one ever is, it must not be a 500.
    is GroupCompletionNotSupported -> errorResponse(
        409, "Conflict", "GROUP_COMPLETION_NOT_SUPPORTED",
        "this schedule targets a group, so a completion has to name a member",
    )
    is MemberCompletionNotSupported -> errorResponse(
        409, "Conflict", "MEMBER_COMPLETION_NOT_SUPPORTED",
        "this schedule targets an asset, not a group",
    )
    // Invariant 8's refusal, raised by `DeleteAsset`: a membership row may not be hard-deleted
    // while a completion or a closure references an occurrence its window covered. **No route calls
    // `DeleteAsset`** — deleting an asset has no endpoint — so this is defence in depth, and it
    // takes 1.2's UPPER_SNAKE spelling because the rule it enforces is 1.2's, not 1.1.0's.
    is AssetMembershipReferenced -> errorResponse(
        409, "Conflict", "ASSET_MEMBERSHIP_REFERENCED",
        "this asset is still a member of a group whose history references it",
    )
    is NoSuchAsset -> errorResponse(404, "Not Found", "no_such_asset", "no such asset")
    is NoSuchEvent -> errorResponse(404, "Not Found", "no_such_event", "no such event")
    is NoSuchDefinition -> errorResponse(404, "Not Found", "no_such_definition", "no such reading")
    is NoSuchProfile -> errorResponse(404, "Not Found", "no_such_profile", "no such quick action")
    is AssetCycle -> errorResponse(
        409, "Conflict", "asset_cycle", "that parent is already part of this asset",
    )
    is AssetHasChildren -> errorResponse(
        409, "Conflict", "asset_has_children", "this asset still has components",
    )
    is EventOwnership -> errorResponse(
        409, "Conflict", "ownership", "that row belongs to a different asset",
    )
    is DefinitionInUse -> errorResponse(
        409, "Conflict", "definition_in_use", "this reading already has measurements",
    )
    is DefinitionWouldBreakDerived -> errorResponse(
        409, "Conflict", "would_break_derived", "another derived reading reads this one",
    )
    is DefinitionWouldBreakProfiles -> errorResponse(
        409, "Conflict", "would_break_profiles", "a quick action offers this reading as a field",
    )
    is UnknownTemplate -> errorResponse(400, "Bad Request", "unknown_template", "no such template")
    is BackupNewerFormat -> errorResponse(
        409, "Conflict", "archive_newer_format", "that archive was written by a newer build",
    )
    is BackupCorrupt -> errorResponse(
        400, "Bad Request", "archive_corrupt", "that archive could not be read",
    )
    is StoreIoException -> errorResponse(
        409, "Conflict", "store_unavailable", "the attachment folder is not available",
    )
    // Defence in depth only: `ApiHandlers.importMergeApply` catches both of these itself, because
    // it can attach the report and its deterministic conflict list to the 409. If one ever reaches
    // here it still must not be a 500, and it still must not claim anything was written.
    is MergeRefused -> errorResponse(
        409, "Conflict", "merge_conflicts",
        "the merge was refused; nothing was written",
        e.report.conflicts.map { "${it.table.name}:${it.id}:${it.reason.name}" },
    )
    is MergePlanStale -> errorResponse(
        409, "Conflict", "merge_plan_stale",
        "this phone changed since the plan was built; nothing was written",
    )
    // --- 1.3, the reference domain (master plan §7) -----------------------------------------
    //
    // Nine arms over one exhaustive `when`, so a member added to `ReferenceProblem` later is a
    // **compile error here** rather than a refusal carrying a code nobody documented. The code is
    // [referenceProblemCode]'s, which is exhaustive for the same reason; only the status and the
    // sentence are chosen here.
    //
    // `problems` is deliberately empty on every one of them, unlike the validation rows above:
    // `UnknownSchemeNeedsConfirmation` carries the scheme it refused, and no refusal on this wire
    // may name a scheme, a URI, an authority or a path (spec §4.4). The sentences below name a
    // field or a rule and never a value.
    is ReferenceRefused -> when (val problem = e.problem) {
        ReferenceProblem.NoSuchReference ->
            referenceError(404, "Not Found", problem, "no such reference")
        // §18.10: an absent owner is the fact the shipped 1.1.0 code already names, so it answers
        // that code rather than a second UPPER_SNAKE twin for one condition.
        ReferenceProblem.OwnerMissing ->
            referenceError(404, "Not Found", problem, "no such asset")
        ReferenceProblem.NotALink ->
            referenceError(422, "Unprocessable Content", problem, "that is not a link")
        ReferenceProblem.UriTooLong ->
            referenceError(422, "Unprocessable Content", problem, "that link is too long")
        ReferenceProblem.BlankName ->
            referenceError(422, "Unprocessable Content", problem, "a reference needs a name")
        ReferenceProblem.SchemeBlocked ->
            referenceError(422, "Unprocessable Content", problem, "that kind of link is not saved")
        // §18.12: there is nobody on the wire to confirm and the API never sets
        // `confirmedUnknownScheme` (§18.2), so an unfamiliar scheme is refused exactly as a
        // hard-blocked one is — spec §6's "a refusal, never a confirmation, over the API".
        is ReferenceProblem.UnknownSchemeNeedsConfirmation ->
            referenceError(422, "Unprocessable Content", problem, "that kind of link is not saved")
        ReferenceProblem.DuplicateUri ->
            referenceError(409, "Conflict", problem, "this asset already holds that link")
        // Defence in depth, and the only arm here that no route reaches: a no-op amend is a **200
        // carrying the stored row** (§18.13), answered in `ReferenceHandlers.update` before
        // anything is wrapped. If it ever arrives here it still must not be a 500, and
        // `REFERENCE_UNCHANGED` is therefore in no `docs/api/v1.md` table: the document lists the
        // five codes a client can actually receive.
        ReferenceProblem.Unchanged ->
            referenceError(409, "Conflict", problem, "this reference already says that")
    }
    // --- 1.4, seasons, policy, condition and health (spec §9.2; master plan §11.5) -----------
    //
    // **The 422/409 tie-break (RN-8):** a 422 means the remedy is to change *this body*, whatever
    // the stored state; a 409 means the remedy is to change *another row first*. So a strands
    // refusal is a 409 (the remedy is the schedule's policy), `SCHEDULE_DRIVES_HEALTH_SUBJECT` a 422
    // (the remedy is the `unlinkHealthSubject` flag in this very body), and a legacy write that
    // cannot be represented a 422 (the remedy is sending the 1.4 form).
    //
    // A validation failure's code is its **first** problem's, as 1.2's are, and `problems` still
    // carries every problem by the domain's own name. The three problem-to-code functions below are
    // exhaustive over their sealed types, so a problem added later is a compile error here rather
    // than a refusal with a code nobody documented.
    //
    // Each falls back to its family's lower-snake validation code for a refusal that named no problem,
    // as 1.2's `SCHEDULE_INVALID` does. It is unreachable — every throw site collects at least one — but
    // `mapDomainFailure` runs inside the router's `catch`, so a throw here would escape `handle`.
    is SeasonValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::seasonRefusal) ?: Refusal(SEASON_VALIDATION, "the season command was refused"),
        e.problems.map { it.toString() },
    )
    is ConditionValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::conditionRefusal) ?: Refusal(CONDITION_VALIDATION, "the condition was refused"),
        e.problems.map { it.toString() },
    )
    is HealthValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::healthRefusal) ?: Refusal(HEALTH_VALIDATION, "the health configuration was refused"),
        e.problems.map { it.toString() },
    )
    is LegacyWriteCannotRepresent -> errorResponse(
        422, "Unprocessable Content", "LEGACY_WRITE_CANNOT_REPRESENT",
        "this asset's season is MANUAL, which a seasonStartMmdd/seasonEndMmdd pair cannot represent; " +
            "send the pair the asset already reports, and change the season through POST /v1/assets/{id}/season-mode",
    )
    is ScheduleDrivesHealthSubject -> errorResponse(
        422, "Unprocessable Content", "SCHEDULE_DRIVES_HEALTH_SUBJECT",
        "a health subject depends on this schedule; send unlinkHealthSubject: true to archive the subject with this change",
        listOf("ScheduleDrivesHealthSubject(subjectId=${e.subjectId.value}, name=${e.name})"),
    )
    is SeasonModeStrandsPolicy -> errorResponse(
        409, "Conflict", "SEASON_MODE_STRANDS_POLICY",
        "a PRE_SERVICE schedule counts back from this season's start; change those schedules' policy first",
        e.schedules.map(::strandedName),
    )
    is BreakStrandsPolicy -> errorResponse(
        409, "Conflict", "BREAK_STRANDS_POLICY",
        "a PRE_SERVICE schedule counts back from this break's start; change those schedules' policy first",
        e.schedules.map(::strandedName),
    )
    is SeasonNotManual -> errorResponse(
        409, "Conflict", "SEASON_NOT_MANUAL", "this asset's season is not MANUAL, so it takes no start or end",
    )
    is SeasonAlreadyStarted -> errorResponse(
        409, "Conflict", "SEASON_ALREADY_STARTED", "this asset's season has already started",
    )
    is SeasonAlreadyEnded -> errorResponse(
        409, "Conflict", "SEASON_ALREADY_ENDED", "this asset's season has already ended",
    )
    is PreServiceNeedsDates -> errorResponse(
        409, "Conflict", "PRE_SERVICE_NEEDS_DATES",
        "this asset has neither a calendar season nor a maintenance break for PRE_SERVICE to count back from",
    )
    is HealthScheduleTaken -> errorResponse(
        409, "Conflict", "HEALTH_SCHEDULE_TAKEN", "that schedule already drives another health subject",
        listOf("HealthScheduleTaken(scheduleId=${e.scheduleId.value}, heldBy=${e.heldBy.value})"),
    )
    is HealthSubjectIsPrimary -> errorResponse(
        409, "Conflict", "HEALTH_SUBJECT_IS_PRIMARY",
        "this subject is the one its asset's TRACK_ONE health follows; change the health policy first",
    )
    is NoSuchHealthSubject -> errorResponse(404, "Not Found", "NO_SUCH_HEALTH_SUBJECT", "no such health subject")
    else -> errorResponse(
        500, "Internal Server Error", "internal", e.javaClass.simpleName,
    )
}

// --- 1.4 ------------------------------------------------------------------------------------------

/** One 1.4 validation problem on its way to the wire: its code, a sentence, and its body key. */
internal data class Refusal(val code: String, val message: String, val field: String? = null)

private fun unprocessable(refusal: Refusal, problems: List<String>): ApiResponse =
    errorResponse(422, "Unprocessable Content", refusal.code, refusal.message, problems, refusal.field)

/** A stranded schedule as a refusal names it: the id to fix, and the title a person recognises. */
private fun strandedName(schedule: StrandedSchedule): String =
    "StrandedSchedule(id=${schedule.id.value}, title=${schedule.title})"

/**
 * The code a malformed value answers with: **the shipped validation shape** (spec §9.2) — 1.1.0's
 * `…_validation` family, whose `problems` name the field (`BadDate(field=seasonStartMmdd)`,
 * `BadTime(field=occurredTime)`, `BadTimeZone(field=tzId)`). Spec §9.2 gives these no code of their
 * own and its 1.4 set is closed, so none is invented for them. Their message stays the family's
 * shipped sentence; since #52 the envelope's `field` carries the key the problem already names.
 */
internal const val SEASON_VALIDATION: String = "season_validation"
internal const val CONDITION_VALIDATION: String = "condition_validation"

/** A health refusal that named no problem: the same unreachable fallback, never a malformed-value code. */
internal const val HEALTH_VALIDATION: String = "health_validation"

/** Every [SeasonProblem] — the season-mode, break and activation commands' — as spec §9.2 codes it. */
internal fun seasonRefusal(problem: SeasonProblem): Refusal = when (problem) {
    SeasonProblem.SeasonWindowRequired -> Refusal(
        "SEASON_WINDOW_REQUIRED", "a CALENDAR season needs both seasonStartMmdd and seasonEndMmdd", "seasonStartMmdd",
    )
    SeasonProblem.SeasonWindowForbidden -> Refusal(
        "SEASON_WINDOW_FORBIDDEN", "only a CALENDAR season takes a window", "seasonStartMmdd",
    )
    SeasonProblem.ManualPhaseRequired -> Refusal(
        "MANUAL_PHASE_REQUIRED", "a switch into MANUAL must say whether the season is running now", "manualPhase",
    )
    SeasonProblem.ManualPhaseForbidden -> Refusal(
        "MANUAL_PHASE_FORBIDDEN", "manualPhase is taken only on a switch into MANUAL from another mode", "manualPhase",
    )
    is SeasonProblem.BadDate -> Refusal(SEASON_VALIDATION, "the season command was refused", problem.field)
    // Phase 1A K7 (ruled 2026-09-25): the key the asset command's own `BothOrNeither` answers (C6); the
    // message stays the shipped one.
    SeasonProblem.BothOrNeither -> Refusal(SEASON_VALIDATION, "the season command was refused", "blackoutStartMmdd")
    SeasonProblem.BlackoutCoversTheYear -> Refusal(
        "BLACKOUT_COVERS_THE_YEAR", "that break leaves some year, common or leap, with no day outside it",
    )
    SeasonProblem.SeasonDateOutOfRange -> Refusal(
        "SEASON_DATE_OUT_OF_RANGE",
        "occurredOn must be no later than today, and on a MANUAL asset no earlier than its latest start or end",
        "occurredOn",
    )
    is SeasonProblem.ForeignEvent -> Refusal("FOREIGN_EVENT", "eventId must name an event of this asset", "eventId")
}

/** Every [ConditionProblem] as spec §9.2 codes it. */
internal fun conditionRefusal(problem: ConditionProblem): Refusal = when (problem) {
    ConditionProblem.DateInFuture -> Refusal(
        "CONDITION_DATE_IN_FUTURE", "occurredOn may not be later than today", "occurredOn",
    )
    is ConditionProblem.ReasonTooLong -> Refusal(
        "CONDITION_REASON_TOO_LONG", "reason may hold at most ${problem.limit} characters", "reason",
    )
    is ConditionProblem.ForeignEvent -> Refusal("FOREIGN_EVENT", "eventId must name an event of this asset", "eventId")
    is ConditionProblem.BadDate -> Refusal(CONDITION_VALIDATION, "the condition was refused", problem.field)
    is ConditionProblem.BadTime -> Refusal(CONDITION_VALIDATION, "the condition was refused", problem.field)
    is ConditionProblem.BadTimeZone -> Refusal(CONDITION_VALIDATION, "the condition was refused", problem.field)
}

/**
 * Every [HealthProblem] as spec §9.2 codes it. Where a problem carries a bound, the sentence states
 * it: an over-long subject name is `HEALTH_SUBJECT_NAME_REQUIRED` too (the spec has one name code,
 * plan decision 33), so the message says the limit rather than "missing". An archived schedule is
 * `FOREIGN_SCHEDULE` (the code set is closed, plan decision 45), and its message says archived.
 */
internal fun healthRefusal(problem: HealthProblem): Refusal = when (problem) {
    is HealthProblem.NameRequired -> Refusal(
        "HEALTH_SUBJECT_NAME_REQUIRED",
        "a health subject's name must be ${problem.limit.first}–${problem.limit.last} characters after trimming",
        "name",
    )
    is HealthProblem.ThresholdsInvalid -> Refusal(
        "HEALTH_THRESHOLDS_INVALID",
        "nominalUntilDays, warningFromDays and criticalFromDays are all required, with " +
            "${problem.limit.first} ≤ nominalUntilDays < warningFromDays < criticalFromDays ≤ ${problem.limit.last}",
        "criticalFromDays",
    )
    HealthProblem.DriverMismatch -> Refusal(
        "HEALTH_DRIVER_MISMATCH",
        "an AGE subject names no schedule; a MAINTENANCE_OVERDUE subject names one and no baselineProfileId",
    )
    is HealthProblem.ForeignSchedule -> Refusal(
        "FOREIGN_SCHEDULE",
        if (problem.archived) {
            "that schedule is archived, so it cannot drive a health subject"
        } else {
            "scheduleId must name a schedule aimed at this asset itself"
        },
    )
    is HealthProblem.ScheduleNeedsATimeRule -> Refusal(
        "HEALTH_SCHEDULE_NEEDS_A_TIME_RULE", "that schedule has no time rule, and a meter drives no health",
    )
    is HealthProblem.ProfileNotAReplacement -> Refusal(
        "PROFILE_NOT_A_REPLACEMENT", "baselineProfileId must name a REPLACEMENT quick action of this asset",
    )
    is HealthProblem.WeightOutOfRange -> Refusal(
        "HEALTH_WEIGHT_OUT_OF_RANGE", "weight must be ${problem.limit.first}–${problem.limit.last}", "weight",
    )
    HealthProblem.PrimaryInvalid -> Refusal(
        "HEALTH_PRIMARY_INVALID",
        "TRACK_ONE needs a healthPrimarySubjectId naming a live subject of this asset, and no other aggregation takes one",
        "healthPrimarySubjectId",
    )
}

private fun referenceError(
    status: Int,
    reason: String,
    problem: ReferenceProblem,
    message: String,
): ApiResponse = errorResponse(status, reason, referenceProblemCode(problem), message)

/**
 * One stable wire code per [ReferenceProblem], in 1.2's `UPPER_SNAKE` style — with one deliberate
 * exception, `OwnerMissing`, which answers 1.1.0's shipped `no_such_asset` because that is the
 * same fact under a name clients already branch on (master plan §18.10).
 *
 * Exhaustive by construction — a `when` over a sealed interface with no `else` — so a problem
 * member added later is a compile error here rather than a reference refused with a code nobody
 * documented. `internal` rather than private, unlike [scheduleProblemCode]: `Unchanged` is
 * unreachable over the wire by design, so the only way to prove every member is mapped is to call
 * this directly, and `ReferenceRoutesTest` does.
 *
 * Two problems share `REFERENCE_URI_INVALID` and two share `REFERENCE_SCHEME_BLOCKED`. Both
 * pairings are the contract's (spec §6): a URI that does not parse and one that is too long are
 * one thing to fix, and an unfamiliar scheme over the wire is a refusal and not a question.
 */
internal fun referenceProblemCode(problem: ReferenceProblem): String = when (problem) {
    ReferenceProblem.NoSuchReference -> "NO_SUCH_REFERENCE"
    ReferenceProblem.OwnerMissing -> "no_such_asset"
    ReferenceProblem.BlankName -> "REFERENCE_NAME_REQUIRED"
    ReferenceProblem.NotALink -> "REFERENCE_URI_INVALID"
    ReferenceProblem.UriTooLong -> "REFERENCE_URI_INVALID"
    ReferenceProblem.SchemeBlocked -> "REFERENCE_SCHEME_BLOCKED"
    is ReferenceProblem.UnknownSchemeNeedsConfirmation -> "REFERENCE_SCHEME_BLOCKED"
    ReferenceProblem.DuplicateUri -> "REFERENCE_URI_TAKEN"
    // Never emitted: the handler answers 200 with the stored row. It exists so the `when` stays
    // exhaustive, which is the whole point of this function.
    ReferenceProblem.Unchanged -> "REFERENCE_UNCHANGED"
}

/**
 * One stable wire code per `ScheduleProblem`, in master plan §9.3's style.
 *
 * Exhaustive by construction — a `when` over a sealed interface with no `else`, so a problem member
 * added later is a **compile error here** rather than a schedule refused with a code nobody
 * documented. Every one of them is 422: each says the command describes a schedule that cannot
 * exist, which is exactly the line §9.3 draws between 422 and 409.
 */
private fun scheduleProblemCode(problem: ScheduleProblem): String = when (problem) {
    ScheduleProblem.TargetInvalid -> "SCHEDULE_TARGET_INVALID"
    ScheduleProblem.NoRuleSide -> "SCHEDULE_NO_RULE_SIDE"
    ScheduleProblem.MeterRuleOnGroupTarget -> "METER_RULE_ON_GROUP_TARGET"
    ScheduleProblem.SeasonFollowsAssetOnGroupTarget -> "SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET"
    ScheduleProblem.PolicyOffsetInvalid -> "POLICY_OFFSET_INVALID"
    ScheduleProblem.SeasonPolicyNeedsATimeRule -> "SEASON_POLICY_NEEDS_A_TIME_RULE"
    ScheduleProblem.ProfileOnGroupTarget -> "PROFILE_ON_GROUP_TARGET"
    // D-12: a group target is QUICK-only, so `FORM` names a form with nothing to collect.
    ScheduleProblem.FormCompletionOnGroupTarget -> "FORM_COMPLETION_ON_GROUP_TARGET"
    ScheduleProblem.EmptyGroupTarget -> "EMPTY_GROUP_TARGET"
    is ScheduleProblem.ForeignProfile -> "FOREIGN_PROFILE"
    is ScheduleProblem.ForeignMeterDefinition -> "FOREIGN_METER_DEFINITION"
    is ScheduleProblem.MeterDefinitionNotAMeter -> "METER_DEFINITION_NOT_A_METER"
    ScheduleProblem.TimeIntervalNotPositive -> "TIME_INTERVAL_NOT_POSITIVE"
    ScheduleProblem.NegativeLeadDays -> "NEGATIVE_LEAD_DAYS"
    ScheduleProblem.AnchorRequired -> "ANCHOR_REQUIRED"
    ScheduleProblem.BadAnchorDate -> "BAD_ANCHOR_DATE"
    ScheduleProblem.TimeUnitRequired -> "TIME_UNIT_REQUIRED"
    ScheduleProblem.MeterIntervalRequired -> "METER_INTERVAL_REQUIRED"
    ScheduleProblem.MeterIntervalNotPositive -> "METER_INTERVAL_NOT_POSITIVE"
    ScheduleProblem.NegativeMeterLead -> "NEGATIVE_METER_LEAD"
    ScheduleProblem.PostponeNeedsTimeRule -> "POSTPONE_NEEDS_TIME_RULE"
    is ScheduleProblem.UnknownProvider -> "UNKNOWN_PROVIDER"
}

/** The same, per `GroupProblem`. `MEMBER_ALREADY_OPEN` is invariant 80's, named by §9.1. */
private fun groupProblemCode(problem: GroupProblem): String = when (problem) {
    GroupProblem.BlankName -> "GROUP_NAME_REQUIRED"
    is GroupProblem.MemberAssetMissing -> "MEMBER_ASSET_MISSING"
    is GroupProblem.ForeignMember -> "FOREIGN_MEMBER"
    is GroupProblem.MemberAlreadyOpen -> "MEMBER_ALREADY_OPEN"
}
