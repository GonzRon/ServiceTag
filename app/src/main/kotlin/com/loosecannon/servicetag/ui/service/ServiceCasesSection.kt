package com.loosecannon.servicetag.ui.service

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * #79 (C20): one row of the asset detail's Service cases section — the case's title and the quiet
 * line `<status word> · <coverage word>`; [open] while its status is neither CLOSED nor CANCELLED.
 */
data class ServiceCaseRow(val id: String, val title: String, val line: String, val open: Boolean)

/**
 * C20's order: the open cases first, then the closed ones as history, each by `openedOn` newest first,
 * then id. Every case is listed — a closed case is history, never hidden.
 */
fun serviceCaseRowsOf(cases: List<ServiceCase>): List<ServiceCaseRow> = cases
    .sortedWith(
        compareBy<ServiceCase> { it.status.isTerminal }
            .thenByDescending { it.openedOn }
            .thenBy { it.id.value },
    )
    .map { case ->
        ServiceCaseRow(
            id = case.id.value,
            title = case.title,
            line = "${caseStatusWord(case.status)} · ${coverageWord(case.coverage)}",
            open = !case.status.isTerminal,
        )
    }

/**
 * #79 (C20, R79-17): **Service cases** (P79-15), after Condition. P79-16 when there are none;
 * P79-17/P79-18 above the rows only while at least one is open; each row opens its case. P79-19 is
 * offered only while the asset is in service ([offersNew]) and only navigates — nothing here writes.
 */
@Composable
fun ServiceCasesSection(
    rows: List<ServiceCaseRow>,
    openLine: String?,
    offersNew: Boolean,
    onOpenCase: (String) -> Unit,
    onNewCase: () -> Unit,
) {
    SectionHeader(title = SERVICE_CASES)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (rows.isEmpty()) QuietLine(NO_SERVICE_CASES_YET)
        openLine?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        rows.forEach { row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenCase(row.id) }
                    .heightIn(min = 56.dp)
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                QuietLine(row.line)
            }
        }
        if (offersNew) {
            OutlinedButton(onClick = onNewCase, shape = ControlShape) { Text(NEW_SERVICE_CASE) }
        }
    }
}
