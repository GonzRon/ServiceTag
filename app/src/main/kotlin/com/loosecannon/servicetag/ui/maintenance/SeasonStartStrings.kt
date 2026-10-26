package com.loosecannon.servicetag.ui.maintenance

import androidx.annotation.StringRes
import com.loosecannon.servicetag.R
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.usecase.SeasonStartRefusal
import com.loosecannon.servicetag.core.usecase.SeasonStartStrandCause
import com.loosecannon.servicetag.core.usecase.SeasonStartStrands
import com.loosecannon.servicetag.l10n.localized
import com.loosecannon.servicetag.l10n.localizedDate

/**
 * #114 (C33) — the Season Start role's words on the phone (§5; `res/values/strings_season_start.xml`): the role's
 * label and line, the confirm, and one sentence for every refusal ([SeasonStartRefusal], P114-5, P114-16…23,
 * P114-28) and every strand ([SeasonStartStrandCause], P114-20/21 and P114-24…27, P114-43), each an exhaustive `when`
 * with no `else`. One mapping for every caller: the role confirm, the three editors and the two restores from the
 * archive (the C11 erratum).
 */
internal object SeasonStartStrings {

    /** P114-1: the role's label on the schedule's row. */
    fun label(): String = localized(R.string.season_start_service)

    /** P114-41: the schedule detail's role line; [sinceOn] the stored ISO day services count from. */
    fun roleLine(sinceOn: String): String = localized(R.string.season_start_role_line, localizedDate(sinceOn))

    /**
     * P114-4 (an asset's schedule) or P114-4g (a group's): the confirm's question, naming the schedule's [title], the
     * target's [targetName] and the day in the date field, [sinceOn], drawn as the owner reads a date when it is one and
     * as typed when it is not.
     */
    fun question(title: String, targetName: String, group: Boolean, sinceOn: String): String = localized(
        if (group) R.string.season_start_confirm_group else R.string.season_start_confirm_asset,
        title,
        targetName,
        localizedDate(sinceOn.trim()),
    )

    /** P114-5: the confirm's note on a one-tap schedule (O-2), the editor's election body and `QUICK_NOT_ELECTED`. */
    fun quickNote(): String = localized(R.string.season_start_quick_note)

    /** P114-28: the date field holds no calendar date (`SeasonStartBadDate`). */
    fun badDate(): String = localized(R.string.season_start_refused_bad_date)

    /** The asset whose name [refused]'s sentence for [reason] carries; null for a refusal that names none. */
    fun assetOf(reason: SeasonStartRefusal): AssetId? = when (reason) {
        is SeasonStartRefusal.NotMaintainedHere -> reason.assetId
        is SeasonStartRefusal.YearRound -> reason.assetId
        is SeasonStartRefusal.LeafChild -> reason.assetId
        is SeasonStartRefusal.AlreadyCovered -> reason.assetId
        SeasonStartRefusal.ScheduleArchived,
        SeasonStartRefusal.QuickNotElected,
        SeasonStartRefusal.GroupArchived,
        SeasonStartRefusal.GroupHasNoMembers,
        SeasonStartRefusal.DateInFuture,
        -> null
    }

    /**
     * The sentence for `SetScheduleRole`'s refusal [reason]: P114-19, P114-5, P114-22 or P114-23 as they stand; an
     * asset's own refusal — P114-16, P114-17, P114-18 or P114-20 with [assetName], the name of [assetOf]'s asset —
     * inside P114-21 when a group target brings the asset in ([throughAGroup]). Null only when that name is missing.
     */
    fun refused(reason: SeasonStartRefusal, assetName: String?, throughAGroup: Boolean): String? = when (reason) {
        SeasonStartRefusal.ScheduleArchived -> localized(R.string.season_start_refused_schedule_archived)
        SeasonStartRefusal.QuickNotElected -> quickNote()
        SeasonStartRefusal.GroupArchived,
        SeasonStartRefusal.GroupHasNoMembers,
        -> localized(R.string.season_start_refused_no_members)
        SeasonStartRefusal.DateInFuture -> localized(R.string.season_start_refused_date_in_future)
        is SeasonStartRefusal.LeafChild,
        is SeasonStartRefusal.YearRound,
        is SeasonStartRefusal.NotMaintainedHere,
        is SeasonStartRefusal.AlreadyCovered,
        -> assetName?.let { name -> assetSentence(reason, name) }
            ?.let { member -> if (throughAGroup) localized(R.string.season_start_refused_group_member, member) else member }
    }

