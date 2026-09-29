package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.Asset
import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.AssetSuccession
import com.loosecannon.servicetag.core.model.AssetTree
import com.loosecannon.servicetag.core.model.EventProfile
import com.loosecannon.servicetag.core.model.GroupId
import com.loosecannon.servicetag.core.model.HealthAggregation
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.MaintenanceSchedule
import com.loosecannon.servicetag.core.model.MeasurementDefinition
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.ScheduleStatus
import com.loosecannon.servicetag.core.model.SeasonMode
import com.loosecannon.servicetag.core.model.ServicePolicy
import com.loosecannon.servicetag.core.model.SuccessionProblem
import com.loosecannon.servicetag.core.model.TagBinding
import com.loosecannon.servicetag.core.model.TagId
import com.loosecannon.servicetag.core.model.TagStatus
import com.loosecannon.servicetag.core.model.TagTarget
import com.loosecannon.servicetag.core.model.successionProblems
import com.loosecannon.servicetag.core.ports.AssetLoanRepository
import com.loosecannon.servicetag.core.ports.AssetRepository
import com.loosecannon.servicetag.core.ports.AssetSuccessionRepository
import com.loosecannon.servicetag.core.ports.Clock
import com.loosecannon.servicetag.core.ports.DefinitionRepository
import com.loosecannon.servicetag.core.ports.GroupRepository
import com.loosecannon.servicetag.core.ports.IdGenerator
import com.loosecannon.servicetag.core.ports.ProfileRepository
import com.loosecannon.servicetag.core.ports.ScheduleRepository
import com.loosecannon.servicetag.core.ports.TagRepository
import com.loosecannon.servicetag.core.ports.Today
import com.loosecannon.servicetag.core.ports.TransferRecordRepository
import com.loosecannon.servicetag.core.ports.UnitOfWork
import com.loosecannon.servicetag.core.schedule.BoundaryKind
import com.loosecannon.servicetag.core.schedule.SeasonPhase
import com.loosecannon.servicetag.core.transfer.AssetTransferredOut
import java.time.LocalDate

/**
 * #86 (C8–C15) — what the Replace form sends: the old asset, the new one's fields, and every carry-forward item the
 * owner ticked. Nothing here is a default the owner did not choose: every flag and set starts false or empty
 * (R86-9), and a tag not in [movedTagIds] stays with the old asset (R86-14).
 *
 * - [retiredOn] is required iff the predecessor is not retired, and ignored once it is (R86-3).
 * - [successor] is the form's own fields; its description and notes are the use case's to fill (C13).
 * - [manualPhase] is required iff [carrySeason] on a MANUAL predecessor (P86-28), with no default.
 * - [scheduleStartOn] is required iff a ticked schedule has a time rule (P86-12).
 */
data class ReplaceDraft(
    val predecessorId: AssetId,
    val retiredOn: String?,
    val successor: AssetCommand,
    val carrySeason: Boolean = false,
    val manualPhase: SeasonPhase? = null,
    val carrySetup: Boolean = false,
    val carryNotes: Boolean = false,
    val scheduleIds: Set<ScheduleId> = emptySet(),
    val scheduleStartOn: String? = null,
    val groupIds: Set<GroupId> = emptySet(),
    val movedTagIds: Set<TagId> = emptySet(),
)

