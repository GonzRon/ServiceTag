package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.toDomain
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.DerivedSpec
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.AssetProblem
import com.loosecannon.servicetag.core.usecase.ReplaceProblem
import com.loosecannon.servicetag.core.usecase.ReplaceStale
import com.loosecannon.servicetag.core.usecase.readSnapshot
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.meterDefinitionOf
import com.loosecannon.servicetag.testing.scheduleOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #92 (C19–C24; rows 25–32 with 28b; R92-1, R92-2) — the replace triad over the production router, handlers and
 * serializers, on a Room-backed [FakeGraph]. `GET …/replace-offer` is `ReplaceAsset.offer`; `POST …/replace-plan` is
 * `ReplaceAsset.plan` with its problems as C2 codes and the digest; `POST …/replace` is the one atomic write behind
 * that digest. The offer and the plan write nothing; tags move only by binding id, as a retarget. Every name is
 * fictional; today is the fake graph's 2026-02-10.
 */
class ReplaceRoutesTest {

    private val graphs = mutableListOf<FakeGraph>()
    private val graph = fresh()
    private val api = V1Client(graph)

    @After fun close() = graphs.forEach { it.close() }

    private fun fresh(): FakeGraph = FakeGraph().also { graphs += it }

    // --- fixtures -----------------------------------------------------------------------------------------------

    /**
     * The pool pump `p1`: under `h1`, a calendar season, notes, a meter definition `d1`, a profile `pr1`; a time
     * schedule `s-time`, a meter schedule `s-meter`, a PRE_SERVICE one `s-pre`; the group `g1`; the tag `t1`; the
     * component `c1`.
     */
    private fun pump(g: FakeGraph) = runBlocking {
        g.assets.upsert(assetRow("h1", name = "Example Pool House"))
        g.assets.upsert(
            assetRow(
                "p1", name = "Sample Pool Pump", parent = "h1",
                seasonMode = SeasonMode.CALENDAR, seasonStart = "05-01", seasonEnd = "09-30",
            ).copy(category = "Pump", location = "Back yard", description = "Runs daily", notes = "Check the basket"),
        )
        g.assets.upsert(assetRow("c1", name = "Example Pump Motor", parent = "p1"))
        g.definitions.upsert(meterDefinitionOf("d1", "p1"))
        g.profiles.upsert(profile("pr1", "p1"))
        g.schedules.upsert(scheduleOf("s-time", assetId = "p1", title = "Clean the basket"))
        g.schedules.upsert(
            scheduleOf(
                "s-meter", assetId = "p1", title = "Change the seal",
                timeInterval = null, timeUnit = null, anchorOn = null, meterDefinitionId = "d1", meterInterval = 500.0,
            ),
        )
        g.schedules.upsert(
            scheduleOf(
                "s-pre", assetId = "p1", title = "Open for the season",
                servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = 7,
            ),
        )
        g.groups.upsert(groupOf("g1", name = "Backyard", members = listOf(Triple("p1", "2026-01-01", null))))
        g.tags.upsert(tag("t1", "p1", label = "Under the lid"))
    }

    /** The garage door opener `m1`: MANUAL. */
    private fun opener(g: FakeGraph) = runBlocking {
        g.assets.upsert(assetRow("m1", name = "Example Garage Door Opener", seasonMode = SeasonMode.MANUAL))
    }

    private fun tag(id: String, asset: String, label: String? = null) = TagBinding(
        id = TagId(id), payloadFormat = PayloadFormat.V1, payloadKey = "TEST-$id",
        target = TagTarget.AssetTarget(AssetId(asset)), label = label, createdAt = 1L, updatedAt = 1L,
    )

    private fun profile(id: String, asset: String) = EventProfile(
        id = ProfileId(id), assetId = AssetId(asset), name = "Water test", eventKind = EventKind.INSPECTION,
        defaultTitle = "Water test", templateKey = null, sortOrder = 0, archivedAt = null,
        createdAt = dayMillis("2026-01-01"), updatedAt = dayMillis("2026-01-01"), fields = emptyList(),
        consumables = emptyList(),
    )

    private fun held(asset: String) = TransferRecord(
        id = "out-$asset", assetId = AssetId(asset), kind = TransferKind.OUT, packId = "0f1e2d3c-pack",
        lineage = emptyList(), at = 1_790_510_400_000L, packSha256 = "ab".repeat(32),
        nameSnapshot = "Sample Pool Pump", note = "",
    )

