package com.loosecannon.servicetag.ui.attachments

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentLocator
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.ports.StoreState
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.components.StatusBlock
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The leading square: 56 dp for a thumbnail, and the same box for the kind glyph (spec §8.1). */
private val ThumbnailSize = 56.dp

/** What a row whose bytes are not on this device is drawn at (D12 §5: dimmed, never red). */
private const val MISSING_ALPHA = 0.38f

/**
 * DOCUMENTS, as the Apollo Service Binder draws a list section (D12 §8): a [SectionHeader] with
 * the count in its title, a 56 dp leading thumbnail or kind glyph, a one-line ellipsised name, a
 * quiet `kind · size · captured-on` line, and a trailing overflow. No cards, no FAB.
 */
@Composable
fun DocumentsSection(
    state: AttachmentsSectionState,
    onOpen: (AttachmentRowState) -> Unit,
    onEdit: (AttachmentRowState) -> Unit,
    onAddFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    SectionHeader(
        title = if (state.rows.isEmpty()) "Documents" else "Documents · ${state.rows.size}",
    )
    if (state.rows.isNotEmpty()) {
        Column { state.rows.forEach { row -> DocumentRow(row, onOpen, onEdit) } }
    } else if (state.store is StoreState.Ready) {
        // Only a folder that is actually there can be empty; without one the status block below
        // is the whole story, and "No documents yet" over it would read as the wrong problem.
        QuietLine("No documents yet")
    }
    Spacer(Modifier.height(4.dp))
    // Both add actions are hidden rather than disabled when there is no folder: there is nowhere
    // for the bytes to go, and a greyed button invites a tap that can only fail (spec §8.1).
    if (state.store is StoreState.Ready) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionButton("Add file", ServiceTagIcons.AttachFile, onAddFiles)
            ActionButton("Take photo", ServiceTagIcons.Photo, onTakePhoto)
        }
    } else {
        StatusBlock(
            kind = ServiceTagTheme.semanticColors.seasonInactive,
            headline = "Attachment storage",
            title = "Attachment storage not set up",
            detail = "Choose a folder in Settings",
            icon = ServiceTagIcons.CloudOff,
            leftRule = false,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = onOpenSettings) { Text("Open settings") }
    }
    state.progress?.let { QuietLine(it) }
}

/**
 * The wrapper both detail screens call: it owns the ViewModel, the pickers and the sheet. The
 * screen's own [snackbars] carries the one-line answers, because a section inside a scrolling
 * column has nowhere to host a snackbar of its own.
 */
@Composable
fun AttachmentsSection(
    graph: AppGraph,
    owner: AttachmentOwner,
    snackbars: SnackbarHostState,
    onOpenSettings: () -> Unit,
) {
    // Keyed by owner so an asset and one of its events never share a model within an entry.
    val model: AttachmentsSectionViewModel =
        viewModel(key = "attachments-${AttachmentLocator.dirFor(owner)}") {
            AttachmentsSectionViewModel(graph, owner)
        }
    val state by model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }
    // The sheet closes when the save lands or changed nothing; a refusal leaves it open holding
    // what was typed, so the line the snackbar just showed can be acted on.
    LaunchedEffect(model) { model.saved.collect { id -> if (editing == id) editing = null } }
    LaunchedEffect(model) { model.deleted.collect { id -> if (editing == id) editing = null } }
    // Entering composition is how this section learns that the person went to Settings, chose a
    // folder and came back: the ViewModel outlives the push, so nothing else would tell it.
    LaunchedEffect(model) { model.refreshStore() }

    val pickers = rememberAttachmentPickers(
        graph = graph,
        viewUri = model::viewUri,
        onPicked = model::add,
        onNoViewer = { scope.launch { snackbars.showSnackbar("No app can open this file") } },
        onNoCamera = { scope.launch { snackbars.showSnackbar("No camera app on this device") } },
        onNoFilePicker = { scope.launch { snackbars.showSnackbar("No app can pick files") } },
    )

    DocumentsSection(
        state = state,
        // A row whose bytes are gone says so again rather than launching an intent at nothing.
        onOpen = { row ->
            if (row.present) pickers.open(row)
            else scope.launch { snackbars.showSnackbar("Not on this device") }
        },
        onEdit = { row -> editing = row.id },
        onAddFiles = pickers.addFiles,
        onTakePhoto = pickers.takePhoto,
        onOpenSettings = onOpenSettings,
    )

    // Read back out of the live state, so a rename or a delete redraws (or closes) the sheet.
    state.rows.firstOrNull { it.id == editing }?.let { row ->
        AttachmentEditSheet(
            row = row,
            onSave = { cmd -> model.save(row.id, cmd) },
            onDelete = { model.delete(row.id) },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun DocumentRow(
    row: AttachmentRowState,
    onOpen: (AttachmentRowState) -> Unit,
    onEdit: (AttachmentRowState) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(row) }
            .padding(vertical = 6.dp),
    ) {
        RowLeading(row)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QuietLine(if (row.present) row.quietLine() else "Not on this device")
            // D-19: the description the byte path has stored since Phase 4A and never drawn. It
            // is genuinely a second line, and it is drawn only when the bytes are here — a row
            // whose whole message is that they are gone must not also carry prose.
            if (row.present && row.notes.isNotEmpty()) DescriptionLine(row.notes)
        }
        IconButton(onClick = { onEdit(row) }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "More for ${row.displayName}",
            )
        }
    }
}

