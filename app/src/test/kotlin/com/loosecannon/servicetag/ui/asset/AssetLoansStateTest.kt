package com.loosecannon.servicetag.ui.asset

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.LoanStanding
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.testing.FakeGraph
import com.loosecannon.servicetag.testing.assetRow
import com.loosecannon.servicetag.testing.loanRow
import com.loosecannon.servicetag.ui.loan.LoanAction
import com.loosecannon.servicetag.ui.loan.LoanFacts
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
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
 * #72 (C16, C19; R72-11, R72-12, R72-18, R72-22; §3 row 29): the Lending section and the plate as the
 * detail state carries them, over a Room-backed [FakeGraph] and the real `observeForAsset`, judged by
 * `Today`. "Lend out" only in service with nothing lent; the open block on every lifecycle, following
 * its standing; the reminder line only with a date and a mode; P72-17 for a name-only loan; "Past loans"
 * newest return first with nothing to press; the plate's loan badge after the lifecycle facts. Every
 * borrower is fictional.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetLoansStateTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var graph: FakeGraph

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        graph = FakeGraph(queryContext = StandardTestDispatcher(scheduler))
        graph.today = LocalDate.parse("2026-09-20")
    }

    @After fun tearDown() {
        graph.close()
        Dispatchers.resetMain()
    }

    private fun detailModel(id: AssetId) = AssetDetailViewModel(
        graph.assets, graph.tags,
        graph.definitions, graph.profiles, graph.events,
        graph.schedules, graph.scheduleStates, graph.groups, graph.dueReadModel,
        graph.conditions, graph.seasonActivations, graph.healthSubjects, graph.attachments,
        graph.assetHealthReadModel, graph.getAssetSeason, graph.recordSeasonActivation,
        graph.archiveAsset, graph.retireAsset, graph.deleteAsset,
        graph.applyTemplate, graph.uow, graph.clock, graph.todayPort, id,
        serviceCases = graph.serviceCases,
        loans = graph.loans,
    )

    private suspend fun TestScope.loaded(id: String): AssetDetailState {
        val vm = detailModel(AssetId(id))
        backgroundScope.launch { vm.state.collect() }
        return vm.state.first { it != null }!!
    }

    private suspend fun TestScope.loans(id: String): LoanFacts = loaded(id).loans

    @Test fun noLoanInServiceOffersLendOut() = runTest {
        graph.assets.upsert(assetRow("drill", name = "Example Drill"))

        val facts = loans("drill")

        assertTrue("the section is drawn", facts.shown)
        assertTrue("P72-14", facts.offersLendOut)
        assertNull(facts.open)
        assertNull(facts.standing)
        assertEquals(emptyList<Any>(), facts.history)
    }

    /** R72-11: out of service, with nothing ever lent, there is no section and no "Lend out". */
    @Test fun noLoanOutOfServiceDrawsNoSection() = runTest {
        graph.assets.upsert(assetRow("retired", name = "Example Drill", retiredOn = "2026-09-01"))
        graph.assets.upsert(assetRow("archived", name = "Example Ladder", status = AssetStatus.ARCHIVED))

        listOf("retired", "archived").forEach { id ->
            val facts = loans(id)
            assertFalse("$id: no section", facts.shown)
            assertFalse("$id: no Lend out", facts.offersLendOut)
        }
    }

    /** The due day is still lent out (P72-6); the day after is P72-2 with P72-7; no date is P72-8. */
    @Test fun theOpenBlockFollowsTheStanding() = runTest {
        graph.assets.upsert(assetRow("drill", name = "Example Drill"))
        graph.assets.upsert(assetRow("ladder", name = "Example Ladder"))
        graph.loans.upsert(loanRow("l1", "drill", lentOn = "2026-09-01", dueOn = "2026-09-20"))
        graph.loans.upsert(loanRow("l2", "ladder", lentOn = "2026-09-02", borrower = "Example Rentals Ltd"))

        val dueDay = loans("drill")
        assertEquals(LoanStanding.LENT_OUT, dueDay.standing)
        assertEquals(LoanStanding.LENT_OUT, dueDay.open!!.standing)
        assertEquals(listOf("Lent to Sample Borrower", "Lent 1 Sep 2026", "Due back 20 Sep 2026"), dueDay.open!!.lines)
        assertFalse("nothing to lend while lent", dueDay.offersLendOut)

        graph.today = LocalDate.parse("2026-09-21")
        val after = loans("drill")
        assertEquals(LoanStanding.OVERDUE, after.standing)
        assertEquals(listOf("Lent to Sample Borrower", "Lent 1 Sep 2026", "Was due back 20 Sep 2026"), after.open!!.lines)

        val noDate = loans("ladder")
        assertEquals(LoanStanding.LENT_OUT, noDate.standing)
        assertEquals(listOf("Lent to Example Rentals Ltd", "Lent 2 Sep 2026", "No due date"), noDate.open!!.lines)
    }

    /** P72-9 for Once and P72-10 for Until returned, only with a due date and a mode. */
    @Test fun theReminderLineOnlyWithADateAndAMode() = runTest {
        listOf("a", "b", "c", "d").forEach { graph.assets.upsert(assetRow(it, name = "Example $it")) }
        graph.loans.upsert(loanRow("l-a", "a", lentOn = "2026-09-01", dueOn = "2026-10-01", mode = LoanReminderMode.ONCE))
        graph.loans.upsert(loanRow("l-b", "b", lentOn = "2026-09-01", dueOn = "2026-10-01", mode = LoanReminderMode.UNTIL_RETURNED))
        graph.loans.upsert(loanRow("l-c", "c", lentOn = "2026-09-01", dueOn = "2026-10-01"))
        graph.loans.upsert(loanRow("l-d", "d", lentOn = "2026-09-01"))

        assertEquals("Reminder: when it is due back", loans("a").open!!.reminderLine)
        assertEquals("Reminder: every day until returned", loans("b").open!!.reminderLine)
        assertNull("no mode", loans("c").open!!.reminderLine)
        assertNull("no date", loans("d").open!!.reminderLine)
    }

    /** R72-4: a name-only loan says P72-17 and offers no "Open contact"; a linked one says P72-19 under it. */
    @Test fun aNameOnlyLoanSaysNoContactLinked() = runTest {
        graph.assets.upsert(assetRow("drill", name = "Example Drill"))
        graph.assets.upsert(assetRow("ladder", name = "Example Ladder"))
        graph.loans.upsert(loanRow("l1", "drill", lentOn = "2026-09-01", lookupUri = null))
        graph.loans.upsert(loanRow("l2", "ladder", lentOn = "2026-09-01"))

        val nameOnly = loans("drill").open!!
        assertEquals("No contact linked", nameOnly.contactLine)
        assertNull(nameOnly.lookupUri)
        assertEquals(
            listOf(LoanAction.CHOOSE_FROM_CONTACTS, LoanAction.MARK_RETURNED, LoanAction.EDIT_LOAN),
            nameOnly.actions,
        )

        val linked = loans("ladder").open!!
        assertEquals("If the contact does not open, choose it again.", linked.contactLine)
        assertEquals(
            listOf(LoanAction.OPEN_CONTACT, LoanAction.CHOOSE_FROM_CONTACTS, LoanAction.MARK_RETURNED, LoanAction.EDIT_LOAN),
            linked.actions,
        )
    }

    /** R72-12: retired or archived while lent, the loan is still shown, returnable and correctable — no Lend out. */
    @Test fun aRetiredAssetsLoanCanStillBeReturned() = runTest {
        graph.assets.upsert(assetRow("retired", name = "Example Drill", retiredOn = "2026-09-10"))
        graph.assets.upsert(assetRow("archived", name = "Example Ladder", status = AssetStatus.ARCHIVED))
        graph.loans.upsert(loanRow("l1", "retired", lentOn = "2026-09-01", dueOn = "2026-09-05"))
        graph.loans.upsert(loanRow("l2", "archived", lentOn = "2026-09-01"))

        listOf("retired", "archived").forEach { id ->
            val facts = loans(id)
            assertTrue("$id: drawn", facts.shown)
            assertFalse("$id: never Lend out", facts.offersLendOut)
            val open = facts.open
            assertTrue("$id: the open block", open != null)
            assertTrue("$id: Mark returned", LoanAction.MARK_RETURNED in open!!.actions)
            assertTrue("$id: Edit loan", LoanAction.EDIT_LOAN in open.actions)
            assertTrue("$id: relink", LoanAction.CHOOSE_FROM_CONTACTS in open.actions)
        }
        assertEquals(LoanStanding.OVERDUE, loans("retired").standing)
    }

    /** R72-18: Past loans newest return first, then by id; each frozen — nothing to press. */
    @Test fun historyNewestReturnedFirstWithNoActions() = runTest {
        graph.assets.upsert(assetRow("drill", name = "Example Drill", retiredOn = "2026-09-15"))
        graph.loans.upsert(loanRow("l2", "drill", lentOn = "2026-04-01", returnedOn = "2026-05-01"))
        graph.loans.upsert(loanRow("l1", "drill", lentOn = "2026-06-01", returnedOn = "2026-07-01", notes = "Returned clean"))
        graph.loans.upsert(loanRow("l0", "drill", lentOn = "2026-06-10", returnedOn = "2026-07-01", borrower = "Example Rentals Ltd"))

        val facts = loans("drill")

        assertTrue("a retired asset with only history still draws its section", facts.shown)
        assertNull(facts.open)
        assertEquals(listOf("l0", "l1", "l2"), facts.history.map { it.loanId })
        assertEquals(
            listOf("Lent to Sample Borrower", "Lent 1 Jun 2026", "Returned 1 Jul 2026"),
            facts.history[1].lines,
        )
        assertEquals("Returned clean", facts.history[1].notes)
        assertTrue("no action on a returned loan", facts.history.all { it.actions.isEmpty() })
    }

    /** C16, R72-22: the loan badge after retired and archived, before the season phase; the condition first. */
    @Test fun thePlateCarriesLentOrOverdueAfterTheLifecycleFacts() = runTest {
        graph.assets.upsert(
            assetRow("drill", name = "Example Drill", retiredOn = "2026-09-10", status = AssetStatus.ARCHIVED),
        )
        graph.assets.upsert(
            assetRow("mower", name = "Example Mower", seasonMode = SeasonMode.CALENDAR, seasonStart = "04-01", seasonEnd = "10-31"),
        )
        graph.assets.upsert(assetRow("ladder", name = "Example Ladder"))
        graph.loans.upsert(loanRow("l1", "drill", lentOn = "2026-09-01", dueOn = "2026-09-19"))
        graph.loans.upsert(loanRow("l2", "mower", lentOn = "2026-09-01", dueOn = "2026-09-30"))

        assertEquals(
            listOf(PlateFact.Condition(null), PlateFact.Retired, PlateFact.Archived("Archived"), PlateFact.Lent(overdue = true)),
            loaded("drill").plate,
        )
        assertEquals(
            listOf(PlateFact.Condition(null), PlateFact.Lent(overdue = false), PlateFact.InSeason),
            loaded("mower").plate,
        )
        assertEquals("nothing lent, no loan badge", listOf(PlateFact.Condition(null)), loaded("ladder").plate)
    }

    /**
     * C19 (beyond the matrix): the Assets list joins the one `observeOpen` read to its rows by asset id —
     * a row's standing, on every lifecycle, and none on an asset with only returned loans.
     */
    @Test fun theAssetsListJoinsTheOpenLoanByAsset() = runTest {
        graph.assets.upsert(assetRow("drill", name = "Example Drill"))
        graph.assets.upsert(assetRow("ladder", name = "Example Ladder"))
        graph.assets.upsert(assetRow("mower", name = "Example Mower"))
        graph.loans.upsert(loanRow("l1", "drill", lentOn = "2026-09-01", dueOn = "2026-09-19"))
        graph.loans.upsert(loanRow("l2", "ladder", lentOn = "2026-09-01", dueOn = "2026-09-20"))
        graph.loans.upsert(loanRow("l3", "mower", lentOn = "2026-06-01", returnedOn = "2026-07-01"))

        val list = AssetsViewModel(
            graph.assets, graph.categories, graph.seasonActivations, graph.tags, graph.assetHealthReadModel,
            graph.todayPort, loans = graph.loans,
        )
        backgroundScope.launch { list.state.collect() }
        val rows = list.state.first { it.items.size == 3 }.items.associate { it.asset.name to it.loan }

        assertEquals(LoanStanding.OVERDUE, rows["Example Drill"])
        assertEquals("the due day is still lent out", LoanStanding.LENT_OUT, rows["Example Ladder"])
        assertNull("history alone is no badge", rows["Example Mower"])
    }
}
