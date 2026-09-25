package com.loosecannon.servicetag.ui.health

import com.loosecannon.servicetag.core.health.DriverLine
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.health.NotTrackedReason
import com.loosecannon.servicetag.core.health.SubjectHealth
import com.loosecannon.servicetag.core.health.SubjectValue
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.testing.subjectRow
import com.loosecannon.servicetag.ui.condition.displayDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Health's words are the ratified strings of spec §10.7 (S95–S106, S108–S110, S142, S143), drawn from
 * the engine's data and never from a number of their own; the two quantity-bearing ones go through
 * [HealthPlurals] (plan decision 29), whose Android side the connected `HealthPluralsContractTest`
 * proves over the real resource.
 */
class HealthWordsTest {

    /** English as `plurals.xml` has it: the `one` form drops only the unit's "s". */
    private val english = object : HealthPlurals {
        override fun ageDays(n: Long) = if (n == 1L) "1 day" else "$n days"
        override fun daysOverdue(title: String, n: Long) =
            if (n == 1L) "$title is 1 day overdue" else "$title is $n days overdue"
    }

    private fun scored(id: String, name: String, score: Int, band: HealthBand, sortOrder: Int = 0) = SubjectHealth(
        subject = subjectRow(id, "ups", name = name, sortOrder = sortOrder),
        value = SubjectValue.Scored(score, band, trackedDays = 0),
        lines = emptyList(),
    )

    private fun text(line: DriverLine): String? = driverLineText(line, english, ::displayDate)

    @Test fun bandsDriverLinesAndSummaries() {
        // S95–S98, and NOT TRACKED is the words, never a number (inv. 118).
        assertEquals("NOMINAL", bandWord(HealthBand.NOMINAL))
        assertEquals("WARNING", bandWord(HealthBand.WARNING))
        assertEquals("CRITICAL", bandWord(HealthBand.CRITICAL))
        assertEquals("NOT TRACKED", bandWord(null))
        assertEquals("NOT TRACKED", healthBadgeLabel(null, null))
        assertEquals("NOT TRACKED", healthBadgeLabel(null, 100))
        assertEquals("WARNING 48", healthBadgeLabel(HealthBand.WARNING, 48))
        assertEquals("CRITICAL", healthBadgeLabel(HealthBand.CRITICAL, null))

        // S99–S106, S142 and S143.
        val jan15 = LocalDate.parse("2026-01-15")
        assertEquals("Replaced 15 Jan 2026, 90 days ago", text(DriverLine.Replaced(jan15, 90)))
        assertEquals("No replacement recorded yet", text(DriverLine.NoReplacement))
        assertEquals("The replacement quick action was removed.", text(DriverLine.ProfileRemoved))
        assertEquals("Oil change is 12 days overdue", text(DriverLine.Overdue("Oil change", 12)))
        assertEquals("Oil change is up to date", text(DriverLine.UpToDate("Oil change")))
        assertEquals("Within the 14-day grace period", text(DriverLine.Grace(14)))
        assertEquals("Not tracked while out of season", text(DriverLine.NotTrackedOutOfSeason))
        assertEquals("Not tracked while the schedule is paused", text(DriverLine.NotTrackedPaused))
        assertEquals(
            "Not tracked: the linked schedule has no date rule or belongs to another asset.",
            text(DriverLine.NotTrackedLink),
        )
        // Postponed while not late against the new date (S143); up to date otherwise (S103).
        assertEquals(
            "Oil change was postponed to 1 May 2026",
            text(DriverLine.Postponed("Oil change", LocalDate.parse("2026-05-01"))),
        )

        // An archived link (plan decision 17) arrives with no line, so nothing is drawn for it.
        val archivedLink = SubjectHealth(
            subject = subjectRow("h-oil", "ups", name = "Oil"),
            value = SubjectValue.NotTracked(NotTrackedReason.SCHEDULE_ARCHIVED),
            lines = emptyList(),
        )
        assertTrue(driverLines(archivedLink, english, ::displayDate).isEmpty())
        assertEquals("NOT TRACKED", bandWord((archivedLink.value as? SubjectValue.Scored)?.band))
        assertEquals(
            listOf("Oil change is 3 days overdue", "Within the 14-day grace period"),
            driverLines(
                archivedLink.copy(lines = listOf(DriverLine.Overdue("Oil change", 3), DriverLine.Grace(14))),
                english,
                ::displayDate,
            ),
        )

        // S108: the contributors with a value, in sortOrder; an untracked subject is not one.
        val battery = scored("h1", "Battery", 22, HealthBand.CRITICAL, sortOrder = 0)
        val fan = scored("h2", "Fan", 100, HealthBand.NOMINAL, sortOrder = 1)
        val filter = SubjectHealth(
            subject = subjectRow("h3", "ups", name = "Filter", sortOrder = 2),
            value = SubjectValue.NotTracked(NotTrackedReason.NO_REPLACEMENT),
            lines = listOf(DriverLine.NoReplacement),
        )
        assertEquals("74 — Battery 22, Fan 100", aggregateLine(74, listOf(battery, fan, filter)))

        // S109 and S110.
        assertEquals("Critical: Battery 22", criticalLine(battery))
        assertEquals(
            "Battery WARNING",
            dashboardHealthRow(SubjectBandFact(HealthSubjectId("h1"), "Battery", HealthBand.WARNING, 48)),
        )
    }

    @Test fun oneAndOtherGoThroughThePlurals() {
        val ages = mutableListOf<Long>()
        val overdue = mutableListOf<Pair<String, Long>>()
        val recording = object : HealthPlurals {
            override fun ageDays(n: Long): String { ages += n; return "<age $n>" }
            override fun daysOverdue(title: String, n: Long): String { overdue += title to n; return "<$title overdue $n>" }
        }
        val apr2 = LocalDate.parse("2026-04-02")

        assertEquals("Replaced 2 Apr 2026, <age 1> ago", driverLineText(DriverLine.Replaced(apr2, 1), recording, ::displayDate))
        assertEquals("Replaced 2 Apr 2026, <age 5> ago", driverLineText(DriverLine.Replaced(apr2, 5), recording, ::displayDate))
        assertEquals("<Oil change overdue 1>", driverLineText(DriverLine.Overdue("Oil change", 1), recording, ::displayDate))
        assertEquals("<Oil change overdue 5>", driverLineText(DriverLine.Overdue("Oil change", 5), recording, ::displayDate))

        // The quantity itself reaches the plurals, so `one` and `other` are the resource's to pick.
        assertEquals(listOf(1L, 5L), ages)
        assertEquals(listOf("Oil change" to 1L, "Oil change" to 5L), overdue)
        // S104 is singular-safe as written and needs no plural.
        assertEquals("Within the 1-day grace period", driverLineText(DriverLine.Grace(1), recording, ::displayDate))
        assertEquals(2, overdue.size)
    }
}
