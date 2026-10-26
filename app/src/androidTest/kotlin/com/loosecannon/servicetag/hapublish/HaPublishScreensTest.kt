package com.loosecannon.servicetag.hapublish

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.hapublish.DecommissionState
import com.loosecannon.servicetag.core.hapublish.DecommissionTombstone
import com.loosecannon.servicetag.core.hapublish.INVALID_SERVICE_DATE
import com.loosecannon.servicetag.core.hapublish.PublishBinding
import com.loosecannon.servicetag.core.hapublish.PublishDelivery
import com.loosecannon.servicetag.core.hapublish.PublishErrorKind
import com.loosecannon.servicetag.core.hapublish.PublishKind
import com.loosecannon.servicetag.core.hapublish.RemoteEntity
import com.loosecannon.servicetag.core.hapublish.STATUS_DEGRADED
import com.loosecannon.servicetag.core.hapublish.STATUS_DOWN
import com.loosecannon.servicetag.core.hapublish.STATUS_UP
import com.loosecannon.servicetag.core.hapublish.ValidationResult
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleRole
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.seasonsync.BackgroundChecks
import com.loosecannon.servicetag.core.seasonsync.HaConnection
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SecretStore
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.usecase.CompletionCommand
import com.loosecannon.servicetag.core.usecase.ConditionCommand
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localizedDate
import com.loosecannon.servicetag.l10n.localizedDateTime
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.asset.AssetDetailScreen
import com.loosecannon.servicetag.ui.asset.DateMeaning
import com.loosecannon.servicetag.ui.asset.LinkPublishSheet
import com.loosecannon.servicetag.ui.asset.LinkPublishViewModel
import com.loosecannon.servicetag.ui.asset.PublishBlock
import com.loosecannon.servicetag.ui.asset.PublishBlockViewModel
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.homeassistant.HomeAssistantScreen
import com.loosecannon.servicetag.ui.maintenance.CompletionFlowHost
import com.loosecannon.servicetag.ui.maintenance.CompletionOutcome
import com.loosecannon.servicetag.ui.maintenance.ScheduleDetailScreen
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #113/#114 C37 (row 91) — what publishing and the Season Start service draw, on a real Compose tree: the season
 * card's readiness line, the role's confirm and a refusal, the completion line, both publishing blocks in their
 * states, the setup sheet's refusals, and the Home Assistant screen's write disclosure and Forget warning. Each case
 * names the one fact only a drawn screen shows; the rules behind them are the JVM rows' (the view models, the
 * strings, the delivery steps, C9). Content is set directly, never navigated to (#62). Every sentence is read from
 * its resource through the target context (`R.string`, `R.plurals`), formatted with the app's own date display.
 *
 * **No network and no Keystore.** A block is the real [PublishBlock] over the real [PublishBlockViewModel], built from
 * its primary constructor over the graph's Room stores with one in-process double, an in-memory [SecretStore] holding
 * `fictional-token-1` (or holding nothing, for the token's own gate). The rows are the case's: a connection, an
 * enabled binding of this installation (or of another, for a copied setup) and its delivery record, written in one
 * transaction. The app's own observer sees them too: its projection finds each desired value already what it would
 * write (a status case records its condition first; a date asset has no Season Start service, so `1970-01-01`), and
 * its delivery pass finds no token in the Keystore and sends nothing — so the drawn state is the seeded one. A case
 * with obligations stores no connection at all: with none, no pass reaches a tombstone, so a PENDING one stays so.
 * The setup sheet's model is seeded under the key the sheet asks for, its fresh read a scripted [Validation].
 *
 * Every wait is bounded; every target is unique on its tree. Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class HaPublishScreensTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val graph: AppGraph get() = app.graph
    private val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val secrets = MemorySecrets()
    private val tombstoneIds = mutableListOf<String>()
    private val setUps = AtomicInteger()
    private val checks = AtomicInteger()
    private val flowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var completion: Job? = null

    /** The setup sheet's fresh read, answered in process; a case scripts it before Check helper. */
    @Volatile private var validation: (String) -> Validation = { Validation.NoConnection }

    @Before fun setUp() {
        clearInstall()
        assertNull(
            "this install already holds a Home Assistant connection, and this class never overwrites one",
            runBlocking { graph.haConnections.get() },
        )
    }

    @After fun tearDown() {
        val failures = mutableListOf<Throwable>()
        fun step(block: () -> Unit) = try {
            block()
        } catch (t: Throwable) {
            failures += t
        }
        step { graph.completionFlow.cancel() }
        step { runBlocking { withTimeout(TIMEOUT_MS) { completion?.join() } } }
        step { flowScope.cancel() }
        // The connection's CASCADE takes the bindings and their deliveries; obligations have no foreign key.
        step { runBlocking { graph.uow.write { graph.haConnections.delete(CONNECTION_ID) } } }
        step { runBlocking { graph.uow.write { tombstoneIds.forEach { graph.publishDecommissions.delete(it) } } } }
        step { clearInstall() }
        failures.firstOrNull()?.let { throw it }
    }

    // --- The equipment status block (#113, AC113) ----------------------------------------------------------------

    /**
     * Pins: with no condition recorded the block is drawn — its title and switch, the helper, the mapping and the
     * delivery limit line — and says P113-7; Publish now is not offered, since nothing is sent.
     */
    @Test fun statusBlockWaitingSaysNothingIsSentAndOffersNoPublishNow() {
        val asset = statusBound(condition = null) { PublishDelivery(it.id, desiredValue = null, desiredRevision = 1, desiredAt = null) }
        block(asset, PublishKind.EQUIPMENT_STATUS)

        awaitText(R.string.ha_publish_waiting_for_status)
        exists(R.string.ha_publish_status_title)
        rule.onNodeWithText(STATUS_ENTITY).assertExists()
        listOf(STATUS_UP, STATUS_DEGRADED, STATUS_DOWN).forEach { rule.onNodeWithText(it).assertExists() }
        exists(R.string.ha_publish_delivery_limit)
        none(R.string.ha_publish_now)
    }

    /** Pins: a recorded condition not yet sent draws P113-8 and offers Publish now (P113-12), enabled. */
    @Test fun statusBlockPendingSaysNotPublishedYetAndOffersPublishNow() {
        val asset = statusBound { PublishDelivery(it.id, desiredValue = STATUS_UP, desiredRevision = 1, desiredAt = now()) }
        block(asset, PublishKind.EQUIPMENT_STATUS)

        awaitText(R.string.ha_publish_not_published_yet)
        rule.onNode(hasText(text(R.string.ha_publish_now)) and hasClickAction()).assertIsEnabled()
        none(R.string.ha_publish_copied_installation)
    }

    /**
     * Pins: our POST read back at the current pair draws the published line (P113-10) and the verified line (P113-11)
     * as two lines, each naming the value and its own time; the state's own line is not drawn beside them.
     */
    @Test fun statusBlockPublishedDrawsThePublishedAndTheMatchesLinesApart() {
        val at = millisOf(SEEN_AT)
        val asset = statusBound {
            PublishDelivery(
                it.id, desiredValue = STATUS_UP, desiredRevision = 1, desiredAt = at,
                lastPublishedValue = STATUS_UP, lastPublishedAt = at, publishedBindingRevision = 1,
                publishedDesiredRevision = 1, lastVerifiedValue = STATUS_UP, lastVerifiedAt = at,
                verifiedBindingRevision = 1, verifiedDesiredRevision = 1,
            )
        }
        block(asset, PublishKind.EQUIPMENT_STATUS)

        val published = text(R.string.ha_publish_published, STATUS_UP, localizedDateTime(SEEN_AT))
        val matches = text(R.string.ha_publish_matches, STATUS_UP, localizedDateTime(SEEN_AT))
        rule.awaitText(published)
        rule.onNodeWithText(published, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(matches, useUnmergedTree = true).assertExists()
        none(R.string.ha_publish_not_published_yet)
    }

    /** Pins: Home Assistant already holding the value draws the verified line (P113-11) and no published line. */
    @Test fun statusBlockMatchesDrawsTheVerifiedLineAlone() {
        val at = millisOf(SEEN_AT)
        val asset = statusBound {
            PublishDelivery(
                it.id, desiredValue = STATUS_UP, desiredRevision = 1, desiredAt = at,
                lastVerifiedValue = STATUS_UP, lastVerifiedAt = at, verifiedBindingRevision = 1, verifiedDesiredRevision = 1,
            )
        }
        block(asset, PublishKind.EQUIPMENT_STATUS)

        rule.awaitText(text(R.string.ha_publish_matches, STATUS_UP, localizedDateTime(SEEN_AT)))
        rule.onAllNodesWithText(text(R.string.ha_publish_published, STATUS_UP, localizedDateTime(SEEN_AT)))
            .assertCountEquals(0)
        none(R.string.ha_publish_not_published_yet)
    }

    /** Pins: a current error of the owner's kind draws its own sentence (here P113-18, `WRITE_FORBIDDEN`). */
    @Test fun statusBlockErrorDrawsTheErrorsOwnSentence() {
        val asset = statusBound {
            PublishDelivery(
                it.id, desiredValue = STATUS_UP, desiredRevision = 1, desiredAt = now(),
                errorKind = PublishErrorKind.WRITE_FORBIDDEN, errorAt = now(), errorBindingRevision = 1,
                errorDesiredRevision = 1,
            )
        }
        block(asset, PublishKind.EQUIPMENT_STATUS)

        awaitText(R.string.ha_publish_write_forbidden)
        none(R.string.ha_publish_not_published_yet)
    }

    /**
     * Pins: a binding activated on another installation draws P113-21 and offers the sheet's Check helper (P113-25)
     * to turn it on again here, never Publish now.
     */
    @Test fun statusBlockCopiedInstallationSaysThisPhoneDoesNotPublish() {
        val asset = statusBound(installation = OTHER_INSTALLATION) {
            PublishDelivery(it.id, desiredValue = STATUS_UP, desiredRevision = 1, desiredAt = now())
        }
        block(asset, PublishKind.EQUIPMENT_STATUS)

        awaitText(R.string.ha_publish_copied_installation)
        exists(R.string.ha_publish_check_helper)
        none(R.string.ha_publish_now)
    }

    // --- The Season Start date block (#114, AC114) -------------------------------------------------------------------

    /**
     * Pins: an enabled date binding whose `1970-01-01` is not yet read back draws its meaning row — C9's line for the
     * asset (no Season Start service) beside the invalid date it maps to — and P114-36 beside its own lines.
     */
    @Test fun dateBlockPendingDrawsTheInvalidDateMeaningAndNotYetCleared() {
        val asset = dateBound()
        block(asset, PublishKind.SEASON_SERVICE_DATE)

        awaitText(R.string.ha_publish_date_title)
        rule.awaitText(text(R.string.ha_publish_date_not_yet_cleared, DATE_ENTITY, INVALID_SERVICE_DATE))
        exists(R.string.season_start_not_ready_no_role)
        rule.onNodeWithText(INVALID_SERVICE_DATE).assertExists()
        rule.onAllNodesWithText(text(R.string.ha_publish_date_cannot_reach, DATE_ENTITY, INVALID_SERVICE_DATE))
            .assertCountEquals(0)
    }

    /**
     * Pins: the gate holding every send until the owner acts — no token on this phone — draws P16-11 and P114-37
     * (ServiceTag cannot reach the helper), never P114-36.
     */
    @Test fun dateBlockHeldForTheTokenDrawsCannotReach() {
        val asset = dateBound()
        block(asset, PublishKind.SEASON_SERVICE_DATE, MemorySecrets())

        awaitText(R.string.ha_enter_token_again)
        rule.awaitText(text(R.string.ha_publish_date_cannot_reach, DATE_ENTITY, INVALID_SERVICE_DATE))
        rule.onAllNodesWithText(text(R.string.ha_publish_date_not_yet_cleared, DATE_ENTITY, INVALID_SERVICE_DATE))
            .assertCountEquals(0)
    }

    /** Pins: a copied setup draws P113-21 and neither obligation sentence (P114-36, P114-37): this phone does not publish. */
    @Test fun dateBlockCopiedInstallationDrawsNeitherObligationLine() {
        val asset = dateBound(installation = OTHER_INSTALLATION)
        block(asset, PublishKind.SEASON_SERVICE_DATE)

        awaitText(R.string.ha_publish_copied_installation)
        rule.onAllNodesWithText(text(R.string.ha_publish_date_not_yet_cleared, DATE_ENTITY, INVALID_SERVICE_DATE))
            .assertCountEquals(0)
        rule.onAllNodesWithText(text(R.string.ha_publish_date_cannot_reach, DATE_ENTITY, INVALID_SERVICE_DATE))
            .assertCountEquals(0)
    }

    /**
     * Pins (C34): an asset that still owes helpers `1970-01-01` draws its date block for them alone — no binding, no
     * connection — each in its sentence: P114-36 while ServiceTag still tries, P114-37 once it cannot; only the latter
     * offers P114-38, so exactly one such button is drawn.
     */
    @Test fun dateBlockDrawsEachObligationAndOnlyAnUndeliverableOneOffersClearedByHand() {
        val asset = heater(MANUAL_IN)
        owe(asset, DATE_ENTITY, DecommissionState.PENDING, requestedAt = now() - HOUR_MS)
        owe(asset, OTHER_DATE_ENTITY, DecommissionState.UNDELIVERABLE, requestedAt = now())
        block(asset, PublishKind.SEASON_SERVICE_DATE)

        awaitText(R.string.ha_publish_date_title)
        rule.awaitText(text(R.string.ha_publish_date_not_yet_cleared, DATE_ENTITY, INVALID_SERVICE_DATE))
        rule.awaitText(text(R.string.ha_publish_date_cannot_reach, OTHER_DATE_ENTITY, INVALID_SERVICE_DATE))
        rule.onAllNodes(hasText(text(R.string.ha_publish_date_cleared_by_hand)) and hasClickAction()).assertCountEquals(1)
    }

    // --- The season card's readiness line (#114, AC114-4) -------------------------------------------------------------

    /**
     * Pins: once a Season Start service covers the asset, the season card draws the readiness line — not ready since
     * the role's day (P114-12) — then its source line (P114-53) and its cutoff line (P114-50), each its own line.
     */
    @Test fun readinessLineSaysNotReadyFromTheRolesDayWithItsSourceAndCutoff() {
        val asset = heater(MANUAL_IN)
        val schedule = schedule(asset)
        role(schedule)
        detail(asset)

        val day = localizedDate(today())
        val line = text(R.string.season_start_not_ready_since_commissioning, day)
        rule.awaitText(line)
        rule.onNodeWithText(line, useUnmergedTree = true).performScrollTo()
        rule.onNodeWithText(text(R.string.season_start_source_commissioning, day), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(text(R.string.season_start_cutoff, day), useUnmergedTree = true).assertExists()
        assertTrue("the readiness line comes first", top(line) < top(text(R.string.season_start_cutoff, day)))
    }

    /** Pins: the role's schedule completed today (one tap, elected) draws P114-7, ready and serviced today. */
    @Test fun readinessLineSaysReadyOnceTheServiceIsCompleted() {
        val asset = heater(MANUAL_IN)
        val schedule = schedule(asset)
        role(schedule)
        runBlocking {
            graph.completeSchedule.run(
                schedule,
                CompletionCommand(occurredOn = today().toString(), tzId = ZoneId.systemDefault().id),
            )
        }
        detail(asset)

        rule.awaitText(text(R.string.season_start_ready, localizedDate(today())))
        rule.onAllNodesWithText(text(R.string.season_start_not_ready_since_commissioning, localizedDate(today())))
            .assertCountEquals(0)
    }

    // --- The role's confirm and a refusal (#114, C33) ----------------------------------------------------------------

    /**
     * Pins: "Use as Season Start service" (P114-2) opens the confirm — P114-4 naming the schedule, the asset and today,
     * and the one-tap note P114-5 — whose button sets the role: the dialog closes, the role line (P114-41) and "Stop
     * using" (P114-3) are drawn, and the stored schedule carries the role from today.
     */
    @Test fun roleConfirmAsksWithTheDayAndTheOneTapNoteThenDrawsTheRoleLine() {
        val asset = heater(MANUAL_IN)
        val schedule = schedule(asset)
        scheduleDetail(schedule)
        val use = text(R.string.season_start_use)
        rule.awaitText(use)

        rule.onNode(hasText(use) and !hasAnyAncestor(isDialog())).performScrollTo().performClick()

        val question = text(R.string.season_start_confirm_asset, SCHEDULE_TITLE, ASSET_NAME, localizedDate(today()))
        rule.awaitText(question)
        rule.onNode(hasText(question) and hasAnyAncestor(isDialog())).assertExists()
        rule.onNode(hasText(text(R.string.season_start_quick_note)) and hasAnyAncestor(isDialog())).assertExists()
        rule.onNode(hasText(use) and hasAnyAncestor(isDialog())).performClick()

        rule.awaitText(text(R.string.season_start_role_line, localizedDate(today())))
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        exists(R.string.season_start_stop)
        val stored = checkNotNull(runBlocking { graph.schedules.get(schedule) })
        assertEquals(ScheduleRole.SEASON_START, stored.role)
        assertEquals(today().toString(), stored.roleSinceOn)
    }

    /**
     * Pins: a refusal keeps the confirm open with its sentence — an asset in use all year has no season to start
     * (P114-17, naming it) — and nothing is written.
     */
    @Test fun roleConfirmKeepsAYearRoundRefusalInTheDialog() {
        val asset = heater(mode = null)
        val schedule = schedule(asset)
        scheduleDetail(schedule)
        val use = text(R.string.season_start_use)
        rule.awaitText(use)

        rule.onNode(hasText(use) and !hasAnyAncestor(isDialog())).performScrollTo().performClick()
        rule.awaitText(text(R.string.season_start_quick_note))
        rule.onNode(hasText(use) and hasAnyAncestor(isDialog())).performClick()

        val refusal = text(R.string.season_start_refused_year_round, ASSET_NAME)
        rule.awaitText(refusal)
        rule.onNode(hasText(refusal) and hasAnyAncestor(isDialog())).assertExists()
        assertEquals(ScheduleRole.NONE, checkNotNull(runBlocking { graph.schedules.get(schedule) }).role)
    }

    // --- The completion line (#114, C33) -----------------------------------------------------------------------------

    /**
     * Pins: the one completion prompt (the graph's flow, as every surface draws it) says, before the write, that
     * completing an asset's Season Start service marks it ready (P114-14, naming the asset); Cancel writes nothing.
     */
    @Test fun completionPromptSaysCompletingMarksTheAssetReady() {
        val asset = heater(MANUAL_IN)
        val schedule = schedule(asset)
        role(schedule)
        show { CompletionFlowHost(graph.completionFlow) }
        var outcome: CompletionOutcome? = null
        completion = flowScope.launch { outcome = graph.completionFlow.complete(schedule) }

        awaitText(R.string.maintenance_when_was_this_done)
        val line = text(R.string.season_start_completion_marks_asset_ready, ASSET_NAME)
        rule.awaitText(line)
        rule.onNode(hasText(line) and hasAnyAncestor(isDialog())).assertExists()

        rule.runOnIdle { graph.completionFlow.cancel() }
        runBlocking { withTimeout(TIMEOUT_MS) { completion?.join() } }
        assertEquals(CompletionOutcome.Cancelled, outcome)
        assertTrue("Cancel wrote nothing", runBlocking { graph.events.forAsset(asset) }.none { it.scheduleId == schedule })
    }

    // --- The setup sheet (#113/#114, C36) ----------------------------------------------------------------------------

    /**
     * Pins: Check helper (P113-25) on a dropdown whose options are not exactly the three draws P113-15 in the sheet,
     * with no Publish (P113-6); a helper that holds them draws the confirm naming the asset and the helper (P113-5)
     * with Publish enabled. Nothing is written before Publish.
     */
    @Test fun setupSheetRefusesAStatusHelperWhoseOptionsDifferThenConfirmsAGoodOne() {
        val asset = heater(mode = null)
        validation = { helper -> validated(RemoteEntity(helper, STATUS_UP, listOf(STATUS_UP, STATUS_DOWN), null, null)) }
        sheet(asset, PublishKind.EQUIPMENT_STATUS, STATUS_ENTITY)
        awaitText(R.string.ha_publish_check_helper)

        clickText(R.string.ha_publish_check_helper)

        rule.awaitText(text(R.string.ha_publish_options_mismatch, STATUS_ENTITY))
        none(R.string.ha_publish_confirm_button)

        validation = { helper ->
            validated(RemoteEntity(helper, STATUS_UP, listOf(STATUS_UP, STATUS_DEGRADED, STATUS_DOWN), null, null))
        }
        clickText(R.string.ha_publish_check_helper)

        rule.awaitText(text(R.string.ha_publish_status_confirm, ASSET_NAME, STATUS_ENTITY))
        rule.onNode(hasText(text(R.string.ha_publish_confirm_button)) and hasClickAction()).assertIsEnabled()
        rule.onAllNodesWithText(text(R.string.ha_publish_options_mismatch, STATUS_ENTITY)).assertCountEquals(0)
        assertEquals("two fresh reads, one per Check helper", 2, checks.get())
        assertTrue("nothing is written before Publish", runBlocking { graph.publishBindings.forAsset(asset) }.isEmpty())
    }

    /** Pins: a date helper that also takes a time draws P114-32 in the sheet, with no Publish. */
    @Test fun setupSheetRefusesADateHelperWithATime() {
        val asset = heater(MANUAL_IN)
        validation = { helper -> validated(RemoteEntity(helper, INVALID_SERVICE_DATE, null, true, true)) }
        sheet(asset, PublishKind.SEASON_SERVICE_DATE, DATE_ENTITY)
        awaitText(R.string.ha_publish_check_helper)

        clickText(R.string.ha_publish_check_helper)

        rule.awaitText(text(R.string.ha_publish_date_not_date_only, DATE_ENTITY))
        none(R.string.ha_publish_confirm_button)
        assertTrue(runBlocking { graph.publishBindings.forAsset(asset) }.isEmpty())
    }

    /** Pins: Check helper with no helper given draws P113-29 under the field and reads nothing. */
    @Test fun setupSheetAsksForAHelperBeforeAnyRead() {
        val asset = heater(mode = null)
        sheet(asset, PublishKind.EQUIPMENT_STATUS, initial = "")
        awaitText(R.string.ha_publish_check_helper)

        clickText(R.string.ha_publish_check_helper)

        awaitText(R.string.ha_publish_choose_helper_first)
        assertEquals("no read without a helper", 0, checks.get())
    }

    // --- The Home Assistant screen (#113/#114, C36) ---------------------------------------------------------------------

    /**
     * Pins: while an asset publishes, the screen draws the write disclosure (P113-40); Disconnect's confirm draws the
     * Forget warning for the one date helper that may still hold a date (P113-41, its plural's form for one) above
     * P16-9; Cancel closes it and the connection stays.
     */
    @Test fun homeAssistantScreenDisclosesWritesAndForgetWarnsOfDateHelpers() {
        dateBound()
        show { HomeAssistantScreen(graph = graph, onBack = {}) }

        awaitText(R.string.ha_publish_write_disclosure)
        rule.onNodeWithText(text(R.string.ha_publish_write_disclosure)).performScrollTo()
        val disconnect = text(R.string.ha_disconnect)
        rule.onNode(hasText(disconnect) and hasClickAction()).performScrollTo().performClick()

        val warning = target.resources.getQuantityString(R.plurals.ha_publish_forget_date_helpers, 1, 1, INVALID_SERVICE_DATE)
        val body = text(R.string.ha_disconnect_body)
        rule.awaitText(warning)
        rule.onNode(hasText(warning) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        rule.onNode(hasText(body) and hasAnyAncestor(isDialog()), useUnmergedTree = true).assertExists()
        assertTrue("P113-41 above P16-9", top(warning) < top(body))

        rule.onNode(hasText(text(R.string.common_cancel)) and hasAnyAncestor(isDialog())).performClick()
        rule.waitUntil(TIMEOUT_MS) { rule.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty() }
        assertEquals(CONNECTION_ID, runBlocking { graph.haConnections.get() }?.id)
    }

    // --- Fixtures ----------------------------------------------------------------------------------------------------

    private fun show(content: @Composable () -> Unit) = rule.setContent { ServiceTagTheme { content() } }

    /** One block for [kind] over its real model, the token in [secrets] (empty: the token's own gate). */
    private fun block(asset: AssetId, kind: PublishKind, secrets: SecretStore = this.secrets) = show {
        val model: PublishBlockViewModel = viewModel(key = "b7-publish:${asset.value}:${kind.name}") {
            PublishBlockViewModel(
                asset, kind, graph.publishBindings, graph.publishDeliveries, graph.publishDecommissions,
                graph.haConnections, secrets, graph.assets, graph.transferRecords, graph.installationIdentity::id,
                graph.uow, graph.publishControls, graph.publishRunner::validate,
                DateMeaning.of(graph.openingServiceReader, graph.events, graph.today) { ZoneId.systemDefault() },
            )
        }
        val state by model.state.collectAsStateWithLifecycle()
        PublishBlock(model, state) { setUps.incrementAndGet() }
    }

    /**
     * The setup sheet for [kind], its model seeded first under the key the sheet asks for (its first opening), so the
     * sheet draws this model: the fresh read is [validation], counted; no list is read; the helper typed as [initial].
     */
    private fun sheet(asset: AssetId, kind: PublishKind, initial: String) = show {
        viewModel<LinkPublishViewModel>(key = "publish-sheet:${asset.value}:${kind.name}:1") {
            LinkPublishViewModel(
                asset, kind, graph.assets, graph.seasonSyncBindings, graph.publishControls,
                validate = { _, _, helper, _ ->
                    checks.incrementAndGet()
                    validation(helper)
                },
                initial = initial,
                initialBoundary = null,
                listEntities = { null },
            )
        }
        LinkPublishSheet(graph = graph, assetId = asset.value, kind = kind, opening = 1, storedHelper = initial) {}
    }

    private fun validated(remote: RemoteEntity): Validation = Validation.Validated(
        ValidationResult(
            target = remote.entityId, remote = remote, connectionId = CONNECTION_ID, configGeneration = 1,
            bindingRevision = 0, readAt = now(),
        ),
        null,
    )

    private fun detail(asset: AssetId) = show {
        AssetDetailScreen(
            graph = graph,
            assetId = asset.value,
            onBack = {}, onEdit = {}, onSetup = {}, onWriteTag = {}, onBackup = {},
            onLogEvent = { _, _ -> }, onOpenEvent = {}, onOpenAsset = {}, onAddComponent = {},
            onAddSchedule = {}, onLogOutcome = { _, _ -> }, onOpenSettings = {},
            onOpenSchedule = {}, onOpenGroup = {},
        )
    }

    private fun scheduleDetail(schedule: ScheduleId) = show {
        ScheduleDetailScreen(
            graph = graph, scheduleId = schedule.value, onBack = {}, onEditRecurrence = {}, onLogForm = {},
        )
    }

    /** The one connection: https on any network, background checks off; its token in the double only. */
    private fun connect() = runBlocking {
        val now = now()
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

    private fun heater(mode: SeasonModeCommand?): AssetId = runBlocking {
        val id = graph.createAsset.run(name = ASSET_NAME).id
        mode?.let { graph.setSeasonMode.run(id, it) }
        id
    }

    /** A one-tap yearly schedule on [asset]. */
    private fun schedule(asset: AssetId): ScheduleId = runBlocking {
        graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = asset, targetGroupId = null, title = SCHEDULE_TITLE,
                timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = today().toString(),
            ),
        ).id
    }

    /** The Season Start role from today, with the one-tap election the confirm makes. */
    private fun role(schedule: ScheduleId) {
        runBlocking { graph.setScheduleRole.run(schedule, ScheduleRole.SEASON_START, today().toString(), quickElected = true) }
    }

    /**
     * A connection, an asset with [condition] recorded (none: nothing to publish), and its enabled, validated status
     * binding activated on [installation], with the delivery [delivery] makes, in one write.
     */
    private fun statusBound(
        condition: OperationalCondition? = OperationalCondition.OPERATIONAL,
        installation: String = here(),
        delivery: (PublishBinding) -> PublishDelivery,
    ): AssetId {
        connect()
        val asset = heater(mode = null)
        condition?.let {
            runBlocking { graph.recordCondition.run(asset, ConditionCommand(it, tzId = ZoneId.systemDefault().id)) }
        }
        bind(binding(asset, PublishKind.EQUIPMENT_STATUS, STATUS_ENTITY, installation), delivery)
        return asset
    }

    /**
     * A connection, a manual-season asset with no Season Start service (so C9 answers not ready and the desired value
     * is `1970-01-01`), and its enabled, validated date binding on [installation], `1970-01-01` desired and not read back.
     */
    private fun dateBound(installation: String = here()): AssetId {
        connect()
        val asset = heater(MANUAL_IN)
        bind(binding(asset, PublishKind.SEASON_SERVICE_DATE, DATE_ENTITY, installation)) {
            PublishDelivery(it.id, desiredValue = INVALID_SERVICE_DATE, desiredRevision = 1, desiredAt = now())
        }
        return asset
    }

    private fun binding(asset: AssetId, kind: PublishKind, entity: String, installation: String): PublishBinding {
        val now = now()
        return PublishBinding(
            id = "b7-${kind.name.lowercase()}-${asset.value}",
            assetId = asset,
            connectionId = CONNECTION_ID,
            kind = kind,
            entityId = entity,
            enabled = true,
            revision = 1,
            activatedInstallationId = installation,
            validatedAt = now,
            validatedRevision = 1,
            boundaryEntityId = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun bind(binding: PublishBinding, delivery: (PublishBinding) -> PublishDelivery) = runBlocking {
        graph.uow.write {
            graph.publishBindings.insert(binding)
            graph.publishDeliveries.upsert(delivery(binding))
        }
    }

    /** One obligation [asset] owes [entity] on a connection no longer stored, in [state]. */
    private fun owe(asset: AssetId, entity: String, state: DecommissionState, requestedAt: Long) {
        val id = runBlocking {
            graph.uow.write {
                graph.publishDecommissions.upsertMerging(
                    DecommissionTombstone(
                        id = "b7-tombstone-$entity",
                        connectionId = GONE_CONNECTION_ID,
                        entityId = entity,
                        assetId = asset,
                        installationId = here(),
                        state = state,
                        requestedAt = requestedAt,
                        errorKind = if (state == DecommissionState.UNDELIVERABLE) PublishErrorKind.NEEDS_TOKEN else null,
                        errorAt = if (state == DecommissionState.UNDELIVERABLE) requestedAt else null,
                        revision = 1,
                    ),
                )
            }
        }
        tombstoneIds += id
    }

    private fun here(): String = graph.installationIdentity.id()

    private fun today(): LocalDate = graph.today.localDate()

    private fun now(): Long = System.currentTimeMillis()

    private fun millisOf(at: LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun text(@StringRes id: Int, vararg args: Any): String = target.getString(id, *args)

    private fun awaitText(@StringRes id: Int) = rule.awaitText(text(id))

    private fun exists(@StringRes id: Int) {
        rule.onAllNodesWithText(text(id)).fetchSemanticsNodes().let { assertTrue("drawn: ${text(id)}", it.isNotEmpty()) }
    }

    private fun none(@StringRes id: Int) = rule.onAllNodesWithText(text(id)).assertCountEquals(0)

    /** The one clickable node carrying [id]'s text. */
    private fun clickText(@StringRes id: Int) {
        rule.onNode(hasText(text(id)) and hasClickAction()).performClick()
    }

    /** Where a text is drawn, read off its own node (the unmerged tree), so a merging parent cannot blur two lines. */
    private fun top(text: String): Float =
        rule.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    /** An in-memory token store: the blocks read it; the Keystore is never touched. */
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

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val HOUR_MS = 3_600_000L
        const val TOKEN = "fictional-token-1"
        const val CONNECTION_ID = "b7-screens-connection"
        const val GONE_CONNECTION_ID = "b7-gone-connection"
        const val ORIGIN = "https://ha.example:8123"
        const val STATUS_ENTITY = "input_select.example_heater_equipment_status"
        const val DATE_ENTITY = "input_datetime.example_heater_service_done"
        const val OTHER_DATE_ENTITY = "input_datetime.example_boiler_service_done"
        const val OTHER_INSTALLATION = "00000000-0000-0000-0000-00000000b7b7"
        const val ASSET_NAME = "Example Heater"
        const val SCHEDULE_TITLE = "Example annual service"
        val SEEN_AT: LocalDateTime = LocalDateTime.of(2026, 10, 1, 9, 30)
        val MANUAL_IN = SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.IN_SEASON)
    }
}
