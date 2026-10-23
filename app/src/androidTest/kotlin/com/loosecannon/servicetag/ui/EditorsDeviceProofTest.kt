package com.loosecannon.servicetag.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ProfileFieldInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Phase 2B-1's device proof: spec §12's instrumented rows, driven through the real editors on real
 * hardware. Same shape as `JournalDeviceProofTest` — one test per scenario, each cold-starting the
 * asset it needs through its `servicetag://asset/<id>` deep link, each asserting what the screen says.
 *
 * What the suite deliberately does *not* claim:
 *
 * - **A reading and its number are tied together by presence and count, not adjacency.**
 *   `InstrumentRow` lays label, value and badge out in a plain `Row` with no semantics of its own,
 *   so no node owns all three. Which definition earns which number is pinned on the JVM by
 *   `DerivedTest` and `LatestReadingsTest`; here the test pins the formatted values and the exact
 *   count of em dashes on the screen, which is what makes "the Rejection row went blank" provable.
 * - **The asset, and the six-field profile of the focus walk, are created in-process.** Every row
 *   under test begins on a screen; the new-asset form is already covered by `AppSmokeTest`, and a
 *   six-definition setup typed through the editor would be six copies of the row that already
 *   proves the editor.
 * - **The backup round trip is called in-process**, as in 2A: the file half of the backup screen is
 *   a SAF document picker, which belongs to the system and not to the app. Decoding a *format 2*
 *   file is JVM-proven by `BackupCodecTest.formatTwoFileStillDecodes`, because the codec's
 *   `encode(…, formatVersion)` overload is `internal` to `:core` and cannot be reached from here.
 */
class EditorsDeviceProofTest {

    @get:Rule val rule = createEmptyComposeRule()

    /** Destructive, exactly as the 1C and 2A suites: no preferences, no rows, no journal. */
    @Before fun freshInstall() = clearInstall()

    // ---------------------------------------------------------------- (a) the editors, end to end

