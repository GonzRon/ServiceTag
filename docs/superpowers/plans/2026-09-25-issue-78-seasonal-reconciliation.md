# #78 — reconcile existing maintenance timing when an asset becomes seasonal: plan (rev 1, for the owner's rulings)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the
> review budget in `docs/superpowers/planning-policy.md`. One brief, one implementer, after the
> rulings in §6 and the strings in §5 are settled. Planned from issue #78's body; it does not reopen
> #14's / #60's separation of operating season and service policy.

**Goal:** when the owner changes an asset from year-round operation into a seasonal mode
(YEAR_ROUND → CALENDAR or MANUAL) and the asset has live schedules whose policy is `CONTINUOUS`
("Whenever it is due"), the editor says so once and offers a review; nothing is rewritten silently,
no completion or history row is fabricated, and a `CONTINUOUS` schedule may stay `CONTINUOUS` without
being asked again.

**Spec:** `docs/superpowers/specs/2026-09-24-servicetag-1.4-seasons-policy-condition-health.md` §3.2
(changing the season), §3.5 (INACTIVE_SEASON: an IN_SERVICE schedule whose asset is OUT_OF_SEASON keeps its
occurrence, computes its date, notifies nothing), §4.1 (the policy vocabulary), §10.4 (the editors: the
schedule editor's question "When should this maintenance be done?" appears only when the asset has a
season or a break). Issue #78's invariants and acceptance criteria 1–10.

## 1. Diagnosis first (AC 1–2; controller, read-only)

- **AC 1.** Read the owner's case on the development phone through the API: the asset's `seasonMode` and
  phase, and every schedule on it with `servicePolicy`, `status` and the derived state. **Pending:** the
  phone was dozing on its lock screen when the read was attempted (2026-09-25); it needs to be unlocked.
- **AC 2.** If a schedule on that asset is `IN_SERVICE_AT_START` / `IN_SERVICE_RESUME_CLAMPED` and still
  reads DUE while the MANUAL season is ended, that is a 1.4 status defect: it is split into its own
  `[NEXT-1][BUG]` and fixed first, before this enhancement. If every DUE schedule there is `CONTINUOUS`,
  the engine behaves as specified and this plan proceeds as a UX gap. Either way a core test pins AC 2
  (MANUAL asset, END recorded, an IN_SERVICE schedule with an open occurrence → `INACTIVE_SEASON`; a
  `CONTINUOUS` schedule beside it → still DUE), added in this brief if none exists.

## 2. The behaviour (the brief's contract)

- **Trigger.** In the asset editor's save, after `SaveAssetSettings` succeeds (the save is already
  written; spec §10.4: one `uow.write`), when the stored mode **before** the save was `YEAR_ROUND` and the
  mode **after** it is `CALENDAR` or `MANUAL`, and the asset has at least one **live** schedule with
  `servicePolicy == CONTINUOUS`. "Live" = not ARCHIVED (ruling R-1 decides whether PAUSED counts).
  Nothing else triggers it: CALENDAR ↔ MANUAL, a window edit, a break edit, a manual START/END on asset
  detail, and any save of a seasonal asset that stays seasonal never prompt (AC 5, no nagging).
- **The prompt.** A dialog over the editor, drawn once per qualifying save, with the ratified body
  (P78-1a/1b by count) and two buttons: **Review** (P78-2) and **Keep as-is** (P78-3). No title
  (ruling R-2). It writes nothing. Dismissing it by the system back gesture counts as Keep as-is.
- **Keep as-is.** The editor finishes exactly as today (the `_saved` signal, the screen closes). Nothing is
  remembered: the transition itself is the one-time gate, so the owner is not asked again unless the
  asset later goes year-round and seasonal once more (ruling R-3).
- **Review.** The editor finishes and the app opens the asset's detail with its maintenance schedules in
  view (the existing `SchedulesSection`), where each schedule opens the existing schedule editor, which
  now draws the 1.4 question "When should this maintenance be done?" because the asset has a season.
  No new list screen, no bulk editor, no per-schedule checkboxes in this brief (ruling R-4).
