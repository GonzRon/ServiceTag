package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.OutboundTargets
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.isValidEntityId
import com.loosecannon.servicetag.core.usecase.NoSuchAsset

/**
 * #113/#114 (C25, C25a) — why Enable, Change target or Check helper wrote nothing: a code the phone and the API map to
 * one sentence each, never a sentence itself. A closed set, drawn by exhaustive `when`s.
 */
enum class PublishRefusal {
    /** No helper chosen, or an id outside #16's shape; at the boundary field, the required season-ended helper (P113-29). */
    BAD_ENTITY_ID,

    /** The id's domain is not the kind's (P113-16, P114-34). */
    WRONG_DOMAIN,

    /** A status helper whose options are not exactly UP, DOWN and DEGRADED (P113-15). */
    OPTIONS_MISMATCH,

    /** Not a date-only helper (P114-32). */
    NOT_DATE_ONLY,

    /** No connection is saved on this phone (P16-10). */
    NO_CONNECTION,

    /** The asset is archived, retired or held by an open transfer (P113-32). */
    NOT_MAINTAINED_HERE,

    /** Another asset's enabled binding claims the helper ([PublishRefused.holderAssetId], P113-22). */
    TARGET_IN_USE,

    /** An inbound #16 binding reads the helper, or a date binding reads it as its season-ended helper (P113-23). */
    INBOUND_SOURCE,

    /** The read is not of this helper, this connection and generation, this binding revision, or is too old (P113-31). */
    STALE_READ,

    /** The season-ended helper is the target, or one this phone writes (P114-48). */
    BOUNDARY_IS_TARGET,

    /** The API's plan no longer holds (P113-44; on the wire, 409 `STALE_PLAN`). */
    STALE_PLAN,
}

/** The field a [PublishRefused] names: the target, the season-ended helper, or the plan. */
const val FIELD_ENTITY_ID = "entityId"
const val FIELD_BOUNDARY_ENTITY_ID = "boundaryEntityId"
const val FIELD_PLAN_ID = "planId"

/** #113/#114 (C25) — how old a validation read may be when its command commits. */
const val VALIDATION_MAX_AGE_MILLIS: Long = 10L * 60 * 1000

/**
 * #113/#114 (C25) — a publishing control refused for [reason] at [field]; nothing was written. [holderAssetId] names
 * the asset holding the helper for `TARGET_IN_USE`. The message is the code only, never an id or an address.
 */
class PublishRefused(val reason: PublishRefusal, val field: String, val holderAssetId: AssetId? = null) :
    IllegalStateException("ha publishing refused: $reason")

/** #113/#114 (C25) — a control named an asset with no binding of [kind]. The phone draws no such control. */
class PublishNotSetUp(val assetId: AssetId, val kind: PublishKind) :
    IllegalStateException("asset ${assetId.value} has no ${kind.name} publishing")

/** #114 (C25a) — the acknowledgement named no tombstone. */
class NoSuchDecommission(val id: String) : IllegalArgumentException("no decommission $id")

/** #114 (C25a) — the acknowledgement named a tombstone ServiceTag is still delivering. */
class DecommissionStillPending(val id: String) : IllegalStateException("decommission $id is still pending")

/**
 * #113/#114 (C25, C25a, C26, C27, C9a, F-1, N-2) — Enable and Change target, one write, no HTTP: the fresh read the
 * plan step made is re-validated and its provenance checked here, inside the transaction, and the binding is written
 * enabled at the next revision, validated, activated on [installationId], with its delivery reset (F-1): the desired
 * value is kept only from a binding enabled on this installation (I1), the attempts never (M5). A PENDING or
 * UNDELIVERABLE tombstone on the new helper is superseded (N-2); a date helper the stored, **enabled** binding let go
 * of is owed `1970-01-01` by a tombstone in the same write (R-1, I19).
 */
