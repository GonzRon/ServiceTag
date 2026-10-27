package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncCommandsHarness.Companion.HEATER
import com.loosecannon.servicetag.core.testing.RiggedFailure
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.scheduleSeasonActivationOf
import com.loosecannon.servicetag.core.testing.scheduleSeasonBindingOf
import com.loosecannon.servicetag.core.transfer.TransferPackTesting
import com.loosecannon.servicetag.core.usecase.ActivationCommand
import com.loosecannon.servicetag.core.usecase.RecordSeasonActivation
import com.loosecannon.servicetag.core.usecase.SeasonSyncOwnsSeason
import com.loosecannon.servicetag.core.usecase.readSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * #129 (L-C1; rows k1–k3; hazards Z1, Z3) — Remove link: one asset's binding deleted in one write and nothing else
 * written; after the commit the work is ensured while another binding is enabled, else cancelled. Today is
 * 2026-06-10. Every asset, helper, schedule and address is fictional.
 */
class UnlinkSeasonSyncTest {
    private val heater = AssetId(HEATER)
    private val other = AssetId(OTHER)

    /** The heater MANUAL and out of season (START 1 Mar, END 20 May), connected and linked in FOLLOW. */
    private suspend fun linkedHeater(): SeasonSyncCommandsHarness = SeasonSyncCommandsHarness().apply {
        h.asset()
        h.activation("act-1", SeasonAction.START, "2026-03-01")
        h.activation("act-2", SeasonAction.END, "2026-05-20")
        val done = completionOf("e-heat", occurredOn = "2026-04-10", occurrenceOn = "2026-04-01", assetId = HEATER)
        h.raw.events.rows[done.id.value] = done
        connected()
        linked()
    }

    /** [linkedHeater], a second linked asset in season, and an `OWN` schedule following its own helper (#125). */
    private suspend fun twoLinkedAndASchedule(): SeasonSyncCommandsHarness = linkedHeater().apply {
        h.asset(OTHER)
        h.activation("act-3", SeasonAction.START, "2026-04-01", assetId = OTHER)
        linked(OTHER, "input_boolean.example_cooler_in_season")
        val schedule = scheduleOf(id = SCHEDULE, assetId = OTHER, title = "Example opening service")
        h.raw.schedules.rows[schedule.id.value] = schedule
        val connectionId = connections.rows.values.single().id
        h.raw.scheduleSeasonBindings.insert(scheduleSeasonBindingOf(SCHEDULE, connectionId = connectionId))
        h.raw.scheduleSeasonActivations.insert(
            scheduleSeasonActivationOf("ssa-1", SCHEDULE, SeasonAction.START, "2026-04-02"),
        )
    }

    private fun SeasonSyncCommandsHarness.unlink(bindings: SeasonSyncRepository = h.bindings) =
        UnlinkSeasonSync(bindings, scheduler, uow)

    /** The owner's own Start and End, behind the season guard, as the asset screen runs them. */
    private fun SeasonSyncCommandsHarness.ownersRecord() = RecordSeasonActivation(
        h.assets, h.events, h.activations, uow, h.ids, h.clock, h.todayPort, h.recompute,
        SeasonSyncGuard(h.raw.seasonSyncBindings),
    )

    /** Everything an unlink of the heater must leave: what travels, every other binding, the connection, the token. */
    private suspend fun SeasonSyncCommandsHarness.everythingButTheHeatersBinding(): List<Any> = listOf(
        uow.read { readSnapshot(TransferPackTesting.repositoriesOf(h.raw)) },
        h.raw.seasonSyncBindings.rows.filterKeys { it != HEATER },
        LinkedHashMap(h.raw.scheduleSeasonBindings.rows),
        LinkedHashMap(h.raw.scheduleSeasonActivations.rows),
        LinkedHashMap(h.raw.activations.rows),
        LinkedHashMap(h.raw.events.rows),
        LinkedHashMap(h.raw.assets.rows),
        LinkedHashMap(connections.rows),
        secrets.keys(),
        secrets.puts.toList(),
    )

    /** The scheduler's calls from here on, each with the commit count it saw. */
    private fun SeasonSyncCommandsHarness.callsFrom(mark: Int): List<Pair<String, Int>> = scheduler.calls.drop(mark)

    // ---- row k1: one binding, nothing else (Z1, constraint 3) ----

