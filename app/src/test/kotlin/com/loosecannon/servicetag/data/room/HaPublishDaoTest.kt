package com.loosecannon.servicetag.data.room

import androidx.sqlite.SQLiteException
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.data.room.dao.HaPublishControlRow
import com.loosecannon.servicetag.data.room.dao.HaPublishObligationRow
import com.loosecannon.servicetag.data.room.entities.HaConnectionEntity
import com.loosecannon.servicetag.data.room.entities.HaPublishBindingEntity
import com.loosecannon.servicetag.data.room.entities.HaPublishDecommissionEntity
import com.loosecannon.servicetag.data.room.entities.HaPublishDeliveryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #113/#114 S1 (C4, C5; rows 23–24) — the three device-local publishing tables through their DAOs, on a fresh v22
 * database. Row 23: one binding per asset and kind; one **enabled** binding per `(connection, entity)` through the
 * claim column, which is null while disabled, so disabled bindings may share a target (F-9); the connection's delete
 * takes its bindings and their deliveries but no tombstone; the asset's delete leaves the binding (a soft link, C34).
 * Row 24: the binding update is a compare-and-set on its revision; the delivery record a compare-and-set on the
 * binding's revision **and** the desired revision; every write that moves either clears the error and its pair (A04);
 * the control flow ignores bookkeeping writes; a tombstone's revision moves only when an obligation is written or
 * merged. The asset, connection and entity ids are fictional.
 */
class HaPublishDaoTest {

    private val db = inMemoryDb()
    private val assets = RoomAssetRepository(db.assetDao())
    private val connections = db.haConnectionDao()
    private val bindings = db.haPublishBindingDao()
    private val deliveries = db.haPublishDeliveryDao()
    private val tombstones = db.haPublishDecommissionDao()

    @After fun close() = db.close()

    private fun connection(id: String = CONNECTION) = HaConnectionEntity(
        id = id, baseUrl = "http://192.168.0.10:8123", cadence = "DAILY", networkEligibility = "HOME_NETWORK_ONLY",
        homeNetworkSsid = "ExampleHomeWifi", backgroundChecks = "OFF", createdAt = 1_000L, updatedAt = 1_000L,
    )

    private fun binding(
        id: String,
        assetId: String = HEATER,
        kind: String = STATUS,
        entityId: String = STATUS_ENTITY,
        enabled: Boolean = true,
        revision: Long = 1,
        connectionId: String = CONNECTION,
    ) = HaPublishBindingEntity(
        id = id, assetId = assetId, connectionId = connectionId, kind = kind, entityId = entityId, enabled = enabled,
        revision = revision, activatedInstallationId = if (enabled) INSTALLATION else null, validatedAt = 1_500L,
        validatedRevision = revision, boundaryEntityId = if (kind == DATE) BOUNDARY_ENTITY else null,
        createdAt = 1_500L, updatedAt = 1_500L,
    )

    private fun delivery(bindingId: String, desiredRevision: Long = 1, desiredValue: String? = "UP") =
        HaPublishDeliveryEntity(bindingId = bindingId, desiredValue = desiredValue, desiredRevision = desiredRevision, desiredAt = 1_600L)

    private fun tombstone(id: String, entityId: String = DATE_ENTITY, revision: Long = 1) = HaPublishDecommissionEntity(
        id = id, connectionId = CONNECTION, entityId = entityId, assetId = HEATER, installationId = INSTALLATION,
        state = "PENDING", requestedAt = 1_700L, errorKind = null, errorAt = null, revision = revision,
    )

    private suspend fun seed() {
        for (id in listOf(HEATER, BOILER, DEHUMIDIFIER)) {
            assets.upsert(Asset(id = AssetId(id), name = "Example $id", createdAt = 500L, updatedAt = 500L))
        }
        connections.insert(connection())
    }

