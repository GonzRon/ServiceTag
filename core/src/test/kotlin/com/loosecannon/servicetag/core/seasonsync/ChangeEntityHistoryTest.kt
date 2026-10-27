package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncCommandsHarness.Companion.HEATER
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncHarness.Companion.answerOff
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncHarness.Companion.answerOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * #129 (constraint 2; row k4; hazard Z2) — **a pin, characterisation, no RED expected**: the shipped
 * [EditSeasonSyncEntity] is what Change helper runs. The owner's case, fictional: the heater was linked to the wrong
 * helper, whose ON applied a START on 20 Oct; the change points the link at the right helper, keeps that row and the
 * provenance, drops a late answer about the old helper, and the new helper's first read decides as any read does.
 */
class ChangeEntityHistoryTest {
    private val heater = AssetId(HEATER)

    @Test
    fun aChangeReadsTheNewHelperFreshAndKeepsTheHistory() = runTest {
        val c = SeasonSyncCommandsHarness(today = "2026-10-20")
        c.h.asset()
        c.connected()
        c.linked(entityId = OLD)
        assertIs<SeasonSyncRecorded.Recorded>(c.h.result(answerOn("2026-10-20T06:00:00+00:00")))
        c.h.result(HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null))
        val stored = c.h.binding()
        val first = c.h.rows().single()
        assertEquals(SeasonAction.START to "2026-10-20", first.action to first.occurredOn, "row 1")
        assertEquals(LastAppliedSource.HOME_ASSISTANT, stored.lastAppliedSource)
        assertEquals(HaSwitchState.ON, stored.observedState)
        assertEquals(SyncErrorKind.UNREACHABLE, stored.errorKind)
        val mark = c.scheduler.calls.size
        val commits = c.uow.commits

        val changed = c.edit.run(heater, NEW)

        assertEquals(
            stored.copy(
                entityId = NEW, observedState = null, observedChangedAt = null, lastSuccessAt = null,
                errorKind = null, errorDetail = null, errorAt = null, revision = stored.revision + 1,
                updatedAt = c.h.now,
            ),
            changed,
            "the observation, the error and the fetch time cleared; the mode and the provenance kept; revision +1",
        )
        assertEquals(listOf(first), c.h.rows(), "the change itself writes no season row")
        assertEquals(listOf("read $HEATER" to commits + 1), c.scheduler.calls.drop(mark), "one fresh read, after the commit")

        assertEquals(
            SeasonSyncRecorded.Dropped,
            c.h.result(answerOff(), readRevision = stored.revision),
            "a late answer about the old helper, at the old revision, is dropped",
        )
        assertEquals(changed, c.h.binding(), "nothing written")

        c.h.on("2026-10-27")
        assertIs<SeasonSyncRecorded.Recorded>(c.h.result(answerOff("2026-10-26T22:00:00+00:00")))

        assertEquals(
            listOf(SeasonAction.START to "2026-10-20", SeasonAction.END to "2026-10-27"),
            c.h.dated(),
            "the new helper's off: one END dated the day it is applied",
        )
        assertEquals(first, c.h.rows().first(), "row 1 kept")
        assertEquals(NEW, c.h.binding().entityId)
        assertEquals(LastAppliedSource.HOME_ASSISTANT, c.h.binding().lastAppliedSource)
    }

    @Test
    fun aStoppedBindingsChangeAsksNoRead() = runTest {
        val c = SeasonSyncCommandsHarness(today = "2026-10-20")
        c.h.asset()
        c.connected()
        c.linked(entityId = OLD)
        c.stop.run(heater)
        val rows = c.h.dated()
        val mark = c.scheduler.calls.size

        val changed = c.edit.run(heater, NEW)

        assertEquals(NEW to false, changed.entityId to changed.enabled, "stored, still stopped")
        assertEquals(emptyList(), c.scheduler.calls.drop(mark), "nothing is read until Resume")
        assertEquals(rows, c.h.dated())
    }

    private companion object {
        const val OLD = "input_boolean.example_heater_in_season"
        const val NEW = "input_boolean.example_heater_season"
    }
}
