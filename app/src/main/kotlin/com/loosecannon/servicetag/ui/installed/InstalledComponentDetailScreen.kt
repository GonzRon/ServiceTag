package com.loosecannon.servicetag.ui.installed

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.links.LinkLauncher
import com.loosecannon.servicetag.ui.attachments.AttachmentsSection
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.references.ReferencesSection

/**
 * #69 (C27; R69-8, R69-9) — one installed component's files and links, pushed from its row sheet's P69-1. The top bar
 * is the component's name with the shipped "Back"; below it, **two ownerships**, its own first:
 * 1. the component's own — P69-2, then the shipped Documents and References sections keyed by the component, open only
 *    while its asset is held (#77); a removed component's are writable;
 * 2. each SupplyItem its direct link and its composition name, once each ([suppliesNamedBy]) — P69-3 naming it (a tap
 *    opens the SupplyItem's detail and writes nothing), the quiet line P69-4, then that SupplyItem's own Documents and
 *    References, **open only** here: they are added and changed on the SupplyItem's detail. An archived SupplyItem's
 *    group is drawn.
 *
 * Nothing is copied: each section reads its own owner's rows. The screen writes nothing itself; the sections write the
 * component's files and links through their own view models. A component that is not there (its asset deleted while
 * the screen is open, or a restored back stack) sends the person back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledComponentDetailScreen(
    graph: AppGraph,
    componentId: String,
    onBack: () -> Unit,
    /** A group heading's tap: that SupplyItem's own detail. */
    onOpenSupply: (supplyId: String) -> Unit,
    /** The Documents section's no-folder card opens Settings, as the asset detail's does. */
    onOpenSettings: () -> Unit,
) {
    val model: InstalledComponentDetailViewModel = viewModel(key = "installed-component-$componentId") {
        InstalledComponentDetailViewModel(graph, componentId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val missing by model.missing.collectAsStateWithLifecycle()
    // The sections' one-line answers; a section inside a scrolling column has no host of its own.
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(missing) { if (missing) onBack() }

    val current = state
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (current != null) Text(text = current.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        // Nothing to draw until the store has answered once; the way back is enough.
        if (current == null) return@Scaffold
        // One launcher for every section, as on the asset detail; `notify = false` because this screen's snackbar
        // draws the missing-handler line itself.
        val activity = LocalActivity.current
        val openLink: (String) -> Boolean = { uri ->
            activity?.let { LinkLauncher.open(it, uri, notify = false) } == true
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            OwnershipHeading(THIS_INSTALLED_COMPONENT)
            AttachmentsSection(
                graph = graph,
                owner = current.files,
                snackbars = snackbars,
                onOpenSettings = onOpenSettings,
                readOnly = current.readOnly,
                onOpenLink = openLink,
            )
            ReferencesSection(
                owner = current.links,
                graph = graph,
                snackbars = snackbars,
                onOpen = openLink,
                readOnly = current.readOnly,
            )
            current.supplyGroups.forEach { group ->
                SupplyGroupHeading(group.heading, onClick = { onOpenSupply(group.supplyId.value) })
                QuietLine(OPEN_THE_SUPPLY_TO_CHANGE)
                // Open only, whatever the component's asset: a SupplyItem's files and links change on its own detail.
                AttachmentsSection(
                    graph = graph,
                    owner = group.files,
                    snackbars = snackbars,
                    onOpenSettings = onOpenSettings,
                    readOnly = true,
                    onOpenLink = openLink,
                )
                ReferencesSection(
                    owner = group.links,
                    graph = graph,
                    snackbars = snackbars,
                    onOpen = openLink,
                    readOnly = true,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** P69-2 over the component's own sections: one step above their section headers. */
@Composable
private fun OwnershipHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .padding(top = 16.dp)
            .semantics { heading() },
    )
}

/** P69-3, the whole row opening the SupplyItem — the "Used by" rows' shape (C27). */
@Composable
private fun SupplyGroupHeading(text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(top = 24.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .semantics { heading() },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
