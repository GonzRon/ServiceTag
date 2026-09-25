package com.loosecannon.servicetag.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Every request body's ceiling but two: 64 KiB, which is a large asset form and a huge event. */
internal const val MAX_BODY_BYTES: Int = 64 * 1024

/** The two endpoints that take an archive instead of a form. */
internal const val IMPORT_MERGE_PLAN_PATH: String = "/v1/import-merge/plan"
internal const val IMPORT_MERGE_APPLY_PATH: String = "/v1/import-merge/apply"

/** Their ceiling: 4 MiB, which is a data archive of a very full phone and no attachment bytes. */
internal const val MAX_IMPORT_BYTES: Int = 4 * 1024 * 1024

/**
 * Authenticates, matches, and turns whatever comes back — an answer or a refusal — into bytes.
 *
 * **The token is checked first, before anything else at all.** A caller without it cannot learn
 * whether a path exists, whether its body parsed, or how long the answer would have been: the reply
 * is 401 with a zero-byte body, every time, for every path. The body has already been read off the
 * socket by then, which is why the ceilings live in [parseRequest] below this class and apply to an
 * unauthenticated caller too.
 *
 * **[handle] is blocking, and that is its contract.** Its caller is [LoopbackApiServer]'s one worker
 * thread, which has nothing else to do until there are bytes to write; everything below it is
 * `suspend`, because everything in `:core` is. `runBlocking(Dispatchers.IO)` is where the two meet —
 * the same dispatcher `BackupViewModel` puts its archive work on (`BackupViewModel.kt:134`) — and it
 * is the only `runBlocking` in `app/src/main`.
 */