    /** P114-16, P114-17, P114-18 or P114-20 for C10's asset rule's answer; null for the role command's own refusals. */
    private fun assetSentence(reason: SeasonStartRefusal, assetName: String): String? = when (reason) {
        is SeasonStartRefusal.LeafChild -> localized(R.string.season_start_refused_leaf_child, assetName)
        is SeasonStartRefusal.YearRound -> localized(R.string.season_start_refused_year_round, assetName)
        is SeasonStartRefusal.NotMaintainedHere -> localized(R.string.season_start_refused_not_maintained_here, assetName)
        is SeasonStartRefusal.AlreadyCovered ->
            localized(R.string.season_start_refused_already_covered, assetName, reason.title)
        SeasonStartRefusal.ScheduleArchived,
        SeasonStartRefusal.QuickNotElected,
        SeasonStartRefusal.GroupArchived,
        SeasonStartRefusal.GroupHasNoMembers,
        SeasonStartRefusal.DateInFuture,
        -> null
    }

    /**
     * The sentence for a refused change that would strand a Season Start role ([strands]), where the shipped command is
     * called; [assetName] is the name of [SeasonStartStrands.assetId]'s asset. Null only when a sentence that names the
     * asset has no name.
     *
     * - `NEEDS_SEASONS` → P114-24 and `WOULD_BE_LEAF_CHILD` → P114-25 (the asset editor), with the first role's title;
     * - `ROLE_SCHEDULE_RETARGET` → P114-26 and `QUICK_NEEDS_ELECTION` → P114-43 (the schedule editor);
     * - `MEMBER_NOT_ELIGIBLE` and `MEMBER_ALREADY_COVERED` → P114-27 (the group editor);
     * - a restore from the archive (the C11 erratum): `RESTORE_ALREADY_COVERED` → P114-20, naming the role that already
     *   covers the asset — the first of [SeasonStartStrands.schedules] not among [restored]; `RESTORE_NOT_ELIGIBLE` →
     *   [refused] for the asset rule's answer, inside P114-21 when a group target brings the asset in ([throughAGroup]).
     */
    fun strand(
        strands: SeasonStartStrands,
        assetName: String?,
        restored: Set<ScheduleId> = emptySet(),
        throughAGroup: Boolean = false,
    ): String? = when (strands.strandCause) {
        SeasonStartStrandCause.NEEDS_SEASONS -> withRole(strands, assetName, R.string.season_start_strand_needs_seasons)
        SeasonStartStrandCause.WOULD_BE_LEAF_CHILD ->
            withRole(strands, assetName, R.string.season_start_strand_would_be_leaf_child)
        SeasonStartStrandCause.ROLE_SCHEDULE_RETARGET -> localized(R.string.season_start_strand_retarget)
        SeasonStartStrandCause.QUICK_NEEDS_ELECTION -> localized(R.string.season_start_strand_quick_needs_election)
        SeasonStartStrandCause.MEMBER_NOT_ELIGIBLE,
        SeasonStartStrandCause.MEMBER_ALREADY_COVERED,
        -> assetName?.let { localized(R.string.season_start_strand_member, it) }
        SeasonStartStrandCause.RESTORE_ALREADY_COVERED -> assetName?.let { name ->
            (strands.schedules.firstOrNull { it.id !in restored } ?: strands.schedules.firstOrNull())
                ?.let { other -> localized(R.string.season_start_refused_already_covered, name, other.title) }
        }
        SeasonStartStrandCause.RESTORE_NOT_ELIGIBLE -> strands.refusal?.let { refused(it, assetName, throughAGroup) }
    }

    /** P114-24 or P114-25: [assetName] and the first role covering it ([SeasonStartStrands.schedules], by title). */
    private fun withRole(strands: SeasonStartStrands, assetName: String?, @StringRes id: Int): String? =
        assetName?.let { name -> strands.schedules.firstOrNull()?.let { role -> localized(id, name, role.title) } }
}
