package com.loosecannon.servicetag.ui.installed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.QuietLine

/**
 * The Installed components list (#103, 1.7.1; owner ruling Q2), pushed from the Maintenance tab's third
 * peer row: every current installed component of every asset, each row its name over the asset-and-parents
 * path, and a tap opening the asset's detail. Read-only — no add, no search, no filter (R15-8's rule for
 * the Supplies list applies) — because the asset's own section is the one place a component is managed.
 *
 * `ON_START` rather than a once-per-entry effect: a component replaced or removed on the asset's detail
 * is gone from this list on the way back, not the next time the screen is opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledComponentsListScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onOpenAsset: (String) -> Unit,
) {
    val model: InstalledComponentsListViewModel = viewModel { InstalledComponentsListViewModel(graph) }
    val rows by model.rows.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(INSTALLED_COMPONENTS_SECTION) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // Nothing to draw until the store has answered once; the title and the way back are enough.
        val current = rows ?: return@Scaffold
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            if (current.isEmpty()) {
                QuietLine(NO_INSTALLED_COMPONENTS, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            current.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                ComponentListRow(row = row, onClick = { onOpenAsset(row.assetId.value) })
            }
        }
    }
}

/** One row: the component's name, the quiet path beneath it, and the chevron every navigating row carries. */
@Composable
private fun ComponentListRow(row: InstalledComponentListRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = row.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
