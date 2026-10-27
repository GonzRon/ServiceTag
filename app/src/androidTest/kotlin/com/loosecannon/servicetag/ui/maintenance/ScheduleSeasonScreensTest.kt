package com.loosecannon.servicetag.ui.maintenance

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.LinkScheduleSeason
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncScheduler
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.UnlinkScheduleSeason
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.asset.CANCEL_BUTTON
import com.loosecannon.servicetag.ui.asset.LinkSeasonSyncViewModel
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_CHOOSE_ENTITY
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_ENTER_MANUALLY
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_ENTITY_ID
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_ENTITY_ID_HELP
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_FOLLOW
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_NO_READING_YET
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_SCOPE_LINE
import com.loosecannon.servicetag.ui.asset.SeasonSyncSheetPurpose
import com.loosecannon.servicetag.ui.asset.SeasonSyncSheetTarget
import com.loosecannon.servicetag.ui.attachments.SAVE_LABEL
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #125 h19 (U-C8) — **the UI proof** of a schedule's season row on a real Compose tree: the row in its three states
 * (the asset's season, following Home Assistant, stranded), Follow Home Assistant opening #16's link sheet fully
 * expanded with the schedule as its target, and Use the asset's season behind its confirm.
 *
 * The models are seeded over the doubles, as `SeasonSyncScreensTest` seeds the asset sheet's: the token lives in an
 * in-memory store, no work is scheduled, the entity list is answered in process, and the post-link check is recorded,
 * never sent — no socket is opened. The words are the getters', so the proof holds in the phone's language; their
 * English is pinned by h18. Every name and entity id is fictional. Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleSeasonScreensTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val graph: AppGraph get() = app.graph
    private val secrets = MemorySecrets()
    private val checks = CopyOnWriteArrayList<ScheduleId>()
    private val owners = mutableListOf<ViewModelStoreOwner>()
    private var shown by mutableStateOf(true)

    private val listEntities: suspend () -> HaListOutcome? = { HaListOutcome.Listed(HELPERS) }

    private val link by lazy {
        LinkScheduleSeason(
            graph.schedules, graph.assets, graph.transferRecords, graph.scheduleSeasonBindings, graph.haConnections,
            secrets, graph.recomputeSchedules, QuietScheduler, graph.uow, graph.clock, graph.outboundTargets,
        )
    }
    private val unlink by lazy {
        UnlinkScheduleSeason(
            graph.schedules, graph.scheduleSeasonBindings, graph.recomputeSchedules, QuietScheduler, graph.uow,
            graph.clock,
        )
    }

    @Before fun setUp() {
        clearInstall()
        assertNull(
            "this install already holds a Home Assistant connection, and this class never overwrites one",
            runBlocking { graph.haConnections.get() },
        )
    }

    /** The content leaves first, so no model reads a cleared store; each step runs on its own. */
    @After fun tearDown() {
        val failures = mutableListOf<Throwable>()
        fun step(block: () -> Unit) = try {
            block()
        } catch (t: Throwable) {
            failures += t
        }
        step { rule.runOnIdle { shown = false } }
        step { rule.waitForIdle() }
        step { rule.runOnUiThread { owners.forEach { it.viewModelStore.clear() } } }
        step { runBlocking { graph.uow.write { graph.haConnections.delete(CONNECTION_ID) } } }
        step { clearInstall() }
        failures.firstOrNull()?.let { throw it }
    }

    /** P125-1 names the asset; Follow Home Assistant is drawn, and Use the asset's season is not. */
    @Test fun anAssetSourcedScheduleShowsItsAssetsSeasonAndFollow() {
        connect()
        val schedule = heaterSchedule()
        row(schedule)
        rule.awaitText(scheduleSeasonOfAsset(ASSET_NAME))
        rule.onNode(hasText(SEASON_SYNC_FOLLOW) and hasClickAction()).performScrollTo().assertIsDisplayed()
        none(SCHEDULE_SEASON_USE_ASSET)
    }

    /** A held owner's schedule (the screen's `editable` false) draws the season and offers neither action. */
    @Test fun aScheduleThatCannotBeWrittenDrawsTheSeasonOnly() {
        connect()
        val schedule = heaterSchedule()
        row(schedule, editable = false)
        rule.awaitText(scheduleSeasonOfAsset(ASSET_NAME))
        none(SEASON_SYNC_FOLLOW)
        none(SCHEDULE_SEASON_USE_ASSET)
    }

    /**
     * Follow Home Assistant opens the sheet expanded: its Choose entity row, Save and Cancel are on screen at once,
     * with no scroll, and P125-8, never the asset's P16-43, on the row and the typed field. The browser lists only the on/off
     * helper; the pick and Save link the **schedule** — the row then says P125-2 — the post-link check is asked for it,
     * and the asset gets no binding.
     */
    @Test fun followOpensTheSheetFullyExpandedAndLinksTheSchedule() {
        connect()
        val schedule = heaterSchedule()
        row(schedule)
        rule.awaitText(SEASON_SYNC_FOLLOW)
        rule.onNode(hasText(SEASON_SYNC_FOLLOW) and hasClickAction()).performScrollTo().performClick()

        rule.awaitText(SEASON_SYNC_CHOOSE_ENTITY)
        rule.onAllNodesWithText(SEASON_SYNC_CHOOSE_ENTITY)[0].assertIsDisplayed()
        rule.onNodeWithText(SAVE_LABEL).assertIsDisplayed()
        rule.onNodeWithText(CANCEL_BUTTON).assertIsDisplayed()
        // H4 review I1: a schedule's sheet draws P125-8, never the asset's P16-43, under the Choose entity row and
        // under the typed field.
        rule.awaitText(SCHEDULE_SEASON_ENTITY_ID_HELP)
        none(SEASON_SYNC_ENTITY_ID_HELP)
        rule.onNodeWithText(SEASON_SYNC_ENTER_MANUALLY).performClick()
        rule.awaitText(SEASON_SYNC_ENTITY_ID)
        rule.awaitText(SCHEDULE_SEASON_ENTITY_ID_HELP)
        none(SEASON_SYNC_ENTITY_ID_HELP)

        rule.onAllNodesWithText(SEASON_SYNC_CHOOSE_ENTITY)[0].performClick()
        rule.awaitText(SEASON_SYNC_SCOPE_LINE)
        rule.awaitText(HELPER.entityId)
        none(OTHER_DOMAIN.entityId)
        rule.onNode(hasText(HELPER.entityId) and hasClickAction()).performClick()
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithText(SEASON_SYNC_SCOPE_LINE).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText(SAVE_LABEL).performClick()

        rule.awaitText(scheduleSeasonFollows(HELPER.entityId))
        assertEquals(HELPER.entityId, runBlocking { graph.scheduleSeasonBindings.get(schedule)?.entityId })
        assertEquals(listOf(schedule), checks.toList())
        assertNull(runBlocking { graph.seasonSyncBindings.get(heater()) })
    }

    /** Following: P125-2 with the entity, #16's P16-31, and Use the asset's season in Follow's place. */
    @Test fun aFollowingScheduleShowsItsEntityAndItsStatus() {
        connect()
        val schedule = heaterSchedule()
        runBlocking { link.run(schedule, HELPER.entityId) }
        row(schedule)
        rule.awaitText(scheduleSeasonFollows(HELPER.entityId))
        rule.awaitText(SEASON_SYNC_NO_READING_YET)
        rule.onNode(hasText(SCHEDULE_SEASON_USE_ASSET) and hasClickAction()).performScrollTo().assertIsDisplayed()
        none(SEASON_SYNC_FOLLOW)
    }

    /**
     * Use the asset's season asks P125-4 first; Cancel keeps the link. Asked again and confirmed, the binding goes,
     * the source is `ASSET` again, and the row says P125-1.
     */
    @Test fun useTheAssetsSeasonAsksFirstThenReturnsTheSchedule() {
        connect()
        val schedule = heaterSchedule()
        runBlocking { link.run(schedule, HELPER.entityId) }
        row(schedule)
        rule.awaitText(SCHEDULE_SEASON_USE_ASSET)

        rule.onNode(hasText(SCHEDULE_SEASON_USE_ASSET) and hasClickAction()).performScrollTo().performClick()
        rule.awaitText(scheduleSeasonUseAssetConfirm(ASSET_NAME))
        rule.onNode(hasText(DIALOG_CANCEL) and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodesWithText(scheduleSeasonUseAssetConfirm(ASSET_NAME)).fetchSemanticsNodes().isEmpty()
        }
        rule.awaitText(scheduleSeasonFollows(HELPER.entityId))
        assertEquals(ScheduleSeasonSource.OWN, runBlocking { graph.schedules.get(schedule)?.seasonSource })

        rule.onNode(hasText(SCHEDULE_SEASON_USE_ASSET) and hasClickAction()).performScrollTo().performClick()
        rule.awaitText(scheduleSeasonUseAssetConfirm(ASSET_NAME))
        rule.onNode(hasText(SCHEDULE_SEASON_USE_ASSET) and hasAnyAncestor(isDialog())).performClick()
        rule.awaitText(scheduleSeasonOfAsset(ASSET_NAME))
        assertNull(runBlocking { graph.scheduleSeasonBindings.get(schedule) })
        assertEquals(ScheduleSeasonSource.ASSET, runBlocking { graph.schedules.get(schedule)?.seasonSource })
    }

    /** The connection gone (its binding with it): P125-5, and both actions. */
    @Test fun aStrandedScheduleSaysSoWithBothActions() {
        connect()
        val schedule = heaterSchedule()
        runBlocking { link.run(schedule, HELPER.entityId) }
        runBlocking { graph.uow.write { graph.haConnections.delete(CONNECTION_ID) } }
        row(schedule)
        rule.awaitText(SCHEDULE_SEASON_STRANDED)
        rule.onNode(hasText(SEASON_SYNC_FOLLOW) and hasClickAction()).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText(SCHEDULE_SEASON_USE_ASSET) and hasClickAction()).performScrollTo().assertIsDisplayed()
        assertEquals(ScheduleSeasonSource.OWN, runBlocking { graph.schedules.get(schedule)?.seasonSource })
    }

    // --- Fixtures ----------------------------------------------------------------------------------------------------

    /** The one connection, https on any network, checked daily; its token in the double. */
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

    private var heaterId: AssetId? = null

    private fun heater(): AssetId = checkNotNull(heaterId)

    /** "Example Heater" and its weekly check, on the asset's season. */
    private fun heaterSchedule(): ScheduleId = runBlocking {
        val asset = graph.createAsset.run(name = ASSET_NAME).id
        heaterId = asset
        graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = asset, targetGroupId = null, title = SCHEDULE_TITLE,
                timeInterval = 1, timeUnit = RecurrenceUnit.WEEK, anchorOn = LocalDate.now().toString(),
                servicePolicy = ServicePolicy.CONTINUOUS, policyOffsetDays = null,
            ),
        ).id
    }

    /** The row for [schedule], its model and its first sheet's seeded over the doubles. */
    private fun row(schedule: ScheduleId, editable: Boolean = true) {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        owners += owner
        val provider = ViewModelProvider.create(
            owner,
            viewModelFactory {
                initializer {
                    ScheduleSeasonRowViewModel(
                        schedule, graph.schedules, graph.assets, graph.scheduleSeasonBindings, graph.haConnections,
                        secrets, graph.transferRecords, unlink, graph.clock,
                    )
                }
                initializer {
                    LinkSeasonSyncViewModel(
                        SeasonSyncSheetTarget.Schedule(schedule), SeasonSyncSheetPurpose.LINK, graph.assets,
                        graph.schedules, graph.haConnections, graph.linkSeasonSync, graph.resumeSeasonSync,
                        { id, entity ->
                            link.run(id, entity)
                            checks += id
                        },
                        listEntities,
                    )
                }
            },
        )
        provider["schedule-season:${schedule.value}", ScheduleSeasonRowViewModel::class.java]
        provider["season-sync-sheet:schedule:${schedule.value}:1", LinkSeasonSyncViewModel::class.java]
        rule.setContent {
            ServiceTagTheme {
                if (shown) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        // The detail screen's own host: its scrolling column, so performScrollTo has a scroll to use.
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            ScheduleSeasonRow(graph = graph, scheduleId = schedule.value, editable = editable)
                        }
                    }
                }
            }
        }
    }

    private fun none(text: String) {
        rule.waitForIdle()
        assertEquals("\"$text\" is drawn", 0, rule.onAllNodesWithText(text).fetchSemanticsNodes().size)
    }

    /** An in-memory token store: the link and the row read it; the Keystore is never touched. */
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

    /** No work is scheduled: nothing here reaches WorkManager or Home Assistant. */
    private object QuietScheduler : SeasonSyncScheduler {
        override suspend fun ensure() = Unit
        override suspend fun cancel() = Unit
        override suspend fun requestFreshRead(assetId: AssetId) = Unit
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val CONNECTION_ID = "schedule-season-screens-connection"
        const val ORIGIN = "https://ha.example:8123"
        const val TOKEN = "fictional-token-schedule-season"
        const val ASSET_NAME = "Example Heater"
        const val SCHEDULE_TITLE = "Example opening service"
        val HELPER = HaEntityCandidate("input_boolean.example_season", "Example season")
        val OTHER_DOMAIN = HaEntityCandidate("input_select.example_status", "Example status")
        val HELPERS = listOf(HELPER, OTHER_DOMAIN)
        /** The confirm's Cancel: the schedule detail's own word. */
        val DIALOG_CANCEL: String get() = localized(R.string.maintenance_cancel)
    }
}
