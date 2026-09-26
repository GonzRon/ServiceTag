package com.loosecannon.servicetag.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.loosecannon.servicetag.BuildConfig
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.links.LinkLauncher
import com.loosecannon.servicetag.prefs.AppearanceMode
import com.loosecannon.servicetag.ui.components.LabelValue
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.theme.ControlShape
import kotlinx.coroutines.launch

/** Where the app's source lives. The only outbound link the app ships with. */
private const val PROJECT_URL = "https://github.com/GonzRon/ServiceTag"

/**
 * The few device-local preferences: appearance first.
 *
 * Mode is written to [com.loosecannon.servicetag.prefs.AppPrefs] and then the activity is recreated,
 * because the theme is read once in `setContent`. Recreation is visible — the screen blinks — and
 * that is the accepted Phase 1C behaviour rather than a reactive theme nobody has asked for yet.
 *
 * The Theme row is informational and has no control at all (D12 §14 "Disabled controls":
 * information must not be hidden merely because its action is unavailable).
 *
 * Read / inspect tag is the scan screen kept as a utility (D12 §16 correction, spec §9): normal
 * tag reading is ambient dispatch, so the only reason to open it deliberately is to identify a
 * tag with nothing else prompting the read.
 *
 * Developer API is the third utility (1.1.0, #46) and the only one whose *screen* is the feature:
 * while it is open the app answers commands on this phone's own loopback address, and closing it
 * is what stops that. The row is last, so the two doors the owner already knows keep their places.
 *
 * Categories (#74) is the fourth, after it on the same rule: the owner's own categories, renamed or
 * deleted there. The rows already here keep their places.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onReadTag: () -> Unit,
    onBackup: () -> Unit,
    onDeveloperApi: () -> Unit,
    onCategories: () -> Unit,
) {
    val activity = LocalActivity.current
    val prefs = graph.prefs
    var mode by remember { mutableStateOf(prefs.appearanceMode) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver

    var store by remember { mutableStateOf(graph.attachmentStorage.state()) }
    // Read once: the answer only changes by adding or deleting a file, neither of which happens
    // on this screen, and the question costs a query.
    var attachmentRows by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { attachmentRows = graph.attachments.count() }

    // Moving existing attachments is a later release (spec §11.12), so a *working* folder that
    // already holds some is fixed. The two other states are not:
    //
    //  - `AccessLost`, where re-choosing the same folder is the only repair there is; refusing it
    //    would leave the owner with rows they can never reach again.
    //  - `NotConfigured`, which is what a data-only restore onto a new phone looks like: rows
    //    exist and no folder does, so there is nothing to move and choosing one is the whole
    //    point — the restore of the files archive cannot happen until it is chosen.
    //
    // The same-folder barrier is therefore only about *rows*: with no attachments at all there is
    // nothing to reach again, and insisting on a folder whose grant is gone — a card that was
    // removed, a cloud account that was signed out — would leave the owner unable to choose any
    // folder at all.
    val repairing = store is StoreState.AccessLost && attachmentRows > 0
    val folderFixed = attachmentRows > 0 && store is StoreState.Ready

    val chooseFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { picked ->
        val stored = prefs.attachmentTreeUri
        when {
            picked == null -> Unit
            repairing && stored != null && !sameTree(stored, picked) -> scope.launch {
                snackbars.showSnackbar("That is a different folder. Choose the same one to restore access.")
            }
            else -> {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                // Take first, release second. A provider that will not give a lasting grant (some
                // cloud ones will not) throws here, inside an activity-result callback, where an
                // escaping exception is a crash — and if the new grant is not ours, letting the
                // old one go would leave the owner with no folder at all. So on failure the pref
                // and the old grant are both untouched, and `store` is re-read so the screen
                // still shows what is actually true.
                if (runCatching { resolver.takePersistableUriPermission(picked, flags) }.isFailure) {
                    store = graph.attachmentStorage.state()
                    scope.launch { snackbars.showSnackbar("Could not keep access to that folder") }
                } else {
                    // Grants accumulate otherwise (spike S5), and releasing one the system no
                    // longer holds throws — which must not undo the take that just succeeded.
                    stored?.takeIf { it != picked.toString() }?.let { old ->
                        runCatching { resolver.releasePersistableUriPermission(old.toUri(), flags) }
                    }
                    prefs.attachmentTreeUri = picked.toString()
                    store = graph.attachmentStorage.state()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader(title = "Appearance")
            Column(modifier = Modifier.selectableGroup()) {
                AppearanceMode.entries.forEach { option ->
                    ModeRow(
                        label = modeLabel(option),
                        selected = option == mode,
                        onSelect = {
                            if (option != mode) {
                                mode = option
                                prefs.appearanceMode = option
                                // The theme is read once, in `setContent`; this is what re-reads it.
                                activity?.recreate()
                            }
                        },
                    )
                }
            }

            SectionHeader(title = "Theme")
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LabelValue(label = "Palette", value = "Apollo Service Binder")
                QuietLine("Dynamic colour arrives in a later release")
            }

            SectionHeader(title = "Attachment storage")
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when (val current = store) {
                    StoreState.NotConfigured -> LabelValue(label = "Folder", value = "Not set")
                    is StoreState.Ready -> {
                        LabelValue(label = "Folder", value = current.displayName)
                        LabelValue(label = "Provider", value = current.authority)
                    }
                    is StoreState.AccessLost -> {
                        LabelValue(label = "Folder", value = current.displayName)
                        LabelValue(label = "Provider", value = authorityOf(prefs.attachmentTreeUri))
                        QuietLine("access lost — choose the folder again")
                    }
                }
                QuietLine(
                    "Files are written as ordinary documents in this folder; a sync tool such as " +
                        "Syncthing owns any off-device copy.",
                )
                Button(
                    onClick = { chooseFolder.launch(null) },
                    enabled = !folderFixed,
                    shape = ControlShape,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("Choose folder")
                }
                // D12 §14: the reason a control is unavailable is shown, not hidden with it.
                if (folderFixed) {
                    QuietLine("Moving attachments to another folder arrives in a later release")
                }
            }

            SectionHeader(title = "Utilities")
            UtilityRow(
                icon = ServiceTagIcons.Backup,
                label = "Backup and restore",
                onClick = onBackup,
            )
            UtilityRow(
                icon = ServiceTagIcons.Contactless,
                label = "Read / inspect tag",
                onClick = onReadTag,
            )
            UtilityRow(
                icon = ServiceTagIcons.Speed,
                label = "Developer API",
                onClick = onDeveloperApi,
            )
            UtilityRow(
                icon = ServiceTagIcons.Label,
                label = "Categories",
                onClick = onCategories,
            )

            SectionHeader(title = "About")
            LabelValue(
                label = "Version",
                value = BuildConfig.VERSION_NAME,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            TextButton(
                // A phone with no browser is rare but real (a kiosk, a stripped ROM). The link
                // failing quietly would read as the tap not having registered.
                onClick = {
                    val opened = activity?.let { LinkLauncher.open(it, PROJECT_URL) } ?: false
                    if (!opened) {
                        scope.launch { snackbars.showSnackbar("No browser available for this link") }
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("Source and issues on GitHub")
            }
            QuietLine(PROJECT_URL, Modifier.padding(bottom = 16.dp))
        }
    }
}

/**
 * Whether a picked tree is the folder already stored: same provider, same tree document id. The
 * whole URI cannot be compared — a provider is free to hand back a differently-spelled URI for
 * the same folder — and the id alone cannot either, because two providers may both call a folder
 * `primary:Attachments`.
 */
private fun sameTree(stored: String, picked: Uri): Boolean {
    val old = stored.toUri()
    val oldId = runCatching { DocumentsContract.getTreeDocumentId(old) }.getOrNull()
    val pickedId = runCatching { DocumentsContract.getTreeDocumentId(picked) }.getOrNull()
    return oldId != null && oldId == pickedId && old.authority == picked.authority
}

/** The provider behind the stored tree, for the Provider line when the store cannot answer. */
private fun authorityOf(treeUri: String?): String = treeUri?.toUri()?.authority ?: "unknown"

/** One radio row. The whole row is the target, so the label is not a decoration next to a dot. */
@Composable
private fun ModeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** One navigation row: leading glyph, label, trailing chevron. The whole row is the target. */
@Composable
private fun UtilityRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun modeLabel(mode: AppearanceMode): String = when (mode) {
    AppearanceMode.SYSTEM -> "System"
    AppearanceMode.LIGHT -> "Light"
    AppearanceMode.DARK -> "Dark"
}
