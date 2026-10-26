package com.loosecannon.servicetag.hapublish

import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DecommissionStillPending
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.DeliverySteps
import com.loosecannon.servicetag.core.hapublish.HaServiceAction
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.PublishState
import com.loosecannon.servicetag.core.hapublish.RemoteRead
import com.loosecannon.servicetag.core.hapublish.Snap
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.DATE
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.STATUS
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.dateHelper
import com.loosecannon.servicetag.hapublish.PublishRig.Companion.statusHelper
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.ScriptedHaPublish
import com.loosecannon.servicetag.testing.assetRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * #114 (C34, AC114-6, G10, K8, K12, O-11; rows 70–74, and rows 93 and 94 on the runner) — every way ServiceTag lets go
 * of a date helper leaves `1970-01-01` delivered **and read back** first, or an obligation drawn until the owner
 * dismisses it. Each case runs the real runner over the graph's scripted Home Assistant: the heater's date binding
 * publishes `2025-11-15` first, then one exit, then a drain. Every asset, helper, address and token is fictional.
 */
class DecommissionScenarioTest {

    private val rig = ScenarioRig()

    @After fun close() = rig.close()

    /** The heater (in season 1 April – 31 October) served on 15 November; its date binding published and verified. */
    private suspend fun ScenarioRig.published(): PublishBinding {
        calendar()
        role("2025-09-01")
        complete("2025-11-15")
        connect()
        ha.helpers[DATE] = dateHelper(DATE, INVALID_SERVICE_DATE)
        val binding = enable(PublishKind.SEASON_SERVICE_DATE)
        project()
        graph.publishRunner.drain()
        assertEquals("2025-11-15", ha.state(DATE))
        ha.log.clear()
        return binding
    }

    private val ScenarioRig.ha: ScriptedHaPublish get() = graph.haPublish

    private suspend fun ScenarioRig.project() = graph.publishProjector.reconcileDesired(graph.today)

    /** What Home Assistant saw of [entityId], in order. */
    private fun ScenarioRig.seen(entityId: String) = ha.log.filter { it.endsWith(entityId) || it.contains("$entityId=") }

    private fun delivered1970(entityId: String = DATE) =
        listOf("GET $entityId", "POST $entityId=$INVALID_SERVICE_DATE", "GET $entityId")

    private suspend fun ScenarioRig.stateOf(binding: PublishBinding): PublishState {
        val stored = graph.publishBindings.get(binding.id)!!
        val connection = graph.haConnections.get()
        return DeliverySteps.stateOf(
            Snap(
                stored, delivery(stored), connection?.id == stored.connectionId,
                connection != null && graph.secretStore.has(connection.id), graph.installationIdentity.id(),
                graph.assets.get(stored.assetId), graph.transferRecords.heldIds(),
            ),
        )
    }

    private suspend fun ScenarioRig.tombstones(): List<DecommissionTombstone> = graph.publishDecommissions.all()

    /**
     * An exit the projector turns into `1970-01-01` (C15): pending until read back, then the binding still enabled, and
     * [quiet] (`INVALIDATED_INELIGIBLE`) or `PUBLISHED` after it; a second drain sends nothing.
     */
    private suspend fun ScenarioRig.deliversThenQuiesces(binding: PublishBinding, quiet: PublishState) {
        project()
        assertEquals(INVALID_SERVICE_DATE, delivery(binding).desiredValue)
        assertEquals("readback before quiescence", PublishState.PENDING, stateOf(binding))
        graph.publishRunner.drain()
        assertEquals(delivered1970(), seen(DATE))
        assertEquals(INVALID_SERVICE_DATE, ha.state(DATE))
        assertEquals(quiet, stateOf(binding))
        assertTrue(graph.publishBindings.get(binding.id)!!.enabled)
        ha.log.clear()
        graph.publishRunner.drain()
        assertTrue("nothing more is sent", ha.log.isEmpty())
    }

    // --- row 70 (AC114-6, G10): each exit delivers 1970-01-01 first --------------------------------------------------

