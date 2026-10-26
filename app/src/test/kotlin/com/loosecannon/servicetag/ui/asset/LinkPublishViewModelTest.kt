package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.seasonsync.EntityScope
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.seasonsync.JdkAead
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #113 row 89 (C36, AC113-1, C30, C32) — the publishing setup sheet's model over a Room-backed [FakeGraph], the runner's
 * real validation and a scripted Home Assistant: the browser lists only the kind's domain, Check helper's fresh read
 * either answers its refusal (and Publish is never offered) or draws the confirm naming the exact destination and the
 * mapping, and Publish enables through `PublishControls`, activating this phone and asking for delivery. The sentences
 * are written out in their ratified words. Every name, helper and address is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkPublishViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private val heater = AssetId("example-heater")
    private var listed: HaListOutcome? = null
    private var listReads = 0

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val store =
            KeystoreSecretStore(createTempDirectory("no-backup").toFile(), JdkAead(), StandardTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler), secretStore = store)
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.connected(): HaConnection {
        graph.assets.upsert(assetRow(heater.value, "Example Heater"))
        val connection = graph.saveHaConnection.run(
            ADDRESS, Secret("fictional-token-1"), null, NetworkEligibility.HOME_NETWORK_ONLY, HOME_WIFI, null,
        )
        advanceUntilIdle()
        return connection
    }

    private fun TestScope.open(kind: PublishKind = PublishKind.EQUIPMENT_STATUS): LinkPublishViewModel {
        val model = LinkPublishViewModel(
            heater, kind, graph.assets, graph.seasonSyncBindings, graph.publishControls, graph.publishRunner::validate,
        ) {
            listReads += 1
            listed
        }
        advanceUntilIdle()
        return model
    }

    @Test fun theScopeListsOnlyItsDomain() = runTest(scheduler) {
        connected()
        listed = HaListOutcome.Listed(
            listOf(
                HaEntityCandidate("input_boolean.example_heater_in_season", "Example heater in season"),
                HaEntityCandidate(STATUS, "Example heater status"),
                HaEntityCandidate("input_datetime.example_heater_service_done", "Example heater service done"),
                HaEntityCandidate("input_select.example_pool_mode", null),
            ),
        )
        val sheet = open()
        assertEquals(EntityScope.INPUT_SELECTS, entityScopeOf(PublishKind.EQUIPMENT_STATUS))
        assertEquals("nothing is read before Choose helper", 0, listReads)

        sheet.chooseHelper()
        advanceUntilIdle()
        assertEquals(listOf(STATUS, "input_select.example_pool_mode"), sheet.state.value.browse!!.rows.map { it.entityId })

        sheet.pick(sheet.state.value.browse!!.rows.first())
        assertEquals(STATUS, sheet.state.value.target)
        assertNull(sheet.state.value.browse)
    }

    @Test fun theConfirmNamesTheExactDestinationAndMapping() = runTest(scheduler) {
        connected()
        graph.haPublish.helpers[STATUS] = RemoteEntity(STATUS, "unknown", listOf("DOWN", "UP", "DEGRADED"), null, null)
        val sheet = open()
        sheet.onEntityId(" $STATUS ")
        assertFalse("Publish needs the fresh read first", sheet.state.value.canPublish)

        sheet.check()
        advanceUntilIdle()
        assertEquals(
            "Publish Example Heater's equipment status to $STATUS? ServiceTag sets this helper whenever the recorded " +
                "condition changes: Operational → UP, Degraded → DEGRADED, Down → DOWN. Home Assistant automations " +
                "should read it and never set it.",
            sheet.state.value.confirm,
        )
        assertEquals("GET $STATUS", graph.haPublish.log.single())
        assertTrue(graph.publishBindings.forAsset(heater).isEmpty())

        val before = graph.deliveryRequests.get()
        sheet.publish()
        advanceUntilIdle()
        val written = graph.publishBindings.forAsset(heater).single()
        assertEquals(STATUS, written.entityId)
        assertTrue(written.enabled)
        assertEquals("Publish activates this phone (I26)", graph.installationIdentity.id(), written.activatedInstallationId)
        assertTrue("the control asked for delivery (A03)", graph.deliveryRequests.get() > before)
        assertTrue(sheet.state.value.finished)
    }

    @Test fun aValidationRefusalBlocksEnable() = runTest(scheduler) {
        connected()
        graph.haPublish.helpers[STATUS] = RemoteEntity(STATUS, "UP", listOf("UP", "DOWN"), null, null)
        val sheet = open()
        sheet.onEntityId(STATUS)
        sheet.check()
        advanceUntilIdle()
        assertEquals(listOf("$STATUS must be a dropdown helper whose options are exactly UP, DOWN and DEGRADED."), sheet.state.value.answer)
        assertNull(sheet.state.value.confirm)
        assertFalse(sheet.state.value.canPublish)

        sheet.publish()
        advanceUntilIdle()
        assertTrue("nothing is written", graph.publishBindings.forAsset(heater).isEmpty())

        // An id outside the domain is refused before any read; nothing chosen is P113-29.
        graph.haPublish.log.clear()
        sheet.onEntityId("input_boolean.example_heater_in_season")
        sheet.check()
        advanceUntilIdle()
        assertEquals(listOf("input_boolean.example_heater_in_season is not a dropdown helper (input_select)."), sheet.state.value.answer)
        assertTrue(graph.haPublish.log.isEmpty())
        sheet.onEntityId("  ")
        sheet.check()
        assertEquals("Choose a helper first.", sheet.state.value.entityLine)
    }

    @Test fun aChangedHelperDropsTheReadAndARefusedPublishHasItsSentence() = runTest(scheduler) {
        val connection = connected()
        graph.haPublish.helpers[STATUS] = RemoteEntity(STATUS, "unknown", listOf("UP", "DEGRADED", "DOWN"), null, null)
        val sheet = open()
        sheet.onEntityId(STATUS)
        sheet.check()
        advanceUntilIdle()
        sheet.onEntityId("input_select.example_pool_mode")
        assertNull("a changed helper drops the confirm", sheet.state.value.confirm)
        sheet.onEntityId(STATUS)
        sheet.check()
        advanceUntilIdle()

        // Meanwhile another asset on this phone took the helper: TARGET_IN_USE, P113-22, and the read is spent.
        graph.assets.upsert(assetRow("example-boiler", "Example Boiler"))
        graph.enablePublishing.run(
            AssetId("example-boiler"), PublishKind.EQUIPMENT_STATUS, STATUS,
            ValidationResult(STATUS, graph.haPublish.helpers.getValue(STATUS), connection.id, connection.configGeneration, 0, graph.now),
            null, graph.installationIdentity.id(),
        )
        sheet.publish()
        advanceUntilIdle()
        assertEquals("$STATUS is already set by another asset on this phone.", sheet.state.value.refusal)
        assertNull(sheet.state.value.confirm)
        assertFalse(sheet.state.value.finished)
        assertTrue(graph.publishBindings.forAsset(heater).isEmpty())
    }

    // --- #114 (B6b): the date kind's sheet and its season-ended helper (C9a, D; P114-47/48) ----------------------------

    @Test fun theDateSheetAsksForTheSeasonEndedHelperOnlyWhenHomeAssistantDrivesTheSeason() = runTest(scheduler) {
        val connection = connected()
        graph.haPublish.helpers[DATE] = RemoteEntity(DATE, "2025-11-15", null, true, false)
        graph.haPublish.helpers[SEASON_ENDED] = RemoteEntity(SEASON_ENDED, "2026-05-10", null, true, false)
        // The heater's own season (year-round here): no season-ended helper is asked for, and the confirm is P114-33.
        val own = open(PublishKind.SEASON_SERVICE_DATE)
        assertFalse(own.state.value.boundaryAsked)
        assertEquals(EntityScope.INPUT_DATETIMES, entityScopeOf(PublishKind.SEASON_SERVICE_DATE))
        own.onEntityId(DATE)
        own.check()
        advanceUntilIdle()
        assertEquals(
            "Publish Example Heater's Season Start date to $DATE? While Example Heater is ready for the season, " +
                "ServiceTag sets the service date; otherwise it sets 1970-01-01, which means not ready. Home Assistant " +
                "automations should read it and never set it.",
            own.state.value.confirm,
        )
        // The status kind never asks for it.
        assertFalse(open().state.value.boundaryAsked)

        // The heater's season now follows Home Assistant (#16, MANUAL): the field is asked for, and checked first.
        graph.linkSeasonSync.run(heater, "input_boolean.example_heater_in_season")
        graph.haPublish.log.clear()
        val sheet = open(PublishKind.SEASON_SERVICE_DATE)
        assertTrue(sheet.state.value.boundaryAsked)
        sheet.onEntityId(DATE)
        sheet.check()
        advanceUntilIdle()
        assertEquals("Choose a helper first.", sheet.state.value.boundaryLine)
        sheet.onBoundaryEntityId(DATE)
        sheet.check()
        advanceUntilIdle()
        assertEquals(
            "$DATE is the helper ServiceTag sets. Choose the date helper Home Assistant stamps when the season ends.",
            sheet.state.value.boundaryLine,
        )
        assertNull(sheet.state.value.confirm)
        assertTrue("nothing is read for a refused pair", graph.haPublish.log.isEmpty())

        // A season-ended helper with a time is refused by the date validator, naming it (P114-32).
        graph.haPublish.helpers[SEASON_ENDED] = RemoteEntity(SEASON_ENDED, "2026-05-10 00:00:00", null, true, true)
        sheet.onBoundaryEntityId(SEASON_ENDED)
        assertNull(sheet.state.value.boundaryLine)
        sheet.check()
        advanceUntilIdle()
        assertEquals(listOf("$SEASON_ENDED must be a date helper with a date and no time."), sheet.state.value.answer)
        assertNull(sheet.state.value.confirm)

        // A date-only one: the confirm, and Publish stores it as the binding's boundary helper.
        graph.haPublish.helpers[SEASON_ENDED] = RemoteEntity(SEASON_ENDED, "2026-05-10", null, true, false)
        sheet.onBoundaryEntityId(" $SEASON_ENDED ")
        sheet.check()
        advanceUntilIdle()
        assertNotNull(sheet.state.value.confirm)
        sheet.publish()
        advanceUntilIdle()
        val written = graph.publishBindings.forAsset(heater).single()
        assertEquals(DATE, written.entityId)
        assertEquals(SEASON_ENDED, written.boundaryEntityId)
        assertEquals(connection.id, written.connectionId)
        assertTrue(sheet.state.value.finished)
    }

    private companion object {
        const val DATE = "input_datetime.example_heater_service_done"
        const val SEASON_ENDED = "input_datetime.example_heater_season_ended"
        const val STATUS = "input_select.example_heater_equipment_status"
        const val ADDRESS = "http://192.168.0.10:8123"
        const val HOME_WIFI = "ExampleHomeWifi"
    }
}
