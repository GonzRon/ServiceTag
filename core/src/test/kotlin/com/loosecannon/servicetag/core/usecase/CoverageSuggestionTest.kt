package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.CaseCoverage
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * `suggestCoverage` (#79, C15; R79-6): the coverage a new case's form starts from. The basis is the
 * originating Incident's date — warranty turns on when the unit failed — and the case's `openedOn`
 * when there is no Incident; the expiry day itself is still in warranty; no warranty date is UNKNOWN;
 * PARTLY_COVERED is only ever the owner's answer. It reads no clock: the dates are chosen so that
 * today's date would give the other answer in every dated row.
 */
class CoverageSuggestionTest {

    private data class Row(val label: String, val expiresOn: String?, val incidentOn: String?, val openedOn: String, val expected: CaseCoverage)

    private val table = listOf(
        Row("the Incident on the expiry day", "2020-06-30", "2020-06-30", "2020-08-01", CaseCoverage.IN_WARRANTY),
        Row("the Incident before it, the case opened after", "2020-06-30", "2020-06-01", "2020-07-15", CaseCoverage.IN_WARRANTY),
        Row("the Incident the day after", "2090-06-30", "2090-07-01", "2090-07-01", CaseCoverage.OUT_OF_WARRANTY),
        Row("no Incident: the opening day, on the expiry", "2020-06-30", null, "2020-06-30", CaseCoverage.IN_WARRANTY),
        Row("no Incident: the opening day after it", "2090-06-30", null, "2090-07-01", CaseCoverage.OUT_OF_WARRANTY),
        Row("no warranty date", null, "2020-06-01", "2020-06-01", CaseCoverage.UNKNOWN),
        Row("a warranty date that does not parse", "30/06/2020", "2020-06-01", "2020-06-01", CaseCoverage.UNKNOWN),
        Row("a basis that does not parse", "2020-06-30", "someday", "2020-06-01", CaseCoverage.UNKNOWN),
    )

    @Test
    fun theIncidentsDateDecidesThenTheOpeningDayThenNothing() {
        for (row in table) {
            assertEquals(row.expected, suggestCoverage(row.expiresOn, row.incidentOn, row.openedOn), row.label)
        }
        assertEquals(
            emptyList(),
            table.filter { suggestCoverage(it.expiresOn, it.incidentOn, it.openedOn) == CaseCoverage.PARTLY_COVERED },
            "never suggested",
        )
    }
}
