package com.loosecannon.servicetag.contacts

import android.provider.ContactsContract.Contacts
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.loosecannon.servicetag.contacts.TestContactSender.Command
import com.loosecannon.servicetag.ui.app
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #72 (C15; M3, R1 of the re-review; §3 row 27): a framework contract, with no contacts permission in
 * ServiceTag — **the pure codec's output is the framework's own** `Contacts.getLookupUri(id, key)`.
 * The JVM cannot say so: its `android.jar` is a stub on which that call answers null.
 *
 * Compared: every key of `ContactLinkTest`'s table the codec **accepts** (`0r1-ABC`, `a.b`, `x%2Fy` —
 * the last is where encoding the key would show), and a real key — the fictional contact
 * `:share-test-sender` seeds, whose `_ID` and `LOOKUP_KEY` ServiceTag reads through the sender's
 * one-shot grant. No blank key is compared: `getLookupUri` does not treat a blank key as absent, and
 * the codec refuses one by design.
 *
 * Emulator only (`emulator-5554`), never a phone.
 */
@RunWith(AndroidJUnit4::class)
class ContactLinkContractTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun forgetTheFixture() = TestContactSender.forget(rule.activity)

    @Test fun theCodecEqualsGetLookupUri() {
        listOf("0r1-ABC", "a.b", "x%2Fy").forEach { key ->
            assertEquals(
                "the key $key",
                Contacts.getLookupUri(42L, key).toString(),
                ContactLink.lookupUriOf(42L, key),
            )
        }

        val (handedOut, row) = aRealContactRow()
        val key = checkNotNull(row.lookupKey) { "the seeded contact has a lookup key" }
        val framework = Contacts.getLookupUri(row.id, key).toString()
        assertEquals("the seeded contact's real key", framework, ContactLink.lookupUriOf(row.id, key))
        assertEquals("and it is the URI the provider handed out", handedOut, framework)
        assertTrue("a real key the codec carries", ContactLink.lookupUriOf(row.id, key) != null)
    }

    /**
     * The sender's fixture, picked with its grant through ServiceTag's own seam, and its row read
     * through the production query — `_ID` and `LOOKUP_KEY` as primitives.
     */
    private fun aRealContactRow(): Pair<String, ContactRow> {
        TestContactSender.grantItsPermissions()
        val answered = AtomicReference<Pair<String, ContactRow?>?>(null)
        var pick: ContactPick? = null
        rule.setContent {
            pick = rememberContactPick(
                onPicked = { uri -> answered.set(uri to ResolverContactRowQuery(app.contentResolver).row(uri)) },
                onNoPicker = { throw AssertionError("the sender's picker did not resolve; is it installed?") },
                contract = TestContactSender.contract(Command.PICK_CONTACT),
            )
        }
        rule.waitForIdle()
        rule.runOnUiThread { pick!!.launch() }
        rule.waitUntil(10_000L) { answered.get() != null }
        val (uri, row) = answered.get()!!
        return uri to checkNotNull(row) { "the granted read found the seeded contact" }
    }
}
