package com.loosecannon.servicetag.core.model

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * #72 (C1; R72-4): [CONTACT_LOOKUP_URI], the one rule for a stored contact link — the loan commands, the
 * backup content check and the phone's pick codec all ask it. It takes the platform provider's lookup
 * URI, with or without the trailing contact id, and nothing else. Every key here is made up.
 */
class ContactLookupUriTest {

    private val base = "content://com.android.contacts/contacts/lookup"

    private fun matches(uri: String) = CONTACT_LOOKUP_URI.matches(uri)

    @Test
    fun theRuleTakesALookupKey() {
        for (key in listOf("0r1-EXAMPLEKEY", "a.b", "x%2Fy", "3789r2-4F2B")) {
            assertEquals(true, matches("$base/$key"), key)
        }
    }

    @Test
    fun theRuleTakesALookupKeyAndAnId() {
        for (uri in listOf("$base/0r1-EXAMPLEKEY/7", "$base/a.b/12", "$base/x%2Fy/3")) {
            assertEquals(true, matches(uri), uri)
        }
    }

    @Test
    fun theRuleRefusesAnotherAuthority() {
        for (uri in listOf(
            "content://media/external/images/media/7",
            "content://com.example.contacts/contacts/lookup/0r1-EXAMPLEKEY/7",
            "content://com.android.contactsX/contacts/lookup/0r1-EXAMPLEKEY/7",
            "https://com.android.contacts/contacts/lookup/0r1-EXAMPLEKEY/7",
            "com.android.contacts/contacts/lookup/0r1-EXAMPLEKEY/7",
        )) {
            assertEquals(false, matches(uri), uri)
        }
    }

    @Test
    fun theRuleRefusesABareContactId() {
        for (uri in listOf("content://com.android.contacts/contacts/7", "content://com.android.contacts/raw_contacts/7")) {
            assertEquals(false, matches(uri), uri)
        }
    }

    @Test
    fun theRuleRefusesAQueryOrAFragment() {
        for (uri in listOf("$base/0r1-EXAMPLEKEY/7?directory=0", "$base/0r1-EXAMPLEKEY?x=1", "$base/0r1-EXAMPLEKEY#top", "$base/0r1-EXAMPLEKEY/7#top")) {
            assertEquals(false, matches(uri), uri)
        }
    }

    @Test
    fun theRuleRefusesASlashInTheKeyAndAnEmptyKey() {
        for (uri in listOf("$base/a/b/7", "$base/a/b", "$base/", "$base//7", "$base/0r1-EXAMPLEKEY/7/8", "$base/0r1-EXAMPLEKEY/x7", "")) {
            assertEquals(false, matches(uri), uri)
        }
    }
}
