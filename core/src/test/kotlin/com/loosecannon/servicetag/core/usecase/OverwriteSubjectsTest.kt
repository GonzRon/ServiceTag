package com.loosecannon.servicetag.core.usecase

import com.loosecannon.nfc.tagcore.OverwriteDecision
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.nfc.OverwriteReasons
import com.loosecannon.servicetag.core.nfc.TagPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * #70 — the overwrite sheet's words, one state at a time (plan §5, ratified 2026-09-26). Every
 * expected sentence is written out here in full; nothing is derived from the code under test.
 */
class OverwriteSubjectsTest {
    private val writing = TagId("123e4567-e89b-12d3-a456-426614174000")
    private val key = "a0c19962-717b-4d67-bd54-644f3565a456"
    private val asset = Asset(AssetId("a1"), "Pump 3", createdAt = 1L, updatedAt = 1L)

    /** The question the library asks when the tag holds a different v1 identity. */
    private val v1Question = confirm(TagPayload.V1(TagId(key)))

    private fun confirm(existing: TagPayload) =
        OverwriteReasons.decide(existing, writing) as OverwriteDecision.Confirm

    private fun row(
        target: TagTarget = TagTarget.None,
        status: TagStatus = TagStatus.ACTIVE,
        label: String? = null,
    ) = TagBinding(TagId(key), PayloadFormat.V1, key, target, status, label = label, createdAt = 1L, updatedAt = 1L)

    private val bound = TagTarget.AssetTarget(AssetId("a1"))

    @Test fun namesTheBoundAsset() {
        assertEquals(
            OverwriteSubject("This tag currently identifies Pump 3.", "a0c19962 · v1 · Pump house"),
            OverwriteSubjects.of(v1Question, Resolution.OpenAsset(row(bound, label = "Pump house"), asset)),
        )
        assertEquals(
            OverwriteSubject("This tag currently identifies Pump 3.", "a0c19962 · v1"),
            OverwriteSubjects.of(v1Question, Resolution.OpenAsset(row(bound), asset)),
        )
        assertEquals(
            OverwriteSubject("This tag currently identifies Pump 3.", "a0c19962 · v1"),
            OverwriteSubjects.of(v1Question, Resolution.OpenAsset(row(bound, label = "   "), asset)),
        )
    }

    @Test fun keepsASpareTagUnassigned() {
        assertEquals(
            OverwriteSubject("This tag is in this phone's records but is not assigned to anything yet.", "a0c19962 · v1 · Pump house"),
            OverwriteSubjects.of(v1Question, Resolution.Unbound(row(status = TagStatus.UNBOUND, label = "Pump house"))),
        )
        assertEquals(
            OverwriteSubject("This tag is in this phone's records but is not assigned to anything yet.", "a0c19962 · v1"),
            OverwriteSubjects.of(v1Question, Resolution.Unbound(row(status = TagStatus.UNBOUND))),
        )
    }

    /** Both rows still target an asset that exists; neither line may name it (AC 3). */
    @Test fun keepsALostAndARetiredTagRevoked() {
        val lost = OverwriteSubjects.of(v1Question, Resolution.Revoked(row(bound, TagStatus.LOST, label = "Pump house")))
        assertEquals(OverwriteSubject("This tag was marked lost and taken out of service.", "a0c19962 · v1 · Pump house"), lost)
        assertFalse("Pump 3" in lost.line)

        val retired = OverwriteSubjects.of(v1Question, Resolution.Revoked(row(bound, TagStatus.RETIRED)))
        assertEquals(OverwriteSubject("This tag was retired and taken out of service.", "a0c19962 · v1"), retired)
        assertFalse("Pump 3" in retired.line)
    }

    @Test fun saysAnUnknownV1IsNotInTheRecords() {
        assertEquals(
            OverwriteSubject("This ServiceTag tag is not in this phone's records.", "a0c19962 · v1"),
            OverwriteSubjects.of(v1Question, Resolution.UnknownV1(TagId(key))),
        )
    }

