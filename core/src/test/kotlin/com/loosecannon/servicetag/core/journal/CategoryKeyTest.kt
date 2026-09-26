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
}
