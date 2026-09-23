package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.journal.EventChronology
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AssetStatus
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
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A fake store that can hand back a closure restoring its state at the moment of the call. */
interface Rollbackable {
    fun snapshot(): () -> Unit
}

/**
 * Bookkeeping shared by a [FakeUnitOfWork] and the stores it covers, so a test can ask which
 * transaction — if any — a table read happened in.
 */
class TransactionWitness {
    var inRead = false
    var inWrite = false

    /** Table reads that ran in no transaction at all, i.e. outside any snapshot. */
    var readsOutsideSnapshot = 0
        private set

    fun observeAll() {
        if (!inRead && !inWrite) readsOutsideSnapshot += 1
    }
}

/** A fake store that reports its `all()` calls to the witness a [FakeUnitOfWork] hands it. */
interface Witnessed {
    var witness: TransactionWitness?
}

/** Thrown by a rigged fake repository so tests can force a mid-transaction failure. */
class RiggedFailure(message: String) : RuntimeException(message)

private class UpsertRig(private val label: String) {
    var failOnUpsert: Int? = null
    private var seen = 0

    fun check() {
        seen += 1
        if (seen == failOnUpsert) throw RiggedFailure("rigged $label upsert failure at #$seen")
    }
}

/** Open so a test can subclass it to rig a check on upsert order (e.g. FK-like checks). */
open class InMemoryAssetRepository : AssetRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, Asset>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("asset")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(asset: Asset) {
        rig.check()
        rows[asset.id.value] = asset
        version.value += 1
    }

    override suspend fun get(id: AssetId): Asset? = rows[id.value]

    override suspend fun all(): List<Asset> {
        witness?.observeAll()
        return rows.values.toList()
    }

    override suspend fun delete(id: AssetId) { rows.remove(id.value); version.value += 1 }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeAll(): Flow<List<Asset>> = version.map {
        rows.values.sortedWith(
            compareBy({ if (it.status == AssetStatus.ACTIVE) 0 else 1 }, { it.name.lowercase() }),
        )
    }
}

class InMemoryTagRepository : TagRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, TagBinding>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("tag")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(tag: TagBinding) {
        rig.check()
        rows[tag.id.value] = tag
        version.value += 1
    }

    override suspend fun get(id: TagId): TagBinding? = rows[id.value]

    override suspend fun findByPayload(format: PayloadFormat, key: String): TagBinding? =
        rows.values.firstOrNull { it.payloadFormat == format && it.payloadKey == key }

    override suspend fun forAsset(assetId: AssetId): List<TagBinding> =
        rows.values.filter { (it.target as? TagTarget.AssetTarget)?.assetId == assetId }

    override suspend fun all(): List<TagBinding> {
        witness?.observeAll()
        return rows.values.toList()
    }

    override suspend fun delete(id: TagId) { rows.remove(id.value); version.value += 1 }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeForAsset(assetId: AssetId): Flow<List<TagBinding>> = version.map {
        rows.values.filter { (it.target as? TagTarget.AssetTarget)?.assetId == assetId }
    }
}

class InMemoryLinkRepository : LinkRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, ExternalLink>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("link")
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy) }
    }

    override suspend fun upsert(link: ExternalLink) {
        rig.check()
        rows[link.id.value] = link
    }

    override suspend fun get(id: LinkId): ExternalLink? = rows[id.value]

    override suspend fun all(): List<ExternalLink> {
        witness?.observeAll()
        return rows.values.toList()
    }

    override suspend fun deleteAll() { rows.clear() }
}

/** Open so a test can subclass it to rig a check on upsert order (e.g. FK-like checks). */
open class InMemoryDefinitionRepository : DefinitionRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, MeasurementDefinition>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("definition")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(d: MeasurementDefinition) {
        rig.check()
        rows[d.id.value] = d
        version.value += 1
    }

    override suspend fun get(id: DefinitionId): MeasurementDefinition? = rows[id.value]

    override suspend fun forAsset(assetId: AssetId): List<MeasurementDefinition> =
        rows.values.filter { it.assetId == assetId }

    override suspend fun all(): List<MeasurementDefinition> {
        witness?.observeAll()
        return rows.values.toList()
    }

    override suspend fun delete(id: DefinitionId) { rows.remove(id.value); version.value += 1 }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeForAsset(assetId: AssetId): Flow<List<MeasurementDefinition>> = version.map {
        rows.values.filter { it.assetId == assetId }.sortedBy { it.sortOrder }
    }
}

