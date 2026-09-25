# Phase 1A audit: #65 and #52

Read-only audit, master at `6a7a161` (1.4.0 released), 2026-09-25. The code, tests, docs and git history
were checked against each ticket's wording. The current state wins wherever the two disagree. Paths
are relative to the repository root.

---

## #65: flaky `AttachmentsSectionViewModelTest`, main dispatcher

### Classification

**STILL RELEVANT BUT NEEDS REFRAMING**

### Original purpose

- **Observed (during #63's gate):** `AttachmentsSectionViewModelTest.savingNothingIsSilent` failed in 1 of 5
  full `:app:testDebugUnitTest --rerun-tasks` runs with "a main-dispatcher `IllegalStateException`". It
  passed 6/6 when run alone.
  - The exception message and stack were not kept anywhere: not in the repository, and not in any ledger.
- **Diagnosis in the ticket:** a `Dispatchers.Main` set/reset race between tests. Either the
  `setMain`/`resetMain` pairing is not per test, or some test touches Main without the rule.
- **Ask:** pin Main per test, using a `MainDispatcherRule` or "the shared rule the other view-model tests
  use". Then run the class 20× under `--rerun-tasks`.

### Current reality (evidence)

1. **Per-test pinning was already there when the ticket was filed** (2026-09-24 14:20Z). The history
   crosses the `notenfc` → `servicetag` package rename at `abd0df1`.
   - **`setMain`/`resetMain`:** `Dispatchers.setMain(...)` is in `@Before` and `Dispatchers.resetMain()`
     in `@After` since `2823d97` (2026-09-16). Today they are at test file lines 90–99.
   - **ViewModel cleanup:** a `ViewModelStore` whose `store.clear()` runs in `@After` since `5460e78`
     (2026-09-16). Its KDoc (lines 83–88) names the leak it was added for: the scan and the `stateIn`
     sharer "running past `graph.close()` and `resetMain()`".
   - **Shared scheduler:** Main is `UnconfinedTestDispatcher(scheduler)` and the graph is
     `FakeGraph(queryContext = StandardTestDispatcher(scheduler))`, since `4aace2c` (2026-09-18). This is
     the project's known pattern. `runTest` takes its scheduler from Main.
   - **The flaky case itself** was rewritten in `068c528` (2026-09-22). It now awaits both `saved` signals
     (`take(2)`) instead of counting them afterwards, which fixed an earlier race of its own.
   - **Since filing,** only `ab9c7c8` touched this class, and it changed one comment line. Nothing changed in:
     - the ViewModel;
     - the `FakeGraph`/`TestDb` dispatcher wiring;
     - the Gradle test configuration.
2. **There is no `MainDispatcherRule` anywhere in the repository.**
   - All 29 `:app` test classes that pin Main do it the same way: `setMain` as the first line of `@Before`,
     `resetMain` in `@After`. The "shared rule" the ask refers to does not exist.
   - A rule would change nothing: a JUnit 4 `TestWatcher` wraps `@Before`/`@After` in the same order.
3. **Gradle configuration (`app/build.gradle.kts`):**
   - `:app` runs JUnit 4.13.2. Only `:core` and `:nfc-core` use the JUnit Platform.
   - No `maxParallelForks` (default 1) and no `forkEvery` (default 0). One test JVM runs every class and
     method in sequence on one thread.
   - The only test JVM argument is `--enable-native-access=ALL-UNNAMED`.
   - `unitTests.isReturnDefaultValues = true`, so the real Android Main cannot initialise on the JVM.
   - **Ruled out by this:** two classes calling `setMain` at the same time, and test parallelism.
   - **Remaining route:** tests can only affect each other through coroutines or threads that outlive
     their test in the shared JVM.
4. **The flake came back in 1.4, with a more specific recorded signature.**
   - `.superpowers/sdd/2026-09-24-servicetag-1.4/B08-report.md:398` records it in the same method:
     `kotlinx.coroutines.CompletionHandlerException … ProducerCoroutine{Cancelled}`.
   - Three isolated reruns of the class passed 18/18. The next full run passed 744/744 (`progress.md:98`).
   - Both recorded failures hit the same method, and only in full-suite runs.
5. **Test order is fixed.** JUnit 4's `MethodSorters.DEFAULT` is deterministic.
   - The current report (`app/build/test-results/testDebugUnitTest/TEST-…AttachmentsSectionViewModelTest.xml`)
     runs `savingNothingIsSilent` 17th of 18.
   - It runs right after `aDeleteThatCannotBeWrittenSaysSoInsteadOfCrashing`.
6. **The ViewModel does its work on real threads**
   (`app/src/main/kotlin/com/loosecannon/servicetag/ui/attachments/AttachmentsSectionViewModel.kt`):
   - `viewModelScope.launch(Dispatchers.IO)` for the scan (176), `add` (212), `save` (226) and `delete` (250).
   - `storeReads.onStart { withContext(Dispatchers.IO) { … } }` (139).
   - The `state` sharer is on `viewModelScope`, which is `Dispatchers.Main.immediate` (149–153).
   - The tests collect `messages`, `saved` and `deleted` on `Dispatchers.Main`.
   - As a result, every write from an IO thread into a flow those collectors watch resumes them through
     `TestMainDispatcher`, from the IO thread.
   - The constructor (90–101) takes no dispatcher, so a test cannot swap the IO dispatcher out.
     `ShareIntakeViewModel` does take one: `io: CoroutineContext = Dispatchers.IO`
     (`ShareIntakeViewModel.kt:150`). Its test passes `StandardTestDispatcher(scheduler)`
     (`ShareIntakeViewModelTest.kt:99,126`).
7. **Work continues after the signal the test waits for.**
   - `save` emits `_saved` (238/241), then runs `refresh.value++` (245).
   - `delete` emits `_deleted` (253), or the "Could not delete" message (259), then runs `refresh.value++`
     (261).
   - Each of those `refresh` bumps starts another scan pass on IO.
   - So a test body can return while that trailing work is still running:
     - `savingNothingIsSilent` leaves two of these tails running at once.
     - Its predecessor waits for the "Could not delete" message and leaves the `refresh` bump and a scan
       pass behind.
8. **Teardown order leaves room for a race.**
   - `runTest` finishes by cancelling `backgroundScope` and then draining the scheduler. This was checked
     in the kotlinx-coroutines-test 1.10.2 bytecode: `TestBuildersKt…runTest$2$1` calls
     `backgroundScope.cancel` and then `advanceUntilIdleOr`.
   - Only after that does `@After` run: `store.clear()` (cancel, with no join), then `graph.close()`,
     then `resetMain()`.
   - **Cancellation work queued on the test's `StandardTestDispatcher` never runs.** Nothing drives that
     scheduler once `runTest` has returned.
   - **Cancellation work on IO keeps going** while the test thread closes the database, resets Main, and
     starts the next test's `setMain`.
9. **Room 3.0.3's observe flow runs inside a producer coroutine.**
   - It is built as `createFlow(...).conflate()` (`androidx.room3.coroutines.FlowUtil`), so every Room-backed
     collection runs inside a `ProducerCoroutine` in the collector's context.
   - This ViewModel has two of them: one under the `state` combine (on Main) and one under the scan's
     `combine(rows, refresh)` (on IO).
   - That is the kind of coroutine named in the B08 trace.
10. **How kotlinx-coroutines-test 1.10.2 turns this into an `IllegalStateException`.** Checked in the
    library bytecode.
    - **The Main guard:** `TestMainDispatcher` protects its delegate with `NonConcurrentlyModifiable`.
      - A read that overlaps `setMain`/`resetMain` throws "Dispatchers.Main is used concurrently with
        setting it".
      - Two overlapping writes throw "… is modified concurrently".
      - Both are `IllegalStateException`s.
    - **Victim tests:** `ExceptionCollector` catches any uncaught exception from a coroutine that belongs
      to no test.
      - It hands it to the `runTest` that is running, or holds it for the next one.
      - The next one then fails with `UncaughtExceptionsBeforeTest`, which is itself an
        `IllegalStateException`.
      - So the test that gets reported can be an innocent victim of the test before it.

### Remaining work

The mechanism the ticket asks for is already in place. The flake is still real. It is a leak at
teardown, where ViewModel work outlives its test. It is not a problem of pinning the dispatcher.

1. **Capture one full cause chain first.**
   - That means the `CompletionHandlerException`'s cause, or the exact `IllegalStateException` message.
   - Loop the full `:app:testDebugUnitTest --rerun-tasks` until it fails, and keep the XML.
   - Runs of the class alone are not a reproduction: 6/6, and 18/18 three times.
2. **Candidate causes, most likely first.**
   - **A. ViewModel work on `Dispatchers.IO` outlives the test.**
     - The work is the un-awaited trailing `refresh.value++` and the scan pass it starts.
     - Its cancellation then completes on an IO thread while the test thread is in `@After`
       (`graph.close`, `resetMain`) or in the next test's `@Before` (`setMain`).
     - A completion handler of a cancelled Room producer, or of its parent, then does one of two things:
       resumes through `TestMainDispatcher`, or touches the closed database.
     - The result is the B08 `CompletionHandlerException`, or a "used concurrently with setting it" or
       missing-Main `IllegalStateException`. Either one is delivered to the next `runTest`.
     - This fits both observations:
       - The failure lands on the same method every time.
       - It appears only in full-suite runs.
       - The predecessor's trailing work and `savingNothingIsSilent`'s two parallel saves are the
         heaviest in the class.
   - **B. Clean-up happens in the wrong place.**
     - `store.clear()` runs after `runTest`'s final drain, so cancellations that need the test's
       scheduler never finish.
     - `graph.close()` then closes the database under those half-cancelled producers.
   - **C. A leak from another class.** This is less likely, since the same method was the victim twice.
     - 26 of the 29 Main-pinning classes never cancel `viewModelScope`.
     - Those whose ViewModels hop to real threads with no way to swap the dispatcher could push an
       exception into whichever `runTest` is running:
       - `BackupViewModel.kt:251` uses `launch(Dispatchers.IO)`.
       - `ScanViewModels.kt:92` defaults to `Dispatchers.IO`, and `ScanViewModelTest`/`TagPlacementTest`
         never replace it.
   - **D. A separate hidden race in the same test, not the reported shape.**
     - `_saved` is `MutableSharedFlow(replay = 0, extraBufferCapacity = 1)` (VM line 164), and two IO
       threads `tryEmit` into it.
     - A second emit that arrives while the first is still buffered is dropped.
     - Then `closed.await()` never returns, and the result is a `runTest` timeout (the test comment's own
       RED shape).
     - The fix should close this too.
   - **Ruled out:** parallel test execution; two classes calling `setMain` at the same time; missing
     per-test pinning; scheduler ownership inside this class.
3. **Fix direction, once step 1 has the evidence.** Run the ViewModel's work on the test's clock.
   - Give `AttachmentsSectionViewModel` an IO context parameter with a default value, as
     `ShareIntakeViewModel` does. The test passes `StandardTestDispatcher(scheduler)`.
   - Clear the `ViewModelStore` inside the `runTest` body (a small helper), so cancellation drains before
     `runTest` returns.
   - Rework the progress test that is gated on a `CountDownLatch`. A blocking latch on a test dispatcher
     would block the test thread; use a suspending gate instead.
   - **Proof:** 20 full `:app:testDebugUnitTest --rerun-tasks` runs green, plus the class alone.

### Size + files

**Small.**

- `app/src/main/kotlin/com/loosecannon/servicetag/ui/attachments/AttachmentsSectionViewModel.kt`: one
  constructor parameter with a default value. It replaces the five `Dispatchers.IO` uses. The app's
  behaviour does not change.
- `app/src/test/kotlin/com/loosecannon/servicetag/ui/attachments/AttachmentsSectionViewModelTest.kt`:
  `model()`, a run-and-clear helper, and the gated progress test.
- **Optional sweep** of the same pattern, where no flake has been reported:
  - `ReferencesSectionViewModel.kt`, with `launch(Dispatchers.IO)` at 136/164/180, and its test.
  - `BackupViewModel.kt`/`BackupViewModelTest.kt`.

### Dependencies / combination

- **Independent of #66, #51 and #52.** No files are shared.
- **Scheduling:** it belongs in the Phase-1 cleanup slot, before any large addition of JVM tests.
- **Optional combination:** a small JVM-test hygiene sweep of References, Backup and Scan. It would add
  the same IO parameter and move the clear inside `runTest`, closing this whole kind of leak rather than
  one instance of it.

### Recommendation

**GitHub disposition: rewrite-narrow.** Keep it in Phase 1.

- **Retitle:** "AttachmentsSectionViewModelTest: ViewModel IO work outlives the test (full-suite flake)".
- **Record both observations:** the #63 gate (1 of 5 full runs) and the 1.4 B08 round
  (`CompletionHandlerException … ProducerCoroutine{Cancelled}`).
- **State in the ticket** that per-test `setMain`/`resetMain` has existed since `2823d97`.
- **Replace the `MainDispatcherRule` ask with three steps:**
  1. Capture the full cause chain.
  2. Put the ViewModel's IO on the test scheduler, and clear the ViewModel inside `runTest`.
  3. Prove it with repeated full-suite runs, not repeats of the class alone.

---

## #52: Developer API, readable 422 validation details

### Classification

**PARTIALLY SUPERSEDED**

### Original purpose

- **Observed at 1.1.0:** a 422 carries the domain's validation problems in `error.problems`. Each entry is
  the Kotlin `toString` of the problem, for example `Season(p=BadDate(which=start))`.
  - The owner ruled on 2026-09-21 to defer it.
- **Ask:**
  1. Map each problem to a stable code, a field name and a short message.
  2. Document the codes in `docs/api/v1.md` under **Errors**.
  3. Have the MCP server pass them through unchanged.
  4. Keep the envelope (`code`, `message`, `problems`) so existing clients keep parsing.

### Current reality (evidence)

**Delivered since the ticket was filed** (2026-09-22 00:14Z), by other briefs. File references are to
`app/src/main/kotlin/com/loosecannon/servicetag/api/ApiJson.kt` unless stated otherwise.

- **1.2 (`28dad68`, 2026-09-22): schedule and group validation.**
  - `ScheduleValidation` and `GroupValidation` answer one `UPPER_SNAKE` code per problem: the first
    problem's code.
  - Both mappings, `scheduleProblemCode` (646) and `groupProblemCode` (673), are exhaustive `when`s with
    no `else`.
  - `SCHEDULE_INVALID` and `GROUP_INVALID` are the fallbacks.
  - Documented in the table at `docs/api/v1.md:720–783`.
- **1.3 (`20b0add`, 2026-09-23): references.**
  - `referenceProblemCode` (624) is exhaustive.
  - Each problem gets its own human-readable sentence (386–411).
  - `problems` is deliberately empty, so no URI, scheme or path can leak.
  - Documented at `v1.md:785–813`.
- **1.4 (B09: `fc8cb40`, plus fix round `7f8bb87`, 2026-09-24): seasons, condition and health.**
  - The envelope gains `field` (`ApiErrorDetail`, 91–104). It is always encoded, and `null` where it does
    not apply.
  - `Refusal(code, message, field)` (494) is filled by the exhaustive `seasonRefusal` (516),
    `conditionRefusal` (543) and `healthRefusal` (562).
  - `POLICY_OFFSET_INVALID` carries `field = policyOffsetDays` (250).
  - Documented with a `field` column at `v1.md:815–868`.
  - The problem types themselves come from B04 (`SeasonProblem`) and B06 (`ConditionProblem`,
    `HealthProblem`).
- **MCP (`tools/servicetag-mcp`):**
  - Every `ApiError` becomes a `ToolError` that reads `"<status> <code>: <message> (<problems…>)"`
    (`server.py:201–205`).
  - The client parses `code`, `message` and `problems` (`client.py:273–278`).
  - Tests pin this behaviour: `test_client.py:44–58`, `test_sdk_boundary.py:93`,
    `test_maintenance_tools.py:604`.

**Still as the ticket describes:**

- **The four 1.1.0 validation families have not moved.** Each answers one family code with a generic
  sentence, `field: null`, and problems as `toString` strings (214–229):
  - `asset_validation`: "the asset was refused"
  - `event_validation`
  - `definition_validation`
  - `profile_validation`
  - The ticket's own example can still be produced: `AssetProblem.Season(it)` (`core/…/usecase/AssetCommands.kt:109`)
    gives `Season(p=BadDate(which=start))` on the wire.
- **Every family still fills `problems` with `it.toString()`, 1.2 and 1.4 included.**
  - The call sites are ApiJson.kt 216, 220, 224, 228, 248, 257, 434, 438 and 442.
  - `code`, `message` and `field` describe the **first** problem only. Any further problems appear only in
    `toString` form.
  - Some strings are built by hand in the same style:
    - `ClosedOnOutOfRange(earliestOn=…, today=…)` (295)
    - `ScheduleDrivesHealthSubject(subjectId=…, name=…)` (452)
    - `LegacyWriteCannotRepresent(scheduleId=…, servicePolicy=PRE_SERVICE)` (`ScheduleForms.kt:92`)
    - `MemberRequired` (`MaintenanceHandlers.kt:224`)
    - on 409s: `OccurrenceNotYetOpen(…)` (290), `StrandedSchedule(id=…, title=…)` (500),
      `HealthScheduleTaken(…)` (479)
- **The hardest entries to read today:**
  - **Events:** a bad `occurredOn` or `occurredTime` gives `BadDate(definitionId=null)` /
    `BadTime(definitionId=null)` (`core/…/usecase/EventCommands.kt:172–173`). These name no field, which
    contradicts `v1.md:715` ("`problems` names each bad field").
  - **Value-class clutter:** `Required(definitionId=DefinitionId(value=…))`,
    `ForeignProfile(profileId=ProfileId(value=…))`, `Derived(p=SourceIsMeter(id=DefinitionId(value=…)))`.
  - **Bound printed as a Kotlin range:** `NameRequired(limit=1..60)`.
  - **Log text on the wire:** `ProfileProblem.BadField(id=…, reason=…)` carries an English reason that was
    written for the log (`core/…/usecase/SaveProfile.kt:62–70`).
- **`field` is left out where the problem already knows it.**
  - `SeasonProblem.BadDate(field)` (529) and `ConditionProblem.BadDate/BadTime/BadTimeZone(field)`
    (551–553) carry the field name, but their `Refusal` sets no `field`.
- **The MCP drops `field`.**
  - `_detail` reads only `code`, `message` and `problems` (`client.py:273–278`).
  - `ApiError` has no `field` (`client.py:60–70`), and the `ToolError` text leaves it out
    (`server.py:202–204`).
  - So the 1.4 `field` never reaches an MCP caller.

**Wire compatibility:**

- **Turning `problems` into objects would break `/v1`.** It is a `List<String>` (`ApiErrorDetail` 102;
  `ApiFailure` 125). Replacing the strings with objects would:
  - fail any strict decoder;
  - make the MCP print Python dict reprs, via `str(p)`;
  - break tests that pin exact strings: `ApiRouterTest.kt:429`; `SeasonHealthRoutesTest.kt:257–273`,
    `322`, `453`, `468`, `498`; `LegacyFormTest.kt:153`; `MaintenanceRoutesTest.kt:370–371`, `451`,
    `894`;
  - contradict `v1.md`, which documents them as the domain's own names.
- **Additive changes are safe, and have been done before.** Filling `field` or `message` where today they
  are `null` or generic adds information without breaking anything. So does adding a new key. 1.4 did
  exactly this when it added `field`.
- **Consumers:** the MCP is the only consumer of `problems`, and it only shows them as text to a model.
  - `tools/servicetag-bundle` and `tools/servicetag-schedules` never read `problems`.
  - No client branches on a problem string.

### Remaining work

The gap is smaller than the ticket, and every part of it can be done without breaking anything:

1. **The four 1.1.0 families (asset, event, definition, profile):** give the first problem a readable
   `message` and a `field`, using exhaustive mappers in the 1.4 style.
   - Keep the `lower_snake` codes: clients branch on them.
   - Per-problem codes would change `code`, which breaks those clients.
2. **Fill the missing `field`s:**
   - on the malformed-value refusals that already carry the name: season `BadDate`; condition
     `BadDate`/`BadTime`/`BadTimeZone`;
   - on the event `BadDate`/`BadTime` refusals, set `field` to `occurredOn`/`occurredTime` in the wire
     mapper, so the `problems` strings stay as they are.
3. **MCP:** parse `field`, keep it on `ApiError`, and include it in the `ToolError` text.
4. **Docs (`docs/api/v1.md` → Errors):**
   - List the 1.1.0 families' problem names and what each means.
   - Correct the 422 row's claim that "`problems` names each bad field".
   - Amend the sentence saying `field` is `null` "on every shipped" answer.

**Not needed:** structured `problems` objects. They would break `/v1`, and nothing consumes them. If
per-problem detail beyond the first problem is wanted later, add it as a new parallel key (for example a
list of `{code, field, message}`) and leave `problems` alone.

### Size + files

**Small** for items 1–4:

- `app/src/main/kotlin/com/loosecannon/servicetag/api/ApiJson.kt`: four family mappers of roughly 60–90
  lines, plus the `field` fills.
- `app/src/test/kotlin/com/loosecannon/servicetag/api/ApiRouterTest.kt`, with a direct exhaustiveness
  case for each mapper, following `ReferenceRoutesTest`'s pattern.
- `docs/api/v1.md`, the Errors section.
- In `tools/servicetag-mcp/`:
  - `src/servicetag_mcp/client.py` and `server.py`;
  - `tests/test_client.py` and `tests/test_sdk_boundary.py`;
  - a `README.md` note and a `pyproject.toml` version bump.

The new API sentences become contract text in `v1.md`. They should get the same owner review the 1.4
codes got.

**Medium** if a structured per-problem key is also wanted. That touches every 422 arm, all five
route-test files, the MCP's rendering, and the `v1.md` tables.

### Dependencies / combination

- **Shared label, no shared code.** #52 carries the Developer API label, like #51 and #66, but they touch
  different files:
  - #51 and #66 are about the Android Developer API screen and its listener or permission (`ui/api/`,
    `LoopbackApiServer.kt`).
  - #52 is the `/v1` error mapping plus the MCP.
- **Shared documents:** `docs/api/v1.md` (#66 proposes documenting the permission inventory there) and
  the MCP README/version.
- **Combination:** ship #52 in the same Phase-1 Developer-API batch as #66 and #51, so `v1.md` and the
  MCP README/version change once and one release note covers them.
  - Of the three, only #52 changes the wire, and only by adding to it.
- **No dependency on #65.**

### Recommendation

**GitHub disposition: rewrite-narrow.**

- **Retitle:** "Developer API: readable 422s for the 1.1.0 validation families, and the MCP passes
  `field` through".
- **Record what already shipped:** 1.2, 1.3 and 1.4 deliver stable codes, messages and `field` for
  schedule, group, reference, season, condition and health.
- **Drop the ask** to restructure `problems` (it breaks `/v1`). Replace it with the four additive items
  above, and keep the `problems` strings as they are.
