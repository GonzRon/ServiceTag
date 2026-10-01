package com.loosecannon.servicetag.ui.supplies

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.ui.components.QuietLine

/**
 * #15 (C32): one SupplyItem chosen from the catalog, on `AssetPicker`'s contract — stateless and view-model-free; it
 * decides nothing and holds nothing. Its title is P15-15; its rows are the Supplies list's own rows ([SupplyRow]),
 * drawn in the order given; a tap reports the row and the host does the rest.
 *
 * **The host passes unarchived SupplyItems only** (R15-6): the filter is each host view model's, proven on the JVM,
 * and this composable draws whatever it is handed. With nothing to offer it says P15-16, which points to the
 * Maintenance catalog: nothing is created from here, and there is no search box (out of scope).
 */
@Composable
fun SupplyItemPicker(
    rows: List<SupplyListRow>,
    onPick: (SupplyListRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        item {
            Text(
                text = CHOOSE_A_SUPPLY,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (rows.isEmpty()) {
            item { QuietLine(NO_SUPPLY_ITEMS_YET, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        } else {
            itemsIndexed(rows, key = { _, row -> row.id.value }) { index, row ->
                if (index > 0) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                SupplyRow(row = row, onClick = { onPick(row) })
            }
        }
    }
}

/**
 * [SupplyItemPicker] in a fully expanded bottom sheet — the shape the asset's Supplies section opens it in, and the
 * one the quick-action editor's link control can open it in too. Dismissing it picks nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupplyItemPickerSheet(
    rows: List<SupplyListRow>,
    onPick: (SupplyListRow) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SupplyItemPicker(rows = rows, onPick = onPick, modifier = Modifier.padding(bottom = 24.dp))
    }
}
