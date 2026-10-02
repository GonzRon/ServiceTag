package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.BackupCorrupt
import com.loosecannon.servicetag.core.backup.BackupNewerFormat
import com.loosecannon.servicetag.core.fetch.FetchProblem
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.usecase.AssetAlreadyLent
import com.loosecannon.servicetag.core.usecase.AssetCycle
import com.loosecannon.servicetag.core.usecase.AssetHasChildren
import com.loosecannon.servicetag.core.usecase.AssetMembershipReferenced
import com.loosecannon.servicetag.core.usecase.AssetSupplyProblem
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
import com.loosecannon.servicetag.core.usecase.InstalledComponentProblem
import com.loosecannon.servicetag.core.usecase.LegacyWriteCannotRepresent
import com.loosecannon.servicetag.core.usecase.LoanReturned
import com.loosecannon.servicetag.core.usecase.LoanValidation
import com.loosecannon.servicetag.core.usecase.MaterializeRefusal
import com.loosecannon.servicetag.core.usecase.MemberCompletionNotSupported
import com.loosecannon.servicetag.core.usecase.MergePlanStale
import com.loosecannon.servicetag.core.usecase.MergeRefused
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchDefinition
import com.loosecannon.servicetag.core.usecase.NoSuchEvent
import com.loosecannon.servicetag.core.usecase.NoSuchGroup
import com.loosecannon.servicetag.core.usecase.NoSuchHealthSubject
import com.loosecannon.servicetag.core.usecase.NoSuchLoan
import com.loosecannon.servicetag.core.usecase.NoSuchProfile
import com.loosecannon.servicetag.core.usecase.NoSuchSchedule
import com.loosecannon.servicetag.core.usecase.NoSuchServiceCase
import com.loosecannon.servicetag.core.usecase.NoSuchSupplyItem
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
import com.loosecannon.servicetag.core.usecase.ReplaceProblem
import com.loosecannon.servicetag.core.usecase.ReplaceStale
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
import com.loosecannon.servicetag.core.usecase.ServiceCaseValidation
import com.loosecannon.servicetag.core.usecase.StrandedSchedule
import com.loosecannon.servicetag.core.usecase.SupplyItemProblem
import com.loosecannon.servicetag.core.usecase.SupplyItemValidation
import com.loosecannon.servicetag.core.usecase.UnknownTemplate
import com.loosecannon.servicetag.core.usecase.WarrantyReminderValidation
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
    // #77 (C13, R77-17): every write route that reaches a row of an asset transferred out from this phone —
    // its own rows, a new asset under it, a group or schedule naming it, or a staying row that would name its
    // graph (C12's one guard, R77-B2b-GUARD). A 409, not a 422: the body may be fine, and the remedy is another
    // row (the transfer) — a command that fails its own validation first still answers its 422. A held asset that
    // reads ACTIVE (merged transfer history) answers this too.
    is AssetTransferredOut -> errorResponse(
        409, "Conflict", "asset_transferred_out",
        "this asset was transferred out from this phone; its history can be read but not changed",
        listOf("AssetTransferredOut(assetId=${e.assetId.value})"),
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
    // Ten arms over one exhaustive `when`, so a member added to `ReferenceProblem` later is a
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
        // six codes a client can actually receive.
        ReferenceProblem.Unchanged ->
            referenceError(409, "Conflict", problem, "this reference already says that")
        // #91 (C2, R91-1): a role on a link that is not a web link — change this body, so 422,
        // naming the one key at fault. The reference family's first `field`.
        ReferenceProblem.RoleNotAllowed ->
            referenceError(
                422, "Unprocessable Content", problem, "a document role belongs on an http or https link",
                field = "role",
            )
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
    // --- #79, the warranty reminder (C12) ---------------------------------------------------
    //
    // The 1.1.0 families' shape (`ValidationRefusals.kt`): one lower-snake code, the first problem's
    // sentence and key, every problem by name. The fallback is unreachable, as theirs is.
    is WarrantyReminderValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::warrantyReminderRefusal)
            ?: Refusal(WARRANTY_REMINDER_VALIDATION, "the warranty reminder was refused"),
        e.problems.map { it.toString() },
    )
    // --- #79b, the service case (C24) --------------------------------------------------------
    //
    // The same shape: one lower-snake code for a header's and an entry's problems alike, the first
    // problem's sentence and key, every problem by name. The fallback is unreachable, as theirs is.
    is ServiceCaseValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::serviceCaseRefusal)
            ?: Refusal(SERVICE_CASE_VALIDATION, "the service case was refused"),
        e.problems.map { it.toString() },
    )
    // 1.1.0's lower-snake spelling for a row that is not there, beside `no_such_asset`.
    is NoSuchServiceCase -> errorResponse(404, "Not Found", "no_such_service_case", "no such service case")
    // --- #72, the loan (C21) ------------------------------------------------------------------
    //
    // The same shape for its problems. The two state refusals come before them in every loan use case
    // (existence, then state, then problems): a lend to an asset already lent out, or any write to a
    // returned loan, is the 409 whatever the body says, because no field of it could change the answer.
    is LoanValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::loanRefusal) ?: Refusal(LOAN_VALIDATION, "the loan was refused"),
        e.problems.map { it.toString() },
    )
    is NoSuchLoan -> errorResponse(404, "Not Found", "no_such_loan", "no such loan")
    // The open loan's id is a stored row's, never a value the caller sent: it is what to return first.
    is AssetAlreadyLent -> errorResponse(
        409, "Conflict", "asset_already_lent", "this asset is already lent out; return its open loan first",
        listOf("AssetAlreadyLent(openLoanId=${e.openLoanId.value})"),
    )
    is LoanReturned -> errorResponse(
        409, "Conflict", "loan_returned", "this loan has been returned, and a returned loan never changes",
    )
    // #15 (C2, C22): a SupplyItem's validation, under one arm in the `GroupValidation` shape — the first problem's
    // code, sentence and key, and `problems` listing every problem by its domain name. `SUPPLY_ITEM_INVALID` is the
    // unreachable fallback for a refusal naming no problem, documented as `GROUP_INVALID` is.
    is SupplyItemValidation -> unprocessable(
        e.problems.firstOrNull()?.let(::supplyItemRefusal) ?: Refusal(SUPPLY_ITEM_INVALID, "the supply item was refused"),
        e.problems.map { it.toString() },
    )
    is NoSuchSupplyItem -> errorResponse(404, "Not Found", NO_SUCH_SUPPLY_ITEM, "no such supply item")
    // #92 (C24): defence — the replace route's own digest check raises it too, so one arm answers both.
    is ReplaceStale -> errorResponse(
        409, "Conflict", "REPLACE_STALE", "the asset or a row reviewed with it changed; nothing was replaced",
        listOf("ReplaceStale(assetId=${e.assetId.value})"), "sourcesDigest",
    )
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
    // Phase 1A K7 (ruled 2026-09-25, corrected the same day): the maintenance break is the only route that
    // raises this value, and its pair's first key is `blackoutStartMmdd`; the message stays the shipped one.
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
    field: String? = null,
): ApiResponse = errorResponse(status, reason, referenceProblemCode(problem), message, field = field)

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
    ReferenceProblem.RoleNotAllowed -> "REFERENCE_ROLE_NOT_ALLOWED"
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

