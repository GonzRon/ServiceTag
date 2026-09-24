package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.testing.groupOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * `SaveHealthSubject` and `ArchiveHealthSubject` (spec §6.1; master plan §10.1): every refusal of
 * rule 4 by its code, no default threshold (inv. 121, Q-1), the whole link re-checked on create,
 * update and restore (plan decision 45), one non-archived subject per schedule and no change of asset
 * (inv. 120). The UPS is F1 and the battery pack F2.
 */
class HealthSubjectCommandTest {

    private val h = ConditionHealthHarness(today = "2026-09-24")

    private fun overdue(
        scheduleId: String = "s1",
        name: String = "Battery test",
        t1: Int? = 14,
        t2: Int? = 45,
        t3: Int? = 120,
        weight: Int = 1,
        baselineProfileId: String? = null,
    ) = HealthSubjectCommand(
        name = name, kind = HealthSubjectKind.PART, driver = HealthDriver.MAINTENANCE_OVERDUE,
        scheduleId = ScheduleId(scheduleId), baselineProfileId = baselineProfileId?.let(::ProfileId),
        nominalUntilDays = t1, warningFromDays = t2, criticalFromDays = t3, weight = weight,
    )

    private fun age(
        name: String = "Battery age",
        baselineProfileId: String? = null,
        scheduleId: String? = null,
        t1: Int? = 365,
        t2: Int? = 1095,
        t3: Int? = 1460,
        weight: Int = 1,
    ) = HealthSubjectCommand(
        name = name, kind = HealthSubjectKind.PART, driver = HealthDriver.AGE, scheduleId = scheduleId?.let(::ScheduleId),
        baselineProfileId = baselineProfileId?.let(::ProfileId), nominalUntilDays = t1, warningFromDays = t2,
        criticalFromDays = t3, weight = weight,
    )

    private suspend fun refused(cmd: HealthSubjectCommand, assetId: String = "a1"): List<HealthProblem> =
        assertFailsWith<HealthValidation> { h.saveHealthSubject.create(AssetId(assetId), cmd) }.problems

    private fun setUp() {
        h.asset("a1")
        h.asset("a2", name = "Battery pack")
        h.schedule("s1")
    }

    /** `HEALTH_SUBJECT_NAME_REQUIRED`, carrying the 1–60 limit its message states (plan decision 33). */
    @Test
    fun aBlankOrOverLongNameIsNameRequired() = runBlocking<Unit> {
        setUp()
        for (name in listOf("", "   ", "n".repeat(61), " " + "n".repeat(61) + " ")) {
            assertEquals(listOf(HealthProblem.NameRequired(1..60)), refused(age(name = name)), "\"$name\"")
        }
        assertTrue(assertFailsWith<HealthValidation> { h.saveHealthSubject.create(AssetId("a1"), age(name = "")) }
            .message!!.contains("1..60"), "the message states the limit")
        val sixty = h.saveHealthSubject.create(AssetId("a1"), age(name = "  " + "n".repeat(60) + "  "))
        assertEquals("n".repeat(60), sixty.name, "trimmed, and 60 is allowed")
    }

    /**
     * Inv. 121, Q-1: a threshold has no default. Each one missing is `HEALTH_THRESHOLDS_INVALID`, as is
     * any triple outside `0 ≤ t1 < t2 < t3 ≤ 36,500`; nothing is stored for any of them.
     */
    @Test
    fun thresholdsAreRequiredWithNoDefault() = runBlocking<Unit> {
        setUp()
        val missing = listOf(
            overdue(t1 = null), overdue(t2 = null), overdue(t3 = null), overdue(t1 = null, t2 = null, t3 = null),
        )
        val badOrder = listOf(
            overdue(t1 = 5, t2 = 5, t3 = 6), overdue(t1 = -1, t2 = 1, t3 = 2), overdue(t1 = 3, t2 = 2, t3 = 1),
            overdue(t1 = 1, t2 = 2, t3 = 36_501),
        )
        for (cmd in missing + badOrder) {
            assertEquals(listOf(HealthProblem.ThresholdsInvalid(0..36_500)), refused(cmd), "$cmd")
        }
        assertTrue(h.healthSubjects.rows.isEmpty(), "no refused subject was stored")

        val edges = h.saveHealthSubject.create(AssetId("a1"), overdue(t1 = 0, t2 = 1, t3 = 36_500))
        assertEquals(Triple(0, 1, 36_500), Triple(edges.nominalUntilDays, edges.warningFromDays, edges.criticalFromDays))
    }

