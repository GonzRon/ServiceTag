# B02 — the season context and the service-policy engine

**Read first:** master plan §1, §7.1 (`SeasonContext`), §8 (the actionable-due pipeline), §14, §15.
**Spec:** §3.1, §3.5, §4.1–§4.7, §7.2–§7.4 (the schedule columns of the worked timelines), §11 (inv. 84–106 and the restated 1.2 invariants), §12.1.
**Wave 2, lane A,** beside B03. **After B01.** Gates B04 and B05.

## Goal

Make the engine answer the 1.4 question — *when is this work actionable* — in one place. Build `SeasonContext` from B01's `SeasonInputs`; implement `ServicePolicyEngine.evaluate` as spec §4.3's `policyDue`, taken as normative, over `P ?: R`; have `rebuild` materialise `actionableDueOn`, `policyReason`, `policyPhase` and `quiet` and derive `O` from the last termination or `ruleChangedAt`; make `statusOf` answer DEFERRED after the worst-of fold from a held time side; give every read path one in-memory accessor, `RecomputeSchedules.readState`, that never writes; and make `BuildReminderSubjects` map engine output instead of re-deriving seasons. The occurrence key stays `computedDueOn`, whose arithmetic this brief does not touch.

## Files

**Create**

- `core/src/main/kotlin/com/loosecannon/servicetag/core/schedule/SeasonContext.kt` — `SeasonPhase`, `BoundaryKind`, `Boundary`, `boundaryKindOf`, `SeasonContext` (master §7.1).
- `core/.../core/schedule/ServicePolicyEngine.kt` — `PolicyInputs`, `PolicyOutcome`, `ServicePolicyEngine` (master §8.2).
- `app/src/main/res/drawable/ic_hourglass_top.xml` — the DEFERRED glyph (spec §10.6's proposed icon).
- `core/src/test/kotlin/com/loosecannon/servicetag/core/testing/SeasonFixtures.kt` — F3, F4 and F5's configurations (spec §7.2–§7.4) as builders; read-only for later briefs.
- Tests under `core/src/test/.../core/schedule/`: `SeasonContextTest`, `ServicePolicyEvaluatorTest`, `PolicyInvarianceTest`, `BreakTest`, `PostponementPolicyTest`, `MeterSidePolicyTest`, `DeferredStatusTest`, `NoBacklogAcrossDormancyTest`, `WorkedTimelinesTest`; under `core/src/test/.../core/usecase/`: `ReadStateTest`.

**Modify**

- `core/.../core/schedule/ScheduleRecompute.kt` — `rebuild` builds a `SeasonContext` from `season` and fills the four policy fields from `evaluate(at = today)`; `O` per master §8.1; a public `policyInputsOf(schedule, state, zone)`. Replace B01's interim substitution.
- `core/.../core/schedule/ScheduleStatus.kt` — `DueStatus.DEFERRED` appended last (`countsAsDue` and `notifies` false); `statusOf` per master §8.4, the time side measured against `actionableDueOn`.
- `core/.../core/usecase/RecomputeSchedules.kt` — `readState(schedule)`; `stateOf` unchanged in contract.
- `core/.../core/reminders/BuildReminderSubjects.kt` — master §8.5's mapping; reads state through `readState` (a missing or stale row is derived, never skipped); the `MM-DD` re-entry derivation (`:138-143`, `:224-231`) deleted; `RuleFacts.seasonal = servicePolicy ≠ CONTINUOUS`; `statusTerm(DEFERRED)` → S92.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/DueItemRow.kt` — the three exhaustive `when`s over `DueStatus`: `statusLabel(DEFERRED)` = S92, `statusColors(DEFERRED)` = `seasonInactive` (spec §10.6: season-inactive grey), a new `StatusGlyph.HOURGLASS`.
- `app/.../ui/components/ServiceTagIcons.kt` — `HourglassTop`.
- `core/src/test/.../core/schedule/ScheduleStructuralTest.kt` — inv. 104's check (below); `ScheduleStatusTest.kt`, `ScheduleRecomputeTest.kt`, `core/src/test/.../reminders/BuildReminderSubjectsTest.kt`, `app/src/test/.../ui/maintenance/StatusVocabularyTest.kt` — extended.

**Untouched:** `AppGraph.kt`, `FakeGraph.kt`, `InMemoryRepositories.kt` (B01's fakes suffice), `core/backup/**`, `core/merge/**` and the four import/export use cases (B03's, same wave), every use case that writes, `DueReadModel.kt` (B07), `RecurrenceMath.kt`, `GroupOccurrence.kt`, `computedDueOn`'s arithmetic.

## Interfaces

**Consumes from B01:** `SeasonInputs`, `SeasonMode`, `SeasonActivation`, `ServicePolicy`, `PolicyPhase`, `PolicyReason`, `ScheduleState` (new fields), `MaintenanceSchedule.ruleChangedAt`, the `rebuild(..., season: SeasonInputs?)` signature, `SeasonActivationRepository` wired into `RecomputeSchedules`.

**Produces** (master §7.1 and §8.2 pin the signatures):

- `SeasonContext.of(inputs)`, `boundaryKindOf(mode, hasBreak)` — for B04 (strands, `SeasonView`), B05 (the clock), B07, B14.
- `ServicePolicyEngine.evaluate(inputs, season, at)` and `ScheduleRecompute.policyInputsOf(schedule, state, zone)` — for B05's per-day evaluation. **`evaluate` is pure and total** and is the only place the policy is applied.
- `RecomputeSchedules.readState(schedule)` — for B07, B09 and every read path (master §8.6).
- `DueStatus.DEFERRED`.

**The rule, restated only where it is easy to get wrong** (spec §4.3 is normative):

1. **Which date** — the policy applies to `P ?: R`. PRE_SERVICE computes its boundary from **`R`**; a postponed `P` is then moved past the break when inside it and **never earlier** (inv. 101).
2. **IN_SERVICE reads `cycleStartAt(at)`**, not the cycle of `R`: AT_START gives `s + offset` only when the date is earlier; RESUME_CLAMPED `max(date, s)`; a null `s` on YEAR_ROUND keeps the date; a null `s` on MANUAL gives `actionableOn = null`, `AWAITING_START`. Then a date inside the break moves to `firstAllowedAfter` with `AFTER_BREAK`; otherwise the reason is `SEASON_START` when the date moved, else `NONE`.
3. **PRE_SERVICE** — boundary `s` (CALENDAR: the season containing `R` or the next start after it; otherwise the break containing `R` or the next one; never a manual START); `dl = s + offset`; keep an allowed `R ≤ dl`; else `point` = the latest day of `W` on or before `dl`, else `W`'s first day, else (empty `W`) the day before the break; a `point > R` applies with `AFTER_BREAK`; a `point < R` applies only when `O < point`, reason `BEFORE_SEASON` when the point is in `W` and the boundary is a season, else `BEFORE_BREAK`; otherwise keep `R` if allowed or move it to the first allowed day after the break (`AFTER_BREAK`). No boundary → `P ?: R`, `POLICY_INAPPLICABLE`.
4. **`phase`** is DORMANT exactly for IN_SERVICE_* on an OUT_OF_SEASON day; PRE_SERVICE and CONTINUOUS are always ACTIVE. **`quiet`** ⇔ policy ≠ CONTINUOUS ∧ `inBreak(at)`.
5. **A meter-only schedule** (`rawDueOn == null`) gets `actionableOn = null` with phase and quiet still computed; its meter side is never moved or deferred (O-7).
6. **Held and DEFERRED:** held ⇔ `AFTER_BREAK` ∧ `T ≥ (P ?: R) − leadDays` ∧ `T < A`; the fold counts a held time side as OK; fold OK ∧ held → DEFERRED.

## Invariants this brief must hold

**84, 85, 86** (engine half: nothing here writes), **90, 94, 95** (engine half), **96, 97, 98, 99** (engine half), **100, 101, 102, 103, 104, 105** (`:core` half), and the restated **10, 16, 17, 22, 23, 47**. It **replaces** the retired inv. 26 with 104's structural check and 84's property.

## Test matrix

Every date is an injected `T`. Literal values are spec §7 and §12.1's.

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the calendar predicate drifts | `SeasonContextTest` · `calendarPhaseWrapsAndReadsFebruary29AsFebruary28` | exclusive end |
| MANUAL phase wrong | `SeasonContextTest` · `manualPhaseIsTheLatestRowOnOrBeforeTheDay` (START then END same-day ordering by `createdAt`; END's own day dormant; no row → OUT_OF_SEASON; rows ignored in CALENDAR and YEAR_ROUND) | `<` for `≤` on `occurredOn` |
| a manual START predicted | `SeasonContextTest` · `aManualAssetOutOfSeasonHasNoCycleStartAndNoSeasonBoundary` | return the last START |
| boundary kinds | `SeasonContextTest` · `boundaryKindAndPreServiceBoundaryPerMode` (CALENDAR → season start containing or after `R`; YEAR_ROUND and MANUAL with a break → break start containing or after `R`; neither → null) | ignore the break on YEAR_ROUND |
| **the pre-service rule** | `ServicePolicyEvaluatorTest` · `snowblowerIsActionableOnFirstNovember` (A = 1 Nov 2026, BEFORE_SEASON); `mowerAutumnDueIsKept` (R 5 Nov 2026); `mowerJanuaryDueGoesToFirstApril` (R 12 Jan 2027 → 1 Apr 2027, AFTER_BREAK); `aLongerBreakTakesTheWindowsFirstDay` (break 12-01→04-05 → 6 Apr 2027); `anInSeasonDueIsPulledOnlyWhenOpenedBefore` (R 20 Apr 2027 → 1 Apr when `O` < 1 Apr); `aWholeGapBreakPullsToTheDayBeforeIt` | take `W`'s first day before its latest |
| the opened-before guard | `ServicePolicyEvaluatorTest` · `theGuardValuesOfSection4_3` (completed 5 Nov, R 5 Dec → 1 Mar AFTER_BREAK; R 20 Nov opened 20 Oct → 1 Nov BEFORE_SEASON; opened 2 Nov → 20 Nov kept) | drop `O < point` |
| AT_START drags a later date back | `ServicePolicyEvaluatorTest` · `atStartMovesOnlyAnEarlierDateAndResumeClampsToTheStart` | always return `s + offset` |
| #14 AC 4 | `ServicePolicyEvaluatorTest` · `issue14Ac4` (CALENDAR 10-15→04-15, every 3 days, done 13 Apr: INACTIVE_SEASON on 1 Jul, DUE on 15 Oct) | read the cycle of `R` instead of `T` |
| no inference | `ServicePolicyEvaluatorTest` · `theRuleReadsNoNameCategoryOrTemplate` (two schedules differing only in title, category and template agree) | a title branch |
| a policy moves the key | `PolicyInvarianceTest` · `everyPolicyKeepsContinuousComputedDueOn` and `continuousActionableIsPostponedOrRaw` (property over generated histories, policies and contexts; inv. 84, 85, 96) | let PRE_SERVICE write `computedDueOn` |
| postponement | `PostponementPolicyTest` · `aPostponementIntoDormancyIsReEnteredNotOverdue`, `aPostponementIntoTheBreakMovesAfterIt`, `preServiceNeverMovesAPostponementEarlier` (inv. 101) | apply PRE_SERVICE's pull to `P` |
| the break | `BreakTest` · `quietIsNonContinuousInsideTheBreak`, `alreadyLateWorkStaysOverdueAndQuiet` (the F3 generator's H.3c branch), `continuousIgnoresTheBreak` (inv. 102) | quiet for CONTINUOUS |
| DEFERRED | `DeferredStatusTest` · `deferredIsDecidedAfterTheFoldFromTheTimeSide`, `deferredNeverNotifiesNorCountsAsDue`, `heldOnlyFromDueMinusLeadUntilActionable` (the mower: OK before 29 Dec, DEFERRED 29 Dec–31 Mar, DUE 1 Apr) | decide DEFERRED before the fold |
| the meter side | `MeterSidePolicyTest` · `meterOnlyInServiceIsPhaseOnly` (INACTIVE_SEASON when dormant, quiet in the break, no date), `aHeldTimeSideWithACrossedMeterReadsDue` (inv. 95, 103) | defer the meter side |
| a backlog after dormancy | `NoBacklogAcrossDormancyTest` · `reEntryLeavesExactlyOneOccurrence` (the F5 hot tub: one occurrence, R 18 Apr, on 1 Jul) | — (property; mutation: advance by elapsed intervals) |
| the worked timelines | `WorkedTimelinesTest` · `hotTubAcrossEndAndStart` (§7.2's schedule column), `snowblowerAndMower` (§7.3's statuses and notifications), `generatorBreak` (§7.4) | swap `AFTER_BREAK` and `SEASON_START` |
| the pin and `O` | `ScheduleRecomputeTest` · `openedIsTheLastTerminationElseTheRuleChangeDate` | read `updatedAt` |
| purity (inv. 16) | `ScheduleRecomputeTest` · `rebuildIsPureAndIdempotentWithSeasonInputs` | read a clock inside `evaluate` |
| reads write | `ReadStateTest` · `aFreshRowIsReturned`, `aStaleOrMissingRowIsDerivedAndNothingIsWritten` (a recording state repository sees zero upserts) | upsert the derived row |
| monotonicity (inv. 23) | `ScheduleStatusTest` · `aBoundaryMayChangeStatusWithNoHistoryChange` | — (amended assertion) |
| reminders re-derive a season | `BuildReminderSubjectsTest` · `theSubjectStateFollowsTheEngine` (spec §4.7's six rows), `aStaleStateRowIsDerivedNotSkipped`, `continuousRowsKeepTheirContentHash` | keep the `MM-DD` re-entry |
| inv. 104 | `ScheduleStructuralTest` · `noReminderCodeEvaluatesASeasonBreakOrPolicy` — no file under `core/.../core/reminders` or `app/.../reminders` references `Season.inSeason`, `SeasonContext`, `ServicePolicyEngine`, `seasonStartMmdd`, `seasonEndMmdd`, `blackoutStartMmdd` or `blackoutEndMmdd` | call `Season.inSeason` from the builder |
| the status word and glyph | `StatusVocabularyTest` · `everyStatusHasItsOwnRatifiedWord` and `everyStatusHasItsOwnGlyph` (DEFERRED included) | reuse the PAUSE glyph |

## Gate

- `./gradlew :core:test :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` → success. No connected run (nothing drawn changes beyond a word and a glyph proved in JVM).
- Anchored: `grep -rnE '(Season\.inSeason|SeasonContext|ServicePolicyEngine|blackout(Start|End)Mmdd|season(Start|End)Mmdd)' core/src/main/kotlin/com/loosecannon/servicetag/core/reminders app/src/main/kotlin/com/loosecannon/servicetag/reminders` → no output; `grep -rnE '\bseason(Behavior|Reentry)' core/src/main/kotlin/com/loosecannon/servicetag/core/schedule core/src/main/kotlin/com/loosecannon/servicetag/core/reminders` → no output; `grep -rn 'nowMillis()' core/src/main/kotlin/com/loosecannon/servicetag/core/schedule` → no output; `git diff --stat <base> -- app/src/main/kotlin/com/loosecannon/servicetag/di core/src/main/kotlin/com/loosecannon/servicetag/core/backup core/src/main/kotlin/com/loosecannon/servicetag/core/merge` → empty.

## Strings

**S92** (DEFERRED) — the status word, in `statusLabel` and `statusTerm`. Nothing else.

## Must NOT

- change `computedDueOn`, `effectiveDueOn` or the occurrence key;
- persist from any read path, or give `readState` a write;
- read a category, template or title in the policy;
- treat a manual START as a boundary or predict one;
- move, defer or mask a crossed meter threshold;
- let any reminder class evaluate a season, break or policy;
- add the Deferred dashboard section (B07) or a why-line (B08);
- edit `AppGraph.kt` or any shared fake.

## Size

Large but pure: two new engine files, three modified. Split seam if one review cannot hold it: **B02a** `SeasonContext` + `ServicePolicyEngine` + their tests; **B02b** `rebuild`, `statusOf`, `readState`, reminders and the worked timelines. The interface between them is `evaluate`.
