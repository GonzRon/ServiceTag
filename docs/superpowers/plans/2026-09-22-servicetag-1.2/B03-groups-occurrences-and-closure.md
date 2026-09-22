# B03 — #55 groups, occurrences and round closure

**Read first:** the master plan's §1, §6 (groups, membership and occurrences), §7 (the closure fact), §5.3 (`rebuild`) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.3, §2.4, §2.9; rulings D-7 to D-16, D-8; issue snapshot `issue-55.md`.

## Purpose

Maintenance groups as a first-class aggregate — never a synthetic Asset, never an NFC identity — with **append-only temporal membership**, the derived group occurrence (its open instant, its required set, its completed set), truthful per-member completion including "Complete all", and the D-8 answer to a stuck round: **"Close this round"**, an immutable exported `occurrence_closure` fact that terminates the round without claiming anybody did the work. This brief also completes B02's `terminations` fold, because a closure is a termination with a date and an empty required set is never one at all. It is the brief where #55's hardest requirement — that historical completion never depends on today's membership list — becomes a property test rather than a hope.

## Files

**Create**

- `core/src/main/kotlin/com/loosecannon/servicetag/core/schedule/GroupOccurrence.kt` — the open instant, the open date, `required(D)`, `completed(D)`, the completeness predicate and the empty-required rule, as pure functions.
- `core/.../core/usecase/GroupCommands.kt` — the group command shape (master plan §9.2), its validation and its problem types.
- `core/.../core/usecase/SaveGroup.kt`, `ArchiveGroup.kt`, `CompleteGroupMembers.kt`, `CloseRound.kt` — one `uow.write` each, validate first.
- `app/src/main/kotlin/com/loosecannon/servicetag/data/room/GroupRepositories.kt` — the Room adapters for this brief's two ports over B01's `MaintenanceGroupDao` and `OccurrenceClosureDao`. **Amended (B01 review, 2026-09-22):** `GroupRepository`/`ClosureRepository`, their adapters `RoomGroupRepository`/`RoomClosureRepository` (`MaintenanceRepositories.kt`) and the two `AppGraph` fields already exist from B01, minimal — extend them (in place, or moved to `GroupRepositories.kt`); `ClosureRepository` keeps insert-only.
- Tests: `core/src/test/kotlin/.../schedule/GroupOccurrenceTest.kt`, `core/src/test/kotlin/.../usecase/GroupMembershipTest.kt`, `GroupCompletionTest.kt`, `CloseRoundTest.kt`.

**Modify**