private class PublishActivation(
    private val bindings: PublishBindingRepository,
    private val deliveries: PublishDeliveryRepository,
    private val tombstones: DecommissionRepository,
    private val connections: HaConnectionRepository,
    private val seasonSync: SeasonSyncRepository,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(
        action: PublishAction,
        assetId: AssetId,
        kind: PublishKind,
        entityId: String,
        fresh: ValidationResult,
        boundary: ValidationResult?,
        installationId: String,
        expectedPlanId: String?,
    ): PublishBinding = uow.write {
        val now = clock.nowMillis()
        val connection = connections.get()
        val stored = bindings.forAsset(assetId).firstOrNull { it.kind == kind }
        if (expectedPlanId != null) {
            val planned = connection?.let {
                planIdOf(
                    assetId, kind, action, entityId, boundary?.target, it.id, it.configGeneration, stored?.revision ?: 0,
                    fresh.remote, boundary?.remote,
                )
            }
            if (planned != expectedPlanId) throw PublishRefused(PublishRefusal.STALE_PLAN, FIELD_PLAN_ID)
        }
        if (action == PublishAction.CHANGE_TARGET && stored == null) throw PublishNotSetUp(assetId, kind)
        if (!isValidEntityId(entityId)) refuse(PublishRefusal.BAD_ENTITY_ID)
        val asset = assets.get(assetId) ?: throw NoSuchAsset(assetId)
        if (!asset.maintainedHere(transfers.heldIds())) refuse(PublishRefusal.NOT_MAINTAINED_HERE)
        if (connection == null) refuse(PublishRefusal.NO_CONNECTION)
        if (domainRefusalOf(kind, entityId) != null) refuse(PublishRefusal.WRONG_DOMAIN)
        if (readsAsInbound(connection.id, entityId, stored)) refuse(PublishRefusal.INBOUND_SOURCE)
        bindings.byTarget(connection.id, entityId).firstOrNull { it.enabled && it.id != stored?.id }?.let {
            throw PublishRefused(PublishRefusal.TARGET_IN_USE, FIELD_ENTITY_ID, it.assetId)
        }
        val boundaryId = boundaryOf(asset, kind, entityId, boundary, connection.id)

        val revision = stored?.revision ?: 0
        if (stale(fresh, entityId, connection, revision, now)) refuse(PublishRefusal.STALE_READ)
        if (boundaryId != null && stale(checkNotNull(boundary), boundaryId, connection, revision, now)) {
            refuse(PublishRefusal.STALE_READ, FIELD_BOUNDARY_ENTITY_ID)
        }
        if (validateTarget(kind, entityId, fresh.remote) != null) refuse(shapeRefusal(kind))
        if (boundaryId != null && validateDateTarget(boundaryId, checkNotNull(boundary).remote) != null) {
            refuse(PublishRefusal.NOT_DATE_ONLY, FIELD_BOUNDARY_ENTITY_ID)
        }

        val next = PublishBinding(
            id = stored?.id ?: ids.newId(),
            assetId = assetId,
            connectionId = connection.id,
            kind = kind,
            entityId = entityId,
            enabled = true,
            revision = revision + 1,
            activatedInstallationId = installationId,
            validatedAt = now,
            validatedRevision = revision + 1,
            boundaryEntityId = boundaryId,
            createdAt = stored?.createdAt ?: now,
            updatedAt = now,
        )
        if (stored == null) bindings.insert(next) else check(bindings.update(next)) { "the binding moved inside its own write" }
        tombstones.byTarget(connection.id, entityId)?.let { tombstones.delete(it.id) }
        if (stored != null && stored.enabled && (stored.connectionId != next.connectionId || stored.entityId != entityId)) {
            oweInvalidation(stored, bindings, tombstones, ids, now)
        }
        val held = deliveries.get(next.id) ?: PublishDelivery(next.id, desiredValue = null, desiredRevision = 0, desiredAt = null)
        // I1, G23 "ServiceTag reports facts": the projector reads only enabled bindings (C31), so the held value is a
        // fact only while the stored binding was enabled on this installation; otherwise the projector computes it
        // again, and its compare-and-set still holds at the kept revision.
        val live = stored?.enabled == true && stored.activatedInstallationId == installationId
        val boundaryOn = boundaryId?.let { boundaryDateOf(checkNotNull(boundary).remote) }
        deliveries.upsert(
            PublishDelivery(
                bindingId = next.id,
                desiredValue = held.desiredValue.takeIf { live },
                desiredRevision = held.desiredRevision,
                desiredAt = held.desiredAt.takeIf { live },
                // M5, F-1: an attempt made to the old helper is never attributed to the new one.
                attemptsAtRevision = 0,
                lastAttemptAt = null,
                boundaryValue = boundaryOn?.toString(),
                boundaryReadAt = boundaryOn?.let { checkNotNull(boundary).readAt },
                boundaryBindingRevision = boundaryOn?.let { next.revision },
                lastRequestId = held.lastRequestId,
                lastRequestAt = held.lastRequestAt,
            ),
        )
        next
    }

    /**
     * C26 — whether an inbound #16 binding on this connection reads [entityId], or a date binding other than [stored]
     * reads it as its season-ended helper.
     */
    private suspend fun readsAsInbound(connectionId: String, entityId: String, stored: PublishBinding?): Boolean =
        seasonSync.all().any { it.connectionId == connectionId && it.entityId == entityId } ||
            bindings.all().any { it.id != stored?.id && it.connectionId == connectionId && it.boundaryEntityId == entityId }

    /**
     * C9a, D — the season-ended helper a date binding stores: required exactly when the asset is `MANUAL` with an
     * enabled #16 binding; never the target, never a helper an enabled binding claims or a PENDING tombstone names.
     * Every other asset, and the status kind, stores none.
     */
    private suspend fun boundaryOf(
        asset: Asset,
        kind: PublishKind,
        entityId: String,
        boundary: ValidationResult?,
        connectionId: String,
    ): String? {
        val required = when (kind) {
            PublishKind.EQUIPMENT_STATUS -> false
            PublishKind.SEASON_SERVICE_DATE -> when (asset.seasonMode) {
                SeasonMode.YEAR_ROUND, SeasonMode.CALENDAR -> false
                SeasonMode.MANUAL -> seasonSync.get(asset.id)?.enabled == true
            }
        }
        if (!required) return null
        val id = boundary?.target?.takeIf(::isValidEntityId)
            ?: refuse(PublishRefusal.BAD_ENTITY_ID, FIELD_BOUNDARY_ENTITY_ID)
        if (id == entityId) refuse(PublishRefusal.BOUNDARY_IS_TARGET, FIELD_BOUNDARY_ENTITY_ID)
        if (domainRefusalOf(PublishKind.SEASON_SERVICE_DATE, id) != null) {
            refuse(PublishRefusal.WRONG_DOMAIN, FIELD_BOUNDARY_ENTITY_ID)
        }
        val written = bindings.byTarget(connectionId, id).any { it.enabled } ||
            tombstones.byTarget(connectionId, id)?.state == DecommissionState.PENDING
        if (written) refuse(PublishRefusal.BOUNDARY_IS_TARGET, FIELD_BOUNDARY_ENTITY_ID)
        return id
    }
}

