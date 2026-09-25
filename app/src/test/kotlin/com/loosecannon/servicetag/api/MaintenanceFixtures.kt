package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.references.LinkLaunchPolicy
import com.loosecannon.servicetag.core.usecase.AddReference
import com.loosecannon.servicetag.core.usecase.UpdateReference
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.ui.maintenance.DueReadModel
import kotlinx.serialization.KSerializer
import java.io.File

/**
 * The 1.2 handlers over a [FakeGraph], built in one place because four suites need them.
 *
 * `ApiHandlers` grew exactly one collaborator in 1.2 — this one — so the four existing call sites
 * each gained one line rather than fifteen, and a later collaborator lands here instead of in all
 * of them.
 *
 * The due projection is B08's own class over the fake graph's real tables, with **no snooze**:
 * `schedule_local_delivery` is device-local and `/v1/due` does not carry a snooze field, so the
 * seam is satisfied with the honest constant rather than a reader this brief does not own.
 */
internal fun maintenanceHandlersFor(graph: FakeGraph): MaintenanceHandlers = MaintenanceHandlers(
    groups = graph.groups,
    schedules = graph.schedules,
    closures = graph.closures,
    assets = graph.assets,
    saveGroup = graph.saveGroup,
    archiveGroup = graph.archiveGroup,
    saveSchedule = graph.saveSchedule,
    pauseSchedule = graph.pauseSchedule,
    archiveSchedule = graph.archiveSchedule,
    postponeSchedule = graph.postponeSchedule,
    completeSchedule = graph.completeSchedule,
    completeGroupMembers = graph.completeGroupMembers,
    closeRound = graph.closeRound,
    due = DueReadModel(
        graph.schedules, graph.assets, graph.groups, graph.definitions,
        graph.recomputeSchedules, graph.todayPort, graph.assetHealthReadModel,
        snoozedUntilOf = { null },
    ),
    recompute = graph.recomputeSchedules,
    today = graph.todayPort,
)

/**
 * The 1.3 reference handlers over a [FakeGraph], here for the reason above: `ApiHandlers` grew one
 * collaborator in 1.3 too, and eight call sites each gained one line rather than four.
 *
 * The two use cases are built here rather than read off the graph because [FakeGraph] exposes the
 * reference **repository** and not the use cases over it; the production wiring in `AppGraph`
 * builds exactly these three objects from exactly these members, and `ReferenceHandlers`' own
 * `constructor(graph)` is what the app uses.
 */
internal fun referenceHandlersFor(graph: FakeGraph): ReferenceHandlers {
    // One instance answering both save and launch, as the production graph does (spec §4.2).
    val policy = LinkLaunchPolicy()
    return ReferenceHandlers(
        references = graph.references,
        assets = graph.assets,
        addReference = AddReference(
            graph.references, graph.assets, policy, graph.uow, graph.ids, graph.clock,
        ),
        updateReference = UpdateReference(graph.references, graph.uow, graph.clock),
    )
}

/**
 * The 1.4 handlers over a [FakeGraph], for the reason above: `ApiHandlers` grew one collaborator in
 * 1.4 too, and every call site gains this one line. Every member is read off the fake graph, which
 * mirrors `AppGraph`'s fields by name (master plan §1), so this is the production wiring exactly.
 */
internal fun seasonHealthHandlersFor(graph: FakeGraph): SeasonHealthHandlers = SeasonHealthHandlers(
    assets = graph.assets,
    activations = graph.seasonActivations,
    conditions = graph.conditions,
    healthSubjects = graph.healthSubjects,
    setSeasonMode = graph.setSeasonMode,
    setMaintenanceBreak = graph.setMaintenanceBreak,
    recordSeasonActivation = graph.recordSeasonActivation,
    getAssetSeason = graph.getAssetSeason,
    recordCondition = graph.recordCondition,
    saveHealthSubject = graph.saveHealthSubject,
    archiveHealthSubject = graph.archiveHealthSubject,
    setHealthPolicy = graph.setHealthPolicy,
    health = graph.assetHealthReadModel,
    attention = graph.attentionReadModel,
)

/**
 * 1.4 (B09): one `/v1` client over a [FakeGraph] for the new suites — the production router, the
 * production handlers (every collaborator above) and the production serializers, exactly as
 * `ApiRouterTest` builds them, written once rather than six times.
 */
internal class V1Client(val graph: FakeGraph, private val token: String = "ABCD2345") {

    fun router(): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
            graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
            maintenanceHandlersFor(graph),
            referenceHandlersFor(graph),
            seasonHealthHandlersFor(graph),
            appVersion = "1.4.0",
            schemaVersion = AppGraph.SCHEMA_VERSION,
        ),
        token,
    )

    fun call(method: String, path: String, body: String = "", contentType: String = "application/json"): ApiResponse {
        val headers = buildMap {
            put("host", "127.0.0.1")
            put("authorization", "Bearer $token")
            if (body.isNotEmpty()) put("content-type", contentType)
        }
        return router().handle(ApiRequest(method, path, headers, body.toByteArray()))
    }

    /** [call], then the 2xx body decoded — failing with the whole answer when the status is not [status]. */
    fun <T> ok(serializer: KSerializer<T>, method: String, path: String, body: String = "", status: Int = 200): T {
        val response = call(method, path, body)
        check(response.status == status) { "$method $path answered ${response.status}: ${response.bodyText()}" }
        return ApiJson.decodeFromString(serializer, response.bodyText())
    }

    /** One asset through the API, returned as its id. */
    fun asset(name: String): String =
        ok(AssetResponse.serializer(), "POST", "/v1/assets", """{"name":"$name"}""", status = 201).asset.id
}

internal fun ApiResponse.bodyText(): String = body.decodeToString()

/** The error envelope of a refusal. */
internal fun ApiResponse.errorDetail(): ApiErrorDetail =
    ApiJson.decodeFromString(ApiErrorBody.serializer(), bodyText()).error

/** A file of the repository, found by walking up from the test's working directory. */
internal fun repoFile(path: String): File {
    var dir = File(".").absoluteFile
    while (!File(dir, "settings.gradle.kts").isFile) {
        dir = dir.parentFile ?: error("cannot find the repository root from ${File(".").absolutePath}")
    }
    return File(dir, path)
}
