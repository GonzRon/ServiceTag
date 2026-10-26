package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.api.V1Client
import com.loosecannon.servicetag.api.bodyText
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.DATE
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.ENDED
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.INBOUND
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.dateHelper
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #124 (C41; row 142) — "exactly C9's inputs": over a matrix of assets — `CALENDAR`, `MANUAL` without Home Assistant,
 * and `MANUAL` following Home Assistant with a fresh and with an unverified season-ended date — the one reader of the
 * opening-service day, the publishing projector's date value (B4b1) and the readiness route's `seasonServiceDoneOn`
 * (A4) give one answer, before the service and after it, on the Room-backed graph. The two Home Assistant cases are
 * built so that the phone's END alone would answer otherwise. Today is 2026-02-10 (UTC); every asset, helper,
 * address and token is fictional.
 *
 * The readiness route reads Home Assistant's boundary only from B5 on (until then it passes `HaBoundary.NotUsed`), so
 * its leg runs on the two cases Home Assistant does not decide.
 */
class OpeningServiceAgreementTest {

    private val rig = ScenarioRig()
    private val graph get() = rig.graph
    private val api by lazy { V1Client(graph) }

    @After fun close() = rig.close()

    /** The phone's END for the heater, observed on [day] and written then. */
    private suspend fun phoneEnd(day: String) = graph.seasonActivations.insert(
        SeasonActivation("end-$day", rig.heater, SeasonAction.END, day, null, dayMillis(day)),
    )

    /**
     * The heater's date binding, enabled on the fictional connection. With [boundary] the heater first follows Home
     * Assistant's in-season helper (#16), so Enable stores [boundary] as its season-ended helper (C9a).
     */
    private suspend fun ScenarioRig.dateBinding(boundary: String? = null): PublishBinding {
        connect()
        graph.haPublish.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        boundary?.let {
            followsHa()
            graph.haPublish.helpers[it] = dateHelper(it, "2025-01-01")
        }
        return enable(PublishKind.SEASON_SERVICE_DATE, boundary = boundary)
    }

    /** The heater follows Home Assistant's in-season helper (#16, enabled). */
    private suspend fun ScenarioRig.followsHa() = graph.seasonSyncBindings.insert(
        SeasonSyncBinding(
            heater, graph.haConnections.get()!!.id, INBOUND, SyncMode.FOLLOW, enabled = true, revision = 1,
            observedState = null, observedChangedAt = null, lastSuccessAt = null, lastAttemptAt = null, errorKind = null,
            errorDetail = null, errorAt = null, appliedAction = null, appliedOn = null, appliedAt = null,
            lastAppliedSource = null, createdAt = graph.now, updatedAt = graph.now,
        ),
    )

    /** Home Assistant's season-ended date as the delivery caches it: [value], read [daysAgo] days ago at this revision. */
    private suspend fun cacheBoundary(binding: PublishBinding, value: String, daysAgo: Int) {
        val delivery = graph.publishDeliveries.get(binding.id)!!
        graph.publishDeliveries.upsert(
            delivery.copy(
                boundaryValue = value,
                boundaryReadAt = graph.now - daysAgo * DAY,
                boundaryBindingRevision = graph.publishBindings.get(binding.id)!!.revision,
            ),
        )
    }

    /** The route's `seasonServiceDoneOn`, or null. */
    private fun routeDoneOn(): String? {
        val response = api.call("GET", "/v1/assets/${rig.heater.value}/season-readiness")
        assertEquals(response.bodyText(), 200, response.status)
        val value = Json.parseToJsonElement(response.bodyText()).jsonObject.getValue("seasonServiceDoneOn")
        return if (value == JsonNull) null else value.jsonPrimitive.content
    }

    /** The reader, the projection and (with [route]) the readiness route all answer [expected]. */
    private suspend fun assertAgree(binding: PublishBinding, expected: String?, route: Boolean, why: String) {
        val read = graph.uow.read { graph.openingServiceReader.openingServiceOn(rig.heater) }
        graph.publishProjector.reconcileDesired(graph.today)
        val projected = graph.publishDeliveries.get(binding.id)!!.desiredValue
        assertEquals("the reader: $why", expected, read?.toString())
        assertEquals("the projection: $why", expected ?: INVALID_SERVICE_DATE, projected)
        if (route) assertEquals("the route: $why", expected, routeDoneOn())
    }

    @Test
    fun aCalendarAsset() = rig.run {
        calendar()
        role("2025-09-01")
        val binding = dateBinding()
        assertAgree(binding, null, route = true, why = "no service yet")

        complete("2026-01-20")
        assertAgree(binding, "2026-01-20", route = true, why = "after the window's end, 2025-11-01")
    }

    @Test
    fun aManualAssetWithoutHomeAssistant() = rig.run {
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        phoneEnd("2025-10-15")
        role("2025-09-01")
        val binding = dateBinding()
        assertAgree(binding, null, route = true, why = "no service yet")

        complete("2026-01-20")
        assertAgree(binding, "2026-01-20", route = true, why = "after the phone's END")
    }

    /** HA's season ended 2025-10-01, the phone saw it 2025-11-15: a service on 2025-10-20 counts only by HA's date. */
    @Test
    fun aManualAssetWithAFreshHomeAssistantBoundary() = rig.run {
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        phoneEnd("2025-11-15")
        role("2025-09-01")
        val binding = dateBinding(boundary = ENDED)
        cacheBoundary(binding, "2025-10-01", daysAgo = 1)
        assertAgree(binding, null, route = false, why = "no service yet")

        complete("2025-10-20")
        assertAgree(binding, "2025-10-20", route = false, why = "on or after HA's fresh date, before the phone's END")
    }

    /** HA's cached 2026-01-25, read a month ago, is later than the phone's END: a service on 2026-01-20 waits. */
    @Test
    fun aManualAssetWithAnUnverifiedHomeAssistantBoundary() = rig.run {
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        phoneEnd("2025-10-15")
        role("2025-09-01")
        val binding = dateBinding(boundary = ENDED)
        cacheBoundary(binding, "2026-01-25", daysAgo = 30)
        complete("2026-01-20")
        assertAgree(binding, null, route = false, why = "before the later of HA's cached date and the phone's END")

        complete("2026-02-01")
        assertAgree(binding, "2026-02-01", route = false, why = "after both")
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
