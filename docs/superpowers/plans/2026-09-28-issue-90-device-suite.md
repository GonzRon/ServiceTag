# #90 — a thin device suite: classify, move down, budgets, a bounded merged-tip gate: plan and briefs (rev 2.1, 2026-09-28)

**PARKED (owner ruling, 2026-09-28).** Deferred from the critical path: the ordinary merged-tip gate measured 13.1 min
(device 12.1 min), under the 15-minute ceiling. Product work (#77 → #84 → #86 → #85) proceeds first; the gate is measured
after every merge; 14 min is the warning line; this plan is promoted before further feature work if the gate exceeds 15 min
or growth would make the next feature exceed it. On promotion: re-measure the baseline, re-check the classification against
the classes added since, and put the open escalations E1–E4 to the owner before B1.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development under the review budget.
> Seven briefs (§8–§14) on one branch `issue-90`, strictly sequential, each with one task review, one batched fix round,
> at most one scoped re-review; one whole-branch review. Planned read-only from issue #90 and the audit (both under
> `.superpowers/sdd/2026-09-28-issue-90/`, cited A§n) on master 79a1ee3d (#72 merged, schema 13 / format 13).
> No user-visible string moves, so there is no strings section: every moved test carries the shipped literals verbatim.
>
> **Rev 2** folds in the brief review (`brief-review.md`: REJECT on rev 1 — 1 blocker, 3 major, 10 minor, 8 notes; the
> timings confirmed) and the owner's rulings of 2026-09-28 (§7): **X1** → seventeen named, behaviour-neutral `internal`
> seams (C11) created by the briefs that need them, with a seam-neutrality review item and a diff-shape check, and three
> screens escalated rather than widened (X1-E1–E3); **Y1** → 65 classes (`DeepLinkSmokeTest`, `MaintenanceTabTest`),
> counts per class declaration, `MaintenanceShellTest` moved whole; **Y2** → the daemon's JDK 25 is the JVM that runs
> `:app`'s tests, verified first in B1; **Y3** → every step's exit and every stale or missing XML is a hard failure (H5);
> **R90-9** → seven classes admitted; Z1–Z10 and N1–N8 applied where they fall.
> **Rev 2.1** applies the re-review's C-1–C-8 (`brief-re-review.md`: APPROVE with conditions); nothing else moved.

**Goal:** the ordinary merged-tip gate's device portion ≤ 10 min and the whole gate ≤ 15 min on `emulator-5554`, held as
acceptance criteria and proven by two consecutive clean runs; every instrumented class classified in the repo; every
behaviour that does not need the platform proven on the JVM under a real mutation, its device case then deleted; per-class
reporting and budgets on every run; a written device-test admission rule that #77 and every later issue follow.

**Inputs:** issue #90 (AC 1–6); the audit (A§0–A§6) and the brief review; `docs/superpowers/planning-policy.md` (testing
hierarchy, #72 amendment); `docs/release-proofs.md` (R1–R7); the #72 gate script `.superpowers/sdd/2026-09-28-issue-72/gate/run.sh`
(one Gradle invocation per class, 41 base + 9 extra); `docs/design/10-testing-strategy.md:12, :61-62` (Robolectric planned,
never wired).

## Global constraints

- **No product behaviour change. Production code changes only as R90-X1's named seams (C11)** — `private` → `internal`,
  or a body moved verbatim into an `internal` content composable that the screen still calls — and nothing else: no domain
  logic, state derivation, navigation, user-visible behaviour or production call path moves; no test-only flag, branch,
  fake or `BuildConfig` test; no public test API. A seam that would need more is a stop-and-escalate for that one screen.
  `core/src/main`, `app/schemas` and the manifest do not move. Build and test wiring (`app/build.gradle.kts` test blocks,
  the catalog, test resources, `app/src/sharedTest`) is not production code. No version bump; gitlink `7e0377a`.
- **The device boundary stays thin (owner rule).** A device case exists only for what only the platform shows —
  notification manager, URI grants and foreign UID, SAF, manifest and intent resolution, lifecycle and process, real
  navigation or activity where an in-process test is insufficient, real text layout, font scale, IME and back dispatch —
  plus the smoke suites. Applied case by case, never by class name (R90-O2).
- **Mutations are JVM mutations**; #90 runs no device mutation (a brief that believes one is needed stops and reports).
  Every brief and fix round carries a mutation cap, a time box and stop-and-report conditions; a fix round is ≤ 1 h and ≤ 3
  mutation runs.
- **Nothing is deleted before its JVM replacement is proven** (C8), and no replacement is weaker than the case it replaces.
- **Measure, never estimate.** Every gate run prints per-class execution and wall time (C7); §3's one projection is
  labelled and built from A§1's measured numbers.
- **Environment.** `emulator-5554` only, `tools/emulator/prepare-emulator.sh` first, never restart the adb server, one
  connected class per Gradle invocation (R90-O6), device runs serialized with #77's lane. One-line commits, no attribution,
  no personal data; no absolute home path, owner-archive path or phone serial in any committed file.

## 1. Audit additions (planner and brief review, checked on 79a1ee3d)

(1) **Two more classes.** `AppSmokeTest.kt:249` declares `DeepLinkSmokeTest` (a cold-start deep link through
`MainActivity`'s `singleTask` path, 1 case) and `MaintenanceShellTest.kt:471` declares `MaintenanceTabTest` (the real
bottom bar on `createAndroidComposeRule<MainActivity>()`, 1 case); neither was ever gated (the #72 gate selected the other
class in each file). The tree holds **65 classes in 63 files**; the manifest and the tripwire count per class declaration.
`MaintenanceShellTest` itself uses `createComposeRule()` and records callbacks, so its "four sections" case names no
boundary and moves (M5). (2) **The JVM that runs `:app`'s tests is the Gradle daemon's**: `gradle/gradle-daemon-jvm.properties`
pins JetBrains 25 (foojay-provisioned, CI included); `setup-java 17` only launches the wrapper; `:app` has no toolchain
(`:core` has 17). (3) **Screens take the production graph.** Every moved screen's entry point is `XScreen(graph: AppGraph,
…)` building its view model inside (`viewModel { XViewModel(graph) }`); `AppGraph` is final and builds Room on the framework
driver (`di/AppGraph.kt:216-230`); `FakeGraph` is a separate class. Every such view model has a primary constructor over
ports, which is how the shipped `*ViewModelTest`s build them from `FakeGraph` — so a content composable that takes the view
model is hostable on the JVM, and that is C11's seam shape. Four bodies also compose graph-bound children (`AssetDetailScreen`
:309, :314, :363, :418, :430; `MaintenanceSheet` :216, :222; `ServiceCaseScreen` :121, :126; `SettingsScreen` reads
`graph.prefs`, `graph.attachmentStorage` and `graph.attachments` inline, :98-154): C11 exposes only their graph-free parts
and escalates the rest (X1-E1–E4). (4) The edit sheet closes on `_saved`, fired only for `Ok` or `Unchanged`
(`AttachmentsSectionViewModel.kt:220-224, :291-306`); after a real rename "sheet gone" proves the write landed, and the row
still needs a wait. (5) Only `awaitText` is shared (`AppSmokeTest.kt:116`, `internal`, 39 files, 41 classes); its timeout
`TIMEOUT_MS` is `private` there (:37). `awaitGone` matching a field can only wait longer, so it stays. (6) `ReleaseProofPolicyTest`
scans `tools/` for `uiautomator`, `input tap|text`, `dumpsys` (:80-90); `tools/` and `app/src/androidTest` are already its
declared inputs, `core/src/test` is not. (7) **Case by case (R90-O2)** thirteen single keeps in category-c classes name no
boundary and move; three cases A§2 moved stay (`AssetsIndicatorsTest.aNarrowLargeFontRowWraps`,
`AssetsFiltersTest.aLargeFontWrapsTheRowWithoutClipping` — font scale; `LendingSectionDeviceTest.theBackKeyOnTheRationaleIsNotNow`
— back dispatch to the dialog's window). (8) `ScanSheetTest.markOperationalSurvivesARotationMidWrite` uses
`StateRestorationTester` (:594), an emulated restore: its device dependence is the framework-SQLite write lock held across
it. (9) `HealthPluralsContractTest` cases 1–2 read the app's plurals through `Resources`, which Robolectric serves from the
real android-all; it is a JVM candidate (M39). (10) `AssetModelDeviceProofTest.theBottomBarHasTwoTabs…` repeats
`NavigationSmokeTest` except the app-bar Back landing on Settings (:325-328) → H8. (11) A§1's per-invocation costs, 3.6 s
Gradle overhead and 0.56 s client time, are the only multipliers §3 uses.

## 2. Contracts

- **C1, the JVM Compose foundation (R90-O1, R90-Y2).** Catalog: one `robolectric` entry (`org.robolectric:robolectric`);
  `:app` gains `testImplementation` of `platform(libs.compose.bom)` (2026.08.00), `libs.compose.ui.test.junit4`,
  `libs.androidx.test.ext.junit` (1.3.0, bringing `androidx.test:core`) and `libs.robolectric`; no plugin and no second AGP,
  Kotlin or Compose version. `testOptions.unitTests.isIncludeAndroidResources = true`; `isReturnDefaultValues` and the
  native-access argument stay; **extra `jvmArgs` (`--add-opens`, the flags Robolectric's notes name) and a `maxHeapSize`
  on the unit-test tasks are allowed**, each recorded in B1's report. `app/src/test/resources/robolectric.properties` holds
  `sdk=` and `application=android.app.Application` (safe: only the three activities cast to `ServiceTagApp`). **Version
  rule:** the newest Robolectric that runs the pinned SDK on the JVM that runs `:app`'s tests — the daemon JDK named in
  `gradle/gradle-daemon-jvm.properties`, JetBrains 25 at 79a1ee3d, locally and in CI; SDK 36 (the targetSdk) if it runs,
  else the highest that does. If no Robolectric runs on JDK 25, B1 stops and reports; a different JDK for `:app` is a
  separate ruling, never pinned in B1. JVM Compose classes use `AndroidJUnit4` (or `RobolectricTestRunner`), are named
  `…ComposeTest`, and host graph-free content: state-in composables, or C11's content composables with view models built
  from `FakeGraph`'s ports — never `AppGraph`, the framework driver, WorkManager or a real `NotificationManager`; a test
  with no database is preferred. **Runtime budget, provisional (Z1):** Robolectric Σ case time ≤ 60 s and the gate's JVM
  step wall ≤ 120 s (45 s today); B3's report measures both after its moves, and the controller fixes them against R90-O4
  before B7. `:app:testDebugUnitTest` is the JVM entry point (CI, R1, the gate); B1 runs `:app:testReleaseUnitTest` once
  and, if the release variant cannot host `ComponentActivity` (its manifest artifact is `debugImplementation`), excludes
  `**/*ComposeTest*` from that task (build wiring) and says so in `docs/release-proofs.md`.
- **C2, shared Compose helpers and a display-only wait (R90-O8).** `app/src/sharedTest/kotlin`, added to both the `test`
  and `androidTest` source sets (Android Studio warns of duplicate content roots; Gradle is unaffected), holds only helpers
  whose imports are Compose ui-test, JUnit and Kotlin. `awaitText` moves there with its FQN unchanged (package
  `com.loosecannon.servicetag.ui`, `internal`), so no caller's import moves, and matches displayed text only. A caller
  that relied on a field waits on the field explicitly at its site (`hasSetTextAction() and hasText(…)`).

```kotlin
// app/src/sharedTest/kotlin/com/loosecannon/servicetag/ui/ComposeWaits.kt (B1) — the pinned semantics
private const val WAIT_MS = 10_000L   // its own constant: AppSmokeTest's TIMEOUT_MS is private there

internal fun hasDisplayedText(text: String): SemanticsMatcher =
    SemanticsMatcher("displayed text == \"$text\"") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }   // a field's own value is never consulted, so a sheet still holding a typed name cannot satisfy a wait

internal fun ComposeTestRule.awaitText(text: String, count: Int = 1) =
    waitUntil(WAIT_MS) { onAllNodes(hasDisplayedText(text)).fetchSemanticsNodes().size >= count }
```

- **C3, the flaky case, test-only (R90-O8; AC 5).** `AttachmentsDeviceProofTest.theSheetRenamesAndReKindsTheRow`: close
  the keyboard before Save (a test API); after the tap wait until the sheet is gone (its `KIND` header absent), a state only
  a landed or no-op save produces, and this re-kind is never a no-op (§1(4)); then wait for the row carrying both the new
  name and `Manual · 2.0 KB · <today>`; then scroll and assert. The behaviour then moves (M15 a).
- **C4, the manifest `tools/gate/device-classes.tsv` — AC 1's record and the one list the gate reads.** Tab-separated,
  FQCN-sorted, one row per instrumented **class declaration** ever classified (65); header `# list-version <n>` and a
  column line. Columns: `class`; `tier` — `gate`, `release` (R2 only, genuinely release-specific, R90-O3), `moved`; `kind`
  — `platform` (a), `integration` (b), `jvm` (moved), and while #90 is in flight `pending-move`, removed by B7; `tests` —
  the `@Test` count inside that class's body, comments and KDoc excluded (0 when moved); `budget_s` — 15, never above;
  `boundary` — what the remaining cases alone show (`pending M<n>` on a `pending-move` row, `-` when moved); `jvm_homes` —
  the JVM classes owning its moved behaviours, relative to `com.loosecannon.servicetag.` (required for moved and split
  rows, else `-`). `list-version` increments with every commit that changes a gate row's tier or tests.

```text
# list-version 1
class	tier	kind	tests	budget_s	boundary	jvm_homes
com.loosecannon.servicetag.backup.PreservedSetRestoreTest	release	platform	1	15	owner archive restored on framework SQLite (staged before R2)	-
com.loosecannon.servicetag.ui.DeepLinkSmokeTest	gate	integration	1	15	cold start by deep-link intent through MainActivity singleTask	-
com.loosecannon.servicetag.ui.JournalDeviceProofTest	moved	jvm	0	-	-	ui.journal.EventEntryViewModelTest ui.journal.JournalFormatTest backup.RestoreProofTest
```

- **C5, the tripwire `DeviceClassManifestTest`** (JVM, `app/src/test/…/policy/`, inside R1 and CI; `core/src/test` and the
  manifest become declared inputs of `:app`'s test tasks — `tools/` and `app/src/androidTest` already are). It fails when
  (i) a class declaration with a `@Test` under `app/src/androidTest` has no gate or release row; (ii) a gate or release row
  names a missing class or its `tests` differs from the class body's count; (iii) a moved row's class still exists, or a
  `jvm_homes` class exists under neither `app/src/test` nor `core/src/test`; (iv) a gate or release row has no boundary or
  a budget outside 1–15; (v) the header or column line is missing; (vi) a kind is outside the allowed set; (vii) a class
  under `app/src/test` run by `AndroidJUnit4` or `RobolectricTestRunner` is not named `…ComposeTest`; (viii) from B7, the
  release rows are other than exactly `PreservedSetRestoreTest` (a new release row edits the tripwire in the open, C9.5).
- **C6, the gate script `tools/gate/merged-tip-gate.sh <checkout> [<outdir>] [<fqcn> …]`** (committed; replaces the
  per-issue `run.sh`). Default `<outdir>` = `<checkout>/.superpowers/gate-runs/<short tip>-<UTC time>` (git-ignored,
  outside `ReleaseProofPolicyTest`'s scan, never tmpfs). Steps: record the start; `./gradlew --stop`; prepare the emulator;
  `python3 -m unittest discover -s tools/gate` (a broken reporter stops the gate); delete every `*/build/test-results`;
  the JVM step `:core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --rerun-tasks --no-build-cache`; the
  sender install; per gate row in manifest order, delete the connected results directory, run `:app:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`, keep its exit code, XML and bracketed start and end as UTC epoch seconds;
  the MCP pytest; the reporter; `GATE DONE`. Every step's exit code is recorded. The script's exit is the reporter's. The
  class list is the manifest's gate tier (no FQCN in the script); a trailing FQCN list is a **subset run** (a brief's):
  every per-class rule applies, the Σ targets are printed, not enforced. No owner-archive push, no absolute path
  (`ANDROID_HOME` from the environment, fail fast if unset), `TMPDIR` under `<outdir>`, `ANDROID_SERIAL=emulator-5554` on
  every Gradle and adb call.
- **C7, the reporter `tools/gate/gate_report.py`** (python3 standard library) **and `tools/gate/test_gate_report.py`**
  (unittest, synthetic inputs). It prints one row per gate class — `class | kind | tests/expected | exec s | wall s | max
  case s | budget | OK / OVER / COUNT / FAIL` — then the JVM step wall and Robolectric Σ, **device wall = first connected
  start → last connected end** (the definition behind A§1's 12.1 min) against 600 s, whole (start → now) against 900 s,
  `list-version`, a `headroom low` notice when device headroom < 2 min or whole < 3 min (a reporting norm, never a verdict
  change, R90-C96), and one verdict (R90-O4, R90-Y3):
  - **FAIL** (exit 1, hard): a non-zero exit of any step — JVM, sender install, any connected invocation, MCP; a result XML
    missing, older than its step's start (its `timestamp`, read as UTC when it carries no zone — the connected XMLs carry none — else
    the file's mtime, against the UTC bracket), or reporting zero
    executed tests; a failure, error or skip; `tests` ≠ the manifest's; a test case whose `classname` is not the requested
    class; a gate row with no run; on a full run, device > 600 s or whole > 900 s.
  - **INVESTIGATE** (exit 3): a class over its `budget_s`, a case over 6 s, or a C1 JVM budget over, each with its number;
    diagnosis in the report is mandatory; it does not by itself invalidate correct code.
  - **PASS** (exit 0): neither. A **clean** run (AC 3) is PASS.
- **C8, the move rule (R90-O3, R90-O5, R90-X1; AC 2).** A moved behaviour's home is a view-model, presenter or read-model
  test first; JVM Compose (C1) for what a composable decides — drawn words, enabled state, which control shows, which
  callback fires — hosting a graph-free composable or a C11 content composable. A newly written JVM case keeps the device
  case's method name when it carries the same behaviour. **No weakening:** the replacement asserts every literal, count,
  order and write the device case asserted for that behaviour, less only what its manifest boundary names. **One proven
  mutation per moved behaviour** (§4): a real temporary edit to production code, the named JVM class run with `--rerun`,
  the failing assertion quoted, the edit reverted; an existing JVM case counts without a replay when a shipped plan's row
  or a brief report records it killing that mutation, cited. **Order:** the seam commits (C11), then the JVM cases and the
  records, then — in a later commit of the same brief — the device deletions with their manifest rows. A case found to
  depend on the platform stays, re-classified and reported (≤ 3 per brief, then stop and report). The report carries the
  map device case → behaviour → JVM case(s) → mutation → RED quote or record.
- **C9, the device-test admission rule (#77 onward; written into the planning policy by B2).**
  1. A new instrumented case names in its manifest row the OS or device boundary it alone shows: NotificationManager,
     AlarmManager or WorkManager; a URI grant or foreign UID; SAF or a documents provider; manifest, intent or package
     resolution; framework SQLite or providers; framework resources only where Robolectric cannot reproduce them (a real
     device configuration, density or font); process or activity lifecycle; the real Navigation 3 or activity back stack
     where an in-process test is insufficient; real text layout, font scale, IME or back dispatch — or it is a case of
     `AppSmokeTest`, `DeepLinkSmokeTest`, `JournalSmokeTest` or `NavigationSmokeTest`.
  2. One case per boundary per feature; every semantic variation is a JVM test in a C8 home.
  3. ≤ 6 s per case and ≤ 15 s per class of execution; a new class enters the gate tier with its count in the same commit.
  4. JVM mutations wherever the property is observable below the platform; a device mutation only where it is not, capped.
  5. The release tier holds genuinely release-specific proofs (owner data) only, never a UI test.
  6. A feature whose gate run prints `headroom low` quotes that line in its report and names what it will move down next.
- **C10, docs (AC 6; B2).** `docs/release-proofs.md` gains "The ordinary merged-tip gate": the command and default
  output, the manifest, C7's verdicts and budgets, the two clocks (R90-O7), the subset run, C9 by reference, and the
  `testDebugUnitTest` note (C1); R2's row says the connected suite is the gate and release tiers; the R4 row and "R4 in
  full" do not move. `docs/superpowers/planning-policy.md` gains **Amendment (#90, 2026-09-28)** under the testing
  hierarchy: layer 2's in-process Compose tests run on the JVM under Robolectric; instrumented Compose serves C9's
  boundaries only; the bounded gate, budgets and C9; the seam rule (C11); every brief's mutation cap, time box and
  stop-and-report. Ratified text above it is not rewritten.
- **C11, the named seams (R90-X1).** Two shapes only. **Visibility:** named `private` composables become `internal`, no
  other character changes. **Extraction:** in `XScreen(graph, …)` the view-model statement stays; every statement after it
  moves verbatim, in order, into `internal fun XContent(model: XViewModel, <the screen's remaining parameters, same names,
  types and defaults>)`, and `XScreen` calls it once with the same arguments; a moved block's free read-only locals become parameters
  of the same names and values (S17 only: `context`). The new function carries the screen's `@Composable` and, where the
  screen has it, a copied `@OptIn(ExperimentalMaterial3Api::class)` (both compile-time only). The production screen keeps
  calling exactly that path.

```kotlin
// S10's shape (B7), for illustration — every extraction seam looks like this
fun CategoriesScreen(graph: AppGraph, onBack: () -> Unit) {
    val model: CategoriesViewModel = viewModel { CategoriesViewModel(graph) }   // unchanged
    CategoriesContent(model, onBack)                                             // the one new line
}