    @Test fun namesAPreSplitLinkTag() {
        assertEquals(
            OverwriteSubject(
                "This tag points at a note link from before the product split. ServiceTag no longer opens links; NoteTag does.",
                "a0c19962 · v1",
            ),
            OverwriteSubjects.of(v1Question, Resolution.PreSplitLink(row(TagTarget.LinkTarget(LinkId("l1"))))),
        )
        // A pre-split row is a known row, so its placement rides the quiet line like any other.
        assertEquals(
            "a0c19962 · v1 · Pump house",
            OverwriteSubjects.of(v1Question, Resolution.PreSplitLink(row(TagTarget.LinkTarget(LinkId("l1")), label = "Pump house"))).identifier,
        )
    }

    /** R70-5: a failed lookup is "not checked just now", never "not in the records". */
    @Test fun isHonestWhenTheLookupFailed() {
        val honest = OverwriteSubject("This is a ServiceTag tag, but its record could not be checked just now.", "a0c19962 · v1")
        assertEquals(honest, OverwriteSubjects.of(v1Question, null))
        // A v1 payload cannot resolve to these two; if it ever did, the words stay honest.
        assertEquals(honest, OverwriteSubjects.of(v1Question, Resolution.NeedsNewerApp(2)))
        assertEquals(honest, OverwriteSubjects.of(v1Question, Resolution.NotOurs(TagPayload.Empty)))
    }

    /** Today's three sentences, moved from the screen into the model, byte for byte (AC 4). */
    @Test fun keepsTheThreeNonV1Sentences() {
        assertEquals(
            OverwriteSubject("The tag already holds a ServiceTag tag written by a newer app (format 3).", null),
            OverwriteSubjects.of(confirm(TagPayload.NewerVersion(3)), null),
        )
        assertEquals(
            OverwriteSubject("The tag already holds foreign NDEF content (tnf=1 type=U).", null),
            OverwriteSubjects.of(confirm(TagPayload.Foreign("tnf=1 type=U")), null),
        )
        assertEquals(
            OverwriteSubject("The tag already holds unreadable NDEF content (NDEF on tag could not be parsed).", null),
            OverwriteSubjects.of(confirm(TagPayload.Malformed("NDEF on tag could not be parsed")), null),
        )
    }

    /** AC 6: the id is secondary and short — eight characters and the format, never the whole uuid. */
    @Test fun theIdentifierIsTheShortIdAndOnlyForV1() {
        val v1Subjects = listOf(
            OverwriteSubjects.of(v1Question, Resolution.OpenAsset(row(bound), asset)),
            OverwriteSubjects.of(v1Question, Resolution.Unbound(row(status = TagStatus.UNBOUND))),
            OverwriteSubjects.of(v1Question, Resolution.Revoked(row(bound, TagStatus.LOST))),
            OverwriteSubjects.of(v1Question, Resolution.Revoked(row(bound, TagStatus.RETIRED))),
            OverwriteSubjects.of(v1Question, Resolution.PreSplitLink(row(TagTarget.LinkTarget(LinkId("l1"))))),
            OverwriteSubjects.of(v1Question, Resolution.UnknownV1(TagId(key))),
            OverwriteSubjects.of(v1Question, null),
        )
        for (subject in v1Subjects) {
            assertEquals("a0c19962 · v1", subject.identifier, subject.line)
            assertFalse(key in subject.line, subject.line)
        }

        val otherSubjects = listOf(
            OverwriteSubjects.of(confirm(TagPayload.NewerVersion(3)), null),
            OverwriteSubjects.of(confirm(TagPayload.Foreign("tnf=1 type=U")), null),
            OverwriteSubjects.of(confirm(TagPayload.Malformed("x")), null),
        )
        for (subject in otherSubjects) {
            assertNull(subject.identifier, subject.line)
            assertFalse(key in subject.line, subject.line)
        }
    }
}
