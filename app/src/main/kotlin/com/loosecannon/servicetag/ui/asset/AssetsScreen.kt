package com.loosecannon.servicetag.ui.asset

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.journal.CategoryChoice
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedPlural
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.components.formContentInsets
import com.loosecannon.servicetag.ui.condition.ConditionBadge
import com.loosecannon.servicetag.ui.condition.conditionOption
import com.loosecannon.servicetag.ui.health.HealthBadge
import com.loosecannon.servicetag.ui.loan.LoanBadge
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import com.loosecannon.servicetag.ui.transfer.TransferStrings
import com.loosecannon.servicetag.ui.transfer.TransferredBadge

/**
 * The asset list: one line per asset, active rows first and archived ones only when asked for
 * (archive is not delete — R-9 — but a list that keeps showing what you archived is no better
 * than never archiving). Rows are hairline-separated lines, not cards (D12 §7), and adding an
 * asset is an app-bar action: the one FAB this app allows belongs to the ledger (G1 §3 c).
 *
 * The search box (#39) moved here from the Dashboard by the owner's 2026-09-23 instruction: the
 * Dashboard's category and maintenance-status dropdowns now serve as its navigation aids, and the
 * quick filter belongs on the screen that lists every asset and component under its controls.
 *
 * Under it, #73's one row of controls — Type, Components, Archived ([AssetsFilterRow]), and #116's
 * Condition after Type — narrows what the box searches. An empty list always says why ([EmptyReason],
 * the view model's decision).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    graph: AppGraph,
    onOpenAsset: (String) -> Unit,
    onNewAsset: () -> Unit,
    /** #77 (C17): P77-1 in the overflow opens the transfer selection, nothing preselected. The tap writes nothing. */
    onTransfer: () -> Unit = {},
) {
    val model: AssetsViewModel = viewModel { AssetsViewModel(graph) }
    val state by model.state.collectAsStateWithLifecycle()
    // The box draws itself from the view model's own query holder, not from `state.query` (F3):
    // the latter is a `combine`/`stateIn` round trip and a text field has to see its own keystroke
    // back in the same frame. `state.query` still decides what the list below says.
    val query by model.query.collectAsStateWithLifecycle()
    // #71 (plan C2): coming back to the list is the moment to re-derive health — an event, a season
    // activation or midnight moves no table the row health watches (the dashboard's staleness).
    LifecycleResumeEffect(model) {
        model.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        contentWindowInsets = formContentInsets,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.assets_title)) },
                actions = {
                    IconButton(onClick = onNewAsset) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.assets_add_asset))
                    }
                    AssetsOverflow(onTransfer = onTransfer)
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            SearchBox(
                query = query,
                onQueryChange = model::onQueryChange,
                onClear = model::clearQuery,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AssetsFilterRow(
                filters = state.filters,
                typeLabel = state.typeLabel,
                typeChoices = state.typeChoices,
                onPickType = model::pickType,
                onToggleComponents = model::toggleComponents,
                onToggleArchived = model::toggleArchived,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                onPickCondition = model::pickCondition,
            )
            if (state.emptyReason == EmptyReason.NONE) {
                LazyColumn {
                    itemsIndexed(state.items, key = { _, row -> row.asset.id.value }) { index, row ->
                        if (index > 0) {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        AssetListRow(row = row, onClick = { onOpenAsset(row.asset.id.value) })
                    }
                }
            } else {
                EmptyList(
                    reason = state.emptyReason,
                    archivedCount = state.archivedCount,
                    onNewAsset = onNewAsset,
                    onShowArchived = model::toggleArchived,
                )
            }
        }
    }
}

/** #77 (C17): the Assets overflow — P77-1, the one item. */
@Composable
private fun AssetsOverflow(onTransfer: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.common_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(TransferStrings.TRANSFER_ASSETS) }, onClick = { open = false; onTransfer() })
        }
    }
}

/**
 * #73's controls (C6), directly under the search box: the Type menu, then Components, then Archived.
 * A `FlowRow` of compact chips, each as wide as its label: at 380dp of content width and font scale
 * 1.0 the three share one line, and a longer chosen type or a larger font wraps the row onto more
 * lines rather than cutting a label (the #68 rule). Internal so the layout tests can draw it alone.
 *
 * **#116 (U-C3, U-C4).** A host that passes [onPickCondition] gets the Condition menu after Type, in
 * Type's shape; it is a fourth chip, so it may wrap on a narrow phone, and nothing else moves. A host
 * that passes none draws the three as before.
 */
