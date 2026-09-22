package com.loosecannon.servicetag.ui.maintenance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.model.AssetId
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
 * What it draws: the group's name and description, the **Archived** badge when it is, its current
 * members each with the ratified "Open asset", and each of its schedules with the round's checklist
 * — the ratified progress line, a tick per member and the round's two ratified actions.
 *
 * What it writes: **nothing of its own.** The three completion actions are `CompletionFlow` calls,
 * which is the one completion mechanism in 1.2, and the only column this screen sets directly is
 * `archived_at`, through `ArchiveGroup`.
 *
 * **There is no "Close this round" here**: that action is B14's and belongs to the schedule, which
 * the checklist's title row opens along with the round's postponement and its snooze.
 *
 * D-26: `description` is the **only** context a group carries. The field #55's sketch proposed
 * is in neither the aggregate nor these screens, so nothing here could draw one.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupDetailScreen(
    graph: AppGraph,
    groupId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOpenAsset: (String) -> Unit,
    onOpenSchedule: (String) -> Unit,
    /** "Add a schedule for this group": the create entry, offered only while it has a member. */
    onAddSchedule: (String) -> Unit,
) {
    val model: GroupDetailViewModel = viewModel(key = groupId) { GroupDetailViewModel(graph, groupId) }
    val state by model.state.collectAsStateWithLifecycle()
    val missing by model.missing.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    // Which members are ticked, as "<scheduleId>\u001f<assetId>" keys. It lives here because it is
    // a selection and not a fact about the round — nothing is written until one of the two ratified
    // actions is tapped — and it is *saveable* so a rotation does not silently drop the ticks the
    // owner just made. A flat list of strings is what the default saver can carry.
    var selected by rememberSaveable(groupId) { mutableStateOf(emptyList<String>()) }

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
        // The one completion affordance, wherever a flow is driven: "When was this done?" is asked
        // the same way here as on the schedule, the scan sheet and the quick action.
        CompletionFlowHost(model.completion)
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

            // The create entry, in the same shape the asset screen carries it: a glyph labelled
            // with the ratified section word, and no new string. It is drawn **only while the group
            // has an open member**, which is how invariant 74 is made unreachable through the UI —
            // `SaveSchedule` refuses a group target with nobody in it, and a refusal the owner
            // cannot provoke needs no sentence.
            MaintenanceSectionTitle(
                title = SCHEDULES_SECTION,
                trailing = if (current.members.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = { onAddSchedule(groupId) }) {
                            Icon(Icons.Outlined.Add, contentDescription = SCHEDULES_SECTION)
                        }
                    }
                },
            )
            if (current.schedules.isEmpty()) {
                // B08's RATIFIED empty-state line (master plan §17.1f), which fits this surface
                // exactly: it names the group as one of the two places a schedule is added from,
                // and the glyph above is that place.
                QuietLine(
                    NO_SCHEDULES_YET,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            current.schedules.forEachIndexed { index, schedule ->
                if (index > 0) {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
                val key = schedule.scheduleId.value
                GroupScheduleBlock(
                    row = schedule,
                    selected = selectionFor(selected, key),
                    busy = busy,
                    onClick = { onOpenSchedule(key) },
                    onSelect = { assetId, on ->
                        val tick = tickOf(key, assetId)
                        selected = if (on) selected + tick else selected - tick
                    },
                    onCompleteAll = {
                        model.completeAll(schedule.scheduleId)
                        selected = selected.filterNot { it.startsWith("$key\u001f") }
                    },
                    onCompleteSelected = {
                        val ticked = selectionFor(selected, key)
                        model.completeSelected(
                            schedule.scheduleId,
                            schedule.checklist
                                .filter { !it.complete && it.assetId.value in ticked }
                                .map { it.assetId },
                        )
                        selected = selected.filterNot { it.startsWith("$key\u001f") }
                    },
                    // Nit 9: the tick goes with the member that was just completed, so the round
                    // action is never enabled on a selection that would filter away to nothing.
                    onCompleteMember = { assetId ->
                        model.completeMember(schedule.scheduleId, assetId)
                        selected = selected - tickOf(key, assetId.value)
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The checklist selection, flattened to one saveable list of `"<scheduleId>\u001f<assetId>"` keys.
 *
 * A `Map<String, Set<String>>` is not something the default `rememberSaveable` saver can carry, and
 * the ticks are worth keeping across a rotation; the unit separator cannot occur in a UUID, so the
 * two halves are unambiguous.
 */
private fun tickOf(scheduleId: String, assetId: String): String = "$scheduleId\u001f$assetId"

private fun selectionFor(ticks: List<String>, scheduleId: String): Set<String> {
    val prefix = "$scheduleId\u001f"
    return ticks.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
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
 * checklist — the required members, each with whether it is done and the two ways to record it.
 *
 * **Only the title row opens the schedule.** The checklist below it is interactive, and a block that
 * was clickable as a whole would swallow the checkboxes' own taps; the schedule is where the round's
 * other operations — its postponement, its snooze and its close — live, and "Close this round" is
 * B14's action and is deliberately not offered here.
 *
 * The two ratified round actions appear only while the round can still take one: a round that
 * obliges nobody is never offered for completion (invariant 74), and a finished one has nothing
 * outstanding to complete.
 *
 * A round that obliges nobody also gets no progress line and no status treatment, for the reasons
 * `DueItemRow` records.
 */
@Composable
private fun GroupScheduleBlock(
    row: GroupScheduleRow,
    selected: Set<String>,
    busy: Boolean,
    onClick: () -> Unit,
    onSelect: (String, Boolean) -> Unit,
    onCompleteAll: () -> Unit,
    onCompleteSelected: () -> Unit,
    onCompleteMember: (AssetId) -> Unit,
) {
    val colors = statusColors(row.status, LocalServiceTagSemanticColors.current)
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp),
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
            MemberChecklistRow(
                member = member,
                checked = member.assetId.value in selected,
                busy = busy,
                onCheck = { onSelect(member.assetId.value, it) },
                onComplete = { onCompleteMember(member.assetId) },
            )
        }
        if (row.canComplete) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The two RATIFIED labels. "Complete all" derives its own member list inside the
                // use case, so a surface cannot complete somebody the round does not oblige.
                Button(onClick = onCompleteAll, enabled = !busy, shape = ControlShape) {
                    Text(COMPLETE_ALL)
                }
                OutlinedButton(
                    onClick = onCompleteSelected,
                    enabled = !busy && selected.isNotEmpty(),
                    shape = ControlShape,
                ) { Text(COMPLETE_SELECTED) }
            }
        }
    }
}

/** One member of the round: its name, whether it is done, and the two ways to record it. */
@Composable
private fun MemberChecklistRow(
    member: GroupMemberRow,
    checked: Boolean,
    busy: Boolean,
    onCheck: (Boolean) -> Unit,
    onComplete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Checkbox(
            checked = member.complete || checked,
            onCheckedChange = onCheck,
            enabled = !member.complete && !busy,
        )
        QuietLine(member.name, modifier = Modifier.weight(1f))
        if (!member.complete) {
            // The RATIFIED label of the canonical flow's entry point, which is what this is: one
            // member, through `CompletionFlow`, writing nothing of its own.
            TextButton(onClick = onComplete, enabled = !busy) { Text(LOG_MAINTENANCE) }
        }
    }
}
