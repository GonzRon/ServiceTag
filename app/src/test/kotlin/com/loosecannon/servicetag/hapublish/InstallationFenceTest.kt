package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DeliveryPlan
import com.loosecannon.servicetag.core.hapublish.DeliveryRecord
import com.loosecannon.servicetag.core.hapublish.DeliverySteps
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger
import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.hapublish.ReadStep
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.Snap
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.testing.FakeGraph
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
 * re-stamps it, and then it sends. A copied tombstone is drawn, never drained. The sender's steps are B1's
 * `DeliverySteps` composed over the ports, as the runner (B4a) will compose them; the helpers are fictional.
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
    private val posts = mutableListOf<Pair<HaServiceAction, String>>()

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

    /** One pass of the sender on [installationId]: the gate, a read, a write-ahead, the POST, a read-back. */
    private suspend fun FakeGraph.pass(bindingId: String, installationId: String): DeliveryPlan {
        val binding = publishBindings.get(bindingId)!!
        val delivery = publishDeliveries.get(bindingId)!!
        val connection = haConnections.get()
        val snap = Snap(
            binding, delivery, connection?.id == binding.connectionId,
            connection != null && secretStore.has(connection.id), installationId, assets.get(binding.assetId),
            transferRecords.heldIds(),
        )
        val plan = DeliverySteps.plan(snap, DeliveryTrigger.RECONCILE)
        if (plan !is DeliveryPlan.ReadFirst) return plan
        val ahead = DeliverySteps.reconcileWriteAhead(binding, delivery)
        record(ahead)
        val read = RemoteRead.Answered(helpers.getValue(binding.entityId))
        when (val step = DeliverySteps.afterRead(binding, ahead.delivery, plan.value, read, now)) {
            is ReadStep.Record -> record(step.record)
            is ReadStep.Post -> {
                record(step.writeAhead)
                posts += step.action to step.value
                helpers[binding.entityId] = helpers.getValue(binding.entityId).copy(state = step.value)
                val back = RemoteRead.Answered(helpers.getValue(binding.entityId))
                record(DeliverySteps.afterPostAccepted(binding, step.writeAhead.delivery, step.value, back, now))
            }
        }
        return plan
    }

    private suspend fun FakeGraph.record(record: DeliveryRecord) {
        assertTrue(publishDeliveries.record(record.bindingRevision, record.delivery))
    }

    /** C34, F-25: a tombstone is drained only by the installation that activated it. */
    private suspend fun FakeGraph.drain(installationId: String): Int {
        var drained = 0
        for (t in publishDecommissions.all()) {
            if (t.state != DecommissionState.PENDING || t.installationId != installationId) continue
            posts += HaServiceAction.SET_DATE to INVALID_SERVICE_DATE
            helpers[t.entityId] = helpers.getValue(t.entityId).copy(state = INVALID_SERVICE_DATE)
            publishDecommissions.delete(t.id)
            drained += 1
        }
        return drained
    }

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
        val id = enabledStatusOnTheOriginal()
        val copy = copied()
        assertNotEquals(original.installationIdentity.id(), copy.installationIdentity.id())
        copy.connect() // the token entered again on the new installation

        assertEquals(DeliveryPlan.Skip(PublishState.COPIED_INSTALLATION), copy.pass(id, copy.installationIdentity.id()))
        assertTrue("the copy POSTs nothing", posts.isEmpty())
    }

    @Test
    fun revalidatingACopiedEnabledRowLeavesItInertUntilEnable() = runBlocking<Unit> {
        val id = enabledStatusOnTheOriginal()
        val copy = copied()
        val here = copy.installationIdentity.id()
        copy.connect()

        val checked = copy.revalidatePublishing.run(heater, PublishKind.EQUIPMENT_STATUS, copy.fresh(status, PublishKind.EQUIPMENT_STATUS), null)
        assertEquals(checked.binding.revision, checked.binding.validatedRevision)
        assertEquals(original.installationIdentity.id(), checked.binding.activatedInstallationId)
        assertEquals(DeliveryPlan.Skip(PublishState.COPIED_INSTALLATION), copy.pass(id, here))

        val restarted = FakeGraph(db = original.db, secretStore = copy.secretStore) // this installation, restarted
        assertEquals(DeliveryPlan.Skip(PublishState.COPIED_INSTALLATION), restarted.pass(id, here))
        assertTrue("zero POSTs after Check helper", posts.isEmpty())

        val enabled = restarted.enablePublishing.run(
            heater, PublishKind.EQUIPMENT_STATUS, status, restarted.fresh(status, PublishKind.EQUIPMENT_STATUS), null, here,
        )
        assertEquals(here, enabled.activatedInstallationId)
        restarted.pass(id, here)
        assertEquals(listOf(HaServiceAction.SELECT_OPTION to "UP"), posts)
    }

    @Test
    fun aCopiedBindingsTombstoneIsDrawnNeverDrained() = runBlocking<Unit> {
        original.assets.upsert(Asset(id = heater, name = "Example Heater", createdAt = 500L, updatedAt = 500L))
        original.connect()
        original.enablePublishing.run(
            heater, PublishKind.SEASON_SERVICE_DATE, date, original.fresh(date, PublishKind.SEASON_SERVICE_DATE), null,
            original.installationIdentity.id(),
        )
        original.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)

        val copy = copied()
        copy.connect()
        assertEquals(0, copy.drain(copy.installationIdentity.id()))
        assertTrue(posts.isEmpty())
        val drawn = copy.publishDecommissions.all().single()
        assertEquals(date, drawn.entityId)
        assertEquals(original.installationIdentity.id(), drawn.installationId)
    }
}
