package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonAction.END
import com.loosecannon.servicetag.core.model.SeasonAction.START
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncHarness.Companion.HEATER
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncHarness.Companion.answerOff
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncHarness.Companion.answerOn
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.HealthFixtures
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.transfer.TransferPackTesting
import com.loosecannon.servicetag.core.usecase.AcceptSeasonOffer
import com.loosecannon.servicetag.core.usecase.ActivationCommand
import com.loosecannon.servicetag.core.usecase.ApplyTemplate
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.AssetSettingsCommand
import com.loosecannon.servicetag.core.usecase.BreakCommand
import com.loosecannon.servicetag.core.usecase.BreakStrandsPolicy
import com.loosecannon.servicetag.core.usecase.HealthPolicyCommand
import com.loosecannon.servicetag.core.usecase.LegacyWriteCannotRepresent
import com.loosecannon.servicetag.core.usecase.PromoteCategory
import com.loosecannon.servicetag.core.usecase.RecordSeasonActivation
import com.loosecannon.servicetag.core.usecase.SaveAssetSettings
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeStrandsPolicy
import com.loosecannon.servicetag.core.usecase.SeasonProblem
import com.loosecannon.servicetag.core.usecase.SeasonSyncOwnsSeason
import com.loosecannon.servicetag.core.usecase.SeasonValidation
import com.loosecannon.servicetag.core.usecase.SetSeasonMode
import com.loosecannon.servicetag.core.usecase.UpdateAsset
import com.loosecannon.servicetag.core.usecase.readSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #16 (B3b; C15, R16-1; rows 33–36) — one season authority. While an asset's binding is enabled, in any mode, every
 * other writer of a season fact answers [SeasonSyncOwnsSeason] after its 404 and 422 and writes nothing; a stopped
 * binding refuses nothing; a no-op stays a no-op; the legacy pair keeps its shipped 422; and the merge stays
 * unguarded, the next run correcting what it brought once. Today is 2026-06-10; the heater is MANUAL and in season
 * since 03-01.
 */
class SeasonSyncAuthorityTest {
    private val heater = AssetId(HEATER)

    /** The four guarded writers and the unguarded legacy pair over the harness's stores, as `AppGraph` wires them. */
    private class Writers(h: SeasonSyncHarness) {
        val guard = SeasonSyncGuard(h.raw.seasonSyncBindings)
        val record = RecordSeasonActivation(
            h.assets, h.events, h.activations, h.raw.uow, h.ids, h.clock, h.todayPort, h.recompute, guard,
        )
        val accept = AcceptSeasonOffer(h.activations, record, h.raw.uow, h.todayPort, guard)
        val setMode = SetSeasonMode(
            h.assets, h.schedules, h.activations, h.raw.uow, h.ids, h.clock, h.todayPort, h.recompute, guard,
        )
        private val promote = PromoteCategory(h.raw.categories)
        val save = SaveAssetSettings(
            h.assets, h.schedules, h.raw.subjects, h.activations, h.raw.uow, h.ids, h.clock, h.todayPort, h.recompute,
            ApplyTemplate(h.raw.definitions, h.raw.profiles, h.assets, h.raw.uow, h.ids, h.clock), promote, guard,
        )
        val update = UpdateAsset(h.assets, h.schedules, h.raw.uow, h.clock, h.recompute, promote)
    }

    /** The heater in season since 03-01, linked in [mode]; [enabled] false is a stopped binding. */
    private suspend fun synced(mode: SyncMode = SyncMode.FOLLOW, enabled: Boolean = true) = SeasonSyncHarness().also {
        it.asset()
        it.activation("a-start", START, "2026-03-01")
        it.link(mode = mode) { binding -> binding.copy(enabled = enabled) }
    }

