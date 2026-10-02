package com.loosecannon.servicetag.core.backup

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetCondition
import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetLoanId
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentMode
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.AttachmentSource
import com.loosecannon.servicetag.core.model.attachmentSourceProblem
import com.loosecannon.servicetag.core.model.CaseCoverage
import com.loosecannon.servicetag.core.model.CaseStatus
import com.loosecannon.servicetag.core.model.CaseType
import com.loosecannon.servicetag.core.model.CompletionMode
import com.loosecannon.servicetag.core.model.CompositionEntry
import com.loosecannon.servicetag.core.model.ConsumableUsage
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.DerivedSpec
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthDriver
import com.loosecannon.servicetag.core.model.HealthSubject
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.HealthSubjectKind
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LoanReminderMode
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.OccurrenceClosure
import com.loosecannon.servicetag.core.model.OperationalCondition
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
import com.loosecannon.servicetag.core.model.SeasonAction
import com.loosecannon.servicetag.core.model.SeasonActivation
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServiceCase
import com.loosecannon.servicetag.core.model.ServiceCaseEntry
import com.loosecannon.servicetag.core.model.ServiceCaseEntryId
import com.loosecannon.servicetag.core.model.ServiceCaseId
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.StorageProvider
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.SupplySpecification
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.model.TransferRecord
import com.loosecannon.servicetag.core.model.TimeBasis
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.model.accepts
import com.loosecannon.servicetag.core.references.accepts
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

/**
 * The last five fields are format 8's, and they carry **no defaults**: a format-8 archive that omits
 * one is corrupt. A format ≤7 file never had them; [LegacyArchive] writes them into its tree —
 * CALENDAR exactly when both `MM-DD` bounds are set, no break, `WORST`, no primary — before this
 * DTO decodes it.
 */
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
    /** `YEAR_ROUND`, `CALENDAR` or `MANUAL`; the window above is set exactly when this is CALENDAR. */
    val seasonMode: String,
    /** The maintenance break, `MM-DD`: both set or both null. */
    val blackoutStartMmdd: String?,
    val blackoutEndMmdd: String?,
    /** How this asset composes its subjects' health: configuration, never a value. */
    val healthAggregation: String,
    /** A soft link to the subject `TRACK_ONE` reads: never validated, never a merge owner. */
    val healthPrimarySubjectId: String?,
    /**
     * Format 11 (#79, C18): the warranty reminder's lead in whole days, or null for none — written as
     * `null` when unset, never left out. A format ≤ 10 archive never had the key, so it reads as null,
     * and one that carries a non-null lead is refused. Configuration only: the warranty **status** is
     * derived at read time and never travels.
     */
    val warrantyReminderLeadDays: Int? = null,
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
    /**
     * Format 18 (#15, C8). The SupplyItem this line names, beside its own `name` and `unit` snapshot; written as an
     * explicit null when unlinked, and defaulting to null because a format ≤17 line has no key. It must name a
     * SupplyItem in the file (R15-6), archived or not.
     */
    val supplyId: String? = null,
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
    /** Format 18 (#15, C8). As on [ProfileConsumableDto]: the link beside the snapshot, an explicit null when unlinked. */
    val supplyId: String? = null,
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
 *
 * Format 8 replaced 1.3's three season fields with [servicePolicy] and [policyOffsetDays], and added
 * [ruleChangedAt]; none of the three carries a default, so a format-8 row that omits one is corrupt,
 * and a 1.3 season field in a format-8 row is an unknown key and corrupt too (inv. 124). A format ≤7
 * row reaches this DTO only after [LegacyArchive] has translated its tree.
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
    val servicePolicy: String,
    val policyOffsetDays: Int?,
    val completionMode: String,
    val profileId: String?,
    val remindersEnabled: Boolean,
    val status: String,
    val postponedDueOn: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** The pin's floor: it moves only on a rule change (inv. 87), so it travels as it is. */
    val ruleChangedAt: Long,
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

