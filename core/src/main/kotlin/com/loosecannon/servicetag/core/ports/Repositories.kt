package com.loosecannon.servicetag.core.ports

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    suspend fun upsert(asset: Asset)
    suspend fun get(id: AssetId): Asset?
    suspend fun all(): List<Asset>
    suspend fun delete(id: AssetId)

    /**
     * Wipes every asset. With a self-referencing `parent_asset_id` FK, this must delete children
     * before parents (spec §10): the Room adapter walks `AssetTree.parentsFirst(all).asReversed()`
     * and deletes each id individually inside the caller's transaction, rather than issuing a
     * single unordered `DELETE`. A fake repository backed by a plain in-memory map may delete in
     * any order — there is no FK to violate.
     */
    suspend fun deleteAll()
    fun observeAll(): Flow<List<Asset>>
}

interface TagRepository {
    suspend fun upsert(tag: TagBinding)
    suspend fun get(id: TagId): TagBinding?
    suspend fun findByPayload(format: PayloadFormat, key: String): TagBinding?
    suspend fun forAsset(assetId: AssetId): List<TagBinding>
    suspend fun all(): List<TagBinding>
    suspend fun delete(id: TagId)
    suspend fun deleteAll()
    fun observeForAsset(assetId: AssetId): Flow<List<TagBinding>>
}

/**
 * 2.6 — the tombstone port. `external_link` is still exported and restored byte-for-byte, so the
 * three members the backup path uses stay; `get` stays because the round-trip proofs read a row
 * back by id. Everything that *displayed* a link — `forAsset`, `standalone`, `observeAll`,
 * `observeForAsset` — and `delete`, which only the now-removed delete use case called, are gone:
 * the queries still exist on `ExternalLinkDao` for the DAO-level tombstone tests, and nothing
 * above the DAO can reach a link row to show it.
 */
interface LinkRepository {
    suspend fun upsert(link: ExternalLink)
    suspend fun get(id: LinkId): ExternalLink?
    suspend fun all(): List<ExternalLink>
    suspend fun deleteAll()
}

interface DefinitionRepository {
    suspend fun upsert(d: MeasurementDefinition)
    suspend fun get(id: DefinitionId): MeasurementDefinition?
    suspend fun forAsset(assetId: AssetId): List<MeasurementDefinition>
    suspend fun all(): List<MeasurementDefinition>
    suspend fun delete(id: DefinitionId)
    suspend fun deleteAll()
    fun observeForAsset(assetId: AssetId): Flow<List<MeasurementDefinition>>
}

interface ProfileRepository {   // aggregate: upsert replaces fields and consumables
    suspend fun upsert(p: EventProfile)
    suspend fun get(id: ProfileId): EventProfile?
    suspend fun forAsset(assetId: AssetId): List<EventProfile>
    suspend fun all(): List<EventProfile>
    suspend fun delete(id: ProfileId)   // events keep their history; the schema SET NULLs profile_id
    suspend fun deleteAll()
    fun observeForAsset(assetId: AssetId): Flow<List<EventProfile>>
}

interface EventRepository {     // aggregate: upsert replaces measurements and consumables
    suspend fun upsert(e: AssetEvent)
    suspend fun get(id: EventId): AssetEvent?
    suspend fun forAsset(assetId: AssetId): List<AssetEvent>
    suspend fun all(): List<AssetEvent>

    /** How many stored measurements name [definitionId] — what makes a definition "in use". */
    suspend fun countMeasurementsFor(definitionId: DefinitionId): Int
    suspend fun delete(id: EventId)
    suspend fun deleteAll()
    fun observeForAsset(assetId: AssetId): Flow<List<AssetEvent>>   // newest first by §4.1
    fun observe(id: EventId): Flow<AssetEvent?>
}

/**
 * 1.2, the data half. Aggregate: one `upsert` writes the group row and replaces its member rows,
 * as [ProfileRepository] does for a profile's fields. The queries the group screens and the
 * occurrence rules need are declared by the brief that owns them; what is here is what an export,
 * an import and a merge plan cannot be written without.
 *
 * There is no `delete`: archiving is a column, and no 1.2 route, tool or action deletes a group.
 * `deleteAll` exists for the replace import's wipe, which is the only caller.
 */
interface GroupRepository {
    suspend fun upsert(group: MaintenanceGroup)
    suspend fun get(id: GroupId): MaintenanceGroup?
    suspend fun all(): List<MaintenanceGroup>

