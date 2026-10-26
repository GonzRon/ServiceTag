package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.DATE
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.STATUS
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.dateHelper
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.statusHelper
import com.loosecannon.servicetag.seasonsync.HOME_WIFI
import com.loosecannon.servicetag.testing.RecordingPublishWork
import com.loosecannon.servicetag.testing.assetRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #113/#114 (A03, I21, C35b, I35; rows 70a, 122) — every publishing control reaches Home Assistant through
 * `PublishControls` alone: the command, then the dispatch. No test here calls `drain()`, starts the observer or runs a
 * lifecycle or time trigger, and no source value moves; background checks are Off. And Publish now stamps only the
 * bindings a pass will cover, returns while Home Assistant is held, and triggers no projection. Every asset, helper,
 * address and token is fictional.
 */
class ControlDispatchTest {

    private val rig = ScenarioRig()
    private val graph get() = rig.graph
    private val ha get() = rig.graph.haPublish

    @After fun close() = rig.close()

    /**
     * The heater's date binding published `2025-11-15` and verified; then Home Assistant reset to `1970-01-01`, which
     * only a read can find. Background checks Off: FOREGROUND_ONLY.
     */
    private suspend fun ScenarioRig.verifiedThenReset(): PublishBinding {
        calendar()
        role("2025-09-01")
        complete("2025-11-15")
        connect()
        ha.helpers[DATE] = dateHelper(DATE, "2025-11-15")
        val binding = enable(PublishKind.SEASON_SERVICE_DATE)
        graph.publishProjector.reconcileDesired(graph.today)
        graph.publishRunner.publishNow(heater)
        assertEquals("2025-11-15", delivery(binding).lastVerifiedValue)
        ha.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        ha.log.clear()
        return binding
    }

    /** What the control's own request brought: the helper read, written and read back (a let-go helper's aside). */
    private suspend fun ScenarioRig.theHelperIsReadAndWritten(entityId: String = DATE) {
        graph.awaitDispatched()
        assertEquals(
            listOf("GET $entityId", "POST $entityId=2025-11-15", "GET $entityId"),
            ha.log.filter { it == "GET $entityId" || it.startsWith("POST $entityId=") },
        )
        assertEquals("2025-11-15", ha.state(entityId))
    }

    // Row 70a

    @Test
    fun reEnableDelivers() = rig.run {
        verifiedThenReset()
        graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)

        graph.publishControls.enable(heater, PublishKind.SEASON_SERVICE_DATE, DATE, fresh(DATE, PublishKind.SEASON_SERVICE_DATE), null)