    /** `HEALTH_DRIVER_MISMATCH`: AGE with a schedule; MAINTENANCE_OVERDUE without one, or with a baseline. */
    @Test
    fun aDriverAndItsLinkMustAgree() = runBlocking<Unit> {
        setUp()
        h.profile("p-replace", EventKind.REPLACEMENT)
        assertEquals(listOf(HealthProblem.DriverMismatch), refused(age(scheduleId = "s1")))
        assertEquals(listOf(HealthProblem.DriverMismatch), refused(overdue().copy(scheduleId = null)))
        assertEquals(listOf(HealthProblem.DriverMismatch), refused(overdue(baselineProfileId = "p-replace")))
    }

    /** `FOREIGN_SCHEDULE`: absent, another asset's, or a group's. */
    @Test
    fun aForeignScheduleIsRefused() = runBlocking<Unit> {
        setUp()
        h.schedule("s-pack", assetId = "a2")
        h.groups.rows["g1"] = groupOf("g1")
        h.schedules.rows["s-group"] = h.schedule("s-group").copy(target = ScheduleTarget.GroupTarget(GroupId("g1")))
        for (id in listOf("s-gone", "s-pack", "s-group")) {
            assertEquals(listOf(HealthProblem.ForeignSchedule(ScheduleId(id))), refused(overdue(scheduleId = id)), id)
        }
    }

    /** `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`: a meter-only schedule drives no health (Q-2). */
    @Test
    fun aMeterOnlyScheduleNeedsATimeRule() = runBlocking<Unit> {
        setUp()
        h.meter("d-hours")
        h.schedule("s-hours", timeRule = false, meterDefinitionId = "d-hours")
        assertEquals(
            listOf(HealthProblem.ScheduleNeedsATimeRule(ScheduleId("s-hours"))), refused(overdue(scheduleId = "s-hours")),
        )
    }

    /** `PROFILE_NOT_A_REPLACEMENT`: another asset's, a missing one, or a quick action that is not a REPLACEMENT. */
    @Test
    fun aBaselineThatIsNotThisAssetsReplacementIsRefused() = runBlocking<Unit> {
        setUp()
        h.profile("p-own", EventKind.REPLACEMENT)
        h.profile("p-pack", EventKind.REPLACEMENT, assetId = "a2")
        h.profile("p-test", EventKind.INSPECTION, name = "Load test")
        for (id in listOf("p-pack", "p-gone", "p-test")) {
            assertEquals(listOf(HealthProblem.ProfileNotAReplacement(ProfileId(id))), refused(age(baselineProfileId = id)), id)
        }
        val own = h.saveHealthSubject.create(AssetId("a1"), age(baselineProfileId = "p-own"))
        assertEquals(ProfileId("p-own"), own.baselineProfileId)
    }

