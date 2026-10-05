package com.loosecannon.servicetag.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.loosecannon.servicetag.MainActivity
import com.loosecannon.servicetag.l10n.dateFieldText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Phase 2A's device proof: the manual checklist of `docs/design/phase-2a-evidence.md` §4, rows
 * 3–11, driven through the real UI on real hardware instead of by hand.
 *
 * One test per checklist row group, each cold-starting the asset it needs through its
 * `servicetag://asset/<id>` deep link — the same path a tag tap takes — and asserting that row's
 * "Expected" column on screen. `JournalSmokeTest` stays as the one-test smoke of the vertical
 * slice; this is the walk-through.
 *
 * Two things the suite deliberately does *not* claim:
 *
 * - **Pairing a row's value with its badge is asserted by presence and count, not adjacency.**
 *   `InstrumentRow` puts the label, the value and the badge in a plain `Row` with no semantics of
 *   its own, so the merged tree has no node that owns all three; the test asserts that each value
 *   is on screen and that the exact multiset of state words is on screen. Which definition earns
 *   which state is pinned by `RangeStateTest` and `LatestReadingsTest` on the JVM.
 * - **The asset and its events are created through the UI wherever the row is about the UI.** The
 *   asset itself is created in-process (the row under test starts on the asset screen, and the new
 *   asset form is already proven by `AppSmokeTest`), and row 10's export/wipe/import is in-process
 *   by construction — the file half of it is a SAF document picker, which is not the app.
 */
class JournalDeviceProofTest {

    @get:Rule val rule = createEmptyComposeRule()

    /** Destructive, exactly as the 1C suite: no preferences, no rows, no journal. */
    @Before fun freshInstall() = clearInstall()

    // ---------------------------------------------------------------- rows 3 and 4

