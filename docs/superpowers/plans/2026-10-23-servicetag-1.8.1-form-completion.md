# ServiceTag 1.8.1 — #117 a FORM schedule completes through its form, #118 one commit button on the entry form: plan (rev 1.1, 2026-10-23)

**Release:** 1.8.1 / `versionCode` 22, a **PATCH** by `docs/versioning.md` ("Bug fix or behaviour correction: something that was supposed to work already now does"; #118 is a "Small UI correction"). No Room schema or backup-format change: schema **21** / format **20**, as 1.8.0. **Cut from master** (ruling Q-C): `issue-113-114-ha-publishing` (#113/#114) is unmerged and stays out; if it merges before the tag, the controller stops and asks.
**Base:** master `62e39670` (1.8.0 / code 21 + the hygiene workflow, #111 #112). Every `file:line` below was read on that commit.
**Inputs:** the bodies of #117 and #118; `docs/superpowers/planning-policy.md`; `docs/release-proofs.md`; the plan review `plan-review-rev1.md` in the ledger and the controller's rulings on it.
**Branch:** `issue-117-form-completion`, merged to master with a merge commit. The pull request description says it touches `app/src/androidTest` and four screens (the entry form, the schedule detail, the scan sheet, the Log maintenance picker). Commits follow the repository `CLAUDE.md` (owner identity, subject line only, no tool names); `bash tools/check-commit-hygiene.sh origin/master HEAD` on the branch before every push.
**Ledger:** `.superpowers/sdd/2026-10-23-issue-117-form-completion/progress.md` (controller's, not committed).

**Rev 1.1 changes** (each finding of `plan-review-rev1.md`, folded as stated, with the controller's rulings):
- **F1** the notification's arrival asks once per arrival; the root clears the entry's `complete` flag on first use (§3.3; T1b2; SD1, RT4, N2 extended, N2b).
- **F2** the scan sheet's resume guard moves into its view model (§3.4; T1b2; SH1, N3b); the "no code change" claim is withdrawn.
- **F3 / Q-A** a form completion carries the **schedule's** title; a profile-less form opened from a schedule starts with it filled in (D3, §3.1, §3.5; V8a, V8b).
- **F4 / Q-B** the view model runs the reminder reconcile after a schedule-opened write (§3.5; V12).
- **F5** T1b split into T1b1 and T1b2; O1 split out of T2 as T2b (§5).
- **F6** rows U (emulator upgrade proof) and D (release dry run); R7 "counts identical"; R3's three tools; the production install is an install, not a proof row (§8).
- **F7** the hygiene ranges (§8, header). **F8** the anchored `journal_save` grep (T2). **F9** §3.6's rows and I3. **F10** V1, V7c, V13. **F11** no scratch files in scanned trees; `tools/emulator/prepare-emulator.sh` before every connected run. **F12** the both-modes save helper (T2). **F13** `modelKey` (§3.5). **F14** RT3 anchored. **F15** the README's previous release (T3). **F16** ledger and branch (header).
- **Q-C** cut from master (header). Six citations corrected (`CompleteSchedule.kt:108-110`; `ScheduleDetailScreen.kt` button lines; `QuickActions.kt:223-224`; I7's meter clause; `phase-2a-evidence.md:291-294`; `resumeQueue :647-671`).

## 1. The defect, read on `62e39670`

`CompletionFlow.complete` answers a FORM schedule with `NeedsForm(scheduleId, assetId, profileId)` and writes nothing (`CompletionFlow.kt:75-79`, `:196-197`). Every consumer forwards only `(assetId, profileId)`: `ScheduleDetailScreen.kt:105`, the Log maintenance picker (`CompletionFlow.kt:516-517` → `ServiceTagRoot.kt:186-188`), the scan sheet (`MaintenanceSheet.kt:132` → `ServiceTagRoot.kt:685-687`), and the detail screen at `ServiceTagRoot.kt:544-546`. The notification's **Done** on a FORM (or meter) schedule opens `Route.ScheduleDetail(id, complete = true)` (`reminders/QuickActions.kt:128-130`, `:223-224`), whose arrival runs the flow (`ScheduleDetailScreen.kt:109-111`) and leaves through the detail's `onLogForm`. Four entry points, three root lambdas.

`Route.EventEntry` (`Route.kt:57-64`) has no schedule field, and `EventEntryViewModel.save()` (`:506-557`) writes through `LogEvent` (`LogEvent.kt:20`: "not a completion"). The only writer that links an event to a schedule is `CompleteSchedule.run` (`:63-126`; `scheduleId` `:91`, `occurrenceOn` `:100`), and a completion is exactly an event naming the schedule (`ScheduleRecompute.kt:227-228`). `MaintenanceSheetViewModelTest` (`:815`) simulates the form's save with `graph.completeSchedule.run(...)` — the call the journal screen never made.

**`CompleteSchedule` cannot be called as it stands.** `CompletionCommand` (`ScheduleCommands.kt:63-71`) has no title, and `detailsPending = FORM && values.isEmpty() && consumables.isEmpty()` (`CompleteSchedule.kt:74-76`): a form saved with nothing typed would be stored details-pending, and since a pending completion is built against no profile (`:108-110`), a form with an empty required field would be written instead of refused.

**Two return-path defects the fix exposes (review F1, F2).** (a) The arrival's `LaunchedEffect(model) { if (startCompletion) model.complete() }` runs again whenever the detail composes afresh — back from the form after a save or ✕, after a rotation, after a process restore — because nothing clears `complete` (`Route.kt:163`; `openDeepLink`, `ServiceTagRoot.kt:736-742`). After the fix that loop would write a second **linked** completion for the next occurrence. (b) The scan sheet re-derives on resume only behind `var resumed by remember` (`MaintenanceSheet.kt:118-122`), which resets when the sheet composes afresh under a popped form (its own comment, `:123-125`), so the first resume back from a form skips `refresh()` and `resumeQueue()` (`MaintenanceSheetViewModel.kt:469-474`, `:647-671`) never runs.

## 2. Decisions

- **D1.** A form opened for a schedule saves through `CompleteSchedule`, in its one `uow.write` (event, conditional postponement clear, recompute). No new use case; `LogEvent` and `UpdateEvent` are not taught about schedules.
- **D2.** `CompletionCommand` gains `title: String? = null` and `viaForm: Boolean = false`. The existing callers — the quick flow (`CompletionFlow.kt:363-376`), the API's `CompletionRequest.toCommand()` (`MaintenanceDtos.kt:419-427`), the notification's one-tap seam (`AppGraph.kt:571-576`) — use named arguments and set neither; `CompletionRequest` (`:408-417`) gains nothing and `ApiJson` refuses unknown keys (`ApiJson.kt:104`).
- **D3 (ruling Q-A).** A form completion carries the **schedule's** title, as the one-tap path and the API do, so one schedule's completions read alike. A profile-backed form draws no title field (`EventEntryScreen.kt:189-200`) and sends `title = null`. A profile-less form opened from a schedule opens its **Entry** field with the schedule's title filled in and sends the field's value. The kind and profile stay `CompleteSchedule`'s (the schedule's profile and its kind, else MAINTENANCE).
- **D4.** The id cannot be dropped: the three composables' `onLogForm` takes the whole `CompletionOutcome.NeedsForm`, and `formEntry(form)` in `Route.kt` is the only way a `NeedsForm` becomes a route.
- **D5.** A refused schedule-opened save writes **nothing** and says so with the lines the form already has. It never falls back to an unlinked entry.
- **D6.** #118 removes the app-bar **Save** (`EventEntryScreen.kt:161-165`) and `journal_save` from all ten string files (its only user is `:163`). The bottom button stays: **Record entry** new, **Save entry** editing.
- **D7.** The IME: nothing under `app/src/main` applies IME insets, the activity is edge-to-edge (`MainActivity.kt:40`, target SDK 36), and G1 §1.3 says the app-bar Save existed so a commit stays reachable with the keyboard open. Reachability is observed (T2b), not assumed.
- **D8 (ruling Q-B).** After a schedule-opened write the entry form runs the graph's `reminderReconcile` (`AppGraph.kt:1184`) once, before any offer — `CompletionFlow`'s `afterWrite` rule — so the standing reminder (`setAutoCancel(false)`, `reminders/Notifications.kt:90`) clears at once from every entry point.

**Rejected alternatives.** *`LogEvent` takes a schedule*: its link fields default so that no route that must not complete has to mention them (`EventCommands.kt:52-61`); it also serves the API's event routes and share intake. *A new `CompleteScheduleWithForm`*: a second copy of the occurrence stamp, the clear and the recompute. *Link afterwards by an edit*: an edit keeps a link and never adds one (`EventCommands.kt:287-289`), and it would be two transactions. *A third callback parameter*: the defect is a dropped field; passing the whole outcome makes dropping impossible. *Keep the app-bar Save, drop the bottom button*: rejected in #118. *Clear the arrival flag before the question is asked*: the key is the entry's content key, so replacing it disposes the entry and cancels the open question (§3.3).

## 3. Contracts

### 3.1 `CompletionCommand` and `CompleteSchedule` (core)

```kotlin
data class CompletionCommand(
    val occurredOn: String,
    val occurredTime: String? = null,
    val tzId: String,
    val notes: String = "",
    val values: Map<DefinitionId, String> = emptyMap(),
    val consumables: List<ConsumableInput> = emptyList(),
    val assetId: AssetId? = null,
    /** #117: a profile-less form's Entry field; null is the schedule's title (every other caller, every profile-backed form). */
    val title: String? = null,
    /** #117: the profile form collected the details — never details_pending; the profile's required fields apply. */
    val viaForm: Boolean = false,
)
```

In `CompleteSchedule.run`: `detailsPending = FORM && minimal && !cmd.viaForm`; `title = cmd.title ?: schedule.title`; the profile handed to `buildEvent` stays `if (detailsPending) null else profile` (`:108-110`), which is what enforces required fields once `viaForm` is set. The class KDoc's third bullet gains: a completion collected by the form is never minimal. `CompleteGroupMembers` is untouched (group targets are QUICK-only, `ScheduleCommands.kt:113-119`, `:295-296`).

### 3.2 The route and the four hand-overs (app)

```kotlin
@Serializable data class EventEntry(
    val assetId: String, val profileId: String?, val eventId: String?,
    val kind: String? = null, val pending: PendingConditionArgs? = null,
    val thenServiceCase: Boolean = false,
    /** #117: the FORM schedule this new entry completes. Null: an ordinary entry. Never with an eventId or pending. */
    val scheduleId: String? = null,
) : Route

/** #117: the one way a NeedsForm becomes a route. */
fun formEntry(form: CompletionOutcome.NeedsForm): Route.EventEntry =
    Route.EventEntry(form.assetId.value, form.profileId?.value, null, scheduleId = form.scheduleId.value)
```

- `onLogForm: (CompletionOutcome.NeedsForm) -> Unit` on `LogMaintenancePicker` (`CompletionFlow.kt:469`), `ScheduleDetailScreen` (`:85`) and `MaintenanceSheet` (`:97`); each forwards what it received (`CompletionFlow.kt:517`, `ScheduleDetailScreen.kt:105`, `MaintenanceSheet.kt:132`); their KDoc corrected in place.
- `ServiceTagRoot.kt`: the picker's lambda (`:186`) and the sheet's (`:685`) become `backStack.add(formEntry(it))` (the picker keeps `logging = false` first); the detail's (`:544`) becomes `{ form -> backStack.clearArrival(key); backStack.add(formEntry(form)) }` (§3.3); `entry<Route.EventEntry>` (`:349-370`) passes `scheduleId = key.scheduleId`.

### 3.3 The arrival asks once (F1)

**Contract.** The notification's arrival asks once per arrival. Coming back to the detail from the form (after a save or ✕), a rotation and a process restore never run the flow again.

**Mechanism: the root clears the stack entry's `complete` flag on first use.**

```kotlin
/** #117 (F1): an arrival is used once; the entry keeps its place and stops asking. Beside openDeepLink. */
internal fun MutableList<NavKey>.clearArrival(key: Route.ScheduleDetail) {
    if (!key.complete) return
    val at = lastIndexOf(key)
    if (at >= 0) set(at, key.copy(complete = false))
}
```

- **When.** The key is the entry's content key (`entry<…>` keys by the key's own text), so `set` disposes that entry and clears its `ViewModelStore`. It therefore runs only when no question is open: (a) a FORM arrival — in the detail's `onLogForm` handler, immediately before the form is pushed, in the same handler, so the hand-over and the clear cannot race; (b) any other ending — `ScheduleDetailViewModel` emits a one-shot `arrivalEnded` as the run's last act (after its offers) for Completed, Cancelled and Refused, and the root's `onArrivalEnded = { backStack.clearArrival(key) }` clears; the entry then composes afresh and re-derives. The held refusal (#77, P77-35) does not emit it: it writes nothing and its sentence must stay on screen; asking again only says it again.
- **Between start and end.** `ScheduleDetailViewModel.arrive()` runs the arrival at most once per model (a model field; the model survives a rotation, and `operate`'s busy guard, `:537-539`, already refuses a second run while one is open). The screen calls `arrive()` where it called `complete()` (`ScheduleDetailScreen.kt:109-111`) and collects `arrivalEnded` into a new `onArrivalEnded: () -> Unit = {}` parameter. Every other way in keeps `complete()` unchanged.
- **Process restore.** A cleared stack restores `complete = false`. A restore while the question is still open restores `complete = true` and asks again — correct, since nothing was written.

### 3.4 The sheet resumes its queue (F2)

**Contract.** Back from a form, the sheet re-derives and resumes the queue on its first resume.

**Mechanism: the guard moves into the view model.** `MaintenanceSheetViewModel.onResumed()`: the first call of a model's life does nothing (its `init` loaded); every later call is `refresh()`. The screen's `var resumed by remember` (`MaintenanceSheet.kt:118-122`) goes, and its `LifecycleResumeEffect` calls `model.onResumed()`. The model lives as long as the sheet's stack entry, so a form pushed and popped over it no longer resets the guard; a rotation keeps it; a process restore makes a new model whose `init` loads. `resumeQueue` and its reconcile are unchanged.

### 3.5 `EventEntryViewModel` and `EventEntryScreen`

- **Constructor.** Four trailing defaulted parameters: `scheduleId: ScheduleId? = null`, `completeSchedule: CompleteSchedule? = null`, `schedules: ScheduleRepository? = null`, `reconcile: ReminderReconcile? = null`. The `init` check (`:220-224`) adds: `scheduleId == null || (completeSchedule != null && schedules != null && reconcile != null && eventId == null && pending == null)`, its message marked `// l10n-ok: exception message`. The graph constructor (`:226-243`) takes `scheduleId: String? = null` and passes `graph.completeSchedule`, `graph.schedules`, `graph.reminderReconcile`.
- **Load.** With a `scheduleId` and no profile, the title starts as the schedule's title (read once through `schedules`); otherwise as today (`:296-299`).
- **Save.** The same `EventCommand` as today, then:

```kotlin
val event = when {
    eventId != null -> updateEvent.run(eventId, cmd)                                    // unchanged
    scheduleId != null -> checkNotNull(completeSchedule).run(scheduleId, cmd.toCompletion())
        .also { reconcileAfterWrite() }                                                 // D8
    else -> logEvent.run(cmd)                                                           // unchanged
}
// toCompletion(): occurredOn, occurredTime, tzId, notes, values, consumables, assetId,
//   title = if (commandProfileId != null) null else cmd.title, viaForm = true
// reconcileAfterWrite(): reconcile.run(); a failure is logged and the save stands (rethrow cancellation)
```

- **Catches,** by name. Added: `NoSuchSchedule`, `ScheduleArchived`, `GroupCompletionNotSupported` → `refuse(e)` (the shipped could-not-save line, `:696-699`). `EventValidation`, `EventOwnership`, `AssetTransferredOut` are already caught. Offers (`:539-548`) and `saved` are untouched, so `onSaved`/`onDone` (`EventEntryScreen.kt:114-120`) behave as before.
- **Screen.** `EventEntryScreen(…, scheduleId: String? = null)`; `modelKey` (`:106`) appends `-schedule-<id>` **only when non-null**, as `pending` does, so a plain entry's key is unchanged; the factory passes the id.

### 3.6 Refusals and edge cases

| While the form is open | `CompleteSchedule` answers | The form shows | Written |
|---|---|---|---|
| a required reading empty, a bad number or material line, an unknown supply item | `EventValidation` | the row marked (`markProblems`) | nothing |
| the Entry field cleared (profile-less) | `EventValidation(TitleRequired)` | as today | nothing |
| the schedule archived / deleted | `ScheduleArchived` / `NoSuchSchedule` | the could-not-save line | nothing |
| the schedule re-targeted to a group | `GroupCompletionNotSupported` | the could-not-save line | nothing |
| the schedule moved to another asset (edit, or Replace asset) | `EventOwnership` (`CompleteSchedule.kt:68-72`) | the could-not-save line | nothing |
| the schedule's profile changed to one with a required field the open form does not draw | `EventValidation(Required)` on an undrawn row | the could-not-save line (`firstProblemText`'s fallback, `EventEntryViewModel.kt:742-758`) | nothing |
| the asset transferred out | `AssetTransferredOut` (held-write guard) | the transferred-out line | nothing |
| the schedule postponed | — | — | the completion; `occurrenceOn` the **computed** due; the postponement cleared |
| the schedule switched to QUICK | — | — | the completion, `source` SCHEDULE_QUICK_COMPLETE (the use case's rule) |
| the schedule paused | — | — | the completion, as the quick path allows (`CompleteSchedule` refuses only ARCHIVED) |
| the app killed, the stack restored | — | the form, still carrying `scheduleId` | on save, the completion |

A FORM schedule that also counts a meter keeps 1.8.0's behaviour: the reading is collected only if the profile carries that definition (follow-up issue, §9).

### 3.7 The entry form (#118)

- `TopAppBar` keeps the eyebrow and ✕; its `actions` block (`EventEntryScreen.kt:161-165`) goes. The bottom `Button` (`:241-248`) is the only commit control.
- The screen's KDoc (`:73-79`) and G1 §1.3 (`docs/design/g1/01-g1-visual-gate-report.md:52` Hierarchy, `:56` Keyboard) say: app bar ✕ and the eyebrow; one filled commit button at the foot, reachable with the keyboard open (and inset above the IME if T2b requires it). `docs/design/phase-2a-evidence.md:291-294` is history and stays.
- If T2b finds the button covered: `Modifier.padding(padding).consumeWindowInsets(padding).imePadding()` ahead of `verticalScroll` on the form's `Column` (`:172-177`), nothing else. If the IME inset reads 0 (so `imePadding` changes nothing), stop and report: `adjustResize` is app-wide.

## 4. Invariants

| # | Invariant |
|---|---|
| I1 | A form opened with a `scheduleId` never writes an event whose `scheduleId` is null — not on success, not on any refusal. |
| I2 | A schedule-opened save is one transaction: the event, the conditional `postponed_due_on` clear, the recompute. A refusal writes no row. The reconcile follows the commit. |
| I3 | The linked event: `scheduleId` the schedule; `occurrenceOn` the computed due at write time, never the postponed date (null for a meter-only schedule, `CompleteSchedule.kt:95-100`); `detailsPending` false; `source` MANUAL (SCHEDULE_QUICK_COMPLETE if the schedule was switched to QUICK while the form was open); `profileId` the schedule's; kind the profile's, else MAINTENANCE; title the schedule's, or a profile-less form's Entry field; readings, materials and supply links as sent. |
| I4 | A route without `scheduleId` saves exactly as 1.8.0 (`LogEvent`, `UpdateEvent`, `RecordConditionWithIncident`), with an unchanged model key and no reconcile from the form. |
| I5 | An edit never completes; a model with `scheduleId` and `eventId` or `pending` is refused at construction. |
| I6 | Every `NeedsForm` reaches the entry route through `formEntry`. |
| I7 | `/v1`, the MCP and the quick paths are unchanged: no caller but the form sets `title` or `viaForm`; a minimal API completion of a FORM schedule still writes `details_pending` true (core `:681-700`); the one-tap seam serves QUICK schedules without a meter rule (`QuickActions.kt:128`) and is unchanged. |
| I8 | One completion per save and per arrival: a late completion yields one next occurrence; two taps write one event; an arrival never asks twice (§3.3). |
| I9 | After a schedule-opened write the reminder reconcile runs once, before any offer; offers are asked once, as after any new entry. |
| I10 | A back stack stored by 1.8.0 restores: no `scheduleId` key decodes as an ordinary entry; a null one is not encoded. |
| I11 | Back from a form, the scan sheet re-derives and resumes its queue on the first resume. |
| I12 | The entry form has one commit control, the bottom button, new and editing; it is reachable with the IME open (T2b). |

**Untouched:** `MaintenanceDtos.kt` and every API/MCP file; `CompletionFlow.complete`, `attempt`, `offerAfter`, `command`; `CompleteGroupMembers`; `LogEvent.kt`; `UpdateEvent.kt`; `EventCommands.kt`; `reminders/QuickActions.kt`; `ScheduleDetailScreen.kt:236-252` and `:367-369` (the group round's buttons, QUICK-only) and `:257-261` (the asset target's **Log maintenance**, whose `NeedsForm` leaves through the reshaped collector); `MaintenanceSheetViewModel.resumeQueue` and its reconcile (on the sheet path the form's reconcile and the sheet's both run; reconcile is idempotent); Room schema and backup format; every string except the removal of `journal_save`.

## 5. Tasks

Order **T1a → T1b1 → T1b2 → T1c → T2 → T2b → T3**: one lane, one implementer and one task review per task, then one whole-branch review. No box over 60 minutes; a task not green at 60 stops and is inventoried (fix rounds 45 min). Every connected run on `emulator-5554` runs `tools/emulator/prepare-emulator.sh` first. Nothing is left under `tools/`, `app/src/androidTest`, `share-test-sender/` or `.github/` that names `dumpsys`, `uiautomator`, `input tap` or `input text` (`ReleaseProofPolicyTest.kt:28-47`, `:79-90`, which scans untracked files too).

| File | T1a | T1b1 | T1b2 | T1c | T2 | T2b | T3 |
|---|---|---|---|---|---|---|---|
| core `ScheduleCommands.kt`, `CompleteSchedule.kt`, test `ScheduleOperationsTest.kt` | ✓ | | | | | | |
| `ui/journal/EventEntryViewModel.kt`, test `EventEntryViewModelTest.kt` | | ✓ | | | | | |
| `ui/journal/EventEntryScreen.kt` | | ✓ param, key | | | ✓ app bar, KDoc | ✓ insets, if needed | |
| `ui/nav/Route.kt`, `ui/nav/ServiceTagRoot.kt`, test `ui/nav/RouteTest.kt` | | | ✓ | | | | |
| `ui/maintenance/CompletionFlow.kt` (picker callback only) | | | ✓ | | | | |
| `ui/maintenance/ScheduleDetailScreen.kt`, `ScheduleDetailViewModel.kt`, test `ScheduleDetailViewModelTest.kt` | | | ✓ | | | | |
| `ui/maintenance/MaintenanceSheet.kt`, `MaintenanceSheetViewModel.kt`, test `MaintenanceSheetViewModelTest.kt` | | | ✓ | | | | |
| androidTest `ScanSheetTest.kt`, `ui/maintenance/ScheduleOperationsTest.kt` (lambda shape) | | | ✓ | | | | |
| androidTest `FormCompletionNavigationTest.kt` (new) | | | | ✓ | | | |
| androidTest `EventEntryActionsTest.kt` (new), the six Save-click files; `res/values*/strings_records.xml` ×10; G1 doc | | | | | ✓ | | |
| `app/build.gradle.kts`, `VersionAgreementTest.kt`, `docs/versioning.md`, `docs/releases/1.8.1.md`, `README.md`, `docs/capabilities.md`, `docs/release-proofs.md` | | | | | | | ✓ |

### T1a — the completion command (#117, core) · ~30 min
§3.1. Tests C1–C3 beside `aMinimalCompletionOfAFormScheduleOwesItsDetails` (`:681-700`, unchanged). **Proof:** `./gradlew :core:test`, C1–C3 seen failing first.

### T1b1 — the form completes (#117, view model) · ~50 min
§3.5 (the view model; the screen's parameter, key and factory, with no caller passing an id yet). Tests V1–V13 through `FakeGraph` (it builds `completeSchedule`, `saveSchedule`, `schedules`, `FakeGraph.kt:644-652`; it wraps `events` in the held-write guard, `:271-279`; a counting `ReminderReconcile` fake). **Proof:** `./gradlew :app:testDebugUnitTest --tests '*EventEntryViewModelTest'`, then the whole `:app:testDebugUnitTest`.

### T1b2 — the hand-over and the two guards (#117, navigation) · ~55 min
§3.2, §3.3, §3.4. Tests RT1–RT4, SD1, SH1. The five androidTest lambdas (`ScanSheetTest.kt:186, :334, :580, :722`; `ScheduleOperationsTest.kt:104`) become one-argument lambdas; the two recording ones record `form:<asset>:<profile>:<schedule>`. **Proof:** `./gradlew :app:testDebugUnitTest --tests '*RouteTest' --tests '*ScheduleDetailViewModelTest' --tests '*MaintenanceSheetViewModelTest' --tests '*CompletionFlowTest'`, then the whole `:app:testDebugUnitTest`; `./gradlew :app:compileDebugAndroidTestKotlin`.

### T1c — the four entry points end to end (#117, Compose) · ~50 min
New `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/maintenance/FormCompletionNavigationTest.kt`: the real `ServiceTagRoot`, driven through `deepLinks` as `ScanSheetIncidentNavigationTest` (`:42-63`) does, `clearInstall()` per case, fixtures fictional (an asset "Mower"; FORM schedules "Blade sharpen" and "Deck clean" anchored two quarters back; a profile with one required reading and one suggested material). Cases N1, N2, N2b, N3, N3b, N4, each committing with the bottom **Record entry** (`performScrollTo()` first), so the class is independent of T2. **Proof:** `tools/emulator/prepare-emulator.sh`, then `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.ui.maintenance.FormCompletionNavigationTest`; each case seen failing with its guard or root lambda locally reverted (never committed), then green.

### T2 — one commit button (#118) · ~45 min
§3.7's removal and documents (not the insets). Remove `journal_save` from all ten files (default, `b+zh+Hans`, `de`, `es`, `fr`, `hi`, `it`, `ja`, `pt`, `ru`). New `EventEntryActionsTest` (A1, A2) composing `EventEntryScreen` over `app.graph`. The six clicks on the app-bar Save move to the bottom button, and their "the app bar's Save is the one that commits" comments are corrected:
- `JournalSmokeTest.kt:77-79`, `:131` and `ScanSheetIncidentNavigationTest.kt:71`, `AssetServiceCasesTest.kt:189` (new entries): `onNodeWithText("Record entry").performScrollTo().performClick()`;
- `JournalDeviceProofTest.kt:435-441` `saveEntry()` (new entries at `:97`, `:194`, `:224`, `:463`; an edit at `:133`): `onNode(hasText("Record entry") or hasText("Save entry")).performScrollTo().performClick()`;
- `EditorsDeviceProofTest.kt:483-489` `saveEntry()` (three new entries): `Record entry`; its `:100`, `:117`, `:163` are the editors' own Save and stay.

Then `git grep -nE 'WithText\("Save"\)' -- app/src/androidTest` and classify every remaining hit as another screen's Save (share intake, the editors, the "When was this done?" dialog at `ScanSheetTest.kt:241`, `:612`, …). **Proof:** `./gradlew :app:testDebugUnitTest --tests '*UiLiteralGuardTest' --tests '*LocalizationCoverageTest'`; `:app:compileDebugAndroidTestKotlin`; `tools/emulator/prepare-emulator.sh`, then on `emulator-5554` the classes `EventEntryActionsTest`, `JournalSmokeTest`, `JournalDeviceProofTest`, `EditorsDeviceProofTest`, `ScanSheetIncidentNavigationTest`, `AssetServiceCasesTest`, `FormCompletionNavigationTest`; `./gradlew :app:lintDebug` 0 errors.

### T2b — the keyboard, observed (#118) · ~20 min (+10 if the inset is needed)
**O1 only, from the shell; no scratch test, script or file in the tree.** After `tools/emulator/prepare-emulator.sh`, on `emulator-5554` with the T2 build: a new entry on a profile long enough to scroll (the fixture shape of `EditorsDeviceProofTest.imeNextWalksFocusDownALongProfile`, `:353`, built through the app), the notes field focused so the keyboard is up, the column scrolled to its end. Question: are the **Record entry** button's bounds above the IME's top? Compose's `assertIsDisplayed` (A2) checks a node against its own window, never against the IME window over it, so only this sees it. The IME inset, the button's bounds and a screenshot go to the ledger directory. Covered → §3.7's `imePadding`, observe again, and rerun `EventEntryActionsTest` and `JournalSmokeTest`; inset 0 → stop and report.

### T3 — the release (1.8.1 / 22) · ~40 min
- `app/build.gradle.kts:45-54`: `versionCode = 22`, `versionName = "1.8.1"`, the comment rewritten (a PATCH, #117 #118; schema 21 / format 20 unchanged, so exports move both ways between 1.8.0 and 1.8.1).
- `VersionAgreementTest`: `theReleaseIdentityIs181AndCode22` (`:59-63`); the status echo `"1.8.1"` (`:157`); new `versioningRecords181` modelled on `versioningRecords180` (`:451-491`): one row `| 1.8.1 | 22 |`, naming `#117` and `#118`, **PATCH**, `schema **21**` … `format **20**` unchanged, linking `docs/releases/1.8.1.md` (which exists), naming `servicetag-v1.8.1`, no `PLACEHOLDER`, the only row claiming 22, after the 1.8.0 row (still there once). `versioningRecords180` is not edited.
- `docs/versioning.md`: the row after `:46`.
- `docs/releases/1.8.1.md`, 1.8.0's shape: `# ServiceTag 1.8.1`, an opening paragraph (version code 22, a fix release); `## Completing a schedule with its form (#117)` (the completion, the due date moving, the standing reminder clearing, the arrival asking once, the scan sheet continuing to the next form); `## One save button on the entry form (#118)`; `## Developer API & MCP` (nothing changed); `## Upgrading` (in place over 1.8.0, data stays, schema 21 / format 20); `## Known limits` (entries saved on 1.8.0 from a schedule's form stay ordinary entries and editing cannot link them — delete duplicates and record the service once; **Record entry** on a form opened from a schedule always completes its current occurrence, so a reading that is not a service is logged from the asset; a FORM schedule's meter reading is taken only if its form carries that reading). Owner-voice; no names from the owner's data.
- `README.md:15`, `:32-33`: the baseline 1.8.1 with its notes; `:33` reads "the previous release: [1.8.0 release notes]", and the page keeps a `](docs/releases/1.7.0.md)` link (`VersionAgreementTest.kt:741` pins it). `docs/capabilities.md:3`, `:196`: the baseline and its notes; the 1.7.0 link stays (`:742`).
- `docs/release-proofs.md` after `:143`: "Cut as 1.8.1 (code 22): no schema or format change; the phones ran 1.8.0, so the emulator proof is 1.8.0 → 1.8.1 in place, and it repeats the token-survival check."
- **Proof:** `./gradlew :app:testDebugUnitTest --tests '*VersionAgreementTest'`, then R1.

### Acceptance (each task review checks these against the real diff)

- **T1a:** C1–C3 red then green; `:681-700` untouched and green; only two main files and one test file under `core/` change.
- **T1b1:** V1–V13 red then green (V5, V6, V9, V10 are pins, green throughout); every existing `EventEntryViewModelTest` case unchanged and green; no route or root file touched.
- **T1b2:** RT1–RT4, SD1, SH1 red then green; `onLogForm` has one shape in all three composables; `Route.kt` alone builds an entry route from a `NeedsForm`; `clearArrival` is called only from the detail's two root handlers; the androidTest edits are the five lambda shapes only.
- **T1c:** every case red with its guard or root lambda reverted, green on the branch; only `deepLinks` and taps drive it; no `assert(`.
- **T2:** A1 red before the removal; `git grep -nE 'R\.string\.journal_save\b|name="journal_save"' -- app` prints nothing; the six sites and their comments corrected and nothing else in those files; G1 §1.3 and the KDoc agree with the screen.
- **T2b:** O1 recorded in the ledger; `imePadding` present if and only if O1 required it; `git status` shows no new file under the scanned trees.
- **T3:** `VersionAgreementTest` green; `versioningRecords180` unedited; every remaining `1.8.0` outside the release notes, the evidence history and that case is deliberately historical (`git grep -nF 1.8.0` reviewed line by line).

**The whole-branch review asks:** is any path left where a schedule-opened form writes through `LogEvent` (I1)? Does any other caller of `CompletionCommand` set `title` or `viaForm` (I7)? Is `detailsPending` reachable as true from the app (`CompletionFlowTest :160-163` says it is not)? Can any arrival ask twice, or any clear dispose an entry with a question open (§3.3)? Does the sheet resume its queue (I11)? One commit control in both modes (I12)? Did anything on the untouched list move?

## 6. Test matrix

"Fails without" names the mechanism on the 1.8.0 code or, for a pin, the wrong implementation it catches.

| ID | Layer · file | Case | Fails without the change because |
|---|---|---|---|
| C1 | JVM · core `ScheduleOperationsTest` | all-optional profile, `viaForm = true`, nothing typed → `detailsPending` false, `profileId` set | `minimal` alone decides: details-pending |
| C2 | JVM · core | `viaForm`, required reading empty → `EventValidation(Required(def))`; no event; due unchanged | a minimal FORM completion is built against no profile, so it is written |
| C3 | JVM · core | `title = "Blade swap"` → stored; `title = null` → the schedule's title (the profile-backed production path) | the title is always the schedule's |
| V1 | JVM · `EventEntryViewModelTest` | FORM schedule (FIXED, quarterly, two periods overdue), model opened with its id, required reading typed, `save()` → **one** event: `scheduleId`, `occurrenceOn` = the computed due read before, `detailsPending` false, `source` MANUAL, kind the profile's; `saved` emits; the due is now the first series date after max(occurrence, occurred-on) — no replay | `LogEvent`: `scheduleId` and `occurrenceOn` null, due unchanged |
| V2 | JVM · same | readings, a suggested material and one linked to a supply item → all carried on the linked event (names, `neutralNumber` quantities, units, `supplyId`) | unlinked (V1's assertion); catches a `toCompletion()` dropping a list |
| V3 | JVM · same | required reading empty → row marked, `saving` false, no event, due unchanged | without `viaForm` a details-pending completion is written |
| V4 | JVM · same | all-optional profile, nothing typed → linked, `detailsPending` false | without `viaForm`: details-pending |
| V5 | JVM · same | pin: same asset and profile, no id (the asset screen's way in) → unlinked, due unchanged, no reconcile | catches linking by asset or profile |
| V6 | JVM · same | pin: edit a linked completion → same id, link and key kept, one event, no offer, no reconcile | catches an edit routed through `CompleteSchedule` |
| V7 | JVM · same | the schedule archived after load → could-not-save line, **zero** events; the same for a deleted schedule | 1.8.0 writes an unlinked event; catches a `LogEvent` fallback |
| V7c | JVM · same | the schedule edited to a group target (QUICK, no profile) after load → could-not-save line, zero events | the uncaught `GroupCompletionNotSupported` crashes the save |
| V8a | JVM · same | profile-less FORM schedule: the Entry field opens with the schedule's title; saved untouched → that title; edited → the edit | the field opens empty and the save is refused `TitleRequired` |
| V8b | JVM · same | profile-backed: the stored title is the schedule's, not the profile's default | the form's title (the profile default) is stored |
| V9 | JVM · same | pin: offers asked once after a schedule-opened save | catches offers skipped or doubled |
| V10 | JVM · same | pin: two `save()` in one frame → one event, the due advanced once | catches the guard lost on the new branch |
| V11 | JVM · same | construction with `scheduleId` plus `eventId`, or plus `pending`, or without the completion collaborators → `IllegalArgumentException` | no such check |
| V12 | JVM · same | the counting reconcile: once after a schedule-opened save, before the offer; zero after a plain entry and after an edit; a reconcile that throws leaves the save standing | the form never reconciles |
| V13 | JVM · same | the asset transferred out after load → the transferred-out line, zero events | pin: catches the held refusal mapped to the wrong line on the new branch |
| RT1 | JVM · `RouteTest` | `formEntry(NeedsForm(s, a, p))` = `EventEntry(a, p, null, scheduleId = s)`; `p` null keeps `profileId` null, no `kind` | no field, no function; catches a dropped id |
| RT2 | JVM · same | 1.8.0's stored JSON decodes with `scheduleId` null; null is not encoded; a set one round-trips | catches a field without a default (`MissingFieldException` on restore) |
| RT3 | JVM · same, reads `ServiceTagRoot.kt` | every line matching `^\s*onLogForm = \{` opens a lambda whose body, to its closing brace, contains `formEntry(` and no `Route.EventEntry(`; exactly three such lines | 1.8.0's three lambdas build `Route.EventEntry(assetId, profileId, null)` by hand; CI's only guard on the root wiring |
| RT4 | JVM · same | `clearArrival`: `(x, true)` becomes `(x, false)` in place; a `(x, false)` elsewhere, another schedule's arrival and every other entry untouched; no-op when absent or already false | no such function |
| SD1 | JVM · `ScheduleDetailViewModelTest` | `arrive()` twice on one model → one flow run (one `NeedsForm` for FORM; one prompt for a QUICK meter schedule); `arrivalEnded` once after a completed, cancelled or refused run, never after `NeedsForm` or the held refusal | `complete()` runs on every call |
| SH1 | JVM · `MaintenanceSheetViewModelTest` | the first `onResumed()` does not re-derive; after a FORM completion is written behind the model, the next `onResumed()` drops the row, opens the next selected form and reconciles once | the guard lives in the screen's `remember` and resets |
| N1 | Compose · `FormCompletionNavigationTest` | `Route.ScheduleDetail(s)` → **Log maintenance** → form → reading → **Record entry** → the detail: no OVERDUE, no "No service recorded yet", the completion listed; one linked event | unlinked; the detail still OVERDUE (the owner's report) |
| N2 | Compose · same | arrival `Route.ScheduleDetail(s, complete = true)` → the form opens by itself → **Record entry** → the detail on top, no **Record entry** node after idle, exactly one event linked to `s`, no OVERDUE | the detail composes afresh, calls `complete()` again and reopens the form (F1) |
| N2b | Compose · same | arrival → form → ✕ → the detail; the form does not reopen after idle; zero events | the same re-run: ✕ loops back into the form |
| N3 | Compose · same | `Route.MaintenanceSheet(asset)` → select the FORM row → **Complete selected** → form → **Record entry** → back on the sheet, the row gone, one linked event | unlinked; and the first resume skips `refresh()` (F2), so the row stays |
| N3b | Compose · same | two FORM rows selected → **Complete selected** → first form → **Record entry** → the second form opens by itself → **Record entry** → the sheet, both rows gone, two linked events | the second form never opens and the first row stays (F2) |
| N4 | Compose · same | the Maintenance destination's **Log maintenance** → the FORM row → form → **Record entry** → one linked event | unlinked |
| A1 | Compose · `EventEntryActionsTest` | new and edit: no node with the exact text "Save"; ✕ present; exactly one "Record entry" / "Save entry" | the app bar carries **Save** |
| A2 | Compose · same | pin: type into the notes field, then the bottom button `performScrollTo()` → displayed, enabled; tapping it stores one event and leaves | catches the button removed, disabled after focus, or outside the scroll column |
| O1 | emulator observation (T2b) | the IME up, scrolled to the end: the button above the IME | no test layer sees the IME window |

**Existing suites, unchanged and green:** core `ScheduleOperationsTest` (`:681-700`), `EventUseCasesTest`, `CrossConceptWriteTest`; app `CompletionFlowTest` (`:165-192`), `MaintenanceSheetViewModelTest` (its simulated save `:815` and `reconciles == 1` stand), `ScheduleDetailViewModelTest`, `MaintenanceRoutesTest`, `QuickActionsTest`, `VersionAgreementTest` (T3's named cases only); androidTest `ScanSheetTest`, `ScheduleOperationsTest`, `QuickActionDeviceProofTest`, and the six migrated clicks.

## 7. Strings

**No new string.** **One removed:** `journal_save` and its nine translations. Reused: `journal_record_entry`, `journal_save_entry`, the could-not-save and transferred-out lines, the field-problem lines, and the existing **Entry** field (`journal_field_entry`), now prefilled with the schedule's own title (owner data, not a string). The schedule-opened form keeps **Record entry**; no PENDING label is proposed.

## 8. Release proofs (at the integrated tip, after the whole-branch review)

| Row | Command / act | Expectation |
|---|---|---|
| R1 | `./gradlew --rerun-tasks :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest` | green from scratch; includes R5 and `ReleaseProofPolicyTest` |
| R2 | `tools/emulator/prepare-emulator.sh`; stage the preserved set (runbook §"Before R2"); `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`, off NVIDIA | the complete suite, **zero skips**, `FormCompletionNavigationTest` and `EventEntryActionsTest` among the classes |
| R3 | the runbook's line as written (`release-proofs.md:18`): `tools/servicetag-mcp`, `tools/servicetag-bundle`, `tools/servicetag-schedules` | each tool's pytest green; none changed |
| R4 | inside R2: `ShareBoundaryTest`, `ContactGrantBoundaryTest` | five cases; nothing added |
| R5 | inside R1: `ManifestContractTest`, `MergedManifestContractTest` | green; the manifest unchanged |
| R6 | `bash tools/check-commit-hygiene.sh servicetag-v1.8.0 HEAD` (authoritative for author, committer and bodies: every non-merge commit is its subject alone; merges exempt, `check-commit-hygiene.sh:52-57`); the runbook's content greps over `servicetag-v1.8.0..HEAD`; `bash tools/check-submodule-pin.sh`; the androidTest `assert(` grep; `./gradlew :app:lintDebug` | no e-mail, home path or non-emulator serial in added lines; no `co-authored`, `generated-by`, tool or model name in a message; `submodule pin ok`; no `assert(`; lint 0 errors, nothing suppressed |
| D | `bash tools/release-dry-run.sh` | passes: the pin, the gate, `assembleRelease`, version 1.8.1 / 22, one signer equal to `RELEASE_CERT_SHA256` |
| U | `tools/emulator/prepare-emulator.sh`; on `emulator-5554` the published, checksum-verified 1.8.0 asset installed, a fictional Home Assistant connection (`ha.example`, an `example-` token) saved; the dry-run candidate `adb install -r` in place | UID and `firstInstallTime` unchanged; version code 22; schema 21 / format 20 on both sides; `GET /v1/status` counts identical; the connection and the token still present |
| R7 | the verified, published APK `adb install -r` over 1.8.0 on the **development phone** (serial in the environment only) | UID and `firstInstallTime` unchanged; version code 22; `/v1/status` counts identical; no export requested |

The tag `servicetag-v1.8.1` goes on the tip after R1–R6, D, U and the review, by the owner's authorization; the release workflow's environment approval stays the owner's gate. The **production phone** is not a proof row (`release-proofs.md:99`): its install is on the owner's word, with the same UID / `firstInstallTime` / version check and the token-survival check. The controller writes the evidence (tip, runs, APK checksum, signer, U, R7) into `docs/architecture/product-split-evidence.md` under a 1.8.1 heading after 1.8.0's.

## 9. Rulings and what is left open

- **Q-A** (F3): ruled — the schedule's title; a profile-less form opens with it filled in (D3). *Reading of the ruling, for the owner to confirm or overturn:* the profile-less **Entry** field stays editable, and an explicit edit there is stored (a drawn field that ignored its edit would mislead).
- **Q-B** (F4): ruled — the form reconciles after the write (D8).
- **Q-C**: ruled — cut from master (header).
- **Q1** (keyboard): decided by T2b's observation; the one stop condition is an IME inset of 0, which would need an app-wide `adjustResize` and goes back to the owner.
- **Q2**: ruled — the 1.8.0 duplicates stay; a known limit in the notes.
- **Q3**: ruled — the FORM-plus-meter reading gap is a follow-up issue, filed by the controller; a known limit in the notes.

## 10. Errata (2026-10-23)

Written at T3, after T1 and T2 were reviewed. Where this section and the text above disagree, this section is what was done.

- **#120 added by ruling (T1d, `856b1226`).** Case N4 of `FormCompletionNavigationTest` found that the Maintenance screen's Log maintenance picker (`LogMaintenancePicker` in `CompletionFlow.kt`) closes before it draws: its first composition sees an empty list and dismisses. The owner ruled the fix into 1.8.1 as #120. The §5 file table said `CompletionFlow.kt` changes in the picker callback only; T1d also changes the picker's loading state (a nullable initial value, nothing drawn while loading, the dismissal only once the list has loaded empty). No new string. 1.8.1 is #117, #118 and #120.
- **T2b, the keyboard observation (O1).** The button's bounds were checked against the IME's top. With the column scrolled to its end and then a field tapped, the foot button sat fully under the keyboard, in portrait and in landscape. The reason is not the button: the manifest sets no `windowSoftInputMode` (so the activity pans), and `ServiceTagRoot`'s outer `Scaffold` and each screen's own `Scaffold` both apply the system-bar insets. The IME inset was not 0, so `imePadding` was tried as §3.7 allowed.
- **T2c, the single-screen padding attempt.** `imePadding` on the entry form alone: portrait passed; landscape did not, because the window still pans on top of the padding and the doubled bars take the remaining height, so the form's column collapsed to nothing. It was reverted. Only T2's review fixes were kept, in `f16b90d1`. The entry form has no IME padding in 1.8.1.
- **#121 split to 1.8.2 by the owner.** The keyboard and the system-bar insets are an app-wide change (consume the outer `Scaffold`'s insets, `adjustResize`, per-screen insets, every form-bearing screen observed with the keyboard up in both orientations), not a PATCH. §3.7's "if T2b finds the button covered, `imePadding` on the form" is therefore not done in 1.8.1, and the G1 §1.3 note and the `EventEntryScreen` KDoc say the button is reachable once the keyboard closes, with #121 making it reachable while typing. D7's "reachability is observed, not assumed" is what the observation settled: it is not reachable in every order of taps.
- **Known limits, as in `docs/releases/1.8.1.md`.** (a) With the keyboard open, **Record entry** can sit under the keyboard; Back closes the keyboard; 1.8.2 (#121) fixes it. (b) Entries 1.8.0 saved from a schedule's form stay unlinked (Q2); the duplicates are deleted by hand, then the schedule is completed once. (c) **Done** on a reminder that asks for a meter reading records the service as soon as the reading is answered, and may then ask a follow-up question; if Android ends ServiceTag before that question is answered (the arrival has not ended: `arrivalEnded` comes after the offers), reopening ServiceTag restores the arrival and can ask for the reading again, and answering it records the service a second time, for the next occurrence; cancelling it writes nothing (the window that §3.3's clear-on-ending leaves; a one-tap **Done** opens no app and has no such window). (d) **Record entry** on a form opened from a schedule always completes its current occurrence. (e) A FORM schedule's meter reading is taken only if its form carries that reading (Q3).
