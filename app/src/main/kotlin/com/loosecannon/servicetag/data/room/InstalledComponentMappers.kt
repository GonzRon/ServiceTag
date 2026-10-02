package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.CompositionEntry
import com.loosecannon.servicetag.core.model.InstalledComponent
import com.loosecannon.servicetag.core.model.InstalledComponentId
import com.loosecannon.servicetag.core.model.SupplyId
import com.loosecannon.servicetag.data.room.dao.InstalledComponentWithComposition
import com.loosecannon.servicetag.data.room.entities.InstalledComponentCompositionEntity
import com.loosecannon.servicetag.data.room.entities.InstalledComponentEntity

// Schema v19's half of the mapping layer (#47). Every column passes through unchanged in both directions — no
// name, date or unit is cleaned, derived or rewritten on a read, because a mapper that did would make a re-imported
// archive differ from the rows it came from. A row's composition reads back in `(sortOrder, id)` order.

fun InstalledComponentWithComposition.toDomain(): InstalledComponent = InstalledComponent(
    id = InstalledComponentId(row.id),
    assetId = AssetId(row.assetId),
    parentId = row.parentId?.let(::InstalledComponentId),
    name = row.name,
    supplyId = row.supplyId?.let(::SupplyId),
    composition = composition
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        .map { it.toDomain() },
    serialOrLot = row.serialOrLot,
    installedOn = row.installedOn,
    removedOn = row.removedOn,
    replacesId = row.replacesId?.let(::InstalledComponentId),
    sortOrder = row.sortOrder,
    notes = row.notes,
    createdAt = row.createdAt,
    updatedAt = row.updatedAt,
)

fun InstalledComponentCompositionEntity.toDomain(): CompositionEntry = CompositionEntry(
    id = id,
    supplyId = SupplyId(supplyId),
    quantity = quantity,
    unit = unit,
    sortOrder = sortOrder,
)

fun InstalledComponent.toEntity(): InstalledComponentEntity = InstalledComponentEntity(
    id = id.value,
    assetId = assetId.value,
    parentId = parentId?.value,
    name = name,
    supplyId = supplyId?.value,
    serialOrLot = serialOrLot,
    installedOn = installedOn,
    removedOn = removedOn,
    replacesId = replacesId?.value,
    sortOrder = sortOrder,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun CompositionEntry.toEntity(componentId: InstalledComponentId): InstalledComponentCompositionEntity =
    InstalledComponentCompositionEntity(
        id = id,
        componentId = componentId.value,
        supplyId = supplyId.value,
        quantity = quantity,
        unit = unit,
        sortOrder = sortOrder,
    )
