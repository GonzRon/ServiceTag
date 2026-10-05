package com.loosecannon.servicetag.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.nav.Route
import com.loosecannon.servicetag.ui.nfc.ReaderMode
import com.loosecannon.servicetag.ui.nfc.TagSinkEffect
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.MonoText
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import com.loosecannon.servicetag.ui.theme.PlateShape

/**
 * Write a v1 payload to a physical tag, with the Phase 1B overwrite guard in front of it (D3 §9):
 * read first, ask before replacing anything, write off the main thread, read back and compare,
 * and lock — if the user armed it — only once the read-back has proved what is on the tag.
 *
 * The screen is hosted by the nav shell, which owns the activity's one reader-mode session as of
 * 2.7 (#37), so this screen owns no reader-mode session of its own; every decision about a tag
 * belongs to [TagWriteController].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteTagScreen(
    graph: AppGraph,
    readerMode: ReaderMode,
    key: Route.WriteTag,
    onDone: () -> Unit,
) {
    val target = remember(key) { key.target() }
    val model: WriteTagViewModel = viewModel(key = "write/${key.targetKind}/${key.targetId}") {
        WriteTagViewModel(graph, target, key.label)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val lock by model.lock.collectAsStateWithLifecycle()
    val targetName by model.targetName.collectAsStateWithLifecycle()
    val placement by model.placement.collectAsStateWithLifecycle()
    val placementLocked by model.placementLocked.collectAsStateWithLifecycle()

    // The activity owns the one reader-mode session (#37, R1): arriving here from the inspect
    // screen is a change of sink, not a hand-over of NFC, so nothing can land between the two.
    TagSinkEffect(readerMode) { tag -> model.onTag(tag) }

    var warnAboutLock by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tag_write_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.tag_write_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TargetLine(targetName)
            PlacementField(value = placement, onValueChange = model::setPlacement, enabled = !placementLocked)
            WriteStatus(state, targetName, onDone)
            NfcAvailabilityLine(readerMode)
            LockSwitch(
                checked = lock,
                onCheckedChange = { checked -> if (checked) warnAboutLock = true else model.setLock(false) },
            )
        }
    }

    val asking = state as? WriteState.Confirm
    if (asking != null) {
        OverwriteSheet(
            subject = asking.subject,
            target = targetName,
            onOverwrite = model::confirmOverwrite,
            onKeepIt = model::keepIt,
        )
    }

    if (warnAboutLock) {
        LockWarning(
            onLock = { warnAboutLock = false; model.setLock(true) },
            // Backing out of the warning is not consent: the switch stays off.
            onCancel = { warnAboutLock = false; model.setLock(false) },
        )
    }
}

/** What this tag will end up identifying, said before anything is written. */
@Composable
private fun TargetLine(targetName: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = PlateShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.tag_write_target),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = targetName, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/**
 * Optional, and it stays optional (#49): an ordinary one-tag asset leaves this blank. Typed here
 * before the first tap, it is carried into the row [TagWriteController] provisions as the new
 * row's placement — the same "Tag placement" value the asset detail's tags section later lists
 * and lets the owner edit in place.
 *
 * [enabled] goes false the instant the row is provisioned (review fix round 1, finding 2): the
 * row is provisioned once and reused across retries, so text typed after that tap would otherwise
 * be silently discarded. Disabling the field is the fix, not writing the label a second time —
 * that would be the second write path the brief's fourth surface rules out.
 */
@Composable
private fun PlacementField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.tag_placement)) },
        singleLine = true,
        enabled = enabled,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The four states of the write flow, each in the family its meaning asks for (D12 §5, §11). */
