package com.loosecannon.servicetag.ui.scan

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.usecase.OverwriteSubject
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #70 — the overwrite sheet draws what the tag already identifies: the subject's line in the
 * due-soon surface and, when there is one, the quiet mono identifier under it (C5, AC 1 and 6).
 * The eyebrow, the explanation paragraph and both buttons are the ones the sheet always had.
 */
@RunWith(AndroidJUnit4::class)
class OverwriteSheetSubjectTest {

    @get:Rule val rule = createComposeRule()

    private fun show(subject: OverwriteSubject) {
        rule.setContent {
            ServiceTagTheme {
                OverwriteSheet(subject = subject, target = "Pump 3", onOverwrite = {}, onKeepIt = {})
            }
        }
        rule.waitForIdle()
    }

    @Test fun theSheetShowsTheLineAndTheQuietIdentifier() {
        show(OverwriteSubject("This tag currently identifies Pump 3.", "a0c19962 · v1 · Pump house"))

        rule.onNodeWithText("OVERWRITE THIS TAG?").assertIsDisplayed()
        rule.onNodeWithText("This tag currently identifies Pump 3.").assertIsDisplayed()
        rule.onNodeWithText("a0c19962 · v1 · Pump house").assertIsDisplayed()
        rule.onNodeWithText(
            "Replacing it will make the tag identify Pump 3. The old content is lost. " +
                "After you confirm, hold the same tag to the phone again to write.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Overwrite").assertIsDisplayed()
        rule.onNodeWithText("Keep it").assertIsDisplayed()
    }

    @Test fun aSubjectWithoutAnIdentifierShowsOnlyTheLine() {
        show(OverwriteSubject("The tag already holds foreign NDEF content (tnf=1 type=U).", null))

        rule.onNodeWithText("The tag already holds foreign NDEF content (tnf=1 type=U).").assertIsDisplayed()
        rule.onAllNodesWithText(" · v1", substring = true).assertCountEquals(0)
        // No empty mono line stands in for the missing identifier either.
        rule.onAllNodesWithText("").assertCountEquals(0)
        rule.onNodeWithText("Overwrite").assertIsDisplayed()
        rule.onNodeWithText("Keep it").assertIsDisplayed()
    }
}
