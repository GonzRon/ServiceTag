# #78 — reconcile existing maintenance timing when an asset becomes seasonal: plan and brief (rev 2, rulings ratified 2026-09-25)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the
> review budget in `docs/superpowers/planning-policy.md`. One brief (§8), one implementer. The rulings in §6
> and the strings in §5 were ratified by the owner on 2026-09-25 (rev 2 records them). Planned from issue #78's body; it does not reopen
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
  phase, and every schedule on it with `servicePolicy`, `status` and the derived state. **Done 2026-09-25 (read-only, through the API on the unlocked development phone):** the case asset is the
  only MANUAL asset on the phone; its phase is OUT_OF_SEASON (one END activation dated 2026-09-25); it has
  two ACTIVE schedules, both `servicePolicy = CONTINUOUS`, offset null: a weekly FIXED rule (status DUE,
  `computedDueOn` 2026-09-25, `policyPhase` ACTIVE, never completed) and a two-yearly FIXED rule (status OK,
  due 2027-09-17). So the engine behaves as §4 specifies — a CONTINUOUS schedule ignores the season — and
  #78 is the UX gap, not a status defect. No bug is split out.
- **AC 2.** (Not the case here.) If a schedule on that asset is `IN_SERVICE_AT_START` / `IN_SERVICE_RESUME_CLAMPED` and still
  reads DUE while the MANUAL season is ended, that is a 1.4 status defect: it is split into its own
  `[NEXT-1][BUG]` and fixed first, before this enhancement. If every DUE schedule there is `CONTINUOUS`,
  the engine behaves as specified and this plan proceeds as a UX gap. Either way a core test pins AC 2
  (MANUAL asset, END recorded, an IN_SERVICE schedule with an open occurrence → `INACTIVE_SEASON`; a
  `CONTINUOUS` schedule beside it → still DUE), added in this brief if none exists.

## 2. The behaviour (the brief's contract)

- **Trigger.** In the asset editor's save, after `SaveAssetSettings` succeeds (the save is already
  written; spec §10.4: one `uow.write`), when the stored mode **before** the save was `YEAR_ROUND` and the
  mode **after** it is `CALENDAR` or `MANUAL`, and the asset has at least one **live** schedule with
  `servicePolicy == CONTINUOUS`. "Live" = not ARCHIVED: ACTIVE and PAUSED both count (R-1: this reconciles persisted policy intent, and a paused schedule regains the same behaviour when resumed).
  Nothing else triggers it: CALENDAR ↔ MANUAL, a window edit, a break edit, a manual START/END on asset
  detail, and any save of a seasonal asset that stays seasonal never prompt (AC 5, no nagging).
- **The prompt.** A dialog over the editor, drawn once per qualifying save, with the ratified body
  (P78-1a/1b by count) and two buttons: **Review** (P78-2) and **Keep schedules as-is** (P78-3). No title (R-2). It writes nothing. Dismissing it by the system back gesture counts as Keep as-is.
- **Keep schedules as-is.** The asset's season change is already saved; this button only declines schedule changes. The editor finishes exactly as today (the `_saved` signal, the screen closes). Nothing is
  remembered: the transition itself is the one-time gate, so the owner is not asked again unless the
  asset later goes year-round and seasonal once more (R-3).
- **Review.** The editor finishes and the app opens the asset's detail with its maintenance schedules in
  view (the existing `SchedulesSection`), where each schedule opens the existing schedule editor, which
  now draws the 1.4 question "When should this maintenance be done?" because the asset has a season.
  No new list screen, no bulk editor, no per-schedule checkboxes, no mass conversion (R-4).
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

## 5. Strings — RATIFIED (owner, 2026-09-25; the rev 1 "will stay due" wording was rejected because a CONTINUOUS schedule may be not yet due and PAUSED ones count)

