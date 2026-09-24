package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.BackupCodec
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.CreateAsset
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code this suite pairs with. Invented, and the only token these requests carry. */
private const val TOKEN = "ABCD2345"

/**
 * 1.1.0 (#46) — the whole API, over `FakeGraph`, on the JVM, with no socket and no emulator.
 *
 * **Why there is no `Dispatchers.setMain` and no shared `TestCoroutineScheduler` here**, against
 * the house convention quoted in the plan's Global Constraints: there is no `ViewModel` in this
 * layer and nothing to settle. `ApiRouter.handle` is a *blocking* call — that is its contract, since
 * its caller is a socket thread — so it does not return until Room has answered, and every
 * assertion below is already sequenced by that. Putting a virtual clock under it would deadlock a
 * `runBlocking` on a scheduler the test thread owns. `FakeGraph()` therefore takes its default
 * query context (`Dispatchers.Default`, `FakeGraph.kt:74`), which is exactly right for a fixture
 * whose calls are synchronous.
 *
 * The real `AppGraph` is never built here: `ApiHandlers`' primary constructor takes the members it
 * uses, the way every view model in this app does (`AssetViewModels.kt:59`–`61`), so the fake graph
 * with its in-memory Room database drives the production use cases, the production mappers and the
 * production serializers.
 */
class ApiRouterTest {

    private val graph = FakeGraph()

    @After fun close() = graph.close()

