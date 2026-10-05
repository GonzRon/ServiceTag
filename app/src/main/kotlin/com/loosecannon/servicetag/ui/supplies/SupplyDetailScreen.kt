package com.loosecannon.servicetag.ui.supplies

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.links.LinkLauncher
import com.loosecannon.servicetag.ui.attachments.AttachmentsSection
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.maintenance.MaintenanceSectionTitle
import com.loosecannon.servicetag.ui.references.ReferencesSection

/**
 * One SupplyItem (#15, C30), on `GroupDetailScreen`'s shape: the top bar is the item's name with the group
 * detail's "Back", "Edit" and "Archive" / "Unarchive"; below it the identity facts that are there, under their
 * one-home labels; **"Specifications"** (P15-6), one "label — value unit" line each, or P15-9; and **"Used by"**
 * (P15-10), one row per applicability row — the asset's name and the role, a tap opening that asset — or P15-11.
 *
 * **Nothing else is drawn.** No key (R15-11: the phone never shows one), no quantity (#95), nothing about what is
 * fitted where (#47). The one addition, below "Used by", is the item's own files and links (#69, C26): the shipped
 * Documents and References sections, keyed by the SupplyItem, writable for an archived item too (R69-10: a SupplyItem
 * is never held).
 * Applicability is added, re-roled and removed on the asset's screen (C33, B8), not here.
 *
 * What it writes itself: `archived_at`, through `ArchiveSupplyItem`; the two sections write the item's own files and
 * links through their own view models. An archived item keeps every applicability row and line link (R15-6); nothing
 * anywhere deletes a SupplyItem (R15-5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyDetailScreen(
    graph: AppGraph,
    supplyId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenAsset: (String) -> Unit,
    /** #69 (C26): the Documents section's no-folder card opens Settings, as the asset detail's does. */
    onOpenSettings: () -> Unit,
) {
    val model: SupplyDetailViewModel = viewModel(key = supplyId) { SupplyDetailViewModel(graph, supplyId) }
    val state by model.state.collectAsStateWithLifecycle()
    val missing by model.missing.collectAsStateWithLifecycle()
    // #69 (C26): the sections' one-line answers; a section inside a scrolling column has no host of its own.
    val snackbars = remember { SnackbarHostState() }

    // A restored back stack or a replacing import can name an item that is not there any more. Leaving is the
    // honest answer; an empty screen would pretend it still exists.
    LaunchedEffect(missing) { if (missing) onBack() }
    // "Used by" is re-read rather than observed (the view model's KDoc): coming back from an asset's screen, where
    // applicability is edited, is the moment to read it again.
    LaunchedEffect(Unit) { model.refresh() }

    val current = state
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (current != null) {
                        Text(text = current.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.supplies_back))
                    }
                },
                actions = {
                    if (current != null) {
                        IconButton(onClick = { onEdit(supplyId) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.supplies_edit))
                        }
                        TextButton(onClick = { model.setArchived(!current.archived) }) {
                            Text(stringResource(if (current.archived) R.string.supplies_unarchive else R.string.supplies_archive))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        // Nothing to draw until the store has answered once; the way back is enough.
        if (current == null) return@Scaffold
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            current.facts.forEach { fact -> FactRow(fact) }

            MaintenanceSectionTitle(SPECIFICATIONS_SECTION)
            if (current.specifications.isEmpty()) {
                QuietLine(NO_SPECIFICATIONS, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            current.specifications.forEach { spec ->
                Text(
                    text = spec.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            MaintenanceSectionTitle(USED_BY_SECTION)
            if (current.usedBy.isEmpty()) {
                QuietLine(NOT_USED_BY_ANY_ASSET, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            current.usedBy.forEachIndexed { index, use ->
                if (index > 0) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                UseRow(use = use, onClick = { onOpenAsset(use.assetId.value) })
            }

            // #69 (C26): the item's own files, then its links, below "Used by" and nowhere else. One launcher for
            // both, as on the asset detail; `notify = false` because this screen's snackbar draws the missing-handler
            // line itself.
            val activity = LocalActivity.current
            val openLink: (String) -> Boolean = { uri ->
                activity?.let { LinkLauncher.open(it, uri, notify = false) } == true
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                AttachmentsSection(
                    graph = graph,
                    owner = AttachmentOwner.OfSupplyItem(current.id),
                    snackbars = snackbars,
                    onOpenSettings = onOpenSettings,
                    readOnly = current.resourcesReadOnly,
                    onOpenLink = openLink,
                )
                ReferencesSection(
                    owner = ReferenceOwner.OfSupplyItem(current.id),
                    graph = graph,
                    snackbars = snackbars,
                    onOpen = openLink,
                    readOnly = current.resourcesReadOnly,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** One identity fact: its label in the sentence case it was ratified in, over the owner's value. */
@Composable
private fun FactRow(fact: SupplyFact) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = fact.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = fact.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One applicability row: the asset's name over the role, the whole row opening the asset (C30). */
@Composable
private fun UseRow(use: SupplyUseRow, onClick: () -> Unit) {
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
                text = use.assetName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = use.role,
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
