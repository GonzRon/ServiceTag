package com.loosecannon.servicetag.ui.loan

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.contacts.ContactRow
import com.loosecannon.servicetag.contacts.ContactRowQuery
import com.loosecannon.servicetag.contacts.PickedContactReader
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.LoanTerms
import com.loosecannon.servicetag.reminders.LOAN_NOTIFICATION_RATIONALE
import com.loosecannon.servicetag.reminders.NotificationPermission
import com.loosecannon.servicetag.ui.app
import com.loosecannon.servicetag.ui.asset.AssetDetailScreen
import com.loosecannon.servicetag.ui.awaitText
import com.loosecannon.servicetag.ui.clearInstall
import com.loosecannon.servicetag.ui.condition.CONDITION_TITLE
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.maintenance.NOT_NOW
import com.loosecannon.servicetag.ui.maintenance.ReminderReconcile
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val WAIT_MS = 10_000L

/**
 * #72 (C15–C18; §3 row 32): the Lending section, the lend form and the return dialog on a real Compose
 * tree, over the production graph.
 *
 * **How the pick is driven without Contacts.** `rememberContactPick` resolves its registry through
 * `LocalActivityResultRegistryOwner` (the `AssetEditorKeyDocumentsTest` seam), so "Choose from Contacts"
 * gets a fictional lookup URI straight back, no picker involved; the lend form's view model is planted
 * in the activity's store under the screen's own key with a fake contact seam that reads that URI as
 * "Sample Borrower". The foreign-UID pick itself is `ContactGrantBoundaryTest`'s. Nothing here drives a
 * system dialog: the rationale case never taps "OK".
 *
 * Emulator only (`emulator-5554`) — the suite wipes app data.
 */
