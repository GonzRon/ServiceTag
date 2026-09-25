package com.loosecannon.servicetag.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.condition.ConditionBadge
import com.loosecannon.servicetag.ui.condition.conditionColors
import com.loosecannon.servicetag.ui.condition.conditionGlyph
import com.loosecannon.servicetag.ui.condition.conditionWord
import com.loosecannon.servicetag.ui.condition.reasonLine
import com.loosecannon.servicetag.ui.health.ConditionView
import com.loosecannon.servicetag.ui.health.HealthBadge
import com.loosecannon.servicetag.ui.health.SubjectBandFact
import com.loosecannon.servicetag.ui.health.dashboardHealthRow
import com.loosecannon.servicetag.ui.health.healthColors
import com.loosecannon.servicetag.ui.health.healthGlyph
import com.loosecannon.servicetag.ui.maintenance.AttentionItem
import com.loosecannon.servicetag.ui.maintenance.AttentionKind
import com.loosecannon.servicetag.ui.maintenance.AttentionSection
import com.loosecannon.servicetag.ui.maintenance.DueItemRow
import com.loosecannon.servicetag.ui.maintenance.HealthSummary
import com.loosecannon.servicetag.ui.maintenance.MaintenanceSectionTitle
import com.loosecannon.servicetag.ui.maintenance.partOfLine
import com.loosecannon.servicetag.ui.maintenance.promotedSubtitle
import com.loosecannon.servicetag.ui.maintenance.REMINDER_FAILED
import com.loosecannon.servicetag.ui.maintenance.sectionLabel
import com.loosecannon.servicetag.ui.maintenance.showsBadge
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.Eyebrow
import com.loosecannon.servicetag.ui.theme.LocalServiceTagSemanticColors
import com.loosecannon.servicetag.ui.theme.PlateShape
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.LocalDate

