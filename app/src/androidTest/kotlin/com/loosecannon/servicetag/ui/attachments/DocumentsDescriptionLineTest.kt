package com.loosecannon.servicetag.ui.attachments

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D-19, drawn. `DocumentsSection` is a pure function of its state — no `AppGraph`, no Room — so
 * each case renders it directly with hand-built rows, exactly as `AssetTagsSectionTest` renders
 * `TagsSection`.
 *
 * The description was collected by the picker, stored in `AddAttachmentCommand.notes` and never
 * shown; these four cases are the ones that can go wrong when a second line is added to a row that
 * had exactly one: it must not replace the shipped line, must not appear when there is nothing to
 * say, must not appear over bytes that are gone, and must not wrap.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class DocumentsDescriptionLineTest {

    @get:Rule val rule = createComposeRule()

    private fun row(
        id: String,
        name: String,
        notes: String,
        present: Boolean = true,
    ) = AttachmentRowState(
        id = id,
        displayName = name,
        kind = AttachmentKind.MANUAL,
        sizeBytes = 2048L,
        capturedOn = "2026-09-20",
        notes = notes,
        mimeType = "application/pdf",
        locator = "assets/$id/$name",
        isImage = false,
        present = present,
    )

    private fun draw(vararg rows: AttachmentRowState) {
        rule.setContent {
            ServiceTagTheme {
                DocumentsSection(
                    state = AttachmentsSectionState(store = READY, rows = rows.toList()),
                    onOpen = {},
                    onEdit = {},
                    onAddFiles = {},
                    onTakePhoto = {},
                    onOpenSettings = {},
                )
            }
        }
        rule.waitForIdle()
    }

    /**
     * The shipped `kind · size · captured-on` line stays and the description is added under it —
     * added, never substituted. The row is clickable, so it is **one merged semantics node** and
     * its text list is the whole of what it draws; asserting the list exactly is what catches a
     * line that replaced the shipped one rather than joining it.
     */
    @Test fun aPresentRowDrawsBothItsShippedQuietLineAndItsDescription() {
        draw(row("a", "Deck manual.pdf", "Section 4 covers the pump seal"))

        rule.onNode(
            hasTextExactly(
                "Deck manual.pdf",
                "Manual · 2.0 KB · 2026-09-20",
                "Section 4 covers the pump seal",
            ),
        ).assertIsDisplayed()
    }

    /**
     * A row with nothing in `notes` draws no second line at all — not an empty one. An unguarded
     * `QuietLine(row.notes)` would put a blank text node on every row in the section, which is
     * what the empty-text count catches.
     */
    @Test fun aRowWithNoDescriptionDrawsNoSecondLine() {
        draw(row("a", "Deck manual.pdf", ""))

        rule.onNode(hasTextExactly("Deck manual.pdf", "Manual · 2.0 KB · 2026-09-20"))
            .assertIsDisplayed()
    }

    /**
     * The bytes are gone, so the row's whole message is that they are gone: "Not on this device"
     * and no prose under it, however much of it was stored.
     */
    @Test fun aRowWhoseBytesAreMissingSaysSoAndCarriesNoDescription() {
        draw(row("a", "Deck manual.pdf", "Section 4 covers the pump seal", present = false))

        rule.onNode(hasTextExactly("Deck manual.pdf", "Not on this device")).assertIsDisplayed()
    }

    /**
     * The cap is 2,000 characters and the row is compact, so the line is held to one and
     * ellipsised. Two rows in one composition, so the comparison is against a line that is
     * genuinely one line rather than against a number this test made up.
     */
    @Test fun aTwoThousandCharacterDescriptionRendersOnOneLine() {
        val long = "Bearing race replaced " + "x".repeat(LONG_ENOUGH_TO_WRAP - "Bearing race replaced ".length)
        draw(
            row("a", "Deck manual.pdf", "Short note"),
            row("b", "Pump manual.pdf", long),
        )

        // The unmerged tree, so each lookup lands on the description `Text` itself: a row is
        // clickable and therefore merges, and its height has a 56 dp thumbnail floor under it
        // that could hide a line or two of wrapping.
        val short = rule.onNodeWithText("Short note", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().height
        val wrapped = rule.onNodeWithText("Bearing race replaced ", substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot().height

        assertEquals(LONG_ENOUGH_TO_WRAP, long.length)
        // Half a dp of tolerance, because measuring the two `Text` nodes themselves exposes the
        // sub-pixel jitter the merged row's 56 dp floor used to swallow. A second line would add
        // a whole line height, so the assertion still bites on everything it is here to catch.
        assertEquals(
            "the description must not wrap the row",
            short.value.toDouble(),
            wrapped.value.toDouble(),
            0.5,
        )
    }

    private companion object {
        val READY = StoreState.Ready("Attachments", "com.example.provider")

        /**
         * A description long enough that a wrapping line would be unmistakable — dozens of rows
         * deep. It is **not** a cap: `AddAttachment` trims `notes` and never truncates it, so an
         * attachment description has no maximum length at all, which is the other half of why the
         * line has to be held to one.
         */
        const val LONG_ENOUGH_TO_WRAP = 2_000
    }
}
