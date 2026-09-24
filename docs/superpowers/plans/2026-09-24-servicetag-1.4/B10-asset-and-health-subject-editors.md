# B10 — the asset editor's season, break and health, and the health subject editor

**Read first:** master plan §1, §7.2 (the season and break rules), §10.1 (subjects, the policy, `SaveAssetSettings`), §13.3, §14, §19.
**Spec:** §3.2, §3.4, §4.4, §6.1, §6.3 (thresholds and templates), §6.5, §10.4 (asset editor, health subject editor); strings S28–S38, S55, S58–S64, S111–S136.
**Wave 6, lane B,** beside B09. **After B04 and B06** (and B08, whose S137 constant it uses). Device first in wave 6.

## Goal

Let the owner configure everything #14, #60 and #61 need from the phone, with nothing decided for them. The asset editor replaces 1.2's single "Year-round" switch with **"Operating season"** (year-round, same dates every year, or started and ended by hand — the last asking **"Is this asset in season right now?"** when it is first chosen), adds **"Maintenance break"** (never prefilled), lists the asset's **health subjects**, and offers **"Combine health by"** — and saves all of it through `SaveAssetSettings` in one transaction, showing S55 or S64 by name when a change would strand pre-service work. A new **health subject editor** creates and edits one subject: what it is, what wears it down, its quick action or schedule, and three threshold fields that start **empty** and are filled either by hand or by a starting point the owner confirms (S129). Nothing is drawn that the spec did not ratify.

## Files