    /**
     * Spec §12 exit criterion 1: a reading and an action that exist only because someone made them
     * drive a logged event and a current reading, with no code change and no template behind them.
     */
    @Test fun aCustomReadingAndActionDriveALoggedEntry() {
        val id = newAsset("Air compressor", templateKey = null)
        openAsset(id).use {
            rule.awaitText("Readings & actions")
            rule.onNodeWithText("Readings & actions").performScrollTo().performClick()

            // The new reading: label, unit, and whole numbers rather than the form's default of one
            // decimal, so the value reads the way a pressure gauge does.
            rule.awaitText("Add reading")
            rule.onNodeWithText("Add reading").performScrollTo().performClick()
            rule.awaitText("New reading")
            rule.field("Label").performTextInput("Pressure")
            rule.field("Unit").performTextInput("psi")
            rule.field("Decimals").performTextReplacement("0")
            rule.onNodeWithText("Save").performClick()

            // Back on the setup screen, the reading is there with its generated key's unit line.
            rule.awaitText("Add action")
            rule.onNodeWithText("Pressure").performScrollTo().assertIsDisplayed()

            // The new action, asking for that reading and requiring it.
            rule.onNodeWithText("Add action").performScrollTo().performClick()
            rule.awaitText("NEW ACTION · AIR COMPRESSOR")
            rule.field("Name").performTextInput("Pressure check")
            rule.onNodeWithText("Add field").performScrollTo().performClick()
            rule.awaitText("Add a field")
            rule.onNodeWithText("Pressure").performClick()
            // The field's meta line reads "psi · Optional", so the word is matched inside it.
            rule.awaitContaining("Optional")
            rule.onNode(isToggleable()).performClick()
            rule.awaitContaining("Required")
            rule.onNodeWithText("Save").performClick()

            // Back on the setup screen, then back to the asset: the action is a quick action now.
            rule.awaitText("Pressure check")
            rule.onNodeWithContentDescription("Back").performClick()
            rule.awaitText("Log pressure check")

            rule.onNodeWithText("Log pressure check").performScrollTo().performClick()
            rule.awaitTag("value-pressure")
            rule.onNodeWithTag("value-pressure").performTextInput("42")
            rule.saveEntry()

            rule.awaitText("SERVICE RECORD")
            rule.onNodeWithText("Pressure").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("42").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Pressure check").performScrollTo().assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- (b) same-event derivation

    /**
     * Spec §12 exit criterion 2: RO rejection appears from one TDS test, and a later entry that
     * cannot produce it does not change it — the same-event rule of spec §5, read off the screen.
     */
    @Test fun rejectionComesFromOneTestAndIsNeverCombinedAcrossEvents() {
        val id = newAsset("RO unit", templateKey = "ro_water")
        openAsset(id).use {
            rule.awaitText("Log TDS test")
            rule.logTdsTest(prefilter = "310", postMembrane = "18", output = "12")

            // (310 − 18) / 310 × 100 = 94.1935…, at the derived definition's one decimal.
            rule.awaitText("SERVICE RECORD")
            rule.onNodeWithText("Rejection").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("94.2").performScrollTo().assertIsDisplayed()

            // A second test with only the pre-filter needs the other two fields to stop being
            // required, which is the profile editor's job — so that is where it is done.
            rule.onNodeWithText("Readings & actions").performScrollTo().performClick()
            rule.awaitText("Add action")
            rule.onNodeWithText("TDS test").performScrollTo().performClick()
            rule.awaitText("EDIT ACTION · RO UNIT")
            rule.awaitContaining("Required", count = 3)
            rule.onAllNodes(isToggleable())[1].performClick()
            rule.onAllNodes(isToggleable())[2].performClick()
            rule.awaitContaining("Optional", count = 2)
            rule.onNodeWithText("Save").performClick()
            rule.awaitText("Add action")
            rule.onNodeWithContentDescription("Back").performClick()

            rule.awaitText("Log TDS test")
            rule.logTdsTest(prefilter = "300", postMembrane = null, output = null)

            rule.awaitText("SERVICE RECORD")
            // The newer entry is the current pre-filter reading…
            rule.onNodeWithText("300").performScrollTo().assertIsDisplayed()
            rule.onAllNodesWithText("310").assertCountEquals(0)
            // …and the rejection is still the older test's, not a number made of both.
            rule.onNodeWithText("94.2").performScrollTo().assertIsDisplayed()
            rule.onAllNodesWithText("TDS test").assertCountEquals(2)
        }
    }

    // ---------------------------------------------------------------- (c) archive

    /**
     * Archiving a source takes the reading off the entry form and blanks what depends on it,
     * without touching what was already recorded — spec §5's archived-source rule on the phone.
     * The quick action that offered it must still save afterwards, which is the half of the row
     * that was missing: proving the field is gone proves nothing if the action is now unsaveable.
     */
    @Test fun archivingASourceEmptiesTheDerivedRowAndLeavesHistoryAlone() {
        val id = newAsset("RO unit", templateKey = "ro_water")
        openAsset(id).use {
            rule.awaitText("Log TDS test")
            rule.logTdsTest(prefilter = "310", postMembrane = "18", output = "12")
            rule.awaitText("94.2")

            rule.onNodeWithText("Readings & actions").performScrollTo().performClick()
            rule.awaitText("Add reading")
            // Row 0 of the readings list is Pre-filter TDS, the derived row's source A.
            rule.onAllNodesWithContentDescription("More")[0].performClick()
            rule.onNodeWithText("Archive").performClick()
            // `StatusBadge` draws its label upper-cased, so the badge on the row reads "ARCHIVED".
            rule.awaitText("ARCHIVED")
            rule.onNodeWithContentDescription("Back").performClick()

            rule.awaitText("CURRENT READINGS")
            // Three rows left — Post-membrane, Output, Rejection — and the archived one is gone.
            rule.onAllNodesWithText("Pre-filter TDS").assertCountEquals(0)
            rule.onAllNodesWithText("310").assertCountEquals(0)
            rule.onNodeWithText("Rejection").performScrollTo().assertIsDisplayed()
            // The plate's six blank cells (spec §9) plus exactly one blank reading: the derived row.
            rule.awaitText("—", count = 7)
            rule.onAllNodesWithText("—").assertCountEquals(7)
            rule.onAllNodesWithText("94.2").assertCountEquals(0)

            // The entry form no longer asks for it; the two live rows are still there.
            rule.onNodeWithText("Log TDS test").performScrollTo().performClick()
            rule.awaitTag("value-tds_post_membrane")
            rule.onAllNodesWithTag("value-tds_prefilter").assertCountEquals(0)
            rule.onAllNodesWithTag("value-tds_output").assertCountEquals(1)
            rule.onNodeWithContentDescription("Close").performClick()

            // History is what happened: the entry still lists the reading it recorded. Read it
            // before the second test is filed, while there is only one row to open.
            rule.awaitText("SERVICE RECORD")
            rule.openLedgerEntry("TDS test", LocalDate.now())
            rule.awaitText("READINGS")
            rule.onNodeWithText("Pre-filter TDS").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("310").performScrollTo().assertIsDisplayed()
            rule.onNodeWithContentDescription("Back").performClick()

            // And the action still saves. The archived field was `required = true`, so before the
            // domain learned to stop demanding it, Save did nothing at all and said nothing.
            rule.awaitText("SERVICE RECORD")
            rule.onNodeWithText("Log TDS test").performScrollTo().performClick()
            rule.awaitTag("value-tds_post_membrane")
            listOf("tds_post_membrane" to "17", "tds_output" to "11").forEach { (key, value) ->
                rule.entryList().performScrollToNode(hasTestTag("value-$key"))
                rule.onNodeWithTag("value-$key").performTextReplacement(value)
            }
            rule.saveEntry()

            rule.awaitText("SERVICE RECORD")
            rule.onAllNodesWithText("TDS test").assertCountEquals(2)
            rule.onNodeWithText("17").performScrollTo().assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- (d) refused delete

    /**
     * D4 §13 as 2B-1 extends it: a delete is refused by what still points at the row, and the
     * dialog names every one of them — the entries, and the derived reading built on it.
     */
    @Test fun deletingAReadingWithDataAndADependentIsRefusedByName() {
        val id = newAsset("RO unit", templateKey = "ro_water")
        openAsset(id).use {
            rule.awaitText("Log TDS test")
            rule.logTdsTest(prefilter = "310", postMembrane = "18", output = "12")
            rule.awaitText("94.2")

            rule.onNodeWithText("Readings & actions").performScrollTo().performClick()
            rule.awaitText("Add reading")
            // Row 1 is Post-membrane TDS: one measurement behind it and source B of Rejection.
            rule.onAllNodesWithContentDescription("More")[1].performClick()
            rule.onNodeWithText("Delete").performClick()

            rule.awaitText("Delete this reading?")
            // The menu closes before the dialog's own Delete is the only one on screen.
            rule.waitUntil(WAIT_MS) {
                rule.onAllNodesWithText("Delete").fetchSemanticsNodes().size == 1
            }
            rule.onNodeWithText("Delete").performClick()

            rule.awaitText("Cannot delete this reading")
            rule.onNodeWithText("1 reading logged").assertIsDisplayed()
            rule.onNodeWithText("Used by Rejection").assertIsDisplayed()
            rule.onNodeWithText("Offered by TDS test").assertIsDisplayed()
            rule.onNodeWithText("OK").performClick()

            // Nothing was written: the row and its data are still there.
            rule.awaitText("Add reading")
            rule.onNodeWithText("Post-membrane TDS").performScrollTo().assertIsDisplayed()
            rule.onAllNodesWithText("ARCHIVED").assertCountEquals(0)
        }
    }

    // ---------------------------------------------------------------- (e) backup format 3

    /**
     * Spec §12 exit criterion 4, on the phone: a format-3 backup carries the derived definition and
     * its two sources through an export, a wipe and an import, and the asset derives again from the
     * imported rows. Called in-process — see the class comment.
     */
    @Test fun formatThreeRoundTripBringsTheDerivedReadingBack() {
        val id = newAsset("RO unit", templateKey = "ro_water")
        val assetId = AssetId(id)
        val definitions = runBlocking { app.graph.definitions.forAsset(assetId) }.associateBy { it.key }
        runBlocking {
            app.graph.logEvent.run(
                EventCommand(
                    assetId = assetId,
                    profileId = runBlocking { app.graph.profiles.forAsset(assetId) }.first().id,
                    kind = EventKind.MEASUREMENT,
                    title = "TDS test",
                    occurredOn = LocalDate.now().toString(),
                    occurredTime = null,
                    tzId = "UTC",
                    notes = "",
                    values = mapOf(
                        definitions.getValue("tds_prefilter").id to "310",
                        definitions.getValue("tds_post_membrane").id to "18",
                        definitions.getValue("tds_output").id to "12",
                    ),
                    consumables = emptyList(),
                ),
            )
        }

        val before = counts()
        val bytes = exportedDataArchive()
        clearInstall()
        assertEquals("the wipe emptied the store", 0, counts().values.sum())
        runBlocking { app.graph.importBackupReplace.run(bytes) }
        assertEquals(before, counts())

        // The derived definition came back as a derived definition, pointing at the same two rows.
        val after = runBlocking { app.graph.definitions.forAsset(assetId) }
        val rejection = after.single { it.kind == DefinitionKind.DERIVED }
        assertEquals("Rejection", rejection.label)
        val spec = rejection.derived
        assertNotNull("the imported derived row kept its formula and sources", spec)
        assertEquals(definitions.getValue("tds_prefilter").id, spec!!.sourceA)
        assertEquals(definitions.getValue("tds_post_membrane").id, spec.sourceB)
        assertEquals(
            "every entered row came back ENTERED with no sources",
            emptyList<DefinitionId>(),
            after.filter { it.kind == DefinitionKind.ENTERED && it.derived != null }.map { it.id },
        )

        openAsset(id).use {
            rule.awaitText("CURRENT READINGS")
            rule.onNodeWithText("Rejection").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("94.2").performScrollTo().assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- (f) the long-profile walk

    /**
     * The 2A minor the entry form fixed in 2B-1 (spec §9): the form is a `Column` with
     * `verticalScroll` and not a `LazyColumn`, so every row stays composed and `ImeAction.Next`
     * walks the focus all the way down a profile long enough to scroll.
     */
    @Test fun imeNextWalksFocusDownALongProfile() {
        val id = newAsset("Test bench", templateKey = null)
        val assetId = AssetId(id)
        runBlocking {
            val fields = (1..6).map { n ->
                app.graph.saveDefinition.run(
                    null,
                    DefinitionCommand(
                        assetId = assetId,
                        key = "f$n",
                        label = "Field $n",
                        unit = "",
                        kind = DefinitionKind.ENTERED,
                        valueType = ValueType.NUMBER,
                        decimals = 0,
                        rangeLow = null,
                        rangeHigh = null,
                        isMeter = false,
                        formula = null,
                        sourceA = null,
                        sourceB = null,
                    ),
                )
            }
            app.graph.saveProfile.run(
                null,
                ProfileCommand(
                    assetId = assetId,
                    name = "Long form",
                    eventKind = EventKind.MEASUREMENT,
                    defaultTitle = "",
                    fields = fields.map { ProfileFieldInput(it.id, required = false) },
                    consumables = emptyList(),
                ),
            )
        }

        openAsset(id).use {
            rule.awaitText("Log long form")
            rule.onNodeWithText("Log long form").performScrollTo().performClick()
            rule.awaitTag("value-f1")
            // Every row exists before anything is scrolled: a lazy list would not have f6 yet.
            (1..6).forEach { rule.onAllNodesWithTag("value-f$it").assertCountEquals(1) }

            rule.onNodeWithTag("value-f1").performTextInput("1")
            rule.onNodeWithTag("value-f1").assertIsFocused()
            (1..5).forEach { n ->
                rule.onNodeWithTag("value-f$n").performImeAction()
                rule.awaitFocus("value-f${n + 1}")
            }
            rule.onNodeWithTag("value-f6").assertIsFocused()
        }
    }
}

// -------------------------------------------------------------------- fixtures

/** How long a device assertion waits for a Room flow, a navigation or a recomposition to land. */
private const val WAIT_MS = 10_000L

/** Created in-process: every row under test starts on a screen, not on the new-asset form. */
private fun newAsset(name: String, templateKey: String?): String =
    runBlocking { app.graph.createAsset.run(name, templateKey = templateKey).id.value }

/** The cold-start path a tag tap takes, which is also the one `ActivityScenario` can track (1C). */
private fun openAsset(id: String): ActivityScenario<MainActivity> {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("servicetag://asset/$id"))
        .setClass(context, MainActivity::class.java)
    return ActivityScenario.launch(intent)
}

/** The journal tables 2B-1 touches, counted through the ports, for the round trip. */
private fun counts(): Map<String, Int> = runBlocking {
    val graph = app.graph
    val profiles = graph.profiles.all()
    val events = graph.events.all()
    mapOf(
        "asset" to graph.assets.all().size,
        "measurement_definition" to graph.definitions.all().size,
        "event_profile" to profiles.size,
        "profile_field" to profiles.sumOf { it.fields.size },
        "profile_consumable" to profiles.sumOf { it.consumables.size },
        "asset_event" to events.size,
        "measurement" to events.sumOf { it.measurements.size },
        "consumable_usage" to events.sumOf { it.consumables.size },
    )
}

/** "15 SEP", the way `LedgerEntry` writes the day it happened on. */
private fun ledgerDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("dd")) + " " +
        date.format(DateTimeFormatter.ofPattern("MMM")).uppercase()

// -------------------------------------------------------------------- driving the screens

/**
 * One labelled field of a form. A Material text field merges its label into its own semantics node,
 * so the label is how a field is named here — the editors carry no test tags of their own, and the
 * label is what the person filling the form reads.
 */
private fun ComposeTestRule.field(label: String): SemanticsNodeInteraction =
    onNode(hasSetTextAction() and hasText(label))

/**
 * The entry form is one scrolling `Column`, so a row below the fold has to be scrolled to before it
 * can be tapped. A focused `OutlinedTextField` carries `ScrollBy` of its own, so the form is the
 * scrollable that is *not* a text field (2A).
 */
private fun ComposeTestRule.entryList(): SemanticsNodeInteraction =
    onNode(hasScrollAction() and !hasSetTextAction())

/** Waits until at least [count] nodes *contain* [text] — for a value inside a composed line. */
private fun ComposeTestRule.awaitContaining(text: String, count: Int = 1) {
    waitUntil(WAIT_MS) {
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size >= count
    }
}

private fun ComposeTestRule.awaitTag(tag: String) {
    waitUntil(WAIT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
}

/** Waits until the field carrying [tag] is the one holding focus. */
private fun ComposeTestRule.awaitFocus(tag: String) {
    waitUntil(WAIT_MS) {
        onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
    }
}

/**
 * The entry form's one commit control: the button at its foot, "Record entry" on the new entries
 * this suite logs. It ends the form's scrolling column, so it is scrolled into view first. The
 * reading and action editors commit with their own "Save", which is not this one.
 */
private fun ComposeTestRule.saveEntry() {
    onNodeWithText("Record entry").performScrollTo().performClick()
}

/**
 * A TDS test through the entry form. A null value leaves the field alone, which is what makes the
 * second test of the same-event row a test that cannot produce a rejection on its own.
 */
private fun ComposeTestRule.logTdsTest(prefilter: String?, postMembrane: String?, output: String?) {
    onNodeWithText("Log TDS test").performScrollTo().performClick()
    awaitTag("value-tds_prefilter")
    listOf(
        "tds_prefilter" to prefilter,
        "tds_post_membrane" to postMembrane,
        "tds_output" to output,
    ).forEach { (key, value) ->
        if (value == null) return@forEach
        entryList().performScrollToNode(hasTestTag("value-$key"))
        onNodeWithTag("value-$key").performTextReplacement(value)
    }
    saveEntry()
    awaitText("SERVICE RECORD")
}

/** One ledger row of the service record, named by its title and the day it is filed under. */
private fun ComposeTestRule.openLedgerEntry(title: String, on: LocalDate) {
    val entry = hasClickAction() and hasText(title) and hasText(ledgerDate(on))
    waitUntil(WAIT_MS) { onAllNodes(entry).fetchSemanticsNodes().isNotEmpty() }
    onNode(entry).performScrollTo().performClick()
}
