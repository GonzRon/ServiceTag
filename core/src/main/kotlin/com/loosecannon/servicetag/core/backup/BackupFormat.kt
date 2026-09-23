package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentMode
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.ConsumableUsage
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.DerivedSpec
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleProviderRow
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.SeasonBehavior
import com.loosecannon.servicetag.core.model.StorageProvider
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.ValueType
import kotlinx.serialization.Serializable

/**
 * What the backup says about itself. `dataSha256` is the hex SHA-256 of the exact
 * `data.json` bytes in the same archive, so a truncated or edited file is caught on read.
 *
 * `formatVersion` keeps its name and becomes 5 (spec §11.4 — D7's `dataFormatVersion` is this
 * field; renaming it would break the branch-on-version reader). The four new fields carry
 * defaults so a format ≤4 manifest still decodes.
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val counts: Map<String, Int>,
    val dataSha256: String,
    /** Ties this data archive to its artifacts archive. Empty only on a format ≤4 file. */
    val backupSetId: String = "",
    val artifactFormatVersion: Int = ArtifactsCodec.ARTIFACT_FORMAT_VERSION,
    val artifactCount: Int = 0,
    val artifactBytes: Long = 0L,
)

@Serializable
data class AssetDto(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val notes: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val templateKey: String? = null,
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
    val retiredOn: String? = null,
    val parentAssetId: String? = null,
    val seasonStartMmdd: String? = null,
    val seasonEndMmdd: String? = null,
)

