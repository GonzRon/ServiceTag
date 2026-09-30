package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AttachmentDto
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.core.usecase.UpdateAttachmentCommand
import kotlinx.serialization.Serializable

/*
 * #92's attachment shapes on the wire (C5–C8).
 *
 * **The row on the wire is the archive's** (C8): `AttachmentDto`, produced by the same `toDto()` the backup codec
 * uses, every field — so an attachment read here and the same row in `data.json` are one JSON object.
 *
 * **Sensitive (R92-4):** `sourceUri`, `sourceResolvedUri`, `sourceRetrievedAt` and `sourceName` are answered as the
 * row carries them. `sourceUri` is the reference's URI verbatim and may carry a token from its own query (#85 limit
 * 1), so an answer is handled like an export: never logged, never pasted into an issue. The phone's own rule that a
 * full URI is never rendered on a screen (R85-9) is separate and unchanged.
 */

/** Whether the phone has an attachment folder, by state alone: never the folder's display name or authority. */
internal enum class AttachmentFolder { READY, NOT_CONFIGURED, ACCESS_LOST }

internal fun folderOf(state: StoreState): AttachmentFolder = when (state) {
    is StoreState.Ready -> AttachmentFolder.READY
    StoreState.NotConfigured -> AttachmentFolder.NOT_CONFIGURED
    is StoreState.AccessLost -> AttachmentFolder.ACCESS_LOST
}

/** `GET /v1/assets/{id}/attachments` (C5): the asset's own rows, oldest first, and the folder's state. Sensitive. */
@Serializable
internal data class AttachmentListResponse(val attachments: List<AttachmentDto>, val folder: AttachmentFolder)

/** `GET` and `PATCH /v1/attachments/{id}` (C6, C7): one row, of an asset or an event. Sensitive, as above. */
@Serializable
internal data class AttachmentResponse(val attachment: AttachmentDto)

/**
 * `PATCH /v1/attachments/{id}` (C7): a **full** command, mirroring `UpdateAttachmentCommand` — the client reads the
 * row and resends every field with only the intended changes. `displayName`, `kind` and `role` have no default, so
 * an absent one is a 400 naming it; `role: null` clears the role (#67 C2). Nothing here moves the locator or the
 * bytes, and nothing here writes provenance.
 */
@Serializable
internal data class UpdateAttachmentRequest(
    val displayName: String,
    val kind: String,
    val capturedOn: String? = null,
    val notes: String = "",
    val role: String?,
) {
    fun toCommand(): UpdateAttachmentCommand = UpdateAttachmentCommand(
        displayName = displayName,
        kind = enumOr400<AttachmentKind>(kind, "kind"),
        capturedOn = capturedOn,
        notes = notes,
        role = role?.let { enumOr400<DocumentRole>(it, "role") },
    )
}
