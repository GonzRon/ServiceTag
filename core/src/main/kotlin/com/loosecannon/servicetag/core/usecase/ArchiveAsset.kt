package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Archive-first (R-9): Phase 1 has no hard delete for an asset, because a tag already stuck to a
 * water heater outlives anyone's tidying-up. Archiving only flips the status — the id, the created
 * date and every tag bound to the asset survive, so a scan of that tag still lands somewhere and
 * [unarchive] puts the asset back exactly as it was.
 *
 * 1.2: the status is an **input to derived due state**, because an archived Asset is excluded from a
 * group round's required set (D-16). [onLifecycleChanged] is how the rebuild that follows is asked
 * for, inside the same transaction; it defaults to nothing so the two shipped tests that hold no
 * scheduling fakes at all still construct this use case with three arguments.
 */
class ArchiveAsset(
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val onLifecycleChanged: suspend (AssetId) -> Unit = {},
) {
    suspend fun run(id: AssetId): Asset = setStatus(id, AssetStatus.ARCHIVED)

    suspend fun unarchive(id: AssetId): Asset = setStatus(id, AssetStatus.ACTIVE)

    private suspend fun setStatus(id: AssetId, status: AssetStatus): Asset {
        val current = assets.get(id) ?: throw NoSuchAsset(id)
        val saved = current.copy(status = status, updatedAt = clock.nowMillis())
        uow.write {
            assets.upsert(saved)
            onLifecycleChanged(id)
        }
        return saved
    }
}
