# B04 — the #28 `ReminderProvider` port

**Read first:** the master plan's §1, §8 (the reminder port and delivery state) and §13.
**Spec:** `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md` §2.5; D3 §7.1 (`docs/design/03-target-architecture.md:184-203`); issue snapshot `issue-28.md`.

## Purpose

One provider-neutral port in `:core` that both the local provider (B06) and a later Todoist provider implement, plus the use case that turns schedule state into the desired subject list for each enabled provider. `reconcile` is the **whole write surface** — there is deliberately no "create one reminder" call, which is what makes idempotence structural rather than remembered and what makes #27's repair safe. Small, foundational, and the one brief whose correctness is mostly a matter of what it refuses to know: no Android type, no provider object, no notification, no alarm.

## Files

**Create**

- `core/src/main/kotlin/com/loosecannon/servicetag/core/reminders/ReminderPort.kt` — `ReminderSubject`, `SubjectKey`, `SubjectState`, `RuleFacts`, `ProviderId`, `ReconcileReport`, `RemoteChange`, `HealthFinding`, `Severity`, `RepairAction`, and `interface ReminderProvider`.
- `core/.../core/reminders/BuildReminderSubjects.kt` — the use case: for each enabled `schedule_provider` row, the subject list that provider should hold.
- `core/.../core/reminders/ContentHash.kt` — the hash the subject carries.
- Tests: `core/src/test/kotlin/.../reminders/BuildReminderSubjectsTest.kt`, `ReminderPortContractTest.kt`; a fake provider in `core/src/test/kotlin/.../testing/FakeReminderProvider.kt` that records every `reconcile` call.

**Modify**

- `core/.../core/ports/Repositories.kt` — nothing. The use case reads through B02's `ScheduleRepository`/`ScheduleStateRepository` and B03's `GroupRepository`.
- `app/.../di/AppGraph.kt` — one field for the subject-building use case. **No provider is registered here** — B06 registers the local one.

**Untouched:** all of `app/src/main` but the one `AppGraph` field; `core/.../core/backup/**` and `merge/**`; `core/.../core/schedule/**` (it is read, not changed); `tools/`; `libs/`.

## Interfaces

**Consumes from B02:** `MaintenanceSchedule`, `ScheduleState`, `DueStatus`, `statusOf`, `Today`, `ScheduleRepository`, `ScheduleStateRepository`. **From B03:** `GroupOccurrence`, `GroupRepository`.

**Produces, for B06, B10 and B12.** The two shapes the spec fixes, quoted from master plan §8 and not to be altered:

```kotlin
data class ReminderSubject(
    val key: SubjectKey, val title: String, val body: String,
    val dueOn: LocalDate?, val leadDays: Int,
    val state: SubjectState, val rule: RuleFacts?, val contentHash: String,
)
interface ReminderProvider {
    val id: ProviderId
    suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport
    suspend fun pullChanges(): List<RemoteChange>     // LOCAL: always empty
    suspend fun health(): List<HealthFinding>
}
```

and the six supporting types, whose shapes are master plan §8's `Plan decision` and are fixed here so B06, B10 and B12 compile against one definition:

