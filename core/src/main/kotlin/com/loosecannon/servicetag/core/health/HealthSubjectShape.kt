package com.loosecannon.servicetag.core.health

import com.loosecannon.servicetag.core.model.HealthSubject

/**
 * What makes a subject's numbers unusable by the engine. The two members are the numeric halves of
 * the subject command's refusals, `HEALTH_THRESHOLDS_INVALID` and `HEALTH_WEIGHT_OUT_OF_RANGE`
 * (spec §6.1, master plan §10.1); they carry no words.
 */
enum class HealthSubjectShapeProblem { THRESHOLDS_INVALID, WEIGHT_OUT_OF_RANGE }

/**
 * The one well-formedness rule for a subject's thresholds and weight (spec §6.1): `0 ≤ t1 < t2 < t3
 * ≤ 36,500` and a weight of 1–10.
 *
 * The engine is a whole-asset function, so one malformed row would abort the whole asset's health.
 * No local write can store one, because the subject command refuses it, and neither can a restore,
 * because the format-8 content check refuses it too. A reader that must not fail — a read model
 * over merged data — therefore **screens** each subject with [problems] before calling
 * [AssetHealthEngine.evaluate], and the engine keeps refusing a malformed subject with
 * `IllegalArgumentException`. [HealthScore.score]'s precondition is [thresholdsValid], so the
 * screen, the engine and the score can never disagree about which subjects are well formed.
 */
object HealthSubjectShape {

    const val MAX_THRESHOLD_DAYS = 36_500
    val WEIGHTS: IntRange = 1..10

    fun thresholdsValid(t1: Int, t2: Int, t3: Int): Boolean = t1 >= 0 && t1 < t2 && t2 < t3 && t3 <= MAX_THRESHOLD_DAYS

    fun weightValid(weight: Int): Boolean = weight in WEIGHTS

    /** Empty exactly when `evaluate`'s subject check accepts [subject] as one of the asset's non-archived subjects. */
    fun problems(subject: HealthSubject): Set<HealthSubjectShapeProblem> = buildSet {
        if (!thresholdsValid(subject.nominalUntilDays, subject.warningFromDays, subject.criticalFromDays)) {
            add(HealthSubjectShapeProblem.THRESHOLDS_INVALID)
        }
        if (!weightValid(subject.weight)) add(HealthSubjectShapeProblem.WEIGHT_OUT_OF_RANGE)
    }
}
