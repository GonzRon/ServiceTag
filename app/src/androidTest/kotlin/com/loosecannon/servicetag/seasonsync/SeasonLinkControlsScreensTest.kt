package com.loosecannon.servicetag.seasonsync

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.EditSeasonSyncEntity
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.LinkSeasonSync
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.OutboundTargets
import com.loosecannon.servicetag.core.seasonsync.ResumeSeasonSync
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncScheduler
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SetSeasonSyncMode
import com.loosecannon.servicetag.core.seasonsync.StopSeasonSync
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.UnlinkSeasonSync
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.asset.AssetDetailScreen
import com.loosecannon.servicetag.ui.asset.CANCEL_BUTTON
import com.loosecannon.servicetag.ui.asset.LinkSeasonSyncViewModel
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_CHANGE_ENTITY
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_CHANGE_HELPER
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_CHANGE_HELPER_SENTENCE
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_ENTER_MANUALLY
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_ENTITY_ID
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_LINK
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_NOW
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_RESUME
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_STOP
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_STOPPED
import com.loosecannon.servicetag.ui.asset.SeasonSyncBlockViewModel
import com.loosecannon.servicetag.ui.asset.SeasonSyncSheetPurpose
import com.loosecannon.servicetag.ui.asset.SeasonSyncSheetTarget
import com.loosecannon.servicetag.ui.asset.seasonSyncFollows
import com.loosecannon.servicetag.ui.asset.seasonSyncOutboundTarget
import com.loosecannon.servicetag.ui.asset.seasonSyncRemoveLinkConfirm
import com.loosecannon.servicetag.ui.attachments.SAVE_LABEL
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.condition.START_SEASON
import com.loosecannon.servicetag.ui.supplies.REMOVE_LINK
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #129 row k24 (L-C6, L-C7, D4) — Change helper and Remove link on the season card, on a real Compose tree: the one
 * fact each case can only show drawn. The rules behind them are the JVM rows' (k7, k8).
 *
 * **No network and no Keystore**, as `SeasonSyncScreensTest`: the card's and the sheet's models are built here from
 * their primary constructors over the real Room stores, with in-process doubles — an in-memory [SecretStore] holding
 * `fictional-token-1`, a [SeasonSyncScheduler] that does nothing (no work, no read), and the sheet's entity list as a
 * scripted empty answer. The writes are core's shipped `LinkSeasonSync`, `StopSeasonSync`, `EditSeasonSyncEntity` and
 * K1's `UnlinkSeasonSync`. They are seeded under the keys the screen asks for, through a [ViewModelStoreOwner] of the
 * test's own; a key the screen stopped using fails loudly (the graph's card would answer P16-11, its store holding no
 * token). Every wait is bounded; every target is unique on its tree. Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class SeasonLinkControlsScreensTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val graph: AppGraph get() = app.graph
    private val secrets = MemorySecrets()
    private val owners = mutableMapOf<AssetId, ViewModelStoreOwner>()
    private var shown by mutableStateOf<AssetId?>(null)

    private val link by lazy {
        LinkSeasonSync(
            graph.assets, graph.seasonActivations, graph.transferRecords, graph.seasonSyncBindings, graph.haConnections,
            secrets, graph.setSeasonMode, QuietScheduler, graph.uow, graph.clock, graph.today,
        )
    }
    private val resume by lazy {
        ResumeSeasonSync(link, graph.seasonSyncBindings, QuietScheduler, graph.uow, graph.clock)
    }
    private val stop by lazy { StopSeasonSync(graph.seasonSyncBindings, QuietScheduler, graph.uow, graph.clock) }
    private val unlink by lazy { UnlinkSeasonSync(graph.seasonSyncBindings, QuietScheduler, graph.uow) }

    @Before fun setUp() {
        clearInstall()
        assertNull(
            "this install already holds a Home Assistant connection, and this class never overwrites one",
            runBlocking { graph.haConnections.get() },
        )
    }

    /** The content leaves first, so no screen asks a cleared store for a fresh model; each step runs on its own. */
    @After fun tearDown() {
        val failures = mutableListOf<Throwable>()
        fun step(block: () -> Unit) = try {
            block()
        } catch (t: Throwable) {
            failures += t
        }
        step { rule.runOnIdle { shown = null } }
        step { rule.waitForIdle() }
        step { rule.runOnUiThread { owners.values.forEach { it.viewModelStore.clear() } } }
        step { runBlocking { graph.uow.write { graph.haConnections.delete(CONNECTION_ID) } } }
        step { clearInstall() }
        failures.firstOrNull()?.let { throw it }
    }

    /**
     * (1) Pins: an enabled link draws Change helper (P129-1) and Remove link (P15-23) in the row with Sync now and Stop
     * syncing; a stopped one draws both with Resume syncing.
     */
    @Test fun aLinkedCardDrawsChangeHelperAndRemoveLinkWithItsControls() {
        connect()
        val heater = heater()
        linked(heater)
        detail(heater)
        rule.awaitText(SEASON_SYNC_NOW)

        listOf(SEASON_SYNC_NOW, SEASON_SYNC_STOP, SEASON_SYNC_CHANGE_HELPER, REMOVE_LINK).forEach {
            rule.onNode(cardButton(it)).performScrollTo().assertIsDisplayed()
        }
        assertTrue("in the controls' row, after Sync now", top(SEASON_SYNC_CHANGE_HELPER) >= top(SEASON_SYNC_NOW))

        runBlocking { stop.run(heater) }

        rule.awaitText(SEASON_SYNC_RESUME)
        listOf(SEASON_SYNC_RESUME, SEASON_SYNC_CHANGE_HELPER, REMOVE_LINK).forEach {
            rule.onNode(cardButton(it)).performScrollTo().assertIsDisplayed()
        }
        assertTrue("with Resume", top(SEASON_SYNC_CHANGE_HELPER) >= top(SEASON_SYNC_RESUME))
    }

    /**
     * (2) Pins: Change helper opens the setup sheet fully expanded on the stored helper — its id with Change (P105-2),
     * P129-3, Save and Cancel on screen with no scroll — Save held on the stored id and enabled once another is typed;
     * Save changes the graph's binding and closes the sheet onto the card, which then follows the new helper.
     */
    @Test fun changeHelperOpensTheSheetOnTheStoredHelperAndSavesAnother() {
        connect()
        val heater = heater()
        linked(heater)
        detail(heater, changeSheet = true)
        rule.awaitText(SEASON_SYNC_CHANGE_HELPER)

        rule.onNode(cardButton(SEASON_SYNC_CHANGE_HELPER)).performScrollTo().performClick()

        rule.awaitText(SEASON_SYNC_CHANGE_HELPER_SENTENCE)
        listOf(ENTITY_ID, SEASON_SYNC_CHANGE_ENTITY, SEASON_SYNC_CHANGE_HELPER_SENTENCE, SAVE_LABEL, CANCEL_BUTTON)
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithText(SAVE_LABEL).assertIsNotEnabled()

        rule.onNodeWithText(SEASON_SYNC_ENTER_MANUALLY).performScrollTo().performClick()
        val field = rule.onNode(hasSetTextAction() and hasText(SEASON_SYNC_ENTITY_ID))
        rule.waitUntil(TIMEOUT_MS) { inputOf(field) == ENTITY_ID }
        rule.onNodeWithText(SAVE_LABEL).performScrollTo().assertIsNotEnabled()
        field.performTextReplacement(NEW_ENTITY_ID)
        rule.waitUntil(TIMEOUT_MS) { inputOf(field) == NEW_ENTITY_ID }
        rule.onNodeWithText(SAVE_LABEL).performScrollTo().assertIsEnabled().performClick()

        rule.waitUntil(TIMEOUT_MS) { binding(heater)?.entityId == NEW_ENTITY_ID }
        rule.waitUntil(TIMEOUT_MS) { none(SEASON_SYNC_CHANGE_HELPER_SENTENCE) }
        rule.awaitText(seasonSyncFollows(NEW_ENTITY_ID))
    }

    /** (3) Pins: a helper this phone writes keeps the sheet open with P113-43 under the entity, and writes nothing. */
    @Test fun anOutboundHelperKeepsTheSheetOpenWithP113_43() {
        connect()
        val heater = heater()
        linked(heater)
        val before = binding(heater)
        detail(heater, changeSheet = true, outbound = WritesNewHelper)
        rule.awaitText(SEASON_SYNC_CHANGE_HELPER)
        rule.onNode(cardButton(SEASON_SYNC_CHANGE_HELPER)).performScrollTo().performClick()
        rule.awaitText(SEASON_SYNC_CHANGE_HELPER_SENTENCE)
        rule.onNodeWithText(SEASON_SYNC_ENTER_MANUALLY).performScrollTo().performClick()
        val field = rule.onNode(hasSetTextAction() and hasText(SEASON_SYNC_ENTITY_ID))
        rule.waitUntil(TIMEOUT_MS) { inputOf(field) == ENTITY_ID }
        field.performTextReplacement(NEW_ENTITY_ID)
        rule.waitUntil(TIMEOUT_MS) { inputOf(field) == NEW_ENTITY_ID }

        rule.onNodeWithText(SAVE_LABEL).performScrollTo().assertIsEnabled().performClick()

        val refusal = seasonSyncOutboundTarget(NEW_ENTITY_ID)
        rule.awaitText(refusal)
        rule.onNodeWithText(refusal).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(SEASON_SYNC_CHANGE_HELPER_SENTENCE).assertExists()
        assertEquals("a refusal writes nothing", before, binding(heater))
    }

    /**
     * (4) Pins: Remove link asks P129-2 in a dialog first; Cancel writes nothing; its Remove link deletes this asset's
     * binding, and the card then offers Link to Home Assistant again and the season's Start again.
     */
    @Test fun removeLinkAsksFirstAndThenTheCardOffersLinkAndStartAgain() {
        connect()
        val heater = heater()
        linked(heater)
        detail(heater)
        rule.awaitText(SEASON_SYNC_NOW)
        assertTrue("an enabled link hides Start", none(START_SEASON))
        val question = seasonSyncRemoveLinkConfirm(ENTITY_ID)

        rule.onNode(cardButton(REMOVE_LINK)).performScrollTo().performClick()
        rule.awaitText(question)
        rule.onNode(hasText(question) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNode(hasText(CANCEL_BUTTON) and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(TIMEOUT_MS) { none(question) }
        assertNotNull("Cancel writes nothing", binding(heater))

        rule.onNode(cardButton(REMOVE_LINK)).performScrollTo().performClick()
        rule.awaitText(question)
        rule.onNode(hasText(REMOVE_LINK) and hasAnyAncestor(isDialog())).performClick()

        rule.waitUntil(TIMEOUT_MS) { binding(heater) == null }
        rule.awaitText(SEASON_SYNC_LINK)
        rule.onNodeWithText(SEASON_SYNC_LINK).performScrollTo().assertIsDisplayed()
        rule.awaitText(START_SEASON)
        rule.onNodeWithText(START_SEASON).performScrollTo().assertIsDisplayed()
        assertTrue("the dialog is gone", none(question))
        assertNotNull("the connection stays", runBlocking { graph.haConnections.get() })
    }

    /** (5) Pins (D4): a held asset draws neither action, enabled or stopped, as it draws no Stop or Resume. */
    @Test fun aHeldAssetDrawsNeitherAction() {
        connect()
        val heater = heater()
        linked(heater)
        runBlocking {
            graph.transferRecords.append(
                TransferRecord(
                    id = "out-${heater.value}", assetId = heater, kind = TransferKind.OUT, packId = "pack-elsewhere",
                    lineage = emptyList(), at = System.currentTimeMillis(), packSha256 = "ab".repeat(32),
                    nameSnapshot = ASSET_NAME, note = "",
                ),
            )
        }
        detail(heater)
        rule.awaitText(seasonSyncFollows(ENTITY_ID))
        assertTrue(none(SEASON_SYNC_STOP) && none(SEASON_SYNC_CHANGE_HELPER) && none(REMOVE_LINK))

        runBlocking { stop.run(heater) }

        rule.awaitText(SEASON_SYNC_STOPPED)
        assertTrue(none(SEASON_SYNC_RESUME) && none(SEASON_SYNC_CHANGE_HELPER) && none(REMOVE_LINK))
    }

    /**
     * (6) Pins (I5): an archived asset and a retired one — not maintained here, but not held — each draw Change helper
     * and Remove link with an enabled and with a stopped link.
     */
    @Test fun anArchivedAndARetiredAssetDrawBothActionsEnabledAndStopped() {
        connect()
        val archived = heater(BOILER)
        val retired = heater(POOL_PUMP)
        linked(archived)
        linked(retired)
        runBlocking {
            graph.archiveAsset.run(archived)
            graph.retireAsset.retire(retired, LocalDate.now().toString())
        }
        detail(listOf(archived, retired), changeSheet = false, outbound = OutboundTargets.NONE)
        for ((id, name, other) in listOf(Triple(archived, BOILER, POOL_PUMP), Triple(retired, POOL_PUMP, BOILER))) {
            rule.runOnIdle { shown = id }
            // This asset's page, not the one before it: its name in the title, the other's gone.
            rule.awaitText(name)
            rule.waitUntil(TIMEOUT_MS) { none(other) }
            rule.awaitText(SEASON_SYNC_CHANGE_HELPER)
            assertTrue("enabled: no Resume yet", none(SEASON_SYNC_RESUME))
            rule.onNode(cardButton(SEASON_SYNC_CHANGE_HELPER)).performScrollTo().assertIsDisplayed()
            rule.onNode(cardButton(REMOVE_LINK)).performScrollTo().assertIsDisplayed()

            runBlocking { stop.run(id) }

            rule.awaitText(SEASON_SYNC_STOPPED)
            rule.onNode(cardButton(SEASON_SYNC_CHANGE_HELPER)).performScrollTo().assertIsDisplayed()
            rule.onNode(cardButton(REMOVE_LINK)).performScrollTo().assertIsDisplayed()
        }
    }

    // --- Fixtures -------------------------------------------------------------------------------------------------

    /**
     * Asset detail for each of [assetIds], the first shown, each with its card's model (and, for [changeSheet], the
     * first Change helper sheet's, its edit through [outbound]) seeded over the doubles. A case shows another by
     * setting [shown].
     */
    private fun detail(
        assetId: AssetId,
        changeSheet: Boolean = false,
        outbound: OutboundTargets = OutboundTargets.NONE,
    ) = detail(listOf(assetId), changeSheet, outbound)

    private fun detail(assetIds: List<AssetId>, changeSheet: Boolean, outbound: OutboundTargets) {
        assetIds.forEach { owners[it] = seeded(it, changeSheet, outbound) }
        shown = assetIds.first()
        rule.setContent {
            ServiceTagTheme {
                shown?.let { id ->
                    key(id) {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides owners.getValue(id)) {
                            AssetDetailScreen(
                                graph = graph,
                                assetId = id.value,
                                onBack = {}, onEdit = {}, onSetup = {}, onWriteTag = {}, onBackup = {},
                                onLogEvent = { _, _ -> }, onOpenEvent = {}, onOpenAsset = {}, onAddComponent = {},
                                onAddSchedule = {}, onLogOutcome = { _, _ -> }, onOpenSettings = {},
                                onOpenSchedule = {}, onOpenGroup = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private fun seeded(assetId: AssetId, changeSheet: Boolean, outbound: OutboundTargets): ViewModelStoreOwner {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val edit = EditSeasonSyncEntity(graph.seasonSyncBindings, QuietScheduler, graph.uow, graph.clock, outbound)
        val provider = ViewModelProvider.create(
            owner,
            viewModelFactory {
                initializer { cardModel(assetId) }
                initializer {
                    LinkSeasonSyncViewModel(
                        SeasonSyncSheetTarget.Asset(assetId), SeasonSyncSheetPurpose.CHANGE_ENTITY, graph.assets,
                        graph.schedules, graph.haConnections, link, resume,
                        { _, _ -> error("an asset's sheet links no schedule") },
                        { HaListOutcome.Listed(emptyList()) },
                        { graph.seasonSyncBindings.get(it)?.entityId },
                        { id, entityId -> edit.run(id, entityId) },
                    )
                }
            },
        )
        provider["season-sync:${assetId.value}", SeasonSyncBlockViewModel::class.java]
        if (changeSheet) provider["season-sync-sheet:${assetId.value}:1", LinkSeasonSyncViewModel::class.java]
        return owner
    }

    private fun cardModel(assetId: AssetId) = SeasonSyncBlockViewModel(
        assetId = assetId,
        bindings = graph.seasonSyncBindings,
        connections = graph.haConnections,
        secrets = secrets,
        assets = graph.assets,
        transfers = graph.transferRecords,
        setMode = SetSeasonSyncMode(
            graph.seasonSyncBindings, graph.assets, graph.recordSeasonSyncResult, QuietScheduler, graph.uow,
            graph.clock,
        ),
        stop = stop,
        resume = resume,
        runSyncNow = {},
        backgroundAllowed = { false },
        clock = graph.clock,
        platform = NoGrants,
        backgroundOptionLabel = { "" },
        unlink = { unlink.run(it) },
    )

    /** The one connection: https on any network, checked daily, background checks off; its token in the double. */
    private fun connect() = runBlocking {
        val now = System.currentTimeMillis()
        graph.uow.write {
            graph.haConnections.upsert(
                HaConnection(
                    id = CONNECTION_ID,
                    baseUrl = ORIGIN,
                    cadence = SyncCadence.DAILY,
                    networkEligibility = NetworkEligibility.ANY_NETWORK,
                    homeNetworkSsid = null,
                    backgroundChecks = BackgroundChecks.OFF,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        secrets.put(CONNECTION_ID, Secret(TOKEN))
    }

    /** A MANUAL asset, out of season, so an unlinked one offers Start. */
    private fun heater(name: String = ASSET_NAME): AssetId = runBlocking {
        val id = graph.createAsset.run(name = name).id
        graph.setSeasonMode.run(id, SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON))
        id
    }

    private fun linked(assetId: AssetId) {
        runBlocking { link.run(assetId, ENTITY_ID) }
    }

    private fun binding(assetId: AssetId): SeasonSyncBinding? = runBlocking { graph.seasonSyncBindings.get(assetId) }

    /** One of the card's own buttons, never the dialog's. */
    private fun cardButton(label: String): SemanticsMatcher =
        hasText(label) and hasClickAction() and !hasAnyAncestor(isDialog())

    private fun none(text: String): Boolean = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()

    /** Where a button is drawn, read off its own node. */
    private fun top(label: String): Float = rule.onNode(cardButton(label)).fetchSemanticsNode().positionInRoot.y

    private fun inputOf(field: SemanticsNodeInteraction): String? =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.InputText)?.text

    /** An in-memory token store: the card and the link read it; the Keystore is never touched. */
    private class MemorySecrets : SecretStore {
        private val held = ConcurrentHashMap<String, Secret>()
        override suspend fun put(key: String, secret: Secret) {
            held[key] = secret
        }
        override suspend fun get(key: String): Secret? = held[key]
        override suspend fun has(key: String): Boolean = held.containsKey(key)
        override suspend fun delete(key: String) {
            held.remove(key)
        }
        override suspend fun keys(): Set<String> = held.keys.toSet()
    }

    /** No work is scheduled and no read is asked for: nothing here reaches WorkManager or Home Assistant. */
    private object QuietScheduler : SeasonSyncScheduler {
        override suspend fun ensure() = Unit
        override suspend fun cancel() = Unit
        override suspend fun requestFreshRead(assetId: AssetId) = Unit
    }

    /** C26's outbound side as a case scripts it: this phone writes [NEW_ENTITY_ID] and nothing else. */
    private object WritesNewHelper : OutboundTargets by OutboundTargets.NONE {
        override suspend fun writes(connectionId: String, entityId: String): Boolean = entityId == NEW_ENTITY_ID
    }

    /** No grants and no network: the any-network connection never asks for one, so no location line is drawn. */
    private object NoGrants : NetworkPlatform {
        override val apiLevel: Int = Build.VERSION.SDK_INT
        override fun defaultNetworkKind(): DefaultNetworkKind? = null
        override fun connectedWifiName(): String? = null
        override fun listenToDefaultNetwork(onAnswer: (DefaultNetworkAnswer) -> Unit): () -> Unit = {}
        override fun locationOn(): Boolean = false
        override fun preciseLocationGranted(): Boolean = false
        override fun approximateLocationGranted(): Boolean = false
        override fun backgroundLocationGranted(): Boolean = false
        override fun inForeground(): Boolean = true
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val TOKEN = "fictional-token-1"
        const val CONNECTION_ID = "link-controls-connection"
        const val ORIGIN = "https://ha.example:8123"
        const val ENTITY_ID = "input_boolean.example_heater_in_season"
        const val NEW_ENTITY_ID = "input_boolean.example_heater_season"
        const val ASSET_NAME = "Example Heater"
        const val BOILER = "Example Boiler"
        const val POOL_PUMP = "Example Pool Pump"
    }
}
