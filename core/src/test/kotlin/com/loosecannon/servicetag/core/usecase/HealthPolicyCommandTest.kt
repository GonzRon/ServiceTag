package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubjectId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * `SetHealthPolicy` and the primary's protection (spec §6.5; master plan §10.1): TRACK_ONE follows a
 * non-archived subject of the same asset and nothing else, and the subject it follows cannot be
 * archived until the aggregation changes (S137).
 */
class HealthPolicyCommandTest {

    private val h = ConditionHealthHarness(today = "2026-09-24")

    private suspend fun refused(cmd: HealthPolicyCommand): List<HealthProblem> =
        assertFailsWith<HealthValidation> { h.setHealthPolicy.run(AssetId("a1"), cmd) }.problems

    /**
     * Hazard: TRACK_ONE follows something it cannot. With no primary, another asset's subject, an
     * archived one or a missing id it is `HEALTH_PRIMARY_INVALID`; so is a primary sent with another
     * aggregation. A valid change writes the asset row once; an unchanged policy writes nothing.
     */
    @Test
    fun trackOneNeedsANonArchivedPrimaryOfThisAsset() = runBlocking<Unit> {
        val before = h.asset("a1")
        h.asset("a2", name = "Battery pack")
        h.subject("h-live")
        h.subject("h-old", archivedAt = 1L)
        h.subject("h-pack", assetId = "a2")

        val invalid = listOf(HealthProblem.PrimaryInvalid)
        assertEquals(invalid, refused(HealthPolicyCommand(HealthAggregation.TRACK_ONE)))
        for (id in listOf("h-pack", "h-old", "h-gone")) {
            assertEquals(invalid, refused(HealthPolicyCommand(HealthAggregation.TRACK_ONE, HealthSubjectId(id))), id)
        }
        for (aggregation in listOf(HealthAggregation.WORST, HealthAggregation.AVERAGE, HealthAggregation.WEIGHTED)) {
            assertEquals(invalid, refused(HealthPolicyCommand(aggregation, HealthSubjectId("h-live"))), "$aggregation")
        }
        assertEquals(before, h.stored("a1"), "a refusal writes nothing")
        assertEquals(0, h.assets.upserts)

        val live = HealthPolicyCommand(HealthAggregation.TRACK_ONE, HealthSubjectId("h-live"))
        val tracked = h.setHealthPolicy.run(AssetId("a1"), live)
        val expected = before.copy(
            healthAggregation = HealthAggregation.TRACK_ONE, healthPrimarySubjectId = HealthSubjectId("h-live"),
            updatedAt = h.now,
        )
        assertEquals(expected, tracked)
        assertEquals(expected, h.stored("a1"))
        assertEquals(1, h.assets.upserts)

        h.setHealthPolicy.run(AssetId("a1"), live)
        assertEquals(1, h.assets.upserts, "an unchanged policy writes nothing")

        val averaged = h.setHealthPolicy.run(AssetId("a1"), HealthPolicyCommand(HealthAggregation.AVERAGE))
        assertEquals(HealthAggregation.AVERAGE to null, averaged.healthAggregation to averaged.healthPrimarySubjectId)
        assertFailsWith<NoSuchAsset> { h.setHealthPolicy.run(AssetId("a9"), HealthPolicyCommand(HealthAggregation.WORST)) }
    }

    /**
     * Hazard: the subject TRACK_ONE follows is archived from under it. Archiving it is 409
     * `HEALTH_SUBJECT_IS_PRIMARY` and writes nothing; once the aggregation changes it archives. Any
     * other subject of the asset archives freely.
     */
    @Test
    fun archivingThePrimaryIs409() = runBlocking<Unit> {
        h.asset("a1")
        val primary = h.subject("h-primary")
        h.subject("h-other", sortOrder = 1)
        h.setHealthPolicy.run(AssetId("a1"), HealthPolicyCommand(HealthAggregation.TRACK_ONE, primary.id))

        val refusal = assertFailsWith<HealthSubjectIsPrimary> { h.archiveHealthSubject.run(primary.id, archived = true) }
        assertEquals(primary.id, refusal.subjectId)
        assertEquals(primary, h.storedSubject("h-primary"), "nothing written")

        assertNotNull(h.archiveHealthSubject.run(HealthSubjectId("h-other"), archived = true).archivedAt)

        h.setHealthPolicy.run(AssetId("a1"), HealthPolicyCommand(HealthAggregation.WORST))
        assertEquals(h.now, h.archiveHealthSubject.run(primary.id, archived = true).archivedAt)
        assertFailsWith<NoSuchHealthSubject> { h.archiveHealthSubject.run(HealthSubjectId("h-gone"), archived = true) }
    }
}