    private fun draft(
        name: String = "Sample Pool Pump (new)",
        retiredOn: String? = "2026-02-01",
        scheduleIds: List<String> = emptyList(),
        groupIds: List<String> = emptyList(),
        movedTagIds: List<String> = emptyList(),
        scheduleStartOn: String? = null,
        carrySeason: Boolean = false,
        carrySetup: Boolean = false,
        carryNotes: Boolean = false,
        manualPhase: String? = null,
    ) = ReplaceDraftRequest(
        retiredOn = retiredOn,
        successor = ReplaceSuccessorRequest(name = name),
        carrySeason = carrySeason,
        carrySetup = carrySetup,
        carryNotes = carryNotes,
        manualPhase = manualPhase,
        scheduleIds = scheduleIds,
        groupIds = groupIds,
        scheduleStartOn = scheduleStartOn,
        movedTagIds = movedTagIds,
    )

    /** Every item the pump offers but set-up and the season, each ticked, with its start date. */
    private fun cleanDraft() = draft(
        scheduleIds = listOf("s-time"), scheduleStartOn = "2026-02-10", groupIds = listOf("g1"),
        movedTagIds = listOf("t1"), carryNotes = true,
    )

    private fun encode(body: ReplaceDraftRequest) = ApiJson.encodeToString(ReplaceDraftRequest.serializer(), body)

    private fun planOf(asset: String, body: ReplaceDraftRequest, client: V1Client = api): ReplacePlanResponse =
        client.ok(ReplacePlanResponse.serializer(), "POST", "/v1/assets/$asset/replace-plan", encode(body))

    private fun apply(asset: String, body: ReplaceDraftRequest, digest: String, client: V1Client = api): ApiResponse =
        client.call("POST", "/v1/assets/$asset/replace", encode(body.copy(sourcesDigest = digest)))

    private fun snapshot(g: FakeGraph) = runBlocking { g.uow.read { readSnapshot(g.backupRepositories) } }

    // --- row 25, C19: the offer ---------------------------------------------------------------------------------

    @Test fun theOfferIsTheUseCasesOffer() {
        pump(graph)
        runBlocking {
            graph.schedules.upsert(scheduleOf("s-old", assetId = "p1", title = "Drain the lines", status = ScheduleStatus.ARCHIVED))
            graph.assets.upsert(assetRow("x1", name = "Example Lent Heater"))
            graph.groups.upsert(
                groupOf("g-held", name = "Shared run", members = listOf(Triple("p1", "2026-01-01", null), Triple("x1", "2026-01-01", null))),
            )
            graph.transferRecords.append(held("x1"))
            graph.tags.upsert(tag("t-lost", "p1").copy(status = TagStatus.LOST))
        }
        val before = snapshot(graph)
        val commits = graph.commits

        val offer = api.ok(ReplaceOfferResponse.serializer(), "GET", "/v1/assets/p1/replace-offer")

        assertEquals(runBlocking { graph.replaceAsset.offer(AssetId("p1")) }.toResponse(), offer)
        assertEquals("never an ARCHIVED schedule", listOf("s-meter", "s-time", "s-pre"), offer.schedules.map { it.id })
        assertEquals("never a group with a held member", listOf("g1"), offer.groups.map { it.id })
        assertEquals("only ACTIVE bindings", listOf("t1"), offer.tags.map { it.id })
        assertEquals("never itself, a descendant or a held asset", listOf("h1"), offer.parentChoiceIds)
        assertEquals(ReplacePrefill("Sample Pool Pump", "Pump", "Back yard", "h1"), offer.prefill)
        assertEquals(listOf("Example Pump Motor"), offer.childNames)
        assertEquals("p1", offer.predecessor.id)
        assertTrue(offer.eligible && !offer.held && offer.replacedBy == null && !offer.openLoan)
        assertTrue(offer.setupOffered && offer.seasonOffered && offer.notesOffered)
        assertEquals("the offer writes nothing", commits, graph.commits)
        assertEquals(before, snapshot(graph))
    }