/**
 * The landing screen: what needs attention, then the ways out of it (D12 §4).
 *
 * The section order is fixed — ATTENTION · UPCOMING · CURRENT · Deferred · OUT OF SEASON (D12 §10
 * `:706-707`; spec §4.5) — and a section with no rows is omitted. Phase 1C only ever drew CURRENT
 * because there were no schedules; 1.2 fills the other three from the shared due projection, and
 * CURRENT now holds the `OK` schedules first and then the systems nothing is scheduled on yet. 1.4
 * adds the quiet Deferred section and the asset-level rows — DOWN and DEGRADED units, independent
 * health — beside the schedule rows (spec §10.2). Scan has no FAB of its own
 * to replace (G1 §1.2 "Navigation") — [onScan] pushes the scan screen from the empty-state action,
 * same as Settings does with its own Read / inspect tag row.
 *
 * Nothing on this screen decides what belongs in a list: the sections, their order, the promotion
 * of a component's due work, F2's two filters and the condition chips are all the view model's, so
 * the dashboard and the Maintenance destination cannot disagree about any of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    graph: AppGraph,
    onOpenAsset: (String) -> Unit,
    onNewAsset: () -> Unit,
    onBackup: () -> Unit,
    onSettings: () -> Unit,
    onScan: () -> Unit,
    onOpenSchedule: (String) -> Unit = {},
    onReminderHealth: () -> Unit = {},
    health: HealthSummary = graph.healthSummary,
) {
    val model: DashboardViewModel = viewModel(key = "dashboard") { DashboardViewModel(graph, health) }
    val state by model.state.collectAsStateWithLifecycle()

    // An export that happened on the backup screen is a preference, and nothing observes those:
    // coming back here is the moment to ask again whether the nudge is still true.
    LaunchedEffect(Unit) { model.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // #27, D3 §7.3: the badge appears only once the worst finding is at least WARN.
                    // An INFO-only set leaves it off, because a badge that never clears is a badge
                    // that has stopped saying anything.
                    if (state.worstSeverity.showsBadge()) {
                        StatusBadge(
                            label = REMINDER_FAILED,
                            colors = LocalServiceTagSemanticColors.current.reminderFailure,
                            icon = ServiceTagIcons.NotificationsOff,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clickable(onClick = onReminderHealth),
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        // The nudge stays put; only the rows scroll.
        Column(modifier = Modifier.padding(padding)) {
            if (state.needsBackup) {
                BackupNudge(onExport = onBackup, modifier = Modifier.padding(16.dp))
            }
            if (!state.anyInService) {
                FirstRun(onNewAsset = onNewAsset, onScan = onScan)
            } else {
                DashboardFilterRow(
                    filters = state.filters,
                    onCategory = model::onCategoryChange,
                    onStatus = model::onStatusChange,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ConditionChipRow(
                    selected = state.filters.conditions,
                    onToggle = model::onConditionToggle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                // Said once, and only while there is something it explains: a list that is short
                // because the parts are on their systems should say where they went. The second
                // sentence pointed at the box this brief moved off this screen (B07 fix round 2,
                // controller ruling S1) and is dropped rather than reworded — a deletion is not a
                // new string, and the Dashboard has nowhere left to send that "Search".
                if (state.hiddenComponents > 0) {
                    QuietLine(
                        text = "Components are listed on the asset they belong to.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (state.sections.isNotEmpty() || state.assets.isNotEmpty()) {
                    AttentionList(
                        state = state,
                        onOpenAsset = onOpenAsset,
                        onOpenSchedule = onOpenSchedule,
                    )
                } else if (state.filters.isActive) {
                    // Only ever an answer to something asked for (F1). An empty list under an active
                    // filter is reachable, and there is nothing else on screen to explain it away.
                    QuietLine(text = "Nothing matches that.", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

/**
 * The five sections in their **fixed** order — ATTENTION · UPCOMING · CURRENT · Deferred · OUT OF
 * SEASON (D12 §10 `:706-707`; spec §4.5 for Deferred) — with the empty ones omitted, and CURRENT's
 * asset rows after its schedule rows. One `LazyColumn` for the lot: a section header is a row of
 * the same list, so the whole thing scrolls as one surface rather than five nested scrollers.
 *
 * The loop is over `AttentionSection.entries` and not over `state.sections`, so a header can only
 * ever appear at its own ordinal position. Iterating the state's list and appending CURRENT
 * afterwards — which is what this did before fix round 1 — drew CURRENT *after* OUT OF SEASON
 * whenever there was no `OK` schedule row but there were assets with nothing scheduled, which is
 * the one thing D12 §10 calls fixed.
 *
 * Within a section the rows are drawn in the view model's order (spec §10.2's groups, each in its
 * projection's rank) and never re-sorted here. The heading is drawn in its ratified case: the
 * shipped four are ratified in upper case, and Deferred (S93) in sentence case, which
 * `SectionHeader`'s upper-casing would paraphrase.
 */