/**
 * A04/C25 — a read whose provenance does not hold: another helper, another connection or generation (any save since,
 * F), another binding revision (0 when none: another setup, a target change or a revalidation since), or older than
 * [VALIDATION_MAX_AGE_MILLIS] at [now].
 */
private fun stale(read: ValidationResult, target: String, connection: HaConnection, revision: Long, now: Long): Boolean =
    read.target != target ||
        read.connectionId != connection.id ||
        read.configGeneration != connection.configGeneration ||
        read.bindingRevision != revision ||
        read.readAt < now - VALIDATION_MAX_AGE_MILLIS

/** The shape refusal of [kind]'s validator once its domain holds. */
private fun shapeRefusal(kind: PublishKind): PublishRefusal = when (kind) {
    PublishKind.EQUIPMENT_STATUS -> PublishRefusal.OPTIONS_MISMATCH
    PublishKind.SEASON_SERVICE_DATE -> PublishRefusal.NOT_DATE_ONLY
}

private fun refuse(reason: PublishRefusal, field: String = FIELD_ENTITY_ID): Nothing = throw PublishRefused(reason, field)

/**
 * C34, R-1, I19, F-25 — [left] no longer writes its date helper: `1970-01-01` is owed to it by a PENDING tombstone
 * carrying the binding's installation id, written or merged in the caller's write — **skipped** while an enabled
 * binding claims that helper (its owner writes its own value). The status kind owes nothing (F-10).
 */
