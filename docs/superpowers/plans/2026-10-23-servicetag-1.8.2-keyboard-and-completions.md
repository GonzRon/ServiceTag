# ServiceTag 1.8.2 — #121 the keyboard and the system bars, #119 a FORM schedule's meter reading, #123 the reminder sweep after a detail completion: plan (rev 1, 2026-10-23)

**Release:** 1.8.2 / `versionCode` 23, a **PATCH** by `docs/versioning.md` ("Bug fix or behaviour correction: something that was supposed to work already now does"). No Room schema or backup-format change: schema **21** / format **20**, as 1.8.1 (§3.6 changes a core validation helper's parameters, not a table, a column or a wire shape; the audit in §1.4 found nothing else). Scope by the owner's ruling of 2026-10-23: #121 + #119 + #123.
**Base:** master `16768cde` (1.8.1 / code 22 + its evidence). Every `file:line` below was read on that commit.
**Inputs:** the bodies and comments of #121, #119 and #123; `docs/superpowers/planning-policy.md`; `docs/release-proofs.md`; the 1.8.1 plan and its errata (§10); the 1.8.1 ledger's T2b/T2c entries, `T2c-report.md` and `t2b/` (bounds, frames, screenshots, the scripts `seed.py`, `ui.py`, `probe.py`, `observe.py`); the 1.8.2 ledger's measured scale; the class files of Material 3 1.4.0 and Compose UI 1.12.0 (the versions `composeBom = "2026.08.00"`, `gradle/libs.versions.toml:13`, resolves to), read for the inset and soft-input behaviour cited in §3.
**Branch:** `issue-121-keyboard-insets`, merged to master with a merge commit. The pull request description says it touches `app/src/androidTest` and every screen (the insets) plus the entry form, the schedule detail and the group detail. Commits follow the repository `CLAUDE.md` (owner identity, subject line only, no tool names); `bash tools/check-commit-hygiene.sh origin/master HEAD` before every push.
**Ledger:** `.superpowers/sdd/2026-10-23-servicetag-1.8.2/progress.md` (controller's, not committed). The observation sweep's scripts and evidence live under `.superpowers/sdd/2026-10-23-servicetag-1.8.2/sweep/`, never in the tree.

## 1. The defects, read on `16768cde`

### 1.1 #121 — the system bars counted twice, and the keyboard pans

- **Both activities draw edge to edge.** `MainActivity.kt:40` and `ShareIntakeActivity.kt:44` call `enableEdgeToEdge()`; the target SDK is 36 (`app/build.gradle.kts:44`), the minimum 26 (`:41`).
- **No soft-input mode.** `AndroidManifest.xml` declares neither `MainActivity` (`:77-96`) nor `ShareIntakeActivity` (`:123-…`) with `android:windowSoftInputMode`, so the platform picks pan for both. The trampoline `NfcDispatchActivity` (`:101-112`) draws nothing.
- **The root applies the bars without consuming them.** `ServiceTagRoot`'s outer `Scaffold` (`ServiceTagRoot.kt:125-132`) keeps Material 3's default content insets and pads `NavDisplay` with `Modifier.padding(padding)` (`:141-144`). Padding is not consumption, so every screen's own `Scaffold` (36 screen files) and every `TopAppBar` apply the same insets again. Measured on the gate emulator (Pixel 10 Pro XL profile, API 37, 1344×2992 at 480 dpi; status bar 159 px, navigation bar 72 px): the entry form's column starts at 510 = 2×159 + 192 (a 64 dp app bar) and ends at 2848 = 2992 − 2×72; in landscape the left display-cutout inset (159 px) is doubled as well (viewport left 318).
- **What Material 3 1.4.0 does with that.** `ScaffoldDefaults.contentWindowInsets` is the system bars united with the display cutout; `TopAppBarDefaults.windowInsets` is the same restricted to the top and the sides; `NavigationBarDefaults.windowInsets` to the bottom and the sides. None includes the keyboard. `Scaffold` subtracts what an ancestor has *consumed* (`onConsumedWindowInsetsChanged` → `contentWindowInsets.exclude(consumed)`), and `Modifier.windowInsetsPadding`, which the app bars use, does the same. An ancestor that only *pads* consumes nothing.
- **Pan plus padding collapses landscape.** 1.8.1's T2b found the entry form's **Record entry** fully under the keyboard after scroll-then-tap (portrait: keyboard top 1984, button [48,2026][1296,2170]; the same in landscape). T2c tried `consumeWindowInsets(padding).imePadding()` on that form alone: portrait passed (pan 0, the button 144 px clear after one scroll); landscape failed (the window still panned 255 px, and the column's bottom, 489, came above its top, 504, so the focused field was not drawn at all). It was reverted; the doubled bars took 159 + 72 px of the 561 px above a landscape keyboard.
- **The only screen that applies an inset itself** is the share intake's picker step: `ShareIntakeScreen.kt:234-237`, `Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)`. The same file's form step (`:150-156`, `fillMaxSize().verticalScroll(…).padding(top = 24.dp, bottom = 24.dp)`) and the Transfer Pack host (`ShareIntakeActivity.kt:104`, `Modifier.padding(top = 24.dp)`) apply no inset of any kind.
- **Comments that claim keyboard-open reachability**, all false until this release: the `EventEntryScreen` KDoc (`EventEntryScreen.kt:72-79`, rewritten in 1.8.1 to say it is reachable once the keyboard closes and that #121 makes it reachable while typing), G1 §1.3's Keyboard row (`docs/design/g1/01-g1-visual-gate-report.md:56`, the same wording), `DefinitionEditScreen.kt:66-69` ("Save in the app bar and again at the bottom so it survives an open keyboard"), `AssetEditScreen.kt:248-250` ("so it is reachable with the keyboard open").

### 1.2 #119 — a FORM schedule's meter reading

`CompletionFlow.complete` routes an asset-target FORM schedule to its form **before** it asks anything (`CompletionFlow.kt:196-197`); the meter demand lives only in `ask` (`:287-307`: the prompt carries the meter definition, `submit` refuses an empty reading, `:240-242`) and in `command` (`:363-377`: the reading goes into `values` under the schedule's own meter definition, so `currentMeter` and `lastCompletedMeter` advance from a real measurement). The form opened from a schedule (1.8.1, `EventEntryViewModel.kt:232-273`, save at `:575-578`, `toCompletion` at `:615-625`) draws only the profile's rows (`rows`, `:376-401`), so a meter its profile does not carry is never asked for, and `CompleteSchedule.run` (`CompleteSchedule.kt:63-126`) demands nothing beyond the profile's required fields. A profile-less form draws every unarchived definition of the asset as an optional row, so the meter is drawn there but may be left empty. 1.8.1's notes carry it as known limit (e).

### 1.3 #123 — no reminder sweep after a detail completion

The reminder is posted with `setAutoCancel(false)` (`reminders/Notifications.kt:90`); app start and resume do not sweep. The sweep is `AppGraph.reminderReconcile` (`di/AppGraph.kt:1184`, `reminderRuns.reconcileAll()`, serialized by one mutex, `reminders/ReminderReceivers.kt:237`), behind the one-method seam `ReminderReconcile` (`ui/maintenance/MaintenanceSheetViewModel.kt:179-181`). The scan sheet passes it as the flow's `afterWrite` (`MaintenanceSheetViewModel.kt:629`), the entry form runs it after a schedule-opened write (`EventEntryViewModel.kt:578`, `:631-642`), and the notification's one-tap action reconciles in a `finally` (`reminders/QuickActionReceiver.kt:89-104`). Nothing else does:

- `ScheduleDetailViewModel.runCompletion` (`ScheduleDetailViewModel.kt:450-456`) calls `completion.complete(scheduleId, assetId)` with no `afterWrite`; it serves **Complete** (`:433`) and the reminder's **Done** arrival (`arrive`, `:440-447`). `completeAll` (`:467-470`) and `completeSelected` (`:473-476`) call the flow's methods of the same names.
- `GroupDetailViewModel.completeAll` (`:206`), `completeSelected` (`:209-211`) and `completeMember` (`:222-224`) likewise.
- `CompletionFlow.completeSelected` (`:212-213`) and `completeAll` (`:227-232`) take no `afterWrite` at all and pass `{}`; `complete` takes one (`:183-186`) and `attempt` runs it once, after the write and before the offers (`:315-326`).
- **Not named in #123, same defect:** the Maintenance destination's **Log maintenance** picker completes a QUICK schedule with `flow.complete(item.scheduleId, asset)` (`CompletionFlow.kt:519`) and no `afterWrite`. See Q2.

### 1.4 The schema and format audit

Nothing in §3 adds, removes or renames a Room entity, column, index or migration, a backup or Transfer Pack field, a `/v1` route, field or code, or an MCP tool. §3.6 adds a defaulted parameter to `buildEvent` (an `internal` core function) and reads `MaintenanceSchedule.meterDefinitionId`, which every format since 1.2 carries. Schema 21 / format 20 stand.

## 2. Decisions

- **D1 (root).** The root **consumes what it applies**: `NavDisplay`'s modifier becomes `Modifier.padding(padding).consumeWindowInsets(padding)`; the outer `Scaffold` keeps Material 3's default content insets. One line; §3.1 justifies it against the alternatives.
- **D2 (windows).** `MainActivity` and `ShareIntakeActivity` declare `android:windowSoftInputMode="adjustResize"`. Under edge to edge this stops the pan and leaves the keyboard to the app as an inset; on API 26-29 it is also what makes the keyboard inset reach Compose at all.
- **D3 (screens).** A screen that takes typed input in its own content passes `contentWindowInsets = formContentInsets` to its `Scaffold`: Material 3's default content insets united with the keyboard. The screen's existing `Modifier.padding(padding)`, already outside its scroll container on every such screen, then ends the scroll viewport at the keyboard's top. One shared definition, one argument per screen, nothing inside the content changes.
- **D4 (sheets).** Bottom sheets keep Material 3 1.4.0's own handling and the app adds nothing (§3.3.4).
- **D5 (dialogs).** Dialogs keep Compose's defaults and the app adds nothing (§3.3.5).
- **D6 (share intake).** The picker keeps its `safeDrawing` padding; the form step and the Transfer Pack host gain the same `safeDrawing` padding, since that activity has no root `Scaffold` to apply anything (§3.4).
- **D7 (one exception to "insets only").** `WriteTagScreen`'s content column (`WriteTagScreen.kt:94-105`) holds a text field and does not scroll, so nothing can bring the field into view above a landscape keyboard; it gains a `verticalScroll`. Nothing else in that screen moves.
- **D8 (#119).** The form, not the flow, collects a FORM schedule's meter reading: a schedule-opened form draws one row for the schedule's meter and core demands it, through a set of definitions `buildEvent` treats as required in addition to the profile's. One reading, one measurement, on the linked completion.
- **D9 (#123).** The schedule detail and the group detail pass the graph's reminder sweep as the flow's `afterWrite`, wrapped so a failed sweep is logged and never fails the completion; `CompletionFlow.completeAll` and `completeSelected` gain the `afterWrite` parameter `complete` already has, defaulted to `{}`.
- **D10 (strings).** No string is added, removed or changed (§9).

**Rejected alternatives.**
- *Root, (B): the outer `Scaffold` takes `contentWindowInsets = WindowInsets(0)` and every screen applies its own bars.* `TagResultSheet` (`Route.TagResult`, `ServiceTagRoot.kt:440`) has no `Scaffold`, so it would draw under the status bar; every screen's `TopAppBar` would start painting its container under the status bar, a colour change on 36 screens that #121 does not ask for; and the bottom bar's height on a top-level route must still be consumed, so the consumption is needed anyway.
- *Root, (C): every screen's `Scaffold` and `TopAppBar` told `WindowInsets(0)`.* 36 `Scaffold`s and as many app bars, each a place to miss one; the doubling would return with the next screen written the ordinary way.
- *Root, padding inside `NavDisplay` per entry.* The same consumption, spread over 37 entries.
- *Screens, `imePadding()` on each scroll column after `consumeWindowInsets(padding)` (1.8.1 T2c's shape).* It works once the root is fixed, but it is three modifiers per screen in the middle of each content chain, it leaves each screen's snackbar and any pinned foot under the keyboard, and "no `imePadding` anywhere" is a simpler rule to pin than "exactly this chain". Rejected for D3.
- *Keep pan.* It is what 1.8.1 measured: tap order decides reachability, and in landscape the pan and any padding add up.
- *#119 in the flow: ask the meter prompt first, then open the form.* The prompt asks the date and time too, so the form would ask them a second time; a reading answered and then a form cancelled would leave the owner unsure whether anything was recorded; and the prompt's own write path is not the form's.
- *#119 by a view-model check only.* It would refuse the meter one problem at a time (before the domain sees the others) and leave the rule out of the core, where `/v1` and every other required field are enforced.
- *#123 by the screens instead of the view models.* The sweep must follow the write and precede the offers (the scan sheet's ruling R-2); only the flow's `afterWrite` sits there.

## 3. Contracts

### 3.1 (a) The root: each system bar once

**Contract.** Every route of `MainActivity` sees each system bar, and the display cutout, exactly once: the status bar at the top, the navigation bar at the bottom (or, on a top-level route, the bottom bar that already contains it), and the cutout on its side in landscape.

**Method: the root consumes what it pads.**

```kotlin
Scaffold(
    snackbarHost = { SnackbarHost(snackbarHost) },
    bottomBar = { /* unchanged */ },
    // contentWindowInsets: not passed. Material 3's default (system bars ∪ display cutout) stays.
) { padding ->
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        // #121: the bars (and the bottom bar on a top-level route) are applied here once and consumed,
        // so no screen's Scaffold or TopAppBar applies them again.
        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        entryDecorators = /* unchanged */,
        entryProvider = /* unchanged */,
    )
}
```

**Why this method.**
1. It is one line, and it covers every route, including `Route.TagResult`, whose `TagResultSheet` has no `Scaffold` (it gets the bars from the root and from nowhere else).
2. Material 3 1.4.0's `Scaffold` and `TopAppBar` subtract consumed insets (§1.1), so the 36 screen `Scaffold`s and their app bars stop re-applying the bars without being edited.
3. `consumeWindowInsets(PaddingValues)` consumes the bottom bar's height on a top-level route. A screen below it that adds the keyboard (D3) therefore gets the keyboard's height **minus** what the bottom bar already took, which is exactly the overlap.
4. The status bar strip keeps today's colour (the outer `Scaffold`'s container); only the duplicate band below it goes. Every alternative in §2 changes more than the double count.
5. The cutout is applied once, by the root, on the side it is on; the inner app bars' side insets become zero.

**Expected geometry on `emulator-5554`, portrait, keyboard down** (the sweep's P1, §8): a pushed screen's app bar starts at the status bar's bottom (159) and its content at 159 + 192 = 351; its content ends at 2992 − 72 = 2920. A top-level screen's content ends at the bottom bar's top. Landscape: the same with the landscape status-bar height, and the content's left edge at the cutout's right edge (159), not twice it.

**Not changed:** the outer `Scaffold`'s `snackbarHost` and `bottomBar`; `BottomBar` and `TopLevelRoutes`; `NavDisplay`'s decorators and entries; every `entry<…>` lambda.

### 3.2 (b) The two windows resize

**Contract.** `MainActivity` and `ShareIntakeActivity` each declare `android:windowSoftInputMode="adjustResize"`, exactly that value (no `state…` flag). `NfcDispatchActivity` declares none. The merged manifest carries the same.

**Why `adjustResize` and not `adjustNothing`.** Under edge to edge on API 30 and later, `adjustResize` stops the pan and does not resize the window: the keyboard reaches Compose as `WindowInsets.ime` and nothing else moves the content. On API 26-29 the keyboard inset reaches Compose only when the window is `adjustResize` (androidx core dispatches it as the bottom system-window inset of a window that does not fit system windows), so `adjustNothing` would leave those versions with no keyboard inset at all.

**Tests.**
- `ManifestContractTest` (`app/src/test/kotlin/com/loosecannon/servicetag/reminders/ManifestContractTest.kt`, class at `:24`) gains `bothDrawingActivitiesResizeForTheKeyboard`: in the source manifest, the two activities' `android:windowSoftInputMode` equals `adjustResize`; `NfcDispatchActivity` has no such attribute. It uses the file's own `elements` and `androidAttr` helpers (`:388-397`).
- `MergedManifestContractTest` (`:318`) gains `theMergedManifestKeepsAdjustResize`: the same two values in the merged manifest (the file the class already finds through `mergedManifestCandidates`, `:382-385`), so no library and no source set overrides them. The debug-only `DebugBackupActivity` (`app/src/debug/AndroidManifest.xml:9-16`) is not asserted and not changed.
- R5 in `docs/release-proofs.md:20` already names both classes; its text needs no edit.

### 3.3 (c) The keyboard rule, per kind

The inventory (§4) sorts every surface into one kind. The rule for each:

#### 3.3.1 Kind S — a `Scaffold` screen whose content takes typed input in a scroll column

- The screen's `Scaffold` passes `contentWindowInsets = formContentInsets`, defined once:

```kotlin
// app/src/main/kotlin/com/loosecannon/servicetag/ui/components/FormInsets.kt
/**
 * #121: the content insets of a screen that takes typed input — Material 3's default (the system bars and the
 * display cutout) and the keyboard. The root has consumed the bars, so on a screen this is the keyboard alone,
 * less what the root's bottom bar already covers.
 */
internal val formContentInsets: WindowInsets
    @Composable get() = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime)
```

- Nothing in the content changes. Every kind-S screen already applies `padding` outside its scroll container (`Column(Modifier.padding(padding).verticalScroll(…))`, §4), so the scroll viewport ends at the keyboard's top, Compose's scroll container keeps the focused field in view as the viewport shrinks, and the end of the column, with its commit control, is reached by scrolling while the keyboard is up.
- The screen's snackbar rides above the keyboard (the `Scaffold` places it on the same content insets). Intended: a refusal said while typing is seen.
- **Ends at the keyboard's top** means: keyboard up, the scroll viewport's bottom edge equals the keyboard's top edge (±2 px). **Brings the focused field into view** means: the focused field's top edge is inside the viewport, and when the field is no taller than the viewport, the whole field is.
- A field taller than the landscape viewport (the entry form's three-line Notes field is 336 px; the landscape viewport above a 561 px keyboard top is about 210 px) is shown from its top as far as the viewport allows. See RK4 and Q1.

#### 3.3.2 Kind L — a `Scaffold` screen with a search field over a list

`AssetsScreen` (`AssetsScreen.kt:109-163`): `Column(Modifier.padding(padding)) { SearchBox(…); …; LazyColumn { … } }`. The same `contentWindowInsets = formContentInsets`: the column, and so the list, ends at the keyboard's top; the search field stays at the top of the content. It is a top-level route, so the root has consumed the bottom bar and the keyboard's overlap is what remains (§3.1, point 3).

#### 3.3.3 Kind X — a `Scaffold` screen with a text field in a column that does not scroll

`WriteTagScreen` (`WriteTagScreen.kt:94-105`): `formContentInsets` as kind S, and the column gains `verticalScroll(rememberScrollState())` between `padding(padding)` and `padding(16.dp)` (D7), so its placement field can be brought into view above a landscape keyboard. There is no commit control on this screen (the tag tap writes); `fillMaxSize()` stays first.

#### 3.3.4 Bottom sheets — Material 3 1.4.0's own handling

Read from the 1.4.0 class files: `ModalBottomSheet` draws in its own dialog window that does not fit system windows; that window's soft-input mode is `adjustNothing` on API 30 and later (`adjustResize` below); the full-size box inside it applies `imePadding()`; and `BottomSheetDefaults.windowInsets` (the default `contentWindowInsets`) is `WindowInsets.safeDrawing` restricted to the top and the bottom. So the sheet lifts itself above the keyboard, and neither the activity's `adjustResize` nor the root's consumption reaches it (consumption does not cross into another window's composition).

The rule:
- no `ModalBottomSheet(` call passes `contentWindowInsets`;
- no code inside a sheet applies `imePadding()`, `windowInsetsPadding(…)` or `formContentInsets`;
- every sheet whose content takes typed input opens fully expanded (`rememberModalBottomSheetState(skipPartiallyExpanded = true)`), so its focused field is never in a part of the sheet that is off screen; all eight already do (§4, group E);
- every such sheet's content scrolls, so its commit control can be scrolled above the keyboard; all eight already do.

No sheet file changes. The rule is pinned by K4 and observed by the sweep (T7b).

#### 3.3.5 Dialogs — their own windows, Compose's defaults

Read from the Compose UI 1.12.0 class files: a `Dialog` (and every Material 3 dialog built on it: `AlertDialog`, `DatePickerDialog`) is its own window; with `DialogProperties()`'s default `decorFitsSystemWindows = true` its soft-input mode is left unspecified and its decor consumes the system insets, so the platform moves the dialog to keep its focused field above the keyboard and `WindowInsets.ime` inside the dialog is zero.

The rule:
- no `DialogProperties(` in `app/src/main` passes `decorFitsSystemWindows = false`;
- no code inside a dialog applies `imePadding()` or `formContentInsets` (each would be a no-op there, and a trap if the default ever changed);
- **pass in portrait:** the focused field and the dialog's confirm button above the keyboard's top; **pass in landscape:** the focused field above the keyboard's top, and the confirm button's position recorded. A confirm button under a landscape keyboard is reported, not fixed in 1.8.2 (Q1); a portrait failure stops the sweep and goes to the owner.

No dialog file changes. Pinned by K5; observed by T7b.

### 3.4 (d) The share intake

`ShareIntakeActivity` has no root `Scaffold`; each of its three surfaces applies its own insets, once:

| Surface | Today | 1.8.2 |
|---|---|---|
| The picker step (`ShareIntakeScreen.kt:234-237`) | `fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)`; `safeDrawing` includes the keyboard, so the search field and the lists end at the keyboard's top, but the window also pans | unchanged code; `adjustResize` (§3.2) removes the pan, so the padding is the only lift |
| The form step (`:150-156`) | `fillMaxSize().verticalScroll(…).padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 24.dp)`: no inset at all | `fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(…)` then the existing paddings: the title below the status bar, the column ending at the keyboard's top, **Save** reached by scrolling |
| The Transfer Pack host (`ShareIntakeActivity.kt:104`) | `Modifier.padding(top = 24.dp)`: no inset | `Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 24.dp)`; the import screen takes no typed input, so this is the bars only |

**Read first, then change (T6's first step).** The form step and the pack host are read on the code as drawing under the status bar today (24 dp = 72 px against a 159 px status bar). T6 records the form step's title bounds on the 1.8.1 build before it edits; if the title is already below the status bar, something else applies the inset and T6 stops and reports instead of adding a second one.

### 3.5 (e) Nothing but insets; the comments that become true

- **No behaviour change beyond insets** except D7 (one scroll container). No string is added, removed or reworded (§9); no control is added, removed, moved between bars or re-enabled; no `TopAppBar` gains a scroll behaviour; no screen's content chain changes except `WriteTagScreen`'s column and the share intake's two modifiers.
- **The visible change on every screen.** Pushed screens' content moves up by one status-bar height and extends down by one navigation-bar height; top-level screens' content moves up by one status-bar height; in landscape the content moves left by one cutout width. This is the fix, not a side effect; the release notes say so (T8).
- **The comments, rewritten by the task that makes them true** (T4):

| Site | Today | Rewritten to say |
|---|---|---|
| `EventEntryScreen.kt:72-79` (KDoc) | reachable once the keyboard closes; #121 (1.8.2) makes it reachable while typing | the commit button ends the scrolling column; with the keyboard up the column ends at the keyboard's top, so the button is reached by scrolling (#121) |
| `docs/design/g1/01-g1-visual-gate-report.md:56` (Keyboard) | the same | the same, in the report's voice |
| `DefinitionEditScreen.kt:66-69` (KDoc) | Save in the app bar and again at the bottom "so it survives an open keyboard (G1 §1.3)" | Save in the app bar and again at the bottom; with the keyboard up the column ends at the keyboard's top, so the bottom Save is reached by scrolling (#121) |
| `AssetEditScreen.kt:248-250` (KDoc) | "so it is reachable with the keyboard open (G1 §1.3)" | the same correction |

`docs/design/phase-2a-evidence.md` and the 1.8.1 plan are history and stay. Any other comment found by T4's grep (`git grep -nE -i 'keyboard (open|is open|up)|open keyboard|survives an open' -- app/src/main docs/design`) is listed in its report and corrected only if it describes a screen this release changes.

### 3.6 #119 — the form collects the meter reading

**Contract.** A form opened for a schedule (a route with `scheduleId`, 1.8.1) whose schedule counts a meter collects that reading exactly as the quick prompt does: one reading, demanded before anything is written, recorded once, as a measurement on the linked completion under the schedule's own meter definition. When the profile already carries that definition there is still one row and one measurement.

**Core (`core/src/main/kotlin/com/loosecannon/servicetag/core/usecase/`).**
- `buildEvent` (`EventCommands.kt:181-190`) gains a trailing parameter `alsoRequired: Set<DefinitionId> = emptySet()`. A profile field is considered with `field.required || field.definitionId in alsoRequired` (`:240`); then each id of `alsoRequired` not yet handled is considered as required, before the extra values (`:241-244`). The existing rules hold unchanged: a definition that does not resolve and is required is `Required`; an archived one is never demanded; a DERIVED one is never a field (a meter cannot be DERIVED: `DerivedProblem.IsMeter`, `core/journal/Derived.kt:13`). Every other caller (`LogEvent.kt:39`, `UpdateEvent.kt:47`, `RecordConditionWithIncident.kt:89`, `CompleteGroupMembers.kt:145`) passes nothing and is unchanged.
- `CompleteSchedule.run` passes `alsoRequired = setOfNotNull(meter)` where `meter = schedule.meterDefinitionId` **only when** `cmd.viaForm` is true **and** `definitions.get(meter)` resolves. A meter rule whose definition has gone demands nothing, as `CompletionFlow.ask` (`:296-300`) decides. Under `viaForm` the completion is never details-pending (1.8.1), so the profile is always passed with it.
- The class KDoc's third bullet gains: a completion collected by the form also owes the schedule's meter reading.

**App (`ui/journal/EventEntryViewModel.kt`).**
- **Load** (`:310-357`): with a `scheduleId`, the schedule is read once (already read for a profile-less form's title, `:319`; now read in every schedule-opened case) and its `meterDefinitionId` kept.
- **Rows** (`rows`, `:376-401`, or a step right after it): when the schedule has a meter whose definition resolves, is not archived and is not DERIVED: if a row for it exists (the profile carries it, or a profile-less form drew it among the asset's definitions), that row's `required` becomes true; otherwise one `FieldRow(definition, required = true, text = "", problem = null)` is **appended after the profile's rows**, the position `buildEvent` gives an extra measurement. Never two rows for one definition.
- **Save:** unchanged. `values` are built from the rows (`:558-562`), so the reading travels under the meter's id once; a blank meter is refused by core as `EventValidation(Required(meter))`, caught at `:592-593`, marked on the meter's row by `markProblems` (`:756-771`) with the shipped "%s is required" line (`journal_field_required`, `:837`), and nothing is written.
- **Untouched:** a route without `scheduleId`, an edit, the condition flow, the offers, the reconcile after the write (1.8.1 D8), the model key.

**Edge cases.**

| Case | The form draws | On Record entry with the meter blank | Typed |
|---|---|---|---|
| FORM + meter, profile without the meter | the profile's rows, then one meter row, required | the meter row marked, "<label> is required", nothing written, the due unchanged | one linked completion, the reading among its measurements once |
| FORM + meter, profile with the meter optional | the profile's rows; the meter row now required | as above | as above |
| FORM + meter, profile with the meter required | unchanged | as today (already required) | as today |
| FORM + meter, profile-less | every unarchived asset definition, the meter's row required | as above | as above |
| the meter definition deleted | no meter row | nothing demanded | as 1.8.1 |
| the meter definition archived | no meter row (an archived definition is off every form) | nothing demanded | as 1.8.1 (the quick prompt still asks for an archived meter; stated in RK11) |
| FORM, no meter | unchanged | unchanged | unchanged |
| QUICK + meter | not this form (the quick prompt) | — | unchanged |
| the meter rule added to the schedule after the form opened | the rows as loaded (no meter row) | core demands it (`Required` on an undrawn row): the could-not-save fallback line, nothing written (1.8.1 §3.6's undrawn-row case) | — |
| an API or one-tap completion | — | unchanged: `viaForm` false demands nothing new | — |

**`CompletionFlow`:** no code change for #119. Its KDoc at `complete` (`:192-195`) gains one clause: the form also collects the schedule's meter reading (#119).

### 3.7 #123 — one sweep after every detail completion

**Contract.** A completion written from the schedule detail (**Complete**; the reminder's **Done** arrival that asks for a meter reading; **Complete all**; **Complete selected**) or from the group detail (**Complete all**, **Complete selected**, a member's completion) runs the graph's reminder sweep **exactly once, after the write and before any offer**. A completion that writes nothing (cancelled, refused, held, or handed to the form) runs none from that path; the form sweeps its own write (1.8.1 D8). A sweep that fails is logged and never undoes, hides or re-reports the write.

**`CompletionFlow`.**

```kotlin
/** "Complete selected": the named members of a group round, in one write. */
suspend fun completeSelected(
    scheduleId: ScheduleId, assetIds: List<AssetId>, afterWrite: suspend () -> Unit = {},
): CompletionOutcome = completeMembers(scheduleId, assetIds, afterWrite)

/** "Complete all": every required member of a group round that is not yet done. */
suspend fun completeAll(scheduleId: ScheduleId, afterWrite: suspend () -> Unit = {}): CompletionOutcome
// body: as today, with attempt(afterWrite) in place of attempt(afterWrite = {})
```

`complete`, `attempt`, `ask`, `offerAfter` and `command` are unchanged; `attempt` already runs `afterWrite` once after a successful block and never after a refusal.

**The sweep that cannot fail a write**, beside `ReminderReconcile` in `MaintenanceSheetViewModel.kt`:

```kotlin
/** #123: the sweep after a completion's write. The write has committed; a failed sweep is the backstop's to repeat. */
suspend fun ReminderReconcile.runAfterWrite(tag: String) {
    try {
        run()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Exception) {
        Log.w(tag, "the reminder sweep after a completion failed", failed)
    }
}
```

**The view models.**
- `ScheduleDetailViewModel` and `GroupDetailViewModel` each gain a trailing constructor parameter `reconcile: ReminderReconcile = ReminderReconcile {}`; each graph constructor (`ScheduleDetailViewModel.kt:283-289`, `GroupDetailViewModel.kt:143-154`) passes `reconcile = graph.reminderReconcile` on its own line.
- `ScheduleDetailViewModel.runCompletion` (`:452`): `completion.complete(scheduleId, assetId) { reconcile.runAfterWrite(TAG) }`. This one change serves **Complete** and the **Done** arrival. `completeAll` (`:469`) and `completeSelected` (`:475`) pass the same lambda.
- `GroupDetailViewModel.completeAll` (`:206`), `completeSelected` (`:210`) and `completeMember` (`:223`) pass the same lambda.
- **Not changed:** `operate` and `operateGuarded` (they reload after the run either way), `arrive`'s once-per-model guard and `arrivalEnded`, `refusedAsHeld`, `sayIfHeld`, the scan sheet's own `afterWrite` (`MaintenanceSheetViewModel.kt:629`) and its resume sweep (`:679`), `QuickActionReceiver`, the entry form's `reconcileAfterWrite`.
- **If the owner says yes to Q2** (the plan's default): `LogMaintenancePicker` (`CompletionFlow.kt:467-532`) gains a `reconcile: ReminderReconcile` parameter, its QUICK branch becomes `flow.complete(item.scheduleId, asset) { reconcile.runAfterWrite(TAG) }` (`:519`), and the root passes `reconcile = graph.reminderReconcile` (`ServiceTagRoot.kt:183-…`). If the owner says no, these three lines are left out and the controller files the picker as its own issue.

**Idempotence and order.** `reconcileAll` is idempotent and serialized by its mutex (`ReminderReceivers.kt:237`), so a sweep from the detail racing the backstop or the digest is harmless; on the scan sheet's FORM path the form's sweep and the sheet's resume sweep both still run, as in 1.8.1.

## 4. Inventory (#121)

Every file under `app/src/main` with a `Scaffold(` (37), a typed input (27, counting the shared input components' own files) or a `ModalBottomSheet(` (11), sorted into one group. "Applies insets today" is what the code does on `16768cde`; "needs" is the rule of §3 that applies; the task is §6's. Paths are under `app/src/main/kotlin/com/loosecannon/servicetag/`. An `ExposedDropdownMenuBox` anchor (`readOnly = true`) opens no keyboard and is not a typed input.

**Group sizes:** A root and windows: 1 root file + 2 activity declarations · B input screens: **15** (13 S, 1 L, 1 X) · C screens with no typed input of their own: **22** (21 `Scaffold` files + `TagResultSheet`) · D dialogs with typed input: **11** (in 9 files) · E bottom sheets with typed input: **10 sheets in 8 files** (+ 3 sheet files with no input) · F share intake: **3** surfaces. Check: B 15 + C 21 + the root = 37 `Scaffold` files; E 8 + 3 = 11 sheet files.

### 4.1 Group A — the root and the windows

| File | Today | Needs | Task |
|---|---|---|---|
| `ui/nav/ServiceTagRoot.kt:125-144` | outer `Scaffold`, M3 default insets; `NavDisplay(modifier = Modifier.padding(padding))`, nothing consumed | §3.1: `.consumeWindowInsets(padding)` | T3 |
| `AndroidManifest.xml` `MainActivity` (`:77-96`) | no `windowSoftInputMode` (pan) | `adjustResize` (§3.2) | T3 |
| `AndroidManifest.xml` `ShareIntakeActivity` (`:123-…`) | no `windowSoftInputMode` (pan) | `adjustResize` (§3.2) | T3 |
| `AndroidManifest.xml` `NfcDispatchActivity` (`:101-112`) | translucent trampoline, draws nothing | nothing; pinned as having no mode | T3 |
| `MainActivity.kt:40`, `ShareIntakeActivity.kt:44` | `enableEdgeToEdge()` | unchanged | — |
| `ui/components/FormInsets.kt` (new) | — | `formContentInsets` (§3.3.1) | T3 |
| `app/src/debug/AndroidManifest.xml` `DebugBackupActivity` | debug source set only | out of scope; not in a release build | — |

### 4.2 Group B — `Scaffold` screens that take typed input in their own content

"`padding` at" is where the screen's `Scaffold` padding is applied; in every row it is outside the scroll container, which is what makes D3 a one-argument change.

| # | File · route | Typed inputs | `padding` at | Commit control | Kind | Task |
|---|---|---|---|---|---|---|
| B1 | `ui/journal/EventEntryScreen.kt` · `EventEntry` | Entry (profile-less), date and time (`:286-329`), one `InstrumentEntryRow` per field, materials (`:330-408`), Notes (`:237`) | `:178`, `Column(… .padding(padding).verticalScroll(…))` | bottom **Record entry** / **Save entry** (`:245`) | S | T4 |
| B2 | `ui/asset/AssetEditScreen.kt` · `AssetEdit` | `FormField`s, `DateField`s (category and choice anchors read-only) | `:360` | app-bar Save (`:350`) and bottom Save (`:398`) | S | T4 |
| B3 | `ui/setup/DefinitionEditScreen.kt` · `DefinitionEdit` | six text fields (`:171-302`), source picker (`:429`) | `:166` | app-bar Save (`:126`) and bottom Save (`:331`) | S | T4 |
| B4 | `ui/setup/ProfileEditScreen.kt` · `ProfileEdit` | name and title (`:209`, `:221`), consumable rows (`:406-426`) | `:203` | app-bar Save (`:151`) and bottom Save (`:286`) | S | T4 |
| B5 | `ui/supplies/SupplyEditScreen.kt` · `SupplyEdit` | `Field`s (`:129`), specification rows (`:157-184`) | `:87` | app-bar Save (`:78`) | S | T4 |
| B6 | `ui/health/HealthSubjectEditScreen.kt` · `HealthSubjectEdit` | text field (`:246`), thresholds (`:321`) | `:240` | app-bar Save (`:211`); buttons (`:289`, `:296`) | S | T4 |
| B7 | `ui/maintenance/GroupEditScreen.kt` · `GroupEdit` | two text fields (`:95`, `:103`) | `:90` | app-bar Save (`:75`) | S | T5 |
| B8 | `ui/maintenance/ScheduleEditScreen.kt` · `ScheduleEdit` | `MaintenanceField`s (`:590`), `DateField`s (unit, meter, profile anchors read-only) | `:284` | app-bar Save (`:249`) | S | T5 |
| B9 | `ui/service/ServiceCaseEditScreen.kt` · `ServiceCaseEdit` | `FormField`s and `DateField`s (`:93-180`) | `:89` | bottom button (`:152`) | S | T5 |
| B10 | `ui/loan/LoanEditScreen.kt` · `LoanEdit` | `DateField`s (`:130`, `:136`), `FormField` (`:155`) | `:112` | buttons (`:125`, `:161`) | S | T5 |
| B11 | `ui/replace/ReplaceAssetScreen.kt` · `ReplaceAsset` | `FormField`s and `DateField`s (`:140-171`) | `:112` (`val modifier = Modifier.padding(padding)`), scroll at `:133` | the screen's own button | S | T5 |
| B12 | `ui/transfer/TransferFlowScreen.kt` · `TransferAssets` | review text field (`:237`) | `:117` (`val modifier`), applied before the scroll at `:232` | button (`:252`) | S | T5 |
| B13 | `ui/homeassistant/HomeAssistantScreen.kt` · `HomeAssistant` | address and token (`:110`, `:119`) | `:104` | buttons (`:147-167`) | S | T5 |
| B14 | `ui/asset/AssetsScreen.kt` · `Assets` (top level) | `SearchBox` (`ui/asset/AssetSearch.kt:61`) | `:122`, `Column(Modifier.padding(padding))` over a `LazyColumn` | none | L | T5 |
| B15 | `ui/scan/WriteTagScreen.kt` · `WriteTag` | placement field (`:160`) | `:95`, `fillMaxSize().padding(padding).padding(16.dp)`, **no scroll** | none (the tag tap writes) | X | T5 |

Every B screen has a `TopAppBar` (M3 default insets) and no inset of its own; today each re-applies the status bar (app bar) and the navigation bar (its `Scaffold`), and pans with the keyboard.

### 4.3 Group C — `Scaffold` screens with no typed input of their own

Needs: §3.1 only (the root fix), nothing in the file. Their typed input, where any, is in a dialog (group D) or a sheet (group E), which are their own windows.

| # | File · route | Typed input reached from it |
|---|---|---|
| C1 | `ui/dashboard/DashboardScreen.kt` · `Dashboard` (top level) | none (filter anchors read-only, `DashboardFilters.kt:223`) |
| C2 | `ui/maintenance/MaintenanceScreen.kt` · `Maintenance` (top level) | D8 through the Log maintenance picker |
| C3 | `ui/asset/AssetDetailScreen.kt` · `AssetDetail` | D1, D2, D3, D4, D9; E1–E7 |
| C4 | `ui/setup/AssetSetupScreen.kt` · `AssetSetup` | none |
| C5 | `ui/backup/BackupScreen.kt` · `Backup` | D5 |
| C6 | `ui/settings/CategoriesScreen.kt` · `Categories` | D6 |
| C7 | `ui/api/DeveloperApiScreen.kt` · `DeveloperApi` | none |
| C8 | `ui/journal/EventDetailScreen.kt` · `EventDetail` | none (its readings are read-only rows) |
| C9 | `ui/maintenance/GroupDetailScreen.kt` · `GroupDetail` | D8 |
| C10 | `ui/installed/InstalledComponentDetailScreen.kt` · `InstalledComponentDetail` | E6 (references) |
| C11 | `ui/installed/InstalledComponentsListScreen.kt` · `InstalledComponents` | none |
| C12 | `ui/maintenance/MaintenanceGroupsScreen.kt` · `MaintenanceGroups` | none |
| C13 | `ui/maintenance/MaintenanceSheet.kt` · `MaintenanceSheet` | D8, D7; E3 |
| C14 | `ui/maintenance/ReminderHealthScreen.kt` · `ReminderHealth` | none |
| C15 | `ui/maintenance/ScheduleDetailScreen.kt` · `ScheduleDetail` | D7, D8, D11 |
| C16 | `ui/scan/ScanScreen.kt` · `Scan` | none |
| C17 | `ui/service/ServiceCaseScreen.kt` · `ServiceCase` | E8 |
| C18 | `ui/settings/SettingsScreen.kt` · `Settings` | none |
| C19 | `ui/supplies/SupplyDetailScreen.kt` · `SupplyDetail` | E6 (references) |
| C20 | `ui/supplies/SupplyListScreen.kt` · `Supplies` | none |
| C21 | `ui/transfer/import/TransferImportScreen.kt` · `TransferImport` | none |
| C22 | `ui/scan/TagResultSheet.kt` · `TagResult` (no `Scaffold`; `SheetHost`, `:283-298`) | none (its bind picker is a sheet with no input) |

### 4.4 Group D — dialogs with typed input (own windows, §3.3.5)

| # | Dialog · site | Typed inputs | Opened from | Sweep |
|---|---|---|---|---|
| D1 | `TypedConfirmDialog` · `ui/components/TypedConfirmDialog.kt:31` | one field (the typed word) | Asset detail › delete (`AssetDetailScreen.kt:799`) | T7c |
| D2 | `TagPlacementDialog` · `ui/asset/AssetDetailScreen.kt:1545` | placement | Asset detail › tag | T7c |
| D3 | `RetireDialog` · `AssetDetailScreen.kt:842` | `DateField` | Asset detail › retire | T7c |
| D4 | `SeasonDialog` · `AssetDetailScreen.kt:1402` | `DateField` | Asset detail › season | T7c |
| D5 | Backup's typed REPLACE · `ui/backup/BackupScreen.kt:272-278` | one field | Backup › restore over existing rows | not driven: it needs a document picked through the system picker; its shape is D1's (one `AlertDialog`, one field) and D1 stands for it |
| D6 | `RenameDialog` · `ui/settings/CategoriesScreen.kt:255` | name | Categories | T7c |
| D7 | `PostponeDialog` · `ui/maintenance/ScheduleDetailScreen.kt:399` | `DateField` | Schedule detail, scan sheet | T7c |
| D8 | "When was this done?" · `CompletionFlowHost`, `ui/maintenance/CompletionFlow.kt:389` | `DateField`, time, the meter reading (`:405-430`) | Schedule detail, group detail, scan sheet, Maintenance | T7c |
| D9 | `ReturnDialog` · `ui/loan/LendingSection.kt:151` | `DateField` | Asset detail › lending | T7c |
| D10 | `CalendarDialog` · `ui/asset/AssetEditScreen.kt:1046` (`DatePickerDialog` + `DatePicker`, whose mode toggle has a text input) | the picker's input mode | every `DateField`'s calendar button | T7c (input mode) |
| D11 | `CloseRoundDialog` · `ui/maintenance/CloseRoundDialog.kt:74` (`DatePickerDialog`, `:134`) | the picker's input mode | Schedule detail (group round) | T7c |

Dialogs with no typed input (confirmations, pickers, offers: `Offers.kt`, `MarkOperationalDialog.kt`, `LocationAsks.kt`, `LogMaintenancePicker`, `LinkGuardDialog`, `FieldPicker`, `RepairPicker`, `ReferencedDialog`, `ConfirmDialog`, `RemoveReferenceDialog`, `UnknownSchemeDialog`, `LockWarning`, the share intake's confirmation) open no keyboard and are out of the sweep.

### 4.5 Group E — bottom sheets with typed input (own windows, §3.3.4)

| # | Sheet · site | Typed inputs | Fully expanded at | Scrolls at | Hosted by | Sweep |
|---|---|---|---|---|---|---|
| E1 | `LinkSeasonSyncSheet` · `ui/asset/LinkSeasonSyncSheet.kt:97` | entity ID (`:146`); Choose entity's search (`:231`) over a `LazyColumn` (`:247`) | `:99-100` | `:140`, `:226` | C3 | T7c |
| E2 | `AttachmentEditSheet` · `ui/attachments/AttachmentEditSheet.kt:76` | name, description (`:87`, `:129`) | `:78` | `:83` | C3 (documents) | T7c |
| E3 | `ChangeConditionSheet` · `ui/condition/ChangeConditionSheet.kt:113` | reason (`:188`), date | `:115` | `:178` | C3, C13 | T7c |
| E4 | `InstalledComponentSheets` · `ui/installed/InstalledComponentSheets.kt:89`, `:155`, `:256` (three sheets) | composition rows (`:363`, `:374`), `FormField`s, `DateField`s | `:91`, `:157`, `:258` | `:96`, `:162`, `:263` | C3 (installed components) | T7c (the form sheet, `:155`) |
| E5 | `MaterializeSheet` · `ui/references/MaterializeSheet.kt:111` | name, description (`:156`, `:169`) | `:113-114` | `:121` | C3, C10, C19 | T7c |
| E6 | `ReferenceEditSheet`, `AddLinkSheet` · `ui/references/ReferenceSheets.kt:66`, `:108` | URL (`:114`), name, description (`:236`, `:247`) | `:68`, `:110` | `SheetColumn` (`:203`) | C3, C10, C19 | T7c (Add link) |
| E7 | `AssetSupplyRoleSheet` · `ui/supplies/AssetSuppliesSection.kt:198` | role (`:211`) | `:200` | `:205` | C3 | T7c |
| E8 | the update sheet · `ui/service/ServiceCaseScreen.kt:265` | note (`:291`), `FormField`s, `DateField`s | `:267` | `:272` | C17 | T7c |

Sheets with no typed input: `BindTargetPicker` (`ui/scan/TagResultSheet.kt:403`), `OverwriteSheet` (`ui/scan/WriteTagScreen.kt:247`), `SupplyItemPickerSheet` (`ui/supplies/SupplyItemPicker.kt:62`). None passes `contentWindowInsets`; none needs a change.

### 4.6 Group F — the share intake (`ShareIntakeActivity`)

| # | Surface · site | Typed inputs | Today | Needs | Task |
|---|---|---|---|---|---|
| F1 | the picker · `share/ShareIntakeScreen.kt:217-…` (`:234-237`) | `SearchBox` in a `LazyColumn` (`:496-505`) | `safeDrawing` padding (bars and keyboard), plus the pan | unchanged code; the pan goes (§3.2) | T6 (observed T7b) |
| F2 | the form · `:150-156` → `IntakeForm` (`:565-654`) | name, description (`:588`, `:595`); **Save** (`:647`) | no inset at all | `safeDrawing` padding ahead of the scroll (§3.4) | T6 |
| F3 | the Transfer Pack host · `ShareIntakeActivity.kt:104` | none | `padding(top = 24.dp)`, no inset | `safeDrawing` padding ahead of it (§3.4) | T6 |

### 4.7 Shared input components (not surfaces; listed so the closure in K2 is explainable)

`SearchBox` (`ui/asset/AssetSearch.kt:54-89`: B14, F1, `AssetPicker`), `InstrumentEntryRow` (`ui/components/InstrumentRow.kt:166-270`: B1), `FormField` and `DateField` (`ui/asset/AssetEditScreen.kt:838`, `:965`: B2, B9–B11, D3, D4, D7–D9, E3, E4, E8), `MaintenanceField` (`ui/maintenance/ScheduleEditScreen.kt:573`: B8, D8), `PlacementField` (`ui/scan/WriteTagScreen.kt:159`: B15). `DashboardFilters.kt`'s anchors and the read-only pickers (`CategoryField`, `ChoiceField`, `UnitPicker`, `MeterPicker`, `ProfilePicker`, `KindPicker`, `SourcePicker`) open no keyboard.

## 5. Invariants

| # | Invariant |
|---|---|
| I1 | Every route of `MainActivity` sees the status bar, the navigation bar (or the bottom bar containing it) and the display cutout exactly once (§3.1). |
| I2 | The outer `Scaffold` keeps Material 3's default content insets, and the root consumes exactly the padding it applies; no screen re-applies a bar. |
| I3 | `MainActivity` and `ShareIntakeActivity` declare `adjustResize`, exactly; `NfcDispatchActivity` declares no mode; the merged manifest agrees. |
| I4 | With the keyboard up, the app's window does not pan (`android:id/content` keeps the full screen height). |
| I5 | On every group B screen and on F1/F2, with the keyboard up, the scroll viewport (or list) ends at the keyboard's top, the focused field is in view, and every commit control is reachable by scrolling without closing the keyboard. |
| I6 | Bottom sheets keep Material 3 1.4.0's handling: no `contentWindowInsets` argument, no app IME padding inside, every input sheet fully expanded and scrolling. |
| I7 | Dialogs keep Compose's defaults: no `decorFitsSystemWindows = false`, no app IME padding inside; in portrait the focused field and the confirm button sit above the keyboard. |
| I8 | No `imePadding(` anywhere in `app/src/main`; `formContentInsets` is used only by the 15 group B screens; every file with a `Scaffold(` is classified as group B, group C or the root. |
| I9 | No string is added, removed or changed; no behaviour changes beyond insets except `WriteTagScreen`'s column scrolling (D7). |
| I10 | (#119) A form opened for a schedule that counts a meter draws exactly one row for the meter's definition, and it is required; a blank meter is refused with nothing written; a typed one is stored once, on the linked completion, under the schedule's meter definition. |
| I11 | (#119) Only `CompleteSchedule` under `viaForm` passes a non-empty `alsoRequired`; `/v1`, the MCP, the quick prompt, the one-tap action, `LogEvent`, `UpdateEvent` and every route without a `scheduleId` behave as in 1.8.1. |
| I12 | (#123) Every completion written from the schedule detail (Complete, the Done arrival, Complete all, Complete selected) and the group detail (Complete all, Complete selected, a member) runs exactly one reminder sweep, after the write and before the first offer; a path that writes nothing runs none. |
| I13 | (#123) A sweep that throws leaves the completion written and the outcome `Completed`; the screen re-derives; cancellation still propagates. |
| I14 | (#123) `CompletionFlow`'s other callers are unchanged: the scan sheet's `afterWrite` and resume sweep, `QuickActionReceiver`, the entry form's own sweep. |
| I15 | Room schema 21, backup format 20, `/v1` and the MCP's 90 tools unchanged. |

**Untouched:** every group C, D and E file except as named in §3.7 (the two detail view models; `CompletionFlow.kt`); `MainActivity.kt`, `ShareIntakeActivity.kt` but `:104`; `BottomBar`, `TopLevelRoutes`, `Route.kt`; every `TopAppBar` call; every string file; `MaintenanceDtos.kt` and every API/MCP file; `LogEvent.kt`, `UpdateEvent.kt`, `RecordConditionWithIncident.kt`, `CompleteGroupMembers.kt`; `reminders/`; `docs/design/phase-2a-evidence.md`; the 1.8.1 plan and release notes.

## 6. Tasks

Order **T1 → T2a → T2b → T3 → T4 → T5 → T6 → T7a → T7b → T7c → T8**: one lane, one implementer and one task review per task (§review budget), then one whole-branch review at the tip. No box over 60 minutes; a task not green at 60 stops and is inventoried (fix rounds 45 min). Every connected run on `emulator-5554` runs `tools/emulator/prepare-emulator.sh` first and starts the emulator off NVIDIA as the runbook says. Nothing is left under `tools/`, `app/src/androidTest`, `share-test-sender/` or `.github/` that names `dumpsys`, `uiautomator`, `input tap` or `input text` (`ReleaseProofPolicyTest`, which scans untracked files too); the sweep's scripts live in the ledger.

**Between T3 and T5 the branch is mid-change:** the pan is gone and not every input screen has its keyboard inset yet. Nothing is cut, installed on a phone or merged from a commit before T5.

| File | T1 | T2a | T2b | T3 | T4 | T5 | T6 | T7 | T8 |
|---|---|---|---|---|---|---|---|---|---|
| `ui/maintenance/CompletionFlow.kt` | ✓ two signatures, KDoc (+ the picker if Q2) | | ✓ KDoc clause | | | | | | |
| `ui/maintenance/MaintenanceSheetViewModel.kt` (the `runAfterWrite` helper only) | ✓ | | | | | | | | |
| `ui/maintenance/ScheduleDetailViewModel.kt`, `GroupDetailViewModel.kt` | ✓ | | | | | | | | |
| test `CompletionFlowTest.kt`, `ScheduleDetailViewModelTest.kt`, `GroupDetailViewModelTest.kt`, `ReminderSweepWiringTest.kt` (new) | ✓ | | | | | | | | |
| core `EventCommands.kt` (`buildEvent`), `CompleteSchedule.kt`, test `ScheduleOperationsTest.kt` | | ✓ | | | | | | | |
| `ui/journal/EventEntryViewModel.kt`, test `EventEntryViewModelTest.kt` | | | ✓ | | | | | | |
| androidTest `ui/maintenance/FormCompletionNavigationTest.kt` (case N5) | | | ✓ | | | | | | |
| `ui/nav/ServiceTagRoot.kt` | (Q2: the picker's argument) | | | ✓ one line | | | | | |
| `AndroidManifest.xml`, test `reminders/ManifestContractTest.kt` | | | | ✓ | | | | | |
| `ui/components/FormInsets.kt` (new), test `ui/nav/InsetsContractTest.kt` (new) | | | | ✓ | ✓ K2 list | ✓ K2 list, closure | ✓ K6 | | |
| androidTest `ui/nav/SystemBarsOnceTest.kt` (new) | | | | ✓ | | | | | |
| B1–B6 screens; G1 report `:56` | | | | | ✓ | | | | |
| B7–B15 screens | | | | | | ✓ | | | |
| `share/ShareIntakeScreen.kt` (`:150-156`), `share/ShareIntakeActivity.kt` (`:104`) | | | | | | | ✓ | | |
| ledger `sweep/` (scripts, captures, `results.tsv`, `summary.md`) | | | | | | | | ✓ | |
| `app/build.gradle.kts`, `VersionAgreementTest.kt`, `docs/versioning.md`, `docs/releases/1.8.2.md` (new), `README.md`, `docs/capabilities.md`, `docs/release-proofs.md` | | | | | | | | | ✓ |

### T1 — the sweep after a detail completion (#123) · ~45 min

§3.7. Add `afterWrite` to `completeAll` and `completeSelected`; the `runAfterWrite` helper; the `reconcile` parameter and the lambdas in both view models (each with a private `TAG`); the graph constructors' `reconcile = graph.reminderReconcile`. With Q2 answered yes: the picker's parameter, its lambda and the root's argument. Tests F1–F4, SD1–SD8, GD1–GD4, W1 (§7), each through a counting fake `ReminderReconcile` that records, at the moment it runs, how many events the store holds and whether an offer is open.
**Proof:** `./gradlew :app:testDebugUnitTest --tests '*CompletionFlowTest' --tests '*ScheduleDetailViewModelTest' --tests '*GroupDetailViewModelTest' --tests '*ReminderSweepWiringTest'`, then the whole `:app:testDebugUnitTest`; `./gradlew :app:compileDebugAndroidTestKotlin`.
**Acceptance:** every new row red first (SD and GD rows fail with zero sweeps; F1/F2 fail to compile, then with zero runs); `AssetTransferStateTest` (which builds both view models, `:404`, `:441`) unchanged and green; the scan sheet's tests (`MaintenanceSheetViewModelTest`, its `reconciles == 1`) unchanged and green; no file outside the T1 column touched.

### T2a — the meter in core (#119) · ~30 min

§3.6's core half. `buildEvent`'s `alsoRequired`; `CompleteSchedule` passes the meter under `viaForm`. Tests C4–C11 beside 1.8.1's C1–C3 in core `ScheduleOperationsTest`.
**Proof:** `./gradlew :core:test`, C4–C7 and C9 seen failing first (C8, C10, C11 are pins, green throughout).
**Acceptance:** only `EventCommands.kt`, `CompleteSchedule.kt` and one core test file change; `EventUseCasesTest`, `CrossConceptWriteTest` and 1.8.1's C1–C3 unchanged and green; no caller of `buildEvent` but `CompleteSchedule` passes `alsoRequired`.

### T2b — the meter on the form (#119) · ~50 min

§3.6's app half: the schedule read in every schedule-opened load, the meter row made required or appended, the KDoc clause in `CompletionFlow`. Tests M1–M8 through `FakeGraph` (`app/src/test/kotlin/com/loosecannon/servicetag/testing/FakeGraph.kt:228`), and the Compose case N5 in `FormCompletionNavigationTest` (fixtures fictional: an asset "Mower", a meter definition "Engine hours" (h), a FORM schedule "Blade sharpen" counting it every 25 h, a profile "Blade service" with one required reading "Blade edge" that does not carry the meter).
**Proof:** `./gradlew :app:testDebugUnitTest --tests '*EventEntryViewModelTest'`, then the whole `:app:testDebugUnitTest`; `tools/emulator/prepare-emulator.sh`, then `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.loosecannon.servicetag.ui.maintenance.FormCompletionNavigationTest`.
**Acceptance:** M1–M5 and N5 red then green; M6–M8 pins green throughout; every existing `EventEntryViewModelTest` case and 1.8.1's twelve `FormCompletionNavigationTest` cases unchanged and green; `UiLiteralGuardTest` green (no literal added).

### T3 — the root, the windows and the pins (#121) · ~55 min

§3.1, §3.2, the helper of §3.3.1 (no caller yet), and the structural and instrumented pins that do not depend on T4–T6: K1, K3, K4, K5, K7 and K2's classification (the group B and group C sets as literal lists; K2's "uses `formContentInsets`" row starts with an empty list that T4 and T5 fill), MF1, MF2, and `SystemBarsOnceTest` B1–B3.
**Proof:** `./gradlew :app:testDebugUnitTest --tests '*ManifestContractTest' --tests '*MergedManifestContractTest' --tests '*InsetsContractTest'`, then the whole `:app:testDebugUnitTest`; `tools/emulator/prepare-emulator.sh`, then on `emulator-5554` the classes `SystemBarsOnceTest` (each case seen red with the root line locally reverted, never committed), `JournalSmokeTest`, `EventEntryActionsTest`, `FormCompletionNavigationTest`, `ScanSheetTest`, `SeasonSyncScreensTest`; `./gradlew :app:lintDebug` 0 errors.
**Acceptance:** MF1, MF2, B1–B3 red then green; K4 and K5 are pins (green on 1.8.1 and after); the manifest diff is two attributes; `ServiceTagRoot.kt`'s diff is the modifier line, its comment and one import.

### T4 — input screens, part 1, and the comments (#121) · ~50 min

B1–B6: `contentWindowInsets = formContentInsets` on each screen's `Scaffold`, nothing else in those screens; the four comment rewrites of §3.5 (three KDocs and G1 `:56`); K2's list gains the six files.
**Spot observation before handing in** (10 min of the box): 1.8.1's `t2b/seed.py` and `t2b/observe.py`, copied into the 1.8.2 ledger's `sweep/`, run the entry form in portrait and landscape, scroll then tap — the case that failed in 1.8.1 T2c. Its verdict and numbers go to the task report. A landscape column of zero height stops the task.
**Proof:** `./gradlew :app:testDebugUnitTest --tests '*InsetsContractTest'`; on `emulator-5554` `EventEntryActionsTest`, `JournalSmokeTest`, `JournalDeviceProofTest`, `EditorsDeviceProofTest` (its `imeNextWalksFocusDownALongProfile`, `:353`, now walks by bring-into-view, not pan), `FormCompletionNavigationTest`, `SystemBarsOnceTest`; `:app:lintDebug`.
**Acceptance:** each B1–B6 diff is the one argument and its import (B1–B3 also the KDoc); the KDocs and G1 `:56` agree with §3.5; the spot observation passes P1–P5 in both orientations.

### T5 — input screens, part 2 (#121) · ~50 min

B7–B13 (kind S) and B14 (kind L): the one argument. B15 (kind X): the argument and D7's `verticalScroll`. K2's list complete and its closure row (`formScreens == groupB`) added.
**Proof:** `./gradlew :app:testDebugUnitTest --tests '*InsetsContractTest'`, then the whole `:app:testDebugUnitTest`; on `emulator-5554` every androidTest class that drives one of the nine screens (the implementer lists them with `git grep -lE 'GroupEdit|ScheduleEdit|ServiceCaseEdit|LoanEdit|ReplaceAsset|TransferFlow|HomeAssistant|AssetsScreen|WriteTag' -- app/src/androidTest` and runs each), plus `SystemBarsOnceTest`; `:app:lintDebug`.
**Acceptance:** each diff is the one argument and its import, `WriteTagScreen` also the scroll; K2 closure green; nothing in group C, D or E touched.

### T6 — the share intake (#121) · ~35 min

§3.4. First, on the T5 build installed on `emulator-5554`, a text share opened from the shell (`adb -s emulator-5554 shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'Example note' -n com.loosecannon.servicetag/.share.ShareIntakeActivity`; an observation of layout, not a boundary proof, which stays `ShareBoundaryTest`'s), an asset picked, and the form step's title bounds against the status bar recorded (the read-first rule of §3.4); the command and the dump live in the ledger. Then F2 and F3's modifiers; K6.
**Proof:** `./gradlew :app:testDebugUnitTest --tests '*InsetsContractTest'`; on `emulator-5554` every `ShareIntake*` class in `app/src/androidTest` and `ShareBoundaryTest`; `:app:lintDebug`.
**Acceptance:** the read-first record in the report; the diff is two modifier insertions and their imports; the picker's code unchanged.

### T7a, T7b, T7c — the observation sweep (#121) · ~60 min each

§8. T7a: B1–B8 (32 cases). T7b: B9–B15, F1, F2 (30 cases). T7c: D1–D4, D6–D11 and E1–E8 (one sheet per file where the file holds several; 46 cases, portrait first, a T7d for any landscape remainder). Each writes its cases into the ledger as §8 says and reports pass/fail per case.
**Stop conditions:** any P1–P4 failure on a group B or F surface, or any portrait P5 failure anywhere, stops the sweep; the controller decides between a bounded fix round (the failing surface only, then that surface's cases again) and the owner. A landscape P5 failure on a dialog is recorded for Q1, not fixed.
**Acceptance:** every case of §8.3 has its row, its two captures and a verdict; the tree is clean (`git status` shows nothing under the scanned trees); `emulator-5554`'s rotation, forward and keyboard settings restored.

### T8 — the release (1.8.2 / 23) · ~40 min

- `app/build.gradle.kts:45-54`: `versionCode = 23`, `versionName = "1.8.2"`, the comment rewritten (a PATCH, #121 #119 #123; schema 21 / format 20 unchanged, so exports move both ways between 1.8.1 and 1.8.2).
- `VersionAgreementTest`: `theReleaseIdentityIs181AndCode22` (`:59-63`) becomes `theReleaseIdentityIs182AndCode23`; the status echo (`:157`) `"1.8.2"`; new `versioningRecords182` modelled on `versioningRecords181` (`:502-…`): exactly one row `| 1.8.2 | 23 |`, naming `#119`, `#121` and `#123`, **PATCH**, `schema **21**` … `format **20**` unchanged, linking `docs/releases/1.8.2.md` (which exists), naming `servicetag-v1.8.2`, no `PLACEHOLDER`, the only row claiming 23, after the 1.8.1 row. `versioningRecords180` and `versioningRecords181` are not edited.
- `docs/versioning.md`: the row after `:47`.
- `docs/releases/1.8.2.md`, 1.8.1's shape: `# ServiceTag 1.8.2`; an opening paragraph (version code 23, a fix release); `## Typing with the keyboard open (#121)` (the form ends at the keyboard's top and scrolls; the button can be reached while typing; the screen no longer slides; every screen's content sits closer to the top and bottom edges, the doubled space gone; in landscape the space above the keyboard is small: the field being typed into is shown and the rest is reached by scrolling); `## A form schedule's meter reading (#119)`; `## Reminders clear after completing from a schedule's page (#123)` (and from Log maintenance, if Q2); `## Developer API & MCP` (nothing changed); `## Upgrading` (in place over 1.8.1, data stays, schema 21 / format 20); `## Known limits` — 1.8.1's keyboard limit and its meter limit are gone; still true and carried: 1.8.0's unlinked entries, **Record entry** on a schedule's form completes its current occurrence, the narrow Done double-ask; added only if the sweep records them and the owner accepts them under Q1: a dialog's button under a landscape keyboard (Back closes the keyboard). Owner voice; no names from the owner's data.
- `README.md:15`, `:32-33`: the baseline 1.8.2 with its notes; `:33`'s previous releases list starts with 1.8.1 and keeps the `](docs/releases/1.7.0.md)` link (`VersionAgreementTest.kt:784`). `docs/capabilities.md:3`, `:196`: the baseline and its notes; the 1.7.0 link stays (`:785`).
- `docs/release-proofs.md` after `:145`: "Cut as 1.8.2 (code 23): no schema or format change; the phones ran 1.8.1, so the emulator proof is 1.8.1 → 1.8.2 in place, and it repeats the token-survival check. The keyboard sweep (§8 of the 1.8.2 plan) is a release row for this cut."
- **Proof:** `./gradlew :app:testDebugUnitTest --tests '*VersionAgreementTest'`, then R1.
- **Acceptance:** `VersionAgreementTest` green; the 1.8.0 and 1.8.1 cases unedited; every remaining `1.8.1` outside the release notes, the evidence history and those cases is deliberately historical (`git grep -nF 1.8.1` reviewed line by line).

**The whole-branch review asks:** does any screen still apply a bar twice, or none (I1, K2's sets against `git grep -lE '^\s*Scaffold\('`)? Is `imePadding` or `formContentInsets` anywhere it should not be (I8)? Did any sheet or dialog file move (I6, I7)? Is there a path left where a detail or group completion writes without one sweep, or sweeps twice (I12)? Can a sweep's failure surface as a refused completion (I13)? Does any caller but `CompleteSchedule` under `viaForm` demand a meter (I11)? Is there any form with two rows for one definition (I10)? Did any string, `/v1` or MCP file, Room or backup file move (I9, I15)?

## 7. Test matrix

"Fails without" names the mechanism on the 1.8.1 code or, for a pin, the wrong implementation it catches. Structural rows read source as text with **anchored** patterns (a line's code from its first non-blank character), so a comment naming a pattern never matches it.

### 7.1 #123 — the sweep (JVM)

| ID | Layer · file | Case | Fails without the change because |
|---|---|---|---|
| F1 | JVM · `CompletionFlowTest` | `completeAll(s) { count++ }` on a group round, answered → the members written, `count == 1`, and at that moment the events are in the store and no offer is open | `completeAll` takes no `afterWrite` (does not compile, then never runs it) |
| F2 | JVM · same | `completeSelected(s, [a, b]) { … }` → one run after the one write | the same |
| F3 | JVM · same | `completeAll` / `completeSelected` cancelled at the prompt, or refused (archived schedule) → zero runs | pin: catches an `afterWrite` run outside `attempt`'s success path |
| F4 | JVM · same | pin: `complete(s, a) { … }` on a QUICK schedule with an offer to make → one run, before the offer is published | catches the order changed |
| SD1 | JVM · `ScheduleDetailViewModelTest` | QUICK schedule, `complete()`, the prompt answered → one event; the counting sweep ran once, with the event already stored | `runCompletion` passes no `afterWrite`: zero sweeps |
| SD2 | JVM · same | QUICK schedule with a meter, opened as an arrival (`arrive()`), the reading answered → one event, one sweep; `arrivalEnded` once | the same: the Done arrival leaves the reminder posted |
| SD3 | JVM · same | group-target schedule, `completeAll()` → one write, one sweep | the flow's `completeAll` runs `{}` |
| SD4 | JVM · same | group-target schedule, `completeSelected([a])` → one sweep; `completeSelected([])` → none | the same |
| SD5 | JVM · same | FORM schedule, `complete()` → `NeedsForm`, zero sweeps (the form sweeps its own write) | pin: catches a sweep added outside `afterWrite` |
| SD6 | JVM · same | the prompt cancelled → zero; the schedule archived after load → refused, zero; the asset transferred out → P77-35, zero | pin: the same |
| SD7 | JVM · same | a sweep that throws `IllegalStateException`, on an arrival (`arrive()`, QUICK with a meter, answered) → the sweep was attempted once, the event stands, `arrivalEnded` is emitted once, `busy` false after the reload, the state re-derived (no longer due) | 1.8.1: zero sweeps. Unwrapped, the exception escapes `attempt`: the offers are skipped and `arrivalEnded` is never sent, so the arrival stays set and asks again (1.8.1 §3.3's hazard) |
| SD8 | JVM · same | pin: a sweep that throws `CancellationException` propagates (the model's job ends cancelled, no further sweep) | catches a catch-all that swallows cancellation |
| GD1 | JVM · `GroupDetailViewModelTest` | `completeAll(s)` → one sweep after the write | `GroupDetailViewModel` has no sweep |
| GD2 | JVM · same | `completeSelected(s, [a])` → one sweep | the same |
| GD3 | JVM · same | `completeMember(s, a)` → one sweep | the same |
| GD4 | JVM · same | a sweep that throws → attempted once, the write stands, no exception reaches the scope's handler, `busy` false | 1.8.1: zero sweeps. Unwrapped, the exception escapes `operate` (it catches only `AssetTransferredOut`) and takes the scope down |
| W1 | JVM · `ReminderSweepWiringTest` (new), reads `ScheduleDetailViewModel.kt` and `GroupDetailViewModel.kt` | each file has exactly one line matching `^\s*reconcile = graph\.reminderReconcile,$`, inside its `constructor(graph: AppGraph` block; with Q2 yes, `ServiceTagRoot.kt` has one `^\s*reconcile = graph\.reminderReconcile,$` inside the `LogMaintenancePicker(` call and `CompletionFlow.kt`'s picker calls `flow.complete(` with a trailing lambda naming `runAfterWrite` | the graph constructors pass nothing, so the default no-op runs in the app while every unit test passes: this row is CI's only guard on the wiring |

### 7.2 #119 — the meter reading (JVM, one Compose case)

| ID | Layer · file | Case | Fails without the change because |
|---|---|---|---|
| C4 | JVM · core `ScheduleOperationsTest` | FORM schedule counting meter `m`, profile without `m`, `viaForm`, `m` absent → `EventValidation` containing `Required(m)`; no event; the due unchanged | `CompleteSchedule` demands only the profile's fields: the completion is written without a reading |
| C5 | JVM · core | the same with `m = "120"` → one event, exactly one measurement for `m` (120), `scheduleId` set | pin on the happy path; catches the reading dropped or doubled |
| C6 | JVM · core | profile carrying `m` optional, `viaForm`, `m` blank → `Required(m)` | the profile's `required = false` wins |
| C7 | JVM · core | profile carrying `m` required, `m` = 120 → one measurement for `m`, not two | pin: catches `alsoRequired` considered a second time after the profile field |
| C8 | JVM · core | pin: not `viaForm` (an API-shaped minimal completion; a QUICK command with no values) → no `Required(m)`; a minimal FORM completion still `details_pending` (1.8.1 `:681-700` unchanged) | catches the demand applied to `/v1` or the quick path |
| C9 | JVM · core | the meter definition deleted (unresolvable) → nothing demanded; the completion written | an unresolvable required id is reported `Required` by `consider`; `CompleteSchedule` must not pass it |
| C10 | JVM · core | pin: the meter definition archived → nothing demanded; a value sent for it is still stored | catches the archived rule bypassed |
| C11 | JVM · core | pin: `LogEvent` and `UpdateEvent` with a value for a definition outside the profile → unchanged (an optional extra) | catches `alsoRequired`'s default not empty |
| M1 | JVM · `EventEntryViewModelTest` | schedule-opened form, FORM + meter `m`, profile without `m` → `fields` = the profile's rows, then one row for `m`, `required` true | the form draws only the profile's rows |
| M2 | JVM · same | profile carrying `m` optional → no extra row; that row's `required` true; `m` appears once in `fields` | no row is made required |
| M3 | JVM · same | profile-less FORM + meter → every unarchived definition once; `m`'s row required | the meter row stays optional |
| M4 | JVM · same | M1's form, `m` blank, the required reading typed, `save()` → `m`'s row has `problem = Required(m)`, `firstProblem` is "<label> is required", `saving` false, zero events, the due unchanged | the completion is written without the reading |
| M5 | JVM · same | M1's form, `m` = "120", `save()` → one linked event with one measurement for `m`; the schedule's read state shows the meter completion advanced (`lastCompletedMeter` 120) | no reading collected |
| M6 | JVM · same | pin: the same asset and profile with no `scheduleId` → rows exactly as 1.8.1 (no meter row, nothing made required) | catches the meter added to an ordinary entry |
| M7 | JVM · same | pin: a FORM schedule without a meter → rows as 1.8.1 | catches a row for a null meter |
| M8 | JVM · same | pin: editing a linked completion (`eventId` set, no `scheduleId`) → rows as 1.8.1 | catches the edit path reading the schedule |
| N5 | Compose · `FormCompletionNavigationTest` | schedule detail of "Blade sharpen" → **Log maintenance** → the form shows a row labelled "Engine hours" → "Blade edge" typed, **Record entry** → still on the form, "Engine hours is required" shown, zero events → "120" typed in "Engine hours", **Record entry** → the detail, one event linked to the schedule carrying the reading | the row never appears; the save writes without the reading |

### 7.3 #121 — what JVM and structure can pin

| ID | Layer · file | Case | Fails without the change because |
|---|---|---|---|
| MF1 | JVM · `ManifestContractTest` | `MainActivity` and `ShareIntakeActivity`: `android:windowSoftInputMode` == `adjustResize`; `NfcDispatchActivity`: no attribute | no attribute (pan) |
| MF2 | JVM · `MergedManifestContractTest` | the same two values in the merged manifest | the same; also catches a library or source set overriding them |
| K1 | JVM · `InsetsContractTest` (new, `app/src/test/kotlin/com/loosecannon/servicetag/ui/nav/`), reads `ServiceTagRoot.kt` | exactly one line `^\s*modifier = Modifier\.padding\(padding\)\.consumeWindowInsets\(padding\),$`, and it is the first `modifier =` after the anchored `NavDisplay(`; no `^\s*contentWindowInsets =` between the outer `Scaffold(` and its `) { padding ->` | 1.8.1's `modifier = Modifier.padding(padding),` |
| K2 | JVM · same, reads `app/src/main/kotlin` | **classification:** the set of files with an anchored `Scaffold(` equals group B ∪ group C ∪ {the root}, each a literal list in the test (a new screen fails here until it is classified); **application:** every anchored `Scaffold(` in a group B file is followed, before its `) { … ->` content lambda, by a line `^\s*contentWindowInsets = formContentInsets,$`; no group C file names `formContentInsets` | no screen passes the insets; the doubled bars and the pan stand |
| K3 | JVM · same | no line in `app/src/main/kotlin` matches `\.imePadding\(\)` or `^\s*imePadding\(\)`; `formContentInsets` is defined once (`ui/components/FormInsets.kt`) | pin: catches a screen, sheet or dialog adding its own keyboard padding on top of D3 or Material 3's (the double count of RK6) |
| K4 | JVM · same | every anchored `ModalBottomSheet(` call has no `contentWindowInsets =` argument; in the eight group E files the number of `skipPartiallyExpanded = true` equals the number of `ModalBottomSheet(` calls | pin: green on 1.8.1; catches a sheet overriding Material 3's insets or opening half way with a field |
| K5 | JVM · same | no line in `app/src/main/kotlin` matches `decorFitsSystemWindows\s*=\s*false` | pin: catches a dialog taken out of the platform's keyboard handling |
| K6 | JVM · same, reads `share/` | `ShareIntakeScreen.kt` has exactly two lines matching `^\s*\.windowInsetsPadding\(WindowInsets\.safeDrawing\)`; `ShareIntakeActivity.kt` one | the form step and the pack host have none |
| K7 | JVM · same, reads `ui/scan/WriteTagScreen.kt` | the content column's modifier chain (from the anchored `Column(` after `) { padding ->`) contains `.verticalScroll(` after `.padding(padding)` | the column does not scroll (D7) |

### 7.4 #121 — what an instrumented test can pin (the bars), and what only the device shows (the keyboard)

Compose tests cannot see the keyboard: the IME is another window, and `assertIsDisplayed` checks a node against its own window. They can see the system bars, because on the API 37 emulator every window is edge to edge with real status and navigation bar insets.

| ID | Layer · file | Case | Fails without the change because |
|---|---|---|---|
| B1 | Compose · `SystemBarsOnceTest` (new, `app/src/androidTest/kotlin/com/loosecannon/servicetag/ui/nav/`), the real `ServiceTagRoot` driven through `deepLinks` as `FormCompletionNavigationTest` is | a pushed route (`Route.EventEntry` on a fictional asset "Bench"): the node with the scroll action's top in root equals the status-bar inset (read from the activity's root view's `WindowInsetsCompat`) plus 64 dp (±1 px) | 1.8.1: the status bar is counted twice, one inset higher |
| B2 | Compose · same | the same route, keyboard down: that node's bottom equals the root height minus the navigation-bar inset (±1 px) | 1.8.1: the navigation bar counted twice |
| B3 | Compose · same | a top-level route (`Route.Assets`): the list's bottom equals the bottom bar's top edge (±1 px) | pin: catches the bottom bar's height not consumed (a gap or an overlap) |
| O | device · the sweep (§8) | every group B, D, E, F surface, portrait and landscape, both orders | no test layer sees the IME window |

**Existing suites, unchanged and green:** core `ScheduleOperationsTest` (1.8.1's C1–C3), `EventUseCasesTest`, `CrossConceptWriteTest`; app `CompletionFlowTest`'s existing cases, `MaintenanceSheetViewModelTest` (`reconciles == 1` stands), `ScheduleDetailViewModelTest`'s and `GroupDetailViewModelTest`'s existing cases, `AssetTransferStateTest`, `EventEntryViewModelTest`'s existing cases, `RouteTest`, `QuickActionsTest`, `UiLiteralGuardTest`, `LocalizationCoverageTest`, `ReleaseProofPolicyTest`, `VersionAgreementTest` (T8's named cases only); androidTest every class in R2, among them `EditorsDeviceProofTest`, `JournalDeviceProofTest`, `FormCompletionNavigationTest`'s twelve 1.8.1 cases, `ShareBoundaryTest`, `ContactGrantBoundaryTest`.

## 8. The observation sweep (T7a, T7b, T7c; release row O)

### 8.1 Method

- **Where.** `emulator-5554` only (the gate profile: Pixel 10 Pro XL, API 37, 1344×2992 at 480 dpi, the default keyboard), started off NVIDIA as the runbook says, after `tools/emulator/prepare-emulator.sh`. Every `adb` call carries `-s emulator-5554`. The emulator's lifecycle and the adb server are not touched.
- **What runs.** The branch's debug build (`:app:installDebug` through the ledger's `run-tasks.sh`), never a release candidate (row O at the tip uses the tip's debug build too).
- **Data.** Fictional only, seeded through the loopback API (the Developer API screen open, a forward `tcp:17338 → tcp:17337`, the pairing code from the screen), as 1.8.1's `t2b/seed.py` did: an asset "Test bench" with six entered NUMBER readings `f1`–`f6` and a MEASUREMENT profile "Long form" carrying all six (none required; the long column); an asset "Mower" with a meter "Engine hours" (h), a FORM schedule "Blade sharpen" counting it, a QUICK schedule "Deck clean", a QUICK schedule "Oil check" counting "Engine hours" (D8's meter field), a group "Garden kit" with "Mower" and a second asset "Trimmer" and a QUICK group schedule "Season check"; a supply "Example oil"; a service case on "Trimmer"; a reference on "Mower". The forward is removed when seeding ends.
- **Keyboard settings** (memory of 2026-09-23): `settings put secure stylus_handwriting_enabled 0` before the first tap; restored after. No text is injected except where a case needs a value to reach a state (D1's typed word is not typed: the dialog is observed with its field focused).
- **Scripts** (ledger `sweep/`, never in the tree): `seed.py` (from `t2b/seed.py`), `ui.py` (from `t2b/ui.py`: dump, tap by text or description, wait, bounds), `insets.py` (new: reads `dumpsys window` for the app window's status-bar, navigation-bar and IME inset sources — frame and visibility — and the screen size), `routes.py` (new: for each surface, the taps from the dashboard, or a deep link, that open it), `observe.py` (from `t2b/observe.py`, generalised: one case = one surface × one orientation × one order; writes the captures and one `results.tsv` row). `probe.py` is folded into `observe.py`.
- **Orientation.** Portrait as installed. Landscape: the surface opened in portrait, then `settings put system accelerometer_rotation 0` and `user_rotation 1` (the activities handle `orientation|screenSize`, so the screen is not recreated); restored to `user_rotation 0`, `accelerometer_rotation 1` after every landscape case.
- **The two orders.**
  - *scroll then tap:* keyboard down, the content scrolled to its end (swipes until two dumps are identical), then the **last** typed input above the commit control tapped. Capture `tapped`. Then swiped to the end again with the keyboard up. Capture `end`.
  - *tap then scroll:* keyboard down at the top, the **first** typed input tapped. Capture `tapped`. Then swiped to the end with the keyboard up. Capture `end`.
  - A surface with one input (dialogs, most sheets, B14, B15) runs one order, *tap*, and its `end` capture is taken without a swipe when nothing scrolls.
- **Captures per step:** the uiautomator dump (`<case>-<step>.xml`), a screenshot (`<case>-<step>.png`), and the inset sources (`<case>-<step>.insets.txt`).

### 8.2 What is measured and the pass criteria

All bounds in screen pixels. `status` = the status-bar source's bottom edge; `nav` = the navigation-bar source's height; `ime` = the IME source's top edge when visible; `bar` = the app bar's height (192 px at 480 dpi); `viewport` = the scrollable node of the surface (for B14 and F1 the list; for B15 the column, scrollable after D7); `content` = `android:id/content`; `focused` = the node with `focused="true"`; `commit` = the surface's commit controls, by their visible text (§8.3).

| ID | Criterion | Applies to |
|---|---|---|
| P1 | **Bars once.** Keyboard down: `viewport.top − status == bar` (±2) on a pushed `Scaffold` screen (top-level: the same at the top); `viewport.bottom == screen height − nav` (±2) on a pushed screen; on a top-level screen `viewport.bottom == the bottom bar's top` (±2). Landscape: the same, and `viewport.left` is the cutout's right edge or the content's own 16 dp gutter past it, never twice the cutout. F2: the title's top ≥ `status`. | B, F |
| P2 | **Ends at the keyboard.** Keyboard up: `|viewport.bottom − ime| ≤ 2`. Sheets: the sheet's last visible node's bottom ≤ `ime`. Dialogs: the dialog's window bottom ≤ `ime` in portrait. | B, E, F; D portrait |
| P3 | **No pan.** Keyboard up: `content.bottom == screen height` (the app window keeps its place; 1.8.1 measured pans of 255–864 px). | B, F (the activity's window) |
| P4 | **The focused field in view.** Keyboard up: `focused` exists; `viewport.top ≤ focused.top < ime`; and when `focused.height ≤ viewport.height`, `focused.bottom ≤ ime`. Dialogs and sheets: `focused.top ≥ status` and `focused.bottom ≤ ime`. | all |
| P5 | **The commit reachable while typing.** At `end` with the keyboard still up: every bottom commit control lies within `[viewport.top, ime]`; every app-bar commit control lies within `[status, viewport.top]`. Dialogs: the confirm button within `[status, ime]` — required in portrait, recorded in landscape (Q1). | B with a commit, D, E, F2 |
| P6 | **Back once closes the keyboard and stays.** From `end`, one Back: the IME source not visible, the same surface on screen. | one case per kind (B1, B14, D8, E3, F2) |

A case **passes** when every criterion that applies holds. A 1.8.1 reference value is recorded beside P1 and P3 for B1 from the 1.8.1 ledger (viewport top 510, bottom 2848; pan 606 and 864).

### 8.3 The cases

Orders: **2** = scroll then tap and tap then scroll; **1** = tap. Each row runs in portrait and landscape.

| Task | Surface | Opened by | Orders | Inputs tapped (first / last) | Commit controls (P5) | Cases |
|---|---|---|---|---|---|---|
| T7a | B1 entry form | "Test bench" › Log "Long form" | 2 | `f1` / Notes | Record entry | 4 |
| T7a | B2 asset edit | "Test bench" › Edit | 2 | Name / Notes | app-bar Save, bottom Save | 4 |
| T7a | B3 reading edit | Asset setup › `f1` | 2 | Label / the last field | app-bar Save, bottom Save | 4 |
| T7a | B4 profile edit | Asset setup › "Long form" | 2 | Name / the last consumable row (one added if none) | app-bar Save, bottom Save | 4 |
| T7a | B5 supply edit | Supplies › "Example oil" › Edit | 2 | Name / the last specification row | app-bar Save | 4 |
| T7a | B6 health subject edit | "Test bench" › health subject | 2 | the first field / the last threshold | app-bar Save, bottom buttons | 4 |
| T7a | B7 group edit | Maintenance › groups › "Garden kit" › Edit | 2 | the first / the second field | app-bar Save | 4 |
| T7a | B8 schedule edit | "Deck clean" › Edit | 2 | Title / the last `MaintenanceField` | app-bar Save | 4 |
| T7b | B9 service case edit | "Trimmer" › the case › Edit | 2 | the first `FormField` / the last | bottom button | 4 |
| T7b | B10 loan edit | "Mower" › Lend | 2 | the first `DateField` / the last `FormField` | bottom buttons | 4 |
| T7b | B11 replace asset | "Trimmer" › Replace | 2 | the first / the last field | the screen's button | 4 |
| T7b | B12 transfer review | Assets › Transfer › "Test bench" › review | 2 | the review field (one input: both orders) | the review button | 4 |
| T7b | B13 Home Assistant | Settings › Home Assistant | 2 | address `ha.example` field / token field | Save / Test buttons | 4 |
| T7b | B14 Assets search | Assets tab | 1 | the search box | — | 2 |
| T7b | B15 write tag | "Mower" › Write tag | 1 | Placement | — | 2 |
| T7b | F1 share picker | shell text share (T6's command) | 1 | the search box | — | 2 |
| T7b | F2 share form | F1 › "Mower" | 2 | Name / Description | Save | 4 |
| T7c | D1 typed confirm | "Trimmer" › Delete | 1 | the word field | Delete | 2 |
| T7c | D2 tag placement | "Mower" › tag › placement | 1 | placement | Save | 2 |
| T7c | D3 retire | "Trimmer" › Retire | 1 | the date | Retire | 2 |
| T7c | D4 season | "Mower" › season | 1 | the date | the confirm | 2 |
| T7c | D6 rename category | Settings › Categories › rename | 1 | name | Save | 2 |
| T7c | D7 postpone | "Deck clean" › Postpone | 1 | the date | the confirm | 2 |
| T7c | D8 when was this done | "Oil check" › Complete | 2 | the date / the meter reading | the confirm | 4 |
| T7c | D9 return | "Mower" lent (B10) › Return | 1 | the date | the confirm | 2 |
| T7c | D10 calendar, input mode | any `DateField`'s calendar › the mode toggle | 1 | the date input | OK | 2 |
| T7c | D11 close round | "Season check" › close the round | 1 | the picker's input mode | the confirm | 2 |
| T7c | E1 link season sync | "Mower" › Link to Home Assistant | 1 | entity ID | Link | 2 |
| T7c | E2 attachment edit | "Mower" › a document › Edit (a fictional file seeded as an attachment) | 2 | name / description | Save | 4 |
| T7c | E3 change condition | "Mower" › Change condition | 1 | reason | Save | 2 |
| T7c | E4 installed component form | "Mower" › Installed components › Add | 2 | the first / the last field | Save | 4 |
| T7c | E5 materialize | "Mower" › a reference › Save as document | 1 | name | Save | 2 |
| T7c | E6 add link | "Mower" › References › Add link | 2 | URL / description | Add | 4 |
| T7c | E7 supply role | "Mower" › Supplies › "Example oil" › role | 1 | role | Save | 2 |
| T7c | E8 service case update | "Trimmer" › the case › Update | 2 | the first / the last field | Save | 4 |

Totals: T7a 32 cases, T7b 30, T7c 46 (22 dialog and 24 sheet cases over 18 surfaces), 108 in all. T7c runs the portrait cases of every D and E surface first (23), then the landscape cases (23); if its box ends before the landscape half is done, the remainder is a T7d of the same shape, ≤ 60 min, never a longer T7c. D5 is not driven (§4.4). A surface a script cannot reach is recorded as "not reached" with the reason, and the controller decides; it is never silently dropped.

### 8.4 The evidence format

In `.superpowers/sdd/2026-10-23-servicetag-1.8.2/sweep/`:
- `results.tsv`, one row per case: `case`, `surface`, `orientation`, `order`, `screen_w`, `screen_h`, `status`, `nav`, `ime_top`, `content_bottom`, `viewport` (`l,t,r,b`), `focused` (`l,t,r,b`), `commit` (name=bounds; several separated by `;`), `P1`…`P6` (`pass`, `fail` or `n/a`), `note`.
- `summary.md`: one table per task, a ✓ or ✗ per case and criterion, the build's commit, the date, and the 1.8.1 reference values for B1.
- Per case: `<case>-tapped.{xml,png,insets.txt}` and `<case>-end.{xml,png,insets.txt}`; for P6, `<case>-back.xml`.
- The scripts and their versions as run; `adb forward --list` empty and the rotation and keyboard settings restored, recorded at the end of each task.
- Case ids: `<surface>-<p|l>-<st|ts|t>`, for example `B1-l-st` (entry form, landscape, scroll then tap).

## 9. Strings

**No string is added, removed or changed.** Reused: the form's "%s is required" (`journal_field_required`) for the meter row, with the meter definition's own label (owner data, not a string); the form's could-not-save line for a meter demanded on an undrawn row; every label the screens already draw. No ratification is needed. `UiLiteralGuardTest` and `LocalizationCoverageTest` stay green unchanged.

## 10. Release proofs (at the integrated tip, after the whole-branch review)

| Row | Command / act | Expectation |
|---|---|---|
| R1 | `./gradlew --rerun-tasks :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest` | green from scratch; includes R5, `ReleaseProofPolicyTest`, `InsetsContractTest` and the sweep rows of §7.1 |
| S | inside R1, named: `CompletionFlowTest` F1–F4, `ScheduleDetailViewModelTest` SD1–SD8, `GroupDetailViewModelTest` GD1–GD4, `ReminderSweepWiringTest` W1 | the reminder sweep after every detail and group completion, once, after the write, a failure never undoing it (#123) |
| R2 | `tools/emulator/prepare-emulator.sh`; stage the preserved set (runbook §"Before R2"); `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`, off NVIDIA | the complete suite, **zero skips**, `SystemBarsOnceTest` and `FormCompletionNavigationTest` (with N5) among the classes |
| R3 | the runbook's line as written (`docs/release-proofs.md:18`): `tools/servicetag-mcp`, `tools/servicetag-bundle`, `tools/servicetag-schedules` | each tool's pytest green; none changed |
| R4 | inside R2: `ShareBoundaryTest`, `ContactGrantBoundaryTest` | five cases; nothing added |
| R5 | inside R1: `ManifestContractTest`, `MergedManifestContractTest` | green, with MF1 and MF2: both drawing activities `adjustResize` |
| R6 | `bash tools/check-commit-hygiene.sh servicetag-v1.8.1 HEAD` (authoritative for author, committer and bodies; merges exempt); the runbook's content greps over `servicetag-v1.8.1..HEAD`; `bash tools/check-submodule-pin.sh`; the androidTest `assert(` grep; `./gradlew :app:lintDebug` | no e-mail, home path or non-emulator serial in added lines; no `co-authored`, `generated-by`, tool or model name in a message; `submodule pin ok`; no `assert(`; lint 0 errors, nothing suppressed |
| O | the sweep at the tip: if no file under `app/src/main` changed between T7's build and the tip, T7's evidence stands and six cases are repeated on the tip's debug build (`B1-p-st`, `B1-l-st`, `B14-l-t`, `D8-p-ts`, `E3-l-t`, `F2-l-st`); otherwise every case of a surface whose file, or whose host's file, changed is repeated, plus those six | every repeated case passes as in §8.2; the evidence beside T7's in the ledger |
| D | `bash tools/release-dry-run.sh` | passes: the pin, the gate, `assembleRelease`, version 1.8.2 / 23, one signer equal to `RELEASE_CERT_SHA256` |
| U | `tools/emulator/prepare-emulator.sh`; on `emulator-5554` the published, checksum-verified 1.8.1 asset installed; fictional data seeded (the sweep's `seed.py`) and a fictional Home Assistant connection (`ha.example`, an `example-` token) saved; the dry-run candidate `adb install -r` in place | UID and `firstInstallTime` unchanged; version code 22 → 23; schema 21 / format 20 on both sides; `GET /v1/status` counts identical; the connection and the token still present; then, on the candidate, the entry form in landscape, scroll then tap (`B1-l-st`): P1–P5 pass on the signed build |
| R7 | the verified, published APK `adb install -r` over 1.8.1 on the **development phone** (serial in the environment only) | UID and `firstInstallTime` unchanged; version code 23; `/v1/status` counts identical; no export requested |

The tag `servicetag-v1.8.2` goes on the tip after R1–R6, O, D, U and the review, by the owner's authorization; the release workflow's environment approval stays the owner's gate. The **production phone** is not a proof row (`release-proofs.md:99`): its install is on the owner's word, with the same UID / `firstInstallTime` / version check and the token-survival check. The controller writes the evidence (tip, runs, the sweep's summary, APK checksum, signer, U, R7) into `docs/architecture/product-split-evidence.md` under a 1.8.2 heading after 1.8.1's.

## 11. Risks

| # | Risk | Where | Mitigation |
|---|---|---|---|
| RK1 | **Screens that relied on the pan.** Under `adjustResize` the window no longer moves; a screen with typed input and no keyboard inset leaves its field under the keyboard. | any input screen outside group B | K2's classification closure (every `Scaffold` file is B, C or the root, as literal lists); §4.3 shows no group C screen has typed input of its own; dialogs and sheets are other windows; the sweep (§8) covers every B, D, E and F surface. Between T3 and T5 the branch is in this state on purpose (§6). |
| RK2 | **Nested scroll containers.** A field in an inner scroll container is brought into view by the innermost one first; the keyboard inset must be applied outside the outermost. | `ServiceCaseScreen.kt` (three `verticalScroll`: screen and sheet), `ProfileEditScreen.kt` (two: screen and `FieldPicker`'s dialog), `ReplaceAssetScreen.kt` (two), `TransferFlowScreen.kt` (three), `LinkSeasonSyncSheet.kt` (scroll plus `LazyColumn`), the share picker | D3 puts the inset on the `Scaffold`, which pads outside every content container; the second containers listed are in other windows (a sheet, a dialog) or other branches of the screen (§4.2 records where `padding` is applied); the sweep's P4 on B4, B11, B12, E1, E8 and F1. |
| RK3 | **Lazy-list forms.** A focused field in a `LazyColumn` that scrolls out of view is disposed with its focus (spec §9's 2A note). | no group B form is lazy (the entry form is a `Column` on purpose, `EventEntryScreen.kt:172-174`); E1's Choose entity and F1's picker search a lazy list from a field at its top | the search fields are the lists' first items or above them; the sweep's P4 on B14, E1, F1. Nothing here changes a list. |
| RK4 | **The landscape height budget.** Above a landscape keyboard (top at 561 of 1344 px) a pushed form keeps about 210 px (70 dp) after the status bar and the 64 dp app bar: one single-line field, or the top of a taller one. | every group B screen in landscape | P4 is stated for a field taller than the viewport; P5 asks only that the commit control be reachable by scrolling. The app bar does not collapse (that would be a behaviour change); Q1 asks the owner whether to accept the budget in 1.8.2. 1.8.1's collapse came from the doubled bars plus the pan, which this release removes. |
| RK5 | **The visible spacing change on every screen.** Content moves up one status-bar height on every route, down one navigation-bar height on pushed routes, and in by one cutout width in landscape. | all 37 routes | intended (§3.5); said in the release notes; `SystemBarsOnceTest` pins it; R2 shows whether any instrumented test relied on 1.8.1's geometry. The ones that read positions on `16768cde` compare nodes with each other (`SeasonSyncScreensTest.kt:361`, `:862`: an order of lines; `ShareIntakeScreenTest.kt:525`, `:1094-1100`: tops, and rows against their own list's window), not with the window's edges; T3 runs the first, T6 the second. |
| RK6 | **Resize plus keyboard padding counted twice.** | any surface | under edge to edge, `adjustResize` resizes nothing on API 30+ and on API 26-29 delivers the keyboard as an inset to a window that does not fit system windows, so the inset is the only lift; K3 (no `imePadding` anywhere), K4 (no sheet inset override; Material 3 already pads the sheet's box), K5 (no dialog out of the platform's handling); the share picker's `safeDrawing` no longer meets a pan; on a top-level route the root consumes the bottom bar, so `exclude` nets the keyboard's overlap (§3.1, point 3). P2 and P3 measure it. |
| RK7 | **API 26-29 are not on the gate emulator.** The keyboard inset path differs there. | devices on Android 8–10 | reasoned in §3.2; the manifest mode is what that path needs; recorded as untested on device in the release notes' upgrading section only if the owner asks (not a known limit: no defect is known). |
| RK8 | **Snackbars ride above the keyboard** on group B screens. | B1–B15 | intended (§3.3.1); a refusal said while typing is seen. |
| RK9 | **The detail sweep doubles the scan sheet's** when a completion is made on the detail while the sheet is underneath. | rare stacks | `reconcileAll` is idempotent and serialized; two sweeps cost a second read, nothing else. |
| RK10 | **A meter added to a schedule while its form is open** is demanded on a row the form did not draw. | #119 | the could-not-save fallback line, nothing written (§3.6's table), as 1.8.1 §3.6 does for a profile changed under an open form; reopening the form draws the row. |
| RK11 | **An archived meter**: the quick prompt asks for a reading of an archived meter definition; the form neither draws nor demands an archived definition (`buildEvent`'s rule). | #119 | stated in §3.6; pinned by C10; the quick prompt is unchanged. |
| RK12 | **The share intake's form and pack host move down** by the status-bar height, if §3.4's read-first record confirms they draw under it today. | F2, F3 | the read-first step; the stop condition if they do not. |

## 12. Rulings and what is left open

- **Scope** (owner, 2026-10-23): ruled — #121 + #119 + #123, a PATCH, from master `16768cde`.
- **Q1 — landscape (owner).** With the keyboard up in landscape, a form keeps about 70 dp between its app bar and the keyboard: the field being typed into is shown from its top, and everything else, the commit button included, is reached by scrolling. A dialog is moved by Android to keep its field above the keyboard; a tall dialog's confirm button can then sit under a landscape keyboard (Back closes the keyboard). The plan accepts both for 1.8.2 and changes nothing else. Accept, or rule a follow-up (an app bar that collapses while typing in landscape; dialogs that scroll) as a later issue?
- **Q2 — the Log maintenance picker (owner).** Completing a QUICK schedule from the Maintenance tab's **Log maintenance** also leaves its reminder posted (`CompletionFlow.kt:519`): the same defect as #123, not named in it, fixed by the same three lines. The plan includes it (T1, W1) unless the owner says no; then the controller files it as its own issue and T1 leaves it out.
- **Planner's readings, for the controller to confirm or overturn:** (a) "the sweep as a proof row" is read as the keyboard observation sweep (row O); the reminder sweep's proof is named too, as row S, inside R1, because the testing hierarchy gives a sweep run by a view model to the JVM layer and no new OS boundary is crossed; (b) the share intake's form step and pack host gain the `safeDrawing` inset (§3.4), an inset-only change that (e) allows, behind a read-first stop condition; (c) `WriteTagScreen`'s column gains a scroll container (D7), the one change beyond insets, because without it nothing brings its field above a landscape keyboard.
