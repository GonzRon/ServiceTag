package com.loosecannon.servicetag.l10n

import com.loosecannon.servicetag.testing.EnglishResources
import com.loosecannon.servicetag.testing.ResourcePack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * #102 (PR #106 review) — numbers and moments the owner reads or types follow the rendering language, while what is
 * stored stays a number or an ISO day. English, the source, and German, a comma-decimal language, side by side.
 */
class LocalizedFormatsTest {

    @After fun english() = AppText.install(EnglishResources())

    @Test fun englishWritesAndReadsDotDecimals() {
        assertEquals("7.25", localizedDecimal(7.25))
        assertEquals("1", localizedDecimal(1.0))
        assertEquals("100", localizedDecimal(100.0))
        assertEquals("1.5", localizedDecimal(1.5, decimals = 1))
        assertEquals("2.00", localizedDecimal(2.0, decimals = 2))
        assertEquals(0.5, parseLocalizedDecimal(" 0.5 ")!!, 0.0)
        assertEquals(-3.0, parseLocalizedDecimal("-3")!!, 0.0)
        assertNull("English never read a comma as a decimal point", parseLocalizedDecimal("0,5"))
        assertNull(parseLocalizedDecimal("1,234.5"))
        assertNull(parseLocalizedDecimal("abc"))
    }

    @Test fun germanWritesAndReadsCommaDecimals() {
        AppText.install(ResourcePack.pack("de"))
        assertEquals(',', localizedDecimalSeparator())
        assertEquals("7,25", localizedDecimal(7.25))
        assertEquals("1,5", localizedDecimal(1.5, decimals = 1))
        assertEquals("the owner's natural entry", 0.5, parseLocalizedDecimal("0,5")!!, 0.0)
        assertEquals("a dot that cannot be a thousands mark is a decimal point", 0.5, parseLocalizedDecimal("0.5")!!, 0.0)
        assertEquals(7.25, parseLocalizedDecimal("7.25")!!, 0.0)
        assertNull("forty-five thousand to a German reader: refused, never read as 45", parseLocalizedDecimal("45.000"))
        assertNull("grouping is never read", parseLocalizedDecimal("1.234,5"))
        assertNull(parseLocalizedDecimal("1,2,3"))
        for (value in listOf(0.5, 7.25, 1234.5, -2.0, 0.001)) {
            assertEquals("$value round-trips", value, parseLocalizedDecimal(localizedDecimal(value))!!, 0.0)
        }
    }

    @Test fun momentsAndDaysAreTheLanguagesOwnAndNeverIsoShaped() {
        val moment = LocalDateTime.of(2026, 3, 1, 9, 30)
        assertEquals("1 Mar 2026, 09:30", localizedDateTime(moment))
        assertEquals("1 Mar 2026", localizedDate("2026-03-01"))
        assertEquals("text that is not an ISO day is shown as it is", "sometime", localizedDate("sometime"))
        for (tag in ResourcePack.languagePacks.map { it.locale.toLanguageTag() }) {
            AppText.install(ResourcePack.pack(tag))
            assertFalse("$tag draws an ISO-shaped date", localizedDate(LocalDate.of(2026, 3, 1)).contains("2026-03-01"))
            assertFalse("$tag draws an ISO-shaped moment", localizedDateTime(moment).contains("2026-03-01"))
            assertTrue("$tag keeps the year", localizedDateTime(moment).contains("2026"))
        }
    }
}
