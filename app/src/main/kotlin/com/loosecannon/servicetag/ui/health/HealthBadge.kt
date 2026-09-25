package com.loosecannon.servicetag.ui.health

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
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.ui.components.StateGlyph
import com.loosecannon.servicetag.ui.theme.BadgeShape
import com.loosecannon.servicetag.ui.theme.ServiceTagSemanticColors
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import com.loosecannon.servicetag.ui.theme.StatusColor

/**
 * The glyph of each band (spec §10.6): `signal_cellular_alt` with three, two and one bars, and
 * `signal_cellular_nodata` for NOT TRACKED — the bar count is a shape that survives grayscale.
 */
fun healthGlyph(band: HealthBand?): StateGlyph = when (band) {
    HealthBand.NOMINAL -> StateGlyph.SIGNAL_3_BARS
    HealthBand.WARNING -> StateGlyph.SIGNAL_2_BARS
    HealthBand.CRITICAL -> StateGlyph.SIGNAL_1_BAR
    null -> StateGlyph.SIGNAL_NO_DATA
}

/** The D12 token of each band (O-2): cool blue, amber, the error family, and neutral for NOT TRACKED. */
fun healthColors(band: HealthBand?, colors: ServiceTagSemanticColors): StatusColor = when (band) {
    HealthBand.NOMINAL -> colors.healthNominal
    HealthBand.WARNING -> colors.healthWarning
    HealthBand.CRITICAL -> colors.healthCritical
    null -> colors.healthNotTracked
}

/**
 * A band as one badge: the bar glyph, the word and the score ([healthBadgeLabel]). A null [band] is
 * NOT TRACKED — drawn as those words with the no-data glyph, never as a number (inv. 118). A null
 * [score] with a band draws the word alone.
 */
@Composable
fun HealthBadge(band: HealthBand?, score: Int?, modifier: Modifier = Modifier) {
    val colors = healthColors(band, ServiceTagTheme.semanticColors)
    Surface(color = colors.container, contentColor = colors.foreground, shape = BadgeShape, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Icon(
                imageVector = healthGlyph(band).icon,
                contentDescription = null,
                tint = colors.foreground,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = healthBadgeLabel(band, score),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
            )
        }
    }
}
