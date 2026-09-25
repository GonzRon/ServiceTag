package com.loosecannon.servicetag.ui.asset

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loosecannon.servicetag.core.health.HealthBand
import com.loosecannon.servicetag.core.journal.RangeState
import com.loosecannon.servicetag.core.journal.Reading
import com.loosecannon.servicetag.core.journal.SeedTemplates
import com.loosecannon.servicetag.core.journal.classify
import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.Money
import com.loosecannon.servicetag.core.model.OperationalCondition
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.model.isRetired
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.usecase.SeasonView
import com.loosecannon.servicetag.di.AppGraph
import com.loosecannon.servicetag.links.LinkLauncher
import com.loosecannon.servicetag.ui.attachments.AttachmentsSection
import com.loosecannon.servicetag.ui.components.ActionGrid
import com.loosecannon.servicetag.ui.components.ActionSpec
import com.loosecannon.servicetag.ui.components.IdentityPlate
import com.loosecannon.servicetag.ui.components.InstrumentList
import com.loosecannon.servicetag.ui.components.InstrumentRow
import com.loosecannon.servicetag.ui.components.LabelValue
import com.loosecannon.servicetag.ui.components.LedgerDateColumnWidth
import com.loosecannon.servicetag.ui.components.LedgerEntry
import com.loosecannon.servicetag.ui.components.LedgerList
import com.loosecannon.servicetag.ui.components.ServiceTagIcons
import com.loosecannon.servicetag.ui.components.PlateValue
import com.loosecannon.servicetag.ui.components.QuietLine
import com.loosecannon.servicetag.ui.components.SectionHeader
import com.loosecannon.servicetag.ui.components.StateGlyph
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.components.TypedConfirmDialog
import com.loosecannon.servicetag.ui.condition.CHANGE_CONDITION
import com.loosecannon.servicetag.ui.condition.CONDITION_TITLE
import com.loosecannon.servicetag.ui.condition.ChangeConditionSheet
import com.loosecannon.servicetag.ui.condition.ConditionBadge
import com.loosecannon.servicetag.ui.condition.END_SEASON
import com.loosecannon.servicetag.ui.condition.MARK_OPERATIONAL
import com.loosecannon.servicetag.ui.condition.MarkOperationalDialog
import com.loosecannon.servicetag.ui.condition.START_SEASON
import com.loosecannon.servicetag.ui.condition.WHEN_DID_THIS_CHANGE
import com.loosecannon.servicetag.ui.condition.conditionColors
import com.loosecannon.servicetag.ui.condition.conditionGlyph
import com.loosecannon.servicetag.ui.condition.conditionWord
import com.loosecannon.servicetag.ui.condition.displayDate
import com.loosecannon.servicetag.ui.condition.reasonLine
import com.loosecannon.servicetag.ui.health.AndroidHealthPlurals
import com.loosecannon.servicetag.ui.health.HealthBadge
import com.loosecannon.servicetag.ui.health.HealthPlurals
import com.loosecannon.servicetag.ui.health.healthColors
import com.loosecannon.servicetag.ui.health.healthGlyph
import com.loosecannon.servicetag.ui.theme.BadgeShape
import com.loosecannon.servicetag.ui.journal.eventDetailLine
import com.loosecannon.servicetag.ui.journal.formatTarget
import com.loosecannon.servicetag.ui.journal.formatValue
import com.loosecannon.servicetag.ui.journal.quickActionLabel
import com.loosecannon.servicetag.ui.journal.stateColors
import com.loosecannon.servicetag.ui.journal.stateIcon
import com.loosecannon.servicetag.ui.journal.stateLabel
import com.loosecannon.servicetag.ui.references.ReferencesSection
import com.loosecannon.servicetag.ui.scan.identityLine
import com.loosecannon.servicetag.ui.scan.placementOrNull
import com.loosecannon.servicetag.ui.theme.ControlShape
import com.loosecannon.servicetag.ui.theme.ServiceTagTheme
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One asset, as the Apollo Service Binder draws it (D12 §8, G1 §1.1): identity plate, the current
 * readings, quick actions, then the service record and the reference sections, separated by
 * hairline rules. There are still no schedules, so the status block is one quiet line; everything
 * else on the screen is the journal of spec §10.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    graph: AppGraph,
    assetId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onSetup: (String) -> Unit,
    onWriteTag: (String) -> Unit,
    onBackup: () -> Unit,
    onLogEvent: (assetId: String, profileId: String) -> Unit,
    onOpenEvent: (eventId: String) -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    onAddComponent: (parentAssetId: String) -> Unit,
    /**
     * 1.2 — "add a schedule for this asset": the create entry point, and the only thing B14 adds to
     * this screen. **B15 owns the schedules *section*** here; this is the action, not the list.
     */
    onAddSchedule: (assetId: String) -> Unit,
    /** A free-form entry of one [EventKind] — the retirement follow-on of spec §7 opens it. */
    onLogOutcome: (assetId: String, kind: String) -> Unit,
    /** DOCUMENTS sends the person here when there is no attachment folder yet (spec §8.1). */
    onOpenSettings: () -> Unit,
    /** 1.2 — a schedule row opens the schedule; a group row opens the group (#55's two directions). */
    onOpenSchedule: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
) {
    val model: AssetDetailViewModel = viewModel(key = assetId) { AssetDetailViewModel(graph, assetId) }
    val state by model.state.collectAsStateWithLifecycle()
    val missing by model.missing.collectAsStateWithLifecycle()
    val prompt by model.prompt.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var pickingTemplate by remember { mutableStateOf(false) }
    // 1.4 — B12's two condition surfaces, opened from the Condition section; saveable, as the scan
    // sheet keeps them, so a rotation does not drop an open surface.
    var changingCondition by rememberSaveable { mutableStateOf(false) }
    var markingOperational by rememberSaveable { mutableStateOf<OperationalCondition?>(null) }
    // S99's `<age>` and S102 through B12's day forms, read from this screen's own resources.
    val resources = LocalResources.current
    val plurals: HealthPlurals = remember(resources) { AndroidHealthPlurals(resources) }

    // A deep link, a restored back stack or a replacing import can name an asset that is not there
    // any more. Leaving is the honest answer; an empty plate would pretend it still exists.
    LaunchedEffect(missing) { if (missing) onBack() }
    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }
    // A deleted asset leaves by its own door rather than through `missing`: the pop happens once,
    // on the write, and not as a side effect of the row disappearing from a flow.
    LaunchedEffect(model) { model.deleted.collect { onBack() } }

    val asset = state?.asset
    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = asset?.name ?: "Asset",
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
                    if (asset != null) {
                        DetailOverflow(
                            archived = asset.status != AssetStatus.ACTIVE,
                            retired = asset.isRetired,
                            onEdit = { onEdit(assetId) },
                            onArchive = model::archive,
                            onUnarchive = model::unarchive,
                            onRetire = model::askRetire,
                            onUnretire = model::unretire,
                            onDelete = model::askDelete,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val current = state
        if (current == null) {
            QuietLine("Loading…", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        if (pickingTemplate) {
            TemplatePicker(
                onDismiss = { pickingTemplate = false },
                onPick = { key ->
                    pickingTemplate = false
                    model.setUpFromTemplate(key)
                },
            )
        }
        DetailPrompts(
            prompt = prompt,
            assetName = current.asset.name,
            onDismiss = model::dismissPrompt,
            onRetire = model::retire,
            onDelete = model::delete,
            onLogOutcome = { kind -> model.dismissPrompt(); onLogOutcome(assetId, kind) },
            onSeasonDate = model::onSeasonDate,
            onConfirmSeason = model::confirmSeason,
        )
        // The page redraws from the condition flow when either closes: nothing to refresh by hand.
        if (changingCondition) {
            ChangeConditionSheet(graph = graph, assetId = assetId) { changingCondition = false }
        }
        markingOperational?.let { condition ->
            MarkOperationalDialog(graph = graph, assetId = assetId, current = condition) { markingOperational = null }
        }
        // The screen's 16dp gutter is applied per block rather than to the whole scroll, because
        // 1.2's two maintenance sections draw their **own** gutter: they reuse the Maintenance
        // destination's heading and its due row verbatim, so a group's work reads the same on this
        // screen as on that one, and a second inset would push them 32dp in.
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                AssetPlate(current)
                current.parentName?.let { parent ->
                    PartOfLine(parent) { current.parentId?.let(onOpenAsset) }
                }
                ReadingsSection(current.readings)
                Spacer(Modifier.height(14.dp))
                ActionGrid(
                    actions = detailActions(
                        assetId = assetId,
                        profiles = current.profiles,
                        bare = current.bare,
                        onLogEvent = onLogEvent,
                        onEdit = onEdit,
                        onSetup = onSetup,
                        onWriteTag = onWriteTag,
                        onBackup = onBackup,
                        onSetUp = { pickingTemplate = true },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                DetailsSection(current)
                // 1.4 — the three independent facts, always in this order (spec §10.3): condition
                // first, so a DOWN asset's health is never drawn above its condition (inv. 119).
                ConditionSection(
                    state = current,
                    onChangeCondition = { changingCondition = true },
                    onMarkOperational = { markingOperational = it },
                    onOpenEvent = onOpenEvent,
                )
                HealthSection(current.healthBlocks, plurals)
                SeasonSection(
                    season = current.season,
                    onStart = { model.askSeason(SeasonAction.START) },
                    onEnd = { model.askSeason(SeasonAction.END) },
                )
            }
            // 1.2 — what is scheduled on this asset, and who it shares work with (spec §2.6).
            //
            // The standalone "No schedule yet" line that used to sit above the actions is now this
            // section's empty state — the same shipped sentence, under the heading it belongs to —
            // and B14's create entry, which stood beside that line, is now the section heading's
            // trailing action, where B14's own note asks B15 to carry it. It stays a glyph labelled
            // with the ratified section word, because §17 ratifies no wording for it.
            AssetMaintenanceSections(
                schedules = current.schedules,
                groups = current.groups,
                onOpenSchedule = onOpenSchedule,
                onOpenGroup = onOpenGroup,
                onAddSchedule = { onAddSchedule(assetId) },
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                ComponentsSection(
                    components = current.components,
                    onOpenAsset = onOpenAsset,
                    onAddComponent = { onAddComponent(assetId) },
                )
                ServiceRecordSection(current.events, current.definitions, onOpenEvent)
                TagsSection(current.tags, onEditLabel = model::editTagLabel)
                AttachmentsSection(
                    graph = graph,
                    owner = AttachmentOwner.OfAsset(current.asset.id),
                    snackbars = snackbars,
                    onOpenSettings = onOpenSettings,
                )
                // 1.3.0 — pointers, below the bytes they are not (D-10). `LinkLauncher` is the one
                // place `ACTION_VIEW` is fired, so the section hands it a URI and reads the answer.
                // `notify = false`: this surface has a snackbar host and draws the missing-handler
                // line itself, so the launcher must not toast the same sentence over the top of it.
                val activity = LocalActivity.current
                ReferencesSection(
                    assetId = current.asset.id,
                    graph = graph,
                    snackbars = snackbars,
                    onOpen = { uri ->
                        activity?.let { LinkLauncher.open(it, uri, notify = false) } == true
                    },
                )
                NotesSection(current.asset.notes)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Quick actions first, because logging is what someone standing next to the machine came to do
 * (spec §10); the four utility actions keep the order 1C gave them. "Set up from template" only
 * appears while the asset has nothing to log against — once it has, the template is refused
 * anyway, and an action that cannot work is worse than no action.
 */
@Composable
private fun detailActions(
    assetId: String,
    profiles: List<EventProfile>,
    bare: Boolean,
    onLogEvent: (String, String) -> Unit,
    onEdit: (String) -> Unit,
    onSetup: (String) -> Unit,
    onWriteTag: (String) -> Unit,
    onBackup: () -> Unit,
    onSetUp: () -> Unit,
): List<ActionSpec> {
    val ledger = ServiceTagIcons.History
    val nfc = ServiceTagIcons.NfcTag
    val backup = ServiceTagIcons.Backup
    return buildList {
        profiles.forEach { profile ->
            add(
                ActionSpec(quickActionLabel(profile), ledger, outlined = false) {
                    onLogEvent(assetId, profile.id.value)
                },
            )
        }
        add(ActionSpec("Write tag", nfc, outlined = true) { onWriteTag(assetId) })
        add(ActionSpec("Edit", Icons.Outlined.Edit, outlined = true) { onEdit(assetId) })
        // What this asset measures and what can be logged against it, both editable (spec §9).
        add(ActionSpec("Readings & actions", ServiceTagIcons.Speed, outlined = true) { onSetup(assetId) })
        add(ActionSpec("Backup", backup, outlined = false, onClick = onBackup))
        if (bare) add(ActionSpec("Set up from template", Icons.Outlined.Add, outlined = true, onClick = onSetUp))
    }
}

/** The five seeds by name. A template is starter data, so the dialog explains nothing further. */
@Composable
private fun TemplatePicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set up from template") },
        text = {
            Column {
                SeedTemplates.all.forEach { template ->
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(template.key) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Absent, not empty, when the asset has no definitions: there is no instrument panel to show.
 * A DERIVED row sits among the entered ones in `sortOrder`, marked so the number is not mistaken
 * for something someone wrote down, and reads "—" while no single event can produce it (spec §5).
 */
@Composable
private fun ReadingsSection(readings: List<Reading>) {
    if (readings.isEmpty()) return
    SectionHeader(title = "Current readings")
    InstrumentList(count = readings.size) { index ->
        val reading = readings[index]
        val derived = reading.definition.kind == DefinitionKind.DERIVED
        InstrumentRow(
            eyebrow = if (derived) "Derived" else null,
            label = reading.definition.label,
            target = formatTarget(reading.definition),
            value = formatValue(reading),
            // The measurement's unit is a snapshot of the definition's at entry (§4): show what
            // was actually measured in, and fall back to the definition only for an empty row.
            unit = reading.measurement?.unit ?: reading.definition.unit,
            state = reading.state,
        )
    }
}

/** The chronological ledger of D12 §8 — a maintenance record, newest first, never a feed. */
@Composable
private fun ServiceRecordSection(
    events: List<AssetEvent>,
    definitions: List<MeasurementDefinition>,
    onOpenEvent: (String) -> Unit,
) {
    SectionHeader(title = "Service record")
    if (events.isEmpty()) {
        QuietLine("No service recorded yet")
        return
    }
    val byId: Map<DefinitionId, MeasurementDefinition> = definitions.associateBy { it.id }
    LedgerList(count = events.size) { index ->
        val event = events[index]
        val (day, month, year) = event.occurredOn.asLedgerDate()
        val flagged = outOfRange(event, byId)
        LedgerEntry(
            day = day,
            month = month,
            year = year,
            title = event.title,
            detail = eventDetailLine(event, byId).takeIf { it.isNotBlank() },
            badge = flagged?.let { state ->
                {
                    StatusBadge(
                        label = stateLabel(state),
                        colors = stateColors(state, ServiceTagTheme.semanticColors),
                        icon = stateIcon(state),
                    )
                }
            },
            modifier = Modifier.clickable { onOpenEvent(event.id.value) },
        )
    }
}

/**
 * The ledger badge says something only when a reading in the entry is outside its target — an
 * in-range entry is the normal case and does not need decorating (D12 §5).
 */
private fun outOfRange(
    event: AssetEvent,
    definitions: Map<DefinitionId, MeasurementDefinition>,
): RangeState? = event.measurements
    .sortedBy { it.sortOrder }
    .firstNotNullOfOrNull { m ->
        val definition = definitions[m.definitionId] ?: return@firstNotNullOfOrNull null
        if (definition.valueType != ValueType.NUMBER) return@firstNotNullOfOrNull null
        val value = m.valueNum ?: return@firstNotNullOfOrNull null
        classify(value, definition.rangeLow, definition.rangeHigh)
            .takeIf { it == RangeState.LOW || it == RangeState.HIGH }
    }

/**
 * Edit, then the two reversible lifecycle actions, then the one that is not. Archive and retire are
 * different facts and both are offered: archived is "off my list", retired is "out of service"
 * (spec §7), and an asset can honestly be either, both or neither. Delete is last and spelled in
 * the destructive family, because it is the only item here that cannot be undone.
 */
@Composable
private fun DetailOverflow(
    archived: Boolean,
    retired: Boolean,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onRetire: () -> Unit,
    onUnretire: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("Edit") }, onClick = { open = false; onEdit() })
        DropdownMenuItem(
            text = { Text(if (archived) "Unarchive" else "Archive") },
            onClick = { open = false; if (archived) onUnarchive() else onArchive() },
        )
        DropdownMenuItem(
            text = { Text(if (retired) "Unretire" else "Retire") },
            onClick = { open = false; if (retired) onUnretire() else onRetire() },
        )
        DropdownMenuItem(
            text = {
                Text("Delete", color = ServiceTagTheme.semanticColors.destructiveAction.foreground)
            },
            onClick = { open = false; onDelete() },
        )
    }
}

/**
 * Every dialog this screen can show, in one place, driven by the ViewModel's one prompt: the
 * retirement date, the follow-on offer that comes *after* the retirement is already written, the
 * delete confirmation, and the children-first refusal (spec §5, §7).
 */
@Composable
private fun DetailPrompts(
    prompt: DetailPrompt?,
    assetName: String,
    onDismiss: () -> Unit,
    onRetire: (String) -> Unit,
    onDelete: () -> Unit,
    onLogOutcome: (String) -> Unit,
    onSeasonDate: (String) -> Unit,
    onConfirmSeason: () -> Unit,
) {
    when (prompt) {
        null -> Unit
        is DetailPrompt.SeasonChange -> SeasonDialog(prompt, onSeasonDate, onConfirmSeason, onDismiss)
        is DetailPrompt.Retire -> RetireDialog(prompt.date, onDismiss, onRetire)
        DetailPrompt.LogWhatHappened -> LogWhatHappenedDialog(
            onDismiss = onDismiss,
            onReplacement = { onLogOutcome(EventKind.REPLACEMENT.name) },
            onNote = { onLogOutcome(EventKind.NOTE.name) },
        )
        DetailPrompt.ConfirmDelete -> TypedConfirmDialog(
            title = "Delete $assetName?",
            body = "Type the asset's name to delete it. Its tags, readings and history go with it. " +
                "There is no automatic snapshot yet.",
            expected = assetName,
            confirmLabel = "Delete",
            onConfirm = onDelete,
            onDismiss = onDismiss,
        )
        is DetailPrompt.DeleteRefused -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Components first") },
            text = {
                Text(
                    "$assetName still has ${prompt.children.joinToString(", ")}. Move or delete " +
                        "them first, so nothing disappears by cascade.",
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
    }
}

/**
 * The date the asset went out of service — today by default, and freely backdated, because
 * "I replaced this in April" is the normal case (spec §7). Retiring commits on its own: nothing
 * about the follow-on offer is decided here.
 */
@Composable
private fun RetireDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var date by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Retire this asset?") },
        text = {
            Column {
                Text("It keeps its history and its tags still resolve.")
                Spacer(Modifier.height(12.dp))
                DateField(value = date, onValueChange = { date = it }, label = "Retired on")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(date) }) { Text("Retire") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Offered after the retirement is written, so all three answers are fine ones and "Not now" is
 * not a cancel: the two entries are a convenience, and declining changes nothing (spec §7).
 */
@Composable
private fun LogWhatHappenedDialog(
    onDismiss: () -> Unit,
    onReplacement: () -> Unit,
    onNote: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log what happened?") },
        text = {
            Column {
                Text(
                    text = "The asset is retired either way.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                listOf("Log replacement" to onReplacement, "Log note" to onNote).forEach { (label, pick) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = pick)
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * The plate's six cells are fixed in spec §9: MODEL / SERIAL / LOCATION / PURCHASED / IN SERVICE /
 * NFC TAG, laid out 2×3. The category is the eyebrow above them and is not repeated as a cell. A
 * blank [PlateValue] renders as "—", which is the honest thing to show for an unfilled field.
 */
@Composable
private fun AssetPlate(state: AssetDetailState) {
    val asset = state.asset
    IdentityPlate(
        category = asset.category.ifBlank { "Asset" },
        model = asset.name,
        name = asset.description.takeIf { it.isNotBlank() },
        cells = listOf(
            "Model" to PlateValue(modelLine(asset)),
            "Serial" to PlateValue(asset.serialNumber, mono = true),
            "Location" to PlateValue(asset.location),
            "Purchased" to PlateValue(asset.purchaseOn.orEmpty().asDayDate()),
            "In service" to PlateValue(asset.inServiceOn.orEmpty().asDayDate()),
            "NFC tag" to PlateValue(state.tags.firstOrNull()?.identityLine().orEmpty(), mono = true),
        ),
        icon = categoryIcon(asset.category),
        badges = plateBadges(state.plate),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Make and model as one line, because that is how the plate on the machine reads. Either half
 * alone is fine; neither leaves the cell to render its own "—" (spec §9).
 */
private fun modelLine(asset: Asset): String =
    listOf(asset.manufacturer, asset.model).filter { it.isNotBlank() }.joinToString(" ")

/**
 * Condition, retired, archived and out of season are four independent facts and an asset can carry
 * all four (spec §6, §7, §10.3). Each gets its own D12 §5 family, wording and glyph. The condition
 * badge is on every plate — "Condition not recorded" is a fact too — so the slot always exists;
 * which badges appear is [AssetDetailState.plate]'s decision, not this function's.
 */
@Composable
private fun plateBadges(facts: List<PlateFact>): (@Composable FlowRowScope.() -> Unit) {
    val semantic = ServiceTagTheme.semanticColors
    val retiredIcon = ServiceTagIcons.PauseCircle
    val seasonIcon = ServiceTagIcons.CalendarMonth
    return {
        facts.forEach { fact ->
            when (fact) {
                is PlateFact.Condition -> ConditionBadge(fact.view)
                PlateFact.Retired -> StatusBadge(label = RETIRED, colors = semantic.paused, icon = retiredIcon)
                is PlateFact.Archived -> StatusBadge(label = fact.label, colors = semantic.seasonInactive)
                PlateFact.OutOfSeason ->
                    StatusBadge(label = OUT_OF_SEASON, colors = semantic.seasonInactive, icon = seasonIcon)
            }
        }
    }
}

/** "Part of <parent>" under the plate, tapping through to the parent (spec §9). */
@Composable
private fun PartOfLine(parentName: String, onClick: () -> Unit) {
    QuietLine(
        text = "Part of $parentName",
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(top = 10.dp, bottom = 4.dp),
    )
}

/**
 * The fields that are neither identity nor journal: what it cost, who from, and what the warranty
 * says (spec §9). Only the ones that are actually set appear, and the section is absent rather
 * than empty when none are — a list of five dashes tells nobody anything.
 */
@Composable
private fun DetailsSection(state: AssetDetailState) {
    val asset = state.asset
    val rows = buildList {
        asset.purchaseOn?.let { add("Purchase date" to it.asDayDate()) }
        priceLine(asset)?.let { add("Price" to it) }
        asset.vendor.takeIf { it.isNotBlank() }?.let { add("Vendor" to it) }
        asset.warrantyExpiresOn?.let { on ->
            // The date on its own makes the reader do the arithmetic; the word does it for them.
            add("Warranty" to on.asDayDate() + if (state.warrantyExpired) " (expired)" else "")
        }
        asset.warrantyNotes.takeIf { it.isNotBlank() }?.let { add("Warranty notes" to it) }
    }
    if (rows.isEmpty()) return
    SectionHeader(title = "Details")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (label, value) ->
            LabelValue(label = label, value = value, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The stored price through [Money], which owns minor units both ways; null when there is none. */
private fun priceLine(asset: Asset): String? {
    val minor = asset.purchasePriceMinor ?: return null
    val code = asset.currency ?: return null
    return runCatching { Money.format(minor, code) }.getOrNull()
}

/**
 * The asset's children (spec §9). Always present, because "+ Add component" is how the first child
 * gets made and an action nobody can reach is no action at all; empty reads "No components" rather
 * than vanishing. Each row says how many of the child's *own* readings are out of range and never
 * what they read: 2B-2 rolls nothing up, so a parent that looks fine is not a claim about its
 * components, only an invitation to open one.
 */
@Composable
private fun ComponentsSection(
    components: List<ComponentRow>,
    onOpenAsset: (String) -> Unit,
    onAddComponent: () -> Unit,
) {
    SectionHeader(title = "Components")
    Column {
        if (components.isEmpty()) QuietLine("No components")
        components.forEach { child ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAsset(child.id) }
                    .heightIn(min = 56.dp)
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = child.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                QuietLine(componentLine(child))
                // 1.4 — the child's own condition badge (spec §10.3), S4 when none is recorded.
                ConditionBadge(child.condition, Modifier.padding(top = 4.dp))
            }
        }
        TextButton(onClick = onAddComponent) { Text("+ Add component") }
    }
}

/**
 * **Condition** (S5; spec §10.3, §5.1): the current badge, the current row's reason (or S23), **S7**
 * for a DOWN or DEGRADED asset and **S6**, then **S21** — every row, newest first, each with its day,
 * word, reason and link, or S24 where the linked record is gone. Each action only opens B12's own
 * surface; nothing here writes, and no history row can be edited or removed (inv. 89, 107, 110).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionSection(
    state: AssetDetailState,
    onChangeCondition: () -> Unit,
    onMarkOperational: (OperationalCondition) -> Unit,
    onOpenEvent: (String) -> Unit,
) {
    val current = state.condition
    SentenceSectionHeader(CONDITION_TITLE)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ConditionBadge(current)
        current?.let { QuietLine(reasonLine(it.reason)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val condition = current?.condition
            if (condition == OperationalCondition.DOWN || condition == OperationalCondition.DEGRADED) {
                Button(onClick = { onMarkOperational(condition) }, shape = ControlShape) { Text(MARK_OPERATIONAL) }
            }
            OutlinedButton(onClick = onChangeCondition, shape = ControlShape) { Text(CHANGE_CONDITION) }
        }
    }
    SentenceSectionHeader(CONDITION_HISTORY)
    LedgerList(count = state.conditionHistory.size) { index ->
        val row = state.conditionHistory[index]
        val (day, month, year) = row.occurredOn.asLedgerDate()
        Column {
            LedgerEntry(
                day = day,
                month = month,
                year = year,
                title = conditionWord(row.condition),
                detail = reasonLine(row.reason),
                meta = listOfNotNull(row.occurredTime),
            )
            when {
                row.linkRemoved -> QuietLine(
                    LINKED_RECORD_REMOVED,
                    Modifier.padding(start = LedgerDateColumnWidth, bottom = 8.dp),
                )
                row.eventExists && row.eventId != null -> Text(
                    text = row.eventTitle.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = LedgerDateColumnWidth)
                        .fillMaxWidth()
                        .clickable { onOpenEvent(row.eventId.value) }
                        .heightIn(min = 44.dp)
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * **Health** (S94; spec §10.3, §6.5, §6.6), drawn from [HealthBlock]s in their order: every CRITICAL
 * subject (S109) and every DOWN or DEGRADED component (S27) **first**, then the aggregate — its badge
 * and S108, or S98 — with S138 when the primary fell back, then each subject with its badge or S98
 * and its driver lines, then S107. The words are [words]'s; this function only lays them out.
 */
@Composable
private fun HealthSection(blocks: List<HealthBlock>, plurals: HealthPlurals) {
    val semantic = ServiceTagTheme.semanticColors
    SentenceSectionHeader(HEALTH_SECTION)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            val words = block.words(plurals, ::displayDate)
            when (block) {
                is HealthBlock.Critical -> GlyphLine(
                    glyph = healthGlyph(HealthBand.CRITICAL),
                    tint = healthColors(HealthBand.CRITICAL, semantic).foreground,
                    text = words.single(),
                )
                is HealthBlock.Component -> GlyphLine(
                    glyph = conditionGlyph(block.component.condition),
                    tint = conditionColors(block.component.condition, semantic).foreground,
                    text = words.single(),
                )
                is HealthBlock.Aggregate -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The badge draws the band word (or S98 alone); S108 follows it only with a value.
                    HealthBadge(band = block.value?.band, score = null)
                    if (block.value != null) Text(words.last(), style = MaterialTheme.typography.bodyMedium)
                }
                HealthBlock.Fallback -> QuietLine(words.single())
                is HealthBlock.Subject -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = words[0],
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        HealthBadge(band = block.health.band, score = block.health.score)
                    }
                    words.drop(2).forEach { QuietLine(it) }
                }
                HealthBlock.Footer -> Text(
                    text = words.single(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A glyph in its state's colour beside one line: a critical subject or a broken component. */
@Composable
private fun GlyphLine(glyph: StateGlyph, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(imageVector = glyph.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * **Season** (spec §10.3): the mode's own lines, then the break. YEAR_ROUND reads S29. CALENDAR reads
 * S32 and S33 with the window's days, the phase (S39 or the shipped out-of-season word) and S50 or
 * S49. MANUAL reads the phase and offers **S40** when out of season or **S41** when in — each opens
 * its dialog and writes nothing — then S48 with every START and END, newest first. No MANUAL start
 * is ever predicted (Q-6). A set break adds S58 with S60 and S61; it never changes the phase word.
 *
 * The phase is drawn as ratified — S39 "IN SEASON", the shipped "Out of season" — by [PhaseBadge]
 * rather than the upper-casing `StatusBadge`, which keeps the plate's shipped badge the one
 * "OUT OF SEASON" on the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonSection(season: SeasonView, onStart: () -> Unit, onEnd: () -> Unit) {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(top = 18.dp, bottom = 10.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (season.seasonMode) {
            SeasonMode.YEAR_ROUND -> Text(
                text = YEAR_ROUND,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SeasonMode.CALENDAR -> {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    SentenceLabelValue(SEASON_STARTS, monthDayText(season.seasonStartMmdd))
                    SentenceLabelValue(SEASON_ENDS, monthDayText(season.seasonEndMmdd))
                }
                PhaseLine(season.phase, calendarLine(season, ::displayDate))
            }
            SeasonMode.MANUAL -> {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    PhaseBadge(season.phase)
                    when (manualAction(season)) {
                        SeasonAction.START -> Button(onClick = onStart, shape = ControlShape) { Text(START_SEASON) }
                        SeasonAction.END -> OutlinedButton(onClick = onEnd, shape = ControlShape) { Text(END_SEASON) }
                        null -> Unit
                    }
                }
            }
        }
    }
    if (season.seasonMode == SeasonMode.MANUAL) {
        val rows = seasonHistory(season)
        SentenceSectionHeader(SEASON_HISTORY)
        LedgerList(count = rows.size) { index ->
            val row = rows[index]
            val (day, month, year) = row.occurredOn.asLedgerDate()
            LedgerEntry(day = day, month = month, year = year, title = activationWord(row.action))
        }
    }
    val breakStart = season.blackoutStartMmdd
    val breakEnd = season.blackoutEndMmdd
    if (breakStart != null && breakEnd != null) {
        SentenceSectionHeader(MAINTENANCE_BREAK)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SentenceLabelValue(BREAK_STARTS, monthDayText(breakStart))
            SentenceLabelValue(BREAK_ENDS, monthDayText(breakEnd))
        }
    }
}

/** The phase badge beside S50 or S49, on one line when there is room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhaseLine(phase: SeasonPhase, line: String?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        PhaseBadge(phase)
        line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
    }
}

/**
 * The season phase as one badge (spec §10.6): S39 "IN SEASON" with `event_available` in the cool
 * blue family, or the shipped "Out of season" with the calendar glyph in the season-inactive grey.
 * Each word is drawn as ratified, never re-cased.
 */
@Composable
private fun PhaseBadge(phase: SeasonPhase) {
    val semantic = ServiceTagTheme.semanticColors
    val inSeason = phase == SeasonPhase.IN_SEASON
    val colors = if (inSeason) semantic.maintenanceOkay else semantic.seasonInactive
    Surface(color = colors.container, contentColor = colors.foreground, shape = BadgeShape) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Icon(
                imageVector = if (inSeason) StateGlyph.EVENT_AVAILABLE.icon else ServiceTagIcons.CalendarMonth,
                contentDescription = null,
                tint = colors.foreground,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (inSeason) IN_SEASON_WORD else OUT_OF_SEASON,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
            )
        }
    }
}

/** A ratified field name over its value, in the case it was ratified (S32, S33, S60, S61). */
@Composable
private fun SentenceLabelValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * **Start season** (S42 over S43) or **End season** (S44 over S45): the date — today by default —
 * under B12's S15 field name, the S54 refusal under it when the date is outside `[latest row,
 * today]`, the confirm (S40 or S41) and Cancel. Cancel and dismissal write nothing (inv. 93).
 */
@Composable
private fun SeasonDialog(
    prompt: DetailPrompt.SeasonChange,
    onDate: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val start = prompt.action == SeasonAction.START
    AlertDialog(
        onDismissRequest = { if (!prompt.saving) onDismiss() },
        title = { Text(if (start) START_THE_SEASON else END_THE_SEASON) },
        text = {
            Column {
                Text(if (start) startSeasonBody(prompt.parsed?.let(::displayDate) ?: prompt.date) else END_SEASON_BODY)
                Spacer(Modifier.height(12.dp))
                DateField(
                    value = prompt.date,
                    onValueChange = onDate,
                    label = WHEN_DID_THIS_CHANGE,
                    problem = prompt.refusal,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = prompt.canConfirm) { Text(if (start) START_SEASON else END_SEASON) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !prompt.saving) { Text("Cancel") } },
    )
}

/** A window's `MM-DD` as a day and month ("1 May"); the stored text itself if it is not one. */
private fun monthDayText(mmdd: String?): String {
    if (mmdd == null) return ""
    return runCatching { MonthDay.parse("--$mmdd").format(monthDay) }.getOrDefault(mmdd)
}

/** "Pump · Water · 1 reading out of range", with an unset category simply left out. */
private fun componentLine(child: ComponentRow): String = listOfNotNull(
    child.category.takeIf { it.isNotBlank() },
    when (child.outOfRange) {
        0 -> "No readings out of range"
        1 -> "1 reading out of range"
        else -> "${child.outOfRange} readings out of range"
    },
).joinToString(" · ")

/**
 * Every bound tag (#49 AC 6) — a lost, retired or freshly written one all get their own row, so
 * the section never implies the first tag is the only scan point. Tapping a row opens the
 * "Tag placement" edit; a tag with no placement set shows no caption at all, which is the
 * honest reading for an ordinary one-tag asset.
 */
@Composable
internal fun TagsSection(tags: List<TagBinding>, onEditLabel: (TagId, String?) -> Unit) {
    SectionHeader(title = "Tags")
    if (tags.isEmpty()) {
        QuietLine("No tag yet · Write tag to add one")
        return
    }
    var editing by remember { mutableStateOf<TagBinding?>(null) }
    LedgerList(count = tags.size) { index ->
        val tag = tags[index]
        val stamped = tag.writtenAt ?: tag.createdAt
        val (day, month, year) = stamped.asLedgerDate()
        // Review fix round 1, nit 7: a trailing edit glyph is the tappable row's own affordance —
        // reusing the shipped word "Edit" already used for the asset-level action above, not a
        // new string.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { editing = tag },
        ) {
            Column(modifier = Modifier.weight(1f)) {
                LedgerEntry(
                    day = day,
                    month = month,
                    year = year,
                    title = if (tag.writtenAt != null) "Tag written" else "Tag bound",
                    detail = tag.identityLine(),
                    badge = if (tag.status != TagStatus.ACTIVE) {
                        {
                            StatusBadge(
                                label = tag.status.name.lowercase().replaceFirstChar { it.uppercase() },
                                colors = ServiceTagTheme.semanticColors.seasonInactive,
                            )
                        }
                    } else {
                        null
                    },
                )
                tag.placementOrNull()?.let { placement ->
                    TagPlacementCaption(placement, modifier = Modifier.padding(start = LedgerDateColumnWidth, bottom = 8.dp))
                }
            }
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    editing?.let { tag ->
        TagPlacementDialog(
            tag = tag,
            onDismiss = { editing = null },
            onSave = { value -> onEditLabel(tag.id, value); editing = null },
        )
    }
}

/** The ratified "Tag placement" caption over the value, indented under the ledger's date column. */
@Composable
private fun TagPlacementCaption(value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Tag placement",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The one place a bound tag's placement is edited (#49 AC 4, invariant 59): this writes only
 * `label` through [AssetDetailViewModel.editTagLabel] — no NFC payload, no binding field.
 */
@Composable
private fun TagPlacementDialog(tag: TagBinding, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember(tag.id) { mutableStateOf(tag.label.orEmpty()) }
    // Review fix round 1, nit 6: the ratified caption appears once, as the dialog's title — the
    // field itself carries no second "Tag placement" label.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag placement") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = ControlShape,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NotesSection(notes: String) {
    SectionHeader(title = "Notes")
    if (notes.isBlank()) {
        QuietLine("No notes")
    } else {
        Text(
            text = notes,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Broad categories only (D12 §12): a keyword in the category the user typed picks one of a handful
 * of glyphs. There is deliberately no per-brand icon — the asset's name supplies that specificity.
 */
@Composable
private fun categoryIcon(category: String): ImageVector {
    val text = category.lowercase()
    fun any(vararg words: String) = words.any { it in text }
    return when {
        any("tool", "equip", "mower", "machine", "engine", "pump", "hvac") -> Icons.Outlined.Build
        any("power", "battery", "electric", "ups", "meter", "gauge") -> ServiceTagIcons.Speed
        any("computer", "network", "server", "nfc", "tag") -> ServiceTagIcons.NfcTag
        any("home", "house", "water", "pool", "tub", "yard", "garden", "outdoor") -> Icons.Outlined.Home
        else -> Icons.Outlined.Info
    }
}

private val plateDate = DateTimeFormatter.ofPattern("d MMM uuuu")
private val monthDay = DateTimeFormatter.ofPattern("d MMM")
private val ledgerDay = DateTimeFormatter.ofPattern("dd")
private val ledgerMonth = DateTimeFormatter.ofPattern("MMM")
private val ledgerYear = DateTimeFormatter.ofPattern("uuuu")

private fun Long.zoned() = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

/** An ISO date as the plate and DETAILS show it. A string the domain would refuse shows verbatim. */
private fun String.asDayDate(): String =
    runCatching { LocalDate.parse(this).format(plateDate) }.getOrDefault(this)

/** The ledger's 64dp date column wants the three parts apart, not one formatted string. */
private fun Long.asLedgerDate(): Triple<String, String, String> {
    val at = zoned()
    return Triple(at.format(ledgerDay), at.format(ledgerMonth), at.format(ledgerYear))
}

/**
 * An event is dated by the calendar day it happened on, not by when the row was written, so the
 * ledger splits `occurredOn` rather than a millisecond instant. A string the domain would have
 * refused is shown verbatim rather than dropped.
 */
private fun String.asLedgerDate(): Triple<String, String, String> {
    val date = runCatching { LocalDate.parse(this) }.getOrNull()
        ?: return Triple(this, "", "")
    return Triple(date.format(ledgerDay), date.format(ledgerMonth), date.format(ledgerYear))
}
