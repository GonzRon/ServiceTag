package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefusal
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncLinkRefused
import com.loosecannon.servicetag.seasonsync.HOME_WIFI
import com.loosecannon.servicetag.seasonsync.TOKEN
import com.loosecannon.servicetag.seasonsync.connect
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #129 (L-C8; rows k12–k17) — `POST /v1/assets/{id}/season-sync` over the production router and `FakeGraph`, on the
 * JVM: each of the six actions runs the phone's own command and answers 200 in `GET`'s shape plus `switchedFrom`; the
 * two confirmations are the caller's own and never defaulted; the body is strict and takes no season state, no address,
 * no Wi-Fi name and no token; every refusal is a code by name with nothing written; and the connection and its token
 * have no route. No Home Assistant is called: the reader is scripted. Every name is fictional: the asset, the helpers
 * `input_boolean.example_heater_in_season` and `input_boolean.example_heater_season`, the addresses
 * `http://192.168.0.10:8123` and `https://ha.example:8123`, the Wi-Fi name `ExampleHomeWifi` and the token
 * `fictional-token-1`.
 */
class SeasonSyncWriteRoutesTest {

    private val graph = FakeGraph().apply {
        now = dayMillis("2026-10-20")
        today = LocalDate.parse("2026-10-20")
    }
    private val api = V1Client(graph)

    @After fun close() = graph.close()

    private val inSeason = "input_boolean.example_heater_in_season"
    private val season = "input_boolean.example_heater_season"

    // --- fixtures -------------------------------------------------------------------------------

    private fun path(asset: String) = "/v1/assets/$asset/season-sync"

    /** Every answer of this suite, scanned (k16): no address, no Wi-Fi name and no token is a byte of any body. */
    private fun post(asset: String, body: String): ApiResponse {
        val response = api.call("POST", path(asset), body)
        val bytes = response.bodyText()
        for (secret in listOf("192.168.0.10", "ha.example", HOME_WIFI, TOKEN.value)) {
            assertFalse("the answer carries $secret: $bytes", bytes.contains(secret))
        }
        return response
    }

