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

private const val UI_MODE_NIGHT = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = UI_MODE_NIGHT)
private annotation class LightDarkPreview

/** D12 §8 sample asset. Nothing here comes from a real tag or a real owner. */
private val rackUpsCells = listOf(
    "Serial" to PlateValue("XYZ12345", mono = true),
    "NFC tag" to PlateValue("41c11b73 · v1", mono = true),
    "Installed" to PlateValue("10 Jun 2024"),
    "Documents" to PlateValue(""),
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
        Text("Apollo Service Binder", style = MaterialTheme.typography.titleMedium)
    }
}

@LightDarkPreview
@Composable
private fun IdentityPlatePreview() {
    PreviewFrame {
        IdentityPlate(
            category = "Battery / power · Server rack",
            model = "CyberPower OR2200LCDRT2U",
            name = "Server Rack",
            cells = rackUpsCells,
            icon = ServiceTagIcons.NfcTag,
            modifier = Modifier.fillMaxWidth(),
        )
        StatusBlock(
            kind = ServiceTagTheme.semanticColors.overdue,
            headline = "Overdue",
            title = "Load test",
            detail = "12 days overdue · originally due 2 Sep 2026",
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
    BadgeSample("Due soon", { it.dueSoon }, ServiceTagIcons.Schedule),
    BadgeSample("Due", { it.due }, ServiceTagIcons.Event),
    BadgeSample("Overdue", { it.overdue }, Icons.Outlined.Warning),
    BadgeSample("Out of season", { it.seasonInactive }, ServiceTagIcons.CalendarMonth),
    BadgeSample("Paused", { it.paused }, ServiceTagIcons.PauseCircle),
    BadgeSample("Low", { it.measurementLow }, ServiceTagIcons.ArrowDownward),
    BadgeSample("In range", { it.measurementInRange }, Icons.Outlined.Check),
    BadgeSample("High", { it.measurementHigh }, ServiceTagIcons.ArrowUpward),
    BadgeSample("No target set", { it.measurementNoTarget }, null),
    BadgeSample("Active", { it.reminderHealthy }, ServiceTagIcons.NotificationsActive),
    BadgeSample("Reminder failed", { it.reminderFailure }, ServiceTagIcons.NotificationsOff),
    BadgeSample("Sync issue", { it.syncProblem }, ServiceTagIcons.CloudOff),
    BadgeSample("Delete", { it.destructiveAction }, ServiceTagIcons.DeleteForever),
)

@LightDarkPreview
@Composable
private fun StatusBadgeRowPreview() {
    PreviewFrame {
        val semantic = ServiceTagTheme.semanticColors
        SectionHeader(title = "Operational states")
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
        SectionHeader(title = "Service record", trailing = { QuietLine("All 12") })
        val entries = listOf(
            Triple("10", "Jun", "2024") to Triple("Battery replaced", "CyberPower RB1290X2", listOf("Receipt", "Photo")),
            Triple("03", "Sep", "2025") to Triple("Load test", "Runtime 46 min", emptyList()),
            Triple("14", "Mar", "2026") to Triple("Inspection", "No visible swelling", emptyList()),
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
                    { StatusBadge(label = "Pass", colors = ServiceTagTheme.semanticColors.measurementInRange, icon = Icons.Outlined.Check) }
                } else {
                    null
                },
                meta = body.third,
            )
        }
        QuietLine("No entries yet · Log maintenance to start")
    }
}

@LightDarkPreview
@Composable
private fun ActionGridPreview() {
    PreviewFrame {
        ActionGrid(
            actions = listOf(
                ActionSpec("Log quarterly inspection, calibration and seal replacement", Icons.Outlined.Build, outlined = true, onClick = {}),
                ActionSpec("Record reading", ServiceTagIcons.Speed, outlined = true, onClick = {}),
                ActionSpec("History", ServiceTagIcons.History, outlined = false, onClick = {}),
                ActionSpec("Documents", ServiceTagIcons.Description, outlined = false, onClick = {}),
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
        SectionHeader(title = "Current readings")
        InstrumentList(count = 5) { index ->
            when (index) {
                0 -> InstrumentRow("pH", "7.2–7.8", "8.1", "", RangeState.HIGH)
                1 -> InstrumentRow("Free chlorine", "1.0–3.0", "0.8", "ppm", RangeState.LOW)
                2 -> InstrumentRow("Alkalinity", "80–120", "110", "ppm", RangeState.IN_RANGE)
                3 -> InstrumentRow("Water temperature", "No target", "102", "°F", RangeState.NO_TARGET)
                else -> InstrumentRow("Calcium hardness", "150–250", null, "ppm", null)
            }
        }
    }
}
