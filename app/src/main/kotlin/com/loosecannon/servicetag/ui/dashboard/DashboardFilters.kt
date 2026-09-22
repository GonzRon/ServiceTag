package com.loosecannon.servicetag.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.ui.maintenance.statusLabel
import com.loosecannon.servicetag.ui.theme.ControlShape

/** The reset option of each control, and the two control labels. All four are RATIFIED (F2). */
internal const val ALL_CATEGORIES = "All categories"
internal const val ALL_STATUSES = "All statuses"
internal const val CATEGORY_LABEL = "Category"
internal const val STATUS_LABEL = "Maintenance status"

/**
 * F2's two filters, and what they may and may not do.
 *
 * A filter **narrows what is listed, never how it is ordered, sectioned or ranked**: it is applied
 * after the lifecycle and search filtering the shipped view model already does, over rows whose
 * `rank` the projection has already assigned. Applying one inside the projection instead would
 * re-derive `rank` over the survivors and silently renumber them, which would break the order
 * `/v1/due`'s clients and B09's sheet share with this screen.
 *
 * [category] null is "All categories" and [status] null is "All statuses" — the absence of a filter
 * rather than a sentinel value, so no option set has to carry a fake member.
 *
 * [categories] is offered from the category values the listed assets actually carry; the status
 * options are the seven ratified words and are offered in full, because an owner looking for
 * "what is paused" should not have to already have something paused to find the control.
 */
data class DashboardFilters(
    val category: String? = null,
    val status: DueStatus? = null,
    val categories: List<String> = emptyList(),
) {
    val isActive: Boolean get() = category != null || status != null
}

/** The seven ratified status words, in the worst-first order a reader scans them in. */
internal val FILTERABLE_STATUSES: List<DueStatus> = listOf(
    DueStatus.OVERDUE, DueStatus.DUE, DueStatus.DUE_SOON, DueStatus.OK,
    DueStatus.INACTIVE_SEASON, DueStatus.PAUSED, DueStatus.NO_DATA,
)

/**
 * The two controls, side by side under the search box. Read-only fields with menus, the same idiom
 * the setup editors use for a value that is picked and never typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardFilterRow(
    filters: DashboardFilters,
    onCategory: (String?) -> Unit,
    onStatus: (DueStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterPicker(
            label = CATEGORY_LABEL,
            value = filters.category ?: ALL_CATEGORIES,
            options = listOf(ALL_CATEGORIES) + filters.categories,
            onPick = { picked -> onCategory(picked.takeIf { it != ALL_CATEGORIES }) },
            modifier = Modifier.weight(1f),
        )
        FilterPicker(
            label = STATUS_LABEL,
            value = filters.status?.let(::statusLabel) ?: ALL_STATUSES,
            options = listOf(ALL_STATUSES) + FILTERABLE_STATUSES.map(::statusLabel),
            onPick = { picked ->
                onStatus(FILTERABLE_STATUSES.firstOrNull { statusLabel(it) == picked })
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterPicker(
    label: String,
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            // The label is the field's accessible name while it is collapsed into the outline, so
            // it is restated here rather than left to the floating label's placement.
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .semantics { contentDescription = label },
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}