| # | surface | text |
|---|---|---|
| P78-1a | dialog body, n ≠ 1 | `This asset has <n> maintenance schedules that are not tied to its operating season. When active, they can become or remain due while the asset is out of season unless you change when that maintenance should be done.` |
| P78-1b | dialog body, n = 1 | `This asset has 1 maintenance schedule that is not tied to its operating season. When active, it can become or remain due while the asset is out of season unless you change when that maintenance should be done.` |
| P78-2 | button | `Review maintenance schedules` |
| P78-3 | button | `Keep schedules as-is` |

No title, no third line. `<n>` is the count of live `CONTINUOUS` schedules (ACTIVE and PAUSED).

## 6. Rulings — RATIFIED (owner, 2026-09-25)

- **Diagnostic:** AC 1 proves the observed case is not a status/recompute defect (MANUAL, OUT_OF_SEASON, both schedules CONTINUOUS; the weekly one DUE is correct). No bug is split out; the AC 2 core regression test stays in the brief.
- **R-1:** count every non-ARCHIVED `CONTINUOUS` schedule, PAUSED included.
- **R-2:** the dialog has no title.
- **R-3:** no persisted "asked" flag; YEAR_ROUND → CALENDAR/MANUAL is the one-time gate; Keep means no repeated prompt while the asset stays seasonal; a later YEAR_ROUND → seasonal transition may prompt again.
- **R-4:** Review opens the existing asset-detail maintenance schedules section; no bulk editor, checkboxes or mass conversion.
- **R-5:** #78 changes only the asset editor's reconciliation UX (with the minimal navigation the Review needs) plus the AC 2 proof; the scheduling engine, `SaveAssetSettings`, the schedule editor's policy semantics, the API, the MCP, the schema, the backup format, occurrence identity and history are untouched.

## 7. What this plan does not do

