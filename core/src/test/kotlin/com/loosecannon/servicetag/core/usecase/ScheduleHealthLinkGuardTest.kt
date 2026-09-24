package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.groupOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The schedule-side link guard (spec §6.1, D-30, RS-3; master plan §10.1; inv. 130): no schedule edit
 * or archive strands the subject it drives unless the request says to archive that subject too, and
 * then both happen in one transaction — or neither, when the subject is the TRACK_ONE primary (plan
 * decision 14). The generator is F3.
 */
class ScheduleHealthLinkGuardTest {

    private val h = ConditionHealthHarness(today = "2026-09-24")

    private fun setUp() {
        h.asset("a1", name = "Generator")
        h.asset("a2", name = "Battery pack")
        h.meter("d-hours")
        h.groups.rows["g1"] = groupOf("g1", members = listOf(Triple("a1", "2026-01-01", null)))
    }

    /** The edits that would strand a subject: no time rule, another asset, a group. */
    private fun strandingEdits(id: String): List<Pair<String, ScheduleCommand>> {
        val cmd = h.commandOf(h.storedSchedule(id))
        return listOf(
            "meter only" to cmd.copy(
                timeInterval = null, timeUnit = null, anchorOn = null,
                meterDefinitionId = DefinitionId("d-hours"), meterInterval = 250.0,
            ),
            "another asset" to cmd.copy(targetAssetId = AssetId("a2")),
            "a group" to cmd.copy(targetAssetId = null, targetGroupId = GroupId("g1")),
        )
    }

    /**
     * Hazard: an edit silently strands a subject. Removing the time rule, moving the target to another
     * asset or to a group, and archiving are each 422 `SCHEDULE_DRIVES_HEALTH_SUBJECT` naming the
     * subject, and each writes nothing.
     */
    @Test
    fun removingTheTimeRuleRetargetingOrArchivingIsRefused() = runBlocking<Unit> {
        setUp()
        val schedule = h.schedule("s1")
        val subject = h.subject("h1", scheduleId = "s1", name = "Service interval")

        for ((what, cmd) in strandingEdits("s1")) {
            val refusal = assertFailsWith<ScheduleDrivesHealthSubject>(what) { h.saveSchedule.run(schedule.id, cmd) }
            assertEquals(subject.id to "Service interval", refusal.subjectId to refusal.name, what)
        }
        val archive = assertFailsWith<ScheduleDrivesHealthSubject> { h.archiveSchedule.run(schedule.id, archived = true) }
        assertEquals(subject.id, archive.subjectId)

        assertEquals(schedule, h.storedSchedule("s1"), "no schedule write")
        assertEquals(subject, h.storedSubject("h1"), "no subject write")
    }

    /**
     * Hazard: the flag's two writes come apart. With `unlinkHealthSubject` the archive and each
     * stranding edit go through and archive the subject; when a later step of the same write fails,
     * the schedule and the subject are both left exactly as they were.
     */
    @Test
    fun theFlagArchivesTheSubjectInTheSameTransaction() = runBlocking<Unit> {
        setUp()
        val schedule = h.schedule("s1")
        val subject = h.subject("h1", scheduleId = "s1")

        h.states.failOnUpsert = 1    // the recompute, the write's last step
        assertFailsWith<RiggedFailure> { h.archiveSchedule.run(schedule.id, archived = true, unlinkHealthSubject = true) }
        assertEquals(schedule, h.storedSchedule("s1"), "the schedule rolled back")
        assertEquals(subject, h.storedSubject("h1"), "and the subject with it")
        h.states.failOnUpsert = 2
        assertFailsWith<RiggedFailure> {
            h.saveSchedule.run(schedule.id, strandingEdits("s1").first().second, unlinkHealthSubject = true)
        }
        assertEquals(schedule, h.storedSchedule("s1"))
        assertEquals(subject, h.storedSubject("h1"))
        h.states.failOnUpsert = null

        val archived = h.archiveSchedule.run(schedule.id, archived = true, unlinkHealthSubject = true)
        assertEquals(ScheduleStatus.ARCHIVED, archived.status)
        assertEquals(subject.copy(archivedAt = h.now, updatedAt = h.now), h.storedSubject("h1"))

        for ((i, edit) in strandingEdits("s1").withIndex()) {
            val id = "s-edit-$i"
            h.schedule(id)
            h.subject("h-edit-$i", scheduleId = id)
            h.now += 1_000L
            val saved = h.saveSchedule.run(ScheduleId(id), edit.second.copy(title = "Edited $i"), unlinkHealthSubject = true)
            assertEquals("Edited $i", saved.title, edit.first)
            assertEquals(h.now, h.storedSubject("h-edit-$i").archivedAt, edit.first)
        }
        assertEquals(ScheduleTarget.GroupTarget(GroupId("g1")), h.storedSchedule("s-edit-2").target)
    }

