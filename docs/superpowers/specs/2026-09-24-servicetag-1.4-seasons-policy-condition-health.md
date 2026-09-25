# ServiceTag 1.4 — seasons, service policy, operational condition and derived health: DESIGN SPEC (rev2.2)

## 0. Status and the owner's record

**Status: revision 2.2, 2026-09-24 — RATIFIED FOR PLANNING.** The owner approved the architecture, ratified strings S1–S142 as written, and ruled every gate item. Nothing in this document is open for the owner; S143 was ratified as written later the same day (O-9).

The programme covers three issues:
- **#14:** operating seasons, with calendar and manual activation.
- **#60:** maintenance service policy and a maintenance break.
- **#61:** operational condition and derived health.

**Inputs.**
- The owner's rulings, including "Gate rulings on spec rev2.1": `.superpowers/sdd/2026-09-24-servicetag-62-test-architecture/owner-rulings-2026-09-24.md`.
- The independent review and its consolidated re-review: `…/spec-review-rev1.md`.
- The archaeology: `docs/superpowers/specs/2026-09-24-servicetag-1.4-archaeology.md` ("arch.").
- "Spec 1.2" means `docs/superpowers/specs/2026-09-22-servicetag-1.2-operational-maintenance.md`.

Next step: the 1.4.0 master plan and briefs (versionCode 16, schema 8, format 8, including #64 and the app + MCP + schedules-loader compatibility work). Every example is fictional.

**The owner's rulings (2026-09-24).** Q-rows answer rev1's questions; O-rows are the gate rulings on rev2.1.

| # | Ruling | Where |
|---|---|---|
| Q-1 | No household values: break dates, margins and thresholds are owner configuration; 14/45/120 and 2/7/14 are confirm-to-use templates | §4.2, §6.3 |
| Q-2 | Meter-driven health is out; the seam is kept | §6.2 |
| Q-3 | The opened-before guard is kept, at any interval | §4.3 |
| Q-4 | The season start is the objective and the break a constraint; the pre-season window is used first (RB-1 rule) | §4.3 |
| Q-5 | Independent health rows; critical contributors are always visible | §6.5, §10.2 |
| Q-6 | A manual START is never a deadline; the hot tub is IN_SERVICE | §3.1, §4.3 |
| Q-7 | D12 colour families | §10.6 |
| Q-8 | No reserved #47 column | §6.1 |
| Q-9 | Stay on `/v1` and 1.4.0; old fields are compatibility inputs; writes an old body cannot represent are refused loudly | §9.3 |
| O-1 | S1–S142 ratified as written | §10.7 |
| O-2 | Families confirmed: cool blue nominal, warning/amber with a distinct DEGRADED token, error for DOWN/critical; colour only reinforces; literal token values are design-token choices under the existing contrast requirements | §10.6 |
| O-3 | On YEAR_ROUND with a break, the break start is PRE_SERVICE's knowable boundary; RS-4 is 409, and the break refusal has the same shape | §3.2, §4.3, §4.4, inv. 99 |
| O-4 | Only when no allowed pre-season day remains is work pulled to the last suitable allowed point before the break, possibly in the previous season; any remaining window wins | §4.3, inv. 98 |
| O-5 | AT_START moves a raw date only when it is earlier than `seasonStart + offset`; a future manual START is never a PRE_SERVICE deadline, and a recorded START is the real cycle start | §3.1, §4.3 |
| O-6 | Snooze delays the reminder and never improves health; Postpone reassesses this occurrence's canonical actionable due date and may restart maintenance-overdue health; no postponement journal in 1.4 | §7.1, §9.1, §10.1, inv. 131 |
| O-7 | Meter-only schedules: phase-only IN_SERVICE and quiet, no date effect, no PRE_SERVICE, no non-zero offset, a crossed threshold stays genuinely DUE | §4.1, inv. 95 |
| O-8 | CRITICAL health alone never opens the scan sheet; it rides along; DOWN/DEGRADED on the asset or a component may open it | §10.1, inv. 123 |
| O-9 | S143 ratified as written: the driver line of a postponed occurrence | §10.1, §10.7 |
| O-10 | Counted-days range is an evaluation bound only; no day before the postponed actionable date contributes to health | inv. 116, §7.1 |

Of the archaeology's 29 §I recommendations:
- 27 are **RATIFIED (I-n, 2026-09-24)** where they are used.
- I-13 is ratified in principle, with its pull rule revised by Q-4.
- I-23 is replaced by Q-9.

**Owner rulings at the gate (closed record, 2026-09-24).**
1. Strings S1–S143 — RATIFIED as written (O-1; S143 under O-9).
2. D12 colour families — APPROVED (O-2).
3. YEAR_ROUND with a break, and RS-4 as 409 — CONFIRMED (O-3).
4. A break that fills the whole pre-season gap — CONFIRMED as the no-window-left case only (O-4).
5. AT_START interpretation — ACKNOWLEDGED, with the manual-season distinction (O-5).
6. Postponement and health — ACKNOWLEDGED, with the snooze/postpone distinction (O-6).
7. Meter-only phase-only design — ACKNOWLEDGED (O-7).
8. Scan routing for health — CONFIRMED (O-8).

**Decision register.** This one table maps decisions to rulings; the inline markers show where each applies.

| D | Decision | Ruled by | § |
|---|---|---|---|
| D-1 | Seven concepts, never collapsed; the interaction matrix is normative | architecture | 2 |
| D-2 | `season_mode` YEAR_ROUND / CALENDAR / MANUAL | I-1 | 3.1 |
| D-3 | Manual activation is an immutable row, never a journal event | I-2 | 3.3 |
| D-4 | Season, break and health policy each have their own command; the asset command keeps 1.3's shape; a PRE_SERVICE boundary never changes kind silently | Q-9, S-6, RS-4, O-3 | 3.2, 4.4, 6.5 |
| D-5 | A switch to MANUAL states the phase; a repeated START/END is 409 | I-3, I-4 | 3.3–3.4 |
| D-6 | One `ServicePolicy` enum replaces `season_behavior` + `season_reentry` | I-5, I-6 | 4.1 |
| D-7 | One signed offset; the owner enters the pre-service margin | I-10, Q-1 | 4.2 |
| D-8 | Pre-service point: the latest allowed day by the margin in the pre-season window, else the window's first day, else before the break; opened-before guard | I-13 principle, Q-3, Q-4, Q-6, RB-1, O-4, O-5 | 4.3 |
| D-9 | Asset-level break; CONTINUOUS ignores it; already-late work stays late and quiet | I-7, I-9, I-14 | 4.4 |
| D-10 | DEFERRED, decided after the worst-of fold on the time side | I-8, B-1 | 4.5 |
| D-11 | Group targets stay CONTINUOUS | I-11 | 4.6 |
| D-12 | The engine alone derives the actionable date; reads never write | S-2 | 4.7 |
| D-13 | Condition: immutable history, reason, soft event link, no notifications, offer never applied | I-21, I-22, I-28, I-29 | 5 |
| D-14 | Health subjects ASSET / PART / MEDIUM, one driver each; no #47 column | Q-8 | 6.1 |
| D-15 | 0–100 score; three owner-entered thresholds; fixed band cut points | I-16, Q-1 | 6.3 |
| D-16 | Baselines are canonical events; soft baseline-profile link | I-20, S-12 | 6.4 |
| D-17 | Aggregation stored, default WORST; critical contributors and DOWN/DEGRADED components always visible | I-17, Q-5, B-3 | 6.5 |
| D-18 | Health computed at read time; never stored, exported or defaulted | I-25, Q-1 | 6.7 |
| D-19 | Health clock: H.1c+H.1b medium, H.1a otherwise; H.2a principle; H.3 per policy, H.3c always | I-12, I-13, I-14, O-6 | 7 |
| D-20 | The policy moves time-side dates only; meter-only schedules are phase-only | Q-2, B-1, RS-2, O-7 | 4.1 |
| D-21 | Recreate `maintenance_schedule` and the derived `schedule_state` | I-24 | 8.1 |
| D-22 | Merge tables 12–14; identity by row id; one declining second identity | architecture | 8.4 |
| D-23 | `/v1` compatible; legacy inputs through one mapping, in the API and as deprecated MCP arguments | Q-9 (replaces I-23), RS-1 | 9.3 |
| D-24 | One scan predicate; health lines are passengers | I-26, B-3, O-8 | 10.1 |
| D-25 | DOWN first, DEGRADED after due work; independent health rows; components promoted | I-27, Q-5, S-3 | 10.2 |
| D-26 | D12 families; fresh words; reminder-health code renamed | I-15, I-18, I-19, Q-7, O-2 | 10.5–10.6 |
| D-27 | 1.4.0 / versionCode 16, MINOR | Q-9 | 15 |
| D-28 | `rule_changed_at` pins the recurrence (fixes #64) | B-2 | 4.3, 8.1 |
| D-29 | Spec 1.2 D-4's "`T`-independent sort key" is retired | S-2 | 4.7 |
| D-30 | A schedule can't leave a health subject dangling | RS-3 | 6.1 |

---

## 1. Goals and non-goals

**Goals.**
- Part-year assets, on a calendar or started by hand, with no backlog while dormant (#14 AC 5, AC 8).
- A per-schedule answer to "when should this be done relative to the season", kept separate from the season itself, with first-class pre-service and in-service families and a maintenance break (#60 AC 1, AC 5, AC 10).
- An auditable operational condition (#61 AC 1–10).
- Explainable health from age or overdue maintenance, whose clock follows the policy and never a raw date (#61 AC 11–26).

**Non-goals.**

| Out | Why |
|---|---|
| Installed components or assemblies | #47 (Q-8) |
| Stock-, measurement-, telemetry- or meter-driven health | #15, Q-2 |
| A one-off work item | RATIFIED (I-21, 2026-09-24): reason plus event link |
| Condition or health notifications | RATIFIED (I-28, 2026-09-24) |
| An UNKNOWN condition | RATIFIED (I-22, 2026-09-24) |
| Conflict-resolution UI, UPDATE verdicts | #44 |
| Todoist `PARKED` behaviour | #9 |
| Season or policy on a group | RATIFIED (I-11, 2026-09-24) |
| A predicted manual START | Q-6 |

---

## 2. The seven concepts (normative)

"Service" means only the maintenance service policy. The commissioning date keeps "In service date" and is none of the seven (arch. §A.6).

| # | Concept | Question | Canonical | Derived | Changed by |
|---|---|---|---|---|---|
| C1 | Lifecycle | Still ours, in use at all? | `asset.status`, `retired_on` | `targetInService` | asset edits |
| C2 | Condition | Can I rely on it now? | `asset_condition` rows | current = latest row | explicit condition write only |
| C3 | Health | How deteriorated or at risk, and why? | `health_subject` + events + schedule history | score, band, drivers, at read | nothing |
| C4 | Schedule state | What work is due? | schedule, completions, closures | `schedule_state`; status at read | `rebuild` only |
| C5 | Season | When is it normally in use? | `season_mode`, window, activation rows | season phase | season-mode command, START/END, a representable legacy pair |
| C6 | Service policy | When should work be done relative to the season? | `service_policy`, offset; asset break | actionable date, policy phase, `quiet` | schedule edits, break command |
| C7 | Reminder health | Is the reminder mechanism working? | none | findings on demand | nothing |

**Interaction matrix.** Read each row as "may affect" each column. "—" means never, and every "—" is an invariant (§11).

| → | C1 | C2 | C3 | C4 | C5 | C6 | C7 |
|---|---|---|---|---|---|---|---|
| C1 | · | — | surfaces only | withdrawn (shipped) | — | — | findings (shipped) |
| C2 | — | · | — | — | — | — | — |
| C3 | — | — | · | — | — | — | — |
| C4 | — | — | the MAINTENANCE_OVERDUE input | · | — | — | findings (shipped) |
| C5 | — | — | the clock (§7) | phase, actionable date | · | — | — |
| C6 | — | — | via the actionable date | actionable date, DEFERRED, quiet | — | · | — |
| C7 | — | — | — | — | — | — | · |

The rules above everything else (arch. §G; #61 AC 14):
- Nothing derives condition.
- Health never changes condition, and condition never pauses a schedule or moves health.
- The policy moves only time-side actionable dates.
- The occurrence key stays `computedDueOn`.

---

## 3. Season model (#14)

### 3.1 Season mode — RATIFIED (I-1, 2026-09-24)

`asset.season_mode` is one of YEAR_ROUND, CALENDAR or MANUAL. The shipped `season_start_mmdd` / `season_end_mmdd` are **non-null if and only if the mode is CALENDAR** (arch. §B). `Season.inSeason` (`Season.kt:27-38`) stays the calendar predicate: inclusive, wrapping, with 02-29 read as 02-28 in non-leap years (#14 AC 6).

**Season phase at date `d`:**
- YEAR_ROUND: always IN_SEASON.
- CALENDAR: `inSeason(window, d)`.
- MANUAL: the action of the latest activation row with `occurred_on ≤ d`. START means IN_SEASON from that day inclusive; END means OUT_OF_SEASON from that day inclusive.

**Cycle start at `d`.** This is the first day of the season span containing `d`. On a CALENDAR asset that is out of season, it is the next start. A MANUAL asset that is out of season has none, because the next START is never predicted (Q-6). A future manual START is therefore never a PRE_SERVICE deadline; once a START is recorded, it is the real cycle start from which IN_SERVICE_AT_START becomes actionable (O-5).

### 3.2 Changing the season (D-4)

1.4 clients change mode and window through `POST /v1/assets/{id}/season-mode`, one use case that the asset editor also calls.

**The asset command keeps 1.3's exact shape.** Its `seasonStartMmdd` / `seasonEndMmdd` are deprecated compatibility inputs (Q-9):
- A pair equal to the stored pair leaves the season untouched. This is how a MANUAL asset's null pair round-trips.
- A different pair is translated into CALENDAR(window) or YEAR_ROUND through the season-mode use case.
- On a MANUAL asset, a different pair is 422 `LEGACY_WRITE_CANNOT_REPRESENT`.

**The season-mode command refuses:**
- CALENDAR without a valid window: 422 `SEASON_WINDOW_REQUIRED`.
- A window with any other mode: 422 `SEASON_WINDOW_FORBIDDEN`.
- `manualPhase` on anything but a switch into MANUAL (MANUAL → MANUAL included): 422 `MANUAL_PHASE_FORBIDDEN`.
- A change to the **kind or presence of the boundary** a PRE_SERVICE schedule uses (§4.3), until that schedule's policy is changed: 409 `SEASON_MODE_STRANDS_POLICY` (S55), naming the schedules. This covers CALENDAR → YEAR_ROUND or MANUAL, and a break-only boundary → CALENDAR. It follows from #60 AC 5, "changing one never silently redefines the other" (RS-4; 409 approved, O-3). The same applies to a legacy pair. Changing a CALENDAR window keeps the boundary kind and is allowed.

Leaving MANUAL keeps the activation rows as unread history.

### 3.3 Manual activation — RATIFIED (I-2, I-4, 2026-09-24)

```
asset_season_activation          -- immutable fact (the occurrence_closure precedent)
  id TEXT PK, asset_id TEXT NOT NULL FK asset ON DELETE CASCADE,
  action TEXT NOT NULL (START|END), occurred_on TEXT NOT NULL,
  event_id TEXT NULL (soft link, no FK), created_at INTEGER NOT NULL
  INDEX(asset_id, occurred_on)
```

The table has no `updated_at`. No row is updated or deleted except by the asset's CASCADE. Rows are ordered by `(occurred_on, created_at, id)`.

**Local refusals:**
- The asset is not MANUAL: 409 `SEASON_NOT_MANUAL`.
- START after START, or END after END: 409 `SEASON_ALREADY_STARTED` / `SEASON_ALREADY_ENDED`.
- A date after today, or before the latest row's date: 422 `SEASON_DATE_OUT_OF_RANGE`.

A merge may still produce START, START. The phase is the latest row's, and the history shows both. Lifecycle only bounds where these rows surface.

**Journal events never activate** (arch. §B). A startup task's completion takes its profile's kind (`CompleteSchedule.kt:81`). After a SEASON_START or SEASON_END event on a MANUAL asset in the opposite phase, the app *offers* "Start the season now?" or "End the season now?". Accepting writes a row with the event's date, clamped, and `event_id` set.

### 3.4 Switching to MANUAL — RATIFIED (I-3, 2026-09-24)

A switch into MANUAL must state `manualPhase` (IN_SEASON or OUT_OF_SEASON); without it, 422 `MANUAL_PHASE_REQUIRED`. The same transaction writes one START or END row dated today, even when the latest historical row already says the same thing: END, END is valid history. The engine stays total: a MANUAL asset with no row (reachable only through an import) reads OUT_OF_SEASON.

### 3.5 Inactive season

`INACTIVE_SEASON` and the word **OUT OF SEASON** survive with one meaning: an IN_SERVICE schedule whose asset is OUT_OF_SEASON. The occurrence is kept and `computedDueOn` is still computed. Nothing notifies and nothing accumulates, and re-entry leaves exactly one current occurrence (#14 AC 5; spec 1.2 inv. 9, 13).

---

## 4. Service policy and the actionable date (#60)

### 4.1 The policy — RATIFIED (I-5, I-6, 2026-09-24)

| Policy | Editor wording | Family |
|---|---|---|
| `CONTINUOUS` | "Whenever it is due" | continuous (#60 AC 4) |
| `IN_SERVICE_AT_START` | "When the season starts" + "The season's start"; on a YEAR_ROUND asset with a break, "After the maintenance break" (offset 0) | in-service re-entry (#60 AC 9) |
| `IN_SERVICE_RESUME_CLAMPED` | "When the season starts" + "Its own date, but not before the season starts" | clamped resume (D5 §10.4) |
| `PRE_SERVICE` | "Before the season starts"; with no calendar season but a break, "Before the maintenance break" | pre-service (#60 AC 1, AC 11) |

**Time side only (D-20; B-1, RS-2).** The policy moves only the time side's date.

A **meter-only schedule** is **phase-only** (O-7):
- it may use IN_SERVICE_* for dormancy: INACTIVE_SEASON while the asset is out of season;
- it may be `quiet` during the maintenance break;
- neither moves a date, because there is no time-side due date;
- PRE_SERVICE is prohibited;
- a non-zero re-entry offset is prohibited (both 422 `SEASON_POLICY_NEEDS_A_TIME_RULE`);
- a crossed threshold is still genuinely DUE; dormancy and quiet affect only surfacing and delivery, never the meter fact.

**Legacy mapping.** One table serves the 7→8 migration, the format ≤7 decoder, the API's deprecated inputs and the MCP's deprecated arguments (§9.3). One test proves all four agree.

| `season_behavior` | `season_reentry` | → policy | → offset |
|---|---|---|---|
| IGNORE, or absent | anything | CONTINUOUS | null |
| FOLLOW_ASSET | null, `AT_START`, or unrecognised (e.g. an `MM-DD`) | IN_SERVICE_AT_START | old offset if 0–365 and a time rule exists, else 0 |
| FOLLOW_ASSET | `RESUME_CLAMPED` | IN_SERVICE_RESUME_CLAMPED | null |

The table *normalises* and never refuses, because none of these values ever had behaviour (spec 1.2 inv. 26). The API refusals for legacy bodies are exactly those in §9.3. On real data, all 43 Stage-B schedules are IGNORE (arch. §C).

### 4.2 The offset — RATIFIED (I-10, 2026-09-24)

`policy_offset_days` is signed and measured from a boundary:
- **IN_SERVICE_AT_START:** 0–365 days after the start. It is 0 by default: in the 1.4 form an omitted key means 0 (v1's full-replace default), and 0 means the start itself.
- **PRE_SERVICE:** −365 to −1, i.e. 1 to 365 days before the start. The owner enters it and nothing suggests a value (Q-1); omitting it is 422.
- **Other policies:** null.

Anything else is 422 `POLICY_OFFSET_INVALID`. The UI's field bounds make that unreachable. On a CALENDAR asset the editor warns (S84), without refusing, when `s + offset` falls after the season's end. `lead_days` keeps its 1.2 meaning.

### 4.3 Deriving the actionable date

**Terms.**
- **Raw due** `R` = `computedDueOn`. It is never changed and remains the occurrence key.
- **Opened** `O` = the occurrence's own opening: `lastTerminationEffectiveOn`, or for a never-terminated schedule the date of `rule_changed_at` (spec 1.2 D-27's pin floor, D-28). It is never the row's `updated_at`.
- **Actionable** `A` = the policy applied to `postponedDueOn ?: R`. It is the status input and the sort key, materialised as `actionable_due_on`.

`effective_due_on` keeps its 1.2 meaning (`docs/api/v1.md:196`).

**Pre-service (Q-4, Q-6, RB-1).** The operating season sets the **objective**: the work should be actionable before the asset enters its season. The break **constrains** when it may be actionable; the season forbids nothing.

The **boundary** `s` for `R`:
- On a CALENDAR asset: the start of the season containing `R`, or else the next start after `R`.
- With no calendar season but a break (YEAR_ROUND, or MANUAL): the start of the break containing `R`, or else the next one. This is the knowable boundary Q-6 names, confirmed for YEAR_ROUND by O-3.
- A manual START is never a boundary.

A day is **allowed** when it is outside the break. The **pre-season window** `W` is the maximal run of allowed days ending on `s − 1`; it is empty when `s − 1` is inside the break.

**The rule.**
- A raw due that is allowed and on or before `s − margin` keeps its date.
- Otherwise the pre-service point is:
  1. the **latest** day in `W` on or before `s − margin`, when one exists;
  2. otherwise the **first** day of `W`, i.e. the first allowed day after the break, which lies before `s`;
  3. only when `W` is empty (the break reaches `s`, leaving no allowed pre-season day), the last suitable allowed day before that break, even in the previous season. Any remaining window wins (O-4).
- A raw due inside the season follows the same rule.

```
policyDue(p, R: LocalDate?, O, ctx, T): Pair<LocalDate?, Reason> {
  if (R == null) return null to NONE                             // meter-only: phase-only
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
      val dl = s + offset                                        // offset = −margin
      if (R <= dl && ctx.allowed(R)) return R to NONE            // already in time
      val w = ctx.windowBefore(s)                                // allowed run ending s − 1, or empty
      val point = w.lastOnOrBefore(dl) ?: w.firstOrNull() ?: ctx.lastAllowedBeforeBreakAt(s - 1)
      if (point > R) return point to AFTER_BREAK                 // R sat in the break
      return if (O < point) point to (if (point in w && ctx.seasonBoundary) BEFORE_SEASON else BEFORE_BREAK) The reason is NONE whenever the actionable date equals the raw date; BEFORE_SEASON and BEFORE_BREAK describe a move (controller ruling, 2026-09-24).
             else if (ctx.allowed(R)) R to NONE                  // opened too late to pull (RN-10)
             else ctx.firstAllowedAfter(R) to AFTER_BREAK
    }
  }
}
```

**Postponement (S-1, O-6).** With `postponedDueOn` = `P`, CONTINUOUS gives `P`; IN_SERVICE runs its branch on `P`; PRE_SERVICE gives `P`, moved past the break when inside it, never earlier. A postponement into dormancy or a break is re-entered or deferred like a raw due, never OVERDUE on the first in-season day (arch. Finding A-1).

**The opened-before guard (Q-3).** Nothing becomes actionable earlier than `R` unless the occurrence opened before that point (`O < point`); otherwise a monthly PRE_SERVICE schedule would read OVERDUE all season. With the snowblower (deadline 1 Nov): completed 5 Nov, `R` 5 Dec → **1 Mar** (AFTER_BREAK); `R` 20 Nov opened 20 Oct → **1 Nov** (BEFORE_SEASON); opened 2 Nov → **20 Nov** kept.

**AT_START moves a raw date only when it is earlier than `seasonStart + offset`** (O-5). A later raw due is never dragged back to the season start, as a literal reading of D5 §6 and #14 would do. On a MANUAL asset `seasonStart` is the recorded START (§3.1).

**No inference.** The rule reads mode, window, break, margin and history. It never reads category, template or name (Q-4).

**Totality.** With no boundary (YEAR_ROUND or MANUAL without a break), PRE_SERVICE behaves as CONTINUOUS, with reason POLICY_INAPPLICABLE and warning S77. Commands refuse to create or reach that state (§3.2, §4.4; 409 `PRE_SERVICE_NEEDS_DATES`), so only a merge can.

**Rule fields (D-28, B-2; issue #64).** 1.3's `SaveSchedule` stamped `updated_at` on every save (`SaveSchedule.kt:133`), and the pin floor read it. Schema 8 adds `rule_changed_at`, written only when `ruleChanged` is true and read by the pin floor and `O`. `service_policy` and `policy_offset_days` are not rule fields, so a policy-, title-, lead- or health-link-only edit moves nothing and clears no postponement.

**What `rebuild` materialises**, from the season context as a pure input: `actionable_due_on`; `policy_reason`; `policy_phase` (DORMANT when an IN_SERVICE schedule's asset is OUT_OF_SEASON at `T`, else ACTIVE); and `quiet` ⇔ `service_policy ≠ CONTINUOUS` and `T` inside the asset's break, during which neither side delivers a notification.

### 4.4 The maintenance break — RATIFIED (I-7, I-9, I-14, 2026-09-24)

The break is stored in `asset.blackout_start_mmdd` / `blackout_end_mmdd`: both set or both null, wrapping, never the whole year (422 `BLACKOUT_COVERS_THE_YEAR`).

It changes only through `POST /v1/assets/{id}/maintenance-break`, never together with the season (#60 AC 5; S-6). That command refuses, with 409 `BREAK_STRANDS_POLICY` (S64), any change that would remove or re-kind a boundary a PRE_SERVICE schedule relies on — the same shape as RS-4 (O-3).

It applies to IN_SERVICE and PRE_SERVICE; CONTINUOUS startup and shutdown tasks ignore it (D5 §6). Its effects, all derived in §4.3:
- **Defer later (H.3a):** IN_SERVICE work, and PRE_SERVICE work whose point lies after the break.
- **Pull earlier (H.3b):** only when `W` is empty.
- **Already late (H.3c), always:** the item stays OVERDUE, counts as due and keeps degrading, but is `quiet`.

### 4.5 DEFERRED — RATIFIED (I-8, 2026-09-24)

A new status, decided **after the worst-of fold, on the time side alone** (B-1).

The time side is **held** when all three hold:
- `policy_reason = AFTER_BREAK`;
- `T ≥ (P ?: R) − leadDays`;
- `T < A`.

`statusOf` answers in this order:
1. PAUSED.
2. INACTIVE_SEASON (`policy_phase` DORMANT).
3. NO_DATA.
4. The fold, with a held time side counted as OK. When the fold answers OK and the time side is held, the status is **DEFERRED**.

A crossed meter threshold is never moved or deferred and reads DUE. Only DORMANT or `quiet` withholds its notification. DEFERRED never notifies and never counts as due. It has its own quiet dashboard section, **Deferred**, placed between CURRENT and OUT OF SEASON, and a status-filter chip.

### 4.6 Group targets — RATIFIED (I-11, 2026-09-24)

A group-targeted schedule is CONTINUOUS only; anything else is 422 `SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET`, restated.

### 4.7 One derivation; reads never write (#60 AC 7; S-2)

`BuildReminderSubjects` stops deriving seasons from `MM-DD` (`:138-143`) and maps engine output:

| Engine state | Subject |
|---|---|
| ARCHIVED | `Withdrawn` |
| PAUSED | `Parked(null)` |
| DORMANT | `Parked(actionableDueOn)` (null for MANUAL) |
| DEFERRED | `Parked(A)` |
| quiet, with a notifying status | `Parked(the first day after the break)` |
| otherwise | `Active` |

`RuleFacts.seasonal` becomes `servicePolicy ≠ CONTINUOUS`. It is false before and after on real data, so no content hash moves.

**No read path writes.** `/v1/due`, `/v1/attention`, the health route, the dashboard, asset detail and scan routing never persist anything. A `schedule_state` row whose `computed_for_on ≠ T`, or a missing row, is derived in memory by `RecomputeSchedules.stateOf` (`:135-147`, as `DueReadModel.kt:192` already does). Only the daily job, a recompute after a write, and an explicit rebuild persist state. Spec 1.2 inv. 23's between-rebuilds exception goes, and so does D-4's `T`-independent sort key (D-29).

---

## 5. Operational condition (#61)

### 5.1 History — RATIFIED (I-22, 2026-09-24)

```
asset_condition                  -- immutable fact
  id TEXT PK, asset_id TEXT NOT NULL FK asset ON DELETE CASCADE,
  condition TEXT NOT NULL (OPERATIONAL|DEGRADED|DOWN),
  occurred_on TEXT NOT NULL (≤ today), occurred_time TEXT NULL (HH:MM), tz_id TEXT NOT NULL,
  reason TEXT NOT NULL (≤ 500, may be empty), event_id TEXT NULL (soft link),
  created_at INTEGER NOT NULL        INDEX(asset_id, occurred_on)
```

Three values and no fourth: "pending maintenance" is a reason. No UNKNOWN is stored; an asset with no row reads **Condition not recorded**. **Current** is the latest row by `(occurred_on, occurred_time with nulls first, created_at, id)`; **since** is the earliest row of the latest run of equal values. No column stores the current condition (arch. §D.1). A correction is a new row; backdated rows sort into place; lifecycle only bounds surfacing.

### 5.2 Reason — RATIFIED (I-21, 2026-09-24)

"Battery failed — replacement pending" is a DOWN row's reason; no one-off schedule is created (#61 AC 2).

### 5.3 The soft link

`event_id` has no FK. Events can be deleted (`docs/api/v1.md:140-141`), and `SET NULL` would mutate an immutable row. A dangling link reads "The linked record was removed." and is never an owner in a merge.

### 5.4 Writes and offers — RATIFIED (I-29, 2026-09-24)

Only these write a condition row:
- "Save condition";
- the "Mark operational" confirmation;
- the offer below;
- `POST /v1/assets/{id}/conditions`;
- MCP `record_condition`.

No scan, health value, status, season, event or completion writes one (#61 AC 4, AC 14).

After a completion, or a MAINTENANCE or REPLACEMENT event, on a DOWN or DEGRADED asset, the app offers "Mark operational?". Accepting writes OPERATIONAL dated `max(event date, current row's date)`, with `event_id` set. "Not yet" writes nothing.

### 5.5 What condition does not do — RATIFIED (I-28, 2026-09-24)

It pauses nothing, withholds no reminder, moves no health and notifies nobody. An OPERATIONAL asset may still show OVERDUE work (#61 AC 7).

---

## 6. Derived health (#61)

### 6.1 Subjects (D-14, D-30)

```
health_subject                   -- configuration aggregate
  id, asset_id (FK CASCADE), name (1–60), kind ASSET|PART|MEDIUM, driver AGE|MAINTENANCE_OVERDUE,
  schedule_id NULL (FK CASCADE; MAINTENANCE_OVERDUE only, required),
  baseline_profile_id NULL (soft link; AGE only), nominal_until_days, warning_from_days,
  critical_from_days (NOT NULL, no default), weight 1–10 DEFAULT 1, sort_order,
  archived_at NULL, created_at, updated_at        INDEX(asset_id), INDEX(schedule_id)
```

**Kinds.**
- `ASSET` is the whole asset.
- `PART` is an asset-scoped logical part (the UPS's "Battery age") with no installed identity; #47 adds the binding later (Q-8).
- `MEDIUM` is a maintained medium such as hot-tub water care, which says nothing about the pump (#61 AC 17).

Kind changes only the clock (§7). There is one driver per subject, and composition happens at the asset (§6.5). A schedule drives at most one non-archived subject (#61 AC 20). A subject never changes asset.

**A subject save is refused when:**
- a MAINTENANCE_OVERDUE subject has no asset-targeted schedule on the same asset with a time rule;
- an AGE subject names a schedule;
- the baseline profile is foreign or is not a REPLACEMENT;
- the thresholds are not `0 ≤ t1 < t2 < t3 ≤ 36,500`.

**The link is guarded from the schedule side (D-30, RS-3).** These schedule writes are refused while a non-archived subject depends on the schedule:
- a PATCH that removes the time rule;
- a PATCH that changes the target asset;
- an archive.

The refusal is 422 `SCHEDULE_DRIVES_HEALTH_SUBJECT`, naming the subject, unless the request carries `unlinkHealthSubject: true`. The flag archives the subject in the same transaction; in the app, S140–S141 ask for it. A deleted schedule cascades its subject. So no subject ever names an archived, retargeted or rule-less schedule. A merge can still bring one; the engine then reads NOT TRACKED (S142).

### 6.2 Drivers (Q-2)

- **AGE:** `x` = days from the baseline to `T`. Season, policy and pause are ignored.
- **MAINTENANCE_OVERDUE:** `x` = the counted late days of the linked schedule's current occurrence (§7), measured from the **actionable** date (#61 AC 24).
- **The meter side drives no health in 1.4.** A meter-only schedule cannot drive a subject (422 `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`). The seam stays: a later driver can read a crossing date without reshaping the subject.

### 6.3 Score and bands — RATIFIED (I-16, 2026-09-24); Q-1

```
score(x; t1, t2, t3) = ceil(v), where v =
  100                                   when x <= t1
  100 - 40·(x - t1)/(t2 - t1)           when t1 < x <= t2
   60 - 35·(x - t2)/(t3 - t2)           when t2 < x <= t3
   25 - 25·(x - t3)/(t3 - t2)           when t3 < x <= t3 + (t3 - t2)
    0                                   beyond
band(s) = CRITICAL if s <= 25, WARNING if s <= 60, NOMINAL otherwise
```

Arithmetic is exact rational, then rounded up with a ceiling. So WARNING starts exactly at `t2` and CRITICAL exactly at `t3`, and the score never rises as `x` grows. The anchors are fixed, so every score means the same thing and an aggregate has a band. The words are NOMINAL · WARNING · CRITICAL · NOT TRACKED: never "healthy", and never a 100 standing in for no data.

**Nothing is defaulted (Q-1).** The thresholds are per-subject configuration. The fields start empty, and the command requires all three. The two templates, engine service 14 / 45 / 120 and water care 2 / 7 / 14, fill the fields only after S129 is confirmed. They are not canonical values and not safety claims, and AGE has none. The fixtures (§12.4) state their own values.

### 6.4 Baselines are events — RATIFIED (I-20, 2026-09-24)

**AGE:** the latest REPLACEMENT event on the asset, logged with the named quick action when one is set; with none, NOT TRACKED. A new REPLACEMENT resets the value and erases nothing (#61 AC 12); deleting it restores the previous value. `baseline_profile_id` is a soft link (S-12): deleting the quick action (always allowed, `DeleteProfile.kt`) leaves NOT TRACKED (S101), never a widened filter.

**MAINTENANCE_OVERDUE:** the linked schedule's terminations, read through the engine; a completion returns `x` to 0 (#61 AC 18). A closure claims no work and cannot reach a subject, because closures are group-only.

There is no baseline table. **The C.2 hazard:** a combined "load test and battery replacement" REPLACEMENT profile would reset battery age on a load test, so the fixture (and the Stage-B note) splits it into an INSPECTION load test and a REPLACEMENT "Battery replaced" baseline.

### 6.5 Aggregation — RATIFIED (I-17, 2026-09-24); Q-5

`health_aggregation` is one of TRACK_ONE, AVERAGE, WEIGHTED or WORST, **default WORST**. It is set only through `POST /v1/assets/{id}/health-policy`, together with `health_primary_subject_id` (no FK; TRACK_ONE only).

Over the non-archived subjects that have a value:
- WORST takes the minimum.
- TRACK_ONE takes the primary's value.
- AVERAGE takes `floor(mean)`.
- WEIGHTED takes `floor(Σ w·s / Σ w)`.

Floors never round an aggregate up into a better band. With no contributors the aggregate is NOT TRACKED. Archiving the primary is 409 `HEALTH_SUBJECT_IS_PRIMARY` (S137). A dangling primary falls back to WORST (S138).

**Nothing hides behind an aggregate** (#61 AC 23; B-3). Every surface that shows health lists, whatever the aggregation:
- every CRITICAL contributor;
- every DOWN or DEGRADED in-service **component** (child asset, at any depth).

A DOWN asset's health is never shown without its condition.

### 6.6 Explanation (#61 AC 15, AC 19)

Driver lines are S99–S106. The aggregate reads `<score> — <subject> <score>, …`. Asset detail closes with S107.

### 6.7 Stored, exported — RATIFIED (I-25, 2026-09-24)

Health is a pure `:core` function computed at read time, like status (spec 1.2 inv. 18). There is no `health_state` table. Archives carry configuration and inputs, and restore reproduces the value (#61 AC 16).

### 6.8 Health and the rest

Health notifies nothing. A linked schedule that is PAUSED or ARCHIVED makes its subject NOT TRACKED. A pause has no dated history, so it cannot freeze a past clock (`PauseSchedule.kt:13-15`); a hot tub uses a MANUAL season instead.

---

## 7. The health clock (#61 AC 24–26)

### 7.1 The rule — RATIFIED (I-12, I-14, 2026-09-24); I-13 in principle

For a MAINTENANCE_OVERDUE subject, a day `d` is **counted** when all of these hold:
- the policy phase on `d` is ACTIVE;
- `d > A(d)`, the actionable date evaluated at `d` with today's configuration and postponement;
- for a MEDIUM subject on an IN_SERVICE schedule only, `d` is on or after the latest cycle start at or before `T`. There is no restriction when no cycle start exists.

`x` is the number of counted days. The span implementation is tested against this day-by-day oracle.

| Case | Consequence | arch. |
|---|---|---|
| MEDIUM, dormant at `T` | NOT TRACKED | H.1c |
| MEDIUM, after START | lateness restarts | H.1b |
| ASSET/PART, IN_SERVICE | lateness freezes while dormant and carries across | H.1a |
| PRE_SERVICE | counts from the pre-service point; season start resets nothing | H.2a principle |
| date moved out of a break | break days not counted | H.3a |
| already late when the break opens | break days counted | H.3c |
| postponement | a canonical reassessment of this occurrence's actionable date: the clock restarts from it and earlier lateness is not retained; no postponement journal in 1.4 | O-6 |
| snooze | delays the reminder only; never changes health | O-6 |

### 7.2 Hot tub across END and START (MANUAL; H.1c + H.1b)

Weekly FIXED schedule anchored Sat 2026-01-03, IN_SERVICE_AT_START with offset 0, lead 1. The subject "Water care" is MEDIUM with thresholds 2 / 7 / 14.

| Date | Fact | Schedule | Water care |
|---|---|---|---|
| Sat 11 Apr | completed | `R` = Sat 18 Apr | 100 |
| Thu 16 Apr | END | DORMANT, OUT OF SEASON, `Parked(null)` | NOT TRACKED |
| 1 Jul | — | one occurrence (`R` 18 Apr) | NOT TRACKED |
| Sat 10 Oct | START | `A` = 10 Oct, DUE | 100 |
| Tue 13 Oct | still open | OVERDUE | `x` 3 → **92** |
| Thu 15 Oct | — | OVERDUE | `x` 5 → **76** |
| Sat 17 Oct | — | OVERDUE | `x` 7 → **60** WARNING |
| Sat 24 Oct | — | OVERDUE | `x` 14 → **25** CRITICAL |
| Tue 13 Oct (alt.) | completed | `R` = Sat 17 Oct | 100 |

**Contrast (H.1a).** Suppose the 18 Apr occurrence were open at an END on Wed 22 Apr, and Water care were a PART subject. The END day is dormant (§3.1), so 19–21 Apr count as 3 days. On 17 Oct `x` = 3 + 7 = **10**, which scores **45** WARNING.

This timeline covers #14 AC 8, #60 AC 9 and #61 AC 18 and AC 25.

### 7.3 Pre-service (CALENDAR, PRE_SERVICE; Q-4, RB-1)

**Snowblower.**
- Season 11-15 → 03-31; break 12-01 → 02-28, which lies inside the season.
- Two-yearly FIXED schedule anchored 2024-12-20, last done 2024-11-10 (`O`), so `R` = 2026-12-20.
- Margin 14, lead 14. Subject "Engine oil service", PART, thresholds 14 / 45 / 120.

Here `s` = 15 Nov 2026, and `W` runs from 1 Mar to 14 Nov. The latest day in `W` on or before 1 Nov is 1 Nov, and `O` < 1 Nov, so `A` = **1 Nov** (BEFORE_SEASON).

| Date | Schedule | Notification | Health |
|---|---|---|---|
| 18 Oct | DUE SOON | first entry | 100 |
| 1 Nov | DUE | yes | 100 |
| 2–30 Nov | OVERDUE | every 3 days (spec 1.2 D-5) | 15 Nov (`x` 14) 100; 30 Nov (29) 81 |
| 1 Dec – 28 Feb | OVERDUE, quiet | none (H.3c) | 16 Dec (45) **60**; 20 Jan (80) 44 |
| 1 Mar 2027 | OVERDUE | resumes | (120) **25** CRITICAL |
| 15 May 2027 | OVERDUE | yes | (195) 0 |

**Mower.**
- Season 04-15 → 10-31; the same break (12-01 → 02-28), which here lies in the off-season.
- Yearly oil change; margin 14, lead 14; the same subject.

`R` = 5 Nov 2026 is allowed and before 1 Apr 2027, so it is kept (the autumn habit).

`R` = 12 Jan 2027 falls inside the break. `s` = 15 Apr 2027, `W` runs from 1 Mar to 14 Apr, and the latest day in `W` on or before 1 Apr is 1 Apr. So `A` = **1 Apr 2027** (AFTER_BREAK; #60 AC 2):

| Date | Schedule | Health |
|---|---|---|
| 29 Dec 2026 – 31 Mar 2027 | **DEFERRED**, S85 "until 1 Apr" | 100 (not counted) |
| 1 Apr | DUE | 100 |
| 15 Apr | OVERDUE; the season starts and resets nothing | (`x` 14) 100 |
| 16 May | OVERDUE | (45) **60** WARNING |
| 30 Jul | OVERDUE | (120) **25** CRITICAL |

**Variants.**
- A mower break of 12-01 → 04-05 leaves `W` = 6–14 Apr. No day in `W` is on or before 1 Apr, so the point is `W`'s first day: **6 Apr 2027**. The item is DEFERRED from 29 Dec and DUE on 6 Apr.
- A raw due inside the season, say 20 Apr 2027, is pulled to **1 Apr** when `O` < 1 Apr.
- Only a break that fills the whole gap up to 14 Apr pulls the work to before it (O-4).
- An IN_SERVICE_AT_START mower shows OUT OF SEASON until 15 Apr and is DUE on 15 Apr (#60 AC 3).

### 7.4 A break (YEAR_ROUND generator, IN_SERVICE; H.3a, H.3c)

**Configuration.**
- YEAR_ROUND; break 12-01 → 02-28.
- Six-monthly FIXED schedule anchored 2025-12-20, "After the maintenance break" (IN_SERVICE_AT_START, offset 0).
- Created 5 Jan 2026, so the pin from `rule_changed_at` puts the first `R` at 20 Jun 2026.
- Lead 14; subject "Engine oil service", 14 / 45 / 120.
- DEGRADED since 30 Aug, "Reduced output under load", independent of everything below.

| Date | Occurrence | Schedule | Health |
|---|---|---|---|
| 20 Jun 2026 | `R` = `A` | DUE | 100 |
| 4 Aug | open | OVERDUE | (`x` 45) **60** |
| 24 Sep | open | OVERDUE | (96) 37 |
| 18 Oct | open | OVERDUE | (120) **25** |
| 20 Oct | completed | `R` = 20 Dec, inside the break → `A` = **1 Mar 2027** | 100 |
| 6 Dec – 28 Feb | open | **DEFERRED**, `Parked(1 Mar)` | 100 (H.3a) |
| 1 Mar 2027 | open | DUE | 100 |
| 15 Apr 2027 | open | OVERDUE | (45) 60 |

**H.3c branch.** Had the 20 Jun occurrence stayed open: on 1 Dec (`x` 164) it reads 11, stays OVERDUE and quiet through the break, and reaches 0 on 1 Jan 2027 (`x` 195).

---

## 8. Data model, migration, format 8, merge

### 8.1 Room schema 7 → 8

| Order | Table | Change |
|---|---|---|
| 1 | `asset` | `ADD COLUMN` ×5: `season_mode` (NOT NULL DEFAULT 'YEAR_ROUND'), the break pair, `health_aggregation` (NOT NULL DEFAULT 'WORST'), `health_primary_subject_id`. Then `season_mode = 'CALENDAR'` where both `MM-DD` are set. `updated_at` untouched |
| 2 | `maintenance_schedule` | **12-step recreate** — RATIFIED (I-24, 2026-09-24). Drop the three season columns. Add `service_policy`, `policy_offset_days`, and `rule_changed_at` seeded from `updated_at`. Copy through §4.1's table. Keep the four FKs (asset CASCADE, group CASCADE, measurement_definition RESTRICT, event_profile SET NULL) and every index |
| 3 | `schedule_state` | Recreate as derived: `policy_phase`, `actionable_due_on` (the sort index), `policy_reason`, `quiet`. Created empty, derived in memory until persisted |
| 4 | three new tables | `health_subject` is created after step 2 |

This follows the 2→3 and 3→4 precedent: Room disables FKs during `migrate` and runs `foreign_key_check` afterwards (`Migrations.kt:96-97, 165-166`).

### 8.2 Lossless migration

- An asset is CALENDAR if and only if both `MM-DD` were set (S-9). No asset gets a break.
- Every schedule goes through §4.1.
- `rule_changed_at = updated_at`, and no `updated_at` moves.

A format-7 export taken before the upgrade therefore merges IDENTICAL row for row (§12.3).

### 8.3 Backup format 8

**New lists**, sorted by id, validated eagerly, and refused on an absent asset: `seasonActivations`, `assetConditions` and `healthSubjects`. Each defaults to empty.

**Changed DTOs.**
- `AssetDto` gains the mode, the break pair and the health policy. A format ≤7 asset decodes as CALENDAR if and only if both `MM-DD` are set, with no break and WORST.
- `MaintenanceScheduleDto` gains `servicePolicy`, `policyOffsetDays` and `ruleChangedAt`. A format ≤7 schedule decodes with `ruleChangedAt = updatedAt`.
- The three old season fields decode through §4.1 in formats ≤7. In format 8 they are `BackupCorrupt`, and they are never encoded.

`counts` gains three keys. The `external_link` tombstones stay byte-for-byte. Nothing derived is exported (spec 1.2 inv. 64). 1.4 reads formats 1–8, and 1.3.x refuses format 8 with `BackupNewerFormat`.

### 8.4 Merge tables 12–14

The three tables follow `REFERENCES` (`MergePlan.kt:28-36`).

| # | Table | Identity | Reasons (**new**) |
|---|---|---|---|
| 12 | `SEASON_ACTIVATIONS` | row id | `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` (asset) |
| 13 | `CONDITIONS` | row id | `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` (asset) |
| 14 | `HEALTH_SUBJECTS` | row id, plus non-archived `schedule_id` | `CONTENT_DIFFERS`, `OWNER_NOT_AVAILABLE` (asset, schedule); **`HEALTH_SUBJECT_SCHEDULE_HELD_BY_A_LOCAL_ROW`** (SKIPPED); **`HEALTH_SUBJECT_SCHEDULE_DUPLICATED_IN_ARCHIVE`** |

Facts (12–13) are immutable: a re-import is IDENTICAL, two phones' rows are two inserts, and soft links are never owners. START, END and condition writes never touch the asset row; a divergence in mode, break or health policy is CONTENT_DIFFERS. The subject's second identity declines (SKIPPED), as 1.3's D-18. There is still no UPDATE verdict, one conflict writes nothing, and every schedule is rebuilt after apply. #44 must remap `asset_id` in all three tables.

---

## 9. API and MCP

### 9.1 Routes (API version stays 1)

**Postpone and snooze (O-6, inv. 131).** The shipped `POST /v1/schedules/{id}/postpone` moves this occurrence's canonical due date; under 1.4 the result passes through the policy (§4.3) and **may restart maintenance-overdue health** from the new actionable date. That is intended, not a defect, and `docs/api/v1.md` says so. Snooze is device-local, has no endpoint, delays only the reminder, and never changes health.

| method | path | body | success |
|---|---|---|---|
| `GET` | `/v1/assets/{id}/season` | — | 200 `{seasonMode, seasonStartMmdd, seasonEndMmdd, seasonPhase, nextBoundaryOn, blackoutStartMmdd, blackoutEndMmdd, inBreak, activations, computedForOn}` |
| `POST` | `/v1/assets/{id}/season` | `{action, occurredOn?, eventId?}` | 201 `{activation, season}` |
| `POST` | `/v1/assets/{id}/season-mode` | `{seasonMode, seasonStartMmdd?, seasonEndMmdd?, manualPhase?}` | 200 `{asset, season}` |
| `POST` | `/v1/assets/{id}/maintenance-break` | `{blackoutStartMmdd, blackoutEndMmdd}` | 200 `{asset, season}` |
| `POST` | `/v1/assets/{id}/health-policy` | `{healthAggregation, healthPrimarySubjectId?}` | 200 `{asset}` |
| `GET`/`POST` | `/v1/assets/{id}/conditions` | — / `{condition, occurredOn?, occurredTime?, tzId, reason?, eventId?}` | 200 `{conditions, current}` / 201 `{condition, current}` |
| `GET` | `/v1/assets/{id}/health` | — | 200 `{assetId, computedForOn, condition, aggregation, aggregate, subjects, critical, components}` |
| `GET` | `/v1/assets/{id}/health-subjects` | — | 200 `{subjects}` |
| `POST` | `/v1/health-subjects` | subject command | 201 |
| `GET`/`PATCH` | `/v1/health-subjects/{id}` | — / subject command (full replace; `assetId` unknown) | 200 |
| `POST` | `/v1/health-subjects/{id}/archive` | `{archived}` | 200 |
| `GET` | `/v1/attention` | — | 200 `{items}` |

**The asset command keeps 1.3's shape**, and condition is never part of it (#61 AC 9).

**Schedule command.**
- It gains `servicePolicy` and `policyOffsetDays`, and keeps the three old fields as deprecated inputs.
- The PATCH and `…/archive` accept the action flag `unlinkHealthSubject` (§6.1). Like `manualPhase`, it is an action parameter and not a column.
- Responses carry the old three as a **derived compatibility projection**: §4.1 reversed, and all null for PRE_SERVICE. So `seasonBehavior` becomes nullable (RN-4).
- `ruleChangedAt` is response-only.

**Derived state.** `ScheduleStateDto` gains `actionableDueOn`, `policyReason`, `policyPhase` and `quiet`. It keeps `effectiveDueOn` (1.2 meaning) and `seasonActive` (`policyPhase == ACTIVE`). `/v1/due` items gain `actionableDueOn`, `policyReason` and `quiet`, and `status` may be `DEFERRED`.

**Attention item.** Every field is present, `null` when absent:

```
{kind (CONDITION|HEALTH), section (ATTENTION|UPCOMING), assetId, parentAssetId, condition,
 reason, occurredOn, healthSubjectId, subjectName, band, score, rank}
```

Rows cover in-service assets and components. `rank` is dense in this order: DOWN, DEGRADED, independent CRITICAL, independent WARNING. Within each group, rows are ordered by asset name, then id.

`/v1/status` reports schema and format 8 and three new count keys. import-merge reads formats 1–8.

### 9.2 Codes

**The 422/409 tie-break (RN-8).** A **422** means the remedy is to change *this body*, whatever the stored state. A **409** means the remedy is to change *another row first*.

**422:**
- Compatibility: `LEGACY_WRITE_CANNOT_REPRESENT`, `LEGACY_AND_CURRENT_FIELDS_MIXED`.
- Policy: `SEASON_POLICY_NEEDS_A_TIME_RULE`, `SEASON_FOLLOWS_ASSET_ON_GROUP_TARGET`, `POLICY_OFFSET_INVALID`.
- Season and break: `SEASON_WINDOW_REQUIRED`, `SEASON_WINDOW_FORBIDDEN`, `MANUAL_PHASE_REQUIRED`, `MANUAL_PHASE_FORBIDDEN`, `SEASON_DATE_OUT_OF_RANGE`, `BLACKOUT_COVERS_THE_YEAR`.
- Condition: `CONDITION_DATE_IN_FUTURE`, `CONDITION_REASON_TOO_LONG`, `FOREIGN_EVENT`.
- Health: `SCHEDULE_DRIVES_HEALTH_SUBJECT` (the remedy is the flag in this body), `HEALTH_SUBJECT_NAME_REQUIRED`, `HEALTH_THRESHOLDS_INVALID`, `HEALTH_DRIVER_MISMATCH`, `FOREIGN_SCHEDULE`, `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE`, `PROFILE_NOT_A_REPLACEMENT`, `HEALTH_WEIGHT_OUT_OF_RANGE`, `HEALTH_PRIMARY_INVALID`.
- A malformed `MM-DD`, `occurredTime` or `tzId` uses the shipped validation shape (`docs/api/v1.md:413`).

**409:**
- `SEASON_NOT_MANUAL`, `SEASON_ALREADY_STARTED`, `SEASON_ALREADY_ENDED`.
- `SEASON_MODE_STRANDS_POLICY` and `BREAK_STRANDS_POLICY`: the remedy is the schedule's policy, another row.
- `PRE_SERVICE_NEEDS_DATES`: the remedy is the asset.
- `HEALTH_SCHEDULE_TAKEN`, `HEALTH_SUBJECT_IS_PRIMARY`.

**404:** `NO_SUCH_HEALTH_SUBJECT`, and the shipped `no_such_asset`. The envelope gains an optional `field`.

**No endpoint** amends or deletes a condition or activation, deletes a health subject, or writes a health value. `theDestructiveUseCasesHaveNoRoute` gains the paths.

### 9.3 Compatibility: `/v1` stays whole (Q-9, replacing I-23; RS-1)

**Two schedule forms, by key presence.**
- `servicePolicy` or `policyOffsetDays` present: the **1.4 form**.
- Neither present: the **legacy form**. It keeps its 1.3 meaning, including the omitted-field defaults (`seasonBehavior` → IGNORE, `MaintenanceDtos.kt:254`).
- Both forms in one body: 422 `LEGACY_AND_CURRENT_FIELDS_MIXED`.
- An explicit `null` counts as present.

The asset command has one form. Its pair is the only compatibility input (§3.2), and the break and health policy live in commands no 1.3 client sends.

**One translation.** Legacy inputs pass only through §4.1's table, which normalises (RN-3).

**Refused, never reset.** A legacy write that would change 1.4-only state is 422 `LEGACY_WRITE_CANNOT_REPRESENT` and writes nothing. That happens in two cases:
- a schedule PATCH on a PRE_SERVICE schedule;
- an asset pair change on a MANUAL asset.

Everything else translates. IN_SERVICE_* is 1.2's FOLLOW_ASSET, and a create clobbers nothing. The 422 follows the tie-break: the remedy is sending the 1.4 form.

**One behaviour change to document.** A legacy body that omits `seasonReentry` on a RESUME_CLAMPED schedule now becomes AT_START. In 1.3 the field was unread. The v1 "Deprecated inputs" note records this.

**Reads stay compatible.** Responses gain fields and one status value, and `seasonBehavior` may now be null. The derived triple round-trips every representable schedule. A PRE_SERVICE row reads nulls, and writing them back is refused loudly.

**No versioned write contract.** The presence rule makes every case unambiguous. A field that could not follow it would get `/v2` writes, not a break in `/v1`.

**MCP (RS-1).** The 1.4 MCP's `create_schedule` and `update_schedule` keep `season_behavior`, `season_reentry` and `season_reentry_offset_days` as **deprecated arguments**:
- They are sent in a legacy-form body, so the API performs the one translation, and refused with the same codes.
- Mixing them with `service_policy` / `policy_offset_days` is a `ToolError` carrying `LEGACY_AND_CURRENT_FIELDS_MIXED`.

**Lockstep components.** The app, `tools/servicetag-mcp` and `tools/servicetag-schedules` ship together.
- **The loader** keeps writing `season_behavior` through the deprecated argument. Its re-plan compares a manifest's legacy value, mapped by §4.1, with the row's `servicePolicy`, so the loaded Stage-B manifest keeps re-planning IDENTICAL.
- **A 1.3 MCP** keeps working on representable rows and gets a named 422 on the rest.

Call sites are listed in Appendix A.

### 9.4 MCP tools

There are fourteen new tools. The shipped conventions hold: unknown arguments are rejected, `null` means unchanged, `clear_fields` clears, and every error is a `ToolError` carrying its code.
- Season: `get_season`, `start_season`, `end_season`, `set_season_mode`, `set_maintenance_break`.
- Condition: `list_conditions`, `record_condition`.
- Health: `get_health`, `set_health_policy`, `list_health_subjects`, `create_health_subject`, `update_health_subject`, `archive_health_subject`.
- Attention: `list_attention`.

`start_season`, `end_season` and `record_condition` have no overlay, like `close_round`. `update_schedule` and `update_asset` stop enumerating fields: they overlay every command key the row reports, checked against a golden `docs/api/command-shapes.json` by both a JVM test and pytest. Write tools refuse while `/v1/status.schemaVersion < 8`. The README count becomes fifty-five.

---

## 10. UI

The wording is in §10.7. There are no UI journey tests (§12).

### 10.1 Scan — RATIFIED (I-26, 2026-09-24)

On a valid asset tag (#50) the sheet shows, top to bottom:
1. The asset name and #49's caption.
2. The **condition** block: word, icon, reason and "since" (#61 AC 3).
3. "Mark operational" and "Change condition" for DOWN or DEGRADED, otherwise "Change condition".
4. Every DOWN or DEGRADED component (S27).
5. Every CRITICAL subject (S109), plus the aggregate when it is WARNING or CRITICAL.
6. "Maintenance" with D-18a's items, or "Nothing due".
7. "Open asset".

The aggregate never decides visibility (B-3).

**One predicate** routes and fills the sheet: `scanSheetContent(items, condition, components, subjects)` (`MaintenanceSheetViewModel.kt:114-166`).
- The sheet opens for any D-18a item, a DOWN or DEGRADED asset, or a DOWN or DEGRADED component.
- Health lines are **passengers**, like DUE SOON: a CRITICAL contributor alone never opens the sheet, and rides along when another reason opens it. Calculated health has no routing power in 1.4 (O-8).
- DOWN or DEGRADED on the asset or a component may open it, because condition is an at-the-unit concern (O-8).
- Otherwise the scan opens asset detail, which shows the same lines.

**Snooze and Postpone** keep their 1.2 meanings on the sheet and on schedule detail (O-6). Snooze delays the reminder and never improves health. Postpone moves the canonical due date and may restart maintenance-overdue health; while an occurrence is postponed and not yet late against the new date, its driver line is S143, else S103.

The scan writes nothing (spec 1.2 inv. 57). "Change condition" offers the three options, "What is wrong? (optional)" and "When did this change?" (today by default, past dates allowed). Cancel writes nothing.

### 10.2 Dashboard — RATIFIED (I-27, 2026-09-24); Q-5

**ATTENTION**, in order:
1. DOWN assets and components (#61 AC 6).
2. Schedule rows.
3. DEGRADED assets and components.
4. Independent CRITICAL subjects.

**UPCOMING** gains independent WARNING subjects after the DUE SOON rows. Overdue-driven health rides its schedule row.

**Components** are promoted with their parent named (spec 1.2 inv. 75, extended). Only in-service rows appear.

**Filters.**
- Status chips (DEFERRED added) select schedule rows.
- Condition chips (Down, Degraded, Operational, Not recorded) select rows by condition.
- When only status chips are active, asset rows are hidden.
- Category chips and search apply to both kinds. (Controller note, 2026-09-26: the dashboard has had no search box since the 1.2 ruling that moved search to the Assets screen; on the dashboard only the category half applies.)

### 10.3 Asset detail

The identity plate and every component row gain a condition badge (`AssetDetailScreen.kt:613-632`). New sections:
- **Condition:** current condition, "Change condition", and history (#61 AC 5).
- **Health:** critical lines and DOWN/DEGRADED components first, then the aggregate, contributors and S107.
- **Season:** the window or phase, Start/End (S42–S45) and history; the break.

### 10.4 Editors

**Asset editor.**
- "Operating season", with S35 when switching to manual.
- "Maintenance break", never prefilled.
- "Health subjects" and "Combine health by".
- Save runs the asset, season-mode, break and health-policy use cases in one `uow.write`. S55 and S64 guide a refused boundary change.

**Health subject editor.**
- Name (shipped), "What is it?", "What wears it down?", and the schedule or quick action.
- Three empty threshold fields. Save is disabled until they are filled (S126); "Use a starting point" (S127–S130) fills them.
- Weight appears only under WEIGHTED.

**Schedule editor.** The question "When should this maintenance be done?" appears only when the asset has a season or a break.

| Asset | Options |
|---|---|
| CALENDAR | Before the season starts / When the season starts / Whenever it is due |
| MANUAL | When the season starts / Whenever it is due; plus Before the maintenance break when a break is set |
| YEAR_ROUND with a break | Before the maintenance break / After the maintenance break / Whenever it is due |

- On a meter-only schedule only "When the season starts" and "Whenever it is due" appear, with no offset.
- "When the season starts" adds "Start counting from": AT_START with 0–365 days after, or RESUME_CLAMPED.
- "Before…" adds "Days before it starts", 1–365, left empty until entered (S83).
- The helpers are S78–S82; the warnings are S76 and S84.
- An edit that would break a health link asks S140–S141 (§6.1).
- Groups get no question.

### 10.5 Maintenance tab

Rows carry their why-line (S85–S91). "Reminders" stays reminder health. RATIFIED (I-19, 2026-09-24): `HealthScreen`, `HealthFinding` and `Severity` become `ReminderHealth*`.

### 10.6 Accessibility — RATIFIED (I-15, I-18, 2026-09-24); Q-7, O-2

The three semantic families are approved (O-2): normal/operational/nominal in the D12 cool-blue family; degraded/warning in the warning/amber family, with DEGRADED on its own token distinguishable from Due and Due soon; down/critical in the error family. Colour is reinforcement only: word, icon or shape, and placement each distinguish the state on their own (D12 §5, §14).

| State | Word | Icon (proposed) | Family | Position |
|---|---|---|---|---|
| Operational | OPERATIONAL | `task_alt` | cool blue | plate |
| Degraded | DEGRADED | `trending_down` | warning/amber, own token distinct from Due and Due soon | ATTENTION, after due |
| Down | DOWN | `block` | error | ATTENTION, first |
| Not recorded | Condition not recorded | `radio_button_unchecked` | neutral | plate |
| Health | NOMINAL / WARNING / CRITICAL + score | `signal_cellular_alt` 3 / 2 / 1 bars | blue / amber / error | detail, badges, rows |
| Not tracked | NOT TRACKED | `signal_cellular_nodata` | neutral | detail |
| Deferred | DEFERRED | `hourglass_top` | season-inactive grey | Deferred section |
| In season | IN SEASON | `event_available` | cool blue | plate, season section |

Literal token values are design-token choices under the existing contrast requirements, not new product-state meanings (O-2).

### 10.7 Strings ledger (S1–S143 RATIFIED, 2026-09-24)

**Every row S1–S143 is RATIFIED as written (O-1 and O-9, 2026-09-24).** S143 arose from O-6 and is drawn while an occurrence is postponed and not yet late against its new date; otherwise the UI shows S103. `<…>` marks a substitution, and "(re)" marks a shipped word re-ratified in a new role. Shipped words reused unchanged (Name, Cancel, Not now, Maintenance, Open asset) are not listed.

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
| S27 | component line | <component> <DOWN/DEGRADED> — <reason, or S23 when empty> |
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
| S62 | helper | Maintenance set to follow the season, or to be ready before it, does not become due during the break. Work already overdue stays overdue, without reminders. |
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
| S78 | helper, before season | A date in the season or the maintenance break becomes due on an allowed day before the season starts. |
| S79 | helper, before break | A date in the maintenance break becomes due before the break starts. |
| S80 | helper, when season starts | This maintenance waits while the season is off and becomes active again when it starts. |
| S81 | helper, after break | A date in the maintenance break moves to the first day after it. |
| S82 | helper, whenever | The season and the maintenance break never change when this is due. |
| S83 | refusal | Enter the number of days. |
| S84 | warning | That is after the season ends, so this would never become due. |
| S85 | why-line | Held until <date> because of the maintenance break |
| S86 | why-line | Made due before the season starts |
| S87 | why-line | Made due before the maintenance break |
| S88 | why-line | Moved from <date> because the season was not running |
| S89 | why-line | Out of season until <date> |
| S90 | why-line | Out of season until you start it |
| S91 | why-line | Reminders wait for the maintenance break to end |
| S92 | status word | DEFERRED |
| S93 | section, chip | Deferred |
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
| S140 | link-guard dialog | This schedule drives the health subject <name>. Archive that subject as well? |
| S141 | link-guard confirm | Archive both |
| S142 | driver line | Not tracked: the linked schedule has no date rule or belongs to another asset. |
| S143 | driver line, postponed occurrence (O-9) | <schedule> was postponed to <date> |

The 143 ratified rows are 139 new plus 4 re-ratified (S29, S32, S33, S37); S123, S124, S128, S132 and S136 each ratify one set of words. The owner's notes are applied:
- Nothing implies all maintenance stops (S38, S43, S45, S62, S80).
- S43 says "becomes active again".
- Nothing claims work "is done".

Retired: "Pause with the asset's season", "Remind me year round", and "Off means this asset is only in use between two dates each year."

---

## 11. Invariants

Invariants continue from spec 1.2 (1–80); §12.1 names a layer and a test for each.

**Spec 1.2 invariants:**
- **25** is restated as 87.
- **26** is retired with `ScheduleStructuralTest.kt:71-94` and replaced by 84 and 104.
- **27** becomes 106.
- **10** stands for `effectiveDueOn`.
- **16:** `rebuild` also takes the season context.
- **17:** kept by 105.
- **22:** INACTIVE_SEASON, PAUSED and DEFERRED never notify, and nothing notifies while quiet.
- **23:** season, activation and break boundaries may change status with no history change, and the between-rebuilds exception is gone.
- **47:** DEFERRED and quiet items are `PARKED`.
- **62–64** are extended to format 8 and to health.

D5 §6's "nothing stored at season end" and "MANUAL_STARTUP deferred" are retired.

**Concepts.**
81. Only an explicit condition write inserts an `asset_condition` row; no health value, status, phase, event, completion or scan does.
82. Health writes nothing: no condition, lifecycle, schedule, policy, season or reminder state.
83. Condition changes nothing but its own history.
84. The policy never changes `computedDueOn`: for the same history, every policy yields CONTINUOUS's `computedDueOn`.
85. Every completion's `occurrence_on` is `computedDueOn`.
86. No policy, phase, break or activation writes an event, a closure or a schedule column, or creates a second occurrence.
87. `rule_changed_at` moves only when `ruleChanged` is true; policy, offset, title, lead and health links are not rule fields, and editing only them leaves `rule_changed_at`, `computedDueOn`, `O` and `actionableDueOn` unchanged and clears no postponement.

**Season.**
88. The `MM-DD` pair is non-null if and only if the mode is CALENDAR.
89. `asset_season_activation` is immutable: no `updated_at`, UPDATE or DELETE except the asset's CASCADE.
90. A MANUAL asset's phase at `d` is the latest row's action among rows with `occurred_on ≤ d`; rows are read only in MANUAL mode.
91. A repeated START/END, and a date after today or before the latest row, are refused and write nothing.
92. A switch into MANUAL writes exactly one activation row, in the same transaction; `manualPhase` elsewhere is refused.
93. No journal event changes a phase; the post-event offer writes only when accepted.
94. Out-of-season time creates no occurrence or backlog; re-entry leaves exactly one occurrence.

**Policy.**
95. The policy moves time-side dates only. A meter-only schedule may be dormant (IN_SERVICE) and quiet in the break, neither of which moves a date; PRE_SERVICE and a non-zero re-entry offset on it are refused; a crossed threshold is genuinely DUE, and dormancy or quiet affects only its surfacing and delivery.
96. CONTINUOUS: `actionableDueOn == postponedDueOn ?: computedDueOn` whatever the season or break.
97. AT_START moves a date to `s + offset` only when it is earlier; RESUME_CLAMPED yields `max(date, s)`; neither drags a later date back.
98. PRE_SERVICE keeps a raw due that is allowed and on or before `s − margin`; otherwise its point is the latest day of the pre-season window on or before `s − margin`, else that window's first day, else, only when no allowed pre-season day remains, the last suitable allowed day before that break, even in the previous season; a point later than `R` applies unconditionally; a point earlier than `R` applies only when `O` is before it, and otherwise `R` is kept if allowed or moved to the first allowed day after the break; no rule reads a category, template or name.
99. A future manual START is never a boundary, and a recorded START is the real cycle start; on YEAR_ROUND the break start is PRE_SERVICE's boundary; PRE_SERVICE is refused where no boundary exists; no season-mode or break change removes or re-kinds a boundary a PRE_SERVICE schedule uses (409 `SEASON_MODE_STRANDS_POLICY` / `BREAK_STRANDS_POLICY`).
100. Season start never completes, resets or hides pre-service work.
101. A postponed date is re-entered and moved out of a break like a raw due, never moved earlier, and never OVERDUE on the first in-season day.
102. `quiet` ⇔ `service_policy ≠ CONTINUOUS` and `T` in the break; work already late when the break opens is not moved, keeps its status and is quiet.
103. DEFERRED is decided after the worst-of fold from the time side alone, never notifies, never counts as due, and never masks a crossed meter.
104. No reminder code evaluates a season, break or policy.
105. No read path writes `schedule_state` or health; a stale or missing row is derived in memory.
106. A group-targeted schedule is CONTINUOUS only.

**Condition.**
107. `asset_condition` is immutable; current is the latest row by `(occurred_on, occurred_time with nulls first, created_at, id)`; no column stores it.
108. No UNKNOWN is stored.
109. Condition and activation `event_id`s are soft links: never an FK, never an owner.
110. Returning to OPERATIONAL inserts one row and leaves every earlier row byte-identical.

**Health.**
111. No health value is stored or exported.
112. MAINTENANCE_OVERDUE follows the actionable date via §7's counted days, never `computedDueOn` alone; no meter side contributes.
113. An AGE baseline is the latest qualifying REPLACEMENT; a deleted baseline profile leaves NOT TRACKED, never a widened filter.
114. A MEDIUM subject on an IN_SERVICE schedule is NOT TRACKED while dormant and counts from the latest cycle start; others freeze and carry.
115. Pre-service lateness counts from the pre-service point and is never reset by season start; break days count for already-late work and not for deferred work.
116. Counted days are measured against the actionable date as postponed (§7.1; semantics in 131). An implementation may begin evaluating from an earlier conservative bound, but after a postponement counted lateness is zero until the policy-derived postponed actionable date has passed; no day before that actionable date contributes to health (owner, 2026-09-24).
117. `score` is non-increasing; WARNING starts exactly at `t2`, CRITICAL exactly at `t3`.
118. An untracked subject is excluded from aggregation and shown as NOT TRACKED, never as 100.
119. Every CRITICAL contributor and every DOWN or DEGRADED in-service component is shown on every surface that shows health; a DOWN asset's health is never shown without its condition.
120. A schedule drives at most one non-archived subject; a subject never changes asset.
121. No threshold, margin or break date has a default; a template fills fields only after confirmation.

**Surfaces.**
122. A DOWN or DEGRADED in-service asset or component reaches ATTENTION with no schedule behind it, and names its parent.
123. The scan sheet opens only by `scanSheetContent`'s answer: a D-18a item, or DOWN/DEGRADED on the asset or a component; calculated health never opens it alone.

**Data and contract.**
124. A format ≤7 archive decodes through §4.1, with empty new lists and `ruleChangedAt = updatedAt`; a legacy field in format 8 is corrupt.
125. The migration and the decoder agree row for row and move no `updated_at`; a pre-upgrade format-7 export merges IDENTICAL.
126. Activations and conditions merge only as INSERT, IDENTICAL or CONTENT_DIFFERS; START, END and condition writes never change the asset row.
127. No route or tool amends or deletes a condition or activation; condition is in no asset command.
128. Legacy inputs, in the API and as MCP arguments, are translated only by §4.1 and never change 1.4-only state: such a write is 422 `LEGACY_WRITE_CANNOT_REPRESENT` and writes nothing; a mixed body is 422.
129. No 1.4 table, column or route names an installed component or assembly; nothing reads stock.
130. No schedule edit removes the time rule, changes the target asset or archives a schedule that a non-archived subject depends on unless the request unlinks it, which archives the subject in the same transaction.
131. Snooze delays only the reminder and never changes health; Postpone is a canonical reassessment of this occurrence's actionable due date and may restart maintenance-overdue health from it, with earlier lateness not retained; 1.4 keeps no postponement journal.

---

## 12. Test matrix

There are no `uiautomator`/`adb` journeys, and no test waits on a real delay: every date is an injected `T`.

### 12.1 Invariant → layer → test

| Inv. | Layer | Named test |
|---|---|---|
| 81–83, 86 | JVM | `CrossConceptWriteTest`: every 1.4 use case and handler against a recording store |
| 84, 85, 96 | JVM property | `PolicyInvarianceTest` |
| 87 | JVM, through SaveSchedule | `SaveScheduleRuleFieldTest`: never-terminated FIXED, anchor 10 Jan, created 1 Jan, title/policy/lead edits on 1 Mar leave `computedDueOn` 10 Jan |
| 88, 92, 99 (refusals) | JVM | `SeasonModeCommandTest`: `manualPhase` edges; CALENDAR → YEAR_ROUND or MANUAL, and break-only → CALENDAR, refused while PRE_SERVICE depends (RS-4, RN-6); the legacy pair likewise |
| 89, 127 | structural | no update/delete DAO for facts; `theDestructiveUseCasesHaveNoRoute` |
| 90, 91, 93 | JVM | `ManualSeasonTest`, `SeasonEventOfferTest` |
| 94 | JVM property | no backlog across dormancy |
| 95, 103 | JVM | `MeterSidePolicyTest`: meter-only IN_SERVICE is phase-only (INACTIVE_SEASON, quiet, no date); PRE_SERVICE and non-zero offset refused; a combined held time side with a crossed meter reads DUE |
| 97, 98, 100 | JVM | `ServicePolicyEvaluatorTest`, with literal values: snowblower 1 Nov; mower 5 Nov kept; mower 12 Jan → 1 Apr 2027; break to 04-05 → 6 Apr 2027; in-season 20 Apr → 1 Apr; whole-gap break → the day before it; the §4.3 guard values; **#14 AC 4:** CALENDAR 10-15 → 04-15, every 3 days, done 13 Apr: INACTIVE_SEASON on 1 Jul, DUE on 15 Oct |
| 101 | JVM | `PostponementPolicyTest` |
| 102 | JVM | `BreakTest` |
| 104 | structural | anchored grep: `reminders/` never calls `Season.inSeason` or reads `MM-DD` or break columns |
| 105 | JVM + structural | `ReadPathsWriteNothingTest` |
| 106 | JVM | group refusals, both forms |
| 107–110 | JVM | `ConditionHistoryTest`; merge suite |
| 111 | structural | no health entity, column or DTO value |
| 112–118 | JVM | `HealthEngineTest`: score edges; the day-loop oracle; the §7 and §12.4 values; profile deletion |
| 119, 122, 123 | JVM + Compose | `ScanSheetContentTest` (AVERAGE NOMINAL plus one CRITICAL shows S109; DOWN child; health alone opens nothing); `AttentionReadModelTest` |
| 120, 121 | JVM + Compose | subject refusals; thresholds required; Save disabled until filled |
| 124–126 | JVM | backup round trip; every mapping row; merge verdicts, SKIPPED arm |
| 125 | structural | `MigrationTest` 7→8 on a real-shaped fixture; `foreign_key_check`; `8.json` |
| 128 | JVM + pytest | `LegacyFormTest`; MCP deprecated arguments translate and refuse identically; golden shapes; the loader re-plans IDENTICAL against a 1.4 row |
| 129 | structural | anchored grep: no assembly/installed-component names, no stock reads |
| 130 | JVM | `ScheduleHealthLinkGuardTest`: removing the time rule, retargeting or archiving is refused; the flag archives the subject atomically; a merged dangling link reads NOT TRACKED (S142) |
| 131 | JVM | `PostponeSnoozeHealthTest`: snoozing a CRITICAL item leaves its health unchanged; postponing restarts it from the postponed actionable date; no event is written |

### 12.2 Compose instrumented

In-process: the scan layouts, lines, confirm and cancel; asset detail per mode; the editors (S35, the hidden question, empty offsets, template confirmation, the link-guard dialog); dashboard order and chips; grayscale (no two states share a word and icon).

### 12.3 Boundary and release

There is no new external-boundary test: 1.4 adds no UID, intent or grant boundary.

`docs/release-proofs.md` R7 installs 1.4.0 over 1.3.0 in place and also checks equal counts, CALENDAR if and only if both `MM-DD` were set, no break, the §4.1 mapping, empty new lists, and that the pre-upgrade format-7 export merge-plans IDENTICAL.

**Controller note (2026-09-25):** the pre-upgrade format-7 export proof named above was replaced by ruling (plan review I1 / concern 2): the development phone's data survival is proven by API counts and per-table hashes before and after the upgrade (added or normalised keys compared through the release's documented mapping), the loader's IDENTICAL re-plan, and the emulator's preserved-set restore. No export is requested.
### 12.4 The five fixtures (fictional; every value entered)

| Fixture | Configuration | Expected at `T` = 2026-09-24 |
|---|---|---|
| F1 UPS (#61's master-bedroom role) | DOWN since 20 Sep, "Battery failed — replacement pending"; "Battery age" PART, AGE 365 / 1095 / 1460, REPLACEMENT 2022-06-01 | `x` 1576 → **18 CRITICAL**; a scan opens the sheet with nothing due; a REPLACEMENT on 27 Sep → 100 and the offer; accepting keeps the DOWN row |
| F2 battery pack | "Battery age" ASSET, same thresholds, profile "Battery replaced", REPLACEMENT 2023-05-10; INSPECTION load test | `x` 1233 → **47 WARNING** (WARNING from 9 May 2026, CRITICAL from 9 May 2027); a load test changes nothing; deleting the quick action → NOT TRACKED |
| F3 generator | §7.4 | 37 WARNING, DEGRADED, OVERDUE |
| F4 snowblower, mower | §7.3 | snowblower OK (DUE SOON from 18 Oct); mower `R` 5 Nov OK; the January mower DEFERRED from 29 Dec, DUE 1 Apr |
| F5 hot tub | §7.2 | OUT OF SEASON, Water care NOT TRACKED |

---

## 13. Acceptance-criteria trace

| AC | § | AC | § |
|---|---|---|---|
| #14-1 | 3.5, 4.3, 7.2 | #61-6 | 10.2, inv. 122 |
| #14-2 | 3.1, 4.1 | #61-7 | 5.5 |
| #14-3 | 4.1, 4.4, 7.3 | #61-8 | 8.3, 8.4 |
| #14-4 | 3.5, 4.3, 12.1 | #61-9 | 9.1, 9.4, inv. 127 |
| #14-5 | 3.5, inv. 94 | #61-10 | 10.6, 12.2 |
| #14-6 | 3.1, 12.1 | #61-11 | 6, inv. 82 |
| #14-7 | 3.1–3.4 | #61-12 | 6.4, 12.4 |
| #14-8 | 3.3, 3.4, 4.3, 7.2 | #61-13 | 6.3, 7 |
| #60-1 | 4.3, 4.4, 7.3 | #61-14 | 2, inv. 82 |
| #60-2 | 4.3, 7.3 | #61-15 | 6.6, 10.6 |
| #60-3 | 4.1, 7.3 | #61-16 | 6.7, 8.3, 9.1 |
| #60-4 | 4.1, inv. 96 | #61-17 | 6.1 |
| #60-5 | 2, 3.2, 4.4, inv. 99 | #61-18 | 7.2 |
| #60-6 | inv. 84–87, 94 | #61-19 | 6.6 |
| #60-7 | 4.7, inv. 104–105 | #61-20 | 6.1, inv. 120, 130 |
| #60-8 | arch. §C, 7, 12.4 | #61-21 | 6.5 |
| #60-9 | 3.3, 4.3, 7.2 | #61-22 | 6.5 |
| #60-10 | 4.1 | #61-23 | 6.5, 10.1, inv. 119 |
| #60-11 | 4.3, 7.2, 7.3 | #61-24 | 6.2, 7.1, inv. 112 |
| #61-1 | 5, inv. 81, 83 | #61-25 | 7.1, 7.2 |
| #61-2 | 5.2, 12.4 | #61-26 | 7.3, inv. 115 |
| #61-3 | 10.1 | #61-4 | 10.1, inv. 81 |
| #61-5 | 5.1, inv. 110 | | |

---

## 14. Dispositions

arch. §I is recorded in §0 and by the inline markers. Of the review's findings, rev2 closed 31 of 32 and rev2.1 closed the rest (RB-1, RS-1–RS-4, RN-1–RN-10). Departures kept, each accepted by the re-review or ruled by the owner: S-1 (no postponement journal, O-6), S-12 (a soft link, not a 409), N-5 (a bound and a warning, not a refusal), N-13 (moot), N-15 (the F1 label), and RS-4 as 409 (O-3).

---

## 15. Seams and version

**Seams:**
- **#47** gets no row or column (Q-8); child assets are today's components.
- **#44** keeps append-only facts and remaps `asset_id`.
- **#15** gets no stock read.
- **#45 / #9:** no provider id; `Parked` also carries break suppression.
- **#27** is renamed only.
- **#64** is fixed by D-28.

**Version (D-27): `versionName` 1.4.0, `versionCode` 16, a MINOR.**
- It is a new capability.
- Schema 8 and format 8 are forward-only: 1.4 reads formats 1–8, and 1.3.x refuses format 8 through `BackupNewerFormat`. That is `docs/versioning.md`'s MINOR condition.
- `/v1` stays backward compatible. Every 1.3 request keeps its meaning on every row a 1.3 body can describe, and legacy inputs pass through the migration's own mapping. A write that could only erase state a 1.3 client cannot see is refused by name.
- Responses gain fields, one status value, and a nullable `seasonBehavior`. `effectiveDueOn` keeps its meaning.
- No archive stops importing, and no stored data becomes unreadable.

---

## Appendix A. For the plan (non-normative)

**MCP:** `update_schedule`/`update_asset` enumeration (`server.py:1359-1395`); `create_asset`, `create_component` (`:475-524`) and `update_asset` keep the season pair; `_ASSET_NULLABLE_CLEARABLE` (`:359-361`) keeps it; `_SCHEDULE_NULLABLE_CLEARABLE` gains `policy_offset_days` and keeps the deprecated triple; the status docstring (`:1168`) gains DEFERRED.

**Loader:** `apply.py:95-105` writes `season_behavior` through the deprecated argument; `phone.py:54-60, 107, 167` gains `servicePolicy`; `plan.py:151-158, 227-230` compares through §4.1; `manifest.py` is unchanged.

**Docs:** D5 §6 and §10.4 (AT_START, manual activation, retired statements); D12 §5; `docs/api/v1.md` (routes, codes, the tie-break, "Deprecated inputs" with the RESUME_CLAMPED omission and nullable `seasonBehavior`, `actionableDueOn`, DEFERRED, the postpone/snooze health note of §9.1, "What has no endpoint", counts); the MCP README; `docs/versioning.md`.
