package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.GroupMemberDto
import com.loosecannon.servicetag.core.backup.MaintenanceGroupDto
import com.loosecannon.servicetag.core.backup.MaintenanceScheduleDto
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

private const val TOKEN = "ABCD2345"

private fun dayMillis(date: String): Long =
    LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private val SerialDescriptor.names: List<String>
    get() = (0 until elementsCount).map { getElementName(it) }

/**
 * 1.2 (B12) — the three commands' **shape**, asserted against the response rows they mirror rather
 * than against a list written twice.
 *
 * Two hazards live here and nowhere else: a command that grows an identity or bookkeeping field
 * (which would let a client rewrite identity or a timestamp), and `ScheduleStateDto` — the derived
 * read projection — leaking into the backup format, which is the one place the "one schema on
 * purpose" paragraph of `docs/api/v1.md` could be read as an instruction to put it.
 */
class MaintenanceCommandShapeTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-02-01")
        today = LocalDate.parse("2026-02-10")
    }

    @After fun close() = graph.close()

    private fun router(): ApiRouter = ApiRouter(
        ApiHandlers(
            graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles,
            graph.events, graph.attachments, graph.categories, graph.transferRecords, graph.assetSuccessions,
            graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
            graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
            graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
            maintenanceHandlersFor(graph),
            referenceHandlersFor(graph),
            seasonHealthHandlersFor(graph),
            warrantyHandlersFor(graph),
            serviceCaseHandlersFor(graph),
            loanHandlersFor(graph),
            attachmentHandlersFor(graph),
            replaceHandlersFor(graph),
            supplyHandlersFor(graph),
            installedComponentHandlersFor(graph),
            appVersion = "1.2.0",
            schemaVersion = AppGraph.SCHEMA_VERSION,
        ),
        TOKEN,
    )

    private fun call(method: String, path: String, body: String = ""): ApiResponse =
        router().handle(
            ApiRequest(
                method, path,
                buildMap {
                    put("host", "127.0.0.1")
                    put("authorization", "Bearer $TOKEN")
                    if (body.isNotEmpty()) put("content-type", "application/json")
                },
                body.toByteArray(),
            ),
        )

    private fun ApiResponse.text(): String = body.decodeToString()

    private fun createAsset(name: String): String =
        ApiJson.decodeFromString(
            AssetResponse.serializer(), call("POST", "/v1/assets", """{"name":"$name"}""").text(),
        ).asset.id

    // --- the identity and bookkeeping keys are in no command -------------------------------------

    /**
     * §9.2's rule, as a structural assertion over all three commands at once: no `id`, `status`,
     * `createdAt`, `updatedAt`, `addedAt`, `removedAt` or `archivedAt` at the top level of any of
     * them. A membership line's own optional `id` is the documented exception — it names an existing
     * window to **keep** — and is asserted separately below.
     */
    @Test fun noCommandCarriesIdentityOrBookkeeping() {
        val forbidden = setOf("id", "status", "createdAt", "updatedAt", "addedAt", "removedAt", "archivedAt")
        for (descriptor in listOf(
            GroupCommandRequest.serializer().descriptor,
            ScheduleCommandRequest.serializer().descriptor,
            CompletionRequest.serializer().descriptor,
        )) {
            assertEquals(
                descriptor.serialName,
                emptySet<String>(),
                descriptor.names.toSet() intersect forbidden,
            )
        }
        assertEquals(
            listOf("id", "assetId", "sortOrder"),
            GroupMemberRequest.serializer().descriptor.names,
        )
    }

    /** The group command is exactly the group row minus its identity and bookkeeping. */
    @Test fun theGroupCommandIsTheGroupRowMinusIdentityAndBookkeeping() {
        assertEquals(
            MaintenanceGroupDto.serializer().descriptor.names -
                setOf("id", "archivedAt", "createdAt", "updatedAt"),
            GroupCommandRequest.serializer().descriptor.names,
        )
        assertEquals(
            GroupMemberDto.serializer().descriptor.names - setOf("addedAt", "removedAt"),
            GroupMemberRequest.serializer().descriptor.names,
        )
    }

    /**
     * The schedule command is the schedule row minus its identity, its bookkeeping and
     * `postponedDueOn` — which has its own route — **with the target pair renamed**: the row reports
     * `assetId`/`groupId`, the command takes `targetAssetId`/`targetGroupId`, exactly as master plan
     * §9.2 fixes and as `ScheduleCommand` itself is named. That rename is the schedule's equivalent
     * of the event's `measurements`/`values` gap the shipped page already documents, and it is on
     * `docs/api/v1.md`.
     *
     * 1.4: the row is the `/v1` schedule row, [ScheduleRowResponse] — the format-8 row plus the
     * derived season triple — and the command carries both: `servicePolicy`/`policyOffsetDays` as
     * the 1.4 form and the triple as the deprecated legacy form (spec §9.3). `ruleChangedAt` is
     * response-only, so it is subtracted with the identity keys. #114 (K11, G18): so are `role` and
     * `roleSinceOn` — the role has its own command, and a schedule body carrying either is the 400.
     */
    @Test fun theScheduleCommandIsTheScheduleRowMinusIdentityBookkeepingAndThePostponement() {
        val fromRow = ScheduleRowResponse.serializer().descriptor.names
            .filterNot {
                it in setOf("id", "status", "createdAt", "updatedAt", "postponedDueOn", "ruleChangedAt", "role", "roleSinceOn")
            }
            .map {
                when (it) {
                    "assetId" -> "targetAssetId"
                    "groupId" -> "targetGroupId"
                    else -> it
                }
            }
        assertEquals(fromRow.toSet(), ScheduleCommandRequest.serializer().descriptor.names.toSet())
    }

    /**
     * 1.4 (B03): the `/v1` schedule row is **the archive row plus the derived triple**, in that
     * order, and nothing else. Pinned against `MaintenanceScheduleDto`'s own names rather than a
     * list written twice, so a field later added to the archive row cannot go missing from `/v1` —
     * nor, through the case above, from the command check that reads this row.
     */
    @Test fun theScheduleRowIsTheArchiveRowPlusTheDerivedTriple() {
        assertEquals(
            // #114 (C29): format 21's `role` and `roleSinceOn` are archive fields, so the `/v1` row carries them too.
            // #125: format 21's `seasonSource` reaches the `/v1` row with H3 (U-C7, h16), which drops this subtraction.
            MaintenanceScheduleDto.serializer().descriptor.names.filterNot { it == "seasonSource" } +
                listOf("seasonBehavior", "seasonReentry", "seasonReentryOffsetDays"),
            ScheduleRowResponse.serializer().descriptor.names,
        )
    }

    // --- a row sent straight back is refused ------------------------------------------------------

    /**
     * The behavioural half: a row read from a `GET` cannot be posted back unchanged, and the same
     * row minus the documented keys is accepted. Proved on the group and the schedule; the
     * completion command has no row of its own to send back — an `AssetEventDto` is further apart
     * still, exactly as `docs/api/v1.md` already says of `PATCH /v1/events/{id}` — so it is proved
     * by the unknown-field case below.
     */
    @Test fun aRowSentStraightBackIsRefusedAndTheStrippedRowIsAccepted() {
        val asset = createAsset("Pump A")
        val group = ApiJson.decodeFromString(
            GroupResponse.serializer(),
            call("POST", "/v1/groups", """{"name":"North run","members":[{"assetId":"$asset"}]}""").text(),
        ).group

        val rowJson = ApiJson.encodeToString(MaintenanceGroupDto.serializer(), group)
        val straightBack = call("PATCH", "/v1/groups/${group.id}", rowJson)
        assertEquals(400, straightBack.status)

        val stripped = ApiJson.decodeFromString(JsonObject.serializer(), rowJson).let { row ->
            buildJsonObject {
                row.forEach { (key, value) ->
                    if (key !in setOf("id", "archivedAt", "createdAt", "updatedAt")) put(key, value)
                }
            }
        }
        // The member rows inside still carry `addedAt`/`removedAt`, so the whole tree has to be
        // stripped — which is the point: a command is not a row.
        assertEquals(400, call("PATCH", "/v1/groups/${group.id}", stripped.toString()).status)

        val members = group.members.joinToString(",") {
            """{"id":"${it.id}","assetId":"${it.assetId}","sortOrder":${it.sortOrder}}"""
        }
        assertEquals(
            200,
            call(
                "PATCH", "/v1/groups/${group.id}",
                """{"name":"${group.name}","description":"${group.description}","members":[$members]}""",
            ).status,
        )

        val schedule = ApiJson.decodeFromString(
            ScheduleResponse.serializer(),
            call(
                "POST", "/v1/schedules",
                """{"title":"Filter change","targetAssetId":"$asset","timeInterval":1,
                   "timeUnit":"MONTH","anchorOn":"2026-02-01"}""",
            ).text(),
        ).schedule
        val scheduleRow = ApiJson.encodeToString(ScheduleRowResponse.serializer(), schedule)
        // 1.4: a schedule row carries both season forms — its policy and the derived 1.3 triple — so
        // the first thing wrong with it is the mix, refused by name before its identity keys are read
        // (spec §9.3). Either way it is refused and nothing is written.
        val scheduleStraightBack = call("PATCH", "/v1/schedules/${schedule.id}", scheduleRow)
        assertEquals(422, scheduleStraightBack.status)
        assertTrue(scheduleStraightBack.text(), scheduleStraightBack.text().contains("LEGACY_AND_CURRENT_FIELDS_MIXED"))
        assertEquals(
            200,
            call(
                "PATCH", "/v1/schedules/${schedule.id}",
                """{"title":"Filter change","targetAssetId":"$asset","timeInterval":1,
                   "timeUnit":"MONTH","anchorOn":"2026-02-01"}""",
            ).status,
        )
    }

    /** Each of the three commands refuses an unknown field with a 400 **naming it**. */
    @Test fun eachCommandRefusesAnUnknownFieldByName() {
        val asset = createAsset("Pump A")
        val group = ApiJson.decodeFromString(
            GroupResponse.serializer(),
            call("POST", "/v1/groups", """{"name":"North run","members":[{"assetId":"$asset"}]}""").text(),
        ).group
        val schedule = ApiJson.decodeFromString(
            ScheduleResponse.serializer(),
            call(
                "POST", "/v1/schedules",
                """{"title":"Filter change","targetAssetId":"$asset","timeInterval":1,
                   "timeUnit":"MONTH","anchorOn":"2026-02-01"}""",
            ).text(),
        ).schedule

        for ((path, body) in listOf(
            "/v1/groups" to """{"name":"South run","memebers":[]}""",
            "/v1/schedules" to """{"title":"x","targetAssetId":"$asset","timeIntervul":1}""",
            "/v1/schedules/${schedule.id}/complete" to
                """{"occurredOn":"2026-02-10","tzId":"UTC","occuredTime":"09:00"}""",
        )) {
            val response = call("POST", path, body)
            assertEquals("$path $body", 400, response.status)
            val misspelt = Regex("\"(\\w*(?:memebers|timeIntervul|occuredTime)\\w*)\"")
                .find(body)!!.groupValues[1]
            assertTrue(
                "$path did not name $misspelt: ${response.text()}",
                response.text().contains(misspelt),
            )
        }
        assertEquals(400, call("PATCH", "/v1/groups/${group.id}", """{"name":"x","membrs":[]}""").status)
    }

    // --- the derived read projection stays out of the format -------------------------------------

    /**
     * `ScheduleStateDto` is a **read projection**, deliberately outside `BackupData`: no command
     * accepts one and no archive carries one. Asserted structurally, because the failure mode is a
     * later reader of the "one schema on purpose" paragraph adding it for symmetry.
     */
    @Test fun scheduleStateIsInNoArchiveAndInNoCommand() {
        val backup = BackupData.serializer().descriptor
        for (index in 0 until backup.elementsCount) {
            val element = backup.getElementDescriptor(index)
            assertFalse(
                backup.getElementName(index),
                element.serialName.contains("ScheduleStateDto") ||
                    (0 until element.elementsCount).any { inner ->
                        element.getElementDescriptor(inner).serialName.contains("ScheduleStateDto")
                    },
            )
        }

        val stateOnly = ScheduleStateDto.serializer().descriptor.names.toSet() - setOf("scheduleId")
        for (descriptor in listOf(
            GroupCommandRequest.serializer().descriptor,
            ScheduleCommandRequest.serializer().descriptor,
            CompletionRequest.serializer().descriptor,
        )) {
            assertEquals(
                descriptor.serialName,
                emptySet<String>(),
                descriptor.names.toSet() intersect stateOnly,
            )
        }
    }

    // --- #114 (C29; row 81): the role on every schedule read, and in no command -------------------

    private val annualService =
        """"title":"Example annual service","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-01""""

    private fun tree(response: ApiResponse): JsonObject {
        assertTrue("${response.status}: ${response.text()}", response.status in 200..299)
        return Json.parseToJsonElement(response.text()).jsonObject
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    /** Every schedule row inside [element], at any depth: an object carrying `completionMode`. */
    private fun scheduleRowsIn(element: JsonElement): List<JsonObject> = when (element) {
        is JsonObject -> (if ("completionMode" in element) listOf(element) else emptyList()) +
            element.values.flatMap(::scheduleRowsIn)
        is JsonArray -> element.flatMap(::scheduleRowsIn)
        is JsonPrimitive -> emptyList()
    }

    private fun seasonalAsset(name: String): String = createAsset(name).also { asset ->
        val response = call(
            "POST", "/v1/assets/$asset/season-mode",
            """{"seasonMode":"CALENDAR","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}""",
        )
        assertEquals(response.text(), 200, response.status)
    }

    private fun createSchedule(target: String): String =
        tree(call("POST", "/v1/schedules", "{$annualService,$target}")).getValue("schedule").jsonObject.text("id")

    private fun giveRole(schedule: String) = runBlocking {
        graph.setScheduleRole.run(ScheduleId(schedule), ScheduleRole.SEASON_START, "2026-02-01", quickElected = true)
    }

    /**
     * Every response that carries a schedule carries `role` and `roleSinceOn` (response only): the reads — the list,
     * one schedule, an asset's, a group's — and the writes' answers. A role schedule reads `SEASON_START` and its
     * boundary; any other reads `NONE` and `null`.
     */
    @Test fun everyScheduleReadCarriesRoleAndRoleSinceOn() {
        val heater = seasonalAsset("Example Heater")
        val boiler = seasonalAsset("Example Boiler")
        val service = createSchedule(""""targetAssetId":"$heater"""")
        giveRole(service)
        val group = tree(call("POST", "/v1/groups", """{"name":"Example appliances","members":[{"assetId":"$boiler"}]}"""))
            .getValue("group").jsonObject.text("id")
        val check = createSchedule(""""targetGroupId":"$group"""")
        giveRole(check)
        val plain = createSchedule(""""targetAssetId":"$boiler"""")
        val roles = mapOf(service to "SEASON_START", check to "SEASON_START", plain to "NONE")

        fun assertRows(label: String, response: ApiResponse, expected: Set<String>) {
            val rows = scheduleRowsIn(tree(response))
            assertEquals(label, expected, rows.map { it.text("id") }.toSet())
            for (row in rows) {
                val id = row.text("id")
                assertEquals("$label $id", roles.getValue(id), row.text("role"))
                if (roles.getValue(id) == "NONE") {
                    assertEquals("$label $id", JsonNull, row.getValue("roleSinceOn"))
                } else {
                    assertEquals("$label $id", "2026-02-01", row.text("roleSinceOn"))
                }
            }
        }
        assertRows("list", call("GET", "/v1/schedules"), setOf(service, check, plain))
        assertRows("one", call("GET", "/v1/schedules/$service"), setOf(service))
        assertRows("asset", call("GET", "/v1/assets/$heater/schedules"), setOf(service))
        assertRows("group", call("GET", "/v1/groups/$group/schedules"), setOf(check))
        assertRows("update", call("PATCH", "/v1/schedules/$service", """{$annualService,"targetAssetId":"$heater"}"""), setOf(service))
        assertRows("pause", call("POST", "/v1/schedules/$service/pause", """{"paused":true}"""), setOf(service))
        assertRows("resume", call("POST", "/v1/schedules/$service/pause", """{"paused":false}"""), setOf(service))
        assertRows("postpone", call("POST", "/v1/schedules/$service/postpone", """{"postponedDueOn":null}"""), setOf(service))
        assertRows(
            "complete", call("POST", "/v1/schedules/$service/complete", """{"occurredOn":"2026-02-01","tzId":"UTC"}"""),
            setOf(service),
        )
        assertRows("archive", call("POST", "/v1/schedules/$plain/archive", """{"archived":true}"""), setOf(plain))
    }

    /** I16: a full-replace PATCH says nothing about the role, so the stored role and its boundary stay. */
    @Test fun aPatchWithoutRoleKeepsIt() {
        val heater = seasonalAsset("Example Heater")
        val service = createSchedule(""""targetAssetId":"$heater"""")
        giveRole(service)
        val answer = tree(
            call(
                "PATCH", "/v1/schedules/$service",
                """{"title":"Example annual service, renamed","targetAssetId":"$heater","timeInterval":2,"timeUnit":"YEAR","anchorOn":"2026-02-01"}""",
            ),
        ).getValue("schedule").jsonObject
        assertEquals("SEASON_START", answer.text("role"))
        assertEquals("2026-02-01", answer.text("roleSinceOn"))
        val stored = runBlocking { graph.schedules.get(ScheduleId(service)) }!!
        assertEquals("Example annual service, renamed", stored.title)
        assertEquals(ScheduleRole.SEASON_START, stored.role)
        assertEquals("2026-02-01", stored.roleSinceOn)
    }

    /** G18: `ignoreUnknownKeys = false`, so a schedule body carrying either role key is the 400 naming it. */
    @Test fun aBodyCarryingRoleIs400() {
        val heater = seasonalAsset("Example Heater")
        val service = createSchedule(""""targetAssetId":"$heater"""")
        val before = runBlocking { graph.schedules.all() }
        for ((key, value) in listOf("role" to "\"SEASON_START\"", "roleSinceOn" to "\"2026-02-01\"")) {
            for ((method, path) in listOf("POST" to "/v1/schedules", "PATCH" to "/v1/schedules/$service")) {
                val response = call(method, path, """{$annualService,"targetAssetId":"$heater","$key":$value}""")
                assertEquals("$method $path $key: ${response.text()}", 400, response.status)
                assertTrue("$method $path did not name $key: ${response.text()}", response.text().contains(key))
            }
        }
        assertEquals("nothing was written", before, runBlocking { graph.schedules.all() })
    }

    // --- #114 (G-R3, B; row 109): the event's `imported` marker, read only --------------------------

    private fun JsonObject.imported(): Boolean = getValue("imported").jsonPrimitive.boolean

    /**
     * Every event read carries `imported`: the list, and the event `POST /v1/events`, `PATCH /v1/events/{id}` and
     * `POST /v1/schedules/{id}/complete` answer. Only a Transfer Pack import on the phone sets it — here the store is
     * marked directly — and an edit keeps it.
     */
    @Test fun everyEventReadCarriesImported() {
        val heater = seasonalAsset("Example Heater")
        val service = createSchedule(""""targetAssetId":"$heater"""")
        val logged = tree(
            call("POST", "/v1/events", """{"assetId":"$heater","kind":"NOTE","title":"Example note","occurredOn":"2026-02-01","tzId":"UTC"}"""),
        ).getValue("event").jsonObject
        assertFalse("log", logged.imported())
        val completed = tree(
            call("POST", "/v1/schedules/$service/complete", """{"occurredOn":"2026-02-01","tzId":"UTC"}"""),
        ).getValue("event").jsonObject
        assertFalse("complete", completed.imported())

        val brought = completed.text("id")
        runBlocking { graph.uow.write { graph.events.upsert(graph.events.get(EventId(brought))!!.copy(imported = true)) } }
        val listed = tree(call("GET", "/v1/assets/$heater/events")).getValue("events").jsonArray.map { it.jsonObject }
        assertEquals("list", mapOf(logged.text("id") to false, brought to true), listed.associate { it.text("id") to it.imported() })

        val edited = tree(
            call(
                "PATCH", "/v1/events/$brought",
                """{"assetId":"$heater","kind":"${completed.text("kind")}","title":"${completed.text("title")}","occurredOn":"2026-02-01","tzId":"UTC","notes":"Example note"}""",
            ),
        ).getValue("event").jsonObject
        assertTrue("an edit keeps the marker", edited.imported())
        val editedNote = tree(
            call("PATCH", "/v1/events/${logged.text("id")}", """{"assetId":"$heater","kind":"NOTE","title":"Example note, edited","occurredOn":"2026-02-01","tzId":"UTC"}"""),
        ).getValue("event").jsonObject
        assertFalse("update", editedNote.imported())
    }

    /** G18: the marker is read only, so an event or completion body carrying it is the 400 naming it. */
    @Test fun anEventBodyCarryingImportedIs400() {
        val heater = seasonalAsset("Example Heater")
        val service = createSchedule(""""targetAssetId":"$heater"""")
        val event = tree(
            call("POST", "/v1/events", """{"assetId":"$heater","kind":"NOTE","title":"Example note","occurredOn":"2026-02-01","tzId":"UTC"}"""),
        ).getValue("event").jsonObject.text("id")
        val before = runBlocking { graph.events.all() }
        for (value in listOf("true", "false")) {
            for ((method, path, body) in listOf(
                Triple("POST", "/v1/events", """{"assetId":"$heater","kind":"NOTE","title":"Example note","occurredOn":"2026-02-01","tzId":"UTC","imported":$value}"""),
                Triple("PATCH", "/v1/events/$event", """{"assetId":"$heater","kind":"NOTE","title":"Example note","occurredOn":"2026-02-01","tzId":"UTC","imported":$value}"""),
                Triple("POST", "/v1/schedules/$service/complete", """{"occurredOn":"2026-02-01","tzId":"UTC","imported":$value}"""),
            )) {
                val response = call(method, path, body)
                assertEquals("$method $path: ${response.text()}", 400, response.status)
                assertTrue("$method $path did not name imported: ${response.text()}", response.text().contains("imported"))
            }
        }
        assertEquals("nothing was written", before, runBlocking { graph.events.all() })
    }
}