    private fun link(asset: String, entity: String = inSeason, confirm: String? = entity): ApiResponse {
        val confirmation = confirm?.let { ""","confirmEntityId":"$it"""" } ?: ""
        return post(asset, """{"action":"LINK","entityId":"$entity"$confirmation}""")
    }

    private fun change(asset: String, entity: String = season, confirm: String? = entity): ApiResponse {
        val confirmation = confirm?.let { ""","confirmEntityId":"$it"""" } ?: ""
        return post(asset, """{"action":"CHANGE_ENTITY","entityId":"$entity"$confirmation}""")
    }

    private fun act(asset: String, action: String) = post(asset, """{"action":"$action"}""")

    private fun setMode(asset: String, mode: String) = post(asset, """{"action":"SET_MODE","mode":"$mode"}""")

    private fun unlink(asset: String) = post(asset, """{"action":"UNLINK","confirmUnlink":true}""")

    private fun tree(response: ApiResponse): JsonObject = Json.parseToJsonElement(response.bodyText()).jsonObject

    /** A 200 in L-C8's shape: `GET`'s three keys and `switchedFrom`. */
    private fun answer(response: ApiResponse): JsonObject {
        assertEquals(response.bodyText(), 200, response.status)
        val body = tree(response)
        assertEquals(setOf("assetId", "connection", "binding", "switchedFrom"), body.keys)
        return body
    }

    private fun JsonObject.binding(): JsonObject = getValue("binding").jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.switchedFrom(): String? = getValue("switchedFrom").takeUnless { it == JsonNull }?.jsonPrimitive?.content

    private fun seasonMode(asset: String, body: String) {
        val response = api.call("POST", "/v1/assets/$asset/season-mode", body)
        assertEquals(response.bodyText(), 200, response.status)
    }

    private fun manual(asset: String, phase: String = "OUT_OF_SEASON") =
        seasonMode(asset, """{"seasonMode":"MANUAL","manualPhase":"$phase"}""")

    private fun calendar(asset: String) =
        seasonMode(asset, """{"seasonMode":"CALENDAR","seasonStartMmdd":"11-01","seasonEndMmdd":"03-31"}""")

    /** A MANUAL asset out of season, linked over the route, its first read settled. */
    private fun linkedManual(name: String): String {
        val asset = api.asset(name)
        manual(asset)
        answer(link(asset))
        settle()
        return asset
    }

    private fun settle() = runBlocking {
        graph.awaitSeasonSyncReads()
        graph.haStateReader.reads.clear()
    }

    private fun rows(asset: String) = runBlocking { graph.seasonActivations.forAsset(AssetId(asset)) }

    private fun stored(asset: String) = runBlocking { graph.seasonSyncBindings.get(AssetId(asset)) }

    /** Every row an action or a refusal could reach. */
    private fun snapshot(): Map<String, Set<Any>> = runBlocking {
        mapOf(
            "asset" to graph.assets.all().toSet(),
            "season_sync_binding" to graph.seasonSyncBindings.all().toSet(),
            "asset_season_activation" to graph.seasonActivations.all().toSet(),
            "ha_connection" to setOfNotNull(graph.haConnections.get()),
            "maintenance_schedule" to graph.schedules.all().toSet(),
            "schedule_season_sync_binding" to graph.scheduleSeasonBindings.all().toSet(),
            "ha_publish_binding" to graph.publishBindings.all().toSet(),
        )
    }

    /** [call] is refused with [status] and [code] (and [problem] among its body), nothing moves and no read runs. */
    private fun assertRefused(status: Int, code: String, problem: String? = null, call: () -> ApiResponse): ApiResponse {
        settle()
        graph.now += 86_400_000L
        val before = snapshot()
        val response = call()
        assertEquals(response.bodyText(), status, response.status)
        assertEquals(response.bodyText(), code, response.errorDetail().code)
        if (problem != null) assertTrue(response.bodyText(), response.bodyText().contains(problem))
        assertEquals("nothing was written", before, snapshot())
        runBlocking { graph.awaitSeasonSyncReads() }
        assertTrue("no read was launched", graph.haStateReader.reads.isEmpty())
        return response
    }

    private fun assertConfirmation(field: String, message: String, call: () -> ApiResponse) {
        val response = assertRefused(422, "SEASON_SYNC_CONFIRMATION_REQUIRED", "SeasonSyncConfirmationRequired(field=$field)", call)
        val detail = response.errorDetail()
        assertEquals(response.bodyText(), field, detail.field)
        assertEquals(response.bodyText(), message, detail.message)
    }

    private fun assertBadRequest(call: () -> ApiResponse) = assertRefused(400, "bad_request", call = call)

    private fun assertSeasonSyncRefused(reason: SeasonSyncLinkRefusal, call: () -> ApiResponse) =
        assertRefused(409, "SEASON_SYNC_REFUSED", "SeasonSyncLinkRefused(reason=${reason.name})", call)

    // --- k12: each action through the graph ---------------------------------------------------

    /** D3: a CALENDAR asset is linked with no extra confirmation, switched into MANUAL at today's phase, and says so. */
    @Test fun linkOfACalendarAssetSwitchesItIntoManualAndSaysFromWhat() {
        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.OFF, null) }
        runBlocking { graph.connect() }
        val heater = api.asset("Example Heater")
        calendar(heater)
        assertTrue(rows(heater).isEmpty())

        val linked = answer(link(heater))

        assertEquals("CALENDAR", linked.switchedFrom())
        assertEquals(heater, linked.text("assetId"))
        assertEquals(inSeason, linked.binding().text("entityId"))
        assertEquals("FOLLOW", linked.binding().text("mode"))
        assertEquals("ACTIVE", linked.binding().text("state"))
        assertEquals(SeasonMode.MANUAL, runBlocking { graph.assets.get(AssetId(heater))!!.seasonMode })
        val switchRow = rows(heater).single()
        assertEquals("the switch row is dated today", "2026-10-20", switchRow.occurredOn)
        assertEquals("out of its calendar season on 10-20", "END", switchRow.action.name)
        runBlocking { graph.awaitSeasonSyncReads() }
        assertEquals("the link launched its one read", listOf(inSeason), graph.haStateReader.reads.map { it.entityId })
    }