/**
 * #86 (C8) — what the old asset offers to carry forward, read in one snapshot. Nothing in it is ticked: it is the
 * list the form draws, each item unticked (R86-9).
 *
 * - **Eligible** iff the asset is not held and has no successor ([eligible]); archived, retired, component, lent,
 *   DOWN/DEGRADED and case-bearing assets all are (R86-5, R86-6).
 * - [schedules]: its own schedules that are not ARCHIVED, by title (R86-10); a group's are never offered.
 * - [groups]: the groups it holds an open window in that are not archived and have no row, current or removed,
 *   naming a held asset (R86-12), by name.
 * - [setupOffered]: any unarchived definition or profile (R86-11). [seasonOffered]: not YEAR_ROUND, or a break
 *   (R86-13). [notesOffered]: a description or notes (P86-10).
 * - [tags]: its ACTIVE rows (R86-14); a LOST, RETIRED or UNBOUND row, or a 2.6 link tombstone, is never offered.
 * - [parentChoices]: the editor's parent rule — every asset that is not held and is neither the old asset nor under
 *   it, by name; no parent is always allowed. [prefill] is the name, category, location and, iff it passes that
 *   rule, the parent (R86-8).
 * - [childNames] and [openLoan] are named, never moved (R86-6, R86-7).
 */
data class ReplaceOffer(
    val predecessor: Asset,
    val held: Boolean,
    val replacedBy: AssetSuccession?,
    val schedules: List<MaintenanceSchedule>,
    val groups: List<MaintenanceGroup>,
    val setupOffered: Boolean,
    val seasonOffered: Boolean,
    val notesOffered: Boolean,
    val tags: List<TagBinding>,
    val parentChoices: List<Asset>,
    val prefill: AssetCommand,
    val childNames: List<String>,
    val openLoan: Boolean,
) {
    val eligible: Boolean get() = !held && replacedBy == null
}

/**
 * #86 (C9) — one thing that keeps Review disabled. None is auto-corrected: a dependency is named, never ticked for
 * the owner.
 */
sealed interface ReplaceProblem {
    data object NameRequired : ReplaceProblem

    /** `retiredOn`, `purchaseOn`, `inServiceOn`, `warrantyExpiresOn` or `scheduleStartOn`: blank where required, or not ISO. */
    data class BadDate(val field: String) : ReplaceProblem

    /** Any other problem the new asset's fields have, by the editor's own rule. */
    data class Successor(val problem: AssetProblem) : ReplaceProblem

    /** A schedule, group or tag the offer does not hold now, or a parent that fails the parent rule. */
    data class NotOffered(val id: String) : ReplaceProblem

    /** P86-13: a ticked schedule names a meter definition or a profile, and `Readings & actions` is unticked. */
    data class NeedsSetup(val scheduleId: ScheduleId) : ReplaceProblem

    /** P86-14: a ticked PRE_SERVICE schedule, and the season item is unticked or has no boundary to carry. */
    data class NeedsSeason(val scheduleId: ScheduleId) : ReplaceProblem

    /** P86-28 is unanswered. */
    data object PhaseRequired : ReplaceProblem

    /** R86-13a: the replacement date is later than today, typed or stored. */
    data object ReplacedOnAfterToday : ReplaceProblem
}

/**
 * #86 (C10) — every row the draft names, exactly as read: the old asset, its successor row (none), each ticked
 * schedule and group, each moved tag and, iff set-up is ticked, every definition and profile of the old asset. The
 * write reads them again and refuses on any difference.
 */
data class ReplaceSources(
    val predecessor: Asset,
    val successorRow: AssetSuccession?,
    val schedules: List<MaintenanceSchedule>,
    val groups: List<MaintenanceGroup>,
    val tags: List<TagBinding>,
    val definitions: List<MeasurementDefinition>,
    val profiles: List<EventProfile>,
)

/** #86 (C9) — the review. Review, and the confirm, are enabled iff [problems] is empty. */
data class ReplacePlan(
    val problems: List<ReplaceProblem>,
    val sources: ReplaceSources,
    /** The typed retirement date, or the stored one once retired (R86-3); never after today (R86-13a). */
    val replacedOn: String,
)

/** #86 (C10) — the new asset as stored, and the succession row that names both. */
data class ReplaceResult(val successor: Asset, val succession: AssetSuccession)

/**
 * #86 (C10) — the old asset or a row the owner reviewed changed before the confirm, it gained a successor, or the
 * draft no longer passes: nothing was written. The phone says P86-25 and reads the offer again.
 */
