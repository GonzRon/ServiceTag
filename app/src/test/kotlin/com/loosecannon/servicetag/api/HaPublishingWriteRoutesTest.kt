package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.VALIDATION_MAX_AGE_MILLIS
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.DATE
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.ENDED
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.INBOUND
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.STATUS
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.dateHelper
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.statusHelper
import com.loosecannon.servicetag.hapublish.ScenarioRig
import com.loosecannon.servicetag.testing.assetRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #113/#114 (C35b, C25a, I33, I34, O-21; row 117) — B5w1's controls over `/v1` through the production router over
 * `FakeGraph`, on the JVM, Home Assistant the graph's script (`ScriptedHaPublish`): `POST /v1/assets/{id}/ha-publishing-plan`
 * reads the helpers and writes nothing, and `POST /v1/assets/{id}/ha-publishing` commits a held plan through
 * `PublishControls` only with the caller's `planId` and confirmed destination, refusing a plan that no longer holds.
 * Every asset, helper, address and token is fictional.
 */
class HaPublishingWriteRoutesTest {

    private val rig = ScenarioRig()

    @After fun close() = rig.close()

    /** The heater on a calendar season, the fictional connection, and its two helpers as Home Assistant holds them. */
    private suspend fun ScenarioRig.ready(): V1Client {
        calendar()
        connect()
        graph.haPublish.helpers[STATUS] = statusHelper("unknown")
        graph.haPublish.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        graph.haPublish.helpers[OTHER_DATE] = dateHelper(OTHER_DATE, INVALID_SERVICE_DATE)
        return V1Client(graph)
    }

    /** The heater a `MANUAL` asset whose season follows an enabled inbound #16 binding (C25's boundary rule). */
    private suspend fun ScenarioRig.manualFollowingHa() {
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        val connection = graph.haConnections.get()!!
        graph.seasonSyncBindings.insert(inbound(heater, connection.id, INBOUND))
        graph.haPublish.helpers[ENDED] = dateHelper(ENDED, "2026-01-05")
    }

    private fun ScenarioRig.inbound(assetId: AssetId, connectionId: String, entityId: String) = SeasonSyncBinding(
        assetId, connectionId, entityId, SyncMode.FOLLOW, enabled = true, revision = 1, observedState = null,
        observedChangedAt = null, lastSuccessAt = null, lastAttemptAt = null, errorKind = null, errorDetail = null,
        errorAt = null, appliedAction = null, appliedOn = null, appliedAt = null, lastAppliedSource = null,
        createdAt = graph.now, updatedAt = graph.now,
    )