    @Test fun anUnknownAssetIs404OnEveryRouteAndAnUntakenVerbIs404() {
        for ((method, path) in listOf(
            "GET" to "/v1/assets/nope/replace-offer",
            "POST" to "/v1/assets/nope/replace-plan",
        )) {
            val response = api.call(method, path, if (method == "GET") "" else encode(draft()))
            assertEquals("$method $path: ${response.bodyText()}", 404, response.status)
            assertEquals("no_such_asset", response.errorDetail().code)
        }
        val missing = apply("nope", draft(), "0".repeat(64))
        assertEquals(404, missing.status)
        assertEquals("no_such_asset", missing.errorDetail().code)

        pump(graph)
        for ((method, path) in listOf(
            "POST" to "/v1/assets/p1/replace-offer",
            "GET" to "/v1/assets/p1/replace-plan",
            "GET" to "/v1/assets/p1/replace",
            "DELETE" to "/v1/assets/p1/replace",
            "PATCH" to "/v1/assets/p1/replace",
        )) {
            val response = api.call(method, path, if (method == "POST" || method == "PATCH") encode(draft()) else "")
            assertEquals("$method $path: ${response.bodyText()}", 404, response.status)
            assertEquals("not_found", response.errorDetail().code)
        }
        assertTrue(runBlocking { graph.assetSuccessions.all() }.isEmpty())
    }

    // --- row 26, C20: the strict successor ----------------------------------------------------------------------

    @Test fun aDescriptionTemplateKeyOrSeasonPairInTheSuccessorIs400() {
        pump(graph)
        val before = snapshot(graph)
        val commits = graph.commits
        val zero = "0".repeat(64)

        for (extra in listOf(
            "\"description\":\"Runs daily\"",
            "\"notes\":\"Check the basket\"",
            "\"templateKey\":\"pool_pump\"",
            "\"seasonStartMmdd\":\"05-01\",\"seasonEndMmdd\":\"09-30\"",
        )) {
            for (route in listOf("replace-plan", "replace")) {
                val digest = if (route == "replace") ",\"sourcesDigest\":\"$zero\"" else ""
                val body = """{"retiredOn":"2026-02-01","successor":{"name":"Sample Pool Pump (new)",$extra}$digest}"""
                val response = api.call("POST", "/v1/assets/p1/$route", body)
                assertEquals("$route $extra: ${response.bodyText()}", 400, response.status)
                assertEquals("bad_request", response.errorDetail().code)
            }
        }
        // The predecessor is the path's; the digest is replace's alone, and replace needs one.
        for ((route, body) in listOf(
            "replace-plan" to """{"predecessorId":"h1","retiredOn":"2026-02-01","successor":{"name":"Sample Pool Pump (new)"}}""",
            "replace-plan" to """{"retiredOn":"2026-02-01","successor":{"name":"Sample Pool Pump (new)"},"sourcesDigest":"$zero"}""",
            "replace" to """{"retiredOn":"2026-02-01","successor":{"name":"Sample Pool Pump (new)"}}""",
        )) {
            val response = api.call("POST", "/v1/assets/p1/$route", body)
            assertEquals("$route $body: ${response.bodyText()}", 400, response.status)
        }
        assertEquals("nothing was written", commits, graph.commits)
        assertEquals(before, snapshot(graph))
    }

    // --- row 27, C21: the plan writes nothing and maps every problem ---------------------------------------------

