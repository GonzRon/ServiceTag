package com.loosecannon.servicetag.core.model

/**
 * How an asset's operating season is decided (spec §3.1). `YEAR_ROUND` has no season at all;
 * `CALENDAR` reads the two `MM-DD` bounds on the asset; `MANUAL` reads the latest
 * [SeasonActivation] row. Stored as TEXT holding the enum name.
 */
enum class SeasonMode { YEAR_ROUND, CALENDAR, MANUAL }

/** What one manual activation row records: the season started, or it ended. */
enum class SeasonAction { START, END }

/**
 * One manual season activation (spec §3.3): an **immutable fact**, the `occurrence_closure`
 * precedent. There is no `updatedAt`, because nothing ever moves the row; it leaves only by its
 * asset's CASCADE. [eventId] is a soft link to the journal event that prompted it, never an owner:
 * deleting that event leaves this row as it was.
 *
 * Rows order by `(occurredOn, createdAt, id)`, and the phase is the latest row's action.
 */
data class SeasonActivation(
    val id: String,
    val assetId: AssetId,
    val action: SeasonAction,
    /** ISO `YYYY-MM-DD`. */
    val occurredOn: String,
    val eventId: EventId?,
    val createdAt: Long,
)

/**
 * Everything the engine needs to know about one asset's season and break, as a pure input: the
 * mode, the calendar window, the break window and the manual history. Built by [seasonInputs] at
 * the seam that reads repositories, so the recompute itself never reads one.
 */
data class SeasonInputs(
    val mode: SeasonMode,
    val seasonStartMmdd: String?,
    val seasonEndMmdd: String?,
    val blackoutStartMmdd: String?,
    val blackoutEndMmdd: String?,
    val activations: List<SeasonActivation>,
)

/**
 * This asset's [SeasonInputs]. [activations] are the asset's rows; the caller reads them only when
 * the asset is `MANUAL`, which is the only mode that consults them, and hands an empty list
 * otherwise.
 */
fun Asset.seasonInputs(activations: List<SeasonActivation>): SeasonInputs = SeasonInputs(
    mode = seasonMode,
    seasonStartMmdd = seasonStartMmdd,
    seasonEndMmdd = seasonEndMmdd,
    blackoutStartMmdd = blackoutStartMmdd,
    blackoutEndMmdd = blackoutEndMmdd,
    activations = activations,
)
