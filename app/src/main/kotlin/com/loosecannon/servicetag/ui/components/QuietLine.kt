package com.loosecannon.servicetag.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * The empty state of G1 §1.1: one quiet line, no red and no illustration —
 * "No schedule yet", "No entries yet · Log maintenance to start".
 *
 * [maxLines] and [overflow] default to exactly what `Text` defaults to on its own, so every call
 * site written before they existed renders character for character as it did. A compact list row
 * passes `maxLines = 1` with [TextOverflow.Ellipsis] for a line that can run long — a document's
 * or a reference's description, which is prose the owner wrote (D-19).
 */
@Composable
fun QuietLine(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}