// --- #15, the SupplyItem and applicability codes (C2) --------------------------------------------
//
// Each code, status and `field` is C2's table and each sentence G1's, verbatim. Both mappers are exhaustive `when`s
// over their sealed types with no `else`, so a problem added later is a compile error here rather than a refusal
// with a code nobody documented. A line's `supplyId` naming no SupplyItem is not here: it is the shipped
// `profile_validation` / `event_validation` family's `UnknownSupplyItem` arm (`ValidationRefusals.kt`), field
// `consumables`.

internal const val NO_SUCH_SUPPLY_ITEM: String = "NO_SUCH_SUPPLY_ITEM"
internal const val SUPPLY_ITEM_INVALID: String = "SUPPLY_ITEM_INVALID"

/** Every [SupplyItemProblem], each a 422: its code, G1's sentence, and its body key. */
internal fun supplyItemRefusal(problem: SupplyItemProblem): Refusal = when (problem) {
    SupplyItemProblem.NameRequired -> Refusal("SUPPLY_ITEM_NAME_REQUIRED", "a supply item needs a name", "name")
    is SupplyItemProblem.SpecLabelRequired ->
        Refusal("SPECIFICATION_LABEL_REQUIRED", "every specification needs a label", "specifications")
    is SupplyItemProblem.SpecValueRequired ->
        Refusal("SPECIFICATION_VALUE_REQUIRED", "every specification needs a value", "specifications")
    is SupplyItemProblem.SpecKeyInvalid -> Refusal(
        "SPECIFICATION_KEY_INVALID",
        "a specification key must be lower-case letters, digits and underscores, starting with a letter, at most 40",
        "specifications",
    )
    is SupplyItemProblem.SpecKeyTaken -> Refusal(
        "SPECIFICATION_KEY_TAKEN", "another specification of this supply item already has that key", "specifications",
    )
}

