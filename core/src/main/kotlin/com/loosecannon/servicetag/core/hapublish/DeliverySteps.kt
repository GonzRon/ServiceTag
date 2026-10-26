package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.maintainedHere

/**
 * #113 (C17, R-2) — what started a pass. [CHANGE]: an eligibility change — the projector's write, a control commit,
 * `requestDelivery`. [SCHEDULED]: the midnight tick and the one-time worker, which are not eligibility changes.
 * [RECONCILE]: start, resume, the periodic reconcile and Publish now, which always read the helper.
 */
enum class DeliveryTrigger { CHANGE, SCHEDULED, RECONCILE }

/**
 * #113/#114 (C17) — one snapshot, read in one transaction: the binding and its delivery, whether the connection and
 * its token exist, this installation's id, and the asset row with the held ids for the per-send eligibility (A02).
 */
data class Snap(
    val binding: PublishBinding,
    val delivery: PublishDelivery,
    val connectionPresent: Boolean,
    val hasToken: Boolean,
    val installationId: String,
    val asset: Asset?,
    val held: Set<AssetId>,
)

/** #113/#114 (C17) — what a pass does with one binding before any I/O. */
sealed interface DeliveryPlan {
    /** Nothing is sent. [reproject] asks for a projector pass first (a date binding whose asset left). */
    data class Skip(val state: PublishState, val reproject: Boolean = false) : DeliveryPlan

    /** The helper was verified at the current pair and nothing is outstanding. */
    data object Done : DeliveryPlan

    /**
     * Read the helper before anything else. [reconcile]: record `reconcilePending` first
     * ([DeliverySteps.reconcileWriteAhead]); for a date binding whose boundary is Home Assistant's, the boundary
     * helper is read in the same pass (C9a).
     */
    data class ReadFirst(val value: String, val reconcile: Boolean) : DeliveryPlan
}

/**
 * #113/#114 (C17) — one record for the store: [delivery] as it should read, written only while the stored
 * `(binding revision, desired revision)` still equals [bindingRevision] and [desiredRevision] (the snapshot's).
 * [clearValidation] also clears the binding's `validatedRevision` in the same write.
 */
data class DeliveryRecord(
    val bindingRevision: Long,
    val desiredRevision: Long,
    val delivery: PublishDelivery,
    val clearValidation: Boolean = false,
)

/** #113/#114 (C17) — what a completed read leads to. */
sealed interface ReadStep {
    /** Write [record] and stop: the helper was verified, or the read failed. */
    data class Record(val record: DeliveryRecord) : ReadStep

    /** The helper differs: write [writeAhead] first (a failed write abandons the attempt), then POST [value]. */
    data class Post(val writeAhead: DeliveryRecord, val action: HaServiceAction, val value: String) : ReadStep
}

/** A record that held: the binding and the delivery as stored after it. */
data class Committed(val binding: PublishBinding, val delivery: PublishDelivery)

/**
 * #113/#114 (C17) — the delivery state machine, pure: a plan from one snapshot, and after each I/O step the record
 * to write. Coalescing is structural: only the latest desired value exists to send.
 */
object DeliverySteps {

    /**
     * F-1 — the helper is known to hold the desired value: verified equal at the binding's current
     * `(revision, desiredRevision)`, with nothing outstanding.
     */
    fun isCurrent(binding: PublishBinding, delivery: PublishDelivery): Boolean =
        delivery.lastVerifiedValue == delivery.desiredValue &&
            delivery.verifiedBindingRevision == binding.revision &&
            delivery.verifiedDesiredRevision == delivery.desiredRevision &&
            !delivery.needsDelivery &&
            !delivery.reconcilePending &&
            delivery.outcomeUnknownSince == null

    /**
     * R-2 — a pass leaves this binding alone because its **current** error (A04) asks for it: a `NEEDS_OWNER` error
     * except in reconcile mode, and a `WAIT` error at a scheduled pass.
     */
    fun errorSkips(binding: PublishBinding, delivery: PublishDelivery, trigger: DeliveryTrigger): Boolean {
        val kind = delivery.errorKind ?: return false
        if (!delivery.errorIsCurrent(binding)) return false
        return when (retryClassOf(kind, delivery.errorDetail)) {
            RetryClass.TRANSIENT -> false
            RetryClass.WAIT -> when (trigger) {
                DeliveryTrigger.CHANGE, DeliveryTrigger.RECONCILE -> false
                DeliveryTrigger.SCHEDULED -> true
            }
            RetryClass.NEEDS_OWNER -> when (trigger) {
                DeliveryTrigger.CHANGE, DeliveryTrigger.SCHEDULED -> true
                DeliveryTrigger.RECONCILE -> false
            }
        }
    }

