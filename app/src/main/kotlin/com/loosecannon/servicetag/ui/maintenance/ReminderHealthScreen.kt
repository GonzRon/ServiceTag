package com.loosecannon.servicetag.ui.maintenance

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.reminders.ReminderHealthSeverity
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.appDetails
import com.loosecannon.servicetag.ui.components.open
import com.loosecannon.servicetag.ui.theme.LocalServiceTagSemanticColors

/**
 * The severity icon's test tag, per finding: `health-icon-<code>`.
 *
 * It exists because D12 §5's acceptance is that the hierarchy survives grayscale, and a screenshot
 * cannot assert that — so the connected suite asserts the **icon** is drawn beside each finding's
 * wording, which is the half a colour-blind or grayscale reading depends on.
 */
fun healthIconTag(code: String): String = "health-icon-$code"

/**
 * #27's Reminder health page — under Settings › Utilities since 1.7.1 (#103), and still the page the
 * Dashboard's and the Maintenance tab's badge opens.
 *
 * Every finding is drawn with **an icon, explicit wording and a position** — worst first, from the
 * check's own ordering — and never by colour alone (D12 §5): the three severities have three
 * different glyphs, and the sentence beside each one says what is wrong in the owner's terms
 * whatever the tint renders as.
 *
 * Both of the screen's system-settings repairs go to a screen and **never request anything**. #24
 * is explicit that the app must not nag for a battery exemption it does not need, so
 * `APP_RESTRICTED` opens the app's own details page — where the background restriction lives — and
 * not `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which is the request this brief refuses to
 * make.
 *
 * **The healthy state (#103, 1.7.1).** A run that found nothing used to leave the screen empty, which
 * read as broken. It now draws P171-4, the run's own instant as P171-5 (owner ruling Q4: never the
 * time the screen opened) and, under P171-6, the ratified line of every check that passed — only
 * when the run has answered and found nothing; with any finding the rows are drawn as before and
 * none of the healthy lines is. The two platform realities #27 asked to be explained even on a
 * healthy phone — an OEM holding the app back, and Android 17 not dispatching NFC to a stopped app —
 * still have no ratified sentence and are still not drawn (controller ruling, 2026-09-22).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderHealthScreen(
    graph: AppGraph,
    onOpenSchedule: (String) -> Unit,
    onLogMeterReading: (String) -> Unit,
    onBack: () -> Unit,
    model: ReminderHealthViewModel = viewModel(key = "reminder-health") { ReminderHealthViewModel(graph) },
) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Decision 32's third run point. `ON_START` rather than `LaunchedEffect(Unit)`, and that is the
    // difference between a working repair and a stale screen: two of the eight repairs leave for
    // the system settings activity, which does not take this destination out of composition, so a
    // once-per-entry effect would still be showing "notifications are turned off" after the owner
    // had just turned them on.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                // P171-3: the page is named by the Utilities row that opens it (#103).
                title = { Text(REMINDER_HEALTH_TITLE) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.maintenance_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.healthy) {
                HealthyState(checkedAt = state.checkedAt, passed = state.passed)
            }
            state.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                FindingRow(
                    row = row,
                    onRepair = {
                        when (val action = row.action) {
                            HealthAction.Automatic,
                            HealthAction.TurnRemindersOn,
                            HealthAction.RestoreReminderDelivery,
                            -> model.repair(row)
                            HealthAction.NotificationSettings -> context.open(notificationSettings(context))
                            HealthAction.BatterySettings -> context.open(appDetails(context))
                            is HealthAction.OpenSchedule -> onOpenSchedule(action.scheduleId)
                            is HealthAction.LogMeterReading -> onLogMeterReading(action.scheduleId)
                            null -> Unit
                        }
                    },
                )
            }
        }
    }
}

/**
 * The ratified healthy state (#103): P171-4, then P171-5 when a run's instant is known (it always is
 * once `loaded`, but the state type allows otherwise and this draws nothing rather than a guess), then
 * P171-6 over one quiet line per check that passed, in the enum's order. Every line is a plain
 * statement; no icon, because there is no severity to carry.
 */
@Composable
private fun HealthyState(checkedAt: Long?, passed: List<HealthCheck>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = NO_PROBLEMS_FOUND,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (checkedAt != null) {
            QuietLine(
                lastCheckedLine(checkedAt, ZoneId.systemDefault()),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        MaintenanceSectionTitle(CHECKS_THAT_PASSED)
        passed.forEach { check ->
            QuietLine(check.passedLine, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

/**
 * One finding: its glyph, its ratified sentence, and its ratified button when there is one.
 *
 * The icon carries no `contentDescription`. The sentence beside it says the same thing in words —
 * that is the point of the ratified wording — and a description would make a screen reader announce
 * the severity twice before reading it.
 */
@Composable
private fun FindingRow(row: HealthRow, onRepair: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = severityIcon(row.severity),
            contentDescription = null,
            tint = severityTint(row.severity),
            modifier = Modifier
                .size(20.dp)
                .testTag(healthIconTag(row.code)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val label = row.label
            if (label != null && row.action != null) {
                TextButton(
                    onClick = onRepair,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(label)
                }
            }
        }
    }
}

/** Three severities, three glyphs, so the distinction survives with colour removed (D12 §5). */
@Composable
private fun severityIcon(severity: ReminderHealthSeverity): ImageVector = when (severity) {
    ReminderHealthSeverity.ERROR -> ServiceTagIcons.NotificationsOff
    ReminderHealthSeverity.WARN -> Icons.Outlined.Warning
    ReminderHealthSeverity.INFO -> Icons.Outlined.Info
}

/** The tint is the *second* signal, never the only one. */
@Composable
private fun severityTint(severity: ReminderHealthSeverity): Color {
    val semantic = LocalServiceTagSemanticColors.current
    return when (severity) {
        ReminderHealthSeverity.ERROR -> semantic.reminderFailure.foreground
        ReminderHealthSeverity.WARN -> semantic.dueSoon.foreground
        ReminderHealthSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** The app's own notification settings — the screen `NOTIFICATIONS_BLOCKED` is about. */
private fun notificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
