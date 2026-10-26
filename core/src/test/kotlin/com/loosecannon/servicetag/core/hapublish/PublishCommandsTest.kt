package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.BOILER
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.DATE
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.DATE_2
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.HEATER
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.INSTALLATION
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.IN_SEASON
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.SEASON_ENDED
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.STATUS
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.STATUS_2
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.ScriptedPublishTransport
import com.loosecannon.servicetag.core.testing.tombstoneOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #113/#114 (C25, C26, C27, C9a, F-1, F-9, R-1, N-2; rows 60, 60a, 63, 64, 93, 94, 96, 97) — the publishing commands:
 * one write each, no HTTP inside; the fresh read re-validated and its provenance checked in the same transaction; the
 * claim per enabled binding; a date helper let go only with a tombstone for it in the same write; and what a sender
 * pass after them sends. Every asset, helper and address is fictional.
 */
class PublishCommandsTest {

    private fun harness(): PublishHarness = PublishHarness().apply {
        asset(HEATER)
        asset(BOILER)
    }

    private suspend fun PublishHarness.refused(reason: PublishRefusal, field: String = FIELD_ENTITY_ID, write: suspend () -> Unit): PublishRefused {
        val before = bindings.rows.toMap() to tombstones.rows.toMap()
        val thrown = assertFailsWith<PublishRefused> { write() }
        assertEquals(reason, thrown.reason)
        assertEquals(field, thrown.field)
        assertEquals(before, bindings.rows.toMap() to tombstones.rows.toMap(), "a refusal writes nothing")
        return thrown
    }

    // Row 60 (C25, AC113-1/2).

    @Test
    fun enableValidatesTheFreshReadAndStampsTheInstallation() = runTest {
        val p = harness()
        p.connect()
        val written = p.enableStatus()
        assertTrue(written.enabled)
        assertEquals(1, written.revision)
        assertEquals(1, written.validatedRevision)
        assertEquals(p.h.now, written.validatedAt)
        assertEquals(INSTALLATION, written.activatedInstallationId)
        assertEquals(written, p.bindingOf(HEATER, PublishKind.EQUIPMENT_STATUS))
        assertNull(p.deliveries.get(written.id)?.desiredValue, "the projector computes the first desired value")

        // The caller's "validated" is never trusted: the command validates the remote it is handed.
        val bad = p.read(STATUS_2, BOILER).let { it.copy(remote = it.remote.copy(options = listOf("UP", "DOWN"))) }
        p.refused(PublishRefusal.OPTIONS_MISMATCH) {
            p.enable.run(AssetId(BOILER), PublishKind.EQUIPMENT_STATUS, STATUS_2, bad, null, INSTALLATION)
        }
        val timed = p.read(DATE, BOILER).let { it.copy(remote = it.remote.copy(hasTime = true)) }
        p.refused(PublishRefusal.NOT_DATE_ONLY) {
            p.enable.run(AssetId(BOILER), PublishKind.SEASON_SERVICE_DATE, DATE, timed, null, INSTALLATION)
        }
        p.refused(PublishRefusal.WRONG_DOMAIN) {
            p.enable.run(AssetId(BOILER), PublishKind.EQUIPMENT_STATUS, DATE, p.read(DATE, BOILER), null, INSTALLATION)
        }
        p.refused(PublishRefusal.BAD_ENTITY_ID) {
            p.enable.run(AssetId(BOILER), PublishKind.EQUIPMENT_STATUS, "Not An Id", bad, null, INSTALLATION)
        }
    }

