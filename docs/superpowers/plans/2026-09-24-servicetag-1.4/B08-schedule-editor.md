# B08 — the schedule editor: "When should this maintenance be done?"

**Read first:** master plan §1, §7.2 (offsets and refusals), §10.1 (the link guard), §13.3, §14, §19.
**Spec:** §4.1 (editor wording), §4.2 (the offset), §10.4 (the schedule editor), §6.1 (the guard, S140–S142); strings S65–S84, S137, S140, S141.
**Wave 5, lane B,** beside B07. **After B04 and B06.** Device first in wave 5.

## Goal

Replace 1.2's "Out of season" pair — "Pause with the asset's season" / "Remind me year round", both retired — with spec §10.4's question, **"When should this maintenance be done?"**, offering exactly the options the target asset's season and break make meaningful, with the offset fields, helpers and warnings the spec ratified, and nothing prefilled that the owner must decide (the pre-service margin starts empty). The question is absent where it means nothing: an asset with no season and no break, and every group target. When a save or an archive would strand a health subject, the editor and the schedule detail ask S140 and, on **Archive both**, repeat the request with the unlink flag; Cancel writes nothing. The editor never shows a policy word the owner did not choose.

## Files

**Modify**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/ScheduleEditViewModel.kt` — the form's `seasonBehavior` becomes a policy choice (`PolicyOption`) plus the offset text; the option set derived from the target asset's `seasonMode`, break and the schedule's rule sides; the link-guard dialog state; `command()` fills `servicePolicy` and `policyOffsetDays`.
- `app/.../ui/maintenance/ScheduleEditScreen.kt` — the question, its options, the offset fields, helpers, warnings and the dialog; the three retired constants deleted.
- `app/.../ui/maintenance/ScheduleDetailViewModel.kt`, `ScheduleDetailScreen.kt` — the archive action's link-guard dialog.
- Tests: `app/src/test/kotlin/com/loosecannon/servicetag/ui/maintenance/ScheduleEditViewModelTest.kt` and `ScheduleDetailViewModelTest.kt` (extended), `…/ui/maintenance/SchedulePolicyFormTest.kt` (new); `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/maintenance/ScheduleEditorTest.kt` (extended).

**Untouched:** `DueReadModel.kt`, `DueItemRow.kt`, `MaintenanceSheetViewModel.kt` and every file B07 creates (same wave); `AppGraph.kt` (the editor reads existing graph fields: `saveSchedule`, `archiveSchedule`, `assets`); `core/**`; `api/**`; the dashboard.

## Interfaces

**Consumes:** `SaveSchedule.run(id, cmd, unlinkHealthSubject)`, `ArchiveSchedule.run(id, archived, unlinkHealthSubject)`, `ScheduleDrivesHealthSubject(subjectId, name)`, `HealthSubjectIsPrimary` (B06); `ScheduleProblem.PolicyOffsetInvalid`, `SeasonPolicyNeedsATimeRule`, `SeasonFollowsAssetOnGroupTarget`, `PreServiceNeedsDates` (B04); `Asset.seasonMode`, the window and break (B01); `SeasonContext.seasonEndAt` and `cycleStartAt` for S84 (B02).

**Produces:** the S137 constant (for B10). Nothing else another brief calls; the option model is private to the editor:

```kotlin
enum class PolicyOption { BEFORE_SEASON, BEFORE_BREAK, WHEN_SEASON_STARTS, AFTER_BREAK, WHENEVER_DUE }
// WHEN_SEASON_STARTS also carries StartCountingFrom { SEASON_START, OWN_DATE } — AT_START vs RESUME_CLAMPED
```

### The option set (spec §10.4, exactly)

| target asset | options shown, in order |
|---|---|
| CALENDAR (with or without a break) | S66 Before the season starts · S67 When the season starts · S68 Whenever it is due |
| MANUAL | S67 · S68; plus S69 Before the maintenance break when a break is set |
| YEAR_ROUND with a break | S69 Before the maintenance break · S70 After the maintenance break · S68 |
| YEAR_ROUND, no break | the question is **not drawn**; the policy is CONTINUOUS |
| a group target | the question is **not drawn**; the policy is CONTINUOUS (inv. 106) |

- **Mapping:** S66, S69 → PRE_SERVICE with `policyOffsetDays = −(Days before it starts)`; S67 → IN_SERVICE_AT_START (with **S73 "Start counting from"**: S74 "The season's start" + S72 "Days after it starts", 0–365, default 0) or IN_SERVICE_RESUME_CLAMPED (S75, no offset); S70 → IN_SERVICE_AT_START, offset 0, no field; S68 → CONTINUOUS.
- **S71 "Days before it starts"** accepts 1–365 and is **empty until the owner enters it** (inv. 121); Save with it empty shows **S83** and writes nothing. **No unratified sentence is ever needed (master dec. 46, ruled on I10):** S71 and S72 take a **digits-only input filter capped at 365** (a fourth digit, or a value above 365, is not accepted as typed), and **Save stays disabled while S71 is 0**, as it is while S71 is empty — so `POLICY_OFFSET_INVALID` is unreachable from the editor and no refusal has to be worded. If a field is still found to need a sentence, the controller escalates to the owner before this wave; the implementer never writes one.
- **A meter-only schedule** (spec §10.4): no pre-service option and no offset field. **Plan decision:** the in-service option keeps its asset's label — S67 on CALENDAR and MANUAL, S70 on YEAR_ROUND with a break — because "When the season starts" names nothing on an asset without a season.
- **Helpers**, under the chosen option: S78 (before the season), S79 (before the break), S80 (when the season starts), S81 (after the break), S82 (whenever it is due).
- **Warnings, never refusals:** **S76** when S67 is chosen on a CALENDAR asset and the anchor date lies outside the window; **S84** when S74 is chosen on a CALENDAR asset and the season's start plus the offset falls after that season's end.
- **Plan decision — S77:** when the stored policy is not CONTINUOUS and the asset has neither a season nor a break (reachable only through a merge), the editor shows **S77** and the question with **S68 alone**; Save stays possible only once the owner picks it, so nothing is changed silently. A `PreServiceNeedsDates` refusal on Save (a race with an asset edit) shows S77 the same way.
- **Loading a stored row** selects its option: PRE_SERVICE −14 → the before-option with 14; AT_START 5 → S67, S74, 5; RESUME_CLAMPED → S67, S75; CONTINUOUS → S68.

### The link-guard dialogs (spec §6.1, D-30)

A Save refused with `ScheduleDrivesHealthSubject(name)` opens **S140** ("This schedule drives the health subject <name>. Archive that subject as well?") with **S141 "Archive both"** and the shipped Cancel. Archive both repeats the same command with `unlinkHealthSubject = true`; Cancel closes the dialog and writes nothing, leaving the form as typed. The schedule detail's archive action does the same through `ArchiveSchedule`. A `HealthSubjectIsPrimary` answer to Archive both is drawn with **S137**, which this brief defines beside S140 and S141 (it lands a wave before B10, which uses the same constant for the subject editor's archive).

## Invariants this brief must hold

**(UI)** **106** (no question for a group), **121** (the margin empty until entered), **130** (the guard's dialogs; Cancel writes nothing). It must not make **87** reachable the wrong way: a policy-only edit keeps the postponement (B01's rule, observed here).

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| wrong options for the asset | `SchedulePolicyFormTest` · `theOptionSetPerAssetMode` (the five rows above) | offer S66 on MANUAL |
| a meter-only pre-service choice | `SchedulePolicyFormTest` · `aMeterOnlyScheduleHasNoPreServiceOptionAndNoOffset` | keep S71 for meter-only |
| the offset's sign or default | `SchedulePolicyFormTest` · `optionsMapToPolicyAndSignedOffset` (14 before → −14; S74 with nothing typed → 0; S75 → null; S70 → 0) | send +14 for PRE_SERVICE |
| a prefilled margin | `SchedulePolicyFormTest` · `theMarginIsEmptyUntilEnteredAndSaveRefusesWithS83` (nothing reaches `SaveSchedule`) | default the margin to 14 |
| S71 out of range (I10) | `SchedulePolicyFormTest` · `s71TakesDigitsOnlyCappedAt365AndZeroKeepsSaveDisabled` ("400" is not accepted, "0" disables Save, no refusal text appears) | accept "0" and send it |
| S72 out of range (I10) | `SchedulePolicyFormTest` · `s72TakesDigitsOnlyCappedAt365` | allow a fourth digit |
| a stored row misread | `SchedulePolicyFormTest` · `aStoredPolicyLoadsIntoItsOption` | map RESUME_CLAMPED to S74 |
| the warnings | `SchedulePolicyFormTest` · `s76WhenTheAnchorIsOutsideTheSeason`, `s84WhenTheOffsetPassesTheSeasonsEnd` | compare against the break instead of the window |
| the merged state | `SchedulePolicyFormTest` · `aStoredPolicyWithNoBoundaryShowsS77AndOnlyS68` | hide the question and keep PRE_SERVICE |
| the guard, editor | `ScheduleEditViewModelTest` · `aGuardRefusalAsksAndArchiveBothRetriesWithTheFlag`, `cancelWritesNothing` | retry without the flag |
| the guard, detail | `ScheduleDetailViewModelTest` · `archivingADrivingScheduleAsksFirst`, `thePrimaryAnswerShowsS137` | archive the schedule alone |
| the screen | `ScheduleEditorTest` (connected) · `theQuestionIsHiddenForAGroupAndAYearRoundAssetWithoutABreak`, `aCalendarAssetOffersThreeOptionsWithHelpers`, `startCountingFromShowsOnlyUnderWhenTheSeasonStarts`, `theDaysBeforeFieldStartsEmpty`, `theLinkGuardDialogConfirmsAndCancels` | draw the question for a group |
| retired words linger | `ScheduleEditorTest` · `theRetiredOptionWordsAreGone` | keep "Remind me year round" |

## Edge cases

- **A new schedule** starts on **S68** (CONTINUOUS), the API's own default for an omitted policy; **Plan decision:** a policy has a safe default, a margin does not, so only the margin starts empty.
- **Removing the time rule in the same edit** recomputes the option set: a selected pre-service option is no longer offered, the selection clears and the question (S65) stands unanswered with Save disabled until the owner picks again — no refusal string is needed because the refusal is unreachable. An offset typed under S74 is hidden and sent as 0, the only value a meter-only schedule may carry.
- **Editing a group-targeted schedule** always sends CONTINUOUS; the question never appears.
- **A stored policy the current option set cannot show** (the asset's mode or break changed through a merge) takes the S77 path above rather than a guess.
- **Archive both answered by `HealthSubjectIsPrimary`** shows S137 and writes nothing — neither the schedule change nor the subject archive.
- **A title-, lead- or policy-only edit** never clears a postponement or moves the pin (B01's #64 rule); the editor adds no side effect of its own.
- **The S84 check** uses the season span that contains the season start the offset is counted from; a wrapping season (for example 10-15 → 04-15) is measured across the year end.
- **Cancel on the editor** writes nothing, as today.

## Gate

- `./gradlew :app:testDebugUnitTest --console=plain` → zero failures, zero skips; counts recorded.
- `./gradlew :app:compileDebugAndroidTestKotlin --console=plain`; connected, one class each: `com.loosecannon.servicetag.ui.maintenance.ScheduleEditorTest`, `com.loosecannon.servicetag.ui.maintenance.ScheduleOperationsTest`.
- Anchored: `grep -rnF "Pause with the asset's season" app/src/main` → no output; `grep -rnF 'Remind me year round' app/src/main` → no output; `grep -rnF 'OUT_OF_SEASON_FIELD' app/src/main` → no output; `git diff --stat <base> -- core/src/main app/src/main/kotlin/com/loosecannon/servicetag/di app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/DueReadModel.kt app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/DueItemRow.kt` → empty.

## Strings

**S65–S84**, **S137**, **S140**, **S141**, verbatim, plus the shipped Cancel. No other text; the helpers are not paraphrased and no option label is shortened.

## Must NOT

- draw the question for a group or for a YEAR_ROUND asset with no break;
- prefill or suggest a margin, or turn an out-of-range value into a different one;
- change a stored policy the owner did not pick (the S77 case included);
- archive a schedule or a subject without the dialog's confirmation;
- touch the read models, the row composable, `AppGraph` or `:core`.

## Review focus

- The option table is implemented exactly, including the two "not drawn" rows; a group or a boundary-less asset never sees S65.
- No path turns an owner's typed value into a different stored value except the documented meter-only offset of 0.
- The dialog repeats the **same** command with the flag and nothing else changed.

## Size

Medium: one view model, one screen, one detail action, with most of the matrix in JVM form tests. No split expected.