    /** CHANGE_ENTITY runs the shipped edit: the new helper, the old reading cleared, the provenance kept, a fresh read. */
    @Test fun changeEntityPointsTheLinkAtAnotherHelperAndKeepsTheHistory() {
        runBlocking { graph.connect() }
        graph.haStateReader.answer = { HaReadOutcome.Observed(HaSwitchState.ON, "2026-10-20T06:00:00+00:00") }
        val heater = linkedManual("Example Heater")
        val history = rows(heater)
        assertEquals("the first read started the season", listOf("END", "START"), history.map { it.action.name }.sorted())
        val before = stored(heater)!!

        val changed = answer(change(heater))

        assertNull(changed.switchedFrom())
        val binding = changed.binding()
        assertEquals(season, binding.text("entityId"))
        assertEquals(JsonNull, binding.getValue("observation"))
        assertEquals(JsonNull, binding.getValue("lastSuccessAt"))
        assertEquals("START", binding.getValue("lastApplied").jsonObject.text("action"))
        val after = stored(heater)!!
        assertEquals(before.revision + 1, after.revision)
        assertEquals(before.appliedAt, after.appliedAt)
        assertEquals("the history is kept", history, rows(heater))
        runBlocking { graph.awaitSeasonSyncReads() }
        assertEquals("the new helper is read at once", listOf(season), graph.haStateReader.reads.map { it.entityId })
    }

    /** STOP and RESUME: a MANUAL asset resumes with `switchedFrom: null`; a YEAR_ROUND one is switched and says so. */
    @Test fun stopAndResumeAnswerTheirBindingAndWhatAResumeSwitched() {
        runBlocking { graph.connect() }
        val heater = linkedManual("Example Heater")

        val stopped = answer(act(heater, "STOP"))
        assertNull(stopped.switchedFrom())
        assertFalse(stopped.binding().getValue("enabled").jsonPrimitive.boolean)
        assertEquals("STOPPED", stopped.binding().text("state"))
        runBlocking { graph.awaitSeasonSyncReads() }
        assertTrue("a stop reads nothing", graph.haStateReader.reads.isEmpty())

        val resumed = answer(act(heater, "RESUME"))
        assertNull("a MANUAL asset is not switched", resumed.switchedFrom())
        assertEquals("ACTIVE", resumed.binding().text("state"))
        settle()

        answer(act(heater, "STOP"))
        seasonMode(heater, """{"seasonMode":"YEAR_ROUND"}""")
        val rowsBefore = rows(heater)

        val switched = answer(act(heater, "RESUME"))

        assertEquals("YEAR_ROUND", switched.switchedFrom())
        assertEquals("ACTIVE", switched.binding().text("state"))
        assertEquals(SeasonMode.MANUAL, runBlocking { graph.assets.get(AssetId(heater))!!.seasonMode })
        val switchRow = (rows(heater) - rowsBefore.toSet()).single()
        assertEquals("one switch row, in season as year-round was, dated today", "START", switchRow.action.name)
        assertEquals("2026-10-20", switchRow.occurredOn)
    }

    /** SET_MODE: FORCE_IN applies the season now through the applier; FOLLOW again writes no row. */
    @Test fun setModeForceInAppliesNowAndFollowWritesNoRow() {
        runBlocking { graph.connect() }
        val heater = linkedManual("Example Heater")
        val before = rows(heater)

        val forced = answer(setMode(heater, "FORCE_IN"))

        assertNull(forced.switchedFrom())
        assertEquals("FORCE_IN", forced.binding().text("mode"))
        val applied = forced.binding().getValue("lastApplied").jsonObject
        assertEquals("FORCED_IN", applied.text("source"))
        assertEquals("START", applied.text("action"))
        val after = rows(heater)
        val added = after - before.toSet()
        assertEquals("one START", before.size + 1, after.size)
        assertEquals("START", added.single().action.name)
        assertEquals("2026-10-20", added.single().occurredOn)

        val following = answer(setMode(heater, "FOLLOW"))
        assertEquals("FOLLOW", following.binding().text("mode"))
        runBlocking { graph.awaitSeasonSyncReads() }
        assertEquals("FOLLOW writes no row", after, rows(heater))
    }

