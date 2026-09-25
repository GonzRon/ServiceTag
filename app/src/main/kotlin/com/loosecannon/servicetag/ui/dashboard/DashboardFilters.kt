package com.loosecannon.servicetag.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
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
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.schedule.DueStatus
import com.loosecannon.servicetag.ui.condition.conditionOption
import com.loosecannon.servicetag.ui.maintenance.DueItem
import com.loosecannon.servicetag.ui.maintenance.statusLabel
import com.loosecannon.servicetag.ui.theme.BadgeShape
import com.loosecannon.servicetag.ui.theme.ControlShape

/** The reset option of each control, and the two control labels. All four are RATIFIED (F2). */
internal const val ALL_CATEGORIES = "All categories"
internal const val ALL_STATUSES = "All statuses"
internal const val CATEGORY_LABEL = "Category"
internal const val STATUS_LABEL = "Maintenance status"

/** S26: the condition chip for an asset with no condition row. B13 owns it (master plan §19). */
internal const val CHIP_NOT_RECORDED = "Not recorded"

/**
 * The four condition chips (spec §10.2; master plan §13.2), in the ratified order S8, S10, S12,
 * S26. [NOT_RECORDED] is the absence of a condition row — nothing stores an UNKNOWN — so it matches
 * a null condition and nothing else.
 */
enum class ConditionChip(val condition: OperationalCondition?) {
    OPERATIONAL(OperationalCondition.OPERATIONAL),
    DEGRADED(OperationalCondition.DEGRADED),
    DOWN(OperationalCondition.DOWN),
    NOT_RECORDED(null),
    ;

    /** S8, S10 and S12 through B12's words, and S26. */
    val label: String get() = condition?.let(::conditionOption) ?: CHIP_NOT_RECORDED

    fun matches(current: OperationalCondition?): Boolean = current == condition
}

/**
 * F2's filters and 1.4's condition chips, and what they may and may not do.
 *
 * A filter **narrows what is listed, never how it is ordered, sectioned or ranked**: it is applied
 * after the lifecycle filtering the view model already does, over rows whose
 * `rank` the projection has already assigned. Applying one inside the projection instead would
 * re-derive `rank` over the survivors and silently renumber them, which would break the order
 * `/v1/due`'s clients and B09's sheet share with this screen.
 *
 * [category] null is "All categories" and [status] null is "All statuses" — the absence of a filter
 * rather than a sentinel value, so no option set has to carry a fake member. [conditions] empty is
 * likewise no condition filter at all, which is why the chip row needs no "All" chip and no label
 * (plan decision 26).
 *
 * [categories] is offered from the category values the listed assets actually carry; the status
 * options are the eight ratified words and are offered in full, because an owner looking for
 * "what is paused" should not have to already have something paused to find the control.
 */
data class DashboardFilters(
    val category: String? = null,
    val status: DueStatus? = null,
    val conditions: Set<ConditionChip> = emptySet(),
    val categories: List<String> = emptyList(),
) {
    val isActive: Boolean get() = category != null || status != null || conditions.isNotEmpty()
}

/**
 * The eight ratified status words, in the worst-first order a reader scans them in. DEFERRED sits
 * where its section does, between CURRENT's OK and OUT OF SEASON (spec §4.5), and its option text is
 * its status word, S92, like the seven shipped options (plan decision 26).
 */
internal val FILTERABLE_STATUSES: List<DueStatus> = listOf(
    DueStatus.OVERDUE, DueStatus.DUE, DueStatus.DUE_SOON, DueStatus.OK,
    DueStatus.DEFERRED, DueStatus.INACTIVE_SEASON, DueStatus.PAUSED, DueStatus.NO_DATA,
)

/**
 * The filter over a **schedule** row (master plan §13.2, plan decision 26). It passes when it is in
 * the chosen category, matches the status picker if one is set, **and** — when any condition chip
 * is selected — its target Asset's condition matches one of them. The condition is the one the
 * projection handed over on the row, a component's own; this computes none. A **group** row has no
 * condition, so it passes only while no chip is selected, "Not recorded" included.
 */
internal fun DashboardFilters.admits(item: DueItem): Boolean =
    (category == null || item.category == category) &&
        (status == null || item.status == status) &&
        (conditions.isEmpty() || (item.target is ScheduleTarget.AssetTarget && conditions.any { it.matches(item.assetCondition) }))

/**
 * The filter over an **asset-level** row — a condition or health row, or a plain asset row — whose
 * Asset is in [rowCategory] and currently in [condition] (null: none recorded).
 *
 * The status picker selects schedule rows. An asset row has no maintenance status, so it passes when
 * **no status is selected or at least one condition chip is**, and it matches the chips (if any):
 * asset rows hide only when **only** a status is selected (spec §10.2, the controller's ruling on
 * M14). Category applies to both kinds.
 */
internal fun DashboardFilters.admitsAssetRow(rowCategory: String?, condition: OperationalCondition?): Boolean =
    (category == null || rowCategory == category) &&
        (status == null || conditions.isNotEmpty()) &&
        (conditions.isEmpty() || conditions.any { it.matches(condition) })

/**
 * The filters over the drawn sections: each row is kept or dropped where it stands, and a section
 * left with no rows is dropped — **nothing is re-sorted, re-ranked or moved between sections**.
 */
internal fun DashboardFilters.narrow(sections: List<AttentionGroup>): List<AttentionGroup> =
    sections.mapNotNull { group ->
        group.entries
            .filter { entry ->
                when (entry) {
                    is SectionEntry.Schedule -> admits(entry.item)
                    is SectionEntry.AssetLevel -> admitsAssetRow(entry.category, entry.item.assetCondition)
                }
            }
            .takeIf { it.isNotEmpty() }
            ?.let { group.copy(entries = it) }
    }

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

/**
 * The four condition chips, multi-select, under the two pickers. No label and no "All" chip: none
 * selected is no condition filter (plan decision 26), and each chip's word is the only text.
 */
@Composable
fun ConditionChipRow(
    selected: Set<ConditionChip>,
    onToggle: (ConditionChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        ConditionChip.entries.forEach { chip ->
            FilterChip(
                selected = chip in selected,
                onClick = { onToggle(chip) },
                label = { Text(chip.label) },
                shape = BadgeShape,
            )
        }
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
