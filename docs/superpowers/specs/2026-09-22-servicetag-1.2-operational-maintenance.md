# ServiceTag 1.2 — operational maintenance: DESIGN SPEC (revision 4, owner-ratified)

Status: **RATIFIED SPECIFICATION, revision 4**, 2026-09-22. Every decision the earlier drafts posed
has been ruled by the owner in `owner-rulings-2026-09-22.md` (D-1…D-28 plus the navigation ruling);
this revision states the ruled behaviour as **contract**, not as options. Revision 4 answers the
focused re-review `spec-review-rev3.md` (3 blocking, 12 should-fix, 8 notes), which confirmed the
D-8 `occurrence_closure` design and that every ruling landed. Revisions 1–3 and both reviews are
archived beside it. §9 is the ruling register — what binds, not what to choose. §11 maps each ruling
and each review finding to the sections that carry it. §12 lists what is still open: two user-visible
strings, and nothing else.

Not a plan and not an implementation. Next in the owner's L2 sequence: the master plan and briefs
B1–B15. S4 (§5.9) is **informational** and gates nothing (D-23 as amended 2026-09-22).

Scope source: issues #4, #55, #28, #24, #21, #11, #50, #5, #27, #49 (snapshots beside this file).
Governing documents: `docs/superpowers/planning-policy.md`, `docs/versioning.md`, `docs/api/v1.md`,
`tools/servicetag-mcp/README.md`, and `docs/design/` (D3 §7, D4 §5/§7/§8/§12/§13/§15, D5 §1–§12,
D7 Phase 3, D12 §5/§10/§16).

User-visible strings: the D-24 list is **RATIFIED** and is quoted as final throughout, and strings
already carried by the approved visual-design direction D12 are pre-ratified with it (§9.1). The only
strings still marked `PROPOSED` are the seven health-finding sentences B10 drafts and the "Close this
round" confirmation (§12). No private data; examples are generic; public-repository material.
Proportionality (owner ruling, 2026-09-22): one test per hazard class, not per permutation.

---

## 1. Purpose and scope

### 1.1 What 1.2 delivers

One canonical maintenance-scheduling engine and the local delivery path around it: **#4** the
provider-neutral recurrence and state model, targeting one Asset or one MaintenanceGroup; **#55**
maintenance groups with many-to-many membership and truthful per-member completion; **#28** the
`ReminderProvider` port and `reconcile` contract in `:core`; **#24** Android platform ownership —
permissions, channels, receivers, the no-exact-alarm policy; **#21** the local provider — a daily
inexact digest alarm plus a 12 h WorkManager backstop; **#11** nonce-protected notification quick
actions; **#50** the scan completion sheet; **#5** the dashboard with maintenance status; **#27**
reminder health with idempotent repair; **#49** per-tag placement labels. Plus the three surfaces
those ten presuppose and none owns: the **Maintenance destination** in primary navigation, a
**schedule editor** with the in-app complete / snooze / postpone / close / edit-recurrence actions,
and the **group screens** with both directions of #55's navigation (§7 briefs B8, B14, B15).

Version: `versionName` **1.2.0**, `versionCode` **13** (D-1; the unused 1.1.1 reservation in
`docs/versioning.md:33` is struck). Room schema **5 → 6**. Backup format **5 → 6**, a MINOR under the
clarification D-2 adds to `docs/versioning.md` (§3.5). API version stays **1**, extended additively
(D-19).

### 1.2 What 1.2 explicitly does not deliver

| Out | Why |
|---|---|
| #14 season **re-entry adjustment** (D5 §6 `AT_START` / `RESUME_CLAMPED`) | D-4: `seasonBehavior`, `INACTIVE_SEASON` and `PARKED` ship; the two re-entry columns are stored and **never read** in 1.2. #14 gives them meaning. |
| #15 part/supply identity and stock; `SUPPLY(id)` reminder subjects | #15 owns it; Phase 6. |
| #47 maintenance material requirements, installed configuration | #47 owns it (#4 comment, 2026-09-21). |
| #9 / #10 / #34 Todoist provider, `reminder_projection`, `provider_op`, `integration_account` | Phase 5. No projection or outbox table is created in 1.2. |
| #56 / #57 telemetry and BLE-fed measurements | The engine stays provenance-neutral so they need no engine change later (#4 AC 7). |
| #26 reminder-fatigue controls | NEXT. D-5's digest policy is fixed and conservative. |
| #25 provider-choice UI beyond a single enabled row | The table permits several; the editor writes one. |
| #44 interactive conflict resolution and asset mapping | Still a later release. |
| #18 the preferences screen | 1.2 carries only the two values #21 cannot work without — digest hour and the global reminders switch — in B6's scope (`issue-21.md:32`). |
| #17 / D4 §7 template-seeded default schedules | `04-domain-data-model.md:357-359` has seed templates create "definitions, profiles, and default schedules"; in 1.2 creating an asset from a template still creates **no** schedule, and templates do not seed groups. |
| `supplies` and `sync_problems` notification channels | D-20 = B: only the two channels 1.2 uses are created. #24 AC 3 is amended (§12). |
| Nested groups, rule-based membership, group NFC identity, fleet analytics | #55 "Out of scope". |
| A group deep link | D-17: no 1.2 surface requires one — the Maintenance destination reaches every group in-app. |
| "Close this round" on an **asset**-targeted schedule | D-8 exists to unstick a partially complete *group* round; `occurrence_closure` is target-agnostic, so widening it later needs no schema change. |
| `asset_event.cost_minor` / `currency` (D4 §5) | No 1.2 flow reads them; do not add columns nothing reads. |

---

## 2. Domain model

Written against the shipped model: `core/src/main/kotlin/com/loosecannon/servicetag/core/model/`
(`Asset.kt:5-30`, `Journal.kt:9-59`, `Season.kt:9-57`, `TagBinding.kt:13-25`, `Ids.kt:3-22`), the ports
in `core/…/core/ports/Repositories.kt:21-109`, and the use-case shape in
`core/…/core/usecase/LogEvent.kt:27-33` (validate, then one `uow.write`, per `core/…/core/ports/UnitOfWork.kt:7`).
`Clock` (`core/…/core/ports/Clock.kt:3`) supplies `nowMillis()` only; the engine needs a **date**, so
1.2 adds a `Today` port (or a `LocalDate` parameter) and never reads a clock itself (D5 line 13:
"`T` … passed in explicitly everywhere").

### 2.1 `MaintenanceSchedule` (new aggregate root)

Configuration plus exactly two user overrides (D4 §8, `docs/design/04-domain-data-model.md:363-390`).
New id type `ScheduleId` beside the existing inline value classes (`Ids.kt:3-22`).

**Target.** `target: ScheduleTarget` — a sealed type with `AssetTarget(AssetId)` and
`GroupTarget(GroupId)`, mirroring `TagTarget` (`TagBinding.kt:7-11`). Persisted as a nullable
`asset_id` / `group_id` pair with `CHECK ((asset_id IS NULL) <> (group_id IS NULL))`. D4's `asset_id`
becomes nullable — the #55 extension #4's comment asked for, and why #55 is specified with #4.

**Recurrence.**

- Time side: `timeInterval: Int?` + `timeUnit: TimeUnit?` (`DAY | WEEK | MONTH | YEAR`),
  `timeBasis: FIXED | COMPLETION`, `anchorOn: LocalDate`, `leadDays: Int`.
- Meter side (D-3: **in 1.2**): `meterDefinitionId: DefinitionId?` — a definition with
  `isMeter = true` (`Journal.kt:15`) — `meterInterval`, `anchorMeter`, `meterLead`.
- `CHECK (time_interval IS NOT NULL OR meter_definition_id IS NOT NULL)`. Both present = "whichever
  first"; due when either side is due (D5 §4).
- **FIXED** due dates lie on `anchorOn + k·interval`, computed with a multiplier, never iteratively
  (D5 §2.1), so month-end and leap-day clamping cannot drift. On a termination of the occurrence due
  `D` with effective date `E`: `nextDue = smallest seriesDate(k) > max(D, E)`. A very late
  termination skips forward; **no backlog is ever created** (D-7).
- **COMPLETION**: `nextDue = E.plus(interval)`; with no termination, `anchorOn`.
- A never-terminated FIXED schedule's occurrence is **pinned from immutable configuration**
  (D-27): `computedDueOn = smallest seriesDate(k) >= max(anchorOn, createdOn)`. It never re-floats on
  Today, and the same pin applies after the sole completion is deleted. **Amended at the release gate (2026-09-22, controller ruling):** the floor is `updated_at`'s date **in the device zone** (`ScheduleRecompute.rebuild` takes the zone as a pure input; `RecomputeSchedules` supplies the device zone) — reading it at UTC pushed an evening-created schedule's first occurrence a whole interval out for owners west of UTC; the schedule can be due on the owner's own day.
- **The pin's floor after a recurrence edit** is the **edit date**: on a schedule with no
  terminations, `computedDueOn = smallest seriesDate(k) >= max(anchorOn, editedOn)`, where `editedOn`
  is the schedule's `updated_at` date at the edit. Without this floor, re-anchoring an old,
  never-terminated schedule today would pin it immediately overdue. The pin is still immutable between
  edits and still never re-floats on Today, so invariants 23 and 25 hold; only an explicit edit moves
  the floor. `05-scheduling-semantics.md:346` ("edited to monthly with a new anchor Oct 1: due =
  first series date ≥ T") is **superseded** by this rule and by D-27, and is corrected together with
  D5 §5 (B13 owns both edits).
- #11's third flavour ("fixed cadence unless the schedule says otherwise") is **not adopted** (C8).

*Termination* is defined in §2.9: a completion, or an explicit round closure. Both carry an effective
date, and both bases advance from it identically.

**Season.** `seasonBehavior: FOLLOW_ASSET | IGNORE`, plus `seasonReentry` and
`seasonReentryOffsetDays`, which are **stored and never read in 1.2** (D-4). The window lives on the
Asset (`Asset.kt:28-29`) and `Season.inSeason` already ships (`Season.kt:27-38`). A group-targeted
schedule is `IGNORE` only; `FOLLOW_ASSET` on a group target is rejected (D-28).

**Other config.** `title`, `description`, `profileId: ProfileId?`, `completionMode: QUICK | FORM`,
`remindersEnabled`, `status: ACTIVE | PAUSED | ARCHIVED`, `createdAt`, `updatedAt`.
**Overrides.** `postponedDueOn: LocalDate?` — a one-off replacement for the current occurrence — is
the row's **only** override. The snooze instant is not here: D-13 puts it in the device-local
`schedule_local_delivery` table (§2.5).

**What a completion or a closure may write on this row — nothing, unless the postponement is set.**

> A completion is an insert into `asset_event` plus a `schedule_state` rebuild; a closure is an
> insert into `occurrence_closure` plus a rebuild. Either touches the `maintenance_schedule` row
> **only** when `postponed_due_on` is actually set; one that clears nothing writes no column and does
> not bump `updated_at`. The same rule holds for each group member's completion.

#4's operations table reads "Complete … clears `postponed_due_on` and `snoozed_until_at`"
(`issue-4.md:42`), which an implementer would render as an unconditional update. It must be
conditional: `IDENTICAL` compares **every** backup-format field, "the last-modified stamp included …
as `incoming == local.toDto()`" (`core/…/core/merge/MergePlanner.kt:62-63`, `docs/api/v1.md:247`).
Further invariants, restated in §6: exactly one target; at most one current occurrence;
`effectiveDueOn = postponedDueOn ?: computedDueOn`; a group-targeted schedule carries no meter rule
(asset-scoped, `Journal.kt:10`) and no `profileId` (asset-scoped, `Journal.kt:30`) — D-12; status is
**never stored**.

### 2.2 Derived due state, and where `prevDue` comes from

`ScheduleState` is derived and fully recomputable. `ScheduleRecompute.rebuild(schedule, events,
closures, membership, T)` is the **only** write path into it (D4 §8 lines 407-429, D5 §5). It runs
after every event insert/update/delete, every closure insert, every schedule edit, every import, and
in the digest and backstop runs. Materialised: `lastCompletedOn`, `lastCompletionEventId`,
`lastCompletedMeter`, `currentMeter`, `lastTerminationEffectiveOn`, `lastTerminationKind`
(`COMPLETED | CLOSED | NONE`), `computedDueOn`, `computedDueMeter`, `effectiveDueOn` (the sort key),
`seasonActive`, `computedForOn`, `computedAt`. `lastTerminationEffectiveOn` is the termination's
**effective date** `E`, not its occurrence key: the key `D` is not materialised, because `rebuild`
recomputes it from `occurrence_on` (invariant 12 is stated against the effective date).
`schedule_state` is derived and **not exported**, so adding the two termination fields costs nothing
downstream.

**`prevDue` is read, not reconstructed.**

> For FIXED, `prevDue` — the occurrence a termination satisfied — is the terminating row's
> `occurrence_on`, never a series date reconstructed from a completion's `occurred_on`. This is what
> `occurrence_on` is for beyond idempotence, and why the column is required rather than convenient. A
> completion event with no `occurrence_on` — a pre-1.2 row, or one re-pointed to a schedule by hand —
> falls back to the largest series date `<= occurred_on`, which is correct except for an early
> completion; the fallback is documented as approximate, and
> `docs/design/05-scheduling-semantics.md` §5 is corrected to match.

Why it matters: D5 §5's reconstruction ("the largest series date `<= last_completed_on`",
`05-scheduling-semantics.md:135-137`) returns the *wrong* occurrence for an early completion — anchor
Jan 1, quarterly, due Apr 1, completed Mar 20 reconstructs `prevDue = Jan 1` and yields Apr 1 where
D5 §10.1 (`:291`) requires **Jul 1**.

**Status is a pure function of the row plus `T`**, computed at read time, never stored:
`OK | DUE_SOON | DUE | OVERDUE | INACTIVE_SEASON | PAUSED | NO_DATA`, worst-of the time and meter
sides, `OVERDUE > DUE > DUE_SOON > OK` (D5 §1). Snooze does not appear in status. Time zone (D-6):
the engine sees no instant, `T` is the injected device-local date, a zone change never changes *what*
is due, only *when* the phone says so (D5 §11), and `tzId` (`Journal.kt:54`) stays audit-only.

### 2.3 `MaintenanceGroup` and membership (new aggregate root)

```
MaintenanceGroup(id: GroupId, name, description, archivedAt: Long?, createdAt, updatedAt,
                 members: List<GroupMember>)
GroupMember(id: String, assetId: AssetId, sortOrder: Int, addedAt: Long, removedAt: Long?)
```

Members are **child rows of the group aggregate**, as `ProfileField` / `ProfileConsumable` are child
rows of `EventProfile` (`Journal.kt:24-35`), with durable ids that survive backup verbatim (D-14;
`Journal.kt:26-27`). There is **no** `location` field (D-26): `description` carries context. A group
is **not** an Asset, is not in the Asset parent/child tree, has no serial number, and **never
receives an NFC identity** (#55 "NFC behavior"); `name` is descriptive only (#55 AC 12 — no name,
location or category is ever identity).

