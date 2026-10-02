package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.InstalledComponentDto
import com.loosecannon.servicetag.core.backup.SupplyItemDto
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.usecase.CompositionInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/*
 * #47's installed-component shapes on the wire (C3, C20).
 *
 * The two rules `api/ApiDtos.kt` states hold here unchanged. **The responses reuse the backup format's own row DTO**
 * — `InstalledComponentDto`, its `composition` nested — so a row read here and the same row inside `data.json` are
 * the same JSON object, produced by the same `toDto()`. **Requests are declared here and nowhere else**, so an unknown
 * key is the decoder's 400: a `PATCH` naming `assetId`, `parentId`, `removedOn` or `replacesId` is refused before
 * anything is read.
 *
 * Every machine noun here is `installedComponent(s)` or `composition`: an asset's child Assets keep
 * `/v1/assets/{id}/components` and the `components` keys (R47-1), and nothing here touches them. There is no delete
 * request: nothing deletes an installed component.
 */

// --- responses ----------------------------------------------------------------------------------

/**
 * `GET /v1/assets/{id}/installed-components`: every row of the asset, current and removed, by id, each with its
 * `composition`; and each SupplyItem the rows and their entries name **once**, by name casefolded then id.
 */
@Serializable
internal data class InstalledComponentListResponse(
    val installedComponents: List<InstalledComponentDto>,
    val supplyItems: List<SupplyItemDto>,
)

/** Install (201), read and edit (200): the row as stored. */
@Serializable
internal data class InstalledComponentResponse(val installedComponent: InstalledComponentDto)

/** `POST /v1/installed-components/{id}/remove`: the closed row, and every current descendant the same write closed. */
@Serializable
internal data class InstalledComponentRemovedResponse(
    val installedComponent: InstalledComponentDto,
    val closed: List<InstalledComponentDto>,
)

/**
 * `POST /v1/installed-components/{id}/replace` (201): the new row, the row it replaced (now closed), and every current
 * descendant of the replaced row the same write closed.
 */
@Serializable
internal data class InstalledComponentReplacedResponse(
    val installedComponent: InstalledComponentDto,
    val replaced: InstalledComponentDto,
    val closed: List<InstalledComponentDto>,
)

// --- requests -----------------------------------------------------------------------------------

/**
 * One composition entry of a request, in list order. [quantity] must be a JSON **number** — a quoted one, `true` or
 * `null` is the 400 a malformed body is — and is handed on as the decimal the caller wrote; whether it is finite and
 * above zero is the use case's rule (`COMPOSITION_QUANTITY_INVALID`). [id], on an edit, keeps an entry this row
 * already owns; an install mints every id, and a replace ignores any sent.
 */
@Serializable
internal data class CompositionEntryRequest(
    val id: String? = null,
    val supplyId: String,
    val quantity: JsonPrimitive,
    val unit: String = "",
) {
    fun toInput(): CompositionInput {
        val number = !quantity.isString && quantity !is JsonNull && quantity.content.toDoubleOrNull() != null
        if (!number) throw ApiFailure.badRequest("every composition quantity must be a JSON number")
        return CompositionInput(id = id, supplyId = SupplyId(supplyId), quantity = quantity.content, unit = unit)
    }
}

/**
 * `POST /v1/installed-components`: `assetId` and `name` are required. Absent text is `""`, an absent or `""`
 * `installedOn` is a date not recorded (R47-8), an absent or `""` `supplyId` is none, an absent `composition` is none,
 * and an absent `sortOrder` is appended after the current siblings (C16).
 */
@Serializable
internal data class InstallComponentRequest(
    val assetId: String,
    val parentId: String? = null,
    val name: String,
    val supplyId: String? = null,
    val composition: List<CompositionEntryRequest> = emptyList(),
    val serialOrLot: String = "",
    val installedOn: String? = null,
    val notes: String = "",
    val sortOrder: Int? = null,
)

/**
 * `PATCH /v1/installed-components/{id}`: an **overlay** (C3, C20). Every key is optional, and **absent or `null` is
 * unchanged**. A given `""` clears `supplyId`, `installedOn`, `serialOrLot` and `notes`; `name` is never blank (the use
 * case's `NameRequired`); a given `composition` is the whole ordered list and `[]` empties it. The asset, the parent,
 * the removal date and `replacesId` are not here, so naming one is the decoder's 400 (R47-15).
 */
@Serializable
internal data class UpdateInstalledComponentRequest(
    val name: String? = null,
    val supplyId: String? = null,
    val composition: List<CompositionEntryRequest>? = null,
    val serialOrLot: String? = null,
    val installedOn: String? = null,
    val notes: String? = null,
    val sortOrder: Int? = null,
)

/** `POST /v1/installed-components/{id}/remove`: the day it came out, required (R47-8). */
@Serializable
internal data class RemoveInstalledComponentRequest(val removedOn: String)

/**
 * `POST /v1/installed-components/{id}/replace`: `replacedOn` is required. An absent or `null` `name` is the replaced
 * row's (a label, not a link). An absent, `null` or `""` `supplyId` is none, and an absent, `null` or `[]`
 * `composition` is none — **never the replaced row's** (R47-17b): a caller keeps them by sending them, and the use
 * case mints every entry id afresh. Absent `serialOrLot` and `notes` are `""`.
 */
@Serializable
internal data class ReplaceInstalledComponentRequest(
    val replacedOn: String,
    val name: String? = null,
    val supplyId: String? = null,
    val composition: List<CompositionEntryRequest>? = null,
    val serialOrLot: String? = null,
    val notes: String? = null,
)

/** The largest `sortOrder` a request may set: an append past it stays far inside `Int`. */
internal const val MAX_INSTALLED_COMPONENT_SORT_ORDER: Int = 1_000_000

/**
 * A sent `sortOrder`, bounded to `0..`[MAX_INSTALLED_COMPONENT_SORT_ORDER]; out of range is the fallback
 * `INSTALLED_COMPONENT_INVALID` with `field` `sortOrder`, since no use-case problem names it.
 */
internal fun boundedSortOrder(sent: Int): Int =
    if (sent in 0..MAX_INSTALLED_COMPONENT_SORT_ORDER) sent else throw installedComponentInvalid("sortOrder")

/** `""` means none for an optional id or date, so it never reaches a command as a value. */
internal fun String?.orNone(): String? = this?.takeIf { it.isNotEmpty() }
