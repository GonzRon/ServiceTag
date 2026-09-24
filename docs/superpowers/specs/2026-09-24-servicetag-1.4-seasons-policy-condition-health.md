# ServiceTag 1.4 — seasons, service policy, operational condition and derived health: DESIGN SPEC (rev2)

## 0. Status, rulings, and what remains open

**Status: revision 2, 2026-09-24. Owner-ruled; strings not yet ratified.** One programme: **#14**
operating seasons with calendar and manual activation, **#60** maintenance service policy and a
maintenance break, **#61** operational condition and derived health. Rev2 folds in two inputs. The first
is the owner's rulings (`.superpowers/sdd/2026-09-24-servicetag-62-test-architecture/owner-rulings-2026-09-24.md`),
which approve the architecture and rule Q-1 to Q-9 and I-1 to I-29. The second is the independent review
(`…/spec-review-rev1.md`: 3 blocking, 13 should-fix, 16 nits) with the controller's rulings on it. At most
one consolidated re-review follows, then string ratification, then the plan. No #14/#60/#61 code is
written from this revision. Evidence base: the archaeology (`docs/superpowers/specs/2026-09-24-servicetag-1.4-archaeology.md`,
"arch."). Spec 1.2 = `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md`.
Every example is fictional.

**Owner rulings, 2026-09-24:**

| Q | Ruling | Lands in |
|---|---|---|
| Q-1 | No household values baked in. Break dates, margins and thresholds are per-asset or per-subject configuration. 14/45/120 and 2/7/14 are templates the owner applies and confirms, never defaults and never a safety claim. AGE has none | §4.2, §6.3, §10.4 |
| Q-2 | Meter-driven health is out of 1.4; the seam is kept | §6.2 |
| Q-3 | The opened-before guard is kept; PRE_SERVICE is not limited to yearly intervals | §4.3 |
| Q-4 | **Revised.** The season start is the preventive objective and the break only constrains it. An allowed window after the break and before the season is used first; the work is pulled before the break only when the break consumes that window. No asset-type inference | §4.3, §7.3 |
| Q-5 | Independent health rows: CRITICAL in ATTENTION, WARNING in UPCOMING. Health driven by overdue maintenance rides its schedule row. A critical contributor stays visible under any aggregate | §6.5, §10.2 |
| Q-6 | A manual START is never a pre-service deadline and none is predicted. The hot tub is IN_SERVICE | §3.1, §4.3 |
| Q-7 | D12 families: cool blue nominal, the warning/amber family for warning, the error family for critical and DOWN, DEGRADED distinguishable, and every state distinct by word, icon and placement | §10.6 |
| Q-8 | No reserved #47 column. PART subjects stay asset-scoped | §6.1 |
| Q-9 | Stay on `/v1` and 1.4.0. The old season fields are deprecated compatibility inputs translated by the legacy mapping. A write an old body cannot represent is refused loudly, never reset | §9.4, §15.2 |

**Dispositions of arch. §I:**
- 27 recommendations are marked **RATIFIED (I-n, 2026-09-24)** where they are used.
- I-13 is ratified in principle (missed pre-service work may degrade health before the season), but its pull rule is revised by Q-4.
- I-23 is replaced by the Q-9 ruling.

§14 lists every disposition and every review finding.

**Still open for the owner:**
1. **Strings.** Ratify the 139 strings in §10.7: 135 new and 4 shipped words in new roles.
2. **D12 tokens.** Contrast-checked values for the new states, in the design brief (§10.6; the colour families are ruled by Q-7).
3. **YEAR_ROUND with a break.** PRE_SERVICE on such an asset uses the break start as its boundary, the knowable boundary Q-6 accepts for a MANUAL asset (§4.3). Confirm.
4. **A break that fills the whole gap between seasons.** The season forbids nothing, so the pre-service point is the last day before the break, which can fall inside the previous season (§4.3). Confirm.
5. **AT_START wording (review S-4).** Re-entry moves a date only when it is *earlier than* the season start plus offset, not "outside the season" as #14 reads literally. A later date goes dormant instead of being dragged back (§4.3). Acknowledge.
6. **Postponement and the health clock (controller S-1).** Postponing an overdue item restarts its maintenance-overdue clock from the postponed date. A postponement writes only `postponed_due_on` and no journal event (`PostponeSchedule.kt`), so the earlier lateness is not recorded anywhere. Recording it would need a dated postponement, which is a schema change (§7.1). Acknowledge.
7. **Old meter-only FOLLOW_ASSET schedules (controller B-1).** In an old archive such a schedule becomes CONTINUOUS and loses 1.2's seasonal gating. Real data has none (§4.1). Acknowledge.
8. **Critical health on a scan.** A CRITICAL health subject is listed on the scan sheet but does not open the sheet by itself. Only a D-18a item or a DOWN/DEGRADED asset or component opens it (§10.1). Confirm.

**Decision register:**

