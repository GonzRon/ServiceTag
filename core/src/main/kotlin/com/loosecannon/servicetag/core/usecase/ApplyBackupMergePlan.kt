package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.backup.BackupData
import com.loosecannon.servicetag.core.backup.toDto
import com.loosecannon.servicetag.core.merge.MergeSnapshot
import com.loosecannon.servicetag.core.model.AssetLoan
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.AttachmentOwner
import com.loosecannon.servicetag.core.model.ExternalLink
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.ScheduleTarget
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.TransferKind
import com.loosecannon.servicetag.core.transfer.DroppedRows
import com.loosecannon.servicetag.core.transfer.TransferGraph
import com.loosecannon.servicetag.core.merge.MergePlan
import com.loosecannon.servicetag.core.merge.MergeReport
import com.loosecannon.servicetag.core.merge.mergePlanOf
import com.loosecannon.servicetag.core.merge.mergeSnapshotOf
import com.loosecannon.servicetag.core.merge.storedBytesOf
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AttachmentRepository
import com.loosecannon.servicetag.core.ports.AttachmentStorage
import com.loosecannon.servicetag.core.ports.CategoryRepository
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
import com.loosecannon.servicetag.core.ports.ServiceCaseEntryRepository
import com.loosecannon.servicetag.core.ports.ServiceCaseRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.AssetSuccessionRepository
import com.loosecannon.servicetag.core.ports.AssetSupplyRepository
import com.loosecannon.servicetag.core.ports.InstalledComponentRepository
import com.loosecannon.servicetag.core.ports.SupplyItemRepository
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
 *
 * **It writes [com.loosecannon.servicetag.core.merge.MergeWrites] and nothing else** — #74's
 * categories included. The promotions a merge makes (the category rows its accepted assets need,
 * and each asset's canonical spelling) are *planned* by the rebuilt plan, so they are in its
 * decisions, its tallies and its fingerprint; nothing is promoted here after the writes. A local save
 * between the plan and this apply that adds a key the plan inserts or synthesises therefore changes a
 * decision, and the apply is refused as stale. A spelling-only rename in that window changes no
 * decision: the apply writes the fresh local spelling, which the report never showed — the catalog's
 * spelling (R74-3), not an incoming one.
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
    /** The three 1.4 stores — manual season activations, conditions and health subjects. */
    private val seasonActivations: SeasonActivationRepository,
    private val conditions: ConditionRepository,
    private val healthSubjects: HealthSubjectRepository,
    /** #74 — the owner's own categories: read into the snapshot, written first. */
    private val categories: CategoryRepository,
    /** #79 — the case headers and their timelines (format 12). */
    private val serviceCases: ServiceCaseRepository,
    private val caseEntries: ServiceCaseEntryRepository,
    /** #72 — the loans (format 13). */
    private val loans: AssetLoanRepository,
    /** #77 — the transfer records (format 14). */
    private val transfers: TransferRecordRepository,
    /**
     * #86 — the successions (format 15): the **raw** port, never the write guard's (C6, MJ-2). A transfer back writes
     * the kept rows through it — they existed before the transaction, so I8 (no **new** row names a held asset) does
     * not apply, and the other end may still be held — and M2 already refuses a held end in the plan this apply
     * rebuilds inside its own write.
     */
    private val successions: AssetSuccessionRepository,
    /** #15 — the SupplyItems with their specifications, and the applicability rows (format 18). */
    private val supplyItems: SupplyItemRepository,
    private val assetSupplies: AssetSupplyRepository,
    /** #47 — the installed components, each with its composition (format 19). */
    private val installedComponents: InstalledComponentRepository,
    private val storage: AttachmentStorage,
    private val uow: UnitOfWork,
    /**
     * Recomputes the derived state of **every** schedule. It runs inside this apply's transaction,
     * after the last write loop, exactly once — see the class KDoc for why it is total and why it
     * is a seam rather than a direct call.
     */
    private val rebuildAll: suspend () -> Unit,
) {
    suspend fun run(plan: MergePlan): MergeReport = runReturning(plan, emptySet()).report

    /**
     * [returning] (#77, C15; R77-25, R77-B3-RETURN) is a Transfer Pack's return, as [ImportTransferPack] decided it —
     * empty for every other merge, which [run] is, unchanged. For a return the plan runs on the snapshot without those
     * assets' stale local graph ([ReturnScope]); in the same write their IN records are appended **first**, closing
     * this phone's OUT (the write guard refuses anything else), that graph is removed through the ports' deletes, the
     * plan's rows are inserted, and the sender-local returned loans and 2.6 link rows with their tags are written back
     * unchanged as history, and so are #86's successions naming a returning asset at either end (C6). It sweeps nothing: [AppliedMerge.removedLocators] are the removed documents' bytes the
     * pack does not name, for the caller to sweep **after** it knows the write committed (MJ-1).
     */
    suspend fun runReturning(plan: MergePlan, returning: Set<AssetId>): AppliedMerge {
        // The cheap refusal first, so a plan the caller already knows is conflicted never opens a
        // transaction at all.
        if (!plan.applicable) throw MergeRefused(plan.report())

        val store = storage.store()
        val configured = store != null
        val stored = storedBytesOf(plan.backup, store)
        var removed: ReturnScope? = null
        val report = uow.write {
            val scope = ReturnScope.of(
                mergeSnapshotOf(
                    assets, groups, tags, links, definitions, profiles, schedules, closures,
                    events, attachments, references, seasonActivations, conditions, healthSubjects,
                    categories, serviceCases, caseEntries, loans, transfers, successions, supplyItems, assetSupplies,
                    installedComponents, stored, configured,
                ),
                returning,
            )
            val fresh = mergePlanOf(plan.backup, scope.snapshot, returning)
            // Order matters — see the class KDoc.
            if (!fresh.applicable) throw MergeRefused(fresh.report())
            if (fresh.fingerprint != plan.fingerprint) throw MergePlanStale(fresh.report())

            // #77 (C15): a return's INs first, then its stale graph out — every other merge has neither.
            val (returns, records) = fresh.writes.transfers
                .partition { it.kind == TransferKind.IN && it.assetId in returning }
            returns.forEach { transfers.append(it) }
            scope.groups.forEach { groups.delete(it) }
            scope.tags.forEach { tags.delete(it) }
            scope.assets.forEach { assets.delete(it) }

            // Field order is write order, and every list is ordered within itself.
            fresh.writes.categories.forEach { categories.upsert(it) }
            fresh.writes.assets.forEach { assets.upsert(it) }
            // #77 (C15, R77-25 (a)): the history a return keeps, back beside the asset the pack brought.
            scope.keptLinks.forEach { links.upsert(it) }
            scope.keptLinkTags.forEach { tags.upsert(it) }
            scope.keptLoans.forEach { loans.upsert(it) }
            // #86 (C6, Hazard 2; MJ-2): the successions the cascades above took, back unchanged, through the raw port.
            scope.keptSuccessions.forEach { successions.append(it) }
            // #15 (C11): the SupplyItems, each with its specifications, after the assets and before the applicability
            // rows whose `supply_id` foreign key names them (RESTRICT) — and before the material lines that link them,
            // though a link is soft and needs no order. The applicability rows after both their owners.
            fresh.writes.supplyItems.forEach { supplyItems.upsert(it) }
            fresh.writes.assetSupplies.forEach { assetSupplies.insert(it) }
            // #47 (C12): the installed components, each with its composition, straight after the applicability rows —
            // after their asset and every SupplyItem they name (`supply_id` RESTRICT, on the row and on each entry), and
            // parents first, as the plan lists them (`parent_id`).
            fresh.writes.installedComponents.forEach { installedComponents.insert(it) }
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
            fresh.writes.seasonActivations.forEach { seasonActivations.insert(it) }
            fresh.writes.conditions.forEach { conditions.insert(it) }
            fresh.writes.healthSubjects.forEach { healthSubjects.upsert(it) }
            // #79: the case headers after their assets and the events, then each case's timeline.
            fresh.writes.serviceCases.forEach { serviceCases.upsert(it) }
            fresh.writes.caseEntries.forEach { caseEntries.insert(it) }
            // #72: the loans after their assets; the plan held each asset to one open loan.
            fresh.writes.loans.forEach { loans.upsert(it) }
            // #86: the successions after both their assets and the loans, before the records (C4).
            fresh.writes.successions.forEach { successions.append(it) }
            // #77: the transfer records last of all, after every row they describe (C10).
            records.forEach { transfers.append(it) }

            // After every write, inside the same transaction, once.
            rebuildAll()

            removed = scope
            fresh.report()
        }
        val named = plan.backup.data.attachments.map { it.storageLocator }.toSet()
        return AppliedMerge(report, removed?.locators.orEmpty().filterNot { it in named })
    }
}

