package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.journal.EventChronology
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleState
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.ports.AssetSuccessionRepository
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.CategoryRepository
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.ScheduleStateRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.heldIds as heldIdsOf
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

    override suspend fun delete(id: AssetId) {
        rows.remove(id.value)
        version.value += 1
        cascades.forEach { it(id) }
    }

    override suspend fun deleteAll() {
        val gone = rows.keys.map(::AssetId)
        rows.clear()
        version.value += 1
        gone.forEach { id -> cascades.forEach { it(id) } }
    }

    /**
     * #79 (C17): the schema's CASCADE from `asset`, for the stores that ask for it — the service cases
     * ([InMemoryServiceCaseRepository.cascadeFromAsset]) and #72's loans
     * ([InMemoryAssetLoanRepository.cascadeFromAsset]), and #47's installed components
     * ([InMemoryInstalledComponentRepository.cascadeFromAsset]) among the rest [BackupInstall] registers. #69 (C11)
     * carries one second level: the component double hands the ids it removed to
     * [InMemoryAttachmentRepository.cascadeFromInstalledComponents] and its twin
     * [InMemoryReferenceRepository.cascadeFromInstalledComponents], so a component's files and links go with the
     * asset's components. The asymmetry is deliberate (N-16): an asset's own files and links and an entry's files are
     * **not** cascaded from here, because the shipped core tests were written against a double that leaves them, so
     * that half stays the Room tests' to prove. Every other table's cascade is still the Room tests' to prove; a
     * double that registers nothing deletes the asset row alone, as before.
     */
    private val cascades = mutableListOf<(AssetId) -> Unit>()

    fun cascadesTo(cascade: (AssetId) -> Unit) { cascades += cascade }

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

    override fun observeAll(): Flow<List<TagBinding>> = version.map { rows.values.toList() }
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

    /**
     * #69 (C11): the schema's CASCADE from `installed_component` — the files the components [ids] own go, and only
     * those. [BackupInstall] registers it on the component double; why nothing cascades here from the asset double is
     * [InMemoryAssetRepository.cascadesTo]'s.
     */
    fun cascadeFromInstalledComponents(ids: Set<InstalledComponentId>) {
        val removed = rows.values.removeAll { (it.owner as? AttachmentOwner.OfInstalledComponent)?.componentId in ids }
        if (removed) version.value += 1
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

    /** #77 (C15): the group row with its members; a test that needs the schema's CASCADE registers it. */
    override suspend fun delete(id: GroupId) {
        rows.remove(id.value)
        version.value += 1
        cascades.forEach { it(id) }
    }

    private val cascades = mutableListOf<(GroupId) -> Unit>()

    /** #77 (C15): the schema's CASCADE from `maintenance_group`, for a test that reproduces it. */
    fun cascadesTo(cascade: (GroupId) -> Unit) { cascades += cascade }

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

    /**
     * The Room DAO's order, mirrored: `ORDER BY actionable_due_on, schedule_id`, a null date first
     * as SQLite sorts it (1.4, B02's M5). `DueReadModelPolicyTest` holds the two to one order.
     */
    override fun observeAll(): Flow<List<ScheduleState>> = version.map {
        rows.values.sortedWith(compareBy({ it.actionableDueOn ?: "" }, { it.scheduleId.value }))
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
        // The three unique indices, as a fake (#69, I3): one row per `(owner, uri)`, the owner value-typed.
        val holder = rows.values.firstOrNull {
            it.owner == reference.owner && it.uri == reference.uri
        }
        check(holder == null || holder.id == reference.id) {
            "asset_reference already holds ${reference.uri} on ${reference.owner}"
        }
        rows[reference.id.value] = reference
        version.value += 1
    }

    override suspend fun get(id: ReferenceId): AssetReference? = rows[id.value]

    override suspend fun forOwner(owner: ReferenceOwner): List<AssetReference> = rows.values
        .filter { it.owner == owner }
        .sortedWith(compareBy({ it.displayName.lowercase() }, { it.id.value }))

    override suspend fun findByUri(owner: ReferenceOwner, uri: String): AssetReference? =
        rows.values.firstOrNull { it.owner == owner && it.uri == uri }

    override suspend fun all(): List<AssetReference> {
        witness?.observeAll()
        return rows.values.toList()
    }

    override suspend fun delete(id: ReferenceId) { rows.remove(id.value); version.value += 1 }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeForOwner(owner: ReferenceOwner): Flow<List<AssetReference>> = version.map {
        rows.values
            .filter { it.owner == owner }
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.id.value }))
    }

    /**
     * #69 (C11, C12): the schema's CASCADE from `installed_component` — the links the components [ids] own go, and
     * only those; the twin of [InMemoryAttachmentRepository.cascadeFromInstalledComponents]. [BackupInstall] registers
     * it on the component double; why nothing cascades here from the asset double is
     * [InMemoryAssetRepository.cascadesTo]'s.
     */
    fun cascadeFromInstalledComponents(ids: Set<InstalledComponentId>) {
        val removed = rows.values.removeAll { (it.owner as? ReferenceOwner.OfInstalledComponent)?.componentId in ids }
        if (removed) version.value += 1
    }
}

