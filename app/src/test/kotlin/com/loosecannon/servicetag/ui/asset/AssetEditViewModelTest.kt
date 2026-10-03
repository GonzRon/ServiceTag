package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.SeasonSyncGuard
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.usecase.liveContinuousCount
import com.loosecannon.servicetag.seasonsync.JdkAead
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.scheduleOf
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #16 row 69 (C27; R16-16) — the asset editor beside a Home Assistant binding: its season block read-only with P16-47
 * while the binding is enabled, the guard's refusal drawn as P16-47 when a save reaches it, and #78's count lifted to
 * core's [liveContinuousCount] with ARCHIVED schedules left out. #78's shipped cases stay in `AssetViewModelsTest`,
 * unchanged. Fixtures are fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetEditViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    private val helper = "input_boolean.example_heater_in_season"
    private val followsHomeAssistant =
        "This asset's season follows Home Assistant. Stop syncing on the asset's page to change it here."

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

    /** The editor as the screen builds it, the season guard included; suspends until the form has loaded. */
    private suspend fun editModel(id: String): AssetEditViewModel {
        val model = AssetEditViewModel(
            graph.assets, graph.healthSubjects, graph.saveAssetSettings, graph.schedules, graph.categories,
            graph.attachments, graph.attachmentStorage, graph.addAttachment, graph.todayPort, AssetId(id),
            seasonSync = SeasonSyncGuard(graph.seasonSyncBindings),
        )
        model.state.first { it.parentChoices.isNotEmpty() }
        return model
    }

    private suspend fun AssetEditViewModel.saveAndSettle() {
        save()
        state.first { !it.saving }
    }

    private suspend fun connect() {
        graph.saveHaConnection.run(
            "https://ha.example:8123", Secret("fictional-token-1"), SyncCadence.DAILY, NetworkEligibility.ANY_NETWORK,
            null, null,
        )
    }

    private suspend fun rows(id: String) = graph.seasonActivations.forAsset(AssetId(id)).map { it.action to it.occurredOn }

    private fun continuous(id: String, assetId: String, status: ScheduleStatus) = scheduleOf(
        id, assetId = assetId, title = "Check $id", timeInterval = 1, timeUnit = RecurrenceUnit.WEEK, leadDays = 1,
        servicePolicy = ServicePolicy.CONTINUOUS, status = status,
    )

    /**
     * R16-16: a linked asset's season block says P16-47 and takes no choice; a rename still saves, with no season row
     * and the binding as it was. An unlinked asset's block is as shipped. An editor opened before the link, its season
     * changed, reaches the guard on Save: P16-47 under the block, and nothing written.
     */
    @Test fun aSyncedAssetsSeasonBlockIsReadOnlyWithP16_47() = runTest {
        connect()
        graph.assets.upsert(assetRow("heater", name = "Example Heater", seasonMode = SeasonMode.MANUAL))
        graph.assets.upsert(assetRow("spare", name = "Example Spare", seasonMode = SeasonMode.MANUAL))
        graph.linkSeasonSync.run(AssetId("heater"), helper)
        advanceUntilIdle()
        val linked = graph.seasonSyncBindings.get(AssetId("heater"))!!

        val editor = editModel("heater")
        assertEquals(followsHomeAssistant, editor.state.value.seasonSyncLine)
        editor.onSeasonMode(SeasonMode.CALENDAR)
        assertEquals("the block offers nothing", SeasonMode.MANUAL, editor.state.value.seasonMode)
        editor.onName("Example Heater, garage")
        editor.saveAndSettle()
        assertEquals("Example Heater, garage", graph.assets.get(AssetId("heater"))!!.name)
        assertEquals(SeasonMode.MANUAL, graph.assets.get(AssetId("heater"))!!.seasonMode)
        assertEquals(emptyList<Any>(), rows("heater"))
        assertEquals(linked, graph.seasonSyncBindings.get(AssetId("heater")))

        val early = editModel("spare")
        assertNull("not linked: as shipped", early.state.value.seasonSyncLine)
        early.onSeasonMode(SeasonMode.CALENDAR)
        early.onSeasonStart("10-01")
        early.onSeasonEnd("04-30")
        graph.linkSeasonSync.run(AssetId("spare"), helper)
        advanceUntilIdle()
        early.saveAndSettle()
        assertEquals(followsHomeAssistant, early.state.value.seasonRefusal)
        assertEquals(SeasonMode.MANUAL, graph.assets.get(AssetId("spare"))!!.seasonMode)
        assertEquals(emptyList<Any>(), rows("spare"))
    }

    /**
     * #78 through the lifted count: ARCHIVED CONTINUOUS schedules are not counted, so one live one asks about one,
     * and an asset whose CONTINUOUS schedules are all ARCHIVED is not asked.
     */
    @Test fun anArchivedContinuousScheduleIsNotCounted() = runTest {
        graph.assets.upsert(assetRow("pump", seasonMode = SeasonMode.YEAR_ROUND))
        graph.schedules.upsert(continuous("p1", "pump", ScheduleStatus.ACTIVE))
        graph.schedules.upsert(continuous("p2", "pump", ScheduleStatus.ARCHIVED))
        graph.schedules.upsert(continuous("p3", "pump", ScheduleStatus.ARCHIVED))
        graph.assets.upsert(assetRow("old", seasonMode = SeasonMode.YEAR_ROUND))
        graph.schedules.upsert(continuous("o1", "old", ScheduleStatus.ARCHIVED))

        assertEquals(1, liveContinuousCount(graph.schedules.forAsset(AssetId("pump"))))
        assertEquals(0, liveContinuousCount(graph.schedules.forAsset(AssetId("old"))))

        val pump = editModel("pump")
        pump.onSeasonMode(SeasonMode.MANUAL)
        pump.onManualPhase(SeasonPhase.OUT_OF_SEASON)
        pump.saveAndSettle()
        assertEquals(EditPrompt.ReconcileSchedules(1), pump.prompt.value)

        val old = editModel("old")
        val closed = mutableListOf<AssetId>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { old.saved.collect { closed += it } }
        old.onSeasonMode(SeasonMode.MANUAL)
        old.onManualPhase(SeasonPhase.OUT_OF_SEASON)
        old.saveAndSettle()
        assertNull("only archived ones: not asked", old.prompt.value)
        assertEquals(listOf(AssetId("old")), closed)
        assertTrue(graph.schedules.forAsset(AssetId("old")).all { it.status == ScheduleStatus.ARCHIVED })
    }
}