/**
 * What [ApplyBackupMergePlan.runReturning] committed: the report, and (#77, C15) the removed documents' locators the
 * pack does not name — bytes the caller sweeps once the write is known to have committed. Empty for a merge.
 */
class AppliedMerge(val report: MergeReport, val removedLocators: List<String>)

/**
 * #77 (C15; R77-25 (a), the 2.6 tombstone rule) — what a Transfer Pack's return replaces here, read from one snapshot:
 * every row of a returning asset's local graph (C3's drop set for them) and every local group with a row, current or
 * removed, naming one — with their schedules and closures — are out of [snapshot], so the pack's rows plan as inserts.
 *
 * Removed through the ports: [groups], then the asset-targeted [tags], then the [assets] themselves, children first;
 * the asset's other rows go by the schema's cascades. Kept as history and written back unchanged after the pack's
 * asset: [keptLoans] (the sender-local returned loans, R77-25 (a)), [keptLinks] and [keptLinkTags] (the 2.6
 * tombstones, never re-purposed or dropped), and #86's [keptSuccessions] — every local succession naming a returning
 * asset at either end, which the cascades take (C6, Hazard 2); the pack never carries one (SENDER_ONLY), so no second
 * copy can collide. [locators] are the removed documents' bytes, for the sweep.
 *
 * A staying row that names a removed one would be changed by those cascades, and a removed group that also names a
 * staying asset would take its rows with it (mn-1), so either refuses the return ([IllegalStateException]) — at the
 * preview, before anything is staged or written (P77-52).
 */
