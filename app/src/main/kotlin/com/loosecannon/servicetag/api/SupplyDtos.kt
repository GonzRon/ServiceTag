package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetSupplyDto
import com.loosecannon.servicetag.core.backup.SupplyItemDto
import com.loosecannon.servicetag.core.usecase.SpecificationInput
import com.loosecannon.servicetag.core.usecase.SupplyItemCommand
import kotlinx.serialization.Serializable

/*
 * #15's SupplyItem and applicability shapes on the wire (C3, C22, C23).
 *
 * The two rules `api/ApiDtos.kt` states hold here unchanged. **The responses reuse the backup format's own row DTOs**
 * — `SupplyItemDto` (its specifications nested) and `AssetSupplyDto` — so a row read here and the same row inside
 * `data.json` are the same JSON object, produced by the same `toDto()`. **Requests are declared here and nowhere
 * else**, with plain `String` fields, so an unknown key is the decoder's 400.
 *
 * There is no quantity, threshold, position, date or file field anywhere here, and there never is (the #95,
 * #47 and #69 fence); there is no delete request for a SupplyItem (R15-5).
 */

// --- responses ----------------------------------------------------------------------------------

/** `GET /v1/supply-items`: every SupplyItem, archived included, by name casefolded then id. */
@Serializable
internal data class SupplyItemListResponse(val supplyItems: List<SupplyItemDto>)

@Serializable
internal data class SupplyItemResponse(val supplyItem: SupplyItemDto)

/** `GET /v1/supply-items/{id}`: the item, and every applicability row naming it, by `(assetId, role, id)`. */
@Serializable
internal data class SupplyItemDetailResponse(val supplyItem: SupplyItemDto, val assetSupplies: List<AssetSupplyDto>)

/**
 * `GET /v1/assets/{id}/supply-items`: the asset's rows by `(role, id)`, and each SupplyItem they name **once**, by
 * name casefolded then id — so a client draws the section without a second read per row.
 */
@Serializable
internal data class AssetSupplyListResponse(val assetSupplies: List<AssetSupplyDto>, val supplyItems: List<SupplyItemDto>)

@Serializable
internal data class AssetSupplyResponse(val assetSupply: AssetSupplyDto)

// --- requests -----------------------------------------------------------------------------------

/**
 * One specification row of a SupplyItem request. [id] names a row this item already holds, to keep — its id and its
 * stored key; [key] absent or `null` asks for none (a new row then takes its label's slug), a typed one must fit the
 * definition key rule. The rule is `SaveSupplyItem`'s, never re-checked here.
 */
@Serializable
internal data class SpecificationRequest(
    val id: String? = null,
    val key: String? = null,
    val label: String,
    val value: String,
    val unit: String = "",
) {
    fun toInput(): SpecificationInput = SpecificationInput(id = id, key = key ?: "", label = label, value = value, unit = unit)
}

/** `POST /v1/supply-items`: `name` is required; absent text is `""` and an absent list is none. */
@Serializable
internal data class CreateSupplyItemRequest(
    val name: String,
    val category: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val partNumber: String = "",
    val preferredUnit: String = "",
    val notes: String = "",
    val specifications: List<SpecificationRequest> = emptyList(),
) {
    fun toCommand(): SupplyItemCommand = SupplyItemCommand(
        name = name,
        category = category,
        manufacturer = manufacturer,
        model = model,
        partNumber = partNumber,
        preferredUnit = preferredUnit,
        notes = notes,
        specifications = specifications.map { it.toInput() },
    )
}

/**
 * `PATCH /v1/supply-items/{id}`: an **overlay** (C22, R15-16). Every key is optional, and **absent or `null` is
 * unchanged** — no SupplyItem field is nullable, so the two mean one thing and no raw-key read is needed. A given
 * `""` clears an optional text field; `name` is never blank (the use case's `NameRequired`); a given
 * `specifications` is the whole ordered list, and `[]` empties it.
 */
@Serializable
internal data class UpdateSupplyItemRequest(
    val name: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val partNumber: String? = null,
    val preferredUnit: String? = null,
    val notes: String? = null,
    val specifications: List<SpecificationRequest>? = null,
)

/** `POST /v1/asset-supplies`: this asset takes this SupplyItem in this role. All three are required. */
@Serializable
internal data class CreateAssetSupplyRequest(val assetId: String, val supplyId: String, val role: String)

/**
 * `PATCH /v1/asset-supplies/{id}`: the role, the row's only editable field, so it is required — a body without it is
 * the decoder's 400. Moving a row to another asset or item is a delete plus a create.
 */
@Serializable
internal data class UpdateAssetSupplyRequest(val role: String)
