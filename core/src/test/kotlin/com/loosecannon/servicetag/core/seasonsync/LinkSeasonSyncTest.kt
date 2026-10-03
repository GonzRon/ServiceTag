package com.loosecannon.servicetag.core.seasonsync

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.seasonInputs
import com.loosecannon.servicetag.core.schedule.ScheduleRecompute
import com.loosecannon.servicetag.core.schedule.SeasonContext
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.testing.InMemorySecretStore
import com.loosecannon.servicetag.core.testing.completionOf
import com.loosecannon.servicetag.core.testing.scheduleOf
import com.loosecannon.servicetag.core.testing.seasonSyncBindingOf
import com.loosecannon.servicetag.core.testing.transferOf
import com.loosecannon.servicetag.core.transfer.TransferPackTesting
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.core.usecase.SeasonModeStrandsPolicy
import com.loosecannon.servicetag.core.usecase.SeasonSyncOwnsSeason
import com.loosecannon.servicetag.core.usecase.SetSeasonMode
import com.loosecannon.servicetag.core.usecase.readSnapshot
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * #16 (B3c) — B3a's [SeasonSyncHarness] (wrapped, never edited) with the lifecycle commands over it: the shipped
 * [SetSeasonMode] on the same guarded stores, the connection double, a token store and a scheduler that both record
 * the commit count each call saw, so "after the commit" is a number a case can compare.
 */
internal class SeasonSyncCommandsHarness(today: String = "2026-06-10") {
    /** Each call, named, with the commit count it saw. */
    class RecordingScheduler(private val commits: () -> Int) : SeasonSyncScheduler {
        val calls = mutableListOf<Pair<String, Int>>()

        override suspend fun ensure() {
            calls += "ensure" to commits()
        }

        override suspend fun cancel() {
            calls += "cancel" to commits()
        }

        override suspend fun requestFreshRead(assetId: AssetId) {
            calls += "read ${assetId.value}" to commits()
        }

        fun names(): List<String> = calls.map { it.first }
    }

    /** The token store double, recording each put's key and the commit count it saw; [failPuts] makes a put throw. */
    class RecordingSecrets(private val inner: SecretStore, private val commits: () -> Int) : SecretStore by inner {
        val puts = mutableListOf<Pair<String, Int>>()
        var failPuts = false

        override suspend fun put(key: String, secret: Secret) {
            puts += key to commits()
            if (failPuts) throw IllegalStateException("the token store failed")
            inner.put(key, secret)
        }
    }

    val h = SeasonSyncHarness(today)
    val uow = h.raw.uow
    val connections = h.raw.haConnections
    val secrets = RecordingSecrets(InMemorySecretStore()) { uow.commits }
    val scheduler = RecordingScheduler { uow.commits }
    val setSeasonMode = SetSeasonMode(
        h.assets, h.schedules, h.activations, uow, h.ids, h.clock, h.todayPort, h.recompute,
        SeasonSyncGuard(h.raw.seasonSyncBindings),
    )
    val link = LinkSeasonSync(
        h.assets, h.activations, h.raw.transfers, h.bindings, connections, secrets, setSeasonMode, scheduler, uow,
        h.clock, h.todayPort,
    )
    val setMode = SetSeasonSyncMode(h.bindings, h.assets, h.applier, scheduler, uow, h.clock)
    val edit = EditSeasonSyncEntity(h.bindings, scheduler, uow, h.clock)
    val stop = StopSeasonSync(h.bindings, scheduler, uow, h.clock)
    val resume = ResumeSeasonSync(link, h.bindings, scheduler, uow, h.clock)
    val save = SaveHaConnection(connections, h.bindings, secrets, scheduler, uow, h.ids, h.clock)
    val forget = ForgetHaConnection(connections, secrets, scheduler, uow)

    /** The fictional connection as the owner saves it: http on the fictional home Wi-Fi, with [token]. */
    suspend fun connected(token: Secret? = Secret("fictional-token-1")): HaConnection = save.run(
        "http://192.168.0.10:8123", token, null, NetworkEligibility.HOME_NETWORK_ONLY, "ExampleHomeWifi", null,
    )

