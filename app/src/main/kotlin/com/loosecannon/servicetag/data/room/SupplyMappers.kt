package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSupply
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.core.model.SupplyItem
import com.loosecannon.servicetag.core.model.SupplySpecification
import com.loosecannon.servicetag.data.room.dao.SupplyItemWithSpecifications
import com.loosecannon.servicetag.data.room.entities.AssetSupplyEntity
import com.loosecannon.servicetag.data.room.entities.SupplyItemEntity
import com.loosecannon.servicetag.data.room.entities.SupplySpecificationEntity

// Schema v18's half of the mapping layer (#15). Every column passes through unchanged in both directions —
// no name, key or role is cleaned, derived or rewritten on a read, because a mapper that did would make a
// re-imported archive differ from the rows it came from. Specifications read back in `(sortOrder, id)` order.

fun SupplyItemWithSpecifications.toDomain(): SupplyItem = SupplyItem(
    id = SupplyId(item.id),
    name = item.name,
    category = item.category,
    manufacturer = item.manufacturer,
    model = item.model,
    partNumber = item.partNumber,
    preferredUnit = item.preferredUnit,
    notes = item.notes,
    archivedAt = item.archivedAt,
    createdAt = item.createdAt,
    updatedAt = item.updatedAt,
    specifications = specifications
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        .map { it.toDomain() },
)

fun SupplySpecificationEntity.toDomain(): SupplySpecification = SupplySpecification(
    id = id,
    key = key,
    label = label,
    value = value,
    unit = unit,
    sortOrder = sortOrder,
)

fun SupplyItem.toEntity(): SupplyItemEntity = SupplyItemEntity(
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
)

fun SupplySpecification.toEntity(supplyId: SupplyId): SupplySpecificationEntity = SupplySpecificationEntity(
    id = id,
    supplyId = supplyId.value,
    key = key,
    label = label,
    value = value,
    unit = unit,
    sortOrder = sortOrder,
)

fun AssetSupplyEntity.toDomain(): AssetSupply = AssetSupply(
    id = id,
    assetId = AssetId(assetId),
    supplyId = SupplyId(supplyId),
    role = role,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun AssetSupply.toEntity(): AssetSupplyEntity = AssetSupplyEntity(
    id = id,
    assetId = assetId.value,
    supplyId = supplyId.value,
    role = role,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