@Composable
internal fun CategoriesContent(model: CategoriesViewModel, onBack: () -> Unit) {
    /* the statements that followed the view-model line, moved verbatim, in order */
}
```

| seam | file (`ui/…`) | change | brief | rows |
|---|---|---|---|---|
| S1 | `maintenance/MaintenanceSheet.kt` | **escalated, X1-E4 (open, §7):** the `Scaffold` block assigns three delegated locals (:169, :170, :188), so it cannot move verbatim; recommended S1′, visibility only: `ConditionBlock`, `ConditionActions`, `ComponentRow`, `HealthBlock`, `TagPlacementLine`, `SheetItemRow`; alternative: a hoisted-setter adaptation the owner authorises by name | B3 (S1′ if ruled) | M1 b |
| S2 | `scan/TagResultSheet.kt` | extraction `TagResultSheetContent` (graph only in the view-model statement, :96) | B3 | M1, M36 |
| S3 | `maintenance/ScheduleEditScreen.kt` | extraction `ScheduleEditContent` (:219-221) | B3 | M2 |
| S4 | `dashboard/DashboardScreen.kt` | extraction `DashboardContent`; the `health` default stays on the screen | B3 | M4 |
| S5 | `maintenance/MaintenanceScreen.kt` | extraction `MaintenanceContent` (:74) | B4 | M5 |
| S6 | `condition/ChangeConditionSheet.kt` | extraction `ChangeConditionSheetContent` (:98-100) | B4 | M7, M9 e |
| S7 | `asset/AssetDetailScreen.kt` | visibility: `AssetPlate`, `PartOfLine`, `DetailsSection`, `WarrantySection`, `ComponentsSection`, `ConditionSection`, `HealthSection`, `SeasonSection`, `SeasonDialog` | B4 | M9, M13, M20, M28, M29 |
| S8 | `asset/AssetEditScreen.kt` | extraction `AssetEditContent` (:264-266) | B4 | M10, M21, M30 |
| S9 | `health/HealthSubjectEditScreen.kt` | extraction `HealthSubjectEditContent` (:170-172) | B4 | M10 d, M11 |
| S10 | `settings/CategoriesScreen.kt` | extraction `CategoriesContent` (:105) | B7 | M23 |
| S11 | `asset/AssetsScreen.kt` | extraction `AssetsContent` (:87) | B6 | M17 c, M18 |
| S12 | `backup/BackupScreen.kt` | extraction `BackupContent` (:78) | B7 | M25 |
| S13 | `service/ServiceCaseEditScreen.kt` | extraction `ServiceCaseEditContent` (:58-60) | B7 | M26 a |
| S14 | `service/ServiceCaseScreen.kt` | visibility: `UpdateSheetContent`, `TimelineEntry` (`LinkedDocuments` stays private, X1-E3) — **held**: with `addUpdateSheetAndTimeline` kept under E3 (C-5) no row needs it; created only if the owner rules E3 otherwise | — | — |
| S15 | `loan/LendingSection.kt` | extraction `LendingSectionContent` (:54) | B7 | M28 |
| S16 | `loan/LoanEditScreen.kt` | extraction `LoanEditContent` (:69-71) | B7 | M28 |
| S17 | `api/DeveloperApiScreen.kt` | extraction `DeveloperApiContent` (the view-model block :79-87 stays; `context`, :78, becomes a same-name parameter) | B7 | M31 |
| S18 | `condition/MarkOperationalDialog.kt` | extraction `MarkOperationalDialogContent` (view model :117-119; no graph after it) | B3 | M1 d, M9 a |

**Seam neutrality — a named review item in B3, B4, B6 and B7.** Each seam is its own commit, touching only its file,
before any test that uses it; the reviewer inspects the seam commits apart from the migrated tests and runs, per seam commit
`C` and its file `F`: (1) `git show --stat C` lists only `F`; (2) the string literals are unchanged: `diff <(git show C^:F |
grep -oE '"([^"\\]|\\.)*"' | sort) <(git show C:F | grep -oE '"([^"\\]|\\.)*"' | sort)` → no output; (3) `git show C |
grep -nE '^\+.*(BuildConfig|VisibleForTesting|isTest|testOnly)|^\+(public )?fun [A-Z]'` → no output; (4) mechanically, the
dedented removed lines of `git diff -U0 C^ C -- F`, as a multiset, equal the dedented added lines less the new signature, the
one call, the `private`/`internal` words, the copied `@Composable` and `@OptIn(ExperimentalMaterial3Api::class)` annotations
and imports. Runtime evidence: after the seam commits, and before any move, the brief runs at least the device classes it
lists, plus every class `git grep` finds naming the seamed screen or launching `MainActivity` or `ServiceTagRoot`, green.

## 3. The bounded gate and its numbers

**Gate tier after #90: 43 classes, 134 cases** — 36 / 111 measured on the #72 gate plus seven never-gated / 23 (R90-9):
- **platform (16):** `ReminderPlatformDeviceProofTest` 12, `QuickActionDeviceProofTest` 2, `ReminderHealthScreenTest` 3,
  `ShareBoundaryTest` 3, `ContactGrantBoundaryTest` 2, `ShareResolutionContractTest` 4, `ContactLinkContractTest` 1,
  `Format7RestoreContractTest` 2, `SafTreeAttachmentStoreContractTest` 10, `NfcIdentityDeviceProofTest` 4,
  `DeveloperApiListenerTest` 7 of 10, `AssetEditorWarrantyReminderTest` 1 of 3 (the real NotificationManager);
  `TagIdentityDispatchTest` 3, `RemovedSurfacesTest` 2, `SharedItemLiftTest` 7, `HealthPluralsContractTest` 3 (JVM
  candidate, M39).
- **integration (27):** smokes `AppSmokeTest` 5, `DeepLinkSmokeTest` 1, `JournalSmokeTest` 2, `NavigationSmokeTest` 3 (+
  H8); navigation `MaintenanceTabTest` 1, `GroupScreensTest` 2 of 6, `AssetServiceCasesTest` 2 of 3,
  `SeasonReconciliationNavigationTest` 1, `ScanSheetIncidentNavigationTest` 1, `InspectBackDismissesTheAnswerTest` 1,
  `InspectNamesABoundTagTest` 3, `ReaderModeHoldTest` 3; documents `AttachmentsDeviceProofTest` 6 of 12; layout, IME, back,
  lock `AssetEditorCategoryPickerTest` 1, `AssetsIndicatorsTest` 2 of 11, `AssetsFiltersTest` 2 of 6,
  `AssetDetailKeyDocumentsTest` 2 of 6 (one with E1), `DocumentsDescriptionLineTest` 1 of 4, `IncidentOfferDialogTest` 1 of 3,
  `EditorsDeviceProofTest` 1 of 6 (IME Next), `AssetEditorSeasonAndHealthTest` 1 of 7 (back gesture),
  `LendingSectionDeviceTest` 1 of 5 (back key), `ScanSheetTest` 11 of 18 (§1(8); ten with E4), `ActionGridTest` 6; escalated AppGraph
  hosts (X1-E1–E3) `AssetDetailConditionHealthSeasonTest` 3 of 10, `SettingsBackupEntryTest` 3, `ServiceCaseScreensTest` 2
  of 3.

**Release (1):** `PreservedSetRestoreTest`. **Moved (21):** `JournalDeviceProofTest`, `AssetModelDeviceProofTest`,
`MaintenanceShellTest`, `AssetWarrantySectionTest`, `EmptyStoreRestorePromptTest`, `ShareIntakeScreenTest`,
`AssetEditorKeyDocumentsTest`, `AssetsSearchTest`, `ChangeConditionSheetTest`, `DashboardAttentionTest`,
`ScheduleEditorTest`, `ScheduleOperationsTest`, `CategoriesScreenTest`; from R2 only `AssetTagsSectionTest`,
`ComponentsSmokeTest`, `HealthSubjectEditorTest`, `ReferencesSectionTest`, `OverwriteSheetSubjectTest`,
`PreSplitLinkTagSheetTest`, `ReadScopedSheetOwnerTest`, `WriteTagScreenConsentWordingTest`. **Split: 16; kept whole: 28;
moved: 21 → 65.** 181 device cases move (153 from the gate, 28 from R2 only); M39 would move 3 more and one class.

| | #72 gate (A§1, measured) | after #90 (projection: #72 case times × A§1 costs) |
|---|---|---|
| device classes / cases | 50 / 265 | 36 / 111, plus 7 / 23 never measured (R90-9) |
| Σ case execution | 518.7 s | 159.3 s, plus the seven |
| device wall (first start → last end) | 728 s (12.1 min) | 159.3 + 36 × (3.6 + 0.56) ≈ 309 s (**5.2 min**); the seven ≈ 65 s more (brief review's estimate) → ≈ **6.2 min** |
| JVM step | 45 s | ≤ 120 s (provisional) |
| whole (start → GATE DONE) | 786 s (13.1 min) | 45–120 + 2 + ≈ 374 + 10 s ≈ **7.2–8.4 min** |

Kept extremes: classes `DeveloperApiListenerTest` 11.9 s, `AppSmokeTest` 11.6 s, `AttachmentsDeviceProofTest` 10.2 s; case
`AssetsIndicatorsTest.nothingClipsAtNarrowWidthOrLargeFont` 5.2 s — hence 15 s and 6 s. B2 measures the seven and takes a
second sample of that case; `ActionGridTest` over 15 s, or that case ≥ 5.5 s in B2 or B7, goes to the controller for a
ruling before the next brief (Z2, Z8).

## 4. Test matrix (a moved behaviour's letter is its mutation's letter)

| # | hazard / class (moved → kept) | test (class · case) or home [seam] | RED mutation |
|---|---|---|---|
| H1 | display-only wait (C2) | `SharedWaitsComposeTest` · `aFieldHoldingTheWordDoesNotSatisfyTheWait`; `aTextHoldingTheWordDoes`; `aMergedRowCountsOnce` | the matcher also reads the field's editable text |
| H2 | foundation (C1) | `SharedWaitsComposeTest.theHarnessHostsAPlainApplication`, also printing `java.version` and the Robolectric SDK for B1's report; `…theJvmIsTheDaemonsToolchain` asserts `Runtime.version().feature()` equals `toolchainVersion` read from `gradle/gradle-daemon-jvm.properties`, so every run, CI included, proves its own JVM | drop `application=`; compare with a constant instead of the file |
| H2b | two classloaders, one fork (C1) | `FakeGraphComposeTest.aRobolectricTestReadsFakeGraph` and a plain `FakeGraph` test in the same `testDebugUnitTest` run, both green | — (a bundled-driver clash fails it outright) |
| H3 | the flaky case (C3) | device `theSheetRenamesAndReKindsTheRow` green in B1 and B2's baseline; the vacuous wait is H1's RED; the race is not reproducible on demand (R90-O8) | — |
| H4 | the sweep (C2) | every one of the 41 classes calling `awaitText` green once in B1; adjusted sites listed | — |
| H5 | verdicts (C7) | `test_gate_report.py` · `test_a_clean_run_passes`; `…_class_over_budget_investigates`; `…_case_over_six_seconds_investigates`; `…_jvm_budget_over_investigates`; `…_device_over_600_fails`; `…_whole_over_900_fails`; `…_subset_enforces_no_sum_target`; `…_zero_tests_fails`; `…_count_mismatch_fails`; `…_skip_fails`; `…_missing_class_fails`; `…_foreign_classname_fails`; `…_nonzero_jvm_exit_fails`; `…_nonzero_sender_exit_fails`; `…_nonzero_connected_exit_fails`; `…_nonzero_mcp_exit_fails`; `…_stale_xml_fails` (a zone-less connected timestamp); `…_old_green_jvm_xmls_after_a_compile_failure_fail`; `…_device_wall_is_first_start_to_last_end`; `…_headroom_low_prints_without_changing_the_verdict`; `…_table_prints_tests_exec_and_wall_per_class` | `>=` for `>` at 600; a skip as a pass; drop the zero check; the XML's own count; ignore a step exit; ignore the timestamp; read a zone-less timestamp as local time; sum the brackets |
| H6 | tripwire (C5) | `DeviceClassManifestTest` · `everyClassDeclarationIsClassified`; `eachRowsCountIsItsClassBodysTestCount`; `aMovedClassHasNoDeclarationAndItsHomesExist`; `everyDeviceRowNamesItsBoundaryWithinBudget`; `theKindsAreTheAllowedSet`; `robolectricClassesAreNamedComposeTest`; `theReleaseTierIsThePreservedSetAlone` (B7) | per check, a temporary tree edit: an unlisted class, an extra `@Test`, a moved class restored, budget 16, a renamed Robolectric class, a second release row |
| H7 | gate script (C6) | B2's full run: every gate row reported, tests = expected; its first step runs H5 | — |
| H8 | the duplicate's step (§1(10)) | device `NavigationSmokeTest.settingsOpensReadInspectTag` gains "Back lands on Settings" verbatim; the duplicate goes after | — (carried, not moved down) |
| H9 | seam neutrality (C11) | per seam commit the four checks, (4) mechanical and allowing the copied `@Composable`/`@OptIn`; at least the listed hosts plus the grep's, green after the seams | — |
| **B3** M1 | `ScanSheetTest` (7 → 11) | `ScanSheetContentTest`, `MaintenanceSheetViewModelTest`, `CompletionFlowTest`, `TagResultViewModelTest`; `ScanSheetComposeTest` [S2, S18; b S1′ per E4] · a when it opens (an ambient tap with due work opens the sheet; a deliberate inspect opens the asset); b what it shows (a down component line; S109); c the completion question's view-model side (nothing for a completion after today or an ordinary arrival); d Cancel on Mark operational writes nothing. **Kept with E4** (the sheet body's own wiring and the effects outside it; the implementer confirms each by reading the case): `openAssetReachesTheOrdinaryDetail`, `theSheetOpensForDueWorkAndCompletesNothingByItself`, `doneOnAMeterReminderArrivesOnTheCompletionQuestion`, `aDownAssetWithNothingDueShowsConditionAndNothingDue`, `aCompletionOnADownAssetAsksMarkOperational`, `notNowWritesNothing`, `logIncidentOnlyNavigates`, `theScanSheetsSheetHandsTheDraftToItsHost`, `backFromTheIncidentEntryTheFlagIsReadAgain`, `completeSelectedWritesOneEventThroughTheCanonicalAffordance` | a open on a deliberate inspect; b drop the component line; c offer after today; d write on Cancel |
| M2 | `ScheduleEditorTest` (11 → 0) | `ScheduleEditViewModelTest`, `SchedulePolicyFormTest`; `ScheduleEditorComposeTest` [S3] · a the link guard; b None first and clearing, One-tap's picker; c season options by asset kind (a calendar asset's three with helpers; Start counting from under season start only; hidden for a group and a year-round asset without a break); d a group offers none of its three forbidden controls; e every ratified label verbatim, three warnings, days-before empty, retired words gone | a save past the guard; b None second; c the question on a group; d a forbidden control; e one label swapped |
| M3 | `ScheduleOperationsTest` (3 → 0) | `ScheduleDetailViewModelTest`, `GroupDetailViewModelTest` · a operations by kind (an asset schedule never offers Close; a group round offers Close and records nobody; a round obliging nobody offers neither) | Close on an asset schedule |
| M4 | `DashboardAttentionTest` (13 → 0) | `DashboardViewModelTest`, `DashboardFiltersTest`, `DashboardViewModelMaintenanceTest`, `AttentionReadModelTest`; `DashboardAttentionComposeTest` [S4; b, c, e on the internal `ConditionRow`, `HealthRow`, `LoanRow`] · a the fixed section order (with condition and health rows; only asset rows; the deferred section); b row words and units; c the health badge (info off, warn on); d condition chips, a promoted component's overdue work under a blank query, no search field; e the overdue-loan row after the ratified tiers | a swap two sections; b drop the unit; c badge on info; d filter out promoted work; e loan row before DOWN |
| **B4** M5 | `MaintenanceShellTest` (6 → 0) | `MaintenanceViewModelTest`, `WhyLinesTest`, `StatusVocabularyTest`; `MaintenanceShellComposeTest` [S5] · a row words (the health it drives; the why line; a round obliging nobody; no schedules says so); b the three quick actions, Log maintenance writes nothing; c each of the four sections reaches its callback | a drop the why line; b write on Log maintenance; c swap two section callbacks |
| M6 | `GroupScreensTest` (4 → 2) | `GroupDetailViewModelTest`, `GroupEditViewModelTest` · a the rounds checklist completes the selected member; b creation only with a member; c soft remove and re-add; d an archived group stays listed and marked | a every member; b offer with none; c hard delete; d drop archived |
| M7 | `ChangeConditionSheetTest` (5 → 0) | `ChangeConditionViewModelTest`, `ConditionWordsTest`; `ChangeConditionSheetComposeTest` [S6] · a three options, helpers, Save; b Down asks, Save condition records one row, Operational asks nothing; c Cancel writes nothing, Log incident details reaches the host and writes nothing | a drop a helper; b two rows; c write on Cancel |
| M8 | `IncidentOfferDialogTest` (2 → 1) | `OffersTest`; `IncidentOfferDialogComposeTest` · a title, body, three answers, each reaching its callback and disabling the others | the others stay enabled |
| M9 | `AssetDetailConditionHealthSeasonTest` (7 → 3, X1-E1) | `AssetViewModelsTest`, `AssetSeasonActionsTest`, `AssetHealthReadModelTest`, `ConditionIncidentAffordanceTest`; `AssetDetailComposeTest` [S7, S6, S18] · a condition section and actions (a down asset without an incident leads with Log incident; the Mark operational dialog's title, sentence and Cancel) — `logIncidentOpensAnIncidentEntry` stays with E1 (its `onLogOutcome` wiring is the body's, :377, C-5); b health order (criticals, components aggregate, contributors, footer); c season lines (calendar; the manual start dialog and history); d S99's day forms; e the details sheet hands its draft to the host | a lead on an operational asset; b swap criticals and components; c drop history; d one day form; e drop the draft |
| M10 | `AssetEditorSeasonAndHealthTest` (6 → 1) | `AssetSettingsFormTest`, `AssetSetupViewModelTest`, `HealthSubjectEditViewModelTest`; `AssetEditorComposeTest` [S8, S9] · a season options and fields, the break toggle; b the manual question; c the retired season sentence gone; d health subjects and combine | a drop a field; b skip the question; c restore it; d combine wrongly |
| M11 | `HealthSubjectEditorTest` (3 → 0, R2 only) | `HealthSubjectEditViewModelTest`; `HealthSubjectEditorComposeTest` [S9] · a empty thresholds disable Save; b the starting-point confirmation; c archive and restore | a enable Save; b skip it; c no restore |
| **B5** M12 | `JournalDeviceProofTest` (8 → 0) | `EventEntryViewModelTest`, `JournalFormatTest`, `AssetSetupViewModelTest`, `RestoreProofTest` · a the current reading (delete falls back; backdated sits below; edit in place); b the hot-tub template seeds readings a water test fills, the UPS load test has no targets and Passed yes; c the mower oil change records the meter and both materials; d a template-less asset set up later; e the backup round trip keeps every count | a latest by insertion; b targets on the UPS; c a material dropped; d refuse setup; e a table dropped |
| M13 | `AssetModelDeviceProofTest` (8 → 0; H8) | `AssetViewModelsTest`, `AssetSetupViewModelTest`, core use-case tests; `AssetDetailComposeTest` [S7] · a a parent lists its components, each names its parent; b reparent under a sibling, no descendant offered; c a parent's delete refused by name, archiving leaves the children; d retirement commits before the event is offered, declining changes nothing; e a grouped price in minor units at the currency's precision; f an out-of-season window badges asset and row; g a category hint pre-selects a template, never over an explicit choice | a drop the name; b a descendant; c delete it; d offer first; e two decimals always; f badge in season; g override |
| M14 | `EditorsDeviceProofTest` (5 → 1) | `ProfileEditViewModelTest`, `DefinitionEditViewModelTest`, `EventEntryViewModelTest` · a a custom reading and action drive an entry; b a reading with data and a dependent refused by name; c archiving a source empties the derived row, history untouched; d a rejection from one test, never combined; e the format-3 round trip keeps the derived reading | a drop the action; b allow; c rewrite history; d combine; e drop it |
| M15 | `AttachmentsDeviceProofTest` (6 → 6) | `AttachmentsSectionViewModelTest`, `DocumentsSectionTest`, `BackupViewModelTest` on `FakeAttachmentStorage` · a rename and re-kind (C3's case); b delete removes the row and its file; c an event's files go with the entry; d a data-only restore says not on this device, another set's archive is refused; e no folder points at Settings | a keep the kind; b keep the file; c leave the files; d accept it; e hide the pointer |
| M16 | `AssetsIndicatorsTest` (9 → 2) | `AssetRowHealthTest`, `AssetViewModelsTest`, `AssetLoansStateTest`; `AssetsIndicatorsComposeTest` (internal `AssetListRow`) · a badge order; b presence (untagged, OK tracked, tagged nominal, loan-only, lent and overdue words); c three distinct bands; d the disc takes no tap | a loan first; b a disc untagged; c merge bands; d a click on the disc |
| **B6** M17 | `AssetsFiltersTest` (4 → 2) | `AssetViewModelsTest`; `AssetsFiltersComposeTest` [internal `AssetsFilterRow`; c S11] · a the type dropdown lists All and the catalog and filters; b the Archived chip's semantics; c Components on restores the part | a filter on the label; b invert; c drop the part |
| M18 | `AssetsSearchTest` (3 → 0) | `AssetViewModelsTest`; `AssetsFiltersComposeTest` [S11] · a an archived-only match shows the hint; b an empty box is not told the search found nothing; c a component names its system, a search narrows to it | a no hint; b "found nothing"; c drop the system |
| M19 | `DocumentsDescriptionLineTest` (3 → 1) | `DocumentsSectionTest`; `DocumentsSectionComposeTest` · a the second line (present, none, missing bytes) | describe a missing row |
| M20 | `AssetDetailKeyDocumentsTest` (4 → 2, one with X1-E1) | `AttachmentsSectionViewModelTest`, `DocumentsSectionTest`; `AssetDetailComposeTest` [S7] · a the details name the purchase document (`aReceiptShowsInKeyDocumentsAndInDocuments…` stays with E1: it opens through `AttachmentsSection(graph…)`, C-5); b a rename keeps the role, the chosen role is saved; c role chips on an asset row only | a name another document; b clear the role; c roles on an event row |
| M21 | `AssetEditorKeyDocumentsTest` (4 → 0) | `AssetSetupViewModelTest`, `AttachmentsSectionViewModelTest`; `AssetEditorComposeTest` [S8] · a the card once without a folder; b stage and remove, receipt in Purchase, manuals in Key documents; c Cancel leaves no row and no file | a twice; b wrong slot; c write on Cancel |
| M22 | `ShareIntakeScreenTest` (13 → 0) | `ShareIntakeViewModelTest`; `ShareIntakeScreenComposeTest` (`ShareIntakeScreen(state)` is graph-free) · a the ratified labels and nothing else; b controls by kind; c Save enabling; d the sentences | a an extra label; b role on a link; c Save with a blank name; d swap two sentences |
| M32 | `ReferencesSectionTest` (12 → 0, R2 only) | `ReferencesSectionViewModelTest`; `ReferencesSectionComposeTest` · a header, count, empty state; b kind words, description line; c a blocked row never handed on, a stored unknown scheme opens once, a missing handler without the URI; d overflow, two edit fields, remove asks; e the add sheet's five strings, the scheme question | a no count; b swap words; c hand it on; d a URI field; e name the URI |
| M33 | `ComponentsSmokeTest` (4 → 0, R2 only) | `ComponentsComposeTest` · a plate dashes, ledger date and title, header title; b the badge's label reaches accessibility | a blank; b drop it |
| M34 | `AssetTagsSectionTest` (3 → 0, R2 only) | `AssetTagsSectionComposeTest` (internal `TagsSection`) · a tags with label and status; b an edit records id and label only, Cancel nothing | a no status; b the whole row |
| M35–38 | `OverwriteSheetSubjectTest` 2, `PreSplitLinkTagSheetTest` 1, `ReadScopedSheetOwnerTest` 1, `WriteTagScreenConsentWordingTest` 2 (→ 0, R2 only) | `TagResultViewModelTest`; `ScanSheetsComposeTest` [M36: S2] · M35 line and quiet identifier; M36 an old link tag says so, offers nothing; M37 each read resolves again, the previous model cleared; M38 lift and re-tap, never keep holding | M35 an empty identifier; M36 an action; M37 key on the screen; M38 swap |
| **B7** M23 | `CategoriesScreenTest` (7 → 0) | `CategoriesViewModelTest`; `CategoriesScreenComposeTest` [S10] · a rename held while blank or unchanged, refusals under the field; b empty catalog, usage, built-ins without a menu; c in-use delete refused, unused asks then removes | a enable unchanged; b a built-in menu; c delete in use |
| M24 | `SettingsBackupEntryTest` (0 → 3, X1-E2) | escalated; no move | — |
| M25 | `EmptyStoreRestorePromptTest` (2 → 0) | `BackupViewModelTest`; `BackupScreenComposeTest` [S12] · a an empty store confirms plainly, one row types REPLACE | invert the emptiness test |
| M26 | `ServiceCaseScreensTest` (1 → 2, X1-E3) | `ServiceCaseEditViewModelTest`; `ServiceCaseComposeTest` [S13] · a the editor's fields, chips, refusals; `addUpdateSheetAndTimeline` stays with E3 (its Add update wiring is `ServiceCaseScreen`'s body, C-5) | a drop a refusal |
| M27 | `AssetServiceCasesTest` (1 → 2) | `AssetCasesStateTest` · a a new case with a current incident opens the editor on it | ignore the incident |
| M28 | `LendingSectionDeviceTest` (4 → 1) | `AssetLoansStateTest`, `LoanEditViewModelTest`, `LoanReturnTest`; `LendingSectionComposeTest` [S15, S16, S7] (fake registry via `LocalActivityResultRegistryOwner`) · a a lend through a fake picker shows the block and the plate; b Mark returned moves it to Past loans; c the rationale, Not now writes nothing; d no picker says P72-45 | a no plate; b stays open; c request after Not now; d a snackbar |
| M29 | `AssetWarrantySectionTest` (2 → 0) | `AssetWarrantyStateTest`; `AssetDetailComposeTest` [S7] · a In, Out, Not recorded; the retired suffix gone | the boundary day; the suffix back |
| M30 | `AssetEditorWarrantyReminderTest` (2 → 1) | `AssetEditWarrantyReminderTest`; `AssetEditorComposeTest` [S8] · a the lead's helper and refusal; b the rationale, Not now writes nothing | a a negative lead; b request after Not now |
| M31 | `DeveloperApiListenerTest` (3 → 7) | `DeveloperApiViewModelTest`; `DeveloperApiComposeTest` [S17] (the fake socket factory the device class uses) · a cannot start says so, a dying listener shows S6; b a denied permission explains and offers Settings | a swap the sentences; b Settings on another failure |
| M39 | `HealthPluralsContractTest` (3 → 0 or 3) | `HealthPluralsComposeTest` · a the app's plurals through Robolectric's `Resources` (one day, n days, any device language) and a future-dated replacement drawing zero days ago | a edit a quantity string: if the JVM case goes RED the class moves; if not it stays platform and the report says why |

Behaviours: B3 15, B4 23, B5 26, B6 30, B7 16 (110, M39 included). A brief may split or merge behaviours with a reason,
never drop one; homes are named from the shipped tests and the audit.

## 5. Lanes, files and collisions

One branch `issue-90` from this plan's ratified commit, B1 → B7, each `<base>` the previous accepted tip. **Files:** B1 the
catalog, `app/build.gradle.kts` test blocks, `robolectric.properties`, `app/src/sharedTest/**`, `AppSmokeTest.kt` (the helper
leaves), the sweep's call sites, `AttachmentsDeviceProofTest.kt`, the H1/H2b tests; B2 `tools/gate/*`, the tripwire,
`app/build.gradle.kts` inputs, the two docs; B3–B7 their §2 seams and §4 classes, the manifest, and in B7 the tripwire's
kinds and release pin. **Collisions — #77** runs in parallel and merges after #90. Until then it touches none of
`app/src/sharedTest`, `AppSmokeTest.kt`'s helpers, `tools/gate/**`, the policy tests, the catalog's or the build's test
entries, the two docs, or C11's seamed screen files beyond what its own feature needs (a hunk inside a moved body is
re-applied inside the content composable at #77's master merge); it adds no case to a class §3 moves
(`AssetModelDeviceProofTest`, `AssetDetailConditionHealthSeasonTest` and `DashboardAttentionTest` are among them). JVM homes
#90 extends (`AssetViewModelsTest`, `AssetSetupViewModelTest`, `DashboardViewModelTest`, `AssetLoansStateTest` and others)
take #77's additions additively; #77 resolves them at its master merge, adds its manifest rows (C5 forces it), brings every
new device case under C9 and runs `tools/gate/merged-tip-gate.sh` before its final gate. Device runs are serialized.

## 6. What this plan does not do, and records

No batching, orchestrator, `clearPackageData`, animation switch or runner change; no CI or release workflow change (the
reporter's unittests run inside the gate); no JDK change for `:app`; no new device case beyond H8's carried step; no product,
schema, format, API, MCP or string change. **Records:** (1) the batching follow-up (R90-O6): a selector proven to run every
selected class, C7's per-class count kept, measured against the per-class run on A§4's five classes; (2) the kept
`ServiceTagRoot`-in-`ComponentActivity` navigation cases and X1-E1–E3 are really AppGraph-host dependent — JVM candidates for
a later issue, not #90 (N7); (3) with `PreservedSetRestoreTest` release-only, owner-data restore is proven at R2 (staged,
zero skips) and no longer on merged tips, so a schema bump between releases is first exercised there (N6).

## 7. Owner rulings (2026-09-28; binding on every brief)

- **R90-O1 YES.** Robolectric and JVM Compose testing join `:app:test`; layout and font-scale fidelity that depend on
  Android rendering stay on the device. **R90-O2 YES, case by case, never by class name**: platform proofs and the smoke
  suites stay; mixed classes split and keep only cases with a real OS or device boundary. **R90-O3 YES**: a moved case is
  deleted once its JVM replacement is proven; no duplicate device tier; release-only for release-specific proofs.
- **R90-O4 YES, exact semantics** (C7): class or case over budget = investigate-and-report; device > 10 min or whole > 15
  min = hard; count mismatch, skip or missing class = hard. Numbers 15 s / 6 s (§3). **R90-O5 YES**: one proven mutation
  per moved behaviour; an existing JVM test that demonstrably kills it counts. **R90-O6 YES**: batching out. **R90-O7 YES**:
  start → `GATE DONE`; baseline 13.1 / 12.1 min. **R90-O8 YES**: fix the flake and the shared wait now, then move it.
- **R90-X1 (option a).** #90 may make narrowly scoped production changes solely to expose deterministic Compose content to
  JVM tests; each seam named (C11: file, composable, change). Prefer making an existing content composable `internal`; where
  extraction is required, move the existing body verbatim into an `internal` composable. No domain logic, state derivation,
  navigation semantics, user-visible behaviour or production call path changes; the production screen keeps calling exactly
  that path; no test-only flags, branches, fake behaviour or `if (BuildConfig…)`; no public test API. If extraction would
  change actual behaviour rather than mechanically expose composition, stop and escalate that screen. Reviewers inspect the
  seams apart from the migrated tests (H9, a named review item). Once the replacement is mutation-proven, the device case goes.
- **R90-Y1:** 65 classes; the tripwire counts classes; B1's sweep covers them. **R90-Y2:** `:app`'s tests run on the daemon's
  JetBrains 25; B1 verifies Robolectric and JVM Compose there first; JVM flags and heap allowed; no other JDK without a
  ruling. **R90-Y3:** a non-zero step exit and a missing, non-executed or stale XML are hard failures (C7, H5).
- **R90-9: admitted** — `TagIdentityDispatchTest`, `RemovedSurfacesTest`, `SharedItemLiftTest` (live), `HealthPluralsContractTest`
  (a JVM candidate, M39), `ActionGridTest`, `DeepLinkSmokeTest`, `MaintenanceTabTest`. **R90-C96:** headroom is a printed
  reporting norm, never a threshold (C7, C9.6). **AC 3:** two consecutive clean runs (§15).
- **Open — X1 escalations (recommended: keep on the device as AppGraph-host integration cases until the §6(2) follow-up).**
  **E1** `AssetDetailConditionHealthSeasonTest.theSchedulesSectionScrollsIntoViewWhenAsked` and
  `…theSchedulesScrollIsNotRepeatedWhenTheStateIsRestored`: the scroll state lives in `AssetDetailScreen`'s body, which
  composes five graph-bound children; with them (C-5) `…logIncidentOpensAnIncidentEntry` (the body's `onLogOutcome` wiring,
  :377) and `AssetDetailKeyDocumentsTest.aReceiptShowsInKeyDocumentsAndInDocuments…` (opened through
  `AttachmentsSection(graph…)`). **E2** `SettingsBackupEntryTest` (3): `SettingsScreen` reads the graph inline and has no view
  model. **E3** `ServiceCaseScreensTest.theLinkedEventsDocumentsAreDrawnThroughTheirOwnSection` (drawn by
  `LinkedDocuments(graph, …)`) and, with it (C-5), `addUpdateSheetAndTimeline` (the body's Add update wiring). Alternative: a
  non-verbatim seam per screen, each its own ruling. §3 counts them kept.
- **Open — E4 (C-1), `MaintenanceSheet`:** the `Scaffold` block assigns three delegated locals (`markingOperational`,
  `changingCondition`, `postponing`; :169, :170, :188), so S1 cannot move verbatim. **The reviewer's recommendation, not a
  decision:** S1′ — make the six private block composables `ConditionBlock`, `ConditionActions`, `ComponentRow`,
  `HealthBlock`, `TagPlacementLine` and `SheetItemRow` `internal` — and keep on the device the M1 cases that need the sheet
  body's own wiring (the `state.blocks` loop; the inline Open asset, Not now, Complete selected and Log incident controls;
  the effects outside it, `emptyOnArrival` and `onShown`): ten cases (M1). Alternative: the owner authorises a hoisted-setter
  adaptation by name. §3 counts the recommendation.

## Briefs — common to all seven

**Read:** §1–§7, the issue, the audit, the brief review, every earlier report. **Gate:** `./gradlew :core:test
:app:testDebugUnitTest --rerun` zero failures and skips; `:app:compileDebugAndroidTestKotlin`; from B2 `python3 -m unittest
discover -s tools/gate`; `tools/emulator/prepare-emulator.sh` first; B1 connects one class per run
(`ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`),
B2 onward via `tools/gate/merged-tip-gate.sh <tree> <outdir> <fqcn> …` (subset runs); anchored greps; the untouched diff;
R6 hygiene; gitlink `7e0377a`; `git status` clean. **The manifest rule:** a commit that changes a class's case count
changes its row (C5) and bumps `list-version` when a gate row moves. **Untouched, always:** `core/src/main`, `app/schemas`,
the manifest, `app/src/main` except C11's named seams, `share-test-sender`, `tools/servicetag-*`, `.github`, `docs/api`,
`docs/versioning.md`, `ReleaseProofPolicyTest`, the R4 row. **Caps:** JVM mutation runs = the brief's behaviours + 25 %
(rounded up), plus ≤ 5 re-runs after a surviving mutation, counted apart; 0 device mutations. **Must NOT, always:** delete a
device case before its record; weaken an assertion; batch; write a FQCN into the script; change a seamed file beyond C11;
restart the adb server or use another device; name `uiautomator`, `dumpsys` or `input tap|text` under `tools/` or
`app/src/androidTest`; commit a home path, archive path, serial or real name. **Report:** every mutation (file:line, edit,
quoted RED, reverted), C8's map, every re-classification or escalation, every INVESTIGATE line with its diagnosis, time spent.

## 8. B1 — foundation on JDK 25, the display-only wait, the flake (C1–C3; H1–H4)

**`<base>`** = the ratified commit. **First, before any other commit:** H2 and H2b on the daemon's JDK 25 through
`:app:testDebugUnitTest` (report `java.version`, the Robolectric version and SDK, any flags and heap), then
`:app:testReleaseUnitTest` once (C1). **Then** C2's shared wait and H1, the sweep, C3. **Connected:** the 41 classes that call
`awaitText` (per class declaration, 39 files), once each; then only adjusted classes, once. **Greps:**
`'^internal fun ComposeTestRule\.awaitText\('` → 1 under `app/src/sharedTest`, 0 under `app/src/androidTest`;
`'SemanticsProperties\.EditableText'` in the wait file → 0; `'^sdk=[0-9]+$'`, `'^application=android\.app\.Application$'` → 1
each; `'isIncludeAndroidResources = true'` → 1. **Caps:** ≤ 4 mutation runs; time box 4 h. **Stop and report:** no
Robolectric runs the pinned SDK on JDK 25 (never pin another JDK); H2b fails (a fork split is a build ruling); a plugin
would be needed; more than six classes fail after the sweep; any `app/src/main` edit. **Size:** about 60 build, 150 test,
60 androidTest lines.

## 9. B2 — manifest, tripwire, gate script and reporter, docs (C4–C7, C9, C10; H5–H7)

**`<base>`** = B1's tip. **The manifest at B2:** all 65 class declarations as they stand — gate = the 50 #72 classes plus
R90-9's seven, release = `PreservedSetRestoreTest` and, until they move, the eight R2-only classes; every class with a §4
row `pending-move`, boundary `pending M<n>`, its §4 homes in `jvm_homes`. **Proof:** one full run on B2's tip — the baseline
with the new reporting, the seven's first measurement, `nothingClips…`'s second sample; **expected verdict FAIL on device
wall only** (nothing has moved); every other FAIL reason stops the brief. **Greps:** `'com\.loosecannon\.servicetag\.'` in
the script → 0; `'/hom[e]/'` over `tools/gate` → 0; `'^# list-version [0-9]+$'` → 1; `'^\*\*Amendment \(#90, '` in the
policy → 1; `git diff <base> -- docs/release-proofs.md | grep -cE '^[-+]\| R4 '` → 0. **Caps:** ≤ 30 RED runs (in-process);
time box 5 h (the ≈ 14 min run included). **Stop and report:** an XML shape the reporter cannot read; a class failing in the
baseline. **Size:** about 300 script and reporter, 350 unittest, 180 tripwire, 80 manifest, 100 docs lines.

## 10. B3 — seams S2–S4, S18 (S1′ per E4); moves: scan sheet, schedules, dashboard (M1–M4)

**`<base>`** = B2's tip. **Before B3:** the controller confirms the pushed B1 tip's CI run is green with H2's JVM line, or
records that CI proof is deferred to the merge (C-7). **Rows:** H9 for S2–S4 and S18 (and S1′ if E4 is ruled as
recommended), M1–M4 (15 behaviours, 34 cases). **Connected:** after the seams and before any move, at least `ScanSheetTest`, `ScanSheetIncidentNavigationTest`, `ScheduleEditorTest`, `DashboardAttentionTest`, `AppSmokeTest`,
`InspectNamesABoundTagTest`, `AssetDetailConditionHealthSeasonTest` (S18), plus H9's grep; after the deletions, `ScanSheetTest`. **Measure:** C1's two JVM budgets (Z1). **Greps:**
`ScheduleEditorTest`, `ScheduleOperationsTest`, `DashboardAttentionTest` absent from `app/src/androidTest`; M1–M4's rows
final. **Caps:** ≤ 19 + 5 runs; time box 5 h. **Stop and report:** a seam that is not a C11 shape (escalate that screen); a
behaviour no seam reaches; a fourth re-classification. **Size:** about 120 seam, 1,300 JVM test lines added, 1,200 removed.

## 11. B4 — seams S5–S9; moves: maintenance shell, groups, condition, asset detail and editor, health (M5–M11)

**`<base>`** = B3's tip. **Rows:** H9 for S5–S9, M5–M11 (23 behaviours, 33 cases). **Connected:** after the seams, at least
`MaintenanceShellTest`, `MaintenanceTabTest`, `ChangeConditionSheetTest`, `AssetDetailConditionHealthSeasonTest`,
`AssetEditorSeasonAndHealthTest`, `AssetEditorWarrantyReminderTest`, `HealthSubjectEditorTest`, `JournalSmokeTest`,
`NavigationSmokeTest`, plus H9's grep; after the deletions, the kept cases of M6, M8, M9, M10. **Greps:** `MaintenanceShellTest` (the class;
`MaintenanceTabTest` stays in its file), `ChangeConditionSheetTest`, `HealthSubjectEditorTest` absent. **Caps:** ≤ 29 + 5;
time box 5 h. **Stop and report:** as B3. **Size:** about 150 seam, 1,100 test lines added, 1,100 removed.

## 12. B5 — moves: journal and asset-model proofs, editors, attachments, the assets list (M12–M16, H8)

**`<base>`** = B4's tip. **Rows:** M12–M16 (26 behaviours, 36 cases), H8 before `AssetModelDeviceProofTest` goes; no new
seam. **Connected:** the kept cases of `AttachmentsDeviceProofTest`, `AssetsIndicatorsTest`, `EditorsDeviceProofTest`, and
`NavigationSmokeTest`. **Greps:** `JournalDeviceProofTest`, `AssetModelDeviceProofTest` absent; `'fun
theSheetRenamesAndReKindsTheRow'` → 0 under `app/src/androidTest`, 1 under `app/src/test`. **Caps:** ≤ 33 + 5; time box 5 h.
**Stop and report:** as B3. **Size:** about 1,300 test lines added, 1,500 removed.

## 13. B6 — seam S11; moves: filters, search, documents, key documents, share intake, R2-only composables (M17–M22, M32–M38)

**`<base>`** = B5's tip. **Rows:** H9 for S11, M17–M22 and M32–M38 (30 behaviours, 56 cases). **Connected:** after S11, at least
`AssetsFiltersTest`, `AssetsSearchTest`, plus H9's grep; after the deletions, the kept cases of `AssetsFiltersTest`,
`AssetDetailKeyDocumentsTest`, `DocumentsDescriptionLineTest`. **Greps:** `AssetsSearchTest`, `AssetEditorKeyDocumentsTest`,
`ShareIntakeScreenTest` and the seven R2-only classes absent. **Caps:** ≤ 38 + 5; time box 5 h. **Stop and report:** as B3.
**Size:** about 30 seam, 1,500 test lines added, 1,600 removed.

## 14. B7 — seams S10, S12, S13, S15–S17 (S14 held, E3); moves: categories, backup, service cases, lending, warranty, Developer API, plurals (M23–M31, M39); the close

**`<base>`** = B6's tip. **Rows:** H9 for its seams, M23–M31 and M39 (16 behaviours, 22 cases + M39's 3); H6's release pin;
`pending-move` leaves the allowed kinds. **Connected:** after the seams, at least `CategoriesScreenTest`, `EmptyStoreRestorePromptTest`,
`ServiceCaseScreensTest`, `AssetServiceCasesTest`, `LendingSectionDeviceTest`, `DeveloperApiListenerTest`,
`AppSmokeTest`, plus H9's grep; then **the full gate once on B7's tip — verdict PASS required**. **Greps:** the deleted classes
absent; `'pending-move'` over `tools/gate` and the tripwire → 0; gate rows 43 (42 if M39 moves), release rows 1; the
androidTest class declarations = gate + release rows. **Caps:** ≤ 20 + 5; time box 5 h (the full run included). **Stop and
report:** as B3; a full run not PASS (diagnose; no re-run until the controller rules). **Size:** about 200 seam, 900 test
lines added, 800 removed.

## 15. Acceptance and proofs

- **AC 1:** the manifest classifies all 65 class declarations; `DeviceClassManifestTest` green with no `pending-move` (B7).
- **AC 2:** §4's 110 behaviours each carry a RED record, fresh or cited, in B3–B7's reports; the whole-branch review samples
  them against the diff and re-runs H9's checks on every seam commit.
- **AC 3:** after the whole-branch review and the `--no-ff` merge, the controller runs `tools/gate/merged-tip-gate.sh` on the
  merged tip **twice consecutively**; both PASS (device ≤ 600 s, whole ≤ 900 s, every step exit 0, every XML fresh, every
  count equal, no skip, no budget line). A non-clean run resets the count and is diagnosed first.
- **AC 4:** C6/C7, H5–H7 and the two runs' per-class tables. **AC 5:** C3, H1, H3; M15 a; both AC 3 runs clean with no
  re-run. **AC 6:** C10 in B2; R1–R7 otherwise unchanged (R2 = the gate and release tiers).
