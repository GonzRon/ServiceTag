package com.loosecannon.servicetag.ui.references

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.links.NO_HANDLER_MESSAGE
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import kotlinx.coroutines.launch

/**
 * The open-time refusal, ratified 2026-09-23 (§10, plan §18.14). **Not** the save-time sentence:
 * nothing is being saved here, the row is already stored, and the two moments got two lines.
 */
private const val BLOCKED_AT_OPEN = "ServiceTag will not open that kind of link."

/**
 * REFERENCES, the section below DOCUMENTS: what a share saved, and what "Add link" adds. It is a
 * separate section and not a mode on DOCUMENTS because the two fail differently — bytes on this
 * phone fail by losing their folder, a pointer elsewhere fails by having no handler (D-10).
 *
 * The wrapper owns the ViewModel and the three sheets; [ReferencesList] draws, and takes its own
 * [onOpen] so the whole open path can be driven without an activity behind it.
 */
@Composable
fun ReferencesSection(
    assetId: AssetId,
    graph: AppGraph,
    snackbars: SnackbarHostState,
    /** Hands the URI to `LinkLauncher`, the one place `ACTION_VIEW` runs; false is no handler. */
    onOpen: (String) -> Boolean,
) {
    val model: ReferencesSectionViewModel = viewModel(key = "references-${assetId.value}") {
        ReferencesSectionViewModel(graph, assetId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(model) { model.saved.collect { id -> if (editing == id) editing = null } }
    LaunchedEffect(model) { model.added.collect { adding = false } }

    ReferencesList(
        state = state,
        snackbars = snackbars,
        onOpen = onOpen,
        onEdit = { row -> editing = row.id },
        onRemove = { row -> removing = row.id },
        onAddLink = { adding = true },
    )

    // Read back out of the live state, so a rename or a removal redraws (or closes) the sheet.
    state.rows.firstOrNull { it.id == editing }?.let { row ->
        ReferenceEditSheet(
            row = row,
            onSave = { cmd -> model.save(row.id, cmd) },
            onDismiss = { editing = null },
        )
    }
    state.rows.firstOrNull { it.id == removing }?.let { row ->
        RemoveReferenceDialog(
            onRemove = { removing = null; model.remove(row.id) },
            onDismiss = { removing = null },
        )
    }
    if (adding) {
        AddLinkSheet(
            onSave = { uri, name, description -> model.addLink(uri, name, description) },
            onDismiss = { adding = false; model.dismissUnknownScheme() },
        )
    }
    state.pendingConfirmation?.let { scheme ->
        UnknownSchemeDialog(
            scheme = scheme,
            onSave = model::confirmUnknownScheme,
            onDismiss = model::dismissUnknownScheme,
        )
    }
}

/**
 * The drawn half: a [SectionHeader] carrying the count, a row per reference, and the section's own
 * "Add link". The open decision lives here rather than in the wrapper so that both of its refusals
 * are readable from a semantics tree — a `Toast` is not, which is why the missing-handler line is
 * a snackbar on this surface even though `LinkLauncher` still toasts for its Settings caller.
 */
@Composable
internal fun ReferencesList(
    state: ReferencesSectionState,
    snackbars: SnackbarHostState,
    onOpen: (String) -> Boolean,
    onEdit: (ReferenceRowState) -> Unit,
    onRemove: (ReferenceRowState) -> Unit,
    onAddLink: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    SectionHeader(
        title = if (state.rows.isEmpty()) "References" else "References · ${state.rows.size}",
    )
    if (state.rows.isEmpty()) {
        QuietLine("No references yet")
    } else {
        Column {
            state.rows.forEach { row ->
                ReferenceRow(
                    row = row,
                    onOpen = {
                        // The policy is asked again here, and a row it now refuses is never handed
                        // on: `onOpen` is not called at all, so nothing reaches `ACTION_VIEW`.
                        val line = when {
                            !row.launchable -> BLOCKED_AT_OPEN
                            onOpen(row.uri) -> null
                            else -> NO_HANDLER_MESSAGE
                        }
                        line?.let { scope.launch { snackbars.showSnackbar(it) } }
                    },
                    onEdit = { onEdit(row) },
                    onRemove = { onRemove(row) },
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onAddLink) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add link")
    }
}

/**
 * The name, the quiet kind label, and the description when there is one. No leading square: a
 * reference has no thumbnail and no presence to dim (I-3), so the row is text and its overflow.
 */
@Composable
private fun ReferenceRow(
    row: ReferenceRowState,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QuietLine(row.kind.label())
            if (row.description.isNotEmpty()) DescriptionLine(row.description)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

/**
 * The description line: [QuietLine]'s own style and colour, held to one line and ellipsised,
 * because the description is capped at 2,000 characters and this is a compact row.
 *
 * It is not [QuietLine] itself only because that primitive takes no `maxLines` and no `overflow`,
 * and the `ui/components` package is shared with the share-intake lane — neither lane may
 * widen a shared primitive, so the line is drawn here and the controller holds the finding.
 */
@Composable
private fun DescriptionLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The three ratified kind words (§10). The enum is never rendered raw. */
private fun ReferenceKind.label(): String = when (this) {
    ReferenceKind.WEB_URL -> "Web link"
    ReferenceKind.NOTE_LINK -> "Note"
    ReferenceKind.OTHER -> "Other"
}