    @Test
    fun unlinkingOneAssetDeletesItsBindingAndNothingElse() = runTest {
        val c = twoLinkedAndASchedule()
        assertFailsWith<SeasonSyncOwnsSeason>("linked: the owner's Start is the link's") {
            c.ownersRecord().run(heater, ActivationCommand(SeasonAction.START))
        }
        val stored = c.h.binding()
        val before = c.everythingButTheHeatersBinding()

        val removed = c.unlink().run(heater)

        assertEquals(stored, removed, "the removed binding is answered")
        assertNull(c.h.raw.seasonSyncBindings.rows[HEATER], "the heater's binding is gone")
        assertEquals(
            before, c.everythingButTheHeatersBinding(),
            "the other binding, the schedule binding and its rows, the connection, the token, the asset, its season " +
                "rows and its events are unchanged",
        )
        assertNotNull(c.h.raw.seasonSyncBindings.rows[OTHER], "not a delete by connection")
        assertEquals(SeasonMode.MANUAL, c.h.raw.assets.rows.getValue(HEATER).seasonMode, "the asset stays MANUAL")
        assertEquals(SeasonPhase.OUT_OF_SEASON, c.phase(), "at its phase: no row closes the link")
        assertFalse(SeasonSyncGuard(c.h.raw.seasonSyncBindings).isSynced(heater), "the season guard is off")

        val started = c.ownersRecord().run(heater, ActivationCommand(SeasonAction.START))

        assertEquals(SeasonAction.START to "2026-06-10", started.action to started.occurredOn, "the owner's Start returns")
        assertEquals(SeasonPhase.IN_SEASON, c.phase())
    }

    // ---- row k2: the work after the commit (Z3) ----

    @Test
    fun theLastEnabledBindingRemovedCancelsTheWorkOnce() = runTest {
        val c = linkedHeater()
        val mark = c.scheduler.calls.size
        val commits = c.uow.commits

        c.unlink().run(heater)

        assertEquals(listOf("cancel" to commits + 1), c.callsFrom(mark), "one cancel, after the commit")
    }

    @Test
    fun anotherEnabledBindingEnsuresTheWorkOnce() = runTest {
        val c = twoLinkedAndASchedule()
        val mark = c.scheduler.calls.size
        val commits = c.uow.commits

        c.unlink().run(heater)

        assertEquals(listOf("ensure" to commits + 1), c.callsFrom(mark), "one ensure, after the commit")
    }

    @Test
    fun aStoppedBindingRemovedFollowsTheRuleByAnyEnabled() = runTest {
        val c = twoLinkedAndASchedule()
        c.stop.run(heater)
        var mark = c.scheduler.calls.size
        var commits = c.uow.commits

        assertEquals(false, c.unlink().run(heater)?.enabled, "the stopped binding is removed")

        assertEquals(listOf("ensure" to commits + 1), c.callsFrom(mark), "the other is enabled")

        c.stop.run(other)
        mark = c.scheduler.calls.size
        commits = c.uow.commits

        c.unlink().run(other)

        assertEquals(listOf("cancel" to commits + 1), c.callsFrom(mark), "no binding is enabled")
        assertEquals(emptyMap(), c.h.raw.seasonSyncBindings.rows)
    }

    @Test
    fun noBindingAnswersNullWritesNothingAndCallsNoScheduler() = runTest {
        val c = linkedHeater()
        c.h.asset(OTHER)
        val before = c.everythingButTheHeatersBinding() + c.h.binding()
        val mark = c.scheduler.calls.size

        assertNull(c.unlink().run(other), "no binding")

        assertEquals(before, c.everythingButTheHeatersBinding() + c.h.binding())
        assertEquals(emptyList(), c.callsFrom(mark), "no scheduler call on the no-op")

        c.unlink().run(heater)
        val after = c.everythingButTheHeatersBinding() + c.h.raw.seasonSyncBindings.rows.size
        val again = c.scheduler.calls.size

        assertNull(c.unlink().run(heater), "a second run: idempotent")

        assertEquals(after, c.everythingButTheHeatersBinding() + c.h.raw.seasonSyncBindings.rows.size)
        assertEquals(emptyList(), c.callsFrom(again))
    }

    // ---- row k3: the write rolls back as one, and the scheduler waits for the commit ----

    @Test
    fun aFailingWriteLeavesTheBindingAndCallsNoScheduler() = runTest {
        val c = twoLinkedAndASchedule()
        val failing = object : SeasonSyncRepository by c.h.raw.seasonSyncBindings {
            override suspend fun anyEnabled(): Boolean = throw RiggedFailure("a later read in the write failed")
        }
        val rollbacks = c.uow.rollbacks

        c.refusesWithNothingWritten<RiggedFailure>("the write fails after its delete") {
            c.unlink(failing).run(heater)
        }

        assertEquals(rollbacks + 1, c.uow.rollbacks, "the delete was inside the write, and rolled back with it")
        assertNotNull(c.h.raw.seasonSyncBindings.rows[HEATER], "the binding is back")
    }

    private companion object {
        const val OTHER = "other"
        const val SCHEDULE = "s-own"
    }
}
