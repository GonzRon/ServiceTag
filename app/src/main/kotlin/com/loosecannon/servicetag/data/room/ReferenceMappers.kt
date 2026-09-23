package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetReference
import com.loosecannon.servicetag.core.model.ReferenceId
import com.loosecannon.servicetag.core.model.ReferenceKind
import com.loosecannon.servicetag.data.room.entities.AssetReferenceEntity

// Schema v7's half of the mapping layer. Same rules as [MaintenanceMappers.kt]: the enum travels
// as its Kotlin name and `valueOf` rejects anything else here, at the repository boundary. Every
// other column passes through unchanged in both directions — `uri` and `scheme` in particular are
// never rewritten on a read, because a mapper that re-derived either would make a re-imported
// archive differ from the rows it came from.

fun AssetReferenceEntity.toDomain(): AssetReference = AssetReference(
    id = ReferenceId(id),
    assetId = AssetId(assetId),
    kind = ReferenceKind.valueOf(kind),
    uri = uri,
    displayName = displayName,
    description = description,
    scheme = scheme,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AssetReference.toEntity(): AssetReferenceEntity = AssetReferenceEntity(
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
