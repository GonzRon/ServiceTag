package com.loosecannon.servicetag.core.usecase

/**
 * Two outcomes and no exception, in the shape [AttachmentResult] already uses: every member of
 * [ReferenceProblem] is something a caller draws or maps to a status code, not something that has
 * gone wrong. `kotlin.Result` carries one type parameter, so it is spelled like this.
 */
sealed interface ReferenceResult<out T> {
    data class Ok<out T>(val value: T) : ReferenceResult<T>
    data class Refused(val problem: ReferenceProblem) : ReferenceResult<Nothing>
}

/**
 * What a share, the "Add link" sheet, the API and MCP all hand to [AddReference].
 *
 * There is no `kind`: it is derived from the scheme (spec §3.2), so a field for it would be a
 * second catalog of one fact and the wire would read as though a client could set it. There is no
 * `provenance` either (D-21 C): an in-app link and a shared one write identical rows.
 */
data class AddReferenceCommand(
    val uri: String,
    val displayName: String,
    val description: String = "",
    /** Set only after the person answered "Save this link?". The API and MCP never set it (§18.2). */
    val confirmedUnknownScheme: Boolean = false,
)

/** What the edit sheet can change. `uri`, `assetId` and `kind` are absent: I-1 and I-6. */
data class UpdateReferenceCommand(val displayName: String, val description: String)

/**
 * One thing wrong with a reference command. A **new** sealed interface and not a member of
 * `AttachmentProblem`, whose KDoc says "the list is closed" and whose exhaustive `when` in
 * `AttachmentsSectionViewModel.say` is on that untouched list.
 *
 * No member carries a user-visible sentence: the words a person reads are the caller's, chosen from
 * the ratified strings, so one refusal can read differently at intake and in a sheet.
 */
sealed interface ReferenceProblem {
    data object BlankName : ReferenceProblem

    /** Empty, no scheme, or a hierarchical scheme with no host (I-10, plan §18.19). */
    data object NotALink : ReferenceProblem
    data object UriTooLong : ReferenceProblem

    /** One of [com.loosecannon.servicetag.core.references.LinkLaunchPolicy.BLOCKED] (I-2). */
    data object SchemeBlocked : ReferenceProblem

    /** Saveable, once — and only once the person has been asked about this scheme by name. */
    data class UnknownSchemeNeedsConfirmation(val scheme: String) : ReferenceProblem

    /** `UNIQUE(asset_id, uri)`; the same URI on a different asset is ordinary (I-7). */
    data object DuplicateUri : ReferenceProblem
    data object OwnerMissing : ReferenceProblem
    data object NoSuchReference : ReferenceProblem

    /** The command says what the row already says, so nothing is written and `updated_at` holds. */
    data object Unchanged : ReferenceProblem
}