    suspend fun linked(assetId: String = HEATER, entityId: String = ENTITY): SeasonSyncLinked =
        link.run(AssetId(assetId), entityId)

    fun phase(assetId: String = HEATER, on: LocalDate = h.today): SeasonPhase =
        SeasonContext.of(h.raw.assets.rows.getValue(assetId).seasonInputs(h.rows(assetId))).phaseAt(on)

    /** Everything a write could move: what travels, the bindings, the connection, the commits, the two recorders. */
    suspend fun everything(): List<Any> = listOf(
        uow.read { readSnapshot(TransferPackTesting.repositoriesOf(h.raw)) },
        LinkedHashMap(h.raw.seasonSyncBindings.rows),
        LinkedHashMap(connections.rows),
        uow.commits,
        scheduler.calls.toList(),
        secrets.puts.toList(),
    )

    /** Runs [refused], which must throw [T], and proves nothing at all moved; answers the exception. */
    suspend inline fun <reified T : Throwable> refusesWithNothingWritten(
        what: String,
        crossinline refused: suspend () -> Unit,
    ): T {
        val before = everything()
        val e = assertFailsWith<T>(what) { refused() }
        assertEquals(before, everything(), "$what: nothing written")
        return e
    }

    companion object {
        const val HEATER = SeasonSyncHarness.HEATER
        const val ENTITY = "input_boolean.example_heater_in_season"
    }
}

/**
 * #16 (B3c; C16; rows 39–41, 40a) — the link: refusals first, then the setup reconciliation through the shipped
 * season-mode switch, then one FOLLOW binding; the work ensured and a fresh read asked for after the commit.
 */
class LinkSeasonSyncTest {
    private val heater = SeasonSyncCommandsHarness.HEATER

    // ---- row 39: from MANUAL ----

    @Test
    fun fromManualWritesNoSeasonRowAndLinksFollow() = runTest {
        val c = SeasonSyncCommandsHarness()
        c.h.asset()
        c.h.activation("act-1", SeasonAction.START, "2026-03-01")
        c.h.activation("act-2", SeasonAction.END, "2026-05-20")
        val connection = c.connected()
        val asset = c.h.raw.assets.rows.getValue(heater)
        val commits = c.uow.commits

        val linked = c.linked()

        assertEquals(listOf(SeasonAction.START to "2026-03-01", SeasonAction.END to "2026-05-20"), c.h.dated())
        assertEquals(asset, c.h.raw.assets.rows.getValue(heater), "the asset is untouched")
        assertNull(linked.switchedFrom)
        assertEquals(c.h.binding(), linked.binding)
        assertEquals(
            seasonSyncBindingOf(heater, connectionId = connection.id, at = c.h.now),
            linked.binding,
            "FOLLOW, enabled, revision 1, no status, on the saved connection",
        )
        assertEquals(commits + 1, c.uow.commits, "one write")
        assertEquals(listOf("ensure" to commits + 1, "read $heater" to commits + 1), c.scheduler.calls.takeLast(2))
    }

    // ---- row 40: the reconciliation (R16-Q-C) ----

    @Test
    fun fromCalendarSwitchesToManualAtTodaysPhaseWithOneRow() = runTest {
        val c = SeasonSyncCommandsHarness()
        c.h.asset("in", SeasonMode.CALENDAR) { it.copy(seasonStartMmdd = "05-01", seasonEndMmdd = "09-30") }
        c.h.asset("out", SeasonMode.CALENDAR) { it.copy(seasonStartMmdd = "10-01", seasonEndMmdd = "04-30") }
        c.connected()
        assertEquals(SeasonPhase.IN_SEASON, c.phase("in"))
        assertEquals(SeasonPhase.OUT_OF_SEASON, c.phase("out"))

        for ((id, phase, action) in listOf(
            Triple("in", SeasonPhase.IN_SEASON, SeasonAction.START),
            Triple("out", SeasonPhase.OUT_OF_SEASON, SeasonAction.END),
        )) {
            val linked = c.linked(id)

            val asset = c.h.raw.assets.rows.getValue(id)
            assertEquals(SeasonMode.MANUAL, asset.seasonMode, id)
            assertNull(asset.seasonStartMmdd, "$id: the window is cleared")
            assertNull(asset.seasonEndMmdd, "$id: the window is cleared")
            assertEquals(listOf(action to "2026-06-10"), c.h.dated(id), "$id: one switch row, dated today")
            assertEquals(phase, c.phase(id), "$id: the season did not change on the day it was linked")
            assertEquals(SeasonMode.CALENDAR, linked.switchedFrom, id)
            assertEquals(SyncMode.FOLLOW, c.h.binding(id).mode, id)
        }
    }

