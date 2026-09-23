package com.loosecannon.servicetag.core.references

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two sanitisers (spec §4.3). A name arrives from a sharing app's `EXTRA_SUBJECT` and a
 * filename from whatever a provider chose to call a file, so neither is trusted text.
 */
class ReferenceLimitsTest {

    @Test
    fun aNameOfNothingButWhitespaceAndControlCharactersSanitisesToNothing() {
        assertEquals("", ReferenceText.sanitiseName("   "))
        assertEquals("", ReferenceText.sanitiseName("\u0000"))
        assertEquals("", ReferenceText.sanitiseName("\n\t\u0000\u0007 "))
        assertEquals("", ReferenceText.sanitiseName(""))
    }

    @Test
    fun aNameLosesItsControlCharactersAndItsRunsOfBlanks() {
        assertEquals("Deck belt", ReferenceText.sanitiseName("  Deck\u0000 belt\n"))
        assertEquals("Deck belt part", ReferenceText.sanitiseName("Deck   belt\tpart"))
        assertEquals("Cub Cadet XT1", ReferenceText.sanitiseName("Cub Cadet\r\nXT1"))
    }

    @Test
    fun aNameIsCappedAtTwoHundredCharacters() {
        assertEquals(MAX_REFERENCE_NAME_CHARS, ReferenceText.sanitiseName("a".repeat(201)).length)
        assertEquals(MAX_REFERENCE_NAME_CHARS, ReferenceText.sanitiseName("a".repeat(200)).length)
        assertEquals(199, ReferenceText.sanitiseName("a".repeat(199)).length)
    }

    @Test
    fun aFilenameIsStrippedToAPathFreeBasename() {
        assertEquals("passwd", ReferenceText.sanitiseFilename("../../etc/passwd"))
        assertEquals("manual.pdf", ReferenceText.sanitiseFilename("..\\..\\windows\\manual.pdf"))
        assertEquals("manual.pdf", ReferenceText.sanitiseFilename("man\u0000ual.pdf"))
        assertEquals("manual.pdf", ReferenceText.sanitiseFilename("manual.pdf\n"))
        assertEquals("deck-belt.pdf", ReferenceText.sanitiseFilename("xt1/parts/deck-belt.pdf"))
    }

    @Test
    fun aFilenameWithNothingLeftIsEmpty() {
        assertEquals("", ReferenceText.sanitiseFilename("../.."))
        assertEquals("", ReferenceText.sanitiseFilename("/"))
        assertEquals("", ReferenceText.sanitiseFilename(".."))
        assertEquals("", ReferenceText.sanitiseFilename("\u0000"))
    }
}