    /** The bundled driver's exception carries no message on the JVM (`AssetDao`'s note), so the type is the proof. */
    private suspend fun assertRefused(what: String, write: suspend () -> Unit) {
        val before = bindings.all()
        val thrown = runCatching { write() }.exceptionOrNull()
        assertTrue("expected the schema to refuse $what, got $thrown", thrown is SQLiteException)
        assertEquals("the refused write wrote nothing", before, bindings.all())
    }

    // --- row 23: the keys and the claim ------------------------------------------------------------

    @Test
    fun oneBindingPerAssetAndKind() = runTest {
        seed()
        bindings.insert(binding("b-status"))

        assertRefused("a second status binding for the asset") {
            bindings.insert(binding("b-status-2", entityId = "input_select.example_other_status"))
        }
        bindings.insert(binding("b-date", kind = DATE, entityId = DATE_ENTITY))
        bindings.insert(binding("b-boiler", assetId = BOILER, entityId = "input_select.example_boiler_equipment_status"))

        assertEquals("by kind", listOf("b-status", "b-date"), bindings.forAsset(HEATER).map { it.id })
        assertEquals(listOf("b-boiler", "b-date", "b-status"), bindings.all().map { it.id })
    }

    @Test
    fun twoEnabledBindingsCannotClaimOneEntity() = runTest {
        seed()
        bindings.insert(binding("b-heater"))
        assertEquals("an enabled binding claims its entity", STATUS_ENTITY, bindings.get("b-heater")?.claimEntityId)

        assertRefused("a second enabled claim on the helper") {
            bindings.insert(binding("b-boiler", assetId = BOILER))
        }

        // a disabled one may sit on it, but enabling it is the same refusal
        bindings.insert(binding("b-boiler", assetId = BOILER, enabled = false))
        assertRefused("enabling a second claim on the helper") {
            bindings.update(binding("b-boiler", assetId = BOILER, enabled = true, revision = 2))
        }
        assertEquals("the refused update wrote nothing", false, bindings.get("b-boiler")?.enabled)

        // the same entity on another connection is another helper
        connections.insert(connection("ha-connection-other"))
        bindings.insert(binding("b-other", assetId = DEHUMIDIFIER, connectionId = "ha-connection-other"))
        assertEquals(STATUS_ENTITY, bindings.get("b-other")?.claimEntityId)
    }

    @Test
    fun disabledBindingsMayShareATarget() = runTest {
        seed()
        bindings.insert(binding("b-heater"))
        bindings.insert(binding("b-boiler", assetId = BOILER, enabled = false))
        bindings.insert(binding("b-dehumidifier", assetId = DEHUMIDIFIER, enabled = false))

        assertEquals(
            listOf("b-boiler|null", "b-dehumidifier|null", "b-heater|$STATUS_ENTITY"),
            bindings.all().map { "${it.id}|${it.claimEntityId}" },
        )
        assertEquals(3, bindings.byTarget(CONNECTION, STATUS_ENTITY).size)

        // disabling the holder frees the claim for the next one
        assertTrue(bindings.update(binding("b-heater", enabled = false, revision = 2)))
        assertNull(bindings.get("b-heater")?.claimEntityId)
        assertTrue(bindings.update(binding("b-boiler", assetId = BOILER, enabled = true, revision = 2)))
        assertEquals(STATUS_ENTITY, bindings.get("b-boiler")?.claimEntityId)
    }

    @Test
    fun deletingTheConnectionTakesItsBindingsButNoTombstone() = runTest {
        seed()
        bindings.insert(binding("b-status"))
        bindings.insert(binding("b-date", kind = DATE, entityId = DATE_ENTITY))
        deliveries.insert(delivery("b-status"))
        deliveries.insert(delivery("b-date", desiredValue = "1970-01-01"))
        tombstones.insertOrMerge(tombstone("t-old", entityId = "input_datetime.example_old_service_done"))

        connections.delete(CONNECTION)

        assertEquals(emptyList<HaPublishBindingEntity>(), bindings.all())
        assertEquals("the deliveries went with their bindings", emptyList<HaPublishDeliveryEntity>(), deliveries.all())
        assertEquals("the obligation outlives the connection", listOf("t-old"), tombstones.all().map { it.id })
    }