internal class ApiRouter(
    private val handlers: ApiHandlers,
    private val token: String,
) {
    /** Asked by the parser with the path, before a body byte is read. */
    fun bodyCapFor(path: String): Int =
        if (path == IMPORT_MERGE_PLAN_PATH || path == IMPORT_MERGE_APPLY_PATH) {
            MAX_IMPORT_BYTES
        } else {
            MAX_BODY_BYTES
        }

    fun handle(request: ApiRequest): ApiResponse {
        if (!tokenMatches(token, request.bearerToken())) {
            return ApiResponse.empty(401, "Unauthorized")
        }
        return try {
            runBlocking(Dispatchers.IO) { route(request) }
        } catch (e: ApiFailure) {
            errorResponse(e.status, e.reason, e.code, e.message ?: e.code, e.problems, e.field)
        } catch (e: Exception) {
            mapDomainFailure(e)
        }
    }

    /**
     * The whole surface. Forty-seven path shapes over fifty-seven method-and-path rows; anything
     * else is a 404, and a known shape with the wrong verb is a 405 — except that an
     * `/v1/assets/{id}/…`, `/v1/groups/{id}/…`, `/v1/schedules/{id}/…` or `/v1/health-subjects/{id}/…`
     * sub-resource answers 404 for a verb it does not take. Written as an explicit `when` over the path's segments rather than a
     * table of regexes, so the set of things this listener answers can be read in one screen and
     * grepped in one line. Note that bare `/v1/import-merge` is **not** a route: the plan and the
     * apply are two different acts and neither is the default.
     *
     * 1.2 added nineteen of those rows — maintenance groups, schedules and their five operations,
     * the read-only closure history and `/v1/due` — additively and at version 1 (D-19). **Nothing
     * destructive came with them:** no verb removes a schedule, a group, a membership or a closure,
     * no verb amends a closure, and there is no snooze endpoint, so each of those is a 404 or a 405
     * because nothing below routes to it (invariants 43, 76).
     *
     * 1.3 added three, and the same holds: a reference is listed, created and amended, and
     * **nothing deletes one** — the API adds and amends, the phone removes (spec §6). No row here
     * accepts or returns a file either, at any version, so there is no share-by-API (I-3).
     *
     * 1.4 added fourteen rows over eleven new shapes (spec §9.1): seven `/v1/assets/{id}/…`
     * sub-resources (the season and its activations, the season mode, the maintenance break, the
     * health policy, conditions, health, health subjects), the three `/v1/health-subjects` shapes and
     * `/v1/attention` — additively, at version 1. **Nothing destructive came with them either**
     * (spec §9.2, invariant 127): no verb amends or removes a condition or an activation, removes a
     * health subject or writes a health value, so each of those falls through to a 404 or a 405.
     */
    private suspend fun route(request: ApiRequest): ApiResponse {
        // `removePrefix`, not `trim`: canonicalisation (dropping a trailing slash) happens exactly
        // once, in `parseRequest`, before `bodyCapFor` is ever consulted. Trimming a trailing slash
        // here too would let a non-canonical spelling reach a handler under the wrong cap — the
        // route and the cap must agree on the same string, which means neither may forgive what the
        // other did not already canonicalise (review S6).
        val segments = request.path.removePrefix("/").split('/')
        if (segments.firstOrNull() != "v1") throw ApiFailure.notFound(request.path)
        val rest = segments.drop(1)
        val method = request.method

        return when {
            rest == listOf("status") ->
                if (method == "GET") handlers.status() else notAllowed(request)

            rest == listOf("assets") -> when (method) {
                "GET" -> handlers.listAssets()
                "POST" -> handlers.createAsset(request)
                else -> notAllowed(request)
            }

            rest.size == 2 && rest[0] == "assets" -> when (method) {
                "GET" -> handlers.getAsset(rest[1])
                "PATCH" -> handlers.updateAsset(rest[1], request)
                else -> notAllowed(request)
            }

            rest.size == 3 && rest[0] == "assets" -> when (rest[2] to method) {
                "components" to "POST" -> handlers.createComponent(rest[1], request)
                "retire" to "POST" -> handlers.retireAsset(rest[1], request)
                "archive" to "POST" -> handlers.archiveAsset(rest[1], request)
                "definitions" to "GET" -> handlers.listDefinitions(rest[1])
                "profiles" to "GET" -> handlers.listProfiles(rest[1])
                "events" to "GET" -> handlers.listEvents(rest[1])
                // 1.2 — #55's asset → groups direction, and this asset's own schedules.
                "groups" to "GET" -> handlers.maintenance.listAssetGroups(rest[1])
                "schedules" to "GET" -> handlers.maintenance.listAssetSchedules(rest[1])
                // 1.3 — the ninth of these sub-resources, so a verb it does not take falls to the
                // `else` below and answers 404, not 405. That asymmetry with `/v1/references` is
                // the shipped convention and `docs/api/v1.md`'s 405 row records it.
                "references" to "GET" -> handlers.references.listForAsset(rest[1])
                // 1.4 — seven more sub-resources, on the same 404 convention. A condition and an
                // activation are appended, read and never amended; health is read, never written.
                "season" to "GET" -> handlers.seasonHealth.getSeason(rest[1])
                "season" to "POST" -> handlers.seasonHealth.recordActivation(rest[1], request)
                "season-mode" to "POST" -> handlers.seasonHealth.setSeasonMode(rest[1], request)
                "maintenance-break" to "POST" -> handlers.seasonHealth.setMaintenanceBreak(rest[1], request)
                "health-policy" to "POST" -> handlers.seasonHealth.setHealthPolicy(rest[1], request)
                "conditions" to "GET" -> handlers.seasonHealth.listConditions(rest[1])
                "conditions" to "POST" -> handlers.seasonHealth.recordCondition(rest[1], request)
                "health" to "GET" -> handlers.seasonHealth.getHealth(rest[1])
                "health-subjects" to "GET" -> handlers.seasonHealth.listSubjects(rest[1])
                else -> throw ApiFailure.notFound(request.path)
            }

            rest == listOf("groups") -> when (method) {
                "GET" -> handlers.maintenance.listGroups()
                "POST" -> handlers.maintenance.createGroup(request)
                else -> notAllowed(request)
            }

            rest.size == 2 && rest[0] == "groups" -> when (method) {
                "GET" -> handlers.maintenance.getGroup(rest[1])
                "PATCH" -> handlers.maintenance.updateGroup(rest[1], request)
                else -> notAllowed(request)
            }

            rest.size == 3 && rest[0] == "groups" -> when (rest[2] to method) {
                "archive" to "POST" -> handlers.maintenance.archiveGroup(rest[1], request)
                "schedules" to "GET" -> handlers.maintenance.listGroupSchedules(rest[1])
                else -> throw ApiFailure.notFound(request.path)
            }

            rest == listOf("schedules") -> when (method) {
                "GET" -> handlers.maintenance.listSchedules()
                "POST" -> handlers.maintenance.createSchedule(request)
                else -> notAllowed(request)
            }

            rest.size == 2 && rest[0] == "schedules" -> when (method) {
                "GET" -> handlers.maintenance.getSchedule(rest[1])
                "PATCH" -> handlers.maintenance.updateSchedule(rest[1], request)
                else -> notAllowed(request)
            }

            rest.size == 3 && rest[0] == "schedules" -> when (rest[2] to method) {
                "pause" to "POST" -> handlers.maintenance.pauseSchedule(rest[1], request)
                "archive" to "POST" -> handlers.maintenance.archiveSchedule(rest[1], request)
                "postpone" to "POST" -> handlers.maintenance.postponeSchedule(rest[1], request)
                "complete" to "POST" -> handlers.maintenance.completeSchedule(rest[1], request)
                "close-round" to "POST" -> handlers.maintenance.closeRound(rest[1], request)
                "closures" to "GET" -> handlers.maintenance.listClosures(rest[1])
                else -> throw ApiFailure.notFound(request.path)
            }

            rest == listOf("due") ->
                if (method == "GET") handlers.maintenance.listDue() else notAllowed(request)

            rest == listOf("definitions") ->
                if (method == "POST") handlers.saveDefinition(request) else notAllowed(request)

            rest.size == 3 && rest[0] == "definitions" && rest[2] == "archive" ->
                if (method == "POST") handlers.archiveDefinition(rest[1], request) else notAllowed(request)

            rest == listOf("profiles") ->
                if (method == "POST") handlers.saveProfile(request) else notAllowed(request)

            rest.size == 3 && rest[0] == "profiles" && rest[2] == "archive" ->
                if (method == "POST") handlers.archiveProfile(rest[1], request) else notAllowed(request)

            rest == listOf("events") ->
                if (method == "POST") handlers.logEvent(request) else notAllowed(request)

            rest.size == 2 && rest[0] == "events" -> when (method) {
                "PATCH" -> handlers.updateEvent(rest[1], request)
                "DELETE" -> handlers.deleteEvent(rest[1])
                else -> notAllowed(request)
            }

            rest == listOf("tags") ->
                if (method == "GET") handlers.listTagBindings() else notAllowed(request)

            // 1.3 — two path shapes, both 405 for a verb they do not take. There is no `DELETE`
            // on either, and `/v1/references/{id}/anything` is not a shape at all.
            rest == listOf("references") ->
                if (method == "POST") handlers.references.create(request) else notAllowed(request)

            rest.size == 2 && rest[0] == "references" ->
                if (method == "PATCH") handlers.references.update(rest[1], request) else notAllowed(request)

            // 1.4 — a health subject is created, read, replaced and archived, and never removed.
            rest == listOf("health-subjects") ->
                if (method == "POST") handlers.seasonHealth.createSubject(request) else notAllowed(request)

            rest.size == 2 && rest[0] == "health-subjects" -> when (method) {
                "GET" -> handlers.seasonHealth.getSubject(rest[1])
                "PATCH" -> handlers.seasonHealth.updateSubject(rest[1], request)
                else -> notAllowed(request)
            }

            rest.size == 3 && rest[0] == "health-subjects" -> when (rest[2] to method) {
                "archive" to "POST" -> handlers.seasonHealth.archiveSubject(rest[1], request)
                else -> throw ApiFailure.notFound(request.path)
            }

            rest == listOf("attention") ->
                if (method == "GET") handlers.seasonHealth.listAttention() else notAllowed(request)

            rest == listOf("import-merge", "plan") ->
                if (method == "POST") handlers.importMergePlan(request) else notAllowed(request)

            rest == listOf("import-merge", "apply") ->
                if (method == "POST") handlers.importMergeApply(request) else notAllowed(request)

            else -> throw ApiFailure.notFound(request.path)
        }
    }

    private fun notAllowed(request: ApiRequest): Nothing =
        throw ApiFailure.methodNotAllowed(request.method, request.path)
}
