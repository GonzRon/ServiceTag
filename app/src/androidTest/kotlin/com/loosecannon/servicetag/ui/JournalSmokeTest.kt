package com.loosecannon.servicetag.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.usecase.AssetCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Phase 2A device smoke test: the journal, end to end, on real hardware.
 *
 * One test, and it is the hot-tub acceptance in miniature — seed an asset from the `hot_tub`
 * template, open its water test, type two readings, watch the live state, save, and find the
 * event in the Service Record with the same number and the same state in Current Readings. Every
 * layer 2A added is on that path: the seed templates, `ApplyTemplate`, `LogEvent`, the Room v2
 * schema, `LatestReadings`, `RangeState` and both journal screens.
 *
 * Like the 1C suite it is deliberately shallow; the depth is in the JVM suites. What it proves is
 * that the pieces assemble on a phone.
 *
 * It takes the cold-start empty-rule shape of [DeepLinkSmokeTest] rather than
 * `createAndroidComposeRule<MainActivity>()` + `startActivity`: `MainActivity` is `singleTask` and
 * answers a second intent through `onNewIntent`/`setIntent`, which makes `ActivityScenario` stop
 * tracking the activity it launched (1C's lesson). So the asset's deep link *is* the launch
 * intent, which is also the path a tag tap takes. The asset therefore has to exist before the
 * activity does, which is why it is created in the test body and not in `@Before`.
 */
class JournalSmokeTest {

    @get:Rule val rule = createEmptyComposeRule()

    /** Destructive, exactly as the 1C suite: no preferences, no rows, no journal. */
    @Before fun freshInstall() = clearInstall()

    @Test fun hotTubWaterTestShowsInRecordAndReadings() {
        val id = runBlocking { app.graph.createAsset.run("Spa", templateKey = "hot_tub").id.value }
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Open the asset through the deep link so the test does not depend on any list copy.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("servicetag://asset/$id"))
            .setClass(context, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use {
            // The template's two profiles become the first two quick actions on the asset.
            rule.awaitText("Log water test")
            rule.onNodeWithText("Log water test").performClick()

            // The entry screen names itself with the profile and the asset, both shouted.
            rule.awaitText("WATER TEST · SPA")
            rule.onNodeWithTag("value-ph").performTextInput("7.9")
            rule.onNodeWithTag("value-free_chlorine").performTextInput("2.0")
            // pH 7.9 is above the template's 7.2–7.8, and the row says so while it is being typed.
            rule.awaitText("HIGH")
            rule.onNodeWithText("HIGH").assertIsDisplayed()

            // The app bar's Save is the one that commits; the button at the foot of the form reads
            // "Record entry", so this is unambiguous — `onFirst` only guards against a future twin.
            rule.onAllNodesWithText("Save").onFirst().performClick()

            // Back on the asset. `SectionHeader` renders its title uppercase, so that is what the
            // semantics tree carries.
            rule.awaitText("SERVICE RECORD")
            // Readings first, because they are the top of the screen: the stored measurement,
            // formatted to the definition's one decimal, and its badge. HIGH is on screen twice
            // now — the current-readings badge and the ledger entry's — so take the first.
            rule.onNodeWithText("7.9").performScrollTo().assertIsDisplayed()
            rule.onAllNodesWithText("HIGH").onFirst().performScrollTo().assertIsDisplayed()
            // The ledger entry takes its title from the event, which took it from the profile. The
            // asset screen is one `verticalScroll` Column and the Service Record sits below the
            // fold on a phone, so scroll to it rather than asserting a node that merely exists.
            rule.onNodeWithText("Water test").performScrollTo().assertIsDisplayed()
        }
    }

    /**
     * #82's Workflow A through the real back stack: Change condition with DOWN asks P82-1 and writes
     * nothing; "Log incident details" opens the prefilled Incident entry with P82-5; closing the entry
     * brings the question back; the entry's Save records the Incident and the held row, linked, once,
     * and the sheet closes on the way back without another write.
     */
    @Test fun logIncidentDetailsRecordsTheIncidentAndTheConditionTogether() {
        val graph = app.graph
        val pump = runBlocking { graph.createAsset.run(AssetCommand(name = "Pump", category = "Water")).id }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("servicetag://asset/${pump.value}"))
            .setClass(context, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use {
            rule.awaitText("Change condition")
            rule.onNodeWithText("Change condition").performScrollTo().performClick()
            rule.awaitText("Save condition")
            rule.onNodeWithText("Down").performClick()
            rule.onNode(hasSetTextAction() and hasText("What is wrong? (optional)")).performTextInput("Will not start")
            rule.onNodeWithText("Save condition").performScrollTo().performClick()
            rule.awaitText("Log incident details?")
            rule.onNodeWithText("Pump is DOWN. Record what went wrong in the service record?").assertIsDisplayed()
            assertEquals(0, runBlocking { graph.conditions.all().size })

            // The entry opens prefilled, with P82-5 under its eyebrow; closing it writes nothing.
            rule.onNodeWithText("Log incident details").performClick()
            rule.awaitText("Saving also records Pump as DOWN.")
            rule.onNode(hasSetTextAction() and hasText("Entry")).assert(hasText("Will not start"))
            rule.onNodeWithContentDescription("Close").performClick()
            rule.awaitText("Log incident details?")
            assertEquals(0, runBlocking { graph.events.all().size + graph.conditions.all().size })

            // Asked again, answered again: the entry's Save records both, and the sheet closes.
            rule.onNodeWithText("Log incident details").performClick()
            rule.awaitText("Saving also records Pump as DOWN.")
            rule.onAllNodesWithText("Save").onFirst().performClick()
            rule.awaitText("SERVICE RECORD")
            rule.waitUntil(TIMEOUT_MS) {
                rule.onAllNodesWithText("Save condition").fetchSemanticsNodes().isEmpty() &&
                    rule.onAllNodesWithText("Log incident details?").fetchSemanticsNodes().isEmpty()
            }
            val incident = runBlocking { graph.events.all().single() }
            assertEquals(EventKind.INCIDENT, incident.kind)
            assertEquals("Will not start", incident.title)
            val row = runBlocking { graph.conditions.all().single() }
            assertEquals(OperationalCondition.DOWN, row.condition)
            assertEquals(incident.id, row.eventId)
            rule.onAllNodesWithText("DOWN").onFirst().assertIsDisplayed()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