- **What never happens.** No `servicePolicy` is written by the asset editor; no occurrence is deleted,
  archived, completed or re-anchored; no event, closure or activation row is written by the prompt; a
  MANUAL season that starts again re-enters under §4's rules with no off-season backlog (already 1.4
  behaviour; pinned by the AC 2 test). Mixed-policy assets stay supported: the count in P78-1 is the
  `CONTINUOUS` subset only.

## 3. Files (indicative; the implementer owns the placement)

Modify `app/…/ui/asset/AssetViewModels.kt` (`AssetEditViewModel.commit`: the mode-before/after
comparison, the schedule read through the existing `ScheduleRepository`, a new editor prompt state) and
`AssetEditScreen.kt` (the dialog; the Review navigation through the existing `onSaved` route with a
"show schedules" hint, or the route the screen already has to asset detail — the implementer reads
`ServiceTagRoot` for the shape); tests `AssetEditViewModelTest` (JVM) and `AssetEditorSeasonAndHealthTest`
(Compose, connected); `core` test for AC 2 beside the existing season-policy engine tests. Untouched:
`SaveAssetSettings`, `RecomputeSchedules`, the schedule editor, the API, the MCP, the schema, the backup
format.

## 4. Test matrix (hazards; the brief fixes names)

| hazard | proof |
|---|---|
| a silent rewrite | after Review or Keep as-is, every schedule row is byte-identical (JVM) |
| the wrong trigger | YEAR_ROUND→MANUAL with a CONTINUOUS schedule → prompt; YEAR_ROUND→CALENDAR → prompt; CALENDAR→MANUAL → none; MANUAL→MANUAL phase change → none; YEAR_ROUND→MANUAL with only IN_SERVICE/PRE_SERVICE schedules → none; an ARCHIVED CONTINUOUS one → none; a mixed asset → prompt counting only the CONTINUOUS ones (JVM) |
| the count and the singular form | 1 → P78-1b, n → P78-1a (JVM, Compose) |
| Keep as-is | the save signal fires, no navigation hint, nothing written (JVM) |
| Review | the save signal fires with the schedules hint; asset detail shows the schedules (Compose) |
| back gesture | counts as Keep as-is (Compose) |
| AC 2 | the core engine test above |
| a fabricated row | events, closures and activations counts unchanged around the whole flow (JVM) |

## 5. Strings to ratify

| # | surface | proposed text |
|---|---|---|
| P78-1a | dialog body, n ≠ 1 | `This asset has <n> maintenance schedules that run year-round. They will stay due while the asset is out of season unless you change when that maintenance should be done.` |
| P78-1b | dialog body, n = 1 | `This asset has 1 maintenance schedule that runs year-round. It will stay due while the asset is out of season unless you change when that maintenance should be done.` |
| P78-2 | button | `Review maintenance schedules` |
| P78-3 | button | `Keep as-is` |

Voice as 1.4 §10.7: plain, no jargon; "when that maintenance should be done" echoes the schedule editor's
own question. No title, no third line.

## 6. Rulings needed before the brief is written

- **R-1.** Do PAUSED `CONTINUOUS` schedules count toward the prompt (they become due again when resumed)?
  Proposed: yes; ARCHIVED never.
- **R-2.** A dialog with no title (proposed), or a title such as `Maintenance timing`?
- **R-3.** Keep as-is remembers nothing; the transition is the only gate (proposed). Alternative: a
  per-asset "asked" flag — rejected here because it would need a column or preference and a way to reset it.
- **R-4.** Review opens the asset's existing schedules section (proposed) rather than a new bulk review
  screen; a bulk "set all to …" action is out of scope.
- **R-5.** Scope confirmation: no change to `SaveAssetSettings`, the engine, the schedule editor or the API;
  the prompt is the asset editor's alone. If AC 2's diagnosis finds a defect, it is a separate bug first.

## 7. What this plan does not do

No version bump; no manifest, API or MCP change; no automation-created data changes; no reminder
architecture change (#83's territory); no inference of policy from the asset's category.