@Composable
private fun AttentionList(
    state: DashboardState,
    onOpenAsset: (String) -> Unit,
    onOpenSchedule: (String) -> Unit,
) {
    LazyColumn {
        AttentionSection.entries.forEach { section ->
            val rows = state.sections.firstOrNull { it.section == section }?.entries.orEmpty()
            // CURRENT is also where the systems with nothing the dashboard draws live, after its
            // own OK rows — so it has content whenever either half does.
            val assetsHere = if (section == AttentionSection.CURRENT) state.assets else emptyList()
            if (rows.isEmpty() && assetsHere.isEmpty()) return@forEach

            item(key = "header-${section.name}") {
                MaintenanceSectionTitle(title = sectionLabel(section))
            }
            items(rows.size, key = { index -> rows[index].key }) { index ->
                when (val entry = rows[index]) {
                    is SectionEntry.Schedule -> {
                        val item = entry.item
                        DueItemRow(
                            item = item,
                            onClick = { onOpenSchedule(item.scheduleId.value) },
                            // The repair is offered only by the repairable form: a missing meter
                            // baseline, which "Log meter reading" fixes. An empty required set gets
                            // no label at all, and it is not in a section to be offered one
                            // (invariant 74, §17.1a).
                            onRepair = { onOpenSchedule(item.scheduleId.value) },
                        )
                    }
                    // A condition or health row opens the asset it names; B14's detail draws the
                    // sections. Neither carries an action that writes.
                    is SectionEntry.AssetLevel -> when (entry.item.kind) {
                        AttentionKind.CONDITION ->
                            ConditionRow(entry.item, onClick = { onOpenAsset(entry.item.assetId.value) })
                        AttentionKind.HEALTH ->
                            HealthRow(entry.item, onClick = { onOpenAsset(entry.item.assetId.value) })
                    }
                }
                RowRule()
            }
            items(assetsHere.size, key = { index -> assetsHere[index].asset.id.value }) { index ->
                val row = assetsHere[index]
                CurrentRow(row = row, onClick = { onOpenAsset(row.asset.id.value) })
                RowRule()
            }
        }
    }
}

/** A lazy key unique across every kind of row in the list. */
private val SectionEntry.key: String
    get() = when (this) {
        is SectionEntry.Schedule -> item.scheduleId.value
        is SectionEntry.AssetLevel -> when (item.kind) {
            AttentionKind.CONDITION -> "condition-${item.assetId.value}"
            AttentionKind.HEALTH -> "health-${item.healthSubjectId?.value ?: "${item.assetId.value}-${item.rank}"}"
        }
    }

/**
 * A DOWN or DEGRADED unit (spec §10.2, §10.6): its condition's glyph — `block` or `trending_down` —
 * in the condition's token, the unit's name with the condition badge (S3 or S2, and S22 "since
 * \<date\>" read from the row's `since`, never recomputed), then the reason or S23, and for a
 * component the parent it belongs to in the shipped promoted-row form (inv. 122).
 *
 * **It never fails silently** (the review's M-5): a row missing a field draws what it has. With no
 * `since` the badge is the word and glyph alone; with no word at all the unit is still named with
 * its reason and parent. A DOWN unit never leaves an empty row behind.
 */
@Composable
internal fun ConditionRow(item: AttentionItem, onClick: () -> Unit) {
    // The row's own word; on a condition row it is the asset's current condition, so that is the
    // truthful fallback.
    val condition = item.condition ?: item.assetCondition
    val colors = conditionColors(condition, ServiceTagTheme.semanticColors)
    val view = condition?.let { item.conditionView(it) }
    AttentionRowFrame(
        glyph = {
            if (condition != null) {
                Icon(conditionGlyph(condition).icon, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(28.dp))
            } else {
                Spacer(modifier = Modifier.size(28.dp))
            }
        },
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = item.assetName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            when {
                view != null -> ConditionBadge(view)
                // S2 and S3 are ratified in upper case, so the shipped badge's upper-casing changes
                // nothing; only S4 would suffer it, and a condition row never draws S4.
                condition != null -> StatusBadge(label = conditionWord(condition), colors = colors, icon = conditionGlyph(condition).icon)
            }
        }
        QuietLine(reasonLine(item.reason.orEmpty()))
        item.parentName?.let { QuietLine(partOfLine(it)) }
    }
}

/**
 * The badge's view of a condition row: the row's own word and `since`, which are all the badge
 * reads. The row carries no event link, so none is claimed. Null when the row has no `since`, and
 * the row then draws its word without S22.
 */
private fun AttentionItem.conditionView(condition: OperationalCondition): ConditionView? {
    val sinceOn = since?.let(LocalDate::parse) ?: return null
    return ConditionView(
        condition = condition,
        since = sinceOn,
        reason = reason.orEmpty(),
        occurredOn = occurredOn?.let(LocalDate::parse) ?: sinceOn,
        occurredTime = null,
        eventId = null,
        eventExists = false,
    )
}

