package com.loosecannon.servicetag.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The date column's width — also the left indent a row's own content (such as a `TagPlacement`
 * caption in the asset detail's tags section) lines up under, so the two never drift apart as two
 * independent `64.dp` literals (review fix round 1, nit 5).
 */
val LedgerDateColumnWidth = 64.dp

/**
 * One row of the chronological service record of D12 §8 — a maintenance record, not an activity
 * feed. The date anchors a [LedgerDateColumnWidth] left column; the rule between entries belongs
 * to [LedgerList] (G1 §1.1 "Ledger": 1dp `outlineVariant` between entries, none around the block).
 */
@Composable
fun LedgerEntry(
    day: String,
    month: String,
    year: String,
    title: String,
    detail: String? = null,
    badge: (@Composable () -> Unit)? = null,
    meta: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.padding(vertical = 10.dp)) {
        Column(modifier = Modifier.width(LedgerDateColumnWidth)) {
            Text(
                text = "$day ${month.uppercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = year,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    // A long title yields space rather than squeezing the result badge to zero.
                    modifier = Modifier.weight(1f, fill = false),
                )
                badge?.invoke()
            }
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),  // G1 §1.1: detail 13sp
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Stacks rows with the 1dp `outlineVariant` rule between them and none around — the ledger idiom
 * of G1 §1.1. [LedgerEntry] is what it usually stacks; any row that belongs in a ruled list, such
 * as the setup screen's readings and actions, uses it for the same rule.
 */
@Composable
fun LedgerList(count: Int, modifier: Modifier = Modifier, entry: @Composable (Int) -> Unit) {
    Column(modifier = modifier) {
        repeat(count) { index ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            entry(index)
        }
    }
}
