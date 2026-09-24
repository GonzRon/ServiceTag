package com.loosecannon.servicetag.core.model

/**
 * When a schedule's work is actionable relative to its asset's season and break (spec §4.1).
 * Stored as TEXT holding the enum name, in `maintenance_schedule.service_policy`.
 */
enum class ServicePolicy { CONTINUOUS, IN_SERVICE_AT_START, IN_SERVICE_RESUME_CLAMPED, PRE_SERVICE }

/** Whether a schedule is surfacing work, or held while its asset is out of season. */
enum class PolicyPhase { ACTIVE, DORMANT }

/** Why the actionable date differs from the raw due date, or [NONE] when it does not. */
enum class PolicyReason {
    NONE,
    SEASON_START,
    AFTER_BREAK,
    BEFORE_SEASON,
    BEFORE_BREAK,
    POLICY_INAPPLICABLE,
    AWAITING_START,
}

/**
 * The one translation between the 1.3 season triple (`seasonBehavior`, `seasonReentry`,
 * `seasonReentryOffsetDays`) and the 1.4 policy (spec §4.1; master plan §4). It serves the 7 → 8
 * migration, the format ≤7 decoder and the API's deprecated inputs, and nothing else reads the
 * re-entry values: none of them ever had behaviour, so the table **normalises and never refuses**.
 *
 * `docs/api/legacy-season-mapping.json` holds the cases, written from the spec's table rather than
 * from this object; `LegacySeasonMappingTest` runs this object over them.
 */
object LegacySeasonMapping {

    /** The re-entry word that selects the clamped resume. Anything else follows AT_START. */
    const val RESUME_CLAMPED: String = "RESUME_CLAMPED"

    /** The re-entry word the reverse projection writes for AT_START. */
    const val AT_START: String = "AT_START"

    /** The AT_START offset range a legacy value must fall in to survive. */
    private val OFFSET_RANGE = 0..365

    data class Policy(val servicePolicy: ServicePolicy, val policyOffsetDays: Int?)

    data class Legacy(
        val seasonBehavior: SeasonBehavior?,
        val seasonReentry: String?,
        val seasonReentryOffsetDays: Int?,
    )

    /**
     * The forward table. A null [behavior] is an absent one. [hasTimeRule] is whether the schedule
     * carries a time side: a meter-only schedule has no date for an offset to move, so its offset
     * is always 0.
     */
    fun toPolicy(behavior: SeasonBehavior?, reentry: String?, offsetDays: Int?, hasTimeRule: Boolean): Policy =
        when (behavior) {
            null, SeasonBehavior.IGNORE -> Policy(ServicePolicy.CONTINUOUS, null)
            SeasonBehavior.FOLLOW_ASSET -> if (reentry == RESUME_CLAMPED) {
                Policy(ServicePolicy.IN_SERVICE_RESUME_CLAMPED, null)
            } else {
                val kept = offsetDays?.takeIf { hasTimeRule && it in OFFSET_RANGE }
                Policy(ServicePolicy.IN_SERVICE_AT_START, kept ?: 0)
            }
        }

    /**
     * The reverse projection, reported as the derived compatibility triple. AT_START reverses to an
     * explicit [AT_START] and its offset, so the triple round-trips through [toPolicy] to the same
     * policy and offset. `PRE_SERVICE` has no legacy spelling at all.
     */
    fun toLegacy(policy: ServicePolicy, offsetDays: Int?): Legacy = when (policy) {
        ServicePolicy.CONTINUOUS -> Legacy(SeasonBehavior.IGNORE, null, null)
        ServicePolicy.IN_SERVICE_AT_START -> Legacy(SeasonBehavior.FOLLOW_ASSET, AT_START, offsetDays)
        ServicePolicy.IN_SERVICE_RESUME_CLAMPED -> Legacy(SeasonBehavior.FOLLOW_ASSET, RESUME_CLAMPED, null)
        ServicePolicy.PRE_SERVICE -> Legacy(null, null, null)
    }
}
