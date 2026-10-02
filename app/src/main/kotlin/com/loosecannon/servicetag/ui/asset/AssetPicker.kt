package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.ui.components.QuietLine

/**
 * #93 (C3): one asset chosen from the Assets tab's own list — its search box, its Type / Components / Archived
 * controls, its rows and its empty sentences, all drawn by the tab's composables from an [AssetsState] the tab's
 * [AssetsViewModel] built (the Share intake's switched to drop held rows, C2). It decides nothing and holds nothing:
 * no filter, sort, sentence or selection of its own; a tap reports the row and the host does the rest
 * (single-select is the host's). Stateless and view-model-free, so a later owner-picking flow can host it too.
 *
 * One `LazyColumn`: the host's [header] items, then the search box and the controls as items with the tab's
 * paddings, then the rows with the tab's dividers — or, when the list is empty, the tab's [EmptyList] with no
 * add-asset button (nothing is created from here, SPEC:80-81). [query] is the model's own `query` flow (F3), never
 * [AssetsState.query].
 *
 * #69 (C30, C-1, C-4): a host whose list holds no archived row passes [archivedControl] false to omit the Archived
 * control, and [noAssetsLine] to say its own line for [EmptyReason.NO_ASSETS]; the tab passes neither.
 */
@Composable
internal fun AssetPicker(
    state: AssetsState,
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onPickType: (String?) -> Unit,
    onToggleComponents: () -> Unit,
    onToggleArchived: () -> Unit,
    onPick: (AssetRow) -> Unit,
    archivedControl: Boolean = true,
    noAssetsLine: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: LazyListScope.() -> Unit = {},
) {
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        header()
        item {
            SearchBox(
                query = query,
                onQueryChange = onQueryChange,
                onClear = onClearQuery,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            AssetsFilterRow(
                filters = state.filters,
                typeLabel = state.typeLabel,
                typeChoices = state.typeChoices,
                onPickType = onPickType,
                onToggleComponents = onToggleComponents,
                onToggleArchived = onToggleArchived,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                archivedControl = archivedControl,
            )
        }
        if (state.emptyReason == EmptyReason.NONE) {
            itemsIndexed(state.items, key = { _, row -> row.asset.id.value }) { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                AssetListRow(row = row, onClick = { onPick(row) })
            }
        } else {
            item {
                val line = noAssetsLine?.takeIf { state.emptyReason == EmptyReason.NO_ASSETS }
                if (line != null) {
                    QuietLine(text = line, modifier = Modifier.padding(16.dp))
                } else {
                    EmptyList(
                        reason = state.emptyReason,
                        archivedCount = state.archivedCount,
                        onNewAsset = null,
                        onShowArchived = onToggleArchived,
                    )
                }
            }
        }
    }
}