    @Test fun thePlanWritesNothingAndMapsEveryProblem() {
        pump(graph)
        opener(graph)
        val before = snapshot(graph)
        val commits = graph.commits

        val busy = ReplaceDraftRequest(
            retiredOn = "2026-03-01",
            successor = ReplaceSuccessorRequest(
                name = " ", purchaseOn = "someday", purchasePriceMinor = -100, currency = "USD", parentAssetId = "c1",
            ),
            scheduleIds = listOf("s-time", "s-meter", "s-pre", "s-none"),
        )
        val pumpPlan = planOf("p1", busy)
        val expected = runBlocking { graph.replaceAsset.plan(busy.toDraft(AssetId("p1"))) }.problems.map {
            val refusal = replaceProblemRefusal(it)
            ReplaceProblemDto(refusal.code, refusal.field, replaceProblemName(it))
        }
        assertEquals("the use case's problems, in its order", expected, pumpPlan.problems)
        assertEquals(
            setOf(
                ReplaceProblemDto("REPLACE_NAME_REQUIRED", "successor.name", "NameRequired"),
                ReplaceProblemDto("REPLACE_BAD_DATE", "successor.purchaseOn", "BadDate(field=purchaseOn)"),
                ReplaceProblemDto("asset_validation", "successor.purchasePriceMinor", "Successor(problem=NegativePrice)"),
                ReplaceProblemDto("REPLACE_NOT_OFFERED", null, "NotOffered(id=c1)"),
                ReplaceProblemDto("REPLACE_NOT_OFFERED", null, "NotOffered(id=s-none)"),
                ReplaceProblemDto("REPLACE_BAD_DATE", "scheduleStartOn", "BadDate(field=scheduleStartOn)"),
                ReplaceProblemDto("REPLACE_NEEDS_SETUP", "carrySetup", "NeedsSetup(scheduleId=s-meter)"),
                ReplaceProblemDto("REPLACE_NEEDS_SEASON", "carrySeason", "NeedsSeason(scheduleId=s-pre)"),
                ReplaceProblemDto("REPLACE_DATE_AFTER_TODAY", "retiredOn", "ReplacedOnAfterToday"),
            ),
            pumpPlan.problems.toSet(),
        )
        assertEquals("2026-03-01", pumpPlan.replacedOn)

        val openerPlan = planOf("m1", draft(retiredOn = null, carrySeason = true))
        assertEquals(
            listOf(
                ReplaceProblemDto("REPLACE_BAD_DATE", "retiredOn", "BadDate(field=retiredOn)"),
                ReplaceProblemDto("REPLACE_PHASE_REQUIRED", "manualPhase", "PhaseRequired"),
            ),
            openerPlan.problems,
        )

        val clean = planOf("p1", cleanDraft())
        assertTrue(clean.problems.isEmpty())
        assertTrue(clean.eligible)
        assertNull(clean.blockedBy)
        assertEquals("2026-02-01", clean.replacedOn)
        assertTrue(clean.sourcesDigest.matches(Regex("[0-9a-f]{64}")))

        assertEquals("a plan writes nothing", commits, graph.commits)
        assertEquals(before, snapshot(graph))
    }

    /** C24 and C2, the mapper directly: every problem its code and field; `Successor` the shipped family. */
    @Test fun everyReplaceProblemHasItsCode() {
        val cases = listOf(
            ReplaceProblem.NameRequired to ("REPLACE_NAME_REQUIRED" to "successor.name"),
            ReplaceProblem.BadDate("retiredOn") to ("REPLACE_BAD_DATE" to "retiredOn"),
            ReplaceProblem.BadDate("scheduleStartOn") to ("REPLACE_BAD_DATE" to "scheduleStartOn"),
            ReplaceProblem.BadDate("inServiceOn") to ("REPLACE_BAD_DATE" to "successor.inServiceOn"),
            ReplaceProblem.BadDate("warrantyExpiresOn") to ("REPLACE_BAD_DATE" to "successor.warrantyExpiresOn"),
            ReplaceProblem.Successor(AssetProblem.NegativePrice) to ("asset_validation" to "successor.purchasePriceMinor"),
            ReplaceProblem.NotOffered("g9") to ("REPLACE_NOT_OFFERED" to null),
            ReplaceProblem.NeedsSetup(ScheduleId("s1")) to ("REPLACE_NEEDS_SETUP" to "carrySetup"),
            ReplaceProblem.NeedsSeason(ScheduleId("s1")) to ("REPLACE_NEEDS_SEASON" to "carrySeason"),
            ReplaceProblem.PhaseRequired to ("REPLACE_PHASE_REQUIRED" to "manualPhase"),
            ReplaceProblem.ReplacedOnAfterToday to ("REPLACE_DATE_AFTER_TODAY" to "retiredOn"),
        )
        for ((problem, want) in cases) {
            val refusal = replaceProblemRefusal(problem)
            assertEquals("$problem", want, refusal.code to refusal.field)
        }
    }

    // --- row 28, C22: the apply ---------------------------------------------------------------------------------