    /** UNLINK deletes this asset's binding only, answers `binding: null`, and a second UNLINK is a 200 writing nothing. */
    @Test fun unlinkDeletesTheBindingAndASecondUnlinkWritesNothing() {
        runBlocking { graph.connect() }
        val heater = linkedManual("Example Heater")
        val boiler = linkedManual("Example Boiler")
        val history = rows(heater)
        val connection = runBlocking { graph.haConnections.get() }

        val unlinked = answer(unlink(heater))

        assertEquals(JsonNull, unlinked.getValue("binding"))
        assertNull(unlinked.switchedFrom())
        assertNull(stored(heater))
        assertNotNull("the other link stays", stored(boiler))
        assertEquals("the connection stays", connection, runBlocking { graph.haConnections.get() })
        assertEquals("the history stays", history, rows(heater))
        assertEquals(SeasonMode.MANUAL, runBlocking { graph.assets.get(AssetId(heater))!!.seasonMode })

        settle()
        graph.now += 86_400_000L
        val before = snapshot()
        val again = answer(unlink(heater))
        assertEquals(JsonNull, again.getValue("binding"))
        assertEquals("a second unlink writes nothing", before, snapshot())
    }

    /** D4 (M9): the wire adds no held-asset guard; CHANGE_ENTITY and UNLINK on a held asset answer 200. */
    @Test fun aHeldAssetIsChangedAndUnlinkedOnTheWire() {
        runBlocking { graph.connect() }
        val heater = linkedManual("Example Heater")
        runBlocking {
            graph.transferRecords.append(
                TransferRecord(
                    id = "example-out-1", assetId = AssetId(heater), kind = TransferKind.OUT, packId = "example-pack",
                    lineage = emptyList(), at = graph.now, packSha256 = "ab".repeat(32),
                    nameSnapshot = "Example Heater", note = "",
                ),
            )
        }

        val changed = answer(change(heater))
        assertEquals(season, changed.binding().text("entityId"))
        assertEquals("NOT_MAINTAINED_HERE", changed.binding().text("state"))
        settle()

        val unlinked = answer(unlink(heater))
        assertEquals(JsonNull, unlinked.getValue("binding"))
        assertNull(stored(heater))
    }

    // --- k13: the confirmations are the caller's own -----------------------------------------

    @Test fun linkAndChangeNeedTheCallersOwnConfirmEntityId() {
        runBlocking { graph.connect() }
        val heater = api.asset("Example Heater")
        val boiler = linkedManual("Example Boiler")
        for (confirm in listOf("missing", "input_boolean.example_other_switch", "null")) {
            fun body(action: String) = when (confirm) {
                "missing" -> """{"action":"$action","entityId":"$season"}"""
                "null" -> """{"action":"$action","entityId":"$season","confirmEntityId":null}"""
                else -> """{"action":"$action","entityId":"$season","confirmEntityId":"$confirm"}"""
            }
            assertConfirmation("confirmEntityId", "LINK needs confirmEntityId, exactly entityId, the caller's own confirmation") {
                post(heater, body("LINK"))
            }
            assertConfirmation(
                "confirmEntityId", "CHANGE_ENTITY needs confirmEntityId, exactly entityId, the caller's own confirmation",
            ) { post(boiler, body("CHANGE_ENTITY")) }
        }
        assertEquals(inSeason, stored(boiler)!!.entityId)
        assertNull(stored(heater))
    }

    @Test fun unlinkNeedsTheCallersOwnConfirmUnlinkTrue() {
        runBlocking { graph.connect() }
        val heater = linkedManual("Example Heater")
        for (body in listOf(
            """{"action":"UNLINK"}""",
            """{"action":"UNLINK","confirmUnlink":false}""",
            """{"action":"UNLINK","confirmUnlink":"true"}""",
            """{"action":"UNLINK","confirmUnlink":1}""",
            """{"action":"UNLINK","confirmUnlink":null}""",
        )) {
            assertConfirmation("confirmUnlink", "UNLINK needs confirmUnlink: true, the caller's own confirmation") {
                post(heater, body)
            }
        }
        assertNotNull(stored(heater))
    }