private suspend fun oweInvalidation(
    left: PublishBinding,
    bindings: PublishBindingRepository,
    tombstones: DecommissionRepository,
    ids: IdGenerator,
    now: Long,
) {
    when (left.kind) {
        PublishKind.EQUIPMENT_STATUS -> return
        PublishKind.SEASON_SERVICE_DATE -> Unit
    }
    if (bindings.byTarget(left.connectionId, left.entityId).any { it.enabled }) return
    tombstones.upsertMerging(
        tombstoneFor(left, DecommissionState.PENDING, ids, now),
    )
}

private fun tombstoneFor(left: PublishBinding, state: DecommissionState, ids: IdGenerator, now: Long) =
    DecommissionTombstone(
        id = ids.newId(),
        connectionId = left.connectionId,
        entityId = left.entityId,
        assetId = left.assetId,
        installationId = left.activatedInstallationId.orEmpty(),
        state = state,
        requestedAt = now,
        errorKind = null,
        errorAt = null,
        revision = 1,
    )

/**
 * #113/#114 (C25) — **Enable**: validates the plan step's fresh read of [entityId] (and, for a `MANUAL` asset whose
 * season Home Assistant drives, of its season-ended helper) and writes the binding enabled and activated on
 * [installationId] (C27). [expectedPlanId], the API's, must still equal [planIdOf] here, else `STALE_PLAN` before any
 * other refusal; the phone passes null. The projector then computes the first desired value (C31).
 */
class EnablePublishing(
    bindings: PublishBindingRepository,
    deliveries: PublishDeliveryRepository,
    tombstones: DecommissionRepository,
    connections: HaConnectionRepository,
    seasonSync: SeasonSyncRepository,
    assets: AssetRepository,
    transfers: TransferRecordRepository,
    uow: UnitOfWork,
    ids: IdGenerator,
    clock: Clock,
) {
    private val activation = PublishActivation(
        bindings, deliveries, tombstones, connections, seasonSync, assets, transfers, uow, ids, clock,
    )

    suspend fun run(
        assetId: AssetId,
        kind: PublishKind,
        entityId: String,
        fresh: ValidationResult,
        boundary: ValidationResult?,
        installationId: String,
        expectedPlanId: String? = null,
    ): PublishBinding =
        activation.run(PublishAction.ENABLE, assetId, kind, entityId, fresh, boundary, installationId, expectedPlanId)
}

/**
 * #113/#114 (C25) — **Change target**: Enable for [newEntityId], the only other command that activates (A08); the
 * date kind owes the old helper `1970-01-01` when the stored binding was enabled (R-1), the status kind lets it go
 * with its last value. An asset with no binding of [kind] is [PublishNotSetUp].
 */
class ChangePublishTarget(
    bindings: PublishBindingRepository,
    deliveries: PublishDeliveryRepository,
    tombstones: DecommissionRepository,
    connections: HaConnectionRepository,
    seasonSync: SeasonSyncRepository,
    assets: AssetRepository,
    transfers: TransferRecordRepository,
    uow: UnitOfWork,
    ids: IdGenerator,
    clock: Clock,
) {
    private val activation = PublishActivation(
        bindings, deliveries, tombstones, connections, seasonSync, assets, transfers, uow, ids, clock,
    )

    suspend fun run(
        assetId: AssetId,
        kind: PublishKind,
        newEntityId: String,
        fresh: ValidationResult,
        boundary: ValidationResult?,
        installationId: String,
        expectedPlanId: String? = null,
    ): PublishBinding = activation.run(
        PublishAction.CHANGE_TARGET, assetId, kind, newEntityId, fresh, boundary, installationId, expectedPlanId,
    )
}

