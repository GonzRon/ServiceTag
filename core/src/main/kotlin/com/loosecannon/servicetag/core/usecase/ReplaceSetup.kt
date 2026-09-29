package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository

/** #86 (C12) — the old asset's set-up ids, each mapped to its clone's, for the schedule copies (C11). */
internal data class SetupClone(
    val definitions: Map<DefinitionId, DefinitionId>,
    val profiles: Map<ProfileId, ProfileId>,
) {
    companion object {
        val NONE = SetupClone(emptyMap(), emptyMap())
    }
}

/**
 * #86 (C12; R86-11) — `Readings & actions` carried to [successor] on [ApplyTemplate]'s key algorithm, inside the
 * caller's transaction: new ids for every definition, profile, field and consumable, derived sources and profile
 * fields remapped to the clones, `key`, `sortOrder` and a profile's `templateKey` kept.
 *
 * **The clone set** is every unarchived definition and profile of the old asset, closed under the definitions and
 * profiles the ticked schedules name, the definitions the profiles' fields name, and derived sources. A row in it
 * that is archived is cloned archived (as of now): archiving a source a derived definition still reads is allowed
 * ([ArchiveDefinition]), and a schedule may name an archived definition or profile, so dropping them would break
 * the remap. Measurements, readings and health subjects never carry.
 *
 * ENTERED definitions are minted before DERIVED ones, as [ApplyTemplate] does, so a derived clone's sources exist
 * first.
 */
internal suspend fun cloneSetup(
    successor: AssetId,
    sources: ReplaceSources,
    now: Long,
    ids: IdGenerator,
    definitions: DefinitionRepository,
    profiles: ProfileRepository,
): SetupClone {
    val definitionsById = sources.definitions.associateBy { it.id }
    val namedProfiles = sources.schedules.mapNotNull { it.profileId }.toSet()
    val carriedProfiles = sources.profiles
        .filter { it.archivedAt == null || it.id in namedProfiles }
        .sortedWith(compareBy({ it.sortOrder }, { it.id.value }))

    val wanted = LinkedHashSet<DefinitionId>()
    val pending = ArrayDeque<DefinitionId>().apply {
        sources.definitions.filter { it.archivedAt == null }.forEach { add(it.id) }
        sources.schedules.mapNotNull { it.meterDefinitionId }.forEach { add(it) }
        carriedProfiles.forEach { profile -> profile.fields.forEach { add(it.definitionId) } }
    }
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (wanted.add(id)) definitionsById[id]?.derived?.let { pending.add(it.sourceA); pending.add(it.sourceB) }
    }
    val carriedDefinitions = sources.definitions
        .filter { it.id in wanted }
        .sortedWith(compareBy({ it.derived != null }, { it.sortOrder }, { it.key }))

    val minted = carriedDefinitions.associate { it.id to DefinitionId(ids.newId()) }
    val definitionClones = carriedDefinitions.map { d ->
        d.copy(
            id = minted.getValue(d.id),
            assetId = successor,
            archivedAt = d.archivedAt?.let { now },
            createdAt = now,
            updatedAt = now,
            derived = d.derived?.let { it.copy(sourceA = minted.getValue(it.sourceA), sourceB = minted.getValue(it.sourceB)) },
        )
    }
    val profileIds = LinkedHashMap<ProfileId, ProfileId>()
    val profileClones = carriedProfiles.map { p ->
        val id = ProfileId(ids.newId()).also { profileIds[p.id] = it }
        p.copy(
            id = id,
            assetId = successor,
            archivedAt = p.archivedAt?.let { now },
            createdAt = now,
            updatedAt = now,
            fields = p.fields.map { ProfileField(ids.newId(), minted.getValue(it.definitionId), it.required, it.sortOrder) },
            consumables = p.consumables.map { it.copy(id = ids.newId()) },
        )
    }
    definitionClones.forEach { definitions.upsert(it) }
    profileClones.forEach { profiles.upsert(it) }
    return SetupClone(minted, profileIds)
}
