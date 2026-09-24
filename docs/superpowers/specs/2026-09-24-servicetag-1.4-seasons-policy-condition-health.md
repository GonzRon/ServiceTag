# ServiceTag 1.4 — seasons, service policy, operational condition and derived health: DESIGN SPEC (rev1)

## 0. Status and rulings needed

**Status: DRAFT, revision 1, 2026-09-24. Not ratified.** One integrated programme: **#14** operating
seasons with calendar *and* manual activation, **#60** maintenance service policy and a maintenance
break, **#61** operational condition and derived health. Built on the archaeology
(`docs/superpowers/specs/2026-09-24-servicetag-1.4-archaeology.md`, "arch." below, at `32c17e2`),
which it does not re-investigate; citations of the form `arch. §B` point at its evidence. Spec 1.2 =
`docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md`. Not a plan and not an
implementation. Every example is fictional; the fixture roles are public (§7, §12.5).

**Working assumptions.** Every one of arch. §I's 29 questions is answered with its recommendation and
marked **ASSUMED (I-n) — awaiting owner ruling** where it is used, so any one can be overruled in rev2
without a rewrite. §14.2 lists all 29 with their sections.

**Rulings needed before a plan can start** (planning policy, "Unchanged"):

1. Confirm or overrule the 29 ASSUMED adoptions (§14.2).
2. Answer the nine open questions (§14.1).
3. Ratify the 126 strings (§10.7: 122 new, 4 shipped words in new roles), with the D12 treatment
   for the new states (§10.6).
4. One independent spec review, at most one consolidated re-review (review budget).

**Decision register.** Column "ASSUMED" names the arch. §I recommendation each decision adopts,
awaiting the owner's ruling (§14.2).

| D | Decision | ASSUMED | § |
|---|---|---|---|
| D-1 | Seven concepts, never collapsed; the interaction matrix is normative | — | 2 |
| D-2 | `season_mode` YEAR_ROUND / CALENDAR / MANUAL | I-1 | 3.1 |
| D-3 | Manual activation is an immutable row, never a journal event | I-2 | 3.3 |
| D-4 | Mode and window change through one season-mode command, leaving the asset command | — | 3.2 |
| D-5 | Switching to MANUAL states the phase; a repeated START/END is 409 | I-3, I-4 | 3.3–3.4 |
| D-6 | One `ServicePolicy` enum replaces `season_behavior` + `season_reentry` | I-5, I-6 | 4.1 |
| D-7 | One signed offset; the lead stays the DUE SOON window | I-10 | 4.2 |
| D-8 | PRE_SERVICE pulls to the run's deadline, only for work opened before it (new guard) | I-13 | 4.3 |
| D-9 | Asset-level break; CONTINUOUS ignores it; defer or pull per policy; already-late stays late | I-7, I-9, I-14 | 4.4 |
| D-10 | New derived status DEFERRED | I-8 | 4.5 |
| D-11 | Group targets stay CONTINUOUS | I-11 | 4.6 |
| D-12 | The engine alone derives the actionable date; providers consume it; stale state rebuilt first | — | 4.7 |
| D-13 | Condition: immutable history, reason + soft event link, no notifications, offer never applied | I-21, I-22, I-28, I-29 | 5 |
| D-14 | Health subjects ASSET / PART / MEDIUM, one driver each; no #47 column yet | — | 6.1 |
| D-15 | 0–100 score, three stored day-thresholds per subject, fixed band cut points | I-16 | 6.3 |
| D-16 | Baselines are canonical events | I-20 | 6.4 |
| D-17 | Aggregation stored, default WORST; critical contributors always surfaced | I-17 | 6.5 |
| D-18 | Health computed at read, never stored or exported | I-25 | 6.7 |
| D-19 | Health clock: H.1c+H.1b medium, H.1a otherwise; H.2a; H.3 per policy, H.3c always | I-12, I-13, I-14 | 7 |
| D-20 | The meter side drives no health in 1.4 | — | 6.2 |
| D-21 | Recreate `maintenance_schedule`; recreate derived `schedule_state` | I-24 | 8.1 |
| D-22 | Merge tables 12–14; identity by row id; one declining second identity | — | 8.4 |
| D-23 | Lockstep app, MCP and loader; `FIELD_RETIRED`, `FIELD_REQUIRED`; no aliasing | I-23 | 9.4 |
| D-24 | Scan routing widened through the one predicate | I-26 | 10.1 |
| D-25 | DOWN first, DEGRADED after due work; AGE health rows | I-27 | 10.2 |
| D-26 | D12 blue-normal palette; fresh words; reminder-health code renamed | I-15, I-18, I-19 | 10.5–10.6 |
| D-27 | 1.4.0 / versionCode 16, MINOR | — | 15.2 |


---

## 1. Goals and non-goals

### 1.1 Goals

