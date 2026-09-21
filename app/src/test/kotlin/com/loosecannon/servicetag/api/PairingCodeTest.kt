package com.loosecannon.servicetag.api

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.0 (#46) — the pairing code and the comparison that accepts it.
 *
 * Two of the release's security minimums live here: the code is fresh per screen visit and drawn
 * from an alphabet a person can read off a phone and type without ambiguity, and the comparison is
 * the JDK's own non-short-circuiting array compare rather than `==` on a String.
 */
class PairingCodeTest {

    @Test fun isEightCharactersOfTheUnambiguousAlphabet() {
        repeat(200) {
            val code = newPairingCode()
            assertEquals(PAIRING_CODE_LENGTH, code.length)
            assertTrue("$code is not all alphabet", code.all { it in PAIRING_ALPHABET })
        }
    }

    /**
     * The alphabet is exactly 32 characters, and none of the six a person confuses by eye. The
     * count matters: 32 is what makes an 8-character code 40 bits.
     */
    @Test fun theAlphabetHasNoLookalikes() {
        assertEquals(32, PAIRING_ALPHABET.length)
        assertEquals(32, PAIRING_ALPHABET.toSet().size)
        for (confusing in listOf('I', 'O', '0', '1', 'i', 'o')) {
            assertFalse("$confusing is in the alphabet", confusing in PAIRING_ALPHABET)
        }
    }

    /** Fresh every time the screen opens — the property that makes a code a session's and not the phone's. */
    @Test fun twoCodesAreNotTheSame() {
        val codes = List(200) { newPairingCode() }
        assertEquals(200, codes.toSet().size)
    }

    @Test fun onlyTheExactCodeMatches() {
        val code = newPairingCode(SecureRandom())
        assertTrue(tokenMatches(code, code))
        assertFalse(tokenMatches(code, null))
        assertFalse(tokenMatches(code, ""))
        assertFalse(tokenMatches(code, code.lowercase()))
        assertFalse(tokenMatches(code, code.dropLast(1)))
        assertFalse(tokenMatches(code, code + "A"))
    }
}
