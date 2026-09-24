# B14 — asset detail: condition, health and season

**Read first:** master plan §1, §7.2 (`SeasonView`, the activation rules), §9, §10.2, §13.1, §13.3, §14, §19.
**Spec:** §10.3 (asset detail), §3.1, §3.3 (Start and End), §5.1 (history), §5.3 (the dangling link), §6.5 ("nothing hides behind an aggregate"), §6.6 (explanation), §10.6; strings S21, S24, S39, S42–S50, S54, S56, S57, S94, S107, S138.
**Wave 8, lane B,** beside B13. **After B07, B10 and B12.** Device second in wave 8.

## Goal

Give every asset one page where its three independent facts are read and acted on. The identity plate — and every component row — carries the **condition badge** beside retired, archived and out of season. A **Condition** section shows the current condition, "Change condition", "Mark operational" for a DOWN or DEGRADED asset, and the full history, including a record whose linked event was deleted. A **Health** section puts every CRITICAL subject and every DOWN or DEGRADED component **first**, then the aggregate, then each contributor with its driver line, and closes with the ratified footer. A **Season** section shows the window or the phase, the next boundary, **Start season** / **End season** with their dialogs and refusals for a manual asset, the season history and the maintenance break. The asset list's out-of-season mark follows the season phase, so a manual asset reads truthfully. This brief reads B07's views and draws with B12's words and composables.

## Files

