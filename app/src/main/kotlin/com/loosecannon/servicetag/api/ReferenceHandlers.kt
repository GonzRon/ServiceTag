package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.ReferenceProblem
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.core.usecase.UpdateReferenceCommand
import com.loosecannon.servicetag.di.AppGraph

/**
 * The three 1.3.0 reference endpoints, and **every write goes through exactly one use case.**
 *
 * That is this file's whole design rule, and it is what makes the rules unreachable rather than
 * merely unwritten: the scheme tiers, the structural URI check, the length caps, the blank name,
 * the owner and the `(assetId, uri)` identity are all `AddReference`'s and `UpdateReference`'s,
 * and **nothing here re-checks one of them** (I-2). A refusal this file finds missing is a finding
 * for the controller and a fix in `:core`, never a check added in the API layer, because a check
 * added here would be one the share screen and the "Add link" sheet do not have.
 *
 * Three decisions carry the weight:
 *
 * - **The API never sets `confirmedUnknownScheme`** (master plan §18.2). There is nobody on the
 *   wire to answer "Save this link?", so an unfamiliar scheme comes back as
 *   `UnknownSchemeNeedsConfirmation` and is refused with the same code a hard block gets — spec
 *   §6's "a refusal, never a confirmation, over the API", true by construction rather than by
 *   remembering.
 * - **`kind` is derived and read-only.** No command carries one (§18.18); the stored row's kind is
 *   inferred from the scheme by `ReferenceKinds.inferFrom` inside `AddReference`, and travels back
 *   out in `AssetReferenceDto`.
 * - **A no-op amend is a 200 carrying the stored row, not an error** (§18.13). `UpdateReference`
 *   answers `Unchanged` and deliberately writes nothing, so `updated_at` does not move — which is
 *   what keeps a re-imported archive `IDENTICAL` on the next merge rather than `CONTENT_DIFFERS`.
 *
 * **There is no delete and no byte path here, and there is none anywhere** (spec §6, I-3): the API
 * adds and amends, the phone removes, and no endpoint on this listener accepts or returns a file.
 *
 * Built as `ApiHandlers`' second collaborator, in [MaintenanceHandlers]' shape, so the Developer
 * API screen's wiring is untouched: it hands over an `ApiHandlers` and knows nothing about what
 * the router matches.
 */
internal class ReferenceHandlers(
    private val references: ReferenceRepository,
    private val assets: AssetRepository,
    private val addReference: AddReference,
    private val updateReference: UpdateReference,
) {
    constructor(graph: AppGraph) : this(
        graph.references, graph.assets, graph.addReference, graph.updateReference,
    )

    /**
     * The ninth `/v1/assets/{id}/…` sub-resource. Ordered by `displayName` then `id`, which is
     * `ReferenceRepository.forAsset`'s own documented order and the order the References section
     * draws — one order, so a client's diff and the phone's screen cannot disagree.
     */
    suspend fun listForAsset(assetId: String): ApiResponse {
        asset(assetId)
        return ok(
            ReferenceListResponse.serializer(),
            ReferenceListResponse(references.forAsset(AssetId(assetId)).map { it.toDto() }),
        )
    }

    /**
     * 201 with the full row. The owner check is `AddReference`'s, deliberately: it runs after the
     * URI and the name are validated, so a body that is wrong in two ways names the field before
     * the row, and `OwnerMissing` answers the shipped `no_such_asset` (§18.10) rather than a
     * second code for the fact 1.1.0 already names.
     */
    suspend fun create(request: ApiRequest): ApiResponse {
        val body = request.decode(CreateReferenceRequest.serializer())
        val saved = addReference.run(
            AssetId(body.assetId),
            AddReferenceCommand(
                uri = body.uri,
                displayName = body.displayName,
                description = body.description,
                // Never set here, and the default is only half the reason: see this class's KDoc.
            ),
        ).orRefuse()
        return createdResponse(ReferenceResponse.serializer(), ReferenceResponse(saved.toDto()))
    }

    /**
     * Name and description, overlaid onto the stored row: an absent or `null` field is the row's
     * current value, any other value replaces it, and `""` blanks the description. The row is read
     * first because `UpdateReferenceCommand` is a **full** pair — there is no partial command —
     * and sending back a field the caller never named is how an amend rewrites what it was not
     * asked to touch.
     */
    suspend fun update(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(UpdateReferenceRequest.serializer())
        val stored = references.get(ReferenceId(id))
            ?: throw ReferenceRefused(ReferenceProblem.NoSuchReference)
        val result = updateReference.run(
            ReferenceId(id),
            UpdateReferenceCommand(
                displayName = body.displayName ?: stored.displayName,
                description = body.description ?: stored.description,
            ),
        )
        val row = when (result) {
            is ReferenceResult.Ok -> result.value
            // The one refusal that is not one. Every other problem travels to [mapDomainFailure].
            is ReferenceResult.Refused ->
                if (result.problem == ReferenceProblem.Unchanged) stored else throw ReferenceRefused(result.problem)
        }
        return ok(ReferenceResponse.serializer(), ReferenceResponse(row.toDto()))
    }

    /** `/v1/status`' `assetReferences` count — the table's own name, as the archive spells it. */
    suspend fun count(): Int = references.all().size

    // --- plumbing ---------------------------------------------------------------------------

    private suspend fun asset(id: String) = assets.get(AssetId(id)) ?: throw NoSuchAsset(AssetId(id))

    /** The value, or the refusal on its way to [mapDomainFailure], which owns every status. */
    private fun <T> ReferenceResult<T>.orRefuse(): T = when (this) {
        is ReferenceResult.Ok -> value
        is ReferenceResult.Refused -> throw ReferenceRefused(problem)
    }
}
