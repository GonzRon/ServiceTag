package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.LocalServiceTagSemanticColors
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme

/**
 * One maintenance group: its members now, and what each of its rounds is asking of them
 * (#55's "Dashboard / UX" minimum, spec §2.3, §2.4).
 *
 * The two things this screen is for are the two directions #55 calls its minimum: a member row
 * opens that member's own **Asset**, and the asset screen's groups section comes back the other way.
 * A group is not equipment — it has no tag, no serial and no place in the asset tree (invariants 4,
 * 5) — so the only navigation out of a member is to the real thing.
 *
 * **There is no "Close this round" here**: that action is B14's and belongs to the schedule. Nor is
 * there an inline completion yet — B14's `CompletionFlow` is the canonical one, so until it lands a
 * round's member work is reached by opening the schedule, and this screen writes nothing but
 * `archived_at`.
 *
 * D-26: `description` is the **only** context a group carries. The field #55's sketch proposed
 * is in neither the aggregate nor these screens, so nothing here could draw one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    graph: AppGraph,
    groupId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenAsset: (String) -> Unit,
    onOpenSchedule: (String) -> Unit,
) {
    val model: GroupDetailViewModel = viewModel(key = groupId) { GroupDetailViewModel(graph, groupId) }
    val state by model.state.collectAsStateWithLifecycle()
    val missing by model.missing.collectAsStateWithLifecycle()

    // A restored back stack or a replacing import can name a group that is not there any more.
    // Leaving is the honest answer; an empty screen would pretend it still exists.
    LaunchedEffect(missing) { if (missing) onBack() }

    val current = state
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = current?.name ?: MAINTENANCE_GROUP,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (current != null) {
                        IconButton(onClick = { onEdit(groupId) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit")
                        }
                        TextButton(onClick = { model.setArchived(!current.archived) }) {
                            Text(if (current.archived) "Unarchive" else "Archive")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) {
            QuietLine("Loading…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        // The 16dp gutter is each block's own, because the section headings carry theirs already
        // and a second inset would push the rows in twice.
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            // The RATIFIED noun as an eyebrow over the name: the screen says what kind of thing
            // this is once, and the title says which one.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(
                    text = MAINTENANCE_GROUP,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current.archived) {
                    StatusBadge(
                        label = "Archived",
                        colors = ServiceTagTheme.semanticColors.seasonInactive,
                    )
                }
            }
            if (current.description.isNotBlank()) {
                Text(
                    text = current.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                )
            }

            // The members, under the shipped word for what they are. A group is a set of
            // equipment, and each row leads to the equipment (#55's group -> member direction).
            MaintenanceSectionTitle(MEMBERS_SECTION)
            current.members.forEach { member ->
                MemberRow(member = member, onOpenAsset = { onOpenAsset(member.assetId.value) })
            }

            MaintenanceSectionTitle(SCHEDULES_SECTION)
            current.schedules.forEachIndexed { index, schedule ->
                if (index > 0) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                GroupScheduleBlock(
                    row = schedule,
                    onClick = { onOpenSchedule(schedule.scheduleId.value) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A member: the Asset's name, and the RATIFIED action that opens it. */
@Composable
private fun MemberRow(member: GroupMemberRow, onOpenAsset: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = member.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenAsset, shape = ControlShape) { Text(OPEN_ASSET) }
    }
}

/** The RATIFIED name of a member row's action (master plan §17). */
const val OPEN_ASSET = "Open asset"

/**
 * The members section's heading: the **shipped** word "Assets", already the second tab's label and
 * the word this app uses for the things a group is a set of.
 *
 * §17 ratifies no heading for a group's membership, and this brief drafts nothing — so the section
 * takes a word the app already says rather than a new one. Reported to the controller: if the owner
 * wants "Members" or another wording here, it needs ratifying.
 */
const val MEMBERS_SECTION = "Assets"

/**
 * One schedule of the group: its title and status word, the RATIFIED progress form, and the round's
 * checklist — the required members, each marked when it is done.
 *
 * The check is a glyph and not a word: §17 ratifies no wording for "this one is done", and the
 * ratified progress line above already says how many of them are. A round that obliges nobody gets
 * neither a progress line nor a status treatment, for the reasons `DueItemRow` records.
 *
 * Tapping the block opens the schedule, which is where a member completion is recorded.
 */
@Composable
private fun GroupScheduleBlock(row: GroupScheduleRow, onClick: () -> Unit) {
    val colors = statusColors(row.status, LocalServiceTagSemanticColors.current)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!row.requiredSetEmpty) {
                StatusBadge(
                    label = statusLabel(row.status),
                    colors = colors,
                    icon = statusIcon(row.status),
                )
            }
        }
        row.progress?.let { QuietLine(it) }
        row.checklist.forEach { member ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (member.complete) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = LocalServiceTagSemanticColors.current.maintenanceOkay.foreground,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Spacer(Modifier.size(16.dp))
                }
                QuietLine(member.name)
            }
        }
    }
}
