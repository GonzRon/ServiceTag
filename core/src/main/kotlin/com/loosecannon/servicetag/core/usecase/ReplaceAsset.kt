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

/** #86 (B2) — tests first: the API only; every answer is empty and the write is not there yet. */
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
    suspend fun offer(predecessorId: AssetId): ReplaceOffer {
        val predecessor = assets.get(predecessorId) ?: throw NoSuchAsset(predecessorId)
        return ReplaceOffer(
            predecessor, held = false, replacedBy = null, schedules = emptyList(), groups = emptyList(),
            setupOffered = false, seasonOffered = false, notesOffered = false, tags = emptyList(),
            parentChoices = emptyList(), prefill = AssetCommand(name = ""), childNames = emptyList(), openLoan = false,
        )
    }

    suspend fun plan(draft: ReplaceDraft): ReplacePlan {
        val predecessor = assets.get(draft.predecessorId) ?: throw NoSuchAsset(draft.predecessorId)
        val sources = ReplaceSources(predecessor, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        return ReplacePlan(emptyList(), sources, draft.retiredOn.orEmpty())
    }

    suspend fun run(draft: ReplaceDraft, reviewed: ReplacePlan): ReplaceResult = TODO("#86 B2: the one write")
}
