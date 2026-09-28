package com.loosecannon.servicetag.core.model

import com.loosecannon.servicetag.core.testing.loanOf
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * #72 (C1; AC 13): a loan's standing is derived at read time — lent out through its due day, overdue
 * from the day after, never overdue with no due date, and nothing at all once returned — and the model
 * that derives it borrows nothing from the maintenance schedule or from health: an overdue loan is not
 * an overdue job. The names are fictional.
 */
class AssetLoanTest {

    private fun day(value: String) = LocalDate.parse(value)

    @Test
    fun theDueDayIsLentOutAndTheNextDayIsOverdue() {
        val loan = loanOf("l1", lentOn = "2026-09-20", dueOn = "2026-10-04")

        assertEquals(LoanStanding.LENT_OUT, loan.standingOn(day("2026-09-20")), "the day it was lent")
        assertEquals(LoanStanding.LENT_OUT, loan.standingOn(day("2026-10-03")), "the day before the due day")
        assertEquals(LoanStanding.LENT_OUT, loan.standingOn(day("2026-10-04")), "the due day itself")
        assertEquals(LoanStanding.OVERDUE, loan.standingOn(day("2026-10-05")), "the day after the due day")
        assertEquals(LoanStanding.OVERDUE, loan.standingOn(day("2027-01-01")), "and every day after that")
    }

    @Test
    fun aReturnedLoanHasNoStanding() {
        val returned = loanOf("l1", dueOn = "2026-10-04", returnedOn = "2026-10-10")

        assertNull(returned.standingOn(day("2026-10-05")))
        assertNull(returned.standingOn(day("2027-01-01")), "long after its due date, a returned loan is history")
        assertTrue(!returned.isOpen)
    }

    @Test
    fun noDueDateIsNeverOverdue() {
        val open = loanOf("l1", dueOn = null, reminderMode = LoanReminderMode.NONE)

        assertEquals(LoanStanding.LENT_OUT, open.standingOn(day("2026-09-20")))
        assertEquals(LoanStanding.LENT_OUT, open.standingOn(day("2099-12-31")))
        assertTrue(open.isOpen)
    }

    /**
     * AC 13 on the source: the standing is custody's own word. `AssetLoan.kt` imports nothing from
     * `core.schedule` or `core.health` and names no maintenance status or health band, so no mapping of
     * OVERDUE onto `DueStatus` — or a health value — can live beside it.
     */
    @Test
    fun theLoanModelImportsNoScheduleOrHealth() {
        val source = repoRoot().resolve("core/src/main/kotlin/com/loosecannon/servicetag/core/model/AssetLoan.kt")
        assertTrue(source.isFile, "no loan model at $source")
        val offenders = source.readLines().mapIndexedNotNull { index, line ->
            "${index + 1}: $line".takeIf {
                Regex("""^import .*\.core\.(schedule|health)\.""").containsMatchIn(line.trim()) ||
                    Regex("""\b(DueStatus|HealthBand|SubjectValue)\b""").containsMatchIn(line)
            }
        }
        assertEquals(emptyList(), offenders)
    }

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("cannot find the repository root from ${File(".").absolutePath}")
        }
        return dir
    }
}
