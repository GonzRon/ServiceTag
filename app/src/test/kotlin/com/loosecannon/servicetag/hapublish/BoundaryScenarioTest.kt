package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.boundaryCache
import com.loosecannon.servicetag.core.hapublish.haBoundaryOf
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.season.NotReadyReason
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.season.BoundarySource
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
import com.loosecannon.servicetag.testing.scheduleOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneOffset

/**
 * #114 (C9a, A07, D, E, G22; rows 98, 99, 99a, 99b, 100, 100a) — the season boundary end to end: the real projector,
 * the runner's boundary step and the dispatch, against a scripted Home Assistant that also models its own start gate
 * (**released** ⇔ `service_done` is not `1970-01-01` and is on or after `season_ended`) and, in row 100a, §8's stamp
 * (`season_ended` set once per heating year, at the first OFF between 1 May and 31 July). The phone's readiness is read
 * as the card reads it. A `MANUAL` "Example Heater" follows Home Assistant's in-season helper; its date binding names
 * the season-ended helper. Every asset, helper, address and token is fictional.
 */
class BoundaryScenarioTest {

    private val rig = ScenarioRig()
    private val graph get() = rig.graph
    private val ha get() = rig.graph.haPublish

    @After fun close() = rig.close()

    // --- the scripted Home Assistant's side -------------------------------------------------------

    private fun serviceDone(): String = ha.state(DATE)!!

    /** Home Assistant's gate: a real date, on or after its own season end. */
    private fun released(): Boolean = serviceDone() != INVALID_SERVICE_DATE && serviceDone() >= ha.state(ENDED)!!

    private var stampedHeatingYear: Int? = null

    /** §8: in_season goes OFF on [day]; the first OFF of a heating year between 1 May and 31 July stamps the date. */
    private fun haOff(day: String) {
        val date = LocalDate.parse(day)
        val heatingYear = if (date.monthValue >= 8) date.year else date.year - 1
        val inWindow = MonthDay.from(date) in MonthDay.of(5, 1)..MonthDay.of(7, 31)
        if (inWindow && stampedHeatingYear != heatingYear) {
            ha.helpers[ENDED] = dateHelper(ENDED, day)
            stampedHeatingYear = heatingYear
        }
    }

    // --- the phone's side -------------------------------------------------------------------------

    /** The heater, `MANUAL`, following Home Assistant (#16 enabled); its role since [roleSince]; the date binding. */
    private suspend fun ScenarioRig.manualHeaterFollowingHa(
        roleSince: String,
        ended: String,
        cadence: SyncCadence = SyncCadence.DAILY,
    ) {
        graph.assets.upsert(assetRow(heater.value, "Example Heater", seasonMode = SeasonMode.MANUAL))
        role(roleSince)
        val connection = graph.saveHaConnection.run(
            ScenarioRig.ADDRESS, Secret("fictional-token-1"), cadence, NetworkEligibility.HOME_NETWORK_ONLY, HOME_WIFI,
            BackgroundChecks.ON,
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
        ha.helpers[ENDED] = dateHelper(ENDED, ended)
        ha.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        enable(PublishKind.SEASON_SERVICE_DATE, boundary = ENDED)
        settle()
    }

    /** The phone observes a #16 transition on [day] (a season row, as the applier writes it). */
    private suspend fun ScenarioRig.observes(action: SeasonAction, day: String, assetId: AssetId = heater) {
        on(day)
        graph.seasonActivations.insert(SeasonActivation(graph.ids.newId(), assetId, action, day, null, graph.now))
    }

    /** What the observer does after a write: a projection, then a delivery request, awaited. */
    private suspend fun ScenarioRig.settle() {
        graph.publishProjector.reconcileDesired(graph.today)
        graph.deliveryRequester.requestDelivery()
        graph.awaitDispatched()
    }

    /** The periodic reconcile, as WorkManager would run it. */
    private suspend fun ScenarioRig.periodicReconcile() =
        assertEquals(PublishWorkResult.SUCCESS, PublishWorkerBody(graph.publishDispatch).reconcile())

    /** The card's readiness: the date binding's cache judged now ([cadence] the connection's unless given). */
    private suspend fun ScenarioRig.phone(assetId: AssetId = heater, cadence: SyncCadence? = null): Readiness =
        graph.uow.read {
            val asset = graph.assets.get(assetId)!!
            val own = graph.seasonActivations.forAsset(assetId)
            val binding = graph.publishBindings.forAsset(assetId).first { it.kind == PublishKind.SEASON_SERVICE_DATE }
            val boundary = haBoundaryOf(
                graph.publishDeliveries.get(binding.id)!!.boundaryCache, binding, asset.seasonMode,
                graph.seasonSyncBindings.get(assetId)?.enabled == true, cadence ?: graph.haConnections.get()!!.cadence,
                own.maxOfOrNull { it.createdAt }, graph.now,
            )
            seasonReadiness(
                asset, graph.assets.all(), graph.schedules.all(), graph.groups.all(), graph.events.forAsset(assetId), own,
                graph.today, ZoneOffset.UTC, boundary,
            )
        }

    private fun Readiness.ready(): Readiness.Ready = this as? Readiness.Ready ?: throw AssertionError("not ready: $this")

    private fun Readiness.notReady(): Readiness.NotReady = this as? Readiness.NotReady ?: throw AssertionError("ready: $this")

    private val endedUnreachable: suspend (String) -> RemoteRead =
        { if (it == ENDED) RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) else ha.answer(it) }

