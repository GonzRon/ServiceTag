package com.loosecannon.servicetag.api

import com.loosecannon.servicetag.core.backup.AssetDto
import com.loosecannon.servicetag.core.backup.AssetEventDto
import com.loosecannon.servicetag.core.backup.EventProfileDto
import com.loosecannon.servicetag.core.backup.MeasurementDefinitionDto
import com.loosecannon.servicetag.core.backup.NfcTagDto
import com.loosecannon.servicetag.core.merge.DuplicateCandidate
import com.loosecannon.servicetag.core.merge.MergeDecision
import com.loosecannon.servicetag.core.merge.MergeReport
import com.loosecannon.servicetag.core.merge.MergeTally
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.usecase.AssetCommand
import com.loosecannon.servicetag.core.usecase.ConsumableInput
import com.loosecannon.servicetag.core.usecase.DefinitionCommand
import com.loosecannon.servicetag.core.usecase.EventCommand
import com.loosecannon.servicetag.core.usecase.ProfileCommand
import com.loosecannon.servicetag.core.usecase.ProfileConsumableInput
import com.loosecannon.servicetag.core.usecase.ProfileFieldInput
import kotlinx.serialization.Serializable

/*
 * The shapes on the wire.
 *
 * **Responses reuse the backup format's own row DTOs** — `AssetDto`, `NfcTagDto`,
 * `MeasurementDefinitionDto`, `EventProfileDto`, `AssetEventDto`, all `@Serializable` in
 * `com.loosecannon.servicetag.core.backup` — so a row read here and the same row inside
 * `data.json` are the same JSON object, produced by the same `toDto()`. One schema, not two.
 *
 * **Requests are declared here and nowhere else**, with plain `String`/`Int`/`Double?` fields.
 * The domain commands hold value classes and enums; making them `@Serializable` would put a wire
 * concern into `:core`'s vocabulary and pin the JSON to Kotlin's value-class encoding. Each request
 * converts in exactly one place, below.
 */

// --- responses ----------------------------------------------------------------------------------

@Serializable
internal data class StatusResponse(
    val appVersion: String,
    val apiVersion: Int,
    val schemaVersion: Int,
    val backupFormatVersion: Int,
    /**
     * One key per table — assets, **groups**, definitions, profiles, **schedules**, **closures**,
     * links, tags, events, attachments, **references**, **seasonActivations**, **assetConditions**,
     * **healthSubjects**, **assetCategories** — listed here in `MergeTable`'s order for reading, which
     * is **not** the JSON's key order and is not contract; a client reads by key. (The sentence claimed
     * that order before 1.3 and the list was not in it: `tags` and `links` sat ahead of `definitions`
     * and `profiles`. Only the prose moved.)
     *
     * Three of those in bold arrived with 1.2's maintenance tables; **references** arrived with
     * 1.3's `asset_reference`, and its key is `assetReferences` — the name the archive's own table
     * carries; three arrived with 1.4, under the archive's own list names; and **assetCategories**
     * with #74 (format 9) — the owner's own categories, never the compiled built-ins. `schedule_state`
     * and `schedule_local_delivery` are **not** here, because derived and device-local rows are not
     * tables a client counts, and no health value is here because none is stored anywhere.
     */
    val counts: Map<String, Int>,
)

/**
 * The dashboard's shape (spec §9): the systems, then each system's components under its own id. A
 * component appears under its parent and never at the top, and both lists are by name,
 * case-insensitively — the order `AssetTree.children` already defines.
 */
@Serializable
internal data class AssetListResponse(
    val topLevel: List<AssetDto>,
    val components: Map<String, List<AssetDto>>,
)

@Serializable
internal data class AssetResponse(val asset: AssetDto)

@Serializable
internal data class DefinitionListResponse(val definitions: List<MeasurementDefinitionDto>)

@Serializable
internal data class DefinitionResponse(val definition: MeasurementDefinitionDto)

@Serializable
internal data class ProfileListResponse(val profiles: List<EventProfileDto>)

@Serializable
internal data class ProfileResponse(val profile: EventProfileDto)

@Serializable
internal data class EventListResponse(val events: List<AssetEventDto>)

@Serializable
internal data class EventResponse(val event: AssetEventDto)

@Serializable
internal data class TagListResponse(val tags: List<NfcTagDto>)

/*
 * The merge report, on the wire. It is a 1:1 mirror of Task 1's `MergeReport` rather than that
 * class made `@Serializable`, for the same reason the request shapes are declared here: a plan is a
 * domain answer and its JSON is a wire concern, and `:core` should not gain an annotation because
 * `:app` grew a socket. The enums travel as their names, which are the stable codes a client
 * branches on.
 */

@Serializable
internal data class MergeTallyDto(
    val insert: Int,
    val identical: Int,
    val conflict: Int,
    val skipped: Int,
)

/** One row's outcome. `reason` is a `MergeReason` name; `detail` is an id or a key, never a label. */
@Serializable
internal data class MergeDecisionDto(
    val table: String,
    val id: String,
    val verdict: String,
    val reason: String,
    val detail: String,
)

