package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * A hard delete, because there is nothing to orphan: one metadata row, no bytes, no locator and no
 * artifacts entry (I-3). `DeleteAttachment` has a sweep after its transaction for exactly the
 * reason this one does not.
 *
 * An absent row is [ReferenceProblem.NoSuchReference] rather than a silent success, because the API
 * has no `DELETE` and the section's Remove is a confirmed action: a caller asking to remove
 * something that is not there has a stale view, and saying so is more use than a shrug.
 */
class RemoveReference(
    private val references: ReferenceRepository,
    private val uow: UnitOfWork,
) {
    suspend fun run(id: ReferenceId): ReferenceResult<Unit> {
        references.get(id) ?: return ReferenceResult.Refused(ReferenceProblem.NoSuchReference)
        uow.write { references.delete(id) }
        return ReferenceResult.Ok(Unit)
    }
}
