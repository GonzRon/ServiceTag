# P2 — Reminder Health names the missing delivery and fixes it (#80, the UX)

**Read first:** plan.md §3–§6; `rulings.md` R1–R3 and the architecture ruling; #80's body ("Reminder Health UX", acceptance 7, 8, 11).
**Lane:** A, wave 2, branched from `<base>` = the master commit that merged P1 (it calls P1's use case through `graph.repairScheduleProviders`). It may run beside P3 only once P3's connected gate has run; connected classes never overlap on `emulator-5554` (plan.md §2).
**Blocked on:** P1 merged. (P141-1a, P141-1b and P141-2 were ratified on 2026-09-25.)

## Goal

Today `SCHEDULE_NO_PROVIDER` (`ReminderHealthCheck.kt:286-308`) covers every ACTIVE, reminders-on schedule with no *enabled* provider, says "…no way to deliver them." and offers "Open the schedule" one row at a time. After P2 the finding is split by shape. The providerless shape — exactly the repair predicate — draws P141-1 and the button P141-2, which runs the canonical repair in-process, sweeps delivery, and refreshes, so one tap clears the whole finding. The residual shape (a non-empty provider set with nothing enabled, which no phone holds and the editor cannot make) keeps the 1.2 sentence and "Open the schedule" under its own code, so a bulk repair never sweeps an explicitly disabled provider (R3). `NOTIFICATIONS_BLOCKED` and the badge rule are untouched.

## Files

**Modify**
- `app/src/main/kotlin/com/loosecannon/servicetag/reminders/ReminderHealthCheck.kt`: `ReminderRepair` (`:38-49`) gains `RESTORE_REMINDER_DELIVERY`; `scheduleFindings()` (`:286-308`) splits; each sentence one literal on one line (the residual one re-joined from `:303-304`).
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/ReminderHealthViewModel.kt`: `repairLabel` (`:29-38`); `HealthAction` (`:52-59`) gains `RestoreReminderDelivery`; the primary constructor gains `restoreDelivery: suspend () -> Unit` **before** `resumeDelivery` (plan.md §3) and the `graph` constructor (`:157-161`) passes `{ graph.repairScheduleProviders.apply() }`; `repair()` (`:182-199`) and `actionOf` (`:210-229`).
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/maintenance/ReminderHealthScreen.kt:125`: the branch that already routes `Automatic, TurnRemindersOn -> model.repair(row)` gains the new action.
- Tests: `app/src/test/…/reminders/ReminderHealthCheckTest.kt` (`anActiveScheduleWithNoEnabledProviderRowIsTheFinding` `:400-410` adapted; `:397-444`, `:566-622`), `app/src/test/…/ui/maintenance/ReminderHealthViewModelTest.kt` (`theSevenRepairLabelsAreTheRatifiedWords` `:189`; `theInAppRepairsCarryTheScheduleTheyOpen` `:283-332` adapted; **`aRowThatOnlyOpensAScreenIsNotRepairedHere` `:264-277` stays untouched** — it is the `NOTIFICATIONS_BLOCKED` guard), `app/src/androidTest/…/ui/maintenance/ReminderHealthScreenTest.kt`.

**Untouched:** `core/**` (P1's contract is consumed, never changed), `AppGraph.kt` (P1 already exposes the use case), `LocalReminderProvider.kt`, `HealthSummary.kt`, `ReminderReceivers.kt`, the API, the MCP, the loader, `docs/api/v1.md`.

## Interfaces

```kotlin
object ReminderRepair { const val RESTORE_REMINDER_DELIVERY = "RESTORE_REMINDER_DELIVERY" /* no target: a batch */ }
sealed interface HealthAction { data object RestoreReminderDelivery : HealthAction }
class ReminderHealthViewModel(health: ReminderHealth, prefs: AppPrefs,
    restoreDelivery: suspend () -> Unit, resumeDelivery: suspend () -> Unit)
// neither seam has a default value: a production wiring that omitted one must fail to compile
```

## Contracts

- **Finding A** — code `SCHEDULE_NO_PROVIDER` (kept): over today's universe (`listedForDue()`, `inService`), `status == ACTIVE && remindersEnabled && providers.isEmpty()`; message P141-1a, or P141-1b when the count is exactly 1; severity WARN; repair the whole value `RepairAction.OpenInApp("RESTORE_REMINDER_DELIVERY")` — **no target**, never `Automatic`.
- **Finding B** — code `SCHEDULE_PROVIDER_DISABLED` (new): the same universe, `status == ACTIVE && remindersEnabled && providers.isNotEmpty() && providers.none { it.enabled }`; message the 1.2 sentence, count-shaped as today; WARN; repair `OpenInApp(targeted(OPEN_SCHEDULE, rows))` exactly as today. A and B partition the old set; a row is in at most one.
- **The label:** `repairLabel(RESTORE_REMINDER_DELIVERY) == P141-2`; the seven existing labels unchanged.
- **The tap** (`repair(row)` for `RestoreReminderDelivery`): `restoreDelivery()`, then `resumeDelivery()`, then `emit(health.refresh())` — in that order: the sweep after the write so the new subjects are delivered at once (the `TurnRemindersOn` precedent, `:186-195`), the refresh last so the row clears within the same tap. **Failure:** an exception from `restoreDelivery()` is caught and logged with one `Log.w` (no token, no body), `resumeDelivery()` is skipped, the refresh still runs and the finding stays; nothing is drawn for it (no ratified sentence). The result of the repair is not drawn.
- **Never unattended:** `ReminderHealthCheck.repair()` (`:222-240`) still acts only on `Automatic` and is not wired to the use case; `runAndRepair()` therefore cannot reach it.
- **The screen:** no new composable; the row's existing `TextButton` (`:169-176`) carries P141-2; the `when` at `:125` routes `RestoreReminderDelivery` to `model.repair(row)`.
- **The count** in P141-1 is finding A's population alone.

## Test matrix

| hazard | test (class · case) | RED mutation |
|---|---|---|
| the old lump | `ReminderHealthCheckTest` · `aProviderlessActiveScheduleDrawsTheDeliverySentenceAndTheBatchRepair` (adapts `:400-410`): two providerless rows → P141-1a with `2`, code A, `repair == RepairAction.OpenInApp("RESTORE_REMINDER_DELIVERY")` | keep `none(enabled)`; make it `Automatic` |
| the plural | `ReminderHealthCheckTest` · `oneProviderlessScheduleUsesTheSingularSentence` | P141-1a for 1 |
| a disabled set swept | `ReminderHealthCheckTest` · `aDisabledProviderSetIsTheResidualFindingWithOpenTheSchedule`: `[LOCAL disabled]` → code B, the 1.2 sentence, `OPEN_SCHEDULE:<id>` | merge the shapes |
| both at once | `ReminderHealthCheckTest` · `theTwoShapesNeverShareARowAndCountSeparately` | — |
| the controls | the existing negatives (enabled LOCAL, PAUSED, ARCHIVED, `remindersEnabled = false`, out of service) absent from **both** codes | — |
| unattended repair | `ReminderHealthCheckTest` · `runAndRepairNeverWritesAProvider`: a recording repository; `runAndRepair()` over finding A issues no upsert | wire the use case into `ReminderHealthCheck.repair` |
| the label | `ReminderHealthViewModelTest` · `theEightRepairLabelsAreTheRatifiedWords` (adapted, verbatim P141-2) | a typo |
| the tap | `ReminderHealthViewModelTest` · `fixReminderDeliveryRestoresSweepsThenClears`: a shared call log records `restoreDelivery` then `resumeDelivery`; the row absent after the refresh | drop the sweep; swap the order |
| the failure | `ReminderHealthViewModelTest` · `aFailedRestoreLeavesTheFindingAndDoesNotCrash`: `restoreDelivery` throws; no sweep; the row still present after the refresh; the model alive | let it propagate |
| the residual row | `ReminderHealthViewModelTest` · `theInAppRepairsCarryTheScheduleTheyOpen` (adapted, `:283-332`): `sched-1` becomes ACTIVE+on+`[LOCAL disabled]` → `SCHEDULE_PROVIDER_DISABLED`, `OpenSchedule("sched-1")`, `Open the schedule`, the 1.2 sentence; a providerless row beside it → `RestoreReminderDelivery` and P141-2 | — |
| the screen | `ReminderHealthScreenTest` · `tappingFixReminderDeliveryClearsTheFinding`: a Room-backed ACTIVE providerless schedule; the row shows P141-1b and P141-2; one tap; the row gone; the repository row now holds `LOCAL enabled` | revert the view-model branch |

## Gate

- `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --console=plain`: zero failures, zero skips.
- Connected, on `emulator-5554` only, one class, scheduled by the controller: `com.loosecannon.servicetag.ui.maintenance.ReminderHealthScreenTest`.
- Anchored `git grep -nE` over `app/src/main/kotlin/com/loosecannon/servicetag/ui app/src/main/kotlin/com/loosecannon/servicetag/reminders` (the `^[^/*]*` prefix keeps a line comment or a KDoc line from matching): `'^\s*ReminderRepair\.RESTORE_REMINDER_DELIVERY -> "Fix reminder delivery"$'` → 1; `'^[^/*]*"\$\{[a-zA-Z.]+\} schedules have reminders turned on, but reminder delivery isn.t configured\."'` → 1; `'^[^/*]*"1 schedule has reminders turned on, but reminder delivery isn.t configured\."'` → 1; `'^[^/*]*"\$\{[a-zA-Z.]+\} schedules have reminders switched on but no way to deliver them\."'` → 1 (kept, one literal); `'^\s*code = "SCHEDULE_PROVIDER_DISABLED",?$'` → 1; `'RepairAction\.Automatic\('` → the same lines as at `<base>`; `'"[^"]*([Pp]rovider|LOCAL)[^"]*"'` → the same lines as at `<base>` (no new user-visible string names a reminder provider, local or otherwise).
- `git diff <base> -- core tools docs app/src/main/kotlin/com/loosecannon/servicetag/di app/src/main/kotlin/com/loosecannon/servicetag/api app/src/main/AndroidManifest.xml` → empty.
- Hygiene; gitlink `7e0377a`; `git status` clean.

## Strings

P141-1a, P141-1b, P141-2 as ratified, verbatim; the 1.2 pair (`<n> schedules have reminders switched on but no way to deliver them.` / `Open the schedule`) verbatim; nothing else. A state that seems to need words means NEEDS_CONTEXT.

## Must NOT

- make the provider repair `Automatic`, or call the use case from `ReminderHealthCheck`;
- add a detail line, a toast, a dialog or a count of what was repaired;
- change `NOTIFICATIONS_BLOCKED`, its guard test, the badge rule, `LocalReminderProvider` or `HealthSummary`;
- touch `AppGraph`, `core`, the API, the MCP or the loader;
- drive the screen with uiautomator or `adb shell input`.

## Review focus

The order restore → sweep → refresh and the caught failure; the two predicates partitioning the old set exactly, with the existing negative controls proven for both; the singular form; the whole `RepairAction` value asserted (the real guard against `Automatic`).

## Size

Small to medium: one check, one view model, one screen line, three test classes.
