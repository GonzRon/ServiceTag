package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.BuildConfig
import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.usecase.ArchiveAsset
import com.loosecannon.servicetag.core.usecase.ArchiveDefinition
import com.loosecannon.servicetag.core.usecase.ArchiveProfile
import com.loosecannon.servicetag.core.usecase.CreateAsset
import com.loosecannon.servicetag.core.usecase.DeleteEvent
import com.loosecannon.servicetag.core.usecase.ImportBackupMerge
import com.loosecannon.servicetag.core.usecase.LogEvent
import com.loosecannon.servicetag.core.usecase.MergePlanStale
import com.loosecannon.servicetag.core.usecase.MergeRefused
import com.loosecannon.servicetag.core.usecase.NoSuchAsset
import com.loosecannon.servicetag.core.usecase.RetireAsset
import com.loosecannon.servicetag.core.usecase.SaveDefinition
import com.loosecannon.servicetag.core.usecase.SaveProfile
import com.loosecannon.servicetag.core.usecase.UpdateAsset
import com.loosecannon.servicetag.core.usecase.UpdateEvent
import com.loosecannon.servicetag.di.AppGraph
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy

/**
 * One method per `/v1` endpoint, and every one of them goes through the use case the UI goes
 * through. Nothing here reads or writes a repository that a use case owns the rules for: the four
 * read-only list endpoints call `all()`/`forAsset()` exactly as the view models' flows do, and
 * every write is a `CreateAsset`, `UpdateAsset`, `RetireAsset`, `ArchiveAsset`, `SaveDefinition`,
 * `ArchiveDefinition`, `SaveProfile`, `ArchiveProfile`, `LogEvent`, `UpdateEvent`, `DeleteEvent` or
 * `ImportBackupMerge` call. There is deliberately no method for a wipe, a replace-import, an
 * export, an NFC write, an NFC bind or an attachment's bytes.
 *
 * **Nineteen collaborators plus two values, named one by one, with a `constructor(graph)` beside
 * them.** That is this app's pattern, stated at `AssetViewModels.kt:59`–`61`: *"Each takes the `AppGraph` members it
 * actually uses — the secondary constructor is what the Compose entry calls, the primary one is
 * what a test builds on a Room-backed fake graph."* It is the reason `ApiRouterTest` can drive the
 * entire API on the JVM over `FakeGraph`, with no Android class anywhere in this file.
 *
 * Every method is `suspend` and none of them catches anything: a domain refusal travels to
 * [ApiRouter], which is the one place a `Throwable` becomes a status code ([mapDomainFailure]).
 *
 * **Eleven handler methods share a name with the use-case property they call** — `createAsset`,
 * `updateAsset`, `retireAsset`, `archiveAsset`, `saveDefinition`, `archiveDefinition`,
 * `saveProfile`, `archiveProfile`, `logEvent`, `updateEvent`, `deleteEvent`. That is legal and
 * deliberate: Kotlin resolves a property and a function of the same name separately, so inside
 * `createAsset(request)` the expression `createAsset.run(…)` is the property, and at the router the
 * call reads `handlers.createAsset(request)`. The alternative — `handleCreateAsset` — would put a
 * word in eleven names to avoid a collision the compiler does not have.
 *
 * One honest qualification of the package's "no Android import" rule (Global Constraints): this
 * file imports `AppGraph`, whose own constructor takes a `Context`. The JVM suite is unaffected —
 * the secondary constructor is never invoked there, and the type resolves against the mockable
 * `android.jar` — but the `import android` grep in Task 6 Step 3 does not see that transitive
 * coupling, so it is written down here rather than credited to the grep.
 */