    private fun router(): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
            graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
            maintenanceHandlersFor(graph),
            referenceHandlersFor(graph),
            seasonHealthHandlersFor(graph),
            appVersion = "1.1.0",
            schemaVersion = 5,
        ),
        TOKEN,
    )

    private fun call(
        method: String,
        path: String,
        body: String = "",
        token: String? = TOKEN,
        contentType: String = "application/json",
    ): ApiResponse {
        val headers = buildMap {
            put("host", "127.0.0.1")
            if (token != null) put("authorization", "Bearer $token")
            if (body.isNotEmpty()) put("content-type", contentType)
        }
        return router().handle(ApiRequest(method, path, headers, body.toByteArray()))
    }

    private fun ApiResponse.text(): String = body.decodeToString()

    private fun ApiResponse.code(): String =
        ApiJson.decodeFromString(ApiErrorBody.serializer(), text()).error.code

    private fun assetIn(response: ApiResponse) =
        ApiJson.decodeFromString(AssetResponse.serializer(), response.text()).asset

    /** One asset, created through the API, returned as its id. */
    private fun createHotTub(): String =
        assetIn(call("POST", "/v1/assets", """{"name":"Hot tub","category":"Water"}""")).id

    // --- the token ---------------------------------------------------------------------------

    @Test fun aRequestWithNoTokenIs401WithAnEmptyBody() {
        val response = call("GET", "/v1/status", token = null)
        assertEquals(401, response.status)
        assertEquals(0, response.body.size)
    }

    @Test fun aWrongTokenIs401WithAnEmptyBody() {
        for (wrong in listOf("", "abcd2345", "ABCD234", "ABCD23456", "ABCD2346")) {
            val response = call("GET", "/v1/status", token = wrong)
            assertEquals(401, response.status)
            assertEquals(0, response.body.size)
        }
    }

    /** An unauthenticated caller learns nothing about what exists: the 401 comes before routing. */
    @Test fun anUnauthenticatedCallerCannotProbeForRoutes() {
        assertEquals(401, call("GET", "/v1/assets", token = null).status)
        assertEquals(401, call("GET", "/v1/no-such-thing", token = null).status)
        assertEquals(401, call("POST", "/v1/assets", """{"nope":1}""", token = null).status)
    }

    // --- the shape of the surface ------------------------------------------------------------

    @Test fun anUnknownPathIs404() {
        assertEquals(404, call("GET", "/v1/no-such-thing").status)
        assertEquals(404, call("GET", "/v2/assets").status)
        assertEquals(404, call("GET", "/").status)
        assertEquals("not_found", call("GET", "/v1/no-such-thing").code())
    }

    @Test fun aKnownPathWithTheWrongMethodIs405() {
        assertEquals(405, call("DELETE", "/v1/status").status)
        assertEquals(405, call("PATCH", "/v1/assets").status)
        assertEquals(405, call("DELETE", "/v1/tags").status)
        assertEquals("method_not_allowed", call("DELETE", "/v1/status").code())
    }

    /**
     * The security minimum with the widest blast radius: the destructive and NFC-writing use cases
     * are not reachable, and they are not reachable because **nothing routes to them**. Every path
     * a plausible client would try falls through to 404.
     */
    @Test fun theDestructiveUseCasesHaveNoRoute() {
        val id = createHotTub()
        for ((method, path) in listOf(
            "POST" to "/v1/import-replace",
            "POST" to "/v1/import",
            "POST" to "/v1/wipe",
            "DELETE" to "/v1/assets",
            "GET" to "/v1/export",
            "POST" to "/v1/export",
            "POST" to "/v1/tags",
            "PATCH" to "/v1/tags",
            "DELETE" to "/v1/tags/t1",
            "POST" to "/v1/tags/t1/bind",
            "GET" to "/v1/attachments",
            "GET" to "/v1/attachments/att1/bytes",
            "GET" to "/v1/links",
            "DELETE" to "/v1/assets/$id",
            "DELETE" to "/v1/definitions/d1",
            "DELETE" to "/v1/profiles/p1",
            // 1.2's additions (invariants 43, 76). A closure is immutable exported history, so
            // neither a DELETE nor a PATCH reaches one; the snooze is device-local under D-13 and
            // has no endpoint at all; and nothing deletes a schedule, a group or a membership row.
            "DELETE" to "/v1/schedules/s1",
            "DELETE" to "/v1/schedules",
            "DELETE" to "/v1/groups/g1",
            "DELETE" to "/v1/groups",
            "DELETE" to "/v1/schedules/s1/closures",
            "PATCH" to "/v1/schedules/s1/closures",
            "POST" to "/v1/schedules/s1/closures",
            "DELETE" to "/v1/schedules/s1/closures/c1",
            "POST" to "/v1/schedules/s1/snooze",
            "POST" to "/v1/snooze",
            "DELETE" to "/v1/groups/g1/members/m1",
            "DELETE" to "/v1/due",
            // 1.3's additions (spec §6). The API adds and amends a reference; the phone removes
            // one. A `DELETE` added here for symmetry would make an automation client able to
            // erase history the phone is meant to own, so neither shape takes one — and there is
            // no byte path either, at any version (I-3).
            "DELETE" to "/v1/references/r1",
            "DELETE" to "/v1/references",
            "DELETE" to "/v1/assets/$id/references",
            "GET" to "/v1/references/r1/bytes",
            "POST" to "/v1/references/r1/bytes",
            // 1.4's additions (spec §9.2, invariant 127). A condition and an activation are
            // immutable facts — appended, read, never amended or removed; a health subject is
            // archived, never removed; and health is computed at read time, so nothing writes it.
            "DELETE" to "/v1/assets/$id/conditions",
            "PATCH" to "/v1/assets/$id/conditions",
            "DELETE" to "/v1/assets/$id/conditions/c1",
            "PATCH" to "/v1/assets/$id/conditions/c1",
            "DELETE" to "/v1/assets/$id/season",
            "PATCH" to "/v1/assets/$id/season",
            "DELETE" to "/v1/assets/$id/season/a1",
            "PATCH" to "/v1/assets/$id/season/a1",
            "DELETE" to "/v1/assets/$id/season/activations/a1",
            "DELETE" to "/v1/health-subjects/h1",
            "DELETE" to "/v1/health-subjects",
            "DELETE" to "/v1/assets/$id/health-subjects",
            "POST" to "/v1/assets/$id/health",
            "PATCH" to "/v1/assets/$id/health",
            "DELETE" to "/v1/assets/$id/health",
            "POST" to "/v1/health",
            "DELETE" to "/v1/attention",
            "PATCH" to "/v1/attention",
        )) {
            val response = call(method, path, if (method == "GET") "" else "{}")
            assertTrue("$method $path answered ${response.status}", response.status == 404 || response.status == 405)
        }
    }

    // --- status ------------------------------------------------------------------------------

    @Test fun statusReportsTheVersionsAndTheCounts() {
        createHotTub()
        val status = ApiJson.decodeFromString(
            StatusResponse.serializer(), call("GET", "/v1/status").text(),
        )
        assertEquals("1.1.0", status.appVersion)
        assertEquals(API_VERSION, status.apiVersion)
        assertEquals(5, status.schemaVersion)
        assertEquals(BackupCodec.FORMAT_VERSION, status.backupFormatVersion)
        assertEquals(1, status.counts["assets"])
        assertEquals(0, status.counts["events"])
        assertEquals(0, status.counts["attachments"])
    }

    // --- assets ------------------------------------------------------------------------------

    @Test fun createThenGetAnAsset() {
        val created = call("POST", "/v1/assets", """{"name":"Hot tub","category":"Water"}""")
        assertEquals(201, created.status)
        val id = assetIn(created).id

        val fetched = call("GET", "/v1/assets/$id")
        assertEquals(200, fetched.status)
        assertEquals("Hot tub", assetIn(fetched).name)
        assertEquals("Water", assetIn(fetched).category)
        assertEquals(AssetStatus.ACTIVE.name, assetIn(fetched).status)
    }

    /** The list is the dashboard's shape: systems, and each system's components under its id. */
    @Test fun listReturnsTopLevelAssetsAndTheirComponents() {
        val tub = createHotTub()
        val pump = assetIn(call("POST", "/v1/assets/$tub/components", """{"name":"Pool pump"}""")).id
        assetIn(call("POST", "/v1/assets", """{"name":"Generator"}"""))

        val list = ApiJson.decodeFromString(
            AssetListResponse.serializer(), call("GET", "/v1/assets").text(),
        )
        assertEquals(listOf("Generator", "Hot tub"), list.topLevel.map { it.name })
        assertEquals(listOf(pump), list.components.getValue(tub).map { it.id })
        assertEquals(tub, list.components.getValue(tub).single().parentAssetId)
        // A component is listed under its parent and not again at the top.
        assertTrue(list.topLevel.none { it.id == pump })
    }

    @Test fun updateChangesFieldsAndKeepsIdentity() {
        val id = createHotTub()
        val updated = call("PATCH", "/v1/assets/$id", """{"name":"Hot tub","location":"Deck"}""")
        assertEquals(200, updated.status)
        assertEquals(id, assetIn(updated).id)
        assertEquals("Deck", assetIn(updated).location)
        // Not in the command, so it comes from the stored row, not from the request.
        assertEquals(AssetStatus.ACTIVE.name, assetIn(updated).status)
    }

    @Test fun aComponentIsParentedOnThePathIdWhateverTheBodySays() {
        val tub = createHotTub()
        val other = assetIn(call("POST", "/v1/assets", """{"name":"Generator"}""")).id
        val child = call(
            "POST", "/v1/assets/$tub/components",
            """{"name":"Pool pump","parentAssetId":"$other"}""",
        )
        assertEquals(201, child.status)
        assertEquals(tub, assetIn(child).parentAssetId)
    }

    @Test fun retireAndArchiveAreBothReversible() {
        val id = createHotTub()

        assertEquals("2026-04-01", assetIn(call("POST", "/v1/assets/$id/retire", """{"retiredOn":"2026-04-01"}""")).retiredOn)
        assertEquals(null, assetIn(call("POST", "/v1/assets/$id/retire", """{"retiredOn":null}""")).retiredOn)

        assertEquals(AssetStatus.ARCHIVED.name, assetIn(call("POST", "/v1/assets/$id/archive", """{"archived":true}""")).status)
        assertEquals(AssetStatus.ACTIVE.name, assetIn(call("POST", "/v1/assets/$id/archive", """{"archived":false}""")).status)
    }

    // --- definitions, profiles, events -------------------------------------------------------

    @Test fun saveListAndArchiveADefinition() {
        val id = createHotTub()
        val saved = call("POST", "/v1/definitions", """{"assetId":"$id","label":"pH","unit":"","decimals":1}""")
        assertEquals(200, saved.status)
        val definition = ApiJson.decodeFromString(DefinitionResponse.serializer(), saved.text()).definition
        assertEquals("ph", definition.key)
        assertEquals("ENTERED", definition.kind)

        val listed = ApiJson.decodeFromString(
            DefinitionListResponse.serializer(), call("GET", "/v1/assets/$id/definitions").text(),
        )
        assertEquals(listOf("pH"), listed.definitions.map { it.label })

        assertEquals(204, call("POST", "/v1/definitions/${definition.id}/archive", """{"archived":true}""").status)
        runBlocking {
            assertTrue(graph.definitions.get(DefinitionId(definition.id))!!.archivedAt != null)
        }

        // The same endpoint with an id in the body is the edit: `SaveDefinition.run(id, cmd)`.
        val edited = call(
            "POST", "/v1/definitions",
            """{"id":"${definition.id}","assetId":"$id","label":"pH (top)","decimals":2}""",
        )
        assertEquals(200, edited.status)
        assertEquals("pH (top)", ApiJson.decodeFromString(DefinitionResponse.serializer(), edited.text()).definition.label)
    }

    /**
     * Review S3: `SaveDefinitionRequest.toCommand()` used to pass `sourceAId`/`sourceBId` into
     * `DefinitionCommand` positionally, two adjacent parameters of the identical type `DefinitionId?`
     * — a reorder in `:core` would have silently swapped them. Distinct sources prove the wire
     * still tells A from B after naming the arguments.
     */
    @Test fun aDerivedDefinitionKeepsSourceADistinctFromSourceB() {
        val id = createHotTub()
        val sourceA = ApiJson.decodeFromString(
            DefinitionResponse.serializer(),
            call("POST", "/v1/definitions", """{"assetId":"$id","label":"Source A","decimals":1}""").text(),
        ).definition
        val sourceB = ApiJson.decodeFromString(
            DefinitionResponse.serializer(),
            call("POST", "/v1/definitions", """{"assetId":"$id","label":"Source B","decimals":1}""").text(),
        ).definition

        val derived = call(
            "POST", "/v1/definitions",
            """{"assetId":"$id","label":"Drop","kind":"DERIVED","formula":"PERCENT_DROP",""" +
                """"sourceAId":"${sourceA.id}","sourceBId":"${sourceB.id}"}""",
        )
        assertEquals(200, derived.status)
        val definition = ApiJson.decodeFromString(DefinitionResponse.serializer(), derived.text()).definition
        assertEquals(sourceA.id, definition.sourceAId)
        assertEquals(sourceB.id, definition.sourceBId)
    }

    @Test fun saveListAndArchiveAProfile() {
        val id = createHotTub()
        val definition = ApiJson.decodeFromString(
            DefinitionResponse.serializer(),
            call("POST", "/v1/definitions", """{"assetId":"$id","label":"pH","decimals":1}""").text(),
        ).definition

        val saved = call(
            "POST", "/v1/profiles",
            """{"assetId":"$id","name":"Water test","eventKind":"MEASUREMENT",""" +
                """"fields":[{"definitionId":"${definition.id}","required":true}]}""",
        )
        assertEquals(200, saved.status)
        val profile = ApiJson.decodeFromString(ProfileResponse.serializer(), saved.text()).profile
        assertEquals("Water test", profile.name)
        assertEquals("Water test", profile.defaultTitle)
        assertEquals(listOf(definition.id), profile.fields.map { it.definitionId })

        val listed = ApiJson.decodeFromString(
            ProfileListResponse.serializer(), call("GET", "/v1/assets/$id/profiles").text(),
        )
        assertEquals(listOf("Water test"), listed.profiles.map { it.name })

        assertEquals(204, call("POST", "/v1/profiles/${profile.id}/archive", """{"archived":true}""").status)
    }

    @Test fun logListUpdateAndDeleteAnEvent() {
        val id = createHotTub()
        val definition = ApiJson.decodeFromString(
            DefinitionResponse.serializer(),
            call("POST", "/v1/definitions", """{"assetId":"$id","label":"pH","decimals":1}""").text(),
        ).definition

        val logged = call(
            "POST", "/v1/events",
            """{"assetId":"$id","kind":"MAINTENANCE","title":"Filter change",""" +
                """"occurredOn":"2026-09-21","tzId":"UTC","notes":"quarterly",""" +
                """"values":{"${definition.id}":"7.4"},""" +
                """"consumables":[{"name":"Cartridge","quantity":"1","unit":"ea"}]}""",
        )
        assertEquals(201, logged.status)
        val event = ApiJson.decodeFromString(EventResponse.serializer(), logged.text()).event
        assertEquals("Filter change", event.title)
        assertEquals(listOf(7.4), event.measurements.map { it.valueNum })
        assertEquals(listOf("Cartridge"), event.consumables.map { it.name })

        val listed = ApiJson.decodeFromString(
            EventListResponse.serializer(), call("GET", "/v1/assets/$id/events").text(),
        )
        assertEquals(listOf(event.id), listed.events.map { it.id })

        val edited = call(
            "PATCH", "/v1/events/${event.id}",
            """{"assetId":"$id","kind":"MAINTENANCE","title":"Filter change (redone)",""" +
                """"occurredOn":"2026-09-21","tzId":"UTC"}""",
        )
        assertEquals(200, edited.status)
        assertEquals(event.id, ApiJson.decodeFromString(EventResponse.serializer(), edited.text()).event.id)

        assertEquals(204, call("DELETE", "/v1/events/${event.id}").status)
        runBlocking { assertEquals(null, graph.events.get(EventId(event.id))) }
    }

    // --- tag bindings, read only -------------------------------------------------------------

    @Test fun tagBindingsAreReadableAndNotWritable() {
        val id = createHotTub()
        runBlocking {
            graph.provisionTag.begin(TagTarget.AssetTarget(AssetId(id)), label = "Lid")
        }
        val tags = ApiJson.decodeFromString(
            TagListResponse.serializer(), call("GET", "/v1/tags").text(),
        )
        assertEquals(1, tags.tags.size)
        assertEquals(id, tags.tags.single().assetId)
        assertEquals(PayloadFormat.V1.name, tags.tags.single().payloadFormat)
        // And there is no way in through this surface.
        assertEquals(405, call("POST", "/v1/tags", """{"assetId":"$id"}""").status)
        runBlocking { assertEquals(1, graph.tags.all().size) }
    }

    // --- refusals ----------------------------------------------------------------------------

    @Test fun aValidationFailureIs422AndNamesTheProblems() {
        val response = call("POST", "/v1/assets", """{"name":"  "}""")
        assertEquals(422, response.status)
        val error = ApiJson.decodeFromString(ApiErrorBody.serializer(), response.text()).error
        assertEquals("asset_validation", error.code)
        assertEquals(listOf("NameRequired"), error.problems)
    }

    @Test fun anAbsentRowIs404AndAnUnknownFieldIs400() {
        assertEquals(404, call("GET", "/v1/assets/nope").status)
        assertEquals("no_such_asset", call("PATCH", "/v1/assets/nope", """{"name":"Hot tub"}""").code())
        assertEquals(404, call("DELETE", "/v1/events/nope").status)

        val typo = call("POST", "/v1/assets", """{"name":"Hot tub","serialNo":"X1"}""")
        assertEquals(400, typo.status)
        assertEquals("bad_request", typo.code())
    }

    /** A parent that is its own child: `validateAsset` throws `AssetCycle`, a real state 409. */
    @Test fun aSelfParentingPatchIs409AssetCycle() {
        val id = createHotTub()
        val response = call("PATCH", "/v1/assets/$id", """{"name":"Hot tub","parentAssetId":"$id"}""")
        assertEquals(409, response.status)
        assertEquals("asset_cycle", response.code())
    }

    /**
     * Review S4: `mapDomainFailure`'s `else` branch is the one place whose entire job is to stop a
     * message leaking — it emits `e.javaClass.simpleName` and deliberately drops `e.message`. A stub
     * repository through `ApiHandlers`' primary constructor is the seam that already exists to prove
     * it: the exception message below (a fabricated database path) must never reach the response.
     */
    private class ThrowingAssetRepository : AssetRepository {
        override suspend fun upsert(asset: Asset): Unit = error("not used by this test")
        override suspend fun get(id: AssetId): Asset? = error("not used by this test")
        override suspend fun all(): List<Asset> =
            throw IllegalStateException("/data/data/com.loosecannon.servicetag/databases/servicetag.db is corrupt")
        override suspend fun delete(id: AssetId): Unit = error("not used by this test")
        override suspend fun deleteAll(): Unit = error("not used by this test")
        override fun observeAll(): Flow<List<Asset>> = error("not used by this test")
    }

    @Test fun anUnanticipatedFailureIs500WithNoMessageLeak() {
        val brokenRouter = ApiRouter(
            ApiHandlers(
                ThrowingAssetRepository(), graph.tags, graph.links, graph.definitions, graph.profiles,
                graph.events, graph.attachments,
                graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
                graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
                graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
                maintenanceHandlersFor(graph),
                referenceHandlersFor(graph),
                seasonHealthHandlersFor(graph),
                appVersion = "1.1.0",
                schemaVersion = 5,
            ),
            TOKEN,
        )
        val response = brokenRouter.handle(
            ApiRequest("GET", "/v1/status", mapOf("authorization" to "Bearer $TOKEN"), ByteArray(0)),
        )
        assertEquals(500, response.status)
        val error = ApiJson.decodeFromString(ApiErrorBody.serializer(), response.text()).error
        assertEquals("internal", error.code)
        assertEquals("IllegalStateException", error.message)
        assertFalse(response.text().contains("corrupt"))
        assertFalse(response.text().contains("db"))
    }

    // --- the merge import over the wire ------------------------------------------------------

    /**
     * A donor install's real format-5 data archive, with ids that **cannot** collide with this
     * suite's. Do not simplify this to `donor.createAsset`.
     *
     * `FakeGraph`'s generator counts from zero per instance (`FakeGraph.kt:83`–`84`), so a second
     * `FakeGraph` mints `…8000-000000000001` for its first asset — and so does `graph` for
     * `createHotTub()`. Two unrelated rows would then share an id, every merge case below would
     * plan as `CONTENT_DIFFERS`, and two of them would fail for a reason that has nothing to do
     * with the API. So the donor gets its own generator in a disjoint range, wired into a
     * `CreateAsset` of its own; everything else about the export stays the production path.
     */
    private fun donorArchive(vararg names: String): ByteArray {
        val donor = FakeGraph()
        return try {
            var n = 0
            val disjoint = IdGenerator { "00000000-0000-4000-8000-9000%08d".format(++n) }
            val createAsset = CreateAsset(
                donor.assets, donor.uow, disjoint, donor.clock, donor.applyTemplate,
            )
            runBlocking {
                names.forEach { createAsset.run(it, "Power") }
                donor.exportBackupSet.run().data
            }
        } finally {
            donor.close()
        }
    }

    private fun postArchive(path: String, archive: ByteArray, type: String = "application/zip") =
        router().handle(
            ApiRequest(
                "POST", path,
                mapOf("authorization" to "Bearer $TOKEN", "content-type" to type),
                archive,
            ),
        )

    private fun reportIn(response: ApiResponse) =
        ApiJson.decodeFromString(MergeReportResponse.serializer(), response.text())

    /** The plan endpoint's whole promise, over the wire: it decides and it writes nothing. */
    @Test fun importMergePlanReportsWhatWouldHappenAndWritesNothing() {
        val archive = donorArchive("Generator")
        createHotTub()
        val before = runBlocking { graph.assets.all().map { it.id.value }.sorted() }

        val response = postArchive(IMPORT_MERGE_PLAN_PATH, archive)

        assertEquals(200, response.status)
        val report = reportIn(response)
        assertEquals(BackupCodec.FORMAT_VERSION, report.formatVersion)
        assertTrue(report.applicable)
        assertEquals(MergeTallyDto(insert = 1, identical = 0, conflict = 0, skipped = 0), report.assets)
        assertEquals(emptyList<MergeDecisionDto>(), report.conflicts)
        runBlocking { assertEquals(before, graph.assets.all().map { it.id.value }.sorted()) }
    }

    @Test fun importMergeApplyWritesTheUnionAndIsIdempotent() {
        val archive = donorArchive("Generator")
        createHotTub()

        val applied = postArchive(IMPORT_MERGE_APPLY_PATH, archive)
        assertEquals(200, applied.status)
        assertTrue(reportIn(applied).applicable)
        assertEquals(MergeTallyDto(1, 0, 0, 0), reportIn(applied).assets)
        runBlocking {
            assertEquals(listOf("Generator", "Hot tub"), graph.assets.all().map { it.name }.sorted())
        }

        // #44 acceptance 4, at the endpoint: the second apply is all-IDENTICAL and changes nothing.
        val again = postArchive(IMPORT_MERGE_APPLY_PATH, archive)
        assertEquals(200, again.status)
        assertEquals(MergeTallyDto(insert = 0, identical = 1, conflict = 0, skipped = 0), reportIn(again).assets)
        runBlocking { assertEquals(2, graph.assets.all().size) }
    }

    /**
     * The refusal contract: **409, the same body shape as a 200, the deterministic conflict list,
     * and zero writes.** The conflict is manufactured by importing an archive and then diverging
     * the very row it carries.
     */
    @Test fun importMergeApplyIs409WithTheConflictListAndNoWrites() {
        val archive = donorArchive("Generator")
        postArchive(IMPORT_MERGE_APPLY_PATH, archive)
        val id = runBlocking { graph.assets.all().single { it.name == "Generator" }.id }
        runBlocking { graph.updateAsset.run(id, AssetCommand(name = "Diesel generator")) }
        val before = runBlocking { graph.assets.all().sortedBy { it.id.value } }

        val refused = postArchive(IMPORT_MERGE_APPLY_PATH, archive)

        assertEquals(409, refused.status)
        val report = reportIn(refused)
        assertFalse(report.applicable)
        assertEquals(1, report.conflicts.size)
        assertEquals("ASSETS", report.conflicts.single().table)
        assertEquals(id.value, report.conflicts.single().id)
        assertEquals("CONFLICT", report.conflicts.single().verdict)
        assertEquals("CONTENT_DIFFERS", report.conflicts.single().reason)
        assertEquals(MergeTallyDto(insert = 0, identical = 0, conflict = 1, skipped = 0), report.assets)
        runBlocking { assertEquals(before, graph.assets.all().sortedBy { it.id.value }) }
    }

    @Test fun bothImportPathsRefuseAnythingThatIsNotAZip() {
        for (path in listOf(IMPORT_MERGE_PLAN_PATH, IMPORT_MERGE_APPLY_PATH)) {
            val wrongType = postArchive(path, byteArrayOf(1, 2, 3), type = "application/json")
            assertEquals(415, wrongType.status)
            assertEquals("unsupported_media_type", wrongType.code())

            val notAnArchive = postArchive(path, byteArrayOf(1, 2, 3))
            assertEquals(400, notAnArchive.status)
            assertEquals("archive_corrupt", notAnArchive.code())
        }
        // And a bare `/v1/import-merge` is not a route at all.
        assertEquals(404, postArchive("/v1/import-merge", byteArrayOf(1, 2, 3)).status)
    }

    /** The one cap the router publishes, and exactly which paths get it. */
    @Test fun onlyTheTwoImportPathsHaveTheBiggerCap() {
        assertEquals(MAX_IMPORT_BYTES, router().bodyCapFor(IMPORT_MERGE_PLAN_PATH))
        assertEquals(MAX_IMPORT_BYTES, router().bodyCapFor(IMPORT_MERGE_APPLY_PATH))
        assertEquals(MAX_BODY_BYTES, router().bodyCapFor("/v1/assets"))
        assertEquals(MAX_BODY_BYTES, router().bodyCapFor("/v1/status"))
        assertEquals(MAX_BODY_BYTES, router().bodyCapFor("/v1/import-merge"))
        assertEquals(MAX_BODY_BYTES, router().bodyCapFor("/v1/import-merge/plan/"))
        // Review S6: agreement, not divergence. `bodyCapFor` does not recognise the trailing-slash
        // spelling as the plan path (above), so `route` must not recognise it as the plan route
        // either — otherwise a second producer of `ApiRequest` (this suite's own `call()`, which
        // bypasses `parseRequest`'s canonicalisation) could reach the plan handler under the smaller
        // cap `bodyCapFor` just answered for this exact spelling. It is a 404, the same "not this
        // path" answer `bodyCapFor` gave.
        assertEquals(404, call("POST", "/v1/import-merge/plan/", "{}").status)
    }
}
