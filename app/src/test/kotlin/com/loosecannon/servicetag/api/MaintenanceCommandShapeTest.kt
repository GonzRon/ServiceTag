package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.GroupMemberDto
import com.loosecannon.servicetag.core.backup.MaintenanceGroupDto
import com.loosecannon.servicetag.core.backup.MaintenanceScheduleDto
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
            graph.events, graph.attachments,
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
     * response-only, so it is subtracted with the identity keys, and nothing else is.
     */
    @Test fun theScheduleCommandIsTheScheduleRowMinusIdentityBookkeepingAndThePostponement() {
        val fromRow = ScheduleRowResponse.serializer().descriptor.names
            .filterNot { it in setOf("id", "status", "createdAt", "updatedAt", "postponedDueOn", "ruleChangedAt") }
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
            MaintenanceScheduleDto.serializer().descriptor.names +
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
}
