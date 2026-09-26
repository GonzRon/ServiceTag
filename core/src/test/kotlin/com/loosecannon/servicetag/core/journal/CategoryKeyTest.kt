package com.loosecannon.servicetag.core.journal

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The persisted key rule (#74, C2, §3). Every expected key is written out by hand: the rule is a
 * primary key and an archive field, so a test that asked the rule for its own expectations would
 * agree with any change to it.
 */
class CategoryKeyTest {

    /** Plan §3's table, row by row. */
    @Test
    fun theIdentityTable() {
        assertEquals("appliance", CategoryKey.of("Appliance"))
        assertEquals("Appliance", CategoryKey.display("Appliance"))

        assertEquals("appliance", CategoryKey.of(" appliance "))
        assertEquals("appliance", CategoryKey.display(" appliance "))
        assertEquals("appliance", CategoryKey.of("APPLIANCE"))

        assertEquals("water heater", CategoryKey.of("Water  heater"))
        assertEquals("Water heater", CategoryKey.display("Water  heater"))

        assertEquals("hot tub", CategoryKey.of("hot tub"))
        assertEquals("hot tub", CategoryKey.of("Hot tub"))

        assertEquals("éclairage", CategoryKey.of("Éclairage"))
        assertEquals("Éclairage", CategoryKey.display("Éclairage"))

        assertNull(CategoryKey.of(""))
        assertNull(CategoryKey.of("   "))
        assertEquals("", CategoryKey.display("   "))
    }

    /** NFC first: a precomposed and a decomposed `É` are one key and one spelling, the precomposed one. */
    @Test
    fun precomposedAndDecomposedAreOneKey() {
        val precomposed = "\u00C9clairage"
        val decomposed = "E\u0301clairage"
        assertEquals("éclairage", CategoryKey.of(decomposed))
        assertEquals(CategoryKey.of(precomposed), CategoryKey.of(decomposed))
        assertEquals("Éclairage", CategoryKey.display(decomposed))
    }

    /** Every `Char.isWhitespace()` run is one space: a no-break space and a tab as well as a plain one. */
    @Test
    fun noBreakSpacesAndTabsCollapse() {
        assertEquals("water heater", CategoryKey.of("Water\u00A0\theater"))
        assertEquals("Water heater", CategoryKey.display("Water\u00A0\theater"))
        assertEquals("water heater", CategoryKey.of("Water\u00A0heater"))
        assertEquals("pump", CategoryKey.of("\u00A0Pump\t"))
        assertEquals("sump pump", CategoryKey.of("Sump\n \t pump"))
        assertNull(CategoryKey.of("\u00A0\t\n"))
    }

    /** `Locale.ROOT`, never the device's: on a Turkish phone `INFO` is still `info`, not `ınfo`. */
    @Test
    fun theKeyIgnoresTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("info", CategoryKey.of("INFO"))
            assertEquals("info", CategoryKey.of("Info"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    /** No case folding beyond `lowercase(Locale.ROOT)`: `ß` and the final sigma stay what they are. */
    @Test
    fun sharpSAndFinalSigmaAreNotFolded() {
        assertEquals("straße", CategoryKey.of("Straße"))
        assertEquals("strasse", CategoryKey.of("STRASSE"))
        assertNotEquals(CategoryKey.of("Straße"), CategoryKey.of("STRASSE"))

        assertEquals("οδος", CategoryKey.of("ΟΔΟΣ"))
        assertEquals("οδοσ", CategoryKey.of("οδοσ"))
        assertNotEquals(CategoryKey.of("ΟΔΟΣ"), CategoryKey.of("οδοσ"))
    }

    // --- The follow-ups (owner, 2026-09-26; follow-ups plan §2 and the review's rulings S1–S4) -----------
    // Every invisible code point, joiner, selector and mark below is a `\u` escape or a `cps(...)` call,
    // never a literal character, so what is tested can be read here.

    /** A pasted invisible character makes no second category: the spelling is the visible text, the key follows. */
    @Test
    fun invisibleCharactersAreRemovedFromTheSpellingAndTheKey() {
        for (pasted in listOf(
            "App\u200Bliance", // ZERO WIDTH SPACE
            "\uFEFFAppliance", // ZERO WIDTH NO-BREAK SPACE (a byte-order mark)
            "Appli\u00ADance", // SOFT HYPHEN
            "\u200EAppliance\u200F", // LEFT-TO-RIGHT MARK, RIGHT-TO-LEFT MARK
            "App\u2060liance", // WORD JOINER
            "Appl\u061Ciance", // ARABIC LETTER MARK
            "Appliance" + cps(0xE0061, 0xE0062, 0xE007F), // tag a, tag b, cancel tag, with no flag before them
        )) {
            assertEquals("appliance", CategoryKey.of(pasted), codePoints(pasted))
            assertEquals("Appliance", CategoryKey.display(pasted), codePoints(pasted))
        }
    }

    /** Every removed code point, one by one, written out here rather than asked of the rule. */
    @Test
    fun everyRemovedCharacterIsRemoved() {
        val removed = listOf(0x00AD, 0x034F, 0x061C, 0x17B4, 0x17B5, 0x180E, 0x200B, 0x200E, 0x200F) +
            (0x202A..0x202E) + (0x2060..0x206F) + listOf(0xFEFF) + (0xFFF0..0xFFF8) + (0x1D173..0x1D17A) +
            (0xE0000..0xE00FF) + (0xE01F0..0xE0FFF)
        assertEquals(3904, removed.size)
        for (cp in removed) {
            val text = "Pu" + cps(cp) + "mp"
            assertEquals("pump", CategoryKey.of(text), codePoints(text))
            assertEquals("Pump", CategoryKey.display(text), codePoints(text))
        }
    }

    /**
     * The review's S4, one code point per range: a musical format control, the unassigned U+2065, U+FFF0,
     * U+E0080 and U+E01F0, and the Khmer inherent vowels (a controller ruling, noted for the owner).
     */
    @Test
    fun theZeroWidthRemainderIsRemoved() {
        assertEquals("appliance", CategoryKey.of("Appliance" + cps(0x1D173))) // MUSICAL SYMBOL BEGIN BEAM
        assertEquals("Appliance", CategoryKey.display("App\u2065liance"))
        assertEquals("Appliance", CategoryKey.display("\uFFF0Appliance"))
        assertEquals("Appliance", CategoryKey.display("Appli" + cps(0xE0080) + "ance"))
        assertEquals("Appliance", CategoryKey.display("Appliance" + cps(0xE01F0)))
        assertEquals("Appliance", CategoryKey.display("Appl\u17B4iance\u17B5")) // KHMER VOWEL INHERENT AQ, AA
    }

    /** A bidi override, embedding or isolate inside a name is removed, with its closing pop. */
    @Test
    fun bidiControlsInsideANameAreRemoved() {
        assertEquals("appliance", CategoryKey.of("Appl\u202Eiance\u202C"))
        assertEquals("Appliance", CategoryKey.display("Appl\u202Eiance\u202C"))
        assertEquals("Appliance", CategoryKey.display("Appl\u202Aiance\u202C"))
        assertEquals("Appliance", CategoryKey.display("\u2067Appliance\u2069"))
    }

    /** Nothing but removed characters and whitespace is blank: no key, never promoted. */
    @Test
    fun onlyInvisibleCharactersIsBlank() {
        assertNull(CategoryKey.of("\u200B\u200B"))
        assertEquals("", CategoryKey.display("\u200B\u200B"))
        assertNull(CategoryKey.of("\u200B \uFEFF\t\u00AD"))
        assertEquals("", CategoryKey.display("\u200B \uFEFF\t\u00AD"))
    }

    /** The removal comes before trim and collapse: what it leaves at an edge is trimmed, a run is one space. */
    @Test
    fun theRemovalComesBeforeTrimAndCollapse() {
        assertEquals("Appliance", CategoryKey.display("\u200B Appliance \u200B"))
        assertEquals("Water heater", CategoryKey.display("Water \u200B heater"))
        assertEquals("water heater", CategoryKey.of("Water\u2060 \u2060heater"))
    }

    /** NFC still holds after the removal: a mark a zero-width space kept from its letter composes with it. */
    @Test
    fun nfcStillAppliesAfterTheRemoval() {
        assertEquals("\u00E9", CategoryKey.of("E\u200B\u0301"))
        assertEquals("\u00C9", CategoryKey.display("E\u200B\u0301"))
        assertEquals("\u00E9clairage", CategoryKey.of("E\u200B\u0301clairage"))
        assertEquals("\u00C9clairage", CategoryKey.display("E\u200B\u0301clairage"))
        assertEquals("\u00E1", CategoryKey.of("a\u034F\u0301"))
    }

    /**
     * The review's S3: the Hangul fillers and the braille blank take visible width, so each is a space —
     * trimmed at the ends, one space in a run, never a key on its own — rather than removed.
     */
    @Test
    fun spaceLikeBlanksAreSpaces() {
        assertEquals("Water heater", CategoryKey.display("Water\u3164heater")) // HANGUL FILLER
        assertEquals("water heater", CategoryKey.of("Water\u3164heater"))
        assertEquals("Water heater", CategoryKey.display("Water\u2800heater")) // BRAILLE PATTERN BLANK
        assertEquals("Water heater", CategoryKey.display("Water \u1160 heater")) // HANGUL JUNGSEONG FILLER
        assertEquals("Appliance", CategoryKey.display("Appliance\u2800"))
        assertEquals("appliance", CategoryKey.of("Appliance\u2800"))
        // HALFWIDTH HANGUL FILLER, then HANGUL CHOSEONG FILLER
        assertEquals("Appliance", CategoryKey.display("\uFFA0Appliance\u115F"))
        assertNull(CategoryKey.of("\u2800"))
        assertEquals("", CategoryKey.display("\u2800"))
        assertNull(CategoryKey.of("\u3164\u2800"))
    }

    /** The review's S2: a subdivision flag keeps its tags and keys as itself, apart from the bare flag. */
    @Test
    fun subdivisionFlagsKeepTheirTags() {
        for (flag in listOf(england, scotland, wales)) {
            assertEquals(flag, CategoryKey.display(flag), codePoints(flag))
            assertEquals(flag, CategoryKey.of(flag), codePoints(flag))
            assertNotEquals(CategoryKey.of(blackFlag), CategoryKey.of(flag), codePoints(flag))
        }
        assertEquals("$england Club", CategoryKey.display(" $england  Club "))
        assertEquals("$england club", CategoryKey.of("$england Club"))
    }

    /**
     * Anywhere else a tag character is removed: after a letter, a run with no cancel tag, a cancel tag
     * alone, a run broken by another code point (even one the rule removes: judged on the text as given),
     * tags after a flag's own, and a lone deprecated LANGUAGE TAG.
     */
    @Test
    fun strayTagCharactersAreRemoved() {
        assertEquals("Appliance", CategoryKey.display("Appliance" + cps(0xE0067, 0xE0062, 0xE007F)))
        assertEquals(blackFlag, CategoryKey.display(blackFlag + cps(0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067)))
        assertEquals(blackFlag, CategoryKey.display(blackFlag + cps(0xE007F)))
        assertEquals(blackFlag, CategoryKey.display(blackFlag + cps(0xE0001, 0xE0067, 0xE0062, 0xE007F)))
        assertEquals(
            blackFlag,
            CategoryKey.display(blackFlag + "\u200B" + cps(0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F)),
        )
        assertEquals(england, CategoryKey.display(england + cps(0xE0061, 0xE007F)))
        assertEquals("", CategoryKey.display(cps(0xE0001)))
        assertNull(CategoryKey.of(cps(0xE0001)))
    }

    /**
     * K2: the joiners that carry meaning are kept, so the key with one differs from the key without it —
     * a ZERO WIDTH NON-JOINER inside a Persian word, a ZERO WIDTH JOINER inside an emoji sequence.
     */
    @Test
    fun meaningfulJoinersAreKept() {
        val books = "\u06A9\u062A\u0627\u0628\u200C\u0647\u0627" // "books", with its non-joiner
        val joined = "\u06A9\u062A\u0627\u0628\u0647\u0627"
        assertEquals("\u06A9\u062A\u0627\u0628\u200C\u0647\u0627", CategoryKey.display(books))
        assertEquals("\u06A9\u062A\u0627\u0628\u200C\u0647\u0627", CategoryKey.of(books))
        assertEquals("\u06A9\u062A\u0627\u0628\u0647\u0627", CategoryKey.of(joined))
        assertNotEquals(CategoryKey.of(joined), CategoryKey.of(books))

        val mechanic = cps(0x1F469, 0x200D, 0x1F527) + " Tools" // woman, ZWJ, wrench, then a word
        assertEquals(cps(0x1F469, 0x200D, 0x1F527) + " Tools", CategoryKey.display(mechanic))
        assertEquals(cps(0x1F469, 0x200D, 0x1F527) + " tools", CategoryKey.of(mechanic))
        assertEquals(cps(0x1F469, 0x1F527) + " tools", CategoryKey.of(cps(0x1F469, 0x1F527) + " Tools"))
        assertNotEquals(CategoryKey.of(cps(0x1F469, 0x1F527) + " Tools"), CategoryKey.of(mechanic))
    }

    /**
     * K2: variation selectors (both blocks), combining marks and every format character outside the removed
     * set are kept — the rule is an explicit list, never a whole Unicode category.
     */
    @Test
    fun variationSelectorsCombiningMarksAndOtherFormatCharactersAreKept() {
        assertEquals("\u2615\uFE0F coffee", CategoryKey.of("\u2615\uFE0F Coffee"))
        assertNotEquals(CategoryKey.of("\u2615 Coffee"), CategoryKey.of("\u2615\uFE0F Coffee"))
        assertEquals("a\uFE00b", CategoryKey.of("A\uFE00B"))
        assertEquals(cps(0x845B, 0xE0100), CategoryKey.display(cps(0x845B, 0xE0100)))
        assertEquals(cps(0x845B, 0xE01EF), CategoryKey.display(cps(0x845B, 0xE01EF)))

        assertEquals("q\u0307", CategoryKey.of("Q\u0307")) // no precomposed form: the mark stays
        assertEquals("\u0915\u094D\u0937", CategoryKey.display("\u0915\u094D\u0937")) // a virama

        // ARABIC NUMBER SIGN: a visible format character, outside the list
        assertEquals("\u0600\u0661", CategoryKey.display("\u0600\u0661"))
    }

    private val blackFlag = cps(0x1F3F4)
    private val england = cps(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F) // gbeng
    private val scotland = cps(0x1F3F4, 0xE0067, 0xE0062, 0xE0073, 0xE0063, 0xE0074, 0xE007F) // gbsct
    private val wales = cps(0x1F3F4, 0xE0067, 0xE0062, 0xE0077, 0xE006C, 0xE0073, 0xE007F) // gbwls

    /** The text of [codePoints], so a supplementary character is written by its number. */
    private fun cps(vararg codePoints: Int): String = String(codePoints, 0, codePoints.size)

    private fun codePoints(text: String): String =
        text.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
}
