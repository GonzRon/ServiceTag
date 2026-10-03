package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncCommandsHarness.Companion.HEATER
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncHarness.Companion.answerOn
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeStrandsPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #16 (B3c; C17; rows 42–45, 43a, 74) — the commands after a link: the three modes, Stop, Resume with the link's
 * reconciliation, Disconnect, the connection's save, and the derived state. Today is 2026-06-10.
 */
class SeasonSyncCommandsTest {
    private val heater = AssetId(HEATER)
    private val today = "2026-06-10"

    /** A MANUAL heater in season since 1 Mar (or out since 20 May), connected and linked in FOLLOW. */
    private suspend fun linkedHeater(inSeason: Boolean = true): SeasonSyncCommandsHarness =
        SeasonSyncCommandsHarness().apply {
            h.asset()
            h.activation("act-1", SeasonAction.START, "2026-03-01")
            if (!inSeason) h.activation("act-2", SeasonAction.END, "2026-05-20")
            connected()
            linked()
        }

    private fun SeasonSyncCommandsHarness.after(commitsBefore: Int, vararg names: String) {
        assertEquals(names.map { it to commitsBefore + 1 }, scheduler.calls.drop(callsAt), "after the commit")
    }

    private var callsAt = 0

    private fun SeasonSyncCommandsHarness.mark(): Int {
        callsAt = scheduler.calls.size
        return uow.commits
    }

    private suspend fun SeasonSyncCommandsHarness.state(assetId: String = HEATER): SeasonSyncState =
        currentSeasonSyncState(h.binding(assetId), secrets, h.assets, h.raw.transfers)

    // ---- row 42: the modes (AC6) ----

    @Test
    fun forceInWritesAStartWhenOut() = runTest {
        val c = linkedHeater(inSeason = false)
        val commits = c.mark()

        val written = c.setMode.run(heater, SyncMode.FORCE_IN)

        assertEquals(SeasonAction.START to today, c.h.dated().last())
        assertEquals(3, c.h.rows().size, "one row")
        assertEquals(SyncMode.FORCE_IN, written.mode)
        assertEquals(SeasonAction.START, written.appliedAction, "the provenance")
        assertEquals(today, written.appliedOn)
        assertEquals(2, written.revision)
        assertEquals(written, c.h.binding())
        c.after(commits)
    }

    @Test
    fun forceOutWritesAnEndWhenIn() = runTest {
        val c = linkedHeater()

        val written = c.setMode.run(heater, SyncMode.FORCE_OUT)

        assertEquals(listOf(SeasonAction.START to "2026-03-01", SeasonAction.END to today), c.h.dated())
        assertEquals(SeasonAction.END, written.appliedAction)
        assertEquals(SeasonPhase.OUT_OF_SEASON, c.phase())
    }

    @Test
    fun forcingTodaysPhaseWritesNothing() = runTest {
        val c = linkedHeater()

        val forced = c.setMode.run(heater, SyncMode.FORCE_IN)

        assertEquals(listOf(SeasonAction.START to "2026-03-01"), c.h.dated(), "already in season")
        assertNull(forced.appliedAction)
        assertEquals(2, forced.revision, "the mode itself changed")

        val commits = c.mark()
        val again = c.setMode.run(heater, SyncMode.FORCE_IN)

        assertEquals(forced, again, "the stored mode again is a no-op")
        assertEquals(forced, c.h.binding())
        c.after(commits)
    }

    @Test
    fun followWritesNothingBumpsTheRevisionAndAsksForARead() = runTest {
        val c = linkedHeater()
        c.setMode.run(heater, SyncMode.FORCE_OUT)
        c.h.result(answerOn())
        assertEquals(HaSwitchState.ON, c.h.binding().observedState, "HA says on; the forced phase stands")
        assertEquals(SeasonPhase.OUT_OF_SEASON, c.phase())
        val rows = c.h.dated()
        val stored = c.h.binding()
        val commits = c.mark()

        val written = c.setMode.run(heater, SyncMode.FOLLOW)

        assertEquals(rows, c.h.dated(), "no row: the stored answer is never applied")
        assertEquals(SeasonPhase.OUT_OF_SEASON, c.phase(), "the season stays until a fresh read succeeds")
        assertEquals(SyncMode.FOLLOW, written.mode)
        assertEquals(stored.revision + 1, written.revision)
        c.after(commits, "read $HEATER")
    }

