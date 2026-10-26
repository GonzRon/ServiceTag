package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.ScriptedHaPublish
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #113/#114 (C27, K7, A08, F-25; row 69) — the installation fence over Room. A binding whose rows came back on another
 * installation (a rebuilt graph over the same database, a new installation id, the token entered again) sends nothing;
 * Check helper validates it but never activates it, also after another rebuild; only Enable on this installation
 * re-stamps it, and then it sends. A copied tombstone is drawn as UNDELIVERABLE, never drained. The sender is the runner (B4a) over each
 * graph's scripted Home Assistant, the tombstone drain included (B5); the helpers are fictional.
 */
class InstallationFenceTest {

    private val original = FakeGraph()
    private val heater = AssetId("asset-example-heater")
    private val status = "input_select.example_heater_status"
    private val date = "input_datetime.example_heater_service_done"

    /** Home Assistant's helpers, and every service call this phone made to them. */
    private val helpers = linkedMapOf(
        status to RemoteEntity(status, "unknown", listOf("UP", "DEGRADED", "DOWN"), null, null),
        date to RemoteEntity(date, "2026-05-01", null, true, false),
    )

    @After fun close() = original.db.close()

    /** The same database on another installation: a fresh no-backup directory, so a new id and no token. */
    private fun copied(): FakeGraph = FakeGraph(db = original.db)

    private suspend fun FakeGraph.connect() = saveHaConnection.run(
        "http://192.168.0.10:8123", Secret("fictional-token-1"), null, NetworkEligibility.HOME_NETWORK_ONLY,
        "ExampleHomeWifi", null,
    )

    private suspend fun FakeGraph.fresh(entityId: String, kind: PublishKind): ValidationResult {
        val connection = haConnections.get()!!
        val revision = publishBindings.forAsset(heater).firstOrNull { it.kind == kind }?.revision ?: 0
        return ValidationResult(entityId, helpers.getValue(entityId), connection.id, connection.configGeneration, revision, now)
    }

    /** This graph's Home Assistant holds the test's helpers; its runner is the sender (C30). */
    private fun FakeGraph.scripted(): FakeGraph = also { haPublish.helpers.putAll(helpers) }

    /** One Publish now of [heater] on this graph: what its one binding's pass came to. */
    private suspend fun FakeGraph.publishNow(): PassResult = publishRunner.publishNow(heater).outcomes.single().result

    private suspend fun enabledStatusOnTheOriginal(): String {
        original.assets.upsert(Asset(id = heater, name = "Example Heater", createdAt = 500L, updatedAt = 500L))
        original.connect()
        val binding = original.enablePublishing.run(
            heater, PublishKind.EQUIPMENT_STATUS, status, original.fresh(status, PublishKind.EQUIPMENT_STATUS), null,
            original.installationIdentity.id(),
        )
        assertTrue(original.publishDeliveries.writeDesired(binding.id, binding.revision, 0, "UP", original.now))
        return binding.id
    }

    @Test
    fun aCopiedBindingSendsNothingEvenWithAToken() = runBlocking<Unit> {
        enabledStatusOnTheOriginal()
        val copy = copied()
        assertNotEquals(original.installationIdentity.id(), copy.installationIdentity.id())
        copy.connect() // the token entered again on the new installation

        assertEquals(PassResult.Skipped(PublishState.COPIED_INSTALLATION), copy.scripted().publishNow())
        assertTrue("the copy reads and POSTs nothing", copy.haPublish.log.isEmpty())
    }

    @Test
    fun revalidatingACopiedEnabledRowLeavesItInertUntilEnable() = runBlocking<Unit> {
        enabledStatusOnTheOriginal()
        val copy = copied().scripted()
        val here = copy.installationIdentity.id()
        copy.connect()

        val checked = copy.revalidatePublishing.run(heater, PublishKind.EQUIPMENT_STATUS, copy.fresh(status, PublishKind.EQUIPMENT_STATUS), null)
        assertEquals(checked.binding.revision, checked.binding.validatedRevision)
        assertEquals(original.installationIdentity.id(), checked.binding.activatedInstallationId)
        assertEquals(PassResult.Skipped(PublishState.COPIED_INSTALLATION), copy.publishNow())

        // This installation, restarted: the same database, token store and installation id.
        val restarted =
            FakeGraph(db = original.db, secretStore = copy.secretStore, installationDir = copy.installationDir).scripted()
        assertEquals(here, restarted.installationIdentity.id())
        assertEquals(PassResult.Skipped(PublishState.COPIED_INSTALLATION), restarted.publishNow())
        assertTrue("zero POSTs after Check helper", copy.haPublish.posts.isEmpty() && restarted.haPublish.posts.isEmpty())

        val enabled = restarted.enablePublishing.run(
            heater, PublishKind.EQUIPMENT_STATUS, status, restarted.fresh(status, PublishKind.EQUIPMENT_STATUS), null, here,
        )
        assertEquals(here, enabled.activatedInstallationId)
        // The value held from the other installation is no fact here (I1): nothing is sent until the projector's write.
        assertEquals(PassResult.Skipped(PublishState.WAITING_FOR_SOURCE), restarted.publishNow())
        assertTrue("zero POSTs before the projector", restarted.haPublish.posts.isEmpty())
        assertTrue(restarted.publishDeliveries.writeDesired(enabled.id, enabled.revision, 1, "UP", restarted.now))
        restarted.publishNow()
        assertEquals(listOf(ScriptedHaPublish.Call(HaServiceAction.SELECT_OPTION, status, "UP")), restarted.haPublish.posts)
    }

    /**
     * B3's M3: a PENDING tombstone another installation activated is never drained here — no read, no POST — and is
     * marked UNDELIVERABLE as `COPIED_INSTALLATION` at once, so it is drawn with its dismissal and never blocks an
     * address change.
     */
    @Test
    fun aCopiedBindingsTombstoneIsDrawnNeverDrained() = runBlocking<Unit> {
        original.assets.upsert(Asset(id = heater, name = "Example Heater", createdAt = 500L, updatedAt = 500L))
        original.connect()
        original.enablePublishing.run(
            heater, PublishKind.SEASON_SERVICE_DATE, date, original.fresh(date, PublishKind.SEASON_SERVICE_DATE), null,
            original.installationIdentity.id(),
        )
        original.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)

        val copy = copied().scripted()
        copy.connect()
        copy.publishRunner.drain()
        copy.publishRunner.publishNow(null)

        assertTrue("the copy reads and POSTs nothing", copy.haPublish.log.isEmpty())
        val drawn = copy.publishDecommissions.all().single()
        assertEquals(date, drawn.entityId)
        assertEquals(original.installationIdentity.id(), drawn.installationId)
        assertEquals(DecommissionState.UNDELIVERABLE, drawn.state)
        assertEquals(PublishErrorKind.COPIED_INSTALLATION, drawn.errorKind)
        copy.saveHaConnection.run(
            "http://192.168.0.11:8123", Secret("fictional-token-1"), null, NetworkEligibility.HOME_NETWORK_ONLY,
            "ExampleHomeWifi", null,
        )
        assertEquals("no longer blocks an address change", "http://192.168.0.11:8123", copy.haConnections.get()!!.baseUrl)
    }
}
