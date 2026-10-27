package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.seasonsync.DefaultNetworkAnswer
import com.loosecannon.servicetag.seasonsync.DefaultNetworkKind
import com.loosecannon.servicetag.seasonsync.JdkAead
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.seasonsync.NetworkPlatform
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.dayMillis
import com.loosecannon.servicetag.ui.homeassistant.Notice
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
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
 * #129 row k7 (L-C6, D4) — the season card's Change helper and Remove link, over a Room-backed [FakeGraph] with the
 * shipped use cases and K1's `UnlinkSeasonSync`: both offered on an enabled and on a stopped binding exactly when the
 * asset is not held here (`writable`, not "maintained here": an archived or a retired asset keeps them, I5), neither on
 * a held asset or with no binding; Remove link asks P129-2 with the link's helper first, Cancel writes nothing, and the
 * confirm runs the unlink once, after which the card offers Link again and Start and End come back. P129-2 is written
 * out in its ratified words, so a paraphrase fails. Home Assistant is never reached; fixtures are fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeasonSyncBlockControlsTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    private val heater = AssetId("example-heater")
    private val helper = "input_boolean.example_heater_in_season"

    /** The unlink seam's calls, counted, so "one command for a double tap" is a number. */
    private var unlinks = 0
    private var unlinkSeam: suspend (AssetId) -> Unit = { unlinks++; graph.unlinkSeasonSync.run(it) }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val store =
            KeystoreSecretStore(createTempDirectory("no-backup").toFile(), JdkAead(), StandardTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler), secretStore = store)
        graph.today = LocalDate.parse("2026-02-10")
        graph.now = dayMillis("2026-02-10") + 9 * HOUR
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private suspend fun connect() {
        graph.saveHaConnection.run(
            "https://ha.example:8123", Secret("fictional-token-1"), SyncCadence.DAILY, NetworkEligibility.ANY_NETWORK,
            null, null,
        )
    }

    /** A MANUAL asset linked to [helper]; the link's own read has run (unreachable: no row). */
    private suspend fun TestScope.linked(id: AssetId = heater, name: String = "Example Heater"): AssetId {
        graph.assets.upsert(assetRow(id.value, name = name, seasonMode = SeasonMode.MANUAL))
        graph.linkSeasonSync.run(id, helper)
        advanceUntilIdle()
        return id
    }

    private fun TestScope.open(id: AssetId = heater): SeasonSyncBlockViewModel {
        val model = SeasonSyncBlockViewModel(
            id, graph.seasonSyncBindings, graph.haConnections, graph.secretStore, graph.assets, graph.transferRecords,
            graph.setSeasonSyncMode, graph.stopSeasonSync, graph.resumeSeasonSync,
            { graph.seasonSyncRunner.syncNow(it) }, graph.seasonSyncBackgroundAllowed, graph.clock, NoGrants,
            { "Allow all the time" }, { ZoneOffset.UTC },
            unlink = { unlinkSeam(it) },
        )
        advanceUntilIdle()
        return model
    }

    private fun TestScope.act(step: () -> Unit) {
        step()
        advanceUntilIdle()
    }

    private val SeasonSyncBlockViewModel.now: SeasonSyncBlockState get() = state.value

    private fun SeasonSyncBlockState.offersBoth() = offersChangeHelper && offersRemoveLink

    private fun SeasonSyncBlockState.offersNeither() = !offersChangeHelper && !offersRemoveLink

    private suspend fun hold(id: AssetId) {
        graph.transferRecords.append(
            TransferRecord(
                id = "out-${id.value}", assetId = id, kind = TransferKind.OUT, packId = "pack-elsewhere",
                lineage = emptyList(), at = dayMillis("2026-02-01"), packSha256 = "ab".repeat(32),
                nameSnapshot = "Example Heater", note = "",
            ),
        )
    }

    private suspend fun rows(id: AssetId) = graph.seasonActivations.forAsset(id).map { it.action to it.occurredOn }

    @Test fun anEnabledAndAStoppedLinkOfferBothOnAnAssetMaintainedHere() = runTest {
        connect()
        linked()
        val enabled = open().now
        assertEquals(SeasonSyncBlockKind.ENABLED, enabled.kind)
        assertTrue("enabled: Change helper and Remove link", enabled.offersBoth())
        assertNull("nothing asked yet", enabled.removeAsk)

        graph.stopSeasonSync.run(heater)
        val stopped = open().now
        assertEquals(SeasonSyncBlockKind.STOPPED, stopped.kind)
        assertTrue("stopped: Change helper and Remove link beside Resume", stopped.offersBoth())
    }

    /** I5: the gate is `writable` (not held), not `here` — an archived or a retired asset's link is not stuck. */
    @Test fun anArchivedAndARetiredAssetOfferBothOnAnEnabledAndAStoppedLink() = runTest {
        connect()
        val archived = linked(AssetId("example-boiler"), "Example Boiler")
        val retired = linked(AssetId("example-pool-pump"), "Example Pool Pump")
        graph.archiveAsset.run(archived)
        graph.retireAsset.retire(retired, "2026-02-01")
        advanceUntilIdle()
        for (id in listOf(archived, retired)) {
            val enabled = open(id).now
            assertEquals(SeasonSyncBlockKind.ENABLED, enabled.kind)
            assertFalse("${id.value}: not maintained here, so no mode control", enabled.offersControls)
            assertTrue("${id.value}, enabled: both offered", enabled.offersBoth())

            graph.stopSeasonSync.run(id)
            val stopped = open(id).now
            assertEquals(SeasonSyncBlockKind.STOPPED, stopped.kind)
            assertTrue("${id.value}, stopped: both offered", stopped.offersBoth())
        }
    }

    /** D4: a held asset hides both, as it hides Stop and Resume; no binding offers neither. */
    @Test fun aHeldAssetAndAnUnlinkedOneOfferNeither() = runTest {
        connect()
        linked()
        hold(heater)
        val enabledCard = open()
        val enabled = enabledCard.now
        assertEquals(SeasonSyncBlockKind.ENABLED, enabled.kind)
        assertFalse(enabled.offersStop)
        assertTrue("held, enabled: neither", enabled.offersNeither())
        act { enabledCard.askRemoveLink() }
        assertNull("held: Remove link is never asked", enabledCard.now.removeAsk)
        graph.stopSeasonSync.run(heater)
        val stoppedCard = open()
        val stopped = stoppedCard.now
        assertEquals(SeasonSyncBlockKind.STOPPED, stopped.kind)
        assertFalse(stopped.offersResume)
        assertTrue("held, stopped: neither", stopped.offersNeither())
        act { stoppedCard.askRemoveLink() }
        act { stoppedCard.confirmRemoveLink() }
        assertNull("held, stopped: never asked", stoppedCard.now.removeAsk)
        assertEquals("nothing removed", 0, unlinks)
        assertNotNull(graph.seasonSyncBindings.get(heater))

        graph.assets.upsert(assetRow("example-sprinkler", name = "Example Sprinkler", seasonMode = SeasonMode.MANUAL))
        val none = open(AssetId("example-sprinkler")).now
        assertEquals(SeasonSyncBlockKind.NONE, none.kind)
        assertTrue("no binding offers Link", none.offersLink)
        assertTrue("no binding: neither", none.offersNeither())
    }

    @Test fun removeLinkAsksP129_2WithTheHelperAndCancelWritesNothing() = runTest {
        connect()
        linked()
        val before = graph.seasonSyncBindings.get(heater)
        val block = open()

        act { block.askRemoveLink() }

        assertEquals(
            "Remove the link to input_boolean.example_heater_in_season? This asset stops following it and keeps its " +
                "current season and history. Your Home Assistant connection and other links stay.",
            block.now.removeAsk,
        )
        assertEquals("asking writes nothing", before, graph.seasonSyncBindings.get(heater))
        assertEquals(0, unlinks)

        act { block.dismissRemoveLink() }

        assertNull(block.now.removeAsk)
        assertEquals("Cancel writes nothing", before, graph.seasonSyncBindings.get(heater))
        assertEquals(0, unlinks)
        assertEquals(SeasonSyncBlockKind.ENABLED, block.now.kind)
    }

    /**
     * The confirm runs the unlink once — a double tap is one command — and closes the ask; the binding flow then emits
     * null: the card is NONE, offers Link again (a connection exists), and Start and End are no longer hidden. The
     * asset's season rows and its connection are untouched.
     */
    @Test fun theConfirmRunsTheUnlinkOnceAndTheCardOffersLinkAgain() = runTest {
        connect()
        linked()
        val other = linked(AssetId("example-boiler"), "Example Boiler")
        val rowsBefore = rows(heater)
        val block = open()
        assertTrue(block.now.hidesStartAndEnd)
        act { block.askRemoveLink() }
        assertNotNull(block.now.removeAsk)

        block.confirmRemoveLink()
        block.confirmRemoveLink()
        advanceUntilIdle()

        assertEquals("one command for a double tap", 1, unlinks)
        assertNull(block.now.removeAsk)
        assertNull("this asset's binding is gone", graph.seasonSyncBindings.get(heater))
        assertNotNull("the other asset's link stays", graph.seasonSyncBindings.get(other))
        assertNotNull("the connection stays", graph.haConnections.get())
        assertEquals("no season row written", rowsBefore, rows(heater))
        assertEquals(SeasonSyncBlockKind.NONE, block.now.kind)
        assertTrue("Link is offered again", block.now.offersLink)
        assertFalse("Start and End come back", block.now.hidesStartAndEnd)
        assertTrue(block.now.offersNeither())
        assertFalse(block.now.busy)
    }

    /** The shipped `command` rule: one in flight; a store failure draws nothing, and the link stays. */
    @Test fun aConfirmWhileBusyIsOneCommandAndAFailedUnlinkDrawsNothing() = runTest {
        connect()
        linked()
        val gate = CompletableDeferred<Unit>()
        unlinkSeam = { unlinks++; gate.await(); error("the store failed") }
        val block = open()
        act { block.askRemoveLink() }
        act { block.confirmRemoveLink() }
        assertTrue("one in flight", block.now.busy)
        act { block.askRemoveLink() }
        act { block.confirmRemoveLink() }
        assertEquals("busy: no second command", 1, unlinks)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(block.now.busy)
        assertEquals("a store failure draws nothing", emptyList<Notice>(), block.now.notices)
        assertNotNull("nothing removed", graph.seasonSyncBindings.get(heater))
        assertEquals(SeasonSyncBlockKind.ENABLED, block.now.kind)
    }

    /** No grants and no network: the any-network connection asks for none, so no location line is drawn. */
    private object NoGrants : NetworkPlatform {
        override val apiLevel: Int = 34
        override fun defaultNetworkKind(): DefaultNetworkKind? = null
        override fun connectedWifiName(): String? = null
        override fun listenToDefaultNetwork(onAnswer: (DefaultNetworkAnswer) -> Unit): () -> Unit = {}
        override fun locationOn(): Boolean = true
        override fun preciseLocationGranted(): Boolean = true
        override fun approximateLocationGranted(): Boolean = true
        override fun backgroundLocationGranted(): Boolean = true
        override fun inForeground(): Boolean = true
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