    @Test fun anApplyRetiresCreatesMovesAndRecordsOneSuccession() {
        pump(graph)
        val body = cleanDraft()
        val plan = planOf("p1", body)
        val commits = graph.commits

        val response = apply("p1", body, plan.sourcesDigest)

        assertEquals(response.bodyText(), 201, response.status)
        assertEquals("one write", commits + 1, graph.commits)
        val answer = ApiJson.decodeFromString(ReplaceResponse.serializer(), response.bodyText())
        val newId = AssetId(answer.successor.id)
        runBlocking {
            val old = graph.assets.get(AssetId("p1"))!!
            assertEquals("retired with the replacement date", "2026-02-01", old.retiredOn)
            val successor = graph.assets.get(newId)!!
            assertEquals(successor.toDto(), answer.successor)
            assertEquals("Sample Pool Pump (new)", successor.name)
            assertEquals("Runs daily" to "Check the basket", successor.description to successor.notes)
            val copies = graph.schedules.forAsset(newId)
            assertEquals(listOf("Clean the basket"), copies.map { it.title })
            assertEquals("the reviewed anchor", "2026-02-10", copies.single().anchorOn)
            val windows = graph.groups.get(GroupId("g1"))!!.members.filter { it.assetId == newId && it.removedAt == null }
            assertEquals("one group window", 1, windows.size)
            assertEquals(TagTarget.AssetTarget(newId), graph.tags.get(TagId("t1"))!!.target)
            val row = graph.assetSuccessions.all().single()
            assertEquals(row.toDto(), answer.succession)
            assertEquals(Triple(AssetId("p1"), newId, "2026-02-01"), Triple(row.predecessorAssetId, row.successorAssetId, row.replacedOn))
        }

        // A replay after success: the predecessor is replaced now, so never a second successor.
        val replay = apply("p1", body, plan.sourcesDigest)
        assertEquals(replay.bodyText(), 409, replay.status)
        assertEquals("ASSET_ALREADY_REPLACED", replay.errorDetail().code)
        assertEquals(listOf("ReplacedBy(successorAssetId=${newId.value})"), replay.errorDetail().problems)
        assertEquals(1, runBlocking { graph.assetSuccessions.all() }.size)
        assertEquals(commits + 1, graph.commits)
        val after = planOf("p1", body)
        assertFalse(after.eligible)
        assertEquals("ASSET_ALREADY_REPLACED", after.blockedBy)
    }

    // --- row 28b, C22 R92-2: only the selected bindings, a metadata retarget ------------------------------------

    @Test fun onlyTheSelectedBindingsMoveAsAMetadataRetarget() {
        pump(graph)
        runBlocking {
            graph.tags.upsert(
                tag("t1", "p1", label = "Under the lid").copy(
                    physicalUid = "04:A1:B2:C3:D4:E5:F6", writtenAt = dayMillis("2026-01-05"),
                    lastScannedAt = dayMillis("2026-02-01"), createdAt = dayMillis("2026-01-05"), updatedAt = dayMillis("2026-01-05"),
                ),
            )
            graph.tags.upsert(
                tag("t2", "p1", label = "On the pipe").copy(
                    payloadFormat = PayloadFormat.V1, physicalUid = "04:11:22:33:44:55:66", writtenAt = dayMillis("2026-01-06"),
                    lastScannedAt = dayMillis("2026-02-02"), createdAt = dayMillis("2026-01-06"), updatedAt = dayMillis("2026-01-06"),
                ),
            )
        }
        val t1Before = runBlocking { graph.tags.get(TagId("t1"))!! }
        val t2Before = runBlocking { graph.tags.get(TagId("t2"))!! }
        graph.now = dayMillis("2026-02-10")
        val body = draft(movedTagIds = listOf("t2"))
        val plan = planOf("p1", body)

        val response = apply("p1", body, plan.sourcesDigest)

        assertEquals(response.bodyText(), 201, response.status)
        val newId = AssetId(ApiJson.decodeFromString(ReplaceResponse.serializer(), response.bodyText()).successor.id)
        val t1 = runBlocking { graph.tags.get(TagId("t1"))!! }
        val t2 = runBlocking { graph.tags.get(TagId("t2"))!! }
        assertEquals("the unselected binding stays, byte-equal", t1Before, t1)
        assertEquals(
            "id, payload, label, physicalUid, writtenAt, lastScannedAt and createdAt kept; only the target and updatedAt move",
            t2Before.copy(target = TagTarget.AssetTarget(newId), updatedAt = dayMillis("2026-02-10")),
            t2,
        )
    }

