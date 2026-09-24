# #62 — the test architecture after 1.3.0: retire the UI-driven release harness

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development, under the
> review budget and testing hierarchy in `docs/superpowers/planning-policy.md`. One brief, one
> implementer, one task review, at most one scoped re-review, controller inspection for mechanical
> fixes. Steps use `- [ ]` only in the gate.

**Goal:** prove the two Android boundaries that matter for share intake with a real foreign UID in
seconds, from the connected suite, and make it structurally impossible for a future release to
rebuild a twelve-minute `uiautomator` journey without noticing.

**Architecture:** a test-only Android application module `:share-test-sender` (its own package
and UID, a `FileProvider` over fictional fixtures, three commands) is installed beside the app by
the same Gradle invocation that runs the connected suite. One connected class in `:app`,
`ShareBoundaryTest`, asks the sender to fire a real `ACTION_SEND` at ServiceTag, waits for
`ShareIntakeActivity` to resume, and asserts the rendered form through Compose semantics. One
connected contract class, `ShareResolutionContractTest`, asks `PackageManager` what resolves.
Everything the retired harness used to drive by screen scraping stays where it already lives:
`ShareIntakeScreenTest`, `SharedItemReaderTest`, `ShareIntakeViewModelTest`, `ManifestContractTest`.
A committed runbook and a tripwire replace the per-release §16 harness.

**Tech stack:** AGP 9.4 application module, `androidx.core.content.FileProvider`, AndroidX test
(`ActivityLifecycleMonitorRegistry`, Compose `createEmptyComposeRule`), JUnit 4 `Timeout`.

**Spec:** GitHub issue #62 (body + the owner's ruling comment of 2026-09-23) and
`docs/superpowers/planning-policy.md` § "Testing hierarchy" — the binding authority.

## Global constraints

- **No production change.** `app/src/main/**`, `core/**`, `libs/**`, the manifest, `docs/api/**`
  and `tools/**` are untouched. `app/build.gradle.kts` may gain only the test-time wiring named
  below. No version bump: the app APK is byte-for-byte the 1.3.0 build.
- **Fictional fixtures only** — the `example-mower.invalid` convention; no owner noun, no real
  document, no e-mail, no serial (only `emulator-5554` may be named), no `/home/<user>` path.
- **The sender never ships.** It is an application module with its own `applicationId`
  `com.loosecannon.servicetag.testsender`, built and installed only by test tasks and CI's build
  job; it is not a dependency of `:app`, appears in no release workflow, and `ManifestContractTest`'s
  exported-set assertion (`app/src/main/AndroidManifest.xml`) is unaffected.
- **Zero skips.** The boundary and contract classes never `assume`; if the sender is absent the
  test fails with a sentence naming the Gradle task that installs it.
- **No screen scraping anywhere in tracked code**: no `uiautomator`, no `input tap`, no `dumpsys`
  in any test, script or Gradle task this brief adds.
- **Commits:** identity `git -c user.name=GonzRon -c user.email="$(git log -1 --format=%ae master)"`,
  one casual single-line subject each, no body, no trailers, no attribution.
- Every added test is shown RED first by a named mutation and the report says which.

---

## Task 1 — the foreign-UID sender module

**Files (create):** `share-test-sender/build.gradle.kts`, `share-test-sender/src/main/AndroidManifest.xml`,
`share-test-sender/src/main/kotlin/com/loosecannon/servicetag/testsender/SenderActivity.kt`,
`share-test-sender/src/main/res/xml/fixtures.xml` (the `FileProvider` paths),
`share-test-sender/src/main/assets/fixtures/mower-manual.pdf` (a valid single-page PDF, fictional),
`share-test-sender/src/main/assets/fixtures/mower-shot.png` (a tiny valid PNG), `settings.gradle.kts`
(one `include`), `share-test-sender/README.md`.

**Contract.** `SenderActivity` is exported, has no UI worth looking at, and is driven by intent
extras: `command` ∈ {`send_text`, `send_file`, `send_bad_grant`}, `fixture` ∈ {`mower-manual.pdf`,
`mower-shot.png`} (default `mower-manual.pdf`), `text` (default
`https://example-mower.invalid/xt1/manual.pdf`). On `onCreate` it copies the named fixture from
`assets/` into its own files dir once, builds the intent below, starts it, and finishes itself.