    /** `HEALTH_WEIGHT_OUT_OF_RANGE`, carrying the 1–10 limit. Every problem is collected, in spec §6.1's order. */
    @Test
    fun aWeightOutsideOneToTenIsRefusedAndEveryProblemIsCollected() = runBlocking<Unit> {
        setUp()
        assertEquals(listOf(HealthProblem.WeightOutOfRange(1..10)), refused(age(weight = 0)))
        assertEquals(listOf(HealthProblem.WeightOutOfRange(1..10)), refused(age(weight = 11)))
        assertEquals(10, h.saveHealthSubject.create(AssetId("a1"), age(weight = 10)).weight)
        assertEquals(
            listOf(
                HealthProblem.NameRequired(), HealthProblem.ThresholdsInvalid(), HealthProblem.DriverMismatch,
                HealthProblem.WeightOutOfRange(),
            ),
            refused(age(name = " ", t2 = null, scheduleId = "s1", weight = 12)),
        )
        assertFailsWith<NoSuchAsset> { h.saveHealthSubject.create(AssetId("a9"), age()) }
    }

    /**
     * The controller's ruling on I8 (plan decision 45): an archived schedule cannot be linked, on
     * create or on update, and the refusal is `FOREIGN_SCHEDULE` saying "archived".
     */
    @Test
    fun anArchivedScheduleCannotBeLinked() = runBlocking<Unit> {
        setUp()
        h.schedule("s-old", status = ScheduleStatus.ARCHIVED)
        val expected = listOf(HealthProblem.ForeignSchedule(ScheduleId("s-old"), archived = true))

        val onCreate = assertFailsWith<HealthValidation> {
            h.saveHealthSubject.create(AssetId("a1"), overdue(scheduleId = "s-old"))
        }
        assertEquals(expected, onCreate.problems)
        assertTrue(onCreate.message!!.contains("archived=true"), onCreate.message)

        val live = h.saveHealthSubject.create(AssetId("a1"), overdue(scheduleId = "s1"))
        val onUpdate = assertFailsWith<HealthValidation> { h.saveHealthSubject.update(live.id, overdue(scheduleId = "s-old")) }
        assertEquals(expected, onUpdate.problems)
        assertEquals(live, h.storedSubject(live.id.value), "the refused edit wrote nothing")
    }

    /**
     * Plan decision 45: restoring re-runs the whole link. Archiving a driving schedule with the flag
     * archives its subject; restoring that subject is then refused, as is restoring one whose schedule
     * was retargeted or lost its time rule meanwhile.
     */
    @Test
    fun restoreRechecksTheWholeLink() = runBlocking<Unit> {
        setUp()
        h.meter("d-hours")
        val subject = h.saveHealthSubject.create(AssetId("a1"), overdue(scheduleId = "s1"))
        h.archiveSchedule.run(ScheduleId("s1"), archived = true, unlinkHealthSubject = true)
        assertNotNull(h.storedSubject(subject.id.value).archivedAt, "the flag archived the subject")

        val archived = h.storedSubject(subject.id.value)
        val refusal = assertFailsWith<HealthValidation> { h.archiveHealthSubject.run(subject.id, archived = false) }
        assertEquals(listOf(HealthProblem.ForeignSchedule(ScheduleId("s1"), archived = true)), refusal.problems)
        assertEquals(archived, h.storedSubject(subject.id.value), "nothing written")

        val s2 = h.schedule("s2")
        val retargeted = h.saveHealthSubject.create(AssetId("a1"), overdue(scheduleId = "s2", name = "Load test"))
        h.archiveHealthSubject.run(retargeted.id, archived = true)
        h.saveSchedule.run(s2.id, h.commandOf(s2).copy(targetAssetId = AssetId("a2")))
        assertEquals(
            listOf(HealthProblem.ForeignSchedule(ScheduleId("s2"))),
            assertFailsWith<HealthValidation> { h.archiveHealthSubject.run(retargeted.id, archived = false) }.problems,
        )

        val s3 = h.schedule("s3")
        val deRuled = h.saveHealthSubject.create(AssetId("a1"), overdue(scheduleId = "s3", name = "Runtime test"))
        h.archiveHealthSubject.run(deRuled.id, archived = true)
        h.saveSchedule.run(
            s3.id,
            h.commandOf(s3).copy(
                timeInterval = null, timeUnit = null, anchorOn = null,
                meterDefinitionId = DefinitionId("d-hours"), meterInterval = 250.0,
            ),
        )
        assertEquals(
            listOf(HealthProblem.ScheduleNeedsATimeRule(ScheduleId("s3"))),
            assertFailsWith<HealthValidation> { h.archiveHealthSubject.run(deRuled.id, archived = false) }.problems,
        )

        val s4 = h.schedule("s4")
        val intact = h.saveHealthSubject.create(AssetId("a1"), overdue(scheduleId = s4.id.value, name = "Self test"))
        h.archiveHealthSubject.run(intact.id, archived = true)
        assertNull(h.archiveHealthSubject.run(intact.id, archived = false).archivedAt, "an intact link restores")
    }

