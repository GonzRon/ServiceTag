package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.season.BoundarySource
import com.loosecannon.servicetag.core.season.NotReadyReason
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.ReadinessBoundary
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #114 (C29; row 80) — `GET /v1/assets/{id}/season-readiness`, the twenty-ninth asset sub-resource, over the production
 * router and `FakeGraph`, on the JVM: C9's answer as eleven keys, every `NotReadyReason` by its name, a group role
 * named as a group, and the boundary's source and cutoff as two fields. Bodies are read as JSON trees, so an absent
 * key and a `null` are told apart. Every name is fictional ("Example Heater", "Example Boiler", "Example pre-season
 * check"). The two Home Assistant sources are proved here on the response mapping the route uses, and through the
 * route itself, fresh and unverified, in `HaPublishingRoutesTest`.
 */
class SeasonReadinessRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-10")
        today = LocalDate.parse("2026-02-10")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private val keys = setOf(
        "assetId", "readyForSeason", "seasonServiceDoneOn", "reason", "roleScheduleId", "roleTarget", "roleSinceOn",
        "cutoffOn", "boundarySource", "boundaryCheckedAt", "boundaryUnverifiedSince",
    )

    // --- fixtures -------------------------------------------------------------------------------

    private fun readiness(asset: String): JsonObject {
        val response = api.call("GET", "/v1/assets/$asset/season-readiness")
        assertEquals(response.bodyText(), 200, response.status)
        val tree = Json.parseToJsonElement(response.bodyText()).jsonObject
        assertEquals(keys, tree.keys)
        assertEquals(asset, tree.text("assetId"))
        return tree
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.isNull(key: String): Boolean = getValue(key) == JsonNull

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    /** A `CALENDAR` asset in season from 1 April to 31 October: on 10 February its season last ended 1 November. */
    private fun calendarAsset(name: String): String = api.asset(name).also { asset ->
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$asset/season-mode",
            """{"seasonMode":"CALENDAR","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}""",
        )
    }

    private fun schedule(asset: String, title: String = "Example annual service"): String = api.ok(
        ScheduleResponse.serializer(), "POST", "/v1/schedules",
        """{"title":"$title","targetAssetId":"$asset","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01"}""",
        status = 201,
    ).schedule.id

    private fun role(schedule: String, sinceOn: String) = runBlocking {
        graph.setScheduleRole.run(ScheduleId(schedule), ScheduleRole.SEASON_START, sinceOn, quickElected = true)
    }

    private fun complete(schedule: String, on: String = "2026-02-10", member: String? = null): String {
        val tail = member?.let { ""","assetId":"$it"""" } ?: ""
        return api.ok(
            CompletionResponse.serializer(), "POST", "/v1/schedules/$schedule/complete",
            """{"occurredOn":"$on","tzId":"UTC"$tail}""", status = 201,
        ).event.id
    }

    /** A stored event rewritten in place: what a merge, a pack or an older build could have left behind. */
    private fun amend(event: String, change: (AssetEvent) -> AssetEvent) =
        runBlocking { graph.uow.write { graph.events.upsert(change(graph.events.get(EventId(event))!!)) } }

    /** A role schedule written straight to the store, past the commands that would refuse it. */
    private fun forceRole(schedule: String, sinceOn: String) = runBlocking {
        graph.uow.write {
            val row = graph.schedules.get(ScheduleId(schedule))!!
            graph.schedules.upsert(row.copy(role = ScheduleRole.SEASON_START, roleSinceOn = sinceOn))
        }
    }

    private fun assertNoRoleKeys(tree: JsonObject) {
        for (key in listOf("roleScheduleId", "roleTarget", "roleSinceOn", "cutoffOn", "boundarySource")) {
            assertTrue("$key: $tree", tree.isNull(key))
        }
    }

    private fun assertNotReady(tree: JsonObject, reason: NotReadyReason) {
        assertFalse(tree.toString(), tree.flag("readyForSeason"))
        assertTrue(tree.toString(), tree.isNull("seasonServiceDoneOn"))
        assertEquals(tree.toString(), reason.name, tree.text("reason"))
        assertTrue(tree.toString(), tree.isNull("boundaryCheckedAt"))
        assertTrue(tree.toString(), tree.isNull("boundaryUnverifiedSince"))
    }

    // --- Ready ----------------------------------------------------------------------------------

    /** Ready: the qualifying day, no reason, the role and its boundary, the cutoff and its source as two fields. */
    @Test fun aReadyAssetReadsEveryKey() {
        val heater = calendarAsset("Example Heater")
        val service = schedule(heater)
        role(service, "2025-06-01")
        complete(service)

        val tree = readiness(heater)
        assertTrue(tree.flag("readyForSeason"))
        assertEquals("2026-02-10", tree.text("seasonServiceDoneOn"))
        assertTrue(tree.isNull("reason"))
        assertEquals(service, tree.text("roleScheduleId"))
        assertEquals("ASSET", tree.text("roleTarget"))
        assertEquals("2025-06-01", tree.text("roleSinceOn"))
        assertEquals("2025-11-01", tree.text("cutoffOn"))
        assertEquals("CALENDAR_WINDOW", tree.text("boundarySource"))
        assertTrue(tree.isNull("boundaryCheckedAt"))
        assertTrue(tree.isNull("boundaryUnverifiedSince"))
    }

    // --- every NotReadyReason, by its name --------------------------------------------------------

    @Test fun eachNotReadyReasonIsItsName() {
        val seen = mutableSetOf<NotReadyReason>()
        fun check(asset: String, reason: NotReadyReason): JsonObject =
            readiness(asset).also { assertNotReady(it, reason); seen += reason }

        // NO_ROLE: nothing covers it, so nothing about a role is known.
        assertNoRoleKeys(check(calendarAsset("Example Boiler"), NotReadyReason.NO_ROLE))

        // TWO_ROLES: a second role a merge could have left; neither is named.
        val twice = calendarAsset("Example Heater 2")
        role(schedule(twice, "Example annual service"), "2025-06-01")
        forceRole(schedule(twice, "Example spring check"), "2025-06-01")
        assertNoRoleKeys(check(twice, NotReadyReason.TWO_ROLES))

        // ROLE_NOT_ALLOWED: a role on an asset that has no season; the role is named, no boundary is.
        val yearRound = api.asset("Example Heater 3")
        val yearRoundService = schedule(yearRound)
        forceRole(yearRoundService, "2025-06-01")
        check(yearRound, NotReadyReason.ROLE_NOT_ALLOWED).let { tree ->
            assertEquals(yearRoundService, tree.text("roleScheduleId"))
            assertEquals("ASSET", tree.text("roleTarget"))
            assertEquals("2025-06-01", tree.text("roleSinceOn"))
            assertTrue(tree.isNull("cutoffOn"))
            assertTrue(tree.isNull("boundarySource"))
        }

        // SCHEDULE_ARCHIVED: the role schedule the answer is about is the archived one.
        val archived = calendarAsset("Example Heater 4")
        val archivedService = schedule(archived)
        role(archivedService, "2025-06-01")
        api.ok(ScheduleResponse.serializer(), "POST", "/v1/schedules/$archivedService/archive", """{"archived":true}""")
        check(archived, NotReadyReason.SCHEDULE_ARCHIVED).let { tree ->
            assertEquals(archivedService, tree.text("roleScheduleId"))
            assertTrue(tree.isNull("cutoffOn"))
        }

        // NOT_DONE_SINCE_SEASON_END: the window's end won the cutoff.
        val idle = calendarAsset("Example Heater 5")
        role(schedule(idle), "2025-06-01")
        check(idle, NotReadyReason.NOT_DONE_SINCE_SEASON_END).let { tree ->
            assertEquals("2025-11-01", tree.text("cutoffOn"))
            assertEquals("CALENDAR_WINDOW", tree.text("boundarySource"))
        }

        // NOT_DONE_SINCE_COMMISSIONING: the role is newer than the season's end.
        val fresh = calendarAsset("Example Heater 6")
        role(schedule(fresh), "2026-01-15")
        check(fresh, NotReadyReason.NOT_DONE_SINCE_COMMISSIONING).let { tree ->
            assertEquals("2026-01-15", tree.text("cutoffOn"))
            assertEquals("COMMISSIONING", tree.text("boundarySource"))
        }

        // The four the events decide: one completion each, then rewritten the way a store could hold it.
        val amended = listOf<Pair<NotReadyReason, (AssetEvent) -> AssetEvent>>(
            NotReadyReason.DETAILS_PENDING to { it.copy(detailsPending = true) },
            NotReadyReason.FUTURE_DATED to { it.copy(occurredOn = "2026-02-12") },
            NotReadyReason.INHERITED_SERVICE_ONLY to { it.copy(imported = true) },
            NotReadyReason.RECORDED_IN_ADVANCE to { it.copy(updatedAt = dayMillis("2026-02-09")) },
        )
        for ((index, case) in amended.withIndex()) {
            val (reason, change) = case
            val asset = calendarAsset("Example Heater ${7 + index}")
            val service = schedule(asset)
            role(service, "2025-06-01")
            amend(complete(service), change)
            check(asset, reason).let { tree ->
                assertEquals(service, tree.text("roleScheduleId"))
                assertEquals("2025-11-01", tree.text("cutoffOn"))
            }
        }

        assertEquals("every NotReadyReason is answered by its name", NotReadyReason.entries.toSet(), seen)
    }

    // --- 404 ------------------------------------------------------------------------------------

    @Test fun noSuchAsset404() {
        val response = api.call("GET", "/v1/assets/no-such-asset/season-readiness")
        assertEquals(404, response.status)
        assertEquals("no_such_asset", response.errorDetail().code)
        // An asset sub-resource answers 404 for a verb it does not take.
        val heater = api.asset("Example Heater")
        assertEquals(404, api.call("POST", "/v1/assets/$heater/season-readiness", "{}").status)
    }

    // --- a group role ---------------------------------------------------------------------------

    /** C1: a role on a group schedule covers each open member, which reads its own completion of it. */
    @Test fun aGroupRoleNamesGroup() {
        val heater = calendarAsset("Example Heater")
        val group = api.ok(
            GroupResponse.serializer(), "POST", "/v1/groups",
            """{"name":"Example appliances","members":[{"assetId":"$heater"}]}""", status = 201,
        ).group.id
        val check = api.ok(
            ScheduleResponse.serializer(), "POST", "/v1/schedules",
            """{"title":"Example pre-season check","targetGroupId":"$group","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01"}""",
            status = 201,
        ).schedule.id
        role(check, "2025-06-01")

        readiness(heater).let { tree ->
            assertNotReady(tree, NotReadyReason.NOT_DONE_SINCE_SEASON_END)
            assertEquals(check, tree.text("roleScheduleId"))
            assertEquals("GROUP", tree.text("roleTarget"))
        }
        complete(check, member = heater)
        readiness(heater).let { tree ->
            assertTrue(tree.flag("readyForSeason"))
            assertEquals("2026-02-10", tree.text("seasonServiceDoneOn"))
            assertEquals("GROUP", tree.text("roleTarget"))
        }
    }

    // --- the boundary's source and the cutoff, two fields ---------------------------------------

    /**
     * F: `cutoffOn` and `boundarySource` are two fields, for every [BoundarySource]. The route reaches the three the
     * phone decides here; the two Home Assistant sources are proved on the same mapping: `boundaryCheckedAt` only for
     * `HA_SEASON_ENDED`, `boundaryUnverifiedSince` whenever the boundary is unverified.
     */
    @Test fun boundarySourceAndCutoffAreTwoFields() {
        val seen = mutableSetOf<String>()

        val calendar = calendarAsset("Example Heater")
        role(schedule(calendar), "2025-06-01")
        readiness(calendar).let { tree ->
            assertEquals("2025-11-01", tree.text("cutoffOn"))
            assertEquals("CALENDAR_WINDOW", tree.text("boundarySource"))
            seen += tree.text("boundarySource")
        }

        // A MANUAL asset switched in out of season records its END today, and that END is the cutoff.
        val manual = api.asset("Example Boiler")
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$manual/season-mode",
            """{"seasonMode":"MANUAL","manualPhase":"OUT_OF_SEASON"}""",
        )
        val manualService = schedule(manual)
        role(manualService, "2026-01-01")
        complete(manualService)
        readiness(manual).let { tree ->
            assertTrue(tree.flag("readyForSeason"))
            assertEquals("2026-02-10", tree.text("cutoffOn"))
            assertEquals("PHONE_SEASON_END", tree.text("boundarySource"))
            assertEquals("2026-01-01", tree.text("roleSinceOn"))
            seen += tree.text("boundarySource")
        }

        val commissioned = calendarAsset("Example Heater 2")
        role(schedule(commissioned), "2026-01-15")
        readiness(commissioned).let { tree ->
            assertEquals("2026-01-15", tree.text("cutoffOn"))
            assertEquals("COMMISSIONING", tree.text("boundarySource"))
            seen += tree.text("boundarySource")
        }

        val heater = AssetId(calendar)
        val schedules = runBlocking { graph.schedules.all() }
        val service = schedules.single { (it.target as? ScheduleTarget.AssetTarget)?.assetId == heater }
        val fresh = Readiness.Ready(
            LocalDate.parse("2026-02-10"), service.id,
            ReadinessBoundary(LocalDate.parse("2025-11-03"), BoundarySource.HA_SEASON_ENDED, checkedAt = 1_770_700_000_000L),
        ).toResponse(heater, schedules)
        assertEquals("2025-11-03", fresh.cutoffOn)
        assertEquals("HA_SEASON_ENDED", fresh.boundarySource)
        assertEquals(1_770_700_000_000L, fresh.boundaryCheckedAt)
        assertEquals(null, fresh.boundaryUnverifiedSince)
        seen += fresh.boundarySource!!

        val unverified = Readiness.NotReady(
            NotReadyReason.NOT_DONE_SINCE_SEASON_END, service.id,
            ReadinessBoundary(LocalDate.parse("2025-11-05"), BoundarySource.HA_UNVERIFIED, unverifiedSince = 1_770_600_000_000L),
        ).toResponse(heater, schedules)
        assertEquals("2025-11-05", unverified.cutoffOn)
        assertEquals("HA_UNVERIFIED", unverified.boundarySource)
        assertEquals(null, unverified.boundaryCheckedAt)
        assertEquals(1_770_600_000_000L, unverified.boundaryUnverifiedSince)
        assertEquals("NOT_DONE_SINCE_SEASON_END", unverified.reason)
        seen += unverified.boundarySource!!

        // Unverified whatever won: commissioning can win the cutoff while HA's date is still unverified (D).
        val commissioningWon = Readiness.NotReady(
            NotReadyReason.NOT_DONE_SINCE_COMMISSIONING, service.id,
            ReadinessBoundary(LocalDate.parse("2026-01-15"), BoundarySource.COMMISSIONING, unverifiedSince = 1_770_600_000_000L),
        ).toResponse(heater, schedules)
        assertEquals("COMMISSIONING", commissioningWon.boundarySource)
        assertEquals(1_770_600_000_000L, commissioningWon.boundaryUnverifiedSince)

        assertEquals("every BoundarySource is answered by its name", BoundarySource.entries.map { it.name }.toSet(), seen)
    }
}
