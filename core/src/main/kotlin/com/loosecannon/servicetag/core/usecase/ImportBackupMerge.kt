package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.merge.MergeReport

/**
 * The two-call merge, as one thing to wire (1.1.0, #46).
 *
 * [plan] decides and writes nothing; [run] decides and then applies, refusing with [MergeRefused]
 * if the plan holds a conflict and with [MergePlanStale] if the destination moved under it. The
 * semantics are the plan's — this is six lines of façade so that `AppGraph` and the loopback API
 * have one name to hold, exactly as #46 asked for.
 */
class ImportBackupMerge(
    private val build: BuildBackupMergePlan,
    private val apply: ApplyBackupMergePlan,
) {
    /** What a merge would do. Never mutates. */
    suspend fun plan(bytes: ByteArray): MergeReport = build.run(bytes).report()

    /**
     * Plan, then apply the fresh plan the apply itself rebuilds. The apply streams and hashes every
     * named attachment a second time on purpose: reusing this plan's map would reopen the window
     * the rebuild closes.
     */
    suspend fun run(bytes: ByteArray): MergeReport = apply.run(build.run(bytes))
}