    /**
     * Inv. 120: a schedule drives at most one non-archived subject — on create, on edit and on
     * restore — and an archived subject holds nothing. The subject being edited does not hold its own
     * schedule against itself.
     */
    @Test
    fun aScheduleDrivesAtMostOneNonArchivedSubject() = runBlocking<Unit> {
        setUp()
        val first = h.saveHealthSubject.create(AssetId("a1"), overdue(scheduleId = "s1", name = "Battery test"))
        assertEquals(0, first.sortOrder)

        val taken = assertFailsWith<HealthScheduleTaken> { h.saveHealthSubject.create(AssetId("a1"), overdue(name = "Twin")) }
        assertEquals(ScheduleId("s1") to first.id, taken.scheduleId to taken.heldBy)

        val other = h.saveHealthSubject.create(AssetId("a1"), age(name = "Battery age"))
        assertEquals(1, other.sortOrder, "a create appends")
        assertFailsWith<HealthScheduleTaken> { h.saveHealthSubject.update(other.id, overdue(name = "Battery age")) }
        assertEquals(other, h.storedSubject(other.id.value))

        val renamed = h.saveHealthSubject.update(first.id, overdue(name = "Battery test, renamed"))
        assertEquals("Battery test, renamed", renamed.name, "a subject does not hold its own schedule against itself")

        h.archiveHealthSubject.run(first.id, archived = true)
        val successor = h.saveHealthSubject.create(AssetId("a1"), overdue(name = "Successor"))
        assertEquals(ScheduleId("s1"), successor.scheduleId, "an archived subject holds nothing")

        val restore = assertFailsWith<HealthScheduleTaken> { h.archiveHealthSubject.run(first.id, archived = false) }
        assertEquals(successor.id, restore.heldBy)
        assertNotNull(h.storedSubject(first.id.value).archivedAt)
    }

    /**
     * Inv. 120: a subject never changes asset. An edit is validated against the stored asset, so a
     * schedule or a baseline of another asset is refused rather than carrying the subject across.
     */
    @Test
    fun anEditKeepsTheAsset() = runBlocking<Unit> {
        setUp()
        h.schedule("s-pack", assetId = "a2")
        h.profile("p-pack", EventKind.REPLACEMENT, assetId = "a2")
        val subject = h.saveHealthSubject.create(AssetId("a1"), age())

        assertEquals(
            listOf(HealthProblem.ForeignSchedule(ScheduleId("s-pack"))),
            assertFailsWith<HealthValidation> {
                h.saveHealthSubject.update(subject.id, overdue(scheduleId = "s-pack"))
            }.problems,
        )
        assertEquals(
            listOf(HealthProblem.ProfileNotAReplacement(ProfileId("p-pack"))),
            assertFailsWith<HealthValidation> {
                h.saveHealthSubject.update(subject.id, age(baselineProfileId = "p-pack"))
            }.problems,
        )
        val renamed = h.saveHealthSubject.update(subject.id, age(name = "Battery age, pack two"))
        assertEquals(AssetId("a1"), renamed.assetId)
        assertEquals(AssetId("a1"), h.storedSubject(subject.id.value).assetId)
        assertFailsWith<NoSuchHealthSubject> { h.saveHealthSubject.update(HealthSubjectId("h-gone"), age()) }
    }
}
