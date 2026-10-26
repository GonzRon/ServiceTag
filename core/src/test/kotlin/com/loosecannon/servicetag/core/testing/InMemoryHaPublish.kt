package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.hapublish.DecommissionRepository
import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.DeliveryPlan
import com.loosecannon.servicetag.core.hapublish.DeliveryRecord
import com.loosecannon.servicetag.core.hapublish.DeliverySteps
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger
import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishBindingRepository
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishDeliveryRepository
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.ReadStep
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.Snap
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * #113/#114 (C24) — the outbound bindings in memory, as the schema keeps them: one per asset and kind, one **enabled**
 * claim per `(connection, entity)` (a refused write throws, as the unique indexes do), [update] the revision
 * compare-and-set that also clears the delivery's error and its pair (A04), [delete] taking the delivery (CASCADE).
 * [cascadeFromConnection] is the connection's CASCADE, which [InMemoryHaConnectionRepository.cascadesTo] calls.
 */
class InMemoryPublishBindingRepository : PublishBindingRepository, Rollbackable {
    val rows = LinkedHashMap<String, PublishBinding>()
    private val version = MutableStateFlow(0)

    /** The delivery table the CASCADE and the A04 clearing reach; set by [InMemoryHaPublish]. */
    var deliveries: InMemoryPublishDeliveryRepository? = null

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun get(id: String): PublishBinding? = rows[id]

    override suspend fun forAsset(assetId: AssetId): List<PublishBinding> =
        rows.values.filter { it.assetId == assetId }.sortedBy { it.kind.name }

    override suspend fun byTarget(connectionId: String, entityId: String): List<PublishBinding> =
        rows.values.filter { it.connectionId == connectionId && it.entityId == entityId }.sortedBy { it.id }

    override suspend fun all(): List<PublishBinding> = rows.values.sortedBy { it.id }

    override fun observeAll(): Flow<List<PublishBinding>> = version.map { rows.values.sortedBy { it.id } }

    override suspend fun insert(binding: PublishBinding) {
        if (binding.id in rows) throw RiggedFailure("ha_publish_binding already holds ${binding.id}")
        refuseUniqueness(binding)
        rows[binding.id] = binding
        version.value += 1
    }

    override suspend fun update(binding: PublishBinding): Boolean {
        val stored = rows[binding.id]
        if (stored == null || stored.revision != binding.revision - 1) return false
        refuseUniqueness(binding)
        rows[binding.id] = binding
        deliveries?.clearError(binding.id)
        version.value += 1
        return true
    }

    override suspend fun clearValidation(id: String, atRevision: Long): Boolean {
        val stored = rows[id]?.takeIf { it.revision == atRevision } ?: return false
        rows[id] = stored.copy(validatedRevision = null)
        version.value += 1
        return true
    }

    override suspend fun delete(id: String) {
        if (rows.remove(id) != null) version.value += 1
        deliveries?.removeRow(id)
    }

    /** The schema's CASCADE from `ha_connection`: every binding on it goes, with its delivery. */
    fun cascadeFromConnection(connectionId: String) {
        rows.values.filter { it.connectionId == connectionId }.map { it.id }.forEach { id ->
            rows.remove(id)
            deliveries?.removeRow(id)
        }
        version.value += 1
    }

    /** `UNIQUE(asset_id, kind)` and `UNIQUE(connection_id, claim_entity_id)` over the enabled bindings. */
    private fun refuseUniqueness(binding: PublishBinding) {
        val others = rows.values.filter { it.id != binding.id }
        if (others.any { it.assetId == binding.assetId && it.kind == binding.kind }) {
            throw RiggedFailure("ha_publish_binding: a second ${binding.kind} binding for ${binding.assetId.value}")
        }
        if (binding.enabled &&
            others.any { it.enabled && it.connectionId == binding.connectionId && it.entityId == binding.entityId }
        ) {
            throw RiggedFailure("ha_publish_binding: a second enabled claim of ${binding.entityId}")
        }
    }
}

/**
 * #113/#114 (C24) — the delivery records in memory. [record] is the store's `DeliverySteps.commit`: a compare-and-set
 * on the binding's revision and the record's desired revision, writing bookkeeping only — never the desired value,
 * the boundary cache or the request stamp — with the error's pair the compare-and-set pair (A04). [writeDesired] moves
 * the desired revision, clears the error and starts the attempt count again.
 */