@Composable
internal fun WriteStatus(state: WriteState, targetName: String, onDone: () -> Unit) {
    when (state) {
        is WriteState.Idle -> NfcSheet(
            eyebrow = stringResource(R.string.tag_write_eyebrow_idle),
            glyph = ServiceTagIcons.NfcTag,
            sentence = state.message,
        )

        is WriteState.Confirm -> NfcSheet(
            eyebrow = stringResource(R.string.tag_write_eyebrow_confirm),
            accent = ServiceTagTheme.semanticColors.dueSoon.foreground,
            border = ServiceTagTheme.semanticColors.dueSoon.foreground,
            glyph = ServiceTagIcons.NfcTag,
            sentence = stringResource(R.string.tag_write_answer_here),
        )

        is WriteState.Written -> NfcSheet(
            eyebrow = stringResource(R.string.tag_write_eyebrow_written),
            accent = ServiceTagTheme.semanticColors.maintenanceOkay.foreground,
            glyph = ServiceTagIcons.NfcTag,
            sentence = targetName,
            identifier = stringResource(
                if (state.locked) R.string.tag_written_locked else R.string.tag_written_rewritable,
                "${state.tagId.take(8)} · v1",
            ),
            actions = { FilledAction(stringResource(R.string.tag_write_done), onDone) },
        ) {
            // The verification gets its own OK-container line: it is the claim the screen makes.
            VerifiedLine()
        }

        is WriteState.Error -> NfcSheet(
            eyebrow = stringResource(R.string.tag_write_eyebrow_error),
            accent = ServiceTagTheme.semanticColors.destructiveAction.foreground,
            border = ServiceTagTheme.semanticColors.destructiveAction.foreground,
            glyph = ServiceTagIcons.NfcTag,
            sentence = state.message,
        )
    }
}

/** The proof, in the OK family — cool blue, never green (D12 §5). */
@Composable
private fun VerifiedLine() {
    Surface(
        color = ServiceTagTheme.semanticColors.maintenanceOkay.container,
        contentColor = ServiceTagTheme.semanticColors.maintenanceOkay.foreground,
        shape = PlateShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.tag_write_verified),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * "Overwrite?" as its own sheet (G1 §1.4): one warning line in the due-soon family naming what is
 * on the tag, Overwrite filled and Keep it outlined. Brick is not used — nothing here is an error.
 *
 * The warning block carries the [subject]'s line and, for a ServiceTag id, the quiet mono line
 * under it — the shortened id and any placement — in the surface's own content colour (#70 C5):
 * secondary identity, never the explanation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverwriteSheet(
    subject: OverwriteWords,
    target: String,
    onOverwrite: () -> Unit,
    onKeepIt: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onKeepIt) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.tag_overwrite_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = ServiceTagTheme.semanticColors.dueSoon.container,
                contentColor = ServiceTagTheme.semanticColors.dueSoon.foreground,
                shape = PlateShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = subject.line, style = MaterialTheme.typography.bodyMedium)
                    subject.identifier?.let { Text(text = it, style = MonoText) }
                }
            }
            Text(
                text = stringResource(R.string.tag_overwrite_explanation, target),
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledAction(stringResource(R.string.tag_overwrite_confirm), onOverwrite)
                OutlinedAction(stringResource(R.string.tag_overwrite_keep), onKeepIt)
            }
        }
    }
}

/** Locking is the one irreversible thing this app does, so it is asked for in those words. */
@Composable
private fun LockWarning(onLock: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.tag_lock_warning_title)) },
        text = { Text(stringResource(R.string.tag_lock_warning_text)) },
        confirmButton = { TextButton(onClick = onLock) { Text(stringResource(R.string.tag_lock_warning_confirm)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.tag_lock_warning_dismiss)) } },
        shape = ControlShape,
    )
}

@Composable
private fun LockSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.tag_lock_switch), style = MaterialTheme.typography.titleSmall)
            QuietLine(stringResource(R.string.tag_lock_switch_hint))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NfcAvailabilityLine(readerMode: ReaderMode) {
    val line = when {
        !readerMode.present -> R.string.tag_write_needs_window
        !readerMode.available -> R.string.nfc_no_hardware
        !readerMode.enabled -> R.string.nfc_turned_off
        else -> null
    }
    line?.let { QuietLine(stringResource(it)) }
}

/** The route carries ids, never objects; this is the one place they become a [TagTarget] again. */
internal fun Route.WriteTag.target(): TagTarget = when (targetKind) {
    "asset" -> targetId?.let { TagTarget.AssetTarget(AssetId(it)) } ?: TagTarget.None
    else -> TagTarget.None
}
