package com.loosecannon.servicetag.core.health

import com.loosecannon.servicetag.core.health.HealthSubjectShapeProblem.THRESHOLDS_INVALID
import com.loosecannon.servicetag.core.health.HealthSubjectShapeProblem.WEIGHT_OUT_OF_RANGE
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.HealthFixtures.SPEC_DAY
import com.loosecannon.servicetag.core.testing.dayMillis
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The screen a read model calls before the engine (controller ruling on B05's concern 3): spec
 * §6.1's bounds, and the engine refusing exactly the subjects the screen reports.
 */
class HealthSubjectShapeTest {

    private val asset = HealthFixtures.upsAsset().copy(healthAggregation = HealthAggregation.WEIGHTED)

    private fun subject(t1: Int, t2: Int, t3: Int, weight: Int = 1, withBaseline: Boolean = true): HealthSubject =
        HealthFixtures.subjectOf(
            id = "h-shape", assetId = "ups", driver = HealthDriver.AGE,
            baselineProfileId = if (withBaseline) null else "p-none",
            nominalUntil = t1, warningFrom = t2, criticalFrom = t3, weight = weight,
        )

    private fun engineRefuses(subject: HealthSubject): Boolean = runCatching {
        HealthFixtures.healthOn(SPEC_DAY.toString(), asset, listOf(subject), events = listOf(HealthFixtures.upsBatteryReplaced()))
    }.exceptionOrNull()?.let { it is IllegalArgumentException || it is ArithmeticException } == true

    @Test
    fun theShapeIsTheCommandsBoundsAndTheEngineAgrees() {
        val cases = listOf(
            Triple(Triple(0, 1, 2), 1, emptySet()),
            Triple(Triple(14, 45, 120), 10, emptySet()),
            Triple(Triple(0, 1, 36_500), 1, emptySet()),
            Triple(Triple(-1, 1, 2), 1, setOf(THRESHOLDS_INVALID)),
            Triple(Triple(5, 5, 6), 1, setOf(THRESHOLDS_INVALID)),
            Triple(Triple(1, 3, 3), 1, setOf(THRESHOLDS_INVALID)),
            Triple(Triple(3, 2, 4), 1, setOf(THRESHOLDS_INVALID)),
            Triple(Triple(0, 1, 36_501), 1, setOf(THRESHOLDS_INVALID)),
            Triple(Triple(0, 1, 2), 0, setOf(WEIGHT_OUT_OF_RANGE)),
            Triple(Triple(0, 1, 2), 11, setOf(WEIGHT_OUT_OF_RANGE)),
            Triple(Triple(0, 1, 2), -1, setOf(WEIGHT_OUT_OF_RANGE)),
            Triple(Triple(4, 4, 4), 0, setOf(THRESHOLDS_INVALID, WEIGHT_OUT_OF_RANGE)),
        )
        for ((thresholds, weight, expected) in cases) {
            val (t1, t2, t3) = thresholds
            for (withBaseline in listOf(true, false)) {
                val label = "($t1, $t2, $t3) weight $weight, ${if (withBaseline) "scored" else "not tracked"}"
                val shaped = subject(t1, t2, t3, weight, withBaseline)
                assertEquals(expected, HealthSubjectShape.problems(shaped), label)
                assertEquals(expected.isNotEmpty(), engineRefuses(shaped), "$label: the engine refuses exactly what the screen reports")
            }
        }

        // The engine judges the subjects it reads: an archived malformed row is not one of them.
        val archived = subject(5, 5, 6, weight = 0).copy(archivedAt = dayMillis("2026-09-01"))
        assertEquals(false, engineRefuses(archived))
    }
}