| command | the intent it fires at `com.loosecannon.servicetag/.share.ShareIntakeActivity` |
|---|---|
| `send_text` | `ACTION_SEND`, `type = text/plain`, `EXTRA_TEXT = text` |
| `send_file` | `ACTION_SEND`, `type` from the fixture's extension (`application/pdf`, `image/png`), `EXTRA_STREAM` = the `FileProvider` `content://` URI for the fixture, **`FLAG_GRANT_READ_URI_PERMISSION`** set, `ClipData` set from the URI |
| `send_bad_grant` | as `send_file` **without** the grant flag and without `ClipData` |

The intent carries `FLAG_ACTIVITY_NEW_TASK`. The component is named explicitly (`setClassName`),
so no chooser and no other share target is involved. The `FileProvider` authority is
`com.loosecannon.servicetag.testsender.fixtures`; it is **not** `com.loosecannon.servicetag` or
`com.loosecannon.servicetag.files`, so ServiceTag's I-9 own-authority rule does not apply.

**Invariants:** the sender holds no permission beyond what a `FileProvider` needs; it never reads
ServiceTag's data; it has no launcher icon (`android:exported="true"` on the activity is required
so the app-under-test can start it; the manifest carries a comment saying why).

**Gradle wiring (the only edit to `app/build.gradle.kts`):** `:app:connectedDebugAndroidTest`
depends on `:share-test-sender:installDebug`, so the sender is on the device before any connected
class runs. CI's `build` job gains `:share-test-sender:assembleDebug` so the module cannot rot.

## Task 2 — the boundary and contract classes in `:app`

**Files (create):** `app/src/androidTest/kotlin/com/loosecannon/servicetag/share/ShareBoundaryTest.kt`,
`app/src/androidTest/kotlin/com/loosecannon/servicetag/share/ShareResolutionContractTest.kt`,
`app/src/androidTest/kotlin/com/loosecannon/servicetag/share/TestSender.kt` (the helper that starts
the sender and waits for `ShareIntakeActivity`).

**`ShareBoundaryTest` — exactly three cases, the cap.** Each seeds one fictional asset through the
app's graph in `@Before` (so the intake renders a form, not the no-assets dead end), starts
`SenderActivity` with the command via `InstrumentationRegistry.getInstrumentation().targetContext`,
waits (≤ 10 s) for a `ShareIntakeActivity` in `RESUMED` through
`ActivityLifecycleMonitorRegistry`, asserts with `createEmptyComposeRule()` bound to that activity,
then finishes it and removes the asset in `@After`. Nothing is saved; no text is typed.

| case | proves (the boundary named, as the policy requires) | assertion |
|---|---|---|
| `anExternalTextShareFromAnotherUidReachesTheIntake` | Android delivered `ACTION_SEND + EXTRA_TEXT` from a foreign UID to the exported activity | title "Save to ServiceTag", the RECEIVED section shows the fixture URI, no dead-end sentence |
| `anExternalStreamWithAGenuineGrantReachesTheByteForm` | a `content://` URI from another package's `FileProvider` with a real temporary read grant is readable by the intake | title, RECEIVED shows `mower-manual.pdf`, the TYPE control is drawn, and no "Could not read what was shared" |
| `anExternalStreamWithoutAGrantIsRefusedNotCrashed` | the app survives an ungranted foreign URI exactly as the intake specifies | the ratified read-failure dead end "Could not read what was shared" with Close, and the activity is alive |

Each case carries a JUnit `Timeout` of 20 s — the tripwire lives in the test: a proof that takes
longer than a screen-scraping journey fails on time alone.

**`ShareResolutionContractTest` — no navigation.** Through `packageManager.queryIntentActivities`:
`ACTION_SEND` with `text/plain`, `application/pdf` and `image/jpeg` each resolve to exactly
`ShareIntakeActivity` within this package; `ACTION_SEND_MULTIPLE` with `application/pdf` resolves to
nothing in this package. This is the framework-level twin of `ManifestContractTest`'s source-manifest
assertions and replaces the shell's `query-activities` step.