class InMemoryPublishDeliveryRepository(
    private val bindings: InMemoryPublishBindingRepository,
) : PublishDeliveryRepository, Rollbackable {
    val rows = LinkedHashMap<String, PublishDelivery>()
    private val version = MutableStateFlow(0)

    /** How many [record] calls answered false. */
    var droppedRecords = 0
        private set

    init {
        bindings.deliveries = this
    }

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun get(bindingId: String): PublishDelivery? = rows[bindingId]

    /** Display only (I28): moved by this double's own writes, and distinct as Room's flow is (B6b review N3). */
    override fun observe(bindingId: String): Flow<PublishDelivery?> =
        version.map { rows[bindingId] }.distinctUntilChanged()

    /** A delivery row gone with its binding (the CASCADE), moving [observe] to `null` as Room's flow does. */
    fun removeRow(id: String) {
        if (rows.remove(id) != null) version.value += 1
    }

    override suspend fun upsert(delivery: PublishDelivery) {
        if (delivery.bindingId !in bindings.rows) throw RiggedFailure("ha_publish_delivery: no binding ${delivery.bindingId}")
        rows[delivery.bindingId] = delivery
        version.value += 1
    }

    override suspend fun writeDesired(
        bindingId: String,
        bindingRevision: Long,
        expectedDesiredRevision: Long,
        desiredValue: String?,
        desiredAt: Long,
    ): Boolean {
        val stored = rows[bindingId] ?: return false
        if (bindings.rows[bindingId]?.revision != bindingRevision || stored.desiredRevision != expectedDesiredRevision) {
            return false
        }
        rows[bindingId] = stored.copy(
            desiredValue = desiredValue,
            desiredRevision = expectedDesiredRevision + 1,
            desiredAt = desiredAt,
            attemptsAtRevision = 0,
        ).withoutError()
        version.value += 1
        return true
    }

    override suspend fun record(bindingRevision: Long, delivery: PublishDelivery): Boolean {
        val stored = rows[delivery.bindingId]
        if (stored == null ||
            bindings.rows[delivery.bindingId]?.revision != bindingRevision ||
            stored.desiredRevision != delivery.desiredRevision
        ) {
            droppedRecords += 1
            return false
        }
        rows[delivery.bindingId] = delivery.copy(
            desiredValue = stored.desiredValue,
            desiredAt = stored.desiredAt,
            boundaryValue = stored.boundaryValue,
            boundaryReadAt = stored.boundaryReadAt,
            boundaryBindingRevision = stored.boundaryBindingRevision,
            lastRequestId = stored.lastRequestId,
            lastRequestAt = stored.lastRequestAt,
            errorBindingRevision = delivery.errorKind?.let { bindingRevision },
            errorDesiredRevision = delivery.errorKind?.let { delivery.desiredRevision },
        )
        version.value += 1
        return true
    }

    override suspend fun stampRequest(bindingIds: List<String>, requestId: String, at: Long) {
        bindingIds.forEach { id -> rows[id]?.let { rows[id] = it.copy(lastRequestId = requestId, lastRequestAt = at) } }
        version.value += 1
    }

    /** A04's clearing for a moved generation, which the binding double's update calls. */
    fun clearError(bindingId: String) {
        rows[bindingId]?.let { rows[bindingId] = it.withoutError() }
        version.value += 1
    }

    private fun PublishDelivery.withoutError() = copy(
        errorKind = null,
        errorDetail = null,
        errorAt = null,
        errorBindingRevision = null,
        errorDesiredRevision = null,
    )
}

/**
 * #114 (C24, C34) — the tombstones in memory: one per `(connection, entity)`, a second merged into the first (its id and
 * `requestedAt` kept, its revision moved, its error dropped); [update] a compare-and-set on the revision it never moves.
 */
class InMemoryDecommissionRepository : DecommissionRepository, Rollbackable {
    val rows = LinkedHashMap<String, DecommissionTombstone>()
    private val version = MutableStateFlow(0)

    /** When set, the next [upsertMerging] throws, as a failed statement does: the caller's write rolls back. */
    var failNextWrite = false

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun get(id: String): DecommissionTombstone? = rows[id]

