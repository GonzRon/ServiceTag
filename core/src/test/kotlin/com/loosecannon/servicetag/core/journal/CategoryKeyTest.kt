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
        val precomposed = "Éclairage"
        val decomposed = "Éclairage"
        assertEquals("éclairage", CategoryKey.of(decomposed))
        assertEquals(CategoryKey.of(precomposed), CategoryKey.of(decomposed))
        assertEquals("Éclairage", CategoryKey.display(decomposed))
    }

    /** Every `Char.isWhitespace()` run is one space: a no-break space and a tab as well as a plain one. */
    @Test
    fun noBreakSpacesAndTabsCollapse() {
        assertEquals("water heater", CategoryKey.of("Water \theater"))
        assertEquals("Water heater", CategoryKey.display("Water \theater"))
        assertEquals("water heater", CategoryKey.of("Water heater"))
        assertEquals("pump", CategoryKey.of(" Pump\t"))
        assertEquals("sump pump", CategoryKey.of("Sump\n \t pump"))
        assertNull(CategoryKey.of(" \t\n"))
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

    // --- The follow-ups (owner, 2026-09-26; follow-ups plan §2, K1–K3): the invisible characters -------

    /** A pasted invisible character makes no second category: the spelling is the visible text, the key follows. */
    @Test
    fun invisibleCharactersAreRemovedFromTheSpellingAndTheKey() {
        for (pasted in listOf(
            "App​liance", // ZERO WIDTH SPACE
            "﻿Appliance", // ZERO WIDTH NO-BREAK SPACE (a byte-order mark)
            "Appli­ance", // SOFT HYPHEN
            "‎Appliance‏", // LEFT-TO-RIGHT MARK, RIGHT-TO-LEFT MARK
            "App⁠liance", // WORD JOINER
            "Appliance󠁡󠁢󠁿", // U+E0061, U+E0062, U+E007F: tag a, tag b, cancel tag
        )) {
            assertEquals("appliance", CategoryKey.of(pasted), codePoints(pasted))
            assertEquals("Appliance", CategoryKey.display(pasted), codePoints(pasted))
        }
    }

    /** Every code point of K1, one by one, written out here rather than asked of the rule. */
    @Test
    fun everyRemovedCharacterIsRemoved() {
        val removed = listOf(0x00AD, 0x034F, 0x180E, 0x200B, 0x200E, 0x200F) +
            (0x202A..0x202E) + (0x2060..0x2064) + (0x2066..0x2069) + (0x206A..0x206F) +
            listOf(0xFEFF) + (0xE0000..0xE007F)
        assertEquals(155, removed.size)
        for (cp in removed) {
            val text = "Pu" + String(Character.toChars(cp)) + "mp"
            assertEquals("pump", CategoryKey.of(text), codePoints(text))
            assertEquals("Pump", CategoryKey.display(text), codePoints(text))
        }
    }

    /** A bidi override, embedding or isolate inside a name is removed, with its closing pop. */
    @Test
    fun bidiControlsInsideANameAreRemoved() {
        assertEquals("appliance", CategoryKey.of("Appl‮iance‬"))
        assertEquals("Appliance", CategoryKey.display("Appl‮iance‬"))
        assertEquals("Appliance", CategoryKey.display("Appl‪iance‬"))
        assertEquals("Appliance", CategoryKey.display("⁧Appliance⁩"))
    }

    /** Nothing but removed characters and whitespace is blank: no key, never promoted. */
    @Test
    fun onlyInvisibleCharactersIsBlank() {
        assertNull(CategoryKey.of("​​"))
        assertEquals("", CategoryKey.display("​​"))
        assertNull(CategoryKey.of("​ ﻿\t­"))
        assertEquals("", CategoryKey.display("​ ﻿\t­"))
    }

    /** The removal comes before trim and collapse: what it leaves at an edge is trimmed, a run is one space. */
    @Test
    fun theRemovalComesBeforeTrimAndCollapse() {
        assertEquals("Appliance", CategoryKey.display("​ Appliance ​"))
        assertEquals("Water heater", CategoryKey.display("Water ​ heater"))
        assertEquals("water heater", CategoryKey.of("Water⁠ ⁠heater"))
    }

    /** NFC still holds after the removal: a mark a zero-width space kept from its letter composes with it. */
    @Test
    fun nfcStillAppliesAfterTheRemoval() {
        assertEquals("é", CategoryKey.of("E​́"))
        assertEquals("É", CategoryKey.display("E​́"))
        assertEquals("éclairage", CategoryKey.of("E​́clairage"))
        assertEquals("Éclairage", CategoryKey.display("E​́clairage"))
        assertEquals("á", CategoryKey.of("a͏́"))
    }

    /**
     * K2: the joiners that carry meaning are kept, so the key with one differs from the key without it —
     * a ZERO WIDTH NON-JOINER inside a Persian word, a ZERO WIDTH JOINER inside an emoji sequence.
     */
    @Test
    fun meaningfulJoinersAreKept() {
        val books = "کتاب‌ها" // "books", with its non-joiner
        assertEquals("کتاب‌ها", CategoryKey.display(books))
        assertEquals("کتاب‌ها", CategoryKey.of(books))
        assertEquals("کتابها", CategoryKey.of("کتابها"))
        assertNotEquals(CategoryKey.of("کتابها"), CategoryKey.of(books))

        val mechanic = "👩‍🔧 Tools" // U+1F469 ZWJ U+1F527, then a word
        assertEquals("👩‍🔧 Tools", CategoryKey.display(mechanic))
        assertEquals("👩‍🔧 tools", CategoryKey.of(mechanic))
        assertEquals("👩🔧 tools", CategoryKey.of("👩🔧 Tools"))
        assertNotEquals(CategoryKey.of("👩🔧 Tools"), CategoryKey.of(mechanic))
    }

    /**
     * K2: variation selectors (both blocks), combining marks and every `Cf` outside the removed set are
     * kept — the rule is an explicit list, never the whole format category.
     */
    @Test
    fun variationSelectorsCombiningMarksAndOtherFormatCharactersAreKept() {
        assertEquals("☕️ coffee", CategoryKey.of("☕️ Coffee"))
        assertNotEquals(CategoryKey.of("☕ Coffee"), CategoryKey.of("☕️ Coffee"))
        assertEquals("a︀b", CategoryKey.of("A︀B"))
        assertEquals("葛󠄀", CategoryKey.display("葛󠄀")) // U+E0100
        assertEquals("葛󠇯", CategoryKey.display("葛󠇯")) // U+E01EF

        assertEquals("q̇", CategoryKey.of("Q̇")) // no precomposed form: the mark stays
        assertEquals("क्ष", CategoryKey.display("क्ष")) // a virama

        assertEquals("؀١", CategoryKey.display("؀١")) // ARABIC NUMBER SIGN, a visible Cf
    }

    private fun codePoints(text: String): String =
        text.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
}
