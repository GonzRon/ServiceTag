# B02 — the #4 scheduling engine

**Read first:** the master plan's §1, §5 (the scheduling domain), §2.3 (what is not a SQL constraint) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.1, §2.2; D5 §1–§5, §7–§10, §12; issue snapshot `issue-4.md`.

## Purpose

The recurrence and state engine, provider-neutral and Android-free: the `MaintenanceSchedule` behaviour on top of B01's data shapes, the derived `ScheduleState`, `ScheduleRecompute.rebuild` as the **only** write path into it, the status function computed at read time and never stored, FIXED / COMPLETION / meter / combined arithmetic including the D-27 pin and its edit-date floor, and the four operations — complete, snooze, postpone, edit recurrence — as use cases that do exactly what they say and never collapse into a generic reschedule. Group semantics are **not** here: `rebuild` takes membership and closures as parameters and B03 supplies their derivation. This brief is where D5 §10's worked examples become tests, and where the two corrections the spec makes to D5 (`prevDue` from `occurrence_on`; the pin) are proved.

## Files

**Create**

- `core/src/main/kotlin/com/loosecannon/servicetag/core/ports/Today.kt` — the date port (see Interfaces).
- `core/.../core/schedule/RecurrenceMath.kt` — the FIXED series, the COMPLETION advance, the calendar rules of D5 §2.3, the meter thresholds. Pure functions over dates and numbers; no repository, no clock.
- `core/.../core/schedule/ScheduleStatus.kt` — the status function.
- `core/.../core/schedule/ScheduleRecompute.kt` — `rebuild`, and the `terminations` fold it calls (the fold's group-aware inputs come in as parameters).
- `core/.../core/usecase/ScheduleCommands.kt` — the schedule command shape, its validation and its problem types, in the style of `usecase/EventCommands.kt:31-58`.
- `core/.../core/usecase/SaveSchedule.kt`, `CompleteSchedule.kt`, `PostponeSchedule.kt`, `PauseSchedule.kt`, `ArchiveSchedule.kt` — one `uow.write` each, validate first, in the shape of `usecase/LogEvent.kt:27-33`.
- `app/src/main/kotlin/com/loosecannon/servicetag/data/room/MaintenanceRepositories.kt` — the Room adapters for this brief's two ports, over B01's DAOs.
- Tests: `core/src/test/kotlin/.../schedule/RecurrenceMathTest.kt`, `ScheduleStatusTest.kt`, `ScheduleRecomputeTest.kt`, `D5WorkedExamplesTest.kt`, `core/src/test/kotlin/.../usecase/ScheduleOperationsTest.kt`; the `:core` in-memory fakes extended in `core/src/test/kotlin/.../testing/InMemoryRepositories.kt`.

**Modify**

- `core/.../core/ports/Repositories.kt` — `ScheduleRepository` and `ScheduleStateRepository` (see Interfaces).
- `core/.../core/usecase/LogEvent.kt`, `UpdateEvent.kt`, `DeleteEvent.kt`, `EventCommands.kt` — an event write may carry `scheduleId`, `occurrenceOn` and `detailsPending`, and **every** insert, update and delete of an event triggers a rebuild of the schedules that event can affect (see Interfaces).
- `core/.../core/usecase/ApplyBackupMergePlan.kt` — nothing but the wiring of B01's `rebuildAll` seam to the real recompute, at the call site in `AppGraph`.
- `app/.../di/AppGraph.kt` — `today`, the two repository fields, the recompute collaborator, and the five new use-case fields.
- `core/.../core/usecase/SeedTemplates.kt` / `ApplyTemplate.kt` — **read only, to confirm**: applying a template still creates **no** schedule (spec §1.2). If it would, that is a finding, not a change.

**Untouched:** `app/src/main/kotlin/.../ui/**`, `api/**`, `nfc/**`; every group and closure file (B03); `core/.../core/backup/**` and `merge/**` (B01 owns them); `tools/`; `libs/`.

## Interfaces

**Consumes from B01:** `MaintenanceSchedule`, `ScheduleTarget`, `ScheduleState`, `RecurrenceUnit`, `TimeBasis`, `SeasonBehavior`, `CompletionMode`, `ScheduleStatus` *(the lifecycle enum)*, `TerminationKind`, `OccurrenceClosure`, `GroupMember`, `ScheduleId`, `GroupId`, the widened `AssetEvent` and `EventSource`, and `MaintenanceScheduleDao`/`ScheduleStateDao`.

**Produces, for B03, B04, B06, B08, B09, B12, B14:**

```kotlin
fun interface Today { fun localDate(): java.time.LocalDate }          // core/.../core/ports/Today.kt

object ScheduleRecompute {
    fun rebuild(
        schedule: MaintenanceSchedule,
        events: List<AssetEvent>,          // the target's events (a group's: all required members')
        closures: List<OccurrenceClosure>, // this schedule's, oldest first
        membership: List<GroupMember>,     // empty for an asset target
        today: LocalDate,
    ): ScheduleState
}
fun statusOf(schedule: MaintenanceSchedule, state: ScheduleState, today: LocalDate): DueStatus
enum class DueStatus { OK, DUE_SOON, DUE, OVERDUE, INACTIVE_SEASON, PAUSED, NO_DATA }
```

`Plan decision:` the derived status enum is named **`DueStatus`**, because `ScheduleStatus` is already the stored lifecycle enum (`ACTIVE|PAUSED|ARCHIVED`) and one name for two different things is how a stored status gets written by accident (invariant 18).

Ports added to `core/.../core/ports/Repositories.kt`, in the style of the seven shipped ones:

| port | operations |
|---|---|
| `ScheduleRepository` | `upsert(schedule)` (replaces its provider rows, as `ProfileRepository.upsert` replaces child rows — `Repositories.kt:74-76`); `get(id)`; `all()`; `forAsset(assetId)`; `forGroup(groupId)`; `deleteAll()`; `observeAll()` |
| `ScheduleStateRepository` | `upsert(state)`; `get(scheduleId)`; `all()`; `deleteAll()`; `observeAll()`. **No other writer exists** (invariant 17) |

The recompute collaborator every event-writing use case takes, and which B03 and B06 also use:

```kotlin
class RecomputeSchedules(/* schedule, state, event, closure, group repositories + Today */) {
    suspend fun forAsset(assetId: AssetId)     // the asset's own schedules + every group schedule requiring it
    suspend fun forSchedule(id: ScheduleId)
    suspend fun all()                          // the digest, the backstop, and B01's rebuildAll seam
}
```

`Plan decision:` this collaborator exists because "every event insert/update/delete rebuilds" (spec §2.2) is a *closure* over group membership that no single use case can compute, and putting it in each use case would duplicate it four times. B03 fills in the group half; B02 ships it with an asset-only implementation and **one seam** (`groupSchedulesRequiring(assetId)`) that B03 implements. B02's own tests cover the asset path; B03's cover the group path.

**The command shape** is master plan §9.2's schedule command, validated here and reused verbatim by B12's route and B14's editor, so all three refuse the same things: exactly one target; at least one rule side; a group target carries no meter rule, no `profileId` and `IGNORE` season; a `profileId` and a `meterDefinitionId` must belong to the target asset; `anchorOn` present whenever `timeInterval` is; `leadDays >= 0`; `timeInterval >= 1`. **Every one of these is a 422-class bad-rule refusal** (master plan §2.3, spec revision 4.1) and the problem types carry the domain's own names so B12 can render them in `problems`.

## Invariants this brief must hold

**1, 2, 3, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23, 24, 25, 26, 27, 68, 70** (master plan §13).

## Test matrix

One test per hazard class. D5 §10's worked-example tables are the data for several rows.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| **the early completion** (D5 §10.1, the spec's headline correction) | anchor 2026-01-01, every 3 months, occurrence due Apr 1 completed **Mar 20** with `occurrence_on = 2026-04-01` → next due **Jul 1** | reconstructing `prevDue` as "the largest series date ≤ `occurred_on`" gives Jan 1 and yields Apr 1 — the exact failure spec §2.2 exists to prevent (invariant 70) |
| a pre-1.2 completion with no occurrence key | the same schedule with a completion carrying `occurrence_on = null` falls back to the largest series date `<= occurred_on` and the test **asserts the approximate answer**, documented as approximate | a fallback that throws, or that silently uses today, makes an old row unusable or wrong |
| FIXED late and very late | completed Apr 20 → Jul 1; completed Jul 15 → **Oct 1**, with **exactly one** next occurrence and no row for the skipped July one (invariants 13, 9) | an iterative advance produces a backlog of missed occurrences |
| calendar arithmetic | **one representative case per D5 §2.3 rule, not a matrix**: Jan 31 + 1 month clamps to Feb 28/29; a Feb 29 anchor returns to Feb 29 in the next leap year because the series is computed from the anchor with a multiplier; a week interval; a year wrap (invariant 11) | an iterative `plusMonths` drifts off the series after a clamp, and the Feb 29 case lands on Feb 28 forever |
| **the D-27 pin** | one test asserting all four facts together: a schedule created 2026-02-10 with a past anchor is due at the pinned series date; it stays **OVERDUE** as `T` advances with **no history change**; after its sole completion is deleted it returns to the **same** pin; and after a recurrence edit the floor becomes the **edit date** (invariants 23, 25) | a due date computed as "the first series date ≥ T" re-floats every day, so the OVERDUE assertion inverts; without the edit-date floor, re-anchoring an old schedule pins it immediately overdue |
| COMPLETION basis | with no termination, due = `anchorOn`; completed Sep 13 on a 90-day rule → Dec 12; completed early Aug 30 → Nov 28 (D5 §10.2, invariant 12) | advancing from the occurrence key instead of the effective date gives the wrong date for an early completion |
| meter side and the combined worst-of | D5 §10.3 in one test: created with a 120 h baseline → due 170 h and 2027-04-10; a 165 h reading → DUE_SOON on the meter and OK on time → **DUE_SOON**; 171 h → **DUE**; completing at 172 h advances **both** sides from one event; and with no readings and `T` past the time due date → **OVERDUE** regardless of the meter | computing status from one side, or advancing only one side on completion, breaks one of the five assertions |
| a missing meter baseline | a meter rule with neither a completion nor `anchorMeter` → **`NO_DATA`**; adding `anchorMeter` moves it off `NO_DATA` (D5 §3) | treating a null baseline as 0 makes the schedule spuriously overdue |
| a meter reading that goes backwards | a reading lower than the previous one is accepted and the engine uses **the latest by date, not the maximum** (D5 §3) | taking the maximum makes a misread meter permanently raise the threshold |
| `rebuild` is not a function | `rebuild(rebuild(x)) == rebuild(x)` and the same inputs always give the same output, asserted as **properties over the function** with no repository and no clock in the test (invariants 15, 16) | a `rebuild` that reads a clock, or that accumulates into its input, fails the second application |
| a second writer into `schedule_state` | a structural assertion: `ScheduleStateRepository.upsert` is called from `ScheduleRecompute`'s caller and nowhere else in `core/src/main` or `app/src/main` (invariant 17) | a use case that "fixes up" state directly makes the grep find a second call site |
| a stored status | a structural assertion: no column, DTO field, entity field or persisted value anywhere holds a `DueStatus` name (invariant 18) | caching status for the dashboard makes the grep hit |
| the four operations collapsing | one test per operation asserting what it changes **and what it does not**: complete inserts the event, rebuilds, and clears the postponement **only if it was set**; snooze changes no `*_on` column and creates no event; postpone changes `postponedDueOn` and no rule, and the **next** occurrence still comes from the rule; a recurrence edit changes the rule, clears the postponement, abandons an open partial occurrence and moves the pin's floor (invariants 19, 20, 21) | a generic `reschedule` makes one of the "does not change" assertions fail; the postpone-then-complete case (D5 §10.2 row 4) is where a rule-from-postponed-date implementation shows up |
| **the conditional write** | completing an **un-postponed** schedule writes **no column** on the `maintenance_schedule` row and leaves its `updated_at` byte-identical; completing a postponed one clears the postponement and only then bumps it (invariants 68, 69) | the unconditional update `issue-4.md:42` invites bumps `updated_at`, and every later re-import of that schedule becomes `CONTENT_DIFFERS` |
| deleting history | deleting the latest completion moves the due date **back**, observably, and the state's `lastTermination*` falls to the previous termination (invariant 24) | a stored "last completed" pointer that is not recomputed leaves the due date forward |
| a bad rule accepted | one test per validation rule of Interfaces, each naming its problem type: both targets, neither target, no rule side, a meter rule on a group target, `FOLLOW_ASSET` on a group target, a `profileId` on a group target, a foreign `profileId`, a foreign `meterDefinitionId`, a non-meter definition as the meter, `timeInterval` 0, negative `leadDays`, a `timeInterval` with no `anchorOn` (invariants 1, 2, 3, 27) | without validation the row stores a schedule the engine cannot evaluate; the group-target rows are D-12 and D-28 |
| pause, archive and season | one test placing the three side by side (D5 §10.6): `PAUSED` produces no due date and no notification; `ARCHIVED` is excluded from every query the dashboard and the provider use; an out-of-season `FOLLOW_ASSET` schedule is `INACTIVE_SEASON` and **not** `OVERDUE`, using the shipped `Season.inSeason` (`core/.../core/model/Season.kt:27-38`) (invariant 22) | treating `PAUSED` as `OK`, or an archived schedule as active, puts either in a due total |
| season monotonicity | status never goes from OVERDUE back to `OK` as `T` advances with no history change; a season boundary moving it to `INACTIVE_SEASON` is asserted as **not** a violation (invariant 23, U1) | a due date that re-floats on `T` produces the OVERDUE → OK transition |
| the deferred re-entry columns | a structural assertion: `seasonReentry` and `seasonReentryOffsetDays` appear in the model, the DTO, the entity, the mappers and the command and have **no read site** in `core/.../core/schedule/` (invariant 26) | implementing #14's semantics early makes the grep find a reader and changes behaviour the owner deferred |

