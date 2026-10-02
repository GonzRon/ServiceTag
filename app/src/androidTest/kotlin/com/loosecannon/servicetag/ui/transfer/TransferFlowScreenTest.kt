package com.loosecannon.servicetag.ui.transfer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #77 (C21a, R77-22; row 37) — the sender's flow, rendered from its states with no graph: a component forced by its
 * checked parent is checked, disabled and described P77-61; the review's refusals are drawn and Create is disabled;
 * the ready screen offers Share, Save a copy and the mark question. The logic's REDs are rows 29 and 30, on the JVM;
 * this class proves only the rendered semantics no JVM test in this build can observe. Fictional names only.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class TransferFlowScreenTest {

    @get:Rule val rule = createComposeRule()

    @Test fun aForcedChildIsCheckedDisabledAndDescribed() {
        val state = TransferSelectionState(
            loading = false,
            choices = listOf(
                TransferChoice("h1", "Example Water Heater", parentName = null, depth = 0, checked = true),
                TransferChoice(
                    "a1", "Example Anode Rod", parentName = "Example Water Heater", depth = 1, checked = true,
                    forcedBy = "Example Water Heater",
                ),
                TransferChoice("g1", "Sample Garage Door Opener", parentName = null, depth = 0, checked = false),
            ),
        )
        rule.setContent { ServiceTagTheme { TransferSelectionContent(state, onToggle = {}, onReview = {}) } }

        rule.onNodeWithText("Select what is leaving this ServiceTag").assertIsDisplayed()
        rule.onNodeWithText("Child assets go with the asset they belong to.").assertIsDisplayed()
        rule.onNodeWithText("Example Anode Rod")
            .assertIsOn()
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Included with Example Water Heater"))
        rule.onNodeWithText("Part of Example Water Heater").assertIsDisplayed()
        rule.onNodeWithText("Sample Garage Door Opener").assertIsEnabled()
        rule.onNodeWithText("Review").performScrollTo().assertIsEnabled()
    }

    @Test fun refusalsBlockCreate() {
        val review = TransferReview(
            counts = listOf("1 asset", "1 NFC tag"),
            refusals = listOf("Sample Garage Door Opener is lent out. Mark it returned first."),
        )
        rule.setContent {
            ServiceTagTheme {
                TransferReviewContent(review, creating = false, errors = emptyList(), onNote = {}, onCreate = {})
            }
        }

        rule.onNodeWithText("TRANSFER PACK").assertIsDisplayed()
        rule.onNodeWithText("1 asset").assertIsDisplayed()
        rule.onNodeWithText(TransferStrings.MAY_CONTAIN).assertIsDisplayed()
        rule.onNodeWithText("Note for the new owner (optional)").assertIsDisplayed()
        rule.onNodeWithText("Sample Garage Door Opener is lent out. Mark it returned first.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Create Transfer Pack").performScrollTo().assertIsNotEnabled()
    }

    @Test fun theReadyScreenOffersShareSaveAndTheQuestion() {
        var marks = 0
        val state = TransferPackState(
            phase = PackPhase.READY, fileName = "servicetag-transfer-2026-09-28-0f1e2d3c.zip", size = "12 KB",
            verified = true,
        )
        rule.setContent {
            ServiceTagTheme {
                TransferReadyContent(state, onShare = {}, onSaveCopy = {}, onMark = { marks += 1 }, onNotNow = {})
            }
        }

        rule.onNodeWithText("Transfer Pack ready").assertIsDisplayed()
        rule.onNodeWithText("Share").assertIsEnabled()
        rule.onNodeWithText("Save a copy").assertIsEnabled()
        rule.onNodeWithText("Size: 12 KB").assertIsDisplayed()
        rule.onNodeWithText(TransferStrings.BEARER).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Mark these assets transferred out on this phone?").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Not now").performScrollTo().assertIsEnabled()
        rule.onNodeWithText(TransferStrings.MARK_CONSEQUENCE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Mark transferred").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, marks)
    }
}
