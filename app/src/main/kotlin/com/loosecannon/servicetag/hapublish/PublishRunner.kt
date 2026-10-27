package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.DecommissionRepository
import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DecommissionSteps
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.DeliveryPlan
import com.loosecannon.servicetag.core.hapublish.DeliveryRecord
import com.loosecannon.servicetag.core.hapublish.DeliverySteps
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger
import com.loosecannon.servicetag.core.hapublish.FIELD_BOUNDARY_ENTITY_ID
import com.loosecannon.servicetag.core.hapublish.FIELD_ENTITY_ID
import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishBindingRepository
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishDeliveryRepository
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.hapublish.ReadStep
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.RetryClass
import com.loosecannon.servicetag.core.hapublish.Snap
import com.loosecannon.servicetag.core.hapublish.TombstonePlan
import com.loosecannon.servicetag.core.hapublish.TombstoneStep
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.hapublish.boundaryCache
import com.loosecannon.servicetag.core.hapublish.boundaryDateOf
import com.loosecannon.servicetag.core.hapublish.domainRefusalOf
import com.loosecannon.servicetag.core.hapublish.haBoundaryOf
import com.loosecannon.servicetag.core.hapublish.retryClassOf
import com.loosecannon.servicetag.core.hapublish.validateDateTarget
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.season.HaBoundary
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.seasonsync.SeasonSyncSchedule
import com.loosecannon.servicetag.seasonsync.seasonSyncScheduleOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * #113/#114 (C30) — a Room read or write of a publish pass failed, the stored-connection lookup and the projector edge
 * included: the worker retries it (#16's `SeasonSyncDatabaseFailed` shape). No message; the cause is never logged.
 */
class PublishDatabaseFailed(cause: Throwable) : Exception(null, cause)

/** #113/#114 (C30) — what one binding's pass came to, as it was recorded. */
sealed interface PassResult {
    /** C17's gate left the binding alone: nothing was read and nothing sent. */
    data class Skipped(val state: PublishState) : PassResult

    /** Verified at the current pair with nothing outstanding: nothing was read (a change or scheduled pass only). */
    data object Current : PassResult

    /** A read equal to the desired value was recorded; [posted] when our POST made it so. */
    data class Verified(val posted: Boolean) : PassResult

    /** [kind] was recorded at the binding's pair. */
    data class Failed(val kind: PublishErrorKind, val detail: String?) : PassResult

    /** Each record of this call was dropped by a moved generation or a newer desired value; the next trigger sends it. */
    data object Superseded : PassResult
}

/**
 * One binding's pass: its [result]; [boundaryFailure], the season-ended helper's failed read when it left a reconcile
 * pending (C9a, C17); and [readCompleted], whether Home Assistant answered a read of the helper (N-1's window).
 */
data class BindingOutcome(
    val bindingId: String,
    val assetId: AssetId,
    val kind: PublishKind,
    val result: PassResult,
    val boundaryFailure: PassResult.Failed? = null,
    val readCompleted: Boolean = false,
) {
    /** B1's carry: from the retry class of what was recorded, never from the selection. */
    val leavesTransientWork: Boolean
        get() = (result as? PassResult.Failed).isTransient() || boundaryFailure.isTransient()
}

private fun PassResult.Failed?.isTransient(): Boolean =
    this != null && retryClassOf(kind, detail) == RetryClass.TRANSIENT

/** #114 (C34) — what one tombstone's drain came to, as it was recorded. */
sealed interface TombstoneResult {
    /** The helper was read back holding `1970-01-01` and the row deleted by its id; [posted] when our POST made it so. */
    data class Cleared(val posted: Boolean) : TombstoneResult

    /** Still PENDING with [kind] (a `TRANSIENT` or `WAIT` class): drawn with P114-36 and drained again. */
    data class Pending(val kind: PublishErrorKind, val detail: String?) : TombstoneResult

    /** UNDELIVERABLE with [kind]: drawn with P114-37 until the owner dismisses it (P114-38). */
    data class Undeliverable(val kind: PublishErrorKind) : TombstoneResult

    /** The row moved or went while this drain held it (a merge, an Enable's supersession): nothing was recorded. */
    data object Superseded : TombstoneResult
}

/** #114 (C34) — one tombstone's drain: its id, helper and asset (for display only), and what it came to. */
data class TombstoneOutcome(val id: String, val entityId: String, val assetId: AssetId?, val result: TombstoneResult) {
    /** C32 — a PENDING row whose error is TRANSIENT is work the one-time work's backoff retries. */
    val leavesTransientWork: Boolean
        get() = result is TombstoneResult.Pending && retryClassOf(result.kind, result.detail) == RetryClass.TRANSIENT
}

/** #113/#114 (C30) — one call's bindings, in the order they were passed, and the tombstones it drained (C34). */
data class PublishPass(val outcomes: List<BindingOutcome>, val tombstones: List<TombstoneOutcome> = emptyList()) {
    /** C32 — TRANSIENT work remains: an error of that class or an unknown outcome, or a reconcile such a failure left. */
    val transientWorkRemains: Boolean
        get() = outcomes.any { it.leavesTransientWork } || tombstones.any { it.leavesTransientWork }

    /** C31, N-1 — some helper's read completed (the resume window counts from it; a gate refusal never does). */
    val readCompleted: Boolean get() = outcomes.any { it.readCompleted }
}

/**
 * #114 (C34, O-11) — what Forget's drain came to: one attempt for each enabled date binding of the connection, with
 * `1970-01-01` as its desired value ([bindings]), and one for each of its PENDING tombstones ([tombstones]).
 */
data class ForgetDrain(val bindings: List<BindingOutcome>, val tombstones: List<TombstoneOutcome>)

/** #113/#114 (C30, A09, F) — what `validate` read, or why it read nothing it can stand on. */
sealed interface Validation {
    /**
     * Both helpers as Home Assistant answered them, with one provenance: the connection's id and `configGeneration`
     * and the binding's revision as one snapshot held them before either read. The validators are the commands' and
     * the plan's (C25, C25a), so a helper that fails one is still a result here.
     */
    data class Validated(val target: ValidationResult, val boundary: ValidationResult?) : Validation

    /** No connection is stored: nothing was read. */
    data object NoConnection : Validation

    /**
     * The helper at [field] (`entityId` or `boundaryEntityId`) gave no answer to stand on: refused before any request
     * (`WRONG_DOMAIN`, `NEEDS_TOKEN`) or the read's own [kind] and [detail].
     */
    data class ReadFailed(val field: String, val kind: PublishErrorKind, val detail: String?) : Validation
}

/**
 * #113/#114 (C30) — **the one sender.** One per graph and one [Mutex], so one request at a time per installation
 * (I9). Each binding is snapshotted in one read transaction (the binding, its delivery, the connection, the asset row
 * and the held ids, its #16 binding and newest activation for the boundary), with the token from [secrets] and this
 * installation's id; then B1's [DeliverySteps] decide, and every record is written by a compare-and-set on the
 * snapshot's `(binding revision, desired revision)`, so a moved generation or a newer desired value drops it (I7). No
 * transaction is open across a request (I8); a transport failure is a recorded value, never a throw; a cancellation
 * propagates, and the write-ahead record before every POST keeps the attempt visible to the next pass (A05).
 *
 * - [publishNow] — Publish now and reconcile mode (start, resume, the periodic work): every enabled binding is read.
 * - [drain] — C17's selection under [trigger] (a change by default; the midnight tick and the worker pass
 *   `SCHEDULED`); answers whether TRANSIENT work remains.
 * - [validate] — the only producer of a [ValidationResult].
 * - [schedule] — the periodic work's rule, #16's over this side's work.
 *
 * The boundary step (C9a): for a date binding whose season end Home Assistant decides, in reconcile mode always and
 * otherwise when the cache is not fresh, one read of the season-ended helper; a valid date is cached by
 * [boundaryCache] at the snapshot's binding revision, then [reconciler] projects before this pass sends. A failed or
 * malformed read keeps the old cache; a helper that fails its validator clears the validation, as the target's does.
 * The decommission tombstones (C34) are drained in every [drain] and [publishNow] after the bindings, and by
 * [drainBeforeForget]: one at a time, the gate, a read, a POST of `1970-01-01` and its read-back, each record the row's
 * revision compare-and-set, a read equal to it deleting the row by its id.
 */
class PublishRunner(
    private val bindings: PublishBindingRepository,
    private val deliveries: PublishDeliveryRepository,
    private val decommissions: DecommissionRepository,
    private val connections: HaConnectionRepository,
    private val seasonSync: SeasonSyncRepository,
    private val secrets: SecretStore,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val activations: SeasonActivationRepository,
    private val installationId: () -> String,
    private val transport: HaPublishTransport,
    private val boundaryCache: BoundaryCacheWriter,
    private val reconciler: DesiredStateReconciler,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val today: Today,
) {
    private val lock = Mutex()

    /**
     * Waits for a running pass, then reads back every enabled binding of [assetId], or of every asset (#113), and
     * drains the PENDING tombstones of that asset, or every one (C34).
     */
    suspend fun publishNow(assetId: AssetId? = null): PublishPass = lock.withLock {
        pass(DeliveryTrigger.RECONCILE, { assetId == null || it.assetId == assetId }) {
            assetId == null || it.assetId == assetId
        }
    }

    /**
     * Waits for a running pass, then delivers what C17 selects under [trigger] and drains every PENDING tombstone
     * (C34); true when TRANSIENT work remains.
     */
    suspend fun drain(trigger: DeliveryTrigger = DeliveryTrigger.CHANGE): Boolean = lock.withLock {
        pass(trigger, { true }, { true }).transientWorkRemains
    }

    /**
     * C34, O-11 — Forget's drain, run by `PublishControls.forgetConnection` before the command, under the lock: for each
     * enabled date binding of the stored connection, `1970-01-01` made its desired value at its own pair (the
     * projector's compare-and-set write) and **one** attempt to deliver and read it back, with no boundary read and no
     * projection, so the binding is left verified at its current pair (B3's ruling) or the command owes it; then one
     * attempt for each PENDING tombstone of the connection. Each request is bounded by the client's timeouts (C22).
     */
    suspend fun drainBeforeForget(): ForgetDrain = lock.withLock {
        val connectionId = database { uow.read { connections.get()?.id } }
            ?: return@withLock ForgetDrain(emptyList(), emptyList())
        val dates = database {
            uow.read {
                bindings.all().filter {
                    it.connectionId == connectionId && it.enabled && it.kind == PublishKind.SEASON_SERVICE_DATE
                }
            }
        }
        val invalidated = dates.mapNotNull { binding ->
            if (invalidate(binding.id)) deliver(binding.id, DeliveryTrigger.CHANGE, forgetting = true) else null
        }
        ForgetDrain(invalidated, drainTombstones { it.connectionId == connectionId })
    }

    /**
     * Under the lock: one snapshot of the connection's id and `configGeneration` and the [assetId]'s [kind] binding's
     * revision (0 when none), then the target's read and, when [boundaryEntityId] is given, the season-ended helper's
     * read, both results carrying that one snapshot (A09, F). A wrong domain is refused before any request.
     */
    suspend fun validate(
        assetId: AssetId,
        kind: PublishKind,
        entityId: String,
        boundaryEntityId: String?,
    ): Validation = lock.withLock {
        val (connection, revision) = database {
            uow.read { connections.get() to (bindings.forAsset(assetId).firstOrNull { it.kind == kind }?.revision ?: 0L) }
        }
        if (connection == null) return Validation.NoConnection
        domainRefusalOf(kind, entityId)?.let { return Validation.ReadFailed(FIELD_ENTITY_ID, it, null) }
        if (boundaryEntityId != null) {
            domainRefusalOf(PublishKind.SEASON_SERVICE_DATE, boundaryEntityId)?.let {
                return Validation.ReadFailed(FIELD_BOUNDARY_ENTITY_ID, it, null)
            }
        }
        val token = secrets.get(connection.id)
            ?: return Validation.ReadFailed(FIELD_ENTITY_ID, PublishErrorKind.NEEDS_TOKEN, null)

        // Both results from the one snapshot above, whatever a save does between the two reads.
        fun resultOf(id: String, read: RemoteRead.Answered) = ValidationResult(
            id, read.remote, connection.id, connection.configGeneration, revision, clock.nowMillis(),
        )
        val target = when (val read = transport.read(connection, entityId, token)) {
            is RemoteRead.Failed -> return Validation.ReadFailed(FIELD_ENTITY_ID, read.kind, read.detail)
            is RemoteRead.Answered -> resultOf(entityId, read)
        }
        val boundary = boundaryEntityId?.let { id ->
            when (val read = transport.read(connection, id, token)) {
                is RemoteRead.Failed -> return Validation.ReadFailed(FIELD_BOUNDARY_ENTITY_ID, read.kind, read.detail)
                is RemoteRead.Answered -> resultOf(id, read)
            }
        }
        Validation.Validated(target, boundary)
    }

    /** C30, C32 — #16's rule over the stored connection and "any publish binding enabled or any tombstone pending". */
    suspend fun schedule(backgroundAllowed: Boolean): SeasonSyncSchedule {
        val (connection, anyWork) = database {
            uow.read {
                connections.get() to (
                    bindings.all().any { it.enabled } ||
                        decommissions.all().any { it.state == DecommissionState.PENDING }
                    )
            }
        }
        return seasonSyncScheduleOf(connection, anyWork, backgroundAllowed)
    }

    private suspend fun pass(
        trigger: DeliveryTrigger,
        wanted: (PublishBinding) -> Boolean,
        tombstonesWanted: (DecommissionTombstone) -> Boolean,
    ): PublishPass {
        val selected = database {
            uow.read {
                bindings.all().filter { binding ->
                    wanted(binding) && deliveries.get(binding.id)?.let { DeliverySteps.selects(binding, it, trigger) } == true
                }
            }
        }
        val outcomes = selected.mapNotNull { deliver(it.id, trigger) }
        return PublishPass(outcomes, drainTombstones(tombstonesWanted))
    }

    /**
     * B3's ruling for Forget: `1970-01-01` as the binding's desired value, written through the projector's own
     * compare-and-set at the pair just read (a new desired revision, its error cleared). False when the binding or its
     * delivery went meanwhile, or the write lost its compare-and-set (the command then owes the helper).
     */
    private suspend fun invalidate(bindingId: String): Boolean = database {
        uow.write {
            val binding = bindings.get(bindingId) ?: return@write false
            val delivery = deliveries.get(bindingId) ?: return@write false
            delivery.desiredValue == INVALID_SERVICE_DATE || deliveries.writeDesired(
                binding.id, binding.revision, delivery.desiredRevision, INVALID_SERVICE_DATE, clock.nowMillis(),
            )
        }
    }

    /**
     * C34 — each PENDING tombstone [wanted], one at a time from its own snapshot: the gate ([DecommissionSteps.plan]),
     * then a read, and a POST of `1970-01-01` and its read-back while the helper holds anything else; a read equal to
     * it deletes the row **by its id**. Every record is the row's revision compare-and-set; no transaction is held
     * across a request; a transport failure is a recorded value. A PENDING row whose error is of the `WAIT` class is
     * tried again on every pass, a scheduled one included (unlike a binding's R-2 skip): the request gate refuses it
     * again before any HTTP while the network still does not allow one (B5 review N2).
     */
    private suspend fun drainTombstones(wanted: (DecommissionTombstone) -> Boolean): List<TombstoneOutcome> {
        val pending = database {
            uow.read { decommissions.all().filter { it.state == DecommissionState.PENDING && wanted(it) } }
        }
        return pending.mapNotNull { drainTombstone(it.id) }
    }

    private suspend fun drainTombstone(id: String): TombstoneOutcome? {
        val (held, connection) = database { uow.read { decommissions.get(id) to connections.get() } }
        if (held == null) return null
        val token = connection?.takeIf { it.id == held.connectionId }?.let { secrets.get(it.id) }
        val outcome = { result: TombstoneResult -> TombstoneOutcome(held.id, held.entityId, held.assetId, result) }
        val plan = DecommissionSteps.plan(held, installationId(), connection?.id, token != null, clock.nowMillis())
        when (plan) {
            TombstonePlan.Wait -> return null
            is TombstonePlan.GiveUp -> return outcome(recordTombstone(held, TombstoneStep.Record(plan.record)))
            TombstonePlan.ReadFirst -> Unit
        }
        // The gate passed, so the connection is the tombstone's and its token is here.
        val target = checkNotNull(connection)
        val secret = checkNotNull(token)
        val read = transport.read(target, held.entityId, secret)
        when (val next = DecommissionSteps.afterRead(held, read, clock.nowMillis())) {
            TombstoneStep.Cleared -> return outcome(clearTombstone(held, posted = false))
            is TombstoneStep.Record -> return outcome(recordTombstone(held, next))
            TombstoneStep.Post -> Unit
        }
        // Still owed as read: a merge, an Enable's supersession or a dismissal meanwhile leaves it to the next pass.
        val still = database { uow.read { decommissions.get(id) } }
        if (still != held) return outcome(TombstoneResult.Superseded)
        val call = transport.call(target, HaServiceAction.SET_DATE, held.entityId, INVALID_SERVICE_DATE, secret)
        val after = when (call) {
            CallOutcome.Accepted -> {
                val back = transport.read(target, held.entityId, secret)
                DecommissionSteps.afterPostAccepted(held, back, clock.nowMillis())
            }
            is CallOutcome.Refused -> DecommissionSteps.afterPostRefused(held, call.kind, call.detail, clock.nowMillis())
            is CallOutcome.Unknown -> DecommissionSteps.afterPostUnknown(held, call.detail, clock.nowMillis())
        }
        return when (after) {
            TombstoneStep.Cleared -> outcome(clearTombstone(held, posted = true))
            is TombstoneStep.Record -> outcome(recordTombstone(held, after))
        }
    }

    /** The helper read back as `1970-01-01`: the row deleted by its id, never by its pair. */
    private suspend fun clearTombstone(held: DecommissionTombstone, posted: Boolean): TombstoneResult {
        database { uow.write { decommissions.delete(held.id) } }
        return TombstoneResult.Cleared(posted)
    }

    /**
     * [step]'s state and error by the row's revision compare-and-set, and only while the row is still exactly [held],
     * as this drain read it, in the same write: a moved, deleted or otherwise changed row records nothing — Forget's
     * PENDING → UNDELIVERABLE keeps the revision, so the compare-and-set alone would not see it (B5 review M1).
     */
    private suspend fun recordTombstone(held: DecommissionTombstone, step: TombstoneStep.Record): TombstoneResult {
        val record = step.record
        val wrote = database { uow.write { decommissions.get(held.id) == held && decommissions.update(record) } }
        val kind = checkNotNull(record.errorKind) { "a tombstone record carries its kind" }
        if (!wrote) return TombstoneResult.Superseded
        return when (record.state) {
            DecommissionState.UNDELIVERABLE -> TombstoneResult.Undeliverable(kind)
            DecommissionState.PENDING -> TombstoneResult.Pending(kind, step.detail)
        }
    }

    /**
     * One binding, from its own snapshot; null when it was deleted meanwhile. [forgetting]: Forget's one attempt at
     * `1970-01-01` — no boundary step, no projection, and nothing sent once the desired value is another.
     */
    private suspend fun deliver(
        bindingId: String,
        trigger: DeliveryTrigger,
        forgetting: Boolean = false,
    ): BindingOutcome? {
        val state = PassState()
        if (forgetting) {
            state.reprojected = true
            state.boundaryStepped = true
            state.forgetting = true
        }
        while (true) {
            val loaded = load(bindingId) ?: return null
            val binding = loaded.snap.binding
            val outcome = { result: PassResult ->
                BindingOutcome(binding.id, binding.assetId, binding.kind, result, state.boundaryFailure, state.readCompleted)
            }
            val step = attempt(loaded, trigger, state)
            when (step) {
                is Step.Finished -> return outcome(step.result)
                Step.Again -> continue
                Step.Dropped -> if (++state.drops > MAX_DROPS) return outcome(PassResult.Superseded)
            }
        }
    }

    /** One attempt over one snapshot: what it recorded, or why the binding is snapshotted again. */
    private suspend fun attempt(loaded: Loaded, trigger: DeliveryTrigger, state: PassState): Step {
        val snap = loaded.snap
        val binding = snap.binding
        val (value, reconcile) = when (val plan = DeliverySteps.plan(snap, trigger)) {
            is DeliveryPlan.Skip -> {
                if (plan.reproject && !state.reprojected) {
                    state.reprojected = true
                    if (reproject()) return Step.Again
                }
                return Step.Finished(PassResult.Skipped(plan.state))
            }
            DeliveryPlan.Done -> return Step.Finished(PassResult.Current)
            is DeliveryPlan.ReadFirst -> plan.value to plan.reconcile
        }
        // Forget sends only 1970-01-01: a projection that moved the desired value meanwhile leaves it owed.
        if (state.forgetting && value != INVALID_SERVICE_DATE) return Step.Finished(PassResult.Superseded)
        // The gate passed, so the connection is the binding's and its token is here.
        val connection = checkNotNull(loaded.connection)
        val token = checkNotNull(loaded.token)
        var delivery = snap.delivery

        if (reconcile && !delivery.reconcilePending) {
            val ahead = DeliverySteps.reconcileWriteAhead(binding, delivery)
            if (!record(ahead)) return Step.Dropped
            delivery = ahead.delivery
        }
        if (!state.boundaryStepped && boundaryDue(loaded.boundary, reconcile)) {
            state.boundaryStepped = true
            boundaryStep(binding, delivery, connection, token, reconcile, state)?.let { return it }
        }

        val read = transport.read(connection, binding.entityId, token)
        if (read is RemoteRead.Answered) state.readCompleted = true
        return when (val next = DeliverySteps.afterRead(binding, delivery, value, read, clock.nowMillis(), state.boundaryPending)) {
            is ReadStep.Record -> recorded(next.record, posted = false)
            is ReadStep.Post -> post(binding, delivery, next, connection, token)
        }
    }

    /**
     * C9a — the season-ended helper, read once per call: a valid date cached at the snapshot's revision and then a
     * projection (answers [Step.Again] to send from a fresh snapshot); a validator failure recorded with the validation
     * cleared; a failed read or an unanswered date records nothing. Null: go on to the target's read.
     */
    private suspend fun boundaryStep(
        binding: PublishBinding,
        delivery: PublishDelivery,
        connection: HaConnection,
        token: Secret,
        reconcile: Boolean,
        state: PassState,
    ): Step? {
        val boundaryId = checkNotNull(binding.boundaryEntityId)
        // Asked before the GET: an activation created while the read is in flight is never older than the cache (C9a).
        val readAt = clock.nowMillis()
        val remote = when (val read = transport.read(connection, boundaryId, token)) {
            is RemoteRead.Failed -> {
                state.boundaryPending = true
                if (reconcile) state.boundaryFailure = PassResult.Failed(read.kind, read.detail)
                return null
            }
            is RemoteRead.Answered -> read.remote
        }
        validateDateTarget(boundaryId, remote)?.let { kind ->
            val failed = DeliverySteps.failed(binding, delivery, kind, null, clock.nowMillis(), clearValidation = true)
            return if (record(failed)) Step.Finished(PassResult.Failed(kind, null)) else Step.Dropped
        }
        val endedOn = boundaryDateOf(remote) ?: return null
        val cached = database { boundaryCache.recordBoundary(binding.id, binding.revision, endedOn.toString(), readAt) }
        if (!cached) {
            state.boundaryPending = true
            return Step.Dropped
        }
        state.reprojected = true
        reproject()
        return Step.Again
    }

    /** The write-ahead, the one POST and its read-back, then the record (A05, C17, C23). */
    private suspend fun post(
        binding: PublishBinding,
        delivery: PublishDelivery,
        step: ReadStep.Post,
        connection: HaConnection,
        token: Secret,
    ): Step {
        if (!admissible(step.action, binding.entityId, step.value)) {
            // A desired value outside its action's shape is never sent (B2: the client refuses it as a bug).
            return recorded(
                DeliverySteps.failed(binding, delivery, PublishErrorKind.MALFORMED, null, clock.nowMillis()),
                posted = false,
            )
        }
        if (!record(step.writeAhead)) return Step.Dropped
        val ahead = step.writeAhead.delivery
        val after = when (val call = transport.call(connection, step.action, binding.entityId, step.value, token)) {
            CallOutcome.Accepted -> {
                val back = transport.read(connection, binding.entityId, token)
                DeliverySteps.afterPostAccepted(binding, ahead, step.value, back, clock.nowMillis())
            }
            is CallOutcome.Refused -> DeliverySteps.afterPostRefused(binding, ahead, call.kind, call.detail, clock.nowMillis())
            is CallOutcome.Unknown -> DeliverySteps.afterPostUnknown(binding, ahead, call.detail, clock.nowMillis())
        }
        return recorded(after, posted = true)
    }

    /** [record] written, as the pass's result; a dropped record is snapshotted again. */
    private suspend fun recorded(record: DeliveryRecord, posted: Boolean): Step {
        if (!record(record)) return Step.Dropped
        val kind = record.delivery.errorKind
        return Step.Finished(if (kind == null) PassResult.Verified(posted) else PassResult.Failed(kind, record.delivery.errorDetail))
    }

    /** C17's compare-and-set, in one write: the delivery's bookkeeping and, when asked, the validation cleared. */
    private suspend fun record(record: DeliveryRecord): Boolean = database {
        uow.write {
            val wrote = deliveries.record(record.bindingRevision, record.delivery)
            if (wrote && record.clearValidation) bindings.clearValidation(record.delivery.bindingId, record.bindingRevision)
            wrote
        }
    }

    /** The projector edge (C30): answers whether a desired value moved. */
    private suspend fun reproject(): Boolean = database { reconciler.reconcileDesired(today.localDate()) }

    private suspend fun load(bindingId: String): Loaded? {
        val row = database {
            uow.read {
                val binding = bindings.get(bindingId) ?: return@read null
                val delivery = deliveries.get(bindingId) ?: return@read null
                val dated = binding.kind == PublishKind.SEASON_SERVICE_DATE
                Row(
                    binding = binding,
                    delivery = delivery,
                    connection = connections.get(),
                    asset = assets.get(binding.assetId),
                    held = transfers.heldIds(),
                    inboundEnabled = dated && seasonSync.get(binding.assetId)?.enabled == true,
                    newestActivationAt = if (dated) activations.forAsset(binding.assetId).maxOfOrNull { it.createdAt } else null,
                )
            }
        } ?: return null
        val connection = row.connection?.takeIf { it.id == row.binding.connectionId }
        val token = connection?.let { secrets.get(it.id) }
        val snap = Snap(
            row.binding, row.delivery, connection != null, token != null, installationId(), row.asset, row.held,
        )
        val boundary = if (row.asset == null || connection == null) {
            HaBoundary.NotUsed
        } else {
            haBoundaryOf(
                row.delivery.boundaryCache, row.binding, row.asset.seasonMode, row.inboundEnabled, connection.cadence,
                row.newestActivationAt, clock.nowMillis(),
            )
        }
        return Loaded(snap, connection, token, boundary)
    }

    private class Row(
        val binding: PublishBinding,
        val delivery: PublishDelivery,
        val connection: HaConnection?,
        val asset: Asset?,
        val held: Set<AssetId>,
        val inboundEnabled: Boolean,
        val newestActivationAt: Long?,
    )

    private class Loaded(val snap: Snap, val connection: HaConnection?, val token: Secret?, val boundary: HaBoundary)

    /** One binding's progress across its attempts in one call. */
    private class PassState {
        var drops = 0
        var reprojected = false
        var boundaryStepped = false
        /** The boundary read has not completed at the current pair, so a reconcile stays pending (C17, C9a). */
        var boundaryPending = false
        var boundaryFailure: PassResult.Failed? = null
        var readCompleted = false
        /** Forget's attempt: `1970-01-01` only. */
        var forgetting = false
    }

    private sealed interface Step {
        data class Finished(val result: PassResult) : Step

        /** A projection moved something: snapshot again. */
        data object Again : Step

        /** A record was dropped by a moved generation or a newer desired value: snapshot again, at most twice. */
        data object Dropped : Step
    }

    private companion object {
        /** C30: a dropped record loops at most twice per call; a third newer desired value waits for the next trigger. */
        const val MAX_DROPS = 2

        /** C9a: in reconcile mode always, otherwise when the cache is not fresh; never when HA does not decide. */
        fun boundaryDue(boundary: HaBoundary, reconcile: Boolean): Boolean = when (boundary) {
            HaBoundary.NotUsed -> false
            is HaBoundary.Fresh -> reconcile
            is HaBoundary.Unverified -> true
        }
    }
}

/**
 * #113 (C22, C30) — the publish client's stored-connection lookup for `AppGraph`: a database read, so its failure is a
 * [PublishDatabaseFailed] and the worker retries it.
 */
fun storedPublishConnection(connections: HaConnectionRepository): suspend () -> HaConnection? =
    { database { connections.get() } }

private suspend fun <T> database(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: PublishDatabaseFailed) {
    throw e
} catch (e: Exception) {
    throw PublishDatabaseFailed(e)
}