    @Test fun anAllOrLabelFormIs400() {
        pump(graph)
        val before = snapshot(graph)
        val commits = graph.commits
        val zero = "0".repeat(64)
        for (form in listOf(
            "\"movedTagIds\":\"all\"",
            "\"movedTagIds\":true",
            "\"movedTagIds\":[{\"label\":\"Under the lid\"}]",
            "\"moveAllTags\":true",
            "\"movedTagLabels\":[\"Under the lid\"]",
            "\"movedTagPattern\":\"TEST-*\"",
        )) {
            for (route in listOf("replace-plan", "replace")) {
                val digest = if (route == "replace") ",\"sourcesDigest\":\"$zero\"" else ""
                val body = """{"retiredOn":"2026-02-01","successor":{"name":"Sample Pool Pump (new)"},$form$digest}"""
                val response = api.call("POST", "/v1/assets/p1/$route", body)
                assertEquals("$route $form: ${response.bodyText()}", 400, response.status)
                assertEquals("bad_request", response.errorDetail().code)
            }
        }
        // A wildcard is only an id, and no binding has it.
        val wildcard = planOf("p1", draft(movedTagIds = listOf("*")))
        assertEquals(listOf(ReplaceProblemDto("REPLACE_NOT_OFFERED", null, "NotOffered(id=*)")), wildcard.problems)
        assertEquals(commits, graph.commits)
        assertEquals(before, snapshot(graph))
    }

    // --- row 29, C22: stale -------------------------------------------------------------------------------------

    @Test fun aChangeBetweenPlanAndApplyIs409StaleAndWritesNothing() {
        val cases: List<Pair<ReplaceDraftRequest, suspend (FakeGraph) -> Unit>> = listOf(
            draft(scheduleIds = listOf("s-time"), scheduleStartOn = "2026-02-10") to { g ->
                g.schedules.upsert(g.schedules.get(ScheduleId("s-time"))!!.copy(title = "Clean the basket weekly"))
            },
            draft(movedTagIds = listOf("t1")) to { g ->
                g.tags.upsert(g.tags.get(TagId("t1"))!!.copy(lastScannedAt = dayMillis("2026-02-09")))
            },
            draft(carrySetup = true) to { g ->
                g.definitions.upsert(g.definitions.get(DefinitionId("d1"))!!.copy(archivedAt = dayMillis("2026-02-09")))
            },
        )
        for ((index, case) in cases.withIndex()) {
            val (body, change) = case
            val g = fresh()
            val client = V1Client(g)
            pump(g)
            val plan = planOf("p1", body, client)
            assertTrue("case $index plans clean: ${plan.problems}", plan.problems.isEmpty())
            runBlocking { change(g) }
            val before = snapshot(g)
            val commits = g.commits

            val response = apply("p1", body, plan.sourcesDigest, client)

            assertEquals("case $index: ${response.bodyText()}", 409, response.status)
            assertEquals("REPLACE_STALE", response.errorDetail().code)
            assertEquals("sourcesDigest", response.errorDetail().field)
            assertEquals("case $index wrote nothing", commits, g.commits)
            assertEquals(before, snapshot(g))
        }
    }

    // --- row 30, C22: the order ---------------------------------------------------------------------------------

    @Test fun aHeldPredecessorIs409() {
        pump(graph)
        runBlocking { graph.transferRecords.append(held("p1")) }
        val body = draft(name = " ")
        val plan = planOf("p1", body)
        assertFalse(plan.eligible)
        assertEquals("asset_transferred_out", plan.blockedBy)
        assertTrue("a problem too, so the order shows", plan.problems.isNotEmpty())
        val commits = graph.commits

        val response = apply("p1", body, plan.sourcesDigest)

        assertEquals(response.bodyText(), 409, response.status)
        assertEquals("asset_transferred_out", response.errorDetail().code)
        assertEquals(commits, graph.commits)
    }

    @Test fun anAlreadyReplacedPredecessorIs409NamingItsSuccessor() {
        pump(graph)
        runBlocking {
            graph.assets.upsert(assetRow("z1", name = "Sample Pool Pump (2)"))
            graph.assetSuccessions.append(AssetSuccession("s-z", AssetId("p1"), AssetId("z1"), "2026-01-20", 1L))
        }
        val body = draft(name = " ")
        val plan = planOf("p1", body)
        assertEquals("ASSET_ALREADY_REPLACED", plan.blockedBy)
        assertTrue(plan.problems.isNotEmpty())
        val commits = graph.commits

        val response = apply("p1", body, plan.sourcesDigest)

        assertEquals(response.bodyText(), 409, response.status)
        assertEquals("ASSET_ALREADY_REPLACED", response.errorDetail().code)
        assertEquals(listOf("ReplacedBy(successorAssetId=z1)"), response.errorDetail().problems)
        assertEquals(commits, graph.commits)
    }

