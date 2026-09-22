package com.loosecannon.servicetag.ui.maintenance

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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.theme.ControlShape

/**
 * The group form: a name, a description and who is in it (#55, spec §2.3).
 *
 * The member set is drawn as a **selection over assets**, one row per asset, which is the shape that
 * makes the two things `SaveGroup` refuses unreachable from here: there is no way to ask for a
 * second open window on one `(group, asset)` pair (invariant 80), and the save is disabled while the
 * name is blank. Neither refusal is drawn as a sentence, because §17 ratifies none and this brief
 * drafts nothing.
 *
 * D-26: a group's context is its **description** and nothing else. The third field #55's sketch
 * proposed has no column behind it, so this form has no row for it.
 *
 * Group membership is **not an asset field** and appears on no asset form — the asset editor is
 * untouched by this brief, and this is the one place membership is edited.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditScreen(
    graph: AppGraph,
    groupId: String?,
    onDone: (String) -> Unit,
    onBack: () -> Unit,
) {
    val model: GroupEditViewModel =
        viewModel(key = "group-edit:${groupId.orEmpty()}") { GroupEditViewModel(graph, groupId) }
    val state by model.state.collectAsStateWithLifecycle()

    LaunchedEffect(model) { model.saved.collect { onDone(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(MAINTENANCE_GROUP) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    // Disabled rather than refused: a blank name is the one state `SaveGroup`
                    // rejects that this form could otherwise ask for, and §17 has no sentence for
                    // the refusal.
                    TextButton(onClick = model::save, enabled = state.canSave) { Text("Save") }
                },
            )
        },
    ) { padding ->
        // The 16dp gutter is each block's own, because the members heading carries its own — the
        // same heading the detail screen draws, so one feature spells it one way.
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = model::onName,
                label = { Text("Name") },
                singleLine = true,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = model::onDescription,
                label = { Text("Description") },
                minLines = 2,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            MaintenanceSectionTitle(MEMBERS_SECTION)
            state.candidates.forEach { candidate ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { model.toggle(candidate.assetId) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp),
                ) {
                    // The whole row is the toggle; the box reports state and takes no click of
                    // its own, so one tap can never be counted twice.
                    Checkbox(checked = candidate.selected, onCheckedChange = null)
                    Text(
                        text = candidate.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