- `core/.../core/ports/Repositories.kt` — `GroupRepository` and `ClosureRepository` (see Interfaces).
- `core/.../core/schedule/ScheduleRecompute.kt` — the `terminations` fold made total per master plan §7; `lastTerminationEffectiveOn` / `lastTerminationKind` filled from `last`; `lastCompletedOn` kept narrow.
- `core/.../core/usecase/RecomputeSchedules.kt` (B02's collaborator) — the `groupSchedulesRequiring(assetId)` seam implemented.
- `core/.../core/usecase/SaveSchedule.kt` — the refusal of a group-targeted schedule on a group with **no members**.
- `app/.../di/AppGraph.kt` — the two repository fields and the four use-case fields.
- `core/src/test/kotlin/.../testing/InMemoryRepositories.kt` — fakes for the two new ports.

**Untouched:** every `app/src/main/kotlin/.../ui/**` and `api/**` (B12, B15); `core/.../core/backup/**` and `merge/**` (B01); `core/.../core/nfc/**`; `tools/`; `libs/`.

## Interfaces

**Consumes from B01:** `MaintenanceGroup`, `GroupMember`, `OccurrenceClosure`, `GroupId`, `MaintenanceGroupDao`, `OccurrenceClosureDao`. **From B02:** `ScheduleRecompute.rebuild` (pass `season`, the sixth defaulted parameter, whenever the schedule carries a window — amended 2026-09-22), `RecomputeSchedules`, `Today`, `ScheduleRepository`, `ScheduleStateRepository`, the schedule command and its problem types, `DueStatus`.

**Produces, for B04, B08, B09, B12, B15:**

```kotlin
data class GroupOccurrence(                       // derived; nothing here is stored
    val scheduleId: ScheduleId, val occurrenceOn: LocalDate,
    val openInstant: Long, val openOn: LocalDate,
    val required: List<AssetId>, val completed: List<AssetId>,
) { val isActionable: Boolean; val isComplete: Boolean; val progress: Pair<Int, Int> }
```

| port | operations |
|---|---|
| `GroupRepository` | `upsert(group)` — the aggregate, **replacing its member rows** as `ProfileRepository.upsert` does (`Repositories.kt:74-76`); `get(id)`; `all()`; `forAsset(assetId)` (open windows) and `allWindowsFor(assetId)`; `deleteAll()`; `observeAll()`; `observeForAsset(assetId)` |
| `ClosureRepository` | `insert(closure)`; `forSchedule(scheduleId)` oldest first; `find(scheduleId, occurrenceOn)`; `all()`; `deleteAll()`. **No `upsert`, no `update`, no `delete`** — the port itself makes invariant 37 unbreakable from above |

`Plan decision:` `ClosureRepository` has `insert`, not `upsert`. Every other repository in `Repositories.kt` exposes `upsert`; copying that here would hand a caller the amendment the spec forbids, and a port is a cheaper place to make that impossible than a review.

The four use cases, with their exact refusals — B12's routes and B14's editor reuse these and their problem types, so the API and the UI cannot diverge:

| use case | writes | refuses |
|---|---|---|
| `SaveGroup(cmd)` | the group and its member rows; **soft-removes** every existing open member the command omits; **inserts** a new row for every member with no `id` | a blank `name`; a member naming an absent asset; a member `id` that is not this group's; a member **without an `id`** for an asset that already has an open row → `MemberAlreadyOpen` (a 422-class refusal, spec revision 4.1) |
| `ArchiveGroup(id, archived)` | `archived_at` | nothing — archive is not delete, and history is retained |
| `CompleteGroupMembers(scheduleId, assetIds, completion)` | one `asset_event` per named member **in one `uow.write`**, then `RecomputeSchedules.forSchedule` | a non-member; an asset outside `required(D)`; an occurrence already closed → `OccurrenceClosed`; a required set that is empty → `OccurrenceNotActionable`. A repeat for an asset already complete is a **no-op**, absorbed by the unique index, not an error |
| `CloseRound(scheduleId, closedOn?)` | **exactly one** `occurrence_closure` row and nothing else, then a rebuild | an asset-targeted schedule → `CloseNotSupported`; an already-closed occurrence → `OccurrenceAlreadyClosed`; a fully completed one → `OccurrenceAlreadyComplete`; an empty required set → `OccurrenceNotCloseable`; a `closedOn` outside **open date ≤ closedOn ≤ today** → `ClosedOnOutOfRange` |

**`terminations`, the fold B02 declared and this brief makes total** — master plan §7 carries it in full and is the authority; restated here only as the five properties a test must pin: an empty required set is **never** a termination; a completed round terminates at `max occurred_on` over its completions; a closed round with **no** completions terminates at its `closed_on`; a closed round with completions terminates at `max(closed_on, max occurred_on)`; and **a full completion takes precedence over a closure for the same occurrence**.

## Invariants this brief must hold

**4, 5, 6, 7, 8, 14, 19, 24, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 68, 73, 74, 77, 78, 79, 80** (master plan §13).

## Test matrix

One test per hazard class; a row naming several facts is one test asserting them together.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| the open instant is not a function | the open instant is the **maximum `created_at`** over the previous occurrence's terminating rows — its member completions **and**, if it was closed, its closure row — and the schedule's `created_at` for the first occurrence; asserted as a **property** over the rows, not with two devices (invariant 73) | using `occurred_on` makes the instant depend on a backdated date; using "now" makes it unreproducible after an import |
| a backdated completion moves the membership basis | a member completion backdated a month does **not** change the next occurrence's required set | keying the basis off `occurred_on` shifts the window and a member drops out of the round |
| required-set derivation across churn | one test covering an add mid-occurrence (not required for the open round), a removal mid-occurrence (**still** required — D-10), and a member added after the round opened appearing in the **next** round (invariant 33) | computing `required` from today's membership list makes the removal drop out and the round close falsely |
| **remove then re-add** | removing a member stamps `removed_at`; re-adding the same asset **inserts a second row** with a new id and a new `added_at`, **clears no `removed_at`**, and **changes no past occurrence's required set** (invariants 6, 33, 79) | clearing `removed_at` to "re-open" the window rewrites the window a past round keyed off, breaking #55 AC 8 |
| two open windows for one asset | adding a member that already has an open row is refused with `MemberAlreadyOpen`, and the database still holds exactly one open row (invariant 80) | without the use-case rule — which a partial unique index cannot express in Room — the group holds two open windows and `required(D)` double-counts |
| an empty group | one test: creating a group-targeted schedule on a group with **no members** is refused; a schedule whose members were **all removed** reports `NO_DATA`, is not offered, is **not counted as due**, and **cannot be closed** (invariants 74, 77) | a vacuous `⊇` over an empty required set reports the round complete and advances the schedule — the R1 defect |
| completing a non-member | completing an asset that is not in `required(D)` is refused and **writes nothing** (invariants 28, 34) | an unchecked asset id writes a maintenance event onto unrelated equipment |
| completing one member marking others | completing one required member leaves every other member's history byte-identical and the occurrence **open** and the schedule **due** (invariants 29, 30) | a group-level completion event, or a loop over all members, falsely records work |
| "Complete all" twice | "Complete all" writes one event per not-yet-completed required member in **one** `uow.write`; running it again writes **nothing** and the event count is unchanged (invariants 31, 32) | without the unique index the second run duplicates every member's event **Amended at review (2026-09-22):** as worded the row is unreachable (finishing the round advances the schedule and the operation takes no occurrence parameter); the accepted proof is the three-way substance test (no second write, no second event, no advance) the implementer recorded. |
| one asset in two groups | an asset in two groups with the same operation has two schedules and two occurrences; completing one leaves the other due, with **no** semantic deduplication (D-11) | deduplicating by operation name silently drops a real obligation |
| a member archived mid-round | archiving or retiring a member Asset leaves its membership rows untouched, keeps the **open** round's required set unchanged (D-10), and excludes it from the **next** round by lifecycle (D-16, invariant 8) | deleting membership on archive erases the history a past round needs |
| a group archived with an open round | archiving the group hides it and its schedules from the active views and **retains** every membership row, completion and closure | a cascade delete on archive destroys history #55 AC 8 requires |
| the occurrence key under a postpone | postponing does not change `occurrence_on`; a completion after a postpone carries the **unpostponed** `computedDueOn` as its key (invariant 21) | stamping the postponed date makes a postpone move an occurrence's identity and the round reopens |
| **closing writes too much** | one test asserting all three: closing writes **exactly one** closure row; **no column** on `maintenance_schedule` (its `updated_at` byte-identical); **no `asset_event` on any asset**, and every member's history unchanged (invariants 35, 36, 68) | the natural implementation — stamp the schedule, or fabricate an event "so the round looks done" — fails one of the three |
| closing twice | the second close is **refused** and the **first row stands**, unchanged (invariant 38, T10) | an upsert overwrites the first closure's date and the recurrence silently moves **Amended at review (2026-09-22):** as worded the row is unreachable (finishing the round advances the schedule and the operation takes no occurrence parameter); the accepted proof is the three-way substance test (no second write, no second event, no advance) the implementer recorded. |
| completing a closed round | a completion with an `occurrence_on` that already has a closure is refused; the same work logged as an ordinary event with **no `schedule_id`** succeeds (invariant 39, spec §2.9's deliberate consequence) | without the check a closed round becomes a claim that it was finished |
| the ordinary close's effective date | a closed round with **no** completions terminates at its `closed_on`, and both bases advance from it: COMPLETION → `closed_on + interval`; FIXED → the smallest series date `> max(D, closed_on)` | an undefined effective date for an empty completion set — the R1 defect — throws or yields the anchor |
| monotone effective date | a closed round carrying a member completion **later** than `closed_on` terminates at the completion's date, not the closure's | taking `closed_on` unconditionally moves the next due date backwards |
| completion beats closure | a stray closure on a round that is in fact fully completed is **inert**: the termination is `COMPLETED` at the completion's date (invariant 40) | closure-first precedence lets a merged stray row change a schedule's future |
| closure survives later rounds, and an edit | round 1 closed then round 2 completed gives two terminations with round 2 as `last`; a recurrence edit **does not touch** existing closures even when their `occurrence_on` now lies off the new series (invariant 14) | "tidying" old closures after an edit destroys exported history and changes the merge verdict on another phone |
| **deleting a later completion** | deleting round 2's member completion reopens round 2, falls `last` back to round 1's closure, and returns the due date to the **closure-derived** value (invariants 24, 41) | a stored "last termination" pointer that is not recomputed leaves the due date forward and the round shut |
| `closedOn` range | `closedOn` defaults to today; the occurrence's **open date** and **today** are both accepted; the day before the open date and tomorrow are each refused with `ClosedOnOutOfRange` (invariant 78) | an unbounded caller date permanently moves a schedule's future on a row that can never be amended — the R2 defect |
| membership churn after a close | a closed round stays `CLOSED` whatever `required(D)` later becomes, while a **completed** round reopens when a membership change grows `required(D)` (spec §2.9, U3) | treating the two the same makes closure no more durable than completion, which is the property D-8 asked for |
| a group is equipment | structural: no code path creates an `Asset` row for a group, puts a group in the Asset parent/child tree, or binds a tag to one (invariants 4, 5) | the "fake parent asset" shortcut #55 forbids |
| identity by name | two groups with the same `name` coexist, and no lookup anywhere resolves a group by name (invariant 7) | a name-keyed cache coalesces two real groups |

## Strings

**Ratified, verbatim** (master plan §17): **"Maintenance group"**, **"3 of 5 complete"** (the progress form), **"Complete selected"**, **"Complete all"**, **"Close this round"** (the action label), **"When was this done?"**.
**This brief draws none of them** — it is `:core` plus a Room adapter, and the surfaces are B14 and B15. It must not introduce a user-visible string of its own; a problem type name is code.
**Ratified, and drawn by B14 rather than here:** the "Close this round" confirmation body — "Close this round? The members not marked done will not be recorded as serviced." (owner, 2026-09-22; master plan §17.1b).

## Ordering

**After B01 and B02.** **Before B04, B08, B09, B12, B15.** Runs in lane A of wave 3; **B04** takes lane B as soon as this brief's domain lands, **and must land before wave 4 opens** — wave 4 lane A is B06, which consumes it (master plan §14.3).

## Review gate

- Unit: `./gradlew :core:test :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- Structural, anchored:
  - `grep -rn 'interface ClosureRepository' -A 8 core/src/main/kotlin/com/loosecannon/servicetag/core/ports/Repositories.kt` shows `insert` and reads only — no `upsert`, no `update`, no `delete`.
  - `grep -rnE '(UPDATE|DELETE)[[:space:]]+(FROM[[:space:]]+)?`?occurrence_closure' app/src/main core/src/main` → no match.
  - `grep -rn 'AssetRepository' core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/SaveGroup.kt` shows the member-asset existence check present (invariant 8's precondition).
  - `grep -rn 'removed_at\|removedAt' core/src/main | grep -iE '= *null|clear'` → no site that clears it (invariant 79). **Amended at review (2026-09-22):** the `= *null` pattern also matches the `== null` predicate and three pre-existing B01 lines; the accepted gate is the implementer's tighter anchored pattern plus the structural test recorded in B03-report.md.
  - `grep -rnE '^import (android|androidx)\.' core/src/main` → no match.
- No connected run.

## Estimated size

The largest brief. If the occurrence derivation and the closure work together exceed what one review holds, split at **B03a membership and the group occurrence** / **B03b group completion and `CloseRound`** and say so in the ledger; the interface is `GroupOccurrence` and the `terminations` properties.
