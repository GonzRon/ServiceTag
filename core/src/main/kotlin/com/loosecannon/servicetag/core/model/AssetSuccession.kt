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
fun successionProblems(rows: List<AssetSuccession>): List<SuccessionProblem> {
    val problems = mutableListOf<SuccessionProblem>()
    val predecessorHolder = HashMap<AssetId, String>()
    val successorHolder = HashMap<AssetId, String>()
    for (row in rows) {
        if (row.predecessorAssetId == row.successorAssetId) problems += SuccessionProblem.SelfLink(row.id)
        predecessorHolder.putIfAbsent(row.predecessorAssetId, row.id)?.let {
            problems += SuccessionProblem.PredecessorTaken(row.id, it)
        }
        successorHolder.putIfAbsent(row.successorAssetId, row.id)?.let {
            problems += SuccessionProblem.SuccessorTaken(row.id, it)
        }
    }
    return problems + cyclesOf(rows.filter { it.predecessorAssetId != it.successorAssetId })
}

/** I4's walk: Tarjan's strongly connected sets over the rows' edges, iteratively, so a long chain never recurses. */
private fun cyclesOf(rows: List<AssetSuccession>): List<SuccessionProblem.Cycle> {
    val edges = LinkedHashMap<AssetId, MutableList<AssetId>>()
    rows.forEach { edges.getOrPut(it.predecessorAssetId) { mutableListOf() } += it.successorAssetId }
    val index = HashMap<AssetId, Int>()
    val low = HashMap<AssetId, Int>()
    val onStack = HashSet<AssetId>()
    val stack = ArrayDeque<AssetId>()
    val components = mutableListOf<Set<AssetId>>()
    var next = 0
    for (start in edges.keys) {
        if (start in index) continue
        val work = ArrayDeque<Pair<AssetId, Int>>()
        work.addLast(start to 0)
        while (work.isNotEmpty()) {
            val (node, at) = work.removeLast()
            if (at == 0) {
                index[node] = next
                low[node] = next
                next += 1
                stack.addLast(node)
                onStack += node
            }
            val outs = edges[node].orEmpty()
            if (at < outs.size) {
                work.addLast(node to at + 1)
                val target = outs[at]
                if (target !in index) {
                    work.addLast(target to 0)
                } else if (target in onStack) {
                    low[node] = minOf(low.getValue(node), index.getValue(target))
                }
                continue
            }
            if (low[node] == index[node]) {
                val component = LinkedHashSet<AssetId>()
                do {
                    val member = stack.removeLast()
                    onStack -= member
                    component += member
                } while (member != node)
                if (component.size > 1) components += component
            }
            work.lastOrNull()?.let { (parent, _) -> low[parent] = minOf(low.getValue(parent), low.getValue(node)) }
        }
    }
    return components
        .map { set -> rows.filter { it.predecessorAssetId in set && it.successorAssetId in set }.map { it.id }.sorted() }
        .sortedBy { it.first() }
        .map { SuccessionProblem.Cycle(it) }
}
