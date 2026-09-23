package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetReferenceDto
import kotlinx.serialization.Serializable

/*
 * The 1.3.0 reference shapes on the wire (master plan §7).
 *
 * The two rules `api/ApiDtos.kt:30`–`41` states hold here unchanged. **The responses reuse the
 * backup format's own row DTO** — `AssetReferenceDto`, `@Serializable` in
 * `com.loosecannon.servicetag.core.backup` — so a reference read here and the same row inside
 * `data.json` are the same JSON object, produced by the same `toDto()`. One schema, not two.
 * **Requests are declared here and nowhere else**, with plain `String` fields.
 *
 * The request shape is a **subset** of the response shape, which is the shipped convention: the
 * two derived fields, `kind` and `scheme`, plus `id`, `createdAt` and `updatedAt`, are
 * response-only. A client reads the derived kind and can never set it.
 */

// --- responses ----------------------------------------------------------------------------------

/** One asset's references, ordered by `displayName` — the order the section reads them in too. */
@Serializable
internal data class ReferenceListResponse(val references: List<AssetReferenceDto>)

@Serializable
internal data class ReferenceResponse(val reference: AssetReferenceDto)

// --- requests -----------------------------------------------------------------------------------

/**
 * `POST /v1/references`. Four fields, and **no `kind`** (owner ruling, master plan §18.18): it is
 * derived from the scheme (spec §3.2), so nothing in the domain can hold a caller's answer, and a
 * field the server accepted and then discarded would read as settable and never take effect. With
 * `ignoreUnknownKeys = false` a body carrying `kind` is therefore a **400 naming it**, exactly as
 * any other misspelled field is.
 */
@Serializable
internal data class CreateReferenceRequest(
    val assetId: String,
    val uri: String,
    val displayName: String,
    val description: String = "",
)

/**
 * `PATCH /v1/references/{id}`. Two fields, and `uri`, `assetId` and `kind` are each an **unknown
 * field** here, so naming one is a 400 and never a silent ignore: the URI is stored exactly as it
 * was validated and is never edited (I-1), and a reference cannot change owner — re-parenting is
 * delete plus re-add (I-6). `UpdateReferenceCommand` carries neither, so neither is expressible
 * below this type either.
 *
 * `null` means **unchanged**, the shipped convention: the handler reads the stored row and
 * overlays only the fields the caller actually named. Blanking the description is `""`.
 */
@Serializable
internal data class UpdateReferenceRequest(
    val displayName: String? = null,
    val description: String? = null,
)