/**
 * D-19's second quiet line: [QuietLine]'s own style and colour, held to **one line** and
 * ellipsised, because the description is capped at 2,000 characters and this is a compact row.
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

/** The thumbnail when there is one, otherwise the kind glyph — dimmed when the bytes are gone. */
@Composable
private fun RowLeading(row: AttachmentRowState) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(ThumbnailSize).clip(RoundedCornerShape(4.dp)),
    ) {
        val bitmap = row.thumbnail?.let { rememberThumbnail(it) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(ThumbnailSize),
            )
        } else {
            val tint = MaterialTheme.colorScheme.onSurfaceVariant
            Icon(
                imageVector = row.kind.glyph(),
                contentDescription = null,
                tint = if (row.present) tint else tint.copy(alpha = MISSING_ALPHA),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The cached thumbnail, decoded off the main thread. It is a ≤ 256 px JPEG, but the cache is the
 * OS cache directory and may have been reclaimed between the scan and this frame, so a null
 * result is ordinary and falls back to the glyph.
 */
@Composable
private fun rememberThumbnail(file: File): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    return state.value
}

/** `kind · size · captured-on`, with the parts that are not there left out. */
private fun AttachmentRowState.quietLine(): String =
    listOfNotNull(kind.label(), sizeBytes.asFileSize(), capturedOn).joinToString(" · ")

/** Sentence case, as the chips in the edit sheet show them too (spec §8.1). */
internal fun AttachmentKind.label(): String = when (this) {
    AttachmentKind.PHOTO -> "Photo"
    AttachmentKind.LABEL_PHOTO -> "Label photo"
    AttachmentKind.RECEIPT -> "Receipt"
    AttachmentKind.MANUAL -> "Manual"
    AttachmentKind.WARRANTY -> "Warranty"
    AttachmentKind.DOCUMENT -> "Document"
    AttachmentKind.OTHER -> "Other"
}

/** Broad glyphs only, as the category icons are (D12 §12): a picture, a page, or a paperclip. */
@Composable
private fun AttachmentKind.glyph(): ImageVector = when (this) {
    AttachmentKind.PHOTO, AttachmentKind.LABEL_PHOTO -> ServiceTagIcons.Photo
    AttachmentKind.RECEIPT, AttachmentKind.MANUAL,
    AttachmentKind.WARRANTY, AttachmentKind.DOCUMENT,
    -> ServiceTagIcons.Description
    AttachmentKind.OTHER -> ServiceTagIcons.AttachFile
}

/**
 * Under a kibibyte is plain bytes, then one decimal of KB, then one decimal of MB (spec §8.1).
 * It lives in this file because nothing else needs it; `internal` only so a test can name it.
 */
internal fun Long.asFileSize(): String = when {
    this < 1024L -> "$this B"
    this < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", this / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", this / (1024.0 * 1024.0))
}