class InMemoryProfileRepository : ProfileRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, EventProfile>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("profile")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(p: EventProfile) {
        rig.check()
        rows[p.id.value] = p
        version.value += 1
    }

    override suspend fun get(id: ProfileId): EventProfile? = rows[id.value]

    override suspend fun forAsset(assetId: AssetId): List<EventProfile> =
        rows.values.filter { it.assetId == assetId }

    override suspend fun all(): List<EventProfile> {
        witness?.observeAll()
        return rows.values.toList()
    }

    /**
     * Stands in for the schema's `ON DELETE SET NULL` on `event.profile_id`: production relies on
     * the foreign key, so a test that cares wires this to [InMemoryEventRepository.clearProfile].
     */
    var onDeleted: (ProfileId) -> Unit = {}

    override suspend fun delete(id: ProfileId) {
        rows.remove(id.value)
        onDeleted(id)
        version.value += 1
    }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeForAsset(assetId: AssetId): Flow<List<EventProfile>> = version.map {
        rows.values.filter { it.assetId == assetId }.sortedBy { it.sortOrder }
    }
}

class InMemoryEventRepository : EventRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, AssetEvent>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("event")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(e: AssetEvent) {
        rig.check()
        rows[e.id.value] = e
        version.value += 1
    }

    override suspend fun get(id: EventId): AssetEvent? = rows[id.value]

    override suspend fun forAsset(assetId: AssetId): List<AssetEvent> =
        rows.values.filter { it.assetId == assetId }

    override suspend fun all(): List<AssetEvent> {
        witness?.observeAll()
        return rows.values.toList()
    }

    override suspend fun countMeasurementsFor(definitionId: DefinitionId): Int =
        rows.values.sumOf { e -> e.measurements.count { it.definitionId == definitionId } }

    /** The SET NULL half of deleting a profile, driven by [InMemoryProfileRepository.onDeleted]. */
    fun clearProfile(profileId: ProfileId) {
        rows.values.filter { it.profileId == profileId }
            .forEach { rows[it.id.value] = it.copy(profileId = null) }
        version.value += 1
    }

    override suspend fun delete(id: EventId) { rows.remove(id.value); version.value += 1 }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeForAsset(assetId: AssetId): Flow<List<AssetEvent>> = version.map {
        rows.values.filter { it.assetId == assetId }.sortedWith(EventChronology.reversed())
    }

    override fun observe(id: EventId): Flow<AssetEvent?> = version.map { rows[id.value] }
}

open class InMemoryAttachmentRepository : AttachmentRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, Attachment>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int? = null
    private var upserts = 0

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(a: Attachment) {
        upserts += 1
        if (upserts == failOnUpsert) throw RiggedFailure("rigged attachment upsert failure at #$upserts")
        rows[a.id.value] = a
        version.value += 1
    }

    override suspend fun get(id: AttachmentId): Attachment? = rows[id.value]

    override suspend fun forOwner(owner: AttachmentOwner): List<Attachment> =
        rows.values.filter { it.owner == owner }

    override suspend fun forAsset(assetId: AssetId): List<Attachment> =
        forOwner(AttachmentOwner.OfAsset(assetId))

    override suspend fun all(): List<Attachment> {
        witness?.observeAll()
        return rows.values.toList()
    }

    override suspend fun delete(id: AttachmentId) { rows.remove(id.value); version.value += 1 }
    override suspend fun deleteAll() { rows.clear(); version.value += 1 }
    override suspend fun count(): Int = rows.size

    override fun observeForOwner(owner: AttachmentOwner): Flow<List<Attachment>> = version.map {
        rows.values.filter { it.owner == owner }.sortedBy { it.displayName.lowercase() }
    }
}

