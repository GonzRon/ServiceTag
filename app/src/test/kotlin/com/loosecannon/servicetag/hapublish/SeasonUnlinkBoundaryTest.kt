package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.boundaryCache
import com.loosecannon.servicetag.core.hapublish.haBoundaryOf
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.season.BoundarySource
import com.loosecannon.servicetag.core.season.HaBoundary
import com.loosecannon.servicetag.core.season.Readiness
import com.loosecannon.servicetag.core.season.seasonReadiness
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.DATE
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.ENDED
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.INBOUND
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.dateHelper
import com.loosecannon.servicetag.seasonsync.HOME_WIFI
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.ui.asset.enabledInboundIds
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #129 (L-C4; row k11; hazard Z4) — Remove link is Stop's case for #114's season boundary (C9a): with the #16 binding
 * gone Home Assistant no longer decides the `MANUAL` heater's season end, so readiness reads the phone's own END; the
 * date binding is not touched and keeps its season-ended helper; the projector re-projects exactly as it does after
 * Stop (a twin graph); a later link restores Home Assistant's authority with the stored helper; and #116's inbound mark
 * goes with the binding. The heater's phone END is 15 Apr, Home Assistant's season ended on 10 Sep, and the service was
 * done on 5 Sep, so the two authorities disagree. Every asset, helper, address and token is fictional.
 */
class SeasonUnlinkBoundaryTest {

    private val rig = ScenarioRig()
    private val twin = ScenarioRig()

    @After fun close() {
        rig.close()
        twin.close()
    }

    /** The heater, `MANUAL`, following Home Assistant (#16 enabled), its role, its history, its date binding. */
    private suspend fun ScenarioRig.manualHeaterFollowingHa() {
        on(TODAY)
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        role("2026-01-01")
        graph.seasonActivations.insert(SeasonActivation("act-1", heater, SeasonAction.START, "2025-10-01", null, dayMillis("2025-10-01")))
        graph.seasonActivations.insert(SeasonActivation("act-2", heater, SeasonAction.END, PHONE_END, null, dayMillis(PHONE_END)))
        val connection = graph.saveHaConnection.run(
            ScenarioRig.ADDRESS, Secret("fictional-token-1"), SyncCadence.DAILY, NetworkEligibility.HOME_NETWORK_ONLY,
            HOME_WIFI, BackgroundChecks.ON,
        )
        graph.backgroundAllowed = true
        graph.seasonSyncBindings.insert(
            SeasonSyncBinding(
                heater, connection.id, INBOUND, SyncMode.FOLLOW, enabled = true, revision = 1, observedState = null,
                observedChangedAt = null, lastSuccessAt = null, lastAttemptAt = null, errorKind = null, errorDetail = null,
                errorAt = null, appliedAction = null, appliedOn = null, appliedAt = null, lastAppliedSource = null,
                createdAt = graph.now, updatedAt = graph.now,
            ),
        )
        graph.haPublish.helpers[ENDED] = dateHelper(ENDED, HA_ENDED)
        graph.haPublish.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        enable(PublishKind.SEASON_SERVICE_DATE, boundary = ENDED)
        complete(SERVICE_ON)
        settle()
    }

    /** What the observer does after a write: a projection, then a delivery request, awaited. */
    private suspend fun ScenarioRig.settle() {
        graph.awaitSeasonSyncReads()
        graph.publishProjector.reconcileDesired(graph.today)
        graph.deliveryRequester.requestDelivery()
        graph.awaitDispatched()
    }

    private suspend fun ScenarioRig.dateBinding(): PublishBinding =
        graph.publishBindings.forAsset(heater).first { it.kind == PublishKind.SEASON_SERVICE_DATE }

    private suspend fun ScenarioRig.desired(): String? = graph.publishDeliveries.get(dateBinding().id)!!.desiredValue

    /** C9a as the card judges it now. */
    private suspend fun ScenarioRig.boundary(): HaBoundary = graph.uow.read {
        val binding = dateBinding()
        haBoundaryOf(
            graph.publishDeliveries.get(binding.id)!!.boundaryCache, binding, graph.assets.get(heater)!!.seasonMode,
            graph.seasonSyncBindings.get(heater)?.enabled == true, graph.haConnections.get()!!.cadence,
            graph.seasonActivations.forAsset(heater).maxOfOrNull { it.createdAt }, graph.now,
        )
    }

    /** The card's readiness over [boundary]. */
    private suspend fun ScenarioRig.readiness(): Readiness {
        val boundary = boundary()
        return graph.uow.read {
            seasonReadiness(
                graph.assets.get(heater)!!, graph.assets.all(), graph.schedules.all(), graph.groups.all(),
                graph.events.forAsset(heater), graph.seasonActivations.forAsset(heater), graph.today, ZoneOffset.UTC,
                boundary,
            )
        }
    }

    private suspend fun ScenarioRig.inboundMarked(): Boolean =
        heater in enabledInboundIds(graph.seasonSyncBindings.observeAll().first())

    @Test
    fun unlinkingHandsTheSeasonEndToThePhoneAndKeepsTheDateBinding() = rig.run {
        manualHeaterFollowingHa()
        twin.manualHeaterFollowingHa()
        val dateBinding = dateBinding()
        assertEquals(ENDED, dateBinding.boundaryEntityId)
        assertFalse("linked: Home Assistant decides", boundary() is HaBoundary.NotUsed)
        assertTrue("HA's 10 Sep end is after the 5 Sep service", readiness() is Readiness.NotReady)
        val linkedDesire = desired()
        assertTrue("#116: the inbound mark is on", inboundMarked())

        graph.unlinkSeasonSync.run(heater)
        settle()
        twin.graph.stopSeasonSync.run(heater)
        twin.settle()

        assertEquals("C9a: Home Assistant no longer decides", HaBoundary.NotUsed, boundary())
        val ready = readiness() as? Readiness.Ready ?: throw AssertionError("not ready: ${readiness()}")
        assertEquals(BoundarySource.PHONE_SEASON_END, ready.boundary.source)
        assertEquals(LocalDate.parse(PHONE_END), ready.boundary.cutoffOn)
        assertEquals(LocalDate.parse(SERVICE_ON), ready.serviceOn)
        assertEquals("the date binding row is untouched, its season-ended helper kept", dateBinding, dateBinding())
        assertNotEquals("the projector re-projected", linkedDesire, desired())
        assertEquals("the unlink re-projects as Stop does", twin.desired(), desired())
        assertEquals(SERVICE_ON, desired())
        assertFalse("#116: the inbound mark goes with the binding", inboundMarked())

        graph.linkSeasonSync.run(heater, INBOUND)
        settle()

        assertFalse("a later link restores Home Assistant's authority", boundary() is HaBoundary.NotUsed)
        assertEquals("with the stored helper", ENDED, dateBinding().boundaryEntityId)
        assertTrue(inboundMarked())
    }

    private companion object {
        const val TODAY = "2026-09-18"
        const val PHONE_END = "2026-04-15"
        const val HA_ENDED = "2026-09-10"
        const val SERVICE_ON = "2026-09-05"
    }
}
