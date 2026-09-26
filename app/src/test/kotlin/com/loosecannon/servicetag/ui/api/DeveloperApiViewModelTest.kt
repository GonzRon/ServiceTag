package com.loosecannon.servicetag.ui.api

import com.loosecannon.servicetag.api.ApiHandlers
import com.loosecannon.servicetag.api.ListenerState
import com.loosecannon.servicetag.api.PAIRING_ALPHABET
import com.loosecannon.servicetag.api.PAIRING_CODE_LENGTH
import com.loosecannon.servicetag.api.StartOutcome
import com.loosecannon.servicetag.api.maintenanceHandlersFor
import com.loosecannon.servicetag.api.referenceHandlersFor
import com.loosecannon.servicetag.api.seasonHealthHandlersFor
import com.loosecannon.servicetag.testing.FakeGraph
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.ServerSocketFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 (#46) — the per-visit pairing code, and what the screen is told about its listener: S6 when
 * it cannot bind or dies (#51), the network-permission notice when the permission reads as denied
 * (#66).
 *
 * No `Dispatchers.setMain` and no shared scheduler: this view model has no `viewModelScope` work,
 * no Room flow and nothing to settle — `listen()` binds a socket synchronously and returns. The one
 * asynchronous edge, a worker publishing `Died`, is awaited on the flow with a bound.
 */
class DeveloperApiViewModelTest {

    private val graph = FakeGraph()

    @After fun close() = graph.close()

    private fun handlers() = ApiHandlers(
        graph.assets, graph.tags, graph.links, graph.definitions, graph.profiles, graph.events,
        graph.attachments,
        graph.createAsset, graph.updateAsset, graph.retireAsset, graph.archiveAsset,
        graph.saveDefinition, graph.archiveDefinition, graph.saveProfile, graph.archiveProfile,
        graph.logEvent, graph.updateEvent, graph.deleteEvent, graph.importBackupMerge,
        maintenanceHandlersFor(graph),
        referenceHandlersFor(graph),
        seasonHealthHandlersFor(graph),
        appVersion = "1.1.0",
        schemaVersion = 5,
    )

    /** A bind seam whose every socket comes from [make]. */
    private fun factory(make: (Int, Int, InetAddress?) -> ServerSocket) = object : ServerSocketFactory() {
        override fun createServerSocket(port: Int): ServerSocket = make(port, 50, null)
        override fun createServerSocket(port: Int, backlog: Int): ServerSocket = make(port, backlog, null)
        override fun createServerSocket(port: Int, backlog: Int, ifAddress: InetAddress?): ServerSocket =
            make(port, backlog, ifAddress)
    }

    /** A fresh code per model is what "a fresh code every time the screen opens" reduces to. */
    @Test fun eachModelMintsItsOwnCode() {
        val codes = List(50) { DeveloperApiViewModel(handlers(), networkPermissionGranted = { true }, port = 0).pairingCode }
        assertEquals(50, codes.toSet().size)
        codes.forEach {
            assertEquals(PAIRING_CODE_LENGTH, it.length)
            assertTrue(it.all { c -> c in PAIRING_ALPHABET })
        }
        assertNotEquals(codes[0], codes[1])
    }

    @Test fun listeningOnAFreePortReportsNoFailure() {
        val model = DeveloperApiViewModel(handlers(), networkPermissionGranted = { true }, port = 0)
        assertEquals(ListenerState.Stopped, model.listener.value)
        try {
            model.listen()
            assertEquals(ListenerState.Listening, model.listener.value)
            assertNotEquals(0, model.boundPort)
        } finally {
            model.stopListening()
        }
        assertEquals(0, model.boundPort)
        assertEquals(ListenerState.Stopped, model.listener.value)
    }

    /** S6's first trigger: something else already has the port. */
    @Test fun listeningOnAnOccupiedPortReportsTheFailure() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { squatter ->
            val model = DeveloperApiViewModel(handlers(), networkPermissionGranted = { true }, port = squatter.localPort)
            model.listen()

            assertEquals(ListenerState.CouldNotStart(StartOutcome.PortInUse), model.listener.value)
            assertEquals(0, model.boundPort)
            assertEquals(0, model.requests.value)

            // Leaving the screen clears the message: the next visit decides for itself.
            model.stopListening()
            assertEquals(ListenerState.Stopped, model.listener.value)
        }
    }

    /** #66: the cause reaches the model, not just the fact of a failure. */
    @Test fun aDeniedPermissionReachesTheModel() {
        val refusing = factory { _, _, _ -> throw SocketException("socket failed: EACCES (Permission denied)") }
        val model = DeveloperApiViewModel(
            handlers(),
            networkPermissionGranted = { false },
            port = 0,
            serverSocketFactory = refusing,
        )
        model.listen()

        assertEquals(ListenerState.CouldNotStart(StartOutcome.NetworkPermissionDenied), model.listener.value)
        assertEquals(0, model.boundPort)

        model.stopListening()
        assertEquals(ListenerState.Stopped, model.listener.value)
    }

    /** #51: a listener that stops answering on its own reaches the model, which never saw a `start()` fail. */
    @Test fun aDeadListenerReachesTheModel() {
        val dying = factory { port, backlog, address ->
            object : ServerSocket(port, backlog, address) {
                override fun accept(): Socket = throw IOException("accept failed on an open socket")
            }
        }
        val model = DeveloperApiViewModel(
            handlers(),
            networkPermissionGranted = { true },
            port = 0,
            serverSocketFactory = dying,
        )
        try {
            model.listen()
            runBlocking { withTimeoutOrNull(5_000) { model.listener.first { it == ListenerState.Died } } }
            assertEquals(ListenerState.Died, model.listener.value)
            assertEquals(0, model.boundPort)
        } finally {
            model.stopListening()
        }
        assertEquals(ListenerState.Stopped, model.listener.value)
    }

    /** Every state the listener can publish, and the one notice (or none) each one draws. */
    @Test fun theNoticeForEveryState() {
        val expected = mapOf(
            ListenerState.Stopped to DeveloperApiNotice.None,
            ListenerState.Listening to DeveloperApiNotice.None,
            ListenerState.CouldNotStart(StartOutcome.PortInUse) to DeveloperApiNotice.CouldNotStart,
            ListenerState.CouldNotStart(StartOutcome.Other) to DeveloperApiNotice.CouldNotStart,
            ListenerState.CouldNotStart(StartOutcome.NetworkPermissionDenied) to
                DeveloperApiNotice.NetworkPermissionDenied,
            // Never published (a bind is `Listening`), and still never read as healthy.
            ListenerState.CouldNotStart(StartOutcome.Bound) to DeveloperApiNotice.CouldNotStart,
            ListenerState.Died to DeveloperApiNotice.CouldNotStart,
        )
        expected.forEach { (state, notice) -> assertEquals("$state", notice, developerApiNotice(state)) }
        // Only the denial outcome reaches the denial notice, as the production mapping answers it.
        assertEquals(
            listOf(ListenerState.CouldNotStart(StartOutcome.NetworkPermissionDenied)),
            expected.keys.filter { developerApiNotice(it) == DeveloperApiNotice.NetworkPermissionDenied },
        )
    }
}