    /** A confirmation an action does not take is the 422 naming the action, and it wins over every later check (M2). */
    @Test fun aConfirmationTheActionDoesNotTakeIs422NamingTheAction() {
        runBlocking { graph.connect() }
        val heater = api.asset("Example Heater")
        val boiler = linkedManual("Example Boiler")
        assertConfirmation("confirmUnlink", "LINK takes no confirmUnlink") {
            post(heater, """{"action":"LINK","entityId":"$inSeason","confirmEntityId":"$inSeason","confirmUnlink":true}""")
        }
        assertConfirmation("confirmUnlink", "CHANGE_ENTITY takes no confirmUnlink") {
            post(boiler, """{"action":"CHANGE_ENTITY","entityId":"$season","confirmEntityId":"$season","confirmUnlink":true}""")
        }
        for (action in listOf("STOP", "RESUME")) {
            assertConfirmation("confirmEntityId", "$action takes no confirmEntityId") {
                post(boiler, """{"action":"$action","confirmEntityId":"$inSeason"}""")
            }
            assertConfirmation("confirmUnlink", "$action takes no confirmUnlink") {
                post(boiler, """{"action":"$action","confirmUnlink":true}""")
            }
        }
        assertConfirmation("confirmEntityId", "SET_MODE takes no confirmEntityId") {
            post(boiler, """{"action":"SET_MODE","mode":"FORCE_IN","confirmEntityId":"$inSeason"}""")
        }
        assertConfirmation("confirmUnlink", "SET_MODE takes no confirmUnlink") {
            post(boiler, """{"action":"SET_MODE","mode":"FORCE_IN","confirmUnlink":true}""")
        }
        assertConfirmation("confirmEntityId", "UNLINK takes no confirmEntityId") {
            post(boiler, """{"action":"UNLINK","confirmUnlink":true,"confirmEntityId":"$inSeason"}""")
        }
        // M2's combined faults: the not-taken 422 comes first, before the 400s and the action's own confirmation.
        assertConfirmation("confirmUnlink", "LINK takes no confirmUnlink") {
            post(heater, """{"action":"LINK","entityId":"$inSeason","confirmEntityId":"$season","confirmUnlink":true}""")
        }
        assertConfirmation("confirmUnlink", "LINK takes no confirmUnlink") {
            post(heater, """{"action":"LINK","confirmUnlink":true}""")
        }
        assertConfirmation("confirmEntityId", "UNLINK takes no confirmEntityId") {
            post(boiler, """{"action":"UNLINK","entityId":"$inSeason","confirmEntityId":"$inSeason"}""")
        }
        assertConfirmation("confirmEntityId", "STOP takes no confirmEntityId") {
            post(boiler, """{"action":"STOP","entityId":"$inSeason","confirmEntityId":"$inSeason"}""")
        }
    }

    // --- k14: the body's shape ------------------------------------------------------------------

    @Test fun theBodyIsStrictAndTakesNoSeasonStateAddressOrToken() {
        runBlocking { graph.connect() }
        val heater = linkedManual("Example Heater")
        assertBadRequest { post(heater, "not json") }
        for (key in listOf("token", "baseUrl", "homeNetworkSsid", "state", "observedState", "occurredOn")) {
            assertBadRequest { post(heater, """{"action":"STOP","$key":"ON"}""") }
        }
        for (action in listOf("SYNC_NOW", "FORCE_IN", "ON", "link")) {
            assertBadRequest { post(heater, """{"action":"$action"}""") }
        }
        for (mode in listOf("ON", "OFF", "IN_SEASON", "follow")) {
            assertBadRequest { post(heater, """{"action":"SET_MODE","mode":"$mode"}""") }
        }
        assertBadRequest { post(heater, """{"entityId":"$inSeason"}""") }
    }

