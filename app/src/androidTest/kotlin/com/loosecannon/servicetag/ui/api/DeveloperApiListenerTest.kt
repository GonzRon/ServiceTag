package com.loosecannon.servicetag.ui.api

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.api.DEVELOPER_API_PORT
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** How long an assertion waits for the lifecycle effect to bind or release the port. */
private const val SETTLE_MILLIS = 5_000L

/**
 * 1.1.0 (#46) — the half of this feature that only a device can prove: that the listener really
 * comes up on 17337 when the screen is shown, that the code on the screen is the code it accepts,
 * and that it is **gone** when the screen goes.
 *
 * **What is proved elsewhere, and is not repeated here.** The parser, the ceilings, the router, the
 * handlers and the 401 are `:app`'s JVM suite (`HttpWireTest`, `ApiRouterTest`,
 * `LoopbackApiServerTest`), on a real socket, with no emulator. What that suite cannot reach is the
 * `INTERNET` permission actually being granted, `LifecycleResumeEffect` actually running, and a
 * `ViewModel` scoped to a nav entry actually minting one code per visit. That is this file.
 *
 * **How the code gets from the screen to the assertion.** The model is built here and seeded into
 * the `ViewModelStore` this test provides, under the key `DeveloperApiScreen` resolves with —
 * `viewModel(key = "developer-api")` returns the stored instance rather than calling its
 * initializer. So `model.pairingCode` is the screen's own code, and the test both types it at the
 * socket and asserts it is on screen. No seam is added to production code. (Task 6's end-to-end
 * proof did it the other way round, reading the code off the screen from the workstation, a path
 * #62 retired.)
 *
 * The production port is used deliberately: it is what the workstation forwards to, and an
 * emulator running one instrumented suite has nothing else on it.
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

    private fun seededModel(): Pair<ViewModelStoreOwner, DeveloperApiViewModel> {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val model = ViewModelProvider.create(
            owner,
            viewModelFactory { initializer { DeveloperApiViewModel(app.graph) } },
        )["developer-api", DeveloperApiViewModel::class.java]
        return owner to model
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
        // S6 is the failure line, so on a working listener it must not be anywhere.
        rule.onAllNodesWithText(
            "The Developer API could not start. Leave this screen and open it again.",
        ).assertCountEquals(0)

        // The effect has bound the port.
        rule.waitUntil(SETTLE_MILLIS) { model.boundPort == DEVELOPER_API_PORT }
        assertFalse(model.failedToStart.value)

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
     * **S6, byte-exactly, on a device.** `DeveloperApiViewModelTest` proves the flag flips;
     * `theScreenSaysWhatItIsDoingAndTheListenerAnswersWhileItIsShown` proves the sentence is
     * *absent* on the happy path — which a typo'd screen also satisfies. This is the only case that
     * reads the ratified words off a rendered screen, so a wrong word in the one string the owner
     * added *because* an earlier draft left the failure silent cannot ship green.
     *
     * The port is squatted by the test itself, and the model is handed that port, so the case is
     * hermetic: it cannot collide with 17337, with a real listener or with another test. It also
     * asserts what decision 5 claims and nothing else checked — that the three `LabelValue` rows
     * stay where they are when the listener does not come up.
     */
    @Test fun theScreenSaysSoWhenTheListenerCannotStart() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { squatter ->
            val owner = object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
            val model = ViewModelProvider.create(
                owner,
                viewModelFactory {
                    initializer { DeveloperApiViewModel(app.graph, port = squatter.localPort) }
                },
            )["developer-api", DeveloperApiViewModel::class.java]

            rule.setContent {
                ServiceTagTheme {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        DeveloperApiScreen(graph = app.graph, onBack = {})
                    }
                }
            }

            rule.awaitText("The Developer API could not start. Leave this screen and open it again.")
            rule.onNodeWithText(
                "The Developer API could not start. Leave this screen and open it again.",
            ).assertIsDisplayed()
            assertTrue(model.failedToStart.value)
            assertEquals(0, model.boundPort)

            // Decision 5: the three rows stay, because the port is still a fact and the count
            // staying at 0 is itself the corroboration.
            rule.onNodeWithText("PORT").assertIsDisplayed()
            rule.onNodeWithText("PAIRING CODE").assertIsDisplayed()
            rule.onNodeWithText("REQUESTS THIS SESSION").assertIsDisplayed()
            rule.onNodeWithText("0").assertIsDisplayed()
        }
    }
}
