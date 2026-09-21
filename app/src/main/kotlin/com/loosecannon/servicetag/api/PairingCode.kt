package com.loosecannon.servicetag.api

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The 32 characters a pairing code is made of: the alphabet without `I` and `O`, and the digits
 * `2`–`9`. Exactly 32, so each character is five bits and an eight-character code is forty — and
 * none of the six shapes a person mistypes reading a phone (`I`/`1`, `O`/`0`, and either case of
 * the two letters).
 */
internal const val PAIRING_ALPHABET: String = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** Eight characters: short enough to read off a screen, forty bits against a local guesser. */
internal const val PAIRING_CODE_LENGTH: Int = 8

/**
 * A fresh code. [SecureRandom] and not `kotlin.random.Random`: this is the only thing standing
 * between a process on this phone and the owner's records, so it comes from the platform's CSPRNG.
 * The parameter exists so a test can pass its own instance; production never does.
 */
internal fun newPairingCode(random: SecureRandom = SecureRandom()): String =
    String(CharArray(PAIRING_CODE_LENGTH) { PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)] })

/**
 * Whether [offered] is [expected], compared without short-circuiting on the first differing byte.
 *
 * [MessageDigest.isEqual] is the JDK's own constant-time array comparison — the right tool, and not
 * a hand-rolled loop. It does return early when the two arrays differ in *length*, which is
 * deliberate and harmless here: every real code is exactly [PAIRING_CODE_LENGTH] characters, so
 * length leaks nothing an attacker does not already know from this file.
 */
internal fun tokenMatches(expected: String, offered: String?): Boolean {
    if (offered == null) return false
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        offered.toByteArray(Charsets.UTF_8),
    )
}