internal class ApiHandlers(
    private val assets: AssetRepository,
    private val tags: TagRepository,
    private val links: LinkRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val events: EventRepository,
    private val attachments: AttachmentRepository,
    private val createAsset: CreateAsset,
    private val updateAsset: UpdateAsset,
    private val retireAsset: RetireAsset,
    private val archiveAsset: ArchiveAsset,
    private val saveDefinition: SaveDefinition,
    private val archiveDefinition: ArchiveDefinition,
    private val saveProfile: SaveProfile,
    private val archiveProfile: ArchiveProfile,
    private val logEvent: LogEvent,
    private val updateEvent: UpdateEvent,
    private val deleteEvent: DeleteEvent,
    private val importBackupMerge: ImportBackupMerge,
    private val appVersion: String,
    private val schemaVersion: Int,
) {
    constructor(graph: AppGraph) : this(
        graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles, graph.events,
        graph.attachments,
        graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
        graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
        graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
        BuildConfig.VERSION_NAME, AppGraph.SCHEMA_VERSION,
    )

    // --- status ---------------------------------------------------------------------------

    suspend fun status(): ApiResponse = ok(
        StatusResponse.serializer(),
        StatusResponse(
            appVersion = appVersion,
            apiVersion = API_VERSION,
            schemaVersion = schemaVersion,
            backupFormatVersion = BackupCodec.FORMAT_VERSION,
            counts = mapOf(
                "assets" to assets.all().size,
                "tags" to tags.all().size,
                "links" to links.all().size,
                "definitions" to definitions.all().size,
                "profiles" to profiles.all().size,
                "events" to events.all().size,
                "attachments" to attachments.count(),
            ),
        ),
    )

    // --- assets ---------------------------------------------------------------------------

    suspend fun listAssets(): ApiResponse {
        val all = assets.all()
        val topLevel = all.filter { it.parentAssetId == null }.sortedBy { it.name.lowercase() }
        // `AssetTree.children` is the order the asset screen's COMPONENTS section already uses.
        val components = all
            .map { it.id }
            .associate { id -> id.value to AssetTree.children(all, id).map { it.toDto() } }
            .filterValues { it.isNotEmpty() }
        return ok(
            AssetListResponse.serializer(),
            AssetListResponse(topLevel.map { it.toDto() }, components),
        )
    }

    suspend fun getAsset(id: String): ApiResponse =
        ok(AssetResponse.serializer(), AssetResponse(asset(id).toDto()))

    suspend fun createAsset(request: ApiRequest): ApiResponse {
        val body = request.decode(AssetCommandRequest.serializer())
        val saved = createAsset.run(body.toCommand(), body.templateKey)
        return createdResponse(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    suspend fun createComponent(parentId: String, request: ApiRequest): ApiResponse {
        // The parent has to exist before the child is validated against it, so the 404 is ours and
        // not an `UnknownParent` validation problem about a path the caller can see is wrong.
        asset(parentId)
        val body = request.decode(AssetCommandRequest.serializer())
        val saved = createAsset.run(body.toCommand(parentOverride = parentId), body.templateKey)
        return createdResponse(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    suspend fun updateAsset(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(AssetCommandRequest.serializer())
        val saved = updateAsset.run(AssetId(id), body.toCommand())
        return ok(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    suspend fun retireAsset(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(RetireRequest.serializer())
        val saved = body.retiredOn
            ?.let { retireAsset.retire(AssetId(id), it) }
            ?: retireAsset.unretire(AssetId(id))
        return ok(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    suspend fun archiveAsset(id: String, request: ApiRequest): ApiResponse {
        val body = request.decode(ArchiveRequest.serializer())
        val saved = if (body.archived) archiveAsset.run(AssetId(id)) else archiveAsset.unarchive(AssetId(id))
        return ok(AssetResponse.serializer(), AssetResponse(saved.toDto()))
    }

    // --- definitions ----------------------------------------------------------------------

    suspend fun listDefinitions(assetId: String): ApiResponse {
        asset(assetId)
        return ok(
            DefinitionListResponse.serializer(),
            DefinitionListResponse(definitions.forAsset(AssetId(assetId)).map { it.toDto() }),
        )
    }

    suspend fun saveDefinition(request: ApiRequest): ApiResponse {
        val body = request.decode(SaveDefinitionRequest.serializer())
        val saved = saveDefinition.run(body.id?.let(::DefinitionId), body.toCommand())
        return ok(DefinitionResponse.serializer(), DefinitionResponse(saved.toDto()))
    }

    suspend fun archiveDefinition(id: String, request: ApiRequest): ApiResponse {
        archiveDefinition.run(DefinitionId(id), request.decode(ArchiveRequest.serializer()).archived)
        return ApiResponse.empty(204, "No Content")
    }

    // --- profiles -------------------------------------------------------------------------

    suspend fun listProfiles(assetId: String): ApiResponse {
        asset(assetId)
        return ok(
            ProfileListResponse.serializer(),
            ProfileListResponse(profiles.forAsset(AssetId(assetId)).map { it.toDto() }),
        )
    }

    suspend fun saveProfile(request: ApiRequest): ApiResponse {
        val body = request.decode(SaveProfileRequest.serializer())
        val saved = saveProfile.run(body.id?.let(::ProfileId), body.toCommand())
        return ok(ProfileResponse.serializer(), ProfileResponse(saved.toDto()))
    }

    suspend fun archiveProfile(id: String, request: ApiRequest): ApiResponse {
        archiveProfile.run(ProfileId(id), request.decode(ArchiveRequest.serializer()).archived)
        return ApiResponse.empty(204, "No Content")
    }

    // --- events ---------------------------------------------------------------------------

    suspend fun listEvents(assetId: String): ApiResponse {
        asset(assetId)
        return ok(
            EventListResponse.serializer(),
            EventListResponse(events.forAsset(AssetId(assetId)).map { it.toDto() }),
        )
    }

    suspend fun logEvent(request: ApiRequest): ApiResponse {
        val logged = logEvent.run(request.decode(EventRequest.serializer()).toCommand())
        return createdResponse(EventResponse.serializer(), EventResponse(logged.toDto()))
    }

    suspend fun updateEvent(id: String, request: ApiRequest): ApiResponse {
        val saved = updateEvent.run(EventId(id), request.decode(EventRequest.serializer()).toCommand())
        return ok(EventResponse.serializer(), EventResponse(saved.toDto()))
    }

    suspend fun deleteEvent(id: String): ApiResponse {
        // `DeleteEvent` is silent about a row that is not there; the API is not, because a client
        // that asked to delete something needs to know whether it existed.
        events.get(EventId(id)) ?: throw ApiFailure.notFound("event $id")
        deleteEvent.run(EventId(id))
        return ApiResponse.empty(204, "No Content")
    }

    // --- tag bindings, read only ----------------------------------------------------------

    suspend fun listTagBindings(): ApiResponse =
        ok(TagListResponse.serializer(), TagListResponse(tags.all().map { it.toDto() }))

    // --- the additive import, planned before it writes --------------------------------------

    /** Decides and returns the plan. Writes nothing, ever, whatever the plan says. */
    suspend fun importMergePlan(request: ApiRequest): ApiResponse {
        requireZip(request)
        return ok(MergeReportResponse.serializer(), importBackupMerge.plan(request.body).toResponse())
    }

    /**
     * Applies, and only when the plan is conflict-free.
     *
     * A refusal is a **409 carrying the same body a 200 would** — the full report, with the
     * deterministic conflict list — because "what stopped you?" is the only question a client has
     * at that point, and making them call `plan` again to find out would be a second round trip for
     * an answer the refusal already holds. [MergePlanStale] answers with the *fresh* plan's report,
     * which is what a client would retry against.
     */
    suspend fun importMergeApply(request: ApiRequest): ApiResponse {
        requireZip(request)
        return try {
            ok(MergeReportResponse.serializer(), importBackupMerge.run(request.body).toResponse())
        } catch (e: MergeRefused) {
            conflict(MergeReportResponse.serializer(), e.report.toResponse())
        } catch (e: MergePlanStale) {
            conflict(MergeReportResponse.serializer(), e.report.toResponse())
        }
    }

    // --- plumbing -------------------------------------------------------------------------

    /** The asset, or `NoSuchAsset` — which [mapDomainFailure] turns into a 404. */
    private suspend fun asset(id: String) =
        assets.get(AssetId(id)) ?: throw NoSuchAsset(AssetId(id))

    private fun <T> ok(serializer: SerializationStrategy<T>, value: T): ApiResponse =
        ApiResponse.json(200, "OK", ApiJson.encodeToString(serializer, value))

    /** Named `createdResponse`, not `created`, so it cannot be misread as a local `val`. */
    private fun <T> createdResponse(serializer: SerializationStrategy<T>, value: T): ApiResponse =
        ApiResponse.json(201, "Created", ApiJson.encodeToString(serializer, value))

    private fun <T> conflict(serializer: SerializationStrategy<T>, value: T): ApiResponse =
        ApiResponse.json(409, "Conflict", ApiJson.encodeToString(serializer, value))

    private fun requireZip(request: ApiRequest) {
        val type = request.mediaType()
        if (type != ZIP_MEDIA_TYPE) throw ApiFailure.unsupportedMediaType(ZIP_MEDIA_TYPE, type)
    }

    /**
     * The body as JSON, or a 415 for the wrong type and a 400 for the wrong shape.
     *
     * The decoder's own message is passed through, because with `ignoreUnknownKeys = false` that
     * message is what names the misspelled field — the difference between a caller fixing a typo
     * and a caller guessing. What it echoes is the caller's own body, back to the caller, over this
     * phone's loopback address; nothing about the phone's data is in it.
     */
    private fun <T> ApiRequest.decode(serializer: DeserializationStrategy<T>): T {
        val type = mediaType()
        if (type != JSON_MEDIA_TYPE) throw ApiFailure.unsupportedMediaType(JSON_MEDIA_TYPE, type)
        return try {
            ApiJson.decodeFromString(serializer, body.decodeToString())
        } catch (e: SerializationException) {
            throw ApiFailure.badRequest(e.message ?: "that is not the JSON this endpoint wants")
        }
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"
        const val ZIP_MEDIA_TYPE = "application/zip"
    }
}
