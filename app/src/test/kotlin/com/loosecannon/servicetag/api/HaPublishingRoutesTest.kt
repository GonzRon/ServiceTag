package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.season.BoundarySource
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #113/#114 (C35, G-R1, C35b, C29, G4; rows 82, 107's B5 cases) — `GET /v1/assets/{id}/ha-publishing` through the
 * production router over `FakeGraph`, on the JVM: the bindings and the asset's obligations as keys, rev 4.1's delivery
 * facts with their `current` flags and the boundary cache, rev 5's `request` and its `RequestProgress` read from the
 * existing pairs; never the address, the token, the Wi-Fi name or the installation id; and the readiness route's Home
 * Assistant boundary (C9a) read from the date binding. Every asset, helper, address and token is fictional.
 */
class HaPublishingRoutesTest {

    private val rig = ScenarioRig()
    private val api get() = V1Client(rig.graph)

    @After fun close() = rig.close()

    private fun publishing(assetId: AssetId = rig.heater): JsonObject {
        val response = api.call("GET", "/v1/assets/${assetId.value}/ha-publishing")
        assertEquals(response.bodyText(), 200, response.status)
        return Json.parseToJsonElement(response.bodyText()).jsonObject
    }

    /** The heater's two bindings published (`UP`, `2025-11-15`), then its date helper let go for another. */
    private suspend fun ScenarioRig.bothPublishedThenRetargeted() {
        calendar()
        role("2025-09-01")
        complete("2025-11-15")
        connect()
        record(OperationalCondition.OPERATIONAL)
        graph.haPublish.helpers[STATUS] = statusHelper("unknown")
        graph.haPublish.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        graph.haPublish.helpers[OTHER_DATE] = dateHelper(OTHER_DATE, INVALID_SERVICE_DATE)
        enable(PublishKind.EQUIPMENT_STATUS)
        enable(PublishKind.SEASON_SERVICE_DATE)
        graph.publishProjector.reconcileDesired(graph.today)
        graph.publishRunner.drain()
        graph.changePublishTarget.run(
            heater, PublishKind.SEASON_SERVICE_DATE, OTHER_DATE, fresh(OTHER_DATE, PublishKind.SEASON_SERVICE_DATE), null,
            graph.installationIdentity.id(),
        )
    }

    // --- row 82 -------------------------------------------------------------------------------------------------------