| D | Decision | Ruled by | § |
|---|---|---|---|
| D-1 | Seven concepts, never collapsed; the interaction matrix is normative | architecture approved | 2 |
| D-2 | `season_mode` YEAR_ROUND / CALENDAR / MANUAL | I-1 | 3.1 |
| D-3 | Manual activation is an immutable row, never a journal event | I-2 | 3.3 |
| D-4 | Season mode+window, the break and health policy each have their own command; the asset command keeps 1.3's shape | Q-9, review S-6 | 3.2, 4.4, 6.5 |
| D-5 | Switching to MANUAL states the phase; a repeated START/END is 409 | I-3, I-4 | 3.3–3.4 |
| D-6 | One `ServicePolicy` enum replaces `season_behavior` + `season_reentry` | I-5, I-6 | 4.1 |
| D-7 | One signed offset; the owner enters the pre-service margin; the lead stays the DUE SOON window | I-10, Q-1 | 4.2 |
| D-8 | Pre-service: the season start is the objective and the break a constraint. The window after the break comes first; before the break only when the window is consumed; opened-before guard | I-13 in principle, Q-3, Q-4, Q-6 | 4.3 |
| D-9 | Asset-level break; CONTINUOUS ignores it; already-late work stays late and quiet | I-7, I-9, I-14 | 4.4 |
| D-10 | New status DEFERRED, decided after the worst-of fold on the time side | I-8, review B-1 | 4.5 |
| D-11 | Group targets stay CONTINUOUS | I-11 | 4.6 |
| D-12 | The engine alone derives the actionable date; reads derive stale state in memory and never write | architecture approved, review S-2 | 4.7 |
| D-13 | Condition is immutable history with a reason and a soft event link; no notifications; "Mark operational?" is offered, never applied | I-21, I-22, I-28, I-29 | 5 |
| D-14 | Health subjects ASSET / PART / MEDIUM, one driver each; no #47 column | Q-8 | 6.1 |
| D-15 | 0–100 score, three per-subject thresholds the owner enters (templates confirmed, never defaulted), fixed band cut points | I-16, Q-1 | 6.3 |
| D-16 | Baselines are canonical events; a missing baseline profile reads NOT TRACKED | I-20, review S-12 | 6.4 |
| D-17 | Aggregation stored, default WORST; critical contributors and DOWN/DEGRADED components always visible | I-17, Q-5, review B-3 | 6.5 |
| D-18 | Health computed at read time; never stored, exported or defaulted | I-25, Q-1 | 6.7 |
| D-19 | Health clock: H.1c+H.1b for a medium, H.1a otherwise; H.2a's principle; H.3 per policy, H.3c always | I-12, I-13, I-14 | 7 |
| D-20 | The policy and health act on the time side only; the meter side's seam is kept | Q-2, review B-1 | 4.3, 6.2 |
| D-21 | Recreate `maintenance_schedule`; recreate derived `schedule_state` | I-24 | 8.1 |
| D-22 | Merge tables 12–14; identity by row id; one declining second identity | architecture approved | 8.4 |
| D-23 | `/v1` stays compatible; legacy inputs go through the legacy mapping; a legacy write that cannot represent 1.4 state is refused | Q-9 (replaces I-23) | 9.4 |
| D-24 | Scan routing widened through the one predicate; health lines are passengers | I-26, review B-3 | 10.1 |
| D-25 | DOWN first, DEGRADED after due work; independent health rows; components promoted | I-27, Q-5, review S-3 | 10.2 |
| D-26 | D12 families; fresh words; reminder-health code renamed | I-15, I-18, I-19, Q-7 | 10.5–10.6 |
| D-27 | 1.4.0 / versionCode 16, MINOR | Q-9 | 15.2 |
| D-28 | `rule_changed_at` pins the recurrence; non-rule edits move nothing (fixes #64) | controller B-2 | 4.3, 8.1 |
| D-29 | Spec 1.2's D-4 consequence ("the stored sort key is `T`-independent") is retired | review S-2 | 4.7 |

---

## 1. Goals and non-goals

### 1.1 Goals

- Part-year assets with calendar or manual seasons, and no backlog while dormant (#14 AC 5, AC 8).
- A per-schedule answer to "when should this be done relative to the season", kept separate from the
  season itself. Pre-service and in-service are both first-class (#60 AC 5, AC 10), and a maintenance
  break is supported (#60 AC 1).
- An explicit, auditable operational condition (#61 AC 1–10).
- Health that can be explained, driven by age or overdue maintenance, whose clock follows the policy and
  never a raw recurrence date (#61 AC 11–26).

### 1.2 Non-goals

| Out | Why |
|---|---|
| Installed components or assemblies, and any column for them | #47 (Q-8) |
| Stock or consumable levels feeding health | #15 |
| Measurement-, telemetry- or meter-driven health | #61 "must not require telemetry"; Q-2 |
| A one-off work item ("replacement pending" as a task) | RATIFIED (I-21, 2026-09-24): reason text plus an optional event link |
| Notifications for condition or health | RATIFIED (I-28, 2026-09-24) |
| An UNKNOWN condition value | RATIFIED (I-22, 2026-09-24) |
| Conflict-resolution UI, UPDATE merge verdicts | #44 |
| Todoist `PARKED` behaviour | #9, not built (arch. §J) |
| Season or policy on a maintenance group | RATIFIED (I-11, 2026-09-24) |
| A predicted manual START | Q-6 |

---

## 2. The seven concepts (normative)

arch. §G becomes contract. "Service" means the maintenance service policy only. The commissioning date
keeps its shipped label, "In service date", and is none of the seven (arch. §A.6).

| # | Concept | Question | Canonical data | Derived | Changed by |
|---|---|---|---|---|---|
| C1 | Asset lifecycle | Still ours and in use at all? | `asset.status`, `retired_on` | `targetInService` | asset edits |
| C2 | Operational condition | Can I rely on it right now? | `asset_condition` rows (§5) | current = latest row | an explicit condition write only |
| C3 | Derived health | How deteriorated or at risk, and why? | `health_subject` rows + events + schedule history | score, band, drivers (§6), at read | nothing writes it |
| C4 | Schedule state | What work is due? | schedule row, completions, closures | `schedule_state`; status at read | `rebuild` only |
| C5 | Operating season | When is it normally in use? | `asset.season_mode`, window, activation rows (§3) | season phase | season-mode command, START/END, a representable legacy asset write |
| C6 | Service policy | When should work be done relative to the season? | `service_policy`, offset; the asset's break (§4) | actionable date, policy phase, `quiet` | schedule edits, break command |
| C7 | Reminder health | Is the reminder mechanism working? | none | `ReminderHealthFinding` on demand | nothing |

**Interaction matrix.** Each row may affect each column. "—" means never, and each "—" is an invariant (§11).

| affects → | C1 | C2 | C3 | C4 | C5 | C6 | C7 |
|---|---|---|---|---|---|---|---|
| C1 lifecycle | · | — | excludes from surfaces only | withdrawn/excluded (shipped) | — | — | findings (shipped) |
| C2 condition | — | · | — | — | — | — | — |
| C3 health | — | — | · | — | — | — | — |
| C4 schedule state | — | — | the MAINTENANCE_OVERDUE input | · | — | — | findings (shipped) |
| C5 season | — | — | the clock (§7) | phase, actionable date | · | — | — |
| C6 policy | — | — | via the actionable date | actionable date, DEFERRED, quiet | — | · | — |
| C7 reminder health | — | — | — | — | — | — | · |

Three rules sit above the matrix (arch. §G; #61 AC 14):
- **Nothing derives condition.**
- **Health never changes condition**, and condition never pauses a schedule or moves health.
- **The policy moves only the actionable date, on the time side.** The occurrence key stays `computedDueOn`.

---

## 3. Season model (#14)

### 3.1 Season mode — RATIFIED (I-1, 2026-09-24)

`asset.season_mode ∈ {YEAR_ROUND, CALENDAR, MANUAL}`, a new non-null column. The shipped
`season_start_mmdd` / `season_end_mmdd` survive as CALENDAR's boundaries (arch. §B row 1). They are
**non-null if and only if the mode is CALENDAR**. `Season.inSeason` (`core/…/model/Season.kt:27-38`)
stays the calendar predicate: inclusive bounds, wrapping when `start > end`, and a non-leap 02-29 read
as 02-28 (#14 AC 6).

**Season phase** at date `d`, derived per asset:
- YEAR_ROUND: always IN_SEASON.
- CALENDAR: `inSeason(window, d)`.
- MANUAL: the action of the latest activation row with `occurred_on ≤ d`. START means IN_SEASON from that day inclusive; END means OUT_OF_SEASON from that day inclusive.

The **cycle start** at `d` is the first day of the season span containing `d`. Out of season on a
CALENDAR asset it is the next start. A MANUAL asset out of season has none: the next START is not
knowable and is never predicted (Q-6; #14 "no artificial fixed date").

### 3.2 Changing the season (D-4)

Mode and window form one configuration with two cross-row effects: the first activation row (§3.4) and
the PRE_SERVICE schedules it could strand (§4.3). A 1.4 client changes them through one **season-mode
command** (`POST /v1/assets/{id}/season-mode`). The asset command keeps exactly 1.3's shape, and its
`seasonStartMmdd` / `seasonEndMmdd` become **deprecated compatibility inputs** (Q-9, §9.4):
- A pair equal to the stored pair leaves the season untouched. This is how a MANUAL asset, whose pair is null, survives a read-overlay-write round trip.
- A different pair is translated by the season-mode use case into CALENDAR(window) or YEAR_ROUND.
- On a MANUAL asset a different pair is 422 `LEGACY_WRITE_CANNOT_REPRESENT`.

The season-mode command refuses:
- CALENDAR without a valid window: 422 `SEASON_WINDOW_REQUIRED`.
- A window with any other mode: 422 `SEASON_WINDOW_FORBIDDEN`.
- `manualPhase` sent when the result is not a switch into MANUAL (including MANUAL → MANUAL): 422 `MANUAL_PHASE_FORBIDDEN`.
- A change that leaves a PRE_SERVICE schedule with no boundary: 409 `SEASON_MODE_STRANDS_POLICY`, naming the schedules (#60 AC 5).

Leaving MANUAL keeps the activation rows as history; they are not read while the mode is not MANUAL.

### 3.3 Manual activation facts — RATIFIED (I-2, I-4, 2026-09-24)

```
asset_season_activation          -- immutable fact; the occurrence_closure precedent (spec 1.2 §2.9)
  id           TEXT PK
  asset_id     TEXT NOT NULL      FK asset ON DELETE CASCADE
  action       TEXT NOT NULL      START | END
  occurred_on  TEXT NOT NULL      ISO date, device-local
  event_id     TEXT NULL          soft link, no FK (§5.3)
  created_at   INTEGER NOT NULL
  INDEX(asset_id, occurred_on)
```

The table has no `updated_at`. Rows are never updated or deleted, except by CASCADE when their asset is
deleted, and are ordered by `(occurred_on, created_at, id)`. A local write is refused when:
- the asset is not MANUAL: 409 `SEASON_NOT_MANUAL`;
- it would follow START with START or END with END: 409 `SEASON_ALREADY_STARTED` / `SEASON_ALREADY_ENDED`;
- `occurred_on` is after today or before the latest row's date: 422 `SEASON_DATE_OUT_OF_RANGE`.

A merge can still bring two STARTs in a row. The phase is the latest row's action, and the history shows
both (§8.4). A retired or archived asset may still record activations; its lifecycle only limits where
it surfaces.

**Journal events never activate** (arch. §B row 7). A startup task's completion takes its profile's kind
(`CompleteSchedule.kt:81`), so it must not toggle a season. After an event of kind SEASON_START
(SEASON_END) on a MANUAL asset that is out of (in) season, the app **offers** "Start the season now?"
("End the season now?"). Accepting writes a row dated the event's date, clamped into range, with
`event_id` set.

### 3.4 Switching to MANUAL — RATIFIED (I-3, 2026-09-24)

A switch into MANUAL must state the current phase (`manualPhase: IN_SEASON | OUT_OF_SEASON`; 422
`MANUAL_PHASE_REQUIRED` if absent). In the same transaction it writes one activation row, START or END,
dated today. The switch states the phase anew even if the latest historical row already says the same:
END followed by END is valid history, and the 409s in §3.3 govern only START/END actions. There is no
silent default. The engine is still total: a MANUAL asset with no row, which only an inconsistent import
can produce, reads OUT_OF_SEASON and its asset detail offers "Start season".

### 3.5 Inactive season

`INACTIVE_SEASON` and the word **OUT OF SEASON** survive (arch. §B row 6). They mean exactly one thing:
an IN_SERVICE schedule (§4.1) whose asset is OUT_OF_SEASON. The occurrence is kept and `computedDueOn`
is still computed. Nothing notifies and nothing accumulates (#14; spec 1.2 inv. 13). Re-entry leaves
exactly one current occurrence (#14 AC 5).

---

## 4. Service policy and the actionable date (#60)

### 4.1 The policy enum — RATIFIED (I-5, I-6, 2026-09-24)

`maintenance_schedule.service_policy` replaces `season_behavior` and `season_reentry` (arch. §B rows 2–4).
The policy applies to the **time side only** (controller B-1).

| Policy | Editor wording (§10.7) | Family (#60) |
|---|---|---|
| `CONTINUOUS` | "Whenever it is due" | continuous / calendar (#60 AC 4) |
| `IN_SERVICE_AT_START` | "When the season starts" + "The season's start"; on a YEAR_ROUND asset with a break, "After the maintenance break" (offset stored 0, unused) | in-service, re-entry at the start + offset (#60 AC 9) |
| `IN_SERVICE_RESUME_CLAMPED` | "When the season starts" + "Its own date, but not before the season starts" | in-service, clamped resume (D5 §10.4) |
| `PRE_SERVICE` | "Before the season starts"; with no calendar season but a break, "Before the maintenance break" | pre-service preventive (#60 AC 1, AC 11) |

A schedule with **no time rule** has no raw due date and so no policy date: it is CONTINUOUS by
construction. Any other policy on it is 422 `SEASON_POLICY_NEEDS_A_TIME_RULE`, in both command forms,
as a group target is refused.

**The legacy mapping** is one table. The 7→8 migration, the format ≤7 decoder and the API's deprecated
inputs (§9.4) all use it, and one test proves the three agree:

| Time rule | `season_behavior` | `season_reentry` | → policy | → offset |
|---|---|---|---|---|
| none | anything | anything | CONTINUOUS | null |
| yes | IGNORE, or absent | anything | CONTINUOUS | null |
| yes | FOLLOW_ASSET | null, `AT_START`, or unrecognised (an `MM-DD`, say) | IN_SERVICE_AT_START | old offset if 0–365, else 0 |
| yes | FOLLOW_ASSET | `RESUME_CLAMPED` | IN_SERVICE_RESUME_CLAMPED | null |

Refusing unrecognised values on decode would make a readable archive unreadable, a MAJOR
(`docs/versioning.md`). A meter-only FOLLOW_ASSET schedule in an old archive loses 1.2's seasonal gating
(open item 7). On the API, a legacy body the table cannot express faithfully is refused (§9.4), never
translated. Real data is untouched by all of this: all 43 Stage-B schedules are IGNORE, with time rules
and no re-entry value (arch. §C).

### 4.2 The offset — RATIFIED (I-10, 2026-09-24)

`policy_offset_days INTEGER NULL` is signed and measured from a boundary:
- **IN_SERVICE_AT_START:** 0 to 365 ("days after it starts"), required, and 0 unless changed. The value 0 means the start itself, not a household value.
- **PRE_SERVICE:** −365 to −1 ("days before it starts"), required, and **entered by the owner with no suggestion** (Q-1).
- **The other two policies:** null.

Anything else is 422 `POLICY_OFFSET_INVALID`. The editor's field bounds make this unreachable from the
UI (S71–S72). On a CALENDAR asset, when `s + offset` falls after the season's end the editor warns
(S84); it does not refuse, because the window can change. `lead_days` keeps its 1.2 meaning: the DUE SOON
window before the actionable date.

### 4.3 Deriving the actionable date

**Terms** (arch. §H):
- **Raw due** `R` = `computedDueOn`. No policy changes it, and it is still the occurrence key.
- **Opened** `O` = the occurrence's own opening. That is `lastTerminationEffectiveOn`, or, for a never-terminated schedule, the date of `rule_changed_at`: spec 1.2 D-27's pin floor, which now reads that column (D-28). It is never the row's `updated_at`.
- **Actionable** `A` = the policy applied to `postponedDueOn ?: R` (below). `A` is the status input and the sort key, and is materialised as `actionable_due_on`.

`effective_due_on` keeps its documented 1.2 meaning, `postponedDueOn ?: computedDueOn`
(`docs/api/v1.md:196`), so no v1 response field changes meaning.

**Pre-service: an objective and a constraint** (Q-4, Q-6):
- The operating season sets the **objective**: the work becomes actionable before the asset enters its season.
- The break **constrains** when the work may be actionable. The season itself forbids nothing.
- The **boundary** `s` for a date: on a CALENDAR asset, the start of the season containing it, else the next start after it. An asset with no calendar season has only its break to be ready before, which is the knowable boundary Q-6 names: `s` is the start of the break containing the date, else the next one.
- A manual START is never a boundary. With neither a calendar season nor a break, there is none.
- The **deadline** is `s + offset`. A day is **allowed** when it is outside the break.

```
policyDue(p, R: LocalDate?, O, ctx, T): Pair<LocalDate?, Reason> {
  if (R == null) return null to NONE                                   // no time side
  when (p) {
    CONTINUOUS -> return R to NONE
    IN_SERVICE_* -> {
      val s = ctx.cycleStartAt(T)                     // next start while off; null: YEAR_ROUND, MANUAL ended
      if (s == null && ctx.mode == MANUAL) return null to AWAITING_START
      val a = if (s == null) R else reentry(p, R, s)  // AT_START: R < s+off ? s+off : R. RESUME: max(R, s)
      return if (ctx.inBreak(a)) ctx.firstAllowedAfter(a) to AFTER_BREAK
             else a to (if (a != R) SEASON_START else NONE)
    }
    PRE_SERVICE -> {
      val s = ctx.preServiceBoundary(R) ?: return R to POLICY_INAPPLICABLE
      val deadline = s + offset                                        // offset <= -1
      val next = ctx.firstAllowedAfter(R)
      if (R <= deadline && ctx.allowed(R)) return R to NONE            // already in time
      if (R < deadline && next <= deadline) return next to AFTER_BREAK // the window after the break
      val p1 = ctx.lastAllowedOnOrBefore(minOf(R, deadline))           // be ready before s
      return if (O < p1) p1 to (if (p1 == deadline && ctx.seasonBoundary) BEFORE_SEASON else BEFORE_BREAK)
             else if (ctx.allowed(R)) R to NONE                        // opened too late to pull
             else next to AFTER_BREAK
    }
  }
}
```

**A postponement is subject to the policy** (controller S-1). With `postponedDueOn` = `P` set:
CONTINUOUS gives `P`; IN_SERVICE runs its branch on `P` in place of `R`; PRE_SERVICE gives `P`, moved to
the first allowed day after the break when `P` falls inside it, and never earlier than the owner's
chosen date. So a postponement that lands in a dormant season or a break is re-entered or deferred
exactly like a raw due, and it can never read OVERDUE on the first in-season day (arch. Finding A-1).

Readings that pin the shape:
- **The window after the break comes first** (Q-4). A raw due inside a break, with allowed days
  between the break's end and the deadline, becomes actionable on the first of them. The mower's
  January date surfaces in March, before its April season, not the previous autumn (§7.3).
- **Before the break only when the break consumes the window.** If no allowed day lies between `R` and
  the deadline, the point is the last allowed day on or before it (BEFORE_BREAK). The snowblower is the
  other shape: its break lies inside its season, the deadline is allowed, and in-season raw dues are
  pulled to it (BEFORE_SEASON).
- **The opened-before guard** (Q-3, kept). Nothing is made actionable earlier than `R` unless the
  occurrence opened before that point (`O < p1`). Without the guard, a monthly PRE_SERVICE schedule
  completed in season would drag every later in-season occurrence back before the season and read
  OVERDUE all season. Any interval may be PRE_SERVICE.
- **No inference** (Q-4). The rule reads mode, window, break, offset and history, and never the
  category, a template or a name.
- **AT_START moves a date only when it is earlier than `s + offset`.** D5 §6 and #14 say "outside the
  season or earlier". Read literally, that would drag a raw due *after* this cycle's end back to its
  start. Here a later date goes dormant when the season ends (open item 5; the D5 §6 edit, §15.1).
- **Totality.** With no boundary (YEAR_ROUND or MANUAL without a break) a PRE_SERVICE schedule behaves
  as CONTINUOUS with reason POLICY_INAPPLICABLE, and its detail says so (S77). Creating that state is
  refused with 409 `PRE_SERVICE_NEEDS_DATES`. Removing the window or break such a schedule relies on is
  refused too (`SEASON_MODE_STRANDS_POLICY`, `BREAK_STRANDS_POLICY`), so only a merge reaches it.
- **The season forbids nothing.** A break covering the whole gap between seasons therefore puts the
  point on the last day before the break, possibly inside the previous season (open item 4).

**Rule fields and the pin (D-28, controller B-2; issue #64).** In 1.3, `SaveSchedule` stamped
`updated_at` on every save (`SaveSchedule.kt:133`), and the never-terminated FIXED pin floor read that
stamp. So a title edit could move a never-terminated schedule's due date, against spec 1.2 inv. 25 and
`docs/api/v1.md:164`.

Schema 8 adds `rule_changed_at`. SaveSchedule writes it only when `ruleChanged(before, after)`, and the
pin floor reads it. `service_policy` and `policy_offset_days` are **not** rule fields: they are left
out of `ruleChanged`, clear no postponement and abandon no round. A policy-only, title-only, lead-only
or health-link edit therefore moves neither the pin nor `O`.

`rebuild` takes the season context as a pure input (mode, window, break, activation rows) and
materialises four derived values:
- `actionable_due_on`;
- `policy_reason`;
- `policy_phase`: DORMANT when an IN_SERVICE schedule's asset is OUT_OF_SEASON at `T`, otherwise ACTIVE;
- `quiet`, defined as **`service_policy ≠ CONTINUOUS ∧ T` is inside the asset's break**. While quiet, no notification is delivered for either side. DEFERRED and DORMANT never notify anyway.

The occurrence key is still stamped from `computedDueOn` (`model/Journal.kt:66-72`).

### 4.4 The maintenance break — RATIFIED (I-7, I-9, I-14, 2026-09-24)

`asset.blackout_start_mmdd` / `blackout_end_mmdd` are set both or neither, wrap like a season, and
never cover the whole year (422 `BLACKOUT_COVERS_THE_YEAR`). The UI calls it the **maintenance break**.
It is changed only by its own command, `POST /v1/assets/{id}/maintenance-break`, never together with the
season (#60 AC 5 by construction; review S-6). That command refuses a change that would strand a
PRE_SERVICE schedule relying on the break (409 `BREAK_STRANDS_POLICY`, S64).

The break is asset-level (an indoor UPS has none). It applies to IN_SERVICE and PRE_SERVICE only:
startup and shutdown tasks are CONTINUOUS and fall inside it by design (D5 §6). Its effects:
- **Defer (H.3a).** For IN_SERVICE, for PRE_SERVICE work whose pre-season window lies after the break,
  and for PRE_SERVICE work opened too late to pull, an actionable date inside the break moves to the
  first day after it.
- **Pull earlier (H.3b).** Only for PRE_SERVICE, and only when the break consumes the pre-season window
  (§4.3).
- **Already late (H.3c), always.** An item due before the break stays put. It keeps its word (OVERDUE),
  counts as due, keeps degrading health (§7), and is `quiet` while `T` is in the break (#60 "must remain
  visible").

### 4.5 DEFERRED — RATIFIED (I-8, 2026-09-24)

A new `DueStatus.DEFERRED`, **decided after the worst-of fold, on the time side alone** (controller B-1).
The time side is **held** when three things are true:
- its actionable date was moved later out of a break (`policy_reason = AFTER_BREAK`);
- `T ≥ (postponedDueOn ?: R) − leadDays`;
- `T < A`.

`statusOf` asks, in order:
1. Not ACTIVE → PAUSED.
2. `policy_phase == DORMANT` → INACTIVE_SEASON.
3. No evaluable side → NO_DATA.
4. Otherwise, the shipped worst-of fold, with a held time side counted as OK. If the fold answers OK and the time side is held, the status is DEFERRED.

The meter side is never moved, deferred or re-entered. A crossed threshold reads DUE (a meter has no
OVERDUE degree) even when the time side is held, and only `quiet` withholds its notification. DEFERRED
never notifies and never counts as due. It has its own quiet dashboard section, **Deferred**, between
CURRENT and OUT OF SEASON, and joins the dashboard status filter (`DashboardFilters.kt:56-60`, review N-7).

### 4.6 Group targets — RATIFIED (I-11, 2026-09-24)

A group-targeted schedule is CONTINUOUS only. Anything else is 422
`SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET`, the shipped code restated (arch. §B row 8). A group has no break.

### 4.7 One derivation; reads never write (#60 AC 7)

The engine is the only place a season boundary, a break or a policy is evaluated.
`BuildReminderSubjects` stops recomputing the next season start from `MM-DD`
(`BuildReminderSubjects.kt:138-143`). It maps engine output onto the unchanged port:

| Engine state | Subject state |
|---|---|
| ARCHIVED | `Withdrawn` |
| PAUSED | `Parked(null)` |
| DORMANT | `Parked(actionableDueOn)`: null for MANUAL, since no re-entry date exists before a START (spec 1.2 inv. 10 stands for `effectiveDueOn`; `actionableDueOn` may be null here) |
| DEFERRED | `Parked(A)` |
| `quiet` with a notifying status | `Parked(the first day after the break)` |
| otherwise | `Active` |

`RuleFacts.seasonal` becomes `servicePolicy ≠ CONTINUOUS`. It is false before and after on real data,
so no content hash moves.

`A` now depends on `T`, so spec 1.2 D-4's "`T`-independent sort key" is retired (D-29). **No read path
writes** (review S-2): `/v1/due`, `/v1/attention`, `/v1/assets/{id}/health`, the dashboard, asset detail
and scan routing all do the same. A `schedule_state` row whose `computed_for_on ≠ T`, or a missing row,
is derived in memory through `RecomputeSchedules.stateOf` (`RecomputeSchedules.kt:135-147`), which
already serves missing rows (`DueReadModel.kt:192`). Health reads that fresh state. Only three things
persist state: the daily job, a recompute after a write, and an explicit rebuild. This removes spec 1.2
inv. 23's between-rebuilds exception without a write on read.

---

## 5. Operational condition (#61)

### 5.1 The history — RATIFIED (I-22, 2026-09-24)

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

Three values and no fourth (#61): "pending maintenance" is a reason, not a condition. There is **no
stored UNKNOWN**: an asset with no row reads **Condition not recorded**, so nobody must attest every
asset.
- **Current** is the latest row by `(occurred_on, occurred_time, created_at, id)`, with a null time
  sorting first.
- **Since** is the earliest row of the latest run of equal values, so re-attesting DOWN keeps "DOWN
  since 20 Sep".
- No column stores the current condition. One on `asset` would make every change an asset-row conflict
  (arch. §D.1).
- A correction is a new row. A backdated row sorts into place.
- An archived or retired asset may record condition; its lifecycle only limits where it surfaces.

### 5.2 Reason and the UPS case — RATIFIED (I-21, 2026-09-24)

"Battery failed — replacement pending" is the reason on a DOWN row. No one-off schedule and no fake
recurrence is created (#61 AC 2).

### 5.3 The event link is soft

`event_id` names the event that prompted the transition: an INCIDENT, or the repair that ended it. It has
**no foreign key**. Events can be deleted (`docs/api/v1.md:140-141`), and `SET NULL` would mutate an
immutable row and make every later re-import `CONTENT_DIFFERS`. A dangling link renders as "The linked
record was removed." and is never an owner in a merge (§8.4).

### 5.4 Writes and offers — RATIFIED (I-29, 2026-09-24)

A condition row is written only by:
- "Save condition" (§10.1);
- the "Mark operational" confirmation;
- the offer below;
- `POST /v1/assets/{id}/conditions`;
- MCP `record_condition`.

A scan, health value, status, season, event or completion never writes one (#61 AC 4, AC 14).

After a completion, or an event of kind MAINTENANCE or REPLACEMENT, on a DOWN or DEGRADED asset, the app
offers "Mark operational?". Accepting writes OPERATIONAL dated `max(event date, current row's date)`
with `event_id` set. "Not yet" writes nothing. A repair is never assumed to have succeeded.

### 5.5 What condition does not do — RATIFIED (I-28, 2026-09-24)

Condition pauses no schedule, withholds no reminder, moves no health value and produces no notification.
An OPERATIONAL asset may show OVERDUE maintenance (#61 AC 7), and a DOWN asset's overdue schedule stays
overdue.

---

## 6. Derived health (#61)

### 6.1 Subjects — D-14, Q-8

A **health subject** is a named, health-bearing thing on one asset with **one driver**:

```
health_subject                   -- configuration aggregate
  id, asset_id (FK CASCADE), name (1–60 chars), kind ASSET | PART | MEDIUM,
  driver AGE | MAINTENANCE_OVERDUE,
  schedule_id NULL (FK maintenance_schedule CASCADE)   -- MAINTENANCE_OVERDUE only, required
  baseline_profile_id NULL (soft link, no FK)           -- AGE only, optional (§6.4)
  nominal_until_days, warning_from_days, critical_from_days  INTEGER NOT NULL, no default
  weight INTEGER NOT NULL DEFAULT 1 (1–10), sort_order, archived_at NULL, created_at, updated_at
  INDEX(asset_id), INDEX(schedule_id)
```

The three kinds:
- `ASSET` is the whole asset.
- `PART` is a named logical part with no installed-component identity (the UPS's "Battery age").
- `MEDIUM` is a maintained medium such as hot-tub water care. It says nothing about the pump or heater (#61 AC 17).

Kind changes only the season clock (§7). Each subject has one driver. Composition happens across
subjects, at the asset (§6.5), which is what makes "72 — Water care 55, Pump 100" explainable. A
schedule drives **at most one non-archived subject** (#61 AC 20), and a subject never changes asset.

A save is refused when:
- a MAINTENANCE_OVERDUE subject lacks an asset-targeted schedule of the same asset with a time rule;
- an AGE subject names a schedule;
- the baseline profile is not this asset's or not of kind REPLACEMENT;
- the thresholds do not satisfy `0 ≤ t1 < t2 < t3 ≤ 36,500`.

**#47 (Q-8, confirmed).** Schema 8 has no reserved or dead column. PART subjects stay asset-scoped
logical subjects, and #47 adds the binding when its schema exists. Identity is the row id, never the
name.

### 6.2 Drivers — Q-2

- **AGE**: `x` = days from the baseline (§6.4) to `T`. Season, policy and pause are ignored, because a
  battery ages in storage (arch. §H).
- **MAINTENANCE_OVERDUE**: `x` = the counted late days of the linked schedule's current occurrence
  (§7). They are measured from its **actionable** date, never from `computedDueOn` (#61 AC 24).
- **The meter side drives no health in 1.4** (Q-2; D-20). A meter-only schedule cannot drive a subject
  (422 `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`), and a combined schedule's meter side contributes nothing.
  The seam is kept: a later driver can read a crossing date without changing the subject's shape.

### 6.3 Score and bands — RATIFIED (I-16, 2026-09-24); Q-1

A score `0–100`. Each subject stores **three thresholds**, `t1 < t2 < t3`, in days, the unit the owner
thinks in. The **score anchors are fixed**, so every score means the same thing and an aggregate has a
band.

```
score(x; t1, t2, t3) = ceil(v), where v =
  100                                   when x <= t1
  100 - 40·(x - t1)/(t2 - t1)           when t1 < x <= t2
   60 - 35·(x - t2)/(t3 - t2)           when t2 < x <= t3
   25 - 25·(x - t3)/(t3 - t2)           when t3 < x <= t3 + (t3 - t2)
    0                                   beyond
band(s) = CRITICAL if s <= 25, WARNING if s <= 60, NOMINAL otherwise
```

The arithmetic is exact rational followed by a ceiling. So a single subject is WARNING from exactly `t2`
and CRITICAL from exactly `t3`, and the score never rises as `x` grows. The band words are **NOMINAL ·
WARNING · CRITICAL**, plus **NOT TRACKED**. Never "healthy", and never a 100 standing in for no data.

**Nothing is defaulted (Q-1):**
- The thresholds are per-subject configuration. The fields start empty and the subject command requires all three.
- The editor offers two **templates**, engine service (14 / 45 / 120 days overdue) and water care (2 / 7 / 14). A template fills the fields only after the owner applies it and confirms S129 ("starting points, not safety limits").
- A template is never a canonical value and never a physical or chemical claim. AGE has none.
- The fixtures (§12.5) state their values explicitly.

### 6.4 Baselines are events — RATIFIED (I-20, 2026-09-24)

- **AGE**: the latest event (by `EventChronology`) on the subject's asset of kind **REPLACEMENT**,
  logged with the named quick action when one is set.
  - With no such event the subject is NOT TRACKED ("No replacement recorded yet").
  - A new REPLACEMENT resets the value and erases nothing (#61 AC 12). Deleting it restores the previous value.
  - `in_service_on` is not a baseline.
  - `baseline_profile_id` is a **soft link** (review S-12). Deleting a quick action is always allowed (`DeleteProfile.kt`). A deleted one leaves the subject NOT TRACKED ("The replacement quick action was removed.") and never widens the filter to any REPLACEMENT. The row itself is never mutated.
- **MAINTENANCE_OVERDUE**: the linked schedule's terminations, through the engine. A completion advances
  the occurrence, the new actionable date lies ahead, and `x` returns to 0 (#61 AC 18). A closure claims
  no work (spec 1.2 inv. 36) and cannot reach a subject, because closures are group-only and a group
  schedule drives none.

There is no baseline table and no mutable baseline column. **The C.2 hazard:** a combined "load test and
battery replacement" schedule with a REPLACEMENT-kind profile would reset battery age on a load test. The
fixture splits it into an INSPECTION load-test profile and a REPLACEMENT "Battery replaced" profile, which
it names as the baseline. The Stage-B note recommends the same split on real data.

### 6.5 Aggregation — RATIFIED (I-17, 2026-09-24); Q-5

`asset.health_aggregation ∈ {TRACK_ONE, AVERAGE, WEIGHTED, WORST}` defaults to **WORST**. With it sits
`asset.health_primary_subject_id`, which has no FK and is required for TRACK_ONE only. Both change only
through their own command, `POST /v1/assets/{id}/health-policy`.

Contributors are the non-archived subjects that have a value:
- WORST is the minimum.
- TRACK_ONE is the primary's value.
- AVERAGE is `floor(mean)`.
- WEIGHTED is `floor(Σ w·s / Σ w)`.

Floors never round an aggregate up into a better band. With one subject every policy equals it. With no
contributors the aggregate is NOT TRACKED. Archiving the primary is refused (409
`HEALTH_SUBJECT_IS_PRIMARY`, S137). A dangling primary falls back to WORST (S138).

**Nothing hides behind an aggregate** (#61 AC 23; Q-5; review B-3). On **every surface that shows health**
— asset detail, the scan sheet, a dashboard badge — two things are listed individually, whatever the
aggregation:
- every contributor in CRITICAL;
- every DOWN or DEGRADED **component** (child asset, at any depth, in service).

A DOWN asset's health is never shown without its condition. No aggregate is shown without its
contributors when there are two or more.

### 6.6 Explanation (#61 AC 15, AC 19)

Each subject carries a driver line (S99–S106), for example:
- "Replaced 10 May 2023, 3 years 4 months ago"
- "Engine oil service is 96 days overdue"
- "Within the 14-day grace period"

The aggregate reads `<score> — <subject> <score>, …` in sort order. The asset-detail section closes with
S107.

### 6.7 Stored, exported, recomputed — RATIFIED (I-25, 2026-09-24)

Health is **computed at read time** by a pure `:core` function of the subjects, the asset's events, the
linked schedules' fresh states and season context, `T` and the zone, as status is (spec 1.2 inv. 18).
There is no `health_state` table. Archives carry configuration and inputs, never a value, and restore
reproduces the value (#61 AC 16).

### 6.8 Health and the rest

Health notifies nothing (I-28). A PAUSED or ARCHIVED linked schedule makes its subject NOT TRACKED. A
pause has no dated history, so it cannot freeze a past clock: after resume the subject reads as if the
schedule had never been paused, as its status does (`PauseSchedule.kt:13-15`). A hot tub uses a MANUAL
season instead.

---

## 7. The health clock (#61 AC 24–26)

### 7.1 The rule — RATIFIED (I-12, I-14, 2026-09-24); I-13 in principle, pull rule per Q-4

For a MAINTENANCE_OVERDUE subject, a day `d` is **counted** when all of these hold:
- (a) the schedule's policy phase on `d` is ACTIVE;
- (b) `d > A(d)`, where `A(d)` is §4.3's actionable date evaluated at `d` with today's configuration and postponement;
- (c) for a **MEDIUM** subject on an IN_SERVICE schedule only, `d` is on or after the latest cycle start at or before `T`. With no cycle start (YEAR_ROUND) there is no restriction.

`x` is the number of counted days up to `T`. The implementation counts by spans; its test oracle is this
day-by-day definition.

| Case | Consequence | arch. |
|---|---|---|
| MEDIUM, dormant at `T` | NOT TRACKED, not "nominal" | H.1c |
| MEDIUM, after START | lateness restarts; lateness before END is history | H.1b |
| ASSET / PART, IN_SERVICE | lateness freezes while dormant and carries across | H.1a |
| PRE_SERVICE | counting starts at the pre-service point, earlier or later than `R`; season start resets nothing | H.2a principle, #61 AC 26 |
| due moved out of a break | break days are not counted | H.3a |
| already late when a break opens | break days are counted | H.3c |
| a postponement | `A(d)` is the postponed actionable date for every `d`, so the clock **restarts from it**; earlier lateness is not retained (open item 6) | controller S-1 |
| a snooze | never touches it (device-local) | H |

### 7.2 Hot tub across END and START (MANUAL; H.1c + H.1b)

Water care: weekly FIXED schedule anchored Sat 2026-01-03, IN_SERVICE_AT_START offset 0, lead 1 day.
The subject "Water care" is MEDIUM, with thresholds entered as 2 / 7 / 14.

| Date | Fact | Schedule | Water care |
|---|---|---|---|
| Sat 11 Apr | completed | next `R` = Sat 18 Apr | 100 NOMINAL |
| Thu 16 Apr | END recorded | DORMANT → OUT OF SEASON, `Parked(null)` | NOT TRACKED |
| 1 Jul | — | still one occurrence (`R` 18 Apr), no notification | NOT TRACKED |
| Sat 10 Oct | START recorded | `R` < 10 Oct → `A` = 10 Oct → DUE | 100 (`x` 0) |
| Tue 13 Oct | still open | OVERDUE | `x` 3 → **92** NOMINAL |
| Thu 15 Oct | — | OVERDUE | `x` 5 → **76** NOMINAL |
| Sat 17 Oct | — | OVERDUE, still one occurrence | `x` 7 → **60** WARNING |
| Sat 24 Oct | — | OVERDUE | `x` 14 → **25** CRITICAL |
| Tue 13 Oct (alternative) | completed | next `R` = Sat 17 Oct | 100 |

**Contrast (H.1a).** Suppose the 18 Apr occurrence were still open at an END on Wed 22 Apr, and Water
care were a PART subject rather than a medium. The END day is itself dormant (§3.1), so 3 days are
counted before it (19–21 Apr). On 17 Oct `x` = 3 + 7 = **10**, which scores 45 WARNING. As a medium,
Water care reads `x` 7 → 60. This timeline covers #14 AC 8, #60 AC 9 and #61 AC 18 and AC 25.

### 7.3 Pre-service: the snowblower and the mower (CALENDAR, PRE_SERVICE; Q-4)

**Snowblower.** Configuration:
- season 11-15 → 03-31 (wraps); break 12-01 → 02-28, inside the season;
- oil change every 2 years, FIXED, anchor 2024-12-20, last done 2024-11-10, so `O` = 2024-11-10 and `R` = 2026-12-20;
- margin entered as 14 (offset −14); lead 14;
- subject "Engine oil service", PART, thresholds entered as 14 / 45 / 120.

`R` falls in the season that starts 15 Nov 2026. The deadline, 1 Nov, is allowed, and `O` < 1 Nov, so
`A` = **1 Nov 2026** (BEFORE_SEASON). Missed pre-service work degrades health before the season, and the
season start excuses nothing (H.2a's principle).

| Date | Schedule | Notification | Health |
|---|---|---|---|
| 18 Oct | DUE SOON | first-entry DUE SOON | 100 |
| 1 Nov | DUE | yes | 100 |
| 2–30 Nov | OVERDUE | every 3 days (spec 1.2 D-5) | 15 Nov (`x` 14) 100; 30 Nov (`x` 29) 81 |
| 1 Dec – 28 Feb | OVERDUE, `quiet` | none (H.3c) | 16 Dec (`x` 45) **60** WARNING; 20 Jan (`x` 80) 44 |
| 1 Mar 2027 | OVERDUE | resumes | (`x` 120) **25** CRITICAL |
| 15 May 2027 | OVERDUE | yes | (`x` 195) 0 |

Had the work been done on 28 Oct, the next `R` would be 20 Dec 2028.

**Mower.** Season 04-15 → 10-31, with the same break (12-01 → 02-28), which here falls in the
off-season. Yearly oil change, margin 14, lead 14, the same subject and thresholds.
- **`R` = 5 Nov 2026.** Allowed, and before the 1 Apr 2027 deadline, so `A` = 5 Nov. The owner's autumn habit is unchanged.
- **`R` = 12 Jan 2027** (a year after a mid-winter change). It falls in the break, and the allowed window 1 Mar – 1 Apr follows it, so `A` = **1 Mar 2027** (AFTER_BREAK; #60 AC 2, Q-4):

| Date | Schedule | Health |
|---|---|---|
| 29 Dec 2026 – 28 Feb 2027 | **DEFERRED**, S85 "until 1 Mar" | 100 (break days not counted) |
| 1 Mar | DUE | 100 |
| 15 Mar | OVERDUE | (`x` 14) 100 |
| 15 Apr | OVERDUE; the season starts and resets nothing | (`x` 45) **60** WARNING |
| 29 Jun | OVERDUE | (`x` 120) **25** CRITICAL |

- **A break that consumes the window.** Had this mower's break run 12-01 → 04-05, no allowed day would lie between 12 Jan and the 1 Apr deadline. The point would be **30 Nov 2026**, the last allowed day before the break (BEFORE_BREAK), because the occurrence opened before it.
- **An owner who chooses IN_SERVICE_AT_START** for the same mower sees OUT OF SEASON until 15 Apr and DUE on 15 Apr (#60 AC 3).

### 7.4 A break (YEAR_ROUND generator, IN_SERVICE; H.3a and H.3c)

Configuration:
- YEAR_ROUND; break 12-01 → 02-28;
- engine service every 6 months, FIXED, anchor 2025-12-20, "After the maintenance break" (IN_SERVICE_AT_START, offset 0);
- lead 14; subject "Engine oil service", PART, thresholds 14 / 45 / 120.

The schedule was created on 5 Jan 2026, so the pin (from `rule_changed_at`) puts its first `R` at
20 Jun 2026. The anchor's own date, 20 Dec 2025, is already past. Condition is DEGRADED since 30 Aug,
"Reduced output under load", independent of everything below.

| Date | Occurrence | Schedule | Health |
|---|---|---|---|
| 20 Jun 2026 | `R` = `A` = 20 Jun | DUE | 100 |
| 4 Aug | open | OVERDUE | (`x` 45) **60** WARNING |
| 24 Sep | open | OVERDUE | (`x` 96) 37 WARNING |
| 18 Oct | open | OVERDUE | (`x` 120) **25** CRITICAL |
| 20 Oct | completed | next `R` = 20 Dec, inside the break → `A` = **1 Mar 2027** (AFTER_BREAK) | 100 |
| 6 Dec – 28 Feb | open | **DEFERRED**, S85, `Parked(1 Mar)` | 100 (H.3a) |
| 1 Mar 2027 | open | DUE | 100 |
| 15 Apr 2027 | open | OVERDUE | (`x` 45) 60 WARNING |

**The H.3c branch.** Had the 20 Jun occurrence stayed open, on 1 Dec (`x` 164) it would read 11
CRITICAL. It would stay OVERDUE and `quiet` through the break, and reach 0 on 1 Jan 2027 (`x` 195).

---

## 8. Data model, migration, backup format 8, merge

### 8.1 Room schema 7 → 8

| Order | Table | Change |
|---|---|---|
| 1 | `asset` | `ADD COLUMN` ×5: `season_mode TEXT NOT NULL DEFAULT 'YEAR_ROUND'`, `blackout_start_mmdd`, `blackout_end_mmdd`, `health_aggregation TEXT NOT NULL DEFAULT 'WORST'`, `health_primary_subject_id`. Then `season_mode = 'CALENDAR'` where both `MM-DD` are set. `updated_at` untouched |
| 2 | `maintenance_schedule` | **12-step recreate**, RATIFIED (I-24, 2026-09-24). Drop the three season columns. Add `service_policy TEXT NOT NULL`, `policy_offset_days INTEGER`, and `rule_changed_at INTEGER NOT NULL` seeded from `updated_at` (D-28). Copy rows through §4.1's mapping. Keep the four FKs (asset CASCADE, group CASCADE, measurement_definition RESTRICT, event_profile SET NULL) and every index from `8.json`. `updated_at` untouched |
| 3 | `schedule_state` | recreate (derived, never exported): `season_active` becomes `policy_phase`, plus `actionable_due_on` (the new sort-key index), `policy_reason` and `quiet`. Created empty and derived in memory until first persisted (§4.7) |
| 4 | `asset_season_activation`, `asset_condition`, `health_subject` | `CREATE TABLE` + indices. `health_subject` comes after step 2, because its FK names the recreated table |

The recreate follows the 2→3 and 3→4 precedent: Room turns `foreign_keys` off for `migrate` and runs
`foreign_key_check` afterwards (`Migrations.kt:96-97, 165-166`). Dropping the parent therefore cascades
nothing and nulls nothing in `schedule_provider`, `occurrence_closure`, `schedule_state`,
`schedule_local_delivery` or `asset_event.schedule_id`. Unread tombstones were rejected because the old
columns carry two contradictory published meanings (arch. §B row 3).

### 8.2 Lossless migration

The migration writes:
- CALENDAR on an asset if and only if both `MM-DD` were set, otherwise YEAR_ROUND (review S-9);
- no break on any asset;
- the §4.1 mapping on every schedule;
- `rule_changed_at = updated_at`.

It moves no `updated_at`. On real data every schedule becomes CONTINUOUS with a null offset (arch. §C).
A format-7 export taken **before** the upgrade therefore merges into the upgraded app as **IDENTICAL for
every row** (§12.4).

### 8.3 Backup format 8

`FORMAT_VERSION` goes from 7 to 8. Three new lists are empty by default, so formats ≤7 decode:
`seasonActivations`, `assetConditions`, `healthSubjects`. Each list is sorted by `id`, validated by
`toDomain()` in the eager pass, and refused if its `assetId` is absent from the archive's assets.

**Changed DTOs:**
- **`AssetDto`** gains `seasonMode`, the break pair, `healthAggregation` and `healthPrimarySubjectId`. A format ≤7 asset decodes as CALENDAR if and only if both `MM-DD` are set, with no break and WORST.
- **`MaintenanceScheduleDto`** gains `servicePolicy`, `policyOffsetDays` and `ruleChangedAt`. A format ≤7 schedule decodes with `ruleChangedAt = updatedAt`.
- **The three old season fields** become decode-only: mapped by §4.1 when `formatVersion ≤ 7`, `BackupCorrupt` when present in a format-8 archive, and never encoded.

`counts` gains three keys. The `external_link` tombstones stay byte-for-byte (arch. §D.6). No derived
value is exported: not `schedule_state`, status, phase or health (spec 1.2 inv. 64). 1.4 reads formats
1–8; 1.3.x refuses format 8 with `BackupNewerFormat` before reading a row.

### 8.4 Merge tables 12–14

The new tables are appended after `REFERENCES`, the 1.3 precedent (`MergePlan.kt:28-36`). Every row
points only at earlier tables.

| # | Table | Identity | Reasons (**new**) |
|---|---|---|---|
| 12 | `SEASON_ACTIVATIONS` | row id | `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` (asset) |
| 13 | `CONDITIONS` | row id | `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` (asset) |
| 14 | `HEALTH_SUBJECTS` | row id, plus non-archived `schedule_id` | `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` (asset, schedule); **`HEALTH_SUBJECT_SCHEDULE_HELD_BY_A_LOCAL_ROW`** on a `SKIPPED`; **`HEALTH_SUBJECT_SCHEDULE_DUPLICATED_IN_ARCHIVE`** |

- **Facts never conflict with each other.** Tables 12 and 13 are immutable, with no `updated_at`:
  - a re-import is `IDENTICAL`;
  - two phones' rows are two inserts;
  - the latest-row state machine absorbs a duplicated START;
  - a second identity would be wrong for conditions, because two transitions in a day are legitimate;
  - soft event and profile links are never owners.
- **Toggling never touches the asset row**, so an asset re-imports `IDENTICAL` after any number of
  STARTs, ENDs and conditions. Mode, break and health policy are configuration, and a divergence is
  `CONTENT_DIFFERS`.
- **The one second identity declines rather than conflicts**, for 1.3's D-18 reason. The local subject
  stands, and the skip shows as a count.
- There is still no `UPDATE` verdict, and one conflict writes nothing. After apply, every schedule is
  rebuilt (spec 1.2 §3.3).
- **#44 seam:** tables 12 and 13 stay append-only. #44's asset-ID remapping must rewrite `asset_id` in
  all three new tables.

---

## 9. API and MCP

### 9.1 Routes (API version stays 1)

| method | path | body | success |
|---|---|---|---|
| `GET` | `/v1/assets/{id}/season` | — | 200 `{seasonMode, seasonStartMmdd, seasonEndMmdd, seasonPhase, nextBoundaryOn, blackoutStartMmdd, blackoutEndMmdd, inBreak, activations, computedForOn}` |
| `POST` | `/v1/assets/{id}/season` | `{action, occurredOn?, eventId?}` | 201 `{activation, season}` |
| `POST` | `/v1/assets/{id}/season-mode` | `{seasonMode, seasonStartMmdd?, seasonEndMmdd?, manualPhase?}` | 200 `{asset, season}` |
| `POST` | `/v1/assets/{id}/maintenance-break` | `{blackoutStartMmdd, blackoutEndMmdd}` (both null clears) | 200 `{asset, season}` |
| `POST` | `/v1/assets/{id}/health-policy` | `{healthAggregation, healthPrimarySubjectId?}` | 200 `{asset}` |
| `GET` | `/v1/assets/{id}/conditions` | — | 200 `{conditions (oldest first), current}` |
| `POST` | `/v1/assets/{id}/conditions` | `{condition, occurredOn?, occurredTime?, tzId, reason?, eventId?}` | 201 `{condition, current}` |
| `GET` | `/v1/assets/{id}/health` | — | 200 `{assetId, computedForOn, condition, aggregation, aggregate, subjects, critical, components}` |
| `GET` | `/v1/assets/{id}/health-subjects` | — | 200 `{subjects}`, archived included |
| `POST` | `/v1/health-subjects` | subject command | 201 `{subject}` |
| `GET`/`PATCH` | `/v1/health-subjects/{id}` | — / subject command (full replace; `assetId` unknown) | 200 |
| `POST` | `/v1/health-subjects/{id}/archive` | `{archived}` | 200 `{subject}` |
| `GET` | `/v1/attention` | — | 200 `{items}` (below) |

**Condition is not an asset-command field** (#61 AC 9, arch. §E). **The asset command keeps 1.3's exact
shape**; its season pair is a compatibility input (§3.2).

**Schedule command.** It gains `servicePolicy` and `policyOffsetDays`. It keeps `seasonBehavior`,
`seasonReentry` and `seasonReentryOffsetDays` as deprecated inputs (§9.4). Responses carry those three as
a **derived compatibility projection** — §4.1 reversed, all null for PRE_SERVICE — outside `BackupData`,
as `ScheduleStateDto` is. `ruleChangedAt` is response-only.

**Derived state.** `ScheduleStateDto` gains `actionableDueOn`, `policyReason`, `policyPhase` and
`quiet`. It keeps `effectiveDueOn` (1.2 meaning) and `seasonActive` (`policyPhase == ACTIVE`). The two
phase names differ on purpose: `seasonPhase` is the asset's, `policyPhase` the schedule's (review N-1).

**`/v1/due`.** Items gain `actionableDueOn`, `policyReason` and `quiet`. `status` may be `DEFERRED` —
the one response meaning that grows.

**`/v1/attention` item.** Every field is present, `null` where not applicable (review S-3):

```
{kind ("CONDITION"|"HEALTH"), section ("ATTENTION"|"UPCOMING"), assetId, parentAssetId,
 condition, reason, occurredOn, healthSubjectId, subjectName, band, score, rank}
```

Rows cover in-service assets and components (C1 bounds them, as `targetInService` does). `rank` is dense
in this order: DOWN, DEGRADED, independent CRITICAL health, then independent WARNING health. Within each
group rows are ordered by asset name, then id. The dashboard interleaves these rows with `/v1/due` by
§10.2's rule.

`/v1/status` reports schema and format 8 and three new count keys. import-merge reads formats 1–8.

### 9.2 What has no endpoint

Three things join the page's list:
- amending or deleting a condition or an activation row;
- deleting a health subject (archive it instead);
- writing a health value.

`ApiRouterTest.theDestructiveUseCasesHaveNoRoute` gains the paths.

### 9.3 Codes (`UPPER_SNAKE`)

**422** — fix the body:
- `LEGACY_WRITE_CANNOT_REPRESENT`, `LEGACY_AND_CURRENT_FIELDS_MIXED` (§9.4)
- `SEASON_POLICY_NEEDS_A_TIME_RULE`, `SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET` (restated), `POLICY_OFFSET_INVALID`
- `SEASON_WINDOW_REQUIRED`, `SEASON_WINDOW_FORBIDDEN`, `MANUAL_PHASE_REQUIRED`, `MANUAL_PHASE_FORBIDDEN`, `SEASON_DATE_OUT_OF_RANGE`, `BLACKOUT_COVERS_THE_YEAR`
- `CONDITION_DATE_IN_FUTURE`, `CONDITION_REASON_TOO_LONG`, `FOREIGN_EVENT`
- `HEALTH_SUBJECT_NAME_REQUIRED`, `HEALTH_THRESHOLDS_INVALID` (including a missing threshold), `HEALTH_DRIVER_MISMATCH`, `FOREIGN_SCHEDULE`, `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`, `PROFILE_NOT_A_REPLACEMENT`, `HEALTH_WEIGHT_OUT_OF_RANGE`, `HEALTH_PRIMARY_INVALID`

**Malformed values.** A malformed `MM-DD`, `occurredTime` or `tzId` gets the shipped 422 validation shape
that the asset and event commands already use (`docs/api/v1.md:413`).

**409** — the store refuses the state (`docs/api/v1.md:425`):
- `SEASON_NOT_MANUAL`, `SEASON_ALREADY_STARTED`, `SEASON_ALREADY_ENDED`
- `SEASON_MODE_STRANDS_POLICY`, `BREAK_STRANDS_POLICY`, `PRE_SERVICE_NEEDS_DATES`
- `HEALTH_SCHEDULE_TAKEN`, `HEALTH_SUBJECT_IS_PRIMARY`

**404:** `NO_SUCH_HEALTH_SUBJECT`, and the shipped `no_such_asset`. The error envelope gains an optional
`field` member, used by `LEGACY_WRITE_CANNOT_REPRESENT`.

### 9.4 Compatibility: `/v1` stays whole (Q-9, replacing I-23)

**The schedule command has two forms, told apart by which keys are present.**
- A body carrying `servicePolicy` or `policyOffsetDays` is a **1.4-form** write.
- A body carrying neither is a **legacy-form** write. It keeps its 1.3 meaning, including v1's rule that an omitted field takes its default (`seasonBehavior` → IGNORE, `MaintenanceDtos.kt:254`).
- A body mixing the two forms is 422 `LEGACY_AND_CURRENT_FIELDS_MIXED`.
- Presence decides, not value: an explicit `null` counts as a key.

**The asset command has one form** (§3.2). Its season pair is the only compatibility input, because the
break and health policy moved to their own commands (D-4).

**One translation.** Legacy inputs are translated by §4.1's table and nothing else. There is no second
alias table.

**What an old body cannot represent is refused, never reset.** A legacy write is 422
`LEGACY_WRITE_CANNOT_REPRESENT`, with `field` naming the state, and writes nothing, in two cases:
- a schedule PATCH on a PRE_SERVICE schedule;
- an asset write that would change the season of a MANUAL asset.

FOLLOW_ASSET on a schedule with no time rule is refused as well, with `SEASON_POLICY_NEEDS_A_TIME_RULE`,
exactly as the 1.4 form is.

A legacy write whose translation keeps every 1.4-only value proceeds. IN_SERVICE_* is 1.2's
FOLLOW_ASSET, so it is representable. A create cannot clobber anything. The status is 422 because the
remedy is in the body (send the 1.4 form), which is v1's "a 422 always means fix the body". Breaks,
health links and health policy live in commands no 1.3 client sends, so no legacy write can reach them.

**Reads stay compatible.** Responses only gain fields and one status value. The derived triple lets a
1.3 client's read-overlay-write round-trip every legacy-representable schedule losslessly. For
PRE_SERVICE it reads nulls, and writing them back is refused loudly.

**No versioned write contract is needed**: the presence rule makes every case unambiguous. If a later
field could not follow it, the answer would be `/v2` writes, not a break in `/v1`.

**Clients** (review S-8):
- **A 1.3 MCP** keeps working on every representable row and gets a named 422 on the rest.
- **The 1.4 MCP** writes the 1.4 form:
  - `update_schedule` and `update_asset` stop enumerating fields (`mcp/server.py:1359-1395`) and overlay every command key the row reports;
  - a golden `docs/api/command-shapes.json` is asserted against the app's command DTOs (JVM) and the MCP's overlay keys (pytest);
  - write tools refuse while `/v1/status.schemaVersion < 8`;
  - `create_asset`, `create_component` (`server.py:475-524`) and `update_asset` keep the season pair as a documented CALENDAR/YEAR_ROUND input, and `_ASSET_NULLABLE_CLEARABLE` (`server.py:359-361`) keeps it;
  - `_SCHEDULE_NULLABLE_CLEARABLE` swaps the season fields for `policy_offset_days`;
  - the status list in the schedule docstring (`server.py:1168`) gains DEFERRED.
- **`tools/servicetag-schedules` needs no change.** It writes `seasonBehavior` (`apply.py:104`) and reads it back (`phone.py:107, 167`) through the derived triple. It accepts FOLLOW_ASSET manifests (`manifest.py:28, 278`), which §4.1 translates, and its group check (`plan.py:227-230`) matches the restated 422. The loaded Stage-B manifest therefore still re-plans IDENTICAL.
- **`tools/servicetag-bundle`** emits format ≤7 archives, which decode leniently.
- **Release timing.** The app and MCP are released together for convenience, not correctness.

### 9.5 MCP tools

Fourteen new tools follow the shipped conventions: unknown arguments are rejected, `null` means
unchanged, fields are cleared by `clear_fields`, and every error is a real `ToolError` carrying its code.

- Season: `get_season`, `start_season`, `end_season`, `set_season_mode`, `set_maintenance_break`
- Condition: `list_conditions`, `record_condition`
- Health: `get_health`, `set_health_policy`, `list_health_subjects`, `create_health_subject`, `update_health_subject`, `archive_health_subject`
- `list_attention`

`start_season`, `end_season` and `record_condition` record new facts and have no overlay, like
`close_round`. `create_schedule` and `update_schedule` take `service_policy` and `policy_offset_days`.
The README's "Forty-one" becomes fifty-five.

---

## 10. UI

The wording is in §10.7. There are no UI-driven journey tests (§12).

### 10.1 Scan — RATIFIED (I-26, 2026-09-24)

The sheet reached from a valid asset tag (#50) leads with **condition, before maintenance** (#61 AC 3).
From top to bottom:
1. The asset name and #49's placement caption, unchanged.
2. The condition block: word, icon, reason and "since".
3. The actions: "Mark operational" and "Change condition" for DOWN or DEGRADED, otherwise "Change condition".
4. **Every DOWN or DEGRADED component**, each with its line (S27).
5. **Every CRITICAL subject** (S109), and the aggregate line when it is WARNING or CRITICAL.
6. The ratified "Maintenance" section with D-18a's items, or "Nothing due".
7. "Open asset", always.

The aggregate alone never decides what is visible (#61 AC 23; review B-3).

**One predicate decides both routing and contents.** `scanSheetItems` becomes
`scanSheetContent(items, condition, components, subjects)` (`MaintenanceSheetViewModel.kt:114-166`). The
sheet opens when the content has any D-18a item, the asset is DOWN or DEGRADED, or a component is. Health
lines are **passengers**, like DUE SOON: they are listed whenever the sheet opens and never open it
alone (open item 8). Otherwise the scan opens asset detail as today, where the same lines appear.

The scan writes nothing (spec 1.2 inv. 57). "Change condition" offers the three options with helpers,
"What is wrong? (optional)" for DEGRADED and DOWN, "When did this change?" (today, past allowed) and
"Save condition". "Mark operational" is one tap plus S17. Cancel writes nothing.

### 10.2 Dashboard — RATIFIED (I-27, 2026-09-24); Q-5

`/v1/attention`'s rows join `DueItem`'s projection as a second kind of row, with no schedule behind it.

**ATTENTION**, in order:
1. **DOWN assets and components** (#61 AC 6);
2. the shipped schedule rows;
3. DEGRADED assets and components;
4. independent CRITICAL health subjects (AGE).

**UPCOMING** gains independent WARNING subjects after the DUE SOON rows. Health driven by overdue
maintenance never gets a row of its own: its badge rides its schedule's row, so nothing is counted twice.

**Components.** A component row is promoted to its rank with its parent named, extending spec 1.2 inv.
75 to rows with no schedule behind them. Only in-service assets and components appear.

**Filters.** Status chips (DEFERRED added) select schedule rows. Condition chips (Down, Degraded,
Operational, Not recorded) select rows by their asset's current condition. When only status chips are
active, asset rows are hidden. Category chips and search apply to both kinds, by asset. Condition is
never inferred from a status (#61).

### 10.3 Asset detail

The identity plate gains the condition badge (`AssetDetailScreen.kt:613-632`). Each component row gains
its condition badge. New sections:
- **Condition:** the current condition, "Change condition", and the history newest first (#61 AC 5).
- **Health:** critical lines and DOWN/DEGRADED components first, then the aggregate and contributors, closing with S107.
- **Season:** for CALENDAR, the window and next boundary; for MANUAL, the phase, "Start season"/"End season" with S42–S45, and the history; the break when one is set.

### 10.4 Editors

**Asset editor.** "Operating season" (Year-round / Same dates every year / Started and ended by hand),
dates for the second option, and S35 when switching to the third. "Maintenance break" with a toggle and
two dates, neither prefilled. "Health subjects" with "Add health subject" and "Combine health by". The
editor never requires a season or a break. Save runs the asset, season-mode, break and health-policy use
cases in one `uow.write`.

**Health subject editor.**
- Fields: Name (shipped), "What is it?", "What wears it down?", and the driver's schedule or its optional replacement quick action.
- Three **empty** threshold fields with driver-specific labels. Save stays disabled until all three are entered (S126).
- "Use a starting point" (S127–S130) fills them from a template only after the owner confirms S129. AGE offers no template.
- Weight appears only under WEIGHTED.

**Schedule editor.** "When should this maintenance be done?" appears only when the target asset has a
season or a break and the schedule has a time rule. Otherwise the schedule is CONTINUOUS silently.

| Asset | Options |
|---|---|
| CALENDAR | Before the season starts / When the season starts / Whenever it is due |
| MANUAL | When the season starts / Whenever it is due, plus Before the maintenance break when a break is set |
| YEAR_ROUND with a break | Before the maintenance break / After the maintenance break / Whenever it is due |

- "When the season starts" adds "Start counting from": AT_START with "Days after it starts" (0–365), or RESUME_CLAMPED.
- "Before…" adds "Days before it starts" (1–365), empty until entered (S83).
- Each option has its helper (S78–S82). The anchor warning (S76) and the past-the-end warning (S84) apply.
- Group targets show no question.

### 10.5 Maintenance tab

Schedule rows show the policy's why-line (S85–S91). The "Reminders" row stays reminder health.
RATIFIED (I-19, 2026-09-24): the code namespace `HealthScreen`, `HealthFinding` and `Severity` becomes
`ReminderHealth*`, with no visible change.

### 10.6 Accessibility — RATIFIED (I-15, I-18, 2026-09-24); Q-7

Every new state carries **position, word, icon and colour**, and colour only reinforces the other three
(D12 §5). There is no green/yellow/red dependence. Nominal stays **cool blue** (D12 §14).

| State | Word | Icon (proposed) | Family | Position |
|---|---|---|---|---|
| Operational | OPERATIONAL | `task_alt` | cool blue | plate |
| Degraded | DEGRADED | `trending_down` | warning/amber, own token distinct from Due and Due soon | ATTENTION, after due |
| Down | DOWN | `block` | error | ATTENTION, first |
| Not recorded | Condition not recorded | `radio_button_unchecked` | neutral | plate |
| Health | NOMINAL / WARNING / CRITICAL + score | `signal_cellular_alt` 3 / 2 / 1 bars | cool blue / warning amber / error | detail, badges, rows |
| Not tracked | NOT TRACKED | `signal_cellular_nodata` | neutral | detail |
| Deferred | DEFERRED | `hourglass_top` | season-inactive grey | Deferred section |
| In season | IN SEASON | `event_available` | cool blue | plate, season section |

The words avoid "healthy" and keep "service" out of the season UI (arch. §A.6). Exact token values are
contrast-checked in the design brief before ratification (open item 2).

### 10.7 Strings ledger (139, all **to ratify**)

`<…>` marks a substitution; "(re)" marks a shipped word re-ratified in a new role. Shipped words reused
unchanged in their own roles (Name, Cancel, Not now, Maintenance, Open asset) are not listed.

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
| S27 | component line | <component> <DOWN/DEGRADED> — <reason> |
| S28 | asset editor section | Operating season |
| S29 | mode (re) | Year-round |
| S30 | mode | Same dates every year |
| S31 | mode | Started and ended by hand |
| S32 | field (re) | Season starts |
| S33 | field (re) | Season ends |
| S34 | helper | The season may run across the new year, for example from October to April. |
| S35 | switch question | Is this asset in season right now? |
| S36 | option | In season |
| S37 | option (re) | Out of season |
| S38 | helper | You start and end the season yourself. Maintenance set to follow the season waits while it is ended. |
| S39 | phase word | IN SEASON |
| S40 | action | Start season |
| S41 | action | End season |
| S42 | dialog title | Start the season? |
| S43 | dialog body | Maintenance set to follow the season becomes active again from <date>. |
| S44 | dialog title | End the season? |
| S45 | dialog body | Maintenance set to follow the season waits until you start it again. Nothing is marked done. |
| S46 | history row | Season started |
| S47 | history row | Season ended |
| S48 | section | Season history |
| S49 | calendar line | Next season starts <date> |
| S50 | calendar line | Season ends <date> |
| S51 | offer title | Start the season now? |
| S52 | offer title | End the season now? |
| S53 | offer body | You logged <event title>. |
| S54 | refusal | Choose a date from <date> to today. |
| S55 | refusal | Some maintenance on this asset is set to be ready before its season. Change it first: <titles>. |
| S56 | refusal | The season is already running. |
| S57 | refusal | The season has already ended. |
| S58 | asset editor section | Maintenance break |
| S59 | toggle | No routine maintenance between two dates |
| S60 | field | Break starts |
| S61 | field | Break ends |
| S62 | helper | During the break, maintenance set to follow the season or to be ready before it is not made due. A date that falls in the break moves to an allowed day. |
| S63 | refusal | The break cannot cover the whole year. |
| S64 | refusal | Some maintenance on this asset is set to be ready before the break. Change it first: <titles>. |
| S65 | schedule question | When should this maintenance be done? |
| S66 | option | Before the season starts |
| S67 | option | When the season starts |
| S68 | option | Whenever it is due |
| S69 | option | Before the maintenance break |
| S70 | option | After the maintenance break |
| S71 | field | Days before it starts |
| S72 | field | Days after it starts |
| S73 | field | Start counting from |
| S74 | option | The season's start |
| S75 | option | Its own date, but not before the season starts |
| S76 | warning | The first due date is outside this asset's season, so it will wait for the season to start. |
| S77 | warning | This asset has no season or maintenance break to be ready before, so this is due whenever its date comes. |
| S78 | helper, before season | A date in the season becomes due before the season starts. A date in the maintenance break moves to an allowed day before the season. |
| S79 | helper, before break | A date in the maintenance break becomes due before the break starts. |
| S80 | helper, when season starts | This maintenance waits while the season is off and becomes active again when it starts. |
| S81 | helper, after break | A date in the maintenance break moves to the first day after it. |
| S82 | helper, whenever | The season and the maintenance break never change when this is due. |
| S83 | refusal | Enter the number of days. |
| S84 | warning | That is after the season ends, so this would never become due. |
| S85 | why-line | Held by the maintenance break until <date> |
| S86 | why-line | Made due before the season starts |
| S87 | why-line | Made due before the maintenance break |
| S88 | why-line | Moved from <date> because the season was not running |
| S89 | why-line | Out of season until <date> |
| S90 | why-line | Out of season until you start it |
| S91 | why-line | Reminders wait for the maintenance break to end |
| S92 | status word | DEFERRED |
| S93 | dashboard section, chip | Deferred |
| S94 | section | Health |
| S95 | band | NOMINAL |
| S96 | band | WARNING |
| S97 | band | CRITICAL |
| S98 | no value | NOT TRACKED |
| S99 | driver line | Replaced <date>, <age> ago |
| S100 | driver line | No replacement recorded yet |
| S101 | driver line | The replacement quick action was removed. |
| S102 | driver line | <schedule> is <n> days overdue |
| S103 | driver line | <schedule> is up to date |
| S104 | driver line | Within the <n>-day grace period |
| S105 | driver line | Not tracked while out of season |
| S106 | driver line | Not tracked while the schedule is paused |
| S107 | footer | Health is an estimate from dates and records, not a diagnosis. It never changes the condition. |
| S108 | aggregate | <score> — <subject> <score>, <subject> <score> |
| S109 | critical line | Critical: <subject> <score> |
| S110 | dashboard row | <subject> <BAND> |
| S111 | section | Health subjects |
| S112 | action | Add health subject |
| S113 | field | What is it? |
| S114 | option | The whole asset |
| S115 | option | A part |
| S116 | option | Something maintained, like water |
| S117 | field | What wears it down? |
| S118 | option | Age since replacement |
| S119 | option | Overdue maintenance |
| S120 | field | Replacement quick action |
| S121 | option | Any replacement |
| S122 | field | Maintenance schedule |
| S123 | fields, age | As new for (days) · Warning after (days) · Critical after (days) |
| S124 | fields, overdue | Grace period (days overdue) · Warning at (days overdue) · Critical at (days overdue) |
| S125 | refusal | Each number must be larger than the one before. |
| S126 | refusal | Enter all three numbers, or use a starting point. |
| S127 | action | Use a starting point |
| S128 | template names | Engine service: 14 / 45 / 120 days overdue · Water care: 2 / 7 / 14 days overdue |
| S129 | confirmation body | These are starting points, not safety limits. Check them for this equipment before saving. |
| S130 | confirm | Use these numbers |
| S131 | field | Combine health by |
| S132 | options | Worst subject · One subject · Average · Weighted average |
| S133 | field | Weight |
| S134 | field | Which subject? |
| S135 | refusal | That schedule already drives another subject. |
| S136 | actions | Archive subject · Restore subject |
| S137 | refusal | This is the subject asset health follows. Choose another way to combine health first. |
| S138 | fallback | The subject to follow is missing, so the worst subject is shown. |
| S139 | scan, empty | Nothing due |

There are 139 rows: 135 new, plus S29, S32, S33 and S37, which are re-ratified. S123, S124, S128, S132
and S136 each ratify their listed words as one set. The owner's string notes are applied:
- S38, S43, S45 and S80 never imply that all maintenance stops;
- S43 says "becomes active again", not "is due again";
- S62 and S76–S79 say "becomes due" or "is made due", never "the work is done".

Retired, with the owner's assent: "Pause with the asset's season", "Remind me year round", and "Off means
this asset is only in use between two dates each year." (`ScheduleEditScreen.kt:78-80`,
`AssetEditScreen.kt:247-268`).

---

## 11. Invariants

Numbering continues from spec 1.2 (1–80). §12.1 names a layer and a test for every invariant.

**Spec 1.2 invariants retired or amended:**

| 1.2 inv. | Now |
|---|---|
| 25 | Restated as 87: it names `rule_changed_at` and is enforced through SaveSchedule (#64) |
| 26 | Retired with `ScheduleStructuralTest.kt:71-94`; replaced by 84 and 104 |
| 27 | Restated as 106 |
| 10 | Stands for `effectiveDueOn` |
| 16 | Amended: `rebuild` also takes the season context |
| 17 | Kept by 105: reads derive in memory |
| 22 | Amended: INACTIVE_SEASON, PAUSED and DEFERRED never notify, and nothing notifies while `quiet` |
| 23 | Amended: season, activation and break boundaries may change status with no history change, and the between-rebuilds exception is gone |
| 47 | Amended: DEFERRED and quiet items arrive as `PARKED` |
| 62–64 | Extended to format 8 and health |

D5 §6's "nothing is stored at season end" and "MANUAL_STARTUP deferred" are retired, as is spec 1.2
D-4's `T`-independent sort key.

**Concepts.**
81. Only an explicit condition write inserts an `asset_condition` row; no health value, status, phase, event, completion or scan does.
82. Health writes nothing: no condition, lifecycle, schedule, policy, season or reminder state.
83. Condition changes nothing but its own history: no schedule is paused, no reminder withheld, no health value moved.
84. The policy never changes `computedDueOn`: for the same history, `rebuild` under any policy yields the same `computedDueOn` as under CONTINUOUS.
85. Every completion's `occurrence_on` is `computedDueOn`, never `actionableDueOn`, `effectiveDueOn` or a postponement.
86. No policy, phase, break or activation writes an event, a closure or a schedule column, or creates a second current occurrence.
87. `rule_changed_at` moves only when `ruleChanged` is true; `service_policy` and `policy_offset_days` are not rule fields; a policy-, title-, lead- or health-link-only edit leaves `rule_changed_at`, `computedDueOn`, `O` and `actionableDueOn` unchanged and clears no postponement.

**Season.**
88. `season_start_mmdd`/`season_end_mmdd` are both non-null if and only if `season_mode` is CALENDAR.
89. `asset_season_activation` is immutable: no `updated_at`, no UPDATE, no DELETE except the asset's CASCADE.
90. A MANUAL asset's phase at `d` is the latest row's action by `(occurred_on, created_at, id)` among rows with `occurred_on ≤ d`; rows are read only in MANUAL mode.
91. A local START on a started asset, an END on an ended one, and a date after today or before the latest row are refused and write nothing.
92. A switch into MANUAL writes exactly one activation row stating the phase, in the same transaction; `manualPhase` on any other change is refused.
93. No journal event changes a phase; the offer after one writes only when accepted.
94. Out-of-season time creates no occurrence and no backlog; re-entry leaves exactly one current occurrence.

**Policy.**
95. The policy acts on the time side only: a schedule with no time rule is CONTINUOUS and any other policy on it is refused; a meter threshold is never moved, deferred or re-entered, and only `quiet` withholds its notification.
96. CONTINUOUS: `actionableDueOn == postponedDueOn ?: computedDueOn`, whatever the season or break.
97. IN_SERVICE_AT_START moves a date to `s + offset` only when it is earlier than that; RESUME_CLAMPED yields `max(date, s)`; neither drags a date later than the cycle's end back to its start.
98. PRE_SERVICE keeps an allowed raw due on or before the deadline; moves a raw due inside the break to the first allowed day after it when that day is on or before the deadline; otherwise makes it actionable on the last allowed day on or before `min(R, deadline)`, and only when `O` is earlier than that day. No rule reads a category, template or name.
99. A manual START is never a pre-service boundary; PRE_SERVICE is refused on an asset with neither a calendar season nor a break, and nothing removes a window or break a PRE_SERVICE schedule relies on.
100. Season start never completes, resets or hides pre-service work: a missed point stays OVERDUE into and through the season.
101. A postponed date is re-entered and moved out of a break exactly as a raw due, is never moved earlier, and never reads OVERDUE on the first in-season day.
102. `quiet` ⇔ `service_policy ≠ CONTINUOUS` and `T` inside the asset's break; an item already late when the break opens is not moved, keeps its status and is `quiet`.
103. DEFERRED is decided after the worst-of fold from the time side alone; it never notifies and never counts as due; a crossed meter threshold is never DEFERRED.
104. No reminder code evaluates a season window, a break or a policy; subject states come from engine output only.
105. No read path writes `schedule_state` or any health materialisation; a stale or missing row is derived in memory; only the daily job, a recompute after a write, and an explicit rebuild persist.
106. A group-targeted schedule is CONTINUOUS only.

**Condition.**
107. `asset_condition` is immutable; current is the latest row by `(occurred_on, occurred_time nulls first, created_at, id)`; no column stores a current condition.
108. No UNKNOWN value is ever stored; an asset with no row reads "Condition not recorded".
109. A condition's or activation's `event_id` is never a foreign key and never an owner; a dangling link is tolerated.
110. Returning to OPERATIONAL inserts one row and leaves every earlier row byte-identical.

**Health.**
111. No health value is stored or exported; there is no `health_state` table and no health column.
112. A MAINTENANCE_OVERDUE value is a function of the actionable date through §7's counted days, never of `computedDueOn` alone, and no meter side contributes.
113. An AGE baseline is the latest qualifying REPLACEMENT event; a deleted baseline profile leaves the subject NOT TRACKED and never widens the filter.
114. A MEDIUM subject on an IN_SERVICE schedule is NOT TRACKED while dormant and counts only from the latest cycle start; other subjects freeze while dormant and carry.
115. Pre-service lateness counts from the pre-service point and is not reset by season start; break days count for an already-late item and not for a deferred one.
116. A postponement restarts the MAINTENANCE_OVERDUE clock from the postponed actionable date; a snooze never touches it.
117. `score` is non-increasing in `x`; WARNING begins exactly at `t2` and CRITICAL exactly at `t3`.
118. An untracked subject is excluded from aggregation and shown as NOT TRACKED, never as 100.
119. Every CRITICAL contributor and every DOWN or DEGRADED in-service component is shown individually on every surface that shows health, whatever the aggregation; a DOWN asset's health is never shown without its condition.
120. A schedule drives at most one non-archived subject; a subject never changes asset.
121. No threshold, pre-service margin or break date has a stored, API or editor default; a template fills fields only after explicit confirmation.

**Surfaces.**
122. A DOWN or DEGRADED in-service asset or component reaches ATTENTION with no schedule behind it; a component carries its parent's name.
123. The scan sheet opens only through `scanSheetContent`'s one answer; health lines never open it alone.

**Data and contract.**
124. A format ≤7 archive decodes: legacy fields map by §4.1, new lists are empty, modes derive from the window, `ruleChangedAt = updatedAt`; a format-8 archive carrying a legacy field is corrupt.
125. The 7→8 migration and the decoder agree row for row, touch no `updated_at`, and a pre-upgrade format-7 export of real-shaped data merges IDENTICAL.
126. Activations and conditions merge only as INSERT, IDENTICAL or CONTENT_DIFFERS by id; START, END and condition writes never change the asset row.
127. No route or tool amends or deletes a condition or an activation; condition is in no asset command.
128. A legacy-form write is translated only by §4.1 and never changes 1.4-only state: such a write is 422 `LEGACY_WRITE_CANNOT_REPRESENT` and writes nothing; a mixed body is 422; responses only gain fields and one status value.
129. No 1.4 table, column or route names an installed component or assembly, and nothing reads stock.

---

## 12. Test matrix

There are no `uiautomator`/`adb` journeys (planning policy, 2026-09-23), and no acceptance test waits on
a real-world delay: every date is an injected `T`.

### 12.1 Invariant → layer → test (review S-13)

| Invariants | Layer | Named test (what fails without the change) |
|---|---|---|
| 81–83, 86 | JVM | `CrossConceptWriteTest`: runs every 1.4 use case and API handler against a recording fake store and asserts each one's write set |
| 84, 85, 96 | JVM property | `PolicyInvarianceTest`: `rebuild` under each policy vs CONTINUOUS on the same history; completions stamp `computedDueOn` |
| 87 | JVM, **through SaveSchedule** | `SaveScheduleRuleFieldTest`: a title-only, policy-only and lead-only edit of a never-terminated FIXED schedule (anchor 10 Jan, created 1 Jan, edited 1 Mar) leaves `computedDueOn` at 10 Jan and `rule_changed_at` unmoved |
| 88, 92 | JVM | `SeasonModeCommandTest` (incl. `manualPhase` edges, END-after-END on re-entry) |
| 89, 127 | structural | the DAO has no update or delete for activations and conditions; `ApiRouterTest.theDestructiveUseCasesHaveNoRoute` |
| 90, 91, 93 | JVM | `ManualSeasonTest`; `SeasonEventOfferTest` (a SEASON_START completion writes no activation) |
| 94 | JVM property | no backlog across dormancy (CALENDAR and MANUAL) |
| 95, 103 | JVM | `MeterSidePolicyTest`: meter-only × every policy refused in both forms; a combined schedule with a held time side and a crossed meter reads DUE |
| 97–100 | JVM | `ServicePolicyEvaluatorTest`: each policy × mode; wrap and boundary days (#14 AC 6); the §7.3 snowblower, mower, window-consumed variant, and a monthly schedule under the guard; **#14 AC 4 literally** — CALENDAR 10-15 → 04-15, every 3 days, completed 13 Apr: INACTIVE_SEASON on 1 Jul, DUE on 15 Oct |
| 101 | JVM | `PostponementPolicyTest`: postponed into dormancy and into a break |
| 102 | JVM | `BreakTest`: defer, H.3c no-move, quiet on the meter side |
| 104 | structural | anchored grep: nothing under `reminders/` calls `Season.inSeason` or reads `MM-DD` or break columns |
| 105 | JVM + structural | `ReadPathsWriteNothingTest`: the due model, scan offer, attention and health handlers over a stale row perform no upsert |
| 106 | JVM | group-target refusals, both forms |
| 107–110 | JVM | `ConditionHistoryTest`; the merge suite for 109 |
| 111 | structural | no health entity or column; `BackupData` has no health value |
| 112–118 | JVM | `HealthEngineTest`: the score at `t1`, `t2 − 1`, `t2`, `t3`, the tail and zero; the counted days against a day-loop oracle (property); the §7 timelines and §12.5 fixtures as literal values; the baseline profile deleted |
| 119, 122, 123 | JVM + Compose | `ScanSheetContentTest`: AVERAGE NOMINAL plus one CRITICAL shows S109, a DOWN child is listed, health alone opens nothing; `AttentionReadModelTest`: a DOWN component with nothing due, lifecycle bound, filters |
| 120, 121 | JVM + Compose | subject save refusals; a subject command without thresholds is refused; the editor's Save is disabled until entered or confirmed |
| 124–126 | JVM | backup round trip; the ≤7 decode of every mapping row; merge verdicts and the SKIPPED arm |
| 125 | structural | `MigrationTest` 7→8 on a real-shaped fixture: values mapped, `updated_at` and `rule_changed_at` as specified, `foreign_key_check` clean, `8.json` |
| 128 | JVM + pytest | `LegacyFormTest`: every mapping row via the API; each LEGACY_WRITE case; a mixed body; a 1.3-shaped overlay round trip is lossless; the MCP golden shapes; the loader re-plans IDENTICAL against a 1.4 row |
| 129 | structural | anchored grep for assembly/installed-component names and stock reads in 1.4 sources |

### 12.2 Compose instrumented

These cases run in-process, with no journeys:
- **Scan sheet:** the condition-first layouts and the component and critical lines (B-3). Confirm writes one row; Cancel writes none.
- **Change-condition sheet:** the date bound.
- **Asset detail:** the sections per mode and the Start/End dialogs.
- **Editors:**
  - the season switch asks S35;
  - the policy question is hidden without a season, a break or a time rule;
  - the offset fields are bounded and empty;
  - templates need confirmation.
- **Dashboard:** row order, component promotion, the Deferred section and the chips.
- **Grayscale:** no two new states share a (word, icon) pair, and every state has text and a content description.

### 12.3 Boundary and release

**No new external-boundary test.** 1.4 adds no UID, intent or grant boundary (owner: "Kept"), and the
manifest contract tests are unchanged. The **one signed-APK upgrade smoke** is `docs/release-proofs.md`
R7: 1.4.0 installed over 1.3.0 in place. Its extra expectations:
- table counts are equal;
- CALENDAR if and only if both `MM-DD` were set;
- no break on any asset;
- every schedule mapped by §4.1;
- the new lists are empty;
- the pre-upgrade format-7 export merge-plans IDENTICAL.

The read of arch. §J (the phone's windows and parentage) is an investigation, not part of the proof.

### 12.4 The five fixtures (fictional; every value entered, nothing defaulted)

| Fixture | Configuration | Expected at `T` = 2026-09-24 |
|---|---|---|
| F1 UPS (#61's master-bedroom role) | DOWN since 20 Sep, "Battery failed — replacement pending"; "Battery age" PART, AGE 365 / 1095 / 1460, baseline REPLACEMENT 2022-06-01 | `x` 1576 → **18 CRITICAL**; a scan opens the sheet with nothing due; a REPLACEMENT on 27 Sep → 100 and the offer; accepting gives OPERATIONAL, and the DOWN row is kept |
| F2 battery pack | "Battery age" ASSET with the same thresholds, baseline profile "Battery replaced", REPLACEMENT 2023-05-10; an INSPECTION load-test profile | `x` 1233 → **47 WARNING** (WARNING from 9 May 2026, CRITICAL from 9 May 2027); a load test changes nothing; deleting the quick action → NOT TRACKED |
| F3 generator | §7.4 | 37 WARNING, DEGRADED, OVERDUE |
| F4 snowblower and mower | §7.3 | snowblower OK (DUE SOON from 18 Oct); mower (`R` 5 Nov) OK; the January mower is DEFERRED from 29 Dec and DUE on 1 Mar |
| F5 hot tub | §7.2 | OUT OF SEASON, Water care NOT TRACKED |

---

## 13. Acceptance-criteria trace

| Issue AC | Section |
|---|---|
| #14-1 winter hot tub reminds in season only | 3.5, 4.3, 4.7, 7.2 |
| #14-2 year-round pool keeps schedules active | 3.1, 4.1 |
| #14-3 mower startup, storage and season work | 4.1, 4.4, 7.3 |
| #14-4 INACTIVE_SEASON in July, DUE on 15 Oct | 3.5, 4.3, 12.1 (literal case) |
| #14-5 one occurrence at re-entry | 3.5, inv. 94 |
| #14-6 wrap-around and boundary days | 3.1, 12.1 |
| #14-7 calendar or manual activation | 3.1–3.4 |
| #14-8 manual season, no fabricated dates, no backlog | 3.3, 3.4, 4.3 (postponement), 7.2 |
| #60-1 snowblower before winter, not in the break | 4.3, 4.4, 7.3 |
| #60-2 mower avoids winter prompts, surfaces before the season | 4.3, 7.3 (spring window) |
| #60-3 another user chooses re-entry | 4.1, 7.3 |
| #60-4 continuous with no suppression | 4.1, inv. 96 |
| #60-5 season and service window distinct | 2, 3.2, 4.4 (own commands) |
| #60-6 no fabricated events or backlog | inv. 84–87, 94 |
| #60-7 providers consume one result | 4.7, inv. 104–105 |
| #60-8 Stage-B examples reviewed | arch. §C, 7, 12.4 |
| #60-9 manual hot tub freezes and resumes | 3.3, 4.3, 7.2 |
| #60-10 in-service and pre-service first class | 4.1 |
| #60-11 readiness before a calendar season; hot tub only while active | 4.3, 7.2, 7.3 |
| #61-1 explicit condition independent of schedules | 5, inv. 81, 83 |
| #61-2 UPS DOWN with a reason, no fake schedule | 5.2, 12.4 F1 |
| #61-3 scan shows DOWN first, with an action | 10.1 |
| #61-4 scan never changes condition | 10.1, inv. 81 |
| #61-5 the prior DOWN is kept | 5.1, inv. 110 |
| #61-6 DOWN with nothing due in attention | 10.2, inv. 122 (components included) |
| #61-7 OPERATIONAL with OVERDUE | 5.5 |
| #61-8 backup, restore, merge keep the history | 8.3, 8.4 |
| #61-9 API/MCP read and change with audit | 9.1, 9.5, inv. 127 |
| #61-10 grayscale | 10.6, 12.2 |
| #61-11 configurable health independent of condition | 6, inv. 82 |
| #61-12 battery ageing from a replacement baseline | 6.4, 12.4 F1–F2 |
| #61-13 grace, warning, critical | 6.3, 7 |
| #61-14 health never changes condition | 2, inv. 82 |
| #61-15 UI says why, not colour alone | 6.6, 10.6 |
| #61-16 configuration and inputs survive; result reproducible | 6.7, 8.3, 9.1 |
| #61-17 whole-asset or medium subject | 6.1 |
| #61-18 hot tub degrades and recovers | 7.2 |
| #61-19 driver named | 6.6 |
| #61-20 a schedule drives one subject | 6.1, inv. 120 |
| #61-21 primary or aggregate | 6.5 |
| #61-22 primary, average/weighted, worst | 6.5 |
| #61-23 contributors shown; nothing hidden | 6.5, 10.1, inv. 119 |
| #61-24 actionable date, not raw | 6.2, 7.1, inv. 112 |
| #61-25 frozen hot tub accumulates nothing | 7.1, 7.2 |
| #61-26 pre-service degrades before service | 7.3, inv. 115 |

---

## 14. Dispositions

### 14.1 arch. §I recommendations (owner, 2026-09-24)

| I | Adopted | Disposition | § |
|---|---|---|---|
| I-1 | Season mode with `MM-DD` kept for CALENDAR | RATIFIED | 3.1 |
| I-2 | Immutable activation table, optional event link | RATIFIED | 3.3 |
| I-3 | A switch to MANUAL states the phase | RATIFIED | 3.4 |
| I-4 | A repeated START/END is 409 | RATIFIED | 3.3 |
| I-5 | One policy enum; FOLLOW_ASSET → IN_SERVICE_AT_START offset 0 | RATIFIED | 4.1 |
| I-6 | Both re-entry variants | RATIFIED | 4.1 |
| I-7 | Asset-level break | RATIFIED | 4.4 |
| I-8 | A new status for break-deferred work | RATIFIED | 4.5 |
| I-9 | The break does not apply to CONTINUOUS | RATIFIED | 4.4 |
| I-10 | A separate signed offset | RATIFIED | 4.2 |
| I-11 | Groups continuous | RATIFIED | 4.6 |
| I-12 | H.1c + H.1b for a medium | RATIFIED | 7 |
| I-13 | H.2a with pull rule (i) | principle RATIFIED; pull rule REVISED by Q-4 | 4.3, 7.3 |
| I-14 | Break per policy; H.3c always | RATIFIED | 4.4, 7.4 |
| I-15 | D12 blue-normal palette | RATIFIED; families per Q-7 | 10.6 |
| I-16 | A score with stored thresholds and bands | RATIFIED; values per Q-1 | 6.3 |
| I-17 | Default WORST; contributors listed | RATIFIED | 6.5 |
| I-18 | Fresh words; no "service" in season UI | RATIFIED | 10.6–10.7 |
| I-19 | `ReminderHealth*` rename | RATIFIED | 10.5 |
| I-20 | An explicit REPLACEMENT baseline | RATIFIED | 6.4 |
| I-21 | Reason text plus event link | RATIFIED | 5.2 |
| I-22 | Immutable history, no UNKNOWN | RATIFIED | 5.1 |
| I-23 | Lockstep + 422 retirement code | **REPLACED** by Q-9 | 9.4 |
| I-24 | Recreate `maintenance_schedule` | RATIFIED | 8.1 |
| I-25 | Health recomputed, never exported | RATIFIED | 6.7 |
| I-26 | DOWN/DEGRADED route a scan | RATIFIED | 10.1 |
| I-27 | DOWN first; DEGRADED after due | RATIFIED | 10.2 |
| I-28 | No condition or health notifications | RATIFIED | 5.5, 6.8 |
| I-29 | "Mark operational?" offered only | RATIFIED | 5.4 |

### 14.2 Review findings (spec-review-rev1.md, with the controller's rulings)

**Taken as asked or as ruled:**
- **B-1:** time side only; meter-only is CONTINUOUS and refused otherwise (`SEASON_POLICY_NEEDS_A_TIME_RULE`, generalised from the reviewer's PRE_SERVICE-only code per the controller); DEFERRED after the fold; `quiet` defined.
- **B-2:** `rule_changed_at`; the 1.2 defect is filed as #64 and fixed here.
- **B-3:** critical and component lines on the scan sheet.
- **S-2:** reads derive in memory.
- **S-3, S-11:** components in attention, on the scan sheet and in health.
- **S-4:** open item 5.
- **S-5:** 3 days, `x` 10.
- **S-6:** the break and health policy move to their own commands.
- **S-7:** S64, S83, S137; the offset bounds; Name is shipped.
- **S-8:** the call sites are listed.
- **S-9:** the migration expectation.
- **S-10:** superseded by Q-9; `effectiveDueOn` keeps its meaning, and DEFERRED is the one added value.
- **S-13:** §12.1.
- **N-1 to N-12, N-14, N-16:** taken.

**Taken with a modification:**
- **S-1.** The controller's wording says prior lateness "survives only as history (the journal shows the postponement)". In fact a postponement writes only `postponed_due_on` and no journal event (`PostponeSchedule.kt`). The spec therefore states that earlier lateness is not retained (open item 6) rather than claim a journal record that does not exist.
- **S-12.** A soft link was chosen over a 409 on profile deletion, because deleting a quick action is always allowed by design (`DeleteProfile.kt`).
- **N-5.** A bound (0–365) plus an editor warning, not a refusal, because "past the season's end" depends on a window that can change.

**Not applied:**
- **N-13:** moot — `FIELD_REQUIRED` is gone under Q-9, and the MCP golden shapes cover future skew.
- **N-15:** the fixture keeps #61's public "master-bedroom" role name, as the brief lists it, under the neutral label F1.

---

## 15. Out of scope, seams, version

### 15.1 Seams

- **#47 installed components:** no row, identity or column (Q-8, inv. 129). Child assets are today's
  components and are surfaced (§6.5, §10.2). #47 adds the assembly binding.
- **#44 merge:** the fact tables are append-only, and asset-ID remapping must rewrite `asset_id` in the
  three new tables.
- **#15 stock:** no stock read feeds health.
- **#45 replication and Todoist (#9, PARKED):** no provider id enters these tables. `Parked` now also
  carries break suppression.
- **#27:** unchanged in behaviour; only renamed.
- **#64:** fixed by D-28.
- **Documents the plan's docs brief edits:**
  - D5 §6 and §10.4: the AT_START reading, manual activation, the retired statements;
  - D12 §5;
  - `docs/api/v1.md`: the new routes and codes, a "Deprecated inputs" section, the new response fields, DEFERRED, "What has no endpoint", and the sub-resource and table counts;
  - the MCP README;
  - `docs/versioning.md`.

### 15.2 Version — D-27

**`versionName` 1.4.0, `versionCode` 16, a MINOR.**

- **Capability.** A new user-facing capability is a MINOR (`docs/versioning.md`).
- **Data.** Room schema 8 and backup format 8 are forward-only: 1.4 reads formats 1–8, and 1.3.x refuses
  format 8 through `BackupNewerFormat`. That is the documented condition for a MINOR format bump.
- **The loopback API stays `/v1` and is backward compatible** (Q-9):
  - every 1.3 request keeps its 1.3 meaning on every row a 1.3 body can describe;
  - the deprecated season inputs are translated by the same mapping the migration uses;
  - a write that could only erase state a 1.3 client cannot see is refused with a named code rather than reinterpreted;
  - responses only gain fields and one status value, and `effectiveDueOn` keeps its documented meaning.
- **Nothing that worked stops working.** No archive stops importing and no stored data becomes
  unreadable, so no "breaking" row of the versioning table applies.
