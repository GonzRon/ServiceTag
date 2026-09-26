package com.loosecannon.servicetag.ui.api

import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.system.ErrnoException
import android.system.OsConstants
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.api.BindErrno
import com.loosecannon.servicetag.api.DEVELOPER_API_PORT
import com.loosecannon.servicetag.api.ListenerState
import com.loosecannon.servicetag.api.StartOutcome
import com.loosecannon.servicetag.api.bindErrnoOf
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.components.appDetails
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import javax.net.ServerSocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long an assertion waits for the lifecycle effect to bind or release the port. */
private const val SETTLE_MILLIS = 5_000L

/** 1.1.0's S6, as ratified. */
private const val S6 = "The Developer API could not start. Leave this screen and open it again."

/** Phase 1A's P1A-1 and P1A-2, as ratified on 2026-09-25. */
private const val P1A_1 =
    "ServiceTag is not allowed to use the network, so the Developer API cannot start. " +
        "Allow network access in the app settings, then close ServiceTag and open it again."
private const val P1A_2 = "Open app settings"

/**
 * 1.1.0 (#46) — the half of this feature that only a device can prove: that the listener really
 * comes up on 17337 when the screen is shown, that the code on the screen is the code it accepts,
 * and that it is **gone** when the screen goes. Phase 1A adds what the screen says about a listener
 * that is not up (#66, #51 b), and that the pairing code stays out of Recents and screenshots for
 * as long as the screen is composed (#51 a).
 *
 * **What is proved elsewhere, and is not repeated here.** The parser, the ceilings, the router, the
 * handlers, the 401 and the start-outcome table are `:app`'s JVM suite (`HttpWireTest`,
 * `ApiRouterTest`, `LoopbackApiServerTest`), on a real socket, with no emulator. What that suite
 * cannot reach is the `INTERNET` permission actually being granted, the platform's own errno on a
 * failed bind, `LifecycleResumeEffect` actually running, the activity window's flags, and a
 * `ViewModel` scoped to a nav entry actually minting one code per visit. That is this file.
 *
 * **A denial is injected, never provoked.** The stock emulator defines INTERNET as a normal
 * permission, so nothing here can revoke it, and nothing here tries: the denial reaches the screen
 * through the model's bind seam and a permission probe that reads as denied.
 *
 * **How the code gets from the screen to the assertion.** The model is built here and seeded into
 * the `ViewModelStore` this test provides, under the key `DeveloperApiScreen` resolves with —
 * `viewModel(key = "developer-api")` returns the stored instance rather than calling its
 * initializer. So `model.pairingCode` is the screen's own code, and the test both types it at the
 * socket and asserts it is on screen. (Task 6's end-to-end proof did it the other way round,
 * reading the code off the screen from the workstation, a path #62 retired.)
 *
 * The production port is used where the listener is meant to come up: it is what the workstation
 * forwards to, and an emulator running one instrumented suite has nothing else on it.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class DeveloperApiListenerTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun freshInstall() = clearInstall()

    /** Sends [raw] verbatim to the listener and reads the whole answer back. */
    private fun speak(raw: String): String = Socket("127.0.0.1", DEVELOPER_API_PORT).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().apply {
            write(raw.toByteArray())
            flush()
        }
        socket.getInputStream().readBytes().decodeToString()
    }

    private fun seededModel(
        create: () -> DeveloperApiViewModel = { DeveloperApiViewModel(app.graph, networkPermissionGranted = { true }) },
    ): Pair<ViewModelStoreOwner, DeveloperApiViewModel> {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val model = ViewModelProvider.create(
            owner,
            viewModelFactory { initializer { create() } },
        )["developer-api", DeveloperApiViewModel::class.java]
        return owner to model
    }

    /** A bind seam whose every socket comes from [make]. */
    private fun factory(make: (Int, Int, InetAddress?) -> ServerSocket) = object : ServerSocketFactory() {
        override fun createServerSocket(port: Int): ServerSocket = make(port, 50, null)
        override fun createServerSocket(port: Int, backlog: Int): ServerSocket = make(port, backlog, null)
        override fun createServerSocket(port: Int, backlog: Int, ifAddress: InetAddress?): ServerSocket =
            make(port, backlog, ifAddress)
    }

    private fun isSecure(): Boolean {
        var flags = 0
        rule.activityRule.scenario.onActivity { flags = it.window.attributes.flags }
        return flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }

    /** Decision 5: the three rows stay in every state, because the port is still a fact. */
    private fun assertTheThreeRowsStay() {
        rule.onNodeWithText("PORT").assertIsDisplayed()
        rule.onNodeWithText("PAIRING CODE").assertIsDisplayed()
        rule.onNodeWithText("REQUESTS THIS SESSION").assertIsDisplayed()
        rule.onNodeWithText("0").assertIsDisplayed()
    }

    @Test fun theScreenSaysWhatItIsDoingAndTheListenerAnswersWhileItIsShown() {
        val (owner, model) = seededModel()
        var shown by mutableStateOf(true)

        rule.setContent {
            ServiceTagTheme {
                if (shown) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        DeveloperApiScreen(graph = app.graph, onBack = {})
                    }
                }
            }
        }

        // The five ratified strings, exactly as ratified. `LabelValue` uppercases a label
        // (`LabelValue.kt:27`), so the three labels read back in capitals; the title and the
        // sentence read back as written.
        rule.awaitText("Developer API")
        rule.onNodeWithText("Developer API").assertIsDisplayed()
        rule.onNodeWithText(
            "While this screen is open, ServiceTag accepts commands on this phone's " +
                "loopback address only. Pair with the code below; leave the screen to stop.",
        ).assertIsDisplayed()
        rule.onNodeWithText("PORT").assertIsDisplayed()
        rule.onNodeWithText("PAIRING CODE").assertIsDisplayed()
        rule.onNodeWithText("REQUESTS THIS SESSION").assertIsDisplayed()
        rule.onNodeWithText(DEVELOPER_API_PORT.toString()).assertIsDisplayed()
        rule.onNodeWithText(model.pairingCode).assertIsDisplayed()

        // The effect has bound the port.
        rule.waitUntil(SETTLE_MILLIS) { model.boundPort == DEVELOPER_API_PORT }
        assertEquals(ListenerState.Listening, model.listener.value)
        // The failure lines, so on a working listener none of them may be anywhere.
        rule.onAllNodesWithText(S6).assertCountEquals(0)
        rule.onAllNodesWithText(P1A_1).assertCountEquals(0)
        rule.onAllNodesWithText(P1A_2).assertCountEquals(0)

        val answer = speak(
            "GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Authorization: Bearer ${model.pairingCode}\r\n\r\n",
        )
        assertTrue(answer, answer.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(answer, answer.contains("\"apiVersion\":1"))
        assertTrue(answer, answer.contains("\"assets\":0"))

        // Leaving the screen is what stops it — and after that there is nothing on the port.
        rule.runOnIdle { shown = false }
        rule.waitUntil(SETTLE_MILLIS) { model.boundPort == 0 }
        try {
            Socket("127.0.0.1", DEVELOPER_API_PORT).use { it.getInputStream().read() }
            fail("the port is still answering after the screen went away")
        } catch (e: IOException) {
            // Refused. That is the lifecycle binding working.
        }
    }

    @Test fun onlyTheCodeOnTheScreenIsAcceptedAndEveryTryIsCounted() {
        val (owner, model) = seededModel()

        rule.setContent {
            ServiceTagTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    DeveloperApiScreen(graph = app.graph, onBack = {})
                }
            }
        }
        rule.awaitText("Developer API")
        rule.waitUntil(SETTLE_MILLIS) { model.boundPort == DEVELOPER_API_PORT }

        val refused = speak("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer NOPENOPE\r\n\r\n")
        assertEquals(
            "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
            refused,
        )
        speak("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer ${model.pairingCode}\r\n\r\n")

        // The screen's third line is the count of answers given, refusals included.
        rule.waitUntil(SETTLE_MILLIS) { model.requests.value == 2 }
        rule.onNodeWithText("2").assertIsDisplayed()
    }

    /**
     * #66's adapter over the platform's own failure. On the JVM no `ErrnoException` is ever in the
     * chain, so this is the only place `bindErrnoOf` meets a real one: a second bind on a port this
     * process already holds. The model on top of it says "port in use" even with a permission probe
     * that reads as denied — rule 1 wins, so a taken port is never explained as a denial.
     */
    @Test fun thePlatformsOwnBindFailureCarriesAddressInUse() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loopback).use { squatter ->
            val failure = runCatching { ServerSocket(squatter.localPort, 1, loopback).close() }.exceptionOrNull()
            assertTrue("a second bind on a taken port did not fail: $failure", failure is IOException)
            assertEquals(BindErrno.ADDRESS_IN_USE, bindErrnoOf(failure!!))

            val model = DeveloperApiViewModel(app.graph, networkPermissionGranted = { false }, port = squatter.localPort)
            model.listen()
            assertEquals(ListenerState.CouldNotStart(StartOutcome.PortInUse), model.listener.value)
            model.stopListening()
        }
    }

    /**
     * **S6, byte-exactly, on a device**, for a taken port. The port is squatted by the test itself
     * and the model is handed that port, so the case is hermetic: it cannot collide with 17337, with
     * a real listener or with another test. It also asserts what decision 5 claims — that the three
     * `LabelValue` rows stay where they are when the listener does not come up.
     */
    @Test fun theScreenSaysSoWhenTheListenerCannotStart() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { squatter ->
            val (owner, model) = seededModel {
                DeveloperApiViewModel(app.graph, networkPermissionGranted = { true }, port = squatter.localPort)
            }

            rule.setContent {
                ServiceTagTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        DeveloperApiScreen(graph = app.graph, onBack = {})
                    }
                }
            }

            rule.awaitText(S6)
            rule.onNodeWithText(S6).assertIsDisplayed()
            rule.onAllNodesWithText(P1A_1).assertCountEquals(0)
            rule.onAllNodesWithText(P1A_2).assertCountEquals(0)
            assertEquals(ListenerState.CouldNotStart(StartOutcome.PortInUse), model.listener.value)
            assertEquals(0, model.boundPort)
            assertTheThreeRowsStay()
        }
    }

    /**
     * #66's notice: the ratified sentence in place of S6, and the ratified button beneath it, whose
     * one click is one call. The denial is injected — a bind seam that refuses the socket the way
     * the owner's hardened build does, and a probe that reads the permission as denied.
     */
    @Test fun aDeniedPermissionExplainsAndOffersSettings() {
        val refusing = factory { _, _, _ -> throw SocketException("socket failed: EACCES (Permission denied)") }
        val (owner, model) = seededModel {
            DeveloperApiViewModel(
                app.graph,
                networkPermissionGranted = { false },
                port = 0,
                serverSocketFactory = refusing,
            )
        }
        var opened = 0

        rule.setContent {
            ServiceTagTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    DeveloperApiScreen(graph = app.graph, onBack = {}, onOpenAppSettings = { opened++ })
                }
            }
        }

        rule.awaitText(P1A_1)
        rule.onNodeWithText(P1A_1).assertIsDisplayed()
        rule.onNodeWithText(P1A_2).assertIsDisplayed()
        rule.onAllNodesWithText(S6).assertCountEquals(0)
        assertEquals(ListenerState.CouldNotStart(StartOutcome.NetworkPermissionDenied), model.listener.value)
        assertTheThreeRowsStay()

        rule.onNodeWithText(P1A_2).performClick()
        rule.runOnIdle { assertEquals(1, opened) }
    }

    /**
     * #51 (b) on the screen: a listener that was up, whose `accept()` then fails on a socket nobody
     * closed, stops looking healthy. S6 appears, the port is released and the rows stay.
     */
    @Test fun aListenerThatDiesShowsS6() {
        val release = CountDownLatch(1)
        val dying = factory { port, backlog, address ->
            object : ServerSocket(port, backlog, address) {
                override fun accept(): Socket {
                    release.await()
                    throw IOException("accept failed on an open socket")
                }
            }
        }
        val (owner, model) = seededModel {
            DeveloperApiViewModel(app.graph, networkPermissionGranted = { true }, port = 0, serverSocketFactory = dying)
        }
        try {
            rule.setContent {
                ServiceTagTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        DeveloperApiScreen(graph = app.graph, onBack = {})
                    }
                }
            }
            rule.awaitText("Developer API")
            rule.waitUntil(SETTLE_MILLIS) { model.boundPort != 0 }
            assertEquals(ListenerState.Listening, model.listener.value)
            rule.onAllNodesWithText(S6).assertCountEquals(0)

            release.countDown()

            rule.awaitText(S6)
            rule.onNodeWithText(S6).assertIsDisplayed()
            rule.onAllNodesWithText(P1A_1).assertCountEquals(0)
            assertEquals(ListenerState.Died, model.listener.value)
            assertEquals(0, model.boundPort)
            assertTheThreeRowsStay()
        } finally {
            release.countDown()
        }
    }

    /**
     * #51 (a): `FLAG_SECURE` for the screen's whole composition. Set while it is shown; **still set
     * after a pause**, because the Recents thumbnail is taken as the task leaves the foreground and
     * a flag cleared on pause would race it; cleared once the screen leaves composition, so no other
     * screen inherits it.
     */
    @Test fun thePairingCodeIsSecureForTheWholeComposition() {
        val (owner, _) = seededModel()
        var shown by mutableStateOf(true)
        rule.setContent {
            ServiceTagTheme {
                if (shown) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        DeveloperApiScreen(graph = app.graph, onBack = {})
                    }
                }
            }
        }
        rule.awaitText("Developer API")
        rule.waitForIdle()
        assertTrue("the window is not secure while the pairing code is shown", isSecure())

        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        assertTrue("the flag dropped on pause, before the Recents snapshot", isSecure())

        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        assertFalse("the flag outlived the screen", isSecure())
    }

    /** The page the button opens when no test replaces it: this app's own details page, which exists. */
    @Test fun theSettingsButtonOpensThisAppsDetailsPage() {
        val intent = appDetails(app)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
        assertNotNull("nothing on this build opens an app's details page", intent.resolveActivity(app.packageManager))
    }

    /**
     * Review F1: the button's own fallback, with no callback passed. A recording context stands in
     * for the activity's `startActivity`, so the click is observed without leaving the app and
     * without espresso-intents.
     */
    @Test fun theButtonWithNoCallbackOpensThisAppsDetailsPage() {
        val refusing = factory { _, _, _ -> throw SocketException("socket failed: EACCES (Permission denied)") }
        val (owner, _) = seededModel {
            DeveloperApiViewModel(app.graph, networkPermissionGranted = { false }, port = 0, serverSocketFactory = refusing)
        }
        val captured = mutableListOf<Intent>()
        rule.setContent {
            val activity = LocalContext.current
            val recorder = remember(activity) {
                object : ContextWrapper(activity) {
                    override fun startActivity(intent: Intent) {
                        captured += intent
                    }
                }
            }
            ServiceTagTheme {
                CompositionLocalProvider(LocalContext provides recorder, LocalViewModelStoreOwner provides owner) {
                    DeveloperApiScreen(graph = app.graph, onBack = {})
                }
            }
        }

        rule.awaitText(P1A_2)
        rule.onNodeWithText(P1A_2).performClick()
        rule.runOnIdle {
            assertEquals(1, captured.size)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captured.single().action)
            assertEquals("package:${app.packageName}", captured.single().dataString)
        }
    }

    /**
     * Review F2: the adapter's other arms, over real `ErrnoException`s — only a device has them; on
     * the JVM they are stubs and every errno reads 0.
     */
    @Test fun theAdapterReadsEveryErrnoArm() {
        fun wrapped(errno: Int) = SocketException("socket failed").apply { initCause(ErrnoException("socket", errno)) }
        assertEquals(BindErrno.ACCESS_DENIED, bindErrnoOf(wrapped(OsConstants.EACCES)))
        assertEquals(BindErrno.ACCESS_DENIED, bindErrnoOf(wrapped(OsConstants.EPERM)))
        assertEquals(BindErrno.OTHER, bindErrnoOf(wrapped(OsConstants.EMFILE)))
        assertEquals(BindErrno.UNKNOWN, bindErrnoOf(SocketException("socket failed")))
        val twoDeep = IOException("outer", SocketException("middle").apply { initCause(ErrnoException("bind", OsConstants.EADDRINUSE)) })
        assertEquals(BindErrno.ADDRESS_IN_USE, bindErrnoOf(twoDeep))
    }
}
