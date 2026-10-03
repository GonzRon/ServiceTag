package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.testing.BackupInstall
import com.loosecannon.servicetag.core.testing.InMemoryScheduleStateRepository
import com.loosecannon.servicetag.core.testing.InMemorySeasonSyncRepository
import com.loosecannon.servicetag.core.testing.SeasonFixtures
import com.loosecannon.servicetag.core.testing.dayMillis
import com.loosecannon.servicetag.core.testing.seasonSyncBindingOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.HeldWriteGuard
import com.loosecannon.servicetag.core.transfer.TransferPackTesting
import com.loosecannon.servicetag.core.usecase.ACTIVATION_ORDER
import com.loosecannon.servicetag.core.usecase.ArchiveAsset
import com.loosecannon.servicetag.core.usecase.PRED
import com.loosecannon.servicetag.core.usecase.RecomputeSchedules
import com.loosecannon.servicetag.core.usecase.RecordSeasonActivation
import com.loosecannon.servicetag.core.usecase.ReplaceHarness
import com.loosecannon.servicetag.core.usecase.WithdrawTransferRecord
import com.loosecannon.servicetag.core.usecase.readSnapshot
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * #16 (B3a) — one installation as `AppGraph` builds it for the applier: [BackupInstall]'s stores behind the
 * held-write guard, the real recompute over them, the shipped season operation and [RecordSeasonSyncResult]. `T` is
 * [today] and the clock stands at [now], both moved by hand; the binding port counts its successful updates.
 */
internal class SeasonSyncHarness(today: String = "2026-06-10") {
    /** The binding table, counting the updates that wrote; [refuse] makes every update answer false, as a lost CAS does. */
    class CountingBindings(private val inner: InMemorySeasonSyncRepository) : SeasonSyncRepository by inner {
        var updates = 0
        var refuse = false
        override suspend fun update(binding: SeasonSyncBinding): Boolean =
            if (refuse) false else inner.update(binding).also { if (it) updates++ }
    }

    val raw = BackupInstall()
    val states = InMemoryScheduleStateRepository()
    private var seq = 0
    val ids = IdGenerator { "id-${++seq}" }
    var now: Long = dayMillis(today) + 50_000L
    val clock = Clock { now }
    var today: LocalDate = LocalDate.parse(today)
    val todayPort = Today { this.today }

    private val guard = HeldWriteGuard(
        raw.transfers, raw.events, raw.definitions, raw.profiles, raw.groups, raw.schedules, raw.serviceCases, raw.links,
        raw.installedComponents,
    )
    val assets = guard.assets(raw.assets)
    val events = guard.events(raw.events)
    val schedules = guard.schedules(raw.schedules)
    val closures = guard.closures(raw.closures)
    val groups = guard.groups(raw.groups)
    val activations = guard.activations(raw.activations)
    val recompute = RecomputeSchedules(
        schedules, states, events, closures, groups, assets, activations, todayPort, clock,
    ) { ZoneOffset.UTC }
    val bindings = CountingBindings(raw.seasonSyncBindings)
    val archive = ArchiveAsset(assets, raw.uow, clock) { recompute.forAsset(it) }

    /** A fresh applier over the same stores — what a recreated process builds. */
    fun applierOf(): RecordSeasonSyncResult = RecordSeasonSyncResult(
        bindings, assets, activations, raw.transfers,
        RecordSeasonActivation(assets, events, activations, raw.uow, ids, clock, todayPort, recompute, SeasonSyncGuard(raw.seasonSyncBindings)),
        raw.uow, clock, todayPort,
    )

    val applier = applierOf()

    /** Moves the day and the clock together. */
    fun on(day: String) {
        today = LocalDate.parse(day)
        now = dayMillis(day) + 50_000L
    }

    /** An asset stored as it is, MANUAL by default. */
    fun asset(id: String = HEATER, mode: SeasonMode = SeasonMode.MANUAL, edit: (Asset) -> Asset = { it }): Asset =
        edit(SeasonFixtures.assetOf(id = id, name = "Example Heater", mode = mode)).also { raw.assets.rows[id] = it }

    /** A history row stored as it is. */
    fun activation(id: String, action: SeasonAction, on: String, assetId: String = HEATER) {
        raw.activations.rows[id] = SeasonFixtures.activationOf(id, assetId, action, on, dayMillis(on))
    }

