package com.loosecannon.servicetag.ui.maintenance

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.reminders.Severity
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
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
 * #27's Health section, inside the Maintenance destination.
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
 * **What this screen deliberately does not say.** #27 asks it to explain two platform realities
 * even when no finding is active — an OEM holding the app back, and Android 17 not dispatching NFC
 * to an app in the stopped state — and a phone with no findings at all wants a line too. §17
 * ratifies **no** string for any of the three: the one `APP_RESTRICTED` sentence it does ratify is
 * conditional by construction and would be false on an unrestricted phone. So this screen draws
 * nothing for them and drafts nothing (controller ruling, 2026-09-22); the three strings are
 * recorded for the owner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(
    graph: AppGraph,
    onOpenSchedule: (String) -> Unit,
    onLogMeterReading: (String) -> Unit,
    onBack: () -> Unit,
    model: HealthViewModel = viewModel(key = "reminder-health") { HealthViewModel(graph) },
) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Every finding here is a platform fact and nothing observes one, so arriving on the screen is
    // the moment to ask again — which is the third of decision 32's three run points.
    LaunchedEffect(Unit) { model.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                // The RATIFIED section label (§17.1f) is the destination's name: the row that
                // opened it says the same word, and §17 ratifies no second title for it.
                title = { Text(REMINDERS_SECTION) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
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
            state.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                FindingRow(
                    row = row,
                    onRepair = {
                        when (val action = row.action) {
                            HealthAction.Automatic, HealthAction.TurnRemindersOn -> model.repair(row)
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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 0.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    Text(label)
                }
            }
        }
    }
}

/** Three severities, three glyphs, so the distinction survives with colour removed (D12 §5). */
@Composable
private fun severityIcon(severity: Severity): ImageVector = when (severity) {
    Severity.ERROR -> ServiceTagIcons.NotificationsOff
    Severity.WARN -> Icons.Outlined.Warning
    Severity.INFO -> Icons.Outlined.Info
}

/** The tint is the *second* signal, never the only one. */
@Composable
private fun severityTint(severity: Severity): Color {
    val semantic = LocalServiceTagSemanticColors.current
    return when (severity) {
        Severity.ERROR -> semantic.reminderFailure.foreground
        Severity.WARN -> semantic.dueSoon.foreground
        Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** The app's own notification settings — the screen `NOTIFICATIONS_BLOCKED` is about. */
private fun notificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/**
 * The app's own details page: where an OEM's background restriction is turned off, and a screen
 * every Android build has. Deliberately **not** a battery-exemption request (#24).
 */
private fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

/**
 * A settings screen an OEM has removed is a dead button, not a crash: the finding stays on screen
 * and still explains what is wrong, which is more than a stack trace would.
 */
private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing to do and nothing to say: §17 ratifies no message for "this phone has no such
        // screen", and the finding the owner is looking at already explains the problem.
    }
}
