package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.testing.compositionEntryOf
import com.loosecannon.servicetag.core.usecase.InstalledComponentProblem.AfterToday
import com.loosecannon.servicetag.core.usecase.InstalledComponentProblem.BadDate
import com.loosecannon.servicetag.core.usecase.InstalledComponentProblem.NameRequired
import com.loosecannon.servicetag.core.usecase.InstalledComponentProblem.QuantityInvalid
import com.loosecannon.servicetag.core.usecase.InstalledComponentProblem.RemovedBeforeInstalled
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #47 (B1a; row 4): the shape of an installed component and of its composition, whatever wrote it. A use case
 * passes its today; the backup content check passes none, so a restore never judges a row by the importing
 * phone's date. The names are fictional.
 */
class InstalledComponentShapeTest {

    private val today = LocalDate.parse("2026-10-01")

    @Test fun aWellFormedRowHasNoProblems() {
        assertEquals(emptyList(), installedComponentProblems("Example Battery Tray", "2026-09-01", null, today))
        assertEquals(emptyList(), installedComponentProblems("Position 1", null, null, today))
        assertEquals(emptyList(), installedComponentProblems("Position 1", null, "2026-09-01", today))
        assertEquals(emptyList(), installedComponentProblems("Position 1", "2026-09-01", "2026-09-01", today))
        assertEquals(emptyList(), installedComponentProblems("Position 1", "2026-10-01", "2026-10-01", today))
    }

    @Test fun aBlankNameIsNameRequired() {
        assertEquals(listOf(NameRequired), installedComponentProblems("", null, null, today))
        assertEquals(listOf(NameRequired), installedComponentProblems(" \t ", "2026-09-01", null))
    }

    @Test fun badDatesAreReportedByField() {
        assertEquals(listOf(BadDate("installedOn")), installedComponentProblems("Position 1", "2026-13-01", null, today))
        assertEquals(listOf(BadDate("installedOn")), installedComponentProblems("Position 1", "2026-9-1", null))
        assertEquals(listOf(BadDate("removedOn")), installedComponentProblems("Position 1", "2026-09-01", "01/09/2026", today))
        assertEquals(listOf(BadDate("removedOn")), installedComponentProblems("Position 1", null, "", today))
        assertEquals(
            listOf(BadDate("installedOn"), BadDate("removedOn")),
            installedComponentProblems("Position 1", "2026-02-30", "yesterday"),
        )
    }

    @Test fun afterTodayOnlyWithAToday() {
        assertEquals(emptyList(), installedComponentProblems("Position 1", "2026-10-02", null))
        assertEquals(emptyList(), installedComponentProblems("Position 1", "2026-10-02", "2026-10-03"))

        assertEquals(listOf(AfterToday("installedOn")), installedComponentProblems("Position 1", "2026-10-02", null, today))
        assertEquals(listOf(AfterToday("removedOn")), installedComponentProblems("Position 1", "2026-09-01", "2026-10-02", today))
        assertEquals(listOf(AfterToday("removedOn")), installedComponentProblems("Position 1", null, "2027-01-01", today))
    }

    @Test fun removedBeforeInstalled() {
        assertEquals(
            listOf(RemovedBeforeInstalled("removedOn")),
            installedComponentProblems("Position 1", "2026-09-10", "2026-09-09", today),
        )
        assertEquals(
            listOf(RemovedBeforeInstalled("removedOn")),
            installedComponentProblems("Position 1", "2026-09-10", "2026-09-09"),
        )
        // An unknown or unreadable install date has nothing to be before.
        assertEquals(emptyList(), installedComponentProblems("Position 1", null, "2020-01-01", today))
        assertEquals(listOf(BadDate("installedOn")), installedComponentProblems("Position 1", "not a date", "2020-01-01", today))
    }

    @Test fun aZeroNegativeOrNonFiniteQuantityIsRefusedByIndex() {
        val entries = listOf(1.0, 0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY, 0.25, Double.NEGATIVE_INFINITY, -0.0)
            .mapIndexed { index, quantity -> compositionEntryOf("e$index", "s1", quantity = quantity, sortOrder = index) }

        assertEquals(
            listOf(QuantityInvalid(1), QuantityInvalid(2), QuantityInvalid(3), QuantityInvalid(4), QuantityInvalid(6), QuantityInvalid(7)),
            compositionProblems(entries),
        )
    }

    @Test fun aWellFormedOrEmptyCompositionHasNoProblems() {
        assertEquals(emptyList(), compositionProblems(emptyList()))
        assertEquals(
            emptyList(),
            compositionProblems(
                listOf(
                    compositionEntryOf("e1", "s1", quantity = 4.0, unit = "ea"),
                    compositionEntryOf("e2", "s1", quantity = 4.0, unit = "ea", sortOrder = 1),
                    compositionEntryOf("e3", "s2", quantity = 0.5, unit = "L", sortOrder = 2),
                ),
            ),
        )
    }

    @Test fun everyProblemIsCollected() {
        assertEquals(
            listOf(NameRequired, AfterToday("installedOn"), RemovedBeforeInstalled("removedOn"), AfterToday("removedOn")),
            installedComponentProblems("  ", "2026-12-01", "2026-11-01", today),
        )
        assertEquals(
            listOf(NameRequired, BadDate("installedOn"), AfterToday("removedOn")),
            installedComponentProblems("", "2026-1-1", "2026-12-01", today),
        )
        assertEquals(
            listOf(NameRequired, BadDate("installedOn"), BadDate("removedOn")),
            installedComponentProblems("", "?", "?", today),
        )
    }
}
