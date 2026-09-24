package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Sets how an asset combines its subjects' health, and the subject TRACK_ONE follows (spec §6.5;
 * master plan §10.1) — the only way either column changes, apart from the settings save that calls
 * the same rule ([SaveAssetSettings]).
 *
 * TRACK_ONE needs a primary that is a non-archived subject of this asset, and any other aggregation
 * takes none: otherwise 422 [HealthValidation] with [HealthProblem.PrimaryInvalid]. A policy equal to
 * the stored one writes nothing. A change writes the asset row alone — health is computed at read
 * time, so there is nothing to recompute and no health value to store (inv. 82, 111).
 */
class SetHealthPolicy(
    private val assets: AssetRepository,
    private val subjects: HealthSubjectRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId, cmd: HealthPolicyCommand): Asset = uow.write {
        val current = assets.get(assetId) ?: throw NoSuchAsset(assetId)
        val problems = healthPolicyProblems(assetId, cmd, subjects)
        if (problems.isNotEmpty()) throw HealthValidation(problems)
        if (current.healthAggregation == cmd.healthAggregation &&
            current.healthPrimarySubjectId == cmd.healthPrimarySubjectId
        ) {
            return@write current
        }
        val next = current.copy(
            healthAggregation = cmd.healthAggregation,
            healthPrimarySubjectId = cmd.healthPrimarySubjectId,
            updatedAt = clock.nowMillis(),
        )
        assets.upsert(next)
        next
    }
}
