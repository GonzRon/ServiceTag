package com.loosecannon.servicetag.core.nfc

import com.loosecannon.nfc.tagcore.ExistingContent
import com.loosecannon.nfc.tagcore.OverwriteDecision
import com.loosecannon.nfc.tagcore.OverwriteReason
import com.loosecannon.servicetag.core.model.TagId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OverwriteReasonsTest {
    private val mine = TagId("123e4567-e89b-12d3-a456-426614174000")
    private val other = TagId("00000000-0000-4000-8000-000000000001")

    @Test fun theMappingToExistingContent() {
        assertEquals(ExistingContent.Empty, OverwriteReasons.existing(TagPayload.Empty))
        assertEquals(ExistingContent.Ours(mine.value), OverwriteReasons.existing(TagPayload.V1(mine)))
        assertEquals(ExistingContent.OursUnsupported("3"), OverwriteReasons.existing(TagPayload.NewerVersion(3)))
        assertEquals(ExistingContent.Foreign("tnf=1 type=U"), OverwriteReasons.existing(TagPayload.Foreign("tnf=1 type=U")))
        assertEquals(ExistingContent.Unreadable("x"), OverwriteReasons.existing(TagPayload.Malformed("x")))
    }

    @Test fun emptyTagProceeds() = assertEquals(OverwriteDecision.Proceed, OverwriteReasons.decide(TagPayload.Empty, mine))
    @Test fun sameV1IdProceeds() = assertEquals(OverwriteDecision.Proceed, OverwriteReasons.decide(TagPayload.V1(mine), mine))
    /**
     * #70 R70-4: the v1 question is worded by `OverwriteSubjects` (core/usecase), which knows what
     * the id means on this phone; `sentence` no longer has a uuid sentence to fall back on.
     */
    @Test fun differentV1IdConfirms() {
        val c = assertIs<OverwriteDecision.Confirm>(OverwriteReasons.decide(TagPayload.V1(other), mine))
        assertEquals(OverwriteReason.OTHER_TAG_SAME_PRODUCT, c.reason)
        assertFailsWith<IllegalStateException> { OverwriteReasons.sentence(c) }
    }
    @Test fun newerVersionConfirms() {
        val c = assertIs<OverwriteDecision.Confirm>(OverwriteReasons.decide(TagPayload.NewerVersion(3), mine))
        assertEquals("a ServiceTag tag written by a newer app (format 3)", OverwriteReasons.sentence(c))
    }
    @Test fun foreignConfirms() {
        val c = assertIs<OverwriteDecision.Confirm>(OverwriteReasons.decide(TagPayload.Foreign("tnf=1 type=U"), mine))
        assertEquals("foreign NDEF content (tnf=1 type=U)", OverwriteReasons.sentence(c))
    }
    @Test fun malformedConfirms() {
        val c = assertIs<OverwriteDecision.Confirm>(OverwriteReasons.decide(TagPayload.Malformed("x"), mine))
        assertEquals("unreadable NDEF content (x)", OverwriteReasons.sentence(c))
    }
    /** The question carries what is on the tag, not just that something is: the id the sheet looks up. */
    @Test fun reasonNamesTheTagThatIsThere() {
        assertEquals(other.value, (OverwriteReasons.decide(TagPayload.V1(other), mine) as OverwriteDecision.Confirm).detail)
    }
}