**Membership is append-only in its temporal fields.** The index is
`UNIQUE(group_id, asset_id, added_at)`, with the application rule — enforced in the use case, since a
partial unique index is not expressible in Room (`04-domain-data-model.md:588-589`) — that **at most
one membership row per `(group, asset)` has `removed_at IS NULL`**. Removing a member stamps
`removed_at`; re-adding the same asset later **inserts a new row** with a new durable id and a new
`added_at`, and never clears an existing `removed_at`. No past occurrence's required set can
therefore change, and invariant 33 holds against every membership operation. `#55`'s sketch
(`issue-55.md:50`) proposed `UNIQUE(group_id, asset_id)`; that predates temporal membership and
cannot represent remove-then-re-add without rewriting the window `required(D)` keys off, which would
break `issue-55.md:163` (AC 8).

`addedAt` and `removedAt` are **server-stamped and appear in no command** (§4.1; `docs/api/v1.md:145-154`
— a request shape is a subset of a response shape). A membership row is never hard-deleted while any
completion or closure references an occurrence its window covered (D-16, invariant 8): archiving a
group hides it and its schedules and retains history, and archiving or retiring a member **Asset**
leaves membership rows untouched, excludes it from *new* occurrences by lifecycle, and leaves an open
round under D-10.

### 2.4 Occurrences and completions — what rows exist

**No general occurrence table.** #4 is emphatic that there is one current occurrence and that
everything is derived (D5 §5, invariants 1-3); #55 is equally emphatic that occurrence membership
must be stable. Both hold because the occurrence's identity and its member basis are **derived from
history**. The one materialised occurrence-scoped row is §2.9's closure fact, which exists **only**
for a round the owner explicitly closed — never for an ordinary one.

1. A completion is an ordinary `asset_event` on the **real asset**, through the shipped event path
   (`LogEvent.kt:27-33` / `EventCommands.kt:31-42`), with two new columns: `schedule_id` (the
   completion link, D4 §5 line 229) and `occurrence_on`.
2. `occurrence_on` is stamped at write time from the schedule's `computedDueOn` — **not** the
   postponed date, so a postpone cannot move an occurrence key. For FIXED it is a series date; for
   COMPLETION it derives from the previous termination, already history. Either way it is immutable
   once written, and it is what §2.2's `prevDue` reads. A recurrence edit abandons an open partially
   complete occurrence (D-9): the recorded member completions stay as truthful history and the edited
   rule creates the new current occurrence.
3. `source = SCHEDULE_QUICK_COMPLETE` for a one-tap completion; a form completion stays `MANUAL`, and
   `schedule_id` carries the relationship either way (D-18b). `details_pending` is 1 when a
   completion is created minimally against a `FORM` schedule (D4 §5 line 237) — reachable from the
   notification quick action and from `POST /v1/schedules/{id}/complete` with no `values`, and from
   nowhere else.
4. **Idempotence is a database constraint**, not a discipline: `UNIQUE(schedule_id, occurrence_on,
   asset_id)`. SQLite treats NULLs as distinct, so the index is inert for non-completion events
   (D4 §15 lines 588-589 rely on the same property). This is what makes #55 AC 7 and #50 AC 12
   provable rather than hoped for.

   > **Added at implementation (2026-09-22, ServiceTag 1.2 / B13): the meter-only case, which item
   > 2 above does not cover.** A **meter-only** schedule — a meter rule and no time rule — has no
   > calendar occurrence to be stamped from: `computedDueOn` is null, so its completion is written
   > with `occurrence_on` **null**. Two consequences, both deliberate:
   >
   > - **The idempotence index does not apply to it.** SQLite treats NULLs as distinct, so two
   >   completions of the same meter-only schedule are two rows, not a conflict. That is correct:
   >   there is no occurrence for the second one to duplicate, and the rule advances from the
   >   reading each completion carries.
   > - **`prevDue` has nothing to read**, and nothing to reconstruct either. It is not the
   >   approximate FIXED fallback §2.2 describes: a meter-only schedule has no series at all, and
   >   its next due meter is `lastCompletedMeter + meterInterval`.
   >
   > A null `occurrence_on` on a **dated** schedule's completion is a different thing — a pre-1.2
   > row, or one re-pointed by hand — and §2.2's approximate fallback is for that case only.

**Asset-targeted occurrence.** One required asset: the schedule's own. Complete → insert event →
`rebuild` → clear the postponement if it is set.