**Test matrix — what already lives in process and is NOT duplicated here:** choosing an asset, typing
a name, Save, Cancel, every refusal sentence, the uri-list arm, the caps, the process-death case
(`ShareIntakeScreenTest`, `ShareIntakeViewModelTest`, `SharedItemReaderTest`, `SharedItemLiftTest`).
The report lists each of those with the class that owns it.

## Task 3 — the runbook and the tripwire

**Files:** create `docs/release-proofs.md`; modify `docs/superpowers/planning-policy.md` (one link
line under "Testing hierarchy"); modify `.github/workflows/ci.yml` (the assemble line above).

`docs/release-proofs.md` is the standing runbook every release's §16 now points at instead of
restating it. Seven rows, each with the exact command and the layer that owns it:

| proof | command | budget |
|---|---|---|
| R1 unit gate from scratch | `./gradlew --rerun-tasks :nfc-core:test :nfc-android:testDebugUnitTest :core:test :app:testDebugUnitTest` | — |
| R2 connected suite (includes the boundary and contract classes) | `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` | zero skips |
| R3 the Python suites | the three `uv run --frozen pytest` (note the projects' own `-q`) | — |
| R4 external boundary | **is R2's `ShareBoundaryTest`** — three cases, 20 s each; nothing else may be added to this row without naming a new OS boundary | ≤ 60 s |
| R5 structural | `ManifestContractTest`, `MergedManifestContractTest`, `VersionAgreementTest` (inside R1) + the XML exported-set parser line | — |
| R6 hygiene | the range greps (no e-mail, home path, serial, attribution; one author; empty bodies) | — |
| R7 signed-APK upgrade smoke | install the verified release over the previous one on the development phone in place; same `firstInstallTime` and UID; counts unchanged | one install |

Also in the runbook: the environment notes that cost a day (the emulator's `stylus_handwriting_enabled`
must be 0 for any test that types through adb — none of these do; the shell cannot delegate a SAF
URI, which is why the sender exists), and the rule in one sentence: *a black-box test must name the
OS boundary it uniquely demonstrates or it is not added.*

**Tripwire beyond the per-case timeout:** a JVM test `ReleaseProofPolicyTest` in `app/src/test/…/policy/`
asserts that no tracked file under `tools/`, `app/src/androidTest/`, `share-test-sender/` or
`.github/` contains `uiautomator`, `input tap`, `input text` or `dumpsys` (anchored, whole tokens),
and that `docs/release-proofs.md` names `ShareBoundaryTest` as the only R4 row. A future harness
either edits this test in the open or fails CI.

## Gate

- [ ] `./gradlew :share-test-sender:assembleDebug` builds; the sender APK's badging shows package
      `com.loosecannon.servicetag.testsender` and no `<uses-permission>` beyond none.
- [ ] `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` green; the unit
      count moves by exactly the policy test's cases (say how many).
- [ ] `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest` — whole suite green,
      zero skips, `ShareBoundaryTest` 3/0 and `ShareResolutionContractTest` green, and the boundary
      class's wall-clock printed (expected well under 60 s total).
- [ ] RED proofs: the text case reddens when the sender's `send_text` omits `EXTRA_TEXT`; the grant
      case reddens when `send_file` drops the grant flag (it must then look like the bad-grant case);
      the bad-grant case reddens when the assertion expects the byte form; the resolution test
      reddens when the expected component is misspelt; the policy test reddens when a `uiautomator`
      token is planted in a scratch file under `tools/`. Each shown, then reverted.
- [ ] `git diff --stat master..HEAD -- app/src/main core libs tools docs/api` → empty.
- [ ] `grep -rn 'uiautomator\|input tap\|dumpsys' share-test-sender app/src/androidTest .github` → none.
- [ ] Hygiene over the range; gitlink `7e0377a`; `git status` clean.

## This brief must NOT

Add a fourth boundary case. Type text through adb. Touch a phone. Drive DocumentsUI, Gboard or
MediaStore. Change any sentence the app shows. Add the sender to any release artifact. Rebuild a
counts helper or a Developer API re-pairing loop. Expand into 1.4 product work.
