package com.loosecannon.servicetag.core.model

/**
 * An asset's operational condition (spec §5.1). Three values and no fourth: "pending maintenance"
 * is a reason, and an asset with no row has no condition recorded — nothing stores an UNKNOWN.
 */
enum class OperationalCondition { OPERATIONAL, DEGRADED, DOWN }

/**
 * One condition fact: **immutable**, the `occurrence_closure` precedent. A correction is a new
 * row, so there is no `updatedAt`; a row leaves only by its asset's CASCADE. [eventId] is a soft
 * link, never an owner.
 *
 * The current condition is the latest row by `(occurredOn, occurredTime nulls first, createdAt,
 * id)`, and no column anywhere stores it.
 */
data class AssetCondition(
    val id: String,
    val assetId: AssetId,
    val condition: OperationalCondition,
    /** ISO `YYYY-MM-DD`. */
    val occurredOn: String,
    /** `HH:MM`, or null when only the day is known. */
    val occurredTime: String?,
    val tzId: String,
    /** Up to 500 characters; may be empty. */
    val reason: String,
    val eventId: EventId?,
    val createdAt: Long,
)