/**
 * Every [AssetSupplyProblem] as the refusal the router answers (C2, C23): 404 for a row that is not there, 409 for
 * one the store will not take (an archived item for a new row, a taken triple), 422 for a blank role. `OwnerMissing`
 * answers the shipped `no_such_asset`, the references precedent. `problems` names the problem by its domain name.
 *
 * `Unchanged` is never emitted: the re-role answers 200 with the stored row before anything is mapped. Its arm keeps
 * the `when` exhaustive and answers the shipped 500 `internal` — no code is invented for a refusal no route returns.
 */
internal fun assetSupplyFailure(problem: AssetSupplyProblem): ApiFailure {
    val problems = listOf(problem.toString())
    return when (problem) {
        AssetSupplyProblem.OwnerMissing -> ApiFailure(404, "Not Found", "no_such_asset", "no such asset", problems)
        AssetSupplyProblem.SupplyItemMissing ->
            ApiFailure(404, "Not Found", NO_SUCH_SUPPLY_ITEM, "no such supply item", problems)
        AssetSupplyProblem.SupplyItemArchived -> ApiFailure(
            409, "Conflict", "SUPPLY_ITEM_ARCHIVED", "an archived supply item takes no new asset", problems,
            field = "supplyId",
        )
        AssetSupplyProblem.RoleRequired -> ApiFailure(
            422, "Unprocessable Content", "ASSET_SUPPLY_ROLE_REQUIRED", "an asset supply needs a role", problems,
            field = "role",
        )
        AssetSupplyProblem.Taken -> ApiFailure(
            409, "Conflict", "ASSET_SUPPLY_TAKEN", "this asset already takes that supply item in that role", problems,
            field = "role",
        )
        AssetSupplyProblem.NoSuchAssetSupply ->
            ApiFailure(404, "Not Found", "NO_SUCH_ASSET_SUPPLY", "no such applicability row", problems)
        AssetSupplyProblem.Unchanged -> ApiFailure(500, "Internal Server Error", "internal", "Unchanged")
    }
}

// --- #47, the installed-component codes (C2, C21) -----------------------------------------------
//
// Each code, status and `field` is C2's table and each sentence G1's, verbatim; the two reused SupplyItem codes keep
// their shipped sentences, with `field` `supplyId` for the direct link and `composition` for an entry. One exhaustive
// `when` over the first problem with no `else`, so a problem added later is a compile error here rather than a
// refusal with a code nobody documented; `problems` names every problem by its domain name.

internal const val INSTALLED_COMPONENT_INVALID: String = "INSTALLED_COMPONENT_INVALID"

