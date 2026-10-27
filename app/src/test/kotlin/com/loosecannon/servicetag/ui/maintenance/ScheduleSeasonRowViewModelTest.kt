package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleSeasonSource
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.HaReadOutcome
import com.loosecannon.servicetag.core.seasonsync.HaSwitchState
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.ScheduleSeasonRefusal
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.seasonsync.JdkAead
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.groupOf
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.asset.LinkSeasonSyncViewModel
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_BAD_ENTITY_ID
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_NOT_MAINTAINED_HERE
import com.loosecannon.servicetag.ui.asset.SEASON_SYNC_NO_READING_YET
import com.loosecannon.servicetag.ui.asset.SeasonSheetExit
import com.loosecannon.servicetag.ui.asset.SeasonSyncSheetPurpose
import com.loosecannon.servicetag.ui.asset.SeasonSyncSheetTarget
import com.loosecannon.servicetag.ui.asset.seasonSyncOutboundTarget
import com.loosecannon.servicetag.ui.asset.seasonSyncStartedFromHa
import com.loosecannon.servicetag.ui.homeassistant.HA_COULD_NOT_REACH
import com.loosecannon.servicetag.ui.homeassistant.HA_ENTER_TOKEN_AGAIN
import com.loosecannon.servicetag.ui.homeassistant.HA_NOT_CONNECTED
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #125 h18 (U-C8) — the schedule detail's season row and the link sheet opened from it, over a Room-backed [FakeGraph]
 * with the shipped #125 commands: each source and state draws its sentence (P125-1, P125-2 with #16's status and error
 * sentences, P125-5), Follow Home Assistant opens the sheet with the **schedule** as its target and its browser keeps
 * the `INPUT_BOOLEANS` scope, the sheet's Save links the schedule and never the asset, Use the asset's season asks
 * P125-4 before it unlinks, and every `ScheduleSeasonRefusal` draws its sentence. The new sentences are written out in
 * their ratified words, so a paraphrase fails. Home Assistant is scripted; every name and entity id is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleSeasonRowViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    private val helper = "input_boolean.example_season"
    private val schedule = ScheduleId("opening")

    private var listReads = 0
    private var entityList: suspend () -> HaListOutcome? = { listReads++; HaListOutcome.Listed(emptyList()) }

    /** The schedule links the sheet asked for, in order: what the graph's construction launches the check for. */
    private val checks = mutableListOf<ScheduleId>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val store =
            KeystoreSecretStore(createTempDirectory("no-backup").toFile(), JdkAead(), StandardTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler), secretStore = store)
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private suspend fun connect() {
        graph.saveHaConnection.run(
            "https://ha.example:8123", Secret("fictional-token-1"), SyncCadence.DAILY, NetworkEligibility.ANY_NETWORK,
            null, null,
        )
    }

    /** "Example Heater", on its CALENDAR season with no break, and its schedule [schedule] on the asset's season. */
    private suspend fun heaterWith(policy: ServicePolicy = ServicePolicy.CONTINUOUS) {
        graph.assets.upsert(
            assetRow(
                "heater", name = "Example Heater", seasonMode = SeasonMode.CALENDAR, seasonStart = "11-01",
                seasonEnd = "03-31",
            ),
        )
        graph.schedules.upsert(
            scheduleOf(
                schedule.value, assetId = "heater", title = "Example opening service", servicePolicy = policy,
                policyOffsetDays = if (policy == ServicePolicy.PRE_SERVICE) -7 else null,
            ),
        )
    }

    private fun TestScope.row(id: ScheduleId = schedule): ScheduleSeasonRowViewModel {
        val model = ScheduleSeasonRowViewModel(
            id, graph.schedules, graph.assets, graph.scheduleSeasonBindings, graph.haConnections, graph.secretStore,
            graph.transferRecords, graph.unlinkScheduleSeason, graph.clock,
        ) { ZoneOffset.UTC }
        advanceUntilIdle()
        return model
    }

    /** The sheet as the row opens it: its target, and the link the graph's construction makes for that target. */
    private fun TestScope.sheet(target: SeasonSyncSheetTarget): LinkSeasonSyncViewModel {
        val model = LinkSeasonSyncViewModel(
            target, SeasonSyncSheetPurpose.LINK, graph.assets, graph.schedules, graph.haConnections,
            graph.linkSeasonSync, graph.resumeSeasonSync,
            { id, entity ->
                graph.linkScheduleSeason.run(id, entity)
                checks += id
            },
        ) { entityList() }
        advanceUntilIdle()
        return model
    }

    private fun TestScope.act(step: () -> Unit) {
        step()
        advanceUntilIdle()
    }

    private val ScheduleSeasonRowViewModel.now: ScheduleSeasonRowState get() = state.value

    private suspend fun source() = graph.schedules.get(schedule)?.seasonSource

    /** P125-1 names the asset; Follow Home Assistant is offered while a connection is saved (#16's P16-22 rule). */
    @Test fun anAssetSourcedScheduleNamesItsAssetsSeason() = runTest {
        heaterWith()
        val model = row()
        assertEquals(ScheduleSeasonRowKind.ASSET, model.now.kind)
        assertEquals("Season: Example Heater's season", model.now.headline)
        assertFalse("no connection, no Follow", model.now.offersFollow)
        assertFalse(model.now.offersUseAsset)

        connect()
        act { model.refresh() }
        assertTrue(model.now.offersFollow)
        assertFalse(model.now.offersUseAsset)
        assertTrue(model.now.statusLines.isEmpty())
    }

    /** A group's schedule has no season row: it follows no season of its own (inv. 106). */
    @Test fun aGroupsScheduleDrawsNoRow() = runTest {
        connect()
        graph.assets.upsert(assetRow("pump", name = "Example Pump"))
        graph.groups.upsert(groupOf("north", members = listOf(Triple("pump", "2026-01-01", null))))
        graph.schedules.upsert(scheduleOf("round", groupId = "north"))
        val model = row(ScheduleId("round"))
        assertEquals(ScheduleSeasonRowKind.NONE, model.now.kind)
        assertNull(model.now.headline)
        assertFalse(model.now.offersFollow)
        act { model.follow() }
        assertNull(model.now.sheet)
    }

    /**
     * Follow Home Assistant opens the sheet with the schedule as its target, a fresh opening each time; the browser
     * shows only on/off helpers (`INPUT_BOOLEANS`); Save links the **schedule** — its binding, source `OWN`, and the
     * post-link check asked — while the asset gets no binding and keeps its CALENDAR season.
     */
    @Test fun followOpensTheSheetForTheScheduleAndLinksOnlyTheSchedule() = runTest {
        heaterWith()
        connect()
        val model = row()
        act { model.follow() }
        assertEquals(SeasonSyncSheetTarget.Schedule(schedule), model.now.sheet)
        assertEquals(1, model.now.sheetOpening)
        act { model.sheetClosed() }
        assertNull(model.now.sheet)
        act { model.follow() }
        assertEquals("each opening is a fresh sheet", 2, model.now.sheetOpening)

        entityList = {
            listReads++
            HaListOutcome.Listed(
                listOf(
                    HaEntityCandidate(helper, "Example season"),
                    HaEntityCandidate("input_select.example_status", "Example status"),
                    HaEntityCandidate("input_datetime.example_date", "Example date"),
                ),
            )
        }
        val opened = sheet(checkNotNull(model.now.sheet))
        assertNull("no asset mode is reconciled for a schedule", opened.state.value.sentence)
        assertEquals("nothing is read before Choose entity", 0, listReads)
        act { opened.chooseEntity() }
        assertEquals(listOf(helper), opened.state.value.browse?.rows?.map { it.entityId })
        act { opened.pick(opened.state.value.browse!!.rows.single()) }
        assertTrue(opened.state.value.canSave)
        act { opened.save() }

        assertEquals(SeasonSheetExit.CLOSED, opened.state.value.finished)
        assertEquals(helper, graph.scheduleSeasonBindings.get(schedule)?.entityId)
        assertEquals(ScheduleSeasonSource.OWN, source())
        assertEquals(listOf(schedule), checks)
        assertNull("the asset is never linked", graph.seasonSyncBindings.get(AssetId("heater")))
        assertEquals(SeasonMode.CALENDAR, graph.assets.get(AssetId("heater"))?.seasonMode)
        assertTrue(graph.seasonActivations.forAsset(AssetId("heater")).isEmpty())

        act { model.sheetClosed() }
        assertEquals(ScheduleSeasonRowKind.FOLLOWING, model.now.kind)
    }

    /**
     * Linked: P125-2 with the entity and #16's status sentences — no reading yet (P16-31), then an unreachable helper's
     * error line, then a reading that applied a START (P16-34) — and Use the asset's season, never Follow.
     */
    @Test fun aFollowingScheduleDrawsItsEntityAndItsStatus() = runTest {
        heaterWith()
        connect()
        graph.linkScheduleSeason.run(schedule, helper)
        val model = row()
        assertEquals(ScheduleSeasonRowKind.FOLLOWING, model.now.kind)
        assertEquals("Season: follows input_boolean.example_season in Home Assistant", model.now.headline)
        assertTrue(model.now.statusLines.toString(), SEASON_SYNC_NO_READING_YET in model.now.statusLines)
        assertTrue(model.now.errorLines.isEmpty())
        assertFalse(model.now.offersFollow)
        assertTrue(model.now.offersUseAsset)

        graph.recordScheduleSeasonSyncResult.run(
            schedule, readRevision = 1, startedAt = 1_000L, fetchedAt = 1_000L,
            outcome = HaReadOutcome.NoDecision(SyncErrorKind.UNREACHABLE, null),
        )
        advanceUntilIdle()
        assertEquals(listOf(HA_COULD_NOT_REACH), model.now.errorLines)

        graph.recordScheduleSeasonSyncResult.run(
            schedule, readRevision = 2, startedAt = 1_000L, fetchedAt = 1_000L,
            outcome = HaReadOutcome.Observed(HaSwitchState.ON, null),
        )
        advanceUntilIdle()
        assertTrue(model.now.errorLines.isEmpty())
        assertTrue(
            model.now.statusLines.toString(),
            seasonSyncStartedFromHa(com.loosecannon.servicetag.ui.condition.displayDate(graph.today)) in
                model.now.statusLines,
        )
    }

    /** A token that is not on this phone is the binding's state line, P16-11, drawn once. */
    @Test fun aMissingTokenIsSaidOnce() = runTest {
        heaterWith()
        connect()
        graph.linkScheduleSeason.run(schedule, helper)
        graph.secretStore.delete(checkNotNull(graph.haConnections.get()).id)
        val model = row()
        assertEquals(1, (model.now.statusLines + model.now.errorLines).count { it == HA_ENTER_TOKEN_AGAIN })
    }

    /**
     * Use the asset's season asks P125-4 with the asset's name first; Cancel writes nothing; the confirm unlinks — the
     * binding gone, the source back to `ASSET`, the own rows kept as history — and the row says P125-1 again.
     */
    @Test fun useTheAssetsSeasonAsksThenReturnsTheSchedule() = runTest {
        heaterWith()
        connect()
        graph.linkScheduleSeason.run(schedule, helper)
        graph.recordScheduleSeasonSyncResult.run(
            schedule, readRevision = 1, startedAt = 1_000L, fetchedAt = 1_000L,
            outcome = HaReadOutcome.Observed(HaSwitchState.ON, null),
        )
        val model = row()

        act { model.askUseAsset() }
        assertEquals(
            "Stop following Home Assistant? This schedule will use Example Heater's season again, and its own season " +
                "history is kept.",
            model.now.confirm,
        )
        act { model.dismissUseAsset() }
        assertNull(model.now.confirm)
        assertNotNull("Cancel writes nothing", graph.scheduleSeasonBindings.get(schedule))
        assertEquals(ScheduleSeasonSource.OWN, source())

        act { model.askUseAsset() }
        act { model.confirmUseAsset() }
        assertNull(model.now.confirm)
        assertNull(graph.scheduleSeasonBindings.get(schedule))
        assertEquals(ScheduleSeasonSource.ASSET, source())
        assertEquals("the own rows stay as history", 1, graph.scheduleSeasonActivations.forSchedule(schedule).size)
        assertEquals(ScheduleSeasonRowKind.ASSET, model.now.kind)
        assertEquals("Season: Example Heater's season", model.now.headline)
    }

    /** Forget leaves the schedule `OWN` with no binding: P125-5, and both actions, even with no connection left. */
    @Test fun aStrandedScheduleSaysSoAndOffersBothActions() = runTest {
        heaterWith()
        connect()
        graph.linkScheduleSeason.run(schedule, helper)
        graph.forgetHaConnection.run()
        val model = row()
        assertEquals(ScheduleSeasonRowKind.STRANDED, model.now.kind)
        assertEquals(
            "No longer linked to Home Assistant: this schedule keeps its own season as it was. Follow Home Assistant " +
                "again, or use the asset's season.",
            model.now.headline,
        )
        assertTrue(model.now.offersFollow)
        assertTrue(model.now.offersUseAsset)
        assertEquals(ScheduleSeasonSource.OWN, source())
    }

    /** Every refusal has its sentence and its place: under the entity, in the sheet, or none (the row says it). */
    @Test fun eachRefusalDrawsItsSentence() {
        val expected = ScheduleSeasonRefusal.entries.associateWith { reason ->
            when (reason) {
                ScheduleSeasonRefusal.NOT_MAINTAINED_HERE ->
                    ScheduleSeasonRefusalLine.InSheet(SEASON_SYNC_NOT_MAINTAINED_HERE)
                ScheduleSeasonRefusal.NO_CONNECTION -> ScheduleSeasonRefusalLine.InSheet(HA_NOT_CONNECTED)
                ScheduleSeasonRefusal.NEEDS_TOKEN -> ScheduleSeasonRefusalLine.InSheet(HA_ENTER_TOKEN_AGAIN)
                ScheduleSeasonRefusal.BAD_ENTITY_ID -> ScheduleSeasonRefusalLine.UnderEntity(SEASON_SYNC_BAD_ENTITY_ID)
                ScheduleSeasonRefusal.ALREADY_LINKED -> null
                ScheduleSeasonRefusal.OUTBOUND_TARGET ->
                    ScheduleSeasonRefusalLine.UnderEntity(seasonSyncOutboundTarget(helper))
                ScheduleSeasonRefusal.GROUP_TARGET ->
                    ScheduleSeasonRefusalLine.InSheet("A group's schedule follows no season of its own.")
                ScheduleSeasonRefusal.SCHEDULE_ARCHIVED ->
                    ScheduleSeasonRefusalLine.InSheet("This schedule is archived. Restore it first.")
                ScheduleSeasonRefusal.STRANDS_PRE_SERVICE -> ScheduleSeasonRefusalLine.InSheet(STRANDS_PRE_SERVICE)
            }
        }
        val drawn = ScheduleSeasonRefusal.entries.associateWith { scheduleSeasonRefusalLine(it, helper) }
        assertEquals(expected, drawn)
    }

    /**
     * Through the sheet: a `PRE_SERVICE` schedule on an asset with no break is refused with P125-7, the sheet open and
     * nothing written; a bad entity id is P16-49 under the field.
     */
    @Test fun aRefusedLinkKeepsTheSheetOpenWithItsSentence() = runTest {
        heaterWith(ServicePolicy.PRE_SERVICE)
        connect()
        val opened = sheet(SeasonSyncSheetTarget.Schedule(schedule))
        act { opened.enterManually() }
        act { opened.onEntityId("switch.example") }
        act { opened.save() }
        assertEquals(SEASON_SYNC_BAD_ENTITY_ID, opened.state.value.entityLine)
        assertNull(opened.state.value.finished)

        act { opened.onEntityId(helper) }
        act { opened.save() }
        assertEquals(STRANDS_PRE_SERVICE, opened.state.value.refusal)
        assertNull(opened.state.value.finished)
        assertNull("nothing written", graph.scheduleSeasonBindings.get(schedule))
        assertEquals(ScheduleSeasonSource.ASSET, source())
        assertTrue(checks.isEmpty())
    }

    private companion object {
        const val STRANDS_PRE_SERVICE = "This schedule is done before the season starts, and a Home Assistant season " +
            "has no start date to be ready before. Change when it should be done first."
    }
}
