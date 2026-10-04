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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.installed.INSTALLED_COMPONENTS_SECTION
import com.loosecannon.servicetag.ui.supplies.SUPPLIES_SECTION
import com.loosecannon.servicetag.ui.theme.LocalServiceTagSemanticColors

/** The Maintenance destination's RATIFIED section labels, and its empty state (§17.1f). */
val DUE_WORK_SECTION: String get() = localized(R.string.maintenance_section_due_work)
val SCHEDULES_SECTION: String get() = localized(R.string.maintenance_section_schedules)
val GROUPS_SECTION: String get() = localized(R.string.maintenance_section_groups)

/**
 * #103 (1.7.1; P171-1, RATIFIED 2026-10-04): the heading over the three peer rows — Maintenance groups,
 * Supplies, Installed components — that the owner's layout ruling puts after Schedules. Named for all
 * three so the section cannot be read as more maintenance work.
 */
val GROUPS_SUPPLIES_COMPONENTS_SECTION: String
    get() = localized(R.string.maintenance_section_groups_supplies_components)
val NO_SCHEDULES_YET: String
    get() = localized(R.string.maintenance_no_schedules_yet)

/** D-22's one dismissible line, RATIFIED. A denied permission disables nothing; it explains. */
val REMINDERS_BLOCKED: String get() = localized(R.string.maintenance_reminders_blocked)

/**
 * The Maintenance destination (spec §2.6, master plan §11): the third tab, and the shell the rest
 * of 1.2's maintenance surfaces land inside.
 *
 * Since 1.7.1 (#103, the owner's layout ruling): **"Due work"** and **"Schedules"**, each routing to
 * a schedule, then one grouped section (P171-1) of three peer rows — **"Maintenance groups"** to the
 * pushed group list (`MaintenanceGroupsScreen`, which keeps the create row), #15's **"Supplies"** to
 * the SupplyItem catalog (C29), and **"Installed components"** to the cross-asset list of what is
 * fitted (`InstalledComponentsListScreen`) — plus F4's three persistent quick actions above them.
 * **Reminder health is no longer a row here**: it lives under Settings › Utilities, and this screen
 * reaches it only through the top bar's badge, as the Dashboard does. Due work is what needs
 * attention, in the shared projection's attention order; Schedules is every listed schedule, **the
 * paused ones included**, because a paused schedule is what an owner comes here to find and the
 * dashboard is the surface that deliberately omits it.
 *
 * This screen decides nothing about what is due, what order it comes in, or what a status means:
 * all of that is `DueReadModel`'s, so this destination and the dashboard cannot drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceScreen(
    graph: AppGraph,
    onOpenSchedule: (String) -> Unit,
    /** #103: the grouped section's first row, opening the pushed group list. */
    onOpenGroups: () -> Unit,
    /** #15 (C29): the grouped section's second row, opening the Supplies list. */
    onOpenSupplies: () -> Unit,
    /** #103: the grouped section's third row, opening the cross-asset Installed components list. */
    onOpenInstalledComponents: () -> Unit,
    /** The top bar's badge only (#103): the page itself is reached from Settings › Utilities. */
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
                title = { Text(stringResource(R.string.maintenance_title)) },
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

            // #103 (1.7.1): the owner's layout — one ratified heading over three peer rows, each a
            // navigating row with no second line (the rule `NavigatingRow` states). The inline group
            // list B15 drew here moved to its own pushed screen with its create row, so C5's
            // reachability ruling of 2026-09-22 still holds one tap away; Supplies is no longer below
            // the fold behind however many groups the owner has; and Installed components, which had
            // no entry in the tab at all, is the third row. The Reminders row left the tab.
            MaintenanceSectionTitle(GROUPS_SUPPLIES_COMPONENTS_SECTION)
            NavigatingRow(title = GROUPS_SECTION, onClick = onOpenGroups)
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            NavigatingRow(title = SUPPLIES_SECTION, onClick = onOpenSupplies)
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            NavigatingRow(title = INSTALLED_COMPONENTS_SECTION, onClick = onOpenInstalledComponents)
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
            Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.maintenance_dismiss))
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
