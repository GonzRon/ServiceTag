package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.maintainedHere
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.core.usecase.SetSeasonMode

/**
 * #16 (C16) — why a link, a resume or an entity edit (C17) was refused before anything was written: a code the phone
 * maps to one sentence (§5), never a sentence itself.
 */
enum class SeasonSyncLinkRefusal {
    /** The asset is archived, retired or held by an open transfer (P16-36). */
    NOT_MAINTAINED_HERE,

    /** No connection is saved on this phone (P16-10). */
    NO_CONNECTION,

    /** The connection's token is not on this phone (P16-11). */
    NEEDS_TOKEN,

    /** The entity id fails C4's rule, [isValidEntityId] (P16-49). */
    BAD_ENTITY_ID,

    /** The asset already has its one binding (R16-10). The phone never draws the action for it, so no sentence. */
    ALREADY_LINKED,
}

/** #16 (C16) — a link or a resume refused for [reason]; nothing was written. The message is the code only. */
class SeasonSyncLinkRefused(val reason: SeasonSyncLinkRefusal) :
    IllegalStateException("season sync link refused: $reason")

/** #16 (C17) — a command named an asset that has no binding. The phone draws no such command. */
class SeasonSyncNotLinked(val assetId: AssetId) :
    IllegalStateException("asset ${assetId.value} has no season sync binding")

/**
 * #16 (C16, C17) — what a link or a resume wrote: the [binding], and the season mode the asset was switched out of
 * into MANUAL, or null when it was MANUAL already. After a switch out of [SeasonMode.YEAR_ROUND] the phone asks #78's
 * question about the asset's live CONTINUOUS schedules (C27); this answer is what tells it to.
 */
data class SeasonSyncLinked(val binding: SeasonSyncBinding, val switchedFrom: SeasonMode?)

/**
 * #16 (C16; R16-1, R16-8, R16-10, R16-Q-G) — links an asset to one Home Assistant on/off entity, in one write,
 * refusals first: [SeasonSyncLinkRefusal.NOT_MAINTAINED_HERE], [SeasonSyncLinkRefusal.NO_CONNECTION],
 * [SeasonSyncLinkRefusal.NEEDS_TOKEN], [SeasonSyncLinkRefusal.BAD_ENTITY_ID], [SeasonSyncLinkRefusal.ALREADY_LINKED].
 *
 * Then **the setup reconciliation.** A MANUAL asset keeps its season and history: no season row. A CALENDAR or
 * YEAR_ROUND asset is switched into MANUAL through [SetSeasonMode]'s own rules and write, at the phase it has today,
 * so its season does not change on the day it is linked; the switch row is dated today, as every switch into MANUAL
 * is, so IN_SERVICE schedules count from today (limit 14). A PRE_SERVICE strands refusal from those rules stops the
 * link with nothing written. The switch runs before the binding exists, so it is called unguarded, as the applier's
 * body is.
 *
 * The binding is FOLLOW, enabled, revision 1, with no status yet. After the commit the work is ensured and a fresh
 * read is asked for. Nothing here reads Home Assistant or writes a row from an answer.
 */
class LinkSeasonSync(
    private val assets: AssetRepository,
    private val activations: SeasonActivationRepository,
    private val transfers: TransferRecordRepository,
    private val bindings: SeasonSyncRepository,
    private val connections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val setSeasonMode: SetSeasonMode,
    private val scheduler: SeasonSyncScheduler,
    private val uow: UnitOfWork,
    private val clock: Clock,
    private val today: Today,
) {
    suspend fun run(assetId: AssetId, entityId: String): SeasonSyncLinked {
        val linked = uow.write {
            val (asset, connection) = readyInTransaction(assetId)
            if (!isValidEntityId(entityId)) throw SeasonSyncLinkRefused(SeasonSyncLinkRefusal.BAD_ENTITY_ID)
            if (bindings.get(assetId) != null) throw SeasonSyncLinkRefused(SeasonSyncLinkRefusal.ALREADY_LINKED)
            val switchedFrom = intoManualInTransaction(asset, guarded = false)
            val now = clock.nowMillis()
            val binding = SeasonSyncBinding(
                assetId = assetId,
                connectionId = connection.id,
                entityId = entityId,
                mode = SyncMode.FOLLOW,
                enabled = true,
                revision = 1,
                observedState = null,
                observedChangedAt = null,
                lastSuccessAt = null,
                lastAttemptAt = null,
                errorKind = null,
                errorDetail = null,
                errorAt = null,
                appliedAction = null,
                appliedOn = null,
                appliedAt = null,
                createdAt = now,
                updatedAt = now,
            )
            bindings.insert(binding)
            SeasonSyncLinked(binding, switchedFrom)
        }
        scheduler.ensure()
        scheduler.requestFreshRead(assetId)
        return linked
    }

    /**
     * C16's first three refusals, shared with the resume (R16-19), inside the caller's write: the asset maintained
     * here, a connection saved, its token on this phone. Answers the asset as read and the connection.
     */
    internal suspend fun readyInTransaction(assetId: AssetId): Pair<Asset, HaConnection> {
        val asset = assets.get(assetId) ?: throw NoSuchAsset(assetId)
        if (!asset.maintainedHere(transfers.heldIds())) {
            throw SeasonSyncLinkRefused(SeasonSyncLinkRefusal.NOT_MAINTAINED_HERE)
        }
        val connection = connections.get() ?: throw SeasonSyncLinkRefused(SeasonSyncLinkRefusal.NO_CONNECTION)
        if (!secrets.has(connection.id)) throw SeasonSyncLinkRefused(SeasonSyncLinkRefusal.NEEDS_TOKEN)
        return asset to connection
    }

    /**
     * The reconciliation, inside the caller's write: a CALENDAR or YEAR_ROUND [asset] goes into MANUAL at today's
     * phase through [SetSeasonMode.setInTransaction], its one switch row dated today; a MANUAL one is left alone.
     * Answers the mode it left, or null. Those rules' strands refusal propagates, and the caller's write rolls back.
     * [guarded] is false for the link, whose binding does not exist yet, and true for the resume, which calls this
     * before it enables its binding.
     */
    internal suspend fun intoManualInTransaction(asset: Asset, guarded: Boolean): SeasonMode? =
        when (asset.seasonMode) {
            SeasonMode.MANUAL -> null
            SeasonMode.CALENDAR, SeasonMode.YEAR_ROUND -> {
                val phase = SeasonContext.of(asset.seasonInputs(activations.forAsset(asset.id)))
                    .phaseAt(today.localDate())
                setSeasonMode.setInTransaction(
                    asset.id,
                    SeasonModeCommand(SeasonMode.MANUAL, manualPhase = phase),
                    guarded,
                )
                asset.seasonMode
            }
        }
}
