package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * A new asset. The scan flow calls this with a name alone — the minimum needed to bind a tag to
 * something new — while the editor hands over a full [AssetCommand]. An optional `templateKey`
 * seeds the asset's definitions and profiles from one of [SeedTemplates] in the same transaction
 * as the create (§7); an asset made without one can still be set up later through [ApplyTemplate]
 * directly.
 *
 * The command's `MM-DD` pair is the new asset's season (spec §3.2; inv. 88): a window makes it
 * CALENDAR, none makes it YEAR_ROUND. A component is created here too, so the same holds for it. A
 * new asset has no schedule for a season to strand and none to recompute, and it starts with no
 * break and no activation: those have their own commands.
 */
class CreateAsset(
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val applyTemplate: ApplyTemplate,
) {
    suspend fun run(cmd: AssetCommand, templateKey: String? = null): Asset {
        // A row that does not exist yet cannot be anyone's ancestor, so `null` here: no cycle
        // is reachable on create, only an unknown parent.
        val clean = validateAsset(cmd, assets.all(), id = null)
        val now = clock.nowMillis()
        val asset = Asset(
            id = AssetId(ids.newId()),
            name = clean.name,
            createdAt = now,
            updatedAt = now,
        ).applying(clean, now).copy(seasonMode = clean.legacyPairMode())
        uow.write {
            assets.upsert(asset)
            templateKey?.let { key ->
                applyTemplate.applyInTransaction(asset.id, SeedTemplates.byKey(key) ?: throw UnknownTemplate(key))
            }
        }
        return asset
    }

    /**
     * The short form the scan flow and the pre-2B-2 screens use: a name, the three text fields
     * the Phase 1C editor had, and a template. Delegates to the command form, so validation and
     * storage are the same code either way.
     */
    suspend fun run(
        name: String,
        category: String = "",
        description: String = "",
        notes: String = "",
        templateKey: String? = null,
    ): Asset = run(
        AssetCommand(name = name, category = category, description = description, notes = notes),
        templateKey,
    )
}