No version bump; no manifest, API or MCP change; no automation-created data changes; no reminder
architecture change (#83's territory); no inference of policy from the asset's category.

## 8. The brief (one implementer)

**Read first:** §1–§7 above; spec §3.2, §3.5, §4.1, §10.4; issue #78's invariants and AC 1–10.
**Lane:** alone, branched from master after the plan's rev 2 commit. **Blocked on:** nothing.

### Files

**Modify**
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/asset/AssetViewModels.kt` — `AssetEditViewModel`: one constructor parameter `schedules: ScheduleRepository` (the `graph` constructor passes `graph.schedules`); a prompt state; `commit()`'s success branch; two answers.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/asset/AssetEditScreen.kt` — the four `const val` strings beside the existing S-string constants; the dialog; a new `onReviewSchedules: (String) -> Unit = {}` parameter.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/Route.kt` — `AssetDetail(val id: String, val section: String? = null)`.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/ServiceTagRoot.kt` — the `AssetEdit` entry wires `onReviewSchedules`; the `AssetDetail` entry passes `key.section`.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/asset/AssetDetailScreen.kt` — a `section: String? = null` parameter; when it is `"schedules"`, the screen scrolls its `verticalScroll` state once so `AssetMaintenanceSections` is in view (the implementer chooses the mechanism, e.g. the section's measured offset; no new composable).
- Tests: `app/src/test/…/ui/asset/AssetViewModelsTest.kt` (the construction helper at `:112` gains `graph.schedules`; the new cases), `app/src/androidTest/…/ui/asset/AssetEditorSeasonAndHealthTest.kt` (the dialog), `app/src/androidTest/…/ui/asset/AssetDetailConditionHealthSeasonTest.kt` (the Review landing), `core/src/test/…/core/schedule/ScheduleStatusTest.kt` (AC 2; if `NoBacklogAcrossDormancyTest` already pins the exact case, cite it instead of adding).

**Untouched:** `SaveAssetSettings.kt`, `RecomputeSchedules.kt`, `ServicePolicy.kt`, `ScheduleEditViewModel.kt` / `ScheduleEditScreen.kt`, `SchedulesSection.kt`, the API, the MCP, the loader, the schema, the backup format, `AppGraph.kt`, `FakeGraph.kt`, every ratified string.

### Interfaces

```kotlin
sealed interface EditPrompt { data class ReconcileSchedules(val count: Int) : EditPrompt }   // in AssetViewModels.kt
class AssetEditViewModel(assets, healthSubjects, saveAssetSettings, schedules: ScheduleRepository, id, presetParentId)
    val prompt: StateFlow<EditPrompt?>          // null when nothing is asked
    val saved: SharedFlow<AssetId>              // unchanged: the editor is done, close it
    val review: SharedFlow<AssetId>             // new: the editor is done, open the schedules
    fun keepSchedules()                          // Keep schedules as-is, and the back gesture
    fun reviewSchedules()                        // Review maintenance schedules
const val NOT_TIED_TO_SEASON = "…P78-1a…"; const val NOT_TIED_TO_SEASON_ONE = "…P78-1b…"
const val REVIEW_MAINTENANCE_SCHEDULES = "Review maintenance schedules"; const val KEEP_SCHEDULES_AS_IS = "Keep schedules as-is"
fun notTiedToSeason(count: Int): String        // P78-1b for 1, else P78-1a with <n>
```

### Contracts

- **C1, the gate.** In `commit()`'s success branch, before `_saved`: if `form.storedSeasonMode == YEAR_ROUND` and `form.seasonMode` is `CALENDAR` or `MANUAL` (the form's own fields; no re-read of the asset), read `schedules.forAsset(id)` and count rows with `status != ARCHIVED && servicePolicy == CONTINUOUS`. If the count is > 0, publish `EditPrompt.ReconcileSchedules(count)` and **do not** emit `_saved` yet. Otherwise emit `_saved` as today. A new asset (`id == null`) has no schedules and never prompts. A refused save never prompts.
- **C2, the answers.** `keepSchedules()` clears the prompt and emits `_saved`. `reviewSchedules()` clears the prompt and emits `review`. Neither writes anything; neither runs a use case.
- **C3, the screen.** `prompt` non-null draws an `AlertDialog` with no title, `notTiedToSeason(count)` as text, `REVIEW_MAINTENANCE_SCHEDULES` as the confirm button and `KEEP_SCHEDULES_AS_IS` as the dismiss button; `onDismissRequest` (the back gesture / outside tap) calls `keepSchedules()`. `saved` collects to `onDone` as today; `review` collects to `onReviewSchedules`.
- **C4, navigation.** `ServiceTagRoot`'s `AssetEdit` entry: `onReviewSchedules = { id -> backStack.removeLastOrNull(); if the new last entry is `Route.AssetDetail` for the same id, replace it with `Route.AssetDetail(id, "schedules")`, else add `Route.AssetDetail(id, "schedules")` }` — so the stack never holds two details of one asset. `AssetDetailScreen(section = "schedules")` scrolls once, on first composition, so `AssetMaintenanceSections` is visible; `section` is read once and never re-triggers on recomposition or rotation of the same entry.
- **C5, nothing written.** Around the whole flow the schedule rows, events, closures and activation rows are byte-identical (the save itself already ran; the prompt and its answers add nothing).
- **C6, the count and the words.** `<n>` = the live CONTINUOUS count; `notTiedToSeason(1)` is P78-1b verbatim; any other count is P78-1a with `<n>` substituted.
- **C7, AC 2.** A core test: a MANUAL asset with an END activation, an `IN_SERVICE_AT_START` schedule with an open occurrence → `INACTIVE_SEASON`; a `CONTINUOUS` schedule beside it → `DUE`. Add it only if no existing engine test pins exactly that pair; the report names the test either way.

### Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| a silent rewrite | `AssetViewModelsTest` · `theSeasonPromptWritesNothing`: after Keep and after Review every schedule row equals its before-state (a `[LOCAL]`-row, profile and `updatedAt` included); event/closure/activation counts unchanged | write a policy in `reviewSchedules()` |
| the trigger | `AssetViewModelsTest` · `theSeasonPromptTriggerTable`: YEAR_ROUND→MANUAL with one ACTIVE CONTINUOUS → prompt(1); with one PAUSED CONTINUOUS → prompt(1); YEAR_ROUND→CALENDAR → prompt; CALENDAR→MANUAL → none; MANUAL→MANUAL → none; YEAR_ROUND→MANUAL with only IN_SERVICE / PRE_SERVICE rows → none; an ARCHIVED CONTINUOUS only → none; a mixed asset (2 CONTINUOUS + 1 IN_SERVICE) → prompt(2); a refused save → none | drop the `storedSeasonMode` check; count ARCHIVED; count IN_SERVICE |
| the answers | `AssetViewModelsTest` · `keepSchedulesFinishesTheEditor` (`saved` fires, `review` does not) and `reviewSchedulesOpensTheSchedules` (`review` fires, `saved` does not); the prompt is null after each | swap the signals |
| the words | `AssetViewModelsTest` · `theSeasonPromptWordsAreTheRatifiedOnes`: `notTiedToSeason(1)` == P78-1b, `notTiedToSeason(3)` == P78-1a with 3; the button constants verbatim | a typo |
| the dialog | `AssetEditorSeasonAndHealthTest` · `switchingAYearRoundAssetToManualAsksAboutItsContinuousSchedules`: an asset with one CONTINUOUS schedule; choose Started and ended by hand, answer S35, Save; P78-1b and both buttons displayed; tap Keep → the editor closes; and `…theBackGestureKeeps`: press back on the dialog → the editor closes, nothing written | drop the dialog |
| Review lands on the schedules | `AssetDetailConditionHealthSeasonTest` · `reviewMaintenanceSchedulesOpensTheAssetsSchedules`: tap Review → asset detail with the maintenance sections displayed, and one detail entry for the asset on the stack | ignore `section` |
| AC 2 | `ScheduleStatusTest` · `aManualAssetThatEndedMakesAnInServiceScheduleInactiveAndLeavesAContinuousOneDue` (or the cited existing case) | flip the policy branch |

### Gate

- `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --console=plain`: zero failures, zero skips; the report quotes counts before and after.
- Connected, on `emulator-5554` only, one class per invocation: `com.loosecannon.servicetag.ui.asset.AssetEditorSeasonAndHealthTest`, then `com.loosecannon.servicetag.ui.asset.AssetDetailConditionHealthSeasonTest`.
- Anchored `git grep -nE` over `app/src/main`: `'^const val KEEP_SCHEDULES_AS_IS = "Keep schedules as-is"$'` → 1; `'^const val REVIEW_MAINTENANCE_SCHEDULES = "Review maintenance schedules"$'` → 1; `'maintenance schedules that are not tied to its operating season'` → 1; `'maintenance schedule that is not tied to its operating season'` → 1; `'servicePolicy = '` under `app/src/main/kotlin/com/loosecannon/servicetag/ui/asset` → the same lines as at the base (the editor writes no policy).
- `git diff <base> -- core/src/main tools docs/api app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance app/src/main/kotlin/com/loosecannon/servicetag/di app/src/main/AndroidManifest.xml` → empty.
- Hygiene; gitlink `7e0377a`; `git status` clean.

### Strings

P78-1a, P78-1b, P78-2, P78-3 verbatim; S35 and the existing editor labels as they are. Nothing else. A state that seems to need words is NEEDS_CONTEXT.

### Must NOT

Write a `servicePolicy`, an event, a closure, an activation or any schedule field; change `SaveAssetSettings`, the engine, the schedule editor, the API, the MCP, the schema or the backup format; add a preference or column for "asked"; add a bulk action, a checkbox list or a new screen; prompt on any transition but YEAR_ROUND → CALENDAR/MANUAL; drive the UI outside the Compose test APIs; use any device but `emulator-5554`.

### Review focus

The gate reading the form's stored mode (not a re-read after the save); PAUSED counted, ARCHIVED not, IN_SERVICE/PRE_SERVICE not; `_saved` withheld only while the prompt is up and always emitted afterwards; the back gesture as Keep; the stack invariant in C4; nothing written (the byte-identical assertion); AC 2 named.

### Size

Small to medium: one view model, one screen dialog, one route field, one scroll, four test classes.
