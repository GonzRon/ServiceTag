package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.merge.MergeReport

/**
 * The merge was refused because the plan holds conflicts. **Nothing was written** — the refusal is
 * raised before the transaction opens, or inside it, where it rolls back.
 *
 * [report] is the full report, conflicts and all, so the caller can say what overlapped without
 * asking again.
 */
class MergeRefused(val report: MergeReport) :
    IllegalStateException("merge refused: ${report.conflicts.size} conflict(s)")

/**
 * The destination changed between the plan being built and the plan being applied, in a way that
 * changes what the plan decided — and is **still** conflict-free, which is what makes this a
 * separate answer from [MergeRefused]. A change that introduced a conflict is reported as the
 * conflict it is.
 *
 * [report] is the *fresh* plan's report — what a merge would do now — so a caller can retry against
 * it directly. Over the loopback API the two calls are microseconds apart and this is nearly
 * unreachable; it is the guard for a future screen where the owner reviews a plan and applies it
 * minutes later (#44 slice E).
 */
class MergePlanStale(val report: MergeReport) :
    IllegalStateException("the destination changed since this plan was built")
