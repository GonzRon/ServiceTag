package com.loosecannon.servicetag.data.room

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ConsumableUsage
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.DerivedFormula
import com.loosecannon.servicetag.core.model.DerivedSpec
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileConsumable
import com.loosecannon.servicetag.core.model.ProfileField
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.data.room.dao.EventWithParts
import com.loosecannon.servicetag.data.room.dao.ProfileWithParts
import com.loosecannon.servicetag.data.room.entities.AssetEventEntity
import com.loosecannon.servicetag.data.room.entities.ConsumableUsageEntity
import com.loosecannon.servicetag.data.room.entities.EventProfileEntity
import com.loosecannon.servicetag.data.room.entities.MeasurementDefinitionEntity
import com.loosecannon.servicetag.data.room.entities.MeasurementEntity
import com.loosecannon.servicetag.data.room.entities.ProfileConsumableEntity
import com.loosecannon.servicetag.data.room.entities.ProfileFieldEntity

// Schema v3's half of the mapping layer. Same rules as [Mappers.kt]: enums travel as their Kotlin
// name and `valueOf` rejects anything else here, at the repository boundary. Child-row ids pass
// through unchanged in both directions — the database never mints one, the domain always does.
// `@Relation` returns children in no particular order, so every read sorts by `sortOrder`.

/**
 * The three DERIVED columns are one value in the domain: a [DerivedSpec], or nothing. Anything in
 * between — a formula with no sources, a source with no formula — is a row the writers below
 * cannot produce and `:core`'s validation refuses, so it is read as an absent spec rather than
 * being half-assembled.
 */
fun MeasurementDefinitionEntity.toDomain(): MeasurementDefinition = MeasurementDefinition(
    id = DefinitionId(id),
    assetId = AssetId(assetId),
    key = key,
    label = label,
    unit = unit,
    valueType = ValueType.valueOf(valueType),
    decimals = decimals,
    rangeLow = rangeLow,
    rangeHigh = rangeHigh,
    isMeter = isMeter,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    kind = enumValueOf<DefinitionKind>(kind),
    derived = derivedSpec(),
)

private fun MeasurementDefinitionEntity.derivedSpec(): DerivedSpec? {
    val f = formula ?: return null
    val a = sourceAId ?: return null
    val b = sourceBId ?: return null
    return DerivedSpec(enumValueOf<DerivedFormula>(f), DefinitionId(a), DefinitionId(b))
}

fun MeasurementDefinition.toEntity(): MeasurementDefinitionEntity = MeasurementDefinitionEntity(
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

fun ProfileWithParts.toDomain(): EventProfile = EventProfile(
    id = ProfileId(profile.id),
    assetId = AssetId(profile.assetId),
    name = profile.name,
    eventKind = EventKind.valueOf(profile.eventKind),
    defaultTitle = profile.defaultTitle,
    templateKey = profile.templateKey,
    sortOrder = profile.sortOrder,
    archivedAt = profile.archivedAt,
    createdAt = profile.createdAt,
    updatedAt = profile.updatedAt,
    fields = fields.sortedBy { it.sortOrder }.map {
        ProfileField(
            id = it.id,
            definitionId = DefinitionId(it.definitionId),
            required = it.required,
            sortOrder = it.sortOrder,
        )
    },
    consumables = consumables.sortedBy { it.sortOrder }.map {
        ProfileConsumable(
            id = it.id,
            name = it.name,
            defaultQuantity = it.defaultQuantity,
            unit = it.unit,
            sortOrder = it.sortOrder,
        )
    },
)

fun EventProfile.toEntity(): EventProfileEntity = EventProfileEntity(
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
)

fun ProfileField.toEntity(profileId: ProfileId): ProfileFieldEntity = ProfileFieldEntity(
    id = id,
    profileId = profileId.value,
    definitionId = definitionId.value,
    required = required,
    sortOrder = sortOrder,
)

fun ProfileConsumable.toEntity(profileId: ProfileId): ProfileConsumableEntity = ProfileConsumableEntity(
    id = id,
    profileId = profileId.value,
    name = name,
    defaultQuantity = defaultQuantity,
    unit = unit,
    sortOrder = sortOrder,
)

fun EventWithParts.toDomain(): AssetEvent = AssetEvent(
    id = EventId(event.id),
    assetId = AssetId(event.assetId),
    kind = EventKind.valueOf(event.kind),
    title = event.title,
    profileId = event.profileId?.let(::ProfileId),
    occurredOn = event.occurredOn,
    occurredTime = event.occurredTime,
    tzId = event.tzId,
    notes = event.notes,
    source = EventSource.valueOf(event.source),
    sourceRef = event.sourceRef,
    createdAt = event.createdAt,
    updatedAt = event.updatedAt,
    measurements = measurements.sortedBy { it.sortOrder }.map {
        Measurement(
            id = it.id,
            definitionId = DefinitionId(it.definitionId),
            valueNum = it.valueNum,
            valueText = it.valueText,
            unit = it.unit,
            sortOrder = it.sortOrder,
        )
    },
    consumables = consumables.sortedBy { it.sortOrder }.map {
        ConsumableUsage(
            id = it.id,
            name = it.name,
            quantity = it.quantity,
            unit = it.unit,
            sortOrder = it.sortOrder,
        )
    },
    scheduleId = event.scheduleId?.let(::ScheduleId),
    occurrenceOn = event.occurrenceOn,
    detailsPending = event.detailsPending,
)

fun AssetEvent.toEntity(): AssetEventEntity = AssetEventEntity(
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
    scheduleId = scheduleId?.value,
    occurrenceOn = occurrenceOn,
    detailsPending = detailsPending,
)

fun Measurement.toEntity(eventId: EventId): MeasurementEntity = MeasurementEntity(
    id = id,
    eventId = eventId.value,
    definitionId = definitionId.value,
    valueNum = valueNum,
    valueText = valueText,
    unit = unit,
    sortOrder = sortOrder,
)

fun ConsumableUsage.toEntity(eventId: EventId): ConsumableUsageEntity = ConsumableUsageEntity(
    id = id,
    eventId = eventId.value,
    name = name,
    quantity = quantity,
    unit = unit,
    sortOrder = sortOrder,
)