/**
 * #113/#114 (C25, R-1) — **Disable**, locally and with no Home Assistant read: `enabled = false` at the next revision;
 * the date kind owes its helper `1970-01-01` by a tombstone in the same write, the status kind leaves the remote value
 * as it is (F-10). Disabling a disabled binding is a no-op: no revision, no tombstone. So is disabling an absent one,
 * which answers null (C35b).
 */
class DisablePublishing(
    private val bindings: PublishBindingRepository,
    private val tombstones: DecommissionRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId, kind: PublishKind): PublishBinding? = uow.write {
        val stored = bindings.forAsset(assetId).firstOrNull { it.kind == kind } ?: return@write null
        if (!stored.enabled) return@write stored
        val now = clock.nowMillis()
        val next = stored.copy(enabled = false, revision = stored.revision + 1, updatedAt = now)
        check(bindings.update(next)) { "the binding moved inside its own write" }
        oweInvalidation(stored, bindings, tombstones, ids, now)
        next
    }
}

/** What Check helper came to: the binding as stored after it, and the validator's kind when the helper failed it. */
data class Revalidated(val binding: PublishBinding, val refusedAs: PublishErrorKind?)

/**
 * #113/#114 (C25, N-2, A08) — **Check helper** on the **stored** destination: a read whose provenance holds and that
 * validates moves the revision and sets `validatedRevision`, clears the error with its pair and re-seeds the boundary
 * cache; the published, verified and outstanding facts stay (an outstanding attempt is still outstanding). A helper
 * that fails its validator records that kind at the current pair, the validation cleared. Never enables and never
 * writes `activatedInstallationId`: a copied row stays inert until Enable on this phone.
 */
class RevalidatePublishing(
    private val bindings: PublishBindingRepository,
    private val deliveries: PublishDeliveryRepository,
    private val connections: HaConnectionRepository,
    private val uow: UnitOfWork,
    private val clock: Clock,
) {
    suspend fun run(assetId: AssetId, kind: PublishKind, fresh: ValidationResult, boundary: ValidationResult?): Revalidated =
        uow.write {
            val now = clock.nowMillis()
            val connection = connections.get() ?: refuse(PublishRefusal.NO_CONNECTION)
            val stored = bindings.forAsset(assetId).firstOrNull { it.kind == kind } ?: throw PublishNotSetUp(assetId, kind)
            if (stored.connectionId != connection.id || stale(fresh, stored.entityId, connection, stored.revision, now)) {
                refuse(PublishRefusal.STALE_READ)
            }
            val boundaryId = stored.boundaryEntityId
            if (boundaryId != null &&
                (boundary == null || stale(boundary, boundaryId, connection, stored.revision, now))
            ) {
                refuse(PublishRefusal.STALE_READ, FIELD_BOUNDARY_ENTITY_ID)
            }
            val delivery = checkNotNull(deliveries.get(stored.id)) { "a binding without its delivery" }
            val failed = validateTarget(kind, stored.entityId, fresh.remote)
                ?: boundaryId?.let { validateDateTarget(it, checkNotNull(boundary).remote) }
            if (failed != null) {
                val record = DeliverySteps.failed(stored, delivery, failed, null, now, clearValidation = true)
                deliveries.record(record.bindingRevision, record.delivery)
                bindings.clearValidation(stored.id, stored.revision)
                return@write Revalidated(stored.copy(validatedRevision = null), failed)
            }
            val next = stored.copy(
                revision = stored.revision + 1,
                validatedAt = now,
                validatedRevision = stored.revision + 1,
                updatedAt = now,
            )
            check(bindings.update(next)) { "the binding moved inside its own write" }
            val boundaryOn = boundaryId?.let { boundaryDateOf(checkNotNull(boundary).remote) }
            deliveries.upsert(
                delivery.copy(
                    errorKind = null,
                    errorDetail = null,
                    errorAt = null,
                    errorBindingRevision = null,
                    errorDesiredRevision = null,
                    boundaryValue = boundaryOn?.toString(),
                    boundaryReadAt = boundaryOn?.let { checkNotNull(boundary).readAt },
                    boundaryBindingRevision = boundaryOn?.let { next.revision },
                ),
            )
            Revalidated(next, null)
        }
}

