package com.loosecannon.servicetag.core.references

import com.loosecannon.servicetag.core.model.ReferenceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The three tiers (spec §4.2) and the kind they infer (spec §3.2). Both lists are #35's own,
 * verbatim; a set that is a prefix of the spec's is exactly the failure these cases exist for.
 */
class LinkLaunchPolicyTest {

    private val policy = LinkLaunchPolicy()

    /** One URI per hard-blocked scheme, each structurally valid so the tier is what refuses it. */
    private val blocked = mapOf(
        "javascript" to "javascript:alert(1)",
        "file" to "file://localhost/etc/passwd",
        "content" to "content://com.android.providers.downloads.documents/document/17",
        "intent" to "intent://scan#Intent;scheme=zxing;end",
        "android-app" to "android-app://com.example.reader",
        "tel" to "tel:+15550100",
        "sms" to "sms:+15550100",
        "mailto" to "mailto:parts@example-mower.invalid",
    )

    private val allowed = mapOf(
        "http" to "http://example-mower.invalid/xt1",
        "https" to "https://example-mower.invalid/xt1",
        "joplin" to "joplin://x-callback-url/openNote?id=0f1e2d3c4b5a6978",
        "obsidian" to "obsidian://open?vault=Shed&file=Mower",
        "logseq" to "logseq://graph/shed?page=Mower",
    )

    @Test
    fun theTwoListsAreTheOnesTheSpecNames() {
        assertEquals(setOf("http", "https", "joplin", "obsidian", "logseq"), LinkLaunchPolicy.ALLOWED)
        assertEquals(
            setOf("javascript", "file", "content", "intent", "android-app", "tel", "sms", "mailto"),
            LinkLaunchPolicy.BLOCKED,
        )
    }

    @Test
    fun everyHardBlockedSchemeIsBlockedAtLaunchAsWellAsAtSave() {
        for ((scheme, uri) in blocked) {
            assertEquals(LinkDecision.Blocked, policy.classify(uri), "$scheme should be blocked")
        }
    }

    @Test
    fun aUriWithNoSchemeAtAllHasNoTierAndIsBlocked() {
        assertNull(policy.schemeOf("notaurl"))
        assertEquals(LinkDecision.Blocked, policy.classify("notaurl"))
        assertEquals(LinkDecision.Blocked, policy.classify("example-mower.invalid/xt1"))
        assertEquals(LinkDecision.Blocked, policy.classify(""))
    }

    @Test
    fun everyAllowedSchemeIsAllowed() {
        for ((scheme, uri) in allowed) {
            assertEquals(LinkDecision.Allowed, policy.classify(uri), "$scheme should be allowed")
            assertEquals(scheme, policy.schemeOf(uri))
        }
    }

    @Test
    fun theTierIsReadFromTheLowercasedScheme() {
        assertEquals(LinkDecision.Blocked, policy.classify("JavaScript:alert(1)"))
        assertEquals(LinkDecision.Allowed, policy.classify("HTTPS://example-mower.invalid/xt1"))
        assertEquals("https", policy.schemeOf("HTTPS://example-mower.invalid/xt1"))
    }

    @Test
    fun anythingElseIsUnknownAndCarriesItsScheme() {
        assertEquals(LinkDecision.Unknown("zotero"), policy.classify("zotero://select/items/0"))
        assertEquals(LinkDecision.Unknown("zotero"), policy.classify("ZOTERO://select/items/0"))
    }

    @Test
    fun theKindIsInferredFromTheSchemeAndNeverPicked() {
        assertEquals(ReferenceKind.WEB_URL, ReferenceKinds.inferFrom("http"))
        assertEquals(ReferenceKind.WEB_URL, ReferenceKinds.inferFrom("HTTPS"))
        assertEquals(ReferenceKind.NOTE_LINK, ReferenceKinds.inferFrom("joplin"))
        assertEquals(ReferenceKind.NOTE_LINK, ReferenceKinds.inferFrom("obsidian"))
        assertEquals(ReferenceKind.NOTE_LINK, ReferenceKinds.inferFrom("logseq"))
        assertEquals(ReferenceKind.OTHER, ReferenceKinds.inferFrom("zotero"))
        assertEquals(ReferenceKind.OTHER, ReferenceKinds.inferFrom(null))
        assertEquals(ReferenceKind.OTHER, ReferenceKinds.inferFrom(""))
    }
}
