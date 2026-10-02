package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.DocumentRole
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.core.model.ReferenceOwner
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.data.room.entities.AssetReferenceEntity

// Schema v7's half of the mapping layer. Same rules as [MaintenanceMappers.kt]: each enum — the kind,
// and since v17 the document role (#91), null for none — travels as its Kotlin name and `valueOf`
// rejects anything else here, at the repository boundary. Every other column passes through
// unchanged in both directions — `uri` and `scheme` in particular are never rewritten on a read,
// because a mapper that re-derived either (or read a role off them) would make a re-imported
// archive differ from the rows it came from.

/**
 * The reference twin of the attachment's exactly-one-owner rule (#69, C7), over the three owner
 * columns: enforced on every Room write, since the schema carries no `CHECK`.
 */
fun AssetReferenceEntity.requireExactlyOneOwner(): AssetReferenceEntity = apply {
    require(listOfNotNull(assetId, supplyItemId, installedComponentId).size == 1) {
        "reference '$id' must name exactly one owner, found asset_id=$assetId " +
            "supply_item_id=$supplyItemId installed_component_id=$installedComponentId"
    }
}

fun AssetReferenceEntity.toDomain(): AssetReference = AssetReference(
    id = ReferenceId(id),
    owner = requireExactlyOneOwner().let {
        when {
            assetId != null -> ReferenceOwner.OfAsset(AssetId(assetId))
            supplyItemId != null -> ReferenceOwner.OfSupplyItem(SupplyId(supplyItemId))
            else -> ReferenceOwner.OfInstalledComponent(InstalledComponentId(checkNotNull(installedComponentId)))
        }
    },
    kind = ReferenceKind.valueOf(kind),
    uri = uri,
    displayName = displayName,
    description = description,
    scheme = scheme,
    createdAt = createdAt,
    updatedAt = updatedAt,
    role = documentRole?.let(DocumentRole::valueOf),
)

fun AssetReference.toEntity(): AssetReferenceEntity = AssetReferenceEntity(
    id = id.value,
    assetId = (owner as? ReferenceOwner.OfAsset)?.assetId?.value,
    kind = kind.name,
    uri = uri,
    displayName = displayName,
    description = description,
    scheme = scheme,
    createdAt = createdAt,
    updatedAt = updatedAt,
    documentRole = role?.name,
    supplyItemId = (owner as? ReferenceOwner.OfSupplyItem)?.supplyId?.value,
    installedComponentId = (owner as? ReferenceOwner.OfInstalledComponent)?.componentId?.value,
).requireExactlyOneOwner()
