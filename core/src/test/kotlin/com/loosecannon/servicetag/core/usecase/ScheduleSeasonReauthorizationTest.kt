package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SaveHaConnection
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRecorded
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncScheduler
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #125 (U-C5; the H3 review's ruling 2b) — the connection's save serves the schedule bindings as #16's serves the
 * asset bindings. A new token, address, network or Wi-Fi name moves every schedule binding's revision, so a read built
 * before it is dropped, and each enabled one is read afresh; a cadence change alone moves none, and the work rule
 * counts an enabled schedule binding. Every name, address and token is fictional.
 */
class ScheduleSeasonReauthorizationTest {

    private val h = ScheduleSeasonHarness()
    private val switch = "input_boolean.example_heater_winterize_season"
    private val opening = ScheduleId("s-open")

    private val requested = mutableListOf<ScheduleId>()
    private val calls = mutableListOf<String>()
    private val scheduler = object : SeasonSyncScheduler {
        override suspend fun ensure() {
            calls += "ensure"
        }

        override suspend fun cancel() {
            calls += "cancel"
        }

        override suspend fun requestFreshRead(assetId: AssetId) {
            calls += "read ${assetId.value}"
        }
    }

    private val save = SaveHaConnection(
        h.raw.haConnections, h.raw.seasonSyncBindings, h.secrets, scheduler, h.uow, h.ids, h.clock, h.outbound,
        scheduleBindings = h.bindings, requestScheduleRead = { requested += it },
    )

    private suspend fun connect(token: String?, cadence: SyncCadence? = null, ssid: String = "ExampleHomeWifi") =
        save.run(
            "http://192.168.0.10:8123", token?.let(::Secret), cadence, NetworkEligibility.HOME_NETWORK_ONLY, ssid,
            BackgroundChecks.OFF,
        )

    private suspend fun linked() {
        h.asset()
        h.schedule(opening.value)
        connect("fictional-token-1")
        h.link.run(opening, switch)
        requested.clear()
        calls.clear()
    }

    @Test
    fun aNewTokenMovesTheScheduleBindingsRevisionDropsTheOldReadAndReadsAfresh() = runTest {
        linked()
        assertEquals(1L, h.bindings.get(opening)!!.revision)

        connect("fictional-token-2")

        assertEquals(2L, h.bindings.get(opening)!!.revision)
        assertEquals(listOf(opening), requested)
        val late = h.applier.run(opening, 1L, h.now, h.now, HaReadOutcome.Observed(HaSwitchState.ON, null), switch)
        assertEquals(ScheduleSeasonRecorded.Dropped, late, "a read built before the change is dropped")
        assertTrue(h.rows.forSchedule(opening).isEmpty())
    }

    @Test
    fun aNewWifiNameIsAReauthorizationTooAndACadenceChangeAloneIsNot() = runTest {
        linked()

        connect(token = null, ssid = "ExampleOtherWifi")
        assertEquals(2L, h.bindings.get(opening)!!.revision)
        assertEquals(listOf(opening), requested)

        requested.clear()
        connect(token = null, cadence = SyncCadence.WEEKLY, ssid = "ExampleOtherWifi")
        assertEquals(2L, h.bindings.get(opening)!!.revision, "a cadence change moves no revision")
        assertEquals(emptyList(), requested)
        assertEquals(listOf("ensure"), calls, "an enabled schedule binding keeps the work")
    }
}
