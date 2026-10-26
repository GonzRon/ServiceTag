package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.BOILER
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.DATE
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.DATE_2
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.HEATER
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.STATUS
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SaveHaConnectionRefusal
import com.loosecannon.servicetag.core.seasonsync.SaveHaConnectionRefused
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #113/#114 (C28, K9, I32, O-11, O-12, F-2; row 87) — the shipped connection commands with the publishing side: an
 * address change waits while a date helper is owed `1970-01-01`; a re-authorised save moves every publish binding's
 * revision and keeps its validation; Forget leaves every undelivered date obligation as an UNDELIVERABLE tombstone;
 * every save moves `configGeneration` by one. The address, token and helpers are fictional.
 */
class ConnectionHooksTest {

    private val third = "dehumidifier"

    private fun harness(): PublishHarness = PublishHarness().apply {
        asset(HEATER)
        asset(BOILER)
        asset(third)
    }

    @Test
    fun anAddressChangeIsRefusedWhileADateBindingOrPendingTombstoneExists() = runTest {
        val p = harness()
        val connection = p.connect()
        p.enableStatus()
        p.connect(baseUrl = "http://192.168.0.11:8123") // a status binding alone does not hold the address
        val moved = p.connections.get()!!

        p.enableDate()
        val refused = assertFailsWith<SaveHaConnectionRefused> { p.connect(baseUrl = "http://192.168.0.12:8123") }
        assertEquals(SaveHaConnectionRefusal.PUBLISHING_BLOCKS_ADDRESS_CHANGE, refused.reason)
        assertEquals(moved, p.connections.get(), "nothing was written")

        p.disable.run(AssetId(HEATER), PublishKind.SEASON_SERVICE_DATE)
        assertFailsWith<SaveHaConnectionRefused> { p.connect(baseUrl = "http://192.168.0.12:8123") }

        // A token change on the same address is allowed while the obligation stands.
        p.connect(baseUrl = "http://192.168.0.11:8123", token = Secret("fictional-token-2"))

        assertEquals(listOf(p.tombstones.all().single().id), p.pass.drainTombstones())
        p.connect(baseUrl = "http://192.168.0.12:8123")
        assertEquals("http://192.168.0.12:8123", p.connections.get()!!.baseUrl)
        assertEquals(connection.id, p.connections.get()!!.id)
    }

    @Test
    fun aTokenChangeBumpsEveryPublishRevision() = runTest {
        val p = harness()
        p.connect()
        val status = p.enableStatus(HEATER)
        val date = p.enableDate(BOILER)
        p.enableDate(third, DATE_2)
        val off = p.disable.run(AssetId(third), PublishKind.SEASON_SERVICE_DATE)
        // An AUTH_REFUSED recorded at the status binding's pair.
        val d = p.deliveries.get(status.id)!!
        assertTrue(p.deliveries.record(status.revision, DeliverySteps.failed(status, d, PublishErrorKind.AUTH_REFUSED, null, p.h.now).delivery))

        p.connect(token = Secret("fictional-token-2"))
        assertEquals(status.revision + 1, p.bindings.get(status.id)!!.revision)
        assertEquals(date.revision + 1, p.bindings.get(date.id)!!.revision)
        assertEquals(off.revision + 1, p.bindings.get(off.id)!!.revision)
        assertNull(p.deliveries.get(status.id)!!.errorKind, "the error is cleared with its pair, so it is retried")
        assertNull(p.deliveries.get(status.id)!!.errorBindingRevision)

        // A cadence-only save is no re-authorisation: no revision moves.
        p.save.run(
            "http://192.168.0.10:8123", null, SyncCadence.WEEKLY, NetworkEligibility.HOME_NETWORK_ONLY,
            "ExampleHomeWifi", BackgroundChecks.OFF,
        )
        assertEquals(status.revision + 1, p.bindings.get(status.id)!!.revision)
    }

    @Test
    fun forgetTurnsUndeliveredDateObligationsIntoUndeliverableTombstones() = runTest {
        val p = harness()
        val connection = p.connect()
        p.enableStatus(HEATER, STATUS)
        val owing = p.enableDate(HEATER, DATE)
        p.project(owing, "2026-06-01")
        p.pass.run(owing.id)
        val cleared = p.enableDate(BOILER, DATE_2)
        p.project(cleared, INVALID_SERVICE_DATE)
        p.pass.run(cleared.id) // read back as 1970-01-01: nothing more is owed
        val pending = "input_datetime.example_dehumidifier_service_done"
        p.transport.dateHelper(pending)
        p.enableDate(third, pending)
        p.disable.run(AssetId(third), PublishKind.SEASON_SERVICE_DATE) // a PENDING tombstone

        p.forget.run()
        assertNull(p.connections.get())
        assertTrue(p.bindings.rows.isEmpty(), "the connection's CASCADE took every binding")
        val left = p.tombstones.all().associate { it.entityId to it.state }
        assertEquals(mapOf(DATE to DecommissionState.UNDELIVERABLE, pending to DecommissionState.UNDELIVERABLE), left)
        val heaters = p.tombstones.byTarget(connection.id, DATE)!!
        assertEquals(AssetId(HEATER), heaters.assetId)
        assertEquals(owing.activatedInstallationId, heaters.installationId)
    }

    @Test
    fun aTokenChangeKeepsBindingsValidatedAndDeliversPendingInvalidations() = runTest {
        val p = harness()
        p.connect()
        val date = p.enableDate()
        p.project(date, "2026-06-01")
        p.pass.run(date.id)
        // The role was removed: the projector's desired value is now 1970-01-01, not yet delivered.
        p.project(p.bindings.get(date.id)!!, INVALID_SERVICE_DATE)

        p.connect(token = Secret("fictional-token-2"))
        val bumped = p.bindings.get(date.id)!!
        assertEquals(bumped.revision, bumped.validatedRevision, "a re-authorised binding stays validated")
        p.transport.posts.clear()
        assertIs<DeliveryPlan.ReadFirst>(p.pass.run(date.id))
        assertEquals(INVALID_SERVICE_DATE, p.transport.stateOf(DATE))
        assertEquals(1, p.transport.posts.size)
    }

    @Test
    fun everySaveAddsOneToConfigGeneration() = runTest {
        val p = harness()
        assertEquals(1, p.connect().configGeneration)
        p.save.run(
            "http://192.168.0.10:8123", null, SyncCadence.WEEKLY, NetworkEligibility.HOME_NETWORK_ONLY,
            "ExampleHomeWifi", BackgroundChecks.OFF,
        )
        assertEquals(2, p.connections.get()!!.configGeneration)
        p.connect(token = Secret("fictional-token-2"))
        assertEquals(3, p.connections.get()!!.configGeneration)
        p.connect(token = null)
        assertEquals(4, p.connections.get()!!.configGeneration, "a save that changes nothing still counts")
    }
}