@RunWith(AndroidJUnit4::class)
class LendingSectionDeviceTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun freshInstall() = clearInstall()

    private val picked = "content://com.android.contacts/contacts/lookup/0r5-EXAMPLEKEY/5"

    private val fakeReader = PickedContactReader(
        ContactRowQuery { uri -> if (uri == picked) ContactRow(5, "0r5-EXAMPLEKEY", "Sample Borrower") else null },
    )

    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, picked.toUri() as O)
            }
        }
    }

    /** The asset the lend form is open on, or null while the detail screen is up. */
    private var editing by mutableStateOf<String?>(null)

    private var sweeps = 0
    private val requests = mutableListOf<String>()

    private fun permission(granted: Boolean) = object : NotificationPermission {
        override fun granted(): Boolean = granted
        override fun shouldExplain(): Boolean = false
        override suspend fun request(): Boolean = false.also { requests += "request" }
    }

    /** The detail screen of [assetId]; "Lend out" swaps in the lend form, whose save or ✕ swaps back. */
    private fun host(assetId: String) {
        rule.setContent {
            ServiceTagTheme {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                    val on = editing
                    if (on != null) {
                        LoanEditScreen(
                            graph = app.graph, assetId = on, loanId = null,
                            onDone = { editing = null }, onBack = { editing = null },
                        )
                    } else {
                        AssetDetailScreen(
                            graph = app.graph,
                            assetId = assetId,
                            onBack = {}, onEdit = {}, onSetup = {}, onWriteTag = {}, onBackup = {},
                            onLogEvent = { _, _ -> }, onOpenEvent = {}, onOpenAsset = {}, onAddComponent = {},
                            onAddSchedule = {}, onLogOutcome = { _, _ -> }, onOpenSettings = {},
                            onOpenSchedule = {}, onOpenGroup = {},
                            onLendOut = { editing = it },
                        )
                    }
                }
            }
        }
    }

    /** The lend form's view model, planted under the screen's own key with the fake contact seam. */
    private fun plantTheLendForm(assetId: String, granted: Boolean) {
        rule.runOnUiThread {
            val g = app.graph
            val planted = LoanEditViewModel(
                g.assets, g.loans, g.lendAsset, g.updateLoan, fakeReader, g.today, AssetId(assetId), null,
                notifications = permission(granted),
                reconcile = ReminderReconcile { sweeps++ },
            )
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = planted as T
            }
            ViewModelProvider(rule.activity, factory)["new-loan-$assetId", LoanEditViewModel::class.java]
        }
    }

    private fun create(name: String): String =
        runBlocking { app.graph.createAsset.run(AssetCommand(name = name)).id.value }

    private fun loans(): List<AssetLoan> = runBlocking { app.graph.loans.all() }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    private fun top(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    /** Lend out → Choose from Contacts → a due date → Save loan, and the open block and the plate say so. */
    @Test fun lendThroughAFakePickerShowsTheOpenBlockAndPlate() {
        val id = create("Example Drill")
        val due = app.graph.today.localDate().plusDays(7)
        plantTheLendForm(id, granted = true)
        host(id)

        rule.awaitText("LENDING")
        check(top("WARRANTY") < top("LENDING")) { "Lending is right after Warranty" }
        check(top("LENDING") < top(CONDITION_TITLE)) { "and before Condition" }
        rule.onNodeWithText(LEND_OUT).performScrollTo().performClick()

        rule.awaitText(CHOOSE_FROM_CONTACTS)
        rule.onNodeWithText(LEND_OUT).assertIsDisplayed()
        rule.onNodeWithText(CHOOSE_FROM_CONTACTS).performClick()
        rule.awaitText("Sample Borrower")
        field(DUE_BACK).performScrollTo().performTextReplacement(due.toString())
        rule.onNodeWithText(SAVE_LOAN).performScrollTo().performClick()

        rule.waitUntil(WAIT_MS) { editing == null }
        rule.awaitText("Lent to Sample Borrower")
        // The plate's badge and the block's, each drawn upper-case with the ratified phrase described.
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText("LENT OUT", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        rule.onAllNodesWithText("LENT OUT", useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText(dueBackLine(displayDate(due))).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(MARK_RETURNED).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(EDIT_LOAN).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(IF_THE_CONTACT_DOES_NOT_OPEN).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(LEND_OUT).assertCountEquals(0)

        val loan = loans().single()
        assertEquals("Sample Borrower", loan.borrowerName)
        assertEquals(picked, loan.contactLookupUri)
        assertEquals(due.toString(), loan.dueOn)
        assertEquals("a new loan swept once", 1, sweeps)
    }

    /** Mark returned → the dialog on today → confirm: the loan moves to Past loans and Lend out is back. */
    @Test fun markReturnedMovesItToPastLoans() {
        val id = create("Example Drill")
        val today = app.graph.today.localDate()
        runBlocking {
            app.graph.lendAsset.run(
                AssetId(id), "Sample Borrower",
                LoanTerms(lentOn = today.minusDays(3).toString(), dueOn = null, reminderMode = LoanReminderMode.NONE),
                picked,
            )
        }
        host(id)

        rule.awaitText("Lent to Sample Borrower")
        rule.onNodeWithText(NO_DUE_DATE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(MARK_RETURNED).performScrollTo().performClick()
        rule.awaitText(MARK_RETURNED_QUESTION)
        rule.onNode(hasText(RETURNED_ON) and hasSetTextAction()).assertIsDisplayed()
        rule.onAllNodesWithText(MARK_RETURNED).filterToOne(hasAnyAncestor(isDialog())).performClick()

        rule.awaitText(PAST_LOANS)
        rule.onNodeWithText(returnedOnLine(displayDate(today))).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(LEND_OUT).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("LENT OUT", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText(MARK_RETURNED).assertCountEquals(0)
        assertEquals(today.toString(), loans().single().returnedOn)
    }

    /**
     * C17, R72-9: a save that first sets a reminder while notifications are off draws P72-33 with "OK"
     * and "Not now", after the loan is written; "Not now" requests nothing, writes nothing more and
     * finishes the form. "OK" is never tapped: it hands over to the system dialog.
     */
    @Test fun theRationaleTextAndNotNowWritesNothingMore() {
        val id = create("Example Drill")
        val due = app.graph.today.localDate().plusDays(7)
        plantTheLendForm(id, granted = false)
        editing = id
        host(id)

        rule.awaitText(CHOOSE_FROM_CONTACTS)
        rule.onNodeWithText(CHOOSE_FROM_CONTACTS).performClick()
        rule.awaitText("Sample Borrower")
        field(DUE_BACK).performScrollTo().performTextReplacement(due.toString())
        rule.onNodeWithText(ONCE).performScrollTo().performClick()
        rule.onNodeWithText(SAVE_LOAN).performScrollTo().performClick()

        rule.awaitText(LOAN_NOTIFICATION_RATIONALE)
        rule.onNodeWithText(LOAN_NOTIFICATION_RATIONALE).assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed()
        rule.onNodeWithText(NOT_NOW).assertIsDisplayed()
        val atTheQuestion = loans().single()
        assertEquals("the loan is written before the question", LoanReminderMode.ONCE, atTheQuestion.reminderMode)
        assertTrue("nothing requested yet", requests.isEmpty())

        rule.onNodeWithText(NOT_NOW).performClick()
        rule.waitUntil(WAIT_MS) { editing == null }
        assertTrue("\"Not now\" requests nothing", requests.isEmpty())
        assertEquals("and writes nothing more", listOf(atTheQuestion), loans())
        assertEquals("the new loan swept once, after the answer", 1, sweeps)
        rule.onAllNodesWithText(LOAN_NOTIFICATION_RATIONALE).assertCountEquals(0)
        assertNull(editing)
    }
}