internal class ReturnScope private constructor(
    val snapshot: MergeSnapshot,
    val groups: List<GroupId>,
    val tags: List<TagId>,
    val assets: List<AssetId>,
    val keptLinks: List<ExternalLink>,
    val keptLinkTags: List<TagBinding>,
    val keptLoans: List<AssetLoan>,
    val keptSuccessions: List<AssetSuccession>,
    val locators: List<String>,
) {
    companion object {
        fun of(full: MergeSnapshot, returning: Set<AssetId>): ReturnScope {
            if (returning.isEmpty()) {
                return ReturnScope(
                    full, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                )
            }
            val groups = full.groups.filter { g -> g.members.any { it.assetId in returning } }
            val groupIds = groups.mapTo(HashSet()) { it.id }
            val schedules = full.schedules.filter { s ->
                when (val target = s.target) {
                    is ScheduleTarget.AssetTarget -> target.assetId in returning
                    is ScheduleTarget.GroupTarget -> target.groupId in groupIds
                }
            }.mapTo(HashSet()) { it.id }
            val events = full.events.filter { it.assetId in returning }.mapTo(HashSet()) { it.id }
            val cases = full.serviceCases.filter { it.assetId in returning }.mapTo(HashSet()) { it.id }
            val attachments = full.attachments.filter { a ->
                when (val owner = a.owner) {
                    is AttachmentOwner.OfAsset -> owner.assetId in returning
                    is AttachmentOwner.OfEvent -> owner.eventId in events
                }
            }
            val keptLinks = full.links.filter { it.assetId in returning }
            val linkIds = keptLinks.mapTo(HashSet()) { it.id }
            val removedTags = full.tags.filter { (it.target as? TagTarget.AssetTarget)?.assetId in returning }
            val keptLinkTags = full.tags.filter { (it.target as? TagTarget.LinkTarget)?.linkId in linkIds }
            val reduced = full.copy(
                assets = full.assets.filterNot { it.id in returning },
                groups = full.groups.filterNot { it.id in groupIds },
                tags = full.tags - removedTags.toSet(),
                definitions = full.definitions.filterNot { it.assetId in returning },
                profiles = full.profiles.filterNot { it.assetId in returning },
                schedules = full.schedules.filterNot { it.id in schedules },
                closures = full.closures.filterNot { it.scheduleId in schedules },
                events = full.events.filterNot { it.id in events },
                attachments = full.attachments - attachments.toSet(),
                references = full.references.filterNot { it.assetId in returning },
                seasonActivations = full.seasonActivations.filterNot { it.assetId in returning },
                conditions = full.conditions.filterNot { it.assetId in returning },
                healthSubjects = full.healthSubjects.filterNot { it.assetId in returning },
                serviceCases = full.serviceCases.filterNot { it.id in cases },
                caseEntries = full.caseEntries.filterNot { it.caseId in cases },
                // #15 (C13, C-4): the applicability rows go with their asset — the delete's cascade takes them, and the
                // pack's rows plan as inserts. `supplyItems` is not named: global, the copy keeps every one.
                assetSupplies = full.assetSupplies.filterNot { it.assetId in returning },
                // #47 (C13): the installed components go with their asset, current and removed — the delete's cascade
                // takes them and their entries, and the pack's rows plan as inserts, so a row closed, replaced or
                // recomposed on the borrowing phone lands instead of refusing the return.
                installedComponents = full.installedComponents.filterNot { it.assetId in returning },
            )
            val dropped = DroppedRows(
                assets = returning.mapTo(HashSet()) { it.value },
                groups = groupIds.mapTo(HashSet()) { it.value },
                schedules = schedules.mapTo(HashSet()) { it.value },
                events = events.mapTo(HashSet()) { it.value },
                links = emptySet(),
                cases = cases.mapTo(HashSet()) { it.value },
                definitions = full.definitions.filter { it.assetId in returning }.mapTo(HashSet()) { it.id.value },
                profiles = full.profiles.filter { it.assetId in returning }.mapTo(HashSet()) { it.id.value },
            )
            val kept = BackupData(
                assets = reduced.assets.map { it.toDto() },
                nfcTags = emptyList(),
                externalLinks = emptyList(),
                maintenanceGroups = reduced.groups.map { it.toDto() },
                maintenanceSchedules = reduced.schedules.map { it.toDto() },
                eventProfiles = reduced.profiles.map { it.toDto() },
                assetEvents = reduced.events.map { it.toDto() },
                healthSubjects = reduced.healthSubjects.map { it.toDto() },
            )
            val entangled = TransferGraph.entangledRefs(kept, dropped)
            // mn-1: a removed group must name returning assets only — one that also names a staying asset, held here
            // or not, would take that asset's membership rows and the round's closures with it.
            val shared = groups.filter { g -> g.members.any { it.assetId !in returning } }.map { it.id.value }
            check(entangled.isEmpty() && shared.isEmpty()) {
                "a staying row names a returning asset's graph: $entangled; groups also naming a staying asset: $shared"
            }
            return ReturnScope(
                snapshot = reduced,
                groups = groups.map { it.id },
                tags = removedTags.map { it.id },
                assets = AssetTree.parentsFirst(full.assets.filter { it.id in returning }).asReversed().map { it.id },
                keptLinks = keptLinks,
                keptLinkTags = keptLinkTags,
                keptLoans = full.loans.filter { it.assetId in returning },
                keptSuccessions = full.successions.filter { it.predecessorAssetId in returning || it.successorAssetId in returning },
                locators = attachments.map { it.storageLocator },
            )
        }
    }
}
