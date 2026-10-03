package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.seasonsync.JdkAead
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.seasonsync.UnconfirmedCause
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.ui.homeassistant.Notice
import com.loosecannon.servicetag.ui.homeassistant.NoticeAction
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #16 row 67 (C27, C33(4)) — the season card's Home Assistant block, its model over a Room-backed [FakeGraph] with the
 * shipped use cases and runner: the separate lines, the stale marker by the cadence, the mode control in place of
 * Start and End, the block on every season mode, Sync now, every error kind's sentence, the unconfirmed network's
 * remedy and the provenance line. Home Assistant is the scripted reader; no socket opens. The sentences are written
 * out in their ratified words, so a paraphrase in the model fails. Fixtures are fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonSyncBlockViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    private val heater = AssetId("example-heater")
    private val helper = "input_boolean.example_heater_in_season"

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val store =
            KeystoreSecretStore(createTempDirectory("no-backup").toFile(), JdkAead(), StandardTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler), secretStore = store)
        graph.on("2026-02-10")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun FakeGraph.on(date: String, hour: Int = 9) {
        today = LocalDate.parse(date)
        now = dayMillis(date) + hour * HOUR
    }

    private fun answers(state: HaSwitchState?, changed: String? = null) {
        graph.haStateReader.answer = {
            if (state == null) HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null) else HaReadOutcome.Observed(state, changed)
        }
    }

    private suspend fun connect(cadence: SyncCadence = SyncCadence.DAILY) {
        graph.saveHaConnection.run(
            "https://ha.example:8123", Secret("fictional-token-1"), cadence, NetworkEligibility.ANY_NETWORK, null, null,
        )
    }

    /** A MANUAL asset, out of season, linked to [entity]; the link's own read has landed. */
    private suspend fun TestScope.linked(id: AssetId = heater, entity: String = helper): AssetId {
        graph.assets.upsert(assetRow(id.value, name = "Example Heater", seasonMode = SeasonMode.MANUAL))
        graph.linkSeasonSync.run(id, entity)
        advanceUntilIdle()
        return id
    }

    private fun TestScope.open(id: AssetId = heater): SeasonSyncBlockViewModel {
        val model = SeasonSyncBlockViewModel(
            id, graph.seasonSyncBindings, graph.haConnections, graph.secretStore, graph.assets, graph.transferRecords,
            graph.setSeasonSyncMode, graph.stopSeasonSync, graph.resumeSeasonSync,
            { graph.seasonSyncRunner.syncNow(it) }, graph.seasonSyncBackgroundAllowed, graph.clock, { ZoneOffset.UTC },
        )
        advanceUntilIdle()
        return model
    }

    private fun TestScope.act(step: () -> Unit) {
        step()
        advanceUntilIdle()
    }

    private val SeasonSyncBlockViewModel.now: SeasonSyncBlockState get() = state.value

    private suspend fun rows() = graph.seasonActivations.forAsset(heater).map { it.action to it.occurredOn }

    @Test fun effectiveSeasonSourceLastSuccessAndLatestErrorAreSeparateLines() = runTest {
        connect()
        answers(HaSwitchState.ON, changed = "2026-02-09T18:30:00+00:00")
        linked()
        assertEquals(SeasonPhase.IN_SEASON, graph.getAssetSeason.run(heater).phase)
        val block = open()
        graph.now += HOUR
        answers(null)
        act { block.syncNow() }
        val state = block.now
        assertEquals("Follows input_boolean.example_heater_in_season", state.sourceLine)
        assertEquals("Last successful check 2026-02-10 09:00", state.lastSuccessLine)
        assertEquals(
            listOf(Notice("Could not reach Home Assistant. Check that this phone is on the same network.")),
            state.errorLines,
        )
        assertEquals("Changed in Home Assistant 2026-02-09 18:30", state.haChangedLine)
        assertEquals("Started from Home Assistant on 10 Feb 2026", state.appliedLine)
        assertNull("checked within the day", state.staleLine)
        assertNull(state.stateLine)
        assertEquals("an error leaves the season", SeasonPhase.IN_SEASON, graph.getAssetSeason.run(heater).phase)
    }

    @Test fun staleFollowsTheCadence() = runTest {
        connect()
        answers(null)
        linked()
        val block = open()
        assertEquals("No reading from Home Assistant yet.", block.now.lastSuccessLine)
        assertEquals("Not checked successfully within the chosen interval.", block.now.staleLine)
        answers(HaSwitchState.OFF)
        act { block.syncNow() }
        assertEquals("Last successful check 2026-02-10 09:00", block.now.lastSuccessLine)
        assertNull(block.now.staleLine)
        graph.now += 23 * HOUR
        answers(null)
        act { block.syncNow() }
        assertNull("23 hours of a daily cadence", block.now.staleLine)
        graph.now += HOUR
        act { block.syncNow() }
        assertEquals("a failed attempt does not count", "Not checked successfully within the chosen interval.", block.now.staleLine)
        assertEquals("Last successful check 2026-02-10 09:00", block.now.lastSuccessLine)
        connect(SyncCadence.WEEKLY)
        act { block.refresh() }
        assertNull("a day of a weekly cadence", block.now.staleLine)
    }

    @Test fun theModeControlReplacesStartAndEnd() = runTest {
        graph.assets.upsert(assetRow(heater.value, name = "Example Heater", seasonMode = SeasonMode.MANUAL))
        val block = open()
        assertFalse("no connection, nothing added", block.now.offersLink)
        connect()
        act { block.refresh() }
        assertEquals(SeasonSyncBlockKind.NONE, block.now.kind)
        assertTrue(block.now.offersLink)
        assertFalse("an unlinked asset keeps Start and End", block.now.hidesStartAndEnd)
        graph.linkSeasonSync.run(heater, helper)
        advanceUntilIdle()
        assertTrue(block.now.hidesStartAndEnd)
        assertTrue(block.now.offersControls)
        assertFalse(block.now.offersLink)
        assertEquals(SyncMode.FOLLOW, block.now.mode)
        act { block.chooseMode(SyncMode.FORCE_IN) }
        assertEquals(SyncMode.FORCE_IN, block.now.mode)
        assertEquals(
            "Forced in season. input_boolean.example_heater_in_season is still checked, but it does not change the " +
                "season until you choose Follow Home Assistant.",
            block.now.sourceLine,
        )
        assertEquals(listOf(SeasonAction.START to "2026-02-10"), rows())
        act { block.stopSyncing() }
        assertEquals(SeasonSyncBlockKind.STOPPED, block.now.kind)
        assertFalse("a stopped binding gives Start and End back", block.now.hidesStartAndEnd)
        assertFalse(block.now.offersControls)
        assertEquals(listOf(SeasonAction.START to "2026-02-10"), rows())
        act { block.resumeSyncing() }
        assertEquals(SeasonSyncBlockKind.ENABLED, block.now.kind)
        assertTrue(block.now.hidesStartAndEnd)
    }

    @Test fun theBlockIsOnEveryModesBranchAndAStoppedOneOffersResume() = runTest {
        connect()
        val modes = mapOf(
            SeasonMode.MANUAL to null,
            SeasonMode.CALENDAR to SeasonModeCommand(SeasonMode.CALENDAR, "10-01", "04-30"),
            SeasonMode.YEAR_ROUND to SeasonModeCommand(SeasonMode.YEAR_ROUND),
        )
        for ((mode, command) in modes) {
            val id = linked(AssetId("example-heater-${mode.name.lowercase()}"))
            graph.stopSeasonSync.run(id)
            command?.let { graph.setSeasonMode.run(id, it) }
            assertEquals(mode, graph.assets.get(id)?.seasonMode)
            val state = open(id).now
            assertEquals(mode.name, SeasonSyncBlockKind.STOPPED, state.kind)
            assertEquals(mode.name, "Syncing is stopped. Home Assistant no longer changes this asset's season.", state.stoppedLine)
            assertTrue(mode.name, state.offersResume)
            assertEquals(mode.name, mode != SeasonMode.MANUAL, state.resumeOpensSheet)
        }
        val manual = open(AssetId("example-heater-manual"))
        act { manual.resumeSyncing() }
        assertEquals(SeasonSyncBlockKind.ENABLED, manual.now.kind)
        assertNull(manual.now.stoppedLine)
    }

    @Test fun syncNowCallsTheRunnerForThisAsset() = runTest {
        connect()
        linked()
        linked(AssetId("example-boiler"), "input_boolean.example_boiler_in_season")
        graph.haStateReader.reads.clear()
        val block = open()
        act { block.syncNow() }
        assertEquals(listOf(helper), graph.haStateReader.reads.map { it.entityId })
        assertEquals(Secret("fictional-token-1"), graph.haStateReader.reads.single().token)
    }

    @Test fun everySyncErrorKindHasItsSentence() {
        val expected: Map<SyncErrorKind, Pair<String?, String>> = mapOf(
            SyncErrorKind.ENDPOINT_REFUSED to (null to "That address is not allowed. Use https:// with a server name or private IPv4 address, or http:// with a private IPv4 address."),
            SyncErrorKind.DENIED to (null to "ServiceTag is not allowed to use the network. Allow network access in the app settings, then close ServiceTag and open it again."),
            SyncErrorKind.UNREACHABLE to (null to "Could not reach Home Assistant. Check that this phone is on the same network."),
            SyncErrorKind.TIMED_OUT to (null to "Home Assistant took too long to answer."),
            SyncErrorKind.AUTH_REFUSED to (null to "Home Assistant refused the access token."),
            SyncErrorKind.ENTITY_NOT_FOUND to (null to "Home Assistant has no entity input_boolean.example_heater_in_season."),
            SyncErrorKind.REDIRECTED to (null to "Home Assistant answered with a redirect, which ServiceTag does not follow. Enter the address it redirects to."),
            SyncErrorKind.HTTP_ERROR to ("503" to "Home Assistant answered with an error (503)."),
            SyncErrorKind.MALFORMED to (null to "Home Assistant sent an answer ServiceTag cannot read."),
            SyncErrorKind.UNSUPPORTED_STATE to ("unavailable" to "Home Assistant reports \"unavailable\" for input_boolean.example_heater_in_season, which is neither on nor off, so the season is unchanged."),
            SyncErrorKind.NEEDS_TOKEN to (null to "Enter the access token again: this phone no longer has it."),
            SyncErrorKind.NOT_MAINTAINED_HERE to (null to "This asset is no longer maintained here, so Home Assistant no longer changes its season."),
            SyncErrorKind.NOT_MANUAL to (null to "This asset's season is no longer started and ended by hand, so Home Assistant cannot change it. Choose Stop syncing, then Resume syncing."),
            SyncErrorKind.DATE_BEFORE_HISTORY to (null to "This phone's date is before the latest season entry, so the season stays as it is until the date catches up."),
            SyncErrorKind.NOT_ON_LOCAL_NETWORK to (null to "This phone is not on your home Wi-Fi, so ServiceTag did not contact Home Assistant."),
            SyncErrorKind.NAME_NOT_LOCAL to (null to "That server name does not resolve only to private network addresses, so ServiceTag did not contact it."),
            SyncErrorKind.TLS_FAILED to (null to "Home Assistant's certificate could not be verified."),
        )
        assertEquals(SyncErrorKind.entries.toSet(), expected.keys)
        for ((kind, case) in expected) {
            val (detail, sentence) = case
            assertEquals(kind.name, listOf(sentence), seasonSyncErrorNotices(kind, detail, helper).map { it.text })
        }
        assertEquals(
            NoticeAction.OPEN_APP_SETTINGS,
            seasonSyncErrorNotices(SyncErrorKind.DENIED, null, helper).single().action,
        )
    }

    @Test fun unconfirmedShowsP16_77AndTheCausesRemedy() = runTest {
        val unconfirmed = "ServiceTag could not confirm that this phone is on your home Wi-Fi, so it did not contact Home Assistant."
        val remedies = mapOf(
            UnconfirmedCause.LOCATION_OFF to Notice("Turn on Location so ServiceTag can read the Wi-Fi network's name, then try again."),
            UnconfirmedCause.PERMISSION_MISSING to Notice(
                "ServiceTag no longer has permission to read the Wi-Fi network's name. Allow precise Location again.",
                NoticeAction.ALLOW_PRECISE_AGAIN,
            ),
            UnconfirmedCause.APPROXIMATE_ONLY to Notice(
                "ServiceTag has only approximate Location, which hides the Wi-Fi network's name. Change it to Precise in the app settings.",
                NoticeAction.OPEN_APP_SETTINGS,
            ),
            UnconfirmedCause.NOT_FOREGROUND to Notice("ServiceTag could not read the Wi-Fi network's name in the background. It checks again when you open or return to the app."),
            UnconfirmedCause.WIRED to Notice("On Ethernet, ServiceTag cannot tell which network this is. Connect to your home Wi-Fi, or choose \"Any network\" with an https:// address."),
        )
        assertEquals(UnconfirmedCause.entries.toSet(), remedies.keys)
        for ((cause, remedy) in remedies) {
            assertEquals(
                cause.name,
                listOf(Notice(unconfirmed), remedy),
                seasonSyncErrorNotices(SyncErrorKind.NOT_ON_LOCAL_NETWORK, cause.name, helper),
            )
        }
        connect()
        answers(HaSwitchState.OFF)
        linked()
        val block = open()
        graph.haStateReader.answer = { HaReadOutcome.NoDecision(SyncErrorKind.NOT_ON_LOCAL_NETWORK, "PERMISSION_MISSING") }
        act { block.syncNow() }
        assertEquals(listOf(Notice(unconfirmed), remedies.getValue(UnconfirmedCause.PERMISSION_MISSING)), block.now.errorLines)
        assertEquals("Last successful check 2026-02-10 09:00", block.now.lastSuccessLine)
    }

    @Test fun theProvenanceLineIsC33s() = runTest {
        connect()
        answers(null)
        linked()
        val block = open()
        assertNull("nothing applied, no line", block.now.appliedLine)
        act { block.chooseMode(SyncMode.FORCE_IN) }
        assertEquals("a forced START", "Forced in season on 10 Feb 2026", block.now.appliedLine)
        graph.on("2026-02-11")
        act { block.chooseMode(SyncMode.FORCE_OUT) }
        assertEquals("a forced END", "Forced out of season on 11 Feb 2026", block.now.appliedLine)
        graph.on("2026-02-12")
        answers(HaSwitchState.ON)
        act { block.chooseMode(SyncMode.FOLLOW) }
        assertEquals("Started from Home Assistant on 12 Feb 2026", block.now.appliedLine)
        graph.on("2026-02-13")
        answers(HaSwitchState.OFF)
        act { block.syncNow() }
        assertEquals("Ended from Home Assistant on 13 Feb 2026", block.now.appliedLine)
        assertEquals(
            listOf(
                SeasonAction.START to "2026-02-10",
                SeasonAction.END to "2026-02-11",
                SeasonAction.START to "2026-02-12",
                SeasonAction.END to "2026-02-13",
            ),
            rows(),
        )
    }

    @Test fun pausedBackgroundChecksShowP16_75WithAllowAgain() = runTest {
        graph.saveHaConnection.run(
            "http://192.168.0.10:8123", Secret("fictional-token-1"), SyncCadence.DAILY,
            NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi", BackgroundChecks.ON,
        )
        linked()
        val block = open()
        assertEquals(
            Notice(
                "Background checks are paused because Android does not let ServiceTag read the Wi-Fi network's name " +
                    "in the background. ServiceTag checks when you open or return to the app and when you tap Sync now.",
                NoticeAction.ALLOW_BACKGROUND_AGAIN,
            ),
            block.now.pausedLine,
        )
        graph.backgroundAllowed = true
        act { block.refresh() }
        assertNull(block.now.pausedLine)
    }

    @Test fun needsTokenAndNotMaintainedHereShowTheirLines() = runTest {
        connect()
        linked()
        val block = open()
        val connectionId = checkNotNull(graph.haConnections.get()).id
        graph.secretStore.delete(connectionId)
        act { block.refresh() }
        assertEquals("Enter the access token again: this phone no longer has it.", block.now.stateLine)
        graph.secretStore.put(connectionId, Secret("fictional-token-1"))
        graph.archiveAsset.run(heater)
        advanceUntilIdle()
        assertEquals(
            "This asset is no longer maintained here, so Home Assistant no longer changes its season.",
            block.now.stateLine,
        )
        assertFalse("nothing to check or force while inert", block.now.offersControls)
        assertTrue(block.now.offersStop)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
