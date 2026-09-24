# B13 — the dashboard and the Maintenance rows

**Read first:** master plan §1, §13.1 (the read models), §13.2 (the dashboard), §13.3 (the why-line), §14, §19.
**Spec:** §4.5 (Deferred), §10.2 (the dashboard), §10.5 (the Maintenance tab), §10.6; strings S26, S85–S91; inv. 103, 119, 122; spec 1.2 inv. 75.
**Wave 8, lane A,** beside B14. **After B07 and B12.** Device first in wave 8.

## Goal

Make the landing screen answer "what needs me" across all three kinds of fact without mixing them. ATTENTION lists DOWN assets and components first, then the due schedules, then DEGRADED assets and components, then independent CRITICAL health; UPCOMING adds independent WARNING health after the DUE SOON rows; a quiet **Deferred** section sits between CURRENT and OUT OF SEASON. A component is promoted with its parent named, and only in-service rows appear. Maintenance-overdue health rides its schedule's row. Every schedule row — on the dashboard and on the Maintenance tab — carries one **why-line** (S85–S91) saying why its date is what it is. The filters gain DEFERRED and a row of condition chips whose meaning is exact. Everything is drawn from B07's projections with B12's words, badges and tokens; this brief invents no ordering and no word.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/WhyLines.kt` — `whyLine(item: DueItem, format: (LocalDate) -> String): String?` (S85–S91).
- Tests: `app/src/test/kotlin/com/loosecannon/servicetag/ui/maintenance/WhyLinesTest.kt`, `app/src/test/.../ui/dashboard/DashboardFiltersTest.kt`.

**Modify**