/**
 * The 422 fallback for a refusal naming no problem: **unreachable**, since every use-case refusal names at least one,
 * and kept, as `SUPPLY_ITEM_INVALID` is, so the mapper answers every list.
 */
internal fun installedComponentInvalid(problems: List<String> = emptyList()): ApiFailure =
    ApiFailure(422, "Unprocessable Content", INSTALLED_COMPONENT_INVALID, "the installed component was refused", problems)

/**
 * Every [InstalledComponentProblem] as the refusal the router answers (C2, C21): 404 for a row that is not there (the
 * path's row, the body's parent, the asset, a SupplyItem), 409 for one the store will not take as it stands (a
 * removed parent or row, an archived SupplyItem), 422 for a body that must change. `OwnerMissing` answers the shipped
 * `no_such_asset`. A held asset is not here: the guarded port raises `AssetTransferredOut`, the shipped 409.
 *
 * `Unchanged` is never emitted: the edit answers 200 with the stored row before anything is mapped. Its arm keeps the
 * `when` exhaustive and answers the shipped 500 `internal` — no code is invented for a refusal no route returns.
 */
internal fun installedComponentRefusal(problems: List<InstalledComponentProblem>): ApiFailure {
    val named = problems.map { it.toString() }
    val first = problems.firstOrNull() ?: return installedComponentInvalid(problems = named)
    return when (first) {
        InstalledComponentProblem.NoSuchInstalledComponent -> ApiFailure(
            404, "Not Found", "NO_SUCH_INSTALLED_COMPONENT", "no such installed component", named,
        )
        InstalledComponentProblem.ParentMissing -> ApiFailure(
            404, "Not Found", "NO_SUCH_INSTALLED_COMPONENT", "no such installed component", named, field = "parentId",
        )
        InstalledComponentProblem.NameRequired -> ApiFailure(
            422, "Unprocessable Content", "INSTALLED_COMPONENT_NAME_REQUIRED", "an installed component needs a name",
            named, field = "name",
        )
        is InstalledComponentProblem.BadDate -> ApiFailure(
            422, "Unprocessable Content", "INSTALLED_COMPONENT_DATE_INVALID", "a date must be YYYY-MM-DD", named,
            field = first.field,
        )
        is InstalledComponentProblem.AfterToday -> ApiFailure(
            422, "Unprocessable Content", "INSTALLED_COMPONENT_DATE_AFTER_TODAY", "a date cannot be later than today",
            named, field = first.field,
        )
        is InstalledComponentProblem.RemovedBeforeInstalled -> ApiFailure(
            422, "Unprocessable Content", "INSTALLED_COMPONENT_REMOVED_BEFORE_INSTALLED",
            "the removal date cannot be before the install date", named, field = first.field,
        )
        InstalledComponentProblem.ParentOnAnotherAsset -> ApiFailure(
            422, "Unprocessable Content", "INSTALLED_COMPONENT_PARENT_ON_ANOTHER_ASSET",
            "the parent belongs to another asset", named, field = "parentId",
        )
        InstalledComponentProblem.ParentRemoved -> ApiFailure(
            409, "Conflict", "INSTALLED_COMPONENT_PARENT_REMOVED", "the parent has been removed", named,
            field = "parentId",
        )
        InstalledComponentProblem.AlreadyRemoved -> ApiFailure(
            409, "Conflict", "INSTALLED_COMPONENT_REMOVED", "this installed component has already been removed", named,
        )
        is InstalledComponentProblem.QuantityInvalid -> ApiFailure(
            422, "Unprocessable Content", "COMPOSITION_QUANTITY_INVALID",
            "every composition quantity must be a number above zero", named, field = "composition",
        )
        InstalledComponentProblem.OwnerMissing -> ApiFailure(404, "Not Found", "no_such_asset", "no such asset", named)
        InstalledComponentProblem.SupplyItemMissing -> ApiFailure(
            404, "Not Found", NO_SUCH_SUPPLY_ITEM, "no such supply item", named, field = "supplyId",
        )
        is InstalledComponentProblem.EntrySupplyItemMissing -> ApiFailure(
            404, "Not Found", NO_SUCH_SUPPLY_ITEM, "no such supply item", named, field = "composition",
        )
        InstalledComponentProblem.SupplyItemArchived -> ApiFailure(
            409, "Conflict", "SUPPLY_ITEM_ARCHIVED", "an archived supply item takes no new asset", named,
            field = "supplyId",
        )
        is InstalledComponentProblem.EntrySupplyItemArchived -> ApiFailure(
            409, "Conflict", "SUPPLY_ITEM_ARCHIVED", "an archived supply item takes no new asset", named,
            field = "composition",
        )
        InstalledComponentProblem.Unchanged -> ApiFailure(500, "Internal Server Error", "internal", "Unchanged")
    }
}

