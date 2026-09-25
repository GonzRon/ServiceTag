# P2 — Reminder Health names the missing delivery and fixes it (#80, the UX)

**Read first:** plan.md §3–§6; `rulings.md` R1–R3 and the architecture ruling; #80's body ("Reminder Health UX", acceptance 7, 8, 11).
**Lane:** A, wave 2, branched from master **after P1 merged** (it calls P1's use case through `graph.repairScheduleProviders`). It may run beside P3; the two share no file.
**Blocked on:** P1 merged; P141-1a, P141-1b and P141-2 ratified (plan.md §4).

## Goal

Today `SCHEDULE_NO_PROVIDER` (`ReminderHealthCheck.kt:286-308`) covers every ACTIVE, reminders-on schedule with no *enabled* provider, says "…no way to deliver them." and offers "Open the schedule" one row at a time. After P2 the finding is split by shape. The providerless shape — exactly the repair predicate — draws P141-1 and the button P141-2, which applies `RepairScheduleProviders` in-process, sweeps delivery, and refreshes, so one tap clears the whole finding. The residual shape (a non-empty provider set with nothing enabled, which no phone holds and the editor cannot make) keeps the 1.2 sentence and "Open the schedule" under its own code, so a bulk repair never sweeps an explicitly disabled provider (R3). `NOTIFICATIONS_BLOCKED` and the badge rule are untouched.

## Files

**Modify**
- `app/src/main/kotlin/com/loosecannon/servicetag/reminders/ReminderHealthCheck.kt`: `ReminderRepair` (`:38-49`) gains `RESTORE_REMINDER_DELIVERY`; `scheduleFindings()` (`:286-308`) splits.
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/ReminderHealthViewModel.kt`: `repairLabel` (`:29-38`); `HealthAction` (`:52-59`) gains `RestoreReminderDelivery`; the primary constructor gains `repairProviders: RepairScheduleProviders` and the `graph` constructor (`:157-161`) passes `graph.repairScheduleProviders`; `repair()` (`:182-199`) and `actionOf` (`:210-229`).
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/ReminderHealthScreen.kt:125`: the branch that already routes `Automatic, TurnRemindersOn -> model.repair(row)` gains the new action.
- Tests: `app/src/test/…/reminders/ReminderHealthCheckTest.kt` (`:397-444`, `:566-622`), `app/src/test/…/ui/maintenance/ReminderHealthViewModelTest.kt` (`theSevenRepairLabelsAreTheRatifiedWords` `:189`, `aRowThatOnlyOpensAScreenIsNotRepairedHere` `:265`), `app/src/androidTest/…/ui/maintenance/ReminderHealthScreenTest.kt`.

**Untouched:** `core/**` (P1's contract is consumed, never changed), `AppGraph.kt` (P1 already exposes the use case), `LocalReminderProvider.kt`, `HealthSummary.kt`, `ReminderReceivers.kt`, the API, the MCP, the loader, `docs/api/v1.md`.

## Interfaces

```kotlin
object ReminderRepair { const val RESTORE_REMINDER_DELIVERY = "RESTORE_REMINDER_DELIVERY" /* no target: a batch */ }
sealed interface HealthAction { data object RestoreReminderDelivery : HealthAction }
class ReminderHealthViewModel(health: ReminderHealth, prefs: AppPrefs, resumeDelivery: suspend () -> Unit, repairProviders: RepairScheduleProviders)
```

## Contracts

- **Finding A** — code `SCHEDULE_NO_PROVIDER` (kept): over today's universe (`listedForDue()`, `inService`), `status == ACTIVE && remindersEnabled && providers.isEmpty()`; message P141-1a, or P141-1b when the count is exactly 1; severity WARN; repair `RepairAction.OpenInApp(ReminderRepair.RESTORE_REMINDER_DELIVERY)` with **no target**.
- **Finding B** — code `SCHEDULE_PROVIDER_DISABLED` (new): the same universe, `status == ACTIVE && remindersEnabled && providers.isNotEmpty() && providers.none { it.enabled }`; message the 1.2 sentence, count-shaped as today (`:303-304`); WARN; repair `OpenInApp(targeted(OPEN_SCHEDULE, rows))` exactly as today. A and B partition the old set; a row is in at most one.
- **The label:** `repairLabel(RESTORE_REMINDER_DELIVERY) == P141-2`; the seven existing labels unchanged.
- **The tap** (`repair(row)` for `RestoreReminderDelivery`): `repairProviders.apply()`, then `resumeDelivery()`, then `emit(health.refresh())` — in that order: the sweep after the write so the new subjects are delivered at once (the `TurnRemindersOn` precedent, `:186-195`), the refresh last so the row clears within the same tap. The result of `apply()` is not drawn (no ratified sentence for it).
- **Never unattended:** `ReminderHealthCheck.repair()` (`:222-240`) still acts only on `Automatic`; `runAndRepair()` therefore never touches a provider. A test proves `check.repair(findingA)` writes nothing.
- **The screen:** no new composable; the row's existing `TextButton` (`:169-176`) carries P141-2; the `when` at `:125` routes `RestoreReminderDelivery` to `model.repair(row)`.
- **The count** in P141-1 is finding A's population alone.

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the old lump | `ReminderHealthCheckTest` · `aProviderlessActiveScheduleDrawsTheDeliverySentenceAndTheBatchRepair`: two providerless rows → P141-1a with `2`, code A, repair code `RESTORE_REMINDER_DELIVERY`, no target | keep `none(enabled)` |
| the plural | `ReminderHealthCheckTest` · `oneProviderlessScheduleUsesTheSingularSentence` | P141-1a for 1 |
| a disabled set swept | `ReminderHealthCheckTest` · `aDisabledProviderSetIsTheResidualFindingWithOpenTheSchedule`: `[LOCAL disabled]` → code B, the 1.2 sentence, `OPEN_SCHEDULE:<id>` | merge the shapes |
| both at once | `ReminderHealthCheckTest` · `theTwoShapesNeverShareARowAndCountSeparately` | — |
| the controls | the existing negatives (enabled LOCAL, PAUSED, ARCHIVED, `remindersEnabled = false`, out of service) absent from **both** codes | — |
| unattended repair | `ReminderHealthCheckTest` · `runAndRepairNeverWritesAProvider`: a recording repository; `check.repair(findingA)` and `runAndRepair()` issue no upsert | make it `Automatic` |
| the label | `ReminderHealthViewModelTest` · `theEightRepairLabelsAreTheRatifiedWords` (adapted, verbatim P141-2) | a typo |
| the tap | `ReminderHealthViewModelTest` · `fixReminderDeliveryAppliesSweepsThenClears`: a fake use case records `apply()`; `resumeDelivery` recorded after it; the row absent after the refresh | drop the sweep; swap the order |
| the residual row | `ReminderHealthViewModelTest` · `aResidualRowOnlyOpensItsSchedule` (adapts `aRowThatOnlyOpensAScreenIsNotRepairedHere`) | — |
| the screen | `ReminderHealthScreenTest` · `tappingFixReminderDeliveryClearsTheFinding`: a Room-backed ACTIVE providerless schedule; the row shows P141-1b and P141-2; one tap; the row gone; the repository row now holds `LOCAL enabled` | revert the view-model branch |

## Gate

- `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --console=plain`: zero failures, zero skips.
- Connected, on `emulator-5554` only, one class: `com.loosecannon.servicetag.ui.maintenance.ReminderHealthScreenTest`.
- Anchored `git grep -nE` over `app/src/main`: `'"Fix reminder delivery"'` → 1; `'schedules have reminders turned on, but reminder delivery isn.t configured\.'` → 1; `'"1 schedule has reminders turned on, but reminder delivery isn.t configured\."'` → 1; `'schedules have reminders switched on but no way to deliver them\.'` → 1 (kept); `'"SCHEDULE_PROVIDER_DISABLED"'` → 1; `'RepairAction\.Automatic\('` → the same lines as at the base; `'"[^"]*[Pp]rovider[^"]*"'` under `app/src/main/kotlin/com/loosecannon/servicetag/ui` → the same lines as at the base (no user-visible "provider").
- `git diff <base> -- core tools docs app/src/main/kotlin/com/loosecannon/servicetag/di app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/AndroidManifest.xml` → empty.
- Hygiene; gitlink `7e0377a`; `git status` clean.

## Strings

P141-1a, P141-1b, P141-2 as ratified, verbatim; the 1.2 pair (`<n> schedules have reminders switched on but no way to deliver them.` / `Open the schedule`) verbatim; nothing else. A state that seems to need words means NEEDS_CONTEXT.

## Must NOT

- make the provider repair `Automatic`, or call the use case from `ReminderHealthCheck`;
- add a detail line, a toast, a dialog or a count of what was repaired;
- change `NOTIFICATIONS_BLOCKED`, the badge rule, `LocalReminderProvider` or `HealthSummary`;
- touch `AppGraph`, `core`, the API, the MCP or the loader;
- drive the screen with uiautomator or `adb shell input`.

## Review focus

The order apply → sweep → refresh; the two predicates partitioning the old set exactly, with the existing negative controls proven for both; the singular form; `runAndRepair` provably unable to reach the repair.

## Size

Small to medium: one check, one view model, one screen line, three test classes.