| type | shape | why this shape |
|---|---|---|
| `SubjectKey` | sealed; `Schedule(ScheduleId)` **only** in 1.2 | `SUPPLY(id)` is #15, Phase 6; a dead member is a member something can write |
| `ProviderId` | enum; **`LOCAL` only** in 1.2 | `TODOIST` is Phase 5; adding it now gives the editor a provider row it cannot deliver. **Consequence:** with one member, and `schedule_provider` keyed `(schedule_id, provider)`, no schedule can hold two enabled rows in 1.2 — so the two-list case is proved **structurally**, not behaviourally (see the matrix row, and master plan decision 8) |
| `SubjectState` | sealed; `Active`, `Parked(reentryOn: LocalDate?)`, `Completed`, `Withdrawn` | `Parked` carries the re-entry date the provider shows without knowing what a season is |
| `RuleFacts` | `basis: TimeBasis?, interval: Int?, unit: RecurrenceUnit?, hasMeter: Boolean, seasonal: Boolean` | the rule's **facts**, never the entity: what a future provider's capability check consumes (#28, D3 §8) |
| `ReconcileReport` | `posted: Int, cleared: Int, unchanged: Int, problems: List<String>` | enough for B10's health screen and B12's diagnostics without naming a mechanism |
| `HealthFinding` / `Severity` / `RepairAction` | `HealthFinding(code: String, severity: Severity, message: String, repair: RepairAction?)`; `Severity { INFO, WARN, ERROR }`; `RepairAction` sealed with `Automatic(code)`, `OpenSystemSettings(code)`, `OpenInApp(code)` | #5's badge threshold is "≥ WARN"; #27's repair policy is "only the unambiguous and idempotent", which the three action kinds make visible in the type |

The use case:

```kotlin
class BuildReminderSubjects(/* schedule, state, group, asset repositories */) {
    suspend fun forProvider(provider: ProviderId, today: LocalDate): List<ReminderSubject>
    suspend fun all(today: LocalDate): Map<ProviderId, List<ReminderSubject>>
}
```

**The subject-building rules, as contract:**

| rule | statement |
|---|---|
| one list per enabled provider | subjects come from `schedule_provider` rows where `enabled` is true; a schedule with two enabled rows appears in **two** lists with **no change to the port** (invariant 49) |
| a group is one subject | a group-targeted schedule is **one** subject, never one per member, with the progress line in its `body` (D-15) |
| parking, not absence | a `PAUSED` schedule and a seasonally inactive one each arrive as `Parked(reentryOn)` — **never absent, never overdue** (invariant 47). `reentryOn` is the season's next start date for an inactive season and `null` for a pause |
| withdrawal | an `ARCHIVED` schedule arrives as `Withdrawn`; a schedule whose `remindersEnabled` is false, or whose provider row is disabled, simply **is not in that provider's list** |
| `dueOn` | the **effective** due date; `null` for a meter-only schedule and for a parked one |
| `NO_DATA` | a schedule with a meter rule and no baseline arrives `Active` with `dueOn = null`; it is #27's finding, not a reminder to post |
| `contentHash` | over every field that changes what a provider should show — `title`, `body`, `dueOn`, `leadDays`, `state` and `rule` — and over **nothing else**, so an unrelated edit does not churn the provider (invariant 46) |
| provenance-neutral | nothing in the subject names a measurement's origin; a telemetry-fed and a hand-entered reading produce identical subjects (#4 AC 7) |

## Invariants this brief must hold

**45, 46, 47, 48, 49** (master plan §13). **Not 22** — "`INACTIVE_SEASON` and `PAUSED` never notify and never count as due" belongs to B02, B06 and B08; this brief proves the adjacent and narrower **47**, that such a schedule *arrives in the subject list as `Parked`, never absent and never overdue*, and it has no notification or dashboard code with which to prove the rest. It must also leave **44** achievable for B06 — the subject list is derivable from schedule state alone, with no provider bookkeeping as an input.

## Test matrix

One test per hazard class, all against the fake provider and in-memory repositories; no Android, no device.

| hazard | behaviour proved | how it fails without the change |
|---|---|---|
| a provider leaks into `:core` | a structural assertion over `core/src/main`: no `android.`/`androidx.` import anywhere, and no type name in `core/.../core/reminders/` mentioning a provider product (invariant 48) | importing a notification type or naming a Todoist object makes `:core` unbuildable off-device and the grep hit |
| two providers need a port change | **proved structurally, not behaviourally** (master plan decision 8): `ProviderId` carries `LOCAL` only in 1.2, so two enabled rows are unconstructable and no test can build the two-list case. What the test asserts instead is that **the port needs no change for one**: the provider is a **parameter** of `forProvider` and the **key** of `all()`'s map, `reconcile`'s signature names no provider, and adding a second enum member is purely additive — asserted over the signatures and over `all()`'s keying, with one subject list for `LOCAL`. **The behavioural half of #28 AC 4 lands with the Todoist provider in Phase 5** (invariant 49) | a provider-shaped parameter on `reconcile`, or a subject list that is not keyed by provider, would make a second provider a port change — which is the fact AC 4 exists to prevent and the only half 1.2 can prove |
| a no-op update churns the provider | the same schedule state yields the **same** `contentHash`; changing only an unrelated schedule field (its `description`, say, when `description` is not in the body) leaves the hash unchanged; changing `dueOn` changes it (invariant 46) | hashing the whole entity makes every edit look like a change and the provider re-posts |
| `reconcile` twice | the fake provider records two calls with **identical** subject lists and reports `unchanged` for every subject the second time, with **no second effect** (invariant 45) | a "create one reminder" call, or a `reconcile` that appends rather than reconciles, produces a second effect |
| a parked schedule vanishing | one test covering both: a `PAUSED` schedule and an out-of-season `FOLLOW_ASSET` schedule each arrive as `Parked`, with the season case carrying its re-entry date; **neither is absent and neither is overdue** (invariant 47) | filtering parked subjects out of the list makes a provider keep a standing notification with nothing to clear it |
| an archived schedule still reminding | an `ARCHIVED` schedule arrives `Withdrawn`; one with `remindersEnabled` false is absent from the list | leaving it `Active` posts a reminder for retired equipment |
| a group subject fanning out | a group-targeted schedule with five required members and three complete produces **one** subject whose body carries the progress, and **not five** (D-15) | one subject per member floods the provider and breaks the counted-once rule the dashboard relies on |
| a meter-only subject with no date | a meter-only schedule arrives with `dueOn = null` and `rule.hasMeter` true, and a meter rule with no baseline arrives `Active` with `dueOn = null` | a fabricated date makes a provider alarm on something that has no date (#21 AC 6's reason) |
| the rule's facts lost | `RuleFacts` carries basis, interval, unit, `hasMeter` and `seasonal` for each of a FIXED, a COMPLETION, a meter-only and a combined schedule, and the subject carries **no** schedule entity | a subject that carries the entity lets a provider re-derive recurrence, which is the coupling #28 exists to prevent |
| provenance leaking | two otherwise identical schedules whose latest meter reading came from a `MANUAL` and a `TELEMETRY` event produce **identical** subjects (#4 AC 7) | a branch on `EventSource` in the subject builder is the telemetry-specific scheduling branch #4 forbids |

## Strings

**None ratified and none drafted.** A subject's `title` and `body` are **composed from already-ratified material** — the schedule's own `title`, the status terms **OK · DUE SOON · DUE · OVERDUE · OUT OF SEASON · PAUSED · NO BASELINE**, the ratified progress form **"3 of 5 complete"**, and a date — and this brief introduces **no new sentence**. The notification's own wrapper text (the digest summary title and body) is **PROPOSED** and belongs to **B06**, which draws it; if B04's `body` composition needs a connective word beyond the ratified material, that is a finding for the controller and goes onto B06's PROPOSED list rather than being invented here.

## Invariant note

Invariant 44's "rebuildable from nothing" is **B06's** to prove; this brief's part is only that `BuildReminderSubjects` takes no provider bookkeeping as an input, so the desired state never depends on what the provider happens to remember. One test asserts that: the same subject list comes back with `schedule_local_delivery` empty and with it populated.

## Ordering

**After B02 and B03** (needs `statusOf`, `ScheduleState` and `GroupOccurrence`). **Before B06** and B10's findings rendering. Takes **lane B of wave 3** as soon as B03's domain lands, and **must land before wave 4 opens, because B06 consumes it** (master plan §14.3). **It may not ride wave 4** — wave 4 lane A is B06 and lane B is B08.

## Review gate

- Unit: `./gradlew :core:test --console=plain` → BUILD SUCCESSFUL, zero failures, zero skips; counts recorded.
- Structural, anchored:
  - `grep -rnE '^import (android|androidx)\.' core/src/main` → no match.
  - `grep -rniE '\b(todoist|notification|alarm|workmanager)\b' core/src/main/kotlin/com/loosecannon/servicetag/core/reminders` → no match outside a doc comment that names the later phase.
  - `grep -n 'enum class ProviderId' -A 3 core/src/main/kotlin/com/loosecannon/servicetag/core/reminders/ReminderPort.kt` shows **`LOCAL`** and nothing else.
  - `grep -n 'sealed interface SubjectKey' -A 3 …/ReminderPort.kt` shows `Schedule` and nothing else.
- No connected run: this brief draws nothing.

## Estimated size

Small. One file of types, one use case, one hash, one fake, two test classes. It should not need a split; if it grows past ~250 lines of brief the extra weight is probably B06's work leaking in, and that is a finding.
