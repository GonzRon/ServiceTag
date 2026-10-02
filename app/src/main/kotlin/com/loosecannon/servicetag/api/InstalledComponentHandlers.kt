package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.usecase.CompositionInput
import com.loosecannon.servicetag.core.usecase.InstallComponent
import com.loosecannon.servicetag.core.usecase.InstallComponentCommand
import com.loosecannon.servicetag.core.usecase.InstalledComponentProblem
import com.loosecannon.servicetag.core.usecase.InstalledComponentResult
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.RemoveInstalledComponent
import com.loosecannon.servicetag.core.usecase.ReplaceComponentCommand
import com.loosecannon.servicetag.core.usecase.ReplaceInstalledComponent
import com.loosecannon.servicetag.core.usecase.UpdateInstalledComponent
import com.loosecannon.servicetag.core.usecase.UpdateInstalledComponentCommand
import com.loosecannon.servicetag.di.AppGraph

/**
 * #47's installed-component rows (C20, C21), and **every write goes through exactly one use case**:
 * `InstallComponent`, `UpdateInstalledComponent`, `RemoveInstalledComponent` or `ReplaceInstalledComponent`. Nothing
 * here re-checks a rule those own — the blank name, the dates, the parent, the SupplyItems, the quantities, the
 * subtree — and nothing here writes a repository; the reads are the asset's rows, one row, and the SupplyItems the
 * rows name.
 *
 * Three decisions carry the weight:
 *
 * - **The `PATCH` is an overlay assembled here** from the stored row, `SupplyHandlers.update`'s shape: a given key
 *   replaces the stored value, an absent or `null` one keeps it, and an absent `composition` sends the stored entries
 *   back with their ids, so they keep them. `""` is a value: it clears `supplyId`, `installedOn`, `serialOrLot` and
 *   `notes`, so no raw-key read is needed. The use case receives one whole command either way, and an edit that
 *   changes nothing answers 200 with the row as stored.
 * - **A replace fills only `name` from the replaced row** (R47-17b): an absent link or composition is none, never
 *   copied, so the new row carries exactly what the caller sent.
 * - **Nothing deletes an installed component**: a row is removed (closed) or replaced, and stays as history.
 *
 * `""` for an optional date or id is none here, before any command: the use case reads `""` as a bad date.
 *
 * Built as `ApiHandlers`' collaborator, in [SupplyHandlers]' shape, so the Developer API screen's wiring is untouched:
 * production builds it from `AppGraph` through `constructor(graph)`.
 */
