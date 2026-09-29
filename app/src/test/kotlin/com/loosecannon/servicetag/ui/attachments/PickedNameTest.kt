package com.loosecannon.servicetag.ui.attachments

import org.junit.Assert.assertEquals
import org.junit.Test

/** #84 (C12): the name rule of a picked file, run without a provider. */
class PickedNameTest {

    @Test fun aRealNameIsKeptVerbatimSpacesIncluded() {
        assertEquals("Example Invoice 2026.pdf", pickedName("Example Invoice 2026.pdf", "doc:42"))
        assertEquals(" padded name ", pickedName(" padded name ", "doc:42"))
    }

    @Test fun aBlankOrMissingNameFallsBackToTheSegmentsLastPart() {
        assertEquals("manual.pdf", pickedName("", "primary:Documents/manual.pdf"))
        assertEquals("manual.pdf", pickedName("   ", "primary:Documents/manual.pdf"))
        assertEquals("manual.pdf", pickedName(null, "primary:Documents/manual.pdf"))
        assertEquals("doc:42", pickedName("", "doc:42"))
    }

    @Test fun aBlankSegmentFallsBackToFile() {
        assertEquals("file", pickedName("", ""))
        assertEquals("file", pickedName(null, null))
        assertEquals("file", pickedName("  ", "dir/"))
        assertEquals("file", pickedName(null, "   "))
    }
}
