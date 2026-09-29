package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Retirement is data, not a lifecycle enum (spec §7): `retiredOn` is the whole state, so a thing
 * that is gone from the house can still be archived — or not — and its tags still resolve. The
 * date is the user's, not the clock's: backdating "I replaced this in April" is the normal case.
 * Retiring a parent does not touch its children.
 *
 * 1.2: `retiredOn` is an **input to derived due state**, because it closes the Asset's membership
 * windows for any group round opening on or after it (D-16). [onLifecycleChanged] asks for the
 * rebuild that follows, inside the same transaction, and has **no default** for [ArchiveAsset]'s
 * reason. Because the date is back-datable, that rebuild is also what makes a correction take
 * effect at all.
 */
class RetireAsset(
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val onLifecycleChanged: suspend (AssetId) -> Unit,
) {
    suspend fun retire(id: AssetId, on: String): Asset {
        val date = on.trim()
        if (!isIsoDate(date)) throw AssetValidation(listOf(AssetProblem.BadDate("retiredOn")))
        return save(id, date)
    }

    suspend fun unretire(id: AssetId): Asset = save(id, null)

    private suspend fun save(id: AssetId, retiredOn: String?): Asset {
        val current = assets.get(id) ?: throw NoSuchAsset(id)
        val now = clock.nowMillis()
        return uow.write { retireInTransaction(current, retiredOn, now) }
    }

    /**
     * #86 (C15): the write itself — the row with [retiredOn] stamped at [now], and the rebuild that follows — inside
     * the caller's transaction, so `ReplaceAsset` retires its old asset in its own one write.
     */
    internal suspend fun retireInTransaction(current: Asset, retiredOn: String?, now: Long): Asset {
        val saved = current.copy(retiredOn = retiredOn, updatedAt = now)
        assets.upsert(saved)
        onLifecycleChanged(current.id)
        return saved
    }
}
