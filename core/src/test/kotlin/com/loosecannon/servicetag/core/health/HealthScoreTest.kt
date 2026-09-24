package com.loosecannon.servicetag.core.health

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec §6.3's score and bands, in exact integer arithmetic (inv. 117). */
class HealthScoreTest {

    /** The two templates' values, used here only as thresholds (they are never defaults, Q-1). */
    private val engineService = Triple(14, 45, 120)
    private val waterCare = Triple(2, 7, 14)

    private fun score(x: Long, t: Triple<Int, Int, Int>) = HealthScore.score(x, t.first, t.second, t.third)

    @Test
    fun warningStartsExactlyAtT2AndCriticalExactlyAtT3() {
        for (t in listOf(engineService, waterCare)) {
            val (t1, t2, t3) = t
            assertEquals(100, score(t1.toLong(), t), "$t: t1 is still 100")
            assertEquals(HealthBand.NOMINAL, HealthScore.band(score(t2 - 1L, t)), "$t: the day before t2 is NOMINAL")
            assertEquals(60, score(t2.toLong(), t), "$t: t2 is exactly 60")
            assertEquals(HealthBand.WARNING, HealthScore.band(score(t2.toLong(), t)), "$t: WARNING starts on t2")
            assertEquals(HealthBand.WARNING, HealthScore.band(score(t3 - 1L, t)), "$t: the day before t3 is WARNING")
            assertEquals(25, score(t3.toLong(), t), "$t: t3 is exactly 25")
            assertEquals(HealthBand.CRITICAL, HealthScore.band(score(t3.toLong(), t)), "$t: CRITICAL starts on t3")
            assertEquals(0, score(t3 + (t3 - t2).toLong(), t), "$t: 0 at t3 + (t3 - t2)")
            assertEquals(0, score(t3 + (t3 - t2) + 1000L, t), "$t: and beyond")
        }
        assertEquals(HealthBand.NOMINAL, HealthScore.band(61))
        assertEquals(HealthBand.WARNING, HealthScore.band(60))
        assertEquals(HealthBand.WARNING, HealthScore.band(26))
        assertEquals(HealthBand.CRITICAL, HealthScore.band(25))
        assertEquals(HealthBand.CRITICAL, HealthScore.band(0))
    }

    @Test
    fun theScoreNeverRisesAsXGrows() {
        val random = Random(11_723)
        repeat(400) {
            val t1 = random.nextInt(0, 400)
            val t2 = t1 + random.nextInt(1, 400)
            val t3 = t2 + random.nextInt(1, 400)
            var previous = HealthScore.score(-5, t1, t2, t3)
            assertEquals(100, previous, "($t1, $t2, $t3): a negative x scores 100")
            for (x in -4L..(t3 + 2L * (t3 - t2) + 3)) {
                val current = HealthScore.score(x, t1, t2, t3)
                assertTrue(current <= previous, "($t1, $t2, $t3): the score rose from $previous to $current at x = $x")
                assertTrue(current in 0..100, "($t1, $t2, $t3): $current at x = $x")
                previous = current
            }
            assertEquals(60, HealthScore.score(t2.toLong(), t1, t2, t3), "($t1, $t2, $t3) at t2")
            assertEquals(25, HealthScore.score(t3.toLong(), t1, t2, t3), "($t1, $t2, $t3) at t3")
        }
    }

    @Test
    fun theWorkedScoresAreExact() {
        // §7.2, water care: x 3, 5, 7, 14.
        assertEquals(listOf(92, 76, 60, 25), listOf(3L, 5L, 7L, 14L).map { score(it, waterCare) })
        // §7.3, the snowblower: x 29, 45, 80, 120, 195.
        assertEquals(listOf(81, 60, 44, 25, 0), listOf(29L, 45L, 80L, 120L, 195L).map { score(it, engineService) })
        // §7.4, the generator: x 96 on 24 Sep, and the H.3c branch's 164 on 1 Dec.
        assertEquals(listOf(37, 11), listOf(96L, 164L).map { score(it, engineService) })
        // §12.4, F2 and F1: battery age 1233 and 1576 days on 365 / 1095 / 1460.
        assertEquals(listOf(47, 18), listOf(1233L, 1576L).map { HealthScore.score(it, 365, 1095, 1460) })
    }
}