**Group-targeted occurrence.** A checklist of members (#55 "Group completion semantics"):

- The occurrence's **open instant** is the maximum `created_at` over the previous occurrence's
  terminating rows — its member completion events and, if it was closed, its closure row — or the
  schedule's `created_at` for the first occurrence. `created_at` is exported and compared by the
  merge, so the instant is identical on every set of rows; `occurred_on` and `closed_on` are
  deliberately **not** used, because a backdated completion must not move the membership basis. The
  occurrence's **open date** is that instant's calendar date in the device zone.
- **Required set** = members whose `[addedAt, removedAt)` window covers that open instant (D-10). A
  pure function of membership rows plus history: no snapshot table, and historical completeness never
  depends on today's membership list (#55 AC 8).
- **Completed set** = member assets holding an event with `(schedule_id, occurrence_on)`.
- The occurrence is complete when the completed set is **non-empty and covers** the required set;
  only then does `rebuild` advance the schedule on a completion. Partial completion leaves it open
  and the schedule due (#55 AC 6, D-7); the way out is §2.9's **"Close this round"**.
- A current occurrence whose **required set is empty** — an empty group, or one whose members were all
  removed — is not actionable: it is neither offered for completion nor counted as due, it can never
  be closed, and the schedule reports `NO_DATA`. Emptiness never means "complete". Creating a
  group-targeted schedule on a group with no members is refused (422).
- "Complete all" writes one event per not-yet-completed required member in one `uow.write`; the unique
  index makes a repeat a no-op rather than a duplicate (#55 AC 7).
- Completing one member **never** writes an event on another (#55 AC 5) and never on a non-member.
  Progress ("3 of 5 complete") is derived, for §2.6 and §2.8.
- An asset in two groups with the same operation gets two schedules and two occurrences, with no
  semantic deduplication; the editor shows a non-blocking warning (D-11).

### 2.5 Reminders as projections (#28), and device-local delivery state

One provider-neutral port in `:core`, with no Android and no provider type in its signature
(#28 AC 1). Shape as D3 §7.1 (`docs/design/03-target-architecture.md:184-203`):

```kotlin
data class ReminderSubject(                           // key: SCHEDULE(id) in 1.2, SUPPLY(id) is #15
    val key: SubjectKey, val title: String, val body: String,
    val dueOn: LocalDate?,                            // effective due; null for meter-only or parked
    val leadDays: Int,
    val state: SubjectState,                          // ACTIVE | PARKED(reentryOn) | COMPLETED | WITHDRAWN
    val rule: RuleFacts?,                             // basis, interval, unit, hasMeter, seasonal
    val contentHash: String,
)
interface ReminderProvider {
    val id: ProviderId
    suspend fun reconcile(subjects: List<ReminderSubject>): ReconcileReport
    suspend fun pullChanges(): List<RemoteChange>     // LOCAL: always empty
    suspend fun health(): List<HealthFinding>
}
```

`reconcile` is the **whole write surface**: it receives the desired state of every subject the
provider owns and makes the provider match it. There is deliberately no "create one reminder" call,
which is what makes idempotence structural (#28 AC 2) and #27's repair safe (#27 AC 3);
`contentHash` suppresses no-op work (#28 AC 3). Subjects come from `schedule_provider` rows: **one
subject list per enabled provider** (#28 AC 4). A group-targeted schedule is **one** subject, not one
per member, with the progress line in its `body`. A seasonally inactive or paused schedule arrives as
`PARKED(reentryOn)`, never absent and never overdue (#28 AC 5, D-4). `rule` carries the rule's *facts*
so a future provider can decide whether its own recurrence engine can carry the subject; the provider
never sees the schedule entity.

**The local provider has no projection rows** (D4 §10 line 476). Its "projection" is the posted
notifications plus the armed alarm — which is why it is rebuildable from nothing and why 1.2 creates
no projection or outbox table. Its bookkeeping lives in one device-local table (D-13, D-21):

```
schedule_local_delivery
  schedule_id      TEXT PK   FK maintenance_schedule ON DELETE CASCADE
  snoozed_until_at INTEGER?  -- D-13: the snooze instant; suppresses this provider only
  last_notified_at INTEGER?  -- D-5: drives the 3-day overdue re-notification
  first_entry_seen INTEGER   -- D-5: DUE_SOON is announced once
  action_nonce     TEXT?     -- D-21: the current notification's nonce
  nonce_issued_at  INTEGER?
  updated_at       INTEGER
```

Nothing in it is canonical, exported or merged. Losing it costs at most one repeated notification,
which is why it is the right place for all four facts. Invariant 44's "rebuildable from nothing" means
the *notifications and the alarm*; this table is a delivery optimisation, and the provider must behave
correctly — at worst noisily — when it is empty. The nonce is persisted, not in-process, so a quick
action still works after process death, and it is cleared on successful use and on replacement or
reconcile (D-21).

### 2.6 Dashboard and the Maintenance destination (#5, navigation ruling)

Primary navigation becomes **Dashboard · Assets · Maintenance** (navigation ruling). "Maintenance"
hosts due work, schedules, maintenance groups and reminder health; the NFC scan stays an interaction
mechanism, not a tab — consistent with D12 §16, which already removed the Scan tab in 2B-2
(`12-visual-design-apollo-service-binder.md:1217-1223`). The `servicetag://schedule/<uuid>` deep link
(D-17) and the scan sheet both land inside Maintenance.

The dashboard is a read model over `Asset` + `ScheduleState`, no new canonical rows, extending the
shipped `DashboardState` / `DashboardViewModel`
(`app/src/main/kotlin/com/loosecannon/servicetag/ui/dashboard/DashboardViewModel.kt:40-50`, `:109-136`),
which today filters by lifecycle and search only.

- Sort key is `effective_due_on`; status computed at read time, so a stale status is unrepresentable
  (D4 §8 lines 423-425). Because re-entry is deferred (D-4) and the FIXED pin is immutable (D-27), the
  stored sort key is `T`-independent and cannot mis-sort behind a stale `computed_for_on`.
- **The section order is D12 §10's, and every state has a home.** The order is fixed at
  ATTENTION · UPCOMING · CURRENT · OUT OF SEASON, empty sections omitted
  (`12-visual-design-apollo-service-binder.md:706-707`). `OVERDUE` and `DUE` take ATTENTION;
  `DUE_SOON` takes UPCOMING; `OK` takes CURRENT; `INACTIVE_SEASON` takes OUT OF SEASON. **`NO_DATA`
  takes ATTENTION**, because it is actionable — the missing meter baseline is repairable with "Log
  meter reading" and #27 raises a finding for it. **`PAUSED` appears in no dashboard section at
  all**: the dashboard answers what needs attention and a paused schedule needs nothing, so it is
  listed under Maintenance → Schedules carrying the PAUSED state label. #5 AC 2's requirement is
  about a seasonally inactive schedule, which OUT OF SEASON satisfies.
- `INACTIVE_SEASON`, `PAUSED` and `NO_DATA` each render distinctly from `OVERDUE` wherever they
  appear (#5 AC 2, D5 §1, D12 §5).
- **A due schedule on a component asset is never hidden.** The shipped list shows only top-level
  assets while the query is blank (`DashboardViewModel.kt:121`), which would hide a component's
  schedule and break #5 AC 1. A component row with an actionable schedule is **promoted to its
  attention rank** and rendered with its parent named, reusing the `parentName` the row already
  carries (`DashboardViewModel.kt:124`); the blank-query "parts live on their systems" rule still
  governs every non-actionable row.
- A group-targeted schedule is **one row that expands to its members' completion state**, and it
  **counts once** in every due total however many members are outstanding (D-15); the progress line
  communicates the member workload.
- Asset detail gains a schedules section and a "groups this asset belongs to" section — both
  directions of #55's navigation minimum (`issue-55.md:123-127`), owned by B15.
- Archived assets stay out of the default view and keep their history (#5 AC 3). A health badge
  appears when any finding is ≥ WARN (#27, D3 §7.3 line 245). The existing search-field set
  (`DashboardViewModel.kt:60-67`) is unchanged.

### 2.7 Multi-tag placement labels (#49)

**No schema change.** `nfc_tag.label` already exists (`TagBinding.kt:19`), `nfc_tag.asset_id` is
already non-unique (`app/…/data/room/entities/NfcTagEntity.kt:25-29` declares the unique index on
`(payload_format, payload_key)` only), and the label already round-trips through backup. #49 is a
UI-and-wording issue: the label is reused as the human-readable **"Tag placement"** value rather than
a second structured column. Binding and provisioning accept an optional placement, and editing it
rewrites **no NFC payload** and changes no binding (#49 AC 4); asset detail lists **all** tags with
label and status, not only the first (#49 AC 6); the scan result surfaces the label when present
(#49 AC 3); lost, retired and rebound tags retain their historical label and a blank label stays
valid; and one asset with scan points in two places is one Asset with two tags — never a child Asset,
never a duplicate schedule (#50 "Multiple tags").

### 2.8 Scan completion sheet (#50 + D5 §7A)

Built on the shipped resolver: `ResolveTag` returns a `Resolution` and the UI switches on that and
nothing else (`core/…/core/usecase/ResolveTag.kt:16-30`, `:45-58`). Only `Resolution.OpenAsset` may
reach the sheet; `Unbound`, `Revoked`, `UnknownV1`, `NeedsNewerApp`, `NotOurs` and `PreSplitLink`
never do (#50 AC 10). The reader-mode hold stays owned by `ReaderMode`
(`app/…/ui/nfc/ReaderMode.kt:20-25`, `:50`) — 1.2 adds a destination, not a second NFC session.

Flow: resolve tag → asset → evaluate the asset's schedule state (including group schedules it is a
required member of) → no actionable work ⇒ open the asset as today; otherwise the sheet, titled
**"Maintenance"** (D-24).

**Which states appear** (D-18a): `DUE` and `OVERDUE` always; `NO_DATA` when it has a repair action —
the missing meter baseline, offered as **"Log meter reading"**; `DUE_SOON` only as a passenger, when
the sheet is already open for another actionable item. `OK`, `INACTIVE_SEASON` and `PAUSED` never
appear, and neither does an archived or disabled schedule. Items are ordered by §2.6's attention
ordering. This satisfies both inputs: #50 excludes the quiet states "unless the canonical completion
flow specifically needs the owner to repair `NO_DATA`" (`issue-50.md:54`), and D5 §7A admits DUE_SOON
only when other actionable items exist (`05-scheduling-semantics.md:208`), honouring #50's "scanning
must not turn every future maintenance item into a completion checklist" (`:58`).

**What the sheet shows.** Which asset was scanned; the scanned tag's placement label when present;
and per item — the state word, the due date and/or meter threshold, the current meter value where
relevant, the last completion's date and its key readings, and one concise line on why it is
actionable now (`05-scheduling-semantics.md:219-221`). Quick versus form is visible before selection,
and **"Open asset"** always reaches the ordinary detail.

**What the sheet can do.** **"Complete selected"**; **"Review maintenance"** (the schedule detail);
**"Snooze"** (notification only); **"Postpone"** (this occurrence); **"Not now"**. These are
semantically distinct and never aliases for completion (`:230-232`). Completion asks **"When was this
done?"**, defaulting to today, with an optional time, so a backdated completion is first class
(`:226-229`) — a D7 Phase 3 exit criterion (`07-implementation-sequence.md:114`), not a nicety.

**The scan itself never mutates domain state.** `servicetag://tag/<id>` remains navigation-only (#19;
`core/…/core/links/DeepLinkRoute.kt:8-27`), and mutation happens only on explicit in-app action
(#50 AC 6, AC 11). `ResolveTag` does write `lastScannedAt` (`ResolveTag.kt:47`) — informational per
D4 §12 line 544, not domain state.

Completion delegates entirely to the #4 use cases: `QUICK` writes its minimal event after explicit
selection; `FORM` opens the profile form and fabricates nothing; a meter schedule cannot complete
without its required reading; several forms complete **sequentially and explicitly**, never partially
and silently. Completion then runs `reconcile`, so the notification is quiesced by canonical state and
not by deleting a notification (#50 AC 8). For a scanned member of a due group occurrence the sheet
offers that occurrence and completing it marks **this member only** (#50 comment, 2026-09-21).

### 2.9 Round closure — the D-8 occurrence-closure fact

D-8 rules in the owner action **"Close this round"** and rules out a mutable `closed_occurrence_on`
column. Closure is therefore **immutable, exported history**: one append-only row per closed round.

```
occurrence_closure                       -- sparse: rows exist ONLY for explicitly closed rounds
  id            TEXT PK                  -- durable UUID, like every other top-level row
  schedule_id   TEXT NOT NULL            -- FK maintenance_schedule ON DELETE CASCADE
  occurrence_on TEXT NOT NULL            -- the occurrence key, the same value events carry
  closed_on     TEXT NOT NULL            -- the termination's effective date: what COMPLETION
                                         -- advances from, and what FIXED compares against D
  created_at    INTEGER NOT NULL         -- audit, and the next round's open-instant input
  UNIQUE(schedule_id, occurrence_on)
  INDEX(schedule_id)
```

There is **no `updated_at`**, because the row is immutable, and a stamp nothing ever moves would be a
lie. Nothing in the app issues an `UPDATE` or a `DELETE` against this table; the only way a row leaves
is the `CASCADE` when its schedule is deleted. It is deliberately **not** a general materialised
occurrence table: an ordinary completed round produces no row, so a schedule that is always finished
on time carries zero closure rows for its whole life, and normal occurrences stay derived exactly as
§2.4 describes. Two fields carry semantics and one is audit — nothing about the round's membership or
progress is copied, because all of that remains derivable from the membership rows and the events.

**What "Close this round" writes.** In one `uow.write`: exactly one `occurrence_closure` row
`(new uuid, schedule_id, occurrence_on = the schedule's current computedDueOn, closed_on, created_at
= now)`, then `rebuild`. It writes **no column on `maintenance_schedule`** (so §2.1's rule and
invariants 68 and 69 hold), and **no `asset_event` on any asset** — which is the whole point: a closed
round never claims anybody did the work. Closing the same round twice is refused: the unique index
stops the second write and the route answers 409 `OCCURRENCE_ALREADY_CLOSED`, leaving the first
closure unchanged. Once a round is closed, no further completion may be written with that
`occurrence_on` (invariant 39, a 409). In 1.2 the action is offered only on group-targeted schedules
(§1.2), and only on a round whose required set is non-empty (invariant 77).

**Who sets `closed_on`, and its range.** `closed_on` is the effective date the recurrence advances
from and the row can never be amended, so an unbounded caller value would permanently move a
schedule's future. It therefore mirrors D-25, which the owner already ruled for completions:

> `closedOn` defaults to today and may be **any date from the occurrence's open date through today,
> inclusive**; a future date, or one before the occurrence opened, is 422 `CLOSED_ON_OUT_OF_RANGE`.
> The occurrence's open date is §2.4's open instant as a calendar date in the device zone. The in-app
> action (B14) offers today by default and the same past range, using the ratified "When was this
> done?" affordance; the API accepts the same range, so the two paths cannot diverge — D-25's
> principle is that the API must not be unable to produce data the app can, nor able to produce data
> it cannot.

**A consequence of invariant 39, stated deliberately.** Once a round is closed, no completion can
ever be recorded against that `occurrence_on`. Work an owner does after closing a round is logged as
an ordinary journal event with no `schedule_id`. That is intentional: a closed round is a statement
that the round ended unfinished, and the spec will not let a later edit turn it into a claim that it
was finished.

**Termination — how `rebuild` reads closures and events together.** The schedule's history is the
ordered set of **terminated occurrences**, and a closure is a termination with a date, so both
recurrence bases advance from it exactly as they do from a completion. The function is total:

```
terminations(schedule, events, closures, membership):
  for each occurrence key D in this schedule's events or closures:
    C(D) = events with (schedule_id, occurrence_on = D)      // may be empty
    R(D) = required members of D                             // may be empty
    if R(D) is empty            -> D is not a termination     // invariant 77: not actionable, NO_DATA
    else if C(D) is non-empty and assets(C(D)) ⊇ R(D)
                                -> (D, max occurred_on over C(D), COMPLETED)
    else if a closure exists for (schedule, D)
                                -> (D, max(closed_on, max occurred_on over C(D) or closed_on), CLOSED)
    else                        -> D is still open; not a termination
  ordered by (D, effectiveOn)
```

The effective date of a closed round **with no completions at all** — the ordinary close — is its
`closed_on`. An occurrence whose required set is empty is **never** a termination, whatever rows it
carries: emptiness is not completion. Five consequences, each of which is why this shape was chosen:

- **A full completion beats a closure for the same occurrence.** A stray closure for a round that is
  in fact fully completed is inert. Local writes cannot create that ambiguity (invariant 39), but a
  merge can, and this precedence makes the outcome deterministic without adding a cross-table check to
  the planner.
- **`max(closed_on, …)` keeps the effective date monotone** even if a member completion carries a date
  later than the closure date.
- **Both bases advance from a closure.** For COMPLETION, `nextDue = closed_on + interval`. For FIXED,
  `closed_on` is not inert: it enters `nextDue = smallest seriesDate(k) > max(D, E)`, so a late
  closure skips the series forward exactly as a late completion does and an early closure does not
  move the series. No special case was needed for either.
- **The same history reproduces the same recurrence after later rounds advance.** Round 1 closed
  2026-04-20 then round 2 completed 2026-07-03 gives two terminations; `last` is round 2 and the
  closure is simply older history. A recurrence edit does **not** touch existing closures: their
  `occurrence_on` values may lie off the new series, which is harmless because `nextDue` is computed
  against the new series from `max(D, E)`, so invariant 11 still holds — an implementer must not
  "tidy" old closures after an edit.
- **And after an event is deleted.** Delete a member completion of round 2 and round 2 is no longer
  fully completed and has no closure, so it is *open* again; `last` falls back to round 1's closure and
  the due date returns to the value derived from `closed_on`. Deleting a completion moves the due date
  back (invariant 24) and reopens the round, which is the honest answer and needs no stored pointer.

A closed round is also **immune to membership churn**: it stays `CLOSED` whatever `required(D)` later
becomes, whereas a completed round is not — a membership change can make `required(D)` grow and reopen
it. Closure is the more durable of the two facts, which is what D-8 asked for.

`schedule_state.lastTerminationEffectiveOn` / `lastTerminationKind` materialise `last`'s effective
date and kind for the UI; `lastCompletedOn` keeps its narrower meaning — the latest member
*completion* — so "last done" never reports a round nobody did.

**In the merge.** `occurrence_closure` is a top-level exported table with its own `MergeTable` member,
and it follows the planner's existing two-identity shape (`MergePlanner.kt:253-300`) exactly:

| Situation | Verdict |
|---|---|
| the row id is absent here and `(schedule_id, occurrence_on)` is unclaimed | `INSERT` |
| the row id is here and every backup-format field matches | `IDENTICAL` |
| the row id is here and any field differs | `CONFLICT / CONTENT_DIFFERS` |
| a local row under a different id holds this `(schedule_id, occurrence_on)` and is otherwise equal field for field | `IDENTICAL / CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW` |
| a local row under a different id holds it and differs (a different `closed_on`, say) | `CONFLICT / CLOSURE_DIVERGED` |
| two rows of the same archive claim one `(schedule_id, occurrence_on)` | `CONFLICT / CLOSURE_DUPLICATED_IN_ARCHIVE` |
| the schedule it names is neither here nor being inserted | `CONFLICT / OWNER_NOT_AVAILABLE` |

The equivalence test is the shipped one, `dto.copy(id = local.id) == local.toDto()`
(`MergePlanner.kt:270`), so two devices that closed the same round on the same day merge cleanly while
a genuine disagreement about *when* a round was closed is a conflict for a human — the same treatment
`SCHEDULE_OCCURRENCE_TAKEN` gives two devices that both recorded one member's completion. A closure
has no `updated_at` and no mutable field, so a re-imported unchanged closure is **always** `IDENTICAL`;
there is no analogue of the `updatedAt` hazard §2.1's conditional-write rule exists to defuse. There
is still no `UPDATE` verdict, so no closure this phone already had can be altered by an import.

One asymmetry, recorded rather than fixed: deleting a schedule preserves its completion events
(`schedule_id` SET NULL, §3.1) but cascades its closures away. A closure is meaningless without its
schedule, and no 1.2 route deletes a schedule (invariant 76), so it is unreachable in 1.2.

---

## 3. Schema and backup

### 3.1 Room schema 5 → 6

The shipped database is `version = 5` (`app/…/data/room/AppDatabase.kt:38`),
`AppGraph.SCHEMA_VERSION = 5` (`app/…/di/AppGraph.kt:237`), migrations chained at `AppGraph.kt:88`,
schemas committed under `app/schemas/{1..5}.json`. **D4 §15's version plan (lines 590-594) is stale**:
it put schedules at v3 and attachments at v4; attachments actually shipped at v5, so schedules land at
**v6**. New tables — all `CREATE TABLE` plus indices, in the `MIGRATION_4_5` shape
(`app/…/data/room/Migrations.kt:211-235`), copied verbatim from the exported `6.json`:

| Table | Key | Indices | FKs / notes |
|---|---|---|---|
| `maintenance_group` | `id` | `name` | — |
| `maintenance_group_member` | `id` | `UNIQUE(group_id, asset_id, added_at)`, `group_id`, `asset_id` | group CASCADE, asset CASCADE. At most one row per `(group, asset)` with `removed_at IS NULL`, enforced in the use case (§2.3) |
| `maintenance_schedule` | `id` | `(asset_id, status)`, `(group_id, status)`, `meter_definition_id`, `profile_id` | asset CASCADE, group CASCADE, definition RESTRICT, profile SET NULL |
| `schedule_provider` | `(schedule_id, provider)` | — | schedule CASCADE |
| `occurrence_closure` | `id` | `UNIQUE(schedule_id, occurrence_on)`, `schedule_id` | schedule CASCADE. **Immutable and append-only**: no `updated_at`, no UPDATE, no DELETE (§2.9) |
| `schedule_state` | `schedule_id` | `effective_due_on` | schedule CASCADE. Derived: **never exported** |
| `schedule_local_delivery` | `schedule_id` | — | schedule CASCADE. Device-local: **never exported, never merged** (§2.5) |

Altered table — `asset_event` gains three columns and two indices: `schedule_id TEXT NULL` FK
`maintenance_schedule` **SET NULL** (the completion link, D4 §5 line 229 — archiving a schedule keeps
it, deleting one nulls it); `occurrence_on TEXT NULL` (§2.4's occurrence key);
`details_pending INTEGER NOT NULL DEFAULT 0` (D4 §5 line 237); `UNIQUE(schedule_id, occurrence_on,
asset_id)`; index `(schedule_id, occurred_on DESC)` (D4 §5 line 240). The shipped
`UNIQUE(source, source_ref)` (`app/…/data/room/entities/JournalEntities.kt:167`) is **not** overloaded
for occurrence identity; `source_ref` stays free for sync provenance.

`EventSource` widens from `MANUAL, IMPORT` (`Journal.kt:38`) to **all three of D4 §5 line 235's
remaining members at once** — `SCHEDULE_QUICK_COMPLETE`, `TODOIST_SYNC`, `TELEMETRY` — though only the
first is written in 1.2 (D-18b). `enumOrCorrupt` throws `BackupCorrupt` on an unknown name
(`core/…/core/backup/BackupFormat.kt:247-249`), so a Phase-5 archive carrying `TODOIST_SYNC` would be
unreadable by a 1.2 build unless the format were bumped again; declaring all three in format 6 is free
insurance, and it is safe now only because an older build refuses a format-6 archive before reading
its rows (`core/…/core/backup/BackupCodec.kt:150-151`).

Because `asset_event` is altered rather than only added to, the migration is **not** a pure table
addition: `ALTER TABLE ADD COLUMN` ×3 plus two `CREATE INDEX`, all copied from `6.json`, with a
`MigrationTest` proving every pre-existing row is untouched (D4 §15 lines 584-585).

### 3.2 Backup format 5 → 6

`BackupCodec.FORMAT_VERSION` 5 → 6 (`BackupCodec.kt:47`). `BackupData` (`BackupFormat.kt:231-240`)
gains three lists, all defaulting to empty so every format ≤5 archive still decodes unchanged:

```kotlin
val maintenanceGroups: List<MaintenanceGroupDto> = emptyList(),        // members as a child list
val maintenanceSchedules: List<MaintenanceScheduleDto> = emptyList(),  // providers as a child list
val occurrenceClosures: List<OccurrenceClosureDto> = emptyList(),      // §2.9; sparse by construction
```

and `AssetEventDto` gains §3.1's three fields with defaults, the pattern the four existing optional
lists already use (`BackupFormat.kt:236-239`): `scheduleId: String? = null`,
`occurrenceOn: String? = null`, `detailsPending: Boolean = false`.

- `schedule_provider` rows travel **inside** the schedule DTO and `maintenance_group_member` rows
  **inside** the group DTO, as `ProfileFieldDto` / `ProfileConsumableDto` travel inside
  `EventProfileDto` (`BackupFormat.kt:139-170`). Closures do **not** travel inside the schedule: they
  must be their own rows, or closing a round would change the schedule's exported content and every
  later re-import would be `CONTENT_DIFFERS` — the defect D-8 rejected.
- `OccurrenceClosureDto` is `{id, scheduleId, occurrenceOn, closedOn, createdAt}` — five fields, no
  `updatedAt`, mirroring the immutable row.
- `schedule_state` and `schedule_local_delivery` are **not exported**: the first because every one of
  its columns is on D4 §12's derived side (line 539), the second because it is device-local delivery
  state. `schedule_state` is rebuilt after any import.
- Encoder: all three lists sort by `id`, child lists by `sortOrder` then `provider`, matching the
  determinism discipline at `BackupCodec.kt:80-98`; new `counts` keys `maintenanceGroups`,
  `groupMembers`, `maintenanceSchedules`, `scheduleProviders`, `occurrenceClosures`
  (`BackupCodec.kt:108-120`).
- Decoder: the `formatVersion > FORMAT_VERSION` refusal (`BackupCodec.kt:150-151`) and the
  `formatVersion >= 5 ⇒ backupSetId` check (`:155-157`) are unchanged; every new row is validated by
  `toDomain()` in the same eager pass as the shipped tables (`BackupCodec.kt:174-180`), so a corrupt
  schedule or closure is refused before any import begins.
- **Old archives, and direction:** a format ≤5 archive restores with no groups, no schedules and no
  closures, its events decoding with all three new fields at their defaults; restoring it never invents
  a schedule. 1.2 reads formats 1–6; 1.1.x refuses a format-6 archive loudly with `BackupNewerFormat`
  rather than dropping rows.

### 3.3 How each new table joins `MergePlanner`

`MergeTable` (`core/…/core/merge/MergePlan.kt:20`) is both the write order and the conflict sort key
(`MergePlan.kt:15-18`). Three members are inserted in dependency position (D-19):

```
ASSETS, GROUPS, DEFINITIONS, PROFILES, SCHEDULES, CLOSURES, LINKS, TAGS, EVENTS, ATTACHMENTS
```

A group's members reference assets, so `GROUPS` follows `ASSETS`; a schedule references an asset or a
group, a meter definition and a profile, so `SCHEDULES` follows all four; a closure references only a
schedule, so `CLOSURES` follows it; an event references a schedule, so `EVENTS` keeps its place. Rules
per table, in the planner's own five-step shape (`core/…/core/merge/MergePlanner.kt:29-60`):

| Table | Identity | Unique constraints checked | New `CONFLICT` reasons |
|---|---|---|---|
| `GROUPS` | row `id` only. **Never a name** (#55 AC 12) | `UNIQUE(group_id, asset_id, added_at)` against the destination *and* the archive's accepted rows; the at-most-one-open rule; member child-row PKs | `CONTENT_DIFFERS`; `GROUP_MEMBER_WINDOW_TAKEN`; `GROUP_MEMBER_ALREADY_OPEN`; `OWNER_NOT_AVAILABLE`; `CHILD_ROW_ID_TAKEN` |
| `SCHEDULES` | row `id` | `schedule_provider` PK `(schedule_id, provider)` | `CONTENT_DIFFERS`; `OWNER_NOT_AVAILABLE` (target asset/group, meter definition or profile absent); `SCHEDULE_TARGET_INVALID` (both or neither set) |
| `CLOSURES` | row `id`, **plus `(schedule_id, occurrence_on)` independently of the id** (§2.9) | that pair, against destination and archive | `CONTENT_DIFFERS`; `CLOSURE_DIVERGED`; `CLOSURE_DUPLICATED_IN_ARCHIVE`; `OWNER_NOT_AVAILABLE`. `CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW` rides on an `IDENTICAL` |
| `EVENTS` (altered) | row `id`, plus the shipped `(source, source_ref)` | new `UNIQUE(schedule_id, occurrence_on, asset_id)` | existing `EVENT_SOURCE_REF_TAKEN`; new `SCHEDULE_OCCURRENCE_TAKEN` |

`GROUP_MEMBER_WINDOW_TAKEN` and `GROUP_MEMBER_ALREADY_OPEN` are **archive-internal planner guards**,
not codes two phones reach by disagreeing: they fire when the incoming archive's *own* rows collide —
two member rows claiming one `(group, asset, added_at)`, or two claiming an open window for one
`(group, asset)` — exactly as `PROFILE_FIELD_DEFINITION_TAKEN` does, whose holder "in the only live
case … is the incoming profile itself" (`MergePlan.kt:77-79`). The second guards a **use-case rule**
(invariant 80) rather than an index, because a partial unique index is not expressible in Room, so the
planner is the only place it can be checked before a write. Divergent membership *between phones* does
not reach either code: members are child rows of the group aggregate, so a group whose membership
differs surfaces as `CONTENT_DIFFERS` on the group row. Both guards are therefore constructable in a
test from a hand-built archive, which is what B1's "each new conflict code" row needs.

Following `MergePlanner.kt:118-146`, `:151-185` and `:253-300`: an id absent here → `INSERT`; present
with every backup-format field equal → `IDENTICAL`; present and different → `CONFLICT /
CONTENT_DIFFERS`. There is still **no `UPDATE` verdict** (`MergePlan.kt:25-27`), so no row this phone
already had can be modified by a merge, and one conflict anywhere writes nothing
(`MergePlan.kt:136-141`). `MergeWrites` (`MergePlan.kt:142-150`) gains `groups`, `schedules` and
`closures` in write-order position; `MergeSnapshot` (`:163-173`) gains the same three; `MergeReport`
(`:182-196`) gains three tallies.

**The post-merge rebuild closure is total, deliberately.** An imported event touches its own schedule,
every schedule of its asset (through `current_meter`) and every group schedule whose required set
contains that asset; an imported membership row changes required sets; an imported closure terminates
a round; an imported meter reading moves a threshold. Rather than enumerate that closure: **after any
merge apply, `rebuild` runs for every schedule in the database, inside the same transaction.** At this
scale it is cheap and provably complete. `schedule_state` itself never appears in a plan.

### 3.4 What CONFLICT means for a schedule whose due state differs

**The rule:** *a re-import of an unchanged schedule is `IDENTICAL` even after its occurrences
advanced.* An occurrence advancing writes three things: (1) a new `asset_event` row, or a new
`occurrence_closure` row — a new id either way, therefore an `INSERT`, never a conflict; (2)
`schedule_state` and `schedule_local_delivery`, **neither exported**, therefore invisible to the
merge; (3) the postponement, **and only when it was set**, because of §2.1's rule that a termination
which clears nothing writes no column and does not bump `updated_at`. Without that rule the claim
would be false on *every* schedule, since `IDENTICAL` compares `updatedAt` like any other field
(`MergePlanner.kt:62-63`, `docs/api/v1.md:247`).

So the rule holds unconditionally for a schedule that was never postponed — the ordinary case, and
what lets two phones exchange completions and closures safely: phone B imports A's events and
closures, rebuilds, and derives identical state without either schedule row moving.

It **cannot** hold for a schedule whose `postponed_due_on` was set on one phone and cleared on the
other. That is not a modelling failure: the two phones genuinely disagree about the current
occurrence's due date, and `CONTENT_DIFFERS` with no automatic winner is correct under "timestamps
never pick a winner" (`docs/api/v1.md:263`). The snooze cannot produce that conflict at all, because
D-13 keeps it out of the export.

Every new conflict code carries user meaning: `SCHEDULE_OCCURRENCE_TAKEN` (two devices recorded the
same member's completion of the same occurrence under different event ids), `CLOSURE_DIVERGED` (two
devices closed the same round on different dates), `SCHEDULE_TARGET_INVALID` and
`OWNER_NOT_AVAILABLE`; `GROUP_MEMBER_WINDOW_TAKEN` and `GROUP_MEMBER_ALREADY_OPEN` mean a malformed
archive rather than a disagreement (§3.3), and divergent membership between phones is a
`CONTENT_DIFFERS` on the group row. The only meaningless
one would be a `CONTENT_DIFFERS` raised by `updatedAt` alone, which §2.1's rule and D-13 together
eliminate.

### 3.5 The versioning clarification (D-2)

`docs/versioning.md` gains this sentence, and 1.2.0 is classified by it:

> A **forward-only** backup-format bump — where the new app reads every older archive and an older
> app safely refuses a newer one rather than dropping rows — is a **MINOR**. A change that makes the
> app unable to read data it previously could is a **MAJOR**.

The same edit strikes the unused `1.1.1` / code 13 reservation at `docs/versioning.md:33` and adds the
1.2.0 / code 13 row (D-1). Owner: B13.

---

## 4. API and MCP surface

Extends `docs/api/v1.md`; the version stays **1** and the changes are additive and documented (D-19).
Everything below obeys the page's discipline: one request per connection; unknown fields rejected
(`docs/api/v1.md:95`); response rows are the backup format's own DTOs from the same mappers
(`:98-103`); **request shape ⊂ response shape** (`:145-154`); **PATCH, and `POST …` with an `id`, are
full replaces** (`:134-143`); the route table is an explicit `when` over path segments
(`app/…/api/ApiRouter.kt:63-120`) under the 64 KiB body cap (`ApiRouter.kt:7`, `:36-41`); response
envelopes follow `app/…/api/ApiDtos.kt:44-87`.

### 4.1 New `/v1` rows

| method | path | body | success | notes |
|---|---|---|---|---|
| `GET` | `/v1/groups` | — | 200 | `{groups: [MaintenanceGroupDto]}`, archived included, by name |
| `POST` | `/v1/groups` | group command | 201 | `{group}` |
| `GET` | `/v1/groups/{id}` | — | 200 | `{group}` |
| `PATCH` | `/v1/groups/{id}` | group command | 200 | `{group}`; **full replace**; an omitted member **soft-removes** it (below) |
| `POST` | `/v1/groups/{id}/archive` | `{"archived": true｜false}` | 200 | `{group}`; archive is not delete; history is retained |
| `GET` | `/v1/groups/{id}/schedules` | — | 200 | group-targeted only |
| `GET` | `/v1/assets/{id}/groups` | — | 200 | `{groups: [...]}` — the asset → groups direction of #55's navigation minimum |
| `GET` | `/v1/schedules` | — | 200 | `{schedules: [MaintenanceScheduleDto]}` |
| `GET` | `/v1/assets/{id}/schedules` | — | 200 | asset-targeted only; sibling of `…/definitions` (`docs/api/v1.md:120`) |
| `POST` | `/v1/schedules` | schedule command | 201 | `{schedule}` |
| `GET` | `/v1/schedules/{id}` | — | 200 | `{schedule, state, status, computedForOn}` |
| `PATCH` | `/v1/schedules/{id}` | schedule command | 200 | `{schedule}`; **full replace**; a rule change clears `postponedDueOn`, abandons an open partial occurrence (D-9), moves the D-27 pin's floor to the edit date and rebuilds |
| `POST` | `/v1/schedules/{id}/pause` | `{"paused": true｜false}` | 200 | `{schedule}` |
| `POST` | `/v1/schedules/{id}/archive` | `{"archived": true｜false}` | 200 | `{schedule}` |
| `POST` | `/v1/schedules/{id}/complete` | completion command | 201 | `{event, schedule, state}`; the only route that writes a completion event |
| `POST` | `/v1/schedules/{id}/postpone` | `{"postponedDueOn": "YYYY-MM-DD"｜null}` | 200 | `{schedule, state}`; changes no rule, creates no event; `null` clears |
| `POST` | `/v1/schedules/{id}/close-round` | `{"closedOn": "YYYY-MM-DD"}` optional | 201 | `{closure, schedule, state}`; group-targeted only; writes one `occurrence_closure` row and nothing else (§2.9) |
| `GET` | `/v1/schedules/{id}/closures` | — | 200 | `{closures: [OccurrenceClosureDto]}`, oldest first — read-only history |
| `GET` | `/v1/due` | — | 200 | `{items: [...]}` — the dashboard read model, attention-ordered |

**The three commands.** Each satisfies request ⊂ response (`docs/api/v1.md:145-154`): no `id`,
`status`, `createdAt`, `updatedAt`, `addedAt`, `removedAt` or `archivedAt` appears in any of them.

- **Group command:** `{name, description, members: [{id?, assetId, sortOrder}]}`. `name` is required;
  `description` defaults to `""`. A member's `id` identifies an existing membership row to keep; a
  member with no `id` is an add. `addedAt` and `removedAt` are **server-stamped and are in no
  command** (§2.3). A member sent with **no `id` for an asset that already has an open membership
  row** is refused **422 `MEMBER_ALREADY_OPEN`**: it would create a second open window for one
  `(group, asset)`, which invariant 80 forbids and which the schema cannot enforce, because
  `UNIQUE(group_id, asset_id, added_at)` permits it and a partial unique index is not expressible in
  Room (`04-domain-data-model.md:588-589`). To keep that membership, send its `id`; to close and
  reopen it, omit it in one call and add it in the next.
- **Schedule command:** `{title, description, targetAssetId?, targetGroupId?, timeInterval?,
  timeUnit?, timeBasis?, anchorOn?, leadDays, meterDefinitionId?, meterInterval?, anchorMeter?,
  meterLead?, seasonBehavior, seasonReentry?, seasonReentryOffsetDays?, completionMode, profileId?,
  remindersEnabled, providers: [{provider, enabled}]}`. Exactly one of `targetAssetId` /
  `targetGroupId`; at least one of the time and meter sides; `postponedDueOn` is **not** in the
  command — it has its own route.
- **Completion command:** `{occurredOn, occurredTime?, tzId, notes?, values?, consumables?,
  assetId?}`. `assetId` is **required and validated as a required member** for a group-targeted
  schedule and **must be absent or the schedule's own asset** otherwise. Any valid past `occurredOn`
  is accepted (D-25).

Notes that pin the contract:

- **A `PATCH /v1/groups/{id}` that omits a member soft-removes it.** The route stamps `removed_at` on
  that membership row and **never deletes a row** (invariant 8, §2.3). "A member list left out" means
  every membership becomes closed — not that rows disappear — and the group's history, including every
  past occurrence's required set, is unchanged. Re-adding the asset later inserts a new row.
- Both `…/archive` routes return the row (200), not 204. Both precedents exist in the page
  (`docs/api/v1.md:119` returns the asset; `:122` and `:125` return 204); the row-returning form is
  more useful and matches the asset route, so 1.2 uses it consistently.
- **`status` is not stored and is therefore in no command.** `GET /v1/schedules/{id}` returns the row,
  the derived state and the computed status word with the `today` used echoed as `computedForOn`.
  **`ScheduleStateDto` is a derived read projection, deliberately outside `BackupData`; no command
  accepts it and no archive carries it** — stated so the "one schema on purpose" paragraph (`:98-103`)
  is not read as an instruction to add it to `BackupFormat.kt`.
- **`GET /v1/due` item shape:** `{scheduleId, targetKind ("ASSET"｜"GROUP"), assetId?, groupId?, title,
  status, effectiveDueOn?, computedDueMeter?, currentMeter?, lastCompletedOn?,
  lastTerminationEffectiveOn?, lastTerminationKind, completionMode, membersRequired?,
  membersComplete?, rank}` — every field present, defaults encoded (`:105-106`). A group-targeted
  schedule is **one** item with the two member counts and it is counted once (D-15).
- `POST …/complete` answers **409** `SCHEDULE_OCCURRENCE_TAKEN` on a repeat for an occurrence that
  asset already completed, and **409** `OCCURRENCE_CLOSED` for a closed occurrence. A `FORM` schedule
  completed with no `values` gets `detailsPending = 1`.
- `POST …/close-round` takes an optional `closedOn`, defaulting to today and bounded to the
  occurrence's open date through today inclusive; outside that range is **422**
  `CLOSED_ON_OUT_OF_RANGE` (§2.9). It refuses with **409** on an asset-targeted schedule
  (`CLOSE_NOT_SUPPORTED`), an already-closed occurrence (`OCCURRENCE_ALREADY_CLOSED`), a fully
  completed one (`OCCURRENCE_ALREADY_COMPLETE`), and one whose **required set is empty**
  (`OCCURRENCE_NOT_CLOSEABLE` — there is nothing to unstick, and a closure would name a round nobody
  could have done). There is **no** route to delete or amend a closure: it is immutable history.
- `POST /v1/events` (`docs/api/v1.md:127`) **cannot** create a completion: its command has no
  `scheduleId` and no `source`, and 1.2 does not add them. One completion path, per #50's "must not
  invent a second completion path". **No snooze endpoint** either: under D-13 the snooze is not
  canonical data.
- **Nothing destructive is added:** no `DELETE` for a schedule, a group or a closure, and no route
  deleting a completion beyond the shipped `DELETE /v1/events/{id}` (`docs/api/v1.md:129`).
  `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` (`docs/api/v1.md:202-211`) gains the new paths so a
  404/405 is asserted, not assumed.
- Errors reuse the shipped envelope and mapping (`docs/api/v1.md:213-234`). **422** with domain problem
  names for a bad rule — both targets set or neither, a meter rule or `FOLLOW_ASSET` on a group
  target, a member already open, an out-of-range `closedOn`, or a group-targeted schedule on an empty
  group. Those first two are **422 rather than 409** because they are command-shape violations: the
  body is invalid whatever the database holds, and `docs/api/v1.md:232` reserves 409 for "a refusal
  about state". **409** for a refusal about state — a completion of an archived schedule, an
  occurrence taken, closed, complete or not closeable, a round already closed, or a close on an
  asset-targeted schedule. **404** for an absent id. `/v1/status`'s `counts` map
  (`app/…/api/ApiHandlers.kt:104-119`) gains group, schedule and closure keys.

### 4.2 `import_merge` extension

`POST /v1/import-merge/plan` and `…/apply` keep their paths, their `application/zip` type and their
4 MiB ceiling (`ApiRouter.kt:10-14`), and now read a format **≤6** archive. The report
(`docs/api/v1.md:193-200`) gains `groups`, `schedules` and `closures` tallies, and `conflicts` gains
§3.3's new reason codes. `applicable` is still the only field a client must read; a merge still only
ever inserts; one conflict still writes nothing. A client that enumerates exactly seven table keys will
not recognise the three new ones; the page's own prose "Each of the seven tables"
(`docs/api/v1.md:195`) becomes wrong and is on B12's edit list.

### 4.3 MCP tools

`tools/servicetag-mcp/` gains one tool per operation, named as the shipped set is
(`tools/servicetag-mcp/README.md:58-63`), conventions unchanged: an unknown argument is rejected before
the tool body runs (`README.md:65-66`); on an edit the tool reads the row, overlays only supplied
arguments and submits the complete replacement (`README.md:68-84`); an **omitted** argument and an
explicit **`null`** both mean "leave alone"; clearing is explicit and **by name** through
`clear_fields`. Seventeen new tools: `list_groups`, `get_group`, `create_group`, `update_group`,
`archive_group`, `list_asset_groups`, `list_schedules`, `get_schedule`, `create_schedule`,
`update_schedule`, `pause_schedule`, `archive_schedule`, `complete_schedule`, `postpone_schedule`,
`close_round`, `list_closures`, `list_due`.

- `update_group` and `update_schedule` are overlay edits. A group's **member list** is the hard case:
  an overlay that treats an omitted `members` as "leave alone" makes closing every membership
  impossible, so that is `clear_fields=["members"]`, and a supplied list replaces wholesale —
  soft-removing every member it omits, per §4.1.
- **The same hole exists on every nullable argument, and `postpone_schedule` is the sharpest case:**
  under the null-means-unchanged rule (`README.md:77-84`), `postpone_schedule(postponed_due_on=None)`
  cannot clear a postponement, so clearing is `clear_fields=["postponed_due_on"]` even though the wire
  route accepts a literal `null`. B12 audits every nullable argument on all seventeen new tools for
  the same hole and documents each one's clear path.
- `complete_schedule` and `close_round` have **no overlay** — like `update_event` (`README.md:86-90`),
  every argument is explicit, because each is a new fact and nothing about it can be inherited from a
  row. `close_round`'s docstring states plainly that it records that a round ended **without**
  claiming the outstanding members were serviced, and that the row can never be amended or deleted.
- No destructive tool is added (`README.md:111-113` stays true): no delete-group, no delete-schedule,
  no delete-closure, no snooze. `import_merge` keeps its shape and now accepts a format-6 archive.
  `README.md:58`'s "Twenty-one" tool count becomes wrong and is on B12's edit list.

---

## 5. Android platform (#24, #21, #11)

The shipped manifest declares only `NFC` and `INTERNET`, one launcher activity with the
`servicetag://asset|tag` filter, and the NFC dispatch activity
(`app/src/main/AndroidManifest.xml:3-12`, `:29-47`, `:51-62`). 1.2 adds:

**5.1 Permissions.** `POST_NOTIFICATIONS` only, requested **on first schedule creation with a
rationale** — not at launch, where the user has no context (#24). The request lives in the schedule
editor (B14) with B5's plumbing; #24 AC 1 is unassertable without a creation flow. The rationale is
the ratified "ServiceTag needs notification permission to remind you when maintenance is due." The app
declares **neither** `SCHEDULE_EXACT_ALARM` **nor** `USE_EXACT_ALARM`, asserted against the merged
manifest (#24 AC 2): the first is denied by default on API 34+, the second is Play-restricted to alarm
and calendar apps, and "due today" is a date, not an instant (ledger A12).

**5.2 Alarm policy without exact alarms.** One **inexact daily digest alarm** at the user's hour via
`setAndAllowWhileIdle(RTC_WAKEUP)` (or `setWindow` with a 30-minute window), re-armed by its own
receiver after firing (#21, D3 §7.2 line 219). The instant is
`ZonedDateTime.of(T_next, reminderTime, deviceZone).toInstant()`; per D-6, non-existent
spring-forward local times resolve forward per `java.time`, ambiguous fall-back times to the earlier
offset (D5 §11), and on the day of a zone or DST change the digest may fire twice or not at all — the
12 h backstop is what guarantees it eventually fires.

**5.3 WorkManager backstop.** A `PeriodicWorkRequest` every 12 h, flex 4 h, that recomputes every
`schedule_state`, posts anything missed, and **re-arms the alarm if it is absent**. WorkManager
survives reboot and force-stop; alarms do not. WorkManager is **not** a dependency of this repository
today; B6's brief declares it.

**5.4 Receivers.** `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `DATE_CHANGED`, plus the
quick-action receiver. **None is exported**, every one is addressed with an explicit intent, and every
`PendingIntent` is `FLAG_IMMUTABLE` (#24 AC 5).

> **Amended at implementation (2026-09-22, ServiceTag 1.2 / B13): the enumeration above is
> incomplete — the manifest declares six receivers, not five.** The one it omits is **the digest
> alarm's own receiver**, which §5.2 already implies ("re-armed by its own receiver after
> firing"): an `AlarmManager` broadcast has to reach a **manifest-declared** component, because
> the alarm outlives the process that armed it and a runtime-registered receiver cannot be woken.
> So the set is the four clock-and-boot receivers, the digest receiver, and the quick-action
> receiver — **six**, every one non-exported, every one addressed explicitly, with
> `FLAG_IMMUTABLE` throughout. The master plan's structural grep already expects 6.

**5.5 Notification channels.** D-20 = **B**: exactly two are created, `maintenance_due` at DEFAULT
importance and `maintenance_overdue` at HIGH. No `supplies` and no `sync_problems` channel is created
in 1.2 — a channel a user can see in system settings for a feature that does not exist is noise. #24
AC 3 is amended accordingly (§12). A channel the user has muted is detectable and becomes a health
finding.

**5.6 Preferences.** Two values only, owned by B6: the digest hour (default **09:00 local**, editable)
and the global reminders switch that #27's `REMINDERS_GLOBALLY_OFF` reads. The preferences screen and
the zone-change preference stay #18.

**5.7 Digest policy** (D-5, fixed for 1.2; fatigue controls are #26). One summary notification per run
listing DUE and OVERDUE; per-item notifications only for DUE/OVERDUE that are not snoozed; DUE_SOON
announced only on first entry; overdue re-notification every 3 days. `last_notified_at` and
`first_entry_seen` in §2.5's device-local table carry the bookkeeping. Meter-only schedules are
evaluated **when a reading is saved**, with an immediate notification if it crosses DUE, because there
is no date to alarm on (#21 AC 6).

> **"One summary notification per run listing DUE and OVERDUE" is superseded by the ratified
> wording. Amended at implementation (2026-09-22, ServiceTag 1.2 / B13); the sentence above is
> kept for the record.** The owner ratified the digest body with one amendment at the gate (master
> plan §17.1e), and it names **three** classes, not two:
>
> - title: **"\<n\> maintenance items need attention"**
> - body: **"\<n\> overdue, \<n\> due, \<n\> due soon."**, and a **zero-count clause may be
>   omitted** — a run with nothing due soon reads "2 overdue, 1 due."
>
> Because the body names three classes, **the title's count must equal the items the body
> represents, first-entry DUE_SOON included**: two overdue, one due and one first-entry DUE_SOON
> gives the title "4 maintenance items need attention". The rest of the policy is unchanged — a
> DUE_SOON item still earns a place **only on its first entry**, and it still gets no per-item
> notification. See §6.1 for how this reconciles with invariant 45.

**5.8 Quick actions (#11) and what each writes.**

| Action | Mechanism | Writes |
|---|---|---|
| **"Done"**, `QUICK` | broadcast to a non-exported receiver | the completion event + `rebuild` + `reconcile`. Never starts an activity from a receiver (API 31+ trampoline rule, #24 AC 6) |
| **"Done"**, `FORM` | `PendingIntent.getActivity` into the completion form | nothing until the form is saved (#11 AC 2) |
| **"Snooze 1 day"** | broadcast | `snoozed_until_at` in the device-local table only; **no date, no event**. The dashboard still shows OVERDUE, badged "Snoozed until <date>" (#11 AC 3) |
| **"Open"** | `PendingIntent.getActivity` | nothing; `servicetag://schedule/<uuid>` only |

Every action carries a **random per-notification nonce**, persisted in `schedule_local_delivery` and
checked by the receiver, so a forged broadcast cannot complete a schedule (#11 AC 4) and a real action
survives process death (D-21). A group-targeted schedule's notification offers **"Open"** only:
"Done" on a group cannot honestly mean all members, so it opens the checklist (§2.4's group
completion semantics, invariants 28-30; D-8 for the explicit way out of an unfinished round).

> **Amended at implementation (2026-09-22, ServiceTag 1.2 / B13): two corrections to §5.8.**
>
> 1. **The group-notification clause was mis-cited as D-7.** D-7 is the **no-backlog** ruling — a
>    partially complete group occurrence stays the one current, overdue occurrence — and it says
>    nothing about which notification actions appear. What carries the clause is §2.4's group
>    completion model (a round is satisfied member by member; completing one member never writes
>    an event on another, invariants 28-30) together with **D-8**, which makes "Close this round"
>    the explicit and only way to end an unfinished round. The citation is corrected above, and
>    the same correction applies to master plan §12.1 and to B07's brief, which both inherited it.
> 2. **The `"Done"`, `QUICK` row needs the meter carve-out** that master plan §12.1 carries. A
>    `QUICK` schedule that also carries a **meter rule** cannot be completed without its reading
>    (#11, D5 §3), so its "Done" does **not** broadcast: it routes into the canonical completion
>    flow as an **activity** `PendingIntent`, exactly as a `FORM` schedule's does, and the
>    notification never fabricates a reading. Invariant 55 is untouched — the carve-out moves work
>    *out* of the receiver, not into it.

`DeepLinkRoute` (`core/…/core/links/DeepLinkRoute.kt:18-27`) gains `servicetag://schedule/<uuid>`
(D-17) with the same canonical-UUID shape check and the same navigation-only guarantee, plus the
matching `intent-filter` `data` line beside `AndroidManifest.xml:44-45`. No group link is added: no
1.2 surface needs one.

**When notifications are denied** (D-22): nothing in scheduling state, the alarm and backstop
machinery, or the reminder settings is disabled. The dashboard, the Maintenance destination and the
scan sheet keep working; `NOTIFICATIONS_BLOCKED` appears with a repair that opens system settings, and
the dashboard carries the one-line, dismissible "Reminders are off because notifications are blocked."
rather than a repeated prompt.

**Health findings shipped in 1.2** (#27, D3 §7.3): `NOTIFICATIONS_BLOCKED`, `DIGEST_ALARM_MISSING`,
`BACKSTOP_WORK_MISSING`, `APP_RESTRICTED`, `REMINDERS_GLOBALLY_OFF`, `SCHEDULE_NO_PROVIDER`, `NO_DATA`.
Repair is **only** what is unambiguous and idempotent — re-arm an alarm, re-enqueue a worker. **No
conflict is ever auto-repaired** (#27 AC 6); every Todoist finding is Phase 5. The health screen lives
under Maintenance and is also where the platform realities are explained rather than hidden: an OEM
standby bucket of `RESTRICTED` or battery optimisation "restricted" (`APP_RESTRICTED`), and Android 17
not dispatching NFC to an app in the stopped state after a force-stop. `targetSdk 37` plus
`android:permission="android.permission.DISPATCH_NFC_MESSAGE"` on the dispatch activity is Phase 7,
recorded here so it is not forgotten.

### 5.9 S4 — the alarm and backstop measurement (D-23, **informational**)

S4 is an **informational observation, not a gate** (owner ruling, 2026-09-22). B6 is designed and
written **now**, on the default alarm-plus-backstop design of §5.2 and §5.3; the S4 result is recorded
when it arrives, and if it shows a real problem B6 is revisited then. It is not a test of ServiceTag:
it answers one question — on the owner's development phone, does an inexact daily alarm fire, and does
a 12 h periodic worker run? One trial is enough (same ruling — do not block on exorbitantly strict
acceptance tests). The controller runs it and the ledger records when. Nothing in this spec is
implemented for it, and nothing in this spec waits for it.

**Instrument.** A throwaway debug probe that arms one `setAndAllowWhileIdle(RTC_WAKEUP)` and enqueues
one 12 h / 4 h-flex `PeriodicWorkRequest`, each writing `target`, `actual` and `trigger` to a log file.
It is **its own throwaway Gradle module** (`:s4probe`, one `include` line in `settings.gradle.kts`),
created in a worktree that is never merged, declaring its own `androidx.work:work-runtime-ktx` —
**WorkManager is not a dependency of this repository today** (no `androidx.work` in
`gradle/libs.versions.toml`, `app/build.gradle.kts`, `core/build.gradle.kts` or
`settings.gradle.kts`), so no file under `app/` or `core/` changes and deleting the probe is deleting
one directory and one line. Its WorkManager version is **not** B6's dependency decision; B6's brief
declares its own. It posts no notification, so it needs no `POST_NOTIFICATIONS`, no channel and
nothing from B5, and with no reboot trial no `BOOT_COMPLETED` receiver either. It is **not** B6 and
must not be reused as B6: a throwaway instrument, while B6 is written independently of it.

**Procedure.** One overnight baseline trial on the development phone (the legacy handset, not the
production one): arm the alarm for 09:00 local and enqueue the worker, leave the phone unplugged with
the screen off overnight, and in the morning read the log.

**Verdict.** **PASS** unless the alarm does not fire at all before the phone is next unlocked, or
fires more than **4 h** after target. Otherwise **REVISIT**.

**Recording.** A dated `s4-result.md` in this SDD workspace holding the target and actual alarm
instants, the worker run instants observed overnight, the device class and OS version (no serial), the
probe's commit or worktree ref, confirmation that the probe was deleted, and the one-word verdict; the
same table is posted as a comment on issue #24 and the ledger line records the verdict. A **PASS**
confirms the default design and changes nothing. A **REVISIT** is a request to reopen B6 *after* the
fact — the likely remedies are a shorter backstop period, a second alarm re-arm point, or surfacing
the OEM restriction through #27 more prominently — and whichever is taken is recorded as an amendment
to B6 rather than a precondition of it.

---

## 6. Invariants and forbidden behaviour

Each line is written so a test can be named after it. Per the proportionality ruling, an invariant
naming several facts is one test asserting them together.

**Targeting and membership.**
1. A schedule targets exactly one of an Asset or a MaintenanceGroup — never both, never neither.
2. A group-targeted schedule carries no meter rule.
3. A group-targeted schedule carries no `profileId` (D-12).
4. A MaintenanceGroup is never an Asset row and never appears in the Asset parent/child tree.
5. A MaintenanceGroup never holds an NFC identity; no tag ever resolves to a group.
6. `(group_id, asset_id, added_at)` is unique; one asset may be in many groups, and may hold several closed membership windows in one group.
7. A group name, an asset name, a location and a category are never identity, in the domain or in a merge.
8. A membership row is never hard-deleted while any completion or closure references an occurrence its window covered.

**Recurrence and state.**
9. At most one current occurrence exists per schedule.
10. `effectiveDueOn` is null only when the schedule has no time rule. **— amended at implementation 2026-09-22, see §6.1.**
11. For FIXED, every `computedDueOn` lies on the series `anchorOn + k·interval`.
12. For COMPLETION, `computedDueOn == lastTerminationEffectiveOn + interval` whenever a termination exists.
13. A very late termination produces exactly one next occurrence — **never a backlog** (D-7).
14. Advancing a recurrence never rewrites history: no stored event and no stored closure is edited by an advance.
15. `rebuild` is idempotent: `rebuild(rebuild(x)) == rebuild(x)`.
16. `rebuild` is a pure function of (config, events, closures, membership, `T`) — asserted as a property over the function, not with two devices.
17. `rebuild` is the only write path into `schedule_state`.
18. Status is never stored and never persisted in any form.
19. A completion or a closure clears the postponement; a recurrence edit clears it too and abandons an open partial occurrence (D-9).
20. Snooze changes no `*_on` column and creates no event.
21. A postponement moves the current occurrence only; the next occurrence comes from the **rule**, not from the postponed date.
22. `INACTIVE_SEASON` and `PAUSED` never produce a notification and never a due count.
23. Status is monotone in `T` between history changes: it never goes from OVERDUE back to OK without a completion, a closure or an edit. A season boundary changes status without a history change and is not a violation, because `INACTIVE_SEASON` is not `OK`.
24. Deleting the latest completion event moves the due date back, observably, and reopens its round.
25. A never-terminated FIXED schedule's due date is pinned from immutable configuration and never re-floats on Today; only an explicit recurrence edit moves the pin's floor, to the edit date (D-27).
    *Amendment (release gate, 2026-09-22) to invariants 16, 23 and 25:* `rebuild` takes the device zone as a pure input — the zone is part of the input tuple, so the function stays deterministic; invariants 23 and 25 hold at that fixed zone.
26. `seasonReentry` and `seasonReentryOffsetDays` are stored and never read by 1.2's engine (D-4).
27. A group-targeted schedule is `IGNORE` season only; `FOLLOW_ASSET` on a group target is rejected (D-28).

**Completion truthfulness.**
28. A group completion never writes an event on a non-member.
29. A group completion never marks an unselected member complete.
30. A group occurrence is incomplete until every required member is complete, or the round is explicitly closed.
31. "Complete all" records each required member's completion exactly once.
32. A repeat completion of the same `(schedule, occurrence, asset)` creates no second event — enforced by a unique index, not by a check in code.
33. An occurrence's required member set never depends on today's membership list, and no membership operation can change a past occurrence's required set.
34. A completion event always names a real asset; a schedule never owns an event of its own.

**Round closure (§2.9).**
35. "Close this round" writes exactly one `occurrence_closure` row, no column on `maintenance_schedule`, and no `asset_event`.
36. A closed round never claims any member did work: no member's history changes when a round is closed.
37. `occurrence_closure` is immutable: the table has no `updated_at`, and nothing issues an UPDATE or a DELETE against it except the CASCADE from deleting its schedule.
38. `UNIQUE(schedule_id, occurrence_on)`: closing the same round twice leaves exactly one row, and the second attempt is refused.
39. No completion event may be written with an `occurrence_on` that already has a closure row for that schedule; later work is logged as an ordinary event with no `schedule_id`.
40. For one occurrence, a full completion takes precedence over a closure row; a closure for a fully completed round is inert.
41. `rebuild` advances from the latest termination, completion or closure alike, and deleting a later completion restores the earlier closure-derived due date.
42. Closure rows are exported and merged as their own table; a diverged closure is a CONFLICT and is never auto-resolved.
43. There is no route, tool or UI action that deletes or amends a closure.

**Reminders.**
44. The reminder projection — the posted notifications and the armed alarm — is derivable from schedule state alone and can be rebuilt from nothing.
45. `reconcile` run twice with the same subject list has no second effect. **— amended at implementation 2026-09-22, see §6.1.**
46. A subject's `contentHash` suppresses a no-op update.
47. A seasonally inactive or paused schedule arrives as `PARKED(reentryOn)` — never absent, never overdue.
48. `:core` compiles with no Android and no provider dependency; no port type names a provider object.
49. A schedule with two enabled provider rows yields two subject lists with no change to the port.
50. No health repair ever resolves a conflict.
51. Every health finding has a positive test and a negative control (`issue-27.md:53`).

**Platform and input safety.**
52. The merged manifest declares neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM`.
53. Exactly two notification channels are created, and no `supplies` or `sync_problems` channel exists (D-20).
54. No boot, time, or quick-action receiver is exported, and every `PendingIntent` is `FLAG_IMMUTABLE`.
55. A notification action never starts an activity from a receiver on API 31+.
56. A quick-action broadcast with a stale, missing or already-used nonce is rejected and writes nothing.
57. A tag scan, an NFC dispatch, and a `servicetag://` link never complete, snooze, postpone, close or otherwise mutate; mutation follows an explicit in-app action.
58. An unknown, unbound, lost, retired, malformed or foreign tag never reaches the completion path.
59. Editing a tag's placement label rewrites no NFC payload and changes no binding.
60. The digest alarm is re-armed after `BOOT_COMPLETED`, `TIME_SET` and `TIMEZONE_CHANGED`.
61. Denied notification permission leaves every scheduling, alarm, backstop and settings path working, and produces exactly one health finding (D-22).

**Data contract.**
62. A format ≤5 archive still decodes and restores, with no groups, no schedules, no closures, and every new event field at its default.
63. A format-6 archive is refused by an older build with `BackupNewerFormat`, never partially read.
64. Neither `schedule_state` nor `schedule_local_delivery` is ever exported or merged.
65. No notification-delivery state — snooze, last-notified instant, first-entry flag, nonce — is exported or merged.
66. A merge still has no `UPDATE` verdict: no row this phone already had is modified by an import.
67. One conflict anywhere writes nothing at all.
68. A termination that clears no postponement writes no column on the `maintenance_schedule` row and never bumps its `updated_at`.
69. A re-import of an unchanged, un-postponed schedule is `IDENTICAL` after its occurrences advanced.
70. For FIXED, `rebuild` takes `prevDue` from the terminating row's `occurrence_on`; the D5 §5 reconstruction is used only for a completion event that has none, and that fallback is documented as approximate for an early completion.
71. A schedule whose target, meter definition or profile is neither local nor being inserted is `OWNER_NOT_AVAILABLE`, not an orphan; so is a closure whose schedule is absent.
72. Groups and closures are never coalesced by name or by date; identity is a row id, or a closure's `(schedule_id, occurrence_on)`.
73. A group occurrence's open instant is a single, stated, exported value — a pure function of the rows, asserted as a property, not with two devices.
74. A schedule whose current occurrence has an empty required set is not offered as actionable and is not counted as due.
75. A schedule attached to an asset the dashboard hides by default is still reachable at its attention rank.
76. `POST /v1/events` cannot create a completion event, and no endpoint or tool deletes a schedule, a group or a closure.

**Added in revision 4.**
77. An occurrence whose required set is empty is never a termination and can never be closed.
78. `occurrence_closure.closed_on` is never in the future and never earlier than its occurrence's open date. **— amended at implementation 2026-09-22, see §6.1.**
79. `removed_at` is never cleared, and `added_at` is never edited, by any use case, route or tool; a re-added asset gets a new membership row.
80. At most one membership row per `(group, asset)` has `removed_at IS NULL`.

### 6.1 Amendments made at implementation (2026-09-22, ServiceTag 1.2 / B13)

Three invariants above are **superseded by the amended forms below**, recorded rather than
rewritten so the original wording and the reason for the change both stay readable. The amended
form is what the code enforces and what the tests assert.

**Invariant 10 — amended.** As written: "`effectiveDueOn` is null only when the schedule has no
time rule." As amended:

> `effectiveDueOn` is null only when there is **no time rule** **or** the current occurrence's
> **required set is empty**.

The second clause was missing. A group-targeted round whose required set is empty is not
actionable — it is neither offered for completion nor counted as due, and it can never be closed
(invariants 74, 77) — so `rebuild` writes `effectiveDueOn = null` for it rather than a sort key
for work nobody owes. The original wording would have made that a violation.

**Invariant 45 — amended, reconciled with first-entry DUE SOON.** As written: "`reconcile` run
twice with the same subject list has no second effect." As amended:

> `reconcile` run twice with the same subject list and the **same delivery bookkeeping** has no
> second effect: a subject already showing in exactly this form is not re-posted.

The qualification is needed because §5.7's DUE_SOON policy is deliberately stateful. A first-entry
DUE_SOON item is announced **once**, and announcing it writes `first_entry_seen`; the second run
therefore legitimately produces a *smaller* digest than the first. That is not a second effect on
the delivered projection — which is what the invariant is about — it is the bookkeeping the policy
is built on. The idempotence claim holds **for a given bookkeeping state**. The same reading covers
the overdue three-day re-notification, whose interval is also state, and it is what makes master
plan §17.1e's rule — the digest title counts first-entry DUE_SOON items alongside DUE and OVERDUE
— consistent with this invariant instead of in tension with it.

**Invariant 78 — amended with the clamped floor.** As written: "`occurrence_closure.closed_on` is
never in the future and never earlier than its occurrence's open date." As amended:

> `occurrence_closure.closed_on` is never in the future and never earlier than
> **`min(the occurrence's open date, today)`**. The `CloseRound` default is today, and the
> accepted range is that clamped floor through today, inclusive.

The floor has to be clamped. The occurrence's open **instant** is converted at UTC, to keep
`rebuild` a pure function of its arguments, while `today` is device-local; in a negative UTC offset
the two can differ by a day on the round's opening evening. An unclamped floor would leave the
accepted range **empty** on that evening, refusing the default and every value a caller could offer
instead. The clamp weakens the stored bound in no ordinary case, because the open date is at or
before today in every other one.

---

## 7. Component boundaries and ordering

The spine is #4 → #55 → #28 → #24 → #21 → #11, then #50 #5 #27 on the same state, with #49
independent. #4 and #55 are specified together and implemented as two briefs against one
already-decided schema.

| # | Brief | Consumes | Produces |
|---|---|---|---|
| B1 | **Schema and backup format 6** | §3 | Room v6 entities (incl. `occurrence_closure`, `schedule_local_delivery`) + migration + `MigrationTest`; `6.json`; `BackupData`'s three new lists and `AssetEventDto`'s three fields; `BackupCodec` counts and sorting; `MergeTable`/`MergeWrites`/`MergeSnapshot`/`MergeReport` additions and the new reason codes; the total post-apply rebuild; round-trip and format-≤5 decode proofs. **No engine, no UI.** |
| B2 | **#4 scheduling engine** (`:core`) | B1 | `MaintenanceSchedule`, `ScheduleState`, `ScheduleRecompute.rebuild` (the total `terminations`, `prevDue` from `occurrence_on`, the D-27 pin and its edit-date floor), status, FIXED/COMPLETION/meter/combined arithmetic, the four operations, `ScheduleRepository` port, use cases. D5 §10's worked examples as tests. |
| B3 | **#55 groups, occurrences and closure** (`:core`) | B1, B2 | `MaintenanceGroup`, append-only temporal membership with the at-most-one-open rule, the open instant, required/completed set derivation, the empty-required rule, group completion use cases, "Complete all", occurrence idempotence, and **`CloseRound`** with its closure repository, `closedOn` range check and precedence rule (§2.9). |
| B4 | **#28 `ReminderProvider` port** (`:core`) | B2, B3 | the port, `ReminderSubject`, `RuleFacts`, `SubjectState`, the subject-building use case, a fake provider, idempotence proofs. No Android. |
| B5 | **#24 platform ownership** (`:app`) | — | manifest permissions and receivers, the two channels, the permission-request plumbing (invoked by B14), the no-exact-alarm assertions, detection of restricted and muted states, and a home for the S4 observation when it arrives (§5.9 — informational, gates nothing). |
| B6 | **#21 local provider** (`:app`) | B4, B5 | digest alarm, backstop worker, receivers, the `reconcile` implementation, `schedule_local_delivery`, digest policy, meter-crossing notification, the two preferences, and **its own WorkManager dependency declaration**. Written on the default design of §5.2-§5.3; a REVISIT from S4 amends it afterwards. |
| B7 | **#11 quick actions** (`:app`) | B6 | the four actions, the persisted nonce, trampoline-safe routing, the `servicetag://schedule` link. |
| B8 | **#5 dashboard, and the Maintenance destination shell** (`:app`) | B2, B3 | the due read model over the shipped `DashboardViewModel`, the section placement of §2.6, the component-promotion rule, the group row and its expansion, the health badge, the grayscale acceptance; **the bottom bar becoming Dashboard · Assets · Maintenance** and the Maintenance shell that hosts due work, schedules (incl. PAUSED), groups (B15) and reminder health (B10). |
| B9 | **#50 scan completion sheet** (`:app`) | B2, B3, B8, B14 | the sheet titled "Maintenance" with D-18a's state set and D5 §7A's per-item detail, Review/Snooze/Postpone, the backdatable completion, the sequential form flow, the group-member offer, the navigation-only proofs. |
| B10 | **#27 health and repair** (`:app`) | B5, B6, B8 | `ReminderHealthCheck`, the seven local findings with positive and negative controls, idempotent repair, the Health section inside Maintenance, and the **draft health-finding sentences for ratification** (§12). |
| B11 | **#49 placement labels** (`:app`) | — (independent; **no schema change**) | binding and edit affordance, all-tags list on asset detail, label in the scan result, backup/merge preservation proof. |
| B12 | **API and MCP surface** | B1–B3, B8 | §4's `/v1` rows incl. `close-round` and `closures`, the three command shapes, the DTOs, the router additions, the destructive-route assertions, the seventeen MCP tools and their `clear_fields` audit, `/v1/status` counts, and the edits to `docs/api/v1.md` (incl. the "seven tables" prose) and `tools/servicetag-mcp/README.md` (incl. the tool count). |
| B13 | **Version, docs, release proofs** | all | `versionName` 1.2.0 / `versionCode` 13, the `docs/versioning.md` edits of §3.5, the D5 §5 **and** §10.5 corrections, design-doc updates, the release-level proof commands. |
| B14 | **Schedule editor and the five operations** (`:app`) | B2, B3, B5 | create and edit a schedule against one asset or one group (basis, interval/unit, anchor, lead, meter rule and `anchor_meter`, season behaviour, completion mode, single-choice provider row, reminders toggle) with the ratified wording; the in-app complete / snooze / postpone / **close this round** / edit-recurrence actions, the close confirmation string, and the `closedOn` range; the D-11 duplicate-operation warning; the `POST_NOTIFICATIONS` request with rationale on first creation; the backdatable "When was this done?" completion. |
| B15 | **Groups and asset integration** (`:app`) | B3, B8's shell | group list and detail inside Maintenance with members and current-occurrence progress, add member, remove member (soft), re-add, archive; asset detail's schedules section and its "groups this asset belongs to" section — both directions of #55's navigation minimum. |

B11 can run in either lane at any time; B5 can run in parallel with B2/B3 (it touches no domain).
**B1 gates B2 and B3**; **B8's shell gates B15 and B10's placement**; and **B14 gates B9**. Nothing
gates on S4: it is informational (D-23 as amended), and no brief waits for it.

---

## 8. Test matrix outline (hazard classes, not tests)

One test per hazard class, not per permutation (owner ruling, 2026-09-22); a row naming several facts
is one test asserting them together unless the row says otherwise. And a general rule, binding on
every brief: **no acceptance procedure may wait on an overnight or multi-day measurement.** Where
real-world timing matters — the digest alarm, the 12 h backstop — the brief's acceptance is a
deterministic test against a shadow or injected clock, and the real-device observation (§5.9) is
informational and recorded separately.

| Brief | Hazard classes needing tests |
|---|---|
| B1 | every new field survives encode→decode byte-identically; an event's three new fields and a closure row round-trip and default on a format-≤5 archive; a format-6 archive is refused by the version gate; new `counts` keys present and correct; the migration adds columns without touching an existing row; each new unique index rejects a duplicate and permits NULLs; merge: insert, identical, each new conflict code, `CLOSURE_HELD_BY_AN_EQUIVALENT_LOCAL_ROW` on an IDENTICAL, `CLOSURE_DIVERGED`, owner-not-available, child-row-id collision, deterministic report order, no-partial-write; a termination that clears nothing leaves the schedule row byte-identical so a re-import is IDENTICAL |
| B2 | D5 §10's worked examples, including §10.1's early completion, which fails unless `prevDue` comes from `occurrence_on`; the NULL-`occurrence_on` fallback and its documented inaccuracy; FIXED early, late and very-late termination; **one representative case per calendar rule of D5 §2.3, not a matrix**; no-backlog; the D-27 pin (a schedule created with a past anchor is due at the pinned series date, stays OVERDUE as `T` advances with no history change, returns to the same pin after the sole completion is deleted, and takes the **edit date** as its floor after a recurrence edit); COMPLETION with and without a termination; combined worst-of status; meter baseline absent → `NO_DATA`; a reading lower than the previous one; `rebuild` idempotence and purity; delete and edit a completion event; postpone then complete; snooze changes nothing; a recurrence edit clears the postponement and abandons an open partial round; pause and resume; archived exclusion; `INACTIVE_SEASON` derivation; `FOLLOW_ASSET` on a group target is rejected |
| B3 | the open instant as a function of the previous occurrence's terminating rows (completions **and** a closure); a backdated completion does not move the membership basis; required-set derivation across a membership add and a removal mid-occurrence; **remove then re-add inserts a second row, clears no `removed_at`, and changes no past occurrence's required set**; at most one open membership per `(group, asset)`; the empty required set is not actionable, not counted, and **not closeable**; a group-targeted schedule on an empty group is refused; completing a non-member is refused; partial completion keeps the occurrence open; "Complete all" twice; two groups containing the same asset; a member asset archived mid-occurrence; a group archived with an open occurrence; occurrence-key stability under a postpone; **closure: closing writes one row and no schedule column and no event; a second close is refused and the first row stands; a completion for a closed occurrence is refused; a closed round with no completions terminates at its `closed_on`; a full completion beats a stray closure; close → advance → delete the later completion restores the closure-derived due date; `closedOn` outside the open-date-to-today range is refused; closure survives a backup round trip and a merge** |
| B4 | one subject per enabled provider; two enabled providers; `contentHash` no-op suppression; `PARKED` for paused and for out-of-season; `WITHDRAWN` for archived; `reconcile` twice; a group subject's progress body; `:core` has no Android import (asserted over the module's dependencies) |
| B5 | merged-manifest assertions (permissions present, the two alarm permissions absent, no receiver exported); exactly two channels exist with DEFAULT and HIGH importance and no third channel is created; a muted channel is detected; the denial path; restricted-bucket detection |
| B6 | alarm re-armed after each of the three broadcasts (`ShadowAlarmManager`); the backstop re-arms a deliberately cancelled alarm and posts what was missed; exactly one notification for a schedule due tomorrow; a snoozed schedule produces nothing and its date is unchanged; a parked schedule clears a standing notification; a meter reading crossing DUE notifies immediately; `reconcile` twice posts nothing the second time; overdue re-notification at 3 days and DUE_SOON once on first entry; the provider behaves correctly with an empty `schedule_local_delivery`; a DST spring-forward and a fall-back digest instant |
| B7 | "Done" on QUICK creates exactly one event and clears the notification; "Done" on FORM creates nothing until saved; "Snooze 1 day" changes no date; a stale, missing or already-used nonce writes nothing; a nonce survives process death and still works; no activity is started from a receiver; a group notification offers only "Open" |
| B8 | attention ordering across all seven states with each state in its §2.6 section, `NO_DATA` in ATTENTION and `PAUSED` absent from the dashboard; ties broken deterministically; an OVERDUE schedule on a component asset is visible with a blank query, with its parent named; a group row's expansion and progress, counted once; archived assets absent and their history intact; the health-badge threshold; the bottom bar is Dashboard · Assets · Maintenance and the shell routes to due work, schedules, groups and health; grayscale legibility (D12 §10) |
| B9 | no due work → ordinary asset screen; one due QUICK; two due QUICK selected together; a FORM schedule routed and not silently completed; a meter schedule blocked without its reading; `NO_DATA` appears with "Log meter reading" and DUE_SOON appears only alongside another actionable item, never alone; a backdated completion yields the next due date from the backdated event (D7 exit criterion); Review / Snooze / Postpone each do only their own thing; **one composed assertion that an item shows its meter value, last completion and why-now line**; "Not now" and back write nothing; "Open asset" bypasses; a second tag on the same asset shows the same work with its own placement label; every non-`OpenAsset` resolution cannot reach the sheet; a repeat scan after completion does not re-offer the occurrence; a scanned group member's occurrence completes that member only |
| B10 | each of the seven findings with a positive test **and** a negative control (`issue-27.md:53`); repair twice changes nothing the second time; no conflict is auto-repaired; the badge appears at ≥ WARN; denied permission disables nothing (D-22) |
| B11 | two active tags, two labels, one asset; scanning either reaches the same asset; the label appears in the scan result; editing a label changes no payload and no binding; backup/restore/merge preserve the label; asset detail shows all tags |
| B12 | **one representative case per status class over the new surface, plus each 409 that carries domain meaning by name — `SCHEDULE_OCCURRENCE_TAKEN`, `OCCURRENCE_CLOSED`, and `close-round`'s state refusals — and one parameterised assertion for 404/405/415 reusing `ApiRouterTest`'s existing shape**; the 422 bad-rule refusals `CLOSED_ON_OUT_OF_RANGE`, both-targets-set, a group meter rule or `FOLLOW_ASSET`, and **`MEMBER_ALREADY_OPEN` on a group PATCH sending an id-less member for an asset that already has an open membership row**; unknown-field rejection on each new command; full-replace semantics on both PATCHes, and that a group PATCH omitting a member soft-removes rather than deletes; the request-shape-⊂-response-shape assertion over all three commands; `ScheduleStateDto` absent from `BackupData`; `/v1/due`'s item shape complete with defaults encoded; `POST /v1/events` cannot set a completion; no route deletes a closure; the destructive-route assertion covers the new paths; MCP overlay behaviour, `null`-means-unchanged, every nullable argument's clear path, unknown-argument rejection; `import_merge` with a format-6 archive |
| B13 | version and tag agreement; the release greps; schema and format numbers agreeing across `AppGraph`, `AppDatabase`, `BackupCodec` and `/v1/status` (`app/…/api/ApiHandlers.kt:104-119`); the `docs/versioning.md` clarification present and the 1.1.1 reservation gone; D5 §5 and §10.5 corrected |
| B14 | a schedule can be created against an asset and against a group; both targets set is impossible through the UI; a meter rule or `FOLLOW_ASSET` on a group target is refused; the permission request fires on the first creation and not at launch; each of the five operations does only what §2.1 and §2.9 say; **"Close this round" is offered only on a group-targeted schedule with an open, non-empty, partially complete round; its confirmation says the outstanding members are not being marked done; and its date picker offers only the open-date-to-today range**; the duplicate-operation warning; a backdated completion; an edit that clears a postponement |
| B15 | group list and detail with members and progress; add, soft-remove and re-add a member; archive a group and keep its history; asset → groups navigation and group → member navigation; a group with no members cannot get a schedule |

---

## 9. Ruling register — what binds

Each line states the ruled contract and the qualification the owner attached. The alternatives that
lost are gone from this document; nothing here is a choice.

| D | Ruled | The rule as it binds |
|---|---|---|
| D-1 | A | `versionName` 1.2.0, `versionCode` **13**; the unused 1.1.1 / code 13 reservation is struck from `docs/versioning.md:33`. §1.1, §3.5, B13. |
| D-2 | A | Format 6 is a **MINOR**. `docs/versioning.md` gains the forward-only clarification quoted in §3.5. |
| D-3 | A | **Meter-based schedules are in 1.2**: the meter side of the rule, `computedDueMeter`, `NO_DATA` and the meter-crossing notification all ship. §2.1, §2.2, §5.7. |
| D-4 | A | `seasonBehavior`, `INACTIVE_SEASON` and `PARKED` ship. Season **re-entry** is deferred to #14; `seasonReentry` and `seasonReentryOffsetDays` are stored and **never read** (invariant 26). Consequence: invariant 11 holds unconditionally and the stored sort key is `T`-independent. |
| D-5 | recommended policy | Digest default **09:00 local**, editable; overdue re-notification every 3 days; one summary per run; per-item notifications only for unsnoozed DUE/OVERDUE; DUE_SOON only on first entry. §5.6, §5.7. |
| D-6 | A | Device-local date semantics; ordinary `java.time` DST resolution; re-arm on `TIMEZONE_CHANGED`; the 12 h backstop covers edge-day duplicate or missed delivery. §2.2, §5.2. |
| D-7 | A | **No backlog** for either target type. A partially complete group occurrence stays the one current, overdue occurrence until it is completed or explicitly closed. §2.1, §2.4, invariant 13. |
| D-8 | **B, persistence as §2.9** | "Close this round" ships. Closure is an **immutable, exported `occurrence_closure` fact** keyed by `(schedule_id, occurrence_on)` carrying `closed_on`; no mutable column on `maintenance_schedule`, no generic materialised occurrence table, no event fabricated. `rebuild` treats a closure as a termination with a date through the **total** `terminations` function, so both bases advance from it, a closed round with no completions terminates at its `closed_on`, an empty required set is never a termination, and deleting a later completion restores the closure-derived date. Full design, range rule, merge behaviour and precedence: §2.9; schema §3.1; format §3.2; merge §3.3; invariants 35-43, 77, 78; matrix B1/B3/B12/B14. Group-targeted schedules only in 1.2. |
| D-9 | A | A recurrence edit **abandons** an open partially complete occurrence; recorded member completions remain truthful history; the edited rule creates the new current occurrence and moves the D-27 pin's floor to the edit date. §2.1, §2.4, §4.1, invariant 19. |
| D-10 | A | Member scope is fixed at the occurrence's **open instant**; removing a member does not alter the open round. §2.3, §2.4, invariants 8 and 33. |
| D-11 | A + C's warning | No semantic deduplication; both schedules are allowed; the editor shows the non-blocking "This asset already has a similar schedule through another group." §2.4, B14. |
| D-12 | A | Group-targeted schedules are **QUICK-only**; `profileId` and the meter rule must be null. Invariants 2-3. |
| D-13 | A | The snooze lives in the non-exported, device-local `schedule_local_delivery` (columns in §2.5) with the delivery bookkeeping and the nonce. It is not canonical cross-device data. Invariants 64-65. |
| D-14 | A | Membership rows carry durable ids, and their temporal fields are append-only (§2.3, invariants 79-80). |
| D-15 | A | A group-targeted schedule **counts once** in dashboard and due totals; progress communicates the member workload. §2.6, §4.1. |
| D-16 | A | Archiving or retiring a member leaves membership history untouched; new occurrences exclude it per lifecycle; an open occurrence continues under D-10. §2.3. |
| D-17 | A | `servicetag://schedule/<uuid>` is added now. **No group deep link**: no concrete 1.2 surface requires one, because Maintenance reaches every group in-app. §5.8, §1.2. |
| D-18a | B | The scan sheet shows DUE and OVERDUE; `NO_DATA` when it has a repair action; DUE_SOON **only as a passenger** when the sheet is already open for another actionable item. §2.8. |
| D-18b | A | QUICK completion is `SCHEDULE_QUICK_COMPLETE`; a form completion stays `MANUAL`; `schedule_id` carries the relationship. §2.4 item 3. |
| D-19 | A | `GROUPS`, `SCHEDULES` and `CLOSURES` sit in dependency order in `MergeTable`; the API stays **v1**; the additive changes are documented. §3.3, §4.2, B12. |
| D-20 | **B** | Only `maintenance_due` and `maintenance_overdue` are created; no dead Supplies or Sync Problems channel. **#24 AC 3 is amended** (§12). §5.5, invariant 53. |
| D-21 | B | Each notification nonce is persisted in `schedule_local_delivery`, cleared on successful use and on replacement or reconcile; actions survive process death. §2.5, §5.8, invariant 56. |
| D-22 | A | Denied notification permission disables **nothing** in scheduling state, the alarm and backstop machinery, or reminder settings; the health finding and the dashboard line carry it. §5.8, invariant 61. |
| D-23 | A, **amended 2026-09-22: informational** | S4 is an **informational observation, not a gate**. B6 is designed and written now on the default alarm-plus-backstop design; S4 is one overnight trial with a single 4 h threshold (§5.9) and its result is recorded when it arrives. A REVISIT reopens B6 after the fact as an amendment; it blocks nothing. |
| D-24 | ratified, one change | The seven status terms **OK / DUE SOON / DUE / OVERDUE / OUT OF SEASON / PAUSED / NO BASELINE** stand (`12-visual-design-apollo-service-binder.md:278-283` for six; `:311-313` for `NO BASELINE`, which D12 assigns the season-inactive treatment — distinct from `NO TARGET SET` at `:287`). Every proposed 1.2 string is **ratified**, with the scan-sheet title **"Maintenance"**. The full ratified list is §9.1. Health-finding sentences, and the "Close this round" confirmation, remain to be ratified (§12). |
| D-25 | A | API completion accepts any valid past `occurredOn`; the same principle bounds `closedOn` (§2.9). §4.1. |
| D-26 | B | No `group.location` in 1.2; `description` carries context. §2.3. |
| D-27 | A | A never-terminated FIXED occurrence is **pinned from immutable configuration**: the smallest series date `>= max(anchorOn, createdOn)`, with the **edit date** as the floor after a recurrence edit. It never re-floats on Today, the same pin applies after the sole completion is deleted, and status is monotone. `05-scheduling-semantics.md:346` is superseded. §2.1, §2.2, invariants 23 and 25. |
| D-28 | A | Group-targeted schedules are `IGNORE` season only; `FOLLOW_ASSET` on a group target is rejected exactly as a group meter rule is; no member-season aggregation. §2.1, invariant 27. |
| Navigation | ruled | Primary navigation is **Dashboard · Assets · Maintenance**. Maintenance hosts due work, schedules, maintenance groups and reminder health. The NFC scan stays an interaction mechanism, not a tab. §2.6, B8, B10, B14, B15. |

### 9.1 Ratified strings (D-24)

Final, quoted as written throughout this spec: **"Maintenance"** (scan sheet title and the navigation
destination) · "Maintenance group" · "3 of 5 complete" · "Complete selected" · "Complete all" ·
"Close this round" · "Open asset" · "Not now" · "Review maintenance" · "Snooze" · "Postpone" ·
"When was this done?" · "Tag placement" · "Log meter reading" · "Done" · "Snooze 1 day" · "Open" ·
"Snoozed until <date>" · "Reminders are off because notifications are blocked." ·
"ServiceTag needs notification permission to remind you when maintenance is due." ·
"This asset already has a similar schedule through another group." · editor wording "Every N",
"Repeats from", "the scheduled date", "when I complete it", "Remind me N days early". Status terms:
OK · DUE SOON · DUE · OVERDUE · OUT OF SEASON · PAUSED · NO BASELINE.

**Strings already carried by D12 are pre-ratified with it**, because D12 is approved visual-design
direction recorded as part of the design authority (`12-visual-design-apollo-service-binder.md:3`).
That covers the seven status terms above and the dashboard section labels ATTENTION · UPCOMING ·
CURRENT · OUT OF SEASON (`:706-707`), so "every other string is ratified" is exactly true.

Still unratified, and the only two items in 1.2: §12.

---

## 10. Size estimate for the master plan, and the brief split

Against `docs/superpowers/planning-policy.md:24-31` (under ~2,000 lines is a normal detailed plan;
2,000–3,500 means consider splitting; over ~3,500 almost certainly split) and the shape at line 22 (one
master plan plus one task brief per component, each reviewed on its own):

| Document | Estimate | Owns |
|---|---|---|
| **Master plan** | **900–1,300** | architecture and the contracts between components; the schema, format-6 and closure-fact decisions recorded as settled; the merge additions; §6's cross-cutting invariants; the dependency spine, and that S4 gates nothing (D-23 as amended); release acceptance and the proof commands; the ratified strings |
| B3 groups and closure · B2 engine · B6 local provider | 300–420 each | B3 carries §2.9's closure model and append-only membership; B2 carries D5 §10's worked examples |
| B9 scan sheet · B12 API and MCP · B1 schema · B14 editor · B8 dashboard and shell | 200–380 each | B8 gained the navigation change and the Maintenance shell |
| B10 health · B5 platform · B7 quick actions · B15 groups UI | 150–250 each | |
| B4 reminder port · B11 placement labels · B13 version and docs | 80–180 each | B11 is independent and needs no schema change |
| **Total** | **4,200–5,800 across 16 documents** | no single document over ~1,300 |

The policy grades **per document**; every one of these sits inside line 28's "normal detailed plan"
band, and the total is far from line 31's 8,000. None of it contains production code — fragments stay
under about 30 lines and only where they pin an interface (policy line 16), implementers author the
code and the tests from the matrices, and reviewers review the real diff.

### Before execution — the owner's L2 sequence

1. Apply the rulings to the spec — **done, this revision**.
2. Focused independent re-review, especially D-8 — **done** (`spec-review-rev3.md`); revision 4
   answers it.
3. **S4 (§5.9) runs alongside; it is informational and blocks nothing.**
4. Master plan and briefs **B1–B15**, B6 included, on the default alarm-plus-backstop design.
5. Independent plan review of the master plan and each brief.
6. Resolve blockers and material should-fixes.
7. Reconcile against the ten issues and all invariants.
8. Final planning summary, remaining questions, unratified strings.
9. **"STOP at the pre-implementation authorization gate. No 1.2 production code, no production phone,
   no release."** Stage-B's 19 questions stay deferred until this package exists.

---

## 11. Revision 4 — what changed

**The three blocking findings.**

| Finding | Resolved in |
|---|---|
| **R1** `terminations` was not total: an undefined effective date for a closed round with no completions, and a vacuous `⊇` on an empty required set | §2.9's `terminations` rewritten to the review's wording (empty required set is never a termination; a closed round with no completions terminates at its `closed_on`; the completion branch requires a non-empty completed set); §2.4's completion sentence now says "non-empty and covers" and states that emptiness never means complete; §4.1's fourth `close-round` refusal **409 `OCCURRENCE_NOT_CLOSEABLE`**; **invariant 77**; B3 and B12 matrix rows |
| **R2** two owners for `closed_on`, and an unbounded caller date on a row that can never be amended | §2.9's "Who sets `closed_on`, and its range" — default today, bounded open date ≤ `closedOn` ≤ today, **422 `CLOSED_ON_OUT_OF_RANGE`**, mirroring D-25, with the in-app action offering the same range; §4.1's `close-round` row and refusal list; **invariant 78**; B3, B12 and B14 matrix rows |
| **R3** membership could not represent remove-then-re-add without rewriting the window `required(D)` keys off; `PATCH` read as a delete; no command field list | §2.3 rewritten: `UNIQUE(group_id, asset_id, added_at)`, at most one open row per `(group, asset)` as a use-case rule, re-add inserts a new row, `addedAt`/`removedAt` server-stamped and in no command; §3.1's index; §3.3's two new group conflict codes; §4.1's three command field lists and the "omitted member **soft-removes**" note; §4.3's `clear_fields=["members"]` wording; **invariants 6, 33, 79, 80**; B3, B12 and B15 matrix rows |

**The twelve should-fixes.**

| Finding | Resolved in |
|---|---|
| T1 stale invariant numbers in §2.9 | §2.9 now cites **68 and 69** for the no-schedule-column rule and **39** for the closed-occurrence rule; the whole document was re-grepped for rev1-era numbers |
| T2 `lastTerminationOn` ambiguous | renamed **`lastTerminationEffectiveOn`** in §2.2, §2.9 and §4.1's `/v1/due` shape, with the statement that the occurrence key is not materialised; invariant 12 restated against it |
| T3 D-27 pin after a recurrence edit | §2.1's edit-date floor, §4.1's PATCH note, invariant 25, D-27's register row, B2's matrix row; `05-scheduling-semantics.md:346` named as superseded and added to B13 |
| T4 S4 one-trial form and the WorkManager gap | §5.9 rewritten: one overnight baseline trial, single 4 h threshold, the `:s4probe` throwaway module declaring its own `androidx.work` so nothing under `app/` or `core/` changes, no `BOOT_COMPLETED` receiver, probe ref and deletion recorded; §5.3 and B6 note that WorkManager is B6's own dependency decision |
| **Owner ruling, 2026-09-22: S4 is informational, not a gate** | D-23's register row amended; §5.9's heading and opening state that B6 is designed and written now on the default design and that a REVISIT amends B6 afterwards; every "may not be implemented until `s4-result.md` exists" and every claim that S4 precedes B6 removed from §5.9, §7 (B5's and B6's rows and the gating sentence), §10's sequence, §12's controller task and the document header; **§8 gains the general rule that no acceptance procedure in any brief may wait on an overnight or multi-day measurement**, with deterministic shadow-clock tests in its place |
| T5 proportionality in §8 | §8 header states one test per hazard class; B12's row collapsed to one case per status class plus the named 409s and a parameterised 404/405/415; B2's calendar row to one case per D5 §2.3 rule; B9's three display facts folded into one composed assertion |
| T6 the unratified close confirmation | drafted `PROPOSED` and listed in §12; D-24's register row and §9.1 updated to name two unratified items |
| T7 command field lists | §4.1's "The three commands" block — group, schedule and completion — each satisfying request ⊂ response |
| T8 `PAUSED` and `NO_DATA` in the section order | §2.6 places every state: `NO_DATA` in ATTENTION, `PAUSED` in no dashboard section but under Maintenance → Schedules; B8's matrix row and brief scope |
| T9 B10's `Consumes` | §7 B10 now consumes B5, B6 **and B8** |
| T10 closing twice | §2.9 and invariant 38 now say the second attempt is refused and the first row stands, matching §4.1's 409 |
| T11 the consequence of invariant 39 | §2.9's "A consequence of invariant 39, stated deliberately"; invariant 39 extended |
| T12 citation a line short | `ApiHandlers.kt:104-119` in §4.1 and B13's matrix row |

**The eight notes**, all taken: U1 season-boundary clause in invariant 23; U2 the widened `closed_on`
comment; U3 the membership-churn immunity property in §2.9; U4 invariants 16 and 73 restated as
property tests; U5 §10's nine-step sequence quoting the owner's terminal gate; U6 §5.9 needs no
`BOOT_COMPLETED` receiver; U7 §9.1 states that D12-carried strings are pre-ratified; U8 records that
rev1's S10 is withdrawn — `NO BASELINE` is a D12 word. Everything on the review's "must not be lost"
list is intact: §3.2's reason closures are not nested in the schedule DTO; §2.9's delete-a-completion
case, completion-beats-closure precedence and no-`updated_at` reasoning; §3.3's total post-merge
rebuild; §2.1's conditional write and the `issue-4.md` sentence it corrects; §2.2's `prevDue` rule
with the failing D5 §10.1 example; §2.8's D-18a reconciliation; and §5.9's "the probe is not B6".

---

### Revision 4.1 — five line-edits from `spec-review-rev4.md` §"New breakage"

| Finding | Resolved in |
|---|---|
| **N1** §10 still called S4 a gate the master plan owns | §10's ownership row now reads "the dependency spine, and that S4 gates nothing (D-23 as amended)" |
| **N2** invariant 80 was unenforceable through the API | §4.1's group command refuses an id-less member for an asset with an open membership row with **422 `MEMBER_ALREADY_OPEN`**, saying why the schema cannot catch it and how to keep or reopen a membership instead; B12's matrix row asserts it |
| **N3** the two group conflict codes were unconstructable | §3.3 states they are **archive-internal planner guards** on the incoming archive's own rows, with `PROFILE_FIELD_DEFINITION_TAKEN` as precedent (`MergePlan.kt:77-79`) and the note that the second guards a use-case rule rather than an index; §3.3 and §3.4 both record that divergent membership between phones surfaces as `CONTENT_DIFFERS` on the group row, so B1's "each new conflict code" row is buildable from a hand-made archive |
| **N4** command-shape violations were mapped to 409 | §4.1's error bullet moves both-targets-set-or-neither and a group meter rule or `FOLLOW_ASSET` to **422**, with one sentence on why (`docs/api/v1.md:232` reserves 409 for "a refusal about state"), and splits the bullet into 422 / 409 / 404 |
| **N5** operational state in the spec | "the trial is already armed" is gone from §5.9, D-23's register row and §10's sequence; §5.9 says the ledger records when it ran |

---

## 12. Open items for the owner — two strings

Both are user-visible strings and nothing else; every other string in 1.2 is ratified (§9.1).

1. **The seven health-finding sentences** (`NOTIFICATIONS_BLOCKED`, `DIGEST_ALARM_MISSING`,
   `BACKSTOP_WORK_MISSING`, `APP_RESTRICTED`, `REMINDERS_GLOBALLY_OFF`, `SCHEDULE_NO_PROVIDER`,
   `NO_DATA`). B10 drafts one plain sentence and one repair label each; they return for ratification
   before B10 executes.
2. **The "Close this round" confirmation.** `PROPOSED: "Close this round? The members not marked done
   will not be recorded as serviced."` The action label itself is ratified; this is the confirmation
   body, and it is the sentence that keeps the action from reading as "mark everything done".

Nothing in the D-8 work is left unsettled: §2.9 is a complete, immutable, exported representation that
fits the plan-before-write architecture, so the D-8 A fallback the owner authorised is **not** needed.

**For the controller — three tracker tasks, none blocking this spec.**

1. Comment on **#24** amending AC 3 to the two channels D-20 = B rules (§5.5), and noting that
   `supplies` and `sync_problems` arrive with #15 and #9.
2. Re-tier **#49** from `[NEXT]` to `[MVP]`, now that 1.2 ships it.
3. Post the **S4** result (§5.9) as a comment on #24 and record the verdict in this workspace's ledger
   when the already-armed trial reports. It is informational: a REVISIT amends B6 afterwards.
