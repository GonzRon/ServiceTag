package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.AddReferenceCommand
import com.loosecannon.servicetag.core.usecase.InstalledComponentProblem
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchSupplyItem
import com.loosecannon.servicetag.core.usecase.ReferenceProblem
import com.loosecannon.servicetag.core.usecase.ReferenceResult
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.core.usecase.UpdateReferenceCommand
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The three 1.3.0 reference endpoints, and **every write goes through exactly one use case.**
 *
 * That is this file's whole design rule, and it is what makes the rules unreachable rather than
 * merely unwritten: the scheme tiers, the structural URI check, the length caps, the blank name,
 * the owner and the `(owner, uri)` identity are all `AddReference`'s and `UpdateReference`'s,
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
 * **There is no delete and no byte path here** (spec §6, I-3): a reference has no bytes, the API
 * adds and amends, and the phone removes. The listener's one byte path is #92's attachment upload,
 * which is not a reference's.
 *
 * Built as `ApiHandlers`' second collaborator, in [MaintenanceHandlers]' shape, so the Developer
 * API screen's wiring is untouched: it hands over an `ApiHandlers` and knows nothing about what
 * the router matches.
 */
internal class ReferenceHandlers(
    private val references: ReferenceRepository,
    assets: AssetRepository,
    supplyItems: SupplyItemRepository,
    installedComponents: InstalledComponentRepository,
    private val addReference: AddReference,
    private val updateReference: UpdateReference,
) {
    constructor(graph: AppGraph) : this(
        graph.references, graph.assets, graph.supplyItems, graph.installedComponents, graph.addReference,
        graph.updateReference,
    )

    private val owners = ResourceOwners(assets, supplyItems, installedComponents)

    /**
     * One owner's own links: the ninth `/v1/assets/{id}/…` sub-resource, and #69's
     * `/v1/supply-items/{id}/references` and `/v1/installed-components/{id}/references` (C19) — an
     * archived SupplyItem's, a removed component's and a held asset's component's read like any
     * other. Ordered by `displayName` then `id`, which is `ReferenceRepository.forOwner`'s own
     * documented order and the order the References section draws — one order, so a client's diff
     * and the phone's screen cannot disagree. An owner that is not there is its own shipped 404.
     */
    suspend fun listForOwner(owner: ReferenceOwner): ApiResponse {
        owners.require(owner)
        val rows = references.forOwner(owner).map { it.toDto() }
        return ok(ReferenceListResponse.serializer(), ReferenceListResponse(rows))
    }

    /**
     * 201 with the full row. The body names exactly one owner (#69, C20) — none or several is G1's
     * 400, before anything is read. The owner check itself is `AddReference`'s, deliberately: it
     * runs after the URI and the name are validated, so a body that is wrong in two ways names the
     * field before the row. Its `OwnerMissing` is answered here, by the owner the body named (C-6):
     * an asset's is the shipped `no_such_asset` (§18.10), with no `field` as ever; a SupplyItem's
     * and an installed component's are their shipped 404s, naming the body key.
     */
    suspend fun create(request: ApiRequest): ApiResponse {
        val body = request.decode(CreateReferenceRequest.serializer())
        val owner = body.owner() ?: throw ApiFailure.badRequest(EXACTLY_ONE_OWNER)
        val result = addReference.run(
            owner,
            AddReferenceCommand(
                uri = body.uri,
                displayName = body.displayName,
                description = body.description,
                role = body.role,
                // `confirmedUnknownScheme` is never set here, and the default is only half the
                // reason: see this class's KDoc.
            ),
        )
        val saved = when (result) {
            is ReferenceResult.Ok -> result.value
            is ReferenceResult.Refused -> {
                if (result.problem == ReferenceProblem.OwnerMissing) throw ownerMissing(owner)
                throw ReferenceRefused(result.problem)
            }
        }
        return createdResponse(ReferenceResponse.serializer(), ReferenceResponse(saved.toDto()))
    }

    /**
     * Name, description and role, overlaid onto the stored row. For the name and the description
     * an absent or `null` field is the row's current value, any other value replaces it, and `""`
     * blanks the description. **The role is three-state** (#91, R91-3): absent keeps the stored
     * role, `null` clears it, a name sets it. The typed decode runs first, over the caller's own
     * bytes, so every 415 and 400 is the shipped one byte for byte; only then is the same text
     * read again for whether it names `role` at all, through the same 400 path (a body the lenient
     * typed decoder took but the element parser refuses — a missing comma — is a 400 here where it
     * was a 200 before #91). The
     * row is read first because `UpdateReferenceCommand` is a **full** triple — there is no
     * partial command — and sending back a field the caller never named is how an amend rewrites
     * what it was not asked to touch.
     */
    suspend fun update(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(UpdateReferenceRequest.serializer())
        // The presence read goes through the shipped 400 path: the typed decoder is lenient (it accepts a
        // missing comma between pairs), the element parser is not, so a body that passed the first step can
        // still fail here — and then it is a 400, not a 500 (B2a re-review R-1).
        val namesRole = "role" in decodeOr400(JsonElement.serializer(), request.body.decodeToString()).jsonObject
        val stored = references.get(ReferenceId(id))
            ?: throw ReferenceRefused(ReferenceProblem.NoSuchReference)
        val result = updateReference.run(
            ReferenceId(id),
            UpdateReferenceCommand(
                displayName = body.displayName ?: stored.displayName,
                description = body.description ?: stored.description,
                // Absent: unchanged; `null`: clear; a name: set.
                role = if (namesRole) body.role else stored.role,
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

    /** C20: exactly one non-null owner key, as its owner; null for none or several. */
    private fun CreateReferenceRequest.owner(): ReferenceOwner? {
        val named = listOfNotNull(
            assetId?.let { ReferenceOwner.OfAsset(AssetId(it)) },
            supplyItemId?.let { ReferenceOwner.OfSupplyItem(SupplyId(it)) },
            installedComponentId?.let { ReferenceOwner.OfInstalledComponent(InstalledComponentId(it)) },
        )
        return named.singleOrNull()
    }

    /** C20, C-6: the create's absent owner, by the body key that named it; `assetId`'s keeps no `field`. */
    private fun ownerMissing(owner: ReferenceOwner): Exception = when (owner) {
        is ReferenceOwner.OfAsset -> NoSuchAsset(owner.assetId)
        is ReferenceOwner.OfSupplyItem -> noSuchSupplyItem(field = "supplyItemId")
        is ReferenceOwner.OfInstalledComponent -> noSuchInstalledComponent(field = "installedComponentId")
    }
}

/** G1 (#69, C20): the create's 400 when its body names no owner, or more than one. */
internal const val EXACTLY_ONE_OWNER: String =
    "a reference names exactly one owner: assetId, supplyItemId or installedComponentId"

/**
 * #69 (C19, C21): the three owners a route can name — an asset, a SupplyItem, an installed component — read once,
 * for the reference and attachment rows alike. [require] answers each owner's shipped 404 when it is not there, and
 * otherwise the asset whose transfer governs writes to it: the asset itself, a component's own asset, and none for a
 * SupplyItem, which is never held (H5). An archived SupplyItem and a removed component are there like any other.
 */
internal class ResourceOwners(
    private val assets: AssetRepository,
    private val supplyItems: SupplyItemRepository,
    private val installedComponents: InstalledComponentRepository,
) {
    suspend fun require(owner: ReferenceOwner): AssetId? = when (owner) {
        is ReferenceOwner.OfAsset -> owner.assetId.also { assets.get(it) ?: throw noSuchOwner(owner) }
        is ReferenceOwner.OfSupplyItem -> {
            supplyItems.get(owner.supplyId) ?: throw noSuchOwner(owner)
            null
        }
        is ReferenceOwner.OfInstalledComponent ->
            (installedComponents.get(owner.componentId) ?: throw noSuchOwner(owner)).assetId
    }
}

/**
 * C2, C-6: [owner]'s shipped 404 where a path names it — `no_such_asset`, `NO_SUCH_SUPPLY_ITEM` or
 * `NO_SUCH_INSTALLED_COMPONENT`, each byte for byte as its own routes answer it, and never with a `field`.
 */
internal fun noSuchOwner(owner: ReferenceOwner): Exception = when (owner) {
    is ReferenceOwner.OfAsset -> NoSuchAsset(owner.assetId)
    is ReferenceOwner.OfSupplyItem -> NoSuchSupplyItem(owner.supplyId)
    is ReferenceOwner.OfInstalledComponent -> noSuchInstalledComponent(field = null)
}

/** C2: the shipped SupplyItem 404, naming [field] when a body key is at fault; byte-identical to its path form. */
internal fun noSuchSupplyItem(field: String?): ApiFailure =
    ApiFailure(404, "Not Found", NO_SUCH_SUPPLY_ITEM, "no such supply item", field = field)

/** C2: the shipped installed-component 404 (`installedComponentRefusal`'s own), naming [field] when one is at fault. */
internal fun noSuchInstalledComponent(field: String?): ApiFailure =
    installedComponentRefusal(listOf(InstalledComponentProblem.NoSuchInstalledComponent)).let {
        ApiFailure(it.status, it.reason, it.code, it.message ?: it.code, it.problems, field)
    }