    /** A binding as a link leaves it, stored as it is. */
    suspend fun link(assetId: String = HEATER, mode: SyncMode = SyncMode.FOLLOW, edit: (SeasonSyncBinding) -> SeasonSyncBinding = { it }) {
        raw.seasonSyncBindings.insert(edit(seasonSyncBindingOf(assetId, mode = mode)))
    }

    fun binding(assetId: String = HEATER): SeasonSyncBinding = raw.seasonSyncBindings.rows.getValue(assetId)

    fun rows(assetId: String = HEATER): List<SeasonActivation> =
        raw.activations.rows.values.filter { it.assetId == AssetId(assetId) }.sortedWith(ACTIVATION_ORDER)

    fun dated(assetId: String = HEATER): List<Pair<SeasonAction, String>> = rows(assetId).map { it.action to it.occurredOn }

    /** One result, read at the binding's current revision unless told otherwise; started and fetched before now. */
    suspend fun result(
        outcome: HaReadOutcome,
        assetId: String = HEATER,
        readRevision: Long = binding(assetId).revision,
        applier: RecordSeasonSyncResult = this.applier,
    ): SeasonSyncRecorded = applier.run(AssetId(assetId), readRevision, now - 20_000L, now - 10_000L, outcome)

    companion object {
        const val HEATER = "heater"
        fun answerOn(lastChanged: String? = "2026-06-09T21:14:03.123456+00:00") = HaReadOutcome.Observed(HaSwitchState.ON, lastChanged)
        fun answerOff(lastChanged: String? = "2026-06-09T21:14:03.123456+00:00") = HaReadOutcome.Observed(HaSwitchState.OFF, lastChanged)
    }
}

/**
 * #16 (B3a; C13, C14; rows 22–31 and 26a) — the applier: one fresh read, one write, at most one season row through
 * the shipped operation's body, and the binding's status beside it. Today is 2026-06-10 unless a case moves it.
 */
class RecordSeasonSyncResultTest {
    private val heater = SeasonSyncHarness.HEATER
    private fun on(lastChanged: String? = "2026-06-09T21:14:03.123456+00:00") = SeasonSyncHarness.answerOn(lastChanged)
    private fun off() = SeasonSyncHarness.answerOff()

    // ---- row 22: apply (AC1, I1) ----

    @Test
    fun onOverOutOfSeasonWritesOneStartDatedToday() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link()

        val written = assertIs<SeasonSyncRecorded.Recorded>(h.result(on("2026-01-02T08:00:00+00:00")))

