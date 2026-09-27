package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.usecase.SaveAssetSettings
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.scheduleOf
import com.loosecannon.servicetag.ui.maintenance.ENTER_THE_NUMBER_OF_DAYS
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * #79 (C11, R79-12, R79-16; §3 row 27): the asset editor's warranty reminder lead. Off by default and
 * blank means off; whole days of at least one, with no upper bound; the lead goes in the save's fifth
 * part, so it lands in the same one write as the asset; a lead with no date is refused with P79-9 and
 * anything that is not a whole number of days with the shipped "Enter the number of days.".
 *
 * After a save that first sets a lead, and only while notifications are not granted, the editor asks
 * with P79-12 — after the write, the #78 question and the copies, at most once per editor — and
 * requests the permission only after "OK". A save that moved the date or the lead runs the shipped
 * reminder sweep once; one that moved neither never does. Both ports are fakes here: the graph's are
 * Android's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetEditWarrantyReminderTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private lateinit var written: RecordingAssets
    private lateinit var saveSettings: SaveAssetSettings

    /** Every sweep the editor ran. */
    private var sweeps = 0
    private val reconcile = ReminderReconcile { sweeps++ }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = java.time.LocalDate.parse("2026-09-27")
        written = RecordingAssets(graph.assets)
        saveSettings = SaveAssetSettings(
            written, graph.schedules, graph.healthSubjects, graph.seasonActivations, graph.uow, graph.ids,
            graph.clock, graph.todayPort, graph.recomputeSchedules, graph.applyTemplate, graph.promoteCategory,
        )
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private class RecordingAssets(private val inner: AssetRepository) : AssetRepository by inner {
        val upserts = mutableListOf<Asset>()
        override suspend fun upsert(asset: Asset) {
            upserts += asset
            inner.upsert(asset)
        }
    }

    /** The permission as a fact the test sets; each request records every stored lead at that moment. */
    private inner class FakePermission(var isGranted: Boolean) : NotificationPermission {
        val requests = mutableListOf<List<Int?>>()
        override fun granted(): Boolean = isGranted
        override fun shouldExplain(): Boolean = false
        override suspend fun request(): Boolean {
            requests += graph.assets.all().map { it.warrantyReminderLeadDays }
            return isGranted
        }
    }

    /** One editor and the two places it says to go, collected eagerly (neither replays). */
    private class Editor(val model: AssetEditViewModel) {
        val saved = mutableListOf<AssetId>()
        val review = mutableListOf<AssetId>()
    }

    private suspend fun TestScope.editor(
        id: String? = null,
        permission: NotificationPermission = FakePermission(isGranted = true),
    ): Editor {
        val model = AssetEditViewModel(
            graph.assets, graph.healthSubjects, saveSettings, graph.schedules, graph.categories,
            graph.attachments, graph.attachmentStorage, graph.addAttachment, graph.todayPort, id?.let(::AssetId),
            notifications = permission,
            reconcile = reconcile,
        )
        model.state.first { it.parentChoices.isNotEmpty() }
        val editor = Editor(model)
        val eager = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(eager) { model.saved.collect { editor.saved += it } }
        backgroundScope.launch(eager) { model.review.collect { editor.review += it } }
        return editor
    }

    private suspend fun AssetEditViewModel.saveAndSettle() {
        save()
        state.first { !it.saving }
    }

    private suspend fun heater(expiresOn: String? = "2028-06-30", lead: Int? = null) = graph.assets.upsert(
        assetRow("heater", name = "Example Heater").copy(warrantyExpiresOn = expiresOn, warrantyReminderLeadDays = lead),
    )

    private suspend fun storedLead(id: String = "heater"): Int? = graph.assets.get(AssetId(id))!!.warrantyReminderLeadDays

    private fun AssetEditViewModel.mark(): String? = state.value.problems[AssetField.WARRANTY_LEAD]

    /** R79-12: nothing pre-filled; blank is no reminder, and a stored lead cleared in the field is turned off. */
    @Test fun blankIsOff() = runTest {
        val created = editor()
        assertEquals("off by default", "", created.model.state.value.warrantyLead)
        created.model.onName("Example Heater")
        created.model.onWarrantyExpiresOn("2028-06-30")
        created.model.saveAndSettle()
        assertNull("a blank lead is no reminder", graph.assets.all().single().warrantyReminderLeadDays)

        heater(lead = 30)
        val edit = editor("heater")
        assertEquals("the stored lead is loaded as typed text", "30", edit.model.state.value.warrantyLead)
        edit.model.onWarrantyLead("")
        edit.model.saveAndSettle()
        assertNull("clearing the field turns it off", storedLead())
        assertEquals(listOf(AssetId("heater")), edit.saved)
    }

    /** C2: the lead rides the settings save's fifth part — one write of the whole row, lead included. */
    @Test fun aLeadIsSavedThroughTheFifthPart() = runTest {
        heater()
        val edit = editor("heater")
        edit.model.onWarrantyLead(" 30 ")
        edit.model.saveAndSettle()

        assertEquals(30, storedLead())
        assertEquals("one write carried it", listOf(30), written.upserts.map { it.warrantyReminderLeadDays })
        assertEquals(listOf(AssetId("heater")), edit.saved)

        val created = editor()
        created.model.onName("Example Heater")
        created.model.onWarrantyExpiresOn("2029-01-31")
        created.model.onWarrantyLead("400")
        created.model.saveAndSettle()
        assertEquals("no upper bound", 400, graph.assets.all().single { it.id != AssetId("heater") }.warrantyReminderLeadDays)
    }

    /** R79-12b: a lead needs a date to count back from; nothing is written. */
    @Test fun aLeadWithABlankDateSaysP79_9() = runTest {
        heater(lead = 30)
        val edit = editor("heater")
        edit.model.onWarrantyExpiresOn("")
        edit.model.saveAndSettle()

        assertEquals(ADD_THE_WARRANTY_DATE_FIRST, edit.model.mark())
        assertEquals("Add the date the warranty expires first.", ADD_THE_WARRANTY_DATE_FIRST)
        assertTrue("nothing was written", written.upserts.isEmpty())
        assertTrue(edit.saved.isEmpty())

        edit.model.onWarrantyExpiresOn("2028-06-30")
        assertNull("editing the date takes the line down", edit.model.mark())
    }

    /** R79-12a: zero, a negative, a fraction and words are all "Enter the number of days."; nothing is written. */
    @Test fun zeroOrABadNumberSaysEnterTheNumberOfDays() = runTest {
        heater()
        val edit = editor("heater")
        for (typed in listOf("0", "-1", "1.5", "a week", "99999999999")) {
            edit.model.onWarrantyLead(typed)
            assertNull("editing the field takes its line down", edit.model.mark())
            edit.model.saveAndSettle()
            assertEquals(typed, ENTER_THE_NUMBER_OF_DAYS, edit.model.mark())
        }
        assertTrue("nothing was written", written.upserts.isEmpty())
        assertTrue(edit.saved.isEmpty())
        assertNull(storedLead())
    }

    /** K4: a rename sends the loaded lead back unchanged, so a full-replace save never loses it. */
    @Test fun aRenameKeepsTheLead() = runTest {
        heater(lead = 30)
        val edit = editor("heater")
        edit.model.onName("Example Heater, garage")
        edit.model.saveAndSettle()

        val stored = graph.assets.get(AssetId("heater"))!!
        assertEquals("Example Heater, garage", stored.name)
        assertEquals(30, stored.warrantyReminderLeadDays)
    }

    /**
     * R79-16, D-22: the lead is written first; then, only while notifications are not granted, P79-12;
     * "OK" requests once, with the lead already stored, and the editor finishes. Granted asks nothing,
     * a lead that was already set asks nothing, and one editor asks at most once.
     */
    @Test fun theRationaleThenRequestRunAfterTheWriteWhenALeadIsFirstSet() = runTest {
        heater()
        val denied = FakePermission(isGranted = false)
        val edit = editor("heater", denied)
        edit.model.onWarrantyLead("30")
        edit.model.saveAndSettle()

        assertTrue("P79-12 is up", edit.model.state.value.askingForNotifications)
        assertEquals("the asset is already written", 30, storedLead())
        assertTrue("nothing is requested before the answer", denied.requests.isEmpty())
        assertTrue("the editor waits for the answer", edit.saved.isEmpty())

        edit.model.requestNotifications()
        edit.model.state.first { !it.askingForNotifications }
        assertEquals("one request, after the write", listOf(listOf<Int?>(30)), denied.requests)
        assertEquals(listOf(AssetId("heater")), edit.saved)

        // The same editor again: off, then on — never a second rationale.
        edit.model.onWarrantyLead("")
        edit.model.saveAndSettle()
        edit.model.onWarrantyLead("45")
        edit.model.saveAndSettle()
        assertFalse("at most once per editor", edit.model.state.value.askingForNotifications)
        assertEquals(1, denied.requests.size)
        assertEquals(3, edit.saved.size)

        // Granted: nothing asked. A lead that was already stored: nothing asked either.
        heater()
        val granted = FakePermission(isGranted = true)
        val allowed = editor("heater", granted)
        allowed.model.onWarrantyLead("30")
        allowed.model.saveAndSettle()
        assertFalse(allowed.model.state.value.askingForNotifications)
        assertTrue(granted.requests.isEmpty())
        assertEquals(listOf(AssetId("heater")), allowed.saved)

        val stillDenied = FakePermission(isGranted = false)
        val moved = editor("heater", stillDenied)
        moved.model.onWarrantyLead("14")
        moved.model.saveAndSettle()
        assertFalse("30 → 14 is not a first lead", moved.model.state.value.askingForNotifications)
        assertEquals(listOf(AssetId("heater")), moved.saved)
        assertTrue(stillDenied.requests.isEmpty())
    }

    /**
     * "Not now" requests nothing and finishes the editor; and the rationale waits for the #78 question,
     * then finishes the way that answer chose.
     */
    @Test fun notNowRequestsNothingAndTheRationaleFollowsTheSeasonQuestion() = runTest {
        heater()
        graph.schedules.upsert(scheduleOf("s1", assetId = "heater", title = "Check", timeInterval = 1, timeUnit = RecurrenceUnit.WEEK, leadDays = 1))
        val denied = FakePermission(isGranted = false)
        val edit = editor("heater", denied)
        edit.model.onWarrantyLead("30")
        edit.model.onSeasonMode(SeasonMode.CALENDAR)
        edit.model.onSeasonStart("05-01")
        edit.model.onSeasonEnd("09-30")
        edit.model.saveAndSettle()

        assertEquals("the #78 question first", EditPrompt.ReconcileSchedules(1), edit.model.prompt.value)
        assertFalse(edit.model.state.value.askingForNotifications)
        edit.model.reviewSchedules()
        assertTrue("then P79-12", edit.model.state.value.askingForNotifications)
        assertTrue(edit.review.isEmpty() && edit.saved.isEmpty())

        edit.model.dismissNotifications()
        assertFalse(edit.model.state.value.askingForNotifications)
        assertTrue("\"Not now\" requests nothing", denied.requests.isEmpty())
        assertEquals("the season answer's way out", listOf(AssetId("heater")), edit.review)
        assertTrue(edit.saved.isEmpty())
        assertEquals(30, storedLead())
    }

    /** C11: the sweep runs once for a save that moved the date or the lead, and never for one that did not. */
    @Test fun aChangedDateOrLeadReconcilesOnceAndAnUnchangedOneNever() = runTest {
        heater(lead = 30)
        val rename = editor("heater")
        rename.model.onName("Example Heater, garage")
        rename.model.saveAndSettle()
        assertEquals("a rename moves neither", 0, sweeps)

        val lead = editor("heater")
        lead.model.onWarrantyLead("14")
        lead.model.saveAndSettle()
        assertEquals("the lead moved", 1, sweeps)
        lead.model.saveAndSettle()
        assertEquals("the same form again moves nothing", 1, sweeps)

        val date = editor("heater")
        date.model.onWarrantyExpiresOn("2029-06-30")
        date.model.saveAndSettle()
        assertEquals("the date moved", 2, sweeps)

        val refused = editor("heater")
        refused.model.onWarrantyLead("0")
        refused.model.saveAndSettle()
        assertEquals("a refused save writes nothing to sweep for", 2, sweeps)

        val created = editor()
        created.model.onName("Example Heater, loft")
        created.model.saveAndSettle()
        assertEquals("a new asset with neither", 2, sweeps)
        val dated = editor()
        dated.model.onName("Example Heater, shed")
        dated.model.onWarrantyExpiresOn("2030-01-31")
        dated.model.onWarrantyLead("7")
        dated.model.saveAndSettle()
        assertEquals("a new asset with both, once", 3, sweeps)
    }
}
