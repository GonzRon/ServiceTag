# B02 — the attachments view model's IO work, on the test's clock (#65)

**Read first:** plan.md §2, §3 and §8 (#65); `audit/audit-65-52.md` (the #65 half); `audit/repeat-65.md`; issue #65's rewritten body.
**Lane:** second, alone, branched from master after B01 merged.

## Goal

`AttachmentsSectionViewModelTest.savingNothingIsSilent` has failed twice, both times in full `:app:testDebugUnitTest` runs (1 of 5 at #63's gate; once in the 1.4 B08 round, recorded as `CompletionHandlerException … ProducerCoroutine{Cancelled}`). It has never failed in a class-alone run, and the controller's 23 repeat runs were all green. The mechanism the audit found:

- the view model runs its work on the real `Dispatchers.IO` in five places;
- that work goes on after the signal a test waits for (the trailing `refresh.value++` and the scan pass it starts);
- `store.clear()` runs in `@After`, after `runTest` has stopped draining;
- so the work's cancellation finishes on a pool thread while the test thread closes the database, resets Main and starts the next test.

Per-test `setMain`/`resetMain` has existed since `2823d97`, and there is no `MainDispatcherRule` to add. The brief runs in this order:

1. make one **bounded attempt** to capture a complete cause chain;
2. make the **deterministic fix**, whatever the capture found;
3. **prove** it by repeated full-suite runs.

## Files

**Modify**
- `app/src/main/kotlin/com/loosecannon/servicetag/ui/attachments/AttachmentsSectionViewModel.kt`
  - one constructor parameter;
  - the five dispatcher sites (lines 139, 176, 212, 226 and 250 at the base);
  - `_saved`'s buffer, **only** under contract C5;
  - the KDoc that names `Dispatchers.IO`.
- `app/src/test/kotlin/com/loosecannon/servicetag/ui/attachments/AttachmentsSectionViewModelTest.kt`
  - `model()`;
  - a clear-and-drain helper;
  - the gated progress case;
  - the new cases in the matrix;
  - comments that describe real-IO ordering.

**Untouched**
- `DocumentsSection.kt`: it keeps calling the graph constructor.
- `app/src/test/.../testing/FakeGraph.kt` and the database test wiring.
- `app/build.gradle.kts` and all Gradle test configuration.
- Every other view model and every other test class.

## Interfaces

**Produces.** `AttachmentsSectionViewModel(…, today: () -> String = …, io: CoroutineContext = Dispatchers.IO)`. The new parameter comes last, and the secondary constructor `(graph: AppGraph, owner: AttachmentOwner)` passes nothing for it. The pattern is the one `ShareIntakeViewModel` already uses (`share/ShareIntakeViewModel.kt:150`, `io: CoroutineContext = Dispatchers.IO`), and its test passes `StandardTestDispatcher(scheduler)`.

**Consumes:**
- the test's `TestCoroutineScheduler`;
- `FakeGraph(queryContext = StandardTestDispatcher(scheduler))`;
- `ViewModelStore`;
- the `AttachmentStore.put` port, which is `suspend` (`core/.../ports/AttachmentStore.kt:17`).

## Step 0 — the bounded capture (before any edit)

On the unmodified lane base, run `./gradlew :app:testDebugUnitTest --rerun --console=plain` **10 times**. For each run, keep:
- the console log, as `.superpowers/b02/capture-NN.log` in the worktree;
- a copy of `app/build/test-results/testDebugUnitTest/`, as `.superpowers/b02/capture-NN-xml/`.

`.superpowers/` is ignored, so these are scratch files and nothing is committed. Ordinary host load is fine, but do not start the emulator for this. Stop early at the first failure. The report then quotes, from that run's XML `<failure>`:
- the failing test and the test JUnit ran just before it in the class;
- the exception class and message;
- **every** `Caused by` line of the chain, with paths reduced to repository-relative.

If none of the 10 runs fails, the report says "not reproduced in 10 full runs", with the ten pass counts. **Either way, go on to step 1.** The bound is 10 runs. It is not "until it fails".

## Step 1 — the deterministic fix: contracts

- **C1.** All five sites run on `io`: four `viewModelScope.launch(io)` and one `withContext(io)` inside `storeReads.onStart`. In production `io` is `Dispatchers.IO`, so the app's threading does not change. The `state` sharer stays on `viewModelScope`.
- **C2.** The test's `model()` passes `io = StandardTestDispatcher(scheduler)`, the scheduler that Main and the graph already share.
- **C3.** Every case that builds a model clears the `ViewModelStore` and then drains the scheduler (`advanceUntilIdle()`) **inside the `runTest` body, before it returns**, through one helper that every case uses. `@After` keeps `store.clear()`, then `graph.close()`, then `resetMain()`, in that order, as a safety net that now has nothing left to cancel.
- **C4.** The progress case's gate suspends; it never blocks.
  - A blocking `CountDownLatch` on a test dispatcher would stop the only thread that drives the test.
  - The gate is a `CompletableDeferred` awaited inside a suspending seam. The natural place is a delegating storage whose store's `put` awaits the gate, in the style of the class's `FlakyExistsStorage`.
  - `PickedFile.open` is a blocking lambda: nothing in it may wait.
  - The case still proves what it proves today: the first progress line is observed, the failure in the middle is reported, and two rows land.
- **C5.** `_saved`, the two-emit race: `MutableSharedFlow(replay = 0, extraBufferCapacity = 1)` fed by two concurrent `tryEmit`s. **If** the implementer shows a second emit dropped (a RED case in which both saves emit before the collector resumes), raise the buffer so two saves in flight cannot lose one. **Otherwise** leave it, and write in the report why the test-clock ordering rules the drop out, and whether production can still reach it. `_messages` and `_deleted` have the same shape. They get an analysis in the report, not an edit.
- **C6.** Nothing a user sees changes: the same sentences, the same order of effects, the same `SUBSCRIPTION_GRACE_MS`.

## Test matrix (JVM, `AttachmentsSectionViewModelTest`)

| hazard | test (case) | RED mutation |
|---|---|---|
| a path still on a real dispatcher under test | `everyPathRunsOnTheTestsThread`. Recording seams note the thread each path reaches the store or the transaction on: the storage's `state()`/`store()`/`exists`/`put`, and a `UnitOfWork` wrapper handed to `UpdateAttachment`/`DeleteAttachment`. Add, save, delete, a scan pass and a resubscription all record the test's thread and nothing else. | put any one of the five sites back on `Dispatchers.IO`: that path records a pool thread |
| work outliving the test body | `clearingInsideTheTestLeavesNoWorkRunning`. Capture the model scope's `Job` before clearing. After save, delete and a refresh, clear and drain; the job `isCompleted`. | clear without draining (or clear in `@After` only): `isCompleted` is false |
| the progress gate blocks the test thread | `addingSeveralFilesReportsProgressAndKeepsGoingPastAFailure`, reworked to C4 | re-introduce the `CountDownLatch`: the case hangs until `runTest` times out (a timeout is the RED shape; a shorter `timeout` on this case is fine) |
| two saves lose a signal (only if C5 applies) | `twoSavesInFlightBothSignal` | restore `extraBufferCapacity = 1` |
| the flaky case itself | `savingNothingIsSilent`, unchanged in intent, with its comment corrected (the saves no longer race on Room's executor) | — |
| production threading drifts | anchored grep G3 | — |

Count the cases in the XML: every new case must appear in the results.

## Step 2 — the proof

- **Primary:** 10 consecutive full `./gradlew :app:testDebugUnitTest --rerun --console=plain` runs on the fixed branch, all green, with each run's pass count recorded and each log kept as in step 0. A single failure restarts the count after a fix; it is never waved through.
- **Secondary:** the class alone, 20 times, with `--rerun` and `--tests '*AttachmentsSectionViewModelTest'`.
- **Mechanism statement:** the report explains how the fixed mechanism accounts for both sightings (the #63-gate full run, and the 1.4 B08 `ProducerCoroutine{Cancelled}`), or says plainly what it does not explain.

## Follow-up notes (not scope)

Read, do not edit, three candidates, and record for each whether it has the same shape: a real dispatcher that cannot be replaced, plus a test that does not drain before `runTest` returns. Give file and line for each finding. The controller files the follow-ups.

- `ui/references/ReferencesSectionViewModel.kt`: `launch(Dispatchers.IO)` at 136, 164 and 180. (In scope only as the identical one-parameter injection: see the controller amendment at the end.)
- `ui/backup/BackupViewModel.kt`: `withContext` at 134, 192 and 206, and `launch` at 251. (Same rule: identical injection only, else a follow-up note.)
- `ui/scan/ScanViewModels.kt:92`: `ioDispatcher` defaults to IO, and `ScanViewModelTest` and `TagPlacementTest` never replace it.

## Gate

- **G1:** the full `:app:testDebugUnitTest` suite, zero failures and zero skips, with its counts recorded (step 2 covers this).
- **G2:** `./gradlew :app:assembleDebug --console=plain`.
- **G3:** `grep -nE 'launch\(Dispatchers\.IO\)|withContext\(Dispatchers\.IO\)' app/src/main/kotlin/com/loosecannon/servicetag/ui/attachments/AttachmentsSectionViewModel.kt` prints nothing, and `grep -nE 'io: CoroutineContext = Dispatchers\.IO' <same file>` prints one line.
- **G4:** `git diff --stat <base>` names only the two files above.

## Strings

None. No user-visible text changes.

## Must NOT

- add a `MainDispatcherRule`, a retry rule, `@Ignore`, a longer global timeout, `maxParallelForks`, `forkEvery` or any other Gradle test setting;
- change `FakeGraph`, the database test wiring or any other test class;
- edit `ReferencesSectionViewModel`, `BackupViewModel` or the scan flow beyond the identical injection the controller amendment below allows (the scan flow is note-only);
- change production behaviour, messages or timings;
- block a test-dispatcher thread anywhere, whether with latches, `Thread.sleep` or `runBlocking` inside a model path;
- stop the capture after "enough" runs have passed, or skip step 0.

## Review focus

- The thread-recording case can actually go RED: its seams sit on the paths the five sites run, not on paths that were already on the test thread.
- The clear-and-drain happens inside every model-building case.
- The capture and proof counts are real runs, backed by kept logs.

## Size

Small: one constructor parameter and five call sites in production, and one test class reworked.

## Controller amendment (2026-09-25)

- The owner's ruling allows a directly equivalent, low-risk fix in the sibling view models. Where `ReferencesSectionViewModel` or `BackupViewModel` shows the identical pattern (production `Dispatchers.IO` launches with no other coupling), apply the identical one-parameter injection with the same production default and the same test wiring, one test row each (RED: keep the real dispatcher). Anything that is not identical stays a note for a follow-up issue, as the brief already says. The scan flow is note-only.