    // ---- row 43: stop and resume (AC8) ----

    @Test
    fun stopWritesNoEndAndLeavesManualAtItsPhase() = runTest {
        val c = linkedHeater()
        c.h.asset("other")
        c.linked("other")
        val rows = c.h.dated()
        val stored = c.h.binding()
        var commits = c.mark()

        val stopped = c.stop.run(heater)

        assertEquals(rows, c.h.dated(), "no END")
        assertEquals(SeasonMode.MANUAL, c.h.raw.assets.rows.getValue(HEATER).seasonMode)
        assertEquals(SeasonPhase.IN_SEASON, c.phase())
        assertEquals(stored.copy(enabled = false, revision = 2, updatedAt = c.h.now), stopped, "kept, disabled")
        assertFalse(SeasonSyncGuard(c.h.raw.seasonSyncBindings).isSynced(heater), "the owner's controls return")
        c.after(commits, "ensure")

        commits = c.mark()
        assertEquals(stopped, c.stop.run(heater), "stopping a stopped binding is a no-op")
        c.after(commits)

        commits = c.mark()
        c.stop.run(AssetId("other"))
        assertEquals(rows, c.h.dated())
        c.after(commits, "cancel")
    }

    @Test
    fun resumeOnAManualAssetNeedsATokenAndWritesNoRow() = runTest {
        val c = linkedHeater()
        c.stop.run(heater)
        val connectionId = c.h.binding().connectionId
        c.secrets.delete(connectionId)
        val rows = c.h.dated()

        val e = c.refusesWithNothingWritten<SeasonSyncLinkRefused>("no token") { c.resume.run(heater) }
        assertEquals(SeasonSyncLinkRefusal.NEEDS_TOKEN, e.reason)

        c.secrets.put(connectionId, Secret("fictional-token-2"))
        val commits = c.mark()
        val resumed = c.resume.run(heater)

        assertEquals(rows, c.h.dated(), "a MANUAL asset takes no row")
        assertNull(resumed.switchedFrom)
        assertTrue(resumed.binding.enabled)
        assertEquals(3, resumed.binding.revision)
        assertEquals(SeasonSyncState.ACTIVE, c.state())
        c.after(commits, "ensure", "read $HEATER")
    }

    @Test
    fun eachCommandBumpsTheRevision() = runTest {
        val c = linkedHeater()
        val first = c.h.binding()
        val steps: List<Pair<String, suspend () -> Unit>> = listOf(
            "force in" to { c.setMode.run(heater, SyncMode.FORCE_IN) },
            "entity" to { c.edit.run(heater, "input_boolean.another_example") },
            "stop" to { c.stop.run(heater) },
            "resume" to { c.resume.run(heater) },
            "follow" to { c.setMode.run(heater, SyncMode.FOLLOW) },
            "token" to {
                c.save.run(
                    "http://192.168.0.10:8123", Secret("fictional-token-2"), null,
                    NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi", null,
                )
            },
        )
        var revision = c.h.binding().revision
        for ((what, step) in steps) {
            step()
            assertEquals(revision + 1, c.h.binding().revision, what)
            revision += 1
        }
        assertEquals(1L + steps.size, c.h.binding().revision)
        assertEquals(first.createdAt, c.h.binding().createdAt, "carried through every write")
        assertEquals(first.connectionId, c.h.binding().connectionId, "carried through every write")
    }

    @Test
    fun anEntityEditClearsTheAnswerAndTheErrorAndKeepsTheMode() = runTest {
        val c = linkedHeater()
        c.setMode.run(heater, SyncMode.FORCE_IN)
        c.h.result(answerOn())
        c.h.result(HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null))
        val stored = c.h.binding()
        val commits = c.mark()

        val edited = c.edit.run(heater, "input_boolean.another_example")