    @Test fun aFutureDateIs422() {
        pump(graph)
        val body = draft(retiredOn = "2026-03-01")
        val plan = planOf("p1", body)
        val commits = graph.commits

        val response = apply("p1", body, plan.sourcesDigest)

        assertEquals(response.bodyText(), 422, response.status)
        val error = response.errorDetail()
        assertEquals("REPLACE_DATE_AFTER_TODAY" to "retiredOn", error.code to error.field)
        assertEquals(listOf("ReplacedOnAfterToday"), error.problems)
        assertEquals(commits, graph.commits)
    }

    @Test fun aMissingPhaseIs422FieldManualPhase() {
        opener(graph)
        val body = draft(name = "Example Garage Door Opener (new)", carrySeason = true)
        val plan = planOf("m1", body)
        val commits = graph.commits

        val response = apply("m1", body, plan.sourcesDigest)

        assertEquals(response.bodyText(), 422, response.status)
        val error = response.errorDetail()
        assertEquals("REPLACE_PHASE_REQUIRED" to "manualPhase", error.code to error.field)
        assertEquals(listOf("PhaseRequired"), error.problems)
        assertEquals(commits, graph.commits)
    }

    // --- row 31, C24: the defence -------------------------------------------------------------------------------

    @Test fun replaceStaleIsA409FromTheMapper() {
        val response = mapDomainFailure(ReplaceStale(AssetId("p1")))
        assertEquals(409, response.status)
        val error = response.errorDetail()
        assertEquals("REPLACE_STALE" to "sourcesDigest", error.code to error.field)
        assertEquals(listOf("ReplaceStale(assetId=p1)"), error.problems)
    }

    // --- row 32, C23: the digest --------------------------------------------------------------------------------

    @Test fun theDigestIsStableAndMovesWithEachSourceClass() {
        pump(graph)
        val body = draft(carrySetup = true, scheduleIds = listOf("s-meter"), groupIds = listOf("g1"), movedTagIds = listOf("t1"))
        val first = planOf("p1", body)
        assertTrue(first.problems.isEmpty())
        assertEquals("the same draft over the same rows", first.sourcesDigest, planOf("p1", body).sourcesDigest)
        assertEquals(
            "the draft is canonical: an id list is a set",
            first.sourcesDigest,
            planOf("p1", body.copy(movedTagIds = listOf("t1", "t1"))).sourcesDigest,
        )

        val digests = mutableListOf(first.sourcesDigest)
        val changes: List<Pair<String, suspend () -> Unit>> = listOf(
            "the predecessor" to { graph.assets.upsert(graph.assets.get(AssetId("p1"))!!.copy(location = "Side yard")) },
            "a ticked schedule" to {
                graph.schedules.upsert(graph.schedules.get(ScheduleId("s-meter"))!!.copy(meterInterval = 600.0))
            },
            "a ticked group" to { graph.groups.upsert(graph.groups.get(GroupId("g1"))!!.copy(description = "By the fence")) },
            "a moved tag" to { graph.tags.upsert(graph.tags.get(TagId("t1"))!!.copy(lastScannedAt = dayMillis("2026-02-09"))) },
            "a definition" to { graph.definitions.upsert(graph.definitions.get(DefinitionId("d1"))!!.copy(label = "Run hours")) },
            "a profile" to { graph.profiles.upsert(graph.profiles.get(ProfileId("pr1"))!!.copy(name = "Chlorine test")) },
            "the successor row" to {
                graph.assets.upsert(assetRow("z1", name = "Sample Pool Pump (2)"))
                graph.assetSuccessions.append(AssetSuccession("s-z", AssetId("p1"), AssetId("z1"), "2026-01-20", 1L))
            },
        )
        for ((what, change) in changes) {
            runBlocking { change() }
            val digest = planOf("p1", body).sourcesDigest
            assertFalse("$what moved the digest", digest in digests)
            digests += digest
        }
    }

    @Test fun aDraftOtherThanThePlannedOneIs409Stale() {
        pump(graph)
        val base = draft(scheduleIds = listOf("s-time"), scheduleStartOn = "2026-02-10")
        val plan = planOf("p1", base)
        assertTrue(plan.problems.isEmpty())
        val before = snapshot(graph)
        val commits = graph.commits

        for (variant in listOf(
            base.copy(successor = base.successor.copy(name = "Sample Pool Pump (other)")),
            base.copy(carrySeason = true),
            base.copy(carryNotes = true),
            base.copy(manualPhase = "IN_SEASON"),
            base.copy(scheduleStartOn = "2026-02-09"),
        )) {
            assertNotEquals(base, variant)
            val response = apply("p1", variant, plan.sourcesDigest)
            assertEquals("$variant: ${response.bodyText()}", 409, response.status)
            assertEquals("REPLACE_STALE", response.errorDetail().code)
        }
        assertEquals(commits, graph.commits)
        assertEquals(before, snapshot(graph))
    }