    // Row 98

    @Test
    fun aDelayedOffAfterANewerServiceAgrees() = rig.run {
        on("2026-04-20")
        manualHeaterFollowingHa(roleSince = "2026-01-01", ended = "2025-05-12")
        graph.seasonActivations.insert(SeasonActivation("activation-1", heater, SeasonAction.START, "2025-10-01", null, dayMillis("2025-10-01")))
        settle()
        assertEquals(INVALID_SERVICE_DATE, serviceDone())

        ha.helpers[ENDED] = dateHelper(ENDED, "2026-05-10") // HA ends the season; the phone is offline and sees nothing
        on("2026-09-18")
        complete("2026-09-18")
        settle()
        assertEquals("2026-09-18", serviceDone())
        val postsBefore = ha.posts.size

        observes(SeasonAction.END, "2026-10-22") // the delayed OFF
        settle()

        val ready = phone().ready()
        assertEquals(LocalDate.parse("2026-09-18"), ready.serviceOn)
        assertEquals(BoundarySource.HA_SEASON_ENDED, ready.boundary.source)
        assertEquals(LocalDate.parse("2026-05-10"), ready.boundary.cutoffOn)
        assertEquals("2026-09-18", serviceDone())
        assertTrue("HA released", released())
        assertEquals("no 1970-01-01 sent after the delayed OFF", postsBefore, ha.posts.size)
    }

    /** Last season's service published, then a good read of last year's season-ended date on 9 May. */
    private suspend fun ScenarioRig.lastSeasonsServiceReadOnMay9(cadence: SyncCadence = SyncCadence.DAILY) {
        on("2025-09-20")
        manualHeaterFollowingHa(roleSince = "2025-01-01", ended = "2025-05-12", cadence = cadence)
        complete("2025-09-20")
        settle()
        assertEquals("2025-09-20", serviceDone())
        on("2026-05-09")
        periodicReconcile()
        assertTrue(ha.log.contains("GET $ENDED"))
        // HA's OFF on 10 May stamps its date and its ON on 11 May follows: the phone misses both (no row).
        haOff("2026-05-10")
    }

    // Row 99

    @Test
    fun aMissedWholeOffOnCycleWithinTheWindow() = rig.run {
        lastSeasonsServiceReadOnMay9()
        val lastGoodRead = graph.now

        // Two days after the last good read the season-ended read fails: the cache still stands.
        on("2026-05-11")
        ha.onRead = endedUnreachable
        periodicReconcile()
        val ready = phone().ready()
        assertEquals(LocalDate.parse("2025-09-20"), ready.serviceOn)
        assertEquals(BoundarySource.HA_SEASON_ENDED, ready.boundary.source)
        assertEquals("P114-44's check date", lastGoodRead, ready.boundary.checkedAt)
        assertEquals("2025-09-20", serviceDone())
        assertFalse("HA's own stamp holds the start", released())

        // The read succeeds.
        ha.onRead = { ha.answer(it) }
        periodicReconcile()
        val notReady = phone().notReady()
        assertEquals(NotReadyReason.NOT_DONE_SINCE_SEASON_END, notReady.reason)
        assertEquals(BoundarySource.HA_SEASON_ENDED, notReady.boundary!!.source)
        assertEquals(INVALID_SERVICE_DATE, serviceDone())
        assertFalse(released())
    }

