package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Sets or clears an asset's warranty reminder lead (#79, C2; R79-11, R79-12), on the
 * [SetHealthPolicy] shape — the only way the lead changes apart from the settings save that asks the
 * same rule ([SaveAssetSettings]), and the asset edit that clears the date and with it the lead.
 *
 * A lead must be whole days, at least one, with no upper bound, and the stored asset must have a
 * warranty date: otherwise 422 [WarrantyReminderValidation], nothing written. `null` turns the reminder
 * off. A lead equal to the stored one writes nothing; a change writes the asset row alone and moves its
 * `updatedAt` — nothing is recomputed, because no schedule reads the lead.
 */
class SetWarrantyReminder(
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId, cmd: WarrantyReminderCommand): Asset = uow.write {
        val current = assets.get(assetId) ?: throw NoSuchAsset(assetId)
        val problems = warrantyReminderProblems(cmd.leadDays, current.warrantyExpiresOn)
        if (problems.isNotEmpty()) throw WarrantyReminderValidation(problems)
        if (current.warrantyReminderLeadDays == cmd.leadDays) return@write current
        val next = current.copy(warrantyReminderLeadDays = cmd.leadDays, updatedAt = clock.nowMillis())
        assets.upsert(next)
        next
    }
}
