package com.loosecannon.servicetag.core.journal

import java.text.Normalizer
import java.util.Locale

/**
 * The category identity rule (#74, C2; amended by the follow-ups, owner rulings 2026-09-26). **The rule
 * is persisted** — it is `asset_category`'s primary key and an archive field — so changing it later is
 * a migration plus a format bump.
 *
 * [display], in this order:
 * 1. Unicode NFC;
 * 2. every removed code point below dropped, and every space-like blank below replaced by a space;
 * 3. NFC again — a no-op unless step 2 brought a mark next to its letter (`E`, U+200B, U+0301 → `É`);
 * 4. trimmed, and every run of `Char.isWhitespace()` characters collapsed to one space.
 *
 * [of]: [display], then `lowercase(Locale.ROOT)`, and **no further case folding** — `ß` and the final
 * sigma are not folded; that is stated, not accidental. `Locale.ROOT`, never the device's locale, so
 * `INFO` keys as `info` on a Turkish phone too.
 *
 * **Removed** — an explicit list of code points, never a whole Unicode category: the invisible characters
 * that make a pasted name look like a saved one while keying apart, and the unassigned code points of the
 * same zero-width kind. U+00AD SOFT HYPHEN; U+034F COMBINING GRAPHEME JOINER; U+061C ARABIC LETTER MARK;
 * U+17B4–U+17B5 KHMER VOWEL INHERENT AQ and AA (zero-width, deprecated); U+180E MONGOLIAN VOWEL SEPARATOR;
 * U+200B ZERO WIDTH SPACE; U+200E LEFT-TO-RIGHT MARK and U+200F RIGHT-TO-LEFT MARK; the bidi embeddings
 * and overrides U+202A–U+202E; U+2060–U+206F (WORD JOINER, the invisible operators, the unassigned
 * U+2065, the bidi isolates, the deprecated format characters); U+FEFF ZERO WIDTH NO-BREAK SPACE; the
 * unassigned U+FFF0–U+FFF8; the musical beam, tie, slur and phrase controls U+1D173–U+1D17A;
 * U+E0000–U+E001F (unassigned, and the deprecated U+E0001 LANGUAGE TAG); the tag characters
 * U+E0020–U+E007F **except in a subdivision flag** (below); the unassigned U+E0080–U+E00FF and
 * U+E01F0–U+E0FFF.
 *
 * **Space-like**, replaced by a space and so trimmed and collapsed like one: the Hangul fillers U+115F,
 * U+1160, U+3164 and U+FFA0, and U+2800 BRAILLE PATTERN BLANK. They take visible width, so dropping them
 * would join two words the owner saw apart.
 *
 * **Kept**, because they carry meaning: U+200C ZERO WIDTH NON-JOINER and U+200D ZERO WIDTH JOINER (Persian
 * and Indic spelling, emoji sequences — the accepted residual being that `App` U+200D `liance` still keys
 * apart from `Appliance`); the variation selectors U+FE00–U+FE0F and U+E0100–U+E01EF; every combining mark
 * but the grapheme joiner and the two Khmer vowels; every whitespace character (collapsed); every other
 * format character; and a subdivision flag's tags — a run of U+E0020–U+E007E closed by U+E007F CANCEL TAG
 * that directly follows U+1F3F4 WAVING BLACK FLAG in the text as given (England, Scotland, Wales). A tag
 * run anywhere else, left open, or broken by any other code point is removed, and the flag reads as the
 * bare U+1F3F4 — which is what such a text shows.
 *
 * Blank text — including text that is nothing but removed characters, space-like blanks and whitespace —
 * has no key and is never promoted.
 */
object CategoryKey {

    /** Dropped wherever they stand (step 2). */
    private val REMOVED: List<IntRange> = listOf(
        0x00AD..0x00AD, // SOFT HYPHEN
        0x034F..0x034F, // COMBINING GRAPHEME JOINER
        0x061C..0x061C, // ARABIC LETTER MARK
        0x17B4..0x17B5, // KHMER VOWEL INHERENT AQ, AA
        0x180E..0x180E, // MONGOLIAN VOWEL SEPARATOR
        0x200B..0x200B, // ZERO WIDTH SPACE
        0x200E..0x200F, // LEFT-TO-RIGHT MARK, RIGHT-TO-LEFT MARK
        0x202A..0x202E, // bidi embeddings, overrides and their pop
        0x2060..0x206F, // WORD JOINER, invisible operators, unassigned U+2065, bidi isolates, deprecated
        0xFEFF..0xFEFF, // ZERO WIDTH NO-BREAK SPACE
        0xFFF0..0xFFF8, // unassigned
        0x1D173..0x1D17A, // musical beam, tie, slur and phrase controls
        0xE0000..0xE001F, // unassigned, and the deprecated LANGUAGE TAG U+E0001
        0xE0080..0xE00FF, // unassigned
        0xE01F0..0xE0FFF, // unassigned
    )

    /** The tag characters a subdivision flag spells with; dropped anywhere else (step 2). */
    private val TAG_SPEC = 0xE0020..0xE007E
    private const val CANCEL_TAG = 0xE007F
    private const val WAVING_BLACK_FLAG = 0x1F3F4

    /** Blanks with visible width, each replaced by a space (step 2). */
    private val SPACE_LIKE: Set<Int> = setOf(0x115F, 0x1160, 0x2800, 0x3164, 0xFFA0)

    /** The key of [text], or null when [text] is blank. */
    fun of(text: String): String? = display(text).takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    /**
     * [text] in NFC, the removed code points dropped and the space-like blanks made spaces, in NFC again,
     * trimmed, whitespace runs collapsed to one space; `""` when blank.
     */
    fun display(text: String): String {
        val visible = visible(Normalizer.normalize(text, Normalizer.Form.NFC))
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

    /** Step 2, by code point, so a supplementary character is kept or dropped whole. */
    private fun visible(text: String): String {
        val cps = text.codePoints().toArray()
        val out = StringBuilder(text.length)
        var i = 0
        while (i < cps.size) {
            val cp = cps[i]
            if (cp in TAG_SPEC || cp == CANCEL_TAG) {
                val end = flagTagsEnd(cps, i)
                for (k in i until end) out.appendCodePoint(cps[k])
                // A flag's tags are kept whole; a stray tag character is dropped.
                i = maxOf(end, i + 1)
                continue
            }
            when {
                cp in SPACE_LIKE -> out.append(' ')
                REMOVED.none { cp in it } -> out.appendCodePoint(cp)
            }
            i++
        }
        return out.toString()
    }

    /**
     * The end (exclusive) of a subdivision flag's tags starting at [start]: a run of [TAG_SPEC] closed by
     * [CANCEL_TAG], directly after [WAVING_BLACK_FLAG] in the text as given. [start] when there is none.
     */
    private fun flagTagsEnd(cps: IntArray, start: Int): Int {
        if (start == 0 || cps[start - 1] != WAVING_BLACK_FLAG) return start
        var k = start
        while (k < cps.size && cps[k] in TAG_SPEC) k++
        return if (k > start && k < cps.size && cps[k] == CANCEL_TAG) k + 1 else start
    }
}