class ReplaceStale(val assetId: AssetId) :
    IllegalStateException("asset ${assetId.value} or a row reviewed with it changed; nothing was replaced")

/**
 * #86 (C8–C15; R86-3…14, R86-13a) — **Replace asset**: the old asset gives way to a *different* one. In one write
 * it retires the old asset (unless it already is), creates the new one, copies only what the owner ticked as new
 * rows with new ids and no history, moves only the tags the owner chose, and appends one succession row.
 *
 * - [offer] and [plan] only read, each through its in-transaction body; nothing is written before [run] (R86-4).
 * - [run] opens the one write and re-reads the offer and the plan through the same bodies: a held old asset is
 *   [AssetTransferredOut]; one with a successor, a problem, or any difference from the reviewed sources is
 *   [ReplaceStale]. Then, in order: the retirement (R86-3), the new asset, set-up, schedules, groups, tags, and the
 *   row.
 * - It never nests another use case: it calls their in-transaction bodies (C15), since the transaction is not
 *   re-entrant.
 * - **The old asset is history (R86-C1).** Its one change is the retirement; its schedules, windows, activations,
 *   events, closures, documents, references, conditions, cases, loans and children are never written.
 * - **The date (R86-3, R86-13 amended, R86-13a).** The succession, the retirement and a MANUAL successor's one
 *   activation all carry the replacement date; one later than today is refused.
 */