/**
 * Owner is exactly one of `assetId`, `eventId`, `supplyItemId` and `installedComponentId`; there is no
 * SQL CHECK, so the readers are the rule (§11.5).
 *
 * [role] is format 10's (#67, C4): a `DocumentRole` name or null, written as `"role": null` when
 * unset (the codec encodes defaults). It defaults to null so a format ≤9 archive, which never had
 * the key, still decodes; `BackupCodec` refuses a non-null one in such an archive.
 *
 * The four `source…` fields are format 16's (#85, C4): the attachment's source provenance, written as
 * explicit nulls when unset, like [role]. They default to null so a format ≤15 archive still decodes;
 * `BackupCodec` refuses a non-null one in such an archive, and [toDomain] refuses a malformed set.
 *
 * [supplyItemId] and [installedComponentId] are format 20's (#69, C9), appended last: the SupplyItem or
 * the installed component that owns the file, written as explicit nulls when unset, like [role]. They
 * default to null so a format ≤19 archive, which never had the keys, still decodes; `BackupCodec`
 * refuses a non-null one in such an archive, and [toDomain] refuses a row naming no owner or more than one.
 */
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
    val role: String? = null,
    val sourceUri: String? = null,
    val sourceResolvedUri: String? = null,
    val sourceRetrievedAt: Long? = null,
    val sourceName: String? = null,
    val supplyItemId: String? = null,
    val installedComponentId: String? = null,
)

/**
 * Format 7. The `asset_reference` table's columns, in column order — **no `provenance`**
 * (D-21 C), and no byte-bearing field of any kind, because a reference has none (I-3).
 *
 * [role] is format 17's (#91, C6), appended after the nine shipped columns: a `DocumentRole` name or null,
 * written as `"role": null` when unset (the codec encodes defaults). It defaults to null so a format ≤16
 * archive, which never had the key, still decodes; `BackupCodec` refuses a non-null one in such an archive,
 * and [toDomain] refuses one on a row whose kind takes no role.
 *
 * [supplyItemId] and [installedComponentId] are format 20's (#69, C9), appended after [role]: the SupplyItem
 * or the installed component that owns the link, written as explicit nulls when unset, like [role]. They
 * default to null so a format ≤19 archive, which never had the keys, still decodes; `BackupCodec` refuses a
 * non-null one in such an archive, and [toDomain] refuses a row naming more than one owner. Interim (#69):
 * [assetId] stays required while the domain reference can name only an asset.
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
    val role: String? = null,
    val supplyItemId: String? = null,
    val installedComponentId: String? = null,
)

/**
 * Format 8. One manual season activation: an immutable fact, so there is no `updatedAt`. [eventId]
 * is a soft link — never validated, never a merge owner (inv. 109).
 */
@Serializable
data class SeasonActivationDto(
    val id: String,
    val assetId: String,
    val action: String,
    val occurredOn: String,
    val eventId: String?,
    val createdAt: Long,
)

/**
 * Format 8. One condition fact: immutable, so there is no `updatedAt`. [eventId] is a soft link,
 * as [SeasonActivationDto]'s is. No column anywhere holds the *current* condition.
 */
@Serializable
data class AssetConditionDto(
    val id: String,
    val assetId: String,
    val condition: String,
    val occurredOn: String,
    val occurredTime: String?,
    val tzId: String,
    val reason: String,
    val eventId: String?,
    val createdAt: Long,
)

/**
 * Format 8. One health subject: **configuration only**. No score, band or aggregate travels here or
 * anywhere in the format, because health is computed at read time and never stored (inv. 111).
 * [scheduleId] is a real reference and must resolve inside the file; [baselineProfileId] is a soft
 * link and is never checked.
 */