- `app/.../ui/dashboard/DashboardViewModel.kt` — reads `AttentionReadModel.items()` beside `DueReadModel.items()` and assembles the sections (below); `DashboardState` gains the asset-level rows and the condition chips; an asset drawn as any row in a section is not repeated in the plain asset list (the shipped "an asset appears exactly once", extended).
- `app/.../ui/dashboard/DashboardScreen.kt` — the condition row (`ConditionBadge`, the reason or S23, the parent named), the health row (S110 with `HealthBadge`), the Deferred section.
- `app/.../ui/dashboard/DashboardFilters.kt` — `DEFERRED` in `FILTERABLE_STATUSES`; `DashboardFilters.conditions: Set<ConditionChip>`; the chip row.
- `app/.../ui/maintenance/DueItemRow.kt` — the why-line under a row; the health passenger (S110) when `DueItem.health` is set. **The public signature of `DueItemRow` is unchanged** — both additions are read from `DueItem` — so its other call sites (`AssetMaintenanceSections.kt:70`, B14's in this wave; `CompletionFlow.kt:372`; `DashboardScreen.kt:188`; `SchedulesSection.kt:43`) need no edit.
- `app/.../ui/maintenance/MaintenanceScreen.kt`, `MaintenanceViewModel.kt` — rows carry their why-line; "Reminders" stays reminder health.
- `app/.../di/AppGraph.kt` — only if the dashboard needs a field B07 did not add, and then **mirrored in `app/src/test/.../testing/FakeGraph.kt` in this brief** (master §1; B14 edits neither).
- Tests: `app/src/test/.../ui/dashboard/DashboardViewModelMaintenanceTest.kt`, `DashboardViewModelTest.kt`, `app/src/androidTest/.../ui/dashboard/DashboardAttentionTest.kt`, `app/src/androidTest/.../ui/maintenance/MaintenanceShellTest.kt` — extended.

**Untouched:** every `ui/asset/**` file (B14's, same wave); `ui/condition/**`, `ui/health/**` (B12's, reused); the read models (B07); `core/**`; `api/**`; `ScheduleDetail*` and `ScheduleEdit*` (B08).

## Interfaces

**Consumes:** `DueReadModel`, `DueItem` (with `actionableDueOn`, `policyReason`, `policyPhase`, `quiet`, `seasonMode`, `dormantUntil`, `health`), `AttentionSection` (with `DEFERRED`), `AttentionReadModel`, `AttentionItem` (B07); `ConditionBadge`, `HealthBadge`, `conditionWord`, `dashboardHealthRow`, the S8/S10/S12 constants (B12); `statusLabel` (S92, B02); `sectionLabel` (S93, B07).

**Produces:** `whyLine(item: DueItem, format: (LocalDate) -> String): String?` — nothing else another brief calls.

### The sections (spec §10.2)

| section | rows, in this order |
|---|---|
| ATTENTION | DOWN condition items ▸ the schedule rows of ATTENTION (their shipped order) ▸ DEGRADED condition items ▸ independent CRITICAL health items |
| UPCOMING | DUE SOON schedule rows ▸ independent WARNING health items |
| CURRENT | OK schedule rows |
| Deferred (S93) | DEFERRED schedule rows — never counted as due |
| OUT OF SEASON | INACTIVE_SEASON schedule rows |

Within each group the projections' own `rank` order holds; the screen never re-sorts. A component row names its parent in the shipped promoted-row form (spec 1.2 inv. 75, extended to condition and health rows; inv. 122). Only in-service assets and components appear.

### Row anatomy (spec §10.6: word, icon and position each distinguish; colour reinforces)

| row kind | position | leading word and icon | token | secondary line |
|---|---|---|---|---|
| condition, DOWN | ATTENTION, first group | S3 with `block` | `conditionDown` | the reason or S23; S22 "since <date>"; the parent named for a component |
| condition, DEGRADED | ATTENTION, after the schedule rows | S2 with `trending_down` | `conditionDegraded` | as above |
| health, CRITICAL | ATTENTION, last group | S110 with the one-bar icon | `healthCritical` | the asset (and parent) named |
| health, WARNING | UPCOMING, after DUE SOON | S110 with the two-bar icon | `healthWarning` | as above |
| schedule | its section, shipped placement | the shipped status word and glyph (S92 for DEFERRED) | the shipped status token | the why-line; then S110 when `health` is set |

Each row opens what it names: a condition or health row opens asset detail (B14 draws the sections); a schedule row opens its schedule, as shipped. No row carries an action that writes.

### The filters (master §13.2)

- The shipped **"Maintenance status"** picker gains DEFERRED, drawn with its status word S92 like the seven shipped options (master §20.26).
- A row of four **condition chips** — **S8 Operational, S10 Degraded, S12 Down, S26 Not recorded** — multi-select; none selected means no condition filter, so no "All …" option (and no new string) is needed.
- A **schedule row** passes when it matches the picker (if set) **and** its target asset's condition matches a selected chip (if any). A **group row** has no condition and passes only when no chip is selected. An **asset-level row** passes when **no status is selected or at least one condition chip is**, and it matches the chips (if any) — so asset rows hide only when **only** a status is selected (spec §10.2's "when only status chips are active"; the controller's ruling on M14). Category and search apply to both kinds. **Filters narrow; they never re-rank** (the shipped rule).

### The why-line (master §13.3; spec §10.5)

One line per schedule row, the first that applies: **DORMANT** — S89 "Out of season until <date>" with `dormantUntil` on a CALENDAR asset, S90 "Out of season until you start it" on a MANUAL one; **DEFERRED** — S85 "Held until <date> because of the maintenance break" with `actionableDueOn`; **quiet** — S91; **BEFORE_SEASON** — S86; **BEFORE_BREAK** — S87; **SEASON_START** — S88 "Moved from <date> because the season was not running" with `effectiveDueOn`; otherwise none. `POLICY_INAPPLICABLE` draws none on a row. `<date>` uses the shipped display shape.

## Invariants this brief must hold

**(UI)** **103** (DEFERRED in its own section, never counted), **119** (every CRITICAL independent subject and every DOWN or DEGRADED in-service component reaches the dashboard; a DOWN asset's health appears only beside its condition row), **122** (a DOWN or DEGRADED unit with no schedule reaches ATTENTION and names its parent), and spec 1.2 inv. 75 (a promoted component stays reachable at its rank).

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the ATTENTION order | `DashboardViewModelMaintenanceTest` · `downThenSchedulesThenDegradedThenCritical` | put DEGRADED before the schedules |
| UPCOMING and Deferred | `DashboardViewModelMaintenanceTest` · `warningHealthFollowsDueSoon`, `deferredSitsBetweenCurrentAndOutOfSeasonAndCountsForNothing` | count DEFERRED in a due total |
| a DOWN unit hidden | `DashboardViewModelMaintenanceTest` · `aDownComponentWithNothingDueIsInAttentionWithItsParent` (inv. 122) | leave it in the plain asset list |
| duplicates | `DashboardViewModelMaintenanceTest` · `anAssetDrawnAsARowIsNotRepeatedInTheAssetList` | forget the attention rows in the exclusion |
| overdue health doubled | `DashboardViewModelMaintenanceTest` · `overdueHealthRidesItsScheduleRow` | also draw it as an independent row |
| out-of-service rows | `DashboardViewModelMaintenanceTest` · `retiredAndArchivedUnitsDoNotAppear` | drop the bound |
| the filters | `DashboardFiltersTest` · `deferredIsAStatusOption`, `statusOnlyHidesAssetRows`, `aStatusPlusAMatchingChipLetsTheAssetRowPass` (M14), `conditionChipsSelectScheduleRowsByTheirAssetsCondition`, `groupRowsPassOnlyWithoutChips`, `categoryAndSearchApplyToBothKinds`, `filtersNeverReRank` | hide asset rows whenever a status is selected |
| the why-line | `WhyLinesTest` · one case per line S85–S91 with its date, `precedenceDormantDeferredQuietReason`, `policyInapplicableDrawsNothing` | show S91 over S85 |
| the screens | `DashboardAttentionTest` (connected) · `sectionOrderWithConditionAndHealthRows`, `theDeferredSection`, `conditionChips`; `MaintenanceShellTest` (connected) · `rowsCarryTheirWhyLine` | omit the Deferred header |

## Edge cases

- **A DOWN asset that also has due schedules** shows its condition row first in ATTENTION and its schedule rows after; it does not appear in the plain asset list.
- **A schedule on a component** is promoted with its parent named; for the condition chips its condition is the component's own.
- **"Not recorded"** matches an asset with no condition row; a group row never matches a chip.
- **Deferred rows** answer the DEFERRED status option; they never appear under OVERDUE or DUE.
- **An empty section** is omitted, as shipped; the Deferred header appears only when a row does.
- **A DEGRADED component of a retired parent** is listed on its own lifecycle, naming the parent.
- **Search** matches asset-level rows by the asset's name and category, the shipped search fields.
- **A quiet OVERDUE row** stays in ATTENTION with S91; quiet changes delivery, never placement.

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain`; connected, one class each: `com.loosecannon.servicetag.ui.dashboard.DashboardAttentionTest`, `com.loosecannon.servicetag.ui.maintenance.MaintenanceShellTest`.
- Anchored: `git diff --stat <base> -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/ui/asset app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/DueReadModel.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/AttentionReadModel.kt` → empty.

## Strings

**S26** and **S85–S91**, verbatim. **Uses** S8, S10, S12, S22, S23, S110 (B12), S92 (B02), S93 (B07) and the shipped section, picker and category words.

## Must NOT

- re-order, re-rank or re-section what the projections return;
- count DEFERRED, or show an asset-level row under a status filter;
- draw health in place of a DOWN asset's condition;
- add a string (no "All conditions", no chip-row label);
- touch the asset screens, the read models or `:core`.

## Review focus

- The assembly concatenates the projections' own orders and re-sorts nothing.
- The filter truth table matches master §13.2 exactly, including the group-row case and the asset-row case with a status **and** a chip.
- `DueItemRow`'s public signature is unchanged; B14's call site in the same wave compiles untouched.
- No new string: the chip row has no label and no "All" option.

## Size

Medium: one view model's assembly, one filter model, one row addition and one pure function. No split expected.
