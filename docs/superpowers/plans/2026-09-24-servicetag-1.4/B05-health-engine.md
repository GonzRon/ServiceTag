# B05 — the health engine, and the reminder-health rename

**Read first:** master plan §1, §8.2 (the policy engine this reads), §10.2 (the engine contract), §14, §15.
**Spec:** §6.2–§6.8, §7 (the clock and its three worked timelines), §12.4 (F1–F5), inv. 100, 111–118, 131; I-19 (the rename).
**Wave 3, lane B,** beside B04. **After B02.** Gates B07.

## Goal

Health as a **pure `:core` function**, computed at read time and never stored: the 0–100 score and its bands in exact integer arithmetic; the AGE driver from the latest qualifying REPLACEMENT event; the MAINTENANCE_OVERDUE driver from **counted days** — the days on which the linked schedule's policy phase is ACTIVE and the day is past the **actionable** date evaluated on that day — with the MEDIUM restart at the latest cycle start; the NOT TRACKED cases; the driver-line data S99–S106, S142 and S143 render from; and the four aggregations with the critical contributors always listed. The engine reads schedules only through B02's `ServicePolicyEngine`, so the clock follows the policy and never a raw date. Before asset health lands, this brief also renames the reminder-health code (`HealthScreen`, `HealthFinding`, `Severity`, and `HealthViewModel` with them) to `ReminderHealth*`, so the two meanings of "health" never share a type name. No write, no store, no screen, no string.

## Files

**Create**

- `core/src/main/kotlin/com/loosecannon/servicetag/core/health/HealthScore.kt` — `HealthScore`, `HealthBand`.
- `core/.../core/health/HealthClock.kt` — `countedDays` (a span implementation).
- `core/.../core/health/AssetHealthEngine.kt` — `AssetHealthEngine`, `LinkedSchedule`, `SubjectHealth`, `SubjectValue` (`Scored(score, band, trackedDays)` | `NotTracked(reason)`), `NotTrackedReason` (`NO_REPLACEMENT`, `PROFILE_REMOVED`, `OUT_OF_SEASON`, `SCHEDULE_PAUSED`, `LINK_INVALID`, `SCHEDULE_ARCHIVED`), `DriverLine` (`Replaced`, `NoReplacement`, `ProfileRemoved`, `Overdue`, `Grace`, `UpToDate`, `Postponed`, `NotTrackedOutOfSeason`, `NotTrackedPaused`, `NotTrackedLink`), `AssetHealthResult`.
- `core/src/test/kotlin/com/loosecannon/servicetag/core/testing/HealthFixtures.kt` — F1–F5's subjects and events (spec §12.4), built on B02's `SeasonFixtures` (read, not edited).
- Tests under `core/src/test/.../core/health/`: `HealthScoreTest`, `HealthClockTest`, `AgeDriverTest`, `HealthAggregationTest`, `AssetHealthEngineTest`, `PostponeSnoozeHealthTest`, `HealthStructuralTest`.

**Rename (I-19; mechanical, shape-only)**

- `core/.../core/reminders/ReminderPort.kt` — `HealthFinding` → `ReminderHealthFinding`, `Severity` → `ReminderHealthSeverity`.
- `app/src/main/kotlin/com/loosecannon/servicetag/reminders/LocalReminderProvider.kt`, `ReminderHealthCheck.kt` — their uses.
- `app/.../ui/maintenance/HealthScreen.kt` → `ReminderHealthScreen.kt` (and its composables); `HealthViewModel.kt` → `ReminderHealthViewModel.kt`; `HealthSummary.kt` and `MaintenanceViewModel.kt` — their uses.
- `app/.../ui/dashboard/DashboardViewModel.kt`, `DashboardScreen.kt`, `app/.../ui/nav/ServiceTagRoot.kt` — their uses.
- Tests: `core/src/test/.../testing/FakeReminderProvider.kt`, `…/reminders/ReminderPortContractTest.kt`; `app/src/test/.../reminders/ReminderHealthCheckTest.kt`, `…/reminders/BackstopWorkerTest.kt`, `…/ui/maintenance/HealthViewModelTest.kt` → `ReminderHealthViewModelTest.kt`, `…/ui/maintenance/MaintenanceViewModelTest.kt`, `…/ui/dashboard/DashboardViewModelMaintenanceTest.kt`; `app/src/androidTest/.../ui/maintenance/HealthScreenTest.kt` → `ReminderHealthScreenTest.kt`, `…/ui/dashboard/DashboardAttentionTest.kt`.