    override suspend fun byTarget(connectionId: String, entityId: String): DecommissionTombstone? =
        rows.values.firstOrNull { it.connectionId == connectionId && it.entityId == entityId }

    override suspend fun all(): List<DecommissionTombstone> = rows.values.sortedBy { it.id }

    override fun observeAll(): Flow<List<DecommissionTombstone>> = version.map { rows.values.sortedBy { it.id } }

    override suspend fun upsertMerging(tombstone: DecommissionTombstone): String {
        if (failNextWrite) {
            failNextWrite = false
            throw RiggedFailure("rigged ha_publish_decommission write failure")
        }
        val held = byTarget(tombstone.connectionId, tombstone.entityId)
        val written = held?.copy(
            state = tombstone.state,
            assetId = tombstone.assetId,
            installationId = tombstone.installationId,
            errorKind = null,
            errorAt = null,
            revision = held.revision + 1,
        ) ?: tombstone
        rows[written.id] = written
        version.value += 1
        return written.id
    }

    override suspend fun update(tombstone: DecommissionTombstone): Boolean {
        val stored = rows[tombstone.id]?.takeIf { it.revision == tombstone.revision } ?: return false
        rows[tombstone.id] = stored.copy(
            state = tombstone.state,
            errorKind = tombstone.errorKind,
            errorAt = tombstone.errorAt,
        )
        version.value += 1
        return true
    }

    override suspend fun delete(id: String) {
        if (rows.remove(id) != null) version.value += 1
    }
}

/** The three publishing tables of one installation, wired to each other as the schema wires them. */
class InMemoryHaPublish {
    val bindings = InMemoryPublishBindingRepository()
    val deliveries = InMemoryPublishDeliveryRepository(bindings)
    val tombstones = InMemoryDecommissionRepository()

    /** The stores a unit of work snapshots for the publishing tables. */
    val stores: Array<Rollbackable> get() = arrayOf(bindings, deliveries, tombstones)
}

/**
 * A unit of work that also rolls [extra] back when [inner]'s write throws: how a test adds the publishing tables to a
 * harness whose own unit of work was built without them.
 */
class WithStores(private val inner: UnitOfWork, private vararg val extra: Rollbackable) : UnitOfWork {
    override suspend fun <T> write(block: suspend () -> T): T {
        val restores = extra.map { it.snapshot() }
        return try {
            inner.write(block)
        } catch (t: Throwable) {
            restores.forEach { it() }
            throw t
        }
    }

    override suspend fun <T> read(block: suspend () -> T): T = inner.read(block)
}

/**
 * #113/#114 — a Home Assistant of helpers in memory, beside [ScriptedHaStateReader]: [helpers] by entity id, a [read]
 * answering the helper as it stands (or [readFailure] when set), and a [post] that records the call and, accepted, sets
 * the helper's state. [posts] is what a test counts: the only writes are the two allowlisted actions.
 */
class ScriptedPublishTransport {
    /** One recorded service call. */
    data class Post(val action: HaServiceAction, val entityId: String, val value: String)

    val helpers = LinkedHashMap<String, RemoteEntity>()
    val reads = mutableListOf<String>()
    val posts = mutableListOf<Post>()
    var readFailure: RemoteRead.Failed? = null

    /** An `input_select` with exactly the three status options. */
    fun statusHelper(entityId: String, state: String = "unknown") {
        helpers[entityId] = RemoteEntity(entityId, state, listOf("UP", "DEGRADED", "DOWN"), null, null)
    }

    /** A date-only `input_datetime`. */
    fun dateHelper(entityId: String, state: String = "1970-01-01") {
        helpers[entityId] = RemoteEntity(entityId, state, null, hasDate = true, hasTime = false)
    }

    fun read(entityId: String): RemoteRead {
        reads += entityId
        readFailure?.let { return it }
        val helper = helpers[entityId] ?: return RemoteRead.Failed(PublishErrorKind.ENTITY_NOT_FOUND, null)
        return RemoteRead.Answered(helper)
    }

    fun post(action: HaServiceAction, entityId: String, value: String) {
        posts += Post(action, entityId, value)
        helpers[entityId]?.let { helpers[entityId] = it.copy(state = value) }
    }