**Modify**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/asset/AssetDetailScreen.kt` — `plateBadges` gains the condition badge (the fourth independent fact, `AssetDetailScreen.kt:613-632`); component rows gain it; the Condition, Health and Season sections; the Start/End dialogs.
- `app/.../ui/asset/AssetViewModels.kt` — **the detail and list view models only**: the detail state gains `condition: ConditionView?` and its history, `health: AssetHealthView`, `season: SeasonView`, and the Start/End actions; `outOfSeasonOn` becomes the season phase from `SeasonContext` (MANUAL read from its activation rows), for the list and the detail. The edit view model (B10's) is not touched.
- `app/.../ui/asset/AssetMaintenanceSections.kt`, `AssetsScreen.kt` — only where the phase or the badge is drawn.
- Tests: `app/src/test/kotlin/com/loosecannon/servicetag/ui/asset/AssetViewModelsTest.kt` (detail and list cases), `app/src/test/.../ui/asset/AssetSeasonActionsTest.kt` (new); `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/asset/AssetDetailConditionHealthSeasonTest.kt` (new).

**Untouched:** `ui/dashboard/**`, `DueItemRow.kt`, `WhyLines.kt`, `MaintenanceScreen.kt` (B13's, same wave); `di/AppGraph.kt` and `FakeGraph.kt` (the view models read the graph fields B04, B06 and B07 created: `getAssetSeason`, `recordSeasonActivation`, `assetHealthReadModel`, `conditions`, `events`); `ui/nav/**`; `ui/condition/**`, `ui/health/**` (B12's, reused); `core/**`; `api/**`.

## Interfaces

**Consumes:** `AssetHealthReadModel.forAsset`, `AssetHealthView`, `ConditionView`, `ComponentCondition` (B07); `GetAssetSeason`, `SeasonView`, `RecordSeasonActivation`, `ActivationCommand`, `SeasonAlreadyStarted`, `SeasonAlreadyEnded`, `SeasonValidation` (B04); `ConditionHistory` (B06); `SeasonContext` (B02); `ConditionBadge`, `HealthBadge`, `ChangeConditionSheet`, `MarkOperationalDialog`, `conditionWord`, `componentLine`, `bandWord`, `driverLineText`, `HealthPlurals` and `AndroidHealthPlurals(resources: Resources)`, built from the screen's resources (the singular forms of S99 and S102, dec. 29), `aggregateLine`, `criticalLine`, the S40/S41 constants (B12); S29, S32, S33, S58, S60, S61 (B10's constants); `Route.HealthSubjectEdit` is **not** needed here.

**Produces:** nothing another brief calls.

### The sections (spec §10.3), in this order after the shipped plate

1. **Condition — S5.** The current `ConditionBadge` (or S4); **S6 "Change condition"** opens B12's sheet; **S7 "Mark operational"** (DOWN or DEGRADED only) opens B12's confirmation. **S21 "Condition history"**, newest first by the ordering key reversed: each row its word, date, the reason or S23, and **S24** when its `eventId` names an event that no longer exists (never an error, never hidden).
2. **Health — S94.** First every CRITICAL subject as S109 and every DOWN or DEGRADED in-service component (any depth) as S27; then the aggregate as S108, or S98 when NOT TRACKED, with **S138** when the primary fell back; then each non-archived subject: its name, `HealthBadge` or S98, and its driver lines (S99–S106, S142, S143 via `driverLineText`); then **S107**. A DOWN asset's health is never drawn above its condition (section 1 always precedes it).
3. **Season.** YEAR_ROUND: S29. CALENDAR: S32 and S33 with the dates, the phase — **S39 IN SEASON** or the shipped out-of-season word — and **S50 "Season ends <date>"** or **S49 "Next season starts <date>"** from `nextBoundaryOn`. MANUAL: the phase; **S40 "Start season"** when out, **S41 "End season"** when in. **Start** opens **S42** with **S43** ("…becomes active again from <date>."), **End** opens **S44** with **S45**; each dialog has a date defaulting to today, bounded to `[the latest row's date, today]`, a refusal drawn as **S54 "Choose a date from <date> to today."**, the confirm action (S40 or S41) and Cancel; `SeasonAlreadyStarted` / `SeasonAlreadyEnded` (a race) draw **S56** / **S57**. **S48 "Season history"** lists **S46 "Season started"** / **S47 "Season ended"** rows with their dates, newest first, and stays readable after the asset leaves MANUAL. When a break is set: S58 with S60 and S61 and the dates.

### What each part reads (so the screen derives nothing)

| part | source | fields |
|---|---|---|
| plate and component badges | `AssetHealthView.condition`, and each component's current condition | `condition`, `since` |
| Condition section | `ConditionView`; the asset's rows through `ConditionHistory.of(...).ordered` | the word, `since`, `reason`, `occurredOn`, `occurredTime`, `eventExists` |
| Health section | `AssetHealthView.result` (`critical`, `aggregate`, `fallback`, `subjects`) and `components` | each subject's `value` and `DriverLine`s |
| Season section | `SeasonView` | `seasonMode`, the window, `phase`, `nextBoundaryOn`, the break, `inBreak`, `activations` |
| Start / End | `RecordSeasonActivation` with `ActivationCommand(action, occurredOn)` | the dialog's date only; `eventId` is null |
| list phase | `SeasonContext.of(asset.seasonInputs(rows)).phaseAt(today)` | activation rows read only for a MANUAL asset |

The view model reads these once per load and on the repository flows the shipped detail model already observes (the asset, its events, its conditions and its activations), so a Start, an End or a new condition redraws the page without a manual refresh.

## Invariants this brief must hold

**(UI)** **89** (history is read-only: no row can be edited or removed from here), **107** and **110** (history shows every row; a correction appears as a new row), **118** (NOT TRACKED drawn as S98, never 100), **119** (criticals and DOWN/DEGRADED components first, whatever the aggregate; health never without condition), **93** (Start and End write only on confirm), and spec §3.1's phase for a MANUAL asset on the list.

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the plate misses a fact | `AssetViewModelsTest` · `thePlateCarriesConditionBesideRetiredArchivedAndOutOfSeason` | drop the badge when retired |
| a manual asset misread | `AssetViewModelsTest` · `outOfSeasonFollowsThePhaseIncludingManual` (list and detail; a MANUAL asset after END reads out of season) | keep the `MM-DD` predicate |
| history incomplete | `AssetViewModelsTest` · `historyIsEveryRowNewestFirstWithADanglingLinkAsS24` | hide rows whose event is gone |
| a critical hidden | `AssetViewModelsTest` · `anAverageNominalAssetShowsItsCriticalSubjectFirst`, `downComponentsAtAnyDepthComeFirst` (inv. 119) | order by the aggregate |
| NOT TRACKED as a number | `AssetViewModelsTest` · `untrackedSubjectsAndAggregatesAreS98`, `theFallbackShowsS138` | render 100 |
| Start/End wrong | `AssetSeasonActionsTest` · `startAndEndSendTheChosenDate`, `aDateOutsideTheRangeShowsS54AndWritesNothing`, `aRaceShowsS56OrS57`, `cancelWritesNothing` | send today regardless |
| the calendar line | `AssetSeasonActionsTest` · `s49OutOfSeasonS50InSeasonNothingForManual` | predict a MANUAL start |
| the screen | `AssetDetailConditionHealthSeasonTest` (connected) · `conditionSectionAndActions`, `healthOrderCriticalsComponentsAggregateContributorsFooter`, `manualSeasonStartDialogAndHistory`, `calendarSeasonLines` | put the aggregate before the criticals |

## Edge cases

- **An asset that left MANUAL** shows its new mode's lines and keeps S48 and the old rows readable.
- **A MANUAL asset with no row** (only an import makes one) reads out of season, offers S40 and shows an empty history.
- **A latest row dated today** narrows the Start/End date range to today alone.
- **Every subject archived** leaves the Health section with S98 for the aggregate and the S107 footer; no contributor lines.
- **A retired or archived asset** still shows its condition, history, health and season on its own page; lifecycle bounds only the dashboard (spec §5.1).
- **A condition row whose linked event was deleted** shows S24 in place of the link and keeps every other field.
- **A component's own page** shows its own three sections; its parent's page lists it among the DOWN or DEGRADED components when it is one.
- **A CALENDAR asset with a break inside its season** shows both the season lines and the break; the break never changes the phase word.

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain`; connected, one class each (after B13 releases the device): `com.loosecannon.servicetag.ui.asset.AssetDetailConditionHealthSeasonTest`, `com.loosecannon.servicetag.ui.AssetModelDeviceProofTest`, `com.loosecannon.servicetag.ui.asset.AssetTagsSectionTest` (still green).
- Anchored: `grep -rnE '\.inSeason\(' app/src/main/kotlin/com/loosecannon/servicetag/ui/asset` → no output (the phase comes from `SeasonContext`; the pattern also catches the shipped `SeasonWindow.inSeason(` alias call at `AssetViewModels.kt:199`, M6); `git diff --stat <base> -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/di app/src/main/kotlin/com/loosecannon/servicetag/ui/dashboard app/src/main/kotlin/com/loosecannon/servicetag/ui/nav` → empty.

## Strings

**S21, S24, S39, S42–S50, S54, S56, S57, S94, S107, S138**, verbatim. **Uses** S1–S7, S17, S18, S22, S23, S27, S40, S41, S95–S109, S142, S143 (B12) and S29, S32, S33, S58, S60, S61 (B10), plus the shipped Cancel and out-of-season word.

## Must NOT

- offer any way to edit or delete a condition or activation row;
- draw health before condition, or let the aggregate decide which subjects appear;
- predict or display a future manual start;
- write a season row without the dialog's confirmation, or outside `[latest row, today]`;
- touch the edit view model, the dashboard, navigation, `AppGraph` or `:core`.

## Review focus

- Section order is Condition, Health, Season, and inside Health the criticals and components come before the aggregate.
- No control edits or deletes a history row; Start and End write only through the dialog.
- The list's out-of-season mark no longer calls `inSeason` directly, under either name (`Season` or its `SeasonWindow` alias).

## Size

Medium-large: three sections and two dialogs on one screen, most of the logic in JVM view-model tests. Split seam if needed: **B14a** Condition and Health; **B14b** Season and the list's phase.

## Carry-forward from B04 (controller, 2026-09-24)

- `SeasonView.activations` carries the full activation history in every season mode, but the phase reads it only in MANUAL. Asset detail shows the history (newest first, decision 41) only on a MANUAL asset; on CALENDAR and YEAR_ROUND assets the section is absent. A 422 body refusal wins over a 409 on the stored state when both apply.
- `ScheduleEditViewModel.fieldOf` maps `PolicyOffsetInvalid` and `SeasonPolicyNeedsATimeRule` to `ScheduleField.SEASON` (B04's compile-through); B08 revisits the mapping when it owns the editor.
