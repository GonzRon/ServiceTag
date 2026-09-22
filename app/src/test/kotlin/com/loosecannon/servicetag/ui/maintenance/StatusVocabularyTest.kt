package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.schedule.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D12 §5's acceptance, asserted where it can be: **every** status carries its own word and its own
 * glyph, so with colour removed the wording plus the glyph plus the row's position still tell the
 * seven states apart (#5 AC 2, D12 §5 `:274-296`).
 *
 * Colour is the fourth channel and the one that may be lost; a duplicated word or glyph would
 * quietly reduce four channels to two, and a device test over a seeded store can only ever show the
 * three or four statuses that store happens to hold. This is the whole table, on the JVM.
 */
class StatusVocabularyTest {

    /** The seven RATIFIED words (spec §9.1), in the enum's own order, each one distinct. */
    @Test fun everyStatusHasItsOwnRatifiedWord() {
        assertEquals(
            listOf("OK", "DUE SOON", "DUE", "OVERDUE", "OUT OF SEASON", "PAUSED", "NO BASELINE"),
            DueStatus.entries.map(::statusLabel),
        )
        assertEquals(
            "a duplicated word would leave two states reading the same in grayscale",
            DueStatus.entries.size,
            DueStatus.entries.map(::statusLabel).distinct().size,
        )
    }

    /**
     * And its own glyph. `INACTIVE_SEASON`, `PAUSED` and `NO_DATA` must each read differently from
     * `OVERDUE`, which the distinctness covers and these three assertions name, because those are
     * the three the brief calls out.
     */
    @Test fun everyStatusHasItsOwnGlyph() {
        assertEquals(
            "a duplicated glyph would leave two states looking the same in grayscale",
            DueStatus.entries.size,
            DueStatus.entries.map(::statusGlyph).distinct().size,
        )
        val overdue = statusGlyph(DueStatus.OVERDUE)
        for (quiet in listOf(DueStatus.INACTIVE_SEASON, DueStatus.PAUSED, DueStatus.NO_DATA)) {
            assertEquals(
                "$quiet must not look like OVERDUE",
                false,
                statusGlyph(quiet) == overdue,
            )
        }
    }

    /** The four RATIFIED section labels (pre-ratified with D12 §10 `:706-707`), in the drawn order. */
    @Test fun theSectionLabelsAreTheRatifiedFour() {
        assertEquals(
            listOf("ATTENTION", "UPCOMING", "CURRENT", "OUT OF SEASON"),
            AttentionSection.entries.map(::sectionLabel),
        )
    }
}
