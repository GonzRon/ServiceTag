package com.loosecannon.servicetag.seasonsync

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.LastAppliedSource
import com.loosecannon.servicetag.core.seasonsync.LinkSeasonSync
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.ResumeSeasonSync
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncBinding
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncScheduler
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SetSeasonSyncMode
import com.loosecannon.servicetag.core.seasonsync.StopSeasonSync
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.asset.AssetDetailScreen
import com.loosecannon.servicetag.ui.asset.AssetEditScreen
import com.loosecannon.servicetag.ui.asset.LinkSeasonSyncViewModel
import com.loosecannon.servicetag.ui.asset.OPERATING_SEASON
import com.loosecannon.servicetag.ui.asset.REVIEW_MAINTENANCE_SCHEDULES
import com.loosecannon.servicetag.ui.asset.SAME_DATES_EVERY_YEAR
import com.loosecannon.servicetag.ui.asset.SEASON_MAY_RUN_ACROSS_THE_NEW_YEAR
import com.loosecannon.servicetag.ui.asset.SEASON_STARTS
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_ENTITY_ID
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_FOLLOW
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_FOLLOWS_HA
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_FORCE_IN
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_FORCE_OUT
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_LINK
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_SCOPE_LINE
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_REFRESH
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_LINK_MANUAL
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_ENTER_MANUALLY
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_CHOOSE_ENTITY
import com.loosecannon.servicetag.ui.asset.CANCEL_BUTTON
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_LINK_CALENDAR
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_LINK_YEAR_ROUND
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_NOT_CHECKED_IN_TIME
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_NOW
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_NO_READING_YET
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_RESUME
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_STOPPED
import com.loosecannon.servicetag.ui.asset.STARTED_AND_ENDED_BY_HAND
import com.loosecannon.servicetag.ui.asset.SeasonSyncBlockViewModel
import com.loosecannon.servicetag.ui.asset.SeasonSyncSheetPurpose
import com.loosecannon.servicetag.ui.asset.YEAR_ROUND
import com.loosecannon.servicetag.ui.asset.YOU_START_AND_END_THE_SEASON
import com.loosecannon.servicetag.ui.asset.notTiedToSeason
import com.loosecannon.servicetag.ui.asset.seasonStrands
import com.loosecannon.servicetag.ui.asset.seasonSyncFollows
import com.loosecannon.servicetag.ui.asset.tallAssetWithAWeeklyCheck
import com.loosecannon.servicetag.ui.attachments.SAVE_LABEL
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.condition.END_SEASON
import com.loosecannon.servicetag.ui.condition.START_SEASON
import com.loosecannon.servicetag.ui.homeassistant.HA_ACCESS_TOKEN
import com.loosecannon.servicetag.ui.homeassistant.HA_COULD_NOT_REACH
import com.loosecannon.servicetag.ui.homeassistant.HA_NOT_CONNECTED
import com.loosecannon.servicetag.ui.homeassistant.HA_SERVER_ADDRESS
import com.loosecannon.servicetag.ui.homeassistant.HA_TITLE
import com.loosecannon.servicetag.ui.homeassistant.HomeAssistantScreen
import com.loosecannon.servicetag.ui.maintenance.SCHEDULES_SECTION
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.nav.ServiceTagRoot
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #16 (C28, row 70) — the Home Assistant surfaces on a real Compose tree. Each case names the one fact only a drawn
 * screen shows; the rules behind them are the JVM rows' (66–69: the view models, the use cases, the strings).
 *
 * **No network and no Keystore** (B9 proved both). The card's and the sheet's models are built here from their
 * primary constructors over the real Room stores, with three in-process doubles: an in-memory [SecretStore] holding
 * `fictional-token-1`, a [SeasonSyncScheduler] that does nothing (no work, no read), and Sync now as a recorder.
 * They are seeded under the keys the screen asks for, through a [ViewModelStoreOwner] of the test's own, the way
 * `DeveloperApiListenerTest` seeds its model; a key the screen stopped using fails loudly (the graph's model would
 * answer P16-11, since the graph's store holds no token). The Home Assistant screen and the editor read the graph
 * with no connection or no token stored, so they read Room alone. The nav shell gets a counting resume hook.
 *
 * Every wait is bounded; every target is unique on its tree. Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class SeasonSyncScreensTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val graph: AppGraph get() = app.graph
    private val secrets = MemorySecrets()
    private val synced = CopyOnWriteArrayList<AssetId>()
    private val owners = mutableListOf<ViewModelStoreOwner>()
    private var shown by mutableStateOf(true)

    private val resumes = AtomicInteger()
    private val resumeHook = ResumeRefresh { resumes.incrementAndGet() }
    private val deepLinks = MutableSharedFlow<Route>(replay = 1, extraBufferCapacity = 4)
    private val snackbars = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

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
        step { rule.runOnIdle { shown = false } }
        step { rule.waitForIdle() }
        step { rule.runOnUiThread { owners.forEach { it.viewModelStore.clear() } } }
        step { runBlocking { graph.uow.write { graph.haConnections.delete(CONNECTION_ID) } } }
        step { clearInstall() }
        failures.firstOrNull()?.let { throw it }
    }

    // --- The Home Assistant screen and the nav shell ---------------------------------------------------------------

    /**
     * Pins: `PasswordVisualTransformation` reaches the platform's semantics — the token field is marked Password and
     * draws one mask character per typed one, while the typed value sits only in the field's own input; no node on
     * the screen draws the token. Target: the field labelled P16-4 (unique). Removing the mask fails the first assert.
     */
    @Test fun theAccessTokenFieldDrawsOnlyTheMask() {
        show { HomeAssistantScreen(graph = graph, onBack = {}) }
        rule.awaitText(HA_NOT_CONNECTED)
        val field = rule.onNode(hasSetTextAction() and hasText(HA_ACCESS_TOKEN))
        field.performScrollTo().performClick()
        field.performTextInput(TOKEN)
        rule.waitUntil(TIMEOUT_MS) { inputOf(field) == TOKEN }

        val config = field.fetchSemanticsNode().config
        assertTrue("the token field is not marked as a password", SemanticsProperties.Password in config)
        val drawn = config[SemanticsProperties.EditableText].text
        assertEquals("what the field draws", MASK.toString().repeat(TOKEN.length), drawn)
        rule.onAllNodes(drawsTheToken, useUnmergedTree = true).assertCountEquals(0)
        val address = rule.onNode(hasSetTextAction() and hasText(HA_SERVER_ADDRESS)).fetchSemanticsNode().config
        assertFalse("the mask is the token field's own", SemanticsProperties.Password in address)
    }

    /** Pins: the Settings row pushes `Route.HomeAssistant` and the real back stack draws its entry. Target: P16-1. */
    @Test fun theSettingsRowOpensTheHomeAssistantScreen() {
        shell()
        rule.runOnIdle { deepLinks.tryEmit(Route.Settings) }
        rule.awaitText(DEVELOPER_API_ROW)

        rule.onNodeWithText(HA_TITLE).performScrollTo().performClick()

        rule.awaitText(HA_SERVER_ADDRESS)
        rule.waitUntil(TIMEOUT_MS) { none(DEVELOPER_API_ROW) }
        rule.onNode(hasSetTextAction() and hasText(HA_SERVER_ADDRESS)).assertIsDisplayed()
        rule.onNodeWithText(HA_NOT_CONNECTED).assertIsDisplayed()
    }

    /** Pins (C23): the nav shell's resume effect calls the hook on every real lifecycle resume, the first included. */
    @Test fun theNavShellCallsTheResumeHookOnEachResume() {
        shell()
        rule.awaitText(DASHBOARD_TITLE)
        rule.waitUntil(TIMEOUT_MS) { resumes.get() >= 1 }
        val before = resumes.get()

        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        rule.waitUntil(TIMEOUT_MS) { resumes.get() >= before + 1 }
    }

    // --- The season card ------------------------------------------------------------------------------------------

    /**
     * Pins: once a binding is enabled the season section draws the three-way control, a radio group under
     * "Operating season", where Start was drawn, and a tap on a mode is taken; neither Start nor End comes back.
     * Targets: P16-23…25 (selectable rows), S40 "Start season".
     */
    @Test fun anEnabledBindingDrawsTheModeControlWhereStartAndEndWere() {
        connect()
        val heater = heater(MANUAL_OUT)
        detail(heater)
        rule.awaitText(START_SEASON)
        rule.onNodeWithText(START_SEASON).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(SEASON_SYNC_LINK).performScrollTo().assertIsDisplayed()

        linked(heater)

        rule.awaitText(SEASON_SYNC_FOLLOW)
        rule.waitUntil(TIMEOUT_MS) { none(START_SEASON) }
        listOf(SEASON_SYNC_FOLLOW, SEASON_SYNC_FORCE_IN, SEASON_SYNC_FORCE_OUT).forEach { label ->
            rule.onNode(hasText(label) and isSelectable() and hasAnyAncestor(hasContentDescription(OPERATING_SEASON)))
                .performScrollTo()
                .assertIsDisplayed()
        }
        rule.onNode(hasText(SEASON_SYNC_FOLLOW) and isSelectable()).assertIsSelected()
        rule.onAllNodesWithText(SEASON_SYNC_LINK).assertCountEquals(0)

        rule.onNode(hasText(SEASON_SYNC_FORCE_IN) and isSelectable()).performClick()
        rule.waitUntil(TIMEOUT_MS) { selected(SEASON_SYNC_FORCE_IN) }
        rule.waitUntil(TIMEOUT_MS) {
            runBlocking { graph.seasonActivations.forAsset(heater) }.any { it.action == SeasonAction.START }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText(START_SEASON).assertCountEquals(0)
        rule.onAllNodesWithText(END_SEASON).assertCountEquals(0)
    }

    /**
     * Pins: Sync now (P16-26) is drawn, reachable and enabled, and its tap reaches the card's runner seam
     * (`runSyncNow`: the runner in the graph, a recorder here) for this asset.
     */
    @Test fun syncNowReachesTheRunnerForThisAsset() {
        connect()
        val heater = heater(MANUAL_OUT)
        linked(heater)
        detail(heater)
        rule.awaitText(SEASON_SYNC_NOW)

        rule.onNodeWithText(SEASON_SYNC_NOW).performScrollTo().assertIsEnabled().performClick()

        rule.waitUntil(TIMEOUT_MS) { synced.isNotEmpty() }
        assertEquals(listOf(heater), synced.toList())
    }

    /**
     * Pins: the source, the last success, HA's change, the provenance and the latest error are each their own drawn
     * line, in C27's sequence, the error under the last success and never in its place. Targets: P16-27, P16-30,
     * P16-33, P16-34, P16-13 (each once on the page).
     */
    @Test fun theStatusLinesAreDrawnApartWithTheErrorUnderTheLastSuccess() {
        connect()
        val heater = heater(MANUAL_OUT)
        linked(heater)
        val now = System.currentTimeMillis()
        rewrite(heater) {
            it.copy(
                observedState = HaSwitchState.ON, observedChangedAt = HA_CHANGED_AT, lastSuccessAt = now - HOUR_MS,
                lastAttemptAt = now, errorKind = SyncErrorKind.UNREACHABLE, errorDetail = null, errorAt = now,
                appliedAction = SeasonAction.START, appliedOn = LocalDate.now().toString(), appliedAt = now - HOUR_MS,
                lastAppliedSource = LastAppliedSource.HOME_ASSISTANT, updatedAt = now,
            )
        }
        detail(heater)
        rule.awaitText(HA_COULD_NOT_REACH)

        val lines = listOf(
            hasText(seasonSyncFollows(ENTITY_ID)),
            hasText(LAST_SUCCESS_WORDS, substring = true),
            hasText(CHANGED_IN_HA_WORDS, substring = true),
            hasText(STARTED_FROM_HA_WORDS, substring = true),
            hasText(HA_COULD_NOT_REACH),
        )
        // Each line read off its own node (the unmerged tree), the last one scrolled into view first.
        rule.onNode(lines.last(), useUnmergedTree = true).performScrollTo()
        lines.forEach { rule.onNode(it, useUnmergedTree = true).assertIsDisplayed() }
        val tops = lines.map { rule.onNode(it, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y }
        assertEquals("each line below the one before it", tops.sorted(), tops)
        assertEquals("one line each", tops.size, tops.distinct().size)
        rule.onAllNodesWithText(SEASON_SYNC_NOT_CHECKED_IN_TIME).assertCountEquals(0)
        rule.onAllNodesWithText(SEASON_SYNC_NO_READING_YET).assertCountEquals(0)
    }

    /** Pins: with no success yet the stale marker (P16-32) is a line of its own, drawn under P16-31. */
    @Test fun theStaleMarkerIsItsOwnLineUnderNoReadingYet() {
        connect()
        val heater = heater(MANUAL_OUT)
        linked(heater)
        detail(heater)
        rule.awaitText(SEASON_SYNC_NOT_CHECKED_IN_TIME)

        rule.onNodeWithText(SEASON_SYNC_NOT_CHECKED_IN_TIME).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(SEASON_SYNC_NO_READING_YET).assertIsDisplayed()
        assertTrue(
            "the marker sits under the last-check line",
            top(SEASON_SYNC_NO_READING_YET) < top(SEASON_SYNC_NOT_CHECKED_IN_TIME),
        )
    }

    // --- The setup sheet ------------------------------------------------------------------------------------------

    /**
     * Pins: the sheet opened by Link draws the mode's sentence (P16-44) above an enabled Save before anything is
     * written, and a strands refusal draws S55 inside the sheet, which stays open. Targets: P16-44, Save, S55.
     */
    @Test fun theSetupSheetShowsItsSentenceBeforeSaveAndKeepsS55Open() {
        connect()
        val heater = heater(SeasonModeCommand(SeasonMode.CALENDAR, "10-01", "04-30"))
        preSeasonSchedule(heater)
        detail(heater, sheet = SeasonSyncSheetPurpose.LINK)
        rule.awaitText(SEASON_SYNC_LINK)
        rule.onNodeWithText(SEASON_SYNC_LINK).performScrollTo().performClick()

        rule.awaitText(SEASON_SYNC_LINK_CALENDAR)
        rule.onNodeWithText(SEASON_SYNC_LINK_CALENDAR).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(SAVE_LABEL).performScrollTo().assertIsEnabled()
        assertTrue("the sentence comes before Save", top(SEASON_SYNC_LINK_CALENDAR) < top(SAVE_LABEL))
        assertNull("nothing is written before Save", binding(heater))

        typeEntityId()
        rule.onNodeWithText(SAVE_LABEL).performScrollTo().performClick()

        val strands = seasonStrands(listOf(PRE_SEASON_TITLE))
        rule.awaitText(strands)
        rule.onNodeWithText(strands).performScrollTo().assertIsDisplayed()
        // Still the sheet: its sentence and its field stay on the tree (a keyboard may cover them, never removes them).
        rule.onNodeWithText(SEASON_SYNC_LINK_CALENDAR).assertExists()
        rule.onNode(hasSetTextAction() and hasText(SEASON_SYNC_ENTITY_ID)).assertExists()
        assertNull("a refusal writes nothing", binding(heater))
    }

    /**
     * #105 row 18 (B3): the setup sheet opens on the Choose entity row — no typed field — and Enter entity ID manually
     * brings the field back, after which typing and Save link exactly as before.
     */
    @Test fun theSetupSheetOffersChooseEntityAndManualEntryBringsTheFieldBack() {
        connect()
        val heater = heater(MANUAL_OUT)
        detail(heater, sheet = SeasonSyncSheetPurpose.LINK)
        rule.awaitText(SEASON_SYNC_LINK)
        rule.onNodeWithText(SEASON_SYNC_LINK).performScrollTo().performClick()

        rule.awaitText(SEASON_SYNC_CHOOSE_ENTITY)
        rule.onAllNodes(hasSetTextAction() and hasText(SEASON_SYNC_ENTITY_ID)).assertCountEquals(0)
        rule.onNodeWithText(SEASON_SYNC_ENTER_MANUALLY).performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText(SEASON_SYNC_ENTITY_ID)).assertExists()

        typeEntityId()
        rule.onNodeWithText(SAVE_LABEL).performScrollTo().performClick()

        rule.waitUntil(TIMEOUT_MS) { binding(heater) != null }
        assertEquals(ENTITY_ID, binding(heater)!!.entityId)
    }

    /**
     * #105 row 19 (B3): Choose entity opens the browser in the sheet's place — its title, its scope line, Refresh and
     * the manual path — and Cancel returns to the form with nothing chosen and nothing written. The rows, the
     * sentences and the pick need a Home Assistant to answer, which the emulator has none of: those are the JVM's
     * (`LinkSeasonSyncViewModelTest`); the read this tap starts is cancelled when the browser closes.
     */
    @Test fun chooseEntityOpensTheBrowserAndCancelReturnsToTheForm() {
        connect()
        val heater = heater(MANUAL_OUT)
        detail(heater, sheet = SeasonSyncSheetPurpose.LINK)
        rule.awaitText(SEASON_SYNC_LINK)
        rule.onNodeWithText(SEASON_SYNC_LINK).performScrollTo().performClick()
        rule.awaitText(SEASON_SYNC_CHOOSE_ENTITY)
        rule.awaitText(SEASON_SYNC_LINK_MANUAL)

        rule.onNodeWithText(SEASON_SYNC_CHOOSE_ENTITY).performScrollTo().performClick()

        rule.awaitText(SEASON_SYNC_SCOPE_LINE)
        rule.onNodeWithText(SEASON_SYNC_REFRESH).assertExists()
        rule.onNodeWithText(SEASON_SYNC_ENTER_MANUALLY).assertExists()
        rule.onAllNodesWithText(SEASON_SYNC_LINK_MANUAL).assertCountEquals(0)

        rule.onNodeWithText(CANCEL_BUTTON).performScrollTo().performClick()

        rule.awaitText(SEASON_SYNC_LINK_MANUAL)
        rule.onNodeWithText(SEASON_SYNC_CHOOSE_ENTITY).assertExists()
        assertNull("nothing chosen, nothing written", binding(heater))
    }

    /**
     * Pins: after a YEAR_ROUND link #78's dialog takes the sheet's place, and its "Review maintenance schedules"
     * scrolls this page's own scroll to the maintenance sections. Targets: P78-1b, P78-2, "Weekly check".
     */
    @Test fun aYearRoundLinkAsksP78AndReviewScrollsThePageToItsSchedules() {
        connect()
        val generator = tallAssetWithAWeeklyCheck(graph)
        detail(generator, sheet = SeasonSyncSheetPurpose.LINK)
        rule.awaitText(SEASON_SYNC_LINK)
        rule.onNodeWithText(SEASON_SYNC_LINK).performScrollTo().performClick()
        rule.awaitText(SEASON_SYNC_LINK_YEAR_ROUND)
        typeEntityId()
        rule.onNodeWithText(SAVE_LABEL).performScrollTo().performClick()

        rule.awaitText(notTiedToSeason(1))
        rule.onNode(hasText(notTiedToSeason(1)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.waitUntil(TIMEOUT_MS) { none(SEASON_SYNC_LINK_YEAR_ROUND) }
        assertNotNull("the link is written before the question", binding(generator))

        // The page back at its top, so the schedules are below the fold until the answer moves it.
        rule.onNode(hasScrollAction() and hasAnyDescendant(hasText(SCHEDULES_SECTION)))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -SCROLL_TO_TOP_PX) }
        rule.waitForIdle()
        rule.onNodeWithText(WEEKLY_CHECK).assertIsNotDisplayed()

        rule.onNodeWithText(REVIEW_MAINTENANCE_SCHEDULES).performClick()

        rule.waitUntil(TIMEOUT_MS) { runCatching { rule.onNodeWithText(WEEKLY_CHECK).assertIsDisplayed() }.isSuccess }
        rule.onNodeWithText(SCHEDULES_SECTION).assertIsDisplayed()
        rule.onAllNodesWithText(REVIEW_MAINTENANCE_SCHEDULES).assertCountEquals(0)
    }

    /**
     * Pins (C-4): a stopped binding is drawn under the CALENDAR branch with P16-41 and Resume, and Resume opens the
     * setup sheet on the mode's sentence, with no entity field and nothing written. Targets: S32, P16-41, P16-40.
     */
    @Test fun aStoppedBindingOnACalendarAssetKeepsResumeWhichOpensTheSheet() {
        connect()
        val heater = heater(MANUAL_OUT)
        linked(heater)
        runBlocking {
            stop.run(heater)
            graph.setSeasonMode.run(heater, SeasonModeCommand(SeasonMode.CALENDAR, "10-01", "04-30"))
        }
        detail(heater)
        rule.awaitText(SEASON_SYNC_STOPPED)

        rule.onNodeWithText(SEASON_STARTS).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(SEASON_SYNC_STOPPED).performScrollTo().assertIsDisplayed()
        assertTrue("the block follows the mode's branch", top(SEASON_STARTS) < top(SEASON_SYNC_STOPPED))
        rule.onNodeWithText(SEASON_SYNC_RESUME).performScrollTo().performClick()

        rule.awaitText(SEASON_SYNC_LINK_CALENDAR)
        rule.onNodeWithText(SEASON_SYNC_LINK_CALENDAR).performScrollTo().assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction() and hasText(SEASON_SYNC_ENTITY_ID)).assertCountEquals(0)
        assertFalse("Resume wrote nothing before Save", checkNotNull(binding(heater)).enabled)
    }

    // --- The editor -----------------------------------------------------------------------------------------------

    /**
     * Pins: while synced the editor's season block draws its three answers disabled, the stored one chosen, with
     * P16-47 under them and S38 gone, and a tap on another answer is not taken. Targets: S29–S31's rows, P16-47.
     */
    @Test fun theEditorsSeasonBlockIsDrawnReadOnlyWhileSynced() {
        connect()
        val heater = heater(MANUAL_OUT)
        linked(heater)
        show { AssetEditScreen(graph = graph, assetId = heater.value, onDone = {}, onBack = {}) }
        rule.awaitText(SEASON_SYNC_FOLLOWS_HA)

        rule.onNodeWithText(SEASON_SYNC_FOLLOWS_HA).performScrollTo().assertIsDisplayed()
        listOf(YEAR_ROUND, SAME_DATES_EVERY_YEAR, STARTED_AND_ENDED_BY_HAND).forEach { answer ->
            rule.onNode(hasText(answer) and isSelectable()).performScrollTo().assertIsNotEnabled()
        }
        rule.onNode(hasText(STARTED_AND_ENDED_BY_HAND) and isSelectable()).assertIsSelected()
        rule.onAllNodesWithText(YOU_START_AND_END_THE_SEASON).assertCountEquals(0)

        rule.onNode(hasText(SAME_DATES_EVERY_YEAR) and isSelectable()).performClick()
        rule.waitForIdle()
        rule.onNode(hasText(SAME_DATES_EVERY_YEAR) and isSelectable()).assertIsNotSelected()
        rule.onNode(hasText(STARTED_AND_ENDED_BY_HAND) and isSelectable()).assertIsSelected()
        rule.onAllNodesWithText(SEASON_MAY_RUN_ACROSS_THE_NEW_YEAR).assertCountEquals(0)
    }

    // --- Fixtures -------------------------------------------------------------------------------------------------

    private fun show(owner: ViewModelStoreOwner? = null, content: @Composable () -> Unit) {
        rule.setContent {
            ServiceTagTheme {
                if (shown) {
                    if (owner == null) {
                        content()
                    } else {
                        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
                    }
                }
            }
        }
    }

    private fun shell() = show {
        ServiceTagRoot(graph = graph, deepLinks = deepLinks, snackbars = snackbars, resumeRefresh = resumeHook)
    }

    /** Asset detail with the card's model (and the sheet's, for [sheet]'s first opening) seeded over the doubles. */
    private fun detail(assetId: AssetId, sheet: SeasonSyncSheetPurpose? = null) {
        val owner = seeded(assetId, sheet)
        show(owner) {
            AssetDetailScreen(
                graph = graph,
                assetId = assetId.value,
                onBack = {}, onEdit = {}, onSetup = {}, onWriteTag = {}, onBackup = {},
                onLogEvent = { _, _ -> }, onOpenEvent = {}, onOpenAsset = {}, onAddComponent = {},
                onAddSchedule = {}, onLogOutcome = { _, _ -> }, onOpenSettings = {},
                onOpenSchedule = {}, onOpenGroup = {},
            )
        }
    }

    private fun seeded(assetId: AssetId, sheet: SeasonSyncSheetPurpose?): ViewModelStoreOwner {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        owners += owner
        val provider = ViewModelProvider.create(
            owner,
            viewModelFactory {
                initializer { cardModel(assetId) }
                initializer {
                    LinkSeasonSyncViewModel(
                        assetId, sheet ?: SeasonSyncSheetPurpose.LINK, graph.assets, graph.schedules,
                        graph.haConnections, link, resume,
                    )
                }
            },
        )
        provider["season-sync:${assetId.value}", SeasonSyncBlockViewModel::class.java]
        if (sheet != null) provider["season-sync-sheet:${assetId.value}:1", LinkSeasonSyncViewModel::class.java]
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
        runSyncNow = { synced += it },
        backgroundAllowed = { false },
        clock = graph.clock,
        platform = NoGrants,
        backgroundOptionLabel = { "" },
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

    private fun heater(mode: SeasonModeCommand): AssetId = runBlocking {
        val id = graph.createAsset.run(name = ASSET_NAME).id
        graph.setSeasonMode.run(id, mode)
        id
    }

    private fun preSeasonSchedule(assetId: AssetId) = runBlocking {
        graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = assetId, targetGroupId = null, title = PRE_SEASON_TITLE,
                timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = LocalDate.now().toString(),
                servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -7,
            ),
        )
    }

    private fun linked(assetId: AssetId) {
        runBlocking { link.run(assetId, ENTITY_ID) }
    }

    private fun binding(assetId: AssetId): SeasonSyncBinding? = runBlocking { graph.seasonSyncBindings.get(assetId) }

    /** The stored binding changed by [change] at the next revision, through the store's compare-and-set. */
    private fun rewrite(assetId: AssetId, change: (SeasonSyncBinding) -> SeasonSyncBinding) {
        val written = runBlocking {
            graph.uow.write {
                val stored = checkNotNull(graph.seasonSyncBindings.get(assetId))
                graph.seasonSyncBindings.update(change(stored).copy(revision = stored.revision + 1))
            }
        }
        assertTrue("the fixture's binding was not rewritten", written)
    }

    /** Types the fictional entity id into the sheet's field and waits for the field to hold it. */
    private fun typeEntityId() {
        val field = rule.onNode(hasSetTextAction() and hasText(SEASON_SYNC_ENTITY_ID))
        field.performScrollTo().performClick()
        field.performTextInput(ENTITY_ID)
        rule.waitUntil(TIMEOUT_MS) { inputOf(field) == ENTITY_ID }
    }

    private fun none(text: String): Boolean = rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()

    private fun selected(label: String): Boolean = rule.onNode(hasText(label) and isSelectable())
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    /** Where a text is drawn, read off its own node (the unmerged tree), so a merging parent cannot blur two lines. */
    private fun top(text: String): Float =
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    private fun inputOf(field: SemanticsNodeInteraction): String? =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.InputText)?.text

    /** An in-memory token store: the card, the link and the resume read it; the Keystore is never touched. */
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
        const val HOUR_MS = 3_600_000L
        const val SCROLL_TO_TOP_PX = 100_000f
        const val MASK = '•'
        const val TOKEN = "fictional-token-1"
        const val CONNECTION_ID = "screens-connection"
        const val ORIGIN = "https://ha.example:8123"
        const val ENTITY_ID = "input_boolean.example_heater_in_season"
        const val ASSET_NAME = "Example Heater"
        const val PRE_SEASON_TITLE = "Pre-season service"
        const val WEEKLY_CHECK = "Weekly check"
        const val HA_CHANGED_AT = "2026-02-10T08:55:00+00:00"
        const val DEVELOPER_API_ROW = "Developer API"
        const val DASHBOARD_TITLE = "ServiceTag"
        const val LAST_SUCCESS_WORDS = "Last successful check "
        const val CHANGED_IN_HA_WORDS = "Changed in Home Assistant "
        const val STARTED_FROM_HA_WORDS = "Started from Home Assistant on "
        val MANUAL_OUT = SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON)

        /** Any drawn text — a label, a line or a field's shown value — that carries the token. */
        val drawsTheToken = SemanticsMatcher("draws the token") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { TOKEN in it.text } ||
                node.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(TOKEN) == true
        }
    }
}
