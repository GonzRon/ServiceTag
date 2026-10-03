package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.usecase.SeasonSyncOwnsSeason

/**
 * #16 (C15; R16-1) — the one season authority. While an asset's binding is **enabled**, in any mode, it owns that
 * asset's season, and every other writer of a season fact asks here first: `RecordSeasonActivation` (unless the
 * applier calls its body with `guarded = false`), `AcceptSeasonOffer`, `SetSeasonMode` and `SaveAssetSettings`, each
 * after its 404 and 422 and before its first 409, and only where it would write a season fact. A stopped binding
 * refuses nothing.
 *
 * Deliberately not asked: the asset PATCH's legacy pair (a synced asset is MANUAL, where a different pair is already
 * the shipped 422), the backup merge and replace and the Transfer Pack (they bring history; the next run reconciles
 * it), and a new asset or successor (it has no binding).
 */
class SeasonSyncGuard(private val bindings: SeasonSyncRepository) {
    /** Whether [assetId]'s binding exists and is enabled — the one place that rule is read. */
    suspend fun isSynced(assetId: AssetId): Boolean = bindings.get(assetId)?.enabled == true

    /** Throws [SeasonSyncOwnsSeason] when [isSynced]. */
    suspend fun requireNotSynced(assetId: AssetId) {
        if (isSynced(assetId)) throw SeasonSyncOwnsSeason(assetId)
    }
}