/**
 * An independent (AGE) health subject scoring CRITICAL or WARNING (spec §10.2, §10.6): the band's
 * bar glyph in its token, S110 "\<subject\> \<BAND\>" with the band badge beside it, and the
 * asset it is on — with its parent, for a component. Overdue-driven health is never a row of its
 * own: it rides its schedule's row.
 *
 * **It never fails silently** (the review's M-5): with a field missing, the title falls back to the
 * subject's name (or the asset's), the badge is drawn whenever the band is known, and the asset line
 * is always there.
 */
@Composable
internal fun HealthRow(item: AttentionItem, onClick: () -> Unit) {
    val band = item.band
    AttentionRowFrame(
        glyph = {
            if (band != null) {
                Icon(
                    healthGlyph(band).icon,
                    contentDescription = null,
                    tint = healthColors(band, ServiceTagTheme.semanticColors).foreground,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Spacer(modifier = Modifier.size(28.dp))
            }
        },
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = item.bandFact()?.let(::dashboardHealthRow) ?: item.subjectName ?: item.assetName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            band?.let { HealthBadge(band = it, score = item.score) }
        }
        QuietLine(promotedSubtitle(item.assetName, item.parentName))
    }
}

private fun AttentionItem.bandFact(): SubjectBandFact? {
    val id = healthSubjectId ?: return null
    val name = subjectName ?: return null
    val b = band ?: return null
    val s = score ?: return null
    return SubjectBandFact(id, name, b, s)
}

/** The shipped row metrics (G1 §1.2): 28dp glyph · text block · chevron, 56dp minimum. */
@Composable
private fun AttentionRowFrame(
    glyph: @Composable () -> Unit,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        glyph()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            content()
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowRule() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * The one card this screen is allowed (D12 §7, G1 §1.2): the nudge is not another row of the list,
 * it is a separate fact about the install, and a card is how a separate fact reads. It says what is
 * missing and what that costs before it offers the button.
 */
@Composable
private fun BackupNudge(onExport: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        shape = PlateShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "BACKUP",
                style = Eyebrow,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = "No backup yet", style = MaterialTheme.typography.titleMedium)
            QuietLine("Tags survive a phone change only if you have one.")
            Button(
                onClick = onExport,
                shape = ControlShape,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Export now")
            }
        }
    }
}

/** No assets at all: say so once, then offer the two ways an asset gets here. */
@Composable
private fun FirstRun(onNewAsset: () -> Unit, onScan: () -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuietLine("Nothing here yet")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewAsset, shape = ControlShape) { Text("Add your first asset") }
            OutlinedButton(onClick = onScan, shape = ControlShape) { Text("Scan a tag") }
        }
    }
}

/**
 * One asset in service with nothing scheduled on it. 28dp glyph · text block · chevron, 11dp
 * vertical padding (G1 §1.2). The glyph is `onSurfaceVariant`, not a state colour: a row with no
 * schedule has no state to carry, and one that looked OK by colour would be claiming something it
 * does not know.
 *
 * A component — which is only ever here because §11.1 promoted its due work — says whose component
 * it is in place of the schedule line. An asset that *has* a schedule is not here at all: its
 * schedule's row is in one of the sections above, at its own attention rank.
 */
@Composable
private fun CurrentRow(row: DashboardRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.asset.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // A component says whose part it is. An asset with nothing scheduled says so. An asset
            // that *has* a schedule but none the dashboard draws — every one of them paused, or a
            // round that obliges nobody — says neither: "No schedule yet" would be false, and §17
            // has no line for the true thing, so the subtitle is omitted rather than drafted.
            if (row.parentName != null) {
                QuietLine(partOfLine(row.parentName))
            } else if (!row.hasSchedule) {
                QuietLine("No schedule yet")
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
