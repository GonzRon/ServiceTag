package com.loosecannon.servicetag.ui.scan

import com.loosecannon.servicetag.core.usecase.OverwriteLine
import com.loosecannon.servicetag.core.usecase.OverwriteSubject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #70 / #102 — the overwrite sheet's ratified English (plan §5, ratified 2026-09-26), one line per
 * state. Core names the sentence (`OverwriteSubjectsTest`); the words live in the app's resources
 * and are pinned here, byte for byte. Every expected sentence is written out in full.
 */
class OverwriteWordsTest {

    @Test fun theV1Lines() {
        assertEquals("This tag currently identifies Pump 3.", overwriteSentence(OverwriteLine.Identifies("Pump 3")))
        assertEquals(
            "This tag is in this phone's records but is not assigned to anything yet.",
            overwriteSentence(OverwriteLine.NotAssigned),
        )
        assertEquals("This tag was marked lost and taken out of service.", overwriteSentence(OverwriteLine.MarkedLost))
        assertEquals("This tag was retired and taken out of service.", overwriteSentence(OverwriteLine.Retired))
        assertEquals("This ServiceTag tag is not in this phone's records.", overwriteSentence(OverwriteLine.NotInRecords))
        assertEquals(
            "This tag points at a note link from before the product split. ServiceTag no longer opens links; NoteTag does.",
            overwriteSentence(OverwriteLine.PreSplitLink),
        )
        assertEquals(
            "This is a ServiceTag tag, but its record could not be checked just now.",
            overwriteSentence(OverwriteLine.CouldNotCheck),
        )
    }

    @Test fun theThreeNonV1Lines() {
        assertEquals(
            "The tag already holds a ServiceTag tag written by a newer app (format 3).",
            overwriteSentence(OverwriteLine.NewerFormat("3")),
        )
        assertEquals(
            "The tag already holds foreign NDEF content (tnf=1 type=U).",
            overwriteSentence(OverwriteLine.Foreign("tnf=1 type=U")),
        )
        assertEquals(
            "The tag already holds unreadable NDEF content (NDEF on tag could not be parsed).",
            overwriteSentence(OverwriteLine.Unreadable("NDEF on tag could not be parsed")),
        )
    }

    /** The owner's asset name is an argument, shown exactly as entered — a `%` in it included. */
    @Test fun theAssetNameIsShownAsEntered() {
        assertEquals("This tag currently identifies Tank 50% · east.", overwriteSentence(OverwriteLine.Identifies("Tank 50% · east")))
    }

    /** The quiet line is an id and the owner's label: worded or not, it passes through untouched. */
    @Test fun theIdentifierPassesThrough() {
        assertEquals(
            OverwriteWords("This tag currently identifies Pump 3.", "a0c19962 · v1 · Pump house"),
            OverwriteSubject(OverwriteLine.Identifies("Pump 3"), "a0c19962 · v1 · Pump house").words(),
        )
        assertEquals(
            OverwriteWords("The tag already holds foreign NDEF content (tnf=1 type=U).", null),
            OverwriteSubject(OverwriteLine.Foreign("tnf=1 type=U"), null).words(),
        )
    }
}
