package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.TimeBasis
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * #79 (C4, K2): the bytes that must not move.
 *
 * A schedule subject's hash is part of its tag, and the tag is how the shade answers "already
 * showing, in this form?". A field appended for every subject would move every shipped hash, and an
 * upgrade would then re-post every standing maintenance reminder. So the repeat fact is appended
 * **only when it is there**, and a schedule's hash is pinned to the value the pre-#79 code computed.
 */
class ContentHashTest {

    /**
     * The two hex values were computed at #79a's A2 base (805d28c8) from the shipped canonical form:
     * the six fields joined by the unit separator, with the absent marker for each null. One subject
     * carries every field; the other carries every absent marker, so neither half of the form can
     * move unseen.
     */
    @Test
    fun aScheduleSubjectsHashIsTheBaseValue() {
        val rule = RuleFacts(TimeBasis.FIXED, 3, RecurrenceUnit.MONTH, hasMeter = false, seasonal = false)
        assertEquals(
            "d23ba950ef1895c76ec1de1657ceba85eb4d85dc1b51113461ed9c4a75e426c6",
            ContentHash.of("Filter change", "DUE", LocalDate.parse("2026-04-20"), 14, SubjectState.Active, rule),
        )
        assertEquals(
            "73c0b0f2d4a5dfba4d2e8d5c7e84872e87bf60e0ef72d927a54b5fa6f81b867a",
            ContentHash.of("Anode check", "", null, 0, SubjectState.Parked(null), null),
        )
        assertEquals(
            ContentHash.of("Filter change", "DUE", LocalDate.parse("2026-04-20"), 14, SubjectState.Active, rule),
            ContentHash.of("Filter change", "DUE", LocalDate.parse("2026-04-20"), 14, SubjectState.Active, rule, null),
            "an explicit null repeat is the schedule form, byte for byte",
        )
    }

    /** The repeat fact is content: a deadline announced once is not the same thing as one without it. */
    @Test
    fun theRepeatFactMovesADeadlinesHash() {
        val expiry = LocalDate.parse("2031-06-30")
        val without = ContentHash.of("Warranty", "", expiry, 30, SubjectState.Active, null)
        val once = ContentHash.of("Warranty", "", expiry, 30, SubjectState.Active, null, DeadlineRepeat.ONCE)

        assertNotEquals(without, once)
        assertEquals("b934b705123fd8773912363c7508d26cbce128ff02753d5a2992dedefca8cf4e", once)
    }
}
