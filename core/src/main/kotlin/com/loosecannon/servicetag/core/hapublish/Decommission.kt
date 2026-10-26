package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.ports.IdGenerator

/**
 * #114 (C34, R-1, I19, F-25) — [left] no longer writes its date helper: `1970-01-01` is owed to it by a PENDING
 * tombstone carrying the binding's installation id, written or merged in the caller's write — **skipped** while an
 * enabled binding claims that helper (its owner writes its own value). The status kind owes nothing (F-10).
 *
 * The one place a tombstone is owed: Disable and Change target (C25) after the binding's own write, and the projector's
 * retirement of a binding whose asset row is gone (C31) after the delete. The caller decides that [left] was enabled.
 */
suspend fun oweInvalidation(
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
    tombstones.upsertMerging(tombstoneFor(left, DecommissionState.PENDING, ids, now))
}

/** The obligation [left] leaves, in [state], at its installation (F-25). */
internal fun tombstoneFor(left: PublishBinding, state: DecommissionState, ids: IdGenerator, now: Long) =
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

/** #114 (C34) — what a drain does with one tombstone before any I/O. */
sealed interface TombstonePlan {
    /** An UNDELIVERABLE row waits for the owner's "I cleared it in Home Assistant" (P114-38): nothing is sent. */
    data object Wait : TombstonePlan

    /** Nothing is sent: [record] marks the row UNDELIVERABLE (another installation's, no connection or no token). */
    data class GiveUp(val record: DecommissionTombstone) : TombstonePlan

    /** Read the helper first. */
    data object ReadFirst : TombstonePlan
}

/** #114 (C34) — what a tombstone's read, or its POST and read-back, came to. */
sealed interface TombstoneStep {
    /** The helper holds `1970-01-01`: the row is deleted **by its id**, never by its pair. */
    data object Cleared : TombstoneStep

    /**
     * Write [record]'s state and error by the row's revision compare-and-set, and stop. [detail] is the live answer's
     * (the row keeps none), for its retry class.
     */
    data class Record(val record: DecommissionTombstone, val detail: String? = null) : TombstoneStep

    /** The helper holds something else: POST `1970-01-01` to it, then read it back. */
    data object Post : TombstoneStep
}

/**
 * #114 (C34, the tombstone table) — the decommission state machine, pure. A drain is C17 with `desired = 1970-01-01`:
 * a read equal to it (with or without our POST) clears the row; a `NEEDS_OWNER` kind, a missing connection or token,
 * or another installation's row is UNDELIVERABLE (drawn with P114-37 until the owner dismisses it); a `TRANSIENT` or
 * `WAIT` kind stays PENDING with its error (P114-36). The retry class is read from the **live** answer's detail (an
 * `HTTP_ERROR`'s status), never from the stored row, which keeps no detail. No step ever drops an obligation silently.
 */
object DecommissionSteps {

    /**
     * The gate: an UNDELIVERABLE row waits; a PENDING row another installation activated is UNDELIVERABLE as
     * `COPIED_INSTALLATION`, never drained (B3's M3: it would otherwise block an address change and never leave); a
     * row whose connection is not the stored one ([connectionId], null when none) or has no token ([hasToken]) is
     * UNDELIVERABLE as `NEEDS_TOKEN`; otherwise the helper is read first.
     */
    fun plan(
        tombstone: DecommissionTombstone,
        installationId: String,
        connectionId: String?,
        hasToken: Boolean,
        now: Long,
    ): TombstonePlan = when (tombstone.state) {
        DecommissionState.UNDELIVERABLE -> TombstonePlan.Wait
        DecommissionState.PENDING -> if (tombstone.installationId != installationId) {
            TombstonePlan.GiveUp(undeliverable(tombstone, PublishErrorKind.COPIED_INSTALLATION, now))
        } else if (tombstone.connectionId != connectionId || !hasToken) {
            TombstonePlan.GiveUp(undeliverable(tombstone, PublishErrorKind.NEEDS_TOKEN, now))
        } else {
            TombstonePlan.ReadFirst
        }
    }

    /** After the helper's read: equal → cleared; a validator failure or a failed read → [failed]; else POST. */
    fun afterRead(tombstone: DecommissionTombstone, read: RemoteRead, now: Long): TombstoneStep {
        val remote = when (read) {
            is RemoteRead.Failed -> return TombstoneStep.Record(failed(tombstone, read.kind, read.detail, now), read.detail)
            is RemoteRead.Answered -> read.remote
        }
        validateDateTarget(tombstone.entityId, remote)?.let {
            return TombstoneStep.Record(failed(tombstone, it, null, now))
        }
        return if (remoteMatches(PublishKind.SEASON_SERVICE_DATE, remote, INVALID_SERVICE_DATE)) {
            TombstoneStep.Cleared
        } else {
            TombstoneStep.Post
        }
    }

    /**
     * After a POST Home Assistant accepted, from its read-back: equal → cleared; differs → `REMOTE_DIFFERS`; a validator
     * failure → that kind; a failed read-back → `OUTCOME_UNKNOWN` (the next drain reads first).
     */
    fun afterPostAccepted(tombstone: DecommissionTombstone, readBack: RemoteRead, now: Long): TombstoneStep {
        val remote = when (readBack) {
            is RemoteRead.Failed -> return TombstoneStep.Record(
                failed(tombstone, PublishErrorKind.OUTCOME_UNKNOWN, readBack.detail, now), readBack.detail,
            )
            is RemoteRead.Answered -> readBack.remote
        }
        validateDateTarget(tombstone.entityId, remote)?.let {
            return TombstoneStep.Record(failed(tombstone, it, null, now))
        }
        return if (remoteMatches(PublishKind.SEASON_SERVICE_DATE, remote, INVALID_SERVICE_DATE)) {
            TombstoneStep.Cleared
        } else {
            TombstoneStep.Record(failed(tombstone, PublishErrorKind.REMOTE_DIFFERS, null, now))
        }
    }

    /** After a POST known not to have acted (C23): [kind] by its live [detail]'s class. */
    fun afterPostRefused(tombstone: DecommissionTombstone, kind: PublishErrorKind, detail: String?, now: Long): TombstoneStep =
        TombstoneStep.Record(failed(tombstone, kind, detail, now), detail)

    /** After a POST that may have acted (C23): `OUTCOME_UNKNOWN`, still PENDING; the next drain reads first. */
    fun afterPostUnknown(tombstone: DecommissionTombstone, detail: String?, now: Long): TombstoneStep =
        TombstoneStep.Record(failed(tombstone, PublishErrorKind.OUTCOME_UNKNOWN, detail, now), detail)

    /**
     * [kind] recorded on the row: UNDELIVERABLE for a `NEEDS_OWNER` class, else PENDING with the error. [detail] is the
     * live answer's (B1's carry), read for the class only.
     */
    fun failed(tombstone: DecommissionTombstone, kind: PublishErrorKind, detail: String?, now: Long): DecommissionTombstone =
        when (retryClassOf(kind, detail)) {
            RetryClass.TRANSIENT, RetryClass.WAIT -> tombstone.copy(errorKind = kind, errorAt = now)
            RetryClass.NEEDS_OWNER -> undeliverable(tombstone, kind, now)
        }

    private fun undeliverable(tombstone: DecommissionTombstone, kind: PublishErrorKind, now: Long) =
        tombstone.copy(state = DecommissionState.UNDELIVERABLE, errorKind = kind, errorAt = now)
}
