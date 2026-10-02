package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.MAX_ATTACHMENT_BYTES
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/**
 * Every request body's ceiling but three (the import pair's and the upload's): 64 KiB, which is a large asset form
 * and a huge event. #92's materialize takes this one too: its body is four review fields and never a file.
 */
internal const val MAX_BODY_BYTES: Int = 64 * 1024

/** The two endpoints that take an archive instead of a form. */
internal const val IMPORT_MERGE_PLAN_PATH: String = "/v1/import-merge/plan"
internal const val IMPORT_MERGE_APPLY_PATH: String = "/v1/import-merge/apply"

/** Their ceiling: 4 MiB, which is a data archive of a very full phone and no attachment bytes. */
internal const val MAX_IMPORT_BYTES: Int = 4 * 1024 * 1024

/**
 * #92 (C9): the attachment upload's ceiling, the attachment cap itself (256 MiB, which fits the parser's `Int`), for
 * `POST /v1/assets/{id}/attachments` and, since #69, the same upload to a SupplyItem and an installed component
 * ([isAttachmentUpload]) alone. That body is never held in memory: it streams into staging.
 */
internal const val MAX_UPLOAD_BYTES: Int = MAX_ATTACHMENT_BYTES.toInt()

/**
 * Authenticates, matches, and turns whatever comes back — an answer or a refusal — into bytes.
 *
 * **The token is checked first, before anything else at all.** A caller without it cannot learn
 * whether a path exists, whether its body parsed, or how long the answer would have been: the reply
 * is 401 with a zero-byte body, every time, for every path. Every body but one has already been read
 * off the socket by then, which is why the ceilings live in [parseRequest] below this class and apply
 * to an unauthenticated caller too. The one is #92's attachment upload: its body is still on the
 * socket ([ApiRequest.stream]), so a 401 never reads, stages, hashes or decodes a byte of it (C10),
 * and the server's bounded drain is all an unauthenticated body ever gets.
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
    /** #92 (C16, C33): the one download in flight, tracked for observability; see [DownloadInFlight]. */
    private val download = DownloadInFlight()

    /**
     * Asked by the parser with the method and the path, before a body byte is read. The upload tier is `POST`'s
     * alone (C9): any other method on that shape keeps 64 KiB and is read like any other request.
     */
    fun bodyCapFor(method: String, path: String): Int = when {
        isAttachmentUpload(method, path) -> MAX_UPLOAD_BYTES
        path == IMPORT_MERGE_PLAN_PATH || path == IMPORT_MERGE_APPLY_PATH -> MAX_IMPORT_BYTES
        else -> MAX_BODY_BYTES
    }

    /**
     * [generation] is the listener generation that read [request] (#92, B2-pre BC5): the materialize download is its
     * child, so a request read before a `stop()` that reaches its download after it starts cancelled. It is only ever
     * a download's parent — `runBlocking` itself stays uncancellable, so every other route runs to completion as it
     * always has. Null for an in-process caller, which no listener stops.
     */
    fun handle(request: ApiRequest, generation: Job? = null): ApiResponse {
        if (!tokenMatches(token, request.bearerToken())) {
            return ApiResponse.empty(401, "Unauthorized")
        }
        return try {
            runBlocking(Dispatchers.IO) { route(request, generation) }
        } catch (e: RequestStreamFailed) {
            throw e // #92 C12: the peer's stream died; there is no one to answer
        } catch (e: CancellationException) {
            throw e // #92 C16: `stop()` cancelled a download; the connection is closed, so no answer is sent
        } catch (e: ApiFailure) {
            errorResponse(e.status, e.reason, e.code, e.message ?: e.code, e.problems, e.field)
        } catch (e: Exception) {
            mapDomainFailure(e)
        }
    }

    /**
     * #92 (C16): what the listener's `stop()` calls — cancels the registered download's `Job`, and nothing else. A
     * second path only: `stop()` cancelling the generation's `Job` is what stops every download of that generation
     * (BC5). It never waits: a cancelled download unwinds on its own worker, and a commit that began is not reached
     * (R87-4).
     */
    fun cancelDownload() = download.cancel()

    /** Row 45's window onto the slot: the download registered now, or null. */
    internal fun downloadInFlight(): Job? = download.current()

    /**
     * The whole surface. Eighty-one path shapes over one hundred and one method-and-path rows; anything
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
     * **nothing deletes one** — the API adds and amends, the phone removes (spec §6). A reference has
     * no bytes (I-3): none of those rows accepts or returns a file. Since #92 one row accepts a file —
     * an asset attachment's upload, below — and still no row returns one.
     *
     * 1.4 added fourteen rows over eleven new shapes (spec §9.1): seven `/v1/assets/{id}/…`
     * sub-resources (the season and its activations, the season mode, the maintenance break, the
     * health policy, conditions, health, health subjects), the three `/v1/health-subjects` shapes and
     * `/v1/attention` — additively, at version 1. **Nothing destructive came with them either**
     * (spec §9.2, invariant 127): no verb amends or removes a condition or an activation, removes a
     * health subject or writes a health value, so each of those falls through to a 404 or a 405.
     *
     * 1.4.1 added two rows over two shapes (#80): the provider repair's plan and apply, both `POST`,
     * both `{}`. The plan writes nothing; the apply adds one `LOCAL` provider row to each ACTIVE,
     * reminders-on, providerless schedule and to nothing else, and deletes nothing.
     *
     * #79 added two rows over two shapes, both `/v1/assets/{id}/…` sub-resources on the same 404
     * convention: the warranty, derived for today and stored nowhere, and its reminder lead, written
     * through `SetWarrantyReminder` alone. Neither runs a reminder sweep.
     *
     * #79b added five rows over four shapes: the nineteenth `/v1/assets/{id}/…` sub-resource (an asset's
     * service cases, read only), and `/v1/service-cases`, `/v1/service-cases/{id}` and
     * `/v1/service-cases/{id}/entries`, each a 405 for a verb it does not take. **Nothing destructive came
     * with them** (R79-8, R79-9): no verb deletes a case, amends or deletes an entry, or writes a case's
     * status or `closedOn` except a status entry — a CANCELLED or CLOSED entry is the exit.
     *
     * #72 added five rows over four shapes: the twentieth `/v1/assets/{id}/…` sub-resource (an asset's
     * loans, read only), and `/v1/loans`, `/v1/loans/{id}` and `/v1/loans/{id}/return`, each a 405 for a
     * verb it does not take. **Nothing destructive came with them either** (R72-17): no verb deletes a
     * loan — "Mark returned" is the exit, and the row stays as history — and none relinks one or reads a
     * contact (R72-3, R72-4): a link is made on the phone alone. None runs a reminder sweep (R72-15).
     *
     * #86 added one row over one shape: the twenty-first `/v1/assets/{id}/…` sub-resource, an asset's succession,
     * read only. **No route records a succession** (R86-18): only the phone's Replace asset records one, and the
     * import-merge apply only inserts an archive's rows, so no verb here makes, amends or removes one, and
     * `AssetDto` carries no succession field — until #92: `POST /v1/assets/{id}/replace` records one over the same
     * use case (R92-1 supersedes R86-18); still no verb amends or removes one.
     *
     * #92 (B1a) added three rows over two shapes: the twenty-second `/v1/assets/{id}/…` sub-resource (an asset's own
     * attachments and the folder's state, read only), and `/v1/attachments/{id}`, read and amended through
     * `UpdateAttachment` alone. **Nothing deletes an attachment here** and no row reads or writes its bytes.
     *
     * #92 (B1b) added one row on the twenty-second shape: `POST /v1/assets/{id}/attachments`, one file streamed into
     * staging and committed through `AddAttachment` alone, idempotent by the caller's operation key. No row reads
     * an attachment's bytes back out.
     *
     * #92 (B2) added one row over one shape: `POST /v1/references/{id}/materialize`, save as document for an existing
     * web reference **by id** — never a URL from the wire (R92-3) — through `MaterializeReference.prepare` and
     * `commit` alone; a 405 for any other verb. The reference itself is never written.
     *
     * #92 (B3) added three rows over three shapes: the twenty-third to twenty-fifth `/v1/assets/{id}/…`
     * sub-resources, `replace-offer` (read), `replace-plan` (writes nothing) and `replace`, #86's one atomic write
     * behind the plan's digest (R92-1 supersedes R86-18).
     *
     * #15 added nine rows over six shapes: the five SupplyItem rows — `GET` and `POST /v1/supply-items`, `GET` and
     * `PATCH /v1/supply-items/{id}`, `POST /v1/supply-items/{id}/archive` — beside the group triad; the twenty-sixth
     * `/v1/assets/{id}/…` sub-resource, an asset's supplies, read only; and the applicability rows `POST
     * /v1/asset-supplies`, `PATCH` and the one delete verb, `DELETE /v1/asset-supplies/{id}` (R15-5: an applicability
     * row is configuration, not a record, so the references' "the API adds and amends, the phone removes" is
     * deliberately not followed; `DELETE /v1/events/{id}` is the precedent). **Nothing deletes a SupplyItem**: it is
     * archived, never removed, so no verb on its shapes does.
     *
     * #47 added six rows over five shapes: the twenty-seventh `/v1/assets/{id}/…` sub-resource, an asset's installed
     * components, current and removed, read only; `POST /v1/installed-components`; `GET` and `PATCH
     * /v1/installed-components/{id}` (the `PATCH` an overlay); and `POST /v1/installed-components/{id}/remove` and
     * `…/replace`, each one write through its use case. **Nothing deletes an installed component**: a row is removed
     * or replaced and stays as history, so no verb on its shapes deletes one. An asset's child Assets keep
     * `/v1/assets/{id}/components` (R47-1).
     *
     * #69 added six rows over four shapes (C19): a SupplyItem's and an installed component's own links (`GET
     * …/references`) and own files (`GET` and `POST …/attachments`), each the asset sub-resource's handler with
     * another owner, and each a 405 for a verb it does not take. **Nothing destructive came with them:** no verb
     * deletes a link or a file, and none moves one to another owner — a move is the phone's remove and a new add.
     */
    private suspend fun route(request: ApiRequest, generation: Job?): ApiResponse {
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
                "references" to "GET" -> handlers.references.listForOwner(ReferenceOwner.OfAsset(AssetId(rest[1])))
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
                // #79 — two more, the seventeenth and eighteenth: the derived warranty, and the lead.
                "warranty" to "GET" -> handlers.warranty.getWarranty(rest[1])
                "warranty-reminder" to "POST" -> handlers.warranty.setWarrantyReminder(rest[1], request)
                // #79b — the nineteenth: the asset's service cases, read only.
                "service-cases" to "GET" -> handlers.serviceCases.listForAsset(rest[1])
                // #72 — the twentieth: the asset's loans, open and returned, read only.
                "loans" to "GET" -> handlers.loans.listForAsset(rest[1])
                // #86 — the twenty-first: which asset this one replaces and which replaced it, read only.
                "succession" to "GET" -> handlers.getSuccession(rest[1])
                // #92 — the twenty-second: the asset's own attachments and the folder's state, read only.
                "attachments" to "GET" -> handlers.attachmentRoutes.listForOwner(ReferenceOwner.OfAsset(AssetId(rest[1])))
                // #92 (B1b) — the first row that accepts a file: streamed, authenticated before a byte is read.
                "attachments" to "POST" ->
                    handlers.attachmentRoutes.upload(ReferenceOwner.OfAsset(AssetId(rest[1])), request)
                // #92 (B3) — the twenty-third to twenty-fifth, the replace triad (R92-1 supersedes R86-18): the offer
                // and the plan write nothing; the apply is #86's one atomic write, behind the plan's digest.
                "replace-offer" to "GET" -> handlers.replace.offer(rest[1])
                "replace-plan" to "POST" -> handlers.replace.plan(rest[1], request)
                "replace" to "POST" -> handlers.replace.replace(rest[1], request)
                // #15 — the twenty-sixth: the asset's supplies and each item they name once, read only.
                "supply-items" to "GET" -> handlers.supplies.listForAsset(rest[1])
                // #47 — the twenty-seventh: the asset's installed components, current and removed, read only.
                "installed-components" to "GET" -> handlers.installedComponents.listForAsset(rest[1])
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

            // #15 — a SupplyItem is listed, created, read, amended by overlay and archived, and never deleted
            // (R15-5): a verb a shape does not take is a 405, any other sub-path no shape at all.
            rest == listOf("supply-items") -> when (method) {
                "GET" -> handlers.supplies.list()
                "POST" -> handlers.supplies.create(request)
                else -> notAllowed(request)
            }

            rest.size == 2 && rest[0] == "supply-items" -> when (method) {
                "GET" -> handlers.supplies.get(rest[1])
                "PATCH" -> handlers.supplies.update(rest[1], request)
                else -> notAllowed(request)
            }

            rest.size == 3 && rest[0] == "supply-items" && rest[2] == "archive" ->
                if (method == "POST") handlers.supplies.archive(rest[1], request) else notAllowed(request)

            // #69 (C19) — a SupplyItem's own links and files, archived or not, on the asset sub-resources' handlers.
            rest.size == 3 && rest[0] == "supply-items" && rest[2] == "references" ->
                if (method == "GET") {
                    handlers.references.listForOwner(ReferenceOwner.OfSupplyItem(SupplyId(rest[1])))
                } else {
                    notAllowed(request)
                }

            rest.size == 3 && rest[0] == "supply-items" && rest[2] == "attachments" -> when (method) {
                "GET" -> handlers.attachmentRoutes.listForOwner(ReferenceOwner.OfSupplyItem(SupplyId(rest[1])))
                "POST" -> handlers.attachmentRoutes.upload(ReferenceOwner.OfSupplyItem(SupplyId(rest[1])), request)
                else -> notAllowed(request)
            }

            // #15 — an applicability row is created, re-roled and removed (R15-5); an asset's rows are read through
            // its sub-resource above, so neither shape answers a `GET`.
            rest == listOf("asset-supplies") ->
                if (method == "POST") handlers.supplies.addAssetSupply(request) else notAllowed(request)

            rest.size == 2 && rest[0] == "asset-supplies" -> when (method) {
                "PATCH" -> handlers.supplies.updateAssetSupply(rest[1], request)
                "DELETE" -> handlers.supplies.removeAssetSupply(rest[1])
                else -> notAllowed(request)
            }

            // #47 — an installed component is installed, read, amended by overlay, removed and replaced, and never
            // deleted: a verb a shape does not take is a 405, any other sub-path no shape at all.
            rest == listOf("installed-components") ->
                if (method == "POST") handlers.installedComponents.install(request) else notAllowed(request)

            rest.size == 2 && rest[0] == "installed-components" -> when (method) {
                "GET" -> handlers.installedComponents.get(rest[1])
                "PATCH" -> handlers.installedComponents.update(rest[1], request)
                else -> notAllowed(request)
            }

            rest.size == 3 && rest[0] == "installed-components" && rest[2] == "remove" ->
                if (method == "POST") handlers.installedComponents.remove(rest[1], request) else notAllowed(request)

            rest.size == 3 && rest[0] == "installed-components" && rest[2] == "replace" ->
                if (method == "POST") handlers.installedComponents.replace(rest[1], request) else notAllowed(request)

            // #69 (C19) — an installed component's own links and files, current or removed; a held asset's component
            // reads as any other and its writes are refused 409.
            rest.size == 3 && rest[0] == "installed-components" && rest[2] == "references" ->
                if (method == "GET") {
                    handlers.references.listForOwner(ReferenceOwner.OfInstalledComponent(InstalledComponentId(rest[1])))
                } else {
                    notAllowed(request)
                }

            rest.size == 3 && rest[0] == "installed-components" && rest[2] == "attachments" -> when (method) {
                "GET" -> handlers.attachmentRoutes.listForOwner(
                    ReferenceOwner.OfInstalledComponent(InstalledComponentId(rest[1])),
                )
                "POST" -> handlers.attachmentRoutes.upload(
                    ReferenceOwner.OfInstalledComponent(InstalledComponentId(rest[1])), request,
                )
                else -> notAllowed(request)
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
            // on either, and `/v1/references/{id}/anything` but #92's `materialize` is not a shape at all.
            rest == listOf("references") ->
                if (method == "POST") handlers.references.create(request) else notAllowed(request)

            rest.size == 2 && rest[0] == "references" ->
                if (method == "PATCH") handlers.references.update(rest[1], request) else notAllowed(request)

            // #92 (B2) — save as document: the reference by id, the four review fields, one synchronous answer.
            rest.size == 3 && rest[0] == "references" && rest[2] == "materialize" ->
                if (method == "POST") {
                    handlers.attachmentRoutes.materialize(rest[1], request, generation, download)
                } else {
                    notAllowed(request)
                }

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

            // #79b — a case is opened, read, replaced and appended to, and never deleted; an entry is
            // appended and never amended. Three shapes, each a 405 for a verb it does not take.
            rest == listOf("service-cases") ->
                if (method == "POST") handlers.serviceCases.open(request) else notAllowed(request)

            rest.size == 2 && rest[0] == "service-cases" -> when (method) {
                "GET" -> handlers.serviceCases.get(rest[1])
                "PATCH" -> handlers.serviceCases.update(rest[1], request)
                else -> notAllowed(request)
            }

            rest.size == 3 && rest[0] == "service-cases" && rest[2] == "entries" ->
                if (method == "POST") handlers.serviceCases.addEntry(rest[1], request) else notAllowed(request)

            // #72 — a loan is lent, read, replaced while open and returned, and never deleted, relinked or
            // reopened. Three shapes, each a 405 for a verb it does not take.
            rest == listOf("loans") ->
                if (method == "POST") handlers.loans.lend(request) else notAllowed(request)

            rest.size == 2 && rest[0] == "loans" -> when (method) {
                "GET" -> handlers.loans.get(rest[1])
                "PATCH" -> handlers.loans.update(rest[1], request)
                else -> notAllowed(request)
            }

            rest.size == 3 && rest[0] == "loans" && rest[2] == "return" ->
                if (method == "POST") handlers.loans.markReturned(rest[1], request) else notAllowed(request)

            // #92 — one attachment, read and amended; a 405 for any other verb. Nothing deletes one here, and
            // `/v1/attachments` and `/v1/attachments/{id}/…` are not shapes at all.
            rest.size == 2 && rest[0] == "attachments" -> when (method) {
                "GET" -> handlers.attachmentRoutes.get(rest[1])
                "PATCH" -> handlers.attachmentRoutes.update(rest[1], request)
                else -> notAllowed(request)
            }

            rest == listOf("attention") ->
                if (method == "GET") handlers.seasonHealth.listAttention() else notAllowed(request)

            rest == listOf("import-merge", "plan") ->
                if (method == "POST") handlers.importMergePlan(request) else notAllowed(request)

            rest == listOf("import-merge", "apply") ->
                if (method == "POST") handlers.importMergeApply(request) else notAllowed(request)

            // 1.4.1 (#80) — the provider repair. Top-level, so no `/v1/schedules/{id}` segment can
            // ever read `repairs` as an id; the plan writes nothing, the apply re-plans inside its
            // own write, and neither runs a reminder sweep.
            rest == listOf("repairs", "schedule-providers", "plan") ->
                if (method == "POST") handlers.maintenance.planProviderRepair(request) else notAllowed(request)

            rest == listOf("repairs", "schedule-providers", "apply") ->
                if (method == "POST") handlers.maintenance.applyProviderRepair(request) else notAllowed(request)

            else -> throw ApiFailure.notFound(request.path)
        }
    }

    private fun notAllowed(request: ApiRequest): Nothing =
        throw ApiFailure.methodNotAllowed(request.method, request.path)
}

