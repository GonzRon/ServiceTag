package com.loosecannon.servicetag.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #72 (C15; R72-3; §3 row 26): what a pick reads as, through a fake [ContactRowQuery] — the seam
 * `ResolverContactRowQuery` fills on the phone. A person reads their name and the link; a company-only
 * contact reads its organisation; a blank name is P72-30's `NoName`; no row, a refused read or a key
 * the codec cannot carry is P72-46's `Unreadable`, logged. Every name and key is fictional.
 */
class PickedContactReaderTest {

    private val picked = "content://com.android.contacts/contacts/lookup/0r5-EXAMPLEKEY/5"

    private fun readerOf(row: ContactRow?, logs: MutableList<String> = mutableListOf()) =
        PickedContactReader(ContactRowQuery { row }) { reason -> logs += reason }

    @Test fun aPersonsNameAndLookupUri() {
        val read = readerOf(ContactRow(5, "0r5-EXAMPLEKEY", "  Sample Borrower ")).read(picked)
        assertEquals(PickedContact.Picked(picked, "Sample Borrower"), read)
    }

    /** AC 2: the provider names a company-only contact by its organisation; the reader keeps that. */
    @Test fun aCompanyOnlyContactReadsItsOrganisation() {
        val read = readerOf(ContactRow(9, "0r9-EXAMPLERENTALS", "Example Rentals Ltd"))
            .read("content://com.android.contacts/contacts/lookup/0r9-EXAMPLERENTALS/9")
        assertEquals(
            PickedContact.Picked("content://com.android.contacts/contacts/lookup/0r9-EXAMPLERENTALS/9", "Example Rentals Ltd"),
            read,
        )
    }

    @Test fun aBlankNameIsNoName() {
        assertEquals(PickedContact.NoName, readerOf(ContactRow(5, "0r5-EXAMPLEKEY", "   ")).read(picked))
        assertEquals(PickedContact.NoName, readerOf(ContactRow(5, "0r5-EXAMPLEKEY", null)).read(picked))
    }

    @Test fun noRowASecurityExceptionOrABlankKeyIsUnreadable() {
        val logs = mutableListOf<String>()
        assertEquals("no row", PickedContact.Unreadable, readerOf(null, logs).read(picked))

        val denied = PickedContactReader(ContactRowQuery { throw SecurityException("Permission Denial") }) { logs += it }
        assertEquals("a refused read", PickedContact.Unreadable, denied.read(picked))

        assertEquals("a blank key", PickedContact.Unreadable, readerOf(ContactRow(5, "", "Sample Borrower"), logs).read(picked))
        assertEquals("no key", PickedContact.Unreadable, readerOf(ContactRow(5, null, "Sample Borrower"), logs).read(picked))
        assertEquals("a key with a '/'", PickedContact.Unreadable, readerOf(ContactRow(5, "0r5/X", "Sample Borrower"), logs).read(picked))

        assertEquals("each is logged", 5, logs.size)
        assertTrue("and no log line names the URI", logs.none { it.contains("content://") })
    }

    /**
     * B3 review MAJOR-1: a refused or failed read logs a fixed reason and never the exception — the
     * platform's permission denial and a provider's "Unknown URI" both name the lookup URI, and a lookup
     * key can embed a contact's normalised name. What reaches the log holds no `content://` and no part
     * of the key — and the log's type admits no `Throwable` at all.
     */
    @Test fun aRefusedOrFailedReadLogsNoPartOfTheUri() {
        val thrown = listOf(
            SecurityException(
                "Permission Denial: reading com.android.providers.contacts.ContactsProvider2 uri $picked " +
                    "from pid=1, uid=2 requires android.permission.READ_CONTACTS, or grantUriPermission()",
            ),
            IllegalArgumentException("Unknown URI $picked"),
        )
        thrown.forEach { failure ->
            val logged = mutableListOf<String>()
            val reader = PickedContactReader(ContactRowQuery { throw failure }) { reason -> logged += reason }

            assertEquals(PickedContact.Unreadable, reader.read(picked))
            assertEquals("one log line for ${failure::class.simpleName}", 1, logged.size)
            assertTrue("no URI in ${logged.single()}", logged.none { it.contains("content://") })
            assertTrue("no key in ${logged.single()}", logged.none { it.contains("EXAMPLEKEY") })
        }
    }
}
