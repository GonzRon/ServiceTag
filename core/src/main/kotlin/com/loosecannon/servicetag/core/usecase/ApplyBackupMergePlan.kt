package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.merge.MergePlan
import com.loosecannon.servicetag.core.merge.MergeReport
import com.loosecannon.servicetag.core.merge.mergePlanOf
import com.loosecannon.servicetag.core.merge.mergeSnapshotOf
import com.loosecannon.servicetag.core.merge.storedBytesOf
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.ClosureRepository
import com.loosecannon.servicetag.core.ports.ConditionRepository
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.EventRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.HealthSubjectRepository
import com.loosecannon.servicetag.core.ports.LinkRepository
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ReferenceRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.SeasonActivationRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork

/**
 * Applies an accepted, conflict-free plan — and refuses everything else (1.1.0, #46; #44's
 * "apply accepted plan in one Room transaction").
 *
 * **It does not execute the plan it is handed.** It re-reads the snapshot *inside its own write
 * transaction*, re-runs the pure planner over the archive the plan carries, and applies only that
 * fresh result. So there is no window between deciding and writing: a plan is never applied to a
 * destination it was not just checked against.
 *
 * **A conflict is named before staleness.** On the rebuilt plan, `applicable` is checked first and
 * the fingerprint second. That order is load-bearing: the fingerprint digests every decision, so
 * *any* destination change that introduces a conflict necessarily changes a verdict and therefore
 * the fingerprint — checking it first would make a conflict-on-rebuild unreachable and would answer
 * a real conflict with a stale-plan report that carries no conflict list. In this order
 * [MergeRefused] answers every conflicted rebuild, and [MergePlanStale] is reserved for its one
 * real case: a destination that changed, is still conflict-free, and no longer matches what was
 * accepted.
 *
 * The snapshot is re-read with the repositories directly and **not** through a nested `uow.read`:
 * [UnitOfWork.read]'s own contract says writing inside it is illegal, and a write transaction
 * already gives one consistent view. The two store questions are asked before the transaction
 * opens, for the same reason [BuildBackupMergePlan] asks them there — `open` is a provider round
 * trip, and it asks `store()` once so both questions are about one state of the store. One
 * consequence, stated rather than hidden: a file that vanishes **or is replaced** between that read
 * and the write still yields an INSERT — a replacement being the likelier of the two on a synced
 * folder, and the one that leaves a row whose recorded sha256 no longer describes what is stored —
 * because there is no transaction that spans Room and a document provider. `docs/api/v1.md`
 * records it.
 *
 * Every refusal happens before the first row is written, and any that did not would roll back with
 * the transaction. So a refused merge leaves this install byte for byte as it was, which is #44's
 * acceptance criteria 13 and 14.
 *
 * **The post-apply rebuild is total, deliberately.** An imported event touches its own schedule,
 * every schedule of its asset through the current meter reading, and every group schedule whose
 * required set contains that asset; an imported membership row changes required sets; an imported
 * closure terminates a round; an imported meter reading moves a threshold. Rather than enumerate
 * that closure, [rebuildAll] recomputes every schedule in the database, inside this transaction,
 * after the last write loop. At this scale it is cheap and provably complete, and a refused or
 * failed apply rolls the recompute back with everything else. It is a **seam** rather than a direct
 * call to the recompute function because the derived state that it writes never appears in a plan
 * and the engine that computes it is not this layer's concern: what belongs here is that it is
 * invoked once, inside, and last.
 */
class ApplyBackupMergePlan(
    private val assets: AssetRepository,
    private val groups: GroupRepository,
    private val tags: TagRepository,
    private val links: LinkRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val schedules: ScheduleRepository,
    private val closures: ClosureRepository,
    private val events: EventRepository,
    private val attachments: AttachmentRepository,
    private val references: ReferenceRepository,
    /**
     * The three 1.4 stores — manual season activations, conditions and health subjects — held for
     * the format-8 archive. Wired here in 1.4's first change so the constructor does not move
     * again when the archive starts carrying them; format 7 reads and writes none of them.
     */
    private val seasonActivations: SeasonActivationRepository,
    private val conditions: ConditionRepository,
    private val healthSubjects: HealthSubjectRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
    /**
     * Recomputes the derived state of **every** schedule. It runs inside this apply's transaction,
     * after the last write loop, exactly once — see the class KDoc for why it is total and why it
     * is a seam rather than a direct call.
     */
    private val rebuildAll: suspend () -> Unit,
) {
    suspend fun run(plan: MergePlan): MergeReport {
        // The cheap refusal first, so a plan the caller already knows is conflicted never opens a
        // transaction at all.
        if (!plan.applicable) throw MergeRefused(plan.report())

        val store = storage.store()
        val configured = store != null
        val stored = storedBytesOf(plan.backup, store)
        return uow.write {
            val fresh = mergePlanOf(
                plan.backup,
                mergeSnapshotOf(
                    assets, groups, tags, links, definitions, profiles, schedules, closures,
                    events, attachments, references, stored, configured,
                ),
            )
            // Order matters — see the class KDoc.
            if (!fresh.applicable) throw MergeRefused(fresh.report())
            if (fresh.fingerprint != plan.fingerprint) throw MergePlanStale(fresh.report())

            // Field order is write order, and every list is ordered within itself.
            fresh.writes.assets.forEach { assets.upsert(it) }
            fresh.writes.groups.forEach { groups.upsert(it) }
            fresh.writes.definitions.forEach { definitions.upsert(it) }
            fresh.writes.profiles.forEach { profiles.upsert(it) }
            fresh.writes.schedules.forEach { schedules.upsert(it) }
            fresh.writes.closures.forEach { closures.insert(it) }
            fresh.writes.links.forEach { links.upsert(it) }
            fresh.writes.tags.forEach { tags.upsert(it) }
            fresh.writes.events.forEach { events.upsert(it) }
            fresh.writes.attachments.forEach { attachments.upsert(it) }
            fresh.writes.references.forEach { references.upsert(it) }

            // After every write, inside the same transaction, once.
            rebuildAll()

            fresh.report()
        }
    }
}
