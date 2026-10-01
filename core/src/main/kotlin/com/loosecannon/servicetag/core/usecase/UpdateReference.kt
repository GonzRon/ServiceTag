package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.references.MAX_REFERENCE_DESCRIPTION_CHARS
import com.loosecannon.servicetag.core.references.ReferenceText
import com.loosecannon.servicetag.core.references.accepts

/**
 * Name, description and (#91) the document role, and never anything else: the `uri` is stored
 * exactly as it was validated and is never edited (I-1), and a reference cannot change owner —
 * re-parenting is delete plus re-add (I-6). [UpdateReferenceCommand] carries neither, so neither is
 * expressible here.
 *
 * The order is `NoSuchReference`, `BlankName`, `RoleNotAllowed` (the row's kind asked whether it
 * takes the command's role, R91-1), then `Unchanged`, then the write. The role is the command's
 * exactly: `null` is "no role", so a role is cleared by naming none, never by forgetting it.
 *
 * `Unchanged` is a refusal that writes nothing, and that is not a convenience (plan §18.13): the
 * merge's `IDENTICAL` compares every backup-format field, `updatedAt` included, so a write on every
 * call would make a re-imported archive `CONTENT_DIFFERS` on the next merge. Over the API the
 * result is 200 with the stored row, not an error. It compares the name, the description **and the
 * role**, so a role-only change writes and moves `updatedAt`.
 */
class UpdateReference(
    private val references: ReferenceRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(id: ReferenceId, cmd: UpdateReferenceCommand): ReferenceResult<AssetReference> {
        val row = references.get(id)
            ?: return ReferenceResult.Refused(ReferenceProblem.NoSuchReference)
        val displayName = ReferenceText.sanitiseName(cmd.displayName)
        if (displayName.isEmpty()) return ReferenceResult.Refused(ReferenceProblem.BlankName)
        if (!row.kind.accepts(cmd.role)) return ReferenceResult.Refused(ReferenceProblem.RoleNotAllowed)
        val description = cmd.description.trim().take(MAX_REFERENCE_DESCRIPTION_CHARS)
        if (displayName == row.displayName && description == row.description && cmd.role == row.role) {
            return ReferenceResult.Refused(ReferenceProblem.Unchanged)
        }

        val updated = row.copy(
            displayName = displayName,
            description = description,
            role = cmd.role,
            updatedAt = clock.nowMillis(),
        )
        uow.write { references.upsert(updated) }
        return ReferenceResult.Ok(updated)
    }
}
