package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.accepts
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.usecase.AttachmentResult
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.UpdateAttachment
import com.loosecannon.servicetag.core.usecase.isIsoDate
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.prefs.InstallationIdentity

/**
 * #92's attachment rows (C5–C8), reached from the router as `handlers.attachmentRoutes.*` — not `attachments`, which
 * `ApiHandlers` already spends on its repository for the status count.
 *
 * The rule is every collaborator's: **the one write is one `UpdateAttachment` call**, and the two reads read the
 * attachment repository as the DOCUMENTS section does. Nothing here deletes an attachment, reads or writes its bytes,
 * or writes provenance: the API adds and amends, the phone removes. A role on an event's attachment is refused here as
 * a 422 before the use case is called, because the use case treats it as a programming error (R67-11).
 *
 * It also holds this installation's [InstallationIdentity] (C5a), which `/v1/status` reports and the upload's id
 * derivation (C13) reads.
 */
internal class AttachmentHandlers(
    private val attachments: AttachmentRepository,
    private val assets: AssetRepository,
    private val storage: AttachmentStorage,
    private val updateAttachment: UpdateAttachment,
    private val installation: InstallationIdentity,
) {
    constructor(graph: AppGraph) : this(
        graph.attachments, graph.assets, graph.attachmentStorage, graph.updateAttachment, graph.installationIdentity,
    )

    /** `/v1/status`' `installationId` (C5a): read from its file once per process, never minted twice. */
    fun installationId(): String = installation.id()

    /**
     * `GET /v1/assets/{id}/attachments` (C5): the asset's own rows — never its events' — oldest first, then by id, and
     * the folder's state by name alone. A transferred-out asset's rows read like any other. Writes nothing.
     */
    suspend fun listForAsset(assetId: String): ApiResponse {
        val id = AssetId(assetId)
        assets.get(id) ?: throw NoSuchAsset(id)
        val rows = attachments.forAsset(id).sortedWith(compareBy<Attachment>({ it.createdAt }, { it.id.value }))
        return ok(
            AttachmentListResponse.serializer(),
            AttachmentListResponse(rows.map { it.toDto() }, folderOf(storage.state())),
        )
    }

    /** `GET /v1/attachments/{id}` (C6): one row of any owner. Writes nothing. */
    suspend fun get(attachmentId: String): ApiResponse =
        ok(AttachmentResponse.serializer(), AttachmentResponse(row(attachmentId).toDto()))

    /**
     * `PATCH /v1/attachments/{id}` (C7): decode, then the row, then `capturedOn`, then the role against the row's
     * owner, then the use case. A no-op is 200 with the stored row and nothing written, as a reference's is.
     * `capturedOn` is trimmed and a blank one is null, as the asset commands and the use case treat a date; only a
     * non-blank value that is not an ISO day is refused.
     */
    suspend fun update(attachmentId: String, request: ApiRequest): ApiResponse {
        val decoded = request.decode(UpdateAttachmentRequest.serializer()).toCommand()
        val command = decoded.copy(capturedOn = decoded.capturedOn?.trim()?.takeIf { it.isNotEmpty() })
        val row = row(attachmentId)
        if (command.capturedOn?.let(::isIsoDate) == false) throw attachmentBadDate()
        if (!row.owner.accepts(command.role)) throw attachmentRoleNotAllowed()
        val saved = when (val result = updateAttachment.run(row.id, command)) {
            is AttachmentResult.Ok -> result.value
            is AttachmentResult.Refused -> attachmentRefusal(result.problem)?.let { throw it } ?: row
        }
        return ok(AttachmentResponse.serializer(), AttachmentResponse(saved.toDto()))
    }

    private suspend fun row(id: String): Attachment = attachments.get(AttachmentId(id)) ?: throw noSuchAttachment()
}