    @Test
    fun bindingsAndTombstonesAsKeys() = rig.run {
        bothPublishedThenRetargeted()

        val tree = publishing()
        assertEquals(setOf("assetId", "bindings", "pendingDecommissions"), tree.keys)
        assertEquals(heater.value, tree.getValue("assetId").jsonPrimitive.content)
        val bindings = tree.getValue("bindings").jsonArray.map { it.jsonObject }
        assertEquals(listOf("EQUIPMENT_STATUS", "SEASON_SERVICE_DATE"), bindings.map { it.str("kind") })
        bindings.forEach { assertEquals(BINDING_KEYS, it.keys) }
        val status = bindings[0]
        assertEquals(STATUS, status.str("entityId"))
        assertEquals(JsonNull, status.getValue("boundaryEntityId"))
        assertEquals("PUBLISHED", status.str("state"))
        assertEquals("UP", status.str("desiredValue"))
        assertEquals("UP", status.getValue("lastPublished").jsonObject.str("value"))
        val date = bindings[1]
        assertEquals(OTHER_DATE, date.str("entityId"))
        assertEquals("PENDING", date.str("state"))
        assertEquals("a new target inherits no published line", JsonNull, date.getValue("lastPublished"))

        val owed = tree.getValue("pendingDecommissions").jsonArray.single().jsonObject
        assertEquals(setOf("id", "entityId", "state", "requestedAt", "lastError"), owed.keys)
        assertEquals(DATE, owed.str("entityId"))
        assertEquals(DecommissionState.PENDING.name, owed.str("state"))
        assertEquals(graph.publishDecommissions.all().single().id, owed.str("id"))
        assertEquals(JsonNull, owed.getValue("lastError"))

        // Drained against an unreachable helper: still pending, with its error's kind and time.
        graph.haPublish.onRead = {
            if (it == DATE) RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) else graph.haPublish.answer(it)
        }
        graph.publishRunner.drain()
        val failed = publishing().getValue("pendingDecommissions").jsonArray.single().jsonObject
        assertEquals(setOf("kind", "at"), failed.getValue("lastError").jsonObject.keys)
        assertEquals("UNREACHABLE", failed.getValue("lastError").jsonObject.str("kind"))
    }

    @Test
    fun noAddressTokenWifiOrInstallationId() = rig.run {
        bothPublishedThenRetargeted()
        val body = api.call("GET", "/v1/assets/${heater.value}/ha-publishing").bodyText()
        for (secret in listOf("192.168.0.10", "fictional-token-1", "ExampleHomeWifi", graph.installationIdentity.id())) {
            assertFalse("$secret in $body", body.contains(secret))
        }
    }

    @Test
    fun anUnknownAssetIs404AndAnAssetWithNoneIsEmpty() = rig.run {
        val missing = api.call("GET", "/v1/assets/example-no-such-asset/ha-publishing")
        assertEquals(404, missing.status)
        assertEquals("no_such_asset", missing.errorDetail().code)
        yearRound()
        val tree = publishing()
        assertTrue(tree.getValue("bindings").jsonArray.isEmpty())
        assertTrue(tree.getValue("pendingDecommissions").jsonArray.isEmpty())
    }

    // --- row 107 (B5's cases) -----------------------------------------------------------------------------------------

    /**
     * A date binding as rows hold it mid-flight: generation 4, validated at it, a cached season-ended read from
     * generation 3, published at an older pair, verified at the current one, an error at the current pair, an
     * outstanding write-ahead and a pending reconcile.
     */
    @Test
    fun deliveryFlagsPairsAndBoundaryAsKeys() = rig.run {
        graph.assets.upsert(assetRow(BOILER.value, "Example Boiler", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-01", seasonEnd = "10-31"))
        val connection = connect()
        val binding = PublishBinding(
            id = "example-binding-1", assetId = BOILER, connectionId = connection.id,
            kind = PublishKind.SEASON_SERVICE_DATE, entityId = BOILER_DATE, enabled = true, revision = 4,
            activatedInstallationId = graph.installationIdentity.id(), validatedAt = 4_000L, validatedRevision = 4,
            boundaryEntityId = ENDED, createdAt = 1_000L, updatedAt = 4_000L,
        )
        graph.publishBindings.insert(binding)
        graph.publishDeliveries.upsert(
            PublishDelivery(
                bindingId = binding.id, desiredValue = "2025-11-15", desiredRevision = 7, desiredAt = 5_000L,
                lastPublishedValue = "2025-11-01", lastPublishedAt = 4_500L, publishedBindingRevision = 3,
                publishedDesiredRevision = 7,
                lastVerifiedValue = "2025-11-15", lastVerifiedAt = 6_000L, verifiedBindingRevision = 4,
                verifiedDesiredRevision = 7,
                remoteValue = "2025-11-14", remoteReadAt = 7_000L,
                errorKind = PublishErrorKind.HTTP_ERROR, errorDetail = "503", errorAt = 7_500L, errorBindingRevision = 4,
                errorDesiredRevision = 7,
                outcomeUnknownSince = 7_200L, needsDelivery = true, attemptsAtRevision = 2, lastAttemptAt = 7_200L,
                boundaryValue = "2025-05-12", boundaryReadAt = 3_500L, boundaryBindingRevision = 3,
                reconcilePending = true,
            ),
        )

        val date = publishing(BOILER).getValue("bindings").jsonArray.single().jsonObject
        assertEquals(BINDING_KEYS, date.keys)
        assertEquals(ENDED, date.str("boundaryEntityId"))
        assertEquals("4", date.str("revision"))
        assertEquals("true", date.str("validated"))
        assertEquals("4000", date.str("validatedAt"))
        assertEquals(mapOf("value" to "2025-11-14", "readAt" to "7000"), date.getValue("remote").jsonObject.strings())
        assertEquals("true", date.str("needsDelivery"))
        assertEquals("true", date.str("reconcilePending"))
        assertEquals("7200", date.str("outcomeUnknownSince"))
        assertEquals("7200", date.str("lastAttemptAt"))
        assertEquals(
            mapOf("value" to "2025-11-01", "at" to "4500", "current" to "false"),
            date.getValue("lastPublished").jsonObject.strings(),
        )
        assertEquals(
            mapOf("value" to "2025-11-15", "at" to "6000", "current" to "true"),
            date.getValue("lastVerified").jsonObject.strings(),
        )
        assertEquals(
            mapOf("kind" to "HTTP_ERROR", "detail" to "503", "at" to "7500", "current" to "true"),
            date.getValue("lastError").jsonObject.strings(),
        )
        assertEquals(
            mapOf("value" to "2025-05-12", "readAt" to "3500", "atCurrentRevision" to "false"),
            date.getValue("boundary").jsonObject.strings(),
        )
        assertEquals("ERROR", date.str("state"))
        assertEquals(JsonNull, date.getValue("request"))
    }

    @Test
    fun theRequestProgressFollowsTheExistingPairs() = rig.run {
        val binding = PublishBinding(
            id = "b", assetId = heater, connectionId = "c", kind = PublishKind.EQUIPMENT_STATUS, entityId = STATUS,
            enabled = true, revision = 2, activatedInstallationId = "i", validatedAt = 1L, validatedRevision = 2,
            boundaryEntityId = null, createdAt = 1L, updatedAt = 1L,
        )
        val accepted = PublishDelivery("b", "UP", 3, 1L, lastRequestId = "r-1", lastRequestAt = 100L)
        assertNull(requestProgressOf(binding, accepted.copy(lastRequestId = null, lastRequestAt = null)))
        assertEquals(RequestProgress.ACCEPTED, requestProgressOf(binding, accepted))
        // Lines and errors older than the request, or at another pair, never answer it.
        val verifiedBefore = accepted.copy(lastVerifiedValue = "UP", lastVerifiedAt = 99L, verifiedBindingRevision = 2, verifiedDesiredRevision = 3)
        assertEquals(RequestProgress.ACCEPTED, requestProgressOf(binding, verifiedBefore))
        val verified = verifiedBefore.copy(lastVerifiedAt = 120L)
        assertEquals(RequestProgress.VERIFIED, requestProgressOf(binding, verified))
        assertEquals(RequestProgress.ACCEPTED, requestProgressOf(binding, verified.copy(verifiedDesiredRevision = 2)))
        val published = verified.copy(lastPublishedValue = "UP", lastPublishedAt = 120L, publishedBindingRevision = 2, publishedDesiredRevision = 3)
        assertEquals(RequestProgress.PUBLISHED, requestProgressOf(binding, published))
        val failed = accepted.copy(errorKind = PublishErrorKind.UNREACHABLE, errorAt = 130L, errorBindingRevision = 2, errorDesiredRevision = 3)
        assertEquals(RequestProgress.FAILED, requestProgressOf(binding, failed))
        assertEquals(RequestProgress.ACCEPTED, requestProgressOf(binding, failed.copy(errorBindingRevision = 1)))

        // Through the route: Publish now's stamp, then what its pass came to.
        yearRound()
        connect()
        record(OperationalCondition.DEGRADED)
        graph.haPublish.helpers[STATUS] = statusHelper("DEGRADED")
        enable(PublishKind.EQUIPMENT_STATUS)
        graph.publishProjector.reconcileDesired(graph.today)
        graph.now += 1_000
        val request = graph.publishControls.requestPublishNow(heater)
        graph.awaitDispatched()
        val stamped = publishing().getValue("bindings").jsonArray.single().jsonObject.getValue("request").jsonObject
        assertEquals(mapOf("id" to request.requestId, "at" to request.acceptedAt.toString(), "progress" to "VERIFIED"), stamped.strings())
    }

    // --- the readiness route's Home Assistant boundary (C29, C9a; A4's carry) ---------------------------------------

    @Test
    fun theReadinessRouteReadsTheDateBindingsBoundary() = rig.run {
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        role("2025-09-01")
        val connection = connect()
        graph.seasonSyncBindings.insert(
            SeasonSyncBinding(
                heater, connection.id, INBOUND, SyncMode.FOLLOW, enabled = true, revision = 1, observedState = null,
                observedChangedAt = null, lastSuccessAt = null, lastAttemptAt = null, errorKind = null, errorDetail = null,
                errorAt = null, appliedAction = null, appliedOn = null, appliedAt = null, lastAppliedSource = null,
                createdAt = graph.now, updatedAt = graph.now,
            ),
        )
        graph.haPublish.helpers[ENDED] = dateHelper(ENDED, "2026-01-05")
        graph.haPublish.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        enable(PublishKind.SEASON_SERVICE_DATE, boundary = ENDED)

        val readiness = api.ok(SeasonReadinessResponse.serializer(), "GET", "/v1/assets/${heater.value}/season-readiness")
        assertEquals(BoundarySource.HA_SEASON_ENDED.name, readiness.boundarySource)
        assertEquals("2026-01-05", readiness.cutoffOn)
        assertNull(readiness.boundaryUnverifiedSince)
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.strings(): Map<String, String> = mapValues { it.value.jsonPrimitive.content }

    private companion object {
        const val OTHER_DATE = "input_datetime.example_heater_service_done_2"
        const val BOILER_DATE = "input_datetime.example_boiler_service_done"
        val BOILER = AssetId("example-boiler")

        val BINDING_KEYS = setOf(
            "kind", "entityId", "boundaryEntityId", "enabled", "state", "desiredValue", "lastPublished", "lastVerified",
            "lastError", "revision", "validated", "validatedAt", "remote", "needsDelivery", "reconcilePending",
            "outcomeUnknownSince", "lastAttemptAt", "boundary", "request",
        )
    }
}