    /**
     * `drain()`'s selection: an enabled binding with outstanding work — a verified pair that is not current,
     * `needsDelivery`, an unknown outcome or a pending reconcile (A05, C) — less R-2's skips; reconcile mode selects
     * every enabled binding.
     */
    fun selects(binding: PublishBinding, delivery: PublishDelivery, trigger: DeliveryTrigger): Boolean {
        if (!binding.enabled || errorSkips(binding, delivery, trigger)) return false
        return when (trigger) {
            DeliveryTrigger.RECONCILE -> true
            DeliveryTrigger.CHANGE, DeliveryTrigger.SCHEDULED -> !isCurrent(binding, delivery)
        }
    }

    /**
     * The gate, then the plan. Gate: not enabled, another installation's activation, no connection or token, no
     * standing validation, then the per-send eligibility (A02): an asset missing or not maintained here sends
     * nothing for the status kind and only `1970-01-01` for the date kind. A null desired value sends nothing.
     * Then: `ReadFirst` unless the delivery [isCurrent] and this is not a reconcile; `Done` otherwise.
     */
    fun plan(snap: Snap, trigger: DeliveryTrigger): DeliveryPlan {
        val binding = snap.binding
        val delivery = snap.delivery
        if (!binding.enabled) return DeliveryPlan.Skip(PublishState.NOT_ENABLED)
        if (binding.activatedInstallationId != snap.installationId) {
            return DeliveryPlan.Skip(PublishState.COPIED_INSTALLATION)
        }
        if (!snap.connectionPresent || !snap.hasToken) return DeliveryPlan.Skip(PublishState.NEEDS_TOKEN)
        if (binding.validatedRevision != binding.revision) return DeliveryPlan.Skip(PublishState.NEEDS_VALIDATION)

        val eligible = snap.asset?.maintainedHere(snap.held) == true
        val value = delivery.desiredValue
        when (binding.kind) {
            PublishKind.EQUIPMENT_STATUS -> {
                if (!eligible) return DeliveryPlan.Skip(PublishState.NOT_MAINTAINED_HERE)
                if (value == null) return DeliveryPlan.Skip(PublishState.WAITING_FOR_SOURCE)
            }
            PublishKind.SEASON_SERVICE_DATE -> {
                // A stale date for an asset that left waits for the projector, which writes 1970-01-01.
                if (value == null || (!eligible && value != INVALID_SERVICE_DATE)) {
                    return DeliveryPlan.Skip(PublishState.PENDING, reproject = true)
                }
            }
        }
        if (errorSkips(binding, delivery, trigger)) return DeliveryPlan.Skip(PublishState.ERROR)

        val reconcile = when (trigger) {
            DeliveryTrigger.RECONCILE -> true
            DeliveryTrigger.CHANGE, DeliveryTrigger.SCHEDULED -> delivery.reconcilePending
        }
        return if (reconcile || !isCurrent(binding, delivery)) DeliveryPlan.ReadFirst(value, reconcile) else DeliveryPlan.Done
    }