        theHelperIsReadAndWritten()
    }

    @Test
    fun aTargetChangeDelivers() = rig.run {
        verifiedThenReset()
        ha.helpers[OTHER_DATE] = dateHelper(OTHER_DATE, INVALID_SERVICE_DATE)

        graph.publishControls.changeTarget(
            heater, PublishKind.SEASON_SERVICE_DATE, OTHER_DATE, fresh(OTHER_DATE, PublishKind.SEASON_SERVICE_DATE), null,
        )

        theHelperIsReadAndWritten(OTHER_DATE)
    }

    @Test
    fun checkHelperDelivers() = rig.run {
        verifiedThenReset()

        graph.publishControls.revalidate(heater, PublishKind.SEASON_SERVICE_DATE, fresh(DATE, PublishKind.SEASON_SERVICE_DATE), null)

        theHelperIsReadAndWritten()
    }

    @Test
    fun aTokenUpdateDelivers() = rig.run {
        verifiedThenReset()

        graph.publishControls.saveConnection(
            ScenarioRig.ADDRESS, Secret("fictional-token-2"), null, NetworkEligibility.HOME_NETWORK_ONLY, HOME_WIFI, null,
        )

        theHelperIsReadAndWritten()
        assertEquals(Secret("fictional-token-2"), ha.tokens.last())
    }

    /**
     * Disable of the heater's date binding while the boiler's stays enabled: the tombstone is committed and the
     * dispatch asked at once — its rule applied (FOREGROUND_ONLY: both works cancelled) and its drain run, which
     * delivers the tombstone's `1970-01-01` to the old helper and reads it back (C34), then deletes the row.
     */
    @Test
    fun disablingOneDateBindingWhileAnotherStaysEnabledAsksForDelivery() = rig.run {
        verifiedThenReset()
        graph.assets.upsert(assetRow(BOILER.value, "Example Boiler", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-01", seasonEnd = "10-31"))
        ha.helpers[BOILER_DATE] = dateHelper(BOILER_DATE, INVALID_SERVICE_DATE)
        graph.enablePublishing.run(
            BOILER, PublishKind.SEASON_SERVICE_DATE, BOILER_DATE, fresh(BOILER_DATE, PublishKind.SEASON_SERVICE_DATE, BOILER),
            null, graph.installationIdentity.id(),
        )
        ha.helpers[DATE] = dateHelper(DATE, "2025-11-15") // Home Assistant holds the published date again
        val before = graph.deliveryRequests.get()

        graph.publishControls.disable(heater, PublishKind.SEASON_SERVICE_DATE)
        graph.awaitDispatched()

        assertEquals(before + 1, graph.deliveryRequests.get())
        assertEquals(RecordingPublishWork.Call.Cancel, graph.publishWork.calls.last())
        assertEquals(
            listOf("GET $DATE", "POST $DATE=$INVALID_SERVICE_DATE", "GET $DATE"),
            ha.log.filter { it == "GET $DATE" || it.startsWith("POST $DATE=") },
        )
        assertEquals(INVALID_SERVICE_DATE, ha.state(DATE))
        assertTrue("the obligation read back and deleted", graph.publishDecommissions.all().isEmpty())
        assertTrue(graph.publishBindings.forAsset(BOILER).single().enabled)
    }

    // Row 122

    @Test
    fun requestPublishNowStampsTheCoveredBindingsAndNeverAwaits() = rig.run {
        val date = verifiedThenReset()
        ha.helpers[STATUS] = statusHelper("UP")
        val status = enable(PublishKind.EQUIPMENT_STATUS) // no condition recorded: WAITING_FOR_SOURCE, not covered
        val observer = graph.publishTriggers.observe()
        delay(OBSERVER_SETTLES)
        val projections = graph.desiredStateReconciler.days.size
        val before = listOf(date, status).associate { it.id to (graph.publishBindings.get(it.id) to delivery(it)) }
        val held = CompletableDeferred<Unit>()
        val reading = CompletableDeferred<Unit>()
        ha.onRead = { reading.complete(Unit); held.await(); ha.answer(it) }

        graph.now += 1_000
        val request = withTimeout(5_000) { graph.publishControls.requestPublishNow(heater) }

        assertEquals(listOf(date.id), request.bindings.map { it.id })
        assertEquals(graph.now, request.acceptedAt)
        val stamped = delivery(date)
        assertEquals(request.requestId, stamped.lastRequestId)
        assertEquals(request.acceptedAt, stamped.lastRequestAt)
        assertNull(delivery(status).lastRequestId)
        val (dateBinding, dateDelivery) = before.getValue(date.id)
        assertEquals(dateBinding, graph.publishBindings.get(date.id))
        assertEquals(dateDelivery.copy(lastRequestId = request.requestId, lastRequestAt = request.acceptedAt), stamped)

        reading.await() // the pass is under way, Home Assistant held
        delay(OBSERVER_SETTLES)
        assertEquals("the stamp triggers no projection", projections, graph.desiredStateReconciler.days.size)
        held.complete(Unit)
        graph.awaitDispatched()
        observer.cancel()
        assertEquals("2025-11-15", ha.state(DATE))

        val none = graph.publishControls.requestPublishNow(AssetId("example-no-such-asset"))
        assertTrue("no covered binding: nothing stamped", none.bindings.isEmpty())
    }

    private companion object {
        const val OTHER_DATE = "input_datetime.example_heater_service_done_2"
        const val BOILER_DATE = "input_datetime.example_boiler_service_done"
        val BOILER = AssetId("example-boiler")

        /** Three of the observer's debounce windows. */
        const val OBSERVER_SETTLES = 3 * PublishTriggers.DEBOUNCE_MILLIS
    }
}
