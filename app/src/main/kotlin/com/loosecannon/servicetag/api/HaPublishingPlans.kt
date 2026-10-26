package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.PublishAction
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.VALIDATION_MAX_AGE_MILLIS
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.hapublish.Validation

/**
 * #113/#114 (C35b, C25a, I34) — the plans the Developer API answered and has not yet seen committed, **in memory on
 * the handler set**: closing the Developer API screen drops every one, and nothing here is ever persisted. At most
 * [CAPACITY] are held, the oldest evicted first; each is the plan step's own validation keyed by its `planId`, and
 * expires once its target read is [VALIDATION_MAX_AGE_MILLIS] old (the answer's `expiresAt`, C25's bound). The action
 * that commits one removes it, so a plan is used once.
 */
internal class HaPublishingPlans(private val clock: Clock) {

    /** What a plan was made for, and the validation the action commits. */
    data class Plan(
        val assetId: AssetId,
        val kind: PublishKind,
        val action: PublishAction,
        val entityId: String,
        val validated: Validation.Validated,
    ) {
        val expiresAt: Long get() = validated.target.readAt + VALIDATION_MAX_AGE_MILLIS
    }

    private val held = LinkedHashMap<String, Plan>()

    /** Holds [plan] under [planId]: a plan made again moves to the newest place; past [CAPACITY] the oldest goes. */
    @Synchronized
    fun put(planId: String, plan: Plan) {
        val now = clock.nowMillis()
        held.entries.removeAll { now >= it.value.expiresAt }
        held.remove(planId)
        held[planId] = plan
        while (held.size > CAPACITY) held.remove(held.keys.first())
    }

    /**
     * The plan held under [planId] for exactly this asset, kind and action and not yet expired; null otherwise — the
     * action's 409 `STALE_PLAN`. A foreign or expired plan is not removed by asking.
     */
    @Synchronized
    fun find(planId: String, assetId: AssetId, kind: PublishKind, action: PublishAction): Plan? =
        held[planId]?.takeIf {
            it.assetId == assetId && it.kind == kind && it.action == action && clock.nowMillis() < it.expiresAt
        }

    /** How many plans are held now. */
    @Synchronized
    fun count(): Int = held.size

    /** The plan [planId] was committed: it is never committed again. */
    @Synchronized
    fun remove(planId: String) {
        held.remove(planId)
    }

    companion object {
        const val CAPACITY = 16
    }
}
