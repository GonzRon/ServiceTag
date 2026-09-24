package com.loosecannon.servicetag.core.health

/** The three words a score falls into (spec §6.3). NOT TRACKED is not a band: it is the absence of a score. */
enum class HealthBand { NOMINAL, WARNING, CRITICAL }

/**
 * The 0–100 score and its band (spec §6.3, master plan §10.2).
 *
 * ```
 * score(x; t1, t2, t3) = ceil(v), where v =
 *   100                                   when x <= t1
 *   100 - 40·(x - t1)/(t2 - t1)           when t1 < x <= t2
 *    60 - 35·(x - t2)/(t3 - t2)           when t2 < x <= t3
 *    25 - 25·(x - t3)/(t3 - t2)           when t3 < x <= t3 + (t3 - t2)
 *     0                                   beyond
 * ```
 *
 * Every segment is computed as an **exact rational** in `Long` arithmetic and rounded up once, with
 * a ceiling division; nothing here is floating point. That is what makes the anchors exact: the
 * value at `x = t2` is 60 exactly and at `x = t3` 25 exactly, so WARNING starts on `t2` and CRITICAL
 * on `t3` to the day, and a day earlier the ceiling keeps the score strictly above the anchor. The
 * segments meet at their ends and each one falls, so the score **never rises as `x` grows**
 * (inv. 117).
 *
 * A negative `x` — an AGE baseline dated in the future — is below `t1` and scores 100.
 */
object HealthScore {

    const val TOP = 100

    /** The ceiling of each band: a score at or below it is in that band. */
    const val CRITICAL_AT_MOST = 25
    const val WARNING_AT_MOST = 60

    /**
     * [t1], [t2] and [t3] are a subject's `nominal_until_days`, `warning_from_days` and
     * `critical_from_days`. They satisfy `0 ≤ t1 < t2 < t3` by construction — the subject command
     * refuses anything else and the format-8 content check refuses it on import — and a row that
     * broke it would divide by zero, so it is refused here too rather than answered.
     */
    fun score(x: Long, t1: Int, t2: Int, t3: Int): Int {
        require(t1 in 0 until t2 && t2 < t3) { "thresholds must satisfy 0 <= t1 < t2 < t3: $t1, $t2, $t3" }
        val nominalUntil = t1.toLong()
        val warningFrom = t2.toLong()
        val criticalFrom = t3.toLong()
        val warningSpan = warningFrom - nominalUntil
        val criticalSpan = criticalFrom - warningFrom
        val value = when {
            x <= nominalUntil -> TOP.toLong()
            x <= warningFrom -> ceilDiv(100 * warningSpan - 40 * (x - nominalUntil), warningSpan)
            x <= criticalFrom -> ceilDiv(60 * criticalSpan - 35 * (x - warningFrom), criticalSpan)
            x <= criticalFrom + criticalSpan -> ceilDiv(25 * criticalSpan - 25 * (x - criticalFrom), criticalSpan)
            else -> 0L
        }
        return value.toInt()
    }

    fun band(score: Int): HealthBand = when {
        score <= CRITICAL_AT_MOST -> HealthBand.CRITICAL
        score <= WARNING_AT_MOST -> HealthBand.WARNING
        else -> HealthBand.NOMINAL
    }

    /** `ceil(numerator / denominator)` for a positive denominator, exactly. */
    private fun ceilDiv(numerator: Long, denominator: Long): Long = -Math.floorDiv(-numerator, denominator)
}