/** The same, per `GroupProblem`. `MEMBER_ALREADY_OPEN` is invariant 80's, named by §9.1. */
private fun groupProblemCode(problem: GroupProblem): String = when (problem) {
    GroupProblem.BlankName -> "GROUP_NAME_REQUIRED"
    is GroupProblem.MemberAssetMissing -> "MEMBER_ASSET_MISSING"
    is GroupProblem.ForeignMember -> "FOREIGN_MEMBER"
    is GroupProblem.MemberAlreadyOpen -> "MEMBER_ALREADY_OPEN"
}

// --- #92, the attachment codes (C2) -------------------------------------------------------------
//
// Each code, status, `field` and sentence is C2's table, verbatim. `problems` names a domain problem by its own name
// where one exists; a refusal decided in the API layer (a date, a role on an event's row) has none.

/** 404: no attachment row has that id. */
internal fun noSuchAttachment(): ApiFailure =
    ApiFailure(404, "Not Found", "NO_SUCH_ATTACHMENT", "no such attachment")

/** 422: `capturedOn` is not an ISO day, checked with core's own rule before the use case runs. */
internal fun attachmentBadDate(): ApiFailure = ApiFailure(
    422, "Unprocessable Content", "ATTACHMENT_BAD_DATE", "capturedOn is not a YYYY-MM-DD day", field = "capturedOn",
)

/** 422: a role on an event's attachment (R67-11), refused before `UpdateAttachment` could treat it as a bug. */
internal fun attachmentRoleNotAllowed(): ApiFailure = ApiFailure(
    422, "Unprocessable Content", "ATTACHMENT_ROLE_NOT_ALLOWED", "a document role belongs on an asset's attachment",
    field = "role",
)

/**
 * Every [AttachmentProblem] as C2 codes it, or null for `Unchanged`, which is no refusal: a no-op edit answers 200
 * with the stored row (C7). Exhaustive, so a problem added later is a compile error here. `OwnerMissing` is the
 * edited row itself; `NoStore`, `StoreUnavailable` and `TooLarge` come only from an add, never from an edit.
 */
internal fun attachmentRefusal(problem: AttachmentProblem): ApiFailure? = when (problem) {
    AttachmentProblem.BlankName -> ApiFailure(
        422, "Unprocessable Content", "ATTACHMENT_NAME_REQUIRED", "an attachment needs a name",
        listOf(problem.toString()), "displayName",
    )
    AttachmentProblem.OwnerMissing -> noSuchAttachment()
    AttachmentProblem.NoStore -> ApiFailure(
        409, "Conflict", "ATTACHMENT_STORE_NOT_CONFIGURED", "no attachment folder is picked on this phone",
        listOf(problem.toString()),
    )
    AttachmentProblem.StoreUnavailable -> ApiFailure(
        409, "Conflict", "store_unavailable", "the attachment folder is not available", listOf(problem.toString()),
    )
    is AttachmentProblem.TooLarge -> ApiFailure(
        422, "Unprocessable Content", "ATTACHMENT_TOO_LARGE", "the file is over 256 MiB", listOf(problem.toString()),
    )
    AttachmentProblem.Unchanged -> null
}

