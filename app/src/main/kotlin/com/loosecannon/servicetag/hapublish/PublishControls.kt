package com.loosecannon.servicetag.hapublish

import android.util.Log
import com.loosecannon.servicetag.core.hapublish.ChangePublishTarget
import com.loosecannon.servicetag.core.hapublish.DeliveryPlan
import com.loosecannon.servicetag.core.hapublish.DeliverySteps
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger
import com.loosecannon.servicetag.core.hapublish.DisablePublishing
import com.loosecannon.servicetag.core.hapublish.DismissDecommission
import com.loosecannon.servicetag.core.hapublish.EnablePublishing
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishBindingRepository
import com.loosecannon.servicetag.core.hapublish.PublishDeliveryRepository
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.hapublish.RevalidatePublishing
import com.loosecannon.servicetag.core.hapublish.Revalidated
import com.loosecannon.servicetag.core.hapublish.Snap
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.ForgetHaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SaveHaConnection
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * #113/#114 (C35b) — what Publish now accepted: its [requestId] and [acceptedAt], stamped on each **covered** binding
 * ([bindings], in the asset's order) — enabled and past C17's gate, so a pass will read it back. Empty: nothing was
 * stamped and nothing launched.
 */
data class PublishRequest(val requestId: String, val acceptedAt: Long, val bindings: List<PublishBinding>)

/**
 * #113/#114 (A03, C32, C35b) — **the publishing controls, one facade**: each runs its core command, and after the
 * commit asks for delivery ([requester], the dispatch), so no view model or route can run a bare command and forget,
 * and no delivery waits for an unrelated trigger (I21). A refusal commits nothing and asks for nothing. Enable and
 * Change target activate on this installation ([installationId], C27) and pass the API's `expectedPlanId` through
 * (C25a); the phone passes none.
 *
 * [requestPublishNow] (the API's Publish now) stamps the covered bindings in one write and launches a projection
 * ([reconciler], I18: so a time-only move is never sent stale) and then [PublishRunner.publishNow] on [scope], **never
 * awaited** (I35): the stamp moves no revision, desired value or error, and nothing observes it (I28).
 */
class PublishControls(
    private val enablePublishing: EnablePublishing,
    private val changePublishTarget: ChangePublishTarget,
    private val disablePublishing: DisablePublishing,
    private val revalidatePublishing: RevalidatePublishing,
    private val dismissDecommission: DismissDecommission,
    private val saveHaConnection: SaveHaConnection,
    private val forgetHaConnection: ForgetHaConnection,
    private val bindings: PublishBindingRepository,
    private val deliveries: PublishDeliveryRepository,
    private val connections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val assets: AssetRepository,
    private val transfers: TransferRecordRepository,
    private val installationId: () -> String,
    private val runner: PublishRunner,
    private val reconciler: DesiredStateReconciler,
    private val today: Today,
    private val requester: DeliveryRequester,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    suspend fun enable(
        assetId: AssetId,
        kind: PublishKind,
        entityId: String,
        fresh: ValidationResult,
        boundary: ValidationResult?,
        expectedPlanId: String? = null,
    ): PublishBinding = enablePublishing.run(assetId, kind, entityId, fresh, boundary, installationId(), expectedPlanId)
        .also { requester.requestDelivery() }

    suspend fun changeTarget(
        assetId: AssetId,
        kind: PublishKind,
        newEntityId: String,
        fresh: ValidationResult,
        boundary: ValidationResult?,
        expectedPlanId: String? = null,
    ): PublishBinding =
        changePublishTarget.run(assetId, kind, newEntityId, fresh, boundary, installationId(), expectedPlanId)
            .also { requester.requestDelivery() }

    /** Check helper: never activates (A08); a helper that fails its validator is recorded, and still delivered to. */
    suspend fun revalidate(
        assetId: AssetId,
        kind: PublishKind,
        fresh: ValidationResult,
        boundary: ValidationResult?,
    ): Revalidated = revalidatePublishing.run(assetId, kind, fresh, boundary).also { requester.requestDelivery() }

    suspend fun disable(assetId: AssetId, kind: PublishKind): PublishBinding? =
        disablePublishing.run(assetId, kind).also { requester.requestDelivery() }

    suspend fun dismissDecommission(id: String) {
        dismissDecommission.run(id)
        requester.requestDelivery()
    }

    /** #16's Save (token, network, cadence): every publish binding re-authorised (C28), then delivered to (F-2). */
    suspend fun saveConnection(
        baseUrl: String,
        token: Secret?,
        cadence: SyncCadence?,
        networkEligibility: NetworkEligibility,
        homeNetworkSsid: String?,
        backgroundChecks: BackgroundChecks?,
    ): HaConnection = saveHaConnection.run(baseUrl, token, cadence, networkEligibility, homeNetworkSsid, backgroundChecks)
        .also { requester.requestDelivery() }

    /**
     * #16's Forget, the one way the phone disconnects (C28, C34, O-11): first the runner's [PublishRunner.drainBeforeForget]
     * — one attempt to deliver and read back `1970-01-01` for each enabled date binding and each PENDING tombstone of
     * the connection, in the foreground and bounded by the client's timeouts — then the command, which in its own write
     * turns every obligation not read back into an UNDELIVERABLE tombstone, drawn until the owner dismisses it; then the
     * works' rule is applied again (no connection: both works cancelled). Answers what the drain came to.
     */
    suspend fun forgetConnection(): ForgetDrain {
        val drained = runner.drainBeforeForget()
        forgetHaConnection.run()
        requester.requestDelivery()
        return drained
    }

    /**
     * C35b — Publish now for [assetId]: in one write, the bindings a reconcile pass would read back (enabled, activated
     * here, a connection and a token that reads — the runner's own test, so an undecryptable one is never stamped — a
     * standing validation, the asset eligible, the date kind's pending projection included) take one new request id and
     * time; then a projection (I18) and the runner's reconcile pass for the asset are launched, and this returns at
     * once. With no covered binding nothing is stamped or launched.
     */
    suspend fun requestPublishNow(assetId: AssetId): PublishRequest {
        val requestId = ids.newId()
        val at = clock.nowMillis()
        val here = installationId()
        val covered = uow.write {
            val connection = connections.get()
            val hasToken = connection != null && secrets.get(connection.id) != null
            val asset = assets.get(assetId)
            val held = transfers.heldIds()
            bindings.forAsset(assetId).filter { binding ->
                val delivery = deliveries.get(binding.id) ?: return@filter false
                val present = connection != null && connection.id == binding.connectionId
                val snap = Snap(binding, delivery, present, hasToken, here, asset, held)
                covers(DeliverySteps.plan(snap, DeliveryTrigger.RECONCILE))
            }.also { if (it.isNotEmpty()) deliveries.stampRequest(it.map(PublishBinding::id), requestId, at) }
        }
        if (covered.isNotEmpty()) {
            scope.launch {
                try {
                    reconciler.reconcileDesired(today.localDate())
                    runner.publishNow(assetId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    log(PublishWorkerBody.COULD_NOT_RUN)
                }
            }
        }
        return PublishRequest(requestId, at, covered)
    }

    private companion object {
        const val TAG = "HaPublish"

        /**
         * Past C17's gate in reconcile mode: a read is due, or the date kind's projection the pass runs first. A
         * reconcile is `Done` only for a quiet ineligible date binding, which a pass neither reads nor writes (C34,
         * P114-39), so it is not covered.
         */
        fun covers(plan: DeliveryPlan): Boolean = when (plan) {
            is DeliveryPlan.ReadFirst -> true
            DeliveryPlan.Done -> false
            is DeliveryPlan.Skip -> when (plan.state) {
                PublishState.PENDING -> plan.reproject
                PublishState.NOT_ENABLED,
                PublishState.COPIED_INSTALLATION,
                PublishState.NEEDS_VALIDATION,
                PublishState.NEEDS_TOKEN,
                PublishState.WAITING_FOR_SOURCE,
                PublishState.NOT_MAINTAINED_HERE,
                PublishState.OUTCOME_UNKNOWN,
                PublishState.ERROR,
                PublishState.PUBLISHED,
                PublishState.MATCHES,
                PublishState.INVALIDATED_INELIGIBLE,
                -> false
            }
        }
    }
}