/**
 * #92 (C16, C33): the one materialize download in flight, across listener generations. The router owns it and lives
 * for the Developer API visit; a handler [register]s its download's `Job` before it waits for `apiLongWrites`, and
 * [clear]s it in `finally` **only if the slot still holds that `Job`** (compare-and-clear, S1's rule).
 *
 * **Cancellation is carried by the per-generation `Job`, not by this slot** (B2-pre BC5): every download is a child of
 * the listener generation that read its request, and `stop()` cancels that generation, so a download is stopped
 * whether or not it is here. The slot only tracks the current download, for observability and tests, and `stop()`'s
 * [cancel] of it is a redundant second path. So that it tracks the live download, a `Job` already cancelled — a
 * stale generation's request that reaches its register after `stop()` — registers nothing: it never overwrites the
 * live one, and its compare-and-clear then finds nothing of its own to clear (review m1).
 */
internal class DownloadInFlight {
    private val slot = AtomicReference<Job?>(null)

    fun register(job: Job) {
        if (!job.isCancelled) slot.set(job)
    }

    fun clear(job: Job) {
        slot.compareAndSet(job, null)
    }

    /** Cancels only what the slot holds, and never waits. */
    fun cancel() {
        slot.get()?.cancel()
    }

    fun current(): Job? = slot.get()
}