    private fun settings(
        mode: SeasonModeCommand,
        name: String = "Example Heater",
        pause: BreakCommand = BreakCommand(null, null),
    ) = AssetSettingsCommand(
        asset = AssetCommand(name = name),
        seasonMode = mode,
        maintenanceBreak = pause,
        healthPolicy = HealthPolicyCommand(HealthAggregation.WORST),
    )

    private fun SeasonSyncHarness.journalEntry(id: String, kind: EventKind) =
        HealthFixtures.eventOf(id, HEATER, kind, "Example season note", "2026-06-10").also { raw.events.rows[id] = it }

    private fun rowOf(id: String, assetId: String, action: SeasonAction, on: String): SeasonActivation =
        SeasonFixtures.activationOf(id, assetId, action, on, dayMillis(on))

    /** Runs [refused], which must answer [SeasonSyncOwnsSeason] for the heater, and proves nothing at all moved. */
    private suspend fun assertRefusedWithNothingWritten(h: SeasonSyncHarness, what: String, refused: suspend () -> Unit) {
        val repos = TransferPackTesting.repositoriesOf(h.raw)
        val before = h.raw.uow.read { readSnapshot(repos) }
        val binding = h.binding()
        val commits = h.raw.uow.commits

        val e = assertFailsWith<SeasonSyncOwnsSeason>(what) { refused() }

        assertEquals(heater, e.assetId, what)
        assertEquals(before, h.raw.uow.read { readSnapshot(repos) }, "$what: nothing that travels moved")
        assertEquals(binding, h.binding(), "$what: the binding is untouched")
        assertEquals(commits, h.raw.uow.commits, "$what: no write committed")
    }

    /** Merges a backup holding the assets and rows stored here plus [rows], recorded on another phone. Unguarded. */
    private suspend fun SeasonSyncHarness.mergeIn(vararg rows: SeasonActivation) {
        val source = BackupInstall()
        source.assets.rows.putAll(raw.assets.rows)
        source.activations.rows.putAll(raw.activations.rows)
        rows.forEach { source.activations.rows[it.id] = it }
        val plan = raw.build.run(source.export.run().data)
        assertTrue(plan.applicable, "the merge applies: ${plan.report()}")
        raw.apply.run(plan)
    }

    // ---- row 33: every guarded writer is refused while the binding is enabled; a stopped one refuses nothing ----

    @Test
    fun recordSeasonActivationIsRefusedInEveryModeFirstAmongThe409s() = runTest {
        for (mode in SyncMode.entries) {
            val h = synced(mode)
            val w = Writers(h)
            assertRefusedWithNothingWritten(h, "an END under $mode") { w.record.run(heater, ActivationCommand(END)) }
            // A START would be SeasonAlreadyStarted: the guard is the first 409.
            assertRefusedWithNothingWritten(h, "a START under $mode") { w.record.run(heater, ActivationCommand(START)) }
        }
    }

    @Test
    fun acceptSeasonOfferIsRefusedWithNothingWritten() = runTest {
        val h = synced()
        val end = h.journalEntry("e-end", EventKind.SEASON_END)
        assertRefusedWithNothingWritten(h, "the journal's offer") { Writers(h).accept.run(heater, end, END) }
    }

    @Test
    fun setSeasonModeChangeIsRefusedWithNothingWritten() = runTest {
        val h = synced()
        val w = Writers(h)
        assertRefusedWithNothingWritten(h, "to CALENDAR") {
            w.setMode.run(heater, SeasonModeCommand(SeasonMode.CALENDAR, "11-01", "03-31"))
        }
        assertRefusedWithNothingWritten(h, "to YEAR_ROUND") { w.setMode.run(heater, SeasonModeCommand(SeasonMode.YEAR_ROUND)) }
    }

    @Test
    fun saveAssetSettingsModeChangeIsRefusedWithNothingWritten() = runTest {
        val h = synced()
        val w = Writers(h)
        assertRefusedWithNothingWritten(h, "a save to YEAR_ROUND") {
            w.save.run(heater, settings(SeasonModeCommand(SeasonMode.YEAR_ROUND)))
        }
        assertRefusedWithNothingWritten(h, "a rename that also moves to CALENDAR") {
            w.save.run(heater, settings(SeasonModeCommand(SeasonMode.CALENDAR, "11-01", "03-31"), name = "Example Heater 2"))
        }
    }