/**
 * #114 (C25a, O-21(b)) — "I cleared it in Home Assistant": deletes one UNDELIVERABLE tombstone by its id, the one
 * P114-38 is drawn on. An unknown id is [NoSuchDecommission]; a PENDING one, which ServiceTag is still delivering, is
 * [DecommissionStillPending]. Nothing is read from Home Assistant: the owner's word is the attestation.
 */
class DismissDecommission(
    private val tombstones: DecommissionRepository,
    private val uow: UnitOfWork,
) {
    suspend fun run(id: String) = uow.write {
        val held = tombstones.get(id) ?: throw NoSuchDecommission(id)
        when (held.state) {
            DecommissionState.PENDING -> throw DecommissionStillPending(id)
            DecommissionState.UNDELIVERABLE -> tombstones.delete(id)
        }
    }
}

/**
 * #113/#114 (C26, C28) — the publishing side as the four #16 commands see it, inside their own write. Production
 * passes this to every one of them; [OutboundTargets.NONE] is only for shipped constructions.
 */
class PublishOutboundTargets(
    private val bindings: PublishBindingRepository,
    private val deliveries: PublishDeliveryRepository,
    private val tombstones: DecommissionRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
) : OutboundTargets {
    override suspend fun writes(connectionId: String, entityId: String): Boolean =
        bindings.byTarget(connectionId, entityId).isNotEmpty() ||
            tombstones.byTarget(connectionId, entityId)?.state == DecommissionState.PENDING

    override suspend fun blocksAddressChange(connectionId: String): Boolean =
        bindings.all().any { it.connectionId == connectionId && it.kind == PublishKind.SEASON_SERVICE_DATE && it.enabled } ||
            tombstones.all().any { it.connectionId == connectionId && it.state == DecommissionState.PENDING }

    override suspend fun reauthorize(connectionId: String, addressChanged: Boolean) {
        val now = clock.nowMillis()
        for (binding in bindings.all().filter { it.connectionId == connectionId }) {
            val standing = !addressChanged && binding.validatedRevision == binding.revision
            val next = binding.copy(
                revision = binding.revision + 1,
                validatedRevision = if (standing) binding.revision + 1 else null,
                updatedAt = now,
            )
            check(bindings.update(next)) { "a binding moved inside the connection's write" }
        }
    }

    override suspend fun forget(connectionId: String) {
        val now = clock.nowMillis()
        for (t in tombstones.all().filter { it.connectionId == connectionId && it.state == DecommissionState.PENDING }) {
            check(tombstones.update(t.copy(state = DecommissionState.UNDELIVERABLE))) { "a tombstone moved inside the write" }
        }
        val dates = bindings.all().filter {
            it.connectionId == connectionId && it.enabled && it.kind == PublishKind.SEASON_SERVICE_DATE
        }
        for (binding in dates) {
            val delivery = deliveries.get(binding.id)
            val delivered = delivery != null &&
                delivery.desiredValue == INVALID_SERVICE_DATE &&
                DeliverySteps.isCurrent(binding, delivery)
            if (!delivered) tombstones.upsertMerging(tombstoneFor(binding, DecommissionState.UNDELIVERABLE, ids, now))
        }
    }
}
