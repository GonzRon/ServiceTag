package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.usecase.NoSuchAsset

/**
 * #16 (C17, C22) — how the commands reach the runner, which implements this (B6a). Every call comes **after** the
 * command's write has committed. [ensure] keeps the periodic work in step with the stored settings, as C22's rule
 * allows it; [cancel] stops it; [requestFreshRead] asks for one read of that asset's binding.
 */
interface SeasonSyncScheduler {
    suspend fun ensure()

    suspend fun cancel()

    suspend fun requestFreshRead(assetId: AssetId)
}

/**
 * #16 (C17) — a binding's state as the card, the API and the MCP show it: derived when it is read, never stored.
 * Only [ACTIVE] bindings are read (C21).
 */
enum class SeasonSyncState { ACTIVE, STOPPED, NEEDS_TOKEN, NOT_MAINTAINED_HERE }

/**
 * #16 (C17; R16-4, R16-18) — the derivation, in this order: [SeasonSyncState.NEEDS_TOKEN] when the connection's token
 * is not on this phone ([hasToken] is `SecretStore.has`); [SeasonSyncState.STOPPED] when the binding is not enabled;
 * [SeasonSyncState.NOT_MAINTAINED_HERE] when its asset is not; else [SeasonSyncState.ACTIVE]. Entering a token, or
 * the asset being maintained here again, makes a binding ACTIVE with no other step.
 */
fun seasonSyncStateOf(binding: SeasonSyncBinding, hasToken: Boolean, maintainedHere: Boolean): SeasonSyncState =
    if (!hasToken) {
        SeasonSyncState.NEEDS_TOKEN
    } else if (!binding.enabled) {
        SeasonSyncState.STOPPED
    } else if (!maintainedHere) {
        SeasonSyncState.NOT_MAINTAINED_HERE
    } else {
        SeasonSyncState.ACTIVE
    }

/** [seasonSyncStateOf] over the stores: the token by the binding's connection id, the asset and the open transfers. */
suspend fun currentSeasonSyncState(
    binding: SeasonSyncBinding,
    secrets: SecretStore,
    assets: AssetRepository,
    transfers: TransferRecordRepository,
): SeasonSyncState {
    val asset = assets.get(binding.assetId)
    val here = asset != null && asset.maintainedHere(transfers.heldIds())
    return seasonSyncStateOf(binding, secrets.has(binding.connectionId), here)
}

/** Writes [next] over [stored] with the revision moved by one; a refused compare-and-set throws inside the write. */
private suspend fun SeasonSyncRepository.bump(
    stored: SeasonSyncBinding,
    next: SeasonSyncBinding,
    now: Long,
): SeasonSyncBinding {
    val written = next.copy(revision = stored.revision + 1, updatedAt = now)
    check(update(written)) { "the binding of ${stored.assetId.value} moved inside its own write" }
    return written
}

/**
 * #16 (C17, AC6) — Follow, Force in season or Force out of season, in one write. A Force mode applies its phase
 * **now** through the applier's [RecordSeasonSyncResult.applyIfChanged]: one row only if today's season differs, the
 * provenance recorded. FOLLOW writes no season row and asks for a fresh read; the asset keeps its season until that
 * read succeeds — a stored answer is never applied. The stored mode again is a no-op. A stopped binding takes the
 * mode and acts on it once resumed.
 */
class SetSeasonSyncMode(
    private val bindings: SeasonSyncRepository,
    private val assets: AssetRepository,
    private val applier: RecordSeasonSyncResult,
    private val scheduler: SeasonSyncScheduler,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId, mode: SyncMode): SeasonSyncBinding {
        var changed = false
        val written = uow.write {
            val stored = bindings.get(assetId) ?: throw SeasonSyncNotLinked(assetId)
            if (stored.mode == mode) return@write stored
            val asset = assets.get(assetId) ?: throw NoSuchAsset(assetId)
            val moded = stored.copy(mode = mode)
            val desired = desiredPhase(mode, null)
            val decided = if (desired != null && stored.enabled) {
                applier.applyIfChanged(asset, desired, moded)
            } else {
                moded
            }
            changed = true
            bindings.bump(stored, decided, clock.nowMillis())
        }
        if (changed && written.enabled && written.mode == SyncMode.FOLLOW) scheduler.requestFreshRead(assetId)
        return written
    }
}

/**
 * #16 (C17) — a new entity id for a linked asset: checked by C4's rule ([SeasonSyncLinkRefusal.BAD_ENTITY_ID]), the
 * old entity's answer and error cleared (its fetch time with it, N-5), the mode and the provenance kept, the revision
 * moved so a read of the old entity is dropped; an enabled binding is then read afresh.
 */