        assertEquals(listOf(SeasonAction.START to "2026-06-10"), h.dated())
        assertEquals(h.binding(), written.binding)
        assertEquals(SeasonAction.START, written.binding.appliedAction)
        assertEquals("2026-06-10", written.binding.appliedOn)
        assertEquals(h.now, written.binding.appliedAt)
        assertEquals(2, written.binding.revision)
    }

    @Test
    fun offOverInSeasonWritesOneEnd() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.activation("act-1", SeasonAction.START, "2026-03-01")
        h.link()

        h.result(off())

        assertEquals(listOf(SeasonAction.START to "2026-03-01", SeasonAction.END to "2026-06-10"), h.dated())
        assertEquals(SeasonAction.END, h.binding().appliedAction)
    }

    @Test
    fun theRowIsTheShippedOperationsRow() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link()

        h.result(on())

        val row = h.rows().single()
        assertEquals(
            SeasonActivation(
                id = row.id, assetId = AssetId(heater), action = SeasonAction.START, occurredOn = "2026-06-10",
                eventId = null, createdAt = h.now,
            ),
            row,
            "no event, the clock's time: the row Start season writes",
        )
    }

    @Test
    fun haLastChangedNeverDatesTheRow() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.activation("act-1", SeasonAction.END, "2026-04-01")
        h.link()

        h.result(on("2026-05-01T00:00:00+00:00"))

        assertEquals(SeasonAction.START to "2026-06-10", h.dated().last(), "dated the day it is applied (R16-3)")
        assertEquals("2026-05-01T00:00:00+00:00", h.binding().observedChangedAt, "HA's text, kept verbatim")
        assertEquals("2026-06-10", h.binding().appliedOn)
    }

    // ---- row 23: idempotency (AC2, AC4, I2) ----

    @Test
    fun theSameOnTenTimesWritesOneRowAndOneProvenance() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link()
        val firstAt = h.now

        repeat(10) {
            h.result(on())
            h.now += 60_000L
        }

        assertEquals(listOf(SeasonAction.START to "2026-06-10"), h.dated())
        val b = h.binding()
        assertEquals(SeasonAction.START, b.appliedAction)
        assertEquals(firstAt, b.appliedAt, "the provenance is the one application's, not the latest read's")
        assertEquals(11, b.revision, "every run wrote the binding once")
        assertEquals(h.now - 60_000L - 10_000L, b.lastSuccessAt, "the latest fetch")
    }

    @Test
    fun offOnAManualAssetWithNoRowsWritesNothingAndRaisesNothing() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link()

        assertIs<SeasonSyncRecorded.Recorded>(h.result(off()))

        assertEquals(emptyList(), h.rows())
        val b = h.binding()
        assertNull(b.errorKind)
        assertNull(b.appliedAction)
        assertEquals(HaSwitchState.OFF, b.observedState)
    }

    @Test
    fun aFreshApplierOverTheSameStateWritesNothing() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link()
        h.result(on())
        val applied = h.binding()

        h.now += 3_600_000L
        h.result(on(), applier = h.applierOf())

        assertEquals(listOf(SeasonAction.START to "2026-06-10"), h.dated(), "a recreated process writes no second row")
        assertEquals(applied.appliedAt, h.binding().appliedAt)
        assertNull(h.binding().errorKind)
    }

    // ---- row 24: concurrency (AC4, AC6) ----

    @Test
    fun twoIdenticalOverlappingResultsWriteOneRow() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link()

        h.result(on(), readRevision = 1)
        val first = h.binding()
        assertEquals(SeasonSyncRecorded.Dropped, h.result(on(), readRevision = 1))

        assertEquals(listOf(SeasonAction.START to "2026-06-10"), h.dated())
        assertEquals(first, h.binding(), "the second result wrote nothing")
    }

    @Test
    fun anOlderResultArrivingLastIsDropped() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link()
        // A read OFF and B read ON, both at revision 1; B lands first.
        h.result(on(), readRevision = 1)

        assertEquals(SeasonSyncRecorded.Dropped, h.result(off(), readRevision = 1))

        assertEquals(listOf(SeasonAction.START to "2026-06-10"), h.dated(), "A wrote no END")
        assertEquals(HaSwitchState.ON, h.binding().observedState)
    }

    // ---- row 25: a stale result (AC6, AC8, I5) ----

    @Test
    fun aResultReadBeforeAModeEntityStopResumeOrAddressChangeWritesNothing() = runTest {
        val edits: List<Pair<String, List<(SeasonSyncBinding) -> SeasonSyncBinding>>> = listOf(
            "a mode change" to listOf({ b: SeasonSyncBinding -> b.copy(mode = SyncMode.FORCE_OUT) }),
            "an entity change" to listOf({ b: SeasonSyncBinding -> b.copy(entityId = "input_boolean.example_heater_other") }),
            "a stop" to listOf({ b: SeasonSyncBinding -> b.copy(enabled = false) }),
            "a stop and a resume" to listOf(
                { b: SeasonSyncBinding -> b.copy(enabled = false) },
                { b: SeasonSyncBinding -> b.copy(enabled = true) },
            ),
            // C17: a changed address or token bumps every binding's revision and nothing else.
            "an address change" to listOf({ b: SeasonSyncBinding -> b }),
        )
        for ((name, steps) in edits) {
            val h = SeasonSyncHarness()
            h.asset()
            h.link()
            for (step in steps) {
                val b = h.binding()
                assertTrue(h.raw.seasonSyncBindings.update(step(b).copy(revision = b.revision + 1)), name)
            }
            val edited = h.binding()

            assertEquals(SeasonSyncRecorded.Dropped, h.result(on(), readRevision = 1), name)

            assertEquals(edited, h.binding(), "$name: the old result wrote nothing")
            assertEquals(emptyList(), h.rows(), name)
        }
    }

    @Test
    fun aResultAtAStoppedBindingsCurrentRevisionWritesNothing() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link { it.copy(enabled = false, revision = 2) }
        val stopped = h.binding()

        assertEquals(SeasonSyncRecorded.Dropped, h.result(on(), readRevision = 2))

        assertEquals(stopped, h.binding())
        assertEquals(emptyList(), h.rows())
    }

    // ---- row 26: lifecycle (AC8, I8, R16-8) ----

    @Test
    fun archivedRetiredHeldAssetsTakeNoDecisionAndShowNotMaintainedHere() = runTest {
        val h = SeasonSyncHarness()
        h.asset("archived") { it.copy(status = AssetStatus.ARCHIVED) }
        h.asset("retired") { it.copy(retiredOn = "2026-05-01") }
        h.asset("held")
        h.raw.transfers.append(transferOf("out-held", assetId = "held"))
        for (id in listOf("archived", "retired", "held")) {
            h.link(id)

            h.result(on(), assetId = id)

            assertEquals(emptyList(), h.rows(id), id)
            val b = h.binding(id)
            assertEquals(SyncErrorKind.NOT_MAINTAINED_HERE, b.errorKind, id)
            assertNull(b.appliedAction, id)
            assertEquals(HaSwitchState.ON, b.observedState, "$id: the answer is still recorded")
            assertEquals(2, b.revision, id)
        }
    }

    @Test
    fun aDeletedAssetsResultIsDropped() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link()
        h.raw.assets.delete(AssetId(heater))
        assertEquals(emptyMap(), h.raw.seasonSyncBindings.rows, "the cascade took the binding")

        assertEquals(SeasonSyncRecorded.Dropped, h.result(on(), readRevision = 1))
        assertEquals(emptyList(), h.rows())

        // A binding whose asset row is missing is dropped too, and left as it was.
        h.link("ghost")
        val ghost = h.binding("ghost")
        assertEquals(SeasonSyncRecorded.Dropped, h.result(on(), assetId = "ghost"))
        assertEquals(ghost, h.binding("ghost"))
        assertEquals(0, h.bindings.updates)
    }

    // ---- row 26a: a binding resumes on its own (R16-18) ----

    @Test
    fun anUnarchivedAssetsStillEnabledBindingAppliesTheCurrentStateOnceOnTheNextRead() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.activation("act-1", SeasonAction.START, "2026-03-01")
        h.link()
        h.archive.run(AssetId(heater))

        for ((day, answer) in listOf("2026-06-11" to off(), "2026-06-12" to on(), "2026-06-13" to off())) {
            h.on(day)
            h.result(answer)
            assertEquals(SyncErrorKind.NOT_MAINTAINED_HERE, h.binding().errorKind, day)
        }
        assertEquals(listOf(SeasonAction.START to "2026-03-01"), h.dated(), "nothing while archived")

        h.on("2026-06-20")
        h.archive.unarchive(AssetId(heater))
        h.result(off())
        h.result(off())

        assertEquals(listOf(SeasonAction.START to "2026-03-01", SeasonAction.END to "2026-06-20"), h.dated())
        assertTrue(h.binding().enabled)
        assertNull(h.binding().errorKind)
        assertEquals("2026-06-20", h.binding().appliedOn)
    }

    @Test
    fun aWithdrawnOutStaysInertUntilUnarchived() = runTest {
        val h = SeasonSyncHarness()
        h.asset { it.copy(status = AssetStatus.ARCHIVED) }
        h.raw.transfers.append(transferOf("out-1", assetId = heater, packId = "pack-q"))
        h.link()

        h.result(on())
        assertEquals(SyncErrorKind.NOT_MAINTAINED_HERE, h.binding().errorKind, "held")

        WithdrawTransferRecord(TransferPackTesting.repositoriesOf(h.raw), h.raw.uow, IdGenerator { "wd-1" }, h.clock)
            .run(AssetId(heater), "pack-q")
        assertEquals(TransferKind.WITHDRAWN, h.raw.transfers.all().last().kind)
        h.on("2026-06-11")
        h.result(on())
        assertEquals(SyncErrorKind.NOT_MAINTAINED_HERE, h.binding().errorKind, "withdrawn, still archived")
        assertEquals(emptyList(), h.rows())

        h.on("2026-06-12")
        h.archive.unarchive(AssetId(heater))
        h.result(on())

        assertEquals(listOf(SeasonAction.START to "2026-06-12"), h.dated())
        assertNull(h.binding().errorKind)
    }

    // ---- row 27: replacement (R16-8) ----

    @Test
    fun aReplacedAssetsBindingStaysInertOnThePredecessorAndTheSuccessorHasNone() = runTest {
        val r = ReplaceHarness()
        r.put(r.assetRow(PRED, "Example Heater").copy(seasonMode = SeasonMode.MANUAL))
        r.raw.seasonSyncBindings.insert(seasonSyncBindingOf(PRED))
        val applier = RecordSeasonSyncResult(
            r.raw.seasonSyncBindings, r.assets, r.activations, r.raw.transfers,
            RecordSeasonActivation(r.assets, r.events, r.activations, r.uow, r.ids, r.clock, r.todayPort, r.recompute, SeasonSyncGuard(r.raw.seasonSyncBindings)),
            r.uow, r.clock, r.todayPort,
        )

        val successor = r.replaceWith(r.draft(retiredOn = "2026-09-15")).successor.id
        applier.run(AssetId(PRED), 1, r.now - 20_000L, r.now - 10_000L, on())

        assertEquals(setOf(PRED), r.raw.seasonSyncBindings.rows.keys, "no successor inherits a binding")
        assertNull(r.raw.seasonSyncBindings.rows[successor.value])
        val b = r.raw.seasonSyncBindings.rows.getValue(PRED)
        assertEquals(SyncErrorKind.NOT_MAINTAINED_HERE, b.errorKind)
        assertTrue(b.enabled, "inert, not stopped")
        assertEquals(emptyList(), r.raw.activations.rows.values.filter { it.assetId == AssetId(PRED) })
    }

    // ---- row 28: status (AC5, I4) ----

    @Test
    fun aFailureNeverAdvancesLastSuccess() = runTest {
        for (kind in SyncErrorKind.entries) {
            val h = SeasonSyncHarness()
            h.asset()
            h.link { it.copy(observedState = HaSwitchState.ON, observedChangedAt = "earlier", lastSuccessAt = 111L) }
            val detail = mapOf(SyncErrorKind.HTTP_ERROR to "503", SyncErrorKind.UNSUPPORTED_STATE to "unavailable")[kind]

            h.result(HaReadOutcome.NoDecision(kind, detail))

            val b = h.binding()
            assertEquals(111L, b.lastSuccessAt, "$kind never moves the last success")
            assertEquals(HaSwitchState.ON, b.observedState, "$kind keeps the last answer")
            assertEquals("earlier", b.observedChangedAt, "$kind")
            assertEquals(kind, b.errorKind)
            assertEquals(detail, b.errorDetail, "$kind")
            assertEquals(h.now, b.errorAt, "$kind")
            assertEquals(h.now - 20_000L, b.lastAttemptAt, "$kind")
            assertEquals(emptyList(), h.rows(), "$kind")
        }
    }

    @Test
    fun anUnchangedOnAdvancesLastSuccessAndClearsTheError() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.activation("act-1", SeasonAction.START, "2026-03-01")
        h.link {
            it.copy(
                observedState = HaSwitchState.ON, lastSuccessAt = 111L,
                errorKind = SyncErrorKind.UNREACHABLE, errorDetail = null, errorAt = 222L,
            )
        }

        h.result(on())

        val b = h.binding()
        assertEquals(h.now - 10_000L, b.lastSuccessAt, "an unchanged answer is still a fresh observation")
        assertNull(b.errorKind)
        assertNull(b.errorDetail)
        assertNull(b.errorAt)
        assertNull(b.appliedAction)
        assertEquals(1, h.rows().size)
    }

    @Test
    fun observationFetchAndApplicationTimesAreThreeFields() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link()

        h.result(on("2026-06-08T05:06:07+00:00"))

        val b = h.binding()
        assertEquals("2026-06-08T05:06:07+00:00", b.observedChangedAt, "HA's change time, as text")
        assertEquals(h.now - 10_000L, b.lastSuccessAt, "the fetch time")
        assertEquals(h.now, b.appliedAt, "the application time")
        assertEquals("2026-06-10", b.appliedOn)
        assertEquals(h.now - 20_000L, b.lastAttemptAt, "the attempt's start")
        assertEquals(h.now, b.updatedAt)
    }

    // ---- row 29: a forced phase survives polls (AC6, I6; R16-15) ----

    @Test
    fun aForcedBindingRecordsTheObservationAndIgnoresIt() = runTest {
        for (mode in listOf(SyncMode.FORCE_IN, SyncMode.FORCE_OUT)) {
            for (answer in listOf(on(), off())) {
                val h = SeasonSyncHarness()
                h.asset()
                if (mode == SyncMode.FORCE_IN) h.activation("act-1", SeasonAction.START, "2026-03-01")
                h.link(mode = mode)
                val before = h.dated()

                h.result(answer)

                assertEquals(before, h.dated(), "$mode with ${answer.state}: the forced phase stands")
                assertEquals(answer.state, h.binding().observedState, "$mode: the answer is shown")
                assertNull(h.binding().appliedAction, "$mode with ${answer.state}")
            }
        }
    }

    @Test
    fun aForcedPhaseIsReassertedOnANoDecisionRun() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        // A merge landed an END on a FORCE_IN asset.
        h.activation("act-1", SeasonAction.START, "2026-03-01")
        h.activation("act-2", SeasonAction.END, "2026-06-01")
        h.link(mode = SyncMode.FORCE_IN)

        h.result(HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null))
        h.now += 60_000L
        h.result(HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null))

        assertEquals(SeasonAction.START to "2026-06-10", h.dated().last())
        assertEquals(3, h.rows().size, "one row, however many runs")
        assertEquals(SeasonAction.START, h.binding().appliedAction)
        assertEquals(SyncErrorKind.UNREACHABLE, h.binding().errorKind, "the read's status stays")
    }

    // ---- row 30: the clock moved back (H4) ----

    @Test
    fun aDateBeforeTheLatestRowIsAStatusNotAThrow() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        // Written while the device's date was ahead; today reads IN_SEASON from the START.
        h.activation("act-1", SeasonAction.START, "2026-06-01")
        h.activation("act-2", SeasonAction.END, "2026-06-20")
        h.link()

        assertIs<SeasonSyncRecorded.Recorded>(h.result(off()))

        assertEquals(SyncErrorKind.DATE_BEFORE_HISTORY, h.binding().errorKind)
        assertEquals(2, h.rows().size)
        assertEquals(HaSwitchState.OFF, h.binding().observedState)
        assertEquals(h.now - 10_000L, h.binding().lastSuccessAt)
    }

    @Test
    fun aDateBeforeTheLatestRowWithAnAgreeingReadRecordsNoError() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.activation("act-1", SeasonAction.START, "2026-06-01")
        h.activation("act-2", SeasonAction.END, "2026-06-20")
        h.link()

        h.result(on())

        assertNull(h.binding().errorKind)
        assertEquals(2, h.rows().size)
    }

    // ---- C13: a refused binding update rolls the whole write back ----

    @Test
    fun aRefusedBindingUpdateThrowsAndRollsBackTheRow() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        h.link()
        val before = h.binding()
        h.bindings.refuse = true

        assertFailsWith<IllegalStateException> { h.result(on()) }

        assertEquals(emptyList(), h.rows(), "the START rolled back with the refused update")
        assertEquals(before, h.binding(), "the binding is unchanged")
        assertEquals(1, h.raw.uow.rollbacks, "one write, rolled back")
    }

    // ---- row 31: it writes nothing else (AC7, C14) ----

    @Test
    fun anAppliedStartWritesOneRowOneBindingUpdateAndRecomputesOnly() = runTest {
        val h = SeasonSyncHarness()
        h.asset()
        val schedule = SeasonFixtures.hotTubSchedule(id = "s-heater", assetId = heater)
        h.raw.schedules.rows[schedule.id.value] = schedule
        h.link()
        val repos = TransferPackTesting.repositoriesOf(h.raw)
        val before = h.raw.uow.read { readSnapshot(repos) }
        val commits = h.raw.uow.commits

        h.result(on())

        val after = h.raw.uow.read { readSnapshot(repos) }
        assertEquals(before.seasonActivations.size + 1, after.seasonActivations.size, "one row")
        assertEquals(before.copy(seasonActivations = after.seasonActivations), after, "nothing else that travels moved")
        assertEquals(1, h.bindings.updates, "one binding update")
        assertEquals(commits + 1, h.raw.uow.commits, "one write")
        assertEquals(h.now, h.states.rows.getValue("s-heater").computedAt, "the body's recompute ran")
    }
}
