# ServiceTag 1.8.1 — #117 a FORM schedule completes through its form, #118 one commit button on the entry form: plan (rev 1.0, 2026-10-23)

**Release:** 1.8.1 / `versionCode` 22, a **PATCH** by `docs/versioning.md` ("Bug fix or behaviour correction: something that was supposed to work already now does"; #118 is a "Small UI correction"). No Room schema or backup-format change: schema **21** / format **20**, as 1.8.0.
**Base:** master `62e39670` (1.8.0 / code 21 + the hygiene workflow, #111 #112). Every `file:line` below was read on that commit.
**Inputs:** the bodies of #117 and #118 (the owner's, with the root cause); `docs/superpowers/planning-policy.md` (plans specify; one task review per task; one whole-branch review); `docs/release-proofs.md` R1–R7.
**Branch:** `fix-117-118-form-completion`, merged to master with a merge commit. The pull request description says it touches `app/src/androidTest` and two screens (the entry form, the three surfaces that hand a form over). Commits follow the repository `CLAUDE.md` (owner identity, subject line only, no tool names); `bash tools/check-commit-hygiene.sh origin/master HEAD` before every push.
**Ledger:** `.superpowers/sdd/2026-10-23-servicetag-1.8.1/progress.md` (controller's, not committed).

## 1. The defect, read on `62e39670`

`CompletionFlow.complete` answers a FORM schedule with `NeedsForm(scheduleId, assetId, profileId)` and writes nothing (`CompletionFlow.kt:75-79`, `:196-197`). Every consumer forwards only `(assetId, profileId)`: `ScheduleDetailScreen.kt:105`, the Log maintenance picker (`CompletionFlow.kt:516-517` → `ServiceTagRoot.kt:186-188`), the scan sheet (`MaintenanceSheet.kt:132` → `ServiceTagRoot.kt:685-687`), and the detail screen once more at `ServiceTagRoot.kt:544-546`. The notification's **Done** on a FORM schedule is not a fourth lambda: it opens `Route.ScheduleDetail(id, complete = true)` (`reminders/QuickActions.kt:128-130`, `:224-225`), whose arrival runs the flow and leaves through the detail screen's `onLogForm` (`ScheduleDetailScreen.kt:109-111`). So there are **four entry points and three root lambdas**.

`Route.EventEntry` (`Route.kt:57-64`) has no schedule field, and `EventEntryViewModel.save()` (`EventEntryViewModel.kt:506-557`) writes through `LogEvent` (`LogEvent.kt:20`: "not a completion"). The only writer that links an event to a schedule is `CompleteSchedule.run` (`CompleteSchedule.kt:63-126`; `scheduleId` `:91`, `occurrenceOn` `:100`), and a completion is exactly an event naming the schedule (`ScheduleRecompute.kt:227-228`). The FORM run of the scan sheet already *assumes* the right contract: `MaintenanceSheetViewModel.resumeQueue` (`:647-672`) treats "the row is no longer offered" as "the form was saved", and `MaintenanceSheetViewModelTest.selectedFormsRunOneAtATime…` simulates the form's save with `graph.completeSchedule.run(...)` — the call the journal screen never made.

**Why `CompleteSchedule` cannot be called as it stands.** Its `CompletionCommand` (`ScheduleCommands.kt:63-71`) has no title, and it decides `detailsPending = FORM && values.isEmpty() && consumables.isEmpty()` (`CompleteSchedule.kt:74-76`). A form saved with nothing typed on an all-optional profile would therefore be stored as **details pending**, and — because a pending completion is built against no profile (`:105-106`) — a form whose required field was left empty would be **written** instead of refused. Both are wrong for a form the owner just filled in. The command needs two defaulted fields (§3.1).

## 2. Decisions

- **D1.** A form opened for a schedule saves through `CompleteSchedule`, in its one `uow.write` (event, conditional postponement clear, recompute). No new use case; `LogEvent` and `UpdateEvent` are not taught about schedules.
- **D2.** `CompletionCommand` gains `title: String? = null` and `viaForm: Boolean = false`. Every existing caller (the quick flow `CompletionFlow.kt:363-376`, the API's `CompletionRequest.toCommand()` `MaintenanceDtos.kt:419-427`, the notification's one-tap seam `quickActionRuns` at `AppGraph.kt:571-576`) sets neither, so their behaviour is byte-for-byte 1.8.0's. The wire (`CompletionRequest`) does not change.
- **D3.** What the form shows is what is stored: the title as typed (validated as every event title is, `EventCommands.kt:193-194`), the date and time, notes, readings and materials with their supply links. The **kind** and **profile** stay `CompleteSchedule`'s (the schedule's profile and its kind, else MAINTENANCE); for a profile-backed form they are already the form's (`EventEntryViewModel.kt:283-284`). A FORM schedule may have no profile (the picker is clearable in FORM mode, `ScheduleEditScreen.kt:415-425`); its form opens exactly as today's profile-less entry and the stored kind is MAINTENANCE.
- **D4.** The id cannot be dropped by a consumer: the three composables' `onLogForm` takes the whole `CompletionOutcome.NeedsForm`, and one function, `formEntry(form)` in `Route.kt` beside `newServiceCaseRoute`, is the only way a `NeedsForm` becomes a route.
- **D5.** A schedule-opened form that is refused (the schedule archived, deleted or re-targeted while the form was open; the asset transferred out; a field problem) writes **nothing** and says so with the lines the form already has. It never falls back to an unlinked entry.
- **D6.** #118 removes the app-bar **Save** (`EventEntryScreen.kt:161-165`) and the `journal_save` resource from all ten string files (its only user is `EventEntryScreen.kt:163`; no test, doc or tool names the key). The bottom button stays: **Record entry** new, **Save entry** editing.
- **D7.** The IME: no screen in the app applies IME insets (`imePadding`, `windowSoftInputMode` and `WindowInsets.ime` appear nowhere under `app/src/main`), the activity is edge-to-edge (`MainActivity.kt:40`, target SDK 36), and G1 §1.3 says the app-bar Save existed *so that* a commit stays reachable with the keyboard open. Reachability is therefore a platform fact to observe, not assume (T2, O1).

**Rejected alternatives.**

- *Let `LogEvent` take a schedule.* `EventCommand`'s link fields default so that "no route that must not be able to create a completion has to mention them" (`EventCommands.kt:52-61`); `LogEvent` also serves the API's event routes and the share intake. Teaching it to complete would open a second completion writer, which master plan decision 36 forbids.
- *A new `CompleteScheduleWithForm` use case.* It would repeat the occurrence stamp, the conditional clear and the recompute that `CompleteSchedule` already does in one transaction; two copies of that are two places to disagree.
- *Link the entry afterwards through an edit.* An edit keeps a link and never adds one (`EventCommands.kt:287-289`), on purpose; and it would be two transactions, the first of which is the bug.
- *Keep `(assetId, profileId)` and add a third parameter.* The defect is a field dropped at a hand-over; passing the whole outcome makes dropping one impossible to write.
- *Keep the app-bar Save and drop the bottom button* (#118's own alternative): rejected by the owner in #118.

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
    /** #117: the profile form's title as typed; null is the schedule's title (every older caller). */
    val title: String? = null,
    /** #117: the profile form collected the details — never details_pending; the profile's required fields apply. */
    val viaForm: Boolean = false,
)
```

In `CompleteSchedule.run`: `detailsPending = FORM && minimal && !cmd.viaForm`; `title = cmd.title ?: schedule.title`; everything else as now (the profile handed to `buildEvent` is still `if (detailsPending) null else profile`, which is what enforces required fields once `viaForm` is set). The class KDoc's third bullet gains one sentence: a completion collected by the form is never minimal. `CompleteGroupMembers` is untouched (a group target is QUICK-only, `ScheduleCommands.kt:113-119`, `:295-296`, so no caller can set `viaForm` for one).

### 3.2 The route and the hand-over (app)

```kotlin
@Serializable data class EventEntry(
    val assetId: String, val profileId: String?, val eventId: String?,
    val kind: String? = null, val pending: PendingConditionArgs? = null,
    val thenServiceCase: Boolean = false,
    /** #117: the FORM schedule this new entry completes. Null: an ordinary entry. Never with an eventId or pending. */
    val scheduleId: String? = null,
) : Route

/** #117: the one way a NeedsForm becomes a route — the schedule's id travels with the asset and the profile. */
fun formEntry(form: CompletionOutcome.NeedsForm): Route.EventEntry =
    Route.EventEntry(form.assetId.value, form.profileId?.value, null, scheduleId = form.scheduleId.value)
```

- `onLogForm: (CompletionOutcome.NeedsForm) -> Unit` on `LogMaintenancePicker` (`CompletionFlow.kt:469`), `ScheduleDetailScreen` (`:85`) and `MaintenanceSheet` (`:97`); each forwards the outcome it received unchanged (`CompletionFlow.kt:517`, `ScheduleDetailScreen.kt:105`, `MaintenanceSheet.kt:132`). The KDoc lines that describe the parameter are corrected in place.
- `ServiceTagRoot.kt` — the three `onLogForm` lambdas (`:186`, `:544`, `:685`) become `backStack.add(formEntry(it))` (the picker's keeps `logging = false` first); `entry<Route.EventEntry>` (`:349-370`) passes `scheduleId = key.scheduleId`. `Route.EventEntry`'s KDoc names the new field.
- `EventEntryScreen(…, scheduleId: String? = null)`; `modelKey` (`EventEntryScreen.kt:106`) appends the id (defensive: the per-entry `ViewModelStore`, `ServiceTagRoot.kt:144-146`, already isolates entries); the factory passes it.

### 3.3 `EventEntryViewModel`

- Primary constructor: two trailing defaulted parameters, `scheduleId: ScheduleId? = null` and `completeSchedule: CompleteSchedule? = null`. The `init` check (`:220-224`) extends to: `scheduleId == null || (completeSchedule != null && eventId == null && pending == null)`, its message marked `// l10n-ok: exception message` like the existing one (`UiLiteralGuardTest`). The graph constructor (`:226-243`) takes `scheduleId: String? = null` and passes `graph.completeSchedule`.
- `save()` builds the same `EventCommand` as today, then chooses the writer:

```kotlin
val event = when {
    eventId != null -> updateEvent.run(eventId, cmd)                       // unchanged
    scheduleId != null -> checkNotNull(completeSchedule).run(scheduleId, cmd.toCompletion())
    else -> logEvent.run(cmd)                                              // unchanged
}
// toCompletion(): occurredOn, occurredTime, tzId, notes, values, consumables,
//                 assetId = cmd.assetId, title = cmd.title, viaForm = true
```

- The catches stay by name. Added: `NoSuchSchedule`, `ScheduleArchived`, `GroupCompletionNotSupported` → `refuse(e)`, which shows the shipped could-not-save line (`:696-699`). `EventValidation` → `markProblems` and `AssetTransferredOut` → `refuse` already cover the rest. The offer path after a write (`:539-548`) and the `saved` emission are untouched, so `onSaved`/`onDone` (`EventEntryScreen.kt:114-120`) behave as before.

### 3.4 Refusals, edge cases and what each leaves behind

| Situation while the form is open | `CompleteSchedule` answers | The form shows | Written |
|---|---|---|---|
| a required reading empty, a bad number, a bad material line, an unknown supply item | `EventValidation` | the row marked, as today (`markProblems`) | nothing |
| the title cleared and the profile has no default title | `EventValidation(TitleRequired)` | as today | nothing |
| the schedule archived | `ScheduleArchived` | the could-not-save line | nothing |
| the schedule deleted | `NoSuchSchedule` | the could-not-save line | nothing |
| the schedule re-targeted to a group | `GroupCompletionNotSupported` | the could-not-save line | nothing |
| the asset transferred out | `AssetTransferredOut` (held-write guard) | the transferred-out line, as today | nothing |
| the schedule postponed | — | — | the completion; `occurrenceOn` is the **computed** due and the postponement is cleared, exactly as the quick path |
| the schedule switched to QUICK | — | — | the completion, `source` SCHEDULE_QUICK_COMPLETE (the use case's rule, unchanged); the form's details are kept |
| the schedule's profile changed | — | — | the completion against the schedule's current profile; values are checked for ownership by `resolveOwnedProfile` as for any event |
| the schedule paused | — | — | the completion, as the quick path allows today |
| the app killed and the stack restored | — | the form, still carrying `scheduleId` (the route is `@Serializable`) | on save, the completion |

A FORM schedule that also counts a meter keeps 1.8.0's behaviour (§9 Q3): the reading is collected only if the profile carries that definition, and the completion is written either way.

### 3.5 The entry form (#118)

- `TopAppBar` keeps the title and the ✕ navigation icon; the `actions` block (`EventEntryScreen.kt:161-165`) is removed. The bottom `Button` (`:241-248`) is the form's only commit control.
- The screen's KDoc (`:73-79`) and G1 §1.3 (`docs/design/g1/01-g1-visual-gate-report.md:52` Hierarchy row, `:56` Keyboard row) say: app bar ✕ and the eyebrow; one filled commit button at the foot; reachable with the keyboard open because the column scrolls (and, if O1 requires it, is inset above the IME). `docs/design/phase-2a-evidence.md:291-293` is a historical record and stays as written.
- If O1 finds the button covered: `Modifier.padding(padding).consumeWindowInsets(padding).imePadding()` ahead of `verticalScroll` on the form's `Column` (`:172-177`), nothing else. If the IME inset reads 0 under the current soft-input mode (so `imePadding` changes nothing), **stop**: an `adjustResize` manifest change is app-wide and is the owner's call (§9 Q1).

## 4. Invariants

| # | Invariant |
|---|---|
| I1 | A form opened with a `scheduleId` never writes an event whose `scheduleId` is null — not on success, not on any refusal (D5). |
| I2 | A schedule-opened save is one transaction: the event, the conditional `postponed_due_on` clear, the recompute. A refusal writes no row anywhere. |
| I3 | The linked event: `scheduleId` = the schedule; `occurrenceOn` = the schedule's computed due at write time, never the postponed date; `detailsPending` false; `source` MANUAL; `profileId` the schedule's; kind the profile's, else MAINTENANCE; title as typed; readings, materials and supply links as the form sent them. |
| I4 | A route without `scheduleId` saves exactly as 1.8.0: `LogEvent` new, `UpdateEvent` edit, `RecordConditionWithIncident` pending, from the same `EventCommand`. |
| I5 | An edit never completes: `eventId != null` ⇒ `UpdateEvent`; a model with `eventId` or `pending` and a `scheduleId` is refused at construction. |
| I6 | Every `NeedsForm` reaches the entry route through `formEntry`; no consumer receives the outcome's parts. |
| I7 | `/v1`, the MCP and the quick paths are unchanged: no caller but the form sets `title` or `viaForm`; a minimal completion of a FORM schedule through the API still writes `details_pending` true (core `:681-700`), and the notification's one-tap seam, which serves QUICK schedules only (`reminders/QuickActions.kt:128-132`), is unchanged. |
| I8 | One completion per save: a late completion yields exactly one next occurrence (no backlog replay), and two taps in one frame write one event. |
| I9 | Offers after a schedule-opened save are asked once, as after any new entry; `formEntry` sets no `kind`, `pending` or `thenServiceCase`. |
| I10 | A back stack stored by 1.8.0 (no `scheduleId` key) restores as an ordinary entry; a null `scheduleId` is not encoded. |
| I11 | The entry form has one commit control, the bottom button; the app bar has ✕ and no Save, new or editing. |
| I12 | The commit control is reachable with the IME open (O1). |

**Untouched (a reviewer checks none of these moves):** `MaintenanceDtos.kt` and every API/MCP file; `CompletionFlow.complete`, `attempt`, `offerAfter` and the quick path's `command` (`:363-376`); `CompleteGroupMembers`; `LogEvent.kt`; `UpdateEvent.kt`; `EventCommands.kt` (`buildEvent` already keeps a stored link on edit, `:287-289`); `reminders/QuickActions.kt`; `ScheduleDetailScreen.kt:255-265` and `:360-372` (the group round's buttons: a group target is QUICK-only, so they never produce `NeedsForm`); `MaintenanceSheetViewModel` (its FORM run starts working as designed, with no code change); Room schema and backup format; every string except the removal of `journal_save`.

## 5. Tasks

Order: **T1a → T1b → T1c → T2 → T3**, one lane, one implementer per task, one task review each, then one whole-branch review. T1b and T2 share `EventEntryScreen.kt`, and T1c and T2 both need `emulator-5554`, so nothing runs in parallel. No task exceeds 60 minutes; a task at 60 that is not green stops and is inventoried (bounded fix rounds, 45 min).

**File ownership** (a file appears under one task only, except where a later task makes a stated, narrower change):

| File | T1a | T1b | T1c | T2 | T3 |
|---|---|---|---|---|---|
| `core/.../usecase/ScheduleCommands.kt`, `CompleteSchedule.kt` | ✓ | | | | |
| `core/src/test/.../usecase/ScheduleOperationsTest.kt` | ✓ | | | | |
| `app/.../ui/nav/Route.kt`, `ServiceTagRoot.kt` | | ✓ | | | |
| `app/.../ui/maintenance/CompletionFlow.kt`, `ScheduleDetailScreen.kt`, `MaintenanceSheet.kt` | | ✓ (callback only) | | | |
| `app/.../ui/journal/EventEntryViewModel.kt` | | ✓ | | | |
| `app/.../ui/journal/EventEntryScreen.kt` | | ✓ (parameter, key) | | ✓ (app bar, KDoc, insets) | |
| `app/src/test/.../EventEntryViewModelTest.kt`, `ui/nav/RouteTest.kt` | | ✓ | | | |
| androidTest `ScanSheetTest.kt`, `ui/maintenance/ScheduleOperationsTest.kt` | | ✓ (lambda shape) | | | |
| androidTest `FormCompletionNavigationTest.kt` (new) | | | ✓ | | |
| androidTest `EventEntryActionsTest.kt` (new) and the six Save-click files | | | | ✓ | |
| `res/values*/strings_records.xml` (×10), `docs/design/g1/01-g1-visual-gate-report.md` | | | | ✓ | |
| `app/build.gradle.kts`, `VersionAgreementTest.kt`, `docs/versioning.md`, `docs/releases/1.8.1.md`, `README.md`, `docs/capabilities.md`, `docs/release-proofs.md` | | | | | ✓ |

### T1a — the completion command (#117, core) · ~30 min

- **Files:** `core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/ScheduleCommands.kt` (`CompletionCommand`, §3.1), `core/.../usecase/CompleteSchedule.kt` (the two rules and the KDoc sentence), `core/src/test/kotlin/com/loosecannon/servicetag/core/usecase/ScheduleOperationsTest.kt` (C1–C3, beside `aMinimalCompletionOfAFormScheduleOwesItsDetails` `:681-700`, which stays unchanged).
- **Proof:** `./gradlew :core:test` green; C1–C3 seen failing first.

### T1b — the hand-over and the save (#117, app) · ~60 min

- **Files:** `app/src/main/kotlin/com/loosecannon/servicetag/ui/nav/Route.kt` (field, `formEntry`, KDoc); `ui/maintenance/CompletionFlow.kt` (picker's callback type and forward only); `ui/maintenance/ScheduleDetailScreen.kt` and `ui/maintenance/MaintenanceSheet.kt` (callback type, forward, KDoc); `ui/nav/ServiceTagRoot.kt` (three lambdas, the entry's `scheduleId`); `ui/journal/EventEntryScreen.kt` (parameter, `modelKey`, factory — **not** the app bar, which is T2's); `ui/journal/EventEntryViewModel.kt` (§3.3). Tests: `app/src/test/.../ui/journal/EventEntryViewModelTest.kt` (V1–V11, through `FakeGraph`, which already builds `completeSchedule` and `saveSchedule`, `FakeGraph.kt:644-652`); `app/src/test/.../ui/nav/RouteTest.kt` (RT1–RT3). The callback-type change obliges the androidTest lambdas to compile: `ScanSheetTest.kt:186, :334, :580, :722` and `ScheduleOperationsTest.kt:104` (androidTest) become one-argument lambdas; the two recording ones record `form:<asset>:<profile>:<schedule>`.
- **Proof:** `./gradlew :app:testDebugUnitTest --tests '*EventEntryViewModelTest' --tests '*RouteTest' --tests '*CompletionFlowTest' --tests '*MaintenanceSheetViewModelTest' --tests '*ScheduleDetailViewModelTest'` then the whole `:app:testDebugUnitTest`; `./gradlew :app:compileDebugAndroidTestKotlin`.

### T1c — the four entry points end to end (#117, Compose) · ~45 min

- **Files:** new `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/maintenance/FormCompletionNavigationTest.kt` — the real `ServiceTagRoot`, composed and driven through `deepLinks` exactly as `ScanSheetIncidentNavigationTest` (`:42-63`) does, `clearInstall()` before each case, fixtures fictional (an asset "Mower", a FORM schedule "Blade sharpen" anchored two quarters back, its profile with one required reading and one suggested material). Cases N1–N4. Each commits with the bottom **Record entry** (`performScrollTo()` first), so the class is independent of T2.
- **Proof:** `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.ui.maintenance.FormCompletionNavigationTest`; the four cases seen failing on the T1a+T1b tree with the root lambdas reverted (a local revert, never committed), then green.

### T2 — one commit button (#118) · ~45 min

- **Files:** `ui/journal/EventEntryScreen.kt` (§3.5; KDoc); `app/src/main/res/values*/strings_records.xml` — remove `journal_save` from all ten (default, `b+zh+Hans`, `de`, `es`, `fr`, `hi`, `it`, `ja`, `pt`, `ru`); `docs/design/g1/01-g1-visual-gate-report.md` §1.3; new `app/src/androidTest/.../ui/journal/EventEntryActionsTest.kt` (A1–A2, `EventEntryScreen` composed alone over `app.graph`).
- **The six instrumented clicks on the app-bar Save move to the bottom button** (`onNodeWithText("Record entry"|"Save entry").performScrollTo().performClick()`), and the comments that call the app-bar Save "the one that commits" are corrected: `JournalSmokeTest.kt:77-79` and `:131`; `JournalDeviceProofTest.kt:435-441` (`saveEntry()`, used five times); `EditorsDeviceProofTest.kt:483-489` (`saveEntry()`, used three times; its `:100`, `:117`, `:163` are the definition and profile editors' own Save and stay); `ScanSheetIncidentNavigationTest.kt:71`; `AssetServiceCasesTest.kt:189`. Then `git grep -nE 'WithText\("Save"\)' -- app/src/androidTest` and classify every remaining hit as another screen's Save (share intake, editors, the "When was this done?" dialog in `ScanSheetTest.kt:241, :612`, …); a miss surfaces in R2.
- **O1 (the emulator observation, the one thing no semantics test can see):** on `emulator-5554`, a new entry on a profile long enough to scroll, the IME shown (focus the notes field; `WindowInsetsControllerCompat.show(ime())` if the keyboard does not rise by itself), the column scrolled to its end: is the **Record entry** button's window bounds above the IME's top? Compose's `assertIsDisplayed` checks the node against its own window, not against the IME window over it, so A2 cannot answer this. Record the IME inset, the button's bounds and a screenshot in the ledger. Covered → apply §3.5's `imePadding` and observe again; inset 0 → stop (Q1). Not a committed test and not a release row.
- **Proof:** `./gradlew :app:testDebugUnitTest --tests '*UiLiteralGuardTest' --tests '*LocalizationCoverageTest'` (the key removed from every locale at once); `:app:compileDebugAndroidTestKotlin`; on `emulator-5554` the classes `EventEntryActionsTest`, `JournalSmokeTest`, `JournalDeviceProofTest`, `EditorsDeviceProofTest`, `ScanSheetIncidentNavigationTest`, `AssetServiceCasesTest`, `FormCompletionNavigationTest`; `./gradlew :app:lintDebug` 0 errors.

### T3 — the release (1.8.1 / 22) · ~40 min

- `app/build.gradle.kts:45-54` — `versionCode = 22`, `versionName = "1.8.1"`, the comment rewritten: a PATCH (#117, #118), schema 21 / format 20 unchanged, so a 1.8.1 export restores into 1.8.0 and back.
- `VersionAgreementTest` — `theReleaseIdentityIs181AndCode22` (`:59-63`), the status echo `"1.8.1"` (`:157`); new `versioningRecords181`, modelled on `versioningRecords180` (`:451-491`): exactly one row `| 1.8.1 | 22 |`; it names `#117` and `#118`, says **PATCH**, carries `schema **21**` … `format **20**` unchanged, links `docs/releases/1.8.1.md` (which exists) and names `servicetag-v1.8.1`, no `PLACEHOLDER`; no other row claims 22; it follows the 1.8.0 row, which is still there once. `versioningRecords180` is not edited.
- `docs/versioning.md` — the row after `:46`: "PATCH: **a FORM schedule completes through its form (#117)** … **one commit button on the entry form (#118)** … schema **21** / format **20** unchanged …; no `/v1` or MCP change; notes `docs/releases/1.8.1.md`; tag `servicetag-v1.8.1`."
- `docs/releases/1.8.1.md` — 1.8.0's shape: `# ServiceTag 1.8.1`, an opening paragraph (version code 22, a fix release), `## Completing a schedule with its form (#117)`, `## One save button on the entry form (#118)`, `## Developer API & MCP` (nothing changed: no route, field, status or tool; the MCP is 1.8.0's), `## Upgrading` (in place over 1.8.0, data stays, schema 21 / format 20, exports move both ways), `## Known limits` (entries saved on 1.8.0 from a schedule's form stay ordinary entries — editing cannot link them; delete the duplicates and record the service once; Record entry on a form opened from a schedule always completes its current occurrence, so a reading that is not a service is logged from the asset). Owner-voice, no names from the owner's data.
- `README.md:15, :32-33` and `docs/capabilities.md:3, :196` — the baseline becomes 1.8.1 with its notes; both keep their link to the **1.7.0** notes, which `theCapabilitiesPageNamesSupplyItemsAndInstalledComponents` (`VersionAgreementTest.kt:741-743`) pins. The capabilities page does not describe FORM completion, so nothing else there changes.
- `docs/release-proofs.md` after `:143`: "Cut as 1.8.1 (code 22): no schema or format change; the phones ran 1.8.0, so the proof is 1.8.0 → 1.8.1 in place, and it repeats the token-survival check."
- **Proof:** `./gradlew :app:testDebugUnitTest --tests '*VersionAgreementTest'`, then R1.

### Acceptance, per task (the task review checks these against the real diff)

- **T1a:** C1–C3 red then green; `aMinimalCompletionOfAFormScheduleOwesItsDetails` untouched and green; `git diff` touches two main files and one test file under `core/`; no change under `app/`.
- **T1b:** V1–V11 and RT1–RT3 red then green (V5, V6, V9, V10 are pins and green throughout); every existing case in the five named classes green unchanged; `onLogForm` has one shape in all three composables; `Route.kt` is the only file that constructs an entry route from a `NeedsForm`; the only androidTest edits are the five lambda shapes.
- **T1c:** N1–N4 red with the root lambdas locally reverted, green on the branch; the class composes `ServiceTagRoot` and drives it only through `deepLinks` and taps; fixtures fictional; no `assert(`.
- **T2:** A1 red before the app-bar removal; A2 green; no `R.string.journal_save` left (`git grep -nw journal_save` prints nothing); the six click sites and their comments corrected and nothing else in those files; G1 §1.3 and the screen KDoc agree with the screen; O1 recorded, and `imePadding` present if and only if O1 required it.
- **T3:** `VersionAgreementTest` green with the new cases; the 1.8.0 case unedited; every 1.8.0 mention outside the release notes, the evidence history and `versioningRecords180` either names 1.8.1 or is deliberately historical (`git grep -nF 1.8.0` reviewed line by line).

### The whole-branch review asks

Is any path left where a form opened from a schedule writes through `LogEvent` (I1)? Does any existing caller of `CompletionCommand` now set `title` or `viaForm` (I7)? Is `detailsPending` reachable as true from the app (it must not be, `CompletionFlowTest`'s own claim at `:160-163`)? Did the entry form keep exactly one commit control in both modes (I11)? Did anything on the untouched list move?

## 6. Test matrix

"Fails without" names the mechanism on the 1.8.0 code or, for a pin, the wrong implementation it catches.

| ID | Layer · file | Case | Fails without the change because |
|---|---|---|---|
| C1 | JVM · core `ScheduleOperationsTest` | FORM schedule, all-optional profile, `CompletionCommand(viaForm = true)` with no values or materials → `detailsPending` false, `profileId` set | `minimal` alone decides: `detailsPending` true |
| C2 | JVM · core | `viaForm`, the profile's required reading empty → `EventValidation` with `Required(def)`; no event; the schedule's computed due unchanged | a minimal FORM completion is built against no profile, so nothing is required and the event is written |
| C3 | JVM · core | `title = "Blade swap"` → the event's title; `title = null` → the schedule's title (1.8.0 pinned) | the title is always `schedule.title` |
| V1 | JVM · `EventEntryViewModelTest` | FORM schedule (FIXED, quarterly, two periods overdue); the model opened with its id; required reading typed; `save()` → **one** event, `scheduleId` = it, `occurrenceOn` = the computed due read before the save, `detailsPending` false, `saved` emits its id; the schedule's computed due is now the first series date after max(occurrence, occurred-on) — one next occurrence, nothing replayed | `LogEvent` writes `scheduleId` null, `occurrenceOn` null; the due does not move |
| V2 | JVM · same | readings, a suggested material and a material linked to a supply item → the linked event carries all of them (names, quantities as `neutralNumber` reads them, units, `supplyId`) | unlinked event (V1's assertion); and catches a `toCompletion()` that drops either list |
| V3 | JVM · same | required reading left empty → the row marked, `saving` false, no event, due unchanged | with the call but without `viaForm`, `CompleteSchedule` writes a details-pending completion |
| V4 | JVM · same | all-optional profile, nothing typed → linked, `detailsPending` false | without `viaForm` the event is details-pending |
| V5 | JVM · same | pin: the same asset and profile, the model opened **without** an id (the asset screen's way in) → `scheduleId` null, due unchanged | catches an implementation that links by asset or profile |
| V6 | JVM · same | pin: edit a linked completion → same id, `scheduleId`/`occurrenceOn` kept, still one event, due unchanged, no offer asked | catches an edit routed through `CompleteSchedule` (a second completion) |
| V7 | JVM · same | the schedule archived after load, then `save()` → the could-not-save line, **zero** events; same for a deleted schedule | 1.8.0 writes an unlinked event; also catches a fallback to `LogEvent` |
| V8 | JVM · same | the typed title is stored; the kind is the profile's | without `title` the schedule's title replaces what the owner typed |
| V9 | JVM · same | pin: `RecordingOffers.asked == 1` after a schedule-opened save | catches offers skipped or asked twice on the new branch |
| V10 | JVM · same | pin: two `save()` calls in one frame → one event; the due advanced one occurrence | catches the guard lost on the new branch (a second call would complete the next occurrence) |
| V11 | JVM · same | constructing with `scheduleId` and `eventId`, or with `scheduleId` and `pending` → `IllegalArgumentException` | no such check exists |
| RT1 | JVM · `RouteTest` | `formEntry(NeedsForm(s, a, p))` equals `EventEntry(a, p, null, scheduleId = s)`; `p` null keeps `profileId` null and sets no `kind` | `formEntry` and the field do not exist; catches a mapping that drops the id |
| RT2 | JVM · same | 1.8.0's stored JSON (no `scheduleId`) decodes with null; a null is not encoded; a set one round-trips | catches a field added without a default (`MissingFieldException` on a restored stack) |
| RT3 | JVM · same (reads `ServiceTagRoot.kt`, the way `VersionAgreementTest` reads documents) | exactly three `onLogForm =` sites, each lambda calls `formEntry(` and builds no `Route.EventEntry(` itself | 1.8.0's three lambdas build `Route.EventEntry(assetId, profileId, null)` by hand. This is CI's only guard on the root wiring, since CI does not run the connected suite |
| N1 | Compose · `FormCompletionNavigationTest` | `Route.ScheduleDetail(s)` → **Log maintenance** → the form → the reading → **Record entry** → back on the detail: no OVERDUE, no "No service recorded yet", the completion listed; the store holds one event linked to `s` | the event is unlinked; the detail still says OVERDUE and "No service recorded yet" (the owner's report) |
| N2 | Compose · same | the notification's arrival, `Route.ScheduleDetail(s, complete = true)` → the form opens by itself → **Record entry** → linked | as N1 |
| N3 | Compose · same | `Route.MaintenanceSheet(asset)` → select the FORM row → **Complete selected** → the form → **Record entry** → back on the sheet, the row gone, one linked event | the row stays offered (`resumeQueue` sees it and stops the run); the event is unlinked |
| N4 | Compose · same | the Maintenance destination's **Log maintenance** → the FORM row in the picker → the form → **Record entry** → linked | as N1 |
| A1 | Compose · `EventEntryActionsTest` | a new entry and an edit: no node with the exact text "Save"; the close icon present; exactly one "Record entry" (new) / "Save entry" (edit) | the app bar carries **Save** (`EventEntryScreen.kt:161-165`) |
| A2 | Compose · same | pin: type into the last field (notes), then the bottom button `performScrollTo()` → displayed, enabled; tapping it stores one event and leaves | catches the button removed, disabled after focus, or placed outside the scroll column |
| O1 | emulator observation (T2) | the IME up, scrolled to the end: the button's bounds lie above the IME | no test layer sees the IME window; see T2 |

**Existing suites, unchanged and green:** core `ScheduleOperationsTest` (including `:681-700`), `EventUseCasesTest`, `CrossConceptWriteTest`; app `CompletionFlowTest` (its NeedsForm case `:165-192`), `MaintenanceSheetViewModelTest` (its simulated save `:815` is now the production path in all but `viaForm`), `ScheduleDetailViewModelTest`, `MaintenanceRoutesTest`, `QuickActionsTest`, `VersionAgreementTest` (T3 edits only the cases named there); androidTest `ScanSheetTest` and `ScheduleOperationsTest` (lambda shape only), `QuickActionDeviceProofTest`, and the six migrated Save clicks (the click only).

## 7. Strings

**No new string.** **One removed:** `journal_save` ("Save" and its nine translations), unused once the app-bar action goes. Reused as they are: `journal_record_entry`, `journal_save_entry`, the form's could-not-save and transferred-out lines (`EventEntryViewModel.kt:696-699`), the field-problem lines. The schedule-opened form keeps **Record entry**: the eyebrow already names the profile and the asset, and the schedule detail shows the completion on return, so no PENDING label is proposed. If the owner wants the button to say it completes the schedule, that is a new string to ratify and a later change, not 1.8.1's.

## 8. Release proofs (at the integrated tip, after the whole-branch review)

| Row | Command / act | Expectation |
|---|---|---|
| R1 | `./gradlew --rerun-tasks :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest` | green from scratch; includes R5 and `ReleaseProofPolicyTest` |
| R2 | stage the preserved set (runbook §"Before R2"), then `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` on the off-NVIDIA emulator configuration | the complete suite, **zero skips**, `FormCompletionNavigationTest` and `EventEntryActionsTest` among the classes |
| R3 | the runbook's Python line (`tools/servicetag-mcp`, `tools/servicetag-bundle`) | green; neither tool changed |
| R4 | inside R2: `ShareBoundaryTest`, `ContactGrantBoundaryTest` | five cases; nothing added |
| R5 | inside R1: `ManifestContractTest`, `MergedManifestContractTest` | green; the manifest is unchanged (unless Q1 rules otherwise) |
| R6 | the runbook greps over `servicetag-v1.8.0..HEAD`; `bash tools/check-commit-hygiene.sh origin/master HEAD`; `./gradlew :app:lintDebug` | no e-mail, home path or non-emulator serial in added lines; no `co-authored`, `generated-by`, tool or model name in any message; one author; empty bodies; the submodule pin ok; no Kotlin `assert(` in androidTest; lint 0 errors, nothing suppressed |
| R7 | the verified, published APK `adb install -r` over 1.8.0 on the **development phone** (serial in the environment only, never written) | UID and `firstInstallTime` unchanged, version code 22; no export requested (the latest export stands). The **production phone** only on the owner's word, the same checks plus the token-survival check (the saved Home Assistant connection and token still present) |

The tag `servicetag-v1.8.1` goes on the tip after R1–R6 and the review, by the owner's authorization; the release workflow's environment approval remains the owner's gate. Evidence (the tip, the runs, the APK checksum, the signer equal to `RELEASE_CERT_SHA256`, R7's lines) goes in `docs/architecture/product-split-evidence.md` under a 1.8.1 heading after 1.8.0's, by the controller.

## 9. Open questions for the owner

- **Q1 (only if O1 finds the button under the keyboard and `imePadding` reads no inset):** take `android:windowSoftInputMode="adjustResize"` on the main activity in 1.8.1 (app-wide: every screen with a field near its foot then needs its own inset handling, which no screen has today), or ship #117 alone in 1.8.1 and keep the app-bar Save until #118 can carry the inset work?
- **Q2:** the duplicate unlinked entries the bug wrote on 1.8.0 stay as ordinary journal entries; 1.8.1 neither links nor removes them (editing cannot add a link, `EventCommands.kt:287-289`). The plan says so in the notes' known limits and leaves the clean-up to the owner by hand. Agreed?
- **Q3 (pre-existing, not 1.8.1's):** a FORM schedule that also counts a meter reaches its form before the flow's reading check (`CompletionFlow.kt:196-197`), so the reading is collected only if the schedule's profile carries the meter's definition. File it as a follow-up?
