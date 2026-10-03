package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.condition.ConditionHistory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.seasonsync.HaConnectionRepository
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncRepository
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.currentSeasonSyncState
import com.loosecannon.servicetag.core.usecase.ArchiveHealthSubject
import com.loosecannon.servicetag.core.usecase.GetAssetSeason
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.NoSuchHealthSubject
import com.loosecannon.servicetag.core.usecase.RecordCondition
import com.loosecannon.servicetag.core.usecase.RecordSeasonActivation
import com.loosecannon.servicetag.core.usecase.SaveHealthSubject
import com.loosecannon.servicetag.core.usecase.SetHealthPolicy
import com.loosecannon.servicetag.core.usecase.SetMaintenanceBreak
import com.loosecannon.servicetag.core.usecase.SetSeasonMode
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.health.AssetHealthReadModel
import com.loosecannon.servicetag.ui.maintenance.AttentionReadModel

/**
 * One method per 1.4 `/v1` endpoint (spec §9.1; master plan §11.1) — the season, the maintenance
 * break, manual activations, condition, health and its subjects, and the attention list — reached
 * from the router as `handlers.seasonHealth.*`.
 *
 * The rule is 1.2's: **every write calls exactly one use case** — `SetSeasonMode`,
 * `SetMaintenanceBreak`, `RecordSeasonActivation`, `SetHealthPolicy`, `RecordCondition`,
 * `SaveHealthSubject` or `ArchiveHealthSubject` — and **every read reads a read model or a
 * repository**: `GetAssetSeason`, `ConditionHistory` over the condition rows, `AssetHealthReadModel`,
 * `AttentionReadModel`, the subject rows. Nothing here re-checks a rule a use case owns, and nothing
 * here upserts a row. A write answers with what it wrote and, where the spec says so, a fresh read
 * beside it (the season after a season write, the current condition after a condition).
 *
 * **Nothing destructive is here, by construction** (spec §9.2, inv. 127): no method amends or
 * deletes a condition or an activation, deletes a health subject or writes a health value, because
 * no use case that could is a collaborator of this class.
 *
 * #16 adds one read, the asset's Home Assistant season sync (C24), over the binding and connection repositories and
 * the token store's `has`, and writes nothing. Its season writes answer `SEASON_SYNC_ENABLED` from their use cases'
 * guard while a binding is enabled; nothing here re-checks it.
 *
 * A read that names an asset answers the shipped 404 `no_such_asset` when it is not there, before
 * any read model is asked: the health and season views of a vanished asset are empty rather than
 * errors, which is right on a screen and wrong on a wire.
 */
