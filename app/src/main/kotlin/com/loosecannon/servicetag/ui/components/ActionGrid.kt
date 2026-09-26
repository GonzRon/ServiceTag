package com.loosecannon.servicetag.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.ui.theme.ControlShape

/** One utility action of the 2×2 region in D12 §8. Logging verbs are outlined, navigation tonal. */
data class ActionSpec(
    val label: String,
    val icon: ImageVector,
    val outlined: Boolean,
    val onClick: () -> Unit,
)

/**
 * Outlined and tonal controls, icon + label on the 6dp control corner, two to a row while the width
 * allows it, not four colourful tiles (D12 §7–§8, G1 §1.1 "Quick actions"); this screen carries no
 * FAB (G1 correction c). A label is the label: it is never shortened, ellipsised or special-cased
 * (issue #68). The button grows and the grid adapts instead.
 *
 * - **44dp is a floor, not a height.** A one-line label at the default font scale makes the 44dp
 *   button G1 drew; a label that wraps makes its button as tall as its lines need, with no maximum.
 * - **Two columns only while each label keeps six ems.** Two actions share a row when each column can
 *   give its label [LABEL_EMS] ems of `labelLarge` beside the button's own chrome: its horizontal
 *   content padding, icon and icon spacing, read from [ButtonDefaults]. Otherwise every action takes
 *   the full width, one per row, in list order, which is D12 §8's own sketch of this region. Ems,
 *   because the question is whether a few words still fit beside the icon at the size the reader
 *   chose. The em is the label face converted to dp *before* it is multiplied: Android scales type
 *   non-linearly from 1.03, so the 14sp face grows with the font-size setting while a large sp value
 *   such as 84sp barely moves, and a threshold written in sp, or converted after multiplying, would
 *   never let go of two columns. At six ems the content width two columns need is 326 / 364.4 /
 *   383.6 / 422 / 470dp at scales 1.0 / 1.2 / 1.3 / 1.5 / 2.0: a 412dp phone keeps two columns
 *   through 1.2, a 360dp phone at 1.0, and from there the grid is one full-width column.
 * - **A pair is level and never cut.** The layout splits each two-column row itself (the gap taken
 *   out, the odd pixel to the second column, as the weighted row it replaces did), asks both buttons
 *   for their maximum intrinsic height at exactly the width each will be measured at, and measures
 *   both at that width with the taller height as a minimum and no maximum. A weighted `Row` at
 *   intrinsic height rounds its split one way when asked and another when measured, so a label that
 *   fits by a pixel could wrap in the measure pass alone and be cut under a height derived from one
 *   line; here the worst case is a stepped pair. A lone button takes the floor alone.
 * - **The layout box is the visible button.** Material pads a clickable surface out to a 48dp box;
 *   the grid unsets [LocalMinimumInteractiveComponentSize] so a one-line button stays its visible
 *   44dp and the rows keep their 10dp gaps on a 54dp pitch, as before. The 48dp touch target is not
 *   lost: Compose expands a 44dp clickable's touch bounds to the platform minimum on its own.
 *
 * The layout answers no intrinsic query of its own: a parent asking for its intrinsic height would see
 * one-line rows at Material's 40dp button minimum, not the 44dp floor. No caller asks today.
 */
@Composable
fun ActionGrid(actions: List<ActionSpec>, modifier: Modifier = Modifier) {
    val labelFace = MaterialTheme.typography.labelLarge.fontSize
    val measurePolicy = remember(labelFace) { ActionGridMeasurePolicy(labelFace) }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Layout(
            content = { actions.forEach { ActionButton(it) } },
            modifier = modifier,
            measurePolicy = measurePolicy,
        )
    }
}

/**
 * K of #68: the ems of the label face each column must keep for two columns to be drawn (R68-2).
 * The arithmetic, and the widths it gives at each font scale, are in [ActionGrid]'s KDoc.
 */
private const val LABEL_EMS = 6f

/** The height of a one-line action (G1 §1.1), and the least any action is. */
private val ActionFloor = 44.dp

/** Between the columns and between the rows (D12 §8). */
private val ActionGap = 10.dp

private class ActionGridMeasurePolicy(private val labelFace: TextUnit) : MeasurePolicy {

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = ActionGap.roundToPx()
        val floor = ActionFloor.roundToPx()
        val bounded = constraints.hasBoundedWidth
        val width = constraints.maxWidth
        val twoColumns = bounded && width >= twoColumnWidth().toPx()

        val rows: List<List<Placeable>> = if (twoColumns) {
            val first = (width - gap) / 2
            val second = width - gap - first
            measurables.chunked(2).map { row ->
                if (row.size == 2) {
                    val height = maxOf(floor, row[0].maxIntrinsicHeight(first), row[1].maxIntrinsicHeight(second))
                    listOf(
                        row[0].measure(columnConstraints(first, height)),
                        row[1].measure(columnConstraints(second, height)),
                    )
                } else {
                    listOf(row[0].measure(columnConstraints(first, floor)))
                }
            }
        } else {
            // Unbounded width is defensive only (the one caller is fillMaxWidth in a vertically
            // scrolling Column): there the buttons are as wide as their content, not full width.
            val full = if (bounded) columnConstraints(width, floor) else Constraints(minHeight = floor)
            measurables.map { listOf(it.measure(full)) }
        }

        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        val height = rowHeights.sum() + gap * (rows.size - 1).coerceAtLeast(0)
        val layoutWidth = if (bounded) width else rows.maxOfOrNull { row -> row.sumOf { it.width } } ?: 0
        return layout(constraints.constrainWidth(layoutWidth), constraints.constrainHeight(height)) {
            var y = 0
            rows.forEachIndexed { index, row ->
                var x = 0
                row.forEach { placeable ->
                    placeable.placeRelative(x, y)
                    x += placeable.width + gap
                }
                y += rowHeights[index] + gap
            }
        }
    }

    /** Twice a column's chrome and six ems of label, plus the gap: the least width two columns need. */
    private fun MeasureScope.twoColumnWidth(): Dp {
        val padding = ButtonDefaults.ContentPadding
        val chrome = padding.calculateStartPadding(layoutDirection) + padding.calculateEndPadding(layoutDirection) +
            ButtonDefaults.IconSize + ButtonDefaults.IconSpacing
        val label = labelFace.toDp() * LABEL_EMS
        return (chrome + label) * 2 + ActionGap
    }

    /** A column's exact width, at least [minHeight] tall and as much taller as the label needs. */
    private fun columnConstraints(width: Int, minHeight: Int) =
        Constraints(minWidth = width, maxWidth = width, minHeight = minHeight, maxHeight = Constraints.Infinity)
}

@Composable
private fun ActionButton(action: ActionSpec) {
    val content: @Composable () -> Unit = {
        Icon(imageVector = action.icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(text = action.label, style = MaterialTheme.typography.labelLarge)
    }
    if (action.outlined) {
        OutlinedButton(
            onClick = action.onClick,
            shape = ControlShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) { content() }
    } else {
        FilledTonalButton(
            onClick = action.onClick,
            shape = ControlShape,
        ) { content() }
    }
}