    /**
     * Rows 3 and 4. The template puts five empty readings and two logging actions on the asset;
     * one water test fills them, states itself while it is being typed, and lands in the record.
     */
    @Test fun hotTubTemplateSeedsReadingsThenAWaterTestFillsThem() {
        val id = newAsset("Hot tub", "hot_tub")
        openAsset(id).use {
            // Row 3: five reading rows, nothing logged against any of them, and the template's
            // two profiles as the first two quick actions.
            rule.awaitText("CURRENT READINGS")
            HOT_TUB_LABELS.forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
            // Five empty readings plus the six blank plate cells of spec §9 (Model, Serial,
            // Location, Purchased, In service, NFC tag) — 2B-2 grew the plate from three.
            rule.onAllNodesWithText("—").assertCountEquals(11)
            rule.onNodeWithText("Log water test").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Log treatment").performScrollTo().assertIsDisplayed()

            // Row 4: the full test sheet, each row stating itself as the number goes in.
            rule.onNodeWithText("Log water test").performScrollTo().performClick()
            rule.awaitTag("value-ph")
            rule.typeReading("ph", "7.9", says = "HIGH")
            rule.typeReading("free_chlorine", "0.8", says = "LOW")
            rule.typeReading("alkalinity", "110", says = "IN RANGE")
            rule.typeReading("calcium_hardness", "200", says = "IN RANGE")
            rule.typeReading("water_temp", "102", says = "NO TARGET SET")

            rule.addMaterial("Chlorine", "1")
            rule.addMaterial("pH reducer", "0.5")

            rule.saveEntry()

            // Back on the asset: the five stored numbers, at each definition's own precision.
            rule.awaitText("SERVICE RECORD")
            listOf("7.9", "0.8", "110", "200", "102").forEach {
                rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
            }
            // HIGH twice: the pH reading and the ledger entry's own out-of-range badge.
            rule.onAllNodesWithText("HIGH").assertCountEquals(2)
            rule.onAllNodesWithText("LOW").assertCountEquals(1)
            rule.onAllNodesWithText("IN RANGE").assertCountEquals(2)
            rule.onAllNodesWithText("NO TARGET SET").assertCountEquals(1)
            // The ledger line is one merged clickable node carrying title, date, detail and badge.
            rule.onNode(
                hasText("Water test") and
                    hasText("pH 7.9 · Free chlorine 0.8 ppm · Alkalinity 110 ppm"),
            ).performScrollTo().assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- row 5

    /** Row 5. An edit corrects the reading in place: same entry, new number, no second row. */
    @Test fun editingTheWaterTestCorrectsItInPlace() {
        val id = newAsset("Hot tub", "hot_tub")
        openAsset(id).use {
            rule.awaitText("Log water test")
            rule.logWaterTest(ph = "7.9")

            rule.openLedgerEntry("Water test", LocalDate.now())
            rule.awaitText("WATER TEST")
            rule.onNodeWithContentDescription("More").performClick()
            rule.onNodeWithText("Edit").performClick()

            rule.awaitTag("value-ph")
            rule.onNodeWithTag("value-ph").performTextReplacement("7.5")
            rule.saveEntry()

            // The edit pops back onto the entry it changed, which now reads 7.5.
            rule.awaitText("7.5")
            rule.onNodeWithContentDescription("Back").performClick()

            rule.awaitText("SERVICE RECORD")
            rule.onNodeWithText("7.5").performScrollTo().assertIsDisplayed()
            // pH 7.5 and free chlorine 2.0 are both inside their targets, and nothing is flagged.
            rule.onAllNodesWithText("IN RANGE").assertCountEquals(2)
            rule.onAllNodesWithText("HIGH").assertCountEquals(0)
            rule.onAllNodesWithText("7.9").assertCountEquals(0)
            // One entry, not two: the edit updated the event it was opened from.
            rule.onAllNodesWithText("Water test").assertCountEquals(1)
        }
    }

    // ---------------------------------------------------------------- row 6

    /** Row 6. A backdated test is filed under its own day and does not become the current value. */
    @Test fun aBackdatedTestSitsBelowAndLeavesTheCurrentReadingAlone() {
        val id = newAsset("Hot tub", "hot_tub")
        val today = LocalDate.now()
        val weekAgo = today.minusDays(7)
        openAsset(id).use {
            rule.awaitText("Log water test")
            rule.logWaterTest(ph = "7.5")
            rule.logWaterTest(ph = "7.0", on = weekAgo)

            rule.awaitText("Water test", count = 2)
            // Current readings still name today's entry.
            rule.onNodeWithText("7.5").performScrollTo().assertIsDisplayed()
            rule.onAllNodesWithText("7.0").assertCountEquals(0)

            // "Below" is a statement about the screen, so it is read off the laid-out positions.
            val entries = rule.onAllNodesWithText("Water test").fetchSemanticsNodes()
            assertEquals("two entries in the record", 2, entries.size)
            val newest = entries.single { hasText(ledgerDate(today)).matches(it) }
            val oldest = entries.single { hasText(ledgerDate(weekAgo)).matches(it) }
            val newestY = newest.positionInRoot.y
            val oldestY = oldest.positionInRoot.y
            check(oldestY > newestY) { "the backdated entry is at $oldestY, today's at $newestY" }
        }
    }

    // ---------------------------------------------------------------- row 7

    /** Row 7. Range-less definitions say so, and a boolean answers the question its label asks. */
    @Test fun upsLoadTestShowsNoTargetsAndPassedYes() {
        val id = newAsset("UPS", "ups")
        openAsset(id).use {
            rule.awaitText("Log load test")
            rule.onNodeWithText("Log load test").performScrollTo().performClick()

            rule.awaitTag("value-battery_voltage")
            rule.typeReading("battery_voltage", "12.7", says = "NO TARGET SET")
            rule.typeReading("load_percent", "38", says = "NO TARGET SET")
            rule.typeReading("runtime_minutes", "42", says = "NO TARGET SET")
            // The BOOLEAN row is a segmented control, not a field: Yes is a tap.
            rule.entryList().performScrollToNode(hasTestTag("value-test_passed"))
            rule.onNodeWithText("Yes").performClick()
            rule.saveEntry()

            rule.awaitText("SERVICE RECORD")
            listOf("12.7", "38", "42").forEach {
                rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
            }
            // Three numbers with no range between them; the boolean carries no state at all.
            rule.onAllNodesWithText("NO TARGET SET").assertCountEquals(3)
            rule.onNodeWithText("Passed").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Yes").performScrollTo().assertIsDisplayed()
            rule.onNode(
                hasText("Load test") and
                    hasText("Battery voltage 12.7 V · Load 38 % · Runtime 42 min"),
            ).performScrollTo().assertIsDisplayed()
        }
    }

    // ---------------------------------------------------------------- row 8

    /** Row 8. A meter reading is an ordinary measurement, and the materials go with the entry. */
    @Test fun mowerOilChangeRecordsTheMeterAndBothMaterials() {
        val id = newAsset("Mower", "power_equipment")
        openAsset(id).use {
            rule.awaitText("Log oil change")
            rule.onNodeWithText("Log oil change").performScrollTo().performClick()

            rule.awaitTag("value-engine_hours")
            rule.typeReading("engine_hours", "138.5", says = "NO TARGET SET")
            rule.addMaterial("Engine oil", "1.5")
            rule.addMaterial("Oil filter", "1")
            rule.saveEntry()

            rule.awaitText("SERVICE RECORD")
            rule.onNodeWithText("138.5").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Engine hours").performScrollTo().assertIsDisplayed()

            rule.openLedgerEntry("Oil change", LocalDate.now())
            rule.awaitText("MATERIALS USED")
            // Both lines, each with the unit the suggestion chip brought with it.
            listOf("Engine oil", "1.5", "qt", "Oil filter", "1", "pcs").forEach {
                rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
            }
        }
    }

    // ---------------------------------------------------------------- row 9

    /** Row 9. An asset saved with no template gets one later, and the offer then goes away. */
    @Test fun anAssetWithNoTemplateCanBeSetUpLater() {
        val id = newAsset("Water filter", templateKey = null)
        openAsset(id).use {
            rule.awaitText("Set up from template")
            rule.onNodeWithText("Set up from template").performScrollTo().performClick()

            // The picker names the five seeds and nothing else.
            rule.awaitText("RO water")
            rule.onNodeWithText("RO water").performClick()

            rule.awaitText("CURRENT READINGS")
            // 2B-1 gave the template a fourth row: "Rejection", derived from the first two.
            listOf("Pre-filter TDS", "Post-membrane TDS", "Output TDS", "Rejection").forEach {
                rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
            }
            // Four empty readings — the three entered plus the derived one, which cannot compute
            // from an asset with no events — plus the six blank plate cells of spec §9.
            rule.onAllNodesWithText("—").assertCountEquals(10)
            // `quickActionLabel` only lowercases the profile name's first character when the
            // second one is itself lowercase, so an acronym like "TDS test" reads "Log TDS test"
            // rather than the mangled "Log tDS test" a blanket decapitalize used to produce.
            rule.onNodeWithText("Log TDS test").performScrollTo().assertIsDisplayed()
            // Doing it again is not offered: the asset is no longer bare.
            rule.onAllNodesWithText("Set up from template").assertCountEquals(0)
        }
    }

    // ---------------------------------------------------------------- row 10

    /**
     * Row 10, on the phone rather than on the JVM: export, wipe, import, and every one of the ten
     * tables comes back at the count it left at — then the asset renders again from those rows.
     *
     * The export and the import are called in-process. The UI half of the backup screen is a SAF
     * document picker, which belongs to the system and not to the app; what is under test here is
     * that the round trip is lossless against real Room on real hardware. The wipe deliberately
     * runs between two activity launches: `AssetDetailScreen` leaves when its asset disappears, so
     * wiping under an open screen would prove nothing about the import that follows it.
     */
    @Test fun backupRoundTripKeepsEveryCountAndTheAssetRendersAgain() {
        val id = newAsset("Hot tub", "hot_tub")
        openAsset(id).use {
            rule.awaitText("Log water test")
            rule.logWaterTest(ph = "7.9")
            rule.awaitText("SERVICE RECORD")
        }

        val before = tableCounts()
        val bytes = exportedDataArchive()
        clearInstall()
        assertEquals("the wipe emptied the store", 0, tableCounts().values.sum())
        runBlocking { app.graph.importBackupReplace.run(bytes) }

        assertEquals(before, tableCounts())

        openAsset(id).use {
            rule.awaitText("SERVICE RECORD")
            rule.onNodeWithText("7.9").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Water test").performScrollTo().assertIsDisplayed()
            rule.onAllNodesWithText("HIGH").assertCountEquals(2)
        }
    }

    // ---------------------------------------------------------------- row 11

    /** Row 11. Deleting the newest entry is not a hole: the previous reading becomes current. */
    @Test fun deletingTodaysTestFallsBackToTheOlderReading() {
        val id = newAsset("Hot tub", "hot_tub")
        val today = LocalDate.now()
        openAsset(id).use {
            rule.awaitText("Log water test")
            rule.logWaterTest(ph = "7.0", on = today.minusDays(7))
            rule.logWaterTest(ph = "7.5")
            rule.awaitText("Water test", count = 2)

            rule.openLedgerEntry("Water test", today)
            rule.awaitText("WATER TEST")
            rule.onNodeWithContentDescription("More").performClick()
            rule.onNodeWithText("Delete").performClick()

            rule.awaitText("Delete this entry?")
            // The menu is dismissed before the dialog's own Delete is the only one on screen.
            rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText("Delete").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithText("Delete").performClick()

            rule.awaitText("SERVICE RECORD")
            rule.onAllNodesWithText("Water test").assertCountEquals(1)
            rule.onAllNodesWithText("7.5").assertCountEquals(0)
            rule.onNodeWithText("7.0").performScrollTo().assertIsDisplayed()
            // LOW twice: the pH reading itself and the surviving entry's out-of-range badge.
            rule.onAllNodesWithText("LOW").assertCountEquals(2)
        }
    }
}

// -------------------------------------------------------------------- fixtures

/** How long a device assertion waits for a Room flow, a navigation or a recomposition to land. */
private const val WAIT_MS = 10_000L

private val HOT_TUB_LABELS =
    listOf("pH", "Free chlorine", "Alkalinity", "Calcium hardness", "Water temperature")

/** Created in-process: every row under test starts on the asset screen, not on the new-asset form. */
private fun newAsset(name: String, templateKey: String?): String =
    runBlocking { app.graph.createAsset.run(name, templateKey = templateKey).id.value }

/** The cold-start path a tag tap takes, which is also the one `ActivityScenario` can track (1C). */
private fun openAsset(id: String): ActivityScenario<MainActivity> {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("servicetag://asset/$id"))
        .setClass(context, MainActivity::class.java)
    return ActivityScenario.launch(intent)
}

/** All ten tables, counted through the ports: the three from 1B and the seven 2A added. */
private fun tableCounts(): Map<String, Int> = runBlocking {
    val graph = app.graph
    val profiles = graph.profiles.all()
    val events = graph.events.all()
    mapOf(
        "asset" to graph.assets.all().size,
        "nfc_tag" to graph.tags.all().size,
        "external_link" to graph.links.all().size,
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
 * The entry form is one scrolling `Column` (2B-1 §9 — a lazy list disposed the focused row), so a
 * row below the fold still has to be scrolled to before it can be tapped. A focused
 * `OutlinedTextField` carries `ScrollBy` of its own, so the form is the scrollable that is *not* a
 * text field.
 */
private fun ComposeTestRule.entryList(): SemanticsNodeInteraction =
    onNode(hasScrollAction() and !hasSetTextAction())

private fun ComposeTestRule.awaitTag(tag: String) {
    waitUntil(WAIT_MS) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
}

/**
 * Types one reading and asserts what its row says back while it is still being typed — the live
 * range state of G1 §1.3, read off the screen rather than off the ViewModel.
 *
 * The badge is a sibling of the field rather than a child of it, so it is attributed by arithmetic:
 * after the value goes in, exactly one more row on the form says [says] than said it before. A
 * word that was already on screen from an earlier row therefore proves nothing on its own.
 */
private fun ComposeTestRule.typeReading(key: String, value: String, says: String) {
    entryList().performScrollToNode(hasTestTag("value-$key"))
    val before = onAllNodesWithText(says).fetchSemanticsNodes().size
    onNodeWithTag("value-$key").performTextInput(value)
    waitUntil(WAIT_MS) { onAllNodesWithText(says).fetchSemanticsNodes().size == before + 1 }
    onAllNodesWithText(says).assertCountEquals(before + 1)
}

/**
 * Adds a material from the profile's own suggestion chip — which brings the unit with it — and
 * types the quantity.
 *
 * The quantity field carries no test tag of its own, so it is found by position: within a material
 * row the fields are Material, Qty, Unit in that order, so it is the text field immediately after
 * the one the chip just filled with the material's name.
 */
private fun ComposeTestRule.addMaterial(suggestion: String, quantity: String) {
    entryList().performScrollToNode(hasText("MATERIALS USED"))
    onNodeWithText(suggestion).performClick()
    textFieldAfter(suggestion).performTextInput(quantity)
}

/** The text field one place after the one currently holding [holding]. */
private fun ComposeTestRule.textFieldAfter(holding: String): SemanticsNodeInteraction {
    val fields = onAllNodes(hasSetTextAction())
    val holder = hasText(holding)
    val index = fields.fetchSemanticsNodes().indexOfFirst { holder.matches(it) }
    check(index >= 0) { "no text field on screen holds \"$holding\"" }
    return fields[index + 1]
}

/**
 * The app bar's Save, which is the one that commits; the button at the foot of the form reads
 * "Record entry" or "Save entry", so this is unambiguous.
 */
private fun ComposeTestRule.saveEntry() {
    onAllNodesWithText("Save").onFirst().performClick()
}

/**
 * A minimal water test through the form: the profile's two required readings, optionally against
 * an earlier day. Used by the rows whose subject is what happens *after* an entry exists.
 */
private fun ComposeTestRule.logWaterTest(
    ph: String,
    freeChlorine: String = "2.0",
    on: LocalDate? = null,
) {
    onNodeWithText("Log water test").performScrollTo().performClick()
    awaitTag("value-ph")
    if (on != null) {
        // Backdating is ordinary (§4): the date is a plain field holding today until it is changed.
        // #102: the field draws and reads the phone's own order, so the day is found and typed that way.
        onNode(hasSetTextAction() and hasText(dateFieldText(LocalDate.now().toString())))
            .performTextReplacement(dateFieldText(on.toString()))
    }
    onNodeWithTag("value-ph").performTextReplacement(ph)
    entryList().performScrollToNode(hasTestTag("value-free_chlorine"))
    onNodeWithTag("value-free_chlorine").performTextReplacement(freeChlorine)
    saveEntry()
    awaitText("SERVICE RECORD")
}

/** One ledger row of the service record, named by its title and the day it is filed under. */
private fun ComposeTestRule.openLedgerEntry(title: String, on: LocalDate) {
    val entry = hasText(title) and hasText(ledgerDate(on))
    waitUntil(WAIT_MS) { onAllNodes(entry).fetchSemanticsNodes().isNotEmpty() }
    onNode(entry).performScrollTo().performClick()
}
