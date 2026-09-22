package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Logs a new [AssetEvent] against an asset, through the one shared validation path
 * ([resolveOwnedProfile] then [buildEvent]). Ownership — the asset exists; the command's profile
 * and every valued definition belong to it — is checked before field validation; nothing is
 * stored if either check fails. One `uow.write`.
 *
 * 1.2: **every** event write rebuilds the derived state of the schedules that event can affect,
 * inside the same transaction. An ordinary journal entry is not a completion, but it may still
 * carry a meter reading, and a meter reading moves a threshold — so the closure is "this Asset's
 * schedules, and every group schedule that requires it", which is the one question
 * [RecomputeSchedules.forAsset] answers.
 */
class LogEvent(
    private val events: EventRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val assets: AssetRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val recompute: RecomputeSchedules,
) {
    suspend fun run(cmd: EventCommand): AssetEvent = uow.write {
        assets.get(cmd.assetId) ?: throw NoSuchAsset(cmd.assetId)
        val profile = resolveOwnedProfile(cmd, definitions, profiles)
        val event = buildEvent(cmd, definitions, profile, existing = null, ids, clock.nowMillis())
        events.upsert(event)
        recompute.forAsset(cmd.assetId)
        event
    }
}