internal class SeasonHealthHandlers(
    private val assets: AssetRepository,
    private val activations: SeasonActivationRepository,
    private val conditions: ConditionRepository,
    private val healthSubjects: HealthSubjectRepository,
    private val setSeasonMode: SetSeasonMode,
    private val setMaintenanceBreak: SetMaintenanceBreak,
    private val recordSeasonActivation: RecordSeasonActivation,
    private val getAssetSeason: GetAssetSeason,
    private val recordCondition: RecordCondition,
    private val saveHealthSubject: SaveHealthSubject,
    private val archiveHealthSubject: ArchiveHealthSubject,
    private val setHealthPolicy: SetHealthPolicy,
    private val health: AssetHealthReadModel,
    private val attention: AttentionReadModel,
    private val seasonSyncBindings: SeasonSyncRepository,
    private val haConnections: HaConnectionRepository,
    private val secrets: SecretStore,
    private val transfers: TransferRecordRepository,
    private val backgroundAllowed: () -> Boolean,
) {
    constructor(graph: AppGraph) : this(
        graph.assets, graph.seasonActivations, graph.conditions, graph.healthSubjects,
        graph.setSeasonMode, graph.setMaintenanceBreak, graph.recordSeasonActivation, graph.getAssetSeason,
        graph.recordCondition, graph.saveHealthSubject, graph.archiveHealthSubject, graph.setHealthPolicy,
        graph.assetHealthReadModel, graph.attentionReadModel,
        graph.seasonSyncBindings, graph.haConnections, graph.secretStore, graph.transferRecords,
        graph.seasonSyncBackgroundAllowed,
    )

    // --- the season ---------------------------------------------------------------------------

    suspend fun getSeason(assetId: String): ApiResponse =
        ok(SeasonResponse.serializer(), getAssetSeason.run(AssetId(assetId)).toResponse())

    /**
     * One manual START or END. Answers 201 with the activation and the season it now makes. The
     * body's 422s come before the stored state's 409s (`RecordSeasonActivation`'s order).
     */
    suspend fun recordActivation(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ActivationRequest.serializer())
        val row = recordSeasonActivation.run(AssetId(assetId), body.toCommand())
        return createdResponse(
            ActivationResponse.serializer(),
            ActivationResponse(row.toDto(), getAssetSeason.run(AssetId(assetId)).toResponse()),
        )
    }

    suspend fun setSeasonMode(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(SeasonModeRequest.serializer())
        val saved = setSeasonMode.run(AssetId(assetId), body.toCommand())
        return ok(
            AssetSeasonResponse.serializer(),
            AssetSeasonResponse(saved.toDto(), getAssetSeason.run(AssetId(assetId)).toResponse()),
        )
    }

    suspend fun setMaintenanceBreak(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(BreakRequest.serializer())
        val saved = setMaintenanceBreak.run(AssetId(assetId), body.toCommand())
        return ok(
            AssetSeasonResponse.serializer(),
            AssetSeasonResponse(saved.toDto(), getAssetSeason.run(AssetId(assetId)).toResponse()),
        )
    }

    // --- season sync (#16) ---------------------------------------------------------------------

    /**
     * #16 (C3, C24; R16-9) — the twenty-eighth `/v1/assets/{id}/…` sub-resource, read only: the asset's binding, its
     * state derived now by the shared derivation (C17), and the connection's non-secret settings. No binding is a 200
     * with `binding: null`. The token is asked about through [SecretStore.has] alone and never read; the
     * connection's address and home Wi-Fi name stay in the connection. A key store that cannot load throws out of
     * `has` and answers the shipped 500 `internal`, never a guessed state (C18). Nothing here writes.
     */
    suspend fun getSeasonSync(assetId: String): ApiResponse {
        asset(assetId)
        val stored = haConnections.get()
        val connection = if (stored == null) {
            SeasonSyncConnectionDto.NOT_CONFIGURED
        } else {
            stored.toSeasonSyncDto(secrets.has(stored.id), backgroundAllowed())
        }
        val binding = seasonSyncBindings.get(AssetId(assetId))?.let { found ->
            found.toDto(currentSeasonSyncState(found, secrets, assets, transfers))
        }
        return ok(SeasonSyncResponse.serializer(), SeasonSyncResponse(assetId, connection, binding))
    }

    // --- condition ----------------------------------------------------------------------------

    /** The asset's whole condition history, oldest first by the ordering key, and its current row. */
    suspend fun listConditions(assetId: String): ApiResponse {
        asset(assetId)
        val history = ConditionHistory.of(conditions.forAsset(AssetId(assetId)))
        return ok(
            ConditionsResponse.serializer(),
            ConditionsResponse(history.ordered.map { it.toDto() }, history.current?.toDto()),
        )
    }

    /**
     * The only route that writes a condition row (inv. 81): one immutable fact, and the asset's
     * current condition after it — read, not assumed, because a backdated row is not current.
     */
    suspend fun recordCondition(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ConditionRequest.serializer())
        val row = recordCondition.run(AssetId(assetId), body.toCommand())
        val current = ConditionHistory.of(conditions.forAsset(AssetId(assetId))).current
        return createdResponse(ConditionResponse.serializer(), ConditionResponse(row.toDto(), current?.toDto()))
    }

    // --- health -------------------------------------------------------------------------------

    /** The asset's health for today, computed at read time; nothing is written (inv. 105, 111). */
    suspend fun getHealth(assetId: String): ApiResponse {
        asset(assetId)
        return ok(HealthResponse.serializer(), health.forAsset(AssetId(assetId)).toResponse())
    }

    suspend fun setHealthPolicy(assetId: String, request: ApiRequest): ApiResponse {
        val body = request.decode(HealthPolicyRequest.serializer())
        val saved = setHealthPolicy.run(AssetId(assetId), body.toCommand())
        return ok(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    /** Every subject of the asset, archived included, in its own order `(sortOrder, id)`. */
    suspend fun listSubjects(assetId: String): ApiResponse {
        asset(assetId)
        val rows = healthSubjects.forAsset(AssetId(assetId)).sortedWith(compareBy({ it.sortOrder }, { it.id.value }))
        return ok(SubjectListResponse.serializer(), SubjectListResponse(rows.map { it.toDto() }))
    }

    suspend fun getSubject(id: String): ApiResponse =
        ok(SubjectResponse.serializer(), SubjectResponse(subject(id).toDto()))

    suspend fun createSubject(request: ApiRequest): ApiResponse {
        val body = request.decode(HealthSubjectCreateRequest.serializer())
        val saved = saveHealthSubject.create(AssetId(body.assetId), body.toCommand())
        return createdResponse(SubjectResponse.serializer(), SubjectResponse(saved.toDto()))
    }

    /** A full replace of everything but the asset, which the command cannot name (inv. 120). */
    suspend fun updateSubject(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(HealthSubjectUpdateRequest.serializer())
        val saved = saveHealthSubject.update(HealthSubjectId(id), body.toCommand())
        return ok(SubjectResponse.serializer(), SubjectResponse(saved.toDto()))
    }

    /** Archive or restore — the only way a subject leaves; nothing deletes one. */
    suspend fun archiveSubject(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ArchiveRequest.serializer())
        val saved = archiveHealthSubject.run(HealthSubjectId(id), body.archived)
        return ok(SubjectResponse.serializer(), SubjectResponse(saved.toDto()))
    }

    // --- attention ----------------------------------------------------------------------------

    suspend fun listAttention(): ApiResponse =
        ok(AttentionResponse.serializer(), AttentionResponse(attention.items().map { it.toDto() }))

    // --- the three counts `/v1/status` gained -------------------------------------------------

    /** Under the archive's own list names, as `assetReferences` is. */
    suspend fun counts(): Map<String, Int> = mapOf(
        "seasonActivations" to activations.all().size,
        "assetConditions" to conditions.all().size,
        "healthSubjects" to healthSubjects.all().size,
    )

    // --- plumbing -----------------------------------------------------------------------------

    private suspend fun asset(id: String) = assets.get(AssetId(id)) ?: throw NoSuchAsset(AssetId(id))

    private suspend fun subject(id: String) =
        healthSubjects.get(HealthSubjectId(id)) ?: throw NoSuchHealthSubject(HealthSubjectId(id))
}
