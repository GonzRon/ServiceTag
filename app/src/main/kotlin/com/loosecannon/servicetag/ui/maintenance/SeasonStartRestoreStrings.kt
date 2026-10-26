package com.loosecannon.servicetag.ui.maintenance

import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.usecase.SeasonStartRefusal
import com.loosecannon.servicetag.core.usecase.SeasonStartStrandCause
import com.loosecannon.servicetag.core.usecase.SeasonStartStrands
import com.loosecannon.servicetag.l10n.localized

/**
 * #114 (C11 erratum; A2 re-review) — the sentence a restore from the archive says when the Season Start rule refuses
 * it (§5), so the screen says why instead of throwing. The words live in `res/values/strings_season_start.xml`.
 *
 * - `RESTORE_ALREADY_COVERED` → P114-20, naming the role that already covers the asset — the first of
 *   [SeasonStartStrands.schedules] that is not among [restored].
 * - `RESTORE_NOT_ELIGIBLE` → P114-16, P114-17 or P114-18 by the asset rule's answer, inside P114-21 when a group
 *   target brings the asset in ([throughAGroup]).
 *
 * Null for a cause no restore raises, or when the answer is missing.
 */
internal object SeasonStartRestoreStrings {

    fun of(refused: SeasonStartStrands, assetName: String, restored: Set<ScheduleId>, throughAGroup: Boolean): String? =
        when (refused.strandCause) {
            SeasonStartStrandCause.RESTORE_ALREADY_COVERED ->
                (refused.schedules.firstOrNull { it.id !in restored } ?: refused.schedules.firstOrNull())
                    ?.let { other -> localized(R.string.season_start_refused_already_covered, assetName, other.title) }
            SeasonStartStrandCause.RESTORE_NOT_ELIGIBLE ->
                refused.refusal?.let { notEligible(it, assetName) }?.let { member ->
                    if (throughAGroup) localized(R.string.season_start_refused_group_member, member) else member
                }
            SeasonStartStrandCause.NEEDS_SEASONS,
            SeasonStartStrandCause.WOULD_BE_LEAF_CHILD,
            SeasonStartStrandCause.ROLE_SCHEDULE_RETARGET,
            SeasonStartStrandCause.QUICK_NEEDS_ELECTION,
            SeasonStartStrandCause.MEMBER_NOT_ELIGIBLE,
            SeasonStartStrandCause.MEMBER_ALREADY_COVERED,
            -> null
        }

    private fun notEligible(refusal: SeasonStartRefusal, assetName: String): String? = when (refusal) {
        is SeasonStartRefusal.LeafChild -> localized(R.string.season_start_refused_leaf_child, assetName)
        is SeasonStartRefusal.YearRound -> localized(R.string.season_start_refused_year_round, assetName)
        is SeasonStartRefusal.NotMaintainedHere -> localized(R.string.season_start_refused_not_maintained_here, assetName)
        // C10's asset rule answers only the three above; the others are the role command's own refusals.
        SeasonStartRefusal.ScheduleArchived,
        SeasonStartRefusal.QuickNotElected,
        is SeasonStartRefusal.AlreadyCovered,
        SeasonStartRefusal.GroupArchived,
        SeasonStartRefusal.GroupHasNoMembers,
        SeasonStartRefusal.DateInFuture,
        -> null
    }
}
