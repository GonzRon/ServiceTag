package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.seasonsync.NetworkEligibility
import com.loosecannon.servicetag.ui.homeassistant.Notice
import com.loosecannon.servicetag.ui.homeassistant.HA_TOKEN_REFUSED
import com.loosecannon.servicetag.ui.homeassistant.HA_NOT_CONNECTED
import com.loosecannon.servicetag.ui.homeassistant.HA_ENTER_TOKEN_AGAIN
import com.loosecannon.servicetag.ui.homeassistant.HA_COULD_NOT_REACH
import com.loosecannon.servicetag.core.seasonsync.SyncErrorKind
import com.loosecannon.servicetag.core.seasonsync.HaListOutcome
import com.loosecannon.servicetag.core.seasonsync.HaEntityCandidate
import com.loosecannon.servicetag.core.seasonsync.Secret
import com.loosecannon.servicetag.core.seasonsync.SyncCadence
import com.loosecannon.servicetag.core.usecase.SeasonModeCommand
import com.loosecannon.servicetag.seasonsync.JdkAead
import com.loosecannon.servicetag.seasonsync.KeystoreSecretStore
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.condition.SeasonOfferPrompt
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
 * #16 row 68 (C27; R16-Q-C, R16-Q-G, R16-19) — the setup sheet's model over a Room-backed [FakeGraph] with the
 * shipped link and resume: the mode's reconciliation sentence before anything is written, #78's question after a
 * YEAR_ROUND link, S55 on a strands refusal, P16-49 on a bad entity id, and Resume on a CALENDAR asset. The sentences
 * are written out in their ratified words, so a paraphrase in the model fails. Home Assistant is the scripted reader
 * (unreachable by default, so no answer adds a row); fixtures are fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkSeasonSyncViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    private val helper = "input_boolean.example_heater_in_season"

    /** #105: the sheet's list seam, scripted per case; counted, so "nothing is read on open" is a number. */
    private var listReads = 0
    private var entityList: suspend () -> HaListOutcome? = { listReads++; HaListOutcome.Listed(emptyList()) }

    private val calendarSentence =
        "Linking this asset to Home Assistant replaces its calendar dates. From today, its season starts and ends " +
            "when Home Assistant says. Maintenance that counts from the start of the season will count from today."
    private val yearRoundSentence =
        "This asset has no operating season now. Linking it to Home Assistant gives it one: in season from today, " +
            "then started and ended when Home Assistant says. Maintenance that counts from the start of the season " +
            "will count from today."
    private val manualSentence =
        "From now on Home Assistant starts and ends this asset's season. Its season history stays as it is."

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

    private fun TestScope.open(id: String, purpose: SeasonSyncSheetPurpose): LinkSeasonSyncViewModel {
        val model = LinkSeasonSyncViewModel(
            AssetId(id), purpose, graph.assets, graph.schedules, graph.haConnections, graph.linkSeasonSync,
            graph.resumeSeasonSync,
        ) { entityList() }
        advanceUntilIdle()
        return model
    }

    private fun TestScope.act(step: () -> Unit) {
        step()
        advanceUntilIdle()
    }

    private val LinkSeasonSyncViewModel.now: LinkSeasonSyncState get() = state.value

    private suspend fun rows(id: String) =
        graph.seasonActivations.forAsset(AssetId(id)).map { it.action to it.occurredOn }

    private suspend fun binding(id: String) = graph.seasonSyncBindings.get(AssetId(id))

    private fun continuous(id: String, assetId: String, status: ScheduleStatus = ScheduleStatus.ACTIVE) = scheduleOf(
        id, assetId = assetId, title = "Check $id", timeInterval = 1, timeUnit = RecurrenceUnit.WEEK, leadDays = 1,
        servicePolicy = ServicePolicy.CONTINUOUS, status = status,
    )

    private fun seasonStartOn(assetId: String) = AssetEvent(
        id = EventId("e-$assetId"), assetId = AssetId(assetId), kind = EventKind.SEASON_START, title = "Lit",
        profileId = null, occurredOn = "2026-02-10", occurredTime = null, tzId = "UTC", notes = "",
        source = EventSource.MANUAL, sourceRef = null, createdAt = 0L, updatedAt = 0L,
        measurements = emptyList(), consumables = emptyList(),
    )

    /**
     * Each mode's sentence (P16-44/45/46) is in the state as soon as the sheet has read the asset, and nothing is
     * written until Save: then one binding, and the switch row today for CALENDAR and YEAR_ROUND only. A linked asset's
     * journal raises no season offer (R16-16), where an unlinked MANUAL one still does.
     */
    @Test fun eachModeShowsItsSentenceBeforeTheWrite() = runTest {
        connect()
        graph.assets.upsert(assetRow("cal", seasonMode = SeasonMode.CALENDAR, seasonStart = "10-01", seasonEnd = "04-30"))
        graph.assets.upsert(assetRow("year", seasonMode = SeasonMode.YEAR_ROUND))
        graph.assets.upsert(assetRow("hand", seasonMode = SeasonMode.MANUAL))
        graph.assets.upsert(assetRow("spare", seasonMode = SeasonMode.MANUAL))
        graph.assets.upsert(assetRow("late", seasonMode = SeasonMode.MANUAL))

        // Save is held until the sentence is read: a model whose asset read is still pending writes nothing.
        val pending = LinkSeasonSyncViewModel(
            AssetId("late"), SeasonSyncSheetPurpose.LINK, graph.assets, graph.schedules, graph.haConnections,
            graph.linkSeasonSync, graph.resumeSeasonSync,
        ) { entityList() }
        assertNull("the read is pending", pending.now.sentence)
        assertFalse(pending.now.canSave)
        pending.onEntityId(helper)
        pending.save()
        advanceUntilIdle()
        assertEquals(manualSentence, pending.now.sentence)
        assertNull("nothing written before the sentence", binding("late"))
        assertNull(pending.now.finished)

        mapOf("cal" to calendarSentence, "year" to yearRoundSentence, "hand" to manualSentence).forEach { (id, said) ->
            val sheet = open(id, SeasonSyncSheetPurpose.LINK)
            assertEquals("$id: its sentence", said, sheet.now.sentence)
            sheet.onEntityId(helper)
            advanceUntilIdle()
            assertNull("$id: no binding before Save", binding(id))
            assertEquals("$id: no row before Save", emptyList<Any>(), rows(id))
            assertNull("$id: still open", sheet.now.finished)

            act { sheet.save() }

            assertTrue("$id: linked", binding(id)!!.enabled)
            assertEquals(SeasonSheetExit.CLOSED, sheet.now.finished)
        }
        assertEquals(listOf(SeasonAction.START to "2026-02-10"), rows("cal"))
        assertEquals(listOf(SeasonAction.START to "2026-02-10"), rows("year"))
        assertEquals(emptyList<Any>(), rows("hand"))
        assertEquals(SeasonMode.MANUAL, graph.assets.get(AssetId("cal"))!!.seasonMode)

        assertTrue(graph.eventOffers.offersAfter(seasonStartOn("hand")).none { it is SeasonOfferPrompt })
        assertTrue(graph.eventOffers.offersAfter(seasonStartOn("spare")).any { it is SeasonOfferPrompt })
    }

    /**
     * #78 after a YEAR_ROUND link: the binding is written first, then P78-1a/1b's count — the live CONTINUOUS
     * schedules, ACTIVE and PAUSED, never ARCHIVED — and the sheet waits for the answer, which writes nothing:
     * "Keep schedules as-is" closes it, "Review maintenance schedules" closes it onto the schedules. A stopped binding
     * resumed through the sheet on an asset moved back to YEAR_ROUND asks the same question (R16-19; row 43a's phone half).
     */
    @Test fun aYearRoundLinkWithContinuousSchedulesAsksP78After() = runTest {
        connect()
        listOf("year", "year2", "year3").forEach { id ->
            graph.assets.upsert(assetRow(id, seasonMode = SeasonMode.YEAR_ROUND))
            graph.schedules.upsert(continuous("$id-a", id))
            graph.schedules.upsert(continuous("$id-p", id, ScheduleStatus.PAUSED))
            graph.schedules.upsert(continuous("$id-x", id, ScheduleStatus.ARCHIVED))
        }
        val before = graph.schedules.forAsset(AssetId("year"))

        val keep = open("year", SeasonSyncSheetPurpose.LINK)
        keep.onEntityId(helper)
        act { keep.save() }
        assertNotNull("the link is written before the question", binding("year"))
        assertEquals(EditPrompt.ReconcileSchedules(2), keep.now.prompt)
        assertNull("the sheet waits for the answer", keep.now.finished)
        act { keep.keepSchedules() }
        assertEquals(SeasonSheetExit.CLOSED, keep.now.finished)
        assertEquals("the answer writes nothing", before, graph.schedules.forAsset(AssetId("year")))

        val beforeReview = graph.schedules.forAsset(AssetId("year2"))
        val review = open("year2", SeasonSyncSheetPurpose.LINK)
        review.onEntityId(helper)
        act { review.save() }
        assertEquals(EditPrompt.ReconcileSchedules(2), review.now.prompt)
        act { review.reviewSchedules() }
        assertEquals(SeasonSheetExit.REVIEW_SCHEDULES, review.now.finished)
        assertEquals("the answer writes nothing", beforeReview, graph.schedules.forAsset(AssetId("year2")))

        // Resume: linked out of season (MANUAL, no row), stopped, moved back to YEAR_ROUND, then resumed in the sheet.
        graph.assets.upsert(assetRow("year3", seasonMode = SeasonMode.MANUAL))
        graph.linkSeasonSync.run(AssetId("year3"), helper)
        graph.stopSeasonSync.run(AssetId("year3"))
        graph.setSeasonMode.run(AssetId("year3"), SeasonModeCommand(SeasonMode.YEAR_ROUND))
        advanceUntilIdle()
        val resumed = open("year3", SeasonSyncSheetPurpose.RESUME)
        assertEquals(yearRoundSentence, resumed.now.sentence)
        act { resumed.save() }
        assertTrue("the resume is written before the question", binding("year3")!!.enabled)
        assertEquals(EditPrompt.ReconcileSchedules(2), resumed.now.prompt)
        assertNull("the sheet waits for the answer", resumed.now.finished)
        act { resumed.keepSchedules() }
        assertEquals(SeasonSheetExit.CLOSED, resumed.now.finished)
    }

    /** A PRE_SERVICE strand refuses the CALENDAR link: S55 names the schedule, the sheet stays, nothing is written. */
    @Test fun aStrandRefusalShowsS55AndKeepsTheSheet() = runTest {
        connect()
        graph.assets.upsert(assetRow("cal", seasonMode = SeasonMode.CALENDAR, seasonStart = "10-01", seasonEnd = "04-30"))
        graph.schedules.upsert(
            scheduleOf(
                "s-pre", assetId = "cal", title = "Before the season", timeInterval = 1, timeUnit = RecurrenceUnit.YEAR,
                servicePolicy = ServicePolicy.PRE_SERVICE, policyOffsetDays = 7,
            ),
        )
        val sheet = open("cal", SeasonSyncSheetPurpose.LINK)
        sheet.onEntityId(helper)

        act { sheet.save() }

        assertEquals(
            "Some maintenance on this asset is set to be ready before its season. Change it first: Before the season.",
            sheet.now.refusal,
        )
        assertNull("the sheet stays open", sheet.now.finished)
        assertEquals(calendarSentence, sheet.now.sentence)
        assertNull(binding("cal"))
        assertEquals(emptyList<Any>(), rows("cal"))
        assertEquals(SeasonMode.CALENDAR, graph.assets.get(AssetId("cal"))!!.seasonMode)
    }

    /** C4's rule refuses the entity id: P16-49 under the field, the sheet open, nothing written; typing clears it. */
    @Test fun aBadEntityShowsP16_49() = runTest {
        connect()
        graph.assets.upsert(assetRow("hand", seasonMode = SeasonMode.MANUAL))
        val sheet = open("hand", SeasonSyncSheetPurpose.LINK)
        sheet.onEntityId("Input_Boolean.Example_Heater")

        act { sheet.save() }

        assertEquals(
            "Enter an entity ID such as input_boolean.example_heater_in_season: lowercase letters, digits and " +
                "underscores, with one dot.",
            sheet.now.entityLine,
        )
        assertNull(sheet.now.finished)
        assertNull(binding("hand"))
        sheet.onEntityId(helper)
        assertNull(sheet.now.entityLine)
    }

    /**
     * C-4: Resume on an asset moved to CALENDAR while stopped shows P16-44 and writes nothing until Save; then the
     * switch row today and the binding enabled.
     */
    @Test fun resumeOnACalendarAssetShowsP16_44BeforeTheWrite() = runTest {
        connect()
        graph.assets.upsert(assetRow("hand", seasonMode = SeasonMode.MANUAL))
        graph.linkSeasonSync.run(AssetId("hand"), helper)
        graph.stopSeasonSync.run(AssetId("hand"))
        graph.setSeasonMode.run(AssetId("hand"), SeasonModeCommand(SeasonMode.CALENDAR, "10-01", "04-30"))
        advanceUntilIdle()
        val rowsBefore = rows("hand")

        val sheet = open("hand", SeasonSyncSheetPurpose.RESUME)

        assertEquals(calendarSentence, sheet.now.sentence)
        assertFalse("nothing written on open", binding("hand")!!.enabled)
        assertEquals(SeasonMode.CALENDAR, graph.assets.get(AssetId("hand"))!!.seasonMode)
        assertEquals(rowsBefore, rows("hand"))

        act { sheet.save() }

        assertTrue(binding("hand")!!.enabled)
        assertEquals(SeasonMode.MANUAL, graph.assets.get(AssetId("hand"))!!.seasonMode)
        assertEquals(rowsBefore + (SeasonAction.START to "2026-02-10"), rows("hand"))
        assertEquals(SeasonSheetExit.CLOSED, sheet.now.finished)
    }

    /**
     * The use cases' other refusals, each its ratified sentence and nothing written (controller resolution 2): not
     * maintained here (P16-36), no connection (P16-10), no token (P16-11), and a Resume whose binding went with a
     * Disconnect (`SeasonSyncNotLinked`: P16-10).
     */
    @Test fun theOtherRefusalsShowTheirSentencesAndWriteNothing() = runTest {
        graph.assets.upsert(assetRow("hand", seasonMode = SeasonMode.MANUAL))
        val unconnected = open("hand", SeasonSyncSheetPurpose.LINK)
        unconnected.onEntityId(helper)
        act { unconnected.save() }
        assertEquals("Not connected. Enter the server address and an access token.", unconnected.now.refusal)

        connect()
        graph.assets.upsert(assetRow("old", seasonMode = SeasonMode.MANUAL, status = AssetStatus.ARCHIVED))
        val archived = open("old", SeasonSyncSheetPurpose.LINK)
        archived.onEntityId(helper)
        act { archived.save() }
        assertEquals(
            "This asset is no longer maintained here, so Home Assistant no longer changes its season.",
            archived.now.refusal,
        )

        graph.linkSeasonSync.run(AssetId("hand"), helper)
        graph.stopSeasonSync.run(AssetId("hand"))
        graph.setSeasonMode.run(AssetId("hand"), SeasonModeCommand(SeasonMode.YEAR_ROUND))
        graph.secretStore.delete(graph.haConnections.get()!!.id)
        val tokenless = open("hand", SeasonSyncSheetPurpose.RESUME)
        act { tokenless.save() }
        assertEquals("Enter the access token again: this phone no longer has it.", tokenless.now.refusal)
        assertFalse(binding("hand")!!.enabled)

        val gone = open("hand", SeasonSyncSheetPurpose.RESUME)
        graph.forgetHaConnection.run()
        act { gone.save() }
        assertEquals("Not connected. Enter the server address and an access token.", gone.now.refusal)
        listOf(unconnected, archived, tokenless, gone).forEach { assertNull(it.now.finished) }
        assertEquals(SeasonMode.YEAR_ROUND, graph.assets.get(AssetId("hand"))!!.seasonMode)
        assertNull(binding("old"))
    }

    // --- #105 (B3): the entity browser, rows 11–17 ---------------------------------------------------------------

    private val stove = HaEntityCandidate("input_boolean.pellet_stove_in_season", "Pellet Stove In Season")
    private val heaterHelper = HaEntityCandidate("input_boolean.example_heater_in_season", "Example Heater In Season")
    private val lamp = HaEntityCandidate("switch.example_lamp", "Example Lamp")

    private suspend fun handAsset() = graph.assets.upsert(assetRow("hand", seasonMode = SeasonMode.MANUAL))

    /** Rows 11, 12: nothing is read on open; Choose entity reads once and lists the scope sorted; a pick links the exact id. */
    @Test fun chooseEntityReadsOnceAndAPickLinksTheExactId() = runTest {
        connect()
        handAsset()
        entityList = { listReads++; HaListOutcome.Listed(listOf(lamp, stove, heaterHelper)) }
        val sheet = open("hand", SeasonSyncSheetPurpose.LINK)
        assertEquals("nothing is read on open", 0, listReads)
        assertNull(sheet.now.browse)

        act { sheet.chooseEntity() }

        assertEquals(1, listReads)
        val browse = sheet.now.browse!!
        assertEquals(listOf(heaterHelper, stove), browse.rows)
        assertFalse(browse.loading)
        assertTrue(browse.loadedOnce)
        assertEquals(emptyList<Notice>(), browse.failure)

        act { sheet.pick(stove) }

        assertNull("the browser closed on the pick", sheet.now.browse)
        assertEquals(stove, sheet.now.chosen)
        assertEquals("", sheet.now.entityId)
        assertEquals(stove.entityId, sheet.now.entityToLink)
        act { sheet.save() }
        assertEquals("the exact id, through the shipped link", stove.entityId, binding("hand")!!.entityId)
        assertEquals(SeasonSheetExit.CLOSED, sheet.now.finished)
        assertEquals("no second read", 1, listReads)
    }

    /** Row 13: typing clears the pick; Enter entity ID manually prefills the field with the pick; a bad shape is P16-49 as today. */
    @Test fun typingClearsThePickAndManualEntryPrefillsTheField() = runTest {
        connect()
        handAsset()
        entityList = { HaListOutcome.Listed(listOf(stove)) }
        val sheet = open("hand", SeasonSyncSheetPurpose.LINK)
        act { sheet.chooseEntity() }
        act { sheet.pick(stove) }

        act { sheet.enterManually() }
        assertTrue(sheet.now.manualEntry)
        assertNull(sheet.now.chosen)
        assertEquals("the field starts with the pick's id", stove.entityId, sheet.now.entityId)

        sheet.onEntityId("not an id")
        assertEquals("not an id", sheet.now.entityToLink)
        act { sheet.save() }
        assertEquals(SEASON_SYNC_BAD_ENTITY_ID, sheet.now.entityLine)
        assertNull("a refusal writes nothing", binding("hand"))

        act { sheet.chooseEntity() }
        act { sheet.pick(stove) }
        assertFalse("a pick leaves manual entry", sheet.now.manualEntry)
        assertEquals("", sheet.now.entityId)
        assertEquals(stove, sheet.now.chosen)
    }

    /** Row 14: a failed first read draws its sentence and no rows; a failed refresh keeps the rows; nothing is chosen by a failure. */
    @Test fun aFailedFirstReadDrawsTheSentenceAndAFailedRefreshKeepsTheRows() = runTest {
        connect()
        handAsset()
        entityList = { HaListOutcome.Failed(SyncErrorKind.UNREACHABLE, null) }
        val sheet = open("hand", SeasonSyncSheetPurpose.LINK)

        act { sheet.chooseEntity() }
        var browse = sheet.now.browse!!
        assertEquals(listOf(Notice(HA_COULD_NOT_REACH)), browse.failure)
        assertEquals(emptyList<HaEntityCandidate>(), browse.rows)
        assertFalse(browse.loadedOnce)
        assertFalse(browse.scopeEmpty)
        assertFalse(browse.noMatch)

        entityList = { HaListOutcome.Listed(listOf(stove, heaterHelper)) }
        act { sheet.refreshEntities() }
        browse = sheet.now.browse!!
        assertEquals(emptyList<Notice>(), browse.failure)
        assertEquals(listOf(heaterHelper, stove), browse.rows)

        entityList = { HaListOutcome.Failed(SyncErrorKind.AUTH_REFUSED, null) }
        act { sheet.refreshEntities() }
        browse = sheet.now.browse!!
        assertEquals(listOf(Notice(HA_TOKEN_REFUSED)), browse.failure)
        assertEquals("the last good list stays", listOf(heaterHelper, stove), browse.rows)

        act { sheet.closeBrowse() }
        assertNull(sheet.now.browse)
        assertNull("nothing was chosen by a failure", sheet.now.chosen)
        assertNull(binding("hand"))
    }

    /** Row 15: an empty scope and a query that matches nothing are two states; the search is live over the list. */
    @Test fun noHelpersAndNoMatchAreTwoDifferentStates() = runTest {
        connect()
        handAsset()
        entityList = { HaListOutcome.Listed(listOf(lamp)) }
        val sheet = open("hand", SeasonSyncSheetPurpose.LINK)
        act { sheet.chooseEntity() }
        assertTrue("a switch is out of scope: nothing to list", sheet.now.browse!!.scopeEmpty)
        assertFalse(sheet.now.browse!!.noMatch)

        entityList = { HaListOutcome.Listed(listOf(lamp, stove, heaterHelper)) }
        act { sheet.refreshEntities() }
        assertFalse(sheet.now.browse!!.scopeEmpty)
        sheet.onQuery("pellet")
        assertEquals(listOf(stove), sheet.now.browse!!.rows)
        sheet.onQuery("zzz")
        assertTrue(sheet.now.browse!!.noMatch)
        assertFalse(sheet.now.browse!!.scopeEmpty)
        sheet.onQuery("")
        assertEquals(listOf(heaterHelper, stove), sheet.now.browse!!.rows)
    }

    /** Rows 14, 17: no connection and no token draw their sentences; no state value carries the token. */
    @Test fun noConnectionAndNoTokenDrawTheirSentencesAndNoStateCarriesTheToken() = runTest {
        connect()
        handAsset()
        entityList = { null }
        val sheet = open("hand", SeasonSyncSheetPurpose.LINK)
        act { sheet.chooseEntity() }
        assertEquals(listOf(Notice(HA_NOT_CONNECTED)), sheet.now.browse!!.failure)

        entityList = { HaListOutcome.Failed(SyncErrorKind.NEEDS_TOKEN, null) }
        act { sheet.refreshEntities() }
        assertEquals(listOf(Notice(HA_ENTER_TOKEN_AGAIN)), sheet.now.browse!!.failure)
        assertFalse(sheet.now.toString().contains("fictional-token"))
    }
}
