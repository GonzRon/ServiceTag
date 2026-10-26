package com.loosecannon.servicetag.core.hapublish

import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.HEATER
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.INSTALLATION
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.STATUS
import com.loosecannon.servicetag.core.hapublish.PublishHarness.Companion.STATUS_2
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.testing.tombstoneOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #113/#114 (C25a, I34; row 116) — a plan's identity and the acknowledgement. `planIdOf` moves with every field it
 * covers and never with a helper's state; Enable and Change target given a plan commit only while it still holds —
 * a connection save, Forget and a new connection at the same generation, or a target change since the plan is
 * `STALE_PLAN` with nothing written — and the phone's null plan is unchanged. `DismissDecommission` deletes an
 * UNDELIVERABLE row only. The helpers and ids are fictional.
 */
class PublishPlanTest {

    private val status = RemoteEntity(STATUS, "UP", listOf("UP", "DEGRADED", "DOWN"), null, null)
    private val date = RemoteEntity("input_datetime.example_heater_season_ended", "2026-05-01", null, true, false)

    private fun plan(
        assetId: String = HEATER,
        kind: PublishKind = PublishKind.EQUIPMENT_STATUS,
        action: PublishAction = PublishAction.ENABLE,
        target: String = STATUS,
        boundary: String? = null,
        connectionId: String = "conn-1",
        generation: Long = 1,
        revision: Long = 0,
        targetShape: RemoteEntity = status,
        boundaryShape: RemoteEntity? = null,
    ) = planIdOf(
        AssetId(assetId), kind, action, target, boundary, connectionId, generation, revision, targetShape, boundaryShape,
    )

    @Test
    fun aPlanIsStableAndEachFieldItCoversMovesIt() {
        val base = plan()
        assertEquals(base, plan())
        assertTrue(base.matches(Regex("^[0-9a-f]{64}$")))
        val moved = listOf(
            plan(assetId = "boiler"),
            plan(kind = PublishKind.SEASON_SERVICE_DATE),
            plan(action = PublishAction.CHANGE_TARGET),
            plan(target = STATUS_2),
            plan(boundary = date.entityId),
            plan(connectionId = "conn-2"),
            plan(generation = 2),
            plan(revision = 1),
            plan(targetShape = status.copy(options = status.options!! + "SPARE")),
            plan(targetShape = status.copy(hasTime = true)),
            plan(boundaryShape = date),
            plan(boundary = date.entityId, boundaryShape = date.copy(hasTime = true)),
        )
        moved.forEach { assertNotEquals(base, it) }
        assertEquals(moved.size, moved.toSet().size)
        assertEquals(base, plan(targetShape = status.copy(state = "DOWN")), "a helper's state is not its shape")
        assertEquals(base, plan(targetShape = status.copy(options = status.options!!.reversed())), "options are a set")
    }

    private suspend fun PublishHarness.planFor(
        fresh: ValidationResult,
        action: PublishAction = PublishAction.ENABLE,
    ): String {
        val connection = connections.get()!!
        return planIdOf(
            AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, action, fresh.target, null, connection.id,
            connection.configGeneration, bindingOf(HEATER, PublishKind.EQUIPMENT_STATUS)?.revision ?: 0, fresh.remote, null,
        )
    }

    private suspend fun PublishHarness.stalePlan(write: suspend () -> Unit) {
        val before = bindings.rows.toMap()
        val thrown = assertFailsWith<PublishRefused> { write() }
        assertEquals(PublishRefusal.STALE_PLAN, thrown.reason)
        assertEquals(before, bindings.rows.toMap(), "a stale plan writes nothing")
    }

    @Test
    fun aMatchingPlanCommitsAndAMovedOneIsStale() = runTest {
        val p = PublishHarness().apply { asset(HEATER) }
        p.connect()

        // A connection save between the plan and the commit.
        val fresh = p.read(STATUS)
        val planned = p.planFor(fresh)
        p.save.run(
            "http://192.168.0.10:8123", null, SyncCadence.WEEKLY, NetworkEligibility.HOME_NETWORK_ONLY,
            "ExampleHomeWifi", BackgroundChecks.OFF,
        )
        p.stalePlan {
            p.enable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, p.read(STATUS), null, INSTALLATION, planned)
        }

        // Forget and a new connection at the same generation: only the connection's id tells them apart.
        val before = p.connections.get()!!
        val again = p.read(STATUS)
        val plannedBeforeForget = p.planFor(again)
        p.forget.run()
        p.connect()
        repeat((before.configGeneration - p.connections.get()!!.configGeneration).toInt()) {
            p.save.run(
                "http://192.168.0.10:8123", null, null, NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi", null,
            )
        }
        assertEquals(before.configGeneration, p.connections.get()!!.configGeneration)
        p.stalePlan {
            p.enable.run(
                AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, p.read(STATUS), null, INSTALLATION, plannedBeforeForget,
            )
        }

        // A plan that still holds commits.
        val now = p.read(STATUS)
        val enabled = p.enable.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, now, null, INSTALLATION, p.planFor(now))
        assertEquals(STATUS, enabled.entityId)

        // A phone-side Change target between an API plan and its commit.
        val apiRead = p.read(STATUS_2)
        val apiPlan = p.planFor(apiRead, PublishAction.CHANGE_TARGET)
        p.change.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS_2, p.read(STATUS_2), null, INSTALLATION)
        p.stalePlan {
            p.change.run(
                AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS_2, p.read(STATUS_2), null, INSTALLATION, apiPlan,
            )
        }

        // Change target with a plan that holds, and the phone's null plan, both commit.
        val back = p.read(STATUS)
        val changed = p.change.run(
            AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS, back, null, INSTALLATION,
            p.planFor(back, PublishAction.CHANGE_TARGET),
        )
        assertEquals(STATUS, changed.entityId)
        val phone = p.change.run(AssetId(HEATER), PublishKind.EQUIPMENT_STATUS, STATUS_2, p.read(STATUS_2), null, INSTALLATION)
        assertEquals(STATUS_2, phone.entityId)
    }

    @Test
    fun dismissDeletesOnlyAnUndeliverableTombstone() = runTest {
        val p = PublishHarness()
        p.tombstones.upsertMerging(tombstoneOf("t-pending", "input_datetime.example_heater_service_done"))
        p.tombstones.upsertMerging(
            tombstoneOf("t-gone", "input_datetime.example_boiler_service_done", state = DecommissionState.UNDELIVERABLE),
        )

        assertEquals("t-unknown", assertFailsWith<NoSuchDecommission> { p.dismiss.run("t-unknown") }.id)
        assertEquals("t-pending", assertFailsWith<DecommissionStillPending> { p.dismiss.run("t-pending") }.id)
        p.dismiss.run("t-gone")
        assertNull(p.tombstones.get("t-gone"))
        assertEquals(listOf("t-pending"), p.tombstones.all().map { it.id })
    }
}
