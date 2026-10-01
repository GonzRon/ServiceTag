package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.journal.Template
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.DerivedSpec
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/** [CreateAsset] passed a `templateKey` that [com.loosecannon.servicetag.core.journal.SeedTemplates] doesn't know. */
class UnknownTemplate(key: String) : IllegalArgumentException("no template $key")

sealed interface ApplyResult {
    /** The asset already has definitions or profiles of its own; nothing was touched. */
    data object AlreadySetUp : ApplyResult
    data class Applied(val definitions: List<MeasurementDefinition>, val profiles: List<EventProfile>) : ApplyResult
}

/**
 * Seeds an asset's definitions and profiles from a starter [Template], once. An asset that
 * already has any definitions or profiles of its own is left alone (§7: a template is starter
 * data, applying it twice must not duplicate or clobber rows a person may have already edited).
 */
class ApplyTemplate(
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId, template: Template): ApplyResult = uow.write {
        applyInTransaction(assetId, template)
    }

    /**
     * The work itself, without opening its own transaction. [FakeUnitOfWork][com.loosecannon.servicetag.core.testing.FakeUnitOfWork]'s
     * `write` is not safely re-entrant (a nested call double-counts commits and can reset the
     * transaction witness before the outer block finishes), so [CreateAsset] — already inside its
     * own `uow.write` — calls this directly instead of nesting through [run].
     */
    internal suspend fun applyInTransaction(assetId: AssetId, template: Template): ApplyResult {
        val asset = assets.get(assetId) ?: throw NoSuchAsset(assetId)
        if (definitions.forAsset(assetId).isNotEmpty() || profiles.forAsset(assetId).isNotEmpty()) {
            return ApplyResult.AlreadySetUp
        }
        val now = clock.nowMillis()
        // ENTERED definitions before DERIVED ones, so a derived spec's sources always have a minted id
        // to resolve against by the time it's built (§4: derived source keys resolved to fresh ids).
        val ordered = template.definitions.sortedBy { it.derived != null }
        val mintedIds = ordered.associate { it.key to DefinitionId(ids.newId()) }
        val defs = ordered.mapIndexed { i, d ->
            MeasurementDefinition(
                id = mintedIds.getValue(d.key),
                assetId = assetId,
                key = d.key,
                label = d.label,
                unit = d.unit,
                valueType = d.valueType,
                decimals = d.decimals,
                rangeLow = d.rangeLow,
                rangeHigh = d.rangeHigh,
                isMeter = d.isMeter,
                sortOrder = i,
                archivedAt = null,
                createdAt = now,
                updatedAt = now,
                kind = if (d.derived != null) DefinitionKind.DERIVED else DefinitionKind.ENTERED,
                derived = d.derived?.let { (sourceAKey, sourceBKey) ->
                    DerivedSpec(DerivedFormula.PERCENT_DROP, mintedIds.getValue(sourceAKey), mintedIds.getValue(sourceBKey))
                },
            )
        }
        val byKey = defs.associateBy { it.key }
        val profs = template.profiles.mapIndexed { i, p ->
            EventProfile(
                id = ProfileId(ids.newId()),
                assetId = assetId,
                name = p.name,
                eventKind = p.eventKind,
                defaultTitle = p.defaultTitle,
                templateKey = template.key,
                sortOrder = i,
                archivedAt = null,
                createdAt = now,
                updatedAt = now,
                fields = p.fields.mapIndexed { j, (key, required) ->
                    ProfileField(ids.newId(), byKey.getValue(key).id, required, j)
                },
                consumables = p.consumables.mapIndexed { j, c ->
                    // #15 (C21, R15-10): a template creates no link — it stays name-only.
                    ProfileConsumable(ids.newId(), c.name, c.defaultQuantity, c.unit, j, supplyId = null)
                },
            )
        }
        defs.forEach { definitions.upsert(it) }
        profs.forEach { profiles.upsert(it) }
        if (asset.templateKey == null) assets.upsert(asset.copy(templateKey = template.key, updatedAt = now))
        return ApplyResult.Applied(defs, profs)
    }
}