    /** C23's premise, per source class: the archive row carries every field `ReplaceSources`' equality compares. */
    @Test fun theArchiveRowsRoundTripEveryFieldTheStaleRuleCompares() {
        val asset = assetRow(
            "p1", name = "Sample Pool Pump", parent = "h1", status = AssetStatus.ARCHIVED, retiredOn = "2026-01-31",
            seasonMode = SeasonMode.CALENDAR, seasonStart = "05-01", seasonEnd = "09-30", breakStart = "07-01",
            breakEnd = "07-15", aggregation = HealthAggregation.TRACK_ONE, primary = "hs1",
        ).copy(
            description = "Runs daily", category = "Pump", notes = "Check the basket", templateKey = "pool_pump",
            manufacturer = "Example Co", model = "X-1", serialNumber = "TEST-SN-1", purchaseOn = "2020-05-01",
            inServiceOn = "2020-05-02", purchasePriceMinor = 49_900L, currency = "USD", vendor = "Example Supply",
            location = "Back yard", warrantyExpiresOn = "2027-05-01", warrantyNotes = "Parts only",
            warrantyReminderLeadDays = 30, healthPrimarySubjectId = HealthSubjectId("hs1"),
        )
        assertEquals(asset, asset.toDto().toDomain())

        val succession = AssetSuccession("s1", AssetId("p0"), AssetId("p1"), "2026-01-31", 1_758_960_000_000L)
        assertEquals(succession, succession.toDto().toDomain())

        val schedule = scheduleOf(
            "s-meter", assetId = "p1", title = "Change the seal", meterDefinitionId = "d1", meterInterval = 500.0,
            anchorMeter = 1200.5, meterLead = 25.0, servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = 7,
            status = ScheduleStatus.PAUSED, postponedDueOn = "2026-03-01",
        ).copy(
            description = "Seal kit in the shed", profileId = ProfileId("pr1"), remindersEnabled = false,
            ruleChangedAt = dayMillis("2026-01-15"),
            providers = listOf(ScheduleProviderRow("LOCAL", enabled = true), ScheduleProviderRow("TODOIST", enabled = false)),
        )
        assertEquals(schedule, schedule.toDto().toDomain())

        val group = groupOf(
            "g1", name = "Backyard", archivedAt = dayMillis("2026-02-01"),
            members = listOf(Triple("p1", "2026-01-01", "2026-01-20"), Triple("h1", "2026-01-02", null)),
        ).copy(description = "By the fence")
        assertEquals(group, group.toDto().toDomain())

        val binding = tag("t1", "p1", label = "Under the lid").copy(
            status = TagStatus.ACTIVE, physicalUid = "04:A1:B2:C3:D4:E5:F6", writtenAt = 5L, lastScannedAt = 7L,
            createdAt = 3L, updatedAt = 9L,
        )
        assertEquals(binding, binding.toDto().toDomain())

        val entered = meterDefinitionOf("d1", "p1").copy(
            rangeLow = 0.5, rangeHigh = 9.5, decimals = 1, sortOrder = 2, archivedAt = dayMillis("2026-02-01"),
            valueType = ValueType.NUMBER,
        )
        assertEquals(entered, entered.toDto().toDomain())
        val derived = entered.copy(
            id = DefinitionId("d2"), key = "drop", isMeter = false, archivedAt = null, kind = DefinitionKind.DERIVED,
            derived = DerivedSpec(DerivedFormula.PERCENT_DROP, DefinitionId("d1"), DefinitionId("d3")),
        )
        assertEquals(derived, derived.toDto().toDomain())

        val action = profile("pr1", "p1").copy(
            templateKey = "pool_pump", sortOrder = 3, archivedAt = dayMillis("2026-02-01"),
            fields = listOf(ProfileField("pf1", DefinitionId("d1"), required = true, sortOrder = 0)),
            consumables = listOf(ProfileConsumable("pc1", "Seal kit", 1.5, "ea", 0)),
        )
        assertEquals(action, action.toDto().toDomain())
    }
}