    @Test
    fun deletingTheAssetLeavesTheBinding() = runTest {
        seed()
        bindings.insert(binding("b-date", kind = DATE, entityId = DATE_ENTITY))
        deliveries.insert(delivery("b-date", desiredValue = "2026-09-20"))

        assets.delete(AssetId(HEATER))

        assertNull(assets.get(AssetId(HEATER)))
        assertEquals("the binding is left for the projector", listOf("b-date"), bindings.all().map { it.id })
        assertEquals("2026-09-20", deliveries.get("b-date")?.desiredValue)
    }

    // --- row 24: the compare-and-sets, the clearing rule and the flows ------------------------------

    @Test
    fun aStaleRevisionUpdateAnswersFalse() = runTest {
        seed()
        bindings.insert(binding("b-status", revision = 1))

        assertFalse("a jump of two", bindings.update(binding("b-status", entityId = "input_select.x", revision = 3)))
        assertFalse("no move at all", bindings.update(binding("b-status", entityId = "input_select.x", revision = 1)))
        assertEquals(STATUS_ENTITY, bindings.get("b-status")?.entityId)

        assertTrue(bindings.update(binding("b-status", enabled = false, revision = 2)))
        assertFalse("the same next revision twice", bindings.update(binding("b-status", revision = 2)))
        assertEquals("a stale revision clears no validation", 0, bindings.clearValidation("b-status", atRevision = 99))
        assertEquals(2L, bindings.get("b-status")?.revision)
        assertEquals(false, bindings.get("b-status")?.enabled)
    }

    @Test
    fun aDeliveryRecordAtAMovedDesiredRevisionAnswersFalse() = runTest {
        seed()
        bindings.insert(binding("b-status"))
        deliveries.insert(delivery("b-status", desiredRevision = 1, desiredValue = "UP"))
        val snapshot = deliveries.get("b-status")!!
        assertTrue(deliveries.writeDesired("b-status", bindingRevision = 1, expectedDesiredRevision = 1, desiredValue = "DOWN", desiredAt = 1_800L))
        val desired = deliveries.get("b-status")!!
        assertEquals("DOWN" to 2L, desired.desiredValue to desired.desiredRevision)

        // the sender's result for the older desired value is dropped whole
        val stale = snapshot.copy(lastVerifiedValue = "UP", lastVerifiedAt = 1_900L, verifiedBindingRevision = 1, verifiedDesiredRevision = 1)
        assertFalse(deliveries.record(bindingRevision = 1, e = stale))
        assertEquals("no field moved", desired, deliveries.get("b-status"))

        // and so is one at a moved generation
        val current = deliveries.get("b-status")!!
        assertTrue(bindings.update(binding("b-status", revision = 2)))
        assertFalse(deliveries.record(bindingRevision = 1, e = current.copy(lastVerifiedValue = "DOWN")))
        assertNull(deliveries.get("b-status")?.lastVerifiedValue)

        // the current pair records
        assertTrue(deliveries.record(bindingRevision = 2, e = current.copy(lastVerifiedValue = "DOWN", lastVerifiedAt = 2_000L)))
        val recorded = deliveries.get("b-status")!!
        assertEquals("DOWN", recorded.lastVerifiedValue)
        assertEquals("a record never moves the desired value", "DOWN" to 2L, recorded.desiredValue to recorded.desiredRevision)

        // the desired write is itself a compare-and-set on both
        assertFalse(deliveries.writeDesired("b-status", bindingRevision = 2, expectedDesiredRevision = 1, desiredValue = "UP", desiredAt = 2_100L))
        assertFalse(deliveries.writeDesired("b-status", bindingRevision = 1, expectedDesiredRevision = 2, desiredValue = "UP", desiredAt = 2_100L))
        assertEquals("DOWN", deliveries.get("b-status")?.desiredValue)
    }