    @Test
    fun disableTargetChangeArchiveRetireTransferOutRoleRemovalAndScheduleArchiveEachDeliver1970First() {
        disable()
        targetChange()
        archive()
        retire()
        transferOut()
        roleRemoval()
        scheduleArchive()
    }

    private fun disable() = ScenarioRig().alone {
        run {
            published()
            graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)
            val owed = graph.publishDecommissions.all().single()
            assertEquals(DecommissionState.PENDING, owed.state)

            graph.publishRunner.drain()

            assertEquals(delivered1970(), seen(DATE))
            assertTrue("deleted by its id once read back", graph.publishDecommissions.all().isEmpty())
        }
    }

    private fun targetChange() = ScenarioRig().alone {
        run {
            published()
            ha.helpers[OTHER_DATE] = dateHelper(OTHER_DATE, INVALID_SERVICE_DATE)
            graph.changePublishTarget.run(
                heater, PublishKind.SEASON_SERVICE_DATE, OTHER_DATE, fresh(OTHER_DATE, PublishKind.SEASON_SERVICE_DATE),
                null, graph.installationIdentity.id(),
            )
            project()

            graph.publishRunner.drain()

            assertEquals(delivered1970(), seen(DATE))
            assertEquals("2025-11-15", ha.state(OTHER_DATE))
            assertTrue(graph.publishDecommissions.all().isEmpty())
        }
    }

    private fun archive() = ScenarioRig().alone {
        run {
            val binding = published()
            graph.archiveAsset.run(heater)
            deliversThenQuiesces(binding, PublishState.INVALIDATED_INELIGIBLE)
        }
    }

    private fun retire() = ScenarioRig().alone {
        run {
            val binding = published()
            graph.retireAsset.retire(heater, "2026-02-01")
            deliversThenQuiesces(binding, PublishState.INVALIDATED_INELIGIBLE)
        }
    }

    private fun transferOut() = ScenarioRig().alone {
        run {
            val binding = published()
            graph.transferRecords.append(
                TransferRecord(
                    id = "example-out-1", assetId = heater, kind = TransferKind.OUT, packId = "example-pack-1",
                    lineage = emptyList(), at = graph.now, packSha256 = "ab".repeat(32), nameSnapshot = "Example Heater",
                    note = "",
                ),
            )
            deliversThenQuiesces(binding, PublishState.INVALIDATED_INELIGIBLE)
        }
    }

    private fun roleRemoval() = ScenarioRig().alone {
        run {
            val binding = published()
            graph.setScheduleRole.run(service, ScheduleRole.NONE)
            // The binding keeps owning its helper: an ordinary delivery, published at the new pair.
            deliversThenQuiesces(binding, PublishState.PUBLISHED)
        }
    }

    private fun scheduleArchive() = ScenarioRig().alone {
        run {
            val binding = published()
            graph.archiveSchedule.run(service, archived = true)
            deliversThenQuiesces(binding, PublishState.PUBLISHED)
        }
    }

    // --- row 71 (C4.2, C34, K8): the soft link leaves the obligation for the projector -------------------------------

    @Test
    fun anAssetDeleteLeavesAnObligationTheProjectorDelivers() = rig.run {
        val binding = published()
        graph.deleteAsset.run(heater)
        assertNotNull("the binding outlives its asset row", graph.publishBindings.get(binding.id))

        project()
        assertNull(graph.publishBindings.get(binding.id))
        assertEquals(DecommissionState.PENDING, tombstones().single().state)
        graph.publishRunner.drain()

        assertEquals(delivered1970(), seen(DATE))
        assertTrue(tombstones().isEmpty())
    }

    @Test
    fun aReplaceRestoreWithTheAssetGoneDoesTheSame() = rig.run {
        val binding = published()
        val elsewhere = FakeGraph()
        try {
            graph.importBackupReplace.run(elsewhere.exportBackupSet.run().data)
        } finally {
            elsewhere.close()
        }
        assertNull(graph.assets.get(heater))

        project()
        assertNull(graph.publishBindings.get(binding.id))
        graph.publishRunner.drain()

        assertEquals(delivered1970(), seen(DATE))
        assertTrue(tombstones().isEmpty())
    }

    // --- row 72 (AC114-6 "never silent") ------------------------------------------------------------------------------

    @Test
    fun anUndeliverableInvalidationIsShownNeverDropped() = rig.run {
        published()
        graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)

        // No network: still PENDING, with the error, and TRANSIENT work for the one-time work's backoff.
        ha.onRead = { RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) }
        assertTrue(graph.publishRunner.drain())
        val waiting = tombstones().single()
        assertEquals(DecommissionState.PENDING, waiting.state)
        assertEquals(PublishErrorKind.UNREACHABLE, waiting.errorKind)

        // A 5xx read is TRANSIENT too, read from the live answer's detail (the row keeps none).
        ha.onRead = { RemoteRead.Failed(PublishErrorKind.HTTP_ERROR, "503") }
        graph.publishRunner.drain()
        assertEquals(DecommissionState.PENDING, tombstones().single().state)

        // The token gone: UNDELIVERABLE, nothing read or sent, and the row stays.
        ha.onRead = { ha.answer(it) }
        graph.secretStore.delete(graph.haConnections.get()!!.id)
        ha.log.clear()
        assertFalse(graph.publishRunner.drain())
        val drawn = tombstones().single()
        assertEquals(DecommissionState.UNDELIVERABLE, drawn.state)
        assertEquals(PublishErrorKind.NEEDS_TOKEN, drawn.errorKind)
        assertTrue(ha.log.isEmpty())
        graph.publishRunner.drain()
        graph.publishRunner.publishNow(null)
        assertEquals(listOf(drawn), tombstones())
    }

    @Test
    fun aNeedsOwnerFailureKeepsTheObligation() = rig.run {
        published()
        graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)
        ha.helpers.remove(DATE) // the helper deleted in Home Assistant

        graph.publishRunner.drain()

        val drawn = tombstones().single()
        assertEquals(DecommissionState.UNDELIVERABLE, drawn.state)
        assertEquals(PublishErrorKind.ENTITY_NOT_FOUND, drawn.errorKind)
        assertEquals(listOf("GET $DATE"), ha.log)
    }

    @Test
    fun dismissIsTheOnlyWayOut() = rig.run {
        published()
        graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)
        val pending = tombstones().single()
        assertFailsWith<DecommissionStillPending> { graph.publishControls.dismissDecommission(pending.id) }

        ha.onCall = { CallOutcome.Refused(PublishErrorKind.WRITE_FORBIDDEN, null) }
        graph.publishRunner.drain()
        val drawn = tombstones().single()
        assertEquals(DecommissionState.UNDELIVERABLE, drawn.state)
        assertEquals(PublishErrorKind.WRITE_FORBIDDEN, drawn.errorKind)

        // Nothing but the owner's word takes it away: drains, a read-back, a projection, a restart.
        ha.onCall = { ha.act(it) }
        graph.publishRunner.drain()
        graph.publishRunner.publishNow(null)
        project()
        rebuild()
        graph.publishRunner.drain()
        assertEquals(listOf(drawn), tombstones())

        graph.publishControls.dismissDecommission(drawn.id)
        assertTrue(tombstones().isEmpty())
    }

    // --- row 73 (O-11): Forget drains first, then keeps what it could not deliver ---------------------------------------

    @Test
    fun forgetDrainsFirstThenKeepsTheRest() = rig.run {
        val heaterDate = published()
        // The boiler's helper, let go earlier and unreachable now.
        graph.assets.upsert(
            assetRow(BOILER.value, "Example Boiler", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-01", seasonEnd = "10-31"),
        )
        ha.helpers[BOILER_DATE] = dateHelper(BOILER_DATE, "2025-10-01")
        graph.enablePublishing.run(
            BOILER, PublishKind.SEASON_SERVICE_DATE, BOILER_DATE, fresh(BOILER_DATE, PublishKind.SEASON_SERVICE_DATE, BOILER),
            null, graph.installationIdentity.id(),
        )
        graph.disablePublishing.run(BOILER, PublishKind.SEASON_SERVICE_DATE)
        // A third helper, let go earlier and reachable: it still holds a date (B5 review I2).
        graph.assets.upsert(assetRow(CABIN.value, "Example Cabin Heater"))
        ha.helpers[CABIN_DATE] = dateHelper(CABIN_DATE, INVALID_SERVICE_DATE)
        graph.enablePublishing.run(
            CABIN, PublishKind.SEASON_SERVICE_DATE, CABIN_DATE, fresh(CABIN_DATE, PublishKind.SEASON_SERVICE_DATE, CABIN),
            null, graph.installationIdentity.id(),
        )
        graph.disablePublishing.run(CABIN, PublishKind.SEASON_SERVICE_DATE)
        ha.helpers[CABIN_DATE] = dateHelper(CABIN_DATE, "2025-10-02")
        ha.onRead = { if (it == BOILER_DATE) RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null) else ha.answer(it) }
        ha.log.clear()

        val drained = graph.publishControls.forgetConnection()
        graph.awaitDispatched()

        // The reachable one: 1970-01-01 written as its desired value, delivered and read back before the command.
        assertEquals(delivered1970(), seen(DATE))
        assertEquals(PassResult.Verified(posted = true), drained.bindings.single().result)
        // Each obligation of the connection had its one attempt (O-11, C34): the boiler's one read, the cabin's
        // delivery and read-back.
        assertEquals(listOf("GET $BOILER_DATE"), seen(BOILER_DATE))
        assertEquals(
            TombstoneResult.Pending(PublishErrorKind.UNREACHABLE, null),
            drained.tombstones.single { it.entityId == BOILER_DATE }.result,
        )
        assertEquals(delivered1970(CABIN_DATE), seen(CABIN_DATE))
        assertEquals(TombstoneResult.Cleared(posted = true), drained.tombstones.single { it.entityId == CABIN_DATE }.result)
        assertNull(graph.haConnections.get())
        val kept = tombstones().single()
        assertEquals(BOILER_DATE, kept.entityId)
        assertEquals(DecommissionState.UNDELIVERABLE, kept.state)
        assertTrue("the heater's helper is no obligation", tombstones().none { it.entityId == DATE })
        assertTrue("the cabin's helper was cleared", tombstones().none { it.entityId == CABIN_DATE })
        assertNull("the date binding went with the connection", graph.publishBindings.get(heaterDate.id))
    }

    /** B5 review M1: a drain mid-tombstone when Forget's command runs records nothing over the row Forget changed. */
    @Test
    fun aDrainNeverTurnsForgetsUndeliverableRowBackToPending() = rig.run {
        published()
        graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)
        ha.onRead = {
            if (it == DATE) {
                graph.forgetHaConnection.run()
                RemoteRead.Failed(PublishErrorKind.UNREACHABLE, null)
            } else {
                ha.answer(it)
            }
        }

        graph.publishRunner.drain()

        val kept = tombstones().single()
        assertEquals(DATE, kept.entityId)
        assertEquals("Forget's state stands", DecommissionState.UNDELIVERABLE, kept.state)
        assertEquals("one read, nothing sent", listOf("GET $DATE"), seen(DATE))
    }

    /** B5 review M3 (a): Forget sends only 1970-01-01, whatever the projector writes while it reads. */
    @Test
    fun forgetSendsNothingButTheInvalidDate() = rig.run {
        published()
        var hooked = false
        ha.onRead = {
            if (it == DATE && !hooked) {
                hooked = true
                val stored = graph.publishBindings.all().single()
                val held = delivery(stored)
                assertTrue(
                    graph.publishDeliveries.writeDesired(stored.id, stored.revision, held.desiredRevision, "2025-12-01", graph.now),
                )
            }
            ha.answer(it)
        }

        val drained = graph.publishControls.forgetConnection()
        graph.awaitDispatched()

        assertTrue(hooked)
        assertTrue("nothing but 1970-01-01 is sent", ha.log.none { it.startsWith("POST $DATE=") })
        assertEquals(PassResult.Superseded, drained.bindings.single().result)
        val kept = tombstones().single()
        assertEquals(DATE, kept.entityId)
        assertEquals(DecommissionState.UNDELIVERABLE, kept.state)
    }

    /** B5 review M3 (b): a tombstone superseded between its read and its POST is not written to. */
    @Test
    fun aTombstoneSupersededBeforeItsPostSendsNothing() = rig.run {
        published()
        graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)
        graph.assets.upsert(
            assetRow(BOILER.value, "Example Boiler", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-01", seasonEnd = "10-31"),
        )
        var hooked = false
        ha.onRead = {
            if (it == DATE && !hooked) {
                hooked = true
                graph.enablePublishing.run(
                    BOILER, PublishKind.SEASON_SERVICE_DATE, DATE, fresh(DATE, PublishKind.SEASON_SERVICE_DATE, BOILER), null,
                    graph.installationIdentity.id(),
                )
            }
            ha.answer(it)
        }

        val pass = graph.publishRunner.publishNow(heater)

        assertTrue(hooked)
        assertTrue("no 1970-01-01 over the new claim", ha.log.none { it == "POST $DATE=$INVALID_SERVICE_DATE" })
        assertEquals(TombstoneResult.Superseded, pass.tombstones.single().result)
        assertTrue("the Enable superseded the row", tombstones().isEmpty())
    }

    /**
     * C34, P114-39 (B5 review M2, the controller's ruling): while the asset is archived, retired or transferred out,
     * nothing more is sent — a quiet `INVALIDATED_INELIGIBLE` binding is neither read nor written on a reconcile, and
     * Publish now covers none. A helper changed in Home Assistant afterwards is Home Assistant's own business.
     */
    @Test
    fun aQuietIneligibleBindingIsNeitherReadNorSentOnAReconcile() = rig.run {
        val binding = published()
        graph.archiveAsset.run(heater)
        project()
        graph.publishRunner.drain()
        assertEquals(PublishState.INVALIDATED_INELIGIBLE, stateOf(binding))
        ha.helpers[DATE] = dateHelper(DATE, "2025-11-15") // changed in Home Assistant by hand
        ha.log.clear()

        graph.publishRunner.publishNow(heater)
        graph.publishRunner.publishNow()
        val request = graph.publishControls.requestPublishNow(heater)
        graph.awaitDispatched()

        assertTrue("nothing read, nothing sent", ha.log.isEmpty())
        assertTrue("Publish now covers no quiet binding", request.bindings.isEmpty())
        assertEquals("2025-11-15", ha.state(DATE))
        assertEquals(PublishState.INVALIDATED_INELIGIBLE, stateOf(binding))
    }

    // --- row 74 (K12): quiescence is no terminal state ------------------------------------------------------------------

    @Test
    fun anUnarchivedAssetsDateBindingResumes() = rig.run {
        val binding = published()
        graph.archiveAsset.run(heater)
        project()
        graph.publishRunner.drain()
        assertEquals(PublishState.INVALIDATED_INELIGIBLE, stateOf(binding))
        ha.log.clear()

        graph.archiveAsset.unarchive(heater)
        project()
        graph.publishRunner.drain()

        assertEquals(listOf("GET $DATE", "POST $DATE=2025-11-15", "GET $DATE"), seen(DATE))
        assertEquals(PublishState.PUBLISHED, stateOf(binding))
    }

    // --- rows 93 and 94 (F-1), on the runner -------------------------------------------------------------------------

    @Test
    fun reEnableAfterDecommissionSendsAgain() = rig.run {
        published()
        graph.disablePublishing.run(heater, PublishKind.SEASON_SERVICE_DATE)
        graph.publishRunner.drain()
        assertEquals(INVALID_SERVICE_DATE, ha.state(DATE))
        assertTrue(tombstones().isEmpty())
        ha.log.clear()

        val again = enable(PublishKind.SEASON_SERVICE_DATE)
        ha.calls.clear()
        graph.desiredStateReconciler.answer = { false } // the re-enable waits for the projector's own value
        graph.publishRunner.drain()
        assertTrue("nothing the projector has not computed", ha.posts.isEmpty())
        graph.desiredStateReconciler.answer = { graph.publishProjector.reconcileDesired(it) }
        project()
        graph.publishRunner.drain()

        assertEquals(listOf(ScriptedHaPublish.Call(HaServiceAction.SET_DATE, DATE, "2025-11-15")), ha.posts)
        assertEquals("2025-11-15", ha.state(DATE))
        val delivery = delivery(again)
        assertEquals("2025-11-15", delivery.lastPublishedValue)
        assertEquals(graph.publishBindings.get(again.id)!!.revision, delivery.publishedBindingRevision)
    }

    @Test
    fun aTargetChangePublishesToTheNewHelper() = rig.run {
        val first = upStatus()
        ha.helpers[OTHER_STATUS] = statusHelper("unknown").copy(entityId = OTHER_STATUS)
        ha.calls.clear()

        val moved = graph.changePublishTarget.run(
            heater, PublishKind.EQUIPMENT_STATUS, OTHER_STATUS, fresh(OTHER_STATUS, PublishKind.EQUIPMENT_STATUS), null,
            graph.installationIdentity.id(),
        )
        graph.publishRunner.drain()

        assertEquals(first.id, moved.id)
        assertEquals(listOf(ScriptedHaPublish.Call(HaServiceAction.SELECT_OPTION, OTHER_STATUS, "UP")), ha.posts)
        assertEquals("the old helper keeps the last reported value", "UP", ha.state(STATUS))
    }

    @Test
    fun aNewTargetShowsNoInheritedPublishedLine() = rig.run {
        val first = upStatus()
        val published = delivery(first)
        assertEquals("UP", published.lastPublishedValue)
        assertNotNull("an attempt was made to the old helper", published.lastAttemptAt)
        ha.helpers[OTHER_STATUS] = statusHelper("unknown").copy(entityId = OTHER_STATUS)

        graph.changePublishTarget.run(
            heater, PublishKind.EQUIPMENT_STATUS, OTHER_STATUS, fresh(OTHER_STATUS, PublishKind.EQUIPMENT_STATUS), null,
            graph.installationIdentity.id(),
        )

        // M5: the old helper's attempts and lines are never the new one's.
        assertEquals(
            PublishDelivery(
                bindingId = first.id,
                desiredValue = published.desiredValue,
                desiredRevision = published.desiredRevision,
                desiredAt = published.desiredAt,
            ),
            delivery(first),
        )
        assertEquals(PublishState.PENDING, stateOf(first))
    }

    /** The heater's status binding, `UP` published and read back. */
    private suspend fun ScenarioRig.upStatus(): PublishBinding {
        yearRound()
        connect()
        ha.helpers[STATUS] = statusHelper("unknown")
        record(OperationalCondition.OPERATIONAL)
        val binding = enable(PublishKind.EQUIPMENT_STATUS)
        project()
        graph.publishRunner.drain()
        assertEquals("UP", ha.state(STATUS))
        return binding
    }

    private fun <T> ScenarioRig.alone(block: ScenarioRig.() -> T): T = try {
        block()
    } finally {
        close()
    }

    private companion object {
        const val OTHER_DATE = "input_datetime.example_heater_service_done_2"
        const val OTHER_STATUS = "input_select.example_heater_equipment_status_2"
        const val BOILER_DATE = "input_datetime.example_boiler_service_done"
        const val CABIN_DATE = "input_datetime.example_cabin_heater_service_done"
        val BOILER = AssetId("example-boiler")
        val CABIN = AssetId("example-cabin-heater")
    }
}