class InMemoryGroupRepository : GroupRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, MaintenanceGroup>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("group")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(group: MaintenanceGroup) {
        rig.check()
        rows[group.id.value] = group
        version.value += 1
    }

    override suspend fun get(id: GroupId): MaintenanceGroup? = rows[id.value]

    override suspend fun all(): List<MaintenanceGroup> {
        witness?.observeAll()
        return rows.values.toList()
    }

    /** The open windows only, as `removed_at IS NULL` in the DAO's query does. */
    override suspend fun forAsset(assetId: AssetId): List<MaintenanceGroup> =
        rows.values.filter { g -> g.members.any { it.assetId == assetId && it.removedAt == null } }

    /** Every window, open or closed: the question the recompute asks. */
    override suspend fun allWindowsFor(assetId: AssetId): List<MaintenanceGroup> =
        rows.values.filter { g -> g.members.any { it.assetId == assetId } }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeAll(): Flow<List<MaintenanceGroup>> = version.map {
        rows.values.sortedWith(compareBy({ it.name }, { it.id.value }))
    }

    override fun observeForAsset(assetId: AssetId): Flow<List<MaintenanceGroup>> = version.map {
        rows.values
            .filter { g -> g.members.any { it.assetId == assetId && it.removedAt == null } }
            .sortedWith(compareBy({ it.name }, { it.id.value }))
    }
}

/**
 * [deleteAll] clears [cascadesTo] as well, because that is what the schema does: a closure row has
 * no delete of its own and leaves only by the CASCADE from its schedule. A fake with no closure
 * store passes null, as a test that has no closures does.
 */
class InMemoryScheduleRepository(
    private val cascadesTo: InMemoryClosureRepository? = null,
    private val statesCascadeTo: InMemoryScheduleStateRepository? = null,
) : ScheduleRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, MaintenanceSchedule>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("schedule")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(schedule: MaintenanceSchedule) {
        rig.check()
        rows[schedule.id.value] = schedule
        version.value += 1
    }

    override suspend fun get(id: ScheduleId): MaintenanceSchedule? = rows[id.value]

    override suspend fun all(): List<MaintenanceSchedule> {
        witness?.observeAll()
        return rows.values.toList()
    }

    override suspend fun forAsset(assetId: AssetId): List<MaintenanceSchedule> =
        rows.values.filter { (it.target as? ScheduleTarget.AssetTarget)?.assetId == assetId }
            .sortedWith(compareBy({ it.title }, { it.id.value }))

    override suspend fun forGroup(groupId: GroupId): List<MaintenanceSchedule> =
        rows.values.filter { (it.target as? ScheduleTarget.GroupTarget)?.groupId == groupId }
            .sortedWith(compareBy({ it.title }, { it.id.value }))

    override suspend fun deleteAll() {
        rows.clear()
        version.value += 1
        cascadesTo?.cascadeFromSchedules()
        statesCascadeTo?.cascadeFromSchedules()
    }

    override fun observeAll(): Flow<List<MaintenanceSchedule>> = version.map {
        rows.values.sortedWith(compareBy({ it.title }, { it.id.value }))
    }
}

/**
 * The derived state, in a map. [cascadeFromSchedules] is not part of the port and is not a delete
 * path: it is how [InMemoryScheduleRepository] reproduces the CASCADE the schema performs when a
 * schedule row goes, which is the only thing that removes a state row other than the wipe.
 */
class InMemoryScheduleStateRepository : ScheduleStateRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, ScheduleState>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("schedule state")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(state: ScheduleState) {
        rig.check()
        rows[state.scheduleId.value] = state
        version.value += 1
    }

    override suspend fun get(scheduleId: ScheduleId): ScheduleState? = rows[scheduleId.value]

    override suspend fun all(): List<ScheduleState> {
        witness?.observeAll()
        return rows.values.toList()
    }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeAll(): Flow<List<ScheduleState>> = version.map {
        rows.values.sortedWith(compareBy({ it.effectiveDueOn ?: "" }, { it.scheduleId.value }))
    }

    internal fun cascadeFromSchedules() { rows.clear(); version.value += 1 }
}