    /** The table's 400s: a key the action needs and lacks, or has and does not take. */
    @Test fun aKeyTheActionNeedsOrDoesNotTakeIs400() {
        runBlocking { graph.connect() }
        val heater = api.asset("Example Heater")
        val boiler = linkedManual("Example Boiler")
        assertBadRequest { post(heater, """{"action":"LINK","confirmEntityId":"$inSeason"}""") }
        assertBadRequest { post(heater, """{"action":"LINK","entityId":"$inSeason","confirmEntityId":"$inSeason","mode":"FOLLOW"}""") }
        assertBadRequest { post(boiler, """{"action":"CHANGE_ENTITY","confirmEntityId":"$season"}""") }
        assertBadRequest { post(boiler, """{"action":"CHANGE_ENTITY","entityId":"$season","confirmEntityId":"$season","mode":"FOLLOW"}""") }
        for (action in listOf("STOP", "RESUME")) {
            assertBadRequest { post(boiler, """{"action":"$action","entityId":"$inSeason"}""") }
            assertBadRequest { post(boiler, """{"action":"$action","mode":"FORCE_IN"}""") }
        }
        assertBadRequest { post(boiler, """{"action":"SET_MODE"}""") }
        assertBadRequest { post(boiler, """{"action":"SET_MODE","mode":"FORCE_IN","entityId":"$inSeason"}""") }
        assertBadRequest { post(boiler, """{"action":"UNLINK","confirmUnlink":true,"entityId":"$inSeason"}""") }
        assertBadRequest { post(boiler, """{"action":"UNLINK","confirmUnlink":true,"mode":"FOLLOW"}""") }
        // M2's combined fault: LINK lacking entityId with a confirmEntityId is the 400 (step 3.2), not the 422 (3.3).
        assertBadRequest { post(heater, """{"action":"LINK","confirmEntityId":"$season"}""") }
        // A 400 before the action's own confirmation: UNLINK's mode with no confirmUnlink.
        assertBadRequest { post(boiler, """{"action":"UNLINK","mode":"FOLLOW"}""") }
    }

    /** The shape first (400), then the asset (404 `no_such_asset`), before any key or confirmation check. */
    @Test fun anUnknownAssetIs404BeforeAnyConfirmation() {
        runBlocking { graph.connect() }
        val nobody = "00000000-0000-4000-8000-999999999999"
        for (body in listOf(
            """{"action":"UNLINK"}""",
            """{"action":"LINK","entityId":"$inSeason"}""",
            """{"action":"LINK","confirmUnlink":true}""",
            """{"action":"SET_MODE"}""",
            """{"action":"STOP"}""",
        )) {
            assertRefused(404, "no_such_asset") { post(nobody, body) }
        }
        assertBadRequest { post(nobody, """{"action":"SYNC_NOW"}""") }
        assertBadRequest { post(nobody, """{"action":"SET_MODE","mode":"ON"}""") }
    }

    // --- k15: the refusals, by name -----------------------------------------------------------

