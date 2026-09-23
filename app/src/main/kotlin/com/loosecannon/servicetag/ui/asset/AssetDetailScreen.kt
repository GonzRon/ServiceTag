package com.loosecannon.servicetag.ui.asset

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.model.isRetired
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
import com.loosecannon.servicetag.ui.components.StatusBadge
import com.loosecannon.servicetag.ui.components.TypedConfirmDialog
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
        )
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
                val activity = LocalActivity.current
                ReferencesSection(
                    assetId = current.asset.id,
                    graph = graph,
                    snackbars = snackbars,
                    onOpen = { uri -> activity?.let { LinkLauncher.open(it, uri) } == true },
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
) {
    when (prompt) {
        null -> Unit
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
        badges = plateBadges(asset, state.outOfSeason),
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
 * Retired, archived and out of season are three independent facts and an asset can carry all three
 * (spec §6, §7). Each gets its own D12 §5 family, wording and glyph; an ordinary asset gets no
 * badge slot at all, because "normal" needs no badge.
 */
@Composable
private fun plateBadges(asset: Asset, outOfSeason: Boolean): (@Composable FlowRowScope.() -> Unit)? {
    val archived = statusLabel(asset.status)
    if (!asset.isRetired && archived == null && !outOfSeason) return null
    val semantic = ServiceTagTheme.semanticColors
    val retiredIcon = ServiceTagIcons.PauseCircle
    val seasonIcon = ServiceTagIcons.CalendarMonth
    return {
        if (asset.isRetired) {
            StatusBadge(label = RETIRED, colors = semantic.paused, icon = retiredIcon)
        }
        if (archived != null) {
            StatusBadge(label = archived, colors = semantic.seasonInactive)
        }
        if (outOfSeason) {
            StatusBadge(label = OUT_OF_SEASON, colors = semantic.seasonInactive, icon = seasonIcon)
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
            }
        }
        TextButton(onClick = onAddComponent) { Text("+ Add component") }
    }
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
