package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentProblem
import com.loosecannon.servicetag.core.model.accepts
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Name, kind, captured-on, notes and — since #67 — the document role. Nothing here touches the
 * locator or the sha256: the bytes are not being edited, so a rename or a role is metadata and the
 * file stays where it is (spec §4; #67 AC 5). There is no store in this class to copy with.
 * `Unchanged` is a refusal rather than a silent no-op so the sheet can close without claiming a
 * save that did not happen.
 *
 * The command's role is taken as given: null clears it, which is why the command has no default
 * for it and every caller passes the row's own.
 */
class UpdateAttachment(
    private val attachments: AttachmentRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(id: AttachmentId, cmd: UpdateAttachmentCommand): AttachmentResult<Attachment> {
        val row = attachments.get(id)
            ?: return AttachmentResult.Refused(AttachmentProblem.OwnerMissing)
        // #67, C1: the same caller's mistake `AddAttachment` refuses, before anything is written.
        require(row.owner.accepts(cmd.role)) {
            "a document role belongs on an asset's, a supply item's or an installed component's file, not an entry's"
        }
        val name = cmd.displayName.trim()
        if (name.isEmpty()) return AttachmentResult.Refused(AttachmentProblem.BlankName)

        val updated = row.copy(
            displayName = name,
            kind = cmd.kind,
            capturedOn = cmd.capturedOn?.trim()?.takeIf { it.isNotEmpty() },
            notes = cmd.notes.trim(),
            role = cmd.role,
            updatedAt = clock.nowMillis(),
        )
        if (updated.copy(updatedAt = row.updatedAt) == row) {
            return AttachmentResult.Refused(AttachmentProblem.Unchanged)
        }
        uow.write { attachments.upsert(updated) }
        return AttachmentResult.Ok(updated)
    }
}
