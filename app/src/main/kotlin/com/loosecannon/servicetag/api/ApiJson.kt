package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.BackupCorrupt
import com.loosecannon.servicetag.core.backup.BackupNewerFormat
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.usecase.AssetCycle
import com.loosecannon.servicetag.core.usecase.AssetHasChildren
import com.loosecannon.servicetag.core.usecase.AssetMembershipReferenced
import com.loosecannon.servicetag.core.usecase.AssetValidation
import com.loosecannon.servicetag.core.usecase.BadScheduleDate
import com.loosecannon.servicetag.core.usecase.CloseNotSupported
import com.loosecannon.servicetag.core.usecase.ClosedOnOutOfRange
import com.loosecannon.servicetag.core.usecase.DefinitionInUse
import com.loosecannon.servicetag.core.usecase.DefinitionValidation
import com.loosecannon.servicetag.core.usecase.DefinitionWouldBreakDerived
import com.loosecannon.servicetag.core.usecase.DefinitionWouldBreakProfiles
import com.loosecannon.servicetag.core.usecase.EventOwnership
import com.loosecannon.servicetag.core.usecase.EventValidation
import com.loosecannon.servicetag.core.usecase.GroupCompletionNotSupported
import com.loosecannon.servicetag.core.usecase.GroupProblem
import com.loosecannon.servicetag.core.usecase.GroupValidation
import com.loosecannon.servicetag.core.usecase.MemberCompletionNotSupported
import com.loosecannon.servicetag.core.usecase.MergePlanStale
import com.loosecannon.servicetag.core.usecase.MergeRefused
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchDefinition
import com.loosecannon.servicetag.core.usecase.NoSuchEvent
import com.loosecannon.servicetag.core.usecase.NoSuchGroup
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
import com.loosecannon.servicetag.core.usecase.ProfileValidation
import com.loosecannon.servicetag.core.usecase.ReferenceProblem
import com.loosecannon.servicetag.core.usecase.ScheduleArchived
import com.loosecannon.servicetag.core.usecase.ScheduleProblem
import com.loosecannon.servicetag.core.usecase.ScheduleValidation
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

/** [code] is stable and machine-readable; [message] is for a person; [problems] names bad fields. */
@Serializable
internal data class ApiErrorDetail(
    val code: String,
    val message: String,
    val problems: List<String> = emptyList(),
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
): ApiResponse = ApiResponse.json(
    status, reason,
    ApiJson.encodeToString(
        ApiErrorBody.serializer(),
        ApiErrorBody(ApiErrorDetail(code, message, problems)),
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
    return try {
        ApiJson.decodeFromString(serializer, body.decodeToString())
    } catch (e: SerializationException) {
        throw ApiFailure.badRequest(e.message ?: "that is not the JSON this endpoint wants")
    }
}

/**
 * Every refusal `:core` can raise, given the status it means.
 *
 * 422 is a *validation* failure — the caller sent a bad field, and `problems` names each one, using
 * the sealed problem types' own `toString()` so the names in the JSON are the names in the code.
 * 409 is a refusal about *state*: the row is fine and the store will not have it (a cycle, a
 * definition that already has measurements, a derived reading that would break). 404 is a row that
 * is not there. 400 is a caller error that is not about a field. Anything left is a 500 carrying
 * the exception's class name and nothing else — no message, because an unanticipated message is the
 * one place a path or a value could leak into a response.
 */
internal fun mapDomainFailure(e: Exception): ApiResponse = when (e) {
    is AssetValidation -> errorResponse(
        422, "Unprocessable Content", "asset_validation", "the asset was refused",
        e.problems.map { it.toString() },
    )
    is EventValidation -> errorResponse(
        422, "Unprocessable Content", "event_validation", "the event was refused",
        e.problems.map { it.toString() },
    )
    is DefinitionValidation -> errorResponse(
        422, "Unprocessable Content", "definition_validation", "the reading was refused",
        e.problems.map { it.toString() },
    )
    is ProfileValidation -> errorResponse(
        422, "Unprocessable Content", "profile_validation", "the quick action was refused",
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
    else -> errorResponse(
        500, "Internal Server Error", "internal", e.javaClass.simpleName,
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