@Composable
internal fun AssetsFilterRow(
    filters: AssetFilters,
    typeLabel: String?,
    typeChoices: List<CategoryChoice>,
    onPickType: (String?) -> Unit,
    onToggleComponents: () -> Unit,
    onToggleArchived: () -> Unit,
    modifier: Modifier = Modifier,
    archivedControl: Boolean = true,
    onPickCondition: ((OperationalCondition?) -> Unit)? = null,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        TypeChip(label = typeLabel, choices = typeChoices, onPick = onPickType)
        if (onPickCondition != null) ConditionChip(chosen = filters.condition, onPick = onPickCondition)
        FilterChip(
            selected = filters.showComponents,
            onClick = onToggleComponents,
            label = { Text(COMPONENTS) },
            shape = ControlShape,
        )
        if (archivedControl) FilterChip(
            selected = filters.showArchived,
            onClick = onToggleArchived,
            label = { Text(ARCHIVED) },
            shape = ControlShape,
        )
    }
}

/**
 * The Type control (C6, R73-3): the menu is All, then the catalog in its own order. Its accessible
 * name is always [TYPE] and its state the choice ([MenuChip]).
 */
@Composable
private fun TypeChip(label: String?, choices: List<CategoryChoice>, onPick: (String?) -> Unit) = MenuChip(
    name = TYPE,
    chosen = label,
    rows = listOf(ALL to { onPick(null) }) + choices.map { choice -> choice.display to { onPick(choice.key) } },
)

/**
 * #116 (U-C3, U-C4): the Condition control, in the Type control's shape. The menu is All (P73-2), then
 * the Change condition sheet's option words in the conditions' order; the label is [CONDITION]
 * (P116-4) while it is All and the chosen word after, and its accessible name is [CONDITION] always.
 */
@Composable
private fun ConditionChip(chosen: OperationalCondition?, onPick: (OperationalCondition?) -> Unit) = MenuChip(
    name = CONDITION,
    chosen = chosen?.let(::conditionOption),
    rows = listOf(ALL to { onPick(null) }) +
        OperationalCondition.entries.map { choice -> conditionOption(choice) to { onPick(choice) } },
)

/**
 * The list's menu controls (C6, R73-3): an `AssistChip`, the one Material 3 chip with a trailing slot and no
 * selection semantics — a `FilterChip` would read to TalkBack as a checkbox, checked or not, and
 * this opens a menu. Its accessible name is always [name] and its state the [chosen] row's word, or All
 * while nothing is chosen, so the chosen value is never hidden behind the name; the outer `role`
 * overrides the chip's own `Role.Button`. The menu is [rows] in order, with no mark on the chosen row
 * (the dashboard's shape). A choice draws in the selected chips' container, with no outline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuChip(name: String, chosen: String?, rows: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(chosen ?: name) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            shape = ControlShape,
            colors = if (chosen != null) {
                AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                AssistChipDefaults.assistChipColors()
            },
            border = if (chosen != null) {
                AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Color.Transparent)
            } else {
                AssistChipDefaults.assistChipBorder(enabled = true)
            },
            modifier = Modifier.semantics {
                role = Role.DropdownList
                contentDescription = name
                stateDescription = chosen ?: ALL
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            rows.forEach { (word, pick) ->
                DropdownMenuItem(
                    text = { Text(word) },
                    onClick = {
                        open = false
                        pick()
                    },
                )
            }
        }
    }
}

/**
 * The empty list's sentence, one per [EmptyReason] (C5), the screen computing none of it. A blank
 * query with nothing at all, or nothing active, still offers the way forward (F1): adding an asset,
 * and turning the archived rows back on. Every other reason is one quiet line naming the control in
 * the way (owner ruling §18.23, kept whole by R73-7), or saying honestly that nothing matched.
 */