    /**
     * The review's MINOR-1: before the strands 409. A break gives the MANUAL heater a BREAK boundary its PRE_SERVICE
     * schedule counts back from; CALENDAR re-kinds it and YEAR_ROUND without the break removes it. Stopped, the same
     * two commands answer the shipped strands 409s, so the fixture does strand.
     */
    @Test
    fun aModeChangeAndASaveThatWouldStrandAScheduleAreRefusedFirstByTheGuard() = runTest {
        val h = SeasonSyncHarness()
        h.asset { it.copy(blackoutStartMmdd = "01-10", blackoutEndMmdd = "01-20") }
        h.activation("a-start", START, "2026-03-01")
        h.raw.schedules.rows["s-heater"] = SeasonFixtures.snowblowerSchedule(id = "s-heater", assetId = HEATER)
        h.link()
        val w = Writers(h)
        val toCalendar = suspend { w.setMode.run(heater, SeasonModeCommand(SeasonMode.CALENDAR, "11-01", "03-31")) }
        val toYearRound = suspend { w.save.run(heater, settings(SeasonModeCommand(SeasonMode.YEAR_ROUND))) }

        assertRefusedWithNothingWritten(h, "a stranding mode change") { toCalendar() }
        assertRefusedWithNothingWritten(h, "a stranding save to YEAR_ROUND") { toYearRound() }

        h.raw.seasonSyncBindings.rows[HEATER] = h.binding().copy(enabled = false)
        assertFailsWith<SeasonModeStrandsPolicy> { toCalendar() }
        assertFailsWith<BreakStrandsPolicy> { toYearRound() }
    }

    @Test
    fun aStoppedBindingRefusesNothing() = runTest {
        val h = synced(enabled = false)
        val w = Writers(h)

        w.record.run(heater, ActivationCommand(END))
        w.accept.run(heater, h.journalEntry("e-start", EventKind.SEASON_START), START)
        assertEquals(listOf(START to "2026-03-01", END to "2026-06-10", START to "2026-06-10"), h.dated())
        w.setMode.run(heater, SeasonModeCommand(SeasonMode.CALENDAR, "11-01", "03-31"))
        assertEquals(SeasonMode.CALENDAR, h.raw.assets.rows.getValue(HEATER).seasonMode)
        w.save.run(heater, settings(SeasonModeCommand(SeasonMode.MANUAL, manualPhase = SeasonPhase.OUT_OF_SEASON)))
        assertEquals(SeasonMode.MANUAL, h.raw.assets.rows.getValue(HEATER).seasonMode)
        assertEquals(END to "2026-06-10", h.dated().last(), "the switch into MANUAL wrote its row")
        assertFalse(w.guard.isSynced(heater), "stopped, it owns nothing")
    }

    @Test
    fun theLegacyPairOnASyncedAssetStaysTheShipped422WithNothingWritten() = runTest {
        val h = synced()
        val repos = TransferPackTesting.repositoriesOf(h.raw)
        val before = h.raw.uow.read { readSnapshot(repos) }

        assertFailsWith<LegacyWriteCannotRepresent> {
            Writers(h).update.run(heater, AssetCommand(name = "Example Heater", seasonStartMmdd = "11-01", seasonEndMmdd = "03-31"))
        }

        assertEquals(before, h.raw.uow.read { readSnapshot(repos) })
    }

    // ---- row 34: a no-op stays a no-op ----

