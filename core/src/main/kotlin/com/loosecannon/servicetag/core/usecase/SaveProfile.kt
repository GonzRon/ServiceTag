package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Creates or edits one event profile — a quick action, with the fields it puts on the form (spec
 * §6). Validation is collected, not fail-fast. A field has to name an ENTERED definition of the
 * same asset, and may appear once: a DERIVED value is computed, never typed.
 *
 * "Unarchived" is a rule about *adding* a field. A field the profile already carries is kept even
 * if its definition has since been archived: the entry form skips archived rows anyway, so
 * dropping it here would silently rewrite someone's quick action the next time they renamed it.
 *
 * Child ids are identity, not bookkeeping (see [ProfileField] / [ProfileConsumable]): on an update
 * a field keeps the id it already had for that definition, and a consumable keeps the id the
 * editor hands back in [ProfileConsumableInput.id] — but only when this profile already owns that
 * id. Everything else, including an id from some other profile, is minted fresh from [ids].
 *
 * #15 (C19, C20): each line stores the [ProfileConsumableInput.supplyId] it is given, and its name and unit stay
 * its own readable snapshot. A link must name a SupplyItem [supplyItems] holds, archived or not (R15-6); one that
 * does not is [ProfileProblem.UnknownSupplyItem], collected with the rest before any write. Nothing here reads a
 * line's words from a SupplyItem, and no SupplyItem edit ever rewrites a stored line.
 */
class SaveProfile(
    private val profiles: ProfileRepository,
    private val definitions: DefinitionRepository,
    private val assets: AssetRepository,
    private val supplyItems: SupplyItemRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(id: ProfileId?, cmd: ProfileCommand): EventProfile {
        assets.get(cmd.assetId) ?: throw NoSuchAsset(cmd.assetId)
        val existing = id?.let { profiles.get(it) ?: throw NoSuchProfile(it) }
        if (existing != null && existing.assetId != cmd.assetId) {
            throw EventOwnership("profile ${existing.id.value} does not belong to asset ${cmd.assetId.value}")
        }
        val problems = mutableListOf<ProfileProblem>()

        val name = cmd.name.trim()
        if (name.isEmpty()) {
            problems += ProfileProblem.NameRequired
        } else {
            // Archived profiles keep their name reserved; a profile may keep its own.
            val taken = profiles.forAsset(cmd.assetId)
                .filter { it.id != existing?.id }
                .any { it.name.equals(name, ignoreCase = true) }
            if (taken) problems += ProfileProblem.NameTaken
        }

        val seen = mutableSetOf<DefinitionId>()
        val fields = mutableListOf<ProfileField>()
        val alreadyOnProfile = existing?.fields?.map { it.definitionId }?.toSet().orEmpty()
        for (input in cmd.fields) {
            val definition = definitions.get(input.definitionId)
            val cause = when {
                definition == null || definition.assetId != cmd.assetId -> BadFieldCause.NOT_THIS_ASSET
                definition.kind != DefinitionKind.ENTERED -> BadFieldCause.DERIVED
                definition.archivedAt != null && input.definitionId !in alreadyOnProfile -> BadFieldCause.ARCHIVED
                !seen.add(input.definitionId) -> BadFieldCause.LISTED_TWICE
                else -> null
            }
            if (cause != null) {
                problems += ProfileProblem.BadField(input.definitionId, cause)
                continue
            }
            fields += ProfileField(
                id = existing?.fields?.firstOrNull { it.definitionId == input.definitionId }?.id ?: ids.newId(),
                definitionId = input.definitionId,
                required = input.required,
                sortOrder = fields.size,
            )
        }

        val ownConsumableIds = existing?.consumables?.map { it.id }?.toSet().orEmpty()
        val consumables = cmd.consumables.mapIndexedNotNull { i, input ->
            val consumableName = input.name.trim()
            val quantity = input.defaultQuantity
            val line = if (consumableName.isEmpty() || (quantity != null && (quantity < 0.0 || !quantity.isFinite()))) {
                problems += ProfileProblem.BadConsumable(i)
                null
            } else {
                ProfileConsumable(
                    // An id is only identity if this profile already owns it: one carried over from
                    // another profile (or made up) would collide with a row we don't control.
                    id = input.id?.takeIf { it in ownConsumableIds } ?: ids.newId(),
                    name = consumableName,
                    defaultQuantity = quantity,
                    unit = input.unit.trim(),
                    sortOrder = i,
                    supplyId = input.supplyId,
                )
            }
            // A link resolves when the SupplyItem exists at all: an archived one is still the line's product.
            input.supplyId?.let { if (supplyItems.get(it) == null) problems += ProfileProblem.UnknownSupplyItem(i) }
            line
        }

        if (problems.isNotEmpty()) throw ProfileValidation(problems)

        val now = clock.nowMillis()
        val saved = EventProfile(
            id = existing?.id ?: ProfileId(ids.newId()),
            assetId = cmd.assetId,
            name = name,
            eventKind = cmd.eventKind,
            defaultTitle = cmd.defaultTitle.trim().ifEmpty { name },
            templateKey = existing?.templateKey,
            sortOrder = existing?.sortOrder
                ?: ((profiles.forAsset(cmd.assetId).maxOfOrNull { it.sortOrder } ?: -1) + 1),
            archivedAt = existing?.archivedAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            fields = fields,
            consumables = consumables,
        )
        uow.write { profiles.upsert(saved) }
        return saved
    }
}