    @Test
    fun aStaleOrForeignReadIsRefused() = runTest {
        val p = harness()
        val connection = p.connect()
        val read = p.read(STATUS)
        p.refused(PublishRefusal.STALE_READ) {
            p.enable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS_2, read, null, INSTALLATION)
        }
        p.refused(PublishRefusal.STALE_READ) {
            p.enable.run(
                AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, read.copy(connectionId = "another-connection"),
                null, INSTALLATION,
            )
        }
        p.refused(PublishRefusal.NO_CONNECTION) {
            p.forget.run()
            p.enable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, read, null, INSTALLATION)
        }
        assertEquals(connection.id, read.connectionId)
    }

    @Test
    fun enableWithNoConditionWaits() = runTest {
        val p = harness()
        p.connect()
        val written = p.enableStatus()
        assertEquals(DeliveryPlan.Skip(PublishState.WAITING_FOR_SOURCE), p.pass.run(written.id))
        assertTrue(p.transport.posts.isEmpty(), "nothing is sent while no condition is recorded")
    }

    // Row 60a (A09, F, C9a, I32).

    @Test
    fun aReadRacedByAnAddressChangeForgetOrAnotherSetupIsStale() = runTest {
        val p = harness()
        p.connect()

        val beforeAddress = p.read(STATUS)
        p.connect(baseUrl = "http://192.168.0.11:8123")
        p.refused(PublishRefusal.STALE_READ) {
            p.enable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, beforeAddress, null, INSTALLATION)
        }

        val beforeForget = p.read(STATUS)
        p.forget.run()
        p.connect()
        p.refused(PublishRefusal.STALE_READ) {
            p.enable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, beforeForget, null, INSTALLATION)
        }

        // Another setup of the same entity commits first: the binding's revision moved since the read.
        val first = p.read(STATUS)
        val second = p.read(STATUS)
        p.enable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, first, null, INSTALLATION)
        p.refused(PublishRefusal.STALE_READ) {
            p.enable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, second, null, INSTALLATION)
        }

        // A target change since the read.
        val beforeChange = p.read(STATUS_2)
        p.change.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS_2, p.read(STATUS_2), null, INSTALLATION)
        p.refused(PublishRefusal.STALE_READ) {
            p.change.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS_2, beforeChange, null, INSTALLATION)
        }

        // One older than ten minutes.
        val old = p.read(STATUS, BOILER)
        p.later(VALIDATION_MAX_AGE_MILLIS + 1)
        p.refused(PublishRefusal.STALE_READ) {
            p.enable.run(AssetId(BOILER), PublishKind.EQUIPMENT_STATUS, STATUS, old, null, INSTALLATION)
        }
        assertNull(p.bindingOf(BOILER, PublishKind.EQUIPMENT_STATUS), "no writer was enabled")
    }

    @Test
    fun twoSavesInOneClockMillisecondStillMakeItStale() = runTest {
        val p = harness()
        p.connect()
        val read = p.read(STATUS)
        // A cadence-only save in the read's own millisecond: no address, token or network change.
        p.save.run(
            "http://192.168.0.10:8123", null, SyncCadence.WEEKLY, NetworkEligibility.HOME_NETWORK_ONLY,
            "ExampleHomeWifi", BackgroundChecks.OFF,
        )
        assertEquals(read.readAt, p.connections.get()!!.updatedAt)
        p.refused(PublishRefusal.STALE_READ) {
            p.enable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, read, null, INSTALLATION)
        }
    }

    @Test
    fun anHaDrivenManualAssetNeedsABoundaryHelperOtherThanItsTarget() = runTest {
        val p = harness()
        p.asset(HEATER, SeasonMode.MANUAL)
        val connection = p.connect()
        p.h.link(HEATER) { it.copy(connectionId = connection.id, entityId = IN_SEASON) }

        p.refused(PublishRefusal.BAD_ENTITY_ID, FIELD_BOUNDARY_ENTITY_ID) { p.enableDate() }
        p.refused(PublishRefusal.BOUNDARY_IS_TARGET, FIELD_BOUNDARY_ENTITY_ID) { p.enableDate(boundary = DATE) }
        p.transport.helpers["input_datetime.example_heater_season_ended_at"] = p.transport.helpers.getValue(SEASON_ENDED)
            .copy(entityId = "input_datetime.example_heater_season_ended_at", hasTime = true)
        p.refused(PublishRefusal.NOT_DATE_ONLY, FIELD_BOUNDARY_ENTITY_ID) {
            p.enableDate(boundary = "input_datetime.example_heater_season_ended_at")
        }

        val written = p.enableDate(boundary = SEASON_ENDED)
        assertEquals(SEASON_ENDED, written.boundaryEntityId)
        val cache = p.deliveries.get(written.id)!!.boundaryCache
        assertEquals(BoundaryCache("2026-05-01", p.h.now, written.revision), cache)
    }

    @Test
    fun aCalendarAssetStoresNoBoundaryHelper() = runTest {
        val p = harness()
        p.connect()
        val written = p.enableDate(boundary = SEASON_ENDED)
        assertNull(written.boundaryEntityId)
        assertEquals(BoundaryCache(null, null, null), p.deliveries.get(written.id)!!.boundaryCache)
    }

    // Row 63 (G1, F-9, AC113-8).

    @Test
    fun aSecondAssetOnTheSameHelperIsTargetInUse() = runTest {
        val p = harness()
        p.connect()
        p.enableStatus(HEATER)
        val thrown = p.refused(PublishRefusal.TARGET_IN_USE) { p.enableStatus(BOILER) }
        assertEquals(AssetId(HEATER), thrown.holderAssetId)
    }

    @Test
    fun onePerAssetAndKind() = runTest {
        val p = harness()
        p.connect()
        p.enableStatus(HEATER, STATUS)
        p.enableStatus(HEATER, STATUS_2)
        val held = p.bindings.forAsset(AssetId(HEATER))
        assertEquals(1, held.size)
        assertEquals(STATUS_2, held.single().entityId)
        assertEquals(2, held.single().revision)
    }

    @Test
    fun enableOverAPendingTombstoneSupersedesIt() = runTest {
        val p = harness()
        val connection = p.connect()
        for (state in DecommissionState.entries) {
            p.tombstones.rows.clear()
            p.bindings.rows.clear()
            p.deliveries.rows.clear()
            p.tombstones.upsertMerging(tombstoneOf("t-old", DATE, connection.id, assetId = BOILER, state = state))
            p.enableDate(HEATER, DATE)
            assertNull(p.tombstones.byTarget(connection.id, DATE), "the new owner supersedes a $state tombstone")
        }
    }

    @Test
    fun anotherAssetsDisabledBindingDoesNotBlockAndReEnablingItIsTargetInUse() = runTest {
        val p = harness()
        p.connect()
        p.enableStatus(HEATER)
        p.disable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS)
        p.enableStatus(BOILER)
        val thrown = p.refused(PublishRefusal.TARGET_IN_USE) { p.enableStatus(HEATER) }
        assertEquals(AssetId(BOILER), thrown.holderAssetId)
    }

    @Test
    fun aDisabledBindingWhoseAssetIsDeletedIsRetired() = runTest {
        val p = harness()
        p.connect()
        val orphan = p.enableStatus(HEATER)
        p.disable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS)
        p.h.raw.assets.delete(AssetId(HEATER))
        assertNotNull(p.bindings.get(orphan.id), "a soft link: the asset's delete leaves the row")
        // It holds no claim, so another asset may take the helper; the retirement deletes it and writes no tombstone.
        p.enableStatus(BOILER)
        p.bindings.delete(orphan.id)
        assertNull(p.bindings.get(orphan.id))
        assertNull(p.deliveries.get(orphan.id))
        assertTrue(p.tombstones.rows.isEmpty())
    }

    // Row 64 (C25, K12).

    @Test
    fun disableAndChangeTargetMoveTheGeneration() = runTest {
        val p = harness()
        p.connect()
        assertEquals(1, p.enableStatus().revision)
        val changed = p.change.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS_2, p.read(STATUS_2), null, INSTALLATION)
        assertEquals(2, changed.revision)
        assertEquals(STATUS_2, changed.entityId)
        val disabled = p.disable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS)
        assertEquals(3, disabled.revision)
        assertFalse(disabled.enabled)
    }

    @Test
    fun theDateKindWritesATombstoneInTheSameWrite() = runTest {
        val p = harness()
        val connection = p.connect()
        val written = p.enableDate()

        p.tombstones.failNextWrite = true
        assertFailsWith<RiggedFailure> { p.disable.run(AssetId(HEATER), PublishKind.SEASON_SERVICE_DATE) }
        assertEquals(written, p.bindings.get(written.id), "a failed tombstone write rolls the disable back with it")

        p.disable.run(AssetId(HEATER), PublishKind.SEASON_SERVICE_DATE)
        val owed = assertNotNull(p.tombstones.byTarget(connection.id, DATE))
        assertEquals(DecommissionState.PENDING, owed.state)
        assertEquals(INSTALLATION, owed.installationId)
        assertEquals(AssetId(HEATER), owed.assetId)

        val moved = p.enableDate(HEATER, DATE)
        p.change.run(AssetId(HEATER), PublishKind.SEASON_SERVICE_DATE, DATE_2, p.read(DATE_2), null, INSTALLATION)
        assertNotNull(p.tombstones.byTarget(connection.id, DATE), "a target change owes the old helper 1970-01-01")
        assertEquals(DATE_2, p.bindings.get(moved.id)!!.entityId)
    }

    @Test
    fun theStatusKindLetsGoSilently() = runTest {
        val p = harness()
        p.connect()
        p.enableStatus()
        p.change.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS_2, p.read(STATUS_2), null, INSTALLATION)
        p.disable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS)
        assertTrue(p.tombstones.rows.isEmpty())
    }

    // Row 93 (F-1, AC114-6).

    @Test
    fun reEnableAfterDecommissionSendsAgain() = runTest {
        val p = harness()
        val connection = p.connect()
        val first = p.enableDate()
        p.project(first, "2026-06-01")
        assertIs<DeliveryPlan.ReadFirst>(p.pass.run(first.id))
        assertEquals("2026-06-01", p.transport.stateOf(DATE))

        p.disable.run(AssetId(HEATER), PublishKind.SEASON_SERVICE_DATE)
        assertEquals(1, p.pass.drainTombstones().size)
        assertEquals(INVALID_SERVICE_DATE, p.transport.stateOf(DATE))
        assertNull(p.tombstones.byTarget(connection.id, DATE))

        val again = p.enableDate()
        p.transport.posts.clear()
        p.pass.run(again.id)
        assertEquals(listOf(ScriptedPublishTransport.Post(HaServiceAction.SET_DATE, DATE, "2026-06-01")), p.transport.posts)
        assertEquals("2026-06-01", p.transport.stateOf(DATE))
        val delivery = p.deliveries.get(again.id)!!
        assertEquals("2026-06-01", delivery.lastPublishedValue)
        assertEquals(again.revision, delivery.publishedBindingRevision)
    }

    // Row 94 (F-1, #113 "never attributed").

    @Test
    fun aTargetChangePublishesToTheNewHelper() = runTest {
        val p = harness()
        p.connect()
        val first = p.enableStatus()
        p.project(first, STATUS_UP)
        p.pass.run(first.id)
        assertEquals(STATUS_UP, p.transport.stateOf(STATUS))

        val moved = p.change.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS_2, p.read(STATUS_2), null, INSTALLATION)
        p.transport.posts.clear()
        p.pass.run(moved.id)
        assertEquals(listOf(ScriptedPublishTransport.Post(HaServiceAction.SELECT_OPTION, STATUS_2, STATUS_UP)), p.transport.posts)
        assertEquals(STATUS_UP, p.transport.stateOf(STATUS), "the old helper keeps the last reported value")
    }

    @Test
    fun aNewTargetShowsNoInheritedPublishedLine() = runTest {
        val p = harness()
        p.connect()
        val first = p.enableStatus()
        p.project(first, STATUS_UP)
        p.pass.run(first.id)
        val published = p.deliveries.get(first.id)!!
        assertEquals(STATUS_UP, published.lastPublishedValue)

        p.change.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS_2, p.read(STATUS_2), null, INSTALLATION)
        val reset = p.deliveries.get(first.id)!!
        val expected = PublishDelivery(
            bindingId = first.id,
            desiredValue = published.desiredValue,
            desiredRevision = published.desiredRevision,
            desiredAt = published.desiredAt,
            attemptsAtRevision = published.attemptsAtRevision,
            lastAttemptAt = published.lastAttemptAt,
        )
        assertEquals(expected, reset, "nothing published to, verified on or read from the old helper is inherited")
    }

    // Rows 96, 97 (R-1, I19).

    @Test
    fun aDisabledBindingsTargetChangeNeverTombstonesAPairAnotherAssetClaims() = runTest {
        val p = harness()
        val connection = p.connect()
        p.enableDate(HEATER, DATE)
        p.disable.run(AssetId(HEATER), PublishKind.SEASON_SERVICE_DATE)
        val boiler = p.enableDate(BOILER, DATE)
        assertNull(p.tombstones.byTarget(connection.id, DATE), "the boiler's Enable superseded the heater's obligation")

        p.change.run(AssetId(HEATER), PublishKind.SEASON_SERVICE_DATE, DATE_2, p.read(DATE_2), null, INSTALLATION)
        assertNull(p.tombstones.byTarget(connection.id, DATE), "no 1970-01-01 is owed over the boiler's date")
        assertTrue(p.bindings.get(boiler.id)!!.enabled)
    }

    @Test
    fun disablingADisabledBindingWritesNothing() = runTest {
        val p = harness()
        p.connect()
        p.enableDate()
        val disabled = p.disable.run(AssetId(HEATER), PublishKind.SEASON_SERVICE_DATE)
        val tombstones = p.tombstones.rows.toMap()
        assertEquals(disabled, p.disable.run(AssetId(HEATER), PublishKind.SEASON_SERVICE_DATE))
        assertEquals(disabled, p.bindingOf(HEATER, PublishKind.SEASON_SERVICE_DATE), "no revision bump")
        assertEquals(tombstones, p.tombstones.rows.toMap(), "no tombstone written or merged")
    }
}
