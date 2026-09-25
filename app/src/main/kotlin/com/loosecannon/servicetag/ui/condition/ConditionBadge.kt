package com.loosecannon.servicetag.ui.condition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.StateGlyph
import com.loosecannon.servicetag.ui.health.ConditionView
import com.loosecannon.servicetag.ui.theme.BadgeShape
import com.loosecannon.servicetag.ui.theme.ServiceTagSemanticColors
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import com.loosecannon.servicetag.ui.theme.StatusColor

/**
 * The glyph of each condition (spec §10.6): `task_alt`, `trending_down`, `block`, and
 * `radio_button_unchecked` for nothing recorded. No two share one, and none is a health or a due
 * glyph, so a condition is told apart without colour.
 */
fun conditionGlyph(condition: OperationalCondition?): StateGlyph = when (condition) {
    OperationalCondition.OPERATIONAL -> StateGlyph.TASK_ALT
    OperationalCondition.DEGRADED -> StateGlyph.TRENDING_DOWN
    OperationalCondition.DOWN -> StateGlyph.BLOCK
    null -> StateGlyph.RADIO_BUTTON_UNCHECKED
}

/**
 * The D12 token of each condition (O-2): cool blue, DEGRADED's own amber, the error family, and
 * neutral for nothing recorded. Never read at a call site as a raw colour (D12 §15).
 */
fun conditionColors(condition: OperationalCondition?, colors: ServiceTagSemanticColors): StatusColor =
    when (condition) {
        OperationalCondition.OPERATIONAL -> colors.conditionOperational
        OperationalCondition.DEGRADED -> colors.conditionDegraded
        OperationalCondition.DOWN -> colors.conditionDown
        null -> colors.conditionNotRecorded
    }

/**
 * An asset's condition as one badge: the word (S1–S3), its glyph and, beside it, S22 "since
 * <date>" — or, with nothing recorded, S4 and its own glyph and no date.
 *
 * The word is drawn **as ratified**: the shipped `StatusBadge` upper-cases its label, which would
 * put S4 on screen in a case the owner never ratified, so this badge draws its own. The label stays
 * in the tree for TalkBack.
 */
@Composable
fun ConditionBadge(view: ConditionView?, modifier: Modifier = Modifier) {
    val condition = view?.condition
    val colors = conditionColors(condition, ServiceTagTheme.semanticColors)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        Surface(color = colors.container, contentColor = colors.foreground, shape = BadgeShape) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Icon(
                    imageVector = conditionGlyph(condition).icon,
                    contentDescription = null,
                    tint = colors.foreground,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = conditionWord(condition),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                )
            }
        }
        view?.let { QuietLine(sinceLine(it.since, ::displayDate)) }
    }
}
