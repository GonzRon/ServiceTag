package com.loosecannon.servicetag.core.testing

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaStateReader
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * #16 (C7) — the bindings in memory. [update] is the port's compare-and-set, and [insert] refuses a second binding
 * for one asset, as the primary key does. [cascadeFromAsset] and [cascadeFromConnection] are not part of the port:
 * they are how [BackupInstall] reproduces the schema's two CASCADEs (C9) — the asset double's delete and wipe call
 * the first, [InMemoryHaConnectionRepository.delete] the second.
 */
class InMemorySeasonSyncRepository : SeasonSyncRepository, Rollbackable, Witnessed {
    val rows = LinkedHashMap<String, SeasonSyncBinding>()
    override var witness: TransactionWitness? = null
    private val version = MutableStateFlow(0)

    /** How many [update] calls answered false. */
    var refusedUpdates = 0
        private set

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy); version.value += 1 }
    }

    override suspend fun get(assetId: AssetId): SeasonSyncBinding? = rows[assetId.value]

    override suspend fun all(): List<SeasonSyncBinding> {
        witness?.observeAll()
        return rows.values.sortedBy { it.assetId.value }
    }

    override fun observeFor(assetId: AssetId): Flow<SeasonSyncBinding?> = version.map { rows[assetId.value] }

    override suspend fun insert(binding: SeasonSyncBinding) {
        if (binding.assetId.value in rows) {
            throw RiggedFailure("season_sync_binding already holds a binding for ${binding.assetId.value}")
        }
        rows[binding.assetId.value] = binding
        version.value += 1
    }

    override suspend fun update(binding: SeasonSyncBinding): Boolean {
        val stored = rows[binding.assetId.value]
        if (stored == null || stored.revision != binding.revision - 1) {
            refusedUpdates += 1
            return false
        }
        rows[binding.assetId.value] = binding
        version.value += 1
        return true
    }

    override suspend fun anyEnabled(): Boolean = rows.values.any { it.enabled }

    /** The schema's CASCADE from `asset`: this asset's binding goes. */
    fun cascadeFromAsset(assetId: AssetId) {
        if (rows.remove(assetId.value) != null) version.value += 1
    }

    /** The schema's CASCADE from `ha_connection`: every binding on that connection goes. */
    fun cascadeFromConnection(connectionId: String) {
        if (rows.values.removeAll { it.connectionId == connectionId }) version.value += 1
    }
}

/**
 * #16 (C7) — the one connection in memory. [get] fails loudly if a writer ever left two rows, which R16-10 rules
 * out. [delete] hands the id to [bindings], the schema's CASCADE from `ha_connection`.
 */
class InMemoryHaConnectionRepository(
    private val bindings: InMemorySeasonSyncRepository,
) : HaConnectionRepository, Rollbackable {
    val rows = LinkedHashMap<String, HaConnection>()

    override fun snapshot(): () -> Unit {
        val copy = LinkedHashMap(rows)
        return { rows.clear(); rows.putAll(copy) }
    }

    override suspend fun get(): HaConnection? {
        check(rows.size <= 1) { "ha_connection holds ${rows.size} rows; one installation has one connection" }
        return rows.values.firstOrNull()
    }

    override suspend fun upsert(connection: HaConnection) {
        rows[connection.id] = connection
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
        bindings.cascadeFromConnection(id)
    }
}

/**
 * #16 (C7) — the token store in memory. Not [Rollbackable]: the platform store is outside every transaction, so
 * a writer puts a token only after its row commits (C17). Nothing here prints a stored value.
 */
class InMemorySecretStore : SecretStore {
    private val values = LinkedHashMap<String, Secret>()

    override suspend fun put(key: String, secret: Secret) {
        values[key] = secret
    }

    override suspend fun get(key: String): Secret? = values[key]

    override suspend fun has(key: String): Boolean = key in values

    override suspend fun delete(key: String) {
        values.remove(key)
    }

    override suspend fun keys(): Set<String> = values.keys.toSet()

    override fun toString(): String = "InMemorySecretStore(keys=${values.keys})"
}

/**
 * #16 (C7) — a [HaStateReader] that answers from a script and records every call. [answer] queues an outcome;
 * [answerWhen] queues one that waits for its gate first, so a test can hold a read in flight while it changes the
 * binding. A read with nothing scripted fails the test.
 */
class ScriptedHaStateReader : HaStateReader {
    /** One recorded read. [token] prints as `Secret(redacted)`. */
    data class Call(val baseUrl: String, val entityId: String, val token: Secret)

    private class Step(val outcome: HaReadOutcome, val gate: CompletableDeferred<Unit>?)

    private val script = ArrayDeque<Step>()
    val calls = mutableListOf<Call>()

    fun answer(outcome: HaReadOutcome) {
        script.addLast(Step(outcome, null))
    }

    fun answerWhen(gate: CompletableDeferred<Unit>, outcome: HaReadOutcome) {
        script.addLast(Step(outcome, gate))
    }

    override suspend fun read(baseUrl: String, entityId: String, token: Secret): HaReadOutcome {
        calls += Call(baseUrl, entityId, token)
        val step = script.removeFirstOrNull() ?: throw AssertionError("no scripted answer for read #${calls.size}")
        step.gate?.await()
        return step.outcome
    }
}

/** A fictional connection: the canonical http fixture (C-7). */
fun haConnectionOf(
    id: String = "conn-1",
    baseUrl: String = "http://192.168.0.10:8123",
    at: Long = 1_759_000_000_000L,
): HaConnection = HaConnection(id = id, baseUrl = baseUrl, createdAt = at, updatedAt = at)

/** A fictional binding as a link leaves it: FOLLOW, enabled, revision 1, no status yet (C16). */
fun seasonSyncBindingOf(
    assetId: String,
    connectionId: String = "conn-1",
    entityId: String = "input_boolean.example_heater_in_season",
    mode: SyncMode = SyncMode.FOLLOW,
    enabled: Boolean = true,
    revision: Long = 1,
    observedState: HaSwitchState? = null,
    at: Long = 1_759_000_000_000L,
): SeasonSyncBinding = SeasonSyncBinding(
    assetId = AssetId(assetId),
    connectionId = connectionId,
    entityId = entityId,
    mode = mode,
    enabled = enabled,
    revision = revision,
    observedState = observedState,
    observedChangedAt = null,
    lastSuccessAt = null,
    lastAttemptAt = null,
    errorKind = null,
    errorDetail = null,
    errorAt = null,
    appliedAction = null,
    appliedOn = null,
    appliedAt = null,
    createdAt = at,
    updatedAt = at,
)
