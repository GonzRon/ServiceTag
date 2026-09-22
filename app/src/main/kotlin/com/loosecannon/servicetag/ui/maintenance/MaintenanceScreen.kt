package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.LocalServiceTagSemanticColors

/** The Maintenance destination's four RATIFIED section labels, and its empty state (§17.1f). */
const val DUE_WORK_SECTION = "Due work"
const val SCHEDULES_SECTION = "Schedules"
const val GROUPS_SECTION = "Maintenance groups"
const val REMINDERS_SECTION = "Reminders"
const val NO_SCHEDULES_YET =
    "No maintenance schedules yet. Add one from an asset or a maintenance group."

/** D-22's one dismissible line, RATIFIED. A denied permission disables nothing; it explains. */
const val REMINDERS_BLOCKED = "Reminders are off because notifications are blocked."

/**
 * The Maintenance destination (spec §2.6, master plan §11): the third tab, and the shell the rest
 * of 1.2's maintenance surfaces land inside.
 *
 * Four sections, each of which routes onward — **"Due work"** and **"Schedules"** to a schedule,
 * **"Maintenance groups"** to a group, **"Reminders"** to reminder health — plus F4's three
 * persistent quick actions above them. Due work is what needs attention, in the shared projection's
 * attention order; Schedules is every listed schedule, **the paused ones included**, because a
 * paused schedule is what an owner comes here to find and the dashboard is the surface that
 * deliberately omits it.
 *
 * This screen decides nothing about what is due, what order it comes in, or what a status means:
 * all of that is `DueReadModel`'s, so this destination and the dashboard cannot drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceScreen(
    graph: AppGraph,
    onOpenSchedule: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
    onReminderHealth: () -> Unit,
    onScanTag: () -> Unit,
    onAddAsset: () -> Unit,
    onLogMaintenance: () -> Unit,
) {
    val model: MaintenanceViewModel = viewModel(key = "maintenance") { MaintenanceViewModel(graph) }
    val state by model.state.collectAsStateWithLifecycle()

    // Notification permission is a platform fact and nothing observes one: coming back here is the
    // moment to ask again whether D-22's line is still true.
    LaunchedEffect(Unit) { model.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maintenance") },
                actions = {
                    if (state.worstSeverity.showsBadge()) {
                        StatusBadge(
                            label = REMINDER_FAILED,
                            colors = LocalServiceTagSemanticColors.current.reminderFailure,
                            icon = ServiceTagIcons.NotificationsOff,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clickable(onClick = onReminderHealth),
                        )
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
            if (state.notificationsBlocked) {
                ReminderNotice(onDismiss = model::dismissReminderNotice)
            }
            QuickActions(
                onScanTag = onScanTag,
                onAddAsset = onAddAsset,
                // F4's one rule that carries risk: this opens B14's canonical completion flow and
                // writes nothing itself (#50).
                onLogMaintenance = onLogMaintenance,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            if (state.isEmpty) {
                // "No schedules" and "not read yet" look identical in an empty list, and only one
                // of them is worth telling the owner — which is what `loaded` is for. The line
                // stands alone: a section header over it would be a heading for nothing.
                QuietLine(NO_SCHEDULES_YET, modifier = Modifier.padding(16.dp))
            } else {
                // A section with no rows is omitted rather than drawn as a bare heading — the same
                // rule D12 §10 sets for the dashboard, and the ratified set has no empty-state line
                // for either of these two.
                if (state.dueWork.isNotEmpty()) {
                    SchedulesSection(
                        title = DUE_WORK_SECTION,
                        items = state.dueWork,
                        onOpen = { onOpenSchedule(it.scheduleId.value) },
                        onRepair = { onOpenSchedule(it.scheduleId.value) },
                    )
                }
                SchedulesSection(
                    title = SCHEDULES_SECTION,
                    items = state.schedules,
                    onOpen = { onOpenSchedule(it.scheduleId.value) },
                )
            }

            // B15's group list, in this section. B08's rule stands and is deliberately not
            // changed here: a section with no rows is **omitted** rather than drawn as a bare
            // heading, and §17.1f has no empty-state line for this one.
            //
            // The consequence is recorded rather than worked around: the list's own create
            // affordance is inside the section, so a phone with **no** groups has no in-app way to
            // make its first one. Drawing the heading unconditionally would fix that in one line,
            // and it is a finding for the controller — not a rule this brief may change, since no
            // numbered plan decision reaches B08's.
            if (state.groups.isNotEmpty()) {
                MaintenanceSectionTitle(GROUPS_SECTION)
                GroupList(
                    groups = state.groups,
                    onOpenGroup = onOpenGroup,
                    onNewGroup = onNewGroup,
                )
            }

            // The fourth section is one row and needs no heading of its own: the row's name *is*
            // the ratified section label, and a heading above it would say the same word twice.
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            NavigatingRow(title = REMINDERS_SECTION, onClick = onReminderHealth)
        }
    }
}

/**
 * D-22's line: one sentence, dismissible, and it disables nothing. A denied permission is a fact
 * about the phone, not a reason to take a feature away, so the schedules and their reminders stay
 * exactly as they were and this explains why nothing arrives.
 */
@Composable
private fun ReminderNotice(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp),
    ) {
        QuietLine(REMINDERS_BLOCKED, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Clear, contentDescription = "Dismiss")
        }
    }
}

/**
 * A section's own row: a name and a chevron, 56dp minimum as every row is.
 *
 * There is deliberately no secondary line. A group's member count and a reminder-health summary
 * would each be a new user-visible string, and the ratified set has neither — so the row says the
 * one thing it is allowed to say and the screen it opens says the rest.
 */
@Composable
private fun NavigatingRow(title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