## Strings

**None user-visible.** Problem type names are code, not copy; B12 renders them in `problems` as the shipped mapping already does (`docs/api/v1.md:229`). The editor's ratified wording is B14's.

## Ordering

**After B01** (needs the data shapes, the widened `AssetEvent` and the DAOs). **Before B03** (which extends `terminations`' inputs and `RecomputeSchedules`' group seam), **B04**, **B08**, **B12** and **B14**. Runs in lane A of wave 2 with **B05** in lane B — B05 touches no domain file.

## Review gate

- Unit: `./gradlew :core:test :app:testDebugUnitTest --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; the review records the counts and confirms `:core` carries the D5 worked-example class.
- Structural, anchored:
  - `grep -rn 'ScheduleStateRepository' core/src/main app/src/main | grep -v 'ports/Repositories.kt'` shows the recompute call site and the Room adapter, and no third writer.
  - `grep -rnE '\bDueStatus\b' core/src/main/kotlin/com/loosecannon/servicetag/core/backup core/src/main/kotlin/com/loosecannon/servicetag/core/merge app/src/main/kotlin/com/loosecannon/servicetag/data` → no match.
  - `grep -rn 'seasonReentry' core/src/main/kotlin/com/loosecannon/servicetag/core/schedule` → no match.
  - `grep -rnE '^import (android|androidx)\.' core/src/main` → no match.
  - `grep -rn 'nowMillis()' core/src/main/kotlin/com/loosecannon/servicetag/core/schedule` → no match (the engine never reads a clock).
- No connected run: this brief draws nothing.

## Estimated size

The largest `:core` brief after B03. If the worked examples and the operations push it past what one review holds, split at **B02a arithmetic, status and `rebuild`** / **B02b the four operations, the ports and the Room adapters** and say so in the ledger; the interface between them is `ScheduleRecompute.rebuild` and `RecomputeSchedules`.
