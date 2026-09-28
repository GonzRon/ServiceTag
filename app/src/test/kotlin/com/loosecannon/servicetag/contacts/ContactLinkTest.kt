package com.loosecannon.servicetag.contacts

import com.loosecannon.servicetag.core.model.CONTACT_LOOKUP_URI
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #72 (C15; R72-4, M3; §3 row 25): the lookup-URI codec, on the JVM. It appends the key **verbatim**,
 * as `Contacts.getLookupUri` does, and refuses — null — any key it would have to encode to keep the
 * shared rule. `ContactLinkContractTest` holds the same outputs against the framework on the emulator.
 * Every key here is fictional.
 */
class ContactLinkTest {

    @Test fun lookupUriOfAppendsTheKeyVerbatim() {
        val carried = listOf("0r1-ABC", "a.b", "x%2Fy")
        carried.forEach { key ->
            assertEquals(
                "the key $key is appended unchanged",
                "content://com.android.contacts/contacts/lookup/$key/42",
                ContactLink.lookupUriOf(42, key),
            )
        }
        val refused = listOf("a/b", "a b", "Exämple", "a?b", "a#b", "a\tb", "")
        refused.forEach { key -> assertNull("the key \"$key\" is refused", ContactLink.lookupUriOf(42, key)) }
        assertNull("a negative id is no contact row", ContactLink.lookupUriOf(-1, "0r1-ABC"))
    }

    @Test fun theOutputMatchesTheRule() {
        val keys = listOf("0r1-ABC", "a.b", "x%2Fy", "0r7-2F3C4A", "!~")
        keys.forEach { key ->
            val uri = ContactLink.lookupUriOf(7, key)
            assertTrue("$uri matches CONTACT_LOOKUP_URI", uri != null && CONTACT_LOOKUP_URI.matches(uri))
        }
        // Every printable ASCII character the codec accepts on its own still yields a rule match.
        ('!'..'~').map { it.toString() }.forEach { key ->
            ContactLink.lookupUriOf(3, key)?.let { uri ->
                assertTrue("\"$key\" gave $uri, outside the rule", CONTACT_LOOKUP_URI.matches(uri))
            }
        }
    }

    /** C15, the grep's twin: the codec imports nothing from Android, so the JVM proof is the real one. */
    @Test fun theCodecImportsNoAndroidType() {
        val source = File(sourceRoot(), "contacts/ContactLink.kt").readText()
        assertTrue("no android import", source.lineSequence().none { it.startsWith("import android.") })
    }

    /** Gradle runs from the module directory and an IDE may use the repository root: walk up to it. */
    private fun sourceRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/kotlin/com/loosecannon/servicetag") }
            .first { it.isDirectory }
}
