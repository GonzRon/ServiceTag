package com.loosecannon.servicetag.ui.homeassistant

import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.CurrentNetwork
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.seasonsync.ConnectionTestOutcome
import com.loosecannon.servicetag.seasonsync.DefaultNetworkAnswer
import com.loosecannon.servicetag.seasonsync.DefaultNetworkKind
import com.loosecannon.servicetag.seasonsync.JdkAead
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.seasonsync.NetworkPlatform
import com.loosecannon.servicetag.seasonsync.NetworkReading
import com.loosecannon.servicetag.seasonsync.UnconfirmedCause
import com.loosecannon.servicetag.testing.FakeGraph
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #16 rows 66, 66a, 66b (C26, C32) — the Home Assistant screen's model over a Room-backed [FakeGraph]: the save and
 * its refusals, the token never held after Save returns, Test connection's sentences, Disconnect, NEEDS_TOKEN's
 * re-entry, the cadence, and the two permission flows with their recovery. The grants, the network and Test
 * connection's answer are scripted; no socket opens. The sentences are hard-coded here in their ratified words, so a
 * paraphrase in the model fails. Fixtures are fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeAssistantViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private val grants = ScriptedGrants()
    private val tested = mutableListOf<Pair<HaConnection, Secret>>()
    private var answer: ConnectionTestOutcome = ConnectionTestOutcome.Ok

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        // The token store on the test scheduler too, so a model step that reads it settles in advanceUntilIdle.
        val store =
            KeystoreSecretStore(createTempDirectory("no-backup").toFile(), JdkAead(), StandardTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler), secretStore = store)
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    /** The location grants and the API level, as a test sets them; below API 29 background location is implied. */
    private class ScriptedGrants : NetworkPlatform {
        override var apiLevel: Int = 34
        var precise = false
        var approximate = false
        var background = false

        override fun defaultNetworkKind(): DefaultNetworkKind? = null
        override fun connectedWifiName(): String? = null
        override fun listenToDefaultNetwork(onAnswer: (DefaultNetworkAnswer) -> Unit): () -> Unit = {}
        override fun locationOn(): Boolean = true
        override fun preciseLocationGranted(): Boolean = precise
        override fun approximateLocationGranted(): Boolean = approximate || precise
        override fun backgroundLocationGranted(): Boolean = apiLevel < 29 || background
        override fun inForeground(): Boolean = true
    }

    /** The model, settled, and every permission request it has made so far. */
    private class Open(val model: HomeAssistantViewModel, val asked: List<PermissionRequest>) {
        val state: HomeAssistantState get() = model.state.value
    }

    private fun TestScope.open(): Open {
        val model = HomeAssistantViewModel(
            graph.haConnections,
            graph.secretStore,
            graph.saveHaConnection,
            graph.forgetHaConnection,
            { connection, token ->
                tested += connection to token
                answer
            },
            graph.currentNetworkReader,
            grants,
            { "Allow all the time" },
        )
        val asked = mutableListOf<PermissionRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.requests.collect { asked += it } }
        advanceUntilIdle()
        return Open(model, asked)
    }

    private fun TestScope.homeWifiChosen(): Open {
        grants.precise = true
        val screen = open()
        screen.model.chooseHomeWifi()
        return screen
    }

    // Row 66 — the save, the token, Test connection, Disconnect, NEEDS_TOKEN, the cadence.

    @Test fun aRefusedAddressShowsP16_12AndSavesNothing() = runTest {
        val screen = open()
        screen.model.onAddressChange("http://192.0.2.10:8123")
        screen.model.onTokenChange("fictional-token-1")
        screen.model.save()
        advanceUntilIdle()

        assertEquals(
            listOf(
                Notice(
                    "That address is not allowed. Use https:// with a server name or private IPv4 address, or " +
                        "http:// with a private IPv4 address.",
                ),
            ),
            screen.state.notices,
        )
        assertNull(graph.haConnections.get())
        assertTrue(graph.secretStore.keys().isEmpty())
        assertEquals("", screen.model.tokenField.value)
    }

    @Test fun afterSaveTheStateHoldsNoTokenText() = runTest {
        val screen = homeWifiChosen()
        screen.model.onAddressChange("  http://192.168.0.10:8123 ")
        screen.model.captureNetwork()
        screen.model.onTokenChange(" fictional-token-1 ")
        screen.model.save()
        advanceUntilIdle()

        val stored = graph.haConnections.get()!!
        assertEquals("http://192.168.0.10:8123", stored.baseUrl)
        assertEquals(NetworkEligibility.HOME_NETWORK_ONLY, stored.networkEligibility)
        assertEquals("ExampleHomeWifi", stored.homeNetworkSsid)
        assertEquals(Secret("fictional-token-1"), graph.secretStore.get(stored.id))
        assertEquals("", screen.model.tokenField.value)
        assertFalse(screen.state.toString().contains("fictional-token-1"))
        assertTrue(screen.state.tokenPresent)
        assertNull(screen.state.statusLine)
        assertEquals(
            listOf("tokenPresent"),
            HomeAssistantState::class.java.declaredFields.map { it.name }.filter { it.contains("token", true) },
        )
    }

    @Test fun testConnectionShowsEachOutcomesSentence() = runTest {
        val screen = open()
        screen.model.onAddressChange(" https://ha.example:8123 ")
        screen.model.onTokenChange("fictional-token-1")
        val notOnHome = SyncErrorKind.NOT_ON_LOCAL_NETWORK
        val couldNotConfirm = Notice(
            "ServiceTag could not confirm that this phone is on your home Wi-Fi, so it did not contact Home Assistant.",
        )
        val expected = listOf(
            ConnectionTestOutcome.Ok to listOf(Notice("Home Assistant answered. The address and the token work.")),
            failed(SyncErrorKind.ENDPOINT_REFUSED) to listOf(
                Notice(
                    "That address is not allowed. Use https:// with a server name or private IPv4 address, or " +
                        "http:// with a private IPv4 address.",
                ),
            ),
            failed(SyncErrorKind.DENIED) to listOf(
                Notice(
                    "ServiceTag is not allowed to use the network. Allow network access in the app settings, then " +
                        "close ServiceTag and open it again.",
                    NoticeAction.OPEN_APP_SETTINGS,
                ),
            ),
            failed(SyncErrorKind.UNREACHABLE) to
                listOf(Notice("Could not reach Home Assistant. Check that this phone is on the same network.")),
            failed(SyncErrorKind.TIMED_OUT) to listOf(Notice("Home Assistant took too long to answer.")),
            failed(SyncErrorKind.AUTH_REFUSED) to listOf(Notice("Home Assistant refused the access token.")),
            failed(SyncErrorKind.REDIRECTED) to listOf(
                Notice(
                    "Home Assistant answered with a redirect, which ServiceTag does not follow. Enter the address " +
                        "it redirects to.",
                ),
            ),
            failed(SyncErrorKind.HTTP_ERROR, "502") to listOf(Notice("Home Assistant answered with an error (502).")),
            failed(SyncErrorKind.MALFORMED) to listOf(Notice("Home Assistant sent an answer ServiceTag cannot read.")),
            failed(SyncErrorKind.NAME_NOT_LOCAL) to listOf(
                Notice(
                    "That server name does not resolve only to private network addresses, so ServiceTag did not " +
                        "contact it.",
                ),
            ),
            failed(SyncErrorKind.TLS_FAILED) to listOf(Notice("Home Assistant's certificate could not be verified.")),
            failed(notOnHome) to listOf(
                Notice("This phone is not on your home Wi-Fi, so ServiceTag did not contact Home Assistant."),
            ),
            failed(notOnHome, "LOCATION_OFF") to listOf(
                couldNotConfirm,
                Notice("Turn on Location so ServiceTag can read the Wi-Fi network's name, then try again."),
            ),
            failed(notOnHome, "PERMISSION_MISSING") to listOf(
                couldNotConfirm,
                Notice(
                    "ServiceTag no longer has permission to read the Wi-Fi network's name. Allow precise Location " +
                        "again.",
                    NoticeAction.ALLOW_PRECISE_AGAIN,
                ),
            ),
            failed(notOnHome, "APPROXIMATE_ONLY") to listOf(
                couldNotConfirm,
                Notice(
                    "ServiceTag has only approximate Location, which hides the Wi-Fi network's name. Change it to " +
                        "Precise in the app settings.",
                    NoticeAction.OPEN_APP_SETTINGS,
                ),
            ),
            failed(notOnHome, "NOT_FOREGROUND") to listOf(
                couldNotConfirm,
                Notice(
                    "ServiceTag could not read the Wi-Fi network's name in the background. It checks again when you " +
                        "open or return to the app.",
                ),
            ),
            failed(notOnHome, "WIRED") to listOf(
                couldNotConfirm,
                Notice(
                    "On Ethernet, ServiceTag cannot tell which network this is. Connect to your home Wi-Fi, or " +
                        "choose \"Any network\" with an https:// address.",
                ),
            ),
        )
        for ((outcome, lines) in expected) {
            answer = outcome
            screen.model.testConnection()
            advanceUntilIdle()
            assertEquals(outcome.toString(), lines, screen.state.notices)
        }
        val (candidate, token) = tested.last()
        assertEquals("https://ha.example:8123", candidate.baseUrl)
        assertEquals(NetworkEligibility.ANY_NETWORK, candidate.networkEligibility)
        assertEquals(Secret("fictional-token-1"), token)
        assertNull(graph.haConnections.get())

        // No token typed and none stored: P16-10, and nothing is sent.
        screen.model.onTokenChange("")
        screen.model.testConnection()
        advanceUntilIdle()
        assertEquals(
            listOf(Notice("Not connected. Enter the server address and an access token.")),
            screen.state.notices,
        )
        assertEquals(expected.size, tested.size)
    }

    @Test fun testConnectionWithAnEmptyFieldUsesTheStoredTokenForThatRequestOnly() = runTest {
        graph.saveHaConnection.run(
            "https://ha.example:8123", Secret("fictional-token-1"), null, NetworkEligibility.ANY_NETWORK, null, null,
        )
        val screen = open()
        screen.model.testConnection()
        advanceUntilIdle()

        assertEquals(Secret("fictional-token-1"), tested.single().second)
        assertEquals("https://ha.example:8123", tested.single().first.baseUrl)
        assertEquals("", screen.model.tokenField.value)
        assertFalse(screen.state.toString().contains("fictional-token-1"))
    }

    @Test fun disconnectAsksP16_9ThenForgets() = runTest {
        val saved = graph.saveHaConnection.run(
            "https://ha.example:8123", Secret("fictional-token-1"), null, NetworkEligibility.ANY_NETWORK, null, null,
        )
        val screen = open()
        assertTrue(screen.state.connected)

        screen.model.askDisconnect()
        assertEquals(
            "Disconnect Home Assistant? Linked assets stop following it and keep their current season and history. " +
                "The token is deleted from this phone; revoke it in Home Assistant as well.",
            screen.state.disconnectAsk,
        )
        screen.model.dismissDisconnect()
        advanceUntilIdle()
        assertNull(screen.state.disconnectAsk)
        assertNotNull(graph.haConnections.get())

        screen.model.askDisconnect()
        screen.model.confirmDisconnect()
        advanceUntilIdle()
        assertNull(graph.haConnections.get())
        assertFalse(graph.secretStore.has(saved.id))
        assertFalse(screen.state.connected)
        assertEquals("Not connected. Enter the server address and an access token.", screen.state.statusLine)
    }

    @Test fun needsTokenShowsP16_11() = runTest {
        grants.precise = true
        val saved = graph.saveHaConnection.run(
            "http://192.168.0.10:8123",
            Secret("fictional-token-1"),
            SyncCadence.WEEKLY,
            NetworkEligibility.HOME_NETWORK_ONLY,
            "ExampleHomeWifi",
            BackgroundChecks.ON,
        )
        graph.secretStore.delete(saved.id) // a platform restore: the row came back, the key did not
        val screen = open()
        assertEquals("Enter the access token again: this phone no longer has it.", screen.state.statusLine)
        assertFalse(screen.state.tokenPresent)

        // The token-only re-entry resends the stored settings unchanged: the row is updated in place.
        screen.model.onTokenChange("fictional-token-2")
        screen.model.save()
        advanceUntilIdle()
        val after = graph.haConnections.get()!!
        assertEquals(saved.copy(updatedAt = after.updatedAt), after)
        assertEquals(Secret("fictional-token-2"), graph.secretStore.get(saved.id))
        assertTrue(screen.state.tokenPresent)
        assertNull(screen.state.statusLine)
    }

    @Test fun theCadenceOffersExactlyFourAndPreselectsOnceADay() = runTest {
        assertEquals(
            listOf(
                SyncCadence.EVERY_12_HOURS to "Every 12 hours",
                SyncCadence.DAILY to "Once a day",
                SyncCadence.WEEKLY to "Once a week",
                SyncCadence.MONTHLY to "Once a month",
            ),
            HA_CADENCE_CHOICES,
        )
        val screen = open()
        assertEquals(SyncCadence.DAILY, screen.state.cadence)

        screen.model.onAddressChange("https://ha.example:8123")
        screen.model.onTokenChange("fictional-token-1")
        screen.model.chooseCadence(SyncCadence.MONTHLY)
        screen.model.save()
        advanceUntilIdle()
        assertEquals(SyncCadence.MONTHLY, graph.haConnections.get()!!.cadence)
        assertEquals(SyncCadence.MONTHLY, screen.state.cadence)
    }

    // Row 66a — the home-network option, foreground.

    @Test fun anyNetworkAsksForNothing() = runTest {
        val screen = open()
        assertTrue(screen.asked.isEmpty()) // nothing on entry

        screen.model.chooseAnyNetwork()
        screen.model.onAddressChange("https://ha.example:8123")
        screen.model.onTokenChange("fictional-token-1")
        screen.model.save()
        advanceUntilIdle()
        assertTrue(screen.asked.isEmpty())
        assertEquals(NetworkEligibility.ANY_NETWORK, graph.haConnections.get()!!.networkEligibility)
    }

    @Test fun homeNetworkShowsP16_65ThenRequestsPreciseLocation() = runTest {
        val screen = open()
        screen.model.chooseHomeWifi()

        assertEquals(
            listOf(
                Notice(
                    "To check that this phone is on your home Wi-Fi before it sends the access token, ServiceTag " +
                        "needs to read the Wi-Fi network's name. Android allows that only with precise Location " +
                        "permission, so choose Precise. ServiceTag does not use your location, but Android will " +
                        "list it among apps that used location.",
                ),
            ),
            screen.state.notices,
        )
        assertEquals(listOf(PermissionRequest.PRECISE_LOCATION), screen.asked)
        assertFalse(screen.state.homeNetworkChosen)
    }

    @Test fun deniedOrApproximateLeavesItOffWithP16_66() = runTest {
        val refused = listOf(
            Notice(
                "Without precise Location permission, ServiceTag cannot tell which Wi-Fi this is, so ‘Only on this " +
                    "home Wi-Fi’ stays off.",
            ),
        )
        val screen = open()
        screen.model.chooseHomeWifi()
        screen.model.onPermissionAnswered() // denied
        assertFalse(screen.state.homeNetworkChosen)
        assertEquals(refused, screen.state.notices)

        grants.approximate = true
        screen.model.chooseHomeWifi()
        screen.model.onPermissionAnswered() // approximate only
        assertFalse(screen.state.homeNetworkChosen)
        assertEquals(refused, screen.state.notices)
        assertEquals(List(2) { PermissionRequest.PRECISE_LOCATION }, screen.asked)
    }

    @Test fun grantedCapturesTheCurrentWifiAndShowsP16_63() = runTest {
        val screen = open()
        screen.model.chooseHomeWifi()
        grants.precise = true
        screen.model.onPermissionAnswered()
        assertTrue(screen.state.homeNetworkChosen)
        assertTrue(screen.state.notices.isEmpty())

        graph.currentNetwork = NetworkReading(CurrentNetwork.WifiUnnamed, null) // a blank name captures nothing
        screen.model.captureNetwork()
        advanceUntilIdle()
        assertNull(screen.state.homeWifiLine)

        graph.currentNetwork = NetworkReading(CurrentNetwork.Wifi("ExampleHomeWifi"), null)
        screen.model.captureNetwork()
        advanceUntilIdle()
        assertEquals("Home Wi-Fi: ExampleHomeWifi", screen.state.homeWifiLine)
        assertTrue(screen.state.notices.isEmpty())
    }

    @Test fun captureOnEthernetShowsP16_81() = runTest {
        val screen = homeWifiChosen()
        graph.currentNetwork = NetworkReading(CurrentNetwork.Wired, UnconfirmedCause.WIRED)
        screen.model.captureNetwork()
        advanceUntilIdle()

        assertEquals(
            listOf(
                Notice(
                    "On Ethernet, ServiceTag cannot tell which network this is. Connect to your home Wi-Fi, or " +
                        "choose \"Any network\" with an https:// address.",
                ),
            ),
            screen.state.notices,
        )
        assertNull(screen.state.homeWifi)
    }

    @Test fun captureOnMobileDataOrNoneShowsP16_82() = runTest {
        val screen = homeWifiChosen()
        for (network in listOf(CurrentNetwork.Other, CurrentNetwork.None)) {
            graph.currentNetwork = NetworkReading(network, null)
            screen.model.captureNetwork()
            advanceUntilIdle()
            assertEquals(listOf(Notice("Connect this phone to your home Wi-Fi, then try again.")), screen.state.notices)
            assertNull(screen.state.homeWifi)
        }
    }

    @Test fun locationOffAtCaptureShowsP16_68() = runTest {
        val screen = homeWifiChosen()
        graph.currentNetwork = NetworkReading(CurrentNetwork.WifiUnnamed, UnconfirmedCause.LOCATION_OFF)
        screen.model.captureNetwork()
        advanceUntilIdle()

        assertEquals(
            listOf(Notice("Turn on Location so ServiceTag can read the Wi-Fi network's name, then try again.")),
            screen.state.notices,
        )
        assertNull(screen.state.homeWifi)
    }

    @Test fun httpWithAnyNetworkShowsP16_67() = runTest {
        val screen = open()
        screen.model.onAddressChange("http://192.168.0.10:8123")
        screen.model.onTokenChange("fictional-token-1")
        screen.model.save()
        advanceUntilIdle()

        assertEquals(
            listOf(
                Notice("An http:// address needs ‘Only on this home Wi-Fi’. Choose it, or use an https:// address."),
            ),
            screen.state.notices,
        )
        assertNull(graph.haConnections.get())
        assertTrue(screen.asked.isEmpty())
    }

    @Test fun homeWithoutACapturedNameRaisesTheCaptureButtonAndSavesNothing() = runTest {
        val screen = homeWifiChosen()
        screen.model.onAddressChange("http://192.168.0.10:8123")
        screen.model.onTokenChange("fictional-token-1")
        screen.model.save()
        advanceUntilIdle()

        assertTrue(screen.state.promptCapture)
        assertTrue(screen.state.notices.isEmpty())
        assertNull(graph.haConnections.get())
    }

    // Row 66b — background checks.

    @Test fun backgroundChecksAppearOnlyUnderHomeOnlyAndStartOff() = runTest {
        val screen = homeWifiChosen()
        assertTrue(screen.state.homeNetworkChosen)
        assertEquals(BackgroundChecks.OFF, screen.state.backgroundChecks)
        assertEquals(listOf(Notice(BACKGROUND_OFF_LINE)), screen.state.locationLines)

        screen.model.chooseAnyNetwork()
        assertFalse(screen.state.homeNetworkChosen)
        assertTrue(screen.state.locationLines.isEmpty())
    }

    @Test fun turningOnAfterTheForegroundGrantShowsP16_73ThenRequestsBackgroundLocation() = runTest {
        val screen = homeWifiChosen()
        screen.model.chooseBackgroundChecks(BackgroundChecks.ON)

        assertEquals(
            "To check in the background, ServiceTag must read the Wi-Fi network's name while the app is closed. " +
                "Android allows that only when Location is set to \"Allow all the time\". ServiceTag reads only the " +
                "network's name, never where you are, and Android will remind you that it has this access.",
            screen.state.backgroundAsk,
        )
        assertTrue(screen.asked.isEmpty())

        screen.model.acceptBackgroundAsk()
        assertEquals(listOf(PermissionRequest.APP_SETTINGS), screen.asked)
        assertNull(screen.state.backgroundAsk)

        grants.background = true
        screen.model.onResumed()
        assertEquals(
            listOf(Notice("ServiceTag also checks in the background while this phone is on your home Wi-Fi.")),
            screen.state.locationLines,
        )
    }

    @Test fun api30AndUpOpensAppSettings() = runTest {
        for (api in listOf(30, 34)) {
            grants.apiLevel = api
            val screen = homeWifiChosen()
            screen.model.chooseBackgroundChecks(BackgroundChecks.ON)
            assertNotNull(screen.state.backgroundAsk)
            screen.model.acceptBackgroundAsk()
            assertEquals(listOf(PermissionRequest.APP_SETTINGS), screen.asked)
        }

        // API 29 asks in the system dialog, which names the option itself.
        grants.apiLevel = 29
        val dialog = homeWifiChosen()
        dialog.model.chooseBackgroundChecks(BackgroundChecks.ON)
        assertNull(dialog.state.backgroundAsk)
        assertEquals(listOf(PermissionRequest.BACKGROUND_LOCATION), dialog.asked)

        // API 26–28 need nothing more.
        grants.apiLevel = 28
        val older = homeWifiChosen()
        older.model.chooseBackgroundChecks(BackgroundChecks.ON)
        assertNull(older.state.backgroundAsk)
        assertTrue(older.asked.isEmpty())
        assertEquals(
            listOf(Notice("ServiceTag also checks in the background while this phone is on your home Wi-Fi.")),
            older.state.locationLines,
        )
    }

    @Test fun declineOrRefusalShowsP16_75AndRunsForegroundOnly() = runTest {
        val paused = listOf(
            Notice(BACKGROUND_PAUSED_LINE, NoticeAction.ALLOW_BACKGROUND_AGAIN),
            Notice(BACKGROUND_OFF_LINE),
        )
        val screen = homeWifiChosen()
        screen.model.chooseBackgroundChecks(BackgroundChecks.ON)
        screen.model.declineBackgroundAsk()
        assertNull(screen.state.backgroundAsk)
        assertEquals(BackgroundChecks.ON, screen.state.backgroundChecks)
        assertEquals(paused, screen.state.locationLines)

        screen.model.act(NoticeAction.ALLOW_BACKGROUND_AGAIN)
        screen.model.acceptBackgroundAsk()
        screen.model.onResumed() // back from the settings page without the grant
        assertEquals(paused, screen.state.locationLines)
    }

    @Test fun allowAgainRerunsTheMatchingFlow() = runTest {
        graph.saveHaConnection.run(
            "http://192.168.0.10:8123", Secret("fictional-token-1"), null,
            NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi", null,
        )
        val screen = open() // precise location withdrawn since the save
        assertEquals(
            Notice(
                "ServiceTag no longer has permission to read the Wi-Fi network's name. Allow precise Location again.",
                NoticeAction.ALLOW_PRECISE_AGAIN,
            ),
            screen.state.locationLines.first(),
        )
        screen.model.act(NoticeAction.ALLOW_PRECISE_AGAIN)
        assertEquals(listOf(PermissionRequest.PRECISE_LOCATION), screen.asked)

        grants.approximate = true
        screen.model.onPermissionAnswered()
        assertEquals(
            Notice(
                "ServiceTag has only approximate Location, which hides the Wi-Fi network's name. Change it to " +
                    "Precise in the app settings.",
                NoticeAction.OPEN_APP_SETTINGS,
            ),
            screen.state.locationLines.first(),
        )

        grants.precise = true
        screen.model.onResumed()
        assertEquals(listOf(Notice(BACKGROUND_OFF_LINE)), screen.state.locationLines)
        screen.model.chooseBackgroundChecks(BackgroundChecks.ON)
        screen.model.declineBackgroundAsk()
        assertEquals(
            Notice(BACKGROUND_PAUSED_LINE, NoticeAction.ALLOW_BACKGROUND_AGAIN),
            screen.state.locationLines.first(),
        )
        screen.model.act(NoticeAction.ALLOW_BACKGROUND_AGAIN)
        assertNotNull(screen.state.backgroundAsk)
    }

    @Test fun offNeverAsksForBackgroundLocation() = runTest {
        for (api in listOf(29, 34)) {
            grants.apiLevel = api
            val screen = homeWifiChosen()
            screen.model.chooseBackgroundChecks(BackgroundChecks.OFF)
            assertNull(screen.state.backgroundAsk)
            assertTrue(screen.asked.isEmpty())
            assertEquals(listOf(Notice(BACKGROUND_OFF_LINE)), screen.state.locationLines)
        }
    }

    private fun failed(kind: SyncErrorKind, detail: String? = null) = ConnectionTestOutcome.Failed(kind, detail)

    private companion object {
        const val BACKGROUND_OFF_LINE =
            "With background checks off, ServiceTag checks when you open or return to the app after the chosen " +
                "interval, and when you tap Sync now. It still needs precise Location while the app is in use."
        const val BACKGROUND_PAUSED_LINE =
            "Background checks are paused because Android does not let ServiceTag read the Wi-Fi network's name in " +
                "the background. ServiceTag checks when you open or return to the app and when you tap Sync now."
    }
}