    @Test
    fun aPairMovingWriteClearsTheErrorAndItsPair() = runTest {
        seed()
        bindings.insert(binding("b-status"))
        deliveries.insert(delivery("b-status"))
        val failed = deliveries.get("b-status")!!.copy(
            errorKind = "UNREACHABLE", errorDetail = "publish-detail-x", errorAt = 1_900L, attemptsAtRevision = 2,
            lastAttemptAt = 1_900L,
        )

        // an error is recorded with the pair it happened at
        assertTrue(deliveries.record(bindingRevision = 1, e = failed))
        assertEquals("UNREACHABLE|publish-detail-x|1900|1|1", errorOf("b-status"))
        assertEquals(2, deliveries.get("b-status")?.attemptsAtRevision)

        // a new desired value clears it, and the attempts count from zero again
        assertTrue(deliveries.writeDesired("b-status", bindingRevision = 1, expectedDesiredRevision = 1, desiredValue = "DOWN", desiredAt = 2_000L))
        assertEquals("null|null|null|null|null", errorOf("b-status"))
        assertEquals(0, deliveries.get("b-status")?.attemptsAtRevision)

        // a moved generation clears it too
        assertTrue(deliveries.record(bindingRevision = 1, e = deliveries.get("b-status")!!.copy(errorKind = "HTTP_ERROR", errorAt = 2_100L)))
        assertEquals("HTTP_ERROR|null|2100|1|2", errorOf("b-status"))
        assertTrue(bindings.update(binding("b-status", revision = 2)))
        assertEquals("null|null|null|null|null", errorOf("b-status"))

        // a record with no error writes no pair
        assertTrue(deliveries.record(bindingRevision = 2, e = deliveries.get("b-status")!!.copy(lastAttemptAt = 2_200L)))
        assertEquals("null|null|null|null|null", errorOf("b-status"))
    }

    @Test
    fun observeControlsIgnoresBookkeepingWrites() = runBlocking {
        seed()
        bindings.insert(binding("b-status"))
        deliveries.insert(delivery("b-status"))
        val controls = Channel<List<HaPublishControlRow>>(Channel.UNLIMITED)
        val raw = Channel<List<HaPublishBindingEntity>>(Channel.UNLIMITED)
        val watchers = listOf(
            launch(Dispatchers.Default) { bindings.observeControls().collect { controls.send(it) } },
            launch(Dispatchers.Default) { bindings.observeAll().collect { raw.send(it) } },
        )
        try {
            val first = withTimeout(TIMEOUT) { controls.receive() }
            assertEquals(
                listOf(HaPublishControlRow("b-status", HEATER, STATUS, true, 1, STATUS_ENTITY, null, INSTALLATION)),
                first,
            )
            withTimeout(TIMEOUT) { raw.receive() }

            // bookkeeping: a validator failure clears the validation, a sender records an attempt
            assertEquals(1, bindings.clearValidation("b-status", atRevision = 1))
            assertTrue(deliveries.record(bindingRevision = 1, e = deliveries.get("b-status")!!.copy(lastAttemptAt = 1_900L, needsDelivery = true)))
            withTimeout(TIMEOUT) { while (raw.receive().single().validatedRevision != null) Unit }
            delay(SETTLE_MS)
            assertNull("a bookkeeping write emits nothing new", controls.tryReceive().getOrNull())

            // a control write does
            assertTrue(bindings.update(binding("b-status", enabled = false, revision = 2)))
            val next = withTimeout(TIMEOUT) { controls.receive() }
            assertEquals(listOf(false to 2L), next.map { it.enabled to it.revision })
        } finally {
            watchers.forEach { it.cancel() }
        }
    }

