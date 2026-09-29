package com.loosecannon.servicetag.ui.replace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetSettingsCommand
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.ReplaceDraft
import com.loosecannon.servicetag.core.usecase.ScheduleCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.asset.AssetDetailScreen
import com.loosecannon.servicetag.ui.asset.CANCEL_BUTTON
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.scan.identityLine
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import com.loosecannon.servicetag.ui.transfer.TransferStrings
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #86 (C19, R86-21; row 18) — Replace asset on a real Compose tree over the app's own graph. **Its boundary is rendered
 * semantics**, which no JVM test in this build observes (no Robolectric, no JVM Compose; the #77 C21a precedent): every
 * offered item drawn unticked and every tag on Leave; a dependency line drawn as text under the schedule it blocks;
 * `Review`'s enabled state following the plan; the review's drawn lines; and the two detail lines' click actions. The
 * logic's REDs are rows 15–16, on the JVM; **this class runs no device mutation** and never confirms a replace through
 * the screen — the one Replace it needs (the detail lines) is seeded through the core use case.
 *
 * Emulator only (`emulator-5554`) — the suite wipes app data. `clearInstall()` empties `asset_succession` through the
 * assets' CASCADE. Every name is fictional.
 */
@RunWith(AndroidJUnit4::class)
class ReplaceAssetFlowTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private val tag = TagBinding(
        id = TagId("7a1f3c2e-0000-4000-8000-000000000001"),
        payloadFormat = PayloadFormat.V1,
        payloadKey = "TEST-0001",
        status = TagStatus.ACTIVE,
        label = "Pump room door",
        createdAt = 1L,
        updatedAt = 1L,
    )

    /**
     * The pool pump: a calendar season, notes, a weekly schedule and a PRE_SERVICE one (which needs the season item), a
     * component, and one placed tag. Returns its id.
     */
    private fun pump(): String = runBlocking {
        val graph = app.graph
        val id = graph.saveAssetSettings.run(
            null,
            AssetSettingsCommand(
                asset = AssetCommand(name = PUMP, category = "Pump", location = "Back yard", notes = "Check the basket"),
                seasonMode = SeasonModeCommand(SeasonMode.CALENDAR, "05-01", "09-30"),
                maintenanceBreak = BreakCommand(null, null),
                healthPolicy = HealthPolicyCommand(HealthAggregation.WORST),
            ),
        ).id
        graph.createAsset.run(AssetCommand(name = MOTOR, parentAssetId = id))
        val today = LocalDate.now().toString()
        graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = id, targetGroupId = null, title = WEEKLY,
                timeInterval = 1, timeUnit = RecurrenceUnit.WEEK, anchorOn = today,
            ),
        )
        graph.saveSchedule.run(
            null,
            ScheduleCommand(
                targetAssetId = id, targetGroupId = null, title = PRE_SEASON,
                timeInterval = 1, timeUnit = RecurrenceUnit.YEAR, anchorOn = today,
                servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = -7,
            ),
        )
        graph.tags.upsert(tag.copy(target = TagTarget.AssetTarget(id)))
        id.value
    }

    private fun replace(id: String) {
        rule.setContent {
            ServiceTagTheme { ReplaceAssetScreen(graph = app.graph, assetId = id, onBack = {}, onDone = {}) }
        }
        rule.awaitText(ReplaceStrings.OLD_ASSET.uppercase())
    }

    private fun review() = rule.onNode(hasText(TransferStrings.REVIEW) and hasClickAction())

    /** Waits for the plan of the form on screen: `Review`'s enabled state is drawn only once it has answered. */
    private fun awaitReview(enabled: Boolean) = rule.waitUntil(TIMEOUT_MS) {
        val matcher = hasText(TransferStrings.REVIEW) and hasClickAction()
        rule.onAllNodes(if (enabled) matcher and isEnabled() else matcher and !isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun gone(text: String) = rule.onAllNodesWithText(text).assertCountEquals(0)

    @Test fun theFormDrawsItsUntickedItemsAndADependencyLine() {
        val id = pump()
        replace(id)

        rule.onNodeWithText(ReplaceStrings.willBeRetired(PUMP)).assertIsDisplayed()
        rule.onNodeWithText(ReplaceStrings.SEPARATE_ASSET).performScrollTo().assertIsDisplayed()
        // R86-9: every offered item unticked; R86-14: the tag on Leave.
        listOf(ReplaceStrings.SEASON_AND_BREAK, ReplaceStrings.DESCRIPTION_AND_NOTES, WEEKLY, PRE_SEASON).forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsOff()
        }
        rule.onNodeWithText(ReplaceStrings.LEAVE_WITH_OLD).performScrollTo().assertIsSelected()
        rule.onNodeWithText(ReplaceStrings.MOVE_TO_NEW).assertIsNotSelected()
        rule.onNodeWithText(tag.identityLine()).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(ReplaceStrings.MOVED_TAG_NOT_REWRITTEN).performScrollTo().assertIsDisplayed()
        // R86-7: the component is named, never moved.
        rule.onNodeWithText(ReplaceStrings.childrenStay(listOf(MOTOR), PUMP)!!).performScrollTo().assertIsDisplayed()
        gone(ReplaceStrings.NEEDS_SEASON)

        // P86-14 is drawn, as text, under the ticked PRE_SERVICE schedule.
        rule.onNodeWithText(PRE_SEASON).performScrollTo().performClick()
        rule.awaitText(ReplaceStrings.NEEDS_SEASON)
        rule.onNodeWithText(ReplaceStrings.NEEDS_SEASON).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(ReplaceStrings.SEASON_AND_BREAK).performScrollTo().assertIsOff()
    }

    @Test fun reviewIsDisabledOnAProblem() {
        val id = pump()
        replace(id)
        awaitReview(enabled = true)

        rule.onNodeWithText(PRE_SEASON).performScrollTo().performClick()
        rule.awaitText(ReplaceStrings.NEEDS_SEASON)
        review().performScrollTo().assertIsNotEnabled()

        // Ticking the item it needs clears the line, and Review is enabled again.
        rule.onNodeWithText(ReplaceStrings.SEASON_AND_BREAK).performScrollTo().performClick()
        // The tick is drawn first, so the wait below can only be answered by the plan of this form.
        rule.onNodeWithText(ReplaceStrings.SEASON_AND_BREAK).assertIsOn()
        awaitReview(enabled = true)
        gone(ReplaceStrings.NEEDS_SEASON)
        review().performScrollTo().assertIsEnabled()
    }

    @Test fun theReviewDrawsItsLines() {
        val id = pump()
        replace(id)
        rule.onNodeWithText(ReplaceStrings.DESCRIPTION_AND_NOTES).performScrollTo().performClick()
        rule.onNodeWithText(WEEKLY).performScrollTo().performClick()
        rule.onNodeWithText(ReplaceStrings.MOVE_TO_NEW).performScrollTo().performClick()
        // The last edit is drawn first, so the wait below can only be answered by the plan of this form.
        rule.onNodeWithText(ReplaceStrings.MOVE_TO_NEW).assertIsSelected()
        awaitReview(enabled = true)
        review().performScrollTo().performClick()

        val today = LocalDate.now().toString()
        rule.awaitText(ReplaceStrings.retireOn(PUMP, today))
        rule.onNodeWithText(ReplaceStrings.create(PUMP)).assertIsDisplayed()
        rule.onNodeWithText(ReplaceStrings.CARRY_FORWARD).assertIsDisplayed()
        rule.onNodeWithText(ReplaceStrings.DESCRIPTION_AND_NOTES).assertIsDisplayed()
        rule.onNodeWithText(WEEKLY).assertIsDisplayed()
        gone(ReplaceStrings.NOTHING_CARRIED)
        gone(ReplaceStrings.SEASON_AND_BREAK)
        rule.onNodeWithText(ReplaceStrings.MOVE_TO_NEW).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(tag.identityLine()).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText(ReplaceStrings.REPLACE_ASSET) and hasClickAction()).performScrollTo().assertIsEnabled()

        // Cancel returns to the form, and nothing was written.
        rule.onNodeWithText(CANCEL_BUTTON).performScrollTo().performClick()
        rule.awaitText(ReplaceStrings.OLD_ASSET.uppercase())
        runBlocking {
            assertEquals(emptyList<Any>(), app.graph.assetSuccessions.all())
            assertNull(app.graph.assets.get(AssetId(id))?.retiredOn)
        }
    }

    @Test fun bothDetailLinesDrawAndOpenTheOtherAsset() {
        val (old, new) = runBlocking {
            val graph = app.graph
            val old = graph.createAsset.run(AssetCommand(name = HEATER)).id
            val draft = ReplaceDraft(predecessorId = old, retiredOn = REPLACED_ON, successor = AssetCommand(name = TANKLESS))
            val result = graph.replaceAsset.run(draft, graph.replaceAsset.plan(draft))
            old.value to result.successor.id.value
        }
        val opened = mutableListOf<String>()
        var shown by mutableStateOf(old)
        rule.setContent {
            ServiceTagTheme {
                AssetDetailScreen(
                    graph = app.graph,
                    assetId = shown,
                    onBack = {}, onEdit = {}, onSetup = {}, onWriteTag = {}, onBackup = {},
                    onLogEvent = { _, _ -> }, onOpenEvent = {},
                    onOpenAsset = { opened += it; shown = it },
                    onAddComponent = {}, onAddSchedule = {}, onLogOutcome = { _, _ -> }, onOpenSettings = {},
                    onOpenSchedule = {}, onOpenGroup = {},
                )
            }
        }

        val replacedBy = ReplaceStrings.replacedBy(TANKLESS, REPLACED_ON)
        rule.awaitText(replacedBy)
        rule.onNodeWithText(replacedBy).performScrollTo().assertIsDisplayed().performClick()
        val replaces = ReplaceStrings.replaces(HEATER)
        rule.awaitText(replaces)
        rule.onNodeWithText(replaces).performScrollTo().assertIsDisplayed().performClick()
        rule.awaitText(replacedBy)
        assertEquals(listOf(new, old), opened)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val PUMP = "Sample Pool Pump"
        const val MOTOR = "Example Pump Motor"
        const val WEEKLY = "Clean the basket"
        const val PRE_SEASON = "Open for the season"
        const val HEATER = "Example Water Heater"
        const val TANKLESS = "Example Tankless Water Heater"
        const val REPLACED_ON = "2025-06-01"
    }
}
