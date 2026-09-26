package com.loosecannon.servicetag.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.asset.SentenceSectionHeader
import com.loosecannon.servicetag.ui.components.LedgerList
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.setup.ConfirmDialog
import com.loosecannon.servicetag.ui.theme.ControlShape

// #74's Categories words (plan §6, RATIFIED 2026-09-26), each by its P74-number and verbatim. The
// sentences that carry a name or a count are the model's, in CategoriesViewModel.kt. P74-1 is the
// Settings row's own word, and P74-14 ("Delete") is the existing one.

/** P74-2, the screen's title. */
const val CATEGORIES_TITLE = "Categories"

/** P74-3, the owner's section. */
const val YOUR_CATEGORIES = "Your categories"

/** P74-4, the built-ins' section. */
const val BUILT_IN = "Built-in"

/** P74-5, under P74-3 when the owner has no categories: the one way in is a save. */
const val NO_CATEGORIES_YET = "No categories of your own yet. Save an asset with a new category to add one."

/** P74-7, under P74-4. */
const val BUILT_INS_ARE_FIXED = "Built-in categories are always offered and cannot be renamed or deleted."

/** P74-8, the rename dialog's title. */
const val RENAME_CATEGORY = "Rename category"

/** P74-10 and P74-13: the dialog's confirm and the row's menu item, one word in two places. */
const val RENAME = "Rename"

/** P74-15, the delete dialog's title. */
const val DELETE_THIS_CATEGORY = "Delete this category?"

/**
 * Settings → Categories (#74, C17): the owner's own categories with how many assets use each, then the
 * built-ins as quiet rows with no menu — they are compiled, not rows, and cannot be renamed or deleted.
 * There is no "Add": a successful asset save is the one way a category comes in, and P74-5 says so.
 *
 * A row's overflow renames it (a dialog whose refusals are drawn under its field, the dialog staying)
 * or deletes it: an unused one after P74-15/16, one in use not at all, with P74-17 on the snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(graph: AppGraph, onBack: () -> Unit) {
    val model: CategoriesViewModel = viewModel { CategoriesViewModel(graph) }
    val state by model.state.collectAsStateWithLifecycle()
    val rename by model.rename.collectAsStateWithLifecycle()
    val deleting by model.deleting.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }

    rename?.let { draft ->
        RenameDialog(
            draft = draft,
            onText = model::onRenameText,
            onConfirm = model::confirmRename,
            onDismiss = model::dismissRename,
        )
    }
    deleting?.let { ask ->
        ConfirmDialog(
            title = DELETE_THIS_CATEGORY,
            body = notUsedByAnyAsset(ask.display),
            onDismiss = model::dismissDelete,
            onConfirm = model::confirmDelete,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(CATEGORIES_TITLE) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // Nothing to draw until the store has answered once; the title and the way back are enough.
        val current = state ?: return@Scaffold
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Sentence case, as ratified: the upper-casing section header would paraphrase them.
            SentenceSectionHeader(YOUR_CATEGORIES)
            if (current.own.isEmpty()) {
                QuietLine(NO_CATEGORIES_YET)
            } else {
                LedgerList(count = current.own.size) { index ->
                    val row = current.own[index]
                    // Keyed by the row, so a row's menu state stays with it when the list reorders.
                    key(row.key) {
                        OwnCategoryRow(
                            row = row,
                            onRename = { model.startRename(row) },
                            onDelete = { model.requestDelete(row) },
                        )
                    }
                }
            }

            SentenceSectionHeader(BUILT_IN)
            QuietLine(BUILT_INS_ARE_FIXED)
            LedgerList(count = current.builtIns.size, modifier = Modifier.padding(top = 4.dp)) { index ->
                BuiltInRow(current.builtIns[index])
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * One of the owner's categories: its display, how many assets use it, and its menu. The name and the
 * usage line are one node to a screen reader ("Appliance, Used by 2 assets"); the menu stays its own.
 */
@Composable
private fun OwnCategoryRow(row: OwnCategory, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.display,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = usageLine(row.usage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CategoryOverflow(onRename = onRename, onDelete = onDelete)
    }
}

/** The owner's row's menu, in the setup screen's overflow idiom: P74-13, then P74-14. */
@Composable
private fun CategoryOverflow(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text(RENAME) }, onClick = { open = false; onRename() })
        DropdownMenuItem(text = { Text("Delete") }, onClick = { open = false; onDelete() })
    }
}

/** A built-in: its label and nothing else — nothing to count against it here, nothing to change. */
@Composable
private fun BuiltInRow(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(vertical = 10.dp),
    )
}

/**
 * P74-8 over P74-9, pre-filled with the row's display. A refusal is the field's own line in the error
 * colour — the editor's problems-under-fields rule — and the dialog stays, so the owner can change the
 * name or cancel. [RenameDraft.canRename] holds the confirm while the text is blank or unchanged, and
 * the field is read-only while a rename is on its way, so the answer is always about the text it sent.
 */
@Composable
private fun RenameDialog(
    draft: RenameDraft,
    onText: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val refusal = draft.refusal
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(RENAME_CATEGORY) },
        text = {
            OutlinedTextField(
                value = draft.text,
                onValueChange = onText,
                label = { Text("Name") },
                readOnly = draft.renaming,
                singleLine = true,
                isError = refusal != null,
                supportingText = if (refusal == null) null else { { Text(refusal) } },
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = draft.canRename) { Text(RENAME) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