class EditSeasonSyncEntity(
    private val bindings: SeasonSyncRepository,
    private val scheduler: SeasonSyncScheduler,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId, entityId: String): SeasonSyncBinding {
        if (!isValidEntityId(entityId)) throw SeasonSyncLinkRefused(SeasonSyncLinkRefusal.BAD_ENTITY_ID)
        val written = uow.write {
            val stored = bindings.get(assetId) ?: throw SeasonSyncNotLinked(assetId)
            val next = stored.copy(
                entityId = entityId,
                observedState = null,
                observedChangedAt = null,
                lastSuccessAt = null,
                errorKind = null,
                errorDetail = null,
                errorAt = null,
            )
            bindings.bump(stored, next, clock.nowMillis())
        }
        if (written.enabled) scheduler.requestFreshRead(assetId)
        return written
    }
}

/**
 * #16 (C17; R16-1, R16-14) — **Stop syncing**: the binding stays, disabled, with its provenance. No END is written and
 * the asset stays MANUAL at its phase; the season guard is off, so the owner's own season controls return. After the
 * commit the work is ensured while another binding is enabled, else cancelled. Stopping a stopped binding is a no-op.
 */
class StopSeasonSync(
    private val bindings: SeasonSyncRepository,
    private val scheduler: SeasonSyncScheduler,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId): SeasonSyncBinding {
        var anyEnabled: Boolean? = null
        val written = uow.write {
            val stored = bindings.get(assetId) ?: throw SeasonSyncNotLinked(assetId)
            if (!stored.enabled) return@write stored
            bindings.bump(stored, stored.copy(enabled = false), clock.nowMillis()).also {
                anyEnabled = bindings.anyEnabled()
            }
        }
        when (anyEnabled) {
            true -> scheduler.ensure()
            false -> scheduler.cancel()
            null -> Unit
        }
        return written
    }
}

/**
 * #16 (C17, R16-19) — **Resume syncing** runs the link's reconciliation: the link's refusals but the entity's and the
 * duplicate's ([LinkSeasonSync.readyInTransaction]); an asset no longer MANUAL is switched back into MANUAL at today's
 * phase, the switch row dated today (limit 14), and a strands refusal writes nothing and leaves the binding stopped;
 * a MANUAL asset takes no row. The switch runs **before** the binding is enabled, with the season guard asked, so its
 * own binding never refuses it. Then the binding is enabled, its revision moved, the work ensured and a fresh read
 * asked for. Resuming an enabled binding is a no-op.
 */
class ResumeSeasonSync(
    private val link: LinkSeasonSync,
    private val bindings: SeasonSyncRepository,
    private val scheduler: SeasonSyncScheduler,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId): SeasonSyncLinked {
        var resumed = false
        val linked = uow.write {
            val stored = bindings.get(assetId) ?: throw SeasonSyncNotLinked(assetId)
            if (stored.enabled) return@write SeasonSyncLinked(stored, null)
            val (asset, _) = link.readyInTransaction(assetId)
            val switchedFrom = link.intoManualInTransaction(asset, guarded = true)
            resumed = true
            SeasonSyncLinked(bindings.bump(stored, stored.copy(enabled = true), clock.nowMillis()), switchedFrom)
        }
        if (resumed) {
            scheduler.ensure()
            scheduler.requestFreshRead(assetId)
        }
        return linked
    }
}

/** #16 (C17) — why a connection was not saved, before anything was written: a code, never a sentence. */
enum class SaveHaConnectionRefusal {
    /** [HaEndpointPolicy] refused the address, whichever rule it was (P16-12). */
    ENDPOINT_REFUSED,

    /** An `http` address with [NetworkEligibility.ANY_NETWORK]: cleartext goes only from the home network (P16-67). */
    HTTP_NEEDS_HOME_NETWORK,

    /** [NetworkEligibility.HOME_NETWORK_ONLY] with no captured Wi-Fi name, or a blank one (P16-62's prompt). */
    HOME_NETWORK_NOT_SET,
}

/** #16 (C17) — a save refused for [reason]; nothing was written. The message is the code only, never the address. */
class SaveHaConnectionRefused(val reason: SaveHaConnectionRefusal) :
    IllegalStateException("home assistant connection refused: $reason")