    private fun planBody(kind: PublishKind, action: String, entityId: String, boundary: String? = null): String =
        """{"kind":"${kind.name}","action":"$action","entityId":"$entityId"""" +
            (boundary?.let { ""","boundaryEntityId":"$it"""" } ?: "") + "}"

    private fun V1Client.plan(
        kind: PublishKind,
        entityId: String,
        action: String = "ENABLE",
        boundary: String? = null,
        assetId: AssetId = rig.heater,
    ): JsonObject {
        val response = call("POST", "/v1/assets/${assetId.value}/ha-publishing-plan", planBody(kind, action, entityId, boundary))
        assertEquals(response.bodyText(), 200, response.status)
        return Json.parseToJsonElement(response.bodyText()).jsonObject
    }

    private fun V1Client.act(
        kind: PublishKind,
        planId: String?,
        confirm: String?,
        action: String = "ENABLE",
        assetId: AssetId = rig.heater,
    ): ApiResponse {
        val keys = listOfNotNull(
            """"kind":"${kind.name}"""",
            """"action":"$action"""",
            planId?.let { """"planId":"$it"""" },
            confirm?.let { """"confirmDestination":"$it"""" },
        )
        return call("POST", "/v1/assets/${assetId.value}/ha-publishing", keys.joinToString(",", "{", "}"))
    }

    /** Enable through the API: the plan, then the action confirming its own target. */
    private fun V1Client.enable(kind: PublishKind, entityId: String, boundary: String? = null): JsonObject {
        val planId = plan(kind, entityId, boundary = boundary).str("planId")
        val response = act(kind, planId, entityId)
        assertEquals(response.bodyText(), 200, response.status)
        return Json.parseToJsonElement(response.bodyText()).jsonObject.getValue("binding").jsonObject
    }

    /** The three publishing tables, as rows. */
    private suspend fun ScenarioRig.tables(): List<Any> = listOf(
        graph.publishBindings.all().toSet(),
        graph.publishBindings.all().mapNotNull { graph.publishDeliveries.get(it.id) }.toSet(),
        graph.publishDecommissions.all().toSet(),
    )

    private fun ApiResponse.assertRefused(status: Int, code: String, field: String?, problem: String? = null) {
        assertEquals(bodyText(), status, this.status)
        val error = errorDetail()
        assertEquals(bodyText(), code, error.code)
        assertEquals(bodyText(), field, error.field)
        problem?.let { assertEquals(bodyText(), listOf(it), error.problems) }
    }

    // --- the plan writes nothing (row 117) ----------------------------------------------------------------------------

    @Test
    fun thePlanWritesNothingAndPostsNothing() = rig.run {
        val api = ready()
        val before = tables()
        graph.haPublish.log.clear()

        val status = api.plan(PublishKind.EQUIPMENT_STATUS, STATUS)
        assertEquals(STATUS_PLAN_KEYS, status.keys.toList())
        assertEquals("one read of the target, and no write", listOf("GET $STATUS"), graph.haPublish.log.toList())
        assertEquals(before, tables())
        assertEquals("a plan asks for no delivery", 0, graph.deliveryRequests.get())
        assertEquals(64, status.str("planId").length)
        assertEquals(heater.value, status.str("assetId"))
        assertEquals("EQUIPMENT_STATUS", status.str("kind"))
        assertEquals("ENABLE", status.str("action"))
        assertEquals(STATUS, status.str("entityId"))
        assertEquals(JsonNull, status.getValue("boundaryEntityId"))
        assertEquals("false", status.str("boundaryRequired"))
        val remote = status.getValue("remote").jsonObject
        assertEquals(setOf("domain", "options", "hasDate", "hasTime"), remote.keys)
        assertEquals("input_select", remote.str("domain"))
        assertEquals(listOf("UP", "DEGRADED", "DOWN"), remote.getValue("options").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("no state is answered", false, status.toString().contains("unknown"))
        assertEquals(JsonNull, status.getValue("boundary"))
        assertEquals(
            mapOf("OPERATIONAL" to "UP", "DEGRADED" to "DEGRADED", "DOWN" to "DOWN"),
            status.getValue("mapping").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(JsonNull, status.getValue("refusal"))
        assertEquals(graph.now, status.getValue("readAt").jsonPrimitive.long)
        assertEquals(graph.now + VALIDATION_MAX_AGE_MILLIS, status.getValue("expiresAt").jsonPrimitive.long)

        val date = api.plan(PublishKind.SEASON_SERVICE_DATE, DATE)
        assertEquals(DATE_PLAN_KEYS, date.keys.toList())
        assertEquals(INVALID_SERVICE_DATE, date.str("invalidDate"))
        assertEquals("a calendar asset's boundary is its own window", "false", date.str("boundaryRequired"))
        assertEquals(JsonNull, date.getValue("remote").jsonObject.getValue("options"))
        assertEquals("true", date.getValue("remote").jsonObject.str("hasDate"))
        assertEquals("false", date.getValue("remote").jsonObject.str("hasTime"))
        assertEquals(listOf("GET $STATUS", "GET $DATE"), graph.haPublish.log.toList())
        assertEquals(before, tables())
        assertTrue(graph.haPublish.posts.isEmpty())
    }

    @Test
    fun aPlanCarriesItsIdOnlyWithoutARefusal() = rig.run {
        val api = ready()
        graph.haPublish.helpers[BAD_STATUS] = RemoteEntity(BAD_STATUS, "UP", listOf("UP", "DOWN"), null, null)
        graph.haPublish.helpers[TIMED_DATE] = RemoteEntity(TIMED_DATE, "2026-01-01 00:00:00", null, true, true)

        fun refused(plan: JsonObject, reason: String, field: String?) {
            assertEquals(plan.toString(), JsonNull, plan.getValue("planId"))
            assertEquals(plan.toString(), JsonNull, plan.getValue("expiresAt"))
            val refusal = plan.getValue("refusal").jsonObject
            assertEquals(setOf("reason", "field"), refusal.keys)
            assertEquals(reason, refusal.str("reason"))
            assertEquals(field?.let(::JsonPrimitive) ?: JsonNull, refusal.getValue("field"))
        }

        val mismatched = api.plan(PublishKind.EQUIPMENT_STATUS, BAD_STATUS)
        refused(mismatched, "OPTIONS_MISMATCH", "entityId")
        assertEquals("the read answered, so its time is told", graph.now, mismatched.getValue("readAt").jsonPrimitive.long)
        refused(api.plan(PublishKind.SEASON_SERVICE_DATE, TIMED_DATE), "NOT_DATE_ONLY", "entityId")
        val missing = api.plan(PublishKind.EQUIPMENT_STATUS, "input_select.example_heater_gone")
        refused(missing, "ENTITY_NOT_FOUND", "entityId")
        assertEquals(JsonNull, missing.getValue("remote"))
        assertEquals(JsonNull, missing.getValue("readAt"))
        graph.haPublish.log.clear()
        refused(api.plan(PublishKind.EQUIPMENT_STATUS, DATE), "WRONG_DOMAIN", "entityId")
        assertTrue("a wrong domain is refused before any read", graph.haPublish.log.isEmpty())
        refused(api.plan(PublishKind.SEASON_SERVICE_DATE, DATE, boundary = TIMED_DATE), "NOT_DATE_ONLY", "boundaryEntityId")
        assertEquals("no refused plan is held", 0, api.haPublishingPlans.count())

        graph.forgetHaConnection.run()
        refused(api.plan(PublishKind.EQUIPMENT_STATUS, STATUS), "NO_CONNECTION", null)
    }

    @Test
    fun boundaryRequiredFollowsC25() = rig.run {
        val api = ready()
        assertEquals("false", api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("boundaryRequired"))
        assertEquals("false", api.plan(PublishKind.SEASON_SERVICE_DATE, DATE).str("boundaryRequired"))
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        assertEquals("a MANUAL asset Home Assistant does not drive", "false", api.plan(PublishKind.SEASON_SERVICE_DATE, DATE).str("boundaryRequired"))
        manualFollowingHa()
        val plan = api.plan(PublishKind.SEASON_SERVICE_DATE, DATE, boundary = ENDED)
        assertEquals("true", plan.str("boundaryRequired"))
        assertEquals("false", api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("boundaryRequired"))
        assertEquals(ENDED, plan.str("boundaryEntityId"))
        val boundary = plan.getValue("boundary").jsonObject
        assertEquals(setOf("value", "readAt", "hasDate", "hasTime"), boundary.keys)
        assertEquals("2026-01-05", boundary.str("value"))
        assertEquals(64, plan.str("planId").length)
    }

    // --- the action's confirmation and the plan's freshness (row 117) -------------------------------------------------

    @Test
    fun enableNeedsThePlanIdAndTheConfirmedDestination() = rig.run {
        val api = ready()
        val planId = api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("planId")
        val before = tables()

        api.act(PublishKind.EQUIPMENT_STATUS, null, STATUS)
            .assertRefused(422, "HA_PUBLISH_CONFIRMATION_REQUIRED", "planId", "PublishConfirmationRequired(field=planId)")
        api.act(PublishKind.EQUIPMENT_STATUS, planId, null).assertRefused(422, "HA_PUBLISH_CONFIRMATION_REQUIRED", "confirmDestination")
        for (wrong in listOf(DATE, STATUS.uppercase(), "$STATUS ", "")) {
            api.act(PublishKind.EQUIPMENT_STATUS, planId, wrong)
                .assertRefused(422, "HA_PUBLISH_CONFIRMATION_REQUIRED", "confirmDestination")
        }
        assertEquals(before, tables())
        assertEquals(0, graph.deliveryRequests.get())

        val answer = api.act(PublishKind.EQUIPMENT_STATUS, planId, STATUS)
        assertEquals(answer.bodyText(), 200, answer.status)
        val binding = Json.parseToJsonElement(answer.bodyText()).jsonObject
        assertEquals(setOf("binding"), binding.keys)
        assertEquals(BINDING_KEYS, binding.getValue("binding").jsonObject.keys)
        assertEquals(STATUS, binding.getValue("binding").jsonObject.str("entityId"))
        assertEquals("true", binding.getValue("binding").jsonObject.str("enabled"))
        assertEquals("the facade asked for delivery", 1, graph.deliveryRequests.get())
        graph.awaitDispatched()
    }

    @Test
    fun anUnknownExpiredConsumedOrForeignPlanIsStale() = rig.run {
        val api = ready()
        graph.assets.upsert(assetRow(BOILER.value, "Example Boiler"))
        val planId = api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("planId")
        val before = tables()

        fun stale(response: ApiResponse) =
            response.assertRefused(409, "STALE_PLAN", "planId", "PublishRefused(reason=STALE_PLAN)")

        stale(api.act(PublishKind.EQUIPMENT_STATUS, "0".repeat(64), STATUS))
        stale(api.act(PublishKind.EQUIPMENT_STATUS, planId, STATUS, assetId = BOILER))
        stale(api.act(PublishKind.SEASON_SERVICE_DATE, planId, STATUS))
        stale(api.act(PublishKind.EQUIPMENT_STATUS, planId, STATUS, action = "CHANGE_TARGET"))
        stale(V1Client(graph).act(PublishKind.EQUIPMENT_STATUS, planId, STATUS))
        graph.now += VALIDATION_MAX_AGE_MILLIS
        stale(api.act(PublishKind.EQUIPMENT_STATUS, planId, STATUS))
        assertEquals(before, tables())
        assertEquals(0, graph.deliveryRequests.get())

        val again = api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("planId")
        assertEquals(200, api.act(PublishKind.EQUIPMENT_STATUS, again, STATUS).status)
        assertEquals("the commit removes its plan", 0, api.haPublishingPlans.count())
        val enabled = tables()
        stale(api.act(PublishKind.EQUIPMENT_STATUS, again, STATUS))
        assertEquals("a plan is committed once", enabled, tables())
        graph.awaitDispatched()
    }

    @Test
    fun aConnectionSaveBetweenPlanAndActionIsStale() = rig.run {
        val api = ready()
        val planId = api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("planId")
        connect()
        val before = tables()
        api.act(PublishKind.EQUIPMENT_STATUS, planId, STATUS)
            .assertRefused(409, "STALE_PLAN", "planId", "PublishRefused(reason=STALE_PLAN)")
        assertEquals(before, tables())
        assertTrue(graph.publishBindings.all().isEmpty())

        // The phone's own Enable between the plan and the action moves the binding's revision.
        val second = api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("planId")
        enable(PublishKind.EQUIPMENT_STATUS)
        val enabled = tables()
        api.act(PublishKind.EQUIPMENT_STATUS, second, STATUS).assertRefused(409, "STALE_PLAN", "planId")
        assertEquals(enabled, tables())
        graph.awaitDispatched()
    }

    @Test
    fun theActionReadsNothing() = rig.run {
        val api = ready()
        val planId = api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("planId")
        val readsBeforeDelivery = mutableListOf<String>()
        graph.haPublish.onRead = { entityId ->
            if (graph.deliveryRequests.get() == 0) readsBeforeDelivery += entityId
            graph.haPublish.answer(entityId)
        }
        assertEquals(200, api.act(PublishKind.EQUIPMENT_STATUS, planId, STATUS).status)
        graph.awaitDispatched()
        assertEquals("no read between the plan and the commit", emptyList<String>(), readsBeforeDelivery)
        assertEquals(1, graph.deliveryRequests.get())
    }

    // --- the transaction's refusals (C25, C25a; row 117) --------------------------------------------------------------

    @Test
    fun eachTransactionRefusalIsHaPublishRefused() = rig.run {
        val api = ready()
        val connection = graph.haConnections.get()!!
        graph.assets.upsert(assetRow(BOILER.value, "Example Boiler"))

        // TARGET_IN_USE: another asset took the helper after the plan.
        val taken = api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("planId")
        graph.enablePublishing.run(
            BOILER, PublishKind.EQUIPMENT_STATUS, STATUS, fresh(STATUS, PublishKind.EQUIPMENT_STATUS, BOILER), null,
            graph.installationIdentity.id(),
        )
        api.act(PublishKind.EQUIPMENT_STATUS, taken, STATUS).assertRefused(
            409, "HA_PUBLISH_REFUSED", "entityId", "PublishRefused(reason=TARGET_IN_USE, holderAssetId=${BOILER.value})",
        )
        graph.disablePublishing.run(BOILER, PublishKind.EQUIPMENT_STATUS)

        // NOT_MAINTAINED_HERE: the asset was archived after the plan (the plan names no asset state).
        graph.assets.upsert(
            assetRow(heater.value, "Example Heater", status = AssetStatus.ARCHIVED, seasonMode = SeasonMode.CALENDAR, seasonStart = "04-01", seasonEnd = "10-31"),
        )
        api.act(PublishKind.EQUIPMENT_STATUS, taken, STATUS)
            .assertRefused(409, "HA_PUBLISH_REFUSED", "entityId", "PublishRefused(reason=NOT_MAINTAINED_HERE)")
        calendar()

        // INBOUND_SOURCE: an inbound #16 binding reads it.
        graph.seasonSyncBindings.insert(inbound(BOILER, connection.id, STATUS))
        api.act(PublishKind.EQUIPMENT_STATUS, taken, STATUS)
            .assertRefused(409, "HA_PUBLISH_REFUSED", "entityId", "PublishRefused(reason=INBOUND_SOURCE)")

        // Change target with no binding of that kind (B3 review M2).
        val change = api.plan(PublishKind.SEASON_SERVICE_DATE, DATE, action = "CHANGE_TARGET").str("planId")
        api.act(PublishKind.SEASON_SERVICE_DATE, change, DATE, action = "CHANGE_TARGET")
            .assertRefused(409, "HA_PUBLISH_REFUSED", "kind", "PublishRefused(reason=BAD_ENTITY_ID)")

        // The season-ended helper: required and missing, then the target itself.
        manualFollowingHa()
        val noBoundary = api.plan(PublishKind.SEASON_SERVICE_DATE, DATE).str("planId")
        api.act(PublishKind.SEASON_SERVICE_DATE, noBoundary, DATE)
            .assertRefused(409, "HA_PUBLISH_REFUSED", "boundaryEntityId", "PublishRefused(reason=BAD_ENTITY_ID)")
        val itself = api.plan(PublishKind.SEASON_SERVICE_DATE, DATE, boundary = DATE).str("planId")
        api.act(PublishKind.SEASON_SERVICE_DATE, itself, DATE)
            .assertRefused(409, "HA_PUBLISH_REFUSED", "boundaryEntityId", "PublishRefused(reason=BOUNDARY_IS_TARGET)")

        assertTrue("nothing of the heater's was written", graph.publishBindings.forAsset(heater).isEmpty())
        graph.awaitDispatched()
    }

    // --- activation and the two commits (O-21(c), I26; row 117) -------------------------------------------------------

    @Test
    fun anApiEnableOfACopiedRowActivatesItHere() = rig.run {
        val api = ready()
        graph.enablePublishing.run(
            heater, PublishKind.EQUIPMENT_STATUS, STATUS, fresh(STATUS, PublishKind.EQUIPMENT_STATUS), null,
            "example-other-installation",
        )
        val copied = Json.parseToJsonElement(api.call("GET", "/v1/assets/${heater.value}/ha-publishing").bodyText())
            .jsonObject.getValue("bindings").jsonArray.single().jsonObject
        assertEquals("COPIED_INSTALLATION", copied.str("state"))

        val binding = api.enable(PublishKind.EQUIPMENT_STATUS, STATUS)
        assertNotEquals("COPIED_INSTALLATION", binding.str("state"))
        assertEquals(graph.installationIdentity.id(), graph.publishBindings.forAsset(heater).single().activatedInstallationId)
        assertEquals("no installation id is answered", false, binding.toString().contains(graph.installationIdentity.id()))
        graph.awaitDispatched()
    }

    @Test
    fun enableAndChangeTargetCommitThroughTheFacade() = rig.run {
        val api = ready()
        val enabled = api.enable(PublishKind.SEASON_SERVICE_DATE, DATE)
        assertEquals(DATE, enabled.str("entityId"))
        assertEquals("1", enabled.str("revision"))
        graph.awaitDispatched()

        val planId = api.plan(PublishKind.SEASON_SERVICE_DATE, OTHER_DATE, action = "CHANGE_TARGET").str("planId")
        // The old helper stops answering, so its obligation stays to be seen.
        graph.haPublish.onRead = {
            if (it == DATE) RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) else graph.haPublish.answer(it)
        }
        val response = api.act(PublishKind.SEASON_SERVICE_DATE, planId, OTHER_DATE, action = "CHANGE_TARGET")
        assertEquals(response.bodyText(), 200, response.status)
        val changed = Json.parseToJsonElement(response.bodyText()).jsonObject.getValue("binding").jsonObject
        assertEquals(OTHER_DATE, changed.str("entityId"))
        assertEquals("2", changed.str("revision"))
        assertEquals(2, graph.deliveryRequests.get())
        val owed = graph.publishDecommissions.all().single()
        assertEquals("the old helper is owed 1970-01-01", DATE, owed.entityId)
        graph.awaitDispatched()
        assertEquals(DecommissionState.PENDING, graph.publishDecommissions.all().single().state)
    }

    // --- no body takes a value (I33, #114 point 5); the shapes ---------------------------------------------------------

    @Test
    fun noRequestBodyTakesAValue() = rig.run {
        val api = ready()
        val planId = api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("planId")
        val before = tables()
        for (key in listOf("value", "desiredValue", "date", "readyForSeason", "seasonServiceDoneOn")) {
            val plan = api.call(
                "POST", "/v1/assets/${heater.value}/ha-publishing-plan",
                """{"kind":"EQUIPMENT_STATUS","action":"ENABLE","entityId":"$STATUS","$key":"UP"}""",
            )
            assertEquals("$key: ${plan.bodyText()}", 400, plan.status)
            val action = api.call(
                "POST", "/v1/assets/${heater.value}/ha-publishing",
                """{"kind":"EQUIPMENT_STATUS","action":"ENABLE","planId":"$planId","confirmDestination":"$STATUS","$key":"UP"}""",
            )
            assertEquals("$key: ${action.bodyText()}", 400, action.status)
        }
        assertEquals(before, tables())
        assertEquals(0, graph.deliveryRequests.get())
    }

    @Test
    fun anUnknownAssetIs404AndAnotherVerbIs404() = rig.run {
        val api = ready()
        val plan = api.call("POST", "/v1/assets/example-no-such-asset/ha-publishing-plan", planBody(PublishKind.EQUIPMENT_STATUS, "ENABLE", STATUS))
        plan.assertRefused(404, "no_such_asset", null)
        val planId = api.plan(PublishKind.EQUIPMENT_STATUS, STATUS).str("planId")
        api.act(PublishKind.EQUIPMENT_STATUS, planId, STATUS, assetId = AssetId("example-no-such-asset"))
            .assertRefused(404, "no_such_asset", null)
        assertEquals(404, api.call("GET", "/v1/assets/${heater.value}/ha-publishing-plan").status)
        assertEquals(404, api.call("PATCH", "/v1/assets/${heater.value}/ha-publishing", "{}").status)
        val badKind = api.call("POST", "/v1/assets/${heater.value}/ha-publishing-plan", planBody(PublishKind.EQUIPMENT_STATUS, "ENABLE", STATUS).replace("EQUIPMENT_STATUS", "CONDITION"))
        assertEquals(400, badKind.status)
        assertTrue(graph.publishBindings.all().isEmpty())
    }

    // --- B5w2: Disable and Check helper (C35b, C25a correction 2; row 118) ---------------------------------------------

    /** One control on the heater's [kind], with any further keys as [extra] (each led by its comma). */
    private fun V1Client.control(kind: PublishKind, action: String, extra: String = ""): ApiResponse =
        call("POST", "/v1/assets/${rig.heater.value}/ha-publishing", """{"kind":"${kind.name}","action":"$action"$extra}""")

    private fun ApiResponse.binding(): JsonObject? {
        assertEquals(bodyText(), 200, status)
        val tree = Json.parseToJsonElement(bodyText()).jsonObject
        assertEquals(setOf("binding"), tree.keys)
        return (tree.getValue("binding") as? JsonObject)?.also { assertEquals(BINDING_KEYS, it.keys) }
    }

    /** The helpers Home Assistant was asked for after the request's own commit asked for delivery, and before. */
    private fun ScenarioRig.readsBeforeDelivery(): MutableList<String> {
        val before = mutableListOf<String>()
        val asked = graph.deliveryRequests.get()
        graph.haPublish.onRead = { entityId ->
            if (graph.deliveryRequests.get() == asked) before += entityId
            graph.haPublish.answer(entityId)
        }
        return before
    }

    @Test
    fun disableWhileHaIsUnreachableSucceeds() = rig.run {
        val api = ready()
        api.enable(PublishKind.SEASON_SERVICE_DATE, DATE)
        graph.awaitDispatched()
        graph.haPublish.helpers[DATE] = dateHelper(DATE, "2025-11-15")
        val unreachable = RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null)
        val asked = graph.deliveryRequests.get()
        val readsDuringTheCall = mutableListOf<String>()
        graph.haPublish.onRead = { entityId ->
            if (graph.deliveryRequests.get() == asked) readsDuringTheCall += entityId
            unreachable
        }
        graph.haPublish.log.clear()

        val binding = api.control(PublishKind.SEASON_SERVICE_DATE, "DISABLE").binding()!!
        assertEquals("NOT_ENABLED", binding.str("state"))
        assertEquals("false", binding.str("enabled"))
        assertEquals("Disable reads nothing (C25a): no validate", emptyList<String>(), readsDuringTheCall)
        assertEquals(asked + 1, graph.deliveryRequests.get())
        graph.awaitDispatched()
        val owed = graph.publishDecommissions.all().single()
        assertEquals(DATE, owed.entityId)
        assertEquals("Home Assistant unreachable: still owed", DecommissionState.PENDING, owed.state)
        assertTrue(graph.haPublish.posts.isEmpty())

        // Home Assistant reachable again: the next delivery writes 1970-01-01, reads it back, and lets the helper go.
        graph.haPublish.onRead = { graph.haPublish.answer(it) }
        graph.deliveryRequester.requestDelivery()
        graph.awaitDispatched()
        assertEquals(INVALID_SERVICE_DATE, graph.haPublish.state(DATE))
        assertEquals("POST $DATE=$INVALID_SERVICE_DATE", graph.haPublish.log.single { it.startsWith("POST") })
        assertTrue("the tombstone is deleted once read back", graph.publishDecommissions.all().isEmpty())
    }

    @Test
    fun disablingADisabledBindingIsANoOp() = rig.run {
        val api = ready()
        api.enable(PublishKind.EQUIPMENT_STATUS, STATUS)
        graph.awaitDispatched()
        val disabled = api.control(PublishKind.EQUIPMENT_STATUS, "DISABLE").binding()!!
        assertEquals("2", disabled.str("revision"))
        graph.awaitDispatched()
        val before = tables()

        val again = api.control(PublishKind.EQUIPMENT_STATUS, "DISABLE").binding()!!
        assertEquals("the binding as stored: no revision, no tombstone (R-1)", disabled, again)
        graph.awaitDispatched()
        assertEquals(before, tables())

        // An absent binding is the same no-op (B3 review M2): 200 with a null binding, nothing written.
        assertEquals(null, api.control(PublishKind.SEASON_SERVICE_DATE, "DISABLE").binding())
        graph.awaitDispatched()
        assertEquals(before, tables())
    }

    @Test
    fun checkHelperReadsTheStoredDestination() = rig.run {
        val api = ready()
        manualFollowingHa()
        api.enable(PublishKind.SEASON_SERVICE_DATE, DATE, boundary = ENDED)
        graph.awaitDispatched()
        val stored = graph.publishBindings.forAsset(heater).single()
        val reads = readsBeforeDelivery()

        val checked = api.control(PublishKind.SEASON_SERVICE_DATE, "CHECK_HELPER").binding()!!
        assertEquals("the stored target, then the stored season-ended helper", listOf(DATE, ENDED), reads)
        assertEquals(DATE, checked.str("entityId"))
        assertEquals(ENDED, checked.str("boundaryEntityId"))
        assertEquals((stored.revision + 1).toString(), checked.str("revision"))
        assertEquals("true", checked.str("validated"))
        assertEquals(graph.installationIdentity.id(), graph.publishBindings.forAsset(heater).single().activatedInstallationId)
        graph.awaitDispatched()
    }

    @Test
    fun checkHelperNeverActivatesACopiedRow() = rig.run {
        val api = ready()
        graph.enablePublishing.run(
            heater, PublishKind.EQUIPMENT_STATUS, STATUS, fresh(STATUS, PublishKind.EQUIPMENT_STATUS), null,
            "example-other-installation",
        )
        val checked = api.control(PublishKind.EQUIPMENT_STATUS, "CHECK_HELPER").binding()!!
        assertEquals("COPIED_INSTALLATION", checked.str("state"))
        assertEquals("example-other-installation", graph.publishBindings.forAsset(heater).single().activatedInstallationId)
        graph.awaitDispatched()
        assertTrue("a copied row sends nothing", graph.haPublish.posts.isEmpty())
    }

    @Test
    fun disableAndCheckHelperTakeNoConfirmation() = rig.run {
        val api = ready()
        api.enable(PublishKind.EQUIPMENT_STATUS, STATUS)
        graph.awaitDispatched()
        val before = tables()
        val asked = graph.deliveryRequests.get()
        for (action in listOf("DISABLE", "CHECK_HELPER")) {
            api.control(PublishKind.EQUIPMENT_STATUS, action, ""","planId":"${"a".repeat(64)}"""")
                .assertRefused(422, "HA_PUBLISH_CONFIRMATION_REQUIRED", "planId", "PublishConfirmationRequired(field=planId)")
            api.control(PublishKind.EQUIPMENT_STATUS, action, ""","confirmDestination":"$STATUS"""")
                .assertRefused(
                    422, "HA_PUBLISH_CONFIRMATION_REQUIRED", "confirmDestination",
                    "PublishConfirmationRequired(field=confirmDestination)",
                )
        }
        assertEquals(before, tables())
        assertEquals(asked, graph.deliveryRequests.get())
    }

    @Test
    fun checkHelperRefusesNoBindingAndAFailedRead() = rig.run {
        val api = ready()
        // C35b, A4w review N4: no binding of the kind is BAD_ENTITY_ID at `kind`, in a sentence about the kind.
        val none = api.control(PublishKind.SEASON_SERVICE_DATE, "CHECK_HELPER")
        none.assertRefused(409, "HA_PUBLISH_REFUSED", "kind", "PublishRefused(reason=BAD_ENTITY_ID)")
        assertTrue(none.bodyText(), none.errorDetail().message.contains("no SEASON_SERVICE_DATE publishing"))
        assertTrue(none.bodyText(), !none.errorDetail().message.contains("entity id"))

        api.enable(PublishKind.EQUIPMENT_STATUS, STATUS)
        graph.awaitDispatched()
        val before = tables()
        graph.haPublish.onRead = { RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) }
        api.control(PublishKind.EQUIPMENT_STATUS, "CHECK_HELPER")
            .assertRefused(409, "HA_HELPER_READ_FAILED", null, "HelperReadFailed(errorKind=UNREACHABLE)")
        assertEquals("a failed read writes nothing", before, tables())
    }

    // --- B5w2: Publish now, 202 (C35b, I35; row 119) -------------------------------------------------------------------

    /** The heater ready since 2025-11-15 (published), and Home Assistant's helper reset to the invalid date since. */
    private suspend fun ScenarioRig.publishedThenReset(api: V1Client) {
        role("2025-09-01")
        complete("2025-11-15")
        api.enable(PublishKind.SEASON_SERVICE_DATE, DATE)
        graph.awaitDispatched()
        assertEquals("2025-11-15", graph.haPublish.state(DATE))
        graph.haPublish.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        graph.now += 1_000
    }

    private fun V1Client.publishNow(assetId: AssetId = rig.heater): ApiResponse =
        call("POST", "/v1/assets/${assetId.value}/ha-publishing/publish-now", "{}")

    private fun ApiResponse.accepted(): JsonObject {
        assertEquals(bodyText(), 202, status)
        return Json.parseToJsonElement(bodyText()).jsonObject.also {
            assertEquals(listOf("requestId", "acceptedAt", "bindings"), it.keys.toList())
        }
    }

    /** The heater's date binding's `request`, as the read route answers it. */
    private fun V1Client.request(): JsonObject? {
        val read = Json.parseToJsonElement(call("GET", "/v1/assets/${rig.heater.value}/ha-publishing").bodyText()).jsonObject
        val date = read.getValue("bindings").jsonArray.map { it.jsonObject }.single { it.str("kind") == "SEASON_SERVICE_DATE" }
        return date.getValue("request") as? JsonObject
    }

    @Test
    fun publishNowAnswers202BeforeAnyHaCall() = rig.run {
        val api = ready()
        publishedThenReset(api)
        val held = CompletableDeferred<Unit>()
        graph.haPublish.onRead = { held.await(); graph.haPublish.answer(it) }
        val posted = graph.haPublish.posts.size
        val pool = Executors.newFixedThreadPool(2)
        try {
            val accepted = pool.submit<ApiResponse> { api.publishNow() }.get(10, TimeUnit.SECONDS).accepted()
            val status = pool.submit<ApiResponse> { api.call("GET", "/v1/status") }.get(10, TimeUnit.SECONDS)
            assertEquals("the API is free while Home Assistant is held", 200, status.status)
            assertEquals(graph.now, accepted.getValue("acceptedAt").jsonPrimitive.long)
            val covered = accepted.getValue("bindings").jsonArray.map { it.jsonObject }
            assertEquals(listOf("SEASON_SERVICE_DATE"), covered.map { it.str("kind") })
            assertEquals(setOf("kind", "state"), covered.single().keys)
            assertEquals("nothing was posted before the answer", posted, graph.haPublish.posts.size)
            val request = api.request()!!
            assertEquals(accepted.str("requestId"), request.str("id"))
            assertEquals("ACCEPTED", request.str("progress"))
        } finally {
            held.complete(Unit)
            pool.shutdown()
        }
        graph.awaitDispatched()
        assertEquals("PUBLISHED", api.request()!!.str("progress"))
        assertEquals("2025-11-15", graph.haPublish.state(DATE))
    }

    @Test
    fun theReadShowsAcceptedThenPublishedVerifiedOrFailed() = rig.run {
        val api = ready()
        publishedThenReset(api)

        api.publishNow().accepted()
        graph.awaitDispatched()
        assertEquals("our POST, read back", "PUBLISHED", api.request()!!.str("progress"))

        graph.now += 1_000
        val posted = graph.haPublish.posts.size
        api.publishNow().accepted()
        graph.awaitDispatched()
        assertEquals("Home Assistant already held it", "VERIFIED", api.request()!!.str("progress"))
        assertEquals("read back, no write", posted, graph.haPublish.posts.size)

        graph.now += 1_000
        graph.haPublish.onRead = { RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) }
        api.publishNow().accepted()
        graph.awaitDispatched()
        assertEquals("an error after the request", "FAILED", api.request()!!.str("progress"))
    }

    @Test
    fun aGateSkippedBindingIsNotCovered() = rig.run {
        val api = ready()
        // The status kind with no condition recorded waits for its source; the date kind was activated on another phone.
        api.enable(PublishKind.EQUIPMENT_STATUS, STATUS)
        graph.enablePublishing.run(
            heater, PublishKind.SEASON_SERVICE_DATE, DATE, fresh(DATE, PublishKind.SEASON_SERVICE_DATE), null,
            "example-other-installation",
        )
        graph.awaitDispatched()
        val before = tables()
        val accepted = api.publishNow().accepted()
        assertEquals(0, accepted.getValue("bindings").jsonArray.size)
        graph.awaitDispatched()
        assertEquals("nothing stamped", before, tables())
    }

    /** B5w1 ruling (1): a quiet INVALIDATED_INELIGIBLE date binding is neither read nor sent, so it is not covered. */
    @Test
    fun aQuietIneligibleBindingIsNotCovered() = rig.run {
        val api = ready()
        api.enable(PublishKind.SEASON_SERVICE_DATE, DATE)
        graph.awaitDispatched()
        graph.assets.upsert(
            assetRow(heater.value, "Example Heater", status = AssetStatus.ARCHIVED, seasonMode = SeasonMode.CALENDAR, seasonStart = "04-01", seasonEnd = "10-31"),
        )
        graph.publishProjector.reconcileDesired(graph.today)
        graph.publishRunner.drain()
        val state = Json.parseToJsonElement(api.call("GET", "/v1/assets/${heater.value}/ha-publishing").bodyText())
            .jsonObject.getValue("bindings").jsonArray.single().jsonObject.str("state")
        assertEquals("INVALIDATED_INELIGIBLE", state)
        val before = tables()
        graph.haPublish.log.clear()

        assertEquals(0, api.publishNow().accepted().getValue("bindings").jsonArray.size)
        graph.awaitDispatched()
        assertEquals(before, tables())
        assertTrue("neither read nor sent", graph.haPublish.log.isEmpty())
    }

    @Test
    fun aSecondRequestIsSafe() = rig.run {
        val api = ready()
        publishedThenReset(api)
        val posted = graph.haPublish.posts.size
        val held = CompletableDeferred<Unit>()
        graph.haPublish.onRead = { held.await(); graph.haPublish.answer(it) }
        // Each call on its own thread, bounded: a handler that waited on Home Assistant would fail here, not hang.
        val pool = Executors.newSingleThreadExecutor()
        val (first, second) = try {
            val first = pool.submit<ApiResponse> { api.publishNow() }.get(10, TimeUnit.SECONDS).accepted().str("requestId")
            graph.now += 1_000
            first to pool.submit<ApiResponse> { api.publishNow() }.get(10, TimeUnit.SECONDS).accepted().str("requestId")
        } finally {
            held.complete(Unit)
            pool.shutdown()
        }
        graph.awaitDispatched()
        assertNotEquals(first, second)
        assertEquals("the later request replaces the stamp", second, api.request()!!.str("id"))
        assertEquals("one POST: the second pass reads it back", 1, graph.haPublish.posts.size - posted)
        assertEquals("PUBLISHED", api.request()!!.str("progress"))
    }

    @Test
    fun theRequestSurvivesAGraphRebuild() = rig.run {
        val api = ready()
        publishedThenReset(api)
        val accepted = api.publishNow().accepted()
        graph.awaitDispatched()
        rebuild()
        val request = V1Client(graph).request()!!
        assertEquals(accepted.str("requestId"), request.str("id"))
        assertEquals(accepted.getValue("acceptedAt").jsonPrimitive.long, request.getValue("at").jsonPrimitive.long)
        assertEquals("PUBLISHED", request.str("progress"))
    }

    @Test
    fun publishNowTakesNoValueAndNamesAnAsset() = rig.run {
        val api = ready()
        assertEquals(404, api.publishNow(AssetId("example-no-such-asset")).status)
        val value = api.call("POST", "/v1/assets/${heater.value}/ha-publishing/publish-now", """{"value":"2026-02-10"}""")
        assertEquals(value.bodyText(), 400, value.status)
        assertEquals("another verb on an asset sub-resource is a 404", 404, api.call("GET", "/v1/assets/${heater.value}/ha-publishing/publish-now").status)
        assertEquals(0, graph.deliveryRequests.get())
    }

    // --- B5w2: the acknowledgement (C35b, O-21(b); row 120) ------------------------------------------------------------

    private suspend fun ScenarioRig.tombstone(id: String, state: DecommissionState): DecommissionTombstone =
        DecommissionTombstone(
            id, graph.haConnections.get()!!.id, "input_datetime.example_gone_service_done_$id", heater,
            graph.installationIdentity.id(), state, graph.now, PublishErrorKind.HTTP_ERROR, graph.now, 1,
        ).also { graph.publishDecommissions.upsertMerging(it) }

    private fun V1Client.acknowledge(pathId: String, body: String): ApiResponse =
        call("POST", "/v1/ha-publishing/decommissions/$pathId/acknowledge", body)

    @Test
    fun theAcknowledgementNeedsTheCallersAttestation() = rig.run {
        val api = ready()
        val owed = tombstone("example-t1", DecommissionState.UNDELIVERABLE)
        for ((body, field) in listOf(
            """{"decommissionId":"example-t1"}""" to "acknowledgedClearedInHa",
            """{"acknowledgedClearedInHa":true}""" to "decommissionId",
            """{"decommissionId":"example-t1","acknowledgedClearedInHa":false}""" to "acknowledgedClearedInHa",
            """{"decommissionId":"example-t1","acknowledgedClearedInHa":"true"}""" to "acknowledgedClearedInHa",
            """{"decommissionId":"example-t1","acknowledgedClearedInHa":1}""" to "acknowledgedClearedInHa",
            """{"decommissionId":"example-t1","acknowledgedClearedInHa":null}""" to "acknowledgedClearedInHa",
            """{"decommissionId":"example-t2","acknowledgedClearedInHa":true}""" to "decommissionId",
        )) {
            api.acknowledge("example-t1", body).assertRefused(
                422, "HA_PUBLISH_CONFIRMATION_REQUIRED", field, "PublishConfirmationRequired(field=$field)",
            )
        }
        assertEquals("nothing deleted", listOf(owed), graph.publishDecommissions.all())
        assertEquals(0, graph.deliveryRequests.get())
    }

    @Test
    fun anUnknownIdIs404() = rig.run {
        val api = ready()
        api.acknowledge("example-none", """{"decommissionId":"example-none","acknowledgedClearedInHa":true}""")
            .assertRefused(404, "NO_SUCH_DECOMMISSION", null)
    }

    @Test
    fun aPendingTombstoneIs409AndKept() = rig.run {
        val api = ready()
        val owed = tombstone("example-t1", DecommissionState.PENDING)
        api.acknowledge("example-t1", """{"decommissionId":"example-t1","acknowledgedClearedInHa":true}""")
            .assertRefused(409, "DECOMMISSION_STILL_PENDING", null)
        assertEquals(listOf(owed), graph.publishDecommissions.all())
    }

    @Test
    fun anUndeliverableOneIsDeletedWith204() = rig.run {
        val api = ready()
        tombstone("example-t1", DecommissionState.UNDELIVERABLE)
        val kept = tombstone("example-t2", DecommissionState.UNDELIVERABLE)
        val response = api.acknowledge("example-t1", """{"decommissionId":"example-t1","acknowledgedClearedInHa":true}""")
        assertEquals(response.bodyText(), 204, response.status)
        assertEquals(listOf(kept), graph.publishDecommissions.all())
        val listed = Json.parseToJsonElement(api.call("GET", "/v1/ha-publishing").bodyText()).jsonObject
            .getValue("decommissions").jsonArray.map { it.jsonObject.str("id") }
        assertEquals(listOf("example-t2"), listed)
        assertEquals(1, graph.deliveryRequests.get())
        graph.awaitDispatched()
        for (verb in listOf("GET", "PATCH", "DELETE")) {
            val body = if (verb == "PATCH") "{}" else ""
            assertEquals(verb, 405, api.call(verb, "/v1/ha-publishing/decommissions/example-t2/acknowledge", body).status)
        }
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        const val OTHER_DATE = "input_datetime.example_heater_service_done_2"
        const val BAD_STATUS = "input_select.example_heater_two_options"
        const val TIMED_DATE = "input_datetime.example_heater_with_time"
        val BOILER = AssetId("example-boiler")

        val STATUS_PLAN_KEYS = listOf(
            "planId", "assetId", "kind", "action", "entityId", "boundaryEntityId", "boundaryRequired", "remote", "boundary",
            "mapping", "refusal", "readAt", "expiresAt",
        )
        val DATE_PLAN_KEYS = STATUS_PLAN_KEYS.map { if (it == "mapping") "invalidDate" else it }

        val BINDING_KEYS = setOf(
            "kind", "entityId", "boundaryEntityId", "enabled", "state", "desiredValue", "lastPublished", "lastVerified",
            "lastError", "revision", "validated", "validatedAt", "remote", "needsDelivery", "reconcilePending",
            "outcomeUnknownSince", "lastAttemptAt", "boundary", "request",
        )
    }
}
