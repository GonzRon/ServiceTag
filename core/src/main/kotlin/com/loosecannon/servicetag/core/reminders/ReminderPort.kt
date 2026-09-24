package com.loosecannon.servicetag.core.reminders

import com.loosecannon.servicetag.core.model.RecurrenceUnit
import com.loosecannon.servicetag.core.model.ScheduleId
import com.loosecannon.servicetag.core.model.TimeBasis
import java.time.LocalDate

/**
 * The provider-neutral reminder port: what a provider is asked to hold, and the one call that asks.
 *
 * Nothing here names a platform, a product or a mechanism, and that refusal is the whole point —
 * `:core` is a JVM library that compiles and is tested off-device, and a provider that could be
 * named here is a provider whose details would leak into every caller. What a subject *is* is
 * decided here; how one is shown is entirely the implementation's business.
 *
 * The whole write surface is [ReminderProvider.reconcile], which receives the desired state of every
 * subject the provider owns. There is deliberately no "create one" call: idempotence is then
 * structural rather than remembered, and a repair that re-runs the same list is safe by
 * construction.
 */

/**
 * What a subject *is about*.
 *
 * Sealed, with one member in 1.2. A supply-level subject is a later phase's, and a member added
 * before the thing it names exists is a subject something can write and nothing can deliver.
 */
sealed interface SubjectKey {
    data class Schedule(val scheduleId: ScheduleId) : SubjectKey
}

/**
 * Which provider a list belongs to.
 *
 * **One member in 1.2.** A second provider is a later phase's, and declaring it now would give the
 * schedule editor a row the phone cannot deliver. The consequence is recorded rather than worked
 * around: with one member, and a provider row keyed `(schedule, provider)`, no schedule can hold two
 * enabled rows, so "two providers yield two lists with no change to the port" is proved
 * **structurally** in 1.2 — the provider is a parameter of [BuildReminderSubjects.forProvider] and
 * the key of [BuildReminderSubjects.all]'s map, and [ReminderProvider.reconcile] names no provider
 * at all, so a second member is purely additive. The behavioural half lands with the second real
 * provider.
 */
enum class ProviderId { LOCAL }

/**
 * Where a subject stands, as a provider needs to know it.
 *
 * [Parked] is the member that earns the type: a paused or seasonally inactive obligation is **in**
 * the list and parked, never absent and never overdue, so a provider has something to clear and
 * something to show. [reentryOn] is the date it comes back — the actionable date, or the first day
 * after the break — and is null for a pause, which has no date, and for a MANUAL asset out of season,
 * whose next START is never predicted, so the provider can say when without knowing what a season is.
 *
 * [Withdrawn] is how a retired obligation arrives: **in** the list, so a provider is told to stop
 * holding it rather than left to infer it from an absence.
 *
 * [Completed] is **reserved and has no producer in 1.2**. It is shaped for the supply-level subjects
 * of a later phase, where a subject can be finished without its schedule advancing; a schedule's
 * round cannot, because a finished round is a termination and the current occurrence is always the
 * next one. Nothing here builds one, and a structural test pins that: every place 1.2 mentions it,
 * it shares a branch with [Withdrawn], because both mean the provider must stop holding the subject.
 */
sealed interface SubjectState {
    data object Active : SubjectState
    data class Parked(val reentryOn: LocalDate?) : SubjectState
    data object Completed : SubjectState
    data object Withdrawn : SubjectState
}

/**
 * Whether this state means **stop holding the subject**, as opposed to show it.
 *
 * One predicate rather than the same `when` written out in each provider, because the two states
 * that answer yes are the two a provider must treat identically, and every implementation counting
 * them differently is a different number on the same health screen. A parked subject answers **no**:
 * it is still held, and still shown, with its re-entry date.
 */
val SubjectState.isCleared: Boolean get() = when (this) {
    SubjectState.Active, is SubjectState.Parked -> false
    SubjectState.Completed, SubjectState.Withdrawn -> true
}

/**
 * The recurrence rule's **facts**, never the entity that holds them.
 *
 * This is what a provider with a recurrence engine of its own consumes to decide whether it can
 * carry the subject; a subject that carried the schedule instead would let a provider re-derive due
 * dates, which is the coupling the port exists to prevent. [basis], [interval] and [unit] are null
 * together for a schedule with no time series — a use-based rule has no dates to project.
 */
data class RuleFacts(
    val basis: TimeBasis?,
    val interval: Int?,
    val unit: RecurrenceUnit?,
    val hasMeter: Boolean,
    val seasonal: Boolean,
)

