package com.loosecannon.servicetag.ui.loan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.testing.DENIED_PICK
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.NAMELESS_PICK
import com.loosecannon.servicetag.testing.RENTALS_PICK
import com.loosecannon.servicetag.testing.SAMPLE_PICK
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.fakeContactReader
import com.loosecannon.servicetag.testing.loanRow
import com.loosecannon.servicetag.ui.condition.DATE_NOT_LATER_THAN_TODAY
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import java.time.LocalDate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * #72 (C17; R72-3, R72-9, R72-15; §3 row 30): the lend form's view model over a Room-backed [FakeGraph],
 * the real loan writers and a fake contact seam. A new loan needs a picked borrower — a nameless pick
 * is P72-30, an unreadable one P72-46, and there is no typed name; the reminder chips need a due date and
 * clearing it resets them; refusals land on their fields; a stale form is P72-37; one write per tap;
 * leaving writes nothing. P72-33 and the permission request come only after the write, never when
 * granted, never for None, once per editor, and "Not now" requests nothing. The sweep runs once for a
 * new loan or a moved due date or reminder, never for the notes or the lent date. Every name is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoanEditViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private val store = ViewModelStore()

    private var sweeps = 0
    private val sweep = ReminderReconcile { sweeps++ }
    private val requests = mutableListOf<String>()

    private fun permission(granted: Boolean) = object : NotificationPermission {
        override fun granted(): Boolean = granted
        override fun shouldExplain(): Boolean = false
        override suspend fun request(): Boolean = false.also { requests += "request" }
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-09-20")
    }

    @After fun tearDown() {
        store.clear()
        graph.close()
        Dispatchers.resetMain()
    }

    private fun <T : ViewModel> held(model: T): T = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = model as V
        },
    )["${model::class.java.name}-${System.identityHashCode(model)}", model::class.java]

    private fun editor(
        loanId: String? = null,
        assetId: String = "drill",
        granted: Boolean = true,
    ) = held(
        LoanEditViewModel(
            graph.assets, graph.loans, graph.lendAsset, graph.updateLoan, fakeContactReader(), graph.todayPort,
            AssetId(assetId), loanId?.let(::AssetLoanId),
            io = StandardTestDispatcher(scheduler),
            notifications = permission(granted),
            reconcile = sweep,
        ),
    )

    private suspend fun LoanEditViewModel.ready(): LoanForm = state.first { it.loaded }

    private suspend fun LoanEditViewModel.settled(): LoanForm = state.first { !it.saving && !it.reading }

    private suspend fun drill() = graph.assets.upsert(assetRow("drill", name = "Example Drill"))

    private suspend fun storedLoans(): List<AssetLoan> = graph.loans.all()

    /** R72-3: no typed name — the borrower is a pick; with none, the save says P72-29 and writes nothing. */
    @Test fun aNewLoanNeedsAPickedBorrower() = runTest {
        drill()
        val model = editor()
        val form = model.ready()
        assertEquals("Lend out", form.screenTitle)
        assertEquals("Lent on opens on today", "2026-09-20", form.lentOn)
        assertTrue(
            "the form has no way to type a borrower",
            LoanEditViewModel::class.java.methods.none { it.name.startsWith("onBorrower") },
        )

        model.save()
        assertEquals(mapOf(LoanField.BORROWER to "Choose a borrower"), model.settled().problems)
        assertEquals(emptyList<AssetLoan>(), storedLoans())

        model.onPicked(RENTALS_PICK)
        val picked = model.settled()
        assertEquals("Example Rentals Ltd", picked.borrower)
        assertEquals(RENTALS_PICK, picked.lookupUri)
        assertFalse("the pick takes P72-29 away", LoanField.BORROWER in picked.problems)

        val saved = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.saved.first() }
        model.save()
        assertEquals("drill", saved.await())
        val loan = storedLoans().single()
        assertEquals("Example Rentals Ltd", loan.borrowerName)
        assertEquals(RENTALS_PICK, loan.contactLookupUri)
        assertNull(loan.returnedOn)
    }

    @Test fun aNamelessPickSaysP72_30() = runTest {
        drill()
        val model = editor()
        model.ready()
        model.onPicked(SAMPLE_PICK)
        assertEquals("Sample Borrower", model.settled().borrower)

        model.onPicked(NAMELESS_PICK)
        val form = model.settled()
        assertEquals(mapOf(LoanField.BORROWER to "This contact has no name to show."), form.problems)
        assertEquals("the failed pick leaves no borrower behind", "", form.borrower)
        assertNull(form.lookupUri)
    }

    @Test fun aReadFailureSaysP72_46() = runTest {
        drill()
        val model = editor()
        model.ready()

        model.onPicked(DENIED_PICK)
        assertEquals(mapOf(LoanField.BORROWER to "Could not read this contact."), model.settled().problems)
        model.onPicked("content://com.android.contacts/contacts/lookup/0r8-GONE/8")
        assertEquals("no row", mapOf(LoanField.BORROWER to "Could not read this contact."), model.settled().problems)
        assertEquals(emptyList<AssetLoan>(), storedLoans())
    }

    /** C17: the chips are disabled with no due date, P72-27 under them; clearing the date resets them to None. */
    @Test fun chipsNeedADueDateAndClearingItResetsToNone() = runTest {
        drill()
        val model = editor()
        val form = model.ready()
        assertFalse(form.remindersEnabled)
        assertEquals(LoanReminderMode.NONE, form.mode)

        model.onMode(LoanReminderMode.ONCE)
        assertEquals("disabled chips take no choice", LoanReminderMode.NONE, model.state.value.mode)

        model.onDueOn("2026-10-01")
        assertTrue(model.state.value.remindersEnabled)
        model.onMode(LoanReminderMode.UNTIL_RETURNED)
        assertEquals(LoanReminderMode.UNTIL_RETURNED, model.state.value.mode)

        model.onDueOn("")
        assertFalse(model.state.value.remindersEnabled)
        assertEquals("clearing the date resets the reminder", LoanReminderMode.NONE, model.state.value.mode)
        assertEquals("None", reminderModeWord(LoanReminderMode.NONE))
        assertEquals("Once", reminderModeWord(LoanReminderMode.ONCE))
        assertEquals("Until returned", reminderModeWord(LoanReminderMode.UNTIL_RETURNED))
    }

    @Test fun refusalsLandOnTheirFields() = runTest {
        drill()
        val model = editor()
        model.ready()
        model.onPicked(SAMPLE_PICK)
        model.settled()

        model.onLentOn("2026-09-21")
        model.onDueOn("2026-09-01")
        model.save()
        assertEquals(
            "the problems come together",
            mapOf(
                LoanField.LENT_ON to DATE_NOT_LATER_THAN_TODAY,
                LoanField.DUE_ON to "The due date cannot be before the day it was lent.",
            ),
            model.settled().problems,
        )

        model.onLentOn("20-09-2026")
        assertFalse("an edit takes its field's line away", LoanField.LENT_ON in model.state.value.problems)
        model.onDueOn("soon")
        model.save()
        assertEquals(
            mapOf(LoanField.LENT_ON to "Enter a date as YYYY-MM-DD", LoanField.DUE_ON to "Enter a date as YYYY-MM-DD"),
            model.settled().problems,
        )
        assertEquals("every refusal wrote nothing", emptyList<AssetLoan>(), storedLoans())
    }

    /** C15, P72-45 (beyond the matrix): a phone with no contact picker says so, and nothing is written. */
    @Test fun noPickerSaysP72_45() = runTest {
        drill()
        val model = editor()
        model.ready()
        val said = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }

        model.onNoPicker()

        assertEquals("No app can pick a contact", said.await())
        assertEquals(emptyList<AssetLoan>(), storedLoans())
    }

    /** C2 (i), P72-37: a form opened before the asset was lent elsewhere writes nothing and says so. */
    @Test fun aStaleFormSaysP72_37() = runTest {
        drill()
        val first = editor()
        val second = editor()
        first.ready()
        second.ready()
        first.onPicked(SAMPLE_PICK)
        second.onPicked(RENTALS_PICK)
        first.settled()
        second.settled()

        val done = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { first.saved.first() }
        first.save()
        done.await()
        val said = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { second.messages.first() }
        second.save()

        assertEquals("This asset is already lent out.", said.await())
        assertFalse("the stale form can try again", second.settled().saving)
        assertEquals(listOf("Sample Borrower"), storedLoans().map { it.borrowerName })
    }

    /** `saving` is set before the first suspension: two taps in one frame lend once. */
    @Test fun aDoubleTapWritesOnce() = runTest {
        drill()
        val model = editor()
        model.ready()
        model.onPicked(SAMPLE_PICK)
        model.settled()
        val saved = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.saved.first() }
        val said = mutableListOf<String>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { model.messages.collect { said += it } }

        model.save()
        model.save()
        saved.await()
        testScheduler.advanceUntilIdle()
        assertEquals("one loan", 1, storedLoans().size)
        // The second tap never reached the use case: it would have met the first loan and said P72-37.
        assertEquals("the second tap said nothing", emptyList<String>(), said)
        assertTrue("saving stays set once the loan is written", model.state.value.saving)
    }

    /** ✕ and back only leave: a picked, typed-into form abandoned writes nothing, new or an edit. */
    @Test fun cancelWritesNothing() = runTest {
        drill()
        val fresh = editor()
        fresh.ready()
        fresh.onPicked(SAMPLE_PICK)
        fresh.onDueOn("2026-10-01")
        fresh.onMode(LoanReminderMode.ONCE)
        fresh.onNotes("With the case")
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList<AssetLoan>(), storedLoans())

        val stored = loanRow("l1", "drill", lentOn = "2026-09-01", dueOn = "2026-10-01")
        graph.loans.upsert(stored)
        val edit = editor(loanId = "l1")
        assertEquals("Edit loan", edit.ready().screenTitle)
        assertEquals("Sample Borrower", edit.state.value.borrower)
        edit.onNotes("Changed and abandoned")
        store.clear()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(stored), storedLoans())
        assertEquals("and nothing was swept", 0, sweeps)
    }

    /**
     * C17, R72-9: P72-33 goes up only after the loan is written, when the save first set a reminder and
     * notifications are off; `request()` runs only after "OK", then the sweep, then `saved`. Never when
     * granted, never for None, and once per editor.
     */
    @Test fun theRationaleThenRequestRunAfterTheWrite() = runTest {
        drill()
        val model = editor(granted = false)
        model.ready()
        model.onPicked(SAMPLE_PICK)
        model.onDueOn("2026-10-01")
        model.onMode(LoanReminderMode.ONCE)
        model.settled()
        val saved = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.saved.first() }

        model.save()
        val asking = model.state.first { it.askingForNotifications }
        assertTrue(asking.askingForNotifications)
        assertEquals("the loan is written before the question", LoanReminderMode.ONCE, storedLoans().single().reminderMode)
        assertEquals("nothing requested before OK", emptyList<String>(), requests)
        assertEquals("the sweep waits for the answer", 0, sweeps)
        assertFalse("not saved yet", saved.isCompleted)

        model.requestNotifications()
        assertEquals("drill", saved.await())
        assertEquals(listOf("request"), requests)
        assertEquals("then one sweep", 1, sweeps)
        assertFalse(model.state.value.askingForNotifications)
        model.requestNotifications()
        assertEquals("once per editor", listOf("request"), requests)

        // Never for None: a second asset lent with no reminder asks nothing.
        graph.assets.upsert(assetRow("ladder", name = "Example Ladder"))
        val none = editor(assetId = "ladder", granted = false)
        none.ready()
        none.onPicked(RENTALS_PICK)
        none.settled()
        val noneSaved = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { none.saved.first() }
        none.save()
        noneSaved.await()
        assertFalse(none.state.value.askingForNotifications)

        // Never when granted.
        graph.assets.upsert(assetRow("mower", name = "Example Mower"))
        val granted = editor(assetId = "mower", granted = true)
        granted.ready()
        granted.onPicked(SAMPLE_PICK)
        granted.onDueOn("2026-10-01")
        granted.onMode(LoanReminderMode.UNTIL_RETURNED)
        granted.settled()
        val grantedSaved = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { granted.saved.first() }
        granted.save()
        grantedSaved.await()
        assertFalse(granted.state.value.askingForNotifications)
        assertEquals("only the first editor ever requested", listOf("request"), requests)
    }

    /** "Not now" requests nothing, and the editor still sweeps once and finishes. */
    @Test fun notNowRequestsNothing() = runTest {
        drill()
        val model = editor(granted = false)
        model.ready()
        model.onPicked(SAMPLE_PICK)
        model.onDueOn("2026-10-01")
        model.onMode(LoanReminderMode.UNTIL_RETURNED)
        model.settled()
        val saved = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.saved.first() }

        model.save()
        model.state.first { it.askingForNotifications }
        model.dismissNotifications()

        assertEquals("drill", saved.await())
        assertEquals(emptyList<String>(), requests)
        assertEquals(1, sweeps)
        assertEquals("and wrote nothing more", 1, storedLoans().size)
    }

    /**
     * B3 review MAJOR-2, the owner's ruling: Back on P72-33 is exactly "Not now". The dialog's dismissal —
     * Back, an outside tap, "Not now" — lands in `dismissNotifications`: nothing is requested, the sweep
     * runs once, `saved` once. A second dismissal, and an "OK" after it, do nothing more.
     */
    @Test fun aDismissalIsNotNowAndNeverSweepsTwice() = runTest {
        drill()
        val model = editor(granted = false)
        model.ready()
        model.onPicked(SAMPLE_PICK)
        model.onDueOn("2026-10-01")
        model.onMode(LoanReminderMode.ONCE)
        model.settled()
        val saved = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.saved.first() }

        model.save()
        model.state.first { it.askingForNotifications }
        val atTheQuestion = storedLoans().single()
        model.dismissNotifications()
        assertEquals("the first dismissal finished the form, with the asset", "drill", saved.await())
        val again = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.saved.first() }
        model.dismissNotifications()
        model.requestNotifications()
        testScheduler.advanceUntilIdle()

        assertEquals("the dismissals and the late OK requested nothing", emptyList<String>(), requests)
        assertEquals("one sweep", 1, sweeps)
        assertFalse("saved once: the second dismissal and the late OK finished nothing", again.isCompleted)
        again.cancel()
        assertEquals("the loan is as it was at the question", listOf(atTheQuestion), storedLoans())
        assertFalse(model.state.value.askingForNotifications)

        // An edit of a stored None loan to Until returned, then one dismissal: one more sweep, no request.
        graph.assets.upsert(assetRow("ladder", name = "Example Ladder"))
        graph.loans.upsert(loanRow("l2", "ladder", lentOn = "2026-09-01", dueOn = "2026-10-05"))
        val edit = editor(loanId = "l2", assetId = "ladder", granted = false)
        edit.ready()
        edit.onMode(LoanReminderMode.UNTIL_RETURNED)
        edit.save()
        edit.state.first { it.askingForNotifications }
        edit.dismissNotifications()
        testScheduler.advanceUntilIdle()
        assertEquals("the edit swept once more", 2, sweeps)
        assertEquals(emptyList<String>(), requests)
    }

    /** R72-15: once for a new loan and once for a moved due date or reminder; never for notes or the lent date. */
    @Test fun aNewLoanOrMovedDueOrModeSweepsOnceAndNotesNever() = runTest {
        drill()
        val fresh = editor()
        fresh.ready()
        fresh.onPicked(SAMPLE_PICK)
        fresh.settled()
        val made = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { fresh.saved.first() }
        fresh.save()
        made.await()
        assertEquals("a new loan, even with no reminder", 1, sweeps)
        val id = storedLoans().single().id.value

        suspend fun edited(change: LoanEditViewModel.() -> Unit) {
            val edit = editor(loanId = id)
            edit.ready()
            edit.change()
            val done = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { edit.saved.first() }
            edit.save()
            done.await()
        }

        edited { onNotes("With the case") }
        assertEquals("notes never", 1, sweeps)
        edited { onLentOn("2026-09-19") }
        assertEquals("the lent date never", 1, sweeps)
        edited { onDueOn("2026-10-01") }
        assertEquals("a moved due date", 2, sweeps)
        edited { onMode(LoanReminderMode.ONCE) }
        assertEquals("a moved reminder", 3, sweeps)
        edited { onDueOn("2026-10-02") }
        assertEquals(4, sweeps)
        assertEquals("With the case", storedLoans().single().notes)
        assertEquals("2026-09-19", storedLoans().single().lentOn)
    }
}
