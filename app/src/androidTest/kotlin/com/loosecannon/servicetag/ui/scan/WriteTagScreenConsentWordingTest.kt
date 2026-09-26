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
 * Pins the two write-screen sentences of the overwrite-consent flow, ratified by the owner
 * verbatim on 2026-09-17. They encode the consent invariant: the NFC handle that raised the
 * question can go stale while the sheet is up, so confirming discards it rather than writing
 * through it — the user must lift the tag off and hold it to the phone again. A future edit must
 * not soften that back into "keep holding the tag while you answer".
 *
 * `WriteStatus`'s `is WriteState.Confirm` arm draws the first sentence in an `NfcSheet`; the
 * second lives in a sibling composable, `OverwriteSheet`, which `WriteTagScreen` shows alongside
 * it whenever the state is `Confirm` (see `WriteTagScreen`'s `asking != null` branch) — no
 * interaction is needed to raise it, so both are composed together here, exactly as the screen
 * wires them, to pin both sentences in one tree. Both composables were `private`; this test
 * needed them visible to the androidTest source set, so both were changed to `internal` (no
 * logic change) — see the WS-1 report for the full accounting.
 */
@RunWith(AndroidJUnit4::class)
class WriteTagScreenConsentWordingTest {

    @get:Rule val rule = createComposeRule()

    /** Exactly what `WriteTagScreen` draws for a `Confirm` state, minus the NFC-session chrome. */
    private fun setConfirmContent() {
        val state = WriteState.Confirm(OverwriteSubject("This ServiceTag tag is not in this phone's records.", "a0c19962 · v1"))
        rule.setContent {
            ServiceTagTheme {
                WriteStatus(state, "Pump 3", onDone = {})
                OverwriteSheet(
                    subject = state.subject,
                    target = "Pump 3",
                    onOverwrite = {},
                    onKeepIt = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test fun theConfirmStateTellsTheUserToLiftAndRetap() {
        setConfirmContent()

        rule.onNodeWithText(
            "Answer here, then hold the same tag to the phone again.",
            substring = true,
        ).assertIsDisplayed()

        rule.onNodeWithText(
            "After you confirm, hold the same tag to the phone again to write.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test fun theConfirmStateNeverAsksTheUserToKeepHoldingTheTag() {
        setConfirmContent()

        rule.onAllNodesWithText("Hold the tag to the phone while", substring = true)
            .assertCountEquals(0)
    }
}
