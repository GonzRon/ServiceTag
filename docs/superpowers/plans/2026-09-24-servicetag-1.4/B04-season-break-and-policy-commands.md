# B04 — the season, break and schedule-policy commands

**Read first:** master plan §1, §7 (the season model and its commands), §4 (the legacy pair), §14, §15.
**Spec:** §3.2, §3.3, §3.4, §4.1 (meter-only refusals), §4.2, §4.4, §4.6, §9.2 (the codes), §9.3 (the asset pair).
**Wave 3, lane A,** beside B05. **After B02.** Gates B06 (which edits `SaveSchedule` after it), B08, B10.

## Goal

Every write that changes a season, a break or a schedule's policy goes through one use case with one set of refusals, so the editor, the API and the MCP refuse the same things. Deliver `SetSeasonMode` (including the switch into MANUAL that states its phase and writes its first activation row), `SetMaintenanceBreak`, `RecordSeasonActivation` (START and END with their refusals), the asset command's legacy pair translated through the season-mode rules, the schedule command's policy validation (offsets, meter-only, group targets, `PRE_SERVICE_NEEDS_DATES`), the **strands rule** that forbids a season or break change from silently re-kinding a PRE_SERVICE schedule's boundary, the pure `SeasonView` every season surface reads, and the season event offer's predicate. Every season or break write recomputes the asset's schedules (1.3's `UpdateAsset` never did).

## Files

**Create**

- `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/SeasonCommands.kt` — `SeasonModeCommand`, `BreakCommand`, `ActivationCommand`, `SeasonProblem`, `SeasonValidation`, and the state refusals `SeasonModeStrandsPolicy`, `BreakStrandsPolicy`, `SeasonNotManual`, `SeasonAlreadyStarted`, `SeasonAlreadyEnded`, `LegacyWriteCannotRepresent`, `PreServiceNeedsDates`.
- `core/.../core/usecase/SetSeasonMode.kt`, `SetMaintenanceBreak.kt`, `RecordSeasonActivation.kt`.
- `core/.../core/usecase/SeasonView.kt` — `SeasonView` (pure, `SeasonView.of(asset, activations, today)`) and `GetAssetSeason.run(assetId)`.
- `core/.../core/usecase/SeasonEventOffer.kt` — `seasonOfferFor(...)` and `AcceptSeasonOffer` (signatures under Interfaces).
- Tests under `core/src/test/.../core/usecase/`: `SeasonModeCommandTest`, `MaintenanceBreakCommandTest`, `ManualSeasonTest`, `SeasonEventOfferTest`, `LegacyAssetPairTest`, `SchedulePolicyValidationTest`, `SeasonViewTest`.

**Modify**

- `core/.../core/usecase/AssetCommands.kt`, `UpdateAsset.kt`, `CreateAsset.kt` — the legacy pair (below); `UpdateAsset` gains the schedule repository and the recompute.
- `core/.../core/usecase/ScheduleCommands.kt` — `ScheduleProblem.PolicyOffsetInvalid`, `ScheduleProblem.SeasonPolicyNeedsATimeRule`; `SeasonFollowsAssetOnGroupTarget` restated as "a non-CONTINUOUS policy on a group target".
- `core/.../core/usecase/SaveSchedule.kt` — policy validation and `PreServiceNeedsDates`.
- `app/src/main/kotlin/com/loosecannon/servicetag/di/AppGraph.kt` — `setSeasonMode`, `setMaintenanceBreak`, `recordSeasonActivation`, `getAssetSeason`, `acceptSeasonOffer`; the re-wired `updateAsset` and `createAsset`.
- `app/src/test/.../testing/FakeGraph.kt` — **the same five fields and the two re-wirings, mirrored** (master §1: every `AppGraph` field is mirrored in `FakeGraph` by the brief that adds it; B09, B10, B12 and B14 read them there).
- `core/src/test/.../testing/InMemoryRepositories.kt` — only if a fake needs a query this brief adds.

**Untouched:** `core/health/**`, `core/reminders/**` and every `app/.../reminders`, `ui/maintenance/ReminderHealth*`, `ui/dashboard/**` and `ui/nav/**` file (B05's, same wave); the engine files (B02's, merged); `api/**` (B09 maps these refusals); every screen; `ArchiveSchedule.kt` (B06).

## Interfaces

**Consumes from B01:** the shapes, `SeasonActivationRepository`, `LegacySeasonMapping`. **From B02:** `SeasonContext`, `boundaryKindOf`, `SeasonPhase`, `RecomputeSchedules.forAsset`.

**Produces** (names the API, the editors and the offers call):

```kotlin
data class SeasonModeCommand(val seasonMode: SeasonMode, val seasonStartMmdd: String? = null,
                             val seasonEndMmdd: String? = null, val manualPhase: SeasonPhase? = null)
data class BreakCommand(val blackoutStartMmdd: String?, val blackoutEndMmdd: String?)
data class ActivationCommand(val action: SeasonAction, val occurredOn: String? = null, val eventId: EventId? = null)
class SetSeasonMode      { suspend fun run(assetId: AssetId, cmd: SeasonModeCommand): Asset }
class SetMaintenanceBreak { suspend fun run(assetId: AssetId, cmd: BreakCommand): Asset }
class RecordSeasonActivation { suspend fun run(assetId: AssetId, cmd: ActivationCommand): SeasonActivation }
class GetAssetSeason     { suspend fun run(assetId: AssetId): SeasonView }
fun seasonOfferFor(asset: Asset, activations: List<SeasonActivation>, event: AssetEvent, today: LocalDate): SeasonAction?
class AcceptSeasonOffer  { suspend fun run(assetId: AssetId, event: AssetEvent, action: SeasonAction): SeasonActivation }
```

`SeasonModeStrandsPolicy` and `BreakStrandsPolicy` carry the stranded schedules (id and title) so the API names them and the editor fills S55/S64's `<titles>`. `SeasonView` carries `seasonMode`, the window, `phase`, `nextBoundaryOn`, the break, `inBreak`, `activations` (ordered `(occurredOn, createdAt, id)`) and `computedForOn` — the API's `SeasonResponse` and asset detail's Season section read nothing else.

### The rules (spec is normative; restated where easy to get wrong)

1. **`SetSeasonMode`** — CALENDAR needs both `MM-DD`, valid (`SEASON_WINDOW_REQUIRED`; a malformed value keeps the shipped `BadDate` shape); a window with YEAR_ROUND or MANUAL is `SEASON_WINDOW_FORBIDDEN`; `manualPhase` is required exactly on a switch **into** MANUAL from another mode (`MANUAL_PHASE_REQUIRED`) and forbidden everywhere else, MANUAL → MANUAL included (`MANUAL_PHASE_FORBIDDEN`). The switch into MANUAL writes exactly one START (IN_SEASON) or END (OUT_OF_SEASON) row dated today in the same transaction, even when the latest historical row already says the same (inv. 92). Leaving MANUAL keeps the rows. **A command equal to the stored mode and window writes nothing** — no asset `updatedAt`, no row — because the editor sends all four settings commands on every save (B06's `SaveAssetSettings`).
2. **`SetMaintenanceBreak`** — both or neither; valid `MM-DD`; `BLACKOUT_COVERS_THE_YEAR` when some common or leap year has no allowed day (master §7.2); unchanged writes nothing. Never prefilled, never defaulted (inv. 121).
3. **The strands rule** — master §7.2: refuse (409) a season-mode or break change that **removes or re-kinds** the boundary — the kind before is not NONE and the kind after differs — while **any** PRE_SERVICE schedule targets the asset, whatever its lifecycle. **Adding a boundary where none existed** (NONE → BREAK, NONE → SEASON) repairs a merged boundary-less schedule and is **allowed** (master dec. 44, ruled). Changing a CALENDAR window, or a break's dates, is allowed. The legacy pair obeys the same rule.
4. **`RecordSeasonActivation`** — the asset must be MANUAL (409 `SEASON_NOT_MANUAL`); START when the latest row is START is 409 `SEASON_ALREADY_STARTED`, END when it is END is 409 `SEASON_ALREADY_ENDED`; **Plan decision:** with no row at all (reachable only by an import) the phase is OUT_OF_SEASON, so END is `SEASON_ALREADY_ENDED` and START is allowed. `occurredOn` defaults to today; after today, or before the latest row's date, is 422 `SEASON_DATE_OUT_OF_RANGE` (equal is allowed). An `eventId` must be an event of this asset (422 `FOREIGN_EVENT`). It writes **one row and no asset column** (inv. 126), then recomputes.
5. **The legacy pair** (spec §3.2, §9.3) — `CreateAsset` and component creation: a pair makes CALENDAR, none makes YEAR_ROUND. `UpdateAsset`: a pair **equal to the stored pair** leaves mode, window and break untouched (so a MANUAL asset's null pair round-trips); a different pair on a MANUAL asset is 422 `LegacyWriteCannotRepresent`, writing nothing; otherwise a different pair becomes CALENDAR(window) or YEAR_ROUND under rules 1 and 3, with the asset's other fields, **in one transaction**, validated before anything is written.
6. **Schedule policy** — non-CONTINUOUS on a group target: `SeasonFollowsAssetOnGroupTarget`; AT_START offset must be 0–365, PRE_SERVICE −365…−1 and present, any other policy null — else `PolicyOffsetInvalid`; with no time rule, PRE_SERVICE or a non-zero IN_SERVICE offset is `SeasonPolicyNeedsATimeRule` (it takes precedence over `PolicyOffsetInvalid`); all 422, collected with the shipped problems. Then PRE_SERVICE on an asset whose boundary kind is NONE is 409 `PreServiceNeedsDates` (the remedy is the asset). `servicePolicy` and `policyOffsetDays` are **not rule fields** (B01's `ruleChanged` is unchanged).
7. **The season event offer** — `seasonOfferFor` answers START after a `SEASON_START` event, END after a `SEASON_END` event, only on a MANUAL asset whose phase at `today` is the opposite; else null. `AcceptSeasonOffer.run` calls `RecordSeasonActivation` with the event's date clamped to `[the latest row's date, today]` and `eventId` set. **No journal event ever changes a phase by itself** (inv. 93).

## Invariants this brief must hold

**Accountable** (master §14): **88**, **91**, **92**, **93** (the predicate and the accept path), **106**. **Halves:** **86** (season and break commands write no event, closure or schedule column), **95** and **99** (the refusals), **121** (no break default), **126** (activations never touch the asset row), **128** (the asset pair on MANUAL).

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| a window with the wrong mode | `SeasonModeCommandTest` · `calendarNeedsAWindowAndOtherModesForbidOne` | accept a window on YEAR_ROUND |
| `manualPhase` edges | `SeasonModeCommandTest` · `manualPhaseIsRequiredOnlyOnASwitchIntoManual` (into MANUAL without it; with it on CALENDAR; MANUAL → MANUAL with it) | allow it on MANUAL → MANUAL |
| the switch writes the wrong rows | `SeasonModeCommandTest` · `aSwitchIntoManualWritesExactlyOneRowToday` (END, END is valid history) and `leavingManualKeepsTheRows` | skip the row when the latest says the same |
| **the strands rule (RS-4)** | `SeasonModeCommandTest` · `reKindingAPreServiceBoundaryIsRefused` (CALENDAR → YEAR_ROUND, CALENDAR → MANUAL, break-only → CALENDAR; a paused and an archived PRE_SERVICE schedule count; a window change on CALENDAR is allowed; the refusal names the schedules and writes nothing) | ignore archived schedules |
| **adding a boundary is a repair** | `SeasonModeCommandTest` · `addingASeasonWhereNoneExistedIsAllowed` (YEAR_ROUND, no break, a merged PRE_SERVICE schedule → CALENDAR succeeds) and `MaintenanceBreakCommandTest` · `addingABreakWhereNoBoundaryExistedIsAllowed` (NONE → BREAK); both ways against `removingABreakAPreServiceScheduleUsesIsRefused` below | refuse every kind change |
| an unchanged editor save writes | `SeasonModeCommandTest` · `anUnchangedModeWritesNothing` (asset `updatedAt` byte-identical, no row) | always stamp `updatedAt` |
| the break | `MaintenanceBreakCommandTest` · `bothOrNeitherAndValidMonthDays`, `aBreakCoveringTheYearIsRefused` (03-01→02-28 in a common year; 01-01→12-31), `removingABreakAPreServiceScheduleUsesIsRefused` (409, YEAR_ROUND), `changingTheBreakOnACalendarAssetIsAllowed` | forget the leap-year pass |
| START/END refusals | `ManualSeasonTest` · `repeatedStartOrEndIsRefusedAndWritesNothing`, `aFutureDateOrOneBeforeTheLatestRowIsRefused`, `anAssetNotManualIsRefused`, `aForeignEventIsRefused`, `aSameDayEndAfterStartWins` (inv. 91) | compare against the first row, not the latest |
| an activation touches the asset | `ManualSeasonTest` · `anActivationWritesOneRowAndNoAssetColumn` (recording store) | stamp the asset's `updatedAt` |
| season writes do more | `SeasonModeCommandTest` · `seasonAndBreakWritesTouchNoEventClosureOrScheduleColumn` (inv. 86) | clear a postponement on a mode change |
| season changes leave stale state | `SeasonModeCommandTest` · `aSeasonOrBreakChangeRecomputesTheAssetsSchedules` | drop the recompute |
| the offer | `SeasonEventOfferTest` · `anOfferOnlyInTheOppositePhaseOfAManualAsset`, `acceptingClampsTheDateAndLinksTheEvent`, `noEventChangesAPhaseByItself` (inv. 93) | offer on a CALENDAR asset |
| the legacy pair | `LegacyAssetPairTest` · `anEqualPairLeavesTheSeasonAlone` (MANUAL null pair round-trips), `aDifferentPairOnManualIs422AndWritesNothing`, `aDifferentPairBecomesCalendarOrYearRound`, `thePairObeysTheStrandsRule` (inv. 128) | translate on MANUAL |
| policy validation | `SchedulePolicyValidationTest` · `offsetRangesPerPolicy` (AT_START −1, 0, 365, 366; PRE_SERVICE 0, −1, −365, −366, null), `meterOnlyIsPhaseOnly` (PRE_SERVICE and offset 5 refused; offset 0 accepted; inv. 95), `aGroupTargetIsContinuousOnly` (inv. 106), `preServiceWithoutABoundaryIs409` (inv. 99), `policyIsNotARuleField` (a policy-only edit keeps the postponement) | accept PRE_SERVICE on YEAR_ROUND with no break |
| the season view | `SeasonViewTest` · `nextBoundaryPerMode` (CALENDAR in and out of season, MANUAL null, YEAR_ROUND null), `activationsAreOrdered`, `inBreakFollowsTheBreakPredicate` | predict a MANUAL start |

## Gate

- `./gradlew :core:test :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` → success. No connected run.
- Anchored: `grep -rnE 'class (SetSeasonMode|SetMaintenanceBreak|RecordSeasonActivation|GetAssetSeason)\b' core/src/main/kotlin/com/loosecannon/servicetag/core/usecase` → four; `grep -rnE '(UPDATE|DELETE)[[:space:]]+(FROM[[:space:]]+)?.?asset_season_activation\b' app/src/main core/src/main` → no output; `git diff --stat <base> -- core/src/main/kotlin/com/loosecannon/servicetag/core/health core/src/main/kotlin/com/loosecannon/servicetag/core/reminders app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/ui` → empty.

## Strings

**None user-visible.** The refusals carry data (titles, dates); the words are B10's (S55, S64), B14's (S54, S56, S57) and B09's codes.

## Must NOT

- write an activation from a journal event, a completion or a mode change other than the switch into MANUAL;
- predict a manual START, or default a window, a break or a margin;
- let a legacy pair change a MANUAL asset, or reset any 1.4-only field;
- make `servicePolicy` or `policyOffsetDays` a rule field;
- bump `updatedAt` or write a row for an unchanged command;
- touch B05's files, the API or a screen.

## Size

Medium: four small use cases, two extended ones and one pure view; most of the weight is in the refusal matrix. No split expected.

## Carry-forward from B01's review (controller, 2026-09-24)

- **M4:** B01 made the shipped fixtures that create a seasonal asset through `createAsset` set CALENDAR directly (a `FakeGraph.calendar` helper and one inline copy in `DashboardAttentionTest`). Once this brief's legacy-pair rule lands in the asset commands (an asset given a season window takes CALENDAR; inv. 88), remove that helper and the inline copy and add the create-path case to this brief's matrix: creating an asset with a window yields `seasonMode = CALENDAR` and its non-CONTINUOUS schedules read INACTIVE_SEASON out of season; RED mutation: leave `seasonMode` at its default.

## Carry-forward from B02's review (controller, 2026-09-24)

- **M3:** `BuildReminderSubjects` kept two unused constructor parameters (`states`, `assets`, marked `@Suppress("unused")`) because only the AppGraph owner may change its wiring. This brief owns `AppGraph` in wave 3: drop the two parameters and the suppression, and update the one wiring site. Mechanical; no test row.
