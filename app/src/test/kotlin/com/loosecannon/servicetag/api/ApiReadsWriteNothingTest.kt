package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.HelperAttributes
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonActivation
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonBinding
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.seasonsync.NetworkReading
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 1.4 (B09) — the API half of invariants 105 and 86 (spec §4.7; master plan §8.6, §14): **no read
 * route writes**, not even over derived state stored before a season or break boundary; and **each
 * write route writes only its own use case's tables** — a season write touches no condition, a
 * condition write touches no asset row, and so on.
 *
 * The proof is table by table: every table a route could reach is read through its repository
 * before and after the call, and the set of tables whose rows changed is the assertion.
 */
class ApiReadsWriteNothingTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-01-20")
        today = LocalDate.parse("2026-01-20")
    }
    /** #113/#114 (C29b): helper discovery's list read, scripted (no Home Assistant is called); two helpers. */
    private val api = V1Client(
        graph,
        haEntities = {
            HaListOutcome.Listed(
                listOf(
                    HaEntityCandidate("input_select.example_heater_equipment_status", "Status", HelperAttributes(listOf("UP", "DEGRADED", "DOWN"), null, null)),
                    HaEntityCandidate("input_datetime.example_heater_service_done", "Done", HelperAttributes(null, true, false)),
                ),
            )
        },
    )

    @After fun close() = graph.close()

    /** Every table a 1.4 route could write, by its SQL name, as the set of its rows. */
    private fun snapshot(): Map<String, Set<Any>> = runBlocking {
        mapOf(
            "asset" to graph.assets.all().toSet(),
            "maintenance_schedule" to graph.schedules.all().toSet(),
            "schedule_state" to graph.scheduleStates.all().toSet(),
            "asset_event" to graph.events.all().toSet(),
            "asset_condition" to graph.conditions.all().toSet(),
            "asset_season_activation" to graph.seasonActivations.all().toSet(),
            "health_subject" to graph.healthSubjects.all().toSet(),
            "occurrence_closure" to graph.closures.all().toSet(),
            "maintenance_group" to graph.groups.all().toSet(),
            "measurement_definition" to graph.definitions.all().toSet(),
            "event_profile" to graph.profiles.all().toSet(),
            // #79b's aggregate: the header and its append-only timeline.
            "service_case" to graph.serviceCases.all().toSet(),
            "service_case_entry" to graph.serviceCaseEntries.all().toSet(),
            // #72's aggregate: the loans, open and returned.
            "asset_loan" to graph.loans.all().toSet(),
            // #113/#114 (row 108, D2): the Home Assistant tables, none of which a read may touch.
            "ha_publish_binding" to graph.publishBindings.all().toSet(),
            "ha_publish_delivery" to graph.publishBindings.all().mapNotNull { graph.publishDeliveries.get(it.id) }.toSet(),
            "ha_publish_decommission" to graph.publishDecommissions.all().toSet(),
            "ha_connection" to setOfNotNull(graph.haConnections.get()),
            "season_sync_binding" to graph.seasonSyncBindings.all().toSet(),
            // #125 (U-C7): a schedule's own season, its device-local binding and its rows.
            "schedule_season_sync_binding" to graph.scheduleSeasonBindings.all().toSet(),
            "schedule_season_activation" to graph.scheduleSeasonActivations.all().toSet(),
        )
    }

    /** The tables [call] changed. The clock moves first, so a rewrite of an identical row still shows. */
    private fun tablesWrittenBy(call: () -> ApiResponse): Set<String> {
        graph.now += 86_400_000L
        val before = snapshot()
        val response = call()
        assertTrue(response.bodyText(), response.status in 200..299)
        val after = snapshot()
        return before.keys.filter { before[it] != after[it] }.toSet()
    }

    private fun schedule(asset: String, title: String, season: String = ""): String {
        val tail = if (season.isEmpty()) "" else ",$season"
        return api.ok(
            ScheduleResponse.serializer(), "POST", "/v1/schedules",
            """{"title":"$title","targetAssetId":"$asset","timeInterval":1,"timeUnit":"MONTH","anchorOn":"2026-02-05"$tail}""",
            status = 201,
        ).schedule.id
    }

    private fun subjectBody(asset: String, name: String, driver: String = "AGE", schedule: String? = null): String {
        val link = schedule?.let { ""","scheduleId":"$it"""" } ?: ""
        return """{"assetId":"$asset","name":"$name","kind":"PART","driver":"$driver"$link,
            "nominalUntilDays":0,"warningFromDays":30,"criticalFromDays":60}"""
    }

    /**
     * Inv. 105 at the wire. Everything is built on 20 January; then it is 10 February — inside the
     * generator's maintenance break, which moves its held job's actionable date — so every stored
     * `schedule_state` row is stale. The five read routes the brief names, and the other 1.4 reads,
     * answer for today and **write nothing**: every table, the stale state rows included, is exactly
     * as it was.
     */
    @Test fun theReadRoutesWriteNothingOverStaleState() {
        val generator = api.asset("Generator")
        api.ok(
            AssetSeasonResponse.serializer(), "POST", "/v1/assets/$generator/maintenance-break",
            """{"blackoutStartMmdd":"02-01","blackoutEndMmdd":"02-28"}""",
        )
        val held = schedule(generator, "Load bank test", """"servicePolicy":"IN_SERVICE_AT_START"""")
        api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(generator, "Oil", "MAINTENANCE_OVERDUE", held), status = 201)
        api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(generator, "Belt"), status = 201)
        api.ok(ConditionResponse.serializer(), "POST", "/v1/assets/$generator/conditions", """{"condition":"DEGRADED","tzId":"UTC"}""", status = 201)
        val tub = api.asset("Hot tub")
        api.ok(AssetSeasonResponse.serializer(), "POST", "/v1/assets/$tub/season-mode", """{"seasonMode":"MANUAL","manualPhase":"IN_SEASON"}""")
        val waterChange = schedule(tub, "Water change", """"servicePolicy":"IN_SERVICE_RESUME_CLAMPED"""")
        // #114 (row 83): a Season Start role, so the readiness read runs C9 over a role, its schedule and its events.
        runBlocking { graph.setScheduleRole.run(ScheduleId(waterChange), ScheduleRole.SEASON_START, "2026-01-20", quickElected = true) }
        api.ok(CompletionResponse.serializer(), "POST", "/v1/schedules/$waterChange/complete", """{"occurredOn":"2026-01-20","tzId":"UTC"}""", status = 201)
        val case = api.ok(
            ServiceCaseResponse.serializer(), "POST", "/v1/service-cases",
            """{"assetId":"$generator","title":"Starter claim","type":"REPAIR","openedOn":"2026-01-19","coverage":"UNKNOWN"}""",
            status = 201,
        ).serviceCase.id
        api.ok(
            CaseEntryResponse.serializer(), "POST", "/v1/service-cases/$case/entries",
            """{"occurredOn":"2026-01-20","tzId":"UTC","note":"Sent out","status":"SENT_OUT"}""", status = 201,
        )
        // #72: a loan past its due date by the read's today — overdue is derived at read time, never stored.
        val loan = api.ok(
            LoanResponse.serializer(), "POST", "/v1/loans",
            """{"assetId":"$generator","borrowerName":"Sample Borrower","lentOn":"2026-01-19","dueOn":"2026-02-01","reminderMode":"ONCE"}""",
            status = 201,
        ).loan.id

        // Row 108: rows in the publishing tables, so the three #113/#114 reads have something to leave alone — seeded so
        // that a read-side projection or boundary write would visibly move them (B5r review I1): the generator's
        // stored `UP` against its recorded `DEGRADED`, and the hot tub's date binding following Home Assistant with no
        // boundary read cached and no value projected.
        runBlocking {
            graph.haConnections.upsert(
                HaConnection(
                    id = "example-connection", baseUrl = "http://192.168.0.10:8123", cadence = SyncCadence.DAILY,
                    networkEligibility = NetworkEligibility.HOME_NETWORK_ONLY, homeNetworkSsid = "ExampleHomeWifi",
                    backgroundChecks = BackgroundChecks.OFF, createdAt = 1_000L, updatedAt = 1_000L, configGeneration = 1,
                ),
            )
            graph.publishBindings.insert(
                PublishBinding(
                    id = "example-binding-1", assetId = AssetId(generator), connectionId = "example-connection",
                    kind = PublishKind.EQUIPMENT_STATUS, entityId = "input_select.example_generator_status", enabled = true,
                    revision = 2, activatedInstallationId = "example-installation", validatedAt = 1L, validatedRevision = 2,
                    boundaryEntityId = null, createdAt = 1L, updatedAt = 1L,
                ),
            )
            graph.publishDeliveries.upsert(PublishDelivery("example-binding-1", "UP", 3, 1L))
            graph.publishBindings.insert(
                PublishBinding(
                    id = "example-binding-2", assetId = AssetId(tub), connectionId = "example-connection",
                    kind = PublishKind.SEASON_SERVICE_DATE, entityId = "input_datetime.example_heater_service_done",
                    enabled = true, revision = 2, activatedInstallationId = "example-installation", validatedAt = 1L,
                    validatedRevision = 2, boundaryEntityId = "input_datetime.example_heater_season_ended",
                    createdAt = 1L, updatedAt = 1L,
                ),
            )
            graph.publishDeliveries.upsert(PublishDelivery("example-binding-2", null, 0, 1L))
            graph.seasonSyncBindings.insert(
                SeasonSyncBinding(
                    AssetId(tub), "example-connection", "input_boolean.example_heater_in_season", SyncMode.FOLLOW,
                    enabled = true, revision = 1, observedState = null, observedChangedAt = null, lastSuccessAt = null,
                    lastAttemptAt = null, errorKind = null, errorDetail = null, errorAt = null, appliedAction = null,
                    appliedOn = null, appliedAt = null, lastAppliedSource = null, createdAt = 1L, updatedAt = 1L,
                ),
            )
            // #125 (h15): the held job follows its own switch, with one row of its own season, so its read has a
            // binding, a stale state row and an own season to leave alone.
            graph.uow.write {
                val stored = graph.schedules.get(ScheduleId(held))!!
                graph.schedules.upsert(stored.copy(seasonSource = ScheduleSeasonSource.OWN))
            }
            graph.scheduleSeasonBindings.insert(
                ScheduleSeasonBinding(
                    ScheduleId(held), "example-connection", "input_boolean.example_generator_load_season", SyncMode.FOLLOW,
                    enabled = true, revision = 1, observedState = null, observedChangedAt = null, lastSuccessAt = null,
                    lastAttemptAt = null, errorKind = null, errorDetail = null, errorAt = null, appliedAction = null,
                    appliedOn = null, appliedAt = null, lastAppliedSource = null, createdAt = 1L, updatedAt = 1L,
                ),
            )
            graph.scheduleSeasonActivations.insert(
                ScheduleSeasonActivation("example-own-start", ScheduleId(held), SeasonAction.START, "2026-01-15", 1L),
            )
            graph.publishDecommissions.upsertMerging(
                DecommissionTombstone(
                    "example-tombstone-1", "example-connection", "input_datetime.example_gone", null, "example-installation",
                    DecommissionState.PENDING, 1L, null, null, 1,
                ),
            )
        }

        graph.today = LocalDate.parse("2026-02-10")
        graph.now = dayMillis("2026-02-10")
        val before = snapshot()
        assertTrue("the publishing tables are not empty", before.getValue("ha_publish_binding").isNotEmpty())
        assertTrue("a season sync binding is seeded", before.getValue("season_sync_binding").isNotEmpty())
        assertTrue("the stored state is stale", runBlocking { graph.scheduleStates.all() }.all { it.computedForOn == "2026-01-20" })

        for (path in listOf(
            "/v1/due",
            "/v1/attention",
            "/v1/assets/$generator/health",
            "/v1/schedules/$held",
            "/v1/assets/$generator/season",
            "/v1/assets/$tub/season",
            "/v1/assets/$generator/conditions",
            "/v1/assets/$generator/health-subjects",
            // #79: the warranty, derived for today like the health and the season.
            "/v1/assets/$generator/warranty",
            // #79b: an asset's cases, and one case with its timeline.
            "/v1/assets/$generator/service-cases",
            "/v1/service-cases/$case",
            // #72: an asset's loans, and one loan.
            "/v1/assets/$generator/loans",
            "/v1/loans/$loan",
            "/v1/schedules",
            "/v1/status",
            // #114 (C29): readiness, derived for today and stored nowhere — with no role, and with one.
            "/v1/assets/$generator/season-readiness",
            "/v1/assets/$tub/season-readiness",
            // #113/#114 (C29b, row 114): helper discovery reads Home Assistant and writes nothing here; row 108 re-runs
            // it over the publishing tables.
            "/v1/ha-connection/entities/input_select",
            "/v1/ha-connection/entities/input_datetime",
            "/v1/ha-connection/entities/input_boolean",
            // #113/#114 (C35): the asset's publishing, derived from its rows and written nowhere.
            "/v1/assets/$generator/ha-publishing",
            // B5r (C35a): the collection, over the same snapshot (row 108).
            "/v1/ha-publishing",
            // #125 (U-C7, row h15): a schedule's own season, derived for today — its phase, binding state and rows.
            "/v1/schedules/$held/season-sync",
        )) {
            val response = api.call("GET", path)
            assertEquals("$path: ${response.bodyText()}", 200, response.status)
            assertEquals(path, before, snapshot())
        }
        // B5w1 (C35b, row 117): the publishing plan is a POST that reads — one fresh read of the helper from the
        // scripted Home Assistant, a plan id, and no row of any table written.
        runBlocking { graph.secretStore.put("example-connection", Secret("fictional-token-1")) }
        graph.haPublish.helpers["input_select.example_generator_status"] = RemoteEntity(
            "input_select.example_generator_status", "DOWN", listOf("UP", "DEGRADED", "DOWN"), null, null,
        )
        val beforePlan = snapshot()
        val plan = api.call(
            "POST", "/v1/assets/$generator/ha-publishing-plan",
            """{"kind":"EQUIPMENT_STATUS","action":"ENABLE","entityId":"input_select.example_generator_status"}""",
        )
        assertEquals(plan.bodyText(), 200, plan.status)
        assertTrue(plan.bodyText(), plan.bodyText().contains("\"refusal\":null"))
        assertEquals("ha-publishing-plan", beforePlan, snapshot())
        assertTrue(graph.haPublish.posts.isEmpty())
        // And what the reads answered was today's derivation, not the stored row.
        val detail = api.ok(ScheduleDetailResponse.serializer(), "GET", "/v1/schedules/$held")
        assertEquals("2026-02-10", detail.computedForOn)
        assertEquals("2026-03-01", detail.state.actionableDueOn)
        assertEquals("DEFERRED", detail.status)

        // The positive control (B5r review I1): the seed is one a projection visibly moves, so a read that projected
        // would have failed the loop above.
        runBlocking { graph.publishProjector.reconcileDesired(graph.today) }
        assertNotEquals(
            "a projection moves the seeded deliveries", before.getValue("ha_publish_delivery"), snapshot().getValue("ha_publish_delivery"),
        )
    }

    /**
     * Inv. 86 and 81 at the wire: each 1.4 write route, and the widened schedule writes, change
     * exactly the tables their one use case owns — a season write never records a condition, a
     * condition never stamps its asset, a subject never touches a schedule — and the recompute's
     * `schedule_state` only where a season, break, activation or schedule moved.
     */
    @Test fun eachWriteRouteWritesOnlyItsUseCasesTables() {
        graph.today = LocalDate.parse("2026-02-10")
        val generator = api.asset("Generator")
        val tub = api.asset("Hot tub")
        val oilChange = schedule(generator, "Oil change")
        schedule(tub, "Water change")
        val heater = api.ok(
            AssetResponse.serializer(), "POST", "/v1/assets", """{"name":"Example Heater","warrantyExpiresOn":"2026-06-30"}""",
            status = 201,
        ).asset.id

        val expectations = listOf<Pair<Set<String>, () -> ApiResponse>>(
            setOf("asset", "schedule_state") to {
                api.call("POST", "/v1/assets/$generator/season-mode", """{"seasonMode":"CALENDAR","seasonStartMmdd":"04-01","seasonEndMmdd":"10-31"}""")
            },
            setOf("asset", "schedule_state") to {
                api.call("POST", "/v1/assets/$generator/maintenance-break", """{"blackoutStartMmdd":"07-01","blackoutEndMmdd":"07-31"}""")
            },
            setOf("asset", "asset_season_activation", "schedule_state") to {
                api.call("POST", "/v1/assets/$tub/season-mode", """{"seasonMode":"MANUAL","manualPhase":"IN_SEASON"}""")
            },
            setOf("asset_season_activation", "schedule_state") to {
                api.call("POST", "/v1/assets/$tub/season", """{"action":"END"}""")
            },
            setOf("asset_condition") to {
                api.call("POST", "/v1/assets/$generator/conditions", """{"condition":"DOWN","tzId":"UTC","reason":"Will not start"}""")
            },
            setOf("health_subject") to {
                api.call("POST", "/v1/health-subjects", subjectBody(generator, "Starter battery"))
            },
            // #79: the lead is the asset row alone — no schedule, no recompute.
            setOf("asset") to {
                api.call("POST", "/v1/assets/$heater/warranty-reminder", """{"leadDays":30}""")
            },
        )
        for ((expected, write) in expectations) assertEquals(expected, tablesWrittenBy(write))

        // #79b (C16): a case writes its own two tables and nothing else — no event, no condition, no
        // schedule or its state — and a note-only entry never touches the header (R79-5).
        var case = ""
        assertEquals(
            setOf("service_case"),
            tablesWrittenBy {
                api.call(
                    "POST", "/v1/service-cases",
                    """{"assetId":"$heater","title":"Heater claim","type":"WARRANTY_SERVICE","openedOn":"2026-02-09","coverage":"IN_WARRANTY"}""",
                ).also { case = ApiJson.decodeFromString(ServiceCaseResponse.serializer(), it.bodyText()).serviceCase.id }
            },
        )
        assertEquals(
            setOf("service_case"),
            tablesWrittenBy {
                api.call(
                    "PATCH", "/v1/service-cases/$case",
                    """{"title":"Heater claim, element","type":"WARRANTY_SERVICE","openedOn":"2026-02-09","coverage":"IN_WARRANTY"}""",
                )
            },
        )
        assertEquals(
            setOf("service_case_entry"),
            tablesWrittenBy { api.call("POST", "/v1/service-cases/$case/entries", """{"occurredOn":"2026-02-10","tzId":"UTC","note":"Called"}""") },
        )
        assertEquals(
            setOf("service_case_entry", "service_case"),
            tablesWrittenBy { api.call("POST", "/v1/service-cases/$case/entries", """{"occurredOn":"2026-02-10","tzId":"UTC","status":"CLOSED"}""") },
        )

        // #72 (C21; R72-13, R72-15): a loan writes its own row and nothing else — no event, no condition, no
        // schedule or its state, no health value — and its reminder settles at the next sweep, not here.
        var loan = ""
        assertEquals(
            setOf("asset_loan"),
            tablesWrittenBy {
                api.call(
                    "POST", "/v1/loans",
                    """{"assetId":"$heater","borrowerName":"Sample Borrower","lentOn":"2026-02-09","dueOn":"2026-02-20","reminderMode":"ONCE"}""",
                ).also { loan = ApiJson.decodeFromString(LoanResponse.serializer(), it.bodyText()).loan.id }
            },
        )
        assertEquals(
            setOf("asset_loan"),
            tablesWrittenBy { api.call("PATCH", "/v1/loans/$loan", """{"lentOn":"2026-02-09","dueOn":"2026-02-21","reminderMode":"UNTIL_RETURNED"}""") },
        )
        assertEquals(
            setOf("asset_loan"),
            tablesWrittenBy { api.call("POST", "/v1/loans/$loan/return", """{"returnedOn":"2026-02-10"}""") },
        )

        val battery = runBlocking { graph.healthSubjects.all() }.single().id.value
        assertEquals(
            setOf("health_subject"),
            tablesWrittenBy {
                api.call(
                    "PATCH", "/v1/health-subjects/$battery",
                    """{"name":"Starter battery (12V)","kind":"PART","driver":"AGE","nominalUntilDays":0,"warningFromDays":30,"criticalFromDays":60}""",
                )
            },
        )
        assertEquals(
            setOf("asset"),
            tablesWrittenBy {
                api.call("POST", "/v1/assets/$generator/health-policy", """{"healthAggregation":"TRACK_ONE","healthPrimarySubjectId":"$battery"}""")
            },
        )
        val belt = api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(generator, "Belt"), status = 201).subject.id
        assertEquals(setOf("health_subject"), tablesWrittenBy { api.call("POST", "/v1/health-subjects/$belt/archive", """{"archived":true}""") })

        // The widened schedule writes: the flag archives the driven subject in the same write.
        api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(generator, "Oil", "MAINTENANCE_OVERDUE", oilChange), status = 201)
        assertEquals(
            setOf("maintenance_schedule", "health_subject", "schedule_state"),
            tablesWrittenBy {
                api.call(
                    "PATCH", "/v1/schedules/$oilChange",
                    """{"title":"Oil change","targetAssetId":"$tub","timeInterval":1,"timeUnit":"MONTH","anchorOn":"2026-02-05","unlinkHealthSubject":true}""",
                )
            },
        )
        // #114 (C10, C29b; row 113): the role route writes the schedule row and no event; since #124 (C41) the same write
        // rebuilds the covered asset's derived state, which a schedule counting from its opening service reads.
        assertEquals(
            setOf("maintenance_schedule", "schedule_state"),
            tablesWrittenBy { api.call("POST", "/v1/schedules/$oilChange/role", """{"role":"SEASON_START","seasonStartQuickElected":true}""") },
        )
        assertEquals(
            setOf("maintenance_schedule", "schedule_state"),
            tablesWrittenBy { api.call("POST", "/v1/schedules/$oilChange/role", """{"role":"NONE"}""") },
        )
        val coolant = schedule(generator, "Coolant flush")
        api.ok(SubjectResponse.serializer(), "POST", "/v1/health-subjects", subjectBody(generator, "Coolant", "MAINTENANCE_OVERDUE", coolant), status = 201)
        assertEquals(
            setOf("maintenance_schedule", "health_subject", "schedule_state"),
            tablesWrittenBy { api.call("POST", "/v1/schedules/$coolant/archive", """{"archived":true,"unlinkHealthSubject":true}""") },
        )
        assertEquals(
            setOf("maintenance_schedule", "schedule_state"),
            tablesWrittenBy {
                api.call(
                    "POST", "/v1/schedules",
                    """{"title":"Spark plugs","targetAssetId":"$generator","timeInterval":1,"timeUnit":"YEAR","anchorOn":"2026-02-05",
                       "servicePolicy":"IN_SERVICE_AT_START"}""",
                )
            },
        )

        // B5w2 (C35b; row 120): the publishing controls write only the publishing tables, each awaited to its end
        // (the delivery it asked for included), and the acknowledgement only its tombstone.
        val status = "input_select.example_heater_equipment_status"
        val date = "input_datetime.example_heater_service_done"
        runBlocking {
            graph.saveHaConnection.run(
                "http://192.168.0.10:8123", Secret("fictional-token-1"), null, NetworkEligibility.HOME_NETWORK_ONLY,
                "ExampleHomeWifi", null,
            )
        }
        graph.haPublish.helpers[status] = RemoteEntity(status, "unknown", listOf("UP", "DEGRADED", "DOWN"), null, null)
        graph.haPublish.helpers[date] = RemoteEntity(date, "2025-11-15", null, true, false)
        for ((kind, entity) in listOf("EQUIPMENT_STATUS" to status, "SEASON_SERVICE_DATE" to date)) {
            val plan = api.call(
                "POST", "/v1/assets/$heater/ha-publishing-plan", """{"kind":"$kind","action":"ENABLE","entityId":"$entity"}""",
            )
            val planId = Json.parseToJsonElement(plan.bodyText()).jsonObject.getValue("planId").jsonPrimitive.content
            val enabled = api.call(
                "POST", "/v1/assets/$heater/ha-publishing",
                """{"kind":"$kind","action":"ENABLE","planId":"$planId","confirmDestination":"$entity"}""",
            )
            assertEquals(enabled.bodyText(), 200, enabled.status)
        }
        runBlocking { graph.awaitDispatched() }
        fun awaited(call: () -> ApiResponse): () -> ApiResponse = { call().also { runBlocking { graph.awaitDispatched() } } }
        assertEquals(
            setOf("ha_publish_binding"),
            tablesWrittenBy(awaited { api.call("POST", "/v1/assets/$heater/ha-publishing", """{"kind":"EQUIPMENT_STATUS","action":"CHECK_HELPER"}""") }),
        )
        // A condition to publish, not yet projected (no observer is started here): Publish now projects it first (I18).
        api.call("POST", "/v1/assets/$heater/conditions", """{"condition":"DOWN","tzId":"UTC"}""")
        assertEquals(
            setOf("ha_publish_delivery"),
            tablesWrittenBy(awaited { api.call("POST", "/v1/assets/$heater/ha-publishing/publish-now", "{}") }),
        )
        // Away from home, so the date helper's obligation stays to be acknowledged: Disable reads nothing either way.
        graph.currentNetwork = NetworkReading(CurrentNetwork.Wifi("ExampleOtherWifi"), null)
        assertEquals(
            setOf("ha_publish_binding", "ha_publish_decommission"),
            tablesWrittenBy(awaited { api.call("POST", "/v1/assets/$heater/ha-publishing", """{"kind":"SEASON_SERVICE_DATE","action":"DISABLE"}""") }),
        )
        val owed = runBlocking { graph.publishDecommissions.all() }.single()
        runBlocking { graph.publishDecommissions.upsertMerging(owed.copy(state = DecommissionState.UNDELIVERABLE)) }
        assertEquals(
            setOf("ha_publish_decommission"),
            tablesWrittenBy(
                awaited {
                    api.call(
                        "POST", "/v1/ha-publishing/decommissions/${owed.id}/acknowledge",
                        """{"decommissionId":"${owed.id}","acknowledgedClearedInHa":true}""",
                    )
                },
            ),
        )
    }
}