        assertEquals(
            stored.copy(
                entityId = "input_boolean.another_example", observedState = null, observedChangedAt = null,
                lastSuccessAt = null, errorKind = null, errorDetail = null, errorAt = null,
                revision = stored.revision + 1, updatedAt = c.h.now,
            ),
            edited,
        )
        c.after(commits, "read $HEATER")
        val e = c.refusesWithNothingWritten<SeasonSyncLinkRefused>("bad entity") { c.edit.run(heater, "a/b.c") }
        assertEquals(SeasonSyncLinkRefusal.BAD_ENTITY_ID, e.reason)
    }

    // ---- row 43a: Resume reconciles (C-4, R16-19) ----

    @Test
    fun stopThenCalendarThenResumeWritesOneSwitchRowAndNoTransition() = runTest {
        val c = linkedHeater()
        c.stop.run(heater)
        c.setSeasonMode.run(heater, SeasonModeCommand(SeasonMode.CALENDAR, "05-01", "09-30"))
        assertEquals(SeasonPhase.IN_SEASON, c.phase(), "in the window")
        val commits = c.mark()

        val resumed = c.resume.run(heater)

        assertEquals(listOf(SeasonAction.START to "2026-03-01", SeasonAction.START to today), c.h.dated(), "one row")
        assertEquals(SeasonMode.MANUAL, c.h.raw.assets.rows.getValue(HEATER).seasonMode)
        assertEquals(SeasonPhase.IN_SEASON, c.phase(), "no transition")
        assertEquals(SeasonMode.CALENDAR, resumed.switchedFrom)
        assertTrue(c.h.binding().enabled)
        c.after(commits, "ensure", "read $HEATER")
    }

    @Test
    fun stopThenYearRoundThenResumeAsksP78After() = runTest {
        val c = linkedHeater(inSeason = false)
        c.stop.run(heater)
        c.setSeasonMode.run(heater, SeasonModeCommand(SeasonMode.YEAR_ROUND))

        val resumed = c.resume.run(heater)

        assertEquals(SeasonMode.YEAR_ROUND, resumed.switchedFrom, "the phone's #78 question follows")
        assertEquals(SeasonAction.START to today, c.h.dated().last())
        assertEquals(SeasonPhase.IN_SEASON, c.phase())
    }

    @Test
    fun aStrandRefusalOnResumeWritesNothingAndStaysStopped() = runTest {
        val c = linkedHeater()
        c.stop.run(heater)
        c.setSeasonMode.run(heater, SeasonModeCommand(SeasonMode.CALENDAR, "10-01", "04-30"))
        c.h.raw.schedules.rows["s-pre"] = scheduleOf(
            id = "s-pre", assetId = HEATER, title = "Before the season", timeInterval = 1,
            timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-01-01", servicePolicy = ServicePolicy.PRE_SERVICE,
            policyOffsetDays = 7,
        )

        c.refusesWithNothingWritten<SeasonModeStrandsPolicy>("strands") { c.resume.run(heater) }

        assertFalse(c.h.binding().enabled, "still stopped")
        assertEquals(SeasonSyncState.STOPPED, c.state())
    }

    /** The controller's carry: the switch runs before the binding is enabled, so its own guard lets it through. */
    @Test
    fun resumeSwitchesBeforeItEnablesItsBinding() = runTest {
        val c = linkedHeater()
        c.stop.run(heater)
        c.setSeasonMode.run(heater, SeasonModeCommand(SeasonMode.YEAR_ROUND))

        c.resume.run(heater)

        assertEquals(SeasonMode.MANUAL, c.h.raw.assets.rows.getValue(HEATER).seasonMode)
        assertTrue(SeasonSyncGuard(c.h.raw.seasonSyncBindings).isSynced(heater))
    }

    // ---- row 44: disconnect (AC8, AC9) ----

    @Test
    fun forgetDeletesTheConnectionItsBindingsAndTheSecretAndWritesNoSeasonRow() = runTest {
        val c = linkedHeater()
        c.h.asset("cold")
        c.linked("cold")
        val connectionId = c.h.binding().connectionId
        val assets = LinkedHashMap(c.h.raw.assets.rows)
        val rows = c.h.raw.activations.rows.toMap()
        val commits = c.mark()

        c.forget.run()

        assertTrue(c.connections.rows.isEmpty())
        assertTrue(c.h.raw.seasonSyncBindings.rows.isEmpty(), "every binding, by the cascade")
        assertFalse(c.secrets.has(connectionId), "the token is gone")
        assertEquals(emptySet(), c.secrets.keys())
        assertEquals(rows, c.h.raw.activations.rows.toMap(), "no END; history kept")
        assertEquals(assets, c.h.raw.assets.rows)
        c.after(commits, "cancel")
    }

    @Test
    fun aChangedAddressOrTokenBumpsEveryRevision() = runTest {
        val c = linkedHeater()
        c.h.asset("cold")
        c.linked("cold")
        c.stop.run(AssetId("cold"))
        val first = c.connections.rows.values.single()
        fun revisions() = c.h.raw.seasonSyncBindings.rows.values.map { it.revision }
        val start = revisions()

        suspend fun save(baseUrl: String, token: Secret?, ssid: String = "ExampleHomeWifi") =
            c.save.run(baseUrl, token, null, NetworkEligibility.HOME_NETWORK_ONLY, ssid, null)

        save("http://192.168.0.10:8123", null)
        assertEquals(start, revisions(), "nothing changed")
        var commits = c.mark()
        save("http://192.168.0.10:8123", Secret("fictional-token-2"))
        assertEquals(start.map { it + 1 }, revisions(), "a new token")
        c.after(commits, "read $HEATER")
        commits = c.mark()
        save("https://ha.example:8123", null)
        assertEquals(start.map { it + 2 }, revisions(), "a new address")
        c.after(commits, "read $HEATER")
        save("https://ha.example:8123", null, ssid = "ExampleCafeWifi")
        assertEquals(start.map { it + 3 }, revisions(), "a new home network")

        val last = c.connections.rows.values.single()
        assertEquals(first.id, last.id, "updated in place: one row, one id")
        assertEquals(first.createdAt, last.createdAt)
        assertEquals("https://ha.example:8123", last.baseUrl)
    }

    // ---- row 45: reauthorization (AC9, I12) ----

    @Test
    fun withoutATokenEveryBindingIsNeedsTokenAndNothingIsRead() = runTest {
        val c = linkedHeater()
        c.h.asset("cold")
        c.linked("cold")
        c.stop.run(AssetId("cold"))
        c.h.asset("shed")
        c.linked("shed")
        c.h.archive.run(AssetId("shed"))
        val states = mapOf(
            HEATER to SeasonSyncState.ACTIVE, "cold" to SeasonSyncState.STOPPED,
            "shed" to SeasonSyncState.NOT_MAINTAINED_HERE,
        )
        assertEquals(states, states.keys.associateWith { c.state(it) })

        c.secrets.delete(c.h.binding().connectionId)

        assertEquals(states.mapValues { SeasonSyncState.NEEDS_TOKEN }, states.keys.associateWith { c.state(it) })
        assertEquals(emptyList(), states.keys.filter { c.state(it) == SeasonSyncState.ACTIVE }, "nothing to read")

        c.secrets.put(c.h.binding().connectionId, Secret("fictional-token-2"))
        c.h.archive.unarchive(AssetId("shed"))
        assertEquals(SeasonSyncState.ACTIVE, c.state("shed"), "maintained here again, with no other step")
    }

    @Test
    fun enteringATokenMakesThemActiveWithAFreshRead() = runTest {
        val c = linkedHeater()
        c.h.asset("cold")
        c.linked("cold")
        val connection = c.connections.rows.values.single()
        c.secrets.delete(connection.id)
        assertEquals(SeasonSyncState.NEEDS_TOKEN, c.state())
        val commits = c.mark()

        c.save.run(
            connection.baseUrl, Secret("fictional-token-2"), connection.cadence, connection.networkEligibility,
            connection.homeNetworkSsid, connection.backgroundChecks,
        )

        assertEquals(listOf(connection.id to commits + 1), c.secrets.puts.takeLast(1), "put after the commit")
        assertEquals(SeasonSyncState.ACTIVE, c.state())
        assertEquals(SeasonSyncState.ACTIVE, c.state("cold"))
        c.after(commits, "read cold", "read $HEATER")
    }

    // ---- row 74: the connection's settings (rev 1.3) ----

    private suspend fun SeasonSyncCommandsHarness.saveRefused(
        reason: SaveHaConnectionRefusal,
        baseUrl: String,
        eligibility: NetworkEligibility,
        ssid: String?,
    ) {
        val e = refusesWithNothingWritten<SaveHaConnectionRefused>("$baseUrl $eligibility '$ssid'") {
            save.run(baseUrl, Secret("fictional-token-1"), null, eligibility, ssid, null)
        }
        assertEquals(reason, e.reason, baseUrl)
        assertFalse(e.message!!.contains("192."), "the message is the code only")
    }

    @Test
    fun anHttpAddressWithAnyNetworkIsHttpNeedsHomeNetwork() = runTest {
        val c = SeasonSyncCommandsHarness()
        c.saveRefused(
            SaveHaConnectionRefusal.HTTP_NEEDS_HOME_NETWORK, "http://192.168.0.10:8123", NetworkEligibility.ANY_NETWORK,
            null,
        )
        for (refused in listOf("http://192.0.2.10:8123", "http://ha.example:8123", "https://ha.example:8123/api")) {
            c.saveRefused(
                SaveHaConnectionRefusal.ENDPOINT_REFUSED, refused, NetworkEligibility.HOME_NETWORK_ONLY,
                "ExampleHomeWifi",
            )
        }
    }

    @Test
    fun homeWithoutACapturedNetworkIsHomeNetworkNotSet() = runTest {
        SeasonSyncCommandsHarness().saveRefused(
            SaveHaConnectionRefusal.HOME_NETWORK_NOT_SET, "http://192.168.0.10:8123",
            NetworkEligibility.HOME_NETWORK_ONLY, null,
        )
    }

    @Test
    fun aBlankCapturedNameIsHomeNetworkNotSet() = runTest {
        val c = SeasonSyncCommandsHarness()
        for (blank in listOf("", "   ", "\t")) {
            c.saveRefused(
                SaveHaConnectionRefusal.HOME_NETWORK_NOT_SET, "https://ha.example:8123",
                NetworkEligibility.HOME_NETWORK_ONLY, blank,
            )
        }
    }

    @Test
    fun aNewConnectionIsDailyAndBackgroundOff() = runTest {
        val c = SeasonSyncCommandsHarness()
        val commits = c.mark()

        val saved = c.save.run(
            "HTTPS://HA.example:8123/", Secret("fictional-token-1"), null, NetworkEligibility.ANY_NETWORK,
            "ExampleHomeWifi", null,
        )

        assertEquals(SyncCadence.DAILY, saved.cadence, "the writer's default, not the model's")
        assertEquals(BackgroundChecks.OFF, saved.backgroundChecks)
        assertEquals(NetworkEligibility.ANY_NETWORK, saved.networkEligibility)
        assertNull(saved.homeNetworkSsid, "no name is kept for any network")
        assertEquals("https://ha.example:8123", saved.baseUrl, "stored canonical")
        assertEquals(saved, c.connections.rows.values.single())
        assertEquals(listOf(saved.id to commits + 1), c.secrets.puts, "the token after the commit")
        c.after(commits)

        val chosen = SeasonSyncCommandsHarness().save.run(
            "http://192.168.0.10:8123", null, SyncCadence.WEEKLY, NetworkEligibility.HOME_NETWORK_ONLY,
            "ExampleHomeWifi", BackgroundChecks.ON,
        )
        assertEquals(SyncCadence.WEEKLY to BackgroundChecks.ON, chosen.cadence to chosen.backgroundChecks)
    }

    @Test
    fun aCadenceChangeCallsEnsureAfterCommitAndAnEligibilityChangeBumpsEveryRevision() = runTest {
        val c = SeasonSyncCommandsHarness()
        c.h.asset()
        c.save.run(
            "https://ha.example:8123", Secret("fictional-token-1"), null, NetworkEligibility.HOME_NETWORK_ONLY,
            "ExampleHomeWifi", null,
        )
        c.linked()
        val revision = c.h.binding().revision

        var commits = c.mark()
        c.save.run(
            "https://ha.example:8123", null, SyncCadence.WEEKLY, NetworkEligibility.HOME_NETWORK_ONLY,
            "ExampleHomeWifi", null,
        )
        assertEquals(SyncCadence.WEEKLY, c.connections.rows.values.single().cadence)
        assertEquals(revision, c.h.binding().revision, "a cadence moves no revision")
        c.after(commits, "ensure")

        commits = c.mark()
        c.save.run("https://ha.example:8123", null, null, NetworkEligibility.ANY_NETWORK, null, null)
        assertEquals(SyncCadence.WEEKLY, c.connections.rows.values.single().cadence, "kept when not given")
        assertEquals(revision + 1, c.h.binding().revision, "a new eligibility")
        c.after(commits, "ensure", "read $HEATER")

        c.stop.run(heater)
        commits = c.mark()
        c.save.run("https://ha.example:8123", null, null, NetworkEligibility.ANY_NETWORK, null, BackgroundChecks.ON)
        c.after(commits, "cancel")
    }
}