// --- #92 (B2), save as document's codes (C2, C17) --------------------------------------------------------------------
//
// Each code, status and sentence is C2's table, verbatim. **No refusal here carries a URI, a host, a remote header, a
// remote byte or a free-text name** (B2-pre BC2): `problems` names each domain problem by its class, `ServerError`
// carries the remote status alone, and `AlreadyHave` is written out by the earlier row's id, never by the data class's
// `toString` (which carries that row's name).

/**
 * Every [MaterializeRefusal] as C17 maps it. Exhaustive, so a refusal added later is a compile error here. A fetch
 * refusal is the remote's answer and no field of the request can fix it: **502**, one `FETCH_` code per problem
 * ([fetchProblemCode]). The permission is a 409 in either spelling: `prepare` turns the transport's `NetworkDenied`
 * into [MaterializeRefusal.NetworkDenied], and this arm keeps a `Fetch(NetworkDenied)` a 409 too.
 */
internal fun materializeRefusal(why: MaterializeRefusal): ApiFailure = when (why) {
    // The shipped 404 of `/v1/references`, unchanged: the reference went between the handler's read and `prepare`'s.
    MaterializeRefusal.NoSuchReference -> ApiFailure(404, "Not Found", "NO_SUCH_REFERENCE", "no such reference")
    MaterializeRefusal.NotEligible -> ApiFailure(
        409, "Conflict", "REFERENCE_NOT_MATERIALIZABLE", "that reference is not an https document link",
        listOf("NotEligible"),
    )
    // `NoStore` and `StoreUnavailable`, the add's own codes; `prepare` raises no other store problem.
    is MaterializeRefusal.Store -> attachmentRefusal(why.problem) ?: error("a store refusal is never Unchanged")
    MaterializeRefusal.NetworkDenied -> networkDenied()
    is MaterializeRefusal.Fetch -> if (why.problem == FetchProblem.NetworkDenied) {
        networkDenied()
    } else {
        ApiFailure(
            502, "Bad Gateway", fetchProblemCode(why.problem), "the download was refused",
            listOf(why.problem.toString()),
        )
    }
    is MaterializeRefusal.AlreadyHave -> ApiFailure(
        409, "Conflict", "ATTACHMENT_ALREADY_HELD", "this asset already holds these bytes",
        listOf("AlreadyHave(attachmentId=${why.attachmentId.value})"),
    )
}

/**
 * One wire code per [FetchProblem] (C2): the twelve `FETCH_` codes, and `NETWORK_DENIED` for the permission, which
 * is no fetch refusal ([materializeRefusal] answers it as the 409). Exhaustive, so a problem added later is a
 * compile error here. Every one is a redirect hop's or the remote's fact: a static first-hop problem is already
 * `REFERENCE_NOT_MATERIALIZABLE`.
 */
internal fun fetchProblemCode(problem: FetchProblem): String = when (problem) {
    FetchProblem.NotHttps -> "FETCH_NOT_HTTPS"
    FetchProblem.HasCredentials -> "FETCH_HAS_CREDENTIALS"
    FetchProblem.LocalAddress -> "FETCH_LOCAL_ADDRESS"
    FetchProblem.Unreachable -> "FETCH_UNREACHABLE"
    FetchProblem.Interrupted -> "FETCH_INTERRUPTED"
    FetchProblem.TimedOut -> "FETCH_TIMED_OUT"
    FetchProblem.TooLarge -> "FETCH_TOO_LARGE"
    FetchProblem.Empty -> "FETCH_EMPTY"
    FetchProblem.NotADocument -> "FETCH_NOT_A_DOCUMENT"
    FetchProblem.NeedsSignIn -> "FETCH_NEEDS_SIGN_IN"
    is FetchProblem.ServerError -> "FETCH_SERVER_ERROR"
    FetchProblem.RedirectRefused -> "FETCH_REDIRECT_REFUSED"
    FetchProblem.NetworkDenied -> "NETWORK_DENIED"
}

