package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetEvent
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ConsumableUsage
import com.loosecannon.servicetag.core.model.DefinitionId
import com.loosecannon.servicetag.core.model.DefinitionKind
import com.loosecannon.servicetag.core.model.EventId
import com.loosecannon.servicetag.core.model.EventKind
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.EventSource
import com.loosecannon.servicetag.core.model.Measurement
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ProfileId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ValueType
import com.loosecannon.servicetag.core.model.shapeMatches
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** One raw consumable line as typed on the event form; validated and snapshotted by [buildEvent]. */
data class ConsumableInput(val name: String, val quantity: String, val unit: String)

/**
 * The event form's raw input, before [buildEvent] turns it into a stored [AssetEvent]. Values are
 * keyed by the [DefinitionId] the field belongs to, text exactly as typed — an absent key or a
 * blank string both mean "no value for this field".
 */
data class EventCommand(
    val assetId: AssetId,
    val profileId: ProfileId?,
    val kind: EventKind,
    val title: String,
    val occurredOn: String,            // "YYYY-MM-DD"
    val occurredTime: String?,         // "HH:MM" or null
    val tzId: String,
    val notes: String,
    val values: Map<DefinitionId, String>,
    val consumables: List<ConsumableInput>,
    /**
     * 1.2, the occurrence link. A completion names the schedule it satisfies and the occurrence key
     * it claims; every other event leaves both null. They default so that no existing caller —
     * and no route that must not be able to create a completion — has to mention them.
     *
     * `occurrenceOn` is **immutable once written**: [buildEvent] keeps the stored value on an edit
     * and only ever takes this one on the first write.
     */
    val scheduleId: ScheduleId? = null,
    val occurrenceOn: String? = null,
    /** True only for a minimal completion against a FORM schedule: the details are still owed. */
    val detailsPending: Boolean = false,
)

/** One thing wrong with an [EventCommand]'s fields, reported so a form can mark the right row. */
sealed interface FieldProblem {
    val definitionId: DefinitionId?

    data class Required(override val definitionId: DefinitionId) : FieldProblem
    data class NotANumber(override val definitionId: DefinitionId) : FieldProblem
    data class BadDate(override val definitionId: DefinitionId? = null) : FieldProblem
    data class BadTime(override val definitionId: DefinitionId? = null) : FieldProblem
    data object TitleRequired : FieldProblem {
        override val definitionId: DefinitionId? = null
    }
    data class BadConsumable(val index: Int) : FieldProblem {
        override val definitionId: DefinitionId? = null
    }
}

/** Field validation failed; every problem found, collected once rather than fail-fast. */
class EventValidation(val problems: List<FieldProblem>) : IllegalArgumentException("invalid event: $problems")

/** A profile or definition named by the command belongs to a different asset (or doesn't exist). */
class EventOwnership(detail: String) : IllegalArgumentException(detail)

/** The event this edit or delete was aimed at is no longer there. */
class NoSuchEvent(id: EventId) : IllegalArgumentException("no event ${id.value}")

private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private val TIME_PATTERN = Regex("^\\d{2}:\\d{2}$")

/**
 * Resolves `cmd.profileId` against [profiles] and checks every definition named in `cmd.values`
 * against [definitions], throwing [EventOwnership] the moment any of them turns out to belong to
 * a different asset than `cmd.assetId` (or not to exist at all). Shared by [LogEvent] and
 * [UpdateEvent] — the asset-identity half of ownership (`existing.assetId == cmd.assetId`) is
 * [UpdateEvent]'s own, since only it has an `existing` event to compare against.
 */
internal suspend fun resolveOwnedProfile(
    cmd: EventCommand,
    definitions: DefinitionRepository,
    profiles: ProfileRepository,
): EventProfile? {
    val profile = cmd.profileId?.let { pid ->
        val p = profiles.get(pid)
        if (p == null || p.assetId != cmd.assetId) {
            throw EventOwnership("profile ${pid.value} does not belong to asset ${cmd.assetId.value}")
        }
        p
    }
    for (defId in cmd.values.keys) {
        val d = definitions.get(defId)
        if (d == null || d.assetId != cmd.assetId) {
            throw EventOwnership("definition ${defId.value} does not belong to asset ${cmd.assetId.value}")
        }
    }
    return profile
}

private fun validDate(occurredOn: String): Boolean =
    DATE_PATTERN.matches(occurredOn) &&
        try {
            LocalDate.parse(occurredOn)
            true
        } catch (e: DateTimeParseException) {
            false
        }

private fun validTime(occurredTime: String?): Boolean = occurredTime == null || isTimeOfDay(occurredTime)

/**
 * The one `HH:MM` rule: two digits, a colon, two digits, an hour below 24 and a minute below 60. An
 * event's and a condition's `occurredTime` are both checked here, so the two can never disagree.
 */
internal fun isTimeOfDay(value: String): Boolean {
    if (!TIME_PATTERN.matches(value)) return false
    val hour = value.substring(0, 2).toInt()
    val minute = value.substring(3, 5).toInt()
    return hour < 24 && minute < 60
}

/** Parses one field's raw text against its definition's [ValueType]; null means "no value". */
private fun parsedValue(definition: MeasurementDefinition, raw: String): Pair<Double?, String?>? =
    when (definition.valueType) {
        ValueType.NUMBER -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it to null }
        ValueType.BOOLEAN -> when (raw.lowercase()) {
            "1", "true" -> 1.0 to null
            "0", "false" -> 0.0 to null
            else -> null
        }
        ValueType.TEXT -> null to raw
    }

