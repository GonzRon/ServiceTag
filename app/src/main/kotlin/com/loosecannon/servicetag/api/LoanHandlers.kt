package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.usecase.LendAsset
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchLoan
import com.loosecannon.servicetag.core.usecase.ReturnLoan
import com.loosecannon.servicetag.core.usecase.UpdateLoan
import com.loosecannon.servicetag.di.AppGraph

/**
 * #72's five `/v1` rows (C21; R72-11, R72-15–R72-18), reached from the router as `handlers.loans.*`.
 *
 * The rule is 1.2's: each write calls exactly one use case — `LendAsset`, `UpdateLoan` or `ReturnLoan` —
 * and the two reads read the loan repository. **Nothing here deletes a loan, relinks one or touches a
 * contact** (R72-3, R72-17): "Mark returned" is a loan's only exit, and only the phone makes a link, so
 * every loan lent here is name-only and none of these rows carries the lookup URI ([LoanDto]).
 *
 * **Nothing here reconciles a reminder** (R72-15): this class holds no reconcile, provider or graph, so a
 * loan written here — lent with a due date and a mode, re-dated or returned — settles at the phone's next
 * reminder sweep at or after the owner's digest hour, as every `/v1` write does. No write here touches an
 * event, a condition, a schedule or its state, or a health value (R72-13).
 */
internal class LoanHandlers(
    private val assets: AssetRepository,
    private val loans: AssetLoanRepository,
    private val lendAsset: LendAsset,
    private val updateLoan: UpdateLoan,
    private val returnLoan: ReturnLoan,
) {
    constructor(graph: AppGraph) : this(graph.assets, graph.loans, graph.lendAsset, graph.updateLoan, graph.returnLoan)

    /** The one `/v1/status` count, under the archive's own list name: every loan, open and returned. */
    suspend fun counts(): Map<String, Int> = mapOf("assetLoans" to loans.all().size)

    /** An asset's loans, the latest lent first, then by id; a 404 for an asset that is not there. Writes nothing. */
    suspend fun listForAsset(assetId: String): ApiResponse {
        assets.get(AssetId(assetId)) ?: throw NoSuchAsset(AssetId(assetId))
        return ok(LoanListResponse.serializer(), LoanListResponse(loans.forAsset(AssetId(assetId)).map { it.toLoanDto() }))
    }

    /** One loan, open or returned. Writes nothing. */
    suspend fun get(loanId: String): ApiResponse {
        val id = AssetLoanId(loanId)
        val loan = loans.get(id) ?: throw NoSuchLoan(id)
        return ok(LoanResponse.serializer(), LoanResponse(loan.toLoanDto()))
    }

    /** Lends an asset of any lifecycle to a name: one open loan row, exactly the fields sent. */
    suspend fun lend(request: ApiRequest): ApiResponse {
        val body = request.decode(LoanCreateRequest.serializer())
        val lent = lendAsset.run(AssetId(body.assetId), body.borrowerName, body.terms().toTerms())
        return createdResponse(LoanResponse.serializer(), LoanResponse(lent.toLoanDto()))
    }

    /** A full replace of an open loan's terms; the asset, borrower, link and return date never move. */
    suspend fun update(loanId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(LoanUpdateRequest.serializer())
        val saved = updateLoan.run(AssetLoanId(loanId), body.toTerms())
        return ok(LoanResponse.serializer(), LoanResponse(saved.toLoanDto()))
    }

    /** "Mark returned": sets the return date, and the row stays as history. */
    suspend fun markReturned(loanId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(LoanReturnRequest.serializer())
        val returned = returnLoan.run(AssetLoanId(loanId), body.returnedOn)
        return ok(LoanResponse.serializer(), LoanResponse(returned.toLoanDto()))
    }
}
