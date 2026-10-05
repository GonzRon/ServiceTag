package com.loosecannon.servicetag.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.ui.theme.ServiceTagSemanticColors
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import com.loosecannon.servicetag.ui.theme.StatusColor

// #102: everything in this file is design-time sample text for the IDE's preview pane. None of it is drawn
// in the app, so none of it is a string resource; each line the literal guard would flag says so.

private const val UI_MODE_NIGHT =Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL

@Preview(name = "Light", showBackground = true) // l10n-ok: preview tool name
@Preview(name = "Dark", showBackground = true, uiMode = UI_MODE_NIGHT) // l10n-ok: preview tool name
private annotation class LightDarkPreview

/** D12 §8 sample asset. Nothing here comes from a real tag or a real owner. */
private val rackUpsCells = listOf(
    "Serial" to PlateValue("XYZ12345", mono = true), // l10n-ok: preview sample
    "NFC tag" to PlateValue("41c11b73 · v1", mono = true),
    "Installed" to PlateValue("10 Jun 2024"), // l10n-ok: preview sample
    "Documents" to PlateValue(""), // l10n-ok: preview sample
)

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    ServiceTagTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@LightDarkPreview
@Composable
private fun ServiceTagThemePreview() {
    PreviewFrame {
        Text("Apollo Service Binder", style = MaterialTheme.typography.titleMedium) // l10n-ok: preview sample
    }
}

@LightDarkPreview
@Composable
private fun IdentityPlatePreview() {
    PreviewFrame {
        IdentityPlate(
            category = "Battery / power · Server rack", // l10n-ok: preview sample
            model = "CyberPower OR2200LCDRT2U", // l10n-ok: preview sample
            name = "Server Rack", // l10n-ok: preview sample
            cells = rackUpsCells,
            icon = ServiceTagIcons.NfcTag,
            modifier = Modifier.fillMaxWidth(),
        )
        StatusBlock(
            kind = ServiceTagTheme.semanticColors.overdue,
            headline = "Overdue", // l10n-ok: preview sample
            title = "Load test", // l10n-ok: preview sample
            detail = "12 days overdue · originally due 2 Sep 2026", // l10n-ok: preview sample
            icon = Icons.Outlined.Warning,
            leftRule = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private data class BadgeSample(val label: String, val pick: (ServiceTagSemanticColors) -> StatusColor, val icon: ImageVector?)

@Composable
private fun badgeSamples(): List<BadgeSample> = listOf(
    BadgeSample("OK", { it.maintenanceOkay }, Icons.Outlined.CheckCircle),
    BadgeSample("Due soon", { it.dueSoon }, ServiceTagIcons.Schedule), // l10n-ok: preview sample
    BadgeSample("Due", { it.due }, ServiceTagIcons.Event), // l10n-ok: preview sample
    BadgeSample("Overdue", { it.overdue }, Icons.Outlined.Warning), // l10n-ok: preview sample
    BadgeSample("Out of season", { it.seasonInactive }, ServiceTagIcons.CalendarMonth), // l10n-ok: preview sample
    BadgeSample("Paused", { it.paused }, ServiceTagIcons.PauseCircle), // l10n-ok: preview sample
    BadgeSample("Low", { it.measurementLow }, ServiceTagIcons.ArrowDownward), // l10n-ok: preview sample
    BadgeSample("In range", { it.measurementInRange }, Icons.Outlined.Check), // l10n-ok: preview sample
    BadgeSample("High", { it.measurementHigh }, ServiceTagIcons.ArrowUpward), // l10n-ok: preview sample
    BadgeSample("No target set", { it.measurementNoTarget }, null), // l10n-ok: preview sample
    BadgeSample("Active", { it.reminderHealthy }, ServiceTagIcons.NotificationsActive), // l10n-ok: preview sample
    BadgeSample("Reminder failed", { it.reminderFailure }, ServiceTagIcons.NotificationsOff), // l10n-ok: preview sample
    BadgeSample("Sync issue", { it.syncProblem }, ServiceTagIcons.CloudOff), // l10n-ok: preview sample
    BadgeSample("Delete", { it.destructiveAction }, ServiceTagIcons.DeleteForever), // l10n-ok: preview sample
)

@LightDarkPreview
@Composable
private fun StatusBadgeRowPreview() {
    PreviewFrame {
        val semantic = ServiceTagTheme.semanticColors
        SectionHeader(title = "Operational states") // l10n-ok: preview sample
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            badgeSamples().forEach { sample ->
                StatusBadge(label = sample.label, colors = sample.pick(semantic), icon = sample.icon)
            }
        }
    }
}

@LightDarkPreview
@Composable
private fun LedgerPreview() {
    PreviewFrame {
        SectionHeader(title = "Service record", trailing = { QuietLine("All 12") }) // l10n-ok: preview sample
        val entries = listOf(
            Triple("10", "Jun", "2024") to Triple("Battery replaced", "CyberPower RB1290X2", listOf("Receipt", "Photo")), // l10n-ok: preview sample
            Triple("03", "Sep", "2025") to Triple("Load test", "Runtime 46 min", emptyList()), // l10n-ok: preview sample
            Triple("14", "Mar", "2026") to Triple("Inspection", "No visible swelling", emptyList()), // l10n-ok: preview sample
        )
        LedgerList(count = entries.size) { index ->
            val (date, body) = entries[index]
            LedgerEntry(
                day = date.first,
                month = date.second,
                year = date.third,
                title = body.first,
                detail = body.second,
                badge = if (index == 1) {
                    { StatusBadge(label = "Pass", colors = ServiceTagTheme.semanticColors.measurementInRange, icon = Icons.Outlined.Check) } // l10n-ok: preview sample
                } else {
                    null
                },
                meta = body.third,
            )
        }
        QuietLine("No entries yet · Log maintenance to start") // l10n-ok: preview sample
    }
}

@LightDarkPreview
@Composable
private fun ActionGridPreview() {
    PreviewFrame {
        ActionGrid(
            actions = listOf(
                ActionSpec("Log quarterly inspection, calibration and seal replacement", Icons.Outlined.Build, outlined = true, onClick = {}), // l10n-ok: preview sample
                ActionSpec("Record reading", ServiceTagIcons.Speed, outlined = true, onClick = {}), // l10n-ok: preview sample
                ActionSpec("History", ServiceTagIcons.History, outlined = false, onClick = {}), // l10n-ok: preview sample
                ActionSpec("Documents", ServiceTagIcons.Description, outlined = false, onClick = {}), // l10n-ok: preview sample
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The instrument panel of D12 §9, one row per state plus the row a definition shows before
 * anything has been logged against it. The values are illustrative, not anyone's water.
 */
@LightDarkPreview
@Composable
private fun InstrumentRowPreview() {
    PreviewFrame {
        SectionHeader(title = "Current readings") // l10n-ok: preview sample
        InstrumentList(count = 5) { index ->
            when (index) {
                0 -> InstrumentRow("pH", "7.2–7.8", "8.1", "", RangeState.HIGH)
                1 -> InstrumentRow("Free chlorine", "1.0–3.0", "0.8", "ppm", RangeState.LOW) // l10n-ok: preview sample
                2 -> InstrumentRow("Alkalinity", "80–120", "110", "ppm", RangeState.IN_RANGE) // l10n-ok: preview sample
                3 -> InstrumentRow("Water temperature", "No target", "102", "°F", RangeState.NO_TARGET) // l10n-ok: preview sample
                else -> InstrumentRow("Calcium hardness", "150–250", null, "ppm", null) // l10n-ok: preview sample
            }
        }
    }
}
