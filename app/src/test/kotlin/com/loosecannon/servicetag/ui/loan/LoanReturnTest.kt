package com.loosecannon.servicetag.ui.loan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.testing.DENIED_PICK
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.NAMELESS_PICK
import com.loosecannon.servicetag.testing.RENTALS_PICK
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #72 (C18; R72-5, R72-15, R72-17, R72-18; §3 row 31): the Lending section's two writes, over a
 * Room-backed [FakeGraph], the real `ReturnLoan` and `RelinkLoanContact`, and a fake contact seam. A
 * return writes the loan's own row and then sweeps once; its refusals land under "Returned on". A relink
 * replaces the name and the link together, on the open loan only, and never sweeps; a pick that cannot
 * be read says P72-46, a nameless one P72-30. Every name is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoanReturnTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph
    private val store = ViewModelStore()
    private val sweptWith = mutableListOf<String?>()

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

    private fun actions(): LoanActionsViewModel = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = LoanActionsViewModel(
                graph.returnLoan, graph.relinkLoanContact, fakeContactReader(), graph.todayPort,
                // The sweep records the loan's return date as it stood when the sweep ran.
                reconcile = ReminderReconcile { sweptWith += graph.loans.get(AssetLoanId("l1"))?.returnedOn },
                io = StandardTestDispatcher(scheduler),
            ) as V
        },
    )["actions", LoanActionsViewModel::class.java]

    private suspend fun lent(returnedOn: String? = null, lookupUri: String? = null): AssetLoan {
        graph.assets.upsert(assetRow("drill", name = "Example Drill"))
        val row = loanRow(
            "l1", "drill", lentOn = "2026-09-01", dueOn = "2026-09-10", returnedOn = returnedOn,
            borrower = "Sample Borrower", lookupUri = lookupUri,
        )
        graph.loans.upsert(row)
        return row
    }

    private suspend fun stored(): AssetLoan = graph.loans.get(AssetLoanId("l1"))!!

    @Test fun returnWritesThenSweepsOnce() = runTest {
        lent()
        val model = actions()

        model.askReturn("l1")
        assertEquals("the date opens on today", "2026-09-20", model.returning.value!!.date)
        model.confirmReturn()
        model.returning.first { it == null }
        testScheduler.advanceUntilIdle()

        val returned = stored()
        assertEquals("the row stays, with its date", "2026-09-20", returned.returnedOn)
        assertEquals("Sample Borrower", returned.borrowerName)
        assertEquals("one sweep, after the write", listOf<String?>("2026-09-20"), sweptWith)
    }

    @Test fun refusalsOnReturnedOn() = runTest {
        lent()
        val model = actions()
        model.askReturn("l1")

        suspend fun refusedWith(date: String): String? {
            model.onReturnedOn(date)
            model.confirmReturn()
            return model.returning.first { it != null && !it.saving }!!.problem
        }

        assertEquals("Enter a date as YYYY-MM-DD", refusedWith("20-09-2026"))
        assertEquals("The return date cannot be before the day it was lent.", refusedWith("2026-08-31"))
        assertEquals(DATE_NOT_LATER_THAN_TODAY, refusedWith("2026-09-21"))
        assertNull("still open", stored().returnedOn)
        assertEquals("no sweep for a refusal", emptyList<String?>(), sweptWith)

        model.dismissReturn()
        assertNull("Cancel writes nothing", model.returning.value)
        assertNull(stored().returnedOn)
    }

    /** R72-5: link and name together, in one write; the dates, mode and notes do not move; no sweep. */
    @Test fun relinkReplacesNameAndLinkWithNoSweep() = runTest {
        val before = lent(lookupUri = null)
        val model = actions()

        model.relink("l1", RENTALS_PICK)
        testScheduler.advanceUntilIdle()

        val after = stored()
        assertEquals("Example Rentals Ltd", after.borrowerName)
        assertEquals(RENTALS_PICK, after.contactLookupUri)
        assertEquals(before.copy(borrowerName = after.borrowerName, contactLookupUri = after.contactLookupUri, updatedAt = after.updatedAt), after)
        assertEquals("a relink never sweeps", emptyList<String?>(), sweptWith)
    }

    /** R72-18: a returned loan is frozen — a relink of it writes nothing and says P72-32. */
    @Test fun relinkOnlyOnTheOpenLoan() = runTest {
        val returned = lent(returnedOn = "2026-09-15")
        val model = actions()
        val said = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }

        model.relink("l1", RENTALS_PICK)

        assertEquals("Could not save this loan.", said.await())
        assertEquals(returned, stored())
        assertEquals(
            "and the section offers nothing on it",
            emptyList<LoanAction>(),
            loanFactsOf(listOf(stored()), graph.today, inService = true).history.single().actions,
        )
    }

    /** C18, P72-45 (beyond the matrix): no contact picker on the phone — the relink says so and writes nothing. */
    @Test fun aRelinkWithNoPickerSaysP72_45() = runTest {
        val before = lent()
        val model = actions()
        val said = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }

        model.onNoPicker()

        assertEquals("No app can pick a contact", said.await())
        assertEquals(before, stored())
    }

    @Test fun aRelinkReadFailureSaysP72_46() = runTest {
        val before = lent()
        val model = actions()

        val unreadable = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }
        model.relink("l1", DENIED_PICK)
        assertEquals("Could not read this contact.", unreadable.await())

        val nameless = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { model.messages.first() }
        model.relink("l1", NAMELESS_PICK)
        assertEquals("This contact has no name to show.", nameless.await())

        assertEquals("nothing was written", before, stored())
        assertEquals(emptyList<String?>(), sweptWith)
    }
}