@Serializable
data class NfcTagDto(
    val id: String,
    val payloadFormat: String,
    val payloadKey: String,
    val assetId: String?,
    val linkId: String?,
    val status: String,
    val label: String?,
    val physicalUid: String?,
    val writtenAt: Long?,
    val lastScannedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ExternalLinkDto(
    val id: String,
    val assetId: String?,
    val kind: String,
    val label: String,
    val uri: String,
    val createdAt: Long,
    val lastOpenedAt: Long?,
    val updatedAt: Long,
)

@Serializable
data class MeasurementDefinitionDto(
    val id: String,
    val assetId: String,
    val key: String,
    val label: String,
    val unit: String,
    val valueType: String,
    val decimals: Int,
    val rangeLow: Double?,
    val rangeHigh: Double?,
    val isMeter: Boolean,
    val sortOrder: Int,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val kind: String = "ENTERED",
    val formula: String? = null,
    val sourceAId: String? = null,
    val sourceBId: String? = null,
)

@Serializable
data class ProfileFieldDto(
    val id: String,
    val definitionId: String,
    val required: Boolean,
    val sortOrder: Int,
)

@Serializable
data class ProfileConsumableDto(
    val id: String,
    val name: String,
    val defaultQuantity: Double?,
    val unit: String,
    val sortOrder: Int,
)

@Serializable
data class EventProfileDto(
    val id: String,
    val assetId: String,
    val name: String,
    val eventKind: String,
    val defaultTitle: String,
    val templateKey: String?,
    val sortOrder: Int,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val fields: List<ProfileFieldDto>,
    val consumables: List<ProfileConsumableDto>,
)

@Serializable
data class MeasurementDto(
    val id: String,
    val definitionId: String,
    val valueNum: Double?,
    val valueText: String?,
    val unit: String,
    val sortOrder: Int,
)

@Serializable
data class ConsumableUsageDto(
    val id: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val sortOrder: Int,
)

@Serializable
data class AssetEventDto(
    val id: String,
    val assetId: String,
    val kind: String,
    val title: String,
    val profileId: String?,
    val occurredOn: String,
    val occurredTime: String?,
    val tzId: String,
    val notes: String,
    val source: String,
    val sourceRef: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val measurements: List<MeasurementDto>,
    val consumables: List<ConsumableUsageDto>,
    /** Format 6. The schedule this event completed an occurrence of, if any. */
    val scheduleId: String? = null,
    /** Format 6. The occurrence key, immutable once written. Null on every non-completion. */
    val occurrenceOn: String? = null,
    /** Format 6. A minimal completion against a FORM schedule still owes its details. */
    val detailsPending: Boolean = false,
)

/**
 * A membership window. Carries no `groupId`: a child row's owner is its position in the tree,
 * exactly as [ProfileFieldDto] carries no `profileId`.
 */
@Serializable
data class GroupMemberDto(
    val id: String,
    val assetId: String,
    val sortOrder: Int,
    val addedAt: Long,
    val removedAt: Long?,
)

/** Format 6. Members travel inside, as a profile's fields do. */
@Serializable
data class MaintenanceGroupDto(
    val id: String,
    val name: String,
    val description: String,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val members: List<GroupMemberDto>,
)

/** One enabled-provider row. Carries no `scheduleId`, for [GroupMemberDto]'s reason. */
@Serializable
data class ScheduleProviderDto(
    val provider: String,
    val enabled: Boolean,
)

/**
 * Format 6. Exactly one of [assetId] and [groupId] is set — a rule `toDomain` enforces, since
 * neither Room nor the wire format can.
 */
@Serializable
data class MaintenanceScheduleDto(
    val id: String,
    val assetId: String?,
    val groupId: String?,
    val title: String,
    val description: String,
    val timeInterval: Int?,
    val timeUnit: String?,
    val timeBasis: String,
    val anchorOn: String?,
    val leadDays: Int,
    val meterDefinitionId: String?,
    val meterInterval: Double?,
    val anchorMeter: Double?,
    val meterLead: Double?,
    val seasonBehavior: String,
    val seasonReentry: String?,
    val seasonReentryOffsetDays: Int?,
    val completionMode: String,
    val profileId: String?,
    val remindersEnabled: Boolean,
    val status: String,
    val postponedDueOn: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val providers: List<ScheduleProviderDto>,
)

/**
 * Format 6. Its own row, **never nested inside the schedule**: closing a round would otherwise
 * change the schedule's exported content and every later re-import of an unchanged schedule would
 * be `CONTENT_DIFFERS`. Five fields, with no `updatedAt`, mirroring the immutable row.
 */
@Serializable
data class OccurrenceClosureDto(
    val id: String,
    val scheduleId: String,
    val occurrenceOn: String,
    val closedOn: String,
    val createdAt: Long,
)

/** Owner is `assetId` xor `eventId`; there is no SQL CHECK, so the readers are the rule (§11.5). */
@Serializable
data class AttachmentDto(
    val id: String,
    val assetId: String?,
    val eventId: String?,
    val kind: String,
    val mode: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val storageProvider: String,
    val storageLocator: String,
    val capturedOn: String?,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Format 7. The `asset_reference` table's nine columns, in column order — **no `provenance`**
 * (D-21 C), and no byte-bearing field of any kind, because a reference has none (I-3).
 */
@Serializable
data class AssetReferenceDto(
    val id: String,
    val assetId: String,
    val kind: String,
    val uri: String,
    val displayName: String,
    val description: String,
    val scheme: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The canonical tables. Everything derived is rebuilt after an import. */
@Serializable
data class BackupData(
    val assets: List<AssetDto>,
    val nfcTags: List<NfcTagDto>,
    val externalLinks: List<ExternalLinkDto>,
    val measurementDefinitions: List<MeasurementDefinitionDto> = emptyList(),
    val eventProfiles: List<EventProfileDto> = emptyList(),
    val assetEvents: List<AssetEventDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
    /** Format 6; members travel inside. Empty on every format ≤5 archive. */
    val maintenanceGroups: List<MaintenanceGroupDto> = emptyList(),
    /** Format 6; providers travel inside. Empty on every format ≤5 archive. */
    val maintenanceSchedules: List<MaintenanceScheduleDto> = emptyList(),
    /** Format 6; their own rows, never nested. Empty on every format ≤5 archive. */
    val occurrenceClosures: List<OccurrenceClosureDto> = emptyList(),
    /** Format 7; their own rows, never nested. Empty on every format ≤6 archive. */
    val assetReferences: List<AssetReferenceDto> = emptyList(),
)

/** A decoded archive: what it claims about itself, and what it holds. */
data class Backup(val manifest: BackupManifest, val data: BackupData)

// --- mapping ------------------------------------------------------------------------------------

private inline fun <reified E : Enum<E>> enumOrCorrupt(name: String, field: String, owner: String): E =
    enumValues<E>().firstOrNull { it.name == name }
        ?: throw BackupCorrupt("unknown $field \"$name\" on $owner")

fun Asset.toDto(): AssetDto = AssetDto(
    id = id.value,
    name = name,
    description = description,
    category = category,
    notes = notes,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    templateKey = templateKey,
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
    retiredOn = retiredOn,
    parentAssetId = parentAssetId?.value,
    seasonStartMmdd = seasonStartMmdd,
    seasonEndMmdd = seasonEndMmdd,
)

fun AssetDto.toDomain(): Asset = Asset(
    id = AssetId(id),
    name = name,
    description = description,
    category = category,
    notes = notes,
    status = enumOrCorrupt<AssetStatus>(status, "asset status", "asset $id"),
    templateKey = templateKey,
    createdAt = createdAt,
    updatedAt = updatedAt,
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
    retiredOn = retiredOn,
    parentAssetId = parentAssetId?.let(::AssetId),
    seasonStartMmdd = seasonStartMmdd,
    seasonEndMmdd = seasonEndMmdd,
)

fun TagBinding.toDto(): NfcTagDto = NfcTagDto(
    id = id.value,
    payloadFormat = payloadFormat.name,
    payloadKey = payloadKey,
    assetId = (target as? TagTarget.AssetTarget)?.assetId?.value,
    linkId = (target as? TagTarget.LinkTarget)?.linkId?.value,
    status = status.name,
    label = label,
    physicalUid = physicalUid,
    writtenAt = writtenAt,
    lastScannedAt = lastScannedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun NfcTagDto.toDomain(): TagBinding {
    if (assetId != null && linkId != null) {
        throw BackupCorrupt("tag $id points at both an asset and a link")
    }
    val target = when {
        assetId != null -> TagTarget.AssetTarget(AssetId(assetId))
        linkId != null -> TagTarget.LinkTarget(LinkId(linkId))
        else -> TagTarget.None
    }
    return TagBinding(
        id = TagId(id),
        payloadFormat = enumOrCorrupt<PayloadFormat>(payloadFormat, "payload format", "tag $id"),
        payloadKey = payloadKey,
        target = target,
        status = enumOrCorrupt<TagStatus>(status, "tag status", "tag $id"),
        label = label,
        physicalUid = physicalUid,
        writtenAt = writtenAt,
        lastScannedAt = lastScannedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun ExternalLink.toDto(): ExternalLinkDto = ExternalLinkDto(
    id = id.value,
    assetId = assetId?.value,
    kind = kind.name,
    label = label,
    uri = uri,
    createdAt = createdAt,
    lastOpenedAt = lastOpenedAt,
    updatedAt = updatedAt,
)

fun ExternalLinkDto.toDomain(): ExternalLink = ExternalLink(
    id = LinkId(id),
    assetId = assetId?.let(::AssetId),
    kind = enumOrCorrupt<LinkKind>(kind, "link kind", "link $id"),
    label = label,
    uri = uri,
    createdAt = createdAt,
    lastOpenedAt = lastOpenedAt,
    updatedAt = updatedAt,
)

fun MeasurementDefinition.toDto(): MeasurementDefinitionDto = MeasurementDefinitionDto(
    id = id.value,
    assetId = assetId.value,
    key = key,
    label = label,
    unit = unit,
    valueType = valueType.name,
    decimals = decimals,
    rangeLow = rangeLow,
    rangeHigh = rangeHigh,
    isMeter = isMeter,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    kind = kind.name,
    formula = derived?.formula?.name,
    sourceAId = derived?.sourceA?.value,
    sourceBId = derived?.sourceB?.value,
)

fun MeasurementDefinitionDto.toDomain(): MeasurementDefinition {
    val definitionKind = enumOrCorrupt<DefinitionKind>(kind, "definition kind", "definition $id")
    val derived = when (definitionKind) {
        DefinitionKind.ENTERED -> {
            if (formula != null || sourceAId != null || sourceBId != null) {
                throw BackupCorrupt("definition $id is ENTERED but carries a derived formula or sources")
            }
            null
        }
        DefinitionKind.DERIVED -> {
            val formulaName = formula
                ?: throw BackupCorrupt("definition $id is DERIVED but has no formula")
            val sourceA = sourceAId
                ?: throw BackupCorrupt("definition $id is DERIVED but has no sourceAId")
            val sourceB = sourceBId
                ?: throw BackupCorrupt("definition $id is DERIVED but has no sourceBId")
            DerivedSpec(
                formula = enumOrCorrupt<DerivedFormula>(formulaName, "derived formula", "definition $id"),
                sourceA = DefinitionId(sourceA),
                sourceB = DefinitionId(sourceB),
            )
        }
    }
    return MeasurementDefinition(
        id = DefinitionId(id),
        assetId = AssetId(assetId),
        key = key,
        label = label,
        unit = unit,
        valueType = enumOrCorrupt<ValueType>(valueType, "value type", "definition $id"),
        decimals = decimals,
        rangeLow = rangeLow,
        rangeHigh = rangeHigh,
        isMeter = isMeter,
        sortOrder = sortOrder,
        archivedAt = archivedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        kind = definitionKind,
        derived = derived,
    )
}

fun ProfileField.toDto(): ProfileFieldDto = ProfileFieldDto(
    id = id,
    definitionId = definitionId.value,
    required = required,
    sortOrder = sortOrder,
)

fun ProfileFieldDto.toDomain(): ProfileField = ProfileField(
    id = id,
    definitionId = DefinitionId(definitionId),
    required = required,
    sortOrder = sortOrder,
)

fun ProfileConsumable.toDto(): ProfileConsumableDto = ProfileConsumableDto(
    id = id,
    name = name,
    defaultQuantity = defaultQuantity,
    unit = unit,
    sortOrder = sortOrder,
)

fun ProfileConsumableDto.toDomain(): ProfileConsumable = ProfileConsumable(
    id = id,
    name = name,
    defaultQuantity = defaultQuantity,
    unit = unit,
    sortOrder = sortOrder,
)

fun EventProfile.toDto(): EventProfileDto = EventProfileDto(
    id = id.value,
    assetId = assetId.value,
    name = name,
    eventKind = eventKind.name,
    defaultTitle = defaultTitle,
    templateKey = templateKey,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    fields = fields.map { it.toDto() },
    consumables = consumables.map { it.toDto() },
)

fun EventProfileDto.toDomain(): EventProfile = EventProfile(
    id = ProfileId(id),
    assetId = AssetId(assetId),
    name = name,
    eventKind = enumOrCorrupt<EventKind>(eventKind, "event kind", "profile $id"),
    defaultTitle = defaultTitle,
    templateKey = templateKey,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    fields = fields.map { it.toDomain() },
    consumables = consumables.map { it.toDomain() },
)

fun Measurement.toDto(): MeasurementDto = MeasurementDto(
    id = id,
    definitionId = definitionId.value,
    valueNum = valueNum,
    valueText = valueText,
    unit = unit,
    sortOrder = sortOrder,
)

fun MeasurementDto.toDomain(): Measurement = Measurement(
    id = id,
    definitionId = DefinitionId(definitionId),
    valueNum = valueNum,
    valueText = valueText,
    unit = unit,
    sortOrder = sortOrder,
)

fun ConsumableUsage.toDto(): ConsumableUsageDto = ConsumableUsageDto(
    id = id,
    name = name,
    quantity = quantity,
    unit = unit,
    sortOrder = sortOrder,
)

fun ConsumableUsageDto.toDomain(): ConsumableUsage = ConsumableUsage(
    id = id,
    name = name,
    quantity = quantity,
    unit = unit,
    sortOrder = sortOrder,
)

fun AssetEvent.toDto(): AssetEventDto = AssetEventDto(
    id = id.value,
    assetId = assetId.value,
    kind = kind.name,
    title = title,
    profileId = profileId?.value,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    notes = notes,
    source = source.name,
    sourceRef = sourceRef,
    createdAt = createdAt,
    updatedAt = updatedAt,
    measurements = measurements.map { it.toDto() },
    consumables = consumables.map { it.toDto() },
    scheduleId = scheduleId?.value,
    occurrenceOn = occurrenceOn,
    detailsPending = detailsPending,
)

fun AssetEventDto.toDomain(): AssetEvent = AssetEvent(
    id = EventId(id),
    assetId = AssetId(assetId),
    kind = enumOrCorrupt<EventKind>(kind, "event kind", "event $id"),
    title = title,
    profileId = profileId?.let(::ProfileId),
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    notes = notes,
    source = enumOrCorrupt<EventSource>(source, "event source", "event $id"),
    sourceRef = sourceRef,
    createdAt = createdAt,
    updatedAt = updatedAt,
    measurements = measurements.map { it.toDomain() },
    consumables = consumables.map { it.toDomain() },
    scheduleId = scheduleId?.let(::ScheduleId),
    occurrenceOn = occurrenceOn,
    detailsPending = detailsPending,
)

fun Attachment.toDto(): AttachmentDto = AttachmentDto(
    id = id.value,
    assetId = (owner as? AttachmentOwner.OfAsset)?.assetId?.value,
    eventId = (owner as? AttachmentOwner.OfEvent)?.eventId?.value,
    kind = kind.name,
    mode = mode.name,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    storageProvider = storageProvider.name,
    storageLocator = storageLocator,
    capturedOn = capturedOn,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AttachmentDto.toDomain(): Attachment {
    if ((assetId == null) == (eventId == null)) {
        throw BackupCorrupt("attachment $id must name exactly one owner, an asset or an event")
    }
    return Attachment(
        id = AttachmentId(id),
        owner = assetId?.let { AttachmentOwner.OfAsset(AssetId(it)) }
            ?: AttachmentOwner.OfEvent(EventId(eventId!!)),
        kind = enumOrCorrupt<AttachmentKind>(kind, "attachment kind", "attachment $id"),
        mode = enumOrCorrupt<AttachmentMode>(mode, "attachment mode", "attachment $id"),
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        storageProvider = enumOrCorrupt<StorageProvider>(
            storageProvider, "storage provider", "attachment $id",
        ),
        storageLocator = storageLocator,
        capturedOn = capturedOn,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

// --- format 6: groups, schedules and closures ----------------------------------------------------

fun GroupMember.toDto(): GroupMemberDto = GroupMemberDto(
    id = id,
    assetId = assetId.value,
    sortOrder = sortOrder,
    addedAt = addedAt,
    removedAt = removedAt,
)

fun GroupMemberDto.toDomain(): GroupMember = GroupMember(
    id = id,
    assetId = AssetId(assetId),
    sortOrder = sortOrder,
    addedAt = addedAt,
    removedAt = removedAt,
)

fun MaintenanceGroup.toDto(): MaintenanceGroupDto = MaintenanceGroupDto(
    id = id.value,
    name = name,
    description = description,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    members = members.map { it.toDto() },
)

fun MaintenanceGroupDto.toDomain(): MaintenanceGroup = MaintenanceGroup(
    id = GroupId(id),
    name = name,
    description = description,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    members = members.map { it.toDomain() },
)

fun ScheduleProviderRow.toDto(): ScheduleProviderDto = ScheduleProviderDto(
    provider = provider,
    enabled = enabled,
)

fun ScheduleProviderDto.toDomain(): ScheduleProviderRow = ScheduleProviderRow(
    provider = provider,
    enabled = enabled,
)

fun MaintenanceSchedule.toDto(): MaintenanceScheduleDto = MaintenanceScheduleDto(
    id = id.value,
    assetId = (target as? ScheduleTarget.AssetTarget)?.assetId?.value,
    groupId = (target as? ScheduleTarget.GroupTarget)?.groupId?.value,
    title = title,
    description = description,
    timeInterval = timeInterval,
    timeUnit = timeUnit?.name,
    timeBasis = timeBasis.name,
    anchorOn = anchorOn,
    leadDays = leadDays,
    meterDefinitionId = meterDefinitionId?.value,
    meterInterval = meterInterval,
    anchorMeter = anchorMeter,
    meterLead = meterLead,
    seasonBehavior = seasonBehavior.name,
    seasonReentry = seasonReentry,
    seasonReentryOffsetDays = seasonReentryOffsetDays,
    completionMode = completionMode.name,
    profileId = profileId?.value,
    remindersEnabled = remindersEnabled,
    status = status.name,
    postponedDueOn = postponedDueOn,
    createdAt = createdAt,
    updatedAt = updatedAt,
    providers = providers.map { it.toDto() },
)

/**
 * The target is the one field the wire can spell in a way the domain cannot hold, so it is checked
 * here: a row naming both sides, or neither, is refused before any import begins. The merge planner
 * reads the DTO's two columns directly for the same reason — it must be able to *report*
 * `SCHEDULE_TARGET_INVALID` on a row this function would throw on.
 */
fun MaintenanceScheduleDto.toDomain(): MaintenanceSchedule {
    if ((assetId == null) == (groupId == null)) {
        throw BackupCorrupt("schedule $id must name exactly one target, an asset or a group")
    }
    return MaintenanceSchedule(
        id = ScheduleId(id),
        target = assetId?.let { ScheduleTarget.AssetTarget(AssetId(it)) }
            ?: ScheduleTarget.GroupTarget(GroupId(groupId!!)),
        title = title,
        description = description,
        timeInterval = timeInterval,
        timeUnit = timeUnit?.let { enumOrCorrupt<RecurrenceUnit>(it, "time unit", "schedule $id") },
        timeBasis = enumOrCorrupt<TimeBasis>(timeBasis, "time basis", "schedule $id"),
        anchorOn = anchorOn,
        leadDays = leadDays,
        meterDefinitionId = meterDefinitionId?.let(::DefinitionId),
        meterInterval = meterInterval,
        anchorMeter = anchorMeter,
        meterLead = meterLead,
        seasonBehavior = enumOrCorrupt<SeasonBehavior>(seasonBehavior, "season behavior", "schedule $id"),
        seasonReentry = seasonReentry,
        seasonReentryOffsetDays = seasonReentryOffsetDays,
        completionMode = enumOrCorrupt<CompletionMode>(completionMode, "completion mode", "schedule $id"),
        profileId = profileId?.let(::ProfileId),
        remindersEnabled = remindersEnabled,
        status = enumOrCorrupt<ScheduleStatus>(status, "schedule status", "schedule $id"),
        postponedDueOn = postponedDueOn,
        createdAt = createdAt,
        updatedAt = updatedAt,
        providers = providers.map { it.toDomain() },
    )
}

fun OccurrenceClosure.toDto(): OccurrenceClosureDto = OccurrenceClosureDto(
    id = id,
    scheduleId = scheduleId.value,
    occurrenceOn = occurrenceOn,
    closedOn = closedOn,
    createdAt = createdAt,
)

fun OccurrenceClosureDto.toDomain(): OccurrenceClosure = OccurrenceClosure(
    id = id,
    scheduleId = ScheduleId(scheduleId),
    occurrenceOn = occurrenceOn,
    closedOn = closedOn,
    createdAt = createdAt,
)

fun AssetReference.toDto(): AssetReferenceDto = AssetReferenceDto(
    id = id.value,
    assetId = assetId.value,
    kind = kind.name,
    uri = uri,
    displayName = displayName,
    description = description,
    scheme = scheme,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AssetReferenceDto.toDomain(): AssetReference = AssetReference(
    id = ReferenceId(id),
    assetId = AssetId(assetId),
    kind = enumOrCorrupt<ReferenceKind>(kind, "reference kind", "reference $id"),
    uri = uri,
    displayName = displayName,
    description = description,
    scheme = scheme,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