/** A review hint. It never makes `applicable` false and never causes anything to happen. */
@Serializable
internal data class DuplicateCandidateDto(
    val incomingAssetId: String,
    val localAssetId: String,
    val hint: String,
)

/**
 * The body of `POST /v1/import-merge/plan` and of `POST /v1/import-merge/apply` — the same shape
 * on a 200 and on a 409, so a client parses one thing and reads [applicable] to know what happened.
 */
@Serializable
internal data class MergeReportResponse(
    val formatVersion: Int,
    val backupSetId: String,
    val applicable: Boolean,
    // `MergeTable`'s own order, which is also the conflict sort key and — for the first fourteen —
    // the write order: a group's members reference assets, a schedule references an asset or a
    // group, a closure references a schedule, a reference an asset; an activation and a condition
    // reference an asset, and a health subject an asset and, softly, a schedule. #74's `categories`
    // is last here, as in the enum, though a merge writes categories **first** (`MergeWrites`).
    // Fifteen tables since format 9.
    val assets: MergeTallyDto,
    val groups: MergeTallyDto,
    val definitions: MergeTallyDto,
    val profiles: MergeTallyDto,
    val schedules: MergeTallyDto,
    val closures: MergeTallyDto,
    val links: MergeTallyDto,
    val tags: MergeTallyDto,
    val events: MergeTallyDto,
    val attachments: MergeTallyDto,
    val references: MergeTallyDto,
    val seasonActivations: MergeTallyDto,
    val conditions: MergeTallyDto,
    val healthSubjects: MergeTallyDto,
    /** #74 — the archive's rows and the synthesised rows its accepted assets need (C13). */
    val categories: MergeTallyDto,
    /** Deterministic: table order, then id. Empty when [applicable]. */
    val conflicts: List<MergeDecisionDto>,
    val duplicateCandidates: List<DuplicateCandidateDto>,
)

private fun MergeTally.dto() = MergeTallyDto(insert, identical, conflict, skipped)

private fun MergeDecision.dto() =
    MergeDecisionDto(table.name, id, verdict.name, reason.name, detail)

private fun DuplicateCandidate.dto() =
    DuplicateCandidateDto(incomingAssetId, localAssetId, hint.name)

internal fun MergeReport.toResponse() = MergeReportResponse(
    formatVersion = formatVersion,
    backupSetId = backupSetId,
    applicable = applicable,
    assets = assets.dto(),
    groups = groups.dto(),
    definitions = definitions.dto(),
    profiles = profiles.dto(),
    schedules = schedules.dto(),
    closures = closures.dto(),
    links = links.dto(),
    tags = tags.dto(),
    events = events.dto(),
    attachments = attachments.dto(),
    references = references.dto(),
    seasonActivations = seasonActivations.dto(),
    conditions = conditions.dto(),
    healthSubjects = healthSubjects.dto(),
    categories = categories.dto(),
    conflicts = conflicts.map { it.dto() },
    duplicateCandidates = duplicateCandidates.map { it.dto() },
)

// --- requests -----------------------------------------------------------------------------------

/**
 * Everything an asset form can say, as JSON. The field names are `AssetDto`'s, so a row read from
 * the API can be edited and sent back without renaming anything.
 *
 * [templateKey] is read on a create and **ignored on a `PATCH`**, which mirrors the domain exactly:
 * `AssetCommand` deliberately has no `templateKey`, because an edit must not re-seed an asset
 * (`AssetCommands.kt:12`–`19`).
 */
@Serializable
internal data class AssetCommandRequest(
    val name: String,
    val category: String = "",
    val description: String = "",
    val notes: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val serialNumber: String = "",
    val purchaseOn: String? = null,
    val inServiceOn: String? = null,
    val purchasePriceMinor: Long? = null,
    val currency: String? = null,
    val vendor: String = "",
    val location: String = "",
    val warrantyExpiresOn: String? = null,
    val warrantyNotes: String = "",
    val parentAssetId: String? = null,
    val seasonStartMmdd: String? = null,
    val seasonEndMmdd: String? = null,
    val templateKey: String? = null,
)

internal fun AssetCommandRequest.toCommand(parentOverride: String? = null) = AssetCommand(
    name = name,
    category = category,
    description = description,
    notes = notes,
    manufacturer = manufacturer,
    model = model,
    serialNumber = serialNumber,
    purchaseOn = purchaseOn,
    inServiceOn = inServiceOn,
    purchasePriceMinor = purchasePriceMinor,
    currency = currency,
    vendor = vendor,
    location = location,
    warrantyExpiresOn = warrantyExpiresOn,
    warrantyNotes = warrantyNotes,
    // The path wins on `POST /v1/assets/{id}/components`: the route says whose component this is.
    parentAssetId = (parentOverride ?: parentAssetId)?.let(::AssetId),
    seasonStartMmdd = seasonStartMmdd,
    seasonEndMmdd = seasonEndMmdd,
)