internal class InstalledComponentHandlers(
    private val installedComponents: InstalledComponentRepository,
    private val supplyItems: SupplyItemRepository,
    private val assets: AssetRepository,
    private val installComponent: InstallComponent,
    private val updateInstalledComponent: UpdateInstalledComponent,
    private val removeInstalledComponent: RemoveInstalledComponent,
    private val replaceInstalledComponent: ReplaceInstalledComponent,
) {
    constructor(graph: AppGraph) : this(
        graph.installedComponents, graph.supplyItems, graph.assets, graph.installComponent,
        graph.updateInstalledComponent, graph.removeInstalledComponent, graph.replaceInstalledComponent,
    )

    /**
     * The twenty-seventh `/v1/assets/{id}/…` sub-resource: every row of the asset, current and removed, by id, and each
     * SupplyItem the rows and their entries name once, by name casefolded then id. A held asset reads as any other.
     */
    suspend fun listForAsset(assetId: String): ApiResponse {
        val asset = assets.get(AssetId(assetId)) ?: throw NoSuchAsset(AssetId(assetId))
        val rows = installedComponents.forAsset(asset.id)
        val named = rows.flatMap { row -> listOfNotNull(row.supplyId) + row.composition.map { it.supplyId } }
            .distinct()
            .mapNotNull { supplyItems.get(it) }
            .sortedWith(BY_NAME)
        return ok(
            InstalledComponentListResponse.serializer(),
            InstalledComponentListResponse(rows.map { it.toDto() }, named.map { it.toDto() }),
        )
    }

    suspend fun get(id: String): ApiResponse = row(stored(id))

    suspend fun install(request: ApiRequest): ApiResponse {
        val body = request.decode(InstallComponentRequest.serializer())
        val command = InstallComponentCommand(
            assetId = AssetId(body.assetId),
            parentId = body.parentId?.let(::InstalledComponentId),
            name = body.name,
            supplyId = body.supplyId.orNone()?.let(::SupplyId),
            composition = body.composition.map { it.toInput() },
            serialOrLot = body.serialOrLot,
            installedOn = body.installedOn.orNone(),
            notes = body.notes,
            sortOrder = body.sortOrder?.let(::boundedSortOrder),
        )
        val written = installComponent.run(command).orRefuse()
        return createdResponse(InstalledComponentResponse.serializer(), InstalledComponentResponse(written.row.toDto()))
    }

    /**
     * The overlay (C20): the stored row is read first because the command is the **whole** editable set, and sending
     * back a field the caller never named is how an edit leaves it alone. A repeated entry id is passed on as sent;
     * the use case keeps its first occurrence (C-7). `Refused([Unchanged])` wrote nothing, so the stored row is the
     * answer.
     */
    suspend fun update(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(UpdateInstalledComponentRequest.serializer())
        val stored = stored(id)
        val command = UpdateInstalledComponentCommand(
            name = body.name ?: stored.name,
            supplyId = if (body.supplyId == null) stored.supplyId else body.supplyId.orNone()?.let(::SupplyId),
            composition = body.composition?.map { it.toInput() } ?: stored.composition.map {
                CompositionInput(id = it.id, supplyId = it.supplyId, quantity = it.quantity.toString(), unit = it.unit)
            },
            serialOrLot = body.serialOrLot ?: stored.serialOrLot,
            installedOn = if (body.installedOn == null) stored.installedOn else body.installedOn.orNone(),
            notes = body.notes ?: stored.notes,
            sortOrder = body.sortOrder?.let(::boundedSortOrder) ?: stored.sortOrder,
        )
        return when (val result = updateInstalledComponent.run(stored.id, command)) {
            is InstalledComponentResult.Ok -> row(result.row)
            is InstalledComponentResult.Refused ->
                if (result.problems == listOf(InstalledComponentProblem.Unchanged)) row(stored(id))
                else throw installedComponentRefusal(result.problems)
        }
    }

    /** 200 `{installedComponent, closed}`: the row closed on the day sent, and its current subtree with it (R47-6). */
    suspend fun remove(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(RemoveInstalledComponentRequest.serializer())
        val closed = removeInstalledComponent.run(InstalledComponentId(id), body.removedOn).orRefuse()
        return ok(
            InstalledComponentRemovedResponse.serializer(),
            InstalledComponentRemovedResponse(closed.row.toDto(), closed.closed.map { it.toDto() }),
        )
    }

    /**
     * 201 `{installedComponent, replaced, closed}`. Only an absent `name` is read from the replaced row; the link and
     * the composition are what the body sends, or none (R47-17b).
     */
    suspend fun replace(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ReplaceInstalledComponentRequest.serializer())
        val command = ReplaceComponentCommand(
            replacedOn = body.replacedOn,
            name = body.name ?: stored(id).name,
            supplyId = body.supplyId.orNone()?.let(::SupplyId),
            composition = body.composition.orEmpty().map { it.toInput() },
            serialOrLot = body.serialOrLot ?: "",
            notes = body.notes ?: "",
        )
        val written = replaceInstalledComponent.run(InstalledComponentId(id), command).orRefuse()
        return createdResponse(
            InstalledComponentReplacedResponse.serializer(),
            InstalledComponentReplacedResponse(
                written.row.toDto(),
                checkNotNull(written.replaced) { "a replace answers the row it closed" }.toDto(),
                written.closed.map { it.toDto() },
            ),
        )
    }

    // --- status ---------------------------------------------------------------------------------

    /** `/v1/status`' `installedComponents` count: every row, current and removed, under the archive's list name. */
    suspend fun rowCount(): Int = installedComponents.all().size

    /** `/v1/status`' `compositionEntries` count: every composition entry of every row, as the manifest counts them. */
    suspend fun entryCount(): Int = installedComponents.all().sumOf { it.composition.size }

    // --- plumbing -------------------------------------------------------------------------------

    private suspend fun stored(id: String): InstalledComponent =
        installedComponents.get(InstalledComponentId(id))
            ?: throw installedComponentRefusal(listOf(InstalledComponentProblem.NoSuchInstalledComponent))

    private fun row(row: InstalledComponent): ApiResponse =
        ok(InstalledComponentResponse.serializer(), InstalledComponentResponse(row.toDto()))

    /** The written rows, or the refusal as `ApiJson`'s one mapper names it; the router answers it. */
    private fun InstalledComponentResult.orRefuse(): InstalledComponentResult.Ok = when (this) {
        is InstalledComponentResult.Ok -> this
        is InstalledComponentResult.Refused -> throw installedComponentRefusal(problems)
    }

    private companion object {
        /** The SupplyItem list's order, as `SupplyHandlers`': name casefolded, then id. */
        val BY_NAME: Comparator<SupplyItem> = compareBy<SupplyItem> { it.name.lowercase() }.thenBy { it.id.value }
    }
}