@Serializable
data class HealthSubjectDto(
    val id: String,
    val assetId: String,
    val name: String,
    val kind: String,
    val driver: String,
    val scheduleId: String?,
    val baselineProfileId: String?,
    val nominalUntilDays: Int,
    val warningFromDays: Int,
    val criticalFromDays: Int,
    val weight: Int,
    val sortOrder: Int,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Format 9 (#74, C11). One of the owner's own Asset categories: the `asset_category` table's four
 * columns, in column order. [key] is the identity — `CategoryKey.of(display)` — and [display] is in
 * `CategoryKey.display` form; the content check holds a row to both. The compiled built-ins are never
 * rows, here or in the table.
 */
@Serializable
data class AssetCategoryDto(
    val key: String,
    val display: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Format 12 (#79, C18; R79-1). One service case's header: the `service_case` table's columns, in column
 * order, with no defaults — a format-12 row that omits one is corrupt. [assetId] is a real reference and
 * must resolve inside the file; [incidentEventId] and [resolutionEventId] are soft links and are never
 * checked (R79-4). [closedOn] is set exactly when [status] is CLOSED or CANCELLED.
 */
@Serializable
data class ServiceCaseDto(
    val id: String,
    val assetId: String,
    val title: String,
    val type: String,
    val openedOn: String,
    val closedOn: String?,
    val provider: String,
    val contact: String,
    val caseRef: String,
    val coverage: String,
    val status: String,
    val outboundTracking: String,
    val outboundCarrier: String,
    val returnTracking: String,
    val returnCarrier: String,
    val costMinor: Long?,
    val currency: String?,
    val notes: String,
    val incidentEventId: String?,
    val resolutionEventId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Format 12 (#79, C18; R79-8). One timeline entry: immutable, so there is no `updatedAt`. Its own row,
 * never nested inside the case — the [OccurrenceClosureDto] precedent — so a new entry never changes the
 * case's exported content and a note added elsewhere re-merges as an INSERT beside an IDENTICAL case.
 * [caseId] must resolve inside the file.
 */
@Serializable
data class ServiceCaseEntryDto(
    val id: String,
    val caseId: String,
    val occurredOn: String,
    val occurredTime: String?,
    val tzId: String,
    val note: String,
    val status: String?,
    val createdAt: Long,
)

/**
 * Format 13 (#72, C6; R72-1, R72-4). One loan: the `asset_loan` table's columns, in column order, with no
 * defaults — a format-13 row that omits one is corrupt — **less the open marker**, which is the Room
 * layer's storage detail and is never exported: open is `returnedOn == null`. [assetId] must resolve
 * inside the file, and an asset holds at most one open loan in it. [contactLookupUri] is the canonical
 * contact link beside the [borrowerName] snapshot; a phone that cannot resolve it still shows the name.
 * A loan's standing (lent out, overdue) is derived at read time and is never here.
 */
@Serializable
data class AssetLoanDto(
    val id: String,
    val assetId: String,
    val borrowerName: String,
    val contactLookupUri: String?,
    val lentOn: String,
    val dueOn: String?,
    val returnedOn: String?,
    val reminderMode: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Format 14 (#77, C7; R77-3, R77-12). One transfer record: the `asset_transfer` table's columns, in column
 * order, with no defaults — a format-14 row that omits one is corrupt. [assetId] is soft: a held asset is
 * never in the archive its own records travel in (the graph check's rule), so it names nothing in the file.
 * [lineage] is the pack ids before [packId], oldest first.
 */
@Serializable
data class TransferRecordDto(
    val id: String,
    val assetId: String,
    val kind: String,
    val packId: String,
    val lineage: List<String>,
    val at: Long,
    val packSha256: String,
    val nameSnapshot: String,
    val note: String,
)

/**
 * Format 15 (#86, C3; R86-1, R86-20). One succession: the `asset_succession` table's columns, in column order, with
 * no defaults — a format-15 row that omits one is corrupt. Both assets must be in the file; an asset is replaced at
 * most once and replaces at most one, and following successors never comes back round (the graph check's, through
 * `successionProblems`). [replacedOn] is an ISO date. No note and no event link (R86-17).
 */
@Serializable
data class AssetSuccessionDto(
    val id: String,
    val predecessorAssetId: String,
    val successorAssetId: String,
    val replacedOn: String,
    val createdAt: Long,
)

/**
 * Format 18 (#15, C8; R15-2). One SupplyItem: the `supply_item` table's columns, in column order, with no defaults —
 * a format-18 row that omits one is corrupt — and its [specifications] nested, as a group's members are. Identity and
 * generic specifications only: no quantity, threshold, price, URL, position or fitting state (the HARD SCOPE).
 */
@Serializable
data class SupplyItemDto(
    val id: String,
    val name: String,
    val category: String,
    val manufacturer: String,
    val model: String,
    val partNumber: String,
    val preferredUnit: String,
    val notes: String,
    val archivedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val specifications: List<SupplySpecificationDto>,
)

/**
 * Format 18 (#15, C8; R15-11). One specification, a child row of its SupplyItem: carries no `supplyId`, its owner is
 * its position in the tree, as [GroupMemberDto] carries no `groupId`. Its id is unique across every SupplyItem in the
 * file; its [key] matches the definition slug rule and is unique within its SupplyItem.
 */
@Serializable
data class SupplySpecificationDto(
    val id: String,
    val key: String,
    val label: String,
    val value: String,
    val unit: String,
    val sortOrder: Int,
)

/**
 * Format 18 (#15, C8; R15-3). One applicability row: [assetId] takes [supplyId] in [role], stored cleaned by
 * `CategoryKey.display`. Both must be in the file, and the triple is unique in it. No notes, no position, no date.
 */
@Serializable
data class AssetSupplyDto(
    val id: String,
    val assetId: String,
    val supplyId: String,
    val role: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Format 19 (#47, C9; R47-2, R47-3, R47-8). One installed component: the `installed_component` table's columns, in
 * column order, with no defaults — a format-19 row that omits one is corrupt — and its [composition] nested, as a
 * SupplyItem's specifications are. [parentId] names a row of the same asset in the file; [replacesId] the removed
 * row of the same asset and the same parent this one replaced; [supplyId] and every entry a SupplyItem in the file,
 * archived or not. Every nullable key is written as an explicit null. The dates are ISO days; a null [installedOn]
 * is a date not recorded and a null [removedOn] a current row.
 */
@Serializable
data class InstalledComponentDto(
    val id: String,
    val assetId: String,
    val parentId: String?,
    val name: String,
    val supplyId: String?,
    val composition: List<CompositionEntryDto>,
    val serialOrLot: String,
    val installedOn: String?,
    val removedOn: String?,
    val replacesId: String?,
    val sortOrder: Int,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Format 19 (#47, C9; R47-17a). One composition entry, a child row of its installed component: carries no component
 * id, as [SupplySpecificationDto] carries no `supplyId` of its owner — its owner is the installed-component row it
 * belongs to, whose `composition` holds it. Its id is unique across every installed component in the file.
 * [quantity] is how many of [supplyId] one unit is made of, in [unit] (`""` for none); nothing is counted, kept or
 * used up by it.
 */
@Serializable
data class CompositionEntryDto(
    val id: String,
    val supplyId: String,
    val quantity: Double,
    val unit: String,
    val sortOrder: Int,
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
    /** Format 8; immutable facts. Empty on every format ≤7 archive. */
    val seasonActivations: List<SeasonActivationDto> = emptyList(),
    /** Format 8; immutable facts. Empty on every format ≤7 archive. */
    val assetConditions: List<AssetConditionDto> = emptyList(),
    /** Format 8; configuration only. Empty on every format ≤7 archive. */
    val healthSubjects: List<HealthSubjectDto> = emptyList(),
    /**
     * Format 9 (#74); the owner's own categories, ordered by key. Empty on every format ≤8 archive,
     * which never carries a **row** — the codec refuses one that does (an empty list is accepted).
     */
    val assetCategories: List<AssetCategoryDto> = emptyList(),
    /**
     * Format 12 (#79); case headers, ordered by id. Empty on every format ≤11 archive, which never
     * carries a **row** — the codec refuses one that does (an empty list is accepted).
     */
    val serviceCases: List<ServiceCaseDto> = emptyList(),
    /** Format 12 (#79); the cases' timelines, their own rows, ordered by id. Empty on every format ≤11 archive. */
    val serviceCaseEntries: List<ServiceCaseEntryDto> = emptyList(),
    /**
     * Format 13 (#72); the loans, open and returned, ordered by id. Empty on every format ≤12 archive,
     * which never carries a **row** — the codec refuses one that does (an empty list is accepted).
     */
    val assetLoans: List<AssetLoanDto> = emptyList(),
    /**
     * Format 14 (#77); the transfer records, OUT, IN and WITHDRAWN, ordered by id. Empty on every format
     * ≤13 archive, which never carries a **row** — the codec refuses one that does (an empty list is
     * accepted). An ordinary backup carries every record and never the graph of an asset they hold.
     */
    val transferRecords: List<TransferRecordDto> = emptyList(),
    /**
     * Format 15 (#86); the successions, ordered by id. Empty on every format ≤14 archive, which never carries a
     * **row** — the codec refuses one that does (an empty list is accepted). An ordinary backup never carries a row
     * naming an asset transferred out from this phone (`TransferGraph.retain` drops it).
     */
    val assetSuccessions: List<AssetSuccessionDto> = emptyList(),
    /**
     * Format 18 (#15); the SupplyItems, archived included, ordered by id, each with its specifications in
     * `(sortOrder, id)` order. Empty on every format ≤17 archive, which never carries a **row** — the codec refuses
     * one that does (an empty list is accepted).
     */
    val supplyItems: List<SupplyItemDto> = emptyList(),
    /** Format 18 (#15); the applicability rows, ordered by id. Empty on every format ≤17 archive, as [supplyItems]. */
    val assetSupplies: List<AssetSupplyDto> = emptyList(),
    /**
     * Format 19 (#47); the installed components, current and removed, ordered by id, each with its composition in
     * `(sortOrder, id)` order. Empty on every format ≤18 archive, which never carries a **row** — the codec refuses
     * one that does (an empty list is accepted).
     */
    val installedComponents: List<InstalledComponentDto> = emptyList(),
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
    seasonMode = seasonMode.name,
    blackoutStartMmdd = blackoutStartMmdd,
    blackoutEndMmdd = blackoutEndMmdd,
    healthAggregation = healthAggregation.name,
    healthPrimarySubjectId = healthPrimarySubjectId?.value,
    warrantyReminderLeadDays = warrantyReminderLeadDays,
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
    seasonMode = enumOrCorrupt<SeasonMode>(seasonMode, "season mode", "asset $id"),
    blackoutStartMmdd = blackoutStartMmdd,
    blackoutEndMmdd = blackoutEndMmdd,
    healthAggregation = enumOrCorrupt<HealthAggregation>(healthAggregation, "health aggregation", "asset $id"),
    healthPrimarySubjectId = healthPrimarySubjectId?.let(::HealthSubjectId),
    warrantyReminderLeadDays = warrantyReminderLeadDays,
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
    supplyId = supplyId?.value,
)

fun ProfileConsumableDto.toDomain(): ProfileConsumable = ProfileConsumable(
    id = id,
    name = name,
    defaultQuantity = defaultQuantity,
    unit = unit,
    sortOrder = sortOrder,
    supplyId = supplyId?.let(::SupplyId),
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
    supplyId = supplyId?.value,
)

fun ConsumableUsageDto.toDomain(): ConsumableUsage = ConsumableUsage(
    id = id,
    name = name,
    quantity = quantity,
    unit = unit,
    sortOrder = sortOrder,
    supplyId = supplyId?.let(::SupplyId),
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
    role = role?.name,
    sourceUri = source?.uri,
    sourceResolvedUri = source?.resolvedUri,
    sourceRetrievedAt = source?.retrievedAt,
    sourceName = source?.name,
    supplyItemId = (owner as? AttachmentOwner.OfSupplyItem)?.supplyId?.value,
    installedComponentId = (owner as? AttachmentOwner.OfInstalledComponent)?.componentId?.value,
)

/**
 * The owner and the role are checked first (#67, C1): exactly one of the four owner keys (#69, C10), a role
 * one of [DocumentRole]'s names, and an entry's file may not carry one (R67-11, widened by R69-6). Then the
 * source (#85, C4): the four fields must pass [attachmentSourceProblem], the one home of the shape rule, on any
 * owner — so a half-set source is a refusal naming the row, and never reaches a constructor. The decode's naming
 * pass and `validateGraph` both run through here, so an archive breaking any rule is refused before anything is
 * written.
 */
fun AttachmentDto.toDomain(): Attachment {
    val owner = when {
        listOfNotNull(assetId, eventId, supplyItemId, installedComponentId).size != 1 -> null
        assetId != null -> AttachmentOwner.OfAsset(AssetId(assetId))
        eventId != null -> AttachmentOwner.OfEvent(EventId(eventId))
        supplyItemId != null -> AttachmentOwner.OfSupplyItem(SupplyId(supplyItemId))
        else -> AttachmentOwner.OfInstalledComponent(InstalledComponentId(checkNotNull(installedComponentId)))
    } ?: throw BackupCorrupt(
        "attachment $id must name exactly one owner, an asset, an event, a supply item or an installed component",
    )
    val documentRole = role?.let { enumOrCorrupt<DocumentRole>(it, "document role", "attachment $id") }
    if (!owner.accepts(documentRole)) {
        throw BackupCorrupt("attachment $id is an entry's file and carries a document role; an entry's file takes none")
    }
    attachmentSourceProblem(sourceUri, sourceResolvedUri, sourceRetrievedAt, sourceName)?.let { problem ->
        throw BackupCorrupt("attachment $id carries a malformed source: $problem")
    }
    val source = sourceUri?.let { AttachmentSource(it, sourceResolvedUri, checkNotNull(sourceRetrievedAt), sourceName) }
    return Attachment(
        id = AttachmentId(id),
        owner = owner,
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
        role = documentRole,
        source = source,
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
        servicePolicy = servicePolicy.name,
        policyOffsetDays = policyOffsetDays,
        completionMode = completionMode.name,
        profileId = profileId?.value,
        remindersEnabled = remindersEnabled,
        status = status.name,
        postponedDueOn = postponedDueOn,
        createdAt = createdAt,
        updatedAt = updatedAt,
        ruleChangedAt = ruleChangedAt,
        providers = providers.map { it.toDto() },
    )

/**
 * The target is the one field the wire can spell in a way the domain cannot hold, so it is checked
 * here: a row naming both sides, or neither, is refused before any import begins. The merge planner
 * reads the DTO's two columns directly for the same reason — it must be able to *report*
 * `SCHEDULE_TARGET_INVALID` on a row this function would throw on.
 *
 * [MaintenanceScheduleDto.ruleChangedAt] is read as it is and never re-seeded from `updatedAt`: a
 * backup round trip must not move the pin's floor (inv. 87). Only a format ≤7 file, which cannot
 * carry the field, has it seeded — on its tree, by [LegacyArchive].
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
        servicePolicy = enumOrCorrupt<ServicePolicy>(servicePolicy, "service policy", "schedule $id"),
        policyOffsetDays = policyOffsetDays,
        completionMode = enumOrCorrupt<CompletionMode>(completionMode, "completion mode", "schedule $id"),
        profileId = profileId?.let(::ProfileId),
        remindersEnabled = remindersEnabled,
        status = enumOrCorrupt<ScheduleStatus>(status, "schedule status", "schedule $id"),
        postponedDueOn = postponedDueOn,
        createdAt = createdAt,
        updatedAt = updatedAt,
        ruleChangedAt = ruleChangedAt,
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
    role = role?.name,
    // Interim (#69): the domain owner is still an asset, so format 20's two keys are written as nulls.
    supplyItemId = null,
    installedComponentId = null,
)

/**
 * The owner first: exactly one of the three owner keys (#69, C10). Then the kind and the role are names: each
 * must be one of its enum's. Then the role must be one the
 * row's stored kind takes (#91, R91-1: [ReferenceKind.accepts], the reference rule's one home) — so a role on a note link
 * or an "other" link is a refusal naming the row. The kind read is the row's stored one, never re-derived from
 * the uri. The decode's naming pass runs every row through here, so an archive breaking any rule is refused
 * before anything is written.
 */
fun AssetReferenceDto.toDomain(): AssetReference {
    if (listOfNotNull(assetId, supplyItemId, installedComponentId).size != 1) {
        throw BackupCorrupt("reference $id must name exactly one owner, an asset, a supply item or an installed component")
    }
    val referenceKind = enumOrCorrupt<ReferenceKind>(kind, "reference kind", "reference $id")
    val documentRole = role?.let { enumOrCorrupt<DocumentRole>(it, "document role", "reference $id") }
    if (!referenceKind.accepts(documentRole)) {
        throw BackupCorrupt("reference $id is not a web link and carries a document role; only an http or https link may")
    }
    return AssetReference(
        id = ReferenceId(id),
        assetId = AssetId(assetId),
        kind = referenceKind,
        uri = uri,
        displayName = displayName,
        description = description,
        scheme = scheme,
        createdAt = createdAt,
        updatedAt = updatedAt,
        role = documentRole,
    )
}

// --- format 8: season activations, conditions and health subjects ---------------------------------

fun SeasonActivation.toDto(): SeasonActivationDto = SeasonActivationDto(
    id = id,
    assetId = assetId.value,
    action = action.name,
    occurredOn = occurredOn,
    eventId = eventId?.value,
    createdAt = createdAt,
)

fun SeasonActivationDto.toDomain(): SeasonActivation = SeasonActivation(
    id = id,
    assetId = AssetId(assetId),
    action = enumOrCorrupt<SeasonAction>(action, "season action", "activation $id"),
    occurredOn = occurredOn,
    eventId = eventId?.let(::EventId),
    createdAt = createdAt,
)

fun AssetCondition.toDto(): AssetConditionDto = AssetConditionDto(
    id = id,
    assetId = assetId.value,
    condition = condition.name,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    reason = reason,
    eventId = eventId?.value,
    createdAt = createdAt,
)

fun AssetConditionDto.toDomain(): AssetCondition = AssetCondition(
    id = id,
    assetId = AssetId(assetId),
    condition = enumOrCorrupt<OperationalCondition>(condition, "condition", "condition $id"),
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    reason = reason,
    eventId = eventId?.let(::EventId),
    createdAt = createdAt,
)

fun HealthSubject.toDto(): HealthSubjectDto = HealthSubjectDto(
    id = id.value,
    assetId = assetId.value,
    name = name,
    kind = kind.name,
    driver = driver.name,
    scheduleId = scheduleId?.value,
    baselineProfileId = baselineProfileId?.value,
    nominalUntilDays = nominalUntilDays,
    warningFromDays = warningFromDays,
    criticalFromDays = criticalFromDays,
    weight = weight,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun HealthSubjectDto.toDomain(): HealthSubject = HealthSubject(
    id = HealthSubjectId(id),
    assetId = AssetId(assetId),
    name = name,
    kind = enumOrCorrupt<HealthSubjectKind>(kind, "health subject kind", "subject $id"),
    driver = enumOrCorrupt<HealthDriver>(driver, "health driver", "subject $id"),
    scheduleId = scheduleId?.let(::ScheduleId),
    baselineProfileId = baselineProfileId?.let(::ProfileId),
    nominalUntilDays = nominalUntilDays,
    warningFromDays = warningFromDays,
    criticalFromDays = criticalFromDays,
    weight = weight,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// --- format 9: the owner's own categories ---------------------------------------------------------

fun AssetCategory.toDto(): AssetCategoryDto = AssetCategoryDto(
    key = key,
    display = display,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AssetCategoryDto.toDomain(): AssetCategory = AssetCategory(
    key = key,
    display = display,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// --- format 12: service cases ---------------------------------------------------------------------

fun ServiceCase.toDto(): ServiceCaseDto = ServiceCaseDto(
    id = id.value,
    assetId = assetId.value,
    title = title,
    type = type.name,
    openedOn = openedOn,
    closedOn = closedOn,
    provider = provider,
    contact = contact,
    caseRef = caseRef,
    coverage = coverage.name,
    status = status.name,
    outboundTracking = outboundTracking,
    outboundCarrier = outboundCarrier,
    returnTracking = returnTracking,
    returnCarrier = returnCarrier,
    costMinor = costMinor,
    currency = currency,
    notes = notes,
    incidentEventId = incidentEventId?.value,
    resolutionEventId = resolutionEventId?.value,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ServiceCaseDto.toDomain(): ServiceCase = ServiceCase(
    id = ServiceCaseId(id),
    assetId = AssetId(assetId),
    title = title,
    type = enumOrCorrupt<CaseType>(type, "case type", "case $id"),
    openedOn = openedOn,
    closedOn = closedOn,
    provider = provider,
    contact = contact,
    caseRef = caseRef,
    coverage = enumOrCorrupt<CaseCoverage>(coverage, "case coverage", "case $id"),
    status = enumOrCorrupt<CaseStatus>(status, "case status", "case $id"),
    outboundTracking = outboundTracking,
    outboundCarrier = outboundCarrier,
    returnTracking = returnTracking,
    returnCarrier = returnCarrier,
    costMinor = costMinor,
    currency = currency,
    notes = notes,
    incidentEventId = incidentEventId?.let(::EventId),
    resolutionEventId = resolutionEventId?.let(::EventId),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ServiceCaseEntry.toDto(): ServiceCaseEntryDto = ServiceCaseEntryDto(
    id = id.value,
    caseId = caseId.value,
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    note = note,
    status = status?.name,
    createdAt = createdAt,
)

fun ServiceCaseEntryDto.toDomain(): ServiceCaseEntry = ServiceCaseEntry(
    id = ServiceCaseEntryId(id),
    caseId = ServiceCaseId(caseId),
    occurredOn = occurredOn,
    occurredTime = occurredTime,
    tzId = tzId,
    note = note,
    status = status?.let { enumOrCorrupt<CaseStatus>(it, "case status", "case entry $id") },
    createdAt = createdAt,
)

// --- format 13: loans -----------------------------------------------------------------------------

fun AssetLoan.toDto(): AssetLoanDto = AssetLoanDto(
    id = id.value,
    assetId = assetId.value,
    borrowerName = borrowerName,
    contactLookupUri = contactLookupUri,
    lentOn = lentOn,
    dueOn = dueOn,
    returnedOn = returnedOn,
    reminderMode = reminderMode.name,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AssetLoanDto.toDomain(): AssetLoan = AssetLoan(
    id = AssetLoanId(id),
    assetId = AssetId(assetId),
    borrowerName = borrowerName,
    contactLookupUri = contactLookupUri,
    lentOn = lentOn,
    dueOn = dueOn,
    returnedOn = returnedOn,
    reminderMode = enumOrCorrupt<LoanReminderMode>(reminderMode, "loan reminder mode", "loan $id"),
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// --- format 14: transfer records -----------------------------------------------------------------

fun TransferRecord.toDto(): TransferRecordDto = TransferRecordDto(
    id = id,
    assetId = assetId.value,
    kind = kind.name,
    packId = packId,
    lineage = lineage,
    at = at,
    packSha256 = packSha256,
    nameSnapshot = nameSnapshot,
    note = note,
)

fun TransferRecordDto.toDomain(): TransferRecord = TransferRecord(
    id = id,
    assetId = AssetId(assetId),
    kind = enumOrCorrupt<TransferKind>(kind, "transfer kind", "transfer record $id"),
    packId = packId,
    lineage = lineage,
    at = at,
    packSha256 = packSha256,
    nameSnapshot = nameSnapshot,
    note = note,
)

// --- format 15: successions ----------------------------------------------------------------------

fun AssetSuccession.toDto(): AssetSuccessionDto = AssetSuccessionDto(
    id = id,
    predecessorAssetId = predecessorAssetId.value,
    successorAssetId = successorAssetId.value,
    replacedOn = replacedOn,
    createdAt = createdAt,
)

fun AssetSuccessionDto.toDomain(): AssetSuccession = AssetSuccession(
    id = id,
    predecessorAssetId = AssetId(predecessorAssetId),
    successorAssetId = AssetId(successorAssetId),
    replacedOn = replacedOn,
    createdAt = createdAt,
)

// --- format 18: supply items ---------------------------------------------------------------------

fun SupplyItem.toDto(): SupplyItemDto = SupplyItemDto(
    id = id.value,
    name = name,
    category = category,
    manufacturer = manufacturer,
    model = model,
    partNumber = partNumber,
    preferredUnit = preferredUnit,
    notes = notes,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    specifications = specifications.map { it.toDto() },
)

fun SupplyItemDto.toDomain(): SupplyItem = SupplyItem(
    id = SupplyId(id),
    name = name,
    category = category,
    manufacturer = manufacturer,
    model = model,
    partNumber = partNumber,
    preferredUnit = preferredUnit,
    notes = notes,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    specifications = specifications.map { it.toDomain() },
)

fun SupplySpecification.toDto(): SupplySpecificationDto = SupplySpecificationDto(
    id = id,
    key = key,
    label = label,
    value = value,
    unit = unit,
    sortOrder = sortOrder,
)

fun SupplySpecificationDto.toDomain(): SupplySpecification = SupplySpecification(
    id = id,
    key = key,
    label = label,
    value = value,
    unit = unit,
    sortOrder = sortOrder,
)

fun AssetSupply.toDto(): AssetSupplyDto = AssetSupplyDto(
    id = id,
    assetId = assetId.value,
    supplyId = supplyId.value,
    role = role,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AssetSupplyDto.toDomain(): AssetSupply = AssetSupply(
    id = id,
    assetId = AssetId(assetId),
    supplyId = SupplyId(supplyId),
    role = role,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// --- format 19: installed components -------------------------------------------------------------

fun InstalledComponent.toDto(): InstalledComponentDto = InstalledComponentDto(
    id = id.value,
    assetId = assetId.value,
    parentId = parentId?.value,
    name = name,
    supplyId = supplyId?.value,
    composition = composition.map { it.toDto() },
    serialOrLot = serialOrLot,
    installedOn = installedOn,
    removedOn = removedOn,
    replacesId = replacesId?.value,
    sortOrder = sortOrder,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun InstalledComponentDto.toDomain(): InstalledComponent = InstalledComponent(
    id = InstalledComponentId(id),
    assetId = AssetId(assetId),
    parentId = parentId?.let(::InstalledComponentId),
    name = name,
    supplyId = supplyId?.let(::SupplyId),
    composition = composition.map { it.toDomain() },
    serialOrLot = serialOrLot,
    installedOn = installedOn,
    removedOn = removedOn,
    replacesId = replacesId?.let(::InstalledComponentId),
    sortOrder = sortOrder,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun CompositionEntry.toDto(): CompositionEntryDto = CompositionEntryDto(
    id = id,
    supplyId = supplyId.value,
    quantity = quantity,
    unit = unit,
    sortOrder = sortOrder,
)

fun CompositionEntryDto.toDomain(): CompositionEntry = CompositionEntry(
    id = id,
    supplyId = SupplyId(supplyId),
    quantity = quantity,
    unit = unit,
    sortOrder = sortOrder,
)
