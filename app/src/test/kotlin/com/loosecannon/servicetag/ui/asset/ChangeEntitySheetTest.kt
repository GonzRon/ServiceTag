package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.seasonsync.EditSeasonSyncEntity
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.core.seasonsync.OutboundTargets
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.seasonsync.SyncMode
import com.loosecannon.servicetag.seasonsync.JdkAead
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.scheduleOf
import kotlin.io.path.createTempDirectory
import kotlin.test.assertFailsWith
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #129 row k8 (L-C7) — the setup sheet's third purpose, Change helper, over a Room-backed [FakeGraph] with the shipped
 * `EditSeasonSyncEntity`: it opens on the stored helper with P129-3, holds Save while the helper is empty or the stored
 * one, saves a pick or a trimmed typed id through the edit (never the link, whose `ALREADY_LINKED` was 1.9.0's dead end)
 * and closes, draws the edit's refusals where the link draws them, and never asks #78's question. The sentences are
 * written out in their ratified words, so a paraphrase fails. Home Assistant is the scripted reader (unreachable by
 * default, so no answer adds a row); fixtures are fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeEntitySheetTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    private val heater = AssetId("example-heater")
    private val oldHelper = "input_boolean.example_heater_in_season"
    private val newHelper = "input_boolean.example_heater_season"

    private val changeSentence =
        "The link reads the helper you choose from its next check. This asset's season history stays as it is."

    /** The list seam, counted, so "nothing is read on open" is a number. */
    private var listReads = 0
    private val pool = HaEntityCandidate("input_boolean.example_pool_pump_in_season", "Example Pool Pump In Season")

    /** What reached the change seam, in order: never the link. */
    private val changes = mutableListOf<String>()

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

    private suspend fun TestScope.linked(mode: SeasonMode = SeasonMode.MANUAL) {
        graph.assets.upsert(assetRow(heater.value, name = "Example Heater", seasonMode = mode))
        graph.linkSeasonSync.run(heater, oldHelper)
        advanceUntilIdle()
    }

    /** The sheet as the graph builds it, its change through [edit] (the graph's shipped edit unless a case scripts one). */
    private fun TestScope.open(
        purpose: SeasonSyncSheetPurpose = SeasonSyncSheetPurpose.CHANGE_ENTITY,
        edit: EditSeasonSyncEntity = graph.editSeasonSyncEntity,
    ): LinkSeasonSyncViewModel {
        val model = LinkSeasonSyncViewModel(
            SeasonSyncSheetTarget.Asset(heater), purpose, graph.assets, graph.schedules, graph.haConnections,
            graph.linkSeasonSync, graph.resumeSeasonSync, { _, _ -> error("an asset's sheet links no schedule") },
            { listReads++; HaListOutcome.Listed(listOf(pool)) },
            storedEntity = { graph.seasonSyncBindings.get(it)?.entityId },
            changeEntity = { assetId, entityId ->
                changes += entityId
                edit.run(assetId, entityId)
            },
        )
        advanceUntilIdle()
        return model
    }

    private fun TestScope.act(step: () -> Unit) {
        step()
        advanceUntilIdle()
    }

    private val LinkSeasonSyncViewModel.now: LinkSeasonSyncState get() = state.value

    private suspend fun binding() = graph.seasonSyncBindings.get(heater)

    @Test fun changeHelperOpensOnTheStoredHelperWithP129_3AndHoldsSave() = runTest {
        connect()
        linked()
        val sheet = open()

        assertEquals("the stored id, chosen, with no friendly name", HaEntityCandidate(oldHelper, null), sheet.now.chosen)
        assertEquals(oldHelper, sheet.now.storedEntityId)
        assertEquals(changeSentence, sheet.now.sentence)
        assertTrue("the entity row is drawn", sheet.now.choosesEntity)
        assertFalse("the stored helper again: Save held", sheet.now.canSave)
        assertEquals("nothing is read on open", 0, listReads)
        assertNull(sheet.now.finished)
    }

    @Test fun saveIsHeldWhileEmptyOrTheStoredHelperAndSendsTheTrimmedTypedId() = runTest {
        connect()
        linked()
        val before = checkNotNull(binding())
        val sheet = open()

        act { sheet.enterManually() }
        assertEquals("the field holds the stored id", oldHelper, sheet.now.entityId)
        assertFalse(sheet.now.canSave)
        act { sheet.onEntityId("") }
        assertFalse("empty: Save held", sheet.now.canSave)
        act { sheet.onEntityId("  $oldHelper ") }
        assertFalse("the stored id, trimmed: Save held", sheet.now.canSave)
        act { sheet.save() }
        assertEquals("a held Save writes nothing", emptyList<String>(), changes)

        act { sheet.onEntityId("  $newHelper ") }
        assertTrue(sheet.now.canSave)
        act { sheet.save() }

        assertEquals("the trimmed id, through the change", listOf(newHelper), changes)
        val after = checkNotNull(binding())
        assertEquals(newHelper, after.entityId)
        // The edit moves it once; the fresh read it asks for (unreachable here) records its error at the next.
        assertTrue("the edit moved the revision", after.revision > before.revision)
        assertEquals("the mode is kept", before.mode, after.mode)
        assertEquals(SeasonSheetExit.CLOSED, sheet.now.finished)
        assertNull(sheet.now.prompt)
    }

    @Test fun aPickEnablesSaveAndSaveChangesToThePickedHelper() = runTest {
        connect()
        linked()
        val sheet = open()

        act { sheet.chooseEntity() }
        assertEquals(1, listReads)
        act { sheet.pick(pool) }
        assertTrue(sheet.now.canSave)
        act { sheet.save() }

        assertEquals(listOf(pool.entityId), changes)
        assertEquals(pool.entityId, binding()!!.entityId)
        assertEquals(SeasonSheetExit.CLOSED, sheet.now.finished)
    }

    @Test fun aBadIdIsP16_49AndAnOutboundHelperIsP113_43UnderTheEntity() = runTest {
        connect()
        linked()
        val before = binding()
        val bad = open()
        act { bad.enterManually() }
        act { bad.onEntityId("Example Heater") }
        act { bad.save() }
        assertEquals(
            "Enter an entity ID such as input_boolean.example_heater_in_season: lowercase letters, digits and " +
                "underscores, with one dot.",
            bad.now.entityLine,
        )
        assertNull(bad.now.finished)

        // C26: a helper this phone writes, refused by the shipped edit through its outbound port.
        val writes = object : OutboundTargets by OutboundTargets.NONE {
            override suspend fun writes(connectionId: String, entityId: String): Boolean = entityId == newHelper
        }
        val edit = EditSeasonSyncEntity(graph.seasonSyncBindings, graph.seasonSyncRunner, graph.uow, graph.clock, writes)
        val outbound = open(edit = edit)
        act { outbound.enterManually() }
        act { outbound.onEntityId(newHelper) }
        act { outbound.save() }
        assertEquals(
            "input_boolean.example_heater_season is a helper this phone sets. Choose the helper Home Assistant itself " +
                "controls.",
            outbound.now.entityLine,
        )
        assertNull(outbound.now.refusal)
        assertNull(outbound.now.finished)
        assertEquals("neither refusal wrote anything", before, binding())
    }

    /**
     * The binding gone before Save: with a Disconnect, P16-10 and the sheet stays; gone any other way, the sheet closes
     * onto the card. A sheet opened on no binding closes at once.
     */
    @Test fun aLinkGoneBeforeSaveIsP16_10WithoutAConnectionElseTheSheetCloses() = runTest {
        connect()
        linked()
        val removed = open()
        act { removed.enterManually() }
        act { removed.onEntityId(newHelper) }
        graph.unlinkSeasonSync.run(heater)
        act { removed.save() }
        assertEquals(SeasonSheetExit.CLOSED, removed.now.finished)
        assertNull(removed.now.refusal)

        val unbound = open()
        assertEquals("no binding: the sheet closes at once", SeasonSheetExit.CLOSED, unbound.now.finished)

        graph.linkSeasonSync.run(heater, oldHelper)
        advanceUntilIdle()
        val disconnected = open()
        act { disconnected.enterManually() }
        act { disconnected.onEntityId(newHelper) }
        graph.forgetHaConnection.run()
        act { disconnected.save() }
        assertEquals("Not connected. Enter the server address and an access token.", disconnected.now.refusal)
        assertNull(disconnected.now.finished)
        assertNull(binding())
    }

    /** #78's question follows a write out of YEAR_ROUND; a change of helper moves no mode, so it is never asked. */
    @Test fun noP78PromptOnAnyPath() = runTest {
        connect()
        graph.assets.upsert(assetRow(heater.value, name = "Example Heater", seasonMode = SeasonMode.YEAR_ROUND))
        graph.schedules.upsert(
            scheduleOf(
                "weekly", assetId = heater.value, title = "Weekly check", timeInterval = 1,
                timeUnit = RecurrenceUnit.WEEK, leadDays = 1, servicePolicy = ServicePolicy.CONTINUOUS,
            ),
        )
        graph.linkSeasonSync.run(heater, oldHelper)
        advanceUntilIdle()
        assertEquals("the link left YEAR_ROUND, where #78 would ask", SeasonMode.MANUAL, graph.assets.get(heater)!!.seasonMode)
        val sheet = open()
        act { sheet.enterManually() }
        act { sheet.onEntityId(newHelper) }
        act { sheet.save() }
        assertNull(sheet.now.prompt)
        assertEquals(SeasonSheetExit.CLOSED, sheet.now.finished)
        assertEquals(newHelper, binding()!!.entityId)
    }

    @Test fun aScheduleTargetRefusesChangeEntityAtConstruction() = runTest {
        assertFailsWith<IllegalArgumentException> {
            LinkSeasonSyncViewModel(
                SeasonSyncSheetTarget.Schedule(ScheduleId("s-heater")), SeasonSyncSheetPurpose.CHANGE_ENTITY,
                graph.assets, graph.schedules, graph.haConnections, graph.linkSeasonSync, graph.resumeSeasonSync,
                { _, _ -> }, { null },
            )
        }
    }

    /** I2: under Force in season the sheet says the same P129-3, and the change keeps the stored mode. */
    @Test fun aForcedInLinksSheetShowsTheSameSentenceAndTheChangeKeepsItsMode() = runTest {
        connect()
        linked()
        graph.setSeasonSyncMode.run(heater, SyncMode.FORCE_IN)
        advanceUntilIdle()
        val sheet = open()
        assertEquals(changeSentence, sheet.now.sentence)
        act { sheet.enterManually() }
        act { sheet.onEntityId(newHelper) }
        act { sheet.save() }
        val after = binding()!!
        assertEquals(newHelper, after.entityId)
        assertEquals("the mode is kept", SyncMode.FORCE_IN, after.mode)
        assertEquals(SeasonSheetExit.CLOSED, sheet.now.finished)
    }
}