    fun stateOf(entityId: String): String? = helpers[entityId]?.state
}

/** A tombstone as a write leaves it, for fixtures. */
fun tombstoneOf(
    id: String,
    entityId: String,
    connectionId: String = "conn-1",
    assetId: String? = "heater",
    installationId: String = "installation-a",
    state: DecommissionState = DecommissionState.PENDING,
    revision: Long = 1,
): DecommissionTombstone = DecommissionTombstone(
    id = id,
    connectionId = connectionId,
    entityId = entityId,
    assetId = assetId?.let(::AssetId),
    installationId = installationId,
    state = state,
    requestedAt = 1_000L,
    errorKind = null,
    errorAt = null,
    revision = revision,
)

/**
 * #113/#114 (C17, C30) — one sender pass over the ports, composed of the pure steps as the runner composes them: the
 * gate and the plan from one snapshot, a read, a write-ahead before the POST, a read-back, each record a
 * compare-and-set; and a tombstone drain of `1970-01-01`, deleting the row by its id once read back. The runner is
 * B4a's; this is how the commands' tests see what a pass after them sends. No transaction is held across [transport].
 */
class DeliveryPass(
    private val bindings: PublishBindingRepository,
    private val deliveries: PublishDeliveryRepository,
    private val tombstones: DecommissionRepository,
    private val connections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val transport: ScriptedPublishTransport,
    private val installationId: () -> String,
    private val now: () -> Long,
) {
    /** One pass over [bindingId]; answers the plan the gate chose. */
    suspend fun run(
        bindingId: String,
        trigger: DeliveryTrigger = DeliveryTrigger.CHANGE,
    ): DeliveryPlan {
        val binding = checkNotNull(bindings.get(bindingId)) { "no binding $bindingId" }
        val delivery = checkNotNull(deliveries.get(bindingId)) { "no delivery for $bindingId" }
        val connection = connections.get()
        val snap = Snap(
            binding = binding,
            delivery = delivery,
            connectionPresent = connection?.id == binding.connectionId,
            hasToken = connection != null && secrets.has(connection.id),
            installationId = installationId(),
            asset = assets.get(binding.assetId),
            held = transfers.heldIds(),
        )
        val plan = DeliverySteps.plan(snap, trigger)
        if (plan !is DeliveryPlan.ReadFirst) return plan
        var current = delivery
        if (plan.reconcile) {
            val ahead = DeliverySteps.reconcileWriteAhead(binding, current)
            if (!commit(binding.id, ahead)) return plan
            current = ahead.delivery
        }
        val read = transport.read(binding.entityId)
        when (val step = DeliverySteps.afterRead(binding, current, plan.value, read, now())) {
            is ReadStep.Record -> commit(binding.id, step.record)
            is ReadStep.Post -> {
                if (!commit(binding.id, step.writeAhead)) return plan
                transport.post(step.action, binding.entityId, step.value)
                val back = transport.read(binding.entityId)
                commit(
                    binding.id,
                    DeliverySteps.afterPostAccepted(
                        binding, step.writeAhead.delivery, step.value, back, now(),
                    ),
                )
            }
        }
        return plan
    }

    /**
     * C34 — every PENDING tombstone this installation activated is sent `1970-01-01` and read back, then deleted by its
     * id; another installation's is drawn, never drained (F-25). Answers the ids it deleted.
     */
    suspend fun drainTombstones(): List<String> {
        val connection = connections.get() ?: return emptyList()
        if (!secrets.has(connection.id)) return emptyList()
        val drained = mutableListOf<String>()
        for (t in tombstones.all()) {
            if (t.state != DecommissionState.PENDING || t.connectionId != connection.id) continue
            if (t.installationId != installationId()) continue
            transport.post(HaServiceAction.SET_DATE, t.entityId, INVALID_SERVICE_DATE)
            if (transport.stateOf(t.entityId) == INVALID_SERVICE_DATE) {
                tombstones.delete(t.id)
                drained += t.id
            }
        }
        return drained
    }

    private suspend fun commit(bindingId: String, record: DeliveryRecord): Boolean {
        val wrote = deliveries.record(record.bindingRevision, record.delivery)
        if (wrote && record.clearValidation) bindings.clearValidation(bindingId, record.bindingRevision)
        return wrote
    }
}