    @Test
    fun anObligationsRevisionMovesOnlyOnInsertOrMerge() = runTest {
        seed()
        assertEquals("t-1", tombstones.insertOrMerge(tombstone("t-1", revision = 1)))
        assertEquals(listOf(HaPublishObligationRow("t-1", 1)), tombstones.observeObligations().first())

        // the drain's error and state writes are a compare-and-set that never moves it
        assertEquals(1, tombstones.updateAtRevision("t-1", revision = 1, state = "PENDING", errorKind = "UNREACHABLE", errorAt = 1_800L))
        assertEquals(1L, tombstones.get("t-1")?.revision)
        assertEquals(1, tombstones.updateAtRevision("t-1", revision = 1, state = "UNDELIVERABLE", errorKind = "AUTH_REFUSED", errorAt = 1_900L))
        assertEquals(1L, tombstones.get("t-1")?.revision)
        assertEquals("an undeliverable row is no obligation to drain", emptyList<HaPublishObligationRow>(), tombstones.observeObligations().first())

        // a second obligation on the helper merges into the first: its id and request time kept, the revision moved
        val merged = tombstones.insertOrMerge(
            tombstone("t-2").copy(assetId = BOILER, installationId = "example-installation-2", requestedAt = 2_500L),
        )
        assertEquals("t-1", merged)
        val row = tombstones.get("t-1")!!
        assertEquals(
            "PENDING|2|1700|$BOILER|example-installation-2|null|null",
            "${row.state}|${row.revision}|${row.requestedAt}|${row.assetId}|${row.installationId}|${row.errorKind}|${row.errorAt}",
        )
        assertEquals(listOf("t-1"), tombstones.all().map { it.id })
        assertEquals(listOf(HaPublishObligationRow("t-1", 2)), tombstones.observeObligations().first())

        // a drain that read the old revision writes nothing
        assertEquals(0, tombstones.updateAtRevision("t-1", revision = 1, state = "UNDELIVERABLE", errorKind = "AUTH_REFUSED", errorAt = 2_600L))
        assertEquals("PENDING", tombstones.get("t-1")?.state)

        // another helper is another obligation, and a delete is by id
        assertEquals("t-3", tombstones.insertOrMerge(tombstone("t-3", entityId = "input_datetime.example_boiler_service_done")))
        assertEquals(1, tombstones.delete("t-1"))
        assertEquals(listOf("t-3"), tombstones.all().map { it.id })
    }

    @Test
    fun theRequestStampWritesItsTwoColumnsOnly() = runTest {
        seed()
        bindings.insert(binding("b-status"))
        bindings.insert(binding("b-date", kind = DATE, entityId = DATE_ENTITY))
        deliveries.insert(delivery("b-status"))
        deliveries.insert(delivery("b-date", desiredValue = "1970-01-01"))
        val before = deliveries.all()

        assertEquals("an empty list stamps nothing", 0, deliveries.stampRequest(emptyList(), "example-request-0", 2_900L))
        assertEquals(before, deliveries.all())
        assertEquals(2, deliveries.stampRequest(listOf("b-status", "b-date"), "example-request-1", 3_000L))

        assertEquals(
            before.map { it.copy(lastRequestId = "example-request-1", lastRequestAt = 3_000L) },
            deliveries.all(),
        )
    }

    private suspend fun errorOf(bindingId: String): String = deliveries.get(bindingId)!!.let {
        "${it.errorKind}|${it.errorDetail}|${it.errorAt}|${it.errorBindingRevision}|${it.errorDesiredRevision}"
    }

    private companion object {
        const val CONNECTION = "ha-connection-example"
        const val HEATER = "asset-heater"
        const val BOILER = "asset-boiler"
        const val DEHUMIDIFIER = "asset-dehumidifier"
        const val STATUS = "EQUIPMENT_STATUS"
        const val DATE = "SEASON_SERVICE_DATE"
        const val STATUS_ENTITY = "input_select.example_heater_equipment_status"
        const val DATE_ENTITY = "input_datetime.example_heater_service_done"
        const val BOUNDARY_ENTITY = "input_datetime.example_heater_season_ended"
        const val INSTALLATION = "example-installation-1"
        const val TIMEOUT = 5_000L
        const val SETTLE_MS = 300L
    }
}
