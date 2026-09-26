package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetCategory
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetStatus
import com.loosecannon.servicetag.core.model.Attachment
import com.loosecannon.servicetag.core.model.AttachmentId
import com.loosecannon.servicetag.core.model.AttachmentKind
import com.loosecannon.servicetag.core.model.AttachmentMode
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.HealthSubjectId
import com.loosecannon.servicetag.core.model.LinkId
import com.loosecannon.servicetag.core.model.LinkKind
import com.loosecannon.servicetag.core.model.PayloadFormat
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.StorageProvider
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.data.room.entities.AssetCategoryEntity
import com.loosecannon.servicetag.data.room.entities.AssetEntity
import com.loosecannon.servicetag.data.room.entities.AttachmentEntity
import com.loosecannon.servicetag.data.room.entities.ExternalLinkEntity
import com.loosecannon.servicetag.data.room.entities.NfcTagEntity

// Enums are stored as TEXT holding the Kotlin enum name (D4 conventions); `valueOf` rejects
// anything else at the repository boundary.

// `status` goes through `AssetStatus.valueOf`, and v4 knows only ACTIVE and ARCHIVED: RETIRED is
// gone from the enum, `MIGRATION_3_4` rewrote any stored one, and retirement is `retired_on` now
// (spec §7). Anything else in the column is a corrupt row and throws, as it always did.
fun AssetEntity.toDomain(): Asset = Asset(
    id = AssetId(id),
    name = name,
    description = description,
    category = category,
    notes = notes,
    status = AssetStatus.valueOf(status),
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
    seasonMode = SeasonMode.valueOf(seasonMode),
    blackoutStartMmdd = blackoutStartMmdd,
    blackoutEndMmdd = blackoutEndMmdd,
    healthAggregation = HealthAggregation.valueOf(healthAggregation),
    healthPrimarySubjectId = healthPrimarySubjectId?.let(::HealthSubjectId),
)

fun Asset.toEntity(): AssetEntity = AssetEntity(
    id = id.value,
    name = name,
    description = description,
    category = category,
    notes = notes,
    status = status.name,
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
    parentAssetId = parentAssetId?.value,
    seasonStartMmdd = seasonStartMmdd,
    seasonEndMmdd = seasonEndMmdd,
    seasonMode = seasonMode.name,
    blackoutStartMmdd = blackoutStartMmdd,
    blackoutEndMmdd = blackoutEndMmdd,
    healthAggregation = healthAggregation.name,
    healthPrimarySubjectId = healthPrimarySubjectId?.value,
)

fun NfcTagEntity.toDomain(): TagBinding = TagBinding(
    id = TagId(id),
    payloadFormat = PayloadFormat.valueOf(payloadFormat),
    payloadKey = payloadKey,
    target = when {
        assetId != null -> TagTarget.AssetTarget(AssetId(assetId))
        linkId != null -> TagTarget.LinkTarget(LinkId(linkId))
        else -> TagTarget.None
    },
    status = TagStatus.valueOf(status),
    label = label,
    physicalUid = physicalUid,
    writtenAt = writtenAt,
    lastScannedAt = lastScannedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun TagBinding.toEntity(): NfcTagEntity = NfcTagEntity(
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

fun ExternalLinkEntity.toDomain(): ExternalLink = ExternalLink(
    id = LinkId(id),
    assetId = assetId?.let(::AssetId),
    kind = LinkKind.valueOf(kind),
    label = label,
    uri = uri,
    createdAt = createdAt,
    lastOpenedAt = lastOpenedAt,
    updatedAt = updatedAt,
)

fun ExternalLink.toEntity(): ExternalLinkEntity = ExternalLinkEntity(
    id = id.value,
    assetId = assetId?.value,
    kind = kind.name,
    label = label,
    uri = uri,
    createdAt = createdAt,
    lastOpenedAt = lastOpenedAt,
    updatedAt = updatedAt,
)

/**
 * D4 §11's exactly-one-owner rule, enforced where the row enters the table. The domain's
 * `AttachmentOwner` already makes both-at-once unrepresentable; this guards rows built any other
 * way, and it is the reason the schema carries no `CHECK` (spec §11.5).
 */
fun AttachmentEntity.requireExactlyOneOwner(): AttachmentEntity = apply {
    require((assetId == null) != (eventId == null)) {
        "attachment '$id' must name exactly one owner, found asset_id=$assetId event_id=$eventId"
    }
}

fun AttachmentEntity.toDomain(): Attachment = Attachment(
    id = AttachmentId(id),
    owner = when {
        assetId != null -> AttachmentOwner.OfAsset(AssetId(assetId))
        eventId != null -> AttachmentOwner.OfEvent(EventId(eventId))
        else -> error("attachment '$id' has no owner")
    },
    kind = AttachmentKind.valueOf(kind),
    mode = AttachmentMode.valueOf(mode),
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    storageProvider = StorageProvider.valueOf(storageProvider),
    storageLocator = storageLocator,
    capturedOn = capturedOn,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Attachment.toEntity(): AttachmentEntity = AttachmentEntity(
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

// #74: a catalog row passes through unchanged in both directions; its key rule lives in core.
fun AssetCategoryEntity.toDomain(): AssetCategory = AssetCategory(
    key = key,
    display = display,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AssetCategory.toEntity(): AssetCategoryEntity = AssetCategoryEntity(
    key = key,
    display = display,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
