package com.loosecannon.servicetag.ui.transfer.`import`

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
 * #77 (B3; C21a, row 28) — the Transfer Pack import screen, rendered from a [TransferImportState] with no graph: the
 * preview's ratified lines, Import offered or not, and a refusal's one sentence with the share host's Close. The
 * state machine's REDs are row 26's, on the JVM; this class proves only what is drawn. Fictional names only.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class TransferImportScreenTest {

    @get:Rule val rule = createComposeRule()

    private var imports = 0
    private var closes = 0

    private fun show(state: TransferImportState, door: TransferDoor = TransferDoor.BACKUP) {
        rule.setContent {
            ServiceTagTheme {
                TransferImportContent(
                    state = state,
                    door = door,
                    onImport = { imports += 1 },
                    onCancel = {},
                    onClose = { closes += 1 },
                )
            }
        }
    }

    private val preview = TransferImportState(
        phase = TransferImportPhase.PREVIEW,
        note = TransferImportStrings.note("Example handover note"),
        created = TransferImportStrings.created("27 Sep 2026"),
        contains = TransferImportStrings.countLines(assets = 2, tags = 1, records = 3, schedules = 0, documents = 1, cases = 0),
        comingBack = listOf(TransferImportStrings.comingBack("Example Water Heater")),
        duplicates = listOf(TransferImportStrings.duplicate("Example Anode Rod", "Sample Anode")),
        importEnabled = true,
    )

    @Test
    fun thePreviewDrawsItsLines() {
        show(preview)

        listOf(
            "Note: Example handover note",
            "Created 27 Sep 2026",
            "Contains",
            "2 assets",
            "1 NFC tag",
            "3 records",
            "1 document or photo",
            "Coming back: Example Water Heater",
            "Example Anode Rod may already be here as Sample Anode.",
        ).forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        rule.onNodeWithText("Import").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, imports)
    }

    @Test
    fun importIsDisabledOnAConflict() {
        show(
            preview.copy(
                comingBack = emptyList(),
                duplicates = emptyList(),
                outcome = listOf(
                    TransferImportStrings.CONFLICTS,
                    TransferImportStrings.tagUsedHere("Sample Pump"),
                ),
                importEnabled = false,
            ),
        )

        rule.onNodeWithText("This Transfer Pack conflicts with records on this phone, so nothing was imported.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("An NFC tag in this pack is already used for Sample Pump here.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Import").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aRefusalDrawsItsSentence() {
        show(
            TransferImportState(phase = TransferImportPhase.REFUSED, refusal = TransferImportStrings.NOT_A_PACK),
            door = TransferDoor.SHARE,
        )

        rule.onNodeWithText("This file is not a Transfer Pack.").assertIsDisplayed()
        rule.onNodeWithText("Import").assertDoesNotExist()
        rule.onNodeWithText("Close").performClick()
        assertEquals(1, closes)
    }
}
