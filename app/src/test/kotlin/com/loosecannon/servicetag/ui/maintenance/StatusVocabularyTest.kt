package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.ui.theme.ServiceTagDarkSemanticColors
import com.loosecannon.servicetag.ui.theme.ServiceTagLightSemanticColors
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D12 §5's acceptance, asserted where it can be: **every** status carries its own word and its own
 * glyph, so with colour removed the wording plus the glyph plus the row's position still tell the
 * eight states apart (#5 AC 2, D12 §5 `:274-296`; 1.4 spec §10.6 for DEFERRED).
 *
 * Colour is the fourth channel and the one that may be lost; a duplicated word or glyph would
 * quietly reduce four channels to two, and a device test over a seeded store can only ever show the
 * three or four statuses that store happens to hold. This is the whole table, on the JVM.
 */
class StatusVocabularyTest {

    /**
     * The eight RATIFIED words (spec §9.1; DEFERRED is 1.4's S92), in the enum's own order, each one
     * distinct.
     */
    @Test fun everyStatusHasItsOwnRatifiedWord() {
        assertEquals(
            listOf("OK", "DUE SOON", "DUE", "OVERDUE", "OUT OF SEASON", "PAUSED", "NO BASELINE", "DEFERRED"),
            DueStatus.entries.map(::statusLabel),
        )
        assertEquals(
            "a duplicated word would leave two states reading the same in grayscale",
            DueStatus.entries.size,
            DueStatus.entries.map(::statusLabel).distinct().size,
        )
    }

    /**
     * And its own glyph. `INACTIVE_SEASON`, `PAUSED`, `NO_DATA` and `DEFERRED` must each read
     * differently from `OVERDUE`, which the distinctness covers and these assertions name. DEFERRED
     * is the hourglass (1.4 spec §10.6), and in particular not the pause glyph: held by the break is
     * not paused.
     */
    @Test fun everyStatusHasItsOwnGlyph() {
        assertEquals(
            "a duplicated glyph would leave two states looking the same in grayscale",
            DueStatus.entries.size,
            DueStatus.entries.map(::statusGlyph).distinct().size,
        )
        assertEquals(StatusGlyph.HOURGLASS, statusGlyph(DueStatus.DEFERRED))
        val overdue = statusGlyph(DueStatus.OVERDUE)
        for (quiet in listOf(DueStatus.INACTIVE_SEASON, DueStatus.PAUSED, DueStatus.NO_DATA, DueStatus.DEFERRED)) {
            assertEquals(
                "$quiet must not look like OVERDUE",
                false,
                statusGlyph(quiet) == overdue,
            )
        }
    }

    /** DEFERRED is drawn in season-inactive grey (1.4 spec §10.6), in both palettes. */
    @Test fun deferredTakesTheSeasonInactiveColours() {
        for (palette in listOf(ServiceTagLightSemanticColors, ServiceTagDarkSemanticColors)) {
            assertEquals(palette.seasonInactive, statusColors(DueStatus.DEFERRED, palette))
        }
    }

    /** The four RATIFIED section labels (pre-ratified with D12 §10 `:706-707`), in the drawn order. */
    @Test fun theSectionLabelsAreTheRatifiedFour() {
        assertEquals(
            listOf("ATTENTION", "UPCOMING", "CURRENT", "OUT OF SEASON"),
            AttentionSection.entries.map(::sectionLabel),
        )
    }

    /**
     * B07's badge, verbatim: **"Snoozed until \<date\>"** (master plan §17), in the shipped display
     * date shape and in the device's own zone — the instant is device-local by definition.
     *
     * It is a line **beside** the status badge, so the status word this row draws is still OVERDUE:
     * a snooze suppresses delivery and moves no obligation (invariant 20, D-13).
     *
     * The zone is `Etc/GMT+5`, but it is incidental here: `now` and `until` are both built and read
     * back in the same `zone`, so this test passes under any zone, UTC included — it is not the
     * hazard this test covers. `Etc/GMT+5` is used only so this file names no real place's zone.
     */
    @Test fun aSnoozedRowCarriesTheRatifiedBadgeAndKeepsItsStatusWord() {
        val zone = ZoneId.of("Etc/GMT+5")
        val now = LocalDate.parse("2026-06-15").atTime(9, 5).atZone(zone).toInstant().toEpochMilli()
        val until = LocalDate.parse("2026-06-16").atTime(9, 5).atZone(zone).toInstant().toEpochMilli()

        assertEquals("Snoozed until 16 Jun 2026", snoozeLine(row(until), now, zone))
        assertEquals("OVERDUE", statusLabel(row(until).status))
    }

    /**
     * No snooze, and a **lapsed** one, each draw nothing. A row still claiming a snooze whose
     * instant has passed would be saying something the delivery path stopped believing at the same
     * moment — the next digest run posts that schedule again.
     */
    @Test fun aRowWithNoSnoozeOrALapsedOneDrawsNothing() {
        val now = 1_781_000_000_000L

        assertNull(snoozeLine(row(null), now, ZoneId.of("UTC")))
        assertNull(snoozeLine(row(now), now, ZoneId.of("UTC")))
        assertNull(snoozeLine(row(now - 1L), now, ZoneId.of("UTC")))
    }

    /** An OVERDUE asset-targeted row, carrying nothing but the snooze under test. */
    private fun row(snoozedUntil: Long?) = DueItem(
        scheduleId = ScheduleId("s1"),
        title = "Filter change",
        target = ScheduleTarget.AssetTarget(AssetId("a1")),
        assetName = "Pump house filter",
        parentName = null,
        category = "Water",
        status = DueStatus.OVERDUE,
        section = AttentionSection.ATTENTION,
        requiredSetEmpty = false,
        effectiveDueOn = LocalDate.parse("2026-05-30"),
        computedDueMeter = null,
        currentMeter = null,
        meterUnit = null,
        lastCompletedOn = null,
        completionMode = CompletionMode.QUICK,
        remindersEnabled = true,
        membersRequired = null,
        membersComplete = null,
        snoozedUntil = snoozedUntil,
        rank = 0,
    )
}