@Composable
internal fun EmptyList(
    reason: EmptyReason,
    archivedCount: Int,
    onNewAsset: (() -> Unit)?,
    onShowArchived: () -> Unit,
) {
    val line = when (reason) {
        EmptyReason.NONE -> return
        EmptyReason.NO_ASSETS -> stringResource(R.string.assets_no_assets_yet)
        EmptyReason.NO_ACTIVE_ASSETS -> localizedPlural(R.plurals.assets_no_active_assets, archivedCount, archivedCount)
        EmptyReason.ONLY_COMPONENTS -> ONLY_COMPONENTS_LINE
        EmptyReason.NOTHING_MATCHES -> stringResource(R.string.assets_nothing_matches)
        EmptyReason.TYPE_HIDDEN -> TYPE_HIDDEN_LINE
        EmptyReason.COMPONENTS_HIDDEN -> COMPONENTS_HIDDEN_LINE
        EmptyReason.ARCHIVED_HIDDEN -> ARCHIVED_HIDDEN_LINE
        EmptyReason.BOTH_HIDDEN -> BOTH_HIDDEN_LINE
        EmptyReason.CONDITION_HIDDEN -> CONDITION_HIDDEN_LINE
    }
    if (reason != EmptyReason.NO_ASSETS && reason != EmptyReason.NO_ACTIVE_ASSETS) {
        QuietLine(text = line, modifier = Modifier.padding(16.dp))
        return
    }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuietLine(line)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onNewAsset != null) {
                Button(onClick = onNewAsset, shape = ControlShape) { Text(stringResource(R.string.assets_add_asset)) }
            }
            if (reason == EmptyReason.NO_ACTIVE_ASSETS) {
                OutlinedButton(onClick = onShowArchived, shape = ControlShape) {
                    Text(stringResource(R.string.assets_show_archived))
                }
            }
        }
    }
}

/**
 * Name over category, then "Part of <parent>" when the asset is a component of another (spec §9).
 * The badges do the work colour alone must not (D12 §5), and an asset can carry more than one:
 * retired and out of season are different facts and neither implies the other.
 *
 * **#71 (plan C4, E2, E5).** The badges wrap under the name — Retired, Out of season, Archived, their
 * words, families, glyphs and order unchanged — so no count of them, at any width or font scale, can
 * run past the row. While the row is a health surface ([AssetRow.health]) the same line goes on with
 * the health badge (the condition badge before it, as #116 amends below) (the band's glyph and word,
 * no score), and under it one quiet line per CRITICAL subject (S109) and per DOWN or DEGRADED
 * component (S27), in the detail's order and words. The NFC disc anchors the right edge; it is a
 * glyph with a description, never a control, so a tap on it opens the asset as a tap anywhere does.
 * No text here carries a line limit (the #68 rule). Internal so the layout tests can draw one row.
 *
 * **#116 (U-C4), amending R71-11 and E2 for the condition badge only.** Every row with a condition draws
 * it — Operational too, and on rows that are not health surfaces — once, after the loan badge and before
 * the health badge. Left of the disc, vertically centred, sit the in-season mark and then the Home
 * Assistant mark, each a glyph in the disc's box with its accessible name and no tap of its own. An
 * out-of-season asset keeps its badge and gets no mark, so a seasonal asset carries one season signifier.
 */
