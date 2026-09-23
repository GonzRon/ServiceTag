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

    /** What the graph passes: the application id, and the one authority the app declares itself. */
    private val policy = StreamSourcePolicy(
        setOf("com.loosecannon.servicetag", "com.loosecannon.servicetag.files"),
    )

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

    /**
     * The merged manifest publishes more than the one provider this app writes: androidx.startup
     * injects `InitializationProvider` under `<applicationId>.androidx-startup`, and the next
     * library to want a content provider will inject another. I-9 says "never one of ServiceTag's
     * own", so the rule is everything under the application id and not a list that goes stale.
     */
    @Test
    fun everyAuthorityUnderOurApplicationIdIsOursIncludingTheOnesLibrariesInject() {
        assertFalse(policy.accepts("content", "com.loosecannon.servicetag.androidx-startup"))
        assertFalse(policy.accepts("content", "com.loosecannon.servicetag"))
        assertFalse(policy.accepts("content", "com.loosecannon.servicetag.some.future.provider"))
        assertFalse(policy.accepts("content", "COM.LOOSECANNON.SERVICETAG.ANDROIDX-STARTUP"))
    }

    /** The prefix keeps its dot, so an unrelated authority that merely starts like ours is not ours. */
    @Test
    fun anAuthorityThatOnlyLooksLikeOursIsStillAccepted() {
        assertTrue(policy.accepts("content", "com.loosecannon.servicetagfoo"))
        assertTrue(policy.accepts("content", "com.loosecannon.servicetagfoo.files"))
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