    /**
     * C14, C34 — the binding's [PublishState] for display and the API, derived from [snap] and never stored: the gate's
     * state when it would send nothing; then a current error (A04) — `OUTCOME_UNKNOWN` for that kind, else `ERROR`; an
     * outstanding unknown outcome; then, verified at the current pair with nothing outstanding ([isCurrent]),
     * `INVALIDATED_INELIGIBLE` for a date binding whose asset is no longer maintained here (`1970-01-01` **read back**:
     * the quiet state comes only after the read), `PUBLISHED` when our POST at this pair made it so, `MATCHES` when Home
     * Assistant already held it; otherwise `PENDING`.
     */
    fun stateOf(snap: Snap): PublishState {
        // Reconcile mode skips for no error, so only the gate's own states come back as a Skip.
        when (val gate = plan(snap, DeliveryTrigger.RECONCILE)) {
            is DeliveryPlan.Skip -> return gate.state
            DeliveryPlan.Done, is DeliveryPlan.ReadFirst -> Unit
        }
        val binding = snap.binding
        val delivery = snap.delivery
        val error = delivery.errorKind?.takeIf { delivery.errorIsCurrent(binding) }
        if (error != null) {
            return if (error == PublishErrorKind.OUTCOME_UNKNOWN) PublishState.OUTCOME_UNKNOWN else PublishState.ERROR
        }
        if (delivery.outcomeUnknownSince != null) return PublishState.OUTCOME_UNKNOWN
        if (!isCurrent(binding, delivery)) return PublishState.PENDING
        val eligible = snap.asset?.maintainedHere(snap.held) == true
        val quiet = when (binding.kind) {
            PublishKind.EQUIPMENT_STATUS -> false
            PublishKind.SEASON_SERVICE_DATE -> !eligible && delivery.desiredValue == INVALID_SERVICE_DATE
        }
        if (quiet) return PublishState.INVALIDATED_INELIGIBLE
        val ours = delivery.lastPublishedValue == delivery.desiredValue &&
            delivery.publishedBindingRevision == binding.revision &&
            delivery.publishedDesiredRevision == delivery.desiredRevision
        return if (ours) PublishState.PUBLISHED else PublishState.MATCHES
    }

    /** C — reconcile mode's write-ahead, before its GET(s): `reconcilePending := true`. */
    fun reconcileWriteAhead(binding: PublishBinding, delivery: PublishDelivery): DeliveryRecord =
        DeliveryRecord(binding.revision, delivery.desiredRevision, delivery.copy(reconcilePending = true))

    /**
     * After the helper's read. [delivery] is as this pass last recorded it, and [value] the plan's
     * ([DeliveryPlan.ReadFirst.value]). Equal → verified, not published; differs → the write-ahead (A05:
     * `needsDelivery`, `outcomeUnknownSince`, `lastAttemptAt`, one more attempt, the contradicting read, and the old
     * error cleared — the attempt supersedes it and its own outcome records the next) and a POST; a validator failure
     * → that kind and the validation cleared; a failed read → its kind at the pair. A completed read clears
     * `reconcilePending` in the same write, unless [boundaryPending] says the boundary helper's read (C9a) has not
     * completed.
     */
    fun afterRead(
        binding: PublishBinding,
        delivery: PublishDelivery,
        value: String,
        read: RemoteRead,
        now: Long,
        boundaryPending: Boolean = false,
    ): ReadStep {
        val remote = when (read) {
            is RemoteRead.Failed -> return ReadStep.Record(failed(binding, delivery, read.kind, read.detail, now))
            is RemoteRead.Answered -> read.remote
        }
        val seen = delivery.copy(remoteValue = remote.state, remoteReadAt = now)
        validateTarget(binding.kind, binding.entityId, remote)?.let { kind ->
            return ReadStep.Record(failed(binding, seen, kind, null, now, clearValidation = true))
        }
        val stillPending = if (boundaryPending) delivery.reconcilePending else false
        if (remoteMatches(binding.kind, remote, value)) {
            val verified = seen.verifiedAs(binding, value, now).copy(reconcilePending = stillPending)
            return ReadStep.Record(recordOf(binding, verified))
        }
        val writeAhead = seen.copy(
            needsDelivery = true,
            outcomeUnknownSince = now,
            lastAttemptAt = now,
            attemptsAtRevision = delivery.attemptsAtRevision + 1,
            reconcilePending = stillPending,
            errorKind = null,
            errorDetail = null,
            errorAt = null,
            errorBindingRevision = null,
            errorDesiredRevision = null,
        )
        return ReadStep.Post(recordOf(binding, writeAhead), actionOf(binding.kind), value)
    }