@Composable
internal fun AssetListRow(row: AssetRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val asset = row.asset
    val health = row.health
    // #116 (U-C4): one condition badge per row, the row's own fact. A row built with only its health view
    // (whose condition is read from the same current row) draws that; the health view never draws a second.
    val condition = row.condition ?: health?.condition
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = asset.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (asset.category.isNotBlank()) {
                Text(
                    text = asset.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.parentName?.let { parent ->
                Text(
                    text = stringResource(R.string.assets_part_of, parent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // #77 (C19): a held row says P77-31 instead of "Archived", whatever its status.
            val archived = if (row.transferred) null else statusLabel(asset.status)
            val loan = row.loan
            if (asset.isRetired || row.outOfSeason || archived != null || row.transferred || loan != null || condition != null || health != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    if (asset.isRetired) {
                        StatusBadge(
                            label = RETIRED,
                            colors = ServiceTagTheme.semanticColors.paused,
                            icon = ServiceTagIcons.PauseCircle,
                        )
                    }
                    if (row.outOfSeason) {
                        StatusBadge(
                            label = OUT_OF_SEASON,
                            colors = ServiceTagTheme.semanticColors.seasonInactive,
                            icon = ServiceTagIcons.CalendarMonth,
                        )
                    }
                    archived?.let { label ->
                        StatusBadge(label = label, colors = ServiceTagTheme.semanticColors.seasonInactive)
                    }
                    if (row.transferred) TransferredBadge()
                    // #72 (C19): after the lifecycle badges, before condition and health — custody is
                    // no maintenance fact, so it never sits among them.
                    loan?.let { LoanBadge(it) }
                    // Condition before health: a DOWN asset's health is never drawn before its
                    // condition (inv. 119), the detail's own rule.
                    condition?.let { ConditionBadge(it) }
                    health?.result?.aggregate?.let { HealthBadge(band = it.band, score = null) }
                }
            }
            health?.let { view -> rowHealthLines(view).forEach { line -> QuietLine(line) } }
        }
        if (row.inSeason || row.haLinked || row.hasWrittenTag) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (row.inSeason) {
                    RowMark(
                        name = IN_SEASON,
                        glyph = ServiceTagIcons.InSeason,
                        tint = ServiceTagTheme.semanticColors.maintenanceOkay.foreground,
                    )
                }
                if (row.haLinked) {
                    RowMark(name = HA_LINKED, glyph = ServiceTagIcons.HaLink, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (row.hasWrittenTag) NfcTagDisc()
            }
        }
    }
}

/**
 * #116 (U-C4): a trailing mark — its glyph in the NFC disc's 22dp box, with its accessible [name]. Informational,
 * never a control: no tap of its own, so a tap on it opens the asset as a tap anywhere on the row does. Colour is
 * never its only signifier: the glyph and the name say it. The in-season mark is in the OK family; the Home
 * Assistant mark is neutral, a link and not a status.
 */
@Composable
private fun RowMark(name: String, glyph: ImageVector, tint: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .semantics { contentDescription = name },
    ) {
        Icon(imageVector = glyph, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * #71 (R71-2): the written-tag mark — the app's NFC tag glyph in the OK family's cool blue, never a
 * green and never a check (D12's OK signifier). 22dp at any font scale: an informational glyph, not
 * a control. The glyph sits in its own centred box so the disc cannot stretch it.
 */
@Composable
private fun NfcTagDisc() {
    val colors = ServiceTagTheme.semanticColors.maintenanceOkay
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(colors.container)
            .semantics { contentDescription = NFC_TAG_WRITTEN },
    ) {
        Icon(
            imageVector = ServiceTagIcons.NfcTag,
            contentDescription = null,
            tint = colors.foreground,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** An active asset says nothing; the other says what it is, in the neutral family. */
internal fun statusLabel(status: AssetStatus): String? = when (status) {
    AssetStatus.ACTIVE -> null
    AssetStatus.ARCHIVED -> ARCHIVED
}

/** P71-4 (ratified 2026-09-26): the NFC disc's accessibility description. */
private val NFC_TAG_WRITTEN: String get() = localized(R.string.assets_nfc_tag_written)

/** The two badge words spec §7 and §6 fix, shared by the list row and the identity plate. */
internal val RETIRED: String get() = localized(R.string.assets_badge_retired)
internal val OUT_OF_SEASON: String get() = localized(R.string.assets_badge_out_of_season)

// #73's words (plan §6, ratified 2026-09-26). Each literal sits on one line of its own.

/** P73-1: the Type chip's label while it is All, and its accessible name always. */
private val TYPE: String get() = localized(R.string.assets_filter_type)

/** P73-2: the Type menu's first row, and the chip's state while it is All; #116 reads it for the Condition menu too. */
private val ALL: String get() = localized(R.string.assets_filter_all)

/** P73-3: the Components chip. */
private val COMPONENTS: String get() = localized(R.string.assets_filter_child_assets)

/** P73-4: the Archived chip, and the word the list's badge draws (upper-cased) on an archived row. */
private val ARCHIVED: String get() = localized(R.string.assets_filter_archived)

/** P73-5: every match of the chosen type is hidden by Archived alone (the §18.23 hint, reworded). */
private val ARCHIVED_HIDDEN_LINE: String get() = localized(R.string.assets_archived_hidden)

/** P73-6: every match of the chosen type is hidden by Components alone. */
private val COMPONENTS_HIDDEN_LINE: String get() = localized(R.string.assets_child_assets_hidden)

/** P73-7: the matches are hidden by both controls, together or one row each. */
private val BOTH_HIDDEN_LINE: String get() = localized(R.string.assets_both_hidden)

/** P73-9: a blank query, Type All, and every asset Archived admits is a component. */
private val ONLY_COMPONENTS_LINE: String get() = localized(R.string.assets_only_child_assets)

/** P73-10: the query has matches, all of another type. */
private val TYPE_HIDDEN_LINE: String get() = localized(R.string.assets_type_hidden)

// #116's words (plan §5, ratified 2026-10-26). Each literal sits on one line of its own.

/** P116-1: the Home Assistant mark's accessible name. */
private val HA_LINKED: String get() = localized(R.string.assets_ha_linked)

/** P116-2: the other controls and the query admit rows, and the Condition choice alone hides every one. */
private val CONDITION_HIDDEN_LINE: String get() = localized(R.string.assets_condition_hidden)

/** P116-3: the in-season mark's accessible name. */
private val IN_SEASON: String get() = localized(R.string.assets_in_season)

/** P116-4: the Condition chip's label while it is All, and its accessible name always. */
private val CONDITION: String get() = localized(R.string.assets_filter_condition)