/**
 * Insert and query only, exactly as the port is: an activation row is an immutable fact. Every list
 * orders by `(occurredOn, createdAt, id)`, the order the Room adapter's queries use.
 */
class InMemorySeasonActivationRepository : SeasonActivationRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, SeasonActivation>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun insert(row: SeasonActivation) {
        // The primary key, as a fake: a second insert of one id is refused, never an overwrite.
        if (row.id in rows) throw RiggedFailure("asset_season_activation already holds ${row.id}")
        rows[row.id] = row
        version.value += 1
    }

    override suspend fun forAsset(assetId: AssetId): List<SeasonActivation> =
        rows.values.filter { it.assetId == assetId }.sortedWith(ORDER)

    override suspend fun all(): List<SeasonActivation> {
        witness?.observeAll()
        return rows.values.sortedWith(ORDER)
    }

    override fun observeForAsset(assetId: AssetId): Flow<List<SeasonActivation>> =
        version.map { rows.values.filter { it.assetId == assetId }.sortedWith(ORDER) }

    private companion object {
        val ORDER = compareBy<SeasonActivation>({ it.occurredOn }, { it.createdAt }, { it.id })
    }
}

/**
 * Insert and query only, exactly as the port is: a condition row is an immutable fact. Every list
 * orders by `(occurredOn, occurredTime nulls first, createdAt, id)` — `compareBy` puts a null
 * first, as SQLite's ascending order does.
 */
class InMemoryConditionRepository : ConditionRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, AssetCondition>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun insert(row: AssetCondition) {
        if (row.id in rows) throw RiggedFailure("asset_condition already holds ${row.id}")
        rows[row.id] = row
        version.value += 1
    }

    override suspend fun forAsset(assetId: AssetId): List<AssetCondition> =
        rows.values.filter { it.assetId == assetId }.sortedWith(ORDER)

    override suspend fun all(): List<AssetCondition> {
        witness?.observeAll()
        return rows.values.sortedWith(ORDER)
    }

    override fun observeForAsset(assetId: AssetId): Flow<List<AssetCondition>> =
        version.map { rows.values.filter { it.assetId == assetId }.sortedWith(ORDER) }

    override fun observeAll(): Flow<List<AssetCondition>> = version.map { rows.values.sortedWith(ORDER) }

    private companion object {
        val ORDER = compareBy<AssetCondition>({ it.occurredOn }, { it.occurredTime }, { it.createdAt }, { it.id })
    }
}

/** Configuration: upsert and query, and no delete, exactly as the port is. Lists order by `(sortOrder, id)`. */
class InMemoryHealthSubjectRepository : HealthSubjectRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, HealthSubject>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(subject: HealthSubject) {
        rows[subject.id.value] = subject
        version.value += 1
    }

    override suspend fun get(id: HealthSubjectId): HealthSubject? = rows[id.value]

    override suspend fun forAsset(assetId: AssetId): List<HealthSubject> =
        rows.values.filter { it.assetId == assetId }.sortedWith(ORDER)

    override suspend fun forSchedule(id: ScheduleId): List<HealthSubject> =
        rows.values.filter { it.scheduleId == id }.sortedWith(ORDER)

    override suspend fun all(): List<HealthSubject> {
        witness?.observeAll()
        return rows.values.sortedWith(ORDER)
    }

    override fun observeForAsset(assetId: AssetId): Flow<List<HealthSubject>> =
        version.map { rows.values.filter { it.assetId == assetId }.sortedWith(ORDER) }

    override fun observeAll(): Flow<List<HealthSubject>> = version.map { rows.values.sortedWith(ORDER) }

    private companion object {
        val ORDER = compareBy<HealthSubject>({ it.sortOrder }, { it.id.value })
    }
}

/**
 * #74's catalog rows, keyed by `key` as the table's primary key is. Lists order by key, as the Room
 * adapter's do. [failOnUpsert] rigs the Nth upsert to throw, so an all-or-nothing claim can be made.
 */
class InMemoryCategoryRepository : CategoryRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, AssetCategory>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("category")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(row: AssetCategory) {
        rig.check()
        rows[row.key] = row
        version.value += 1
    }

    override suspend fun get(key: String): AssetCategory? = rows[key]

    override suspend fun all(): List<AssetCategory> {
        witness?.observeAll()
        return rows.values.sortedBy { it.key }
    }

    override suspend fun delete(key: String) { rows.remove(key); version.value += 1 }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeAll(): Flow<List<AssetCategory>> = version.map { rows.values.sortedBy { it.key } }
}