/**
 * #16 (C17; R16-10, R16-Q-D, R16-20) — saves the one connection of this installation. The address is asked of
 * [HaEndpointPolicy] first and stored canonical; then an `http` address needs [NetworkEligibility.HOME_NETWORK_ONLY],
 * and that needs a captured, non-blank Wi-Fi name, kept exactly as given; under ANY_NETWORK no name is kept.
 *
 * The row is **updated in place** under its first id, never deleted and inserted, so its bindings stay: a new one
 * takes a fresh id from [ids], [SyncCadence.DAILY] and [BackgroundChecks.OFF] unless [cadence] or
 * [backgroundChecks] is given; a null [cadence] or [backgroundChecks] keeps a stored connection's. [token] is put in
 * the [SecretStore] **after** the commit, and only when given. A new token, address, eligibility or Wi-Fi name moves
 * every binding's revision, so a read built before it is dropped, and each enabled binding is then read afresh; a
 * changed cadence, eligibility or background setting ensures or cancels the work after the commit.
 */
class SaveHaConnection(
    private val connections: HaConnectionRepository,
    private val bindings: SeasonSyncRepository,
    private val secrets: SecretStore,
    private val scheduler: SeasonSyncScheduler,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    private class Saved(val connection: HaConnection, val reread: List<AssetId>, val work: Boolean?)

    suspend fun run(
        baseUrl: String,
        token: Secret?,
        cadence: SyncCadence?,
        networkEligibility: NetworkEligibility,
        homeNetworkSsid: String?,
        backgroundChecks: BackgroundChecks?,
    ): HaConnection {
        val endpoint = when (val check = HaEndpointPolicy.classify(baseUrl)) {
            is EndpointCheck.Allowed -> check.endpoint
            is EndpointCheck.Refused -> throw SaveHaConnectionRefused(SaveHaConnectionRefusal.ENDPOINT_REFUSED)
        }
        val ssid = when (networkEligibility) {
            NetworkEligibility.ANY_NETWORK -> when (endpoint.scheme) {
                HaScheme.HTTP -> throw SaveHaConnectionRefused(SaveHaConnectionRefusal.HTTP_NEEDS_HOME_NETWORK)
                HaScheme.HTTPS -> null
            }
            NetworkEligibility.HOME_NETWORK_ONLY -> homeNetworkSsid?.takeIf { it.isNotBlank() }
                ?: throw SaveHaConnectionRefused(SaveHaConnectionRefusal.HOME_NETWORK_NOT_SET)
        }
        val saved = uow.write {
            val stored = connections.get()
            val now = clock.nowMillis()
            val next = stored?.copy(
                baseUrl = endpoint.canonical,
                cadence = cadence ?: stored.cadence,
                networkEligibility = networkEligibility,
                homeNetworkSsid = ssid,
                backgroundChecks = backgroundChecks ?: stored.backgroundChecks,
                updatedAt = now,
            ) ?: HaConnection(
                id = ids.newId(),
                baseUrl = endpoint.canonical,
                cadence = cadence ?: SyncCadence.DAILY,
                networkEligibility = networkEligibility,
                homeNetworkSsid = ssid,
                backgroundChecks = backgroundChecks ?: BackgroundChecks.OFF,
                createdAt = now,
                updatedAt = now,
            )
            connections.upsert(next)
            if (stored == null) return@write Saved(next, emptyList(), null)
            val reauthorized = token != null ||
                stored.baseUrl != next.baseUrl ||
                stored.networkEligibility != next.networkEligibility ||
                stored.homeNetworkSsid != next.homeNetworkSsid
            val reread = if (reauthorized) {
                bindings.all().map { bindings.bump(it, it, now) }.filter { it.enabled }.map { it.assetId }
            } else {
                emptyList()
            }
            val rescheduled = stored.cadence != next.cadence ||
                stored.networkEligibility != next.networkEligibility ||
                stored.backgroundChecks != next.backgroundChecks
            Saved(next, reread, if (rescheduled) bindings.anyEnabled() else null)
        }
        if (token != null) secrets.put(saved.connection.id, token)
        when (saved.work) {
            true -> scheduler.ensure()
            false -> scheduler.cancel()
            null -> Unit
        }
        saved.reread.forEach { scheduler.requestFreshRead(it) }
        return saved.connection
    }
}

/**
 * #16 (C17; R16-14) — **Disconnect**: deletes the connection, and with it every binding (the schema's CASCADE), in one
 * write; no END is written and season history stays. Then the token is deleted from the [SecretStore] and the work
 * cancelled — cancelled even when the store fails, whose failure then reaches the caller to be logged by its kind; a
 * token left behind is swept at the next start (C18). Home Assistant itself still holds the token (limit 10).
 */
class ForgetHaConnection(
    private val connections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val scheduler: SeasonSyncScheduler,
    private val uow: UnitOfWork,
) {
    suspend fun run() {
        val id = uow.write { connections.get()?.id?.also { connections.delete(it) } } ?: return
        try {
            secrets.delete(id)
        } finally {
            scheduler.cancel()
        }
    }
}
