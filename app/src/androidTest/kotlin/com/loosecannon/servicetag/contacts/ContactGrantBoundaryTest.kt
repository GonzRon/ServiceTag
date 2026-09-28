package com.loosecannon.servicetag.contacts

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.contacts.TestContactSender.Command
import com.loosecannon.servicetag.core.model.CONTACT_LOOKUP_URI
import com.loosecannon.servicetag.ui.app
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The external boundary of the contact pick (#72, C25; R72-24; planning policy "Testing hierarchy",
 * layer 4, the #72 amendment): **a contact handed over by another UID with a one-shot read grant is
 * readable by ServiceTag, which holds no contacts permission** — and without the grant it is not.
 *
 * ServiceTag's own pick seam (`rememberContactPick`) is aimed at `:share-test-sender`'s picker — its
 * own package, its own UID, holding the contacts permissions this test grants it — and `launch()` is
 * called from an activity of ServiceTag's process. The sender seeds its fictional, account-less
 * contact and answers; the result reaches the production callback, and the graph's production reader
 * (`ResolverContactRowQuery` under `PickedContactReader`) reads it. Nothing is chosen, typed or pressed,
 * and the Contacts app is never driven.
 *
 * **Two cases, and that is the cap.** Everything the lend form and the section do with a pick is
 * proved in process (`PickedContactReaderTest`, `LoanEditViewModelTest`, `LoanReturnTest`,
 * `LendingSectionDeviceTest`); the codec against the framework is `ContactLinkContractTest`.
 *
 * **The tripwire is the timeout**, as `ShareBoundaryTest`'s: 20 s a case, set-up and tear-down
 * included. **The ungranted case runs first** (`NAME_ASCENDING`; its name sorts first), so every run
 * shows the refusal before any grant has been made in the run.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
// Load-bearing: the no-grant case runs before any grant is made, and its name sorts first. A renamed
// or added case has to keep it first.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ContactGrantBoundaryTest {

    @get:Rule(order = 0) val timeout: Timeout = Timeout.seconds(20)

    @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()

    /** The sender may write and read its fixture; ServiceTag holds neither contacts permission. */
    @Before fun grantTheSenderAndNotServiceTag() {
        TestContactSender.grantItsPermissions()
        val context = app
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).forEach { permission ->
            assertEquals(
                "ServiceTag's UID holds no $permission",
                PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(permission),
            )
        }
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertFalse("and never asks for it", Manifest.permission.READ_CONTACTS in requested)
    }

    /** The fixture contact goes whatever happened above. */
    @After fun forgetTheFixture() = TestContactSender.forget(rule.activity)

    /**
     * Aims ServiceTag's seam at the sender with [command], calls `launch()` and waits for the production
     * callback to hand the result to the graph's reader. Answers the URI the sender returned and what
     * it read as.
     */
    private fun pickThroughTheSender(command: Command): Pair<String, PickedContact> {
        val answered = AtomicReference<Pair<String, PickedContact>?>(null)
        var pick: ContactPick? = null
        rule.setContent {
            pick = rememberContactPick(
                onPicked = { uri -> answered.set(uri to app.graph.pickedContactReader.read(uri)) },
                onNoPicker = { throw AssertionError("the sender's picker did not resolve; is it installed?") },
                contract = TestContactSender.contract(command),
            )
        }
        rule.waitForIdle()
        rule.runOnUiThread { pick!!.launch() }
        rule.waitUntil(ANSWER_MS) { answered.get() != null }
        return answered.get()!!
    }

    /**
     * Boundary: the sender's real lookup URI **without** a grant. ServiceTag cannot read it — the
     * provider refuses the query — and the pick reads as `Unreadable` (P72-46), not a crash.
     */
    @Test fun aPickWithoutAGrantIsUnreadable() {
        val (uri, read) = pickThroughTheSender(Command.PICK_CONTACT_NO_GRANT)

        assertTrue("a real lookup URI arrived: $uri", CONTACT_LOOKUP_URI.matches(uri))
        assertEquals(PickedContact.Unreadable, read)
    }

    /**
     * Boundary: the same pick **with** the sender's one-shot read grant. ServiceTag, holding no contacts
     * permission, reads the organisation-only contact's name — "Example Rentals Ltd" — and the codec's
     * link from its real `_ID` and `LOOKUP_KEY` is the very URI the provider handed out.
     */
    @Test fun anotherUidsPickIsReadThroughItsOneShotGrant() {
        val (uri, read) = pickThroughTheSender(Command.PICK_CONTACT)

        assertEquals(PickedContact.Picked(lookupUri = uri, displayName = "Example Rentals Ltd"), read)
        assertTrue(CONTACT_LOOKUP_URI.matches(uri))
    }

    private companion object {
        const val ANSWER_MS = 10_000L
    }
}
