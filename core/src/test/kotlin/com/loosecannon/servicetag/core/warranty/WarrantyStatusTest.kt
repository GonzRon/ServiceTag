package com.loosecannon.servicetag.core.warranty

import java.time.LocalDate
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * #79 (C1, K7): the warranty status is derived from the stored date and the `Today` a caller hands
 * in. The expiry day counts as in warranty; no date, or a date that does not parse, is not recorded.
 * The dates are years from the machine's own calendar, so a derivation that read a clock instead of
 * the date it was given could not pass.
 */
class WarrantyStatusTest {

    @Test
    fun theExpiryDayIsInWarrantyAndTheNextDayIsNot() {
        val expiry = "2011-03-15"
        assertEquals(WarrantyStatus.IN_WARRANTY, warrantyStatusOf(expiry, LocalDate.parse("2010-01-01")), "long before")
        assertEquals(WarrantyStatus.IN_WARRANTY, warrantyStatusOf(expiry, LocalDate.parse("2011-03-14")), "the day before")
        assertEquals(WarrantyStatus.IN_WARRANTY, warrantyStatusOf(expiry, LocalDate.parse("2011-03-15")), "the expiry day")
        assertEquals(WarrantyStatus.OUT_OF_WARRANTY, warrantyStatusOf(expiry, LocalDate.parse("2011-03-16")), "the day after")
        assertEquals(WarrantyStatus.OUT_OF_WARRANTY, warrantyStatusOf(expiry, LocalDate.parse("2019-12-31")), "long after")
        assertEquals(
            WarrantyStatus.IN_WARRANTY,
            warrantyStatusOf("2099-12-31", LocalDate.parse("2099-12-31")),
            "a far-future expiry on its own day",
        )
    }

    @Test
    fun noDateIsNotRecorded() {
        assertEquals(WarrantyStatus.NOT_RECORDED, warrantyStatusOf(null, LocalDate.parse("2011-03-15")))
        assertEquals(
            WarrantyStatus.IN_WARRANTY,
            warrantyStatusOf("2011-03-15", LocalDate.parse("2011-03-15")),
            "the same day with a date is recorded",
        )
    }

    @Test
    fun anUnparseableDateIsNotRecorded() {
        for (bad in listOf("", "  ", "2011-02-30", "15/03/2011", "2011-3-15", "soon")) {
            assertEquals(WarrantyStatus.NOT_RECORDED, warrantyStatusOf(bad, LocalDate.parse("2011-03-15")), "\"$bad\"")
        }
        assertEquals(
            WarrantyStatus.OUT_OF_WARRANTY,
            warrantyStatusOf("2011-03-14", LocalDate.parse("2011-03-15")),
            "a well-formed date beside them is read",
        )
    }
}