/**
 * One thing a provider should be holding.
 *
 * [contentHash] covers every field that changes what a provider should show — [title], [body],
 * [dueOn], [leadDays], [state] and [rule] — and nothing else, so an edit a provider cannot see does
 * not churn it. [dueOn] is the **actionable** date for an active subject — the one its status word
 * is measured against — and the effective date a withdrawn subject was last shown with. It is null
 * for a use-based rule and for a parked subject: a fabricated date is how a provider comes to announce
 * something that has no date.
 */
data class ReminderSubject(
    val key: SubjectKey,
    val title: String,
    val body: String,
    val dueOn: LocalDate?,
    val leadDays: Int,
    val state: SubjectState,
    val rule: RuleFacts?,
    val contentHash: String,
)

/**
 * What one [ReminderProvider.reconcile] did, in the coarsest terms that are still useful: enough for
 * a health screen and for diagnostics, and not enough to name a mechanism.
 *
 * The three counters are **defined here, not left to each provider**, because a health screen and a
 * diagnostics report render the same numbers from different providers and two meanings for one
 * number is worse than no number:
 *
 * - [posted] — subjects the provider is now showing that it was not showing in this form before:
 *   shown for the first time, or re-shown because the content hash changed. A subject whose state
 *   [isCleared] is **never** posted.
 * - [cleared] — subjects the provider **stopped** holding in this call, whether they left the list
 *   altogether (absence is the cancel: the list is the whole desired state) or were still in it
 *   with a state that says to let go. A subject the provider was not holding is not cleared, which
 *   is what keeps a standing withdrawal from reporting a clearance on every run for ever.
 * - [unchanged] — same key, same content hash: nothing to do, and the reason calling `reconcile`
 *   twice has no second effect.
 *
 * So `posted + unchanged` is the number of subjects the provider holds after the call, and
 * [cleared] is disjoint from both.
 *
 * [problems] are sentences a provider has already composed for display; the port takes them as
 * given because only the implementation knows what went wrong on its own side.
 */
data class ReconcileReport(
    val posted: Int,
    val cleared: Int,
    val unchanged: Int,
    val problems: List<String>,
)

/**
 * A change a provider observed on its own side and is reporting back.
 *
 * `LOCAL` returns empty: its own state is only ever what [ReminderProvider.reconcile] put there, so
 * nothing in 1.2 produces or consumes one of these. The shape is the smallest one that is not a lie
 * — which subject, and the date the change took effect if it has one — and it is **provisional**:
 * neither the spec nor the plan fixes its fields. It is shaped for a **pull-based provider** and is
 * **revisited at the first non-`LOCAL` provider**.
 */
data class RemoteChange(val key: SubjectKey, val effectiveOn: LocalDate?)

/** How loudly a finding should be shown. The badge threshold is `>= WARN`, which the order gives. */
enum class ReminderHealthSeverity { INFO, WARN, ERROR }

/**
 * What can be done about a finding, in the three kinds that make the repair policy visible in the
 * type: repair is **only** ever what is unambiguous and idempotent, so an [Automatic] one can be
 * run on the owner's behalf and the other two can only ever take them somewhere to decide.
 *
 * [code] is the repair's own identifier — which automatic repair to run, which system screen to
 * open, which in-app destination to go to — and not the finding's code.
 */
sealed interface RepairAction {
    val code: String
    data class Automatic(override val code: String) : RepairAction
    data class OpenSystemSettings(override val code: String) : RepairAction
    data class OpenInApp(override val code: String) : RepairAction
}

/**
 * One thing wrong with a provider's ability to deliver.
 *
 * [code] is the finding's stable identifier, [message] the already-composed sentence to show, and
 * [repair] null when there is nothing honest to offer. A finding with no repair is still worth
 * showing: explaining a platform reality is better than hiding it behind a button that cannot help.
 */
data class ReminderHealthFinding(
    val code: String,
    val severity: ReminderHealthSeverity,
    val message: String,
    val repair: RepairAction?,
)

/**
 * A reminder provider: it is told the whole desired state, it reports what it observed, and it
 * reports what is wrong with it.
 *
 * [reconcile] is the entire write surface. It receives the desired state of every subject this
 * provider owns and makes the provider match it: **a subject that is not in the list is one the
 * provider must no longer be holding**, and so is one whose state [isCleared]. Calling it twice
 * with the same list therefore has no second effect, which is a property of the signature and not
 * of anyone's discipline. [ReconcileReport] defines what each of the three counters counts, and an
 * implementation is expected to obey those definitions rather than invent its own.
 */
interface ReminderProvider {
    val id: ProviderId
    suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport

    /**
     * What changed on the provider's own side. Empty for a provider that only ever holds what it
     * was told.
     */
    suspend fun pullChanges(): List<RemoteChange>
    suspend fun health(): List<ReminderHealthFinding>
}
