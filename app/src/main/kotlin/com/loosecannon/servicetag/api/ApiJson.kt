package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.BackupCorrupt
import com.loosecannon.servicetag.core.backup.BackupNewerFormat
import com.loosecannon.servicetag.core.ports.StoreIoException
import com.loosecannon.servicetag.core.usecase.AssetCycle
import com.loosecannon.servicetag.core.usecase.AssetHasChildren
import com.loosecannon.servicetag.core.usecase.AssetValidation
import com.loosecannon.servicetag.core.usecase.DefinitionInUse
import com.loosecannon.servicetag.core.usecase.DefinitionValidation
import com.loosecannon.servicetag.core.usecase.DefinitionWouldBreakDerived
import com.loosecannon.servicetag.core.usecase.DefinitionWouldBreakProfiles
import com.loosecannon.servicetag.core.usecase.EventOwnership
import com.loosecannon.servicetag.core.usecase.EventValidation
import com.loosecannon.servicetag.core.usecase.MergePlanStale
import com.loosecannon.servicetag.core.usecase.MergeRefused
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchDefinition
import com.loosecannon.servicetag.core.usecase.NoSuchEvent
import com.loosecannon.servicetag.core.usecase.NoSuchProfile
import com.loosecannon.servicetag.core.usecase.ProfileValidation
import com.loosecannon.servicetag.core.usecase.UnknownTemplate
import kotlinx.serialization.Serializable
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
    else -> errorResponse(
        500, "Internal Server Error", "internal", e.javaClass.simpleName,
    )
}