    @Test
    fun fromYearRoundStartsInSeason() = runTest {
        val c = SeasonSyncCommandsHarness()
        c.h.asset(mode = SeasonMode.YEAR_ROUND)
        c.connected()

        val linked = c.linked()

        assertEquals(SeasonMode.MANUAL, c.h.raw.assets.rows.getValue(heater).seasonMode)
        assertEquals(listOf(SeasonAction.START to "2026-06-10"), c.h.dated())
        assertEquals(SeasonPhase.IN_SEASON, c.phase())
        assertEquals(SeasonMode.YEAR_ROUND, linked.switchedFrom, "the phone asks #78's question after this one")
    }

    @Test
    fun aPreServiceStrandRefusesAndWritesNothing() = runTest {
        val c = SeasonSyncCommandsHarness()
        c.h.asset(mode = SeasonMode.CALENDAR) { it.copy(seasonStartMmdd = "10-01", seasonEndMmdd = "04-30") }
        c.h.raw.schedules.rows["s-pre"] = scheduleOf(
            id = "s-pre", assetId = heater, title = "Before the season", timeInterval = 1,
            timeUnit = RecurrenceUnit.YEAR, anchorOn = "2026-01-01", servicePolicy = ServicePolicy.PRE_SERVICE,
            policyOffsetDays = 7,
        )
        c.connected()

        val e = c.refusesWithNothingWritten<SeasonModeStrandsPolicy>("strands") { c.linked() }

        assertEquals(listOf("s-pre"), e.schedules.map { it.id.value })
        assertNull(c.h.raw.seasonSyncBindings.rows[heater], "no binding either")
        assertEquals(SeasonMode.CALENDAR, c.h.raw.assets.rows.getValue(heater).seasonMode)
    }

    // ---- row 40a: the cadence moves (C-2, limit 14, R16-Q-G) ----

    /** Limit 14's fictional example: due six months after a 10 Apr service, so 10 Oct; AT_START, offset 14. */
    private fun atStart(assetId: String = heater): MaintenanceSchedule = scheduleOf(
        id = "s-heat", assetId = assetId, title = "Burner service", timeInterval = 6, timeUnit = RecurrenceUnit.MONTH,
        timeBasis = TimeBasis.COMPLETION, anchorOn = "2026-04-01", leadDays = 1,
        servicePolicy = ServicePolicy.IN_SERVICE_AT_START, policyOffsetDays = 14, createdOn = "2026-03-01",
    )

    private fun SeasonSyncCommandsHarness.withAtStart() {
        val schedule = atStart()
        h.raw.schedules.rows[schedule.id.value] = schedule
        val done = completionOf(
            "e-heat", occurredOn = "2026-04-10", occurrenceOn = "2026-04-01", assetId = heater, scheduleId = "s-heat",
        )
        h.raw.events.rows[done.id.value] = done
    }

    /** The shipped evaluator over the asset as stored now, on [day]. */
    private fun SeasonSyncCommandsHarness.actionableOn(day: String): String? = ScheduleRecompute.rebuild(
        atStart(), h.raw.events.rows.values.toList(), emptyList(), emptyList(), LocalDate.parse(day), ZoneOffset.UTC,
        h.raw.assets.rows.getValue(heater).seasonInputs(h.rows()),
    ).actionableDueOn

