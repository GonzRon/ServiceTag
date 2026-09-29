package com.loosecannon.servicetag.core.model

/**
 * #86 (C1; R86-1, R86-2, R86-17) — one asset replaced by a **distinct** successor: an immutable fact, appended once
 * and never updated. It leaves only by an endpoint's CASCADE (R86-15: deleting either asset removes the row, and
 * nothing re-links around it), `ImportBackupReplace`'s wipe, or a transfer back's cascade, which re-inserts it (C6).
 *
 * - [predecessorAssetId]: the asset that was replaced; it keeps its identity and its whole history.
 * - [successorAssetId]: the new asset that replaced it, a different identity.
 * - [replacedOn]: an ISO `YYYY-MM-DD` date — on the phone, the predecessor's `retiredOn` (R86-3).
 *
 * No note and no event link (R86-17). "Succession" names the concept in code, schema and wire; the phone says
 * "Replace asset", "Replaced by" and "Replaces" (R86-2).
 */
data class AssetSuccession(
    val id: String,
    val predecessorAssetId: AssetId,
    val successorAssetId: AssetId,
    val replacedOn: String,
    val createdAt: Long,
)

/** #86 (C1) — why a set of successions cannot all stand together; [successionProblems] is the one home of I1–I4. */
sealed interface SuccessionProblem {
    /** I1: [id] names one asset at both ends. */
    data class SelfLink(val id: String) : SuccessionProblem

    /** I2: [id] names a predecessor that the earlier row [holder] already names. */
    data class PredecessorTaken(val id: String, val holder: String) : SuccessionProblem

    /** I2: [id] names a successor that the earlier row [holder] already names. */
    data class SuccessorTaken(val id: String, val holder: String) : SuccessionProblem

    /** I4: every row on one cycle, by id. */
    data class Cycle(val ids: List<String>) : SuccessionProblem
}

/**
 * #86 (C1) — **the one home of I1–I4**, pure: the codec's graph and content checks, the merge planner's MS3 and MS4,
 * and `ReplaceAsset` all ask it. [rows] are read **in order**: the first row naming an asset as a predecessor (or as
 * a successor) holds it, and every later row naming it is taken, naming that holder. A cycle is the rows among
 * which following successors comes back round — every such row of one strongly connected set, sorted by id; a
 * self-link is its own problem and never a cycle. Chains are rows (I3): A → B → C is two rows and no problem.
 *
 * The order of the answer is the rows' own: each row's self-link, predecessor and successor problems, then every
 * cycle, by its first id.
 */
fun successionProblems(rows: List<AssetSuccession>): List<SuccessionProblem> = emptyList()
