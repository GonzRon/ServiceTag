package com.loosecannon.servicetag.core.usecase

import com.loosecannon.servicetag.core.model.AssetId
import com.loosecannon.servicetag.core.model.GroupMember
import com.loosecannon.servicetag.core.model.MaintenanceGroup
import com.loosecannon.servicetag.core.model.ScheduleId

/**
 * The group editor's raw input, before [SaveGroup] turns it into a stored aggregate.
 *
 * **There is no `addedAt`, `removedAt` or `archivedAt` here**, and that absence is the contract: the
 * temporal fields are server-stamped, so no caller — a form, a route or an MCP tool — can hand in a
 * window, move one, or close one by writing a date. A caller says who the members are *now*; when
 * each of them joined and left is a fact this use case records.
 */
data class GroupCommand(
    val name: String,
    val description: String = "",
    val members: List<GroupMemberInput> = emptyList(),
)

/**
 * One line of the command's member list.
 *
 * [id] names an existing membership window to **keep**. No [id] is an **add**, and an add for an
 * asset that already holds an open window is refused rather than obeyed: the caller meant "keep it",
 * and the way to say that is to send its id. Omitting a window that is open means "remove it", which
 * stamps `removed_at` and deletes nothing.
 */
data class GroupMemberInput(
    val assetId: AssetId,
    val id: String? = null,
    val sortOrder: Int = 0,
)

/**
 * One thing wrong with a [GroupCommand]. Every member is a **bad-command refusal** — what was asked
 * for describes a group that cannot exist — so each answers 422 on the wire and never 409.
 */
sealed interface GroupProblem {
    /** A group with no name is not identifiable by a human, and a name is all the group has. */
    data object BlankName : GroupProblem

    /** A member has to name a real Asset: a group is a set of equipment, not of ids. */
    data class MemberAssetMissing(val assetId: AssetId) : GroupProblem

    /** A membership id that belongs to another group, or to no group at all. */
    data class ForeignMember(val memberId: String) : GroupProblem

    /**
     * An **add** for an asset that already holds an open window, or two adds for one asset in a
     * single command: either would leave `(group, asset)` with two open windows, which
     * `required(D)` would then double-count and no partial unique index in Room can prevent
     * (invariant 80).
     */
    data class MemberAlreadyOpen(val assetId: AssetId) : GroupProblem
}

/** Validation failed; every problem found, collected once rather than fail-fast. */
class GroupValidation(val problems: List<GroupProblem>) :
    IllegalArgumentException("invalid group: $problems")

/**
 * A completion was aimed at the members of an asset-targeted schedule. The mirror of
 * [GroupCompletionNotSupported]: an asset-targeted round has exactly one required member and its own
 * entry point, and accepting a member list here would let a caller name somebody else's equipment.
 */
class MemberCompletionNotSupported(val id: ScheduleId) :
    IllegalStateException("schedule ${id.value} targets an asset, not a group")

/** An asset that has never been a member of the group this schedule targets. */
class NotAGroupMember(val id: ScheduleId, val assetId: AssetId) :
    IllegalStateException("asset ${assetId.value} is not a member of schedule ${id.value}'s group")

/**
 * A member of the group, but not one this round obliges: its window did not cover the round's open
 * instant. Distinct from [NotAGroupMember] because the two are different facts — "never in this
 * group" and "not in it when this round opened" — and a surface that cannot tell them apart will
 * eventually tell an owner the wrong one.
 */
class NotARequiredMember(val id: ScheduleId, val assetId: AssetId) :
    IllegalStateException("asset ${assetId.value} is not required for schedule ${id.value}'s round")

/**
 * A completion was aimed at a round that has been closed. Deliberate, and permanent: a closed round
 * is a statement that it ended unfinished, and no later write may turn it into a claim that it was
 * finished (invariant 39). Work done afterwards is logged as an ordinary journal event with no
 * schedule link, which still succeeds.
 */
class OccurrenceClosed(val id: ScheduleId, val occurrenceOn: String) :
    IllegalStateException("schedule ${id.value}'s occurrence $occurrenceOn is closed")

/**
 * The round obliges nobody — an empty group, or one whose windows all closed before this round
 * opened. It is not offered for completion, not counted as due, and not closeable; the schedule
 * reports `NO_DATA`. **Emptiness is never completeness** (invariants 74, 77).
 */
class OccurrenceNotActionable(val id: ScheduleId, val occurrenceOn: String) :
    IllegalStateException("schedule ${id.value}'s occurrence $occurrenceOn requires nobody")

/** "Close this round" was aimed at an asset-targeted schedule; in 1.2 it is offered on groups only. */
class CloseNotSupported(val id: ScheduleId) :
    IllegalStateException("schedule ${id.value} is not group-targeted")

/** This round already has a closure row, and that row is immutable: the first one stands. */
class OccurrenceAlreadyClosed(val id: ScheduleId, val occurrenceOn: String) :
    IllegalStateException("schedule ${id.value}'s occurrence $occurrenceOn is already closed")

/**
 * The round is in fact finished, so closing it would record that it ended unfinished when it did
 * not. A closure for a complete round is inert in the engine anyway (invariant 40); refusing it here
 * keeps the exported history honest rather than merely harmless.
 */
class OccurrenceAlreadyComplete(val id: ScheduleId, val occurrenceOn: String) :
    IllegalStateException("schedule ${id.value}'s occurrence $occurrenceOn is already complete")

/** A round that obliges nobody can never be closed: there is no round for a closure to be about. */
class OccurrenceNotCloseable(val id: ScheduleId, val occurrenceOn: String) :
    IllegalStateException("schedule ${id.value}'s occurrence $occurrenceOn requires nobody")

/**
 * `closedOn` was outside the occurrence's **open date through today, inclusive**.
 *
 * The bound exists because `closed_on` is the date the recurrence advances from and the row can
 * never be amended: an unbounded caller value would permanently move a schedule's future on a fact
 * nothing can correct. It mirrors D-25's rule for completions, so the API and the in-app action
 * accept exactly the same range.
 */
class ClosedOnOutOfRange(val value: String, val openOn: String, val today: String) :
    IllegalArgumentException("closedOn $value is outside $openOn..$today")

/** The windows still running: at most one per asset, which is invariant 80's whole content. */
internal fun MaintenanceGroup.openMembers(): List<GroupMember> = members.filter { it.removedAt == null }
