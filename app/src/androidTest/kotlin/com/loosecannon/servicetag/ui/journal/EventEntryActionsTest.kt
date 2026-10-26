package com.loosecannon.servicetag.ui.journal

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.settleKeyboard
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #118 — the entry form has one commit control: the filled button at its foot, "Record entry" on a
 * new entry and "Save entry" on an edit. The app bar carries ✕ and the eyebrow, and no "Save" (A1).
 * The bottom button is reached by scrolling the form after typing in its last field, and tapping it
 * stores one event and leaves (A2).
 *
 * `EventEntryScreen` composed over `app.graph` on a profile-less NOTE entry, whose Entry field
 * opens holding "Note", so the form is loaded once that text is drawn. Whether the keyboard covers
 * the button is a different window's question, which Compose cannot see; it is observed elsewhere.
 *
 * Emulator only — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class EventEntryActionsTest {

    @get:Rule val rule = createComposeRule()

    @Before fun freshInstall() = clearInstall()

    private val left = mutableListOf<String>()

    @Test fun aNewEntryHasOnlyRecordEntryToCommit() {
        val asset = asset()
        entry(asset, eventId = null)
        awaitEntryField(holding = PRESET_TITLE)

        oneCommitControl(shown = RECORD_ENTRY, absent = SAVE_ENTRY)
    }

    @Test fun anEditHasOnlySaveEntryToCommit() {
        val asset = asset()
        val today = app.graph.today.localDate().toString()
        val event = runBlocking {
            app.graph.logEvent.run(
                EventCommand(
                    assetId = asset, profileId = null, kind = EventKind.NOTE, title = "Filter rinsed",
                    occurredOn = today, occurredTime = null, tzId = "UTC", notes = "",
                    values = emptyMap(), consumables = emptyList(),
                ),
            )
        }
        entry(asset, eventId = event.id.value)
        awaitEntryField(holding = "Filter rinsed")

        oneCommitControl(shown = SAVE_ENTRY, absent = RECORD_ENTRY)
    }

    @Test fun theBottomButtonCommitsAfterTheLastField() {
        val asset = asset()
        entry(asset, eventId = null)
        awaitEntryField(holding = PRESET_TITLE)

        // Notes is the form's last field; the button sits under it, inside the same scrolling column.
        rule.onNode(hasSetTextAction() and hasText(NOTES)).performScrollTo().performTextInput("Rinsed the prefilter")
        rule.settleKeyboard()
        rule.onNodeWithText(RECORD_ENTRY).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()

        rule.waitUntil(TIMEOUT_MS) { rule.runOnIdle { left.isNotEmpty() } }
        assertEquals(listOf("done"), rule.runOnIdle { left.toList() })
        val stored = runBlocking { app.graph.events.forAsset(asset) }
        assertEquals(1, stored.size)
        assertEquals("Rinsed the prefilter", stored.single().notes)
    }

    private fun asset(): AssetId = runBlocking { app.graph.createAsset.run(AssetCommand(name = "Softener", category = "Water")).id }

    private fun entry(asset: AssetId, eventId: String?) {
        rule.setContent {
            ServiceTagTheme {
                EventEntryScreen(
                    graph = app.graph,
                    assetId = asset.value,
                    profileId = null,
                    eventId = eventId,
                    onDone = { left += "done" },
                    onBack = { left += "back" },
                    kind = EventKind.NOTE.name,
                )
            }
        }
    }

    private fun awaitEntryField(holding: String) {
        rule.waitUntil(TIMEOUT_MS) {
            rule.onAllNodes(hasSetTextAction() and hasText(ENTRY) and hasText(holding)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** ✕ in the app bar, no node reading exactly "Save", and one button at the foot with this mode's label. */
    private fun oneCommitControl(shown: String, absent: String) {
        rule.onNodeWithContentDescription(CLOSE).assertIsDisplayed()
        rule.onAllNodesWithText(SAVE).assertCountEquals(0)
        rule.onAllNodesWithText(shown).assertCountEquals(1)
        rule.onAllNodesWithText(absent).assertCountEquals(0)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SAVE = "Save"
        const val RECORD_ENTRY = "Record entry"
        const val SAVE_ENTRY = "Save entry"
        const val CLOSE = "Close"
        const val ENTRY = "Entry"
        const val NOTES = "Notes"
        const val PRESET_TITLE = "Note"
    }
}