/**
 * #79's case headers: upsert and query, and no delete, exactly as the port is. [cascadeFromAsset] is not
 * part of the port: it is how [InMemoryAssetRepository] reproduces the schema's CASCADE from `asset`
 * (which takes each case, and — through [entries] — each case's timeline). [failOnUpsert] rigs the Nth
 * upsert to throw, so a two-row write can be shown to be all or nothing.
 */
class InMemoryServiceCaseRepository(
    private val entries: InMemoryServiceCaseEntryRepository? = null,
) : ServiceCaseRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, ServiceCase>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("service case")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(case: ServiceCase) {
        rig.check()
        rows[case.id.value] = case
        version.value += 1
    }

    override suspend fun get(id: ServiceCaseId): ServiceCase? = rows[id.value]

    override suspend fun forAsset(assetId: AssetId): List<ServiceCase> =
        rows.values.filter { it.assetId == assetId }.sortedWith(BY_ASSET)

    override suspend fun all(): List<ServiceCase> {
        witness?.observeAll()
        return rows.values.sortedBy { it.id.value }
    }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeForAsset(assetId: AssetId): Flow<List<ServiceCase>> =
        version.map { rows.values.filter { it.assetId == assetId }.sortedWith(BY_ASSET) }

    /** The schema's CASCADE from `asset`: this asset's cases go, and their entries with them. */
    fun cascadeFromAsset(assetId: AssetId) {
        val doomed = rows.values.filter { it.assetId == assetId }.map { it.id }
        doomed.forEach { rows.remove(it.value) }
        entries?.cascadeFromCases(doomed.toSet())
        version.value += 1
    }

    private companion object {
        val BY_ASSET = compareByDescending<ServiceCase> { it.openedOn }.thenBy { it.id.value }
    }
}

/**
 * #79's case timeline: insert and query only, exactly as the port is. An insert of an id already held
 * is refused, never an overwrite — the primary key, as a fake. Every list is in the timeline order,
 * `(occurredOn, occurredTime nulls first, createdAt, id)`.
 */
class InMemoryServiceCaseEntryRepository : ServiceCaseEntryRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, ServiceCaseEntry>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun insert(entry: ServiceCaseEntry) {
        if (entry.id.value in rows) throw RiggedFailure("service_case_entry already holds ${entry.id.value}")
        rows[entry.id.value] = entry
        version.value += 1
    }

    override suspend fun forCase(caseId: ServiceCaseId): List<ServiceCaseEntry> =
        rows.values.filter { it.caseId == caseId }.sortedWith(ORDER)

    override suspend fun all(): List<ServiceCaseEntry> {
        witness?.observeAll()
        return rows.values.sortedWith(ORDER)
    }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeForCase(caseId: ServiceCaseId): Flow<List<ServiceCaseEntry>> =
        version.map { rows.values.filter { it.caseId == caseId }.sortedWith(ORDER) }

    internal fun cascadeFromCases(caseIds: Set<ServiceCaseId>) {
        rows.values.removeAll { it.caseId in caseIds }
        version.value += 1
    }

    private companion object {
        val ORDER = compareBy<ServiceCaseEntry>({ it.occurredOn }, { it.occurredTime }, { it.createdAt }, { it.id.value })
    }
}

/**
 * #72's loans: upsert and query, and no delete, exactly as the port is. The schema's unique index on
 * `(asset_id, open_marker)` is reproduced — an upsert that would leave an asset with two open loans is
 * refused, never written — so a writer that forgot its own check fails here as it would on Room.
 * [cascadeFromAsset] is not part of the port: it is how [InMemoryAssetRepository] reproduces the
 * schema's CASCADE from `asset`. [failOnUpsert] rigs the Nth upsert to throw. [openForInWrite] records,
 * per [openFor] call, whether a [FakeUnitOfWork] write transaction was open — so a writer's one-open-loan
 * read can be shown to share the write it guards (C2 ii).
 */