/**
 * Insert and query only, exactly as the port is. [cascadeFromSchedules] is not part of the port: it
 * is how [InMemoryScheduleRepository] reproduces the CASCADE, and it is the only way a row leaves.
 */
class InMemoryClosureRepository : ClosureRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, OccurrenceClosure>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("closure")
    var failOnInsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy) }
    }

    override suspend fun insert(closure: OccurrenceClosure) {
        rig.check()
        // The unique index, as a fake: one row per `(schedule_id, occurrence_on)`.
        val pair = closure.scheduleId.value to closure.occurrenceOn
        if (rows.values.any { it.scheduleId.value to it.occurrenceOn == pair }) {
            throw RiggedFailure("occurrence_closure already holds ${pair.first}/${pair.second}")
        }
        rows[closure.id] = closure
    }

    override suspend fun forSchedule(scheduleId: ScheduleId): List<OccurrenceClosure> =
        rows.values.filter { it.scheduleId == scheduleId }.sortedBy { it.occurrenceOn }

    override suspend fun find(scheduleId: ScheduleId, occurrenceOn: String): OccurrenceClosure? =
        rows.values.firstOrNull { it.scheduleId == scheduleId && it.occurrenceOn == occurrenceOn }

    override suspend fun all(): List<OccurrenceClosure> {
        witness?.observeAll()
        return rows.values.toList()
    }

    internal fun cascadeFromSchedules() { rows.clear() }
}

/**
 * Snapshots every store before running [block] and restores them all if it throws,
 * so rollback is observable in tests without a real database.
 */
class FakeUnitOfWork(private vararg val stores: Rollbackable) : UnitOfWork {
    private val witness = TransactionWitness()

    init {
        stores.filterIsInstance<Witnessed>().forEach { it.witness = witness }
    }

    var commits = 0
        private set
    var rollbacks = 0
        private set

    /** How many read transactions have been opened. */
    var reads = 0
        private set

    /** Table reads the covered stores served outside any transaction. */
    val readsOutsideSnapshot: Int get() = witness.readsOutsideSnapshot

    override suspend fun <T> write(block: suspend () -> T): T {
        val restores = stores.map { it.snapshot() }
        witness.inWrite = true
        return try {
            val result = block()
            commits += 1
            result
        } catch (t: Throwable) {
            restores.forEach { it() }
            rollbacks += 1
            throw t
        } finally {
            witness.inWrite = false
        }
    }

    override suspend fun <T> read(block: suspend () -> T): T {
        reads += 1
        witness.inRead = true
        return try {
            block()
        } finally {
            witness.inRead = false
        }
    }
}

/**
 * Open so a test can subclass it to rig a check on upsert order, the way
 * [InMemoryDefinitionRepository] is. The unique index is modelled here because the planner's
 * second-identity rule is only interesting if a duplicate pair would really be refused.
 */
open class InMemoryReferenceRepository : ReferenceRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, AssetReference>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(reference: AssetReference) {
        // The unique index, as a fake: one row per `(asset_id, uri)`.
        val holder = rows.values.firstOrNull {
            it.assetId == reference.assetId && it.uri == reference.uri
        }
        if (holder != null && holder.id != reference.id) {
            throw RiggedFailure("asset_reference already holds ${reference.uri} on ${reference.assetId.value}")
        }
        rows[reference.id.value] = reference
        version.value += 1
    }

    override suspend fun get(id: ReferenceId): AssetReference? = rows[id.value]

    override suspend fun forAsset(assetId: AssetId): List<AssetReference> = rows.values
        .filter { it.assetId == assetId }
        .sortedWith(compareBy({ it.displayName.lowercase() }, { it.id.value }))

    override suspend fun findByUri(assetId: AssetId, uri: String): AssetReference? =
        rows.values.firstOrNull { it.assetId == assetId && it.uri == uri }

    override suspend fun all(): List<AssetReference> {
        witness?.observeAll()
        return rows.values.toList()
    }

    override suspend fun delete(id: ReferenceId) { rows.remove(id.value); version.value += 1 }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeForAsset(assetId: AssetId): Flow<List<AssetReference>> = version.map {
        rows.values
            .filter { it.assetId == assetId }
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.id.value }))
    }
}
