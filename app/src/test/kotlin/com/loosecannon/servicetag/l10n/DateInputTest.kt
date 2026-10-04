package com.loosecannon.servicetag.l10n

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.testing.EnglishResources
import com.loosecannon.servicetag.testing.ResourcePack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * #102 (PR #106 review) — a date field shows and reads the locale's own order while its form keeps ISO, and a season
 * field the locale's day and month while its form keeps `MM-DD`. English (the unit tests' `en`, which reads month
 * first as a US phone does), German (day first, dots) and Japanese (year first) side by side.
 */
class DateInputTest {

    @After fun english() = AppText.install(EnglishResources())

    @Test fun englishDrawsAndReadsMonthFirst() {
        assertEquals("03/01/2026", dateFieldText("2026-03-01"))
        assertEquals("MM/DD/YYYY", datePlaceholder())
        assertEquals("2026-03-01", dateFieldValue("03/01/2026"))
        assertEquals("2026-03-01", dateFieldValue("3/1/2026"))
        assertEquals("ISO typed is still a day", "2026-03-01", dateFieldValue("2026-03-01"))
        assertEquals("Enter a date as MM/DD/YYYY", localized(R.string.asset_model_bad_date, datePlaceholder()))

        assertEquals("03/01", monthDayFieldText("03-01"))
        assertEquals("MM/DD", monthDayPlaceholder())
        assertEquals("11-01", monthDayFieldValue("11/01"))
    }

    @Test fun germanDrawsAndReadsDayFirst() {
        AppText.install(ResourcePack.pack("de"))
        assertEquals("01.03.2026", dateFieldText("2026-03-01"))
        assertEquals("TT.MM.JJJJ", datePlaceholder())
        assertEquals("2026-03-01", dateFieldValue("01.03.2026"))
        assertEquals("2026-03-01", dateFieldValue(" 1.3.2026 "))
        assertEquals("2026-03-01", dateFieldValue("2026-03-01"))
        assertEquals("Gib ein Datum als TT.MM.JJJJ ein", localized(R.string.asset_model_bad_date, datePlaceholder()))

        assertEquals("01.03", monthDayFieldText("03-01"))
        assertEquals("TT.MM", monthDayPlaceholder())
        assertEquals("03-01", monthDayFieldValue("1.3"))
        assertEquals("a leap day is a real season edge", "02-29", monthDayFieldValue("29.02"))
        assertEquals("canonicalMonthDay is the form's shape", "02-29", canonicalMonthDay(LocalDate.of(2024, 2, 29)))
    }

    @Test fun japaneseDrawsAndReadsYearFirst() {
        AppText.install(ResourcePack.pack("ja"))
        assertEquals("2026/03/01", dateFieldText("2026-03-01"))
        assertEquals("YYYY/MM/DD", datePlaceholder())
        assertEquals("2026-03-01", dateFieldValue("2026/3/1"))
        assertEquals("2026-03-01", dateFieldValue("2026年3月1日"))
    }

    /** Text that is not a date reaches the form as typed, so the form refuses it with its own words. */
    @Test fun whatIsNotADateIsHandedOnAsTyped() {
        AppText.install(ResourcePack.pack("de"))
        for (typed in listOf("31.02.2026", "01.03.26", "01.03", "1/3", "morgen", "01,03,2026", "", "0103202")) {
            assertEquals(typed, dateFieldValue(typed))
            assertNull(typed, parseFieldDate(typed))
        }
        for (typed in listOf("31.04", "13", "1.13", "01.03.2026")) assertEquals(typed, monthDayFieldValue(typed))
        assertEquals("text that is not a stored day is drawn as it is", "01.0", dateFieldText("01.0"))
        assertEquals("01.0", monthDayFieldText("01.0"))
    }

    /** What the field shows while the owner types, and when the form's value replaces it. */
    @Test fun aFieldKeepsWhatIsTypedUntilTheFormsValueMovesElsewhere() {
        AppText.install(ResourcePack.pack("de"))
        val show = { typed: String?, value: String -> shownFieldText(typed, value, ::dateFieldValue, ::dateFieldText) }

        assertEquals("a pre-filled form", "01.03.2026", show(null, "2026-03-01"))
        assertEquals("half typed, the form holds the same text", "01.0", show("01.0", "01.0"))
        assertEquals("an unpadded day is never redrawn under the cursor", "1.3.2026", show("1.3.2026", "2026-03-01"))
        assertEquals("a calendar pick replaces the text", "15.04.2026", show("1.3.2026", "2026-04-15"))
        assertEquals("a reset clears it", "", show("01.0", ""))
    }

    @Test fun everyPackGivesThreeLetters() {
        for (pack in listOf(ResourcePack.english) + ResourcePack.languagePacks) {
            assertEquals(pack.directory.name, 3, pack.strings.getValue("format_date_input_letters").length)
        }
    }
}