private fun networkDenied(): ApiFailure =
    ApiFailure(409, "Conflict", "NETWORK_DENIED", "this app may not use the network", listOf("NetworkDenied"))

// --- #92 (B3), the replace codes (C2, C24) ----------------------------------------------------------------------------
//
// Each code, status, `field` and sentence is C2's table, verbatim. `problems` names each domain problem by its own
// name ([replaceProblemName]), an id by its raw value.

/** 409: the predecessor already has a successor; `problems` names it, so a replay can read that asset. */
internal fun assetAlreadyReplaced(row: AssetSuccession): ApiFailure = ApiFailure(
    409, "Conflict", "ASSET_ALREADY_REPLACED", "this asset has already been replaced",
    listOf("ReplacedBy(successorAssetId=${row.successorAssetId.value})"),
)

/**
 * Every [ReplaceProblem] as C2 codes it. Exhaustive, so a problem added later is a compile error here. `Successor`
 * is the shipped asset family ([assetRefusal]) with its field under `successor.`; a date is the draft's own key.
 */
internal fun replaceProblemRefusal(problem: ReplaceProblem): Refusal = when (problem) {
    ReplaceProblem.NameRequired -> Refusal("REPLACE_NAME_REQUIRED", "the new asset needs a name", "successor.name")
    is ReplaceProblem.BadDate -> Refusal(
        "REPLACE_BAD_DATE", "that date is missing or not a YYYY-MM-DD day",
        when (problem.field) {
            "retiredOn", "scheduleStartOn" -> problem.field
            else -> "successor.${problem.field}"
        },
    )
    is ReplaceProblem.Successor -> assetRefusal(problem.problem).let { it.copy(field = it.field?.let { f -> "successor.$f" }) }
    is ReplaceProblem.NotOffered -> Refusal("REPLACE_NOT_OFFERED", "that schedule, group, tag or parent is not offered")
    is ReplaceProblem.NeedsSetup ->
        Refusal("REPLACE_NEEDS_SETUP", "a ticked schedule needs its readings and actions", "carrySetup")
    is ReplaceProblem.NeedsSeason ->
        Refusal("REPLACE_NEEDS_SEASON", "a ticked pre-service schedule needs the season", "carrySeason")
    ReplaceProblem.PhaseRequired -> Refusal("REPLACE_PHASE_REQUIRED", "say whether the new asset is in season", "manualPhase")
    ReplaceProblem.ReplacedOnAfterToday ->
        Refusal("REPLACE_DATE_AFTER_TODAY", "the replacement date is later than today", "retiredOn")
}

/** A [ReplaceProblem] by its own name, each id by its raw value. */
internal fun replaceProblemName(problem: ReplaceProblem): String = when (problem) {
    ReplaceProblem.NameRequired -> "NameRequired"
    is ReplaceProblem.BadDate -> "BadDate(field=${problem.field})"
    is ReplaceProblem.Successor -> "Successor(problem=${problem.problem})"
    is ReplaceProblem.NotOffered -> "NotOffered(id=${problem.id})"
    is ReplaceProblem.NeedsSetup -> "NeedsSetup(scheduleId=${problem.scheduleId.value})"
    is ReplaceProblem.NeedsSeason -> "NeedsSeason(scheduleId=${problem.scheduleId.value})"
    ReplaceProblem.PhaseRequired -> "PhaseRequired"
    ReplaceProblem.ReplacedOnAfterToday -> "ReplacedOnAfterToday"
}

/** 422: the first problem's code, sentence and field; every problem in `problems`, first first. */
internal fun replaceProblems(problems: List<ReplaceProblem>): ApiFailure {
    val first = replaceProblemRefusal(problems.first())
    return ApiFailure(
        422, "Unprocessable Content", first.code, first.message, problems.map(::replaceProblemName), first.field,
    )
}
