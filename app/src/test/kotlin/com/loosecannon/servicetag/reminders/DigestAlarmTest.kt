package com.loosecannon.servicetag.reminders

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one arithmetic in this brief nothing else can check: which **instant** the next digest is
 * armed for.
 *
 * No row here waits on a clock. `nextDigestAt` is a pure function of (now, `T`, the owner's hour,
 * the device zone), which is what lets the two DST cases be asserted as instants rather than
 * observed as deliveries — the D-6 resolution rules are a property of `java.time`, and the point
 * of asserting them is that this brief reads them off `ZonedDateTime.of` rather than reinventing
 * them with a `LocalDateTime.toInstant()` that throws or silently shifts an hour.
 */
class DigestAlarmTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun instantOf(local: String, zone: ZoneId): Long =
        ZonedDateTime.parse("$local[${zone.id}]").toInstant().toEpochMilli()

    @Test
    fun theNextDigestIsTodayWhileTheHourIsStillAheadAndTomorrowOnceItHasPassed() {
        val today = LocalDate.parse("2026-06-15")
        val nine = instantOf("2026-06-15T09:00:00-04:00", newYork)

        assertEquals(
            nine,
            nextDigestAt(nowMillis = nine - 60_000L, today = today, hour = 9, zone = newYork),
        )
        assertEquals(
            "at the hour exactly the next one is tomorrow's: an alarm armed for now would fire twice",
            instantOf("2026-06-16T09:00:00-04:00", newYork),
            nextDigestAt(nowMillis = nine, today = today, hour = 9, zone = newYork),
        )
        assertEquals(
            instantOf("2026-06-16T09:00:00-04:00", newYork),
            nextDigestAt(nowMillis = nine + 60_000L, today = today, hour = 9, zone = newYork),
        )
    }

    /**
     * Both DST edge days in one row (the matrix's one-test-per-hazard rule).
     *
     * 2026-03-08 in New York has no 02:00 at all — the clock jumps 02:00 to 03:00 — and `java.time`
     * resolves a gap **forward**, so a 02:00 digest lands at 03:00 EDT. 2026-11-01 has two 01:00s,
     * and `java.time` resolves an overlap to the **earlier** offset, so a 01:00 digest lands at
     * 01:00 EDT (-04:00) and not the second, EST one. Both are asserted as instants; the delivery
     * is not, because the brief's own contract says a change day may fire twice or not at all and
     * the backstop is what guarantees it eventually fires.
     */
    @Test
    fun theDigestInstantResolvesAGapForwardAndAnOverlapToTheEarlierOffset() {
        val springForward = nextDigestAt(
            nowMillis = instantOf("2026-03-08T00:30:00-05:00", newYork),
            today = LocalDate.parse("2026-03-08"),
            hour = 2,
            zone = newYork,
        )
        assertEquals(instantOf("2026-03-08T03:00:00-04:00", newYork), springForward)

        val fallBack = nextDigestAt(
            nowMillis = instantOf("2026-11-01T00:30:00-04:00", newYork),
            today = LocalDate.parse("2026-11-01"),
            hour = 1,
            zone = newYork,
        )
        assertEquals(instantOf("2026-11-01T01:00:00-04:00", newYork), fallBack)
        assertTrue(
            "the earlier offset is the earlier instant; the later one is an hour further on",
            fallBack < instantOf("2026-11-01T01:00:00-05:00", newYork),
        )
    }

    /**
     * Invariant 61 and D-22, the half that lives in this file: nothing on the arming path may
     * consult the notification permission. A guard here is how granting the permission later
     * leaves the machinery dead, and it is invisible to every behavioural test that runs with the
     * permission granted — so it is asserted structurally, at the source.
     */
    @Test
    fun theArmingAndBackstopPathsNeverConsultTheNotificationPermission() {
        listOf("DigestAlarm.kt", "DigestReceiver.kt", "BackstopWorker.kt").forEach { name ->
            val source = sourceFile("kotlin/com/loosecannon/servicetag/reminders/$name").readText()
            assertTrue(
                "$name must not read the notification permission",
                "NotificationPermission" !in source && "areNotificationsEnabled" !in source,
            )
        }
    }

    /**
     * Invariant 17: derived state has one writer. Every entry point in this brief goes through the
     * recompute seam, and nothing under `reminders/` may reach `schedule_state` directly.
     */
    @Test
    fun nothingInThisPackageWritesDerivedStateDirectly() {
        remindersSourceFiles().forEach { file ->
            assertTrue(
                "${file.name} must not touch ScheduleStateRepository",
                "ScheduleStateRepository" !in file.readText(),
            )
        }
    }
}