/** `null` un-retires; a date retires. The same shape either way, so one endpoint does both. */
@Serializable
internal data class RetireRequest(val retiredOn: String? = null)

/** `true` archives, `false` unarchives. Shared by assets, definitions and profiles. */
@Serializable
internal data class ArchiveRequest(val archived: Boolean)

/**
 * `SaveDefinition.run(id, cmd)` as one body: [id] null creates, [id] set edits. [key] blank means
 * "generate from the label" on a create and "leave it alone" on an edit — which is the domain's own
 * rule (`DefinitionCommands.kt:15`–`18`), not a new one.
 */
@Serializable
internal data class SaveDefinitionRequest(
    val id: String? = null,
    val assetId: String,
    val key: String = "",
    val label: String,
    val unit: String = "",
    val kind: String = "ENTERED",
    val valueType: String = "NUMBER",
    val decimals: Int = 0,
    val rangeLow: Double? = null,
    val rangeHigh: Double? = null,
    val isMeter: Boolean = false,
    val formula: String? = null,
    val sourceAId: String? = null,
    val sourceBId: String? = null,
)

internal fun SaveDefinitionRequest.toCommand() = DefinitionCommand(
    assetId = AssetId(assetId),
    key = key,
    label = label,
    unit = unit,
    kind = enumOr400<DefinitionKind>(kind, "kind"),
    valueType = enumOr400<ValueType>(valueType, "valueType"),
    decimals = decimals,
    rangeLow = rangeLow,
    rangeHigh = rangeHigh,
    isMeter = isMeter,
    formula = formula?.let { enumOr400<DerivedFormula>(it, "formula") },
    // Review S3: named, not positional — `sourceA`/`sourceB` are adjacent parameters of the
    // identical type `DefinitionId?`, so a reorder in `:core` would have compiled and silently
    // swapped them.
    sourceA = sourceAId?.let(::DefinitionId),
    sourceB = sourceBId?.let(::DefinitionId),
)

@Serializable
internal data class ProfileFieldRequest(val definitionId: String, val required: Boolean = false)

@Serializable
internal data class ProfileConsumableRequest(
    val id: String? = null,
    val name: String,
    val defaultQuantity: Double? = null,
    val unit: String = "",
)

/** `SaveProfile.run(id, cmd)` as one body: [id] null creates, [id] set edits. */
@Serializable
internal data class SaveProfileRequest(
    val id: String? = null,
    val assetId: String,
    val name: String,
    val eventKind: String,
    val defaultTitle: String = "",
    val fields: List<ProfileFieldRequest> = emptyList(),
    val consumables: List<ProfileConsumableRequest> = emptyList(),
)

internal fun SaveProfileRequest.toCommand() = ProfileCommand(
    assetId = AssetId(assetId),
    name = name,
    eventKind = enumOr400<EventKind>(eventKind, "eventKind"),
    defaultTitle = defaultTitle,
    fields = fields.map { ProfileFieldInput(DefinitionId(it.definitionId), it.required) },
    consumables = consumables.map {
        ProfileConsumableInput(it.id, it.name, it.defaultQuantity, it.unit)
    },
)

/** A consumable line exactly as the entry form sends one: the quantity is text until validated. */
@Serializable
internal data class ConsumableRequest(
    val name: String,
    val quantity: String,
    val unit: String = "",
)

/**
 * One event, logged or edited. [values] is keyed by definition id, the text a person would type —
 * `EventCommand.values` is a `Map<DefinitionId, String>` for exactly that reason
 * (`EventCommands.kt:26`–`30`), and "absent" and "blank" both mean no value.
 */
@Serializable
internal data class EventRequest(
    val assetId: String,
    val profileId: String? = null,
    val kind: String,
    val title: String = "",
    val occurredOn: String,
    val occurredTime: String? = null,
    val tzId: String,
    val notes: String = "",
    val values: Map<String, String> = emptyMap(),
    val consumables: List<ConsumableRequest> = emptyList(),
)

internal fun EventRequest.toCommand() = EventCommand(
    assetId = AssetId(assetId),
    profileId = profileId?.let(::ProfileId),
    kind = enumOr400<EventKind>(kind, "kind"),
    title = title,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    notes = notes,
    values = values.mapKeys { (id, _) -> DefinitionId(id) },
    consumables = consumables.map { ConsumableInput(it.name, it.quantity, it.unit) },
)

/**
 * An enum by name, or a 400 naming the field and every name it would have accepted. `:core`'s own
 * reader does the same thing for a backup (`BackupFormat.kt:247`–`249`), with `BackupCorrupt` in
 * place of a status code.
 */
internal inline fun <reified E : Enum<E>> enumOr400(name: String, field: String): E =
    enumValues<E>().firstOrNull { it.name == name }
        ?: throw ApiFailure.badRequest(
            "$field must be one of ${enumValues<E>().joinToString(", ") { it.name }}, not \"$name\"",
        )
