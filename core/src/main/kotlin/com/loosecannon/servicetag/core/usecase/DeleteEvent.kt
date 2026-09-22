package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Deletes an [com.loosecannon.servicetag.core.model.AssetEvent]; its children go with it (CASCADE),
 * attachment rows included. One `uow.write`, which also reads the locators the cascade is about
 * to take out of reach; the bytes then go after the commit, best effort (spec §6).
 *
 * 1.2: a delete rebuilds too, and it is the direction that proves the engine has no hidden state —
 * deleting the latest completion moves the due date **back** and reopens its round (invariant 24).
 * The row is read before it goes, because after the delete there is no asset to ask about.
 * Deleting an event that is not there stays a no-op, so there is nothing to rebuild for.
 */
class DeleteEvent(
    private val events: EventRepository,
    private val attachments: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(id: EventId) {
        val doomed = uow.write {
            val assetId = events.get(id)?.assetId
            val locators = attachments.forOwner(AttachmentOwner.OfEvent(id)).map { it.storageLocator }
            events.delete(id)
            assetId?.let { recompute.forAsset(it) }
            locators
        }
        storage.sweepBytes(doomed)
    }
}
