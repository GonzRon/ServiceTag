package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncHarness.Companion.HEATER
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncHarness.Companion.answerOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

/**
 * #129 (L-C3; row k5; hazard Z2) — the asset applier's entity guard, #125's I1 applied to #16: a read that names the
 * entity it asked about is dropped when the stored binding now follows another, even at the same revision (a Remove
 * link and a relink both start at revision 1). A read that names none keeps the revision-only check, so every shipped
 * caller and [RecordSeasonSyncResultTest] stay as they were. Today is 2026-06-10; the helpers are fictional.
 */
class RecordSeasonSyncEntityGuardTest {
    private val heater = AssetId(HEATER)

    /** A MANUAL heater, out of season, linked at revision 1 to [STORED]. */
    private suspend fun linked(): SeasonSyncHarness = SeasonSyncHarness().apply {
        asset()
        link { it.copy(entityId = STORED) }
    }

    private suspend fun SeasonSyncHarness.read(readEntityId: String?): SeasonSyncRecorded =
        applier.run(heater, binding().revision, now - 20_000L, now - 10_000L, answerOn(), readEntityId = readEntityId)

    @Test
    fun aReadAboutAnotherEntityAtTheSameRevisionIsDroppedAndWritesNothing() = runTest {
        val h = linked()
        val stored = h.binding()

        assertEquals(SeasonSyncRecorded.Dropped, h.read(readEntityId = OLD))

        assertEquals(stored, h.binding(), "the binding untouched: no status, no revision")
        assertEquals(emptyList(), h.dated(), "no season row")
        assertEquals(0, h.bindings.updates)
    }

    @Test
    fun aReadAboutTheStoredEntityIsRecorded() = runTest {
        val h = linked()

        val written = assertIs<SeasonSyncRecorded.Recorded>(h.read(readEntityId = STORED))

        assertEquals(listOf(SeasonAction.START to "2026-06-10"), h.dated())
        assertEquals(2, written.binding.revision)
        assertEquals(HaSwitchState.ON, written.binding.observedState)
    }

    @Test
    fun aReadNamingNoEntityKeepsTheRevisionOnlyCheck() = runTest {
        val h = linked()

        assertIs<SeasonSyncRecorded.Recorded>(h.read(readEntityId = null))
        assertEquals(listOf(SeasonAction.START to "2026-06-10"), h.dated())

        val stale = h.applier.run(heater, readRevision = 1, h.now - 20_000L, h.now - 10_000L, answerOn(), readEntityId = null)
        assertEquals(SeasonSyncRecorded.Dropped, stale, "the revision still guards a read that names no entity")
    }

    private companion object {
        const val STORED = "input_boolean.example_heater_season"
        const val OLD = "input_boolean.example_heater_in_season"
    }
}
