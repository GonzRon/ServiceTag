package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
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
import com.loosecannon.servicetag.testing.scheduleOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #125 (constraint 3, I-R; row h6; hazard Z1) — a schedule's own Home Assistant season never reaches publishing. A
 * `MANUAL` "Example Heater" follows Home Assistant's in-season helper (#16) and publishes its season service date with a
 * season-ended helper (C9a); its Season Start schedule follows its own winterize switch. The switch turning ON and OFF
 * moves the schedule's own season and nothing of the asset's: no desired or published value, no read of the
 * season-ended helper, no delivery or decommission record, no asset season row. A second, `MANUAL` heater ready by its
 * own season shows the leak the separate tables prevent: its schedule's END, read as the asset's, would unpublish its
 * service date. The real projector, runner and dispatch, over the Room-backed graph and the scripted Home Assistant;
 * every name is fictional.
 */
class ScheduleSeasonPublishingTest {

    private val rig = ScenarioRig()
    private val graph get() = rig.graph
    private val ha get() = rig.graph.haPublish

    @After fun close() = rig.close()

    private val winterize = "input_boolean.example_heater_winterize_season"
    private val opening = ScheduleId("example-season-start")

    /** What the observer does after a write: a projection, then a delivery request, awaited. */
    private suspend fun settle() {
        graph.publishProjector.reconcileDesired(graph.today)
        graph.deliveryRequester.requestDelivery()
        graph.awaitDispatched()
    }

    /** Everything publishing owns or says, and every request Home Assistant has seen. */
    private suspend fun published(): Map<String, Any> = mapOf(
        "bindings" to graph.publishBindings.all().toSet(),
        "deliveries" to graph.publishBindings.all().mapNotNull { graph.publishDeliveries.get(it.id) }.toSet(),
        "decommissions" to graph.publishDecommissions.all().toSet(),
        "helpers" to ha.helpers.toMap(),
        "requests" to ha.log.toList(),
        "assetSeason" to graph.seasonActivations.all().toSet(),
    )

    @Test fun aSchedulesOwnSwitchMovesNothingPublished() = rig.run {
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        role("2025-06-01")
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
        ha.helpers[ENDED] = dateHelper(ENDED, "2025-06-15")
        ha.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        enable(PublishKind.SEASON_SERVICE_DATE, boundary = ENDED)
        settle()
        graph.uow.write {
            graph.schedules.upsert(scheduleOf(opening.value, assetId = heater.value, title = "Example season start"))
        }
        graph.linkScheduleSeason.run(opening, winterize)
        settle()
        settle()
        val before = published()

        for ((day, state) in listOf("2026-10-20" to HaSwitchState.ON, "2026-10-28" to HaSwitchState.OFF, "2026-11-02" to HaSwitchState.ON)) {
            on(day)
            val binding = graph.scheduleSeasonBindings.get(opening)!!
            graph.recordScheduleSeasonSyncResult.run(opening, binding.revision, graph.now, graph.now, HaReadOutcome.Observed(state, null))
            settle()
            assertEquals("$day $state: nothing published moved", before, published())
        }

        assertEquals(
            "the switch moved the schedule's own season, and only that",
            listOf(SeasonAction.START, SeasonAction.END, SeasonAction.START),
            graph.scheduleSeasonActivations.forSchedule(opening).sortedBy { it.occurredOn }.map { it.action },
        )
    }

    /**
     * The discriminating case: a `MANUAL` heater whose own season (not Home Assistant's) ended 04-01, serviced 09-15, so
     * it is ready and its date helper carries 2026-09-15. Its schedule's switch turning OFF on 10-28 ends only the
     * schedule's season; read as the asset's, it would end the asset's season after the service, and the published
     * date would fall back to 1970-01-01.
     */
    @Test fun aSchedulesOwnEndNeverEndsTheAssetsSeasonForPublishing() = rig.run {
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        role("2025-06-01")
        on("2026-04-01")
        graph.seasonActivations.insert(SeasonActivation(graph.ids.newId(), heater, SeasonAction.END, "2026-04-01", null, graph.now))
        on("2026-09-15")
        complete("2026-09-15")
        connect()
        ha.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        enable(PublishKind.SEASON_SERVICE_DATE)
        settle()
        assertEquals("the seed publishes the service date", "2026-09-15", ha.state(DATE))
        graph.uow.write {
            graph.schedules.upsert(scheduleOf(opening.value, assetId = heater.value, title = "Example winter check"))
        }
        graph.linkScheduleSeason.run(opening, winterize)
        settle()
        val before = published()

        for ((day, state) in listOf("2026-10-20" to HaSwitchState.ON, "2026-10-28" to HaSwitchState.OFF)) {
            on(day)
            val binding = graph.scheduleSeasonBindings.get(opening)!!
            graph.recordScheduleSeasonSyncResult.run(opening, binding.revision, graph.now, graph.now, HaReadOutcome.Observed(state, null))
            settle()
            assertEquals("$day $state: nothing published moved", before, published())
        }
        assertEquals("2026-09-15", ha.state(DATE))
        assertEquals(
            listOf(SeasonAction.START, SeasonAction.END),
            graph.scheduleSeasonActivations.forSchedule(opening).sortedBy { it.occurredOn }.map { it.action },
        )
    }
}