    // Row 99a

    @Test
    fun pastTheWindowItIsUnverified() = rig.run {
        pastTheWindow(SyncCadence.DAILY, unverifiedOn = "2026-05-17")
    }

    @Test
    fun pastAMonthlyWindowItIsUnverified() = rig.run {
        lastSeasonsServiceReadOnMay9(SyncCadence.MONTHLY)
        ha.onRead = endedUnreachable
        on("2026-05-17")
        periodicReconcile()
        assertEquals("fresh at day 8 at MONTHLY", BoundarySource.HA_SEASON_ENDED, phone().ready().boundary.source)
        pastTheWindow(SyncCadence.MONTHLY, unverifiedOn = "2026-06-09", setUp = false)
    }

    private suspend fun ScenarioRig.pastTheWindow(cadence: SyncCadence, unverifiedOn: String, setUp: Boolean = true) {
        if (setUp) lastSeasonsServiceReadOnMay9(cadence)
        val lastGoodRead = dayMillis("2026-05-09") + 9 * ScenarioRig.HOUR
        ha.onRead = endedUnreachable
        ha.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE) // so the publication is seen to proceed
        ha.log.clear()

        on(unverifiedOn)
        periodicReconcile()
        val ready = phone().ready()
        assertEquals(BoundarySource.HA_UNVERIFIED, ready.boundary.source)
        assertEquals("P114-45's since date", lastGoodRead, ready.boundary.unverifiedSince)
        assertEquals("the later of the cached date and the phone's END", LocalDate.parse("2025-05-12"), ready.boundary.cutoffOn)
        assertTrue("the publication proceeds", ha.log.contains("POST $DATE=2025-09-20"))
        assertFalse(released())