**Plan decision:** `HealthSummary` keeps its name — it is wired in `AppGraph` (B04's in this wave), I-19 does not name it, and asset health's types are all `AssetHealth*`/`Health*` in `core/health`, which it cannot be confused with.

**Untouched:** `AppGraph.kt`, `FakeGraph.kt`, `InMemoryRepositories.kt`, every `core/usecase/**` file (B04's, same wave), the engine files (B02's), every user-visible string (the rename changes no text; "Reminders" stays the row's word).

## Interfaces

**Consumes from B01:** `HealthSubject`, `HealthSubjectKind`, `HealthDriver`, `HealthAggregation`, `Asset.healthAggregation`, `Asset.healthPrimarySubjectId`. **From B02:** `ServicePolicyEngine.evaluate`, `PolicyInputs`, `SeasonContext` (`phaseAt`, `latestCycleStartOnOrBefore`), `ScheduleRecompute.policyInputsOf`. Shipped: `AssetEvent`, `EventKind.REPLACEMENT`, `EventChronology`.

**Produces** (master §10.2 pins the signatures; B07 is the one caller):

```kotlin
object AssetHealthEngine {
    fun evaluate(asset: Asset, subjects: List<HealthSubject>, links: Map<ScheduleId, LinkedSchedule>,
                 events: List<AssetEvent>, profileExists: (ProfileId) -> Boolean,
                 season: SeasonContext?, today: LocalDate): AssetHealthResult
}
data class AssetHealthResult(val subjects: List<SubjectHealth>,          // non-archived, (sortOrder, id)
                             val aggregate: Scored?, val fallback: Boolean,  // null = NOT TRACKED; S138
                             val critical: List<SubjectHealth>)          // every CRITICAL contributor
```

`profileExists` is a function, not a repository, so the engine stays free of ports; the caller answers it from the asset's profiles.

### The rules, restated where easy to get wrong

1. **Score** — spec §6.3's formula over `(x, t1, t2, t3)` computed as an exact rational with a **ceiling**, in `Long` integer arithmetic (`ceilDiv`); no `Double`. `band`: ≤ 25 CRITICAL, ≤ 60 WARNING, else NOMINAL. A negative `x` (a future-dated baseline) scores 100.
2. **AGE** — `x` = `ChronoUnit.DAYS` from the baseline event's `occurredOn` to `T`; the baseline is the latest `REPLACEMENT` event on the asset by `EventChronology`, restricted to `baselineProfileId` when set. A set `baselineProfileId` whose profile does not exist → `PROFILE_REMOVED` (never "any replacement"); no qualifying event → `NO_REPLACEMENT`. Season, policy and pause are ignored. An INSPECTION event never qualifies.
3. **MAINTENANCE_OVERDUE, NOT TRACKED first**, in this order: the link missing, on another asset, a group target or with no time rule → `LINK_INVALID`; the schedule ARCHIVED → `SCHEDULE_ARCHIVED` (no driver line — master §20.17); PAUSED → `SCHEDULE_PAUSED`; a MEDIUM subject whose schedule is IN_SERVICE_* and dormant at `T` → `OUT_OF_SEASON`.
4. **Counted days** — `d` over `O < d ≤ T` (master §20.16) counts when `evaluate(link.inputs, season, at = d)` has phase ACTIVE, a non-null `actionableOn`, and `d > actionableOn`; and, for a MEDIUM subject on IN_SERVICE_*, `d ≥ season.latestCycleStartOnOrBefore(T)` when that exists. The production code computes spans between phase, cycle and break boundaries; a **day-by-day oracle lives only in the test** and the two must agree.
5. **Driver lines** — AGE: `Replaced(on, ageDays)`, `NoReplacement`, `ProfileRemoved`. MAINTENANCE_OVERDUE with `x == 0`: `Postponed(title, to = P)` while the schedule carries a postponement and is not late against its actionable date, else `UpToDate(title)`; with `x > 0`: `Overdue(title, x)` then `Grace(t1)` while `x ≤ t1`. NOT TRACKED: `NotTrackedOutOfSeason` (S105), `NotTrackedPaused` (S106), `NotTrackedLink` (S142); `SCHEDULE_ARCHIVED` has none.
6. **Aggregation** over non-archived subjects **with a value**: WORST min; TRACK_ONE the primary's value — the primary untracked → aggregate NOT TRACKED; the primary missing or archived → WORST with `fallback = true`; AVERAGE `floor(Σs/n)`; WEIGHTED `floor(Σw·s/Σw)`. No contributor → `aggregate = null`. `critical` lists every contributor scoring ≤ 25, whatever the aggregation.
7. **Postpone and snooze** — the engine's only schedule input is `PolicyInputs`, which carries `P`; a postponement therefore reassesses `A` and may restart the clock, earlier lateness not retained, and nothing is journalled. The engine has **no** input a snooze could reach.

## Invariants this brief must hold

**82** (the engine writes nothing — it has no port), **100** (the clock half), **111**, **112**, **113**, **114**, **115**, **116**, **117**, **118** (aggregation half), **131**.

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| band edges drift | `HealthScoreTest` · `warningStartsExactlyAtT2AndCriticalExactlyAtT3` (both templates, 14/45/120 and 2/7/14) | `floor` for `ceil` |
| the score rises | `HealthScoreTest` · `theScoreNeverRisesAsXGrows` (property over thresholds and `x`) | swap the third segment's divisor |
| floating point | `HealthScoreTest` · `theWorkedScoresAreExact` (92, 76, 60, 25; 81, 60, 44, 25, 0; 37, 11; 47, 18) | compute in `Double` and round |
| the clock follows a raw date | `HealthClockTest` · `theSpanImplementationEqualsTheDayByDayOracle` (property over generated policies, contexts, postponements and `O`) | measure from `computedDueOn` |
| MEDIUM across END and START | `HealthClockTest` · `hotTubWaterCare` (spec §7.2: NOT TRACKED on 16 Apr and 1 Jul; 100 on 10 Oct; 92 / 76 / 60 / 25 on 13 / 15 / 17 / 24 Oct; the 13 Oct completion → 100) | drop the MEDIUM restart |
| hardware freezes and carries | `HealthClockTest` · `aPartCarriesAcrossDormancy` (§7.2's contrast: 3 + 7 = 10 → 45 WARNING on 17 Oct) | apply the MEDIUM restart to a PART |
| pre-service lateness reset by the season | `HealthClockTest` · `snowblowerCountsFromThePointThroughTheBreak` (§7.3: 100, 81, 60, 44, 25, 0) and `mowerStartsAfterTheDeferral` (100 on 15 Apr, 60 on 16 May, 25 on 30 Jul) — inv. 100, 115 | reset at `cycleStartAt` |
| break days | `HealthClockTest` · `generatorDeferredDaysAreNotCountedAndLateDaysAre` (§7.4: 60, 37, 25; 100 through a deferral; the H.3c branch 11 on 1 Dec and 0 on 1 Jan) | count days where `d ≤ A(d)` |
| AGE | `AgeDriverTest` · `theLatestQualifyingReplacementIsTheBaseline` (F1 18 CRITICAL; F2 47 WARNING, WARNING from 9 May 2026, CRITICAL from 9 May 2027), `aLoadTestChangesNothing`, `aNewReplacementResetsAndDeletingItRestores` | include INSPECTION events |
| a deleted quick action widens the filter | `AgeDriverTest` · `aMissingBaselineProfileIsNotTrackedNeverAnyReplacement` (inv. 113) | fall back to any REPLACEMENT |
| NOT TRACKED order | `AssetHealthEngineTest` · `notTrackedReasonsAndTheirLines` (link invalid, archived with no line, paused, MEDIUM dormant) | report a paused schedule as 100 |
| the driver lines | `AssetHealthEngineTest` · `driverLineData` (Up to date; Postponed while not late; Overdue with Grace while `x ≤ t1`; Overdue alone after) | show Postponed after the new date passes |
| aggregation | `HealthAggregationTest` · `eachAggregation` (WORST, TRACK_ONE, AVERAGE and WEIGHTED floors), `untrackedSubjectsAreExcludedNeverCountedAs100` (inv. 118), `aMissingPrimaryFallsBackToWorstWithTheFlag`, `anUntrackedPrimaryIsNotTracked`, `noContributorIsNotTracked` | count an untracked subject as 100 |
| a critical hidden by an average | `HealthAggregationTest` · `everyCriticalContributorIsListedWhateverTheAggregation` (AVERAGE NOMINAL with one CRITICAL member) | list criticals only under WORST |
| the five fixtures | `AssetHealthEngineTest` · `fixturesAtTheSpecsDate` (T = 2026-09-24: F1 18 CRITICAL, F2 47 WARNING, F3 37 WARNING, F4 snowblower 100, F5 NOT TRACKED) | — (values; mutation: drop the ceiling) |
| **postpone and snooze** | `PostponeSnoozeHealthTest` · `postponingRestartsTheClockFromTheNewActionableDate` (through the real `PostponeSchedule` with fakes; earlier lateness gone), `noEventIsWritten`, and `theEngineHasNoInputASnoozeCanReach` (O-6, inv. 131) | measure from the pre-postponement `A` |
| health stores or writes | `HealthStructuralTest` · `theHealthPackageImportsNoPortAndNoDeliveryType` (no `ports.*Repository`, `UnitOfWork`, `ScheduleLocalDelivery` or snooze import under `core/health`; inv. 82, 111) | inject a repository |
| the rename | shape-only (master §15.3): every renamed test green; `ReminderHealthScreenTest` connected | — |

## Gate

- `./gradlew :core:test :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain`, then connected, one class each: `com.loosecannon.servicetag.ui.maintenance.ReminderHealthScreenTest`, `com.loosecannon.servicetag.ui.dashboard.DashboardAttentionTest`.
- Anchored: `grep -rnwE '(HealthFinding|Severity|HealthScreen|HealthViewModel)' app/src core/src` → no output; `grep -rlE '^import .*\.(ports\.[A-Za-z]*Repository|ports\.UnitOfWork)' core/src/main/kotlin/com/loosecannon/servicetag/core/health` → no output; `grep -rnE '(Double|toDouble|Math\.round)' core/src/main/kotlin/com/loosecannon/servicetag/core/health/HealthScore.kt` → no output; `git diff --stat <base> -- app/src/main/kotlin/com/loosecannon/servicetag/di core/src/main/kotlin/com/loosecannon/servicetag/core/usecase` → empty.

## Strings

**None drawn here.** The driver-line data drives S99–S106, S142 and **S143** (the postponed line, ratified under O-9), which B12 renders; the rename changes no visible text.

## Must NOT

- store, cache, export or default a health value, a band or an aggregate;
- read `computedDueOn` or `effectiveDueOn` as the lateness start, or any meter reading;
- widen the AGE filter when its quick action is gone;
- count an untracked subject as 100, or let an aggregate hide a CRITICAL contributor;
- read the snooze, or journal a postponement;
- change a user-visible word in the rename, or touch `AppGraph`.

## Size

Medium: three engine files and a fixture file, plus a mechanical rename across about fifteen files. Split seam if the review cannot hold both: the rename alone as **B05b**, landing first.