/**
 * The one validation path for [LogEvent] and [UpdateEvent]: builds a fully-formed [AssetEvent]
 * from [cmd], or throws [EventValidation] with every field problem found (collected, not
 * fail-fast). Ownership must already have been checked by the caller before this runs.
 *
 * Measurements are produced in [profile]'s field order first, then any other defined value in
 * [EventCommand.values] in definition `sortOrder`; a measurement's id is carried over from
 * [existing] by definition id when there is a prior measurement for it, otherwise minted from
 * [ids] — for [UpdateEvent], that means a field new to this edit still gets a fresh id even
 * though the use case itself has no [IdGenerator] of its own (see [UpdateEvent]).
 */
internal suspend fun buildEvent(
    cmd: EventCommand,
    definitions: DefinitionRepository,
    profile: EventProfile?,
    existing: AssetEvent?,
    ids: IdGenerator,
    now: Long,
    source: EventSource = EventSource.MANUAL,
): AssetEvent {
    val problems = mutableListOf<FieldProblem>()

    val title = cmd.title.trim().ifBlank { profile?.defaultTitle?.trim().orEmpty() }
    if (title.isBlank()) problems += FieldProblem.TitleRequired

    if (!validDate(cmd.occurredOn)) problems += FieldProblem.BadDate()
    if (!validTime(cmd.occurredTime)) problems += FieldProblem.BadTime()

    fun existingMeasurementId(defId: DefinitionId): String? =
        existing?.measurements?.firstOrNull { it.definitionId == defId }?.id

    val measurements = mutableListOf<Measurement>()
    val handled = mutableSetOf<DefinitionId>()

    suspend fun consider(defId: DefinitionId, required: Boolean) {
        handled += defId
        // A profile field whose definition no longer resolves can't be measured; a required one
        // is still missing a value, so report it rather than silently dropping the field.
        val definition = definitions.get(defId) ?: run { if (required) problems += FieldProblem.Required(defId); return }
        // A derived value is computed from the event's own measurements, never typed and never
        // stored: whatever the caller sent for it is ignored, and it is not a field to demand.
        if (definition.kind == DefinitionKind.DERIVED) return
        val raw = cmd.values[defId]?.trim()
        if (raw.isNullOrBlank()) {
            // An archived definition is off every form, so nothing can satisfy its `required`
            // flag; a value carried over from before the archiving still saves below.
            if (required && definition.archivedAt == null) problems += FieldProblem.Required(defId)
            return
        }
        val parsed = parsedValue(definition, raw)
        if (parsed == null) {
            problems += FieldProblem.NotANumber(defId)
            return
        }
        val (valueNum, valueText) = parsed
        val measurement = Measurement(
            id = existingMeasurementId(defId) ?: ids.newId(),
            definitionId = defId,
            valueNum = valueNum,
            valueText = valueText,
            unit = definition.unit,
            sortOrder = 0,   // reassigned below, by final position
        )
        check(measurement.shapeMatches(definition.valueType)) {
            "measurement for ${defId.value} does not match ${definition.valueType}"
        }
        measurements += measurement
    }

    profile?.fields?.sortedBy { it.sortOrder }?.forEach { field -> consider(field.definitionId, field.required) }
    val extraIds = cmd.values.keys - handled
    val extraDefs = extraIds.mapNotNull { defId -> definitions.get(defId)?.let { defId to it.sortOrder } }
        .sortedBy { it.second }
    extraDefs.forEach { (defId, _) -> consider(defId, required = false) }

    val orderedMeasurements = measurements.mapIndexed { i, m -> m.copy(sortOrder = i) }

    val consumables = cmd.consumables.mapIndexedNotNull { i, input ->
        val name = input.name.trim()
        val quantity = input.quantity.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        if (name.isBlank() || quantity == null || quantity < 0.0) {
            problems += FieldProblem.BadConsumable(i)
            null
        } else {
            ConsumableUsage(
                id = existing?.consumables?.getOrNull(i)?.id ?: ids.newId(),
                name = name,
                quantity = quantity,
                unit = input.unit.trim(),
                sortOrder = i,
            )
        }
    }

    if (problems.isNotEmpty()) throw EventValidation(problems)

    return AssetEvent(
        id = existing?.id ?: EventId(ids.newId()),
        assetId = cmd.assetId,
        kind = cmd.kind,
        title = title,
        profileId = cmd.profileId,
        occurredOn = cmd.occurredOn,
        occurredTime = cmd.occurredTime,
        tzId = cmd.tzId,
        notes = cmd.notes.trim(),
        source = existing?.source ?: source,
        sourceRef = existing?.sourceRef,
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
        measurements = orderedMeasurements,
        consumables = consumables,
        // An ordinary edit of a completion keeps its link: a form that knows nothing about
        // schedules must not be able to orphan one by leaving the field out.
        scheduleId = cmd.scheduleId ?: existing?.scheduleId,
        // Immutable once written, which is what keeps the idempotence index meaningful.
        occurrenceOn = existing?.occurrenceOn ?: cmd.occurrenceOn,
        // An edit that supplies the owed details clears the flag, which is the badge going away.
        detailsPending = cmd.detailsPending,
    )
}