    /**
     * Plan decision 14: when the subject the flag would archive is the one its asset's TRACK_ONE
     * follows, the whole write is 409 `HEALTH_SUBJECT_IS_PRIMARY`, and nothing is written.
     */
    @Test
    fun theFlagOnThePrimaryIs409() = runBlocking<Unit> {
        setUp()
        val schedule = h.schedule("s1")
        val subject = h.subject("h1", scheduleId = "s1")
        h.setHealthPolicy.run(AssetId("a1"), HealthPolicyCommand(HealthAggregation.TRACK_ONE, subject.id))

        val onArchive = assertFailsWith<HealthSubjectIsPrimary> {
            h.archiveSchedule.run(schedule.id, archived = true, unlinkHealthSubject = true)
        }
        assertEquals(subject.id, onArchive.subjectId)
        for ((what, cmd) in strandingEdits("s1")) {
            assertFailsWith<HealthSubjectIsPrimary>(what) { h.saveSchedule.run(schedule.id, cmd, unlinkHealthSubject = true) }
        }
        assertEquals(schedule, h.storedSchedule("s1"))
        assertEquals(subject, h.storedSubject("h1"))
    }

    /**
     * B06-F3: the refusal order with `PreServiceNeedsDates`. Retargeting a driving PRE_SERVICE schedule
     * onto an asset with no season and no break is two refusals at once. The guard's 422 comes first,
     * because its remedy is in this body; with the flag it is the 409, whose remedy is the other asset.
     * Neither writes anything, and the subject is still live.
     */
    @Test
    fun theGuardsRefusalComesBeforePreServiceNeedsDates() = runBlocking<Unit> {
        setUp()
        h.asset("a1", name = "Generator", breakStart = "06-01", breakEnd = "06-30")
        val schedule = h.schedule("s1", policy = ServicePolicy.PRE_SERVICE, offset = -14)
        val subject = h.subject("h1", scheduleId = "s1")
        val onto = h.commandOf(schedule).copy(targetAssetId = AssetId("a2"))

        assertFailsWith<ScheduleDrivesHealthSubject> { h.saveSchedule.run(schedule.id, onto) }
        assertFailsWith<PreServiceNeedsDates> {
            h.saveSchedule.run(schedule.id, onto, unlinkHealthSubject = true)
        }
        assertEquals(schedule, h.storedSchedule("s1"))
        assertEquals(subject, h.storedSubject("h1"), "still live")
    }

    /**
     * The flag with nothing to unlink changes nothing: a schedule no subject drives, and one whose only
     * subject is already archived, archive as they always did, and no subject row is touched.
     */
    @Test
    fun theFlagWithNothingToUnlinkIsANoOp() = runBlocking<Unit> {
        setUp()
        h.schedule("s-free")
        h.schedule("s-old")
        val old = h.subject("h-old", scheduleId = "s-old", archivedAt = 5L)
        val subjects = h.healthSubjects.rows.toMap()

        for (id in listOf("s-free", "s-old")) {
            val archived = h.archiveSchedule.run(ScheduleId(id), archived = true, unlinkHealthSubject = true)
            assertEquals(ScheduleStatus.ARCHIVED, archived.status, id)
        }
        val retargeted = h.saveSchedule.run(
            ScheduleId("s-old"), h.commandOf(h.storedSchedule("s-old")).copy(targetAssetId = AssetId("a2")),
            unlinkHealthSubject = true,
        )
        assertEquals(ScheduleTarget.AssetTarget(AssetId("a2")), retargeted.target)
        assertEquals(subjects, h.healthSubjects.rows.toMap())
        assertEquals(5L, h.storedSubject(old.id.value).archivedAt)
    }

    /**
     * Hazard: the guard refuses what it should not. A title, a lead, a policy or a new cadence that
     * keeps a time rule leaves the subject's link intact, so none is guarded; neither is restoring an
     * archived schedule, and the subject is not touched by any of them.
     */
    @Test
    fun aTitleOrPolicyEditIsNotGuarded() = runBlocking<Unit> {
        setUp()
        val schedule = h.schedule("s1")
        val subject = h.subject("h1", scheduleId = "s1")
        val cmd = h.commandOf(schedule)

        h.saveSchedule.run(schedule.id, cmd.copy(title = "Oil and filter"))
        h.saveSchedule.run(schedule.id, cmd.copy(leadDays = 21))
        h.saveSchedule.run(schedule.id, cmd.copy(servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 0))
        val recut = h.saveSchedule.run(schedule.id, cmd.copy(timeInterval = 3, anchorOn = "2026-02-01"))
        assertEquals(3, recut.timeInterval)

        h.schedule("s-archived", status = ScheduleStatus.ARCHIVED)
        h.subject("h-archived", scheduleId = "s-archived", archivedAt = 5L, sortOrder = 1)
        assertEquals(ScheduleStatus.ACTIVE, h.archiveSchedule.run(ScheduleId("s-archived"), archived = false).status)

        assertEquals(subject, h.storedSubject("h1"))
        assertEquals(5L, h.storedSubject("h-archived").archivedAt, "restoring a schedule restores no subject")
        assertEquals(HealthSubjectId("h1"), h.healthSubjects.forSchedule(ScheduleId("s1")).single().id)
    }
}
