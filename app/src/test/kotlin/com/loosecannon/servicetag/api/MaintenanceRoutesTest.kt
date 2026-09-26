package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.PolicyPhase
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TerminationKind
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.usecase.AssetMembershipReferenced
import com.loosecannon.servicetag.core.usecase.CreateAsset
import com.loosecannon.servicetag.core.usecase.OccurrenceAlreadyComplete
import com.loosecannon.servicetag.core.usecase.SaveGroup
import com.loosecannon.servicetag.core.usecase.SaveSchedule
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The code this suite pairs with. Invented, and the only token these requests carry. */
private const val TOKEN = "ABCD2345"

/** The day the fake `Today` reports for every call below. */
private val TODAY: LocalDate = LocalDate.parse("2026-02-10")

private fun dayMillis(date: String): Long =
    LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * 1.2 (B12) — the nineteen `/v1` maintenance rows, over `FakeGraph`, on the JVM, with no socket and
 * no emulator. `ApiRouterTest`'s shape exactly: the production handlers, the production use cases
 * and the production serializers over an in-memory Room database.
 *
 * **Every write here goes through a use case** (invariant 17, and the brief's route → use case
 * table): nothing in this suite reaches a repository to make a schedule advance. The two places it
 * does write a row directly — the seeded `occurrence_closure` rows — are standing in for the one
 * thing a route cannot do, an **import** delivering a closure for a round that is not a
 * termination, which is the only state in which `OCCURRENCE_CLOSED` and `OCCURRENCE_ALREADY_CLOSED`
 * are reachable at all (`GroupCompletionTest`'s own note, and `CloseRoundTest`'s).
 *
 * The clock is moved explicitly wherever a membership window has to open and close on different
 * days: `removed_at` is stamped from it, and a window whose `removed_at` equals the round's open
 * instant does not cover that instant.
 */
class MaintenanceRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-01")
        today = TODAY
    }

    @After fun close() = graph.close()

    private fun router(): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments, graph.categories,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
            graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
            maintenanceHandlersFor(graph),
            referenceHandlersFor(graph),
            seasonHealthHandlersFor(graph),
            appVersion = "1.2.0",
            schemaVersion = AppGraph.SCHEMA_VERSION,
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

    private fun ApiResponse.problems(): List<String> =
        ApiJson.decodeFromString(ApiErrorBody.serializer(), text()).error.problems

    private fun groupIn(response: ApiResponse) =
        ApiJson.decodeFromString(GroupResponse.serializer(), response.text()).group

    private fun scheduleIn(response: ApiResponse) =
        ApiJson.decodeFromString(ScheduleResponse.serializer(), response.text()).schedule

    // --- fixtures ------------------------------------------------------------------------------

    private fun createAsset(name: String): String =
        ApiJson.decodeFromString(
            AssetResponse.serializer(),
            call("POST", "/v1/assets", """{"name":"$name","category":"Water"}""").text(),
        ).asset.id

    /** A group with the named assets as open members, through `SaveGroup`. */
    private fun createGroup(name: String, vararg assetIds: String): String {
        val members = assetIds.mapIndexed { i, id -> """{"assetId":"$id","sortOrder":$i}""" }
        val response = call(
            "POST", "/v1/groups",
            """{"name":"$name","members":[${members.joinToString(",")}]}""",
        )
        assertEquals(response.text(), 201, response.status)
        return groupIn(response).id
    }

    /** A monthly FIXED schedule on one asset, anchored on the day the fixtures were built. */
    private fun createAssetSchedule(assetId: String, title: String = "Filter change"): String {
        val response = call(
            "POST", "/v1/schedules",
            """{"title":"$title","targetAssetId":"$assetId","timeInterval":1,
               "timeUnit":"MONTH","timeBasis":"FIXED","anchorOn":"2026-02-01"}""",
        )
        assertEquals(response.text(), 201, response.status)
        return scheduleIn(response).id
    }

    /** A monthly FIXED schedule on one group — QUICK-only, no meter, no profile (D-12). */
    private fun createGroupSchedule(groupId: String, title: String = "Winterise"): String {
        val response = call(
            "POST", "/v1/schedules",
            """{"title":"$title","targetGroupId":"$groupId","timeInterval":1,
               "timeUnit":"MONTH","timeBasis":"FIXED","anchorOn":"2026-02-01"}""",
        )
        assertEquals(response.text(), 201, response.status)
        return scheduleIn(response).id
    }

    private fun completionBody(assetId: String? = null, occurredOn: String = "2026-02-10"): String {
        val member = assetId?.let { ""","assetId":"$it"""" } ?: ""
        return """{"occurredOn":"$occurredOn","tzId":"UTC"$member}"""
    }

    // --- one representative case per status class ----------------------------------------------

    /**
     * 201 on a create, 200 on a read, 404 for a row that is not there, 422 for a command describing
     * something that cannot exist, and 409 for a refusal about state — one assertion each, over the
     * new surface, because those five are what a client branches on.
     */
    @Test fun theFiveStatusClassesOverTheNewSurface() {
        val asset = createAsset("Hot tub")

        val created = call("POST", "/v1/groups", """{"name":"North run","members":[]}""")
        assertEquals(201, created.status)

        val read = call("GET", "/v1/groups/${groupIn(created).id}")
        assertEquals(200, read.status)
        assertEquals("North run", groupIn(read).name)

        assertEquals(404, call("GET", "/v1/schedules/00000000-0000-4000-8000-999999999999").status)
        assertEquals("NO_SUCH_SCHEDULE", call("GET", "/v1/schedules/00000000-0000-4000-8000-999999999999").code())

        // Neither target named: the command describes a schedule that cannot exist.
        val bad = call("POST", "/v1/schedules", """{"title":"Nowhere","timeInterval":1,"timeUnit":"MONTH","anchorOn":"2026-02-01"}""")
        assertEquals(422, bad.status)
        assertEquals("SCHEDULE_TARGET_INVALID", bad.code())

        // A completion aimed at an archived schedule: the row is fine and the store will not have it.
        val schedule = createAssetSchedule(asset)
        assertEquals(200, call("POST", "/v1/schedules/$schedule/archive", """{"archived":true}""").status)
        val refused = call("POST", "/v1/schedules/$schedule/complete", completionBody())
        assertEquals(409, refused.status)
        assertEquals("SCHEDULE_ARCHIVED", refused.code())
    }

    // --- the named refusals, one per code -------------------------------------------------------

    /**
     * `SCHEDULE_OCCURRENCE_TAKEN`: the member named has already been recorded for this round, so
     * the use case writes nothing — and a 201 carrying no event is not a representable answer.
     * Two members, so the first completion leaves the round open and the repeat lands on the very
     * same occurrence.
     */
    @Test fun aRepeatCompletionOfOneMemberIs409ScheduleOccurrenceTaken() {
        val a1 = createAsset("Pump A")
        val a2 = createAsset("Pump B")
        val schedule = createGroupSchedule(createGroup("North run", a1, a2))

        assertEquals(201, call("POST", "/v1/schedules/$schedule/complete", completionBody(a1)).status)
        val repeat = call("POST", "/v1/schedules/$schedule/complete", completionBody(a1))
        assertEquals(409, repeat.status)
        assertEquals("SCHEDULE_OCCURRENCE_TAKEN", repeat.code())
        runBlocking { assertEquals(1, graph.events.all().size) }
    }

    /**
     * `OCCURRENCE_CLOSED`: the round the caller would complete already carries a closure. The
     * closure is seeded the way an **import** delivers one — on a round that obliges nobody, and so
     * is not a termination — because a locally closed round always advances and is never the
     * current one (`GroupCompletionTest`'s note).
     */
    @Test fun completingAClosedRoundIs409OccurrenceClosed() {
        val a1 = createAsset("Pump A")
        val group = createGroup("North run", a1)
        val schedule = createGroupSchedule(group)

        // The member leaves, and finishing the round it was still required for opens an empty one.
        graph.now = dayMillis("2026-02-02")
        assertEquals(200, call("PATCH", "/v1/groups/$group", """{"name":"North run","members":[]}""").status)
        graph.now = dayMillis("2026-02-03")
        assertEquals(201, call("POST", "/v1/schedules/$schedule/complete", completionBody(a1)).status)

        val open = runBlocking {
            graph.recomputeSchedules.occurrenceOf(graph.schedules.get(ScheduleId(schedule))!!)!!
        }
        assertEquals(emptyList<AssetId>(), open.required)
        runBlocking {
            graph.closures.insert(
                OccurrenceClosure(
                    id = "00000000-0000-4000-8000-100000000001",
                    scheduleId = ScheduleId(schedule),
                    occurrenceOn = open.occurrenceOn.toString(),
                    closedOn = "2026-02-05",
                    createdAt = dayMillis("2026-02-05"),
                ),
            )
        }

        val refused = call("POST", "/v1/schedules/$schedule/complete", completionBody(a1))
        assertEquals(409, refused.status)
        assertEquals("OCCURRENCE_CLOSED", refused.code())
    }

    /** `CLOSE_NOT_SUPPORTED`: in 1.2 closing a round is offered on group targets only. */
    @Test fun closingAnAssetTargetedRoundIs409CloseNotSupported() {
        val schedule = createAssetSchedule(createAsset("Hot tub"))
        val refused = call("POST", "/v1/schedules/$schedule/close-round", "{}")
        assertEquals(409, refused.status)
        assertEquals("CLOSE_NOT_SUPPORTED", refused.code())
        runBlocking { assertEquals(emptyList<OccurrenceClosure>(), graph.closures.all()) }
    }

    /**
     * `OCCURRENCE_ALREADY_CLOSED`: the first closure stands and the second attempt is refused
     * (invariant 38). Reached the same way `OCCURRENCE_CLOSED` is — a closure on a round that is
     * not a termination — because closing a live round advances the schedule past it.
     */
    @Test fun closingTheSameRoundTwiceIs409OccurrenceAlreadyClosed() {
        val a1 = createAsset("Pump A")
        val group = createGroup("North run", a1)
        val schedule = createGroupSchedule(group)

        graph.now = dayMillis("2026-02-02")
        call("PATCH", "/v1/groups/$group", """{"name":"North run","members":[]}""")
        graph.now = dayMillis("2026-02-03")
        call("POST", "/v1/schedules/$schedule/complete", completionBody(a1))

        val open = runBlocking {
            graph.recomputeSchedules.occurrenceOf(graph.schedules.get(ScheduleId(schedule))!!)!!
        }
        runBlocking {
            graph.closures.insert(
                OccurrenceClosure(
                    id = "00000000-0000-4000-8000-100000000002",
                    scheduleId = ScheduleId(schedule),
                    occurrenceOn = open.occurrenceOn.toString(),
                    closedOn = "2026-02-05",
                    createdAt = dayMillis("2026-02-05"),
                ),
            )
        }

        val refused = call("POST", "/v1/schedules/$schedule/close-round", "{}")
        assertEquals(409, refused.status)
        assertEquals("OCCURRENCE_ALREADY_CLOSED", refused.code())
        runBlocking { assertEquals(1, graph.closures.all().size) }
    }

    /**
     * `OCCURRENCE_ALREADY_COMPLETE` is **unreachable through any route**: a completion that covers
     * the required set terminates the round and the engine always advances strictly past its own
     * key, so the open round is never one that is already complete (`CloseRoundTest`'s property
     * test). The refusal exists as defence in depth — a closure against a finished round would be a
     * false statement in exported history — so what this brief owes is the **mapping**: were the
     * domain ever to raise it, the wire says 409 and names it, not 500.
     */
    @Test fun occurrenceAlreadyCompleteMapsTo409ByName() {
        val response = mapDomainFailure(
            OccurrenceAlreadyComplete(ScheduleId("s1"), "2026-02-01"),
        )
        assertEquals(409, response.status)
        assertEquals(
            "OCCURRENCE_ALREADY_COMPLETE",
            ApiJson.decodeFromString(ApiErrorBody.serializer(), response.body.decodeToString()).error.code,
        )
    }

    /**
     * `ASSET_MEMBERSHIP_REFERENCED` maps to 409 by name.
     *
     * Invariant 8's refusal: a membership row may not be hard-deleted while a completion or a
     * closure references an occurrence its window covered, so `DeleteAsset` refuses an asset that
     * still holds one. **No 1.2 route calls `DeleteAsset`** — deleting an asset has no endpoint and
     * `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` asserts it — so this is defence in depth,
     * like the two completion-dispatch refusals: were the domain ever reached, the wire says 409 and
     * names it rather than 500.
     */
    @Test fun assetMembershipReferencedMapsTo409ByName() {
        val response = mapDomainFailure(
            AssetMembershipReferenced(AssetId("a1"), listOf(GroupId("g1"))),
        )
        assertEquals(409, response.status)
        assertEquals(
            "ASSET_MEMBERSHIP_REFERENCED",
            ApiJson.decodeFromString(ApiErrorBody.serializer(), response.body.decodeToString()).error.code,
        )
    }

    /**
     * 1.2.1: `OCCURRENCE_NOT_YET_OPEN` — the round has not reached its own due-soon window
     * (`effectiveDueOn - leadDays`) yet, and the refusal writes nothing.
     */
    @Test fun closingARoundBeforeItsDueSoonWindowIs409OccurrenceNotYetOpen() {
        val a1 = createAsset("Pump A")
        val group = createGroup("North run", a1)
        // due = anchorOn = "2026-03-01" (TODAY is 2026-02-10, well behind it); leadDays = 5 puts the
        // window's open date at 2026-02-24, still ahead of TODAY.
        val response = call(
            "POST", "/v1/schedules",
            """{"title":"Winterise","targetGroupId":"$group","timeInterval":1,
               "timeUnit":"MONTH","timeBasis":"FIXED","anchorOn":"2026-03-01","leadDays":5}""",
        )
        assertEquals(response.text(), 201, response.status)
        val schedule = scheduleIn(response).id

        val refused = call("POST", "/v1/schedules/$schedule/close-round", "{}")
        assertEquals(409, refused.status)
        assertEquals("OCCURRENCE_NOT_YET_OPEN", refused.code())
        // Both fields are the payload: with no occurrence key on the request, `occurrenceOn` is the
        // only way a retrying caller can tell "my first call succeeded" from "it was never due".
        assertTrue(
            refused.problems().toString(),
            refused.problems().any { it.contains("occurrenceOn=2026-03-01") && it.contains("opensOn=2026-02-24") },
        )
        runBlocking { assertEquals(emptyList<OccurrenceClosure>(), graph.closures.all()) }
    }

    /** `OCCURRENCE_NOT_CLOSEABLE`: a round that obliges nobody is not a round (invariant 77). */
    @Test fun closingARoundThatObligesNobodyIs409OccurrenceNotCloseable() {
        val a1 = createAsset("Pump A")
        val group = createGroup("North run", a1)
        val schedule = createGroupSchedule(group)

        graph.now = dayMillis("2026-02-02")
        call("PATCH", "/v1/groups/$group", """{"name":"North run","members":[]}""")
        graph.now = dayMillis("2026-02-03")
        call("POST", "/v1/schedules/$schedule/complete", completionBody(a1))

        val refused = call("POST", "/v1/schedules/$schedule/close-round", "{}")
        assertEquals(409, refused.status)
        assertEquals("OCCURRENCE_NOT_CLOSEABLE", refused.code())
        runBlocking { assertEquals(emptyList<OccurrenceClosure>(), graph.closures.all()) }
    }

    /**
     * `COMPLETION_MEMBER_REQUIRED`: **the one validation this route owns.**
     *
     * `CompleteGroupMembers` cannot raise it — an empty member list is legal to it and answers
     * "nothing to record" — so a body with no `assetId` on a group target would otherwise be
     * indistinguishable from a repeat, and answer 409 for the wrong reason. §9.2 makes `assetId`
     * required there, and this is the route saying so.
     */
    @Test fun aGroupCompletionWithNoMemberNamedIs422CompletionMemberRequired() {
        val a1 = createAsset("Pump A")
        val schedule = createGroupSchedule(createGroup("North run", a1))

        val refused = call("POST", "/v1/schedules/$schedule/complete", completionBody())
        assertEquals(422, refused.status)
        assertEquals("COMPLETION_MEMBER_REQUIRED", refused.code())
        runBlocking { assertEquals(emptyList<Any>(), graph.events.all()) }

        // And naming the member is all it takes for the very same body to be accepted.
        assertEquals(201, call("POST", "/v1/schedules/$schedule/complete", completionBody(a1)).status)
    }

    /**
     * `OCCURRENCE_NOT_ACTIONABLE`: completing a round that obliges nobody.
     *
     * Reachable at the wire, not defence in depth — `CompleteGroupMembers.openRound` raises it
     * before any member check, so it is the answer for the very state
     * `closingARoundThatObligesNobodyIs409OccurrenceNotCloseable` builds: the member left, the round
     * it was still required for was finished, and the round that opened after obliges nobody.
     * Emptiness is never completeness, and it is never actionability either (invariants 74, 77).
     */
    @Test fun completingARoundThatObligesNobodyIs409OccurrenceNotActionable() {
        val a1 = createAsset("Pump A")
        val group = createGroup("North run", a1)
        val schedule = createGroupSchedule(group)

        graph.now = dayMillis("2026-02-02")
        call("PATCH", "/v1/groups/$group", """{"name":"North run","members":[]}""")
        graph.now = dayMillis("2026-02-03")
        assertEquals(201, call("POST", "/v1/schedules/$schedule/complete", completionBody(a1)).status)

        val before = runBlocking { graph.events.all().size }
        val refused = call("POST", "/v1/schedules/$schedule/complete", completionBody(a1))
        assertEquals(409, refused.status)
        assertEquals("OCCURRENCE_NOT_ACTIONABLE", refused.code())
        runBlocking { assertEquals(before, graph.events.all().size) }
    }

    /** `MEMBER_ALREADY_OPEN`: an add for an asset that already holds an open window (invariant 80). */
    @Test fun addingAMemberThatIsAlreadyOpenIs422MemberAlreadyOpen() {
        val a1 = createAsset("Pump A")
        val group = createGroup("North run", a1)

        val refused = call(
            "PATCH", "/v1/groups/$group",
            """{"name":"North run","members":[{"assetId":"$a1","sortOrder":0}]}""",
        )
        assertEquals(422, refused.status)
        assertEquals("MEMBER_ALREADY_OPEN", refused.code())
        assertTrue(refused.problems().toString(), refused.problems().any { it.contains("MemberAlreadyOpen") })
        runBlocking { assertEquals(1, graph.groups.get(GroupId(group))!!.members.size) }
    }

    /** A group-targeted schedule on an **empty group** is refused at the command (invariants 74, 77). */
    @Test fun aGroupTargetedScheduleOnAnEmptyGroupIs422() {
        val group = createGroup("Nobody")
        val refused = call(
            "POST", "/v1/schedules",
            """{"title":"Winterise","targetGroupId":"$group","timeInterval":1,
               "timeUnit":"MONTH","timeBasis":"FIXED","anchorOn":"2026-02-01"}""",
        )
        assertEquals(422, refused.status)
        assertEquals("EMPTY_GROUP_TARGET", refused.code())
        runBlocking { assertEquals(emptyList<Any>(), graph.schedules.all()) }
    }

    /**
     * `CLOSED_ON_OUT_OF_RANGE` at the wire, with both ends of the range and both neighbours: the
     * round's **open date** and **today** are accepted, the day before and tomorrow are each 422,
     * and omitting `closedOn` defaults to today (invariant 78).
     */
    @Test fun closedOnIsBoundedByTheOpenDateAndToday() {
        val a1 = createAsset("Pump A")
        val schedule = createGroupSchedule(createGroup("North run", a1))

        for (outside in listOf("2026-01-31", "2026-02-11")) {
            val refused = call("POST", "/v1/schedules/$schedule/close-round", """{"closedOn":"$outside"}""")
            assertEquals(outside, 422, refused.status)
            assertEquals(outside, "CLOSED_ON_OUT_OF_RANGE", refused.code())
        }
        runBlocking { assertEquals(emptyList<OccurrenceClosure>(), graph.closures.all()) }

        val open = call("POST", "/v1/schedules/$schedule/close-round", """{"closedOn":"2026-02-01"}""")
        assertEquals(201, open.status)
        assertEquals(
            "2026-02-01",
            ApiJson.decodeFromString(CloseRoundResponse.serializer(), open.text()).closure.closedOn,
        )

        // The next round: today is accepted, and so is omitting the field entirely.
        val a2 = createAsset("Pump B")
        val other = createGroupSchedule(createGroup("South run", a2), title = "Drain")
        val today = call("POST", "/v1/schedules/$other/close-round", """{"closedOn":"2026-02-10"}""")
        assertEquals(201, today.status)

        val a3 = createAsset("Pump C")
        val third = createGroupSchedule(createGroup("East run", a3), title = "Flush")
        val defaulted = call("POST", "/v1/schedules/$third/close-round", "{}")
        assertEquals(201, defaulted.status)
        assertEquals(
            TODAY.toString(),
            ApiJson.decodeFromString(CloseRoundResponse.serializer(), defaulted.text()).closure.closedOn,
        )
    }

    // --- 404 / 405 / 415, parameterised ---------------------------------------------------------

    /**
     * The shipped shape, over the new paths: a wrong verb on a top-level path is a **405**, a wrong
     * verb on an `/v1/…/{id}/…` sub-resource is a **404** (the sub-resource's own name is the route,
     * and a verb it does not take is a path that does not exist), and a body of the wrong type is a
     * **415**. One parameterised assertion rather than one per path.
     */
    @Test fun theNewPathsAnswer405And404And415TheWayTheShippedOnesDo() {
        val asset = createAsset("Hot tub")
        val schedule = createAssetSchedule(asset)
        val group = createGroup("North run", asset)

        for ((method, path) in listOf(
            "DELETE" to "/v1/groups",
            "PATCH" to "/v1/groups",
            "DELETE" to "/v1/groups/$group",
            "POST" to "/v1/groups/$group",
            "DELETE" to "/v1/schedules",
            "PATCH" to "/v1/schedules",
            "DELETE" to "/v1/schedules/$schedule",
            "POST" to "/v1/schedules/$schedule",
            "POST" to "/v1/due",
            "DELETE" to "/v1/due",
        )) {
            val response = call(method, path, if (method == "GET") "" else "{}")
            assertEquals("$method $path", 405, response.status)
            assertEquals("$method $path", "method_not_allowed", response.code())
        }

        for ((method, path) in listOf(
            "GET" to "/v1/groups/$group/archive",
            "POST" to "/v1/groups/$group/schedules",
            "POST" to "/v1/assets/$asset/groups",
            "POST" to "/v1/assets/$asset/schedules",
            "GET" to "/v1/schedules/$schedule/pause",
            "GET" to "/v1/schedules/$schedule/complete",
            "GET" to "/v1/schedules/$schedule/close-round",
            "POST" to "/v1/schedules/$schedule/closures",
        )) {
            val response = call(method, path, if (method == "GET") "" else "{}")
            assertEquals("$method $path", 404, response.status)
            assertEquals("$method $path", "not_found", response.code())
        }

        val wrongType = call("POST", "/v1/groups", """{"name":"x"}""", contentType = "text/plain")
        assertEquals(415, wrongType.status)
        assertEquals("unsupported_media_type", wrongType.code())
    }

    // --- nothing destructive was added ----------------------------------------------------------

    /**
     * Invariants 43 and 76 at the wire: no delete, no closure amendment, no snooze. Each of these
     * answers 404 or 405 because **nothing routes to it**.
     */
    @Test fun theNewSurfaceAddsNothingDestructive() {
        val asset = createAsset("Hot tub")
        val schedule = createAssetSchedule(asset)
        val group = createGroup("North run", asset)

        for ((method, path) in listOf(
            "DELETE" to "/v1/schedules/$schedule",
            "DELETE" to "/v1/groups/$group",
            "DELETE" to "/v1/schedules/$schedule/closures",
            "PATCH" to "/v1/schedules/$schedule/closures",
            "DELETE" to "/v1/schedules/$schedule/closures/c1",
            "POST" to "/v1/schedules/$schedule/snooze",
            "POST" to "/v1/snooze",
            "DELETE" to "/v1/groups/$group/members/m1",
            "DELETE" to "/v1/due",
        )) {
            val response = call(method, path, if (method == "GET") "" else "{}")
            assertTrue(
                "$method $path answered ${response.status}",
                response.status == 404 || response.status == 405,
            )
        }
    }

    // --- full replace, not partial --------------------------------------------------------------

    /** A `PATCH /v1/schedules/{id}` that omits an optional field **clears** it. */
    @Test fun aScheduleTweakThatOmitsAFieldClearsIt() {
        val asset = createAsset("Hot tub")
        val created = call(
            "POST", "/v1/schedules",
            """{"title":"Filter change","description":"every month","targetAssetId":"$asset",
               "timeInterval":1,"timeUnit":"MONTH","timeBasis":"FIXED","anchorOn":"2026-02-01",
               "leadDays":7}""",
        )
        assertEquals(201, created.status)
        val id = scheduleIn(created).id
        assertEquals("every month", scheduleIn(created).description)
        assertEquals(7, scheduleIn(created).leadDays)

        val patched = call(
            "PATCH", "/v1/schedules/$id",
            """{"title":"Filter change","targetAssetId":"$asset","timeInterval":1,
               "timeUnit":"MONTH","timeBasis":"FIXED","anchorOn":"2026-02-01"}""",
        )
        assertEquals(200, patched.status)
        assertEquals("", scheduleIn(patched).description)
        assertEquals(0, scheduleIn(patched).leadDays)
    }

    /**
     * A 1.3 client's create still works (Q-9): a legacy body with `seasonBehavior: FOLLOW_ASSET` and
     * no re-entry is translated through the legacy mapping, and the row reads back as the
     * normalised triple `FOLLOW_ASSET` / `AT_START` / 0. Sending that triple back is a no-op: the
     * policy, its offset and the pin's floor stay where they were, and only `updatedAt` moves.
     */
    @Test fun aLegacyFollowAssetCreateReadsBackANormalisedTriple() {
        val asset = createAsset("Snowblower")
        val legacy = """{"title":"Auger belt","targetAssetId":"$asset","timeInterval":1,
            "timeUnit":"YEAR","timeBasis":"FIXED","anchorOn":"2026-02-01",
            "seasonBehavior":"FOLLOW_ASSET"}"""
        val created = call("POST", "/v1/schedules", legacy)
        assertEquals(created.text(), 201, created.status)
        val id = scheduleIn(created).id

        fun detail() = ApiJson.decodeFromString(
            ScheduleDetailResponse.serializer(), call("GET", "/v1/schedules/$id").text(),
        ).schedule
        val read = detail()
        assertEquals("FOLLOW_ASSET", read.seasonBehavior)
        assertEquals("AT_START", read.seasonReentry)
        assertEquals(0, read.seasonReentryOffsetDays)
        val stored = runBlocking { graph.schedules.get(ScheduleId(id))!! }

        graph.now = dayMillis("2026-02-05")
        // 1.4.1 (#80): the create stored the editor's LOCAL row, and a PATCH is a full replace, so the
        // echo carries the row's providers as a client echoing the row does.
        val echoed = legacy.replace(
            """"seasonBehavior":"FOLLOW_ASSET"}""",
            """"seasonBehavior":"FOLLOW_ASSET","seasonReentry":"AT_START","seasonReentryOffsetDays":0,
               "providers":[{"provider":"LOCAL","enabled":true}]}""",
        )
        val patched = call("PATCH", "/v1/schedules/$id", echoed)
        assertEquals(patched.text(), 200, patched.status)

        assertEquals(read.copy(updatedAt = dayMillis("2026-02-05")), detail())
        val after = runBlocking { graph.schedules.get(ScheduleId(id))!! }
        assertEquals(stored.copy(updatedAt = dayMillis("2026-02-05")), after)
        assertEquals(stored.ruleChangedAt, after.ruleChangedAt)
    }

    /**
     * 1.4 (B03): format 8 took 1.3's season triple out of the schedule row, and `/v1` still reports
     * it — **derived**, through spec §4.1 reversed, beside the row's own policy (master plan §11.3).
     * An AT_START schedule reads `FOLLOW_ASSET` / `AT_START` / its offset; a CONTINUOUS one reads
     * `IGNORE` / null / null; `PRE_SERVICE`, which 1.3 cannot spell, reads null throughout. The keys
     * are read off the **wire**, so a response that fell back to the bare format-8 row fails here, and
     * every schedule-bearing response is pinned to [ScheduleRowResponse] structurally as well.
     */
    @Test fun aScheduleResponseCarriesTheDerivedTriple() {
        val row = ScheduleRowResponse.serializer().descriptor.serialName
        for (descriptor in listOf(
            ScheduleResponse.serializer().descriptor,
            ScheduleDetailResponse.serializer().descriptor,
            ScheduleAndStateResponse.serializer().descriptor,
            CompletionResponse.serializer().descriptor,
            CloseRoundResponse.serializer().descriptor,
        )) {
            assertEquals(
                descriptor.serialName, row,
                descriptor.getElementDescriptor(descriptor.getElementIndex("schedule")).serialName,
            )
        }
        val list = ScheduleListResponse.serializer().descriptor
        assertEquals(row, list.getElementDescriptor(list.getElementIndex("schedules")).getElementDescriptor(0).serialName)

        val asset = createAsset("Snowblower")
        val atStart = call(
            "POST", "/v1/schedules",
            """{"title":"Auger belt","targetAssetId":"$asset","timeInterval":1,"timeUnit":"YEAR",
               "timeBasis":"FIXED","anchorOn":"2026-02-01","seasonBehavior":"FOLLOW_ASSET",
               "seasonReentry":"AT_START","seasonReentryOffsetDays":5}""",
        )
        assertEquals(atStart.text(), 201, atStart.status)
        val atStartId = scheduleIn(atStart).id
        val continuousId = createAssetSchedule(asset, "Oil change")

        fun wireRow(response: ApiResponse): JsonObject =
            ApiJson.parseToJsonElement(response.text()).jsonObject.getValue("schedule").jsonObject
        fun JsonObject.triple() = listOf(get("seasonBehavior"), get("seasonReentry"), get("seasonReentryOffsetDays"))

        val atStartRow = wireRow(call("GET", "/v1/schedules/$atStartId"))
        assertEquals(listOf(JsonPrimitive("FOLLOW_ASSET"), JsonPrimitive("AT_START"), JsonPrimitive(5)), atStartRow.triple())
        assertEquals(JsonPrimitive("IN_SERVICE_AT_START"), atStartRow["servicePolicy"])
        assertEquals(JsonPrimitive(5), atStartRow["policyOffsetDays"])
        assertTrue("ruleChangedAt" in atStartRow)

        val continuousRow = wireRow(call("GET", "/v1/schedules/$continuousId"))
        assertEquals(listOf(JsonPrimitive("IGNORE"), JsonNull, JsonNull), continuousRow.triple())
        assertEquals(JsonPrimitive("CONTINUOUS"), continuousRow["servicePolicy"])

        // The list, a postpone and a completion carry the same row.
        val listed = ApiJson.parseToJsonElement(call("GET", "/v1/schedules").text()).jsonObject
            .getValue("schedules").jsonArray.map { it.jsonObject }
        assertEquals(2, listed.size)
        assertTrue(listed.all { it.triple().size == 3 && "seasonBehavior" in it && "seasonReentryOffsetDays" in it })
        val postponed = call("POST", "/v1/schedules/$atStartId/postpone", """{"postponedDueOn":"2027-03-01"}""")
        assertEquals(postponed.text(), 200, postponed.status)
        assertEquals(atStartRow.triple(), wireRow(postponed).triple())
        val completed = call("POST", "/v1/schedules/$continuousId/complete", completionBody())
        assertEquals(completed.text(), 201, completed.status)
        assertEquals(continuousRow.triple(), wireRow(completed).triple())

        // PRE_SERVICE has no 1.3 spelling: the builder reports null in all three.
        val stored = runBlocking { graph.schedules.get(ScheduleId(continuousId))!! }
        val preService = stored.copy(servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -14).rowResponse()
        assertEquals(listOf(null, null, null), listOf(preService.seasonBehavior, preService.seasonReentry, preService.seasonReentryOffsetDays))
        assertEquals("PRE_SERVICE", preService.servicePolicy)
        assertEquals(-14, preService.policyOffsetDays)
    }

    /**
     * A `PATCH /v1/groups/{id}` that omits a member **soft-removes** it: the row is still there with
     * `removedAt` stamped, and nothing was deleted (invariants 8, 79, 80).
     */
    @Test fun aGroupTweakThatOmitsAMemberSoftRemovesItAndDeletesNothing() {
        val a1 = createAsset("Pump A")
        val a2 = createAsset("Pump B")
        val group = createGroup("North run", a1, a2)
        val before = groupIn(call("GET", "/v1/groups/$group")).members
        assertEquals(2, before.size)

        graph.now = dayMillis("2026-02-04")
        val keep = before.single { it.assetId == a1 }
        val patched = call(
            "PATCH", "/v1/groups/$group",
            """{"name":"North run","members":[{"id":"${keep.id}","assetId":"$a1","sortOrder":0}]}""",
        )
        assertEquals(200, patched.status)

        val after = groupIn(patched).members
        assertEquals("no row was deleted", 2, after.size)
        assertEquals(before.map { it.id }.sorted(), after.map { it.id }.sorted())
        assertNull(after.single { it.assetId == a1 }.removedAt)
        assertEquals(dayMillis("2026-02-04"), after.single { it.assetId == a2 }.removedAt)
        // `added_at` was never edited, and no `removed_at` was ever cleared.
        assertEquals(
            before.associate { it.id to it.addedAt },
            after.associate { it.id to it.addedAt },
        )
    }

    /**
     * **An overlay resubmitted through a PATCH is re-validated as a fresh command** — it is never
     * upserted (master plan B12 N1).
     *
     * The failure this rules out: a handler that wrote the overlaid row straight to the repository
     * would let a legacy or hand-edited body bypass every rule `SaveSchedule` enforces. Here a
     * group-targeted schedule is read back and resubmitted with a meter rule bolted on — legal
     * JSON, a shape the domain refuses — and the route answers 422 by name with the stored row
     * untouched. The route → use case table is what makes that structural; this is what proves it.
     */
    @Test fun aResubmittedOverlayIsRevalidatedByTheUseCaseAndNotUpserted() {
        val a1 = createAsset("Pump A")
        val group = createGroup("North run", a1)
        val schedule = createGroupSchedule(group)
        val meter = ApiJson.decodeFromString(
            DefinitionResponse.serializer(),
            call(
                "POST", "/v1/definitions",
                """{"assetId":"$a1","label":"Hours","unit":"h","isMeter":true}""",
            ).text(),
        ).definition.id
        val before = runBlocking { graph.schedules.get(ScheduleId(schedule)) }

        val refused = call(
            "PATCH", "/v1/schedules/$schedule",
            """{"title":"Winterise","targetGroupId":"$group","timeInterval":1,
               "timeUnit":"MONTH","timeBasis":"FIXED","anchorOn":"2026-02-01",
               "meterDefinitionId":"$meter","meterInterval":100.0}""",
        )
        assertEquals(422, refused.status)
        assertEquals("METER_RULE_ON_GROUP_TARGET", refused.code())
        runBlocking { assertEquals(before, graph.schedules.get(ScheduleId(schedule))) }
    }

    // --- POST /v1/events still cannot create a completion ---------------------------------------

    /**
     * Invariant 76, and #50's "must not invent a second completion path": the event command has no
     * `scheduleId`, `occurrenceOn`, `detailsPending` or `source`, so each of them is an unknown
     * field and a 400 naming it.
     */
    @Test fun postEventsCannotCreateACompletion() {
        val asset = createAsset("Hot tub")
        val schedule = createAssetSchedule(asset)
        for (field in listOf(
            """"scheduleId":"$schedule"""",
            """"occurrenceOn":"2026-02-01"""",
            """"detailsPending":true""",
            """"source":"SCHEDULE_QUICK_COMPLETE"""",
        )) {
            val response = call(
                "POST", "/v1/events",
                """{"assetId":"$asset","kind":"MAINTENANCE","occurredOn":"2026-02-10","tzId":"UTC",$field}""",
            )
            assertEquals(field, 400, response.status)
            assertTrue(field, response.text().contains(field.substringBefore(":").trim('"')))
        }
        runBlocking { assertEquals(emptyList<Any>(), graph.events.all()) }
    }

    // --- the completion route itself ------------------------------------------------------------

    /**
     * A **FORM** schedule completed with no `values` records the event with `detailsPending = 1`;
     * one completed with values does not. The quick path has to exist, and it has to say that the
     * form is still owed.
     */
    @Test fun aMinimalFormCompletionMarksDetailsPendingAndOneWithValuesDoesNot() {
        val asset = createAsset("Hot tub")
        val reading = ApiJson.decodeFromString(
            DefinitionResponse.serializer(),
            call(
                "POST", "/v1/definitions",
                """{"assetId":"$asset","label":"Chlorine","unit":"ppm","decimals":1}""",
            ).text(),
        ).definition.id
        val profile = ApiJson.decodeFromString(
            ProfileResponse.serializer(),
            call(
                "POST", "/v1/profiles",
                """{"assetId":"$asset","name":"Water test","eventKind":"MEASUREMENT",
                   "fields":[{"definitionId":"$reading","required":false}]}""",
            ).text(),
        ).profile.id
        val schedule = scheduleIn(
            call(
                "POST", "/v1/schedules",
                """{"title":"Water test","targetAssetId":"$asset","timeInterval":1,
                   "timeUnit":"MONTH","timeBasis":"FIXED","anchorOn":"2026-02-01",
                   "completionMode":"FORM","profileId":"$profile"}""",
            ),
        ).id

        val minimal = call("POST", "/v1/schedules/$schedule/complete", completionBody())
        assertEquals(minimal.text(), 201, minimal.status)
        val first = ApiJson.decodeFromString(CompletionResponse.serializer(), minimal.text())
        assertTrue(first.event.detailsPending)
        assertEquals(schedule, first.event.scheduleId)
        assertEquals("2026-02-01", first.event.occurrenceOn)

        val withValues = call(
            "POST", "/v1/schedules/$schedule/complete",
            """{"occurredOn":"2026-02-10","tzId":"UTC","notes":"clear","values":{"$reading":"3.2"}}""",
        )
        assertEquals(withValues.text(), 201, withValues.status)
        assertFalse(
            ApiJson.decodeFromString(CompletionResponse.serializer(), withValues.text())
                .event.detailsPending,
        )
    }

    /**
     * D-25: **any valid past `occurredOn` is accepted**, and the date a caller sent is the date
     * stored — never today, and never refused for being in the past. A malformed date is a 422
     * naming the field.
     *
     * **A future date is accepted, and that is the contract as it stands.** D-25's principle is
     * that "the API must not be unable to produce data the app can, nor able to produce data it
     * cannot" (spec §2.9), and nothing in the completion path bounds `occurredOn` above: neither
     * `LogEvent`'s validation nor `CompleteSchedule` nor `CompleteGroupMembers` looks at today, so
     * the in-app completion flow accepts a future date too. Refusing one *here* would invent a rule
     * no use case holds and make the two paths diverge — which is the half of D-25 that is easy to
     * miss. `closedOn` **is** bounded, by `CloseRound`, and that bound is proved above.
     *
     * B12's brief reads this row as "a future one is refused"; the spec's sentence, D-25's own
     * wording and the domain all say otherwise, so the contradiction is reported rather than
     * resolved here. If a future bound is ever ruled, it belongs in the completion use cases and
     * this assertion is what will fail and point at it.
     */
    @Test fun aBackdatedCompletionIsAcceptedWithTheDateItWasSent() {
        val asset = createAsset("Hot tub")
        val schedule = createAssetSchedule(asset)

        val past = call("POST", "/v1/schedules/$schedule/complete", completionBody(occurredOn = "2026-02-03"))
        assertEquals(past.text(), 201, past.status)
        assertEquals(
            "2026-02-03",
            ApiJson.decodeFromString(CompletionResponse.serializer(), past.text()).event.occurredOn,
        )

        val malformed = call("POST", "/v1/schedules/$schedule/complete", completionBody(occurredOn = "03/02/2026"))
        assertEquals(422, malformed.status)
        assertTrue(malformed.problems().toString(), malformed.problems().any { it.contains("BadDate") })

        val future = call("POST", "/v1/schedules/$schedule/complete", completionBody(occurredOn = "2099-01-01"))
        assertEquals("no use case bounds occurredOn above", 201, future.status)
    }

    // --- GET /v1/schedules/{id} and the derived read projection ---------------------------------

    /** `{schedule, state, status, computedForOn}`, with the `today` used echoed back. */
    @Test fun theScheduleDetailCarriesTheDerivedStateAndTheComputedStatus() {
        val asset = createAsset("Hot tub")
        val schedule = createAssetSchedule(asset)

        val detail = ApiJson.decodeFromString(
            ScheduleDetailResponse.serializer(), call("GET", "/v1/schedules/$schedule").text(),
        )
        assertEquals(schedule, detail.schedule.id)
        assertEquals("2026-02-01", detail.state.computedDueOn)
        assertEquals("2026-02-01", detail.state.effectiveDueOn)
        assertEquals("NONE", detail.state.lastTerminationKind)
        assertEquals("OVERDUE", detail.status)
        assertEquals(TODAY.toString(), detail.computedForOn)
    }

    /** A postpone moves the current occurrence only, and `null` clears it (carry-forward (a)). */
    @Test fun postponeMovesTheOccurrenceAndNullClearsIt() {
        val asset = createAsset("Hot tub")
        val schedule = createAssetSchedule(asset)

        val postponed = call(
            "POST", "/v1/schedules/$schedule/postpone", """{"postponedDueOn":"2026-02-20"}""",
        )
        assertEquals(200, postponed.status)
        val moved = ApiJson.decodeFromString(ScheduleAndStateResponse.serializer(), postponed.text())
        assertEquals("2026-02-20", moved.schedule.postponedDueOn)
        assertEquals("2026-02-20", moved.state.effectiveDueOn)
        assertEquals("2026-02-01", moved.state.computedDueOn)

        val cleared = call("POST", "/v1/schedules/$schedule/postpone", """{"postponedDueOn":null}""")
        assertEquals(200, cleared.status)
        val back = ApiJson.decodeFromString(ScheduleAndStateResponse.serializer(), cleared.text())
        assertNull(back.schedule.postponedDueOn)
        assertEquals("2026-02-01", back.state.effectiveDueOn)
    }

    /** A meter-only schedule has no occurrence date to move, so a postpone is refused (422). */
    @Test fun postponingAMeterOnlyScheduleIs422() {
        val asset = createAsset("Generator")
        val meter = ApiJson.decodeFromString(
            DefinitionResponse.serializer(),
            call(
                "POST", "/v1/definitions",
                """{"assetId":"$asset","label":"Hours","unit":"h","isMeter":true}""",
            ).text(),
        ).definition.id
        val schedule = scheduleIn(
            call(
                "POST", "/v1/schedules",
                """{"title":"Oil change","targetAssetId":"$asset","meterDefinitionId":"$meter",
                   "meterInterval":100.0,"anchorMeter":0.0}""",
            ),
        ).id

        val refused = call("POST", "/v1/schedules/$schedule/postpone", """{"postponedDueOn":"2026-03-01"}""")
        assertEquals(422, refused.status)
        assertEquals("POSTPONE_NEEDS_TIME_RULE", refused.code())

        // Clearing is always allowed, even here.
        assertEquals(200, call("POST", "/v1/schedules/$schedule/postpone", """{"postponedDueOn":null}""").status)
    }

    // --- GET /v1/due -----------------------------------------------------------------------------

    /**
     * §9.3's item shape: every field present with its default encoded, a group-targeted schedule as
     * **one** item carrying its two member counts, and `rank` dense and ascending from 0 (D-15).
     * An **archived** schedule never appears — every due query starts from `listedForDue()`.
     */
    @Test fun dueCarriesEveryFieldOncePerScheduleWithADenseRank() {
        val hotTub = createAsset("Hot tub")
        val a1 = createAsset("Pump A")
        val a2 = createAsset("Pump B")
        val own = createAssetSchedule(hotTub)
        val grouped = createGroupSchedule(createGroup("North run", a1, a2))
        val archived = createAssetSchedule(hotTub, title = "Retired job")
        assertEquals(200, call("POST", "/v1/schedules/$archived/archive", """{"archived":true}""").status)

        // One member done, so the group row has real progress to report.
        assertEquals(201, call("POST", "/v1/schedules/$grouped/complete", completionBody(a1)).status)

        val items = ApiJson.decodeFromString(
            DueListResponse.serializer(), call("GET", "/v1/due").text(),
        ).items

        assertEquals(listOf(own, grouped).sorted(), items.map { it.scheduleId }.sorted())
        assertEquals(List(items.size) { it }, items.map { it.rank })

        val group = items.single { it.scheduleId == grouped }
        assertEquals("GROUP", group.targetKind)
        assertNull(group.assetId)
        assertNotNull(group.groupId)
        assertEquals(2, group.membersRequired)
        assertEquals(1, group.membersComplete)
        assertEquals("QUICK", group.completionMode)

        val asset = items.single { it.scheduleId == own }
        assertEquals("ASSET", asset.targetKind)
        assertEquals(hotTub, asset.assetId)
        assertNull(asset.groupId)
        assertNull(asset.membersRequired)
        assertNull(asset.membersComplete)
        assertEquals("OVERDUE", asset.status)
        assertEquals("2026-02-01", asset.effectiveDueOn)
        assertEquals("NONE", asset.lastTerminationKind)

        // Defaults are encoded: an absent value is present as `null`, never missing.
        val raw = call("GET", "/v1/due").text()
        for (field in listOf(
            "scheduleId", "targetKind", "assetId", "groupId", "title", "status", "effectiveDueOn",
            "computedDueMeter", "currentMeter", "lastCompletedOn", "lastTerminationEffectiveOn",
            "lastTerminationKind", "completionMode", "membersRequired", "membersComplete", "rank",
        )) {
            assertTrue(field, raw.contains("\"$field\":"))
        }
    }

    // --- the read lists --------------------------------------------------------------------------

    /** The four new read lists, and the two directions #55 asked for. */
    @Test fun theReadListsAnswerForGroupsAndSchedulesInBothDirections() {
        val a1 = createAsset("Pump A")
        val a2 = createAsset("Pump B")
        val group = createGroup("North run", a1)
        val own = createAssetSchedule(a1)
        val grouped = createGroupSchedule(group)

        val groups = ApiJson.decodeFromString(
            GroupListResponse.serializer(), call("GET", "/v1/groups").text(),
        ).groups
        assertEquals(listOf("North run"), groups.map { it.name })

        val assetGroups = ApiJson.decodeFromString(
            GroupListResponse.serializer(), call("GET", "/v1/assets/$a1/groups").text(),
        ).groups
        assertEquals(listOf(group), assetGroups.map { it.id })
        assertEquals(
            emptyList<String>(),
            ApiJson.decodeFromString(
                GroupListResponse.serializer(), call("GET", "/v1/assets/$a2/groups").text(),
            ).groups.map { it.id },
        )

        assertEquals(
            listOf(own, grouped).sorted(),
            ApiJson.decodeFromString(
                ScheduleListResponse.serializer(), call("GET", "/v1/schedules").text(),
            ).schedules.map { it.id }.sorted(),
        )
        assertEquals(
            listOf(own),
            ApiJson.decodeFromString(
                ScheduleListResponse.serializer(), call("GET", "/v1/assets/$a1/schedules").text(),
            ).schedules.map { it.id },
        )
        assertEquals(
            listOf(grouped),
            ApiJson.decodeFromString(
                ScheduleListResponse.serializer(), call("GET", "/v1/groups/$group/schedules").text(),
            ).schedules.map { it.id },
        )

        assertEquals(404, call("GET", "/v1/groups/00000000-0000-4000-8000-999999999999/schedules").status)
        assertEquals(404, call("GET", "/v1/assets/00000000-0000-4000-8000-999999999999/schedules").status)
    }

    /** The closure history is read-only, oldest first, and both archive routes answer with the row. */
    @Test fun closuresAreReadOnlyHistoryAndArchiveAnswersWithTheRow() {
        val a1 = createAsset("Pump A")
        val group = createGroup("North run", a1)
        val schedule = createGroupSchedule(group)

        assertEquals(201, call("POST", "/v1/schedules/$schedule/close-round", """{"closedOn":"2026-02-05"}""").status)
        graph.today = LocalDate.parse("2026-03-10")
        graph.now = dayMillis("2026-03-10")
        assertEquals(201, call("POST", "/v1/schedules/$schedule/close-round", """{"closedOn":"2026-03-05"}""").status)

        val closures = ApiJson.decodeFromString(
            ClosureListResponse.serializer(), call("GET", "/v1/schedules/$schedule/closures").text(),
        ).closures
        assertEquals(listOf("2026-02-05", "2026-03-05"), closures.map { it.closedOn })

        val archivedGroup = call("POST", "/v1/groups/$group/archive", """{"archived":true}""")
        assertEquals(200, archivedGroup.status)
        assertNotNull(groupIn(archivedGroup).archivedAt)

        val archivedSchedule = call("POST", "/v1/schedules/$schedule/archive", """{"archived":true}""")
        assertEquals(200, archivedSchedule.status)
        assertEquals("ARCHIVED", scheduleIn(archivedSchedule).status)
    }

    /** Pause answers with the row, and a paused schedule is still listed. */
    @Test fun pauseAnswersWithTheRow() {
        val schedule = createAssetSchedule(createAsset("Hot tub"))
        val paused = call("POST", "/v1/schedules/$schedule/pause", """{"paused":true}""")
        assertEquals(200, paused.status)
        assertEquals("PAUSED", scheduleIn(paused).status)
        val resumed = call("POST", "/v1/schedules/$schedule/pause", """{"paused":false}""")
        assertEquals("ACTIVE", scheduleIn(resumed).status)
    }

    // --- /v1/status ------------------------------------------------------------------------------

    /** The three new counts (carry-forward (g)). */
    @Test fun statusCountsGroupsSchedulesAndClosures() {
        val a1 = createAsset("Pump A")
        val group = createGroup("North run", a1)
        val schedule = createGroupSchedule(group)
        call("POST", "/v1/schedules/$schedule/close-round", """{"closedOn":"2026-02-05"}""")

        val status = ApiJson.decodeFromString(
            StatusResponse.serializer(), call("GET", "/v1/status").text(),
        )
        assertEquals(1, status.counts["groups"])
        assertEquals(1, status.counts["schedules"])
        assertEquals(1, status.counts["closures"])
    }

    // --- the merge report's three new tables -----------------------------------------------------

    /**
     * `import_merge` reads a **format-9** archive, and the report carries the `groups`, `schedules`
     * and `closures` tallies beside the shipped ones, plus 1.3's `references`. `applicable` still
     * governs.
     *
     * The format is a **literal**, deliberately: an assertion against `BackupCodec.FORMAT_VERSION`
     * would be a round trip through the same constant the archive was stamped with, and would go on
     * passing whatever that constant became.
     */
    @Test fun theMergeReportCarriesTheThreeNewTallies() {
        val archive = donorArchive()

        val planned = router().handle(
            ApiRequest(
                "POST", IMPORT_MERGE_PLAN_PATH,
                mapOf("authorization" to "Bearer $TOKEN", "content-type" to "application/zip"),
                archive,
            ),
        )
        assertEquals(200, planned.status)
        val report = ApiJson.decodeFromString(MergeReportResponse.serializer(), planned.text())
        assertEquals(9, report.formatVersion)
        assertTrue(report.text(), report.applicable)
        assertEquals(MergeTallyDto(insert = 1, identical = 0, conflict = 0, skipped = 0), report.groups)
        assertEquals(MergeTallyDto(insert = 1, identical = 0, conflict = 0, skipped = 0), report.schedules)
        assertEquals(MergeTallyDto(insert = 1, identical = 0, conflict = 0, skipped = 0), report.closures)
        assertEquals(MergeTallyDto(insert = 1, identical = 0, conflict = 0, skipped = 0), report.references)

        val applied = router().handle(
            ApiRequest(
                "POST", IMPORT_MERGE_APPLY_PATH,
                mapOf("authorization" to "Bearer $TOKEN", "content-type" to "application/zip"),
                archive,
            ),
        )
        assertEquals(200, applied.status)
        runBlocking {
            assertEquals(1, graph.groups.all().size)
            assertEquals(1, graph.schedules.all().size)
            assertEquals(1, graph.closures.all().size)
            assertEquals(1, graph.references.all().size)
        }
    }

    /**
     * Hazard: the report loses a table on the **wire**. `MergeReportResponse` is a hand-written
     * 1:1 mirror of `MergeReport` (master plan §18.3), so a tally added to the domain type and not
     * to the mirror is silently dropped from `/v1/import-merge/plan` and `/v1/import-merge/apply`
     * rather than failing to compile — and `ignoreUnknownKeys = false` cannot catch it either,
     * because a field missing from both the DTO and the JSON is nothing for the parser to object to.
     *
     * So the serialised field names are pinned as a list, in `MergeTable` order — which is the write
     * order for the first fourteen and **not** for #74's `categories`, listed last and written first
     * (`MergeWrites`) — and the values of the ones the releases add are read off a **live route
     * response** rather than off a constructed DTO.
     */
    @Test fun theMergeReportWireMirrorCarriesEveryTallyInTableOrder() {
        assertEquals(
            listOf(
                "formatVersion", "backupSetId", "applicable",
                "assets", "groups", "definitions", "profiles", "schedules", "closures",
                "links", "tags", "events", "attachments", "references",
                // 1.4 (B09): tables 12–14, after the references.
                "seasonActivations", "conditions", "healthSubjects",
                // #74 (format 9): table 15, appended, though its rows are written first.
                "categories",
                "conflicts", "duplicateCandidates",
            ),
            MergeReportResponse.serializer().descriptor.elementNames.toList(),
        )

        val planned = router().handle(
            ApiRequest(
                "POST", IMPORT_MERGE_PLAN_PATH,
                mapOf("authorization" to "Bearer $TOKEN", "content-type" to "application/zip"),
                donorArchive(),
            ),
        )
        assertEquals(200, planned.status)
        // The key is really on the wire, not merely on the Kotlin type: read it out of the JSON
        // before decoding, so a mirror that stopped emitting it fails here.
        assertTrue(planned.text(), "\"references\"" in planned.text())
        for (key in listOf("seasonActivations", "conditions", "healthSubjects", "categories")) {
            assertTrue("$key is on the wire: ${planned.text()}", "\"$key\"" in planned.text())
        }
        val report = ApiJson.decodeFromString(MergeReportResponse.serializer(), planned.text())
        assertEquals(MergeTallyDto(insert = 1, identical = 0, conflict = 0, skipped = 0), report.references)
        assertEquals(MergeTallyDto(insert = 1, identical = 0, conflict = 0, skipped = 0), report.healthSubjects)
        // The donor's two category rows, which this phone does not hold.
        assertEquals(MergeTallyDto(insert = 2, identical = 0, conflict = 0, skipped = 0), report.categories)
    }

    // --- 1.4 (B09): derived state, status and the fourteen-table merge ---------------------------

    /**
     * Spec §9.1: `ScheduleStateDto` gains `actionableDueOn`, `policyReason`, `policyPhase` and
     * `quiet`; `/v1/due` items gain `actionableDueOn`, `policyReason` and `quiet`, and `status` may
     * be `DEFERRED`. A YEAR_ROUND asset with a February break holds an IN_SERVICE_AT_START job due on
     * 5 February until the day after the break: inside its lead, before its actionable date, so
     * DEFERRED, and quiet because today is in the break under a non-CONTINUOUS policy.
     * `effectiveDueOn` keeps its 1.2 meaning. A CONTINUOUS job on the same asset is the control.
     */
    @Test fun stateAndDueCarryTheActionableFieldsAndDeferred() {
        val asset = createAsset("Generator")
        val brk = call(
            "POST", "/v1/assets/$asset/maintenance-break",
            """{"blackoutStartMmdd":"02-01","blackoutEndMmdd":"02-28"}""",
        )
        assertEquals(brk.text(), 200, brk.status)
        val held = call(
            "POST", "/v1/schedules",
            """{"title":"Load bank test","targetAssetId":"$asset","timeInterval":1,"timeUnit":"MONTH",
               "timeBasis":"FIXED","anchorOn":"2026-02-05","leadDays":7,"servicePolicy":"IN_SERVICE_AT_START"}""",
        )
        assertEquals(held.text(), 201, held.status)
        val heldId = scheduleIn(held).id
        val plainId = scheduleIn(
            call(
                "POST", "/v1/schedules",
                """{"title":"Oil change","targetAssetId":"$asset","timeInterval":1,"timeUnit":"MONTH",
                   "timeBasis":"FIXED","anchorOn":"2026-02-05","leadDays":7}""",
            ),
        ).id

        val detailText = call("GET", "/v1/schedules/$heldId").text()
        val detail = ApiJson.decodeFromString(ScheduleDetailResponse.serializer(), detailText)
        assertEquals("2026-02-05", detail.state.effectiveDueOn)
        assertEquals("2026-03-01", detail.state.actionableDueOn)
        assertEquals("AFTER_BREAK", detail.state.policyReason)
        assertEquals("ACTIVE", detail.state.policyPhase)
        assertTrue("inside the break under a non-CONTINUOUS policy", detail.state.quiet)
        assertEquals("DEFERRED", detail.status)
        for (key in listOf("actionableDueOn", "policyReason", "policyPhase", "quiet", "seasonActive", "effectiveDueOn")) {
            assertTrue(key, "\"$key\":" in detailText)
        }

        val dueText = call("GET", "/v1/due").text()
        val items = ApiJson.decodeFromString(DueListResponse.serializer(), dueText).items
        val deferred = items.single { it.scheduleId == heldId }
        assertEquals("DEFERRED", deferred.status)
        assertEquals("2026-02-05", deferred.effectiveDueOn)
        assertEquals("2026-03-01", deferred.actionableDueOn)
        assertEquals("AFTER_BREAK", deferred.policyReason)
        assertTrue(deferred.quiet)
        for (key in listOf("actionableDueOn", "policyReason", "quiet")) assertTrue(key, "\"$key\":" in dueText)

        val plain = items.single { it.scheduleId == plainId }
        assertEquals("OVERDUE", plain.status)
        assertEquals(plain.effectiveDueOn, plain.actionableDueOn)
        assertEquals("NONE", plain.policyReason)
        assertFalse("CONTINUOUS is never quiet", plain.quiet)
        // The order follows the actionable date: the overdue job ranks ahead of the held one.
        assertTrue(plain.rank < deferred.rank)
    }

    /**
     * `seasonActive` is 1.3's field answered from the phase (`policyPhase == ACTIVE`), never a
     * stored column: on a CALENDAR mower out of its season, the IN_SERVICE job is DORMANT and reads
     * false, while a CONTINUOUS job on the same asset is ACTIVE and reads true.
     */
    @Test fun seasonActiveIsDerivedFromThePhase() {
        val mower = createAsset("Mower")
        val season = call(
            "POST", "/v1/assets/$mower/season-mode",
            """{"seasonMode":"CALENDAR","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}""",
        )
        assertEquals(season.text(), 200, season.status)
        val dormant = scheduleIn(
            call(
                "POST", "/v1/schedules",
                """{"title":"Blade sharpening","targetAssetId":"$mower","timeInterval":1,"timeUnit":"MONTH",
                   "anchorOn":"2026-02-01","servicePolicy":"IN_SERVICE_AT_START"}""",
            ),
        ).id
        val continuous = createAssetSchedule(mower, "Battery check")

        fun state(id: String) = ApiJson.decodeFromString(
            ScheduleDetailResponse.serializer(), call("GET", "/v1/schedules/$id").text(),
        ).state
        assertEquals("DORMANT", state(dormant).policyPhase)
        assertFalse(state(dormant).seasonActive)
        assertEquals("ACTIVE", state(continuous).policyPhase)
        assertTrue(state(continuous).seasonActive)
    }

    /**
     * B07's review (O2): `GET /v1/schedules/{id}` and `/v1/due` read derived state through
     * `readState`, so a row stored on an earlier day — here one that says the round was completed
     * and the job is DORMANT until June — is derived afresh for today and never believed, and is
     * still what is stored afterwards: the read wrote nothing.
     */
    @Test fun theDetailAndDueReadAStaleStoredRowThroughReadState() {
        val asset = createAsset("Hot tub")
        val id = createAssetSchedule(asset)
        val stale = runBlocking {
            graph.scheduleStates.get(ScheduleId(id))!!.copy(
                computedForOn = "2026-02-09",
                lastTerminationKind = TerminationKind.COMPLETED,
                lastTerminationEffectiveOn = "2026-02-09",
                policyPhase = PolicyPhase.DORMANT,
                actionableDueOn = "2026-06-01",
            ).also { graph.scheduleStates.upsert(it) }
        }

        val detail = ApiJson.decodeFromString(
            ScheduleDetailResponse.serializer(), call("GET", "/v1/schedules/$id").text(),
        )
        assertEquals("NONE", detail.state.lastTerminationKind)
        assertEquals("ACTIVE", detail.state.policyPhase)
        assertEquals("2026-02-01", detail.state.actionableDueOn)
        assertEquals(TODAY.toString(), detail.state.computedForOn)
        assertEquals("OVERDUE", detail.status)

        val item = ApiJson.decodeFromString(DueListResponse.serializer(), call("GET", "/v1/due").text())
            .items.single { it.scheduleId == id }
        assertEquals("NONE", item.lastTerminationKind)
        assertNull(item.lastTerminationEffectiveOn)
        assertEquals("OVERDUE", item.status)

        assertEquals(stale, runBlocking { graph.scheduleStates.get(ScheduleId(id)) })
    }

    /**
     * Master plan §11.3: `/v1/status` reports the schema and the format, and counts the three 1.4 tables
     * under the archive's own list names beside every shipped key. #74 moved the schema to 9 (its
     * `asset_category` table) and then the format to 9 (the archive that carries the categories).
     */
    @Test fun statusReports9And9AndTheNewCounts() {
        val tub = createAsset("Hot tub")
        assertEquals(201, call("POST", "/v1/assets/$tub/conditions", """{"condition":"DOWN","tzId":"UTC"}""").status)
        assertEquals(
            200,
            call("POST", "/v1/assets/$tub/season-mode", """{"seasonMode":"MANUAL","manualPhase":"IN_SEASON"}""").status,
        )
        assertEquals(
            201,
            call(
                "POST", "/v1/health-subjects",
                """{"assetId":"$tub","name":"Heater element","kind":"PART","driver":"AGE",
                   "nominalUntilDays":0,"warningFromDays":300,"criticalFromDays":600}""",
            ).status,
        )

        val status = ApiJson.decodeFromString(StatusResponse.serializer(), call("GET", "/v1/status").text())
        assertEquals(9, status.schemaVersion)
        assertEquals(9, status.backupFormatVersion)
        assertEquals(1, status.counts["seasonActivations"])
        assertEquals(1, status.counts["assetConditions"])
        assertEquals(1, status.counts["healthSubjects"])
        assertEquals(1, status.counts["assets"])
        // #74: the asset's saved category ("Water", from `createAsset`) is the one row counted.
        assertEquals(1, status.counts["assetCategories"])
    }

    /**
     * Import-merge reads a **format-9** archive and reports **fifteen** tables: the donor's
     * activation, condition and health subject each tally one INSERT on the wire, its two categories
     * (#74) two, and the apply writes each of them — an INSERT, never an update (spec §8.4).
     */
    @Test fun importMergeReadsFormat9AndReportsFifteenTables() {
        val archive = donorArchive()
        fun post(path: String) = router().handle(
            ApiRequest("POST", path, mapOf("authorization" to "Bearer $TOKEN", "content-type" to "application/zip"), archive),
        )

        val planned = post(IMPORT_MERGE_PLAN_PATH)
        assertEquals(planned.text(), 200, planned.status)
        val wire = ApiJson.parseToJsonElement(planned.text()).jsonObject
        val tallies = wire.keys.filter { key -> wire.getValue(key).let { it is JsonObject && "insert" in it } }
        assertEquals(15, tallies.size)
        val report = ApiJson.decodeFromString(MergeReportResponse.serializer(), planned.text())
        assertEquals(9, report.formatVersion)
        assertTrue(report.text(), report.applicable)
        val one = MergeTallyDto(insert = 1, identical = 0, conflict = 0, skipped = 0)
        assertEquals(one, report.seasonActivations)
        assertEquals(one, report.conditions)
        assertEquals(one, report.healthSubjects)
        assertEquals(MergeTallyDto(insert = 2, identical = 0, conflict = 0, skipped = 0), report.categories)

        assertEquals(200, post(IMPORT_MERGE_APPLY_PATH).status)
        runBlocking {
            assertEquals(1, graph.seasonActivations.all().size)
            assertEquals(1, graph.conditions.all().size)
            assertEquals(1, graph.healthSubjects.all().size)
            assertEquals(listOf("Spare parts", "Test gear"), graph.categories.all().map { it.display })
        }
    }

    private fun MergeReportResponse.text(): String = conflicts.toString()

    /**
     * A donor phone carrying one group, one group-targeted schedule, one closure and one
     * reference — and, since 1.4, one activation, one condition and one health subject. Each is
     * there so its tally has a non-zero value to be wrong about — an empty tally would pass against
     * any table the mirror happened to read.
     */
    private fun donorArchive(): ByteArray {
        val donor = FakeGraph().apply {
            now = dayMillis("2026-01-01")
            today = LocalDate.parse("2026-01-20")
        }
        return try {
            var n = 0
            val disjoint = IdGenerator { "00000000-0000-4000-8000-9000%08d".format(++n) }
            val createAsset =
                CreateAsset(donor.assets, donor.uow, disjoint, donor.clock, donor.applyTemplate, donor.promoteCategory)
            val saveGroup = SaveGroup(donor.groups, donor.assets, donor.uow, disjoint, donor.clock)
            val saveSchedule = SaveSchedule(
                donor.schedules, donor.assets, donor.groups, donor.definitions, donor.profiles,
                donor.uow, disjoint, donor.clock, donor.recomputeSchedules, donor.healthSubjects,
            )
            runBlocking {
                val asset = createAsset.run(
                    com.loosecannon.servicetag.core.usecase.AssetCommand(name = "Donor pump", category = "Test gear"), null,
                )
                val group = saveGroup.run(
                    null,
                    com.loosecannon.servicetag.core.usecase.GroupCommand(
                        name = "Donor run",
                        members = listOf(
                            com.loosecannon.servicetag.core.usecase.GroupMemberInput(asset.id),
                        ),
                    ),
                )
                val schedule = saveSchedule.run(
                    null,
                    com.loosecannon.servicetag.core.usecase.ScheduleCommand(
                        targetAssetId = null,
                        targetGroupId = group.id,
                        title = "Donor job",
                        timeInterval = 1,
                        timeUnit = com.loosecannon.servicetag.core.model.RecurrenceUnit.MONTH,
                        anchorOn = "2026-01-01",
                    ),
                )
                donor.closures.insert(
                    OccurrenceClosure(
                        id = "00000000-0000-4000-8000-900000009999",
                        scheduleId = schedule.id,
                        occurrenceOn = "2026-01-01",
                        closedOn = "2026-01-15",
                        createdAt = dayMillis("2026-01-15"),
                    ),
                )
                donor.references.upsert(
                    AssetReference(
                        id = ReferenceId("00000000-0000-4000-8000-900000008888"),
                        assetId = asset.id,
                        kind = ReferenceKind.WEB_URL,
                        uri = "https://example-mower.invalid/donor-manual",
                        displayName = "Donor manual",
                        description = "",
                        scheme = "https",
                        createdAt = dayMillis("2026-01-01"),
                        updatedAt = dayMillis("2026-01-01"),
                    ),
                )
                // 1.4: one row in each of tables 12–14, so their tallies have a value to be wrong
                // about. Inserted as a merge would deliver them — facts and configuration, no health.
                donor.seasonActivations.insert(
                    SeasonActivation(
                        id = "00000000-0000-4000-8000-900000007777",
                        assetId = asset.id,
                        action = SeasonAction.START,
                        occurredOn = "2026-01-10",
                        eventId = null,
                        createdAt = dayMillis("2026-01-10"),
                    ),
                )
                donor.conditions.insert(
                    AssetCondition(
                        id = "00000000-0000-4000-8000-900000006666",
                        assetId = asset.id,
                        condition = OperationalCondition.DEGRADED,
                        occurredOn = "2026-01-12",
                        occurredTime = null,
                        tzId = "UTC",
                        reason = "Seal weeping",
                        eventId = null,
                        createdAt = dayMillis("2026-01-12"),
                    ),
                )
                donor.healthSubjects.upsert(
                    HealthSubject(
                        id = HealthSubjectId("00000000-0000-4000-8000-900000005555"),
                        assetId = asset.id,
                        name = "Pump seal",
                        kind = HealthSubjectKind.PART,
                        driver = HealthDriver.AGE,
                        scheduleId = null,
                        baselineProfileId = null,
                        nominalUntilDays = 0,
                        warningFromDays = 300,
                        criticalFromDays = 600,
                        weight = 1,
                        sortOrder = 0,
                        archivedAt = null,
                        createdAt = dayMillis("2026-01-01"),
                        updatedAt = dayMillis("2026-01-01"),
                    ),
                )
                // #74: the donor asset's saved category is one row; a second, unused row makes the
                // categories tally two — a value no other tally here has, so a mirror wired to the
                // wrong tally fails.
                donor.categories.upsert(
                    com.loosecannon.servicetag.core.model.AssetCategory(
                        "spare parts", "Spare parts", dayMillis("2026-01-02"), dayMillis("2026-01-02"),
                    ),
                )
                donor.exportBackupSet.run().data
            }
        } finally {
            donor.close()
        }
    }

    // --- 1.4.1 (#80, R4): the create default, and PATCH left exactly as it was -----------------

    private val localOn = ScheduleProviderRow("LOCAL", enabled = true)
    private val localOff = ScheduleProviderRow("LOCAL", enabled = false)

    /** A monthly schedule on [assetId] with [extra] (a leading comma and keys) spliced into the body. */
    private fun scheduleBody(assetId: String, extra: String = "", title: String = "Filter change"): String =
        """{"title":"$title","targetAssetId":"$assetId","timeInterval":1,"timeUnit":"MONTH","anchorOn":"2026-02-01"$extra}"""

    /** The stored row's provider set, read from the repository rather than the response. */
    private fun storedProviders(id: String): List<ScheduleProviderRow> =
        runBlocking { graph.schedules.get(ScheduleId(id))!!.providers }

    private fun created(body: String): String {
        val response = call("POST", "/v1/schedules", body)
        assertEquals(response.text(), 201, response.status)
        return scheduleIn(response).id
    }

    /**
     * #80 AC 1–2: a create that omits `providers` stores what the app's editor stores — one LOCAL
     * row enabled exactly when reminders are — for both values and for `remindersEnabled`'s own
     * default.
     */
    @Test fun createWithoutProvidersStoresLocalFromRemindersEnabled() {
        val asset = createAsset("Pump A")
        assertEquals(listOf(localOn), storedProviders(created(scheduleBody(asset, title = "Default"))))
        assertEquals(listOf(localOn), storedProviders(created(scheduleBody(asset, ""","remindersEnabled":true""", "On"))))
        assertEquals(listOf(localOff), storedProviders(created(scheduleBody(asset, ""","remindersEnabled":false""", "Off"))))
    }

    /** The decoder cannot tell an absent key from a `null` on a create, and a create need not: same row. */
    @Test fun createWithNullProvidersDoesTheSame() {
        val asset = createAsset("Pump A")
        assertEquals(listOf(localOn), storedProviders(created(scheduleBody(asset, ""","providers":null""", "On"))))
        assertEquals(
            listOf(localOff),
            storedProviders(created(scheduleBody(asset, ""","remindersEnabled":false,"providers":null""", "Off"))),
        )
    }

    /** #80 AC 3: an explicit empty list is the one way to store no provider, and it is kept. */
    @Test fun createWithEmptyProvidersStoresNone() {
        val asset = createAsset("Pump A")
        assertEquals(emptyList<ScheduleProviderRow>(), storedProviders(created(scheduleBody(asset, ""","providers":[]"""))))
    }

    /** An explicit list is stored as sent and validated as today: an unknown name is still refused. */
    @Test fun createWithAListKeepsIt() {
        val asset = createAsset("Pump A")
        assertEquals(
            listOf(localOff),
            storedProviders(created(scheduleBody(asset, ""","providers":[{"provider":"LOCAL","enabled":false}]"""))),
        )
        val unknown = call("POST", "/v1/schedules", scheduleBody(asset, ""","providers":[{"provider":"ALMANAC"}]""", "Unknown"))
        assertEquals(422, unknown.status)
        assertEquals("UNKNOWN_PROVIDER", unknown.code())
    }

    /** R4: a PATCH is a full replace, so an omitted `providers` still replaces the set with none. */
    @Test fun patchWithoutProvidersStillReplacesWithNone() {
        val asset = createAsset("Pump A")
        val id = created(scheduleBody(asset, ""","providers":[{"provider":"LOCAL","enabled":true}]"""))
        assertEquals(listOf(localOn), storedProviders(id))

        val patched = call("PATCH", "/v1/schedules/$id", scheduleBody(asset))
        assertEquals(patched.text(), 200, patched.status)
        assertEquals(emptyList<ScheduleProviderRow>(), storedProviders(id))
    }

    /** R4 and Q2: a PATCH naming `providers` as `null` is refused as it was before 1.4.1, and writes nothing. */
    @Test fun patchWithNullProvidersIsStillA400() {
        val asset = createAsset("Pump A")
        val id = created(scheduleBody(asset, ""","providers":[{"provider":"LOCAL","enabled":true}]"""))
        val before = runBlocking { graph.schedules.get(ScheduleId(id)) }

        val refused = call("PATCH", "/v1/schedules/$id", scheduleBody(asset, ""","providers":null""", "Renamed"))
        assertEquals(refused.text(), 400, refused.status)
        assertEquals("bad_request", refused.code())
        assertTrue(refused.text(), "providers" in refused.errorDetail().message)
        assertEquals(before, runBlocking { graph.schedules.get(ScheduleId(id)) })

        // Keyed on the PATCH, not on the stored row: a PATCH naming no schedule is the same 400 it
        // always was, never a 404 that only a well-formed body could earn.
        val nowhere = call("PATCH", "/v1/schedules/00000000-0000-4000-8000-999999999999", scheduleBody(asset, ""","providers":null"""))
        assertEquals(nowhere.text(), 400, nowhere.status)
        assertEquals("bad_request", nowhere.code())
    }

    // --- 1.4.1 (#80, R2): the provider repair's two routes ------------------------------------

    private val repairPlanPath = "/v1/repairs/schedule-providers/plan"
    private val repairApplyPath = "/v1/repairs/schedule-providers/apply"

    private fun repairIn(response: ApiResponse): ProviderRepairResponse {
        assertEquals(response.text(), 200, response.status)
        return ApiJson.decodeFromString(ProviderRepairResponse.serializer(), response.text())
    }

    /**
     * The three shapes a plan sorts — ACTIVE and providerless (repairable), PAUSED and providerless,
     * ACTIVE with LOCAL disabled — beside two it must not list: an ARCHIVED providerless row and an
     * ordinary LOCAL one. Returns the ids by shape.
     */
    private fun seedRepairShapes(): Map<String, String> {
        val asset = createAsset("Pump A")
        val repairable = created(scheduleBody(asset, ""","providers":[]""", "Belt check"))
        val paused = created(scheduleBody(asset, ""","providers":[]""", "Drive check"))
        assertEquals(200, call("POST", "/v1/schedules/$paused/pause", """{"paused":true}""").status)
        val disabled = created(scheduleBody(asset, ""","providers":[{"provider":"LOCAL","enabled":false}]""", "Coupling check"))
        val archived = created(scheduleBody(asset, ""","providers":[]""", "Alignment check"))
        assertEquals(200, call("POST", "/v1/schedules/$archived/archive", """{"archived":true}""").status)
        val delivered = created(scheduleBody(asset, title = "Filter change"))
        return mapOf(
            "repairable" to repairable, "paused" to paused, "disabled" to disabled,
            "archived" to archived, "delivered" to delivered,
        )
    }

    /**
     * Every table the repair's use case could reach, as sets of rows — `ApiReadsWriteNothingTest`'s
     * read-only proof. The clock moves before the call, so even a rewrite of an identical row shows.
     */
    private fun repairTables(): Map<String, Set<Any>> = runBlocking {
        mapOf(
            "maintenance_schedule" to graph.schedules.all().toSet(),
            "schedule_state" to graph.scheduleStates.all().toSet(),
            "occurrence_closure" to graph.closures.all().toSet(),
            "asset_event" to graph.events.all().toSet(),
            "maintenance_group" to graph.groups.all().toSet(),
            "asset" to graph.assets.all().toSet(),
        )
    }

    /** The plan counts by shape, lists the matched rows by title, and writes nothing at all. */
    @Test fun theRepairPlanWritesNothingAndCounts() {
        val ids = seedRepairShapes()
        val tablesBefore = repairTables()
        val listBefore = call("GET", "/v1/schedules").text()
        graph.now += 86_400_000L

        val plan = repairIn(call("POST", repairPlanPath, "{}"))

        assertEquals(3, plan.matched)
        assertEquals(1, plan.repairable)
        assertEquals(2, plan.skipped)
        assertEquals(0, plan.repaired)
        assertEquals(
            listOf(
                ProviderRepairRowResponse(ids.getValue("repairable"), "Belt check", "REPAIR", null),
                ProviderRepairRowResponse(ids.getValue("disabled"), "Coupling check", "SKIPPED", "PROVIDERS_DISABLED"),
                ProviderRepairRowResponse(ids.getValue("paused"), "Drive check", "SKIPPED", "PAUSED"),
            ),
            plan.schedules,
        )
        assertEquals(tablesBefore, repairTables())
        assertEquals(listBefore, call("GET", "/v1/schedules").text())
    }

    /** The apply repairs what its own re-plan calls repairable; a re-plan then finds nothing to repair. */
    @Test fun theRepairApplyThenReplanIsZero() {
        val ids = seedRepairShapes()
        graph.now = dayMillis("2026-02-07")

        val applied = repairIn(call("POST", repairApplyPath, "{}"))
        assertEquals(3, applied.matched)
        assertEquals(1, applied.repairable)
        assertEquals(2, applied.skipped)
        assertEquals(applied.repairable, applied.repaired)
        assertEquals(
            listOf("REPAIRED", "SKIPPED", "SKIPPED"),
            applied.schedules.map { it.outcome },
        )
        assertEquals(listOf(localOn), storedProviders(ids.getValue("repairable")))
        assertEquals(dayMillis("2026-02-07"), runBlocking { graph.schedules.get(ScheduleId(ids.getValue("repairable")))!!.updatedAt })
        assertEquals(emptyList<ScheduleProviderRow>(), storedProviders(ids.getValue("paused")))
        assertEquals(emptyList<ScheduleProviderRow>(), storedProviders(ids.getValue("archived")))
        assertEquals(listOf(localOff), storedProviders(ids.getValue("disabled")))

        val replan = repairIn(call("POST", repairPlanPath, "{}"))
        assertEquals(0, replan.repairable)
        assertEquals(2, replan.skipped)
        assertEquals(0, repairIn(call("POST", repairApplyPath, "{}")).repaired)
    }

    /**
     * The body is `{}` and nothing else: a key is the strict decoder's 400, a zero-byte body is not
     * JSON (400 with the JSON type, the router's 415 without one), another type is 415, another verb
     * 405 — and none of them writes. Neither route has a bare or deeper spelling.
     */
    @Test fun theRepairRefusesABodyWithKeysOrNoBody() {
        seedRepairShapes()
        val rowsBefore = runBlocking { graph.schedules.all().toSet() }

        for (path in listOf(repairPlanPath, repairApplyPath)) {
            val keyed = call("POST", path, """{"planOnly":true}""")
            assertEquals(keyed.text(), 400, keyed.status)
            assertEquals("bad_request", keyed.code())

            val emptyJson = router().handle(
                ApiRequest(
                    "POST", path,
                    mapOf("host" to "127.0.0.1", "authorization" to "Bearer $TOKEN", "content-type" to "application/json"),
                    ByteArray(0),
                ),
            )
            assertEquals(emptyJson.text(), 400, emptyJson.status)
            assertEquals("bad_request", emptyJson.code())

            assertEquals(415, call("POST", path).status)
            assertEquals(415, call("POST", path, "{}", contentType = "text/plain").status)
            assertEquals(405, call("GET", path).status)
            assertEquals(405, call("PATCH", path, "{}").status)
        }
        assertEquals(404, call("POST", "/v1/repairs/schedule-providers", "{}").status)
        assertEquals(404, call("POST", "/v1/repairs", "{}").status)
        assertEquals(rowsBefore, runBlocking { graph.schedules.all().toSet() })
    }
}
