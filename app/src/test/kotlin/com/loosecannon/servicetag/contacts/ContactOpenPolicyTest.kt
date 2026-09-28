package com.loosecannon.servicetag.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #72 (C15; R72-4; §3 row 26): "Open contact" launches only a link the shared rule matches, and a
 * phone with nothing to take it says P72-20. The launch is a recorder: nothing here is Android.
 */
class ContactOpenPolicyTest {

    @Test fun onlyARuleMatchingUriIsLaunched() {
        val launched = mutableListOf<String>()
        val launch: (String) -> Boolean = { launched += it; true }

        val linked = "content://com.android.contacts/contacts/lookup/0r5-EXAMPLEKEY/5"
        val keyOnly = "content://com.android.contacts/contacts/lookup/0r5-EXAMPLEKEY"
        assertTrue(openContact(linked, launch))
        assertTrue(openContact(keyOnly, launch))

        val refused = listOf(
            null,
            "",
            "content://media/external/images/media/5",
            "content://com.android.contacts/contacts/5",
            "content://com.android.contacts/contacts/lookup/0r5-EXAMPLEKEY/5?directory=1",
            "content://com.android.contacts/contacts/lookup/0r5-EXAMPLEKEY/5#x",
            "https://example.invalid/contact",
            "tel:5550100",
        )
        refused.forEach { uri ->
            assertFalse("$uri is not opened", openContact(uri, launch))
            assertNull("$uri is refused by the policy", ContactOpenPolicy.decide(uri))
        }
        assertEquals("only the two rule-matching links reached the launch", listOf(linked, keyOnly), launched)
    }

    @Test fun noHandlerSaysP72_20() {
        assertEquals("No app can open this contact", NO_APP_CAN_OPEN_THIS_CONTACT)
        val opened = openContact("content://com.android.contacts/contacts/lookup/0r5-EXAMPLEKEY/5") { false }
        assertFalse(opened)
        assertEquals(NO_APP_CAN_OPEN_THIS_CONTACT, contactOpenFailure(opened))
        assertNull("an opened contact says nothing", contactOpenFailure(true))
        assertFalse("the sentence names no URI", NO_APP_CAN_OPEN_THIS_CONTACT.contains("content:"))
    }
}
