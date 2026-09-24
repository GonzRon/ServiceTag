package com.loosecannon.servicetag.core.health

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.HealthFixtures.SPEC_DAY
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.dayMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec §6.5: the four aggregations over the subjects with a value (inv. 118, 119). */
class HealthAggregationTest {

    /**
     * One AGE subject per score, each counting from its own quick action so the subjects never share
     * a baseline. The thresholds are this test's; the replacement date is found so that the subject
     * scores exactly [score] on the spec's date.
     */
    private class Member(val id: String, val score: Int?, weight: Int = 1, sortOrder: Int, archived: Boolean = false) {
        val subject: HealthSubject = HealthFixtures.subjectOf(
            id = id, assetId = "agg", driver = HealthDriver.AGE, baselineProfileId = "p-$id",
            nominalUntil = 0, warningFrom = 40, criticalFrom = 75, weight = weight, sortOrder = sortOrder,
            archivedAt = if (archived) dayMillis("2026-09-01") else null,
        )

        /** No event for a null [score]: that subject has no baseline and is NOT TRACKED. */
        val events: List<AssetEvent> = listOfNotNull(
            score?.let {
                val x = (0L..200L).first { x -> HealthScore.score(x, 0, 40, 75) == it }
                HealthFixtures.eventOf("e-$id", "agg", EventKind.REPLACEMENT, "Replaced", SPEC_DAY.minusDays(x).toString(), "p-$id")
            },
        )
    }

    private var order = 0
    private fun member(id: String, score: Int?, weight: Int = 1, archived: Boolean = false) = Member(id, score, weight, order++, archived)

    private fun health(aggregation: HealthAggregation, members: List<Member>, primary: String? = null): AssetHealthResult {
        val asset = SeasonFixtures.assetOf(id = "agg", name = "Array", mode = SeasonMode.YEAR_ROUND)
            .copy(healthAggregation = aggregation, healthPrimarySubjectId = primary?.let(::HealthSubjectId))
        return HealthFixtures.healthOn(SPEC_DAY.toString(), asset, members.map { it.subject }, events = members.flatMap { it.events })
    }

    private fun scored(score: Int) = SubjectValue.Scored(score, HealthScore.band(score), null)

    @Test
    fun eachAggregation() {
        val members = listOf(member("m1", 90, weight = 1), member("m2", 61, weight = 1), member("m3", 40, weight = 2))
        assertEquals(listOf(90, 61, 40), health(HealthAggregation.WORST, members).subjects.map { (it.value as SubjectValue.Scored).score })

        assertEquals(scored(40), health(HealthAggregation.WORST, members).aggregate, "WORST: the minimum")
        assertEquals(scored(61), health(HealthAggregation.TRACK_ONE, members, primary = "m2").aggregate, "TRACK_ONE: the primary's")
        assertEquals(scored(63), health(HealthAggregation.AVERAGE, members).aggregate, "AVERAGE: floor(191 / 3)")
        assertEquals(scored(57), health(HealthAggregation.WEIGHTED, members).aggregate, "WEIGHTED: floor(231 / 4)")

        // A floor never rounds an aggregate up into a better band.
        val edge = listOf(member("e1", 61), member("e2", 60))
        assertEquals(scored(60), health(HealthAggregation.AVERAGE, edge).aggregate, "floor(60.5) is WARNING")
        assertEquals(HealthBand.WARNING, health(HealthAggregation.AVERAGE, edge).aggregate?.band)
        val weightedEdge = listOf(member("w1", 61, weight = 3), member("w2", 60, weight = 1))
        assertEquals(scored(60), health(HealthAggregation.WEIGHTED, weightedEdge).aggregate, "floor(243 / 4)")
        assertFalse(health(HealthAggregation.AVERAGE, members).fallback)
    }

    @Test
    fun untrackedSubjectsAreExcludedNeverCountedAs100() {
        val members = listOf(member("m1", 40), member("m2", null), member("m3", 50, archived = true))
        for (aggregation in listOf(HealthAggregation.WORST, HealthAggregation.AVERAGE, HealthAggregation.WEIGHTED)) {
            val result = health(aggregation, members)
            assertEquals(scored(40), result.aggregate, "$aggregation: only the tracked, non-archived subject counts")
        }
        val result = health(HealthAggregation.AVERAGE, members)
        assertEquals(listOf("m1", "m2"), result.subjects.map { it.subject.id.value }, "the archived subject is not reported")
        assertEquals(SubjectValue.NotTracked(NotTrackedReason.NO_REPLACEMENT), result.subjects[1].value, "shown as NOT TRACKED")
    }

    @Test
    fun aMissingPrimaryFallsBackToWorstWithTheFlag() {
        val members = listOf(member("m1", 90), member("m2", 45), member("m3", 70, archived = true))
        for (primary in listOf(null, "gone", "m3")) {
            val result = health(HealthAggregation.TRACK_ONE, members, primary)
            assertEquals(scored(45), result.aggregate, "primary $primary: WORST")
            assertTrue(result.fallback, "primary $primary: S138")
        }
        assertFalse(health(HealthAggregation.TRACK_ONE, members, "m1").fallback, "a live primary does not fall back")
        assertEquals(scored(90), health(HealthAggregation.TRACK_ONE, members, "m1").aggregate)
    }

    @Test
    fun anUntrackedPrimaryIsNotTracked() {
        val members = listOf(member("m1", 90), member("m2", null))
        val result = health(HealthAggregation.TRACK_ONE, members, primary = "m2")
        assertNull(result.aggregate, "the primary exists and has no value: NOT TRACKED, no fallback")
        assertFalse(result.fallback)
    }

    @Test
    fun noContributorIsNotTracked() {
        val untracked = listOf(member("m1", null), member("m2", 30, archived = true))
        for (aggregation in HealthAggregation.entries) {
            val result = health(aggregation, untracked, primary = "m1")
            assertNull(result.aggregate, "$aggregation over no contributor")
            assertEquals(emptyList(), result.critical)
            assertNull(health(aggregation, emptyList()).aggregate, "$aggregation over no subject")
        }
        // A missing or archived primary with nothing for WORST to show: NOT TRACKED, and no S138,
        // because there is no worst subject to show (controller ruling on B05's concern 2).
        for (primary in listOf(null, "gone", "m2")) {
            for (members in listOf(untracked, emptyList())) {
                val result = health(HealthAggregation.TRACK_ONE, members, primary)
                assertNull(result.aggregate, "primary $primary over ${members.size} subjects")
                assertFalse(result.fallback, "primary $primary over ${members.size} subjects: nothing is shown, so no fallback")
            }
        }
    }

    /** #61 AC 23: nothing hides behind an aggregate. */
    @Test
    fun everyCriticalContributorIsListedWhateverTheAggregation() {
        val members = listOf(member("m1", 100), member("m2", 18), member("m3", 100), member("m4", 100), member("m5", 25), member("m6", 10, archived = true))
        val average = health(HealthAggregation.AVERAGE, members)
        assertEquals(scored(68), average.aggregate, "AVERAGE NOMINAL: floor(343 / 5)")
        assertEquals(HealthBand.NOMINAL, average.aggregate?.band)
        for (aggregation in HealthAggregation.entries) {
            val result = health(aggregation, members, primary = "m1")
            assertEquals(listOf("m2", "m5"), result.critical.map { it.subject.id.value }, "$aggregation")
        }
    }
}