    /**
     * After a POST of [value] ([ReadStep.Post.value]) that HA accepted (200), from the write-ahead [delivery] and the
     * read-back: equal → published and verified; differs → `REMOTE_DIFFERS`; a validator failure → that kind and the
     * validation cleared; a failed read-back → `OUTCOME_UNKNOWN`.
     */
    fun afterPostAccepted(
        binding: PublishBinding,
        delivery: PublishDelivery,
        value: String,
        readBack: RemoteRead,
        now: Long,
    ): DeliveryRecord {
        val remote = when (readBack) {
            // The POST may have acted: the next attempt reads first (outcomeUnknownSince kept from the write-ahead).
            is RemoteRead.Failed ->
                return failed(binding, delivery, PublishErrorKind.OUTCOME_UNKNOWN, readBack.detail, now)
            is RemoteRead.Answered -> readBack.remote
        }
        // HA answered the POST, so its outcome is known; the read-back says what it came to.
        val seen = delivery.copy(remoteValue = remote.state, remoteReadAt = now, outcomeUnknownSince = null)
        validateTarget(binding.kind, binding.entityId, remote)?.let { kind ->
            return failed(binding, seen, kind, null, now, clearValidation = true)
        }
        if (!remoteMatches(binding.kind, remote, value)) {
            return failed(binding, seen, PublishErrorKind.REMOTE_DIFFERS, null, now)
        }
        val published = seen.verifiedAs(binding, value, now).copy(
            lastPublishedValue = value,
            lastPublishedAt = now,
            publishedBindingRevision = binding.revision,
            publishedDesiredRevision = delivery.desiredRevision,
        )
        return recordOf(binding, published)
    }

    /** After a POST known not to have acted (C23): [kind] at the pair; the outcome is known, the delivery still owed. */
    fun afterPostRefused(
        binding: PublishBinding,
        delivery: PublishDelivery,
        kind: PublishErrorKind,
        detail: String?,
        now: Long,
    ): DeliveryRecord = failed(binding, delivery.copy(outcomeUnknownSince = null), kind, detail, now)

    /** After a POST that may have acted (C23): `OUTCOME_UNKNOWN` at the pair; the next attempt reads first. */
    fun afterPostUnknown(
        binding: PublishBinding,
        delivery: PublishDelivery,
        detail: String?,
        now: Long,
    ): DeliveryRecord = failed(binding, delivery, PublishErrorKind.OUTCOME_UNKNOWN, detail, now)

    /**
     * The compare-and-set every record is written under: a moved generation or a newer desired value drops [record]
     * entirely (null). The desired value, the boundary cache and the request stamp are never a record's to write.
     */
    fun commit(binding: PublishBinding, delivery: PublishDelivery, record: DeliveryRecord): Committed? {
        if (binding.revision != record.bindingRevision || delivery.desiredRevision != record.desiredRevision) return null
        val written = record.delivery.copy(
            bindingId = delivery.bindingId,
            desiredValue = delivery.desiredValue,
            desiredRevision = delivery.desiredRevision,
            desiredAt = delivery.desiredAt,
            boundaryValue = delivery.boundaryValue,
            boundaryReadAt = delivery.boundaryReadAt,
            boundaryBindingRevision = delivery.boundaryBindingRevision,
            lastRequestId = delivery.lastRequestId,
            lastRequestAt = delivery.lastRequestAt,
        )
        val stored = if (record.clearValidation) binding.copy(validatedRevision = null) else binding
        return Committed(stored, written)
    }

    private fun recordOf(binding: PublishBinding, delivery: PublishDelivery, clearValidation: Boolean = false) =
        DeliveryRecord(binding.revision, delivery.desiredRevision, delivery, clearValidation)

    /** Verified equal at the current pair: nothing is owed, and any error is over. */
    private fun PublishDelivery.verifiedAs(binding: PublishBinding, value: String, now: Long) = copy(
        lastVerifiedValue = value,
        lastVerifiedAt = now,
        verifiedBindingRevision = binding.revision,
        verifiedDesiredRevision = desiredRevision,
        needsDelivery = false,
        outcomeUnknownSince = null,
        errorKind = null,
        errorDetail = null,
        errorAt = null,
        errorBindingRevision = null,
        errorDesiredRevision = null,
    )

    /**
     * A04 — [kind] recorded **with its pair**; everything else as [delivery] holds it, and [clearValidation] clears the
     * binding's `validatedRevision` in the same write. Also the boundary helper's validator failure
     * (`clearValidation = true`).
     */
    fun failed(
        binding: PublishBinding,
        delivery: PublishDelivery,
        kind: PublishErrorKind,
        detail: String?,
        now: Long,
        clearValidation: Boolean = false,
    ): DeliveryRecord = recordOf(
        binding,
        delivery.copy(
            errorKind = kind,
            errorDetail = detail,
            errorAt = now,
            errorBindingRevision = binding.revision,
            errorDesiredRevision = delivery.desiredRevision,
        ),
        clearValidation,
    )
}
