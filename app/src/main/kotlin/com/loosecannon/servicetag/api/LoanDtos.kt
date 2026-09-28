package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.usecase.LoanTerms
import kotlinx.serialization.Serializable

/*
 * #72's shapes on the wire (C21; R72-3, R72-4, R72-16).
 *
 * **A loan answers as [LoanDto], not as the archive's row.** The archive's `AssetLoanDto` carries the
 * Android Contacts lookup URI beside the borrower's name snapshot; this API never does (R72-4): a
 * response says only whether the loan is linked to a contact, [LoanDto.contactLinked], and no request
 * takes a link. A link is made on the phone alone — by "Lend out" or "Choose from Contacts" — so every
 * loan this API makes is name-only. (A data archive still carries the link as data through
 * the two `/v1/import-merge` routes, N7: the merge moves rows, it does not read them out here.)
 *
 * The requests are the command's fields and nothing else. The borrower is fixed when a loan is made and
 * the return date is set only by `POST /v1/loans/{id}/return`, so the replace takes neither, and no
 * body names the open marker or a standing — both are derived, never sent. The API applies **none of
 * the phone's form defaults**: `lentOn` has no default (the form offers today), and `reminderMode` left
 * out is `NONE`, the value a cleared mode takes. `docs/api/command-shapes.json` pins the lend's keys as
 * `loan` (the replace takes every key but `assetId` and `borrowerName`) and the return's as `loanReturn`.
 */

/**
 * One loan as this API answers it: the archive row's fields in their order, less the contact lookup URI,
 * plus [contactLinked] in its place. [reminderMode] is a `LoanReminderMode` name; [returnedOn] is `null`
 * while the loan is open. A loan's standing — lent out or overdue — is derived at read time by the
 * phone's screens and is not here, as it is on no archive row.
 */
@Serializable
internal data class LoanDto(
    val id: String,
    val assetId: String,
    val borrowerName: String,
    /** True when the phone linked the loan to an Android contact. The link itself never leaves the phone. */
    val contactLinked: Boolean,
    val lentOn: String,
    val dueOn: String?,
    val reminderMode: String,
    val returnedOn: String?,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** [AssetLoan] as [LoanDto]: every field but the lookup URI, which becomes whether there is one. */
internal fun AssetLoan.toLoanDto() = LoanDto(
    id = id.value,
    assetId = assetId.value,
    borrowerName = borrowerName,
    contactLinked = contactLookupUri != null,
    lentOn = lentOn,
    dueOn = dueOn,
    reminderMode = reminderMode.name,
    returnedOn = returnedOn,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * `POST /v1/loans`: lends an asset — any lifecycle (R72-11) — to [borrowerName], a name only. `assetId`,
 * `borrowerName` and `lentOn` have no default; a blank or omitted `dueOn` is no due date.
 */
@Serializable
internal data class LoanCreateRequest(
    val assetId: String,
    val borrowerName: String,
    val lentOn: String,
    val dueOn: String? = null,
    val reminderMode: String = "NONE",
    val notes: String = "",
) {
    /** The terms this body carries: every key but [assetId] and [borrowerName]. */
    fun terms() = LoanUpdateRequest(lentOn = lentOn, dueOn = dueOn, reminderMode = reminderMode, notes = notes)
}

/**
 * `PATCH /v1/loans/{id}`: a **full replace** of an open loan's terms. An optional key left out is
 * cleared — `dueOn` to no due date, `reminderMode` to `NONE`, `notes` to `""` — and a mode other than
 * `NONE` without a `dueOn` is refused, never reset. The asset, the borrower, the contact link, the return
 * date and `createdAt` never move.
 */
@Serializable
internal data class LoanUpdateRequest(
    val lentOn: String,
    val dueOn: String? = null,
    val reminderMode: String = "NONE",
    val notes: String = "",
)

internal fun LoanUpdateRequest.toTerms() = LoanTerms(
    lentOn = lentOn,
    dueOn = dueOn,
    reminderMode = enumOr400<LoanReminderMode>(reminderMode, "reminderMode"),
    notes = notes,
)

/** `POST /v1/loans/{id}/return`: "Mark returned", the one exit. [returnedOn] has no default. */
@Serializable
internal data class LoanReturnRequest(val returnedOn: String)

/** `GET /v1/assets/{id}/loans`: the asset's loans, open and returned, in the repository's order. */
@Serializable
internal data class LoanListResponse(val loans: List<LoanDto>)

/** Every other loan route: the loan as stored. */
@Serializable
internal data class LoanResponse(val loan: LoanDto)
