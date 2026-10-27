package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.DeliveryRecord
import com.loosecannon.servicetag.core.hapublish.DeliverySteps
import com.loosecannon.servicetag.core.hapublish.DeliveryTrigger
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.hapublish.errorIsCurrent
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.testing.FakeGraph
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #113 (A04, I22; row 42b) — the error pair through Room, read back by a **rebuilt graph** before the drain's
 * selection: history A (`WRITE_FORBIDDEN` at (1, 7), then re-authorised to (2, 7)) is retried; history B
 * (re-authorised first, then `WRITE_FORBIDDEN` at (2, 7)) is skipped; a new desired value or a Check helper clears B's
 * error, and a second failure at the same pair keeps it current. Also B1's carry: the Room record is
 * `DeliverySteps.commit`, writing neither the desired value, the boundary cache nor the request stamp, and the desired
 * write starts the attempt count again. Every id, helper and address is fictional.
 */
class ErrorPairRoomTest {

    private val first = FakeGraph()
    private val heater = AssetId("asset-example-heater")
    private val helper = "input_select.example_heater_status"

    @After fun close() = first.db.close()

    private fun rebuilt(): FakeGraph = FakeGraph(db = first.db, secretStore = first.secretStore)

    private suspend fun FakeGraph.connect(token: String) = saveHaConnection.run(
        "http://192.168.0.10:8123", Secret(token), null, NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi", null,
    )

    /** A validated, enabled status binding at revision 1 whose delivery stands at desired revision 7. */
    private suspend fun seed(): PublishBinding {
        first.assets.upsert(Asset(id = heater, name = "Example Heater", createdAt = 500L, updatedAt = 500L))
        val connection = first.connect("fictional-token-1")
        val binding = PublishBinding(
            id = "binding-1", assetId = heater, connectionId = connection.id, kind = PublishKind.EQUIPMENT_STATUS,
            entityId = helper, enabled = true, revision = 1, activatedInstallationId = "installation-a",
            validatedAt = 900L, validatedRevision = 1, boundaryEntityId = null, createdAt = 900L, updatedAt = 900L,
        )
        first.publishBindings.insert(binding)
        first.publishDeliveries.upsert(PublishDelivery(binding.id, desiredValue = "UP", desiredRevision = 7, desiredAt = 950L))
        return binding
    }

    private suspend fun FakeGraph.writeForbidden(): Boolean {
        val binding = publishBindings.get("binding-1")!!
        val delivery = publishDeliveries.get("binding-1")!!
        val record = DeliverySteps.failed(binding, delivery, PublishErrorKind.WRITE_FORBIDDEN, "403", 1_100L)
        return publishDeliveries.record(record.bindingRevision, record.delivery)
    }

    private suspend fun FakeGraph.selected(): Boolean =
        DeliverySteps.selects(publishBindings.get("binding-1")!!, publishDeliveries.get("binding-1")!!, DeliveryTrigger.CHANGE)

    @Test
    fun historyAIsRetriedAndHistoryBIsSkippedAfterARebuild() = runBlocking<Unit> {
        seed()
        // A: the failure at (1, 7), then the re-authorisation moves the pair to (2, 7).
        assertTrue(first.writeForbidden())
        first.connect("fictional-token-2")
        val a = rebuilt()
        assertEquals(2L, a.publishBindings.get("binding-1")!!.revision)
        assertNull(a.publishDeliveries.get("binding-1")!!.errorKind)
        assertTrue("history A is retried", a.selected())

        // B: re-authorised first, then the failure at (2, 7).
        assertTrue(a.writeForbidden())
        val b = rebuilt()
        val stored = b.publishDeliveries.get("binding-1")!!
        assertEquals(PublishErrorKind.WRITE_FORBIDDEN, stored.errorKind)
        assertEquals(2L, stored.errorBindingRevision)
        assertEquals(7L, stored.errorDesiredRevision)
        assertTrue(stored.errorIsCurrent(b.publishBindings.get("binding-1")!!))
        assertFalse("history B is skipped", b.selected())
    }

    @Test
    fun aDesiredChangeARevalidationOrASecondFailureAtThePair() = runBlocking<Unit> {
        val binding = seed()
        assertTrue(first.writeForbidden())
        assertTrue(first.writeForbidden())
        val twice = rebuilt()
        assertTrue(twice.publishDeliveries.get(binding.id)!!.errorIsCurrent(twice.publishBindings.get(binding.id)!!))
        assertFalse(twice.selected())

        // A new desired value: the error is cleared with its pair, and the attempt count starts again.
        val attempted = twice.publishDeliveries.get(binding.id)!!.copy(attemptsAtRevision = 3)
        assertTrue(twice.publishDeliveries.record(binding.revision, attempted))
        assertTrue(twice.publishDeliveries.writeDesired(binding.id, binding.revision, 7, "DOWN", 1_200L))
        val desired = rebuilt().publishDeliveries.get(binding.id)!!
        assertNull(desired.errorKind)
        assertEquals(0, desired.attemptsAtRevision)
        assertEquals(8L, desired.desiredRevision)

        // A Check helper whose read validates clears it too.
        assertTrue(twice.writeForbidden())
        val connection = twice.haConnections.get()!!
        val remote = RemoteEntity(helper, "UP", listOf("UP", "DEGRADED", "DOWN"), null, null)
        val fresh = ValidationResult(helper, remote, connection.id, connection.configGeneration, binding.revision, twice.now)
        twice.revalidatePublishing.run(heater, PublishKind.EQUIPMENT_STATUS, fresh, null)
        val checked = rebuilt()
        assertNull(checked.publishDeliveries.get(binding.id)!!.errorKind)
        assertTrue(checked.selected())
    }

    @Test
    fun aRecordWritesNeitherTheDesiredValueTheBoundaryCacheNorTheRequestStamp() = runBlocking<Unit> {
        val binding = seed()
        val stored = first.publishDeliveries.get(binding.id)!!.copy(
            boundaryValue = "2026-05-01", boundaryReadAt = 960L, boundaryBindingRevision = 1,
        )
        first.publishDeliveries.upsert(stored)
        first.publishDeliveries.stampRequest(listOf(binding.id), "request-1", 970L)

        val tampered = stored.copy(
            desiredValue = "DOWN", desiredAt = 1L, boundaryValue = "1999-01-01", boundaryReadAt = 2L,
            boundaryBindingRevision = 9, lastRequestId = "request-other", lastRequestAt = 3L,
            lastVerifiedValue = "UP", lastVerifiedAt = 1_000L, verifiedBindingRevision = 1, verifiedDesiredRevision = 7,
        )
        assertTrue(first.publishDeliveries.record(binding.revision, tampered))
        val after = rebuilt().publishDeliveries.get(binding.id)!!
        val expected = DeliverySteps.commit(
            binding,
            stored.copy(lastRequestId = "request-1", lastRequestAt = 970L),
            DeliveryRecord(binding.revision, 7, tampered),
        )!!.delivery
        assertEquals(expected, after)
        assertEquals("UP", after.desiredValue)
        assertEquals("2026-05-01", after.boundaryValue)
        assertEquals("request-1", after.lastRequestId)
    }
}
