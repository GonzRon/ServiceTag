package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import com.loosecannon.servicetag.core.usecase.ReplaceAsset
import com.loosecannon.servicetag.core.usecase.ReplaceStale
import com.loosecannon.servicetag.di.AppGraph

/**
 * #92 (C19–C24; R92-1, R92-2) — the replace triad, reached from the router as `handlers.replace.*`.
 *
 * **R92-1 supersedes R86-18**: the API may replace an asset, and it does so only through #86's canonical atomic use
 * case — [ReplaceAsset.offer], [ReplaceAsset.plan] and one [ReplaceAsset.run] — so no step of a replacement is
 * written here. The one collaborator is the use case: this class holds no repository and cannot write a row.
 *
 * - The offer and the plan write nothing. Nothing is ticked for the caller and `scheduleStartOn` is never defaulted.
 * - The apply refuses in C22's order: existence, then state (held, already replaced), then the digest (C23: the
 *   canonical draft and every source `run` compares), then the plan's problems, and only then the one write, which
 *   re-plans inside its own transaction and answers [ReplaceStale] for any change the digest could not see.
 * - **R92-2**: a tag moves only when its binding id is in `movedTagIds`, and the move is the use case's in-place
 *   retarget of that row. No tag I/O exists here or anywhere under this package.
 */
internal class ReplaceHandlers(private val replaceAsset: ReplaceAsset) {
    constructor(graph: AppGraph) : this(graph.replaceAsset)

    /** C19: `GET /v1/assets/{id}/replace-offer`. A missing asset is the shipped 404. */
    suspend fun offer(assetId: String): ApiResponse =
        ok(ReplaceOfferResponse.serializer(), replaceAsset.offer(AssetId(assetId)).toResponse())

    /** C21: `POST /v1/assets/{id}/replace-plan` — the review, its problems as data, and the digest to apply with. */
    suspend fun plan(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ReplaceDraftRequest.serializer())
        if (body.sourcesDigest != null) {
            throw ApiFailure.badRequest("sourcesDigest is replace's key; replace-plan answers one")
        }
        val id = AssetId(assetId)
        val offer = replaceAsset.offer(id)
        val plan = replaceAsset.plan(body.toDraft(id))
        val blockedBy = when {
            offer.held -> "asset_transferred_out"
            offer.replacedBy != null -> "ASSET_ALREADY_REPLACED"
            else -> null
        }
        return ok(
            ReplacePlanResponse.serializer(),
            ReplacePlanResponse(
                eligible = offer.eligible,
                blockedBy = blockedBy,
                replacedOn = plan.replacedOn,
                problems = plan.problems.map { problem ->
                    val refusal = replaceProblemRefusal(problem)
                    ReplaceProblemDto(refusal.code, refusal.field, replaceProblemName(problem))
                },
                sourcesDigest = sourcesDigest(body, plan),
            ),
        )
    }

    /** C22: `POST /v1/assets/{id}/replace` — the planned draft and its digest, then the one atomic write. */
    suspend fun replace(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ReplaceDraftRequest.serializer())
        val digest = body.sourcesDigest
            ?: throw ApiFailure.badRequest("sourcesDigest is required: send the one replace-plan answered")
        val id = AssetId(assetId)
        val offer = replaceAsset.offer(id)
        if (offer.held) throw AssetTransferredOut(id)
        offer.replacedBy?.let { throw assetAlreadyReplaced(it) }
        val draft = body.toDraft(id)
        val plan = replaceAsset.plan(draft)
        if (sourcesDigest(body, plan) != digest) throw ReplaceStale(id)
        if (plan.problems.isNotEmpty()) throw replaceProblems(plan.problems)
        val result = replaceAsset.run(draft, plan)
        return createdResponse(
            ReplaceResponse.serializer(),
            ReplaceResponse(result.successor.toDto(), result.succession.toDto()),
        )
    }
}
