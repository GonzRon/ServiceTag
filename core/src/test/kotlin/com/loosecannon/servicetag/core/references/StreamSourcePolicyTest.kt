package com.loosecannon.servicetag.core.references

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I-9, as strings: `content://` or refused, and never one of ServiceTag's own authorities. The
 * predicate lives here so `:app` can ask it **before** it constructs a `ByteSource` — a refused URI
 * is then never opened at all.
 */
class StreamSourcePolicyTest {

    private val policy = StreamSourcePolicy(setOf("com.loosecannon.servicetag.files"))

    @Test
    fun aFileStreamIsRefused() {
        assertFalse(policy.accepts("file", null))
        assertFalse(policy.accepts("file", "localhost"))
        assertFalse(policy.accepts("http", "example-mower.invalid"))
        assertFalse(policy.accepts("android-app", "com.example.reader"))
    }

    @Test
    fun ourOwnProviderIsRefusedWhateverTheCase() {
        assertFalse(policy.accepts("content", "com.loosecannon.servicetag.files"))
        assertFalse(policy.accepts("content", "COM.LOOSECANNON.SERVICETAG.FILES"))
        assertFalse(policy.accepts("CONTENT", "com.loosecannon.servicetag.files"))
    }

    @Test
    fun anotherProvidersContentUriIsAccepted() {
        assertTrue(policy.accepts("content", "com.android.providers.downloads.documents"))
        assertTrue(policy.accepts("CONTENT", "media"))
    }

    @Test
    fun aMissingOrBlankSchemeOrAuthorityIsRefused() {
        assertFalse(policy.accepts(null, "com.android.providers.downloads.documents"))
        assertFalse(policy.accepts("", "com.android.providers.downloads.documents"))
        assertFalse(policy.accepts("content", null))
        assertFalse(policy.accepts("content", "   "))
    }
}