class ReplaceAsset(
    private val assets: AssetRepository,
    private val schedules: ScheduleRepository,
    private val groups: GroupRepository,
    private val tags: TagRepository,
    private val definitions: DefinitionRepository,
    private val profiles: ProfileRepository,
    private val loans: AssetLoanRepository,
    private val transfers: TransferRecordRepository,
    private val successions: AssetSuccessionRepository,
    private val uow: UnitOfWork,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val today: Today,
    private val retire: RetireAsset,
    private val saveAssetSettings: SaveAssetSettings,
    private val saveSchedule: SaveSchedule,
    private val saveGroup: SaveGroup,
    private val bindTag: BindTag,
) {
    /** C8: what [predecessorId] offers, read in one snapshot. */
    suspend fun offer(predecessorId: AssetId): ReplaceOffer =
        uow.read { offerInTransaction(predecessorId) } ?: throw NoSuchAsset(predecessorId)

    /** C9: the review of [draft]. It writes nothing. */
    suspend fun plan(draft: ReplaceDraft): ReplacePlan = uow.read {
        val offer = offerInTransaction(draft.predecessorId) ?: throw NoSuchAsset(draft.predecessorId)
        planInTransaction(draft, offer)
    }

    /** C10: the one write, after the final confirm of [reviewed]. */
    suspend fun run(draft: ReplaceDraft, reviewed: ReplacePlan): ReplaceResult = uow.write {
        val offer = offerInTransaction(draft.predecessorId) ?: throw ReplaceStale(draft.predecessorId)
        if (offer.held) throw AssetTransferredOut(draft.predecessorId)
        if (offer.replacedBy != null) throw ReplaceStale(draft.predecessorId)
        val fresh = planInTransaction(draft, offer)
        if (fresh.problems.isNotEmpty() || fresh.sources != reviewed.sources || fresh.replacedOn != reviewed.replacedOn) {
            throw ReplaceStale(draft.predecessorId)
        }
        replaceInTransaction(draft, fresh)
    }

    /** C8's body, inside the caller's transaction; null when the asset does not exist. */
    internal suspend fun offerInTransaction(predecessorId: AssetId): ReplaceOffer? {
        val predecessor = assets.get(predecessorId) ?: return null
        val all = assets.all()
        val held = transfers.heldIds()
        val blocked = AssetTree.descendants(all, predecessorId) + predecessorId
        val parentChoices = all
            .filterNot { it.id in blocked || it.id in held }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id.value }))
        return ReplaceOffer(
            predecessor = predecessor,
            held = predecessorId in held,
            replacedBy = successions.replacedBy(predecessorId),
            schedules = schedules.forAsset(predecessorId)
                .filter { it.status != ScheduleStatus.ARCHIVED }
                .sortedWith(compareBy({ it.title }, { it.id.value })),
            groups = groups.forAsset(predecessorId)
                .filter { group -> group.archivedAt == null && group.members.none { it.assetId in held } }
                .sortedWith(compareBy({ it.name }, { it.id.value })),
            setupOffered = definitions.forAsset(predecessorId).any { it.archivedAt == null } ||
                profiles.forAsset(predecessorId).any { it.archivedAt == null },
            seasonOffered = predecessor.seasonMode != SeasonMode.YEAR_ROUND ||
                predecessor.blackoutStartMmdd != null || predecessor.blackoutEndMmdd != null,
            notesOffered = predecessor.description.isNotBlank() || predecessor.notes.isNotBlank(),
            tags = tags.forAsset(predecessorId)
                .filter { it.status == TagStatus.ACTIVE && it.target == TagTarget.AssetTarget(predecessorId) }
                .sortedWith(compareBy({ it.createdAt }, { it.id.value })),
            parentChoices = parentChoices,
            prefill = AssetCommand(
                name = predecessor.name,
                category = predecessor.category,
                location = predecessor.location,
                parentAssetId = predecessor.parentAssetId?.takeIf { parent -> parentChoices.any { it.id == parent } },
            ),
            childNames = AssetTree.children(all, predecessorId).map { it.name },
            openLoan = loans.openFor(predecessorId) != null,
        )
    }

    /** C9's body, inside the caller's transaction, over [offer] read in the same one. */
    internal suspend fun planInTransaction(draft: ReplaceDraft, offer: ReplaceOffer): ReplacePlan {
        val predecessor = offer.predecessor
        val problems = mutableListOf<ReplaceProblem>()
        val replacedOn = predecessor.retiredOn ?: draft.retiredOn?.trim().orEmpty()
        if (predecessor.retiredOn == null && !isIsoDate(replacedOn)) problems += ReplaceProblem.BadDate("retiredOn")
        problems += successorProblems(draft.successor, offer.parentChoices)

        val offered = offer.schedules.associateBy { it.id }
        val ticked = draft.scheduleIds.sortedBy { it.value }
        ticked.filterNot { it in offered }.forEach { problems += ReplaceProblem.NotOffered(it.value) }
        val offeredGroups = offer.groups.map { it.id }.toSet()
        draft.groupIds.sortedBy { it.value }.filterNot { it in offeredGroups }
            .forEach { problems += ReplaceProblem.NotOffered(it.value) }
        val offeredTags = offer.tags.map { it.id }.toSet()
        draft.movedTagIds.sortedBy { it.value }.filterNot { it in offeredTags }
            .forEach { problems += ReplaceProblem.NotOffered(it.value) }

        val carried = ticked.mapNotNull { offered[it] }
        if (carried.any { it.timeInterval != null } && !isIsoDate(draft.scheduleStartOn?.trim().orEmpty())) {
            problems += ReplaceProblem.BadDate("scheduleStartOn")
        }
        // A PRE_SERVICE copy counts back from the new asset's boundary, which only a ticked season with one carries.
        val carriesBoundary = draft.carrySeason && predecessor.boundaryKind() != BoundaryKind.NONE
        carried.forEach { schedule ->
            if (!draft.carrySetup && (schedule.meterDefinitionId != null || schedule.profileId != null)) {
                problems += ReplaceProblem.NeedsSetup(schedule.id)
            }
            if (schedule.servicePolicy == ServicePolicy.PRE_SERVICE && !carriesBoundary) {
                problems += ReplaceProblem.NeedsSeason(schedule.id)
            }
        }
        if (draft.carrySeason && predecessor.seasonMode == SeasonMode.MANUAL && draft.manualPhase == null) {
            problems += ReplaceProblem.PhaseRequired
        }
        // R86-13a: the one read of today — a replacement date later than it is refused, typed or stored.
        if (isIsoDate(replacedOn) && LocalDate.parse(replacedOn) > today.localDate()) {
            problems += ReplaceProblem.ReplacedOnAfterToday
        }
        return ReplacePlan(problems, sourcesOf(draft, predecessor), replacedOn)
    }

    /** The new asset's fields by the editor's rule, the parent judged against the offer's parent choices. */
    private fun successorProblems(cmd: AssetCommand, parentChoices: List<Asset>): List<ReplaceProblem> = try {
        validateAsset(cmd, parentChoices, id = null)
        emptyList()
    } catch (e: AssetValidation) {
        e.problems.map { problem ->
            when (problem) {
                AssetProblem.NameRequired -> ReplaceProblem.NameRequired
                is AssetProblem.BadDate -> ReplaceProblem.BadDate(problem.field)
                AssetProblem.UnknownParent -> ReplaceProblem.NotOffered(cmd.parentAssetId!!.value)
                else -> ReplaceProblem.Successor(problem)
            }
        }
    }

    private suspend fun sourcesOf(draft: ReplaceDraft, predecessor: Asset) = ReplaceSources(
        predecessor = predecessor,
        successorRow = successions.replacedBy(predecessor.id),
        schedules = draft.scheduleIds.sortedBy { it.value }.mapNotNull { schedules.get(it) },
        groups = draft.groupIds.sortedBy { it.value }.mapNotNull { groups.get(it) },
        tags = draft.movedTagIds.sortedBy { it.value }.mapNotNull { tags.get(it) },
        definitions = if (draft.carrySetup) definitions.forAsset(predecessor.id).sortedBy { it.id.value } else emptyList(),
        profiles = if (draft.carrySetup) profiles.forAsset(predecessor.id).sortedBy { it.id.value } else emptyList(),
    )

    /** C10's steps 2–8, inside the one write, over a [plan] that has just passed again. */
    private suspend fun replaceInTransaction(draft: ReplaceDraft, plan: ReplacePlan): ReplaceResult {
        val now = clock.nowMillis()
        val predecessor = plan.sources.predecessor
        // (2) R86-3: retired with the replacement date, unless it already is — then its row is not written at all.
        if (predecessor.retiredOn == null) retire.retireInTransaction(predecessor, plan.replacedOn, now)
        // (3) C13: a new identity; a MANUAL season's one activation is dated the replacement date (R86-13 amended).
        var successor = saveAssetSettings.saveInTransaction(
            id = null,
            cmd = settingsFor(draft, predecessor),
            templateKey = null,
            activationDay = LocalDate.parse(plan.replacedOn),
        )
        // (4) C12: set-up, iff ticked, with the old asset's template key as provenance.
        val clone = if (draft.carrySetup) {
            cloneSetup(successor.id, plan.sources, now, ids, definitions, profiles)
        } else {
            SetupClone.NONE
        }
        if (draft.carrySetup && predecessor.templateKey != null && successor.templateKey == null) {
            successor = successor.copy(templateKey = predecessor.templateKey, updatedAt = now)
            assets.upsert(successor)
        }
        // (5) C11: each ticked schedule, in id order, as a create.
        plan.sources.schedules.forEach { source ->
            saveSchedule.createInTransaction(copyOf(source, successor.id, draft.scheduleStartOn, clone))
        }
        // (6) C13: each ticked group keeps every open window by id and gains one for the new asset.
        plan.sources.groups.forEach { group -> saveGroup.saveInTransaction(group.id, joining(group, successor.id)) }
        // (7) C14: each moved tag retargeted in place; no tag is written to.
        plan.sources.tags.forEach { tag ->
            bindTag.bindInTransaction(tag.payloadFormat, tag.payloadKey, TagTarget.AssetTarget(successor.id), label = null)
        }
        // (8) The row, dated the old asset's stored retirement; the new row goes last, so any problem names it (NT-3).
        val row = AssetSuccession(
            id = ids.newId(),
            predecessorAssetId = predecessor.id,
            successorAssetId = successor.id,
            replacedOn = checkNotNull(assets.get(predecessor.id)?.retiredOn) { "the old asset is retired by now" },
            createdAt = now,
        )
        check(row.predecessorAssetId != row.successorAssetId) { "a succession never names one asset twice" }
        check(successionProblems(successions.all() + row).none { it.names(row.id) }) {
            "succession ${row.id} breaks the one-successor rule or makes a cycle"
        }
        successions.append(row)
        return ReplaceResult(successor, row)
    }

    private fun settingsFor(draft: ReplaceDraft, predecessor: Asset): AssetSettingsCommand {
        val mode = predecessor.seasonMode
        return AssetSettingsCommand(
            asset = draft.successor.copy(
                description = if (draft.carryNotes) predecessor.description else "",
                notes = if (draft.carryNotes) predecessor.notes else "",
            ),
            seasonMode = if (draft.carrySeason) {
                SeasonModeCommand(
                    seasonMode = mode,
                    seasonStartMmdd = predecessor.seasonStartMmdd.takeIf { mode == SeasonMode.CALENDAR },
                    seasonEndMmdd = predecessor.seasonEndMmdd.takeIf { mode == SeasonMode.CALENDAR },
                    manualPhase = draft.manualPhase.takeIf { mode == SeasonMode.MANUAL },
                )
            } else {
                SeasonModeCommand(SeasonMode.YEAR_ROUND)
            },
            maintenanceBreak = if (draft.carrySeason) {
                BreakCommand(predecessor.blackoutStartMmdd, predecessor.blackoutEndMmdd)
            } else {
                BreakCommand(null, null)
            },
            healthPolicy = HealthPolicyCommand(HealthAggregation.WORST),
            warrantyReminder = null,
        )
    }

    /** C11: the configuration, never the history; one reviewed anchor, and no meter anchor. */
    private fun copyOf(source: MaintenanceSchedule, successor: AssetId, startOn: String?, clone: SetupClone) =
        ScheduleCommand(
            targetAssetId = successor,
            targetGroupId = null,
            title = source.title,
            description = source.description,
            timeInterval = source.timeInterval,
            timeUnit = source.timeUnit,
            timeBasis = source.timeBasis,
            anchorOn = if (source.timeInterval != null) startOn?.trim() else null,
            leadDays = source.leadDays,
            meterDefinitionId = source.meterDefinitionId?.let { clone.definitions.getValue(it) },
            meterInterval = source.meterInterval,
            anchorMeter = null,
            meterLead = source.meterLead,
            servicePolicy = source.servicePolicy,
            policyOffsetDays = source.policyOffsetDays,
            completionMode = source.completionMode,
            profileId = source.profileId?.let { clone.profiles.getValue(it) },
            remindersEnabled = source.remindersEnabled,
            providers = source.providers,
        )

    /** C13 (MN-2): every open window kept by id — an omitted one would be closed — and one new window, last. */
    private fun joining(group: MaintenanceGroup, successor: AssetId) = GroupCommand(
        name = group.name,
        description = group.description,
        members = group.members.filter { it.removedAt == null }.map { GroupMemberInput(it.assetId, it.id, it.sortOrder) } +
            GroupMemberInput(successor, id = null, sortOrder = (group.members.maxOfOrNull { it.sortOrder } ?: -1) + 1),
    )
}

/** Whether this problem is about the row [id]. */
private fun SuccessionProblem.names(id: String): Boolean = when (this) {
    is SuccessionProblem.SelfLink -> this.id == id
    is SuccessionProblem.PredecessorTaken -> this.id == id
    is SuccessionProblem.SuccessorTaken -> this.id == id
    is SuccessionProblem.Cycle -> id in ids
}