class InMemoryAssetLoanRepository : AssetLoanRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, AssetLoan>()
    override var witness: TransactionWitness? = null
    private val rig = UpsertRig("loan")
    private val version = MutableStateFlow(0)
    var failOnUpsert: Int?
        get() = rig.failOnUpsert
        set(value) { rig.failOnUpsert = value }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun upsert(loan: AssetLoan) {
        rig.check()
        if (loan.isOpen && rows.values.any { it.isOpen && it.assetId == loan.assetId && it.id != loan.id }) {
            throw RiggedFailure("asset_loan already holds an open loan for ${loan.assetId.value}")
        }
        rows[loan.id.value] = loan
        version.value += 1
    }

    override suspend fun get(id: AssetLoanId): AssetLoan? = rows[id.value]

    override suspend fun forAsset(assetId: AssetId): List<AssetLoan> =
        rows.values.filter { it.assetId == assetId }.sortedWith(BY_ASSET)

    /** One entry per [openFor] call: true when it ran inside a write transaction. */
    val openForInWrite = mutableListOf<Boolean>()

    override suspend fun openFor(assetId: AssetId): AssetLoan? {
        openForInWrite += witness?.inWrite == true
        return rows.values.firstOrNull { it.isOpen && it.assetId == assetId }
    }

    override suspend fun open(): List<AssetLoan> = rows.values.filter { it.isOpen }.sortedBy { it.id.value }

    override suspend fun all(): List<AssetLoan> {
        witness?.observeAll()
        return rows.values.sortedBy { it.id.value }
    }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeForAsset(assetId: AssetId): Flow<List<AssetLoan>> =
        version.map { rows.values.filter { it.assetId == assetId }.sortedWith(BY_ASSET) }

    override fun observeOpen(): Flow<List<AssetLoan>> =
        version.map { rows.values.filter { it.isOpen }.sortedBy { it.id.value } }

    /** The schema's CASCADE from `asset`: this asset's loans go, open and returned alike. */
    fun cascadeFromAsset(assetId: AssetId) {
        rows.values.removeAll { it.assetId == assetId }
        version.value += 1
    }

    private companion object {
        val BY_ASSET = compareByDescending<AssetLoan> { it.lentOn }.thenBy { it.id.value }
    }
}

/**
 * #77 (C6) — the transfer records: append and query only, an id already held aborting the append, and
 * **no cascade** from the asset double — a record outlives its asset (R77-4). [onAppend] lets a test see
 * what the rest of the install held when a record landed (the merge writes records last).
 */
class InMemoryTransferRecordRepository : TransferRecordRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, TransferRecord>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)
    var onAppend: ((TransferRecord) -> Unit)? = null

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun append(record: TransferRecord) {
        if (record.id in rows) throw RiggedFailure("asset_transfer already holds ${record.id}")
        onAppend?.invoke(record)
        rows[record.id] = record
        version.value += 1
    }

    override suspend fun all(): List<TransferRecord> {
        witness?.observeAll()
        return rows.values.sortedBy { it.id }
    }

    override suspend fun forAsset(assetId: AssetId): List<TransferRecord> =
        rows.values.filter { it.assetId == assetId }.sortedWith(compareBy({ it.at }, { it.id }))

    override suspend fun heldIds(): Set<AssetId> = heldIdsOf(rows.values.toList())

    override fun observeHeldIds(): Flow<Set<AssetId>> = version.map { heldIdsOf(rows.values.toList()) }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }
}

/**
 * #86 (C2) — the successions: append and query only. An append aborts on an id, a predecessor or a successor
 * another row already holds (the schema's primary key and its two unique indexes), and [cascadeFromAsset] is the
 * schema's CASCADE from `asset` at **either** end — registered through `assets.cascadesTo`, as #72's loans are.
 * [onAppend] lets a test see what the rest of the install held when a row landed.
 */
class InMemoryAssetSuccessionRepository : AssetSuccessionRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, AssetSuccession>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)
    var onAppend: ((AssetSuccession) -> Unit)? = null

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun append(row: AssetSuccession) {
        if (row.id in rows) throw RiggedFailure("asset_succession already holds ${row.id}")
        if (rows.values.any { it.predecessorAssetId == row.predecessorAssetId }) {
            throw RiggedFailure("asset_succession already names predecessor ${row.predecessorAssetId.value}")
        }
        if (rows.values.any { it.successorAssetId == row.successorAssetId }) {
            throw RiggedFailure("asset_succession already names successor ${row.successorAssetId.value}")
        }
        onAppend?.invoke(row)
        rows[row.id] = row
        version.value += 1
    }

    override suspend fun all(): List<AssetSuccession> {
        witness?.observeAll()
        return rows.values.sortedBy { it.id }
    }

    override suspend fun replacedBy(predecessor: AssetId): AssetSuccession? =
        rows.values.firstOrNull { it.predecessorAssetId == predecessor }

    override suspend fun replaces(successor: AssetId): AssetSuccession? =
        rows.values.firstOrNull { it.successorAssetId == successor }

    override suspend fun deleteAll() { rows.clear(); version.value += 1 }

    override fun observeForAsset(assetId: AssetId): Flow<List<AssetSuccession>> = version.map {
        rows.values.filter { it.predecessorAssetId == assetId || it.successorAssetId == assetId }.sortedBy { it.id }
    }

    /** The schema's CASCADE from `asset`, at either end: a row naming the deleted asset goes. */
    fun cascadeFromAsset(assetId: AssetId) {
        rows.values.removeAll { it.predecessorAssetId == assetId || it.successorAssetId == assetId }
        version.value += 1
    }
}
