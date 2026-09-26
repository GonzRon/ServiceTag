package com.loosecannon.servicetag.core.journal

import java.text.Normalizer
import java.util.Locale

/**
 * The category identity rule (#74, C2; amended by the follow-ups plan §2, owner 2026-09-26). **The rule
 * is persisted** — it is `asset_category`'s primary key and an archive field — so changing it later is
 * a migration plus a format bump.
 *
 * - [display], in this order: Unicode NFC; the invisible characters below removed; NFC again, so a mark
 *   that a removed character kept apart from its letter composes with it; trimmed; every run of
 *   `Char.isWhitespace()` characters collapsed to one space. The owner's visible spelling otherwise kept.
 * - [of]: [display], then `lowercase(Locale.ROOT)`, and **no further case folding** — `ß` and the
 *   final sigma are not folded; that is stated, not accidental. `Locale.ROOT`, never the device's
 *   locale, so `INFO` keys as `info` on a Turkish phone too.
 *
 * **Removed** — an explicit list, never the whole format category: the non-semantic invisible characters
 * that make a pasted name look identical to a saved one while keying differently. U+00AD SOFT HYPHEN;
 * U+034F COMBINING GRAPHEME JOINER; U+180E MONGOLIAN VOWEL SEPARATOR; U+200B ZERO WIDTH SPACE; U+200E
 * LEFT-TO-RIGHT MARK and U+200F RIGHT-TO-LEFT MARK; the bidi embeddings and overrides U+202A–U+202E and
 * isolates U+2066–U+2069; U+2060 WORD JOINER and the invisible operators U+2061–U+2064; the deprecated
 * format characters U+206A–U+206F; U+FEFF ZERO WIDTH NO-BREAK SPACE; the tag characters U+E0000–U+E007F.
 *
 * **Kept**, because they carry meaning: U+200C ZERO WIDTH NON-JOINER and U+200D ZERO WIDTH JOINER
 * (Persian and Indic spelling, emoji sequences), the variation selectors U+FE00–U+FE0F and
 * U+E0100–U+E01EF, every combining mark but the grapheme joiner, every whitespace character (collapsed,
 * as above), and every other format character.
 *
 * Blank text — including text that is nothing but removed characters and whitespace — has no key and
 * is never promoted.
 */
object CategoryKey {

    /** The removed code points, exactly the list above. */
    private val INVISIBLE: List<IntRange> = listOf(
        0x00AD..0x00AD, // SOFT HYPHEN
        0x034F..0x034F, // COMBINING GRAPHEME JOINER
        0x180E..0x180E, // MONGOLIAN VOWEL SEPARATOR
        0x200B..0x200B, // ZERO WIDTH SPACE
        0x200E..0x200F, // LEFT-TO-RIGHT MARK, RIGHT-TO-LEFT MARK
        0x202A..0x202E, // bidi embeddings, overrides and their pop
        0x2060..0x2064, // WORD JOINER, the invisible operators
        0x2066..0x2069, // bidi isolates and their pop
        0x206A..0x206F, // deprecated format characters
        0xFEFF..0xFEFF, // ZERO WIDTH NO-BREAK SPACE
        0xE0000..0xE007F, // tag characters
    )

    /** The key of [text], or null when [text] is blank. */
    fun of(text: String): String? = display(text).takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    /** [text] in NFC with the invisible characters removed, trimmed, whitespace runs collapsed; `""` when blank. */
    fun display(text: String): String {
        val visible = withoutInvisibles(Normalizer.normalize(text, Normalizer.Form.NFC))
        // NFC again: a removed character may have kept a mark apart from its letter (`E`, U+200B, U+0301).
        val normal = Normalizer.normalize(visible, Normalizer.Form.NFC).trim()
        return buildString(normal.length) {
            var inRun = false
            for (c in normal) {
                if (c.isWhitespace()) {
                    if (!inRun) append(' ')
                    inRun = true
                } else {
                    append(c)
                    inRun = false
                }
            }
        }
    }

    private fun withoutInvisibles(text: String): String {
        val kept = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (INVISIBLE.none { cp in it }) kept.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return kept.toString()
    }
}
