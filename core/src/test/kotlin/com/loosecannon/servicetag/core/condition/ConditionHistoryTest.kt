package com.loosecannon.servicetag.core.condition

import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.OperationalCondition.DEGRADED
import com.loosecannon.servicetag.core.model.OperationalCondition.DOWN
import com.loosecannon.servicetag.core.model.OperationalCondition.OPERATIONAL
import com.loosecannon.servicetag.core.testing.conditionOf
import com.loosecannon.servicetag.core.testing.dayMillis
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * `ConditionHistory` (spec §5.1; master plan §9; inv. 107, 108): the ordering key, `current`, `since`,
 * and what a backdated correction does to them. Pure, so every case hands rows in a shuffled order:
 * an answer that depended on arrival order could not pass.
 */
class ConditionHistoryTest {

    private fun row(
        id: String,
        condition: OperationalCondition,
        on: String,
        time: String? = null,
        createdAt: Long = dayMillis(on),
        reason: String = "",
    ): AssetCondition = conditionOf(
        id = id, condition = condition, occurredOn = on, occurredTime = time, tzId = "UTC", reason = reason,
        createdAt = createdAt,
    )

    /**
     * Hazard: the wrong row is current. The key is `(occurredOn, occurredTime nulls first, createdAt,
     * id)`: on one day a row with no time sorts **before** a timed one even when it was entered later,
     * and between two rows with the same day and time the one created later wins, whatever their ids.
     */
    @Test
    fun currentIsTheLatestByTheOrderingKey() {
        val timed = row("c-timed", DOWN, "2026-09-20", time = "08:00", createdAt = 100L)
        val untimed = row("c-untimed", OPERATIONAL, "2026-09-20", time = null, createdAt = 200L)
        val nullsFirst = ConditionHistory.of(listOf(timed, untimed))
        assertEquals(listOf(untimed, timed), nullsFirst.ordered, "a null time sorts first")
        assertEquals(timed, nullsFirst.current, "the timed row is the later one that day")

        val earlier = row("z-created-first", OPERATIONAL, "2026-09-21", createdAt = 250L)
        val later = row("a-created-second", DEGRADED, "2026-09-21", createdAt = 300L)
        val tie = ConditionHistory.of(listOf(later, earlier))
        assertEquals(listOf(earlier, later), tie.ordered, "createdAt breaks a same-day tie before id does")
        assertEquals(later, tie.current)

        val sameInstant = listOf(
            row("c-b", DEGRADED, "2026-09-22", createdAt = 400L),
            row("c-a", DOWN, "2026-09-22", createdAt = 400L),
        )
        assertEquals(listOf("c-a", "c-b"), ConditionHistory.of(sameInstant).ordered.map { it.id }, "then the id")
    }

    /**
     * Hazard: "since" moves on every row. It is the first row of the latest run of equal values, so
     * recording DOWN again with a new reason adds a row and keeps "since"; a change starts a new run.
     */
    @Test
    fun sinceIsTheStartOfTheLatestRun() {
        val operational = row("c1", OPERATIONAL, "2026-09-01")
        val down = row("c2", DOWN, "2026-09-10", reason = "Battery failed")
        val downAgain = row("c3", DOWN, "2026-09-12", reason = "Replacement pending")
        val history = ConditionHistory.of(listOf(downAgain, operational, down))

        assertEquals(downAgain, history.current)
        assertEquals(down, history.since, "the run of DOWN started on 10 Sep")

        val back = row("c4", OPERATIONAL, "2026-09-15")
        val after = ConditionHistory.of(listOf(back, downAgain, operational, down))
        assertEquals(back, after.since, "a change starts a new run")
        assertEquals(operational, ConditionHistory.of(listOf(operational)).since, "one row is its own run")
    }

    /**
     * Hazard: a correction rewrites history. A backdated row sorts into place among the others and the
     * rows themselves come back exactly as they went in: nothing is edited to make room.
     */
    @Test
    fun aBackdatedCorrectionSortsIntoPlace() {
        val down = row("c1", DOWN, "2026-09-20", createdAt = dayMillis("2026-09-20"))
        val operational = row("c2", OPERATIONAL, "2026-09-22", createdAt = dayMillis("2026-09-22"))
        val correction = row("c3", DEGRADED, "2026-09-21", createdAt = dayMillis("2026-09-23"))
        val inputs = listOf(correction, operational, down)
        val history = ConditionHistory.of(inputs)

        assertEquals(listOf(down, correction, operational), history.ordered)
        assertEquals(operational, history.current, "entered last, but dated earlier: not current")
        assertEquals(operational, history.since)
        assertEquals(listOf(correction, operational, down), inputs, "the rows handed in are untouched")

        val backdatedDown = row("c4", DOWN, "2026-09-23", createdAt = dayMillis("2026-09-24"))
        val corrected = ConditionHistory.of(inputs + backdatedDown)
        assertEquals(backdatedDown, corrected.current, "a correction dated after the last row is current")
    }

    /** Hazard: an UNKNOWN is invented. No row reads as nothing recorded, never as a value (inv. 108). */
    @Test
    fun noRowIsNotRecorded() {
        val history = ConditionHistory.of(emptyList())

        assertNull(history.current)
        assertNull(history.since)
        assertTrue(history.ordered.isEmpty())
    }
}