    @Test fun eachReachableRefusalIsSeasonSyncRefusedByName() {
        val heater = api.asset("Example Heater")
        manual(heater)
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.NO_CONNECTION) { link(heater) }
        val connection = runBlocking { graph.connect() }
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.BAD_ENTITY_ID) { link(heater, "example heater") }

        val written = "input_datetime.example_heater_service_done"
        runBlocking {
            graph.publishBindings.insert(
                PublishBinding(
                    id = "example-binding-1", assetId = AssetId(heater), connectionId = connection.id,
                    kind = PublishKind.SEASON_SERVICE_DATE, entityId = written, enabled = false, revision = 1,
                    activatedInstallationId = null, validatedAt = null, validatedRevision = null,
                    boundaryEntityId = null, createdAt = 1L, updatedAt = 1L,
                ),
            )
        }
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.OUTBOUND_TARGET) { link(heater, written) }

        answer(link(heater))
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.ALREADY_LINKED) { link(heater, season) }
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.BAD_ENTITY_ID) { change(heater, "example heater") }
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.OUTBOUND_TARGET) { change(heater, written) }
        answer(act(heater, "STOP"))
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.ALREADY_LINKED) { link(heater, season) }

        val boiler = linkedManual("Example Boiler")
        answer(act(boiler, "STOP"))
        api.call("POST", "/v1/assets/$boiler/archive", """{"archived":true}""").also { assertEquals(it.bodyText(), 200, it.status) }
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.NOT_MAINTAINED_HERE) { act(boiler, "RESUME") }
        val spa = api.asset("Example Spa")
        api.call("POST", "/v1/assets/$spa/archive", """{"archived":true}""").also { assertEquals(it.bodyText(), 200, it.status) }
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.NOT_MAINTAINED_HERE) { link(spa) }

        runBlocking { graph.secretStore.delete(connection.id) }
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.NEEDS_TOKEN) { act(heater, "RESUME") }
        val tub = api.asset("Example Tub")
        assertSeasonSyncRefused(SeasonSyncLinkRefusal.NEEDS_TOKEN) { link(tub) }
    }

    /** M5: an action on an asset with no binding is 409 `SEASON_SYNC_NOT_LINKED`, never the 500. */
    @Test fun anActionOnAnUnlinkedAssetIsNotLinked() {
        runBlocking { graph.connect() }
        val heater = api.asset("Example Heater")
        for (call in listOf<() -> ApiResponse>(
            { change(heater) },
            { act(heater, "STOP") },
            { act(heater, "RESUME") },
            { setMode(heater, "FORCE_OUT") },
        )) {
            val response = assertRefused(409, "SEASON_SYNC_NOT_LINKED", call = call)
            assertEquals("this asset has no season link; link it first", response.errorDetail().message)
        }
    }

    /** A LINK whose switch into MANUAL would strand a PRE_SERVICE schedule is the shipped 409, nothing written. */
    @Test fun aLinkThatStrandsAPreServiceScheduleIsRefused() {
        runBlocking { graph.connect() }
        val heater = api.asset("Example Heater")
        calendar(heater)
        val schedule = api.call(
            "POST", "/v1/schedules",
            """{"title":"Example pre-season check","targetAssetId":"$heater","timeInterval":1,"timeUnit":"YEAR",""" +
                """"anchorOn":"2026-02-01","servicePolicy":"PRE_SERVICE","policyOffsetDays":-14}""",
        )
        assertEquals(schedule.bodyText(), 201, schedule.status)
        assertRefused(409, "SEASON_MODE_STRANDS_POLICY") { link(heater) }
        assertNull(stored(heater))
    }

    /** Every reason maps to 409 by its name with its own developer sentence, the unreachable ones included. */
    @Test fun everyRefusalMapsTo409ByItsNameAndSentence() {
        val sentences = mapOf(
            SeasonSyncLinkRefusal.NOT_MAINTAINED_HERE to "the asset is archived, retired or transferred out",
            SeasonSyncLinkRefusal.NO_CONNECTION to "no Home Assistant connection is saved on this phone",
            SeasonSyncLinkRefusal.NEEDS_TOKEN to "the Home Assistant token is not on this phone; enter it on the phone first",
            SeasonSyncLinkRefusal.BAD_ENTITY_ID to "entityId is not a Home Assistant entity id",
            SeasonSyncLinkRefusal.ALREADY_LINKED to
                "this asset already has a season link; change its entity, resume it or unlink it first",
            SeasonSyncLinkRefusal.OUTBOUND_TARGET to "ServiceTag writes that helper, so it cannot follow it",
        )
        assertEquals(SeasonSyncLinkRefusal.entries.toSet(), sentences.keys)
        for ((reason, sentence) in sentences) {
            val response = mapDomainFailure(SeasonSyncLinkRefused(reason))
            assertEquals(reason.name, 409, response.status)
            val detail = response.errorDetail()
            assertEquals(reason.name, "SEASON_SYNC_REFUSED", detail.code)
            assertEquals(reason.name, sentence, detail.message)
            assertEquals(listOf("SeasonSyncLinkRefused(reason=${reason.name})"), detail.problems)
        }
    }

    // --- k16: the answer is the read's, race-free -----------------------------------------------

    /**
     * I6: each 200 without `switchedFrom` equals an immediate `GET`. For the four actions that launch the runner's read
     * the reader is held on a gate, so the read cannot land between the answer and the `GET`; the gate is released and
     * the read joined before the next action. STOP, UNLINK and a Force mode launch none.
     */
    @Test fun eachAnswerIsAnImmediateGetPlusSwitchedFrom() {
        runBlocking { graph.connect() }
        val heater = api.asset("Example Heater")

        fun sameAsGet(response: ApiResponse): JsonObject {
            val posted = answer(response)
            val read = api.call("GET", path(heater))
            assertEquals(read.bodyText(), 200, read.status)
            assertEquals(tree(read), JsonObject(posted - "switchedFrom"))
            return posted
        }

        fun gated(launch: () -> ApiResponse): JsonObject {
            val gate = CompletableDeferred<Unit>()
            graph.haStateReader.answer = { gate.await(); HaReadOutcome.Observed(HaSwitchState.ON, null) }
            val posted = sameAsGet(launch())
            gate.complete(Unit)
            settle()
            return posted
        }

        fun ungated(launch: () -> ApiResponse): JsonObject {
            val posted = sameAsGet(launch())
            runBlocking { graph.awaitSeasonSyncReads() }
            assertTrue("no read was launched", graph.haStateReader.reads.isEmpty())
            return posted
        }

        assertEquals("a new asset is year-round", "YEAR_ROUND", gated { link(heater) }.switchedFrom())
        assertNull(ungated { act(heater, "STOP") }.switchedFrom())
        assertNull(ungated { change(heater) }.switchedFrom())
        assertNull(gated { act(heater, "RESUME") }.switchedFrom())
        assertNull(gated { change(heater, inSeason) }.switchedFrom())
        assertNull(ungated { setMode(heater, "FORCE_IN") }.switchedFrom())
        assertNull(ungated { setMode(heater, "FORCE_OUT") }.switchedFrom())
        assertNull(gated { setMode(heater, "FOLLOW") }.switchedFrom())
        assertNull(ungated { unlink(heater) }.switchedFrom())
        val relinked = gated { link(heater) }
        assertNull("a MANUAL asset is not switched", relinked.switchedFrom())
    }

    /** The same answers over an `https` address with no home network: neither address is a byte of them. */
    @Test fun anHttpsConnectionIsNotOnTheWireEither() {
        runBlocking { graph.connect(eligibility = NetworkEligibility.ANY_NETWORK) }
        val heater = api.asset("Example Heater")
        manual(heater)
        val linked = answer(link(heater))
        assertTrue(linked.getValue("connection").jsonObject.getValue("configured").jsonPrimitive.boolean)
        settle()
        answer(change(heater))
        settle()
        answer(act(heater, "STOP"))
        answer(unlink(heater))
    }

    // --- k17: the parity fence -------------------------------------------------------------------

    /** The connection, the token and Forget stay the phone's: no verb on their shapes is a route. */
    @Test fun theConnectionAndTheTokenHaveNoRoute() {
        runBlocking { graph.connect() }
        val before = snapshot()
        for (shape in listOf("/v1/ha-connection", "/v1/ha-connection/token")) {
            for (verb in listOf("POST", "PUT", "DELETE")) {
                val body = if (verb == "DELETE") "" else """{"token":"fictional-token-2","baseUrl":"https://ha.example:8123"}"""
                val response = api.call(verb, shape, body)
                assertEquals("$verb $shape: ${response.bodyText()}", 404, response.status)
            }
        }
        assertEquals(before, snapshot())
        assertTrue(runBlocking { graph.secretStore.has(graph.haConnections.get()!!.id) })
        val descriptor = SeasonSyncRequest.serializer().descriptor
        assertEquals(
            "L-C8's five keys, no other",
            listOf("action", "entityId", "confirmEntityId", "mode", "confirmUnlink"),
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) },
        )
    }

    /** Another verb on the asset shape stays the sub-resource convention's 404. */
    @Test fun anotherVerbOnTheAssetShapeIs404() {
        runBlocking { graph.connect() }
        val heater = linkedManual("Example Heater")
        val before = snapshot()
        for (verb in listOf("PATCH", "PUT", "DELETE")) {
            val body = if (verb == "DELETE") "" else """{"action":"UNLINK","confirmUnlink":true}"""
            val response = api.call(verb, path(heater), body)
            assertEquals("$verb: ${response.bodyText()}", 404, response.status)
            assertEquals(verb, "not_found", response.errorDetail().code)
        }
        assertEquals(before, snapshot())
    }
}