    @Test
    fun anEqualSetSeasonModeAndARenameSaveOnASyncedAssetSucceed() = runTest {
        val h = synced()
        val w = Writers(h)
        val stored = h.raw.assets.rows.getValue(HEATER)

        assertEquals(stored, w.setMode.run(heater, SeasonModeCommand(SeasonMode.MANUAL)), "an equal command writes nothing")
        val renamed = w.save.run(heater, settings(SeasonModeCommand(SeasonMode.MANUAL), name = "Example Heater 2"))
        val paused = w.save.run(
            heater,
            settings(SeasonModeCommand(SeasonMode.MANUAL), name = "Example Heater 2", pause = BreakCommand("01-10", "01-20")),
        )

        assertEquals("Example Heater 2", renamed.name)
        assertEquals("01-10", paused.blackoutStartMmdd, "a break is not a season fact the binding owns")
        assertEquals(SeasonMode.MANUAL, paused.seasonMode)
        assertEquals(listOf(START to "2026-03-01"), h.dated(), "no season row")
    }

    // ---- row 35: the 422 comes first ----

    @Test
    fun aBadDateOnASyncedAssetIs422() = runTest {
        val h = synced()
        val w = Writers(h)

        val bad = assertFailsWith<SeasonValidation> { w.record.run(heater, ActivationCommand(END, occurredOn = "06/01/2026")) }
        assertIs<SeasonProblem.BadDate>(bad.problems.single())
        val ahead = assertFailsWith<SeasonValidation> { w.record.run(heater, ActivationCommand(END, occurredOn = "2026-06-11")) }
        assertEquals(listOf<SeasonProblem>(SeasonProblem.SeasonDateOutOfRange), ahead.problems)
        assertFailsWith<SeasonValidation> { w.setMode.run(heater, SeasonModeCommand(SeasonMode.CALENDAR, "11-01", null)) }
        assertFailsWith<SeasonValidation> {
            w.save.run(heater, settings(SeasonModeCommand(SeasonMode.CALENDAR, "13-01", "03-31")))
        }
        assertEquals(listOf(START to "2026-03-01"), h.dated())
    }

    // ---- row 36: the merge stays unguarded (H7); the next run reconciles once ----

    @Test
    fun aMergeInsertingAnEndForASyncedAssetLandsAndTheNextOnCorrectsOnce() = runTest {
        val h = synced()

        h.mergeIn(rowOf("a-merged-end", HEATER, END, "2026-06-01"))
        assertEquals(listOf(START to "2026-03-01", END to "2026-06-01"), h.dated(), "the merge's END lands")

        h.result(answerOn())
        h.result(answerOn())
        assertEquals(
            listOf(START to "2026-03-01", END to "2026-06-01", START to "2026-06-10"),
            h.dated(),
            "the next ON corrects it once",
        )
    }

    /**
     * C-5 and the B3a review: under a Force mode the forced phase is re-asserted on **every** answer — an observation
     * agreeing with the force, one agreeing with what the merge landed, and no decision at all.
     */
    @Test
    fun aMergeThatLandedTheOtherPhaseOnAForcedAssetIsCorrectedOnTheNextRun() = runTest {
        val answers = linkedMapOf(
            "heater-on" to answerOn(),
            "heater-off" to answerOff(),
            "heater-unreachable" to HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null),
        )
        val h = SeasonSyncHarness()
        for (id in answers.keys) {
            h.asset(id)
            h.activation("start-$id", START, "2026-03-01", assetId = id)
            h.link(id, SyncMode.FORCE_IN)
        }

        h.mergeIn(*answers.keys.map { rowOf("end-$it", it, END, "2026-06-01") }.toTypedArray())

        for ((id, answer) in answers) {
            assertEquals(listOf(START to "2026-03-01", END to "2026-06-01"), h.dated(id), "$id: the merge's END lands")
            h.result(answer, assetId = id)
            h.result(answer, assetId = id)
            assertEquals(
                listOf(START to "2026-03-01", END to "2026-06-01", START to "2026-06-10"),
                h.dated(id),
                "$id: the forced phase, once, whatever Home Assistant answered",
            )
        }
    }
}