    @Test
    fun anInWindowCalendarLinkMovesAnAtStartScheduleToLinkDayPlusOffset() = runTest {
        val c = SeasonSyncCommandsHarness("2026-10-20")
        c.h.asset(mode = SeasonMode.CALENDAR) { it.copy(seasonStartMmdd = "10-01", seasonEndMmdd = "04-15") }
        c.withAtStart()
        c.connected()
        assertEquals("2026-10-15", c.actionableOn("2026-10-20"), "before: the window's start plus 14")

        c.linked()

        assertEquals(listOf(SeasonAction.START to "2026-10-20"), c.h.dated(), "the switch row is dated the link day")
        assertEquals("2026-11-03", c.actionableOn("2026-10-20"), "after: the link day plus 14")
        assertEquals("2026-11-03", c.h.states.rows.getValue("s-heat").actionableDueOn, "the switch's recompute")
    }

    @Test
    fun aYearRoundLinkGainsACycleStartAtToday() = runTest {
        val c = SeasonSyncCommandsHarness("2026-10-20")
        c.h.asset(mode = SeasonMode.YEAR_ROUND)
        c.withAtStart()
        c.connected()
        val today = LocalDate.parse("2026-10-20")
        fun cycleStart() =
            SeasonContext.of(c.h.raw.assets.rows.getValue(heater).seasonInputs(c.h.rows())).cycleStartAt(today)
        assertNull(cycleStart(), "a year-round asset has no season start")
        assertEquals("2026-10-10", c.actionableOn("2026-10-20"), "so the schedule keeps its own date")

        c.linked()

        assertEquals(today, cycleStart())
        assertEquals("2026-11-03", c.actionableOn("2026-10-20"), "now it counts from today")
    }

    // ---- row 41: the refusals ----

    @Test
    fun notMaintainedHereNoConnectionNeedsTokenBadEntityAndAlreadyLinkedRefuse() = runTest {
        suspend fun SeasonSyncCommandsHarness.refused(
            reason: SeasonSyncLinkRefusal,
            assetId: String = heater,
            entityId: String = SeasonSyncCommandsHarness.ENTITY,
        ) {
            val e = refusesWithNothingWritten<SeasonSyncLinkRefused>("$reason on $assetId") {
                linked(assetId, entityId)
            }
            assertEquals(reason, e.reason, assetId)
        }

        SeasonSyncCommandsHarness().apply {
            h.asset(mode = SeasonMode.CALENDAR) { it.copy(seasonStartMmdd = "05-01", seasonEndMmdd = "09-30") }
            refused(SeasonSyncLinkRefusal.NO_CONNECTION)
            connected(token = null)
            refused(SeasonSyncLinkRefusal.NEEDS_TOKEN)
        }
        SeasonSyncCommandsHarness().apply {
            h.asset("archived", SeasonMode.CALENDAR) {
                it.copy(status = AssetStatus.ARCHIVED, seasonStartMmdd = "05-01", seasonEndMmdd = "09-30")
            }
            h.asset("retired") { it.copy(retiredOn = "2026-05-01") }
            h.asset("held")
            h.raw.transfers.append(transferOf("out-held", assetId = "held"))
            h.asset()
            connected()
            for (id in listOf("archived", "retired", "held")) refused(SeasonSyncLinkRefusal.NOT_MAINTAINED_HERE, id)
            for (bad in listOf("input_boolean/x", "Input_Boolean.x", "input_boolean.", "a.b%2e", "")) {
                refused(SeasonSyncLinkRefusal.BAD_ENTITY_ID, entityId = bad)
            }
            linked()
            refused(SeasonSyncLinkRefusal.ALREADY_LINKED)
            refused(SeasonSyncLinkRefusal.ALREADY_LINKED, entityId = "input_boolean.another_example")
            assertEquals(SeasonMode.CALENDAR, h.raw.assets.rows.getValue("archived").seasonMode, "never switched")
        }
    }

    // ---- the controller's carry: the guard moved into the extracted body ----

    @Test
    fun aSeasonModeChangeOnALinkedAssetIsStillRefused() = runTest {
        val c = SeasonSyncCommandsHarness()
        c.h.asset(mode = SeasonMode.YEAR_ROUND)
        c.connected()
        c.linked()

        c.refusesWithNothingWritten<SeasonSyncOwnsSeason>("SetSeasonMode.run") {
            c.setSeasonMode.run(AssetId(heater), SeasonModeCommand(SeasonMode.YEAR_ROUND))
        }
    }
}
