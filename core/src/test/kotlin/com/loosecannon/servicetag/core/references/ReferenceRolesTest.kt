package com.loosecannon.servicetag.core.references

import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.ReferenceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #91 (C1, R91-1): the reference rule for a document role, stated once beside the rule that picks
 * the kind. Only a web link — http or https, which is `WEB_URL` — may carry a role; no role is
 * always fine. Each case walks every value of both enums, so a kind or a role added later is asked
 * the question rather than skipped.
 */
class ReferenceRolesTest {

    @Test
    fun onlyAWebLinkTakesARole() {
        assertEquals(
            mapOf(
                ReferenceKind.WEB_URL to true,
                ReferenceKind.NOTE_LINK to false,
                ReferenceKind.OTHER to false,
            ),
            ReferenceKind.entries.associateWith { it.takesRole },
        )
    }

    @Test
    fun noRoleIsAcceptedOnEveryKind() {
        for (kind in ReferenceKind.entries) {
            assertTrue(kind.accepts(null), "no role must be accepted on $kind")
        }
    }

    @Test
    fun eachRoleIsAcceptedOnAWebLinkOnly() {
        for (role in DocumentRole.entries) {
            assertTrue(ReferenceKind.WEB_URL.accepts(role), "$role must be accepted on a web link")
            assertFalse(ReferenceKind.NOTE_LINK.accepts(role), "$role must be refused on a note link")
            assertFalse(ReferenceKind.OTHER.accepts(role), "$role must be refused on an other link")
        }
    }
}