        ha.onRead = { ha.answer(it) }
        periodicReconcile()
        assertEquals(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phone().notReady().reason)
        assertEquals(INVALID_SERVICE_DATE, serviceDone())
    }

    // Row 99b

    @Test
    fun aCalendarAssetNeverUsesHaDate() = rig.run {
        calendar()
        role("2025-09-01")
        complete("2025-11-15")
        connect(background = BackgroundChecks.ON)
        graph.backgroundAllowed = true
        ha.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        ha.helpers[ENDED] = dateHelper(ENDED, "2026-01-20") // fresh, and later than the window's end
        val stored = enable(PublishKind.SEASON_SERVICE_DATE)
        // Test-stored: the command stores none for a CALENDAR asset.
        check(graph.publishBindings.update(stored.copy(boundaryEntityId = ENDED, revision = stored.revision + 1, validatedRevision = stored.revision + 1)))
        settle()
        periodicReconcile()

        assertEquals(BoundarySource.CALENDAR_WINDOW, phone().ready().boundary.source)
        assertEquals("2025-11-15", serviceDone())
        assertFalse("no boundary GET", ha.log.contains("GET $ENDED"))

        // A MANUAL asset whose #16 binding is disabled: the phone's END decides, and nothing is read.
        val boiler = AssetId("example-boiler")
        val boilerService = ScheduleId("example-boiler-service")
        graph.assets.upsert(assetRow(boiler.value, "Example Boiler", seasonMode = SeasonMode.MANUAL))
        graph.uow.write {
            graph.schedules.upsert(
                scheduleOf(boilerService.value, assetId = boiler.value, title = "Example boiler service", anchorOn = "2025-06-01")
                    .copy(role = ScheduleRole.SEASON_START, roleSinceOn = "2025-01-01"),
            )
        }
        graph.seasonSyncBindings.insert(
            SeasonSyncBinding(
                boiler, graph.haConnections.get()!!.id, "input_boolean.example_boiler_in_season", SyncMode.FOLLOW,
                enabled = false, revision = 1, observedState = null, observedChangedAt = null, lastSuccessAt = null,
                lastAttemptAt = null, errorKind = null, errorDetail = null, errorAt = null, appliedAction = null,
                appliedOn = null, appliedAt = null, lastAppliedSource = null, createdAt = graph.now, updatedAt = graph.now,
            ),
        )
        observes(SeasonAction.END, "2025-05-01", boiler)
        on("2026-02-10")
        ha.helpers[BOILER_DATE] = dateHelper(BOILER_DATE, INVALID_SERVICE_DATE)
        ha.helpers[BOILER_ENDED] = dateHelper(BOILER_ENDED, "2026-01-20")
        val boilerDate = graph.enablePublishing.run(
            boiler, PublishKind.SEASON_SERVICE_DATE, BOILER_DATE, fresh(BOILER_DATE, PublishKind.SEASON_SERVICE_DATE, boiler),
            null, graph.installationIdentity.id(),
        )
        check(graph.publishBindings.update(boilerDate.copy(boundaryEntityId = BOILER_ENDED, revision = boilerDate.revision + 1, validatedRevision = boilerDate.revision + 1)))
        ha.log.clear()
        settle()
        periodicReconcile()

        assertEquals(BoundarySource.PHONE_SEASON_END, phone(boiler).notReady().boundary!!.source)
        assertFalse("no boundary GET", ha.log.contains("GET $BOILER_ENDED"))
    }

    // Row 100

    @Test
    fun aPhaseAlreadyMatchingAtResumeReadsTheBoundary() = rig.run {
        lastSeasonsServiceReadOnMay9()
        on("2026-05-11") // no transition observed, the cache still fresh
        assertEquals(BoundarySource.HA_SEASON_ENDED, phone().ready().boundary.source)
        ha.log.clear()

        graph.publishTriggers.resumePass()

        assertTrue("resume read the season-ended helper", ha.log.contains("GET $ENDED"))
        assertEquals(LocalDate.parse("2026-05-10"), phone().notReady().boundary!!.cutoffOn)
        assertEquals(INVALID_SERVICE_DATE, serviceDone())
    }

    // Row 100a — §8's June cold snap

    @Test
    fun aJuneColdSnapAfterTheMayStamp() = rig.run {
        on("2025-09-20")
        stampedHeatingYear = 2024
        manualHeaterFollowingHa(roleSince = "2025-01-01", ended = "2025-05-12")
        complete("2025-09-20")
        settle()
        assertTrue(released())

        haOff("2026-05-15")
        observes(SeasonAction.END, "2026-05-15")
        settle()
        assertEquals(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phone().notReady().reason)
        assertEquals(INVALID_SERVICE_DATE, serviceDone())
        assertFalse(released())

        observes(SeasonAction.START, "2026-06-10") // the cold snap: in_season ON again, HA's override may act
        settle()
        assertEquals(NotReadyReason.NOT_DONE_SINCE_SEASON_END, phone().notReady().reason)

        on("2026-06-12")
        complete("2026-06-12")
        settle()
        assertEquals(LocalDate.parse("2026-06-12"), phone().ready().serviceOn)
        assertTrue(released())

        haOff("2026-06-20") // no new stamp this heating year
        observes(SeasonAction.END, "2026-06-20")
        settle()
        assertEquals("2026-05-15", ha.state(ENDED))
        assertEquals(LocalDate.parse("2026-06-12"), phone().ready().serviceOn)
        assertTrue(released())

        on("2026-09-20")
        complete("2026-09-20")
        settle()
        assertTrue(released())

        haOff("2026-10-05") // an October warm spell
        observes(SeasonAction.END, "2026-10-05")
        settle()
        observes(SeasonAction.START, "2026-10-08")
        settle()
        val ready = phone().ready()
        assertEquals(LocalDate.parse("2026-09-20"), ready.serviceOn)
        assertEquals(BoundarySource.HA_SEASON_ENDED, ready.boundary.source)
        assertEquals("2026-09-20", serviceDone())
        assertTrue(released())
    }

    private companion object {
        const val BOILER_DATE = "input_datetime.example_boiler_service_done"
        const val BOILER_ENDED = "input_datetime.example_boiler_season_ended"
    }
}