    /**
     * The groups this Asset is an **open** member of — #55's asset → groups direction, and what the
     * asset screen shows. One Asset may hold several *closed* windows in one group, so the answer is
     * distinct groups and never one entry per window.
     *
     * There is no lookup by name here, and there is none anywhere: a name is descriptive, never
     * identity, in the domain or in a merge (invariant 7).
     */
    suspend fun forAsset(assetId: AssetId): List<MaintenanceGroup>

    /**
     * Every group this Asset has *ever* been a member of, open window or closed.
     *
     * This is the question the recompute asks, and it is the wider one on purpose: a member removed
     * mid-round is **still** required for the round already open (D-10), so an event on a former
     * member can still change a group schedule's derived state. Answering with the open windows only
     * would silently stop rebuilding exactly the schedules a mid-round removal leaves behind.
     */
    suspend fun allWindowsFor(assetId: AssetId): List<MaintenanceGroup>
    suspend fun deleteAll()
    fun observeAll(): Flow<List<MaintenanceGroup>>

    /** The groups one Asset is an open member of, followed. */
    fun observeForAsset(assetId: AssetId): Flow<List<MaintenanceGroup>>
}

/**
 * 1.2, the data half. Aggregate: one `upsert` writes the schedule row and replaces its
 * `schedule_provider` rows.
 *
 * No `delete`, for [GroupRepository]'s reason. `deleteAll` is the replace import's wipe, and it is
 * also what clears `occurrence_closure`: closures have no delete of their own and leave only by the
 * CASCADE from their schedule.
 */
interface ScheduleRepository {
    suspend fun upsert(schedule: MaintenanceSchedule)
    suspend fun get(id: ScheduleId): MaintenanceSchedule?
    suspend fun all(): List<MaintenanceSchedule>

    /** The schedules aimed at this Asset — its own, never a group's. */
    suspend fun forAsset(assetId: AssetId): List<MaintenanceSchedule>

    /** The schedules aimed at this group. One of them may require this Asset; that is not this question. */
    suspend fun forGroup(groupId: GroupId): List<MaintenanceSchedule>
    suspend fun deleteAll()
    fun observeAll(): Flow<List<MaintenanceSchedule>>
}

/**
 * 1.2, the **derived** half: one row per schedule, every column recomputable from configuration,
 * events, closures, membership and today.
 *
 * `upsert` has exactly one caller — the recompute — and that is the whole point of the port
 * (invariant 17). There is no partial update and no per-column setter here, because either of them
 * would be a second write path into derived state, and a second write path is how a due date and
 * the history it is derived from stop agreeing. It is never exported and never merged: `deleteAll`
 * exists for the replace import's wipe, and the recompute that follows the import fills it again.
 */
interface ScheduleStateRepository {
    suspend fun upsert(state: ScheduleState)
    suspend fun get(scheduleId: ScheduleId): ScheduleState?
    suspend fun all(): List<ScheduleState>
    suspend fun deleteAll()
    fun observeAll(): Flow<List<ScheduleState>>
}

/**
 * 1.2. **Insert and query only.** Every other port here offers `upsert`; copying that shape would
 * hand a caller the amendment the closure fact forbids, so this one does not have it — and it has
 * no `delete` and no `deleteAll` either. A closure row is immutable: it is written once and leaves
 * only when its schedule is deleted and the CASCADE takes it.
 */
interface ClosureRepository {
    suspend fun insert(closure: OccurrenceClosure)
    suspend fun forSchedule(scheduleId: ScheduleId): List<OccurrenceClosure>
    /** The row holding `(scheduleId, occurrenceOn)`, which is unique — the second identity. */
    suspend fun find(scheduleId: ScheduleId, occurrenceOn: String): OccurrenceClosure?
    suspend fun all(): List<OccurrenceClosure>
}

interface AttachmentRepository {
    suspend fun upsert(a: Attachment)
    suspend fun get(id: AttachmentId): Attachment?
    suspend fun forOwner(owner: AttachmentOwner): List<Attachment>
    /** The asset's own rows only — not its events'. `DeleteAsset` asks for both, separately. */
    suspend fun forAsset(assetId: AssetId): List<Attachment>
    suspend fun all(): List<Attachment>
    suspend fun delete(id: AttachmentId)
    suspend fun deleteAll()
    suspend fun count(): Int
    fun observeForOwner(owner: AttachmentOwner): Flow<List<Attachment>>
}
