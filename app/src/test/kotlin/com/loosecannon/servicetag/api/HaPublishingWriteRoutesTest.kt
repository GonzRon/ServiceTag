package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.DecommissionState
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
