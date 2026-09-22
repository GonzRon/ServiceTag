package com.loosecannon.servicetag.ui.api

import com.loosecannon.servicetag.api.ApiHandlers
import com.loosecannon.servicetag.api.PAIRING_ALPHABET
import com.loosecannon.servicetag.api.PAIRING_CODE_LENGTH
import com.loosecannon.servicetag.api.maintenanceHandlersFor
import com.loosecannon.servicetag.testing.FakeGraph
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 (#46) — the per-visit pairing code, and **S6**: what the screen is told when the listener
 * cannot bind.
 *
 * No `Dispatchers.setMain` and no shared scheduler: this view model has no `viewModelScope` work,
 * no Room flow and nothing to settle — `listen()` binds a socket synchronously and returns.
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
        appVersion = "1.1.0",
        schemaVersion = 5,
    )

    /** A fresh code per model is what "a fresh code every time the screen opens" reduces to. */
    @Test fun eachModelMintsItsOwnCode() {
        val codes = List(50) { DeveloperApiViewModel(handlers(), port = 0).pairingCode }
        assertEquals(50, codes.toSet().size)
        codes.forEach {
            assertEquals(PAIRING_CODE_LENGTH, it.length)
            assertTrue(it.all { c -> c in PAIRING_ALPHABET })
        }
        assertNotEquals(codes[0], codes[1])
    }

    @Test fun listeningOnAFreePortReportsNoFailure() {
        val model = DeveloperApiViewModel(handlers(), port = 0)
        try {
            model.listen()
            assertFalse(model.failedToStart.value)
            assertNotEquals(0, model.boundPort)
        } finally {
            model.stopListening()
        }
        assertEquals(0, model.boundPort)
    }

    /** S6's trigger: something else already has the port. */
    @Test fun listeningOnAnOccupiedPortReportsTheFailure() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { squatter ->
            val model = DeveloperApiViewModel(handlers(), port = squatter.localPort)
            model.listen()

            assertTrue(model.failedToStart.value)
            assertEquals(0, model.boundPort)
            assertEquals(0, model.requests.value)

            // Leaving the screen clears the message: the next visit decides for itself.
            model.stopListening()
            assertFalse(model.failedToStart.value)
        }
    }
}