- Part-year assets, by fixed dates or by hand, with no backlog while dormant (#14 AC 5, AC 8).
- A per-schedule answer to "when should this be done relative to the season", separate from the
  season, with pre-service and in-service first-class (#60 AC 5, AC 10); a maintenance break (#60 AC 1).
- An explicit, auditable operational condition (#61 AC 1–10).
- Explainable derived health from age or overdue maintenance, its clock following the policy, never a
  raw recurrence date (#61 AC 11–26).

### 1.2 Non-goals

| Out | Why |
|---|---|
| Installed components or assemblies, and any column for them | #47 (§15.1) |
| Stock, supplies, consumable levels feeding health | #15 (§15.1) |
| Measurement- or telemetry-driven health | #61 "must not require telemetry"; #56/#57 later |
| Meter-driven health | D-20; open question Q-2 |
| A one-off work item ("replacement pending" as a task) | ASSUMED (I-21): reason text plus optional event link |
| Notifications for condition or health | ASSUMED (I-28) |
| An UNKNOWN condition value | ASSUMED (I-22) |
| Conflict resolution UI, UPDATE merge verdicts | #44 (§15.1) |
| Todoist `PARKED` behaviour | #9, not built (arch. §J) |
| Season or policy on a maintenance group | ASSUMED (I-11) |

---

## 2. The seven concepts (normative)

arch. §G becomes contract. The word "service" means the maintenance service policy only; the
commissioning date keeps its shipped label "In service date" and is none of the seven (arch. §A.6).

| # | Concept | Question | Canonical data | Derived | Who changes it |
|---|---|---|---|---|---|
| C1 | Asset lifecycle | Is this unit still ours and in use at all? | `asset.status`, `retired_on` | `targetInService` | Asset edits only |
| C2 | Operational condition | Can I rely on it right now? | `asset_condition` rows (§5) | current condition = latest row | An explicit condition write only |
| C3 | Derived health | How deteriorated or at-risk is it, and why? | `health_subject` configuration + events + schedule history | score, band, drivers (§6), at read | Nothing writes it |
| C4 | Maintenance schedule state | What work is due? | schedule row, completions, closures | `schedule_state`, status at read | `rebuild` only |
| C5 | Operating season | When is it normally in use? | `asset.season_mode`, window, `asset_season_activation` rows (§3) | season phase | Season-mode command, START/END |
| C6 | Maintenance service policy | When should this work be done relative to the season? | `maintenance_schedule.service_policy`, offset; `asset` break window (§4) | actionable date, schedule phase | Schedule and asset edits |
| C7 | Reminder health | Is the reminder mechanism working? | none | `ReminderHealthFinding` on demand | Nothing |

**Interaction matrix** (row may affect column; "—" means never, and each "—" is an invariant, §11):

| affects → | C1 | C2 | C3 | C4 | C5 | C6 | C7 |
|---|---|---|---|---|---|---|---|
| C1 lifecycle | · | — | excluded from dashboard only | withdrawn/excluded (shipped) | — | — | findings (shipped) |
| C2 condition | — | · | — | — | — | — | — |
| C3 health | — | — | · | — | — | — | — |
| C4 schedule state | — | — | the MAINTENANCE_OVERDUE input (§6.2) | · | — | — | findings (shipped) |
| C5 season | — | — | the clock (§7) | phase, actionable date | · | — | — |
| C6 policy | — | — | via the actionable date | actionable date, DEFERRED, quiet | — | · | — |
| C7 reminder health | — | — | — | — | — | — | · |

Three rules sit above the matrix (arch. §G "Rules to carry forward", #61 AC 14): **nothing derives
condition**; **health never changes condition**, and condition never pauses a schedule or moves a
health value; **the policy moves only the actionable date — the occurrence key stays `computedDueOn`**.

---

## 3. Season model (#14)

### 3.1 Season mode — ASSUMED (I-1)

`asset.season_mode ∈ {YEAR_ROUND, CALENDAR, MANUAL}`, a new non-null column. The shipped
`season_start_mmdd` / `season_end_mmdd` survive as CALENDAR's boundaries (arch. §B row 1) and are
**non-null if and only if the mode is CALENDAR**; `Season.inSeason` (`core/…/model/Season.kt:27-38`)
stays the calendar predicate, inclusive, wrapping when `start > end`, a non-leap 02-29 read as 02-28
(#14 AC 6). "Both null" no longer means anything by itself: the mode says year-round.

**Season phase** (derived, per asset, at date `d`): YEAR_ROUND → IN_SEASON; CALENDAR →
`inSeason(window, d)`; MANUAL → the action of the latest activation row with `occurred_on ≤ d` (§3.3),
START meaning IN_SEASON from that day inclusive, END meaning OUT_OF_SEASON from that day inclusive.
The **cycle start** at `d` is the first day of the season span containing `d`, or, out of season, the
next CALENDAR start; MANUAL out of season has none (it is not knowable, #14 "no artificial fixed date").

### 3.2 Changing mode and window (D-4)

Mode and window are one configuration with two cross-row effects — the first activation row (§3.4) and
the policies it could strand (§4.3) — so they change through **one season-mode command**
(`POST /v1/assets/{id}/season-mode`, §9.1; the asset editor's Save calls the same use case in the same
`uow.write`). The pair **leaves the asset command**: `seasonStartMmdd` / `seasonEndMmdd` in an asset
`POST`/`PATCH` body are 422 `FIELD_RETIRED` (§9.4). The columns and the DTO fields are unchanged.
Refusals: CALENDAR without a valid window 422 `SEASON_WINDOW_REQUIRED`; a window with any other mode
422 `SEASON_WINDOW_FORBIDDEN`; a change that would leave a PRE_SERVICE schedule without a boundary
409 `SEASON_MODE_STRANDS_POLICY` naming them (#60 AC 5: changing one never silently redefines the
other). Leaving MANUAL keeps its activation rows as history, unread while the mode is not MANUAL.

### 3.3 Manual activation facts — ASSUMED (I-2), ASSUMED (I-4)

```
asset_season_activation          -- immutable fact; the occurrence_closure precedent (spec 1.2 §2.9)
  id           TEXT PK
  asset_id     TEXT NOT NULL      FK asset ON DELETE CASCADE
  action       TEXT NOT NULL      START | END
  occurred_on  TEXT NOT NULL      ISO date, device-local
  event_id     TEXT NULL          soft link, no FK (§5.3); informational
  created_at   INTEGER NOT NULL
  INDEX(asset_id, occurred_on)
```

No `updated_at`, no UPDATE, no DELETE except the asset's CASCADE; rows are ordered by
`(occurred_on, created_at, id)`. A local write is refused when the asset is not MANUAL (409
`SEASON_NOT_MANUAL`), when START follows START or END follows END (409 `SEASON_ALREADY_STARTED` /
`SEASON_ALREADY_ENDED`, as closures refuse a second close), or when `occurred_on` is after today or
before the latest row's date (422 `SEASON_DATE_OUT_OF_RANGE`). A merge may still bring two STARTs in a
row; the phase is the latest row's, the history shows both (§8.4).

**Journal events never activate** (arch. §B row 7): a startup task's completion takes its profile's
kind (`CompleteSchedule.kt:81`) and must not toggle a season. After an event of kind SEASON_START
(SEASON_END) on a MANUAL asset out of (in) season, the app **offers** "Start the season now?" ("End the
season now?"); accepting writes a row dated the event's date, clamped into range, with `event_id` set.

### 3.4 Switching to MANUAL — ASSUMED (I-3)

A switch to MANUAL must state the current phase (`manualPhase: IN_SEASON | OUT_OF_SEASON`, 422
`MANUAL_PHASE_REQUIRED` if absent) and writes, in the same transaction, one activation row (START or
END, dated today). There is no silent default. The engine is still total: a MANUAL asset with no row —
reachable only through an inconsistent import — reads OUT_OF_SEASON, and its asset detail offers
"Start season".

### 3.5 Inactive season, preserved

`INACTIVE_SEASON` and the word **OUT OF SEASON** survive (arch. §B row 6) and mean exactly one thing:
an IN_SERVICE schedule (§4.1) whose asset is OUT_OF_SEASON. The occurrence is kept, `computedDueOn` is
still computed, nothing notifies, nothing accumulates (#14 "Inactive-season semantics", spec 1.2
invariant 13). Re-entry produces exactly one current occurrence (#14 AC 5) because the engine has only
one (spec 1.2 invariant 9).

---

## 4. Service policy and the actionable date (#60)

### 4.1 The policy enum — ASSUMED (I-5), ASSUMED (I-6)

`maintenance_schedule.service_policy`, one enum replacing `season_behavior` and `season_reentry`
(arch. §B rows 2–4):

| Policy | Editor wording (§10.7) | Family (#60) |
|---|---|---|
| `CONTINUOUS` | "Whenever it is due" | continuous / calendar (#60 AC 4) |
| `IN_SERVICE_AT_START` | "When the season starts" + "The season's start" | in-service, re-entry at start + offset (#60 AC 9) |
| `IN_SERVICE_RESUME_CLAMPED` | "When the season starts" + "Its own date, but not before the season starts" | in-service, clamped resume (D5 §10.4) |
| `PRE_SERVICE` | "Before the season starts" | pre-service preventive (#60 AC 1, AC 11) |

**Legacy mapping** — one table, used identically by the 7→8 migration (§8.2) and the format ≤7
decoder (§8.3), and proven equal by one test:

| `season_behavior` | `season_reentry` | → `service_policy` | → `policy_offset_days` |
|---|---|---|---|
| IGNORE | anything | CONTINUOUS | null |
| FOLLOW_ASSET | null, `AT_START`, or anything unrecognised (incl. an `MM-DD`) | IN_SERVICE_AT_START | old offset if ≥ 0, else 0 |
| FOLLOW_ASSET | `RESUME_CLAMPED` | IN_SERVICE_RESUME_CLAMPED | null |

Refusing the unrecognised values would make a readable archive unreadable, a MAJOR
(`docs/versioning.md`); mapping them to the D5 §10.4 default is lossless on real data, where all 43
Stage-B schedules are IGNORE with no re-entry value (arch. §C).

### 4.2 The offset — ASSUMED (I-10)

`policy_offset_days INTEGER NULL`, signed, relative to a boundary: **≥ 0** ("days after it starts")
and required for IN_SERVICE_AT_START; **≤ −1** ("days before it starts") and required for PRE_SERVICE;
**null** for the other two. Anything else is 422 `POLICY_OFFSET_INVALID`. `lead_days` keeps its 1.2
meaning, the DUE SOON window before the actionable date.

### 4.3 Deriving the actionable date

Terms (arch. §H): **raw due** `R` = `computedDueOn`, unchanged by any policy and still the occurrence
key; **opened** `O` = `lastTerminationEffectiveOn`, else the D-27 pin floor's date; **actionable**
`A` = `postponedDueOn ?: policyDue`, which becomes `effectiveDueOn`, the status input and the sort key.
A postponement is the owner's explicit date and beats the policy, exactly as it beats the rule today.

A **restricted day** for PRE_SERVICE is a day in the CALENDAR season or in the asset's break (§4.4); a
**run** is a maximal year-periodic span of consecutive restricted days.

```
policyDue(schedule, R, O, ctx, T): Pair<LocalDate?, Reason>
  when (schedule.servicePolicy) {
    CONTINUOUS -> R to NONE
    PRE_SERVICE -> {
      val run = ctx.runContainingOrNext(R) ?: return R to POLICY_INAPPLICABLE
      val dl = maxOf(run.start + offset, run.previousEnd + 1)     // the deadline; offset <= -1
      when {
        O < dl && dl < R -> dl to (if (run.hasSeasonDay) BEFORE_SEASON else BEFORE_BREAK)
        ctx.inBreak(R)  -> ctx.breakEndFor(R) + 1 to AFTER_BREAK  // opened too late to pull
        else            -> R to NONE
      }
    }
    IN_SERVICE_AT_START, IN_SERVICE_RESUME_CLAMPED -> {
      if (ctx.mode == MANUAL && ctx.phaseAt(T) == OUT_OF_SEASON) return null to AWAITING_START
      val s = ctx.cycleStartAt(T)                                  // null for YEAR_ROUND
      val a = when {
        s == null -> R
        schedule.servicePolicy == IN_SERVICE_AT_START -> if (R < s + offset) s + offset else R
        else -> maxOf(R, s)
      }
      if (ctx.inBreak(a)) ctx.breakEndFor(a) + 1 to AFTER_BREAK
      else a to (if (a != R) SEASON_START else NONE)
    }
  }
```

Four readings pin the shape:

- **AT_START corrects D5 §6's literal text**, which moves `R` "if the computed date is outside the
  season or earlier than that": read literally, a raw due *after* this cycle's end would be dragged
  back to its start and read OVERDUE. The rule is "earlier than `s + offset`"; a later `R` goes dormant
  when the season ends.
- **PRE_SERVICE pull rule (i)** — ASSUMED (I-13): work whose raw due falls in the coming season **or**
  a break is pulled to the run's deadline; a raw due after the season is never pulled (arch. §H.2).
- **The opened-before-deadline guard `O < dl` is new.** Without it a monthly PRE_SERVICE schedule
  completed in season would pull every later in-season occurrence back to the pre-season deadline and
  read OVERDUE all season. With it only work open before the deadline — work "for the upcoming season"
  (#60) — is pulled; later occurrences keep their date, or leave the break. Q-3.
- **Totality.** Only a CALENDAR season or a break makes restricted days; a MANUAL start is never a
  deadline, because it is not knowable in advance (Q-6). A PRE_SERVICE schedule with no run, or whose
  runs cover the year, behaves as CONTINUOUS with reason `POLICY_INAPPLICABLE` and says so (S75).
  Editors and the API refuse to create that state (409 `PRE_SERVICE_NEEDS_DATES`); the same 409 guards
  removing a break or a window that a PRE_SERVICE schedule relies on. Only a merge can reach it.

`rebuild` gains the season context as a pure input — mode, window, break, activation rows — and
materialises `policy_due_on`, `policy_reason`, `phase` (ACTIVE / DORMANT: DORMANT when an IN_SERVICE
schedule's asset is OUT_OF_SEASON at `T`) and `quiet` (§4.4). The occurrence key is still stamped from
`computedDueOn` (`model/Journal.kt:66-72`), so the idempotence index and every closure are untouched.

### 4.4 The maintenance break — ASSUMED (I-7), ASSUMED (I-9), ASSUMED (I-14)

`asset.blackout_start_mmdd` / `blackout_end_mmdd`, both or neither, wrapping like a season, never the
whole year (422 `BLACKOUT_COVERS_THE_YEAR`). The UI calls it the **maintenance break**. It is
asset-level (an indoor UPS has none), independent of the season (#60 AC 5), and applies to
IN_SERVICE_* and PRE_SERVICE only: startup and shutdown tasks are CONTINUOUS and fall inside it by
design (D5 §6).

- **Defer (H.3a)** for IN_SERVICE and for PRE_SERVICE work opened too late to pull: an actionable date
  inside the break moves to the day after it.
- **Pull earlier (H.3b)** for PRE_SERVICE: the break is part of a run (§4.3).
- **Already late (H.3c), always**: an item due before the break stays put, keeps its word (OVERDUE),
  counts as due and keeps degrading health (§7); `quiet` holds its notifications while `T` is in the
  break (#60 "must remain visible").

### 4.5 DEFERRED — ASSUMED (I-8)

A new `DueStatus.DEFERRED`: the actionable date was moved out of a break (`policy_reason =
AFTER_BREAK`, no postponement), `T ≥ R − leadDays`, and `T < A` — where the raw date would have read
DUE SOON, DUE or OVERDUE. It never notifies or counts as due, and has its own quiet dashboard section,
**Deferred**, between CURRENT and OUT OF SEASON, which would be false here: the asset may be in use.

`statusOf` asks, in order: not ACTIVE → PAUSED; `phase == DORMANT` → INACTIVE_SEASON; DEFERRED as above;
no evaluable side → NO_DATA; otherwise the shipped worst-of fold on `A`. The meter side is outside the
policy (D-20): a crossed threshold is DUE when crossed, and only `quiet` holds its notification.

### 4.6 Group targets — ASSUMED (I-11)

A group-targeted schedule is CONTINUOUS only; anything else is 422
`SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET`, the shipped code restated (arch. §B row 8). The one Stage-B
group-targeted pre-season check stays a calendar date. A group has no break.

### 4.7 One derivation, consumed everywhere (#60 AC 7)

The engine is the only place a season boundary, a break or a policy is evaluated.
`BuildReminderSubjects` stops recomputing the next season start from the asset's `MM-DD`
(`BuildReminderSubjects.kt:138-143`, arch. §A.3) and maps engine output onto the unchanged port:
ARCHIVED → `Withdrawn`; PAUSED → `Parked(null)`; DORMANT → `Parked(policyDueOn)` (null for MANUAL);
DEFERRED → `Parked(A)`; `quiet` with a notifying status → `Parked(the day after the break)`; else
`Active`. `RuleFacts.seasonal` becomes `servicePolicy != CONTINUOUS` — false on real data before and
after, so no content hash moves. Because `A` now depends on `T`, spec 1.2 D-4's "`T`-independent sort
key" is retired for a freshness rule: **a status or health value is computed only from a
`schedule_state` row whose `computed_for_on` is today; a read finding an older row, or none, rebuilds
first.** Invariant 23's between-rebuilds exception goes with it.

---

## 5. Operational condition (#61)

### 5.1 The history — ASSUMED (I-22)

```
asset_condition                  -- immutable fact
  id            TEXT PK
  asset_id      TEXT NOT NULL    FK asset ON DELETE CASCADE
  condition     TEXT NOT NULL    OPERATIONAL | DEGRADED | DOWN
  occurred_on   TEXT NOT NULL    ISO date; default today; never in the future
  occurred_time TEXT NULL        HH:MM
  tz_id         TEXT NOT NULL    audit only, as on events
  reason        TEXT NOT NULL    may be empty, ≤ 500 characters
  event_id      TEXT NULL        soft link, no FK (§5.3)
  created_at    INTEGER NOT NULL
  INDEX(asset_id, occurred_on)
```

Three values and no fourth (#61 "Condition vocabulary"): "pending maintenance" is a reason, not a
condition. There is **no stored UNKNOWN**; an asset with no row reads **Condition not recorded**, so
nobody must attest every asset. **Current** is the latest row by `(occurred_on, occurred_time,
created_at, id)`, a null time sorting first; **since** is the earliest row of the latest run of equal
values, so re-attesting DOWN keeps "DOWN since 20 Sep". No column stores the current condition: one on
`asset` would make every change an asset-row conflict (arch. §D.1). A correction is a new row; a
backdated row (an outage recorded afterwards) sorts into place.

### 5.2 Reason and the UPS case — ASSUMED (I-21)

"Battery failed — replacement pending" is the reason on a DOWN row. No one-off schedule and no fake
recurrence is created (#61 AC 2, Stage-B requirement).

### 5.3 The event link is soft

`event_id` names the event that prompted the transition (an INCIDENT, or the repair that ended it). It
has **no foreign key**, because events are deletable (`docs/api/v1.md:140-141`) and a `SET NULL` would
mutate an immutable row, making every later re-import `CONTENT_DIFFERS`. A dangling link renders as
"The linked record was removed." and is never an owner in a merge (§8.4).

### 5.4 Writes, offers — ASSUMED (I-29)

A condition row is written only by "Save condition" (§10.1), the "Mark operational" confirmation, the
offer below, `POST /v1/assets/{id}/conditions` and MCP `record_condition` — never by a scan, health
value, status, season, event or completion (#61 AC 4, AC 14). After a completion, or an event of kind
MAINTENANCE or REPLACEMENT, on a DOWN or DEGRADED asset, the app offers "Mark operational?"; accepting
writes OPERATIONAL dated `max(event date, current row's date)` with `event_id` set; "Not yet" writes
nothing. A repair is never assumed to have succeeded (#61 "Maintenance relationship").

### 5.5 What condition does not do — ASSUMED (I-28)

It pauses no schedule, suppresses no reminder, moves no health value and produces no notification. A
DOWN asset's overdue schedule is still overdue (#61 AC 7 in reverse); an OPERATIONAL asset may show
OVERDUE maintenance (#61 AC 7).

---

## 6. Derived health (#61)

### 6.1 Subjects — D-14

A **health subject** is a named, health-bearing thing on one asset with **one driver**:

```
health_subject                   -- configuration aggregate
  id, asset_id (FK CASCADE), name (1–60 chars), kind ASSET | PART | MEDIUM,
  driver AGE | MAINTENANCE_OVERDUE,
  schedule_id NULL (FK maintenance_schedule CASCADE)   -- MAINTENANCE_OVERDUE only, required
  baseline_profile_id NULL (FK event_profile SET NULL)  -- AGE only, optional
  nominal_until_days, warning_from_days, critical_from_days  INTEGER NOT NULL   -- t1 < t2 < t3
  weight INTEGER NOT NULL DEFAULT 1 (1–10), sort_order, archived_at NULL, created_at, updated_at
  INDEX(asset_id), INDEX(schedule_id)
```

`ASSET` is the whole asset; `PART` a named logical part with no installed-component identity (the
UPS's "Battery age" before #47); `MEDIUM` a maintained medium such as hot-tub water care, which says
nothing about pump or heater (#61 AC 17). Kind changes only the season clock (§7). **One driver per
subject**: composition happens across subjects at the asset (§6.5), so "72 — Water care 55, Pump 100"
is explained by construction; a multi-driver subject is a later additive child table.

**One schedule drives at most one non-archived subject** (#61 AC 20); a subject never changes asset.
Refusals (§9.3): a MAINTENANCE_OVERDUE subject needs an asset-targeted schedule of the same asset with a
time rule; an AGE subject takes no schedule and its baseline profile must be this asset's with event
kind REPLACEMENT; thresholds must satisfy `0 ≤ t1 < t2 < t3 ≤ 36,500`.

**The #47 seam is not a column.** arch. §D.7 suggested a reserved nullable id; 1.2 declined columns
nothing reads (spec 1.2 §1.2, `cost_minor`), and #47 bumps the format anyway, adding
`health_subject.installed_assembly_id TEXT NULL` then. 1.4 guarantees what keeps that clean: identity
is the row id, never the name, and a PART subject is asset-scoped. Q-8.

### 6.2 Drivers

- **AGE**: `x` = days from the baseline (§6.4) to `T`. Season, policy and pause are ignored: a battery
  ages in storage (arch. §H "Age sources").
- **MAINTENANCE_OVERDUE**: `x` = counted late days of the linked schedule's current occurrence (§7),
  measured from its **actionable** date, never from `computedDueOn` (#61 AC 24).
- **D-20, the meter side:** a meter-only schedule cannot drive a subject (422
  `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`), and a combined schedule's meter side contributes nothing. A
  crossed threshold has no date the policy can move (arch. §H "The meter side has no date") and no real
  schedule has a meter rule (arch. §C). Q-2.

### 6.3 Score and bands — ASSUMED (I-16)

A score `0–100` with **three stored day-thresholds per subject**, `t1 < t2 < t3`, in the unit the
owner thinks in, and **fixed score anchors**, so every score means the same thing and an aggregate has
a band (per-subject score cut points would leave an average with none).

```
score(x; t1, t2, t3) = ceil(v), where v =
  100                                   when x <= t1
  100 - 40·(x - t1)/(t2 - t1)           when t1 < x <= t2
   60 - 35·(x - t2)/(t3 - t2)           when t2 < x <= t3
   25 - 25·(x - t3)/(t3 - t2)           when t3 < x <= t3 + (t3 - t2)
    0                                   beyond
band(s) = CRITICAL if s <= 25, WARNING if s <= 60, NOMINAL otherwise
```

Exact rational arithmetic, then ceiling, so a single subject is WARNING from exactly `t2` and CRITICAL
from exactly `t3`. The function is non-increasing in `x`. The words are **NOMINAL · WARNING · CRITICAL**,
plus **NOT TRACKED** for "no value" — never "healthy" (#61 "Condition vocabulary") and never a 100
standing in for no data. Defaults offered by the editor, to be replaced by the owner's (Q-1):
MAINTENANCE_OVERDUE 14 / 45 / 120 days overdue; MEDIUM water care 2 / 7 / 14; AGE has no default.

### 6.4 Baselines are events — ASSUMED (I-20)

- **AGE**: the latest event (by `EventChronology`) on the subject's asset with kind **REPLACEMENT** and,
  when `baseline_profile_id` is set, logged with that quick action. No such event → NOT TRACKED, "No
  replacement recorded yet". A new REPLACEMENT event resets the value and erases nothing (#61 AC 12);
  deleting it restores the previous value. `in_service_on` is not a baseline (arch. §A.6).
- **MAINTENANCE_OVERDUE**: the linked schedule's terminations, through the engine: a completion
  advances the occurrence, the new actionable date lies ahead, `x` returns to 0 (#61 AC 18). A closure
  claims no work (spec 1.2 invariant 36) and cannot reach a subject, because closures are group-only and
  a group schedule cannot drive one.

There is **no baseline table and no mutable baseline column** (arch. §D.2). **The C.2 hazard:** a
combined "load test and battery replacement" schedule with a REPLACEMENT-kind profile would reset
battery age on a load test. The fixture splits it (an INSPECTION load-test profile; a REPLACEMENT
"Battery replaced" profile named as the baseline), and the Stage-B note recommends the same split on
real data. No code rule guesses intent.

### 6.5 Aggregation — ASSUMED (I-17)

`asset.health_aggregation ∈ {TRACK_ONE, AVERAGE, WEIGHTED, WORST}`, default **WORST**, plus
`asset.health_primary_subject_id` (no FK; required for TRACK_ONE only). Contributors are the
non-archived subjects with a value. WORST = the minimum; TRACK_ONE = the primary's value; AVERAGE =
`floor(mean)`; WEIGHTED = `floor(Σ w·s / Σ w)`. Floors never round an aggregate up into a better band.
With one subject every policy equals it — the "primary when there is one subject" default. No
contributors → NOT TRACKED. A dangling or archived primary (merge-only; archiving the primary is 409
`HEALTH_SUBJECT_IS_PRIMARY`) falls back to WORST with S125.

**Averaging never conceals** (#61 AC 23): every contributor in CRITICAL is listed on its own line before
the aggregate on every surface that shows the aggregate, whatever the policy; a DOWN asset's condition
is always shown beside its health, never nominal health alone. No aggregate is shown without its
contributors when there are two or more.

### 6.6 Explanation (#61 AC 15, AC 19)

Each subject carries a driver line (S92–S98): "Replaced 10 May 2023, 3 years 4 months ago";
"Engine oil service is 96 days overdue"; "Within the 14-day grace period"; "Not tracked while out of
season". The aggregate reads `<score> — <subject> <score>, …` in sort order, e.g. "37 — Engine oil
service 37". The asset-detail section closes with S99: an estimate, not a diagnosis, never a condition.

### 6.7 Stored, exported, recomputed — ASSUMED (I-25)

Health is **computed at read time** by a pure `:core` function of (subjects, the asset's events, linked
schedules' states and season context, `T`, zone), like status (spec 1.2 invariant 18). There is no
`health_state` table: at the owner's scale (tens of assets) the cost is a loop, and nothing can go
stale. Archives carry configuration and inputs, never a value; restore and merge reproduce the value
(#61 AC 16). A later materialisation would be internal and invisible to the contract (`computedForOn`).

### 6.8 Health and the rest

Health notifies nothing — ASSUMED (I-28). A PAUSED or ARCHIVED linked schedule makes its subject NOT
TRACKED. Pause has no dated history, so it cannot freeze a past clock: on resume the subject reads as
if never paused, as the status does (`PauseSchedule.kt:13-15`). A hot tub uses a MANUAL season instead.

---

## 7. The health clock (#61 AC 24–26)

### 7.1 The rule — ASSUMED (I-12, I-13, I-14)

For a MAINTENANCE_OVERDUE subject, with `R` and `O` the linked schedule's current occurrence, a day `d`
is **counted** when (a) the schedule's phase on `d` is ACTIVE, (b) `d > A(d)`, where `A(d)` is §4.3's
actionable date evaluated at `d` with today's configuration and postponement, and (c) for a **MEDIUM**
subject on an IN_SERVICE schedule only, `d` is on or after the latest cycle start at or before `T`.
`x` = counted days up to `T`.

| Case | Consequence | arch. |
|---|---|---|
| MEDIUM, dormant at `T` | NOT TRACKED, not "nominal" | H.1c |
| MEDIUM, after START | lateness restarts; pre-END lateness is history | H.1b |
| ASSET / PART, IN_SERVICE | lateness freezes while dormant and carries across | H.1a |
| PRE_SERVICE | counting starts at the pulled deadline, runs through season and break; season start resets nothing | H.2a, #61 AC 26 |
| IN_SERVICE, due moved out of a break | break days are not counted (`d ≤ A(d)`) | H.3a |
| already late when a break opens | break days are counted | H.3c |
| postponement | moves the clock (it is canonical and explicit) | H cross-cutting |
| snooze | never touches it (device-local) | H cross-cutting |

Measure (b), "days counted only while the programme is active", keeps the function deterministic from
windows and activation rows (arch. §H). The implementation counts by spans; its test oracle is the
literal day-by-day loop above.

### 7.2 Hot tub across END and START (MANUAL, H.1c + H.1b)

Water care: weekly FIXED, anchor Sat 2026-01-03, IN_SERVICE_AT_START offset 0, lead 1 day; subject
"Water care", MEDIUM, 2 / 7 / 14.

| Date | Fact | Schedule | Water care |
|---|---|---|---|
| Sat 11 Apr | completed | next `R` = Sat 18 Apr | 100 NOMINAL |
| Thu 16 Apr | END recorded | DORMANT → OUT OF SEASON, `Parked(null)` | NOT TRACKED |
| 1 Jul | — | still one occurrence, `R` = 18 Apr, no notification | NOT TRACKED |
| Sat 10 Oct | START recorded | `R` 18 Apr < 10 Oct → `A` = 10 Oct → DUE | 100 (`x` = 0) |
| Tue 13 Oct | — (if still open) | OVERDUE | `x` = 3 → **92** NOMINAL |
| Thu 15 Oct | — | OVERDUE | `x` = 5 → **76** NOMINAL |
| Sat 17 Oct | — | OVERDUE, still one occurrence | `x` = 7 → **60** WARNING |
| Sat 24 Oct | — | OVERDUE | `x` = 14 → **25** CRITICAL |
| Tue 13 Oct (alt.) | completed | next `R` = Sat 17 Oct (first series date after max(18 Apr, 13 Oct)) | 100 |

Contrast (H.1a): had the 18 Apr occurrence been open at an END on Wed 22 Apr, a PART subject on the
same schedule would carry 4 counted days (19–22 Apr) and read `x` = 11 on 17 Oct, where Water care
reads 7. #14 AC 8, #60 AC 9, #61 AC 18 and AC 25.

### 7.3 Snowblower pre-season (CALENDAR, PRE_SERVICE, H.2a)

Season 11-15 → 03-31 (wraps); break 12-01 → 02-28; oil change every 2 years FIXED, anchor 2024-12-20,
last done 2024-11-10 (so `O` = 2024-11-10, `R` = 2026-12-20); PRE_SERVICE offset −14; lead 14;
subject "Engine oil service", PART, 14 / 45 / 120.

`R` lies in the run 15 Nov 2026 – 31 Mar 2027 (season with the break inside), deadline `dl` = 1 Nov, `O < dl < R`:
`A` = **1 Nov 2026**, reason BEFORE_SEASON.

| Date | Schedule | Notification | Health |
|---|---|---|---|
| 18 Oct | DUE SOON | first-entry DUE SOON | 100 |
| 1 Nov | DUE | yes | 100 |
| 2–30 Nov | OVERDUE | every 3 days (spec 1.2 D-5) | 15 Nov (`x` 14) 100 — season start excuses nothing; 30 Nov (`x` 29) 81 |
| 1 Dec – 28 Feb | OVERDUE, `quiet` | none (H.3c) | 16 Dec (`x` 45) **60** WARNING; 20 Jan (`x` 80) 44 |
| 1 Mar 2027 | OVERDUE | resumes | (`x` 120) **25** CRITICAL |
| 15 May 2027 | OVERDUE | yes | (`x` 195) 0 |

Had the owner done it on 28 Oct, FIXED advances from max(20 Dec 2026, 28 Oct) to `R` = 20 Dec 2028.
Mower (season 04-15 → 10-31, same break, yearly oil change, offset −14): `R` = 5 Nov 2026 is in no run;
the next run is the break (`dl` = 17 Nov) and `dl > R`, so `A` = 5 Nov — the owner's late-autumn habit is
unchanged. A mower `R` of 12 Jan 2027 opened on 12 Jan 2026 lies in the break run: `A` = **17 Nov
2026**, before the break (#60 AC 2). Another owner choosing IN_SERVICE_AT_START for the same mower sees
OUT OF SEASON until 15 Apr and DUE on 15 Apr (#60 AC 3).

### 7.4 A break (YEAR_ROUND generator, IN_SERVICE, H.3a and H.3c)

YEAR_ROUND; break 12-01 → 02-28; engine service every 6 months FIXED, anchor 2025-12-20,
IN_SERVICE_AT_START offset 0 ("After the maintenance break"); lead 14; subject "Engine oil service",
PART, 14 / 45 / 120. Condition DEGRADED since 30 Aug, "Reduced output under load" — independent of
everything below (#61 "generator" example).

| Date | Occurrence | Schedule | Health |
|---|---|---|---|
| 20 Jun 2026 | `R` = `A` = 20 Jun | DUE | 100 |
| 4 Aug | open | OVERDUE | (`x` 45) **60** WARNING |
| 24 Sep | open | OVERDUE | (`x` 96) 37 WARNING |
| 18 Oct | open | OVERDUE | (`x` 120) **25** CRITICAL |
| 20 Oct | completed | next `R` = 20 Dec, inside the break → `A` = **1 Mar 2027**, AFTER_BREAK | 100 |
| 6 Dec – 28 Feb | open | **DEFERRED**, S78, `Parked(1 Mar)` | 100 — break days not counted (H.3a) |
| 1 Mar 2027 | open | DUE | 100 |
| 15 Apr 2027 | open | OVERDUE | (`x` 45) 60 WARNING |

H.3c branch: had 20 Jun stayed open, on 1 Dec (`x` 164) it reads 11 CRITICAL, stays OVERDUE and `quiet`
through the break, and reaches 0 on 1 Jan 2027 (`x` 195).

---

## 8. Data model, migration, backup format 8, merge

### 8.1 Room schema 7 → 8

| Table | Change |
|---|---|
| `asset` | `ADD COLUMN` ×5: `season_mode TEXT NOT NULL DEFAULT 'YEAR_ROUND'`, `blackout_start_mmdd`, `blackout_end_mmdd`, `health_aggregation TEXT NOT NULL DEFAULT 'WORST'`, `health_primary_subject_id`; then `season_mode = 'CALENDAR'` where both `MM-DD` are set. `updated_at` untouched |
| `maintenance_schedule` | **12-step recreate** — ASSUMED (I-24): drop `season_behavior`, `season_reentry`, `season_reentry_offset_days`; add `service_policy TEXT NOT NULL`, `policy_offset_days INTEGER`; copy through §4.1's mapping; every index from `8.json`; `updated_at` untouched |
| `schedule_state` | recreate (derived, never exported): `season_active` → `phase`, plus `policy_due_on`, `policy_reason`, `quiet`; created empty, rebuilt before first read (§4.7) |
| `asset_season_activation`, `asset_condition`, `health_subject` | `CREATE TABLE` + indices (§3.3, §5.1, §6.1) |

The recreate follows the 2→3 and 3→4 precedent: Room turns `foreign_keys` off for `migrate` and runs
`foreign_key_check` afterwards (`data/room/Migrations.kt:96-97, 165-166`), so dropping the parent of
`schedule_provider`, `occurrence_closure`, `schedule_state`, `schedule_local_delivery` and
`asset_event.schedule_id` cascades and nulls nothing. Unread tombstones were rejected: the columns
carry two contradictory published meanings (arch. §B row 3).

### 8.2 Lossless on real data

Every Stage-B schedule is IGNORE with no re-entry value and no Stage-A asset carries a window (arch.
§C), so the migration writes CONTINUOUS / null on every schedule and YEAR_ROUND on every asset, and
moves no `updated_at`: a format-7 export taken **before** the upgrade merges into the upgraded app as
**IDENTICAL for every row** (§12.4).

### 8.3 Backup format 8

`FORMAT_VERSION` 7 → 8. New lists, empty by default so formats ≤7 decode: `seasonActivations`,
`assetConditions`, `healthSubjects`, each sorted by `id`, each validated by `toDomain()` in the eager
pass, each refused if its `assetId` is absent from the archive's assets (as references are).
`AssetDto` gains `seasonMode`, `blackoutStartMmdd`, `blackoutEndMmdd`, `healthAggregation`,
`healthPrimarySubjectId`; a format ≤7 asset decodes as CALENDAR when both `MM-DD` are set, else
YEAR_ROUND, with no break and WORST. `MaintenanceScheduleDto` gains `servicePolicy`,
`policyOffsetDays`; the three old fields become **decode-only**: accepted and mapped by §4.1's table
when `formatVersion ≤ 7`, `BackupCorrupt` when present in a format-8 archive, never encoded.
`counts` gains three keys. The `external_link` tombstones, `ExternalLinkDto` and `externalLinks` stay
byte-for-byte (arch. §D.6). No derived value — `schedule_state`, status, phase, health — is exported
(spec 1.2 invariant 64). 1.4 reads 1–8; 1.3.x refuses 8 with `BackupNewerFormat` before reading a row.

### 8.4 Merge tables 12–14

Appended after `REFERENCES`, the 1.3 precedent (`merge/MergePlan.kt:28-36`); every row points only at
earlier tables.

| # | Table | Identity | Reasons (**new**) |
|---|---|---|---|
| 12 | `SEASON_ACTIVATIONS` | row id | `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` (asset) |
| 13 | `CONDITIONS` | row id | `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` (asset) |
| 14 | `HEALTH_SUBJECTS` | row id, plus non-archived `schedule_id` | `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` (asset, schedule, profile); **`HEALTH_SUBJECT_SCHEDULE_HELD_BY_A_LOCAL_ROW`** on a `SKIPPED`; **`HEALTH_SUBJECT_SCHEDULE_DUPLICATED_IN_ARCHIVE`** |

- **Facts never conflict with each other.** Tables 12–13 are immutable with no `updated_at`: a
  re-import is `IDENTICAL`, two phones' rows are two inserts, and the latest-row state machine absorbs
  a duplicated START. A second identity is wrong for conditions (two transitions a day are legitimate,
  arch. §D.5). Soft event links are never owners, so a condition naming an event deleted elsewhere
  still applies.
- **Toggling never touches the asset row** (arch. §D.1), so an asset re-imports `IDENTICAL` after any
  number of STARTs, ENDs and conditions. Mode, break and aggregation are configuration: divergence is
  `CONTENT_DIFFERS`, as for any edited row.
- **The one second identity declines**, for 1.3's D-18 reason: two phones configuring a subject for
  one schedule disagree about no history, and with no UPDATE verdict a conflict could only block the
  archive. The local subject stands; the skip shows as a count.
- Still no `UPDATE` verdict; one conflict writes nothing; after apply `rebuild` runs for every schedule
  (spec 1.2 §3.3), absorbing imported activation rows.
- **#44 seam:** tables 12–13 stay append-only, so #44's field-aware merge applies to configuration
  rows only, and its asset-ID remapping must rewrite `asset_id` in all three new tables.

---

## 9. API and MCP

### 9.1 Routes (API version stays 1)

| method | path | body | success |
|---|---|---|---|
| `GET` | `/v1/assets/{id}/season` | — | 200 `{seasonMode, seasonStartMmdd, seasonEndMmdd, phase, nextBoundaryOn, blackoutStartMmdd, blackoutEndMmdd, inBreak, activations, computedForOn}` |
| `POST` | `/v1/assets/{id}/season` | `{action, occurredOn?, eventId?}` | 201 `{activation, season}` |
| `POST` | `/v1/assets/{id}/season-mode` | `{seasonMode, seasonStartMmdd?, seasonEndMmdd?, manualPhase?}` | 200 `{asset, season}` |
| `GET` | `/v1/assets/{id}/conditions` | — | 200 `{conditions (oldest first), current}` |
| `POST` | `/v1/assets/{id}/conditions` | `{condition, occurredOn?, occurredTime?, tzId, reason?, eventId?}` | 201 `{condition, current}` |
| `GET` | `/v1/assets/{id}/health` | — | 200 `{assetId, computedForOn, condition, aggregation, aggregate, subjects, critical}` |
| `GET` | `/v1/assets/{id}/health-subjects` | — | 200 `{subjects}` incl. archived |
| `POST` | `/v1/health-subjects` | subject command | 201 `{subject}` |
| `GET`/`PATCH` | `/v1/health-subjects/{id}` | — / subject command (full replace; `assetId` unknown) | 200 |
| `POST` | `/v1/health-subjects/{id}/archive` | `{archived}` | 200 `{subject}` |
| `GET` | `/v1/attention` | — | 200 `{items}`: asset-level rows (§10.2), ranked |

Condition is **not** an asset-command field: PATCH is a full replace (`docs/api/v1.md:128`) and would
bypass the audit (#61 AC 9, arch. §E). Commands keep request ⊂ response. The asset command gains
`blackoutStartMmdd`, `blackoutEndMmdd`, `healthAggregation`, `healthPrimarySubjectId`; the schedule
command gains `servicePolicy` (default CONTINUOUS on create) and `policyOffsetDays`. `ScheduleStateDto`
gains `phase`, `policyDueOn`, `policyReason`, `quiet`, and keeps `seasonActive` as a derived
compatibility boolean (`phase == ACTIVE`; arch. §B row 5). `/v1/due` items gain `computedDueOn`,
`policyDueOn`, `policyReason`, `quiet`; `status` may be `DEFERRED`. `/v1/status` reports schema and
format 8 and three new count keys; import-merge reads formats 1–8.

### 9.2 What has no endpoint

Added to the page's list: amending or deleting a condition or an activation row (immutable history);
deleting a health subject (archive instead); a health value write (derived). `ApiRouterTest.theDestructiveUseCasesHaveNoRoute`
gains the paths.

### 9.3 Codes (`UPPER_SNAKE`)

**422** (the body cannot describe anything valid): `FIELD_RETIRED`, `FIELD_REQUIRED` (§9.4),
`SEASON_WINDOW_REQUIRED`, `SEASON_WINDOW_FORBIDDEN`, `MANUAL_PHASE_REQUIRED`, `SEASON_DATE_OUT_OF_RANGE`,
`BLACKOUT_COVERS_THE_YEAR`, `POLICY_OFFSET_INVALID`, `SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET` (restated),
`CONDITION_DATE_IN_FUTURE`, `CONDITION_REASON_TOO_LONG`, `FOREIGN_EVENT`, `HEALTH_SUBJECT_NAME_REQUIRED`,
`HEALTH_THRESHOLDS_INVALID`, `HEALTH_DRIVER_MISMATCH` (a schedule on AGE, none on MAINTENANCE_OVERDUE),
`FOREIGN_SCHEDULE`, `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`, `PROFILE_NOT_A_REPLACEMENT`,
`HEALTH_WEIGHT_OUT_OF_RANGE`, `HEALTH_PRIMARY_INVALID`.
**409** (the store refuses the state, `docs/api/v1.md:425`): `SEASON_NOT_MANUAL`,
`SEASON_ALREADY_STARTED`, `SEASON_ALREADY_ENDED`, `SEASON_MODE_STRANDS_POLICY`, `PRE_SERVICE_NEEDS_DATES`
(the asset's stored mode decides, so it is state, not shape), `HEALTH_SCHEDULE_TAKEN`,
`HEALTH_SUBJECT_IS_PRIMARY`. **404** `NO_SUCH_HEALTH_SUBJECT`. The error envelope gains an optional
`field` member, used by the two field codes.

### 9.4 Retirement, lockstep, and the explicit-field hazard — ASSUMED (I-23)

- **No silent aliasing.** `seasonBehavior`, `seasonReentry`, `seasonReentryOffsetDays` in a schedule
  body, or `seasonStartMmdd` / `seasonEndMmdd` in an asset command, is 422 `FIELD_RETIRED`, `field`
  naming it and the message its replacement — checked before the generic unknown-field 400.
- **A full replace never defaults a field its caller may not know.** A `PATCH` omitting a 1.4 field
  (`servicePolicy`, `policyOffsetDays`, the break pair, `healthAggregation`, `healthPrimarySubjectId`)
  is 422 `FIELD_REQUIRED`, decided by the key's presence (an explicit `null` is a value); `POST` still
  defaults them. A 1.3 MCP can no longer silently reset a policy (arch. §E).
- **At the MCP**, `update_schedule` and `update_asset` stop enumerating fields
  (`mcp/server.py:1359-1395`): the overlay copies every command key the row reports and overlays only
  supplied arguments. A golden `docs/api/command-shapes.json` is asserted equal to the app's command
  DTOs (JVM) and to the MCP's overlay keys (pytest): the drift tripwire. Write tools refuse with a
  `ToolError` while `/v1/status.schemaVersion < 8`.
- **Lockstep scope:** the app, `tools/servicetag-mcp` and `tools/servicetag-schedules` release
  together. The loader writes and re-plans `seasonBehavior` (`manifest.py:37`, `plan.py:151-158`,
  `apply.py:104`); it maps a legacy `seasonBehavior: IGNORE` to `servicePolicy: CONTINUOUS`, so the
  loaded Stage-B manifest still re-plans IDENTICAL. `tools/servicetag-bundle` emits format ≤7
  archives, which decode leniently, and is unchanged.

### 9.5 MCP tools

Twelve new tools, shipped conventions (unknown argument rejected, `null` means unchanged, clearing by
`clear_fields`, every error a real `ToolError` carrying the code): `get_season`, `start_season`,
`end_season`, `set_season_mode`, `list_conditions`, `record_condition`, `get_health`,
`list_health_subjects`, `create_health_subject`, `update_health_subject`, `archive_health_subject`,
`list_attention`. `start_season`, `end_season` and `record_condition` record new facts and have no
overlay, as `close_round`. `create_/update_schedule` swap `season_*` for `service_policy`,
`policy_offset_days`; `create_/update_asset` drop `season_start/end_mmdd` and gain the break and
aggregation arguments; `_SCHEDULE_NULLABLE_CLEARABLE` and the "`season_reentry` is `MM-DD`" docstring
follow. The README's "Forty-one" becomes fifty-three.

---

## 10. UI

Wording: §10.7. No UI-driven journey tests (§12).

### 10.1 Scan — ASSUMED (I-26)

The sheet reached from a valid asset tag (#50) leads with **condition, before maintenance** (#61 AC 3):
asset name and #49's placement caption, unchanged; the condition block (word, icon, reason, "since");
"Mark operational" and "Change condition" for DOWN or DEGRADED, else "Change condition"; one health
line only when the aggregate is WARNING or CRITICAL; the ratified "Maintenance" section with D-18a's
items, or "Nothing due"; "Open asset" always.

**One predicate still decides routing and contents.** `scanSheetItems` becomes
`scanSheetContent(items, condition)`, and `ScanSheetOffer.has` means "that content offers a sheet":
any D-18a item **or** a current DOWN or DEGRADED (`MaintenanceSheetViewModel.kt:114-166`, arch. §A.5).
An OPERATIONAL or unrecorded asset with nothing due opens asset detail as today. The scan writes
nothing (spec 1.2 invariant 57). "Change condition" opens the three options with helpers, "What is
wrong? (optional)" for DEGRADED and DOWN, "When did this change?" (today, past allowed) and "Save
condition"; "Mark operational" is one tap plus S17. Cancel writes nothing (#61 AC 3–5).

### 10.2 Dashboard — ASSUMED (I-27)

`/v1/attention`'s rows join `DueItem`'s projection as a second row kind, with no schedule behind them
(arch. §A.4). ATTENTION order: **DOWN assets first** (#61 AC 6) → the shipped schedule rows → DEGRADED
assets → AGE subjects in CRITICAL. UPCOMING gains AGE subjects in WARNING after the DUE SOON rows.
MAINTENANCE_OVERDUE subjects never get a row: their badge rides their schedule's row, which is already
there, so nothing is counted twice. DEFERRED gets its own quiet section (§4.5). Condition is never
inferred from a status (#61). Filter chips beside categories (`DashboardFilters.kt:48-51`): Down,
Degraded, Operational, Not recorded.

### 10.3 Asset detail

The identity plate gains the condition badge, a fourth independent fact beside retired, archived and
out of season (`AssetDetailScreen.kt:613-632`). New sections: **Condition** (current, "Change
condition", then Condition history newest first, reasons and links shown — #61 AC 5); **Health**
(critical lines, aggregate, contributors with driver lines, S99); **Season** for CALENDAR (window,
next boundary) and MANUAL (phase, "Start season"/"End season" with S41–S44 confirmations, Season
history); the break when set.

### 10.4 Editors

**Asset editor.** "Operating season": Year-round / Same dates every year / Started and ended by hand;
dates for the second; S34 for the third on a switch. "Maintenance break" with a toggle and two dates.
"Health subjects" list with "Add health subject" and "Combine health by". The editor never requires a
season or a break (#60 "sensible default").
**Health subject editor.** Name; "What is it?"; "What wears it down?"; the driver's schedule or
optional replacement quick action; the three thresholds with driver-specific labels; weight only under
WEIGHTED.
**Schedule editor.** "When should this maintenance be done?" appears only when the target asset has a
season or a break; otherwise the schedule is CONTINUOUS silently. Options: CALENDAR — Before the season
starts / When the season starts / Whenever it is due; MANUAL — the last two, plus Before the
maintenance break when a break is set; YEAR_ROUND with a break — Before the maintenance break / After
the maintenance break / Whenever it is due. "When the season starts" adds "Start counting from"
(AT_START with "Days after it starts", or RESUME_CLAMPED); "Before…" adds "Days before it starts",
default 14, stored as −14. One helper per option; the D5 §6 anchor warning (S74) for an IN_SERVICE
schedule anchored outside a CALENDAR window. Group targets show no question.

### 10.5 Maintenance tab

Schedule rows show the policy's short why-line (S79–S84). The "Reminders" row stays reminder health and
is not renamed "Health" (arch. §F). ASSUMED (I-19): the code namespace `HealthScreen`, `HealthFinding`,
`Severity` becomes `ReminderHealth*`; no visible text changes.

### 10.6 Accessibility and the D12 treatment — ASSUMED (I-15), ASSUMED (I-18)

Every new state has **position + word + icon + colour, colour only reinforcing** (D12 §5); normal
stays **cool blue** (D12 §14), so #61's green → yellow → red becomes blue → amber → error, the bar count
carrying it in grayscale.

| State | Word | Icon (proposed) | Colour family | Position |
|---|---|---|---|---|
| Condition operational | OPERATIONAL | `task_alt` | normal blue | plate |
| Condition degraded | DEGRADED | `trending_down` | new ochre token, distinct from Due | ATTENTION, after due |
| Condition down | DOWN | `block` | error family | ATTENTION, first |
| Not recorded | Condition not recorded | none | neutral, quiet | plate |
| Health nominal / warning / critical | NOMINAL / WARNING / CRITICAL + score | `signal_cellular_alt` 3 / 2 / 1 bars | blue / due-soon amber / error | detail, badges |
| Not tracked | NOT TRACKED | `signal_cellular_nodata` | neutral | detail |
| Deferred | DEFERRED | `hourglass_top` | season-inactive grey | Deferred section |
| In season | IN SEASON | `event_available` | normal blue | plate, season section |

The words avoid "healthy" and keep "service" out of the season UI (arch. §A.6). D12 §5 gains these
rows with contrast-checked token values in the design brief, before ratification (arch. §J, Q-7).

### 10.7 Strings ledger (126, all **to ratify**)

`<…>` is a substitution. "re" = shipped wording re-ratified for a new role.

| # | Surface | String |
|---|---|---|
| S1 | condition word | OPERATIONAL |
| S2 | condition word | DEGRADED |
| S3 | condition word | DOWN |
| S4 | no row | Condition not recorded |
| S5 | section, sheet title | Condition |
| S6 | action | Change condition |
| S7 | action, confirm | Mark operational |
| S8 | option, chip | Operational |
| S9 | helper | Available for normal use. |
| S10 | option, chip | Degraded |
| S11 | helper | Works, but with a known problem. |
| S12 | option, chip | Down |
| S13 | helper | Not available for its intended use. |
| S14 | field | What is wrong? (optional) |
| S15 | field | When did this change? |
| S16 | button | Save condition |
| S17 | dialog title | Mark operational? |
| S18 | dialog body | The earlier <DOWN/DEGRADED> record stays in the history. |
| S19 | offer body | You logged <event title>. Is <asset> working normally again? |
| S20 | offer dismiss | Not yet |
| S21 | section | Condition history |
| S22 | badge suffix | since <date> |
| S23 | empty reason | No reason given |
| S24 | dangling link | The linked record was removed. |
| S25 | refusal | The date cannot be later than today. |
| S26 | chip | Not recorded |
| S27 | asset editor section | Operating season |
| S28 | mode (re) | Year-round |
| S29 | mode | Same dates every year |
| S30 | mode | Started and ended by hand |
| S31 | field (re) | Season starts |
| S32 | field (re) | Season ends |
| S33 | helper | The season may run across the new year, for example from October to April. |
| S34 | switch question | Is this asset in season right now? |
| S35 | option | In season |
| S36 | option (re) | Out of season |
| S37 | helper | You start and end the season yourself. Nothing is due while it is ended. |
| S38 | phase word | IN SEASON |
| S39 | action | Start season |
| S40 | action | End season |
| S41 | dialog title | Start the season? |
| S42 | dialog body | Maintenance that follows the season is due again from <date>. |
| S43 | dialog title | End the season? |
| S44 | dialog body | Maintenance that follows the season stops until you start it again. Nothing is marked done. |
| S45 | history row | Season started |
| S46 | history row | Season ended |
| S47 | section | Season history |
| S48 | calendar line | Next season starts <date> |
| S49 | calendar line | Season ends <date> |
| S50 | offer title | Start the season now? |
| S51 | offer title | End the season now? |
| S52 | offer body | You logged <event title>. |
| S53 | refusal | Choose a date from <date> to today. |
| S54 | refusal | Some maintenance on this asset is done before the season starts. Change it first: <titles>. |
| S55 | refusal | The season is already running. |
| S56 | refusal | The season has already ended. |
| S57 | asset editor section | Maintenance break |
| S58 | toggle | No routine maintenance between two dates |
| S59 | field | Break starts |
| S60 | field | Break ends |
| S61 | helper | Maintenance set to follow the season waits until the break ends, or is done before it starts. |
| S62 | refusal | The break cannot cover the whole year. |
| S63 | schedule question | When should this maintenance be done? |
| S64 | option | Before the season starts |
| S65 | option | When the season starts |
| S66 | option | Whenever it is due |
| S67 | option | Before the maintenance break |
| S68 | option | After the maintenance break |
| S69 | field | Days before it starts |
| S70 | field | Days after it starts |
| S71 | field | Start counting from |
| S72 | option | The season's start |
| S73 | option | Its own date, but not before the season starts |
| S74 | warning | The first due date is outside this asset's season, so it will wait for the season to start. |
| S75 | warning | This asset has no dates to finish before, so this is done whenever it is due. |
| S76 | helper, Before | Due dates in the season or the break move earlier, so the work is done first. |
| S77 | helper, When | Nothing is due while the season is ended. The work comes back when it starts. |
| S78 | why-line | Held by the maintenance break until <date> |
| S79 | why-line | Moved earlier to finish before the season |
| S80 | why-line | Moved earlier to finish before the break |
| S81 | why-line | Due when the season starts |
| S82 | why-line | Out of season until <date> |
| S83 | why-line | Out of season until you start it |
| S84 | why-line | Reminders wait for the maintenance break to end |
| S85 | helper, Whenever | The season and the maintenance break never change when this is due. |
| S86 | status word | DEFERRED |
| S87 | dashboard section | Deferred |
| S88 | section | Health |
| S89 | band | NOMINAL |
| S90 | band | WARNING |
| S91 | band | CRITICAL |
| S92 | driver line, AGE | Replaced <date>, <age> ago |
| S93 | driver line, AGE | No replacement recorded yet |
| S94 | driver line | <schedule> is <n> days overdue |
| S95 | driver line | <schedule> is up to date |
| S96 | driver line | Within the <n>-day grace period |
| S97 | driver line | Not tracked while out of season |
| S98 | driver line | Not tracked while the schedule is paused |
| S99 | footer | Health is an estimate from dates and records, not a diagnosis. It never changes the condition. |
| S100 | no value | NOT TRACKED |
| S101 | aggregate | <score> — <subject> <score>, <subject> <score> |
| S102 | critical line | Critical: <subject> <score> |
| S103 | dashboard row | <subject> <BAND> |
| S104 | section | Health subjects |
| S105 | action | Add health subject |
| S106 | field | What is it? |
| S107 | option | The whole asset |
| S108 | option | A part |
| S109 | option | Something maintained, like water |
| S110 | field | What wears it down? |
| S111 | option | Age since replacement |
| S112 | option | Overdue maintenance |
| S113 | field | Replacement quick action |
| S114 | option | Any replacement |
| S115 | field | Maintenance schedule |
| S116 | fields, AGE | As new for (days) · Warning after (days) · Critical after (days) |
| S117 | fields, overdue | Grace period (days overdue) · Warning at (days overdue) · Critical at (days overdue) |
| S118 | refusal | Each number must be larger than the one before. |
| S119 | field | Combine health by |
| S120 | options | Worst subject · One subject · Average · Weighted average |
| S121 | field | Weight |
| S122 | field | Which subject? |
| S123 | refusal | That schedule already drives another subject. |
| S124 | actions | Archive subject · Restore subject |
| S125 | fallback | The subject to follow is missing, so the worst subject is shown. |
| S126 | scan, empty | Nothing due |

126 rows: 122 new, and S28, S31, S32, S36 shipped words re-ratified for a new role. S116, S117, S120
and S124 ratify their listed words as one set each. Retired with the owner's assent: "Pause with the
asset's season", "Remind me year round", and "Off means this asset is only in use between two dates
each year." (`ScheduleEditScreen.kt:78-80`, `AssetEditScreen.kt:247-268`).

---

## 11. Invariants

Continuing spec 1.2's numbering (1–80). Each is written so a test can be named after it.

**1.2 invariants retired or amended.** **26 retired** ("`seasonReentry*` never read") with its
structural test (`ScheduleStructuralTest.kt:71-94`), replaced by 84 and 100. **27 restated**: a
group-targeted schedule is CONTINUOUS only. **10 amended**: `effectiveDueOn` is also null for an
IN_SERVICE schedule on a MANUAL asset that is out of season. **16 amended**: `rebuild` is a pure
function of (config, events, closures, membership, `T`, zone, season context). **22 amended**:
INACTIVE_SEASON, PAUSED and DEFERRED never notify, and nothing notifies while `quiet`. **23 amended**:
a season boundary, an activation or a break boundary may change status without a history change;
the between-rebuilds exception is removed (101). **47 amended**: DEFERRED and quiet items arrive as
`PARKED`. **62–64 extended** to formats ≤7, format 8 and health. D5 §6's "nothing is stored at season
end" and "MANUAL_STARTUP deferred" are retired, as is spec 1.2 D-4's "stored sort key is `T`-independent".

**Concepts.**
81. Only an explicit condition write inserts an `asset_condition` row; no health value, status, phase, event, completion or scan does.
82. Health changes nothing: no condition, lifecycle, schedule, policy, season or reminder state is written from a health value.
83. Condition changes nothing but its own history: no schedule is paused, no reminder suppressed and no health value moved by a condition.
84. The policy never changes `computedDueOn`: for the same history, `rebuild` under any policy yields the same `computedDueOn` as under CONTINUOUS.
85. Every completion's `occurrence_on` is `computedDueOn`, never `policyDueOn`, `effectiveDueOn` or a postponement.
86. No policy, phase, break or activation writes an event, a closure or any schedule column, or creates a second current occurrence.

**Season.**
87. `season_start_mmdd`/`season_end_mmdd` are both non-null if and only if `season_mode` is CALENDAR.
88. `asset_season_activation` is immutable: no `updated_at`, no UPDATE, no DELETE except the asset's CASCADE.
89. A MANUAL asset's phase at `d` is the action of the latest row with `occurred_on ≤ d` by `(occurred_on, created_at, id)`; activation rows are read only in MANUAL mode.
90. A local START on a started asset, an END on an ended one, and a date after today or before the latest row are refused and write nothing.
91. Switching to MANUAL writes exactly one activation row, stating the phase, in the same transaction.
92. No journal event of any kind changes a phase; the post-event offer writes only when accepted.
93. Out-of-season time creates no occurrence and no backlog; re-entry leaves exactly one current occurrence.

**Policy.**
94. CONTINUOUS: `policyDueOn == computedDueOn` whatever the season or break.
95. IN_SERVICE_AT_START moves `R` to `s + offset` only when `R < s + offset`; RESUME_CLAMPED yields `max(R, s)`; neither moves a date later than the cycle's end back to its start.
96. PRE_SERVICE pulls only when `O < dl < R`; a raw due after the season and outside any run's margin is never pulled.
97. Season start never completes, resets or hides pre-service work: a missed deadline stays OVERDUE into and through the season.
98. An actionable date inside a respected break moves to the day after it; an item already late when the break opens is not moved, keeps its status and is `quiet` for the break.
99. DEFERRED never notifies and never counts as due.
100. No reminder code evaluates a season window, a break or a policy; subject states come from engine output only.
101. A status or health value is computed only from a `schedule_state` row whose `computed_for_on` is today.
102. A group-targeted schedule is CONTINUOUS only; a PRE_SERVICE schedule is refused on an asset with no run.

**Condition.**
103. `asset_condition` is immutable; current = the latest row by `(occurred_on, occurred_time nulls first, created_at, id)`; no column stores a current condition.
104. No UNKNOWN value is ever stored; an asset with no row reads "Condition not recorded".
105. A condition's `event_id` is never a foreign key and never an owner; a dangling link is tolerated in the app and in a merge.
106. Returning to OPERATIONAL inserts one row and leaves every earlier row byte-identical.

**Health.**
107. No health value is stored or exported; there is no `health_state` table and no health column.
108. A MAINTENANCE_OVERDUE value is a function of the actionable date via §7's counted days, never of `computedDueOn` alone.
109. An AGE baseline is the latest qualifying REPLACEMENT event; adding one resets the value and edits nothing; deleting it restores the previous value.
110. A MEDIUM subject is NOT TRACKED while its programme is dormant and counts only from the latest cycle start; other subjects freeze while dormant and carry.
111. Pre-service lateness counts from the pulled deadline and is not reset by season start; break days count for an already-late item and not for a deferred one.
112. A postponement moves the health clock; a snooze never does.
113. `score` is non-increasing in `x`; WARNING begins exactly at `t2` and CRITICAL exactly at `t3`.
114. An untracked subject is excluded from aggregation and shown as NOT TRACKED, never as 100.
115. Every CRITICAL contributor is shown separately whatever the aggregation; a DOWN asset's health is never shown without its condition.
116. A schedule drives at most one non-archived subject; a subject never changes asset.

**Data and surface contract.**
117. A format ≤7 archive decodes: legacy season fields map by §4.1's table, new lists are empty, modes derive from the window; a format-8 archive carrying a legacy field is corrupt.
118. The 7→8 migration and the legacy decoder agree row for row, touch no `updated_at`, and a pre-upgrade format-7 export of real-shaped data merges IDENTICAL.
119. Activations and conditions merge as INSERT, IDENTICAL or CONTENT_DIFFERS by id only; START, END and condition writes never change the asset row.
120. No route or tool amends or deletes a condition or an activation; condition is in no asset command.
121. A retired field is 422 `FIELD_RETIRED` and never aliased; a PATCH omitting a 1.4 field is 422 `FIELD_REQUIRED` and writes nothing.
122. No 1.4 table, column or route names an installed component or assembly, and nothing reads stock.

---

## 12. Test matrix by layer (hazard classes, not permutations)

Testing hierarchy (planning policy, 2026-09-23): no `uiautomator`/`adb` journeys; no acceptance waits on
a real-world delay — every date is an injected `T`.

### 12.1 JVM (`:core`, `:app` unit, pytest)

| Area | Hazard classes |
|---|---|
| Season | `inSeason` wrap and boundary days incl. 02-29 (#14 AC 6); MANUAL phase from rows incl. same-day START/END and merged double START; START/END refusals and date range; switch-to-manual writes one row |
| Policy | each policy × each mode; AT_START's corrected reading (a later `R` is not dragged back); RESUME_CLAMPED both D5 §10.4 cases; PRE_SERVICE pull, margin clamp against the previous run, the `O < dl` guard with a monthly schedule, `POLICY_INAPPLICABLE` totality; break defer, H.3c no-move, DEFERRED window edges; group CONTINUOUS only; property: `computedDueOn` invariant under policy (84); property: no backlog across dormancy (93) |
| Status and reminders | DEFERRED position in `statusOf`; `quiet` suppresses delivery and not status; subject states from engine output; `Parked(null)` for MANUAL; content hash unchanged on CONTINUOUS; freshness rebuild (101) |
| Condition | ordering and current; "since" across a re-attestation; backdated row; soft link dangling; no UNKNOWN |
| Health | `score` table at `t1`, `t2 − 1`, `t2`, `t3`, tail and zero; monotonicity property; bands; AGE baseline selection with and without a profile; MAINTENANCE_OVERDUE counted days vs the day-loop oracle (property); the three timelines of §7 and the five fixtures of §12.5 as literal expected values; aggregation × 4, floors, untracked exclusion, primary fallback, critical surfacing |
| Backup | round trip byte-identical; format ≤7 decode incl. every legacy mapping row; legacy field in format 8 is corrupt; format 8 refused by the version gate; counts; tombstones untouched |
| Merge | per new table: insert, identical, content-differs, owner-not-available; re-import IDENTICAL after START/END and conditions; dangling event link applies; subject SKIPPED arm with `applicable` true; archive-duplicate conflict; no partial write |
| API | one case per status class per route; every new code by name; `FIELD_RETIRED` before unknown-field 400; `FIELD_REQUIRED` by presence; request ⊂ response; destructive routes absent; `/v1/status`; import of formats 7 and 8 |
| MCP / loader | twelve tools; overlay carries a row field the tool does not name; golden command shapes; schema gate; clear paths; loader maps legacy manifests and re-plans IDENTICAL against a 1.4 row |

### 12.2 Compose instrumented

Scan sheet: DOWN with nothing due shows condition first and offers "Mark operational"; OPERATIONAL with
due work shows the #61 layout; confirm writes one row, cancel writes none; the view model's routing
equals the sheet's contents. Change-condition sheet options, reason field only for DEGRADED/DOWN, date
bound. Asset detail: history newest first, health contributors and critical lines, season section per
mode, Start/End dialogs. Editors: season mode switch asks S34; the policy question hidden without a
season or break, options per mode, offsets, S74 warning; health subject validations. Dashboard: DOWN
first, DEGRADED after due, AGE rows, Deferred section, filter chips. Grayscale: no two new states share
the (word, icon) pair; every state has non-empty text and a content description.

### 12.3 Structural contract

`MigrationTest` 7→8 on a real-shaped fixture (43 IGNORE schedules, windowless assets): every value
mapped, `updated_at` byte-identical, `foreign_key_check` clean, `8.json` committed; schema and format
numbers agree across `AppGraph`, `AppDatabase`, `BackupCodec`, `/v1/status`; anchored grep tripwires —
no `season_behavior`/`seasonReentry` outside the legacy decoder and migration, no `Season.inSeason`
under `reminders/`, no health entity or column, no DELETE/PATCH route on conditions or activations,
nothing touching `external_link`; the golden command-shape file.

### 12.4 Boundary and release

**No new external-boundary test**: 1.4 adds no intent, grant or exported component, so it names no OS
boundary (#62 hard rule); the manifest contract tests stay unchanged. The **one signed-APK upgrade
smoke** on the development phone installs 1.4.0 over 1.3.0 in place and proves: counts equal, every
schedule CONTINUOUS / null, every asset YEAR_ROUND with no break, the three new lists empty, and the
pre-upgrade format-7 export merge-plans IDENTICAL for every row. It also reads the phone's asset rows
arch. §J could not (windows, battery-pack parentage). Private exports stay in the owner's folder.

### 12.5 The five fixtures (fictional test data, arch. §C)

| Fixture | Configuration | Expected at `T` = 2026-09-24 |
|---|---|---|
| F1 master-bedroom UPS | DOWN since 20 Sep, "Battery failed — replacement pending"; "Battery age" PART, AGE 365 / 1095 / 1460, baseline REPLACEMENT 2022-06-01 | `x` 1576 → **18 CRITICAL**; scan routes to the sheet with nothing due; a REPLACEMENT on 27 Sep → 100 and the offer; accepting → OPERATIONAL, DOWN kept |
| F2 battery pack | "Battery age" ASSET, same thresholds, baseline profile "Battery replaced", REPLACEMENT 2023-05-10; a load-test profile of kind INSPECTION | `x` 1233 → **47 WARNING** (WARNING since 9 May 2026, CRITICAL from 9 May 2027); a load test changes nothing |
| F3 generator | §7.4 | 37 WARNING, DEGRADED, schedule OVERDUE |
| F4 snowblower and mower | §7.3 | OK; DUE SOON from 18 Oct |
| F5 hot tub | §7.2 | OUT OF SEASON, Water care NOT TRACKED |

---

## 13. Acceptance-criteria trace

| Issue AC | Section |
|---|---|
| #14-1 winter hot tub reminds in season only | 3.5, 4.3, 4.7, 7.2 |
| #14-2 year-round pool leaves schedules active | 3.1, 4.1 (CONTINUOUS), 4.3 |
| #14-3 mower startup, storage and season work | 4.1, 4.4 (CONTINUOUS tasks), 7.3 |
| #14-4 INACTIVE_SEASON in July, DUE on 15 Oct | 3.5, 4.3 AT_START, 12.1 |
| #14-5 one occurrence at re-entry | 3.5, inv. 93 |
| #14-6 wrap-around and boundary days | 3.1, 12.1 |
| #14-7 calendar or manual activation | 3.1–3.4 |
| #14-8 manual season without fabricated dates, no backlog | 3.3, 3.4, 7.2 |
| #60-1 snowblower before winter, not during the break | 4.3, 4.4, 7.3 |
| #60-2 mower avoids winter prompts, surfaces before season | 4.3, 7.3 |
| #60-3 another user chooses re-entry | 4.1, 7.3 |
| #60-4 continuous with no suppression | 4.1, 4.4, inv. 94 |
| #60-5 season and service window distinct | 2, 3.2, 4.4 |
| #60-6 no fabricated events or backlog | inv. 84–86, 93 |
| #60-7 providers consume one result | 4.7, inv. 100 |
| #60-8 Stage-B examples reviewed | arch. §C, 7, 12.5 |
| #60-9 manual hot tub freezes and resumes | 3.3, 4.3, 7.2 |
| #60-10 in-service and pre-service first class | 4.1 |
| #60-11 readiness before calendar season; hot tub only while active | 4.3, 7.2, 7.3 |
| #61-1 explicit condition independent of schedules | 5, inv. 81, 83 |
| #61-2 UPS DOWN with reason, no fake schedule | 5.2, 12.5 F1 |
| #61-3 scan shows DOWN first with an action | 10.1 |
| #61-4 scan never changes condition | 10.1, inv. 81 |
| #61-5 prior DOWN kept | 5.1, inv. 106 |
| #61-6 DOWN with nothing due in attention | 10.2 |
| #61-7 OPERATIONAL with OVERDUE | 5.5 |
| #61-8 backup, restore, merge keep history | 8.3, 8.4 |
| #61-9 API/MCP read and change with audit | 9.1, 9.5, inv. 120 |
| #61-10 grayscale | 10.6, 12.2 |
| #61-11 configurable health independent of condition | 6, inv. 82 |
| #61-12 battery aging from a replacement baseline | 6.4, 12.5 F1–F2 |
| #61-13 grace, warning, critical | 6.3, 7 |
| #61-14 health never changes condition | 2, inv. 82 |
| #61-15 UI says why, not colour alone | 6.6, 10.6 |
| #61-16 configuration and inputs survive; result reproducible | 6.7, 8.3, 9.1 |
| #61-17 whole-asset or medium subject | 6.1 |
| #61-18 hot tub degrades and recovers | 7.2 |
| #61-19 driver named | 6.6 |
| #61-20 a schedule drives one subject | 6.1, inv. 116 |
| #61-21 primary or aggregate | 6.5 |
| #61-22 primary, average/weighted, worst | 6.5 |
| #61-23 contributors shown; average hides nothing | 6.5, inv. 115 |
| #61-24 actionable date, not raw | 6.2, 7.1, inv. 108 |
| #61-25 frozen hot tub accumulates nothing | 7.1, 7.2 |
| #61-26 pre-service degrades before service | 7.3, inv. 111 |

---

## 14. Open questions and assumed adoptions

### 14.1 Open questions (not settled by arch. §I)

- **Q-1** The owner's real values: break dates, pre-season margins, and per-subject thresholds (arch.
  §J). Rev1 ships the editor defaults in §6.3; the owner supplies real values at Stage C.
- **Q-2** The meter side and health (D-20): excluded in 1.4. Alternative: the date of the first reading
  at or past the threshold starts the clock.
- **Q-3** The PRE_SERVICE guard `O < dl` (§4.3): needed for short intervals; confirm, or restrict
  PRE_SERVICE to intervals of a year or more instead.
- **Q-4** A mower's raw due inside a winter break is pulled to **before** the break (H.2a with rule
  (i)), not to the gap between the break and the season. The owner's late-autumn habit suggests this
  is right; the alternative is one more policy value.
- **Q-5** Health rows on the dashboard: AGE CRITICAL in ATTENTION and AGE WARNING in UPCOMING (§10.2),
  or badges only.
- **Q-6** A manual START is never a pre-service deadline (it is not knowable in advance), so on a
  MANUAL asset PRE_SERVICE pulls before the break only, and is refused without one. Confirm.
- **Q-7** The D12 treatment: icons, the new DEGRADED token, contrast values (§10.6).
- **Q-8** The #47 binding: no reserved column now (§6.1), departing from arch. §D.7's reserved id.
- **Q-9** Moving the season pair out of the asset command (D-4) is a second API retirement beside the
  schedule fields; the alternative keeps it and makes the MANUAL switch a separate call.

### 14.2 ASSUMED adoptions (arch. §I recommendations, each awaiting a ruling)

| I | Adopted | § |
|---|---|---|
| I-1 | `season_mode` YEAR_ROUND / CALENDAR / MANUAL; `MM-DD` kept for CALENDAR | 3.1 |
| I-2 | Dedicated immutable activation table, optional event link | 3.3 |
| I-3 | Switching to MANUAL states the phase and writes the first row | 3.4 |
| I-4 | Repeated START/END is 409 | 3.3 |
| I-5 | One policy enum; FOLLOW_ASSET → IN_SERVICE_AT_START offset 0 | 4.1 |
| I-6 | Both re-entry variants kept | 4.1 |
| I-7 | Break is asset-level | 4.4 |
| I-8 | New status for break-deferred work (DEFERRED) | 4.5 |
| I-9 | The break does not apply to CONTINUOUS | 4.4 |
| I-10 | Separate signed offset; lead stays DUE SOON | 4.2 |
| I-11 | Groups stay continuous | 4.6 |
| I-12 | Hot tub H.1c + H.1b for a medium | 7.1, 7.2 |
| I-13 | H.2a with pull rule (i) | 4.3, 7.3 |
| I-14 | Break: defer or pull per policy; H.3c always | 4.4, 7.4 |
| I-15 | D12 blue-normal palette; icon, word, position | 10.6 |
| I-16 | Score with stored thresholds and bands | 6.3 |
| I-17 | Default WORST, primary when one subject; contributors listed | 6.5 |
| I-18 | Fresh words; no "service" in season UI; no "healthy" | 10.6, 10.7 |
| I-19 | Reminder-health code renamed `ReminderHealth*` | 10.5 |
| I-20 | Battery baseline is an explicit REPLACEMENT fact | 6.4 |
| I-21 | Reason text plus optional event link; no one-off schedule | 5.2 |
| I-22 | Immutable history, correction by a new row, no stored UNKNOWN | 5.1 |
| I-23 | Lockstep release, 422 retirement code, no aliasing | 9.4 |
| I-24 | Recreate `maintenance_schedule` | 8.1 |
| I-25 | Health recomputed, never exported | 6.7 |
| I-26 | DOWN/DEGRADED route a scan through the same predicate | 10.1 |
| I-27 | DOWN first in ATTENTION; DEGRADED after due items | 10.2 |
| I-28 | No condition or health notifications in 1.4 | 5.5, 6.8 |
| I-29 | "Mark operational?" offered, never auto-applied | 5.4 |

---

## 15. Out of scope, seams, version

### 15.1 Seams

- **#47 installed components.** No assembly row, identity or column (§6.1, inv. 122). PART subjects
  and asset-level condition are the pre-#47 forms; #47 adds `health_subject.installed_assembly_id` and
  may add component condition, whose DOWN §6.5 already surfaces separately.
- **#44 merge.** New fact tables stay append-only; configuration rows are #44's UPDATE candidates;
  asset-ID remapping must rewrite `asset_id` in all three new tables (§8.4).
- **#15 stock.** No stock read feeds health; consumables on completion events are unchanged.
- **#45 replication; Todoist (#9, PARKED).** No provider id enters these tables. `Parked(reentryOn)`
  now carries break suppression as well as dormancy (§4.7); how Todoist renders it is #9's decision.
- **#27 reminder health** is unchanged in behaviour; only its code namespace is renamed.
- **Docs edited by the plan's docs brief:** D5 §6 and §10.4 (AT_START reading, manual activation,
  retired statements), D12 §5, `docs/api/v1.md` (routes, codes, the retirement section, "What has no
  endpoint", the sub-resource and table counts), the MCP README, `docs/versioning.md`.

### 15.2 Version — D-27

**`versionName` 1.4.0, `versionCode` 16.** New user-facing capability is a MINOR
(`docs/versioning.md`). Room schema **8**; backup format **8**, forward-only: 1.4 reads formats 1–8 and
1.3.x refuses 8 through `BackupNewerFormat`, the rule that makes a format bump a MINOR. The loopback API
retires five request fields with explicit 422s in a lockstep release of the app, the MCP and the
schedules loader; no stored data becomes unreadable and no archive stops importing, so this is not the
"breaking backup or data contract" row. If the owner reads the API retirement as a documented-behaviour
break, the alternative is 2.0.0 (Q-9 is the lever that would shrink it).