**Create**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/health/HealthSubjectEditViewModel.kt`, `HealthSubjectEditScreen.kt`.
- Tests: `app/src/test/kotlin/com/loosecannon/servicetag/ui/asset/AssetSettingsFormTest.kt`, `app/src/test/.../ui/health/HealthSubjectEditViewModelTest.kt`; `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/asset/AssetEditorSeasonAndHealthTest.kt`, `app/src/androidTest/.../ui/health/HealthSubjectEditorTest.kt`.

**Modify**

- `app/.../ui/asset/AssetEditScreen.kt` — the "Operating season", "Maintenance break", "Health subjects" and "Combine health by" sections; `SeasonField` and its two retired sentences go.
- `app/.../ui/asset/AssetViewModels.kt` — **the edit view model only** (`AssetEditState` and its handlers): season mode, window, `manualPhase`, break, subjects list, aggregation and primary; `save()` builds an `AssetSettingsCommand`. The list and detail view models in this file are B14's in wave 8 and are not touched here.
- `app/.../ui/nav/Route.kt`, `ServiceTagRoot.kt` — the subject editor's route (create for an asset; edit a subject).
- `app/src/test/.../ui/asset/AssetViewModelsTest.kt` — edit-model cases adjusted.

**Untouched:** `api/**`, `docs/**` (B09's, same wave); `di/AppGraph.kt` (the view models read `saveAssetSettings`, `saveHealthSubject`, `archiveHealthSubject`, `healthSubjects`, `schedules`, `profiles` through their `(graph)` constructors); `core/**`; the asset detail and list (B14); the dashboard (B13).

## Interfaces

**Consumes:** `SaveAssetSettings`, `AssetSettingsCommand`, `SaveHealthSubject.create/update`, `ArchiveHealthSubject`, `HealthSubjectCommand`, `HealthPolicyCommand`, `HealthScheduleTaken`, `HealthSubjectIsPrimary`, `HealthValidation` (B06); `SeasonModeCommand`, `BreakCommand`, `SeasonModeStrandsPolicy`, `BreakStrandsPolicy`, `SeasonValidation` (B04); the S137 constant (B08).

**Produces:** the subject editor's route, `Route.HealthSubjectEdit(assetId, subjectId?)`, for B14's "Add health subject" and subject rows to open. Nothing else.

### The asset editor (spec §10.4)

- **Operating season (S28):** options S29 Year-round · S30 Same dates every year · S31 Started and ended by hand. S30 shows S32 and S33 (`MM-DD`) with helper S34. S31 shows helper S38. **Choosing S31 on an asset that is not already MANUAL** shows S35 with S36 In season / S37 Out of season and **no default** — Save stays disabled until one is chosen, and the choice becomes `manualPhase`. An asset already MANUAL shows no S35 and sends no `manualPhase` (inv. 92, UI half).
- **Maintenance break (S58):** toggle S59, **off unless a break is stored**; turning it on shows S60 and S61 **empty** with helper S62 (inv. 121). `BLACKOUT_COVERS_THE_YEAR` is drawn as S63 under the fields.
- **Health subjects (S111):** the asset's subjects in `sortOrder`, archived ones marked by their S136 action ("Restore subject"); **S112 "Add health subject"** opens the subject editor. Shown on an **existing** asset only — a subject needs its asset's id (**Plan decision**; a new asset gains subjects after its first save).
- **Combine health by (S131):** the four S132 options; "One subject" shows **S134 "Which subject?"** listing the non-archived subjects, so `HEALTH_PRIMARY_INVALID` cannot be reached from here.
- **Save** sends one `AssetSettingsCommand`. `SeasonModeStrandsPolicy` → **S55** with the schedules' titles; `BreakStrandsPolicy` → **S64** with the titles; the form stays as typed and nothing is written. The asset's own field problems keep their shipped messages.

### The health subject editor (spec §10.4, §6.1, §6.3)

- Name (the shipped word), **S113 "What is it?"** (S114 / S115 / S116), **S117 "What wears it down?"** (S118 / S119).
- **Age since replacement:** **S120 "Replacement quick action"** — **S121 "Any replacement"** plus this asset's REPLACEMENT quick actions only; thresholds labelled by **S123**'s three labels. **No starting point** is offered (spec §6.3: AGE has none).
- **Overdue maintenance:** **S122 "Maintenance schedule"** — this asset's asset-targeted schedules with a time rule, **archived schedules excluded** (master dec. 45, ruled on I8); thresholds labelled by **S124**'s three labels; **S127 "Use a starting point"** offers the two **S128** names; picking one opens **S129** with **S130 "Use these numbers"** and Cancel, and only **S130** fills the three fields (inv. 121). Cancel fills nothing.
- **The three thresholds start empty.** Save is disabled until all three hold a number, with **S126** beside the disabled Save; an order that is not strictly increasing is **S125**.
- **S133 "Weight"** appears only when the asset combines by Weighted average.
- **Restore subject (S136)** is **disabled** while the subject's link would be refused — its schedule archived, retargeted or rule-less (B06's full link re-check) — so the owner edits the subject to another schedule first; no refusal sentence is needed. A restore refused because the schedule is taken still answers S135.
- **The race rule** (B08's shape): if an API or MCP write archives, retargets or de-rules the schedule between the view model's check and the tap, a Save or Restore answered `FOREIGN_SCHEDULE` or `HEALTH_SCHEDULE_NEEDS_A_TIME_RULE` **reloads the S122 picker and the Restore state, writes nothing and draws no sentence**.

### Fields with no ratified refusal (master dec. 46, ruled on I10 — mechanisms, never new words)

| field | mechanism |
|---|---|
| S32 / S33 under S30 | both **marked required** — the mark is **non-verbal**: an asterisk in the ratified label or the field's error outline, never "Required" text; Save **disabled** until both hold a valid `MM-DD`; one date alone draws no sentence (the shipped "Set both season dates or neither" is no longer drawn here — "neither" is not allowed for CALENDAR, so it would be false) |
| S60 / S61 | the same: both required once S59 is on, with the same non-verbal mark; Save disabled until both are valid |
| any `MM-DD` malformed | the shipped **"Not a real month and day"** under the field |
| the three thresholds | a **digits-only filter capped at 36,500** |
| S133 Weight | a **1–10 stepper**, never free text |
| the subject name | a **60-character input limit** |

If any field is still found to need a sentence, the controller escalates to the owner before this wave; the implementer never writes one.
- **S135** answers `HealthScheduleTaken`. **S136**'s two actions archive and restore; archiving the primary answers **S137**.

## Invariants this brief must hold

**(UI)** **92** (S35 only on a switch into MANUAL, with no default), **120** (S135), **121** (no prefilled break, margin or threshold; a template only after S130), and the atomic save of **SaveAssetSettings** (a refusal writes nothing).

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the manual phase defaulted | `AssetSettingsFormTest` · `choosingManualAsksS35WithNoDefaultAndSaveWaits`, `anAssetAlreadyManualSendsNoPhase` | default to In season |
| the wrong command | `AssetSettingsFormTest` · `saveSendsOneSettingsCommandWithAllFourParts` | save the break separately |
| a prefilled break | `AssetSettingsFormTest` · `theBreakIsOffAndEmptyUnlessStored` | prefill 12-01 → 02-28 |
| strands shown wrong | `AssetSettingsFormTest` · `aStrandsRefusalShowsS55OrS64WithTitlesAndKeepsTheForm` | show S55 for a break refusal |
| the year-long break | `AssetSettingsFormTest` · `aBreakCoveringTheYearShowsS63` | swallow the refusal |
| the primary unreachable | `AssetSettingsFormTest` · `oneSubjectOffersOnlyNonArchivedSubjects` | list archived subjects |
| thresholds prefilled | `HealthSubjectEditViewModelTest` · `thresholdsStartEmptyAndSaveWaitsForAllThree` (S126) | default 14 / 45 / 120 |
| a template applied silently | `HealthSubjectEditViewModelTest` · `aStartingPointFillsOnlyAfterS130`, `cancelFillsNothing`, `ageOffersNoStartingPoint` | fill on the S128 tap |
| the wrong pickers | `HealthSubjectEditViewModelTest` · `ageListsOnlyReplacementQuickActionsWithAnyReplacement`, `overdueListsOnlyThisAssetsTimedSchedules` | list meter-only schedules |
| order and weight | `HealthSubjectEditViewModelTest` · `aNonIncreasingOrderShowsS125`, `weightOnlyUnderWeightedAverage` | show weight always |
| refusals | `HealthSubjectEditViewModelTest` · `aTakenScheduleShowsS135`, `archivingThePrimaryShowsS137` | ignore `HealthScheduleTaken` |
| the screens | `AssetEditorSeasonAndHealthTest` (connected) · `operatingSeasonOptionsAndFields`, `theManualQuestion`, `theBreakToggle`, `healthSubjectsAndCombine`; `HealthSubjectEditorTest` (connected) · `emptyThresholdsDisableSave`, `startingPointConfirmation`, `archiveAndRestore` | draw S35 on an already-MANUAL asset |
| retired words | `AssetEditorSeasonAndHealthTest` · `theRetiredSeasonSentenceIsGone` | keep "Off means…" |
| a half-filled window (I10) | `AssetSettingsFormTest` · `calendarWithOneDateDisablesSaveWithNoSentence`, `aHalfFilledBreakDisablesSave`, `aMalformedMonthDayShowsTheShippedSentence` | draw "Set both season dates or neither" |
| out-of-range numbers (I10) | `HealthSubjectEditViewModelTest` · `thresholdsTakeDigitsCappedAt36500`, `weightIsAStepperFrom1To10`, `theNameStopsAt60Characters` | accept 36,501 |
| an archived schedule offered (I8) | `HealthSubjectEditViewModelTest` · `s122ExcludesArchivedSchedules`, `restoreIsDisabledWhileTheLinkWouldBeRefused` | list every timed schedule |
| a race reaches the refusal (F5) | `HealthSubjectEditViewModelTest` · `aForeignOrRuleLessAnswerReloadsThePickerAndRestoreStateAndDrawsNothing` (the fake use case answers each code once; no write, no text, picker and Restore state re-read) | show the exception's message |
| the required mark speaks (F4) | `AssetSettingsFormTest` · `theRequiredMarkIsNonVerbal` (the form state adds no literal outside §10.7 and the shipped words; the mark is an asterisk or an error outline) | add "Required" supporting text |

## Edge cases

- **MANUAL → CALENDAR or YEAR_ROUND** shows no S35; the activation rows stay as history (B14 draws them). **CALENDAR → MANUAL** asks S35.
- **A break turned off** on an asset whose PRE_SERVICE work relies on it answers S64 and keeps the toggle on in the form.
- **"One subject" with no non-archived subject** shows an empty S134 picker and Save stays disabled, so `HEALTH_PRIMARY_INVALID` is unreachable.
- **An asset with no REPLACEMENT quick action** offers only S121 under S120. **An asset with no timed asset-targeted schedule** offers an empty S122 and Save stays disabled for an overdue subject.
- **A hidden weight** (the asset does not combine by Weighted average) keeps its stored value; it is not reset to 1.
- **`t1 = 0`** is legal (`0 ≤ t1`); **36,500** is the upper bound; the field accepts digits only.
- **Kind and driver are independent**: a MEDIUM subject may use age, a PART may use overdue maintenance; only the clock differs (spec §6.1).
- **Restoring an archived subject** whose schedule is now driven by another answers S135 and stays archived; one whose schedule is archived, retargeted or rule-less cannot be restored (the action is disabled) until it is edited onto a valid schedule.
- **The subject editor's Cancel** writes nothing; leaving the asset editor without Save writes nothing.

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain`; connected, one class each: `com.loosecannon.servicetag.ui.asset.AssetEditorSeasonAndHealthTest`, `com.loosecannon.servicetag.ui.health.HealthSubjectEditorTest`, `com.loosecannon.servicetag.ui.EditorsDeviceProofTest` (still green).
- Anchored: `grep -rnF 'Off means this asset is only in use' app/src/main` → no output; `git diff --stat <base> -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/kotlin/com/loosecannon/servicetag/di app/src/main/kotlin/com/loosecannon/servicetag/ui/asset/AssetDetailScreen.kt` → empty.

## Strings

**S28–S38, S55, S58–S64, S111–S136**, verbatim; the shipped Name and Cancel. **Uses** S137 (B08's constant). S123, S124, S128, S132 and S136 are each one ratified set of words, split only at their "·" separators.

## Must NOT

- default the manual phase, the break, a margin or a threshold, or apply a starting point without S130;
- write a refusal sentence for a field that has none (the mechanisms above make each unreachable), or draw the false "Set both season dates or neither";
- offer a starting point for an age subject;
- save the four settings in more than one transaction, or write anything on a refusal;
- touch the asset list or detail view models, the API, `AppGraph` or `:core`.

## Review focus

- One `SaveAssetSettings` call per Save, and nothing written on any refusal (the strands and year-long-break cases are the ones to re-run).
- No default anywhere: S35 unanswered, the break empty, thresholds empty, the template applied only by S130.
- S123 / S124 / S128 / S132 / S136 are split only at their "·" separators and never reworded.

## Size

Medium-large: one editor extended, one editor new, both mostly form state proved in JVM. Split seam if needed: **B10a** the asset editor; **B10b** the subject editor and its route.

## Carry-forward from B06's review (controller, 2026-09-25)

- The asset editor never re-sends a dangling TRACK_ONE primary (a primary subject id that no longer names a non-archived subject): when the stored primary is dangling, the editor's save omits it (the policy falls back as the engine already does) instead of letting every settings save on that asset be refused. One form-test case (RED: send the stored id through).
