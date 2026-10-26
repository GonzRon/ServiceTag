package com.loosecannon.servicetag.hapublish

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * #113/#114 (C31, K14; B4b1 review M6) — the wait to the next local midnight across the zone database's edges: a
 * 23-hour day when the clocks go forward, a day whose midnight does not exist, and a wait from exactly midnight.
 */
class MidnightTickTest {

    private fun waitFrom(local: String, zone: String): Long {
        val id = ZoneId.of(zone)
        return millisUntilNextLocalMidnight(LocalDateTime.parse(local).atZone(id).toInstant().toEpochMilli(), id)
    }

    @Test fun theDayTheClocksGoForwardIsTwentyThreeHours() {
        assertEquals(23 * HOUR, waitFrom("2026-03-29T00:00", "Europe/Berlin"))
    }

    @Test fun aSkippedMidnightWaitsForTheDaysFirstInstant() {
        // On 6 September 2026 Santiago skips from 00:00 to 01:00: the day starts at 01:00, twelve hours after noon.
        assertEquals(12 * HOUR, waitFrom("2026-09-05T12:00", "America/Santiago"))
    }

    @Test fun exactlyMidnightWaitsAWholeDay() {
        assertEquals(24 * HOUR, waitFrom("2026-02-10T00:00", "UTC"))
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
